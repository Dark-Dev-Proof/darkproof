package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Voice notes against spec/test-vectors.txt (`kind: voice-field`, `voice-trim`, `voice-message`), made by
 * spec/make-reply-vectors.py from the iPhone's rules, run through Android's own [Voice] rules and sealing. VoiceNotesTest
 * checks the same rules with packets of its own.
 */
class VoiceVectorsTest {
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
        assertEquals(31, of("voice-field").size); assertEquals(1, of("voice-trim").size); assertEquals(1, of("voice-message").size)
    }

    @Test fun fields() {
        for (v in of("voice-field")) {
            val field = unhex(v["field"]!!); val text = unhex(v["text_hex"]!!)
            assertEquals("$v", v["bytes"]!!.toInt(), field.size - 1)
            val kept = Voice.of(field, text)
            if (v["reads"] == "voice") {
                assertNotNull("${v["bytes"]} bytes: kept", kept)
                val n = Voice.split(kept!!)!!.size
                assertEquals(v["packets"]!!.toInt(), n)
                assertEquals(v["ms"]!!.toInt(), Voice.durationMs(n)); assertEquals(v["seconds"]!!.toInt(), Voice.seconds(n)); assertEquals(v["label"], Voice.label(Voice.seconds(n)))
                assertArrayEquals("what a writer sends of the same packets", field, byteArrayOf(Voice.TYPE) + Voice.frames(Voice.split(kept)!!))
            } else assertNull("${v["bytes"]} bytes, text ${v["text_hex"]}: ignored, the message is its text", kept)
        }
        assertEquals(setOf("voice", "text"), of("voice-field").map { it["reads"] }.toSet())
    }

    /** What a writer keeps of what an encoder wrote: the first 500 of Apple's 501 packets. */
    @Test fun trim() {
        val v = of("voice-trim").single()
        val fixture = File("../app/src/test/voice/${v["fixture"]}").readBytes()
        assertEquals(v["fixture_sha256"], hex(sha(fixture)))
        assertEquals(v["fixture_bytes"]!!.toInt(), fixture.size - 1)
        val raw = ArrayList<ByteArray>(); var i = 1
        while (i < fixture.size) { val n = fixture[i].toInt() and 0xFF; raw += fixture.copyOfRange(i + 1, i + 1 + n); i += 1 + n }
        assertEquals(v["fixture_packets"]!!.toInt(), raw.size)
        assertEquals("text", v["fixture_reads"]); assertNull("as it came, readers ignore it", Voice.packets(fixture))
        val kept = Voice.forSending(raw) as Voice.Kept.Ok
        assertEquals(v["kept_packets"]!!.toInt(), kept.packets.size); assertEquals(v["kept_bytes"]!!.toInt(), kept.frames.size)
        val field = byteArrayOf(Voice.TYPE) + kept.frames
        assertEquals(v["kept_sha256"], hex(sha(field)))
        assertEquals("voice", v["reads"]); assertNotNull(Voice.of(field, Voice.PLACEHOLDER.toByteArray(Charsets.UTF_8)))
        assertEquals(v["seconds"]!!.toInt(), Voice.seconds(kept.packets.size))
    }

    /** A voice note that answers a message: opened, read as a voice note and a reply, and sealed again to the same bytes. */
    @Test fun aVoiceReply() {
        val v = of("voice-message").single()
        val secret = unhex(v["secret"]!!); val g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        val position = v["position"]!!.toInt(); val number = v["number"]!!.toInt(); val ts = v["ts"]!!.toLong()
        val mk = MessageKeys.of(secret, listOf(v["member_0"]!!, v["member_1"]!!)).key(position, number)!!
        assertEquals(v["key"], hex(mk))
        val o = g.openWith(mk, position, number, Packet(v["id"]!!, v["wire_ts"]!!.toLong(), 16, unhex(v["cipher"]!!)))!!
        val m = o.message
        assertEquals(ts, m.ts); assertEquals(v["sender"], m.sender); assertEquals(v["name"], m.name)
        assertEquals(v["text_hex"], hex(m.text.toByteArray(Charsets.UTF_8))); assertEquals(Voice.PLACEHOLDER, m.text)
        assertEquals(v["fourth"], hex(o.extra!!))
        val frames = m.voice!!
        assertArrayEquals(unhex(v["fourth"]!!).copyOfRange(1, unhex(v["fourth"]!!).size), frames)
        val n = Voice.split(frames)!!.size
        assertEquals(v["packets"]!!.toInt(), n); assertEquals(v["seconds"]!!.toInt(), Voice.seconds(n))
        assertEquals(v["fifth"], hex(o.aboutField!!))
        val target = About.Target.parse("${v["about_id"]}:${v["about_mark"]}")!!
        assertEquals(About.Reply(target), m.about)
        assertEquals(hex(sha(unhex(v["signed_message"]!!))), hex(o.digest)); assertEquals(v["mark"], About.Target.of(o).mark)
        assertEquals("voice", v["reads"]); assertEquals(v["older"], m.text) // an app without voice notes shows the text
        val again = g.sealWith(mk, position, number, v["id"]!!, ts, v["sender"]!!, v["name"]!!, Voice.PLACEHOLDER, iv = unhex(v["iv"]!!),
            about = About.Reply(target), voice = frames) { unhex(v["signature"]!!) }
        assertEquals(v["cipher"], hex(again))
    }
}
