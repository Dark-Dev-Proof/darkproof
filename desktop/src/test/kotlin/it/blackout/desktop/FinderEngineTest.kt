package it.blackout.desktop

import it.blackout.mesh.CheckIn
import it.blackout.mesh.FinderNote
import it.blackout.mesh.FinderPrefs
import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.Groups
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.MlKem
import it.blackout.mesh.Need
import it.blackout.mesh.P256
import it.blackout.mesh.Packet
import it.blackout.mesh.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finder on the real engine (the phones' own MeshEngine) and the phones' own screens, on a computer: a member's "I need
 * help" with a finder note arrives and is read; with the trial switch on, the chat offers "Find by Bluetooth" under it,
 * whose screen says Finder needs a phone's Bluetooth and offers nothing that can't work here; "I'm safe" ends it. With
 * the trial off nothing of Finder shows. A computer never writes the note: it has no Bluetooth links. The rules
 * themselves are tested as plain Kotlin (FinderTest). Pictures: build/finder/screen-*.png.
 */
class FinderEngineTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MeshEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(MeshEngine) as T

    private class Ben(val code: String) {
        val pair = P256.newKeyPair(); val pub = P256.compress(pair.public); val id = P256.memberId(pub)
        fun sign(m: ByteArray) = P256.sign(pair.private, m)
    }
    private fun asks(b: Ben) {
        val cr = GroupCrypto(b.code).intros(); val id = Packet.newId(cr.mailbox); val now = System.currentTimeMillis()
        MeshEngine.router!!.receive("test", Packet(id, now, 16, cr.seal(id, now, b.id, "Ben", "",
            extra = byteArrayOf(3) + b.pub + P256.uncompressed(P256.newKeyPair().public) + MlKem.newKey().first) { b.sign(it) }))
    }
    private fun keys(code: String): Any = field<HashMap<String, Any>>("keyCache")[code]!!
    private fun call(k: Any, name: String): Any? = k.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(k)
    private fun benWrites(b: Ben, cr: GroupCrypto, text: String, fifth: ByteArray?): Packet {
        val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
        val c = cr.seal(id, ts, b.id, "Ben", text, fifth = fifth) { b.sign(it) }
        return Packet(id, cr.hideTime(ts, c), 13, c)
    }

    @Test fun findingSomeoneFromAComputer() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        var code: String? = null
        val link = "a1b2c3d4e5f6"
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, "Search"); s.tap("Create group")
                s.await("the new group in use") { Groups.current(c)?.name == "Search" && MeshEngine.currentCode == Groups.current(c)?.code }
                code = Groups.current(c)!!.code; val g = code!!; s.secrets += g
                s.await("its invite panel") { s.has("Invite to Search") }; s.tap("Close")
                s.await("this computer to be its admin") { MeshEngine.isAdmin(g, MeshEngine.deviceId) }
                val ben = Ben(g); asks(ben)
                RealEngine.await("Ben's request") { MeshEngine.joinRequests(g).any { it.id == ben.id } }
                val letIn = CountDownLatch(1); MeshEngine.approveJoin(g, ben.id) { letIn.countDown() }
                assertTrue(letIn.await(30, TimeUnit.SECONDS))
                RealEngine.await("Ben to be in") { MeshEngine.inGroup(g, ben.id) }
                val cr = call(keys(g), "getGroup") as GroupCrypto

                // A computer doesn't write the note, whatever its switch says.
                assertFalse(FinderPrefs.writes(c))

                // Ben asks for help, with his phone's link id.
                FinderPrefs.setTrial(c, false)
                val help = benWrites(ben, cr, CheckIn.HELP.text(15, needs = setOf(Need.TRAPPED)), FinderNote.field(link))
                assertTrue(MeshEngine.router!!.receive("nostr", help))
                RealEngine.await("Ben's help check-in") { MeshEngine.snapshotAll().any { it.id == help.id } }
                val m = MeshEngine.snapshotAll().first { it.id == help.id }
                assertEquals(link, m.finder)
                assertEquals(link, Person.all(MeshEngine.snapshotAll().filter { it.group == g }, MeshEngine.deviceId).first { it.id == ben.id }.finder)
                a.state.tab = 1; a.state.everyone = false; s.settle()
                s.await("Ben's help card") { s.has("Ben needs help") }
                assertFalse("nothing of Finder while the trial is off", s.has("Find by Bluetooth"))

                // The trial on: the button under his card, and the screen it opens.
                FinderPrefs.setTrial(c, true); a.state.tick++; s.settle()
                s.await("Find by Bluetooth under Ben's card") { s.says("Find by Bluetooth") }
                s.png("build/finder/screen-chat.png")
                s.tap("Find by Bluetooth")
                s.await("the Finder screen") { s.has("Finding Ben") && s.has("Finder needs a phone's Bluetooth") }
                assertTrue(s.has("Ben needs help"))
                assertFalse("no button that can't work on a computer", s.has("I'm with Ben"))
                assertFalse(s.has("Not in reach"))
                s.png("build/finder/screen-computer.png")

                // "I'm safe" ends it, also on the open screen.
                val safe = benWrites(ben, cr, CheckIn.SAFE.text(80), null)
                assertTrue(MeshEngine.router!!.receive("nostr", safe))
                RealEngine.await("Ben's I'm safe") { MeshEngine.snapshotAll().any { it.id == safe.id } }
                s.await("the screen to say Ben is safe") { s.has("Finding Ben") && s.has("Ben is safe") && !s.has("Finder needs a phone's Bluetooth") }
                assertNull(Person.all(MeshEngine.snapshotAll().filter { it.group == g }, MeshEngine.deviceId).first { it.id == ben.id }.finder)
                s.png("build/finder/screen-safe.png")
                s.tap("Close"); s.settle()
                assertFalse("the button is gone with the note", s.says("Find by Bluetooth"))

                val left = CountDownLatch(1)
                MeshEngine.leave(c, g) { left.countDown() }
                assertTrue("the group wasn't left", left.await(30, TimeUnit.SECONDS))
                code = null
            } finally {
                FinderPrefs.setTrial(c, false)
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }
}
