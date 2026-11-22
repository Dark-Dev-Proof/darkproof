import SwiftUI
import AVFAudio
import AudioToolbox
import BlackoutCore

// The iPhone's side of voice notes (`Voice` in BlackoutCore holds the rules): the microphone while the button is held,
// the system's Opus encoder and decoder (AVAudioConverter, kAudioFormatOpus), the speaker, and the pieces of the chat
// that show them.
//
// - The microphone is used only between the press and the release of the mic button, and only while the chat is on
//   screen. The sound is kept in memory, encoded, and sealed like any message. Nothing is written to a file, nothing is
//   transcribed. Decoded sound is held only while a note is shown or played.
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
    private var player: (engine: AVAudioEngine, node: AVAudioPlayerNode, timer: Timer)?
    private var turn = 0

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

    /// Plays the note `id`, or stops it if it is the one playing. Any other note stops first.
    func toggle(id: String, frames: Data, failed: @escaping () -> Void) {
        if playing == id { stopPlaying(); return }
        stopPlaying()
        turn += 1
        let mine = turn
        playing = id; progress = 0
        DispatchQueue.global(qos: .userInitiated).async {
            let pcm = self.pcm(id: id, frames: frames)
            DispatchQueue.main.async {
                guard self.turn == mine else { return }
                guard let pcm, self.startPlayer(pcm, mine) else { self.playing = nil; failed(); return }
            }
        }
    }

    private func startPlayer(_ pcm: [Float], _ mine: Int) -> Bool {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: OpusCodec.pcm, frameCapacity: AVAudioFrameCount(pcm.count)) else { return false }
        pcm.withUnsafeBufferPointer { buffer.floatChannelData![0].update(from: $0.baseAddress!, count: pcm.count) }
        buffer.frameLength = AVAudioFrameCount(pcm.count)
        let engine = AVAudioEngine(), node = AVAudioPlayerNode()
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: OpusCodec.pcm)
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            try engine.start()
        } catch { return false }
        node.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in
            DispatchQueue.main.async { if self.turn == mine { self.stopPlaying() } }
        }
        node.play()
        let total = Double(pcm.count)
        let timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self, weak node] _ in
            guard let self, self.turn == mine, let node, let t = node.lastRenderTime, let at = node.playerTime(forNodeTime: t) else { return }
            self.progress = min(1, max(0, Double(at.sampleTime) / total))
        }
        player = (engine, node, timer)
        return true
    }

    /// Stops whatever note is playing (the chat was left, the app went to the background, another note started).
    func stopPlaying() {
        turn += 1
        if let p = player { p.timer.invalidate(); p.node.stop(); p.engine.stop(); player = nil; deactivate() }
        if playing != nil { playing = nil }
        if progress != 0 { progress = 0 }
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
    /// Ten seconds are recorded: the timer stays at 0:10 and the note goes when the finger lifts.
    @Published var full = false
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
        full = false; on = true
        return true
    }

    /// The finger lifted (`keep`) or slid away (not `keep`).
    func end(keep: Bool) {
        guard on else { return }
        on = false
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
        if on { on = false; VoiceAudio.shared.cancelRecording() }
    }

    /// "Record anyway" under high-risk mode's line.
    func riskSeen() {
        UserDefaults.standard.set(true, forKey: Self.riskSeenKey)
        Nav.shared.toast("Hold the mic to record")
    }
}

/// The send button while the message box is empty: a mic. Holding it records, letting go sends, sliding more than 24 pt
/// past its edge cancels, and so does a touch the system takes away (a call, a menu, the screen changing under the
/// finger): only a finger lifted on the button sends. While recording it grows and turns red.
struct MicButton: View {
    @ObservedObject var voice: VoiceComposer
    @GestureState private var pressing = false // reset by SwiftUI when the touch ends or is cancelled
    @State private var down = false      // this touch has begun
    @State private var started = false   // and started a recording
    @State private var cancelled = false // and slid away, or was lifted (handled)
    private let size: CGFloat = 50, margin: CGFloat = 24

    var body: some View {
        Image(systemName: "mic.fill").scaledFont(20, .semibold)
            .foregroundStyle(voice.on ? Color.white : Theme.text)
            .frame(width: size, height: size)
            .background(Circle().fill(voice.on ? Theme.help : Theme.tile))
            .scaleEffect(voice.on ? 1.22 : 1)
            .animation(Theme.lowPower ? nil : .spring(response: 0.25, dampingFraction: 0.7), value: voice.on)
            .contentShape(Circle())
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .local)
                .updating($pressing) { _, state, _ in state = true }
                .onChanged { g in
                    if !down { down = true; started = voice.begin(); return }
                    guard started, !cancelled else { return }
                    let p = g.location
                    if p.x < -margin || p.y < -margin || p.x > size + margin || p.y > size + margin { cancelled = true; voice.end(keep: false) }
                }
                .onEnded { _ in
                    if started && !cancelled { voice.end(keep: true) }
                    down = false; started = false; cancelled = false
                })
            // A touch that ends without `onEnded` was cancelled: what it recorded is dropped.
            .onChange(of: pressing) { _, now in
                guard !now, down else { return }
                if started && !cancelled { voice.end(keep: false) }
                down = false; started = false; cancelled = false
            }
            .accessibilityLabel("Hold to record a voice note")
    }
}

/// The composer's line while recording: a red dot, the time so far and "Slide away to cancel". The time is what the
/// microphone has given, so it stops at 0:10.
struct RecordingStrip: View {
    @ObservedObject var voice: VoiceComposer

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.1)) { ctx in
            let secs = min(10, VoiceAudio.shared.recordedMs / 1000)
            let blink = voice.full || Int(ctx.date.timeIntervalSinceReferenceDate * 2) % 2 == 0
            HStack(spacing: 8) {
                Circle().fill(Theme.help.opacity(blink ? 1 : 0.3)).frame(width: 10, height: 10)
                Text(Voice.label(secs)).scaledFont(16, .semibold).monospacedDigit().foregroundStyle(Theme.text).fixedSize()
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

// MARK: - The bubble

/// A voice note that may be played (`Voice.playable`): the play/pause button, 20 bars from the decoded sound and the
/// length ("0:07"). If the sound can't be decoded: "Can't play this voice note."
struct VoiceNoteRow: View {
    let id: String
    let frames: Data
    let onWhite: Bool
    @ObservedObject private var audio = VoiceAudio.shared
    @State private var levels: [Float]?
    @State private var failed = false

    var body: some View {
        let count = Voice.split(frames)?.count ?? 0, secs = Voice.seconds(count)
        let ink = onWhite ? Color.black : Theme.text
        let playing = audio.playing == id, progress = playing ? audio.progress : 0
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    audio.toggle(id: id, frames: frames) { failed = true; Nav.shared.toast("Can't play this voice note.") }
                } label: {
                    Image(systemName: playing ? "pause.fill" : "play.fill").scaledFont(16, .bold).foregroundStyle(ink)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(onWhite ? Color.black.opacity(0.10) : Color.white.opacity(0.13)))
                }
                .buttonStyle(.plain)
                .disabled(failed)
                .accessibilityLabel(playing ? "Pause" : "Play")
                HStack(spacing: 2.5) {
                    ForEach(0..<20, id: \.self) { i in
                        let l = CGFloat(levels?[i] ?? 0), played = progress > 0 && (Double(i) + 0.5) / 20 <= progress
                        RoundedRectangle(cornerRadius: 2).fill(ink.opacity(played ? 1 : 0.5)).frame(width: 2.5, height: 4 + 14 * l)
                    }
                }
                .frame(height: 22)
                .accessibilityHidden(true)
                Text(Voice.label(secs)).scaledFont(14, .semibold).monospacedDigit().foregroundStyle(ink)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(Voice.spoken(secs))
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
