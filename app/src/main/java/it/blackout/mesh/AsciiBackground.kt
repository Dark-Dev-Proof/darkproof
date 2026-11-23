package it.blackout.mesh

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The background the owner chose ("C", an ASCII field): dark procedural clouds and one soft diagonal streak of light,
// typed out in monospace characters, denser where it is brighter. Our own code, no pictures and no library. It is drawn
// once for a screen's size into one picture at the screen's real pixels and kept; it is drawn again only when the size
// or a choice changes (after the window has stopped changing size for a moment), never frame by frame. Only the "Slow
// drift" choice draws again over time: at most 12 times a second, only while the window is in front, never in calm mode.
// The same file serves the phones (behind the main screens) and the computers (behind the panes), and its Settings page.

// MARK: - The choices

/** Which characters. A level with several characters picks one of them per cell, at random but always the same one. */
enum class BgGlyphs(val label: String, val levels: List<String>) {
    Classic("Classic", listOf(" ", ".", ":", "-", "=", "+", "*", "#", "%", "@")),
    Blocks("Blocks", listOf(" ", "░", "▒", "▓", "█")),
    Marks("DarkProof", listOf(" ", "·", ":", "•", "/\\", "DP")),
    Binary("Binary", listOf(" ", "01", "01", "01", "01", "01")),
}

/** How big a cell is, in dp across (its height is 1.75 times that). */
enum class BgDensity(val label: String, val cell: Float) { Fine("Fine", 8f), Medium("Medium", 11f), Coarse("Coarse", 14f) }

/**
 * The characters' colours, from the faintest level to the brightest, before [AsciiBackground.inkAlpha]. [share]: how
 * much of the readable maximum this colour may reach. Green stays very faint: green means safe.
 */
enum class BgPalette(val label: String, val lo: Color, val hi: Color, val share: Float) {
    Grey("Grey", Color(0xFF2A2A2A), Color(0xFF9C9C9C), 1f),
    Navy("Navy", Color(0xFF101B36), Color(0xFF566FAE), 1f),
    Green("Green", Color(0xFF0F1914), Color(0xFF58836D), 0.5f),
}

/** What the characters show: the clouds, the streak of light, or both. */
enum class BgSource(val label: String) { Clouds("Clouds"), Streak("Light streak"), Both("Both") }

/**
 * One person's choice. [strength] is 0 to 100, a share of the most the texture may ever show: at 100 white writing on
 * the texture's brightest dot still has 7:1 contrast ([AsciiBackground.MIN_CONTRAST]).
 */
data class BgStyle(
    val on: Boolean = true, val glyphs: BgGlyphs = BgGlyphs.Classic, val density: BgDensity = BgDensity.Medium,
    val palette: BgPalette = BgPalette.Grey, val source: BgSource = BgSource.Both, val strength: Int = AsciiBackground.defaultStrength,
    val drift: Boolean = false,
)

object AsciiBackground {
    /** White writing on the brightest dot the texture can draw has at least this contrast (WCAG's AAA for text). */
    const val MIN_CONTRAST = 7.0
    /**
     * Where the strength starts, picked by looking at text on the busiest part: 60 in a computer's window; 45 on a phone,
     * whose light sits just above the message box, behind the newest messages and their time lines.
     */
    val defaultStrength get() = if (Here.wide) 60 else 45
    /** How long a window has to keep its size before the texture is drawn again for it. */
    const val SETTLE_MS = 150L
    /** "Slow drift": at most this many pictures a second. */
    const val DRIFT_FPS = 12

    private var loaded = false
    /** What is in use. Read by the screens, so a change shows at once. Tests may set it directly. */
    var style by mutableStateOf(BgStyle())

    /** How long the last full picture took to draw, in ms, how big it was, and what it was drawn for: for the tests. */
    @Volatile var lastMillis = 0.0; private set
    @Volatile var lastSize = IntSize.Zero; private set
    @Volatile var lastFor: BgStyle? = null; private set
    internal fun measured(ms: Double, size: IntSize, s: BgStyle) { lastMillis = ms; lastSize = size; lastFor = s }

    /** The choice as it is saved in the app's own preferences ("mesh"), read the first time it is asked for. */
    fun style(c: Context): BgStyle {
        if (!loaded) {
            loaded = true
            val p = c.getSharedPreferences("mesh", 0); val d = BgStyle()
            style = BgStyle(
                on = p.getBoolean("bg_on", d.on),
                glyphs = BgGlyphs.entries.firstOrNull { it.name == p.getString("bg_glyphs", null) } ?: d.glyphs,
                density = BgDensity.entries.firstOrNull { it.name == p.getString("bg_density", null) } ?: d.density,
                palette = BgPalette.entries.firstOrNull { it.name == p.getString("bg_palette", null) } ?: d.palette,
                source = BgSource.entries.firstOrNull { it.name == p.getString("bg_source", null) } ?: d.source,
                strength = p.getInt("bg_strength", d.strength).coerceIn(0, 100),
                drift = p.getBoolean("bg_drift", d.drift),
            )
        }
        return style
    }

    /** Uses [s] from now on and keeps it. */
    fun set(c: Context, s: BgStyle) {
        style = s; loaded = true
        c.getSharedPreferences("mesh", 0).edit().putBoolean("bg_on", s.on).putString("bg_glyphs", s.glyphs.name).putString("bg_density", s.density.name)
            .putString("bg_palette", s.palette.name).putString("bg_source", s.source.name).putInt("bg_strength", s.strength).putBoolean("bg_drift", s.drift).apply()
    }

    /** The line Settings shows next to "Background". */
    fun summary(s: BgStyle) = if (!s.on) "Off" else "${s.glyphs.label}, ${s.palette.label.lowercase()}"

    /** How opaque the characters are drawn over black: [BgStyle.strength] of the readable maximum for the palette. */
    fun inkAlpha(s: BgStyle): Float = s.strength.coerceIn(0, 100) / 100f * s.palette.share * maxAlpha(s.palette)

    /** The brightest colour the texture can put on the screen with [s]. */
    fun peak(s: BgStyle): Color = s.palette.hi.over(inkAlpha(s))

    /** Contrast of [text] on the texture's brightest dot with [s]: the worst place writing can land. */
    fun worstContrast(s: BgStyle, text: Color = Theme.text): Double = contrast(text, peak(s))

    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun Color.over(alpha: Float) = Color(red * alpha, green * alpha, blue * alpha) // drawn on black
    private val maxAlphas = HashMap<BgPalette, Float>()
    /** The most a palette's brightest colour may be laid over black and keep white writing at [MIN_CONTRAST], with a little room for rounding. */
    private fun maxAlpha(p: BgPalette): Float = synchronized(maxAlphas) {
        maxAlphas.getOrPut(p) {
            var lo = 0f; var hi = 1f
            repeat(30) { val m = (lo + hi) / 2; if (contrast(Theme.text, p.hi.over(m)) >= MIN_CONTRAST + 0.25) lo = m else hi = m }
            lo
        }
    }
}

// MARK: - The field

/**
 * The field for one size, worked out per cell once: clouds, the light's mask and the streak. [wide]: a computer's window,
 * where the light sits over the chat pane ([focusL] to [focusR], parts of the width); else a phone's screen.
 */
internal class BgField(val cols: Int, val rows: Int, val cellW: Float, val cellH: Float, val w: Int, val h: Int,
                       val wide: Boolean, val focusL: Float, val focusR: Float, val cellDp: Float) {
    val clouds = FloatArray(cols * rows)
    val mask = FloatArray(cols * rows)
    val fade = FloatArray(rows)
    val streak = FloatArray(cols * rows) // at the start, without the sway that "Slow drift" adds
    val alt = IntArray(cols * rows)

    // Where the light sits and how the top fades, as in the owner's preview (backgrounds.html, style C). A computer's
    // fade reaches further down than the preview's: a chat pane's title, its line under it and "No messages yet" sit in
    // the top third, and text there read worse on the clouds' bright heads.
    private val lx = if (wide) 0.74f else 0.72f; private val ly = if (wide) 0.45f else 0.8f
    private val sigma = if (wide) 0.42f else 0.5f; private val floor0 = if (wide) 0.2f else 0.14f
    private val f0 = 0.05f; private val f1 = if (wide) 0.34f else 0.16f; private val fmin = if (wide) 0.15f else 0.12f
    // The width the preview's layout would have for this height: the chat pane is 0.684 of a computer's window there.
    private val weq = if (wide) (focusR - focusL) * w / 0.684f else (focusR - focusL) * w.toFloat()
    private val designH = if (wide) 640f else 844f

    /** Across, 0 to 1, in the preview's layout: a computer's chat pane runs from 0.316 to 1, a phone's screen from 0 to 1. */
    fun u(col: Int): Float {
        val x = (col + 0.5f) * cellW / w
        val t = (x - focusL) / (focusR - focusL)
        return if (wide) 0.316f + 0.684f * t else t
    }

    init {
        val aspect = rows * cellH / (cols * cellW)
        val raw = cloudField(cols, rows, aspect, if (wide) 3 else 2, 11)
        for (y in 0 until rows) {
            val v = (y + 0.5f) * cellH / h
            fade[y] = fmin + (1 - fmin) * smooth(f0, f1, v)
            for (x in 0 until cols) {
                val i = y * cols + x; val u = u(x)
                clouds[i] = raw[i]
                val dx = u - lx; val dy = (v - ly) * h / weq
                mask[i] = (floor0 + (1 - floor0) * exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))) * fade[y]
                streak[i] = streakAt(u, v)
                alt[i] = ihash(x, y, 99) and 255
            }
        }
    }

    /** The streak of light at ([u], [v]): it runs through the empty band of each layout, brightest over the chat pane. */
    fun streakAt(u: Float, v: Float): Float {
        val vc: Float; val sl: Float; val uc: Float
        if (wide) { vc = 0.74f - 0.55f * u + 0.05f * sin(PI.toFloat() * u); sl = -0.55f + 0.05f * PI.toFloat() * cos(PI.toFloat() * u); uc = ((u - 0.2f) / 0.8f).coerceIn(0f, 1f) }
        else { vc = 0.97f - 0.36f * u + 0.05f * sin(PI.toFloat() * u); sl = -0.36f + 0.05f * PI.toFloat() * cos(PI.toFloat() * u); uc = u.coerceIn(0f, 1f) }
        val sn = sin(PI.toFloat() * uc)
        val sp = sl * h / weq
        val d = (v - vc) * h / sqrt(1 + sp * sp) // pixels from the streak's middle line
        val width = (if (wide) 10 + 26 * sn else 8 + 18 * sn) * h / designH
        val q = d * d / (width * width)
        return (0.55f * exp(-q) + 0.25f * exp(-q / 16) + 0.08f * exp(-q / 100)) * (0.2f + 0.8f * sn)
    }

    /** Each cell's level (0: empty) for [n] levels at [t] seconds of drift, ordered-dithered so steps don't band. */
    fun levels(source: BgSource, n: Int, t: Float, out: ByteArray) {
        val useC = source != BgSource.Streak; val useS = source != BgSource.Clouds
        val off = t * 3.6f / cellDp // the clouds move at the same speed on screen at every density
        val sway = sin(t * 0.12f) * 0.08f // the streak sways slowly from side to side
        for (y in 0 until rows) {
            val v = (y + 0.5f) * cellH / h
            for (x in 0 until cols) {
                val i = y * cols + x
                var value = if (useC) 0f else 0.02f
                if (useC) value += (if (off == 0f) clouds[i] else sampleWrap(x + off, y)) * mask[i] * (if (useS) 0.95f else 1.25f)
                if (useS) value += (if (sway == 0f) streak[i] else streakAt(u(x) - sway, v)) * fade[y] * 1.1f
                out[i] = floor(value * (n - 1) + BAYER8[((y and 7) shl 3) + (x and 7)]).toInt().coerceIn(0, n - 1).toByte()
            }
        }
    }

    private fun sampleWrap(x: Float, y: Int): Float {
        val xx = ((x % cols) + cols) % cols
        val x0 = xx.toInt() % cols; val x1 = (x0 + 1) % cols; val f = xx - xx.toInt()
        return clouds[y * cols + x0] * (1 - f) + clouds[y * cols + x1] * f
    }

    companion object {
        private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

        /** Bayer's 8 × 8 ordered-dither thresholds, in (0, 1). */
        val BAYER8: FloatArray = run {
            val base = arrayOf(intArrayOf(0, 2), intArrayOf(3, 1))
            var m = base
            while (m.size < 8) {
                val n = m.size; val prev = m
                m = Array(2 * n) { i -> IntArray(2 * n) { j -> 4 * prev[i % n][j % n] + base[i / n][j / n] } }
            }
            FloatArray(64) { (m[it / 8][it % 8] + 0.5f) / 64f }
        }

        fun ihash(x: Int, y: Int, s: Int): Int {
            var h = (x * 0x27d4eb2d) xor (y * 0x165667b1) xor (s * 0x9e3779b1L.toInt())
            h = (h xor (h ushr 15)) * 0x85ebca6bL.toInt()
            h = (h xor (h ushr 13)) * 0xc2b2ae35L.toInt()
            return h xor (h ushr 16)
        }
        private val GX = FloatArray(256) { cos(it / 256.0 * 2 * PI).toFloat() }
        private val GY = FloatArray(256) { sin(it / 256.0 * 2 * PI).toFloat() }
        private fun wrap(i: Int, p: Int) = if (p > 0) ((i % p) + p) % p else i

        /** Gradient noise, repeating every [px] across so the clouds can drift for ever without a seam. */
        private fun noise(x: Float, y: Float, px: Int, s: Int): Float {
            val xi = floor(x).toInt(); val yi = floor(y).toInt(); val xf = x - xi; val yf = y - yi
            val x0 = wrap(xi, px); val x1 = wrap(xi + 1, px)
            val h00 = ihash(x0, yi, s) and 255; val h10 = ihash(x1, yi, s) and 255
            val h01 = ihash(x0, yi + 1, s) and 255; val h11 = ihash(x1, yi + 1, s) and 255
            val n00 = GX[h00] * xf + GY[h00] * yf; val n10 = GX[h10] * (xf - 1) + GY[h10] * yf
            val n01 = GX[h01] * xf + GY[h01] * (yf - 1); val n11 = GX[h11] * (xf - 1) + GY[h11] * (yf - 1)
            val u = xf * xf * xf * (xf * (xf * 6 - 15) + 10); val v = yf * yf * yf * (yf * (yf * 6 - 15) + 10)
            val a = n00 + (n10 - n00) * u; val b = n01 + (n11 - n01) * u
            return a + (b - a) * v
        }
        private fun fbm(x: Float, y: Float, octaves: Int, px: Int, s: Int): Float {
            var sum = 0f; var amp = 1f; var norm = 0f; var f = 1
            for (o in 0 until octaves) { sum += amp * noise(x * f, y * f, px * f, s + o * 31); norm += amp; amp *= 0.5f; f *= 2 }
            return sum / norm
        }

        /** Clouds: domain-warped fractal noise, shaped into heaps with lit tops. 0 to 1 per cell. */
        fun cloudField(fw: Int, fh: Int, aspect: Float, c: Int, seed: Int): FloatArray {
            val raw = FloatArray(fw * fh); var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (y in 0 until fh) {
                val ny = (y + 0.5f) / fh * aspect * c
                for (x in 0 until fw) {
                    val nx = (x + 0.5f) / fw * c
                    val wx = fbm(nx + 5.2f, ny + 1.3f, 2, c, seed + 101); val wy = fbm(nx + 1.7f, ny + 9.2f, 2, c, seed + 202)
                    val n = fbm(nx + 0.8f * wx, ny + 0.8f * wy, 5, c, seed)
                    raw[y * fw + x] = n; if (n < mn) mn = n; if (n > mx) mx = n
                }
            }
            val inv = 1 / (if (mx - mn > 0) mx - mn else 1f)
            val k = max(1, (fh / (aspect * 60)).roundToInt())
            val out = FloatArray(fw * fh)
            for (y in 0 until fh) {
                val row = y * fw; val up = max(0, y - k) * fw; val dn = min(fh - 1, y + k) * fw
                for (x in 0 until fw) {
                    val n = (raw[row + x] - mn) * inv
                    val lit = (0.5f + (raw[dn + x] - raw[up + x]) * inv * 5).coerceIn(0f, 1f)
                    out[row + x] = min(1f, smooth(0.4f, 0.88f, n) * (0.45f + 0.55f * lit) + 0.1f * n * n)
                }
            }
            return out
        }
    }
}

/** What a picture is drawn from: the field, and every character once in its level's colour (an atlas), for one size and choice. */
internal class BgPlan(val key: BgKey, measurer: TextMeasurer) {
    val field: BgField
    private val atlas: ImageBitmap
    private val starts: IntArray; private val counts: IntArray
    private val cw: Int; private val ch: Int
    private val n = key.glyphs.levels.size
    private val levels: ByteArray

    init {
        val cellW = key.density.cell * key.scale; val cellH = cellW * 1.75f
        cw = kotlin.math.ceil(cellW).toInt(); ch = kotlin.math.ceil(cellH).toInt()
        val cols = kotlin.math.ceil(key.w / cellW).toInt(); val rows = kotlin.math.ceil(key.h / cellH).toInt()
        field = BgField(cols, rows, cellW, cellH, key.w, key.h, key.wide, key.focusL, key.focusR, key.density.cell)
        levels = ByteArray(cols * rows)
        // The atlas: level i's characters side by side, in a colour from the palette's faintest (level 1) to its brightest.
        val lv = key.glyphs.levels
        starts = IntArray(n); counts = IntArray(n); var total = 0
        for (i in 0 until n) { starts[i] = total; counts[i] = lv[i].length; total += lv[i].length }
        atlas = ImageBitmap(cw * total, ch)
        val font = TextStyle(fontFamily = FontFamily.Monospace, fontSize = (cellW * 1.375f).sp)
        val one = Density(1f) // sizes here are pixels
        CanvasDrawScope().draw(one, LayoutDirection.Ltr, Canvas(atlas), Size(atlas.width.toFloat(), atlas.height.toFloat())) {
            for (i in 1 until n) {
                val t = (i - 1f) / max(1, n - 2)
                val colour = Color(lerp(key.palette.lo.red, key.palette.hi.red, t), lerp(key.palette.lo.green, key.palette.hi.green, t), lerp(key.palette.lo.blue, key.palette.hi.blue, t))
                lv[i].forEachIndexed { j, g ->
                    val r = measurer.measure(AnnotatedString(g.toString()), font, density = one)
                    drawText(r, colour, Offset((starts[i] + j) * cw + (cw - r.size.width) / 2f, (ch - r.size.height) / 2f))
                }
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** The picture at [t] seconds of drift, drawn into [into] when it is the right size (else a new one). */
    fun draw(t: Float, into: ImageBitmap?): ImageBitmap {
        val f = field
        val target = into?.takeIf { it.width == key.w && it.height == key.h } ?: ImageBitmap(key.w, key.h)
        val canvas = Canvas(target)
        if (target === into) canvas.drawRect(0f, 0f, key.w.toFloat(), key.h.toFloat(), Paint().apply { blendMode = BlendMode.Clear })
        f.levels(key.source, n, t, levels)
        val paint = Paint().apply { filterQuality = FilterQuality.None }
        val size = IntSize(cw, ch)
        for (y in 0 until f.rows) {
            val top = (y * f.cellH).roundToInt()
            for (x in 0 until f.cols) {
                val i = y * f.cols + x; val k = levels[i].toInt()
                if (k < 1) continue
                val g = starts[k] + (if (counts[k] > 1) f.alt[i] % counts[k] else 0)
                canvas.drawImageRect(atlas, IntOffset(g * cw, 0), size, IntOffset((x * f.cellW).roundToInt(), top), size, paint)
            }
        }
        return target
    }
}

/** Everything a picture depends on, so it is drawn again exactly when one of them changes. The strength is not in it: it is how the picture is laid over black. */
internal data class BgKey(val w: Int, val h: Int, val scale: Float, val glyphs: BgGlyphs, val density: BgDensity, val palette: BgPalette,
                          val source: BgSource, val wide: Boolean, val focusL: Float, val focusR: Float)

/** A part of the window across, from [from] to [to] (dp from its left edge), where the texture shows at [share] of its strength. */
class BgBand(val from: Dp, val to: Dp, val share: Float)

// MARK: - On screen

/**
 * The texture, filling [modifier]'s box, behind whatever comes after it. [focus]: on a computer, where the chat pane is
 * (dp from the left), so the light falls there. [bands]: parts that show less of it, such as a sidebar. Nothing is drawn
 * while it is off.
 */
@Composable
fun AsciiBackdrop(style: BgStyle, modifier: Modifier = Modifier, focus: Pair<Dp, Dp>? = null, bands: List<BgBand> = emptyList(), report: Boolean = true) {
    if (!style.on) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    var box by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var plan by remember { mutableStateOf<BgPlan?>(null) }
    var t by remember { mutableStateOf(0f) }
    val wide = Here.wide
    val key = if (box.width <= 0 || box.height <= 0) null else {
        val (fl, fr) = focus?.let { (a, b) -> with(density) { (a.toPx() / box.width).coerceIn(0f, 1f) to (b.toPx() / box.width).coerceIn(0f, 1f) } }?.takeIf { it.second - it.first > 0.05f } ?: (0f to 1f)
        BgKey(box.width, box.height, density.density, style.glyphs, style.density, style.palette, style.source, wide, fl, fr)
    }
    LaunchedEffect(key) {
        val k = key ?: return@LaunchedEffect
        if (plan != null) delay(AsciiBackground.SETTLE_MS) // a window being resized: once it has settled
        val start = System.nanoTime()
        val (p, img) = withContext(Dispatchers.Default) { val p = BgPlan(k, measurer); p to p.draw(t, null) }
        if (report) AsciiBackground.measured((System.nanoTime() - start) / 1e6, IntSize(k.w, k.h), style)
        plan = p; image = img
    }
    // "Slow drift", only while this window is in front and nothing asks for calm (calm mode, a battery saver).
    val drift = style.drift && !Motion.calm && LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(plan, drift) {
        val p = plan ?: return@LaunchedEffect
        if (!drift) return@LaunchedEffect
        var back: ImageBitmap? = null; var last = System.nanoTime()
        while (true) {
            delay(1000L / AsciiBackground.DRIFT_FPS)
            val now = System.nanoTime(); t += min((now - last) / 1e9f, 0.25f); last = now
            val shown = image
            val drawn = withContext(Dispatchers.Default) { p.draw(t, back) }
            back = shown?.takeIf { it !== drawn }
            image = drawn
        }
    }
    val alpha = AsciiBackground.inkAlpha(style)
    Box(modifier.onSizeChanged { box = it }.clearAndSetSemantics {}.drawBehind {
        val img = image ?: return@drawBehind
        if (bands.isEmpty()) { drawImage(img, alpha = alpha); return@drawBehind }
        // Across the window, part by part: each band's share, and the whole strength where no band is.
        val cuts = (listOf(0f, size.width.toFloat()) + bands.flatMap { listOf(it.from.toPx(), it.to.toPx()) }).map { it.coerceIn(0f, size.width.toFloat()) }.distinct().sorted()
        for ((a, b) in cuts.zipWithNext()) {
            val mid = (a + b) / 2
            val share = bands.lastOrNull { mid >= it.from.toPx() && mid < it.to.toPx() }?.share ?: 1f
            if (share > 0f) clipRect(a, 0f, b, size.height.toFloat()) { drawImage(img, alpha = alpha * share) }
        }
    })
}

// MARK: - Settings

/**
 * Settings > Background: a live preview, then on or off, the characters, how dense, the colour, what it shows, how
 * strong, and slow drift. Saved with the app's preferences at once.
 */
@Composable
fun BackgroundPage(c: Context) {
    val s = AsciiBackground.style(c)
    fun set(n: BgStyle) = AsciiBackground.set(c, n)
    BgPreview(s)
    RowCard {
        ToggleRow(R.drawable.ic_image, "Background texture", s.on, if (s.on) "Faint characters behind the screens" else "Plain black") { set(s.copy(on = it)) }
    }
    Column(Modifier.alpha(if (s.on) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(padding = 18.dp) {
            BgChoice("Characters", BgGlyphs.entries, s.glyphs, { it.label }, s.on) { set(s.copy(glyphs = it)) }
            BgChoice("Density", BgDensity.entries, s.density, { it.label }, s.on) { set(s.copy(density = it)) }
            BgChoice("Colour", BgPalette.entries, s.palette, { it.label }, s.on) { set(s.copy(palette = it)) }
            BgChoice("Source", BgSource.entries, s.source, { it.label }, s.on) { set(s.copy(source = it)) }
            BgStrength(s, s.on, { AsciiBackground.style = s.copy(strength = it) }) { set(AsciiBackground.style) }
        }
        RowCard { DriftRow(s, s.on) { set(s.copy(drift = it)) } }
    }
}

/** A small window onto the texture as chosen, with a title and a message over it, drawn again as the choices change. */
@Composable
private fun BgPreview(s: BgStyle) {
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().height(if (Here.wide) 168.dp else 150.dp).clip(shape).background(Theme.bg, shape)) {
        AsciiBackdrop(s, Modifier.fillMaxSize(), report = false)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Txt("My group", 17, FontWeight.Black, maxLines = 1)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.background(Theme.card, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 9.dp)) { Txt("Meet at the bridge at 6.", 14, maxLines = 1) }
                Box(Modifier.align(Alignment.End).background(Theme.text, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 9.dp)) { Txt("On my way", 14, color = Color(0xFF111111), maxLines = 1) }
            }
        }
    }
}

/** One choice of a few: the two-way switch's look ([PillSwitch]) for any number of answers, a white pill on the one chosen. */
@Composable
private fun <T> BgChoice(title: String, options: List<T>, chosen: T, label: (T) -> String, enabled: Boolean, set: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(title)
        val i = options.indexOf(chosen).coerceAtLeast(0)
        val at by animateFloatAsState(i.toFloat(), Motion.slide(), label = "choice")
        Row(Modifier.fillMaxWidth().background(Theme.tile, CircleShape).padding(4.dp).drawBehind {
            val gap = 4.dp.toPx(); val w = (size.width - gap * (options.size - 1)) / options.size
            val x = at * (w + gap)
            drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x, 1.dp.toPx()), Size(w, size.height), CornerRadius(size.height / 2)) // its small shadow
            drawRoundRect(Color(0xFFFAFAFA), Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2))
        }.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { j, o ->
                val on = j == i
                Box(Modifier.weight(1f).heightIn(min = 40.dp).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                    .selectable(on, interactionSource = null, indication = Press(down = 0.96f, dim = 0f, lift = 0.04f, buzz = Buzz.None), enabled = enabled, role = Role.RadioButton) {
                        if (!on) { Buzz.play(Buzz.Tick); set(o) }
                    }, contentAlignment = Alignment.Center) {
                    Txt(label(o), 14, FontWeight.SemiBold, if (on) Color(0xFF111111) else Theme.dim, maxLines = 1)
                }
            }
        }
    }
}

/** How strong the texture is: 0 to 100 % of the most that keeps writing readable (7:1 on its brightest dot). */
@Composable
private fun BgStrength(s: BgStyle, enabled: Boolean, change: (Int) -> Unit, done: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Kicker("Strength") }
            Txt("${s.strength}%", 14, FontWeight.SemiBold, Theme.dim)
        }
        Slider(s.strength.toFloat(), { change(it.roundToInt()) }, Modifier.fillMaxWidth(), enabled = enabled, valueRange = 0f..100f, onValueChangeFinished = done,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Theme.tile,
                disabledThumbColor = Theme.dim, disabledActiveTrackColor = Theme.dim, disabledInactiveTrackColor = Theme.tile))
        Txt("Even at 100%, writing stays easy to read on it.", 13, color = Theme.dim)
    }
}

/** "Slow drift": off by default, and not offered while calm mode or a battery saver is on. */
@Composable
private fun DriftRow(s: BgStyle, enabled: Boolean, set: (Boolean) -> Unit) {
    val calm = Motion.calm
    val can = enabled && !calm
    val detail = when {
        calm -> if (Here.batterySaver) "Off while calm mode or a battery saver is on" else "Off while calm mode is on"
        Here.wide -> "The clouds move slowly while this window is in front"
        else -> "The clouds move slowly while DarkProof is open. Uses a little more battery."
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pointerHoverIcon(if (can) PointerIcon.Hand else PointerIcon.Default)
        .toggleable(s.drift && !calm, interactionSource = null, indication = RowPress, enabled = can, role = Role.Switch) { Buzz.play(if (it) Buzz.On else Buzz.Off); set(it) }
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Ic(R.drawable.ic_swap_horiz, Theme.text, 22.dp); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt("Slow drift", 17, FontWeight.SemiBold, maxLines = 1)
            Txt(detail, 14, color = Theme.dim)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.alpha(if (can) 1f else 0.4f).widthIn(min = 44.dp)) { Toggle(s.drift && !calm) }
    }
}
