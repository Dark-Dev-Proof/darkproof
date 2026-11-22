import XCTest
import CryptoKit
@testable import BlackoutCore

/// Replies, reactions and journey notes against spec/test-vectors.txt (`kind: about`, `about-target`, `reactions`,
/// `receipt-notes`, `journey-note`), which an independent Python implementation made (spec/make-reply-vectors.py).
/// Android checks the same.
final class RepliesVectorsTests: XCTestCase {
    private func of(_ kind: String) -> [[String: String]] { VectorsTests.vectors.filter { $0["kind"] == kind } }

    func testVectorCounts() {
        XCTAssertEqual(of("about").count, 16)
        XCTAssertEqual(of("about-target").count, 2)
        XCTAssertEqual(of("reactions").count, 7)
        XCTAssertEqual(of("receipt-notes").count, 9)
        XCTAssertEqual(of("journey-note").count, 7)
    }

    /// A reader that knows four fields, as before the 5th existed: the signature, sender, name, text, and a 4th field if there is one.
    private func oldRead(_ plain: Data) -> (sender: String, name: String, text: Data, fourth: Data)? {
        let b = [UInt8](plain.dropFirst(64)); var i = 0
        func next() -> [UInt8]? {
            guard i + 2 <= b.count else { return nil }
            let n = Int(b[i]) << 8 | Int(b[i + 1])
            guard i + 2 + n <= b.count else { return nil }
            defer { i += 2 + n }
            return Array(b[(i + 2)..<(i + 2 + n)])
        }
        guard let s = next(), let n = next(), let t = next() else { return nil }
        return (String(decoding: s, as: UTF8.self), String(decoding: n, as: UTF8.self), Data(t), Data(next() ?? []))
    }

    /// The message the vectors' replies and reactions are about: the one in `kind: message-key`, named by its packet
    /// id and the first 8 bytes of SHA-256 of its signed message.
    private func named() throws -> About.Target {
        let first = try XCTUnwrap(VectorsTests.vectors.first { $0["kind"] == "message-key" })
        let digest = Data(SHA256.hash(data: Hex.decode(first["signed_message"]!)))
        return try XCTUnwrap(About.Target(key: first["id"]! + ":" + Hex.encode(digest.prefix(8))))
    }

    func testAboutVectorsOpenToTheirFields() throws {
        let named = try named()
        for v in of("about") {
            let what = v["id"]!
            let secret = Hex.decode(v["secret"]!), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
            let position = Int(v["position"]!)!, number = Int(v["number"]!)!, ts = Int64(v["ts"]!)!
            let mk = try XCTUnwrap(MessageKeys.of(secret, members: [v["member_0"]!, v["member_1"]!]).key(position, number))
            XCTAssertEqual(Hex.encode(mk), v["key"], what)
            let cipher = Hex.decode(v["cipher"]!), text = Hex.decode(v["text_hex"]!), fourth = v["fourth"].map(Hex.decode) ?? Data()
            let p = Packet(id: v["id"]!, ts: Int64(v["wire_ts"]!)!, ttl: 16, cipher: cipher)
            let o = try XCTUnwrap(g.open(messageKey: mk, index: position, counter: number, p), what)
            XCTAssertEqual(o.message.ts, ts, what); XCTAssertEqual(o.message.sender, v["sender"], what); XCTAssertEqual(o.message.name, v["name"], what)
            XCTAssertEqual(o.message.text, String(decoding: text, as: UTF8.self), what)
            XCTAssertEqual(o.extra ?? Data(), fourth, what)
            XCTAssertEqual(o.message.photo, fourth.isEmpty ? nil : Data(fourth.dropFirst()), what)
            XCTAssertEqual(o.signature, Hex.decode(v["signature"]!), what)
            XCTAssertEqual(Hex.encode(o.digest), Hex.encode(Data(SHA256.hash(data: Hex.decode(v["signed_message"]!)))), what)
            XCTAssertEqual(About.Target(o).mark, v["mark"], what); XCTAssertEqual(About.Target(o).id, v["id"], what)
            // What a reader makes of the 5th field.
            switch v["reads"]! {
            case "reply": XCTAssertEqual(o.message.about, .reply(named), what); XCTAssertEqual(named.id, v["about_id"]); XCTAssertEqual(named.mark, v["about_mark"])
            case "reaction": XCTAssertEqual(o.message.about, .reaction(named), what); XCTAssertEqual(named.id, v["about_id"]); XCTAssertEqual(named.mark, v["about_mark"])
            case "nothing": XCTAssertEqual(o.message.about, .nothing, what)
            case "ordinary": XCTAssertNil(o.message.about, what)
            default: XCTFail("unknown `reads` in \(what)")
            }
            XCTAssertEqual(About.read(field: v["fifth"].map(Hex.decode), text: text), o.message.about, what)
            // A reader without the 5th field reads the same sender, name, text and photo field from the same bytes.
            let plain = Hex.decode(v["plain"]!)
            let old = try XCTUnwrap(oldRead(plain), what)
            XCTAssertEqual(old.sender, v["sender"], what); XCTAssertEqual(old.name, v["name"], what); XCTAssertEqual(old.text, text, what); XCTAssertEqual(old.fourth, fourth, what)
            // What this app writes for the same message is byte for byte the vector (not for fields it never writes).
            if let about = o.message.about, about != .nothing, v["sixth"] == nil {
                let again = try g.seal(messageKey: mk, index: position, counter: number, id: v["id"]!, ts: ts, sender: v["sender"]!, name: v["name"]!,
                                       text: String(decoding: text, as: UTF8.self), photo: o.message.photo, about: about, iv: Hex.decode(v["iv"]!)) { _ in Hex.decode(v["signature"]!) }
                XCTAssertEqual(Hex.encode(again), v["cipher"], what); XCTAssertEqual(String(g.hideTime(ts, again)), v["wire_ts"], what)
            }
        }
        XCTAssertEqual(Set(of("about").map { $0["reads"]! }), ["reply", "reaction", "nothing", "ordinary"])
        XCTAssertTrue(of("about").contains { $0["fourth"] != nil && $0["reads"] == "reply" }, "a photo and a reply together")
        // Only listed reactions are reactions: a check mark and a word are not shown at all.
        for (text, reads) in [("e29c85", "nothing"), ("6f6b", "nothing"), ("e29da4", "nothing"), ("e29da4efb88f", "reaction"), ("f09f918d", "reaction"), ("", "reaction")] {
            XCTAssertEqual(of("about").first { $0["text_hex"] == text && $0["fifth"]?.hasPrefix("02") == true }?["reads"], reads, text)
        }
    }

    /// A reply or reaction names a message, not a packet: the same signed message sealed again is still its target,
    /// another message under the same id is not.
    func testAboutTargetVectors() throws {
        let named = try named()
        let first = try XCTUnwrap(VectorsTests.vectors.first { $0["kind"] == "message-key" })
        let original = Packet(id: first["id"]!, ts: Int64(first["wire_ts"]!)!, ttl: 16, cipher: Hex.decode(first["cipher"]!))
        for v in of("about-target") {
            let secret = Hex.decode(v["secret"]!), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
            let position = Int(v["position"]!)!, number = Int(v["number"]!)!
            let mk = try XCTUnwrap(MessageKeys.of(secret, members: [v["member_0"]!, v["member_1"]!]).key(position, number))
            let p = Packet(id: v["id"]!, ts: Int64(v["wire_ts"]!)!, ttl: 16, cipher: Hex.decode(v["cipher"]!))
            XCTAssertEqual(p.id, original.id)
            XCTAssertNotEqual(MeshRouter.key(p), MeshRouter.key(original), "another packet than the one in `kind: message-key`")
            XCTAssertEqual(String(MeshRouter.key(p).suffix(16)), v["content_key"])
            let o = try XCTUnwrap(g.open(messageKey: mk, index: position, counter: number, p))
            XCTAssertEqual(Data(o.message.text.utf8), Hex.decode(v["text_hex"]!))
            XCTAssertEqual(Hex.encode(o.digest), Hex.encode(Data(SHA256.hash(data: Hex.decode(v["signed_message"]!)))))
            XCTAssertEqual(About.Target(o).mark, v["mark"])
            XCTAssertEqual(About.Target(o) == named, v["named"] == "yes", "\(v["text_hex"]!)")
            // On a phone that shows only this packet: is it found by what the replies and reactions name?
            var row = o.message; row.group = "g"; row.mark = About.Target(o)
            XCTAssertEqual(About.targets(in: [row], group: "g")[named.key] != nil, v["named"] == "yes")
        }
        XCTAssertEqual(Set(of("about-target").map { $0["named"]! }), ["yes", "no"])
    }

    /// The rules of "Reactions" as a reader applies them to what it holds: only listed reactions that name a message it shows count.
    func testReactionVectors() throws {
        for v in of("reactions") {
            let target = try XCTUnwrap(About.Target(key: v["target"]!))
            // The one message on this reader's phone; every `r` is a type 0x02 message, so none of them is a message to react to.
            var shown = Message(id: target.id, ts: 1, sender: "9999999999999999", name: "Ann", text: "hello", group: "g")
            shown.mark = target
            var held = Reactions(), rows = [shown]
            for n in 1...9 {
                guard let line = v["r\(n)"] else { break }
                let f = line.split(separator: " ").map(String.init)
                XCTAssertEqual(f.count, 6, line)
                let text = f[5] == "-" ? Data() : Hex.decode(f[5]), names = try XCTUnwrap(About.Target(key: f[4]))
                let about = try XCTUnwrap(About.read(field: Data([About.reactionType]) + Hex.decode(names.id) + Hex.decode(names.mark), text: text))
                var row = Message(id: f[2], ts: Int64(f[1])!, sender: f[0], name: "", text: String(decoding: text, as: UTF8.self), group: "g")
                row.about = about; row.mark = About.Target(key: "\(f[2]):\(f[3])")
                rows.append(row)
                // A text that isn't listed is no reaction: nothing is held for it.
                guard case .reaction(let t) = about else { XCTAssertEqual(about, .nothing, line); continue }
                held.add("\(f[2]):\(f[3])", Reactions.Entry(group: "g", target: t.key, sender: f[0], text: String(decoding: text, as: UTF8.self), ts: Int64(f[1])!, id: f[2]))
            }
            // Reactions are shown only under what can be a target: the message, never one of the type 0x02 messages.
            let targets = About.targets(in: rows, group: "g")
            XCTAssertEqual(Array(targets.keys), [target.key], v["target"]!)
            let chips = held.chips(group: "g", target: target.key, me: "me")
            let got = chips.isEmpty ? "-" : chips.map { "\(Hex.encode(Data($0.text.utf8)))=\($0.count)" }.joined(separator: " ")
            XCTAssertEqual(got, v["counts"], "\(v)")
        }
    }

    func testReceiptNoteVectors() {
        for v in of("receipt-notes") {
            let text = v["text"]!
            XCTAssertEqual(Receipt.ids(in: text).joined(separator: " "), v["ids"] == "-" ? "" : v["ids"]!, "the reader without notes: \(text.prefix(60))")
            let got = Receipt.entries(in: text).map { e -> String in
                guard let n = e.note else { return e.id }
                return "\(e.id)/\(n.hops)/\(n.link.letter.map(String.init) ?? "?")"
            }.joined(separator: " ")
            XCTAssertEqual(got, v["entries"] == "-" ? "" : v["entries"]!, "\(text.prefix(60))")
            XCTAssertEqual(Receipt.entries(in: text).map(\.id), Receipt.ids(in: text))
        }
    }

    func testJourneyNoteVectors() throws {
        for v in of("journey-note") {
            let link = try XCTUnwrap(Journey.Link(letter: try XCTUnwrap(v["link"]!.utf8.first)))
            // The ttl as the phone holds it: a received ttl above 16 is lowered to 16 (`MeshRouter.clamp`).
            let p = MeshRouter.clamp(Packet(id: "0000000000000001", ts: 1, ttl: Int(v["ttl"]!)!, cipher: Data()))
            XCTAssertEqual(Journey.Note(ttl: p.ttl, link: link)?.token, v["note"], "\(v)")
            XCTAssertEqual(Journey.Note(ttl: Int(v["ttl"]!)!, link: link)?.token, v["note"], "\(v)")
        }
    }
}
