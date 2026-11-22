package it.blackout.desktop

import android.content.Context
import it.blackout.mesh.Tor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The desktop's Tor, against a stand-in for the Tor program ([FakeTor]): no Tor is fetched and no network is reached. */
class TorTest {
    private val dir = Files.createTempDirectory("darkproof-tor").toFile()
    private val c = Context(dir)
    private val before = Tor.command
    @Before fun standIn() { Tor.command = { FakeTor.command() } }
    @After fun tidy() { Tor.stop(c); Tor.command = before; Thread.sleep(200); dir.deleteRecursively() }
    private fun await(what: String, ms: Long = 15_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) fail("timed out waiting for $what; Tor said: ${Tor.said}"); Thread.sleep(20) }
    }
    /** The stand-in Tors running now: the Java programs this test started (Windows tells a program's name, not what it was started with). */
    private fun tors() = ProcessHandle.current().children().filter { it.isAlive && File(it.info().command().orElse("")).name.startsWith("java") }.count()

    @Test fun itGivesItsPortOnlyOnceTorIsConnected() {
        assertNull(Tor.socksPort())
        Tor.start(c)
        assertNull("a Tor that is still starting has no port to give: nothing may go to a relay yet", Tor.socksPort())
        await("Tor to connect") { Tor.socksPort() != null }
        assertTrue(Tor.socksPort()!! in 1024..65535); assertEquals("Bootstrapped 100% (done): Done", Tor.said)
        assertTrue("its files are kept in the app's folder", File(dir, "tor/torrc").isFile)
        val port = Tor.socksPort(); Tor.start(c); Tor.start(c) // asked again while it runs, as the relay links do: still the one Tor
        assertEquals(port, Tor.socksPort()); assertEquals(1L, tors())
    }

    @Test fun stoppingEndsTheProgram() {
        Tor.start(c); await("Tor to connect") { Tor.socksPort() != null }
        Tor.stop(c)
        assertNull(Tor.socksPort()); assertEquals("stopped", Tor.said)
        await("the program to end") { tors() == 0L }
        Tor.start(c); await("Tor to connect again, at once after a stop") { Tor.socksPort() != null }
    }

    @Test fun aTorThatEndsIsStartedAgainButNotInALoop() {
        File(dir, "tor").mkdirs(); val marker = File(dir, "tor/ends-after-connecting").also { it.writeText("") }
        Tor.start(c); await("Tor to connect") { Tor.socksPort() != null }
        await("Tor to end by itself") { Tor.socksPort() == null && Tor.said.startsWith("ended: ") }
        assertEquals("ended: Something went wrong", Tor.said)
        val t0 = System.currentTimeMillis(); marker.delete()
        Tor.start(c); assertNull("started again right away", Tor.socksPort()); assertEquals(0L, tors()) // within ten seconds of the last start: not yet
        while (Tor.socksPort() == null) { check(System.currentTimeMillis() - t0 < 20_000) { "never started again: ${Tor.said}" }; Thread.sleep(200); Tor.start(c) }
        assertTrue("it waited before starting again", System.currentTimeMillis() - t0 > 5_000)
    }

    @Test fun aCopyWithoutTorSaysSoAndGivesNoPort() {
        Tor.command = { null }
        Tor.start(c); Thread.sleep(300)
        assertNull(Tor.socksPort()); assertEquals("this copy of DarkProof has no Tor", Tor.said)
        assertEquals(null to "this copy has no Tor", Tor.version())
    }

    /** The relays are on unless the person switches them off; a copy from before the switch existed is put right once; tests stay off. */
    @Test fun theInternetRelaysAreOnUnlessThePersonSaysNo() {
        val fresh = Context(File(dir, "fresh")).getSharedPreferences("mesh", 0)
        Desktop.relayDefault(fresh, relays = true); assertTrue(fresh.getBoolean("internet", false))
        fresh.edit().putBoolean("internet", false).commit() // the person switches it off in Connections
        Desktop.relayDefault(fresh, relays = true); assertFalse("the person's choice is kept at the next start", fresh.getBoolean("internet", true))
        val older = Context(File(dir, "older")).getSharedPreferences("mesh", 0)
        older.edit().putBoolean("internet", false).commit() // written by a build that had no switch
        Desktop.relayDefault(older, relays = true); assertTrue(older.getBoolean("internet", false))
        val test = Context(File(dir, "test")).getSharedPreferences("mesh", 0)
        test.edit().putBoolean("internet", true).commit()
        Desktop.relayDefault(test, relays = false); assertFalse("a test never goes to the relays", test.getBoolean("internet", true))
    }

    @Test fun torSaysItsVersionAndTheCheckReadsTheTorProjectsAnswer() {
        assertEquals("Tor version 0.0.0 (stand-in).", Tor.version().first); assertNotNull(Tor.version().second)
        assertTrue(Tor.isTor("{\"IsTor\":true,\"IP\":\"185.0.0.1\"}")); assertFalse(Tor.isTor("{\"IsTor\":false,\"IP\":\"1.2.3.4\"}")); assertFalse(Tor.isTor("<html>blocked</html>"))
        assertTrue(Tor.enabled(c)); Tor.setEnabled(c, false); assertFalse(Tor.enabled(c)) // on unless the person turns it off, as on the phones
    }
}
