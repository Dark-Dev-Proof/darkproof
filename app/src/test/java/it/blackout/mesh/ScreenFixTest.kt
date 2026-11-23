package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The screen-side fixes of the Fable 5.2 review (briefs-2026-11-22/fable-fixes.md, Android-ui): the clock card on a
 * device that doesn't keep the clock (item 9), its words with alerts off (design review M1), the chat's reach line and the
 * "Ask" toast (L5), the background's one strength range (L1), and the voice recorder's and bubbles' helpers (Android review
 * findings 5 and 12). Plain Kotlin: the desktop runs it too.
 */
class ScreenFixTest {
    private val min = 60_000L
    private val d = 1_800_000_000_000L

    // ---- Item 9: the card promises nothing for a clock this device doesn't keep ----

    @Test fun aCardKeepsItsPromiseOnlyForTheClockThisDeviceHolds() {
        assertTrue("running, that very deadline", QuietBook.keeps(QuietBook.State.Running(d), d, verified = true))
        assertTrue("run out, still armed: this device alerted or will", QuietBook.keeps(QuietBook.State.RanOut(d), d, verified = true))
        assertFalse("not verified", QuietBook.keeps(QuietBook.State.Running(d), d, verified = false))
        assertFalse("a newer clock of that member is the one held", QuietBook.keeps(QuietBook.State.Running(d + 30 * min), d, verified = true))
        assertFalse("a check-in after it cancelled it", QuietBook.keeps(QuietBook.State.CheckedIn(d + min, d), d, verified = true))
        assertFalse("never taken (rule Q1), or its record has gone", QuietBook.keeps(null, d, verified = true))
        assertFalse("a stop is no clock", QuietBook.keeps(QuietBook.State.Running(0L), 0L, verified = true))
    }

    @Test fun aKeptClockAsTheBookHoldsItIsKeptAndAnOlderOneIsNot() {
        val book = QuietBook(); val anna = "2222222222222222"
        val ts = d - 60 * min
        assertTrue(book.take(anna, "e608000000000101", "00000000000000aa", ts, d, null, ts + min, false))
        assertTrue(QuietBook.keeps(book.state(anna, ts + 2 * min), d, verified = true))
        // Anna starts a newer clock: the card of the first one no longer promises anything here.
        assertTrue(book.take(anna, "e608000000000102", "00000000000000ab", ts + 5 * min, d + 30 * min, null, ts + 6 * min, false))
        assertFalse(QuietBook.keeps(book.state(anna, ts + 7 * min), d, verified = true))
        assertTrue(QuietBook.keeps(book.state(anna, ts + 7 * min), d + 30 * min, verified = true))
        // Her check-in after it: neither card keeps a promise.
        assertTrue(book.checkIn(anna, ts + 8 * min, ts + 8 * min, false))
        assertFalse(QuietBook.keeps(book.state(anna, ts + 9 * min), d + 30 * min, verified = true))
    }

    @Test fun theCardsLineSaysOnlyWhatThisDeviceKnows() {
        // This phone's own clock: about the phones that got it, which "can" alert (M1).
        assertEquals("Ends 18:30. Phones that got it can alert their owners if you don't check in.",
            QuietWords.cardLine(null, "18:30", keeping = true, alerts = true, alertsOff = false))
        // Someone else's, kept here, alerts on: as before, with Android's inexact alarms.
        assertEquals("If no check-in from Anna reaches this phone by 18:30, it tells you. It can come a few minutes late.",
            QuietWords.cardLine("Anna", "18:30", keeping = true, alerts = true, alertsOff = false, late = true))
        // Kept here, alerts off for DarkProof (M1).
        assertEquals("If no check-in from Anna reaches this phone by 18:30, it shows here. Alerts are off for DarkProof.",
            QuietWords.cardLine("Anna", "18:30", keeping = true, alerts = true, alertsOff = true, late = true))
        // Not kept here (item 9): the spec's line, and no promise.
        assertEquals("This phone isn't keeping this clock.", QuietWords.cardLine("Anna", "18:30", keeping = false, alerts = true, alertsOff = false))
        assertEquals("This computer isn't keeping this clock.", QuietWords.cardLine("Anna", "18:30", keeping = false, alerts = false, alertsOff = false, device = "computer"))
        // A computer posts no alerts: a kept clock's card promises nothing about telling.
        assertNull(QuietWords.cardLine("Anna", "18:30", keeping = true, alerts = false, alertsOff = false, device = "computer"))
        for (keeping in listOf(true, false)) for (off in listOf(true, false)) {
            val l = QuietWords.cardLine("Anna", "18:30", keeping, true, off, late = true)!!.lowercase()
            for (n in QuietWords.NEVER) assertFalse(l, n in l)
            if (!keeping) assertFalse("no promise from a device that doesn't keep it: $l", "tells you" in l || "shows here" in l)
        }
    }

    // ---- L5: the chat's reach line, and "Asked" only when something can carry it ----

    @Test fun theChatSaysOnlineWhenNoPhoneIsInReach() {
        assertEquals("0 phones in reach", ChatReach.line(0, 0, tor = true))
        assertEquals("1 phone in reach", ChatReach.line(1, 0, tor = false))
        assertEquals("3 phones in reach · online", ChatReach.line(3, 2, tor = true))
        assertEquals("Online through Tor", ChatReach.line(0, 4, tor = true))
        assertEquals("Online", ChatReach.line(0, 1, tor = false))
        assertEquals("Asked My group", ChatReach.asked("My group", inReach = true))
        assertEquals("Saved. It goes out when a phone or the internet is in reach.", ChatReach.asked("My group", inReach = false))
    }

    // ---- L1: one strength range, the iPhone's ----

    @Test fun theBackgroundsStrengthIsTwentyToAHundred() {
        assertEquals(20, AsciiBackground.MIN_STRENGTH)
        assertEquals("an older build's 0 reads as the least", 20, AsciiBackground.strengthOf(0))
        assertEquals(45, AsciiBackground.strengthOf(45))
        assertEquals(100, AsciiBackground.strengthOf(250))
    }

    // ---- Android review finding 5: a recording's sound is its own ----

    @Test fun aTakeKeepsAtMostItsCapAndHandsOverACopy() {
        val t = VoiceTake(10)
        assertEquals(4, t.add(shortArrayOf(1, 2, 3, 4), 4))
        assertEquals("only what fits", 6, t.add(ShortArray(8) { 9 }, 8))
        assertTrue(t.full); assertEquals(0, t.room)
        assertEquals("nothing more once full", 0, t.add(shortArrayOf(5), 1))
        assertEquals("a negative length takes nothing", 0, VoiceTake(4).add(shortArrayOf(1), -3))
        assertEquals("a length beyond the chunk takes the chunk", 1, VoiceTake(4).add(shortArrayOf(1), 3))
        val c = t.copy()
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 9, 9, 9, 9, 9, 9), c)
        c[0] = 42
        assertEquals("the copy is its own", 1.toShort(), t.copy()[0])
    }

    @Test fun aRecorderThatOutlivesItsRecordingWritesOnlyIntoItsOwnTake() {
        // As finishRecording does when the microphone stalls: it stops waiting, takes what there is and starts afresh. The
        // old recorder comes back from its read and writes again: nothing throws, and neither the copy nor the next take changes.
        val old = VoiceTake(48_000)
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        var failure: Throwable? = null
        val chunk = ShortArray(960) { 7 }
        val recorder = Thread {
            try {
                old.add(chunk, chunk.size); started.countDown()
                release.await(5, TimeUnit.SECONDS) // "stuck in a read"
                while (!old.full) old.add(chunk, chunk.size) // it doesn't look at stop: the worst case
            } catch (e: Throwable) { failure = e } finally { done.countDown() }
        }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        old.stop = true
        val handed = old.copy()
        val next = VoiceTake(48_000)
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS)); recorder.join(1_000)
        assertNull("the old recorder ended without an exception", failure)
        assertEquals(960, handed.size)
        assertEquals("the next recording got nothing of the old one", 0, next.count)
        assertFalse("its stop is its own", next.stop)
    }

    // ---- Android review finding 12: a bubble's levels are worked out once ----

    @Test fun levelsAreWorkedOutOncePerNoteAndKeptForMany() {
        val kept = VoiceLevels(max = 3)
        var made = 0
        fun levels(id: String) = kept.get(id) { made++; FloatArray(20) { id.length / 10f } }
        levels("a"); levels("a"); levels("a")
        assertEquals("decoded once", 1, made)
        levels("b"); levels("c"); levels("a") // a is the newest used again
        levels("d") // b goes, the least recently used
        assertEquals(3, kept.size); assertEquals(4, made)
        levels("a"); assertEquals("still kept", 4, made)
        levels("b"); assertEquals("worked out again", 5, made)
        // A note that can't be decoded isn't kept: it is tried again when it shows again.
        var tries = 0
        assertNull(kept.get("bad") { tries++; null }); assertNull(kept.get("bad") { tries++; null })
        assertEquals(2, tries)
        assertEquals("200 notes by default: a long chat scrolls back without decoding again", 200, VoiceLevels().let { l -> repeat(250) { i -> l.get("m$i") { FloatArray(20) } }; l.size })
    }
}
