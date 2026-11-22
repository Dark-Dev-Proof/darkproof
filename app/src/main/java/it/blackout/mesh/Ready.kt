package it.blackout.mesh

import android.content.Context

/** The "Get ready" steps done so far that the app can't see otherwise (saved on the phone). */
object Ready {
    const val INVITE = "invite"; const val TILE = "tile"; const val PRACTICE = "practice"; const val HIDDEN = "hidden"
    private fun prefs(c: Context) = c.getSharedPreferences("ready", 0)
    fun done(c: Context, step: String) = prefs(c).getBoolean(step, false)
    fun mark(c: Context, step: String) { if (!done(c, step)) prefs(c).edit().putBoolean(step, true).apply() }
}
