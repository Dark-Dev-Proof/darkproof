package it.blackout.mesh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * This phone's member keys, one per group (spec "Member ids"), in the Android Keystore: the private key never leaves
 * it. [tag] identifies the group without revealing its key (GroupCrypto.tag).
 */
object MemberKeys {
    private val cache = HashMap<String, KeyPair>()

    @Synchronized fun forGroup(tag: String): KeyPair = cache.getOrPut(tag) {
        val alias = "darkproof-member-$tag"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return@getOrPut KeyPair(it.certificate.publicKey, it.privateKey) }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }
}

/**
 * Small secrets on this phone (epoch secrets, encryption keys: spec/DESIGN-v0.6-groups.md, "Keys"), encrypted with an
 * AES key that never leaves the Android Keystore, in the app's private preferences.
 */
object Vault {
    private const val ALIAS = "darkproof-vault"

    @Synchronized private fun key(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    /** Saves [data] under [name] (null removes it). */
    fun put(c: android.content.Context, name: String, data: ByteArray?) {
        val prefs = c.getSharedPreferences("vault", 0)
        if (data == null) { prefs.edit().remove(name).apply(); return }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(data)
        prefs.edit().putString(name, android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)).apply()
    }

    fun get(c: android.content.Context, name: String): ByteArray? = runCatching {
        val raw = android.util.Base64.decode(c.getSharedPreferences("vault", 0).getString(name, null) ?: return null, android.util.Base64.NO_WRAP)
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12))
        }.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    /**
     * This phone's encryption key in group [groupId] (HPKE recipient key), made the first time. From Android 12 the private
     * key is made inside the Keystore and can't be copied off the phone. A key made earlier stays: other members have
     * sealed new keys to it.
     */
    fun encryptionKey(c: android.content.Context, groupId: String): KeyPair {
        get(c, "enc.$groupId")?.let { b ->
            runCatching {
                val n = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                val f = KeyFactory.getInstance("EC")
                return KeyPair(f.generatePublic(java.security.spec.X509EncodedKeySpec(b.copyOfRange(2 + n, b.size))),
                    f.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(b.copyOfRange(2, 2 + n))))
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { return keystoreEncryptionKey("darkproof-enc-$groupId") }
            .onFailure { DebugTools.log("no Keystore encryption key: $it") }
        val kp = P256.newKeyPair(); val priv = kp.private.encoded
        put(c, "enc.$groupId", byteArrayOf((priv.size ushr 8).toByte(), priv.size.toByte()) + priv + kp.public.encoded)
        return kp
    }

    @androidx.annotation.RequiresApi(31)
    private fun keystoreEncryptionKey(alias: String): KeyPair {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return KeyPair(it.certificate.publicKey, it.privateKey) }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
        }.generateKeyPair().also { DebugTools.log("encryption key made in the Keystore") }
    }

    /** Deletes an encryption key for good: what was sealed to it can't be opened any more, on this phone or by anyone who takes it. */
    fun forgetEncryptionKey(c: android.content.Context, groupId: String) {
        put(c, "enc.$groupId", null)
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("darkproof-enc-$groupId") }
    }
}
