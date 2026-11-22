package it.blackout.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The person code (spec/FORMAT.md, "Person code"): `DPK1:` and the member's 33-byte public key in base64url, read strictly. */
class PersonCodeTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    /** The spec's member vector: the same key the person emoji are made from. */
    private val key = hex("02ea9227dede645b42e9d369c0759de19b46685357f9d0485b8dc80a535e659acf")
    private val code = "DPK1:AuqSJ97eZFtC6dNpwHWd4ZtGaFNX-dBIW43IClNeZZrP"
    private val other = P256.compress(P256.newKeyPair().public)
    private val mine = P256.compress(P256.newKeyPair().public)

    @Test fun encodesTheSpecsVector() {
        assertEquals(code, PersonCode.encode(key))
        assertEquals(49, code.length)
    }

    @Test fun decodesWhatItEncodes() {
        assertArrayEquals(key, PersonCode.decode(code))
        for (k in listOf(other, mine)) assertArrayEquals(k, PersonCode.decode(PersonCode.encode(k)))
    }

    @Test fun aCodeIsWhatTheEmojiAreMadeFrom() {
        // The same bytes in, the same emoji out: a scanned code and the emoji on the screen can't disagree.
        assertEquals(P256.emoji(key), P256.emoji(PersonCode.decode(code)!!))
        assertEquals(16, P256.emoji(key).size)
    }

    @Test fun wrongPrefixIsNotACode() {
        for (t in listOf("DPK2:" + code.drop(5), "dpk1:" + code.drop(5), "DPK1" + code.drop(5), code.drop(5), "darkproof://join?code=ABCD", "", "DPK1:"))
            assertNull(t, PersonCode.decode(t))
    }

    @Test fun badBase64IsNotACode() {
        val body = code.drop(5)
        for (t in listOf(
            "DPK1:" + body.replace('-', '+'),            // the other base64 alphabet
            "DPK1:" + body.dropLast(1) + "=",            // padding
            "DPK1:" + body.dropLast(1) + "!",            // not a base64 character
            "DPK1:" + body.dropLast(1) + " ",            // a space inside
        )) assertNull(t, PersonCode.decode(t))
    }

    @Test fun nothingIsTrimmedAndTheLengthIsExact() {
        for (t in listOf(" $code", "$code ", "$code\n", code.dropLast(1), code + "A", code + "=", code + code.drop(5)))
            assertNull(t, PersonCode.decode(t))
    }

    @Test fun theKeyMustBeACompressedPoint() {
        // 33 bytes that start with anything but 02 or 03 are not a public key in this form.
        for (first in listOf(0, 1, 4, 5, 0xff)) assertNull(PersonCode.decode(PersonCode.encode(byteArrayOf(first.toByte()) + key.drop(1))))
        assertArrayEquals(byteArrayOf(3) + key.drop(1), PersonCode.decode(PersonCode.encode(byteArrayOf(3) + key.drop(1))))
        // The wrong number of bytes never gives 44 characters.
        assertNull(PersonCode.decode(PersonCode.encode(key.copyOf(32)))); assertNull(PersonCode.decode(PersonCode.encode(key + 0)))
    }

    @Test fun equalKeysMatchAndUnequalOnesDoNot() {
        assertEquals(PersonCode.Result.MATCH, PersonCode.check(code, key, mine))
        assertEquals(PersonCode.Result.OTHER, PersonCode.check(code, other, mine))
        // One bit of difference is a different key.
        val near = key.copyOf().also { it[32] = (it[32].toInt() xor 1).toByte() }
        assertEquals(PersonCode.Result.OTHER, PersonCode.check(PersonCode.encode(near), key, mine))
    }

    @Test fun ownCodeNoKeyAndNotACode() {
        assertEquals(PersonCode.Result.OWN, PersonCode.check(PersonCode.encode(mine), key, mine))
        assertEquals(PersonCode.Result.NO_KEY, PersonCode.check(code, null, mine))        // their introduction hasn't arrived
        assertEquals(PersonCode.Result.OWN, PersonCode.check(PersonCode.encode(mine), null, mine))
        assertEquals(PersonCode.Result.MATCH, PersonCode.check(code, key, null))
        assertEquals(PersonCode.Result.NOT_A_CODE, PersonCode.check("darkproof://join?name=A&code=B", key, mine))
        assertEquals(PersonCode.Result.NOT_A_CODE, PersonCode.check(" $code", key, mine))
    }
}
