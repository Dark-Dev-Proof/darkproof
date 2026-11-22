package it.blackout.desktop

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import it.blackout.mesh.MemberKeys
import it.blackout.mesh.P256
import it.blackout.mesh.Vault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The desktop's stand-ins for the phone's database and key store. */
class StoreStandInTest {
    private fun dir(): File = Files.createTempDirectory("darkproof-test").toFile().also { it.deleteOnExit() }
    /** A copy of the saved files in a new folder: opening it reads everything from disk, as a new start of the app does. */
    private fun restarted(d: File): File = dir().also { n -> d.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(n, it.name)) } }

    /** The same shape as the engine's own helper. */
    private class Db(c: Context, v: Int) : SQLiteOpenHelper(c, "t.db", null, v) {
        var created = 0; val upgrades = ArrayList<Pair<Int, Int>>()
        override fun onCreate(d: SQLiteDatabase) { created++; d.execSQL("CREATE TABLE p(id TEXT PRIMARY KEY, ts INTEGER, ttl INTEGER, cipher BLOB)") }
        override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) { upgrades += a to b; d.execSQL("CREATE TABLE k(grp TEXT, id TEXT, pub BLOB, seen INTEGER, enc BLOB, PRIMARY KEY(grp, id))") }
    }
    private fun row(id: String, ts: Long, ttl: Int, cipher: ByteArray) = ContentValues().apply { put("id", id); put("ts", ts); put("ttl", ttl); put("cipher", cipher) }
    private fun SQLiteDatabase.ids() = rawQuery("SELECT id FROM p ORDER BY ts", null).use { q -> buildList { while (q.moveToNext()) add(q.getString(0)) } }

    @Test fun tablesAreMadeOnceAndAnOlderFileIsBroughtUp() {
        val c = Context(dir())
        Db(c, 1).let { h -> h.writableDatabase.insertWithOnConflict("p", null, row("a", 1, 16, byteArrayOf(1)), SQLiteDatabase.CONFLICT_REPLACE); assertEquals(1, h.created); h.close() }
        Db(c, 1).let { h -> assertEquals(listOf("a"), h.writableDatabase.ids()); assertEquals(0, h.created); assertTrue(h.upgrades.isEmpty()); h.close() }
        Db(c, 3).let { h -> val d = h.writableDatabase; assertEquals(listOf(1 to 3), h.upgrades); assertEquals(3, d.version); assertEquals(listOf("a"), d.ids()); d.execSQL("DELETE FROM k"); h.close() }
        try { Db(c, 2).writableDatabase; fail("a file from a newer version was opened") } catch (_: SQLiteException) { }
    }

    @Test fun rowsComeBackAsTheyWentIn() {
        val h = Db(Context(dir()), 1); val d = h.writableDatabase
        val blob = ByteArray(16_000) { (it * 7).toByte() }
        assertTrue(d.insertWithOnConflict("p", null, row("0001", 1_763_650_000_123L, 16, blob), SQLiteDatabase.CONFLICT_REPLACE) > 0)
        d.insertWithOnConflict("p", null, ContentValues().apply { put("id", "0002"); put("ts", 5L) }, SQLiteDatabase.CONFLICT_REPLACE)
        d.rawQuery("SELECT id, ts, ttl, cipher FROM p WHERE id = ?", arrayOf("0001")).use { q ->
            assertEquals(1, q.count); assertTrue(q.moveToNext())
            assertEquals("0001", q.getString(0)); assertEquals(1_763_650_000_123L, q.getLong(1)); assertEquals(16, q.getInt(2)); assertArrayEquals(blob, q.getBlob(3)); assertFalse(q.isNull(3))
            assertFalse(q.moveToNext())
        }
        d.rawQuery("SELECT ttl, cipher FROM p WHERE id = ?", arrayOf("0002")).use { q -> assertTrue(q.moveToNext()); assertTrue(q.isNull(0)); assertTrue(q.isNull(1)) }
        d.rawQuery("SELECT id FROM p WHERE id = ?", arrayOf("nobody")).use { q -> assertFalse(q.moveToNext()) }
        h.close()
    }

    @Test fun aRowThatIsThereIsReplacedOrLeftAsAsked() {
        val h = Db(Context(dir()), 1); val d = h.writableDatabase
        d.insertWithOnConflict("p", null, row("a", 1, 3, byteArrayOf(1)), SQLiteDatabase.CONFLICT_IGNORE)
        assertEquals(-1L, d.insertWithOnConflict("p", null, row("a", 2, 9, byteArrayOf(2)), SQLiteDatabase.CONFLICT_IGNORE))
        d.rawQuery("SELECT ttl FROM p WHERE id = ?", arrayOf("a")).use { q -> q.moveToNext(); assertEquals(3, q.getInt(0)) }
        d.insertWithOnConflict("p", null, row("a", 2, 9, byteArrayOf(2)), SQLiteDatabase.CONFLICT_REPLACE)
        d.rawQuery("SELECT ttl FROM p WHERE id = ?", arrayOf("a")).use { q -> q.moveToNext(); assertEquals(9, q.getInt(0)) }
        h.close()
    }

    @Test fun theEnginesOwnStatementsRun() {
        val h = Db(Context(dir()), 2); val d = h.writableDatabase; d.execSQL("CREATE TABLE k(grp TEXT, id TEXT, pub BLOB, seen INTEGER, enc BLOB, PRIMARY KEY(grp, id))")
        val cipher = byteArrayOf(9, 8, 7)
        d.insertWithOnConflict("p", null, row("a", 100, 3, cipher), SQLiteDatabase.CONFLICT_REPLACE)
        // raising a stored packet's ttl: numbers, text and a blob as arguments
        d.execSQL("UPDATE p SET ttl = ? WHERE (id = ? OR id = ?) AND ts = ? AND ttl < ? AND cipher = ?", arrayOf<Any>(12, "a", "other", 100L, 12, cipher))
        d.rawQuery("SELECT ttl FROM p WHERE id = ?", arrayOf("a")).use { q -> q.moveToNext(); assertEquals(12, q.getInt(0)) }
        // forgetting members' keys beyond the newest ones
        for (i in 1..5) d.insertWithOnConflict("k", null, ContentValues().apply { put("grp", "g"); put("id", "m$i"); put("pub", byteArrayOf(i.toByte())); put("seen", i.toLong()) }, SQLiteDatabase.CONFLICT_REPLACE)
        d.execSQL("DELETE FROM k WHERE grp = ? AND id NOT IN (?) AND id NOT IN (SELECT id FROM k WHERE grp = ? ORDER BY seen DESC LIMIT 2)", arrayOf<Any>("g", "m1", "g"))
        d.rawQuery("SELECT id FROM k WHERE grp = ? ORDER BY id", arrayOf("g")).use { q -> assertEquals(listOf("m1", "m4", "m5"), buildList { while (q.moveToNext()) add(q.getString(0)) }) }
        assertEquals(2, d.delete("k", "grp = ? AND seen >= ?", arrayOf("g", "4"))); assertEquals(1, d.delete("k", null, null))
        h.close()
    }

    @Test fun aTransactionIsKeptWholeOrNotAtAll() {
        val h = Db(Context(dir()), 1); val d = h.writableDatabase
        d.beginTransaction()
        try { d.insertWithOnConflict("p", null, row("gone", 1, 1, byteArrayOf(1)), SQLiteDatabase.CONFLICT_REPLACE) } finally { d.endTransaction() } // not marked successful
        assertEquals(emptyList<String>(), d.ids())
        d.beginTransaction()
        try { d.insertWithOnConflict("p", null, row("kept", 1, 1, byteArrayOf(1)), SQLiteDatabase.CONFLICT_REPLACE); d.setTransactionSuccessful() } finally { d.endTransaction() }
        assertEquals(listOf("kept"), d.ids())
        h.close()
    }

    @Test fun anotherThreadWaitsForATransactionToEnd() {
        val h = Db(Context(dir()), 1); val d = h.writableDatabase; val started = CountDownLatch(1); val done = CountDownLatch(1)
        d.beginTransaction()
        val other = thread { started.countDown(); d.insertWithOnConflict("p", null, row("b", 2, 1, byteArrayOf(2)), SQLiteDatabase.CONFLICT_REPLACE); done.countDown() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS)); assertFalse("the other thread wrote inside the transaction", done.await(200, TimeUnit.MILLISECONDS))
            d.insertWithOnConflict("p", null, row("a", 1, 1, byteArrayOf(1)), SQLiteDatabase.CONFLICT_REPLACE); d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        assertTrue(done.await(5, TimeUnit.SECONDS)); other.join()
        assertEquals(listOf("a", "b"), d.ids())
        h.close()
    }

    @Test fun badSqlIsTheDatabasesOwnException() {
        val h = Db(Context(dir()), 1); val d = h.writableDatabase
        try { d.execSQL("DELETE FROM nothing"); fail("no error") } catch (_: SQLiteException) { }
        try { d.rawQuery("SELECT nothing FROM p", null); fail("no error") } catch (_: SQLiteException) { }
        assertEquals(emptyList<String>(), d.ids()) // and it still works afterwards
        h.close()
    }

    @Test fun theVaultGivesBackWhatItWasGivenAlsoAfterARestart() {
        val d = dir(); val secret = ByteArray(64) { it.toByte() }
        Context(d).let { c -> Vault.put(c, "groups", secret); assertArrayEquals(secret, Vault.get(c, "groups")); assertNull(Vault.get(c, "nothing")); c.getSharedPreferences("vault", 0).edit().commit() }
        val c = Context(restarted(d)) // a new start: the key and the values come from the files
        assertArrayEquals(secret, Vault.get(c, "groups"))
        val saved = File(d, "vault.json").readText()
        assertFalse("the secret is readable in the file", java.util.Base64.getEncoder().encodeToString(secret).take(24) in saved)
        Vault.put(c, "groups", null); assertNull(Vault.get(c, "groups"))
        assertEquals(32L, File(d, "vault.key").length())
        assertNull("another folder's key opened it", Context(dir()).let { other -> other.getSharedPreferences("vault", 0).edit().putString("x", c.getSharedPreferences("vault", 0).getString("y", "AAAA")).commit(); Vault.get(other, "x") })
    }

    @Test fun keyPairsStayTheSameAndSign() {
        val d = dir(); val c = Context(d)
        val enc = Vault.encryptionKey(c, "g1"); c.getSharedPreferences("vault", 0).edit().commit()
        assertArrayEquals(enc.public.encoded, Vault.encryptionKey(Context(restarted(d)), "g1").public.encoded)
        assertNotEquals(enc.public, Vault.encryptionKey(c, "g2").public)
        Vault.forgetEncryptionKey(c, "g1"); assertFalse(enc.public.encoded.contentEquals(Vault.encryptionKey(c, "g1").public.encoded))

        val before = Home.current() // the engine other tests share keeps its keys through Home: it must get its own folder back
        try {
            Home.use(d)
            val m = MemberKeys.forGroup("tag1"); assertTrue(m === MemberKeys.forGroup("tag1")); assertNotEquals(m.public, MemberKeys.forGroup("tag2").public)
            val msg = "signed on a desktop".toByteArray()
            assertTrue(P256.verify(m.public, java.security.MessageDigest.getInstance("SHA-256").digest(msg), P256.sign(m.private, msg)))
        } finally { if (before != null) Home.set(before) }
    }

    @Test fun aDamagedVaultKeyIsNotReplacedSilently() {
        val d = dir(); File(d, "vault.key").writeBytes(ByteArray(7))
        try { Vault.put(Context(d), "x", byteArrayOf(1)); fail("a damaged key file was replaced") } catch (_: IllegalStateException) { }
        assertEquals(7L, File(d, "vault.key").length())
    }
}
