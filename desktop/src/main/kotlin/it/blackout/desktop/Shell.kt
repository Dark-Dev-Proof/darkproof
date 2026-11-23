package it.blackout.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import it.blackout.mesh.Buzz
import it.blackout.mesh.Here
import it.blackout.mesh.HoverTip
import it.blackout.mesh.Motion
import it.blackout.mesh.Press
import it.blackout.mesh.litEdge
import it.blackout.mesh.pressed
import it.blackout.mesh.tap
import it.blackout.mesh.tapArea
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.blackout.mesh.AppState
import it.blackout.mesh.AsciiBackdrop
import it.blackout.mesh.AsciiBackground
import it.blackout.mesh.AskTab
import it.blackout.mesh.BgBand
import it.blackout.mesh.ChatTab
import it.blackout.mesh.Groups
import it.blackout.mesh.Ic
import it.blackout.mesh.Kicker
import it.blackout.mesh.Links
import it.blackout.mesh.LocalAi
import it.blackout.mesh.Logo
import it.blackout.mesh.MainActivity
import it.blackout.mesh.GroupTimer
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.Message
import it.blackout.mesh.Moderation
import it.blackout.mesh.Overlays
import it.blackout.mesh.PeopleTab
import it.blackout.mesh.PlacesTab
import it.blackout.mesh.R
import it.blackout.mesh.ReadMarks
import it.blackout.mesh.RoundIcon
import it.blackout.mesh.SavedGroup
import it.blackout.mesh.SetupFlow
import it.blackout.mesh.Sheet
import it.blackout.mesh.Theme
import it.blackout.mesh.Txt
import it.blackout.mesh.linkLines
import it.blackout.mesh.phonesInRange
import it.blackout.mesh.vaultShape

// The desktop's own layout, the way desktop chat apps are laid out: a rail of groups, a sidebar with the link status
// and the channels, the chat in the middle, and the people at the right. What fills those panes is the phones' own
// screens and sheets (ChatTab, PeopleTab, PlacesTab, AskTab, Overlays), on the phones' engine and with the phones'
// look (Theme): black, charcoal cards, colour only for safe and help, the logo's shape on the status card.

private val RAIL = 76.dp
private val SIDEBAR = 264.dp
private val PEOPLE = 440.dp // a phone's width and a little more: the people screen is the phones' own
private val PAGE = 720.dp // the widest a page of cards and buttons gets: wider, and its buttons become bars
private val TOP = 12.dp // above every pane's heading, on top of the 8 the phones' screens leave themselves
private const val SIDE_TEXTURE = 0.25f // how much of the background texture shows behind the sidebar: its rows are text on black
private const val PEOPLE_TEXTURE = 0.35f // and behind the people at the right

/** Everything in the window. */
@Composable
fun DesktopApp(a: MainActivity) {
    val s = a.state
    MaterialTheme(colorScheme = darkColorScheme(background = Theme.bg, surface = Theme.bg, primary = Theme.text, onPrimary = Color.Black)) {
        // The focus ring shows only after the keyboard moved the focus (Tab, arrows), never after a click: a click focuses too.
        Box(Modifier.fillMaxSize().background(Theme.bg)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key in FocusKeys) Here.keys = true
                false
            }
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) Here.keys = false } }
            }) {
            val g = s.group
            if (g == null) Welcome(s, a) else Panes(s, a, g)
        }
    }
}

/** The keys that move the focus from one thing to the next. */
private val FocusKeys = setOf(Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/** Before the first setup: the phones' own setup, as one column in the middle of the window. */
@Composable
private fun Welcome(s: AppState, a: MainActivity) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(460.dp).fillMaxHeight().padding(vertical = 24.dp)) {
            SetupFlow(s.invite, askName = true, myName = s.name, dropInvite = { s.invite = null }) { name, group, created ->
                a.started(name, group); s.invite = null
                if (created) s.sheet = Sheet.Invite
            }
        }
    }
}

@Composable
private fun Panes(s: AppState, a: MainActivity, g: SavedGroup) {
    val tick = s.tick; val moderated = s.moderated
    val messages = remember(tick, moderated) { MeshEngine.snapshot().filter { Moderation.visible(a, it) } } // blocked and reported hidden everywhere
    val all = remember(tick, moderated) { MeshEngine.snapshotAll().filter { Moderation.visible(a, it) } }
    val phones = remember(tick) { phonesInRange() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val peopleAtSide = maxWidth >= 1200.dp // a narrower window shows People as a page of its own
        // The phones' tabs are 0 Home, 1 Chat, 2 Places, 3 People, 4 Ask. Here Chat is the home, and People a pane.
        LaunchedEffect(s.tab, peopleAtSide) { if (s.tab == 0 || (s.tab == 3 && peopleAtSide)) s.tab = 1 }
        LaunchedEffect(g.code) { s.tab = 1; s.everyone = false } // another group, joined or switched to, opens on its chat
        val onGroupChat = s.tab == 1 && !s.everyone
        LaunchedEffect(onGroupChat, all, g) { if (onGroupChat) all.lastOrNull { it.group == g.code }?.let { ReadMarks.markRead(a, g.code, it.ts) } }
        val unread = remember(all, onGroupChat, g) { if (onGroupChat) 0 else ReadMarks.unread(a, all, g.code) }

        // The background texture the person chose (Settings > Background): full behind the chat, faint behind the sidebar
        // and the people, none behind the rail. Drawn once per window size, not per frame (AsciiBackground.kt).
        val chatFrom = RAIL + SIDEBAR + 2.dp; val chatTo = if (peopleAtSide) maxWidth - PEOPLE - 1.dp else maxWidth
        AsciiBackdrop(AsciiBackground.style(a), Modifier.fillMaxSize(), focus = chatFrom to chatTo,
            bands = listOf(BgBand(0.dp, RAIL + 1.dp, 0f), BgBand(RAIL + 1.dp, chatFrom, SIDE_TEXTURE), BgBand(chatTo, maxWidth, PEOPLE_TEXTURE)))

        Row(Modifier.fillMaxSize()) {
            Rail(s, a, g, all)
            Line()
            Sidebar(s, a, g, phones, unread, peopleAtSide)
            Line()
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = TOP)) {
                when (s.tab) {
                    2 -> Box(Modifier.widthIn(max = PAGE)) { PlacesTab(s, messages) { s.sheet = it } }
                    3 -> Box(Modifier.widthIn(max = PAGE)) { PeopleTab(s, a, g, messages, phones) { s.sheet = it } }
                    4 -> {
                        // Asked once, when there is a model to answer and this copy has an engine for the graphics card
                        if (LocalAi.hasGraphicsEngine && !LocalAi.graphicsAsked && LocalAi.states[LocalAi.selected] == LocalAi.State.Ready) GraphicsQuestion()
                        Box(Modifier.weight(1f)) { AskTab(g, messages) }
                    }
                    else -> {
                        // The group's timer for disappearing messages, while one is set: the phones show it next to the reach line.
                        val timer = remember(s.tick, s.everyone, g) { if (s.everyone) null else MeshEngine.timer(g.code).seconds?.takeIf { it > 0 }?.let(GroupTimer::label) }
                        PaneTitle(if (s.everyone) "Everyone" else g.name) {
                            if (timer != null) Chip(timer, R.drawable.ic_schedule) { s.sheet = Sheet.Timer }
                            if (!s.everyone) Chip("Security", R.drawable.ic_shield) { s.sheet = Sheet.Security } // the phones' round shield in the chat's header
                            if (!s.everyone) Chip("Invite", R.drawable.ic_qr_code_2) { s.sheet = Sheet.Invite }
                        }
                        ChatTab(s, g, messages, phones) { s.sheet = it }
                    }
                }
            }
            if (peopleAtSide) {
                Line()
                Box(Modifier.width(PEOPLE).fillMaxHeight().padding(top = TOP)) { PeopleTab(s, a, g, messages, phones) { s.sheet = it } }
            }
        }
    }
    Overlays(s, a, g, messages, all)
}

/** Whether the graphics card should do the AI's work: the person's call, since only they know their computer. */
@Composable
private fun GraphicsQuestion() {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Theme.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Use the graphics card for the AI?", 17, FontWeight.Bold)
        Txt("Answers come faster if this computer has a graphics card the AI can use. If it can't use it, DarkProof goes back to the processor by itself. You can change this later in this page's menu.", 14, color = Theme.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Use the graphics card", R.drawable.ic_auto_awesome) { LocalAi.chooseGraphics(true) }
            Chip("Not now", R.drawable.ic_close) { LocalAi.chooseGraphics(false) }
        }
    }
}

/** A hairline between panes: on black, panes are told apart by a line, not by shades of grey. */
@Composable
private fun Line(across: Boolean = false) =
    Box(if (across) Modifier.fillMaxWidth().height(1.dp).background(Theme.line) else Modifier.fillMaxHeight().width(1.dp).background(Theme.line))

/** The groups, one round button each, and what belongs to the whole app: another group, settings. */
@Composable
private fun Rail(s: AppState, a: MainActivity, g: SavedGroup, all: List<Message>) {
    val groups = remember(s.tick, g) { Groups.all(a) }
    Column(Modifier.width(RAIL).fillMaxHeight().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Logo(38.dp)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (x in groups) {
                val here = x.code == g.code
                val news = !here && remember(all, x) { ReadMarks.unread(a, all, x.code) } > 0
                val name = if (here) "${x.name}, the group on screen" else "Switch to ${x.name}"
                HoverTip(name) { RailGroup(x.name, here, news, name) { if (here) { s.tab = 1; s.everyone = false } else a.switchTo(x) } }
            }
            RoundIcon(R.drawable.ic_add, 48.dp, desc = "Join or start another group") { s.sheet = Sheet.NewGroup }
        }
    }
}

/**
 * One group on the rail: a circle that rounds into a softer square for the group on screen, and on the way there under the
 * mouse. A white dot: something new in it.
 */
@Composable
private fun RailGroup(name: String, here: Boolean, news: Boolean, desc: String, go: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val corner = animateFloatAsState(if (here) 33f else if (hovered) 40f else 50f, Motion.slide(), label = "rail")
    val shape = RoundedCornerShape(percent = corner.value.roundToInt().coerceIn(0, 50))
    // The press effect keeps one shape that follows the corner as it moves: a new effect each frame would lose its spring.
    val press = remember {
        val follows = object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                RoundedCornerShape(percent = corner.value.roundToInt().coerceIn(0, 50)).createOutline(size, layoutDirection, density)
        }
        Press(down = 0.92f, shape = follows, dim = 0.15f, lift = 0.06f, bouncy = true, buzz = Buzz.None)
    }
    Box(contentAlignment = Alignment.TopEnd) {
        Box(Modifier.size(48.dp).pointerHoverIcon(PointerIcon.Hand)
            .clickable(source, press, role = Role.Button, onClick = go)
            .semantics { contentDescription = desc }
            .background(if (here) Theme.text else Theme.tile, shape).litEdge(shape, if (here) 0.6f else 0.10f),
            contentAlignment = Alignment.Center) {
            Txt(name.trim().take(1).uppercase().ifEmpty { "G" }, 19, FontWeight.Black, if (here) Color.Black else Theme.text)
        }
        if (news) Box(Modifier.size(12.dp).background(Theme.text, CircleShape).border(2.dp, Theme.bg, CircleShape))
    }
}

/** The group on screen: how it is linked, its channels, and who is using this computer. */
@Composable
private fun Sidebar(s: AppState, a: MainActivity, g: SavedGroup, phones: Int, unread: Int, peopleAtSide: Boolean) {
    Column(Modifier.width(SIDEBAR).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt("DarkProof", 24, FontWeight.Black, maxLines = 1, lineHeight = 30, modifier = Modifier.padding(start = 4.dp, top = TOP - 8.dp, bottom = 12.dp))
        StatusCard(s, phones)
        Spacer(Modifier.height(10.dp))
        Kicker("Channels")
        NavRow(R.drawable.ic_group, g.name, s.tab == 1 && !s.everyone, unread) { s.tab = 1; s.everyone = false }
        NavRow(R.drawable.ic_public, "Everyone", s.tab == 1 && s.everyone) { s.tab = 1; s.everyone = true }
        Spacer(Modifier.height(10.dp))
        Kicker("More")
        if (!peopleAtSide) NavRow(R.drawable.ic_person, "People", s.tab == 3) { s.tab = 3 }
        NavRow(R.drawable.ic_location_on, "Places", s.tab == 2) { s.tab = 2 }
        NavRow(R.drawable.ic_auto_awesome, "Ask", s.tab == 4) { s.tab = 4 }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().tap(SidePress) { s.sheet = Sheet.Settings }.background(Theme.card, RoundedCornerShape(20.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Txt(s.name.trim().take(1).uppercase().ifEmpty { "?" }, 17, FontWeight.Black) }
            Column(Modifier.weight(1f)) {
                Txt(s.name, 15, FontWeight.SemiBold, maxLines = 1)
                Txt("This computer", 13, color = Theme.dim, maxLines = 1)
            }
            Box(Modifier.semantics { contentDescription = "Settings" }) { Ic(R.drawable.ic_settings, Theme.dim, 20.dp) }
        }
    }
}

/** How this computer is linked right now: the phones' status card, with the logo's shape. Opens Connections. */
@Composable
private fun StatusCard(s: AppState, phones: Int) {
    val links = remember(s.tick) { Links.now() }
    val lines = remember(s.tick) { linkLines() }
    val shape = remember { vaultShape(cut = 26.dp, radius = 30.dp) }
    val press = remember(shape) { Press(down = 0.98f, shape = shape, dim = 0f, lift = 0.05f, light = 0.05f, buzz = Buzz.None) }
    Column(Modifier.fillMaxWidth().tap(press) { s.sheet = Sheet.Radio }.background(Theme.card, shape).padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).background(if (links.any) Theme.safe else Theme.faint, CircleShape))
            Txt(if (links.any) "Connected" else "Looking for phones", 15, FontWeight.SemiBold, if (links.any) Theme.safe else Theme.dim)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("$phones", 52, FontWeight.Black, lineHeight = 56)
            Txt(if (phones == 1) "phone\nin range" else "phones\nin range", 16, FontWeight.SemiBold, Theme.dim, Modifier.padding(bottom = 9.dp), lineHeight = 19)
        }
        for (l in lines) Txt(l, 13, color = Theme.dim, maxLines = 2)
    }
}

private val SidePress = Press(down = 0.98f, shape = RoundedCornerShape(20.dp), dim = 0f, lift = 0.05f, light = 0.05f, buzz = Buzz.None)
private val NavPress = Press(down = 0.98f, shape = RoundedCornerShape(14.dp), dim = 0f, lift = 0.05f, light = 0.05f, buzz = Buzz.None)

@Composable
private fun NavRow(icon: Int, label: String, here: Boolean, badge: Int = 0, go: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(NavPress, onClick = go).background(if (here) Theme.tile else Color.Transparent, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ic(icon, if (here) Theme.text else Theme.dim, 20.dp)
        Txt(label, 16, if (here) FontWeight.SemiBold else FontWeight.Normal, if (here) Theme.text else Theme.dim, Modifier.weight(1f), maxLines = 1)
        if (badge > 0) Box(Modifier.background(Theme.text, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) { Txt(if (badge > 99) "99+" else "$badge", 12, FontWeight.Bold, Color.Black) }
    }
}

/** The name of the chat in the middle pane, written like the phones' screens write theirs, and what can be done with it. */
@Composable
private fun PaneTitle(title: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(title, 24, FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1, lineHeight = 30)
        actions()
    }
}

@Composable
private fun Chip(label: String, icon: Int, tap: () -> Unit) {
    // A small glass capsule (the phones' GhostButton), with room around it to be easy to hit.
    val source = remember { MutableInteractionSource() }
    Box(Modifier.heightIn(min = 40.dp).tapArea(source, onClick = tap), contentAlignment = Alignment.Center) {
    Row(Modifier.heightIn(min = 32.dp).pressed(source, Press(down = 0.95f, lift = 0.05f, buzz = Buzz.None))
        .background(Color.White.copy(alpha = 0.09f), CircleShape).litEdge(CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ic(icon, Theme.text, 18.dp)
        Txt(label, 14, FontWeight.SemiBold)
    }
    }
}
