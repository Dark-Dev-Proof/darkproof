package it.blackout.desktop

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * DNS messages, as far as finding a service on the local network needs them (RFC 1035, 6762, 6763): questions, and
 * PTR, SRV, TXT and A records. A name is its labels. Everything that comes from the network is untrusted: [parse]
 * returns null for anything that isn't a well-formed message and never reads outside the packet. No network in here.
 */
object Dns {
    const val A = 1; const val PTR = 12; const val TXT = 16; const val SRV = 33; const val ANY = 255
    const val MAX_PACKET = 9000
    private const val MAX_ENTRIES = 256   // questions and records in one message
    private const val FLUSH = 0x8000      // "this replaces what you remembered": on records only one device answers for

    class Question(val name: List<String>, val type: Int)
    sealed class Record(val name: List<String>, val ttl: Long)
    class Ptr(name: List<String>, ttl: Long, val target: List<String>) : Record(name, ttl)
    class Srv(name: List<String>, ttl: Long, val port: Int, val target: List<String>) : Record(name, ttl)
    class Txt(name: List<String>, ttl: Long) : Record(name, ttl)
    class Addr(name: List<String>, ttl: Long, val address: ByteArray) : Record(name, ttl)
    class Message(val response: Boolean, val questions: List<Question>, val records: List<Record>)

    fun name(s: String): List<String> = s.trimEnd('.').split('.')

    /** DNS names compare without regard to case. */
    fun same(a: List<String>, b: List<String>) = a.size == b.size && a.indices.all { a[it].equals(b[it], ignoreCase = true) }

    fun query(vararg questions: Question): ByteArray = message(false, questions.toList(), emptyList())
    fun response(records: List<Record>): ByteArray = message(true, emptyList(), records)

    private fun message(response: Boolean, questions: List<Question>, records: List<Record>): ByteArray = ByteArrayOutputStream().also { o ->
        fun u16(v: Int) { o.write(v ushr 8); o.write(v) }
        fun name(n: List<String>) { n.forEach { l -> val b = l.toByteArray(); require(b.size in 1..63) { "label length" }; o.write(b.size); o.write(b) }; o.write(0) }
        u16(0); u16(if (response) 0x8400 else 0); u16(questions.size); u16(records.size); u16(0); u16(0) // a response is authoritative
        questions.forEach { name(it.name); u16(it.type); u16(1) }
        for (r in records) {
            val data = ByteArrayOutputStream().also { d ->
                fun d16(v: Int) { d.write(v ushr 8); d.write(v) }
                fun dname(n: List<String>) { n.forEach { l -> val b = l.toByteArray(); d.write(b.size); d.write(b) }; d.write(0) }
                when (r) {
                    is Ptr -> dname(r.target)
                    is Srv -> { d16(0); d16(0); d16(r.port); dname(r.target) }
                    is Txt -> d.write(0) // no text: one empty string
                    is Addr -> d.write(r.address)
                }
            }.toByteArray()
            name(r.name)
            u16(when (r) { is Ptr -> PTR; is Srv -> SRV; is Txt -> TXT; is Addr -> A })
            u16(if (r is Ptr) 1 else 1 or FLUSH)
            u16((r.ttl ushr 16).toInt()); u16(r.ttl.toInt()); u16(data.size); o.write(data)
        }
    }.toByteArray()

    fun parse(b: ByteArray, len: Int = b.size): Message? = try { read(b, minOf(len, b.size)) } catch (_: RuntimeException) { null }

    private class Bad : RuntimeException()

    private fun read(b: ByteArray, len: Int): Message? {
        if (len < 12 || len > MAX_PACKET) return null
        fun u8(at: Int): Int { if (at < 0 || at >= len) throw Bad(); return b[at].toInt() and 0xFF }
        fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
        /** The name at [at] and where what follows it starts. Names may point back into the packet to save room; a packet that points in circles is refused. */
        fun name(at: Int): Pair<List<String>, Int> {
            val labels = ArrayList<String>(); var p = at; var after = -1; var jumps = 0; var total = 0
            while (true) {
                val l = u8(p)
                when {
                    l == 0 -> return labels to (if (after >= 0) after else p + 1)
                    (l and 0xC0) == 0xC0 -> { if (after < 0) after = p + 2; if (++jumps > 16) throw Bad(); p = ((l and 0x3F) shl 8) or u8(p + 1) }
                    (l and 0xC0) != 0 -> throw Bad()
                    else -> {
                        if (p + 1 + l > len) throw Bad(); total += l + 1; if (total > 255) throw Bad()
                        labels += String(b, p + 1, l, Charsets.UTF_8); p += 1 + l
                    }
                }
            }
        }
        val flags = u16(2); if ((flags and 0x7800) != 0) return null // only standard queries and their answers
        val nq = u16(4); val nr = u16(6) + u16(8) + u16(10); if (nq + nr > MAX_ENTRIES) return null
        var p = 12
        val questions = ArrayList<Question>(nq); val records = ArrayList<Record>()
        repeat(nq) { val (n, next) = name(p); questions += Question(n, u16(next)); u16(next + 2); p = next + 4 }
        repeat(nr) {
            val (n, next) = name(p); val type = u16(next); val ttl = (u16(next + 4).toLong() shl 16) or u16(next + 6).toLong(); val size = u16(next + 8)
            val data = next + 10; if (data + size > len) throw Bad()
            when (type) {
                PTR -> records += Ptr(n, ttl, name(data).first)
                SRV -> { if (size < 7) throw Bad(); records += Srv(n, ttl, u16(data + 4), name(data + 6).first) }
                TXT -> records += Txt(n, ttl)
                A -> if (size == 4) records += Addr(n, ttl, b.copyOfRange(data, data + 4))
            }
            p = data + size
        }
        return Message((flags and 0x8000) != 0, questions, records)
    }
}

/**
 * Finding DarkProof devices on the same network and being found by them (spec/FORMAT.md, "Wi-Fi networks",
 * Discovery): DNS-SD over multicast DNS, service type `_darkproof._tcp`, instance name = the node id, port = the TCP
 * port this device listens on. Phones get this from their system (Bonjour, NsdManager). A desktop Java app has no
 * such service to ask, so this is a small one of its own, for this one service, over IPv4.
 *
 * - It tells the network about this device when it starts, answers who asks, and says goodbye when it stops.
 * - It asks who else is there, and calls [found] with each other device's node id, address and port. The address is
 *   the one the answer came from: a device announces its own service. Who then connects is the caller's business.
 * - What it reads from the network is untrusted and bounded ([Dns.parse]); a device that floods questions gets a
 *   limited number of answers.
 *
 * [found] and [gone] are called on this object's own thread.
 */
class Mdns(private val node: String, private val port: Int, private val found: (node: String, host: String, port: Int) -> Unit, private val gone: (node: String) -> Unit = {},
           /** The service type. Tests use one of their own, so that they never meet real DarkProof devices on the network. */
           type: String = SERVICE) {
    companion object {
        const val SERVICE = "_darkproof._tcp"
        const val PORT = 5353
        val GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
        private const val MAX_NODE = 64 // a node id is 1 to 64 bytes (spec, HELLO)
        private val TYPES = Dns.name("_services._dns-sd._udp.local")
    }
    private val service = Dns.name("$type.local")
    private val instance = listOf(node) + service
    private val host = listOf("darkproof-$node", "local")
    @Volatile private var socket: MulticastSocket? = null
    private var timer: ScheduledExecutorService? = null
    private val joined = HashMap<String, NetworkInterface>()
    private var lastAnswer = 0L
    private val asked = HashMap<String, Long>() // instance -> when its port was last asked for

    /** False if this computer doesn't let the app use the multicast DNS port: then devices are linked by typing an address. */
    @Synchronized fun start(): Boolean {
        if (socket != null) return true
        val s = try {
            MulticastSocket(null as SocketAddress?).apply {
                reuseAddress = true // the system's own Bonjour has this port too: they share it
                runCatching { setOption(StandardSocketOptions.SO_REUSEPORT, true) }
                bind(InetSocketAddress(PORT)); timeToLive = 255
                runCatching { setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true) } // other apps on this computer hear it too
            }
        } catch (_: IOException) { return false }
        socket = s
        thread(isDaemon = true, name = "mdns-listen") { listen(s) }
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mdns").apply { isDaemon = true } }.also { t ->
            t.scheduleWithFixedDelay({ quietly { join(s) } }, 0, 30, TimeUnit.SECONDS) // networks come and go
            for (at in longArrayOf(200, 1_200)) t.schedule({ quietly { announce(120) } }, at, TimeUnit.MILLISECONDS)
            for (at in longArrayOf(300, 1_300, 3_300)) t.schedule({ quietly { ask() } }, at, TimeUnit.MILLISECONDS)
            t.scheduleWithFixedDelay({ quietly { ask() } }, 10, 20, TimeUnit.SECONDS)
        }
        return true
    }

    @Synchronized fun stop() {
        val s = socket ?: return
        quietly { announce(0) } // goodbye: others forget this device at once
        socket = null; timer?.shutdownNow(); timer = null
        s.close(); synchronized(joined) { joined.clear() }
    }

    private fun quietly(f: () -> Unit) { try { f() } catch (_: Exception) { } }

    /** The interfaces a local network can be on, each with this computer's address there. */
    private fun interfaces(): List<Pair<NetworkInterface, Inet4Address>> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { n ->
        if (!runCatching { n.isUp && n.supportsMulticast() && !n.isLoopback && !n.isPointToPoint }.getOrDefault(false)) null
        else n.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.let { n to it }
    }

    private fun join(s: MulticastSocket) = synchronized(joined) {
        for ((n, _) in interfaces()) if (n.name !in joined) quietly { s.joinGroup(InetSocketAddress(GROUP, PORT), n); joined[n.name] = n }
    }

    /** Sends on every joined interface what [make] builds for this computer's address there. */
    private fun send(make: (Inet4Address) -> ByteArray) {
        val s = socket ?: return
        val on = synchronized(joined) { joined.keys.toSet() }
        for ((n, a) in interfaces()) if (n.name in on) quietly {
            val b = make(a)
            synchronized(s) { s.networkInterface = n; s.send(DatagramPacket(b, b.size, GROUP, PORT)) }
        }
    }

    private fun records(a: Inet4Address, ttl: Long, types: Boolean = false) = listOfNotNull(
        Dns.Ptr(TYPES, ttl, service).takeIf { types },
        Dns.Ptr(service, if (ttl == 0L) 0 else 4500, instance),
        Dns.Srv(instance, ttl, port, host),
        Dns.Txt(instance, if (ttl == 0L) 0 else 4500),
        Dns.Addr(host, ttl, a.address),
    )

    private fun announce(ttl: Long) = send { a -> Dns.response(records(a, ttl)) }
    private fun ask() = send { Dns.query(Dns.Question(service, Dns.PTR)) }

    private fun listen(s: MulticastSocket) {
        val buf = ByteArray(Dns.MAX_PACKET)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: IOException) { if (s.isClosed) return else continue }
            val from = p.address as? Inet4Address ?: continue
            val m = Dns.parse(p.data, p.length) ?: continue
            quietly { if (m.response) learn(m, from) else answer(m) }
        }
    }

    /** Someone asks: for the service, for this device by name, or which services there are. One answer carries everything a phone needs to connect. */
    private fun answer(m: Dns.Message) {
        fun asks(n: List<String>, vararg types: Int) = m.questions.any { Dns.same(it.name, n) && (it.type == Dns.ANY || it.type in types) }
        val types = asks(TYPES, Dns.PTR)
        if (!types && !asks(service, Dns.PTR) && !asks(instance, Dns.SRV, Dns.TXT) && !asks(host, Dns.A)) return
        val now = System.nanoTime() / 1_000_000
        synchronized(this) { if (now - lastAnswer < 50) return; lastAnswer = now } // at most 20 answers a second, whoever asks
        send { a -> Dns.response(records(a, 120, types)) }
    }

    /** An answer, to anyone's question: who is there, and on which port. */
    private fun learn(m: Dns.Message, from: Inet4Address) {
        fun other(name: List<String>): String? = name.takeIf { it.size == service.size + 1 && Dns.same(it.drop(1), service) }?.first()
            ?.takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_NODE && !it.equals(node, ignoreCase = true) }
        val ports = m.records.filterIsInstance<Dns.Srv>().mapNotNull { r -> other(r.name)?.let { it to r } }.toMap()
        for (r in m.records.filterIsInstance<Dns.Ptr>()) {
            if (!Dns.same(r.name, service)) continue
            val id = other(r.target) ?: continue
            if (r.ttl == 0L) { gone(id); continue }
            if (id !in ports) { // the answer names a device without its port: ask for it, not more than once every few seconds
                val now = System.currentTimeMillis()
                val due = synchronized(asked) { if (asked.size > 512) asked.clear(); (now - (asked[id] ?: 0) > 3_000).also { if (it) asked[id] = now } }
                if (due) send { Dns.query(Dns.Question(listOf(id) + service, Dns.SRV)) }
            }
        }
        for ((id, r) in ports) if (r.ttl > 0 && r.port in 1..65535) found(id, from.hostAddress, r.port)
    }
}
