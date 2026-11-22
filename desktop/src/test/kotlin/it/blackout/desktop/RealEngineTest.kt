package it.blackout.desktop

import it.blackout.mesh.Groups
import it.blackout.mesh.MeshEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The phones' own engine (MeshEngine.kt, unchanged), running on a desktop JVM on the desktop's stand-ins. */
class RealEngineTest {
    private fun await(what: String, ms: Long = 15_000, ok: () -> Boolean) = RealEngine.await(what, ms, ok)

    @Test fun aNewGroupAndItsKeysAreKeptInTheDataFolder() {
        RealEngine.started(); val c = RealEngine.activity
        assertNotNull(MeshEngine.router); assertEquals("Desktop", MeshEngine.name)
        val g = Groups.current(c)!!; assertEquals("My group", g.name); assertEquals(g.code, MeshEngine.currentCode)
        assertTrue(File(RealEngine.dir, "mesh.db").length() > 0); assertTrue(File(RealEngine.dir, "vault.key").length() == 32L)
        assertFalse("relays must stay off until the desktop has Tor", c.getSharedPreferences("mesh", 0).getBoolean("internet", true)); assertNull(MeshEngine.nostr)
        c.getSharedPreferences("vault", 0).edit().commit()
        assertFalse("the group's code is readable in the saved files", RealEngine.dir.listFiles()!!.filter { it.extension == "json" }.any { g.code in it.readText() })
    }

    @Test fun aGroupMessageIsWrittenWithTheGroupsKeysAndShown() {
        RealEngine.started()
        await("this computer to be in its own group") { !MeshEngine.joining(MeshEngine.currentCode) }
        MeshEngine.send("hello group")
        await("the group message") { MeshEngine.snapshot().any { it.text == "hello group" && !it.public && it.group == MeshEngine.currentCode && it.sender == MeshEngine.deviceId } }
        assertTrue(MeshEngine.isAdmin(MeshEngine.currentCode, MeshEngine.deviceId))
    }

    @Test fun itTalksOnEveryoneWithAPeerOnThisComputer() {
        RealEngine.started()
        val bo = EveryonePeer("Bo", loopback = true)
        try {
            RealEngine.connect("127.0.0.1", bo.start(0))
            await("the link") { bo.wifi.linkCount() == 1 && MeshEngine.linkedNodes().size == 1 }
            MeshEngine.send("hello everyone", public = true)
            await("the engine's post at the peer, signed") { bo.lines().any { it.text == "hello everyone" && it.name == "Desktop" && it.verified } }
            bo.send("hello from Bo")
            await("the peer's post in the engine, signed") { MeshEngine.snapshotAll().any { it.public && it.text == "hello from Bo" && it.name == "Bo" && it.verified && it.sender != MeshEngine.deviceId } }
        } finally { MeshEngine.wifi?.links?.stopManual(); bo.stop() }
    }

    /**
     * Against a real DarkProof app at DARKPROOF_PEER ("host:port": an emulator behind `adb forward tcp:7778 tcp:7777`, or
     * a phone on the network). Skipped when that isn't set.
     *
     *     DARKPROOF_PEER=127.0.0.1:7778 ./gradlew --offline -Pdesktop :desktop:test --tests '*RealEngineTest.itTalksToARealApp' --rerun
     *
     * With DARKPROOF_GROUP_FILE too, the desktop's group code is written to that file (never printed) for the app to
     * join with; the desktop lets it in, writes to the group and waits for the app's answer there.
     */
    @Test fun itTalksToARealApp() {
        val peer = System.getenv("DARKPROOF_PEER") ?: ""
        assumeTrue("DARKPROOF_PEER is not set", peer.isNotBlank())
        RealEngine.started()
        try {
            RealEngine.connect(peer.substringBeforeLast(':'), peer.substringAfterLast(':').toInt())
            await("a link to $peer", 60_000) { MeshEngine.linkedNodes().isNotEmpty() }
            println("REAL linked: ${MeshEngine.wifi?.links?.statusLine()}")
            MeshEngine.send(System.getenv("DARKPROOF_SAY") ?: "Hello from the desktop", public = true)
            await("a signed Everyone post from the app", 30_000) { MeshEngine.snapshotAll().any { it.public && it.verified && it.sender != MeshEngine.deviceId } }

            val file = System.getenv("DARKPROOF_GROUP_FILE") ?: ""
            if (file.isNotBlank()) {
                val code = MeshEngine.currentCode
                await("this computer to be in its own group") { !MeshEngine.joining(code) }
                File(file).apply { writeText(code); setReadable(false, false); setReadable(true, true) }
                println("REAL waiting for the app to ask to join")
                await("the app's request to join", 180_000) { MeshEngine.joinRequests(code).isNotEmpty() }
                val r = MeshEngine.joinRequests(code).first(); var let: Boolean? = null
                MeshEngine.approveJoin(code, r.id) { let = it }
                await("the app to be let in") { let != null }
                println("REAL let in ${r.name}: $let"); assertTrue(let == true)
                MeshEngine.send("Hello group, from the desktop")
                await("a signed group message from the app", 180_000) { MeshEngine.snapshot().any { !it.public && it.verified && it.sender != MeshEngine.deviceId } }
            }
            Thread.sleep(3_000) // time for this computer's last packets and receipts to leave
            MeshEngine.snapshotAll().forEach { println("REAL line public=${it.public} mine=${it.sender == MeshEngine.deviceId} verified=${it.verified} from=${it.name}: ${it.text.take(80)}") }
        } finally { MeshEngine.wifi?.links?.stopManual() }
    }
}
