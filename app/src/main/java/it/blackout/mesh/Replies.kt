package it.blackout.mesh

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

// Replies, reactions and "How it got there" in the chat (spec/FORMAT.md, "Replies and reactions", "Journey notes"). The
// rules are in About.kt and Journey.kt (About, Reactions, Quote, Journey); these are only the views. Same as the iPhone's Replies.swift.

/** The quote above a reply's text, or the line that says why there is none. [name]: null when the phone has nothing to quote. [row]: the row to scroll to. */
data class QuoteLine(val name: String?, val text: String, val row: String? = null)

/**
 * The quote above a reply (spec "Replies"): from this phone's own copy of the target, found by what the reply names. Nothing
 * is quoted from the reply itself, and nothing while the reply isn't checked. [shown]: [About.targets] of the rows on screen;
 * [held]: of every row of the group this phone holds, hidden ones too ([MeshEngine.groupRows]).
 */
fun quoteOf(m: Message, shown: Map<String, Message>, held: Map<String, Message>, me: String): QuoteLine? {
    if (m.public || !m.verified) return null
    val t = (m.about as? About.Reply)?.target ?: return null
    shown[t.key]?.let { return QuoteLine(Quote.name(it, me), Quote.line(it), it.id) }
    return QuoteLine(null, if (held.containsKey(t.key)) Quote.HIDDEN else Quote.MISSING)
}

/**
 * A quote inside a bubble or above a card: a thin bar, the name, one line of the message. Tapping it shows that message,
 * when it is in the chat. Holding it does what holding the rest of the message does ([hold]: its menu), not a tap.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QuoteView(q: QuoteLine, modifier: Modifier = Modifier, onWhite: Boolean = false, tap: (() -> Unit)? = null, hold: (() -> Unit)? = null) {
    val ink = if (onWhite) Color.Black else Theme.text
    val said = q.name?.let { "Reply to $it: ${q.text}" } ?: "Reply to: ${q.text}"
    val canTap = tap != null && q.row != null
    Row(modifier.height(IntrinsicSize.Min)
        .then(if (canTap) Modifier.pointerHoverIcon(PointerIcon.Hand).combinedClickable(interactionSource = null,
            indication = Press(down = 0.98f, shape = RoundedCornerShape(10.dp), dim = 0.12f, lift = 0.04f, buzz = Buzz.None),
            onClickLabel = "Show that message", role = Role.Button, onLongClick = hold, onLongClickLabel = if (hold != null) "Message options" else null) { tap?.invoke() }
            else Modifier)
        .semantics(mergeDescendants = true) { contentDescription = said }
        .alpha(0.64f)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(ink, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            q.name?.let { Txt(it, 13, FontWeight.Bold, ink, maxLines = 1) }
            Txt(q.text, 13, color = ink, maxLines = 1)
        }
    }
}

/**
 * The reactions under a message: each with its count, the user's own outlined. Tapping one sets or takes back the user's
 * own ([tap] is null where the group can't be written to: then they are only shown).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionChips(chips: List<Reactions.Chip>, modifier: Modifier = Modifier, tap: ((String) -> Unit)? = null) =
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in chips) {
            val said = "${c.text}, ${c.count} ${if (c.count == 1) "person" else "people"}${if (c.mine) ", you too" else ""}"
            val shape = CircleShape
            // A black ring, so a chip reads apart from the bubble it sits on.
            Box(Modifier.background(Theme.bg, shape).padding(2.dp)) {
                Row(Modifier
                    .then(if (tap != null) Modifier.tap(Press(down = 0.9f, shape = shape, dim = 0.2f, bouncy = true, buzz = Buzz.Tap),
                        label = if (c.mine) "Take your reaction back" else "Add your reaction") { tap(c.text) } else Modifier)
                    .background(if (c.mine) Color(0xFF454545) else Color(0xFF2C2C2E), shape)
                    .then(if (c.mine) Modifier.border(1.5.dp, Theme.text.copy(alpha = 0.9f), shape) else Modifier)
                    .semantics(mergeDescendants = true) { contentDescription = said }
                    .padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.text, fontSize = 14.sp, color = Theme.text, maxLines = 1)
                    if (c.count > 1) { Spacer(Modifier.width(4.dp)); Txt("${c.count}", 14, FontWeight.SemiBold) }
                }
            }
        }
    }

/** The strip above the composer while a reply is being written. [to]: "Marco", or "yourself". */
@Composable
fun ReplyStrip(to: String, text: String, modifier: Modifier = Modifier, cancel: () -> Unit) =
    Row(modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Ic(R.drawable.ic_reply, Theme.dim, 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Txt("Replying to $to", 14, FontWeight.SemiBold, maxLines = 1)
            if (text.isNotEmpty()) Txt(text, 13, color = Theme.dim, maxLines = 1)
        }
        Box(Modifier.size(44.dp).tap(Press(down = 0.9f, buzz = Buzz.Tap), label = "Cancel the reply", onClick = cancel)
            .semantics { contentDescription = "Cancel the reply" }, contentAlignment = Alignment.Center) { Ic(R.drawable.ic_close, Theme.dim, 18.dp) }
    }

/** One line of the menu on a held message. [danger]: in red, last, after a thicker gap. */
class HoldItem(val label: String, @DrawableRes val icon: Int, val danger: Boolean = false, val act: () -> Unit)

/** The overlay fills the window from its top left, whatever it was opened from. */
private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * What holding a group message opens (the board's `hold()`): the chat dims, the held message stays sharp, the reactions sit
 * in a capsule above it and a short menu below it. [react]: null where the group can't be written to (no capsule then).
 * Tapping outside, Back, or Escape closes it. [message]: the held message, drawn again.
 */
@Composable
fun HoldOverlay(mine: Boolean, react: ((String) -> Unit)?, items: List<HoldItem>, close: () -> Unit, message: @Composable () -> Unit) {
    Popup(popupPositionProvider = WholeWindow, onDismissRequest = close, properties = PopupProperties(focusable = true)) {
        val shown = remember { Animatable(if (Motion.calm) 1f else 0f) }
        LaunchedEffect(Unit) { shown.animateTo(1f, Motion.fade(200)) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Color.Black.copy(alpha = 0.74f))
                .clickable(interactionSource = null, indication = null, onClickLabel = "Close the menu", onClick = close)
                .semantics { contentDescription = "Close the menu" })
            // On a phone the whole width; in a wide window a column in the middle, so the held message isn't at the window's edge.
            Column(Modifier.fillMaxHeight().widthIn(max = 520.dp).fillMaxWidth().align(Alignment.Center).systemBarsPadding().padding(16.dp)
                .graphicsLayer { val v = shown.value; alpha = v; val sc = 0.96f + 0.04f * v; scaleX = sc; scaleY = sc },
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (react != null) ReactionBar(react)
                Box(Modifier.widthIn(max = 320.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { message() }
                HoldMenu(items)
            }
        }
    }
}

/** The five reactions in a capsule; each rises in after the one before it (not in calm mode). */
@Composable
private fun ReactionBar(react: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(Modifier.background(Color(0xF0343436), shape).litEdge(shape).padding(horizontal = 6.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ReactionList.offered.forEachIndexed { i, e ->
            val rise = remember { Animatable(if (Motion.calm) 1f else 0f) }
            LaunchedEffect(Unit) { if (!Motion.calm) { delay(30L * i); rise.animateTo(1f, Motion.rise()) } }
            Box(Modifier.size(44.dp)
                .graphicsLayer { val v = rise.value; val sc = 0.5f + 0.5f * v; scaleX = sc; scaleY = sc; alpha = v.coerceIn(0f, 1f) }
                .tap(Press(down = 0.8f, dim = 0f, light = 0.10f, lift = 0.10f, bouncy = true, buzz = Buzz.Tap), label = "React with $e") { react(e) }
                .semantics { contentDescription = "React with $e" }, contentAlignment = Alignment.Center) {
                Text(e, fontSize = 24.sp, color = Theme.text)
            }
        }
    }
}

@Composable
private fun HoldMenu(items: List<HoldItem>) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(236.dp).clip(shape).background(Color(0xF7222224)).litEdge(shape)) {
        items.forEachIndexed { i, it ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(if (it.danger) 6.dp else 1.dp).background(if (it.danger) Color.Black.copy(alpha = 0.4f) else Theme.line))
            val ink = if (it.danger) Theme.help else Theme.text
            Row(Modifier.fillMaxWidth().tap(Press(down = 1f, shape = RoundedCornerShape(0.dp), dim = 0f, lift = 0.05f, light = 0.07f, buzz = Buzz.None), onClick = it.act)
                .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(it.label, 16, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
                Ic(it.icon, ink, 19.dp)
            }
        }
    }
}

/** A time on the journey: the time of day, with the day in front when it isn't today. */
private fun stamp(c: android.content.Context, ts: Long): String = if (dayOf(ts) == dayOf(System.currentTimeMillis())) clock(c, ts) else "${dayLabel(c, ts)} ${clock(c, ts)}"

/**
 * "How it got there": what this phone knows about the way one of its own group messages took (spec "Journey notes"). It
 * reads the engine again at each update ([AppState.tick]), so a receipt that arrives while it is open shows up.
 */
@Composable
fun JourneySheet(s: AppState, id: String, close: () -> Unit) = SmallSheet(close) {
    val ctx = LocalContext.current
    val tick = s.tick
    val m = remember(tick, id) { MeshEngine.snapshotAll().firstOrNull { it.id == id } }
    val j = remember(tick, m) { m?.let { MeshEngine.journey(it) } }
    Txt("How it got there", 21, FontWeight.Black)
    if (m == null || j == null) { Txt("This message is no longer on this ${Here.device}.", 15, color = Theme.dim); return@SmallSheet }
    val line = Quote.line(m)
    if (line.isNotEmpty()) Txt(line, 14, color = Theme.dim, maxLines = 1, modifier = Modifier.fillMaxWidth().background(Theme.tile, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
    Column { j.steps.forEachIndexed { i, st -> StepRow(ctx, st, last = i == j.steps.size - 1) } }
    if (j.carried) Row(verticalAlignment = Alignment.Top) {
        Ic(R.drawable.ic_group, Theme.dim, 18.dp); Spacer(Modifier.width(10.dp)); Txt(Journey.CARRIED, 14, color = Theme.dim)
    }
    if (j.confirmed) Txt("Reached means their phone got the message. It doesn't mean they have read it.", 13, color = Theme.faint)
    Row(Modifier.fillMaxWidth().background(Theme.tile, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Ic(R.drawable.ic_lock, Theme.dim, 15.dp); Spacer(Modifier.width(10.dp))
        Txt(Journey.SEALED, 15, color = Theme.dim, modifier = Modifier.weight(1f))
        Txt("Yes", 15, FontWeight.SemiBold)
    }
}

/** One line of the journey: a round mark, what happened, and when. With a note the line under the title says how it came; without one, when the receipt was written. */
@Composable
private fun StepRow(ctx: android.content.Context, step: Journey.Step, last: Boolean) {
    val reached = step.kind == Journey.Step.Kind.REACHED
    val detail = step.detail ?: if (reached) step.at?.let { stamp(ctx, it) } else null
    val time = if (reached) step.later else step.at?.let { stamp(ctx, it) }
    val icon = when (step.kind) { Journey.Step.Kind.LEFT -> R.drawable.ic_arrow_upward; Journey.Step.Kind.REACHED -> R.drawable.ic_check; else -> R.drawable.ic_schedule }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(24.dp).background(if (reached) Theme.safe else Theme.tile, CircleShape), contentAlignment = Alignment.Center) {
                Ic(icon, if (reached) Color.Black else Theme.text, 14.dp)
            }
            if (!last) Box(Modifier.padding(top = 3.dp).width(2.dp).weight(1f).background(Theme.line, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp, bottom = if (last) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Txt(step.title, 16, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (time != null) { Spacer(Modifier.width(8.dp)); Txt(time, 13, color = Theme.faint) }
            }
            if (detail != null) Txt(detail, 14, color = Theme.dim)
        }
    }
}
