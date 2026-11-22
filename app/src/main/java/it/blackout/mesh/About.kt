package it.blackout.mesh

/**
 * What a group message is about (spec/FORMAT.md, "Replies and reactions"): a 5th plaintext field, after the photo field,
 * names one earlier message. The field is inside the signed body, so it is the sender's own statement; what the target
 * says is always taken from the reader's own copy of it, never from the message that names it. Same as the iPhone's About.
 */
sealed class About {
    /** Type `0x01`: an ordinary message, shown with a quote of its target. */
    data class Reply(override val target: Target) : About()
    /** Type `0x02` with a listed reaction's text, or none: never a chat row, counted under its target ([Reactions]). */
    data class Reaction(override val target: Target) : About()
    /** Type `0x02` with any other text ([ReactionList.accepts]): nothing of it is shown, neither as a reaction nor as a message. Never written. */
    object Nothing : About() { override val target: Target? get() = null; override fun toString() = "Nothing" }

    /** The target, for a reply or a reaction. */
    abstract val target: Target?

    /**
     * True for a message that is never a chat row: a reaction, or a type `0x02` message that isn't one. It is not
     * alerted, not counted as unread, not confirmed with a receipt, and shows no "Delivered".
     */
    val isSilent: Boolean get() = this !is Reply

    /** The 5th field as it is written, or null for [Nothing], which is never written. */
    val field: ByteArray? get() = when (this) {
        is Reply -> byteArrayOf(REPLY) + Hex.decode(target.id) + Hex.decode(target.mark)
        is Reaction -> byteArrayOf(REACTION) + Hex.decode(target.id) + Hex.decode(target.mark)
        Nothing -> null
    }

    /**
     * One message: its packet id and the first 8 bytes of the digest its signature is over (SHA-256 of the signed message,
     * spec "Signatures", "Message keys"). The digest covers the id, the time and the whole body, not the IV or the
     * ciphertext: a copy of the message sealed again is the same target on every phone that opened and checked it, and
     * another message sent under the same id is not. It is what a chat tells messages apart by ([GroupCrypto.Opened.mark]).
     * [id] and [mark]: 16 lowercase hex chars each.
     */
    data class Target internal constructor(val id: String, val mark: String) {
        /** As [GroupCrypto.Opened.mark] writes it: "<id>:<first 8 bytes of the signed digest>". */
        val key: String get() = "$id:$mark"

        companion object {
            /** What names the message an opened packet carries. */
            fun of(o: GroupCrypto.Opened) = Target(o.message.id.take(16), Hex.encode(o.digest.copyOf(8)))

            /** From [key]; null unless it is two runs of 16 lowercase hex chars with ":" between. */
            fun parse(key: String): Target? {
                val parts = key.split(':')
                if (parts.size != 2 || !Senders.isMemberId(parts[0]) || !Senders.isMemberId(parts[1])) return null
                return Target(parts[0], parts[1])
            }
        }
    }

    companion object {
        const val REPLY: Byte = 0x01
        const val REACTION: Byte = 0x02
        /** Type (1), the target's packet id (8), the first 8 bytes of the target's signed digest (8). */
        const val SIZE = 17

        /**
         * What a reader makes of a 5th field. [field]: its bytes, null if the body ends before it. [text]: the bytes of the
         * message's `text` field. Null, an ordinary message, for a field of another length or another type: padding reads
         * as an empty 5th field, and newer apps may write types this one doesn't know.
         */
        fun read(field: ByteArray?, text: ByteArray): About? {
            if (field == null || field.size != SIZE) return null
            val t = Target(Hex.encode(field.copyOfRange(1, 9)), Hex.encode(field.copyOfRange(9, 17)))
            return when (field[0]) {
                REPLY -> Reply(t)
                REACTION -> if (ReactionList.accepts(text)) Reaction(t) else Nothing
                else -> null
            }
        }

        /**
         * What a reply or reaction to [m] names, or null if [m] can't be one's target: only a group message this phone shows
         * as a message, whose signature checked. [group]: the code of the group the reply or reaction is written to
         * ([Message.group]); a message of another group is no target.
         */
        fun target(m: Message, group: String?): Target? {
            if (group == null || m.public || !m.verified || m.group != group || m.about?.isSilent == true) return null
            return m.mark
        }

        /**
         * The reader's own messages of one group that can be a target, by [Target.key]. [rows]: what the phone shows. A
         * reply or reaction is looked up here and nowhere else, so nothing is ever shown for a target that is on the Public
         * channel, in another group, not checked yet, or itself a reaction.
         */
        fun targets(rows: Iterable<Message>, group: String?): Map<String, Message> {
            val out = HashMap<String, Message>()
            for (m in rows) target(m, group)?.let { t -> out.putIfAbsent(t.key, m) }
            return out
        }
    }
}

/**
 * The reactions (spec/FORMAT.md, "Reactions"): a group message of type `0x02` whose `text` is one of the listed reactions,
 * byte for byte, or empty. Nothing else is ever shown under a message, so nobody can put text there. Same as the iPhone's Reaction.
 */
object ReactionList {
    /** No reaction is ever listed that is longer than this. */
    const val MAX_BYTES = 16

    /**
     * The spec's list, in its order; the heart carries its variation selector (`e2 9d a4 ef b8 8f`). `✅` is left out on
     * purpose: an app that doesn't know reactions shows a reaction as an ordinary message, and any message that starts
     * with `✅` is an "I'm safe" check-in there ([CheckIn.of]).
     */
    val listed: List<String> = listOf(cp(0x1F44D), "❤️", cp(0x1F602), cp(0x1F62E), cp(0x1F64F))
    /** What the app offers: the whole list. */
    val offered: List<String> get() = listed
    private val bytes: List<ByteArray> = listed.map { it.toByteArray(Charsets.UTF_8) }
    private fun cp(c: Int) = String(Character.toChars(c))

    /** Where [text] stands in the list, comparing bytes; null if it isn't listed. */
    fun place(text: ByteArray): Int? = bytes.indexOfFirst { it.contentEquals(text) }.takeIf { it >= 0 }

    /** True if the bytes of a `text` field are a reaction's: exactly a listed reaction, or none at all, which takes the sender's reaction back. */
    fun accepts(text: ByteArray): Boolean = text.isEmpty() || place(text) != null

    /** True if this app may send [text] as a reaction: only a listed one. */
    fun sendable(text: String): Boolean = place(text.toByteArray(Charsets.UTF_8)) != null
}

/**
 * Every member's reactions this phone holds, and which of them count (spec/FORMAT.md, "Reactions"). Built only from packets
 * a group key opened and whose signature checked for a member; what is shown follows from the set of those packets alone,
 * whatever order they came in. Not thread-safe: the engine's messages lock guards it. Same as the iPhone's Reactions.
 */
class Reactions {
    /**
     * One reaction packet. [group]: the id of the group whose key opened it. [target]: [About.Target.key]. [sender]: the
     * member (this phone's own mark for its own; a `web-` in front doesn't count). [text]: "" takes the reaction back.
     * [id]: the packet's id.
     */
    class Entry(val group: String, val target: String, sender: String, val text: String, val ts: Long, val id: String) {
        val sender: String = sender.removePrefix("web-")
        /** The later one counts; at the same time, the greater packet id (16 lowercase hex chars, so text order is number order). */
        internal fun beats(o: Entry) = ts > o.ts || (ts == o.ts && id > o.id)
        override fun equals(other: Any?) = other is Entry && other.group == group && other.target == target && other.sender == sender &&
            other.text == text && other.ts == ts && other.id == id
        override fun hashCode() = listOf(group, target, sender, text, ts, id).hashCode()
    }

    /** A reaction under a message: how many members have it now, and whether this phone's user is one of them. */
    data class Chip(val text: String, val count: Int, val mine: Boolean)

    private val entries = HashMap<String, Entry>()             // the name a reaction's packet goes by in the store -> what it says
    private val targets = HashMap<String, HashSet<String>>()   // group and target -> the names of the reactions to it

    private fun place(group: String, target: String) = "$group|$target"

    /**
     * A checked reaction. [name]: what its packet goes by in the store ([StoreCap.name]), so that it leaves with the packet
     * ([forget]). Adding the same one again changes nothing. One whose text is neither listed nor empty isn't taken at all
     * ([About.read] lets none through): it must not replace the member's reaction either.
     */
    fun add(name: String, e: Entry) {
        if (entries.containsKey(name) || !ReactionList.accepts(e.text.toByteArray(Charsets.UTF_8))) return
        entries[name] = e
        targets.getOrPut(place(e.group, e.target)) { HashSet() } += name
    }

    /** The packets [names] left the store: their reactions no longer count. */
    fun forget(names: Iterable<String>) {
        for (name in names) {
            val e = entries.remove(name) ?: continue
            val p = place(e.group, e.target)
            targets[p]?.let { it -= name; if (it.isEmpty()) targets.remove(p) }
        }
    }

    val count: Int get() = entries.size

    /** Each member's reaction to [target] that counts: one per member, their latest. [hidden]: members whose reactions this phone doesn't show (blocked here). */
    fun current(group: String, target: String, hidden: (String) -> Boolean = { false }): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        for (name in targets[place(group, target)].orEmpty()) {
            val e = entries[name] ?: continue
            if (hidden(e.sender)) continue
            val had = out[e.sender]
            if (had != null && !e.beats(had)) continue
            out[e.sender] = e
        }
        return out
    }

    /** This phone's own reaction to [target] that counts ("" once taken back), or null if it never reacted. */
    fun mine(group: String, target: String, me: String): Entry? = current(group, target)[me]

    /** What is shown under [target]: each listed reaction that a member has, with its count, in the list's order. A member who took theirs back counts for none. */
    fun chips(group: String, target: String, me: String, hidden: (String) -> Boolean = { false }): List<Chip> {
        val n = IntArray(ReactionList.listed.size); val mine = BooleanArray(ReactionList.listed.size)
        for (e in current(group, target, hidden).values) {
            val i = ReactionList.place(e.text.toByteArray(Charsets.UTF_8)) ?: continue
            n[i]++; if (e.sender == me) mine[i] = true
        }
        return n.indices.filter { n[it] > 0 }.map { Chip(ReactionList.listed[it], n[it], mine[it]) }
    }

    override fun equals(other: Any?) = other is Reactions && other.entries == entries
    override fun hashCode() = entries.hashCode()
}

/** The quote above a reply and the strip above the composer (spec/FORMAT.md, "Replies"): built from the reader's own copy of the target. */
object Quote {
    /** Shown instead of a quote when the phone has no checked copy of the target: it expired, never arrived, or its signature hasn't checked. */
    const val MISSING = "A message this phone doesn't have"
    /** Shown instead when the target is here but hidden on this phone: its sender is blocked, or the message was hidden or reported. */
    const val HIDDEN = "A message hidden on this phone"
    /** Longer first lines are cut (in characters as a reader sees them): the screen shows one line anyway. */
    const val MAX_CHARS = 120

    private fun isNewline(c: Char) = c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u0085' || c == ' ' || c == ' '
    private fun isBlank(c: Char) = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** The first line of what [m] says: a photo says its caption, or "Photo"; anything else the first line of its text that isn't empty. */
    fun line(m: Message): String {
        val text = if (m.photo == null) m.text else Photo.caption(m.text)
        val lines = ArrayList<String>(); var from = 0
        for (i in text.indices) if (isNewline(text[i])) { lines += text.substring(from, i); from = i + 1 }
        lines += text.substring(from)
        val first = lines.map { l -> l.trim { isBlank(it) } }.firstOrNull { it.isNotEmpty() } ?: ""
        if (first.isEmpty()) return if (m.photo == null) "" else "Photo"
        // Cut by characters as they are drawn (an emoji with a skin tone is one), like the iPhone.
        val it = java.text.BreakIterator.getCharacterInstance().apply { setText(first) }
        var end = 0; var n = 0
        while (n < MAX_CHARS) { val next = it.next(); if (next == java.text.BreakIterator.DONE) return first; end = next; n++ }
        return if (it.next() == java.text.BreakIterator.DONE) first else first.substring(0, end) + "…"
    }

    /** Whose message it is, as the reader sees it. [me]: this phone's mark for its own messages. */
    fun name(m: Message, me: String): String = if (m.sender == me) "You" else m.name.ifEmpty { "Someone" }
}

/** Lowercase hex, for the 5th field and the target names. */
internal object Hex {
    private const val DIGITS = "0123456789abcdef"
    fun encode(b: ByteArray): String { val c = CharArray(b.size * 2); for (i in b.indices) { val v = b[i].toInt() and 0xFF; c[2 * i] = DIGITS[v ushr 4]; c[2 * i + 1] = DIGITS[v and 15] }; return String(c) }
    fun decode(s: String): ByteArray = ByteArray(s.length / 2) { i -> ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte() }
}
