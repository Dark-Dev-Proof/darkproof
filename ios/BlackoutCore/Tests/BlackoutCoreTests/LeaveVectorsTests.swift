import XCTest
import CryptoKit
@testable import BlackoutCore

/// "Leaving the group" against spec/test-vectors.txt (`kind: leave-field`, `leave-note`, `leave-valid`, `leave-admin`,
/// `leave-after`), made by spec/make-reply-vectors.py. Android checks the same.
final class LeaveVectorsTests: XCTestCase {
    private func of(_ kind: String) -> [[String: String]] { VectorsTests.vectors.filter { $0["kind"] == kind } }
    private let t0: Int64 = 1_794_000_000_000, hour: Int64 = 3_600_000

    func testVectorCounts() {
        XCTAssertEqual(of("leave-field").count, 6); XCTAssertEqual(of("leave-note").count, 1); XCTAssertEqual(of("leave-valid").count, 12)
        XCTAssertEqual(of("leave-admin").count, 16); XCTAssertEqual(of("leave-after").count, 4)
    }

    func testFields() {
        for v in of("leave-field") {
            let f = Hex.decode(v["field"]!)
            XCTAssertEqual(LeaveNote.read(field: f) ? "leave" : "ordinary", v["reads"], v["field"]!)
            XCTAssertEqual(Notice.read(field: f) == .leave ? "leave" : "ordinary", v["reads"])
            XCTAssertEqual(Hex.encode(Data([0, 0, UInt8(f.count >> 8), UInt8(f.count & 255)]) + f), v["framed"])
        }
        XCTAssertEqual(of("leave-field").filter { $0["reads"] == "leave" }.map { $0["field"]! }, [Hex.encode(LeaveNote.field)], "what this app writes")
    }

    func testTheSealedNote() throws {
        let v = try XCTUnwrap(of("leave-note").first)
        let secret = Hex.decode(v["secret"]!), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        let position = Int(v["position"]!)!, number = Int(v["number"]!)!, ts = Int64(v["ts"]!)!
        let mk = try XCTUnwrap(MessageKeys.of(secret, members: [v["member_0"]!, v["member_1"]!]).key(position, number))
        let p = Packet(id: v["id"]!, ts: Int64(v["wire_ts"]!)!, ttl: 16, cipher: Hex.decode(v["cipher"]!))
        let o = try XCTUnwrap(g.open(messageKey: mk, index: position, counter: number, p))
        XCTAssertEqual(o.message.text, v["text"]); XCTAssertEqual(o.message.text, LeaveNote.text); XCTAssertEqual(Hex.encode(Data(o.message.text.utf8)), v["text_hex"])
        XCTAssertEqual(o.message.ts, ts); XCTAssertEqual(o.message.sender, v["sender"]); XCTAssertEqual(o.message.name, v["name"])
        XCTAssertEqual(o.message.notice, .leave); XCTAssertEqual(v["reads"], "leave")
        XCTAssertTrue(o.message.isSilent, "shown as a line, never a bubble"); XCTAssertEqual(v["shown"], "line")
        XCTAssertNil(o.message.about); XCTAssertNil(o.extra)
        XCTAssertEqual(Hex.encode(try XCTUnwrap(o.fifth)), v["fifth"])
        XCTAssertEqual(Hex.encode(o.digest), Hex.encode(Data(SHA256.hash(data: Hex.decode(v["signed_message"]!)))))
        // An app without the field: the text, an ordinary message.
        XCTAssertNil(About.read(field: o.fifth, text: Data(o.message.text.utf8))); XCTAssertNil(GroupTimer.read(field: o.fifth))
        XCTAssertEqual(o.message.text, v["older"])
        // What this app seals is byte for byte the vector.
        let again = try g.seal(messageKey: mk, index: position, counter: number, id: v["id"]!, ts: ts, sender: v["sender"]!, name: v["name"]!, text: LeaveNote.text,
                               fifth: LeaveNote.field, iv: Hex.decode(v["iv"]!)) { _ in Hex.decode(v["signature"]!) }
        XCTAssertEqual(Hex.encode(again), v["cipher"])
    }

    /// Each vector's epochs, held by a phone; the note's opener is the one numbered `opened_by`.
    func testWhichNotesCount() throws {
        for v in of("leave-valid") {
            let ring = Keyring()
            var opener: Keyring.Epoch?
            for key in v.keys where key.hasPrefix("epoch_") {
                let n = Int64(key.dropFirst(6))!, members = v[key]!.split(separator: ",").map(String.init)
                let removes = v["removes_\(n)"], read = v["read_\(n)"] == "yes"
                let e = Keyring.Epoch.fresh(number: n, made: t0 + n * hour, secret: Data(repeating: UInt8(n), count: 32), roster: members, members: read ? [] : members,
                                            admins: [members[0]], code: removes == nil ? nil : "code-\(n)", via: removes == nil ? nil : "old", removes: removes,
                                            by: members[0], rank: removes == nil ? Keyring.byAdmin : 0, taken: t0 + n * hour)
                ring.add(e)
                if n == Int64(v["opened_by"]!)! { opener = e }
                // A rival that lost at n: a member's key (not an admin's), so it comes after the admin's winner whatever its hash.
                if let rival = v["rival_\(n)"]?.split(separator: ",").map(String.init) {
                    ring.add(Keyring.Epoch.fresh(number: n, made: t0 + n * hour + 1, secret: Data(repeating: UInt8(n) &+ 100, count: 32), members: rival,
                                                 admins: [rival[0]], by: rival.last!, rank: .max, taken: t0 + n * hour + 1))
                }
            }
            let checked: Bool? = v["checked"] == "yes" ? true : v["checked"] == "no" ? false : nil
            let got = LeaveNote.verdict(sender: v["sender"]!, me: v["me"]!, checked: checked, opener: opener, ring: ring)
            XCTAssertEqual(got == .valid ? "valid" : got == .notYet ? "not yet" : "no", v["valid"], "\(v)")
        }
    }

    func testWhatAnAdminsPhoneDoes() {
        for v in of("leave-admin") {
            let act = LeaveNote.act(admin: v["admin"] == "yes", inCurrent: v["in_current"] == "yes", removalHeld: v["removal_held"] == "yes", keysComplete: v["keys"] == "yes")
            XCTAssertEqual(act == .removeNow ? "remove-now" : act == .retryHourly ? "retry-hourly" : "nothing", v["act"], "\(v)")
        }
    }

    func testWhatIsShownFromTheLeaver() {
        for v in of("leave-after") {
            var book = LeaveBook()
            book.take(member: v["leaver"]!, ts: Int64(v["note_ts"]!)!, epoch: 5, now: t0)
            XCTAssertEqual(book.hides(v["sender"]!, ts: Int64(v["ts"]!)!) ? "no" : "yes", v["shown"], "\(v)")
        }
    }
}
