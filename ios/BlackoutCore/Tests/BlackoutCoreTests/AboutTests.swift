import XCTest
import CryptoKit
@testable import BlackoutCore

/// Replies and reactions (spec/FORMAT.md, "Replies and reactions"): the 5th plaintext field, what readers make of it,
/// which reactions count, and that a reader which doesn't know the field reads exactly what it read before.
final class AboutTests: XCTestCase {
    let code = "test-group-1"
    lazy var group = GroupCrypto(code: code)
    let iv = Data(repeating: 9, count: 12)
    let sig = Data(repeating: 7, count: 64)
    let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data((0..<400).map { UInt8($0 % 251) }) + Data([0xFF, 0xD9])
    /// Some earlier message, to reply or react to: its packet id and the start of its signed digest.
    let target = About.Target(key: "00000000000000a1:5a5a5a5a5a5a5a5a")!

    // MARK: - What a reader without the feature does (a copy of the rules as they were before the 5th field)

    /// The body's fields exactly as a reader read them before the 5th field existed (spec "Encryption", "Photos"):
    /// sender, name, text, then a 4th field if the bytes are there. It stops there.
    private func oldRead(_ plain: Data) -> (sender: String, name: String, text: Data, fourth: Data?)? {
        let b = [UInt8](plain.dropFirst(64)); var i = 0
        func next() -> [UInt8]? {
            guard i + 2 <= b.count else { return nil }
            let n = Int(b[i]) << 8 | Int(b[i + 1])
            guard i + 2 + n <= b.count else { return nil }
            defer { i += 2 + n }
            return Array(b[(i + 2)..<(i + 2 + n)])
        }
        guard let s = next(), let n = next(), let t = next() else { return nil }
        let fourth = next().flatMap { $0.isEmpty ? nil : Data($0) }
        return (String(decoding: s, as: UTF8.self), String(decoding: n, as: UTF8.self), Data(t), fourth)
    }

    /// The plaintext of a packet sealed with the group's own key.
    private func plain(_ cipher: Data, id: String, ts: Int64) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: cipher), using: SymmetricKey(data: GroupCrypto.deriveKey(code)), authenticating: Data("\(id)|\(ts)".utf8))
    }

    /// A packet from a body made by hand, for fields this app never writes.
    private func sealRaw(_ body: Data, id: String, ts: Int64) throws -> Packet {
        let box = try AES.GCM.seal(sig + body, using: SymmetricKey(data: GroupCrypto.deriveKey(code)), nonce: AES.GCM.Nonce(data: iv), authenticating: Data("\(id)|\(ts)".utf8))
        return Packet(id: id, ts: ts, ttl: 16, cipher: box.combined!)
    }

    private func field(_ d: Data) -> Data { Data([UInt8(d.count >> 8), UInt8(d.count & 255)]) + d }
    private func body(_ text: String, fourth: Data? = Data(), fifth: Data?, more: Data = Data()) -> Data {
        var b = field(Data("1111111111111111".utf8)) + field(Data("Bea".utf8)) + field(Data(text.utf8))
        if let fourth { b += field(fourth) }
        if let fifth { b += field(fifth) }
        return b + more
    }

    // MARK: - The field

    func testTheFieldIsTypeIdAndTheStartOfTheSignedDigest() {
        XCTAssertEqual(About.size, 17)
        XCTAssertEqual(Hex.encode(About.reply(target).field!), "01" + "00000000000000a1" + "5a5a5a5a5a5a5a5a")
        XCTAssertEqual(Hex.encode(About.reaction(target).field!), "02" + target.id + target.mark)
        XCTAssertNil(About.nothing.field, "never written")
        XCTAssertEqual(About.Target(key: target.key), target)
        for bad in ["", "00000000000000a1", "00000000000000a1:", "00000000000000A1:0000000000000001", "00000000000000a1:0000000000000001:02", "00000000000000a1 0000000000000001"] {
            XCTAssertNil(About.Target(key: bad), bad)
        }
    }

    // MARK: - What a target is named by

    private let m0 = "1111111111111111", m1 = "2222222222222222"
    private lazy var epoch = GroupCrypto.fromSecret(MessageKeys.outer(Data(repeating: 0x11, count: 32)))
    private func messageKey(_ n: Int) throws -> Data { try XCTUnwrap(MessageKeys.of(Data(repeating: 0x11, count: 32), members: [m0, m1]).key(1, n)) }

    /// Member 1's message number `n`, sealed with its own key under `iv`: the packet, and what it opens to.
    private func sealed(_ n: Int, id: String, _ text: String, about: About? = nil, iv: UInt8, signature: Data? = nil) throws -> (packet: Packet, opened: GroupCrypto.Opened) {
        let mk = try messageKey(n)
        let c = try epoch.seal(messageKey: mk, index: 1, counter: n, id: id, ts: 77, sender: m1, name: "Bea", text: text, about: about, iv: Data(repeating: iv, count: 12)) { _ in signature ?? self.sig }
        let p = Packet(id: id, ts: epoch.hideTime(77, c), ttl: 16, cipher: c)
        return (p, try XCTUnwrap(epoch.open(messageKey: mk, index: 1, counter: n, p)))
    }

    /// The chat row a phone makes from an opened packet.
    private func shown(_ o: GroupCrypto.Opened) -> Message { var m = o.message; m.group = "g"; m.mark = About.Target(o); return m }

    /// The target is named by what was signed (`GroupCrypto.Opened.mark`): the id and the start of the signed digest.
    func testATargetIsNamedByWhatWasSigned() throws {
        let a = try sealed(5, id: "e608000000000005", "The bridge is open", iv: 1)
        XCTAssertEqual(About.Target(a.opened).key, a.opened.mark)
        XCTAssertEqual(About.Target(a.opened).id, "e608000000000005")
        XCTAssertEqual(About.Target(a.opened).mark, Hex.encode(Data(a.opened.digest.prefix(8))))
        // A copy with fewer hops left is the same packet and the same message.
        XCTAssertEqual(About.Target(try XCTUnwrap(epoch.open(messageKey: try messageKey(5), index: 1, counter: 5, a.packet.withTtl(3)))), About.Target(a.opened))
        // The other signature that checks for the same message (every ECDSA signature has a twin) doesn't change it.
        let twin = try sealed(5, id: "e608000000000005", "The bridge is open", iv: 1, signature: Data(repeating: 9, count: 64))
        XCTAssertEqual(About.Target(twin.opened), About.Target(a.opened))
    }

    /// A member can seal someone's signed message again under a new IV: another packet (another cipher, another header
    /// time), the same message. Phones that got that copy first must still find it as the target of a reply or a
    /// reaction written about the other copy, and the other way round. A different message under the same id is no target.
    func testAResealedCopyIsStillTheTargetAndAnotherMessageUnderItsIdIsNot() throws {
        let id = "e608000000000005"
        let first = try sealed(5, id: id, "The bridge is open", iv: 1)
        let again = try sealed(5, id: id, "The bridge is open", iv: 2)   // the same signed message, sealed again
        let other = try sealed(5, id: id, "The bridge is closed", iv: 3) // another message under the same id
        XCTAssertNotEqual(MeshRouter.key(first.packet), MeshRouter.key(again.packet), "two different packets")
        XCTAssertNotEqual(first.packet.cipher, again.packet.cipher); XCTAssertNotEqual(first.packet.ts, again.packet.ts)
        XCTAssertEqual(first.opened.digest, again.opened.digest, "one signed message")
        XCTAssertEqual(About.Target(first.opened), About.Target(again.opened))
        XCTAssertNotEqual(About.Target(first.opened), About.Target(other.opened))

        // Written on a phone that shows the message from the first copy; read as the bytes that travel.
        let reply = try sealed(6, id: "e608000000000006", "Coming over", about: .reply(About.Target(first.opened)), iv: 4).opened.message
        let reaction = try sealed(7, id: "e608000000000007", "🙏", about: .reaction(About.Target(first.opened)), iv: 5).opened.message
        guard case .reply(let named)? = reply.about, case .reaction(let reacted)? = reaction.about else { return XCTFail("not read as a reply and a reaction") }

        // A phone that only ever got the re-sealed copy finds it, for the quote and for the chip. So does the first.
        for copy in [again, first] {
            let rows = About.targets(in: [shown(copy.opened)], group: "g")
            XCTAssertEqual(rows[named.key]?.text, "The bridge is open")
            var held = Reactions()
            held.add("e608000000000007", Reactions.Entry(group: "g", target: reacted.key, sender: reaction.sender, text: reaction.text, ts: reaction.ts, id: "e608000000000007"))
            let under = try XCTUnwrap(About.target(of: shown(copy.opened), group: "g"))
            XCTAssertEqual(held.chips(group: "g", target: under.key, me: "me").map(\.text), ["🙏"])
        }
        // A phone that holds another message under that id: no quote of it, no chip under it.
        let wrong = shown(other.opened)
        XCTAssertNil(About.targets(in: [wrong], group: "g")[named.key])
        var held = Reactions()
        held.add("e608000000000007", Reactions.Entry(group: "g", target: reacted.key, sender: reaction.sender, text: reaction.text, ts: reaction.ts, id: "e608000000000007"))
        XCTAssertEqual(held.chips(group: "g", target: try XCTUnwrap(About.target(of: wrong, group: "g")).key, me: "me"), [])
        // With both on the phone, each is its own target.
        XCTAssertEqual(About.targets(in: [wrong, shown(again.opened)], group: "g")[named.key]?.text, "The bridge is open")
    }

    /// The digest covers the key the message was sealed for and how: the same fields sealed with the epoch's own key,
    /// or in another group, are another target.
    func testTheSameWordsElsewhereAreAnotherTarget() throws {
        let a = try sealed(5, id: "e608000000000005", "hello", iv: 1)
        let outer = try epoch.seal(id: "e608000000000005", ts: 77, sender: m1, name: "Bea", text: "hello", iv: iv) { _ in self.sig }
        let b = try XCTUnwrap(epoch.openSigned(Packet(id: "e608000000000005", ts: epoch.hideTime(77, outer), ttl: 16, cipher: outer)))
        XCTAssertNotEqual(About.Target(a.opened), About.Target(b))
        let elsewhere = try group.seal(id: "e608000000000005", ts: 77, sender: m1, name: "Bea", text: "hello", iv: iv) { _ in self.sig }
        let c = try XCTUnwrap(group.openSigned(Packet(id: "e608000000000005", ts: 77, ttl: 16, cipher: elsewhere)))
        XCTAssertNotEqual(About.Target(b), About.Target(c))
    }

    func testAReplyOpensWithItsTarget() throws {
        let c = try group.seal(id: "00000000000000b1", ts: 5, sender: "1111111111111111", name: "Bea", text: "On my way", about: .reply(target), iv: iv) { _ in self.sig }
        let o = try XCTUnwrap(group.openSigned(Packet(id: "00000000000000b1", ts: 5, ttl: 16, cipher: c)))
        XCTAssertEqual(o.message.about, .reply(target))
        XCTAssertEqual(o.message.text, "On my way")
        XCTAssertNil(o.extra, "no photo: the 4th field is there and empty"); XCTAssertNil(o.message.photo)
        XCTAssertEqual(o.aboutField, About.reply(target).field)
        // The 4th field is written, empty, in front of the 5th.
        let fields = try plain(c, id: "00000000000000b1", ts: 5).dropFirst(64)
        XCTAssertEqual(Data(fields.prefix(body("On my way", fifth: About.reply(target).field).count)), body("On my way", fifth: About.reply(target).field))
    }

    func testAReplyCanCarryAPhoto() throws {
        let c = try group.seal(id: "00000000000000b2", ts: 5, sender: "1111111111111111", name: "Bea", text: Photo.text(caption: "Here"), photo: jpeg, about: .reply(target), iv: iv) { _ in self.sig }
        let o = try XCTUnwrap(group.openSigned(Packet(id: "00000000000000b2", ts: 5, ttl: 16, cipher: c)))
        XCTAssertEqual(o.message.photo, jpeg); XCTAssertEqual(o.message.about, .reply(target)); XCTAssertEqual(o.message.text, "📷 Here")
    }

    func testAReactionOpensAsOneAndEmptyTextTakesItBack() throws {
        for text in ["👍", "", "❤️"] {
            let c = try group.seal(id: "00000000000000b3", ts: 5, sender: "1111111111111111", name: "Bea", text: text, about: .reaction(target), iv: iv) { _ in self.sig }
            let o = try XCTUnwrap(group.openSigned(Packet(id: "00000000000000b3", ts: 5, ttl: 16, cipher: c)))
            XCTAssertEqual(o.message.about, .reaction(target), text); XCTAssertEqual(o.message.text, text)
            XCTAssertTrue(o.message.about!.isSilent)
        }
    }

    /// The same with a message key, the way group messages really travel.
    func testReplyAndReactionWithAMessageKey() throws {
        let secret = Data(repeating: 0x11, count: 32), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        let keys = MessageKeys.of(secret, members: ["1111111111111111", "2222222222222222"])
        for (n, about, text) in [(3, About.reply(target), "ok"), (4, About.reaction(target), "❤️")] {
            let mk = try XCTUnwrap(keys.key(1, n))
            let c = try g.seal(messageKey: mk, index: 1, counter: n, id: "e60800000000000\(n)", ts: 77, sender: "2222222222222222", name: "Bea", text: text, about: about, iv: iv) { _ in self.sig }
            let o = try XCTUnwrap(g.open(messageKey: mk, index: 1, counter: n, Packet(id: "e60800000000000\(n)", ts: g.hideTime(77, c), ttl: 16, cipher: c)))
            XCTAssertEqual(o.message.about, about); XCTAssertEqual(o.message.text, text); XCTAssertEqual(o.message.ts, 77)
        }
    }

    // MARK: - Fields a reader ignores

    func testAnotherTypeOrLengthIsAnOrdinaryMessage() throws {
        let good = About.reply(target).field!
        let cases: [(String, Data?)] = [
            ("another type", Data([0x03]) + good.dropFirst()), ("type 0", Data([0x00]) + good.dropFirst()), ("type 255", Data([0xFF]) + good.dropFirst()),
            ("one byte short", good.dropLast()), ("one byte long", good + Data([0])), ("only the type", Data([0x01])), ("empty", Data()), ("no 5th field", nil),
        ]
        for (i, (what, fifth)) in cases.enumerated() {
            let id = String(format: "00000000000000c%x", i)
            let o = try XCTUnwrap(group.openSigned(try sealRaw(body("hello", fifth: fifth), id: id, ts: 9)), what)
            XCTAssertNil(o.message.about, what); XCTAssertNil(o.aboutField, what)
            XCTAssertEqual(o.message.text, "hello", what); XCTAssertEqual(o.message.sender, "1111111111111111")
        }
    }

    func testWhatFollowsThe5thFieldIsIgnored() throws {
        let b = body("hello", fifth: About.reply(target).field, more: field(Data("a 6th field".utf8)) + Data([1, 2, 3]))
        XCTAssertEqual(group.openSigned(try sealRaw(b, id: "00000000000000d1", ts: 9))?.message.about, .reply(target))
    }

    /// Padding is zero bytes: it reads as an empty 4th and an empty 5th field, so a padded message is about nothing.
    func testPaddingIsNeverAnAboutField() throws {
        for pad in [Pad.none, .size, .full] {
            let c = try group.seal(id: "00000000000000d2", ts: 5, sender: "1111111111111111", name: "Bea", text: "hello", extra: nil, iv: iv, pad: pad) { _ in self.sig }
            XCTAssertNil(group.openSigned(Packet(id: "00000000000000d2", ts: 5, ttl: 16, cipher: c))?.message.about)
        }
        // After a photo too, and after an introduction's 4th field.
        let c = try group.seal(id: "00000000000000d3", ts: 5, sender: "1111111111111111", name: "Bea", text: Photo.placeholder, photo: jpeg, iv: iv) { _ in self.sig }
        XCTAssertNil(group.openSigned(Packet(id: "00000000000000d3", ts: 5, ttl: 16, cipher: c))?.message.about)
        let intro = try group.intros().seal(id: "00000000000000d4", ts: 5, sender: "1111111111111111", name: "Bea", text: "", extra: Data([2]) + Data(repeating: 4, count: 98), iv: iv) { _ in self.sig }
        XCTAssertNil(group.intros().openSigned(Packet(id: "00000000000000d4", ts: 5, ttl: 16, cipher: intro))?.message.about)
    }

    /// The reactions are a list: exactly these bytes, or none at all. The heart carries its variation selector.
    func testTheListedReactions() {
        XCTAssertEqual(Reaction.listed.map { Hex.encode(Data($0.utf8)) }, ["f09f918d", "e29da4efb88f", "f09f9882", "f09f98ae", "f09f998f"])
        XCTAssertEqual(Reaction.offered, Reaction.listed)
        XCTAssertEqual(Set(Reaction.listed.map { Array($0.utf8) }).count, Reaction.listed.count)
        for e in Reaction.listed { XCTAssertLessThanOrEqual(e.utf8.count, Reaction.maxBytes, e) }
    }

    /// A type 0x02 message whose text is not a listed reaction, byte for byte, is not a reaction and not a message
    /// either: nobody can put text under someone's message.
    func testAReactionWithAnyOtherTextIsNothing() throws {
        let r = About.reaction(target).field!
        func read(_ text: [UInt8]) -> About? { About.read(field: r, text: Data(text)) }
        XCTAssertEqual(read([]), .reaction(target), "takes it back")
        for e in Reaction.listed { XCTAssertEqual(read(Array(e.utf8)), .reaction(target), e) }
        let others: [(String, [UInt8])] = [
            ("a word", Array("ok".utf8)), ("a check mark", Array("✅".utf8)), ("a call for help", Array("🆘".utf8)),
            ("the heart without its variation selector", [0xE2, 0x9D, 0xA4]), ("the heart with the text selector", [0xE2, 0x9D, 0xA4, 0xEF, 0xB8, 0x8E]),
            ("a listed one twice", Array("👍👍".utf8)), ("a listed one and a space", Array("👍 ".utf8)), ("a space and a listed one", Array(" 👍".utf8)),
            ("a listed one with a skin tone", Array("👍🏽".utf8)), ("another emoji", Array("🎉".utf8)), ("a zero byte", [0]), ("a space", [0x20]),
            ("16 letters", Array(repeating: 0x41, count: 16)), ("17 bytes", Array(repeating: 0x41, count: 17)), ("not UTF-8", [0xFF]),
            ("a listed one cut short", [0xF0, 0x9F, 0x91]), ("a listed one and a zero byte", Array("👍".utf8) + [0]),
        ]
        for (what, text) in others { XCTAssertEqual(read(text), .nothing, what) }
        XCTAssertTrue(About.nothing.isSilent); XCTAssertNil(About.nothing.target)
        // Sealed and opened: the same.
        for (n, text) in ["ok", "✅", "seventeen bytes!!"].enumerated() {
            let p = try sealRaw(body(text, fifth: r), id: "00000000000000e\(n)", ts: 9)
            XCTAssertEqual(group.openSigned(p)?.message.about, .nothing, text)
        }
        // A reply's text is never judged.
        XCTAssertEqual(About.read(field: About.reply(target).field, text: Data(repeating: 0xFF, count: 400)), .reply(target))
    }

    func testThisAppOnlyWritesListedReactions() {
        func seal(_ text: String, photo: Data? = nil) throws -> Data {
            try group.seal(id: "00000000000000e9", ts: 5, sender: "1111111111111111", name: "Bea", text: text, photo: photo, about: .reaction(target), iv: iv) { _ in self.sig }
        }
        for bad in ["ok", "✅", "🆘", "👍👍", "seventeen bytes!!", "\u{2764}"] {
            XCTAssertThrowsError(try seal(bad), bad)
            XCTAssertFalse(Reaction.sendable(bad), bad)
        }
        XCTAssertThrowsError(try seal("👍", photo: jpeg), "a reaction carries no photo")
        for e in Reaction.listed { XCTAssertNoThrow(try seal(e), e); XCTAssertTrue(Reaction.sendable(e), e) }
        XCTAssertNoThrow(try seal(""), "taking it back")
        XCTAssertFalse(Reaction.sendable(""), "a tap is always a listed reaction; the app makes the empty one itself")
    }

    /// An app without reactions shows a reaction as an ordinary message: no listed reaction may read as a check-in,
    /// a report or a request there. "✅" would be an "I'm safe", "🆘" a call for help.
    func testListedReactionsAreNeverTakenForACheckIn() {
        XCTAssertFalse(Reaction.listed.isEmpty)
        for e in Reaction.listed {
            XCTAssertNil(CheckIn.of(e), e); XCTAssertNil(Vouch.of(e), e); XCTAssertNil(AreYouSafe.of(e), e)
            XCTAssertEqual(ChatCard.of(Message(id: "x", ts: 1, sender: "a", name: "A", text: e, group: "g")), .text, e)
        }
        XCTAssertEqual(CheckIn.of("✅"), .safe, "why it is not listed")
    }

    // MARK: - A reader that doesn't know the field

    /// The promise of "Encryption" and "Photos": a reader that knows four fields reads the same sender, name, text and
    /// photo whether or not a 5th field follows.
    func testOlderReadersReadTheSameFields() throws {
        let cases: [(text: String, photo: Data?, about: About)] = [("On my way", nil, .reply(target)), ("👍", nil, .reaction(target)), ("", nil, .reaction(target)), (Photo.text(caption: "Here"), jpeg, .reply(target))]
        for (i, c) in cases.enumerated() {
            let id = String(format: "00000000000000f%x", i)
            let with = try group.seal(id: id, ts: 9, sender: "1111111111111111", name: "Bea", text: c.text, photo: c.photo, about: c.about, iv: iv) { _ in self.sig }
            let without = try group.seal(id: id, ts: 9, sender: "1111111111111111", name: "Bea", text: c.text, photo: c.photo, iv: iv) { _ in self.sig }
            let a = try XCTUnwrap(oldRead(try plain(with, id: id, ts: 9))), b = try XCTUnwrap(oldRead(try plain(without, id: id, ts: 9)))
            XCTAssertEqual(a.sender, b.sender); XCTAssertEqual(a.name, b.name); XCTAssertEqual(a.text, b.text); XCTAssertEqual(a.fourth, b.fourth)
            XCTAssertEqual(a.text, Data(c.text.utf8)); XCTAssertEqual(a.fourth, c.photo.map { Data([Photo.jpeg]) + $0 })
            // And this app, reading a message without the field, reads what the old reader reads.
            let o = try XCTUnwrap(group.openSigned(Packet(id: id, ts: 9, ttl: 16, cipher: without)))
            XCTAssertEqual(o.message.text, c.text); XCTAssertEqual(o.message.photo, c.photo); XCTAssertNil(o.message.about)
        }
    }

    /// The field is inside the signed body: it can't be added to, changed in or cut from someone's message.
    func testTheFieldIsSigned() throws {
        func digest(_ about: About?) throws -> Data {
            let c = try group.seal(id: "0000000000000101", ts: 9, sender: "1111111111111111", name: "Bea", text: "👍", about: about, iv: iv, pad: .none) { _ in self.sig }
            return try XCTUnwrap(group.openSigned(Packet(id: "0000000000000101", ts: 9, ttl: 16, cipher: c))).digest
        }
        let other = About.Target(key: "00000000000000a2:5a5a5a5a5a5a5a5a")!
        XCTAssertEqual(Set([try digest(nil), try digest(.reply(target)), try digest(.reaction(target)), try digest(.reply(other))]).count, 4)
    }

    // MARK: - The Public channel

    func testOnThePublicChannelTheFieldIsIgnored() throws {
        let pub = GroupCrypto(code: GroupCrypto.publicCode)
        for about in [About.reply(target), .reaction(target)] {
            let c = try pub.seal(id: "0000000000000111", ts: 9, sender: "1111111111111111", name: "Bea", text: "👍", about: about, iv: iv) { _ in self.sig }
            let o = try XCTUnwrap(pub.openSigned(Packet(id: "0000000000000111", ts: 9, ttl: 16, cipher: c)))
            for mine in [true, false] {
                let shown = o.message.onPublicChannel(mine: mine)
                XCTAssertNil(shown.about); XCTAssertNil(shown.photo); XCTAssertTrue(shown.isPublic); XCTAssertEqual(shown.text, "👍")
                XCTAssertNil(About.target(of: shown, group: nil)); XCTAssertNil(About.target(of: shown, group: "g"))
            }
        }
    }

    // MARK: - Targets on the reader's own phone

    private func row(_ id: String, group: String? = "g", verified: Bool = true, isPublic: Bool = false, about: About? = nil, mark: String = "0101010101010101") -> Message {
        var m = Message(id: id, ts: 10, sender: "2222222222222222", name: "Ann", text: "hi", isPublic: isPublic, group: group)
        m.verified = verified; m.about = about
        m.mark = About.Target(key: id + ":" + mark)
        return m
    }

    func testOnlyACheckedGroupMessageOfTheSameGroupIsATarget() {
        let ok = row("0000000000000201")
        XCTAssertEqual(About.target(of: ok, group: "g"), ok.mark)
        XCTAssertNil(About.target(of: ok, group: "other"), "another group")
        XCTAssertNil(About.target(of: ok, group: nil))
        XCTAssertNil(About.target(of: row("0000000000000202", verified: false), group: "g"), "not verified yet")
        XCTAssertNil(About.target(of: row("0000000000000203", group: nil, isPublic: true), group: "g"), "the Public channel")
        XCTAssertNil(About.target(of: row("0000000000000204", about: .reaction(target)), group: "g"), "a reaction")
        XCTAssertNil(About.target(of: row("0000000000000205", about: .nothing), group: "g"))
        XCTAssertNotNil(About.target(of: row("0000000000000206", about: .reply(target)), group: "g"), "a reply can be replied to")
        var unmatched = ok; unmatched.mark = nil
        XCTAssertNil(About.target(of: unmatched, group: "g"))
    }

    /// Another message under the same id is not the target: a reply to the real one doesn't find it.
    func testAReplyFindsOnlyTheVeryMessage() {
        let real = row("0000000000000301"), junk = row("0000000000000301", mark: "0202020202020202").withId("0000000000000301:aaaaaaaaaaaaaaaa")
        let rows = [junk, real, row("0000000000000302", verified: false), row("0000000000000303", group: "other")]
        let found = About.targets(in: rows, group: "g")
        XCTAssertEqual(found.count, 2)
        XCTAssertEqual(found[real.mark!.key]?.id, real.id)
        XCTAssertEqual(found[junk.mark!.key]?.id, junk.id)
        XCTAssertNil(found[rows[2].mark!.key]); XCTAssertNil(found[rows[3].mark!.key])
        XCTAssertNil(found[target.key], "a message this phone doesn't have")
    }

    func testQuoteLine() {
        func m(_ text: String, photo: Data? = nil) -> Message { Message(id: "x", ts: 1, sender: "2222222222222222", name: "Marco", text: text, photo: photo, group: "g") }
        XCTAssertEqual(Quote.line(of: m("Water is back.\nBring bottles.")), "Water is back.")
        XCTAssertEqual(Quote.line(of: m("\n\n  second  \nthird")), "second")
        XCTAssertEqual(Quote.line(of: m("📷 Photo", photo: jpeg)), "Photo")
        XCTAssertEqual(Quote.line(of: m("📷 Bridge is out", photo: jpeg)), "Bridge is out")
        XCTAssertEqual(Quote.line(of: m("📷 Photo")), "📷 Photo", "no photo came with it: its text as it is")
        XCTAssertEqual(Quote.line(of: m("✅ I'm safe · 🔋 64%")), "✅ I'm safe · 🔋 64%")
        XCTAssertEqual(Quote.line(of: m(String(repeating: "a", count: 500))).count, Quote.maxChars + 1)
        XCTAssertEqual(Quote.line(of: m("")), "")
        XCTAssertEqual(Quote.name(of: m("hi"), me: "dev"), "Marco")
        XCTAssertEqual(Quote.name(of: m("hi").withSender("dev"), me: "dev"), "You")
    }

    // MARK: - Which reactions count

    private func e(_ sender: String, _ text: String, _ ts: Int64, _ id: String, group: String = "g", target t: String? = nil) -> Reactions.Entry {
        Reactions.Entry(group: group, target: t ?? target.key, sender: sender, text: text, ts: ts, id: id)
    }
    private func chips(_ r: Reactions, me: String = "me") -> [String] { r.chips(group: "g", target: target.key, me: me).map { "\($0.text)\($0.count)\($0.mine ? "*" : "")" } }

    func testOneReactionPerMemberTheLatestCounts() {
        var r = Reactions()
        r.add("p1", e("a", "❤️", 100, "0000000000000001"))
        r.add("p2", e("b", "👍", 150, "0000000000000002"))
        r.add("p3", e("a", "👍", 300, "0000000000000003"))
        r.add("p4", e("a", "😂", 200, "0000000000000004")) // arrives last, written earlier: doesn't count
        XCTAssertEqual(chips(r), ["👍2"])
        // Whatever order they arrive in.
        var back = Reactions()
        for (n, x) in [("p4", e("a", "😂", 200, "0000000000000004")), ("p3", e("a", "👍", 300, "0000000000000003")), ("p2", e("b", "👍", 150, "0000000000000002")), ("p1", e("a", "❤️", 100, "0000000000000001"))] { back.add(n, x) }
        XCTAssertEqual(chips(back), ["👍2"])
        XCTAssertEqual(r, back)
    }

    func testAtTheSameTimeTheGreaterIdCounts() {
        var r = Reactions()
        r.add("p1", e("a", "😮", 100, "00000000000000b0")); r.add("p2", e("a", "👍", 100, "00000000000000af"))
        XCTAssertEqual(chips(r), ["😮1"])
        var back = Reactions()
        back.add("p2", e("a", "👍", 100, "00000000000000af")); back.add("p1", e("a", "😮", 100, "00000000000000b0"))
        XCTAssertEqual(chips(back), ["😮1"])
    }

    func testEmptyTextTakesItBack() {
        var r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001")); r.add("p2", e("b", "🙏", 100, "0000000000000002")); r.add("p3", e("a", "", 200, "0000000000000003"))
        XCTAssertEqual(chips(r), ["🙏1"])
        XCTAssertEqual(r.mine(group: "g", target: target.key, me: "a")?.text, "", "taken back, not never reacted")
        XCTAssertNil(r.mine(group: "g", target: target.key, me: "c"))
        r.add("p4", e("a", "❤️", 300, "0000000000000004")) // and again
        XCTAssertEqual(chips(r), ["❤️1", "🙏1"])
    }

    func testMineIsMarkedAndChipsComeInTheOrderOfTheList() {
        var r = Reactions()
        r.add("p1", e("a", "🙏", 100, "0000000000000001")); r.add("p2", e("me", "🙏", 100, "0000000000000002"))
        r.add("p3", e("b", "❤️", 100, "0000000000000003")); r.add("p4", e("c", "👍", 100, "0000000000000004")); r.add("p5", e("d", "😮", 100, "0000000000000005"))
        XCTAssertEqual(chips(r), ["👍1", "❤️1", "😮1", "🙏2*"])
        XCTAssertEqual(r.mine(group: "g", target: target.key, me: "me")?.text, "🙏")
    }

    /// Only listed reactions are ever held: whatever else is handed in is not taken, shows nothing, and doesn't
    /// replace the member's reaction. (`About.read` lets none through; this is the second lock.)
    func testOnlyListedReactionsAreHeld() {
        var r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001"))
        for (n, text) in ["ok", "✅", "\u{2764}", "👍👍", "Marco lied", " "].enumerated() { r.add("x\(n)", e("a", text, 200 + Int64(n), "00000000000000f\(n)")) }
        r.add("y1", e("b", "ok", 100, "0000000000000002"))
        XCTAssertEqual(r.count, 1)
        XCTAssertEqual(chips(r), ["👍1"])
        XCTAssertEqual(r.mine(group: "g", target: target.key, me: "a")?.text, "👍")
        XCTAssertNil(r.mine(group: "g", target: target.key, me: "b"))
    }

    /// A reaction counts only for the target it names, in the group whose key opened it: someone who is in two groups
    /// knows the messages of both, and must not be able to hang a reaction written in one on a message of the other.
    func testAReactionCountsOnlyInItsOwnGroupAndForItsOwnTarget() {
        var r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001", group: "other"))
        r.add("p2", e("a", "👍", 100, "0000000000000002", target: "00000000000000a9:00000000000000a9"))
        XCTAssertEqual(chips(r), [])
        XCTAssertEqual(r.chips(group: "other", target: target.key, me: "me").map(\.text), ["👍"])
    }

    /// A reaction whose target is a reaction is never shown: a reaction is no row, so nothing looks its reactions up.
    func testAReactionToAReactionIsIgnored() {
        let first = row("0000000000000401", about: .reaction(target))
        XCTAssertNil(About.target(of: first, group: "g"))
        XCTAssertTrue(About.targets(in: [first], group: "g").isEmpty)
        var r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000401"))
        r.add("p2", e("b", "❤️", 200, "0000000000000402", target: first.mark!.key))
        XCTAssertEqual(chips(r), ["👍1"])
    }

    func testAReactionGoesWithItsPacket() {
        var r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001")); r.add("p2", e("a", "❤️", 200, "0000000000000002"))
        r.add("p2", e("a", "😂", 900, "0000000000000009")) // the same packet again: nothing changes
        XCTAssertEqual(chips(r), ["❤️1"]); XCTAssertEqual(r.count, 2)
        r.forget(["p2", "unknown"])
        XCTAssertEqual(chips(r), ["👍1"], "what is left is what the stored packets say")
        r.forget(["p1"])
        XCTAssertEqual(chips(r), []); XCTAssertEqual(r, Reactions())
    }

    func testBlockedPeoplesReactionsAreNotShownAndWebPrefixIsTheSameMember() {
        var r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001")); r.add("p2", e("b", "👍", 100, "0000000000000002")); r.add("p3", e("web-a", "❤️", 200, "0000000000000003"))
        XCTAssertEqual(chips(r), ["👍1", "❤️1"], "a's later one, under web-, replaces a's")
        XCTAssertEqual(r.chips(group: "g", target: target.key, me: "me", hidden: { $0 == "b" }).map(\.text), ["❤️"])
    }

    // MARK: - This phone's copy

    func testThisPhonesCopyKeepsTheField() throws {
        let secret = Data(repeating: 0x11, count: 32), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
        let keys = MessageKeys.of(secret, members: ["1111111111111111", "2222222222222222"]), key = Data(repeating: 3, count: 32)
        func opened(_ n: Int, _ text: String, _ about: About?, photo: Data? = nil) throws -> GroupCrypto.Opened {
            let mk = try XCTUnwrap(keys.key(1, n))
            let c = try g.seal(messageKey: mk, index: 1, counter: n, id: "e608000000000021", ts: 77, sender: "2222222222222222", name: "Bea", text: text, photo: photo, about: about, iv: iv) { _ in self.sig }
            return try XCTUnwrap(g.open(messageKey: mk, index: 1, counter: n, Packet(id: "e608000000000021", ts: g.hideTime(77, c), ttl: 16, cipher: c)))
        }
        for (n, text, about, photo) in [(1, "ok", About.reply(target), nil), (2, "👍", .reaction(target), nil), (3, "", .reaction(target), nil), (4, "📷 Here", .reply(target), jpeg)] as [(Int, String, About, Data?)] {
            let o = try opened(n, text, about, photo: photo)
            let blob = try XCTUnwrap(History.seal(key: key, id: "e608000000000021", group: "gid", epoch: "eid", o, index: 1, counter: n))
            let back = try XCTUnwrap(History.open(key: key, id: "e608000000000021", blob: blob))
            XCTAssertEqual(back.opened.message.about, about); XCTAssertEqual(back.opened.message.text, text); XCTAssertEqual(back.opened.message.photo, photo)
            XCTAssertEqual(back.opened.aboutField, about.field)
        }
        // A message without the field: the copy is byte for byte what it was, and reads back as an ordinary message.
        let plainCopy = try XCTUnwrap(History.open(key: key, id: "e608000000000021", blob: try XCTUnwrap(History.seal(key: key, id: "e608000000000021", group: "gid", epoch: "eid", try opened(5, "hello", nil), index: 1, counter: 5))))
        XCTAssertNil(plainCopy.opened.message.about); XCTAssertNil(plainCopy.opened.aboutField); XCTAssertEqual(plainCopy.opened.message.text, "hello")
        // A type 0x02 message that is no reaction gets no copy: nothing of it is ever shown.
        var void = try opened(6, "hello", nil)
        void = GroupCrypto.Opened(message: { var m = void.message; m.about = .nothing; return m }(), signature: void.signature, digest: void.digest, extra: nil, aboutField: About.reaction(target).field)
        XCTAssertNil(History.seal(key: key, id: "e608000000000021", group: "gid", epoch: "eid", void, index: 1, counter: 6))
    }

    /// The size a reply adds: 21 bytes (2 for the empty 4th field, 2 and 17 for the 5th), 19 next to a photo.
    func testSize() throws {
        func size(_ about: About?, photo: Data? = nil) throws -> Int {
            try group.seal(id: "0000000000000501", ts: 9, sender: "1111111111111111", name: "Bea", text: "ok", photo: photo, about: about, iv: iv, pad: .none) { _ in self.sig }.count
        }
        XCTAssertEqual(try size(.reply(target)) - size(nil), 21)
        XCTAssertEqual(try size(.reply(target), photo: jpeg) - size(nil, photo: jpeg), 19)
    }
}
