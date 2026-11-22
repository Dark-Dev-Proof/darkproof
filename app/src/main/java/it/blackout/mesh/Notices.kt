package it.blackout.mesh

// The 5th plaintext field beyond replies and reactions (spec/FORMAT.md, "The 5th field"): the group's timer for
// disappearing messages, and the note of a phone that leaves. [About] holds types 0x01 and 0x02. Same as the iPhone's
// Notices.swift (its timer and leave parts).

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
     * Under the chat's title: "They disappear 1 day after they reach each phone." [highRisk]: this device is in high-risk
     * mode, whose day counts unless the group's timer is shorter.
     */
    fun chatLine(seconds: Int?, highRisk: Boolean = false, device: String = "phone"): String {
        val timed = seconds != null && seconds > 0
        if (highRisk && !(timed && seconds!! <= KeepLess.KEEP_MS / 1000)) return "High-risk mode: they leave this $device 1 day after they arrive."
        if (!timed) return "They disappear after one to two weeks."
        return "They disappear ${label(seconds!!)} after they reach each phone."
    }

    /**
     * The "Messages disappear" row of Group security. [by]: who set it, as the reader sees them ("Anna", "you"), if this
     * device saw the change. [highRisk]: this device is in high-risk mode, which removes after a day whatever the group says.
     */
    fun securityLine(seconds: Int?, by: String?, highRisk: Boolean, device: String = "phone"): String {
        val day = " On this $device, 1 day after they arrive."
        if (seconds == null) return "Not known yet. Until an admin's key brings it, this $device keeps messages one to two weeks." + (if (highRisk) day else "")
        if (seconds <= 0) return "After one to two weeks" + (if (highRisk) ".$day" else "")
        val line = "After ${label(seconds)}" + (by?.let { ", set by $it" } ?: "")
        return if (highRisk && seconds > KeepLess.KEEP_MS / 1000) "$line.$day" else line
    }

    /** The line in the chat when the timer changed. [who]: "You", "Anna", or null when this device didn't see who set it. */
    fun changeLine(seconds: Int, who: String?): String {
        if (who == null) return if (seconds == 0) "The timer is off. Messages disappear after 1 to 2 weeks." else "Messages disappear after ${label(seconds)}"
        return if (seconds == 0) "$who switched the timer off. Messages disappear after 1 to 2 weeks." else "$who set messages to disappear after ${label(seconds)}"
    }

    /** What someone who can't change it reads in the timer sheet. */
    fun sheetLine(seconds: Int?, device: String = "phone"): String {
        if (seconds == null) return "Not known on this $device yet. Until an admin's key brings it, messages stay one to two weeks."
        return if (seconds > 0) "Messages disappear ${label(seconds)} after they reach each phone." else "Messages disappear after one to two weeks."
    }

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
     * Type `0x04`: the sender's check-in clock ([QuietClock]), its deadline in ms since 1970 UTC; 0 stops it. This app
     * keeps no clock yet: the message is an ordinary one (shown, alerted, confirmed, on LoRa), but its free text is never
     * read as a check-in, a help report or a question ([QuietClock.shown]).
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
 * The 5th field of a check-in clock message (spec/FORMAT.md, "If I go quiet", "The clock message"): type `0x04`, then the
 * deadline as a signed 64-bit big-endian number. This app keeps no clock yet (the clock itself is the iPhone's for now):
 * it only knows a clock message when it sees one, so that its free text is never read as a check-in. Same reading as the
 * iPhone's QuietClock.
 */
object QuietClock {
    const val TYPE: Byte = 0x04
    /** Type (1), deadline (8). */
    const val SIZE = 9
    const val MIN_MS = 5 * 60_000L
    const val MAX_MS = 24 * 3_600_000L
    /** How every clock text starts: what an app without the field shows of it, and the only free text this app shows of one. */
    const val PREFIX = "⏳"
    const val STOP = "⏳ Clock stopped. No need to check on me"

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

    /** The text a clock message carries: a start or more time, or the stop. */
    fun text(deadline: Long, ts: Long): String = if (deadline == 0L) STOP else "$PREFIX Check on me if I don't check in within ${duration(deadline - ts)}"

    /**
     * What this app shows of a clock message whose text is [said] (spec "The 5th field": nothing is read from it but a
     * position): the text when it is a clock's, else what the field says. Never a check-in, a help report or a question,
     * which a member could otherwise send under a field that other phones show as a clock.
     */
    fun shown(said: String, deadline: Long, ts: Long): String = if (said.startsWith(PREFIX)) said else text(deadline, ts)
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
    fun empty(device: String = "phone") = "This $device is empty"
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
