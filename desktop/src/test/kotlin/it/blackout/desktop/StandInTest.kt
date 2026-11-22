package it.blackout.desktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The desktop's stand-ins for the Android classes the phone's engine calls. */
class StandInTest {
    private fun dir(): File = Files.createTempDirectory("darkproof-test").toFile().also { it.deleteOnExit() }
    /** A copy of the saved files in a new folder: opening it reads everything from disk, as a new start of the app does. */
    private fun restarted(d: File): File = dir().also { n -> d.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(n, it.name)) } }

    @Test fun preferencesAreReadBackAfterARestart() {
        val d = dir()
        Context(d).getSharedPreferences("mesh", 0).edit()
            .putString("name", "Anna").putLong("introAt", 1_763_650_000_000L).putInt("n", 7).putBoolean("tor", false)
            .putStringSet("reports", setOf("a", "b")).commit()
        val p = Context(restarted(d)).getSharedPreferences("mesh", 0) // a new start: read from the file
        assertEquals("Anna", p.getString("name", null)); assertEquals(1_763_650_000_000L, p.getLong("introAt", 0))
        assertEquals(7, p.getInt("n", 0)); assertFalse(p.getBoolean("tor", true)); assertEquals(setOf("a", "b"), p.getStringSet("reports", emptySet()))
        assertTrue(p.contains("name")); assertFalse(p.contains("nothing"))
        assertNull(p.getString("nothing", null)); assertEquals("x", p.getString("nothing", "x")); assertTrue(p.getBoolean("nothing", true))
        assertEquals(setOf("name", "introAt", "n", "tor", "reports"), p.all.keys)
    }

    @Test fun removeClearAndNullAllTakeAValueAway() {
        val d = dir(); val p = Context(d).getSharedPreferences("mesh", 0)
        p.edit().putString("a", "1").putString("b", "2").putString("c", "3").commit()
        p.edit().remove("a").putString("b", null).commit()
        assertEquals(setOf("c"), p.all.keys); assertEquals(setOf("c"), Context(restarted(d)).getSharedPreferences("mesh", 0).all.keys)
        p.edit().putString("d", "4").clear().commit() // as on Android: the clear comes first
        assertEquals(setOf("d"), p.all.keys); assertEquals(setOf("d"), Context(restarted(d)).getSharedPreferences("mesh", 0).all.keys)
    }

    @Test fun applyIsSeenAtOnceAndOneNameIsOneObject() {
        val c = Context(dir()); val p = c.getSharedPreferences("mesh", 0)
        p.edit().putString("k", "v").apply()
        assertEquals("v", p.getString("k", null))
        assertTrue(p === c.getSharedPreferences("mesh", 0)); assertFalse(p === c.getSharedPreferences("vault", 0))
        assertTrue("two Contexts on one folder must share one object, or they overwrite each other", p === Context(c.filesDir).getSharedPreferences("mesh", 0))
        assertTrue(c.applicationContext === c)
    }

    @Test fun whatASetHandsOutCanBeChangedWithoutChangingWhatIsSaved() {
        val p = Context(dir()).getSharedPreferences("mesh", 0)
        p.edit().putStringSet("s", setOf("a")).commit()
        (p.getStringSet("s", null) as MutableSet<String>).add("b")
        assertEquals(setOf("a"), p.getStringSet("s", null))
    }

    @Test fun theMainThreadRunsThingsInOrderAndOneAtATime() {
        val h = Handler(Looper.getMainLooper()); val seen = Collections.synchronizedList(ArrayList<String>()); val done = CountDownLatch(1)
        h.postDelayed({ seen += "late"; done.countDown() }, 120)
        repeat(50) { i -> h.post { seen += "$i" } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals((0 until 50).map { "$it" } + "late", seen.toList())
    }

    @Test fun whatWasTakenBackDoesNotRun() {
        val h = Handler(Looper.getMainLooper()); val seen = Collections.synchronizedList(ArrayList<String>()); val done = CountDownLatch(1)
        val taken = Runnable { seen += "taken back" }
        h.postDelayed(taken, 100); h.postDelayed(taken, 150); h.removeCallbacks(taken)
        val other = Handler(Looper.getMainLooper()); other.postDelayed({ seen += "all taken back" }, 100); other.removeCallbacksAndMessages(null)
        h.postDelayed({ seen += "kept"; done.countDown() }, 300)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("kept"), seen.toList())
    }

    @Test fun oneFailureOnTheMainThreadDoesNotStopTheNext() {
        val h = Handler(Looper.getMainLooper()); val done = CountDownLatch(1)
        val err = System.err; System.setErr(java.io.PrintStream(java.io.ByteArrayOutputStream())) // the failure is reported there; keep the test output clean
        try { h.post { error("on purpose") }; h.post { done.countDown() }; assertTrue(done.await(5, TimeUnit.SECONDS)) } finally { System.setErr(err) }
    }
}
