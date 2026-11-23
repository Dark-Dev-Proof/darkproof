package it.blackout.mesh

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Voice notes on screen (board `chat`): the bubble (seek on the bars, the speed chip), the mic button that records while
// held (or, slid up onto the lock, hands-free), and the strip that shows the recording. Shared by the phone and the
// desktop; the desktop's VoiceAudio can neither record nor play, so it offers no mic and its bubble says "Play it on a
// phone". The rules of the bytes are in VoiceNotes.kt; nothing here changes them.

/** A recording in progress, as the composer shows it. */
class VoiceRec {
    var on by mutableStateOf(false)
    /** Ten seconds are recorded: the timer stays at 0:10 and the note goes when the finger lifts (or, locked, when Send is tapped). */
    var full by mutableStateOf(false)
    /** Slid up onto the lock: it goes on recording with the finger lifted, until the button stops and sends it or Cancel drops it. */
    var locked by mutableStateOf(false)
}

/**
 * The bubble's content for a voice note that may be played ([Voice.playable]): the play/pause button, 20 bars from the
 * decoded sound, the length ("0:07") and the speed chip ("1×", the same for every note, kept on this phone). A tap or a
 * sideways drag on the bars moves the note (TalkBack: the row is adjustable, a tenth at a time); a paused note keeps its
 * place. On a device that can't play voice notes (a computer): "Voice note, 0:07" and "Play it on a phone". [hold] opens
 * the message's menu, as holding the rest of the bubble does.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VoiceNoteRow(m: Message, onWhite: Boolean, preview: Boolean, hold: () -> Unit) {
    val ctx = LocalContext.current
    val frames = m.voice ?: return
    val count = remember(m.id) { Voice.split(frames)?.size ?: 0 }
    val secs = Voice.seconds(count)
    val ink = if (onWhite) Color.Black else Theme.text
    if (!VoiceAudio.canPlay) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp).semantics(mergeDescendants = true) { contentDescription = Voice.spoken(secs) + ". Play it on a phone" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic(R.drawable.ic_mic, ink, 18.dp); Spacer(Modifier.width(6.dp))
                Txt("Voice note, ${Voice.label(secs)}", 17, FontWeight.SemiBold, ink)
            }
            Txt("Play it on a phone", 14, color = ink.copy(alpha = 0.6f))
        }
        return
    }
    // The picture is drawn from the decoded sound, here, once; until then the bars lie flat.
    var failed by remember(m.id) { mutableStateOf(false) }
    val levels by produceState<FloatArray?>(null, m.id) { value = withContext(Dispatchers.Default) { VoiceAudio.levels(m.id, frames) }; if (value == null) failed = true }
    val playing = VoiceAudio.playing == m.id
    var drag by remember(m.id) { mutableStateOf<Float?>(null) } // where a finger on the bars is, until it lets go
    val at = drag ?: VoiceAudio.position(m.id)
    val live = !preview && !failed
    val fill = if (onWhite) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.13f)
    Column(Modifier.padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.semantics(mergeDescendants = true) {
                contentDescription = Voice.spoken(secs)
                if (live) {
                    stateDescription = Voice.positionSpoken(at, count)
                    progressBarRangeInfo = ProgressBarRangeInfo(at, 0f..1f, steps = 9)
                    // TalkBack's adjust: a tenth forward or back, to the nearest tenth, as on the iPhone.
                    setProgress { v -> if (v != at) VoiceAudio.seek(m.id, Voice.stepped(at, forward = v > at)); true }
                }
            }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(fill)
                    .then(if (!live) Modifier else Modifier.combinedClickable(onClickLabel = if (playing) "Pause" else "Play", onLongClick = hold, onLongClickLabel = "Message options") {
                        Buzz.play(Buzz.Tap)
                        VoiceAudio.toggle(m.id, frames) { failed = true; Toast.makeText(ctx, "Can't play this voice note.", Toast.LENGTH_SHORT).show() }
                    }), contentAlignment = Alignment.Center) {
                    Ic(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, ink, 18.dp)
                }
                Spacer(Modifier.width(10.dp))
                Bars(levels, at, ink, if (!live) Modifier else Modifier.seekBars(m.id, { drag = it }, { VoiceAudio.seek(m.id, it) }, hold))
                Spacer(Modifier.width(10.dp))
                Txt(Voice.label(secs), 14, FontWeight.SemiBold, ink)
            }
            Spacer(Modifier.width(8.dp))
            SpeedChip(ink, fill, enabled = live)
        }
        if (failed) Txt("Can't play this voice note.", 13, color = ink.copy(alpha = 0.6f), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

/** 20 bars, 2.5 dp wide, up to 18 dp tall; those already played are drawn full, the rest dimmer (board `.wv`). 34 dp of touch. */
@Composable
private fun Bars(levels: FloatArray?, progress: Float, ink: Color, modifier: Modifier = Modifier) =
    Row(modifier.height(34.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 20) {
            val l = levels?.getOrNull(i) ?: 0f
            val played = progress > 0f && (i + 0.5f) / 20f <= progress
            Box(Modifier.width(2.5.dp).height((4 + 14 * l).dp).clip(RoundedCornerShape(2.dp)).background(ink.copy(alpha = if (played) 1f else 0.5f)))
        }
    }

/**
 * A tap on the bars moves the note there; a sideways drag shows where it will go ([drag], null when the finger is off)
 * and moves it when the finger lets go. Up and down are left to the chat's scrolling; holding still opens the menu ([hold]).
 */
private fun Modifier.seekBars(id: String, drag: (Float?) -> Unit, seek: (Float) -> Unit, hold: () -> Unit): Modifier = composed {
    val drags by rememberUpdatedState(drag); val seeks by rememberUpdatedState(seek); val holds by rememberUpdatedState(hold)
    pointerInput(id) {
        detectTapGestures(onTap = { seeks(Voice.seekAt(it.x, size.width.toFloat())) }, onLongPress = { holds() })
    }.pointerInput(id) {
        var last = 0f
        detectHorizontalDragGestures(
            onDragStart = { last = Voice.seekAt(it.x, size.width.toFloat()); drags(last) },
            onDragEnd = { seeks(last); drags(null) },
            onDragCancel = { drags(null) },
        ) { change, _ -> change.consume(); last = Voice.seekAt(change.position.x, size.width.toFloat()); drags(last) }
    }
}

/** The speed chip: "1×", "1.5×", "2×" in turn, for every voice note at once, kept on this phone. */
@Composable
private fun SpeedChip(ink: Color, fill: Color, enabled: Boolean) {
    val ctx = LocalContext.current
    val s = VoiceAudio.speed(ctx)
    val shape = RoundedCornerShape(13.dp)
    Box(Modifier.heightIn(min = 34.dp).clip(shape).tap(Press(shape = shape, buzz = Buzz.Tick), enabled = enabled, label = "Change") { VoiceAudio.cycleSpeed(ctx) }
        .semantics { contentDescription = Voice.speedSpoken(s) }, contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(min = 38.dp).height(26.dp).background(fill, shape).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Txt(Voice.speedLabel(s), 13, FontWeight.SemiBold, ink, maxLines = 1)
        }
    }
}

/** Under a message whose text says it is a voice note but that has none this phone can play (an ignored field). */
@Composable
fun CantPlayNote(onWhite: Boolean) =
    Txt("Can't play this voice note.", 13, color = (if (onWhite) Color.Black else Theme.text).copy(alpha = 0.6f), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))

/**
 * The composer's line while recording (board `.rec`): a red dot, the time so far and "Slide away to cancel"; once locked,
 * "Cancel" instead. The time is what the microphone has given, so it stops at 0:10.
 */
@Composable
fun RecordingStrip(v: VoiceComposer, modifier: Modifier = Modifier) {
    val rec = v.rec
    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(rec.on) { while (rec.on) { ms = VoiceAudio.recordedMs(); delay(100) } }
    val secs = (ms / 1000).toInt().coerceAtMost(10)
    val blink by produceState(1f, rec.on) { var on = true; while (true) { delay(500); on = !on; value = if (on) 1f else 0.3f } }
    val dot = @Composable {
        Box(Modifier.size(10.dp).background(Theme.help.copy(alpha = if (rec.full) 1f else blink), CircleShape))
        Spacer(Modifier.width(10.dp))
        Txt(Voice.label(secs), 16, FontWeight.SemiBold)
    }
    if (rec.locked) Row(modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Theme.tile).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Recording hands-free, ${Voice.label(secs)}" }, verticalAlignment = Alignment.CenterVertically) { dot() }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val pill = RoundedCornerShape(22.dp)
            Box(Modifier.heightIn(min = 44.dp).clip(pill).tap(Press(shape = pill)) { v.end(false) }.semantics { contentDescription = "Cancel voice note" }
                .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Txt("Cancel", 15, FontWeight.SemiBold, maxLines = 1)
            }
        }
    } else Row(modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Theme.tile).padding(horizontal = 18.dp)
        .semantics(mergeDescendants = true) { contentDescription = "Recording, ${Voice.label(secs)}. Slide away to cancel" },
        verticalAlignment = Alignment.CenterVertically) {
        dot()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_chevron_left, Theme.dim, 16.dp); Spacer(Modifier.width(2.dp))
            Txt("Slide away to cancel", 14, color = Theme.dim, maxLines = 1)
        }
    }
}

/**
 * The send button while the message box is empty: a mic. Holding it records, letting go sends, sliding off it (a little
 * past its edge beside or below it) cancels, and sliding up onto the lock above it keeps it recording with the finger
 * lifted ([Voice.slide]); then it is a red send button that stops and sends. While recording it grows and turns red
 * (board `.recording .mic`). TalkBack: "Record hands-free" starts a locked recording at once.
 */
@Composable
fun MicButton(v: VoiceComposer) {
    val rec = v.rec
    if (rec.on && rec.locked) LockedSend(v) else HoldMic(v)
}

@Composable
private fun HoldMic(v: VoiceComposer) {
    val rec = v.rec
    val starts by rememberUpdatedState(v.begin); val ends by rememberUpdatedState(v.end); val locks by rememberUpdatedState(v.lock)
    var lift by remember { mutableFloatStateOf(0f) } // how near the finger is to the lock, for its picture
    val grow by animateFloatAsState(if (rec.on) 1.22f else 1f, Motion.round(), label = "mic")
    Box(Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp)
        .semantics {
            contentDescription = "Hold to record a voice note"
            customActions = listOf(CustomAccessibilityAction("Record hands-free") { v.handsFree(); true })
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                if (rec.on || !starts()) { waitForUpOrCancellation(); return@awaitEachGesture }
                var keep = true
                try {
                    val dp = 1.dp.toPx()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id }
                        if (c == null) { keep = false; break }
                        if (!c.pressed) break
                        val p = c.position
                        lift = Voice.lift(p.y, dp)
                        when (Voice.slide(p.x, p.y, size.width.toFloat(), size.height.toFloat(), dp)) {
                            Voice.Slide.LOCK -> { c.consume(); lift = 0f; locks(); return@awaitEachGesture } // this button gives way to the send button
                            Voice.Slide.CANCEL -> { keep = false; break }
                            Voice.Slide.HOLD -> c.consume()
                        }
                    }
                } catch (e: CancellationException) { lift = 0f; if (!rec.locked) ends(false); throw e }
                lift = 0f
                ends(keep)
            }
        }, contentAlignment = Alignment.Center) {
        if (rec.on) LockPill(lift, Modifier.align(Alignment.TopCenter).above(10.dp))
        Box(Modifier.size(52.dp).scale(grow).background(if (rec.on) Theme.help else Theme.tile, CircleShape).litEdge(CircleShape),
            contentAlignment = Alignment.Center) {
            Ic(R.drawable.ic_mic, if (rec.on) Color.White else Theme.text, 22.dp)
        }
    }
}

private const val LOCK_PILL = 68 // dp, the lock's height above the mic

/** Drawn [gap] above where it is put, centred, taking no room: the composer row keeps its height while the lock shows. */
private fun Modifier.above(gap: Dp) = layout { m, _ ->
    val p = m.measure(Constraints())
    layout(0, 0) { p.place(-p.width / 2, -p.height - gap.roundToPx()) }
}

/** The lock above the mic while it is held: slide up onto it ([lift] 0 to 1 as the finger nears it). Only a picture: TalkBack has "Record hands-free". */
@Composable
private fun LockPill(lift: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.width(40.dp).height(LOCK_PILL.dp).clip(shape).background(Theme.tile).litEdge(shape).padding(vertical = 12.dp).clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Ic(R.drawable.ic_lock, Theme.text.copy(alpha = 0.6f + 0.4f * lift), 18.dp)
        Ic(R.drawable.ic_expand_more, Theme.dim, 18.dp, Modifier.graphicsLayer { rotationZ = 180f; translationY = -8.dp.toPx() * lift })
    }
}

/** A locked recording's button: red, an arrow up, stops the recording and sends it. */
@Composable
private fun LockedSend(v: VoiceComposer) {
    val label = if (v.rec.full) "Send voice note" else "Stop and send"
    Box(Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp).tap(Press(buzz = Buzz.Confirm)) { v.end(true) }.semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp).scale(1.22f).background(Theme.help, CircleShape).litEdge(CircleShape), contentAlignment = Alignment.Center) {
            Ic(R.drawable.ic_arrow_upward, Color.White, 22.dp)
        }
    }
}

/**
 * What the composer needs for voice notes: the recording's state, and what holding, sliding up and letting go of the mic
 * do. Asks for the microphone the first time (with the reason first), shows high-risk mode's line once before the first
 * recording, and sends the note as a reply when one is being written. [replying] and [sent] are read when it is sent.
 */
class VoiceComposer(val rec: VoiceRec, val begin: () -> Boolean, val end: (Boolean) -> Unit, val lock: () -> Unit) {
    /** For people who can't hold and slide (TalkBack's "Record hands-free"): a recording that is locked from the start. */
    fun handsFree() { if (!rec.on && begin()) lock() }
}

@Composable
fun rememberVoiceComposer(s: AppState, replying: () -> Message?, sent: () -> Unit): VoiceComposer {
    val ctx = LocalContext.current
    val a = ctx as MainActivity
    val rec = remember { VoiceRec() }
    var why by remember { mutableStateOf(false) }      // the reason, before the phone's own question
    var off by remember { mutableStateOf(false) }      // refused for good: only Settings can turn it on
    var risk by remember { mutableStateOf(false) }     // high-risk mode, once before the first recording
    val toast = { t: String -> Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show() }
    val ask = VoiceAudio.rememberMicAsk { ok -> if (ok) toast("Hold the mic to record") }
    if (why) Confirm("Use the microphone?", "To record a voice note for your group, from when you press the mic button until the note is sent or cancelled. Nothing is recorded at any other time.", "Continue",
        onYes = { why = false; VoiceAudio.notedMicAsked(ctx); ask() }, onNo = { why = false })
    if (off) Confirm("The microphone is off", "DarkProof can't use the microphone. Turn it on in Settings to record voice notes.", "Open Settings",
        onYes = { off = false; VoiceAudio.openSettings(a) }, onNo = { off = false })
    if (risk) Confirm("A voice can identify you.", "Even to someone who later gets a phone from your group.", "Record anyway",
        onYes = { risk = false; ctx.getSharedPreferences("mesh", 0).edit().putBoolean("voiceRiskSeen", true).apply(); toast("Hold the mic to record") },
        onNo = { risk = false })
    // Nothing records or plays behind a closed app or a chat that was left.
    WhileOnScreen { { VoiceAudio.stopPlaying(); if (rec.on) { rec.on = false; rec.locked = false; VoiceAudio.cancelRecording() } } }
    return remember {
        VoiceComposer(rec, begin = {
            when {
                !meshReady(ctx) -> false
                s.highRisk && !ctx.getSharedPreferences("mesh", 0).getBoolean("voiceRiskSeen", false) -> { risk = true; false }
                !VoiceAudio.micAllowed(ctx) -> { if (VoiceAudio.canAskMic(a)) why = true else off = true; false }
                !VoiceAudio.startRecording(ctx) { rec.full = true; Buzz.play(Buzz.Confirm) } -> { toast("The microphone can't be used right now"); false }
                else -> { Buzz.play(Buzz.Hold); rec.full = false; rec.locked = false; rec.on = true; true }
            }
        }, lock = {
            if (rec.on && !rec.locked) { rec.locked = true; Buzz.play(Buzz.Confirm) }
        }, end = { keep ->
            if (rec.on) {
                rec.on = false; rec.locked = false
                if (!keep) { VoiceAudio.cancelRecording(); toast("Voice note cancelled") }
                else {
                    val to = replying()
                    Thread {
                        val r = VoiceAudio.finishRecording()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            when (r) {
                                is VoiceAudio.Result.Ready -> { sent(); Thread { MeshEngine.send(Voice.PLACEHOLDER, voice = r.frames, replyTo = to) }.start() }
                                VoiceAudio.Result.TooShort -> toast("Hold the mic to record")
                                is VoiceAudio.Result.CantRecord -> { DebugTools.log("voice note not sent: ${r.why}"); toast("This phone can't record voice notes.") }
                            }
                        }
                    }.start()
                }
            }
        })
    }
}
