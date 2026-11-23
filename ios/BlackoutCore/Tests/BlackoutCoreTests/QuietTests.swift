import XCTest
import CryptoKit
@testable import BlackoutCore

/// "If I go quiet" (spec/FORMAT.md, "If I go quiet"): the clock's field (Q2), which messages act on clocks (Q1, Q4),
/// which clock counts (Q3), what is due and when (Q5 to Q8), the saved records (Q9, Q10, Q12), the wording, and that a
/// reader without the feature sees an ordinary message with the ⏳ text and nothing else.
final class QuietTests: XCTestCase {
    let code = "test-group-quiet"
    lazy var group = GroupCrypto(code: code)
    let iv = Data(repeating: 9, count: 12)
    let sig = Data(repeating: 7, count: 64)
    let anna = "1111111111111111", ben = "2222222222222222", cara = "3333333333333333"
    let t0: Int64 = 1_794_000_000_000, minute: Int64 = 60_000, hour: Int64 = 3_600_000

    // MARK: - Readers as they were before this feature (frozen copies)

    /// A reader from before the 5th field: sender, name, text, then a 4th field if the bytes are there. It stops there.
    private func preFifthRead(_ plain: Data) -> (sender: String, name: String, text: Data, fourth: Data?)? {
        let b = [UInt8](plain.dropFirst(64)); var i = 0
        func next() -> [UInt8]? {
            guard i + 2 <= b.count else { return nil }
            let n = Int(b[i]) << 8 | Int(b[i + 1])
            guard i + 2 + n <= b.count else { return nil }
            defer { i += 2 + n }
            return Array(b[(i + 2)..<(i + 2 + n)])
        }
        guard let s = next(), let n = next(), let t = next() else { return nil }
        let fourth = next().flatMap { $0.isEmpty ? nil : Data($0) }
        return (String(decoding: s, as: UTF8.self), String(decoding: n, as: UTF8.self), Data(t), fourth)
    }

    /// The build before this one: replies and reactions (17 bytes, types 1 and 2), the timer's notice (5 bytes, type 3)
    /// and the leave note (exactly `05 01`). Anything else in the 5th field: an ordinary message.
    private func leaveBuildReads(_ field: Data?) -> String {
        if let field, field.count == 17, field.first == 0x01 || field.first == 0x02 { return "about" }
        if let field, field.count == 5, field.first == 0x03 { return "timer notice" }
        if let field, field == Data([0x05, 0x01]) { return "leave" }
        return "ordinary"
    }

    private func plain(_ cipher: Data, id: String, ts: Int64) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: cipher), using: SymmetricKey(data: GroupCrypto.deriveKey(code)), authenticating: Data("\(id)|\(ts)".utf8))
    }

    private func sealed(_ id: String, ts: Int64, text: String, fifth: Data?) throws -> GroupCrypto.Opened {
        let c = try group.seal(id: id, ts: ts, sender: cara, name: "Cara", text: text, fifth: fifth, iv: iv) { _ in self.sig }
        return try XCTUnwrap(group.openSigned(Packet(id: id, ts: ts, ttl: 16, cipher: c)))
    }

    // MARK: - The field (Q2)

    func testTheBriefsExample() {
        let ts: Int64 = 1_793_807_238_631, d = ts + hour
        XCTAssertEqual(d, 1_793_810_838_631)
        XCTAssertEqual(Hex.encode(QuietClock.field(deadline: d)), "04000001a1a7753467")
        XCTAssertEqual(QuietClock.raw(field: QuietClock.field(deadline: d)), d)
        XCTAssertEqual(QuietClock.read(field: QuietClock.field(deadline: d), ts: ts), d)
        XCTAssertEqual(Notice.read(field: QuietClock.field(deadline: d), ts: ts), .clock(d))
        XCTAssertNil(Notice.read(field: QuietClock.field(deadline: d)), "no time, no clock")
        XCTAssertEqual(QuietClock.read(field: QuietClock.field(deadline: 0), ts: ts), 0, "a stop")
        XCTAssertEqual(QuietClock.text(ts: ts, deadline: d), "⏳ Check on me if I don't check in within 1 hour")
        XCTAssertEqual(Data(QuietClock.text(ts: ts, deadline: d).utf8).count, 49)
    }

    func testOtherLengthsAndTypesAreIgnored() {
        let f = QuietClock.field(deadline: t0 + hour)
        for other in [Data(f.prefix(8)), f + Data([0]), Data([0x06]) + f.dropFirst(), Data(), Data([0x04])] {
            XCTAssertNil(QuietClock.raw(field: other), Hex.encode(other))
            XCTAssertNil(Notice.read(field: other, ts: t0), Hex.encode(other))
        }
        XCTAssertNil(QuietClock.raw(field: nil))
    }

    func testWhichDeadlinesCount() {
        XCTAssertFalse(QuietClock.valid(deadline: t0 + 299_000, ts: t0))
        XCTAssertTrue(QuietClock.valid(deadline: t0 + 5 * minute, ts: t0))
        XCTAssertTrue(QuietClock.valid(deadline: t0 + 24 * hour, ts: t0))
        XCTAssertFalse(QuietClock.valid(deadline: t0 + 24 * hour + 1, ts: t0))
        XCTAssertFalse(QuietClock.valid(deadline: t0 - 1, ts: t0), "in the past")
        XCTAssertFalse(QuietClock.valid(deadline: -1, ts: t0))
        XCTAssertTrue(QuietClock.valid(deadline: 0, ts: t0), "a stop")
        XCTAssertFalse(QuietClock.valid(deadline: .max, ts: t0), "years ahead")
        XCTAssertFalse(QuietClock.valid(deadline: .max, ts: .max), "no overflow")
    }

    // MARK: - In a message

    func testAClockIsACardThatAlertsConfirmsAndGoesOnTheRadio() throws {
        let d = t0 + hour, text = QuietClock.text(ts: t0, deadline: d)
        let o = try sealed("00000000000000d1", ts: t0, text: text, fifth: QuietClock.field(deadline: d))
        XCTAssertEqual(o.message.notice, .clock(d)); XCTAssertNil(o.message.about)
        XCTAssertFalse(o.message.isSilent, "an ordinary message: a row, an alert, a receipt")
        XCTAssertFalse(o.message.offRadio, "it matters like a check-in")
        XCTAssertEqual(QuietClock.effect(of: o.message), .clock(d))
        // This phone's copy keeps the field, and reads the clock back.
        let blob = try XCTUnwrap(History.seal(key: Data(repeating: 1, count: 32), id: "00000000000000d1", group: "g", epoch: "e", o, index: 0, counter: 0))
        let back = try XCTUnwrap(History.open(key: Data(repeating: 1, count: 32), id: "00000000000000d1", blob: blob))
        XCTAssertEqual(back.opened.message.notice, .clock(d)); XCTAssertEqual(back.opened.message.text, text)
        // The Public channel reads none of it.
        XCTAssertNil(o.message.onPublicChannel(mine: false).notice)
        XCTAssertNil(QuietClock.effect(of: o.message.onPublicChannel(mine: false)))
    }

    func testADeadlineOutOfRangeIsAnOrdinaryMessage() throws {
        for d in [t0 + 4 * minute, t0 + 25 * hour, t0 - hour, Int64(-5)] {
            let o = try sealed("00000000000000d2", ts: t0, text: "⏳ Check on me", fifth: QuietClock.field(deadline: d))
            XCTAssertNil(o.message.notice, "\(d)"); XCTAssertFalse(o.message.isSilent); XCTAssertNil(QuietClock.effect(of: o.message))
            XCTAssertEqual(o.message.text, "⏳ Check on me")
        }
    }

    /// Hard requirement 5: a build without the feature sees the ⏳ text as an ordinary message, and nothing else.
    func testReadersWithoutTheFeatureSeeAnOrdinaryMessage() throws {
        let id = "00000000000000d3", d = t0 + 90 * minute, text = QuietClock.text(ts: t0, deadline: d)
        let with = try group.seal(id: id, ts: t0, sender: cara, name: "Cara", text: text, fifth: QuietClock.field(deadline: d), iv: iv) { _ in self.sig }
        let without = try group.seal(id: id, ts: t0, sender: cara, name: "Cara", text: text, iv: iv) { _ in self.sig }
        // Before the 5th field: the same three fields, no 4th.
        let a = try XCTUnwrap(preFifthRead(try plain(with, id: id, ts: t0))), b = try XCTUnwrap(preFifthRead(try plain(without, id: id, ts: t0)))
        XCTAssertEqual(a.sender, b.sender); XCTAssertEqual(a.name, b.name); XCTAssertEqual(a.text, b.text); XCTAssertNil(a.fourth); XCTAssertNil(b.fourth)
        XCTAssertEqual(a.text, Data(text.utf8))
        // The build before this one: neither a reply, a reaction, the timer's notice nor a leave note.
        let o = try XCTUnwrap(group.openSigned(Packet(id: id, ts: t0, ttl: 16, cipher: with)))
        XCTAssertEqual(leaveBuildReads(o.fifth), "ordinary")
        XCTAssertNil(About.read(field: o.fifth, text: Data(text.utf8)))
        XCTAssertNil(GroupTimer.read(field: o.fifth)); XCTAssertFalse(LeaveNote.read(field: o.fifth))
        XCTAssertNil(Notice.read(field: o.fifth), "a reader that doesn't pass the time: as before this feature")
        // Not a check-in, a report or a question, whatever the build: a plain bubble with its time.
        XCTAssertNil(CheckIn.of(text)); XCTAssertNil(Vouch.of(text)); XCTAssertNil(AreYouSafe.of(text))
        XCTAssertEqual(ChatCard.of(o.message), .text)
        XCTAssertNil(CheckIn.of(QuietClock.stopText))
    }

    func testTheFieldIsSigned() throws {
        func digest(_ fifth: Data?) throws -> Data {
            let c = try group.seal(id: "00000000000000d4", ts: t0, sender: cara, name: "Cara", text: "⏳", fifth: fifth, iv: iv, pad: .none) { _ in self.sig }
            return try XCTUnwrap(group.openSigned(Packet(id: "00000000000000d4", ts: t0, ttl: 16, cipher: c))).digest
        }
        XCTAssertNotEqual(try digest(nil), try digest(QuietClock.field(deadline: t0 + hour)), "nobody can add a clock to someone's message")
        XCTAssertNotEqual(try digest(QuietClock.field(deadline: t0 + hour)), try digest(QuietClock.field(deadline: t0 + 2 * hour)), "nor move its deadline")
    }

    // MARK: - What acts on a clock (Q1, Q4)

    private func secret(_ n: UInt8) -> Data { Data(repeating: n, count: 32) }

    private func epoch(_ n: Int64, _ members: [String], readOnly: Bool = false, removes: String? = nil) -> Keyring.Epoch {
        .fresh(number: n, made: t0 + n * hour, secret: secret(UInt8(n)), roster: members, members: readOnly ? [] : members, admins: [anna],
               code: removes == nil ? nil : "new-code-\(n)", via: removes == nil ? nil : "old", removes: removes, by: anna, rank: removes == nil ? Keyring.byAdmin : 0, taken: t0 + n * hour)
    }

    private func ring(_ epochs: [Keyring.Epoch]) -> Keyring { let r = Keyring(); epochs.forEach { r.add($0) }; return r }

    /// A clock by `sender`, sealed with its message key in epoch `n` (members as listed), opened by `e`.
    private func clock(_ n: Int64, _ members: [String], sender: String, by e: Keyring.Epoch) throws -> GroupCrypto.Opened {
        let s = secret(UInt8(n)), g = GroupCrypto.fromSecret(MessageKeys.outer(s)), position = try XCTUnwrap(members.firstIndex(of: sender))
        let mk = try XCTUnwrap(MessageKeys.of(s, members: members).key(position, 4))
        let ts = t0 + n * hour + minute, d = ts + hour
        let c = try g.seal(messageKey: mk, index: position, counter: 4, id: "e6080000000000d5", ts: ts, sender: sender, name: "Cara", text: QuietClock.text(ts: ts, deadline: d),
                           fifth: QuietClock.field(deadline: d), iv: iv) { _ in self.sig }
        return try XCTUnwrap(e.open(Packet(id: "e6080000000000d5", ts: g.hideTime(ts, c), ttl: 16, cipher: c)), "the epoch opens it")
    }

    func testOnlyAMembersOwnCheckedClockCounts() throws {
        let e5 = epoch(5, [anna, ben, cara])
        let o = try clock(5, [anna, ben, cara], sender: cara, by: e5)
        XCTAssertEqual(o.message.notice, .clock(o.message.ts + hour))
        XCTAssertTrue(QuietClock.counts(sender: cara, checked: true, opener: o.opener, ring: ring([e5]), left: false))
        XCTAssertTrue(QuietClock.counts(sender: cara, checked: true, opener: o.opener, ring: ring([e5, epoch(6, [anna, ben, cara])]), left: false), "listed since")
        XCTAssertFalse(QuietClock.counts(sender: cara, checked: nil, opener: o.opener, ring: ring([e5]), left: false), "decided when it checks")
        XCTAssertFalse(QuietClock.counts(sender: cara, checked: false, opener: o.opener, ring: ring([e5]), left: false), "a forged one: not Cara's")
        XCTAssertFalse(QuietClock.counts(sender: cara, checked: true, opener: nil, ring: ring([e5]), left: false), "not opened by an epoch")
        XCTAssertFalse(QuietClock.counts(sender: cara, checked: true, opener: o.opener, ring: ring([e5]), left: true), "after a leave note")
        XCTAssertFalse(QuietClock.counts(sender: "web-" + cara, checked: true, opener: o.opener, ring: ring([e5]), left: false), "a Wi-Fi board writes no clocks")
    }

    /// Requirement 1: a removed member's clock never counts, nor one played back after the same key was let in again.
    func testARemovedMembersClockNeverCounts() throws {
        let e5 = epoch(5, [anna, ben, cara]), e6 = epoch(6, [anna, ben], removes: cara), e7 = epoch(7, [anna, ben, cara])
        let o = try clock(5, [anna, ben, cara], sender: cara, by: e5)
        XCTAssertFalse(QuietClock.counts(sender: cara, checked: true, opener: o.opener, ring: ring([e5, e6]), left: false))
        XCTAssertFalse(QuietClock.counts(sender: cara, checked: true, opener: o.opener, ring: ring([e5, e6, e7]), left: false), "played back after being let in again")
        XCTAssertFalse(QuietClock.counts(sender: cara, checked: true, opener: o.opener, ring: ring([epoch(5, [anna, ben, cara], readOnly: true), epoch(6, [anna, ben, cara])]), left: false),
                       "an epoch kept only to read with decides nothing")
    }

    private func msg(_ text: String, ts: Int64, notice: Notice? = nil, about: About? = nil, isPublic: Bool = false) -> Message {
        var m = Message(id: "x", ts: ts, sender: cara, name: "Cara", text: text, isPublic: isPublic, group: isPublic ? nil : "g")
        m.notice = notice; m.about = about
        return m
    }

    /// Rule Q4: only an own check-in or a stop cancels; reports, reactions, questions and other messages don't.
    func testWhatCancels() {
        let reaction = About.reaction(About.Target(key: "e608000000000005:0102030405060708")!)
        XCTAssertEqual(QuietClock.effect(of: msg("✅ I'm safe", ts: 101)), .checkIn)
        XCTAssertEqual(QuietClock.effect(of: msg("🆘 I need help: water", ts: 101)), .checkIn)
        XCTAssertNil(QuietClock.effect(of: msg("👤 Anna is safe", ts: 101)), "a report about someone else")
        XCTAssertNil(QuietClock.effect(of: msg("👍", ts: 101, about: reaction)), "a reaction")
        XCTAssertNil(QuietClock.effect(of: msg("✅", ts: 101, about: reaction)), "a reaction is never a check-in, whatever its text")
        XCTAssertNil(QuietClock.effect(of: msg("❓ Is everyone safe?", ts: 101)))
        XCTAssertNil(QuietClock.effect(of: msg("on my way", ts: 101)), "ordinary messages don't cancel (decided)")
        XCTAssertNil(QuietClock.effect(of: msg("✅ I'm safe", ts: 101, isPublic: true)), "never on the Public channel")
        XCTAssertNil(QuietClock.effect(of: msg(LeaveNote.text, ts: 101, notice: .leave)))
        XCTAssertEqual(QuietClock.effect(of: msg(QuietClock.stopText, ts: 101, notice: .clock(0))), .clock(0))
        XCTAssertTrue(QuietClock.cancels(text: "✅ I'm safe", ts: 101, clockTs: 100))
        XCTAssertFalse(QuietClock.cancels(text: "✅ I'm safe", ts: 100, clockTs: 100), "the same time: not later")
        XCTAssertFalse(QuietClock.cancels(text: "✅ I'm safe", ts: 99, clockTs: 100))
        XCTAssertFalse(QuietClock.cancels(text: "👤 Anna is safe", ts: 101, clockTs: 100))
    }

    // MARK: - Which clock counts (Q3) and the book

    private func take(_ b: inout QuietBook, _ member: String, id: String = "e608000000000001", mark: String = "0000000000000001", ts: Int64, deadline: Int64,
                      spot: Spot? = nil, now: Int64? = nil, highRisk: Bool = false) -> Bool {
        b.take(member: member, id: id, mark: mark, ts: ts, deadline: deadline, spot: spot, now: now ?? ts, highRisk: highRisk)
    }

    func testTheNewestCountsWhateverTheOrder() {
        let a = (ts: t0, id: "e608000000000101"), bb = (ts: t0 + minute, id: "e608000000000102"), c = (ts: t0 + 2 * minute, id: "e608000000000103")
        for order in [[c, a, bb], [a, bb, c], [bb, c, a]] {
            var b = QuietBook()
            for x in order { _ = take(&b, cara, id: x.id, ts: x.ts, deadline: x.ts + hour, now: t0 + 3 * minute) }
            XCTAssertEqual(b.clock(cara)?.id, c.id, "\(order.map(\.id))")
        }
        var b = QuietBook()
        XCTAssertTrue(take(&b, cara, id: "e608000000000201", mark: "ff00000000000000", ts: t0, deadline: t0 + hour))
        XCTAssertTrue(take(&b, cara, id: "e608000000000202", mark: "0000000000000001", ts: t0, deadline: t0 + 2 * hour), "same ts: the greater id")
        XCTAssertFalse(take(&b, cara, id: "e608000000000201", mark: "ffffffffffffffff", ts: t0, deadline: t0 + 3 * hour))
        XCTAssertTrue(take(&b, cara, id: "e608000000000202", mark: "0000000000000002", ts: t0, deadline: t0 + 4 * hour), "same id: the greater mark")
        XCTAssertEqual(b.clock(cara)?.deadline, t0 + 4 * hour)
        XCTAssertFalse(take(&b, cara, id: "e608000000000202", mark: "0000000000000002", ts: t0, deadline: t0 + 4 * hour), "the same one again changes nothing")
    }

    /// Requirement 2: 50 clock messages from one member make one record, and one alert.
    func testOneClockPerMemberPerGroup() {
        var b = QuietBook()
        for i in 0..<50 { _ = take(&b, cara, id: String(format: "e6080000000%05x", i), ts: t0 + Int64(i) * 1000, deadline: t0 + Int64(i) * 1000 + hour, now: t0 + 60_000) }
        XCTAssertEqual(b.clocks.count, 1)
        XCTAssertEqual(b.alerts(now: t0 + 2 * hour, me: anna).count, 1)
        XCTAssertEqual(b.clock(cara)?.ts, t0 + 49_000)
    }

    func testAStopAndACheckInCancelInAnyOrder() {
        var b = QuietBook()
        _ = take(&b, cara, ts: 100, deadline: 100 + hour)
        XCTAssertTrue(b.armed(cara))
        b.checkIn(member: cara, ts: 99, now: 200, highRisk: false); XCTAssertTrue(b.armed(cara), "an older check-in")
        b.checkIn(member: cara, ts: 100, now: 200, highRisk: false); XCTAssertTrue(b.armed(cara), "at the same time")
        b.checkIn(member: cara, ts: 101, now: 200, highRisk: false); XCTAssertFalse(b.armed(cara))
        // The check-in first, then the clock it is later than: still cancelled.
        var c = QuietBook()
        c.checkIn(member: cara, ts: 101, now: 200, highRisk: false)
        _ = take(&c, cara, ts: 100, deadline: 100 + hour, spot: Spot(lat: 45, lon: 9), now: 300)
        XCTAssertFalse(c.armed(cara)); XCTAssertNil(c.clock(cara)?.spot, "a cancelled clock keeps no position")
        // A newer clock arms again; a stop newer still disarms.
        _ = take(&c, cara, id: "e608000000000002", ts: 102, deadline: 102 + hour, now: 300); XCTAssertTrue(c.armed(cara))
        _ = take(&c, cara, id: "e608000000000003", ts: 103, deadline: 0, now: 300); XCTAssertFalse(c.armed(cara))
        XCTAssertFalse(take(&c, cara, id: "e608000000000009", ts: 102, deadline: 102 + 2 * hour, now: 400), "an older clock never replaces a newer stop")
        XCTAssertFalse(c.armed(cara))
    }

    func testWhatIsDueAndWhen() {
        let d = t0
        XCTAssertEqual(QuietClock.due(deadline: d, learned: d - hour, now: d - hour), .at(d))
        XCTAssertEqual(QuietClock.due(deadline: d, learned: d, now: d), .now)
        XCTAssertEqual(QuietClock.due(deadline: d, learned: d - hour, now: d + hour), .now, "held before: on time, even if posted later")
        XCTAssertEqual(QuietClock.due(deadline: d, learned: d + 43_140_000, now: d + 43_140_000), .late)
        XCTAssertEqual(QuietClock.due(deadline: d, learned: d + 12 * hour, now: d + 12 * hour), .late)
        XCTAssertEqual(QuietClock.due(deadline: d, learned: d + 12 * hour + 1, now: d + 12 * hour + 1), .none)
        XCTAssertEqual(QuietClock.due(deadline: d, learned: d, now: d, armed: false), .none)
        XCTAssertEqual(QuietClock.due(deadline: 0, learned: d, now: d), .none)
    }

    /// Requirement 2 and rule Q8: the sender's own phone never alerts about its own clock; others' alert once.
    func testOwnClockNeverAlertsAndEachAlertsOnce() {
        var b = QuietBook()
        _ = take(&b, anna, ts: t0, deadline: t0 + hour)
        _ = take(&b, cara, mark: "00000000000000c1", ts: t0, deadline: t0 + 2 * hour)
        XCTAssertEqual(b.alerts(now: t0 + 3 * hour, me: anna).map(\.clock.member), [cara])
        XCTAssertEqual(b.alerts(now: t0 + 3 * hour, me: anna).first?.due, .now)
        XCTAssertTrue(b.note(cara, mark: "00000000000000c1", alerted: true))
        XCTAssertTrue(b.alerts(now: t0 + 3 * hour, me: anna).isEmpty, "never a second alert")
        XCTAssertFalse(b.note(cara, mark: "0000000000000000", alerted: false), "another clock's mark changes nothing")
        // A newer clock is a new alert.
        _ = take(&b, cara, id: "e608000000000002", mark: "00000000000000c2", ts: t0 + hour, deadline: t0 + 3 * hour)
        XCTAssertEqual(b.alerts(now: t0 + 4 * hour, me: anna).count, 1)
    }

    func testStatesForPeople() {
        var b = QuietBook()
        _ = take(&b, cara, ts: t0, deadline: t0 + hour)
        XCTAssertEqual(b.state(cara, now: t0 + minute), .running(deadline: t0 + hour))
        XCTAssertEqual(b.state(cara, now: t0 + hour), .ranOut(deadline: t0 + hour))
        XCTAssertEqual(b.state(cara, now: t0 + 13 * hour), .ranOut(deadline: t0 + hour))
        XCTAssertNil(b.state(cara, now: t0 + 13 * hour + 1), "12 hours after the deadline")
        b.checkIn(member: cara, ts: t0 + 2 * hour, now: t0 + 2 * hour, highRisk: false)
        XCTAssertEqual(b.state(cara, now: t0 + 2 * hour), .checkedIn(at: t0 + 2 * hour, deadline: t0 + hour))
        var c = QuietBook()
        _ = take(&c, cara, ts: t0, deadline: t0 + hour)
        c.checkIn(member: cara, ts: t0 + minute, now: t0 + minute, highRisk: false)
        XCTAssertNil(c.state(cara, now: t0 + 2 * minute), "checked in before the deadline: nothing to show")
        _ = take(&c, ben, ts: t0, deadline: 0)
        XCTAssertNil(c.state(ben, now: t0), "a stop")
    }

    /// Rule Q9: a removed member's clock, or one who left, goes without an alert.
    func testRemovalAndLeavingDropTheClock() {
        var b = QuietBook()
        _ = take(&b, cara, ts: t0, deadline: t0 + hour)
        _ = take(&b, ben, mark: "0000000000000002", ts: t0, deadline: t0 + hour)
        XCTAssertTrue(b.sweep(now: t0, highRisk: false) { $0 != cara }, "Cara is no longer listed")
        XCTAssertNil(b.clock(cara)); XCTAssertNotNil(b.clock(ben))
        XCTAssertTrue(b.drop(ben)); XCTAssertFalse(b.drop(ben))
        XCTAssertTrue(b.alerts(now: t0 + 2 * hour, me: anna).isEmpty)
    }

    /// Rule Q10: records last 36 hours after their time (24 in high-risk mode, which keeps no position); one that would be
    /// gone already isn't taken again, so a stored message read at the next start can't alert twice.
    func testHowLongRecordsLast() {
        var b = QuietBook()
        let spot = Spot(lat: 45.4642, lon: 9.19, accuracy: 12)
        _ = take(&b, cara, ts: t0, deadline: t0 + 24 * hour, spot: spot)
        XCTAssertEqual(b.clock(cara)?.spot, spot)
        XCTAssertFalse(b.sweep(now: t0 + 36 * hour - 1, highRisk: false) { _ in true })
        XCTAssertTrue(b.sweep(now: t0 + 36 * hour, highRisk: false) { _ in true }); XCTAssertNil(b.clock(cara))
        XCTAssertFalse(take(&b, cara, ts: t0, deadline: t0 + 24 * hour, now: t0 + 36 * hour), "read again later: not taken")
        var h = QuietBook()
        _ = take(&h, cara, ts: t0, deadline: t0 + hour, spot: spot, highRisk: true)
        XCTAssertNil(h.clock(cara)?.spot, "high-risk mode keeps no position")
        var n = QuietBook()
        _ = take(&n, cara, ts: t0, deadline: t0 + hour, spot: spot)
        XCTAssertTrue(n.sweep(now: t0 + 2 * hour, highRisk: true) { _ in true }); XCTAssertNil(n.clock(cara)?.spot, "switched on: positions go")
        XCTAssertTrue(n.sweep(now: t0 + 24 * hour, highRisk: true) { _ in true }); XCTAssertNil(n.clock(cara), "24 hours in high-risk mode, never before the deadline")
    }

    func testAtMostAHundredRecords() {
        var b = QuietBook()
        for i in 0..<101 { _ = take(&b, String(format: "%016x", i + 1), ts: t0 + Int64(i), deadline: t0 + Int64(i) + hour) }
        XCTAssertEqual(b.clocks.count, QuietBook.maxRecords)
        XCTAssertNil(b.clock(String(format: "%016x", 1)), "the oldest went")
    }

    func testAtMostThirtyTwoScheduledNearestFirst() {
        let deadlines = (0..<40).map { t0 + Int64(40 - $0) * minute }
        let picked = QuietClock.scheduled(deadlines) { $0 }
        XCTAssertEqual(picked.count, 32); XCTAssertEqual(picked.first, t0 + minute); XCTAssertEqual(picked.last, t0 + 32 * minute)
    }

    func testSavedAndReadBack() {
        var b = QuietBook()
        _ = take(&b, cara, id: "e608000000000077", mark: "a1b2c3d4e5f60718", ts: t0, deadline: t0 + hour, spot: Spot(lat: 45.4642, lon: -9.19, accuracy: 12), now: t0 + 5)
        _ = take(&b, ben, ts: t0, deadline: 0)
        b.checkIn(member: anna, ts: t0 + 7, now: t0 + 7, highRisk: false)
        b.note(cara, mark: "a1b2c3d4e5f60718", alerted: true, scheduled: true)
        let back = QuietBook(saved: b.saved)
        XCTAssertEqual(back, b)
        XCTAssertEqual(back.clock(cara)?.learned, t0 + 5)
        XCTAssertEqual(QuietBook(saved: ["c x", "i y", "z"]), QuietBook())
    }

    // MARK: - Writing

    func testTheText() {
        XCTAssertEqual(QuietClock.text(ts: t0, deadline: t0 + 30 * minute), "⏳ Check on me if I don't check in within 30 minutes")
        XCTAssertEqual(QuietClock.text(ts: t0, deadline: t0 + 90 * minute), "⏳ Check on me if I don't check in within 1 hour 30 minutes")
        XCTAssertEqual(QuietClock.text(ts: t0, deadline: t0 + 195 * minute), "⏳ Check on me if I don't check in within 3 hours 15 minutes")
        XCTAssertEqual(QuietClock.text(ts: t0, deadline: t0 + 24 * hour), "⏳ Check on me if I don't check in within 24 hours")
        XCTAssertEqual(QuietClock.text(ts: t0, deadline: t0 + 30 * minute + 30_000), "⏳ Check on me if I don't check in within 31 minutes", "rounded to the nearest minute")
        XCTAssertEqual(QuietClock.text(ts: t0, deadline: 0), QuietClock.stopText)
        let spot = Spot(lat: 45.4642, lon: 9.19, accuracy: 12), t = QuietClock.text(ts: t0, deadline: t0 + hour, spot: spot)
        XCTAssertEqual(t, "⏳ Check on me if I don't check in within 1 hour · 📍 45.46420,9.19000 ±12m")
        XCTAssertEqual(QuietClock.spot(in: t), spot, "read as in a check-in")
        XCTAssertTrue(t.hasSuffix(CheckIn.safe.text(battery: nil, spot: spot).dropFirst(CheckIn.safe.rawValue.count)), "the check-in suffix, byte for byte")
        XCTAssertNil(QuietClock.spot(in: "on my way · 📍 45.46420,9.19000"))
    }

    func testAddingTimeAndTheReminder() {
        let now = t0
        XCTAssertEqual(QuietClock.added(to: now + hour, now: now), now + 90 * minute)
        XCTAssertEqual(QuietClock.added(to: now + 24 * hour, now: now), now + 24 * hour, "at most 24 hours from now")
        XCTAssertEqual(QuietClock.added(to: now - hour, now: now), now + 30 * minute, "ran out already: 30 minutes from now")
        for d in [now + hour, now + 24 * hour, now - hour] { XCTAssertTrue(QuietClock.valid(deadline: QuietClock.added(to: d, now: now), ts: now)) }
        XCTAssertEqual(QuietClock.remindAt(ts: t0, deadline: t0 + 30 * minute), t0 + 20 * minute)
        XCTAssertEqual(QuietClock.remindAt(ts: t0, deadline: t0 + 20 * minute), t0 + 10 * minute)
        XCTAssertNil(QuietClock.remindAt(ts: t0, deadline: t0 + 19 * minute), "shorter than 20 minutes")
        XCTAssertNil(QuietClock.remindAt(ts: t0, deadline: 0))
        for m in QuietClock.choices { XCTAssertTrue(QuietClock.valid(deadline: QuietClock.deadline(minutes: m, now: t0), ts: t0)) }
        XCTAssertEqual(QuietClock.choices.map(QuietWords.chip), ["30 min", "1 hour", "3 hours", "8 hours"])
    }

    // MARK: - Wording

    /// Requirement 3: nothing claims more than the phone knows.
    func testTheWordingNeverClaimsMore() {
        let all = [QuietWords.lead, QuietWords.sub, QuietWords.addWhereSub, QuietWords.reached([]), QuietWords.reached(["Marco"]), QuietWords.reached(["Marco", "Sara"]),
                   QuietWords.reached(["Marco", "Sara", "Ben", "Lia"]), QuietWords.started("Anna"), QuietWords.startedSub("Anna", ends: "18:30"), QuietWords.startedSub(nil, ends: "18:30"),
                   QuietWords.stoppedLine("Anna"), QuietWords.running("18:30"), QuietWords.overdue("18:30"), QuietWords.checkedIn("19:10"),
                   QuietWords.alertTitle("Anna", late: false, at: "18:30"), QuietWords.alertTitle("Anna", late: true, at: "18:30"),
                   QuietWords.alertBody("Anna", since: "17:30", late: true, position: "18:02"), QuietWords.hiddenTitle, QuietWords.hiddenBody,
                   QuietWords.banner("Anna", group: "My group"), QuietWords.remindTitle("18:30"), QuietWords.remindBody, QuietWords.remindHidden, QuietWords.ranOut("18:30")]
            + [true, false].flatMap { [QuietWords.sent(waiting: $0), QuietWords.added(waiting: $0), QuietWords.safeSent(waiting: $0), QuietWords.stopped(waiting: $0)] }
        for s in all { for bad in QuietWords.never { XCTAssertFalse(s.lowercased().contains(bad), "\(s) says \(bad)") } }
        XCTAssertEqual(QuietWords.reached(["Marco", "Sara", "Ben", "Lia"]), "Reached Marco's, Sara's and 2 more phones so far")
        XCTAssertEqual(QuietWords.alertBody("Anna", since: "17:30", late: false, position: "18:02"),
                       "No check-in from Anna has reached this phone since 17:30. Anna's phone may just be out of reach. Last position known: 18:02.")
        XCTAssertEqual(QuietWords.hiddenBody, "A check-in clock in your group ran out. Open DarkProof.")
        XCTAssertFalse(QuietWords.hiddenBody.contains("Anna"))
        XCTAssertEqual(QuietWords.lead, "If you don't check in on time, the phones that got your clock can alert their owners.")
    }
}
