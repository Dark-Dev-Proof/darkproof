package it.blackout.mesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.*

/**
 * Street maps from OpenFreeMap (OpenStreetMap data, dark style), saved on the phone for offline use while there is
 * internet, like the iPhone's OfflineMap.swift. OpenFreeMap's terms (openfreemap.org/tos, "Offline Downloads") allow an
 * app to download areas if each download is at most 3,000 tiles or 50 MB, uses at most 4 parallel requests, and sends a
 * User-Agent naming the app.
 */
object OfflineMap {
    const val STYLE = "https://tiles.openfreemap.org/styles/dark"
    private const val RADIUS_KM = 10.0 // the saved area is about 20 km across
    private const val MAX_ZOOM = 14.0  // OpenFreeMap's tiles stop at 14; the map zooms further by enlarging them
    private const val MAX_TILES = 3_000
    private const val MAX_BYTES = 50_000_000L

    sealed class State {
        object None : State()
        data class Downloading(val progress: Float) : State()
        data class Ready(val bytes: Long, val center: Spot, val ts: Long) : State()
        data class Failed(val why: String) : State()
    }
    var state by mutableStateOf<State>(State.None); private set
    private var region: OfflineRegion? = null
    private var ready = false

    /** Call once at launch, before any map is shown. */
    fun setUp(c: Context) {
        if (ready) return; ready = true
        MapLibre.getInstance(c.applicationContext)
        if (Tor.enabled(c)) MapLibre.setConnected(false)
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"
        val ua = "DarkProof/$version (Android; +https://github.com/DEV-ENT/DarkProof)"
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
            .addInterceptor { ch -> ch.proceed(ch.request().newBuilder().header("User-Agent", ua).build()) }
            .build())
        OfflineManager.getInstance(c).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) { offlineRegions?.firstOrNull()?.let { watch(it, resume = true) } }
            override fun onError(error: String) {}
        })
    }

    /** The saved area as a box around [c]. */
    fun bounds(c: Spot): LatLngBounds {
        val dLat = RADIUS_KM / 111.32; val dLon = RADIUS_KM / (111.32 * max(0.1, cos(Math.toRadians(c.lat))))
        return LatLngBounds.from(c.lat + dLat, c.lon + dLon, c.lat - dLat, c.lon - dLon)
    }

    /** Tiles a download of [b] from zoom 0 to MAX_ZOOM needs (standard web map tiling). */
    fun tileCount(b: LatLngBounds): Int = (0..MAX_ZOOM.toInt()).sumOf { z ->
        val n = (1 shl z).toDouble()
        fun x(lon: Double) = floor((lon + 180) / 360 * n).toInt()
        fun y(lat: Double) = Math.toRadians(lat).let { r -> floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt() }
        (x(b.longitudeEast) - x(b.longitudeWest) + 1) * (y(b.latitudeSouth) - y(b.latitudeNorth) + 1)
    }

    /** Saves the map around [c], replacing any saved area. */
    /** With "Connect through Tor" the map shows only what is saved: the map server would see this phone's address and the area on screen. */
    fun torChanged(on: Boolean) { runCatching { MapLibre.setConnected(if (on) false else null) } }

    fun download(ctx: Context, c: Spot) {
        if (state is State.Downloading) return
        if (Tor.enabled(ctx)) { state = State.Failed("Turn off Connect through Tor to download the map: the map server would see your address."); return }
        val b = bounds(c)
        if (tileCount(b) > MAX_TILES) { state = State.Failed("This area needs too many map tiles."); return }
        state = State.Downloading(0f)
        val def = OfflineTilePyramidRegionDefinition(STYLE, b, 0.0, MAX_ZOOM, ctx.resources.displayMetrics.density)
        val meta = JSONObject().put("lat", c.lat).put("lon", c.lon).put("ts", System.currentTimeMillis()).toString().toByteArray()
        removeAll(ctx) {
            OfflineManager.getInstance(ctx).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) = watch(offlineRegion, resume = true)
                override fun onError(error: String) { state = State.Failed(error) }
            })
        }
    }

    fun delete(ctx: Context) = removeAll(ctx) { state = State.None }

    private fun removeAll(ctx: Context, done: () -> Unit) {
        region?.let { it.setObserver(null); it.setDownloadState(OfflineRegion.STATE_INACTIVE) }; region = null
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val all = offlineRegions.orEmpty().toMutableList()
                fun next() { if (all.isEmpty()) done() else all.removeAt(0).delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = next()
                    override fun onError(error: String) = next()
                }) }
                next()
            }
            override fun onError(error: String) = done()
        })
    }

    private fun center(r: OfflineRegion): Pair<Spot, Long> = runCatching {
        JSONObject(String(r.metadata)).let { Spot(it.getDouble("lat"), it.getDouble("lon")) to it.getLong("ts") }
    }.getOrDefault(Spot(0.0, 0.0) to 0L)

    private fun watch(r: OfflineRegion, resume: Boolean) {
        region = r
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (region !== r) return
                if (status.completedResourceSize > MAX_BYTES) { // over OpenFreeMap's limit: stop and remove it
                    r.setObserver(null); r.setDownloadState(OfflineRegion.STATE_INACTIVE); region = null
                    r.delete(object : OfflineRegion.OfflineRegionDeleteCallback { override fun onDelete() {}; override fun onError(error: String) {} })
                    state = State.Failed("This area is bigger than 50 MB."); return
                }
                state = if (status.isComplete) {
                    r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    val (c, ts) = center(r); State.Ready(status.completedResourceSize, c, ts)
                } else State.Downloading(if (status.requiredResourceCount > 0) min(0.99f, status.completedResourceCount.toFloat() / status.requiredResourceCount) else 0f)
            }
            override fun onError(error: OfflineRegionError) { if (region === r) state = State.Failed(error.message) }
            override fun mapboxTileCountLimitExceeded(limit: Long) { if (region === r) state = State.Failed("This area needs too many map tiles.") }
        })
        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (status == null) return
                if (status.isComplete) { val (c, ts) = center(r); state = State.Ready(status.completedResourceSize, c, ts) }
                else if (resume) { state = State.Downloading(0f); r.setDownloadState(OfflineRegion.STATE_ACTIVE) } // cut off earlier: carry on
            }
            override fun onError(error: String?) {}
        })
    }
}

/**
 * A pin on the map: a place, shown with its kind's [icon], or a person, shown with the first letter of [title]. [color]
 * is the ring: white, grey for an old place, green for "I'm safe", red for help. Someone asking for [help] is never
 * merged into a cluster.
 */
data class Pin(val title: String, val spot: Spot, val ts: Long, val color: String, @DrawableRes val icon: Int? = null, val help: Boolean = false)

/**
 * How pins look on the map, drawn here as pictures so the map needs nothing from the internet for them (no sprite, no
 * font): a charcoal disc with a white glyph and a coloured ring, like the iPhone's MapView.swift, and for nearby pins
 * merged together, a charcoal bubble with the count. Each picture has a name that says everything needed to draw it
 * again ([PinArt], MapPins.kt, shared with the desktop's tests), so the map can ask for it again at any time.
 */
object PinPictures {
    private const val SIZE = 38f   // dp, as the iPhone's pins (points)
    private const val RING = 2.5f
    private const val GLYPH = 20f
    private const val CHARCOAL = 0xFF212121.toInt() // Theme.tile

    /** The picture for [name], or null when the name is not one of ours (the map style's own icons). */
    fun image(ctx: Context, name: String): Bitmap? = runCatching {
        when {
            name.startsWith(PinArt.CLUSTER) -> name.removePrefix(PinArt.CLUSTER).toIntOrNull()?.let { cluster(ctx, it) }
            name.startsWith(PinArt.PIN) -> name.removePrefix(PinArt.PIN).split("|", limit = 3).takeIf { it.size == 3 }?.let { (icon, ring, letter) ->
                pin(ctx, icon.toIntOrNull()?.takeIf { it != 0 }, android.graphics.Color.parseColor(ring), letter)
            }
            else -> null
        }
    }.getOrNull()

    private fun pin(ctx: Context, icon: Int?, ring: Int, letter: String): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (SIZE * d).roundToInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val mid = s / 2f; val w = RING * d
        c.drawCircle(mid, mid, mid - w / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHARCOAL })
        c.drawCircle(mid, mid, mid - w / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; color = ring })
        val glyph = icon?.let { ctx.getDrawable(it)?.mutate() }
        if (glyph != null) {
            val g = (GLYPH * d).roundToInt(); val at = ((s - g) / 2f).roundToInt()
            glyph.setTint(android.graphics.Color.WHITE); glyph.setBounds(at, at, at + g, at + g); glyph.draw(c)
        } else text(c, letter, 16 * d, mid, mid)
        return bmp
    }

    private fun cluster(ctx: Context, n: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val label = PinArt.count(n)
        val paint = textPaint(15 * d)
        val disc = max(40 * d, paint.measureText(label) + 24 * d); val halo = 5 * d
        val s = ceil(disc + 2 * halo).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val mid = s / 2f
        c.drawCircle(mid, mid, mid, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(41, 255, 255, 255) })
        c.drawCircle(mid, mid, disc / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHARCOAL })
        c.drawCircle(mid, mid, disc / 2 - 0.75f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = android.graphics.Color.WHITE })
        text(c, label, 15 * d, mid, mid)
        return bmp
    }

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = size; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    private fun text(c: Canvas, s: String, size: Float, x: Float, y: Float) {
        val p = textPaint(size)
        c.drawText(s, x, y - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2, p)
    }
}

private const val SRC_PINS = "dp-pins"     // places and people, merged into clusters when they are close on screen
private const val SRC_HELP = "dp-help"     // people asking for help: never merged
private const val SRC_ME = "dp-me"
private const val LAYER_CLUSTERS = "dp-clusters"
private const val LAYER_PINS = "dp-pins"
private const val LAYER_HELP = "dp-help"
private const val MAX_ACTIONS = 25         // TalkBack actions on the map (Android has room for about 30 per view)

/**
 * The street map with pins. Tapping a pin opens the compass to it; tapping a count bubble zooms in on it. [onCenter]
 * gets the middle of the map after each move, or null while the map shows too large an area to save (zoomed out).
 * Everything on it is drawn on the phone, so with the saved area it works with no internet.
 */
@Composable
fun StreetMap(pins: List<Pin>, me: Spot?, modifier: Modifier, open: (Sheet) -> Unit, onCenter: (Spot?) -> Unit = {}) {
    val centerNow by rememberUpdatedState(onCenter)
    val openNow by rememberUpdatedState(open)
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var centered by remember { mutableStateOf(false) }
    val added = remember { mutableSetOf<String>() } // pin pictures given to the map
    val view = remember {
        MapView(ctx).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                // Count bubbles, and any pin picture the map dropped while unused, are drawn when the map asks for them.
                addOnStyleImageMissingListener { name -> m.style?.let { st -> PinPictures.image(ctx, name)?.let { st.addImage(name, it) } } }
                m.setStyle(Style.Builder().fromUri(OfflineMap.STYLE)) { style ->
                    style.addSource(GeoJsonSource(SRC_PINS, GeoJsonOptions().withCluster(true).withClusterMaxZoom(14).withClusterRadius(50)))
                    style.addSource(GeoJsonSource(SRC_HELP))
                    style.addSource(GeoJsonSource(SRC_ME))
                    fun icons(l: SymbolLayer, image: Expression) = l.withProperties(
                        PropertyFactory.iconImage(image), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true))
                    style.addLayer(icons(SymbolLayer(LAYER_PINS, SRC_PINS).withFilter(Expression.not(Expression.has("point_count"))), Expression.get("icon")))
                    style.addLayer(icons(SymbolLayer(LAYER_CLUSTERS, SRC_PINS).withFilter(Expression.has("point_count")),
                        Expression.concat(Expression.literal(PinArt.CLUSTER), Expression.toString(Expression.get("point_count")))))
                    style.addLayer(icons(SymbolLayer(LAYER_HELP, SRC_HELP), Expression.get("icon")))
                    // You: a white dot with a soft halo, on top.
                    style.addLayer(CircleLayer("dp-me-halo", SRC_ME).withProperties(
                        PropertyFactory.circleRadius(15f), PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleOpacity(0.18f)))
                    style.addLayer(CircleLayer("dp-me", SRC_ME).withProperties(
                        PropertyFactory.circleRadius(6.5f), PropertyFactory.circleColor("#FFFFFF"),
                        PropertyFactory.circleStrokeColor("#000000"), PropertyFactory.circleStrokeWidth(2f)))
                    map = m
                }
                m.addOnCameraIdleListener {
                    val cp = m.cameraPosition
                    centerNow(cp.target?.takeIf { cp.zoom >= 9 }?.let { t -> Spot(t.latitude, t.longitude) })
                }
                m.addOnMapClickListener { ll ->
                    val at = m.projection.toScreenLocation(ll)
                    val f = m.queryRenderedFeatures(at, LAYER_HELP, LAYER_PINS).firstOrNull()
                    if (f != null) {
                        val p = f.geometry() as? Point ?: return@addOnMapClickListener false
                        openNow(Sheet.Find(f.getStringProperty("title"), Spot(p.latitude(), p.longitude()), f.getNumberProperty("ts").toLong()))
                        return@addOnMapClickListener true
                    }
                    val c = m.queryRenderedFeatures(at, LAYER_CLUSTERS).firstOrNull() ?: return@addOnMapClickListener false
                    val p = c.geometry() as? Point ?: return@addOnMapClickListener false
                    val zoom = runCatching { m.style?.getSourceAs<GeoJsonSource>(SRC_PINS)?.getClusterExpansionZoom(c)?.toDouble() }.getOrNull()
                        ?: (m.cameraPosition.zoom + 2)
                    val to = CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude(), p.longitude()), zoom)
                    if (Motion.calm) m.moveCamera(to) else m.animateCamera(to, 350)
                    true
                }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart(); Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause(); Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(map, pins) {
        val st = map?.style ?: return@LaunchedEffect
        fun features(list: List<Pin>) = FeatureCollection.fromFeatures(list.map { p ->
            val name = PinArt.nameOf(p)
            if (added.add(name)) PinPictures.image(ctx, name)?.let { st.addImage(name, it) }
            Feature.fromGeometry(Point.fromLngLat(p.spot.lon, p.spot.lat)).apply {
                addStringProperty("title", p.title); addNumberProperty("ts", p.ts); addStringProperty("icon", name)
            }
        })
        st.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(features(pins.filter { !it.help }))
        st.getSourceAs<GeoJsonSource>(SRC_HELP)?.setGeoJson(features(pins.filter { it.help }))
    }
    LaunchedEffect(map, me) {
        val st = map?.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })))
    }
    // TalkBack: drawn pins are not items it can reach, so the map says what is on it and offers each pin as an action
    // (people asking for help first). The list shows every place too.
    val actions = remember { mutableListOf<Int>() }
    LaunchedEffect(pins) {
        actions.forEach { ViewCompat.removeAccessibilityAction(view, it) }; actions.clear()
        val places = pins.count { it.icon != null }; val people = pins.size - places; val help = pins.count { it.help }
        view.contentDescription = "Street map: " + listOfNotNull(
            "$places ${if (places == 1) "place" else "places"}", "$people ${if (people == 1) "person" else "people"}",
            if (help > 0) "$help asking for help" else null).joinToString(", ") +
            if (pins.isEmpty()) "." else ". Use actions to open one, or switch to List."
        (pins.filter { it.help } + pins.filter { !it.help }).take(MAX_ACTIONS).forEach { p ->
            val id = ViewCompat.addAccessibilityAction(view, if (p.help) "${p.title}, asking for help" else p.title) { _, _ ->
                openNow(Sheet.Find(p.title, p.spot, p.ts)); true
            }
            if (id != View.NO_ID) actions += id
        }
    }
    LaunchedEffect(map, pins, me) {
        val m = map ?: return@LaunchedEffect
        if (!centered) {
            val c = me ?: (OfflineMap.state as? OfflineMap.State.Ready)?.center ?: pins.firstOrNull()?.spot
            if (c != null) { centered = true; m.cameraPosition = CameraPosition.Builder().target(LatLng(c.lat, c.lon)).zoom(13.0).build() }
        }
    }
    AndroidView({ view }, modifier.clip(RoundedCornerShape(24.dp)))
}

/**
 * Save, update or delete the offline map, like the iPhone's OfflineMapCard. Saves around [me], or without a position
 * fix around [center], the middle of the map on screen (move the map to choose the area).
 */
@Composable
fun OfflineMapCard(me: Spot?, center: Spot? = null) {
    val ctx = LocalContext.current
    val around = me ?: center
    val where = if (me != null) "around you" else "around the middle of the map"
    Card {
        when (val s = OfflineMap.state) {
            OfflineMap.State.None -> {
                Txt("Save this map for the blackout", 18, FontWeight.Bold)
                Txt("Downloads the streets about 10 km $where (up to 50 MB) while you still have internet. Then the map works with none." +
                    if (me == null) " Zoom the map in on your area first." else "", 15, color = Theme.dim)
                PrimaryButton(if (around == null) "Zoom in on your area" else "Download map", enabled = around != null, icon = R.drawable.ic_download) { around?.let { OfflineMap.download(ctx, it) } }
            }
            is OfflineMap.State.Downloading -> {
                Txt("Saving the map… ${(s.progress * 100).toInt()}%", 18, FontWeight.Bold)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Theme.tile)) {
                    Box(Modifier.fillMaxWidth(s.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(Theme.text, CircleShape))
                }
                Txt("Keep the app open and the internet on.", 14, color = Theme.dim)
                GhostButton("Cancel") { OfflineMap.delete(ctx) }
            }
            is OfflineMap.State.Ready -> {
                Txt("Map saved", 18, FontWeight.Bold, Theme.safe)
                Txt("About 10 km around where you saved it, ${ago(s.ts)} · ${"%.1f".format(s.bytes / 1_000_000.0)} MB. It works with no internet.", 15, color = Theme.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (around != null) GhostButton(if (me != null) "Save around me again" else "Save this area instead") { OfflineMap.download(ctx, around) }
                    GhostButton("Delete") { OfflineMap.delete(ctx) }
                }
            }
            is OfflineMap.State.Failed -> {
                Txt("The map wasn't saved", 18, FontWeight.Bold, Theme.help)
                Txt(s.why, 15, color = Theme.dim)
                GhostButton("Try again", enabled = around != null) { around?.let { OfflineMap.download(ctx, it) } }
            }
        }
    }
}
