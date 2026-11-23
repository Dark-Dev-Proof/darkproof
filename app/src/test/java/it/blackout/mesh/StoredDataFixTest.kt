package it.blackout.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone keeps, and for how long: copies of messages go with their epoch (spec "This phone's copy"), and an
 * alert doesn't outlive its message (spec "Alerts"). Same cases as the iPhone's StoredDataFixTests.
 */
class StoredDataFixTest {
    private val anna = "1111111111111111"; private val ben = "2222222222222222"
    private val group = "00112233445566778899aabbccddeeff"
    private val key = ByteArray(32) { 3 }
    private val day = 24 * 3_600_000L

    private fun epoch(n: Long, made: Long, secret: Int) = Keyring.Epoch.fresh(n, made, ByteArray(32) { secret.toByte() }, listOf(anna, ben), listOf(anna))

    /** Ben's message [i] in epoch [e], opened with its message key, and this phone's copy of it naming [named]. */
    private fun copy(e: Keyring.Epoch, i: Int, named: String = e.id): History.Saved {
        val id = "6a4400000000000$i"; val ts = e.made + 1_000
        val c = e.group.sealWith(e.messageKeys!!.key(1, i)!!, 1, i, id, ts, ben, "Ben", "message $i") { TestKeys.sign(it) }
        val o = e.open(Packet(id, e.group.hideTime(ts, c), 16, c))!!
        return History.open(key, id, History.seal(key, id, group, named, o, 1, i)!!)!!
    }

    @Test fun aCopyGoesWithTheEpochThatWasDeleted() {
        val ring = Keyring(); val one = epoch(1, 0, 1); val two = epoch(2, day, 2)
        ring.add(one); ring.add(two)
        val old = copy(one, 1); val new = copy(two, 2)
        val gone = ring.expire(day + 8 * day)
        assertEquals(listOf(one), gone)
        assertTrue(History.goesWith(old, gone, ring.all()))
        assertFalse(History.goesWith(new, gone, ring.all()))
    }

    @Test fun nothingGoesWhileNoEpochIsDeleted() {
        val ring = Keyring(); val one = epoch(1, 0, 1); val two = epoch(2, day, 2)
        ring.add(one); ring.add(two)
        val gone = ring.expire(day + 6 * day)
        assertTrue(gone.isEmpty())
        assertFalse(History.goesWith(copy(one, 1), gone, ring.all()))
    }

    @Test fun anOldCopyNamingItsEpochByHashGoesOnlyIfNoHeldEpochHasThatHash() {
        val one = epoch(1, 0, 1); val again = epoch(3, 2 * day, 1) // the same secret, sent again under another number
        val old = copy(one, 1, named = one.hash)
        assertTrue(History.goesWith(old, listOf(one), listOf(epoch(2, day, 2))))
        assertFalse(History.goesWith(old, listOf(one), listOf(again))) // still shown with that one
    }

    @Test fun aCopyOfAnotherEpochIsLeftAlone() {
        val one = epoch(1, 0, 1); val two = epoch(2, day, 2)
        assertFalse(History.goesWith(copy(two, 3), listOf(one), listOf(two)))
    }

    @Test fun anAlertGoesAWeekAfterItsMessagesDate() {
        val now = 1_794_000_000_000L
        assertEquals(AlertLife.WEEK_MS, AlertLife.timeout(now, now))
        assertEquals(AlertLife.WEEK_MS - 3 * day, AlertLife.timeout(now - 3 * day, now)) // arrived three days late
        assertEquals(60_000L, AlertLife.timeout(now - 9 * day, now)) // arrived after its week: a minute
        assertEquals(AlertLife.WEEK_MS, AlertLife.timeout(now + 600_000, now)) // dated ahead: a week at most
    }

    // Stored check-in clocks (decision C of the final review, 2026-11-23): table q's rows are sealed.

    private val hour = 3_600_000L

    /** Ben's 1-hour clock with where he was, taken at [now], as table q keeps it. */
    private fun bensClock(now: Long) = QuietBook().apply {
        take(ben, "e608000000000101", "00000000000000aa", now, now + hour, Spot(45.46420, 9.19000, 12), now, highRisk = false)
    }

    @Test fun storedClocksAreSealedAndOpenAgain() {
        val lines = bensClock(1_794_000_000_000L).saved
        assertTrue("a position to hide", lines.single().contains("45.46420"))
        val blob = QuietSeal.seal(key, group, lines)
        val text = String(blob, Charsets.ISO_8859_1)
        assertFalse("no position in what is stored", text.contains("45.46420") || text.contains("9.19000"))
        assertFalse("no member id in what is stored", text.contains(ben))
        assertEquals(lines, QuietSeal.open(key, group, blob))
        assertEquals(bensClock(1_794_000_000_000L), QuietBook.parse(QuietSeal.open(key, group, blob)!!))
        assertFalse("a fresh IV each time", blob.contentEquals(QuietSeal.seal(key, group, lines)))
    }

    @Test fun aSealedRowOpensOnlyForItsGroupAndItsKey() {
        val blob = QuietSeal.seal(key, group, bensClock(1_794_000_000_000L).saved)
        assertEquals(null, QuietSeal.open(ByteArray(32) { 4 }, group, blob))
        assertEquals("a row moved to another group doesn't open", null, QuietSeal.open(key, "ffeeddccbbaa99887766554433221100", blob))
        assertEquals(null, QuietSeal.open(key, group, blob.copyOf(blob.size - 1)))
        assertEquals(null, QuietSeal.open(key, group, ByteArray(5)))
        val flipped = blob.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertEquals(null, QuietSeal.open(key, group, flipped))
        assertEquals(emptyList<String>(), QuietSeal.open(key, group, QuietSeal.seal(key, group, emptyList())))
    }

    // The backup alarm with no engine running (QuietRang): only what its group's records still hold.

    private val t0 = 1_794_000_000_000L
    private fun alertFor(g: String, member: String, mark: String, at: Long) =
        QuietAlert(QuietAlerts.id(g, member), mark, at, "Ben's check-in clock ran out", "body", "Ben", "banner", "CODE", remind = false, safe = false)

    @Test fun theAlarmPostsWhatIsDueAndArmsWhatIsToCome() {
        val book = bensClock(t0)
        val due = alertFor(group, ben, "00000000000000aa", t0 + hour)
        // Before its time: armed again as it is.
        val early = QuietRang.handle(listOf(due), mapOf(group to book), t0 + hour - 60_000)
        assertEquals(listOf(due), early.left); assertTrue(early.post.isEmpty()); assertTrue(early.changed.isEmpty())
        // At its time: posted, and the record says so.
        val r = QuietRang.handle(listOf(due), mapOf(group to book), t0 + hour + 1_000)
        assertEquals(listOf(due), r.post); assertTrue(r.left.isEmpty()); assertEquals(setOf(group), r.changed)
        assertTrue(book.clock(ben)!!.alerted)
        // Rung again: posted already, nothing more.
        val again = QuietRang.handle(listOf(due), mapOf(group to book), t0 + hour + 2_000)
        assertTrue(again.post.isEmpty() && again.left.isEmpty())
    }

    @Test fun theAlarmDropsWhatNoGroupOrClockHoldsAnyMore() {
        val coming = alertFor(group, ben, "00000000000000aa", t0 + hour)
        // The group was left (or the phone erased): its row is gone, so nothing of it is posted or armed again, coming or due.
        assertTrue(QuietRang.handle(listOf(coming), emptyMap(), t0 + 60_000).let { it.post.isEmpty() && it.left.isEmpty() })
        assertTrue(QuietRang.handle(listOf(coming), emptyMap(), t0 + hour + 1_000).let { it.post.isEmpty() && it.left.isEmpty() })
        // Ben checked in: his clock no longer runs, and what was to come for it goes.
        val book = bensClock(t0).apply { checkIn(ben, t0 + 60_000, t0 + 60_000, highRisk = false) }
        assertTrue(QuietRang.handle(listOf(coming), mapOf(group to book), t0 + 120_000).let { it.post.isEmpty() && it.left.isEmpty() })
        // A newer clock replaced the one the alarm was set for.
        val replaced = bensClock(t0).apply { take(ben, "e608000000000102", "00000000000000bb", t0 + 1_000, t0 + 2 * hour, null, t0 + 1_000, highRisk = false) }
        assertTrue(QuietRang.handle(listOf(coming), mapOf(group to replaced), t0 + 120_000).let { it.post.isEmpty() && it.left.isEmpty() })
        // Another group's alert in the same alarm is kept.
        val other = "ffeeddccbbaa99887766554433221100"
        val theirs = alertFor(other, ben, "00000000000000aa", t0 + hour)
        assertEquals(listOf(theirs), QuietRang.handle(listOf(coming, theirs), mapOf(other to bensClock(t0)), t0 + 60_000).left)
    }

    /**
     * Re-check N2: Android ended the app, the alarm rings at Ben's deadline, and the Keystore fails for a moment, so the
     * sealed clocks don't open. Before, every alert read as "its group is gone" and was dropped. Now all of it is armed
     * again, the one due now and the one still to come, and the next ring posts it once the key opens.
     */
    @Test fun aRingThatCantOpenTheClocksArmsEverythingAgain() {
        val due = alertFor(group, ben, "00000000000000aa", t0 + hour)
        val coming = alertFor("ffeeddccbbaa99887766554433221100", ben, "00000000000000bb", t0 + 3 * hour)
        val now = t0 + hour + 1_000
        assertEquals("nothing is dropped as gone", listOf(due, coming), QuietRang.again(listOf(due, coming), now))
        // What the store does without the key: no group's clocks read, so the old path dropped both.
        assertTrue(QuietRang.handle(listOf(due, coming), emptyMap(), now).let { it.post.isEmpty() && it.left.isEmpty() })
        // A few minutes later the key opens: the alert due then is posted, from its record.
        val later = now + QuietRang.AGAIN_MS
        val r = QuietRang.handle(QuietRang.again(listOf(due), now), mapOf(group to bensClock(t0)), later)
        assertEquals(listOf(due), r.post)
        assertTrue("a few minutes, not at once", QuietRang.AGAIN_MS in 60_000L..10 * 60_000L)
        // A key that stays unreadable doesn't wake the phone for ever: an alert 12 hours past its time goes, as it would anyway.
        assertEquals(listOf(due), QuietRang.again(listOf(due), t0 + hour + QuietClock.LATE_MS))
        assertEquals(emptyList<QuietAlert>(), QuietRang.again(listOf(due), t0 + hour + QuietClock.LATE_MS + 1))
    }

    /**
     * Re-check N3: the receiver found no group in use. The alarm goes only when no group is saved at all (an erase ended)
     * or the saved groups read and none is left (every group was left); saved groups that can't be read right now (the
     * Keystore failed for a moment) arm it again, never cancel it for good.
     */
    @Test fun theAlarmGoesOnlyWhenNoGroupIsSaved() {
        assertEquals(QuietRang.Gate.RING, QuietRang.gate(group = true, saved = true, readable = true))
        assertEquals(QuietRang.Gate.CANCEL, QuietRang.gate(group = false, saved = false, readable = true))
        assertEquals(QuietRang.Gate.CANCEL, QuietRang.gate(group = false, saved = true, readable = true))
        assertEquals(QuietRang.Gate.AGAIN, QuietRang.gate(group = false, saved = true, readable = false))
    }

    /** Re-check N4: the words the window and Home show when the store can't be opened (the desktop shows them: QuietStoreEngineTest). */
    @Test fun aStoreFromANewerVersionIsSaidInWords() {
        assertEquals("The saved messages were written by a newer version of DarkProof (store 10, this version reads 9). Install the newer version again.", StoreTooNew.text(10, 9))
        assertEquals("Can't open the saved messages", StoreTooNew.words(IllegalStateException("disk I/O error")))
    }
}
