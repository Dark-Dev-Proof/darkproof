package it.blackout.mesh

/**
 * Ten-second voice notes (round 2c, rules V1 to V11, with the four amendments decided on 2026-11-22 after the codec
 * experiment). Only the rules here: what a voice note's bytes must be, how long it is, what a writer keeps, and the
 * decoder header built from constants. Plain Kotlin, so the phone and the desktop compile and test the same rules;
 * recording and playing are the phone's own (VoiceAudio.kt; the desktop has no codec and shows "Play it on a phone").
 *
 * The 4th plaintext field of a group message (after `sender`, `name`, `text`):
 *   `0x02` ‖ repeated ( len (1 byte, 1 to 255) ‖ one Opus packet of len bytes, RFC 6716 )
 * - every packet mono (TOC `s` = 0) and 20 ms (TOC config 1, 5, 9, 13, 15, 19, 23, 27 or 31), and either one frame
 *   (TOC code `c` = 0) or code 3 holding exactly one frame (2nd byte `v` = 0, `M` = 1, with or without padding):
 *   constant-bitrate encoders (Apple's, libopus) write the second kind for about half of all packets;
 * - 50 to 500 packets (1 to 10 s), at most 14000 bytes after the type byte, parsed exactly to the end;
 * - the `text` field exactly [PLACEHOLDER] (older builds show that, and nothing else can stand beside the sound);
 * - group messages only: never on the Public channel, never on LoRa (the packet is far too big for it anyway);
 * - shown only from a packet that opened with a group key and whose signature checked ([playable]).
 * Anything else: the field is ignored, nothing of it is decoded, and the message shows as its text.
 *
 * The raw packets carry no Opus header: the format fixes the encoder's start-up delay at [PRE_SKIP] samples at 48 kHz
 * (6.5 ms), and a decoder that wants a header (Android's MediaCodec) is given one built from the constants ([opusHead]).
 *
 * What is not proven yet (say so, test it on real phones): that an iPhone's encoder behaves like the Mac's in the
 * experiment, and what each Android vendor's MediaCodec Opus encoder writes. A writer therefore checks its encoder's real
 * output with the same rules before it sends ([forSending]) and sends nothing it would not accept itself.
 */
object Voice {
    /** The 4th field's type byte for a voice note (`0x01` is a JPEG, [Photo.JPEG]). */
    const val TYPE: Byte = 0x02
    /** The message's `text` field, exactly (V7). */
    const val PLACEHOLDER = "🎤 Voice note"
    /** The most bytes after the type byte (V5, the same limit as a photo). */
    const val MAX_BYTES = 14_000
    /** 1 s and 10 s of 20 ms packets (V4). */
    const val MIN_PACKETS = 50
    const val MAX_PACKETS = 500
    const val PACKET_MS = 20
    /** The longest packet a `len` byte can name (and the longest a writer may send). */
    const val MAX_PACKET = 255
    /** Opus always decodes at 48 kHz here; the samples dropped at the start (the encoder's look-ahead), fixed by the format. */
    const val SAMPLE_RATE = 48_000
    const val PRE_SKIP = 312
    /** What a writer asks its encoder for: variable bitrate at this many bits per second, 20 ms frames, mono, 48 kHz input. */
    const val BIT_RATE = 8_000
    /** The 20 ms configs of each mode and bandwidth (RFC 6716 table 2): SILK NB/MB/WB, Hybrid SWB/FB, CELT NB/WB/SWB/FB. */
    val CONFIGS = setOf(1, 5, 9, 13, 15, 19, 23, 27, 31)

    /** True if [p] is a packet a voice note may hold (amended V3): mono, 20 ms, one frame (code 0, or code 3 with one). */
    fun accepts(p: ByteArray): Boolean {
        if (p.isEmpty() || p.size > MAX_PACKET) return false
        val toc = p[0].toInt() and 0xFF
        if ((toc ushr 3) !in CONFIGS || (toc ushr 2) and 1 != 0) return false
        return when (toc and 3) {
            0 -> true
            3 -> {
                if (p.size < 2) return false
                val b = p[1].toInt() and 0xFF
                if (b ushr 7 != 0 || b and 0x3F != 1) return false // v = 0 (constant-size frames), M = 1 frame
                var i = 2; var padding = 0
                if ((b ushr 6) and 1 == 1) { // RFC 6716 §3.2.5: padding length bytes, 255 meaning 254 and another byte follows
                    while (true) {
                        if (i >= p.size) return false
                        val n = p[i++].toInt() and 0xFF
                        if (n == 255) padding += 254 else { padding += n; break }
                    }
                }
                p.size - i - padding >= 0 // what is left is the one frame (it may be empty, as in RFC 6716)
            }
            else -> false // code 1 and 2: two frames
        }
    }

    /**
     * The packets of a 4th field, type byte included, or null if it isn't a voice note this app reads (V6): it must parse
     * exactly to the end, every `len` from 1, every packet [accepts], 50 to 500 of them, at most 14000 bytes after the type.
     */
    fun packets(field: ByteArray?): List<ByteArray>? {
        if (field == null || field.size < 2 || field[0] != TYPE || field.size - 1 > MAX_BYTES) return null
        val out = ArrayList<ByteArray>(); var i = 1
        while (i < field.size) {
            val n = field[i].toInt() and 0xFF
            if (n == 0 || i + 1 + n > field.size || out.size == MAX_PACKETS) return null
            val p = field.copyOfRange(i + 1, i + 1 + n)
            if (!accepts(p)) return null
            out += p; i += 1 + n
        }
        return if (out.size < MIN_PACKETS) null else out
    }

    /**
     * What a reader keeps of a message's 4th field [extra] (type byte included) with text [text]: the frames after the
     * type byte if they are a voice note ([packets]) and the text is exactly [PLACEHOLDER], else null.
     */
    fun of(extra: ByteArray?, text: ByteArray): ByteArray? =
        if (extra != null && extra.size > 1 && extra[0] == TYPE && text.contentEquals(PLACEHOLDER.toByteArray(Charsets.UTF_8)) && packets(extra) != null)
            extra.copyOfRange(1, extra.size) else null

    /** The frames (`len` ‖ packet, repeated) for [packets]; no check. */
    fun frames(packets: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in packets) { require(p.size in 1..MAX_PACKET) { "packet of ${p.size} bytes" }; out.write(p.size); out.write(p) }
        return out.toByteArray()
    }

    /** The packets of [frames] as a message holds them ([Message.voice], no type byte), or null if they aren't a voice note. */
    fun split(frames: ByteArray): List<ByteArray>? = packets(byteArrayOf(TYPE) + frames)

    /** What a writer may send of what its encoder wrote. */
    sealed class Kept {
        class Ok(val packets: List<ByteArray>) : Kept() { val frames: ByteArray get() = frames(packets) }
        /** Under 1 s once cut. */
        object TooShort : Kept()
        /** A packet a reader would refuse (wrong length, stereo, not 20 ms, more than one frame): nothing is sent. */
        class Refused(val index: Int, val packet: ByteArray) : Kept()
    }

    /**
     * What a writer keeps of [encoded] (amended V4 and V8): the first 500 packets (ten seconds come out of an encoder as 501:
     * start-up delay and the final flush), then as many from the start as keep the field within 14000 bytes (an encoder
     * that overshoots makes a shorter note, never an oversized one). Every kept packet must pass [accepts]; if one doesn't,
     * the encoder writes what readers refuse and nothing is sent ("This phone can't record voice notes.").
     */
    fun forSending(encoded: List<ByteArray>): Kept {
        val kept = ArrayList<ByteArray>(); var size = 0
        for ((i, p) in encoded.withIndex()) {
            if (kept.size == MAX_PACKETS) break
            if (!accepts(p)) return Kept.Refused(i, p)
            if (size + 1 + p.size > MAX_BYTES) break
            kept += p; size += 1 + p.size
        }
        return if (kept.size < MIN_PACKETS) Kept.TooShort else Kept.Ok(kept)
    }

    /** How long [count] packets play, in milliseconds. */
    fun durationMs(count: Int) = count * PACKET_MS

    /** The length shown, whole seconds rounded half up, at least 1 (V4): 50 to 500 packets give 1 to 10. */
    fun seconds(count: Int) = maxOf(1, (durationMs(count) + 500) / 1000)

    /** "0:07". */
    fun label(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    /** What a screen reader says: "Voice note, 7 seconds". */
    fun spoken(seconds: Int) = "Voice note, $seconds second${if (seconds == 1) "" else "s"}"

    /** True if [m]'s voice note may be shown and played (V9, V10): a group message whose signature checked. */
    fun playable(m: Message) = m.voice != null && !m.public && m.group != null && m.verified

    // MARK: - Decoder setup from constants (amendment 4)

    /** The 19-byte Opus identification header (RFC 7845 §5.1): version 1, 1 channel, pre-skip 312, 48000 Hz, gain 0, family 0. */
    fun opusHead(): ByteArray = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1) + le(PRE_SKIP.toLong(), 2) + le(SAMPLE_RATE.toLong(), 4) + le(0, 2) + byteArrayOf(0)

    /** The encoder's start-up delay in nanoseconds (MediaCodec's `csd-1`): 312 samples at 48 kHz = 6,500,000 ns. */
    const val CODEC_DELAY_NS = PRE_SKIP * 1_000_000_000L / SAMPLE_RATE
    /** How far before a seek point decoding starts (MediaCodec's `csd-2`): 80 ms, RFC 7845's recommendation. */
    const val SEEK_PRE_ROLL_NS = 80_000_000L

    /** [v] as [n] bytes, little-endian (MediaCodec's `csd-1` and `csd-2` are 8 bytes each). */
    fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }

    /** The PCM samples [decoded] 500 packets should give: 20 ms each at 48 kHz, less the pre-skip. */
    fun expectedSamples(count: Int) = count * (SAMPLE_RATE / 1000 * PACKET_MS) - PRE_SKIP

    // MARK: - The bubble's picture

    /** [bars] levels from 0 to 1 for the bubble's picture, from decoded [pcm] (computed on this phone; nothing extra is sent). */
    fun levels(pcm: ShortArray, bars: Int = 20): FloatArray {
        if (pcm.isEmpty()) return FloatArray(bars)
        val rms = FloatArray(bars) { b ->
            val from = (pcm.size.toLong() * b / bars).toInt(); val to = maxOf(from + 1, (pcm.size.toLong() * (b + 1) / bars).toInt()).coerceAtMost(pcm.size)
            var sum = 0.0; for (i in from until to) { val s = pcm[i] / 32768.0; sum += s * s }
            Math.sqrt(sum / (to - from)).toFloat()
        }
        val top = rms.maxOrNull()?.takeIf { it > 0f } ?: return FloatArray(bars)
        return FloatArray(bars) { (rms[it] / top).coerceIn(0f, 1f) }
    }

    // MARK: - Playing and recording on screen (seek, speed, record lock): this phone only, nothing here is sent

    /** The speeds the bubble's chip goes through, in order. One choice for the whole app, kept on this phone. */
    val SPEEDS = floatArrayOf(1f, 1.5f, 2f)

    /** A kept speed, or 1 if it isn't one of [SPEEDS] (nothing kept yet). */
    fun speedOf(kept: Float): Float = SPEEDS.firstOrNull { it == kept } ?: 1f

    /** The speed after [s] when the chip is tapped: 1×, 1.5×, 2×, then 1× again. */
    fun nextSpeed(s: Float): Float = SPEEDS[(SPEEDS.indexOfFirst { it == s } + 1) % SPEEDS.size]

    /** The chip: "1×", "1.5×", "2×". */
    fun speedLabel(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"

    /** What a screen reader says for the chip: "Playback speed, normal", "Playback speed, 1.5 times". */
    fun speedSpoken(s: Float): String = "Playback speed, " + if (s == 1f) "normal" else speedLabel(s).dropLast(1) + " times"

    /** Where a finger at [x] on bars [width] wide puts the note: 0 (its start) to 1 (its end). */
    fun seekAt(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    /** A screen reader's adjust gesture on the bars moves the note by a tenth, to the nearest tenth. */
    fun stepped(at: Float, forward: Boolean): Float = (Math.round(at * 10) + if (forward) 1 else -1).coerceIn(0, 10) / 10f

    /** What a screen reader says for where a note of [count] packets is at [at] (0 to 1): "3 of 7 seconds". */
    fun positionSpoken(at: Float, count: Int): String {
        val total = seconds(count)
        val now = ((at.coerceIn(0f, 1f) * durationMs(count)).toInt() + 500) / 1000
        return "${minOf(now, total)} of $total second${if (total == 1) "" else "s"}"
    }

    /** How far above the mic button's top edge the finger slides to lock a recording (dp on Android, points on iPhone). */
    const val LOCK_REACH = 72
    /** How far beside or below the mic button the finger may go before the recording is cancelled; beside it once the finger is above it, a little more. */
    const val CANCEL_MARGIN = 24
    const val CANCEL_MARGIN_ABOVE = 40

    /** What the finger on the mic does to a recording. */
    enum class Slide { HOLD, LOCK, CANCEL }

    /** [Slide] for a finger at [x], [y] (from the button's top left corner) on a mic button [w] by [h]; [dp]: pixels per dp. */
    fun slide(x: Float, y: Float, w: Float, h: Float, dp: Float = 1f): Slide {
        val side = (if (y < 0f) CANCEL_MARGIN_ABOVE else CANCEL_MARGIN) * dp
        return when {
            x < -side || x > w + side || y > h + CANCEL_MARGIN * dp -> Slide.CANCEL
            -y >= LOCK_REACH * dp -> Slide.LOCK
            else -> Slide.HOLD
        }
    }

    /** How near the finger is to the lock, 0 to 1, for the lock's picture. */
    fun lift(y: Float, dp: Float = 1f): Float = (-y / (LOCK_REACH * dp)).coerceIn(0f, 1f)
}
