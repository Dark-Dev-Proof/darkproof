package it.blackout.mesh

/**
 * The MeshCore companion-radio protocol, as far as DarkProof uses it (spec/FORMAT.md, "LoRa via MeshCore"). Pure
 * functions, so unit tests run; [MeshCoreLink] does the Bluetooth part.
 */
object MeshCore {
    const val PAYLOAD_MAX = 163           // channel data that every firmware from 1.15 delivers
    const val MIN_FIRMWARE = 11            // FIRMWARE_VER_CODE of 1.15, the first with channel data
    const val DATA_TYPE = 0xFF44           // development range FF00-FFFF
    const val CHANNEL_NAME = "#darkproof"
    /** First 16 bytes of SHA-256("#darkproof"), as MeshCore derives hashtag channel keys. */
    val CHANNEL_KEY: ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(CHANNEL_NAME.toByteArray()).copyOf(16)

    const val OK = 0x00; const val ERR = 0x01; const val SELF_INFO = 0x05; const val NO_MORE = 0x0A
    const val DEVICE_INFO = 0x0D; const val CHANNEL_INFO = 0x12; const val CHANNEL_DATA = 0x1B; const val MSG_WAITING = 0x83

    fun fits(p: Packet) = p.encode().size <= PAYLOAD_MAX

    fun deviceQuery() = byteArrayOf(0x16, 0x03)
    fun appStart() = byteArrayOf(0x01) + ByteArray(7) + "DarkProof".toByteArray()
    fun getChannel(idx: Int) = byteArrayOf(0x1F, idx.toByte())
    fun setChannel(idx: Int) = byteArrayOf(0x20, idx.toByte()) + CHANNEL_NAME.toByteArray().copyOf(32) + CHANNEL_KEY
    fun syncNext() = byteArrayOf(0x0A)
    fun sendData(idx: Int, packet: ByteArray): ByteArray {
        require(packet.size <= PAYLOAD_MAX)
        return byteArrayOf(0x3E, idx.toByte(), 0xFF.toByte(), (DATA_TYPE and 0xFF).toByte(), (DATA_TYPE shr 8).toByte()) + packet
    }

    /** Replies have codes below 0x80; pushes (sent by the radio on its own) at 0x80 and above. */
    fun isPush(f: ByteArray) = f.isNotEmpty() && (f[0].toInt() and 0xFF) >= 0x80

    class DeviceInfo(val firmware: Int, val channels: Int)
    fun deviceInfo(f: ByteArray): DeviceInfo? =
        if (f.size >= 4 && f[0].toInt() == DEVICE_INFO) DeviceInfo(f[1].toInt() and 0xFF, f[3].toInt() and 0xFF) else null

    /** A channel slot: null if the frame isn't one. [isOurs] when it holds the DarkProof key; [isEmpty] when unused. */
    class Slot(val idx: Int, val name: String, val key: ByteArray) {
        val isOurs get() = key.contentEquals(CHANNEL_KEY)
        val isEmpty get() = name.isEmpty() && key.all { it.toInt() == 0 }
    }
    fun slot(f: ByteArray): Slot? {
        if (f.size < 2 + 32 + 16 || f[0].toInt() != CHANNEL_INFO) return null
        val nameBytes = f.copyOfRange(2, 34).takeWhile { it.toInt() != 0 }.toByteArray()
        return Slot(f[1].toInt() and 0xFF, String(nameBytes, Charsets.UTF_8), f.copyOfRange(34, 50))
    }

    /** The DarkProof packet in a channel-data frame on [ourSlot], or null for anything else. */
    fun packet(f: ByteArray, ourSlot: Int): ByteArray? {
        if (f.size < 9 || f[0].toInt() != CHANNEL_DATA || (f[4].toInt() and 0xFF) != ourSlot) return null
        val type = (f[6].toInt() and 0xFF) or ((f[7].toInt() and 0xFF) shl 8)
        val len = f[8].toInt() and 0xFF
        if (type != DATA_TYPE || f.size < 9 + len) return null
        return f.copyOfRange(9, 9 + len)
    }
}
