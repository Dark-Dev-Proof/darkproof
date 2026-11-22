package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Replies, reactions and journey notes against spec/test-vectors.txt (`kind: about`, `about-target`, `reactions`,
 * `receipt-notes`, `journey-note`), which an independent Python implementation made (spec/make-reply-vectors.py). The
 * iPhone's RepliesVectorsTests checks the same.
 */
class RepliesVectorsTest {
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = Hex.encode(b)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private val vectors: List<Map<String, String>> =
        File("../spec/test-vectors.txt").readText(Charsets.UTF_8).split(Regex("\n\\s*\n")).map { block ->
            block.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore(":") to line.substringAfter(":").removePrefix(" ") }
        }.filter { it.isNotEmpty() }
    private fun of(kind: String) = vectors.filter { it["kind"] == kind }

    @Test fun vectorCounts() {
        assertEquals(16, of("about").size)
        assertEquals(2, of("about-target").size)
        assertEquals(7, of("reactions").size)
        assertEquals(9, of("receipt-notes").size)
        assertEquals(7, of("journey-note").size)
    }

    /** A reader that knows four fields, as before the 5th existed: the signature, sender, name, text, and a 4th field if there is one. */
    private class Old(val sender: String, val name: String, val text: ByteArray, val fourth: ByteArray)
    private fun oldRead(plain: ByteArray): Old? {
        val b = plain.copyOfRange(64, plain.size); var i = 0
        fun next(): ByteArray? {
            if (i + 2 > b.size) return null
            val n = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (i + 2 + n > b.size) return null
            return b.copyOfRange(i + 2, i + 2 + n).also { i += 2 + n }
        }
        val s = next() ?: return null; val n = next() ?: return null; val t = next() ?: return null
        return Old(String(s, Charsets.UTF_8), String(n, Charsets.UTF_8), t, next() ?: ByteArray(0))
    }

    /** The message the vectors' replies and reactions are about: the one in `kind: message-key`, by its packet id and the first 8 bytes of SHA-256 of its signed message. */
    private fun named(): About.Target {
        val first = vectors.first { it["kind"] == "message-key" }
        return About.Target.parse(first["id"]!! + ":" + hex(sha(unhex(first["signed_message"]!!)).copyOf(8)))!!
    }

    @Test fun aboutVectorsOpenToTheirFields() {
        val named = named()
        var n = 0
        for (v in of("about")) {
            val what = v["id"]!!
            val secret = unhex(v["secret"]!!); val g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
            val position = v["position"]!!.toInt(); val number = v["number"]!!.toInt(); val ts = v["ts"]!!.toLong()
            val mk = MessageKeys.of(secret, listOf(v["member_0"]!!, v["member_1"]!!)).key(position, number)!!
            assertEquals(what, v["key"], hex(mk))
            val text = unhex(v["text_hex"]!!); val fourth = v["fourth"]?.let(::unhex) ?: ByteArray(0)
            val p = Packet(v["id"]!!, v["wire_ts"]!!.toLong(), 16, unhex(v["cipher"]!!))
            val o = g.openWith(mk, position, number, p); assertNotNull(what, o); o!!
            assertEquals(what, ts, o.message.ts); assertEquals(what, v["sender"], o.message.sender); assertEquals(what, v["name"], o.message.name)
            assertEquals(what, String(text, Charsets.UTF_8), o.message.text)
            assertArrayEquals(what, fourth, o.extra ?: ByteArray(0))
            assertArrayEquals(what, if (fourth.isEmpty()) null else fourth.copyOfRange(1, fourth.size), o.message.photo)
            assertEquals(what, v["signature"], hex(o.signature))
            assertEquals(what, hex(sha(unhex(v["signed_message"]!!))), hex(o.digest))
            assertEquals(what, v["mark"], About.Target.of(o).mark); assertEquals(what, v["id"], About.Target.of(o).id)
            // What a reader makes of the 5th field.
            when (v["reads"]) {
                "reply" -> { assertEquals(what, About.Reply(named), o.message.about); assertEquals(v["about_id"], named.id); assertEquals(v["about_mark"], named.mark) }
                "reaction" -> { assertEquals(what, About.Reaction(named), o.message.about); assertEquals(v["about_id"], named.id); assertEquals(v["about_mark"], named.mark) }
                "nothing" -> assertEquals(what, About.Nothing, o.message.about)
                "ordinary" -> assertNull(what, o.message.about)
                else -> fail("unknown `reads` in $what")
            }
            assertEquals(what, o.message.about, About.read(v["fifth"]?.let(::unhex), text))
            // A reader without the 5th field reads the same sender, name, text and photo field from the same bytes.
            val old = oldRead(unhex(v["plain"]!!)); assertNotNull(what, old); old!!
            assertEquals(what, v["sender"], old.sender); assertEquals(what, v["name"], old.name); assertArrayEquals(what, text, old.text); assertArrayEquals(what, fourth, old.fourth)
            // What this app writes for the same message is byte for byte the vector (not for fields it never writes).
            val about = o.message.about
            if (about != null && about != About.Nothing && v["sixth"] == null) {
                val again = g.sealWith(mk, position, number, v["id"]!!, ts, v["sender"]!!, v["name"]!!, String(text, Charsets.UTF_8), o.message.photo,
                    iv = unhex(v["iv"]!!), about = about) { unhex(v["signature"]!!) }
                assertEquals(what, v["cipher"], hex(again)); assertEquals(what, v["wire_ts"], g.hideTime(ts, again).toString())
                n++
            }
        }
        assertEquals("the vectors this app writes byte for byte", 5, n)
        assertEquals(setOf("reply", "reaction", "nothing", "ordinary"), of("about").map { it["reads"] }.toSet())
        assertTrue("a photo and a reply together", of("about").any { it["fourth"] != null && it["reads"] == "reply" })
        // Only listed reactions are reactions: a check mark and a word are not shown at all.
        for ((text, reads) in listOf("e29c85" to "nothing", "6f6b" to "nothing", "e29da4" to "nothing", "e29da4efb88f" to "reaction", "f09f918d" to "reaction", "" to "reaction"))
            assertEquals(text, reads, of("about").first { it["text_hex"] == text && it["fifth"]?.startsWith("02") == true }["reads"])
    }

    /** A reply or reaction names a message, not a packet: the same signed message sealed again is still its target, another message under the same id is not. */
    @Test fun aboutTargetVectors() {
        val named = named()
        val first = vectors.first { it["kind"] == "message-key" }
        val original = Packet(first["id"]!!, first["wire_ts"]!!.toLong(), 16, unhex(first["cipher"]!!))
        for (v in of("about-target")) {
            val secret = unhex(v["secret"]!!); val g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
            val position = v["position"]!!.toInt(); val number = v["number"]!!.toInt()
            val mk = MessageKeys.of(secret, listOf(v["member_0"]!!, v["member_1"]!!)).key(position, number)!!
            val p = Packet(v["id"]!!, v["wire_ts"]!!.toLong(), 16, unhex(v["cipher"]!!))
            assertEquals(original.id, p.id)
            assertNotEquals("another packet than the one in `kind: message-key`", MeshRouter.key(original), MeshRouter.key(p))
            assertEquals(v["content_key"], MeshRouter.key(p).takeLast(16))
            val o = g.openWith(mk, position, number, p)!!
            assertArrayEquals(unhex(v["text_hex"]!!), o.message.text.toByteArray(Charsets.UTF_8))
            assertEquals(hex(sha(unhex(v["signed_message"]!!))), hex(o.digest))
            assertEquals(v["mark"], About.Target.of(o).mark)
            assertEquals(v["text_hex"], v["named"] == "yes", About.Target.of(o) == named)
            // On a phone that shows only this packet: is it found by what the replies and reactions name?
            val row = o.message.copy(group = "g", mark = About.Target.of(o))
            assertEquals(v["named"] == "yes", About.targets(listOf(row), "g")[named.key] != null)
        }
        assertEquals(setOf("yes", "no"), of("about-target").map { it["named"] }.toSet())
    }

    /** The rules of "Reactions" as a reader applies them to what it holds: only listed reactions that name a message it shows count. */
    @Test fun reactionVectors() {
        for (v in of("reactions")) {
            val target = About.Target.parse(v["target"]!!)!!
            // The one message on this reader's phone; every `r` is a type 0x02 message, so none of them is a message to react to.
            val rows = arrayListOf(Message(target.id, 1, "9999999999999999", "Ann", "hello", group = "g", mark = target))
            val held = Reactions()
            for (k in 1..9) {
                val line = v["r$k"] ?: break
                val f = line.split(" ")
                assertEquals(line, 6, f.size)
                val text = if (f[5] == "-") ByteArray(0) else unhex(f[5]); val names = About.Target.parse(f[4])!!
                val about = About.read(byteArrayOf(About.REACTION) + unhex(names.id) + unhex(names.mark), text)!!
                rows += Message(f[2], f[1].toLong(), f[0], "", String(text, Charsets.UTF_8), group = "g", about = about, mark = About.Target.parse("${f[2]}:${f[3]}"))
                // A text that isn't listed is no reaction: nothing is held for it.
                if (about !is About.Reaction) { assertEquals(line, About.Nothing, about); continue }
                held.add("${f[2]}:${f[3]}", Reactions.Entry("g", about.target.key, f[0], String(text, Charsets.UTF_8), f[1].toLong(), f[2]))
            }
            // Reactions are shown only under what can be a target: the message, never one of the type 0x02 messages.
            assertEquals(v["target"], setOf(target.key), About.targets(rows, "g").keys)
            val chips = held.chips("g", target.key, "me")
            val got = if (chips.isEmpty()) "-" else chips.joinToString(" ") { "${hex(it.text.toByteArray(Charsets.UTF_8))}=${it.count}" }
            assertEquals(v.toString(), v["counts"], got)
        }
    }

    @Test fun receiptNoteVectors() {
        for (v in of("receipt-notes")) {
            val text = v["text"]!!
            assertEquals("the reader without notes: ${text.take(60)}", if (v["ids"] == "-") "" else v["ids"], Receipt.ids(text).joinToString(" "))
            val got = Receipt.entries(text).joinToString(" ") { e -> e.note?.let { n -> "${e.id}/${n.hops}/${n.link.letter ?: '?'}" } ?: e.id }
            assertEquals(text.take(60), if (v["entries"] == "-") "" else v["entries"], got)
            assertEquals(Receipt.ids(text), Receipt.entries(text).map { it.id })
        }
    }

    @Test fun journeyNoteVectors() {
        for (v in of("journey-note")) {
            val link = Journey.Link.of(v["link"]!![0].code)!!
            // The ttl as the phone holds it: a received ttl above 16 is lowered to 16 (MeshRouter.clamp).
            val p = MeshRouter.clamp(Packet("0000000000000001", 1, v["ttl"]!!.toInt(), ByteArray(0)))
            assertEquals(v.toString(), v["note"], Journey.Note.of(p.ttl, link)?.token)
            assertEquals(v.toString(), v["note"], Journey.Note.of(v["ttl"]!!.toInt(), link)?.token)
        }
    }
}
