package it.blackout.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's side of voice notes ([Voice] holds the rules): the microphone while the button is held, the system's Opus
 * encoder and decoder (MediaCodec), and the speaker. The desktop has its own VoiceAudio with no codec.
 *
 * - Recording needs the system's Opus encoder, which Android has from version 10 (API 29). On Android 8 and 9 no record
 *   control is offered at all; playing works there (the decoder is in Android from 5.0).
 * - The microphone is used only from the press of the mic button until the note is sent or cancelled (on release, or
 *   with Send or Cancel once locked hands-free; at most 10 s), and only while the app is on screen. The sound is kept
 *   in memory, encoded, and sealed like any message. Nothing is written to a file, nothing is transcribed. Decoded
 *   sound is held only while a note is shown or played.
 * - Playing: one note at a time; a paused note keeps its place, a finger on the bars moves it, and the speed chip's
 *   choice (1×, 1.5×, 2×, Android's own time stretch, pitch kept) is one for the whole app.
 * - What the encoder writes is checked with the readers' rules before anything is sent ([Voice.forSending]); a phone whose
 *   encoder writes packets readers refuse sends nothing and says "This phone can't record voice notes."
 *
 * Not knowable without real phones: what each vendor's encoder writes (VBR or CBR, frame size, whether it honours 8000 bit/s);
 * the emulator runs Android's own software encoder (c2.android.opus.encoder).
 */
object VoiceAudio {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
    private val main = Handler(Looper.getMainLooper())
    /** The longest one encode or decode of a note (at most 10 s of sound) may take: far more than any phone needs. */
    private const val CODEC_LIMIT_NS = 10_000_000_000L

    /** True if this phone can record a voice note: Android 10 or newer, with an Opus encoder. */
    val canRecord: Boolean by lazy { Build.VERSION.SDK_INT >= 29 && runCatching { encoderName(Voice.SAMPLE_RATE) != null }.getOrDefault(false) }
    /** Every Android this app runs on decodes Opus. */
    val canPlay = true

    private fun encoderName(rate: Int): String? = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(MediaFormat.createAudioFormat(MIME, rate, 1))

    // MARK: - Encoding

    /** What the encoder wrote: its packets (codec-config buffers left out), and how it was set up. */
    class Encoded(val packets: List<ByteArray>, val codec: String, val rate: Int, val vbrAsked: Boolean, val vbrTaken: Boolean)

    /**
     * Encodes mono 16-bit [pcm] at [rate] Hz with the system's Opus encoder: variable bitrate at 8000 bit/s, 20 ms frames
     * (the format's amended V8). Every output buffer is one packet; buffers flagged as codec config are skipped.
     * Throws if the phone has no encoder or it fails.
     */
    fun encode(pcm: ShortArray, rate: Int): Encoded {
        val name = encoderName(rate) ?: error("no Opus encoder for $rate Hz")
        fun format(vbr: Boolean) = MediaFormat.createAudioFormat(MIME, rate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, Voice.BIT_RATE)
            if (vbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rate / 50 * 2 * 4)
        }
        val codec = MediaCodec.createByCodecName(name)
        val out = ArrayList<ByteArray>()
        var vbrOk = false; var vbrTaken = false
        // Everything after the codec exists is inside the try, so it is always released (Android review, finding 6).
        try {
            vbrOk = runCatching { codec.getCodecInfo().getCapabilitiesForType(MIME).encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) }.getOrDefault(false)
            vbrTaken = vbrOk && runCatching { codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }.isSuccess
            if (!vbrTaken) {
                if (vbrOk) runCatching { codec.reset() } // a refused configure can leave it in its error state: back to the start first
                codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            codec.start()
            val frame = rate / 50 // 20 ms
            var fed = 0; var inputDone = false; val info = MediaCodec.BufferInfo()
            val until = System.nanoTime() + CODEC_LIMIT_NS
            while (true) {
                check(System.nanoTime() < until) { "the encoder didn't finish in time" } // one that never ends its stream holds no thread for ever
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!.also { it.clear() }
                        val n = minOf(frame, pcm.size - fed, buf.remaining() / 2)
                        val pts = fed * 1_000_000L / rate
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(pcm, fed, n)
                            buf.put(bb); codec.queueInputBuffer(i, 0, n * 2, pts, 0); fed += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val b = codec.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size)
                        out += ByteArray(info.size).also { b.get(it) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { runCatching { codec.stop() }; codec.release() }
        return Encoded(out, name, rate, vbrOk, vbrTaken)
    }

    // MARK: - Decoding

    /** Decoded sound: 48 kHz mono 16-bit, the pre-skip already dropped by the decoder; [errors]: packets the decoder refused. */
    class Decoded(val pcm: ShortArray, val errors: Int, val rate: Int, val channels: Int)

    /**
     * Decodes [packets] with the system's Opus decoder, set up from constants (the format's amendment 4): OpusHead with
     * version 1, 1 channel, pre-skip 312, 48000 Hz, gain 0, family 0, as `csd-0`; the codec delay (6.5 ms) and the seek
     * pre-roll (80 ms) in nanoseconds as `csd-1` and `csd-2`. One packet per input buffer, 20 ms apart.
     */
    fun decode(packets: List<ByteArray>): Decoded {
        val fmt = MediaFormat.createAudioFormat(MIME, Voice.SAMPLE_RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(Voice.opusHead()))
            setByteBuffer("csd-1", ByteBuffer.wrap(Voice.le(Voice.CODEC_DELAY_NS, 8)))
            setByteBuffer("csd-2", ByteBuffer.wrap(Voice.le(Voice.SEEK_PRE_ROLL_NS, 8)))
        }
        val codec = MediaCodec.createDecoderByType(MIME)
        val out = java.io.ByteArrayOutputStream(); var errors = 0; var rate = Voice.SAMPLE_RATE; var channels = 1
        try {
            codec.configure(fmt, null, null, 0); codec.start()
            var next = 0; var inputDone = false; val info = MediaCodec.BufferInfo()
            val until = System.nanoTime() + CODEC_LIMIT_NS
            while (true) {
                check(System.nanoTime() < until) { "the decoder didn't finish in time" }
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (next == packets.size) { codec.queueInputBuffer(i, 0, 0, next * 20_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val p = packets[next]; codec.getInputBuffer(i)!!.apply { clear(); put(p) }
                            codec.queueInputBuffer(i, 0, p.size, next * 20_000L, 0); next++
                        }
                    }
                }
                val o = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: MediaCodec.CodecException) { errors++; if (!e.isTransient) throw e; continue }
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let { f ->
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        if (info.size > 0) { val b = codec.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size); out.write(ByteArray(info.size).also { b.get(it) }) }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally { runCatching { codec.stop() }; codec.release() }
        val bytes = out.toByteArray()
        val pcm = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        return Decoded(pcm, errors, rate, channels)
    }

    /**
     * The last few notes decoded, by message id: shown notes draw their picture from it, and playing one needs no second
     * decode. A decode that ends after it was emptied ([stopPlaying], [forget]) isn't kept ([TurnKept]).
     */
    private val decoded = TurnKept<ShortArray>(4)

    /** The sound of a note, decoded once (off the main thread), or null if it can't be played. */
    fun pcm(id: String, frames: ByteArray): ShortArray? = decoded.get(id) {
        Voice.split(frames)?.let { ps -> runCatching { decode(ps) }.getOrNull()?.takeIf { it.pcm.isNotEmpty() && it.channels == 1 && it.rate == Voice.SAMPLE_RATE }?.pcm }
    }

    /** The bubbles' levels of many notes, so a note scrolled back into view isn't decoded again (Android review, finding 12). */
    private val levelsKept = VoiceLevels()

    /** The bubble's 20 levels, or null if the note can't be decoded here. Blocks: call it off the main thread. */
    fun levels(id: String, frames: ByteArray): FloatArray? = levelsKept.get(id) { pcm(id, frames)?.let { Voice.levels(it) } }

    /**
     * A group was left, or everything is being erased (re-check screens 4): the decoded sound and the bubbles' levels of
     * every note go, with message ids, and a decode still running keeps nothing when it ends. Any thread.
     */
    fun forget() { decoded.clear(); levelsKept.clear() }

    // MARK: - Playing: one note at a time

    /** The id of the note playing now, and how far it is (0 to 1). Read by the bubbles. */
    var playing by mutableStateOf<String?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    /**
     * Where paused notes are (0 to 1), by message id: a note paused, or moved by a finger on its bars while silent, goes
     * on from there. Forgotten when the chat is left (main thread only).
     */
    private val paused = mutableStateMapOf<String, Float>()
    private var track: AudioTrack? = null
    private var trackFrames = 0
    @Volatile private var turn = 0
    /** Where the note being started begins (0 to 1): read once its sound is decoded, so a finger moving it meanwhile counts. */
    @Volatile private var startAt = 0f

    /** How far note [id] is (0 to 1): playing, paused, or moved there by a finger. */
    fun position(id: String): Float = if (playing == id) progress else paused[id] ?: 0f

    /** Plays the note [id] from where it is, or pauses it if it is the one playing. Any other note pauses first. [failed]: it can't be played. */
    fun toggle(id: String, frames: ByteArray, failed: () -> Unit) {
        if (playing == id) { pause(); return }
        play(id, frames, paused[id] ?: 0f, failed)
    }

    /** Moves note [id] to [to] (0 to 1, a finger on its bars): the one playing jumps there and goes on; any other waits there, silent. */
    fun seek(id: String, to: Float) {
        val f = to.coerceIn(0f, 1f)
        if (playing != id) { if (f > 0f) paused[id] = f else paused.remove(id); return }
        progress = f; startAt = f
        synchronized(this) {
            val t = track ?: return // still being decoded: it starts at startAt
            // A static track's head moves only while it is paused or stopped.
            runCatching { t.pause(); t.setPlaybackHeadPosition(minOf((f * trackFrames).toInt(), trackFrames - 1)); t.play() }
        }
    }

    private fun play(id: String, frames: ByteArray, from: Float, failed: () -> Unit) {
        pause()
        paused.remove(id)
        val mine = ++turn
        playing = id; progress = from; startAt = from
        Thread {
            val pcm = pcm(id, frames)
            if (pcm == null) { main.post { if (turn == mine) { playing = null; progress = 0f; failed() } }; return@Thread }
            val t = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Voice.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                    .also { it.write(pcm, 0, pcm.size) }
            }.getOrNull()
            if (t == null) { main.post { if (turn == mine) { playing = null; progress = 0f; failed() } }; return@Thread }
            synchronized(this) {
                if (turn != mine) { t.release(); return@Thread }
                val at = (startAt * pcm.size).toInt()
                // Moved to its very end before it started: nothing is left to play, so it is done.
                if (pcm.size - at < Voice.SAMPLE_RATE / 20) { t.release(); main.post { if (turn == mine) halt() }; return@Thread }
                val started = runCatching { if (at > 0) t.setPlaybackHeadPosition(at); tune(t); t.play() }.isSuccess
                if (!started) { t.release(); main.post { if (turn == mine) { playing = null; progress = 0f; failed() } }; return@Thread }
                track = t; trackFrames = pcm.size
            }
            while (true) {
                val at = runCatching { t.playbackHeadPosition }.getOrDefault(pcm.size)
                val f = (at.toFloat() / pcm.size).coerceIn(0f, 1f)
                main.post { if (turn == mine) progress = f }
                if (at >= pcm.size || turn != mine) break
                Thread.sleep(50)
            }
            main.post { if (turn == mine) halt() } // played to the end: back to its start
        }.start()
    }

    /** Pauses the note playing: it keeps its place. */
    private fun pause() {
        val id = playing ?: return
        val at = progress
        halt()
        if (at > 0f && at < 1f) paused[id] = at
    }

    private fun halt() {
        turn++
        synchronized(this) { track?.let { runCatching { it.stop() }; it.release() }; track = null }
        playing = null; progress = 0f
    }

    /**
     * Stops whatever note is playing and forgets where paused ones were (the chat was left, the app went to the background,
     * a recording started). The decoded sound goes too: it is held only while a note is shown or played.
     */
    fun stopPlaying() {
        halt()
        paused.clear()
        decoded.clear()
    }

    // MARK: - Speed: the bubble's chip, one choice for the whole app

    /** The chip's choice once tapped in this run (0 until then: the settings say). Bubbles read it, so all chips change together. */
    private val tapped = mutableFloatStateOf(0f)
    @Volatile private var playSpeed = 1f

    /** The speed notes play at (1, 1.5 or 2), as kept in this phone's settings. Main thread; the chips call it as they are drawn. */
    fun speed(c: Context): Float =
        (tapped.floatValue.takeIf { it > 0f } ?: Voice.speedOf(c.getSharedPreferences("mesh", 0).getFloat("voiceSpeed", 1f))).also { playSpeed = it }

    /** The chip was tapped: the next speed, kept, and heard at once if a note is playing. */
    fun cycleSpeed(c: Context) {
        val s = Voice.nextSpeed(speed(c))
        tapped.floatValue = s; playSpeed = s
        c.getSharedPreferences("mesh", 0).edit().putFloat("voiceSpeed", s).apply()
        synchronized(this) { track?.let { tune(it) } }
    }

    /** The track's speed, its pitch kept (Android's own time stretch). A phone that refuses it plays at 1×. */
    private fun tune(t: AudioTrack) { runCatching { t.playbackParams = t.playbackParams.setSpeed(playSpeed) } }

    // MARK: - Recording

    /** Sound fed in place of the microphone, 48 kHz mono: set only by the debug build's test hook (DebugTools), never in a release build. */
    @Volatile var fakeInput: ShortArray? = null

    private var recorder: Thread? = null
    /** The recording under way: its own sound and its own stop ([VoiceTake]). */
    @Volatile private var take: VoiceTake? = null
    private var rate = Voice.SAMPLE_RATE

    /** True if the microphone may be used (or the debug build's feed stands in for it). */
    fun micAllowed(c: Context) = fakeInput != null || c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts recording into memory, at most 10 s; [full] is called (on the main thread) when the 10 s are up. False if the
     * microphone can't be opened. The caller has checked [canRecord] and the permission.
     */
    @SuppressLint("MissingPermission") // asked for before (VoiceViews' MicButton), checked again here
    fun startRecording(c: Context, full: () -> Unit): Boolean {
        cancelRecording()
        stopPlaying()
        val fake = fakeInput
        if (!micAllowed(c)) return false
        val r: AudioRecord? = if (fake != null) null else {
            // 48 kHz, as the format asks writers to; 16 kHz if this phone's microphone can't (the encoder takes either).
            listOf(Voice.SAMPLE_RATE, 16_000).firstNotNullOfOrNull { hz ->
                val min = AudioRecord.getMinBufferSize(hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) null else runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, hz / 5 * 2)) }
                    .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }?.also { rate = hz }
            } ?: return false
        }
        if (fake != null) rate = Voice.SAMPLE_RATE
        val cap = rate * Voice.MAX_PACKETS * Voice.PACKET_MS / 1000 // 10 s
        val t = VoiceTake(cap); take = t
        recorder = Thread {
            val chunk = ShortArray(rate / 50)
            // Nothing in here may end the app (Android review, finding 5): a microphone that fails mid-way ends the
            // recording, with what it gave so far. The thread writes only into its own take, so a read that comes back after
            // the recording was finished or cancelled lands where nobody reads.
            try {
                r?.startRecording()
                var fakeAt = 0; val started = System.nanoTime()
                while (!t.stop && !t.full) {
                    val n = if (r != null) r.read(chunk, 0, minOf(chunk.size, t.room)) else {
                        // The debug feed comes in real time, as a microphone's would.
                        val due = ((System.nanoTime() - started) / 1_000_000_000.0 * rate).toInt()
                        if (due <= fakeAt) { Thread.sleep(5); 0 } else {
                            val m = minOf(due - fakeAt, chunk.size, t.room)
                            for (k in 0 until m) chunk[k] = fake!!.getOrElse(fakeAt + k) { 0 }
                            fakeAt += m; m
                        }
                    }
                    if (n < 0) break
                    t.add(chunk, n)
                }
            } catch (e: Exception) { DebugTools.log("voice: the recording stopped: $e") }
            finally { runCatching { r?.stop() }; runCatching { r?.release() } }
            if (t.full && !t.stop) main.post(full)
        }.also { it.start() }
        return true
    }

    /** How much is recorded so far, in milliseconds. */
    fun recordedMs(): Long = (take?.count ?: 0) * 1000L / rate

    /** What a finished recording became. */
    sealed class Result {
        class Ready(val frames: ByteArray, val seconds: Int) : Result()
        /** Under a second: "Hold the mic to record". */
        object TooShort : Result()
        /** The encoder wrote what readers refuse, or failed: "This phone can't record voice notes." */
        class CantRecord(val why: String) : Result()
    }

    /** Stops recording and encodes what was recorded, checked with the readers' rules. Blocks: call it off the main thread. */
    fun finishRecording(): Result {
        val th = recorder ?: return Result.TooShort
        val t = take
        t?.stop = true; th.join(2_000); recorder = null; take = null
        val pcm = t?.copy() ?: ShortArray(0) // what it gave until now, even from a recorder that hasn't ended yet
        if (pcm.size < rate) return Result.TooShort
        return forSending(pcm, rate)
    }

    /** [pcm] encoded and checked with the readers' rules: what a recording becomes. Blocks. */
    fun forSending(pcm: ShortArray, rate: Int): Result {
        val e = runCatching { encode(pcm, rate) }.getOrElse { return Result.CantRecord("encoder failed: ${it.message}") }
        return when (val k = Voice.forSending(e.packets)) {
            is Voice.Kept.Ok -> Result.Ready(k.frames, Voice.seconds(k.packets.size))
            Voice.Kept.TooShort -> if (e.packets.size >= Voice.MIN_PACKETS) Result.CantRecord("packets too big") else Result.TooShort
            is Voice.Kept.Refused -> Result.CantRecord("packet ${k.index} refused, first byte %02x".format(k.packet.firstOrNull() ?: 0))
        }
    }

    /** Stops recording and drops what was recorded ("Slide away to cancel", the app left). */
    fun cancelRecording() {
        take?.stop = true; recorder?.join(2_000); recorder = null; take = null
    }

    /** True while the microphone is open. */
    val recording: Boolean get() = recorder != null

    /** The phone's own question for the microphone, as a function that asks it; [done] gets the answer. */
    @Composable
    fun rememberMicAsk(done: (Boolean) -> Unit): () -> Unit {
        val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), done)
        return { l.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** True if the phone would still show its own question (false once it was refused for good). */
    fun canAskMic(a: MainActivity): Boolean = !a.getSharedPreferences("mesh", 0).getBoolean("micAsked", false) || a.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    fun notedMicAsked(c: Context) = c.getSharedPreferences("mesh", 0).edit().putBoolean("micAsked", true).apply()
    fun openSettings(a: MainActivity) { a.openAppSettings() }
}
