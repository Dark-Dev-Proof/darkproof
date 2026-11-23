import XCTest
@testable import BlackoutCore

/// The iPhone's part of the last pass after the Fable re-check (briefs-2026-11-22/fable-recheck-fixes.md): Siri's
/// "I'm safe" within 10 s (N1), the key that is never replaced while it can't be read (N5), the toast while DarkProof is
/// open (N7), and the screens' findings 3 and 7.
final class RecheckFixTests: XCTestCase {

    // MARK: - N1: Siri answers within about 10 s in all

    func testSiriDoesntWaitForANewPosition() {
        XCTAssertTrue(SafeAnswer.waitsForFix(.alert), "an alert's answer still waits up to 10 s for a position")
        XCTAssertFalse(SafeAnswer.waitsForFix(.siri), "Siri takes a position under 2 minutes old, or none")
        XCTAssertEqual(SafeAnswer.siriSeconds, 10)
    }

    func testSiriWaitsForALinkOnlyForWhatIsLeftOfTenSeconds() {
        XCTAssertEqual(SafeAnswer.waitAfterWritten(.siri, askedAgo: 0), 10)
        XCTAssertEqual(SafeAnswer.waitAfterWritten(.siri, askedAgo: 0.25), 9.75, accuracy: 1e-9)
        XCTAssertEqual(SafeAnswer.waitAfterWritten(.siri, askedAgo: 10), 0)
        XCTAssertEqual(SafeAnswer.waitAfterWritten(.siri, askedAgo: 14), 0, "never below 0")
        XCTAssertEqual(SafeAnswer.waitAfterWritten(.siri, askedAgo: -3), 10, "a clock that went back gives no extra time")
        for ago in stride(from: 0.0, through: 9.5, by: 0.5) {
            XCTAssertEqual(ago + SafeAnswer.waitAfterWritten(.siri, askedAgo: ago), SafeAnswer.siriSeconds, accuracy: 1e-9, "10 s in all")
        }
        // The alert's answer is as before: 20 s once written, however long the position took.
        XCTAssertEqual(SafeAnswer.waitAfterWritten(.alert, askedAgo: 9.9), SafeAnswer.waitSeconds)
        XCTAssertEqual(SafeAnswer.waitSeconds, 20)
    }

    func testSiriEndsAsWaitingWhenItsTimeIsUp() {
        let limit = SafeAnswer.waitAfterWritten(.siri, askedAgo: 0.3)
        XCTAssertNil(SafeAnswer.outcome(handedOff: false, elapsed: limit - 0.01, limit: limit), "keep waiting")
        XCTAssertEqual(SafeAnswer.outcome(handedOff: false, elapsed: limit, limit: limit), .waiting)
        XCTAssertEqual(SafeAnswer.outcome(handedOff: true, elapsed: 0.1, limit: limit), .left)
        XCTAssertEqual(SafeAnswer.outcome(handedOff: false, elapsed: 0, limit: 0), .waiting, "no time left: it waits on this phone")
        XCTAssertNil(SafeAnswer.outcome(handedOff: false, elapsed: 19.9), "the default is still the alert's 20 s")
        // What Siri then says: the existing "waiting on this phone" words (it is written and will go).
        XCTAssertEqual(SafeAnswer.said(.waiting, .safe, group: "My group"), "“I'm safe” is waiting on this phone. Open DarkProof so it can go.")
    }

    // MARK: - N7: no "Open DarkProof" while DarkProof is open

    func testTheToastOnScreenDoesntSayOpenDarkProof() {
        XCTAssertEqual(SafeAnswer.line(.waiting, .safe, onScreen: true),
                       "“I'm safe” is waiting on this phone. It goes out when a phone or the internet is in reach.")
        XCTAssertEqual(SafeAnswer.line(.waiting, .help, onScreen: true),
                       "“I need help” is waiting on this phone. It goes out when a phone or the internet is in reach.")
        XCTAssertNil(SafeAnswer.line(.left, .help, onScreen: true))
        // The alert, with the app not on screen, keeps "Open DarkProof so it can go".
        XCTAssertEqual(SafeAnswer.line(.waiting, .help), "“I need help” is waiting on this phone. Open DarkProof so it can go.")
        XCTAssertEqual(SafeAnswer.line(.waiting, .help, onScreen: false), SafeAnswer.line(.waiting, .help))
        for c in [CheckIn.safe, .help] {
            let s = SafeAnswer.line(.waiting, c, onScreen: true)!.lowercased()
            XCTAssertFalse(s.contains("open darkproof"), s)
            for bad in QuietWords.never + ["sent to", "was sent"] { XCTAssertFalse(s.contains(bad), "\(s) says \(bad)") }
        }
    }

    // MARK: - N5: a key that can't be read right now is never replaced

    func testOnlyAMissingKeyIsMadeAnew() {
        let k = Data(repeating: 7, count: 32)
        XCTAssertEqual(OwnKey.use(.found(k)), .use(k))
        XCTAssertEqual(OwnKey.use(.missing), .make, "nothing saved yet: the first start makes it")
        XCTAssertEqual(OwnKey.use(.unreadable), .none, "saved but unreadable now: no key, nothing replaced")
        XCTAssertEqual(OwnKey.use(.found(Data(repeating: 7, count: 16))), .make, "one of another length never sealed anything")
    }

    func testAFileReadWithoutItsKeyIsNotWrittenOver() {
        var u = UnreadFiles()
        XCTAssertTrue(u.mayWrite("daybook.sealed", keyKnown: true))
        XCTAssertFalse(u.mayWrite("daybook.sealed", keyKnown: false), "no key: nothing is written")
        u.read("daybook.sealed", lost: true) // there, but read as empty: the key couldn't be read
        XCTAssertFalse(u.mayWrite("daybook.sealed", keyKnown: true), "the key came back, but the book in memory is empty")
        XCTAssertTrue(u.mayWrite("quiet.g1.sealed", keyKnown: true), "other files are not held back")
        u.read("daybook.sealed", lost: false) // read again with the key
        XCTAssertTrue(u.mayWrite("daybook.sealed", keyKnown: true))
        u.read("quiet.g1.sealed", lost: true)
        u.removed("quiet.g1.sealed") // its group was left: it goes whatever it holds
        XCTAssertTrue(u.mayWrite("quiet.g1.sealed", keyKnown: true))
        XCTAssertTrue(u.names.isEmpty)
    }

    /// What the guard protects: under a new key, the old file doesn't open and reads as nothing.
    func testANewKeyWouldLoseWhatTheOldOneSealed() {
        let old = Data(repeating: 1, count: 32), new = Data(repeating: 2, count: 32)
        let file = SealedFile.seal(Data("clock".utf8), key: old, label: "quiet.g1")!
        XCTAssertNil(SealedFile.load(sealed: file, plain: nil, key: new, label: "quiet.g1").data)
        XCTAssertEqual(SealedFile.load(sealed: file, plain: nil, key: old, label: "quiet.g1").data, Data("clock".utf8))
    }

    // MARK: - Screens 3: a new "I need help" starts the search again

    func testANewHelpStartsTheSearchAgain() {
        var p = Person(id: "b2b2b2b2b2b2b2b2", name: "Marco", lastTs: 1)
        p.checkIn = .help; p.finder = "0123456789ab"
        XCTAssertEqual(FinderWords.looksFor(p), "0123456789ab")
        var safe = p; safe.checkIn = .safe; safe.finder = nil
        XCTAssertNil(FinderWords.looksFor(safe), "\"I'm safe\" ends it")
        XCTAssertEqual(FinderWords.looksFor(p), "0123456789ab", "help again: searching again, the card isn't left saying it ended")
        var restarted = p; restarted.finder = "ba9876543210"
        XCTAssertEqual(FinderWords.looksFor(restarted), "ba9876543210", "a help from a restarted phone names its new link")
        var report = p; report.reportedBy = "Anna"
        XCTAssertNil(FinderWords.looksFor(report))
        var noNote = p; noNote.finder = nil
        XCTAssertNil(FinderWords.looksFor(noNote))
    }

    // MARK: - Screens 7: "Says less." names Find by Bluetooth only with the trial on

    func testSaysLessFollowsTheTrial() {
        XCTAssertEqual(HighRiskSetting.saysLess(finderTrial: false), "Receipts don't say how a message arrived.")
        XCTAssertEqual(HighRiskSetting.saysLess(finderTrial: true), "No Find by Bluetooth, and receipts don't say how a message arrived.")
        for t in [false, true] { XCTAssertFalse(HighRiskSetting.saysLess(finderTrial: t).contains("Finder"), "L3: \"Finder\" is retired") }
    }
}
