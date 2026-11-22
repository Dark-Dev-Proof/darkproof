package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Replies and reactions (spec/FORMAT.md, "Replies and reactions"): the 5th plaintext field, what readers make of it, which
 * reactions count, and that a reader which doesn't know the field reads exactly what it read before. The same cases as the
 * iPhone's AboutTests.
 */
class AboutTest {
    private val code = "test-group-1"
    private val group by lazy { GroupCrypto(code) }
    private val iv = ByteArray(12) { 9 }
    private val sig = ByteArray(64) { 7 }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(400) { (it % 251).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    /** Some earlier message, to reply or react to: its packet id and the start of its signed digest. */
    private val target = About.Target.parse("00000000000000a1:5a5a5a5a5a5a5a5a")!!
    private fun hex(b: ByteArray) = Hex.encode(b)
    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    // MARK: - What a reader without the feature does (a copy of the rules as they were before the 5th field)

    /** sender, name, text, then a 4th field if the bytes are there (empty reads as none), exactly as before the 5th field existed. */
    private class Old(val sender: String, val name: String, val text: ByteArray, val fourth: ByteArray?)
    private fun oldRead(plain: ByteArray): Old? {
        val b = plain.copyOfRange(64, plain.size); var i = 0
        fun next(): ByteArray? {
            if (i + 2 > b.size) return null
            val n = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (i + 2 + n > b.size) return null
            return b.copyOfRange(i + 2, i + 2 + n).also { i += 2 + n }
        }
        val s = next() ?: return null; val n = next() ?: return null; val t = next() ?: return null
        return Old(String(s, Charsets.UTF_8), String(n, Charsets.UTF_8), t, next()?.takeIf { it.isNotEmpty() })
    }

    private fun gcm(mode: Int, data: ByteArray, id: String, ts: Long, nonce: ByteArray = iv): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(GroupCrypto.deriveKey(code), "AES"), GCMParameterSpec(128, nonce)); updateAAD("$id|$ts".toByteArray()); doFinal(data)
    }
    /** The plaintext of a packet sealed with the group's own key. */
    private fun plain(cipher: ByteArray, id: String, ts: Long) = gcm(Cipher.DECRYPT_MODE, cipher.copyOfRange(12, cipher.size), id, ts, cipher.copyOf(12))
    /** A packet from a body made by hand, for fields this app never writes. */
    private fun sealRaw(body: ByteArray, id: String, ts: Long) = Packet(id, ts, 16, iv + gcm(Cipher.ENCRYPT_MODE, sig + body, id, ts))
    private fun field(d: ByteArray) = byteArrayOf((d.size shr 8).toByte(), (d.size and 255).toByte()) + d
    private fun body(text: ByteArray, fourth: ByteArray? = ByteArray(0), fifth: ByteArray?, more: ByteArray = ByteArray(0)): ByteArray {
        var b = field(utf8("1111111111111111")) + field(utf8("Bea")) + field(text)
        if (fourth != null) b += field(fourth)
        if (fifth != null) b += field(fifth)
        return b + more
    }
    private fun body(text: String, fifth: ByteArray?, more: ByteArray = ByteArray(0)) = body(utf8(text), ByteArray(0), fifth, more)

    // MARK: - The field

    @Test fun theFieldIsTypeIdAndTheStartOfTheSignedDigest() {
        assertEquals(17, About.SIZE)
        assertEquals("01" + "00000000000000a1" + "5a5a5a5a5a5a5a5a", hex(About.Reply(target).field!!))
        assertEquals("02" + target.id + target.mark, hex(About.Reaction(target).field!!))
        assertNull("never written", About.Nothing.field)
        assertEquals(target, About.Target.parse(target.key))
        for (bad in listOf("", "00000000000000a1", "00000000000000a1:", "00000000000000A1:0000000000000001", "00000000000000a1:0000000000000001:02", "00000000000000a1 0000000000000001"))
            assertNull(bad, About.Target.parse(bad))
    }

    // MARK: - What a target is named by

    private val m0 = "1111111111111111"; private val m1 = "2222222222222222"
    private val secret = ByteArray(32) { 0x11 }
    private val epoch by lazy { GroupCrypto.fromSecret(MessageKeys.outer(secret)) }
    private fun messageKey(n: Int) = MessageKeys.of(secret, listOf(m0, m1)).key(1, n)!!

    private class Sealed(val packet: Packet, val opened: GroupCrypto.Opened)
    /** Member 1's message number [n], sealed with its own key under [ivByte]: the packet, and what it opens to. */
    private fun sealed(n: Int, id: String, text: String, about: About? = null, ivByte: Int, signature: ByteArray? = null): Sealed {
        val mk = messageKey(n)
        val c = epoch.sealWith(mk, 1, n, id, 77, m1, "Bea", text, about = about, iv = ByteArray(12) { ivByte.toByte() }) { signature ?: sig }
        val p = Packet(id, epoch.hideTime(77, c), 16, c)
        return Sealed(p, epoch.openWith(mk, 1, n, p)!!)
    }

    /** The chat row a phone makes from an opened packet. */
    private fun shown(o: GroupCrypto.Opened) = o.message.copy(group = "g", mark = About.Target.of(o))

    @Test fun aTargetIsNamedByWhatWasSigned() {
        val a = sealed(5, "e608000000000005", "The bridge is open", ivByte = 1)
        assertEquals(a.opened.mark, About.Target.of(a.opened).key)
        assertEquals("e608000000000005", About.Target.of(a.opened).id)
        assertEquals(hex(a.opened.digest.copyOf(8)), About.Target.of(a.opened).mark)
        // A copy with fewer hops left is the same packet and the same message.
        assertEquals(About.Target.of(a.opened), About.Target.of(epoch.openWith(messageKey(5), 1, 5, a.packet.withTtl(3))!!))
        // The other signature that checks for the same message (every ECDSA signature has a twin) doesn't change it.
        val twin = sealed(5, "e608000000000005", "The bridge is open", ivByte = 1, signature = ByteArray(64) { 9 })
        assertEquals(About.Target.of(a.opened), About.Target.of(twin.opened))
    }

    @Test fun aResealedCopyIsStillTheTargetAndAnotherMessageUnderItsIdIsNot() {
        val id = "e608000000000005"
        val first = sealed(5, id, "The bridge is open", ivByte = 1)
        val again = sealed(5, id, "The bridge is open", ivByte = 2)   // the same signed message, sealed again
        val other = sealed(5, id, "The bridge is closed", ivByte = 3) // another message under the same id
        assertNotEquals("two different packets", MeshRouter.key(first.packet), MeshRouter.key(again.packet))
        assertFalse(first.packet.cipher.contentEquals(again.packet.cipher)); assertNotEquals(first.packet.ts, again.packet.ts)
        assertArrayEquals("one signed message", first.opened.digest, again.opened.digest)
        assertEquals(About.Target.of(first.opened), About.Target.of(again.opened))
        assertNotEquals(About.Target.of(first.opened), About.Target.of(other.opened))

        // Written on a phone that shows the message from the first copy; read as the bytes that travel.
        val reply = sealed(6, "e608000000000006", "Coming over", About.Reply(About.Target.of(first.opened)), 4).opened.message
        val reaction = sealed(7, "e608000000000007", "🙏", About.Reaction(About.Target.of(first.opened)), 5).opened.message
        val named = (reply.about as About.Reply).target; val reacted = (reaction.about as About.Reaction).target

        // A phone that only ever got the re-sealed copy finds it, for the quote and for the chip. So does the first.
        for (copy in listOf(again, first)) {
            val rows = About.targets(listOf(shown(copy.opened)), "g")
            assertEquals("The bridge is open", rows[named.key]?.text)
            val held = Reactions()
            held.add("e608000000000007", Reactions.Entry("g", reacted.key, reaction.sender, reaction.text, reaction.ts, "e608000000000007"))
            val under = About.target(shown(copy.opened), "g")!!
            assertEquals(listOf("🙏"), held.chips("g", under.key, "me").map { it.text })
        }
        // A phone that holds another message under that id: no quote of it, no chip under it.
        val wrong = shown(other.opened)
        assertNull(About.targets(listOf(wrong), "g")[named.key])
        val held = Reactions()
        held.add("e608000000000007", Reactions.Entry("g", reacted.key, reaction.sender, reaction.text, reaction.ts, "e608000000000007"))
        assertEquals(emptyList<Reactions.Chip>(), held.chips("g", About.target(wrong, "g")!!.key, "me"))
        // With both on the phone, each is its own target.
        assertEquals("The bridge is open", About.targets(listOf(wrong, shown(again.opened)), "g")[named.key]?.text)
    }

    /** The digest covers the key the message was sealed for and how: the same fields sealed with the epoch's own key, or in another group, are another target. */
    @Test fun theSameWordsElsewhereAreAnotherTarget() {
        val a = sealed(5, "e608000000000005", "hello", ivByte = 1)
        val outer = epoch.seal("e608000000000005", 77, m1, "Bea", "hello", iv = iv) { sig }
        val b = epoch.openSigned(Packet("e608000000000005", epoch.hideTime(77, outer), 16, outer))!!
        assertNotEquals(About.Target.of(a.opened), About.Target.of(b))
        val elsewhere = group.seal("e608000000000005", 77, m1, "Bea", "hello", iv = iv) { sig }
        val c = group.openSigned(Packet("e608000000000005", 77, 16, elsewhere))!!
        assertNotEquals(About.Target.of(b), About.Target.of(c))
    }

    @Test fun aReplyOpensWithItsTarget() {
        val c = group.seal("00000000000000b1", 5, "1111111111111111", "Bea", "On my way", iv = iv, about = About.Reply(target)) { sig }
        val o = group.openSigned(Packet("00000000000000b1", 5, 16, c))!!
        assertEquals(About.Reply(target), o.message.about)
        assertEquals("On my way", o.message.text)
        assertNull("no photo: the 4th field is there and empty", o.extra); assertNull(o.message.photo)
        assertArrayEquals(About.Reply(target).field, o.aboutField)
        // The 4th field is written, empty, in front of the 5th.
        val fields = plain(c, "00000000000000b1", 5).let { it.copyOfRange(64, it.size) }
        val want = body("On my way", About.Reply(target).field)
        assertArrayEquals(want, fields.copyOf(want.size))
    }

    @Test fun aReplyCanCarryAPhoto() {
        val c = group.seal("00000000000000b2", 5, "1111111111111111", "Bea", Photo.text("Here"), jpeg, iv = iv, about = About.Reply(target)) { sig }
        val o = group.openSigned(Packet("00000000000000b2", 5, 16, c))!!
        assertArrayEquals(jpeg, o.message.photo); assertEquals(About.Reply(target), o.message.about); assertEquals("📷 Here", o.message.text)
    }

    @Test fun aReactionOpensAsOneAndEmptyTextTakesItBack() {
        for (text in listOf("👍", "", "❤️")) {
            val c = group.seal("00000000000000b3", 5, "1111111111111111", "Bea", text, iv = iv, about = About.Reaction(target)) { sig }
            val o = group.openSigned(Packet("00000000000000b3", 5, 16, c))!!
            assertEquals(text, About.Reaction(target), o.message.about); assertEquals(text, o.message.text)
            assertTrue(o.message.about!!.isSilent)
        }
    }

    /** The same with a message key, the way group messages really travel. */
    @Test fun replyAndReactionWithAMessageKey() {
        val keys = MessageKeys.of(secret, listOf(m0, m1))
        for ((n, about, text) in listOf(Triple(3, About.Reply(target), "ok"), Triple(4, About.Reaction(target), "❤️"))) {
            val mk = keys.key(1, n)!!
            val c = epoch.sealWith(mk, 1, n, "e60800000000000$n", 77, m1, "Bea", text, about = about, iv = iv) { sig }
            val o = epoch.openWith(mk, 1, n, Packet("e60800000000000$n", epoch.hideTime(77, c), 16, c))!!
            assertEquals(about, o.message.about); assertEquals(text, o.message.text); assertEquals(77L, o.message.ts)
        }
    }

    // MARK: - Fields a reader ignores

    @Test fun anotherTypeOrLengthIsAnOrdinaryMessage() {
        val good = About.Reply(target).field!!
        val cases = listOf(
            "another type" to byteArrayOf(0x03) + good.copyOfRange(1, 17), "type 0" to byteArrayOf(0x00) + good.copyOfRange(1, 17),
            "type 255" to byteArrayOf(0xFF.toByte()) + good.copyOfRange(1, 17), "one byte short" to good.copyOf(16), "one byte long" to good + byteArrayOf(0),
            "only the type" to byteArrayOf(0x01), "empty" to ByteArray(0), "no 5th field" to null,
        )
        cases.forEachIndexed { i, (what, fifth) ->
            val o = group.openSigned(sealRaw(body("hello", fifth), "00000000000000c%x".format(i), 9))
            assertNotNull(what, o); o!!
            assertNull(what, o.message.about); assertNull(what, o.aboutField)
            assertEquals(what, "hello", o.message.text); assertEquals("1111111111111111", o.message.sender)
        }
    }

    @Test fun whatFollowsThe5thFieldIsIgnored() {
        val b = body("hello", About.Reply(target).field, field(utf8("a 6th field")) + byteArrayOf(1, 2, 3))
        assertEquals(About.Reply(target), group.openSigned(sealRaw(b, "00000000000000d1", 9))?.message?.about)
    }

    /** Padding is zero bytes: it reads as an empty 4th and an empty 5th field, so a padded message is about nothing. */
    @Test fun paddingIsNeverAnAboutField() {
        for (pad in Pad.entries) {
            val c = group.seal("00000000000000d2", 5, "1111111111111111", "Bea", "hello", iv = iv, pad = pad) { sig }
            assertNull(group.openSigned(Packet("00000000000000d2", 5, 16, c))?.message?.about)
        }
        // After a photo too, and after an introduction's 4th field.
        val c = group.seal("00000000000000d3", 5, "1111111111111111", "Bea", Photo.PLACEHOLDER, jpeg, iv = iv) { sig }
        assertNull(group.openSigned(Packet("00000000000000d3", 5, 16, c))?.message?.about)
        val intro = group.intros().seal("00000000000000d4", 5, "1111111111111111", "Bea", "", extra = byteArrayOf(2) + ByteArray(98) { 4 }, iv = iv) { sig }
        assertNull(group.intros().openSigned(Packet("00000000000000d4", 5, 16, intro))?.message?.about)
    }

    /** The reactions are a list: exactly these bytes, or none at all. The heart carries its variation selector. */
    @Test fun theListedReactions() {
        assertEquals(listOf("f09f918d", "e29da4efb88f", "f09f9882", "f09f98ae", "f09f998f"), ReactionList.listed.map { hex(utf8(it)) })
        assertEquals(ReactionList.listed, ReactionList.offered)
        assertEquals(ReactionList.listed.size, ReactionList.listed.map { hex(utf8(it)) }.toSet().size)
        for (e in ReactionList.listed) assertTrue(e, utf8(e).size <= ReactionList.MAX_BYTES)
    }

    /** A type 0x02 message whose text is not a listed reaction, byte for byte, is not a reaction and not a message either: nobody can put text under someone's message. */
    @Test fun aReactionWithAnyOtherTextIsNothing() {
        val r = About.Reaction(target).field!!
        fun read(text: ByteArray) = About.read(r, text)
        assertEquals("takes it back", About.Reaction(target), read(ByteArray(0)))
        for (e in ReactionList.listed) assertEquals(e, About.Reaction(target), read(utf8(e)))
        val others = listOf(
            "a word" to utf8("ok"), "a check mark" to utf8("✅"), "a call for help" to utf8("🆘"),
            "the heart without its variation selector" to byteArrayOf(0xE2.toByte(), 0x9D.toByte(), 0xA4.toByte()),
            "the heart with the text selector" to byteArrayOf(0xE2.toByte(), 0x9D.toByte(), 0xA4.toByte(), 0xEF.toByte(), 0xB8.toByte(), 0x8E.toByte()),
            "a listed one twice" to utf8("👍👍"), "a listed one and a space" to utf8("👍 "), "a space and a listed one" to utf8(" 👍"),
            "a listed one with a skin tone" to utf8("👍🏽"), "another emoji" to utf8("🎉"), "a zero byte" to byteArrayOf(0), "a space" to byteArrayOf(0x20),
            "16 letters" to ByteArray(16) { 0x41 }, "17 bytes" to ByteArray(17) { 0x41 }, "not UTF-8" to byteArrayOf(0xFF.toByte()),
            "a listed one cut short" to byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x91.toByte()), "a listed one and a zero byte" to utf8("👍") + byteArrayOf(0),
        )
        for ((what, text) in others) assertEquals(what, About.Nothing, read(text))
        assertTrue(About.Nothing.isSilent); assertNull(About.Nothing.target)
        // Sealed and opened: the same.
        listOf("ok", "✅", "seventeen bytes!!").forEachIndexed { n, text ->
            assertEquals(text, About.Nothing, group.openSigned(sealRaw(body(text, r), "00000000000000e$n", 9))?.message?.about)
        }
        // A reply's text is never judged.
        assertEquals(About.Reply(target), About.read(About.Reply(target).field, ByteArray(400) { 0xFF.toByte() }))
    }

    @Test fun thisAppOnlyWritesListedReactions() {
        fun seal(text: String, photo: ByteArray? = null) = group.seal("00000000000000e9", 5, "1111111111111111", "Bea", text, photo, iv = iv, about = About.Reaction(target)) { sig }
        for (bad in listOf("ok", "✅", "🆘", "👍👍", "seventeen bytes!!", "❤")) {
            assertThrows(bad, IllegalArgumentException::class.java) { seal(bad) }
            assertFalse(bad, ReactionList.sendable(bad))
        }
        assertThrows("a reaction carries no photo", IllegalArgumentException::class.java) { seal("👍", jpeg) }
        for (e in ReactionList.listed) { seal(e); assertTrue(e, ReactionList.sendable(e)) }
        seal("") // taking it back
        assertFalse("a tap is always a listed reaction; the app makes the empty one itself", ReactionList.sendable(""))
    }

    /** An app without reactions shows a reaction as an ordinary message: no listed reaction may read as a check-in, a report or a request there. */
    @Test fun listedReactionsAreNeverTakenForACheckIn() {
        assertTrue(ReactionList.listed.isNotEmpty())
        for (e in ReactionList.listed) {
            assertNull(e, CheckIn.of(e)); assertNull(e, Vouch.of(e)); assertNull(e, AreYouSafe.of(e)); assertNull(e, Place.of(e))
        }
        assertEquals("why it is not listed", CheckIn.SAFE, CheckIn.of("✅"))
    }

    // MARK: - A reader that doesn't know the field

    /** The promise of "Encryption" and "Photos": a reader that knows four fields reads the same sender, name, text and photo whether or not a 5th field follows. */
    @Test fun olderReadersReadTheSameFields() {
        val cases = listOf(Triple("On my way", null, About.Reply(target)), Triple("👍", null, About.Reaction(target)), Triple("", null, About.Reaction(target)),
            Triple(Photo.text("Here"), jpeg, About.Reply(target)))
        cases.forEachIndexed { i, (text, photo, about) ->
            val id = "00000000000000f%x".format(i)
            val with = group.seal(id, 9, "1111111111111111", "Bea", text, photo, iv = iv, about = about) { sig }
            val without = group.seal(id, 9, "1111111111111111", "Bea", text, photo, iv = iv) { sig }
            val a = oldRead(plain(with, id, 9))!!; val b = oldRead(plain(without, id, 9))!!
            assertEquals(b.sender, a.sender); assertEquals(b.name, a.name); assertArrayEquals(b.text, a.text); assertArrayEquals(b.fourth, a.fourth)
            assertArrayEquals(utf8(text), a.text); assertArrayEquals(photo?.let { byteArrayOf(Photo.JPEG) + it }, a.fourth)
            // And this app, reading a message without the field, reads what the old reader reads.
            val o = group.openSigned(Packet(id, 9, 16, without))!!
            assertEquals(text, o.message.text); assertArrayEquals(photo, o.message.photo); assertNull(o.message.about)
        }
    }

    /** The field is inside the signed body: it can't be added to, changed in or cut from someone's message. */
    @Test fun theFieldIsSigned() {
        fun digest(about: About?): String {
            val c = group.seal("0000000000000101", 9, "1111111111111111", "Bea", "👍", iv = iv, pad = Pad.NONE, about = about) { sig }
            return hex(group.openSigned(Packet("0000000000000101", 9, 16, c))!!.digest)
        }
        val other = About.Target.parse("00000000000000a2:5a5a5a5a5a5a5a5a")!!
        assertEquals(4, setOf(digest(null), digest(About.Reply(target)), digest(About.Reaction(target)), digest(About.Reply(other))).size)
    }

    // MARK: - Targets on the reader's own phone

    private fun row(id: String, group: String? = "g", verified: Boolean = true, public: Boolean = false, about: About? = null, mark: String = "0101010101010101") =
        Message(id, 10, "2222222222222222", "Ann", "hi", public, group = group, verified = verified, about = about, mark = About.Target.parse("$id:$mark"))

    @Test fun onlyACheckedGroupMessageOfTheSameGroupIsATarget() {
        val ok = row("0000000000000201")
        assertEquals(ok.mark, About.target(ok, "g"))
        assertNull("another group", About.target(ok, "other"))
        assertNull(About.target(ok, null))
        assertNull("not verified yet", About.target(row("0000000000000202", verified = false), "g"))
        assertNull("the Public channel", About.target(row("0000000000000203", group = null, public = true), "g"))
        assertNull("a reaction", About.target(row("0000000000000204", about = About.Reaction(target)), "g"))
        assertNull(About.target(row("0000000000000205", about = About.Nothing), "g"))
        assertNotNull("a reply can be replied to", About.target(row("0000000000000206", about = About.Reply(target)), "g"))
        assertNull(About.target(ok.copy(mark = null), "g"))
    }

    /** Another message under the same id is not the target: a reply to the real one doesn't find it. */
    @Test fun aReplyFindsOnlyTheVeryMessage() {
        val real = row("0000000000000301"); val junk = row("0000000000000301", mark = "0202020202020202").copy(id = "0000000000000301:aaaaaaaaaaaaaaaa")
        val rows = listOf(junk, real, row("0000000000000302", verified = false), row("0000000000000303", group = "other"))
        val found = About.targets(rows, "g")
        assertEquals(2, found.size)
        assertEquals(real.id, found[real.mark!!.key]?.id)
        assertEquals(junk.id, found[junk.mark!!.key]?.id)
        assertNull(found[rows[2].mark!!.key]); assertNull(found[rows[3].mark!!.key])
        assertNull("a message this phone doesn't have", found[target.key])
    }

    @Test fun quoteLine() {
        fun m(text: String, photo: ByteArray? = null) = Message("x", 1, "2222222222222222", "Marco", text, photo = photo, group = "g")
        assertEquals("Water is back.", Quote.line(m("Water is back.\nBring bottles.")))
        assertEquals("second", Quote.line(m("\n\n  second  \nthird")))
        assertEquals("Photo", Quote.line(m("📷 Photo", jpeg)))
        assertEquals("Bridge is out", Quote.line(m("📷 Bridge is out", jpeg)))
        assertEquals("no photo came with it: its text as it is", "📷 Photo", Quote.line(m("📷 Photo")))
        assertEquals("✅ I'm safe · 🔋 64%", Quote.line(m("✅ I'm safe · 🔋 64%")))
        assertEquals(Quote.MAX_CHARS + 1, Quote.line(m("a".repeat(500))).length)
        assertEquals("a skin tone is one character", Quote.MAX_CHARS + 1, java.text.BreakIterator.getCharacterInstance().run {
            val s = Quote.line(m("👍🏽".repeat(200))); setText(s); var n = 0; while (next() != java.text.BreakIterator.DONE) n++; n })
        assertEquals("", Quote.line(m("")))
        assertEquals("Marco", Quote.name(m("hi"), "dev"))
        assertEquals("You", Quote.name(m("hi").copy(sender = "dev"), "dev"))
        assertEquals("Someone", Quote.name(m("hi").copy(name = ""), "dev"))
        assertEquals(Quote.MISSING, "A message this phone doesn't have"); assertEquals(Quote.HIDDEN, "A message hidden on this phone")
    }

    /** What the chat quotes: from this phone's own copy, or why there is none. */
    @Test fun quoteOfAReply() {
        val t = row("0000000000000501").copy(text = "Water is back.\nBring bottles.", name = "Marco")
        val reply = row("0000000000000502", about = About.Reply(t.mark!!))
        val shown = About.targets(listOf(t, reply), "g")
        assertEquals(QuoteLine("Marco", "Water is back.", t.id), quoteOf(reply, shown, shown, "dev"))
        assertEquals("hidden here: it is held, not shown", QuoteLine(null, Quote.HIDDEN), quoteOf(reply, emptyMap(), shown, "dev"))
        assertEquals(QuoteLine(null, Quote.MISSING), quoteOf(reply, emptyMap(), emptyMap(), "dev"))
        assertNull("not verified yet: no quote", quoteOf(reply.copy(verified = false), shown, shown, "dev"))
        assertNull("an ordinary message", quoteOf(t, shown, shown, "dev"))
        assertNull("on Everyone nothing of it is read", quoteOf(reply.copy(public = true), shown, shown, "dev"))
        // Nothing is taken from the reply: a reply whose own text pretends to quote shows the target as this phone has it.
        assertEquals("Water is back.", quoteOf(reply.copy(text = "> Marco: the water is poisoned"), shown, shown, "dev")?.text)
    }

    // MARK: - Which reactions count

    private fun e(sender: String, text: String, ts: Long, id: String, group: String = "g", t: String? = null) = Reactions.Entry(group, t ?: target.key, sender, text, ts, id)
    private fun chips(r: Reactions, me: String = "me") = r.chips("g", target.key, me).map { "${it.text}${it.count}${if (it.mine) "*" else ""}" }

    @Test fun oneReactionPerMemberTheLatestCounts() {
        val r = Reactions()
        r.add("p1", e("a", "❤️", 100, "0000000000000001")); r.add("p2", e("b", "👍", 150, "0000000000000002"))
        r.add("p3", e("a", "👍", 300, "0000000000000003")); r.add("p4", e("a", "😂", 200, "0000000000000004")) // arrives last, written earlier: doesn't count
        assertEquals(listOf("👍2"), chips(r))
        val back = Reactions()
        for ((n, x) in listOf("p4" to e("a", "😂", 200, "0000000000000004"), "p3" to e("a", "👍", 300, "0000000000000003"), "p2" to e("b", "👍", 150, "0000000000000002"), "p1" to e("a", "❤️", 100, "0000000000000001"))) back.add(n, x)
        assertEquals(listOf("👍2"), chips(back))
        assertEquals(r, back)
    }

    @Test fun atTheSameTimeTheGreaterIdCounts() {
        val r = Reactions(); r.add("p1", e("a", "😮", 100, "00000000000000b0")); r.add("p2", e("a", "👍", 100, "00000000000000af"))
        assertEquals(listOf("😮1"), chips(r))
        val back = Reactions(); back.add("p2", e("a", "👍", 100, "00000000000000af")); back.add("p1", e("a", "😮", 100, "00000000000000b0"))
        assertEquals(listOf("😮1"), chips(back))
    }

    @Test fun emptyTextTakesItBack() {
        val r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001")); r.add("p2", e("b", "🙏", 100, "0000000000000002")); r.add("p3", e("a", "", 200, "0000000000000003"))
        assertEquals(listOf("🙏1"), chips(r))
        assertEquals("taken back, not never reacted", "", r.mine("g", target.key, "a")?.text)
        assertNull(r.mine("g", target.key, "c"))
        r.add("p4", e("a", "❤️", 300, "0000000000000004")) // and again
        assertEquals(listOf("❤️1", "🙏1"), chips(r))
    }

    @Test fun mineIsMarkedAndChipsComeInTheOrderOfTheList() {
        val r = Reactions()
        r.add("p1", e("a", "🙏", 100, "0000000000000001")); r.add("p2", e("me", "🙏", 100, "0000000000000002"))
        r.add("p3", e("b", "❤️", 100, "0000000000000003")); r.add("p4", e("c", "👍", 100, "0000000000000004")); r.add("p5", e("d", "😮", 100, "0000000000000005"))
        assertEquals(listOf("👍1", "❤️1", "😮1", "🙏2*"), chips(r))
        assertEquals("🙏", r.mine("g", target.key, "me")?.text)
    }

    /** Only listed reactions are ever held: whatever else is handed in is not taken, shows nothing, and doesn't replace the member's reaction. */
    @Test fun onlyListedReactionsAreHeld() {
        val r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001"))
        listOf("ok", "✅", "❤", "👍👍", "Marco lied", " ").forEachIndexed { n, text -> r.add("x$n", e("a", text, 200L + n, "00000000000000f$n")) }
        r.add("y1", e("b", "ok", 100, "0000000000000002"))
        assertEquals(1, r.count)
        assertEquals(listOf("👍1"), chips(r))
        assertEquals("👍", r.mine("g", target.key, "a")?.text)
        assertNull(r.mine("g", target.key, "b"))
    }

    /** A reaction counts only for the target it names, in the group whose key opened it. */
    @Test fun aReactionCountsOnlyInItsOwnGroupAndForItsOwnTarget() {
        val r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001", group = "other"))
        r.add("p2", e("a", "👍", 100, "0000000000000002", t = "00000000000000a9:00000000000000a9"))
        assertEquals(emptyList<String>(), chips(r))
        assertEquals(listOf("👍"), r.chips("other", target.key, "me").map { it.text })
    }

    /** A reaction whose target is a reaction is never shown: a reaction is no row, so nothing looks its reactions up. */
    @Test fun aReactionToAReactionIsIgnored() {
        val first = row("0000000000000401", about = About.Reaction(target))
        assertNull(About.target(first, "g"))
        assertTrue(About.targets(listOf(first), "g").isEmpty())
        val r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000401"))
        r.add("p2", e("b", "❤️", 200, "0000000000000402", t = first.mark!!.key))
        assertEquals(listOf("👍1"), chips(r))
    }

    @Test fun aReactionGoesWithItsPacket() {
        val r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001")); r.add("p2", e("a", "❤️", 200, "0000000000000002"))
        r.add("p2", e("a", "😂", 900, "0000000000000009")) // the same packet again: nothing changes
        assertEquals(listOf("❤️1"), chips(r)); assertEquals(2, r.count)
        r.forget(listOf("p2", "unknown"))
        assertEquals("what is left is what the stored packets say", listOf("👍1"), chips(r))
        r.forget(listOf("p1"))
        assertEquals(emptyList<String>(), chips(r)); assertEquals(Reactions(), r)
    }

    @Test fun blockedPeoplesReactionsAreNotShownAndWebPrefixIsTheSameMember() {
        val r = Reactions()
        r.add("p1", e("a", "👍", 100, "0000000000000001")); r.add("p2", e("b", "👍", 100, "0000000000000002")); r.add("p3", e("web-a", "❤️", 200, "0000000000000003"))
        assertEquals("a's later one, under web-, replaces a's", listOf("👍1", "❤️1"), chips(r))
        assertEquals(listOf("❤️"), r.chips("g", target.key, "me") { it == "b" }.map { it.text })
    }

    // MARK: - This phone's copy

    @Test fun thisPhonesCopyKeepsTheField() {
        val keys = MessageKeys.of(secret, listOf(m0, m1)); val key = ByteArray(32) { 3 }
        fun opened(n: Int, text: String, about: About?, photo: ByteArray? = null): GroupCrypto.Opened {
            val mk = keys.key(1, n)!!
            val c = epoch.sealWith(mk, 1, n, "e608000000000021", 77, m1, "Bea", text, photo, iv = iv, about = about) { sig }
            return epoch.openWith(mk, 1, n, Packet("e608000000000021", epoch.hideTime(77, c), 16, c))!!
        }
        for ((n, text, about, photo) in listOf(Quad(1, "ok", About.Reply(target), null), Quad(2, "👍", About.Reaction(target), null), Quad(3, "", About.Reaction(target), null), Quad(4, "📷 Here", About.Reply(target), jpeg))) {
            val o = opened(n, text, about, photo)
            val back = History.open(key, "e608000000000021", History.seal(key, "e608000000000021", "0123456789abcdef0123456789abcdef", "eid", o, 1, n)!!)!!
            assertEquals(about, back.opened.message.about); assertEquals(text, back.opened.message.text); assertArrayEquals(photo, back.opened.message.photo)
            assertArrayEquals(about.field, back.opened.aboutField)
        }
        // A message without the field: the copy reads back as an ordinary message.
        val plainCopy = History.open(key, "e608000000000021", History.seal(key, "e608000000000021", "0123456789abcdef0123456789abcdef", "eid", opened(5, "hello", null), 1, 5)!!)!!
        assertNull(plainCopy.opened.message.about); assertNull(plainCopy.opened.aboutField); assertEquals("hello", plainCopy.opened.message.text)
        // A type 0x02 message that is no reaction gets no copy: nothing of it is ever shown.
        val v = opened(6, "hello", null)
        val void = GroupCrypto.Opened(v.message.copy(about = About.Nothing), v.signature, v.digest, null, About.Reaction(target).field)
        assertNull(History.seal(key, "e608000000000021", "0123456789abcdef0123456789abcdef", "eid", void, 1, 6))
    }
    private data class Quad(val n: Int, val text: String, val about: About, val photo: ByteArray?)

    /** The size a reply adds: 21 bytes (2 for the empty 4th field, 2 and 17 for the 5th), 19 next to a photo. */
    @Test fun size() {
        fun size(about: About?, photo: ByteArray? = null) = group.seal("0000000000000501", 9, "1111111111111111", "Bea", "ok", photo, iv = iv, pad = Pad.NONE, about = about) { sig }.size
        assertEquals(21, size(About.Reply(target)) - size(null))
        assertEquals(19, size(About.Reply(target), jpeg) - size(null, jpeg))
    }
}
