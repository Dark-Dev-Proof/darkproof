import XCTest
import CryptoKit
@testable import BlackoutCore

/// Two bugs in the message-delivery path (the class a receipt is stored in, and the row a late signature check binds to).
/// The rules live in the core (StoreCap, KeyWait) so that they can be checked here; Engine only calls them. Android's
/// DeliveryFixTest checks the same. Dummy keys only.
final class DeliveryFixTests: XCTestCase {
    private let everyone = GroupCrypto(code: GroupCrypto.publicCode)
    private let now: Int64 = 1_800_000_000_000
    private func memberId(_ k: P256.Signing.PrivateKey) -> String { MemberKey.id(k.publicKey.compressedRepresentation) }

    // MARK: 1. A receipt is the group's own only once its signature checks

    func testAFloodOfBadReceiptsNeverPushesARealMessageOut() {
        // How the delivery path now classes a receipt by its signature check (Engine.check -> StoreCap.received):
        XCTAssertEqual(StoreCap.received(false), .unreadable, "a forged or non-member receipt is not the group's own")
        XCTAssertEqual(StoreCap.received(nil), .group, "one still waiting for its sender's key is kept")
        XCTAssertEqual(StoreCap.received(true), .group, "one that checks is the group's own")
        // 200 real group messages, and a flood of receipts whose signature doesn't check, dated newest and enough to fill the store.
        let real = (1...200).map { StoreCap.Entry(id: "m\($0)", ts: 1_000 + Int64($0), size: 300, kind: .group) }
        let junk = (1...21_000).map { StoreCap.Entry(id: "r\($0)", ts: 9_000 + Int64($0), size: 300, kind: StoreCap.received(false)) }
        let gone = Set(StoreCap.overflow(real + junk))
        XCTAssertTrue(gone.allSatisfy { $0.hasPrefix("r") }, "only the bad receipts go")
        XCTAssertTrue(real.allSatisfy { !gone.contains($0.id) }, "every real message stays")
        // Classed as the group's own, as they were before the fix, the same flood pushed real messages out.
        let asGroup = junk.map { StoreCap.Entry(id: $0.id, ts: $0.ts, size: $0.size, kind: .group) }
        XCTAssertTrue(StoreCap.overflow(real + asGroup).contains { $0.hasPrefix("m") }, "the bug this fixes: bad receipts classed .group evict real messages")
    }

    // MARK: 2. After a restart, a late signature check binds to the row it was made for, not to a shared id

    /// The store holds two packets under one id (A's first, B's second): the first is shown under that id, the second
    /// under its key (StoreCap.name). Each waits for its own sender's key, so the rescan must bind each pending check to
    /// the row it is shown as — its store name — not to the bare packet id, which both carry.
    func testARescanBindsEachWaitingRowToItsOwnPacketNotToTheSharedId() throws {
        let a = P256.Signing.PrivateKey(), aId = memberId(a)
        let b = P256.Signing.PrivateKey(), bId = memberId(b)
        let id = Packet.newId(mailbox: everyone.mailbox)
        let c1 = try everyone.seal(id: id, ts: now, sender: aId, name: "A", text: "first", photo: nil, extra: nil, iv: nil) { try MemberKey.sign(a, $0) }
        let c2 = try everyone.seal(id: id, ts: now, sender: bId, name: "B", text: "second", photo: nil, extra: nil, iv: nil) { try MemberKey.sign(b, $0) }
        let p1 = Packet(id: id, ts: now, ttl: 16, cipher: c1), p2 = Packet(id: id, ts: now, ttl: 16, cipher: c2)
        let o1 = try XCTUnwrap(everyone.openSigned(p1)), o2 = try XCTUnwrap(everyone.openSigned(p2))
        let row1 = StoreCap.name(p1, first: true), row2 = StoreCap.name(p2, first: false)
        XCTAssertEqual(row1, id, "the first is shown under the id"); XCTAssertNotEqual(row1, row2, "the second under its key")
        XCTAssertEqual(o1.message.id, id); XCTAssertEqual(o2.message.id, id, "both packets carry the bare id")
        XCTAssertNotEqual(o1.key, o2.key, "one id, two packets")

        // The fix: the rescan binds each pending under the row it is shown as (its store name).
        var w = KeyWait<String, GroupCrypto.Opened>()
        XCTAssertTrue(w.message(o1.key, channel: Blocks.everyone, sender: aId, id: row1, shown: true, o1))
        XCTAssertTrue(w.message(o2.key, channel: Blocks.everyone, sender: bId, id: row2, shown: true, o2))
        let forA = w.take(channel: Blocks.everyone, sender: aId)
        XCTAssertEqual(forA.map(\.row), [row1]); XCTAssertEqual(forA.first?.item.message.text, "first", "A's key checks A's row")
        let forB = w.take(channel: Blocks.everyone, sender: bId)
        XCTAssertEqual(forB.map(\.row), [row2]); XCTAssertEqual(forB.first?.item.message.text, "second", "B's key checks B's row")

        // The bug: binding both under the shared packet id (o.message.id) voided A's own check and left B's signature
        // bound to the row that shows A — so B's signature would mark or remove A's message.
        var bug = KeyWait<String, GroupCrypto.Opened>()
        _ = bug.message(o1.key, channel: Blocks.everyone, sender: aId, id: o1.message.id, shown: true, o1)
        _ = bug.message(o2.key, channel: Blocks.everyone, sender: bId, id: o2.message.id, shown: true, o2)
        XCTAssertTrue(bug.take(channel: Blocks.everyone, sender: aId).isEmpty, "A's row lost its own waiting check")
        XCTAssertEqual(bug.take(channel: Blocks.everyone, sender: bId).map(\.row), [id], "B's signature would resolve the row that shows A")
    }
}
