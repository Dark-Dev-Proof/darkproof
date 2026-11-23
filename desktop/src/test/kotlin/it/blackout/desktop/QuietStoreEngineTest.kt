package it.blackout.desktop

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.blackout.mesh.Groups
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.QuietAlert
import it.blackout.mesh.QuietBook
import it.blackout.mesh.QuietOut
import it.blackout.mesh.QuietSeal
import it.blackout.mesh.Spot
import it.blackout.mesh.StoreTooNew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The engine's store after the final review of 2026-11-23, on the real engine (the phones' own MeshEngine):
 * - decision C: a group's check-in clocks are stored sealed in table q (no position or member id readable in the file),
 *   open again at the next start, and a row a version before 9 saved readable is sealed once and its readable copy emptied;
 * - the store's step from version 8 to 9, and a store from a newer DarkProof refused with words that say so;
 * - a group left with no engine running can be joined again in the same run of the app (`leftCold`);
 * - the alarm's receiver hands over to a running engine.
 * The sealing itself and the alarm's choices are tested as plain Kotlin (StoredDataFixTest).
 */
class QuietStoreEngineTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MeshEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(MeshEngine) as T
    private fun keys(code: String): Any = field<HashMap<String, Any>>("keyCache")[code]!!
    private fun call(k: Any, name: String): Any? = k.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(k)
    private fun historyKey(): ByteArray = MeshEngine::class.java.getDeclaredMethod("getHistoryKey").apply { isAccessible = true }.invoke(MeshEngine) as ByteArray
    private fun db(): SQLiteDatabase = field("db")
    private fun row(group: String): Pair<String?, ByteArray?>? =
        db().rawQuery("SELECT data, box FROM q WHERE grp = ?", arrayOf(group)).use { q -> if (q.moveToNext()) q.getString(0) to q.getBlob(1) else null }
    private fun dir(): File = Files.createTempDirectory("darkproof-store").toFile().also { it.deleteOnExit() }
    private fun storeHelper(c: Context): SQLiteOpenHelper =
        Class.forName("it.blackout.mesh.Db").getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(c) as SQLiteOpenHelper

    @Test fun aGroupsClocksAreStoredSealed() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        var code: String? = null
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, "Kept clocks"); s.tap("Create group")
                s.await("the new group in use") { Groups.current(c)?.name == "Kept clocks" && MeshEngine.currentCode == Groups.current(c)?.code }
                val g = Groups.current(c)!!.code; code = g; s.secrets += g
                s.await("its invite panel") { s.has("Invite to Kept clocks") }; s.tap("Close")
                s.await("this computer to be its admin") { MeshEngine.isAdmin(g, MeshEngine.deviceId) }
                val k = keys(g); val id = call(k, "getId") as String

                // This computer's own clock, with where it is.
                assertTrue(MeshEngine.sendClock(g, { it + 3_600_000L }, Spot(45.12345, 9.54321, 8)))
                RealEngine.await("the clock in table q") { row(id) != null }
                val (data, box) = row(id)!!
                assertNull("nothing readable in the row", data)
                val lines = QuietSeal.open(historyKey(), id, box!!)
                assertNotNull("it opens with the copies' key, for its group", lines)
                assertTrue(lines.toString(), lines!!.any { it.startsWith("c ") && "45.12345" in it && "9.54321" in it })
                assertNull("bound to its group", QuietSeal.open(historyKey(), mine.code, box))
                // Nowhere in the store's files: the clock message and this computer's copy of it are sealed too.
                for (f in RealEngine.dir.listFiles()!!.filter { it.name.startsWith("mesh.db") })
                    assertFalse("${f.name} holds the position", String(f.readBytes(), Charsets.ISO_8859_1).contains("45.12345"))

                // As at the next start: the book is read from the sealed row again.
                val book = call(k, "getQuietBook") as QuietBook
                synchronized(book) { book.clocks.clear(); book.checkIns.clear(); book.posted.clear() }
                MeshEngine::class.java.declaredMethods.first { it.name == "loadQuiet" }.apply { isAccessible = true }.invoke(MeshEngine, k)
                val me = call(k, "getMe") as String
                assertEquals(45.12345, synchronized(book) { book.clock(me) }!!.spot!!.lat, 1e-9)

                assertTrue(MeshEngine.sendClock(g, { 0L }, null)) // stopped again
            } finally {
                code?.let { left -> val done = CountDownLatch(1); MeshEngine.leave(c, left) { done.countDown() }; done.await(30, TimeUnit.SECONDS) }
                Groups.current(c).let { now -> if (now?.code != mine.code) a.switchTo(mine) }
                a.state.group = Groups.current(c); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }

    @Test fun aRowSavedReadableBeforeIsSealedOnce() {
        RealEngine.started()
        val group = "0123456789abcdef0123456789abcdef" // no group of this computer's: the row alone is looked at
        val now = System.currentTimeMillis()
        val lines = QuietBook().apply { take("2222222222222222", "e608000000000101", "00000000000000aa", now, now + 3_600_000L, Spot(45.46420, 9.19000, 12), now, false) }.saved
        try {
            // As version 8 wrote it: readable.
            db().insertWithOnConflict("q", null, ContentValues().apply { put("grp", group); put("data", lines.joinToString("\n")) }, SQLiteDatabase.CONFLICT_REPLACE)
            assertEquals(lines.joinToString("\n"), row(group)!!.first)
            MeshEngine.sealPlainClocks()
            val (data, box) = row(group)!!
            assertNull("its readable copy is gone", data)
            assertEquals(lines, QuietSeal.open(historyKey(), group, box!!))
            MeshEngine.sealPlainClocks() // once: nothing more to do
            assertEquals(lines, QuietSeal.open(historyKey(), group, row(group)!!.second!!))
        } finally { db().delete("q", "grp = ?", arrayOf(group)) }
    }

    @Test fun theStoreGoesFrom8To9AndANewerOneIsRefused() {
        // A store as version 8 left it, with a clock row saved readable.
        val c = Context(dir())
        object : SQLiteOpenHelper(c, "mesh.db", null, 8) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE p(id TEXT PRIMARY KEY, ts INTEGER, ttl INTEGER, cipher BLOB, at INTEGER DEFAULT 0)")
                d.execSQL("CREATE TABLE k(grp TEXT, id TEXT, pub BLOB, seen INTEGER, enc BLOB, encAt INTEGER DEFAULT 0, pq BLOB, PRIMARY KEY(grp, id))")
                d.execSQL("CREATE TABLE h(id TEXT PRIMARY KEY, data BLOB)")
                d.execSQL("CREATE TABLE g(key TEXT PRIMARY KEY, ts INTEGER, grp TEXT)")
                d.execSQL("CREATE TABLE q(grp TEXT PRIMARY KEY, data TEXT)")
                d.insertWithOnConflict("q", null, ContentValues().apply { put("grp", "aa"); put("data", "i 2222222222222222 5") }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) {}
        }.also { it.writableDatabase; it.close() }
        storeHelper(c).also { h ->
            val d = h.writableDatabase
            assertEquals(9, d.version)
            d.rawQuery("SELECT grp, data, box FROM q", null).use { q -> assertTrue(q.moveToNext()); assertEquals("i 2222222222222222 5", q.getString(1)); assertTrue(q.isNull(2)) }
            h.close()
        }
        // A store a newer DarkProof wrote: refused, with words that say so, and left as it is.
        val newer = Context(dir())
        object : SQLiteOpenHelper(newer, "mesh.db", null, 10) {
            override fun onCreate(d: SQLiteDatabase) { d.execSQL("CREATE TABLE p(id TEXT PRIMARY KEY, ts INTEGER, ttl INTEGER, cipher BLOB)") }
            override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) {}
        }.also { it.writableDatabase; it.close() }
        try { storeHelper(newer).writableDatabase; fail("a store from a newer version was opened") }
        catch (e: StoreTooNew) { assertTrue(e.message, e.message!!.contains("newer version of DarkProof") && e.message!!.contains("store 10, this version reads 9")) }
        object : SQLiteOpenHelper(newer, "mesh.db", null, 10) {
            override fun onCreate(d: SQLiteDatabase) = fail("made again")
            override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) = fail("changed")
        }.also { assertEquals(10, it.writableDatabase.version); it.close() }
    }

    @Test fun aGroupLeftWithNoEngineCanBeJoinedAgain() {
        RealEngine.started(); val c = RealEngine.activity
        val blocks = MeshEngine::class.java.getDeclaredMethod("leftColdBlocks", Context::class.java, String::class.java).apply { isAccessible = true }
        val leftCold = field<HashSet<String>>("leftCold")
        val saved = Groups.all(c).first { it.name == "My group" }.code
        try {
            synchronized(MeshEngine) { leftCold += "not-saved-any-more" }
            assertEquals("a start still on its way with a group just left does nothing", true, blocks.invoke(MeshEngine, c, "not-saved-any-more"))
            assertTrue(synchronized(MeshEngine) { "not-saved-any-more" in leftCold })
            synchronized(MeshEngine) { leftCold += saved }
            assertEquals("saved again (joined anew): the start goes on", false, blocks.invoke(MeshEngine, c, saved))
            assertFalse("and the entry is gone", synchronized(MeshEngine) { saved in leftCold })
            assertEquals(false, blocks.invoke(MeshEngine, c, saved))
        } finally { synchronized(MeshEngine) { leftCold -= "not-saved-any-more"; leftCold -= saved } }
    }

    @Test fun theAlarmHandsOverToARunningEngine() {
        RealEngine.started()
        val posted = CopyOnWriteArrayList<QuietAlert>()
        val out = object : QuietOut {
            override fun post(a: QuietAlert) { posted += a }
            override fun take(ids: Collection<String>) {}
            override fun arm(coming: List<QuietAlert>) {}
        }
        val due = QuietAlert("quiet.0000000000000000", "00000000000000aa", 1L, "t", "b", "bt", "bb", "CODE", remind = false, safe = false)
        assertEquals("the engine's own pass decides and arms", emptyList<QuietAlert>(), MeshEngine.quietAlarmRang(RealEngine.activity, listOf(due), out))
        assertTrue(posted.isEmpty())
    }
}
