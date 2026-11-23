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

    /// F2: only own "needs help" check-ins in a group, with the trial switch on, the switch on and high-risk mode off.
    func testWhoWritesIt() {
        let help = CheckIn.help.text(battery: 15)
        XCTAssertTrue(FinderNote.writes(text: help, isPublic: false, on: true, highRisk: false, trial: true))
        XCTAssertFalse(FinderNote.writes(text: help, isPublic: true, on: true, highRisk: false, trial: true), "never on the Public channel")
        XCTAssertFalse(FinderNote.writes(text: help, isPublic: false, on: false, highRisk: false, trial: true), "the switch is off")
        XCTAssertFalse(FinderNote.writes(text: help, isPublic: false, on: true, highRisk: true, trial: true), "high-risk mode, whatever the switch says")
        for text in [CheckIn.safe.text(battery: 15), "👤 Grandma needs help", "❓ Marco, are you safe?", "I'm with Marco"] {
            XCTAssertFalse(FinderNote.writes(text: text, isPublic: false, on: true, highRisk: false, trial: true), text)
        }
    }

    /// Decision B (fix round 2026-11-23): while the trial switch is off, as in release builds, "I need help" carries no
    /// link id, whatever the settings switch says (it is hidden then): nobody can use the note, so nobody gives it away.
    func testNoNoteWhileTheTrialIsOff() {
        let help = CheckIn.help.text(battery: 15)
        for on in [true, false] {
            XCTAssertFalse(FinderNote.writes(text: help, isPublic: false, on: on, highRisk: false, trial: false), "settings switch \(on)")
        }
        // Reading is unchanged: a note another phone wrote is still taken.
        XCTAssertEqual(FinderNote.read(field: FinderNote.field(linkId: "a1b2c3d4e5f6"), text: help), "a1b2c3d4e5f6")
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
        func resends(_ own: (kind: CheckIn, ts: Int64)?, last: Int64? = nil, on: Bool = true, highRisk: Bool = false) -> Bool {
            FinderNote.resends(lastOwn: own, lastResend: last, now: now, on: on, highRisk: highRisk, trial: true, ownClock: nil)
        }
        XCTAssertTrue(resends((.help, t0)))
        XCTAssertFalse(resends((.safe, t0)), "followed by I'm safe")
        XCTAssertFalse(resends(nil))
        XCTAssertFalse(resends((.help, now - 24 * hour)), "a day old")
        XCTAssertTrue(resends((.help, now - 24 * hour + 1)))
        XCTAssertFalse(resends((.help, t0), last: now - 10 * minute + 1), "within 10 minutes")
        XCTAssertTrue(resends((.help, t0), last: now - 10 * minute))
        XCTAssertFalse(resends((.help, t0), on: false), "switch off")
        XCTAssertFalse(resends((.help, t0), highRisk: true), "high-risk mode")
        XCTAssertFalse(resends((.help, now + minute)), "dated ahead of this phone's clock")
    }

    /// Decision B: no re-send while the trial switch is off (release builds): it would carry no note, and only alert again.
    func testNoResendWhileTheTrialIsOff() {
        XCTAssertFalse(FinderNote.resends(lastOwn: (.help, t0), lastResend: nil, now: t0 + hour, on: true, highRisk: false, trial: false, ownClock: nil))
    }

    /// Item 3 (fix round 2026-11-23): Anna asks for help at 10:00, starts an 8-hour clock at 10:30; the app restarts at
    /// 11:00. A re-sent "I need help" would be dated 11:00, after the clock message, and cancel her clock on every phone
    /// (rule Q4), which her own help call didn't. So no re-send while a clock message of her own as new as the help
    /// call is held, a start or a stop; an older clock (one the help call already cancelled) changes nothing.
    func testTheResendNeverCancelsANewerOwnClock() {
        let help = t0, clock = t0 + 30 * minute, restart = t0 + hour
        // What the re-send would do to the clock: cancel it, where the help call didn't.
        var book = QuietBook()
        book.take(member: anna, id: "c1", mark: "m1", ts: clock, deadline: clock + 8 * hour, spot: nil, now: clock, highRisk: false)
        book.checkIn(member: anna, ts: help, now: clock, highRisk: false)
        XCTAssertTrue(book.armed(anna), "the help call came first: the clock runs")
        var resent = book
        resent.checkIn(member: anna, ts: restart, now: restart, highRisk: false)
        XCTAssertFalse(resent.armed(anna), "a re-send dated after the clock message would stop it")
        // So it isn't sent.
        func resends(ownClock: Int64?) -> Bool {
            FinderNote.resends(lastOwn: (.help, help), lastResend: nil, now: restart, on: true, highRisk: false, trial: true, ownClock: ownClock)
        }
        XCTAssertFalse(resends(ownClock: book.clock(anna)?.ts), "a newer own clock is held")
        XCTAssertFalse(resends(ownClock: help), "a clock message as old as the help call: Q4 needs a greater ts, so the help call didn't cancel it")
        XCTAssertFalse(resends(ownClock: clock + minute), "a stop is a clock message too")
        XCTAssertTrue(resends(ownClock: help - minute), "a clock from before the help call: the help call cancelled it already")
        XCTAssertTrue(resends(ownClock: nil), "no own clock held")
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
