package it.blackout.desktop

import android.content.Context
import it.blackout.mesh.Socks5
import it.blackout.mesh.Tor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real Tor, only when asked for: the Tor Project's own program, started the way the app starts it, reaching the
 * real Tor network, and then the same check the relay links make before anything goes to a relay. It needs the
 * internet and up to a couple of minutes, so it runs only with
 *   DARKPROOF_REAL_TOR      the Tor program, e.g. desktop/engine/macos-arm64/tor/tor
 * It reaches the Tor network and the Tor Project's own check page, and nothing else: no relay, no message.
 */
class RealTorTest {
    @Test fun realTorStartsAndTrafficLeavesThroughIt() {
        val program = System.getenv("DARKPROOF_REAL_TOR")
        assumeTrue("only when asked for", !program.isNullOrBlank())
        val dir = Files.createTempDirectory("darkproof-real-tor").toFile(); val before = Tor.command
        Tor.command = { listOf(File(program!!).absolutePath) }
        val c = Context(dir)
        try {
            val (version, why) = Tor.version(); println("REAL TOR version: ${version ?: why}")
            assertTrue(why, version != null)
            val t0 = System.currentTimeMillis()
            Tor.start(c)
            var last = ""
            while (Tor.socksPort() == null) {
                if (Tor.said != last) { last = Tor.said; println("REAL TOR ${(System.currentTimeMillis() - t0) / 1000} s: $last") }
                check(System.currentTimeMillis() - t0 < 180_000) { "Tor did not connect in three minutes; it last said: ${Tor.said}" }
                Thread.sleep(250); Tor.start(c) // as the relay links do while they wait
            }
            println("REAL TOR connected after ${(System.currentTimeMillis() - t0) / 1000} s: ${Tor.said}")
            // The relay links' own way through it, and their own check
            val socks = Socks5.factory(Tor.socksPort()!!, "darkproof-test")
            val through = okhttp3.OkHttpClient.Builder().socketFactory(socks).dns(Socks5.noDns).build()
            assertTrue("the Tor Project says this traffic does not come from Tor", Tor.check(through, "test"))
            // The same question asked straight, without Tor, is answered with "not Tor": the check tells the two apart
            assertFalse("a request that does not go through Tor passed the check", Tor.check(okhttp3.OkHttpClient(), "direct"))
            println("REAL TOR check: through Tor yes, direct no")
            Tor.stop(c); Thread.sleep(1500)
            assertTrue(Tor.socksPort() == null)
        } finally { Tor.stop(c); Tor.command = before; dir.deleteRecursively() }
    }
}
