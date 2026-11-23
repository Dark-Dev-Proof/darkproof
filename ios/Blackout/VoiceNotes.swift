import SwiftUI
import AVFAudio
import AudioToolbox
import BlackoutCore

// The iPhone's side of voice notes (`Voice` in BlackoutCore holds the rules): the microphone while the button is held,
// the system's Opus encoder and decoder (AVAudioConverter, kAudioFormatOpus), the speaker, and the pieces of the chat
// that show them.
//
// - The microphone is used only from the press of the mic button until the note is sent or cancelled (on release, or
//   with Send or Cancel once locked hands-free; at most 10 s), and only while the chat is on screen. The sound is kept
//   in memory, encoded, and sealed like any message. Nothing is written to a file, nothing is transcribed. Decoded
//   sound is held only while a note is shown or played.
// - What the encoder writes is checked with the readers' rules before anything is sent (`Voice.forSending`, then
//   `Voice.packets` of the field); a phone whose encoder writes packets readers refuse sends nothing and says "This phone
//   can't record voice notes."
// - Not knowable without a real iPhone: whether its encoder behaves like the Mac's (the Simulator uses the Mac's
//   AudioToolbox), real microphone input, headsets and interruptions.

/// Apple's Opus codec as the format uses it: 48 kHz, mono, 20 ms packets (960 frames), variable bitrate at 8000 bit/s.
enum OpusCodec {
    static let pcm = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(Voice.sampleRate), channels: 1, interleaved: false)!
    static let frames = AVAudioFrameCount(Voice.sampleRate / 1000 * Voice.packetMs) // 960

    static func opus() -> AVAudioFormat? {
        var a = AudioStreamBasicDescription()
        a.mSampleRate = Double(Voice.sampleRate); a.mFormatID = kAudioFormatOpus; a.mChannelsPerFrame = 1; a.mFramesPerPacket = frames
        return AVAudioFormat(streamDescription: &a)
    }

    /// True if this phone has an Opus encoder.
    static let canEncode: Bool = opus().flatMap { AVAudioConverter(from: pcm, to: $0) } != nil

    struct Failed: Error { let why: String }

    /// Encodes 48 kHz mono `samples`: every packet the encoder writes, as it writes them (the writer trims, `Voice.forSending`).
    static func encode(_ samples: [Float]) throws -> [Data] {
        guard let ofmt = opus(), let conv = AVAudioConverter(from: pcm, to: ofmt) else { throw Failed(why: "no Opus encoder") }
        conv.bitRateStrategy = AVAudioBitRateStrategy_Variable
        conv.bitRate = Voice.bitRate
        guard let input = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(max(samples.count, 1))) else { throw Failed(why: "no buffer") }
        samples.withUnsafeBufferPointer { input.floatChannelData![0].update(from: $0.baseAddress!, count: samples.count) }
        input.frameLength = AVAudioFrameCount(samples.count)
        let out = AVAudioCompressedBuffer(format: ofmt, packetCapacity: 1024, maximumPacketSize: max(conv.maximumOutputPacketSize, 1500))
        var packets: [Data] = [], fed = false
        while true {
            out.packetCount = 0; out.byteLength = 0
            var err: NSError?
            let st = conv.convert(to: out, error: &err) { _, s in
                if fed { s.pointee = .endOfStream; return nil }
                fed = true; s.pointee = .haveData; return input
            }
            if let err { throw Failed(why: "encoder: \(err.localizedDescription)") }
            let base = out.data.assumingMemoryBound(to: UInt8.self)
            for k in 0..<Int(out.packetCount) {
                let d = out.packetDescriptions![k]
                packets.append(Data(bytes: base + Int(d.mStartOffset), count: Int(d.mDataByteSize)))
            }
            if st == .endOfStream || st == .error || (st == .inputRanDry && out.packetCount == 0) { break }
        }
        return packets
    }

    /// The sound of `packets`: 48 kHz mono, without the format's 312 samples of start-up delay. `raw`: how many samples
    /// Apple's decoder gave; it drops part of the delay itself, so the rest (up to `Voice.preSkip`) is dropped here, and
    /// 500 packets give `Voice.expectedSamples(500)` = 479,688 samples, as on Android. Nil if the decoder fails.
    static func decode(_ packets: [Data]) -> (pcm: [Float], raw: Int, errors: Int)? {
        guard let ifmt = opus(), let conv = AVAudioConverter(from: ifmt, to: pcm),
              let chunk = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: frames * 4) else { return nil }
        var all: [Float] = []; all.reserveCapacity(packets.count * Int(frames))
        var errors = 0
        func drain(_ next: @escaping () -> (AVAudioBuffer?, AVAudioConverterInputStatus)) -> Bool {
            while true {
                chunk.frameLength = 0
                var err: NSError?
                let st = conv.convert(to: chunk, error: &err) { _, s in let (b, x) = next(); s.pointee = x; return b }
                if err != nil || st == .error { return false }
                if chunk.frameLength > 0 { all.append(contentsOf: UnsafeBufferPointer(start: chunk.floatChannelData![0], count: Int(chunk.frameLength))) }
                if st != .haveData || chunk.frameLength == 0 { return true }
            }
        }
        for p in packets {
            let cb = AVAudioCompressedBuffer(format: ifmt, packetCapacity: 1, maximumPacketSize: max(p.count, 1))
            p.withUnsafeBytes { cb.data.copyMemory(from: $0.baseAddress!, byteCount: p.count) }
            cb.packetDescriptions![0] = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(p.count))
            cb.packetCount = 1; cb.byteLength = UInt32(p.count)
            var used = false
            if !drain({ if used { return (nil, .noDataNow) }; used = true; return (cb, .haveData) }) { errors += 1 }
        }
        _ = drain { (nil, .endOfStream) }
        guard !all.isEmpty, errors < packets.count else { return nil }
        let raw = all.count, over = raw - Voice.expectedSamples(packets.count)
        if over > 0 { all.removeFirst(min(over, Voice.preSkip)) }
        return (all, raw, errors)
    }

    /// Any audio file as 48 kHz mono samples (the debug build's stand-in for the microphone, and its fixture checks).
    static func samples(of url: URL) -> [Float]? {
        guard let f = try? AVAudioFile(forReading: url),
              let src = AVAudioPCMBuffer(pcmFormat: f.processingFormat, frameCapacity: AVAudioFrameCount(f.length)), (try? f.read(into: src)) != nil,
              let conv = AVAudioConverter(from: f.processingFormat, to: pcm),
              let out = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(Double(src.frameLength) * pcm.sampleRate / f.processingFormat.sampleRate) + 4096) else { return nil }
        var given = false, err: NSError?
        _ = conv.convert(to: out, error: &err) { _, s in
            if given { s.pointee = .endOfStream; return nil }
            given = true; s.pointee = .haveData; return src
        }
        guard err == nil else { return nil }
        return Array(UnsafeBufferPointer(start: out.floatChannelData![0], count: Int(out.frameLength)))
    }
}

/// Recording and playing, one at a time, for the whole app. Main thread, except where it says otherwise.
final class VoiceAudio: ObservableObject {
    static let shared = VoiceAudio()

    /// True if this phone can record a voice note: its Opus encoder exists.
    var canRecord: Bool { OpusCodec.canEncode }

    // MARK: - Playing: one note at a time

    /// The id of the note playing now, and how far it is (0 to 1).
    @Published private(set) var playing: String?
    @Published private(set) var progress: Double = 0
    /// Where paused notes are (0 to 1), by message id: a note paused, or moved by a finger on its bars while silent, goes
    /// on from there. Forgotten when the chat is left.
    @Published private var paused: [String: Double] = [:]
    /// The speed chip's choice (1, 1.5 or 2), one for the whole app, kept on this phone.
    @Published private(set) var speed: Float = Voice.speed(of: UserDefaults.standard.float(forKey: VoiceAudio.speedKey))
    static let speedKey = "voiceSpeed"
    private var player: Player?
    private var turn = 0
    /// Where the note being started begins (0 to 1): read once its sound is decoded, so a finger moving it meanwhile counts.
    private var startAt: Double = 0

    /// The engine playing one note: the player, then Apple's time pitch (speed with the pitch kept), then the speaker.
    private final class Player {
        let engine = AVAudioEngine(), node = AVAudioPlayerNode(), pitch = AVAudioUnitTimePitch()
        let pcm: [Float]
        /// The sample the part playing now began at, and which part it is (a seek starts a new one).
        var start = 0, part = 0
        var timer: Timer?
        init(pcm: [Float]) { self.pcm = pcm }
    }

    /// The last few notes decoded, by message id: shown notes draw their picture from it, and playing one needs no second decode.
    private let cacheLock = NSLock()
    private var cache: [(id: String, pcm: [Float])] = []

    /// The sound of a note, decoded once, or nil if it can't be played. Blocks: call it off the main thread.
    func pcm(id: String, frames: Data) -> [Float]? {
        if let hit = cacheLock.withLock({ cache.first { $0.id == id }?.pcm }) { return hit }
        guard let ps = Voice.split(frames), let d = OpusCodec.decode(ps) else { return nil }
        #if DEBUG
        DebugLog.write("voice decoded \(id): \(ps.count) packets -> \(d.raw) samples from the decoder, \(d.pcm.count) after the pre-skip (expected \(Voice.expectedSamples(ps.count))), errors \(d.errors)")
        #endif
        cacheLock.withLock { cache.removeAll { $0.id == id }; cache.append((id, d.pcm)); if cache.count > 4 { cache.removeFirst() } }
        return d.pcm
    }

    /// How far note `id` is (0 to 1): playing, paused, or moved there by a finger.
    func position(_ id: String) -> Double { playing == id ? progress : paused[id] ?? 0 }

    /// Plays the note `id` from where it is, or pauses it if it is the one playing. Any other note pauses first.
    func toggle(id: String, frames: Data, failed: @escaping () -> Void) {
        if playing == id { pause(); return }
        play(id: id, frames: frames, from: paused[id] ?? 0, failed: failed)
    }

    /// Moves note `id` to `to` (0 to 1, a finger on its bars): the one playing jumps there and goes on; any other waits
    /// there, silent.
    func seek(id: String, to: Double) {
        let to = min(1, max(0, to))
        guard playing == id else { paused[id] = to > 0 ? to : nil; return }
        progress = to; startAt = to
        if let p = player { schedule(p, from: Int(to * Double(p.pcm.count)), turn) } // else still being decoded: it starts at startAt
    }

    /// The chip was tapped: the next speed, kept, and heard at once if a note is playing.
    func cycleSpeed() {
        speed = Voice.nextSpeed(speed)
        UserDefaults.standard.set(speed, forKey: Self.speedKey)
        if let p = player { tune(p) }
    }

    /// At 1× the time pitch is left out of the sound (bypassed), so a note sounds exactly as before.
    private func tune(_ p: Player) { p.pitch.rate = speed; p.pitch.bypass = speed == 1 }

    private func play(id: String, frames: Data, from: Double, failed: @escaping () -> Void) {
        pause()
        paused[id] = nil
        turn += 1
        let mine = turn
        playing = id; progress = from; startAt = from
        DispatchQueue.global(qos: .userInitiated).async {
            let pcm = self.pcm(id: id, frames: frames)
            DispatchQueue.main.async {
                guard self.turn == mine else { return }
                guard let pcm, self.startPlayer(pcm, mine) else { self.playing = nil; self.progress = 0; failed(); return }
            }
        }
    }

    private func startPlayer(_ pcm: [Float], _ mine: Int) -> Bool {
        let p = Player(pcm: pcm)
        p.engine.attach(p.node); p.engine.attach(p.pitch)
        p.engine.connect(p.node, to: p.pitch, format: OpusCodec.pcm)
        p.engine.connect(p.pitch, to: p.engine.mainMixerNode, format: OpusCodec.pcm)
        tune(p)
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            try p.engine.start()
        } catch { return false }
        player = p
        let total = Double(pcm.count)
        p.timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self, weak p] _ in
            guard let self, let p, self.turn == mine, p.node.isPlaying, let t = p.node.lastRenderTime, let at = p.node.playerTime(forNodeTime: t) else { return }
            self.progress = min(1, max(0, Double(p.start + Int(at.sampleTime)) / total))
        }
        schedule(p, from: Int(startAt * total), mine)
        return true
    }

    /// Plays `p`'s note from sample `from` (where a seek lands too). With less than 50 ms left there is nothing to play:
    /// the note is done.
    private func schedule(_ p: Player, from: Int, _ mine: Int) {
        p.part += 1
        let part = p.part, start = max(0, from), n = p.pcm.count - start
        p.node.stop()
        p.pitch.reset() // the time pitch holds sound it took in before: without this a seek first replays it (checked on a Mac)
        guard n >= Voice.sampleRate / 20, let b = AVAudioPCMBuffer(pcmFormat: OpusCodec.pcm, frameCapacity: AVAudioFrameCount(n)) else { halt(); return }
        p.pcm.withUnsafeBufferPointer { b.floatChannelData![0].update(from: $0.baseAddress! + start, count: n) }
        b.frameLength = AVAudioFrameCount(n)
        p.start = start
        p.node.scheduleBuffer(b, completionCallbackType: .dataPlayedBack) { [weak self, weak p] _ in
            // Stopping the player for a seek also ends the part before it: only the part playing now ends the note.
            DispatchQueue.main.async { guard let self, let p, self.turn == mine, p.part == part else { return }; self.halt() }
        }
        p.node.play()
    }

    /// Pauses the note playing: it keeps its place.
    private func pause() {
        guard let id = playing else { return }
        let at = progress
        halt()
        if at > 0 && at < 1 { paused[id] = at }
    }

    private func halt() {
        turn += 1
        if let p = player { p.timer?.invalidate(); p.part += 1; p.node.stop(); p.engine.stop(); player = nil; deactivate() }
        if playing != nil { playing = nil }
        if progress != 0 { progress = 0 }
    }

    /// Stops whatever note is playing and forgets where paused ones were (the chat was left, the app went to the
    /// background, a recording started).
    func stopPlaying() {
        halt()
        if !paused.isEmpty { paused = [:] }
    }

    private func deactivate() { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }

    // MARK: - Recording

    #if DEBUG
    /// Sound fed in place of the microphone, 48 kHz mono, through the same path: set only by the debug build's launch
    /// argument `voiceFeed <file>` (BlackoutApp), never in a release build.
    var fakeInput: [Float]?
    #endif
    private var fake: [Float]? {
        #if DEBUG
        return fakeInput
        #else
        return nil
        #endif
    }

    /// Ten seconds at 48 kHz.
    private let cap = Voice.sampleRate * Voice.maxPackets * Voice.packetMs / 1000
    private let recLock = NSLock()
    private var recorded: [Float] = [] // recLock
    private var stopAsked = false      // recLock
    private var engine: AVAudioEngine?
    private var feeder: DispatchSourceTimer?
    @Published private(set) var recording = false

    /// What the microphone permission is: true granted, false refused, nil not asked yet (the debug feed counts as granted).
    var micAllowed: Bool? {
        if fake != nil { return true }
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .denied: return false
        default: return nil
        }
    }

    /// The phone's own question for the microphone (its text is the Info.plist's NSMicrophoneUsageDescription).
    func askMic(_ done: @escaping (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission { ok in DispatchQueue.main.async { done(ok) } }
    }

    /// Starts recording into memory, at most 10 s; `full` is called (main thread) when the 10 s are up. False if the
    /// microphone can't be opened. The caller has checked the permission.
    func startRecording(full: @escaping () -> Void) -> Bool {
        cancelRecording()
        stopPlaying()
        recLock.withLock { recorded = []; recorded.reserveCapacity(cap); stopAsked = false }
        let filled: () -> Void = { [weak self] in
            DispatchQueue.main.async { guard let self, self.recording else { return }; self.stopInput(); full() }
        }
        if let fake {
            // The debug feed comes in real time, as a microphone's would.
            let start = DispatchTime.now(), t = DispatchSource.makeTimerSource(queue: .global(qos: .userInitiated))
            var at = 0
            t.schedule(deadline: .now(), repeating: .milliseconds(20))
            t.setEventHandler { [weak self] in
                guard let self else { return }
                let due = Int(Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1e9 * Double(Voice.sampleRate))
                guard due > at else { return }
                let chunk = (at..<due).map { $0 < fake.count ? fake[$0] : 0 }
                at = due
                if self.append(chunk) { filled() }
            }
            feeder = t; t.resume()
        } else {
            let session = AVAudioSession.sharedInstance()
            do {
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
                try session.setPreferredSampleRate(Double(Voice.sampleRate))
                try session.setActive(true)
            } catch { return false }
            let e = AVAudioEngine(), input = e.inputNode, hw = input.outputFormat(forBus: 0)
            guard hw.sampleRate > 0, hw.channelCount > 0, let conv = AVAudioConverter(from: hw, to: OpusCodec.pcm) else { deactivate(); return false }
            input.installTap(onBus: 0, bufferSize: AVAudioFrameCount(hw.sampleRate / 10), format: hw) { [weak self] buf, _ in
                guard let self, let out = AVAudioPCMBuffer(pcmFormat: OpusCodec.pcm, frameCapacity: AVAudioFrameCount(Double(buf.frameLength) * OpusCodec.pcm.sampleRate / hw.sampleRate) + 64) else { return }
                var given = false, err: NSError?
                _ = conv.convert(to: out, error: &err) { _, s in
                    if given { s.pointee = .noDataNow; return nil }
                    given = true; s.pointee = .haveData; return buf
                }
                guard err == nil else { return }
                if self.append(Array(UnsafeBufferPointer(start: out.floatChannelData![0], count: Int(out.frameLength)))) { filled() }
            }
            do { try e.start() } catch { input.removeTap(onBus: 0); deactivate(); return false }
            engine = e
        }
        recording = true
        return true
    }

    /// Adds sound from the microphone (any thread). True once the 10 s are full.
    private func append(_ s: [Float]) -> Bool {
        recLock.withLock {
            guard !stopAsked, recorded.count < cap else { return false }
            recorded.append(contentsOf: s.prefix(cap - recorded.count))
            if recorded.count >= cap { stopAsked = true; return true }
            return false
        }
    }

    /// How much is recorded so far, in milliseconds (any thread).
    var recordedMs: Int { recLock.withLock { recorded.count } * 1000 / Voice.sampleRate }

    private func stopInput() {
        recLock.withLock { stopAsked = true }
        feeder?.cancel(); feeder = nil
        if let e = engine { e.inputNode.removeTap(onBus: 0); e.stop(); engine = nil; deactivate() }
    }

    /// What a finished recording became.
    enum Result {
        case ready(frames: Data, seconds: Int)
        /// Under a second: "Hold the mic to record".
        case tooShort
        /// The encoder wrote what readers refuse, or failed: "This phone can't record voice notes."
        case cantRecord(String)
    }

    /// Stops recording and hands back what was recorded; nil if nothing was recording.
    func finishRecording() -> [Float]? {
        guard recording else { return nil }
        stopInput()
        recording = false
        return recLock.withLock { defer { recorded = [] }; return recorded }
    }

    /// Stops recording and drops what was recorded ("Slide away to cancel", the chat or app left).
    func cancelRecording() {
        guard recording || engine != nil || feeder != nil else { return }
        stopInput()
        recording = false
        recLock.withLock { recorded = [] }
    }

    /// `pcm` (48 kHz mono) encoded and checked with the readers' rules: what a recording becomes. Blocks: off the main thread.
    static func forSending(_ pcm: [Float]) -> Result {
        if pcm.count < Voice.sampleRate { return .tooShort }
        let packets: [Data]
        do { packets = try OpusCodec.encode(pcm) } catch { return .cantRecord("encoder failed: \(error)") }
        #if DEBUG
        let sizes = packets.map(\.count), firsts = Dictionary(grouping: packets.compactMap(\.first), by: { $0 }).mapValues(\.count)
        DebugLog.write("voice encoded: \(pcm.count) samples -> \(packets.count) packets, bytes min/avg/max \(sizes.min() ?? 0)/\(sizes.isEmpty ? 0 : sizes.reduce(0, +) / sizes.count)/\(sizes.max() ?? 0), first bytes "
                       + firsts.sorted { $0.key < $1.key }.map { String(format: "%02x×%d", $0.key, $0.value) }.joined(separator: " "))
        #endif
        let kept = Voice.forSending(packets)
        switch kept {
        case .ok(let ps):
            // The note as readers will see it: if this phone's own rules would ignore it, it isn't sent.
            guard let frames = kept.frames, Voice.packets(Data([Voice.type]) + frames)?.count == ps.count else { return .cantRecord("the reader refuses the field") }
            #if DEBUG
            DebugLog.write("voice kept: \(ps.count) packets, field \(frames.count + 1) bytes, reader ok, \(Voice.seconds(ps.count)) s")
            #endif
            return .ready(frames: frames, seconds: Voice.seconds(ps.count))
        case .tooShort: return packets.count >= Voice.minPackets ? .cantRecord("packets too big") : .tooShort
        case .refused(let i, let p): return .cantRecord(String(format: "packet %d refused, first byte %02x", i, p.first ?? 0))
        }
    }
}

// MARK: - The composer

/// A recording in progress, as the chat's composer shows it, and what holding and letting go of the mic do. Asks for the
/// microphone the first time, shows high-risk mode's line once before the first recording, and hands the note to `send`.
final class VoiceComposer: ObservableObject {
    @Published var on = false
    /// Ten seconds are recorded: the timer stays at 0:10 and the note goes when the finger lifts (or, locked, when Send is tapped).
    @Published var full = false
    /// Slid up onto the lock: it goes on recording with the finger lifted, until the button stops and sends it or Cancel drops it.
    @Published var locked = false
    @Published var askRisk = false   // high-risk mode, once before the first recording
    @Published var micOff = false    // refused: only Settings can turn it on
    /// Sends the finished note's frames (as a reply when one is being written): set by the chat.
    var send: (Data) -> Void = { _ in }
    static let riskSeenKey = "voiceRiskSeen"

    /// The finger went down on the mic: true if recording started.
    func begin() -> Bool {
        let audio = VoiceAudio.shared
        if HighRisk.shared.isOn && !UserDefaults.standard.bool(forKey: Self.riskSeenKey) { askRisk = true; return false }
        switch audio.micAllowed {
        case nil: audio.askMic { ok in if ok { Nav.shared.toast("Hold the mic to record") } else { self.micOff = true } }; return false
        case false?: micOff = true; return false
        case true?: break
        }
        guard audio.startRecording(full: { [weak self] in self?.full = true; UINotificationFeedbackGenerator().notificationOccurred(.success) }) else {
            Nav.shared.toast("The microphone can't be used right now"); return false
        }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        full = false; locked = false; on = true
        return true
    }

    /// The finger slid up onto the lock: the recording goes on without it.
    func lock() {
        guard on, !locked else { return }
        locked = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    /// For people who can't hold and slide (VoiceOver's "Record hands-free"): a recording that is locked from the start.
    func handsFree() { if !on && begin() { lock() } }

    /// The finger lifted, or a locked recording's button was tapped (`keep`); it slid away, or Cancel was tapped (not `keep`).
    func end(keep: Bool) {
        guard on else { return }
        on = false; locked = false
        guard keep else { VoiceAudio.shared.cancelRecording(); Nav.shared.toast("Voice note cancelled"); return }
        guard let pcm = VoiceAudio.shared.finishRecording() else { return }
        let send = self.send
        DispatchQueue.global(qos: .userInitiated).async {
            let r = VoiceAudio.forSending(pcm)
            DispatchQueue.main.async {
                switch r {
                case .ready(let frames, _): send(frames)
                case .tooShort: Nav.shared.toast("Hold the mic to record")
                case .cantRecord(let why):
                    #if DEBUG
                    DebugLog.write("voice note not sent: \(why)")
                    #endif
                    Nav.shared.toast("This phone can't record voice notes.")
                }
            }
        }
    }

    /// The chat or the app was left: nothing records or plays behind it.
    func stopAll() {
        VoiceAudio.shared.stopPlaying()
        if on { on = false; locked = false; VoiceAudio.shared.cancelRecording() }
    }

    /// "Record anyway" under high-risk mode's line.
    func riskSeen() {
        UserDefaults.standard.set(true, forKey: Self.riskSeenKey)
        Nav.shared.toast("Hold the mic to record")
    }
}

/// The send button while the message box is empty: a mic. Holding it records, letting go sends, sliding more than 24 pt
/// past its edge beside or below it cancels, and so does a touch the system takes away (a call, a menu, the screen
/// changing under the finger): only a finger lifted on the button sends. Sliding up onto the lock above it keeps it
/// recording with the finger lifted (`Voice.slide`); then it is a red send button that stops and sends. While recording
/// it grows and turns red. VoiceOver: "Record hands-free" starts a locked recording at once.
struct MicButton: View {
    @ObservedObject var voice: VoiceComposer

    var body: some View {
        if voice.on && voice.locked { LockedSendButton(voice: voice) } else { HoldMic(voice: voice) }
    }
}

private struct HoldMic: View {
    @ObservedObject var voice: VoiceComposer
    @GestureState private var pressing = false // reset by SwiftUI when the touch ends or is cancelled
    @State private var down = false      // this touch has begun
    @State private var started = false   // and started a recording
    @State private var cancelled = false // and slid away, or was lifted (handled)
    @State private var lift: Double = 0  // how near the finger is to the lock, for its picture
    private let size: CGFloat = 50

    var body: some View {
        Image(systemName: "mic.fill").scaledFont(20, .semibold)
            .foregroundStyle(voice.on ? Color.white : Theme.text)
            .frame(width: size, height: size)
            .background(Circle().fill(voice.on ? Theme.help : Theme.tile))
            .scaleEffect(voice.on ? 1.22 : 1)
            .animation(Theme.lowPower ? nil : .spring(response: 0.25, dampingFraction: 0.7), value: voice.on)
            .contentShape(Circle())
            .overlay(alignment: .top) {
                if voice.on { LockPill(lift: lift).offset(y: -(LockPill.height + 10)).allowsHitTesting(false).accessibilityHidden(true) }
            }
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .local)
                .updating($pressing) { _, state, _ in state = true }
                .onChanged { g in
                    if !down { down = true; started = !voice.on && voice.begin(); return }
                    guard started, !cancelled else { return }
                    let p = g.location
                    lift = Voice.lift(y: Double(p.y))
                    switch Voice.slide(x: Double(p.x), y: Double(p.y), width: Double(size), height: Double(size)) {
                    case .lock: // this button gives way to the send button; the finger lifting later does nothing
                        lift = 0; down = false; started = false; voice.lock()
                    case .cancel: lift = 0; cancelled = true; voice.end(keep: false)
                    case .hold: break
                    }
                }
                .onEnded { _ in
                    if started && !cancelled && !voice.locked { voice.end(keep: true) }
                    down = false; started = false; cancelled = false; lift = 0
                })
            // A touch that ends without `onEnded` was cancelled: what it recorded is dropped.
            .onChange(of: pressing) { _, now in
                guard !now, down else { return }
                if started && !cancelled && !voice.locked { voice.end(keep: false) }
                down = false; started = false; cancelled = false; lift = 0
            }
            .accessibilityLabel("Hold to record a voice note")
            .accessibilityAction(named: "Record hands-free") { voice.handsFree() }
    }
}

/// The lock above the mic while it is held: slide up onto it (`lift` 0 to 1 as the finger nears it). Only a picture:
/// VoiceOver has "Record hands-free".
private struct LockPill: View {
    static let height: CGFloat = 68
    let lift: Double

    var body: some View {
        VStack(spacing: 0) {
            Image(systemName: "lock.fill").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text.opacity(0.6 + 0.4 * lift))
            Spacer(minLength: 0)
            Image(systemName: "chevron.up").font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.dim).offset(y: -8 * lift)
        }
        .padding(.vertical, 12)
        .frame(width: 40, height: Self.height)
        .background(Theme.tile, in: Capsule())
    }
}

/// A locked recording's button: red, an arrow up, stops the recording and sends it.
private struct LockedSendButton: View {
    @ObservedObject var voice: VoiceComposer

    var body: some View {
        Button { voice.end(keep: true) } label: {
            Image(systemName: "arrow.up").scaledFont(19, .bold).foregroundStyle(Color.white)
                .frame(width: 50, height: 50)
                .background(Circle().fill(Theme.help))
                .scaleEffect(1.22)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(voice.full ? "Send voice note" : "Stop and send")
    }
}

/// The composer's line while recording: a red dot, the time so far and "Slide away to cancel"; once locked, "Cancel"
/// instead. The time is what the microphone has given, so it stops at 0:10.
struct RecordingStrip: View {
    @ObservedObject var voice: VoiceComposer

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.1)) { ctx in
            let secs = min(10, VoiceAudio.shared.recordedMs / 1000)
            let blink = voice.full || Int(ctx.date.timeIntervalSinceReferenceDate * 2) % 2 == 0
            let time = HStack(spacing: 8) {
                Circle().fill(Theme.help.opacity(blink ? 1 : 0.3)).frame(width: 10, height: 10)
                Text(Voice.label(secs)).scaledFont(16, .semibold).monospacedDigit().foregroundStyle(Theme.text).fixedSize()
            }
            if voice.locked {
                HStack(spacing: 8) {
                    time.accessibilityElement(children: .ignore).accessibilityLabel("Recording hands-free, \(Voice.label(secs))")
                    Spacer(minLength: 2)
                    Button { voice.end(keep: false) } label: {
                        Text("Cancel").scaledFont(15, .semibold).foregroundStyle(Theme.text).lineLimit(1)
                            .padding(.horizontal, 16).frame(minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Cancel voice note")
                    Spacer(minLength: 0)
                }
                .padding(.leading, 14).padding(.trailing, 6)
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(Theme.tile, in: Capsule())
            } else {
                HStack(spacing: 8) {
                    time
                    Spacer(minLength: 2)
                    HStack(spacing: 2) {
                        Image(systemName: "chevron.left").scaledFont(12, .semibold)
                        Text("Slide away to cancel").scaledFont(14).lineLimit(1).minimumScaleFactor(0.7)
                    }
                    .foregroundStyle(Theme.dim)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(Theme.tile, in: Capsule())
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Recording, \(Voice.label(secs)). Slide away to cancel")
            }
        }
    }
}

// MARK: - The bubble

/// A voice note that may be played (`Voice.playable`): the play/pause button, 20 bars from the decoded sound, the length
/// ("0:07") and the speed chip ("1×", the same for every note, kept on this phone). A tap or a sideways drag on the bars
/// moves the note (VoiceOver: the row is adjustable, a tenth at a time); a paused note keeps its place. If the sound can't
/// be decoded: "Can't play this voice note."
struct VoiceNoteRow: View {
    let id: String
    let frames: Data
    let onWhite: Bool
    @ObservedObject private var audio = VoiceAudio.shared
    @State private var levels: [Float]?
    @State private var failed = false
    @State private var drag: Double? // where a finger on the bars is, until it lets go
    private static let barsWidth: CGFloat = 20 * 2.5 + 19 * 2.5

    var body: some View {
        let count = Voice.split(frames)?.count ?? 0, secs = Voice.seconds(count)
        let ink = onWhite ? Color.black : Theme.text
        let fill = onWhite ? Color.black.opacity(0.10) : Color.white.opacity(0.13)
        let playing = audio.playing == id, at = drag ?? audio.position(id)
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                HStack(spacing: 10) {
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        audio.toggle(id: id, frames: frames) { failed = true; Nav.shared.toast("Can't play this voice note.") }
                    } label: {
                        Image(systemName: playing ? "pause.fill" : "play.fill").scaledFont(16, .bold).foregroundStyle(ink)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(fill))
                    }
                    .buttonStyle(.plain)
                    .disabled(failed)
                    .accessibilityLabel(playing ? "Pause" : "Play")
                    HStack(spacing: 2.5) {
                        ForEach(0..<20, id: \.self) { i in
                            let l = CGFloat(levels?[i] ?? 0), played = at > 0 && (Double(i) + 0.5) / 20 <= at
                            RoundedRectangle(cornerRadius: 2).fill(ink.opacity(played ? 1 : 0.5)).frame(width: 2.5, height: 4 + 14 * l)
                        }
                    }
                    .frame(width: Self.barsWidth, height: 34) // 34 pt of touch
                    .contentShape(Rectangle())
                    .onTapGesture(coordinateSpace: .local) { p in if !failed { audio.seek(id: id, to: Voice.seekAt(Double(p.x), width: Double(Self.barsWidth))) } }
                    .gesture(SidewaysDrag(
                        moved: { x in if !failed { drag = Voice.seekAt(Double(x), width: Double(Self.barsWidth)) } },
                        ended: { done in if done, let d = drag { audio.seek(id: id, to: d) }; drag = nil }))
                    .accessibilityHidden(true)
                    Text(Voice.label(secs)).scaledFont(14, .semibold).monospacedDigit().foregroundStyle(ink)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(Voice.spoken(secs))
                .accessibilityValue(failed ? "" : Voice.positionSpoken(at, count: count))
                .accessibilityAdjustableAction { d in
                    guard !failed else { return }
                    switch d {
                    case .increment: audio.seek(id: id, to: Voice.stepped(at, forward: true))
                    case .decrement: audio.seek(id: id, to: Voice.stepped(at, forward: false))
                    @unknown default: break
                    }
                }
                SpeedChip(ink: ink, fill: fill).disabled(failed)
            }
            if failed { Text("Can't play this voice note.").scaledFont(13).foregroundStyle(ink.opacity(0.6)).padding(.leading, 4) }
        }
        .task(id: id) {
            // The picture is drawn from the decoded sound, here, once; until then the bars lie flat.
            let (id, frames) = (id, frames)
            let l = await Task.detached(priority: .utility) { VoiceAudio.shared.pcm(id: id, frames: frames).map { Voice.levels($0) } }.value
            if let l { levels = l } else { failed = true }
        }
    }
}

/// The speed chip: "1×", "1.5×", "2×" in turn, for every voice note at once, kept on this phone.
private struct SpeedChip: View {
    let ink: Color, fill: Color
    @ObservedObject private var audio = VoiceAudio.shared

    var body: some View {
        Button {
            UISelectionFeedbackGenerator().selectionChanged()
            audio.cycleSpeed()
        } label: {
            Text(Voice.speedLabel(audio.speed)).scaledFont(13, .semibold).monospacedDigit().foregroundStyle(ink).lineLimit(1).fixedSize()
                .padding(.horizontal, 8).frame(minWidth: 38, minHeight: 26)
                .background(Capsule().fill(fill))
                .frame(minHeight: 34).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Voice.speedSpoken(audio.speed))
    }
}

/// A sideways drag: a pan that begins only when the finger moves more sideways than up or down, and that the chat's
/// scrolling waits for, so a vertical swipe on the bars still scrolls and holding still still opens the bubble's menu.
/// `moved` gets the finger's x in the view; `ended` gets true when it lets go, false when the system takes the touch.
private struct SidewaysDrag: UIGestureRecognizerRepresentable {
    let moved: (CGFloat) -> Void
    let ended: (Bool) -> Void

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let g = UIPanGestureRecognizer()
        g.delegate = context.coordinator
        return g
    }

    func handleUIGestureRecognizerAction(_ g: UIPanGestureRecognizer, context: Context) {
        switch g.state {
        case .began, .changed: moved(context.converter.localLocation.x)
        case .ended: ended(true)
        case .cancelled, .failed: ended(false)
        default: break
        }
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let pan = g as? UIPanGestureRecognizer else { return true }
            let v = pan.velocity(in: pan.view)
            return abs(v.x) > abs(v.y)
        }
        /// The chat's scroll view waits until this drag is ruled out (it is at once for an up or down swipe).
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            other.view is UIScrollView
        }
    }
}

/// What the chat does for voice notes beyond the composer: nothing records or plays behind a closed app, another tab,
/// another channel or group; high-risk mode's line; the microphone turned off in Settings.
struct VoiceHooks: ViewModifier {
    @ObservedObject var voice: VoiceComposer
    /// The channel and group on screen: a change stops everything.
    let place: String
    /// The app is active and the chat is the tab on screen.
    let shown: Bool
    let send: (Data) -> Void

    func body(content: Content) -> some View {
        content
            .onChange(of: place) { voice.stopAll() }
            .onChange(of: shown) { _, s in if !s { voice.stopAll() } }
            .onDisappear { voice.stopAll() }
            .onAppear { voice.send = send }
            .alert("A voice can identify you.", isPresented: $voice.askRisk) {
                Button("Record anyway") { voice.riskSeen() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Even to someone who later gets a phone from your group.") }
            .alert("The microphone is off", isPresented: $voice.micOff) {
                Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                Button("Cancel", role: .cancel) {}
            } message: { Text("DarkProof can't use the microphone. Turn it on in Settings to record voice notes.") }
    }
}
