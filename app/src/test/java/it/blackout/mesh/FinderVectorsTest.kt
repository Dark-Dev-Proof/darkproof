package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Finder against spec/test-vectors.txt (`kind: finder-field`, `finder-fit`, `finder-bands`), made by
 * spec/make-reply-vectors.py (argument `finder`). The iPhone's FinderTests checks the same vectors, so both apps take the
 * same note from the same field, leave it out of the same packets and show the same band and trend each second.
 */
class FinderVectorsTest {
    private val vectors: List<Map<String, String>> =
        File("../spec/test-vectors.txt").readText(Charsets.UTF_8).split(Regex("\n\\s*\n")).map { block ->
            block.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore(":") to line.substringAfter(":").removePrefix(" ") }
        }.filter { it.isNotEmpty() }
    private fun of(kind: String) = vectors.filter { it["kind"] == kind }
    private val code = "test-group-finder"
    private val group by lazy { GroupCrypto(code) }
    private val sig = ByteArray(64) { 7 }
    private val iv = ByteArray(12) { 9 }
    private val anna = "1111111111111111"; private val marco = "2222222222222222"
    private val t0 = 1_794_000_000_000L
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun vectorCounts() {
        assertEquals(13, of("finder-field").size); assertEquals(6, of("finder-fit").size); assertEquals(8, of("finder-bands").size)
    }

    /** `kind: finder-field`: sealed for real, so the field goes the whole way through a packet; then what People take from it. */
    @Test fun readingTheField() {
        of("finder-field").forEachIndexed { n, v ->
            val field = v["field"]!!.takeIf { it != "-" }?.let(::unhex); val text = v["text"]!!
            val id = "e6080000000001%02x".format(n)
            val c = group.seal(id, t0, marco, "Marco", text, iv = iv, fifth = field) { sig }
            val m = group.openSigned(Packet(id, t0, 16, c))!!.message
            assertEquals("$v", text, m.text)
            assertEquals("$v", FinderNote.read(field, text.toByteArray(Charsets.UTF_8)), m.finder)
            assertNull("a finder note is never a reply or a reaction: $v", m.about)
            // People take it only from a checked group message (`checked: not-yet`: the sender's key hasn't come, so not checked).
            val public = v["channel"] == "public"
            val shown = Message(id, t0, marco, "Marco", text, public = public, group = if (public) null else code,
                verified = v["checked"] == "yes", finder = m.finder)
            val p = Person.all(listOf(shown), anna).firstOrNull { it.id == marco }
            assertEquals("$v", v["reads"], p?.finder ?: "ignored")
        }
    }

    /** `kind: finder-fit`: radio first (F3), with the real packet's sizes. */
    @Test fun radioFirst() {
        val note = FinderNote.field("a1b2c3d4e5f6")!!
        of("finder-fit").forEachIndexed { n, v ->
            val keyed = v["keyed"] == "yes"; val sender = v["sender"]!!; val name = v["name"]!!; val text = v["text"]!!
            val kept = FinderNote.radioFirst(note, sender, name, text, keyed)
            assertEquals("$v", v["note"], if (kept != null) "yes" else "no")
            // The size without the note is what sealing gives before padding, and the note adds 11 bytes.
            val body = GroupCrypto.fields(sender, name, text, null, null, null)
            assertEquals("$v", v["size"]!!.toInt(), Packet.OVERHEAD + (if (keyed) 3 else 0) + body.size)
            assertEquals(FinderNote.COST, GroupCrypto.fields(sender, name, text, null, null, null, raw = note).size - body.size)
            if (!keyed) { // the epoch-key seal pads to its class: 163 exactly when it still fits the radio
                val id = "e6080000000002%02x".format(n)
                val c = group.seal(id, t0, sender, name, text, iv = iv, fifth = kept) { sig }
                if (v["size"]!!.toInt() <= MeshCore.PAYLOAD_MAX) assertEquals("still fits the radio: $v", MeshCore.PAYLOAD_MAX, Packet(id, t0, 16, c).encode().size)
            }
        }
    }

    /** `kind: finder-bands`: every second of every trace, band and trend. */
    @Test fun bandsAndTrends() {
        for (v in of("finder-bands")) {
            val trace = v["rssi"]!!.split(" ").mapIndexed { i, w -> i.toLong() to (if (w == "-") null else w.toInt()) }
            val got = FinderMath.trace(trace)
            assertEquals(v["rssi"], v["bands"], got.joinToString(" ") { it.band.code.toString() })
            assertEquals(v["rssi"], v["trends"], got.joinToString(" ") { it.trend?.code?.toString() ?: "." })
        }
    }
}
