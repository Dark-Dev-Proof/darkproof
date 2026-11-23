package it.blackout.mesh

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// The Finder screen (board piece 9, "Finding Marco"): a ring of ticks that light up with the signal, rings for the band,
// the band's word and the trend under it, and a card with the person, the fine print and "I'm with Marco". No metres, no
// animation that runs by itself: the screen changes only when a reading does, and in calm mode it simply changes.
// Shown only while the trial switch is on (FinderPrefs.trial). The desktop shows the same screen with a line saying
// Finder needs a phone's Bluetooth instead of the ring.

/** The person the Finder screen looks for, for its card: their name, when they asked for help, their battery then. */
class FinderAsker(val name: String, val askedTs: Long, val battery: Int?) {
    /** "Asked 6 min ago · 🔋 15%". */
    fun line(now: Long = System.currentTimeMillis()) = "Asked ${ago(askedTs, now)}" + (battery?.let { " · 🔋 $it%" } ?: "")
    companion object { fun of(p: Person) = FinderAsker(p.name, p.checkInTs, p.battery) }
}

/** True if the Finder button belongs on [p]'s page or help card: the trial is on and their latest check-in is a checked "I need help" with a note. */
fun findable(p: Person?, trial: Boolean): Boolean = trial && p != null && !p.isReport && p.checkIn == CheckIn.HELP && p.finder != null

/** "Finding Marco" for person [id] of the group on screen. */
@Composable
fun FinderSheet(s: AppState, a: MainActivity, messages: List<Message>, id: String, close: () -> Unit) {
    val code = s.group?.code
    val p = remember(messages, id, s.tick) { peopleOf(a, messages, code).firstOrNull { it.id == id } }
    val name = p?.name ?: "Someone"
    val link = p?.finder?.takeIf { p.checkIn == CheckIn.HELP && !p.isReport }
    SheetPage(FinderWords.title(name), close) {
        when {
            // "I'm safe" (or a help check-in without the note) ends it: the screen says so, the readings stop (rule F6).
            p == null || link == null -> Card { Txt(FinderWords.ended(name, p?.checkIn == CheckIn.SAFE), 16, color = Theme.dim) }
            !Here.bluetooth -> FinderOnComputer(FinderAsker.of(p))
            s.bluetoothOff -> FinderBluetoothOff(FinderAsker.of(p)) { a.turnOnBluetooth() }
            else -> LiveFinder(p, link) {
                if (!meshReady(a)) return@LiveFinder
                val to = s.group?.name ?: "your group"
                Thread { MeshEngine.send(FinderWords.withThem(p.name), group = code) }.start() // a plain group message (decision 6)
                Toast.makeText(a, if (Links.now().any) "Sent to $to" else "Saved. It goes out when a phone or the internet is in reach.", Toast.LENGTH_LONG).show()
                close()
            }
        }
    }
}

/**
 * The live screen: Bluetooth readings of the link to [link] once a second ([BleLink.startRssi]), or the debug build's
 * fed readings ([FinderFake]), through [FinderMath.Tracker] into [FinderWords.shown]. The screen stays on meanwhile.
 */
@Composable
private fun LiveFinder(p: Person, link: String, withThem: () -> Unit) {
    KeepScreenOn()
    val name by rememberUpdatedState(p.name)
    val fake = remember { FinderFake.readings }
    val inbox = remember { java.util.concurrent.atomic.AtomicReference<Int?>(null) } // the latest reading, from the Bluetooth thread
    var shown by remember { mutableStateOf(FinderWords.shown(p.name, FinderReach.NONE, FinderRole.NONE, null)) }
    var debug by remember { mutableStateOf<String?>(null) }
    DisposableEffect(link) {
        if (fake == null) MeshEngine.ble?.startRssi(link) { inbox.set(it) }
        onDispose { if (fake == null) MeshEngine.ble?.stopRssi() }
    }
    // Once a second, whatever came in: this is the readings' clock, not an animation.
    LaunchedEffect(link) {
        val t = FinderMath.Tracker(); var second = 0L
        while (true) {
            val reach = if (fake != null) FinderReach.BLUETOOTH else MeshEngine.finderReach(link)
            val role = if (fake != null) FinderRole.FAKE else MeshEngine.ble?.rssiRole() ?: FinderRole.NONE
            val raw = if (fake != null) fake.getOrNull(second.toInt()) else inbox.getAndSet(null)
            val step = t.step(second, if (reach == FinderReach.BLUETOOTH) raw else null)
            shown = FinderWords.shown(name, reach, role, step)
            if (DebugTools.finderLine) debug = "${raw ?: "–"} dBm · S ${step.smoothed?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "–"} · ${role.word} · $link"
            second++
            delay(1_000)
        }
    }
    // A short touch at each change of band, the confirming one at "Here"; nothing while it stays the same.
    var felt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shown.word) {
        if (felt != null && felt != shown.word) Buzz.play(if (shown.here) Buzz.Confirm else Buzz.Tick)
        felt = shown.word
    }
    FinderBody(shown, FinderAsker.of(p), debug, withThem)
}

/** The Finder screen at one moment, as drawn (also by the desktop's screenshot test): the ring, a note, then the person's card. */
@Composable
fun FinderBody(shown: FinderWords.Shown, asker: FinderAsker, debug: String? = null, withThem: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FinderRing(shown)
        shown.note?.let { Txt(it, 15, color = Theme.dim, align = TextAlign.Center) }
        debug?.let { Txt(it, 12, color = Theme.faint, align = TextAlign.Center, maxLines = 2) }
    }
    FinderCard(asker) {
        Txt(FinderWords.FINE_PRINT, 14, color = Theme.dim)
        if (withThem != null) PrimaryButton(FinderWords.withThem(asker.name), onClick = withThem)
    }
}

/**
 * The ring (board piece 9): 36 ticks lit as the signal grows ([FinderWords.Shown.ticks]), white, green only at "Here";
 * inside, three thin rings, one more lit for each band closer. The word and its line in the middle are announced when
 * they change. Changes ease in over a moment, or at once in calm mode ([Motion]).
 */
@Composable
fun FinderRing(shown: FinderWords.Shown) {
    val lit by animateFloatAsState(shown.ticks.toFloat(), Motion.fade(450), label = "ticks")
    val tint by animateColorAsState(if (shown.here) Theme.safe else Color.White, Motion.fade(300), label = "tint")
    Box(Modifier.size(272.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val r = size.minDimension / 2
            val outer = r * 0.96f; val inner = r * 0.81f
            val w = 3.8.dp.toPx()
            for (i in 0 until FinderMath.TICKS) {
                val a = Math.toRadians(i * 360.0 / FinderMath.TICKS - 90.0)
                val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                // The last lit tick fades in with the count, so a change of one tick doesn't jump.
                val on = (lit - i).coerceIn(0f, 1f)
                val color = if (on <= 0f) Unlit else androidx.compose.ui.graphics.lerp(Unlit, tint, on)
                drawLine(color, Offset(center.x + dx * inner, center.y + dy * inner), Offset(center.x + dx * outer, center.y + dy * outer), w, StrokeCap.Round)
            }
            BandRings.forEachIndexed { i, f ->
                val on = shown.level > i
                drawCircle(if (on) tint.copy(alpha = if (shown.here) 0.55f else 0.30f) else Color.White.copy(alpha = 0.06f), r * f, style = Stroke(1.5.dp.toPx()))
            }
        }
        Column(Modifier.widthIn(max = 170.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val big = if (shown.word.length > 8) 26 else 34
            Txt(shown.word, big, FontWeight.Black, if (shown.here) Theme.safe else Theme.text, maxLines = 1, lineHeight = big + 4, align = TextAlign.Center)
            Txt(shown.line, 14, FontWeight.Medium, Theme.dim, Modifier.widthIn(max = 150.dp), maxLines = 3, lineHeight = 18, align = TextAlign.Center)
        }
    }
}

private val Unlit = Color(0xFF2B2B2B)
/** The band rings' radii, as parts of the ring's, inside the ticks and outside the words: the outer one lights at Far, the inner one at Very close. */
private val BandRings = listOf(0.765f, 0.715f, 0.665f)

/** The person's card: their initial in a red ring, "Marco needs help", when they asked and their battery; then [more]. */
@Composable
fun FinderCard(asker: FinderAsker, more: @Composable ColumnScope.() -> Unit) = Card(padding = 16.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(asker.name, CheckIn.HELP, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(FinderWords.card(asker.name), 17, FontWeight.SemiBold, maxLines = 2)
            Txt(asker.line(), 14, color = Theme.dim, maxLines = 1)
        }
    }
    more()
}

/** A computer has no Bluetooth links to phones: the screen says so, and offers nothing that can't work. */
@Composable
fun FinderOnComputer(asker: FinderAsker) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(72.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Ic(R.drawable.ic_bluetooth, Theme.text, 32.dp) }
        Txt(FinderWords.desktop(asker.name), 17, FontWeight.SemiBold, align = TextAlign.Center)
    }
    FinderCard(asker) {}
}

/** This phone's Bluetooth is off: one line, and the button that turns it on. */
@Composable
private fun FinderBluetoothOff(asker: FinderAsker, turnOn: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(72.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Ic(R.drawable.ic_bluetooth, Theme.dim, 32.dp) }
        Txt(FinderWords.BLUETOOTH_OFF, 17, FontWeight.SemiBold, align = TextAlign.Center)
        GhostButton("Turn on Bluetooth", primary = true, onClick = turnOn)
    }
    FinderCard(asker) { Txt(FinderWords.FINE_PRINT, 14, color = Theme.dim) }
}
