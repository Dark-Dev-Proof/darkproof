package it.blackout.desktop

import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.Groups
import it.blackout.mesh.Keyring
import it.blackout.mesh.LeaveNote
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.MeshRouter
import it.blackout.mesh.MlKem
import it.blackout.mesh.P256
import it.blackout.mesh.Packet
import it.blackout.mesh.Rekey
import it.blackout.mesh.StoreCap
import it.blackout.mesh.Telling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Leaving the group on the real engine (the phones' own, MeshEngine) and the phones' own screens (spec/FORMAT.md,
 * "Leaving the group"). What decides is tested as plain Kotlin (LeaveTest, LeaveVectorsTest); this checks the wiring:
 * - a member's note, sealed with her own message key and brought by a link, makes this computer (an admin) remove her
 *   with the normal removal; the chat says "Vera left the group", never "You removed Vera", the note is no row, and
 *   what she dated after it isn't shown; a note under another member's id that her key signed does nothing;
 * - this computer, the only admin, leaves through the leave sheet: it asks who runs the group, makes one admin new key
 *   for them, sends its note (which goes on the radio), never makes a removal of itself, and once the group is forgotten
 *   the note's packet is still in the store and still passed on to a phone that comes into reach.
 * Pictures along the way are left in build/screen-leave-*.png.
 */
class LeaveEngineTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MeshEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(MeshEngine) as T
    private fun keys(code: String): Any = field<HashMap<String, Any>>("keyCache")[code]!!
    private fun call(k: Any, name: String): Any? = k.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(k)
    private fun ring(code: String) = call(keys(code), "getRing") as Keyring
    private fun me(code: String) = call(keys(code), "getMe") as String

    private class Member(val code: String, val name: String) {
        val pair = P256.newKeyPair(); val pub = P256.compress(pair.public); val id = P256.memberId(pub)
        fun sign(m: ByteArray) = P256.sign(pair.private, m)
    }

    /** [m] asks to join, as their phone would: an introduction through the door, with an ML-KEM key; then this computer lets them in. */
    private fun letIn(m: Member) {
        val cr = GroupCrypto(m.code).intros(); val id = Packet.newId(cr.mailbox); val now = System.currentTimeMillis()
        MeshEngine.router!!.receive("test", Packet(id, now, 16, cr.seal(id, now, m.id, m.name, "",
            extra = byteArrayOf(3) + m.pub + P256.uncompressed(P256.newKeyPair().public) + MlKem.newKey().first) { m.sign(it) }))
        RealEngine.await("${m.name}'s request") { MeshEngine.joinRequests(m.code).any { it.id == m.id } }
        val done = CountDownLatch(1); MeshEngine.approveJoin(m.code, m.id) { done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        RealEngine.await("${m.name} to be in") { MeshEngine.inGroup(m.code, m.id) }
    }

    /** A message [m]'s phone writes under the epoch's key, dated [ts]. */
    private fun says(m: Member, text: String, ts: Long): Packet {
        val cr = call(keys(m.code), "getGroup") as GroupCrypto
        val id = Packet.newId(cr.mailbox); val c = cr.seal(id, ts, m.id, m.name, text) { m.sign(it) }
        return Packet(id, cr.hideTime(ts, c), 16, c)
    }

    /** The leave note under [m]'s id, sealed with [m]'s own message key in the current epoch, as their phone writes it; [signer] signs it. */
    private fun note(m: Member, ts: Long, signer: Member = m): Packet {
        val e = ring(m.code).current(); val keys = e.messageKeys!!; val i = keys.index(m.id); assertTrue(i >= 0)
        val n = 500 + java.security.SecureRandom().nextInt(1_000); val mk = keys.key(i, n)!!
        val id = Packet.newId(e.group.mailbox)
        val c = e.group.sealWith(mk, i, n, id, ts, m.id, m.name, LeaveNote.TEXT, fifth = LeaveNote.field) { signer.sign(it) }
        return Packet(id, e.group.hideTime(ts, c), 16, c)
    }

    private fun newGroup(s: Screen, name: String): String {
        val a = RealEngine.activity
        s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, name); s.tap("Create group")
        s.await("the new group in use") { Groups.current(a)?.name == name && MeshEngine.currentCode == Groups.current(a)?.code }
        val code = Groups.current(a)!!.code; s.secrets += code
        s.await("its invite panel") { s.has("Invite to $name") }; s.tap("Close")
        s.await("this computer to be its admin") { MeshEngine.isAdmin(code, MeshEngine.deviceId) }
        return code
    }

    @Test fun aMemberLeavesAndTheAdminsEngineRemovesHer() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                val g = newGroup(s, "Parting").also { code = it }
                val vera = Member(g, "Vera"); val ben = Member(g, "Ben")
                letIn(vera); letIn(ben)

                // Vera writes once before her note and once dated after it, both before the note comes.
                val noteTs = System.currentTimeMillis()
                MeshEngine.router!!.receive("test", says(vera, "Before I go", noteTs - 1_000))
                MeshEngine.router!!.receive("test", says(vera, "Still here?", noteTs + 1_000))
                RealEngine.await("both to be shown") { MeshEngine.snapshotAll().count { it.group == g && it.sender == vera.id } == 2 }

                // A note under Ben's id that Vera's key signed: not his, does nothing.
                MeshEngine.router!!.receive("test", note(ben, noteTs, signer = vera))
                // Vera's own note: this computer is an admin, and removes her after 0 to 3 s with the normal removal.
                val before = ring(g).current().number
                MeshEngine.router!!.receive("test", note(vera, noteTs))
                val myId = me(g)
                // The removal moves the group to a new code: from then on it goes by that one.
                RealEngine.await("Vera to be removed") { Groups.current(c)?.code != g && MeshEngine.currentCode != g }
                val g2 = Groups.current(c)!!.code; code = g2; s.secrets += g2
                RealEngine.await("Vera out of the key in use") { !MeshEngine.inGroup(g2, vera.id) }
                val removal = ring(g2).all().single { it.removes == vera.id }
                assertEquals("made here, one number up", myId, removal.by); assertEquals(before + 1, removal.number)
                assertEquals("a removal gives the rest a new code", g2, removal.code)
                assertTrue("Ben stays: the note signed by Vera under his id did nothing", MeshEngine.inGroup(g2, ben.id))
                val lines = MeshEngine.groupLines(g2).map { it.second }
                assertTrue(lines.toString(), lines.none { "removed Vera" in it || "Ben left" in it })
                assertEquals("one line for her leaving", 1, lines.count { "Vera left" in it })
                // The note is never a row; what she dated after it isn't shown, what she wrote before it is.
                val rows = MeshEngine.snapshotAll().filter { it.group == g2 }
                assertTrue(rows.none { it.text == LeaveNote.TEXT })
                assertTrue(rows.any { it.text == "Before I go" }); assertFalse(rows.any { it.text == "Still here?" })
                s.await("the chat to say it") { s.has("Vera left the group") }
                assertFalse(s.has(LeaveNote.TEXT)); assertFalse(s.has("Still here?")); assertTrue(s.has("Before I go"))
                s.png("build/screen-leave-admin.png")
            } finally {
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }

    /**
     * Review round 2b, M5 (on the iPhone, Leave deleted its own note) and Low 19: with nothing in reach, this computer
     * sends its note, makes no key in the group while it waits (here: an admin's timer change is refused), then forgets the
     * group. The note's packet is still in the store as one it only carries, and goes to a phone that comes into reach.
     */
    @Test fun aNoteThatDidNotLeaveInTimeIsStillCarried() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        val taken = CopyOnWriteArrayList<Pair<String, Packet>>()
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                val g = newGroup(s, "Offline").also { code = it }
                val ben = Member(g, "Ben"); letIn(ben)
                MeshEngine.sender = { peer, p -> taken += peer to p }
                val id = MeshEngine.sendLeaveNote(g)!!
                assertEquals(id, MeshEngine.toldNote(g))
                // While it waits, no new key: not even an admin's own timer change.
                val number = ring(g).current().number
                val refused = CountDownLatch(1); var ok = true
                MeshEngine.setTimer(g, 3_600) { ok = it; refused.countDown() }
                assertTrue(refused.await(30, TimeUnit.SECONDS)); assertFalse("no key while the note waits", ok)
                assertEquals(number, ring(g).current().number)
                // The wait ends with nobody in reach: the group is forgotten, the note kept to carry.
                val done = CountDownLatch(1); MeshEngine.leave(c, g, id) { done.countDown() }
                assertTrue(done.await(30, TimeUnit.SECONDS))
                assertTrue(Groups.all(c).none { it.code == g }); code = null
                val index = field<HashMap<String, StoreCap.Entry>>("index")
                assertEquals("still in the store, as a packet it only carries", StoreCap.Kind.UNREADABLE, synchronized(field<Any>("messages")) { index[id] }?.kind)
                assertEquals(null, MeshEngine.toldNote(g))
                MeshEngine.router!!.peerUp("ble:later")
                RealEngine.await("the note to reach the phone that came") { taken.any { (peer, p) -> peer == "ble:later" && p.id == id } }
            } finally {
                MeshEngine.sender = null; runCatching { MeshEngine.router?.peerDown("ble:later") }
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }

    /**
     * Review round 2b, M6: an erase takes over from a leave that waits for its note. The leave's wait ends without its last
     * step, its note is handed to the erase, and no leave starts or waits afterwards. (Only the waits: the erase itself
     * isn't run here, it would empty the engine every test shares.)
     */
    @Test fun anEraseTakesOverFromALeavesWait() {
        RealEngine.started()
        val leaveFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            Telling.wait(listOf("00000000000000aa")) { leaveFinished.set(true) }
            assertTrue(Telling.waiting)
            assertEquals(listOf("00000000000000aa"), Telling.takeOver())
            assertFalse("the leave waits no more", Telling.waiting); assertTrue(Telling.erasing); assertNull(Telling.left)
            Telling.wait(listOf("00000000000000ab")) { leaveFinished.set(true) }
            assertFalse("no leave waits once the erase began", Telling.waiting)
            Thread.sleep(500)
            assertFalse("the leave's last step never ran", leaveFinished.get())
            assertEquals("nothing more to take over", emptyList<String>(), Telling.takeOver())
            val erased = java.util.concurrent.atomic.AtomicReference<Set<String>?>(null)
            Telling.wait(listOf("00000000000000aa"), erase = true) { erased.set(it) }
            assertTrue(Telling.waiting); Telling.now()
            RealEngine.await("the erase's wait to end") { erased.get() != null }
            assertEquals(emptySet<String>(), erased.get())
        } finally { Telling.dryRunOver() }
    }

    @Test fun theOnlyAdminHandsOverTellsTheGroupAndNeverRemovesItself() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        val taken = CopyOnWriteArrayList<Pair<String, Packet>>()
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                val g = newGroup(s, "Farewell").also { code = it }
                val ben = Member(g, "Ben"); letIn(ben)
                assertTrue(MeshEngine.onlyAdmin(g)); assertTrue(MeshEngine.canTell(g))
                val myId = me(g)
                val lora = field<it.blackout.mesh.RecentIds>("receiptIds")
                // A link that takes every packet: the note leaves this computer as soon as it is sent.
                MeshEngine.sender = { peer, p -> taken += peer to p }
                MeshEngine.router!!.peerUp("ble:test")

                s.tap("Settings"); s.wheel("Advanced", 30)
                s.tap("Leave Farewell")
                s.await("the only admin's question") { s.has("Who runs Farewell after you?") }
                assertTrue(s.has("Admins can let people in and remove people.") && s.says("Leave without choosing") && s.has("Nobody will be able to let people in or remove anyone."))
                s.png("build/screen-leave-choose.png")
                s.tap("Ben"); s.tap("Continue")
                s.await("the question") { s.has("Leave Farewell?") && s.has("A note asks the group's admins to remove you and change the key.") }
                s.png("build/screen-leave-ask.png")
                s.tap("Leave Farewell")
                s.await("the group left", 30_000) { Groups.all(c).none { it.code == g } }
                s.await("the note") { s.has("You left Farewell") }
                assertFalse("a link took it in time", s.has("didn't leave this computer in time"))

                // One admin new key for Ben, through the door, before the note; none that removes this computer.
                val door = GroupCrypto(g).rekeys()
                val keys = taken.map { it.second }.filter { Packet.mailbox(it.id) == door.mailbox }.mapNotNull { p -> door.openSigned(p)?.extra?.let(Rekey::decode)?.let { p to it } }
                val handOver = keys.single { (_, r) -> ben.id in r.admins && !r.removal }
                assertTrue("this computer stays listed and an admin in it", myId in handOver.second.members && myId in handOver.second.admins)
                assertTrue("never a removal of itself", keys.none { (_, r) -> r.removal })
                // The note: after the new key, in another mailbox, small, and on the radio like any message.
                val after = taken.map { it.second }.dropWhile { it.id != handOver.first.id }.drop(1)
                val notes = after.filter { Packet.mailbox(it.id) != door.mailbox && Packet.mailbox(it.id) != GroupCrypto(g).intros().mailbox }
                val note = notes.single()
                assertFalse("not kept off LoRa", MeshRouter.key(note) in lora)
                // It stays in the store, as a packet this computer only carries, and is passed on to a phone that comes into reach.
                val index = field<HashMap<String, StoreCap.Entry>>("index")
                assertEquals(StoreCap.Kind.UNREADABLE, synchronized(field<Any>("messages")) { index[note.id] }?.kind)
                assertNotNull("the router still carries it", MeshEngine.router!!.source(note))
                assertFalse("its id left this computer's lists", MeshEngine.noteLeft(note.id))
                val before = taken.size
                MeshEngine.router!!.peerUp("ble:later")
                RealEngine.await("the note to reach the phone that came") { taken.drop(before).any { (peer, p) -> peer == "ble:later" && p.id == note.id } }
                code = null
            } finally {
                MeshEngine.sender = null; runCatching { MeshEngine.router?.peerDown("ble:test") }; runCatching { MeshEngine.router?.peerDown("ble:later") }
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }
}
