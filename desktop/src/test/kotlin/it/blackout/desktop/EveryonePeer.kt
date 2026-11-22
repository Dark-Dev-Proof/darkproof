package it.blackout.desktop

import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.MeshRouter
import it.blackout.mesh.MlKem
import it.blackout.mesh.P256
import it.blackout.mesh.Packet
import it.blackout.mesh.Senders
import it.blackout.mesh.WifiLinks
import it.blackout.mesh.WordFilter
import java.security.PublicKey
import java.security.SecureRandom

/**
 * A small stand-alone peer for tests: the Everyone channel over Wi-Fi links, with the phones' router, links, sealing
 * and signatures, and nothing else (no groups, nothing saved, a new member key each time). The desktop app itself runs
 * the phones' full engine (MeshEngine); several of these can run in one test next to it, which the engine can't.
 */
class EveryonePeer(name: String, private val changed: () -> Unit = {}, loopback: Boolean = false) {
    /** One row of the chat. [key] tells packets apart that share an id. [verified]: its sender's key is known and signed it. */
    data class Line(val key: String, val ts: Long, val name: String, val text: String, val mine: Boolean, val verified: Boolean)

    companion object {
        const val MAX_TEXT = 1_000           // as the phones' message box
        const val MAX_NAME = 40              // as the phones' names
        private const val MAX_LINES = 2_000  // Everyone messages kept, as on the phones
        private const val MAX_KNOWN = 500    // public keys kept, as on the phones
        private const val MAX_WAITING = 1_000
        private const val DEVICE = "desktop" // what this computer's own rows go by; never sent (Senders.shown)
    }

    @Volatile var name = name.trim().take(MAX_NAME).ifEmpty { "Desktop" }; private set
    fun rename(n: String) { name = n.trim().take(MAX_NAME).ifEmpty { name }; changed() }

    private val everyone = GroupCrypto(GroupCrypto.PUBLIC_CODE)
    private val intros = everyone.intros()
    private val pair = P256.newKeyPair()
    private val pub = P256.compress(pair.public)
    /** This computer's member id on Everyone (spec "Member ids"). */
    val me: String = P256.memberId(pub)
    private val encPub = P256.uncompressed(P256.newKeyPair().public) // introductions carry one; nothing is sealed to it on Everyone

    private val lock = Any()
    private val known = LinkedHashMap<String, PublicKey>()   // member id -> key, from introductions
    private val waiting = ArrayList<GroupCrypto.Opened>()    // shown as not verified: their sender's key isn't known yet
    private val lines = ArrayList<Line>()
    private var introduced = false

    private val router = MeshRouter(send = ::route, deliver = ::onDeliver, minCipher = MeshRouter.MIN_SEALED, maxPerMinute = MeshRouter.LINK_PER_MINUTE)

    /** Links to phones and computers on the same network. A computer's own loopback counts only when [loopback] is set (tests, emulators). */
    val wifi = WifiLinks(ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }, { router }, changed) {
        WifiLinks.onLan(it) || (loopback && it.isLoopbackAddress)
    }

    private fun route(peer: String, p: Packet) { if (WifiLinks.owns(peer)) wifi.send(peer, p) }

    /** Starts listening for phones. Returns the port, or 0 if that failed. */
    fun start(port: Int = WifiLinks.PORT): Int = wifi.listen(port)

    fun stop() { wifi.stopManual(); wifi.stop() }

    /** Keeps a link to "host" or "host:port". False if that isn't an address. */
    fun connect(address: String): Boolean {
        val a = address.trim(); val host = a.substringBeforeLast(':', a).trim()
        val port = if (':' in a) a.substringAfterLast(':').trim().toIntOrNull() ?: return false else WifiLinks.PORT
        if (host.isEmpty() || ' ' in host || port !in 1..65535) return false
        wifi.connectManually(host, port); return true
    }

    fun lines(): List<Line> = synchronized(lock) { lines.toList() }

    /** Posts [text] on Everyone. False if there is nothing to post. */
    fun send(text: String): Boolean {
        val t = text.trim().take(MAX_TEXT); if (t.isEmpty()) return false
        val now = System.currentTimeMillis()
        val first = synchronized(lock) { !introduced.also { introduced = true } }
        if (first) { // others can check a post only with its sender's key (spec "Introductions")
            val id = Packet.newId(intros.mailbox)
            router.publish(packet(intros, id, now, intros.seal(id, now, me, "", "", extra = byteArrayOf(2) + pub + encPub) { sign(it) }))
        }
        val id = Packet.newId(everyone.mailbox)
        val p = packet(everyone, id, now, everyone.seal(id, now, me, name, t) { sign(it) })
        synchronized(lock) { add(Line(id, now, name, t, mine = true, verified = true)) }
        router.publish(p); changed()
        return true
    }

    private fun sign(msg: ByteArray) = P256.sign(pair.private, msg)
    private fun packet(c: GroupCrypto, id: String, ts: Long, cipher: ByteArray) = Packet(id, c.hideTime(ts, cipher), MeshRouter.MAX_TTL, cipher)

    /** A packet seen for the first time. The router has already kept it and passes it on, readable here or not. */
    private fun onDeliver(p: Packet) {
        if (Packet.mailbox(p.id) != everyone.mailbox) return // a group's packet: carried, not read
        val o = everyone.openSigned(p)
        if (o != null) message(o) else intros.openSigned(p)?.let(::introduction)
    }

    private fun message(o: GroupCrypto.Opened) {
        val m = o.message
        val sender = Senders.shown(m.sender, me, DEVICE) ?: return // no member id: like a packet nobody can open
        if (sender == DEVICE) return                                // this computer's own post, come back
        synchronized(lock) {
            val key = known[sender.removePrefix("web-")]
            if (key != null && !P256.verify(key, o.digest, o.signature)) return // forged or broken
            if (lines.any { it.key == o.key }) return
            if (key == null) { waiting += o; if (waiting.size > MAX_WAITING) drop(waiting.removeAt(0)) }
            // Others' words on Everyone are filtered, as on the phones (WordFilter).
            add(Line(o.key, m.ts, WordFilter.clean(m.name).take(MAX_NAME).ifEmpty { "Someone" }, WordFilter.clean(m.text), mine = false, verified = key != null))
        }
        changed()
    }

    /** A valid introduction (the same rules as MeshEngine.introduction): its key's hash is the sender's id, and that key signed it. */
    private fun introduction(o: GroupCrypto.Opened) {
        val e = o.extra ?: return
        val withPq = e.size == 99 + MlKem.PUBLIC_SIZE && e[0] == 3.toByte()
        if (!withPq && (e.size != 99 || e[0] != 2.toByte())) return
        if (withPq && MlKem.encapsulate(e.copyOfRange(99, e.size)) == null) return
        val pub = e.copyOfRange(1, 34); val id = o.message.sender
        if (P256.memberId(pub) != id || P256.fromUncompressed(e.copyOfRange(34, 99)) == null) return
        val key = P256.decompress(pub) ?: return
        if (!P256.verify(key, o.digest, o.signature)) return
        synchronized(lock) {
            known.remove(id); known[id] = key
            if (known.size > MAX_KNOWN) known.remove(known.keys.first())
            // What waited for this key is checked now: it stays as verified, or it goes.
            val mine = waiting.filter { it.message.sender.removePrefix("web-") == id }
            waiting -= mine.toSet()
            for (w in mine) {
                val i = lines.indexOfFirst { it.key == w.key }.takeIf { it >= 0 } ?: continue
                if (P256.verify(key, w.digest, w.signature)) lines[i] = lines[i].copy(verified = true) else lines.removeAt(i)
            }
        }
        changed()
    }

    private fun add(l: Line) {
        lines += l; lines.sortBy { it.ts }
        while (lines.size > MAX_LINES) { val gone = lines.removeAt(0); waiting.removeAll { it.key == gone.key } }
    }

    private fun drop(o: GroupCrypto.Opened) { lines.removeAll { it.key == o.key && !it.verified } }
}
