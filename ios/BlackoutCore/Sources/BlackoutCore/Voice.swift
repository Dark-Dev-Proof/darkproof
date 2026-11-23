import Foundation

/// Ten-second voice notes (spec/FORMAT.md, "Voice notes"; round 2c rules V1 to V11 with the four amendments of
/// 2026-11-22). Only the rules: what a voice note's bytes must be, how long it is, what a writer keeps, and the decoder
/// header built from constants. Same rules and numbers as Android's `object Voice` (VoiceNotes.kt); recording and
/// playing are the app's (Blackout/VoiceNotes.swift).
///
/// The 4th plaintext field of a group message (after `sender`, `name`, `text`):
///   `0x02` ‖ repeated ( len (1 byte, 1 to 255) ‖ one Opus packet of len bytes, RFC 6716 )
/// - every packet mono (TOC `s` = 0) and 20 ms (TOC config 1, 5, 9, 13, 15, 19, 23, 27 or 31), and either one frame
///   (TOC code `c` = 0) or code 3 holding exactly one frame (2nd byte `v` = 0, `M` = 1, with or without padding):
///   constant-bitrate encoders (Apple's, libopus) write the second kind for about half of all packets;
/// - 50 to 500 packets (1 to 10 s), at most 14000 bytes after the type byte, parsed exactly to the end;
/// - the `text` field exactly `placeholder` (older builds show that, and nothing else can stand beside the sound);
/// - group messages only: never on the Public channel, never on LoRa (the packet is far too big for it anyway);
/// - shown only from a packet that opened with a group key and whose signature checked (`playable`).
/// Anything else: the field is ignored, nothing of it is decoded, and the message shows as its text.
///
/// The raw packets carry no Opus header: the format fixes the encoder's start-up delay at `preSkip` samples at 48 kHz
/// (6.5 ms); a decoder that wants a header is given one built from the constants (`opusHead`).
///
/// Not proven yet (test it on real phones): that an iPhone's encoder behaves like the Mac's in the codec experiment, and
/// what each Android vendor's encoder writes. A writer therefore checks its encoder's real output with these rules before
/// it sends (`forSending`) and sends nothing it would not accept itself.
public enum Voice {
    /// The 4th field's type byte for a voice note (`0x01` is a JPEG, `Photo.jpeg`).
    public static let type: UInt8 = 0x02
    /// The message's `text` field, exactly (V7).
    public static let placeholder = "🎤 Voice note"
    /// The most bytes after the type byte (V5, the same limit as a photo).
    public static let maxBytes = 14_000
    /// 1 s and 10 s of 20 ms packets (V4).
    public static let minPackets = 50
    public static let maxPackets = 500
    public static let packetMs = 20
    /// The longest packet a `len` byte can name (and the longest a writer may send).
    public static let maxPacket = 255
    /// Opus always decodes at 48 kHz here; the samples dropped at the start (the encoder's look-ahead), fixed by the format.
    public static let sampleRate = 48_000
    public static let preSkip = 312
    /// What a writer asks its encoder for: variable bitrate at this many bits per second, 20 ms frames, mono, 48 kHz input.
    public static let bitRate = 8_000
    /// The 20 ms configs of each mode and bandwidth (RFC 6716 table 2): SILK NB/MB/WB, Hybrid SWB/FB, CELT NB/WB/SWB/FB.
    public static let configs: Set<Int> = [1, 5, 9, 13, 15, 19, 23, 27, 31]

    /// True if `p` is a packet a voice note may hold (amended V3): mono, 20 ms, one frame (code 0, or code 3 with one).
    public static func accepts(_ p: Data) -> Bool { accepts([UInt8](p)) }

    static func accepts(_ p: [UInt8]) -> Bool {
        guard !p.isEmpty, p.count <= maxPacket else { return false }
        let toc = Int(p[0])
        guard configs.contains(toc >> 3), (toc >> 2) & 1 == 0 else { return false }
        switch toc & 3 {
        case 0: return true
        case 3:
            guard p.count >= 2 else { return false }
            let b = Int(p[1])
            guard b >> 7 == 0, b & 0x3F == 1 else { return false } // v = 0 (constant-size frames), M = 1 frame
            var i = 2, padding = 0
            if (b >> 6) & 1 == 1 { // RFC 6716 §3.2.5: padding length bytes, 255 meaning 254 and another byte follows
                while true {
                    guard i < p.count else { return false }
                    let n = Int(p[i]); i += 1
                    if n == 255 { padding += 254 } else { padding += n; break }
                }
            }
            return p.count - i - padding >= 0 // what is left is the one frame (it may be empty, as in RFC 6716)
        default: return false // code 1 and 2: two frames
        }
    }

    /// The packets of a 4th field, type byte included, or nil if it isn't a voice note this app reads (V6): it must parse
    /// exactly to the end, every `len` from 1, every packet `accepts`, 50 to 500 of them, at most 14000 bytes after the type.
    public static func packets(_ field: Data?) -> [Data]? {
        guard let field else { return nil }
        let f = [UInt8](field)
        guard f.count >= 2, f[0] == type, f.count - 1 <= maxBytes else { return nil }
        var out: [Data] = [], i = 1
        while i < f.count {
            let n = Int(f[i])
            guard n != 0, i + 1 + n <= f.count, out.count < maxPackets else { return nil }
            let p = Array(f[(i + 1)..<(i + 1 + n)])
            guard accepts(p) else { return nil }
            out.append(Data(p)); i += 1 + n
        }
        return out.count < minPackets ? nil : out
    }

    /// What a reader keeps of a message's 4th field `extra` (type byte included) with text `text`: the frames after the
    /// type byte if they are a voice note (`packets`) and the text is exactly `placeholder`, else nil.
    public static func of(extra: Data?, text: Data) -> Data? {
        guard let extra, extra.count > 1, extra.first == type, text == Data(placeholder.utf8), packets(extra) != nil else { return nil }
        return Data(extra.dropFirst())
    }

    /// The frames (`len` ‖ packet, repeated) for `packets`; no check beyond the length of each.
    public static func frames(_ packets: [Data]) -> Data {
        var out = Data()
        for p in packets {
            precondition((1...maxPacket).contains(p.count), "packet of \(p.count) bytes")
            out.append(UInt8(p.count)); out.append(p)
        }
        return out
    }

    /// The packets of `frames` as a message holds them (`Message.voice`, no type byte), or nil if they aren't a voice note.
    public static func split(_ frames: Data) -> [Data]? { packets(Data([type]) + frames) }

    /// What a writer may send of what its encoder wrote.
    public enum Kept: Equatable {
        case ok([Data])
        /// Under 1 s once cut.
        case tooShort
        /// A packet a reader would refuse (wrong length, stereo, not 20 ms, more than one frame): nothing is sent.
        case refused(index: Int, packet: Data)

        /// The frames to send, for `.ok`.
        public var frames: Data? { if case .ok(let p) = self { return Voice.frames(p) } else { return nil } }
    }

    /// What a writer keeps of `encoded` (amended V4 and V8): the first 500 packets (ten seconds come out of an encoder as
    /// 501: start-up delay and the final flush), then as many from the start as keep the field within 14000 bytes (an
    /// encoder that overshoots makes a shorter note, never an oversized one). Every kept packet must pass `accepts`; if one
    /// doesn't, the encoder writes what readers refuse and nothing is sent ("This phone can't record voice notes.").
    public static func forSending(_ encoded: [Data]) -> Kept {
        var kept: [Data] = [], size = 0
        for (i, p) in encoded.enumerated() {
            if kept.count == maxPackets { break }
            if !accepts(p) { return .refused(index: i, packet: p) }
            if size + 1 + p.count > maxBytes { break }
            kept.append(p); size += 1 + p.count
        }
        return kept.count < minPackets ? .tooShort : .ok(kept)
    }

    /// How long `count` packets play, in milliseconds.
    public static func durationMs(_ count: Int) -> Int { count * packetMs }

    /// The length shown, whole seconds rounded half up, at least 1 (V4): 50 to 500 packets give 1 to 10.
    public static func seconds(_ count: Int) -> Int { max(1, (durationMs(count) + 500) / 1000) }

    /// "0:07".
    public static func label(_ seconds: Int) -> String { String(format: "%d:%02d", seconds / 60, seconds % 60) }

    /// What a screen reader says: "Voice note, 7 seconds".
    public static func spoken(_ seconds: Int) -> String { "Voice note, \(seconds) second\(seconds == 1 ? "" : "s")" }

    /// True if `m`'s voice note may be shown and played (V9, V10): a group message whose signature checked.
    public static func playable(_ m: Message) -> Bool { m.voice != nil && !m.isPublic && m.group != nil && m.verified }

    // MARK: - Decoder setup from constants (amendment 4)

    /// The 19-byte Opus identification header (RFC 7845 §5.1): version 1, 1 channel, pre-skip 312, 48000 Hz, gain 0, family 0.
    public static func opusHead() -> Data {
        Data("OpusHead".utf8) + Data([1, 1]) + le(Int64(preSkip), 2) + le(Int64(sampleRate), 4) + le(0, 2) + Data([0])
    }

    /// The encoder's start-up delay in nanoseconds: 312 samples at 48 kHz = 6,500,000 ns.
    public static let codecDelayNs = Int64(preSkip) * 1_000_000_000 / Int64(sampleRate)
    /// How far before a seek point decoding starts: 80 ms, RFC 7845's recommendation.
    public static let seekPreRollNs: Int64 = 80_000_000

    /// `v` as `n` bytes, little-endian.
    public static func le(_ v: Int64, _ n: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: v >> (8 * $0)) }) }

    /// The PCM samples `count` packets decode to: 20 ms each at 48 kHz, less the pre-skip.
    public static func expectedSamples(_ count: Int) -> Int { count * (sampleRate / 1000 * packetMs) - preSkip }

    // MARK: - The bubble's picture

    /// `bars` levels from 0 to 1 for the bubble's picture, from decoded `pcm` (computed on this phone; nothing extra is sent).
    public static func levels(_ pcm: [Float], bars: Int = 20) -> [Float] {
        guard !pcm.isEmpty else { return Array(repeating: 0, count: bars) }
        let rms: [Float] = (0..<bars).map { b in
            let from = pcm.count * b / bars, to = min(pcm.count, max(from + 1, pcm.count * (b + 1) / bars))
            var sum = 0.0
            for i in from..<to { sum += Double(pcm[i]) * Double(pcm[i]) }
            return Float((sum / Double(to - from)).squareRoot())
        }
        guard let top = rms.max(), top > 0 else { return Array(repeating: 0, count: bars) }
        return rms.map { min(1, max(0, $0 / top)) }
    }

    // MARK: - Playing and recording on screen (seek, speed, record lock): this phone only, nothing here is sent

    /// The speeds the bubble's chip goes through, in order. One choice for the whole app, kept on this phone.
    public static let speeds: [Float] = [1, 1.5, 2]

    /// A kept speed, or 1 if it isn't one of `speeds` (nothing kept yet).
    public static func speed(of kept: Float) -> Float { speeds.contains(kept) ? kept : 1 }

    /// The speed after `s` when the chip is tapped: 1×, 1.5×, 2×, then 1× again.
    public static func nextSpeed(_ s: Float) -> Float { speeds[((speeds.firstIndex(of: s) ?? -1) + 1) % speeds.count] }

    /// The chip: "1×", "1.5×", "2×".
    public static func speedLabel(_ s: Float) -> String { (s == s.rounded() ? String(Int(s)) : String(s)) + "×" }

    /// What a screen reader says for the chip: "Playback speed, normal", "Playback speed, 1.5 times".
    public static func speedSpoken(_ s: Float) -> String { "Playback speed, " + (s == 1 ? "normal" : String(speedLabel(s).dropLast()) + " times") }

    /// Where a finger at `x` on bars `width` wide puts the note: 0 (its start) to 1 (its end).
    public static func seekAt(_ x: Double, width: Double) -> Double { width <= 0 ? 0 : min(1, max(0, x / width)) }

    /// A screen reader's adjust gesture on the bars moves the note by a tenth, to the nearest tenth.
    public static func stepped(_ at: Double, forward: Bool) -> Double { Double(min(10, max(0, Int((at * 10).rounded()) + (forward ? 1 : -1)))) / 10 }

    /// What a screen reader says for where a note of `count` packets is at `at` (0 to 1): "3 of 7 seconds".
    public static func positionSpoken(_ at: Double, count: Int) -> String {
        let total = seconds(count)
        let now = (Int(min(1, max(0, at)) * Double(durationMs(count))) + 500) / 1000
        return "\(min(now, total)) of \(total) second\(total == 1 ? "" : "s")"
    }

    /// How far above the mic button's top edge the finger slides to lock a recording (points on iPhone, dp on Android).
    public static let lockReach: Double = 72
    /// How far beside or below the mic button the finger may go before the recording is cancelled; beside it once the
    /// finger is above it, a little more.
    public static let cancelMargin: Double = 24
    public static let cancelMarginAbove: Double = 40

    /// What the finger on the mic does to a recording.
    public enum Slide: Equatable { case hold, lock, cancel }

    /// `Slide` for a finger at `x`, `y` (from the button's top left corner) on a mic button `width` by `height`.
    public static func slide(x: Double, y: Double, width: Double, height: Double) -> Slide {
        let side = y < 0 ? cancelMarginAbove : cancelMargin
        if x < -side || x > width + side || y > height + cancelMargin { return .cancel }
        return -y >= lockReach ? .lock : .hold
    }

    /// How near the finger is to the lock, 0 to 1, for the lock's picture.
    public static func lift(y: Double) -> Double { min(1, max(0, -y / lockReach)) }
}
