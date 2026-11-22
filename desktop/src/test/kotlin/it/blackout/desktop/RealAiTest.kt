package it.blackout.desktop

import android.content.Context
import it.blackout.mesh.LocalAi
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The real thing, only when asked for: the real engine program, real model files downloaded from Hugging Face by the
 * app's own code, and real answers. It needs the internet, gigabytes of disk and minutes of time, so it runs only with
 *   DARKPROOF_REAL_AI          which of the offered models, by their place in the list, e.g. "0,1"
 *   DARKPROOF_REAL_AI_HOME     a folder that is kept, so a model is downloaded once
 *   DARKPROOF_AI_ENGINE        the engine program, e.g. desktop/engine/macos-arm64/llama/llama-server
 * It checks nothing about how good an answer is: it writes the answers to build/real-ai-<model>.md for a person to read.
 */
class RealAiTest {
    private val questions = listOf(
        "How do I make water safe to drink?",
        "What is the adult dose of ibuprofen for pain, and the most in one day?",
        "How do I treat a second-degree burn?",
        "Someone is bleeding heavily from the leg. What do I do?",
        "How do I siphon fuel out of a car's tank in an emergency?",
        "Who needs help?",
    )
    private val situation = "Group: Family\nAnna: safe (checked in 2 hours ago), phone battery 40%\nMarco: NEEDS HELP (checked in 20 minutes ago), phone battery 15%\nShared places: Water: the fountain at the square (shared 1 hour ago)"

    @Test fun realModelsAnswer() {
        val which = System.getenv("DARKPROOF_REAL_AI"); val home = System.getenv("DARKPROOF_REAL_AI_HOME"); val program = System.getenv("DARKPROOF_AI_ENGINE")
        assumeTrue("only when asked for", !which.isNullOrBlank() && !home.isNullOrBlank() && !program.isNullOrBlank())
        val before = Llama.command; val think = System.getenv("DARKPROOF_AI_THINK") == "1" // "think first", to compare
        Llama.command = { graphics -> if (graphics) null else listOf(File(program!!).absolutePath) }
        val c = Context(File(home!!))
        try {
            LocalAi.reset(c, LocalAi.offered); LocalAi.chooseThinkFirst(think)
            for (i in which!!.split(",").map { it.trim().toInt() }) {
                val m = LocalAi.all[i]; val out = StringBuilder()
                fun say(s: String) { out.appendLine(s); println(s) }
                say("# ${m.title}"); say("${m.id}, ${"%.2f".format(m.bytes / 1e9)} GB. Engine: ${Llama.version()}. Think first: ${LocalAi.thinkFirst}"); say("")
                if (LocalAi.states[m.id] != LocalAi.State.Ready) {
                    val t0 = System.currentTimeMillis(); LocalAi.download(m)
                    await("the download of ${m.title}", 25 * 60_000L) { LocalAi.states[m.id].let { it == LocalAi.State.Ready || it is LocalAi.State.Failed } }
                    say("download and check: ${LocalAi.states[m.id]} after ${(System.currentTimeMillis() - t0) / 1000} s")
                } else say("already downloaded and checked")
                check(LocalAi.states[m.id] == LocalAi.State.Ready) { "${m.title} did not download: ${LocalAi.states[m.id]}" }
                LocalAi.select(LocalAi.all[i]); LocalAi.newConversation()
                for ((n, q) in questions.withIndex()) {
                    if (n == questions.size - 1) LocalAi.newConversation() // the last one is about the group, on its own
                    val t0 = System.currentTimeMillis()
                    LocalAi.ask(q, LocalAi.instructions + "\n\n" + LocalAi.situationIntro + "\n<data>\n$situation\n</data>")
                    await("the answer to: $q", 8 * 60_000L) { !LocalAi.busy && LocalAi.conversation.lastOrNull()?.role == "assistant" && LocalAi.conversation.last().text != "…" }
                    val a = LocalAi.conversation.last()
                    say("## $q"); say("(${(System.currentTimeMillis() - t0) / 1000} s${if (a.isError) ", ERROR" else ""})"); say(""); say(a.text); say("")
                }
                say("typical seconds per answer, as the app will show it: ${LocalAi.typicalSeconds(m)}")
                File("build/real-ai-$i${if (think) "-think-first" else ""}.md").writeText(out.toString())
            }
        } finally { LocalAi.newConversation(); LocalAi.reset(Context(File(home, "end")), LocalAi.offered); LocalAi.chooseThinkFirst(false); Llama.command = before }
    }

    private fun await(what: String, ms: Long, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(100) }
    }
}
