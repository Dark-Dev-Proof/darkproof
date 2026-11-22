package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Telling the group when a phone leaves or is erased (spec/FORMAT.md, "Leaving the group"): the note's field, which
 * notes count (L1), what admins' phones do (L4), the leaving records (L2, L3), the wording, and that a reader without the
 * feature sees an ordinary message and nothing else. The iPhone's LeaveTests checks the same.
 */
class LeaveTest {
    private val code = "test-group-leave"
    private val group = GroupCrypto(code)
    private val iv = ByteArray(12) { 9 }
    private val sig = ByteArray(64) { 7 }
    private val anna = "1111111111111111"; private val ben = "2222222222222222"; private val cara = "3333333333333333"
    private val t0 = 1_794_000_000_000L; private val hour = 3_600_000L

    companion object {
        /** The fields as a reader from before the 5th field takes them: sender, name, text, then a 4th field if the bytes are there. It stops there. */
        fun olderFields(plain: ByteArray): List<ByteArray?> {
            val b = plain.copyOfRange(64, plain.size); var i = 0
            fun next(): ByteArray? {
                if (i + 2 > b.size) return null
                val n = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                if (i + 2 + n > b.size) return null
                return b.copyOfRange(i + 2, i + 2 + n).also { i += 2 + n }
            }
            return listOf(next(), next(), next(), next()?.takeIf { it.isNotEmpty() })
        }

        /** What a reader from before the 5th field shows: the text. */
        fun olderReads(plain: ByteArray): String = String(olderFields(plain)[2]!!, Charsets.UTF_8)

        /**
         * The build with replies, reactions and the timer, before the leave note (frozen copy): a 5th field it reads only as
         * a reply or reaction (17 bytes, types 1 and 2) or as the timer's notice (5 bytes, type 3). Anything else: an ordinary message.
         */
        fun timerBuildReads(field: ByteArray?): String = when {
            field != null && field.size == 17 && (field[0] == 1.toByte() || field[0] == 2.toByte()) -> "about"
            field != null && field.size == 5 && field[0] == 3.toByte() -> "timer notice"
            else -> "ordinary"
        }
    }

    private fun plainOf(cipher: ByteArray, id: String, ts: Long, key: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, cipher, 0, 12)); updateAAD("$id|$ts".toByteArray())
        doFinal(cipher, 12, cipher.size - 12)
    }

    // The field

    @Test fun theField() {
        assertArrayEquals(byteArrayOf(0x05, 0x01), LeaveNote.field)
        assertEquals(2, LeaveNote.SIZE)
        assertTrue(LeaveNote.read(byteArrayOf(0x05, 0x01)))
        assertEquals(Notice.Leave, Notice.read(byteArrayOf(0x05, 0x01)))
        for (other in listOf(byteArrayOf(0x05, 0x02), byteArrayOf(0x05), byteArrayOf(0x05, 0x01, 0x00), byteArrayOf(0x05, 0x00), byteArrayOf(0x06, 0x01), ByteArray(0))) {
            assertFalse(Hex.encode(other), LeaveNote.read(other)); assertNull(Hex.encode(other), Notice.read(other))
        }
        assertFalse(LeaveNote.read(null))
        assertEquals(21, LeaveNote.TEXT.toByteArray().size)
    }

    @Test fun aNoteIsALineThatStillGoesOnTheRadio() {
        val c = group.seal("00000000000000c1", 9, cara, "Cara", LeaveNote.TEXT, iv = iv, fifth = LeaveNote.field) { sig }
        val o = group.openSigned(Packet("00000000000000c1", 9, 16, c))!!
        assertEquals(Notice.Leave, o.message.notice); assertEquals(LeaveNote.TEXT, o.message.text); assertNull(o.message.about)
        assertTrue("never a bubble, an alert or a receipt", o.message.silent)
        assertFalse("small, and maybe the only way to an admin", o.message.offRadio)
        assertArrayEquals(LeaveNote.field, o.fifth)
        // The timer's notice stays off the radio, as before.
        assertTrue(o.message.copy(notice = Notice.Timer(3600)).offRadio)
        // A note is never copied for history: nothing of it is shown, and the record keeps what counts (L6).
        assertNull(History.seal(ByteArray(32) { 1 }, "00000000000000c1", "00112233445566778899aabbccddeeff", "e", o, 0, 0))
        // On the Public channel the field is dropped: no note there.
        assertNull(o.message.onPublic().notice)
    }

    @Test fun anotherFormIsAnOrdinaryMessage() {
        val c = group.seal("00000000000000c2", 9, cara, "Cara", "👋 hi", iv = iv, fifth = byteArrayOf(0x05, 0x02)) { sig }
        val o = group.openSigned(Packet("00000000000000c2", 9, 16, c))!!
        assertNull(o.message.notice); assertNull(o.message.about); assertFalse(o.message.silent); assertEquals("👋 hi", o.message.text)
    }

    /**
     * A check-in clock message (type `0x04`): this app keeps no clock yet, but knows one, so that its free text is never read
     * as a check-in (review round 2b, Low 20). The clock's own text is shown as it is, position and all; any other text is
     * shown as what the field says. A deadline rule Q2 refuses leaves the field ignored: an ordinary message.
     */
    @Test fun aClockMessagesTextIsNeverACheckIn() {
        val ts = t0 + 7; val hourLater = ts + hour
        fun field(deadline: Long) = byteArrayOf(QuietClock.TYPE) + ByteArray(8) { i -> (deadline ushr (56 - 8 * i)).toByte() }
        fun open(text: String, fifth: ByteArray): Message {
            val c = group.seal("00000000000000c6", ts, cara, "Cara", text, iv = iv, fifth = fifth) { sig }
            return group.openSigned(Packet("00000000000000c6", ts, 16, c))!!.message
        }
        assertEquals(Notice.Clock(hourLater), Notice.read(field(hourLater), ts))
        assertNull("without the message's time a clock reads as none", Notice.read(field(hourLater)))
        for (said in listOf("🆘 I need help: injured", "✅ I'm safe", "👤 Marco needs help", AreYouSafe.EVERYONE, "hello")) {
            val m = open(said, field(hourLater))
            assertEquals(said, "⏳ Check on me if I don't check in within 1 hour", m.text)
            assertEquals(Notice.Clock(hourLater), m.notice)
            assertNull(CheckIn.of(m.text)); assertNull(Vouch.of(m.text)); assertNull(AreYouSafe.of(m.text))
            assertFalse("an ordinary message: shown, alerted, confirmed", m.silent); assertFalse("on the radio", m.offRadio)
        }
        val own = "⏳ Check on me if I don't check in within 1 hour · 📍 45.46420,9.19000 ±12m"
        assertEquals("a clock's own text, with its position", own, open(own, field(hourLater)).text)
        assertEquals(QuietClock.STOP, open("🆘 I need help", field(0)).text)
        // Deadline too soon, or another length: the field is ignored, the message is its text.
        for (f in listOf(field(ts + 60_000), field(hourLater).copyOf(8))) open("🆘 I need help", f).let { m -> assertNull(m.notice); assertEquals(CheckIn.HELP, CheckIn.of(m.text)) }
        // Its readable copy keeps the text it is shown with; a leave note and the timer's notice get none.
        val c = group.seal("00000000000000c6", ts, cara, "Cara", "🆘 I need help", iv = iv, fifth = field(hourLater)) { sig }
        val o = group.openSigned(Packet("00000000000000c6", ts, 16, c))!!
        assertEquals("on the Public channel the field is dropped: the text as written", "🆘 I need help", o.message.onPublic(o.said).text)
        assertNull(o.message.onPublic(o.said).notice)
        val key = ByteArray(32) { 1 }; val g = "00112233445566778899aabbccddeeff"
        val copy = History.seal(key, "00000000000000c6", g, "e", o, 0, 0)!!
        assertEquals("⏳ Check on me if I don't check in within 1 hour", History.open(key, "00000000000000c6", copy)!!.opened.message.text)
        val timer = group.openSigned(Packet("00000000000000c7", ts, 16, group.seal("00000000000000c7", ts, cara, "Cara", GroupTimer.notice(3_600), iv = iv, fifth = GroupTimer.field(3_600)) { sig }))!!
        assertNull("the timer's notice keeps no copy", History.seal(key, "00000000000000c7", g, "e", timer, 0, 0))
    }

    /** Hard requirement: a build without the feature sees `👋 I left the group` as an ordinary message, and nothing else. */
    @Test fun readersWithoutTheFeatureSeeAnOrdinaryMessage() {
        val id = "00000000000000c3"
        val with = group.seal(id, 9, cara, "Cara", LeaveNote.TEXT, iv = iv, fifth = LeaveNote.field) { sig }
        val without = group.seal(id, 9, cara, "Cara", LeaveNote.TEXT, iv = iv) { sig }
        val key = GroupCrypto::class.java.getDeclaredField("keyBytes").apply { isAccessible = true }.get(group) as ByteArray
        // Before the 5th field: the same three fields, no 4th.
        val a = olderFields(plainOf(with, id, 9, key)); val b = olderFields(plainOf(without, id, 9, key))
        for (i in 0..2) assertArrayEquals(a[i], b[i])
        assertNull(a[3]); assertNull(b[3])
        assertArrayEquals(LeaveNote.TEXT.toByteArray(), a[2])
        // The build before this one: neither a reply, a reaction nor the timer's notice.
        val o = group.openSigned(Packet(id, 9, 16, with))!!
        assertEquals("ordinary", timerBuildReads(o.fifth))
        assertNull(About.read(o.fifth, LeaveNote.TEXT.toByteArray())); assertNull(GroupTimer.read(o.fifth))
        // Not a check-in either.
        assertNull(CheckIn.of(LeaveNote.TEXT))
    }

    @Test fun theFieldIsSigned() {
        fun digest(fifth: ByteArray?): String {
            val c = group.seal("00000000000000c4", 9, cara, "Cara", LeaveNote.TEXT, iv = iv, pad = Pad.NONE, fifth = fifth) { sig }
            return Hex.encode(group.openSigned(Packet("00000000000000c4", 9, 16, c))!!.digest)
        }
        assertNotEquals("nobody can add the field to an ordinary message", digest(null), digest(LeaveNote.field))
    }

    // Which notes count (L1)

    private fun epoch(n: Long, members: List<String>, readOnly: Boolean = false, removes: String? = null): Keyring.Epoch =
        Keyring.Epoch.fresh(n, t0 + n * hour, ByteArray(32) { n.toByte() }, members, listOf(anna), if (removes == null) null else "new-code-$n",
            if (removes == null) null else "old", removes, anna, if (removes == null) Keyring.BY_ADMIN else 0, t0 + n * hour).let { if (readOnly) it.forReadingOnly() else it }

    /** A note by [sender], sealed with its message key in epoch [n] (members as listed), opened by [e]. */
    private fun note(n: Long, members: List<String>, sender: String, e: Keyring.Epoch): GroupCrypto.Opened {
        val s = ByteArray(32) { n.toByte() }; val g = GroupCrypto.fromSecret(MessageKeys.outer(s)); val position = members.indexOf(sender)
        val mk = MessageKeys.of(s, members).key(position, 3)!!
        val ts = t0 + n * hour + 60_000
        val c = g.sealWith(mk, position, 3, "e6080000000000c5", ts, sender, "Cara", LeaveNote.TEXT, iv = iv, fifth = LeaveNote.field) { sig }
        val o = e.open(Packet("e6080000000000c5", g.hideTime(ts, c), 16, c))
        assertNotNull("the epoch opens it", o)
        return o!!
    }

    private fun ring(vararg epochs: Keyring.Epoch) = Keyring().also { r -> epochs.forEach { r.add(it) } }

    @Test fun aNoteFromAMemberStillListedCounts() {
        val e5 = epoch(5, listOf(anna, ben, cara)); val r = ring(e5)
        val o = note(5, listOf(anna, ben, cara), cara, e5)
        assertEquals(5L, o.opener?.number); assertEquals(Notice.Leave, o.message.notice)
        assertEquals(LeaveNote.Verdict.VALID, LeaveNote.verdict(o.message.sender, anna, true, o.opener, r))
        assertEquals("a web- in front is the same member", LeaveNote.Verdict.VALID, LeaveNote.verdict("web-$cara", anna, true, o.opener, r))
        // Through the epochs since: still listed in each.
        assertEquals(LeaveNote.Verdict.VALID, LeaveNote.verdict(cara, anna, true, o.opener, ring(e5, epoch(6, listOf(anna, ben, cara)))))
    }

    /** Hard requirement: a note played back after the same key was let in again does nothing. */
    @Test fun aReplayedNoteAfterTheSameKeyWasLetInAgainDoesNothing() {
        val e5 = epoch(5, listOf(anna, ben, cara)); val e6 = epoch(6, listOf(anna, ben), removes = cara); val e7 = epoch(7, listOf(anna, ben, cara))
        val o = note(5, listOf(anna, ben, cara), cara, e5)
        val r = ring(e5, e6, e7)
        assertEquals(7L, r.current().number); assertTrue(cara in r.current().members)
        assertEquals(LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, o.opener, r))
        // The same holds without the removal: a key that left them out and one that let them in again.
        assertEquals(LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, o.opener, ring(e5, epoch(6, listOf(anna, ben)), e7)))
    }

    @Test fun aRemovedMembersNoteDoesNothing() {
        val e5 = epoch(5, listOf(anna, ben, cara)); val o = note(5, listOf(anna, ben, cara), cara, e5)
        assertEquals(LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, o.opener, ring(e5, epoch(6, listOf(anna, ben), removes = cara))))
    }

    @Test fun anEpochKeptOnlyToReadWithDecidesNothing() {
        val e5 = epoch(5, listOf(anna, ben, cara), readOnly = true)
        val o = note(5, listOf(anna, ben, cara), cara, e5)
        assertEquals("it opens: its message keys know everyone", 5L, o.opener?.number)
        assertEquals(LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, o.opener, ring(e5, epoch(6, listOf(anna, ben, cara)))))
    }

    /**
     * Only the epoch that wins each number counts (review round 2b, H2). The admin's key that lets Cara in wins number 6;
     * a member's weekly key of the same number, which doesn't list her, lost: her note counts all the same, for the whole
     * week that losing key is kept. The other way round, a losing rival that lists her proves nothing.
     */
    @Test fun aLosingRivalThatLeavesTheSenderOutDoesNotCount() {
        val with = listOf(anna, ben, cara); val without = listOf(anna, ben)
        fun key6(secret: Int, members: List<String>, by: String, rank: Int) =
            Keyring.Epoch.fresh(6, t0 + 6 * hour, ByteArray(32) { secret.toByte() }, members, listOf(anna), by = by, rank = rank, taken = t0 + 6 * hour)
        val e5 = epoch(5, without)
        val admins = key6(6, with, anna, Keyring.BY_ADMIN); val weekly = key6(66, without, ben, Int.MAX_VALUE)
        for (order in listOf(listOf(e5, admins, weekly), listOf(e5, weekly, admins))) {
            val r = ring(*order.toTypedArray())
            assertSame("the admin's key wins number 6", admins, r.current())
            val o = note(6, with, cara, admins)
            assertEquals(LeaveNote.Verdict.VALID, LeaveNote.verdict(cara, anna, true, o.opener, r))
            assertEquals("and through a later epoch that lists her", LeaveNote.Verdict.VALID, LeaveNote.verdict(cara, anna, true, o.opener, ring(*(order + epoch(7, with)).toTypedArray())))
        }
        // The winner leaves her out; the rival that lists her (and opens her note) lost.
        val loser = key6(6, with, ben, Int.MAX_VALUE); val winner = key6(67, without, anna, Keyring.BY_ADMIN)
        val r = ring(e5, loser, winner)
        assertSame(winner, r.current())
        assertEquals(LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, note(6, with, cara, loser).opener, r))
    }

    @Test fun aNumberWithNoEpochHereFails() {
        val e5 = epoch(5, listOf(anna, ben, cara)); val o = note(5, listOf(anna, ben, cara), cara, e5)
        assertEquals("epoch 6 isn't here: nothing shows it listed them", LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, o.opener, ring(e5, epoch(7, listOf(anna, ben, cara)))))
    }

    @Test fun uncheckedForgedOwnAndForeignNotes() {
        val e5 = epoch(5, listOf(anna, ben, cara)); val r = ring(e5)
        val o = note(5, listOf(anna, ben, cara), cara, e5)
        assertEquals("decided when it checks", LeaveNote.Verdict.NOT_YET, LeaveNote.verdict(cara, anna, null, o.opener, r))
        assertEquals(LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, false, o.opener, r))
        assertEquals("this phone's own note", LeaveNote.Verdict.NO, LeaveNote.verdict(cara, cara, true, o.opener, r))
        assertEquals("not opened by an epoch", LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, null, r))
        assertEquals("an epoch this ring doesn't hold", LeaveNote.Verdict.NO, LeaveNote.verdict(cara, anna, true, epoch(5, listOf(anna, ben, cara)), r))
        assertEquals("Ben isn't listed now", LeaveNote.Verdict.NO, LeaveNote.verdict(ben, anna, true, o.opener, ring(e5, epoch(6, listOf(anna, cara)))))
    }

    // Admins' phones (L4, L5)

    @Test fun whatAnAdminsPhoneDoes() {
        assertEquals(LeaveNote.Act.REMOVE_NOW, LeaveNote.act(admin = true, inCurrent = true, removalHeld = false, keysComplete = true))
        assertEquals(LeaveNote.Act.RETRY_HOURLY, LeaveNote.act(admin = true, inCurrent = true, removalHeld = false, keysComplete = false))
        assertEquals("a member's phone only shows the line", LeaveNote.Act.NOTHING, LeaveNote.act(admin = false, inCurrent = true, removalHeld = false, keysComplete = true))
        assertEquals("out already", LeaveNote.Act.NOTHING, LeaveNote.act(admin = true, inCurrent = false, removalHeld = false, keysComplete = true))
        assertEquals("another admin's removal is here", LeaveNote.Act.NOTHING, LeaveNote.act(admin = true, inCurrent = true, removalHeld = true, keysComplete = true))
    }

    // The records (L2, L3)

    @Test fun onlyTheFirstNoteCountsAndLaterMessagesAreHidden() {
        val b = LeaveBook()
        assertTrue(b.take(cara, t0, 5, t0 + 10))
        assertFalse("later notes change nothing", b.take(cara, t0 + 99, 5, t0 + 20))
        assertEquals(t0, b.record(cara)?.ts)
        assertFalse(b.hides(cara, t0 - 1)); assertFalse(b.hides(cara, t0)); assertTrue(b.hides(cara, t0 + 1))
        assertTrue(b.hides("web-$cara", t0 + 1))
        assertFalse(b.hides(ben, t0 + 1))
    }

    @Test fun aRecordGoesWhenTheMemberIsOutAndTheRemovalIsKeptForTheLine() {
        val b = LeaveBook()
        b.take(cara, t0, 5, t0)
        assertFalse(b.sweep({ true }, { null }, setOf("e5"), t0 + hour))
        assertTrue(b.sweep({ it != cara }, { if (it == cara) "e6" else null }, setOf("e5", "e6"), t0 + hour))
        assertNull(b.record(cara)); assertEquals(cara, b.removals["e6"]?.member)
        assertTrue("what they wrote after the note stays hidden while the removal is held", b.hides(cara, t0 + 1))
        // A new note after they were let in again with the same key starts a new record.
        assertTrue(b.take(cara, t0 + 5 * hour, 8, t0 + 5 * hour))
        // The removal goes with its epoch.
        assertTrue(b.sweep({ true }, { null }, setOf("e8"), t0 + 6 * hour))
        assertTrue(b.removals.isEmpty()); assertNotNull(b.record(cara))
        // Out without a removal (left out by a key): nothing kept.
        val c = LeaveBook(); c.take(ben, t0, 5, t0)
        c.sweep({ false }, { null }, emptySet(), t0)
        assertTrue(c.isEmpty)
    }

    @Test fun aRecordLastsSevenDays() {
        val b = LeaveBook(); b.take(cara, t0, 5, t0)
        assertFalse(b.sweep({ true }, { null }, emptySet(), t0 + LeaveNote.KEEP_MS - 1))
        assertTrue(b.sweep({ true }, { null }, emptySet(), t0 + LeaveNote.KEEP_MS))
        assertTrue(b.isEmpty)
    }

    @Test fun savedAndReadBack() {
        val b = LeaveBook()
        b.take(cara, t0, 5, t0 + 1)
        b.take(ben, t0 + 2, 6, t0 + 3)
        b.sweep({ it != ben }, { "abc" }, setOf("abc"), t0 + 4)
        assertEquals(b, LeaveBook.parse(b.saved))
        assertEquals("x abc $ben ${t0 + 2} 6 ${t0 + 3}", b.saved.last())
        assertEquals(3L, LeaveBook.parse(listOf("r bad", "x", "r $cara 1 2 3", "nonsense line")).record(cara)?.taken)
    }

    // Words

    /** Hard requirement: never "was told", "removed from the group" and the rest of the "Never say" list. The same strings as the iPhone's. */
    @Test fun theWordingNeverClaimsTheGroupWasToldOrAnyoneRemoved() {
        val g = "My group"
        val all = listOf(LeaveWords.sheetTitle(g), LeaveWords.sheetText(), LeaveWords.leaveButton(g), LeaveWords.cantTell(g), LeaveWords.chooseTitle(g), LeaveWords.CHOOSE_TEXT,
            LeaveWords.LEAVE_WITHOUT_CHOOSING, LeaveWords.WITHOUT_CHOOSING_WARNING, LeaveWords.cantHandOver(), LeaveWords.LEAVE_WITHOUT_TELLING, LeaveWords.waitingTitle(g),
            LeaveWords.WAITING_TEXT, LeaveWords.LEAVE_NOW, LeaveWords.left(g, true), LeaveWords.left(g, false), LeaveWords.left(g, null),
            LeaveWords.TELL_FIRST, LeaveWords.tellFirstSub(), LeaveWords.TELLING_TITLE, LeaveWords.ERASE_NOW, LeaveWords.empty(),
            LeaveWords.line("Marco", true), LeaveWords.line("Marco", false), LeaveNote.TEXT)
        for (d in listOf("phone", "computer")) for (s in all + listOf(LeaveWords.sheetText(d), LeaveWords.cantTell(g, d), LeaveWords.left(g, false, d)) + erasedLines(d)) {
            val l = s.lowercase()
            for (bad in LeaveWords.NEVER) assertFalse(s, bad in l)
            assertFalse(s, "was told" in l); assertFalse(s, "removed " in l)
        }
        // Word for word the iPhone's (ios/BlackoutCore/Sources/BlackoutCore/Notices.swift, LeaveWords).
        assertEquals("Leave My group?", LeaveWords.sheetTitle(g))
        assertEquals("This phone forgets the group, its messages and its keys. A note asks the group's admins to remove you and change the key. To come back you need a new invite.", LeaveWords.sheetText())
        assertEquals("This phone isn't let in, so My group can't be told.", LeaveWords.cantTell(g))
        assertEquals("Who runs My group after you?", LeaveWords.chooseTitle(g))
        assertEquals("This phone can't hand over now: it doesn't have every member's keys.", LeaveWords.cantHandOver())
        assertEquals("Telling My group…", LeaveWords.waitingTitle(g))
        assertEquals("You left My group", LeaveWords.left(g, true)); assertEquals("You left My group", LeaveWords.left(g, null))
        assertEquals("You left My group. The note didn't leave this phone in time. It goes when a phone comes into reach.", LeaveWords.left(g, false))
        assertEquals("Asks their admins' phones to remove this phone and change the key", LeaveWords.tellFirstSub())
        assertEquals("This phone is empty", LeaveWords.empty())
        assertEquals("Marco left. Waiting for an admin's phone to change the key.", LeaveWords.line("Marco", true))
        assertEquals("Marco left the group", LeaveWords.line("Marco", false))
    }

    /** Every line the final erase screen can show, for 0 to 3 groups. */
    private fun erasedLines(device: String) = (0..3).flatMap { groups -> (0..groups).flatMap { sent -> (0..sent).mapNotNull { left -> LeaveWords.erased(groups, sent, left, device) } } }

    /**
     * The final erase screen names no group (review round 2b, M10): it shows right after the erase, on a phone that may be
     * taken at that moment. It counts the notes instead. The same strings as the iPhone's.
     */
    @Test fun theFinalEraseScreenNamesNoGroup() {
        assertEquals("The notes left this phone.", LeaveWords.erased(3, 2, 2))
        assertEquals("1 of 2 notes didn't leave this phone in time.", LeaveWords.erased(2, 2, 1))
        assertEquals("2 of 3 notes didn't leave this phone in time.", LeaveWords.erased(3, 3, 1))
        assertEquals("Your groups were not told.", LeaveWords.erased(2, 0, 0))
        assertEquals("one note", "The note left this phone.", LeaveWords.erased(1, 1, 1))
        assertEquals("The note didn't leave this phone in time.", LeaveWords.erased(1, 1, 0))
        assertEquals("The notes left this computer.", LeaveWords.erased(2, 2, 2, "computer"))
        assertNull("no group: no line", LeaveWords.erased(0, 0, 0))
        val names = listOf("My group", "Legal team")
        for (d in listOf("phone", "computer")) for (l in erasedLines(d)) for (n in names) assertFalse(l, n in l)
    }
}
