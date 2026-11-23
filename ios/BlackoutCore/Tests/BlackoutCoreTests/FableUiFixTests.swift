import XCTest
@testable import BlackoutCore

/// The iPhone screen fixes of the round after the Fable 5.2 review (briefs-2026-11-22/fable-fixes.md): the words the
/// screens use, and the small rules behind swipe-to-reply, the clock card, Finder and voice notes.
final class FableUiFixTests: XCTestCase {

    // MARK: - Decision A, must-fix 8: "Reached", never "Kept by"

    func testReachedSaysOnlyThatTheMessageGotThere() {
        XCTAssertEqual(QuietWords.reached([]), "Not confirmed by any phone yet. Until a phone has it, nobody can be alerted.")
        XCTAssertEqual(QuietWords.reached(["Marco"]), "Reached Marco's phone so far")
        XCTAssertEqual(QuietWords.reached(["Marco", "Anna"]), "Reached Marco's and Anna's phones so far")
        XCTAssertEqual(QuietWords.reached(["Marco", "Anna", "Ben", "Lia", "Sara"]), "Reached Marco's, Anna's and 3 more phones so far")
        for n in 0...5 {
            let s = QuietWords.reached(Array(["Marco", "Anna", "Ben", "Lia", "Sara"].prefix(n)))
            XCTAssertFalse(s.lowercased().contains("kept"), s)
        }
        XCTAssertEqual(QuietWords.olderOnly, "Phones on older DarkProof versions only show the message.")
    }

    // MARK: - M1: "can alert", not "tell their owners"

    func testTheClockWordsSayCanAlert() {
        XCTAssertEqual(QuietWords.lead, "If you don't check in on time, the phones that got your clock can alert their owners.")
        XCTAssertEqual(QuietWords.ranOut("18:30"), "Your clock ran out at 18:30. Phones that have it can alert their owners.")
        XCTAssertEqual(QuietWords.startedSub(nil, ends: "18:30"), "Ends 18:30. Phones that got it can alert their owners if you don't check in.")
        XCTAssertEqual(QuietWords.startedSub("Anna", ends: "18:30"), "If no check-in from Anna reaches this phone by 18:30, it tells you.")
        XCTAssertEqual(QuietWords.startedSub("Anna", ends: "18:30", alertsOn: false),
                       "If no check-in from Anna reaches this phone by 18:30, it shows here. Alerts are off for DarkProof.")
        XCTAssertEqual(QuietWords.subtitle, "A check-in clock")
        let all = [QuietWords.lead, QuietWords.ranOut("18:30"), QuietWords.startedSub(nil, ends: "18:30"), QuietWords.notKeeping,
                   QuietWords.startedSub("Anna", ends: "18:30", alertsOn: false), QuietWords.olderOnly]
            + (0...4).map { QuietWords.reached(Array(["Marco", "Anna", "Ben", "Lia"].prefix($0))) }
        for s in all {
            for bad in QuietWords.never { XCTAssertFalse(s.lowercased().contains(bad), "\(s) says \(bad)") }
            XCTAssertFalse(s.contains("tell their owners"), s)
        }
    }

    /// L3: the toasts say "in reach", as the rest of the app.
    func testTheClockToastsSayInReach() {
        for w in [true, false] {
            for s in [QuietWords.sent(waiting: w), QuietWords.added(waiting: w), QuietWords.safeSent(waiting: w), QuietWords.stopped(waiting: w)] {
                XCTAssertFalse(s.contains("in range"), s)
            }
        }
        XCTAssertEqual(QuietWords.sent(waiting: true), "The clock waits for a phone in reach")
    }

    // MARK: - Item 9: the card of a clock this phone doesn't keep promises nothing

    private let anna = "a1a1a1a1a1a1a1a1", t0: Int64 = 1_800_000_000_000, minute: Int64 = 60_000

    private func book(deadline: Int64) -> QuietBook {
        var b = QuietBook()
        XCTAssertTrue(b.take(member: anna, id: "00000000000000c1", mark: "m1", ts: t0, deadline: deadline, spot: nil, now: t0, highRisk: false))
        return b
    }

    func testTheCardPromisesOnlyForAClockThisPhoneKeeps() {
        let d = t0 + 60 * minute
        var b = book(deadline: d)
        XCTAssertTrue(QuietBook.keeps(card: d, verified: true, state: b.state(anna, now: t0 + minute)), "running, the same clock")
        XCTAssertTrue(QuietBook.keeps(card: d, verified: true, state: b.state(anna, now: d + minute)), "ran out, still alerting")
        XCTAssertFalse(QuietBook.keeps(card: d, verified: false, state: b.state(anna, now: t0 + minute)), "not checked")
        XCTAssertFalse(QuietBook.keeps(card: d, verified: true, state: nil), "never taken (rule Q1 failed), or gone")
        XCTAssertFalse(QuietBook.keeps(card: 0, verified: true, state: b.state(anna, now: t0 + minute)), "a stop")
        XCTAssertFalse(QuietBook.keeps(card: d, verified: true, state: b.state(anna, now: d + QuietClock.lateMs + minute)), "12 hours after: nothing more")

        // A newer clock of hers counts now (rule Q3): the old card isn't kept.
        let d2 = t0 + 120 * minute
        XCTAssertTrue(b.take(member: anna, id: "00000000000000c2", mark: "m2", ts: t0 + minute, deadline: d2, spot: nil, now: t0 + minute, highRisk: false))
        XCTAssertFalse(QuietBook.keeps(card: d, verified: true, state: b.state(anna, now: t0 + 2 * minute)))
        XCTAssertTrue(QuietBook.keeps(card: d2, verified: true, state: b.state(anna, now: t0 + 2 * minute)))

        // Her check-in cancels it (rule Q4): not kept, whatever its deadline.
        XCTAssertTrue(b.checkIn(member: anna, ts: t0 + 3 * minute, now: t0 + 3 * minute, highRisk: false))
        XCTAssertFalse(QuietBook.keeps(card: d2, verified: true, state: b.state(anna, now: t0 + 4 * minute)))
        XCTAssertFalse(b.armed(anna))
    }

    func testTheCardOfAClockNotKeptSaysSo() {
        XCTAssertEqual(QuietWords.notKeeping, "This phone isn't keeping this clock.")
        XCTAssertEqual(QuietWords.startedSub("Anna", ends: "18:30", keeping: false), "This phone isn't keeping this clock.")
        XCTAssertEqual(QuietWords.startedSub("Anna", ends: "18:30", keeping: false, alertsOn: false), "This phone isn't keeping this clock.")
        XCTAssertFalse(QuietWords.startedSub("Anna", ends: "18:30", keeping: false).contains("tells you"))
        // The own card is about the other phones, never this one.
        XCTAssertEqual(QuietWords.startedSub(nil, ends: "18:30", keeping: false), QuietWords.startedSub(nil, ends: "18:30"))
    }

    // MARK: - Decision D and M3: the timer

    func testTheTimerSheetSaysItAlsoDeletesHelpCalls() {
        XCTAssertEqual(GroupTimer.alsoHelp, "This includes \"I need help\" calls and the positions sent with them.")
    }

    func testTheTimerSpeaksForThisPhoneOnly() {
        XCTAssertEqual(GroupTimer.chatLine(3_600), "This phone deletes them 1 hour after they arrive. So do phones on the latest DarkProof.")
        XCTAssertEqual(GroupTimer.sheetLine(3_600), "This phone deletes messages 1 hour after they arrive. So do phones on the latest DarkProof.")
        for s in GroupTimer.choices {
            XCTAssertFalse(GroupTimer.chatLine(s).contains("reach each phone"))
            XCTAssertFalse(GroupTimer.sheetLine(s).contains("reach each phone"))
        }
        // L3: one way to write it on the screens.
        for s in [GroupTimer.chatLine(nil), GroupTimer.chatLine(0), GroupTimer.sheetLine(nil), GroupTimer.sheetLine(0),
                  GroupTimer.securityLine(nil, by: nil, highRisk: false), GroupTimer.securityLine(0, by: nil, highRisk: true)] {
            XCTAssertFalse(s.contains("one to two"), s)
            XCTAssertTrue(s.contains("1 to 2 weeks"), s)
        }
    }

    // MARK: - M4: the erase screen's headline

    func testTheEraseScreenNeverSaysEmptyWhenSomethingIsLeft() {
        XCTAssertEqual(LeaveWords.empty, "DarkProof is empty on this phone")
        XCTAssertEqual(LeaveWords.finalTitle(left: false), "DarkProof is empty on this phone")
        XCTAssertEqual(LeaveWords.finalTitle(left: true), "Not everything could be erased")
        XCTAssertEqual(LeaveWords.deleteRest, "Delete DarkProof from this phone to remove the rest.")
        XCTAssertFalse(LeaveWords.finalTitle(left: true).lowercased().contains("empty"))
        XCTAssertFalse(LeaveWords.deleteRest.lowercased().contains("empty"))
        for s in [LeaveWords.empty, LeaveWords.notAllErased, LeaveWords.deleteRest] {
            for bad in LeaveWords.never { XCTAssertFalse(s.lowercased().contains(bad), s) }
        }
    }

    // MARK: - M5: the journey

    func testTheJourneyClaimsOnlyWhatDarkProofDoes() {
        XCTAssertEqual(Journey.carried, "Carried by other phones on the way. They can't read it, and DarkProof never notes whose they are.")
        XCTAssertFalse(Journey.carried.contains("nobody sees"))
    }

    // MARK: - Must-fix 7 and M6: Finder

    func testIAmWithThemSaysWhatHappened() {
        XCTAssertEqual(FinderWords.withThemSent(group: "My group", waiting: false), "Sent to My group")
        XCTAssertEqual(FinderWords.withThemSent(group: "My group", waiting: true), "Saved. It goes out when a phone or the internet is in reach.")
        for w in [true, false] {
            let s = FinderWords.withThemSent(group: "My group", waiting: w).lowercased()
            XCTAssertFalse(s.contains("is told"), s); XCTAssertFalse(s.contains("you're with"), s)
        }
        XCTAssertEqual(FinderWords.withThem("Marco"), "I'm with Marco")
        XCTAssertNil(CheckIn.of(FinderWords.withThem("Marco")), "never a check-in")
    }

    func testTheSearchEndsAsOnAndroid() {
        var p = Person(id: "b2b2b2b2b2b2b2b2", name: "Marco", lastTs: 1)
        p.checkIn = .help; p.finder = "0123456789ab"
        XCTAssertTrue(FinderWords.searching(p))
        var safe = p; safe.checkIn = .safe; safe.finder = nil
        XCTAssertFalse(FinderWords.searching(safe), "\"I'm safe\" ends it")
        var noNote = p; noNote.finder = nil
        XCTAssertFalse(FinderWords.searching(noNote), "an \"I need help\" without the note ends it")
        var report = p; report.reportedBy = "Anna"
        XCTAssertFalse(FinderWords.searching(report), "a report about someone is never searched for")
        XCTAssertEqual(FinderWords.ended("Marco", safe: true), "Marco is safe")
        XCTAssertEqual(FinderWords.ended("Marco", safe: false),
                       "Marco's phone can't be found by Bluetooth right now. A new “I need help” from Marco makes it findable again.")
        XCTAssertTrue(FinderWords.notLinked("Marco").hasPrefix("Find by Bluetooth works only when"), "L3: the button's name, not \"Finder\"")
    }

    // MARK: - Must-fix 6 and same pass: swipe to reply

    func testALiftPastTheThresholdReplies() {
        var s = SwipeReply()
        XCTAssertFalse(s.drag(width: 20, height: 2, scrubbing: false))
        XCTAssertEqual(s.moved, 20)
        XCTAssertTrue(s.drag(width: 50, height: 3, scrubbing: false), "the tap, once, as it passes")
        XCTAssertFalse(s.drag(width: 60, height: 3, scrubbing: false), "no second tap")
        XCTAssertTrue(s.armed)
        XCTAssertEqual(s.moved, 48 + 12 * 0.35, accuracy: 0.0001)
        XCTAssertTrue(s.drag(width: 500, height: 3, scrubbing: false) == false && s.moved == 72, "never more than half as far again")
        XCTAssertTrue(s.lifted())
        XCTAssertEqual(s, SwipeReply(), "the touch is over")
        XCTAssertFalse(s.lifted(), "a lift with nothing before it does nothing")
    }

    func testALiftBeforeTheThresholdOrBackDoesNotReply() {
        var s = SwipeReply()
        _ = s.drag(width: 40, height: 0, scrubbing: false)
        XCTAssertFalse(s.lifted())
        var back = SwipeReply()
        _ = back.drag(width: 60, height: 0, scrubbing: false)
        _ = back.drag(width: 30, height: 0, scrubbing: false) // slid back under the threshold
        XCTAssertFalse(back.armed); XCTAssertFalse(back.lifted())
    }

    func testOnlyASidewaysRightwardDragCounts() {
        var left = SwipeReply()
        _ = left.drag(width: -20, height: 0, scrubbing: false)
        _ = left.drag(width: 80, height: 0, scrubbing: false) // decided at the start: not a reply
        XCTAssertFalse(left.lifted())
        var up = SwipeReply()
        _ = up.drag(width: 10, height: 30, scrubbing: false) // a scroll
        _ = up.drag(width: 80, height: 30, scrubbing: false)
        XCTAssertFalse(up.lifted())
        XCTAssertEqual(up.moved, 0)
    }

    /// Must-fix 6: a finger moving a voice note's bars seeks; the same touch never also replies, whichever comes first.
    func testMovingAVoiceNoteNeverReplies() {
        var s = SwipeReply()
        _ = s.drag(width: 14, height: 0, scrubbing: false) // the row's drag starts a moment before the bars' pan
        XCTAssertFalse(s.drag(width: 30, height: 0, scrubbing: true))
        XCTAssertEqual(s.moved, 0, "the bubble goes back under the finger")
        XCTAssertFalse(s.drag(width: 97, height: 0, scrubbing: false), "the bars' drag ended first: the touch stays a seek")
        XCTAssertFalse(s.lifted())

        var armed = SwipeReply()
        XCTAssertTrue(armed.drag(width: 60, height: 0, scrubbing: false))
        _ = armed.drag(width: 70, height: 0, scrubbing: true)
        XCTAssertFalse(armed.armed); XCTAssertFalse(armed.lifted())
    }

    /// Same pass: a touch the system takes away never replies, even past the threshold.
    func testACancelledTouchNeverReplies() {
        var s = SwipeReply()
        XCTAssertTrue(s.drag(width: 60, height: 0, scrubbing: false))
        s.cancelled()
        XCTAssertEqual(s, SwipeReply())
        XCTAssertFalse(s.lifted(), "nothing is left for a late lift either")
    }

    // MARK: - Same pass: calls, alarms, headphones

    func testABreakPausesPlayingAndCancelsRecording() {
        func a(_ b: VoiceBreak, playing: Bool = false, recording: Bool = false) -> VoiceBreakAction { VoiceBreakAction.of(b, playing: playing, recording: recording) }
        let none = VoiceBreakAction(pause: false, cancel: false)
        // A call or an alarm.
        XCTAssertEqual(a(.interruptionBegan, playing: true), VoiceBreakAction(pause: true, cancel: false))
        XCTAssertEqual(a(.interruptionBegan, recording: true), VoiceBreakAction(pause: false, cancel: true))
        XCTAssertEqual(a(.interruptionBegan), none, "nothing playing or recording: nothing to do")
        XCTAssertEqual(a(.interruptionEnded, playing: true, recording: true), none, "nothing starts again by itself")
        // Headphones out: the note pauses rather than going on aloud.
        XCTAssertEqual(a(.deviceGone, playing: true), VoiceBreakAction(pause: true, cancel: false))
        XCTAssertEqual(a(.deviceGone, recording: true), VoiceBreakAction(pause: false, cancel: true))
        // Headphones in: playing goes on; a recording whose input may have moved is dropped.
        XCTAssertEqual(a(.deviceAdded, playing: true), none)
        XCTAssertEqual(a(.deviceAdded, recording: true), VoiceBreakAction(pause: false, cancel: true))
        // The app's own category change before playing or recording is nothing.
        XCTAssertEqual(a(.otherRoute, playing: true, recording: true), none)
        // An engine that stopped under its note.
        XCTAssertEqual(a(.engineChanged(running: false), playing: true), VoiceBreakAction(pause: true, cancel: false))
        XCTAssertEqual(a(.engineChanged(running: false), recording: true), VoiceBreakAction(pause: false, cancel: true))
        XCTAssertEqual(a(.engineChanged(running: true), playing: true, recording: true), none)
    }
}
