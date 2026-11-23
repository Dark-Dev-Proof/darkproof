import Foundation

// Rules of high-risk mode and of leaving a group (Settings), kept in the core so unit tests run. The Android app
// follows the same ones.

/// High-risk mode, "Keeps less": the messages and receipts of the groups this phone is in leave it a day after they
/// reached it. Packets this phone can't open and only carries for others, the Everyone channel, and what a group runs
/// on (its members' introductions and its new keys) keep the normal rules (`StoreCap`, `Keyring.keepMs`).
public enum KeepsLess {
    public static let keepMs: Int64 = 24 * 3_600_000

    /// What a stored packet opened as on this phone.
    public enum Opened: Sendable { case message, receipt, introduction, newKey, nothing }

    /// True if the rule covers a packet: one that opened as a message (a reaction is one) or a receipt of a group here,
    /// and wasn't thrown out by its signature. Decided by what the packet is, never by the mailbox in its id: a group's
    /// door can share its 16-bit mailbox with a message key, and its introductions and new keys must stay.
    /// `isPublic`: of the Everyone channel. `forged`: its signature failed, or its sender isn't in the group.
    public static func covers(_ opened: Opened, isPublic: Bool, forged: Bool = false) -> Bool {
        !isPublic && !forged && (opened == .message || opened == .receipt)
    }

    /// A stored packet the rule covers, by the name it goes by in the store. `since`: when it reached this phone, by this
    /// phone's clock then; for one written here, when it was first handed to a link ("Sent"). Nil: written here and not
    /// handed to anyone yet, so it never goes by this rule: an unsent "I need help" must not vanish.
    public struct Held: Sendable, Equatable {
        public let name: String, since: Int64?
        public init(name: String, since: Int64?) { self.name = name; self.since = since }
    }

    /// Since when a covered packet counts that has no time noted when the store is read; nil: none, it stays.
    /// - `beforeTimes`: it was stored before this phone noted times (the first reading of the store by a version that
    ///   does). Then it counts from its own `date`. Otherwise its time just wasn't saved when the app ended, and it
    ///   counts from `now`: never shorter than a day after it came.
    /// - `own` and `receipt`: a receipt written here counts from when it was written.
    /// - `own`, not a receipt: a message (or reaction) written here. `handedOff` nil: it has not left this phone, gets
    ///   no time and stays. Otherwise it left, at that time if the phone kept it (the inner value).
    /// A date ahead of the clock counts as `now`.
    public static func sinceUnnoted(own: Bool, receipt: Bool, date: Int64, handedOff: Int64??, beforeTimes: Bool, now: Int64) -> Int64? {
        let its = min(date, now)
        if own && receipt { return its }
        if own { return handedOff.map { min($0 ?? (beforeTimes ? its : now), now) } }
        return beforeTimes ? its : now
    }

    /// The names that go now: on this phone for more than a day. When a message was written doesn't count: one that
    /// arrives late is still readable for a day.
    /// `now`: this phone's clock. Only a `since` that is more than a day in the past counts, so a clock that was set back
    /// removes less, never more. A clock moved forward by days makes messages leave early: that is accepted, the mode
    /// exists to keep less, and there is no workaround for it here.
    public static func expired(_ held: some Collection<Held>, now: Int64) -> [String] {
        let (cut, over) = now.subtractingReportingOverflow(keepMs)
        guard !over else { return [] }
        return held.filter { h in h.since.map { $0 < cut } ?? false }.map(\.name)
    }
}

/// What "Keeps less" remembers next to the packet store, none of it content: since when each covered packet has been on
/// this phone, and the gone list: the packets the rule removed. Other phones carry a packet for 7 days, so one that was
/// removed here can arrive again: it is then passed on like a packet this phone can't open, but never shown, alerted,
/// counted as unread, confirmed or copied again, and it goes back into the store only if no key on this phone opens
/// it any more (`storesAgain`). Packets go by their key (`MeshRouter.key`: id and content), which doesn't change with
/// their place in the store. Not thread-safe.
public struct DayBook: Sendable, Equatable {
    /// The most packets the gone list names: beyond it the oldest go.
    public static let maxGone = 5_000
    /// How long other phones pass a packet on (`MeshRouter`): one older than this can't come back.
    public static let carryMs: Int64 = 7 * 24 * 3_600_000
    /// A removed packet: its header time, and the id of the group it was a message or receipt of.
    public struct Gone: Sendable, Equatable {
        public let ts: Int64, group: String
        public init(ts: Int64, group: String) { self.ts = ts; self.group = group }
    }

    public private(set) var since: [String: Int64] = [:]
    public private(set) var gone: [String: Gone] = [:]
    private var goneIds: [String: Int] = [:] // packet id -> how many gone packets carry it: most packets are settled by this look

    public init() {}

    public var isEmpty: Bool { since.isEmpty && gone.isEmpty }

    // MARK: Since when

    /// Since when the packet `key` has been on this phone, if that was noted.
    public func since(_ key: String) -> Int64? { since[key] }

    /// The packet `key` reached this phone, or, written here, first left it, at `now`. The first time counts: a copy that
    /// comes again later, or a second link it is handed to, changes nothing. True if it was noted.
    @discardableResult public mutating func note(_ key: String, at now: Int64) -> Bool {
        guard since[key] == nil else { return false }
        since[key] = now
        return true
    }

    /// These packets left the store by another rule (their key expired, the store was full): nothing is kept of them.
    public mutating func forget(_ keys: some Sequence<String>) { for k in keys { since[k] = nil } }

    /// Forgets since when for every packet that is no longer stored. True if anything went.
    @discardableResult public mutating func keepOnly(_ stored: Set<String>) -> Bool {
        let n = since.count
        since = since.filter { stored.contains($0.key) }
        return since.count != n
    }

    // MARK: The gone list

    /// "Keeps less" removed the packet `key`: it goes on the gone list, with its header time `ts` and its group's id.
    public mutating func remove(_ key: String, ts: Int64, group: String) {
        since[key] = nil
        if gone.updateValue(Gone(ts: ts, group: group), forKey: key) == nil { goneIds[Self.id(key), default: 0] += 1 }
        while gone.count > Self.maxGone, let oldest = gone.min(by: { ($0.value.ts, $0.key) < ($1.value.ts, $1.key) }) { drop(oldest.key) }
    }

    /// True if "Keeps less" removed this very packet: another packet under its id is not it.
    public func isGone(_ p: Packet) -> Bool { goneIds[p.id] != nil && gone[MeshRouter.key(p)] != nil }
    public func isGone(key: String) -> Bool { gone[key] != nil }

    /// What a packet that arrives, or is read from the store, is counted as: `.unreadable` for one on the gone list,
    /// which is never shown again; nil for any other.
    public func kind(of p: Packet) -> StoreCap.Kind? { isGone(p) ? .unreadable : nil }

    /// Whether a packet on the gone list that arrived again is written to the store, to be carried for others across a
    /// restart. Only if nothing on this phone opens it any more (`stillOpens` false: its own message key was deleted when
    /// it was read). One that a held key still opens (a receipt, a message whose key couldn't be deleted yet) is passed
    /// on from the router's memory and not written: what this phone could read never returns to its store.
    public static func storesAgain(stillOpens: Bool) -> Bool { !stillOpens }

    /// Drops the names of packets older than `carryMs` by their header time: no phone passes them on any more. Called
    /// after the store's own clean-up has dropped such packets, never before: a stored copy must not outlive its name
    /// here. A clock that was set back drops fewer. True if any went.
    @discardableResult public mutating func expire(now: Int64) -> Bool {
        let (cut, over) = now.subtractingReportingOverflow(Self.carryMs)
        guard !over else { return false }
        let old = gone.filter { $0.value.ts < cut }.map(\.key)
        old.forEach { drop($0) }
        return !old.isEmpty
    }

    /// A group was left: nothing of it stays on the gone list. True if any went.
    @discardableResult public mutating func forget(group: String) -> Bool {
        let its = gone.filter { $0.value.group == group }.map(\.key)
        its.forEach { drop($0) }
        return !its.isEmpty
    }

    private mutating func drop(_ key: String) {
        guard gone.removeValue(forKey: key) != nil else { return }
        let id = Self.id(key), n = (goneIds[id] ?? 1) - 1
        goneIds[id] = n > 0 ? n : nil
    }

    private static func id(_ key: String) -> String { String(key.prefix(16)) }

    // MARK: Saved on the phone

    /// As it is saved: a line "s <key> <since>" for each packet noted, "g <key> <header time> <group id>" for each gone.
    public var saved: Data {
        var out = ""
        for (k, t) in since.sorted(by: { $0.key < $1.key }) { out += "s \(k) \(t)\n" }
        for (k, g) in gone.sorted(by: { $0.key < $1.key }) { out += "g \(k) \(g.ts) \(g.group)\n" }
        return Data(out.utf8)
    }

    /// From what `saved` wrote. A line that doesn't read is left out; the rest counts.
    public init(saved: Data) {
        for line in String(decoding: saved, as: UTF8.self).split(separator: "\n") {
            let f = line.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
            if f.count == 3, f[0] == "s", !f[1].isEmpty, let t = Int64(f[2]) { since[f[1]] = t }
            else if f.count == 4, f[0] == "g", !f[1].isEmpty, let t = Int64(f[2]) { remove(f[1], ts: t, group: f[3]) }
        }
    }
}

/// High-risk mode, "Locks every time".
public enum HighRiskLock {
    /// Like `LockGrace.asksAgain` without the minute: the lock asks whenever the app was left at all.
    /// `awaySeconds`: nil if the app wasn't left since it was last unlocked; any number, also one that can't be, asks.
    public static func asksAgain(unlockedOnce: Bool, awaySeconds: Double?) -> Bool { !unlockedOnce || awaySeconds != nil }
}

/// Leaving a group (Settings): what the phone keeps under the group's id, outside the packet store. The id is 32 hex
/// characters and never changes for the group (`GroupCrypto.groupId`), so nothing of another group matches.
public enum LeftGroup {
    /// Of the key store's entry names, the ones of group `id`: the name is the id (the member key), ends with "." and the
    /// id (the group's keys and its encryption keys' first), or has the id between dots (its later encryption keys).
    public static func secrets(_ names: some Collection<String>, group id: String) -> [String] {
        guard !id.isEmpty else { return [] }
        return names.filter { $0 == id || $0.hasSuffix("." + id) || $0.contains("." + id + ".") }
    }

    /// The saved preferences without the group. `remove`: keys that go whole (they end with "." and the id). `change`:
    /// lists and tables that lose their entries of the group, with what is left of each:
    /// - a list loses the entries that start with the id and "|" (people checked there), that are one of `messages` (ids
    ///   of the group's messages: handed off, hidden, reported), or that name one of `tags` on either side of ">" (where
    ///   answers under old alerts go);
    /// - a table loses the keys that start with the id and "|" (people blocked there), are one of `tags` (what was
    ///   read), or are one of `messages` (over which link a message of the group left this phone).
    /// `tags`: the tag of each code the group has or had (`GroupTag`).
    public static func preferences(_ all: [String: Any], group id: String, tags: Set<String>, messages: Set<String>) -> (remove: [String], change: [String: Any]) {
        guard !id.isEmpty else { return ([], [:]) }
        var remove: [String] = [], change: [String: Any] = [:]
        for (key, value) in all {
            if key.hasSuffix("." + id) { remove.append(key); continue }
            if let list = value as? [String] {
                let kept = list.filter { e in
                    if e.hasPrefix(id + "|") || messages.contains(e) { return false }
                    let sides = e.split(separator: ">", omittingEmptySubsequences: false)
                    return !(sides.count == 2 && (tags.contains(String(sides[0])) || tags.contains(String(sides[1]))))
                }
                if kept.count != list.count { change[key] = kept }
            } else if let table = value as? [String: Any] {
                let kept = table.filter { !$0.key.hasPrefix(id + "|") && !tags.contains($0.key) && !messages.contains($0.key) }
                if kept.count != table.count { change[key] = kept }
            }
        }
        return (remove.sorted(), change)
    }
}

/// High-risk mode (Settings), off unless switched on: saved like the app's other switches.
public enum HighRiskSetting {
    public static let key = "highRisk"
    public static func isOn(_ d: UserDefaults = .standard) -> Bool { d.bool(forKey: key) }

    /// The line under "Says less." on the sheet that switches it on. Find by Bluetooth is named only while its trial is
    /// on (`FinderNote.trialKey`): otherwise nobody has seen it (screens re-check, finding 7). Same words as Android.
    public static func saysLess(finderTrial: Bool) -> String {
        finderTrial ? "No Find by Bluetooth, and receipts don't say how a message arrived." : "Receipts don't say how a message arrived."
    }
}

/// "Erase everything" (Settings): emptying the folders the app writes in, and looking at what is left afterwards. Plain
/// file calls, so unit tests run them on real folders. Hidden files and folders count like any other; a link is
/// removed, never followed.
public enum FolderWipe {
    /// Deletes everything inside `folder`, not the folder itself: the app's code expects its folders to be there.
    /// `keep`: names directly inside it that are left alone.
    public static func empty(_ folder: URL, keep: Set<String> = []) {
        let fm = FileManager.default
        for name in (try? fm.contentsOfDirectory(atPath: folder.path)) ?? [] where !keep.contains(name) {
            try? fm.removeItem(at: folder.appendingPathComponent(name))
        }
    }

    /// Every file still under `folder`, as its path from there: what `empty` could not delete, or what was written
    /// since. Folders themselves don't count. `keep`: names directly inside it that aren't looked at.
    public static func files(in folder: URL, keep: Set<String> = []) -> [String] {
        let fm = FileManager.default
        var out: [String] = []
        // A real folder; a link to one counts as a file, whatever it points at.
        func isFolder(_ path: String) -> Bool {
            let v = try? URL(fileURLWithPath: path).resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            return v?.isDirectory == true && v?.isSymbolicLink != true
        }
        for name in (try? fm.contentsOfDirectory(atPath: folder.path)) ?? [] where !keep.contains(name) {
            let path = folder.appendingPathComponent(name).path
            guard isFolder(path) else { out.append(name); continue }
            for sub in (try? fm.subpathsOfDirectory(atPath: path)) ?? [] where !isFolder(path + "/" + sub) { out.append(name + "/" + sub) }
        }
        return out.sorted()
    }
}
