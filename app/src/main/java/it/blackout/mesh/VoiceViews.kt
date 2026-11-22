package it.blackout.mesh

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Voice notes on screen (board `chat`): the bubble, the mic button that records while held, and the strip that shows the
// recording. Shared by the phone and the desktop; the desktop's VoiceAudio can neither record nor play, so it offers no
// mic and its bubble says "Play it on a phone". The rules of the bytes are in VoiceNotes.kt.

/** A recording in progress, as the composer shows it. */
class VoiceRec {
    var on by mutableStateOf(false)
    /** Ten seconds are recorded: the timer stays at 0:10 and the note goes when the finger lifts. */
    var full by mutableStateOf(false)
}

/**
 * The bubble's content for a voice note that may be played ([Voice.playable]): the play/pause button, 20 bars from the
 * decoded sound and the length ("0:07"). On a device that can't play voice notes (a computer): "Voice note, 0:07" and
 * "Play it on a phone". [hold] opens the message's menu, as holding the rest of the bubble does.
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
    val progress = if (playing) VoiceAudio.progress else 0f
    Column(Modifier.padding(start = 10.dp, end = 16.dp, top = 9.dp, bottom = 9.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = Voice.spoken(secs) }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (onWhite) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.13f))
                .then(if (preview || failed) Modifier else Modifier.combinedClickable(onClickLabel = if (playing) "Pause" else "Play", onLongClick = hold, onLongClickLabel = "Message options") {
                    Buzz.play(Buzz.Tap)
                    VoiceAudio.toggle(m.id, frames) { failed = true; Toast.makeText(ctx, "Can't play this voice note.", Toast.LENGTH_SHORT).show() }
                }), contentAlignment = Alignment.Center) {
                Ic(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow, ink, 18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Bars(levels, progress, ink)
            Spacer(Modifier.width(10.dp))
            Txt(Voice.label(secs), 14, FontWeight.SemiBold, ink)
        }
        if (failed) Txt("Can't play this voice note.", 13, color = ink.copy(alpha = 0.6f), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

/** 20 bars, 2.5 dp wide, up to 18 dp tall; those already played are drawn full, the rest dimmer (board `.wv`). */
@Composable
private fun Bars(levels: FloatArray?, progress: Float, ink: Color) = Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
    for (i in 0 until 20) {
        val l = levels?.getOrNull(i) ?: 0f
        val played = progress > 0f && (i + 0.5f) / 20f <= progress
        Box(Modifier.width(2.5.dp).height((4 + 14 * l).dp).clip(RoundedCornerShape(2.dp)).background(ink.copy(alpha = if (played) 1f else 0.5f)))
    }
}

/** Under a message whose text says it is a voice note but that has none this phone can play (an ignored field). */
@Composable
fun CantPlayNote(onWhite: Boolean) =
    Txt("Can't play this voice note.", 13, color = (if (onWhite) Color.Black else Theme.text).copy(alpha = 0.6f), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))

/**
 * The composer's line while recording (board `.rec`): a red dot, the time so far and "Slide away to cancel". The time is
 * what the microphone has given, so it stops at 0:10.
 */
@Composable
fun RecordingStrip(rec: VoiceRec, modifier: Modifier = Modifier) {
    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(rec.on) { while (rec.on) { ms = VoiceAudio.recordedMs(); delay(100) } }
    val secs = (ms / 1000).toInt().coerceAtMost(10)
    val blink by produceState(1f, rec.on) { var on = true; while (true) { delay(500); on = !on; value = if (on) 1f else 0.3f } }
    Row(modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Theme.tile).padding(horizontal = 18.dp)
        .semantics(mergeDescendants = true) { contentDescription = "Recording, ${Voice.label(secs)}. Slide away to cancel" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Theme.help.copy(alpha = if (rec.full) 1f else blink), CircleShape))
        Spacer(Modifier.width(10.dp))
        Txt(Voice.label(secs), 16, FontWeight.SemiBold)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_chevron_left, Theme.dim, 16.dp); Spacer(Modifier.width(2.dp))
            Txt("Slide away to cancel", 14, color = Theme.dim, maxLines = 1)
        }
    }
}

/**
 * The send button while the message box is empty: a mic. Holding it records ([begin] starts and says whether it did),
 * letting go sends, sliding off it (a little past its edge) cancels: [end] gets true to send, false to drop. While
 * recording it grows and turns red (board `.recording .mic`).
 */
@Composable
fun MicButton(rec: VoiceRec, begin: () -> Boolean, end: (Boolean) -> Unit) {
    val starts by rememberUpdatedState(begin); val ends by rememberUpdatedState(end)
    val grow by animateFloatAsState(if (rec.on) 1.22f else 1f, Motion.round(), label = "mic")
    Box(Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp).semantics { contentDescription = "Hold to record a voice note" }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                if (!starts()) { waitForUpOrCancellation(); return@awaitEachGesture }
                var keep = true
                try {
                    val margin = 24.dp.toPx()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id }
                        if (c == null) { keep = false; break }
                        if (!c.pressed) break
                        val p = c.position
                        if (p.x < -margin || p.y < -margin || p.x > size.width + margin || p.y > size.height + margin) { keep = false; break }
                        c.consume()
                    }
                } catch (e: CancellationException) { ends(false); throw e }
                ends(keep)
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp).scale(grow).background(if (rec.on) Theme.help else Theme.tile, CircleShape).litEdge(CircleShape),
            contentAlignment = Alignment.Center) {
            Ic(R.drawable.ic_mic, if (rec.on) Color.White else Theme.text, 22.dp)
        }
    }
}

/**
 * What the composer needs for voice notes: the recording's state, and what holding and letting go of the mic do. Asks
 * for the microphone the first time (with the reason first), shows high-risk mode's line once before the first recording,
 * and sends the note as a reply when one is being written. [replying] and [sent] are read when the finger lifts.
 */
class VoiceComposer(val rec: VoiceRec, val begin: () -> Boolean, val end: (Boolean) -> Unit)

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
    if (why) Confirm("Use the microphone?", "To record a voice note for your group while you hold the mic button. Nothing is recorded at any other time.", "Continue",
        onYes = { why = false; VoiceAudio.notedMicAsked(ctx); ask() }, onNo = { why = false })
    if (off) Confirm("The microphone is off", "DarkProof can't use the microphone. Turn it on in Settings to record voice notes.", "Open Settings",
        onYes = { off = false; VoiceAudio.openSettings(a) }, onNo = { off = false })
    if (risk) Confirm("A voice can identify you.", "Even to someone who later gets a phone from your group.", "Record anyway",
        onYes = { risk = false; ctx.getSharedPreferences("mesh", 0).edit().putBoolean("voiceRiskSeen", true).apply(); toast("Hold the mic to record") },
        onNo = { risk = false })
    // Nothing records or plays behind a closed app or a chat that was left.
    WhileOnScreen { { VoiceAudio.stopPlaying(); if (rec.on) { rec.on = false; VoiceAudio.cancelRecording() } } }
    return remember {
        VoiceComposer(rec, begin = {
            when {
                !meshReady(ctx) -> false
                s.highRisk && !ctx.getSharedPreferences("mesh", 0).getBoolean("voiceRiskSeen", false) -> { risk = true; false }
                !VoiceAudio.micAllowed(ctx) -> { if (VoiceAudio.canAskMic(a)) why = true else off = true; false }
                !VoiceAudio.startRecording(ctx) { rec.full = true; Buzz.play(Buzz.Confirm) } -> { toast("The microphone can't be used right now"); false }
                else -> { Buzz.play(Buzz.Hold); rec.full = false; rec.on = true; true }
            }
        }, end = { keep ->
            if (rec.on) {
                rec.on = false
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
