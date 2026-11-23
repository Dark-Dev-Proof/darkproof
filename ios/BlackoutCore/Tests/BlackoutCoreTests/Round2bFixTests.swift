import XCTest
@testable import BlackoutCore

/// The fixes after the independent review of round 2b (timer, leave note, check-in clock): one test per finding the
/// core can show. The vectors' part is in LeaveVectorsTests (rival epochs, H2) and TimerVectorsTests (late keys, M4).
final class Round2bFixTests: XCTestCase {
    private let anna = "1111111111111111", ben = "2222222222222222", cara = "3333333333333333"
    private let t0: Int64 = 1_794_000_000_000, hour: Int64 = 3_600_000, minute: Int64 = 60_000

    private func clock(_ b: inout QuietBook, _ member: String, ts: Int64, deadline: Int64, spot: Spot? = nil, now: Int64? = nil) {
        XCTAssertTrue(b.take(member: member, id: "e60800000000000" + String(member.prefix(1)), mark: "0011223344556677", ts: ts, deadline: deadline, spot: spot, now: now ?? ts, highRisk: false))
    }

    // MARK: - H1: a leave note, and the removal after it, don't end a clock

    func testALeaveNoteAndTheRemovalAfterItKeepTheClock() {
        var book = QuietBook(), leaving = LeaveBook()
        clock(&book, cara, ts: t0, deadline: t0 + 8 * hour)
        clock(&book, ben, ts: t0, deadline: t0 + 8 * hour)
        // Cara left with a note; she is still listed. The engine's rule (Engine.sweepQuiet): in the current epoch, or left.
        leaving.take(member: cara, ts: t0 + hour, epoch: 5, now: t0 + hour)
        var current: Set<String> = [anna, ben, cara]
        func sweep(_ now: Int64) { book.sweep(now: now, highRisk: false) { current.contains($0) || leaving.left($0) } }
        sweep(t0 + hour)
        XCTAssertTrue(book.armed(cara), "the note doesn't stop her clock")
        // The removal that followed the note: no longer listed, the removal kept with the record. Ben is taken out too,
        // by another removal, with no note of his.
        current = [anna]
        leaving.sweep(inCurrent: { current.contains($0) }, removal: { $0 == self.cara ? "removal-6" : nil }, held: ["removal-6"], now: t0 + hour + minute)
        XCTAssertTrue(leaving.left(cara)); XCTAssertNil(leaving.record(cara))
        sweep(t0 + 2 * hour)
        XCTAssertTrue(book.armed(cara), "nor does the removal that followed it")
        XCTAssertEqual(book.alerts(now: t0 + 8 * hour, me: anna).map(\.clock.member), [cara], "it alerts at its deadline as usual")
        // Ben is removed without a note of his: his clock goes without an alert (Q9).
        XCTAssertFalse(leaving.left(ben))
        XCTAssertNil(book.clock(ben))
        // Rule Q1 with a note held (L3): a check-in dated at or before the note still counts and cancels; one after it doesn't.
        var l = LeaveBook(); l.take(member: cara, ts: t0 + hour, epoch: 5, now: t0 + hour)
        XCTAssertFalse(l.hides(cara, ts: t0 + hour)); XCTAssertTrue(l.hides(cara, ts: t0 + hour + 1))
    }

    // MARK: - M5: Leave keeps its note's packet

    func testLeaveKeepsTheNotesPacketEvenWhenItsCopyNamesIt() {
        let note = "e6080000000000aa"
        let r = LeaveCleanup.removed(packets: ["m1", "m2", note], copies: [(kept: "c-m1", packet: "m1"), (kept: "c-note", packet: note), (kept: "c-m3", packet: "m3")], keeping: note)
        XCTAssertFalse(r.packets.contains(note), "the note stays as a carried packet")
        XCTAssertEqual(r.packets, ["m1", "m2", "m3"])
        XCTAssertEqual(r.copies, ["c-m1", "c-note", "c-m3"], "its copy goes with the others")
        XCTAssertEqual(LeaveCleanup.removed(packets: ["m1"], copies: [], keeping: nil).packets, ["m1"])
    }

    // MARK: - M7: who the clock "Reached" is kept in the clock record

    func testKeepersLiveInTheRecordAndSurviveARestart() {
        var b = QuietBook()
        clock(&b, anna, ts: t0, deadline: t0 + 3 * hour)
        let id = b.clock(anna)!.id
        XCTAssertTrue(b.addKeeper(me: anna, clockId: id, keeper: ben))
        XCTAssertFalse(b.addKeeper(me: anna, clockId: id, keeper: ben), "once")
        XCTAssertFalse(b.addKeeper(me: anna, clockId: "e6080000000000ff", keeper: cara), "another message")
        XCTAssertFalse(b.addKeeper(me: anna, clockId: id, keeper: anna), "never this phone itself")
        XCTAssertTrue(b.addKeeper(me: anna, clockId: id, keeper: "web-" + cara))
        XCTAssertEqual(b.clock(anna)?.keepers, [ben, cara])
        XCTAssertEqual(QuietBook(saved: b.saved), b, "saved with the record, so the group's timer deleting the message doesn't lose it")
        // A line an older version wrote (12 fields, no keepers) still reads.
        let old = "c \(cara) e608000000000001 0011223344556677 \(t0) \(t0 + hour) \(t0) 0 1 - - -"
        XCTAssertEqual(QuietBook(saved: [old]).clock(cara)?.keepers, [])
        XCTAssertEqual(QuietBook(saved: [old]).clock(cara)?.scheduled, true)
    }

    // MARK: - M8: who a clock is from

    func testAClockNamesItsSenderSoAnotherAnnaCantStandForHer() {
        let emoji = ["🐶", "🐱", "🦁", "🐴", "🦄"]
        XCTAssertEqual(QuietWords.who("Anna", checked: true, shared: false, emoji: emoji), "Anna")
        XCTAssertEqual(QuietWords.who("Anna", checked: false, shared: false, emoji: emoji), "Anna (not checked)")
        XCTAssertEqual(QuietWords.who("Anna", checked: false, shared: true, emoji: emoji), "Anna (not checked)")
        XCTAssertEqual(QuietWords.who("Anna", checked: true, shared: true, emoji: emoji), "Anna · 🐶🐱🦁🐴", "the first four of her emoji")
        // Names match by their key: case, spaces and invisible characters don't make another name.
        XCTAssertEqual(Vouch.key("anna "), Vouch.key("Anna")); XCTAssertEqual(Vouch.key("An\u{200B}na"), Vouch.key("Anna"))
    }

    // MARK: - M9: a clock's position goes with what the timer deletes

    func testThePositionGoesWhenTheGroupsTimerWouldDeleteTheClockMessage() {
        var b = QuietBook()
        clock(&b, cara, ts: t0, deadline: t0 + 8 * hour, spot: Spot(lat: 45.4642, lon: 9.19, accuracy: 12), now: t0 + minute)
        XCTAssertFalse(b.sweep(now: t0 + hour, highRisk: false, positionMs: hour) { _ in true }, "an hour less a minute after it came")
        XCTAssertNotNil(b.clock(cara)?.spot)
        XCTAssertTrue(b.sweep(now: t0 + hour + minute, highRisk: false, positionMs: hour) { _ in true })
        XCTAssertNil(b.clock(cara)?.spot, "the message is gone: its position too")
        XCTAssertTrue(b.armed(cara), "the clock itself stays (rule Q10)")
        var c = QuietBook()
        clock(&c, cara, ts: t0, deadline: t0 + 8 * hour, spot: Spot(lat: 45.4642, lon: 9.19, accuracy: 12))
        XCTAssertFalse(c.sweep(now: t0 + 30 * hour, highRisk: false, positionMs: nil) { _ in true }, "no timer: kept with the record")
    }

    // MARK: - Low: clock alert storm

    func testAtMostOneClockAlertAboutAMemberEveryTenMinutes() {
        var b = QuietBook()
        clock(&b, cara, ts: t0, deadline: t0 + 5 * minute, now: t0 + hour) // learned late
        let first = b.clock(cara)!
        XCTAssertEqual(b.postableAt(first, late: true, now: t0 + hour), t0 + hour)
        b.didPost(first, now: t0 + hour)
        // A backdated clock with a greater ts: at most one alert in 10 minutes.
        clock(&b, cara, ts: t0 + minute, deadline: t0 + 6 * minute, now: t0 + hour + minute)
        let second = b.clock(cara)!
        XCTAssertEqual(b.postableAt(second, late: true, now: t0 + hour + minute), t0 + hour + QuietBook.alertGapMs, "posted when the 10 minutes are up")
        // A late one no newer than the one alerted: never.
        XCTAssertNil(b.postableAt(first, late: true, now: t0 + 2 * hour))
        // On time, a newer clock later on: fine.
        XCTAssertEqual(b.postableAt(second, late: false, now: t0 + 2 * hour), t0 + 2 * hour)
        XCTAssertEqual(QuietBook(saved: b.saved), b, "saved")
    }

    // MARK: - Low: notes and notices get no copy

    func testNotesAndNoticesGetNoCopy() throws {
        let secret = Data(repeating: 0x11, count: 32), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        let keys = MessageKeys.of(secret, members: [anna, ben]), key = Data(repeating: 3, count: 32)
        for (n, text, fifth) in [(40, LeaveNote.text, LeaveNote.field), (41, GroupTimer.notice(3600), GroupTimer.field(3600))] {
            let mk = try XCTUnwrap(keys.key(1, n))
            let c = try g.seal(messageKey: mk, index: 1, counter: n, id: "e6080000000000b\(n % 10)", ts: 77, sender: ben, name: "Ben", text: text, fifth: fifth) { _ in Data(repeating: 7, count: 64) }
            let o = try XCTUnwrap(g.open(messageKey: mk, index: 1, counter: n, Packet(id: "e6080000000000b\(n % 10)", ts: g.hideTime(77, c), ttl: 16, cipher: c)))
            XCTAssertTrue(o.message.isSilent)
            XCTAssertNil(History.seal(key: key, id: "e6080000000000b\(n % 10)", group: "gid", epoch: "eid", o, index: 1, counter: n), text)
        }
    }

    // MARK: - M10: the final erase screen names no group

    func testTheFinalEraseScreenNamesNoGroup() {
        XCTAssertEqual(LeaveWords.erased(sent: 3, left: 3), "The notes left this phone.")
        XCTAssertEqual(LeaveWords.erased(sent: 2, left: 1), "1 of 2 notes didn't leave this phone in time.")
        XCTAssertEqual(LeaveWords.erased(sent: 0, left: 0), "Your groups were not told.")
    }

    /// One note in the singular, as Android has it: never "1 of 1 notes".
    func testOneNoteIsSaidInTheSingular() {
        XCTAssertEqual(LeaveWords.erased(sent: 1, left: 1), "The note left this phone.")
        XCTAssertEqual(LeaveWords.erased(sent: 1, left: 0), "The note didn't leave this phone in time.")
        XCTAssertEqual(LeaveWords.erased(sent: 2, left: 0), "2 of 2 notes didn't leave this phone in time.")
    }
}
