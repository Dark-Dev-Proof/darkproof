package it.blackout.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.fail
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * The app's screens without a window, driven by a script the way a person drives them: it reads what is written on
 * the screen, taps what it finds there by its words, and types into fields. Taps go in as touches at the place the
 * thing is drawn, so something covered or off screen can't be tapped. Frames are drawn as time passes, so sheets and
 * other animations finish. Everything runs on the calling thread.
 */
class Screen(private val width: Int = 2040, private val height: Int = 1290, density: Float = 1.5f, content: @Composable () -> Unit) : AutoCloseable { // 1360 x 860, the window's own size
    private class Here : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
        fun run() { while (true) (queue.poll() ?: return).run() }
    }
    private val here = Here()
    private var time = 0L
    private val scene = ImageComposeScene(width, height, Density(density), coroutineContext = here) { content() }

    init { settle() }

    /** Draws frames for [ms] of the app's time: long enough for a sheet to slide in. */
    fun settle(ms: Int = 700) { repeat(ms / 16) { here.run(); time += 16_000_000L; scene.render(time) }; here.run() }

    /** Waits for something that other threads bring about (the engine, a link), drawing frames meanwhile. */
    fun await(what: String, ms: Long = 15_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (true) { settle(48); if (ok()) return; if (System.currentTimeMillis() > end) fail("timed out waiting for: $what\non screen: ${texts().map { t -> secrets.fold(t) { shown, secret -> shown.replace(secret, "(hidden)") } }}"); Thread.sleep(40) }
    }

    /** Texts that a failed wait must not print with the rest of the screen, such as a group's code. */
    val secrets = ArrayList<String>()

    private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
    private fun words(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
    private fun onScreen(n: SemanticsNode) = n.boundsInWindow.let { it.width > 0 && it.height > 0 && it.center.x in 0f..width.toFloat() && it.center.y in 0f..height.toFloat() }

    /** Everything written on the screen now (and what icons are called), top to bottom. */
    fun texts(): List<String> = nodes().filter(::onScreen).sortedBy { it.boundsInWindow.top }.flatMap(::words).filter { it.isNotBlank() }

    fun has(text: String) = texts().any { text in it }

    /** Whether something on the screen says exactly [text]: a row's or a button's own label. */
    fun says(text: String) = texts().any { it == text }

    /** Layers from the top one down: a sheet or a dialog lies over the screen it was opened from. */
    private fun layers(): List<List<SemanticsNode>> = scene.semanticsOwners.toList().asReversed().map { o -> o.getAllSemanticsNodes(mergingEnabled = false).filter(::onScreen).sortedBy { it.boundsInWindow.top } }

    /** What says [text], in the topmost layer that has it: a person taps what is in front. An exact match counts before one that only contains it. */
    private fun find(text: String, nth: Int = 0): SemanticsNode {
        val hits = layers().firstNotNullOfOrNull { l -> l.filter { n -> words(n).any { it == text } }.ifEmpty { null } }
            ?: layers().firstNotNullOfOrNull { l -> l.filter { n -> words(n).any { text in it } }.ifEmpty { null } }.orEmpty()
        return hits.getOrNull(nth) ?: fail("nothing on screen says \"$text\"${if (nth > 0) " (${nth + 1} times)" else ""}\non screen: ${texts()}").let { error("") }
    }

    /** Taps what says [text] (exactly, else containing it); [nth] picks among several, from the top. */
    fun tap(text: String, nth: Int = 0) { tapAt(find(text, nth).boundsInWindow.center); settle() }

    /** Turns the mouse wheel over what says [text]: [lines] down, or up when negative. */
    fun wheel(text: String, lines: Int) {
        scene.sendPointerEvent(PointerEventType.Scroll, find(text).boundsInWindow.center, scrollDelta = Offset(0f, lines.toFloat()), type = PointerType.Mouse); settle()
    }

    /** Presses a key of the keyboard, in whatever was last clicked into. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun press(key: androidx.compose.ui.input.key.Key, shift: Boolean = false) {
        scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key, androidx.compose.ui.input.key.KeyEventType.KeyDown, isShiftPressed = shift)); here.run()
        scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key, androidx.compose.ui.input.key.KeyEventType.KeyUp, isShiftPressed = shift)); settle()
    }

    fun tapAt(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch); here.run(); time += 16_000_000L; scene.render(time)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Touch); here.run()
    }

    /** Puts [value] into the field whose hint or content is [hint]: as if typed over what was there. */
    fun type(hint: String, value: String) {
        val fields = layers().firstOrNull { l -> l.any { it.config.contains(SemanticsActions.SetText) } }.orEmpty().filter { it.config.contains(SemanticsActions.SetText) }
        fun below(n: SemanticsNode): List<SemanticsNode> = n.children.flatMap { listOf(it) + below(it) }
        val field = fields.firstOrNull { f -> (words(f) + below(f).flatMap(::words)).any { hint in it } }
            ?: fail("no field says \"$hint\"\nfields: ${fields.map { f -> words(f) + below(f).flatMap(::words) }}\non screen: ${texts()}").let { error("") }
        tapAt(field.boundsInWindow.center); settle(100) // focus first, as a person does
        field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)); settle(200)
    }

    /** Puts [value] into the [n]th field on the screen, counted from the top (0 is the first). */
    fun typeInto(n: Int, value: String) {
        val fields = layers().firstOrNull { l -> l.any { it.config.contains(SemanticsActions.SetText) } }.orEmpty().filter { it.config.contains(SemanticsActions.SetText) }
        val field = fields.getOrNull(n) ?: fail("there are only ${fields.size} fields\non screen: ${texts()}").let { error("") }
        tapAt(field.boundsInWindow.center); settle(100)
        field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)); settle(200)
    }

    /** Leaves a picture of the screen in [file]. */
    fun png(file: String): ByteArray { settle(64); return scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes.also { File(file).apply { parentFile.mkdirs(); writeBytes(it) } } }

    override fun close() { scene.close(); here.run() }
}
