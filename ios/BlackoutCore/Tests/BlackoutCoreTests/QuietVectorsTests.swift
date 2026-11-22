import XCTest
import CryptoKit
@testable import BlackoutCore

/// "If I go quiet" against spec/test-vectors.txt (`kind: quiet-field`, `quiet-message`, `quiet-valid`, `quiet-pick`,
/// `quiet-cancel`, `quiet-late`, `quiet-text`), made by spec/make-reply-vectors.py. Android checks the same.
final class QuietVectorsTests: XCTestCase {
    private func of(_ kind: String) -> [[String: String]] { VectorsTests.vectors.filter { $0["kind"] == kind } }

    func testVectorCounts() {
        XCTAssertEqual(of("quiet-field").count, 5); XCTAssertEqual(of("quiet-message").count, 1); XCTAssertEqual(of("quiet-valid").count, 8)
        XCTAssertEqual(of("quiet-pick").count, 4); XCTAssertEqual(of("quiet-cancel").count, 10); XCTAssertEqual(of("quiet-late").count, 8)
        XCTAssertEqual(of("quiet-text").count, 11)
    }

    func testFields() {
        for v in of("quiet-field") {
            let f = Hex.decode(v["field"]!)
            XCTAssertEqual(Hex.encode(Data([0, 0, UInt8(f.count >> 8), UInt8(f.count & 255)]) + f), v["framed"])
            if v["reads"] == "clock" {
                let d = Int64(v["deadline"]!)!, ts = Int64(v["ts"]!)!
                XCTAssertEqual(QuietClock.raw(field: f), d)
                XCTAssertEqual(Hex.encode(QuietClock.field(deadline: d)), v["field"], "what this app writes")
                XCTAssertEqual(QuietClock.valid(deadline: d, ts: ts) ? "yes" : "no", v["valid"])
                XCTAssertEqual(Notice.read(field: f, ts: ts), .clock(d))
            } else {
                XCTAssertEqual(v["reads"], "ordinary"); XCTAssertNil(QuietClock.raw(field: f), v["field"]!); XCTAssertNil(Notice.read(field: f, ts: 1))
            }
        }
    }

    func testTheSealedClock() throws {
        let v = try XCTUnwrap(of("quiet-message").first)
        let secret = Hex.decode(v["secret"]!), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        let position = Int(v["position"]!)!, number = Int(v["number"]!)!, ts = Int64(v["ts"]!)!, d = Int64(v["deadline"]!)!
        let mk = try XCTUnwrap(MessageKeys.of(secret, members: [v["member_0"]!, v["member_1"]!]).key(position, number))
        let p = Packet(id: v["id"]!, ts: Int64(v["wire_ts"]!)!, ttl: 16, cipher: Hex.decode(v["cipher"]!))
        let o = try XCTUnwrap(g.open(messageKey: mk, index: position, counter: number, p))
        XCTAssertEqual(o.message.text, v["text"]); XCTAssertEqual(o.message.text, QuietClock.text(ts: ts, deadline: d)); XCTAssertEqual(Hex.encode(Data(o.message.text.utf8)), v["text_hex"])
        XCTAssertEqual(o.message.ts, ts); XCTAssertEqual(o.message.sender, v["sender"]); XCTAssertEqual(o.message.name, v["name"])
        XCTAssertEqual(o.message.notice, .clock(d)); XCTAssertEqual(v["reads"], "clock")
        XCTAssertFalse(o.message.isSilent, "a card, alerted and confirmed"); XCTAssertEqual(v["shown"], "card")
        XCTAssertNil(o.message.about); XCTAssertNil(o.extra)
        XCTAssertEqual(Hex.encode(try XCTUnwrap(o.fifth)), v["fifth"])
        XCTAssertEqual(Hex.encode(o.digest), Hex.encode(Data(SHA256.hash(data: Hex.decode(v["signed_message"]!)))))
        XCTAssertEqual(Hex.encode(Data(o.digest.prefix(8))), v["mark"])
        // An app without the field: the text, an ordinary message.
        XCTAssertNil(About.read(field: o.fifth, text: Data(o.message.text.utf8))); XCTAssertNil(GroupTimer.read(field: o.fifth)); XCTAssertFalse(LeaveNote.read(field: o.fifth))
        XCTAssertNil(Notice.read(field: o.fifth))
        XCTAssertEqual(o.message.text, v["older"])
        // What this app seals is byte for byte the vector.
        let again = try g.seal(messageKey: mk, index: position, counter: number, id: v["id"]!, ts: ts, sender: v["sender"]!, name: v["name"]!, text: QuietClock.text(ts: ts, deadline: d),
                               fifth: QuietClock.field(deadline: d), iv: Hex.decode(v["iv"]!)) { _ in Hex.decode(v["signature"]!) }
        XCTAssertEqual(Hex.encode(again), v["cipher"])
    }

    func testWhichDeadlinesCount() {
        for v in of("quiet-valid") {
            XCTAssertEqual(QuietClock.valid(deadline: Int64(v["deadline"]!)!, ts: Int64(v["ts"]!)!) ? "yes" : "no", v["valid"], "\(v)")
        }
    }

    func testWhichClockCounts() {
        for v in of("quiet-pick") {
            var book = QuietBook()
            var last: Int64 = 0
            for x in v["arrival"]!.split(separator: ",").map(String.init) {
                let f = v["clock_\(x)"]!.split(separator: " ").map(String.init)
                let ts = Int64(f[0])!; last = max(last, ts)
                book.take(member: "3333333333333333", id: f[1], mark: f[2], ts: ts, deadline: Int64(f[3])!, spot: nil, now: last, highRisk: false)
            }
            let want = v["clock_\(v["counts"]!)"]!.split(separator: " ").map(String.init)
            XCTAssertEqual(book.clock("3333333333333333")?.id, want[1], "\(v)"); XCTAssertEqual(book.clock("3333333333333333")?.mark, want[2])
        }
    }

    func testWhatCancels() {
        for v in of("quiet-cancel") {
            let member = "3333333333333333", clockTs = Int64(v["clock_ts"]!)!, ts = Int64(v["ts"]!)!
            var book = QuietBook()
            book.take(member: member, id: "e608000000000001", mark: "0000000000000001", ts: clockTs, deadline: clockTs + 3_600_000, spot: nil, now: clockTs, highRisk: false)
            let text = String(decoding: Hex.decode(v["text_hex"]!), as: UTF8.self)
            XCTAssertEqual(text, v["text"])
            // The message as a reader takes it: its 5th field read as the app reads one.
            var m = Message(id: "e608000000000002", ts: ts, sender: member, name: "Cara", text: text, group: "g")
            let fifth = v["fifth"].map(Hex.decode)
            m.about = About.read(field: fifth, text: Data(text.utf8)); m.notice = Notice.read(field: fifth, ts: ts)
            switch QuietClock.effect(of: m) {
            case .clock(let d)?: book.take(member: member, id: m.id, mark: "0000000000000002", ts: ts, deadline: d, spot: nil, now: clockTs, highRisk: false)
            case .checkIn?: book.checkIn(member: member, ts: ts, now: clockTs, highRisk: false)
            case nil: break
            }
            XCTAssertEqual(book.armed(member) ? "no" : "yes", v["cancels"], "\(v)")
        }
    }

    func testWhatIsDue() {
        for v in of("quiet-late") {
            let due = QuietClock.due(deadline: Int64(v["deadline"]!)!, learned: Int64(v["learned"]!)!, now: Int64(v["now"]!)!, armed: v["armed"] == "yes")
            let word: String = switch due { case .at: "at-deadline"; case .now: "now"; case .late: "late"; case .none: "none" }
            XCTAssertEqual(word, v["does"], "\(v)")
        }
    }

    func testTheText() {
        for v in of("quiet-text") {
            let ts = Int64(v["ts"]!)!, d = Int64(v["deadline"]!)!, spot = v["spot"].flatMap { Spot.last(in: "📍 " + $0) }
            if v["spot"] != nil { XCTAssertNotNil(spot) }
            let t = QuietClock.text(ts: ts, deadline: d, spot: spot)
            XCTAssertEqual(t, v["text"]); XCTAssertEqual(Hex.encode(Data(t.utf8)), v["text_hex"]); XCTAssertEqual(String(Data(t.utf8).count), v["bytes"])
            XCTAssertEqual(QuietClock.spot(in: t), spot)
        }
    }
}
