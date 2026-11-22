package it.blackout.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.random.Random

/** The desktop's finder of DarkProof devices on the network: its DNS messages, then two finders finding each other. */
class MdnsTest {
    private val service = Dns.name("_darkproof._tcp.local")
    /** A service type of the tests' own: real DarkProof devices on this network neither see these tests nor are seen by them. */
    private val TEST = "_darkproof-test._tcp"
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun whatIsWrittenIsReadBack() {
        val inst = listOf("a1b2c3d4e5f6") + service; val host = Dns.name("darkproof-a1b2c3d4e5f6.local")
        val m = Dns.parse(Dns.response(listOf(Dns.Ptr(service, 4500, inst), Dns.Srv(inst, 120, 7777, host), Dns.Txt(inst, 4500), Dns.Addr(host, 120, byteArrayOf(192.toByte(), 168.toByte(), 1, 20)))))!!
        assertTrue(m.response); assertEquals(4, m.records.size)
        val ptr = m.records[0] as Dns.Ptr; assertEquals(service, ptr.name); assertEquals(inst, ptr.target); assertEquals(4500L, ptr.ttl)
        val srv = m.records[1] as Dns.Srv; assertEquals(inst, srv.name); assertEquals(7777, srv.port); assertEquals(host, srv.target); assertEquals(120L, srv.ttl)
        assertTrue(m.records[2] is Dns.Txt)
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), (m.records[3] as Dns.Addr).address)
        val q = Dns.parse(Dns.query(Dns.Question(service, Dns.PTR)))!!
        assertFalse(q.response); assertEquals(1, q.questions.size); assertEquals(service, q.questions[0].name); assertEquals(Dns.PTR, q.questions[0].type)
    }

    /** As the phones' systems write them: later names point back at earlier ones to save room. */
    @Test fun namesThatPointBackIntoThePacketAreRead() {
        val b = hex("0000 8400 0000 0002 0000 0000" +
            "0a 5f6461726b70726f6f66 04 5f746370 05 6c6f63616c 00" + "000c 0001 00001194 0007" + "04 61626364 c00c" +     // _darkproof._tcp.local PTR abcd.<name at 12>
            "c02d" + "0021 8001 00000078 000b" + "0000 0000 1e61" + "02 6877 c01c")                                            // <the name at 45: abcd.…> SRV port 7777, hw.<"local" at 28>
        val m = Dns.parse(b)!!
        val ptr = m.records[0] as Dns.Ptr; assertEquals(listOf("abcd") + service, ptr.target)
        val srv = m.records[1] as Dns.Srv; assertEquals(listOf("abcd") + service, srv.name); assertEquals(7777, srv.port); assertEquals(listOf("hw", "local"), srv.target)
    }

    @Test fun namesCompareWithoutCase() {
        assertTrue(Dns.same(Dns.name("_DarkProof._TCP.Local."), service)); assertFalse(Dns.same(Dns.name("_darkproof._udp.local"), service))
        assertFalse(Dns.same(listOf("x") + service, service))
    }

    @Test fun brokenPacketsAreRefusedNotFollowed() {
        val good = Dns.response(listOf(Dns.Ptr(service, 4500, listOf("abcd") + service), Dns.Srv(listOf("abcd") + service, 120, 7777, Dns.name("h.local"))))
        assertNotNull(Dns.parse(good))
        for (cut in 0 until good.size) Dns.parse(good, cut)                                    // every shorter version: no crash
        assertNull(Dns.parse(good, 11)); assertNull(Dns.parse(ByteArray(0)))
        assertNull("a name that points at itself", Dns.parse(hex("0000 8400 0000 0001 0000 0000 c00c 000c 0001 00000001 0002 c00c")))
        assertNull("a record longer than the packet", Dns.parse(hex("0000 8400 0000 0001 0000 0000 01 61 00 000c 0001 00000001 00ff 00")))
        assertNull("a label running past the end", Dns.parse(hex("0000 0000 0001 0000 0000 0000 3f 6161")))
        assertNull("more entries than a packet can hold", Dns.parse(hex("0000 8400 ffff ffff 0000 0000")))
        assertNull("not a plain query or answer", Dns.parse(hex("0000 2800 0000 0000 0000 0000")))
        val r = Random(7); repeat(2_000) { Dns.parse(ByteArray(r.nextInt(0, 120)).also(r::nextBytes)) }  // noise: no crash
        repeat(2_000) { val b = good.copyOf(); b[r.nextInt(b.size)] = r.nextInt().toByte(); Dns.parse(b) } // one byte off: no crash
    }

    /** Whether this computer lets a program hear its own multicast: not so on some build machines, or with some network settings. */
    private fun multicastWorks(): Boolean = runCatching {
        val a = MulticastSocket(null as SocketAddress?).apply { reuseAddress = true; bind(InetSocketAddress(Mdns.PORT)); soTimeout = 1500 }
        try {
            val nif = java.net.NetworkInterface.getNetworkInterfaces().toList().firstOrNull { it.isUp && it.supportsMulticast() && !it.isLoopback && !it.isPointToPoint && it.inetAddresses.toList().any { x -> x is java.net.Inet4Address } } ?: return false
            a.joinGroup(InetSocketAddress(Mdns.GROUP, Mdns.PORT), nif); a.networkInterface = nif
            val mark = Dns.query(Dns.Question(Dns.name("darkproof-test-${System.nanoTime()}.local"), Dns.A))
            a.send(DatagramPacket(mark, mark.size, Mdns.GROUP, Mdns.PORT))
            val buf = ByteArray(Dns.MAX_PACKET); val end = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < end) { val p = DatagramPacket(buf, buf.size); try { a.receive(p) } catch (_: SocketTimeoutException) { break }; if (p.data.copyOf(p.length).contentEquals(mark)) return true }
            false
        } finally { a.close() }
    }.getOrDefault(false)

    @Test fun twoDevicesOnOneNetworkFindEachOther() {
        assumeTrue("this computer doesn't let a program hear its own multicast", multicastWorks())
        val seenByA = Collections.synchronizedList(ArrayList<Triple<String, String, Int>>()); val seenByB = Collections.synchronizedList(ArrayList<Triple<String, String, Int>>())
        val goneAtB = Collections.synchronizedList(ArrayList<String>())
        val a = Mdns("aaaa00000001", 7801, { n, h, p -> seenByA += Triple(n, h, p) }, type = TEST)
        val b = Mdns("bbbb00000002", 7802, { n, h, p -> seenByB += Triple(n, h, p) }, { goneAtB += it }, type = TEST)
        try {
            assertTrue(a.start()); assertTrue(b.start())
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && (seenByA.none { it.first == "bbbb00000002" } || seenByB.none { it.first == "aaaa00000001" })) Thread.sleep(50)
            val atA = seenByA.firstOrNull { it.first == "bbbb00000002" }; val atB = seenByB.firstOrNull { it.first == "aaaa00000001" }
            assertNotNull("A didn't find B", atA); assertNotNull("B didn't find A", atB)
            assertEquals(7802, atA!!.third); assertEquals(7801, atB!!.third)
            assertTrue("not an address: ${atA.second}", atA.second.matches(Regex("""\d+\.\d+\.\d+\.\d+""")))
            assertTrue(seenByA.none { it.first == "aaaa00000001" }) // never itself
            a.stop() // says goodbye
            val end2 = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < end2 && "aaaa00000001" !in goneAtB) Thread.sleep(50)
            assertTrue("B didn't hear A leave", "aaaa00000001" in goneAtB)
        } finally { a.stop(); b.stop() }
    }

    /** For a failure on a machine nobody can look at: this computer's networks, what a finder hears on them, and whether a plain connection to what it hears works. */
    private fun why(small: it.blackout.mesh.WifiLink, big: it.blackout.mesh.WifiLink): String = buildString {
        fun connect(host: String, port: Int) = runCatching { java.net.Socket().use { it.connect(InetSocketAddress(host, port), 3_000) }; "ok" }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        append("\n  status: ${small.links.statusLine()} / ${big.links.statusLine()}; ports ${small.links.port} and ${big.links.port}")
        append("\n  networks: " + java.net.NetworkInterface.getNetworkInterfaces().toList().filter { runCatching { it.isUp }.getOrDefault(false) }.joinToString("; ") { n ->
            "${n.name} (${listOfNotNull("multicast".takeIf { n.supportsMulticast() }, "loopback".takeIf { n.isLoopback }, "point-to-point".takeIf { n.isPointToPoint }).joinToString(",")}) " +
                n.interfaceAddresses.filter { it.address is java.net.Inet4Address }.joinToString(",") { "${it.address.hostAddress}/${it.networkPrefixLength}" }
        })
        val heard = Collections.synchronizedList(ArrayList<Triple<String, String, Int>>())
        val ear = Mdns("5555cccc0003", 1, { n, h, p -> heard += Triple(n, h, p) }, type = TEST)
        ear.start(); Thread.sleep(4_000); ear.stop()
        val each = heard.distinct()
        append("\n  a third finder heard: $each")
        for ((n, h, p) in each) append("\n  a plain connection to $n at $h:$p: ${connect(h, p)}; counts as the local network: ${it.blackout.mesh.WifiLinks.onLan(java.net.InetAddress.getByName(h))}")
        append("\n  a plain connection to 127.0.0.1:${big.links.port}: ${connect("127.0.0.1", big.links.port)}")
    }

    /** Two desktops on one network: they find each other, and the one with the smaller node id connects (spec, "Who connects"). */
    @Test fun twoDesktopsLinkByThemselves() {
        assumeTrue("this computer doesn't let a program hear its own multicast", multicastWorks())
        val before = it.blackout.mesh.WifiLink.discovery
        fun device(id: String): Pair<it.blackout.mesh.WifiLink, it.blackout.mesh.MeshRouter> {
            lateinit var w: it.blackout.mesh.WifiLink
            val r = it.blackout.mesh.MeshRouter(send = { peer, p -> w.links.send(peer, p) }, deliver = { })
            w = it.blackout.mesh.WifiLink(android.content.Context(java.nio.file.Files.createTempDirectory("darkproof-test").toFile()), id, { r }, { })
            return w to r
        }
        val (small, _) = device("1111aaaa0001"); val (big, _) = device("9999bbbb0002")
        try {
            it.blackout.mesh.WifiLink.discovery = TEST
            small.start(); big.start()
            org.junit.Assert.assertNotEquals("the two listen on the same port", small.links.port, big.links.port)
            val end = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < end && (small.links.linkCount() != 1 || big.links.linkCount() != 1)) Thread.sleep(100)
            if (small.links.linkCount() != 1 || big.links.linkCount() != 1) org.junit.Assert.fail("the two didn't link: ${small.links.linkCount()} and ${big.links.linkCount()} links" + why(small, big))
            Thread.sleep(6_000) // more answers come, from every network this computer is on: the one link has to stay the one link
            if (small.links.linkCount() != 1 || big.links.linkCount() != 1) org.junit.Assert.fail("the link didn't stay one link: ${small.links.linkCount()} and ${big.links.linkCount()} links" + why(small, big))
            assertEquals(setOf("9999bbbb0002"), small.links.nodes()); assertEquals(setOf("1111aaaa0001"), big.links.nodes())
        } finally { small.stop(); big.stop(); it.blackout.mesh.WifiLink.discovery = before }
    }

    /**
     * Against Apple's own Bonjour, on a Mac: its `dns-sd` tool must see and resolve what this finder announces, and this
     * finder must find what `dns-sd` registers. iPhones use that same Bonjour.
     */
    @Test fun applesBonjourAndThisFinderSeeEachOther() {
        val tool = java.io.File("/usr/bin/dns-sd")
        assumeTrue("only on a Mac, which has Apple's dns-sd tool", tool.canExecute()); assumeTrue("this computer doesn't let a program hear its own multicast", multicastWorks())
        val procs = ArrayList<Process>()
        fun run(vararg args: String): Pair<Process, StringBuffer> {
            val p = ProcessBuilder(listOf(tool.path) + args).redirectErrorStream(true).start(); procs += p
            val out = StringBuffer(); Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { isDaemon = true; start() }
            return p to out
        }
        fun await(what: String, ms: Long = 12_000, ok: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!ok()) { if (System.currentTimeMillis() > end) org.junit.Assert.fail("timed out waiting for: $what"); Thread.sleep(100) } }
        val seen = Collections.synchronizedList(ArrayList<Triple<String, String, Int>>())
        val mine = Mdns("cafe00000001", 7803, { n, h, p -> seen += Triple(n, h, p) }, type = TEST)
        try {
            assertTrue(mine.start())
            // Apple's Bonjour sees and resolves this finder's announcement
            val (_, browse) = run("-B", TEST, "local")
            await("Apple's Bonjour to list this device") { "cafe00000001" in browse.toString() }
            val (_, lookup) = run("-L", "cafe00000001", TEST, "local")
            await("Apple's Bonjour to resolve it: ${lookup}") { ":7803" in lookup.toString() }
            assertTrue(lookup.toString(), "darkproof-cafe00000001.local" in lookup.toString().lowercase())
            // this finder finds what Apple's Bonjour registers
            run("-R", "beef00000002", TEST, "local", "7804")
            await("this finder to find what Apple's Bonjour registered") { seen.any { it.first == "beef00000002" && it.third == 7804 } }
        } finally { mine.stop(); procs.forEach { it.destroy() } }
    }

    /** A device on two networks answers from two addresses: it gets one link, at one of them, and keeps it. */
    @Test fun aDeviceHeardAtTwoAddressesGetsOneLink() {
        val linked = HashSet<Pair<String, String>>() // node, host
        val f = FoundDevices("1111", 256) { n, h -> (n to h) in linked }
        assertEquals("10.0.0.2" to 7777, f.heard("9999", "10.0.0.2", 7777, 1_000))        // first answer: connect there
        assertNull(f.heard("9999", "172.16.0.2", 7777, 1_200))                              // its other address, while that connection is being made: wait
        linked += "9999" to "10.0.0.2"
        repeat(20) { i -> assertNull(f.heard("9999", if (i % 2 == 0) "172.16.0.2" else "10.0.0.2", 7777, 2_000L + i * 500)) } // linked: answers from either address change nothing
        assertEquals(emptyList<Pair<String, Int>>(), f.unlinked(20_000))
        linked.clear()                                                                      // the link drops
        assertEquals("172.16.0.2" to 7777, f.heard("9999", "172.16.0.2", 7777, 30_000))    // now its other address is tried
        assertEquals(listOf("172.16.0.2" to 7777), f.unlinked(40_000))                      // and retried while there is no link
        // a link from elsewhere that only claims the node id doesn't count
        linked += "9999" to "203.0.113.9"; assertEquals(listOf("172.16.0.2" to 7777), f.unlinked(50_000))
        f.gone("9999"); assertEquals(emptyList<Pair<String, Int>>(), f.unlinked(60_000))
    }

    @Test fun onlyTheSmallerNodeIdConnectsAndOnlySoManyAreKept() {
        val f = FoundDevices("5555", 3) { _, _ -> false }
        assertNull("a larger id waits to be connected to", f.heard("1111", "10.0.0.2", 7777, 0)); assertNull("never itself", f.heard("5555", "10.0.0.3", 7777, 0))
        for (i in 1..3) assertNotNull(f.heard("900$i", "10.0.1.$i", 7777, 0))
        assertNull("a fourth isn't kept", f.heard("9004", "10.0.1.4", 7777, 0)); assertNotNull("one that is kept still is", f.heard("9001", "10.0.1.1", 7777, 0))
        assertEquals(3, f.unlinked(10_000).size)
    }
}
