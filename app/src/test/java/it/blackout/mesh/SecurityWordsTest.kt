package it.blackout.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words of Group security and of Home's dials: the same on Android, the desktop and the iPhone. */
class SecurityWordsTest {
    private val day = 24 * 3_600_000L
    private val now = 1_800_000_000_000L

    @Test fun theVerdict() {
        assertEquals("All checked", SecurityWords.verdict(0))
        assertEquals("1 thing to check", SecurityWords.verdict(1))
        assertEquals("2 things to check", SecurityWords.verdict(2))
        assertEquals("3 things to check", SecurityWords.verdict(3))
    }

    @Test fun peopleChecked() {
        assertEquals("Nobody else yet", SecurityWords.people(0, emptyList()))
        assertEquals("5 of 5", SecurityWords.people(5, emptyList()))
        assertEquals("4 of 5. Ben is not.", SecurityWords.people(5, listOf("Ben")))
        assertEquals("3 of 5. Ben and Luca are not.", SecurityWords.people(5, listOf("Ben", "Luca")))
        assertEquals("1 of 5. Ben, Luca and 2 more are not.", SecurityWords.people(5, listOf("Ben", "Luca", "Sara", "Tom")))
        assertEquals("2 of 5. Ben, Luca and 1 more are not.", SecurityWords.people(5, listOf("Ben", "Luca", "Sara")))
    }

    @Test fun someoneWhoseNameHasNotComeIsCountedNotNamed() {
        assertEquals("0 of 1. 1 is not.", SecurityWords.people(1, listOf("")))
        assertEquals("0 of 2. 2 are not.", SecurityWords.people(2, listOf("", "")))
        assertEquals("1 of 3. Ben and 1 more are not.", SecurityWords.people(3, listOf("", "Ben")))
    }

    @Test fun theKeysAge() {
        assertEquals("Last changed today", SecurityWords.keyAge(now, now))
        assertEquals("Last changed today", SecurityWords.keyAge(now - day + 1, now))
        assertEquals("Last changed yesterday", SecurityWords.keyAge(now - day, now))
        assertEquals("Last changed 2 days ago", SecurityWords.keyAge(now - 2 * day, now))
        assertEquals("Last changed 9 days ago", SecurityWords.keyAge(now - 9 * day - 5_000, now))
        assertEquals("Last changed today", SecurityWords.keyAge(now + 60_000, now)) // a key dated a little ahead
    }

    @Test fun theKeyIsFlaggedOnlyPastTheWeekAndADay() {
        assertFalse(SecurityWords.keyOld(now, now))
        assertFalse(SecurityWords.keyOld(now - 7 * day, now))            // the weekly change is due, not late
        assertFalse(SecurityWords.keyOld(now - 8 * day, now))
        assertTrue(SecurityWords.keyOld(now - 8 * day - 1, now))
        assertEquals(7 * day, Keyring.KEEP_MS)                           // the app's own change period
    }

    @Test fun newKeyIsOfferedOnlyOnceTheKeyIsADayOld() {
        val min = MeshEngine.NEW_KEY_AFTER_MS
        assertEquals(day, min)
        assertFalse(SecurityWords.keyOldEnough(now, now, min))               // a join or a removal made a key just now
        assertFalse(SecurityWords.keyOldEnough(now - 3_600_000L - 1, now, min)) // the old limit of one hour is not enough
        assertFalse(SecurityWords.keyOldEnough(now - day + 1, now, min))
        assertTrue(SecurityWords.keyOldEnough(now - day, now, min))
        assertTrue(SecurityWords.keyOldEnough(now - 6 * day, now, min))
        assertFalse(SecurityWords.keyOldEnough(now + 60_000, now, min))      // a key dated ahead of the clock is not old
    }

    @Test fun howLongMessagesStay() {
        assertEquals("After 1 to 2 weeks", SecurityWords.disappear(false, "phone"))
        assertEquals("After 1 to 2 weeks. On this phone, 1 day after they arrive.", SecurityWords.disappear(true, "phone"))
        assertEquals("After 1 to 2 weeks. On this computer, 1 day after they arrive.", SecurityWords.disappear(true, "computer"))
        assertEquals("Only people in Family can read these. They disappear after 1 to 2 weeks.", SecurityWords.chatNote("Family", false, "phone"))
        assertEquals("Only people in Family can read these. High-risk mode: they leave this phone 1 day after they arrive.", SecurityWords.chatNote("Family", true, "phone"))
    }

    @Test fun theDials() {
        assertEquals(Dial(false, false, "Not allowed"), Dials.bluetooth(allowed = false, switchedOn = true, linked = 0))
        assertEquals(Dial(false, false, "Not allowed"), Dials.bluetooth(allowed = false, switchedOn = false, linked = 0))
        assertEquals(Dial(false, false, "Off"), Dials.bluetooth(allowed = true, switchedOn = false, linked = 0))
        assertEquals(Dial(true, false, "Looking"), Dials.bluetooth(true, true, 0))
        assertEquals(Dial(true, true, "1 phone"), Dials.bluetooth(true, true, 1))
        assertEquals(Dial(true, true, "2 phones"), Dials.bluetooth(true, true, 2))

        assertEquals(Dial(false, false, "Off"), Dials.wifi(on = false, linked = 0))
        assertEquals(Dial(true, false, "Looking"), Dials.wifi(true, 0))
        assertEquals(Dial(true, true, "1 phone"), Dials.wifi(true, 1))
        assertEquals(Dial(true, true, "3 phones"), Dials.wifi(true, 3))

        assertEquals(Dial(false, false, "Not paired"), Dials.radio(paired = false, connected = false))
        assertEquals(Dial(true, false, "Not in reach"), Dials.radio(paired = true, connected = false))
        assertEquals(Dial(true, true, "Linked"), Dials.radio(paired = true, connected = true))

        assertEquals(Dial(false, false, "Off"), Dials.internet(on = false, relays = 0, throughTor = false))
        assertEquals(Dial(true, false, "Connecting"), Dials.internet(true, 0, true))
        assertEquals(Dial(true, false, "Connecting"), Dials.internet(true, 0, false))
        assertEquals(Dial(true, true, "Through Tor"), Dials.internet(true, 2, true))
        assertEquals(Dial(true, true, "Online"), Dials.internet(true, 2, false))
    }

    /** Joining or removed: never "All checked", and never green, whatever else is fine. */
    @Test fun aGroupThisPhoneIsNotInHasItsOwnVerdict() {
        assertEquals("Waiting to be let in", SecurityWords.outside(removed = false))
        assertEquals("You were removed", SecurityWords.outside(removed = true))
        val outside = listOf(SecurityLine("pq", 0, "Quantum-safe keys", "On, always"), SecurityLine("invite", 0, "Invite", "Open.", ok = false))
        assertEquals("Waiting to be let in", GroupSecurity.verdict(outside, removed = false))
        assertEquals("You were removed", GroupSecurity.verdict(outside, removed = true))
        assertFalse(GroupSecurity.allFine(outside))
        val inside = listOf(SecurityLine("people", 0, "People checked", "2 of 2")) + outside
        assertEquals("All checked", GroupSecurity.verdict(inside, removed = false))
        assertTrue(GroupSecurity.allFine(inside))
    }

    /** A request under a member's name is told apart the way the app compares names; a name not known yet matches nothing. */
    @Test fun aRequestUnderAMembersName() {
        val members = memberNameKeys(listOf("Ben", "", "Giulia"))
        assertTrue(Vouch.key("ben ") in members)
        assertFalse(Vouch.key("Luca") in members)
        assertFalse("" in members)
    }
}
