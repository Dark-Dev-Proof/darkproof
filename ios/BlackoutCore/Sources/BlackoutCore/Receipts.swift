import Foundation

/// A delivery receipt says "I got these messages" (spec/FORMAT.md, "Delivery receipts"). It travels as an ordinary
/// packet sealed with the group's receipt key; its text is the confirmed message ids separated by spaces. An id may be
/// followed by one journey note (spec "Journey notes"), which a reader that doesn't know notes skips like anything
/// else that isn't an id.
public enum Receipt {
    /// Most ids in one receipt.
    public static let maxIds = 200

    public static func text(for ids: [String]) -> String { ids.joined(separator: " ") }

    /// The message ids a receipt confirms: its first `maxIds`. Whatever follows them is not read. Anything that isn't
    /// 16 lowercase hex characters is skipped. This is the whole rule of a reader that doesn't know journey notes.
    public static func ids(in text: String) -> [String] {
        Array(text.utf8.split(separator: 32).lazy.filter(isId).prefix(maxIds).map { String(decoding: $0, as: UTF8.self) })
    }

    private static func isId(_ t: Substring.UTF8View) -> Bool {
        t.count == 16 && t.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }

    /// Splits ids into receipt-sized batches.
    public static func batches(_ ids: [String]) -> [[String]] {
        stride(from: 0, to: ids.count, by: maxIds).map { Array(ids[$0..<min($0 + maxIds, ids.count)]) }
    }

    /// A confirmed message and, if the receipt says it, how it reached the reader.
    public struct Entry: Equatable, Sendable {
        public let id: String
        public var note: Journey.Note?
        public init(id: String, note: Journey.Note? = nil) { self.id = id; self.note = note }
    }

    /// What a receipt confirms, with journey notes: the same ids as `ids(in:)`, in the same order, each with the note
    /// in the token right after it, if that token is one. A note anywhere else is skipped: at the start, after
    /// something that is no id, or after another note. A note doesn't count towards `maxIds`; nothing is read after
    /// the token that follows the last id counted.
    public static func entries(in text: String) -> [Entry] {
        var out: [Entry] = []
        var afterId = false // the token before this one was an id
        for t in text.utf8.split(separator: 32) {
            if isId(t) {
                if out.count == maxIds { break }
                out.append(Entry(id: String(decoding: t, as: UTF8.self)))
                afterId = true
            } else {
                if afterId, let n = Journey.Note(token: t) { out[out.count - 1].note = n }
                afterId = false
            }
        }
        return out
    }

    /// A receipt's text. `highRisk`: this phone is in high-risk mode, where it adds no journey notes: the receipt is
    /// then exactly what `text(for:)` makes of the ids.
    public static func text(for entries: [Entry], highRisk: Bool) -> String {
        entries.map { e in
            guard !highRisk, let n = e.note?.token else { return e.id }
            return e.id + " " + n
        }.joined(separator: " ")
    }

    /// Splits what is to confirm into receipt-sized batches.
    public static func batches(of entries: [Entry]) -> [[Entry]] {
        stride(from: 0, to: entries.count, by: maxIds).map { Array(entries[$0..<min($0 + maxIds, entries.count)]) }
    }
}

/// Someone who confirmed getting a message.
public struct Reader: Equatable, Sendable {
    public let id: String   // their device id
    public var name: String
    public var ts: Int64    // their clock when they first confirmed it
    /// What their receipt said about the way the message took to them, if anything (`Journey.Note`).
    public var note: Journey.Note?
    public init(id: String, name: String, ts: Int64, note: Journey.Note? = nil) { self.id = id; self.name = name; self.ts = ts; self.note = note }
}

/// Who got which of this phone's own group messages, built from checked receipts (spec "Delivery receipts").
/// A receipt counts for a message only if this phone wrote that message (`sent`) in the group whose receipt key opened
/// the receipt: a member of one saved group can't confirm a message of another, and ids that are no message of this
/// phone's take no memory. Same as Android's Deliveries.
public struct Deliveries: Equatable, Sendable {
    /// Message id -> readers, earliest confirmation first.
    public private(set) var readers: [String: [Reader]] = [:]
    private var groups: [String: String] = [:] // this phone's own group messages: id -> group id

    public init() {}

    /// This phone wrote `messageId` in the group whose id is `group`.
    public mutating func sent(group: String, _ messageId: String) { if groups[messageId] == nil { groups[messageId] = group } }

    /// Adds a checked receipt opened with a receipt key of the group whose id is `group`. A reader counts once per
    /// message, with the time, name and journey note of their earliest receipt for it.
    public mutating func add(group: String, _ r: Message) {
        // An id named twice in one receipt counts as it is named first (`count` keeps the earlier one at equal times).
        for e in Receipt.entries(in: r.text) where groups[e.id] == group {
            count(Reader(id: r.sender, name: r.name, ts: r.ts, note: e.note), for: e.id)
        }
    }

    /// Adds what `other` holds: the messages it knows as written here, and their readers. For a table built off the
    /// main thread from the stored packets.
    public mutating func merge(_ other: Deliveries) {
        for (id, group) in other.groups where groups[id] == nil { groups[id] = group }
        for (id, list) in other.readers {
            guard let group = other.groups[id], groups[id] == group else { continue }
            for r in list { count(r, for: id) }
        }
    }

    /// The messages `ids` left the store.
    public mutating func forget<S: Sequence>(_ ids: S) where S.Element == String {
        for id in ids { groups[id] = nil; readers[id] = nil }
    }

    private mutating func count(_ r: Reader, for id: String) {
        var list = readers[id] ?? []
        if let i = list.firstIndex(where: { $0.id == r.id }) {
            guard r.ts < list[i].ts else { return }
            list[i] = r
        } else {
            list.append(r)
        }
        readers[id] = list.sorted { $0.ts < $1.ts }
    }
}

/// When the next delivery receipt goes out (spec/FORMAT.md, "Delivery receipts"): 2 s after the last message to confirm
/// arrived, or 10 s after the first one, so a burst of arriving messages shares one receipt. Same as Android's.
public enum ReceiptBatch {
    public static let quietMs: Int64 = 2_000
    public static let maxWaitMs: Int64 = 10_000
    public static func due(firstMs: Int64, lastMs: Int64, nowMs: Int64) -> Bool {
        nowMs - lastMs >= quietMs || nowMs - firstMs >= maxWaitMs
    }
}
