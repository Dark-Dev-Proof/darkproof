package it.blackout.mesh

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import it.blackout.desktop.Opener
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

// The desktop's versions of what the phones' screens ask of the phone itself (app/.../Device.kt and others): same
// names and calls, so the screens compile for the desktop unchanged. A computer has no GPS, compass or battery
// reading here yet: those answer "unknown", and the screens already handle that.

/**
 * What this device is and can do, for the words and rows the phones' screens show: a computer's answer (the phones'
 * own is in app/.../Device.kt). Each "false" is something the desktop app doesn't have yet; the screens then leave
 * its rows and steps out instead of offering what can't work. Turn one on here when the desktop gets it.
 */
object Here {
    val device = "computer"
    val bluetooth = false     // no Bluetooth or Wi-Fi Direct links yet: phones link over the same network
    val internet = true       // the internet relays, through the Tor program the app carries (standin/Tor.kt); off until switched on
    val radios = false
    val batterySaver = false
    val wifiBoard = false
    val notifications = false
    val lock = false
    val shareApp = false      // only an Android phone has the Android app's file to send
    val position = false      // no GPS
    val map = false
    val tile = false
    val ai = true             // llama.cpp beside the app (Llama.kt), with the dark room's kind of models
    val allModels = true      // every AI model is listed and says what it needs: the person decides which to download
    val aiSwitches get() = LocalAi.switches() // think first, and the graphics card where this copy can use one
    val scan = false          // no camera scanning: a code is typed, or an invite link pasted
    val background = false    // the app passes messages on only while its window is open
    val tabBar = false        // no tabs along the bottom: a rail and a sidebar at the side
    val wide = true           // panes side by side; pages open as a panel in the middle of the window
    /** A keyboard moved the focus last (Tab, arrows), not the mouse: only then is the focus ring shown. Set at the window's root. */
    var keys by mutableStateOf(false)
}

/** The phone's touch feedback on its motor. A computer has none: the same names, doing nothing. */
object Buzz {
    const val None = 0; const val Key = 1; const val Tap = 2; const val On = 3; const val Off = 4
    const val Tick = 5; const val Hold = 6; const val Confirm = 7; const val Reject = 8
    fun play(kind: Int) {}
}

/** The phones keep their screen on for Finder; a computer's screen is its own business. */
@Composable
fun KeepScreenOn() {}

/** Calm mode on a computer: not read from the system yet, so things move as on a phone with animations on. */
fun calmNow(c: Context): Boolean = false

/** The name an icon-only button already carries for screen readers, shown when the mouse rests on it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HoverTip(text: String, content: @Composable () -> Unit) = TooltipArea(delayMillis = 500, tooltip = {
    Box(Modifier.background(Color(0xFF343436), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) { Txt(text, 13, maxLines = 2) }
}) { content() }

/** Marks what a glass surface floats over. Today nothing: the place a blur's source goes later. */
fun Modifier.glassSource(): Modifier = this

/**
 * A glass surface in [shape]: the tint from [from] to [to], solid. Nothing behind it is blurred, so any see-through
 * would let the text under it show; a blur, when there is one, can make it clear again.
 */
fun Modifier.glass(shape: Shape, from: Color, to: Color): Modifier =
    background(Brush.horizontalGradient(listOf(from, to)), shape)

/** The screens ask Android's version for a few old-phone cases. A desktop is none of those. */
fun androidSdk(): Int = Int.MAX_VALUE

fun batteryPercent(c: Context): Int? = null

fun clock(c: Context, ts: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(ts))

/** "Today", "Yesterday", or the date in the computer's language, for the chat. */
fun dayLabel(c: Context, ts: Long, now: Long = System.currentTimeMillis()): String {
    fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR) }
    val today = day(System.currentTimeMillis())
    return when {
        day(ts) == today -> "Today"
        day(ts + 86_400_000L) == today -> "Yesterday"
        else -> SimpleDateFormat(if (day(ts).first == day(now).first) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy").format(Date(ts))
    }
}

fun copyText(c: Context, label: String, text: String, sensitive: Boolean = false, done: String = "Copied") {
    Opener.copy(text); Toast.makeText(c, done, Toast.LENGTH_SHORT).show()
}

/**
 * A photo from another phone, decoded safely: its bytes are untrusted, so a header claiming a huge picture is refused
 * before anything is decoded, as on the phones.
 */
fun decodePhoto(jpeg: ByteArray, maxSide: Int = 1280): Bitmap? = runCatching {
    val size = org.jetbrains.skia.Data.makeFromBytes(jpeg).use { d -> org.jetbrains.skia.Codec.makeFromData(d).use { it.width to it.height } }
    if (size.first !in 1..4096 || size.second !in 1..4096) return null
    Bitmap(org.jetbrains.skia.Image.makeFromEncoded(jpeg).toComposeImageBitmap())
}.getOrNull()

fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until size) for (x in 0 until size) img.setRGB(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return Bitmap(img.toComposeImageBitmap())
}

/** The phones scan an invite's QR code with the camera. Not on the desktop yet: an invite link can be pasted. */
fun scanInvite(c: Context, got: (SavedGroup) -> Unit) {
    Toast.makeText(c, "Scanning isn't on the desktop yet. Paste the invite link or type the code.", Toast.LENGTH_LONG).show()
}

/** The phones scan a person code with the camera (Check). Not on the desktop: its screens show the code and don't offer to scan. */
fun scanCode(c: Context, got: (String) -> Unit) {
    Toast.makeText(c, "This device has no camera. Compare the emoji instead.", Toast.LENGTH_LONG).show()
}

/** Positions. A desktop computer has no GPS: there is never a position, and the screens say so where it matters. */
object Locator {
    fun allowed(c: Context) = false
    fun enabled(c: Context) = false
    fun lastKnown(c: Context): Spot? = null
    fun fix(c: Context, waitMs: Long = 10_000, done: (Spot?) -> Unit): () -> Unit { Handler(Looper.getMainLooper()).post { done(null) }; return {} }
    fun watch(c: Context, update: (Spot) -> Unit): () -> Unit = {}
}

object Compass {
    fun watch(c: Context, update: (Float?) -> Unit): () -> Unit { Handler(Looper.getMainLooper()).post { update(null) }; return {} }
    fun declination(at: Spot, now: Long = System.currentTimeMillis()): Float = 0f
}

object BatteryLife {
    fun hoursLeft(c: Context, now: Long = System.currentTimeMillis()): Int? = null
}

/** A chosen picture file, made small enough to send: the phones' own sizes and quality steps (Device.kt). Rotation notes inside camera files are not read here. */
object PhotoShrinker {
    fun jpeg(c: Context, uri: Uri): ByteArray? = runCatching {
        val file = File(URI(uri.toString())); if (file.length() > 60_000_000L) return null
        val image = ImageIO.read(file) ?: return null
        var side = 640
        while (side >= 120) {
            val small = resized(image, side)
            for (q in 60 downTo 20 step 10) { val b = encode(small, q / 100f); if (b.size <= Photo.MAX_BYTES) return b }
            side = side * 3 / 4
        }
        null
    }.getOrNull()

    private fun resized(b: BufferedImage, maxSide: Int): BufferedImage {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        val w = (b.width * scale).roundToInt().coerceAtLeast(1); val h = (b.height * scale).roundToInt().coerceAtLeast(1)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out -> // no transparency: JPEG has none
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                color = java.awt.Color.WHITE; fillRect(0, 0, w, h); drawImage(b, 0, 0, w, h, null); dispose()
            }
        }
    }

    private fun encode(img: BufferedImage, quality: Float): ByteArray = ByteArrayOutputStream().also { out ->
        val w = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(out).use { s ->
            w.output = s
            w.write(null, IIOImage(img, null, null), w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality })
        }
        w.dispose()
    }.toByteArray()
}

/** "Lock DarkProof" uses the phone's own screen lock. Not on the desktop yet: it is never on here, and Settings says it isn't available. */
object AppLock {
    fun enabled(c: Context) = false
    fun set(c: Context, on: Boolean) {}
    fun available(c: Context) = false
    fun left(stillLocked: Boolean) {}
    fun due(c: Context) = false
    fun ask(a: MainActivity, title: String, no: () -> Unit = {}, ok: () -> Unit) = ok()
}

/** On a phone this keeps DarkProof passing messages on while the app is closed. A desktop app runs while its window is open. */
object MeshService {
    fun start(c: Context) {}
}

/** Shares the Android app's file with a phone nearby. Only an Android phone has that file. */
object ApkShare {
    fun share(a: MainActivity) { Toast.makeText(a, "The Android app can be shared from an Android phone, not from a computer.", Toast.LENGTH_LONG).show() }
}

/** The Wi-Fi board: a phone opens its own Wi-Fi network and a web page for people without the app. Not on the desktop yet. */
object WifiBoard {
    const val PORT = 8080
    val running get() = false
    val status: String get() = "Not on the desktop yet"
    fun start(ctx: Context, changed: () -> Unit) { Toast.makeText(ctx, "The Wi-Fi board isn't on the desktop yet.", Toast.LENGTH_LONG).show() }
    fun stop() {}
}
