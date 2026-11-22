package it.blackout.mesh

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DarkProof's look, the same as the iPhone (ios/Blackout/Theme.swift): white on pure black (black pixels are off on
 * OLED screens, which saves battery), soft charcoal cards, white capsule buttons. Color only means something:
 * green = safe or connected, red = help. The logo's outline is the one signature shape.
 */
object Theme {
    val bg = Color.Black
    val card = Color(0xFF131313)
    val tile = Color(0xFF212121)
    val line = Color(0xFF2B2B2B)
    val text = Color.White
    val dim = Color(0xFF949494)
    val faint = Color(0xFF888888) // at least 4.5:1 on black, cards and tiles: it still carries information
    val safe = Color(0xFF33D64A)
    val help = Color(0xFFFF453B)
}

/** The logo's silhouette: cut top-left corner, round right side, near-square bottom-left. */
fun vaultShape(cut: Dp = 42.dp, radius: Dp = 46.dp, small: Dp = 8.dp): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }; val r = with(density) { radius.toPx() }; val s = with(density) { small.toPx() }
        val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(c, 0f)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(s, h)
            arcTo(Rect(0f, h - 2 * s, 2 * s, h), 90f, 90f, false)
            lineTo(0f, c)
            close()
        })
    }
}

@Composable fun Ic(@DrawableRes id: Int, tint: Color = Theme.text, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Image(painterResource(id), null, modifier.size(size), colorFilter = ColorFilter.tint(tint))

@Composable fun Logo(size: Dp) =
    Image(painterResource(R.drawable.logo), "DarkProof", Modifier.size(size), colorFilter = ColorFilter.tint(Theme.text))

@Composable fun Txt(
    text: String, size: Int = 16, weight: FontWeight = FontWeight.Normal, color: Color = Theme.text,
    modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, lineHeight: Int = 0, align: TextAlign? = null,
) = Text(
    text, modifier, color = color, fontSize = size.sp, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    lineHeight = if (lineHeight > 0) lineHeight.sp else (size * 1.3).sp, textAlign = align,
)

/** Small section label above a field or group, in sentence case. */
@Composable fun Kicker(text: String, color: Color = Theme.dim) = Txt(text, 14, FontWeight.SemiBold, color)

/** Section title with an optional action on the right, e.g. "Your people · See all". */
@Composable fun SectionTitle(title: String, action: String? = null, tap: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, 20, FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) Txt(action, 15, FontWeight.SemiBold, Theme.dim, Modifier.tap(Press(down = 0.95f, shape = RoundedCornerShape(8.dp), dim = 0.35f, buzz = Buzz.Tap), onClick = tap).padding(vertical = 12.dp, horizontal = 4.dp))
    }
}

/** Big title at the top of a tab. [timer]: "1 day", the group's timer for disappearing messages, shown after the line while one is set. */
@Composable fun ScreenTitle(title: String, subtitle: String, timer: String? = null) {
    val wide = Here.wide // in a wide window this heads one pane of several, so it is smaller
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(title, if (wide) 24 else 32, FontWeight.Black, maxLines = 1, lineHeight = if (wide) 30 else 38)
        if (timer == null) Txt(subtitle, if (wide) 14 else 15, FontWeight.Medium, Theme.dim)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val size = if (wide) 14 else 15
            Txt(subtitle, size, FontWeight.Medium, Theme.dim, Modifier.weight(1f, fill = false), maxLines = 1)
            Txt("·", size, FontWeight.Medium, Theme.dim, Modifier.clearAndSetSemantics {})
            Row(Modifier.clearAndSetSemantics { contentDescription = "Messages disappear after $timer" }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Ic(R.drawable.ic_schedule, Theme.dim, 14.dp)
                Txt(timer, size, FontWeight.Medium, Theme.dim, maxLines = 1)
            }
        }
    }
}

/** A choice in the Ask page's menu: [label] says it as it is now, and [flip] changes it. */
class AiSwitch(val label: String, val on: Boolean, val flip: () -> Unit)

/** Filled capsule tag, e.g. "Here". */
@Composable fun Chip(text: String, color: Color = Theme.dim) =
    Txt(text, 13, FontWeight.SemiBold, color, Modifier
        .background(if (color == Theme.dim) Theme.tile else color.copy(alpha = 0.14f), CircleShape)
        .padding(horizontal = 10.dp, vertical = 5.dp))

/**
 * True inside a card or a sheet. On their charcoal the white glow under a main button reads as a grey smudge, so there
 * the button keeps its gradient and lit edge and has no glow ([PrimaryButton]); on the black page it glows.
 */
val LocalOnCharcoal = staticCompositionLocalOf { false }
@Composable private fun OnCharcoal(content: @Composable () -> Unit) = CompositionLocalProvider(LocalOnCharcoal provides true, content = content)

/** A soft charcoal card, no border. */
@Composable fun Card(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) = OnCharcoal {
    Column(modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(24.dp)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

// MARK: - How things move and answer a touch

/** One place for how things move. In calm mode nothing springs: it is simply there (Device.kt / standin: calmNow). */
object Motion {
    var calm by mutableStateOf(false)
    /** Going down under a finger: about 70 ms, no bounce. */
    fun sink(threshold: Float = 0.01f): FiniteAnimationSpec<Float> = if (calm) snap() else spring(1f, 3800f, threshold)
    /** Coming back up: about 250 ms with a small overshoot, the board's spring. */
    fun rise(threshold: Float = 0.01f): FiniteAnimationSpec<Float> = if (calm) snap() else spring(0.6f, 800f, threshold)
    /** Round buttons and dials: a little more give. */
    fun round(threshold: Float = 0.01f): FiniteAnimationSpec<Float> = if (calm) snap() else spring(0.55f, 600f, threshold)
    /** A switch's thumb, the tab bar's and a two-way switch's pill. */
    fun slide(): FiniteAnimationSpec<Float> = if (calm) snap() else spring(0.7f, 500f, 0.001f)
    fun <T> fade(ms: Int = 150): FiniteAnimationSpec<T> = if (calm) snap() else tween(ms)
}

/**
 * How a pressable thing answers a finger or a mouse: it sinks to [down] and springs back, darkens by [dim] (or lightens
 * by [light]) while held, lightens by [lift] under a mouse, and shows a white ring when a keyboard has moved onto it.
 * [shape] is the thing's own outline. [buzz] is what the phone's motor does at the press ([Buzz.None] for nothing).
 * It only draws, and makes no layer of its own (separate scale and alpha layers were once left blank after the keyboard closed).
 */
class Press(
    val down: Float = 0.97f, val shape: Shape = CircleShape, val dim: Float = 0.18f, val lift: Float = 0.05f,
    val buzz: Int = Buzz.Key, val bouncy: Boolean = false, val light: Float = 0f,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, this)
    override fun equals(other: Any?) = other is Press && other.down == down && other.shape == shape && other.dim == dim &&
        other.lift == lift && other.buzz == buzz && other.bouncy == bouncy && other.light == light
    override fun hashCode() = ((down.hashCode() * 31 + shape.hashCode()) * 31 + buzz) * 31 + dim.hashCode()

    private class Node(val source: InteractionSource, val p: Press) : Modifier.Node(), DrawModifierNode {
        // The travel is only a few hundredths, so the spring runs to a finer end than its default, or its overshoot never shows.
        val squash = Animatable(1f, 0.0005f)
        val shade = Animatable(0f); val glow = Animatable(0f)
        var focused by mutableStateOf(false)

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> {
                            Buzz.play(p.buzz)
                            launch { squash.animateTo(p.down, Motion.sink(0.0005f)) }; launch { shade.animateTo(1f, Motion.sink()) }
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            launch { squash.animateTo(1f, if (p.bouncy) Motion.round(0.0005f) else Motion.rise(0.0005f)) }
                            launch { shade.animateTo(0f, Motion.fade(160)) }
                        }
                        is HoverInteraction.Enter -> launch { glow.animateTo(1f, Motion.fade(120)) }
                        is HoverInteraction.Exit -> launch { glow.animateTo(0f, Motion.fade(200)) }
                        is FocusInteraction.Focus -> focused = true
                        is FocusInteraction.Unfocus -> focused = false
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val s = squash.value
            scale(s) {
                this@draw.drawContent()
                val g = glow.value; val sh = shade.value
                if (g > 0f || sh > 0f) {
                    val o = p.shape.createOutline(size, layoutDirection, this)
                    if (g > 0f && p.lift > 0f) drawOutline(o, Color.White, alpha = p.lift * g)
                    if (sh > 0f && p.dim > 0f) drawOutline(o, Color.Black, alpha = p.dim * sh)
                    if (sh > 0f && p.light > 0f) drawOutline(o, Color.White, alpha = p.light * sh)
                }
                // Only after a keyboard moved here (a click also focuses): a 2 dp ring, 2 dp away, the board's :focus-visible.
                if (focused && Here.keys) {
                    val gap = 3.dp.toPx()
                    inset(-gap, -gap, -gap, -gap) {
                        drawOutline(p.shape.createOutline(size, layoutDirection, this), Color.White, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
    }
}

/**
 * Everything that can be tapped goes through this: the hand cursor, the press effect, the role. Put it before the
 * fill ([background]) in the chain, so the fill sinks with the label.
 */
fun Modifier.tap(press: Press = Press(), enabled: Boolean = true, role: Role? = Role.Button, label: String? = null, onClick: () -> Unit): Modifier =
    pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = null, indication = press, enabled = enabled, onClickLabel = label, role = role, onClick = onClick)

/** The touch target ([tapArea]) and what is drawn ([pressed]) as two parts, for a small button with room around it. */
fun Modifier.tapArea(source: MutableInteractionSource, enabled: Boolean = true, role: Role? = Role.Button, label: String? = null, onClick: () -> Unit): Modifier =
    pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClickLabel = label, role = role, onClick = onClick)

fun Modifier.pressed(source: MutableInteractionSource, press: Press): Modifier = indication(source, press)

/** A 1 dp edge of light along the top of [shape], gone by mid-height. Not an outline: it is how glass catches light. */
fun Modifier.litEdge(shape: Shape, alpha: Float = 0.10f) = drawWithCache {
    val o = shape.createOutline(size, layoutDirection, this)
    val light = Brush.verticalGradient(0f to Color.White.copy(alpha = alpha), 0.5f to Color.Transparent, endY = size.height)
    val w = 1.dp.toPx()
    onDrawWithContent { drawContent(); drawOutline(o, light, style = Stroke(w)) }
}

/** A soft light around a capsule or circle, the board's `0 8px 22px -8px`: six faint copies, each a little larger. No blur pass. */
fun Modifier.glow(color: Color, alpha: Float = 0.22f, drop: Dp = 8.dp, inset: Dp = 8.dp) = drawBehind {
    val i0 = inset.toPx(); val d = drop.toPx()
    // Ten copies, each as faint as the next and 2.2 dp larger: where they overlap they add up, so the light fades evenly
    // from [alpha] at the middle to nothing about 22 dp out, with no visible steps.
    val n = 10; val step = 2.2.dp.toPx()
    for (i in 0 until n) {
        val g = i * step
        val w = size.width - 2 * i0 + 2 * g; val h = size.height - 2 * i0 + 2 * g
        if (w <= 0f || h <= 0f) continue
        drawRoundRect(color.copy(alpha = alpha / n), Offset(i0 - g, i0 - g + d), Size(w, h), CornerRadius(h / 2))
    }
}

/** A disabled thing is still there but far back: the page's black laid over all of it. No layer, so nothing is cut off. */
fun Modifier.dimmed(off: Boolean, shape: Shape) = if (!off) this else drawWithContent {
    drawContent(); drawOutline(shape.createOutline(size, layoutDirection, this), Color.Black, alpha = 0.65f)
}

private val WhiteFill = Brush.verticalGradient(listOf(Color.White, Color(0xFFE5E5E5)))
private val Glass = Color.White.copy(alpha = 0.09f)
private val Ink = Color(0xFF111111)

/** The one main action on a screen: full-width white capsule, black text. Its white glow only on the black page ([LocalOnCharcoal]). */
@Composable fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null, onClick: () -> Unit) =
    CapsuleButton(text, modifier, enabled, icon, WhiteFill, Ink, if (LocalOnCharcoal.current) null else Color.White, 0.22f, 8.dp, 0.6f, onClick)

/** An urgent main action ("Send “I need help”"): full-width red capsule, white text. */
@Composable fun DangerButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, onClick: () -> Unit) =
    CapsuleButton(text, modifier, true, icon, SolidColor(Theme.help), Color.White, Theme.help, 0.3f, 0.dp, 0.25f, onClick)

/** A second full-width action: a glass capsule, white text. */
@Composable fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null, color: Color = Theme.text, onClick: () -> Unit) =
    CapsuleButton(text, modifier, enabled, icon, SolidColor(Glass), color, null, 0f, 0.dp, 0.10f, onClick, Press(lift = 0.04f))

/** A way out that needs no weight ("Not now", "Keep it on"): words only, as wide as a button. */
@Composable fun TextAction(text: String, color: Color = Theme.dim, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).tap(Press(dim = 0.35f, lift = 0.04f)) { onClick() }, contentAlignment = Alignment.Center) {
        Txt(text, 16, FontWeight.SemiBold, color, maxLines = 1)
    }

@Composable private fun CapsuleButton(text: String, modifier: Modifier, enabled: Boolean, icon: Int?, fill: Brush, fg: Color, glow: Color?,
                                      glowAlpha: Float, drop: Dp, edge: Float, onClick: () -> Unit, press: Press = Press()) =
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (glow != null && enabled) Modifier.glow(glow, glowAlpha, drop, if (drop > 0.dp) 8.dp else 4.dp) else Modifier)
            .tap(press, enabled, onClick = onClick)
            .background(fill, CircleShape).litEdge(CircleShape, edge).dimmed(!enabled, CircleShape)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Ic(icon, fg, 20.dp); Spacer(Modifier.width(8.dp)) }
        Txt(text, 17, FontWeight.SemiBold, fg, maxLines = 1)
    }

/** A small inline action, e.g. "Switch" in a row: a compact glass capsule ([primary]: white), in a 48 dp touch target. */
@Composable fun GhostButton(text: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(Modifier.heightIn(min = 48.dp).tapArea(source, enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = 36.dp).pressed(source, Press(down = 0.95f, buzz = Buzz.Tap, lift = 0.05f))
            .background(if (primary) WhiteFill else SolidColor(Glass), CircleShape).litEdge(CircleShape, if (primary) 0.6f else 0.10f)
            .dimmed(!enabled, CircleShape).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center) { Txt(text, 15, FontWeight.SemiBold, if (primary) Ink else Theme.text, maxLines = 1) }
    }
}

/** A round icon button with its name under it: the chat's "+" tray, what can be done with a person. Color only for safe and help. */
@Composable fun CircleAction(@DrawableRes icon: Int, label: String, modifier: Modifier = Modifier, tint: Color = Theme.text, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(modifier.tapArea(source, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(56.dp)
            .then(if (tint == Theme.text) Modifier else Modifier.glow(tint, 0.3f, 0.dp, 6.dp))
            .pressed(source, Press(down = 0.88f, bouncy = true, lift = 0.06f))
            .background(Theme.tile, CircleShape).litEdge(CircleShape), contentAlignment = Alignment.Center) {
            Ic(icon, tint, if (icon == R.drawable.ic_sos) 30.dp else 24.dp)
        }
        Txt(label, 13, FontWeight.SemiBold, Theme.dim, maxLines = 2, lineHeight = 16, align = TextAlign.Center)
    }
}

/**
 * A person's sixteen emoji (spec "Person emoji"), eight to a line and always all of them: on a narrow screen or with
 * large text they get smaller, never cut off and never wrapped into other lines. Read out as their names.
 */
@Composable fun PersonEmoji(indices: List<Int>, size: Int = 30, modifier: Modifier = Modifier, center: Boolean = false) {
    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = Fingerprint.spoken(indices) }) {
        val density = LocalDensity.current
        // An emoji is drawn about 1.3 times as wide as its text size: eight of them must fit, with a little air between.
        val cell = minOf(maxWidth / 8, with(density) { size.sp.toDp() } * 1.5f)
        val sp = with(density) { (cell / 1.5f).toSp() }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            indices.chunked(8).forEach { line ->
                Row { line.forEach { i -> Text(Fingerprint.EMOJI[i], Modifier.width(cell), fontSize = sp, lineHeight = sp * 1.4f, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) } }
            }
        }
    }
}

/**
 * Round icon button, e.g. settings in a header. A small one keeps a 48 dp touch target around it. On a computer its
 * name ([desc], what TalkBack reads) also shows as a tooltip.
 */
@Composable fun RoundIcon(@DrawableRes id: Int, size: Dp = 42.dp, tint: Color = Theme.text, bg: Color = Theme.tile, desc: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    HoverTip(desc) {
        Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).tapArea(source, onClick = onClick).semantics { contentDescription = desc }, // TalkBack reads it
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(size).pressed(source, Press(down = 0.90f, bouncy = true, buzz = Buzz.Tap, lift = 0.06f))
                .background(bg, CircleShape).litEdge(CircleShape, if (bg == Color.White) 0.6f else 0.10f),
                contentAlignment = Alignment.Center) { Ic(id, tint, size * 0.45f) }
        }
    }
}

/** Two-way switch in a capsule, e.g. List | Map: a white pill slides to the side that is chosen. */
@Composable fun PillSwitch(left: String, right: String, isRight: Boolean, set: (Boolean) -> Unit) {
    val at by animateFloatAsState(if (isRight) 1f else 0f, Motion.slide(), label = "pill")
    Row(Modifier.fillMaxWidth().background(Theme.tile, CircleShape).padding(4.dp).drawBehind {
        val gap = 4.dp.toPx(); val w = (size.width - gap) / 2
        val x = at * (w + gap)
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x, 1.dp.toPx()), Size(w, size.height), CornerRadius(size.height / 2)) // its small shadow
        drawRoundRect(Color(0xFFFAFAFA), Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2))
    }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((title, r) in listOf(left to false, right to true)) {
            val on = isRight == r
            Box(Modifier.weight(1f).heightIn(min = 40.dp).pointerHoverIcon(PointerIcon.Hand)
                .selectable(on, interactionSource = null, indication = Press(down = 0.96f, dim = 0f, lift = 0.04f, buzz = Buzz.None), role = Role.Tab) {
                    if (!on) { Buzz.play(Buzz.Tick); set(r) }
                }, contentAlignment = Alignment.Center) {
                Txt(title, 15, FontWeight.SemiBold, if (on) Ink else Theme.dim, maxLines = 1)
            }
        }
    }
}

/** The on/off part of a row: the board's switch, a white thumb that slides over a grey or green track. The row around it is what is tapped. */
@Composable fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val x by animateFloatAsState(if (on) 1f else 0f, Motion.slide(), label = "thumb")
    val track by animateColorAsState(if (on) Theme.safe else Color(0xFF3A3A3C), Motion.fade(250), label = "track")
    Canvas(modifier.size(44.dp, 26.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val r = 11.dp.toPx(); val c = Offset(2.dp.toPx() + r + 18.dp.toPx() * x, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.25f), r + 0.5.dp.toPx(), c + Offset(0f, 1.dp.toPx())) // the thumb's small shadow
        drawCircle(Color.White, r, c)
    }
}

/**
 * A button that acts only after it was held, for the one thing that can't be undone ("Erase everything"): it fills
 * while held, and letting go early does nothing but [onTap] (say "Keep holding"). The hold is timed by the touch itself,
 * never by the fill, so with the system's animations removed a tap still can't do it. People who can't hold (TalkBack, a
 * switch, a keyboard's Enter or Space) get [ask]: the caller asks once more. [action]: what TalkBack says it does.
 */
@Composable fun HoldButton(text: String, modifier: Modifier = Modifier, action: String = text, onTap: () -> Unit = {}, ask: () -> Unit, onDone: () -> Unit) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val tapped by rememberUpdatedState(onTap); val asked by rememberUpdatedState(ask); val done by rememberUpdatedState(onDone)
    Box(modifier.heightIn(min = 56.dp).glow(Theme.help, 0.3f, 0.dp, 4.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    Buzz.play(Buzz.Hold)
                    val calm = Motion.calm
                    val time = if (calm) HOLD_CALM_MS else HOLD_MS
                    val grow = scope.launch { fill.snapTo(0f); if (!calm) fill.animateTo(1f, tween(time.toInt(), easing = LinearEasing)) }
                    // true: the finger left, or the touch was taken away, before the time was up. null: the time ran out, still held.
                    // (waitForUpOrCancellation itself gives null for a cancelled touch, the same as a timeout: read it in here.)
                    val leftEarly = withTimeoutOrNull(time) { waitForUpOrCancellation(); true } ?: false
                    grow.cancel()
                    if (leftEarly) { scope.launch { fill.animateTo(0f, Motion.fade(250)) }; tapped() }
                    else { scope.launch { fill.snapTo(1f) }; Buzz.play(Buzz.Confirm); done() }
                }
            }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)) { asked(); true } else false }
            .focusable(interactionSource = source).hoverable(source)
            .clearAndSetSemantics { contentDescription = text; role = Role.Button; onClick(action) { asked(); true } }
            .pressed(source, Press(down = 1f, dim = 0f, lift = 0.06f, buzz = Buzz.None)) // a computer's hover and focus ring; the fill is the press
            .background(Theme.help, CircleShape).litEdge(CircleShape, 0.25f)
            .drawWithContent {
                val f = fill.value
                if (f > 0f) {
                    val clip = Path().apply { addOutline(CircleShape.createOutline(size, layoutDirection, this@drawWithContent)) }
                    clipPath(clip) { drawRect(Color.Black.copy(alpha = 0.34f), size = Size(size.width * f, size.height)) }
                }
                drawContent()
            },
        contentAlignment = Alignment.Center) {
        Txt(text, 17, FontWeight.SemiBold, Color.White, maxLines = 1)
    }
}

/** How long a hold-to-confirm button has to be held, and in calm mode, where it shows no fill (the board's times). */
const val HOLD_MS = 1300L
const val HOLD_CALM_MS = 400L

/** Label above a dark rounded text field. */
@Composable fun InputField(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String = "",
    singleLine: Boolean = true, keyboard: KeyboardOptions = KeyboardOptions.Default, modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(label)
        TextBox(value, onChange, placeholder, singleLine, keyboard, Modifier.fillMaxWidth())
    }
}

@Composable fun TextBox(
    value: String, onChange: (String) -> Unit, placeholder: String, singleLine: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp),
    onEnter: (() -> Unit)? = null, // what a keyboard's Enter key does, where it should not start a new line
) = BasicTextField(
    value, onChange, if (onEnter == null) modifier else modifier.onPreviewKeyEvent { e ->
        val enter = (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed // with Shift it starts a new line
        if (enter && e.type == KeyEventType.KeyDown) onEnter()
        enter
    }, singleLine = singleLine, maxLines = if (singleLine) 1 else 5, keyboardOptions = keyboard,
    textStyle = TextStyle(color = Theme.text, fontSize = 17.sp), cursorBrush = SolidColor(Theme.text),
    decorationBox = { inner ->
        Box(Modifier.background(Theme.tile, shape).padding(horizontal = 16.dp, vertical = 15.dp)) {
            if (value.isEmpty()) Txt(placeholder, 17, color = Theme.faint, maxLines = 1)
            inner()
        }
    },
)

/** On/off row with an explanation under it. */
@Composable fun SwitchRow(title: String, on: Boolean, set: (Boolean) -> Unit, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The whole line is the switch, as in ToggleRow.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
            .toggleable(on, interactionSource = null, indication = Press(down = 1f, shape = RoundedCornerShape(14.dp), dim = 0f, lift = 0.04f, light = 0.05f, buzz = Buzz.None),
                role = Role.Switch) { Buzz.play(if (it) Buzz.On else Buzz.Off); set(it) },
            verticalAlignment = Alignment.CenterVertically) {
            Txt(title, 16, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Toggle(on)
        }
        if (note != null) Txt(note, 14, color = Theme.dim)
    }
}

/** What sheets are made of: a little lighter than the black page, with a lit top edge (the board's .sheet). */
val SheetColor = Color(0xFF171718)
private val SheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

/** The grab handle at the top of a sheet. */
@Composable private fun Grab(top: Dp = 8.dp, bottom: Dp = 12.dp) =
    Box(Modifier.fillMaxWidth().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 5.dp).background(Color(0xFF3A3A3C), CircleShape))
    }

/** A panel in the middle of a wide window: it comes in with a short scale and fade (none in calm mode). */
@Composable private fun Panel(maxWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    val shown = remember { Animatable(if (Motion.calm) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.fade(180)) }
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.padding(vertical = 40.dp).widthIn(max = maxWidth).fillMaxWidth()
        .graphicsLayer { alpha = shown.value; val sc = 0.96f + 0.04f * shown.value; scaleX = sc; scaleY = sc }
        .background(SheetColor, shape).litEdge(shape)) { OnCharcoal { content() } }
}

/**
 * A full-height sheet with a title and a close button, like the iPhone's SheetPage. With [onBack] it shows a page
 * inside the sheet (Settings > Groups, …) with a back arrow; [scrollKey] starts each such page at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SheetPage(title: String, onClose: () -> Unit, onBack: (() -> Unit)? = null, scrollKey: Any? = null,
                          content: @Composable ColumnScope.() -> Unit) {
    if (Here.wide) { WidePage(title, onClose, onBack, scrollKey, content); return }
    ModalBottomSheet(onClose, modifier = Modifier.litEdge(SheetShape), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor, contentColor = Theme.text, scrimColor = Color.Black.copy(alpha = 0.55f), dragHandle = null,
        shape = SheetShape) { OnCharcoal {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp)) {
            Grab(bottom = 6.dp) // below the status bar: a full-height sheet reaches the top of the screen
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) { RoundIcon(R.drawable.ic_arrow_back, 36.dp, desc = "Back", onClick = onBack); Spacer(Modifier.width(12.dp)) }
                Txt(title, if (onBack != null) 22 else 26, FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1)
                RoundIcon(R.drawable.ic_close, 36.dp, desc = "Close", onClick = onClose)
            }
            Spacer(Modifier.height(16.dp))
            val scroll = key(scrollKey) { rememberScrollState() }
            // The room under the last button is inside the scroll: a scroll cuts off what is drawn outside it, a button's glow too.
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).imePadding().navigationBarsPadding().padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            Spacer(Modifier.height(2.dp))
        }
    } }
}

/** [SheetPage] in a wide window: the same page as a panel in the middle, over the dimmed window. */
@Composable private fun WidePage(title: String, onClose: () -> Unit, onBack: (() -> Unit)?, scrollKey: Any?, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(520.dp) {
          Column(Modifier.padding(horizontal = 24.dp).padding(top = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) { RoundIcon(R.drawable.ic_arrow_back, 36.dp, desc = "Back", onClick = onBack); Spacer(Modifier.width(12.dp)) }
                Txt(title, if (onBack != null) 22 else 26, FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1)
                RoundIcon(R.drawable.ic_close, 36.dp, desc = "Close", onClick = onClose)
            }
            Spacer(Modifier.height(16.dp))
            val scroll = key(scrollKey) { rememberScrollState() }
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            Spacer(Modifier.height(2.dp))
          }
        }
    }
}

/** A short sheet from below, for a few choices or one question; in a wide window, a small panel in the middle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SmallSheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (Here.wide) {
        androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Panel(440.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
        return
    }
    ModalBottomSheet(onClose, modifier = Modifier.litEdge(SheetShape), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor, contentColor = Theme.text, scrimColor = Color.Black.copy(alpha = 0.55f), shape = SheetShape,
        dragHandle = { Grab(top = 8.dp, bottom = 14.dp) }) {
        OnCharcoal {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

/**
 * One question in a [SmallSheet]: the main answer is the white button, the other one sits under it, in red if it takes
 * something away. [more]: what comes between the text and the buttons.
 */
@Composable fun ChoiceSheet(title: String, text: String, main: String, other: String?, @DrawableRes icon: Int? = null, iconTint: Color = Theme.text,
                            otherDanger: Boolean = false, onMain: () -> Unit, onOther: () -> Unit = {}, onClose: () -> Unit, more: @Composable ColumnScope.() -> Unit = {}) =
    SmallSheet(onClose) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Box(Modifier.size(64.dp).background(if (iconTint == Theme.text) Theme.tile else iconTint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) { Ic(icon, iconTint, 30.dp) }
            Txt(title, 21, FontWeight.Black, align = TextAlign.Center, lineHeight = 26)
            Txt(text, 15, color = Theme.dim, align = TextAlign.Center)
        }
        more()
        PrimaryButton(main, onClick = onMain)
        if (other != null) SecondaryButton(other, color = if (otherDanger) Theme.help else Theme.text, onClick = onOther)
    }

/** Settings lines on one charcoal card. */
@Composable fun RowCard(content: @Composable ColumnScope.() -> Unit) = OnCharcoal {
    Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(24.dp)).padding(horizontal = 6.dp, vertical = 6.dp), content = content)
}

/** Icon, title and an optional line under it, for [LinkRow] and [ToggleRow]. */
@Composable private fun RowScope.RowText(@DrawableRes icon: Int, title: String, detail: String?, detailColor: Color, color: Color = Theme.text) {
    Ic(icon, color, 22.dp); Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(title, 17, FontWeight.SemiBold, color, maxLines = 2)
        if (!detail.isNullOrEmpty()) Txt(detail, 14, color = detailColor)
    }
}

/**
 * A line that opens a page or does one thing: the current value on the right, then a chevron. [dot]: a green dot before
 * the value, for something that is on. [danger]: in red, for what can't be undone.
 */
@Composable fun LinkRow(@DrawableRes icon: Int, title: String, value: String? = null, detail: String? = null, dot: Boolean = false, danger: Boolean = false, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).tap(RowPress, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(icon, title, detail, Theme.dim, if (danger) Theme.help else Theme.text)
        if (dot) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(8.dp).background(Theme.safe, CircleShape)) }
        if (!value.isNullOrEmpty()) { Spacer(Modifier.width(if (dot) 6.dp else 8.dp)); Txt(value, 15, color = Theme.dim, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp)) }
        Spacer(Modifier.width(4.dp))
        Ic(R.drawable.ic_chevron_right, Theme.faint, 22.dp)
    }

/** An on/off line, with what it does (or what is happening) under the title. The whole line toggles. */
@Composable fun ToggleRow(@DrawableRes icon: Int, title: String, on: Boolean, detail: String? = null, detailColor: Color = Theme.dim, set: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pointerHoverIcon(PointerIcon.Hand)
        .toggleable(on, interactionSource = null, indication = RowPress, role = Role.Switch) { Buzz.play(if (it) Buzz.On else Buzz.Off); set(it) }
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(icon, title, detail, detailColor)
        Spacer(Modifier.width(8.dp))
        Toggle(on)
    }

/** A line on a charcoal card: it doesn't shrink, it lights up a little while pressed (and under a mouse). */
val RowPress = Press(down = 1f, shape = RoundedCornerShape(18.dp), dim = 0f, lift = 0.05f, light = 0.05f, buzz = Buzz.None)

/** Simple confirm dialog in the app's colors. */
@Composable fun Confirm(title: String, text: String, yes: String, danger: Boolean = false, onYes: () -> Unit, onNo: () -> Unit) =
    AlertDialog(onNo, containerColor = Theme.card, titleContentColor = Theme.text, textContentColor = Theme.dim,
        title = { Txt(title, 20, FontWeight.Bold) }, text = { Txt(text, 15, color = Theme.dim) },
        confirmButton = { TextButton(onYes) { Txt(yes, 16, FontWeight.Bold, if (danger) Theme.help else Theme.text) } },
        dismissButton = { TextButton(onNo) { Txt("Cancel", 16, FontWeight.SemiBold, Theme.dim) } })

/** A message with one button, in the app's colors. */
@Composable fun Info(title: String, text: String, ok: String = "OK", onOk: () -> Unit) =
    AlertDialog(onOk, containerColor = Theme.card, titleContentColor = Theme.text, textContentColor = Theme.dim,
        title = { Txt(title, 20, FontWeight.Bold) }, text = { Txt(text, 15, color = Theme.dim) },
        confirmButton = { TextButton(onOk) { Txt(ok, 16, FontWeight.Bold) } })

/** Battery level, red at 20% or less. */
@Composable fun BatteryLabel(percent: Int) {
    val low = percent <= 20
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Ic(R.drawable.ic_battery_full, if (low) Theme.help else Theme.dim, 16.dp)
        Txt("$percent%", 15, FontWeight.Medium, if (low) Theme.help else Theme.dim)
    }
}
