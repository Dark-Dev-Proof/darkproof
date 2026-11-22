package it.blackout.desktop

import it.blackout.mesh.MainActivity
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.WifiLink
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files

/**
 * The phones' engine is one object and starts once in a process, so every test that needs it shares this one. It keeps
 * its data in a folder of its own, and it is set up the way a person does it: by going through the app's own setup
 * screens ("Create a group") with the name "Desktop" and the group name "My group".
 */
object RealEngine {
    val dir: File = Files.createTempDirectory("darkproof-engine").toFile()
    lateinit var activity: MainActivity; private set

    private val start: Unit by lazy {
        WifiLink.discovery = null // the tests' engine must not find, or be found by, real DarkProof devices on this network
        val a = Desktop.activity(dir, relays = false); activity = a // the tests' engine never goes to the real relays // nothing saved yet: the screens ask for a name and a group
        Screen { App(a) }.use { s ->
            check(s.has("Create a group") && s.has("Join a group")) { "a first start doesn't show the setup: ${s.texts()}" }
            s.png("build/screen-setup.png")
            s.tap("Create a group")
            check(s.has("Type your name first")) { "the form doesn't ask for a name: ${s.texts()}" }
            s.type("Anna", "Desktop")                // the name field, by its example
            s.type("Desktop's group", "My group")    // the group name field now suggests one from the name
            s.tap("Create group")
            s.await("the engine to start") { MeshEngine.router != null }
            s.await("the invite sheet, which a new group opens with") { s.has("Invite to My group") && s.has("Invite QR code") }
            s.tap("Close")
            check(!s.has("Invite QR code") && s.has("Looking for phones") && s.has("Channels")) { "the main window isn't showing after the setup: ${s.texts()}" }
        }
        await("the Wi-Fi link to listen") { (MeshEngine.wifi?.links?.port ?: 0) > 0 }
    }
    fun started() = start

    /** Keeps a link to a peer, as typing its address on the Connections screen does. */
    fun connect(host: String, port: Int) = MeshEngine.wifi!!.links.connectManually(host, port)

    fun await(what: String, ms: Long = 15_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) fail("timed out waiting for: $what"); Thread.sleep(50) }
    }
}
