package it.blackout.mesh

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * The desktop's VoiceAudio: no codec (no library was added, the owner's choice of 2026-11-22), so a computer neither
 * records nor plays voice notes. It reads, stores and passes them on with the phone's own code (VoiceNotes.kt), and its
 * bubble says "Voice note, 0:07" and "Play it on a phone". Nothing here is ever called with sound.
 */
object VoiceAudio {
    const val canRecord = false
    const val canPlay = false
    val playing: String? get() = null
    val progress: Float get() = 0f
    fun toggle(id: String, frames: ByteArray, failed: () -> Unit) = failed()
    fun stopPlaying() {}
    fun levels(id: String, frames: ByteArray): FloatArray? = null

    sealed class Result {
        class Ready(val frames: ByteArray, val seconds: Int) : Result()
        object TooShort : Result()
        class CantRecord(val why: String) : Result()
    }
    fun micAllowed(c: Context) = false
    fun startRecording(c: Context, full: () -> Unit) = false
    fun recordedMs() = 0L
    fun finishRecording(): Result = Result.CantRecord("a computer records no voice notes")
    fun cancelRecording() {}
    @Composable fun rememberMicAsk(done: (Boolean) -> Unit): () -> Unit = { done(false) }
    fun canAskMic(a: MainActivity) = false
    fun notedMicAsked(c: Context) {}
    fun openSettings(a: MainActivity) {}
}
