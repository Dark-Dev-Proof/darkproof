package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Disappearing messages" against spec/test-vectors.txt (`kind: timer-field`, `timer-rekey`, `timer-epoch`, `timer-rival`,
 * `timer-due`, `timer-notice`), made by spec/make-reply-vectors.py. The iPhone's TimerVectorsTests checks the same.
 */
class TimerVectorsTest {
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = Hex.encode(b)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private val vectors: List<Map<String, String>> =
        File("../spec/test-vectors.txt").readText(Charsets.UTF_8).split(Regex("\n\\s*\n")).map { block ->
            block.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore(":") to line.substringAfter(":").removePrefix(" ") }
        }.filter { it.isNotEmpty() }
    private fun of(kind: String) = vectors.filter { it["kind"] == kind }
    private fun timer(s: String): Int? = if (s == "unknown" || s == "none") null else s.toInt()
    private val anna = "1111111111111111"; private val ben = "2222222222222222"; private val cara = "3333333333333333"; private val dan = "4444444444444444"
    private val t0 = 1_794_000_000_000L; private val hour = 3_600_000L

    @Test fun vectorCounts() {
        assertEquals(15, of("timer-field").size); assertEquals(2, of("timer-rekey").size); assertEquals(12, of("timer-epoch").size)
        assertEquals(4, of("timer-rival").size); assertEquals(11, of("timer-due").size); assertEquals(2, of("timer-notice").size)
        assertEquals(8, of("timer-late").size)
    }

    @Test fun fields() {
        for (v in of("timer-field")) {
            val f = v["field"]!!
            val got = GroupTimer.read(unhex(f))
            assertEquals(f, v["reads"], got?.toString() ?: "ignored")
            // What this app writes, for the values it would write.
            if (got != null && (got in GroupTimer.CHOICES || got == 0) && f.length == 10 && f.substring(2).toLong(16) == got.toLong())
                assertEquals("what this app writes", f, hex(GroupTimer.field(got)))
        }
    }

    /** A reader that knows four fields, as before the 5th existed: the 4th field. */
    private fun oldFourth(plain: ByteArray): ByteArray? {
        val b = plain.copyOfRange(64, plain.size); var i = 0
        fun next(): ByteArray? {
            if (i + 2 > b.size) return null
            val n = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (i + 2 + n > b.size) return null
            return b.copyOfRange(i + 2, i + 2 + n).also { i += 2 + n }
        }
        if (next() == null || next() == null || next() == null) return null
        return next()
    }

    @Test fun aNewKeyWithTheTimerIsTheSameKeyToEveryReader() {
        val fourths = HashSet<String>()
        for (v in of("timer-rekey")) {
            val rekeys = GroupCrypto(v["code"]!!).rekeys()
            val raw = unhex(v["packet"]!!)
            assertEquals(16_018, raw.size)
            val p = Packet.decode(raw)!!
            assertEquals(v["id"], p.id); assertEquals(v["ts"]!!.toLong(), p.ts); assertEquals(16, p.ttl)
            val o = rekeys.openSigned(p)!!
            assertEquals(v["sender"], o.message.sender); assertEquals(v["name"], o.message.name); assertEquals("", o.message.text)
            assertEquals(v["fourth"], hex(o.extra!!)); assertEquals(v["digest"], hex(o.digest))
            val r = Rekey.decode(o.extra!!)!!
            assertEquals(v["epoch"]!!.toLong(), r.epoch); assertEquals(v["made"]!!.toLong(), r.made); assertEquals(v["maker"], hex(r.maker)); assertFalse(r.removal)
            assertEquals(v["admins"], r.admins.joinToString(" ")); assertEquals(v["members"], r.members.joinToString(" ")); assertNull(r.answers)
            assertEquals(v["members"], r.parts.joinToString(" ") { it.first }); assertTrue(r.parts.all { hex(it.second) == v["box"] })
            assertEquals(o.message.sender, P256.memberId(r.maker))
            // The 5th field: the timer, or none.
            assertEquals(v["timer"], GroupTimer.read(o.fifth)?.toString() ?: "none")
            assertEquals(v["fifth"], o.fifth?.let(::hex) ?: "-")
            assertNull(o.message.about)
            // A reader that stops after the 4th field takes the same key from the same bytes.
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(unhex(v["rekey_key"]!!), "AES"), GCMParameterSpec(128, p.cipher, 0, 12)); updateAAD("${p.id}|${p.ts}".toByteArray())
                doFinal(p.cipher, 12, p.cipher.size - 12)
            }
            assertEquals(v["fourth"], hex(oldFourth(plain)!!))
            assertArrayEquals(r.encode(), Rekey.decode(oldFourth(plain)!!)!!.encode())
            fourths += v["fourth"]!!
            // What this app seals for the same key is byte for byte the vector.
            val again = rekeys.seal(p.id, p.ts, o.message.sender, o.message.name, "", extra = r.encode(), iv = unhex(v["iv"]!!), pad = Pad.FULL, fifth = o.fifth) { unhex(v["signature"]!!) }
            assertArrayEquals(raw, Packet(p.id, p.ts, 16, again).encode())
        }
        assertEquals("with and without the timer: one and the same 4th field", 1, fourths.size)
        assertEquals(setOf("86400", "none"), of("timer-rekey").map { it["timer"]!! }.toSet())
    }

    /** Epoch 5 on Cara's phone: Anna and Ben are admins, Cara and Dan members. */
    private fun base(timer: Int?) = Keyring().also {
        it.add(Keyring.Epoch(5, t0 - 100 * hour, ByteArray(32) { 5 }, listOf(anna, ben, cara, dan), listOf(anna, ben), by = anna, rank = Keyring.BY_ADMIN,
            timerRaw = timer, timer = timer, timerCounts = timer != null))
    }
    private fun key(epoch: Long, made: Long): Rekey {
        val m = listOf(anna, ben, cara, dan)
        return Rekey(epoch, made, ByteArray(33), listOf(anna, ben), null, m.map { it to ByteArray(1) }, members = m)
    }
    private fun take(ring: Keyring, r: Rekey, by: String, timer: Int?, secret: ByteArray, now: Long) =
        ring.take(r, by, cara, "d", false, null, now, timer = timer) { secret } is Keyring.Taken.Added

    @Test fun whoSetsItAndHowEpochsInherit() {
        for (v in of("timer-epoch")) {
            val had = v["had"] == "yes"; val admin = v["maker"] == "admin"; val field = timer(v["field"]!!); val before = timer(v["before"]!!); val secret = ByteArray(32) { 9 }
            val ring: Keyring
            if (!had) {
                ring = Keyring() // the key's own admin list names its maker, or not
                assertTrue("$v", take(ring, key(6, t0), if (admin) anna else dan, field, secret, t0))
            } else if (v["kept"] == "read") {
                ring = base(before) // a member's weekly key first, so that the tested key is numbered below the current epoch
                assertTrue(take(ring, key(6, t0), cara, null, ByteArray(32) { 6 }, t0))
                assertTrue("$v", take(ring, key(5, t0 + 2 * hour), if (admin) anna else dan, field, secret, t0 + 3 * hour))
                assertEquals(6L, ring.current().number)
            } else {
                ring = base(before)
                assertTrue("$v", take(ring, key(6, t0), if (admin) anna else cara, field, secret, t0))
                assertEquals(6L, ring.current().number)
            }
            assertEquals("$v", timer(v["timer"]!!), ring.timer().seconds)
        }
    }

    @Test fun twoAdminsAtOnce() {
        for (v in of("timer-rival")) {
            val ring = base(0)
            val keys = mapOf("a" to Triple(anna, unhex(v["secret_a"]!!), v["timer_a"]!!.toInt()), "b" to Triple(ben, unhex(v["secret_b"]!!), v["timer_b"]!!.toInt()))
            var now = t0
            for (step in v["order"]!!.split(" ")) {
                if (step == "member") { assertTrue(take(ring, key(7, t0 + 2 * hour), cara, null, ByteArray(32) { 7 }, t0 + 2 * hour)); now = t0 + 3 * hour; continue }
                val (maker, secret, t) = keys.getValue(step)
                assertTrue("$step in ${v["order"]}", take(ring, key(6, t0), maker, t, secret, now))
            }
            // The epochs' hashes are the vector's, so the winner is too.
            for ((name, k) in keys) ring.all().firstOrNull { it.number == 6L && it.by == k.first }?.let { assertEquals(v["hash_$name"], it.hash) }
            assertEquals(v["wins"], if (v["hash_a"]!! < v["hash_b"]!!) "a" else "b")
            assertEquals(v["order"], v["timer"]!!.toInt(), ring.timer().seconds)
            assertEquals("the winner's value", keys.getValue(v["wins"]!!).third, v["timer"]!!.toInt())
        }
    }

    /**
     * `kind: timer-late`: a key numbered 6 that arrives after Cara's key 7, kept only to read with. Epoch 5 (Anna and Ben
     * admins) has the vector's `before`, which the key current at 6 inherits. Where the late key must win, the key current
     * at 6 is a member's weekly key (an admin's late key beats it; Dan's, a member's, beats it by hash); where it must lose,
     * it is Anna's (Ben's late key loses by hash, Dan's by rank). `beaten: not-admin` for an admin of 5 (Ben): the key current at
     * 6 is Anna's, which demoted him, and his late key beats it by hash. `lines`: whether it stands in for number 6 in the chat's lines.
     */
    @Test fun lateKeys() {
        fun hash(s: Int) = Keyring.Epoch.fresh(6, t0, ByteArray(32) { s.toByte() }, listOf(anna), listOf(anna)).hash
        val bySize = (60..69).sortedBy(::hash); val lo = bySize.first(); val hi = bySize.last()
        val m = listOf(anna, ben, cara, dan)
        fun keyBy(epoch: Long, made: Long, admins: List<String>) = Rekey(epoch, made, ByteArray(33), admins, null, m.map { it to ByteArray(1) }, members = m)
        for (v in of("timer-late")) {
            val wins = v["wins"] == "yes"; val admin = v["maker"] == "admin"
            val demoted = admin && v["beaten"] == "not-admin" // Anna's key 6 took Ben's admin role away
            val ring = base(timer(v["before"]!!))
            val admins6 = if (demoted) listOf(anna) else listOf(anna, ben)
            assertTrue("$v", take(ring, keyBy(6, t0, admins6), if (wins && !demoted) cara else anna, null, ByteArray(32) { (if (wins) hi else lo).toByte() }, t0))
            assertTrue(take(ring, keyBy(7, t0 + 2 * hour, admins6), cara, null, ByteArray(32) { 7 }, t0 + 2 * hour))
            assertEquals(timer(v["before"]!!), ring.timer().seconds)
            val field = if (v["field"] == "none") null else v["field"]!!.toInt()
            assertTrue("$v", take(ring, key(6, t0 + hour), if (admin) ben else dan, field, ByteArray(32) { (if (wins) lo else hi).toByte() }, t0 + 3 * hour))
            val late = ring.all().single { it.number == 6L && it.members.isEmpty() }
            assertEquals("$v", wins, ring.won(late))
            assertEquals("$v", timer(v["timer"]!!), ring.timer().seconds)
            assertEquals("$v", v["lines"] == "yes", ring.lineEpochs().single { it.number == 6L } === late)
        }
    }

    /**
     * M4's stricter rule (2026-11-23): a late key kept only to read with also needs its maker to be an admin of the epoch it
     * beats. Ben, an admin of epoch 5, is demoted by Anna's key 6; a key 6 of Ben's that arrives after Cara's key 7 and
     * wins at 6 by its hash sets nothing, freezes nothing and makes no line. Ben still an admin in Anna's key 6: his late
     * key is his word on the timer, as `kind: timer-late` has it.
     */
    @Test fun aDemotedAdminsLateKeySetsNothing() {
        fun hash(s: Int) = Keyring.Epoch.fresh(6, t0, ByteArray(32) { s.toByte() }, listOf(anna), listOf(anna)).hash
        val bySize = (60..69).sortedBy(::hash); val lo = bySize.first(); val hi = bySize.last()
        val m = listOf(anna, ben, cara, dan)
        fun key(epoch: Long, made: Long, admins: List<String>) = Rekey(epoch, made, ByteArray(33), admins, null, m.map { it to ByteArray(1) }, members = m)
        for (demoted in listOf(true, false)) {
            val admins6 = if (demoted) listOf(anna) else listOf(anna, ben)
            val ring = base(86_400)
            assertTrue(take(ring, key(6, t0, admins6), anna, null, ByteArray(32) { hi.toByte() }, t0))
            assertTrue(take(ring, key(7, t0 + 2 * hour, admins6), cara, null, ByteArray(32) { 7 }, t0 + 2 * hour))
            assertEquals(86_400, ring.timer().seconds)
            assertTrue(take(ring, key(6, t0 + hour, listOf(anna, ben)), ben, 300, ByteArray(32) { lo.toByte() }, t0 + 3 * hour))
            val late = ring.all().single { it.number == 6L && it.members.isEmpty() }
            assertTrue("it wins at 6 by its hash", ring.won(late))
            assertEquals("demoted=$demoted", if (demoted) 86_400 else 300, ring.timer().seconds)
            assertEquals("demoted=$demoted", !demoted, ring.lineEpochs().single { it.number == 6L } === late)
        }
        // No epoch with a member list held at 6 (this phone went from 5 to 7): Ben's late key corrects nothing and says nothing.
        val ring = base(86_400)
        assertTrue(take(ring, key(7, t0 + 2 * hour, listOf(anna, ben)), cara, null, ByteArray(32) { 7 }, t0 + 2 * hour))
        assertEquals(7L, ring.current().number)
        assertTrue(take(ring, key(6, t0 + hour, listOf(anna, ben)), ben, 300, ByteArray(32) { lo.toByte() }, t0 + 3 * hour))
        val late = ring.all().single { it.number == 6L }
        assertTrue(late.members.isEmpty() && ring.won(late))
        assertEquals(86_400, ring.timer().seconds)
        assertTrue(ring.lineEpochs().none { it === late })
    }

    @Test fun whenAMessageGoes() {
        for (v in of("timer-due")) {
            val watch = Disappear.Watch(timer(v["seconds"]!!), v["value_since"]!!.toLong()); val now = v["now"]!!.toLong()
            val at = if (v["arrived"] == "none") KeepLess.UNSENT else v["arrived"]!!.toLong()
            val byDay = v["high_risk"] == "yes" && KeepLess.due(at, now)
            val byTimer = watch.deletes(now)?.let { Disappear.due(mapOf("m" to at), it, now).isNotEmpty() } ?: false
            assertEquals("$v", v["goes"], if (byDay) "high-risk" else if (byTimer) "timer" else "no")
        }
    }

    @Test fun theNoticeMessage() {
        for (v in of("timer-notice")) {
            val secret = unhex(v["secret"]!!); val g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
            val position = v["position"]!!.toInt(); val number = v["number"]!!.toInt(); val ts = v["ts"]!!.toLong(); val seconds = v["timer"]!!.toInt()
            val mk = MessageKeys.of(secret, listOf(v["member_0"]!!, v["member_1"]!!)).key(position, number)!!
            val p = Packet(v["id"]!!, v["wire_ts"]!!.toLong(), 16, unhex(v["cipher"]!!))
            val o = g.openWith(mk, position, number, p)!!
            assertEquals(v["text"], o.message.text); assertEquals(GroupTimer.notice(seconds), o.message.text); assertEquals(ts, o.message.ts)
            assertEquals(Notice.Timer(seconds), o.message.notice); assertEquals(v["shown"] == "no", o.message.silent)
            assertNull(o.message.about); assertNull(o.extra); assertNull(o.aboutField)
            assertEquals(v["fifth"], hex(o.fifth!!)); assertEquals(v["fifth"], hex(GroupTimer.field(seconds)))
            assertEquals(hex(sha(unhex(v["signed_message"]!!))), hex(o.digest))
            val again = g.sealWith(mk, position, number, v["id"]!!, ts, v["sender"]!!, v["name"]!!, GroupTimer.notice(seconds), iv = unhex(v["iv"]!!),
                fifth = GroupTimer.field(seconds)) { unhex(v["signature"]!!) }
            assertEquals(v["cipher"], hex(again))
        }
    }
}
