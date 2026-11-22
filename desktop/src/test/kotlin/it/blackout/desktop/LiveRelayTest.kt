package it.blackout.desktop

import it.blackout.mesh.Invite
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.SavedGroup
import it.blackout.mesh.Tor
import it.blackout.mesh.WifiLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real relays, only when a person has said so: two copies of the app on this computer, each with its own
 * throwaway folder and its own Tor, with their links on this computer's network switched off, so that the public
 * relays are the only way between them. One makes a group, the other asks to join with its code, is let in, and a
 * message goes each way. What reaches the relays: the encrypted traffic of that one throwaway group. Nothing is
 * written to Everyone, and the group's code is passed in a file and never printed.
 *
 * It posts to public relays, so it runs only with DARKPROOF_LIVE_RELAYS=1 and DARKPROOF_REAL_TOR=<the Tor program>,
 * and on its own (the engine is one to a process): --tests '*LiveRelayTest*'.
 */
class LiveRelayTest {
    companion object { const val FROM_A = "Hello over the relays, from the first copy"; const val FROM_B = "Hello back over the relays, from the second copy" }
    private fun say(s: String) = println("LIVE A $s")
    private fun await(what: String, ms: Long, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what; relays: ${MeshEngine.nostr?.statusLine()}; Tor: ${Tor.said}" }; Thread.sleep(200) }
    }

    @Test fun twoCopiesTalkOverTheRelaysThroughTor() {
        val tor = System.getenv("DARKPROOF_REAL_TOR")
        assumeTrue("only when a person has said so", System.getenv("DARKPROOF_LIVE_RELAYS") == "1" && !tor.isNullOrBlank())
        val dir = Files.createTempDirectory("darkproof-live").toFile(); val codeFile = File(dir, "group")
        WifiLink.discovery = null
        Tor.command = { listOf(File(tor!!).absolutePath) }
        var second: Process? = null
        try {
            val a = Desktop.activity(File(dir, "a"), relays = false) // switched on below, once the group is there and the local links are off
            val code = Invite.newCode()
            MeshEngine.found(a, code); a.started("Live A", SavedGroup("Relay test", code))
            await("the engine", 60_000) { MeshEngine.router != null }
            await("this copy to be in its own group", 60_000) { !MeshEngine.joining(code) }
            MeshEngine.setWifi(false) // no links on this computer's network: the relays are the only way
            val t0 = System.currentTimeMillis()
            MeshEngine.setInternet(true)
            await("the relays through Tor", 240_000) { MeshEngine.nostr?.let { it.connected > 0 && it.statusLine().endsWith("through Tor") } == true }
            say("online after ${(System.currentTimeMillis() - t0) / 1000} s: ${MeshEngine.nostr?.statusLine()}")

            codeFile.writeText(code); codeFile.setReadable(false, false); codeFile.setReadable(true, true)
            val builder = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "--enable-native-access=ALL-UNNAMED", "@" + FakeLlama.classes.path, "it.blackout.desktop.LivePeer").redirectErrorStream(true)
            builder.environment().apply { put("DARKPROOF_LIVE_HOME", File(dir, "b").path); put("DARKPROOF_GROUP_FILE", codeFile.path); put("DARKPROOF_REAL_TOR", tor!!) }
            val peer = builder.start(); second = peer
            Thread({ peer.inputStream.bufferedReader().forEachLine { if (it.startsWith("LIVE B")) println(it) } }, "second-copy").apply { isDaemon = true }.start()

            await("the second copy's request to join, over the relays", 420_000) { MeshEngine.joinRequests(code).isNotEmpty() || !peer.isAlive }
            check(MeshEngine.joinRequests(code).isNotEmpty()) { "the second copy ended before it asked to join" }
            val request = MeshEngine.joinRequests(code).first(); var let: Boolean? = null
            say("request to join from ${request.name} after ${(System.currentTimeMillis() - t0) / 1000} s")
            MeshEngine.approveJoin(code, request.id) { let = it }
            await("the second copy to be let in", 60_000) { let != null }
            assertEquals(true, let)
            MeshEngine.send(FROM_A)
            await("the second copy's answer, over the relays", 420_000) { MeshEngine.snapshot().any { it.text == FROM_B && it.sender != MeshEngine.deviceId } || !peer.isAlive }
            val answer = MeshEngine.snapshot().firstOrNull { it.text == FROM_B && it.sender != MeshEngine.deviceId }
            check(answer != null) { "the second copy ended before its answer arrived" }
            say("got the answer from ${answer.name} after ${(System.currentTimeMillis() - t0) / 1000} s, signature checked: ${answer.verified}")
            assertTrue("the answer's signature", answer.verified)
            assertEquals("no link on this computer's network: the relays carried it", 0, MeshEngine.linkedNodes().size)
            assertEquals("nothing written to Everyone", 0, MeshEngine.snapshotAll().count { it.public && it.sender == MeshEngine.deviceId })
            say("relays at the end: ${MeshEngine.nostr?.statusLine()}")
            assertTrue("the second copy did not end well", peer.waitFor(90, java.util.concurrent.TimeUnit.SECONDS) && peer.exitValue() == 0)
        } finally {
            second?.destroy()
            runCatching { MeshEngine.setInternet(false); MeshEngine.shutdown() }
            codeFile.delete(); dir.deleteRecursively()
        }
    }
}
