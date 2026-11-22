package it.blackout.mesh

import android.content.Context
import it.blackout.desktop.Home
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The desktop's versions of the phone's key objects (app/.../Keystore.kt), with the same names and calls, so the
// phone's engine compiles against them unchanged.
//
// A phone keeps these keys in its Keystore, where they can't be copied out. A desktop has no such place that works
// the same on Windows, macOS and Linux, so here the vault's key is a file in DarkProof's data folder: it is as safe as
// the user's account on this computer, no safer. (To do for the Windows installer: wrap that file with Windows' own
// data protection.)

/** Small secrets on this computer (group codes, epoch secrets, keys), encrypted with the key in vault.key. */
object Vault {
    private const val KEY_FILE = "vault.key"
    private val keys = HashMap<File, SecretKey>() // by data folder: there is one, except in tests

    @Synchronized private fun key(c: Context): SecretKey {
        keys[c.filesDir]?.let { return it }
        val f = File(c.filesDir, KEY_FILE)
        // KeyProtect holds the key in the computer's own key store (Keychain, DPAPI, Secret Service) in the real app, so
        // a copy of this folder alone can't read what the Vault encrypts. It returns null only when there is no key yet;
        // a present-but-unreadable file throws rather than being silently replaced (that would lose every saved key).
        val raw = KeyProtect.read(c.filesDir, f) ?: ByteArray(32).also { SecureRandom().nextBytes(it); KeyProtect.write(c.filesDir, f, it) }
        return SecretKeySpec(raw, "AES").also { keys[c.filesDir] = it }
    }

    /**
     * "Erase everything": the key of data folder [dir] goes, its file and what memory holds of it (the item in the
     * computer's own key store is [KeyProtect.clear]'s). The erase's first step: nothing the vault holds opens after it.
     */
    @Synchronized internal fun forgetKey(dir: File) { keys.remove(dir); runCatching { File(dir, KEY_FILE).delete() } }

    /** Saves [data] under [name] (null removes it). */
    fun put(c: Context, name: String, data: ByteArray?) {
        val prefs = c.getSharedPreferences("vault", 0)
        if (data == null) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(c)) }
        prefs.edit().putString(name, Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(data))).apply()
    }

    fun get(c: Context, name: String): ByteArray? = runCatching {
        val raw = Base64.getDecoder().decode(c.getSharedPreferences("vault", 0).getString(name, null) ?: return null)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(c), GCMParameterSpec(128, raw, 0, 12)) }.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    /** A P-256 key pair kept under [name], made the first time. Saved as the phones save theirs: length, private key, public key. */
    internal fun pair(c: Context, name: String): KeyPair {
        get(c, name)?.let { b ->
            runCatching {
                val n = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF); val f = KeyFactory.getInstance("EC")
                return KeyPair(f.generatePublic(X509EncodedKeySpec(b.copyOfRange(2 + n, b.size))), f.generatePrivate(PKCS8EncodedKeySpec(b.copyOfRange(2, 2 + n))))
            }
        }
        val kp = P256.newKeyPair(); val priv = kp.private.encoded
        put(c, name, byteArrayOf((priv.size ushr 8).toByte(), priv.size.toByte()) + priv + kp.public.encoded)
        return kp
    }

    /** This computer's encryption key in group [groupId] (HPKE recipient key), made the first time. */
    fun encryptionKey(c: Context, groupId: String): KeyPair = pair(c, "enc.$groupId")

    /** Deletes an encryption key for good: what was sealed to it can't be opened any more. */
    fun forgetEncryptionKey(c: Context, groupId: String) = put(c, "enc.$groupId", null)
}

/** This computer's member keys, one per group (spec "Member ids"). [tag] identifies the group without revealing its key. */
object MemberKeys {
    private val cache = HashMap<String, KeyPair>()
    @Synchronized fun forGroup(tag: String): KeyPair = Home.context().let { c -> cache.getOrPut("${c.filesDir}|$tag") { Vault.pair(c, "member.$tag") } }

    /** "Erase everything": no member key of data folder [dir] is held in memory any more. */
    @Synchronized internal fun forgetAll(dir: File) { cache.keys.removeAll { it.startsWith("$dir|") } }

    /** The group [tag] was left: this computer's member key there is deleted. Joining again makes a new one. */
    @Synchronized fun forget(tag: String) = Home.context().let { c -> cache.remove("${c.filesDir}|$tag"); Vault.put(c, "member.$tag", null) }
}
