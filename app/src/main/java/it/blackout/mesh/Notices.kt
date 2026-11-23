package it.blackout.mesh

// The 5th plaintext field beyond replies and reactions (spec/FORMAT.md, "The 5th field"): the group's timer for
// disappearing messages, the note of a phone that leaves, and a member's check-in clock ("If I go quiet"). [About] holds
// types 0x01 and 0x02. Same as the iPhone's Notices.swift.

/**
 * The group's timer for disappearing messages (spec/FORMAT.md, "Disappearing messages"): 5th field type `0x03`, then
 * the seconds as a 32-bit big-endian number. It counts where it is the 5th field of a new key; in a group message it
 * is only a notice for apps that don't follow the timer.
 */
object GroupTimer {
    const val TYPE: Byte = 0x03
    /** Type (1), seconds (4). */
    const val SIZE = 5
    /** The shortest timer: a smaller number reads as this. */
    const val FLOOR = 300
    /** The longest: a week. A greater number reads as off, since messages go after one to two weeks anyway. */
    const val MAX = 604_800
    /** What the apps offer, shortest first; `0` is off. */
    val CHOICES = listOf(3_600, 28_800, 86_400, 604_800)
    /** A timer of a day or less (spec "Disappearing messages": an iPhone's alerts there carry no message text). */
    const val BRIEF_MAX = 86_400

    /** True for a group whose timer ([seconds]: null not known, 0 off) is a day or less: a help report's alert there doesn't carry the name it reports. */
    fun brief(seconds: Int?) = seconds != null && seconds in 1..BRIEF_MAX

    /** Who a help report's alert says needs help: the report's own word for them, or "Someone" in a [brief] group. */
    fun reported(subject: String, seconds: Int?) = if (brief(seconds)) "Someone" else subject

    /** What a field's number means ([raw]: 0 to 2^32 - 1): 0 off; 1 to 299 the floor; up to a week as written; anything above, off. */
    fun read(raw: Long): Int = if (raw == 0L || raw > MAX) 0 else maxOf(raw.toInt(), FLOOR)

    /** The timer a 5th field carries: null unless it is exactly [SIZE] bytes of this type. */
    fun read(field: ByteArray?): Int? {
        if (field == null || field.size != SIZE || field[0] != TYPE) return null
        return read((1..4).fold(0L) { n, i -> (n shl 8) or (field[i].toLong() and 0xFF) })
    }

    /** The field for a timer of [seconds] (0: off). */
    fun field(seconds: Int): ByteArray {
        val s = seconds.coerceAtLeast(0).toLong()
        return byteArrayOf(TYPE, (s ushr 24).toByte(), (s ushr 16).toByte(), (s ushr 8).toByte(), s.toByte())
    }

    /** "1 hour", "8 hours", "1 day", "1 week"; other values in the largest unit that divides them, else in minutes. */
    fun label(seconds: Int): String {
        fun n(v: Int, unit: String) = "$v $unit${if (v == 1) "" else "s"}"
        return when {
            seconds % 604_800 == 0 -> n(seconds / 604_800, "week")
            seconds % 86_400 == 0 -> n(seconds / 86_400, "day")
            seconds % 3_600 == 0 -> n(seconds / 3_600, "hour")
            else -> n(maxOf(1, seconds / 60), "minute")
        }
    }

    /** The text of the notice message that follows a change: what apps that don't follow the timer show. Never read back. */
    fun notice(seconds: Int): String =
        if (seconds == 0) "⏱ The timer is off. Messages disappear after 1 to 2 weeks."
        else "⏱ Messages here now disappear ${label(seconds)} after they reach each phone. Older DarkProof versions keep them 1 to 2 weeks."

    // What the screens say. [device]: "phone", or "computer" on the desktop app (Here.device), where it means this device.

    /**
     * Under the chat's title: "This phone deletes them 1 day after they arrive. So do phones on the latest DarkProof." It
     * says what this device does and what phones on the latest version do, never more (design review M3). [highRisk]:
     * this device is in high-risk mode, whose day counts unless the group's timer is shorter.
     */
    fun chatLine(seconds: Int?, highRisk: Boolean = false, device: String = "phone"): String {
        val timed = seconds != null && seconds > 0
        if (highRisk && !(timed && seconds!! <= KeepLess.KEEP_MS / 1000)) return "High-risk mode: they leave this $device 1 day after they arrive."
        if (!timed) return "They disappear after 1 to 2 weeks."
        return deletes(seconds!!, device)
    }

    /** "This phone deletes them 1 hour after they arrive. So do phones on the latest DarkProof." */
    private fun deletes(seconds: Int, device: String) = "This $device deletes them ${label(seconds)} after they arrive. So do phones on the latest DarkProof."

    /**
     * The "Messages disappear" row of Group security. [by]: who set it, as the reader sees them ("Anna", "you"), if this
     * device saw the change. [highRisk]: this device is in high-risk mode, which removes after a day whatever the group says.
     */
    fun securityLine(seconds: Int?, by: String?, highRisk: Boolean, device: String = "phone"): String {
        val day = " On this $device, 1 day after they arrive."
        if (seconds == null) return "Not known yet. Until an admin's key brings it, this $device keeps messages 1 to 2 weeks." + (if (highRisk) day else "")
        if (seconds <= 0) return "After 1 to 2 weeks" + (if (highRisk) ".$day" else "")
        val line = "After ${label(seconds)}" + (by?.let { ", set by $it" } ?: "")
        return if (highRisk && seconds > KeepLess.KEEP_MS / 1000) "$line.$day" else line
    }

    /** The line in the chat when the timer changed. [who]: "You", "Anna", or null when this device didn't see who set it. */
    fun changeLine(seconds: Int, who: String?): String {
        if (who == null) return if (seconds == 0) "The timer is off. Messages disappear after 1 to 2 weeks." else "Messages disappear after ${label(seconds)}"
        return if (seconds == 0) "$who switched the timer off. Messages disappear after 1 to 2 weeks." else "$who set messages to disappear after ${label(seconds)}"
    }

    /** What someone who can't change it reads in the timer sheet: what this device does, as in the chat ([chatLine]). */
    fun sheetLine(seconds: Int?, device: String = "phone"): String {
        if (seconds == null) return "Not known on this $device yet. Until an admin's key brings it, messages stay 1 to 2 weeks."
        return if (seconds > 0) "This $device deletes messages ${label(seconds)} after they arrive. So do phones on the latest DarkProof." else "Messages disappear after 1 to 2 weeks."
    }

    /** Under the timer's choices (owner's decision D): the timer takes help calls with it too, and the positions in them. */
    const val TAKES_HELP = "This includes \"I need help\" calls and the positions sent with them."

    /** What tapping the row explains. */
    fun explainer(seconds: Int): String {
        val t = label(seconds)
        return "Each phone deletes a message $t after it reaches it. A phone that was away gets it later and deletes it $t after that. Phones need the latest DarkProof for this. Anyone in the group can still copy or photograph a message, and a changed app can keep everything."
    }
}

/**
 * What a group message's 5th field says, next to replies and reactions (spec "The 5th field"). Readers act on it only in
 * a group, from a packet whose signature checked for a member; on the Public channel it is ignored ([Message.onPublic]).
 */
sealed class Notice {
    /**
     * Type `0x03` in a message: the timer changed, said in the text for apps that don't follow it. An app that does
     * follow it shows nothing of this message and takes nothing from it: the new key is the only word on the timer.
     */
    data class Timer(val seconds: Int) : Notice()

    /**
     * Type `0x05`, form `0x01`: the sender left the group or erased their phone ([LeaveNote]). Never a bubble: the chat
     * shows a line instead, and admins' phones remove the sender.
     */
    object Leave : Notice() { override fun toString() = "Leave" }

    /**
     * Type `0x04`: the sender's check-in clock ([QuietClock]), its deadline in ms since 1970 UTC; 0 stops it. An ordinary
     * message on every phone (its text says it), shown here as a card, alerted, confirmed and sent on LoRa; its free text
     * is never read as a check-in, a help report or a question ([QuietClock.shown]).
     */
    data class Clock(val deadline: Long) : Notice()

    /** True for a notice that is never a chat row, an alert or a receipt. A check-in clock is an ordinary message. */
    val isSilent: Boolean get() = this !is Clock

    /** True for a notice that stays off LoRa, like a receipt. The leave note goes on the radio: it is small, and it may be the only way it reaches an admin. */
    val staysOffRadio: Boolean get() = this is Timer

    companion object {
        /**
         * What a reader makes of a 5th field; null for any other type or length. [ts]: the message's own signed time, which a
         * check-in clock's deadline is checked against (rule Q2); without it a clock reads as none.
         */
        fun read(field: ByteArray?, ts: Long? = null): Notice? = GroupTimer.read(field)?.let { Timer(it) } ?: if (LeaveNote.read(field)) Leave
            else ts?.let { QuietClock.read(field, it) }?.let { Clock(it) }
    }
}

/**
 * A check-in clock the group's phones keep (spec/FORMAT.md, "If I go quiet"): an ordinary signed group message whose
 * `text` says it for every app (`⏳ Check on me if I don't check in within 1 hour`), with the 5th field `0x04` and the
 * deadline, ms since 1970 UTC as a signed 64-bit big-endian number, by the sender's clock. Deadline 0 stops the sender's
 * clock. Nothing is ever parsed from the text but a position, read as in a check-in. Same as the iPhone's QuietClock.
 */
object QuietClock {
    const val TYPE: Byte = 0x04
    /** Type (1), deadline (8). */
    const val SIZE = 9
    /** Rule Q2: a deadline counts from 5 minutes to 24 hours after the message's own time, both ends included. */
    const val MIN_MS = 5 * 60_000L
    const val MAX_MS = 24 * 3_600_000L
    /** Rules Q6, Q7: "overdue" in People, and alerted at once by a phone that learns of it late, until this long after the deadline. */
    const val LATE_MS = 12 * 3_600_000L
    /** Rule Q8: the sender's own phone reminds its user this long before the deadline, for a clock at least [REMIND_FROM_MS] long. */
    const val REMIND_MS = 10 * 60_000L
    const val REMIND_FROM_MS = 20 * 60_000L
    /** "Add 30 minutes". */
    const val ADD_MS = 30 * 60_000L
    /** What the screen offers, in minutes. */
    val CHOICES = listOf(30, 60, 180, 480)
    /** How every clock text starts: what an app without the field shows of it, and the only free text this app shows of one. */
    const val PREFIX = "⏳"
    const val STOP = "⏳ Clock stopped. No need to check on me"
    private const val LEAD = "$PREFIX Check on me if I don't check in within "

    /** The field for [deadline] (0: stop). */
    fun field(deadline: Long): ByteArray = byteArrayOf(TYPE) + ByteArray(8) { i -> (deadline ushr (56 - 8 * i)).toByte() }

    /** The deadline a 5th field carries, unchecked: null unless it is exactly [SIZE] bytes of this type. */
    fun raw(field: ByteArray?): Long? {
        if (field == null || field.size != SIZE || field[0] != TYPE) return null
        return (1..8).fold(0L) { n, i -> (n shl 8) or (field[i].toLong() and 0xFF) }
    }

    /** Rule Q2: `0` (a stop), or between 5 minutes and 24 hours after [ts], both ends included. */
    fun valid(deadline: Long, ts: Long): Boolean {
        if (deadline == 0L) return true
        val lo = ts + MIN_MS; val hi = ts + MAX_MS
        if (lo < ts || hi < ts) return false // went round
        return deadline in lo..hi
    }

    /** What a reader takes from the 5th field of a message dated [ts]: its deadline if it is a clock rule Q2 allows. */
    fun read(field: ByteArray?, ts: Long): Long? = raw(field)?.takeIf { valid(it, ts) }

    /** [ms] rounded to the nearest minute: "30 minutes", "1 hour", "3 hours", "1 hour 30 minutes", "3 hours 15 minutes". */
    fun duration(ms: Long): String {
        val minutes = (maxOf(0L, ms) + 30_000) / 60_000; val h = minutes / 60; val m = minutes % 60
        fun n(v: Long, unit: String) = "$v $unit${if (v == 1L) "" else "s"}"
        return if (h == 0L) n(m, "minute") else if (m == 0L) n(h, "hour") else n(h, "hour") + " " + n(m, "minute")
    }

    /**
     * The text a clock message carries: a start or more time, or the stop. Relative to the message's own time, which every
     * app shows, so time zones don't matter. [spot]: the check-in suffix ` · 📍 45.46420,9.19000 ±12m`, only if the sender
     * shares their position and chose "Add where I am".
     */
    fun text(deadline: Long, ts: Long, spot: Spot? = null): String {
        if (deadline == 0L) return STOP
        return LEAD + duration(deadline - ts) + (spot?.takeIf { it.isValid }?.let { " · 📍 " + it.text } ?: "")
    }

    /**
     * What this app shows of a clock message whose text is [said] (spec "The 5th field": nothing is read from it but a
     * position): the text when it is a clock's, else what the field says. Never a check-in, a help report or a question,
     * which a member could otherwise send under a field that other phones show as a clock.
     */
    fun shown(said: String, deadline: Long, ts: Long): String = if (said.startsWith(PREFIX)) said else text(deadline, ts)

    /** The position a clock message's text carries, read as in a check-in ("Check-ins"). */
    fun spot(text: String): Spot? = if (text.startsWith(PREFIX)) Spot.last(text) else null

    /** Rule Q4: an own check-in of the clock's member (text starting with `✅` or `🆘`) dated after the clock message cancels it. */
    fun cancels(text: String, ts: Long, clockTs: Long) = CheckIn.of(text) != null && ts > clockTs

    /** What a group message does to its sender's clock. */
    sealed class Effect {
        /** A clock message (rule Q2 passed): it counts if it is the newest (Q3); deadline 0 stops. */
        data class Clock(val deadline: Long) : Effect()
        /** An own check-in: cancels a clock dated before it (Q4). */
        object CheckIn : Effect() { override fun toString() = "CheckIn" }
    }

    /**
     * Rule Q4's sorting: a clock message, an own check-in, or null for anything else: a report (`👤`), "Are you safe?", an
     * ordinary message, and anything silent (a reaction, the timer's notice, a leave note), whatever its text. Never on the
     * Public channel.
     */
    fun effect(m: Message): Effect? {
        if (m.public) return null
        (m.notice as? Notice.Clock)?.let { return Effect.Clock(it.deadline) }
        if (m.silent) return null
        return if (CheckIn.of(m.text) != null) Effect.CheckIn else null
    }

    /**
     * Rule Q1: a message acts on clocks only in a group, from a packet whose signature checked for its sender ([checked];
     * one waiting for its sender's key is decided when it checks), opened by an epoch with a member list that lists the
     * sender, as the winning epoch at every number up to the current one does ([Keyring.listsThrough]): nothing of a
     * removed member, nor a message sealed before a removal and played back after the same key was let in again. [left]:
     * the message is dated after a valid leave note of the sender that is held ([LeaveBook.hides]): it counts for nothing.
     * One dated at or before the note still counts, so a check-in sent just before leaving still cancels the clock, which a
     * leave note itself never ends (rule Q9). A Wi-Fi board writes none.
     */
    fun counts(sender: String, checked: Boolean?, opener: Keyring.Epoch?, ring: Keyring, left: Boolean): Boolean {
        if (checked != true || left || sender.startsWith("web-") || opener == null) return false
        return ring.listsThrough(sender, opener)
    }

    /** The deadline of a new clock of [minutes], started at [now]. */
    fun deadline(minutes: Int, now: Long) = now + minutes * 60_000L

    /** "Add 30 minutes": the old deadline plus 30 minutes (from now if it has passed), at most 24 hours from now, so it always passes rule Q2. */
    fun added(deadline: Long, now: Long) = minOf(maxOf(deadline, now) + ADD_MS, now + MAX_MS)

    /** Rule Q8: when the sender's own phone reminds its user, or null for a clock shorter than 20 minutes, or a stop. */
    fun remindAt(ts: Long, deadline: Long): Long? = if (deadline != 0L && deadline - ts >= REMIND_FROM_MS) deadline - REMIND_MS else null

    /** What a phone does about a clock it holds (rules Q5, Q6, Q7). */
    sealed class Due {
        /** Not yet: an alert at the deadline, by this phone's clock. */
        data class At(val at: Long) : Due()
        /** Now, worded as on time: this phone held the clock before its deadline. */
        object Now : Due() { override fun toString() = "Now" }
        /** Now, with "This phone learned of it late.": it learned of the clock after the deadline. */
        object Late : Due() { override fun toString() = "Late" }
        /** Never: not armed, a stop, or more than 12 hours after the deadline. No record shows, only the chat card. */
        object None : Due() { override fun toString() = "None" }
    }

    /** [learned]: when this phone took the clock; [armed]: no cancelling check-in or newer clock message reached it. */
    fun due(deadline: Long, learned: Long, now: Long, armed: Boolean = true): Due {
        if (!armed || deadline == 0L) return Due.None
        if (now < deadline) return Due.At(deadline)
        if (now - deadline > LATE_MS) return Due.None
        return if (learned > deadline) Due.Late else Due.Now
    }
}

/**
 * The check-in clocks of one group on this phone (spec/FORMAT.md, "If I go quiet", rules Q3, Q4, Q6, Q9, Q10, Q12): each
 * member's clock that counts and their latest check-in, saved apart from the chat (MeshEngine keeps it in the store's
 * table q, beside the gone list, out of the preferences and of backups), so that a clock survives its message being
 * deleted by the group's timer or high-risk mode, and app restarts. Built only from messages that passed rule Q1 (the
 * engine checks it). A leave note, and the removal that follows it, don't end a clock (rule Q9): only a cancelling
 * check-in or a stop that counts does, or the record's time. A record is kept, without its position, after it is
 * cancelled, so that a copy of an older clock read again can't arm it again. Same as the iPhone's QuietBook. Not
 * thread-safe: the engine guards it.
 */
class QuietBook {
    /**
     * A member's clock: their clock message's packet id (16 hex chars) and mark (the first 8 bytes of its signed digest,
     * hex, [About.Target]), its `ts`, its deadline (0: a stop), where they were (none in high-risk mode on this phone, or
     * once cancelled), when this phone took it, whether its alert was posted ([alerted]; for this phone's own clock: its
     * reminder) or handed to the backup alarm ([scheduled]), and for this phone's own clock who confirmed it ([keepers],
     * delivery receipts, first confirmation first: ids only, kept here so "Reached" outlives the message; a receipt says
     * only that the message reached that phone, not that it keeps the clock).
     */
    data class Clock(val member: String, val id: String, val mark: String, val ts: Long, val deadline: Long, val spot: Spot?, val learned: Long,
                     val alerted: Boolean = false, val scheduled: Boolean = false, val keepers: List<String> = emptyList()) {
        /** Rule Q3: the greater `ts`; equal, the greater packet id; equal, the greater mark. Arrival order doesn't matter. */
        fun beats(o: Clock): Boolean = compareValuesBy(this, o, Clock::ts, Clock::id, Clock::mark) > 0
    }

    /** Member id -> their clock that counts. */
    val clocks = HashMap<String, Clock>()
    /** Member id -> the greatest `ts` of their own check-ins this phone took (rule Q4). */
    val checkIns = HashMap<String, Long>()
    /** Member id -> when this phone last posted a clock alert about them, and that clock's `ts` (rule Q12). */
    val posted = HashMap<String, Pair<Long, Long>>()

    val isEmpty get() = clocks.isEmpty() && checkIns.isEmpty() && posted.isEmpty()

    private fun plain(id: String) = id.removePrefix("web-")

    /** Rule Q3: a valid clock message of [member] (Q2 passed; deadline 0 is a stop), taken at [now]. True if it now counts. */
    fun take(member: String, id: String, mark: String, ts: Long, deadline: Long, spot: Spot?, now: Long, highRisk: Boolean): Boolean {
        val m = plain(member)
        if (!keeps(ts, now, highRisk)) return false
        var c = Clock(m, id, mark, ts, deadline, if (highRisk || deadline == 0L) null else spot, now)
        clocks[m]?.let { if (!c.beats(it)) return false }
        if ((checkIns[m] ?: Long.MIN_VALUE) > ts) c = c.copy(spot = null) // cancelled already: kept only so that older ones stay out
        clocks[m] = c
        if (clocks.size > MAX_RECORDS) clocks.values.minWithOrNull(compareBy<Clock>({ it.ts }, { it.member }))?.let { clocks.remove(it.member) }
        return clocks.containsKey(m)
    }

    /** Rule Q4: an own check-in of [member] dated [ts]. True if the book changed (it is their latest). */
    fun checkIn(member: String, ts: Long, now: Long, highRisk: Boolean): Boolean {
        val m = plain(member)
        if (!keeps(ts, now, highRisk) || ts <= (checkIns[m] ?: Long.MIN_VALUE)) return false
        checkIns[m] = ts
        clocks[m]?.let { c -> if (ts > c.ts) clocks[m] = c.copy(spot = null) } // cancelled: where they were goes
        return true
    }

    /** Rule Q4: [member] has a clock that runs or ran out: deadline not 0, and no check-in of theirs dated after it. */
    fun armed(member: String): Boolean {
        val c = clocks[plain(member)] ?: return false
        return c.deadline != 0L && (checkIns[c.member] ?: Long.MIN_VALUE) <= c.ts
    }

    fun clock(member: String): Clock? = clocks[plain(member)]

    /** What People and the own screen show of a member's clock (rule Q6). */
    sealed class State {
        /** Armed, before its deadline. */
        data class Running(val deadline: Long) : State()
        /** Armed, its deadline passed less than 12 hours ago: "overdue". */
        data class RanOut(val deadline: Long) : State()
        /** A check-in dated after the deadline cancelled it, less than 12 hours after the deadline. */
        data class CheckedIn(val at: Long, val deadline: Long) : State()
    }

    fun state(member: String, now: Long): State? {
        val c = clocks[plain(member)]?.takeIf { it.deadline != 0L } ?: return null
        val within = now - c.deadline <= QuietClock.LATE_MS
        checkIns[c.member]?.let { ci -> if (ci > c.ts) return if (ci >= c.deadline && within) State.CheckedIn(ci, c.deadline) else null }
        if (now < c.deadline) return State.Running(c.deadline)
        return if (within) State.RanOut(c.deadline) else null
    }

    /** Rule Q9: [member] was removed by a removal that didn't follow a leave note of theirs: their clock goes without an alert. True if anything went. */
    fun drop(member: String): Boolean {
        val m = plain(member)
        val had = clocks.containsKey(m) || checkIns.containsKey(m)
        clocks.remove(m); checkIns.remove(m)
        return had
    }

    /**
     * Rule Q10's end: records and check-ins go 36 hours after their `ts` (24 in high-risk mode, which also keeps no
     * position), and when their member is no longer in the group ([inGroup], rule Q9: still listed in the current epoch,
     * or taken out only after a leave note of theirs). [positionMs]: the group's timer as it deletes now; a record's
     * position goes once its clock message would have gone by it (counted from when this phone took the clock, as messages
     * count from arrival). True if anything changed.
     */
    fun sweep(now: Long, highRisk: Boolean, positionMs: Long? = null, inGroup: (String) -> Boolean): Boolean {
        val before = saved
        clocks.entries.removeAll { (m, c) -> !keeps(c.ts, now, highRisk) || !inGroup(m) }
        for ((m, c) in clocks.entries.toList()) if (c.spot != null && (highRisk || (positionMs != null && now - c.learned >= positionMs))) clocks[m] = c.copy(spot = null)
        checkIns.entries.removeAll { (m, ts) -> !keeps(ts, now, highRisk) || !inGroup(m) }
        posted.entries.removeAll { (m, p) -> now - p.first >= KEEP_MS || !inGroup(m) }
        return saved != before
    }

    /** For this phone's own clock ([me]) whose message is [clockId]: [keeper]'s phone confirmed it. True if the list grew. */
    fun addKeeper(me: String, clockId: String, keeper: String): Boolean {
        val m = plain(me); val k = plain(keeper)
        val c = clocks[m] ?: return false
        if (c.id != clockId || k == m || k in c.keepers) return false
        clocks[m] = c.copy(keepers = c.keepers + k)
        return true
    }

    /**
     * Rule Q14: whose phones this phone's own clock ([me]) reached, as "Reached" names them: of the phones that confirmed it, only
     * those of people who are members now ([member]: listed in the current epoch and not left with a note), since whoever
     * left or was removed since forgot the group (review round 2b M7).
     */
    fun keptBy(me: String, member: (String) -> Boolean): List<String> = clocks[plain(me)]?.keepers?.filter(member) ?: emptyList()

    /**
     * Rule Q12: when an alert about [c] may be posted now, or null if never (a late alert for a clock no newer than one
     * already alerted about that member). At most one alert per member every [ALERT_GAP_MS]: a member can't make every
     * phone ring again and again with backdated clocks.
     */
    fun postableAt(c: Clock, late: Boolean, now: Long): Long? {
        val last = posted[c.member] ?: return now
        if (late && c.ts <= last.second) return null
        return maxOf(now, last.first + ALERT_GAP_MS)
    }

    /** An alert about [c] was posted at [now]. */
    fun didPost(c: Clock, now: Long): Boolean {
        posted[c.member] = now to maxOf(c.ts, posted[c.member]?.second ?: Long.MIN_VALUE)
        return true
    }

    /** The armed clocks of other members ([me]: this phone's member id, whose own clock never alerts it, rule Q8) with what is due for each at [now]. Already alerted ones are left out. */
    fun alerts(now: Long, me: String): List<Pair<Clock, QuietClock.Due>> =
        clocks.values.filter { it.member != plain(me) && !it.alerted && armed(it.member) }.sortedBy { it.deadline }
            .map { it to QuietClock.due(it.deadline, it.learned, now) }

    /** Marks the alert of [member]'s clock [mark] as posted or come due ([alerted]), or as handed to the alarm ([scheduled]). Nothing changes for another clock. True if anything changed. */
    fun note(member: String, mark: String, alerted: Boolean? = null, scheduled: Boolean? = null): Boolean {
        val m = plain(member)
        val c = clocks[m]?.takeIf { it.mark == mark } ?: return false
        val n = c.copy(alerted = alerted ?: c.alerted, scheduled = scheduled ?: c.scheduled)
        clocks[m] = n
        return n != c
    }

    /**
     * As it is saved, the iPhone's lines: "c <member> <id> <mark> <ts> <deadline> <learned> <alerted> <scheduled> <lat> <lon>
     * <accuracy> <keepers>" for each clock ("-" for no position, accuracy or keepers; keepers comma-separated; 0 or 1 for the
     * flags), "i <member> <ts>" for each check-in, "a <member> <at> <ts>" for each alert posted.
     */
    val saved: List<String> get() {
        fun n(d: Double?) = d?.let { String.format(java.util.Locale.ROOT, "%.5f", it) } ?: "-"
        return clocks.values.sortedBy { it.member }.map { c ->
            "c ${c.member} ${c.id} ${c.mark} ${c.ts} ${c.deadline} ${c.learned} ${if (c.alerted) 1 else 0} ${if (c.scheduled) 1 else 0} ${n(c.spot?.lat)} ${n(c.spot?.lon)} ${c.spot?.accuracy ?: "-"} ${c.keepers.joinToString(",").ifEmpty { "-" }}"
        } + checkIns.entries.sortedBy { it.key }.map { "i ${it.key} ${it.value}" } +
            posted.entries.sortedBy { it.key }.map { "a ${it.key} ${it.value.first} ${it.value.second}" }
    }

    fun copy(): QuietBook = parse(saved)
    override fun equals(other: Any?) = other is QuietBook && other.saved == saved
    override fun hashCode() = saved.hashCode()

    companion object {
        /** Rule Q12: at most this many clocks in one group (a group has at most 100 members): the oldest goes. */
        const val MAX_RECORDS = 100
        /** Rule Q10: a record is kept until 36 hours after its message's `ts`; in high-risk mode 24 hours, never before the deadline. */
        const val KEEP_MS = QuietClock.MAX_MS + QuietClock.LATE_MS
        const val KEEP_HIGH_RISK_MS = QuietClock.MAX_MS
        /** At most one clock alert about a member per group in this long (rule Q12). */
        const val ALERT_GAP_MS = 10 * 60_000L

        /** True while something dated [ts] is still kept (rule Q10). */
        fun keeps(ts: Long, now: Long, highRisk: Boolean) = now - ts < (if (highRisk) KEEP_HIGH_RISK_MS else KEEP_MS)

        /**
         * Whether this device keeps the clock a chat card shows (review item 9): the card's message checked ([verified]), and
         * [state], what this device holds of that member's clock, is armed (running or run out) with the card's very
         * [deadline]. A newer clock, a check-in after it, a record that has gone, or a clock never taken (rule Q1) all mean
         * no, and the card then promises nothing.
         */
        fun keeps(state: State?, deadline: Long, verified: Boolean): Boolean = verified && deadline != 0L && when (state) {
            is State.Running -> state.deadline == deadline
            is State.RanOut -> state.deadline == deadline
            else -> false
        }

        /** From what [saved] wrote. A line that doesn't read is left out. */
        fun parse(saved: List<String>): QuietBook = QuietBook().also { b ->
            for (line in saved) {
                val f = line.split(' ')
                if ((f.size == 12 || f.size == 13) && f[0] == "c") {
                    val ts = f[4].toLongOrNull(); val d = f[5].toLongOrNull(); val l = f[6].toLongOrNull()
                    if (ts == null || d == null || l == null) continue
                    val lat = f[9].toDoubleOrNull(); val lon = f[10].toDoubleOrNull()
                    val spot = if (lat != null && lon != null) Spot(lat, lon, f[11].toIntOrNull()).takeIf { it.isValid } else null
                    val keepers = if (f.size == 13 && f[12] != "-") f[12].split(',').filter { it.isNotEmpty() } else emptyList()
                    b.clocks[f[1]] = Clock(f[1], f[2], f[3], ts, d, spot, l, f[7] == "1", f[8] == "1", keepers)
                } else if (f.size == 3 && f[0] == "i") f[2].toLongOrNull()?.let { b.checkIns[f[1]] = it }
                else if (f.size == 4 && f[0] == "a") { val at = f[2].toLongOrNull(); val ts = f[3].toLongOrNull(); if (at != null && ts != null) b.posted[f[1]] = at to ts }
            }
        }
    }
}

/**
 * What the screens and alerts say about check-in clocks (spec/FORMAT.md, "If I go quiet"): the iPhone's QuietWords, word
 * for word. Nothing here claims more than the phone knows: an alert says that no check-in *has reached this phone*, never
 * that someone is missing or that anyone was told. [device]: "phone", or "computer" on the desktop app (Here.device).
 */
object QuietWords {
    const val TITLE = "If I go quiet"
    /** Under the title: the name the cards, the alerts and People use (design review L3). */
    const val SUBTITLE = "A check-in clock"
    const val LEAD = "If you don't check in on time, the phones that got your clock can alert their owners."
    const val SUB = "It still works when your phone is off or gone. Only phones that got the clock can tell anyone."
    const val ADD_WHERE = "Add where I am"
    const val ADD_WHERE_SUB = "Where you are now goes with the clock"
    const val START = "Start the clock"
    const val SAFE = "I'm safe, stop the clock"
    const val ADD_MORE = "Add 30 minutes"
    const val STOP_WITHOUT = "Stop without checking in"
    const val OLDER = "Phones on older DarkProof versions only show the message."
    const val SHARE_OFF = "To add where you are, turn on location sharing in Settings."
    const val GETTING = "Getting your position…"
    const val OFF = "Off"
    /** Android: its alarms are inexact, so an alert can come minutes after the deadline (spec "Alerts, and what can't be guaranteed"). */
    const val CAN_BE_LATE = "It can come a few minutes late."
    /** The chips: "30 min", "1 hour", "3 hours", "8 hours". */
    fun chip(minutes: Int) = if (minutes < 60) "$minutes min" else if (minutes == 60) "1 hour" else "${minutes / 60} hours"
    fun checkInBy(time: String) = "Check in by $time"
    fun ends(time: String) = "Ends $time"
    fun ranOutShort(time: String) = "Ran out at $time"
    fun ranOut(time: String) = "Your clock ran out at $time. Phones that have it can alert their owners."
    /**
     * Whose phones the clock message reached (delivery receipts), never the member list (decision A): a receipt says that a
     * phone got the message, nothing more. A phone on an older DarkProof, a computer or a phone with alerts off sends one
     * too, so this never says those phones keep the clock or will alert anyone.
     */
    fun reached(names: List<String>) = when (names.size) {
        0 -> "Not confirmed by any phone yet. Until a phone has it, nobody can be alerted."
        1 -> "Reached ${names[0]}'s phone so far"
        2 -> "Reached ${names[0]}'s and ${names[1]}'s phones so far"
        else -> "Reached ${names[0]}'s, ${names[1]}'s and ${names.size - 2} more phones so far"
    }
    fun cantWrite(group: String, device: String = "phone") = "This $device isn't let in, so it can't send a clock to $group."

    // Toasts. [waiting]: no phone, radio or relay is in reach, so it waits on this phone.
    fun sent(waiting: Boolean) = if (waiting) "The clock waits for a phone in reach" else "Clock sent to your group"
    fun added(waiting: Boolean) = if (waiting) "30 minutes added. It waits for a phone in reach" else "30 minutes added"
    fun safeSent(waiting: Boolean) = if (waiting) "“I'm safe” waits for a phone in reach. The clock stopped here." else "“I'm safe” sent. The clock stopped."
    fun stopped(waiting: Boolean) = if (waiting) "Clock stopped. The message waits for a phone in reach" else "Clock stopped"

    // The chat card. [name]: null for this phone's own.
    fun started(name: String?) = name?.let { "$it started a check-in clock" } ?: "You started a check-in clock"
    fun startedSub(name: String?, time: String) =
        name?.let { "If no check-in from $it reaches this phone by $time, it tells you." } ?: "Ends $time. Phones that got it can alert their owners if you don't check in."
    /** Someone else's clock this phone keeps, on a phone whose alerts are off for DarkProof (design review M1). */
    fun alertsOffSub(name: String, time: String) = "If no check-in from $name reaches this phone by $time, it shows here. Alerts are off for DarkProof."
    /** Someone else's clock this device doesn't keep (not verified, rule Q1 failed, or no longer armed): no promise at all (review item 9). */
    fun notKeeping(device: String = "phone") = "This $device isn't keeping this clock."

    /**
     * The line under a clock card, or null for none. [name]: null for this phone's own. [keeping]: this device holds this
     * very clock, armed ([QuietBook.keeps]). [alerts]: this device can post alerts at all (a computer can't); [alertsOff]:
     * they are off for DarkProof on it. [late]: Android's alarms are inexact, so an alert can come minutes late.
     */
    fun cardLine(name: String?, time: String, keeping: Boolean, alerts: Boolean, alertsOff: Boolean, device: String = "phone", late: Boolean = false): String? = when {
        name == null -> startedSub(null, time)
        !keeping -> notKeeping(device)
        !alerts -> null // a computer promises nothing about telling
        alertsOff -> alertsOffSub(name, time)
        else -> startedSub(name, time) + if (late) " $CAN_BE_LATE" else ""
    }
    fun stoppedLine(name: String?) = name?.let { "$it stopped the check-in clock" } ?: "You stopped your check-in clock"

    // People.
    fun running(time: String) = "Check-in clock · ends $time"
    fun overdue(time: String) = "Clock ran out at $time · no check-in has reached this phone"
    fun checkedIn(time: String) = "Checked in at $time"

    /**
     * Rule Q13: who a clock is from, as the alert and the card name them. The bare name only for a checked member whose name
     * (by its key, [Vouch.key]) no other member of the group has; a member not checked: "Anna (not checked)"; a checked one
     * whose name another member has too: "Anna · 🐶🐱🦁🐴", the first four of their person emoji.
     */
    fun who(name: String, checked: Boolean, shared: Boolean, emoji: List<String>): String = when {
        !checked -> "$name (not checked)"
        shared -> "$name · " + emoji.take(4).joinToString("")
        else -> name
    }

    // The alert. [time]: the deadline; [since]: the clock message's time; [position]: when the newest position known was sent.
    fun alertTitle(name: String, late: Boolean, time: String) = if (late) "$name's check-in clock ran out at $time" else "$name's check-in clock ran out"
    fun alertBody(name: String, since: String, late: Boolean, position: String?): String {
        var s = "No check-in from $name has reached this phone since $since. $name's phone may just be out of reach."
        if (position != null) s += " Last position known: $position."
        if (late) s += " This phone learned of it late."
        return s
    }
    /** With "Lock DarkProof" or high-risk mode on: no name, no time, no text. */
    const val HIDDEN_TITLE = "DarkProof"
    const val HIDDEN_BODY = "A check-in clock in your group ran out. Open DarkProof."
    /** The banner while DarkProof is on screen (neutral, not red). */
    fun banner(name: String, group: String) = "No check-in from $name has reached this phone. In $group."

    // The sender's own reminder, 10 minutes before (rule Q8).
    fun remindTitle(time: String) = "Your check-in clock ends at $time"
    const val REMIND_BODY = "Check in now?"
    const val REMIND_HIDDEN = "Your check-in clock ends soon. Open DarkProof."

    /** What a message alert says of a clock message: none of its sender's words, which may carry a position. */
    fun messageAlert(deadline: Long, time: String) = if (deadline == 0L) "Stopped the check-in clock." else "Started a check-in clock. Ends $time."

    /** Never said anywhere about a clock (lowercased): the "Never say" list. */
    val NEVER = listOf("is missing", "didn't check in", "did not check in", "will be told", "are told", "group is told")
}

/**
 * The names check-in clock alerts go by (spec "If I go quiet"): a hash of the group's id and the member's, so neither sits
 * in the phone's alert or alarm lists, and the same member's newer clock replaces what waits. Same as the iPhone's.
 */
object QuietAlerts {
    const val PREFIX = "quiet."
    fun id(group: String, member: String) = PREFIX + hash("m|$group|$member")
    fun remindId(group: String) = PREFIX + "r." + hash("r|$group")
    private fun hash(s: String) = Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(("darkproof-quiet|$s").toByteArray()).copyOf(8))
}

/**
 * A clock alert or this phone's own reminder, with its words decided when it is made (no name and no text with "Lock
 * DarkProof" or high-risk mode on): posted now, or handed to the backup alarm for [at]. [id]: [QuietAlerts]' name for it;
 * [mark]: the clock's, so an alert for a clock that was replaced or cancelled meanwhile isn't posted; [group]: the
 * group's code, to open it; [bannerTitle], [banner]: what the in-app banner says while DarkProof is on screen and
 * unlocked; [safe]: "I'm safe" may go under a reminder (never with the lock on).
 */
data class QuietAlert(val id: String, val mark: String, val at: Long, val title: String, val body: String, val bannerTitle: String, val banner: String,
                      val group: String, val remind: Boolean, val safe: Boolean)

/** Where clock alerts go: a phone's notifications and alarm (QuietAlarms.kt). The desktop has none yet: nothing is posted there. */
interface QuietOut {
    /** [a] now: a banner while DarkProof is on screen, else a notification. */
    fun post(a: QuietAlert)
    /** The alerts [ids] that show for clocks that were cancelled meanwhile go. */
    fun take(ids: Collection<String>)
    /** The backup alarm for what is still to come ([coming], nearest first; empty: none), in case the app isn't running then. */
    fun arm(coming: List<QuietAlert>)
}

/**
 * The note a phone sends when it leaves its group, or when "Erase everything" tells its groups first (spec/FORMAT.md,
 * "Leaving the group"): an ordinary group message with [TEXT] and the 5th field `05 01`, sealed with the phone's message
 * key in its current epoch. One form for leaving and erasing: saying "erased" could tell the wrong reader that the phone
 * was in danger. Only the member's own key can sign it, so nobody else can make a member leave. Same as the iPhone's.
 */
object LeaveNote {
    const val TYPE: Byte = 0x05
    /** The only form, "I left". A reader ignores any other second byte (a newer form) and reads the message as text. */
    const val FORM: Byte = 0x01
    /** Type (1), form (1). */
    const val SIZE = 2
    /** What every app shows of it, also those that don't know the field: to them it is an ordinary message. */
    const val TEXT = "👋 I left the group"
    val field: ByteArray get() = byteArrayOf(TYPE, FORM)
    /** The longest a leaving or erasing phone waits for a phone, a radio or a relay to take the note. */
    const val WAIT_SECONDS = 15
    /** How long a leaving record lasts after this phone took it: a packet's life, so the note itself is gone by then. */
    const val KEEP_MS = 7 * 24 * 3_600_000L
    /** An admin's phone waits a random time below this before it removes the member: the group's admins don't all act at once. */
    const val MAX_DELAY_MS = 3_000

    /** True only for exactly `05 01`: another form, another length or another type is not a note. */
    fun read(field: ByteArray?): Boolean = field != null && field.size == SIZE && field[0] == TYPE && field[1] == FORM

    /** What a reader makes of a note (rule L1). */
    enum class Verdict {
        /** It counts: the member left. */
        VALID,
        /** Its signature can't be checked yet (the sender's key hasn't come): decided again when it checks. */
        NOT_YET,
        /** It does nothing. */
        NO,
    }

    /**
     * Rule L1: a note counts only if it opened in this group with an epoch that has a member list (not one kept only to
     * read with), its signature checked for its sender, every epoch from that one up to the current one lists the sender
     * ([Keyring.listsThrough]), no removal of the sender that won is held, and the sender isn't this phone. A note from
     * before a removal, played back after the same key was let in again, fails: the removal's epoch doesn't list them.
     * [checked]: the signature checked (true), failed (false), or can't be checked yet (null). [opener]: the epoch that
     * opened it ([GroupCrypto.Opened.opener]).
     */
    fun verdict(sender: String, me: String, checked: Boolean?, opener: Keyring.Epoch?, ring: Keyring): Verdict {
        val id = sender.removePrefix("web-")
        if (checked == false || id == me || opener == null || !ring.listsThrough(id, opener)) return Verdict.NO
        return if (checked == null) Verdict.NOT_YET else Verdict.VALID
    }

    /** What an admin's phone does about a member who left (rules L4, L5). */
    enum class Act {
        /** The normal removal: a new key and a new code for everyone else. */
        REMOVE_NOW,
        /** It can't make the key now (a member's encryption key is missing): the hourly check tries again. */
        RETRY_HOURLY,
        NOTHING,
    }

    /**
     * [admin]: this phone is an admin of the current epoch and wasn't removed. [inCurrent]: the member is still in the
     * current epoch. [removalHeld]: a removal of them is held already (another admin's, or a rival one, which the rules
     * of "Two admins at once" settle). [keysComplete]: this phone has every remaining member's encryption keys.
     */
    fun act(admin: Boolean, inCurrent: Boolean, removalHeld: Boolean, keysComplete: Boolean): Act =
        if (!admin || !inCurrent || removalHeld) Act.NOTHING else if (keysComplete) Act.REMOVE_NOW else Act.RETRY_HOURLY
}

/**
 * What the screens say about leaving (spec/FORMAT.md, "Leaving the group"): the iPhone's LeaveWords, word for word.
 * Nothing here says the group "was told" or that anyone "was removed": the note leaving this phone isn't the group
 * getting it. [device]: "phone", or "computer" on the desktop app (Here.device), where it means this device; where
 * "a phone" means another one, it stays.
 */
object LeaveWords {
    fun sheetTitle(group: String) = "Leave $group?"
    fun sheetText(device: String = "phone") = "This $device forgets the group, its messages and its keys. A note asks the group's admins to remove you and change the key. To come back you need a new invite."
    fun leaveButton(group: String) = "Leave $group"
    fun cantTell(group: String, device: String = "phone") = "This $device isn't let in, so $group can't be told."
    fun chooseTitle(group: String) = "Who runs $group after you?"
    const val CHOOSE_TEXT = "Admins can let people in and remove people."
    const val LEAVE_WITHOUT_CHOOSING = "Leave without choosing"
    const val WITHOUT_CHOOSING_WARNING = "Nobody will be able to let people in or remove anyone."
    fun cantHandOver(device: String = "phone") = "This $device can't hand over now: it doesn't have every member's keys."
    const val LEAVE_WITHOUT_TELLING = "Leave without telling"
    fun waitingTitle(group: String) = "Telling $group…"
    const val WAITING_TEXT = "Waiting for a phone or relay to take the note."
    const val LEAVE_NOW = "Leave now"
    /** The toast after leaving. [noteLeft]: null when no note was sent. */
    fun left(group: String, noteLeft: Boolean?, device: String = "phone") =
        if (noteLeft == false) "You left $group. The note didn't leave this $device in time. It goes when a phone comes into reach." else "You left $group"
    const val TELL_FIRST = "Tell my groups first"
    fun tellFirstSub(device: String = "phone") = "Asks their admins' phones to remove this $device and change the key"
    const val TELLING_TITLE = "Telling your groups…"
    const val ERASE_NOW = "Erase now"
    fun empty(device: String = "phone") = "DarkProof is empty on this $device"
    /** The last screen's headline in place of [empty] when the erase couldn't remove everything (design review M4). */
    const val NOT_ALL = "Not everything could be erased"
    fun notAllLine(device: String = "phone") = "Delete DarkProof from this $device to remove the rest."
    /**
     * The final screen's line about the notes, or null when this device had no group. It names no group: the screen shows
     * right after an erase, on a device that may be taken at that moment. [groups]: the saved groups; [sent]: the notes sent
     * (none with the switch off, or where this device couldn't write); [left]: those a phone, a radio or a relay took in time.
     */
    fun erased(groups: Int, sent: Int, left: Int, device: String = "phone"): String? = when {
        groups <= 0 -> null
        sent <= 0 -> "Your groups were not told."
        left >= sent -> if (sent == 1) "The note left this $device." else "The notes left this $device."
        sent == 1 -> "The note didn't leave this $device in time."
        else -> "${sent - left} of $sent notes didn't leave this $device in time."
    }
    /** The chat's line on other phones. [waiting]: the member is still listed and this phone is no admin. */
    fun line(name: String, waiting: Boolean) = if (waiting) "$name left. Waiting for an admin's phone to change the key." else "$name left the group"
    /** Never said anywhere about leaving (lowercased): the "Never say" list. */
    val NEVER = listOf("their phones remove this one", "removed from the group", "your messages are deleted from their phones", "was told to remove")
}

/**
 * The leaving records of one group on this phone (spec/FORMAT.md, "Leaving the group", rules L2 and L3), and the removals
 * that followed a note, so the chat can say "Marco left the group" for them. Built only from notes that passed
 * [LeaveNote.verdict]. Same as the iPhone's LeaveBook. Not thread-safe: the engine guards it.
 */
class LeaveBook {
    /** A member who left: their note's `ts`, the number of the epoch it opened with, and when this phone took it. */
    data class Record(val member: String, val ts: Long, val epoch: Long, val taken: Long)
    /** Member id -> their record, while they are still in the current epoch. */
    val records = HashMap<String, Record>()
    /** The id of a removal epoch that took a member out after their note -> that record. Kept while the epoch is held. */
    val removals = HashMap<String, Record>()

    val isEmpty get() = records.isEmpty() && removals.isEmpty()

    private fun plain(id: String) = id.removePrefix("web-")

    /** L2: a valid note from [member]. Only the first counts while their record lasts. True if it was taken. */
    fun take(member: String, ts: Long, epoch: Long, now: Long): Boolean {
        val m = plain(member)
        if (records.containsKey(m)) return false
        records[m] = Record(m, ts, epoch, now)
        return true
    }

    /** The record of [member] while it lasts. */
    fun record(member: String): Record? = records[plain(member)]

    /** True while [member] left with a valid note: their record lasts, or the removal that followed it is kept. Their check-in clock stays (rule Q9). */
    fun left(member: String): Boolean { val m = plain(member); return records.containsKey(m) || removals.values.any { it.member == m } }

    /** L3: true if what [member] wrote at [ts] isn't shown, alerted, confirmed or counted: it is dated after their note. Also after the removal that followed it, while that epoch is held. */
    fun hides(member: String, ts: Long): Boolean {
        val m = plain(member)
        records[m]?.let { if (ts > it.ts) return true }
        return removals.values.any { it.member == m && ts > it.ts }
    }

    /**
     * L2's end. A record goes when its member is no longer in the current epoch ([inCurrent]), keeping, if a removal that
     * won took them out ([removal]: that epoch's id), what the chat needs for it; and 7 days after this phone took it. A
     * kept removal goes with its epoch ([held]: the ids of the epochs this phone holds). True if anything changed.
     */
    fun sweep(inCurrent: (String) -> Boolean, removal: (String) -> String?, held: Set<String>, now: Long): Boolean {
        val before = saved
        for ((m, r) in records.entries.toList()) if (!inCurrent(m) || now - r.taken >= LeaveNote.KEEP_MS) {
            records.remove(m)
            if (!inCurrent(m)) removal(m)?.let { removals[it] = r }
        }
        removals.keys.retainAll(held)
        return saved != before
    }

    /** As it is saved: "r <member> <ts> <epoch> <taken>" for each record, "x <removal epoch id> <member> <ts> <epoch> <taken>" for each removal. */
    val saved: List<String> get() =
        records.values.sortedBy { it.member }.map { "r ${it.member} ${it.ts} ${it.epoch} ${it.taken}" } +
            removals.entries.sortedBy { it.key }.map { (id, r) -> "x $id ${r.member} ${r.ts} ${r.epoch} ${r.taken}" }

    fun copy(): LeaveBook = parse(saved)
    override fun equals(other: Any?) = other is LeaveBook && other.saved == saved
    override fun hashCode() = saved.hashCode()

    companion object {
        /** From what [saved] wrote. A line that doesn't read is left out. */
        fun parse(saved: List<String>): LeaveBook = LeaveBook().also { b ->
            for (line in saved) {
                val f = line.split(' ')
                if (f.size == 5 && f[0] == "r") { val ts = f[2].toLongOrNull(); val e = f[3].toLongOrNull(); val t = f[4].toLongOrNull()
                    if (ts != null && e != null && t != null) b.records[f[1]] = Record(f[1], ts, e, t) }
                else if (f.size == 6 && f[0] == "x") { val ts = f[3].toLongOrNull(); val e = f[4].toLongOrNull(); val t = f[5].toLongOrNull()
                    if (ts != null && e != null && t != null) b.removals[f[1]] = Record(f[2], ts, e, t) }
            }
        }
    }
}

/**
 * When messages disappear on this device (spec/FORMAT.md, "Disappearing messages"). The group's timer comes from its
 * current key ([Keyring.timer]); what this adds is the settle time, and the counting from arrival that high-risk mode's
 * day uses too ([KeepLess]).
 */
object Disappear {
    /** A value deletes nothing until this device has had it, without a break, for this long. */
    const val SETTLE_MS = 10 * 60_000L

    /**
     * The group's timer value on this device and since when, by its own clock: saved next to the group's keys. A new value
     * starts its settle time; a value that only stays (a later key that inherits it) doesn't. Not thread-safe.
     */
    class Watch(seconds: Int? = null, since: Long = 0L) {
        var seconds: Int? = seconds; private set
        var since: Long = since; private set

        /** The group's timer is [seconds] now (null: not known). True if that is a change. */
        fun observe(seconds: Int?, now: Long): Boolean {
            if (seconds == this.seconds) { if (since > now) since = now; return false } // a clock set back must not hold a value off for long
            this.seconds = seconds; since = now
            return true
        }

        /**
         * The timer that deletes at [now], in ms: null while there is none (off, not known) or the value hasn't settled.
         * Two admins changing it at once can make a value that loses current for a moment on some phones, and deleting
         * can't be undone.
         */
        fun deletes(now: Long): Long? {
            val s = seconds ?: return null
            if (s <= 0 || now - since < SETTLE_MS) return null
            return s * 1_000L
        }

        /** When the value will have settled. */
        val settledAt: Long get() = since + SETTLE_MS

        /** As it is saved: "<seconds or ->:<since>". */
        val saved: String get() = "${seconds?.toString() ?: "-"}:$since"

        override fun equals(other: Any?) = other is Watch && other.seconds == seconds && other.since == since
        override fun hashCode() = (seconds ?: -1) * 31 + since.hashCode()
        fun copy() = Watch(seconds, since)

        companion object {
            /** From what [saved] wrote; null if it doesn't read. */
            fun parse(saved: String): Watch? {
                val f = saved.split(':')
                if (f.size != 2) return null
                val since = f[1].toLongOrNull() ?: return null
                if (f[0] != "-" && f[0].toIntOrNull() == null) return null
                return Watch(f[0].toIntOrNull(), since)
            }
        }
    }

    /**
     * The names that go now under a timer of [timerMs]: on this device for that long. [held]: name -> when it reached this
     * device ([KeepLess.Held.at]), or, written here, first left it; [KeepLess.UNSENT] for one that hasn't left, which never
     * goes. Exactly the timer's length after it arrived, it goes.
     */
    fun due(held: Map<String, Long>, timerMs: Long, now: Long): List<String> {
        if (timerMs <= 0) return emptyList()
        val cut = now - timerMs
        if (cut > now) return emptyList() // went round: a clock that far back removes nothing
        return held.filter { it.value in 1..cut }.map { it.key }
    }

    /** When the next of [held] (arrival times as for [due]) goes under a timer of [timerMs], or null if none will. */
    fun next(held: Collection<Long>, timerMs: Long): Long? {
        if (timerMs <= 0) return null
        return held.filter { it >= 1 }.minOrNull()?.let { it + timerMs }
    }

    /**
     * Whether a packet the timer removed stays in the store, to be carried for others for the rest of its week. Only if
     * nothing on this device opens it any more (its own message key was deleted when it was read): then it is one more
     * packet this device carries and can't read. One that a held key still opens (a receipt, a message sealed with the
     * epoch's key or still waiting for its sender's key) leaves the store. High-risk mode's day removes the packet either way.
     */
    fun carries(stillOpens: Boolean) = !stillOpens
}
