import Foundation
import BlackoutCore

/// Append-only file of every packet seen, read back piece by piece (never the whole file at once). The Android app
/// keeps the same list in SQLite. Each record: 4-byte big-endian length, then the encoded packet. Its size is kept
/// under the store cap by Engine (StoreCap), which removes records with `remove`.
final class PacketStore {
    /// What a record goes by (`StoreCap.name`) in `forEachNamed` and `remove`. `.first`: "packets.bin", where the first
    /// record with an id goes by that id and a later one with the same id by its key (an older version wrote a readable
    /// packet after junk sent first under its id). `.key`: "more.bin", the packets that arrived under an id another
    /// packet had already taken: each goes by its key. `.id`: every record by its id (the copies in "history.bin").
    enum Naming { case id, first, key }
    private let naming: Naming
    private let url: URL
    /// The largest cipher a record may hold, written or read: a packet's, or for the copies in "history.bin" `History.maxSealed`.
    /// A longer record is never written, as the reader would take it for damage and stop there.
    private let limit: Int
    private let lock = NSLock() // appends and rewrites; reading needs no lock (a rewrite replaces the file atomically)
    private var closed = false  // lock
    private static let piece = 1 << 20

    /// `file`: "history.bin" holds this phone's readable copies of messages whose keys are deleted (spec "Message keys").
    init(file: String = "packets.bin", naming: Naming? = nil) {
        self.naming = naming ?? (file == "packets.bin" ? .first : .id)
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent(file)
        limit = file == "history.bin" ? History.maxSealed : Packet.maxCipher
        // Protocol v0.5: older packets were sealed with v0.4 keys that no phone uses any more (and no longer decode).
        if file == "packets.bin" && !UserDefaults.standard.bool(forKey: "storeV05") {
            try? FileManager.default.removeItem(at: url)
            UserDefaults.standard.set(true, forKey: "storeV05")
        }
        excludeFromBackup() // a file kept by an earlier version too
    }

    /// The store stays on this phone: never in an iCloud or computer backup (PRIVACY.md, "What stays on your phone").
    /// Set again after each rewrite, which replaces the file.
    private func excludeFromBackup() {
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        var u = url, v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }

    /// The engine this store belongs to was shut down (a group was left, or everything erased): from now on it reads
    /// and writes nothing. The file may be deleted, and the next engine's store may open one of the same name: work
    /// still queued for this one (a ttl to raise, a packet to append) must not reach that file. Any thread.
    func close() { lock.withLock { closed = true } }
    private var isClosed: Bool { lock.withLock { closed } }

    /// Calls `body` with every stored packet, in the order they were stored, reading 1 MB at a time. Any thread; may
    /// run while packets are appended (a torn last record is ignored).
    func forEach(_ body: (Packet) -> Void) {
        guard !isClosed, let h = try? FileHandle(forReadingFrom: url) else { return }
        defer { try? h.close() }
        Self.records(h, limit) { _, p in if let p { body(p) } }
    }

    /// Like `forEach`, with the name each packet goes by (`Naming`).
    func forEachNamed(_ body: (Packet, String) -> Void) {
        guard !isClosed, let h = try? FileHandle(forReadingFrom: url) else { return }
        defer { try? h.close() }
        var seen = Set<String>()
        Self.records(h, limit) { _, p in if let p { body(p, name(p, &seen)) } }
    }

    /// `seen`: the ids of the records before this one.
    private func name(_ p: Packet, _ seen: inout Set<String>) -> String {
        switch naming {
        case .id: return p.id
        case .key: return StoreCap.name(p, first: false)
        case .first: return StoreCap.name(p, first: seen.insert(p.id).inserted)
        }
    }

    /// Removes the packets that go by these names (`Naming`) by writing the others to a new file that then replaces this
    /// one, under the lock, so packets appended meanwhile are kept.
    func remove(_ ids: Set<String>) {
        guard !ids.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        guard !closed, let h = try? FileHandle(forReadingFrom: url) else { return }
        defer { try? h.close() }
        let tmp = url.appendingPathExtension("new")
        guard FileManager.default.createFile(atPath: tmp.path, contents: nil), let out = try? FileHandle(forWritingTo: tmp) else { return }
        var pending = Data(), seen = Set<String>()
        Self.records(h, limit) { record, p in
            guard let p, !ids.contains(name(p, &seen)) else { return } // unreadable records go too
            pending.append(record)
            if pending.count >= Self.piece { try? out.write(contentsOf: pending); pending = Data() }
        }
        try? out.write(contentsOf: pending)
        try? out.close()
        if Foundation.rename(tmp.path, url.path) != 0 { try? FileManager.default.removeItem(at: tmp) }
        excludeFromBackup()
    }

    /// Raises the ttl of stored packets in place: `ttls` is `MeshRouter.key` → the ttl of a copy that came with more
    /// hops left. One pass over the file, under the lock. Nothing is added, so the store cap still counts every record.
    func raise(_ ttls: [String: Int]) {
        guard !ttls.isEmpty else { return }
        let ids = Set(ttls.keys.map { String($0.prefix(16)) }) // a key starts with the packet id: only those records are hashed
        lock.lock(); defer { lock.unlock() }
        guard !closed, let h = try? FileHandle(forUpdating: url) else { return }
        defer { try? h.close() }
        var at: UInt64 = 0, found: [(at: UInt64, ttl: UInt8)] = []
        Self.records(h, limit) { record, p in
            if let p, ids.contains(p.id), let ttl = ttls[MeshRouter.key(p)], ttl > p.ttl, ttl <= Packet.maxTtl {
                found.append((at + 4 + UInt64(Packet.headerSize) - 1, UInt8(ttl))) // the ttl is the last byte of the header
            }
            at += UInt64(record.count)
        }
        for f in found {
            do { try h.seek(toOffset: f.at); try h.write(contentsOf: Data([f.ttl])) } catch { return }
        }
    }

    func append(_ p: Packet) {
        guard p.cipher.count <= limit else { return } // the reader would stop at it, and lose every record after it
        let e = p.encode()
        let rec = Data([UInt8(e.count >> 24 & 0xFF), UInt8(e.count >> 16 & 0xFF), UInt8(e.count >> 8 & 0xFF), UInt8(e.count & 0xFF)]) + e
        lock.lock(); defer { lock.unlock() }
        guard !closed else { return }
        if let h = try? FileHandle(forWritingTo: url) {
            defer { try? h.close() }
            _ = try? h.seekToEnd(); try? h.write(contentsOf: rec)
        } else {
            try? rec.write(to: url)
            excludeFromBackup()
        }
    }

    /// Each whole record (length prefix included) and its packet, or nil if it doesn't decode. Stops at a bad length.
    private static func records(_ h: FileHandle, _ limit: Int, _ body: (Data, Packet?) -> Void) {
        var buf: [UInt8] = []
        while let more = try? h.read(upToCount: piece), !more.isEmpty {
            buf.append(contentsOf: more)
            var i = 0
            while i + 4 <= buf.count {
                let n = Int(buf[i]) << 24 | Int(buf[i + 1]) << 16 | Int(buf[i + 2]) << 8 | Int(buf[i + 3])
                guard n > 0, n <= Packet.headerSize + limit else { return } // damaged: ignore the rest
                guard i + 4 + n <= buf.count else { break }                             // the rest comes with the next piece
                let record = Data(buf[i..<(i + 4 + n)])
                body(record, Packet.decode(record.dropFirst(4), limit: limit))
                i += 4 + n
            }
            buf.removeFirst(i)
        }
    }
}

/// A small file of this phone's own beside the packet stores, sealed with the key of this phone's copies of messages
/// (`Engine.historyKey`, `SealedFile`): never in a backup, and readable from the first unlock on, like that key in the
/// Keychain, so a clock's alert can still be worked out while DarkProof runs in the background with the phone locked.
/// The first unlock is also the earliest an engine starts (the group's code is in the Keychain), so an engine never reads
/// one as empty while it is there. An older version kept these files plain (`plain`): such a file is read once, sealed,
/// and the plain copy deleted.
/// If the Keychain can't give the key right now (`Engine.historyKey` is nil), nothing is sealed, written or removed for
/// being empty, and a sealed file read meanwhile stays as it is until it is read again with the key (`UnreadFiles`): it
/// read as empty, and writing it would lose the clocks and the gone list it holds (re-check N5).
private enum OwnFile {
    private static let lock = NSLock()
    private static var unread = UnreadFiles() // lock

    static func url(_ name: String) -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(name)
    }

    /// The file's contents, or nil if there is none (or it doesn't open). `label`: what it is, which its seal is bound to.
    static func read(_ name: String, plain: String, label: String) -> Data? {
        let (u, p) = (url(name), url(plain))
        let sealed = try? Data(contentsOf: u)
        guard let key = Engine.historyKey else {
            lock.withLock { unread.read(name, lost: sealed != nil) }
            return sealed == nil ? try? Data(contentsOf: p) : nil // an older version's plain copy still reads; it is sealed later
        }
        lock.withLock { unread.read(name, lost: false) }
        let r = SealedFile.load(sealed: sealed, plain: try? Data(contentsOf: p), key: key, label: label)
        if r.migrate, let d = r.data { write(name, d, plain: plain, label: label) }
        else if sealed != nil { try? FileManager.default.removeItem(at: p) } // left over from a move cut short
        return r.data
    }

    /// Seals and writes `data` in one piece; then the plain copy, if one is left, goes. Nothing is written if it can't be
    /// sealed (`UnreadFiles.mayWrite`). True if it was written.
    @discardableResult static func write(_ name: String, _ data: Data, plain: String, label: String) -> Bool {
        let u = url(name), key = Engine.historyKey
        guard let key, lock.withLock({ unread.mayWrite(name, keyKnown: true) }),
              let sealed = SealedFile.seal(data, key: key, label: label) else { return false }
        try? FileManager.default.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard (try? sealed.write(to: u, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil else { return false }
        var f = u, v = URLResourceValues(); v.isExcludedFromBackup = true; try? f.setResourceValues(v)
        try? FileManager.default.removeItem(at: url(plain))
        return true
    }

    /// The book is empty: its file goes, unless it only looks empty because it couldn't be read (`UnreadFiles.mayWrite`).
    static func clear(_ name: String, plain: String) {
        let keyKnown = Engine.historyKey != nil
        guard lock.withLock({ unread.mayWrite(name, keyKnown: keyKnown) }) else { return }
        try? FileManager.default.removeItem(at: url(name))
        try? FileManager.default.removeItem(at: url(plain))
    }

    /// The file goes whatever it holds (its group was left).
    static func remove(_ name: String, plain: String) {
        lock.withLock { unread.removed(name) }
        try? FileManager.default.removeItem(at: url(name))
        try? FileManager.default.removeItem(at: url(plain))
    }
}

/// Where "Keeps less" (high-risk mode) keeps its book (`DayBook`): since when each message and receipt of a group has been
/// on this phone, and the gone list. Packet names and times, no content; sealed all the same (`OwnFile`): in high-risk
/// mode the gone list is a week-long log of how many messages each group had, and when.
enum DayBookFile {
    private static let name = "daybook.sealed", plain = "daybook.txt", label = "daybook"

    static func read() -> DayBook { OwnFile.read(name, plain: plain, label: label).map { DayBook(saved: $0) } ?? DayBook() }

    /// Writes the whole book in one piece, or removes the file when there is nothing to keep.
    static func write(_ book: DayBook) {
        if book.isEmpty { return OwnFile.clear(name, plain: plain) }
        OwnFile.write(name, book.saved, plain: plain, label: label)
    }
}

/// Each group's check-in clocks (`QuietBook`, spec "If I go quiet", rule Q10), with the members' positions: a sealed file
/// per group beside the day book (`OwnFile`), never in the preferences (which go into backups and stay readable on a
/// phone after its first unlock). A book that an older version kept in the preferences, or in a plain file, is moved
/// here when first read.
enum QuietFile {
    private static func name(_ group: String) -> String { "quiet.\(group).sealed" }
    private static func plain(_ group: String) -> String { "quiet.\(group).txt" }
    private static func label(_ group: String) -> String { "quiet.\(group)" }

    static func read(_ group: String) -> [String] {
        if let d = OwnFile.read(name(group), plain: plain(group), label: label(group)) {
            UserDefaults.standard.removeObject(forKey: "quiet.\(group)")
            return String(decoding: d, as: UTF8.self).split(separator: "\n").map(String.init)
        }
        let old = UserDefaults.standard.stringArray(forKey: "quiet.\(group)") ?? []
        if !old.isEmpty, !write(group, old) { return old } // stays in the preferences until it can be sealed
        UserDefaults.standard.removeObject(forKey: "quiet.\(group)")
        return old
    }

    /// Writes the whole book in one piece, or removes the file when there is nothing to keep. False if it couldn't be
    /// sealed and written.
    @discardableResult static func write(_ group: String, _ lines: [String]) -> Bool {
        if lines.isEmpty { OwnFile.clear(name(group), plain: plain(group)); return true }
        return OwnFile.write(name(group), Data(lines.joined(separator: "\n").utf8), plain: plain(group), label: label(group))
    }

    /// The group was left: its clocks go.
    static func remove(_ group: String) {
        OwnFile.remove(name(group), plain: plain(group))
        UserDefaults.standard.removeObject(forKey: "quiet.\(group)")
    }
}
