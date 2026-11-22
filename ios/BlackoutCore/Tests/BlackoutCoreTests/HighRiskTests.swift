import XCTest
@testable import BlackoutCore

/// High-risk mode (Settings): "Keeps less" (a group's messages leave the phone a day after they arrive, and don't come
/// back), "Locks every time", when "New key" is offered, and what leaving a group removes from the preferences and
/// the key store.
final class HighRiskTests: XCTestCase {
    private let hour: Int64 = 3_600_000, day: Int64 = 86_400_000
    private let now: Int64 = 1_800_000_000_000

    private func held(_ name: String, since: Int64?) -> KeepsLess.Held { KeepsLess.Held(name: name, since: since) }

    /// A packet of the mailbox `box` whose content is `n`, dated `age` before now.
    private func packet(_ n: Int, age: Int64 = 0, id: String? = nil, box: UInt16 = 0x6a44) -> Packet {
        Packet(id: id ?? String(format: "%04x%012x", box, n), ts: now - age, ttl: 16, cipher: Data((0..<120).map { UInt8(truncatingIfNeeded: $0 &* 7 &+ n &* 13 &+ (n >> 8)) }))
    }

    // MARK: - Keeps less: a day on this phone

    /// The 24 hours count from when the message reached this phone, not from when it was written: one that was written
    /// three days ago and arrives now is readable for a day.
    func testAMessageThatArrivesLateStaysADay() {
        let arrived = now // written 3 days ago, here since now
        let h = [held("late", since: arrived)]
        XCTAssertEqual(KeepsLess.expired(h, now: now), [])
        XCTAssertEqual(KeepsLess.expired(h, now: now + 23 * hour), [])
        XCTAssertEqual(KeepsLess.expired(h, now: now + day), [])       // a day to the millisecond: still here
        XCTAssertEqual(KeepsLess.expired(h, now: now + day + 1), ["late"])
    }

    func testAMessageGoesADayAfterItArrived() {
        let h = [held("a", since: now - day - 1), held("b", since: now - day), held("c", since: now - hour), held("d", since: now - 6 * day)]
        XCTAssertEqual(Set(KeepsLess.expired(h, now: now)), ["a", "d"])
    }

    /// Written here and nobody in reach: it has not left this phone, so the rule never takes it, however old it is.
    func testAnOwnMessageNobodyTookNeverGoes() {
        let h = [held("i-need-help", since: nil)]
        for later in [Int64(0), day + 1, 6 * day, 400 * day] { XCTAssertEqual(KeepsLess.expired(h, now: now + later), [], "after \(later) ms") }
    }

    /// For a message written here the day starts when it is first handed to a link ("Sent"), not when it was written.
    func testAnOwnMessageGoesADayAfterItWasHandedOff() {
        var book = DayBook()
        let p = packet(1, age: 3 * day), key = MeshRouter.key(p) // written 3 days ago, out of reach since
        XCTAssertNil(book.since(key))
        XCTAssertEqual(KeepsLess.expired([held(p.id, since: book.since(key))], now: now), [])
        XCTAssertTrue(book.note(key, at: now)) // a phone came in reach: handed off now
        XCTAssertEqual(KeepsLess.expired([held(p.id, since: book.since(key))], now: now + day), [])
        XCTAssertEqual(KeepsLess.expired([held(p.id, since: book.since(key))], now: now + day + 1), [p.id])
    }

    /// The first time counts: a copy that comes again, or a second link, doesn't start the day over.
    func testOnlyTheFirstArrivalCounts() {
        var book = DayBook()
        XCTAssertTrue(book.note("k", at: now))
        XCTAssertFalse(book.note("k", at: now + 20 * hour))
        XCTAssertEqual(book.since("k"), now)
    }

    /// What was stored before the phone noted arrivals has no time: it counts from its own date. A message written
    /// here that has not left stays all the same.
    func testPacketsStoredBeforeTimesWereKeptCountFromTheirOwnDate() {
        let written = now - 3 * day
        // received, stored by an older version: its own date, so it goes at the first clean-up
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: false, receipt: false, date: written, handedOff: nil, beforeTimes: true, now: now), written)
        // written here and never handed to anyone: no time, it stays
        XCTAssertNil(KeepsLess.sinceUnnoted(own: true, receipt: false, date: written, handedOff: nil, beforeTimes: true, now: now))
        XCTAssertNil(KeepsLess.sinceUnnoted(own: true, receipt: false, date: written, handedOff: nil, beforeTimes: false, now: now))
        // written here and "Sent": when it left, if that was kept, else its own date
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: true, receipt: false, date: written, handedOff: .some(now - hour), beforeTimes: true, now: now), now - hour)
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: true, receipt: false, date: written, handedOff: .some(nil), beforeTimes: true, now: now), written)
        // a receipt written here counts from when it was written, whatever became of it
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: true, receipt: true, date: written, handedOff: nil, beforeTimes: false, now: now), written)
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: true, receipt: true, date: written, handedOff: nil, beforeTimes: true, now: now), written)
    }

    /// Once times are noted, a packet without one lost it when the app ended within seconds of its arrival: its day
    /// starts at the next start, never at its own date, or a message that came late could go unread.
    func testATimeThatWasNotSavedStartsAgainNeverEarlier() {
        let written = now - 3 * day
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: false, receipt: false, date: written, handedOff: nil, beforeTimes: false, now: now), now)
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: true, receipt: false, date: written, handedOff: .some(nil), beforeTimes: false, now: now), now)
        XCTAssertEqual(KeepsLess.expired([held("late", since: KeepsLess.sinceUnnoted(own: false, receipt: false, date: written, handedOff: nil, beforeTimes: false, now: now))], now: now + day), [])
        // dated or handed off ahead of the clock: from now
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: false, receipt: false, date: now + hour, handedOff: nil, beforeTimes: true, now: now), now)
        XCTAssertEqual(KeepsLess.sinceUnnoted(own: true, receipt: false, date: written, handedOff: .some(now + day), beforeTimes: true, now: now), now)
    }

    // MARK: - Keeps less: what it covers

    func testTheRuleCoversMessagesAndReceiptsOfAGroupOnly() {
        XCTAssertTrue(KeepsLess.covers(.message, isPublic: false))
        XCTAssertTrue(KeepsLess.covers(.receipt, isPublic: false))
        XCTAssertFalse(KeepsLess.covers(.introduction, isPublic: false)) // what a group runs on stays a week:
        XCTAssertFalse(KeepsLess.covers(.newKey, isPublic: false))       // phones that were away need it
        XCTAssertFalse(KeepsLess.covers(.nothing, isPublic: false))      // carried for others
        XCTAssertFalse(KeepsLess.covers(.message, isPublic: true))       // Everyone
        XCTAssertFalse(KeepsLess.covers(.introduction, isPublic: true))
        XCTAssertFalse(KeepsLess.covers(.message, isPublic: false, forged: true)) // thrown out: like a packet nobody can open
        XCTAssertFalse(KeepsLess.covers(.receipt, isPublic: false, forged: true))
    }

    /// The claim: with the rule choosing by the 16-bit mailbox in a packet's id, a group whose door has the mailbox of
    /// one of its message keys lost its introductions and new keys after a day. The rule is given only what opened as
    /// a message or a receipt, so the same mailbox changes nothing.
    func testDoorPacketsWithACollidingMailboxStay() {
        let box: UInt16 = 0x6a44 // the door's mailbox and a message key's: the same by chance
        let stored: [(p: Packet, opened: KeepsLess.Opened)] = [(packet(1, box: box), .message), (packet(2, box: box), .receipt),
                                                               (packet(3, box: box), .introduction), (packet(4, box: box), .newKey), (packet(5, box: box), .nothing)]
        XCTAssertEqual(Set(stored.map { Packet.mailbox(of: $0.p.id) }), [box])
        var book = DayBook()
        for s in stored { book.note(MeshRouter.key(s.p), at: now - 5 * day) } // all here for 5 days
        let covered = stored.filter { KeepsLess.covers($0.opened, isPublic: false) }.map { held($0.p.id, since: book.since(MeshRouter.key($0.p))) }
        XCTAssertEqual(Set(KeepsLess.expired(covered, now: now)), [stored[0].p.id, stored[1].p.id])
    }

    // MARK: - Keeps less: the clock

    /// A clock that went backwards must never make this rule delete: set back, it removes a part of what it would
    /// have removed, never anything else.
    func testAClockSetBackRemovesLessNeverMore() {
        let h = (0..<200).map { held("m\($0)", since: now - Int64($0) * hour) }
        let right = Set(KeepsLess.expired(h, now: now))
        XCTAssertFalse(right.isEmpty)
        for back in [hour, 12 * hour, day, 3 * day, 30 * day, 25 * 365 * day] {
            let wrong = Set(KeepsLess.expired(h, now: now - back))
            XCTAssertTrue(wrong.isSubset(of: right), "set back by \(back) ms")
            if back >= 9 * day { XCTAssertTrue(wrong.isEmpty) }
        }
    }

    /// Only an arrival in the past counts: noted with the clock ahead, a packet stays until the clock has passed that.
    func testAnArrivalAheadOfTheClockDoesNotCount() {
        let h = [held("ahead", since: now + 3 * day), held("just", since: now)]
        for clock in [now, Int64(978_307_200_000), 0, -1, Int64.min, Int64.min + KeepsLess.keepMs] { XCTAssertEqual(KeepsLess.expired(h, now: clock), [], "clock \(clock)") }
        XCTAssertEqual(KeepsLess.expired(h, now: now + 4 * day), ["just"])
        XCTAssertEqual(Set(KeepsLess.expired(h, now: now + 4 * day + 1)), ["ahead", "just"])
    }

    func testAnyTimeIsTaken() {
        let h = [held("min", since: .min), held("max", since: .max)]
        XCTAssertEqual(KeepsLess.expired(h, now: now), ["min"])
        XCTAssertEqual(KeepsLess.expired(h, now: .max), ["min"])
        XCTAssertEqual(KeepsLess.expired(h, now: .min), [])
    }

    // MARK: - Keeps less: the gone list

    /// The claim: a message the rule removed was shown again, with a new alert, as soon as the app had restarted and a
    /// phone that still carries it for 7 days came in reach. It still arrives, and is kept and passed on like a packet
    /// this phone can't open; but it is counted as one, so nothing opens, shows, alerts or confirms it.
    func testARemovedPacketThatComesBackIsCarriedButNeverOpenedAgain() {
        let p = packet(7, age: 30 * hour)
        var book = DayBook()
        book.note(MeshRouter.key(p), at: now - 25 * hour)
        XCTAssertNil(book.kind(of: p)) // here for 25 hours: opened and shown as usual
        // The rule removes it.
        XCTAssertEqual(KeepsLess.expired([held(p.id, since: book.since(MeshRouter.key(p)))], now: now), [p.id])
        book.remove(MeshRouter.key(p), ts: p.ts, group: "g1")
        XCTAssertNil(book.since(MeshRouter.key(p)))
        // After a restart the router remembers nothing of it: the neighbour's copy is new to it, delivered and passed on.
        book = DayBook(saved: book.saved)
        var delivered: [Packet] = [], passedOn: [String] = []
        let router = MeshRouter(send: { peer, _ in passedOn.append(peer) }, deliver: { delivered.append($0) }, now: { self.now })
        router.peerUp("neighbour"); router.peerUp("other")
        XCTAssertTrue(router.receive(from: "neighbour", p))
        XCTAssertEqual(delivered, [p])
        XCTAssertEqual(passedOn, ["other"])                 // still passed on to others
        XCTAssertEqual(book.kind(of: p), .unreadable)       // and counted as what this phone can't open: never shown
        XCTAssertTrue(book.isGone(p))
        // Into the store again, to be carried across a restart, only if no key here opens it any more: what this
        // phone could still read (a receipt, a message whose key wasn't deleted) is passed on from memory and not written.
        XCTAssertTrue(DayBook.storesAgain(stillOpens: false))
        XCTAssertFalse(DayBook.storesAgain(stillOpens: true))
        // A week old by its date, the store's own clean-up drops it; only then does its name go.
        XCTAssertFalse(book.expire(now: p.ts + DayBook.carryMs))
        XCTAssertTrue(book.isGone(p))
        XCTAssertTrue(book.expire(now: p.ts + DayBook.carryMs + 1))
        XCTAssertFalse(book.isGone(p))
        XCTAssertFalse(MeshRouter(send: { _, _ in }, deliver: { _ in }, now: { p.ts + DayBook.carryMs + 1 }).receive(from: "neighbour", p)) // and no phone takes it any more
    }

    /// Gone is this very packet: its id and its content. Another packet sent under its id is not it.
    func testAnotherPacketUnderAGoneIdIsNotGone() {
        let p = packet(1), other = packet(2, id: p.id)
        var book = DayBook()
        book.remove(MeshRouter.key(p), ts: p.ts, group: "g1")
        XCTAssertTrue(book.isGone(p))
        XCTAssertFalse(book.isGone(other))
        XCTAssertNil(book.kind(of: other))
        XCTAssertFalse(book.isGone(packet(3)))
    }

    func testTheBookSurvivesARestart() {
        var book = DayBook()
        let a = packet(1), b = packet(2, age: 2 * day), c = packet(3)
        book.note(MeshRouter.key(a), at: now - hour)
        book.note(MeshRouter.key(c), at: now - 2 * hour)
        book.remove(MeshRouter.key(b), ts: b.ts, group: "00112233445566778899aabbccddeeff")
        let again = DayBook(saved: book.saved)
        XCTAssertEqual(again, book)
        XCTAssertEqual(again.since(MeshRouter.key(a)), now - hour)
        XCTAssertTrue(again.isGone(b))
        XCTAssertEqual(again.gone[MeshRouter.key(b)], DayBook.Gone(ts: b.ts, group: "00112233445566778899aabbccddeeff"))
        XCTAssertFalse(String(decoding: book.saved, as: UTF8.self).contains("hello")) // names and times only
        XCTAssertTrue(DayBook(saved: DayBook().saved).isEmpty)
    }

    func testADamagedSavedBookStillReads() {
        let saved = Data("s k1 100\nnonsense\ng k2 5 g1\ns k3 notanumber\ng k4\n\ns k5 7".utf8)
        let book = DayBook(saved: saved)
        XCTAssertEqual(book.since, ["k1": 100, "k5": 7])
        XCTAssertEqual(Array(book.gone.keys), ["k2"])
    }

    /// At most 5,000 names: beyond that the oldest packets' go first (they are the nearest to their week).
    func testTheGoneListIsCapped() {
        var book = DayBook()
        for i in 0..<(DayBook.maxGone + 25) { let p = packet(i, age: Int64(i) * 1_000); book.remove(MeshRouter.key(p), ts: p.ts, group: "g1") }
        XCTAssertEqual(book.gone.count, DayBook.maxGone)
        XCTAssertTrue(book.isGone(packet(0, age: 0)))                                   // the newest stays
        XCTAssertTrue(book.isGone(packet(DayBook.maxGone - 1, age: Int64(DayBook.maxGone - 1) * 1_000)))
        XCTAssertFalse(book.isGone(packet(DayBook.maxGone, age: Int64(DayBook.maxGone) * 1_000))) // the 25 oldest went
        XCTAssertFalse(book.isGone(packet(DayBook.maxGone + 24, age: Int64(DayBook.maxGone + 24) * 1_000)))
        XCTAssertEqual(DayBook(saved: book.saved).gone.count, DayBook.maxGone)
    }

    func testAGoneNameExpiresWhenItsPacketIsAWeekOld() {
        var book = DayBook()
        let fresh = packet(1, age: day), old = packet(2, age: 7 * day + 1), edge = packet(3, age: 7 * day)
        for p in [fresh, old, edge] { book.remove(MeshRouter.key(p), ts: p.ts, group: "g1") }
        XCTAssertFalse(book.expire(now: now - day)) // a clock set back drops fewer
        XCTAssertEqual(book.gone.count, 3)
        XCTAssertTrue(book.expire(now: now))
        XCTAssertTrue(book.isGone(fresh)); XCTAssertTrue(book.isGone(edge)); XCTAssertFalse(book.isGone(old))
        XCTAssertFalse(book.expire(now: .min)) // a clock that can't be
        XCTAssertEqual(book.gone.count, 2)
    }

    func testLeavingAGroupClearsItsGoneNames() {
        var book = DayBook()
        let a = packet(1), b = packet(2), c = packet(3, id: packet(1).id)
        book.remove(MeshRouter.key(a), ts: a.ts, group: "left")
        book.remove(MeshRouter.key(b), ts: b.ts, group: "stays")
        book.remove(MeshRouter.key(c), ts: c.ts, group: "stays") // under the id of a packet of the group that is left
        XCTAssertTrue(book.forget(group: "left"))
        XCTAssertFalse(book.isGone(a)); XCTAssertTrue(book.isGone(b)); XCTAssertTrue(book.isGone(c))
        XCTAssertFalse(book.forget(group: "left"))
    }

    /// What leaves the store by another rule leaves nothing behind, and a packet that is no longer stored isn't timed.
    func testNothingIsKeptOfPacketsThatLeftTheStore() {
        var book = DayBook()
        for k in ["a", "b", "c", "d"] { book.note(k, at: now) }
        book.forget(["a"])
        XCTAssertTrue(book.keepOnly(["b", "c", "x"]))
        XCTAssertEqual(Set(book.since.keys), ["b", "c"])
        XCTAssertFalse(book.keepOnly(["b", "c"]))
    }

    // MARK: - New key (Group security)

    /// The claim: an admin could ask for a new key every hour, each one several full-size packets for everyone, and
    /// after 64 the ring pushed out keys that phones still needed. It is offered only for a key a day old.
    func testANewKeyCanBeAskedOnlyForAKeyADayOld() {
        XCTAssertFalse(Keyring.mayAsk(since: now, now: now))
        XCTAssertFalse(Keyring.mayAsk(since: now - hour, now: now))
        XCTAssertFalse(Keyring.mayAsk(since: now - day + 1, now: now))
        XCTAssertTrue(Keyring.mayAsk(since: now - day, now: now))
        XCTAssertTrue(Keyring.mayAsk(since: now - 9 * day, now: now))
        XCTAssertFalse(Keyring.mayAsk(since: now + hour, now: now))  // dated ahead: not old
        XCTAssertFalse(Keyring.mayAsk(since: .max, now: now))
        XCTAssertFalse(Keyring.mayAsk(since: .min, now: now))        // a time that can't be: no
        XCTAssertFalse(Keyring.mayAsk(since: now, now: now - 3 * day)) // the clock set back
    }

    // MARK: - Locks every time

    func testHighRiskAsksWheneverTheAppWasLeft() {
        XCTAssertTrue(HighRiskLock.asksAgain(unlockedOnce: true, awaySeconds: 0))
        XCTAssertTrue(HighRiskLock.asksAgain(unlockedOnce: true, awaySeconds: 0.2))
        XCTAssertTrue(HighRiskLock.asksAgain(unlockedOnce: true, awaySeconds: 59))   // inside the normal lock's minute
        XCTAssertTrue(HighRiskLock.asksAgain(unlockedOnce: true, awaySeconds: -5))   // a time that can't be
        XCTAssertTrue(HighRiskLock.asksAgain(unlockedOnce: false, awaySeconds: nil)) // first open
        XCTAssertFalse(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: 59))     // what the normal lock does there
    }

    func testHighRiskDoesNotAskWhileTheAppStayedOpen() {
        XCTAssertFalse(HighRiskLock.asksAgain(unlockedOnce: true, awaySeconds: nil))
    }

    // MARK: - Leaving a group

    private let id = "00112233445566778899aabbccddeeff", other = "ffeeddccbbaa99887766554433221100"

    func testLeavingFindsEveryKeyOfTheGroup() {
        let mine = ["epochs.\(id)", "mkeys.\(id)", "parts.\(id)", "door.\(id)", "enc.\(id)", "chip.enc.\(id)", "mlkem.\(id)",
                    "enc.\(id).3", "chip.enc.\(id).3", "mlkem.\(id).12", "chip.sign.\(id)", id]
        let others = ["epochs.\(other)", "mkeys.\(other)", "enc.\(other).3", "chip.sign.\(other)", other, "history", "groups", "origin.\(other)"]
        XCTAssertEqual(Set(LeftGroup.secrets(mine + others, group: id)), Set(mine))
        XCTAssertEqual(LeftGroup.secrets(mine + others, group: ""), [])
    }

    func testLeavingClearsThePreferencesOfTheGroupOnly() {
        let all: [String: Any] = [
            "introAt.\(id)": 5.0, "known.\(id)": ["a": ["p": "00"]], "rejoining.\(id)": 1.0, "removedAt.\(id)": "3:0:x", "closed.\(id)": true,
            "declined.\(id)": ["aaaa"], "encs.\(id)": "0:1", "relaySent.\(id)": ["p1"], "relaySince.\(id)": "s",
            "introAt.\(other)": 6.0, "relaySent.\(other)": ["p2"], "relaySent": ["p3"],
            "checked": ["\(id)|aaaa|ben", "\(other)|bbbb|ida"], "checkedInPerson": ["\(id)|aaaa"],
            "blocked": ["\(id)|cccc": "Carl", "\(other)|dddd": "Dora", "everyone|eeee": "Eve"],
            "lastReadTags": ["tagNow": Int64(5), "tagOther": Int64(6), "everyone": Int64(7)],
            "movedTags": ["tagOld>tagNow", "tagX>tagOther"],
            "handedOff": ["m1", "m2", "z9"], "hidden": ["m2"], "reports": ["z8"],
            "name": "Anna", "groupName": "Home", "highRisk": true,
        ]
        let r = LeftGroup.preferences(all, group: id, tags: ["tagNow", "tagOld"], messages: ["m1", "m2"])
        XCTAssertEqual(r.remove, ["closed.\(id)", "declined.\(id)", "encs.\(id)", "introAt.\(id)", "known.\(id)", "rejoining.\(id)", "relaySent.\(id)",
                                  "relaySince.\(id)", "removedAt.\(id)"])
        XCTAssertEqual(Set(r.change.keys), ["checked", "checkedInPerson", "blocked", "lastReadTags", "movedTags", "handedOff", "hidden"])
        XCTAssertEqual(r.change["checked"] as? [String], ["\(other)|bbbb|ida"])
        XCTAssertEqual(r.change["checkedInPerson"] as? [String], [])
        XCTAssertEqual(r.change["blocked"] as? [String: String], ["\(other)|dddd": "Dora", "everyone|eeee": "Eve"])
        XCTAssertEqual(r.change["lastReadTags"] as? [String: Int64], ["tagOther": 6, "everyone": 7])
        XCTAssertEqual(r.change["movedTags"] as? [String], ["tagX>tagOther"])
        XCTAssertEqual(r.change["handedOff"] as? [String], ["z9"])
        XCTAssertEqual(r.change["hidden"] as? [String], [])
    }

    func testLeavingWithoutAGroupIdTouchesNothing() {
        let r = LeftGroup.preferences(["checked": ["|x|y"], "a.": 1], group: "", tags: [], messages: [])
        XCTAssertTrue(r.remove.isEmpty); XCTAssertTrue(r.change.isEmpty)
    }
}

/// "Erase everything": the folders the app writes in are emptied, and what is left is found.
final class FolderWipeTests: XCTestCase {
    private var root: URL!
    private let fm = FileManager.default

    override func setUpWithError() throws {
        root = fm.temporaryDirectory.appendingPathComponent("wipe-\(UUID().uuidString)")
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDown() { try? fm.removeItem(at: root) }

    private func write(_ path: String, _ text: String = "x") throws {
        let u = root.appendingPathComponent(path)
        try fm.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: u)
    }

    /// The files the app really keeps, by their names, hidden folders included.
    func testEverythingInAFolderGoes() throws {
        for f in ["packets.bin", "more.bin", "history.bin", "history.bin.new", "known.00112233445566778899aabbccddeeff.plist", "ai-conversation.json",
                  "model.gguf", "model.gguf.part", "debug.log", "it.darkproof.mesh/.mapbox/cache.db", ".hidden", "tor/state", "tor/keys/secret"] { try write(f) }
        try fm.createDirectory(at: root.appendingPathComponent("empty/inside"), withIntermediateDirectories: true)
        XCTAssertEqual(FolderWipe.files(in: root).count, 13)
        FolderWipe.empty(root)
        XCTAssertEqual(FolderWipe.files(in: root), [])
        XCTAssertEqual(try fm.contentsOfDirectory(atPath: root.path), []) // the empty folders went too
        XCTAssertTrue(fm.fileExists(atPath: root.path))                   // the folder itself stays
    }

    func testWhatIsKeptStaysAndIsNotCounted() throws {
        try write("Preferences/app.plist"); try write("Caches/tor/state"); try write("top")
        FolderWipe.empty(root, keep: ["Preferences"])
        XCTAssertEqual(FolderWipe.files(in: root), ["Preferences/app.plist"])
        XCTAssertEqual(FolderWipe.files(in: root, keep: ["Preferences"]), [])
    }

    /// What is written after the wipe is found: a part that still runs must not go unnoticed.
    func testAFileWrittenAfterwardsIsFound() throws {
        try write("a/b/c")
        FolderWipe.empty(root)
        try write("tor/state"); try write(".mapbox/cache.db")
        XCTAssertEqual(FolderWipe.files(in: root), [".mapbox/cache.db", "tor/state"])
    }

    /// A link is removed, and what it points at, outside the folder, is not touched.
    func testALinkIsRemovedNotFollowed() throws {
        let outside = fm.temporaryDirectory.appendingPathComponent("outside-\(UUID().uuidString)")
        try fm.createDirectory(at: outside, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: outside) }
        try Data("keep".utf8).write(to: outside.appendingPathComponent("file"))
        try fm.createSymbolicLink(at: root.appendingPathComponent("link"), withDestinationURL: outside)
        XCTAssertEqual(FolderWipe.files(in: root), ["link"])
        FolderWipe.empty(root)
        XCTAssertEqual(FolderWipe.files(in: root), [])
        XCTAssertTrue(fm.fileExists(atPath: outside.appendingPathComponent("file").path))
    }

    func testAFolderThatIsNotThereIsEmpty() {
        let gone = root.appendingPathComponent("nothing")
        FolderWipe.empty(gone)
        XCTAssertEqual(FolderWipe.files(in: gone), [])
    }
}
