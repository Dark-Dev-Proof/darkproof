package it.blackout.mesh

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * Member keys and signatures (spec/FORMAT.md, "Member ids", "Signatures", "Person emoji"): P-256 with the phone's own
 * crypto (Conscrypt), so signing and checking are native code. Only point compression is done here.
 */
object P256 {
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun sha256(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }
    private fun bytes32(v: BigInteger): ByteArray = v.toByteArray().let { b -> if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }

    /** The 33-byte compressed public key (SEC 1). */
    fun compress(k: PublicKey): ByteArray { val w = (k as ECPublicKey).w; return byteArrayOf(if (w.affineY.testBit(0)) 3 else 2) + bytes32(w.affineX) }

    /** The key of a 33-byte compressed point, or null if it isn't a point on P-256. */
    fun decompress(c: ByteArray): PublicKey? {
        if (c.size != 33 || (c[0].toInt() != 2 && c[0].toInt() != 3)) return null
        val x = BigInteger(1, c.copyOfRange(1, 33)); if (x >= P) return null
        val y2 = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        var y = y2.modPow(P.add(BigInteger.ONE).shiftRight(2), P) // P ≡ 3 (mod 4)
        if (y.multiply(y).mod(P) != y2) return null
        if (y.testBit(0) != (c[0].toInt() == 3)) y = P.subtract(y)
        return runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) }.getOrNull()
    }

    private fun onCurve(x: BigInteger, y: BigInteger) = x < P && y < P && y.multiply(y).mod(P) == x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)

    /** The 65-byte uncompressed point 04 ‖ x ‖ y (HPKE's encoding). */
    fun uncompressed(k: PublicKey): ByteArray { val w = (k as ECPublicKey).w; return byteArrayOf(4) + bytes32(w.affineX) + bytes32(w.affineY) }

    /** The key of a 65-byte uncompressed point, or null if it isn't one on P-256. */
    fun fromUncompressed(u: ByteArray): PublicKey? {
        if (u.size != 65 || u[0].toInt() != 4) return null
        val x = BigInteger(1, u.copyOfRange(1, 33)); val y = BigInteger(1, u.copyOfRange(33, 65))
        if (!onCurve(x, y)) return null
        return runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) }.getOrNull()
    }

    /** A private key from its 32-byte scalar (test vectors). */
    fun privateKey(scalar: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(java.security.spec.ECPrivateKeySpec(BigInteger(1, scalar), params))

    /** First 8 bytes of SHA-256(public key), as 16 lowercase hex chars. */
    fun memberId(pub: ByteArray): String = sha256(pub).copyOf(8).joinToString("") { "%02x".format(it) }

    /** The 16 emoji indices of a member's public key: the first 96 bits of the hash, 6 bits each. */
    fun emoji(pub: ByteArray): List<Int> {
        val h = sha256("darkproof-person-emoji-v1".toByteArray(), pub)
        return (0 until 16).map { i ->
            val bit = 6 * i; val at = bit / 8
            ((((h[at].toInt() and 0xFF) shl 8) or (h[at + 1].toInt() and 0xFF)) shr (10 - bit % 8)) and 63
        }
    }

    /** Signs the whole signed message (spec "Signatures") and returns r ‖ s. */
    fun sign(key: PrivateKey, msg: ByteArray): ByteArray =
        fromDer(Signature.getInstance("SHA256withECDSA").run { initSign(key); update(msg); sign() })

    /** Checks r ‖ s against SHA-256 of the signed message ([digest]). */
    fun verify(key: PublicKey, digest: ByteArray, raw: ByteArray): Boolean = raw.size == 64 && runCatching {
        Signature.getInstance("NONEwithECDSA").run { initVerify(key); update(digest); verify(toDer(raw)) }
    }.getOrDefault(false)

    /** A software key pair, for tests and debug messages. */
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun toDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray { var v = b.dropWhile { it == 0.toByte() }.toByteArray(); if (v.isEmpty()) v = byteArrayOf(0); if (v[0] < 0) v = byteArrayOf(0) + v; return byteArrayOf(2, v.size.toByte()) + v }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun fromDer(der: ByteArray): ByteArray {
        var i = 2 // 0x30, length (always < 128 for P-256)
        fun next(): ByteArray { require(der[i] == 2.toByte()); val n = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + n); i += 2 + n; return bytes32(BigInteger(1, v)) }
        return next() + next()
    }
}
