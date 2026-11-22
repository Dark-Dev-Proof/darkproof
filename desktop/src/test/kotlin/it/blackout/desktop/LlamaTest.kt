package it.blackout.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The AI engine's side of the app, against a stand-in for the engine program ([FakeLlama]): no model, no download. */
class LlamaTest {
    private val dir = Files.createTempDirectory("darkproof-llama").toFile()
    private val before = Llama.command
    private val open = ArrayList<Llama>()
    @Before fun standIn() { Llama.command = { FakeLlama.command() + if (it) listOf("--graphics") else emptyList() } }
    @After fun tidy() { open.forEach { it.close() }; Llama.command = before; dir.deleteRecursively() }
    private fun model(name: String) = File(dir, name).also { it.writeText("not a model") }
    private fun start(name: String = "tiny.gguf") = Llama.start(model(name), 8192).also { open += it }

    @Test fun itStartsWaitsForTheModelAndAnswers() {
        val llama = start()
        val seen = ArrayList<String>()
        val answer = llama.chat("Be brief.", listOf("user" to "Hi", "assistant" to "Hello"), "How do I treat a burn?", 256) { seen += it }
        assertEquals("You asked: How do I treat a burn? | earlier turns: 2 | instructions: 9 characters | model: tiny.gguf", answer)
        assertTrue("the answer grows as it comes", seen.size > 3 && seen.last() == answer)
        assertFalse("what the model thinks is never shown", seen.any { "think" in it || "The user wants" in it })
    }

    @Test fun onlyTheKeyOpensIt() {
        val llama = start()
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        val body = okhttp3.RequestBody.create(null, "{\"messages\":[{\"role\":\"system\",\"content\":\"s\"},{\"role\":\"user\",\"content\":\"q\"}]}")
        for (key in listOf(null, "Bearer guess")) {
            val r = Request.Builder().url("http://127.0.0.1:${llama.port}/v1/chat/completions").post(body).apply { if (key != null) header("Authorization", key) }.build()
            assertEquals(401, http.newCall(r).execute().use { it.code })
        }
    }

    @Test fun aModelThatDoesNotLoadSaysWhy() {
        try { start("broken.gguf"); fail("it started") } catch (e: IOException) { assertTrue(e.message, "failed to load model" in e.message!!) }
    }

    @Test fun aCopyWithoutAnEngineSaysSo() {
        Llama.command = { _ -> null }
        try { start(); fail("it started") } catch (e: IOException) { assertEquals("This copy of DarkProof has no AI engine.", e.message) }
        assertEquals(null, Llama.version())
    }

    @Test fun anAnswerCanBeStopped() {
        val llama = start()
        var answer = ""; val t0 = System.currentTimeMillis()
        val asking = Thread { answer = llama.chat("s", emptyList(), "Count slowly", 256) {} }.also { it.start() }
        Thread.sleep(700); llama.stop(); asking.join(5000)
        assertFalse("the question still runs", asking.isAlive)
        assertTrue("it ran all 9 seconds", System.currentTimeMillis() - t0 < 5000)
        assertTrue(answer, answer.startsWith("word1 word2") && "word60" !in answer)
        assertEquals("the engine answers the next question", true, llama.chat("s", emptyList(), "Again", 64) {}.startsWith("You asked: Again"))
    }

    @Test fun anAnswerThatRanOutOfRoomSaysSo() {
        assertEquals("It goes on and on\n\n(The answer was cut off here: it got too long.)", start().chat("s", emptyList(), "Talk forever", 16) {})
    }

    @Test fun closingEndsTheProgram() {
        val llama = start(); assertTrue(llama.alive)
        val pid = llama.pid
        llama.close()
        assertFalse(llama.alive); assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun theEngineSaysItsVersion() { assertEquals("version: 1 (stand-in)", Llama.version()) }

    /** Packing the app can lose a program's execute permission (seen on the Mac build): the app gives it back, and says why when an engine doesn't run. */
    @Test fun anEngineThatLostItsExecutePermissionGetsItBack() {
        org.junit.Assume.assumeTrue("Windows has no such permission", !TitleBar.onWindows)
        val resources = File(dir, "resources"); val program = File(resources, "llama/llama-server")
        program.parentFile.mkdirs(); program.writeText("#!/bin/sh\necho 'version: 7 (a script)'\n"); program.setExecutable(false)
        val before = System.getProperty("compose.application.resources.dir"); val commandBefore = Llama.command
        System.setProperty("compose.application.resources.dir", resources.path)
        try {
            Llama.command = { graphics -> Llama.program(graphics)?.let { listOf(it.path) } } // the app's own way of finding its engine
            assertFalse(program.canExecute())
            assertEquals("version: 7 (a script)", Llama.version()); assertTrue(program.canExecute())
            assertEquals(null, Llama.version(graphics = true)); assertEquals("this copy has no such engine", Llama.whyNot(graphics = true))
            program.writeText("#!/bin/sh\necho 'dyld: Library not loaded: libllama.1.dylib' >&2\nexit 6\n")
            assertEquals(null, Llama.version())
            assertTrue(Llama.whyNot(), Llama.whyNot().let { "may be run" in it && "ended with 6" in it && "Library not loaded" in it })
        } finally { Llama.command = commandBefore; if (before == null) System.clearProperty("compose.application.resources.dir") else System.setProperty("compose.application.resources.dir", before) }
    }

    /** The engine refuses to hand its key and questions to whoever grabbed its port: it checks who really holds it. */
    @Test fun itSeesWhoHoldsThePort() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { held ->
            val pids = Llama.listenersOn(held.localPort)
            org.junit.Assume.assumeTrue("this computer can't be asked who holds a port (no lsof or PowerShell)", pids != null)
            assertTrue("the process holding the port is among the listeners seen", ProcessHandle.current().pid() in pids!!)
        }
        val free = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort } // closed again
        assertEquals(emptySet<Long>(), Llama.listenersOn(free) ?: emptySet<Long>())
    }

    @Test fun thinkingOutLoudIsCutFromWhatIsShown() {
        assertEquals("Boil it.", Llama.shown("<think>water... unsafe?</think>\n\nBoil it."))
        assertEquals("", Llama.shown("<think>still thinking"))
        assertEquals("Boil it. Then cool it.", Llama.shown("Boil it. <think>and then?</think>Then cool it."))
        assertEquals("Boil it.", Llama.shown("<|channel>thought\nplan<channel|>Boil it."))
        assertEquals("No marks here.", Llama.shown("No marks here."))
    }
}
