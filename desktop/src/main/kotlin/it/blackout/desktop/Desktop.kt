package it.blackout.desktop

import it.blackout.mesh.AppState
import it.blackout.mesh.Groups
import it.blackout.mesh.LocalAi
import it.blackout.mesh.MainActivity
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.Notifier
import it.blackout.mesh.Wipe
import java.io.File

/** What the desktop app does where a phone's activity starts up: the same steps, without the phone-only ones. */
object Desktop {
    /**
     * Makes what the phones' screens are handed (their "activity" and its state) and starts the engine if a group is
     * saved; before the first setup the screens ask for a name and a group, as on a phone.
     *
     * [relays]: false keeps the internet relays switched off whatever was saved. That is for tests and self-tests: what
     * they write is not for the real relays.
     */
    fun activity(dir: File = Home.folder(), relays: Boolean = true): MainActivity {
        Wipe.finish(dir) // an erase that waited for its leave notes and was cut short: finished before anything opens the folder
        val a = MainActivity(dir); Home.set(a)
        relayDefault(a.getSharedPreferences("mesh", 0), relays)
        a.state = AppState(a)
        MeshEngine.listener = { Groups.current(a)?.let { if (it != a.state.group) a.state.group = it }; a.state.tick++ } // a removal changes the group's code
        Notifier.onScreen = true; Notifier.inApp = { a.state.banner = it }
        LocalAi.setUp(a)
        a.startStore()
        return a
    }

    /**
     * The internet relays are on unless the person switches them off, as on the phones, and they go through Tor (tried
     * from a desktop against the real relays on 2026-11-21). Desktop builds before that had no such switch and saved
     * "off" on their own, so "on" is written once; after that only the person's switch in Connections changes it.
     */
    internal fun relayDefault(prefs: android.content.SharedPreferences, relays: Boolean) {
        if (!relays) prefs.edit().putBoolean("internet", false).commit()
        else if (!prefs.contains("internetChoice")) prefs.edit().putBoolean("internet", true).putBoolean("internetChoice", true).commit()
    }
}
