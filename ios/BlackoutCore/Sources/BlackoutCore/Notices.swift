import Foundation

// The 5th plaintext field beyond replies and reactions (spec/FORMAT.md, "The 5th field"): the group's timer for
// disappearing messages, the note of a phone that leaves, and a member's check-in clock ("If I go quiet"). `About`
// holds types 0x01 and 0x02. Same as Android's.

/// The group's timer for disappearing messages (spec/FORMAT.md, "Disappearing messages"): 5th field type `0x03`, then
/// the seconds as a 32-bit big-endian number. It counts where it is the 5th field of a new key; in a group message it
/// is only a notice for apps that don't follow the timer.
public enum GroupTimer {
    public static let type: UInt8 = 0x03
    /// Type (1), seconds (4).
    public static let size = 5
    /// The shortest timer: a smaller number reads as this.
    public static let floor = 300
    /// The longest: a week. A greater number reads as off, since messages go after one to two weeks anyway.
    public static let max = 604_800
    /// What the apps offer, shortest first; `0` is off.
    public static let choices = [3_600, 28_800, 86_400, 604_800]
    /// A timer of a day or less: alerts in that group carry no message text (spec "Disappearing messages").
    public static let briefMax = 86_400

    /// What a field's number means: 0 off; 1 to 299 the floor; up to a week as written; anything above, off.
    public static func read(seconds raw: UInt32) -> Int {
        if raw == 0 || raw > UInt32(max) { return 0 }
        return Swift.max(Int(raw), floor)
    }

    /// The timer a 5th field carries: nil unless it is exactly `size` bytes of this type.
    public static func read(field: Data?) -> Int? {
        guard let field, field.count == size else { return nil }
        let b = [UInt8](field)
        guard b[0] == type else { return nil }
        return read(seconds: UInt32(b[1]) << 24 | UInt32(b[2]) << 16 | UInt32(b[3]) << 8 | UInt32(b[4]))
    }

    /// The field for a timer of `seconds` (0: off).
    public static func field(_ seconds: Int) -> Data {
        let s = UInt32(clamping: Swift.max(0, seconds))
        return Data([type, UInt8(s >> 24), UInt8(s >> 16 & 255), UInt8(s >> 8 & 255), UInt8(s & 255)])
    }

    /// "1 hour", "8 hours", "1 day", "1 week"; other values in the largest unit that divides them, else in minutes.
    public static func label(_ seconds: Int) -> String {
        func n(_ v: Int, _ unit: String) -> String { "\(v) \(unit)\(v == 1 ? "" : "s")" }
        if seconds % 604_800 == 0 { return n(seconds / 604_800, "week") }
        if seconds % 86_400 == 0 { return n(seconds / 86_400, "day") }
        if seconds % 3_600 == 0 { return n(seconds / 3_600, "hour") }
        return n(Swift.max(1, seconds / 60), "minute")
    }

    /// The text of the notice message that follows a change (spec "Disappearing messages"): what apps that don't
    /// follow the timer show. Never read back.
    public static func notice(_ seconds: Int) -> String {
        seconds == 0 ? "⏱ The timer is off. Messages disappear after 1 to 2 weeks."
                     : "⏱ Messages here now disappear \(label(seconds)) after they reach each phone. Older DarkProof versions keep them 1 to 2 weeks."
    }

    // What the screens say.

    /// Under the chat's title: "They disappear 1 day after they reach each phone." `highRisk`: this phone is in
    /// high-risk mode, whose day counts unless the group's timer is shorter.
    public static func chatLine(_ seconds: Int?, highRisk: Bool = false) -> String {
        let timed = seconds.map { $0 > 0 } ?? false
        if highRisk && !(timed && seconds! <= Int(KeepsLess.keepMs / 1000)) { return "High-risk mode: they leave this phone 1 day after they arrive." }
        guard let seconds, timed else { return "They disappear after one to two weeks." }
        return "They disappear \(label(seconds)) after they reach each phone."
    }

    /// The "Messages disappear" row of Group security. `by`: who set it, as the reader sees them ("Anna", "you"), if this
    /// phone saw the change. `highRisk`: this phone is in high-risk mode, which removes after a day whatever the group says.
    public static func securityLine(_ seconds: Int?, by: String?, highRisk: Bool) -> String {
        let day = " On this phone, 1 day after they arrive."
        guard let seconds else { return "Not known yet. Until an admin's key brings it, this phone keeps messages one to two weeks." + (highRisk ? day : "") }
        guard seconds > 0 else { return "After one to two weeks" + (highRisk ? "." + day : "") }
        let line = "After \(label(seconds))" + (by.map { ", set by \($0)" } ?? "")
        return highRisk && seconds > Int(KeepsLess.keepMs / 1000) ? line + "." + day : line
    }

    /// The line in the chat when the timer changed. `who`: "You", "Anna", or nil when this phone didn't see who set it.
    public static func changeLine(_ seconds: Int, who: String?) -> String {
        guard let who else { return seconds == 0 ? "The timer is off. Messages disappear after 1 to 2 weeks." : "Messages disappear after \(label(seconds))" }
        return seconds == 0 ? "\(who) switched the timer off. Messages disappear after 1 to 2 weeks." : "\(who) set messages to disappear after \(label(seconds))"
    }

    /// What someone who can't change it reads in the timer sheet.
    public static func sheetLine(_ seconds: Int?) -> String {
        guard let seconds else { return "Not known on this phone yet. Until an admin's key brings it, messages stay one to two weeks." }
        return seconds > 0 ? "Messages disappear \(label(seconds)) after they reach each phone." : "Messages disappear after one to two weeks."
    }

    /// What tapping the row explains.
    public static func explainer(_ seconds: Int) -> String {
        let t = label(seconds)
        return "Each phone deletes a message \(t) after it reaches it. A phone that was away gets it later and deletes it \(t) after that. Phones need the latest DarkProof for this. Anyone in the group can still copy or photograph a message, and a changed app can keep everything."
    }
}

/// What a group message's 5th field says, next to replies and reactions (spec "The 5th field"). Readers act on it only
/// in a group, from a packet whose signature checked for a member; on the Public channel it is ignored.
public enum Notice: Equatable, Sendable {
    /// Type `0x03` in a message: the timer changed, said in the text for apps that don't follow it. An app that does
    /// follow it shows nothing of this message and takes nothing from it: the new key is the only word on the timer.
    case timer(Int)
    /// Type `0x05`, form `0x01`: the sender left the group or erased their phone (`LeaveNote`). Never a bubble: the
    /// chat shows a line instead, and admins' phones remove the sender.
    case leave
    /// Type `0x04`: the sender's check-in clock (`QuietClock`), its deadline in ms since 1970 UTC; 0 stops it. An
    /// ordinary message on every phone (its text says it), shown here as a card, alerted, confirmed and sent on LoRa.
    case clock(Int64)

    /// What a reader makes of a 5th field; nil for any other type or length. `ts`: the message's own signed time, which
    /// a check-in clock's deadline is checked against (rule Q2); without it a clock reads as none.
    public static func read(field: Data?, ts: Int64? = nil) -> Notice? {
        if let seconds = GroupTimer.read(field: field) { return .timer(seconds) }
        if LeaveNote.read(field: field) { return .leave }
        if let ts, let deadline = QuietClock.read(field: field, ts: ts) { return .clock(deadline) }
        return nil
    }

    /// True for a notice that is never a chat row, an alert or a receipt.
    public var isSilent: Bool {
        switch self {
        case .timer, .leave: return true
        case .clock: return false
        }
    }

    /// True for a notice that stays off LoRa, like a receipt. The leave note goes on the radio: it is small, and it may
    /// be the only way it reaches an admin. So does a check-in clock: it matters like a check-in.
    public var staysOffRadio: Bool {
        switch self {
        case .timer: return true
        case .leave, .clock: return false
        }
    }
}

// MARK: - If I go quiet (spec/FORMAT.md, "If I go quiet")

/// A check-in clock the group's phones keep: an ordinary signed group message whose `text` says it for every app
/// (`⏳ Check on me if I don't check in within 1 hour`), with the 5th field `0x04` and the deadline, ms since 1970 UTC
/// as a signed 64-bit big-endian number, by the sender's clock. Deadline 0 stops the sender's clock. Nothing is ever
/// parsed from the text but a position, read as in a check-in. Same as Android's.
public enum QuietClock {
    public static let type: UInt8 = 0x04
    /// Type (1), deadline (8).
    public static let size = 9
    /// Rule Q2: a deadline counts from 5 minutes to 24 hours after the message's own time, both ends included.
    public static let minMs: Int64 = 5 * 60_000
    public static let maxMs: Int64 = 24 * 3_600_000
    /// Rules Q6, Q7: a clock that ran out is "overdue" in People, and alerted at once by a phone that learns of it late,
    /// until this long after its deadline; after that nothing.
    public static let lateMs: Int64 = 12 * 3_600_000
    /// Rule Q8: the sender's own phone reminds its user this long before the deadline, for a clock at least `remindFromMs` long.
    public static let remindMs: Int64 = 10 * 60_000
    public static let remindFromMs: Int64 = 20 * 60_000
    /// "Add 30 minutes".
    public static let addMs: Int64 = 30 * 60_000
    /// What the screen offers, in minutes.
    public static let choices = [30, 60, 180, 480]
    /// Rule Q12: at most this many clock alerts wait with iOS at once (it keeps 64 per app), nearest deadlines first.
    public static let maxScheduled = 32
    /// The text of a stop (deadline 0).
    public static let stopText = "⏳ Clock stopped. No need to check on me"
    private static let lead = "⏳ Check on me if I don't check in within "

    /// The field for `deadline` (0: stop).
    public static func field(deadline: Int64) -> Data {
        var d = Data([type])
        withUnsafeBytes(of: deadline.bigEndian) { d.append(contentsOf: $0) }
        return d
    }

    /// The deadline a 5th field carries as written, before rule Q2: nil unless it is exactly 9 bytes of type `0x04`.
    public static func raw(field: Data?) -> Int64? {
        guard let field, field.count == size, field.first == type else { return nil }
        return Int64(bitPattern: field.dropFirst().reduce(UInt64(0)) { $0 << 8 | UInt64($1) })
    }

    /// Rule Q2: 0 (stop), or `ts + 5 min <= deadline <= ts + 24 h`. Anything else: the field is ignored and the message
    /// is an ordinary text.
    public static func valid(deadline: Int64, ts: Int64) -> Bool {
        if deadline == 0 { return true }
        let (lo, o1) = ts.addingReportingOverflow(minMs), (hi, o2) = ts.addingReportingOverflow(maxMs)
        return !o1 && !o2 && deadline >= lo && deadline <= hi
    }

    /// What a reader takes from the 5th field of a message dated `ts`: its deadline if it is a clock that rule Q2 allows.
    public static func read(field: Data?, ts: Int64) -> Int64? {
        guard let d = raw(field: field), valid(deadline: d, ts: ts) else { return nil }
        return d
    }

    /// `ms` rounded to the nearest minute: "30 minutes", "1 hour", "3 hours", "1 hour 30 minutes", "3 hours 15 minutes".
    public static func duration(_ ms: Int64) -> String {
        let minutes = Int((Swift.max(0, ms) + 30_000) / 60_000), h = minutes / 60, m = minutes % 60
        func n(_ v: Int, _ unit: String) -> String { "\(v) \(unit)\(v == 1 ? "" : "s")" }
        if h == 0 { return n(m, "minute") }
        return m == 0 ? n(h, "hour") : n(h, "hour") + " " + n(m, "minute")
    }

    /// The message's text: what every app shows, also those that don't know the field. Relative to the message's own
    /// time, which every app shows, so time zones don't matter. `spot`: where the sender is, the check-in suffix
    /// ` · 📍 45.46420,9.19000 ±12m`, only if they share their position and chose "Add where I am".
    public static func text(ts: Int64, deadline: Int64, spot: Spot? = nil) -> String {
        guard deadline != 0 else { return stopText }
        var t = lead + duration(deadline - ts)
        if let spot, spot.isValid { t += " · 📍 " + spot.text }
        return t
    }

    /// The position a clock message's text carries, read as in a check-in ("Check-ins").
    public static func spot(in text: String) -> Spot? { text.hasPrefix("⏳") ? Spot.last(in: text) : nil }

    /// Rule Q4: an own check-in of the clock's member (text starting with `✅` or `🆘`, `CheckIn.of`) whose `ts` is
    /// greater than the clock message's cancels it. Reports, reactions, "Are you safe?" and other messages don't.
    public static func cancels(text: String, ts: Int64, clockTs: Int64) -> Bool { CheckIn.of(text) != nil && ts > clockTs }

    /// What a group message does to its sender's clock.
    public enum Effect: Equatable, Sendable {
        /// A clock message (rule Q2 passed): it counts if it is the newest (Q3); deadline 0 stops.
        case clock(Int64)
        /// An own check-in: cancels a clock dated before it (Q4).
        case checkIn
    }

    /// Rule Q4's sorting: a clock message, an own check-in, or nil for anything else: a report (`👤`), "Are you safe?",
    /// an ordinary message, and anything silent (a reaction, the timer's notice, a leave note), whatever its text. Never
    /// on the Public channel.
    public static func effect(of m: Message) -> Effect? {
        guard !m.isPublic else { return nil }
        if case .clock(let d)? = m.notice { return .clock(d) }
        if m.isSilent { return nil }
        return CheckIn.of(m.text) != nil ? .checkIn : nil
    }

    /// Rule Q1: a message acts on clocks only in a group, from a packet whose signature checked for its sender (`checked`;
    /// one waiting for its sender's key is decided when it checks), opened by an epoch with a member list that lists
    /// the sender, as every epoch up to the current one does (`Keyring.listsThrough`): nothing of a removed member, nor
    /// a message sealed before a removal and played back after the same key was let in again. `left`: the message is
    /// dated after a valid leave note of the sender that is held ("Leaving the group", L3, `LeaveBook.hides`): it
    /// counts for nothing. One dated at or before the note still counts, so a check-in sent just before leaving still
    /// cancels the clock, which a leave note itself never ends (rule Q9).
    public static func counts(sender: String, checked: Bool?, opener: Keyring.Epoch?, ring: Keyring, left: Bool) -> Bool {
        guard checked == true, !left, !sender.hasPrefix("web-"), let e = opener else { return false }
        return ring.listsThrough(sender, from: e)
    }

    /// The deadline of a new clock of `minutes`, started at `now`.
    public static func deadline(minutes: Int, now: Int64) -> Int64 { now + Int64(minutes) * 60_000 }

    /// "Add 30 minutes": a new clock message with the old deadline plus 30 minutes (from now if it has passed), at most
    /// 24 hours from now, so it always passes rule Q2.
    public static func added(to deadline: Int64, now: Int64) -> Int64 { Swift.min(Swift.max(deadline, now) + addMs, now + maxMs) }

    /// Rule Q8: when the sender's own phone reminds its user, or nil for a clock shorter than 20 minutes, or a stop.
    public static func remindAt(ts: Int64, deadline: Int64) -> Int64? { deadline != 0 && deadline - ts >= remindFromMs ? deadline - remindMs : nil }

    /// What a phone does about a clock it holds (rules Q5, Q6, Q7).
    public enum Due: Equatable, Sendable {
        /// Not yet: an alert is scheduled for the deadline, by this phone's clock.
        case at(Int64)
        /// Now, worded as on time: this phone held the clock before its deadline.
        case now
        /// Now, with "This phone learned of it late.": it learned of the clock after the deadline.
        case late
        /// Never: not armed, a stop, or more than 12 hours after the deadline. No record, only the chat card.
        case none
    }

    /// `learned`: when this phone took the clock; `armed`: no cancelling check-in or newer clock message reached it.
    public static func due(deadline: Int64, learned: Int64, now: Int64, armed: Bool = true) -> Due {
        guard armed, deadline != 0 else { return .none }
        if now < deadline { return .at(deadline) }
        if now - deadline > lateMs { return .none }
        return learned > deadline ? .late : .now
    }

    /// Rule Q12: which of `deadlines` (still to come) get an alert scheduled with iOS now: the nearest `maxScheduled`.
    public static func scheduled<T>(_ items: [T], deadline: (T) -> Int64) -> [T] {
        Array(items.sorted { deadline($0) < deadline($1) }.prefix(maxScheduled))
    }
}

/// The check-in clocks of one group on this phone (spec/FORMAT.md, "If I go quiet", rules Q3, Q4, Q6, Q9, Q10, Q12):
/// each member's clock that counts and their latest check-in, saved apart from the chat so that a clock survives its
/// message being deleted by the group's timer or high-risk mode, and app restarts. Built only from messages that passed
/// rule Q1 (the engine checks it): signed by that member, opened by an epoch that lists them through the current one,
/// not dated after a leave note. A leave note, and the removal that follows it, don't end a clock (rule Q9): only a
/// cancelling check-in or a stop that counts does, or the record's time. A record is kept, without its position, after it is cancelled, so that a copy of an older
/// clock read again (at the next start, or brought late) can't arm it again. Not thread-safe.
public struct QuietBook: Equatable, Sendable {
    public struct Clock: Equatable, Sendable {
        /// The member, their clock message's packet id (16 hex chars) and mark (the first 8 bytes of its signed digest,
        /// hex, `About.Target`), its `ts` and its deadline (0: a stop).
        public let member: String, id: String, mark: String, ts: Int64, deadline: Int64
        /// Where the member was, from the clock message's text; none in high-risk mode on this phone, or once cancelled.
        public var spot: Spot?
        /// When this phone took it, by its own clock.
        public let learned: Int64
        /// An alert for it was posted, or the alert scheduled with iOS came due: never a second one.
        public var alerted: Bool
        /// Its alert waits with iOS for the deadline.
        public var scheduled: Bool
        /// For this phone's own clock: the members whose phones confirmed the clock message (delivery receipts), first
        /// confirmation first. Kept here, not with the message, so "Kept by" outlives the message when the group's
        /// timer deletes it. Ids only; the screen names them, and leaves out who isn't a member now.
        public var keepers: [String] = []
        /// Rule Q3: the greater `ts`; equal, the greater packet id; equal, the greater mark. Arrival order doesn't matter.
        public func beats(_ o: Clock) -> Bool { (ts, id, mark) > (o.ts, o.id, o.mark) }
    }

    /// Member id -> their clock that counts.
    public private(set) var clocks: [String: Clock] = [:]
    /// Member id -> the greatest `ts` of their own check-ins this phone took (rule Q4).
    public private(set) var checkIns: [String: Int64] = [:]
    /// Member id -> when this phone last posted a clock alert about them, and that clock's `ts`: at most one alert per
    /// member every `alertGapMs`, and a late one only for a clock newer than the last one alerted.
    public private(set) var posted: [String: (at: Int64, ts: Int64)] = [:]
    /// At most one clock alert about a member per group in this long.
    public static let alertGapMs: Int64 = 10 * 60_000

    public static func == (a: QuietBook, b: QuietBook) -> Bool {
        a.clocks == b.clocks && a.checkIns == b.checkIns && a.posted.mapValues { [$0.at, $0.ts] } == b.posted.mapValues { [$0.at, $0.ts] }
    }

    /// Rule Q12: at most this many clocks in one group (a group has at most 100 members): the oldest goes.
    public static let maxRecords = 100
    /// Rule Q10: a record is kept until 36 hours after its message's `ts`, past the deadline (at most 24 hours later) and
    /// the 12 hours after it; then no older clock of that member can still alert. In high-risk mode it goes at 24 hours,
    /// never before the deadline.
    public static let keepMs = QuietClock.maxMs + QuietClock.lateMs
    public static let keepHighRiskMs = QuietClock.maxMs

    public init() {}

    public var isEmpty: Bool { clocks.isEmpty && checkIns.isEmpty && posted.isEmpty }

    private static func plain(_ id: String) -> String { id.hasPrefix("web-") ? String(id.dropFirst(4)) : id }

    /// True while something dated `ts` is still kept (rule Q10).
    public static func keeps(ts: Int64, now: Int64, highRisk: Bool) -> Bool { now - ts < (highRisk ? keepHighRiskMs : keepMs) }

    /// Rule Q3: a valid clock message of `member` (rule Q2 passed; deadline 0 is a stop), taken at `now`. It counts only
    /// if it beats the one held; one older than what this phone keeps (`keeps`) isn't taken. True if it now counts.
    @discardableResult public mutating func take(member: String, id: String, mark: String, ts: Int64, deadline: Int64, spot: Spot?, now: Int64, highRisk: Bool) -> Bool {
        let m = Self.plain(member)
        guard Self.keeps(ts: ts, now: now, highRisk: highRisk) else { return false }
        let c = Clock(member: m, id: id, mark: mark, ts: ts, deadline: deadline, spot: highRisk || deadline == 0 ? nil : spot,
                      learned: now, alerted: false, scheduled: false)
        if let had = clocks[m], !c.beats(had) { return false }
        clocks[m] = c
        if (checkIns[m] ?? .min) > ts { clocks[m]?.spot = nil } // cancelled already: kept only so that older ones stay out
        if clocks.count > Self.maxRecords, let oldest = clocks.values.min(by: { ($0.ts, $0.member) < ($1.ts, $1.member) }) { clocks[oldest.member] = nil }
        return clocks[m] != nil
    }

    /// Rule Q4: an own check-in of `member` dated `ts`. True if the book changed (it is their latest).
    @discardableResult public mutating func checkIn(member: String, ts: Int64, now: Int64, highRisk: Bool) -> Bool {
        let m = Self.plain(member)
        guard Self.keeps(ts: ts, now: now, highRisk: highRisk), ts > (checkIns[m] ?? .min) else { return false }
        checkIns[m] = ts
        if let c = clocks[m], ts > c.ts { clocks[m]?.spot = nil } // cancelled: where they were goes
        return true
    }

    /// Rule Q4: `member` has a clock that runs or ran out: deadline not 0, and no check-in of theirs dated after it.
    public func armed(_ member: String) -> Bool {
        guard let c = clocks[Self.plain(member)], c.deadline != 0 else { return false }
        return (checkIns[c.member] ?? .min) <= c.ts
    }

    public func clock(_ member: String) -> Clock? { clocks[Self.plain(member)] }

    /// What People and the own screen show of a member's clock (rule Q6).
    public enum State: Equatable, Sendable {
        /// Armed, before its deadline.
        case running(deadline: Int64)
        /// Armed, its deadline passed less than 12 hours ago: "overdue".
        case ranOut(deadline: Int64)
        /// A check-in dated after the deadline cancelled it, less than 12 hours after the deadline.
        case checkedIn(at: Int64, deadline: Int64)
    }

    public func state(_ member: String, now: Int64) -> State? {
        guard let c = clocks[Self.plain(member)], c.deadline != 0 else { return nil }
        let within = now - c.deadline <= QuietClock.lateMs
        if let ci = checkIns[c.member], ci > c.ts { return ci >= c.deadline && within ? .checkedIn(at: ci, deadline: c.deadline) : nil }
        if now < c.deadline { return .running(deadline: c.deadline) }
        return within ? .ranOut(deadline: c.deadline) : nil
    }

    /// Rule Q9: `member` was removed by a removal that didn't follow a leave note of theirs: their clock goes without an
    /// alert. True if anything went.
    @discardableResult public mutating func drop(_ member: String) -> Bool {
        let m = Self.plain(member)
        let had = clocks[m] != nil || checkIns[m] != nil
        clocks[m] = nil; checkIns[m] = nil
        return had
    }

    /// Rule Q10's end: records and check-ins go 36 hours after their `ts` (24 in high-risk mode, which also keeps no
    /// position), and when their member is no longer in the group (`inGroup`, rule Q9: still listed in the current
    /// epoch, or taken out only after a leave note of theirs). `positionMs`: the group's timer as it deletes now; a
    /// record's position goes once its clock message would have gone by it (counted from when this phone took the
    /// clock, as messages count from arrival): what the timer deletes from the chat isn't kept in the record either.
    /// True if anything changed.
    @discardableResult public mutating func sweep(now: Int64, highRisk: Bool, positionMs: Int64? = nil, inGroup: (String) -> Bool) -> Bool {
        let before = self
        clocks = clocks.filter { Self.keeps(ts: $0.value.ts, now: now, highRisk: highRisk) && inGroup($0.key) }
        for m in clocks.keys where clocks[m]?.spot != nil {
            guard let c = clocks[m] else { continue }
            if highRisk || positionMs.map({ now - c.learned >= $0 }) == true { clocks[m]?.spot = nil }
        }
        checkIns = checkIns.filter { Self.keeps(ts: $0.value, now: now, highRisk: highRisk) && inGroup($0.key) }
        posted = posted.filter { now - $0.value.at < Self.keepMs && inGroup($0.key) }
        return self != before
    }

    /// For this phone's own clock (`me`) whose message is `clockId`: `keeper`'s phone confirmed it. True if the list grew.
    @discardableResult public mutating func addKeeper(me: String, clockId: String, keeper: String) -> Bool {
        let m = Self.plain(me), k = Self.plain(keeper)
        guard let c = clocks[m], c.id == clockId, k != m, !c.keepers.contains(k) else { return false }
        clocks[m]?.keepers.append(k)
        return true
    }

    /// The low rule on clock alerts: when an alert about `c` may be posted now, or nil if never (a late alert for a
    /// clock no newer than one already alerted about that member). At most one alert per member every `alertGapMs`:
    /// a member can't make every phone ring again and again with backdated clocks.
    public func postableAt(_ c: Clock, late: Bool, now: Int64) -> Int64? {
        guard let last = posted[c.member] else { return now }
        if late && c.ts <= last.ts { return nil }
        return Swift.max(now, last.at + Self.alertGapMs)
    }

    /// An alert about `c` was posted at `now`.
    @discardableResult public mutating func didPost(_ c: Clock, now: Int64) -> Bool {
        posted[c.member] = (now, Swift.max(c.ts, posted[c.member]?.ts ?? .min))
        return true
    }

    /// The armed clocks of other members (`me`: this phone's member id, whose own clock never alerts it, rule Q8) with
    /// what is due for each at `now`. Already alerted ones are left out.
    public func alerts(now: Int64, me: String) -> [(clock: Clock, due: QuietClock.Due)] {
        clocks.values.filter { $0.member != Self.plain(me) && !$0.alerted && armed($0.member) }.sorted { $0.deadline < $1.deadline }
            .map { ($0, QuietClock.due(deadline: $0.deadline, learned: $0.learned, now: now)) }
    }

    /// Marks the alert of `member`'s clock `mark` as posted or come due (`alerted`), or as waiting with iOS (`scheduled`).
    /// Nothing changes for another clock: a newer one came meanwhile. True if anything changed.
    @discardableResult public mutating func note(_ member: String, mark: String, alerted: Bool? = nil, scheduled: Bool? = nil) -> Bool {
        let m = Self.plain(member)
        guard let c = clocks[m], c.mark == mark else { return false }
        if let alerted { clocks[m]?.alerted = alerted }
        if let scheduled { clocks[m]?.scheduled = scheduled }
        return clocks[m] != c
    }

    // MARK: Saved on the phone

    /// As it is saved: "c <member> <id> <mark> <ts> <deadline> <learned> <alerted> <scheduled> <lat> <lon> <accuracy>
    /// <keepers>" for each clock ("-" for no position, accuracy or keepers; keepers comma-separated; 0 or 1 for the
    /// flags), "i <member> <ts>" for each check-in, "a <member> <at> <ts>" for each alert posted.
    public var saved: [String] {
        func n(_ d: Double?) -> String { d.map { String(format: "%.5f", $0) } ?? "-" }
        return clocks.values.sorted { $0.member < $1.member }.map { c in
            "c \(c.member) \(c.id) \(c.mark) \(c.ts) \(c.deadline) \(c.learned) \(c.alerted ? 1 : 0) \(c.scheduled ? 1 : 0) \(n(c.spot?.lat)) \(n(c.spot?.lon)) \(c.spot?.accuracy.map(String.init) ?? "-") \(c.keepers.isEmpty ? "-" : c.keepers.joined(separator: ","))"
        } + checkIns.sorted { $0.key < $1.key }.map { "i \($0.key) \($0.value)" }
          + posted.sorted { $0.key < $1.key }.map { "a \($0.key) \($0.value.at) \($0.value.ts)" }
    }

    /// From what `saved` wrote. A line that doesn't read is left out.
    public init(saved: [String]) {
        for line in saved {
            let f = line.split(separator: " ").map(String.init)
            if f.count == 12 || f.count == 13, f[0] == "c", let ts = Int64(f[4]), let d = Int64(f[5]), let l = Int64(f[6]) {
                let spot = Double(f[9]).flatMap { lat in Double(f[10]).map { Spot(lat: lat, lon: $0, accuracy: Int(f[11])) } }.flatMap { $0.isValid ? $0 : nil }
                let keepers = f.count == 13 && f[12] != "-" ? f[12].split(separator: ",").map(String.init) : []
                clocks[f[1]] = Clock(member: f[1], id: f[2], mark: f[3], ts: ts, deadline: d, spot: spot, learned: l, alerted: f[7] == "1", scheduled: f[8] == "1", keepers: keepers)
            } else if f.count == 3, f[0] == "i", let ts = Int64(f[2]) { checkIns[f[1]] = ts }
            else if f.count == 4, f[0] == "a", let at = Int64(f[2]), let ts = Int64(f[3]) { posted[f[1]] = (at, ts) }
        }
    }
}

/// What the screens and alerts say about check-in clocks (spec/FORMAT.md, "If I go quiet"). Nothing here claims more
/// than the phone knows: an alert says that no check-in *has reached this phone*, never that someone is missing or that
/// anyone was told. Same as Android's.
public enum QuietWords {
    public static let title = "If I go quiet"
    public static let lead = "If you don't check in on time, the phones that got your clock tell their owners."
    public static let sub = "It still works when your phone is off or gone. Only phones that got the clock can tell anyone."
    public static let addWhere = "Add where I am"
    public static let addWhereSub = "Where you are now goes with the clock"
    public static let start = "Start the clock"
    public static let safe = "I'm safe, stop the clock"
    public static let addMore = "Add 30 minutes"
    public static let stopWithout = "Stop without checking in"
    /// The chips: "30 min", "1 hour", "3 hours", "8 hours".
    public static func chip(_ minutes: Int) -> String { minutes < 60 ? "\(minutes) min" : minutes == 60 ? "1 hour" : "\(minutes / 60) hours" }
    public static func checkInBy(_ time: String) -> String { "Check in by \(time)" }
    public static func ends(_ time: String) -> String { "Ends \(time)" }
    public static func ranOut(_ time: String) -> String { "Your clock ran out at \(time). Phones that kept it tell their owners." }
    /// Who confirmed the clock message (delivery receipts), never the member list: only phones that got it can tell anyone.
    public static func kept(_ names: [String]) -> String {
        switch names.count {
        case 0: return "Not confirmed by any phone yet. If no phone has it, nobody is told."
        case 1: return "Kept by \(names[0]) so far"
        case 2: return "Kept by \(names[0]) and \(names[1]) so far"
        default: return "Kept by \(names[0]), \(names[1]) and \(names.count - 2) more so far"
        }
    }
    public static func cantWrite(_ group: String) -> String { "This phone isn't let in, so it can't send a clock to \(group)." }
    public static let shareOff = "To add where you are, turn on location sharing in Settings."

    // Toasts. `waiting`: no phone, radio or relay is in reach, so it waits on this phone.
    public static func sent(waiting: Bool) -> String { waiting ? "The clock waits for a phone in range" : "Clock sent to your group" }
    public static func added(waiting: Bool) -> String { waiting ? "30 minutes added. It waits for a phone in range" : "30 minutes added" }
    public static func safeSent(waiting: Bool) -> String { waiting ? "“I'm safe” waits for a phone in range. The clock stopped here." : "“I'm safe” sent. The clock stopped." }
    public static func stopped(waiting: Bool) -> String { waiting ? "Clock stopped. The message waits for a phone in range" : "Clock stopped" }

    // The chat card. `name`: nil for this phone's own.
    public static func started(_ name: String?) -> String { name.map { "\($0) started a check-in clock" } ?? "You started a check-in clock" }
    public static func startedSub(_ name: String?, ends time: String) -> String {
        name.map { "If no check-in from \($0) reaches this phone by \(time), it tells you." } ?? "Ends \(time). Phones that got it tell their owners if you don't check in."
    }
    public static func stoppedLine(_ name: String?) -> String { name.map { "\($0) stopped the check-in clock" } ?? "You stopped your check-in clock" }

    // People.
    public static func running(_ time: String) -> String { "Check-in clock · ends \(time)" }
    public static func overdue(_ time: String) -> String { "Clock ran out at \(time) · no check-in has reached this phone" }
    public static func checkedIn(_ time: String) -> String { "Checked in at \(time)" }

    /// Rule Q13: who a clock is from, as the alert and the card name them. The bare name only for a checked member whose
    /// name (by its key, `Vouch.key`) no other member of the group has; a member not checked: "Anna (not checked)"; a
    /// checked one whose name another member has too: "Anna · 🐶🐱🦁🐴", the first four of their person emoji. So a
    /// member who calls themselves "Anna" can't make the group read an alert as being about the real Anna.
    public static func who(_ name: String, checked: Bool, shared: Bool, emoji: [String]) -> String {
        if !checked { return "\(name) (not checked)" }
        if shared { return "\(name) · " + emoji.prefix(4).joined() }
        return name
    }

    // The alert. `at`: the deadline; `since`: the clock message's time; `position`: when the newest position known was sent.
    public static func alertTitle(_ name: String, late: Bool, at time: String) -> String { late ? "\(name)'s check-in clock ran out at \(time)" : "\(name)'s check-in clock ran out" }
    public static func alertBody(_ name: String, since: String, late: Bool, position: String?) -> String {
        var s = "No check-in from \(name) has reached this phone since \(since). \(name)'s phone may just be out of reach."
        if let position { s += " Last position known: \(position)." }
        if late { s += " This phone learned of it late." }
        return s
    }
    /// With "Lock DarkProof" or high-risk mode on: no name, no time, no text.
    public static let hiddenTitle = "DarkProof"
    public static let hiddenBody = "A check-in clock in your group ran out. Open DarkProof."
    /// The banner while DarkProof is on screen (neutral, not red).
    public static func banner(_ name: String, group: String) -> String { "No check-in from \(name) has reached this phone. In \(group)." }

    // The sender's own reminder, 10 minutes before (rule Q8).
    public static func remindTitle(_ time: String) -> String { "Your check-in clock ends at \(time)" }
    public static let remindBody = "Check in now?"
    public static let remindHidden = "Your check-in clock ends soon. Open DarkProof."

    /// Never said anywhere about a clock (lowercased): the brief's "Never say" list.
    public static let never = ["is missing", "didn't check in", "did not check in", "will be told", "are told", "group is told"]
}

/// The note a phone sends when it leaves its group, or when "Erase everything" tells its groups first (spec/FORMAT.md,
/// "Leaving the group"): an ordinary group message with `text` and the 5th field `05 01`, sealed with the phone's
/// message key in its current epoch. One form for leaving and erasing: saying "erased" could tell the wrong reader that
/// the phone was in danger. Only the member's own key can sign it, so nobody else can make a member leave. Same as
/// Android's.
public enum LeaveNote {
    public static let type: UInt8 = 0x05
    /// The only form, "I left". A reader ignores any other second byte (a newer form) and reads the message as text.
    public static let form: UInt8 = 0x01
    /// Type (1), form (1).
    public static let size = 2
    /// What every app shows of it, also those that don't know the field: to them it is an ordinary message.
    public static let text = "👋 I left the group"
    public static var field: Data { Data([type, form]) }
    /// The longest a leaving or erasing phone waits for a phone, a radio or a relay to take the note.
    public static let waitSeconds = 15
    /// How long a leaving record lasts after this phone took it: a packet's life, so the note itself is gone by then.
    public static let keepMs: Int64 = 7 * 24 * 3_600_000
    /// An admin's phone waits a random time below this before it removes the member: the group's admins don't all act at once.
    public static let maxDelayMs = 3_000

    /// True only for exactly `05 01`: another form, another length or another type is not a note.
    public static func read(field: Data?) -> Bool {
        guard let field, field.count == size else { return false }
        return field.first == type && field.last == form
    }

    /// What a reader makes of a note (rule L1).
    public enum Verdict: Equatable, Sendable {
        /// It counts: the member left.
        case valid
        /// Its signature can't be checked yet (the sender's key hasn't come): decided again when it checks.
        case notYet
        /// It does nothing.
        case no
    }

    /// Rule L1: a note counts only if it opened in this group with an epoch that has a member list (not one kept only
    /// to read with), its signature checked for its sender, every epoch from that one up to the current one lists the
    /// sender (`Keyring.listsThrough`), no removal of the sender that won is held, and the sender isn't this phone.
    /// A note from before a removal, played back after the same key was let in again, fails: the removal's epoch
    /// doesn't list them. `checked`: the signature checked (true), failed (false), or can't be checked yet (nil).
    /// `opener`: the epoch that opened it (`GroupCrypto.Opened.opener`).
    public static func verdict(sender: String, me: String, checked: Bool?, opener: Keyring.Epoch?, ring: Keyring) -> Verdict {
        let id = sender.hasPrefix("web-") ? String(sender.dropFirst(4)) : sender
        guard checked != false, id != me, let e = opener, ring.listsThrough(id, from: e) else { return .no }
        return checked == nil ? .notYet : .valid
    }

    /// What an admin's phone does about a member who left (rules L4, L5).
    public enum Act: Equatable, Sendable {
        /// The normal removal: a new key and a new code for everyone else.
        case removeNow
        /// It can't make the key now (a member's encryption key is missing): the hourly check tries again.
        case retryHourly
        case nothing
    }

    /// `admin`: this phone is an admin of the current epoch and wasn't removed. `inCurrent`: the member is still in the
    /// current epoch. `removalHeld`: a removal of them is held already (another admin's, or a rival one, which the
    /// rules of "Two admins at once" settle). `keysComplete`: this phone has every remaining member's encryption keys.
    public static func act(admin: Bool, inCurrent: Bool, removalHeld: Bool, keysComplete: Bool) -> Act {
        guard admin, inCurrent, !removalHeld else { return .nothing }
        return keysComplete ? .removeNow : .retryHourly
    }
}

/// What leaving a group takes out of the packet stores (rule LW3): the names of the group's packets and of the
/// copies this phone kept of its messages, except the leave note's own packet, which stays as a packet the phone only
/// carries until its 7 days end. Its copy, if one was made, goes with the others.
public enum LeaveCleanup {
    /// `packets`: the names of the stored packets the group's keys open (the note's among them). `copies`: this phone's
    /// copies of the group's messages, each by the name it is kept under and the name of its packet. `note`: the leave
    /// note's packet. Returns what to remove from the packet stores and from the copies.
    public static func removed(packets: Set<String>, copies: [(kept: String, packet: String)], keeping note: String?) -> (packets: Set<String>, copies: Set<String>) {
        var names = packets, gone = Set<String>()
        for c in copies { names.insert(c.packet); gone.insert(c.kept) }
        if let note { names.remove(note) } // after the copies: a copy of the note must not bring its packet back into the list
        return (names, gone)
    }
}

/// What the screens say about leaving (spec/FORMAT.md, "Leaving the group", and the brief's wording). Nothing here says
/// the group "was told" or that anyone "was removed": the note leaving this phone isn't the group getting it.
public enum LeaveWords {
    public static func sheetTitle(_ group: String) -> String { "Leave \(group)?" }
    public static let sheetText = "This phone forgets the group, its messages and its keys. A note asks the group's admins to remove you and change the key. To come back you need a new invite."
    public static func leaveButton(_ group: String) -> String { "Leave \(group)" }
    public static func cantTell(_ group: String) -> String { "This phone isn't let in, so \(group) can't be told." }
    public static func chooseTitle(_ group: String) -> String { "Who runs \(group) after you?" }
    public static let chooseText = "Admins can let people in and remove people."
    public static let leaveWithoutChoosing = "Leave without choosing"
    public static let withoutChoosingWarning = "Nobody will be able to let people in or remove anyone."
    public static let cantHandOver = "This phone can't hand over now: it doesn't have every member's keys."
    public static let leaveWithoutTelling = "Leave without telling"
    public static func waitingTitle(_ group: String) -> String { "Telling \(group)…" }
    public static let waitingText = "Waiting for a phone or relay to take the note."
    public static let leaveNow = "Leave now"
    /// The toast after leaving. `noteLeft`: nil when no note was sent.
    public static func left(_ group: String, noteLeft: Bool?) -> String {
        noteLeft == false ? "You left \(group). The note didn't leave this phone in time. It goes when a phone comes into reach." : "You left \(group)"
    }
    public static let tellFirst = "Tell my groups first"
    public static let tellFirstSub = "Asks their admins' phones to remove this phone and change the key"
    public static let tellingTitle = "Telling your groups…"
    public static let eraseNow = "Erase now"
    public static let empty = "This phone is empty"
    /// The final screen's line, naming no group: the phone may be taken from its user at that moment. `sent`: notes
    /// sent; `left`: of those, how many a phone, radio or relay took in time. One note is said in the singular ("The
    /// note left this phone."), never "1 of 1 notes". Same words as Android's.
    public static func erased(sent: Int, left: Int) -> String {
        if sent == 0 { return "Your groups were not told." }
        if sent == 1 { return left >= 1 ? "The note left this phone." : "The note didn't leave this phone in time." }
        if left >= sent { return "The notes left this phone." }
        return "\(sent - left) of \(sent) notes didn't leave this phone in time."
    }
    /// The chat's line on other phones. `waiting`: the member is still listed and this phone is no admin.
    public static func line(_ name: String, waiting: Bool) -> String {
        waiting ? "\(name) left. Waiting for an admin's phone to change the key." : "\(name) left the group"
    }
    /// Never said anywhere about leaving (lowercased): the brief's "Never say" list.
    public static let never = ["their phones remove this one", "removed from the group", "your messages are deleted from their phones", "was told to remove"]
}

/// The leaving records of one group on this phone (spec/FORMAT.md, "Leaving the group", rules L2 and L3), and the
/// removals that followed a note, so the chat can say "Marco left the group" for them. Built only from notes that
/// passed `LeaveNote.verdict`. Not thread-safe.
public struct LeaveBook: Equatable, Sendable {
    /// A member who left: their note's `ts`, the number of the epoch it opened with, and when this phone took it.
    public struct Record: Equatable, Sendable {
        public let member: String, ts: Int64, epoch: Int64, taken: Int64
        public init(member: String, ts: Int64, epoch: Int64, taken: Int64) { self.member = member; self.ts = ts; self.epoch = epoch; self.taken = taken }
    }
    /// Member id -> their record, while they are still in the current epoch.
    public private(set) var records: [String: Record] = [:]
    /// The id of a removal epoch that took a member out after their note -> that record. Kept while the epoch is held.
    public private(set) var removals: [String: Record] = [:]

    public init() {}

    public var isEmpty: Bool { records.isEmpty && removals.isEmpty }

    private static func plain(_ id: String) -> String { id.hasPrefix("web-") ? String(id.dropFirst(4)) : id }

    /// L2: a valid note from `member`. Only the first counts while their record lasts. True if it was taken.
    @discardableResult public mutating func take(member: String, ts: Int64, epoch: Int64, now: Int64) -> Bool {
        let m = Self.plain(member)
        guard records[m] == nil else { return false }
        records[m] = Record(member: m, ts: ts, epoch: epoch, taken: now)
        return true
    }

    /// The record of `member` while it lasts.
    public func record(_ member: String) -> Record? { records[Self.plain(member)] }

    /// True while `member` left with a valid note: their record lasts, or the removal that followed it is kept. Their
    /// check-in clock stays (rule Q9).
    public func left(_ member: String) -> Bool {
        let m = Self.plain(member)
        return records[m] != nil || removals.values.contains { $0.member == m }
    }

    /// L3: true if what `member` wrote at `ts` isn't shown, alerted, confirmed or counted: it is dated after their note.
    /// Also after the removal that followed it, for as long as that epoch is held.
    public func hides(_ member: String, ts: Int64) -> Bool {
        let m = Self.plain(member)
        if let r = records[m], ts > r.ts { return true }
        return removals.values.contains { $0.member == m && ts > $0.ts }
    }

    /// L2's end. A record goes when its member is no longer in the current epoch (`inCurrent`), keeping, if a removal
    /// that won took them out (`removal`: that epoch's id), what the chat needs for it; and 7 days after this phone took
    /// it. A kept removal goes with its epoch (`held`: the ids of the epochs this phone holds). True if anything changed.
    @discardableResult public mutating func sweep(inCurrent: (String) -> Bool, removal: (String) -> String?, held: Set<String>, now: Int64) -> Bool {
        let before = self
        for (m, r) in records where !inCurrent(m) || now - r.taken >= LeaveNote.keepMs {
            records[m] = nil
            if !inCurrent(m), let id = removal(m) { removals[id] = r }
        }
        removals = removals.filter { held.contains($0.key) }
        return self != before
    }

    // MARK: Saved on the phone

    /// As it is saved: "r <member> <ts> <epoch> <taken>" for each record, "x <removal epoch id> <member> <ts> <epoch> <taken>" for each removal.
    public var saved: [String] {
        records.values.sorted { $0.member < $1.member }.map { "r \($0.member) \($0.ts) \($0.epoch) \($0.taken)" }
            + removals.sorted { $0.key < $1.key }.map { "x \($0.key) \($0.value.member) \($0.value.ts) \($0.value.epoch) \($0.value.taken)" }
    }

    /// From what `saved` wrote. A line that doesn't read is left out.
    public init(saved: [String]) {
        for line in saved {
            let f = line.split(separator: " ").map(String.init)
            if f.count == 5, f[0] == "r", let ts = Int64(f[2]), let e = Int64(f[3]), let t = Int64(f[4]) { records[f[1]] = Record(member: f[1], ts: ts, epoch: e, taken: t) }
            else if f.count == 6, f[0] == "x", let ts = Int64(f[3]), let e = Int64(f[4]), let t = Int64(f[5]) { removals[f[1]] = Record(member: f[2], ts: ts, epoch: e, taken: t) }
        }
    }
}

/// When messages disappear on this phone (spec/FORMAT.md, "Disappearing messages"). The group's timer comes from its
/// current key (`Keyring.timer`); what this type adds is the settle time, and the day high-risk mode keeps.
public enum Disappear {
    /// A value deletes nothing until this phone has had it, without a break, for this long.
    public static let settleMs: Int64 = 10 * 60_000

    /// The group's timer value on this phone and since when, by its own clock: saved next to the group's keys. A new
    /// value starts its settle time; a value that only stays (a later key that inherits it) doesn't.
    public struct Watch: Equatable, Sendable {
        public private(set) var seconds: Int?
        public private(set) var since: Int64
        public init(seconds: Int? = nil, since: Int64 = 0) { self.seconds = seconds; self.since = since }

        /// The group's timer is `seconds` now (nil: not known). True if that is a change.
        @discardableResult public mutating func observe(_ seconds: Int?, now: Int64) -> Bool {
            guard seconds != self.seconds else { if since > now { since = now }; return false } // a clock set back must not hold a value off for long
            self.seconds = seconds; since = now
            return true
        }

        /// The timer that deletes at `now`, in ms: nil while there is none (off, not known) or the value hasn't settled.
        /// Two admins changing it at once can make a value that loses current for a moment on some phones, and deleting
        /// can't be undone.
        public func deletes(now: Int64) -> Int64? {
            guard let seconds, seconds > 0, now - since >= Disappear.settleMs else { return nil }
            return Int64(seconds) * 1_000
        }

        /// When the value will have settled.
        public var settledAt: Int64 { since + Disappear.settleMs }

        /// As it is saved: "<seconds or ->:<since>".
        public var saved: String { "\(seconds.map(String.init) ?? "-"):\(since)" }
        public init?(saved: String) {
            let f = saved.split(separator: ":", omittingEmptySubsequences: false)
            guard f.count == 2, let since = Int64(f[1]), f[0] == "-" || Int(f[0]) != nil else { return nil }
            self.init(seconds: Int(f[0]), since: since)
        }
    }

    /// The names that go now under a timer of `timerMs`: on this phone for that long. Same counting as `KeepsLess`:
    /// from when a packet reached this phone, or, written here, first left it; one that hasn't left never goes.
    public static func due(_ held: some Collection<KeepsLess.Held>, timerMs: Int64, now: Int64) -> [String] {
        let (cut, over) = now.subtractingReportingOverflow(timerMs)
        guard timerMs > 0, !over else { return [] }
        return held.filter { h in h.since.map { $0 <= cut } ?? false }.map(\.name)
    }

    /// When the next of `held` goes under a timer of `timerMs`, or nil if none will.
    public static func next(_ held: some Collection<KeepsLess.Held>, timerMs: Int64) -> Int64? {
        guard timerMs > 0 else { return nil }
        return held.compactMap(\.since).min().map { $0 + timerMs }
    }

    /// Whether a packet the timer removed stays in the store, to be carried for others for the rest of its week.
    /// Only if nothing on this phone opens it any more (its own message key was deleted when it was read): then it is
    /// one more packet this phone carries and can't read. One that a held key still opens (a receipt, a message sealed
    /// with the epoch's key or still waiting for its sender's key) leaves the store. High-risk mode's day removes
    /// the packet either way.
    public static func carries(stillOpens: Bool) -> Bool { DayBook.storesAgain(stillOpens: stillOpens) }
}
