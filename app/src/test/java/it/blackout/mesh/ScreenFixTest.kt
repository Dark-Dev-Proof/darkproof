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

    // Re-check screens 2: the iPhone's header words (Status.swift `reachLine`), each case.
    @Test fun theChatSaysOnlineWhenNoPhoneIsInReach() {
        assertEquals("No one in reach right now", ChatReach.line(0, 0, radio = false))
        assertEquals("1 phone in reach", ChatReach.line(1, 0, radio = false))
        assertEquals("3 phones in reach · online", ChatReach.line(3, 2, radio = true))
        assertEquals("Online · 4 relays", ChatReach.line(0, 4, radio = false))
        assertEquals("Online · 1 relay", ChatReach.line(0, 1, radio = true))
        assertEquals("LoRa radio linked", ChatReach.line(0, 0, radio = true))
    }

    // Re-check screens 1: the iPhone's "Ask" toasts (HelpFlow.swift `queuedToast`), to a person and to the whole group.
    @Test fun theAskToastSaysSentOnlyWhenSomethingCarriesIt() {
        assertEquals("Sent the question to Marco", ChatReach.asked("Marco", inReach = true))
        assertEquals("The question to Marco waits for a phone in reach", ChatReach.asked("Marco", inReach = false))
        assertEquals("Sent the question", ChatReach.asked(null, inReach = true))
        assertEquals("The question waits for a phone in reach", ChatReach.asked(null, inReach = false))
    }

    // Re-check screens 7: the high-risk sheet names Find by Bluetooth only where it is offered.
    @Test fun theHighRiskSheetNamesFindByBluetoothOnlyWithTheTrial() {
        assertEquals("Receipts don't say how a message arrived.", HighRisk.saysLess(finder = false))
        assertEquals("No Find by Bluetooth, and receipts don't say how a message arrived.", HighRisk.saysLess(finder = true))
        assertFalse("no retired name", "Finder" in HighRisk.saysLess(true) || "Finder" in HighRisk.saysLess(false))
        assertTrue("offered only with the trial, on a phone", FinderPrefs.offered(trial = true, bluetooth = true))
        assertFalse(FinderPrefs.offered(trial = false, bluetooth = true)); assertFalse(FinderPrefs.offered(trial = true, bluetooth = false))
    }

    // Re-check screens 9: the Background switch's line while on, as the iPhone's.
    @Test fun theBackgroundSwitchSaysWhatItCosts() {
        assertEquals("Drawn once, so it costs almost no battery. Pure black saves the most.", AsciiBackground.ON_LINE)
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

    /**
     * Re-check screens 4: leaving a group or erasing empties the decoded sound and the levels, and a decode that was
     * still running when they were emptied hands its result to its caller but keeps nothing (the iPhone's cacheTurn).
     */
    @Test fun aDecodeThatEndsAfterTheCacheWasEmptiedKeepsNothing() {
        val kept = VoiceLevels()
        kept.get("a") { FloatArray(20) }; kept.get("b") { FloatArray(20) }
        assertEquals(2, kept.size)
        kept.clear()
        assertEquals("everything goes, with the message ids", 0, kept.size)
        // A decode under way while the cache is emptied.
        val decoding = CountDownLatch(1); val emptied = CountDownLatch(1)
        var got: FloatArray? = null
        val t = Thread { got = kept.get("late") { decoding.countDown(); emptied.await(5, TimeUnit.SECONDS); FloatArray(20) { 0.5f } } }
        t.start()
        assertTrue(decoding.await(5, TimeUnit.SECONDS))
        kept.clear(); emptied.countDown(); t.join(5_000)
        assertNotNull("its caller still gets it", got)
        assertEquals("but it isn't kept", 0, kept.size)
        // The next decode after that is kept as usual.
        var made = 0
        kept.get("late") { made++; FloatArray(20) }; kept.get("late") { made++; FloatArray(20) }
        assertEquals(1, made); assertEquals(1, kept.size)
        // The decoded sound uses the same rule.
        val sound = TurnKept<ShortArray>(4)
        repeat(6) { i -> sound.get("n$i") { ShortArray(8) } }
        assertEquals("the last 4 notes", 4, sound.size)
        sound.clear(); assertEquals(0, sound.size)
    }
}
