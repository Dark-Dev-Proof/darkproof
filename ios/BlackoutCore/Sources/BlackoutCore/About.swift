import Foundation

/// What a group message is about (spec/FORMAT.md, "Replies and reactions"): a 5th plaintext field, after the photo
/// field, names one earlier message. The field is inside the signed body, so it is the sender's own statement; what the
/// target says is always taken from the reader's own copy of it, never from the message that names it.
/// Same as Android's About.
public enum About: Equatable, Sendable {
    /// Type `0x01`: an ordinary message, shown with a quote of its target.
    case reply(Target)
    /// Type `0x02` with a listed reaction's text, or none: never a chat row, counted under its target (`Reactions`).
    case reaction(Target)
    /// Type `0x02` with any other text (`Reaction.accepts`): nothing of it is shown, neither as a reaction nor as a
    /// message. Never written.
    case nothing

    public static let replyType: UInt8 = 0x01, reactionType: UInt8 = 0x02
    /// Type (1), the target's packet id (8), the first 8 bytes of the target's signed digest (8).
    public static let size = 17

    /// One message: its packet id and the first 8 bytes of the digest its signature is over (SHA-256 of the signed
    /// message, spec "Signatures", "Message keys"). The digest covers the id, the time and the whole body, not the IV
    /// or the ciphertext: a copy of the message sealed again is the same target on every phone that opened and checked
    /// it, and another message sent under the same id is not. It is what a chat tells messages apart by
    /// (`GroupCrypto.Opened.mark`).
    public struct Target: Equatable, Hashable, Sendable {
        /// 16 lowercase hex chars each.
        public let id: String, mark: String
        /// As `GroupCrypto.Opened.mark` writes it: "<id>:<first 8 bytes of the signed digest>".
        public var key: String { id + ":" + mark }

        /// What names the message an opened packet carries.
        public init(_ o: GroupCrypto.Opened) {
            id = String(o.message.id.prefix(16)); mark = Hex.encode(Data(o.digest.prefix(8)))
        }

        /// From `key`; nil unless it is two runs of 16 lowercase hex chars with ":" between.
        public init?(key: String) {
            let parts = key.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 2, Senders.isMemberId(String(parts[0])), Senders.isMemberId(String(parts[1])) else { return nil }
            id = String(parts[0]); mark = String(parts[1])
        }

        fileprivate init(id: String, mark: String) { self.id = id; self.mark = mark }
    }

    /// The target, for a reply or a reaction.
    public var target: Target? {
        switch self {
        case .reply(let t), .reaction(let t): return t
        case .nothing: return nil
        }
    }

    /// True for a message that is never a chat row: a reaction, or a type `0x02` message that isn't one. It is not
    /// alerted, not counted as unread, not confirmed with a receipt, and shows no "Delivered".
    public var isSilent: Bool { if case .reply = self { return false }; return true }

    /// The 5th field as it is written, or nil for `.nothing`, which is never written.
    public var field: Data? {
        switch self {
        case .reply(let t): return Data([Self.replyType]) + Hex.decode(t.id) + Hex.decode(t.mark)
        case .reaction(let t): return Data([Self.reactionType]) + Hex.decode(t.id) + Hex.decode(t.mark)
        case .nothing: return nil
        }
    }

    /// What a reader makes of a 5th field. `field`: its bytes, nil if the body ends before it. `text`: the bytes of the
    /// message's `text` field. Nil, an ordinary message, for a field of another length or another type: padding reads
    /// as an empty 5th field, and newer apps may write types this one doesn't know.
    public static func read(field: Data?, text: Data) -> About? {
        guard let field, field.count == size else { return nil }
        let b = [UInt8](field)
        let t = Target(id: Hex.encode(Data(b[1..<9])), mark: Hex.encode(Data(b[9..<17])))
        switch b[0] {
        case replyType: return .reply(t)
        case reactionType: return Reaction.accepts(text) ? .reaction(t) : .nothing
        default: return nil
        }
    }

    /// What a reply or reaction to `m` names, or nil if `m` can't be one's target: only a group message this phone shows
    /// as a message, whose signature checked. `group`: the group the reply or reaction is written to (`Message.group`);
    /// a message of another group is no target.
    public static func target(of m: Message, group: String?) -> Target? {
        guard let group, !m.isPublic, m.verified, m.group == group, m.about?.isSilent != true else { return nil }
        return m.mark
    }

    /// The reader's own messages of one group that can be a target, by `Target.key`. `rows`: what the phone shows.
    /// A reply or reaction is looked up here and nowhere else, so nothing is ever shown for a target that is on the
    /// Public channel, in another group, not checked yet, or itself a reaction.
    public static func targets(in rows: some Sequence<Message>, group: String?) -> [String: Message] {
        var out: [String: Message] = [:]
        for m in rows { if let t = target(of: m, group: group), out[t.key] == nil { out[t.key] = m } }
        return out
    }
}

/// A reaction (spec/FORMAT.md, "Reactions"): a group message of type `0x02` whose `text` is one of the listed
/// reactions, byte for byte, or empty. Nothing else is ever shown under a message, so nobody can put text there.
/// Same as Android's.
public enum Reaction {
    /// No reaction is ever listed that is longer than this.
    public static let maxBytes = 16
    /// The spec's list, in its order; the heart carries its variation selector (`e2 9d a4 ef b8 8f`). `✅` is left
    /// out on purpose: an app that doesn't know reactions shows a reaction as an ordinary message, and any message
    /// that starts with `✅` is an "I'm safe" check-in there (`CheckIn.of`).
    public static let listed = ["\u{1F44D}", "\u{2764}\u{FE0F}", "\u{1F602}", "\u{1F62E}", "\u{1F64F}"]
    /// What the apps offer: the whole list.
    public static var offered: [String] { listed }
    private static let bytes: [[UInt8]] = listed.map { Array($0.utf8) }

    /// Where `text` stands in the list, comparing bytes; nil if it isn't listed.
    static func place(_ text: some Sequence<UInt8>) -> Int? { let t = Array(text); return bytes.firstIndex(of: t) }

    /// True if the bytes of a `text` field are a reaction's: exactly a listed reaction, or none at all, which takes
    /// the sender's reaction back. Anything else under type `0x02` is not a reaction and is not shown at all.
    public static func accepts(_ text: Data) -> Bool { text.isEmpty || place(text) != nil }

    /// True if this app may send `text` as a reaction: only a listed one.
    public static func sendable(_ text: String) -> Bool { place(text.utf8) != nil }
}

/// Every member's reactions this phone holds, and which of them count (spec/FORMAT.md, "Reactions"). Built only from
/// packets a group key opened and whose signature checked for a member; what is shown follows from the set of those
/// packets alone, whatever order they came in. Same as Android's Reactions.
public struct Reactions: Equatable, Sendable {
    /// One reaction packet. `group`: the id of the group whose key opened it. `target`: `About.Target.key`. `sender`:
    /// the member (this phone's own mark for its own). `text`: "" takes the reaction back. `id`: the packet's id.
    public struct Entry: Equatable, Sendable {
        public let group: String, target: String, sender: String, text: String, ts: Int64, id: String
        public init(group: String, target: String, sender: String, text: String, ts: Int64, id: String) {
            self.group = group; self.target = target; self.text = text; self.ts = ts; self.id = id
            self.sender = sender.hasPrefix("web-") ? String(sender.dropFirst(4)) : sender // a `web-` in front doesn't count
        }
        /// The later one counts; at the same time, the greater packet id (16 lowercase hex chars, so text order is number order).
        fileprivate func beats(_ o: Entry) -> Bool { (ts, id) > (o.ts, o.id) }
    }

    /// A reaction under a message: how many members have it now, and whether this phone's user is one of them.
    public struct Chip: Equatable, Sendable, Identifiable {
        public let text: String, count: Int, mine: Bool
        public var id: String { text }
    }

    private var entries: [String: Entry] = [:]       // the name a reaction's packet goes by in the store -> what it says
    private var targets: [String: Set<String>] = [:] // group and target -> the names of the reactions to it

    public init() {}

    private static func place(_ group: String, _ target: String) -> String { group + "|" + target }

    /// A checked reaction. `name`: what its packet goes by in the store (`StoreCap.name`), so that it leaves with the
    /// packet (`forget`). Adding the same one again changes nothing. One whose text is neither listed nor empty
    /// isn't taken at all (`About.read` lets none through): it must not replace the member's reaction either.
    public mutating func add(_ name: String, _ e: Entry) {
        guard entries[name] == nil, Reaction.accepts(Data(e.text.utf8)) else { return }
        entries[name] = e
        targets[Self.place(e.group, e.target), default: []].insert(name)
    }

    /// The packets `names` left the store: their reactions no longer count.
    public mutating func forget<S: Sequence>(_ names: S) where S.Element == String {
        for name in names {
            guard let e = entries.removeValue(forKey: name) else { continue }
            let p = Self.place(e.group, e.target)
            targets[p]?.remove(name)
            if targets[p]?.isEmpty == true { targets[p] = nil }
        }
    }

    public var count: Int { entries.count }

    /// Each member's reaction to `target` that counts: one per member, their latest. `hidden`: members whose
    /// reactions this phone doesn't show (blocked here).
    public func current(group: String, target: String, hidden: (String) -> Bool = { _ in false }) -> [String: Entry] {
        var out: [String: Entry] = [:]
        for name in targets[Self.place(group, target)] ?? [] {
            guard let e = entries[name], !hidden(e.sender) else { continue }
            if let had = out[e.sender], !e.beats(had) { continue }
            out[e.sender] = e
        }
        return out
    }

    /// This phone's own reaction to `target` that counts ("" once taken back), or nil if it never reacted.
    public func mine(group: String, target: String, me: String) -> Entry? { current(group: group, target: target)[me] }

    /// What is shown under `target`: each listed reaction that a member has, with its count, in the list's order. A
    /// member who took theirs back counts for none.
    public func chips(group: String, target: String, me: String, hidden: (String) -> Bool = { _ in false }) -> [Chip] {
        var counts = [(n: Int, mine: Bool)](repeating: (0, false), count: Reaction.listed.count)
        for e in current(group: group, target: target, hidden: hidden).values {
            guard let i = Reaction.place(e.text.utf8) else { continue }
            counts[i].n += 1; if e.sender == me { counts[i].mine = true }
        }
        return counts.indices.filter { counts[$0].n > 0 }.map { Chip(text: Reaction.listed[$0], count: counts[$0].n, mine: counts[$0].mine) }
    }
}

/// The quote above a reply and the strip above the composer (spec/FORMAT.md, "Replies"): built from the reader's own
/// copy of the target.
public enum Quote {
    /// Shown instead of a quote when the phone has no checked copy of the target: it expired, never arrived, or its
    /// signature hasn't checked.
    public static let missing = "A message this phone doesn't have"
    /// Shown instead when the target is here but hidden on this phone: its sender is blocked, or the message was hidden or reported.
    public static let hidden = "A message hidden on this phone"
    /// Longer first lines are cut: the screen shows one line anyway.
    public static let maxChars = 120

    /// The first line of what `m` says: a photo says its caption, or "Photo"; anything else the first line of its text
    /// that isn't empty.
    public static func line(of m: Message) -> String {
        let text = m.photo == nil ? m.text : Photo.caption(m.text)
        let first = text.split(whereSeparator: \.isNewline).lazy.map { $0.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty } ?? ""
        if first.isEmpty { return m.photo == nil ? "" : "Photo" }
        return first.count > maxChars ? String(first.prefix(maxChars)) + "…" : first
    }

    /// Whose message it is, as the reader sees it. `me`: this phone's mark for its own messages.
    public static func name(of m: Message, me: String) -> String { m.sender == me ? "You" : (m.name.isEmpty ? "Someone" : m.name) }
}

extension Message {
    /// A Public channel message as readers show it (spec/FORMAT.md, "Photos", "Voice notes", "Replies and reactions"):
    /// photos, voice notes and the 5th field are for groups only, so they are dropped there, whatever the packet carries. `mine`: written on
    /// this phone; in other people's posts offensive words are masked (`WordFilter`).
    public func onPublicChannel(mine: Bool) -> Message {
        var m = Message(id: id, ts: ts, sender: sender, name: mine ? name : WordFilter.clean(name), text: mine ? text : WordFilter.clean(text), isPublic: true)
        m.verified = verified; m.mark = mark // no `about` and no `notice`: the 5th field means nothing on the Public channel
        return m
    }
}
