package it.blackout.desktop

import android.content.Context
import java.io.File

/**
 * Where DarkProof keeps its data on this computer: preferences, the message store, keys. One folder in the user's own
 * profile, which only that account can read where the system has such permissions.
 */
object Home {
    @Volatile private var context: Context? = null

    /** The folder: DARKPROOF_HOME if set, else the system's usual place for an app's data. Made on first use. */
    fun folder(): File {
        val os = System.getProperty("os.name").lowercase(); val user = System.getProperty("user.home")
        val dir = System.getenv("DARKPROOF_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: when {
            // Local, not Roaming: keys and messages must not sync to a company server with a roaming Windows profile.
            // An install that already has a Roaming folder keeps it, so nobody loses their groups on an update.
            "win" in os -> {
                val roaming = File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$user\\AppData\\Roaming", "DarkProof")
                if (roaming.isDirectory) roaming else File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$user\\AppData\\Local", "DarkProof")
            }
            "mac" in os -> File(user, "Library/Application Support/DarkProof")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$user/.local/share", "darkproof")
        }
        dir.mkdirs()
        runCatching { java.nio.file.Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) } // not on Windows: the profile's own permissions
        return dir
    }

    /** What the phones' code gets where it has no Context of its own to ask (the member keys). */
    fun context(): Context = context ?: synchronized(this) { context ?: Context(folder()).also { context = it } }

    /** The app's one Context, once it is made. */
    fun set(c: Context) { context = c }

    /** The Context in use, if one was made or set: asking this never makes the data folder. */
    fun current(): Context? = context

    /** Tests keep their data somewhere else. */
    fun use(dir: File) = set(Context(dir))
}
