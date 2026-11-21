package it.blackout.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Stands in for llama.cpp's server program in tests: started the same way, with the same arguments, answering at the
 * same two addresses, with canned answers. It thinks out loud before every answer, as the real models do. A model
 * file whose name starts with "broken" fails to load; a question with "slowly" in it is answered a word at a time;
 * "How were you started?" is answered with the thinking and the graphics it was started with. Started with
 * --no-graphics-here it fails the way the graphics card's engine does on a computer without a usable card.
 */
object FakeLlama {
    /**
     * The command that starts it, for [Llama.command]: this same Java, with the tests' own classes. The classes are
     * named in a file that Java reads its arguments from: on Windows a command line holds too few letters for them all.
     */
    fun command() = listOf(File(System.getProperty("java.home"), "bin/java" + if (TitleBar.onWindows) ".exe" else "").path, "@" + classes.path, "it.blackout.desktop.FakeLlama")
    /** The file that names the tests' classes to a stand-in program's Java. */
    val classes: File by lazy {
        File.createTempFile("darkproof-fake-llama", ".args").also { f ->
            f.deleteOnExit()
            f.writeText("-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n") // in such a file, \ and " are written with a \ before them
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        if ("--version" in args) { println("version: 1 (stand-in)"); return }
        fun arg(name: String) = args[args.indexOf(name) + 1]
        val model = File(arg("-m")); val key = arg("--api-key")
        // What the app must always pass: the engine stays off the network, shows no pages and no view of what is asked, and does not think first
        for (must in listOf("--offline", "--no-webui", "--no-slots", "--reasoning-budget", "-c")) if (must !in args) { System.err.println("error: started without $must"); exitProcess(2) }
        if (arg("--host") != "127.0.0.1") { System.err.println("error: not on this computer only"); exitProcess(2) }
        if (System.getenv().keys.any { it.startsWith("LLAMA_") }) { System.err.println("error: settings from outside reached the engine"); exitProcess(2) }
        if ("--no-graphics-here" in args) { System.err.println("error: no graphics card this engine can use"); exitProcess(3) }
        if (model.name.startsWith("broken")) { System.err.println("error: failed to load model '${model.name}'"); exitProcess(1) }
        val started = System.currentTimeMillis()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(arg("--host")), arg("--port").toInt()), 0)
        fun reply(x: HttpExchange, code: Int, text: String) { val b = text.toByteArray(); x.sendResponseHeaders(code, b.size.toLong()); x.responseBody.use { it.write(b) } }
        server.createContext("/health") { x -> if (System.currentTimeMillis() - started > 400) reply(x, 200, "{\"status\":\"ok\"}") else reply(x, 503, "{\"error\":\"loading\"}") }
        server.createContext("/v1/chat/completions") { x ->
            if (x.requestHeaders.getFirst("Authorization") != "Bearer $key") { reply(x, 401, "{\"error\":\"invalid api key\"}"); return@createContext }
            val body = String(x.requestBody.readAllBytes())
            val messages = JSONObject(body).getJSONArray("messages")
            val question = messages.getJSONObject(messages.length() - 1).getString("content")
            val system = messages.getJSONObject(0).getString("content")
            val answer = when {
                "dose" in question -> "Take 400 mg every six hours with food."
                "slowly" in question -> (1..60).joinToString(" ") { "word$it" }
                "forever" in question -> "It goes on and on"
                "started" in question -> "thinking ${arg("--reasoning-budget")}, graphics ${"--graphics" in args}, room ${JSONObject(body).getInt("max_tokens")}"
                else -> "You asked: $question | earlier turns: ${messages.length() - 2} | instructions: ${system.length} characters | model: ${model.name}"
            }
            x.responseHeaders.add("Content-Type", "text/event-stream"); x.sendResponseHeaders(200, 0)
            try {
                x.responseBody.use { out ->
                    fun say(piece: String) { out.write(("data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", piece)))) + "\n\n").toByteArray()); out.flush() }
                    say("<think>The user wants"); say(" an answer.</think>\n\n")
                    for (word in answer.split(" ")) { say("$word "); if ("slowly" in question) Thread.sleep(150) }
                    if ("forever" in question) out.write(("data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "length"))) + "\n\n").toByteArray())
                    out.write("data: [DONE]\n\n".toByteArray())
                }
            } catch (_: IOException) { } // the asker hung up: that is how an answer is stopped
        }
        server.executor = Executors.newCachedThreadPool(); server.start()
        Thread.currentThread().join()
    }
}
