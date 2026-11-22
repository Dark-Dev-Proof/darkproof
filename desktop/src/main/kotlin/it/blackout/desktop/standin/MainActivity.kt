package it.blackout.mesh

import androidx.activity.ComponentActivity
import java.io.File

/**
 * What the phones' screens are handed as "the activity". On a phone it owns the screen, the permissions and the
 * phone's switches; the desktop has a window instead and none of those, so here it is the app's Context plus the few
 * calls the screens make. What asks Android for a permission or a setting does nothing: the screens' state never says
 * one is missing.
 */
class MainActivity(dir: File) : ComponentActivity(dir) {
    lateinit var state: AppState

    /** Opens the message store and the group's keys, off the window's thread. */
    fun startStore() {
        val g = state.group ?: return; val name = state.name
        Thread({ MeshEngine.start(applicationContext, name, g.code) }, "darkproof-start").start()
    }

    /** After the first setup: saves the group and starts the mesh. */
    fun started(name: String, g: SavedGroup) {
        Groups.setUp(this, name, g)
        state.name = name; state.group = g
        // The engine is already up when the last group was left and this is the next one: it only has to show it.
        if (MeshEngine.router != null) { MeshEngine.rename(name); MeshEngine.switchGroup(g.code) } else startStore()
    }

    fun switchTo(g: SavedGroup) { Groups.switchTo(this, g); state.group = g; state.tick++ }

    fun setAppLock(on: Boolean) { AppLock.set(this, on); state.lockOn = on }
    fun lockNow() { state.locked = true }
    fun justUnlocked() { state.locked = false; state.waitingSafe = false }
    fun dropWaiting() { state.waitingSafe = false }

    fun permissions() { state.introPermissions = false }
    fun askNearby() {}
    fun askLocation() {}
    fun askNotifications() {}
    fun shareLocation(on: Boolean) { state.setShareLocation(this, on) }
    fun turnOnBluetooth() {}
    fun openLocationSettings() {}
    fun addTile() {}
}
