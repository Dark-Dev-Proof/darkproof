package it.blackout.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Two desktop engines on this computer, linked over real sockets on loopback. */
class EveryonePeerTest {
    private fun await(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!ok()) { if (System.currentTimeMillis() > end) fail("timed out waiting for: $what"); Thread.sleep(25) }
    }

    @Test fun twoComputersTalkOnEveryone() {
        val a = EveryonePeer("Ada", loopback = true); val b = EveryonePeer("Bo", loopback = true)
        try {
            val port = a.start(0); assertTrue(port > 0)
            assertTrue(b.connect("127.0.0.1:$port"))
            await("the link") { a.wifi.linkCount() == 1 && b.wifi.linkCount() == 1 }

            assertTrue(b.send("hello from Bo"))
            await("Bo's post at Ada") { a.lines().any { it.text == "hello from Bo" && it.name == "Bo" && !it.mine && it.verified } }
            assertTrue(a.send("hello back"))
            await("Ada's post at Bo") { b.lines().any { it.text == "hello back" && it.name == "Ada" && !it.mine && it.verified } }

            assertEquals(listOf("hello from Bo", "hello back"), a.lines().map { it.text })
            assertEquals(listOf(false, true), a.lines().map { it.mine })
        } finally { a.stop(); b.stop() }
    }

    @Test fun whatWasPostedBeforeTheLinkArrivesOnceItIsUp() {
        val a = EveryonePeer("Ada", loopback = true); val b = EveryonePeer("Bo", loopback = true)
        try {
            assertTrue(b.send("written with no link"))
            val port = a.start(0)
            b.connect("127.0.0.1:$port")
            await("the stored post at Ada") { a.lines().any { it.text == "written with no link" && it.verified } }
        } finally { a.stop(); b.stop() }
    }

    /** A second device on the same computer gets a port of its own, on every system (on Windows two listeners could otherwise end up sharing one). */
    @Test fun twoDevicesOnOneComputerNeverShareAPort() {
        val a = EveryonePeer("Ada", loopback = true); val b = EveryonePeer("Bo", loopback = true)
        try {
            val first = a.start(0); assertTrue(first > 0)
            val second = b.start(first); assertTrue(second > 0) // asks for the port that is taken
            assertNotEquals("both listen on port $first", first, second)
        } finally { a.stop(); b.stop() }
    }

    @Test fun addressesAreChecked() {
        val e = EveryonePeer("Ada")
        assertFalse(e.connect("")); assertFalse(e.connect("host:notaport")); assertFalse(e.connect("host:70000")); assertFalse(e.connect("two words"))
        assertFalse(e.send("   "))
        assertEquals("Desktop", EveryonePeer("  ").name)
    }
}
