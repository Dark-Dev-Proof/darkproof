package it.blackout.desktop

import it.blackout.mesh.Invite
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.SavedGroup
import it.blackout.mesh.WifiLink
import java.io.File

/**
 * `DarkProof --self-test`: starts the engine without a window in the data folder (set DARKPROOF_HOME to a throwaway
 * one: a folder already in use is refused), makes a group, writes to it and to Everyone, and checks they are kept. It shows that a built app has what the
 * engine needs on this computer: the encryption, the database and the network link. The result is the exit code (0 is
 * good) and a file self-test.txt in the data folder, since a built Windows app has no console to print to.
 */
object SelfTest {
    fun run(): Int {
        val out = StringBuilder()
        fun say(s: String) { out.appendLine(s); println(s) }
        val ok = runCatching {
            WifiLink.discovery = null // a self-test's messages are not for real devices: it neither looks for them nor announces itself
            val a = Desktop.activity(relays = false) // a self-test's messages are not for the real relays
            check(a.state.group == null) { "this data folder is in use: set DARKPROOF_HOME to an empty folder" } // never a test group in someone's real DarkProof
            say("data folder ready on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}")
            val code = Invite.newCode()
            MeshEngine.found(a, code); a.started("Self test", SavedGroup("Self test", code))
            await("the engine to start") { MeshEngine.router != null }; say("engine started: keys made, database open")
            await("the group's first key") { !MeshEngine.joining(code) }
            MeshEngine.send("group message"); MeshEngine.send("everyone message", public = true)
            await("both messages to be kept") { MeshEngine.snapshotAll().count { it.sender == MeshEngine.deviceId } == 2 }; say("a group message and an Everyone message sealed, signed and kept")
            await("the network link to listen") { (MeshEngine.wifi?.links?.port ?: 0) > 0 }; say("listening for phones on port ${MeshEngine.wifi?.links?.port}")
            // The AI engine is a program of its own: that it is in this copy and runs on this computer, without a model
            if (Llama.program() == null) say("no AI engine in this copy")
            else say("AI engine runs: " + (Llama.version() ?: error("the AI engine in this copy does not run on this computer: " + Llama.whyNot())))
            // The second engine, for the graphics card, needs a card and a driver: that it doesn't start here is no fault, the app falls back
            val resources = System.getProperty("compose.application.resources.dir")
            if (resources != null && java.io.File(resources, "llama-gpu").isDirectory) say("AI engine for the graphics card: " + when {
                Llama.program(graphics = true) == null -> "in this copy, and not offered on this computer (it has no Vulkan, which comes with a graphics card's driver)"
                else -> Llama.version(graphics = true)?.let { "runs: $it" } ?: ("does not start on this computer: " + Llama.whyNot(graphics = true))
            })
            // Tor is a program of its own too: that it is in this copy and runs here, by asking it for its version. It is
            // NOT started for real here. On 2026-11-21 a self-test that connected Tor to its network ran on GitHub's build
            // machines, and the GitHub account was suspended within minutes (GitHub gave no reason; the timing points at
            // this). A build machine is not the place to reach the Tor network from. A person at their own computer can
            // ask for that check with DARKPROOF_SELF_TEST_TOR=1.
            if (it.blackout.mesh.Tor.program() == null) say("no Tor in this copy") else {
                val (version, why) = it.blackout.mesh.Tor.version()
                say("Tor runs: " + (version ?: error("the Tor in this copy does not run on this computer: $why")))
                if (System.getenv("DARKPROOF_SELF_TEST_TOR") == "1") {
                    val t0 = System.currentTimeMillis(); it.blackout.mesh.Tor.start(a)
                    while (it.blackout.mesh.Tor.socksPort() == null && System.currentTimeMillis() - t0 < 90_000) { Thread.sleep(250); it.blackout.mesh.Tor.start(a) }
                    val port = it.blackout.mesh.Tor.socksPort()
                    if (port == null) say("Tor did not get through to its network from here in 90 seconds; it last said: ${it.blackout.mesh.Tor.said}") else {
                        val through = okhttp3.OkHttpClient.Builder().socketFactory(it.blackout.mesh.Socks5.factory(port, "self-test")).dns(it.blackout.mesh.Socks5.noDns).build()
                        say("Tor connected in ${(System.currentTimeMillis() - t0) / 1000} s; the Tor Project says traffic leaves through Tor: ${it.blackout.mesh.Tor.check(through, "self-test")}")
                    }
                    it.blackout.mesh.Tor.stop(a)
                }
            }
            MeshEngine.shutdown()
        }.onFailure { say("FAILED: $it"); it.stackTrace.take(12).forEach { l -> say("    at $l") } }.isSuccess
        say(if (ok) "SELF-TEST OK" else "SELF-TEST FAILED")
        runCatching { File(Home.folder(), "self-test.txt").writeText(out.toString()) }
        return if (ok) 0 else 1
    }

    private fun await(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!ok()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(50) }
    }
}
