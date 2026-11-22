package it.blackout.mesh

import android.content.Context
import android.widget.Toast

/** A check-in sent from outside the screen (tile, shortcut, notification): starts the mesh if needed, never waits for GPS. */
object QuickCheckIn {
    fun ready(c: Context) = Groups.code(c) != null

    /**
     * Sends [check] to [group] (the group in use if null), under its new code if a removal gave it one ([AnswerGroup]);
     * [done] runs on a background thread with false if there is no group, or if [group] isn't saved here any more:
     * then nothing is sent, not to another group, and a toast says so.
     */
    fun send(c: Context, check: CheckIn, group: String? = null, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        val prefs = app.getSharedPreferences("mesh", 0)
        val name = prefs.getString("name", null); val code = Groups.code(app)
        if (name == null || code == null) { done(false); return }
        Thread {
            MeshEngine.start(app, name, code) // does nothing if it runs already
            val to = AnswerGroup.of(group, { Groups.find(app, it) != null }, MeshEngine::movedCode) as? AnswerTo.Group
            if (to == null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(app, "Not sent: the group that asked isn't on this ${Here.device} any more. Open DarkProof to answer.", Toast.LENGTH_LONG).show()
                }
                done(false); return@Thread
            }
            val spot = if (prefs.getBoolean("shareLocation", false)) runCatching { Locator.lastKnown(app) }.getOrNull() else null
            MeshEngine.send(check.text(batteryPercent(app), spot), group = to.code)
            MeshService.start(app) // passes it on, also if the user stopped DarkProof before
            done(true)
        }.start()
    }
}
