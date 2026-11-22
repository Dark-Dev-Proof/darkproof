package it.blackout.mesh

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Ways to reach DarkProof without opening it first: the "I'm safe" and "I need help" Quick Settings tiles, the app
 * shortcuts (long-press the icon), the answers in an "Are you safe?" notification, and the restart after a reboot.
 */
object Actions {
    const val SAFE = "it.darkproof.mesh.action.SAFE"   // send "I'm safe" (shortcut)
    const val HELP = "it.darkproof.mesh.action.HELP"   // open the "Send I need help?" question
    const val CHAT = "it.darkproof.mesh.action.CHAT"   // open the chat (of EXTRA_GROUP, if given)
    const val EXTRA_GROUP = "group"
    /** The way in for all of these. It isn't exported, so another app on the phone can't send "I'm safe" for you. */
    const val ALIAS = "it.blackout.mesh.QuickActions"

    fun intent(c: Context, action: String, group: String? = null): Intent = Intent().setClassName(c, ALIAS).setAction(action)
        .apply { if (group != null) putExtra(EXTRA_GROUP, group) }
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

/** "I'm safe" in an "Are you safe?" notification: answers in that group without opening the app. */
class AnswerReceiver : BroadcastReceiver() {
    companion object { const val NOTIFICATION = "notification" }
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        val id = i.getIntExtra(NOTIFICATION, 0)
        // Not offered while "Lock DarkProof" is on ([Notifier]); one from before it was turned on isn't sent either.
        if (AppLock.enabled(c)) {
            Toast.makeText(c, "Open DarkProof and unlock it to say you're safe.", Toast.LENGTH_LONG).show()
            pending.finish(); return
        }
        QuickCheckIn.send(c, CheckIn.SAFE, i.getStringExtra(Actions.EXTRA_GROUP)) { ok ->
            if (ok) c.getSystemService(NotificationManager::class.java).cancel(id)
            pending.finish()
        }
    }
}

/** After a restart of the phone: the mesh starts again if DarkProof is set up (and wasn't stopped by the user). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = c.getSharedPreferences("mesh", 0)
        if (Groups.code(c) == null || prefs.getBoolean("stopped", false)) return
        MeshService.start(c) // connectedDevice may start from BOOT_COMPLETED (Android 14 and 15 rules)
    }
}

/** Quick Settings tile: one tap sends "I'm safe" to the group in use. */
class SafeTile : TileService() {
    override fun onTileAdded() { Ready.mark(this, Ready.TILE) }
    override fun onStartListening() {
        Ready.mark(this, Ready.TILE)
        qsTile?.apply { state = Tile.STATE_INACTIVE; if (Build.VERSION.SDK_INT >= 29) subtitle = if (AppLock.enabled(this@SafeTile)) null else Groups.current(this@SafeTile)?.name; updateTile() }
    }
    override fun onClick() {
        // With "Lock DarkProof" on, whoever holds the phone can't tell the group you're safe without unlocking.
        if (!QuickCheckIn.ready(this) || AppLock.enabled(this)) { openApp(Intent(this, MainActivity::class.java)); return }
        QuickCheckIn.send(this, CheckIn.SAFE)
        qsTile?.apply { state = Tile.STATE_ACTIVE; if (Build.VERSION.SDK_INT >= 29) subtitle = "Sent"; updateTile() }
        Toast.makeText(this, "Sending “I'm safe” to ${Groups.current(this)?.name ?: "your group"}", Toast.LENGTH_SHORT).show()
    }
}

/** Quick Settings tile: opens DarkProof at the "Send I need help?" question, so it is never sent by mistake. */
class HelpTile : TileService() {
    override fun onStartListening() { qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() } }
    override fun onClick() { openApp(Actions.intent(this, Actions.HELP)) }
}

/** Opens DarkProof from a tile and closes the Quick Settings panel (Android 14+ wants a PendingIntent). */
@SuppressLint("StartActivityAndCollapseDeprecated")
@Suppress("DEPRECATION")
private fun TileService.openApp(i: Intent) {
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    else startActivityAndCollapse(i)
}
