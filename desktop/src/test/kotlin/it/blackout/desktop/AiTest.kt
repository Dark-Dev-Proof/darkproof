package it.blackout.desktop

import android.content.Context
import com.sun.net.httpserver.HttpServer
import it.blackout.mesh.AiModel
import it.blackout.mesh.LocalAi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The desktop's AI as the Ask screen uses it: the models on offer, downloading one (from a stand-in file server on
 * this computer, never from outside), and asking the stand-in engine ([FakeLlama]). No model and no engine is fetched.
 */
class AiTest {
    private val dir = Files.createTempDirectory("darkproof-ai").toFile()
    private val before = Llama.command
    private val bytes = ByteArray(600_000) { (it * 31 + it / 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val asked = CopyOnWriteArrayList<String>() // "path range" of every request
    private lateinit var server: HttpServer
    @Volatile private var cutAt = -1     // "/flaky.gguf" stops after this many bytes, once
    @Volatile private var slow = false

    @Before fun start() {
        Llama.command = { graphics -> if (graphics) null else FakeLlama.command() } // a copy with the processor's engine only, unless a test says otherwise
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { x ->
            val range = x.requestHeaders.getFirst("Range"); asked += x.requestURI.path + " " + (range ?: "whole")
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            var to = bytes.size; var cut = false
            if (x.requestURI.path == "/flaky.gguf" && cutAt >= 0) { to = cutAt; cutAt = -1; cut = true }
            // The cut answer names no length and ends early: what arrives is less than the file, as when a download drops.
            x.sendResponseHeaders(if (range != null) 206 else 200, if (cut) 0 else (bytes.size - from).toLong())
            runCatching { x.responseBody.use { out -> var i = from; while (i < to) { val n = minOf(20_000, to - i); out.write(bytes, i, n); out.flush(); i += n; if (slow) Thread.sleep(60) } } }
            x.close()
        }
        server.executor = Executors.newCachedThreadPool(); server.start()
    }
    @After fun stop() { LocalAi.reset(Context(File(dir, "end")), LocalAi.offered); server.stop(0); Llama.command = before; dir.deleteRecursively() }

    private fun model(path: String = "/tiny.gguf", sha256: String = sha) =
        AiModel(path.drop(1), "Tiny", "A stand-in.", "none", "http://127.0.0.1:${server.address.port}$path", bytes.size.toLong(), sha256, minMemoryGB = 4)
    private fun fresh(vararg models: AiModel) = Context(File(dir, "home${System.nanoTime()}")).also { LocalAi.reset(it, models.toList()) }
    private fun await(what: String, ms: Long = 15_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) fail("timed out waiting for $what; states ${LocalAi.states.toMap()}; conversation ${LocalAi.conversation.toList()}"); Thread.sleep(20) }
    }
    private fun file(c: Context, m: AiModel) = File(File(c.filesDir, "models"), m.id)

    @Test fun aModelIsDownloadedCheckedAndChosen() {
        val m = model(); val c = fresh(m)
        assertEquals(LocalAi.State.Missing, LocalAi.states[m.id]); assertNull(LocalAi.selected)
        LocalAi.download(m)
        await("the model to be ready") { LocalAi.states[m.id] == LocalAi.State.Ready }
        assertTrue(file(c, m).readBytes().contentEquals(bytes)); assertFalse(File(file(c, m).path + ".part").exists())
        await("it to be the model in use") { LocalAi.selected == m.id }
        // The next start finds it
        LocalAi.reset(c, listOf(m)); assertEquals(LocalAi.State.Ready, LocalAi.states[m.id]); assertEquals(m.id, LocalAi.selected)
    }

    @Test fun aDroppedDownloadGoesOnFromWhereItStopped() {
        val m = model("/flaky.gguf"); val c = fresh(m)
        cutAt = 200_000; LocalAi.download(m)
        await("the download to fail") { LocalAi.states[m.id] is LocalAi.State.Failed }
        assertTrue((LocalAi.states[m.id] as LocalAi.State.Failed).why, "goes on from where it stopped" in (LocalAi.states[m.id] as LocalAi.State.Failed).why)
        assertEquals(200_000L, File(file(c, m).path + ".part").length())
        LocalAi.download(m)
        await("the rest to arrive") { LocalAi.states[m.id] == LocalAi.State.Ready }
        assertEquals(listOf("/flaky.gguf whole", "/flaky.gguf bytes=200000-"), asked.toList())
        assertTrue(file(c, m).readBytes().contentEquals(bytes))
    }

    @Test fun aFileThatIsNotThePinnedOneIsThrownAway() {
        val m = model(sha256 = "0".repeat(64)); val c = fresh(m)
        LocalAi.download(m)
        await("the check to fail") { LocalAi.states[m.id] is LocalAi.State.Failed }
        assertEquals("The downloaded file was damaged. Try again.", (LocalAi.states[m.id] as LocalAi.State.Failed).why)
        assertTrue(File(c.filesDir, "models").listFiles().orEmpty().isEmpty()); assertNull(LocalAi.selected)
    }

    @Test fun aDownloadCanBeCancelledAndAModelDeleted() {
        val m = model(); val c = fresh(m)
        slow = true; LocalAi.download(m)
        await("some of it to arrive") { File(file(c, m).path + ".part").length() > 40_000 }
        LocalAi.cancel(m); assertEquals(LocalAi.State.Missing, LocalAi.states[m.id])
        await("the part to go") { !File(file(c, m).path + ".part").exists() }
        slow = false; LocalAi.download(m); await("the model") { LocalAi.states[m.id] == LocalAi.State.Ready }
        LocalAi.delete(m)
        await("the file to go") { !file(c, m).exists() && LocalAi.states[m.id] == LocalAi.State.Missing && LocalAi.selected == null }
    }

    @Test fun aQuestionIsAnsweredWithTheConversationSoFar() {
        val m = model(); val c = fresh(m)
        LocalAi.download(m); await("the model") { LocalAi.selected == m.id }
        LocalAi.ask("How do I make water safe?", LocalAi.instructions + "\n\n" + LocalAi.situationIntro)
        await("the answer") { !LocalAi.busy && LocalAi.conversation.size == 2 }
        assertEquals("user", LocalAi.conversation[0].role); assertEquals("How do I make water safe?", LocalAi.conversation[0].text)
        assertTrue(LocalAi.conversation[1].text, LocalAi.conversation[1].text.startsWith("You asked: How do I make water safe? | earlier turns: 0 |") && LocalAi.conversation[1].text.endsWith("model: tiny.gguf"))
        LocalAi.ask("And then?", LocalAi.instructions)
        await("the second answer") { !LocalAi.busy && LocalAi.conversation.size == 4 }
        assertTrue(LocalAi.conversation[3].text, "earlier turns: 2" in LocalAi.conversation[3].text)
        assertTrue("how long an answer takes here is kept", LocalAi.typicalSeconds(LocalAi.all.single()) != null)
        // The conversation is kept across a restart, and a new one empties it
        LocalAi.reset(c, listOf(m)); assertEquals(4, LocalAi.conversation.size)
        LocalAi.newConversation(); assertTrue(LocalAi.conversation.isEmpty())
    }

    @Test fun anAnswerAboutADoseGetsTheNote() {
        val m = model(); fresh(m)
        LocalAi.download(m); await("the model") { LocalAi.selected == m.id }
        LocalAi.ask("What dose of ibuprofen?", LocalAi.instructions)
        await("the answer") { !LocalAi.busy && LocalAi.conversation.size == 2 }
        assertEquals("Take 400 mg every six hours with food.\n\nCheck doses on the package. This model can get doses wrong.", LocalAi.conversation[1].text)
        assertEquals("Boil it for one minute.", LocalAi.withDoseNote("Boil it for one minute."))
        for (a in listOf("Give 5 ml.", "Take 2 tablets.", "The usual dosage is on the box.", "500 milligrams", "an overdose is dangerous")) assertTrue(a, LocalAi.withDoseNote(a).endsWith(LocalAi.DOSE_NOTE))
    }

    @Test fun withoutAModelOrWithoutAnEngineItSaysSo() {
        val m = model(); fresh(m)
        LocalAi.ask("Hello?", LocalAi.instructions)
        await("the note") { LocalAi.conversation.size == 2 }
        assertEquals(LocalAi.Turn("assistant", "No AI model on this computer yet. Download one first.", true), LocalAi.conversation[1])
        LocalAi.newConversation()
        LocalAi.download(m); await("the model") { LocalAi.selected == m.id }
        Llama.command = { _ -> null }
        LocalAi.ask("Hello?", LocalAi.instructions)
        await("the error") { !LocalAi.busy && LocalAi.conversation.size == 2 }
        assertEquals(LocalAi.Turn("assistant", "AI error: This copy of DarkProof has no AI engine.", true), LocalAi.conversation[1])
    }

    @Test fun aLongAnswerCanBeStopped() {
        val m = model(); fresh(m)
        LocalAi.download(m); await("the model") { LocalAi.selected == m.id }
        LocalAi.ask("Count slowly", LocalAi.instructions)
        await("the first words") { LocalAi.conversation.lastOrNull()?.text?.startsWith("word1 word2") == true }
        LocalAi.stop()
        await("it to end", 6_000) { !LocalAi.busy }
        val last = LocalAi.conversation.last().text
        assertTrue(last, last.endsWith("(Stopped.)") && "word60" !in last)
    }

    @Test fun thinkFirstIsThePersonsChoiceAndIsKept() {
        val m = model(); val c = fresh(m)
        LocalAi.download(m); await("the model") { LocalAi.selected == m.id }
        fun started(): String { LocalAi.newConversation(); LocalAi.ask("How were you started?", LocalAi.instructions); await("the answer") { !LocalAi.busy && LocalAi.conversation.size == 2 }; return LocalAi.conversation[1].text }
        assertFalse(LocalAi.thinkFirst); assertEquals("thinking 0, graphics false, room 2048", started())
        assertEquals(listOf("Think first: off (answers start at once)"), LocalAi.switches().map { it.label }) // no graphics engine in this copy: no such choice
        LocalAi.switches()[0].flip()
        assertTrue(LocalAi.thinkFirst); assertEquals("Think first: on (better answers, slower)", LocalAi.switches()[0].label)
        assertEquals("the engine starts anew with the next question, and the thinking gets room of its own", "thinking 768, graphics false, room 2816", started())
        LocalAi.reset(c, listOf(m)); assertTrue("kept across a restart", LocalAi.thinkFirst)
        LocalAi.chooseThinkFirst(false); assertEquals("thinking 0, graphics false, room 2048", started())
    }

    @Test fun theGraphicsCardIsThePersonsChoiceAndFallsBackByItself() {
        var cardWorks = true
        Llama.command = { graphics -> FakeLlama.command() + if (!graphics) emptyList() else listOf(if (cardWorks) "--graphics" else "--no-graphics-here") }
        val m = model(); val c = fresh(m)
        LocalAi.download(m); await("the model") { LocalAi.selected == m.id }
        fun started(): String { LocalAi.newConversation(); LocalAi.ask("How were you started?", LocalAi.instructions); await("the answer") { !LocalAi.busy && LocalAi.conversation.size == 2 }; return LocalAi.conversation[1].text }
        assertTrue(LocalAi.hasGraphicsEngine); assertFalse(LocalAi.graphics); assertFalse(LocalAi.graphicsAsked)
        assertEquals(listOf("Think first: off (answers start at once)", "Graphics card: off"), LocalAi.switches().map { it.label })
        assertEquals("thinking 0, graphics false, room 2048", started())
        LocalAi.chooseGraphics(true); assertTrue(LocalAi.graphicsAsked); assertEquals("Graphics card: on (faster answers)", LocalAi.switches()[1].label)
        assertEquals("thinking 0, graphics true, room 2048", started())
        LocalAi.reset(c, listOf(m)); assertTrue("kept across a restart", LocalAi.graphics && LocalAi.graphicsAsked)
        // A computer whose card the engine can't use: the processor answers, the choice goes back to off, and the person is told
        cardWorks = false
        assertEquals("thinking 0, graphics false, room 2048", started())
        await("the choice to go back to off") { !LocalAi.graphics }
        await("the note") { Toasts.text == "The graphics card can't be used on this computer. The processor answers instead." }
        assertEquals("thinking 0, graphics false, room 2048", started())
    }

    /** Every model on offer is one exact file: a pinned revision on Hugging Face over HTTPS, with its size and SHA-256. */
    @Test fun theModelsOnOfferArePinned() {
        val offered = LocalAi.offered
        assertEquals(listOf("Qwen 3.5 2B uncensored", "Qwen 3.5 4B uncensored", "Qwen 3.5 9B uncensored", "Gemma 4 12B uncensored", "Gemma 4 26B uncensored"), offered.map { it.title })
        assertEquals(offered.size, offered.map { it.id }.toSet().size)
        for (m in offered) {
            assertTrue(m.url, Regex("https://huggingface\\.co/[\\w.-]+/[\\w.-]+/resolve/[0-9a-f]{40}/" + Regex.escape(m.id)).matches(m.url))
            assertTrue(m.title, m.bytes > 1_000_000_000 && Regex("[0-9a-f]{64}").matches(m.sha256) && m.license.isNotBlank() && m.minMemoryGB >= 4)
        }
        assertEquals(offered.map { it.bytes }, offered.map { it.bytes }.sorted()) // smallest first
        // The dark room's own model is among them, at the dark room's own revision (where the website's files are there to compare with)
        File("../site/darkroom.js").takeIf { it.isFile }?.let { assertTrue(it.readText().contains(offered[0].url)) }
    }

    @Test fun eachModelSaysWhatItNeedsOnThisComputer() {
        fresh(*LocalAi.offered.toTypedArray())
        assertEquals(5, LocalAi.catalog.size) // all of them, whatever this computer has: the person decides
        for (m in LocalAi.catalog) {
            val need = "Needs ${m.minMemoryGB} GB of memory"
            assertTrue(m.detail, if (LocalAi.memoryGB < m.minMemoryGB) m.detail.endsWith("$need: this computer has ${LocalAi.memoryGB} GB, so it may not load.") else m.detail.endsWith("$need."))
        }
        assertEquals(listOf(8, 16, 16, 32, 4, 64), listOf(7.7, 15.8, 16.0, 31.9, 3.9, 63.6).map { LocalAi.memoryClass((it * 1_073_741_824).toLong()) })
    }

    /** The desktop's assistant is told what the phones' is told: the two texts are the same, word for word. */
    @Test fun theInstructionsAreThePhones() {
        val phone = File("../app/src/main/java/it/blackout/mesh/LocalAi.kt").readText()
        assertTrue(phone.contains(LocalAi.instructions))
        val intro = Regex("val situationIntro = \"(.*?)\" \\+\\s*\"(.*?)\"\\n", RegexOption.DOT_MATCHES_ALL).find(phone)!!.let { it.groupValues[1] + it.groupValues[2] }
        assertEquals(intro, LocalAi.situationIntro)
    }
}
