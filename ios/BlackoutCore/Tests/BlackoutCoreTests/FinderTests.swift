import XCTest
import CryptoKit
@testable import BlackoutCore

/// Finder (spec/FORMAT.md, "Finder"): the note's field (F1, F4), who writes it (F2), radio first (F3), which check-in
/// counts (F5), sending again after a restart (F6), and the bands (F7 to F12), against spec/test-vectors.txt
/// (`kind: finder-field`, `finder-fit`, `finder-bands`, made by spec/make-reply-vectors.py). Android checks the same.
final class FinderTests: XCTestCase {
    private func of(_ kind: String) -> [[String: String]] { VectorsTests.vectors.filter { $0["kind"] == kind } }
    let code = "test-group-finder"
    lazy var group = GroupCrypto(code: code)
    let sig = Data(repeating: 7, count: 64)
    let anna = "1111111111111111", marco = "2222222222222222"
    let t0: Int64 = 1_794_000_000_000, minute: Int64 = 60_000, hour: Int64 = 3_600_000

    func testVectorCounts() {
        XCTAssertEqual(of("finder-field").count, 13); XCTAssertEqual(of("finder-fit").count, 6); XCTAssertEqual(of("finder-bands").count, 8)
    }

    // MARK: - The field

    /// `kind: finder-field`: what a reader takes, sealed for real so the field goes the whole way through a packet.
    func testReadingTheField() throws {
        for (n, v) in of("finder-field").enumerated() {
            let field = v["field"] == "-" ? nil : Hex.decode(v["field"]!), text = v["text"]!, id = String(format: "e6080000000001%02x", n)
            let c = try group.seal(id: id, ts: t0, sender: marco, name: "Marco", text: text, fifth: field) { _ in self.sig }
            let m = try XCTUnwrap(group.openSigned(Packet(id: id, ts: t0, ttl: 16, cipher: c))).message
            XCTAssertEqual(m.text, text)
            // What the message carries; then what People take from it: a checked group message only.
            XCTAssertEqual(m.finder, FinderNote.read(field: field, text: text))
            var shown = Message(id: id, ts: t0, sender: marco, name: "Marco", text: text, isPublic: v["channel"] == "public", group: v["channel"] == "public" ? nil : code)
            shown.verified = v["checked"] == "yes"; shown.finder = m.finder
            let p = Person.all(in: [shown], me: anna).first { $0.id == marco }
            XCTAssertEqual(p?.finder ?? "ignored", v["reads"], "\(v)")
            XCTAssertNil(m.about); XCTAssertNil(m.notice, "a finder note is neither a reply nor a notice")
        }
    }

    func testTheWritersField() {
        XCTAssertEqual(FinderNote.field(linkId: "a1b2c3d4e5f6").map(Hex.encode), "06a1b2c3d4e5f6")
        for bad in ["A1B2C3D4E5F6", "a1b2c3d4e5", "a1b2c3d4e5f6a7", "g1b2c3d4e5f6", ""] { XCTAssertNil(FinderNote.field(linkId: bad), bad) }
        XCTAssertEqual(FinderNote.cost, 11)
    }

    /// F2: only own "needs help" check-ins in a group, with the switch on and high-risk mode off.
    func testWhoWritesIt() {
        let help = CheckIn.help.text(battery: 15)
        XCTAssertTrue(FinderNote.writes(text: help, isPublic: false, on: true, highRisk: false))
        XCTAssertFalse(FinderNote.writes(text: help, isPublic: true, on: true, highRisk: false), "never on the Public channel")
        XCTAssertFalse(FinderNote.writes(text: help, isPublic: false, on: false, highRisk: false), "the switch is off")
        XCTAssertFalse(FinderNote.writes(text: help, isPublic: false, on: true, highRisk: true), "high-risk mode, whatever the switch says")
        for text in [CheckIn.safe.text(battery: 15), "👤 Grandma needs help", "❓ Marco, are you safe?", "I'm with Marco"] {
            XCTAssertFalse(FinderNote.writes(text: text, isPublic: false, on: true, highRisk: false), text)
        }
    }

    /// `kind: finder-fit`: the sizes are the real packet's, checked by sealing it both ways.
    func testRadioFirst() throws {
        for (n, v) in of("finder-fit").enumerated() {
            let keyed = v["keyed"] == "yes", id = String(format: "e6080000000002%02x", n)
            XCTAssertEqual(FinderNote.fits(sender: v["sender"]!, name: v["name"]!, text: v["text"]!, keyed: keyed) ? "yes" : "no", v["note"], "\(v)")
            // The size without the note is what sealing gives before padding, and the note adds 11 bytes.
            let body = try GroupCrypto.fields(sender: v["sender"]!, name: v["name"]!, text: v["text"]!, photo: nil, extra: nil, about: nil)
            XCTAssertEqual(Packet.overhead + (keyed ? 3 : 0) + body.count, Int(v["size"]!), "\(v)")
            let withNote = try GroupCrypto.fields(sender: v["sender"]!, name: v["name"]!, text: v["text"]!, photo: nil, extra: nil, about: nil, fifth: FinderNote.field(linkId: "a1b2c3d4e5f6"))
            XCTAssertEqual(withNote.count - body.count, FinderNote.cost)
            if !keyed { // the epoch-key seal pads to the class: 163 exactly when it fits
                let c = try group.seal(id: id, ts: t0, sender: v["sender"]!, name: v["name"]!, text: v["text"]!, fifth: v["note"] == "yes" ? FinderNote.field(linkId: "a1b2c3d4e5f6") : nil) { _ in self.sig }
                let size = Packet(id: id, ts: t0, ttl: 16, cipher: c).encode().count
                if Int(v["size"]!)! <= 163 { XCTAssertEqual(size, 163, "still fits the radio: \(v)") }
            }
        }
    }

    /// F5: a later "I'm safe", or a later "I need help" without a note, ends it; an older one changes nothing.
    func testTheLatestCheckInCounts() {
        func msg(_ n: Int, _ text: String, finder: String?, verified: Bool = true) -> Message {
            var m = Message(id: "m\(n)", ts: t0 + Int64(n) * minute, sender: marco, name: "Marco", text: text, group: code)
            m.verified = verified; m.finder = finder
            return m
        }
        let note = "a1b2c3d4e5f6", help = CheckIn.help.text(battery: 15), safe = CheckIn.safe.text(battery: 15)
        func finder(_ ms: [Message]) -> String? { Person.all(in: ms, me: anna).first { $0.id == marco }?.finder }
        XCTAssertEqual(finder([msg(1, help, finder: note)]), note)
        XCTAssertNil(finder([msg(1, help, finder: note), msg(2, safe, finder: nil)]), "I'm safe ends it")
        XCTAssertNil(finder([msg(1, help, finder: note), msg(2, help, finder: nil)]), "a newer help without a note ends it")
        XCTAssertEqual(finder([msg(2, help, finder: note), msg(1, safe, finder: nil)]), note, "an older check-in that arrives late changes nothing")
        XCTAssertEqual(finder([msg(1, help, finder: "000000000001"), msg(2, help, finder: note)]), note, "the newest note")
        XCTAssertEqual(finder([msg(1, help, finder: note), msg(2, "on my way", finder: nil)]), note, "an ordinary message is no check-in")
        XCTAssertNil(finder([msg(1, help, finder: note, verified: false)]), "not checked")
    }

    /// F6: once after a restart, for a help under a day old and not followed by "I'm safe", at most every 10 minutes.
    func testSendingAgainAfterARestart() {
        let now = t0 + 2 * hour
        XCTAssertTrue(FinderNote.resends(lastOwn: (.help, t0), lastResend: nil, now: now, on: true, highRisk: false))
        XCTAssertFalse(FinderNote.resends(lastOwn: (.safe, t0), lastResend: nil, now: now, on: true, highRisk: false), "followed by I'm safe")
        XCTAssertFalse(FinderNote.resends(lastOwn: nil, lastResend: nil, now: now, on: true, highRisk: false))
        XCTAssertFalse(FinderNote.resends(lastOwn: (.help, now - 24 * hour), lastResend: nil, now: now, on: true, highRisk: false), "a day old")
        XCTAssertTrue(FinderNote.resends(lastOwn: (.help, now - 24 * hour + 1), lastResend: nil, now: now, on: true, highRisk: false))
        XCTAssertFalse(FinderNote.resends(lastOwn: (.help, t0), lastResend: now - 10 * minute + 1, now: now, on: true, highRisk: false), "within 10 minutes")
        XCTAssertTrue(FinderNote.resends(lastOwn: (.help, t0), lastResend: now - 10 * minute, now: now, on: true, highRisk: false))
        XCTAssertFalse(FinderNote.resends(lastOwn: (.help, t0), lastResend: nil, now: now, on: false, highRisk: false), "switch off")
        XCTAssertFalse(FinderNote.resends(lastOwn: (.help, t0), lastResend: nil, now: now, on: true, highRisk: true), "high-risk mode")
        XCTAssertFalse(FinderNote.resends(lastOwn: (.help, now + minute), lastResend: nil, now: now, on: true, highRisk: false), "dated ahead of this phone's clock")
    }

    // MARK: - Bands

    /// `kind: finder-bands`: every second of every trace, band and trend.
    func testBandsAgainstTheVectors() {
        for v in of("finder-bands") {
            let trace = v["rssi"]!.split(separator: " ").map { $0 == "-" ? nil : Int($0)! }
            let got = FinderMeter.run(trace)
            XCTAssertEqual(got.map { String($0.band.code) }.joined(separator: " "), v["bands"], v["rssi"]!)
            XCTAssertEqual(got.map { $0.trend.map { String($0.code) } ?? "." }.joined(separator: " "), v["trends"], v["rssi"]!)
        }
    }

    func testBandWordsNeverSayADistance() {
        for b in FinderBand.allCases {
            for s in [b.word, b.hint("Marco")] {
                XCTAssertNil(s.range(of: #"\d"#, options: .regularExpression), s)
                for unit in [" m ", "metre", "meter", "feet", " ft"] { XCTAssertFalse(s.lowercased().contains(unit), s) }
            }
        }
        XCTAssertEqual(FinderBand.allCases.map(\.word), ["Not in reach", "Far", "Near", "Very close", "Here"])
        XCTAssertEqual([FinderTrend.stronger, .weaker, .steady].map(\.words), ["Signal getting stronger", "Signal getting weaker", "Signal steady"])
    }

    func testReadingsOutsideTheRangeAreNone() {
        for v in [127, 0, -10, -19, -111, -200] { XCTAssertFalse(FinderMeter.counts(v), "\(v)") }
        for v in [-20, -60, -110] { XCTAssertTrue(FinderMeter.counts(v), "\(v)") }
        XCTAssertEqual(FinderMeter.run([127, 0, -115]).map(\.band), [.notInReach, .notInReach, .notInReach])
        XCTAssertNil(FinderMeter.run([-80, nil])[1].trend)
        XCTAssertEqual(FinderMeter.run([-80])[0].smoothed, -80)
    }
}
