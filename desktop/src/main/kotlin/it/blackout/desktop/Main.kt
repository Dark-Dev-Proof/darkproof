package it.blackout.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import it.blackout.mesh.EmptyScreen
import it.blackout.mesh.MainActivity
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.Theme
import it.blackout.mesh.Wipe
import it.blackout.mesh.WifiLink
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--self-test" in args) exitProcess(SelfTest.run()) // no window: see SelfTest
    // A Mac draws the bar along the top of the window itself too, and draws it dark when asked before the first window opens.
    if (System.getProperty("os.name").orEmpty().startsWith("Mac")) System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    val test = "--window-test" in args // the real window, photographed: see WindowTest
    if (test) WifiLink.discovery = null // a test's messages are not for real devices: it neither looks for them nor announces itself
    it.blackout.mesh.KeyProtect.secureStore = !test // the real app keeps the Vault key in the computer's own key store; tests stay on a plain file
    window(test)
}

private fun window(test: Boolean) = application {
    val a = remember { Desktop.activity(relays = !test) } // the window test stays off the relays
    // On Windows the window opens once its title bar has been made dark, so it never shows up with a light one.
    var open by remember { mutableStateOf(!TitleBar.onWindows) }
    LaunchedEffect(Unit) { delay(2500); open = true } // whatever happens to the title bar, the window opens
    Window(onCloseRequest = { MeshEngine.shutdown(); exitApplication() }, visible = open, title = "DarkProof", icon = remember { appIcon() }, state = rememberWindowState(size = DpSize(1360.dp, 860.dp))) {
        LaunchedEffect(Unit) {
            // High-risk mode's 24-hour rule also runs each time the window comes to the front, as on the phones.
            window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { if (it.blackout.mesh.HighRisk.on(a)) MeshEngine.keepLess() }
            })
            window.minimumSize = java.awt.Dimension(900, 620)
            var bar = TitleBar.Answer(error = "not Windows"); var asked = "not asked"
            if (TitleBar.onWindows) {
                val colour = Theme.bg.toArgb() and 0xFFFFFF; val edge = Theme.line.toArgb() and 0xFFFFFF
                if (!open) { // the window exists for Windows from here on, and stays hidden until it is asked for a dark bar
                    bar = runCatching { if (!window.isDisplayable) window.addNotify(); TitleBar.darken(window.windowHandle, colour, edge) }.getOrElse { TitleBar.Answer(error = it.toString()) }
                    asked = "asked before the window opened"; open = true
                }
                if (!bar.isDark) { // not taken then: once more with the window open, drawing its frame again
                    delay(400); val first = bar
                    bar = runCatching { TitleBar.darken(window.windowHandle, colour, edge, redraw = true) }.getOrElse { TitleBar.Answer(error = it.toString()) }
                    asked = "asked after the window opened; before it: $first"
                }
            }
            if (test) exitProcess(WindowTest.run(window, a, bar, asked))
        }
        App(a)
    }
}

/** The icon in the window's corner and on the taskbar: the logo on a black tile (icons/MakeIcon.java draws it). */
internal fun appIcon(): Painter =
    BitmapPainter(Image.makeFromEncoded(object {}.javaClass.getResourceAsStream("/icon.png")!!.use { it.readBytes() }).toComposeImageBitmap())

/**
 * Everything inside the window: the desktop's layout (Shell.kt) around the phones' own screens, handed the desktop's
 * Context where they ask Android for one, and over them the short messages a phone shows at the bottom of its screen.
 * Tests draw this without a window.
 */
@Composable
internal fun App(a: MainActivity) {
    CompositionLocalProvider(LocalContext provides a) {
        Box(Modifier.fillMaxSize().background(Theme.bg)) {
            DesktopApp(a)
            Wipe.erased?.let { lines -> EmptyScreen(lines) { Wipe.emptySeen() } } // "This computer is empty", once, after "Erase everything"
            Toasts.text?.let { t ->
                // Above the tab bar and the message box, and light on dark so it can't be taken for one of the screen's own cards.
                Box(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 96.dp).clip(RoundedCornerShape(22.dp)).background(Theme.text).padding(horizontal = 18.dp, vertical = 11.dp)) {
                    Text(t, color = Color.Black, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}
