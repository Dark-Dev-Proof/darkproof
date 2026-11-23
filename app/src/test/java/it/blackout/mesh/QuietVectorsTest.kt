package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * "If I go quiet" against spec/test-vectors.txt (`kind: quiet-field`, `quiet-message`, `quiet-valid`, `quiet-pick`,
 * `quiet-cancel`, `quiet-late`, `quiet-text`), made by spec/make-reply-vectors.py, and the rules the vectors don't pin:
 * the binding fixes of review round 2b (H1 a leave note doesn't drop a clock, M7 "Kept by" members only, M8 names told
 * apart, M9 positions follow the timer and high-risk mode) and rules Q6, Q8, Q10, Q12, QW1. The iPhone's
 * QuietVectorsTests checks the same vectors.
 */
class QuietVectorsTest {
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = Hex.encode(b)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private val vectors: List<Map<String, String>> =
        File("../spec/test-vectors.txt").readText(Charsets.UTF_8).split(Regex("\n\\s*\n")).map { block ->
            block.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore(":") to line.substringAfter(":").removePrefix(" ") }
        }.filter { it.isNotEmpty() }
    private fun of(kind: String) = vectors.filter { it["kind"] == kind }
    private val anna = "1111111111111111"; private val ben = "2222222222222222"; private val cara = "3333333333333333"
    private val t0 = 1_794_000_000_000L; private val min = 60_000L; private val hour = 3_600_000L

    @Test fun vectorCounts() {
        assertEquals(5, of("quiet-field").size); assertEquals(1, of("quiet-message").size); assertEquals(8, of("quiet-valid").size)
        assertEquals(4, of("quiet-pick").size); assertEquals(10, of("quiet-cancel").size); assertEquals(8, of("quiet-late").size)
        assertEquals(11, of("quiet-text").size)
    }

    @Test fun theField() {
        for (v in of("quiet-field")) {
            val f = unhex(v["field"]!!)
            // Framed after the text: the empty 4th field, then the 5th, each behind its 2-byte length.
            assertEquals("$v", v["framed"], "0000" + "%04x".format(f.size) + v["field"])
            if (v["reads"] == "clock") {
                val d = v["deadline"]!!.toLong(); val ts = v["ts"]!!.toLong()
                assertEquals("$v", d, QuietClock.raw(f)); assertEquals(v["valid"] == "yes", QuietClock.valid(d, ts))
                assertEquals(Notice.Clock(d), Notice.read(f, ts))
                assertEquals("what this app writes", v["field"], hex(QuietClock.field(d)))
            } else { assertNull("$v", QuietClock.raw(f)); assertNull(Notice.read(f, t0)) }
        }
    }

    /** The sealed clock message: what readers with the feature make of it, what readers without it show, and this app's own sealing, byte for byte. */
    @Test fun theClockMessage() {
        val v = of("quiet-message").single()
        val secret = unhex(v["secret"]!!); val g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        val position = v["position"]!!.toInt(); val number = v["number"]!!.toInt(); val ts = v["ts"]!!.toLong(); val d = v["deadline"]!!.toLong()
        val mk = MessageKeys.of(secret, listOf(v["member_0"]!!, v["member_1"]!!)).key(position, number)!!
        val o = g.openWith(mk, position, number, Packet(v["id"]!!, v["wire_ts"]!!.toLong(), 16, unhex(v["cipher"]!!)))!!
        assertEquals(v["text"], o.message.text); assertEquals(v["text"], String(unhex(v["text_hex"]!!), Charsets.UTF_8))
        assertEquals(ts, o.message.ts); assertEquals(v["sender"], o.message.sender); assertEquals(v["name"], o.message.name)
        assertEquals(Notice.Clock(d), o.message.notice); assertEquals(v["fifth"], hex(o.fifth!!)); assertEquals(v["fifth"], hex(QuietClock.field(d)))
        assertEquals(QuietClock.text(d, ts), o.message.text) // "1 hour 30 minutes"
        assertEquals(hex(sha(unhex(v["signed_message"]!!))), hex(o.digest)); assertEquals(v["mark"], About.Target.of(o).mark)
        // `shown: card`: an ordinary message here, alerted, confirmed and on LoRa; the card comes from the field.
        assertEquals("card", v["shown"]); assertFalse(o.message.silent); assertFalse(o.message.offRadio); assertNull(o.message.about)
        assertEquals(QuietClock.Effect.Clock(d), QuietClock.effect(o.message))
        // `older`: an app without the feature reads the 5th field as none and shows the text.
        assertEquals(v["older"], o.said); assertNull(CheckIn.of(o.said))
        // This app seals the same message to the same bytes.
        val again = g.sealWith(mk, position, number, v["id"]!!, ts, v["sender"]!!, v["name"]!!, QuietClock.text(d, ts), iv = unhex(v["iv"]!!),
            fifth = QuietClock.field(d)) { unhex(v["signature"]!!) }
        assertEquals(v["cipher"], hex(again))
        // This phone's copy keeps the field: the copy is a card again after a restart.
        val key = ByteArray(32) { 3 }
        val copy = History.open(key, v["id"]!!, History.seal(key, v["id"]!!, "00112233445566778899aabbccddeeff", "e", o, position, number)!!)!!
        assertEquals(Notice.Clock(d), copy.opened.message.notice); assertEquals(v["fifth"], hex(copy.opened.fifth!!)); assertEquals(v["text"], copy.opened.message.text)
    }

    @Test fun whichDeadlinesCount() {
        for (v in of("quiet-valid")) {
            val ts = v["ts"]!!.toLong(); val d = v["deadline"]!!.toLong(); val valid = v["valid"] == "yes"
            assertEquals("$v", valid, QuietClock.valid(d, ts))
            assertEquals("$v", if (valid) Notice.Clock(d) else null, Notice.read(QuietClock.field(d), ts))
        }
    }

    private fun <T> orders(xs: List<T>): List<List<T>> = if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> orders(xs - x).map { listOf(x) + it } }

    @Test fun whichClockCounts() {
        for (v in of("quiet-pick")) {
            val clocks = v.filterKeys { it.startsWith("clock_") }.mapKeys { it.key.removePrefix("clock_") }.mapValues { it.value.split(" ") }
            val now = clocks.values.maxOf { it[0].toLong() } + min
            val winner = clocks.getValue(v["counts"]!!)
            // The vector's arrival order, and every other: arrival order doesn't matter.
            for (order in listOf(v["arrival"]!!.split(",")) + orders(clocks.keys.toList())) {
                val book = QuietBook()
                for (name in order) clocks.getValue(name).let { c -> book.take(ben, c[1], c[2], c[0].toLong(), c[3].toLong(), null, now, false) }
                val held = book.clock(ben)!!
                assertEquals("$v $order", winner[1], held.id); assertEquals(winner[2], held.mark); assertEquals(winner[0].toLong(), held.ts)
                assertEquals("a stop leaves no clock", winner[3] != "0", book.armed(ben))
            }
        }
    }

    @Test fun whatCancels() {
        for (v in of("quiet-cancel")) {
            val clockTs = v["clock_ts"]!!.toLong(); val now = 10_000L
            val book = QuietBook().apply { take(ben, "e608000000000101", "00000000000000aa", clockTs, clockTs + hour, null, now, false) }
            val ts = v["ts"]!!.toLong(); val bytes = unhex(v["text_hex"]!!); val text = String(bytes, Charsets.UTF_8); val fifth = v["fifth"]?.let(::unhex)
            assertEquals(v["text"], text)
            val m = Message("e608000000000102", ts, ben, "Bea", text, group = "g", about = About.read(fifth, bytes), notice = Notice.read(fifth, ts))
            when (val e = QuietClock.effect(m)) {
                is QuietClock.Effect.Clock -> book.take(ben, m.id, "00000000000000bb", ts, e.deadline, null, now, false)
                QuietClock.Effect.CheckIn -> book.checkIn(ben, ts, now, false)
                null -> {}
            }
            assertEquals("$v", v["cancels"] == "yes", !book.armed(ben))
            if (v["what"] == "check-in") assertEquals("$v", v["cancels"] == "yes", QuietClock.cancels(text, ts, clockTs))
            if (v["what"] in listOf("report", "reaction", "text", "ask")) assertNull("$v", QuietClock.effect(m))
        }
    }

    @Test fun whatAPhoneDoes() {
        for (v in of("quiet-late")) {
            val got = QuietClock.due(v["deadline"]!!.toLong(), v["learned"]!!.toLong(), v["now"]!!.toLong(), v["armed"] == "yes")
            val word = when (got) { is QuietClock.Due.At -> "at-deadline"; QuietClock.Due.Now -> "now"; QuietClock.Due.Late -> "late"; QuietClock.Due.None -> "none" }
            assertEquals("$v", v["does"], word)
            if (got is QuietClock.Due.At) assertEquals(v["deadline"]!!.toLong(), got.at)
        }
    }

    @Test fun theText() {
        for (v in of("quiet-text")) {
            val ts = v["ts"]!!.toLong(); val d = v["deadline"]!!.toLong(); val spot = v["spot"]?.let { Spot.last("📍 $it")!! }
            val text = QuietClock.text(d, ts, spot)
            assertEquals("$v", v["text"], text); assertEquals(v["text_hex"], hex(text.toByteArray(Charsets.UTF_8))); assertEquals(v["bytes"]!!.toInt(), text.toByteArray(Charsets.UTF_8).size)
            assertNull("never a check-in", CheckIn.of(text))
            assertEquals(spot, QuietClock.spot(text)) // the only thing ever read from it
        }
    }

    // MARK: - The rules the vectors don't pin

    private fun clockBook(member: String = ben, ts: Long = t0, minutes: Int = 60, spot: Spot? = null, now: Long = t0, high: Boolean = false) =
        QuietBook().apply { take(member, "e608000000000201", "00000000000000aa", ts, ts + minutes * min, spot, now, high) }

    @Test fun aLeaveNoteDoesNotEndAClock() { // H1, rule Q9
        val book = clockBook()
        val leaving = LeaveBook().apply { take(ben, t0 + min, 5, t0 + min) }
        val members = setOf(anna, cara) // Ben was removed after his note
        assertFalse(book.sweep(t0 + 2 * min, false) { it in members || leaving.left(it) })
        assertTrue("still armed after the note and the removal", book.armed(ben))
        assertEquals(QuietClock.Due.Now, book.alerts(t0 + 61 * min, anna).single().second)
        // Removed without a note: dropped, without an alert.
        val other = clockBook()
        assertTrue(other.sweep(t0 + 2 * min, false) { it in members })
        assertNull(other.clock(ben)); assertTrue(other.alerts(t0 + 61 * min, anna).isEmpty())
    }

    @Test fun whatCountsForClocks() { // rule Q1
        val e5 = Keyring.Epoch(5, t0 - 100 * hour, ByteArray(32) { 5 }, listOf(anna, ben, cara), listOf(anna), by = anna, rank = Keyring.BY_ADMIN)
        val ring = Keyring().also { it.add(e5) }
        assertTrue(QuietClock.counts(ben, true, e5, ring, false))
        assertFalse("not checked yet", QuietClock.counts(ben, null, e5, ring, false))
        assertFalse("forged", QuietClock.counts(ben, false, e5, ring, false))
        assertFalse("dated after their leave note", QuietClock.counts(ben, true, e5, ring, true))
        assertFalse("a Wi-Fi board writes none", QuietClock.counts("web-$ben", true, e5, ring, false))
        assertFalse("not in the group", QuietClock.counts("4444444444444444", true, e5, ring, false))
        assertFalse("no epoch opened it", QuietClock.counts(ben, true, null, ring, false))
        assertNull("never on the Public channel", QuietClock.effect(Message("x", t0, ben, "Ben", "✅ I'm safe", public = true)))
        assertEquals(QuietClock.Effect.CheckIn, QuietClock.effect(Message("x", t0, ben, "Ben", "🆘 I need help", group = "g")))
    }

    @Test fun reachedMembersOnly() { // M7, rule Q14; "Reached" since the Fable review's decision A
        val book = clockBook(member = anna)
        val id = book.clock(anna)!!.id
        assertTrue(book.addKeeper(anna, id, ben)); assertTrue(book.addKeeper(anna, id, "web-$cara"))
        assertFalse("not twice", book.addKeeper(anna, id, ben)); assertFalse("not itself", book.addKeeper(anna, id, anna))
        assertFalse("another message", book.addKeeper(anna, "e608000000000999", "4444444444444444"))
        assertEquals(listOf(ben, cara), book.clock(anna)!!.keepers)
        // Kept in the record: it outlives the message, and survives a restart.
        assertEquals(listOf(ben, cara), QuietBook.parse(book.saved).clock(anna)!!.keepers)
        val leaving = LeaveBook().apply { take(cara, t0, 5, t0) }
        assertEquals("only members now, not who left", listOf(ben), book.keptBy(anna) { it in setOf(anna, ben, cara) && !leaving.left(it) })
        assertEquals("nor who was removed", listOf(ben), book.keptBy(anna) { it in setOf(anna, ben) })
        // A receipt says the message reached a phone, not that the phone keeps the clock (decision A): the spec's words.
        assertEquals("Not confirmed by any phone yet. Until a phone has it, nobody can be alerted.", QuietWords.reached(emptyList()))
        assertEquals("Reached Marco's phone so far", QuietWords.reached(listOf("Marco")))
        assertEquals("Reached Marco's and Anna's phones so far", QuietWords.reached(listOf("Marco", "Anna")))
        assertEquals("Reached Marco's, Anna's and 3 more phones so far", QuietWords.reached(listOf("Marco", "Anna", "Ben", "Cara", "Dan")))
        for (n in 0..5) assertFalse("never \"kept\"", "kept" in QuietWords.reached(List(n) { "P$it" }).lowercase())
        // A newer clock of this phone starts its own list: receipts name that message.
        book.take(anna, "e608000000000202", "00000000000000bb", t0 + min, t0 + 61 * min, null, t0 + min, false)
        assertTrue(book.clock(anna)!!.keepers.isEmpty())
    }

    @Test fun namesToldApart() { // M8, rule Q13
        val emoji = listOf("🐶", "🐱", "🦁", "🐴", "🐸")
        assertEquals("Anna", QuietWords.who("Anna", checked = true, shared = false, emoji = emoji))
        assertEquals("Anna (not checked)", QuietWords.who("Anna", checked = false, shared = true, emoji = emoji))
        assertEquals("Anna · 🐶🐱🦁🐴", QuietWords.who("Anna", checked = true, shared = true, emoji = emoji))
        val words = listOf(QuietWords.alertTitle("Anna", false, "18:30"), QuietWords.alertBody("Anna", "17:30", true, "18:02"), QuietWords.started("Anna"),
            QuietWords.startedSub("Anna", "18:30"), QuietWords.overdue("18:30"), QuietWords.banner("Anna", "My group"), QuietWords.HIDDEN_BODY, QuietWords.LEAD, QuietWords.SUB,
            QuietWords.startedSub(null, "18:30"), QuietWords.alertsOffSub("Anna", "18:30"), QuietWords.notKeeping(), QuietWords.ranOut("18:30"), QuietWords.reached(emptyList()),
            QuietWords.reached(listOf("Marco", "Anna")), QuietWords.SUBTITLE, QuietWords.sent(true), QuietWords.safeSent(true))
        for (w in words) for (n in QuietWords.NEVER) assertFalse("\"$w\" says \"$n\"", w.lowercase().contains(n))
        assertEquals("No check-in from Anna has reached this phone since 17:30. Anna's phone may just be out of reach. Last position known: 18:02. This phone learned of it late.",
            QuietWords.alertBody("Anna", "17:30", true, "18:02"))
        assertEquals("Anna's check-in clock ran out at 18:30", QuietWords.alertTitle("Anna", true, "18:30"))
    }

    @Test fun positionsFollowTheTimerAndHighRisk() { // M9, rule Q10
        val spot = Spot(45.4642, 9.19, 12)
        val book = clockBook(spot = spot)
        assertEquals(spot, book.clock(ben)!!.spot)
        assertFalse("not yet: the timer hasn't deleted the message", book.sweep(t0 + 59 * min, false, positionMs = hour) { true })
        assertTrue(book.sweep(t0 + hour, false, positionMs = hour) { true }); assertNull(book.clock(ben)!!.spot)
        assertTrue("the clock stays", book.armed(ben))
        val high = clockBook(spot = spot); assertTrue(high.sweep(t0 + min, true) { true }); assertNull(high.clock(ben)!!.spot)
        assertNull("none kept in high-risk mode", clockBook(spot = spot, high = true).clock(ben)!!.spot)
        // A check-in takes the position away too; the record stays, without it, so an older copy can't arm it again.
        val checked = clockBook(spot = spot); assertTrue(checked.checkIn(ben, t0 + min, t0 + min, false)); assertNull(checked.clock(ben)!!.spot); assertFalse(checked.armed(ben))
        assertFalse(checked.take(ben, "e608000000000200", "00000000000000ff", t0 - min, t0 + 59 * min, spot, t0 + 2 * min, false))
        // Saved as the iPhone saves it.
        val saved = clockBook(spot = spot).saved
        assertEquals(listOf("c $ben e608000000000201 00000000000000aa $t0 ${t0 + hour} $t0 0 0 45.46420 9.19000 12 -"), saved)
        assertEquals(saved, QuietBook.parse(saved).saved)
    }

    @Test fun howLongARecordLasts() { // rule Q10
        assertTrue(QuietBook.keeps(t0, t0 + 36 * hour - 1, false)); assertFalse(QuietBook.keeps(t0, t0 + 36 * hour, false))
        assertFalse(QuietBook.keeps(t0, t0 + 24 * hour, true))
        val book = clockBook()
        assertTrue(book.sweep(t0 + 36 * hour, false) { true }); assertTrue(book.isEmpty)
        assertFalse("not taken again once its record would have gone", QuietBook().take(ben, "e608000000000201", "00000000000000aa", t0, t0 + hour, null, t0 + 36 * hour, false))
    }

    @Test fun whatPeopleShow() { // rule Q6
        val book = clockBook()
        assertEquals(QuietBook.State.Running(t0 + hour), book.state(ben, t0 + min))
        assertEquals(QuietBook.State.RanOut(t0 + hour), book.state(ben, t0 + 2 * hour))
        assertNull("12 hours after the deadline: nothing", book.state(ben, t0 + hour + 12 * hour + 1))
        book.checkIn(ben, t0 + 70 * min, t0 + 70 * min, false)
        assertEquals(QuietBook.State.CheckedIn(t0 + 70 * min, t0 + hour), book.state(ben, t0 + 2 * hour))
        val early = clockBook(); early.checkIn(ben, t0 + min, t0 + min, false); assertNull("checked in in time: nothing", early.state(ben, t0 + 2 * min))
    }

    @Test fun alertsAtMostEveryTenMinutes() { // rule Q12
        val book = clockBook()
        val c = book.clock(ben)!!
        assertEquals(t0 + hour, book.postableAt(c, false, t0 + hour)); book.didPost(c, t0 + hour)
        val newer = clockBook(ts = t0 + 2 * min).clock(ben)!!
        assertEquals("one due meanwhile comes when the 10 minutes are up", t0 + hour + 10 * min, book.postableAt(newer, false, t0 + hour + min))
        assertNull("a backdated clock learned late: never again", book.postableAt(c.copy(ts = t0 - min), true, t0 + 2 * hour))
        assertEquals(t0 + 2 * hour, book.postableAt(newer, true, t0 + 2 * hour))
        // One clock per member, at most 100 records per group: the oldest goes.
        val many = QuietBook()
        for (i in 0..100) many.take("%016x".format(i), "e608000000000201", "00000000000000aa", t0 + i, t0 + hour, null, t0 + 200, false)
        assertEquals(100, many.clocks.size); assertNull(many.clock("%016x".format(0)))
        // This phone's own clock never alerts it.
        assertTrue(clockBook(member = anna).alerts(t0 + 2 * hour, anna).isEmpty())
    }

    @Test fun theWritersChoices() { // rules Q8, QW1
        assertEquals(t0 + 20 * min, QuietClock.remindAt(t0, t0 + 30 * min)); assertEquals(t0 + 10 * min, QuietClock.remindAt(t0, t0 + 20 * min))
        assertNull(QuietClock.remindAt(t0, t0 + 19 * min)); assertNull(QuietClock.remindAt(t0, 0))
        assertEquals(t0 + 90 * min, QuietClock.added(t0 + hour, t0)); assertEquals(t0 + 2 * hour + 30 * min, QuietClock.added(t0 + hour, t0 + 2 * hour))
        assertEquals("at most 24 hours from now", t0 + 24 * hour, QuietClock.added(t0 + 24 * hour, t0))
        assertTrue(QuietClock.valid(QuietClock.added(t0 + 24 * hour, t0), t0))
        assertEquals(listOf("30 min", "1 hour", "3 hours", "8 hours"), QuietClock.CHOICES.map(QuietWords::chip))
        assertEquals("This phone isn't let in, so it can't send a clock to My group.", QuietWords.cantWrite("My group"))
        assertEquals(QuietAlerts.id("g", ben), QuietAlerts.id("g", ben)); assertNotEquals(QuietAlerts.id("g", ben), QuietAlerts.id("g", anna))
        assertFalse("neither id sits in the alert's name", QuietAlerts.id("g", ben).contains(ben))
    }
}
