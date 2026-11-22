package it.blackout.desktop

import androidx.compose.ui.awt.ComposeWindow
import it.blackout.mesh.Invite
import it.blackout.mesh.MainActivity
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.SavedGroup
import it.blackout.mesh.Sheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * `DarkProof --window-test`: opens the app's real window the way it always opens, makes a group in an empty data
 * folder (set DARKPROOF_HOME to a throwaway one: a folder already in use is refused), photographs the screen where
 * the window is, and checks the picture: the window shows, and on Windows its title bar is dark. It is how a built
 * app is looked at on a computer nobody sits at. Left in the data folder: window-1-chat.png, window-2-settings.png
 * and window-test.txt. The result is the exit code: 0 good, 2 refused, 3 the title bar is not dark, 4 the screen
 * can't be photographed here, 1 anything else.
 */
internal object WindowTest {
    suspend fun run(window: ComposeWindow, a: MainActivity, bar: TitleBar.Answer, asked: String): Int {
        val out = StringBuilder()
        fun say(s: String) { out.appendLine(s); println(s) }
        val code = try {
            say("window test on ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
            say("title bar: $bar ($asked)")
            if (a.state.group != null) { say("REFUSED: this data folder is in use. Set DARKPROOF_HOME to an empty folder."); 2 } else {
                val group = Invite.newCode()
                MeshEngine.found(a, group); a.started("Desktop", SavedGroup("My group", group))
                await("the engine to start") { MeshEngine.router != null && !MeshEngine.joining(group) }
                withContext(Dispatchers.IO) { MeshEngine.send("Hello from a real window on ${System.getProperty("os.name")}") }
                await("the message to be kept") { MeshEngine.snapshot().any { it.sender == MeshEngine.deviceId } }
                window.toFront(); delay(3000) // time to draw
                val screen = window.graphicsConfiguration.bounds
                say("screen ${screen.width} x ${screen.height}; window ${window.width} x ${window.height} at ${window.x}, ${window.y}; title bar ${window.insets.top} high; drawn with ${window.renderApi}")
                val chat = photograph(window); ImageIO.write(chat, "png", File(Home.folder(), "window-1-chat.png"))
                a.state.sheet = Sheet.Settings; delay(1500)
                ImageIO.write(photograph(window), "png", File(Home.folder(), "window-2-settings.png"))
                a.state.sheet = null
                val lit = bright(chat); val barLight = caption(chat, window.insets.top)
                say("picture: $lit bright dots, title bar brightness $barLight of 255")
                when {
                    lit < 400 -> { say("NOT CHECKED: the picture shows no window, so this computer's screen can't be photographed"); 4 }
                    TitleBar.onWindows && !bar.isDark -> { say("FAILED: Windows did not accept the dark title bar"); 3 }
                    TitleBar.onWindows && barLight > DARK -> { say("FAILED: the title bar is not dark in the picture"); 3 }
                    else -> { say("WINDOW TEST OK"); 0 }
                }
            }
        } catch (t: Throwable) { say("FAILED: $t"); t.stackTrace.take(12).forEach { say("    at $it") }; 1 }
        runCatching { File(Home.folder(), "window-test.txt").writeText(out.toString()) }
        runCatching { MeshEngine.shutdown() }
        return code
    }

    /** A title bar this bright or less (0 to 255) is a dark one: Windows' dark bars are near black, its light ones near white. */
    const val DARK = 90

    private fun luma(rgb: Int) = (((rgb shr 16) and 255) * 299 + ((rgb shr 8) and 255) * 587 + (rgb and 255) * 114) / 1000

    /** How bright the title bar is in a picture of the window: its middle part, between the name at the left and the buttons at the right. */
    fun caption(picture: BufferedImage, barHeight: Int): Int {
        val bottom = (if (barHeight > 12) barHeight else 28).coerceAtMost(picture.height) - 4
        var sum = 0L; var n = 0
        for (y in 4 until bottom.coerceAtLeast(5)) for (x in picture.width * 2 / 5 until picture.width * 3 / 5) { sum += luma(picture.getRGB(x, y)); n++ }
        return if (n == 0) 255 else (sum / n).toInt()
    }

    /** How many bright dots a picture has: white writing and the logo, when the window shows; none on an empty screen. */
    fun bright(picture: BufferedImage): Int {
        var n = 0
        for (y in 0 until picture.height) for (x in 0 until picture.width) if (luma(picture.getRGB(x, y)) > 200) n++
        return n
    }

    private suspend fun photograph(window: ComposeWindow): BufferedImage = withContext(Dispatchers.IO) {
        Robot().createScreenCapture(window.bounds.intersection(window.graphicsConfiguration.bounds))
    }

    private suspend fun await(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!ok()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; delay(50) }
    }
}
