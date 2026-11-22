package it.blackout.mesh

import java.security.KeyPair

/**
 * This phone's encryption keys in one group (spec "Sealing for one member"): the newest goes in its introductions, and
 * every key still kept can open what was sealed to it. [EncSchedule] says when one changes. Any thread.
 */
class EncKeys(private val c: android.content.Context, private val groupId: String) {
    private val prefs = c.getSharedPreferences("mesh", 0)
    private var list = EncSchedule.parse(prefs.getString("encs.$groupId", "") ?: "").ifEmpty {
        listOf(EncSchedule.Entry(0, System.currentTimeMillis())).also(::save)
    }
    private val pairs = HashMap<Int, KeyPair>()
    private val pqs = HashMap<Int, Pair<ByteArray, ByteArray>>() // n -> ML-KEM (public, private)

    private fun pq(n: Int) = pqs.getOrPut(n) {
        val b = Vault.get(c, "mlkem.${name(n)}")
        if (b != null && b.size > MlKem.PUBLIC_SIZE) b.copyOf(MlKem.PUBLIC_SIZE) to b.copyOfRange(MlKem.PUBLIC_SIZE, b.size)
        else MlKem.newKey().also { (pub, priv) -> Vault.put(c, "mlkem.${name(n)}", pub + priv) }
    }
    private fun save(l: List<EncSchedule.Entry>) { prefs.edit().putString("encs.$groupId", EncSchedule.save(l)).apply() }
    private fun name(n: Int) = if (n == 0) groupId else "$groupId.$n" // the first key keeps the name older builds gave it
    private fun pair(n: Int) = pairs.getOrPut(n) { Vault.encryptionKey(c, name(n)) }

    /** The newest key, as it goes in an introduction. */
    @Synchronized fun currentPub(): ByteArray = P256.uncompressed(pair(list.last().n).public)

    /** The newest key's ML-KEM half, as it goes in an introduction. */
    @Synchronized fun currentPq(): ByteArray = pq(list.last().n).first

    /** Deletes keys that were replaced long enough ago, and makes a new one when the current one is a week old. True if it did. */
    @Synchronized fun rotateIfDue(now: Long): Boolean {
        val gone = EncSchedule.expired(list, now)
        if (gone.isNotEmpty()) {
            gone.forEach { Vault.forgetEncryptionKey(c, name(it.n)); Vault.put(c, "mlkem.${name(it.n)}", null); pairs.remove(it.n); pqs.remove(it.n) }
            list = list - gone.toSet(); save(list)
            DebugTools.log("deleted ${gone.size} old encryption key(s)")
        }
        if (!EncSchedule.due(list, now)) return false
        list = EncSchedule.rotated(list, now); pair(list.last().n); save(list)
        DebugTools.log("new encryption key ${list.last().n}")
        return true
    }

    /** What was sealed to any key still kept, newest first; null if none opens it. */
    @Synchronized fun open(box: ByteArray, info: ByteArray, aad: ByteArray): ByteArray? =
        list.asReversed().firstNotNullOfOrNull { Hybrid.open(box, pair(it.n), pq(it.n).second, info, aad) }
}
