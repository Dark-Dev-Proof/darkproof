package it.blackout.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockFixTest {
    // The "I'm safe" shortcut used while DarkProof is locked.

    @Test fun waitingSafeGoesOutRightAfterTheShortcut() {
        assertTrue(WaitingSafe.stillWanted(at = 1_000, now = 1_000))
        assertTrue(WaitingSafe.stillWanted(at = 1_000, now = 1_000 + 30_000))
        assertTrue(WaitingSafe.stillWanted(at = 1_000, now = 1_000 + WaitingSafe.MAX_WAIT_MS))
    }

    @Test fun waitingSafeIsDroppedWhenTheUnlockComesLater() {
        assertFalse(WaitingSafe.stillWanted(at = 1_000, now = 1_000 + WaitingSafe.MAX_WAIT_MS + 1))
        assertFalse(WaitingSafe.stillWanted(at = 1_000, now = 1_000 + 10 * 60_000)) // ten minutes: the claim's case
        assertFalse(WaitingSafe.stillWanted(at = 1_000, now = 999)) // a time before it started: not trusted
    }

    // Answers under an "Are you safe?" notification.

    private val saved = setOf("NEW-CODE", "IN-USE")
    private val moves = mapOf("OLD-CODE" to "NEW-CODE", "LEFT-CODE" to "NOT-SAVED")
    private fun of(asked: String?) = AnswerGroup.of(asked, { it in saved }, { moves[it] })

    @Test fun answerGoesToTheSavedGroupThatAsked() {
        assertEquals(AnswerTo.Group("NEW-CODE"), of("NEW-CODE"))
        assertEquals(AnswerTo.Group(null), of(null)) // no group named: the group in use, as before
    }

    @Test fun answerFollowsTheAskingGroupToItsNewCode() {
        assertEquals(AnswerTo.Group("NEW-CODE"), of("OLD-CODE"))
    }

    @Test fun answerForAGroupNoLongerSavedGoesNowhere() {
        assertEquals(AnswerTo.Gone, of("UNKNOWN"))
        assertEquals(AnswerTo.Gone, of("LEFT-CODE")) // moved, but its new code isn't saved here either
    }

    @Test fun anEmptyGroupMeansNoneNamed() {
        assertEquals(AnswerTo.Group(null), of("")) // as on the iPhone, where an alert without a group carries ""
    }

    // "I'm safe" from a locked phone (final review 2026-11-23, item 1; rule Q8): it stops the clock on every phone.

    @Test fun theTileAsksForThePhonesUnlockFirst() {
        assertEquals(LockedSafe.Tap.UNLOCK_FIRST, LockedSafe.tile(ready = true, appLock = false, locked = true))
        assertEquals(LockedSafe.Tap.SEND, LockedSafe.tile(ready = true, appLock = false, locked = false))
        assertEquals("with the app's lock on, the app opens behind it", LockedSafe.Tap.OPEN_APP, LockedSafe.tile(ready = true, appLock = true, locked = true))
        assertEquals(LockedSafe.Tap.OPEN_APP, LockedSafe.tile(ready = true, appLock = true, locked = false))
        assertEquals("no group yet", LockedSafe.Tap.OPEN_APP, LockedSafe.tile(ready = false, appLock = false, locked = true))
        assertEquals(LockedSafe.Tap.OPEN_APP, LockedSafe.tile(ready = false, appLock = false, locked = false))
    }

    @Test fun beforeAndroid12TheButtonOpensTheAppInstead() {
        assertFalse("Android 8 to 11: a notification button can't ask for the unlock", LockedSafe.buttonSends(26))
        assertFalse(LockedSafe.buttonSends(30))
        assertTrue("Android 12 asks for the unlock, then sends", LockedSafe.buttonSends(31))
        assertTrue(LockedSafe.buttonSends(36))
    }

    @Test fun theAnswerIsNeverSentFromALockedPhone() {
        // An alert from before this rule, still on screen on Android 8 to 11: refused while the phone is locked.
        assertTrue(LockedSafe.receiverRefuses(sdk = 30, appLock = false, deviceLocked = true))
        assertFalse(LockedSafe.receiverRefuses(sdk = 30, appLock = false, deviceLocked = false))
        // From Android 12 the button itself asked for the unlock before it reached the receiver.
        assertFalse(LockedSafe.receiverRefuses(sdk = 31, appLock = false, deviceLocked = true))
        // With the app's lock on, never, as before.
        assertTrue(LockedSafe.receiverRefuses(sdk = 31, appLock = true, deviceLocked = false))
        assertTrue(LockedSafe.receiverRefuses(sdk = 26, appLock = true, deviceLocked = false))
    }
}
