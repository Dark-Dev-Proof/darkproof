package it.blackout.desktop

import it.blackout.mesh.MeshEngine
import it.blackout.mesh.SavedGroup
import it.blackout.mesh.Tor
import it.blackout.mesh.WifiLink
import java.io.File
import kotlin.system.exitProcess

/**
 * The second copy of the app in [LiveRelayTest]: a program of its own, since the engine is one to a process. It joins
 * the group whose code is in a file (never printed), waits to be let in and for the first copy's message, answers, and
 * ends. Its only way to the first copy is the internet relays, through its own Tor.
 */
object LivePeer {
    private fun say(s: String) = println("LIVE B $s")
    private fun await(what: String, ms: Long, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) { say("TIMED OUT waiting for $what; relays: ${MeshEngine.nostr?.statusLine()}; Tor: ${Tor.said}"); MeshEngine.shutdown(); exitProcess(3) }; Thread.sleep(200) }
    }

    @JvmStatic fun main(args: Array<String>) {
        WifiLink.discovery = null // it neither looks for devices on this network nor announces itself
        Tor.command = { listOf(File(System.getenv("DARKPROOF_REAL_TOR")).absolutePath) }
        val a = Desktop.activity(File(System.getenv("DARKPROOF_LIVE_HOME")), relays = false) // switched on below
        val code = File(System.getenv("DARKPROOF_GROUP_FILE")).readText().trim()
        a.started("Live B", SavedGroup("Relay test", code))
        await("the engine", 60_000) { MeshEngine.router != null }
        MeshEngine.setWifi(false) // no links on this computer's network: the relays are the only way
        val t0 = System.currentTimeMillis()
        MeshEngine.setInternet(true)
        await("the relays through Tor", 240_000) { MeshEngine.nostr?.let { it.connected > 0 && it.statusLine().endsWith("through Tor") } == true }
        say("online after ${(System.currentTimeMillis() - t0) / 1000} s: ${MeshEngine.nostr?.statusLine()}")
        await("to be let into the group", 420_000) { !MeshEngine.joining(code) }
        say("let into the group after ${(System.currentTimeMillis() - t0) / 1000} s")
        await("the first copy's message", 300_000) { MeshEngine.snapshot().any { it.text == LiveRelayTest.FROM_A && it.sender != MeshEngine.deviceId } }
        val got = MeshEngine.snapshot().first { it.text == LiveRelayTest.FROM_A }
        say("got the message from ${got.name}, signature checked: ${got.verified}")
        MeshEngine.send(LiveRelayTest.FROM_B)
        Thread.sleep(25_000) // time for the answer to go up to the relays
        say("answered; links on this network: ${MeshEngine.linkedNodes().size}; sent to Everyone by this copy: ${MeshEngine.snapshotAll().count { it.public && it.sender == MeshEngine.deviceId }}")
        MeshEngine.setInternet(false); MeshEngine.shutdown()
        exitProcess(0)
    }
}
