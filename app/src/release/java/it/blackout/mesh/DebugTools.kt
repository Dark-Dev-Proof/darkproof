package it.blackout.mesh

import android.content.Context
import android.content.Intent

/** Release build: no test hooks. The real ones live in src/debug and have the same API. */
object DebugTools {
    const val ahead = 0L
    /** Finder's trial switch is off in release builds until the real-phone script passes (FinderPrefs.trial). */
    const val finderTrial = false
    /** No debug line on the Finder screen. */
    const val finderLine = false
    fun relays(c: Context): List<String>? = null
    fun log(s: String) {}
    fun noteCode(code: String?) {}
    fun debugIntent(c: Context, i: Intent) {}
}
