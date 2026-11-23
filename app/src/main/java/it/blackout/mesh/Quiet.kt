package it.blackout.mesh

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// "If I go quiet" (spec/FORMAT.md, "If I go quiet"): this phone's check-in clock, its row on Home, its card in the chat
// and its line in People. The iPhone's QuietView.swift, with its words (QuietWords). No Android of its own: the desktop
// compiles it as it is.

/**
 * This phone's check-in clock in [g]. The group's phones that get the clock message keep it; if no check-in from this phone
 * reaches one of them by the deadline, it can alert its owner. Whose phones it reached comes from the clock message's
 * delivery receipts, never the member list, and a receipt doesn't show that a phone keeps the clock (decision A).
 */
@Composable
fun QuietSheet(s: AppState, g: SavedGroup, close: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(MeshEngine.quietNow()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000 - System.currentTimeMillis() % 1_000); now = MeshEngine.quietNow() } }
    val own = remember(s.tick, now, g) { MeshEngine.quietOwn(g.code, now) }
    var minutes by remember { mutableIntStateOf(60) }
    var withSpot by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val share = s.shareLocation && Here.position

    /** Sends a clock message (rules QW1, QW2): the position first if it goes with it, then [toast] says what happened. */
    fun send(deadline: (Long) -> Long, spot: Boolean, toast: (Boolean) -> String) {
        if (busy) return
        busy = true
        val waiting = !Links.now().any
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun go(where: Spot?) = Thread {
            val ok = MeshEngine.sendClock(g.code, deadline, where)
            main.post {
                busy = false
                Buzz.play(if (ok) Buzz.Confirm else Buzz.Reject)
                Toast.makeText(ctx, if (ok) toast(waiting) else QuietWords.cantWrite(g.name, Here.device), Toast.LENGTH_LONG).show()
            }
        }.start()
        if (spot && Locator.allowed(ctx)) Locator.fix(ctx) { go(it) } else go(null)
    }

    SheetPage(QuietWords.TITLE, close) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(QuietWords.SUBTITLE, 14, FontWeight.SemiBold, Theme.dim) // the name the cards and alerts use (design review L3)
            Txt(QuietWords.LEAD, 19, FontWeight.Bold)
            Txt(QuietWords.SUB, 15, color = Theme.dim)
        }
        QuietDial(own, minutes, now, ctx)
        if (own != null) {
            val (c, state) = own
            if (state is QuietBook.State.RanOut) Txt(QuietWords.ranOut(clock(ctx, state.deadline)), 15, FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(QuietWords.reached(remember(s.tick) { MeshEngine.quietKeepers(g.code) }), 15, color = Theme.dim, align = TextAlign.Center)
                Txt(QuietWords.OLDER, 13, color = Theme.faint, align = TextAlign.Center) // right under whose phones it reached (design review H1)
            }
            // A check-in stops the clock everywhere it reaches (rule Q4).
            PrimaryButton(QuietWords.SAFE, enabled = !busy, icon = R.drawable.ic_verified_user) { quietSafe(ctx, s, g) }
            SecondaryButton(QuietWords.ADD_MORE, enabled = !busy) { val old = c.deadline; send({ QuietClock.added(old, it) }, share && withSpot, QuietWords::added) }
            TextAction(QuietWords.STOP_WITHOUT) { if (!busy) send({ 0L }, false, QuietWords::stopped) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietClock.CHOICES.forEach { m -> QuietChip(QuietWords.chip(m), m == minutes, Modifier.weight(1f)) { minutes = m } }
            }
            if (share) Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                SwitchRow(QuietWords.ADD_WHERE, withSpot, { withSpot = it }, QuietWords.ADD_WHERE_SUB)
            } else if (Here.position) Txt(QuietWords.SHARE_OFF, 14, color = Theme.dim)
            if (remember(s.tick, g) { MeshEngine.canTell(g.code) }) PrimaryButton(if (busy) QuietWords.GETTING else QuietWords.START, enabled = !busy) {
                val m = minutes
                send({ QuietClock.deadline(m, it) }, share && withSpot, QuietWords::sent)
            } else Txt(QuietWords.cantWrite(g.name, Here.device), 15, FontWeight.SemiBold) // rule QW2: never held until this phone is let in
            Txt(QuietWords.OLDER, 13, color = Theme.faint)
        }
    }
}

/** "I'm safe, stop the clock": the ordinary check-in, with battery and, if the user shares it, position. */
private fun quietSafe(ctx: android.content.Context, s: AppState, g: SavedGroup) {
    val waiting = !Links.now().any
    fun go(spot: Spot?) { Thread { MeshEngine.send(CheckIn.SAFE.text(batteryPercent(ctx), spot), group = g.code) }.start() }
    if (s.shareLocation && Locator.allowed(ctx)) Locator.fix(ctx) { go(it) } else go(null)
    Buzz.play(Buzz.Confirm)
    Toast.makeText(ctx, QuietWords.safeSent(waiting), Toast.LENGTH_LONG).show()
}

/** One of the chips "30 min", "1 hour", "3 hours", "8 hours": the chosen one white, the others on a tile. */
@Composable
private fun QuietChip(text: String, on: Boolean, modifier: Modifier, tap: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).tap(Press(down = 0.94f, shape = CircleShape, bouncy = true, lift = 0.05f, buzz = Buzz.Tick), role = androidx.compose.ui.semantics.Role.RadioButton, onClick = tap)
        .semantics { selected = on }.background(if (on) Color.White else Theme.tile, CircleShape).litEdge(CircleShape, if (on) 0.6f else 0.10f),
        contentAlignment = Alignment.Center) {
        Txt(text, 15, FontWeight.SemiBold, if (on) Color.Black else Theme.text, maxLines = 1)
    }
}

/** The ring with the time left in it: the running clock, or the one about to start. */
@Composable
private fun QuietDial(own: Pair<QuietBook.Clock, QuietBook.State>?, minutes: Int, now: Long, ctx: android.content.Context) {
    val total: Long; val left: Long; val line: String
    if (own != null) {
        val c = own.first
        total = maxOf(1L, c.deadline - c.ts); left = maxOf(0L, c.deadline - now); line = QuietWords.ends(clock(ctx, c.deadline))
    } else {
        total = minutes * 60_000L; left = total; line = QuietWords.checkInBy(clock(ctx, QuietClock.deadline(minutes, now)))
    }
    val lit = if (left == 0L) 0 else minOf(60, Math.ceil(left.toDouble() / total * 60).toInt())
    val countdown = countdown(left)
    Box(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = if (own == null) line else "$countdown left. $line" }, contentAlignment = Alignment.Center) {
        QuietRing(lit)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(countdown, 38, FontWeight.Black)
            Txt(line, 14, FontWeight.Medium, Theme.dim, Modifier.widthIn(max = 150.dp), align = TextAlign.Center)
        }
    }
}

/** "1:00:00", "29:59", "0:00". */
fun countdown(ms: Long): String {
    val s = Math.ceil(maxOf(0L, ms) / 1000.0).toLong(); val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

/** 60 ticks around the countdown, lit from the top clockwise for the time that is left (the board's ring). */
@Composable
private fun QuietRing(lit: Int) {
    val shown by animateFloatAsState(lit.toFloat(), Motion.fade(200), label = "ring")
    Canvas(Modifier.size(220.dp)) {
        val w = 3.5.dp.toPx(); val h = 10.dp.toPx(); val r = 99.dp.toPx()
        for (i in 0 until 60) rotate(i * 6f, center) {
            drawRoundRect(if (i < shown) Color.White else Color(0xFF2B2B2B), Offset(center.x - w / 2, center.y - r - h / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** Home's row under the check-in buttons: opens "If I go quiet" and says how this phone's clock stands. */
@Composable
fun HomeQuietRow(s: AppState, g: SavedGroup, open: () -> Unit) {
    val ctx = LocalContext.current
    val own = remember(s.tick, g) { MeshEngine.quietOwn(g.code) }
    val line = when (val st = own?.second) {
        is QuietBook.State.Running -> QuietWords.ends(clock(ctx, st.deadline))
        is QuietBook.State.RanOut -> QuietWords.ranOutShort(clock(ctx, st.deadline))
        else -> QuietWords.OFF
    }
    val shape = RoundedCornerShape(22.dp)
    Row(Modifier.fillMaxWidth().tap(CardPress, label = QuietWords.TITLE, onClick = open).background(Theme.card, shape).litEdge(shape, 0.06f)
        .padding(horizontal = 16.dp, vertical = 14.dp).semantics { contentDescription = "${QuietWords.TITLE}. $line" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Theme.tile, CircleShape).litEdge(CircleShape), contentAlignment = Alignment.Center) {
            Ic(R.drawable.ic_schedule, if (own != null) Theme.text else Theme.dim, 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(QuietWords.TITLE, 17, FontWeight.SemiBold, maxLines = 1)
            Txt(line, 14, color = if (own?.second is QuietBook.State.RanOut) Theme.text else Theme.dim, maxLines = 1)
        }
        Ic(R.drawable.ic_chevron_right, Theme.dim, 20.dp)
    }
}

/**
 * A clock message in the chat: what this phone will do about it, in words that claim no more than it knows. [name]: null
 * for this phone's own, else as rule Q13 has it. [keeping]: this device holds this very clock, armed ([QuietBook.keeps]);
 * if not, the card says so and promises nothing (review item 9). A device without alerts (a computer) promises nothing
 * about telling; a phone whose alerts are off for DarkProof ([alertsOff]) says it shows here instead (design review M1).
 */
@Composable
fun QuietCard(m: Message, deadline: Long, name: String?, time: String, keeping: Boolean, alertsOff: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Theme.tile, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_schedule, if (deadline == 0L) Theme.dim else Theme.text, 24.dp)
            Spacer(Modifier.width(14.dp))
            Txt(if (deadline == 0L) QuietWords.stoppedLine(name) else QuietWords.started(name), 17, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Txt(time, 14, color = Theme.dim)
        }
        // late: Android's alarms are inexact (a computer posts none, so it never gets that far).
        if (deadline != 0L) QuietWords.cardLine(name, clock(ctx, deadline), keeping, Here.notifications, alertsOff, Here.device, late = true)?.let { sub ->
            Txt(sub, 15, color = Theme.dim, modifier = Modifier.padding(start = 38.dp))
        }
        QuietClock.spot(m.text)?.let { Box(Modifier.padding(start = 38.dp)) { PlusCodeLabel(it, 13) } }
    }
}

/** The line under a person in People and on Home (rule Q6): their clock while it runs, "overdue" once it ran out with no check-in reaching this phone, and their check-in after it. */
@Composable
fun QuietLine(state: QuietBook.State?) {
    val ctx = LocalContext.current
    when (state) {
        is QuietBook.State.Running -> Txt(QuietWords.running(clock(ctx, state.deadline)), 14, color = Theme.dim, maxLines = 2)
        is QuietBook.State.RanOut -> Txt(QuietWords.overdue(clock(ctx, state.deadline)), 14, FontWeight.SemiBold, maxLines = 2)
        is QuietBook.State.CheckedIn -> Txt(QuietWords.checkedIn(clock(ctx, state.at)), 14, FontWeight.Medium, Theme.safe, maxLines = 2)
        null -> {}
    }
}
