import XCTest
import CryptoKit
@testable import BlackoutCore

/// Person codes (spec/FORMAT.md, "Person code") and how a checked person was checked. Same cases on Android.
final class PersonCodeTests: XCTestCase {
    // The two `kind: member` keys of spec/test-vectors.txt.
    private let anna = Hex.decode("02ea9227dede645b42e9d369c0759de19b46685357f9d0485b8dc80a535e659acf")
    private let ben = Hex.decode("028e533b6fa0bf7b4625bb30667c01fb607ef9f8b8a80fef5b300628703187b2a3")
    private let annaCode = "DPK1:AuqSJ97eZFtC6dNpwHWd4ZtGaFNX-dBIW43IClNeZZrP"
    private let benCode = "DPK1:Ao5TO2-gv3tGJbswZnwB-2B--fi4qA_vWzAGKHAxh7Kj"

    private func newKey() -> Data { P256.Signing.PrivateKey().publicKey.compressedRepresentation }

    // MARK: - Encode and decode

    func testEncodeIsThePrefixAnd44Base64urlCharacters() {
        XCTAssertEqual(PersonCode.encode(anna), annaCode)
        XCTAssertEqual(PersonCode.encode(ben), benCode) // has both "-" and "_"
        XCTAssertEqual(annaCode.count, 5 + 44)
        XCTAssertFalse(benCode.contains("=")); XCTAssertFalse(benCode.contains("+")); XCTAssertFalse(benCode.contains("/"))
    }

    func testDecodeGivesBackEveryByte() {
        XCTAssertEqual(PersonCode.decode(annaCode), anna)
        XCTAssertEqual(PersonCode.decode(benCode), ben)
        for _ in 0..<50 {
            let k = newKey()
            let code = PersonCode.encode(k)
            XCTAssertEqual(code?.count, 49)
            XCTAssertEqual(code.flatMap(PersonCode.decode), k)
        }
    }

    func testCodeCarriesTheBytesTheEmojiAreMadeFrom() {
        // The emoji of the key in the code are the emoji of the vector: scanning and comparing emoji are about the same key.
        XCTAssertEqual(PersonCode.decode(annaCode).map(MemberKey.emoji), [15, 55, 62, 35, 57, 53, 61, 59, 7, 62, 20, 7, 39, 8, 2, 2])
        XCTAssertEqual(PersonCode.decode(annaCode).map(MemberKey.id), "c482f374ea24d954")
    }

    func testWrongPrefixIsRefused() {
        let body = String(annaCode.dropFirst(5))
        for bad in ["DPK2:" + body, "dpk1:" + body, "DPK1" + body, "DPK1;" + body, " " + annaCode, "DPK:" + body, body, "",
                    "darkproof://join?n=My%20group&c=ABCD-EFGH-JKLM-NPQR", "https://darkproof.app/join#" + body] {
            XCTAssertNil(PersonCode.decode(bad), bad)
        }
    }

    func testBadBase64IsRefused() {
        let body = String(annaCode.dropFirst(5))
        let std = anna.base64EncodedString() // "+" and "/" are the other alphabet
        XCTAssertTrue(std.contains("+") || std.contains("/"))
        for bad in ["DPK1:" + String(body.dropLast()),      // 43 characters
                    annaCode + "A",                          // 45
                    annaCode + "=",                          // padding
                    annaCode + "\n", annaCode + " ",         // nothing is trimmed
                    "DPK1:" + std,                           // base64, not base64url
                    "DPK1:" + String(body.dropLast()) + "*", // not in the alphabet
                    "DPK1:" + String(body.dropLast()) + "é", // not ASCII
                    "DPK1:" + String(body.dropLast(4)) + "A A=",
                    "DPK1:"] {
            XCTAssertNil(PersonCode.decode(bad), bad)
        }
    }

    func testOnlyTheShapeOfACompressedPointIsAKey() {
        // 33 bytes that don't start with 02 or 03, and other lengths: no code is made of them, and none is read as one.
        let uncompressedTag = Data([4]) + anna.dropFirst(), zeroTag = Data([0]) + anna.dropFirst()
        for bad in [uncompressedTag, zeroTag, Data(anna.dropLast()), anna + Data([1]), Data()] { XCTAssertNil(PersonCode.encode(bad)) }
        for bad in [uncompressedTag, zeroTag] {
            let text = "DPK1:" + bad.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            XCTAssertEqual(text.count, 49)
            XCTAssertNil(PersonCode.decode(text))
        }
        XCTAssertNotNil(PersonCode.encode(Data([3]) + anna.dropFirst())) // 03 is the other half of the curve
    }

    // MARK: - Comparing

    func testEqualKeysAreTheSame() {
        XCTAssertEqual(PersonCode.check(benCode, theirs: ben, mine: anna), .same)
        XCTAssertEqual(PersonCode.check(benCode, theirs: ben, mine: nil), .same)
    }

    func testUnequalKeysAreNotThePersons() {
        let other = newKey()
        XCTAssertEqual(PersonCode.check(PersonCode.encode(other)!, theirs: ben, mine: anna), .other)
        XCTAssertEqual(PersonCode.check(PersonCode.encode(other)!, theirs: ben, mine: nil), .other)
        // Every byte counts: one bit changed anywhere is another key.
        for i in [1, 16, 32] {
            var changed = ben; changed[i] ^= 1
            XCTAssertEqual(PersonCode.check(PersonCode.encode(changed)!, theirs: ben, mine: anna), .other, "byte \(i)")
        }
        var tag = ben; tag[0] = 3
        XCTAssertEqual(PersonCode.check(PersonCode.encode(tag)!, theirs: ben, mine: anna), .other)
    }

    func testOwnCodeIsToldApart() {
        XCTAssertEqual(PersonCode.check(annaCode, theirs: ben, mine: anna), .own)
        XCTAssertEqual(PersonCode.check(annaCode, theirs: ben, mine: nil), .other) // own key not ready: still not the person's
    }

    func testAnythingElseIsNotACode() {
        for text in ["hello", "", "darkproof://join?n=My%20group&c=ABCD-EFGH-JKLM-NPQR", "DPK1:", "DPK2:" + annaCode.dropFirst(5)] {
            XCTAssertEqual(PersonCode.check(text, theirs: ben, mine: anna), .notACode, text)
        }
    }

    // MARK: - How someone was checked

    private func mark(_ checked: [String], _ inPerson: [String], id: String, name: String, how: Checked.How, group: String = "g1") -> (checked: [String], inPerson: [String]) {
        let c = Checked.marked(checked, group: group, id: id, name: name)
        return (c, Checked.markedHow(inPerson, checked: c, group: group, id: id, how: how))
    }

    func testMarksMadeBeforeCountAsEmoji() {
        let old = [Checked.entry(group: "g1", id: "aaaaaaaaaaaaaaaa", name: "Ben")] // saved before "how" was kept
        XCTAssertEqual(Checked.how(old, inPerson: [], group: "g1", id: "aaaaaaaaaaaaaaaa"), .emoji)
        XCTAssertNil(Checked.how(old, inPerson: [], group: "g1", id: "bbbbbbbbbbbbbbbb"))
        XCTAssertNil(Checked.how(old, inPerson: [], group: "g2", id: "aaaaaaaaaaaaaaaa"))
    }

    func testInPersonIsKeptNextToTheMark() {
        let s = mark([], [], id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .inPerson)
        XCTAssertEqual(Checked.how(s.checked, inPerson: s.inPerson, group: "g1", id: "aaaaaaaaaaaaaaaa"), .inPerson)
        XCTAssertNil(Checked.how(s.checked, inPerson: s.inPerson, group: "g2", id: "aaaaaaaaaaaaaaaa")) // a member id means a person only in its group
        let t = mark(s.checked, s.inPerson, id: "cccccccccccccccc", name: "Luca", how: .emoji)
        XCTAssertEqual(Checked.how(t.checked, inPerson: t.inPerson, group: "g1", id: "aaaaaaaaaaaaaaaa"), .inPerson)
        XCTAssertEqual(Checked.how(t.checked, inPerson: t.inPerson, group: "g1", id: "cccccccccccccccc"), .emoji)
    }

    func testTheLastCheckSaysHow() {
        var s = mark([], [], id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .emoji)
        s = mark(s.checked, s.inPerson, id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .inPerson)
        XCTAssertEqual(Checked.how(s.checked, inPerson: s.inPerson, group: "g1", id: "aaaaaaaaaaaaaaaa"), .inPerson)
        XCTAssertEqual(s.inPerson.count, 1) // marked twice: one entry
        s = mark(s.checked, s.inPerson, id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .inPerson)
        XCTAssertEqual(s.inPerson.count, 1)
        s = mark(s.checked, s.inPerson, id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .emoji)
        XCTAssertEqual(Checked.how(s.checked, inPerson: s.inPerson, group: "g1", id: "aaaaaaaaaaaaaaaa"), .emoji)
        XCTAssertEqual(s.inPerson, [])
    }

    func testNewKeyClearsIt() {
        var s = mark([], [], id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .inPerson)
        // Ben's name with another key: a new key, not checked in any way.
        XCTAssertTrue(Checked.newKey(s.checked, group: "g1", id: "bbbbbbbbbbbbbbbb", name: "Ben"))
        XCTAssertNil(Checked.how(s.checked, inPerson: s.inPerson, group: "g1", id: "bbbbbbbbbbbbbbbb"))
        // Checked again by emoji: the old key's mark and its in-person entry are gone.
        s = mark(s.checked, s.inPerson, id: "bbbbbbbbbbbbbbbb", name: "Ben", how: .emoji)
        XCTAssertEqual(Checked.how(s.checked, inPerson: s.inPerson, group: "g1", id: "bbbbbbbbbbbbbbbb"), .emoji)
        XCTAssertNil(Checked.how(s.checked, inPerson: s.inPerson, group: "g1", id: "aaaaaaaaaaaaaaaa"))
        XCTAssertEqual(s.inPerson, [])
    }

    func testInPersonEntryAloneIsNoMark() {
        let stray = [Checked.inPersonEntry(group: "g1", id: "aaaaaaaaaaaaaaaa")]
        XCTAssertNil(Checked.how([], inPerson: stray, group: "g1", id: "aaaaaaaaaaaaaaaa"))
        // And it goes at the next check of anyone.
        let s = mark([], stray, id: "cccccccccccccccc", name: "Luca", how: .emoji)
        XCTAssertEqual(s.inPerson, [])
    }

    func testOtherGroupsEntriesStay() {
        var s = mark([], [], id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .inPerson, group: "g2")
        s = mark(s.checked, s.inPerson, id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .emoji, group: "g1")
        XCTAssertEqual(Checked.how(s.checked, inPerson: s.inPerson, group: "g2", id: "aaaaaaaaaaaaaaaa"), .inPerson)
        XCTAssertEqual(Checked.how(s.checked, inPerson: s.inPerson, group: "g1", id: "aaaaaaaaaaaaaaaa"), .emoji)
    }

    func testNoMatchTakesTheMarkAway() {
        let s = mark([], [], id: "aaaaaaaaaaaaaaaa", name: "Ben", how: .inPerson)
        let t = mark(s.checked, s.inPerson, id: "cccccccccccccccc", name: "Luca", how: .emoji)
        let left = Checked.unmarked(t.checked, group: "g1", id: "aaaaaaaaaaaaaaaa")
        XCTAssertNil(Checked.how(left, inPerson: t.inPerson, group: "g1", id: "aaaaaaaaaaaaaaaa"))
        XCTAssertEqual(Checked.how(left, inPerson: t.inPerson, group: "g1", id: "cccccccccccccccc"), .emoji)
        XCTAssertEqual(Checked.unmarked(t.checked, group: "g2", id: "aaaaaaaaaaaaaaaa"), t.checked) // another group's member
        // Its in-person entry goes with it, as the app saves it (`Engine.unmarkChecked`).
        XCTAssertEqual(Checked.markedHow(t.inPerson, checked: left, group: "g1", id: "aaaaaaaaaaaaaaaa", how: .emoji), [])
    }

    func testSilentMemberHasNothingHeard() {
        let p = Person.silent(id: "aaaaaaaaaaaaaaaa", name: "Ben")
        XCTAssertEqual(p.lastTs, 0); XCTAssertNil(p.checkIn); XCTAssertNil(p.spot); XCTAssertFalse(p.isReport)
    }
}
