package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test

/**
 * Finder (round 2c, decisions in round2c-finder-decisions.md): the finder note in the 5th field, who writes it and who
 * reads it, radio first, the person's note, the re-send after a restart, the band logic over a list of readings, and the
 * screen's words. The same cases the iPhone writes as vectors `kind: finder-field` and `kind: finder-bands`; the desktop
 * runs this file too.
 */
class FinderTest {
    private val code = "test-group-1"
    private val group by lazy { GroupCrypto(code) }
    private val sig = ByteArray(64) { 7 }
    private val iv = ByteArray(12) { 9 }
    private val link = "a1b2c3d4e5f6"
    private val note = byteArrayOf(0x06, 0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte())
    private val sender = "1111111111111111"
    private fun hex(b: ByteArray) = Hex.encode(b)
    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    /** A group message with these fields, sealed with the group's key and opened again (signature not checked here). */
    private fun roundTrip(text: String, fifth: ByteArray?): Message {
        val id = "00000000000000f1"; val ts = 1_700_000_000_000L
        val c = group.seal(id, ts, sender, "Marco", text, iv = iv, fifth = fifth) { sig }
        return group.openSigned(Packet(id, ts, 16, c))!!.message
    }

    // MARK: - The field (vectors finder-field)

    @Test fun theNoteIsTypeSixAndTheSixBytesOfTheLinkId() {
        assertEquals(0x06.toByte(), FinderNote.TYPE)
        assertEquals(7, FinderNote.SIZE)
        assertEquals("06a1b2c3d4e5f6", hex(FinderNote.field(link)!!))
        assertEquals(11, FinderNote.COST)
        for (bad in listOf("", "a1b2c3d4e5", "a1b2c3d4e5f6a7", "A1B2C3D4E5F6", "a1b2c3d4e5fg")) assertNull(bad, FinderNote.field(bad))
    }

    /** F-V1: a help check-in with the note, the 4th field empty: readers report the link id; the bytes after the text are exactly 00 00 00 07 06 a1 b2 c3 d4 e5 f6. */
    @Test fun aHelpCheckInCarriesTheLinkId() {
        val text = CheckIn.HELP.text(15, needs = setOf(Need.TRAPPED))
        val fields = GroupCrypto.fields(sender, "Marco", text, null, null, null, raw = note)
        assertTrue(hex(fields).endsWith(hex(utf8(text)) + "0000" + "0007" + "06a1b2c3d4e5f6"))
        val m = roundTrip(text, note)
        assertEquals(link, m.finder)
        assertEquals(text, m.text)
        assertNull(m.about); assertNull(m.notice); assertNull(m.photo); assertNull(m.voice)
    }

    /** Sealed with a member's own message key (how phones write today), the note reads the same. */
    @Test fun withAMessageKeyToo() {
        val secret = ByteArray(32) { 0x11 }; val members = listOf("0000000000000000", sender)
        val epoch = GroupCrypto.fromSecret(MessageKeys.outer(secret)); val mk = MessageKeys.of(secret, members).key(1, 3)!!
        val c = epoch.sealWith(mk, 1, 3, "00000000000000f2", 77, sender, "Marco", "🆘 I need help", iv = iv, fifth = note) { sig }
        assertEquals(link, epoch.openWith(mk, 1, 3, Packet("00000000000000f2", 77, 16, c), at = 77)!!.message.finder)
    }

    /** F-V2: nothing but exactly 7 bytes of type 0x06 on a `🆘` text is a note; the message is otherwise normal. */
    @Test fun anythingElseIsNoNote() {
        val help = "🆘 I need help · 🔋 15%"
        val bad = listOf(
            byteArrayOf(0x06) + ByteArray(5) { 1 },          // too short
            byteArrayOf(0x06) + ByteArray(7) { 1 },          // too long
            byteArrayOf(0x03) + note.copyOfRange(1, 7),      // the timer's type
            byteArrayOf(0x04) + note.copyOfRange(1, 7),      // the clock's type
            byteArrayOf(0x07) + note.copyOfRange(1, 7),      // a type nobody knows yet
            ByteArray(0),                                    // empty: padding
        )
        for (f in bad) { val m = roundTrip(help, f); assertNull(hex(f), m.finder); assertEquals(help, m.text) }
        for (text in listOf("✅ I'm safe · 🔋 80%", "👤 Grandma needs help", "❓ Is everyone safe?", "I'm with Marco", "help 🆘")) {
            val m = roundTrip(text, note); assertNull(text, m.finder); assertEquals(text, m.text)
        }
        assertNull("not on the Public channel", roundTrip(help, note).onPublic().finder)
        assertNull(FinderNote.read(note, utf8("✅ I'm safe")))
        assertEquals(link, FinderNote.read(note, utf8("🆘")))
    }

    /** A reader that knows only the 4th field (before round 2a) and the 5th-field readers of other types show the check-in as before. */
    @Test fun olderReadersSeeTheCheckIn() {
        assertNull(About.read(note, utf8("🆘 I need help")))
        assertNull(Notice.read(note, 77))
        assertNull(GroupTimer.read(note)); assertFalse(LeaveNote.read(note)); assertNull(QuietClock.read(note, 77))
        val m = roundTrip("🆘 I need help", note)
        assertEquals(CheckIn.HELP, CheckIn.of(m.text)); assertEquals("🆘 I need help", m.text)
    }

    // MARK: - Who writes it (F2, F3, decision 2)

    @Test fun onlyOwnHelpCheckInsInAGroup() {
        val help = CheckIn.HELP.text(40)
        assertTrue(FinderNote.wanted(help, public = false))
        assertTrue(FinderNote.wanted(CheckIn.HELP.text(40, null, setOf(Need.INJURED), 2), public = false))
        assertFalse(FinderNote.wanted(help, public = true))
        assertFalse(FinderNote.wanted(help, public = false, board = true))
        assertFalse(FinderNote.wanted(help, public = false, photo = true))
        assertFalse(FinderNote.wanted(help, public = false, voice = true))
        assertFalse("a reply has its own 5th field", FinderNote.wanted(help, public = false, about = true))
        for (t in listOf(CheckIn.SAFE.text(40), Vouch("Grandma", CheckIn.HELP).text, AreYouSafe().text, AreYouSafe("Marco").text, FinderWords.withThem("Marco"), "Bridge is out"))
            assertFalse(t, FinderNote.wanted(t, public = false))
    }

    @Test fun theSwitchAndHighRiskMode() {
        assertTrue(FinderPrefs.writes(findable = true, highRisk = false, trial = true, bluetooth = true))
        assertFalse("off in high-risk mode whatever the switch says", FinderPrefs.writes(findable = true, highRisk = true, trial = true, bluetooth = true))
        assertFalse(FinderPrefs.writes(findable = false, highRisk = false, trial = true, bluetooth = true))
        assertFalse("a computer has no Bluetooth links", FinderPrefs.writes(findable = true, highRisk = false, trial = true, bluetooth = false))
    }

    /**
     * Decision B of the final review (2026-11-23): while the trial switch is off (the release default), "I need help"
     * carries no link id (and isn't sent again after a restart, which asks the same), and Settings doesn't offer the switch.
     */
    @Test fun nothingOfTheNoteWhileTheTrialIsOff() {
        assertFalse("no note while the trial is off", FinderPrefs.writes(findable = true, highRisk = false, trial = false, bluetooth = true))
        assertFalse(FinderPrefs.writes(findable = true, highRisk = true, trial = false, bluetooth = true))
        assertTrue("the Settings row on a phone with the trial on", FinderPrefs.offered(trial = true, bluetooth = true))
        assertFalse("hidden while the trial is off", FinderPrefs.offered(trial = false, bluetooth = true))
        assertFalse("never on a computer", FinderPrefs.offered(trial = true, bluetooth = false))
        // Reading a note doesn't depend on it.
        assertEquals(link, FinderNote.read(note, utf8("🆘 I need help")))
    }

    // MARK: - Radio first (F3, vectors finder-fit)

    @Test fun theNoteNeverKeepsACheckInOffARadio() {
        assertTrue(FinderNote.keeps(150, 161))
        assertTrue(FinderNote.keeps(152, 163))    // exactly 163 with the note
        assertFalse(FinderNote.keeps(163, 164))   // MeshCore's 163 bytes
        assertTrue(FinderNote.keeps(164, 175))    // past 163 either way
        assertTrue("163 is the only limit (spec F3)", FinderNote.keeps(233, 234))
        assertTrue(FinderNote.keeps(240, 251))
    }

    @Test fun radioFirstWithTodaysSizes() {
        // "🆘 I need help" from "Al": 40 bytes of fields. With a message key 153 bytes, 164 with the note: MeshCore keeps it without.
        val short = "🆘 I need help"
        assertEquals(16, utf8(short).size)
        assertNull(FinderNote.radioFirst(note, sender, "Al", short, keyed = true))
        // Sealed with the epoch's key it is 3 bytes smaller: 150, and 161 with the note, which still fits.
        assertArrayEquals(note, FinderNote.radioFirst(note, sender, "Al", short, keyed = false))
        // The spec's example check-in (85 bytes) from an 11-byte name: 231 bytes, past 163 either way, so with the note.
        val example = "🆘 I need help: injured, water · 👥 3 · 🔋 23% · 📍 45.46420,9.19000 ±12m"
        assertEquals(85, utf8(example).size)
        assertArrayEquals(note, FinderNote.radioFirst(note, sender, "Marco Rossi", example, keyed = true))
        assertArrayEquals(note, FinderNote.radioFirst(note, sender, "Marco Rossi 14", example, keyed = true))
        // A typical help check-in without a position (54 bytes) from a 5-byte name: past 163 either way, so with the note.
        val typical = CheckIn.HELP.text(23, null, setOf(Need.INJURED, Need.WATER), 3)
        assertArrayEquals(note, FinderNote.radioFirst(note, sender, "Marco", typical, keyed = true))
    }

    // MARK: - The person's note (F5, F6, vector F-V5)

    private fun msg(text: String, ts: Long, finder: String? = null, verified: Boolean = true, from: String = "s1") =
        Message("id$ts", ts, from, "Marco", text, group = code, verified = verified, finder = finder)
    private fun person(vararg m: Message) = Person.all(m.toList(), "me").first { it.id == "s1" }

    @Test fun aPersonsNoteIsTheOneInTheirLatestCheckIn() {
        val help = msg("🆘 I need help", 1, link)
        assertEquals(link, person(help).finder)
        assertNull("I'm safe ends it", person(help, msg("✅ I'm safe", 2)).finder)
        assertNull("a help check-in without the note ends it", person(help, msg("🆘 I need help", 3)).finder)
        assertEquals("other messages change nothing", link, person(help, msg("I'm under the stairs", 4)).finder)
        assertEquals("a newer note replaces it", "0a0b0c0d0e0f", person(help, msg("🆘 I need help", 5, "0a0b0c0d0e0f")).finder)
        assertNull("in any order", person(msg("✅ I'm safe", 2), help).finder)
        assertNull("not before the signature checked", person(msg("🆘 I need help", 1, link, verified = false)).finder)
        assertTrue(Person.all(listOf(msg("🆘 I need help", 1, link).onPublic()), "me").isEmpty())
        val report = Person.all(listOf(msg(Vouch("Grandma", CheckIn.HELP).text, 6)), "me").first { it.isReport }
        assertNull("a report about someone never", report.finder)
        assertTrue(findable(person(help), trial = true))
        assertFalse("only while the trial is on", findable(person(help), trial = false))
        assertFalse(findable(person(help, msg("✅ I'm safe", 2)), trial = true))
    }

    /** This phone's readable copy of a message keeps the note, so the button is still there after this phone restarts. */
    @Test fun theReadableCopyKeepsTheNote() {
        val key = ByteArray(32) { 3 }
        val o = GroupCrypto.Opened(Message("00000000000000f3", 77, sender, "Marco", "🆘 I need help", finder = link), sig, ByteArray(32) { 5 }, null)
        val blob = History.seal(key, "00000000000000f3", "00112233445566778899aabbccddeeff", "e1", o, 1, 3)!!
        val back = History.open(key, "00000000000000f3", blob)!!.opened
        assertEquals(link, back.message.finder)
        assertNull(back.message.about); assertNull(back.aboutField)
    }

    // MARK: - Re-send after a restart (decision 3)

    @Test fun helpGoesAgainOnceAfterARestart() {
        val now = 1_800_000_000_000L; val h = 3_600_000L; val min = 60_000L
        assertTrue(FinderResend.due(CheckIn.HELP, now - h, now, 0))
        assertFalse(FinderResend.due(CheckIn.SAFE, now - h, now, 0))
        assertFalse(FinderResend.due(null, now - h, now, 0))
        assertTrue(FinderResend.due(CheckIn.HELP, now - 24 * h + 1, now, 0))
        assertFalse("24 hours old", FinderResend.due(CheckIn.HELP, now - 24 * h, now, 0))
        assertFalse("from the future", FinderResend.due(CheckIn.HELP, now + min, now, 0))
        assertFalse("at most every 10 minutes", FinderResend.due(CheckIn.HELP, now - h, now, now - 9 * min))
        assertTrue(FinderResend.due(CheckIn.HELP, now - h, now, now - 10 * min))
        assertTrue("a clock set back doesn't hold it off", FinderResend.due(CheckIn.HELP, now - h, now, now + h))
        assertFalse("never while an own clock message newer than the call is held", FinderResend.due(CheckIn.HELP, now - h, now, 0, ownClockAfter = true))
        assertTrue(FinderResend.due(CheckIn.HELP, now - h, now, 0, ownClockAfter = false))
        val old = "🆘 I need help: injured, water · 👥 3 · 🔋 23% · 📍 45.46420,9.19000 ±12m"
        assertEquals("🆘 I need help: injured, water · 👥 3 · 🔋 40%", FinderResend.text(old, 40, null))
        assertEquals("🆘 I need help", FinderResend.text("🆘 I need help · 🔋 9%", null, null))
    }

    /**
     * Final review 2026-11-23, item 3: Anna asks for help at 10:00, starts an 8-hour check-in clock at 10:30, and her app
     * starts again at 11:00. Her newest check-in is still the help call, under a day old, but her own clock is newer: no
     * re-send, since a check-in dated 11:00 would stop that clock on every phone (rule Q4).
     */
    @Test fun aResendNeverStopsTheSendersNewerClock() {
        val ten = 1_800_000_000_000L; val min = 60_000L; val anna = "1111111111111111"
        val book = QuietBook()
        assertTrue(book.checkIn(anna, ten, ten, highRisk = false))                                        // 🆘 at 10:00
        val at = ten + 30 * min
        assertTrue(book.take(anna, "00000000000000c1", "0011223344556677", at, at + 8 * 60 * min, null, at, highRisk = false)) // ⏳ at 10:30
        assertTrue(book.armed(anna))
        val restart = ten + 60 * min
        val clockAfter = book.clock(anna)!!.ts > ten
        assertTrue(clockAfter)
        assertFalse("no re-send while her newer clock is held", FinderResend.due(CheckIn.HELP, ten, restart, 0, ownClockAfter = clockAfter))
        // Without the clock it would go, as before.
        assertTrue(FinderResend.due(CheckIn.HELP, ten, restart, 0, ownClockAfter = false))
        // What the re-send would have done: a check-in dated 11:00 stops her clock.
        book.checkIn(anna, restart, restart, highRisk = false)
        assertFalse(book.armed(anna))
    }

    // MARK: - The band logic (vectors finder-bands)

    private fun bands(vararg r: Int?) = FinderMath.trace(r.mapIndexed { i, d -> i.toLong() to d }).map { it.band }
    private fun steps(vararg r: Int?) = FinderMath.trace(r.mapIndexed { i, d -> i.toLong() to d })
    private val F = FinderMath.Band.FAR; private val N = FinderMath.Band.NEAR; private val V = FinderMath.Band.VERY_CLOSE
    private val H = FinderMath.Band.HERE; private val X = FinderMath.Band.NOT_IN_REACH
    private fun run(n: Int, dbm: Int?) = Array(n) { dbm }

    @Test fun medianTicksAndValidReadings() {
        assertEquals(2.0, FinderMath.median(listOf(3, 1, 2)), 0.0)
        assertEquals(2.5, FinderMath.median(listOf(4, 1, 3, 2)), 0.0)
        assertEquals(-80.0, FinderMath.median(listOf(-80)), 0.0)
        assertEquals(0, FinderMath.ticks(null)); assertEquals(0, FinderMath.ticks(-95.0)); assertEquals(0, FinderMath.ticks(-110.0))
        assertEquals(5, FinderMath.ticks(-90.0)); assertEquals(18, FinderMath.ticks(-75.0)); assertEquals(36, FinderMath.ticks(-55.0)); assertEquals(36, FinderMath.ticks(-20.0))
        for (d in listOf(-110, -100, -60, -20)) assertTrue(FinderMath.valid(d))
        for (d in listOf(127, 0, -111, -19, 20)) assertFalse(FinderMath.valid(d))
    }

    @Test fun steadyAndFar() {
        val s = steps(*run(5, -90))
        assertEquals(List(5) { F }, s.map { it.band })
        assertTrue(s.all { it.ticks == 5 && it.smoothed == -90.0 })
        assertTrue("no trend before there are 5 seconds before (F12)", s.all { it.trend == null })
        assertEquals(FinderMath.Trend.STEADY, steps(*run(6, -90)).last().trend)
    }

    /** "Here" needs S at −60 or more for 3 seconds in a row: the first two are "Very close". */
    @Test fun hereNeedsThreeSeconds() {
        assertEquals(listOf(V, V, H, H), bands(*run(4, -55)))
        assertEquals("seconds count, also without a reading (S stays)", listOf(V, V, H), bands(-55, null, null))
        assertEquals(36, steps(*run(3, -55)).last().ticks)
    }

    /** One strong reading among weak ones moves nothing: the median takes it out. */
    @Test fun aSpikeIsNoHere() {
        assertEquals(List(10) { N }, bands(*run(6, -80), -50, *run(3, -80)))
        assertTrue(steps(*run(6, -80), -50, *run(3, -80)).all { it.smoothed == -80.0 })
    }

    /** Going down a band needs S 3 dB under its edge: S settles near −84 and stays "Near"; at −90 it drops to "Far" once S is under −85. */
    @Test fun hysteresisGoingDown() {
        val r = arrayOf(*run(10, -75), *run(20, -84), *run(5, -90))
        val b = bands(*r)
        assertEquals(List(30) { N }, b.take(30))
        assertEquals(N, b[31]); assertEquals(F, b[32]); assertEquals(F, b[34])
        val s = steps(*r)
        assertTrue("S went under −82 and the band stayed", s[29].smoothed!! < -82.0)
    }

    /** Going up is at once: the first S at −82 or more is "Near". */
    @Test fun goingUpAtOnce() {
        val b = bands(*run(10, -90), *run(8, -80))
        assertEquals(List(16) { F }, b.take(16))
        assertEquals(N, b[16]); assertEquals(N, b[17])
    }

    /** No reading for 10 s: "Not in reach", nothing kept; a reading after that starts again from scratch. */
    @Test fun aTenSecondGap() {
        val s = steps(-70, -70, -70, *run(10, null), -70)
        assertEquals(List(12) { V }, s.take(12).map { it.band })
        assertEquals(X, s[12].band); assertNull(s[12].trend); assertNull(s[12].smoothed); assertEquals(0, s[12].ticks)
        assertEquals(V, s[13].band); assertEquals(-70.0, s[13].smoothed!!, 0.0)
        assertEquals("nothing yet", listOf(X, X), bands(null, null))
    }

    /** 127 and 0 (the "unavailable" values) and anything outside −110…−20 count as no reading. */
    @Test fun unavailableReadingsAreDropped() {
        assertEquals(List(4) { X }, bands(127, 0, -111, -19))
        assertEquals(List(3) { N }, bands(-80, 127, -80))
        assertTrue(steps(-80, 127, 0, -80).all { it.smoothed == -80.0 })
        assertEquals(X, bands(-80, *run(10, 127)).last())
        assertEquals(F, bands(-110).single()); assertEquals(V, bands(-20).single())
    }

    /** The trend: the readings of the last 5 s against the 5 s before, ±4 dB. */
    @Test fun theTrend() {
        fun trend(vararg r: Int?) = steps(*r).last().trend
        assertEquals(FinderMath.Trend.STRONGER, trend(*run(5, -90), *run(5, -80)))
        assertEquals(FinderMath.Trend.WEAKER, trend(*run(5, -80), *run(5, -90)))
        assertEquals(FinderMath.Trend.STEADY, trend(*run(5, -80), *run(5, -78)))
        assertEquals("exactly 4 dB", FinderMath.Trend.STRONGER, trend(*run(5, -84), *run(5, -80)))
        assertNull("nothing before: no trend", trend(*run(4, -90), -60))
        assertEquals(FinderMath.Trend.STRONGER, trend(*run(5, -90), null, null, -80, null, -80))
    }

    /** A walk towards the phone and back: every word on the way, as the screen shows them. */
    @Test fun aWalk() {
        val walk = arrayOf(-92, -91, -90, -88, -86, -84, -82, -80, -78, -76, -74, -72, -70, -68, -66, -64, -62, -60, -58, -57, -57, -57, -57, -60, -64, -68, -72, -76, -80)
        val words = steps(*walk).map { FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, it).word }
        assertEquals("Far", words.first()); assertTrue(words.contains("Near")); assertTrue(words.contains("Very close")); assertTrue(words.contains("Here"))
        // The words only ever step to a neighbour, and "Here" never comes before 3 readings at −60 or more.
        val order = listOf("Far", "Near", "Very close", "Here")
        words.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", kotlin.math.abs(order.indexOf(a) - order.indexOf(b)) <= 1 || (order.indexOf(b) < order.indexOf(a))) }
        assertTrue(words.indexOf("Here") >= walk.indexOfFirst { it >= -60 } + 2)
    }

    // MARK: - The words

    @Test fun theScreensWords() {
        assertEquals(listOf("Not in reach", "Far", "Near", "Very close", "Here"), FinderMath.Band.entries.map { it.word })
        assertEquals(listOf("Signal getting stronger", "Signal getting weaker", "Signal steady"), FinderMath.Trend.entries.map { it.line })
        assertEquals("Bluetooth can't tell direction, only stronger or weaker. Walls and people weaken it.", FinderWords.FINE_PRINT)
        assertEquals("Finding Marco", FinderWords.title("Marco"))
        assertEquals("I'm with Marco", FinderWords.withThem("Marco"))
        assertNull("never a check-in", CheckIn.of(FinderWords.withThem("Marco")))
        val near = FinderMath.Step(9, N, FinderMath.Trend.STRONGER, -75.0, 18)
        FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, near).let {
            assertEquals("Near", it.word); assertEquals("Keep going the way the signal gets stronger.", it.line); assertEquals("Signal getting stronger", it.trend)
            assertNull(it.note); assertEquals(2, it.level)
        }
        FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, FinderMath.Step(9, H, FinderMath.Trend.STEADY, -55.0, 36)).let { assertTrue(it.here); assertEquals(4, it.level); assertEquals("Signal steady", it.trend) }
        // The spec's lines, one per band (spec "What the screens say").
        assertEquals(listOf("Keep moving.", "Keep walking and watch the signal.", "Keep going the way the signal gets stronger.",
            "Within a few rooms. Call out and listen.", "Within a few steps. Look around."), FinderMath.Band.entries.map(FinderWords::line))
        assertNull("no trend line yet", FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, FinderMath.Step(3, F, null, -88.0, 5)).trend)
        FinderWords.shown("Marco", FinderReach.NONE, FinderRole.NONE, null).let { assertEquals("Not in reach", it.word); assertTrue(it.note!!.contains("linked by Bluetooth")) }
        FinderWords.shown("Marco", FinderReach.OTHER, FinderRole.NONE, null).let { assertEquals("Linked", it.word); assertTrue(it.note!!.contains("not over Bluetooth")) }
        FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.FAILED, near).let { assertEquals("Linked", it.word); assertTrue(it.note!!.contains("can't measure")) }
        FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, FinderMath.Step(9, X, null, null, 0)).let { assertEquals("This phone hasn't heard Marco's phone yet.", it.note) }
        // No metres anywhere (decision 4): no number with a unit, no "metre".
        val all = FinderReach.entries.flatMap { r -> FinderRole.entries.flatMap { ro -> listOf(null, near).map { FinderWords.shown("Marco", r, ro, it) } } }
            .flatMap { listOfNotNull(it.word, it.line, it.note, it.trend) } + FinderWords.FINE_PRINT + FinderWords.desktop("Marco")
        all.forEach { assertFalse(it, Regex("metre|meter|\\d\\s?m\\b|\\bft\\b").containsMatchIn(it)) }
    }

    @Test fun fakeReadingsForTheEmulator() {
        assertEquals(listOf(-90, null, -80), FinderFake.parse("-90, x,-80"))
        assertNull(FinderFake.parse("off")); assertNull(FinderFake.parse("-90,loud"))
    }
}
