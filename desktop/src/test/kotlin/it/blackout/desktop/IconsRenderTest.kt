package it.blackout.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.blackout.mesh.Ic
import it.blackout.mesh.Logo
import it.blackout.mesh.PrimaryButton
import it.blackout.mesh.R
import it.blackout.mesh.SecondaryButton
import it.blackout.mesh.Theme
import org.junit.Assert.assertTrue
import org.junit.Test

class IconsRenderTest {
    /** Every picture of the phone app, drawn by the phone's own Ui.kt on the desktop; left in build/icons.png for a look. */
    @Test fun thePhonesIconsAndButtonsDraw() {
        val ids = R.drawable::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType && it.name.startsWith("ic_") && it.name != "ic_launcher_foreground" }.sortedBy { it.name }.map { it.getInt(null) }
        assertTrue("only ${ids.size} icons were found", ids.size >= 40)
        val png = Screen(920, 560, 2f) {
            Column(Modifier.fillMaxSize().background(Theme.bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Logo(44.dp); PrimaryButton("Start a group", icon = R.drawable.ic_add) {}; SecondaryButton("Join") {} }
                ids.chunked(14).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Ic(it) } } }
            }
        }.use { it.png("build/icons.png") }
        assertTrue("the picture is nearly empty", png.size > 8_000)
    }

    /** The window's icon is in the program and reads as a picture, and the installer's is where the build looks for it. */
    @Test fun theDesktopIconIsThere() {
        org.junit.Assert.assertEquals(androidx.compose.ui.geometry.Size(512f, 512f), appIcon().intrinsicSize)
        val ico = java.io.File("icons/DarkProof.ico").readBytes() // tests run in the desktop folder
        assertTrue("not an icon file with its nine sizes", ico.size > 20_000 && ico[0].toInt() == 0 && ico[2].toInt() == 1 && ico[4].toInt() == 9)
    }
}
