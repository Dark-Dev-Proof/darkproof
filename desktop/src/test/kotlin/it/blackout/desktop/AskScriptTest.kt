package it.blackout.desktop

import androidx.compose.ui.input.key.Key
import com.sun.net.httpserver.HttpServer
import it.blackout.mesh.AiModel
import it.blackout.mesh.LocalAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * The Ask page used the way a person uses it, in the desktop's window: the models on offer, downloading one, asking.
 * The model is a small stand-in file from this computer and the engine is [FakeLlama]: nothing comes from outside.
 */
class AskScriptTest {
    /** All five models are on the page with what each needs, in the computer's words. Left in build/screen-ask-models.png. */
    @Test fun thePageOffersEveryModelAndSaysWhatEachNeeds() {
        RealEngine.started(); val a = RealEngine.activity
        LocalAi.reset(a, LocalAi.offered)
        Screen { App(a) }.use { s ->
            try {
                a.state.tab = 4; a.state.sheet = null; s.settle()
                assertTrue(s.has("No AI model on this computer") && s.has("It stays on the computer and works offline afterwards."))
                assertTrue(s.has("Models run entirely on this computer.") && s.has("This computer has ${LocalAi.memoryGB} GB of memory. Each model says what it needs."))
                assertTrue(s.has("They are uncensored versions that other people made: they answer anything, and they can be wrong."))
                s.png("build/screen-ask-models.png")
                // The list is longer than the window: it is read while turning the mouse wheel, as a person reads it
                val read = LinkedHashSet<String>()
                repeat(40) { // a few notches at a time, so nothing passes unread
                    read += s.texts()
                    s.wheel("Big: use Wi-Fi if you can", 6) // every model's card has this line, and one of them is always in view
                }
                read += s.texts()
                for (m in LocalAi.catalog) { assertTrue(m.title, m.title in read); assertTrue(m.title, read.any { "Needs ${m.minMemoryGB} GB of memory" in it }) }
                for (button in listOf("Download 1.3 GB", "Download 2.7 GB", "Download 5.6 GB", "Download 7.4 GB", "Download 16.8 GB")) assertTrue(button, button in read)
                for (phoneOnly in listOf("this phone", "only models that fit are shown", "The AI isn't on the desktop yet")) assertFalse(phoneOnly, read.any { phoneOnly in it })
                s.png("build/screen-ask-models-2.png")
            } finally { a.state.tab = 1 }
        }
    }

    @Test fun aPersonDownloadsAModelAndAsks() {
        RealEngine.started(); val a = RealEngine.activity
        val bytes = ByteArray(300_000) { (it * 17).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { x -> x.sendResponseHeaders(200, bytes.size.toLong()); x.responseBody.use { it.write(bytes) } }
        server.start()
        val engineBefore = Llama.command
        Llama.command = { FakeLlama.command() + if (it) listOf("--graphics") else emptyList() }
        LocalAi.reset(a, listOf(AiModel("tiny.gguf", "Tiny", "A stand-in for the tests.", "no licence", "http://127.0.0.1:${server.address.port}/tiny.gguf", bytes.size.toLong(), sha, minMemoryGB = 4)))
        Screen { App(a) }.use { s ->
            try {
                a.state.tab = 4; a.state.sheet = null; s.settle()
                assertTrue(s.has("No AI model on this computer") && s.says("Tiny"))
                s.tap("Download 0.0 GB")
                s.await("the model to be in use: the page turns into a conversation") { s.has("How do I treat a burn?") && s.has("Ask anything") }
                // A question typed and sent with Enter; the answer names a dose, so it gets the note
                s.type("Ask anything", "What dose of ibuprofen?"); s.press(Key.Enter)
                try {
                    s.await("the answer with its note", 60_000) { s.has("Take 400 mg every six hours with food.") && s.has("Check doses on the package. This model can get doses wrong.") }
                } catch (e: AssertionError) { throw AssertionError(e.message + "\n" + whereTheAiIs(), e) }
                assertTrue(s.has("What dose of ibuprofen?")); assertFalse("what the model thinks is not shown", s.has("The user wants"))
                assertEquals(2, LocalAi.conversation.size)
                s.png("build/screen-ask-answer.png")
                // The menu's two choices. This copy has an engine for the graphics card, so the person was asked once, above the page
                assertTrue(s.has("Use the graphics card for the AI?")); assertFalse(LocalAi.graphics)
                s.tap("Use the graphics card"); assertTrue(LocalAi.graphics && LocalAi.graphicsAsked); assertFalse(s.has("Use the graphics card for the AI?"))
                s.tap("More: new conversation, AI model")
                assertTrue(s.says("Think first: off (answers start at once)") && s.says("Graphics card: on (faster answers)"))
                s.tap("Think first: off (answers start at once)"); assertTrue(LocalAi.thinkFirst)
                s.type("Ask anything", "How were you started?"); s.press(Key.Enter)
                s.await("the answer from an engine started the new way") { s.has("thinking 768, graphics true, room 2816") }
                s.tap("More: new conversation, AI model"); s.tap("Graphics card: on (faster answers)"); s.tap("More: new conversation, AI model"); s.tap("Think first: on (better answers, slower)")
                assertFalse(LocalAi.graphics || LocalAi.thinkFirst)
                // The model's page, from the menu: in use, and it can be deleted
                s.tap("More: new conversation, AI model"); s.tap("AI model: Tiny")
                s.await("the model's page") { s.has("In use") && s.says("Delete") }
                s.tap("Close")
                // A new conversation empties the page
                s.tap("More: new conversation, AI model"); s.tap("New conversation")
                s.await("the empty conversation") { LocalAi.conversation.isEmpty() && s.has("How do I treat a burn?") }
            } finally {
                a.state.tab = 1; a.state.sheet = null
                LocalAi.newConversation(); LocalAi.reset(a, LocalAi.offered); Llama.command = engineBefore; server.stop(0)
            }
        }
    }

    /** For a wait that ran out on a machine nobody can look at: what the AI holds, and what its threads are doing. */
    private fun whereTheAiIs(): String = buildString {
        appendLine("the AI: busy ${LocalAi.busy}, model in use ${LocalAi.selected}, states ${LocalAi.states.toMap()}, conversation ${LocalAi.conversation.toList()}")
        appendLine("engine programs alive: " + ProcessHandle.current().descendants().map { it.pid().toString() + " " + it.info().command().orElse("?").takeLast(30) }.toList())
        for ((thread, stack) in Thread.getAllStackTraces()) if (thread.name in setOf("ai-answers", "darkproof-main", "ai-engine-output", "ai-model-download"))
            appendLine("thread ${thread.name} (${thread.state}): " + stack.take(14).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber })
    }
}
