package it.blackout.desktop

import it.blackout.mesh.KeyProtect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * KeyProtect holds the desktop Vault's master key. The plain path (what tests and older installs use) is checked on
 * every computer; the secured path through the operating system's key store is checked only on this macOS machine, and
 * cleans up after itself. Windows (DPAPI) and Linux (Secret Service) are covered by reading, as the README records.
 */
class VaultProtectTest {
    private fun tmp() = Files.createTempDirectory("darkproof-keyprotect").toFile().also { it.deleteOnExit() }
    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) { for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer; return true }
        return false
    }

    @Test fun plainPathRoundTripsAndKeepsAThirtyTwoByteFile() {
        val dir = tmp(); val f = File(dir, "vault.key")
        try {
            KeyProtect.secureStore = false
            val raw = ByteArray(32) { (it * 7 + 1).toByte() }
            KeyProtect.write(dir, f, raw)
            assertEquals("the plain key file is 32 bytes, as before", 32L, f.length())
            assertArrayEquals(raw, KeyProtect.read(dir, f))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aDamagedFileThrowsAndIsNotReplaced() {
        val dir = tmp(); val f = File(dir, "vault.key"); f.writeBytes(ByteArray(7))
        try { KeyProtect.read(dir, f); fail("a damaged key file was read") } catch (_: IllegalStateException) { }
        assertEquals(7L, f.length())
        dir.deleteRecursively()
    }

    @Test fun anAbsentFileReadsAsNull() {
        val dir = tmp()
        assertEquals(null, KeyProtect.read(dir, File(dir, "vault.key")))
        dir.deleteRecursively()
    }

    @Test fun theSecuredKeyIsNotReadableFromTheFolderAlone() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return // verified on macOS; Windows and Linux by reading
        val dir = tmp(); val f = File(dir, "vault.key")
        try {
            KeyProtect.secureStore = true
            val raw = ByteArray(32) { (255 - it).toByte() }
            KeyProtect.write(dir, f, raw)
            val blob = f.readBytes()
            assertTrue("the secured file is a marker, not the 32-byte key", blob.size != 32)
            assertFalse("the real key must not sit in the folder", contains(blob, raw))
            assertArrayEquals("it reads back from the Keychain", raw, KeyProtect.read(dir, f))
        } finally {
            KeyProtect.clear(dir)
            KeyProtect.secureStore = false
            dir.deleteRecursively()
        }
    }
}
