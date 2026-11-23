package it.blackout.mesh

import android.content.Context

// Finder (round 2c): a group member walking nearby reads how strong the Bluetooth signal of someone who asked for help is.
// The rules here are pure, so the phone, the desktop and their unit tests run the same ones; the iPhone has the same
// numbers. What this file decides:
// - the finder note: the asker's current link id in the 5th field of their own "I need help" check-in (FinderNote);
// - the band logic: a list of signal readings in, the word and trend each second out (FinderMath);
// - the re-send of a help check-in after a restart (FinderResend), and the settings (FinderPrefs);
// - every word the Finder screen says (FinderWords).
// Nothing about a match or a reading is ever sent, stored or put in a receipt: Finder is local (rule F8).

/**
 * The finder note (decisions 1 and 2): in the 5th plaintext field, type `0x06`, then the sender's current link id as 6
 * raw bytes ([MeshEngine.linkId], 12 lowercase hex characters). Only in the sender's own `🆘` check-ins in a group,
 * never on the Public channel. A reader ignores it anywhere else, with another length, and from a packet that didn't
 * open with a group key and whose signature didn't check (the engine's [Message.verified], [Person.all]).
 */
object FinderNote {
    const val TYPE: Byte = 0x06
    /** Type (1), link id (6). */
    const val SIZE = 7
    /** What the 4th and 5th fields add to a check-in: an empty 4th (2), the 5th's length (2), the note (7). */
    const val COST = 2 + 2 + SIZE
    /** The LoRa limit a check-in is kept under first (MeshCore's 163 bytes, spec rule F3): reaching a radio matters more than Finder. */
    const val RADIO_LIMIT = MeshCore.PAYLOAD_MAX

    /** True for a link id as HELLO carries it: 12 lowercase hex characters. */
    fun isLinkId(s: String) = s.length == 12 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** The field for link id [linkId], or null if it isn't one. */
    fun field(linkId: String): ByteArray? = if (isLinkId(linkId)) byteArrayOf(TYPE) + Hex.decode(linkId) else null

    /**
     * What a reader takes from a 5th field ([field]: its bytes, null if the body has none): the link id, only for exactly
     * [SIZE] bytes of type `0x06` in a message whose text ([text], its bytes) starts with `🆘`. Null for anything else.
     */
    fun read(field: ByteArray?, text: ByteArray): String? {
        if (field == null || field.size != SIZE || field[0] != TYPE) return null
        if (CheckIn.of(String(text, Charsets.UTF_8)) != CheckIn.HELP) return null
        return Hex.encode(field.copyOfRange(1, SIZE))
    }

    /**
     * Rule F2: whether a message this phone writes may carry the note: its own `🆘` check-in in a group, with nothing else
     * in the 4th or 5th field. Never on the Public channel or from the Wi-Fi board, never a reply or reaction, a photo or a
     * voice note, never a `✅` check-in, a report about someone (`👤`) or a question (`❓`).
     */
    fun wanted(text: String, public: Boolean, board: Boolean = false, photo: Boolean = false, voice: Boolean = false, about: Boolean = false): Boolean =
        !public && !board && !photo && !voice && !about && CheckIn.of(text) == CheckIn.HELP

    /**
     * Rule F3, radio first: [note] for a check-in with these fields, or null when the note alone would push its packet past
     * 163 bytes (MeshCore) while it fits without the note. [keyed]: sealed with a message key, whose header adds 3 bytes.
     * A packet that doesn't fit either way keeps the note. Same as the iPhone's (vectors `kind: finder-fit`).
     */
    fun radioFirst(note: ByteArray, sender: String, name: String, text: String, keyed: Boolean): ByteArray? {
        val over = Packet.OVERHEAD + if (keyed) 3 else 0
        val without = over + GroupCrypto.fields(sender, name, text, null, null, null).size
        val with = over + GroupCrypto.fields(sender, name, text, null, null, null, raw = note).size
        return note.takeIf { keeps(without, with) }
    }

    /** F3 on whole packet sizes before padding: false when [without] fits 163 bytes and [with] doesn't. */
    fun keeps(without: Int, with: Int): Boolean = !(without <= RADIO_LIMIT && with > RADIO_LIMIT)
}

/** How this phone is linked to the asker's phone right now (rule F7). */
enum class FinderReach {
    /** A Bluetooth LE link that said HELLO with the note's link id: the signal can be read. */
    BLUETOOTH,
    /** Linked, but only over Wi-Fi Direct or a Wi-Fi network: no reading (Nearby and Wi-Fi give none). */
    OTHER,
    /** Not linked: not in reach, or the asker's app restarted since (a new link id). */
    NONE,
}

/** How this phone reads the signal on the Bluetooth link to the asker (debug line, and what the screen says). */
enum class FinderRole(val word: String) {
    NONE("none"),
    /** This phone dialed: it reads the signal on its own GATT client. */
    CENTRAL("central"),
    /** The asker dialed: this phone opened a read-only GATT client on the same connection (rule 5, unverified on real phones). */
    CLIENT("client-on-connection"),
    /** The asker dialed and the client on the same connection didn't open on this phone. */
    FAILED("can't read"),
    /** Debug builds: readings fed by hand ([FinderFake]). */
    FAKE("fake"),
}

/**
 * From signal readings to an honest reading (decision 4, brief F14 and F18 to F22). No metres anywhere. Pure, so the
 * phone, the desktop and the iPhone give the same word and trend for the same list of readings.
 *
 * Each second the screen takes at most one reading (dBm, or none); spec/FORMAT.md "Finder", rules F7 to F12, the same
 * as the iPhone's FinderMeter (vectors `kind: finder-bands`):
 * - F7: a reading outside −110…−20 dBm (127 and 0 are the "unavailable" values) counts as none.
 * - F8: readings 10 s old are forgotten; with no reading for 10 s the word is "Not in reach" and everything starts again.
 * - F9: at a second with a reading, m = the median of the readings of the last 5 seconds (the mean of the middle two
 *   for an even count); S = m for the first, else S = 0.3·m + 0.7·S before. At a second without a reading S stays.
 * - F10, F11: bands on S: below −82 Far, from −82 Near, from −70 Very close, from −60 Here. Going up is at once, except
 *   Here, which needs S ≥ −60 for 3 seconds in a row. Going down needs S more than 3 dB below the band's lower edge,
 *   and then goes to the level of S. Checked every second.
 * - F12: trend: the mean of the readings of the last 5 s against the mean of those of the 5 s before: +4 dB or more
 *   stronger, −4 dB or less weaker, else steady; none while either has no reading.
 * - Ring: round(clamp((S + 95) / 40, 0, 1) × 36) lit ticks; none when not in reach.
 */
object FinderMath {
    const val MIN_DBM = -110
    const val MAX_DBM = -20
    const val NEAR_FROM = -82
    const val VERY_CLOSE_FROM = -70
    const val HERE_FROM = -60
    const val MARGIN = 3.0
    /** Readings in a row with S ≥ [HERE_FROM] before "Here" shows. */
    const val HERE_HOLD = 3
    /** Seconds without a reading before "Not in reach"; older readings are forgotten. */
    const val GAP_S = 10
    const val MEDIAN_OF = 5
    const val TREND_S = 5
    const val TREND_DB = 4.0
    const val TICKS = 36

    /** [code]: the letter in vectors `kind: finder-bands`. */
    enum class Band(val word: String, val level: Int, val code: Char) {
        NOT_IN_REACH("Not in reach", 0, '-'), FAR("Far", 1, 'F'), NEAR("Near", 2, 'N'), VERY_CLOSE("Very close", 3, 'V'), HERE("Here", 4, 'H');
        companion object { fun of(level: Int) = entries.first { it.level == level } }
    }

    /** [code]: the sign in vectors `kind: finder-bands`. */
    enum class Trend(val line: String, val code: Char) { STRONGER("Signal getting stronger", '+'), WEAKER("Signal getting weaker", '-'), STEADY("Signal steady", '=') }

    /** What the screen shows at one second. [smoothed] is null while not in reach, [trend] also while there is none yet; [ticks] is 0 then. */
    data class Step(val second: Long, val band: Band, val trend: Trend?, val smoothed: Double?, val ticks: Int)

    /** F14: a reading worth keeping. */
    fun valid(dbm: Int) = dbm in MIN_DBM..MAX_DBM

    /** The median: the middle value, or the mean of the middle two for an even count. */
    fun median(xs: List<Int>): Double {
        require(xs.isNotEmpty())
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** F21: the lit ticks of the ring for smoothed value [s]. */
    fun ticks(s: Double?): Int = if (s == null) 0 else Math.round(((s + 95.0) / 40.0).coerceIn(0.0, 1.0) * TICKS).toInt()

    /** F10: the level a smoothed value falls in (1 Far to 4 Here). */
    private fun levelOf(s: Double): Int = when { s >= HERE_FROM -> 4; s >= VERY_CLOSE_FROM -> 3; s >= NEAR_FROM -> 2; else -> 1 }

    /** F11: the lower edge of a level, which a band is left downwards only [MARGIN] below. */
    private fun lowerEdge(level: Int): Double = when (level) { 4 -> HERE_FROM.toDouble(); 3 -> VERY_CLOSE_FROM.toDouble(); 2 -> NEAR_FROM.toDouble(); else -> Double.NEGATIVE_INFINITY }

    /** The readings of one Finder screen, second by second. Not thread-safe: one screen's ticker feeds it. */
    class Tracker {
        private val kept = ArrayDeque<Pair<Long, Int>>() // (second, dBm) of the valid readings of the last 10 s
        private var s: Double? = null
        private var level = 0 // 0: no band yet
        private var hold = 0  // seconds in a row with S at Here's edge or above

        /** Second [second] (one more than the last, or later), with the reading taken in it ([dbm]: null for none). */
        fun step(second: Long, dbm: Int?): Step {
            val fresh = dbm != null && valid(dbm)
            if (fresh) kept.addLast(second to dbm!!)
            while (kept.isNotEmpty() && second - kept.first().first >= GAP_S) kept.removeFirst() // F8: only the last 10 s
            if (kept.isEmpty()) { s = null; level = 0; hold = 0; return Step(second, Band.NOT_IN_REACH, null, null, 0) }
            // F9: at a second with a reading, the median of the last 5 seconds' readings, then the exponential average.
            if (fresh) {
                val m = median(kept.filter { second - it.first < MEDIAN_OF }.map { it.second })
                s = s?.let { 0.3 * m + 0.7 * it } ?: m
            }
            val now = s ?: kept.last().second.toDouble()
            // F11: Here only after 3 seconds in a row at its edge; up at once, down only past the margin and then to S's level.
            hold = if (now >= HERE_FROM) hold + 1 else 0
            val at = levelOf(now)
            val target = minOf(at, if (hold >= HERE_HOLD) 4 else 3)
            level = when {
                level == 0 || target > level -> target
                at < level && now < lowerEdge(level) - MARGIN -> at
                else -> level
            }
            return Step(second, Band.of(level), trend(second), now, ticks(now))
        }

        /** F12: the last 5 seconds' readings against the 5 before; none while either has no reading. */
        private fun trend(second: Long): Trend? {
            val recent = kept.filter { second - it.first < TREND_S }.map { it.second }
            val before = kept.filter { second - it.first >= TREND_S }.map { it.second }
            if (recent.isEmpty() || before.isEmpty()) return null
            val d = recent.average() - before.average()
            return when { d >= TREND_DB -> Trend.STRONGER; d <= -TREND_DB -> Trend.WEAKER; else -> Trend.STEADY }
        }
    }

    /** The steps for a list of (second, reading or null), in order: the same table the iPhone runs (vectors `kind: finder-bands`). */
    fun trace(readings: List<Pair<Long, Int?>>): List<Step> = Tracker().let { t -> readings.map { (sec, dbm) -> t.step(sec, dbm) } }
}

/**
 * Re-sending "I need help" after a restart (decision 3): the old note names a link id that is gone, so once per start,
 * if this phone's newest own check-in in a group is "I need help", under 24 hours old, no own clock message dated at or
 * after it is held there, and the last re-send there was 10 minutes ago or more, it goes again with a fresh battery level and
 * the new link id. Only while the note is written ([FinderPrefs.writes], the trial switch among it).
 */
object FinderResend {
    const val MAX_AGE_MS = 24 * 3_600_000L
    const val EVERY_MS = 10 * 60_000L
    /** Where the last re-send's time is kept per group: this prefix and the group's id, in the "mesh" settings. */
    const val PREF = "finderResend."

    /**
     * [newest]: this phone's newest own check-in in the group and its time; [lastResend]: 0 for never. [ownClockAfter]: an
     * own clock message dated at or after that check-in is held in the group ([clockAfter]) (its message, or the clock's record): no re-send
     * then, since a check-in dated after a clock message stops that clock on every phone (rule Q4), and a re-send nobody
     * chose must never stop the sender's own newer clock (final review 2026-11-23, item 3).
     */
    fun due(newest: CheckIn?, newestTs: Long, now: Long, lastResend: Long, ownClockAfter: Boolean = false): Boolean {
        if (newest != CheckIn.HELP || ownClockAfter) return false
        val age = now - newestTs
        if (age < 0 || age >= MAX_AGE_MS) return false
        return lastResend > now || now - lastResend >= EVERY_MS // a clock set back must not hold it off for long
    }

    /**
     * Whether this phone's own newest clock ([ownClock]: the `ts` of its clock message or record, null for none) holds a
     * re-send of the help call dated [helpTs] back ([due]'s `ownClockAfter`): at or after it, as the spec says (F6) and
     * the iPhone does. A clock message with the same millisecond as the call counts too (re-check N6). Pure: tested.
     */
    fun clockAfter(ownClock: Long?, helpTs: Long): Boolean = ownClock != null && ownClock >= helpTs

    /** The text of the re-sent check-in: the same needs and head count as [old], a fresh battery level and position. */
    fun text(old: String, battery: Int?, spot: Spot?): String = CheckIn.HELP.text(battery, spot, CheckIn.needs(old), CheckIn.people(old))
}

/** The Finder settings, in the "mesh" settings. */
object FinderPrefs {
    /** "Let my group find this phone" (decision 2): on unless turned off. */
    const val FINDABLE = "finderFindable"
    /** The trial switch (decision 5): the Finder screen and its buttons show only while it is on. */
    const val TRIAL = "finderTrial"

    private fun prefs(c: Context) = c.getSharedPreferences("mesh", 0)
    fun findable(c: Context) = prefs(c).getBoolean(FINDABLE, true)
    fun setFindable(c: Context, on: Boolean) = prefs(c).edit().putBoolean(FINDABLE, on).apply()
    /** Default off in release builds, on in debug builds (DebugTools), until the real-phone script passes. */
    fun trial(c: Context) = prefs(c).getBoolean(TRIAL, DebugTools.finderTrial)
    fun setTrial(c: Context, on: Boolean) = prefs(c).edit().putBoolean(TRIAL, on).apply()

    /**
     * Whether this device adds the note now: a phone (a computer has no Bluetooth links), the trial switch on (decision B
     * of the final review, 2026-11-23: while the Finder screen is hidden, "I need help" carries no link id and is not sent
     * again after a restart), "Let my group find this phone" on, high-risk mode off. Reading a note doesn't depend on it.
     */
    fun writes(findable: Boolean, highRisk: Boolean, trial: Boolean, bluetooth: Boolean = Here.bluetooth) = bluetooth && trial && findable && !highRisk
    fun writes(c: Context) = writes(findable(c), HighRisk.on(c), trial(c))

    /** Whether Settings shows "Let my group find this phone": on a phone, and only while the trial is on (decision B). */
    fun offered(trial: Boolean, bluetooth: Boolean = Here.bluetooth) = bluetooth && trial
}

/** Debug builds only: readings fed by hand instead of Bluetooth (the emulator has none). Set by DebugTools; never in a release build. */
object FinderFake {
    /** One reading per second, in order (null: no reading that second); then nothing. Null: real readings. */
    @Volatile var readings: List<Int?>? = null

    /** "-90,-85,x,-80": numbers, and x for a second without a reading. Null for "off" or anything that doesn't read. */
    fun parse(s: String): List<Int?>? {
        if (s == "off" || s.isBlank()) return null
        return s.split(',').map { w -> w.trim().let { if (it == "x") null else it.toIntOrNull() ?: return null } }
    }
}

/**
 * Every word the Finder screen says (brief 1.6, decisions 4 and 6), in sentence case. [name]: the asker as this phone
 * shows them. Pure, so the words are tested and the same on the desktop.
 */
object FinderWords {
    fun title(name: String) = "Finding $name"
    const val BUTTON = "Find by Bluetooth"
    const val FINE_PRINT = "Bluetooth can't tell direction, only stronger or weaker. Walls and people weaken it."
    const val BLUETOOTH_OFF = "Turn on Bluetooth to search."
    fun card(name: String) = "$name needs help"
    /** The button, and the plain group message it sends (decision 6): no `✅` or `🆘`, so it is never taken for a check-in. */
    fun withThem(name: String) = "I'm with $name"
    fun desktop(name: String) = "$BUTTON works only on a phone. Search from DarkProof on a phone near $name."
    fun ended(name: String, safe: Boolean) = if (safe) "$name is safe" else "$name's phone can't be found by Bluetooth right now. A new “I need help” from $name makes it findable again."

    /** The line under a band's word (spec "What the screens say"); Not in reach's is "Keep moving." with the rest in the note. */
    fun line(band: FinderMath.Band): String = when (band) {
        FinderMath.Band.NOT_IN_REACH -> "Keep moving."
        FinderMath.Band.FAR -> "Keep walking and watch the signal."
        FinderMath.Band.NEAR -> "Keep going the way the signal gets stronger."
        FinderMath.Band.VERY_CLOSE -> "Within a few rooms. Call out and listen."
        FinderMath.Band.HERE -> "Within a few steps. Look around."
    }

    /**
     * What the ring and the lines under it say: [word] big in the ring, [line] small under it in the ring, [trend] under the
     * ring (null while there is none, F12), [note] longer, under that (null for none). [level]: 0 to 4, for the band rings.
     * [here]: the green state.
     */
    data class Shown(val word: String, val line: String, val note: String?, val ticks: Int, val level: Int, val here: Boolean, val trend: String? = null)

    /** The screen at one moment, from how the phones are linked ([reach]), how the signal is read ([role]) and the step. */
    fun shown(name: String, reach: FinderReach, role: FinderRole, step: FinderMath.Step?): Shown = when {
        reach == FinderReach.NONE -> Shown("Not in reach", "Keep moving.",
            "$BUTTON works only when this phone and $name's are linked by Bluetooth. If $name's phone restarted after asking, it can't be recognised until it sends a new check-in.", 0, 0, false)
        reach == FinderReach.OTHER -> Shown("Linked", "Not over Bluetooth",
            "Linked to $name's phone, but not over Bluetooth, so the signal can't be measured.", 0, 0, false)
        role == FinderRole.FAILED -> Shown("Linked", "Can't measure",
            "Linked to $name's phone, but this phone can't measure the signal on this link.", 0, 0, false)
        step == null || step.band == FinderMath.Band.NOT_IN_REACH -> Shown("Not in reach", line(FinderMath.Band.NOT_IN_REACH), "This phone hasn't heard $name's phone yet.", 0, 0, false)
        else -> Shown(step.band.word, line(step.band), null, step.ticks, step.band.level, step.band == FinderMath.Band.HERE, step.trend?.line)
    }
}
