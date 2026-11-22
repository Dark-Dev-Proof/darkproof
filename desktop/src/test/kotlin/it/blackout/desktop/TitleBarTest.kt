package it.blackout.desktop

import androidx.compose.ui.awt.ComposeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT
import javax.swing.SwingUtilities

class TitleBarTest {
    /** This Java calls a function of the system the way the title bar code does; on Windows, one from Windows' own folder. */
    @Test fun thisJavaCallsIntoTheSystem() {
        Arena.ofConfined().use { arena ->
            val linker = Linker.nativeLinker()
            val pid = if (TitleBar.onWindows) linker.downcallHandle(TitleBar.library("kernel32.dll", arena).find("GetCurrentProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
            else linker.downcallHandle(linker.defaultLookup().find("getpid").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
            assertEquals(ProcessHandle.current().pid(), (pid.invoke() as Int).toLong())
        }
    }

    @Test fun aWindowThatIsNotThereIsLeftAlone() {
        val answer = TitleBar.darken(0, 0x000000, 0x2B2B2B)
        assertFalse(answer.isDark); assertTrue(answer.toString(), answer.error != null)
    }

    @Test fun windowsWantsAColourBlueFirst() {
        assertEquals(0x332211, TitleBar.bgr(0x112233)); assertEquals(0, TitleBar.bgr(0)); assertEquals(0x2B2B2B, TitleBar.bgr(0x2B2B2B))
    }

    @Test fun anAnswerSaysWhatWindowsDid() {
        assertEquals("dark: done; the window's colour: refused (0x80070057); edge: not asked", TitleBar.Answer(dark = 0, colour = 0x80070057.toInt()).toString())
        assertTrue(TitleBar.Answer(dark = 0).isDark); assertFalse(TitleBar.Answer(dark = 5).isDark); assertFalse(TitleBar.Answer().isDark)
    }

    /** On Windows: the app's window, made but not opened, takes the dark bar. This is the order the app asks in. */
    @Test fun onWindowsAWindowTakesTheDarkBarBeforeItOpens() {
        assumeTrue("only Windows draws this bar", TitleBar.onWindows)
        var answer = TitleBar.Answer(error = "never asked"); var handle = 0L
        SwingUtilities.invokeAndWait {
            val window = ComposeWindow()
            try { window.setSize(600, 400); window.addNotify(); handle = window.windowHandle; answer = TitleBar.darken(handle, 0x000000, 0x2B2B2B) } finally { window.dispose() }
        }
        println("title bar before opening, on ${System.getProperty("os.name")} ${System.getProperty("os.version")}: handle ${if (handle == 0L) "none" else "there"}, $answer")
        assertTrue("Windows ${System.getProperty("os.version")} answered: $answer", answer.isDark)
    }

    /** The picture check of the window test tells a light bar from a dark one, and an empty screen from a window. */
    @Test fun aPictureTellsADarkBarFromALightOne() {
        fun window(bar: Color, writing: Boolean) = BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB).also { img ->
            val g = img.createGraphics()
            g.color = Color.BLACK; g.fillRect(0, 0, 800, 500); g.color = bar; g.fillRect(0, 0, 800, 31)
            g.color = Color.WHITE; g.fillRect(8, 9, 70, 12) // the window's name at the left of the bar
            if (writing) g.fillRect(100, 200, 300, 40)
            g.dispose()
        }
        assertTrue(WindowTest.caption(window(Color.WHITE, true), 31) > 240)
        assertTrue(WindowTest.caption(window(Color(0xF3, 0xF3, 0xF3), true), 31) > WindowTest.DARK)
        assertEquals(0, WindowTest.caption(window(Color.BLACK, true), 31))
        assertTrue(WindowTest.caption(window(Color(0x20, 0x20, 0x20), true), 31) < WindowTest.DARK)
        assertEquals(0, WindowTest.caption(window(Color.BLACK, true), 0)) // a bar of unknown height: the top of the picture
        assertTrue(WindowTest.bright(window(Color.BLACK, true)) > 10_000)
        assertTrue(WindowTest.bright(window(Color.BLACK, false)) < 1_000) // only the name: fewer than a window's worth of bright dots
        assertEquals(0, WindowTest.bright(BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB)))
    }
}
