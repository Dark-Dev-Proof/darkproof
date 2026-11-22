package it.blackout.desktop

import it.blackout.mesh.About
import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.Groups
import it.blackout.mesh.HighRisk
import it.blackout.mesh.KeepLess
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.MeshRouter
import it.blackout.mesh.GoneList
import it.blackout.mesh.MlKem
import it.blackout.mesh.P256
import it.blackout.mesh.Packet
import it.blackout.mesh.Voice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Voice notes on the real engine (the phones' own, MeshEngine) and the phones' own chat screens, on a computer, which has
 * no codec: a member's voice note (made by Apple's encoder in the codec experiment) arrives, is shown as "Voice note,
 * 0:10" with "Play it on a phone", has no "Copy" in its menu, can be a reply whose quote opens the menu when held, is
 * dropped on the Public channel, and leaves a day after it arrived in high-risk mode like any group message. A computer
 * offers no mic. The rules themselves are tested as plain Kotlin (VoiceNotesTest). Pictures: build/screen-voice-*.png.
 */
class VoiceEngineTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MeshEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(MeshEngine) as T
    private fun lock(): Any = field("messages")
    private val day = 24 * 3_600_000L

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
    private fun benWrites(b: Ben, cr: GroupCrypto, text: String, voice: ByteArray?, about: About? = null): Packet {
        val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
        val c = cr.seal(id, ts, b.id, "Ben", text, about = about, voice = voice) { b.sign(it) }
        return Packet(id, cr.hideTime(ts, c), 13, c)
    }
    /** Apple's 8 kbit/s VBR note from the experiment, its first 500 packets. */
    private val appleNote: ByteArray by lazy {
        val f = File("../app/src/test/voice/apple-8k-vbr-48000.voicefield").readBytes()
        val ps = ArrayList<ByteArray>(); var i = 1
        while (i < f.size) { val n = f[i].toInt() and 0xFF; ps += f.copyOfRange(i + 1, i + 1 + n); i += 1 + n }
        (Voice.forSending(ps) as Voice.Kept.Ok).frames
    }

    @Test fun aVoiceNoteOnAComputer() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, "Voices"); s.tap("Create group")
                s.await("the new group in use") { Groups.current(c)?.name == "Voices" && MeshEngine.currentCode == Groups.current(c)?.code }
                code = Groups.current(c)!!.code; val g = code!!; s.secrets += g
                s.await("its invite panel") { s.has("Invite to Voices") }; s.tap("Close")
                s.await("this computer to be its admin") { MeshEngine.isAdmin(g, MeshEngine.deviceId) }
                val ben = Ben(g); asks(ben)
                RealEngine.await("Ben's request") { MeshEngine.joinRequests(g).any { it.id == ben.id } }
                val letIn = CountDownLatch(1); MeshEngine.approveJoin(g, ben.id) { letIn.countDown() }
                assertTrue(letIn.await(30, TimeUnit.SECONDS))
                RealEngine.await("Ben to be in") { MeshEngine.inGroup(g, ben.id) }
                HighRisk.set(c, true); a.state.highRisk = true
                val cr = call(keys(g), "getGroup") as GroupCrypto

                // My message, then Ben's voice note answering it.
                MeshEngine.send("Is the bridge open?", group = g)
                RealEngine.await("my message") { MeshEngine.snapshotAll().any { it.group == g && it.text == "Is the bridge open?" } }
                val mineMsg = MeshEngine.snapshotAll().first { it.group == g && it.text == "Is the bridge open?" }
                val notePacket = benWrites(ben, cr, Voice.PLACEHOLDER, appleNote, About.Reply(mineMsg.mark!!))
                assertTrue(MeshEngine.router!!.receive("nostr", notePacket))
                RealEngine.await("Ben's voice note") { MeshEngine.snapshotAll().any { it.id == notePacket.id } }
                val note = MeshEngine.snapshotAll().first { it.id == notePacket.id }
                assertArrayEquals(appleNote, note.voice); assertTrue(Voice.playable(note)); assertEquals(About.Reply(mineMsg.mark!!), note.about)

                // The screen: the bubble a computer shows, no mic in the composer.
                a.state.tab = 1; a.state.everyone = false; s.settle()
                s.await("the voice note's bubble") { s.says("Voice note, 0:10") && s.says("Play it on a phone") }
                assertTrue("the quote of my message", s.has("Reply to You: Is the bridge open?"))
                assertFalse("a computer offers no mic", s.has("Hold to record a voice note"))
                s.png("build/screen-voice-bubble.png")
                // Holding the quote opens the menu, as holding the rest of the bubble does; a voice note has nothing to copy.
                s.hold("Reply to You: Is the bridge open?")
                s.await("the menu on Ben's voice note") { s.says("Reply") && s.says("Block Ben") }
                assertFalse("no Copy for a voice note", s.says("Copy"))
                s.png("build/screen-voice-held.png")
                s.tap("Reply"); s.await("the strip above the composer") { s.has("Replying to Ben") }
                a.state.replying = null; s.settle()
                // The tray has no "Voice note" on a computer.
                s.tap("Add something"); assertTrue(s.says("Photo")); assertFalse(s.says("Voice note"))
                s.tap("Ask if safe"); s.tap("Cancel")

                // On the Public channel a voice note is dropped, whatever the packet carries.
                val pub = (field<Any>("publicKeys").let { call(it, "getGroup") } as GroupCrypto)
                val pubPacket = benWrites(ben, pub, Voice.PLACEHOLDER, appleNote)
                MeshEngine.router!!.receive("nostr", pubPacket)
                RealEngine.await("the Public message") { MeshEngine.snapshotAll().any { it.id == pubPacket.id } }
                assertNull("no voice note on the Public channel", MeshEngine.snapshotAll().first { it.id == pubPacket.id }.voice)

                // High-risk mode: counted from its arrival like any group message; two days later it goes and stays gone.
                val kept: HashMap<String, KeepLess.Held> = field("kept"); val gone: GoneList = field("gone")
                synchronized(lock()) { assertTrue("its arrival is noted", kept[note.id]!!.at > 0) }
                synchronized(lock()) { kept[note.id] = kept[note.id]!!.let { KeepLess.Held(it.at - 2 * day, it.ts, it.group) } }
                MeshEngine.cleanUp()
                RealEngine.await("the voice note to leave") { MeshEngine.snapshotAll().none { it.id == note.id } }
                synchronized(lock()) { assertTrue("its name is on the gone list", MeshRouter.key(notePacket) in gone) }

                val left = CountDownLatch(1)
                MeshEngine.leave(c, g) { left.countDown() }
                assertTrue("the group wasn't left", left.await(30, TimeUnit.SECONDS))
                code = null
            } finally {
                HighRisk.set(c, false); a.state.highRisk = false
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }
}
