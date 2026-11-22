package it.blackout.mesh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.system.exitProcess

/**
 * "Erase everything" (Settings) on a computer: the desktop's version of the phones' Wipe (app/.../Erase.kt). It
 * removes all that DarkProof stored here: the whole data folder (the vault with group codes, keys and message keys,
 * the message store, the members' keys, preferences, AI models and the AI conversation, Tor's state) and the vault's
 * key in the computer's own key store (the Mac's Keychain, the Linux Secret Service; on Windows that key is in a file
 * of the folder, protected by the Windows account).
 *
 * In this order ([start]): the keys go first (the vault's key in the computer's key store or its file, and the member
 * keys' files), so that from that moment the folder holds only what nothing can open; then the engine, the AI engine
 * and Tor stop, so that nothing holds a file open or writes one again; then the whole folder goes, the keys once more;
 * then what is left is checked. Then the app ends, after its last screen ([erased]) if there is one: a running app can't
 * be trusted to hold nothing in memory. It starts like a new install the next time. An erase that the computer or the
 * person ends half-way is not picked up again at the next start, unlike on the phones: what the keys opened stays
 * unreadable, the rest of the folder stays. Only one that waited for its leave notes ("Tell my groups first") is: its
 * mark is saved before the notes go ([markFirst]), and the next start finishes it ([finish]).
 */
object Wipe {
    /** What happens once everything is gone. Tests put their own here. */
    @Volatile var after: () -> Unit = { exitProcess(0) }

    /** True if an erase left something behind that the app couldn't remove: the screen says so (it is drawn again when this changes), and the app stays open to say it. */
    var notAll by mutableStateOf(false); private set

    /** What the last screen of "Erase everything" says about the notes (never a group's name), in memory only; null when there is none to show. */
    var erased by mutableStateOf<List<String>?>(null); private set

    /** "Continue" on that screen: the app ends ([after]), unless something is left that the screen asks the person to remove. */
    fun emptySeen() { erased = null; if (!notAll) after() }

    private fun mark(dir: File) = File(dir, "erasing")

    /** The mark, before the leave notes go (spec "Leaving the group", LW4): an app that ends while it waits erases at its next start. */
    fun markFirst(a: MainActivity) { runCatching { mark(a.filesDir).createNewFile() } }

    /** At the start of the app, before anything opens the folder: an erase that waited for its notes and didn't get to run, finished. */
    fun finish(dir: File) { if (runCatching { mark(dir).exists() }.getOrDefault(false)) runCatching { everything(dir) } }

    /**
     * It always begins at once: an erase takes precedence over a leave. If a group is being forgotten right now (the last
     * step of a leave, which only deletes on this computer), the keys still go at once, and the engine and Tor stop as soon
     * as that step ends, a few seconds at most: the store it still writes to is closed only then. [lines]: what the last
     * screen says; without them the app ends as soon as everything is gone.
     */
    fun start(a: MainActivity, lines: List<String>? = null) {
        val dir = a.filesDir
        MeshEngine.stopping() // from here on nothing starts, sends, or writes a setting or a key
        runCatching { deleteKeys(dir) } // the keys first: what is stored can't be opened from this moment
        // The AI engine stops on a thread of its own while the engine and Tor stop here, not one after the other.
        val ai = Thread({ runCatching { LocalAi.halt() } }, "erase-ai").apply { start() }
        fun stop() {
            runCatching { MeshEngine.halt(closeStore = true) } // an open store can't be deleted on Windows
            runCatching { Tor.stop(a) }
        }
        val leaving = MeshEngine.isLeaving
        if (!leaving) stop()
        Thread({
            if (leaving) { MeshEngine.awaitLeft(LEAVE_MS); stop() }
            runCatching { ai.join(12_000) } // an AI engine that won't end doesn't hold the erase up
            runCatching { it.blackout.desktop.Opener.forgetCopied() } // a code or a message copied from the app, if it is still on the clipboard
            val left = everything(dir)
            if (left.isNotEmpty()) notAll = true
            if (lines != null) erased = lines else if (left.isEmpty()) after()
        }, "erase").start()
    }

    /** How long an erase lets a group that is being forgotten right now ([MeshEngine.leave]) finish before it goes on regardless. */
    private const val LEAVE_MS = 5_000L

    /** The vault's key (in the computer's key store, or its file) and the member keys' files: what opens everything else in [dir]. */
    private fun deleteKeys(dir: File) {
        Vault.forgetKey(dir); MemberKeys.forgetAll(dir)
        runCatching { KeyProtect.clear(dir) }
    }

    /** Removes the data folder [dir] and its key in the computer's key store. Returns the files that are still there: none, when it worked. */
    fun everything(dir: File): List<String> {
        Context.forgetPreferences(dir) // what is held in memory must not be written back
        runCatching { deleteKeys(dir) }
        // On Windows a file is free only once the program that held it has ended (Tor, the AI engine): tried for a few seconds.
        for (i in 0 until 20) { if (!dir.exists() || dir.deleteRecursively()) break; Thread.sleep(150) }
        return if (dir.exists()) dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toList() else emptyList()
    }
}
