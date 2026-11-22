package it.blackout.desktop

import android.content.Context
import it.blackout.mesh.Vault
import it.blackout.mesh.Wipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** "Erase everything" on a computer: the data folder goes, and nothing held in memory is written back. */
class WipeTest {
    private fun dir(): File = Files.createTempDirectory("darkproof-wipe").toFile().also { it.deleteOnExit() }

    @Test fun theWholeFolderGoes() {
        val d = dir(); val c = Context(d)
        c.getSharedPreferences("mesh", 0).edit().putString("name", "Anna").putBoolean("highRisk", true).commit()
        Vault.put(c, "groups", "a group code".toByteArray())                      // makes vault.json and vault.key
        File(d, "mesh.db").writeBytes(ByteArray(4096)); File(d, "models").mkdirs(); File(d, "models/model.gguf").writeBytes(ByteArray(10_000))
        File(d, "tor").mkdirs(); File(d, "tor/state").writeText("guards"); File(d, "ai-conversation.json").writeText("[]")
        assertTrue(File(d, "vault.key").isFile && File(d, "vault.json").isFile && File(d, "mesh.json").isFile)

        assertEquals(emptyList<String>(), Wipe.everything(d))
        assertFalse("the data folder is still there", d.exists())
    }

    @Test fun nothingInMemoryIsWrittenBack() {
        val d = dir(); val c = Context(d)
        val old = c.getSharedPreferences("mesh", 0)
        old.edit().putString("name", "Anna").commit()
        Wipe.everything(d)
        // A part of the app that still holds the old preferences and writes to them brings nothing back: the folder is gone.
        old.edit().putString("late", "x").commit()
        assertFalse(d.exists())
        // Opened again, as a new start of the app does: empty, like a new install.
        val fresh = Context(d).getSharedPreferences("mesh", 0)
        assertNull(fresh.getString("name", null)); assertNull(fresh.getString("late", null))
        assertNull(Vault.get(Context(d), "groups"))
    }
}
