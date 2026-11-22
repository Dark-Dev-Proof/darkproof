package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The group's timer for disappearing messages (spec/FORMAT.md, "Disappearing messages"): the field, who may set it, how
 * epochs inherit it, when it starts deleting, and that phones without it take the same new keys as before. The iPhone's
 * TimerTests checks the same.
 */
class TimerTest {
    private val anna = "1111111111111111"; private val ben = "2222222222222222"; private val cara = "3333333333333333"; private val dan = "4444444444444444"
    private val hour = 3_600_000L; private val t0 = 1_794_000_000_000L
    private fun hex(b: ByteArray) = Hex.encode(b)

    // MARK: - The field

    @Test fun theFiveFieldsAsWritten() {
        val framed = listOf(0 to "00050300000000", 3_600 to "00050300000e10", 28_800 to "00050300007080", 86_400 to "00050300015180", 604_800 to "00050300093a80")
        for ((seconds, h) in framed) {
            val f = GroupTimer.field(seconds)
            assertEquals(h, hex(byteArrayOf(0, f.size.toByte()) + f))
            assertEquals(seconds, GroupTimer.read(f))
        }
        assertEquals(listOf(3_600, 28_800, 86_400, 604_800), GroupTimer.CHOICES)
    }

    @Test fun readingTheNumber() {
        fun read(n: Long) = GroupTimer.read(byteArrayOf(0x03, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        assertEquals(0, read(0)); assertEquals(300, read(1)); assertEquals(300, read(299)); assertEquals(300, read(300)); assertEquals(301, read(301))
        assertEquals(604_800, read(604_800)); assertEquals("longer than the normal rule means nothing: off", 0, read(604_801)); assertEquals(0, read(0xFFFF_FFFFL))
        assertNull("4 bytes", GroupTimer.read(byteArrayOf(0x03, 0, 0, 0x0e))); assertNull("6 bytes", GroupTimer.read(byteArrayOf(0x03, 0, 0, 0x0e, 0x10, 0)))
        assertNull("another type", GroupTimer.read(byteArrayOf(0x04, 0, 0, 0x0e, 0x10))); assertNull(GroupTimer.read(null)); assertNull(GroupTimer.read(ByteArray(0)))
        assertNull("a reply's field in a new key means nothing", GroupTimer.read(byteArrayOf(0x01) + ByteArray(16)))
        assertEquals(Notice.Timer(3_600), Notice.read(GroupTimer.field(3_600))); assertNull(Notice.read(byteArrayOf(0x01) + ByteArray(16)))
        assertTrue(Notice.Timer(0).isSilent); assertTrue(Notice.Timer(0).staysOffRadio)
    }

    @Test fun wording() {
        assertEquals("1 hour", GroupTimer.label(3_600)); assertEquals("8 hours", GroupTimer.label(28_800)); assertEquals("1 day", GroupTimer.label(86_400))
        assertEquals("1 week", GroupTimer.label(604_800)); assertEquals("5 minutes", GroupTimer.label(300)); assertEquals("2 days", GroupTimer.label(172_800))
        assertEquals("⏱ Messages here now disappear 1 day after they reach each phone. Older DarkProof versions keep them 1 to 2 weeks.", GroupTimer.notice(86_400))
        assertEquals("⏱ The timer is off. Messages disappear after 1 to 2 weeks.", GroupTimer.notice(0))
        assertEquals("They disappear 1 day after they reach each phone.", GroupTimer.chatLine(86_400))
        assertEquals("They disappear after one to two weeks.", GroupTimer.chatLine(0)); assertEquals("They disappear after one to two weeks.", GroupTimer.chatLine(null))
        assertEquals("the shorter of the two is what happens", "They disappear 1 hour after they reach each phone.", GroupTimer.chatLine(3_600, highRisk = true))
        assertEquals("High-risk mode: they leave this phone 1 day after they arrive.", GroupTimer.chatLine(604_800, highRisk = true))
        assertEquals("High-risk mode: they leave this phone 1 day after they arrive.", GroupTimer.chatLine(null, highRisk = true))
        assertEquals("High-risk mode: they leave this computer 1 day after they arrive.", GroupTimer.chatLine(null, highRisk = true, device = "computer"))
        assertEquals("After 1 day, set by Anna", GroupTimer.securityLine(86_400, "Anna", false))
        assertEquals("After 1 day", GroupTimer.securityLine(86_400, null, true))
        assertEquals("After 1 week, set by you. On this phone, 1 day after they arrive.", GroupTimer.securityLine(604_800, "you", true))
        assertEquals("After one to two weeks", GroupTimer.securityLine(0, null, false))
        assertEquals("After one to two weeks. On this phone, 1 day after they arrive.", GroupTimer.securityLine(0, null, true))
        assertEquals("Not known yet. Until an admin's key brings it, this phone keeps messages one to two weeks.", GroupTimer.securityLine(null, null, false))
        assertEquals("Messages disappear 1 day after they reach each phone.", GroupTimer.sheetLine(86_400))
        assertEquals("Messages disappear after one to two weeks.", GroupTimer.sheetLine(0))
        assertEquals("Not known on this phone yet. Until an admin's key brings it, messages stay one to two weeks.", GroupTimer.sheetLine(null))
        assertEquals("Anna set messages to disappear after 8 hours", GroupTimer.changeLine(28_800, "Anna"))
        assertEquals("You set messages to disappear after 1 day", GroupTimer.changeLine(86_400, "You"))
        assertEquals("Anna switched the timer off. Messages disappear after 1 to 2 weeks.", GroupTimer.changeLine(0, "Anna"))
        assertEquals("Messages disappear after 1 hour", GroupTimer.changeLine(3_600, null))
        assertEquals("The timer is off. Messages disappear after 1 to 2 weeks.", GroupTimer.changeLine(0, null))
        assertTrue(GroupTimer.explainer(86_400).startsWith("Each phone deletes a message 1 day after it reaches it."))
        // The line above the chat, as before for a group without a timer, and with one.
        assertEquals("Only people in Family can read these. They disappear after one to two weeks.", SecurityWords.chatNote("Family", false, "phone", 0))
        assertEquals("Only people in Family can read these. They disappear 8 hours after they reach each phone.", SecurityWords.chatNote("Family", false, "phone", 28_800))
        assertEquals("Only people in Family can read these. High-risk mode: they leave this phone 1 day after they arrive.", SecurityWords.chatNote("Family", true, "phone", 604_800))
        // Never said (the brief's list).
        val all = listOf(GroupTimer.notice(3_600), GroupTimer.chatLine(3_600), GroupTimer.securityLine(3_600, "Anna", true), GroupTimer.sheetLine(3_600), GroupTimer.explainer(3_600), GroupTimer.changeLine(3_600, "Anna")).joinToString(" ").lowercase()
        for (never in listOf("deleted for everyone", "gone from the network", "can't be saved", "screenshots blocked")) assertFalse(never, never in all)
    }

    // MARK: - A new key that carries it, and readers that don't know it

    /** The body's fields as a reader read them before the 5th field existed: sender, text, and a 4th field. */
    private class Old(val sender: String, val text: ByteArray, val fourth: ByteArray)
    private fun oldRead(plain: ByteArray): Old? {
        val b = plain.copyOfRange(64, plain.size); var i = 0
        fun next(): ByteArray? {
            if (i + 2 > b.size) return null
            val n = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (i + 2 + n > b.size) return null
            return b.copyOfRange(i + 2, i + 2 + n).also { i += 2 + n }
        }
        val s = next() ?: return null; next() ?: return null; val t = next() ?: return null
        return Old(String(s, Charsets.UTF_8), t, next() ?: ByteArray(0))
    }
    private fun open(key: ByteArray, cipher: ByteArray, id: String, ts: Long): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, cipher, 0, 12)); updateAAD("$id|$ts".toByteArray())
        doFinal(cipher, 12, cipher.size - 12)
    }

    @Test fun aNewKeyWithTheFieldIsTheSameNewKeyToAReaderWithoutIt() {
        val code = "test-group-1"; val rekeys = GroupCrypto(code).rekeys(); val id = "0123456789abcdef"
        val r = Rekey(6, t0, ByteArray(33) { 2 }, listOf(anna), null, listOf(anna, ben).map { it to ByteArray(40) { 7 } }, members = listOf(anna, ben))
        val iv = ByteArray(12) { 9 }; val sig = ByteArray(64) { 7 }
        val with = rekeys.seal(id, t0, anna, "Anna", "", extra = r.encode(), iv = iv, pad = Pad.FULL, fifth = GroupTimer.field(86_400)) { sig }
        val without = rekeys.seal(id, t0, anna, "Anna", "", extra = r.encode(), iv = iv, pad = Pad.FULL) { sig }
        assertEquals("padded to the same size: the field can't be seen from outside", without.size, with.size); assertEquals(Packet.MAX_CIPHER, with.size)
        for ((cipher, timer) in listOf(with to 86_400, without to null)) {
            val o = rekeys.openSigned(Packet(id, t0, 16, cipher))!!
            assertArrayEquals("today's reader of the 4th field takes the same key", r.encode(), Rekey.decode(o.extra!!)!!.encode())
            assertEquals(timer, GroupTimer.read(o.fifth))
            assertNull("no reply, no reaction", o.message.about)
        }
        // A reader that stops after the 4th field: the same bytes, the same key.
        val old = oldRead(open(GroupCrypto.hkdf(GroupCrypto.deriveKey(code), "darkproof-rekey-v1"), with, id, t0))!!
        assertEquals(anna, old.sender); assertEquals(0, old.text.size); assertArrayEquals(r.encode(), Rekey.decode(old.fourth)!!.encode())
        // And the keyring takes both alike: the same epoch, with or without a timer.
        fun taken(timer: Int?): Keyring.Epoch? {
            val ring = base()
            return (ring.take(r, anna, ben, "d", false, null, t0, timer = timer) { ByteArray(32) { 6 } } as? Keyring.Taken.Added)?.epoch
        }
        val a = taken(86_400)!!; val b = taken(null)!!
        assertEquals(a.id, b.id); assertEquals(a.members, b.members); assertEquals(a.admins, b.admins); assertEquals(a.rank, b.rank)
        assertEquals(86_400, a.timer); assertEquals("without the field it keeps the value of the epoch before", 0, b.timer)
    }

    @Test fun theLargestNewKeyStillFitsWithTheField() {
        val members = (0 until 100).map { "%016x".format(it) }; val box = ByteArray(MlKem.CIPHER_SIZE + Hpke.ENC_SIZE + 32 + 19 + 16)
        val crypto = GroupCrypto.fromSecret(ByteArray(32) { 1 }); val id = "0123456789abcdef"
        for (some in Rekey.split(members)) {
            val r = Rekey(7, 1, ByteArray(33), members, "%016x".format(200), some.map { it to box }, removal = true, members = members)
            val p = Packet(id, 1, 16, crypto.seal(id, 1, anna, "n".repeat(200), "", extra = r.encode(), pad = Pad.FULL, fifth = GroupTimer.field(3_600)) { TestKeys.sign(it) })
            assertEquals(16_018, p.encode().size)
            val o = crypto.openSigned(p)!!
            assertNotNull(Rekey.decode(o.extra!!)); assertEquals(3_600, GroupTimer.read(o.fifth))
        }
    }

    // MARK: - Who sets it, and how epochs inherit it

    /** Epoch 5: Anna and Ben are admins, Cara and Dan members. The timer is off. */
    private fun base(timer: Int? = 0) = Keyring().also {
        it.add(Keyring.Epoch(5, t0 - 100 * hour, ByteArray(32) { 5 }, listOf(anna, ben, cara, dan), listOf(anna, ben), by = anna, rank = Keyring.BY_ADMIN,
            timerRaw = timer, timer = timer, timerCounts = timer != null))
    }

    private fun key(epoch: Long, made: Long, members: List<String>? = null, admins: List<String>? = null, removes: String? = null): Rekey {
        val m = members ?: listOf(anna, ben, cara, dan)
        return Rekey(epoch, made, ByteArray(33), admins ?: listOf(anna, ben), removes, m.map { it to ByteArray(1) }, removal = removes != null, members = m)
    }

    /** What [Keyring.take] makes of a key on Cara's phone, as the keyring holds it; null if it isn't taken. [secret]: what its part opens to. */
    private fun take(ring: Keyring, r: Rekey, by: String, timer: Int?, secret: Int, now: Long? = null): Keyring.Epoch? {
        val plain = ByteArray(32) { secret.toByte() } + (if (r.removal) Invite.newCode().toByteArray() else ByteArray(0))
        val e = (ring.take(r, by, cara, "d", false, null, now ?: r.made, timer = timer) { plain } as? Keyring.Taken.Added)?.epoch ?: return null
        return ring.all().firstOrNull { it.id == e.id } // a key numbered below the current epoch is held without its member list
    }

    @Test fun anAdminSetsItAndLaterKeysInherit() {
        val ring = base()
        assertEquals(0, ring.timer().seconds)
        assertNotNull(take(ring, key(6, t0), anna, 86_400, 6))
        assertEquals(86_400, ring.timer().seconds); assertEquals("the value changed with her key", anna, ring.timer().by)
        assertNotNull(take(ring, key(7, t0 + 2 * hour), cara, null, 7))
        assertEquals("a member's weekly key without the field keeps it", 86_400, ring.timer().seconds); assertEquals(anna, ring.timer().by)
        assertNotNull(take(ring, key(8, t0 + 4 * hour), ben, null, 8))
        assertEquals("so does an admin's key from a build without the field", 86_400, ring.timer().seconds)
        assertNotNull(take(ring, key(9, t0 + 6 * hour), ben, 86_400, 9))
        assertEquals("repeating the value isn't setting it", anna, ring.timer().by)
        assertNotNull(take(ring, key(10, t0 + 8 * hour), ben, 0, 10))
        assertEquals("only an admin's explicit zero turns it off", 0, ring.timer().seconds); assertEquals(ben, ring.timer().by)
    }

    @Test fun aMembersFieldIsIgnored() {
        val ring = base(86_400)
        assertNotNull(take(ring, key(6, t0), cara, 3_600, 6))
        assertEquals(86_400, ring.timer().seconds)
        assertNotNull(take(ring, key(7, t0 + 2 * hour), dan, 0, 7))
        assertEquals("nor can a member turn it off", 86_400, ring.timer().seconds)
    }

    @Test fun aPhoneThatJoinsLearnsItFromTheKeyThatLetsItIn() {
        fun joined(by: String, admins: List<String>, timer: Int?): Pair<Boolean, Int?> {
            val ring = Keyring()
            return (take(ring, key(6, t0, admins = admins), by, timer, 6) != null) to ring.timer().seconds
        }
        assertEquals(true to 28_800, joined(anna, listOf(anna, ben), 28_800))
        assertEquals(true to 0, joined(anna, listOf(anna, ben), 0))
        assertEquals("let in by a build without the field: not known yet", true to null, joined(anna, listOf(anna, ben), null))
        assertEquals("never told a value by someone the key doesn't name as an admin", true to null, joined(dan, listOf(anna, ben), 3_600))
    }

    @Test fun notKnownStaysNotKnownUntilAnAdminsKeyBringsIt() {
        val ring = base(null)
        assertNull(ring.timer().seconds)
        take(ring, key(6, t0), cara, 3_600, 6)
        assertNull(ring.timer().seconds)
        take(ring, key(7, t0 + 2 * hour), anna, null, 7)
        assertNull(ring.timer().seconds)
        take(ring, key(8, t0 + 4 * hour), anna, 604_800, 8)
        assertEquals(604_800, ring.timer().seconds); assertNull("this phone didn't see who set it: her key may only repeat it", ring.timer().by)
    }

    /** Two admins set different values at once: every phone ends with the value of the key that wins the number, whichever came first. */
    @Test fun twoAdminsAtOnceTheWinnersValueEverywhere() {
        fun ring(order: List<Triple<String, Int, Int>>) = base().also { r -> for ((maker, timer, secret) in order) assertNotNull(take(r, key(6, t0), maker, timer, secret)) }
        val a = Triple(anna, 3_600, 61); val b = Triple(ben, 86_400, 62)
        val one = ring(listOf(a, b)); val two = ring(listOf(b, a))
        assertEquals(one.current().id, two.current().id)
        assertEquals(one.timer().seconds, two.timer().seconds)
        assertEquals("no merging: the winner's", if (one.current().by == anna) 3_600 else 86_400, one.timer().seconds)
    }

    /**
     * A phone gets the rival that loses, then a member's next key, and the winner only later. The member's key inherits; it
     * must end with the winner's value like every other phone, not keep the loser's for good.
     */
    @Test fun aLosingRivalsValueDoesNotStickThroughAMembersNextKey() {
        // Which of the two secrets wins number 6 is a matter of hashes: find out, then give the loser the short timer.
        val probe = base()
        take(probe, key(6, t0), anna, null, 61); take(probe, key(6, t0), ben, null, 62)
        val annaWins = probe.current().by == anna
        val winner = Triple(if (annaWins) anna else ben, 86_400, if (annaWins) 61 else 62); val loser = Triple(if (annaWins) ben else anna, 3_600, if (annaWins) 62 else 61)

        val late = base()
        assertNotNull(take(late, key(6, t0), loser.first, loser.second, loser.third))
        assertEquals(3_600, late.timer().seconds)
        assertNotNull(take(late, key(7, t0 + 2 * hour), cara, null, 7))
        assertEquals("all this phone knows so far", 3_600, late.timer().seconds)
        val read = take(late, key(6, t0), winner.first, winner.second, winner.third, now = t0 + 3 * hour)!!
        assertTrue("numbered below the current epoch: kept only to read with", read.members.isEmpty())
        assertEquals(7L, late.current().number)
        assertEquals("the admin's late key is still that admin's word on the timer", 86_400, late.timer().seconds)

        val early = base()
        take(early, key(6, t0), winner.first, winner.second, winner.third); take(early, key(6, t0), loser.first, loser.second, loser.third)
        take(early, key(7, t0 + 2 * hour), cara, null, 7)
        assertEquals(86_400, early.timer().seconds)
        assertEquals("the same keys in another order: the same timer", late.timer().seconds, early.timer().seconds)
    }

    /** A key kept only to read with never becomes the group's timer by itself, and a member's can't change anything. */
    @Test fun aKeyKeptOnlyToReadWithDecidesNothing() {
        val ring = base(86_400)
        take(ring, key(6, t0), anna, null, 6); take(ring, key(7, t0 + 2 * hour), cara, null, 7)
        val old = take(ring, key(6, t0 + hour), dan, 300, 66, now = t0 + 3 * hour)!!
        assertTrue(old.members.isEmpty()); assertNull(old.timer)
        assertEquals(7L, ring.current().number); assertEquals(86_400, ring.timer().seconds)
        // An admin's late key without a timer says nothing about it either.
        take(ring, key(6, t0 + hour), ben, null, 67, now = t0 + 3 * hour)
        assertEquals(86_400, ring.timer().seconds)
    }

    /**
     * Review round 2b, M4: Ben, an admin of epoch 5, is demoted by Anna's key 6; members' keys 7 and 8 follow. Ben then
     * seals a key numbered 7 (kept only to read with) with a short timer and, in one of the two runs, a smaller hash than
     * Cara's key 7, so it wins number 7. It sets nothing and freezes nothing, and the chat's lines still go by Cara's key
     * (before, they read as everyone joining at 8): Ben is no admin of the winning epoch before it.
     */
    @Test fun aDemotedAdminsLateKeySetsNothing() {
        var won = 0
        for ((caras, bens) in listOf(71 to 72, 72 to 71)) {
            val ring = base(86_400)
            assertNotNull(take(ring, key(6, t0, admins = listOf(anna)), anna, null, 6))
            assertNotNull(take(ring, key(7, t0 + 2 * hour), cara, null, caras))
            assertNotNull(take(ring, key(8, t0 + 4 * hour), cara, null, 8))
            assertEquals(86_400, ring.timer().seconds)
            val late = take(ring, key(7, t0 + 3 * hour, admins = listOf(anna, ben)), ben, 300, bens, now = t0 + 5 * hour)!!
            assertTrue("kept only to read with", late.members.isEmpty())
            if (ring.won(late)) won++
            assertEquals("Ben's late key sets nothing", 86_400, ring.timer().seconds)
            assertNull(late.timer); assertFalse(ring.timers().getValue(late.id).counts)
            val seven = ring.lineEpochs().single { it.number == 7L }
            assertEquals("the lines go by Cara's key", cara, seven.by); assertTrue(seven.members.isNotEmpty())
            assertEquals(listOf(5L, 6L, 7L, 8L), ring.lineEpochs().map { it.number })
        }
        assertEquals("in one run Ben's key wins number 7", 1, won)
    }

    /** A late key kept only to read with still stands in for its number when its maker is an admin of the epoch it beats: the lines go by it. */
    @Test fun anAdminsLateWinnerStandsInForItsNumber() {
        for ((annas, bens) in listOf(61 to 62, 62 to 61)) {
            val ring = base(0)
            take(ring, key(6, t0), anna, 3_600, annas); take(ring, key(7, t0 + 2 * hour), cara, null, 7)
            val late = take(ring, key(6, t0), ben, 86_400, bens, now = t0 + 3 * hour)!!
            val wins = ring.won(late)
            assertEquals(if (wins) ben else anna, ring.lineEpochs().single { it.number == 6L }.by)
            assertEquals(if (wins) 86_400 else 3_600, ring.timer().seconds)
        }
    }

    /**
     * A late admin key without the field that wins its number says nothing about the timer (spec "Disappearing messages":
     * only one that carries it counts), but it makes the chat's lines. Review round 2b, Low 13 stays open on both apps
     * (open-problems.md): a phone that got Ben's key in time, where it won, inherits from it instead.
     */
    @Test fun aLateWinnerWithoutTheFieldSaysNothingAboutTheTimer() {
        var benWon = 0
        for ((annas, bens) in listOf(61 to 62, 62 to 61)) {
            val late = base(0)
            take(late, key(6, t0), anna, 3_600, annas); take(late, key(7, t0 + 2 * hour), cara, null, 7)
            val bens6 = take(late, key(6, t0), ben, null, bens, now = t0 + 3 * hour)!!
            assertTrue(bens6.members.isEmpty())
            assertEquals("Anna's value stays", 3_600, late.timer().seconds)
            val wins = late.won(bens6)
            if (wins) benWon++
            assertEquals(if (wins) ben else anna, late.lineEpochs().single { it.number == 6L }.by)
        }
        assertEquals(1, benWon)
    }

    /** Review round 2b, Low 21: in a group whose timer is a day or less, a help report's alert doesn't carry the name it reports. */
    @Test fun aHelpReportsNameStaysOutOfAlertsInABriefGroup() {
        for (s in listOf(300, 3_600, 28_800, 86_400)) assertEquals("$s", "Someone", GroupTimer.reported("Marco", s))
        for (s in listOf(null, 0, 86_401, 604_800)) assertEquals("$s", "Marco", GroupTimer.reported("Marco", s))
    }

    /** The same key read again is ignored (its part was opened once), so a replay can't bring a value back. */
    @Test fun theSameKeyAgainChangesNothing() {
        val ring = base()
        val first = key(6, t0)
        assertNotNull(take(ring, first, anna, 3_600, 6))
        assertNotNull(take(ring, key(7, t0 + 2 * hour), anna, 86_400, 7))
        assertNull("replayed", take(ring, first, anna, 3_600, 6, now = t0 + 3 * hour))
        assertNull("replayed with another field: the packet wouldn't even check, and the part is known", take(ring, first, anna, 300, 6, now = t0 + 3 * hour))
        assertEquals(86_400, ring.timer().seconds)
    }

    /** Someone a removal took out sets nothing afterwards: their keys aren't taken at all. */
    @Test fun aRemovedAdminSetsNothing() {
        val ring = base(86_400)
        assertNotNull(take(ring, key(6, t0, members = listOf(anna, cara, dan), admins = listOf(anna), removes = ben), anna, null, 6))
        assertEquals("a removal without the field keeps it", 86_400, ring.timer().seconds)
        assertNull(take(ring, key(7, t0 + 2 * hour, members = listOf(anna, cara, dan), admins = listOf(anna)), ben, 300, 7))
        assertNull(take(ring, key(6, t0, admins = listOf(anna, ben)), ben, 300, 66))
        assertEquals(86_400, ring.timer().seconds)
    }

    @Test fun aRemovalCanCarryIt() {
        val ring = base(86_400)
        assertNotNull(take(ring, key(6, t0, members = listOf(anna, cara, dan), admins = listOf(anna), removes = ben), anna, 3_600, 6))
        assertEquals(3_600, ring.timer().seconds)
    }

    /** The timers are saved with the epochs: read back, the keyring says the same. */
    @Test fun whatWasWorkedOutIsKeptWhenTheEpochsBeforeAreGone() {
        val ring = base()
        take(ring, key(6, t0), anna, 28_800, 6); take(ring, key(7, t0 + 2 * hour), cara, null, 7)
        val cur = ring.current(); val saved = ring.timers().getValue(cur.id)
        assertEquals(28_800, saved.seconds); assertEquals(anna, saved.by)
        val back = Keyring() // only the current epoch is left a week later
        back.add(Keyring.Epoch(cur.number, cur.made, cur.secret, cur.members, cur.admins, by = cur.by, rank = cur.rank, taken = cur.taken,
            timerRaw = cur.timerRaw, timer = saved.seconds, timerBy = saved.by, timerCounts = saved.counts))
        assertEquals(28_800, back.timer().seconds); assertEquals(anna, back.timer().by)
    }

    @Test fun thisPhonesOwnChoiceIsKnownToBeItsOwn() {
        val ring = base(null)
        val r = key(6, t0)
        assertTrue(ring.setsTimer(r, anna)); assertFalse(ring.setsTimer(r, cara))
        ring.add(Keyring.Epoch.fresh(6, t0, ByteArray(32) { 6 }, r.members, r.admins, by = anna, rank = Keyring.BY_ADMIN, timerRaw = 3_600, timerSetHere = true, timerCounts = true))
        assertEquals(3_600, ring.timer().seconds); assertEquals(anna, ring.timer().by)
    }

    @Test fun aGroupMadeHereStartsWithTheTimerOffAndKnowsIt() {
        val ring = Keyring()
        ring.add(Keyring.Epoch.fresh(1, t0, ByteArray(32) { 1 }, listOf(anna), listOf(anna), timerRaw = 0, timerCounts = true))
        assertEquals(0, ring.timer().seconds)
        // Its next key carries what its maker knows, and a phone let in by it learns the value.
        val joining = Keyring()
        assertNotNull(take(joining, key(2, t0, admins = listOf(anna)), anna, ring.timer().seconds, 2))
        assertEquals(0, joining.timer().seconds)
    }

    // MARK: - When it deletes

    @Test fun aValueDeletesOnlyOnceItHasSettled() {
        val w = Disappear.Watch()
        assertTrue(w.observe(3_600, t0))
        assertNull(w.deletes(t0)); assertNull("9:59", w.deletes(t0 + 9 * 60_000 + 59_999))
        assertEquals("10:00", 3_600_000L, w.deletes(t0 + 10 * 60_000))
        assertFalse("a later key that inherits it doesn't start the wait again", w.observe(3_600, t0 + hour))
        assertEquals(3_600_000L, w.deletes(t0 + hour))
        assertTrue(w.observe(0, t0 + 2 * hour)); assertNull("off", w.deletes(t0 + 9 * hour))
        assertTrue(w.observe(null, t0 + 9 * hour)); assertNull("not known: the normal rule", w.deletes(t0 + 99 * hour))
    }

    /** Two admins change it at once, and the value that loses is current for a moment: it never deletes anything. */
    @Test fun aRivalThatIsCurrentForAMomentDeletesNothing() {
        val w = Disappear.Watch(604_800, t0 - 100 * hour)
        assertEquals(604_800_000L, w.deletes(t0))
        w.observe(300, t0)                 // the losing rival arrives first
        assertNull(w.deletes(t0 + 5_000))
        w.observe(86_400, t0 + 5_000)      // the winner, 5 s later
        for (at in t0..(t0 + 10 * 60_000 + 4_999) step 1_000) assertNotEquals(300_000L, w.deletes(at))
        assertNull(w.deletes(t0 + 10 * 60_000 + 4_999))
        assertEquals(86_400_000L, w.deletes(t0 + 10 * 60_000 + 5_000))
        // Back and forth: each change starts the wait again.
        w.observe(300, t0 + hour); w.observe(86_400, t0 + hour + 9 * 60_000); w.observe(300, t0 + hour + 18 * 60_000)
        assertNull(w.deletes(t0 + hour + 27 * 60_000))
    }

    @Test fun theWatchIsSaved() {
        for (w in listOf(Disappear.Watch(3_600, t0), Disappear.Watch(0, 5), Disappear.Watch(null, t0))) assertEquals(w, Disappear.Watch.parse(w.saved))
        for (bad in listOf("", "3600", "x:5", "3600:", ":5", "3600:5:1")) assertNull(bad, Disappear.Watch.parse(bad))
        val back = Disappear.Watch(3_600, t0 + 99 * hour) // the clock was set back since
        back.observe(3_600, t0)
        assertEquals("it waits ten minutes again, not days", 3_600_000L, back.deletes(t0 + 10 * 60_000))
    }

    /** Counted from when a message reached this phone (or, written here, first left it), never from when it was written. */
    @Test fun whatIsDue() {
        val held = linkedMapOf("a" to t0, "b" to t0 + 1, "unsent" to KeepLess.UNSENT)
        assertEquals(emptyList<String>(), Disappear.due(held, hour, t0 + hour - 1))
        assertEquals("exactly an hour: it goes", listOf("a"), Disappear.due(held, hour, t0 + hour))
        assertEquals("one that hasn't left this phone never goes", setOf("a", "b"), Disappear.due(held, hour, t0 + 500 * hour).toSet())
        assertEquals(emptyList<String>(), Disappear.due(held, 0, t0 + 500 * hour))
        assertEquals(emptyList<String>(), Disappear.due(held, hour, Long.MIN_VALUE + 5))
        assertEquals(t0 + hour, Disappear.next(held.values, hour)); assertNull(Disappear.next(listOf(KeepLess.UNSENT), hour)); assertNull(Disappear.next(held.values, 0))
        // High-risk mode keeps its own day: whichever is earlier removes.
        assertEquals(listOf("a"), held.filter { KeepLess.due(it.value, t0 + 24 * hour + 1) }.keys.toList())
        assertEquals(emptyList<String>(), Disappear.due(held, 168 * hour, t0 + 24 * hour + 1))
    }

    @Test fun whatTheTimerRemovesIsStillCarriedUnlessThisPhoneCanReadIt() {
        assertTrue("its own key is gone: one more packet this phone only carries", Disappear.carries(stillOpens = false))
        assertFalse("a receipt, or a message the epoch's key still opens: out of the store", Disappear.carries(stillOpens = true))
    }

    /** An alert ends when its message is due (spec "Disappearing messages": Android ends an alert when its message is due). */
    @Test fun anAlertEndsWhenItsMessageIsDue() {
        val now = t0
        assertEquals(AlertLife.WEEK_MS, AlertLife.timeout(now, now))
        assertEquals(AlertLife.WEEK_MS, AlertLife.timeout(now, now, 0))
        assertEquals(3_600_000L, AlertLife.timeout(now, now, 3_600_000))
        assertEquals("a minute at least", 60_000L, AlertLife.timeout(now, now, 300))
        assertEquals("the week's rule, when it comes first", 2 * hour, AlertLife.timeout(now - AlertLife.WEEK_MS + 2 * hour, now, 604_800_000))
    }

    /** What waited for its sender's key comes back from [KeyWait.forget], so that its message key can go with it. */
    @Test fun whatWaitedIsReturnedWhenItsRowGoes() {
        val w = KeyWait<String, Int>()
        w.message("k1", "g", anna, "row1", true, 1); w.message("k2", "g", anna, "row2", true, 2); w.receipt("k3", "g", anna, 3)
        assertEquals(listOf(1), w.forget(listOf("row1", "nothing")).map { it.item })
        assertEquals(2, w.count("g"))
    }

    // MARK: - The notice message

    @Test fun theNoticeIsTextToAppsWithoutTheTimerAndNothingToAppsWithIt() {
        val code = "test-group-1"; val group = GroupCrypto(code); val id = "00000000000000a7"; val text = GroupTimer.notice(86_400)
        val c = group.seal(id, 9, anna, "Anna", text, iv = ByteArray(12) { 9 }, fifth = GroupTimer.field(86_400)) { ByteArray(64) { 7 } }
        val o = group.openSigned(Packet(id, 9, 16, c))!!
        assertEquals(Notice.Timer(86_400), o.message.notice); assertTrue("never a row, an alert or a receipt", o.message.silent); assertTrue(o.message.offRadio)
        assertNull(o.message.about); assertNull("a build with replies and reactions only: an ordinary message", About.read(o.fifth, text.toByteArray()))
        assertEquals(text, o.message.text)
        val old = oldRead(open(GroupCrypto.deriveKey(code), c, id, 9))!!
        assertArrayEquals(text.toByteArray(), old.text); assertEquals("the 4th field is there and empty", 0, old.fourth.size)
        assertEquals(115, text.toByteArray().size)
        // On the Public channel the field means nothing: the text is shown like any other.
        assertNull(o.message.onPublic().notice); assertFalse(o.message.onPublic().silent)
        // No copy is kept of it: nothing of it is ever shown.
        assertNull(History.seal(ByteArray(32) { 3 }, id, "g", "e", o, 0, 0))
        // An ordinary message has no notice.
        val plain = group.seal(id, 9, anna, "Anna", "hello", iv = ByteArray(12) { 9 }) { ByteArray(64) { 7 } }
        assertNull(group.openSigned(Packet(id, 9, 16, plain))!!.message.notice)
        // A reply's field wins over a raw one: the 5th field is one field.
        val target = About.Target.parse("0123456789abcdef:0123456789abcdef")!!
        val both = group.seal(id, 9, anna, "Anna", "hi", iv = ByteArray(12) { 9 }, about = About.Reply(target), fifth = GroupTimer.field(60)) { ByteArray(64) { 7 } }
        group.openSigned(Packet(id, 9, 16, both))!!.message.let { assertEquals(About.Reply(target), it.about); assertNull(it.notice) }
    }
}
