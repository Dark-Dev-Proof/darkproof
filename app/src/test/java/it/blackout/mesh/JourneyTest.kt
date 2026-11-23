package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test

/**
 * Journey notes in receipts and "How it got there" (spec/FORMAT.md, "Journey notes"): what is written, what is read, and
 * that a reader which doesn't know notes takes the same ids from a receipt as before. The same cases as the iPhone's JourneyTests.
 */
class JourneyTest {
    private val a = "0123456789abcdef"; private val b = "89abcdef01234567"; private val c = "fedcba9876543210"

    /** The rule of "Delivery receipts" as it was before journey notes, copied here so it can't change with the code. */
    private fun oldIds(text: String): List<String> =
        text.splitToSequence(' ').filter { id -> id.length == 16 && id.all { it in '0'..'9' || it in 'a'..'f' } }.take(200).toList()

    private fun note(s: String) = Journey.Note.parse(s)

    // MARK: - The token

    @Test fun notesParse() {
        assertEquals(3, note("h3i")?.hops); assertEquals(Journey.Link.INTERNET, note("h3i")?.link)
        assertEquals(Journey.Link.BLUETOOTH, note("h1b")?.link); assertEquals(Journey.Link.WIFI, note("h1w")?.link)
        assertEquals(Journey.Link.DIRECT, note("h1p")?.link); assertEquals(Journey.Link.RADIO, note("h1r")?.link)
        assertEquals(0, note("h0b")?.hops); assertEquals(16, note("h16r")?.hops)
        assertEquals("a leading zero is read", 7, note("h07w")?.hops); assertEquals(0, note("h00b")?.hops)
        // A letter this app doesn't know: another link, the hops still read.
        assertEquals(Journey.Link.OTHER, note("h2z")?.link); assertEquals(2, note("h2z")?.hops); assertEquals(Journey.Link.OTHER, note("h9a")?.link)
    }

    @Test fun whatIsNoNote() {
        for (bad in listOf("", "h", "hb", "h3", "3i", "h17b", "h99b", "h016b", "h003b", "h3B", "H3b", "h3ii", "h-1b", "h+1b", "h 1b", "h1.b", "h１b", "h3é", "x3b", "h3b ", "hh3b", "h3\u0000"))
            assertNull(bad, note(bad))
    }

    /** 16 minus the ttl of the first copy, not below 0; a ttl above 16 counts as 16. */
    @Test fun whatAReaderWrites() {
        fun written(ttl: Int, link: Journey.Link) = Journey.Note.of(ttl, link)?.token
        assertEquals("h1b", written(15, Journey.Link.BLUETOOTH)); assertEquals("h0w", written(16, Journey.Link.WIFI)); assertEquals("h3i", written(13, Journey.Link.INTERNET))
        assertEquals("h15r", written(1, Journey.Link.RADIO)); assertEquals("h16p", written(0, Journey.Link.DIRECT))
        assertEquals("h0i", written(40, Journey.Link.INTERNET)); assertEquals("h0b", written(64, Journey.Link.BLUETOOTH))
        assertNull("a link this app doesn't know is never written", written(15, Journey.Link.OTHER))
        for (ttl in 0..16) for (l in Journey.Link.entries - Journey.Link.OTHER) {
            val n = Journey.Note.of(ttl, l)!!
            assertEquals(n, note(n.token!!))
        }
    }

    @Test fun theLinkOfARouterPeer() {
        assertEquals(Journey.Link.BLUETOOTH, Journey.Link.ofPeer("ble:3")); assertEquals(Journey.Link.WIFI, Journey.Link.ofPeer("wifi:abc#1"))
        assertEquals(Journey.Link.DIRECT, Journey.Link.ofPeer(NearbyPeers.peer("x"))); assertEquals(Journey.Link.RADIO, Journey.Link.ofPeer("lora"))
        assertEquals(Journey.Link.RADIO, Journey.Link.ofPeer("meshcore")); assertEquals(Journey.Link.INTERNET, Journey.Link.ofPeer("nostr"))
        assertNull("this phone's own, or a link that says nothing", Journey.Link.ofPeer("")); assertNull(Journey.Link.ofPeer(null)); assertNull(Journey.Link.ofPeer("board"))
    }

    @Test fun wording() {
        assertEquals("Over Bluetooth", Journey.Link.BLUETOOTH.over); assertEquals("Over a Wi-Fi network", Journey.Link.WIFI.over)
        assertEquals("Over phone-to-phone Wi-Fi", Journey.Link.DIRECT.over); assertEquals("Over the radio", Journey.Link.RADIO.over)
        assertEquals("Over the internet", Journey.Link.INTERNET.over); assertEquals("Over another link", Journey.Link.OTHER.over)
        assertEquals("Over the internet · about 3 hops", note("h3i")?.text)
        assertEquals("Over Bluetooth · about 1 hop", note("h1b")?.text)
        assertEquals("Over a Wi-Fi network", note("h0w")?.text)
        assertEquals("Less than a minute later", Journey.later(0)); assertEquals("Less than a minute later", Journey.later(59_999))
        assertEquals("1 min later", Journey.later(60_000)); assertEquals("4 min later", Journey.later(4 * 60_000L + 5)); assertEquals("59 min later", Journey.later(59 * 60_000L))
        assertEquals("3 h later", Journey.later(3 * 3_600_000L)); assertEquals("3 days later", Journey.later(72 * 3_600_000L))
        assertNull("the reader's clock is behind the writer's: nothing is said", Journey.later(-1))
    }

    // MARK: - Receipts

    @Test fun entriesReadNotesRightAfterAnId() {
        fun read(t: String) = Receipt.entries(t).map { it.id.take(2) + (it.note?.let { n -> ":${n.hops}" } ?: "") }
        assertEquals(listOf("01:3", "89:1"), read("$a h3i $b h1b"))
        assertEquals(listOf("01", "89:16", "fe"), read("$a $b h16r $c"))
        assertEquals("only the token right after an id", listOf("01:2", "89", "fe"), read("h1b $a h2b h3b $b x h4b $c h99x h5b"))
        assertEquals("more than one space", listOf("01:3", "89"), read("$a  h3i   $b"))
        assertEquals(listOf("01", "89", "fe:2"), read("$a h17b $b h3B $c h2z"))
        assertEquals(Journey.Link.OTHER, Receipt.entries("$a h2z").first().note?.link)
        assertEquals(emptyList<String>(), read("")); assertEquals(emptyList<String>(), read("h3i"))
    }

    /** A reader that doesn't know notes skips them: it takes exactly the ids it took before, in the same order. */
    @Test fun aReaderWithoutNotesTakesTheSameIds() {
        val many = (1..202).joinToString(" ") { "%016x".format(it) + " h${it % 17}" + listOf("b", "w", "p", "r", "i")[it % 5] }
        val texts = listOf("$a h3i $b h1b", "$a $b", "", "h1b", "$a h17b $b h016b", "h1b $a h2b h3b $b x h4b $c h99x h5b", "$a  h3i   $b",
            "0123456789ABCDEF short 0123456789abcdeg  $c h2z", many, "$a h1b $a h9i")
        for (t in texts) {
            assertEquals("this app's id-only reader is still the old rule", oldIds(t), Receipt.ids(t))
            assertEquals("the new reader confirms the same messages", oldIds(t), Receipt.entries(t).map { it.id })
        }
        assertEquals(listOf(a, b), oldIds("$a h3i $b h1b"))
        assertEquals(200, oldIds(many).size)
    }

    @Test fun notesDoNotCountTowardsThe200() {
        val ids = (1..202).map { "%016x".format(it) }
        val e = Receipt.entries(ids.joinToString(" ") { "$it h4i" })
        assertEquals(200, e.size); assertEquals(ids.take(200), e.map { it.id })
        assertEquals("the note after the 200th id is its note", 4, e.last().note?.hops)
        assertTrue(e.all { it.note != null })
    }

    @Test fun writingAReceipt() {
        val entries = listOf(Receipt.Entry(a, note("h3i")), Receipt.Entry(b), Receipt.Entry(c, note("h1b")))
        assertEquals("$a h3i $b $c h1b", Receipt.text(entries, highRisk = false))
        assertEquals(entries, Receipt.entries(Receipt.text(entries, highRisk = false)))
        // High-risk mode: no notes, the receipt is exactly what it was before notes existed.
        assertEquals(Receipt.text(listOf(a, b, c)), Receipt.text(entries, highRisk = true))
        assertEquals("$a $b $c", Receipt.text(entries, highRisk = true))
        // A link this app doesn't write leaves no token behind.
        assertEquals(a, Receipt.text(listOf(Receipt.Entry(a, note("h2z"))), highRisk = false))
        val many = (0 until 450).map { Receipt.Entry("%016x".format(it), note("h1b")) }
        assertEquals(listOf(200, 200, 50), Receipt.batchesOf(many).map { it.size })
        assertEquals(many, Receipt.batchesOf(many).flatten())
        for (batch in Receipt.batchesOf(many)) assertEquals(batch, Receipt.entries(Receipt.text(batch, highRisk = false)))
    }

    @Test fun deliveriesKeepTheNoteOfTheEarliestReceipt() {
        fun receipt(sender: String, name: String, ts: Long, text: String) = Message("r$sender$ts", ts, sender, name, text)
        val d = Deliveries()
        d.sent("g", a); d.sent("g", b)
        d.add("g", receipt("sara", "Sara", 300, "$a h3i $b"))
        d.add("g", receipt("marco", "Marco", 200, "$a h1b"))
        d.add("g", receipt("sara", "Sara", 400, "$a h9r"))          // later: not counted
        d.add("g", receipt("lucia", "Lucia", 250, "$a h1b $a h9i")) // named twice: as named first
        d.add("g", receipt("old", "Old app", 500, a))               // a reader that writes no notes
        d.add("other", receipt("eve", "Eve", 100, "$a h1b"))        // another group's receipt key
        assertEquals(listOf("marco", "lucia", "sara", "old"), d.readers(a).map { it.id })
        assertEquals(listOf(1, 1, 3, null), d.readers(a).map { it.note?.hops })
        assertEquals(Journey.Link.INTERNET, d.readers(a)[2].note?.link)
        assertEquals(listOf("sara"), d.readers(b).map { it.id }); assertNull(d.readers(b).first().note)
        assertTrue("no message of this phone's", d.readers(c).isEmpty())
    }

    // MARK: - The screen

    @Test fun notLeftYet() {
        val s = Journey.steps(1_000, false, null, emptyList(), 5_000)
        assertEquals(listOf("Saved on this phone"), s.map { it.title })
        assertEquals("No phone is in reach yet. It leaves by itself when one is.", s[0].detail); assertEquals(Journey.Step.Kind.SAVED, s[0].kind)
        val late = Journey.steps(1_000, false, null, emptyList(), 1_000 + 8 * 24 * 3_600_000L)
        assertEquals("No phone was in reach for 7 days. It is no longer passed on.", late[0].detail)
    }

    @Test fun handedOnButNotConfirmed() {
        val s = Journey.steps(1_000, true, Journey.Left(Journey.Link.BLUETOOTH, 2_000), emptyList(), 5_000)
        assertEquals(listOf("Left your phone", "Not confirmed by anyone yet"), s.map { it.title })
        assertEquals("Over Bluetooth", s[0].detail); assertEquals(2_000L, s[0].at)
        val old = Journey.steps(1_000, true, null, emptyList(), 5_000)
        assertEquals("Left your phone", old[0].title); assertNull(old[0].detail); assertNull(old[0].at)
    }

    @Test fun reachedReaders() {
        val readers = listOf(Reader("m", "Marco", 41_000, note("h1b")), Reader("s", "Sara", 241_000, note("h3i")), Reader("o", "", 500_000))
        val s = Journey.steps(1_000, true, Journey.Left(Journey.Link.INTERNET, 1_500), readers, 600_000)
        assertEquals(listOf("Left your phone", "Reached Marco", "Reached Sara", "Reached someone"), s.map { it.title })
        assertEquals(listOf("Over the internet", "Over Bluetooth · about 1 hop", "Over the internet · about 3 hops", null), s.map { it.detail })
        assertEquals(listOf(null, "Less than a minute later", "4 min later", "8 min later"), s.map { it.later })
        assertEquals("without a note the screen shows the time of the receipt", 500_000L, s[3].at)
        assertTrue("Sara's note says 3 hops", Journey.wasCarried(readers))
        assertFalse("1 hop, and no note: nothing says other phones carried it", Journey.wasCarried(listOf(readers[0], readers[2])))
        assertFalse(Journey.wasCarried(emptyList()))
        // Confirmed, though this phone never noted handing it on: it left.
        assertEquals(listOf("Left your phone", "Reached Marco"), Journey.steps(1_000, false, null, listOf(readers[0]), 600_000).map { it.title })
        assertEquals("Sealed the whole way", Journey.SEALED)
        assertEquals("Carried by other phones on the way. They can't read it, and DarkProof never notes whose they are.", Journey.CARRIED) // design review M5
    }

    // MARK: - What the writer's phone remembers

    @Test fun howAMessageLeftIsSavedAsLetterAndTime() {
        for (l in Journey.Link.entries - Journey.Link.OTHER) {
            val left = Journey.Left(l, 1_793_807_238_631)
            assertEquals(left, Journey.Left.parse(left.saved!!))
        }
        assertEquals("r12", Journey.Left(Journey.Link.RADIO, 12).saved)
        assertNull(Journey.Left(Journey.Link.OTHER, 12).saved)
        for (bad in listOf("", "b", "12", "z12", "b-1", "B12", "b1x")) assertNull(bad, Journey.Left.parse(bad))
    }
}
