import XCTest
import CryptoKit
@testable import BlackoutCore

/// "Disappearing messages" against spec/test-vectors.txt (`kind: timer-field`, `timer-rekey`, `timer-epoch`,
/// `timer-rival`, `timer-due`, `timer-notice`), made by spec/make-reply-vectors.py. Android checks the same.
final class TimerVectorsTests: XCTestCase {
    private func of(_ kind: String) -> [[String: String]] { VectorsTests.vectors.filter { $0["kind"] == kind } }
    private func timer(_ s: String) -> Int? { s == "unknown" || s == "none" ? nil : Int(s)! }
    private let anna = "1111111111111111", ben = "2222222222222222", cara = "3333333333333333", dan = "4444444444444444"
    private let t0: Int64 = 1_794_000_000_000, hour: Int64 = 3_600_000

    func testVectorCounts() {
        XCTAssertEqual(of("timer-field").count, 15); XCTAssertEqual(of("timer-rekey").count, 2); XCTAssertEqual(of("timer-epoch").count, 12)
        XCTAssertEqual(of("timer-rival").count, 4); XCTAssertEqual(of("timer-due").count, 11); XCTAssertEqual(of("timer-notice").count, 2)
        XCTAssertEqual(of("timer-late").count, 8)
    }

    /// `kind: timer-late` (finding M4): a key that came late, kept only to read with, at number 5, after a member's key 6
    /// that is current. It speaks for the timer and the chat's lines only if it wins at 5, its maker is an admin of the
    /// winning epoch 4, and its maker is an admin of the epoch it beats (Anna's 5). Built as a phone holds them: 4 (admin
    /// Anna, timer `before`), Anna's 5 inheriting it (which keeps Ben an admin, or demotes him), the late 5 by Ben (an
    /// admin of 4, or not), Cara's 6.
    func testLateKeysSpeakOnlyAsWinnersByAnAdminOfTheWinnerBeforeAndOfTheEpochTheyBeat() throws {
        let all = [anna, ben, cara]
        func epoch(_ n: Int64, by: String, secret: Data, admins: [String], members: [String], timer: Int?, rank: Int) -> Keyring.Epoch {
            Keyring.Epoch.fresh(number: n, made: t0 + n * hour, secret: secret, roster: all, members: members, admins: admins, by: by, rank: rank, taken: t0 + n * hour,
                                timerRaw: timer, timerSetHere: false, timerCounts: timer != nil)
        }
        let real5 = Data(repeating: 0x55, count: 32), real5Hash = epoch(5, by: anna, secret: real5, admins: [anna], members: all, timer: nil, rank: Keyring.byAdmin).hash
        /// A secret whose epoch's hash comes before (or after) Anna's 5.
        func secret(before: Bool) -> Data {
            for b in 0...255 {
                let d = Data(repeating: UInt8(b), count: 32)
                let h = epoch(5, by: ben, secret: d, admins: [anna], members: [], timer: nil, rank: Keyring.byAdmin).hash
                if (h < real5Hash) == before && d != real5 { return d }
            }
            fatalError("no secret")
        }
        for v in of("timer-late") {
            let wins = v["wins"] == "yes", admin = v["maker"] == "admin", kept = v["beaten"] == "admin", before = Int(v["before"]!)!
            let ring = Keyring()
            ring.add(epoch(4, by: anna, secret: Data(repeating: 0x44, count: 32), admins: admin ? [anna, ben] : [anna], members: all, timer: before, rank: Keyring.byAdmin))
            ring.add(epoch(5, by: anna, secret: real5, admins: kept ? [anna, ben] : [anna], members: all, timer: nil, rank: Keyring.byAdmin))
            ring.add(epoch(6, by: cara, secret: Data(repeating: 0x66, count: 32), admins: [anna], members: all, timer: nil, rank: .max))
            XCTAssertEqual(ring.timer.seconds, before, "without the late key")
            let late = epoch(5, by: ben, secret: secret(before: wins), admins: [anna, ben], members: [], timer: timer(v["field"]!), rank: Keyring.byAdmin)
            ring.add(late)
            XCTAssertEqual(ring.won(late), wins, "\(v)")
            XCTAssertEqual(ring.timer.seconds, Int(v["timer"]!)!, "\(v)")
            XCTAssertEqual(ring.counted.contains { $0 === late } ? "yes" : "no", v["lines"], "\(v)")
        }
    }

    func testFields() {
        for v in of("timer-field") {
            let got = GroupTimer.read(field: Hex.decode(v["field"]!))
            XCTAssertEqual(got.map(String.init) ?? "ignored", v["reads"], v["field"]!)
            if let got, GroupTimer.choices.contains(got) || got == 0, v["field"]!.count == 10, Int(v["field"]!.dropFirst(2), radix: 16) == got {
                XCTAssertEqual(Hex.encode(GroupTimer.field(got)), v["field"], "what this app writes")
            }
        }
    }

    /// A reader that knows four fields, as before the 5th existed.
    private func oldFourth(_ plain: Data) -> Data? {
        let b = [UInt8](plain.dropFirst(64)); var i = 0
        func next() -> [UInt8]? {
            guard i + 2 <= b.count else { return nil }
            let n = Int(b[i]) << 8 | Int(b[i + 1])
            guard i + 2 + n <= b.count else { return nil }
            defer { i += 2 + n }
            return Array(b[(i + 2)..<(i + 2 + n)])
        }
        guard next() != nil, next() != nil, next() != nil else { return nil }
        return next().map { Data($0) }
    }

    func testANewKeyWithTheTimerIsTheSameKeyToEveryReader() throws {
        var fourths = Set<String>()
        for v in of("timer-rekey") {
            let rekeys = GroupCrypto(code: v["code"]!).rekeys()
            let raw = Hex.decode(v["packet"]!)
            XCTAssertEqual(raw.count, 16_018)
            let p = try XCTUnwrap(Packet.decode(raw))
            XCTAssertEqual(p.id, v["id"]); XCTAssertEqual(p.ts, Int64(v["ts"]!)!); XCTAssertEqual(p.ttl, 16)
            let o = try XCTUnwrap(rekeys.openSigned(p))
            XCTAssertEqual(o.message.sender, v["sender"]); XCTAssertEqual(o.message.name, v["name"]); XCTAssertEqual(o.message.text, "")
            XCTAssertEqual(Hex.encode(try XCTUnwrap(o.extra)), v["fourth"]); XCTAssertEqual(Hex.encode(o.digest), v["digest"])
            let r = try XCTUnwrap(Rekey.decode(try XCTUnwrap(o.extra)))
            XCTAssertEqual(r.epoch, Int64(v["epoch"]!)!); XCTAssertEqual(r.made, Int64(v["made"]!)!); XCTAssertEqual(Hex.encode(r.maker), v["maker"]); XCTAssertFalse(r.removal)
            XCTAssertEqual(r.admins.joined(separator: " "), v["admins"]); XCTAssertEqual(r.members.joined(separator: " "), v["members"]); XCTAssertNil(r.answers)
            XCTAssertEqual(r.parts.map(\.id).joined(separator: " "), v["members"]); XCTAssertTrue(r.parts.allSatisfy { Hex.encode($0.box) == v["box"] })
            XCTAssertEqual(MemberKey.id(r.maker), o.message.sender)
            // The 5th field: the timer, or none.
            XCTAssertEqual(GroupTimer.read(field: o.fifth).map(String.init) ?? "none", v["timer"])
            XCTAssertEqual(o.fifth.map { Hex.encode($0) } ?? "-", v["fifth"])
            XCTAssertNil(o.message.about)
            // A reader that stops after the 4th field takes the same key from the same bytes.
            let key = SymmetricKey(data: Hex.decode(v["rekey_key"]!))
            let plain = try AES.GCM.open(AES.GCM.SealedBox(combined: p.cipher), using: key, authenticating: Data("\(p.id)|\(p.ts)".utf8))
            XCTAssertEqual(Rekey.decode(try XCTUnwrap(oldFourth(plain))), r)
            fourths.insert(v["fourth"]!)
            // What this app seals for the same key is byte for byte the vector.
            let again = try rekeys.seal(id: p.id, ts: p.ts, sender: o.message.sender, name: o.message.name, text: "", extra: r.encode(), fifth: o.fifth,
                                        iv: Hex.decode(v["iv"]!), pad: .full) { _ in Hex.decode(v["signature"]!) }
            XCTAssertEqual(Packet(id: p.id, ts: p.ts, ttl: 16, cipher: again).encode(), raw)
        }
        XCTAssertEqual(fourths.count, 1, "with and without the timer: one and the same 4th field")
        XCTAssertEqual(Set(of("timer-rekey").map { $0["timer"]! }), ["86400", "none"])
    }

    /// Epoch 5 on Cara's phone: Anna and Ben are admins, Cara and Dan members.
    private func base(timer: Int?) -> Keyring {
        let r = Keyring()
        r.add(.init(number: 5, made: t0 - 100 * hour, secret: Data(repeating: 5, count: 32), members: [anna, ben, cara, dan], admins: [anna, ben], by: anna, rank: Keyring.byAdmin,
                    timerRaw: timer, timer: timer, timerCounts: timer != nil))
        return r
    }
    private func key(_ epoch: Int64, made: Int64) -> Rekey {
        let m = [anna, ben, cara, dan]
        return Rekey(epoch: epoch, made: made, maker: Data(count: 33), admins: [anna, ben], answers: nil, parts: m.map { ($0, Data(count: 1)) }, members: m)
    }
    @discardableResult private func take(_ ring: Keyring, _ r: Rekey, by maker: String, timer: Int?, secret: Data, now: Int64) -> Bool {
        if case .added = ring.take(r, maker: maker, me: cara, door: "d", oldDoor: false, removedAt: nil, now: now, timer: timer, open: { _ in secret }) { return true }
        return false
    }

    func testWhoSetsItAndHowEpochsInherit() {
        for v in of("timer-epoch") {
            let had = v["had"] == "yes", admin = v["maker"] == "admin", field = timer(v["field"]!), before = timer(v["before"]!), secret = Data(repeating: 9, count: 32)
            let ring: Keyring
            if !had {
                ring = Keyring() // the key's own admin list names its maker, or not
                XCTAssertTrue(take(ring, key(6, made: t0), by: admin ? anna : dan, timer: field, secret: secret, now: t0), "\(v)")
            } else if v["kept"] == "read" {
                ring = base(timer: before) // a member's weekly key first, so that the tested key is numbered below the current epoch
                XCTAssertTrue(take(ring, key(6, made: t0), by: cara, timer: nil, secret: Data(repeating: 6, count: 32), now: t0))
                XCTAssertTrue(take(ring, key(5, made: t0 + 2 * hour), by: admin ? anna : dan, timer: field, secret: secret, now: t0 + 3 * hour), "\(v)")
                XCTAssertEqual(ring.current.number, 6)
            } else {
                ring = base(timer: before)
                XCTAssertTrue(take(ring, key(6, made: t0), by: admin ? anna : cara, timer: field, secret: secret, now: t0), "\(v)")
                XCTAssertEqual(ring.current.number, 6)
            }
            XCTAssertEqual(ring.timer.seconds, timer(v["timer"]!), "\(v)")
        }
    }

    func testTwoAdminsAtOnce() throws {
        for v in of("timer-rival") {
            let ring = base(timer: 0)
            let keys: [String: (maker: String, secret: Data, timer: Int)] = ["a": (anna, Hex.decode(v["secret_a"]!), Int(v["timer_a"]!)!), "b": (ben, Hex.decode(v["secret_b"]!), Int(v["timer_b"]!)!)]
            var now = t0
            for step in v["order"]!.split(separator: " ").map(String.init) {
                if step == "member" { XCTAssertTrue(take(ring, key(7, made: t0 + 2 * hour), by: cara, timer: nil, secret: Data(repeating: 7, count: 32), now: t0 + 2 * hour)); now = t0 + 3 * hour; continue }
                let k = try XCTUnwrap(keys[step])
                XCTAssertTrue(take(ring, key(6, made: t0), by: k.maker, timer: k.timer, secret: k.secret, now: now), "\(step) in \(v["order"]!)")
            }
            // The epochs' hashes are the vector's, so the winner is too.
            for (name, k) in keys {
                if let e = ring.all.first(where: { $0.number == 6 && $0.by == k.maker }) { XCTAssertEqual(e.hash, v["hash_\(name)"]) }
            }
            XCTAssertEqual(v["hash_a"]! < v["hash_b"]! ? "a" : "b", v["wins"])
            XCTAssertEqual(ring.timer.seconds, Int(v["timer"]!)!, v["order"]!)
            XCTAssertEqual(Int(v["timer"]!)!, keys[v["wins"]!]!.timer, "the winner's value")
        }
    }

    func testWhenAMessageGoes() {
        for v in of("timer-due") {
            let watch = Disappear.Watch(seconds: timer(v["seconds"]!), since: Int64(v["value_since"]!)!), now = Int64(v["now"]!)!
            let held = [KeepsLess.Held(name: "m", since: v["arrived"] == "none" ? nil : Int64(v["arrived"]!)!)]
            let byDay = v["high_risk"] == "yes" && !KeepsLess.expired(held, now: now).isEmpty
            let byTimer = watch.deletes(now: now).map { !Disappear.due(held, timerMs: $0, now: now).isEmpty } ?? false
            XCTAssertEqual(byDay ? "high-risk" : byTimer ? "timer" : "no", v["goes"], "\(v)")
        }
    }

    func testTheNoticeMessage() throws {
        for v in of("timer-notice") {
            let secret = Hex.decode(v["secret"]!), g = GroupCrypto.fromSecret(MessageKeys.outer(secret))
            let position = Int(v["position"]!)!, number = Int(v["number"]!)!, ts = Int64(v["ts"]!)!, seconds = Int(v["timer"]!)!
            let mk = try XCTUnwrap(MessageKeys.of(secret, members: [v["member_0"]!, v["member_1"]!]).key(position, number))
            let p = Packet(id: v["id"]!, ts: Int64(v["wire_ts"]!)!, ttl: 16, cipher: Hex.decode(v["cipher"]!))
            let o = try XCTUnwrap(g.open(messageKey: mk, index: position, counter: number, p))
            XCTAssertEqual(o.message.text, v["text"]); XCTAssertEqual(o.message.text, GroupTimer.notice(seconds)); XCTAssertEqual(o.message.ts, ts)
            XCTAssertEqual(o.message.notice, .timer(seconds)); XCTAssertEqual(o.message.isSilent, v["shown"] == "no")
            XCTAssertNil(o.message.about); XCTAssertNil(o.extra)
            XCTAssertEqual(Hex.encode(try XCTUnwrap(o.fifth)), v["fifth"]); XCTAssertEqual(Hex.encode(GroupTimer.field(seconds)), v["fifth"])
            XCTAssertEqual(Hex.encode(o.digest), Hex.encode(Data(SHA256.hash(data: Hex.decode(v["signed_message"]!)))))
            let again = try g.seal(messageKey: mk, index: position, counter: number, id: v["id"]!, ts: ts, sender: v["sender"]!, name: v["name"]!, text: GroupTimer.notice(seconds),
                                   fifth: GroupTimer.field(seconds), iv: Hex.decode(v["iv"]!)) { _ in Hex.decode(v["signature"]!) }
            XCTAssertEqual(Hex.encode(again), v["cipher"])
        }
    }
}
