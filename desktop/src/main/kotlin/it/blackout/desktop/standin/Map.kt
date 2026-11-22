package it.blackout.mesh

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// The phones' street map (app/.../OfflineMap.kt), as the screens see it. There is no map in the desktop app yet:
// where the phones draw one, the desktop says so, and the list of places works as on the phones.

object OfflineMap {
    sealed class State {
        object None : State()
        data class Downloading(val progress: Float) : State()
        data class Ready(val bytes: Long, val center: Spot, val ts: Long) : State()
        data class Failed(val why: String) : State()
    }
    var state by mutableStateOf<State>(State.None); private set
    fun torChanged(on: Boolean) {}
}

data class Pin(val title: String, val spot: Spot, val ts: Long, val color: String, val icon: Int? = null, val help: Boolean = false)

@Composable
fun StreetMap(pins: List<Pin>, me: Spot?, modifier: Modifier, open: (Sheet) -> Unit, onCenter: (Spot?) -> Unit = {}) {
    Card(modifier.padding(horizontal = 20.dp)) {
        Txt("The map isn't on the desktop yet", 17)
        Txt("Places are in the list.", 15, color = Theme.dim)
    }
}

@Composable
fun OfflineMapCard(me: Spot?, center: Spot? = null) {}
