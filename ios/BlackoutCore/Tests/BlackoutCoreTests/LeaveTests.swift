import XCTest
import CryptoKit
@testable import BlackoutCore

/// Telling the group when a phone leaves or is erased (spec/FORMAT.md, "Leaving the group"): the note's field, which
/// notes count (L1), what admins' phones do (L4), the leaving records (L2, L3), the wording, and that a reader without
/// the feature sees an ordinary message and nothing else.
final class LeaveTests: XCTestCase {
    let code = "test-group-leave"
    lazy var group = GroupCrypto(code: code)
    let iv = Data(repeating: 9, count: 12)
    let sig = Data(repeating: 7, count: 64)
    let anna = "1111111111111111", ben = "2222222222222222", cara = "3333333333333333"
    let t0: Int64 = 1_794_000_000_000, hour: Int64 = 3_600_000

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

    /// The build with replies, reactions and the timer, before the leave note: a 5th field it reads only as a reply or
    /// reaction (17 bytes, types 1 and 2) or as the timer's notice (5 bytes, type 3). Anything else: an ordinary message.
    private func timerBuildReads(_ field: Data?) -> String {
        if let field, field.count == 17, field.first == 0x01 || field.first == 0x02 { return "about" }
        if let field, field.count == 5, field.first == 0x03 { return "timer notice" }
        return "ordinary"
    }

    private func plain(_ cipher: Data, id: String, ts: Int64) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: cipher), using: SymmetricKey(data: GroupCrypto.deriveKey(code)), authenticating: Data("\(id)|\(ts)".utf8))
    }

    // MARK: - The field

    func testTheField() {
        XCTAssertEqual(LeaveNote.field, Data([0x05, 0x01]))
        XCTAssertEqual(LeaveNote.size, 2)
        XCTAssertTrue(LeaveNote.read(field: Data([0x05, 0x01])))
        XCTAssertEqual(Notice.read(field: Data([0x05, 0x01])), .leave)
        for other in [Data([0x05, 0x02]), Data([0x05]), Data([0x05, 0x01, 0x00]), Data([0x05, 0x00]), Data([0x06, 0x01]), Data()] {
            XCTAssertFalse(LeaveNote.read(field: other), Hex.encode(other))
            XCTAssertNil(Notice.read(field: other), Hex.encode(other))
        }
        XCTAssertFalse(LeaveNote.read(field: nil))
        XCTAssertEqual(Data(LeaveNote.text.utf8).count, 21)
    }

    func testANoteIsALineThatStillGoesOnTheRadio() throws {
        let c = try group.seal(id: "00000000000000c1", ts: 9, sender: cara, name: "Cara", text: LeaveNote.text, fifth: LeaveNote.field, iv: iv) { _ in self.sig }
        let o = try XCTUnwrap(group.openSigned(Packet(id: "00000000000000c1", ts: 9, ttl: 16, cipher: c)))
        XCTAssertEqual(o.message.notice, .leave)
        XCTAssertEqual(o.message.text, LeaveNote.text)
        XCTAssertNil(o.message.about)
        XCTAssertTrue(o.message.isSilent, "never a bubble, an alert or a receipt")
        XCTAssertFalse(o.message.offRadio, "small, and maybe the only way to an admin")
        XCTAssertEqual(o.fifth, LeaveNote.field)
        // The timer's notice and a reaction stay off the radio, as before.
        var m = o.message; m.notice = .timer(3600); XCTAssertTrue(m.offRadio)
        // A note is never copied for history: nothing of it is shown, and the record keeps what counts.
        XCTAssertNil(History.seal(key: Data(repeating: 1, count: 32), id: "00000000000000c1", group: "g", epoch: "e", o, index: 0, counter: 0))
    }

    func testAnotherFormIsAnOrdinaryMessage() throws {
        let c = try group.seal(id: "00000000000000c2", ts: 9, sender: cara, name: "Cara", text: "👋 hi", fifth: Data([0x05, 0x02]), iv: iv) { _ in self.sig }
        let o = try XCTUnwrap(group.openSigned(Packet(id: "00000000000000c2", ts: 9, ttl: 16, cipher: c)))
        XCTAssertNil(o.message.notice); XCTAssertNil(o.message.about); XCTAssertFalse(o.message.isSilent); XCTAssertEqual(o.message.text, "👋 hi")
    }

    /// Hard requirement 5: a build without the feature sees `👋 I left the group` as an ordinary message, and nothing else.
    func testReadersWithoutTheFeatureSeeAnOrdinaryMessage() throws {
        let id = "00000000000000c3"
        let with = try group.seal(id: id, ts: 9, sender: cara, name: "Cara", text: LeaveNote.text, fifth: LeaveNote.field, iv: iv) { _ in self.sig }
        let without = try group.seal(id: id, ts: 9, sender: cara, name: "Cara", text: LeaveNote.text, iv: iv) { _ in self.sig }
        // Before the 5th field: the same three fields, no 4th.
        let a = try XCTUnwrap(preFifthRead(try plain(with, id: id, ts: 9))), b = try XCTUnwrap(preFifthRead(try plain(without, id: id, ts: 9)))
        XCTAssertEqual(a.sender, b.sender); XCTAssertEqual(a.name, b.name); XCTAssertEqual(a.text, b.text); XCTAssertNil(a.fourth); XCTAssertNil(b.fourth)
        XCTAssertEqual(a.text, Data(LeaveNote.text.utf8))
        // The build before this one: neither a reply, a reaction nor the timer's notice.
        let o = try XCTUnwrap(group.openSigned(Packet(id: id, ts: 9, ttl: 16, cipher: with)))
        XCTAssertEqual(timerBuildReads(o.fifth), "ordinary")
        XCTAssertNil(About.read(field: o.fifth, text: Data(LeaveNote.text.utf8)))
        XCTAssertNil(GroupTimer.read(field: o.fifth))
        // Not a check-in either, whatever the build.
        XCTAssertNil(CheckIn.of(LeaveNote.text))
    }

    func testTheFieldIsSigned() throws {
        func digest(_ fifth: Data?) throws -> Data {
            let c = try group.seal(id: "00000000000000c4", ts: 9, sender: cara, name: "Cara", text: LeaveNote.text, fifth: fifth, iv: iv, pad: .none) { _ in self.sig }
            return try XCTUnwrap(group.openSigned(Packet(id: "00000000000000c4", ts: 9, ttl: 16, cipher: c))).digest
        }
        XCTAssertNotEqual(try digest(nil), try digest(LeaveNote.field), "nobody can add the field to an ordinary message")
    }

    // MARK: - Which notes count (L1)

    private func secret(_ n: UInt8) -> Data { Data(repeating: n, count: 32) }

    /// An epoch from its raw secret, as a phone holds it.
    private func epoch(_ n: Int64, _ members: [String], readOnly: Bool = false, removes: String? = nil, by: String = "1111111111111111") -> Keyring.Epoch {
        .fresh(number: n, made: t0 + n * hour, secret: secret(UInt8(n)), roster: members, members: readOnly ? [] : members, admins: [anna],
               code: removes == nil ? nil : "new-code-\(n)", via: removes == nil ? nil : "old", removes: removes, by: by, rank: removes == nil ? Keyring.byAdmin : 0, taken: t0 + n * hour)
    }

    /// A note by `sender` at `position`, sealed with its message key in epoch `n` (members as listed), opened by `e`.
    private func note(_ n: Int64, _ members: [String], sender: String, by e: Keyring.Epoch) throws -> GroupCrypto.Opened {
        let s = secret(UInt8(n)), g = GroupCrypto.fromSecret(MessageKeys.outer(s)), position = try XCTUnwrap(members.firstIndex(of: sender))
        let mk = try XCTUnwrap(MessageKeys.of(s, members: members).key(position, 3))
        let ts = t0 + n * hour + 60_000
        let c = try g.seal(messageKey: mk, index: position, counter: 3, id: "e6080000000000c5", ts: ts, sender: sender, name: "Cara", text: LeaveNote.text,
                           fifth: LeaveNote.field, iv: iv) { _ in self.sig }
        return try XCTUnwrap(e.open(Packet(id: "e6080000000000c5", ts: g.hideTime(ts, c), ttl: 16, cipher: c)), "the epoch opens it")
    }

    private func ring(_ epochs: [Keyring.Epoch]) -> Keyring { let r = Keyring(); epochs.forEach { r.add($0) }; return r }

    func testANoteFromAMemberStillListedCounts() throws {
        let e5 = epoch(5, [anna, ben, cara]), r = ring([e5])
        let o = try note(5, [anna, ben, cara], sender: cara, by: e5)
        XCTAssertEqual(o.opener?.number, 5); XCTAssertEqual(o.message.notice, .leave)
        XCTAssertEqual(LeaveNote.verdict(sender: o.message.sender, me: anna, checked: true, opener: o.opener, ring: r), .valid)
        XCTAssertEqual(LeaveNote.verdict(sender: "web-" + cara, me: anna, checked: true, opener: o.opener, ring: r), .valid, "a web- in front is the same member")
        // Through the epochs since: still listed in each.
        let e6 = epoch(6, [anna, ben, cara]), r2 = ring([e5, e6])
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: o.opener, ring: r2), .valid)
    }

    /// Hard requirement 1: a note played back after the same key was let in again does nothing.
    func testAReplayedNoteAfterTheSameKeyWasLetInAgainDoesNothing() throws {
        let e5 = epoch(5, [anna, ben, cara]), e6 = epoch(6, [anna, ben], removes: cara), e7 = epoch(7, [anna, ben, cara])
        let o = try note(5, [anna, ben, cara], sender: cara, by: e5)
        let r = ring([e5, e6, e7])
        XCTAssertEqual(r.current.number, 7); XCTAssertTrue(r.current.members.contains(cara))
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: o.opener, ring: r), .no)
        // The same holds without the removal: a key that left them out and one that let them in again.
        let e6b = epoch(6, [anna, ben])
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: o.opener, ring: ring([e5, e6b, e7])), .no)
    }

    func testARemovedMembersNoteDoesNothing() throws {
        let e5 = epoch(5, [anna, ben, cara]), e6 = epoch(6, [anna, ben], removes: cara)
        let o = try note(5, [anna, ben, cara], sender: cara, by: e5)
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: o.opener, ring: ring([e5, e6])), .no)
    }

    func testAnEpochKeptOnlyToReadWithDecidesNothing() throws {
        let e5 = epoch(5, [anna, ben, cara], readOnly: true), e6 = epoch(6, [anna, ben, cara])
        let o = try note(5, [anna, ben, cara], sender: cara, by: e5)
        XCTAssertEqual(o.opener?.number, 5, "it opens: its message keys know everyone")
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: o.opener, ring: ring([e5, e6])), .no)
    }

    func testANumberWithNoEpochHereFails() throws {
        let e5 = epoch(5, [anna, ben, cara]), e7 = epoch(7, [anna, ben, cara])
        let o = try note(5, [anna, ben, cara], sender: cara, by: e5)
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: o.opener, ring: ring([e5, e7])), .no, "epoch 6 isn't here: nothing shows it listed them")
    }

    func testUncheckedForgedOwnAndForeignNotes() throws {
        let e5 = epoch(5, [anna, ben, cara]), r = ring([e5])
        let o = try note(5, [anna, ben, cara], sender: cara, by: e5)
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: nil, opener: o.opener, ring: r), .notYet, "decided when it checks")
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: false, opener: o.opener, ring: r), .no)
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: cara, checked: true, opener: o.opener, ring: r), .no, "this phone's own note")
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: nil, ring: r), .no, "not opened by an epoch")
        XCTAssertEqual(LeaveNote.verdict(sender: cara, me: anna, checked: true, opener: epoch(5, [anna, ben, cara]), ring: r), .no, "an epoch this ring doesn't hold")
        XCTAssertEqual(LeaveNote.verdict(sender: ben, me: anna, checked: true, opener: o.opener, ring: ring([e5, epoch(6, [anna, cara])])), .no, "Ben isn't listed now")
    }

    // MARK: - Admins' phones (L4, L5)

    func testWhatAnAdminsPhoneDoes() {
        XCTAssertEqual(LeaveNote.act(admin: true, inCurrent: true, removalHeld: false, keysComplete: true), .removeNow)
        XCTAssertEqual(LeaveNote.act(admin: true, inCurrent: true, removalHeld: false, keysComplete: false), .retryHourly)
        XCTAssertEqual(LeaveNote.act(admin: false, inCurrent: true, removalHeld: false, keysComplete: true), .nothing, "a member's phone only shows the line")
        XCTAssertEqual(LeaveNote.act(admin: true, inCurrent: false, removalHeld: false, keysComplete: true), .nothing, "out already")
        XCTAssertEqual(LeaveNote.act(admin: true, inCurrent: true, removalHeld: true, keysComplete: true), .nothing, "another admin's removal is here")
    }

    // MARK: - The records (L2, L3)

    func testOnlyTheFirstNoteCountsAndLaterMessagesAreHidden() {
        var b = LeaveBook()
        XCTAssertTrue(b.take(member: cara, ts: t0, epoch: 5, now: t0 + 10))
        XCTAssertFalse(b.take(member: cara, ts: t0 + 99, epoch: 5, now: t0 + 20), "later notes change nothing")
        XCTAssertEqual(b.record(cara)?.ts, t0)
        XCTAssertFalse(b.hides(cara, ts: t0 - 1)); XCTAssertFalse(b.hides(cara, ts: t0)); XCTAssertTrue(b.hides(cara, ts: t0 + 1))
        XCTAssertTrue(b.hides("web-" + cara, ts: t0 + 1))
        XCTAssertFalse(b.hides(ben, ts: t0 + 1))
    }

    func testARecordGoesWhenTheMemberIsOutAndTheRemovalIsKeptForTheLine() {
        var b = LeaveBook()
        b.take(member: cara, ts: t0, epoch: 5, now: t0)
        XCTAssertFalse(b.sweep(inCurrent: { _ in true }, removal: { _ in nil }, held: ["e5"], now: t0 + hour))
        XCTAssertTrue(b.sweep(inCurrent: { $0 != self.cara }, removal: { $0 == self.cara ? "e6" : nil }, held: ["e5", "e6"], now: t0 + hour))
        XCTAssertNil(b.record(cara)); XCTAssertEqual(b.removals["e6"]?.member, cara)
        XCTAssertTrue(b.hides(cara, ts: t0 + 1), "what they wrote after the note stays hidden while the removal is held")
        // A new note after they were let in again with the same key starts a new record.
        XCTAssertTrue(b.take(member: cara, ts: t0 + 5 * hour, epoch: 8, now: t0 + 5 * hour))
        // The removal goes with its epoch.
        XCTAssertTrue(b.sweep(inCurrent: { _ in true }, removal: { _ in nil }, held: ["e8"], now: t0 + 6 * hour))
        XCTAssertTrue(b.removals.isEmpty); XCTAssertNotNil(b.record(cara))
        // Out without a removal (left out by a key): nothing kept.
        var c = LeaveBook(); c.take(member: ben, ts: t0, epoch: 5, now: t0)
        c.sweep(inCurrent: { _ in false }, removal: { _ in nil }, held: [], now: t0)
        XCTAssertTrue(c.isEmpty)
    }

    func testARecordLastsSevenDays() {
        var b = LeaveBook(); b.take(member: cara, ts: t0, epoch: 5, now: t0)
        XCTAssertFalse(b.sweep(inCurrent: { _ in true }, removal: { _ in nil }, held: [], now: t0 + LeaveNote.keepMs - 1))
        XCTAssertTrue(b.sweep(inCurrent: { _ in true }, removal: { _ in nil }, held: [], now: t0 + LeaveNote.keepMs))
        XCTAssertTrue(b.isEmpty)
    }

    func testSavedAndReadBack() {
        var b = LeaveBook()
        b.take(member: cara, ts: t0, epoch: 5, now: t0 + 1)
        b.take(member: ben, ts: t0 + 2, epoch: 6, now: t0 + 3)
        b.sweep(inCurrent: { $0 != self.ben }, removal: { _ in "abc" }, held: ["abc"], now: t0 + 4)
        XCTAssertEqual(LeaveBook(saved: b.saved), b)
        XCTAssertEqual(LeaveBook(saved: ["r bad", "x", "r \(cara) 1 2 3", "nonsense line"]).record(cara)?.taken, 3)
    }

    // MARK: - Words

    /// Hard requirement 6: never "was told", "removed from the group" and the rest of the "Never say" list.
    func testTheWordingNeverClaimsTheGroupWasToldOrAnyoneRemoved() {
        let g = "My group"
        let all = [LeaveWords.sheetTitle(g), LeaveWords.sheetText, LeaveWords.leaveButton(g), LeaveWords.cantTell(g), LeaveWords.chooseTitle(g), LeaveWords.chooseText,
                   LeaveWords.leaveWithoutChoosing, LeaveWords.withoutChoosingWarning, LeaveWords.cantHandOver, LeaveWords.leaveWithoutTelling, LeaveWords.waitingTitle(g),
                   LeaveWords.waitingText, LeaveWords.leaveNow, LeaveWords.left(g, noteLeft: true), LeaveWords.left(g, noteLeft: false), LeaveWords.left(g, noteLeft: nil),
                   LeaveWords.tellFirst, LeaveWords.tellFirstSub, LeaveWords.tellingTitle, LeaveWords.eraseNow, LeaveWords.empty,
                   LeaveWords.erased(sent: 2, left: 2), LeaveWords.erased(sent: 2, left: 1), LeaveWords.erased(sent: 0, left: 0),
                   LeaveWords.line("Marco", waiting: true), LeaveWords.line("Marco", waiting: false), LeaveNote.text]
        for s in all {
            let l = s.lowercased()
            for bad in LeaveWords.never { XCTAssertFalse(l.contains(bad), s) }
            XCTAssertFalse(l.contains("was told"), s)
            XCTAssertFalse(l.contains("removed "), s)
        }
        XCTAssertEqual(LeaveWords.sheetText, "This phone forgets the group, its messages and its keys. A note asks the group's admins to remove you and change the key. To come back you need a new invite.")
        // M10: the final erase screen names no group: the phone may be taken at that moment.
        XCTAssertEqual(LeaveWords.erased(sent: 2, left: 2), "The notes left this phone.")
        XCTAssertEqual(LeaveWords.erased(sent: 2, left: 1), "1 of 2 notes didn't leave this phone in time.")
        XCTAssertEqual(LeaveWords.erased(sent: 0, left: 0), "Your groups were not told.")
        for (s, l) in [(1, 1), (1, 0), (3, 1), (0, 0)] { XCTAssertFalse(LeaveWords.erased(sent: s, left: l).contains(g)) }
        XCTAssertEqual(LeaveWords.left(g, noteLeft: false), "You left My group. The note didn't leave this phone in time. It goes when a phone comes into reach.")
        XCTAssertEqual(LeaveWords.line("Marco", waiting: true), "Marco left. Waiting for an admin's phone to change the key.")
    }
}
