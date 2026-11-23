import XCTest
import CryptoKit
@testable import BlackoutCore

/// Ten-second voice notes (round 2c, rules V1 to V11 with the amendments of 2026-11-22): the 4th field's bytes, every
/// rejection, the voice fields Apple's encoder made in the codec experiment, a voice reply, what an older build shows,
/// and that a voice note is an ordinary group message for the Public channel, LoRa and high-risk mode. The same cases as
/// Android's VoiceNotesTest. Dummy keys only. The fixtures in ios/BlackoutCore/Tests/voice are the experiment's files as
/// they came (501 packets, one more than allowed), byte for byte Android's app/src/test/voice.
final class VoiceTests: XCTestCase {
    private let group = GroupCrypto(code: "test-group-voice")
    private let iv = Data(repeating: 9, count: 12)
    private let anna = "1111111111111111", ben = "2222222222222222"
    private let sig: (Data) throws -> Data = { _ in Data(repeating: 7, count: 64) }

    /// `n` packets of `size` bytes with first byte `toc` (and second byte `second` for code 3).
    private func packets(_ n: Int, _ size: Int = 20, toc: UInt8 = 0x08, second: UInt8? = nil) -> [Data] {
        (0..<n).map { i in Data((0..<size).map { j in j == 0 ? toc : (j == 1 && second != nil) ? second! : UInt8(truncatingIfNeeded: i * 31 + j) }) }
    }
    private func field(_ ps: [Data]) -> Data { Data([Voice.type]) + Voice.frames(ps) }
    static let apple = ["cbr-16000", "cbr-48000", "default-16000", "default-48000", "vbr-16000", "vbr-48000"]
    static func appleFile(_ name: String) throws -> Data {
        var url = URL(fileURLWithPath: #filePath) // .../ios/BlackoutCore/Tests/BlackoutCoreTests/VoiceTests.swift -> .../Tests/voice
        url.deleteLastPathComponent(); url.deleteLastPathComponent()
        return try Data(contentsOf: url.appendingPathComponent("voice/apple-8k-\(name).voicefield"))
    }
    /// The experiment's packets as they are, without the format's checks.
    private func rawSplit(_ field: Data) -> [Data] {
        let f = [UInt8](field); var out: [Data] = [], i = 1
        while i < f.count { let n = Int(f[i]); out.append(Data(f[(i + 1)..<min(f.count, i + 1 + n)])); i += 1 + n }
        XCTAssertEqual(i, f.count, "the experiment's files parse to their end")
        return out
    }
    private func open(_ id: String, _ c: Data) throws -> GroupCrypto.Opened { try XCTUnwrap(group.openSigned(Packet(id: id, ts: 9, ttl: 16, cipher: c))) }

    // MARK: - The bytes

    func testTheConstantsAreTheFormats() {
        XCTAssertEqual(Voice.type, 0x02); XCTAssertEqual(Photo.jpeg, 0x01)
        XCTAssertEqual(Voice.placeholder, "🎤 Voice note")
        XCTAssertEqual(Voice.maxBytes, 14_000); XCTAssertEqual(Voice.minPackets, 50); XCTAssertEqual(Voice.maxPackets, 500)
        XCTAssertEqual(Voice.preSkip, 312); XCTAssertEqual(Voice.sampleRate, 48_000); XCTAssertEqual(Voice.bitRate, 8_000)
        // The TOC first bytes allowed: the 20 ms configs, mono, code 0 or 3.
        let firsts = (0...255).filter { Voice.accepts(Data([UInt8($0), 0x01, 0x00])) || Voice.accepts(Data([UInt8($0)])) }
        XCTAssertEqual(firsts, [0x08, 0x0b, 0x28, 0x2b, 0x48, 0x4b, 0x68, 0x6b, 0x78, 0x7b, 0x98, 0x9b, 0xb8, 0xbb, 0xd8, 0xdb, 0xf8, 0xfb])
    }

    func testTheDecoderHeaderIsBuiltFromConstants() {
        XCTAssertEqual(Hex.encode(Voice.opusHead()), "4f707573486561640101380180bb0000000000")
        XCTAssertEqual(Voice.opusHead().count, 19)
        XCTAssertEqual(Voice.codecDelayNs, 6_500_000); XCTAssertEqual(Hex.encode(Voice.le(Voice.codecDelayNs, 8)), "a02e630000000000")
        XCTAssertEqual(Voice.seekPreRollNs, 80_000_000); XCTAssertEqual(Hex.encode(Voice.le(Voice.seekPreRollNs, 8)), "00b4c40400000000")
        XCTAssertEqual(Voice.expectedSamples(500), 479_688)
    }

    func testLengthIsPacketsTimes20MsInWholeSeconds() {
        XCTAssertEqual(Voice.seconds(50), 1); XCTAssertEqual(Voice.seconds(500), 10); XCTAssertEqual(Voice.seconds(350), 7)
        XCTAssertEqual(Voice.seconds(75), 2); XCTAssertEqual(Voice.seconds(74), 1) // 1.5 s rounds up, 1.48 s down
        XCTAssertEqual(Voice.durationMs(500), 10_000)
        XCTAssertEqual(Voice.label(7), "0:07"); XCTAssertEqual(Voice.label(10), "0:10")
        XCTAssertEqual(Voice.spoken(7), "Voice note, 7 seconds"); XCTAssertEqual(Voice.spoken(1), "Voice note, 1 second")
    }

    func testAFieldParsesExactlyToItsEnd() throws {
        XCTAssertEqual(Voice.packets(field(packets(350)))?.count, 350)
        // Code 3, one frame: without padding, and with 3 bytes of padding.
        let cbr = packets(250, toc: 0x0b, second: 0x01) + packets(250, toc: 0x0b, second: 0x41).map { var p = $0; p[p.startIndex + 2] = 3; return p }
        XCTAssertEqual(Voice.packets(field(cbr))?.count, 500)
        // At the limits: exactly 14000 bytes after the type byte, 56 packets of 249 bytes.
        XCTAssertEqual(Voice.packets(field(packets(56, 249)))?.count, 56)
        XCTAssertEqual(Voice.frames(packets(56, 249)).count, 14_000)
        XCTAssertEqual(Voice.split(Voice.frames(packets(60))), packets(60))
    }

    /// Every rejection of V6 and the amendments: the field is ignored as a whole.
    func testEveryBrokenRuleIgnoresTheField() {
        let good = field(packets(60))
        XCTAssertNotNil(Voice.packets(good))
        let cases: [(String, Data)] = [
            ("trailing byte (a len with nothing after it)", good + Data([5])),
            ("trailing len 1 cut off", good + Data([1])),
            ("len 0", good + Data([0])),
            ("stereo", field(packets(59) + packets(1, toc: 0x0c))),
            ("10 ms (config 0)", field(packets(59) + packets(1, toc: 0x00))),
            ("40 ms (config 2)", field(packets(59) + packets(1, toc: 0x10))),
            ("60 ms (config 3)", field(packets(59) + packets(1, toc: 0x18))),
            ("10 ms CELT (config 18)", field(packets(59) + packets(1, toc: 0x90))),
            ("code 1", field(packets(59) + packets(1, toc: 0x09))),
            ("code 2", field(packets(59) + packets(1, toc: 0x0a))),
            ("code 3 with two frames", field(packets(59) + packets(1, toc: 0x0b, second: 0x02))),
            ("code 3 with v = 1", field(packets(59) + packets(1, toc: 0x0b, second: 0x81))),
            ("code 3 with no second byte", field(packets(59) + [Data([0x0b])])),
            ("code 3 padding longer than the packet", field(packets(59) + [Data([0x0b, 0x41, 30, 1, 2, 3])])),
            ("code 3 padding length never ends", field(packets(59) + [Data([0x0b, 0x41, 0xFF, 0xFF])])),
            ("49 packets", field(packets(49))),
            ("501 packets", field(packets(501))),
            ("14001 bytes after the type", field(packets(55, 249) + packets(1, 250))),
            ("another type", Data([0x03]) + Voice.frames(packets(60))),
            ("only the type", Data([Voice.type])),
        ]
        for (why, f) in cases { XCTAssertNil(Voice.packets(f), why) }
        XCTAssertNil(Voice.packets(nil))
    }

    func testAWriterKeepsTheFirst500AndNothingOversized() throws {
        // Ten seconds come out of an encoder as 501 packets: the first 500 go.
        guard case .ok(let k) = Voice.forSending(packets(501)) else { return XCTFail("not kept") }
        XCTAssertEqual(k.count, 500); XCTAssertEqual(k[499], packets(501)[499])
        // V-V5: an encoder that overshoots (40-byte packets for 10 s) makes a shorter note, never one over 14000 bytes.
        let big = Voice.forSending(packets(501, 40))
        guard case .ok(let b) = big, let frames = big.frames else { return XCTFail("not kept") }
        XCTAssertEqual(b.count, 14_000 / 41); XCTAssertLessThanOrEqual(frames.count, Voice.maxBytes)
        XCTAssertNotNil(Voice.packets(Data([Voice.type]) + frames))
        XCTAssertEqual(Voice.forSending(packets(49)), .tooShort)
        // A packet readers would refuse: nothing is sent at all.
        guard case .refused(let index, _) = Voice.forSending(packets(20) + packets(1, toc: 0x18) + packets(100)) else { return XCTFail("not refused") }
        XCTAssertEqual(index, 20)
        guard case .refused = Voice.forSending(packets(60) + [Data(repeating: 0x08, count: 256)]) else { return XCTFail("longer than 255 bytes") }
    }

    // MARK: - Apple's encoder (the codec experiment's files)

    func testTheNotesApplesEncoderMadeAreReadAfterTheTrim() throws {
        for name in Self.apple {
            let f = try Self.appleFile(name)
            let all = rawSplit(f)
            XCTAssertEqual(all.count, 501, "\(name): the experiment kept every packet")
            XCTAssertNil(Voice.packets(f), "\(name): 501 packets, one too many")
            let kept = Voice.forSending(all)
            guard case .ok(let ps) = kept, let frames = kept.frames else { XCTFail(name); continue }
            XCTAssertEqual(ps.count, 500, name)
            let field = Data([Voice.type]) + frames
            XCTAssertEqual(Voice.packets(field)?.count, 500, name)
            XCTAssertLessThanOrEqual(field.count - 1, Voice.maxBytes, name)
            if name.hasPrefix("cbr") { XCTAssertTrue(ps.contains { $0.first! & 3 == 3 }, "\(name) has code 3 packets") }
            // Through a sealed group message and back.
            let c = try group.seal(id: "00000000000000b1", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, voice: frames, iv: iv, sign: sig)
            let m = try open("00000000000000b1", c).message
            XCTAssertEqual(m.voice, frames, name); XCTAssertEqual(m.text, Voice.placeholder); XCTAssertNil(m.photo)
        }
    }

    // MARK: - In a message

    func testAVoiceNoteRoundTripsThroughBothSealings() throws {
        let frames = Voice.frames(packets(350, 18))
        let c = try group.seal(id: "00000000000000b2", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, voice: frames, iv: iv, sign: sig)
        let o = try open("00000000000000b2", c)
        XCTAssertEqual(o.message.voice, frames); XCTAssertNil(o.message.photo); XCTAssertNil(o.message.about)
        XCTAssertEqual(o.extra, Data([Voice.type]) + frames)
        XCTAssertEqual(Voice.seconds(try XCTUnwrap(Voice.split(try XCTUnwrap(o.message.voice))).count), 7)
        // Sealed with a message key, as a member writes.
        let secret = Data(repeating: 0x21, count: 32)
        let e = Keyring.Epoch.fresh(number: 3, made: 1_000, secret: secret, members: [anna, ben], admins: [anna])
        let r = Keyring.Epoch.fresh(number: 3, made: 1_000, secret: secret, members: [anna, ben], admins: [anna])
        let id = String(format: "%04x", e.group.mailbox) + "0000000000b3"
        let cw = try e.group.seal(messageKey: try XCTUnwrap(e.messageKeys?.key(1, 0)), index: 1, counter: 0, id: id, ts: 5_000, sender: ben, name: "Ben",
                                  text: Voice.placeholder, voice: frames) { try MemberKey.sign(TestKeys.key, $0) }
        let got = try XCTUnwrap(r.open(Packet(id: id, ts: e.group.hideTime(5_000, cw), ttl: 16, cipher: cw)))
        XCTAssertEqual(got.message.voice, frames); XCTAssertEqual(got.message.sender, ben)
        // This phone's own copy (History) keeps it, and reads it with the same rules.
        let key = Data(repeating: 3, count: 32)
        let back = try XCTUnwrap(History.open(key: key, id: id, blob: try XCTUnwrap(History.seal(key: key, id: id, group: "0123456789abcdef0123456789abcdef", epoch: "eid", got, index: 1, counter: 0))))
        XCTAssertEqual(back.opened.message.voice, frames)
        // Padded to a size class: 350 packets of 18 bytes (6650 bytes) fit 8192; ten seconds at 8 kbit/s go in the 16018 class.
        XCTAssertEqual(Packet(id: id, ts: 5_000, ttl: 16, cipher: cw).encode().count, 8192)
        let ten = try group.seal(id: "00000000000000b3", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, voice: Voice.frames(packets(500, 20)), sign: sig)
        XCTAssertEqual(Packet(id: "00000000000000b3", ts: 9, ttl: 16, cipher: ten).encode().count, 16_018)
        XCTAssertLessThanOrEqual(ten.count, Packet.maxCipher)
    }

    func testAVoiceReplyCarriesBothFields() throws {
        let target = try XCTUnwrap(About.Target(key: "00000000000000a1:5a5a5a5a5a5a5a5a"))
        let frames = Voice.frames(packets(100))
        let c = try group.seal(id: "00000000000000b4", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, about: .reply(target), voice: frames, iv: iv, sign: sig)
        let o = try open("00000000000000b4", c)
        XCTAssertEqual(o.message.voice, frames); XCTAssertEqual(o.message.about, .reply(target))
        XCTAssertNotNil(o.aboutField)
    }

    func testWritersRefuseWhatReadersWouldIgnore() throws {
        let frames = Voice.frames(packets(100))
        let target = try XCTUnwrap(About.Target(key: "00000000000000a1:5a5a5a5a5a5a5a5a"))
        XCTAssertThrowsError(try group.seal(id: "00000000000000b5", ts: 9, sender: anna, name: "Bea", text: "Listen to this", voice: frames, sign: sig), "other text")
        XCTAssertThrowsError(try group.seal(id: "00000000000000b5", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, photo: Data([1, 2]), voice: frames, sign: sig), "both")
        XCTAssertThrowsError(try group.seal(id: "00000000000000b5", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, voice: Voice.frames(packets(49)), sign: sig), "too short")
        XCTAssertThrowsError(try group.seal(id: "00000000000000b5", ts: 9, sender: anna, name: "Bea", text: "", about: .reaction(target), voice: frames, sign: sig), "a reaction")
        XCTAssertThrowsError(try group.seal(id: "00000000000000b5", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, extra: Data([2, 1]), voice: frames, sign: sig), "another 4th field")
    }

    // MARK: - Readers that ignore it

    /// A packet from a body made by hand, for fields this app never writes.
    private func sealRaw(_ text: String, _ fourth: Data, _ id: String) throws -> Packet {
        func f(_ d: Data) -> Data { Data([UInt8(d.count >> 8), UInt8(d.count & 255)]) + d }
        let plain = Data(repeating: 7, count: 64) + f(Data(anna.utf8)) + f(Data("Bea".utf8)) + f(Data(text.utf8)) + f(fourth)
        let box = try AES.GCM.seal(plain, using: SymmetricKey(data: GroupCrypto.deriveKey("test-group-voice")), nonce: AES.GCM.Nonce(data: iv), authenticating: Data("\(id)|9".utf8))
        return Packet(id: id, ts: 9, ttl: 16, cipher: box.combined!)
    }

    func testABrokenFieldShowsOnlyTheText() throws {
        let bad: [(String, Data)] = [("trailing", field(packets(60)) + Data([5])), ("stereo", field(packets(60, toc: 0x0c))), ("501", field(packets(501))),
                                     ("text is not the placeholder", field(packets(60)))]
        for (i, (why, fourth)) in bad.enumerated() {
            let text = why.hasPrefix("text") ? "Hear this" : Voice.placeholder
            let m = try XCTUnwrap(group.openSigned(try sealRaw(text, fourth, "00000000000000c\(i)"))?.message, why)
            XCTAssertNil(m.voice, why); XCTAssertNil(m.photo, why); XCTAssertEqual(m.text, text, why)
        }
        // The same by hand with a good field reads as a voice note: the cases above fail only by what they break.
        XCTAssertNotNil(group.openSigned(try sealRaw(Voice.placeholder, field(packets(60)), "00000000000000c9"))?.message.voice)
    }

    /// The 4th-field rule as builds before voice notes had it (GroupCrypto.parsed and History.open before 2026-11-22), frozen here.
    private func oldPhoto(_ extra: Data?) -> Data? {
        guard let a = extra, a.first == Photo.jpeg, a.count > 1, a.count - 1 <= Photo.maxBytes else { return nil }
        return Data(a.dropFirst())
    }

    func testBuildsWithoutTheFeatureShowOnlyTheText() throws {
        let c = try group.seal(id: "00000000000000d1", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, voice: Voice.frames(packets(300)), iv: iv, sign: sig)
        let o = try open("00000000000000d1", c)
        XCTAssertNil(oldPhoto(o.extra), "an older build finds no photo in it")
        XCTAssertEqual(o.message.text, "🎤 Voice note", "and shows its text")
    }

    // MARK: - An ordinary group message everywhere else

    func testNeverOnThePublicChannel() {
        var m = Message(id: "00000000000000e1", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, group: "g")
        m.voice = Voice.frames(packets(60))
        XCTAssertTrue(Voice.playable(m))
        XCTAssertNil(m.onPublicChannel(mine: false).voice, "a Public channel reader keeps no voice note")
        XCTAssertFalse(Voice.playable(m.onPublicChannel(mine: true)))
        var u = m; u.verified = false
        XCTAssertFalse(Voice.playable(u), "not from a packet whose signature checked")
        var g = m; g.group = nil
        XCTAssertFalse(Voice.playable(g), "not from a group key")
        var n = m; n.voice = nil
        XCTAssertFalse(Voice.playable(n))
        XCTAssertEqual(m.withSender("me").voice, m.voice); XCTAssertEqual(m.withId("00000000000000e9").voice, m.voice)
    }

    func testNeverOnLoRa() throws {
        let c = try group.seal(id: "00000000000000e2", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, voice: Voice.frames(packets(50, 8)), sign: sig) // the shortest note there can be
        let p = Packet(id: "00000000000000e2", ts: Int64(Date().timeIntervalSince1970 * 1000), ttl: 16, cipher: c)
        XCTAssertFalse(Meshtastic.fits(p)); XCTAssertFalse(MeshCore.fits(p))
        for q in [LoraQueue(), LoraQueue(maxBytes: MeshCore.payloadMax)] { q.offer(p, mine: true); XCTAssertNil(q.next(), "not queued for the radio") }
    }

    /// High-risk mode's 24-hour rule and its gone list go by what a packet opened as (Engine.cover), never by what its 4th
    /// field holds: a voice note opens as an ordinary group message (not silent, not Public), so the engine notes its
    /// arrival like any other, it leaves a day later and stays on the gone list.
    func testHighRiskModeTreatsItAsAGroupMessage() throws {
        let c = try group.seal(id: "00000000000000e3", ts: 9, sender: anna, name: "Bea", text: Voice.placeholder, voice: Voice.frames(packets(60)), sign: sig)
        let p = Packet(id: "00000000000000e3", ts: 9, ttl: 16, cipher: c)
        let m = try open("00000000000000e3", c).message
        XCTAssertNil(m.about, "not a reaction or other silent message"); XCTAssertFalse(m.isSilent); XCTAssertFalse(m.offRadio); XCTAssertFalse(m.isPublic)
        XCTAssertEqual(StoreCap.received(true, public: m.isPublic), .group)
        XCTAssertTrue(KeepsLess.covers(.message, isPublic: m.isPublic))
        let now: Int64 = 1_800_000_000_000, hour: Int64 = 3_600_000
        XCTAssertEqual(KeepsLess.expired([KeepsLess.Held(name: p.id, since: now - 25 * hour)], now: now), [p.id])
        var book = DayBook(); book.remove(MeshRouter.key(p), ts: p.ts, group: "g")
        XCTAssertTrue(book.isGone(p)); XCTAssertEqual(book.kind(of: p), .unreadable)
    }

    func testTheBubblePictureIsLevelsFromZeroToOne() {
        let pcm: [Float] = (0..<48_000).map { i in i < 24_000 ? 0 : Float((i % 100) * 300 - 15_000) / 32_768 }
        let l = Voice.levels(pcm)
        XCTAssertEqual(l.count, 20); XCTAssertEqual(l[0], 0); XCTAssertEqual(l.max(), 1)
        XCTAssertEqual(Voice.levels([]).count, 20)
    }

    // MARK: - spec/test-vectors.txt (`kind: voice-field`, `voice-trim`, `voice-message`), made by spec/make-reply-vectors.py

    private func of(_ kind: String) -> [[String: String]] { VectorsTests.vectors.filter { $0["kind"] == kind } }

    func testVoiceFieldVectors() throws {
        let vs = of("voice-field")
        XCTAssertEqual(vs.count, 31)
        XCTAssertEqual(vs.filter { $0["reads"] == "voice" }.count, 9)
        for (i, v) in vs.enumerated() {
            let field = Hex.decode(v["field"]!), text = Hex.decode(v["text_hex"]!), what = "voice-field \(i + 1)"
            XCTAssertEqual(field.count - 1, Int(v["bytes"]!), what)
            let kept = Voice.of(extra: field, text: text)
            switch v["reads"]! {
            case "voice":
                let ps = try XCTUnwrap(Voice.packets(field), what)
                XCTAssertEqual(kept, Data(field.dropFirst()), what)
                XCTAssertEqual(ps.count, Int(v["packets"]!), what); XCTAssertEqual(Voice.durationMs(ps.count), Int(v["ms"]!), what)
                XCTAssertEqual(Voice.seconds(ps.count), Int(v["seconds"]!), what); XCTAssertEqual(Voice.label(Voice.seconds(ps.count)), v["label"], what)
                // What this app writes of these packets is the vector's field.
                XCTAssertEqual(Data([Voice.type]) + Voice.frames(ps), field, what)
            case "text": XCTAssertNil(kept, what)
            default: XCTFail("unknown `reads` in \(what)")
            }
        }
    }

    func testVoiceTrimVector() throws {
        let v = try XCTUnwrap(of("voice-trim").first)
        XCTAssertEqual(of("voice-trim").count, 1)
        let raw = try Self.appleFile(String(v["fixture"]!.dropFirst("apple-8k-".count).dropLast(".voicefield".count)))
        XCTAssertEqual(Hex.encode(Data(SHA256.hash(data: raw))), v["fixture_sha256"])
        XCTAssertEqual(raw.count - 1, Int(v["fixture_bytes"]!))
        let all = rawSplit(raw)
        XCTAssertEqual(all.count, Int(v["fixture_packets"]!))
        XCTAssertEqual(v["fixture_reads"], "text"); XCTAssertNil(Voice.packets(raw))
        let kept = Voice.forSending(all)
        guard case .ok(let ps) = kept, let frames = kept.frames else { return XCTFail("not kept") }
        let field = Data([Voice.type]) + frames
        XCTAssertEqual(ps.count, Int(v["kept_packets"]!)); XCTAssertEqual(frames.count, Int(v["kept_bytes"]!))
        XCTAssertEqual(Hex.encode(Data(SHA256.hash(data: field))), v["kept_sha256"])
        XCTAssertEqual(v["reads"], "voice"); XCTAssertNotNil(Voice.packets(field)); XCTAssertEqual(Voice.seconds(ps.count), Int(v["seconds"]!))
    }

    func testVoiceMessageVector() throws {
        let v = try XCTUnwrap(of("voice-message").first)
        let secret = Hex.decode(v["secret"]!), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        let position = Int(v["position"]!)!, number = Int(v["number"]!)!, ts = Int64(v["ts"]!)!
        let mk = try XCTUnwrap(MessageKeys.of(secret, members: [v["member_0"]!, v["member_1"]!]).key(position, number))
        XCTAssertEqual(Hex.encode(mk), v["key"])
        let fourth = Hex.decode(v["fourth"]!), text = Hex.decode(v["text_hex"]!)
        let o = try XCTUnwrap(g.open(messageKey: mk, index: position, counter: number, Packet(id: v["id"]!, ts: Int64(v["wire_ts"]!)!, ttl: 16, cipher: Hex.decode(v["cipher"]!))))
        XCTAssertEqual(o.message.ts, ts); XCTAssertEqual(o.message.sender, v["sender"]); XCTAssertEqual(o.message.text, String(decoding: text, as: UTF8.self))
        XCTAssertEqual(o.message.voice, Data(fourth.dropFirst())); XCTAssertNil(o.message.photo)
        XCTAssertEqual(Voice.split(try XCTUnwrap(o.message.voice))?.count, Int(v["packets"]!))
        let named = try XCTUnwrap(About.Target(key: v["about_id"]! + ":" + v["about_mark"]!))
        XCTAssertEqual(o.message.about, .reply(named))
        XCTAssertEqual(About.Target(o).mark, v["mark"])
        XCTAssertEqual(Hex.encode(o.digest), Hex.encode(Data(SHA256.hash(data: Hex.decode(v["signed_message"]!)))))
        XCTAssertNil(oldPhoto(o.extra)); XCTAssertEqual(o.message.text, v["older"])
        // What this app writes for the same note is byte for byte the vector.
        let again = try g.seal(messageKey: mk, index: position, counter: number, id: v["id"]!, ts: ts, sender: v["sender"]!, name: v["name"]!, text: Voice.placeholder,
                               about: .reply(named), voice: o.message.voice, iv: Hex.decode(v["iv"]!)) { _ in Hex.decode(v["signature"]!) }
        XCTAssertEqual(Hex.encode(again), v["cipher"]); XCTAssertEqual(String(g.hideTime(ts, again)), v["wire_ts"])
    }

    // MARK: - Seek, speed and the record lock (the screens; the same cases as Android's VoiceNotesTest)

    func testTheSpeedChipGoesRoundAndIsKeptOnlyIfKnown() {
        XCTAssertEqual(Voice.nextSpeed(1), 1.5); XCTAssertEqual(Voice.nextSpeed(1.5), 2); XCTAssertEqual(Voice.nextSpeed(2), 1)
        XCTAssertEqual(Voice.nextSpeed(3), 1) // from an unknown speed, back to 1×
        XCTAssertEqual(Voice.speed(of: 0), 1); XCTAssertEqual(Voice.speed(of: 1.25), 1); XCTAssertEqual(Voice.speed(of: 2), 2)
        XCTAssertEqual(Voice.speeds.map(Voice.speedLabel), ["1×", "1.5×", "2×"])
        XCTAssertEqual(Voice.speeds.map(Voice.speedSpoken), ["Playback speed, normal", "Playback speed, 1.5 times", "Playback speed, 2 times"])
    }

    func testAFingerOnTheBarsSeeksAndAScreenReaderStepsByTenths() {
        XCTAssertEqual(Voice.seekAt(-5, width: 100), 0); XCTAssertEqual(Voice.seekAt(25, width: 100), 0.25); XCTAssertEqual(Voice.seekAt(140, width: 100), 1)
        XCTAssertEqual(Voice.seekAt(10, width: 0), 0)
        XCTAssertEqual(Voice.stepped(0, forward: true), 0.1); XCTAssertEqual(Voice.stepped(0, forward: false), 0); XCTAssertEqual(Voice.stepped(1, forward: true), 1)
        XCTAssertEqual(Voice.stepped(0.43, forward: true), 0.5); XCTAssertEqual(Voice.stepped(0.43, forward: false), 0.3)
        var at = 0.0; for _ in 0..<12 { at = Voice.stepped(at, forward: true) }; XCTAssertEqual(at, 1)
        XCTAssertEqual(Voice.positionSpoken(0, count: 350), "0 of 7 seconds")
        XCTAssertEqual(Voice.positionSpoken(0.43, count: 350), "3 of 7 seconds")
        XCTAssertEqual(Voice.positionSpoken(1, count: 340), "7 of 7 seconds") // 6.8 s shown as 7: the end says the whole length
        XCTAssertEqual(Voice.positionSpoken(1, count: 50), "1 of 1 second")
    }

    func testSlidingUpLocksSlidingAwayCancels() {
        let w = 52.0, h = 52.0
        XCTAssertEqual(Voice.slide(x: 26, y: 26, width: w, height: h), .hold)
        XCTAssertEqual(Voice.slide(x: 26, y: -71, width: w, height: h), .hold)
        XCTAssertEqual(Voice.slide(x: 26, y: -72, width: w, height: h), .lock)
        XCTAssertEqual(Voice.slide(x: -35, y: -80, width: w, height: h), .lock) // a thumb drifts sideways on its way up
        XCTAssertEqual(Voice.slide(x: -41, y: -80, width: w, height: h), .cancel)
        XCTAssertEqual(Voice.slide(x: -25, y: 10, width: w, height: h), .cancel)
        XCTAssertEqual(Voice.slide(x: w + 25, y: 10, width: w, height: h), .cancel)
        XCTAssertEqual(Voice.slide(x: 26, y: h + 25, width: w, height: h), .cancel)
        XCTAssertEqual(Voice.slide(x: -24, y: h + 24, width: w, height: h), .hold)
        XCTAssertEqual(Voice.lift(y: 10), 0); XCTAssertEqual(Voice.lift(y: -36), 0.5); XCTAssertEqual(Voice.lift(y: -200), 1)
    }
}
