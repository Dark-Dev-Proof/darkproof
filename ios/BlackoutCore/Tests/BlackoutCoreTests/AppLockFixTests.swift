import XCTest
@testable import BlackoutCore

final class AppLockFixTests: XCTestCase {
    // "Lock DarkProof": coming back.

    func testAsksOnFirstOpenAndAfterAMinute() {
        XCTAssertTrue(LockGrace.asksAgain(unlockedOnce: false, awaySeconds: nil))
        XCTAssertTrue(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: 60))
        XCTAssertTrue(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: 3_600))
    }

    func testNoAskWithinTheMinute() {
        XCTAssertFalse(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: nil))
        XCTAssertFalse(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: 0))
        XCTAssertFalse(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: 59.9))
    }

    /// The claim: a clock set back made the time away negative, and the app opened without asking.
    func testATimeAwayBelowZeroAsksAgain() {
        XCTAssertTrue(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: -3_600))
        XCTAssertTrue(LockGrace.asksAgain(unlockedOnce: true, awaySeconds: -0.001))
    }

    // Answers under an "Are you safe?" alert.

    private let saved = ["tagNew": "NEW-CODE", "tagInUse": "IN-USE"]

    func testAnswerGoesToTheSavedGroupThatAsked() {
        XCTAssertEqual(AnswerGroup.of(tag: "tagNew", saved: saved, moves: []), .group("NEW-CODE"))
        XCTAssertEqual(AnswerGroup.of(tag: "", saved: saved, moves: []), .group(nil))
        XCTAssertEqual(AnswerGroup.of(tag: nil, saved: saved, moves: []), .group(nil))
    }

    func testAnswerFollowsTheAskingGroupToItsNewCode() {
        var moves = AnswerGroup.noting(from: "tagOld", to: "tagMid", in: [])
        moves = AnswerGroup.noting(from: "tagMid", to: "tagNew", in: moves) // moved twice
        XCTAssertEqual(AnswerGroup.of(tag: "tagOld", saved: saved, moves: moves), .group("NEW-CODE"))
        XCTAssertEqual(AnswerGroup.of(tag: "tagMid", saved: saved, moves: moves), .group("NEW-CODE"))
    }

    func testAnswerForAGroupNoLongerSavedGoesNowhere() {
        XCTAssertEqual(AnswerGroup.of(tag: "tagUnknown", saved: saved, moves: []), .gone)
        let moves = AnswerGroup.noting(from: "tagLeft", to: "tagGone", in: [])
        XCTAssertEqual(AnswerGroup.of(tag: "tagLeft", saved: saved, moves: moves), .gone)
        let loop = AnswerGroup.noting(from: "tagB", to: "tagA", in: AnswerGroup.noting(from: "tagA", to: "tagB", in: []))
        XCTAssertEqual(AnswerGroup.of(tag: "tagA", saved: saved, moves: loop), .gone) // never loops
    }

    func testMovesAreKeptWithinTheirLimit() {
        var moves: [String] = []
        for i in 0..<(AnswerGroup.maxMoves + 50) { moves = AnswerGroup.noting(from: "t\(i)", to: "t\(i + 1)", in: moves) }
        XCTAssertEqual(moves.count, AnswerGroup.maxMoves)
        XCTAssertEqual(AnswerGroup.noting(from: "a", to: "c", in: ["a>b"]), ["a>c"])
    }

    // Answers given without opening DarkProof (fix round 2026-11-23, must-fix 1 and 2).

    /// Must-fix 1: "I'm safe" from Siri, the Action button or Shortcuts asks for the phone's unlock (it stops the clock
    /// everywhere); "I need help" stays possible from a locked phone, by design.
    func testOnlyImSafeNeedsTheUnlock() {
        XCTAssertTrue(SafeAnswer.needsUnlock(.safe))
        XCTAssertFalse(SafeAnswer.needsUnlock(.help))
    }

    /// Must-fix 2: an answer from an alert keeps the app running until a link took it, or 20 seconds.
    func testAnAnswerWaitsUntilItLeftOrTwentySeconds() {
        XCTAssertNil(SafeAnswer.outcome(handedOff: false, elapsed: 0), "keep waiting")
        XCTAssertNil(SafeAnswer.outcome(handedOff: false, elapsed: 19.9), "keep waiting")
        XCTAssertEqual(SafeAnswer.outcome(handedOff: true, elapsed: 0.5), .left)
        XCTAssertEqual(SafeAnswer.outcome(handedOff: true, elapsed: 25), .left, "taken counts whenever it is seen")
        XCTAssertEqual(SafeAnswer.outcome(handedOff: false, elapsed: 20), .waiting)
        XCTAssertEqual(SafeAnswer.waitSeconds, 20)
    }

    /// What the user is told: nothing once it left; else that it waits, or wasn't sent. Never that the group got it.
    func testWhatAnAnswerThatDidntLeaveSays() {
        XCTAssertNil(SafeAnswer.line(.left, .safe))
        XCTAssertEqual(SafeAnswer.line(.waiting, .safe), "“I'm safe” is waiting on this phone. Open DarkProof so it can go.")
        XCTAssertEqual(SafeAnswer.line(.notSent, .safe), "“I'm safe” wasn't sent. Open DarkProof to send it.")
        XCTAssertEqual(SafeAnswer.line(.waiting, .help), "“I need help” is waiting on this phone. Open DarkProof so it can go.")
        for o in [SafeAnswer.Outcome.waiting, .notSent] {
            for c in [CheckIn.safe, .help] {
                let s = SafeAnswer.line(o, c)!.lowercased()
                for bad in QuietWords.never + ["sent to", "was sent"] { XCTAssertFalse(s.contains(bad), "\(s) says \(bad)") }
            }
        }
    }
}
