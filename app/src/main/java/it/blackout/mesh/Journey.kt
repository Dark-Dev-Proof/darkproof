package it.blackout.mesh

/**
 * "How it got there" (spec/FORMAT.md, "Journey notes"): what a reader's receipt may say about the way a message took to
 * its phone, and what the writer's phone remembers about how the message left. Same as the iPhone's Journey.
 */
object Journey {
    /** The kind of link a packet came or went over. Never which phone, and never a link id. [letter]: as written; null for [OTHER]. */
    enum class Link(val letter: Char?, val over: String) {
        BLUETOOTH('b', "Over Bluetooth"), WIFI('w', "Over a Wi-Fi network"), DIRECT('p', "Over phone-to-phone Wi-Fi"),
        RADIO('r', "Over the radio"), INTERNET('i', "Over the internet"),
        /** A letter this app doesn't know: a newer app's link. Read, never written. */
        OTHER(null, "Over another link");

        companion object {
            /**
             * `b` Bluetooth, `w` a Wi-Fi network, `p` phone-to-phone Wi-Fi (Apple peer-to-peer, Nearby), `r` LoRa radio, `i` an
             * internet relay. Any other lowercase letter a–z is [OTHER]; anything else is no link.
             */
            fun of(letter: Int): Link? = entries.firstOrNull { it.letter?.code == letter } ?: if (letter in 'a'.code..'z'.code) OTHER else null

            /** The kind of link a router peer is (Android's link names); null for none this app knows (a test link, the Wi-Fi board). */
            fun ofPeer(peer: String?): Link? = when {
                peer == null -> null
                BleLink.owns(peer) -> BLUETOOTH
                WifiLinks.owns(peer) -> WIFI
                NearbyPeers.owns(peer) -> DIRECT
                peer == LoraLink.PEER || peer == MeshCoreLink.PEER -> RADIO
                NostrLink.owns(peer) -> INTERNET
                else -> null
            }
        }
    }

    /** Most hops a note can say: a new packet starts with this many ([MeshRouter.MAX_TTL]). */
    const val MAX_HOPS = 16

    /**
     * A journey note, `h<hops><link>`: after about how many hops, and over which kind of link, the first copy of a message
     * reached the reader's phone. The ttl it is counted from is not authenticated: hops are "about".
     */
    data class Note(val hops: Int, val link: Link) {
        /** As it is written, without a leading zero; null for a link this app doesn't write. */
        val token: String? get() = link.letter?.let { "h$hops$it" }

        /** "Over Bluetooth · about 3 hops". Without a hop count when the note says none. */
        val text: String get() = if (hops == 0) link.over else "${link.over} · about $hops ${if (hops == 1) "hop" else "hops"}"

        companion object {
            /**
             * What a reader's phone notes for a message whose first copy came with [ttl] hops left over [link]: 16 minus the
             * ttl, not below 0. Null when the link isn't one this app writes.
             */
            fun of(ttl: Int, link: Link): Note? = if (link.letter == null) null else Note(minOf(MAX_HOPS, maxOf(0, MeshRouter.MAX_TTL - ttl)), link)

            /** Reads a token: `h`, 1 or 2 ASCII digits, one lowercase letter a–z, nothing else. Null if it doesn't parse or says more than 16 hops. */
            fun parse(token: String): Note? {
                val b = token.toByteArray(Charsets.UTF_8)
                if ((b.size != 3 && b.size != 4) || b[0] != 'h'.code.toByte()) return null
                val link = Link.of(b[b.size - 1].toInt() and 0xFF) ?: return null
                var n = 0
                for (i in 1 until b.size - 1) { val d = b[i].toInt() and 0xFF; if (d !in '0'.code..'9'.code) return null; n = n * 10 + (d - '0'.code) }
                return if (n <= MAX_HOPS) Note(n, link) else null
            }
        }
    }

    /** Over which kind of link one of this phone's messages was first handed on, and when by this phone's clock: kept next to what the phone remembers for "Sent". */
    data class Left(val link: Link, val at: Long) {
        /** As it is saved: the link's letter, then the time in ms. Null for a link that has no letter. */
        val saved: String? get() = link.letter?.let { "$it$at" }

        companion object {
            fun parse(saved: String): Left? {
                val first = saved.firstOrNull() ?: return null
                val link = Link.of(first.code)?.takeIf { it != Link.OTHER } ?: return null
                val rest = saved.substring(1)
                if (rest.isEmpty() || !rest.all { it in '0'..'9' }) return null
                val at = rest.toLongOrNull() ?: return null
                return Left(link, at)
            }
        }
    }

    const val SEALED = "Sealed the whole way"
    const val CARRIED = "Carried by other phones on the way. They can't read it, and nobody sees whose they are."
    /** How long a packet is passed on ([MeshRouter], spec "Forwarding rules"). */
    private const val PASSED_ON_MS = 7 * 24 * 3_600_000L

    /**
     * One line of the "How it got there" screen. [detail]: the line under the title, if there is one. [at]: a time to show
     * (ms): when it left, or when the reader's receipt was written. [later]: "4 min later".
     */
    data class Step(val kind: Kind, val title: String, val detail: String?, val at: Long?, val later: String?) {
        enum class Kind { SAVED, LEFT, REACHED, UNCONFIRMED }
    }

    /**
     * How long after the message a receipt was written, by the two phones' clocks: "Less than a minute later", "4 min
     * later", "3 h later", "2 days later". Null when the receipt is dated before the message: the clocks differ.
     */
    fun later(ms: Long): String? {
        if (ms < 0) return null
        val min = ms / 60_000
        return when {
            min < 1 -> "Less than a minute later"
            min < 60 -> "$min min later"
            min < 48 * 60 -> "${min / 60} h later"
            else -> "${min / (24 * 60)} days later"
        }
    }

    /**
     * The lines of the screen for one of this phone's own group messages, top to bottom, without the last one ([SEALED]).
     * [written]: the message's time. [handedOff]: it was given to a phone, a radio or a relay ("Sent"); [left]: over what
     * and when, if the phone noted it. [readers]: who confirmed it, earliest first ([Deliveries]).
     */
    fun steps(written: Long, handedOff: Boolean, left: Left?, readers: List<Reader>, now: Long): List<Step> {
        if (!handedOff && readers.isEmpty()) {
            // Packets are passed on for 7 days: after that this one no longer leaves.
            val late = now - written > PASSED_ON_MS
            return listOf(Step(Step.Kind.SAVED, "Saved on this phone",
                if (late) "No phone was in reach for 7 days. It is no longer passed on." else "No phone is in reach yet. It leaves by itself when one is.", written, null))
        }
        val out = arrayListOf(Step(Step.Kind.LEFT, "Left your phone", left?.link?.over, left?.at, null))
        for (r in readers) out += Step(Step.Kind.REACHED, "Reached ${r.name.ifEmpty { "someone" }}", r.note?.text, r.ts, later(r.ts - written))
        if (readers.isEmpty()) out += Step(Step.Kind.UNCONFIRMED, "Not confirmed by anyone yet", null, null, null)
        return out
    }

    /** True when the line about other phones belongs on the screen: a reader's note says 2 hops or more. */
    fun wasCarried(readers: List<Reader>): Boolean = readers.any { (it.note?.hops ?: 0) >= 2 }
}
