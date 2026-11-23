package it.blackout.desktop

import it.blackout.mesh.About
import it.blackout.mesh.Delivery
import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.Groups
import it.blackout.mesh.HighRisk
import it.blackout.mesh.Journey
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.MeshRouter
import it.blackout.mesh.Message
import it.blackout.mesh.MlKem
import it.blackout.mesh.P256
import it.blackout.mesh.Packet
import it.blackout.mesh.Reactions
import it.blackout.mesh.RecentIds
import it.blackout.mesh.quoteOf
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
 * Replies, reactions and "How it got there" on the real engine (the phones' own, MeshEngine) and the phones' own chat
 * screens, in a group made here and left at the end. The rules themselves are tested as plain Kotlin (AboutTest,
 * JourneyTest, RepliesVectorsTest); this checks that the engine and the screens are wired to them: a reaction is never a
 * row, an alert or a receipt and is kept off LoRa by its packet, a reply is quoted from this computer's own copy, a
 * receipt's journey note reaches "How it got there", and high-risk mode writes none. Another member ("Ben") is let in
 * the way a person lets someone in, and writes the way his phone would: under the epoch's key, signed with his own.
 * Pictures along the way are left in build/screen-replies-*.png.
 */
class RepliesEngineTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MeshEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(MeshEngine) as T
    private fun lock(): Any = field("messages")

    private class Ben(val code: String) {
        val pair = P256.newKeyPair(); val pub = P256.compress(pair.public); val id = P256.memberId(pub)
        fun sign(m: ByteArray) = P256.sign(pair.private, m)
    }

    /** Ben asks to join, as his phone would: an introduction through the door, with an ML-KEM key. */
    private fun asks(b: Ben) {
        val cr = GroupCrypto(b.code).intros(); val id = Packet.newId(cr.mailbox); val now = System.currentTimeMillis()
        MeshEngine.router!!.receive("test", Packet(id, now, 16, cr.seal(id, now, b.id, "Ben", "",
            extra = byteArrayOf(3) + b.pub + P256.uncompressed(P256.newKeyPair().public) + MlKem.newKey().first) { b.sign(it) }))
    }

    private fun keys(code: String): Any = field<HashMap<String, Any>>("keyCache")[code]!!
    private fun call(k: Any, name: String): Any? = k.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(k)

    /** A packet Ben's phone writes in [code]'s group: under the epoch's key ([about]: a reply or a reaction), arriving with [ttl] hops left. */
    private fun benWrites(b: Ben, text: String, about: About? = null, ttl: Int = 16): Packet {
        val cr = call(keys(b.code), "getGroup") as GroupCrypto
        val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
        val c = cr.seal(id, ts, b.id, "Ben", text, about = about) { b.sign(it) }
        return Packet(id, cr.hideTime(ts, c), ttl, c)
    }

    private fun benReceipt(b: Ben, text: String): Packet {
        val rc = call(keys(b.code), "getReceipts") as GroupCrypto
        val id = Packet.newId(rc.mailbox); val ts = System.currentTimeMillis()
        val c = rc.seal(id, ts, b.id, "Ben", text) { b.sign(it) }
        return Packet(id, rc.hideTime(ts, c), 16, c)
    }

    private fun row(code: String, text: String) = MeshEngine.snapshotAll().first { it.group == code && it.text == text }
    private fun receiptIds(): RecentIds = field("receiptIds")

    @Test fun repliesReactionsAndTheJourneyWorkOnTheRealEngine() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        val taken = CopyOnWriteArrayList<Pair<String, Packet>>()
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, "Talk"); s.tap("Create group")
                s.await("the new group in use") { Groups.current(c)?.name == "Talk" && MeshEngine.currentCode == Groups.current(c)?.code }
                code = Groups.current(c)!!.code; val g = code!!; s.secrets += g
                s.await("its invite panel") { s.has("Invite to Talk") }; s.tap("Close")
                s.await("this computer to be its admin") { MeshEngine.isAdmin(g, MeshEngine.deviceId) }
                val ben = Ben(g)
                asks(ben)
                RealEngine.await("Ben's request") { MeshEngine.joinRequests(g).any { it.id == ben.id } }
                val letIn = CountDownLatch(1); MeshEngine.approveJoin(g, ben.id) { letIn.countDown() }
                assertTrue(letIn.await(30, TimeUnit.SECONDS))
                RealEngine.await("Ben to be in") { MeshEngine.inGroup(g, ben.id) }

                // A link that takes every packet: the radio's rule is checked on what this computer hands out.
                MeshEngine.sender = { peer, p -> taken += peer to p }
                MeshEngine.router!!.peerUp("ble:test")

                // My message, then my reactions to it: chips at once, never a row, no "Sent" or "Delivered".
                MeshEngine.send("Water is back at the school.\nBring bottles.", group = g)
                RealEngine.await("my message to be shown") { MeshEngine.snapshotAll().any { it.group == g && it.text.startsWith("Water is back") } }
                val water = row(g, "Water is back at the school.\nBring bottles.")
                assertNotNull("my own message is named by what it signed", water.mark)
                assertTrue(MeshEngine.canAnswer(water))
                RealEngine.await("it to say Sent, over Bluetooth") { MeshEngine.delivery(water) == Delivery.Sent }
                assertEquals("Over Bluetooth", MeshEngine.journey(water)!!.steps.first().detail)
                val rows = MeshEngine.snapshotAll().size
                MeshEngine.react(water, "👍")
                assertEquals(listOf(Reactions.Chip("👍", 1, true)), MeshEngine.chips(water))
                assertEquals("a reaction is no row", rows, MeshEngine.snapshotAll().size)
                val reaction = taken.last { it.first == "ble:test" }.second
                assertTrue("my reaction goes over Bluetooth, like everything", reaction.id != water.id)
                assertTrue("kept off LoRa by its packet", MeshRouter.key(reaction) in receiptIds())
                val waterPacket = taken.first { it.second.id == water.id }.second
                assertFalse("my message is not kept off LoRa", MeshRouter.key(waterPacket) in receiptIds())
                MeshEngine.react(water, "👍") // the same again takes it back
                assertEquals(emptyList<Reactions.Chip>(), MeshEngine.chips(water))
                MeshEngine.react(water, "❤️")
                MeshEngine.react(water, "✅") // not listed: nothing is sent
                assertEquals(listOf(Reactions.Chip("❤️", 1, true)), MeshEngine.chips(water))
                assertEquals(rows, MeshEngine.snapshotAll().size)

                // Ben reacts: counted, one per member, but no row, no alert, no receipt.
                val pending: ArrayList<*> = field("pendingAcks")
                val alerts = CopyOnWriteArrayList<Message>(); MeshEngine.onIncoming = { alerts += it }
                assertTrue(MeshEngine.router!!.receive("nostr", benWrites(ben, "👍", About.Reaction(water.mark!!))))
                RealEngine.await("Ben's reaction to count") { MeshEngine.chips(water) == listOf(Reactions.Chip("👍", 1, false), Reactions.Chip("❤️", 1, true)) }
                assertEquals(rows, MeshEngine.snapshotAll().size)
                Thread.sleep(300)
                assertTrue("no alert for a reaction", alerts.isEmpty()); assertTrue("no receipt for a reaction", pending.isEmpty())
                // Ben replies: an ordinary message, alerted and confirmed, quoted from this computer's own copy.
                assertTrue(MeshEngine.router!!.receive("nostr", benWrites(ben, "On my way. Does anyone need some?", About.Reply(water.mark!!), ttl = 13)))
                RealEngine.await("Ben's reply") { MeshEngine.snapshotAll().any { it.group == g && it.text == "On my way. Does anyone need some?" } }
                val reply = row(g, "On my way. Does anyone need some?")
                assertEquals(About.Reply(water.mark!!), reply.about)
                val shown = About.targets(MeshEngine.snapshot(), g)
                assertEquals("You", quoteOf(reply, shown, shown, MeshEngine.deviceId)?.name)
                assertEquals("Water is back at the school.", quoteOf(reply, shown, shown, MeshEngine.deviceId)?.text)
                RealEngine.await("the reply to be alerted and queued for a receipt") { alerts.any { it.id == reply.id } && pending.isNotEmpty() }
                // Its receipt says how it came: 3 hops, over the internet. In high-risk mode it says nothing of that.
                fun flushedReceipt(): String {
                    val handler = field<Any>("main")
                    val before = taken.size
                    val flush = MeshEngine::class.java.getDeclaredMethod("flushAcks").apply { isAccessible = true }
                    handler.javaClass.getMethod("post", Runnable::class.java).invoke(handler, Runnable { flush.invoke(MeshEngine) })
                    val rc = call(keys(g), "getReceipts") as GroupCrypto
                    var text: String? = null
                    RealEngine.await("a receipt to go out") { text = taken.drop(before).firstNotNullOfOrNull { rc.openSigned(it.second)?.message?.text }; text != null }
                    return text!!
                }
                assertEquals("${StoreName(reply.id)} h3i", flushedReceipt())
                HighRisk.set(c, true)
                assertTrue(MeshEngine.router!!.receive("nostr", benWrites(ben, "Second one", ttl = 13)))
                RealEngine.await("the second to be queued") { pending.isNotEmpty() }
                assertEquals("high-risk mode writes no journey note", row(g, "Second one").id, flushedReceipt())
                HighRisk.set(c, false)

                // Ben's receipt with a journey note: "How it got there" says how my message reached him.
                assertTrue(MeshEngine.router!!.receive("test", benReceipt(ben, "${water.id} h3i")))
                RealEngine.await("Ben's receipt to count") { MeshEngine.delivery(water) is Delivery.Delivered }
                val j = MeshEngine.journey(water)!!
                assertEquals(listOf("Left your phone", "Reached Ben"), j.steps.map { it.title })
                assertEquals(listOf("Over Bluetooth", "Over the internet · about 3 hops"), j.steps.map { it.detail })
                assertTrue("3 hops: other phones carried it", j.carried)
                // A receipt that names my reaction confirms nothing.
                assertTrue(MeshEngine.router!!.receive("test", benReceipt(ben, reaction.id)))
                Thread.sleep(300)
                assertNull("a reaction is no message of mine", MeshEngine.snapshotAll().firstOrNull { it.id == reaction.id })

                // Someone who isn't in the group: their reaction doesn't count, whatever it names.
                val stranger = Ben(g)
                MeshEngine.router!!.receive("test", benWrites(stranger, "😂", About.Reaction(water.mark!!)))
                Thread.sleep(300)
                assertEquals(listOf(Reactions.Chip("👍", 1, false), Reactions.Chip("❤️", 1, true)), MeshEngine.chips(water))

                // After a restart (the store read again): the same chips, from the packets alone.
                val reactionsField = MeshEngine::class.java.getDeclaredField("reactions").apply { isAccessible = true }
                synchronized(lock()) { reactionsField.set(MeshEngine, Reactions()) }
                val scan = MeshEngine::class.java.getDeclaredMethod("scan").apply { isAccessible = true }
                val loaded = scan.invoke(MeshEngine)
                val apply = MeshEngine::class.java.declaredMethods.first { it.name == "apply" && it.parameterTypes.size == 1 && it.parameterTypes[0] == loaded.javaClass }.apply { isAccessible = true }
                synchronized(lock()) { apply.invoke(MeshEngine, loaded) }
                assertEquals("read back from the store", listOf(Reactions.Chip("👍", 1, false), Reactions.Chip("❤️", 1, true)), MeshEngine.chips(water))

                // The screens: hold my message, react, reply.
                a.state.tab = 1; a.state.everyone = false; s.settle()
                s.await("the chat with the chips") { s.has("Water is back at the school.") && s.has("On my way. Does anyone need some?") }
                s.png("build/screen-replies-chat.png")
                s.hold("Bring bottles.")
                s.await("the menu on the held message") { s.says("Reply") && s.says("Copy") && s.says("How it got there") && s.has("React with 👍") }
                assertFalse("my own group message has no Block or Hide", s.has("Hide this message") || s.has("Block "))
                s.png("build/screen-replies-held.png")
                s.tap("React with 😮")
                s.await("the chip, mine") { MeshEngine.chips(water).any { it.text == "😮" && it.mine } }
                assertFalse("the menu has closed", s.says("How it got there"))
                // The right button opens the same menu on a computer.
                s.rightClick("On my way. Does anyone need some?")
                s.await("the menu on Ben's message") { s.says("Reply") && s.says("Block Ben") }
                assertFalse("only my own messages tell how they got there", s.says("How it got there"))
                s.tap("Reply")
                s.await("the strip above the composer") { s.has("Replying to Ben") }
                s.png("build/screen-replies-strip.png")
                s.type("Message Talk", "I have two bottles")
                s.press(androidx.compose.ui.input.key.Key.Enter)
                s.await("my reply") { MeshEngine.snapshotAll().any { it.group == g && it.text == "I have two bottles" } }
                assertEquals(About.Reply(reply.mark!!), row(g, "I have two bottles").about)
                s.await("the strip gone and the quote shown") { !s.has("Replying to Ben") && s.has("Reply to Ben: On my way. Does anyone need some?") }
                // The keyboard (Fable review, Android finding 3): Enter on something inside a message presses that thing, here
                // the quote, which shows the message it answers; only Enter on the message itself opens its menu.
                s.focus("Reply to Ben: On my way. Does anyone need some?")
                assertTrue("the quote has the focus: ${s.focused()}", s.focused().any { "Reply to Ben" in it })
                s.press(androidx.compose.ui.input.key.Key.Enter); s.settle()
                assertFalse("Enter on the quote doesn't open the message's menu", s.says("Copy") || s.says("How it got there"))
                s.focus("I have two bottles"); s.press(androidx.compose.ui.input.key.Key.Enter)
                s.await("the menu, opened from the keyboard") { s.says("Reply") && s.says("Copy") && s.says("How it got there") }
                s.tapAt(androidx.compose.ui.geometry.Offset(6f, 6f)) // the dimmed window around the menu closes it
                s.await("the menu closed") { !s.says("How it got there") }
                // "How it got there" from the line under my message.
                s.hold("Bring bottles."); s.tap("How it got there")
                s.await("the journey") { s.has("Reached Ben") && s.has("Over the internet · about 3 hops") && s.has(Journey.SEALED) }
                s.png("build/screen-replies-journey.png")
                a.state.sheet = null; s.settle()

                // Leaving takes the reactions with the group.
                val left = CountDownLatch(1)
                MeshEngine.leave(c, g) { left.countDown() }
                assertTrue("the group wasn't left", left.await(30, TimeUnit.SECONDS))
                synchronized(lock()) { assertEquals(0, (reactionsField.get(MeshEngine) as Reactions).count) }
                code = null
            } finally {
                MeshEngine.onIncoming = null
                MeshEngine.sender = null; runCatching { MeshEngine.router?.peerDown("ble:test") }
                HighRisk.set(c, false)
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }

    /** A receipt names a message by its packet id: the row's name without the key some rows carry after it. */
    @Suppress("FunctionName") private fun StoreName(id: String) = id.take(16)
}
