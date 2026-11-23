package it.blackout.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * What this device is and can do, for the words and rows the screens show. This is a phone's answer: everything. The
 * desktop app gives its own (desktop/src/main/kotlin/it/blackout/desktop/standin/Phone.kt), so the same screens say
 * "this computer" there and leave out what a computer can't do yet.
 */
object Here {
    /** What the screens call this device: "this phone". */
    val device = "phone"
    val bluetooth = true      // phones nearby link by themselves over Bluetooth and Wi-Fi Direct
    val internet = true       // the internet relays, through Tor inside the app
    val radios = true         // a LoRa radio can be paired
    val batterySaver = true
    val wifiBoard = true
    val notifications = true
    val lock = true           // "Lock DarkProof"
    val shareApp = true       // the Android app's file can be sent to a phone nearby
    val position = true       // GPS, for check-ins and places
    val map = true
    val tile = true           // the "I'm safe" quick tile
    val ai = true             // the offline AI
    val scan = true           // a camera that scans an invite's QR code
    val background = true     // DarkProof keeps passing messages on while the app is closed
    val allModels = false     // the AI models shown are the ones this phone can run; a computer lists them all and says what each needs
    val aiSwitches = emptyList<AiSwitch>() // what a computer's Ask menu offers besides the model; a phone's AI has no such choices
    val tabBar = true         // the tabs float over the bottom of the screen, so each tab leaves room for them
    val wide = false          // a wide window with panes side by side: pages open in the middle of it, not as a sheet from below
    val keys = false          // a computer shows a ring where its keyboard is; a phone's finger needs none
    val swipe = true          // a finger drags a group message to the right to answer it (SwipeToReply)
}

/** A short touch on the phone's motor for what the finger just did. The phone's own touch-feedback setting can turn it off; no permission. */
object Buzz {
    const val None = 0; const val Key = 1; const val Tap = 2; const val On = 3; const val Off = 4
    const val Tick = 5; const val Hold = 6; const val Confirm = 7; const val Reject = 8
    /** The window's view, set by MainActivity while it is there. */
    var view: android.view.View? = null
    fun play(kind: Int) {
        val sdk = androidSdk()
        val what = when (kind) {
            Key -> android.view.HapticFeedbackConstants.VIRTUAL_KEY
            Tap -> android.view.HapticFeedbackConstants.KEYBOARD_TAP
            On -> if (sdk >= 34) android.view.HapticFeedbackConstants.TOGGLE_ON else android.view.HapticFeedbackConstants.CLOCK_TICK
            Off -> if (sdk >= 34) android.view.HapticFeedbackConstants.TOGGLE_OFF else android.view.HapticFeedbackConstants.CLOCK_TICK
            Tick -> if (sdk >= 34) android.view.HapticFeedbackConstants.SEGMENT_TICK else android.view.HapticFeedbackConstants.CLOCK_TICK
            Hold -> android.view.HapticFeedbackConstants.LONG_PRESS
            Confirm -> if (sdk >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY
            Reject -> if (sdk >= 30) android.view.HapticFeedbackConstants.REJECT else android.view.HapticFeedbackConstants.LONG_PRESS
            else -> return
        }
        view?.performHapticFeedback(what)
    }
}

/**
 * Calm mode ([Motion.calm]): things simply appear instead of moving, when the phone removes animations, saves power, or
 * DarkProof's own battery saver is on.
 */
fun calmNow(c: Context): Boolean =
    android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        c.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true ||
        MeshEngine.batterySaver(c)

/** Keeps the screen on while this is on screen (Finder: the searcher walks with the phone in hand). */
@Composable
fun KeepScreenOn() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
}

/** A computer shows [text] (what the button already says to TalkBack) when the mouse rests on it; a phone has no mouse. */
@Composable
fun HoverTip(text: String, content: @Composable () -> Unit) = content()

/** Marks what a glass surface floats over. Today nothing: the place a blur's source goes later. */
fun Modifier.glassSource(): Modifier = this

/**
 * A glass surface in [shape]: the tint from [from] to [to], solid. Nothing behind it is blurred, so any see-through
 * would let the text under it show; a blur, when there is one, can make it clear again.
 */
fun Modifier.glass(shape: Shape, from: Color, to: Color): Modifier =
    background(Brush.horizontalGradient(listOf(from, to)), shape)

/** Android's version number (API level). The screens ask through this, so that they also run where there is no Android. */
fun androidSdk(): Int = Build.VERSION.SDK_INT

/** Phone battery level in percent, or null if unknown. */
fun batteryPercent(c: Context): Int? =
    c.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

fun clock(c: Context, ts: Long): String = android.text.format.DateFormat.getTimeFormat(c).format(Date(ts))

/** Positions for check-ins, places and the compass. Only while the app is open; never in the background. */
object Locator {
    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Age from the phone's uptime clock: a fix's wall-clock time can be off (GPS time, emulators). */
    private fun ageMs(l: Location) = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
    private fun spot(l: Location) = Spot(l.latitude, l.longitude, l.accuracy.roundToInt().takeIf { l.hasAccuracy() })

    /**
     * A position at most 2 minutes old, waiting up to [waitMs] for a new one; null if there is none. Returns a cancel
     * function: after it, [done] is never called (e.g. the sheet that asked was closed).
     */
    @SuppressLint("MissingPermission")
    fun fix(c: Context, waitMs: Long = 10_000, done: (Spot?) -> Unit): () -> Unit {
        if (!allowed(c)) { done(null); return {} }
        val lm = c.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMs(it) < 120_000 }.minByOrNull { it.accuracy }
        if (recent != null) { done(spot(recent)); return {} }
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
            ?: providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: run { done(null); return {} }
        val h = Handler(Looper.getMainLooper()); var finished = false
        lateinit var listener: LocationListener
        fun finish(s: Spot?, report: Boolean = true) { if (!finished) { finished = true; lm.removeUpdates(listener); if (report) done(s) } }
        listener = listener { finish(spot(it)) }
        try { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) } catch (_: RuntimeException) { finish(null); return {} }
        h.postDelayed({ finish(null) }, waitMs)
        return { h.post { finish(null, report = false) } }
    }

    /** Whether the phone's location setting is on (Android's switch, not DarkProof's permission). */
    fun enabled(c: Context): Boolean = runCatching {
        val lm = c.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)

    /** The phone's last position if it is at most 10 minutes old, without turning on GPS. */
    @SuppressLint("MissingPermission")
    fun lastKnown(c: Context): Spot? {
        if (!allowed(c)) return null
        val lm = c.getSystemService(LocationManager::class.java)
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMs(it) < 600_000 }.minByOrNull { it.accuracy }?.let(::spot)
    }

    /** Position updates every few seconds until the returned function is called. */
    @SuppressLint("MissingPermission")
    fun watch(c: Context, update: (Spot) -> Unit): () -> Unit {
        if (!allowed(c)) return {}
        val lm = c.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMs(it) < 600_000 }.minByOrNull { it.accuracy }?.let { update(spot(it)) }
        val l = listener { update(spot(it)) }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) if (p in providers)
            runCatching { lm.requestLocationUpdates(p, 3_000L, 2f, l, Looper.getMainLooper()) }
        return { lm.removeUpdates(l) }
    }

    /** All four methods, so older Android versions (which have no defaults for the last three) don't crash. */
    private fun listener(got: (Location) -> Unit) = object : LocationListener {
        override fun onLocationChanged(l: Location) = got(l)
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }
}

/**
 * Which way the top of the screen points, in degrees from magnetic north; null if the phone has no compass. The sensor
 * axes are turned with the screen, so the arrow stays right in landscape too. Add [declination] for true north.
 */
object Compass {
    fun watch(c: Context, update: (Float?) -> Unit): () -> Unit {
        val sm = c.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: run { update(null); return {} }
        val rot = FloatArray(9); val turned = FloatArray(9); val o = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                val (x, y) = when (screenRotation(c)) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rot, x, y, turned); SensorManager.getOrientation(turned, o)
                update(((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat())
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        return { sm.unregisterListener(l) }
    }

    /** Degrees east of true north that magnetic north lies at [at] (Earth's field model): true = magnetic + this. */
    fun declination(at: Spot, now: Long = System.currentTimeMillis()): Float =
        runCatching { GeomagneticField(at.lat.toFloat(), at.lon.toFloat(), 0f, now).declination }.getOrDefault(0f)

    @Suppress("DEPRECATION")
    private fun screenRotation(c: Context): Int =
        (if (Build.VERSION.SDK_INT >= 30) runCatching { c.display?.rotation }.getOrNull() else null)
            ?: runCatching { c.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)
}

/** About how long the battery lasts, from how fast it dropped since it was last unplugged. */
object BatteryLife {
    /** Hours left at the rate of the last half hour or more, or null while that isn't known (or the phone charges). */
    fun hoursLeft(c: Context, now: Long = System.currentTimeMillis()): Int? {
        val bm = c.getSystemService(BatteryManager::class.java) ?: return null
        val pct = batteryPercent(c) ?: return null
        val prefs = c.getSharedPreferences("battery", 0)
        if (bm.isCharging) { if (prefs.contains("t")) prefs.edit().clear().apply(); return null }
        val t0 = prefs.getLong("t", 0); val p0 = prefs.getInt("p", -1)
        if (t0 == 0L || p0 < pct || now < t0) { prefs.edit().putLong("t", now).putInt("p", pct).apply(); return null }
        return estimate(pct, p0 - pct, (now - t0) / 3_600_000.0)
    }

    /** [drop] points in [hours]: meaningful after half an hour and 2 points, and only between 1 and 240 hours. */
    fun estimate(pct: Int, drop: Int, hours: Double): Int? =
        if (hours < 0.5 || drop < 2) null else (pct * hours / drop).roundToInt().takeIf { it in 1..240 }
}

/**
 * Copies [text] for a minute ([Copied]): a group code, a Plus Code or a message's text. Every copy is marked sensitive,
 * so Android hides it in its clipboard preview. [sensitive] is kept for the callers; it no longer changes anything.
 */
@Suppress("UNUSED_PARAMETER")
fun copyText(c: Context, label: String, text: String, sensitive: Boolean = false, done: String = "Copied") {
    Copied.put(c, label, text)
    if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(c, done, android.widget.Toast.LENGTH_SHORT).show() // Android 13+ shows its own
}

/**
 * What the app last put on the clipboard, so that it comes off again: a minute after it was copied, and when
 * everything is erased ([Wipe]).
 *
 * Android gives a copy no expiry of its own and lets an app read the clipboard only while it is in front. So the
 * minute is kept by the app: when it is over, the clipboard is cleared if it still holds this very text; if another
 * app is in front then, that is done the next time DarkProof is in front ([expire], from the screen getting the
 * focus). If the app is ended within that minute the copy stays until Android clears it or something else is copied.
 * Android has no way to keep a copy on this phone only: a phone that shares its clipboard with other devices shares this too.
 */
object Copied {
    const val KEEP_MS = 60_000L
    /** The labels the app copies under: how an erase knows a clip is the app's own, after a restart too. */
    val LABELS = setOf("Group code", "Message", "Plus Code")
    @Volatile private var text: String? = null
    @Volatile private var until = 0L

    fun put(c: Context, label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        val app = c.applicationContext
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        this.text = text; until = android.os.SystemClock.elapsedRealtime() + KEEP_MS
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ expire(app) }, KEEP_MS)
    }

    /** Clears the clipboard if the minute of the app's last copy is over and it is still there. Main thread. */
    fun expire(c: Context) {
        val mine = text ?: return
        if (android.os.SystemClock.elapsedRealtime() < until) return
        runCatching {
            val board = c.getSystemService(ClipboardManager::class.java)
            val there = board.primaryClip ?: return // not readable now (another app is in front), or empty: tried again when this app is in front
            if (there.itemCount > 0 && there.getItemAt(0).text?.toString() == mine) clear(board)
            text = null
        }
    }

    /** Erase: the clipboard is cleared now if it holds something the app put there (by its text, or by its label after a restart). */
    fun forget(c: Context) {
        runCatching {
            val board = c.getSystemService(ClipboardManager::class.java)
            val mine = text
            val isMine = board.primaryClipDescription?.label?.toString() in LABELS ||
                (mine != null && board.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() == mine)
            if (isMine) clear(board)
        }
        text = null
    }

    private fun clear(board: ClipboardManager) { if (Build.VERSION.SDK_INT >= 28) board.clearPrimaryClip() else board.setPrimaryClip(ClipData.newPlainText("", "")) }
}

/**
 * Makes a photo small enough for one packet (Photo.MAX_BYTES): JPEG, a few hundred pixels wide.
 * Bluetooth carries a few kilobytes per second, so this keeps a photo to a few seconds per hop.
 */
object PhotoShrinker {
    fun jpeg(c: Context, uri: Uri): ByteArray? = runCatching { load(c, uri)?.let(::jpeg) }.getOrNull()

    fun jpeg(image: Bitmap): ByteArray? {
        var side = 640
        while (side >= 120) {
            val small = resized(image, side)
            for (q in 60 downTo 20 step 10) {
                val b = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
                if (b.size <= Photo.MAX_BYTES) return b
            }
            side = side * 3 / 4
        }
        return null
    }

    private fun resized(b: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(b.width, b.height)
        return if (scale >= 1) b else Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt().coerceAtLeast(1), (b.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    /** Decodes at most about 1280 px on the long side, upright (camera photos carry their rotation in EXIF). */
    private fun load(c: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
            val s = 1280f / maxOf(info.size.width, info.size.height)
            if (s < 1) d.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
}

/**
 * A photo from another phone, decoded safely: its bytes are untrusted, so a header claiming a huge picture is refused
 * (it could need hundreds of MB) and the picture is decoded at most about [maxSide] pixels on its longest side.
 */
fun decodePhoto(jpeg: ByteArray, maxSide: Int = 1280): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
    if (o.outWidth !in 1..4096 || o.outHeight !in 1..4096) return null
    var sample = 1; while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
    return runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
}

/** A QR code as a black-on-white bitmap, made on the phone. */
fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * "Lock DarkProof" (Settings), off unless turned on: the app asks for the phone's fingerprint, face or PIN when it opens,
 * and again after a minute away. While it is on, notifications don't show message text and screenshots are blocked.
 * Asking for help never needs it: the lock screen has the button, and tiles and shortcuts keep working.
 */
object AppLock {
    private const val GRACE_MS = 60_000L
    private var leftAt = 0L // when the screen was last left; 0: not since the app started
    private fun prefs(c: Context) = c.getSharedPreferences("mesh", 0)

    /** On when the user turned it on, and always while high-risk mode is on: everything that follows the lock follows that too. */
    fun enabled(c: Context) = prefs(c).getBoolean("appLock", false) || HighRisk.on(c)
    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("appLock", on).apply()
    /** False if the phone has no screen lock: there would be nothing to ask for. */
    fun available(c: Context) = c.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure
    /** The screen went away. Still locked: it stays locked however the app is opened next, not only for a minute. */
    fun left(stillLocked: Boolean) { leftAt = if (stillLocked) 0L else android.os.SystemClock.elapsedRealtime() }
    /** In high-risk mode there is no minute of grace: the app locks every time it was left. */
    fun due(c: Context) = enabled(c) && (leftAt == 0L || HighRisk.on(c) || android.os.SystemClock.elapsedRealtime() - leftAt >= GRACE_MS)

    /**
     * Asks for the phone's own unlock, then runs [ok]; [no] if it didn't go through (cancelled, or failed for good). If
     * the phone has no screen lock any more, nobody is shut out.
     */
    fun ask(a: MainActivity, title: String, no: () -> Unit = {}, ok: () -> Unit) {
        if (!available(a)) { ok(); return }
        if (Build.VERSION.SDK_INT < 30) { a.confirmCredential(title, no, ok); return }
        android.hardware.biometrics.BiometricPrompt.Builder(a).setTitle(title)
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build().authenticate(android.os.CancellationSignal(), a.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = ok()
                override fun onAuthenticationError(code: Int, why: CharSequence?) = no()
            })
    }
}

/**
 * Alerts for new messages of every saved group. Made on the phone: no internet needed. While DarkProof is on screen,
 * someone needing help, an "are you safe?" meant for me and other groups' messages show as a banner (with a vibration
 * when it is urgent); otherwise as notifications, which open that group's chat.
 */
object Notifier {
    private const val MESSAGES = "messages"; private const val HELP = "help"
    @Volatile var onScreen = false
    /** Set by the screen while it is shown: gets the banners. Main thread. */
    var inApp: ((Alert) -> Unit)? = null

    /** [group]: the saved group's code. [ask]: offer the one-tap "I'm safe" / "I need help" answers. */
    data class Alert(val title: String, val body: String, val group: String?, val urgent: Boolean, val ask: Boolean, val messageId: String)

    fun setUp(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MESSAGES, "Messages and check-ins", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(HELP, "Someone needs help", NotificationManager.IMPORTANCE_HIGH))
        MeshEngine.onIncoming = { m -> received(c.applicationContext, m) }
        QuietAlarms.setUp(c) // check-in clocks' alerts and their alarm (spec "If I go quiet")
    }

    /** What a group message from someone else says, as an alert. */
    fun alertFor(c: Context, m: Message): Alert {
        val group = MeshEngine.groupName(m.group) ?: Groups.current(c)?.name ?: "your group"
        val me = c.getSharedPreferences("mesh", 0).getString("name", "") ?: ""
        val check = CheckIn.of(m.text); val vouch = Vouch.of(m.text); val ask = AreYouSafe.of(m.text)
        fun details(needs: Set<Need>, people: Int?) = (needs.map { it.label } + listOfNotNull(people?.let { "$it people" })).joinToString(" · ")
        return when {
            check == CheckIn.HELP -> Alert("${m.name} needs help",
                details(CheckIn.needs(m.text), CheckIn.people(m.text)).ifEmpty { "Open DarkProof to see where they are." } + " · $group", m.group, true, false, m.id)
            // In a group whose messages disappear after a day or less, the name a report gives is its sender's own words: kept
            // out of the alert ("Someone needs help", reported by whom), as the iPhone keeps a message's words out of its alerts there.
            vouch?.status == CheckIn.HELP -> Alert("${GroupTimer.reported(vouch.subject, MeshEngine.timer(m.group).seconds)} needs help",
                listOf(details(vouch.needs, vouch.people), "Reported by ${m.name} · $group").filter { it.isNotEmpty() }.joinToString(" · "), m.group, true, false, m.id)
            ask != null && ask.isFor(me) -> Alert(if (ask.to == null) "${m.name} asks: is everyone safe?" else "${m.name} asks: are you safe?",
                "Answer with one tap · $group", m.group, true, true, m.id)
            check == CheckIn.SAFE -> Alert("${m.name} is safe", group, m.group, false, false, m.id)
            // A check-in clock (spec "If I go quiet"): none of its sender's words, which may carry a position.
            m.notice is Notice.Clock -> Alert("${m.name} · $group", QuietWords.messageAlert(m.notice.deadline, clock(c, m.notice.deadline)), m.group, false, false, m.id)
            vouch != null -> Alert("${vouch.subject} is safe", "Reported by ${m.name} · $group", m.group, false, false, m.id)
            else -> Alert("${m.name} · $group", if (m.photo != null) Photo.text(Photo.caption(m.text)) else m.text, m.group, false, false, m.id)
        }
    }

    private fun received(c: Context, m: Message) {
        if (!Moderation.visible(c, m)) return
        val a = alertFor(c, m)
        if (onScreen) {
            val otherGroup = m.group != null && m.group != Groups.current(c)?.code
            if (a.urgent || otherGroup) { inApp?.invoke(a); if (a.urgent) vibrate(c) }
            return
        }
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 33) return
        // Help alerts and questions each get their own notification; the rest replace one per person.
        val id = if (a.urgent) m.id.hashCode() else m.sender.hashCode()
        // With "Lock DarkProof" on, nothing about who or what shows on a locked or borrowed phone.
        val hide = AppLock.enabled(c)
        // High-risk mode shows less still: only that there is a new message, or that someone needs help. A question
        // ("are you safe?") is a new message like any other there, with no answers to tap.
        val risk = HighRisk.on(c); val plain = risk && a.ask
        val body = if (!hide) a.body else if (plain) "New message" else if (a.ask) "Someone asks if you are safe. Open DarkProof." else if (a.urgent) "Someone needs help. Open DarkProof." else "New message"
        val n = android.app.Notification.Builder(c, if (a.urgent && !plain) HELP else MESSAGES)
            .setSmallIcon(R.drawable.ic_notifications).setContentTitle(if (hide) "DarkProof" else a.title).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(open(c, id, m.group)).setAutoCancel(true).setGroup("group")
        // Its text must not outlive the message: a week at most, and in a group with a timer, until the message is due.
        n.setTimeoutAfter(AlertLife.timeout(m.ts, System.currentTimeMillis(), MeshEngine.timer(m.group).seconds?.let { it * 1_000L }))
        if (a.ask && !risk) {
            // "I'm safe" sends only after the phone's unlock (rule Q8). From Android 12 the button asks for it and then sends;
            // before, a button can't ask: it opens the group's chat, with the answers under the question, which a locked
            // phone shows only after its unlock (as the clock's reminder does, QuietAlarms).
            val sends = LockedSafe.buttonSends(Build.VERSION.SDK_INT)
            val safe = if (sends) PendingIntent.getBroadcast(c, id,
                Intent(c, AnswerReceiver::class.java).putExtra(Actions.EXTRA_GROUP, m.group).putExtra(AnswerReceiver.NOTIFICATION, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getActivity(c, id + 2, Actions.intent(c, Actions.CHAT, m.group).putExtra(AnswerReceiver.NOTIFICATION, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (!hide) n.addAction(android.app.Notification.Action.Builder(null, "I'm safe", safe).apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build())
            n.addAction(android.app.Notification.Action.Builder(null, "I need help", PendingIntent.getActivity(c, id + 1,
                Actions.intent(c, Actions.HELP, m.group).putExtra(AnswerReceiver.NOTIFICATION, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        c.getSystemService(NotificationManager::class.java).notify(id, n.build())
        synchronized(posted) { posted[id] = m.id }
    }

    private val posted = HashMap<Int, String>() // notification id -> the message it shows now, while the app runs

    /** The messages [ids] were deleted: their alerts go too, as they show their text ([AlertLife] covers the rest). */
    fun forget(c: Context, ids: Collection<String>) {
        val gone = synchronized(posted) { posted.filterValues { it in ids }.keys.toList().onEach { posted.remove(it) } }
        if (gone.isNotEmpty()) c.getSystemService(NotificationManager::class.java)?.let { nm -> gone.forEach(nm::cancel) }
    }

    /**
     * A group was left: its alerts go. Message alerts are known by their message only while the app that posted them still
     * runs ([posted]), so every message alert goes, the other groups' too; their messages are still in the app, counted as
     * new. Clock alerts go only for that group ([quietIds]: their [QuietAlerts] names): another group's "hasn't checked in"
     * alert stays, since it isn't posted a second time (final review 2026-11-23).
     */
    fun forgetGroup(c: Context, quietIds: Collection<String>) {
        synchronized(posted) { posted.clear() }
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        cancelMessages(nm)
        quietIds.forEach { runCatching { nm.cancel(QuietAlarms.TAG, it.hashCode()) } }
    }

    /**
     * "Lock DarkProof" (or high-risk mode, which turns it on) was turned on: message alerts already showing go, since they
     * show names and text; clock alerts still showing stay, worded again without a name ([QuietAlarms.hideShowing]), since
     * they aren't posted a second time (final review 2026-11-23).
     */
    fun hideShowing(c: Context) {
        synchronized(posted) { posted.clear() }
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        cancelMessages(nm)
        QuietAlarms.hideShowing(c)
    }

    /** Every message alert showing (they have no tag; clock alerts have [QuietAlarms.TAG]). The running service's "DarkProof is on" notice stays. */
    private fun cancelMessages(nm: NotificationManager) {
        val showing = runCatching { nm.activeNotifications }.getOrNull() ?: run { nm.cancelAll(); return } // can't be listed: everything, as before
        for (s in showing) if (s.tag == null && s.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE == 0) runCatching { nm.cancel(s.id) }
    }

    /** Opens DarkProof on [group]'s chat. */
    private fun open(c: Context, id: Int, group: String?) = PendingIntent.getActivity(c, id, Actions.intent(c, Actions.CHAT, group),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("DEPRECATION")
    fun vibrate(c: Context) = runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else c.getSystemService(android.os.Vibrator::class.java)
        v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1))
    }
}

/** "Today", "Yesterday", or the date in the phone's language, for the chat. */
fun dayLabel(c: Context, ts: Long, now: Long = System.currentTimeMillis()): String = when {
    android.text.format.DateUtils.isToday(ts) -> "Today"
    android.text.format.DateUtils.isToday(ts + 86_400_000L) -> "Yesterday"
    else -> {
        val sameYear = java.util.Calendar.getInstance().apply { timeInMillis = ts }.get(java.util.Calendar.YEAR) ==
            java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.YEAR)
        android.text.format.DateUtils.formatDateTime(c, ts, android.text.format.DateUtils.FORMAT_SHOW_DATE or
            android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or (if (sameYear) android.text.format.DateUtils.FORMAT_NO_YEAR else android.text.format.DateUtils.FORMAT_SHOW_YEAR))
    }
}
