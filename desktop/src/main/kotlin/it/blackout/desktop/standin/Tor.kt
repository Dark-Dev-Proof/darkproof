package it.blackout.mesh

import android.content.Context
import it.blackout.desktop.TitleBar
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * "Through Tor" on the desktop: the desktop's version of the phones' Tor.kt, with the same names and calls, so the
 * engine's relay links (NostrLink) use it unchanged. The phones carry Tor as a library; the desktop carries the Tor
 * Project's own program and starts it beside the app, as a program of its own, while the relays need it.
 *
 * The rules are the phones' (spec/FORMAT.md, "Tor"): relay links go only through Tor's SOCKS port, and only after a
 * check through it says traffic leaves through Tor; while Tor starts, nothing goes to the relays. The port listens on
 * this computer only. Tor is told who started it and ends by itself if DarkProof is gone, also when DarkProof was
 * ended the hard way.
 */
object Tor {
    const val CHECK_URL = "https://check.torproject.org/api/ip"

    private fun prefs(c: Context) = c.getSharedPreferences("mesh", 0)
    fun enabled(c: Context) = prefs(c).getBoolean("tor", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("tor", on).apply()

    /** The Tor program in this copy of the app, or null if this copy has none (a build made without it). */
    fun program(): File? {
        val resources = System.getProperty("compose.application.resources.dir") ?: return null
        val program = File(resources, "tor/" + if (TitleBar.onWindows) "tor.exe" else "tor").takeIf { it.isFile } ?: return null
        if (!TitleBar.onWindows && !program.canExecute()) program.setExecutable(true) // packing an app can lose this mark, see Llama.program
        return program
    }

    /** The command that starts Tor, without its settings. Tests put a stand-in program here. */
    @Volatile var command: () -> List<String>? = { program()?.let { listOf(it.path) } }

    @Volatile private var process: Process? = null
    @Volatile private var port = 0
    @Volatile private var connected = false // Tor said it has a way through its network
    @Volatile private var startedAt = 0L
    private var hooked = false

    /** How far Tor says it is ("Bootstrapped 45% …"), or why it is not running; for a self-test and for looking into a problem. */
    @Volatile var said = "not started"; private set

    /** Starts Tor, if it isn't running. A Tor that keeps ending is started again at most every ten seconds. */
    @Synchronized fun start(c: Context) {
        if (process?.isAlive == true) return
        val now = System.currentTimeMillis()
        if (now - startedAt < 10_000) return
        val base = command() ?: run { said = "this copy of DarkProof has no Tor"; return }
        startedAt = now; connected = false
        try {
            val dir = File(c.applicationContext.filesDir, "tor").also { it.mkdirs() } // what Tor keeps between runs: its picture of the network, its guards
            val settings = File(dir, "torrc").also { if (!it.exists()) it.writeText("") } // none of ours: everything is said on the command line
            val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val builder = ProcessBuilder(base + listOf("-f", settings.path, "--SocksPort", "127.0.0.1:$free", "--DataDirectory", dir.path, "--ClientOnly", "1",
                "--Log", "notice stdout", "--__OwningControllerProcess", ProcessHandle.current().pid().toString()))
            File(base.first()).parentFile?.takeIf { it.isDirectory }?.let(builder::directory) // its library lies next to it
            builder.redirectErrorStream(true)
            val started = builder.start()
            process = started; port = free; said = "starting"
            if (!hooked) { hooked = true; Runtime.getRuntime().addShutdownHook(Thread { process?.destroy() }) }
            Thread({
                // Tor's notices say how far it is and nothing about what goes through it. Only the last one is kept, in memory.
                runCatching {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        val at = line.indexOf("Bootstrapped ")
                        if (at >= 0) { said = line.substring(at).take(120); if (process === started && line.startsWith("Bootstrapped 100%", at)) connected = true }
                        else if ("[err]" in line || "[warn]" in line) said = line.substringAfter("] ").take(160)
                    }
                }
                if (process === started) { connected = false; said = "ended: $said" }
            }, "tor-output").apply { isDaemon = true }.start()
        } catch (e: Exception) { said = "could not be started: $e" }
    }

    /** Stops Tor. */
    @Synchronized fun stop(c: Context) {
        val p = process ?: return
        process = null; connected = false; port = 0; startedAt = 0; said = "stopped"
        p.destroy()
        Thread({ if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }, "tor-stop").apply { isDaemon = true }.start()
    }

    /** Tor's SOCKS port once it has a way through its network, else null. */
    fun socksPort(): Int? = port.takeIf { connected && process?.isAlive == true && it > 0 }

    /** The Tor Project's answer: {"IsTor": true, "IP": …}. */
    fun isTor(json: String): Boolean = runCatching { JSONObject(json).getBoolean("IsTor") }.getOrDefault(false)

    /** Asks the Tor Project, through [client] (which goes through Tor), whether traffic leaves through Tor. Blocking. */
    fun check(client: okhttp3.OkHttpClient, label: String = ""): Boolean = runCatching {
        client.newCall(okhttp3.Request.Builder().url(CHECK_URL).build()).execute().use { r ->
            val body = r.peekBody(4096).string()
            DebugTools.log("tor check $label: ${runCatching { JSONObject(body).optString("IP") }.getOrDefault("?")}")
            r.isSuccessful && isTor(body)
        }
    }.onFailure { DebugTools.log("tor check $label failed: $it") }.getOrDefault(false)

    /** Tor's version line, to check that this copy's Tor runs on this computer at all; else why not. */
    fun version(): Pair<String?, String> = runCatching {
        val base = command() ?: return null to "this copy has no Tor"
        val program = File(base.first())
        val p = ProcessBuilder(base + "--version").redirectErrorStream(true).also { b -> program.parentFile?.takeIf { it.isDirectory }?.let(b::directory) }.start()
        val out = p.inputStream.bufferedReader().readText()
        val ended = p.waitFor(30, TimeUnit.SECONDS); if (!ended) p.destroyForcibly()
        val line = out.lines().firstOrNull { it.startsWith("Tor version") }?.trim()?.take(120)
        (line.takeIf { ended && p.exitValue() == 0 }) to "${program.name} is ${if (program.isFile) "there" else "missing"} and ${if (program.canExecute()) "may be run" else "may not be run"}; " +
            "it ${if (ended) "ended with ${p.exitValue()}" else "did not end in 30 seconds"} and said: " + out.lines().filter { it.isNotBlank() }.take(4).joinToString(" / ").take(400).ifEmpty { "nothing" }
    }.getOrElse { null to "it could not be started: $it" }
}
