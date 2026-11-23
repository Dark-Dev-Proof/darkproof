package it.blackout.mesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the screens show; [tick] changes whenever the mesh has news, so the screens read it again. */
class AppState(a: ComponentActivity) {
    private val prefs = a.getSharedPreferences("mesh", 0)
    var tick by mutableIntStateOf(0)
    var group by mutableStateOf(Groups.current(a))
    var locked by mutableStateOf(AppLock.due(a)) // "Lock DarkProof" is asking
    var lockOn by mutableStateOf(AppLock.enabled(a))
    var highRisk by mutableStateOf(HighRisk.on(a)) // "High-risk mode": locks every time, shows less, keeps less
    /** A check that just ended (Check sheet): kept here, not in the sheet, because the app may lock while the scanner is open. */
    var checkEnded by mutableStateOf<CheckEnded?>(null)
    var waitingSafe by mutableStateOf(false) // the "I'm safe" shortcut waits for the unlock (MainActivity.justUnlocked)
    var name by mutableStateOf(prefs.getString("name", "") ?: "")
    var invite by mutableStateOf<SavedGroup?>(null)          // an invite link that was opened
    var shareLocation by mutableStateOf(prefs.getBoolean("shareLocation", false))
    /** "Let my group find this phone" (Finder): "I need help" carries this phone's link id. Off in high-risk mode whatever it says. */
    var findable by mutableStateOf(FinderPrefs.findable(a))
    var permissionsMissing by mutableStateOf(false) // Bluetooth: without it phones can't connect
    var locationMissing by mutableStateOf(false)    // precise location: Android needs it to find phones before Android 13
    var bluetoothOff by mutableStateOf(false)       // the phone's Bluetooth switch
    var locationOff by mutableStateOf(false)        // the phone's Location switch
    var notificationsOff by mutableStateOf(false)
    var introPermissions by mutableStateOf(false)   // setup: the short explainer before Android asks
    var tab by mutableIntStateOf(0)
    var moderated by mutableIntStateOf(0) // changes when someone is blocked or a message reported
    var sheet by mutableStateOf<Sheet?>(null)
    var everyone by mutableStateOf(false)  // Chat shows the Everyone channel
    var placesMap by mutableStateOf(false) // Places shows the map
    /**
     * The message being answered (spec "Replies"): the group's code and the row's id, while the strip above the composer
     * shows. Cleared when the chat switches channel or group. The photo sheet reads it too: a reply can carry a photo.
     */
    var replying by mutableStateOf<Pair<String, String>?>(null)
    /** Chat drafts per channel: the group's code, or "" for Everyone. */
    val drafts = mutableStateMapOf<String, String>()
    var banner by mutableStateOf<Notifier.Alert?>(null)
    /** "Send I need help?" is asked (from Home, People, a shortcut, a tile or a notification); [helpGroup]: to which group. */
    var askHelp by mutableStateOf(false)
    var helpGroup: String? = null
    var tileHowTo by mutableStateOf(false) // how to add the tile by hand (Android 12 and older)
    fun setShareLocation(c: ComponentActivity, on: Boolean) { shareLocation = on; c.getSharedPreferences("mesh", 0).edit().putBoolean("shareLocation", on).apply() }

    /** What a new screen (after Android ended the app in the background) needs to look the same. */
    fun save(b: Bundle) {
        b.putInt("tab", tab); b.putBoolean("everyone", everyone); b.putBoolean("placesMap", placesMap)
        b.putString("sheet", sheet?.let(Sheet::save))
        b.putStringArrayList("drafts", ArrayList(drafts.flatMap { listOf(it.key, it.value) }))
        b.putBoolean("introPermissions", introPermissions)
    }
    fun restore(b: Bundle) {
        tab = b.getInt("tab"); everyone = b.getBoolean("everyone"); placesMap = b.getBoolean("placesMap")
        sheet = b.getString("sheet")?.let(Sheet::restore)
        b.getStringArrayList("drafts")?.chunked(2)?.forEach { if (it.size == 2) drafts[it[0]] = it[1] }
        introPermissions = b.getBoolean("introPermissions")
    }
}

/** Changes when a permission or a phone setting may have changed, so screens that depend on them look again. */
object Perms { var version by mutableIntStateOf(0) }

/** Changes when a person was marked as checked, so their badges and Group security look again. */
object Marks { var version by mutableIntStateOf(0) }

/** How checking person [id] ended: [how] they are now checked, or what the scanned code turned out to be ([problem]). */
class CheckEnded(val id: String, val how: CheckedHow? = null, val problem: PersonCode.Result? = null)
