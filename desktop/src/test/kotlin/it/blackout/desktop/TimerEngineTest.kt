package it.blackout.desktop

import it.blackout.mesh.Delivery
import it.blackout.mesh.Disappear
import it.blackout.mesh.GoneList
import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.GroupSecurity
import it.blackout.mesh.Groups
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
 * The group's timer for disappearing messages on the real engine (the phones' own, MeshEngine) and the phones' own
 * screens, in a group made here and left at the end (spec/FORMAT.md, "Disappearing messages"). What the timer decides
 * is tested as plain Kotlin (TimerTest, TimerVectorsTest); this checks the wiring: the admin chooses a value from the
 * "+" tray, a new key carries it and the chat, the header and Group security say it; the notice is never a row; a
 * value deletes nothing in its first ten minutes; then a sent message goes and its packet stays to be carried (its own
 * key is gone), one a key here still opens leaves the store, an unsent one stays, and none is read again when it comes back.
 *
 * Like KeepLessEngineTest, it reaches into the engine where a test can't wait: arrival times and the time the value was
 * first seen are moved back, and a packet is given to the engine's own delivery. TIMER_PNG, if set, names a picture of
 * the chat with the timer set.
 */
class TimerEngineTest {
    private val hour = 3_600_000L

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

    @Test fun anAdminSetsTheTimerAndWhatIsDueGoes() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        val taken = CopyOnWriteArrayList<Packet>()
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, "Clock"); s.tap("Create group")
                s.await("the new group in use") { Groups.current(c)?.name == "Clock" && MeshEngine.currentCode == Groups.current(c)?.code }
                code = Groups.current(c)!!.code; s.secrets += code!!
                s.await("its invite panel") { s.has("Invite to Clock") }; s.tap("Close")
                s.await("this computer to be in it") { MeshEngine.isAdmin(code, MeshEngine.deviceId) }
                val groupId = MeshEngine.groupId(code)!!
                assertEquals("a group made here starts with the timer off, and knows it", 0, MeshEngine.timer(code).seconds)
                assertTrue(s.has("They disappear after one to two weeks."))

                // The admin chooses a value from the "+" tray.
                s.tap("Add something"); s.tap("Timer")
                s.await("the timer sheet") { s.has("Messages disappear after") }
                assertTrue(s.says("1 hour") && s.says("8 hours") && s.says("1 day") && s.says("1 week") && s.says("Off") && s.says("One to two weeks"))
                assertTrue(s.has("A new value starts deleting after 10 minutes."))
                s.tap("1 hour")
                RealEngine.await("the new key that carries it") { MeshEngine.timer(code).seconds == 3_600 }
                s.await("the sheet to close") { !s.has("Messages disappear after") }
                assertEquals("you", MeshEngine.timer(code).by)
                s.await("the chat to say it") { s.has("You set messages to disappear after 1 hour") }
                assertTrue("the composer note", s.has("They disappear 1 hour after they reach each phone."))
                assertTrue("the header's value", s.says("1 hour"))
                System.getenv("TIMER_PNG")?.let { s.png(it) }
                GroupSecurity.lines(c, Groups.current(c)!!).first { it.key == "gone" }.let { l ->
                    assertEquals("After 1 hour, set by you", l.text); assertEquals("Change", l.button); assertTrue(l.explain!!.startsWith("Each phone deletes a message 1 hour after it reaches it."))
                }
                // The notice for older builds went out, and is nothing here: no row.
                assertFalse(MeshEngine.snapshotAll().any { it.group == code && it.text.startsWith("⏱") })
                val kept: HashMap<String, KeepLess.Held> = field("kept")
                synchronized(lock()) { assertTrue("the notice is in the store, timed like any group message", kept.values.count { it.group == groupId } == 1) }

                // A link takes the first message: it says "Sent", and its time starts then.
                MeshEngine.sender = { _, p -> taken += p }
                MeshEngine.router!!.peerUp("test-link")
                MeshEngine.send("Sent one", group = code)
                val sent = MeshEngine.snapshotAll().first { it.text == "Sent one" && it.group == code }
                RealEngine.await("the link to take it") { MeshEngine.delivery(sent) == Delivery.Sent }
                val packet = taken.first { it.id == sent.id }
                MeshEngine.router!!.peerDown("test-link"); MeshEngine.sender = null
                MeshEngine.send("I need help", group = code) // nobody in reach: it hasn't left this computer
                val unsent = MeshEngine.snapshotAll().first { it.text == "I need help" && it.group == code }
                synchronized(lock()) { assertEquals(KeepLess.UNSENT, kept[unsent.id]!!.at); assertTrue(kept[sent.id]!!.at > 0) }

                // Two hours on (its hand-off moved back), but the value is new: it deletes nothing in its first ten minutes.
                synchronized(lock()) { kept[sent.id] = kept[sent.id]!!.let { KeepLess.Held(it.at - 2 * hour, it.ts, it.group) } }
                MeshEngine.cleanUp(); settled()
                assertTrue("a value that hasn't settled deletes nothing", MeshEngine.snapshotAll().any { it.id == sent.id })
                // Eleven minutes after the value came.
                val keys = field<HashMap<String, Any>>("keyCache")[code]!!
                val watch = keys.javaClass.getDeclaredField("watch").apply { isAccessible = true }
                val w = watch.get(keys) as Disappear.Watch
                assertEquals(3_600, w.seconds)
                watch.set(keys, Disappear.Watch(w.seconds, w.since - 11 * 60_000L))
                MeshEngine.cleanUp(); settled()
                assertFalse("the sent message is gone from the chat", MeshEngine.snapshotAll().any { it.id == sent.id })
                assertTrue("the unsent one is still there", MeshEngine.snapshotAll().any { it.id == unsent.id })
                val gone: GoneList = field("gone"); val index: HashMap<String, StoreCap.Entry> = field("index")
                synchronized(lock()) {
                    assertTrue("its name is on the gone list", MeshRouter.key(packet) in gone)
                    assertFalse(sent.id in kept)
                    // Its own key was deleted when it was written: nothing here opens it, so it stays as a packet this computer only carries.
                    assertEquals("still carried for the rest of its week", StoreCap.Kind.UNREADABLE, index[sent.id]?.kind)
                }
                deliver(packet); settled()
                assertFalse("brought again, it is not shown", MeshEngine.snapshotAll().any { it.id == sent.id || it.text == "Sent one" && it.group == code })

                // A message without a key of its own, from another phone: a key here still opens its packet, so it leaves the store.
                fun call(name: String, vararg x: Any?): Any? = keys.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == x.size }.apply { isAccessible = true }.invoke(keys, *x)
                val cr = call("getGroup") as GroupCrypto; val me = call("getMe") as String
                val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
                val cipher = cr.seal(id, ts, me, "Desktop", "Meet at the bridge") { call("sign", it) as ByteArray }
                val bridge = Packet(id, cr.hideTime(ts, cipher), MeshRouter.MAX_TTL, cipher)
                assertTrue(MeshEngine.router!!.receive("test", bridge))
                RealEngine.await("it to be shown") { MeshEngine.snapshotAll().any { it.id == bridge.id } }
                synchronized(lock()) { kept[bridge.id] = kept[bridge.id]!!.let { KeepLess.Held(it.at - 2 * hour, it.ts, it.group) } }
                MeshEngine.cleanUp(); settled()
                assertFalse(MeshEngine.snapshotAll().any { it.id == bridge.id })
                synchronized(lock()) { assertTrue(MeshRouter.key(bridge) in gone); assertFalse("it left the store", bridge.id in index) }
                deliver(bridge); settled()
                assertFalse("a packet this computer could still open is not shown again", MeshEngine.snapshotAll().any { it.id == bridge.id || it.text == "Meet at the bridge" })
                synchronized(lock()) { assertFalse(bridge.id in index); assertFalse(bridge.id in kept) }
                assertNotNull("the router still passes it on", MeshEngine.router!!.source(bridge))

                // Off again: the header shows nothing, the chat says so.
                s.tap("Add something"); s.tap("Timer"); s.await("the timer sheet") { s.has("Messages disappear after") }
                s.tap("Off")
                RealEngine.await("the new key") { MeshEngine.timer(code).seconds == 0 }
                s.await("the chat to say it") { s.has("You switched the timer off. Messages disappear after 1 to 2 weeks.") }
                assertFalse(s.says("1 hour"))

                val left = CountDownLatch(1)
                MeshEngine.leave(c, code!!) { left.countDown() }
                assertTrue("the group wasn't left", left.await(30, TimeUnit.SECONDS))
                code = null
            } finally {
                MeshEngine.sender = null; runCatching { MeshEngine.router?.peerDown("test-link") }
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }
}
