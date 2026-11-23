package it.blackout.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.blackout.mesh.FinderAsker
import it.blackout.mesh.FinderBody
import it.blackout.mesh.FinderMath
import it.blackout.mesh.FinderOnComputer
import it.blackout.mesh.FinderReach
import it.blackout.mesh.FinderRole
import it.blackout.mesh.FinderWords
import it.blackout.mesh.R
import it.blackout.mesh.RoundIcon
import it.blackout.mesh.SheetColor
import it.blackout.mesh.Theme
import it.blackout.mesh.Txt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Finder screen as a phone draws it, from the phones' own FinderView.kt, at a phone's width (390 x 844 dp), in each of
 * its states: the four bands, "Not in reach", not linked, linked over Wi-Fi only; and the computer's version. An emulator
 * has no Bluetooth, so this is how the screen is looked at before real phones. Pictures: the PNG files in build/finder.
 */
class FinderRenderTest {
    private val marco = FinderAsker("Marco", System.currentTimeMillis() - 6 * 60_000L, 15)

    /** The sheet around the screen as a phone shows it: its colour, the title and the close button. */
    @Composable private fun PhoneSheet(title: String, content: @Composable () -> Unit) =
        Column(Modifier.fillMaxSize().background(Theme.bg).padding(top = 44.dp)) {
            Column(Modifier.fillMaxSize().background(SheetColor, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(title, 26, FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1)
                    RoundIcon(R.drawable.ic_close, 36.dp, desc = "Close") {}
                }
                content()
            }
        }

    private fun step(band: FinderMath.Band, trend: FinderMath.Trend?, s: Double?) = FinderMath.Step(9, band, trend, s, FinderMath.ticks(s))

    private fun shoot(name: String, shown: FinderWords.Shown, debug: String? = null): List<String> =
        Screen(780, 1688, 2f) { PhoneSheet(FinderWords.title("Marco")) { FinderBody(shown, marco, debug) {} } }.use { s ->
            s.png("build/finder/$name.png"); s.texts()
        }

    @Test fun everyStateOfThePhoneScreen() {
        val far = FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, step(FinderMath.Band.FAR, FinderMath.Trend.STEADY, -88.0))
        val near = FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, step(FinderMath.Band.NEAR, FinderMath.Trend.STRONGER, -76.0))
        val close = FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CLIENT, step(FinderMath.Band.VERY_CLOSE, FinderMath.Trend.WEAKER, -65.0))
        val here = FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, step(FinderMath.Band.HERE, FinderMath.Trend.STEADY, -56.0))
        val silent = FinderWords.shown("Marco", FinderReach.BLUETOOTH, FinderRole.CENTRAL, step(FinderMath.Band.NOT_IN_REACH, null, null))
        val none = FinderWords.shown("Marco", FinderReach.NONE, FinderRole.NONE, null)
        val wifi = FinderWords.shown("Marco", FinderReach.OTHER, FinderRole.NONE, null)
        shoot("1-far", far).let { t -> assertTrue(t.toString(), "Far" in t && "Signal steady" in t && "I'm with Marco" in t && "Marco needs help" in t && FinderWords.FINE_PRINT in t) }
        shoot("2-near", near, debug = "-76 dBm · S -76.0 · central · a1b2c3d4e5f6").let { t -> assertTrue(t.toString(), "Near" in t && "Signal getting stronger" in t) }
        shoot("3-very-close", close).let { t -> assertTrue(t.toString(), "Very close" in t && "Signal getting weaker" in t) }
        shoot("4-here", here).let { t -> assertTrue(t.toString(), "Here" in t && "Within a few steps. Look around." in t) }
        shoot("5-not-in-reach", silent).let { t -> assertTrue(t.toString(), "Not in reach" in t && "This phone hasn't heard Marco's phone yet." in t) }
        shoot("6-not-linked", none).let { t -> assertTrue(t.toString(), "Not in reach" in t && t.any { "linked by Bluetooth" in it }) }
        shoot("7-wifi-only", wifi).let { t -> assertTrue(t.toString(), "Linked" in t && t.any { "not over Bluetooth" in it }) }
        // No word in metres, on any of them (decision 4).
        for (sh in listOf(far, near, close, here, silent, none, wifi)) assertFalse(listOfNotNull(sh.word, sh.line, sh.note).joinToString().contains(Regex("\\d\\s?m\\b|metre")))
    }

    /** A computer: the screen says Finder needs a phone's Bluetooth, and has nothing to press but Close. */
    @Test fun theComputersScreen() {
        Screen(1040, 1100, 2f) {
            Column(Modifier.fillMaxSize().background(Theme.bg).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.width(440.dp).background(SheetColor, RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(FinderWords.title("Marco"), 26, FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1)
                        RoundIcon(R.drawable.ic_close, 36.dp, desc = "Close") {}
                    }
                    FinderOnComputer(marco)
                }
            }
        }.use { s ->
            s.png("build/finder/8-computer.png")
            assertTrue(s.texts().toString(), s.has("Finder needs a phone's Bluetooth") && s.has("Marco needs help"))
            assertFalse("no button that can't work here", s.has("I'm with Marco"))
            assertFalse(s.has("Not in reach"))
        }
    }
}
