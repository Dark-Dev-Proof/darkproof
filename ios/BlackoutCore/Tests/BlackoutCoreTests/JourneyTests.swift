import XCTest
@testable import BlackoutCore

/// Journey notes in receipts and "How it got there" (spec/FORMAT.md, "Journey notes"): what is written, what is
/// read, and that a reader which doesn't know notes takes the same ids from a receipt as before.
final class JourneyTests: XCTestCase {
    let a = "0123456789abcdef", b = "89abcdef01234567", c = "fedcba9876543210"

    /// The rule of "Delivery receipts" as it was before journey notes, copied here so it can't change with the code:
    /// the first 200 tokens that are 16 lowercase hex characters; everything else is skipped.
    private func oldIds(_ text: String) -> [String] {
        Array(text.utf8.split(separator: 32).lazy.filter { id in
            id.count == 16 && id.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
        }.prefix(200).map { String(decoding: $0, as: UTF8.self) })
    }

    private func note(_ s: String) -> Journey.Note? { Journey.Note(token: Array(s.utf8)) }

    // MARK: - The token

    func testNotesParse() {
        XCTAssertEqual(note("h3i").map { [$0.hops] }, [3]); XCTAssertEqual(note("h3i")?.link, .internet)
        XCTAssertEqual(note("h1b")?.link, .bluetooth); XCTAssertEqual(note("h1w")?.link, .wifi)
        XCTAssertEqual(note("h1p")?.link, .direct); XCTAssertEqual(note("h1r")?.link, .radio)
        XCTAssertEqual(note("h0b")?.hops, 0); XCTAssertEqual(note("h16r")?.hops, 16)
        XCTAssertEqual(note("h07w")?.hops, 7, "a leading zero is read"); XCTAssertEqual(note("h00b")?.hops, 0)
        // A letter this app doesn't know: another link, the hops still read.
        XCTAssertEqual(note("h2z")?.link, .other); XCTAssertEqual(note("h2z")?.hops, 2); XCTAssertEqual(note("h9a")?.link, .other)
    }

    func testWhatIsNoNote() {
        for bad in ["", "h", "hb", "h3", "3i", "h17b", "h99b", "h016b", "h003b", "h3B", "H3b", "h3ii", "h-1b", "h+1b", "h 1b", "h1.b", "h１b", "h3é", "x3b", "h3b ", "hh3b", "h3\u{0}"] {
            XCTAssertNil(note(bad), bad)
        }
    }

    /// 16 minus the ttl of the first copy, not below 0; a ttl above 16 counts as 16.
    func testWhatAReaderWrites() {
        func written(_ ttl: Int, _ link: Journey.Link) -> String? { Journey.Note(ttl: ttl, link: link)?.token }
        XCTAssertEqual(written(15, .bluetooth), "h1b"); XCTAssertEqual(written(16, .wifi), "h0w"); XCTAssertEqual(written(13, .internet), "h3i")
        XCTAssertEqual(written(1, .radio), "h15r"); XCTAssertEqual(written(0, .direct), "h16p")
        XCTAssertEqual(written(40, .internet), "h0i"); XCTAssertEqual(written(64, .bluetooth), "h0b")
        XCTAssertNil(written(15, .other), "a link this app doesn't know is never written")
        // What is written is read back the same.
        for ttl in 0...16 { for l in [Journey.Link.bluetooth, .wifi, .direct, .radio, .internet] {
            let n = Journey.Note(ttl: ttl, link: l)!
            XCTAssertEqual(note(n.token!), n)
        } }
    }

    func testWording() {
        XCTAssertEqual(Journey.Link.bluetooth.over, "Over Bluetooth"); XCTAssertEqual(Journey.Link.wifi.over, "Over a Wi-Fi network")
        XCTAssertEqual(Journey.Link.direct.over, "Over phone-to-phone Wi-Fi"); XCTAssertEqual(Journey.Link.radio.over, "Over the radio")
        XCTAssertEqual(Journey.Link.internet.over, "Over the internet"); XCTAssertEqual(Journey.Link.other.over, "Over another link")
        XCTAssertEqual(note("h3i")?.text, "Over the internet · about 3 hops")
        XCTAssertEqual(note("h1b")?.text, "Over Bluetooth · about 1 hop")
        XCTAssertEqual(note("h0w")?.text, "Over a Wi-Fi network")
        XCTAssertEqual(Journey.later(0), "Less than a minute later"); XCTAssertEqual(Journey.later(59_999), "Less than a minute later")
        XCTAssertEqual(Journey.later(60_000), "1 min later"); XCTAssertEqual(Journey.later(4 * 60_000 + 5), "4 min later"); XCTAssertEqual(Journey.later(59 * 60_000), "59 min later")
        XCTAssertEqual(Journey.later(3 * 3_600_000), "3 h later"); XCTAssertEqual(Journey.later(72 * 3_600_000), "3 days later")
        XCTAssertNil(Journey.later(-1), "the reader's clock is behind the writer's: nothing is said")
    }

    // MARK: - Receipts

    func testEntriesReadNotesRightAfterAnId() {
        func read(_ t: String) -> [String] { Receipt.entries(in: t).map { String($0.id.prefix(2)) + ($0.note.map { ":\($0.hops)" } ?? "") } }
        XCTAssertEqual(read("\(a) h3i \(b) h1b"), ["01:3", "89:1"])
        XCTAssertEqual(read("\(a) \(b) h16r \(c)"), ["01", "89:16", "fe"])
        XCTAssertEqual(read("h1b \(a) h2b h3b \(b) x h4b \(c) h99x h5b"), ["01:2", "89", "fe"], "only the token right after an id")
        XCTAssertEqual(read("\(a)  h3i   \(b)"), ["01:3", "89"], "more than one space")
        XCTAssertEqual(read("\(a) h17b \(b) h3B \(c) h2z"), ["01", "89", "fe:2"])
        XCTAssertEqual(Receipt.entries(in: "\(a) h2z").first?.note?.link, .other)
        XCTAssertEqual(read(""), []); XCTAssertEqual(read("h3i"), [])
    }

    /// A reader that doesn't know notes skips them: it takes exactly the ids it took before, in the same order.
    func testAReaderWithoutNotesTakesTheSameIds() {
        let many = (1...202).map { String(format: "%016x", $0) + " h\($0 % 17)" + ["b", "w", "p", "r", "i"][$0 % 5] }.joined(separator: " ")
        let texts = ["\(a) h3i \(b) h1b", "\(a) \(b)", "", "h1b", "\(a) h17b \(b) h016b", "h1b \(a) h2b h3b \(b) x h4b \(c) h99x h5b", "\(a)  h3i   \(b)",
                     "0123456789ABCDEF short 0123456789abcdeg  \(c) h2z", many, "\(a) h1b \(a) h9i"]
        for t in texts {
            XCTAssertEqual(oldIds(t), Receipt.ids(in: t), "this app's id-only reader is still the old rule")
            XCTAssertEqual(Receipt.entries(in: t).map(\.id), oldIds(t), "the new reader confirms the same messages")
        }
        XCTAssertEqual(oldIds("\(a) h3i \(b) h1b"), [a, b])
        XCTAssertEqual(oldIds(many).count, 200)
    }

    func testNotesDoNotCountTowardsThe200() {
        let ids = (1...202).map { String(format: "%016x", $0) }
        let text = ids.map { "\($0) h4i" }.joined(separator: " ")
        let e = Receipt.entries(in: text)
        XCTAssertEqual(e.count, 200); XCTAssertEqual(e.map(\.id), Array(ids.prefix(200)))
        XCTAssertEqual(e.last?.note?.hops, 4, "the note after the 200th id is its note")
        XCTAssertTrue(e.allSatisfy { $0.note != nil })
    }

    func testWritingAReceipt() {
        let entries = [Receipt.Entry(id: a, note: note("h3i")), Receipt.Entry(id: b), Receipt.Entry(id: c, note: note("h1b"))]
        XCTAssertEqual(Receipt.text(for: entries, highRisk: false), "\(a) h3i \(b) \(c) h1b")
        XCTAssertEqual(Receipt.entries(in: Receipt.text(for: entries, highRisk: false)), entries)
        // High-risk mode: no notes, the receipt is exactly what it was before notes existed.
        XCTAssertEqual(Receipt.text(for: entries, highRisk: true), Receipt.text(for: [a, b, c]))
        XCTAssertEqual(Receipt.text(for: entries, highRisk: true), "\(a) \(b) \(c)")
        // A link this app doesn't write leaves no token behind.
        XCTAssertEqual(Receipt.text(for: [Receipt.Entry(id: a, note: note("h2z"))], highRisk: false), a)
        let many = (0..<450).map { Receipt.Entry(id: String(format: "%016x", $0), note: note("h1b")) }
        XCTAssertEqual(Receipt.batches(of: many).map(\.count), [200, 200, 50])
        XCTAssertEqual(Receipt.batches(of: many).flatMap { $0 }, many)
        for batch in Receipt.batches(of: many) { XCTAssertEqual(Receipt.entries(in: Receipt.text(for: batch, highRisk: false)), batch) }
    }

    func testHighRiskSettingIsOffUnlessSwitchedOn() {
        let d = UserDefaults(suiteName: "journey-tests-\(UUID().uuidString)")!
        XCTAssertFalse(HighRiskSetting.isOn(d))
        d.set(true, forKey: HighRiskSetting.key)
        XCTAssertTrue(HighRiskSetting.isOn(d))
    }

    func testDeliveriesKeepTheNoteOfTheEarliestReceipt() {
        func receipt(_ sender: String, _ name: String, _ ts: Int64, _ text: String) -> Message { Message(id: "r\(sender)\(ts)", ts: ts, sender: sender, name: name, text: text) }
        var d = Deliveries()
        d.sent(group: "g", a); d.sent(group: "g", b)
        d.add(group: "g", receipt("sara", "Sara", 300, "\(a) h3i \(b)"))
        d.add(group: "g", receipt("marco", "Marco", 200, "\(a) h1b"))
        d.add(group: "g", receipt("sara", "Sara", 400, "\(a) h9r"))          // later: not counted
        d.add(group: "g", receipt("lucia", "Lucia", 250, "\(a) h1b \(a) h9i")) // named twice: as named first
        d.add(group: "g", receipt("old", "Old app", 500, a))                 // a reader that writes no notes
        d.add(group: "other", receipt("eve", "Eve", 100, "\(a) h1b"))        // another group's receipt key
        XCTAssertEqual(d.readers[a]?.map(\.id), ["marco", "lucia", "sara", "old"])
        XCTAssertEqual(d.readers[a]?.map { $0.note?.hops }, [1, 1, 3, nil])
        XCTAssertEqual(d.readers[a]?[2].note?.link, .internet)
        XCTAssertEqual(d.readers[b]?.map(\.id), ["sara"]); XCTAssertNil(d.readers[b]?.first?.note)
        XCTAssertNil(d.readers[c], "no message of this phone's")
    }

    // MARK: - The screen

    func testNotLeftYet() {
        let s = Journey.steps(written: 1_000, handedOff: false, left: nil, readers: [], now: 5_000)
        XCTAssertEqual(s.map(\.title), ["Saved on this phone"])
        XCTAssertEqual(s[0].detail, "No phone is in reach yet. It leaves by itself when one is."); XCTAssertEqual(s[0].kind, .saved)
        // Packets are passed on for 7 days: after that the screen no longer says it will leave.
        let late = Journey.steps(written: 1_000, handedOff: false, left: nil, readers: [], now: 1_000 + 8 * 24 * 3_600_000)
        XCTAssertEqual(late[0].detail, "No phone was in reach for 7 days. It is no longer passed on.")
    }

    func testHandedOnButNotConfirmed() {
        let s = Journey.steps(written: 1_000, handedOff: true, left: Journey.Left(link: .bluetooth, at: 2_000), readers: [], now: 5_000)
        XCTAssertEqual(s.map(\.title), ["Left your phone", "Not confirmed by anyone yet"])
        XCTAssertEqual(s[0].detail, "Over Bluetooth"); XCTAssertEqual(s[0].at, 2_000)
        // Handed on before this was noted: it left, and nothing is said about how.
        let old = Journey.steps(written: 1_000, handedOff: true, left: nil, readers: [], now: 5_000)
        XCTAssertEqual(old[0].title, "Left your phone"); XCTAssertNil(old[0].detail); XCTAssertNil(old[0].at)
    }

    func testReachedReaders() {
        let readers = [Reader(id: "m", name: "Marco", ts: 41_000, note: note("h1b")), Reader(id: "s", name: "Sara", ts: 241_000, note: note("h3i")), Reader(id: "o", name: "", ts: 500_000)]
        let s = Journey.steps(written: 1_000, handedOff: true, left: Journey.Left(link: .internet, at: 1_500), readers: readers, now: 600_000)
        XCTAssertEqual(s.map(\.title), ["Left your phone", "Reached Marco", "Reached Sara", "Reached someone"])
        XCTAssertEqual(s.map(\.detail), ["Over the internet", "Over Bluetooth · about 1 hop", "Over the internet · about 3 hops", nil])
        XCTAssertEqual(s.map(\.later), [nil, "Less than a minute later", "4 min later", "8 min later"])
        XCTAssertEqual(s[3].at, 500_000, "without a note the screen shows the time of the receipt")
        XCTAssertTrue(Journey.wasCarried(readers), "Sara's note says 3 hops")
        XCTAssertFalse(Journey.wasCarried([readers[0], readers[2]]), "1 hop, and no note: nothing says other phones carried it")
        XCTAssertFalse(Journey.wasCarried([]))
        // Confirmed, though this phone never noted handing it on (its list of those is limited): it left.
        XCTAssertEqual(Journey.steps(written: 1_000, handedOff: false, left: nil, readers: [readers[0]], now: 600_000).map(\.title), ["Left your phone", "Reached Marco"])
        XCTAssertEqual(Journey.sealed, "Sealed the whole way")
        XCTAssertEqual(Journey.carried, "Carried by other phones on the way. They can't read it, and nobody sees whose they are.")
    }

    // MARK: - What the writer's phone remembers

    func testHowAMessageLeftIsSavedAsLetterAndTime() {
        for l in [Journey.Link.bluetooth, .wifi, .direct, .radio, .internet] {
            let left = Journey.Left(link: l, at: 1_793_807_238_631)
            XCTAssertEqual(Journey.Left(saved: left.saved!), left)
        }
        XCTAssertEqual(Journey.Left(link: .radio, at: 12).saved, "r12")
        XCTAssertNil(Journey.Left(link: .other, at: 12).saved)
        for bad in ["", "b", "12", "z12", "b-1", "B12", "b1x"] { XCTAssertNil(Journey.Left(saved: bad), bad) }
    }

    /// Leaving a group: what the phone noted about how that group's messages left goes with the rest.
    func testLeavingAGroupForgetsHowItsMessagesLeft() {
        let prefs: [String: Any] = ["handedOff": ["00000000000000a1", "00000000000000b1"], "handedOffOver": ["00000000000000a1": "b12", "00000000000000b1": "i13"]]
        let r = LeftGroup.preferences(prefs, group: "0123456789abcdef0123456789abcdef", tags: [], messages: ["00000000000000a1"])
        XCTAssertEqual(r.change["handedOff"] as? [String], ["00000000000000b1"])
        XCTAssertEqual(r.change["handedOffOver"] as? [String: String], ["00000000000000b1": "i13"])
    }
}
