package it.blackout.mesh

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Home: one dial for each way messages travel. What a dial says comes from what the engine already knows; the rules
// are plain Kotlin so unit tests run them (the iPhone shows the same words).

/** A dial: [on] it is switched on (white), [live] it has at least one link right now (green dot), and the one word under its name. */
data class Dial(val on: Boolean, val live: Boolean, val word: String)

object Dials {
    private fun phones(n: Int) = if (n == 1) "1 phone" else "$n phones"

    /** [allowed]: the app may use Bluetooth. [switchedOn]: the phone's Bluetooth switch. [linked]: phones linked over it now. */
    fun bluetooth(allowed: Boolean, switchedOn: Boolean, linked: Int) = when {
        !allowed -> Dial(false, false, "Not allowed")
        !switchedOn -> Dial(false, false, "Off")
        linked > 0 -> Dial(true, true, phones(linked))
        else -> Dial(true, false, "Looking")
    }

    /** [on]: the app's own Wi-Fi switch. [linked]: every link over a Wi-Fi network. */
    fun wifi(on: Boolean, linked: Int) = when {
        !on -> Dial(false, false, "Off")
        linked > 0 -> Dial(true, true, phones(linked))
        else -> Dial(true, false, "Looking")
    }

    /** A LoRa radio, Meshtastic or MeshCore: connected, only paired, or none. */
    fun radio(paired: Boolean, connected: Boolean) = when {
        connected -> Dial(true, true, "Linked")
        paired -> Dial(true, false, "Not in reach")
        else -> Dial(false, false, "Not paired")
    }

    /** [on]: the app's internet switch. [relays]: relays connected now. [throughTor]: those links go through Tor. */
    fun internet(on: Boolean, relays: Int, throughTor: Boolean) = when {
        !on -> Dial(false, false, "Off")
        relays > 0 && throughTor -> Dial(true, true, "Through Tor")
        relays > 0 -> Dial(true, true, "Online")
        else -> Dial(true, false, "Connecting")
    }
}

/**
 * The four dials in Home's hero card. Bluetooth and Radio open Connections ([connections]); Wi-Fi and Internet flip the
 * app's own switches: on at once, off only after asking, because a dial is easily touched by accident. A way this
 * device doesn't have gets no dial.
 */
@Composable
fun LinkDials(s: AppState, connections: () -> Unit) {
    s.tick // drawn again with every change of a link
    var askOff by remember { mutableStateOf(false) }
    var askWifiOff by remember { mutableStateOf(false) }
    val n = MeshEngine.nostr; val wifi = MeshEngine.wifi
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (Here.bluetooth) DialButton(R.drawable.ic_bluetooth, "Bluetooth", Dials.bluetooth(!s.permissionsMissing, !s.bluetoothOff, MeshEngine.bluetoothNodes().size),
            "Opens Connections", Modifier.weight(1f), connections)
        val wOn = wifi != null
        DialButton(R.drawable.ic_wifi, "Wi-Fi", Dials.wifi(wOn, wifi?.links?.linkCount() ?: 0), if (wOn) "Asks before switching Wi-Fi links off" else "Switches Wi-Fi links on", Modifier.weight(1f)) {
            if (!wOn) { Buzz.play(Buzz.On); MeshEngine.setWifi(true); s.tick++ } else askWifiOff = true
        }
        if (Here.radios) DialButton(R.drawable.ic_settings_input_antenna, "Radio",
            Dials.radio(MeshEngine.lora?.paired == true || MeshEngine.meshcore?.paired == true, MeshEngine.lora?.connected == true || MeshEngine.meshcore?.connected == true),
            "Opens Connections", Modifier.weight(1f), connections)
        if (Here.internet) DialButton(R.drawable.ic_public, "Internet", Dials.internet(n != null, n?.connected ?: 0, n?.throughTor == true),
            if (n != null) "Asks before switching the internet off" else "Switches the internet on", Modifier.weight(1f)) {
            if (n == null) { Buzz.play(Buzz.On); MeshEngine.setInternet(true); s.tick++ } else askOff = true
        }
    }
    if (askWifiOff) ChoiceSheet("Switch Wi-Fi links off?", "You lose the phones you reach over a Wi-Fi network until you switch it back on.",
        "Keep it on", "Switch it off", icon = R.drawable.ic_wifi,
        onMain = { askWifiOff = false }, onOther = { askWifiOff = false; Buzz.play(Buzz.Off); MeshEngine.setWifi(false); s.tick++ }, onClose = { askWifiOff = false })
    if (askOff) ChoiceSheet("Switch the internet off?", "You keep the phones nearby. You lose anyone far away until you switch it back on.",
        "Keep it on", "Switch it off", icon = R.drawable.ic_public,
        onMain = { askOff = false }, onOther = { askOff = false; Buzz.play(Buzz.Off); MeshEngine.setInternet(false); s.tick++ }, onClose = { askOff = false })
}

/**
 * One dial (the board's .dial): a round button, white with a soft light while its way is switched on, a still green dot
 * while it has a link. Its name and one word under it. The whole dial is one button for TalkBack, read as "name, word".
 */
@Composable
private fun DialButton(@DrawableRes icon: Int, name: String, d: Dial, does: String, modifier: Modifier, tap: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (d.on) Color(0xFFFAFAFA) else Theme.tile, Motion.fade(250), label = "dial")
    val ink by animateColorAsState(if (d.on) Color(0xFF111111) else Theme.faint, Motion.fade(250), label = "dialIcon")
    Column(modifier.tapArea(source, label = does, onClick = tap)
        .clearAndSetSemantics { contentDescription = "$name, ${d.word}"; role = Role.Button }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(48.dp)
                .then(if (d.on) Modifier.glow(Color.White, 0.28f, 0.dp, 9.dp) else Modifier)
                .pressed(source, Press(down = 0.86f, bouncy = true, buzz = Buzz.None, lift = 0.06f))
                .background(fill, CircleShape).litEdge(CircleShape, if (d.on) 0.6f else 0.10f), contentAlignment = Alignment.Center) {
                Ic(icon, ink, 22.dp)
            }
            // Live: a still green dot (it never pulses: an endless animation keeps the screen busy), ringed in the card's colour.
            if (d.live) Box(Modifier.align(Alignment.TopEnd).size(15.dp).background(Theme.card, CircleShape).padding(2.5.dp).background(Theme.safe, CircleShape))
        }
        Spacer(Modifier.height(4.dp))
        Txt(name, 13, FontWeight.SemiBold, maxLines = 1)
        Txt(d.word, 12, color = if (d.live) Theme.safe else Theme.dim, maxLines = 2, lineHeight = 15, align = TextAlign.Center)
    }
}
