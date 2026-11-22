package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Leaving the group" against spec/test-vectors.txt (`kind: leave-field`, `leave-note`, `leave-valid`, `leave-admin`,
 * `leave-after`), made by spec/make-reply-vectors.py. The iPhone's LeaveVectorsTests checks the same.
 */
class LeaveVectorsTest {
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = Hex.encode(b)

    private val vectors: List<Map<String, String>> =
        File("../spec/test-vectors.txt").readText(Charsets.UTF_8).split(Regex("\n\\s*\n")).map { block ->
            block.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore(":") to line.substringAfter(":").removePrefix(" ") }
        }.filter { it.isNotEmpty() }
    private fun of(kind: String) = vectors.filter { it["kind"] == kind }
    private val t0 = 1_794_000_000_000L; private val hour = 3_600_000L

    @Test fun vectorCounts() {
        assertEquals(6, of("leave-field").size); assertEquals(1, of("leave-note").size); assertEquals(12, of("leave-valid").size)
        assertEquals(16, of("leave-admin").size); assertEquals(4, of("leave-after").size)
    }

    @Test fun fields() {
        for (v in of("leave-field")) {
            val f = unhex(v["field"]!!)
            assertEquals(v["field"], v["reads"], if (LeaveNote.read(f)) "leave" else "ordinary")
            assertEquals(v["reads"], if (Notice.read(f) == Notice.Leave) "leave" else "ordinary")
            assertEquals(v["framed"], hex(byteArrayOf(0, 0, (f.size shr 8).toByte(), f.size.toByte()) + f))
        }
        assertEquals("what this app writes", listOf(hex(LeaveNote.field)), of("leave-field").filter { it["reads"] == "leave" }.map { it["field"]!! })
    }

    @Test fun theSealedNote() {
        val v = of("leave-note").single()
        val secret = unhex(v["secret"]!!); val g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        val position = v["position"]!!.toInt(); val number = v["number"]!!.toInt(); val ts = v["ts"]!!.toLong()
        val mk = MessageKeys.of(secret, listOf(v["member_0"]!!, v["member_1"]!!)).key(position, number)!!
        val p = Packet(v["id"]!!, v["wire_ts"]!!.toLong(), 16, unhex(v["cipher"]!!))
        val o = g.openWith(mk, position, number, p)!!
        assertEquals(v["text"], o.message.text); assertEquals(LeaveNote.TEXT, o.message.text); assertEquals(v["text_hex"], hex(o.message.text.toByteArray()))
        assertEquals(ts, o.message.ts); assertEquals(v["sender"], o.message.sender); assertEquals(v["name"], o.message.name)
        assertEquals(Notice.Leave, o.message.notice); assertEquals("leave", v["reads"])
        assertTrue("shown as a line, never a bubble", o.message.silent); assertEquals("line", v["shown"])
        assertFalse("it goes on the radio", o.message.offRadio)
        assertNull(o.message.about); assertNull(o.extra); assertNull(o.aboutField)
        assertEquals(v["fifth"], hex(o.fifth!!))
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(unhex(v["signed_message"]!!))), hex(o.digest))
        // The plaintext, read the way a phone without the field reads it: the text, an ordinary message.
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(mk, "AES"), GCMParameterSpec(128, p.cipher, 0, 12))
            updateAAD("${p.id}|$ts".toByteArray() + byteArrayOf(position.toByte(), (number shr 8).toByte(), number.toByte()))
            doFinal(p.cipher, 15, p.cipher.size - 15)
        }
        assertEquals(v["plain"], hex(plain))
        assertEquals(v["older"], LeaveTest.olderReads(plain))
        assertNull(About.read(o.fifth, o.message.text.toByteArray())); assertNull(GroupTimer.read(o.fifth))
        // What this app seals is byte for byte the vector.
        val again = g.sealWith(mk, position, number, v["id"]!!, ts, v["sender"]!!, v["name"]!!, LeaveNote.TEXT, iv = unhex(v["iv"]!!), fifth = LeaveNote.field) { unhex(v["signature"]!!) }
        assertEquals(v["cipher"], hex(again))
    }

    /**
     * Each vector's epochs, held by a phone; the note's opener is the one numbered `opened_by`. A `rival_N` is a key of the
     * same number made by a member who isn't an admin (it loses to `epoch_N`, an admin's), with a secret of its own.
     */
    @Test fun whichNotesCount() {
        var rivals = 0
        for (v in of("leave-valid")) {
            val ring = Keyring(); var opener: Keyring.Epoch? = null
            for (key in v.keys.filter { it.startsWith("epoch_") }) {
                val n = key.removePrefix("epoch_").toLong(); val members = v[key]!!.split(',')
                val removes = v["removes_$n"]; val read = v["read_$n"] == "yes"
                val e = Keyring.Epoch.fresh(n, t0 + n * hour, ByteArray(32) { n.toByte() }, members, listOf(members[0]), if (removes == null) null else "code-$n",
                    if (removes == null) null else "old", removes, members[0], if (removes == null) Keyring.BY_ADMIN else 0, t0 + n * hour).let { if (read) it.forReadingOnly() else it }
                ring.add(e)
                if (n == v["opened_by"]!!.toLong()) opener = e
            }
            for (key in v.keys.filter { it.startsWith("rival_") }) {
                val n = key.removePrefix("rival_").toLong(); val members = v[key]!!.split(','); val winner = ring.all().first { it.number == n && it.members.isNotEmpty() }
                val rival = Keyring.Epoch.fresh(n, t0 + n * hour, ByteArray(32) { (n + 100).toByte() }, members, winner.admins, by = members.last { it !in winner.admins },
                    rank = Int.MAX_VALUE, taken = t0 + n * hour)
                ring.add(rival); rivals++
                assertFalse("$v: the rival loses", ring.won(rival))
            }
            val checked = when (v["checked"]) { "yes" -> true; "no" -> false; else -> null }
            val got = LeaveNote.verdict(v["sender"]!!, v["me"]!!, checked, opener, ring)
            assertEquals("$v", v["valid"], when (got) { LeaveNote.Verdict.VALID -> "valid"; LeaveNote.Verdict.NOT_YET -> "not yet"; LeaveNote.Verdict.NO -> "no" })
        }
        assertTrue("the vectors with a losing rival (review round 2b, H2)", rivals >= 2)
    }

    @Test fun whatAnAdminsPhoneDoes() {
        for (v in of("leave-admin")) {
            val act = LeaveNote.act(v["admin"] == "yes", v["in_current"] == "yes", v["removal_held"] == "yes", v["keys"] == "yes")
            assertEquals("$v", v["act"], when (act) { LeaveNote.Act.REMOVE_NOW -> "remove-now"; LeaveNote.Act.RETRY_HOURLY -> "retry-hourly"; LeaveNote.Act.NOTHING -> "nothing" })
        }
    }

    /**
     * `kind: quiet-field`, `quiet-text`, `quiet-message` ("If I go quiet"): this app keeps no clock yet, but reads a clock
     * message's field the same way, so that its free text is never taken for a check-in ([QuietClock.shown]).
     */
    @Test fun clockMessagesAreKnown() {
        assertEquals(5, of("quiet-field").size); assertEquals(11, of("quiet-text").size); assertEquals(1, of("quiet-message").size)
        for (v in of("quiet-field")) {
            val f = unhex(v["field"]!!)
            if (v["reads"] == "clock") {
                assertEquals(v["field"], v["deadline"]!!.toLong(), QuietClock.raw(f))
                assertEquals(Notice.Clock(v["deadline"]!!.toLong()), Notice.read(f, v["ts"]!!.toLong()))
            } else { assertNull(v["field"], QuietClock.raw(f)); assertNull(Notice.read(f, t0)) }
        }
        for (v in of("quiet-text")) {
            val ts = v["ts"]!!.toLong(); val text = QuietClock.text(v["deadline"]!!.toLong(), ts)
            assertEquals(v["text"], text + (v["spot"]?.let { " · 📍 $it" } ?: ""))
            assertEquals("its own text is shown as it is", v["text"], QuietClock.shown(v["text"]!!, v["deadline"]!!.toLong(), ts))
            assertNull(CheckIn.of(v["text"]!!))
        }
        val v = of("quiet-message").single()
        val secret = unhex(v["secret"]!!); val g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        val mk = MessageKeys.of(secret, listOf(v["member_0"]!!, v["member_1"]!!)).key(v["position"]!!.toInt(), v["number"]!!.toInt())!!
        val o = g.openWith(mk, v["position"]!!.toInt(), v["number"]!!.toInt(), Packet(v["id"]!!, v["wire_ts"]!!.toLong(), 16, unhex(v["cipher"]!!)))!!
        assertEquals(v["text"], o.message.text); assertEquals(Notice.Clock(v["deadline"]!!.toLong()), o.message.notice)
        assertFalse("an ordinary message here", o.message.silent); assertFalse(o.message.offRadio); assertEquals(v["fifth"], hex(o.fifth!!))
    }

    @Test fun whatIsShownFromTheLeaver() {
        for (v in of("leave-after")) {
            val book = LeaveBook().apply { take(v["leaver"]!!, v["note_ts"]!!.toLong(), 5, t0) }
            assertEquals("$v", v["shown"], if (book.hides(v["sender"]!!, v["ts"]!!.toLong())) "no" else "yes")
        }
    }
}
