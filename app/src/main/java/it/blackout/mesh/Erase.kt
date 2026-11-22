package it.blackout.mesh

import android.app.ActivityManager
import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.KeyStore

/**
 * "Erase everything" (Settings) on a phone. It removes all that DarkProof stored on this phone:
 * - every key the Android Keystore holds for the app (the vault's key, the member keys, the encryption keys);
 * - every preferences file (the vault with group codes, epoch secrets and message keys; names, switches, who was
 *   checked or blocked, read marks, the AI's choices);
 * - every database (messages and packets, the members' keys, the offline map's);
 * - every other file in the app's own folders, on the phone and on shared storage: AI models and downloads under way,
 *   the AI conversation, offline maps, Tor's state, caches and temporary files;
 * - its notifications, shown or waiting, and jobs; and a group code or message of its own still on the clipboard.
 *
 * It happens twice. Once at once, with the app still running, in this order ([start]): the mark is saved; the keys in
 * the Android Keystore are deleted, so that from this moment the phone holds only what nothing can open; the engine,
 * the links and the AI stop; then everything goes, the Keystore once more; then what is left is checked. Then the
 * app starts again as a new process, and before anything else runs, [finish] removes whatever a part of the old
 * process may have written in its last moment. A mark is saved before anything is deleted and removed last: it makes
 * the second pass happen even if the app was ended in the middle of the first. It is tried at three starts at most, so
 * a mark that can't be removed never erases what was set up since. If something is still there after the second pass,
 * Android is asked to clear the app's data itself; if it won't, the welcome screen says that not everything could be erased.
 *
 * What it can't reach is not the app's: what other phones hold, Android's own notification history, the phone's
 * Bluetooth pairing with a radio, and the permissions given to the app.
 */
object Wipe {
    private fun mark(c: Context) = File(c.noBackupFilesDir, "erasing")
    private const val MAX_STARTS = 3

    /** True if an erase left something behind that the app couldn't remove: the welcome screen says so (it is drawn again when this changes). */
    var notAll by mutableStateOf(false); private set

    /**
     * What the last screen of "Erase everything" says about the notes ("The notes left this phone.", never a group's
     * name), or null when there is no such screen to show. Held in memory only: the old process hands it to the new one in
     * the intent that starts it ([restart]), which Android keeps in memory and doesn't write down with its extras.
     */
    var erased by mutableStateOf<List<String>?>(null)
    const val ERASED = "it.blackout.mesh.ERASED"
    /** "Continue" on that screen. */
    fun emptySeen() { erased = null }

    /** Debug builds only (--es eraseDry 1): the erase is only shown, nothing is deleted. Release builds never set it. */
    @Volatile var dryRun = false

    /**
     * The mark, before the leave notes of "Tell my groups first" go (spec "Leaving the group", LW4): from here on the
     * erase happens whatever stops the app while it waits for them, at its next start at the latest ([finish]).
     */
    fun markFirst(c: Context) { if (!dryRun) runCatching { mark(c.applicationContext).createNewFile() } }

    /** How long an erase lets a group that is being forgotten right now ([MeshEngine.leave]) finish before it goes on regardless. */
    private const val LEAVE_MS = 5_000L

    /**
     * Deletes the keys, stops every link, removes everything, and starts the app again at its welcome screen. Main thread.
     * It always begins at once: an erase takes precedence over a leave. If a group is being forgotten right now (the last
     * step of a leave, which only deletes on this phone), the keys still go at once, and the rest follows as soon as that
     * step ends, a few seconds at most. [lines]: what the last screen says, shown once the app has started again ([erased]).
     */
    fun start(a: MainActivity, lines: List<String>? = null) {
        if (dryRun) {
            DebugTools.log("erase (dry run): nothing deleted, ${lines?.size ?: 0} line(s)"); erased = lines ?: emptyList()
            MeshEngine.untold(); Telling.dryRunOver(); return
        }
        val c = a.applicationContext
        runCatching { mark(c).createNewFile() }
        // From here on nothing in the app starts anything, sends, or writes a setting or a key (one flag, set before the
        // keys go so that nothing reaches for a key that is being deleted). And whatever was already running and now finds
        // its key gone must not end the app in the middle of the erase: such a thread just ends.
        MeshEngine.stopping()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> android.util.Log.w("DarkProof", "ended during the erase: ${t.name}: $e") }
        // The keys first. Everything stored is sealed under them, directly or through the vault (group codes' secrets,
        // message keys, the key of the readable copies): a phone taken a second from now holds only what nothing opens.
        deleteKeys()
        if (!MeshEngine.isLeaving) return stopAndRemove(a, c, lines)
        Thread({ MeshEngine.awaitLeft(LEAVE_MS); Handler(Looper.getMainLooper()).post { stopAndRemove(a, c, lines) } }, "erase-wait").start()
    }

    /** [start]'s rest, once the keys are gone. Main thread. */
    private fun stopAndRemove(a: MainActivity, c: Context, lines: List<String>?) {
        // Then nothing comes in, goes out or is written any more. The AI stops on a thread of its own while the engine and
        // the links stop here, not one after the other. Bluetooth has no stop of its own: it ends with the process, a
        // moment from now; until then the engine takes nothing from it.
        val ai = Thread({ runCatching { LocalAi.halt() } }, "erase-ai").apply { start() }
        runCatching { MeshService.halt() }
        runCatching { WifiBoard.stop() }
        runCatching { MeshEngine.halt(closeStore = false) } // its files are removed while open: a closed store would end a thread that is still writing
        // On a thread that isn't tied to the screen, and with the mesh service still in the foreground where it runs
        // ([MeshService.end]): it goes on if the app is sent to the background in this moment. If Android ends the app
        // before it is done all the same, the mark makes the next start finish it before anything else ([finish]).
        Thread({
            runCatching { ai.join(5_000) } // an AI that won't stop doesn't hold the erase up
            runCatching { everything(c) }
            Handler(Looper.getMainLooper()).post { runCatching { MeshService.end() }; restart(a, lines) }
            // The screen may be gone by now (the app was closed meanwhile): the process ends all the same.
            Thread.sleep(3_000); Runtime.getRuntime().exit(0)
        }, "erase").start()
    }

    /** Every key the Android Keystore holds for the app. */
    private fun deleteKeys() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { ks -> ks.aliases().toList().forEach { runCatching { ks.deleteEntry(it) } } } }
    }

    /** At every start of the app, before anything else: the second pass of an erase that was begun. */
    fun finish(c: Context) {
        val mark = mark(c)
        if (!runCatching { mark.exists() }.getOrDefault(false)) return
        // The mark counts the starts it was found at. Past the third it only goes: whatever is on the phone by then was set up since.
        val starts = runCatching { mark.readText().trim().toIntOrNull() ?: 0 }.getOrDefault(0)
        if (starts >= MAX_STARTS) { mark.delete(); return }
        runCatching { mark.writeText("${starts + 1}") }
        val left = everything(c)
        if (left.isNotEmpty()) {
            // Something could not be removed: Android clears the app's data itself. That ends the app; it opens empty the next time.
            runCatching { c.getSystemService(ActivityManager::class.java).clearApplicationUserData() }
            notAll = true // still here: Android didn't. The welcome screen says what is left to do.
        }
        mark.delete()
    }

    /** Removes everything the app stored. Returns what is still there afterwards: nothing, when it worked. Any thread. */
    fun everything(c: Context): List<String> {
        runCatching { c.getSystemService(NotificationManager::class.java).cancelAll() }
        runCatching { c.getSystemService(JobScheduler::class.java)?.cancelAll() }
        // Downloads under way belong to Android's downloader, which would carry on by itself: they are taken back, with their files.
        runCatching {
            val dm = c.getSystemService(DownloadManager::class.java)
            c.getSharedPreferences("ai", 0).all.forEach { (k, v) -> if (k.startsWith("download.") && v is Long) dm.remove(v) }
        }
        // The keys (again, when this is the running app's pass: something still running may have made a fresh one since).
        deleteKeys()
        // Preferences are emptied through Android before their files go: it holds them in memory and would write them back.
        prefsNames(c).forEach { n -> runCatching { c.getSharedPreferences(n, 0).edit().clear().commit(); c.deleteSharedPreferences(n) } }
        c.databaseList().forEach { runCatching { c.deleteDatabase(it) } }
        val keep = mark(c)
        for (root in roots(c)) root.listFiles()?.forEach { f -> remove(f, keep, top = root == c.dataDir) }
        Copied.forget(c) // a group code, a Plus Code or a message copied from the app, if it is still on the clipboard
        return left(c)
    }

    private fun prefsNames(c: Context): List<String> = File(c.dataDir, "shared_prefs").list()?.filter { it.endsWith(".xml") }?.map { it.removeSuffix(".xml") } ?: emptyList()

    /** The app's own folders: its private one, and the ones Android gives it on shared storage (AI models are there). */
    @Suppress("DEPRECATION")
    private fun roots(c: Context): List<File> = (listOf(c.dataDir) + c.getExternalFilesDirs(null).filterNotNull().mapNotNull { it.parentFile } +
        c.externalCacheDirs.filterNotNull() + c.obbDirs.filterNotNull() + c.externalMediaDirs.filterNotNull()).distinct()

    /** [top]: directly in the app's private folder, where "lib" is Android's link to the app's own code, not data. */
    private fun remove(f: File, keep: File, top: Boolean) {
        if (top && f.name == "lib") return
        if (f == keep) return
        if (f.isDirectory && keep.path.startsWith(f.path + File.separator)) { f.listFiles()?.forEach { remove(it, keep, false) }; return } // the mark's folder: everything but the mark
        f.deleteRecursively()
    }

    /** Files and keys of the app that are still there (the mark and Android's "lib" link don't count). */
    fun left(c: Context): List<String> {
        val keep = mark(c); val out = ArrayList<String>()
        for (root in roots(c)) root.walkTopDown().onEnter { !(it.parentFile == c.dataDir && it.name == "lib") }
            .filter { it.isFile && it != keep }.forEach { out += it.path.removePrefix(c.dataDir.path + File.separator) }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().forEach { out += "key: $it" } }
        return out
    }

    /** The app again, as a new process: nothing the old one held in memory is carried over, but the last screen's [lines]. */
    private fun restart(a: MainActivity, lines: List<String>?) {
        val i = Intent.makeRestartActivityTask(ComponentName(a, MainActivity::class.java)).apply { if (lines != null) putExtra(ERASED, lines.toTypedArray()) }
        runCatching { a.startActivity(i) }.onFailure { runCatching { a.applicationContext.startActivity(i) } } // the screen may be gone by now
        Runtime.getRuntime().exit(0)
    }
}

/** Runs before any screen, service or receiver of the app: an erase that was begun is finished first ([Wipe.finish]). */
class Startup : Application() {
    override fun onCreate() { super.onCreate(); Wipe.finish(this) }
}
