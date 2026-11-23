import SwiftUI
import BlackoutCore

@main
struct BlackoutApp: App {
    init() {
        Wipe.atLaunch() // an "Erase everything" the app's end cut short is finished before anything else starts
        Vault.wipeAfterReinstall() // before anything reads the Keychain
        OfflineMap.shared.setUp()
        Notifier.shared.setUp() // before launch ends, so tapping an alert that relaunched the app is handled
        #if DEBUG
        let args = CommandLine.arguments // devicectl ... launch --console it.darkproof.mesh aiSelfTest <model file>
        if let i = args.firstIndex(of: "join"), args.indices.contains(i + 1), let bar = args[i + 1].firstIndex(of: "|") {
            // join "Name|code": the test group, like Android's --es join (group codes never go in logs)
            UserDefaults.standard.set(String(args[i + 1][..<bar]), forKey: "name")
            GroupSecrets.shared.setCode(String(args[i + 1][args[i + 1].index(after: bar)...]))
        }
        if let i = args.firstIndex(of: "joinLink"), args.indices.contains(i + 1), let bar = args[i + 1].firstIndex(of: "|"),
           let invite = InviteLinks.read(String(args[i + 1][args[i + 1].index(after: bar)...])) { // joinLink "Name|darkproof://join?…": like join, through the code that reads an invite
            UserDefaults.standard.set(String(args[i + 1][..<bar]), forKey: "name")
            GroupSecrets.shared.joining(invite.code, pins: invite.pins)
            GroupSecrets.shared.setCode(invite.code)
        }
        if args.contains("found"), let c = GroupSecrets.shared.current { Engine.found(c) } // with join: as if this phone created the group
        if let i = args.firstIndex(of: "ahead"), args.indices.contains(i + 1), let days = Int64(args[i + 1]) { Engine.ahead = days * 86_400_000 } // ahead 8
        if let i = args.firstIndex(of: "quietAhead"), args.indices.contains(i + 1), let m = Int64(args[i + 1]) { Engine.quietAhead = m * 60_000 } // quietAhead 35: check-in clocks judged 35 minutes ahead
        for i in args.indices where args[i] == "set" && args.indices.contains(i + 2) { // set wifiNetwork 0 (repeatable)
            UserDefaults.standard.set(args[i + 2] == "1", forKey: args[i + 1])
        }
        if let i = args.firstIndex(of: "relays"), args.indices.contains(i + 1) { // relays ws://127.0.0.1:7447: tools/mock-relay
            NostrLink.debugRelays = args[i + 1].split(separator: ",").map(String.init)
        }
        #endif
        Mesh.shared.startIfSetUp() // also when iOS relaunches the app in the background for Bluetooth
        #if DEBUG
        if args.contains("dumpChat") { // for the mock-relay tests: what this phone shows, every 3 s (never the group code)
            Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in
                guard let e = Mesh.shared.engine else { return }
                DebugLog.write("CHAT " + e.messages.map { "\($0.id):\($0.verified ? "v" : "u"):\($0.name):\($0.text.prefix(60))" }.joined(separator: " | "))
            }
        }
        if let i = args.firstIndex(of: "aiSelfTest") { LocalAi.selfTest(args.indices.contains(i + 1) ? args[i + 1] : nil) }
        if let i = args.firstIndex(of: "interopTest"), args.indices.contains(i + 1) { InteropTest.run(args[i + 1]) }
        if let i = args.firstIndex(of: "unlockAfter"), args.indices.contains(i + 1), let s = Double(args[i + 1]) { // unlockAfter 10
            DispatchQueue.main.asyncAfter(deadline: .now() + s) { AppLock.shared.unlockForTests() }
        }
        if args.contains("logPins") { // key hashes, not the group code
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { DebugLog.write("pins " + (GroupSecrets.shared.current.flatMap { Mesh.shared.engine?.invitePins($0) } ?? "none")) }
        }
        if args.contains("samples") { InteropTest.samples(after: 8) } // no link: the messages go by whatever is up, e.g. relays
        if let i = args.firstIndex(of: "redeliver"), args.indices.contains(i + 1), let s = Double(args[i + 1]) { // redeliver 12: see `Engine.debugRedeliver`
            DispatchQueue.main.asyncAfter(deadline: .now() + s) { Mesh.shared.engine?.debugRedeliver() }
        }
        if args.contains("dumpChat") { // with the chat: what the store holds, every 3 s (counts only)
            Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in Mesh.shared.engine?.debugStore() }
        }
        if let i = args.firstIndex(of: "timer"), args.indices.contains(i + 1), let s = Int(args[i + 1]) { // timer 3600: an admin sets the group's timer (0: off)
            DispatchQueue.main.asyncAfter(deadline: .now() + 12) { Mesh.shared.engine?.setTimer(GroupSecrets.shared.current, seconds: s) { DebugLog.write("timer \(s): \($0)") } }
        }
        if let i = args.firstIndex(of: "quietStart"), args.indices.contains(i + 1), let m = Int(args[i + 1]) { // quietStart 30: a check-in clock to the group on screen (0: stop it)
            DispatchQueue.main.asyncAfter(deadline: .now() + 12) {
                Mesh.shared.engine?.sendClock(GroupSecrets.shared.current, deadline: { m == 0 ? 0 : QuietClock.deadline(minutes: m, now: $0) }, spot: false) { DebugLog.write("quietStart \(m): \($0)") }
            }
        }
        if let i = args.firstIndex(of: "say"), args.indices.contains(i + 1) { // say Hello: to the group on screen only
            DispatchQueue.main.asyncAfter(deadline: .now() + 12) { Mesh.shared.engine?.send(args[i + 1]); DebugLog.write("said") }
        }
        if args.contains("declineAsking") { // the asking list of a test group is full: everyone on it is turned down
            DispatchQueue.main.asyncAfter(deadline: .now() + 8) { Mesh.shared.engine?.debugDeclineAsking() }
        }
        if let i = args.firstIndex(of: "approve"), args.indices.contains(i + 1) { // approve Bea: lets them in, by name
            DispatchQueue.main.asyncAfter(deadline: .now() + 12) {
                guard let e = Mesh.shared.engine, let r = e.joinRequests(GroupSecrets.shared.current).first(where: { $0.name == args[i + 1] }) else { DebugLog.write("approve: nobody with that name is asking"); return }
                e.approveJoin(GroupSecrets.shared.current, r.id) { DebugLog.write("approve \(args[i + 1]): \($0)") }
            }
        }
        // finderFeed "-90x5,-75x6,-,-58x8": Finder's readings instead of Bluetooth, one a second (`x` repeats, `-` is none);
        // with `set finderTrial 1` and `sheet finder` the screen opens on a stand-in who asked for help.
        if let i = args.firstIndex(of: "finderFeed"), args.indices.contains(i + 1) { FinderSession.fakeFeed = FinderDemo.feed(args[i + 1]) }
        if let i = args.firstIndex(of: "voiceFeed"), args.indices.contains(i + 1) { // voiceFeed <audio file>: stands in for the microphone (the Simulator's is silent)
            VoiceAudio.shared.fakeInput = OpusCodec.samples(of: URL(fileURLWithPath: args[i + 1]))
            DebugLog.write("voiceFeed: \(VoiceAudio.shared.fakeInput?.count ?? 0) samples at 48 kHz")
        }
        if let i = args.firstIndex(of: "voiceDecode"), args.indices.contains(i + 1) { // voiceDecode <.voicefield>: a fixture through the app's decoder
            DispatchQueue.global().asyncAfter(deadline: .now() + 3) { VoiceDebug.decode(args[i + 1]) }
        }
        if let i = args.firstIndex(of: "voiceSend"), args.indices.contains(i + 1), let s = Double(args[i + 1]) { // voiceSend 4: records the feed for 4 s and sends it, without the button
            DispatchQueue.main.asyncAfter(deadline: .now() + 12) { VoiceDebug.send(seconds: s) }
        }
        for (arg, change) in [("makeAdmin", Engine.makeAdmin), ("removeMember", Engine.remove)] as [(String, (Engine) -> (String?, String, @escaping (Bool) -> Void) -> Void)] {
            guard let i = args.firstIndex(of: arg), args.indices.contains(i + 1) else { continue } // makeAdmin Ben, removeMember Marco: by name
            DispatchQueue.main.asyncAfter(deadline: .now() + 12) {
                guard let e = Mesh.shared.engine, let p = Person.all(in: e.messages, me: e.deviceId).first(where: { $0.name == args[i + 1] }) else { DebugLog.write("\(arg): nobody with that name"); return }
                change(e)(GroupSecrets.shared.current, p.id) { DebugLog.write("\(arg) \(args[i + 1]): \($0)") }
            }
        }
        #endif
    }

    var body: some Scene {
        WindowGroup { RootView().preferredColorScheme(.dark) }
    }
}

/// Finder on the Simulator, which has no Bluetooth (`finderFeed`, `sheet finder` above, debug builds only).
enum FinderDemo {
    /// "-90x5,-75,-" -> five -90s, a -75, a second without a reading.
    static func feed(_ s: String) -> [Int?] {
        s.split(separator: ",").flatMap { part -> [Int?] in
            let bits = part.split(separator: "x"), n = bits.count > 1 ? Int(bits[1]) ?? 1 : 1
            return Array(repeating: bits.first == "-" ? nil : Int(bits.first ?? ""), count: max(1, n))
        }
    }

    /// A stand-in who asked for help 6 minutes ago, with a finder note.
    static func person() -> Person {
        var p = Person(id: "finder-demo", name: "Marco", lastTs: Int64(Date().timeIntervalSince1970 * 1000) - 360_000)
        p.checkIn = .help; p.checkInTs = p.lastTs; p.battery = 15; p.needs = [.trapped]; p.finder = "a1b2c3d4e5f6"
        return p
    }
}

#if DEBUG
/// Debug builds only: voice notes without a microphone or a finger (`voiceDecode`, `voiceSend` above).
enum VoiceDebug {
    /// A field as the codec experiment wrote it (type byte, then every packet as it came), trimmed as a writer trims it,
    /// then decoded by the app's own path.
    static func decode(_ path: String) {
        guard let raw = try? Data(contentsOf: URL(fileURLWithPath: path)) else { DebugLog.write("voiceDecode: can't read \(path)"); return }
        let b = [UInt8](raw); var all: [Data] = [], i = 1
        while i < b.count { let n = Int(b[i]); all.append(Data(b[(i + 1)..<min(b.count, i + 1 + n)])); i += 1 + n }
        let kept = Voice.forSending(all)
        guard case .ok(let ps) = kept, let frames = kept.frames else { DebugLog.write("voiceDecode: \(all.count) packets, not kept: \(kept)"); return }
        let start = Date()
        guard let pcm = VoiceAudio.shared.pcm(id: "fixture", frames: frames) else { DebugLog.write("voiceDecode: \(ps.count) packets, decoder failed"); return }
        let rms = (pcm.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(pcm.count)).squareRoot()
        DebugLog.write(String(format: "voiceDecode %@: %d packets as written (reader: %@), %d kept, %d samples = %.3f s (expected %d), shown %@, rms %.4f, %.0f ms",
                              URL(fileURLWithPath: path).lastPathComponent, all.count, Voice.packets(raw) == nil ? "ignored" : "read", ps.count, pcm.count,
                              Double(pcm.count) / Double(Voice.sampleRate), Voice.expectedSamples(ps.count), Voice.label(Voice.seconds(ps.count)), rms, Date().timeIntervalSince(start) * 1000))
    }

    /// Records the debug feed for `seconds` through VoiceAudio and sends it to the group on screen.
    static func send(seconds: Double) {
        guard VoiceAudio.shared.startRecording(full: {}) else { DebugLog.write("voiceSend: can't record"); return }
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            guard let pcm = VoiceAudio.shared.finishRecording() else { return }
            DispatchQueue.global().async {
                let r = VoiceAudio.forSending(pcm)
                DispatchQueue.main.async {
                    if case .ready(let frames, let s) = r { Mesh.shared.engine?.send(Voice.placeholder, voice: frames); DebugLog.write("voiceSend: sent \(s) s") }
                    else { DebugLog.write("voiceSend: \(r)") }
                }
            }
        }
    }
}

/// Debug builds only: `simctl launch booted it.darkproof.mesh interopTest 127.0.0.1:7777` links to the Android app's
/// LAN test listener, then sends a check-in with battery and position, a place and a small photo, for the mixed-group test.
enum InteropTest {
    static func run(_ address: String) {
        guard let i = address.lastIndex(of: ":"), let port = UInt16(address[address.index(after: i)...]) else { return }
        let host = String(address[..<i])
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            guard let e = Mesh.shared.engine else { DebugLog.write("interop: no group"); return }
            e.wifi.connect(host: host, port: port)
            samples(after: 4)
        }
    }

    static func samples(after delay: Double) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            guard let e = Mesh.shared.engine else { DebugLog.write("interop: no group"); return }
            let spot = Spot(lat: 45.46420, lon: 9.19000, accuracy: 12)
            e.send(CheckIn.safe.text(battery: 64, spot: spot))
            e.send(Place(kind: .water, note: "Fountain in the square", spot: spot).text, isPublic: true)
            let img = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 240)).image { c in
                UIColor.systemTeal.setFill(); c.fill(CGRect(x: 0, y: 0, width: 320, height: 240))
                UIColor.white.setFill(); c.fill(CGRect(x: 100, y: 60, width: 120, height: 120))
            }
            if let jpeg = PhotoShrinker.jpeg(img) { e.send(Photo.text(caption: "From the iPhone"), photo: jpeg) }
            e.send("Hello from the iPhone")
            e.debugConversation() // a reply, a reply to a message that isn't here, reactions, a confirmed message with journey notes
            DebugLog.write("interop: sent check-in, place, photo, text")
        }
    }
}
#endif
