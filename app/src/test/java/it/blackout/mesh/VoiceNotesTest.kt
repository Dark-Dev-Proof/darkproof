package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ten-second voice notes (round 2c, rules V1 to V11 with the amendments of 2026-11-22): the 4th field's bytes, every
 * rejection, the voice fields Apple's encoder made in the codec experiment, a voice reply, what an older build shows,
 * and that a voice note is an ordinary group message for the Public channel, LoRa and high-risk mode. Dummy keys only.
 * The fixtures in app/src/test/voice are the experiment's files as they came (501 packets, one more than allowed).
 */
class VoiceNotesTest {
    private val code = "test-group-voice"
    private val group by lazy { GroupCrypto(code) }
    private val iv = ByteArray(12) { 9 }
    private val sig = ByteArray(64) { 7 }
    private val anna = "1111111111111111"; private val ben = "2222222222222222"
    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    /** [n] packets of [size] bytes with first byte [toc] (and second byte [second] for code 3). */
    private fun packets(n: Int, size: Int = 20, toc: Int = 0x08, second: Int? = null) = List(n) { i ->
        ByteArray(size) { j -> when { j == 0 -> toc.toByte(); j == 1 && second != null -> second.toByte(); else -> (i * 31 + j).toByte() } }
    }
    private fun field(ps: List<ByteArray>) = byteArrayOf(Voice.TYPE) + Voice.frames(ps)
    private val apple = listOf("cbr-16000", "cbr-48000", "default-16000", "default-48000", "vbr-16000", "vbr-48000")
    private fun appleFile(name: String) = File("../app/src/test/voice/apple-8k-$name.voicefield").readBytes()
    /** The experiment's packets as they are, without the format's checks. */
    private fun rawSplit(field: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(); var i = 1
        while (i < field.size) { val n = field[i].toInt() and 0xFF; out += field.copyOfRange(i + 1, i + 1 + n); i += 1 + n }
        assertEquals("the experiment's files parse to their end", field.size, i)
        return out
    }

    // MARK: - The bytes

    @Test fun theConstantsAreTheFormats() {
        assertEquals(0x02.toByte(), Voice.TYPE); assertEquals(0x01.toByte(), Photo.JPEG)
        assertEquals("🎤 Voice note", Voice.PLACEHOLDER)
        assertEquals(14_000, Voice.MAX_BYTES); assertEquals(50, Voice.MIN_PACKETS); assertEquals(500, Voice.MAX_PACKETS)
        assertEquals(312, Voice.PRE_SKIP); assertEquals(48_000, Voice.SAMPLE_RATE); assertEquals(8_000, Voice.BIT_RATE)
        // The TOC first bytes allowed: the 20 ms configs, mono, code 0 or 3.
        val firsts = (0..255).filter { Voice.accepts(byteArrayOf(it.toByte(), 0x01, 0x00)) || Voice.accepts(byteArrayOf(it.toByte())) }
        assertEquals(listOf(0x08, 0x0b, 0x28, 0x2b, 0x48, 0x4b, 0x68, 0x6b, 0x78, 0x7b, 0x98, 0x9b, 0xb8, 0xbb, 0xd8, 0xdb, 0xf8, 0xfb), firsts)
    }

    @Test fun theDecoderHeaderIsBuiltFromConstants() {
        assertEquals("4f707573486561640101380180bb0000000000", Hex.encode(Voice.opusHead()))
        assertEquals(19, Voice.opusHead().size)
        assertEquals(6_500_000L, Voice.CODEC_DELAY_NS); assertEquals("a02e630000000000", Hex.encode(Voice.le(Voice.CODEC_DELAY_NS, 8)))
        assertEquals(80_000_000L, Voice.SEEK_PRE_ROLL_NS); assertEquals("00b4c40400000000", Hex.encode(Voice.le(Voice.SEEK_PRE_ROLL_NS, 8)))
        assertEquals(479_688, Voice.expectedSamples(500))
    }

    @Test fun lengthIsPacketsTimes20MsInWholeSeconds() {
        assertEquals(1, Voice.seconds(50)); assertEquals(10, Voice.seconds(500)); assertEquals(7, Voice.seconds(350))
        assertEquals(2, Voice.seconds(75)); assertEquals(1, Voice.seconds(74)) // 1.5 s rounds up, 1.48 s down
        assertEquals(10_000, Voice.durationMs(500))
        assertEquals("0:07", Voice.label(7)); assertEquals("0:10", Voice.label(10))
        assertEquals("Voice note, 7 seconds", Voice.spoken(7)); assertEquals("Voice note, 1 second", Voice.spoken(1))
    }

    @Test fun aFieldParsesExactlyToItsEnd() {
        val ps = packets(350)
        assertEquals(350, Voice.packets(field(ps))!!.size)
        val cbr = packets(250, toc = 0x0b, second = 0x01) + packets(250, toc = 0x0b, second = 0x41).map { it.also { p -> p[2] = 3 } } // code 3, one frame, 3 bytes padding
        assertEquals(500, Voice.packets(field(cbr))!!.size)
        // At the limits: exactly 14000 bytes after the type byte, 56 packets of 249 bytes.
        assertEquals(56, Voice.packets(field(packets(56, 249)))!!.size)
        assertEquals(14_000, Voice.frames(packets(56, 249)).size)
    }

    /** Every rejection of V6 and the amendments: the field is ignored as a whole. */
    @Test fun everyBrokenRuleIgnoresTheField() {
        val good = field(packets(60))
        assertNotNull(Voice.packets(good))
        val cases = mapOf(
            "trailing byte (a len with nothing after it)" to good + byteArrayOf(5),
            "trailing len 1 cut off" to good + byteArrayOf(1),
            "len 0" to good + byteArrayOf(0),
            "stereo" to field(packets(59) + packets(1, toc = 0x0c)),
            "10 ms (config 0)" to field(packets(59) + packets(1, toc = 0x00)),
            "40 ms (config 2)" to field(packets(59) + packets(1, toc = 0x10)),
            "60 ms (config 3)" to field(packets(59) + packets(1, toc = 0x18)),
            "10 ms CELT (config 18)" to field(packets(59) + packets(1, toc = 0x90)),
            "code 1" to field(packets(59) + packets(1, toc = 0x09)),
            "code 2" to field(packets(59) + packets(1, toc = 0x0a)),
            "code 3 with two frames" to field(packets(59) + packets(1, toc = 0x0b, second = 0x02)),
            "code 3 with v = 1" to field(packets(59) + packets(1, toc = 0x0b, second = 0x81)),
            "code 3 with no second byte" to field(packets(59) + listOf(byteArrayOf(0x0b))),
            "code 3 padding longer than the packet" to field(packets(59) + listOf(byteArrayOf(0x0b, 0x41, 30, 1, 2, 3))),
            "code 3 padding length never ends" to field(packets(59) + listOf(byteArrayOf(0x0b, 0x41, 0xFF.toByte(), 0xFF.toByte()))),
            "49 packets" to field(packets(49)),
            "501 packets" to field(packets(501)),
            "14001 bytes after the type" to field(packets(55, 249) + packets(1, 250)),
            "another type" to byteArrayOf(0x03) + Voice.frames(packets(60)),
            "only the type" to byteArrayOf(Voice.TYPE),
        )
        for ((why, f) in cases) assertNull(why, Voice.packets(f))
        assertNull(Voice.packets(null))
    }

    @Test fun aWriterKeepsTheFirst500AndNothingOversized() {
        // Ten seconds come out of an encoder as 501 packets: the first 500 go.
        val k = Voice.forSending(packets(501)) as Voice.Kept.Ok
        assertEquals(500, k.packets.size); assertArrayEquals(packets(501)[499], k.packets[499])
        // V-V5: an encoder that overshoots (40-byte packets for 10 s) makes a shorter note, never one over 14000 bytes.
        val big = Voice.forSending(packets(501, 40)) as Voice.Kept.Ok
        assertEquals(14_000 / 41, big.packets.size); assertTrue(big.frames.size <= Voice.MAX_BYTES)
        assertNotNull(Voice.packets(byteArrayOf(Voice.TYPE) + big.frames))
        assertSame(Voice.Kept.TooShort, Voice.forSending(packets(49)))
        // A packet readers would refuse: nothing is sent at all.
        val bad = Voice.forSending(packets(20) + packets(1, toc = 0x18) + packets(100)) as Voice.Kept.Refused
        assertEquals(20, bad.index)
        assertTrue(Voice.forSending(packets(60) + listOf(ByteArray(256) { 0x08 })) is Voice.Kept.Refused) // longer than 255 bytes
    }

    // MARK: - Apple's encoder (the codec experiment's files)

    @Test fun theNotesApplesEncoderMadeAreReadAfterTheTrim() {
        for (name in apple) {
            val f = appleFile(name)
            val all = rawSplit(f)
            assertEquals("$name: the experiment kept every packet", 501, all.size)
            assertNull("$name: 501 packets, one too many", Voice.packets(f))
            val kept = Voice.forSending(all) as Voice.Kept.Ok
            assertEquals(name, 500, kept.packets.size)
            val field = byteArrayOf(Voice.TYPE) + kept.frames
            assertEquals(name, 500, Voice.packets(field)!!.size)
            assertEquals(10, Voice.seconds(500))
            assertTrue(name, field.size - 1 <= Voice.MAX_BYTES)
            if (name.startsWith("cbr")) assertTrue("$name has code 3 packets", kept.packets.any { it[0].toInt() and 3 == 3 })
            // Through a sealed group message and back.
            val c = group.seal("00000000000000b1", 9, anna, "Bea", Voice.PLACEHOLDER, iv = iv, voice = kept.frames) { sig }
            val m = group.openSigned(Packet("00000000000000b1", 9, 16, c))!!.message
            assertArrayEquals(name, kept.frames, m.voice); assertEquals(Voice.PLACEHOLDER, m.text); assertNull(m.photo)
        }
    }

    // MARK: - In a message

    @Test fun aVoiceNoteRoundTripsThroughBothSealings() {
        val frames = Voice.frames(packets(350, 18))
        val c = group.seal("00000000000000b2", 9, anna, "Bea", Voice.PLACEHOLDER, iv = iv, voice = frames) { sig }
        val o = group.openSigned(Packet("00000000000000b2", 9, 16, c))!!
        assertArrayEquals(frames, o.message.voice); assertNull(o.message.photo); assertNull(o.message.about)
        assertArrayEquals(byteArrayOf(Voice.TYPE) + frames, o.extra)
        assertEquals(7, Voice.seconds(Voice.split(o.message.voice!!)!!.size))
        // Sealed with a message key, as a member writes.
        val secret = ByteArray(32) { 0x21 }
        val e = Keyring.Epoch.fresh(3, 1_000, secret, listOf(anna, ben), listOf(anna))
        val r = Keyring.Epoch.fresh(3, 1_000, secret, listOf(anna, ben), listOf(anna))
        val id = "%04x".format(e.group.mailbox) + "0000000000b3"
        val cw = e.group.sealWith(e.messageKeys!!.key(1, 0)!!, 1, 0, id, 5_000, ben, "Ben", Voice.PLACEHOLDER, voice = frames) { TestKeys.sign(it) }
        val got = r.open(Packet(id, e.group.hideTime(5_000, cw), 16, cw))!!
        assertArrayEquals(frames, got.message.voice); assertEquals(ben, got.message.sender)
        // This phone's own copy (History) keeps it, and reads it with the same rules.
        val key = ByteArray(32) { 3 }
        val back = History.open(key, id, History.seal(key, id, "0123456789abcdef0123456789abcdef", "eid", got, 1, 0)!!)!!
        assertArrayEquals(frames, back.opened.message.voice)
        // Padded to a size class: 350 packets of 18 bytes (6650 bytes) fit 8192; ten seconds at 8 kbit/s go in the 16018 class.
        assertEquals(8192, Packet(id, 5_000, 16, cw).encode().size)
        val ten = group.seal("00000000000000b3", 9, anna, "Bea", Voice.PLACEHOLDER, voice = Voice.frames(packets(500, 20))) { sig }
        assertEquals(16_018, Packet("00000000000000b3", 9, 16, ten).encode().size)
    }

    @Test fun aVoiceReplyCarriesBothFields() {
        val target = About.Target.parse("00000000000000a1:5a5a5a5a5a5a5a5a")!!
        val frames = Voice.frames(packets(100))
        val c = group.seal("00000000000000b4", 9, anna, "Bea", Voice.PLACEHOLDER, iv = iv, about = About.Reply(target), voice = frames) { sig }
        val o = group.openSigned(Packet("00000000000000b4", 9, 16, c))!!
        assertArrayEquals(frames, o.message.voice); assertEquals(About.Reply(target), o.message.about)
        assertNotNull(o.aboutField)
    }

    @Test fun writersRefuseWhatReadersWouldIgnore() {
        val frames = Voice.frames(packets(100))
        fun refused(f: () -> Unit) = assertTrue(runCatching(f).exceptionOrNull() is IllegalArgumentException)
        refused { group.seal("00000000000000b5", 9, anna, "Bea", "Listen to this", voice = frames) { sig } }                      // other text
        refused { group.seal("00000000000000b5", 9, anna, "Bea", Voice.PLACEHOLDER, photo = byteArrayOf(1, 2), voice = frames) { sig } } // both
        refused { group.seal("00000000000000b5", 9, anna, "Bea", Voice.PLACEHOLDER, voice = Voice.frames(packets(49))) { sig } }     // too short
        refused { group.seal("00000000000000b5", 9, anna, "Bea", "", about = About.Reaction(About.Target.parse("00000000000000a1:5a5a5a5a5a5a5a5a")!!), voice = frames) { sig } }
    }

    // MARK: - Readers that ignore it

    private fun gcm(mode: Int, data: ByteArray, id: String, ts: Long, nonce: ByteArray = iv): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(GroupCrypto.deriveKey(code), "AES"), GCMParameterSpec(128, nonce)); updateAAD("$id|$ts".toByteArray()); doFinal(data)
    }
    private fun f(d: ByteArray) = byteArrayOf((d.size shr 8).toByte(), (d.size and 255).toByte()) + d
    /** A packet from a body made by hand, for fields this app never writes. */
    private fun sealRaw(text: String, fourth: ByteArray, id: String) =
        Packet(id, 9, 16, iv + gcm(Cipher.ENCRYPT_MODE, sig + f(utf8(anna)) + f(utf8("Bea")) + f(utf8(text)) + f(fourth), id, 9))

    @Test fun aBrokenFieldShowsOnlyTheText() {
        val bad = mapOf("trailing" to field(packets(60)) + byteArrayOf(5), "stereo" to field(packets(60, toc = 0x0c)), "501" to field(packets(501)),
            "text is not the placeholder" to field(packets(60)))
        bad.entries.forEachIndexed { i, (why, fourth) ->
            val text = if (why.startsWith("text")) "Hear this" else Voice.PLACEHOLDER
            val m = group.openSigned(sealRaw(text, fourth, "00000000000000c$i"))!!.message
            assertNull(why, m.voice); assertNull(why, m.photo); assertEquals(why, text, m.text)
        }
    }

    /** The 4th-field rule as builds before voice notes had it (Core.kt and MessageKeys.kt before 2026-11-22), frozen here. */
    private fun oldPhoto(extra: ByteArray?) = extra?.takeIf { it.size > 1 && it[0] == Photo.JPEG && it.size - 1 <= Photo.MAX_BYTES }?.copyOfRange(1, extra.size)

    @Test fun buildsWithoutTheFeatureShowOnlyTheText() {
        val c = group.seal("00000000000000d1", 9, anna, "Bea", Voice.PLACEHOLDER, iv = iv, voice = Voice.frames(packets(300))) { sig }
        val o = group.openSigned(Packet("00000000000000d1", 9, 16, c))!!
        assertNull("an older build finds no photo in it", oldPhoto(o.extra))
        assertEquals("and shows its text", "🎤 Voice note", o.message.text)
    }

    // MARK: - An ordinary group message everywhere else

    @Test fun neverOnThePublicChannel() {
        val m = Message("00000000000000e1", 9, anna, "Bea", Voice.PLACEHOLDER, group = "g", voice = Voice.frames(packets(60)))
        assertTrue(Voice.playable(m))
        assertNull("a Public channel reader keeps no voice note", m.onPublic().voice)
        assertFalse(Voice.playable(m.copy(public = true)))
        assertFalse("not from a packet whose signature checked", Voice.playable(m.copy(verified = false)))
        assertFalse("not from a group key", Voice.playable(m.copy(group = null)))
        assertFalse(Voice.playable(m.copy(voice = null)))
    }

    @Test fun neverOnLoRa() {
        val c = group.seal("00000000000000e2", 9, anna, "Bea", Voice.PLACEHOLDER, voice = Voice.frames(packets(50, 8))) { sig } // the shortest note there can be
        val p = Packet("00000000000000e2", System.currentTimeMillis(), 16, c)
        assertFalse(Meshtastic.fits(p)); assertFalse(MeshCore.fits(p))
        for (q in listOf(LoraQueue(), LoraQueue(maxBytes = MeshCore.PAYLOAD_MAX))) { q.offer(p, mine = true); assertNull("not queued for the radio", q.next()) }
    }

    /**
     * High-risk mode's 24-hour rule and its gone list go by what a packet opened as (MeshEngine.kept), never by what its
     * 4th field holds: a voice note opens as an ordinary group message (not silent, not Public), so the engine notes its
     * arrival like any other, it leaves a day later and its name stays on the gone list.
     */
    @Test fun highRiskModeTreatsItAsAGroupMessage() {
        val c = group.seal("00000000000000e3", 9, anna, "Bea", Voice.PLACEHOLDER, voice = Voice.frames(packets(60))) { sig }
        val m = group.openSigned(Packet("00000000000000e3", 9, 16, c))!!.message
        assertNull("not a reaction or other silent message", m.about); assertFalse(m.public)
        assertEquals(StoreCap.Kind.GROUP, StoreCap.received(true, m.public))
        val now = 1_800_000_000_000L; val hour = 3_600_000L
        val held = mapOf("00000000000000e3" to KeepLess.Held(now - 25 * hour, now - 25 * hour, "g"))
        assertEquals(listOf("00000000000000e3"), KeepLess.due(held, now))
        val gone = GoneList(); gone.add(listOf(GoneList.Entry("00000000000000e3", now - 25 * hour, "g")))
        assertEquals(GoneList.Arrival.PASS_ON, gone.arrival("00000000000000e3") { true })
    }

    @Test fun theBubblePictureIsLevelsFromZeroToOne() {
        val pcm = ShortArray(48_000) { i -> if (i < 24_000) 0 else ((i % 100) * 300 - 15_000).toShort() }
        val l = Voice.levels(pcm)
        assertEquals(20, l.size); assertEquals(0f, l[0]); assertEquals(1f, l.max())
        assertEquals(20, Voice.levels(ShortArray(0)).size)
    }

    // MARK: - Seek, speed and the record lock (the screens; the same cases as the iPhone's VoiceTests)

    @Test fun theSpeedChipGoesRoundAndIsKeptOnlyIfKnown() {
        assertEquals(1.5f, Voice.nextSpeed(1f)); assertEquals(2f, Voice.nextSpeed(1.5f)); assertEquals(1f, Voice.nextSpeed(2f))
        assertEquals(1f, Voice.nextSpeed(3f)) // from an unknown speed, back to 1×
        assertEquals(1f, Voice.speedOf(0f)); assertEquals(1f, Voice.speedOf(1.25f)); assertEquals(2f, Voice.speedOf(2f))
        assertEquals(listOf("1×", "1.5×", "2×"), Voice.SPEEDS.map { Voice.speedLabel(it) })
        assertEquals(listOf("Playback speed, normal", "Playback speed, 1.5 times", "Playback speed, 2 times"), Voice.SPEEDS.map { Voice.speedSpoken(it) })
    }

    @Test fun aFingerOnTheBarsSeeksAndAScreenReaderStepsByTenths() {
        assertEquals(0f, Voice.seekAt(-5f, 100f)); assertEquals(0.25f, Voice.seekAt(25f, 100f)); assertEquals(1f, Voice.seekAt(140f, 100f))
        assertEquals(0f, Voice.seekAt(10f, 0f))
        assertEquals(0.1f, Voice.stepped(0f, true)); assertEquals(0f, Voice.stepped(0f, false)); assertEquals(1f, Voice.stepped(1f, true))
        assertEquals(0.5f, Voice.stepped(0.43f, true)); assertEquals(0.3f, Voice.stepped(0.43f, false))
        var at = 0f; repeat(12) { at = Voice.stepped(at, true) }; assertEquals(1f, at)
        assertEquals("0 of 7 seconds", Voice.positionSpoken(0f, 350))
        assertEquals("3 of 7 seconds", Voice.positionSpoken(0.43f, 350))
        assertEquals("7 of 7 seconds", Voice.positionSpoken(1f, 340)) // 6.8 s shown as 7: the end says the whole length
        assertEquals("1 of 1 second", Voice.positionSpoken(1f, 50))
    }

    @Test fun slidingUpLocksSlidingAwayCancels() {
        val w = 52f; val h = 52f
        assertEquals(Voice.Slide.HOLD, Voice.slide(26f, 26f, w, h))
        assertEquals(Voice.Slide.HOLD, Voice.slide(26f, -71f, w, h))
        assertEquals(Voice.Slide.LOCK, Voice.slide(26f, -72f, w, h))
        assertEquals(Voice.Slide.LOCK, Voice.slide(-35f, -80f, w, h)) // a thumb drifts sideways on its way up
        assertEquals(Voice.Slide.CANCEL, Voice.slide(-41f, -80f, w, h))
        assertEquals(Voice.Slide.CANCEL, Voice.slide(-25f, 10f, w, h))
        assertEquals(Voice.Slide.CANCEL, Voice.slide(w + 25f, 10f, w, h))
        assertEquals(Voice.Slide.CANCEL, Voice.slide(26f, h + 25f, w, h))
        assertEquals(Voice.Slide.HOLD, Voice.slide(-24f, h + 24f, w, h))
        assertEquals(Voice.Slide.LOCK, Voice.slide(52f, -144f, 104f, 104f, dp = 2f)) // in pixels at 2 px per dp
        assertEquals(0f, Voice.lift(10f)); assertEquals(0.5f, Voice.lift(-36f)); assertEquals(1f, Voice.lift(-200f))
    }
}
