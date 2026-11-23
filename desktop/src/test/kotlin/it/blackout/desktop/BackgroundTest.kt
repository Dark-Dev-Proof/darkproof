package it.blackout.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import it.blackout.mesh.AsciiBackground
import it.blackout.mesh.BgDensity
import it.blackout.mesh.BgField
import it.blackout.mesh.BgGlyphs
import it.blackout.mesh.BgKey
import it.blackout.mesh.BgPalette
import it.blackout.mesh.BgPlan
import it.blackout.mesh.BgSource
import it.blackout.mesh.BgStyle
import it.blackout.mesh.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The background texture (Settings > Background, AsciiBackground.kt): writing stays readable on it at every choice,
 * the field is the same every time, and the window with it, used the way a person uses it. Pictures are left in
 * the folder build/background, and what was measured is printed.
 */
class BackgroundTest {
    private fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** The brightest dot and the busiest patch (16 dp square, about one line of small text) of the texture as laid over black. */
    private fun readability(style: BgStyle, w: Int = 1440, h: Int = 900, wide: Boolean = true, scale: Float = 1f): Pair<Double, Double> {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, 0)
        val key = BgKey(w, h, scale, style.glyphs, style.density, style.palette, style.source, wide, if (wide) 0.25f else 0f, 1f)
        val img = BgPlan(key, measurer).draw(0f, null)
        val px = IntArray(w * h); img.readPixels(px)
        val alpha = AsciiBackground.inkAlpha(style)
        val lum = DoubleArray(w * h)
        var peak = 0.0
        for (i in px.indices) {
            val p = px[i]; val a = (p ushr 24) / 255f * alpha
            val l = 0.2126 * lin((p shr 16 and 255) / 255f * a) + 0.7152 * lin((p shr 8 and 255) / 255f * a) + 0.0722 * lin((p and 255) / 255f * a)
            lum[i] = l; if (l > peak) peak = l
        }
        // The busiest patch, from a summed table
        val n = (16 * scale).toInt(); val sum = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) for (x in 0 until w) sum[(y + 1) * (w + 1) + x + 1] = lum[y * w + x] + sum[y * (w + 1) + x + 1] + sum[(y + 1) * (w + 1) + x] - sum[y * (w + 1) + x]
        var busiest = 0.0
        for (y in 0..h - n step 4) for (x in 0..w - n step 4) {
            val s = sum[(y + n) * (w + 1) + x + n] - sum[y * (w + 1) + x + n] - sum[(y + n) * (w + 1) + x] + sum[y * (w + 1) + x]
            if (s / (n * n) > busiest) busiest = s / (n * n)
        }
        return 1.05 / (peak + 0.05) to 1.05 / (busiest + 0.05)
    }

    @Test fun writingStaysReadableAtEveryChoice() {
        for (p in BgPalette.entries) {
            val worst = AsciiBackground.worstContrast(BgStyle(palette = p, strength = 100))
            assertTrue("$p at full strength: white on its brightest dot is ${"%.2f".format(worst)}:1", worst >= AsciiBackground.MIN_CONTRAST)
        }
        val d = BgStyle()
        println("background default (strength ${d.strength}): brightest dot ${AsciiBackground.peak(d)}; white ${"%.1f".format(AsciiBackground.worstContrast(d))}:1, " +
            "grey text ${"%.1f".format(AsciiBackground.worstContrast(d, Theme.dim))}:1 on it (${"%.1f".format(AsciiBackground.contrast(Theme.dim, Color.Black))}:1 on black)")
        // The real pictures, every character set and colour, at full strength: the brightest dot and the busiest patch
        for (g in BgGlyphs.entries) for (p in BgPalette.entries) {
            val (dot, patch) = readability(BgStyle(glyphs = g, palette = p, density = BgDensity.Fine, strength = 100))
            assertTrue("$g $p: white on the brightest dot ${"%.2f".format(dot)}:1", dot >= AsciiBackground.MIN_CONTRAST)
            if (p == BgPalette.Grey) println("background $g grey at 100%: white ${"%.1f".format(dot)}:1 on the brightest dot, ${"%.1f".format(patch)}:1 on the busiest patch")
        }
        val (dot, patch) = readability(d)
        println("background default in a 1440 x 900 window: white ${"%.1f".format(dot)}:1 on the brightest dot, ${"%.1f".format(patch)}:1 on the busiest patch; " +
            "grey text ${"%.1f".format((Theme.dim.lum() + 0.05) / (1.05 / patch))}:1 there")
        // A phone's screen (1080 × 2400 at 2.625), at the phones' starting strength
        val phone = BgStyle(strength = 45)
        val (pDot, pPatch) = readability(phone, 1080, 2400, wide = false, scale = 2.625f)
        println("background on a phone at 45%: white ${"%.1f".format(pDot)}:1 on the brightest dot, ${"%.1f".format(pPatch)}:1 on the busiest patch; " +
            "grey text ${"%.1f".format(AsciiBackground.worstContrast(phone, Theme.dim))}:1 on the brightest dot, ${"%.1f".format((Theme.dim.lum() + 0.05) / (1.05 / pPatch))}:1 on the busiest patch")
    }

    private fun Color.lum() = 0.2126 * lin(red) + 0.7152 * lin(green) + 0.0722 * lin(blue)

    @Test fun theFieldIsTheSameEveryTime() {
        assertEquals((0 until 64).map { (it + 0.5f) / 64f }, BgField.BAYER8.sorted())
        fun levels() = BgField(120, 45, 12f, 21f, 1440, 945, true, 0.25f, 1f, 11f).let { f -> ByteArray(120 * 45).also { f.levels(BgSource.Both, 10, 0f, it) } }
        val a = levels(); assertTrue(a.contentEquals(levels()))
        assertTrue("some cells are lit", a.count { it > 0 } > 300)
        assertTrue("some stay empty", a.count { it.toInt() == 0 } > a.size / 10)
        assertTrue("few reach the brightest characters", a.count { it >= 8 } < a.size / 5)
        assertTrue(a.all { it in 0..9 })
        println("background field: ${a.count { it.toInt() == 0 }} of ${a.size} cells empty, ${a.count { it >= 8 }} at the two brightest levels")
        // "Slow drift": ten seconds on, the clouds have moved; the characters' grid has not
        val f = BgField(120, 45, 12f, 21f, 1440, 945, true, 0.25f, 1f, 11f); val later = ByteArray(120 * 45); f.levels(BgSource.Both, 10, 10f, later)
        assertTrue("drifted", !later.contentEquals(a)); assertTrue("but only a little", later.indices.count { later[it] != a[it] } < a.size / 2)
    }

    @Test fun theWindowWithTheTexture() {
        RealEngine.started(); val a = RealEngine.activity
        val out = "build/background"
        fun Screen.texture(what: String, s: BgStyle = AsciiBackground.style) = await("the texture for $what") {
            AsciiBackground.lastSize == IntSize(2040, 1290) && // this window's, not an earlier one's
                AsciiBackground.lastFor?.let { it.glyphs == s.glyphs && it.density == s.density && it.palette == s.palette && it.source == s.source } == true
        }.also { settle(200) }
        try {
            AsciiBackground.set(a, BgStyle()); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            // How long one picture takes: a 1440 × 900 window, and the same at twice the pixels
            for ((w, h, d) in listOf(Triple(1440, 900, 1f), Triple(1440, 900, 1f), Triple(2880, 1800, 2f))) {
                Screen(w, h, d) { App(a) }.use { s ->
                    s.await("the texture at $w x $h") { AsciiBackground.lastSize == IntSize(w, h) }
                    println("background: one picture for a $w x $h window (${w / d.toInt()} x ${h / d.toInt()} dp) took ${"%.1f".format(AsciiBackground.lastMillis)} ms")
                }
            }
            Screen { App(a) }.use { s ->
                s.texture("the chat")
                s.type("Message My group", "Power is out on our street too. The mesh still works."); s.tap("Send")
                s.await("the message in the chat") { s.has("Power is out on our street too.") }
                s.png("$out/1-chat.png")
                s.tap("Places"); s.png("$out/2-places.png"); s.tap("My group")
                // Settings > Background, the page where each person chooses
                s.tap("Settings"); assertTrue(s.says("Background") && s.says("Classic, grey")); s.tap("Background")
                assertTrue(s.says(AsciiBackground.ON_LINE) && s.says("Characters") && s.says("Density") && s.says("Colour") && s.says("Source") && s.says("Strength"))
                assertTrue(s.says("Classic") && s.says("Blocks") && s.says("DarkProof") && s.says("Binary") && s.says("Fine") && s.says("Medium") && s.says("Coarse"))
                assertTrue(s.says("Grey") && s.says("Navy") && s.says("Green") && s.says("Clouds") && s.says("Light streak") && s.says("Both") && s.says("60%"))
                s.settle(400); Thread.sleep(300); s.settle(200) // the preview's own picture
                s.png("$out/3-settings-background.png")
                s.wheel("Strength", 20); assertTrue(s.says("Slow drift") && s.has("The clouds move slowly while this window is in front"))
                assertTrue("the iPhone's words (design review L1)", s.says("Text stays readable at every strength."))
                s.png("$out/4-settings-background-end.png")
                // A person's first choice: blocks, coarse, navy, clouds only
                s.wheel("Strength", -20)
                s.tap("Blocks"); s.tap("Coarse"); s.tap("Navy"); s.tap("Clouds")
                assertEquals(BgStyle(glyphs = BgGlyphs.Blocks, density = BgDensity.Coarse, palette = BgPalette.Navy, source = BgSource.Clouds), AsciiBackground.style)
                assertEquals("saved with the app's preferences", "Blocks", a.getSharedPreferences("mesh", 0).getString("bg_glyphs", null))
                s.settle(400); Thread.sleep(300); s.settle(200); s.png("$out/5-settings-choice-1.png")
                s.tap("Close"); s.texture("choice 1"); s.png("$out/6-window-choice-1.png")
                // A second person's choice: DarkProof's marks, fine, green, the light streak only
                s.tap("Settings"); assertTrue(s.says("Blocks, navy")); s.tap("Background")
                s.tap("DarkProof"); s.tap("Fine"); s.tap("Green"); s.tap("Light streak")
                s.tap("Close"); s.texture("choice 2"); s.png("$out/7-window-choice-2.png")
                // Off: plain black, and nothing drawn
                s.tap("Settings"); s.tap("Background"); s.tap(AsciiBackground.ON_LINE); assertTrue(s.says("Pure black") && !s.says(AsciiBackground.ON_LINE) && !AsciiBackground.style.on)
                s.tap("Close"); assertTrue(s.says("Settings")); s.png("$out/8-window-off.png")
                // Set by the test, not through Settings: the block characters and the faint navy on their own, and full strength
                AsciiBackground.style = BgStyle(glyphs = BgGlyphs.Blocks); s.texture("blocks"); s.png("$out/9-blocks.png")
                AsciiBackground.style = BgStyle(palette = BgPalette.Navy); s.texture("navy"); s.png("$out/10-navy.png")
                AsciiBackground.style = BgStyle(strength = 100); s.texture("full strength"); s.png("$out/11-full-strength.png")
            }
        } finally {
            AsciiBackground.set(a, BgStyle()); a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
        }
    }
}
