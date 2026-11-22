package it.blackout.desktop

import it.blackout.mesh.Delivery
import it.blackout.mesh.GoneList
import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.Groups
import it.blackout.mesh.HighRisk
import it.blackout.mesh.KeepLess
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.MeshRouter
import it.blackout.mesh.Packet
import it.blackout.mesh.StoreCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * High-risk mode's 24-hour rule on the real engine (the phones' own, MeshEngine), in a group made here and left at the
 * end. What the rule decides is tested as plain Kotlin (HighRiskTest); this checks that the engine is wired to it:
 * a message of this computer's that a link took goes a day after the hand-off, one that never left stays, and the
 * removed packet, delivered again the way the router delivers a packet it doesn't know (after a restart it knows none
 * of those that were removed), is not read again: neither one whose own key was deleted, nor one that a key this
 * computer holds would still open.
 *
 * Two things here reach into the engine, because a test can't wait two days or restart the one engine a process has:
 * the hand-off time the engine noted is moved two days back, and the packet is given to the engine's own delivery.
 */
class KeepLessEngineTest {
    private val day = 24 * 3_600_000L

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MeshEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(MeshEngine) as T
    private fun lock(): Any = field("messages")
    private fun deliver(p: Packet) { MeshEngine::class.java.getDeclaredMethod("onDeliver", Packet::class.java).apply { isAccessible = true }.invoke(MeshEngine, p) }

    /** Waits until everything queued on the engine's background thread so far is done (a harmless call that answers after them). */
    private fun settled() {
        val done = CountDownLatch(1)
        MeshEngine.newKey(MeshEngine.currentCode) { done.countDown() }
        assertTrue("the engine's background work didn't finish", done.await(20, TimeUnit.SECONDS))
    }

    @Test fun aSentMessageGoesAfterADayAnUnsentOneStaysAndWhatWentIsNotReadAgain() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        val taken = CopyOnWriteArrayList<Packet>()
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, "Day"); s.tap("Create group")
                s.await("the new group in use") { Groups.current(c)?.name == "Day" && MeshEngine.currentCode == Groups.current(c)?.code }
                code = Groups.current(c)!!.code; s.secrets += code!!
                s.await("its invite panel") { s.has("Invite to Day") }; s.tap("Close")
                s.await("this computer to be in it") { MeshEngine.isAdmin(code, MeshEngine.deviceId) }
                val groupId = MeshEngine.groupId(code)!!
                HighRisk.set(c, true); a.state.highRisk = true

                // A link takes the first message: it says "Sent", and its day starts then.
                MeshEngine.sender = { _, p -> taken += p }
                MeshEngine.router!!.peerUp("test-link")
                MeshEngine.send("Sent one", group = code)
                val sent = MeshEngine.snapshotAll().first { it.text == "Sent one" && it.group == code }
                RealEngine.await("the link to take it") { MeshEngine.delivery(sent) == Delivery.Sent }
                val packet = taken.first { it.id == sent.id }
                MeshEngine.router!!.peerDown("test-link"); MeshEngine.sender = null
                // Nobody is in reach for the second one: it has not left this computer.
                MeshEngine.send("I need help", group = code)
                val unsent = MeshEngine.snapshotAll().first { it.text == "I need help" && it.group == code }
                assertEquals(Delivery.Waiting, MeshEngine.delivery(unsent))

                val kept: HashMap<String, KeepLess.Held> = field("kept")
                synchronized(lock()) {
                    assertEquals("an unsent message has no arrival time", KeepLess.UNSENT, kept[unsent.id]!!.at)
                    assertTrue("a sent one counts from its hand-off", kept[sent.id]!!.at > 0)
                    assertEquals(groupId, kept[sent.id]!!.group)
                }
                // Within the day nothing goes.
                MeshEngine.cleanUp(); settled()
                assertTrue(MeshEngine.snapshotAll().any { it.id == sent.id } && MeshEngine.snapshotAll().any { it.id == unsent.id })

                // Two days later (the hand-off is moved two days back; the unsent one has no time to move).
                synchronized(lock()) { kept[sent.id] = kept[sent.id]!!.let { KeepLess.Held(it.at - 2 * day, it.ts, it.group) } }
                MeshEngine.cleanUp(); settled()
                assertFalse("the sent message is gone from the chat", MeshEngine.snapshotAll().any { it.id == sent.id })
                assertTrue("the unsent one is still there", MeshEngine.snapshotAll().any { it.id == unsent.id && it.text == "I need help" })
                assertEquals(Delivery.Waiting, MeshEngine.delivery(unsent))
                val gone: GoneList = field("gone"); val index: HashMap<String, StoreCap.Entry> = field("index")
                synchronized(lock()) {
                    assertTrue("its name is on the gone list", MeshRouter.key(packet) in gone)
                    assertFalse(sent.id in kept); assertFalse("its packet left the store", sent.id in index)
                }

                // Another phone brings the very packet again. It is not read: no row, nothing counted for the rule.
                deliver(packet); settled()
                assertFalse("it is not shown again", MeshEngine.snapshotAll().any { it.id == sent.id || it.text == "Sent one" && it.group == code })
                synchronized(lock()) {
                    assertFalse(sent.id in kept)
                    // Its own key was deleted when it was written, so nothing here opens it: it is carried like any such packet.
                    assertEquals(StoreCap.Kind.UNREADABLE, index[sent.id]?.kind)
                }
                assertNotNull("the router still passes it on", MeshEngine.router!!.source(packet))
                // The clean-up doesn't take it for a message again, and doesn't bring it back.
                MeshEngine.cleanUp(); settled()
                assertFalse(MeshEngine.snapshotAll().any { it.id == sent.id })

                // A message that has no key of its own (a member without message keys writes like this, under the group's
                // key): it comes in from another phone, and this computer could still open its packet later.
                val keys = field<HashMap<String, Any>>("keyCache")[code]!!
                fun call(name: String, vararg x: Any?): Any? = keys.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == x.size }.apply { isAccessible = true }.invoke(keys, *x)
                val cr = call("getGroup") as GroupCrypto; val me = call("getMe") as String
                fun written(text: String): Packet {
                    val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
                    val cipher = cr.seal(id, ts, me, "Desktop", text) { call("sign", it) as ByteArray }
                    return Packet(id, cr.hideTime(ts, cipher), MeshRouter.MAX_TTL, cipher)
                }
                val bridge = written("Meet at the bridge")
                assertTrue(MeshEngine.router!!.receive("test", bridge))
                RealEngine.await("it to be shown") { MeshEngine.snapshotAll().any { it.id == bridge.id && it.text == "Meet at the bridge" } }
                synchronized(lock()) { assertTrue("what arrives counts from its arrival", kept[bridge.id]!!.at > 0) }
                synchronized(lock()) { kept[bridge.id] = kept[bridge.id]!!.let { KeepLess.Held(it.at - 2 * day, it.ts, it.group) } }
                MeshEngine.cleanUp(); settled()
                assertFalse(MeshEngine.snapshotAll().any { it.id == bridge.id })
                synchronized(lock()) { assertTrue(MeshRouter.key(bridge) in gone); assertFalse(bridge.id in index) }
                // It comes again. A held key would open it, so without the gone list it would be shown again, as the same
                // kind of packet just was. It is not read, and it is not written back to the store either.
                deliver(bridge); settled()
                assertFalse("a packet this computer could still open is not shown again", MeshEngine.snapshotAll().any { it.id == bridge.id || it.text == "Meet at the bridge" })
                synchronized(lock()) { assertFalse(bridge.id in index); assertFalse(bridge.id in kept); assertFalse(MeshRouter.key(bridge) in index) }
                assertNotNull("the router still passes it on", MeshEngine.router!!.source(bridge))
                // A packet of the very same kind that was never removed is read as always.
                val other = written("Bring water")
                deliver(other); settled()
                assertTrue(MeshEngine.snapshotAll().any { it.id == other.id && it.text == "Bring water" })

                // Leaving the group takes its names off the gone list and its packets out of the router.
                val left = CountDownLatch(1)
                MeshEngine.leave(c, code!!) { left.countDown() }
                assertTrue("the group wasn't left", left.await(30, TimeUnit.SECONDS))
                synchronized(lock()) { assertFalse(MeshRouter.key(packet) in gone); assertFalse(MeshRouter.key(bridge) in gone); assertTrue(kept.values.none { it.group == groupId }) }
                for (p in listOf(packet, bridge, other)) assertEquals("the router no longer holds the left group's packets", null, MeshEngine.router!!.source(p))
                assertTrue(Groups.all(c).none { it.code == code })
                code = null
            } finally {
                MeshEngine.sender = null; runCatching { MeshEngine.router?.peerDown("test-link") }
                HighRisk.set(c, false); a.state.highRisk = false
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }
}
