package it.blackout.mesh

import java.util.Base64

/**
 * A person code: this member's public key in one group, as text for a QR code that the other person scans while both
 * are in the same place. It is `DPK1:` followed by base64url, without padding, of the 33-byte compressed public key:
 * exactly the bytes the person emoji are made from (P256.emoji), so a code and the emoji always say the same thing.
 * Optical only: it is shown and scanned, never sent in a packet. The iPhone makes and reads the same text.
 *
 * Plain Kotlin, so unit tests run it.
 */
object PersonCode {
    const val PREFIX = "DPK1:"
    const val KEY_BYTES = 33
    /** 33 bytes are 44 base64url characters, with no padding. */
    const val LENGTH = 5 + 44

    fun encode(publicKey: ByteArray): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)

    /**
     * The key in [text], or null if it isn't a person code: exactly the prefix, then exactly the 44 base64url characters
     * that 33 bytes make, and those bytes start with 0x02 or 0x03 as a compressed public key does. Nothing is trimmed
     * and nothing else is taken: no spaces around it, no padding, no other alphabet, no other length.
     */
    fun decode(text: String): ByteArray? {
        if (text.length != LENGTH || !text.startsWith(PREFIX)) return null
        val body = text.substring(PREFIX.length)
        if (!body.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        val key = runCatching { Base64.getUrlDecoder().decode(body) }.getOrNull() ?: return null
        // 33 bytes fill 44 characters exactly, so only one text stands for a key.
        return key.takeIf { it.size == KEY_BYTES && (it[0] == 2.toByte() || it[0] == 3.toByte()) && encode(it) == text }
    }

    enum class Result {
        /** The scanned key is the one this phone holds for the person. */
        MATCH,
        /** It is this phone's own code. */
        OWN,
        /** A person code, but of another key. */
        OTHER,
        /** Not a person code at all. */
        NOT_A_CODE,
        /** This phone has no key for the person yet, so there is nothing to compare with. */
        NO_KEY,
    }

    /**
     * What a scanned [text] says about a person. [theirs]: the public key this phone holds for them in this group, or
     * null if their introduction hasn't arrived. [mine]: this phone's own public key there. Every byte is compared.
     */
    fun check(text: String, theirs: ByteArray?, mine: ByteArray?): Result {
        val key = decode(text) ?: return Result.NOT_A_CODE
        return when {
            mine != null && key.contentEquals(mine) -> Result.OWN
            theirs == null -> Result.NO_KEY
            key.contentEquals(theirs) -> Result.MATCH
            else -> Result.OTHER
        }
    }
}
