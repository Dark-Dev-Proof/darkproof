import XCTest
import CryptoKit
@testable import BlackoutCore

/// The group's timer for disappearing messages (spec/FORMAT.md, "Disappearing messages"): the field, who may set it,
/// how epochs inherit it, when it starts deleting, and that phones without it take the same new keys as before.
final class TimerTests: XCTestCase {
    private let anna = "1111111111111111", ben = "2222222222222222", cara = "3333333333333333", dan = "4444444444444444"
    private let hour: Int64 = 3_600_000, t0: Int64 = 1_794_000_000_000

    // MARK: - The field

    func testTheFiveFieldsAsWritten() {
        let framed: [(Int, String)] = [(0, "00050300000000"), (3_600, "00050300000e10"), (28_800, "00050300007080"), (86_400, "00050300015180"), (604_800, "00050300093a80")]
        for (seconds, hex) in framed {
            let f = GroupTimer.field(seconds)
            XCTAssertEqual(Hex.encode(Data([0, UInt8(f.count)]) + f), hex)
            XCTAssertEqual(GroupTimer.read(field: f), seconds)
        }
        XCTAssertEqual(GroupTimer.choices, [3_600, 28_800, 86_400, 604_800])
    }

    func testReadingTheNumber() {
        func read(_ n: UInt32) -> Int? { GroupTimer.read(field: Data([0x03, UInt8(n >> 24), UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255)])) }
        XCTAssertEqual(read(0), 0); XCTAssertEqual(read(1), 300); XCTAssertEqual(read(299), 300); XCTAssertEqual(read(300), 300); XCTAssertEqual(read(301), 301)
        XCTAssertEqual(read(604_800), 604_800); XCTAssertEqual(read(604_801), 0, "longer than the normal rule means nothing: off"); XCTAssertEqual(read(0xFFFF_FFFF), 0)
        XCTAssertNil(GroupTimer.read(field: Data([0x03, 0, 0, 0x0e])), "4 bytes"); XCTAssertNil(GroupTimer.read(field: Data([0x03, 0, 0, 0x0e, 0x10, 0])), "6 bytes")
        XCTAssertNil(GroupTimer.read(field: Data([0x04, 0, 0, 0x0e, 0x10])), "another type"); XCTAssertNil(GroupTimer.read(field: nil)); XCTAssertNil(GroupTimer.read(field: Data()))
        XCTAssertNil(GroupTimer.read(field: Data([0x01]) + Data(count: 16)), "a reply's field in a new key means nothing")
    }

    func testWording() {
        XCTAssertEqual(GroupTimer.label(3_600), "1 hour"); XCTAssertEqual(GroupTimer.label(28_800), "8 hours"); XCTAssertEqual(GroupTimer.label(86_400), "1 day")
        XCTAssertEqual(GroupTimer.label(604_800), "1 week"); XCTAssertEqual(GroupTimer.label(300), "5 minutes"); XCTAssertEqual(GroupTimer.label(172_800), "2 days")
        XCTAssertEqual(GroupTimer.notice(86_400), "⏱ Messages here now disappear 1 day after they reach each phone. Older DarkProof versions keep them 1 to 2 weeks.")
        XCTAssertEqual(GroupTimer.notice(0), "⏱ The timer is off. Messages disappear after 1 to 2 weeks.")
        XCTAssertEqual(GroupTimer.chatLine(86_400), "They disappear 1 day after they reach each phone.")
        XCTAssertEqual(GroupTimer.chatLine(0), "They disappear after one to two weeks."); XCTAssertEqual(GroupTimer.chatLine(nil), "They disappear after one to two weeks.")
        XCTAssertEqual(GroupTimer.chatLine(3_600, highRisk: true), "They disappear 1 hour after they reach each phone.", "the shorter of the two is what happens")
        XCTAssertEqual(GroupTimer.chatLine(604_800, highRisk: true), "High-risk mode: they leave this phone 1 day after they arrive.")
        XCTAssertEqual(GroupTimer.chatLine(nil, highRisk: true), "High-risk mode: they leave this phone 1 day after they arrive.")
        XCTAssertEqual(GroupTimer.securityLine(86_400, by: "Anna", highRisk: false), "After 1 day, set by Anna")
        XCTAssertEqual(GroupTimer.securityLine(86_400, by: nil, highRisk: true), "After 1 day")
        XCTAssertEqual(GroupTimer.securityLine(604_800, by: "you", highRisk: true), "After 1 week, set by you. On this phone, 1 day after they arrive.")
        XCTAssertEqual(GroupTimer.securityLine(0, by: nil, highRisk: false), "After one to two weeks")
        XCTAssertEqual(GroupTimer.securityLine(0, by: nil, highRisk: true), "After one to two weeks. On this phone, 1 day after they arrive.")
        XCTAssertEqual(GroupTimer.securityLine(nil, by: nil, highRisk: false), "Not known yet. Until an admin's key brings it, this phone keeps messages one to two weeks.")
        XCTAssertEqual(GroupTimer.sheetLine(86_400), "Messages disappear 1 day after they reach each phone.")
        XCTAssertEqual(GroupTimer.sheetLine(0), "Messages disappear after one to two weeks.")
        XCTAssertEqual(GroupTimer.changeLine(28_800, who: "Anna"), "Anna set messages to disappear after 8 hours")
        XCTAssertEqual(GroupTimer.changeLine(86_400, who: "You"), "You set messages to disappear after 1 day")
        XCTAssertEqual(GroupTimer.changeLine(0, who: "Anna"), "Anna switched the timer off. Messages disappear after 1 to 2 weeks.")
        XCTAssertEqual(GroupTimer.changeLine(3_600, who: nil), "Messages disappear after 1 hour")
        XCTAssertTrue(GroupTimer.explainer(86_400).hasPrefix("Each phone deletes a message 1 day after it reaches it."))
    }

    // MARK: - A new key that carries it, and readers that don't know it

    /// The body's fields as a reader read them before the 5th field existed: sender, name, text, and a 4th field.
    private func oldRead(_ plain: Data) -> (sender: String, text: Data, fourth: Data)? {
        let b = [UInt8](plain.dropFirst(64)); var i = 0
        func next() -> [UInt8]? {
            guard i + 2 <= b.count else { return nil }
            let n = Int(b[i]) << 8 | Int(b[i + 1])
            guard i + 2 + n <= b.count else { return nil }
            defer { i += 2 + n }
            return Array(b[(i + 2)..<(i + 2 + n)])
        }
        guard let s = next(), next() != nil, let t = next() else { return nil }
        return (String(decoding: s, as: UTF8.self), Data(t), Data(next() ?? []))
    }

    func testANewKeyWithTheFieldIsTheSameNewKeyToAReaderWithoutIt() throws {
        let code = "test-group-1", rekeys = GroupCrypto(code: code).rekeys(), id = "0123456789abcdef"
        let r = Rekey(epoch: 6, made: t0, maker: Data(repeating: 2, count: 33), admins: [anna], answers: nil,
                      parts: [anna, ben].map { ($0, Data(repeating: 7, count: 40)) }, members: [anna, ben])
        let iv = Data(repeating: 9, count: 12), sig = Data(repeating: 7, count: 64)
        let with = try rekeys.seal(id: id, ts: t0, sender: anna, name: "Anna", text: "", extra: r.encode(), fifth: GroupTimer.field(86_400), iv: iv, pad: .full) { _ in sig }
        let without = try rekeys.seal(id: id, ts: t0, sender: anna, name: "Anna", text: "", extra: r.encode(), iv: iv, pad: .full) { _ in sig }
        XCTAssertEqual(with.count, without.count, "padded to the same size: the field can't be seen from outside"); XCTAssertEqual(with.count, Packet.maxCipher)
        for (cipher, timer) in [(with, Optional(86_400)), (without, nil)] {
            let o = try XCTUnwrap(rekeys.openSigned(Packet(id: id, ts: t0, ttl: 16, cipher: cipher)))
            XCTAssertEqual(Rekey.decode(try XCTUnwrap(o.extra)), r, "today's reader of the 4th field takes the same key")
            XCTAssertEqual(GroupTimer.read(field: o.fifth), timer)
            XCTAssertNil(o.message.about, "no reply, no reaction")
        }
        // A reader that stops after the 4th field: the same bytes, the same key.
        let key = SymmetricKey(data: HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: GroupCrypto.deriveKey(code)), info: Data("darkproof-rekey-v1".utf8), outputByteCount: 32))
        let plain = try AES.GCM.open(AES.GCM.SealedBox(combined: with), using: key, authenticating: Data("\(id)|\(t0)".utf8))
        let old = try XCTUnwrap(oldRead(plain))
        XCTAssertEqual(old.sender, anna); XCTAssertEqual(old.text, Data()); XCTAssertEqual(Rekey.decode(old.fourth), r)
        // And the keyring takes both alike: the same epoch, with or without a timer.
        func taken(_ timer: Int?) -> Keyring.Epoch? {
            let ring = base()
            if case .added(let e, _, _) = ring.take(r, maker: anna, me: ben, door: "d", oldDoor: false, removedAt: nil, now: t0, timer: timer, open: { _ in Data(repeating: 6, count: 32) }) { return e }
            return nil
        }
        let a = try XCTUnwrap(taken(86_400)), b = try XCTUnwrap(taken(nil))
        XCTAssertEqual(a.id, b.id); XCTAssertEqual(a.members, b.members); XCTAssertEqual(a.admins, b.admins); XCTAssertEqual(a.rank, b.rank)
        XCTAssertEqual(a.timer, 86_400); XCTAssertEqual(b.timer, 0, "without the field it keeps the value of the epoch before")
    }

    func testTheLargestNewKeyStillFitsWithTheField() throws {
        let members = (0..<100).map { String(format: "%016x", $0) }, box = Data(count: MlKem.cipherSize + Hpke.encSize + 32 + 19 + 16)
        let crypto = GroupCrypto.fromSecret(Data(repeating: 1, count: 32)), id = "0123456789abcdef"
        for some in Rekey.split(members) {
            let r = Rekey(epoch: 7, made: 1, maker: Data(count: 33), admins: members, answers: String(format: "%016x", 200), parts: some.map { ($0, box) }, removal: true, members: members)
            let c = try crypto.seal(id: id, ts: 1, sender: anna, name: String(repeating: "n", count: 200), text: "", extra: r.encode(), fifth: GroupTimer.field(3_600), pad: .full) { try MemberKey.sign(TestKeys.key, $0) }
            let p = Packet(id: id, ts: 1, ttl: 16, cipher: c)
            XCTAssertEqual(p.encode().count, 16_018)
            let o = try XCTUnwrap(crypto.openSigned(p))
            XCTAssertNotNil(Rekey.decode(try XCTUnwrap(o.extra))); XCTAssertEqual(GroupTimer.read(field: o.fifth), 3_600)
        }
    }

    // MARK: - Who sets it, and how epochs inherit it

    /// Epoch 5: Anna and Ben are admins, Cara and Dan members. The timer is off.
    private func base(timer: Int? = 0) -> Keyring {
        let r = Keyring()
        r.add(.init(number: 5, made: t0 - 100 * hour, secret: Data(repeating: 5, count: 32), members: [anna, ben, cara, dan], admins: [anna, ben], by: anna, rank: Keyring.byAdmin,
                    timerRaw: timer, timer: timer, timerCounts: timer != nil))
        return r
    }

    private func key(_ epoch: Int64, made: Int64, members: [String]? = nil, admins: [String]? = nil, removes: String? = nil) -> Rekey {
        let m = members ?? [anna, ben, cara, dan]
        return Rekey(epoch: epoch, made: made, maker: Data(count: 33), admins: admins ?? [anna, ben], answers: removes, parts: m.map { ($0, Data(count: 1)) }, removal: removes != nil, members: m)
    }

    /// What `Keyring.take` makes of a key on Cara's phone; nil if it isn't taken. `secret`: what its part opens to.
    @discardableResult private func take(_ ring: Keyring, _ r: Rekey, by maker: String, timer: Int?, secret: UInt8, now: Int64? = nil, me: String? = nil) -> Keyring.Epoch? {
        let plain = Data(repeating: secret, count: 32) + Data((r.removal ? "ABCD-EFGH-JKMN-PQR\(secret % 8 + 2)" : "").utf8)
        if case .added(let e, _, _) = ring.take(r, maker: maker, me: me ?? cara, door: "d", oldDoor: false, removedAt: nil, now: now ?? r.made, timer: timer, open: { _ in plain }) { return e }
        return nil
    }

    func testAnAdminSetsItAndLaterKeysInherit() {
        let ring = base()
        XCTAssertEqual(ring.timer.seconds, 0)
        XCTAssertNotNil(take(ring, key(6, made: t0), by: anna, timer: 86_400, secret: 6))
        XCTAssertEqual(ring.timer.seconds, 86_400); XCTAssertEqual(ring.timer.by, anna, "the value changed with her key")
        XCTAssertNotNil(take(ring, key(7, made: t0 + 2 * hour), by: cara, timer: nil, secret: 7))
        XCTAssertEqual(ring.timer.seconds, 86_400, "a member's weekly key without the field keeps it"); XCTAssertEqual(ring.timer.by, anna)
        XCTAssertNotNil(take(ring, key(8, made: t0 + 4 * hour), by: ben, timer: nil, secret: 8))
        XCTAssertEqual(ring.timer.seconds, 86_400, "so does an admin's key from a build without the field")
        XCTAssertNotNil(take(ring, key(9, made: t0 + 6 * hour), by: ben, timer: 86_400, secret: 9))
        XCTAssertEqual(ring.timer.by, anna, "repeating the value isn't setting it")
        XCTAssertNotNil(take(ring, key(10, made: t0 + 8 * hour), by: ben, timer: 0, secret: 10))
        XCTAssertEqual(ring.timer.seconds, 0, "only an admin's explicit zero turns it off"); XCTAssertEqual(ring.timer.by, ben)
    }

    func testAMembersFieldIsIgnored() {
        let ring = base(timer: 86_400)
        XCTAssertNotNil(take(ring, key(6, made: t0), by: cara, timer: 3_600, secret: 6))
        XCTAssertEqual(ring.timer.seconds, 86_400)
        XCTAssertNotNil(take(ring, key(7, made: t0 + 2 * hour), by: dan, timer: 0, secret: 7))
        XCTAssertEqual(ring.timer.seconds, 86_400, "nor can a member turn it off")
    }

    func testAPhoneThatJoinsLearnsItFromTheKeyThatLetsItIn() {
        func joined(by maker: String, admins: [String], timer: Int?) -> Int?? {
            let ring = Keyring()
            guard take(ring, key(6, made: t0, admins: admins), by: maker, timer: timer, secret: 6) != nil else { return nil }
            return ring.timer.seconds
        }
        XCTAssertEqual(joined(by: anna, admins: [anna, ben], timer: 28_800), .some(28_800))
        XCTAssertEqual(joined(by: anna, admins: [anna, ben], timer: 0), .some(0))
        XCTAssertEqual(joined(by: anna, admins: [anna, ben], timer: nil), .some(nil), "let in by a build without the field: not known yet")
        XCTAssertEqual(joined(by: dan, admins: [anna, ben], timer: 3_600), .some(nil), "never told a value by someone the key doesn't name as an admin")
    }

    func testNotKnownStaysNotKnownUntilAnAdminsKeyBringsIt() {
        let ring = base(timer: nil)
        XCTAssertNil(ring.timer.seconds)
        take(ring, key(6, made: t0), by: cara, timer: 3_600, secret: 6)
        XCTAssertNil(ring.timer.seconds)
        take(ring, key(7, made: t0 + 2 * hour), by: anna, timer: nil, secret: 7)
        XCTAssertNil(ring.timer.seconds)
        take(ring, key(8, made: t0 + 4 * hour), by: anna, timer: 604_800, secret: 8)
        XCTAssertEqual(ring.timer.seconds, 604_800); XCTAssertNil(ring.timer.by, "this phone didn't see who set it: her key may only repeat it")
    }

    /// Two admins set different values at once: every phone ends with the value of the key that wins the number
    /// (`Keyring.first`: both are admins' keys, so the smaller hash), whichever came first.
    func testTwoAdminsAtOnceTheWinnersValueEverywhere() throws {
        func ring(_ order: [(String, Int, UInt8)]) -> Keyring {
            let r = base()
            for (maker, timer, secret) in order { XCTAssertNotNil(take(r, key(6, made: t0), by: maker, timer: timer, secret: secret)) }
            return r
        }
        let a = (anna, 3_600, UInt8(61)), b = (ben, 86_400, UInt8(62))
        let one = ring([a, b]), two = ring([b, a])
        XCTAssertTrue(one.current.id == two.current.id)
        XCTAssertEqual(one.timer.seconds, two.timer.seconds)
        XCTAssertEqual(one.timer.seconds, one.current.by == anna ? 3_600 : 86_400, "no merging: the winner's")
    }

    /// A phone gets the rival that loses, then a member's next key, and the winner only later. The member's key
    /// inherits; it must end with the winner's value like every other phone, not keep the loser's for good.
    func testALosingRivalsValueDoesNotStickThroughAMembersNextKey() throws {
        // Which of the two secrets wins number 6 is a matter of hashes: find out, then give the loser the short timer.
        let probe = base()
        take(probe, key(6, made: t0), by: anna, timer: nil, secret: 61); take(probe, key(6, made: t0), by: ben, timer: nil, secret: 62)
        let annaWins = probe.current.by == anna
        let winner = (annaWins ? anna : ben, 86_400, UInt8(annaWins ? 61 : 62)), loser = (annaWins ? ben : anna, 3_600, UInt8(annaWins ? 62 : 61))

        let late = base()
        XCTAssertNotNil(take(late, key(6, made: t0), by: loser.0, timer: loser.1, secret: loser.2))
        XCTAssertEqual(late.timer.seconds, 3_600)
        XCTAssertNotNil(take(late, key(7, made: t0 + 2 * hour), by: cara, timer: nil, secret: 7))
        XCTAssertEqual(late.timer.seconds, 3_600, "all this phone knows so far")
        let read = try XCTUnwrap(take(late, key(6, made: t0), by: winner.0, timer: winner.1, secret: winner.2, now: t0 + 3 * hour))
        XCTAssertTrue(read.members.isEmpty, "numbered below the current epoch: kept only to read with")
        XCTAssertEqual(late.current.number, 7)
        XCTAssertEqual(late.timer.seconds, 86_400, "the admin's late key is still that admin's word on the timer")

        let early = base()
        take(early, key(6, made: t0), by: winner.0, timer: winner.1, secret: winner.2); take(early, key(6, made: t0), by: loser.0, timer: loser.1, secret: loser.2)
        take(early, key(7, made: t0 + 2 * hour), by: cara, timer: nil, secret: 7)
        XCTAssertEqual(early.timer.seconds, 86_400)
        XCTAssertEqual(early.timer.seconds, late.timer.seconds, "the same keys in another order: the same timer")
    }

    /// A key kept only to read with never becomes the group's timer by itself, and a member's can't change anything.
    func testAKeyKeptOnlyToReadWithDecidesNothing() throws {
        let ring = base(timer: 86_400)
        take(ring, key(6, made: t0), by: anna, timer: nil, secret: 6); take(ring, key(7, made: t0 + 2 * hour), by: cara, timer: nil, secret: 7)
        let old = try XCTUnwrap(take(ring, key(6, made: t0 + hour), by: dan, timer: 300, secret: 66, now: t0 + 3 * hour))
        XCTAssertTrue(old.members.isEmpty); XCTAssertNil(old.timer)
        XCTAssertEqual(ring.current.number, 7); XCTAssertEqual(ring.timer.seconds, 86_400)
        // An admin's late key without a timer says nothing about it either.
        take(ring, key(6, made: t0 + hour), by: ben, timer: nil, secret: 67, now: t0 + 3 * hour)
        XCTAssertEqual(ring.timer.seconds, 86_400)
    }

    /// The same key read again is ignored (its part was opened once), so a replay can't bring a value back.
    func testTheSameKeyAgainChangesNothing() {
        let ring = base()
        let first = key(6, made: t0)
        XCTAssertNotNil(take(ring, first, by: anna, timer: 3_600, secret: 6))
        XCTAssertNotNil(take(ring, key(7, made: t0 + 2 * hour), by: anna, timer: 86_400, secret: 7))
        XCTAssertNil(take(ring, first, by: anna, timer: 3_600, secret: 6, now: t0 + 3 * hour), "replayed")
        XCTAssertNil(take(ring, first, by: anna, timer: 300, secret: 6, now: t0 + 3 * hour), "replayed with another field: the packet wouldn't even check, and the part is known")
        XCTAssertEqual(ring.timer.seconds, 86_400)
    }

    /// Someone a removal took out sets nothing afterwards: their keys aren't taken at all.
    func testARemovedAdminSetsNothing() {
        let ring = base(timer: 86_400)
        XCTAssertNotNil(take(ring, key(6, made: t0, members: [anna, cara, dan], admins: [anna], removes: ben), by: anna, timer: nil, secret: 6))
        XCTAssertEqual(ring.timer.seconds, 86_400, "a removal without the field keeps it")
        XCTAssertNil(take(ring, key(7, made: t0 + 2 * hour, members: [anna, cara, dan], admins: [anna]), by: ben, timer: 300, secret: 7))
        XCTAssertNil(take(ring, key(6, made: t0, admins: [anna, ben]), by: ben, timer: 300, secret: 66))
        XCTAssertEqual(ring.timer.seconds, 86_400)
    }

    func testARemovalCanCarryIt() {
        let ring = base(timer: 86_400)
        XCTAssertNotNil(take(ring, key(6, made: t0, members: [anna, cara, dan], admins: [anna], removes: ben), by: anna, timer: 3_600, secret: 6))
        XCTAssertEqual(ring.timer.seconds, 3_600)
    }

    /// The timers are saved with the epochs: read back, the keyring says the same.
    func testWhatWasWorkedOutIsKeptWhenTheEpochsBeforeAreGone() {
        let ring = base()
        take(ring, key(6, made: t0), by: anna, timer: 28_800, secret: 6); take(ring, key(7, made: t0 + 2 * hour), by: cara, timer: nil, secret: 7)
        let cur = ring.current, saved = ring.timers[cur.id]!
        XCTAssertEqual(saved.seconds, 28_800); XCTAssertEqual(saved.by, anna)
        let back = Keyring() // only the current epoch is left a week later
        back.add(.init(number: cur.number, made: cur.made, secret: cur.secret, members: cur.members, admins: cur.admins, by: cur.by, rank: cur.rank, taken: cur.taken,
                       timerRaw: cur.timerRaw, timer: saved.seconds, timerBy: saved.by, timerCounts: saved.counts))
        XCTAssertEqual(back.timer.seconds, 28_800); XCTAssertEqual(back.timer.by, anna)
    }

    func testThisPhonesOwnChoiceIsKnownToBeItsOwn() {
        let ring = base(timer: nil)
        let r = key(6, made: t0)
        XCTAssertTrue(ring.setsTimer(r, maker: anna)); XCTAssertFalse(ring.setsTimer(r, maker: cara))
        ring.add(.fresh(number: 6, made: t0, secret: Data(repeating: 6, count: 32), members: r.members, admins: r.admins, by: anna, rank: Keyring.byAdmin, timerRaw: 3_600, timerSetHere: true, timerCounts: true))
        XCTAssertEqual(ring.timer.seconds, 3_600); XCTAssertEqual(ring.timer.by, anna)
    }

    // MARK: - When it deletes

    func testAValueDeletesOnlyOnceItHasSettled() {
        var w = Disappear.Watch()
        XCTAssertTrue(w.observe(3_600, now: t0))
        XCTAssertNil(w.deletes(now: t0)); XCTAssertNil(w.deletes(now: t0 + 9 * 60_000 + 59_999), "9:59")
        XCTAssertEqual(w.deletes(now: t0 + 10 * 60_000), 3_600_000, "10:00")
        XCTAssertFalse(w.observe(3_600, now: t0 + hour), "a later key that inherits it doesn't start the wait again")
        XCTAssertEqual(w.deletes(now: t0 + hour), 3_600_000)
        XCTAssertTrue(w.observe(0, now: t0 + 2 * hour)); XCTAssertNil(w.deletes(now: t0 + 9 * hour), "off")
        XCTAssertTrue(w.observe(nil, now: t0 + 9 * hour)); XCTAssertNil(w.deletes(now: t0 + 99 * hour), "not known: the normal rule")
    }

    /// Two admins change it at once, and the value that loses is current for a moment: it never deletes anything.
    func testARivalThatIsCurrentForAMomentDeletesNothing() {
        var w = Disappear.Watch(seconds: 604_800, since: t0 - 100 * hour)
        XCTAssertEqual(w.deletes(now: t0), 604_800_000)
        w.observe(300, now: t0)                 // the losing rival arrives first
        XCTAssertNil(w.deletes(now: t0 + 5_000))
        w.observe(86_400, now: t0 + 5_000)      // the winner, 5 s later
        for at in stride(from: t0, through: t0 + 10 * 60_000 + 4_999, by: 1_000) { XCTAssertNotEqual(w.deletes(now: at), 300_000) }
        XCTAssertNil(w.deletes(now: t0 + 10 * 60_000 + 4_999))
        XCTAssertEqual(w.deletes(now: t0 + 10 * 60_000 + 5_000), 86_400_000)
        // Back and forth: each change starts the wait again.
        w.observe(300, now: t0 + hour); w.observe(86_400, now: t0 + hour + 9 * 60_000); w.observe(300, now: t0 + hour + 18 * 60_000)
        XCTAssertNil(w.deletes(now: t0 + hour + 27 * 60_000))
    }

    func testTheWatchIsSaved() {
        for w in [Disappear.Watch(seconds: 3_600, since: t0), Disappear.Watch(seconds: 0, since: 5), Disappear.Watch(seconds: nil, since: t0)] {
            XCTAssertEqual(Disappear.Watch(saved: w.saved), w)
        }
        for bad in ["", "3600", "x:5", "3600:", ":5", "3600:5:1"] { XCTAssertNil(Disappear.Watch(saved: bad), bad) }
        var back = Disappear.Watch(seconds: 3_600, since: t0 + 99 * hour) // the clock was set back since
        back.observe(3_600, now: t0)
        XCTAssertEqual(back.deletes(now: t0 + 10 * 60_000), 3_600_000, "it waits ten minutes again, not days")
    }

    /// Counted from when a message reached this phone (or, written here, first left it), never from when it was written.
    func testWhatIsDue() {
        let held = [KeepsLess.Held(name: "a", since: t0), KeepsLess.Held(name: "b", since: t0 + 1), KeepsLess.Held(name: "unsent", since: nil)]
        XCTAssertEqual(Disappear.due(held, timerMs: hour, now: t0 + hour - 1), [])
        XCTAssertEqual(Disappear.due(held, timerMs: hour, now: t0 + hour), ["a"], "exactly an hour: it goes")
        XCTAssertEqual(Set(Disappear.due(held, timerMs: hour, now: t0 + 500 * hour)), ["a", "b"], "one that hasn't left this phone never goes")
        XCTAssertEqual(Disappear.due(held, timerMs: 0, now: t0 + 500 * hour), [])
        XCTAssertEqual(Disappear.due(held, timerMs: hour, now: Int64.min + 5), [])
        XCTAssertEqual(Disappear.next(held, timerMs: hour), t0 + hour); XCTAssertNil(Disappear.next([held[2]], timerMs: hour)); XCTAssertNil(Disappear.next(held, timerMs: 0))
        // High-risk mode keeps its own day: whichever is earlier removes.
        XCTAssertEqual(KeepsLess.expired(held, now: t0 + 24 * hour + 1), ["a"])
        XCTAssertEqual(Disappear.due(held, timerMs: 168 * hour, now: t0 + 24 * hour + 1), [])
    }

    func testWhatTheTimerRemovesIsStillCarriedUnlessThisPhoneCanReadIt() {
        XCTAssertTrue(Disappear.carries(stillOpens: false), "its own key is gone: one more packet this phone only carries")
        XCTAssertFalse(Disappear.carries(stillOpens: true), "a receipt, or a message the epoch's key still opens: out of the store")
    }

    // MARK: - The notice message

    private func oldPlain(_ cipher: Data, code: String, id: String, ts: Int64) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: cipher), using: SymmetricKey(data: GroupCrypto.deriveKey(code)), authenticating: Data("\(id)|\(ts)".utf8))
    }

    func testTheNoticeIsTextToAppsWithoutTheTimerAndNothingToAppsWithIt() throws {
        let code = "test-group-1", group = GroupCrypto(code: code), id = "00000000000000a7", text = GroupTimer.notice(86_400)
        let c = try group.seal(id: id, ts: 9, sender: anna, name: "Anna", text: text, fifth: GroupTimer.field(86_400), iv: Data(repeating: 9, count: 12)) { _ in Data(repeating: 7, count: 64) }
        let o = try XCTUnwrap(group.openSigned(Packet(id: id, ts: 9, ttl: 16, cipher: c)))
        XCTAssertEqual(o.message.notice, .timer(86_400)); XCTAssertTrue(o.message.isSilent, "never a row, an alert or a receipt")
        XCTAssertNil(o.message.about); XCTAssertNil(About.read(field: o.fifth, text: Data(text.utf8)), "a build with replies and reactions only: an ordinary message")
        XCTAssertEqual(o.message.text, text)
        let old = try XCTUnwrap(oldRead(try oldPlain(c, code: code, id: id, ts: 9)))
        XCTAssertEqual(old.text, Data(text.utf8)); XCTAssertEqual(old.fourth, Data(), "the 4th field is there and empty")
        XCTAssertEqual(text.utf8.count, 115)
        // On the Public channel the field means nothing: the text is shown like any other.
        XCTAssertNil(o.message.onPublicChannel(mine: false).notice); XCTAssertFalse(o.message.onPublicChannel(mine: false).isSilent)
        // No copy is kept of it: nothing of it is ever shown.
        XCTAssertNil(History.seal(key: Data(repeating: 3, count: 32), id: id, group: "g", epoch: "e", o, index: 0, counter: 0))
        // An ordinary message has no notice.
        let plain = try group.seal(id: id, ts: 9, sender: anna, name: "Anna", text: "hello", iv: Data(repeating: 9, count: 12)) { _ in Data(repeating: 7, count: 64) }
        XCTAssertNil(group.openSigned(Packet(id: id, ts: 9, ttl: 16, cipher: plain))?.message.notice)
    }
}
