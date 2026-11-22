package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test

/**
 * Two bugs in the message-delivery path (receipt storage class, and the row a late signature check binds to). The rules
 * live in the core (StoreCap, KeyWait) so that they can be checked here; MeshEngine only calls them. The iPhone's
 * DeliveryFixTests check the same. Dummy keys only.
 */
class DeliveryFixTest {
    private val everyone = GroupCrypto(GroupCrypto.PUBLIC_CODE)
    private val now = 1_800_000_000_000L
    private fun memberId(k: java.security.KeyPair) = P256.memberId(P256.compress(k.public))

    // ---- 1. A receipt is the group's own only once its signature checks ----

    @Test fun aFloodOfBadReceiptsNeverPushesARealMessageOut() {
        // How the delivery path now classes a receipt by its signature check ([MeshEngine.check] -> StoreCap.received):
        assertEquals("a forged or non-member receipt is not the group's own", StoreCap.Kind.UNREADABLE, StoreCap.received(false))
        assertEquals("one still waiting for its sender's key is kept", StoreCap.Kind.GROUP, StoreCap.received(null))
        assertEquals("one that checks is the group's own", StoreCap.Kind.GROUP, StoreCap.received(true))
        // 200 real group messages, and a flood of receipts whose signature doesn't check, dated newest and enough to fill the store.
        val real = (1..200).map { StoreCap.Entry("m$it", 1_000L + it, 300, StoreCap.Kind.GROUP) }
        val junk = (1..21_000).map { StoreCap.Entry("r$it", 9_000L + it, 300, StoreCap.received(false)) }
        val gone = StoreCap.overflow(real + junk).toSet()
        assertTrue("only the bad receipts go", gone.all { it.startsWith("r") })
        assertTrue("every real message stays", real.all { it.id !in gone })
        // Classed as the group's own, as they were before the fix, the same flood pushed real messages out.
        val asGroup = junk.map { StoreCap.Entry(it.id, it.ts, it.size, StoreCap.Kind.GROUP) }
        assertTrue("the bug this fixes: bad receipts classed GROUP evict real messages", StoreCap.overflow(real + asGroup).any { it.startsWith("m") })
    }

    // ---- 2. After a restart, a late signature check binds to the row it was made for, not to a shared id ----

    /**
     * The store holds two packets under one id (A's first, B's second): the first is shown under that id, the second
     * under its key ([StoreCap.name]). Each waits for its own sender's key, so the rescan must bind each pending check to
     * the row it is shown as — its store name — not to the bare packet id, which both carry. Binding both to the packet
     * id let B's signature verify (or remove) the row that shows A's message.
     */
    @Test fun aRescanBindsEachWaitingRowToItsOwnPacketNotToTheSharedId() {
        val a = P256.newKeyPair(); val aId = memberId(a)
        val b = P256.newKeyPair(); val bId = memberId(b)
        val id = Packet.newId(everyone.mailbox)
        val p1 = Packet(id, now, 16, everyone.seal(id, now, aId, "A", "first") { P256.sign(a.private, it) })
        val p2 = Packet(id, now, 16, everyone.seal(id, now, bId, "B", "second") { P256.sign(b.private, it) })
        val o1 = everyone.openSigned(p1)!!; val o2 = everyone.openSigned(p2)!!
        val row1 = StoreCap.name(p1, first = true); val row2 = StoreCap.name(p2, first = false)
        assertEquals("the first is shown under the id", id, row1); assertNotEquals("the second under its key", row1, row2)
        assertEquals("both packets carry the bare id", id, o1.message.id); assertEquals(id, o2.message.id)
        assertNotEquals("one id, two packets", o1.key, o2.key)

        // The fix: the rescan binds each pending under the row it is shown as (its store name).
        val w = KeyWait<String, GroupCrypto.Opened>()
        assertTrue(w.message(o1.key, Blocks.EVERYONE, aId, row1, shown = true, o1))
        assertTrue(w.message(o2.key, Blocks.EVERYONE, bId, row2, shown = true, o2))
        val forA = w.take(Blocks.EVERYONE, aId); assertEquals(listOf(row1), forA.map { it.row }); assertSame("A's key checks A's row", o1, forA.single().item)
        val forB = w.take(Blocks.EVERYONE, bId); assertEquals(listOf(row2), forB.map { it.row }); assertSame("B's key checks B's row", o2, forB.single().item)

        // The bug: binding both under the shared packet id ([GroupCrypto.Opened.message] id) voided A's own check and
        // left B's signature bound to the row that shows A — so B's signature would mark or remove A's message.
        val bug = KeyWait<String, GroupCrypto.Opened>()
        bug.message(o1.key, Blocks.EVERYONE, aId, o1.message.id, shown = true, o1)
        bug.message(o2.key, Blocks.EVERYONE, bId, o2.message.id, shown = true, o2)
        assertTrue("A's row lost its own waiting check", bug.take(Blocks.EVERYONE, aId).isEmpty())
        assertEquals("B's signature would resolve the row that shows A", listOf(id), bug.take(Blocks.EVERYONE, bId).map { it.row })
    }
}
