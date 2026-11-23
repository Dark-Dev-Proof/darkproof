package it.blackout.mesh

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import java.util.Locale

// MARK: - Chat

/** A chat row: a day header or a message. */
private sealed class ChatRow(val key: String) {
    class Day(val label: String, key: String) : ChatRow(key)
    class Msg(val m: Message, val firstOfRun: Boolean, key: String) : ChatRow(key)
    /** "Marco joined" (v0.6): from the group's epochs, not a message. */
    class Event(val text: String, key: String) : ChatRow(key)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatTab(s: AppState, g: SavedGroup, messages: List<Message>, phones: Int, open: (Sheet) -> Unit) {
    val ctx = LocalContext.current
    val a = ctx as MainActivity
    val everyone = s.everyone
    val channel = if (everyone) "" else g.code // drafts are kept per channel
    val draft = s.drafts[channel] ?: ""
    val shown = remember(messages, everyone) { messages.filter { it.public == everyone } }
    // Receipts change no message, so the list above stays the same: delivery states are read again on every update.
    val tick = s.tick
    val deliveries = remember(tick, shown) { shown.filter { it.sender == MeshEngine.deviceId }.associate { it.id to MeshEngine.delivery(it) } }
    val joined = remember(tick, everyone, g) { if (everyone) emptyList() else MeshEngine.groupLines(g.code) }
    // The group's timer for disappearing messages (spec "Disappearing messages"): seconds, 0 off, null not known.
    val timer = remember(tick, everyone, g) { if (everyone) null else MeshEngine.timer(g.code).seconds }
    val rows = remember(shown, joined) {
        val out = ArrayList<ChatRow>(shown.size + 8); var day = -1
        val keys = RowKeys() // no key twice, or the list ends the app: two events can have the same time and text, and a day can come up again
        // With no message to place them between, what last happened in the group still shows: a join, the timer.
        val events = joined.sortedBy { it.first }.let { if (shown.isEmpty()) it.takeLast(3) else it }.toMutableList()
        fun dayRow(ts: Long) { val d = dayOf(ts); if (d != day) { day = d; out += ChatRow.Day(dayLabel(ctx, ts), keys.unique("day-$d")) } }
        fun eventsUpTo(ts: Long) { while (events.isNotEmpty() && events[0].first <= ts) { val e = events.removeAt(0); dayRow(e.first); out += ChatRow.Event(e.second, keys.unique("event-${e.first}-${e.second}")) } }
        shown.forEachIndexed { i, m ->
            eventsUpTo(m.ts)
            val d = dayOf(m.ts)
            dayRow(m.ts)
            out += ChatRow.Msg(m, i == 0 || out.lastOrNull() is ChatRow.Event || shown[i - 1].sender != m.sender || CheckIn.of(shown[i - 1].text) != null || dayOf(shown[i - 1].ts) != d, keys.unique(m.id))
        }
        eventsUpTo(Long.MAX_VALUE)
        out
    }
    val lastMine = remember(shown) { shown.lastOrNull { it.sender == MeshEngine.deviceId && CheckIn.of(it.text) != null } }
    // Replies and reactions are for groups only (spec "Replies and reactions"): on Everyone nothing of them is read.
    val shownTargets = remember(shown, everyone, g) { if (everyone) emptyMap() else About.targets(shown, g.code) }
    val heldTargets = remember(tick, everyone, g) { if (everyone) emptyMap() else About.targets(MeshEngine.groupRows(g.code), g.code) }
    // The message being answered, while it is still shown in this group's chat; switching channel or group clears it.
    val replying = s.replying?.takeIf { (code, _) -> !everyone && code == g.code }?.let { (_, id) -> shown.firstOrNull { it.id == id } }
    LaunchedEffect(everyone, g.code) { if (s.replying?.first != g.code || everyone) s.replying = null }
    var flashed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusDraft = remember { androidx.compose.ui.focus.FocusRequester() }
    val list = rememberLazyListState()
    // To the end when the chat opens; for a new message only if it is mine or the list was already at the end.
    var counted by remember(everyone, g) { mutableIntStateOf(-1) }
    // Kept at the end while the list is there: when the window under it changes (the keyboard closing after a send), the
    // end stays in view. Scrolling up by hand lets go of it; reaching the end again, or sending, takes it back.
    var pinned by remember(everyone, g) { mutableStateOf(true) }
    LaunchedEffect(rows.size, everyone, g) {
        if (rows.isEmpty()) return@LaunchedEffect
        val lastSeen = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val atEnd = counted < 0 || lastSeen >= counted - 1
        if (atEnd || shown.lastOrNull()?.sender == MeshEngine.deviceId) { list.scrollToItem(rows.size - 1, 100_000); pinned = true } // the offset is clamped: the very end
        counted = rows.size
    }
    val lastRow by rememberUpdatedState(rows.size - 1)
    LaunchedEffect(list, everyone, g) {
        launch { list.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) pinned = false } }
        launch { snapshotFlow { list.canScrollForward }.collect { more -> if (!more) pinned = true } }
        snapshotFlow { list.layoutInfo.viewportSize.height }.collect { if (pinned && lastRow >= 0) list.scrollToItem(lastRow, 100_000) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> photoChosen(ctx, uri, open) }
    // The phones' picker is asked for above the lock (LocalPhotoPicker), so a photo chosen while the app locked isn't lost.
    val above = LocalPhotoPicker.current
    val choosePhoto = { above?.invoke() ?: pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    var tray by rememberSaveable { mutableStateOf(false) }
    var askAll by remember { mutableStateOf(false) }
    if (tray && !everyone) AddTray(
        photo = { tray = false; choosePhoto() },
        voice = if (VoiceAudio.canRecord) ({ tray = false; Toast.makeText(ctx, "Hold the mic to record, 10 seconds at most", Toast.LENGTH_LONG).show() }) else null,
        safe = { tray = false; checkIn(a, s, CheckIn.SAFE) },
        help = { tray = false; s.helpGroup = null; s.askHelp = true },
        ask = { tray = false; askAll = true },
        timer = { tray = false; open(Sheet.Timer) }) { tray = false }
    if (askAll) AskEveryone(g) { askAll = false }
    val typing = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(bottom = if (typing || !Here.tabBar) 0.dp else 84.dp)) { // room for the tab bar
        Column(Modifier.padding(horizontal = 20.dp).padding(top = if (Here.wide) 2.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!Here.wide) { // a wide window names the chat above it and switches channels at its side
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The group's timer shows next to the reach line only while one is set.
                    Box(Modifier.weight(1f)) { ScreenTitle(g.name, if (phones == 1) "1 phone in range" else "$phones phones in range", timer = timer?.takeIf { it > 0 }?.let(GroupTimer::label)) }
                    if (!everyone) RoundIcon(R.drawable.ic_shield, desc = "Group security") { open(Sheet.Security) }
                }
                PillSwitch(g.name, "Everyone", everyone) { s.everyone = it }
            }
            Txt(if (everyone) "Anyone with DarkProof can read these, nearby or online." else SecurityWords.chatNote(g.name, s.highRisk, Here.device, timer), 14, color = Theme.dim)
            if (!everyone) remember(tick, g) { groupNote(g) }?.let { (title, text) ->
                Card(padding = 16.dp) { Txt(title, 16, FontWeight.SemiBold); Txt(text, 14, color = Theme.dim) }
            }
            // Admins only (the engine lists none for anyone else): one line, however many are asking.
            if (!everyone) JoinRow(remember(tick, g) { MeshEngine.joinRequests(g.code) }) { open(Sheet.Joins) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is ChatRow.Day -> Txt(r.label, 13, FontWeight.SemiBold, Theme.dim, Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).wrapContentWidth())
                    is ChatRow.Event -> Txt(r.text, 14, FontWeight.Medium, Theme.dim, Modifier.fillMaxWidth().padding(vertical = 6.dp).wrapContentWidth())
                    is ChatRow.Msg -> MessageRow(r.m, r.firstOfRun, deliveries[r.m.id], s.name,
                        lastMine?.takeIf { it.ts > r.m.ts }?.let { CheckIn.of(it.text) }, open, { s.moderated++ },
                        rowExtras(r.m, s, everyone, shownTargets, heldTargets, flashed == r.m.id, open,
                            reply = { s.replying = g.code to r.m.id; scope.launch { kotlinx.coroutines.delay(300); runCatching { focusDraft.requestFocus() } } },
                            jump = { id -> rows.indexOfFirst { it is ChatRow.Msg && it.m.id == id }.takeIf { it >= 0 }?.let { i ->
                                scope.launch { pinned = false; list.animateScrollToItem(i); flashed = id; kotlinx.coroutines.delay(1_400); if (flashed == id) flashed = null } } })) { c ->
                        if (c == CheckIn.SAFE) checkIn(a, s, CheckIn.SAFE, group = r.m.group) else { s.helpGroup = r.m.group; s.askHelp = true }
                    }
                }
            }
            // No message: under the group's last lines, if any. With a timer, or in high-risk mode, an empty chat may be one
            // whose messages have left: it doesn't say "yet".
            if (shown.isEmpty()) item(key = "empty") {
                val leaves = !everyone && (s.highRisk || (timer ?: 0) > 0)
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(if (leaves) "No messages here now" else "No messages yet", 20, FontWeight.Bold)
                    Txt(if (everyone) "Messages here reach everyone, nearby or online."
                        else if (leaves) "What you write reaches everyone in ${g.name} who is in range, or later when they are."
                        else if (Here.tabBar) "Say hi, or tap I'm safe on Home." else "Say hi, or tap I'm safe.", 15, color = Theme.dim, align = TextAlign.Center)
                }
            }
        }
        val canWrite = everyone || !remember(tick, g) { MeshEngine.removed(g.code) || MeshEngine.joining(g.code) }
        if (canWrite && replying != null) ReplyStrip(if (replying.sender == MeshEngine.deviceId) "yourself" else Quote.name(replying, MeshEngine.deviceId), Quote.line(replying),
            Modifier.padding(horizontal = 16.dp).padding(top = 6.dp)) { s.replying = null }
        // Voice notes: groups only, on a phone that can record them (Android 10 and newer; never on a computer).
        val voice = rememberVoiceComposer(s, replying = { if (everyone) null else replying }, sent = { s.replying = null; pinned = true })
        if (canWrite) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val recording = voice.rec.on
            if (!everyone && !recording) RoundIcon(R.drawable.ic_add, 52.dp, desc = "Add something") { tray = true }
            val send: () -> Unit = {
                val t = draft.trim()
                if (t.isNotEmpty() && meshReady(ctx)) {
                    s.drafts.remove(channel)
                    pinned = true
                    val pub = everyone
                    val to = if (pub) null else replying // a reply: the 5th field names it (spec "Replies")
                    s.replying = null
                    Thread {
                        val fits = MeshEngine.send(t, pub, replyTo = to)
                        if (!fits && MeshEngine.lora?.connected == true) android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(ctx, "Too long for LoRa: it will travel only over Bluetooth and Wi-Fi", Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            }
            if (recording) RecordingStrip(voice, Modifier.weight(1f))
            else TextBox(draft, { s.drafts[channel] = it.take(1000) }, if (everyone) "Message everyone" else "Message ${g.name}", singleLine = false,
                keyboard = KeyboardOptions(KeyboardCapitalization.Sentences), modifier = Modifier.weight(1f).focusRequester(focusDraft), shape = RoundedCornerShape(26.dp),
                onEnter = if (Here.wide) send else null) // at a computer's keyboard, Enter sends
            // An empty box in a group: the send button is the mic (hold to record). Never on Everyone, never where it can't record.
            if (recording || (!everyone && VoiceAudio.canRecord && draft.isBlank())) MicButton(voice)
            else RoundIcon(R.drawable.ic_arrow_upward, 52.dp, tint = if (draft.isBlank()) Theme.dim else Color.Black,
                bg = if (draft.isBlank()) Theme.tile else Color.White, desc = "Send", onClick = send)
        }
    }
}

/** What one message row needs for replies and reactions ([RowExtras]); read again at each update of the engine ([AppState.tick]). */
@Composable
private fun rowExtras(m: Message, s: AppState, everyone: Boolean, shown: Map<String, Message>, held: Map<String, Message>, flash: Boolean,
                      open: (Sheet) -> Unit, reply: () -> Unit, jump: (String) -> Unit): RowExtras {
    val tick = s.tick; val moderated = s.moderated
    val quote = remember(m, shown, held) { if (everyone) null else quoteOf(m, shown, held, MeshEngine.deviceId) }
    val chips = remember(tick, moderated, m) { if (m.public) emptyList() else MeshEngine.chips(m) }
    val canAnswer = remember(tick, m) { !m.public && MeshEngine.canAnswer(m) }
    return RowExtras(quote, chips, canAnswer, flash,
        react = { e -> Thread { MeshEngine.react(m, e) }.start() }, reply = reply, jump = jump,
        journey = { if (!m.public && m.sender == MeshEngine.deviceId) open(Sheet.Journey(m.id)) })
}

/**
 * Where this phone stands in group [g] when it isn't simply in it: removed, joining with an invite that no longer works,
 * rejoining after more than a week, or still asking to join. A title and a text (the chat and Group security show them); null when it is in.
 */
fun groupNote(g: SavedGroup): Pair<String, String>? = when {
    MeshEngine.removed(g.code) -> "You were removed from ${g.name}" to "You can still read what's here, but you can't write or see anything new. To come back, ask for a new invite."
    MeshEngine.inviteClosed(g.code) -> "This invite no longer works" to "The group has a new code. Ask someone in ${g.name} for a new invite."
    MeshEngine.rejoining(g.code) -> "Rejoining ${g.name}…" to "You've been away for more than a week. A phone that's in the group needs to be in reach, nearby or online."
    MeshEngine.joining(g.code) -> "Asking to join ${g.name}…" to "An admin of the group has to let you in, and their phone needs to be in reach, nearby or online. You can write here once they have."
    else -> null
}

/** Set above the app's lock screen on a phone: opens the photo picker and, when a photo comes back, the "Send photo" sheet. */
val LocalPhotoPicker = staticCompositionLocalOf<(() -> Unit)?> { null }

/** A photo that was picked: made small enough to send, off the main thread, then the "Send photo" sheet opens with it. */
fun photoChosen(ctx: android.content.Context, uri: Uri?, open: (Sheet) -> Unit) {
    if (uri != null) Thread {
        val jpeg = PhotoShrinker.jpeg(ctx, uri)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (jpeg == null) Toast.makeText(ctx, "This photo can't be read", Toast.LENGTH_LONG).show() else open(Sheet.SendPhoto(jpeg))
        }
    }.start()
}

/**
 * The "+" in the group's message box: what can be added, four to a row. Each does what its button elsewhere does.
 * [voice] (where this device can record): says how, since a voice note is recorded by holding the mic. [timer]: the
 * group's timer, which admins choose in the sheet behind it and everyone else reads there. A place comes later; there is
 * no "Camera" while the app has no way to take a picture itself.
 */
@Composable
private fun AddTray(photo: () -> Unit, voice: (() -> Unit)?, safe: () -> Unit, help: () -> Unit, ask: () -> Unit, timer: () -> Unit, close: () -> Unit) = SmallSheet(close) {
    // Room above and below: the sheet scrolls, which cuts off what is drawn outside it, and the green and red buttons glow.
    val items: List<@Composable (Modifier) -> Unit> = listOfNotNull(
        { m -> CircleAction(R.drawable.ic_image, "Photo", m, onClick = photo) },
        voice?.let { v -> { m: Modifier -> CircleAction(R.drawable.ic_mic, "Voice note", m, onClick = v) } },
        { m -> CircleAction(R.drawable.ic_verified_user, "I'm safe", m, Theme.safe, safe) },
        { m -> CircleAction(R.drawable.ic_sos, "I need help", m, Theme.help, help) },
        { m -> CircleAction(R.drawable.ic_notifications, "Ask if safe", m, onClick = ask) },
        { m -> CircleAction(R.drawable.ic_schedule, "Timer", m, onClick = timer) },
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in items.chunked(4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { it(Modifier.weight(1f)) }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** "Ask if everyone is safe?", from People and from the chat's "+" tray: the whole group gets the question with one-tap answers. */
@Composable
fun AskEveryone(g: SavedGroup, close: () -> Unit) {
    val ctx = LocalContext.current
    Confirm("Ask if everyone is safe?", "Everyone in ${g.name} gets “Is everyone safe?” with one-tap answers.", "Ask",
        onYes = { close(); if (meshReady(ctx)) { Thread { MeshEngine.send(AreYouSafe().text) }.start(); Toast.makeText(ctx, "Asked ${g.name}", Toast.LENGTH_SHORT).show() } },
        onNo = close)
}

/**
 * What a message row needs for replies and reactions (spec "Replies and reactions"): its [quote] if it is a reply, the
 * [chips] under it, and whether the group can be written to now ([canAnswer]: then the reactions and "Reply" are offered).
 * [flash]: lit for a moment, after a quote of it was tapped.
 */
private class RowExtras(val quote: QuoteLine?, val chips: List<Reactions.Chip>, val canAnswer: Boolean, val flash: Boolean,
                        val react: (String) -> Unit, val reply: () -> Unit, val jump: (String) -> Unit, val journey: () -> Unit)

/**
 * One message: a check-in, a report about someone, an "are you safe?" question (with one-tap answers when it is for
 * me), a place, or text and photo. Holding a group message (or right-clicking it, or Enter on it from the keyboard) opens
 * [HoldOverlay]: the reactions, Reply, Copy, for my own "How it got there", and last and red Block or Hide for someone
 * else's. An Everyone message keeps its menu: Copy, Block or Hide, Report.
 * While a message is "not verified yet" nobody knows who wrote it: it can be hidden, but its sender can't be blocked.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(m: Message, firstOfRun: Boolean, delivery: Delivery?, myName: String, answered: CheckIn?,
                       open: (Sheet) -> Unit, moderated: () -> Unit, x: RowExtras, answer: (CheckIn) -> Unit) {
    val ctx = LocalContext.current
    val me = m.sender == MeshEngine.deviceId
    val check = if (!m.public) CheckIn.of(m.text) else null
    val vouch = if (!m.public) Vouch.of(m.text) else null
    val ask = if (!m.public) AreYouSafe.of(m.text) else null
    val place = if (m.public) Place.of(m.text) else null
    val time = clock(ctx, m.ts)
    var menu by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val openMenu = { Buzz.play(Buzz.Hold); if (m.public) menu = true else held = true }
    val hold = Modifier
        // A computer: the menu key or Shift+F10 (and Enter) on a message opens what holding it opens.
        .onPreviewKeyEvent { e ->
            val k = e.key
            val asks = k == Key.Enter || k == Key.NumPadEnter || k == Key.Spacebar || k == Key.Menu || (k == Key.F10 && e.isShiftPressed)
            if (asks && e.type == KeyEventType.KeyDown) openMenu()
            asks
        }
        // A mouse: the right button opens it too.
        .pointerInput(m.id) { awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) { e.changes.forEach { it.consume() }; openMenu() } } } }
        .combinedClickable(interactionSource = null, indication = MessagePress, onLongClick = openMenu, onClick = {}, onLongClickLabel = "Message options")
    if (confirmBlock) Confirm("Block ${m.name}?", "You won't see their messages, places or check-ins on this ${Here.device}. You can unblock them in Settings.", "Block", danger = true,
        onYes = { confirmBlock = false; Moderation.block(ctx, m); moderated() }, onNo = { confirmBlock = false })
    // A voice note has no text to copy (its text only says what it is).
    val copyable = if (m.voice != null) "" else if (m.photo != null) Photo.caption(m.text) else m.text
    val hide = { Moderation.hide(ctx, m.id); moderated(); Toast.makeText(ctx, "Hidden on this ${Here.device}", Toast.LENGTH_SHORT).show() }
    // An Everyone message: the menu it always had (anchored to the Box around it).
    val options: @Composable () -> Unit = { DropdownMenu(menu, { menu = false }, containerColor = Theme.card) {
        DropdownMenuItem(text = { Txt("Copy text", 16) }, onClick = { menu = false; copyText(ctx, "Message", copyable) })
        if (Blocks.canBlock(m, MeshEngine.deviceId)) DropdownMenuItem(text = { Txt("Block ${m.name}", 16) }, onClick = { menu = false; confirmBlock = true })
        else if (!me) DropdownMenuItem(text = { Txt("Hide this message", 16) }, onClick = { menu = false; hide() })
        if (!me && m.public) DropdownMenuItem(text = { Txt("Report this message", 16, color = Theme.help) }, onClick = {
            menu = false; Moderation.hide(ctx, m.id); NostrReports.report(ctx, m.id); moderated()
            Toast.makeText(ctx, "Reported and hidden on this ${Here.device}", Toast.LENGTH_SHORT).show()
        })
    } }
    val card = check != null || vouch != null || ask != null || place != null
    // The message itself; [preview]: drawn again over the dimmed chat while it is held, without what goes under it.
    val body: @Composable (Boolean) -> Unit = { preview ->
        // A card has no bubble to hold the quote: it sits above the card.
        if (card) x.quote?.let { q -> QuoteView(q, Modifier.padding(bottom = 4.dp).background(Theme.card, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp), tap = if (preview) null else ({ q.row?.let(x.jump) }), hold = if (preview) null else openMenu) }
        when {
            check != null -> {
                val c = if (check == CheckIn.HELP) Theme.help else Theme.safe
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Ic(if (check == CheckIn.HELP) R.drawable.ic_sos else R.drawable.ic_verified_user, c, 26.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        val who = if (me) (if (check == CheckIn.HELP) "You need help" else "You're safe") else (if (check == CheckIn.HELP) "${m.name} needs help" else "${m.name} is safe")
                        Txt(who, 18, FontWeight.SemiBold, maxLines = 2)
                        val extra = (CheckIn.needs(m.text).sortedBy { it.ordinal }.map { it.label } +
                            listOfNotNull(CheckIn.people(m.text)?.takeIf { it > 1 }?.let { "$it people" })).joinToString(" · ")
                        if (extra.isNotEmpty()) Txt(extra, 15)
                    }
                    CheckIn.battery(m.text)?.let { BatteryLabel(it); Spacer(Modifier.width(12.dp)) }
                    Txt(time, 15, color = Theme.dim)
                }
                if (!preview) {
                    CheckIn.spot(m.text)?.let { sp -> SpotLinks(if (me) null else "Find ${m.name}", sp) { open(Sheet.Find(m.name, sp, m.ts)) } }
                    Chips(x)
                    if (me) Status(delivery, null, x.journey)
                }
            }
            vouch != null -> {
                val c = if (vouch.status == CheckIn.HELP) Theme.help else Theme.safe
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Theme.card).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Ic(R.drawable.ic_group, c, 26.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt("${vouch.subject} ${if (vouch.status == CheckIn.HELP) "needs help" else "is safe"}", 18, FontWeight.SemiBold, c, maxLines = 2)
                        val extra = (vouch.needs.sortedBy { it.ordinal }.map { it.label } + listOfNotNull(vouch.people?.takeIf { it > 1 }?.let { "$it people" })).joinToString(" · ")
                        if (extra.isNotEmpty()) Txt(extra, 15)
                        Txt("Reported by ${if (me) "you" else m.name}", 14, color = Theme.dim)
                    }
                    Txt(time, 15, color = Theme.dim)
                }
                if (!preview) {
                    vouch.spot?.let { sp -> SpotLinks("Find ${vouch.subject}", sp) { open(Sheet.Find(vouch.subject, sp, m.ts)) } }
                    Chips(x)
                    if (me) Status(delivery, null, x.journey)
                }
            }
            ask != null -> {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Theme.card).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_notifications, Theme.text, 26.dp)
                        Spacer(Modifier.width(14.dp))
                        Txt("${if (me) "You" else m.name} asked: ${m.text.removePrefix("❓ ")}", 17, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Txt(time, 15, color = Theme.dim)
                    }
                    // Answered already (a check-in of mine came after the question): no buttons.
                    if (preview) Unit
                    else if (!me && ask.isFor(myName) && answered != null)
                        Txt("You answered: ${if (answered == CheckIn.HELP) "I need help" else "I'm safe"}", 15, color = Theme.dim)
                    else if (!me && ask.isFor(myName)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CapsuleChoice("I'm safe", Theme.safe, Color.Black, Modifier.weight(1f)) { answer(CheckIn.SAFE) }
                        CapsuleChoice("I need help", Theme.help, Color.White, Modifier.weight(1f)) { answer(CheckIn.HELP) }
                    }
                }
                if (!preview) { Chips(x); if (me) Status(delivery, null, x.journey) }
            }
            place != null -> {
                Row(Modifier.fillMaxWidth().tap(CardPress, enabled = place.spot != null && !preview, role = null) {
                    place.spot?.let { open(Sheet.Find(place.kind.word, it, m.ts)) } }.background(Theme.card, RoundedCornerShape(24.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindIcon(place.kind)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt("${if (me) "You" else m.name} shared ${place.kind.word.lowercase()}", 16, FontWeight.SemiBold)
                        if (place.note.isNotEmpty()) Txt(place.note, 15, color = Theme.dim)
                        place.spot?.let { PlusCodeLabel(it, 13) }
                    }
                    Txt(time, 14, color = Theme.dim)
                }
            }
            else -> {
                if (!me && (firstOfRun || preview)) Txt(m.name, 14, FontWeight.SemiBold, Theme.dim, Modifier.padding(start = 14.dp, bottom = 4.dp))
                val bubble = if (me) Color.White else Theme.tile
                val fg = if (me) Color.Black else Theme.text
                Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp)).background(bubble)) {
                    x.quote?.let { q -> QuoteView(q, Modifier.padding(start = 16.dp, end = 16.dp, top = 11.dp), onWhite = me, tap = if (preview) null else ({ q.row?.let(x.jump) }), hold = if (preview) null else openMenu) }
                    m.photo?.let { jpeg ->
                        val img = remember(m.id) { decodePhoto(jpeg)?.asImageBitmap() }
                        // The sender chooses the picture: its shape is kept within what a row can be laid out as (Photo.shape).
                        if (img != null) Image(img, "Photo", Modifier.padding(top = if (x.quote != null) 8.dp else 0.dp).fillMaxWidth().aspectRatio(Photo.shape(img.width, img.height))
                            .tap(Press(down = 0.98f, shape = RectangleShape, dim = 0.15f, buzz = Buzz.None), enabled = !preview, role = null) { open(Sheet.ViewPhoto(jpeg)) }, contentScale = ContentScale.Crop)
                    }
                    // A voice note only from a group packet whose signature checked (Voice.playable); until then, or if its
                    // field was ignored, the message is its text.
                    if (Voice.playable(m)) VoiceNoteRow(m, me, preview, openMenu)
                    else {
                        val text = if (m.photo != null) Photo.caption(m.text) else m.text
                        if (text.isNotEmpty()) Txt(text, 17, color = fg, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                        else if (x.quote != null && m.photo == null) Spacer(Modifier.height(11.dp))
                        if (m.voice == null && !m.public && m.text == Voice.PLACEHOLDER) CantPlayNote(me)
                    }
                }
                if (!preview) {
                    Chips(x, Modifier.padding(horizontal = 8.dp).offset(y = (-4).dp))
                    if (me) Status(delivery, time, if (m.public) null else x.journey) else Txt(time, 13, color = Theme.dim, modifier = Modifier.padding(start = 14.dp, top = 3.dp))
                }
            }
        }
    }
    if (held) {
        val close = { held = false }
        val items = buildList {
            if (x.canAnswer) add(HoldItem("Reply", R.drawable.ic_reply) { close(); x.reply() })
            if (copyable.isNotEmpty()) add(HoldItem("Copy", R.drawable.ic_content_copy) { close(); copyText(ctx, "Message", copyable) })
            if (me) add(HoldItem("How it got there", R.drawable.ic_route) { close(); x.journey() })
            // Last and red: what there was before for someone else's message. My own group messages have no local removal.
            if (!me && Blocks.canBlock(m, MeshEngine.deviceId)) add(HoldItem("Block ${m.name}", R.drawable.ic_block, danger = true) { close(); confirmBlock = true })
            else if (!me) add(HoldItem("Hide this message", R.drawable.ic_visibility_off, danger = true) { close(); hide() })
        }
        HoldOverlay(me && !card, if (x.canAnswer) ({ e -> close(); Buzz.play(Buzz.Tap); x.react(e) }) else null, items, close) {
            Column(horizontalAlignment = if (me && !card) Alignment.End else Alignment.Start) { body(true) }
        }
    }
    Box(Modifier.fillMaxWidth()) {
    options()
    Column(Modifier.fillMaxWidth().then(hold).padding(top = if (firstOfRun) 8.dp else 0.dp)
        .then(if (x.flash) Modifier.background(Theme.text.copy(alpha = 0.10f), RoundedCornerShape(24.dp)) else Modifier),
        horizontalAlignment = if (me && !card) Alignment.End else Alignment.Start) {
        body(false)
        if (!me) VerifyNote(m)
    }
    }
}

/** The reactions under a message, when it has any. */
@Composable
private fun Chips(x: RowExtras, modifier: Modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
    if (x.chips.isNotEmpty()) ReactionChips(x.chips, modifier, if (x.canAnswer) x.react else null)
}

/** A message is held, not tapped: it gives a little under the finger and lights up, then its menu opens. */
private val MessagePress = Press(down = 0.98f, shape = RoundedCornerShape(22.dp), dim = 0f, lift = 0f, light = 0.04f, buzz = Buzz.None)

/** A card that opens something: it gives a little and lights up, the board's .rowc. */
val CardPress = Press(down = 0.98f, shape = RoundedCornerShape(22.dp), dim = 0f, lift = 0.05f, light = 0.05f)

/** Under someone else's message: "Not verified yet" (their key hasn't come), or a new key for a checked person's name. */
@Composable
private fun VerifyNote(m: Message) {
    val newKey = MeshEngine.newKeyFor(m.group, m.sender, m.name)
    if (newKey) Txt("New key for ${m.name}: check their emoji", 13, FontWeight.SemiBold, Theme.help, Modifier.padding(start = 14.dp, top = 3.dp))
    else if (!m.verified) Txt("Not verified yet", 13, color = Theme.faint, modifier = Modifier.padding(start = 14.dp, top = 3.dp))
}

/** Under a check-in with a position: "Find Anna" and its Plus Code (tap to copy). */
@Composable
private fun SpotLinks(find: String?, sp: Spot, open: () -> Unit) = Row(Modifier.padding(start = 18.dp, top = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    if (find != null) Txt(find, 14, FontWeight.SemiBold, Theme.text, Modifier.tap(Press(down = 0.95f, shape = RoundedCornerShape(8.dp), dim = 0.35f, buzz = Buzz.Tap), onClick = open).padding(vertical = 4.dp))
    PlusCodeLabel(sp)
}

/** Under my messages: still waiting, given to a phone, or confirmed by group members. [journey]: tapping it opens "How it got there" (group messages). */
@Composable
private fun Status(d: Delivery?, time: String?, journey: (() -> Unit)? = null) {
    val label = when (d) {
        is Delivery.Delivered -> {
            val n = d.readers.map { it.name }
            "Delivered to " + when (n.size) { 1 -> n[0]; 2 -> "${n[0]} and ${n[1]}"; else -> "${n.size} people" }
        }
        Delivery.Sent -> "Sent"
        Delivery.Waiting -> "Waiting for a phone or internet"
        null -> null
    }
    Row(Modifier.padding(end = 12.dp, top = 3.dp).fillMaxWidth().wrapContentWidth(Alignment.End)
        .then(if (journey != null) Modifier.tap(Press(down = 0.97f, shape = RoundedCornerShape(8.dp), dim = 0.3f, buzz = Buzz.None), label = "How it got there", onClick = journey) else Modifier),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (time != null) Txt(time + if (label != null) " · " else "", 13, color = Theme.dim)
        if (d is Delivery.Delivered) { Ic(R.drawable.ic_check_circle, Theme.dim, 14.dp); Spacer(Modifier.width(4.dp)) }
        if (label != null) Txt(label, 13, color = Theme.dim, maxLines = 1)
    }
}

// MARK: - Places

private data class Shared(val place: Place, val by: String, val ts: Long, val id: String) {
    val old get() = Place.freshness(ts) == Freshness.OLD
}

@DrawableRes fun kindIcon(k: PlaceKind) = when (k) {
    PlaceKind.WATER -> R.drawable.ic_water_drop; PlaceKind.SHELTER -> R.drawable.ic_house
    PlaceKind.CHARGING -> R.drawable.ic_bolt; PlaceKind.MEDICAL -> R.drawable.ic_medical_services
}

@Composable fun KindIcon(k: PlaceKind) = Box(Modifier.size(48.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Ic(kindIcon(k), Theme.text, 24.dp) }

/**
 * Runs [start] while the app is on screen, and the stop function it returns when the app goes to the background or this
 * part of the screen goes away: GPS and the compass sensor must not keep running behind a closed app. A new [key]
 * (e.g. location was just allowed) starts it again.
 */
@Composable fun WhileOnScreen(key: Any? = null, start: () -> (() -> Unit)) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, key) {
        var stop: (() -> Unit)? = null
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START && stop == null) stop = start()
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { stop?.invoke(); stop = null }
        }
        lifecycle.addObserver(obs) // replays ON_START if the app is already on screen
        onDispose { lifecycle.removeObserver(obs); stop?.invoke(); stop = null }
    }
}

/** My position while a screen that needs it is open. */
@Composable fun rememberMySpot(): Spot? {
    val ctx = LocalContext.current
    var spot by remember { mutableStateOf<Spot?>(null) }
    val allowed = Perms.version.let { Locator.allowed(ctx) }
    WhileOnScreen(allowed) { Locator.watch(ctx) { spot = it } }
    return spot
}

@Composable
fun PlacesTab(s: AppState, messages: List<Message>, open: (Sheet) -> Unit) {
    var filter by rememberSaveable { mutableStateOf<PlaceKind?>(null) }
    val me = rememberMySpot()
    val all = remember(messages) { // expired places (older than 7 days) are hidden
        messages.filter { it.public && Place.freshness(it.ts) != Freshness.EXPIRED }
            .mapNotNull { m -> Place.of(m.text)?.let { Shared(it, if (m.sender == MeshEngine.deviceId) "you" else m.name, m.ts, m.id) } }
    }
    val shown = remember(all, filter, me) {
        val f = all.filter { filter == null || it.place.kind == filter }
        if (me == null) f.sortedWith(compareBy<Shared> { it.old }.thenByDescending { it.ts })
        else f.sortedWith(compareBy<Shared> { it.old }.thenBy { it.place.spot?.let { sp -> Geo.distance(me, sp) } ?: Double.MAX_VALUE }.thenByDescending { it.ts })
    }
    if (s.placesMap && Here.map) {
        var center by remember { mutableStateOf<Spot?>(null) }
        val people = remember(messages) { Person.all(messages, MeshEngine.deviceId) }
        val pins = remember(all, people) {
            all.mapNotNull { sh -> sh.place.spot?.let { Pin(sh.place.kind.word + if (sh.place.note.isNotEmpty()) ": " + Cut.units(sh.place.note, 40) else "", it, sh.ts, if (sh.old) "#6B6B6B" else "#FFFFFF") } } +
                people.mapNotNull { p -> p.spot?.let { Pin(p.name, it, p.spotTs, when (p.checkIn) { CheckIn.HELP -> "#FF453B"; CheckIn.SAFE -> "#33D64A"; null -> "#949494" }) } }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = if (Here.tabBar) 100.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("Places", "Shared by anyone with DarkProof, nearby or online")
            PillSwitch("List", "Map", true) { s.placesMap = it }
            StreetMap(pins, me, Modifier.fillMaxWidth().weight(1f), open) { center = it }
            Txt("Map data © OpenStreetMap contributors · OpenFreeMap · OpenMapTiles", 12, color = Theme.dim)
            OfflineMapCard(me, center)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = if (Here.tabBar) 120.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Places", "Shared by anyone with DarkProof, nearby or online")
        if (Here.map) PillSwitch("List", "Map", false) { s.placesMap = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All", null, filter == null) { filter = null }
            PlaceKind.entries.forEach { k -> FilterChip(k.word, kindIcon(k), filter == k) { filter = k } }
        }
        PrimaryButton("Share a place", icon = R.drawable.ic_add) { open(Sheet.AddPlace) }
        if (shown.isEmpty()) Card {
            Txt("Nothing shared yet", 18, FontWeight.Bold)
            Txt("Know where there is water, shelter, a place to charge phones or medical help? Share it so people nearby can find it.", 15, color = Theme.dim)
        } else Card(padding = 8.dp) {
            shown.forEach { sh ->
                val sp = sh.place.spot
                Row(Modifier.fillMaxWidth().tap(RowPress, enabled = sp != null, role = null) { sp?.let { open(Sheet.Find(sh.place.kind.word, it, sh.ts)) } }
                    .alpha(if (sh.old) 0.55f else 1f).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindIcon(sh.place.kind)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(sh.place.kind.word, 18, FontWeight.Bold)
                        if (sh.place.note.isNotEmpty()) Txt(sh.place.note, 16, color = Theme.text.copy(alpha = 0.85f))
                        Txt(if (sh.old) "Old: by ${sh.by}, ${ago(sh.ts)}. It may have changed." else "By ${sh.by} · ${ago(sh.ts)}", 14, color = Theme.dim)
                        sp?.let { PlusCodeLabel(it) }
                    }
                    if (sp != null) Column(horizontalAlignment = Alignment.End) {
                        if (me != null) {
                            val d = Geo.distance(me, sp)
                            if (d <= maxOf(15, me.accuracy ?: 0, sp.accuracy ?: 0)) Chip("Here", Theme.safe)
                            else { Txt(Geo.meters(d), 15, FontWeight.SemiBold); Txt(Geo.compassWord(Geo.bearing(me, sp)), 13, color = Theme.dim) }
                        } else Ic(R.drawable.ic_navigation, Theme.dim, 26.dp)
                    }
                }
            }
        }
        if (me == null && all.any { it.place.spot != null }) Txt("Finding your position to sort places by distance…", 14, color = Theme.dim)
        Txt("Places disappear 7 days after they were shared. Older than a day, they are greyed out.", 14, color = Theme.dim)
    }
}

/** A filter in a row of them (the board's .chips): white when chosen, a dark capsule with a lit edge otherwise. */
@Composable private fun FilterChip(label: String, @DrawableRes icon: Int?, on: Boolean, tap: () -> Unit) =
    Row(Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
        .selectable(on, interactionSource = null, indication = Press(down = 0.94f, buzz = Buzz.Tap), role = Role.Tab, onClick = tap)
        .background(if (on) Color(0xFFFAFAFA) else Theme.tile, CircleShape).litEdge(CircleShape, if (on) 0.6f else 0.10f)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Ic(icon, if (on) Color.Black else Theme.text, 18.dp); Spacer(Modifier.width(6.dp)) }
        Txt(label, 16, FontWeight.SemiBold, if (on) Color.Black else Theme.text)
    }

@Composable
fun AddPlaceSheet(close: () -> Unit) {
    val ctx = LocalContext.current
    val a = ctx as MainActivity
    var kind by rememberSaveable { mutableStateOf(PlaceKind.WATER) }
    var note by rememberSaveable { mutableStateOf("") }
    var withSpot by rememberSaveable { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { cancel?.invoke() } } // closed while finding the position: nothing is posted
    val allowed = Perms.version.let { Locator.allowed(ctx) }
    val empty = note.isBlank() && !(withSpot && allowed) // a place with neither details nor a position helps no one
    SheetPage("Share a place", close) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlaceKind.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        val on = k == kind
                        Row(Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                            .selectable(on, interactionSource = null, indication = Press(down = 0.97f, shape = RoundedCornerShape(20.dp), dim = 0.1f, buzz = Buzz.Tap), role = Role.RadioButton) { kind = k }
                            .background(if (on) Color(0xFFFAFAFA) else Theme.card, RoundedCornerShape(20.dp))
                            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Ic(kindIcon(k), if (on) Color.Black else Theme.text, 24.dp); Spacer(Modifier.width(10.dp))
                            Txt(k.word, 17, FontWeight.SemiBold, if (on) Color.Black else Theme.text)
                        }
                    }
                }
            }
        }
        InputField("Details", note, { note = it.take(Place.MAX_NOTE) }, "Tap at the school gym, bring bottles", singleLine = false,
            keyboard = KeyboardOptions(KeyboardCapitalization.Sentences))
        Card { SwitchRow("Add my current position", withSpot, { withSpot = it }, "So people can find it with the compass. Share it only if you are at the place.") }
        if (withSpot && !allowed) {
            Txt("Location isn't allowed for DarkProof: the place can only go out with its details.", 14, color = Theme.help)
            GhostButton("Allow location") { a.askLocation() }
        }
        Txt("Anyone with DarkProof can see it, nearby or online. Once shared it can't be taken back, but it disappears after 7 days.", 14, color = Theme.dim)
        PrimaryButton(if (sending) "Finding your position…" else "Share", enabled = !sending && !empty) {
            if (!meshReady(ctx)) return@PrimaryButton
            fun go(sp: Spot?) {
                cancel = null
                if (sp == null && note.isBlank()) { sending = false; Toast.makeText(ctx, "No position found yet. Add details, or try again outside.", Toast.LENGTH_LONG).show(); return }
                Thread { MeshEngine.send(Place(kind, note, sp).text, public = true) }.start()
                Toast.makeText(ctx, if (withSpot && sp == null) "Shared without your position: no GPS fix yet" else "Shared with everyone", Toast.LENGTH_LONG).show()
                close()
            }
            if (withSpot && allowed) { sending = true; cancel = Locator.fix(ctx) { go(it) } } else go(null)
        }
        if (empty) Txt("Add details or your position, so people can find it.", 14, color = Theme.dim)
    }
}

// MARK: - People

@Composable
fun PeopleTab(s: AppState, a: MainActivity, g: SavedGroup, messages: List<Message>, phones: Int, open: (Sheet) -> Unit) {
    val people = remember(messages, s.tick, s.moderated, g) { peopleOf(a, messages, g.code) }
    val mine = remember(messages) { messages.lastOrNull { !it.public && it.sender == MeshEngine.deviceId && CheckIn.of(it.text) != null } }
    var askAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = if (Here.tabBar) 120.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScreenTitle("People", "${g.name} · ${people.size} ${if (people.size == 1) "person" else "people"} · $phones nearby") }
            if (!Here.wide) RoundIcon(R.drawable.ic_settings, desc = "Settings") { open(Sheet.Settings) } // a wide window has Settings at its side
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigCheckIn("I'm safe", R.drawable.ic_verified_user, Theme.safe, Color.Black, Modifier.weight(1f)) {
                checkIn(a, s, CheckIn.SAFE)
            }
            BigCheckIn("I need help", R.drawable.ic_sos, Theme.help, Color.White, Modifier.weight(1f)) { s.helpGroup = null; s.askHelp = true }
        }
        mine?.let { m -> Txt("Your last check-in: ${if (CheckIn.of(m.text) == CheckIn.HELP) "need help" else "safe"}, ${ago(m.ts)}.", 15, color = Theme.dim) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Ask everyone", Modifier.weight(1f), icon = R.drawable.ic_notifications) { askAll = true }
            SecondaryButton("Check in for…", Modifier.weight(1f), icon = R.drawable.ic_group) { open(Sheet.Vouch) }
        }
        if (people.isEmpty()) Card {
            Txt("No one from ${g.name} yet", 18, FontWeight.Bold)
            Txt("Invite friends with a QR code. People show up here once they write or check in.", 15, color = Theme.dim)
        } else Card(padding = 12.dp) {
            people.forEach { p -> PersonRow(p, { open(Sheet.Person(p.id)) }) { sp -> open(Sheet.Find(p.name, sp, p.spotTs)) } }
        }
        Txt("“Check in for…” tells the group about someone without a phone, for example a neighbour you found.", 14, color = Theme.dim)
        SecondaryButton("Invite friends", icon = R.drawable.ic_qr_code_2) { open(Sheet.Invite) }
    }
    if (askAll) AskEveryone(g) { askAll = false }
}

/**
 * The People list: everyone who wrote or checked in ([Person.all]), then the members who haven't written anything yet,
 * so that every member can be opened and checked. Someone blocked in this group stays hidden here.
 */
fun peopleOf(c: android.content.Context, messages: List<Message>, code: String?): List<Person> {
    val wrote = Person.all(messages, MeshEngine.deviceId)
    val have = wrote.mapTo(HashSet()) { it.id }
    val blocked = Moderation.blocked(c).keys; val channel = MeshEngine.groupId(code)
    return wrote + MeshEngine.otherMembers(code).filter { (id, _) -> id !in have && !Blocks.hides(blocked, channel, id) }
        .map { (id, name) -> Person(id, name.ifEmpty { "Someone" }, 0L) }.sortedBy { it.name.lowercase() } // 0: nothing heard from them yet
}

/** The big check-in tiles, in the logo's shape. */
@Composable
private fun BigCheckIn(label: String, @DrawableRes icon: Int, bg: Color, fg: Color, modifier: Modifier, tap: () -> Unit) {
    val shape = remember { vaultShape(34.dp, 40.dp, 8.dp) }
    val press = remember(shape) { Press(down = 0.95f, shape = shape, dim = 0.18f) }
    Column(modifier.height(176.dp).tap(press, onClick = tap).background(bg, shape).padding(20.dp),
        verticalArrangement = Arrangement.Bottom) {
        Ic(icon, fg, if (icon == R.drawable.ic_sos) 56.dp else 40.dp)
        Spacer(Modifier.height(10.dp))
        Txt(label, 22, FontWeight.Black, fg, maxLines = 1)
    }
}

// MARK: - Find (compass)

@Composable
fun FindSheet(f: Sheet.Find, close: () -> Unit) {
    val ctx = LocalContext.current
    val me = rememberMySpot()
    var heading by remember { mutableStateOf<Float?>(null) }
    var compass by remember { mutableStateOf(true) }
    WhileOnScreen { Compass.watch(ctx) { h -> if (h == null) compass = false else heading = h } }
    val allowed = Perms.version.let { Locator.allowed(ctx) }
    SheetPage(f.title, close) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (me == null) {
                Spacer(Modifier.height(40.dp))
                Txt(if (allowed) "Finding your position…" else "Allow location to see how far", 18, FontWeight.SemiBold, Theme.dim)
                if (!allowed) GhostButton("Allow location") { (ctx as? MainActivity)?.askLocation() }
            } else {
                val d = Geo.distance(me, f.spot); val b = Geo.bearing(me, f.spot)
                val near = d <= maxOf(15, me.accuracy ?: 0, f.spot.accuracy ?: 0)
                // The sensor gives magnetic north; positions use true north: add the declination where I am.
                val north = heading?.let { it + Compass.declination(me) } ?: 0f
                Box(Modifier.size(240.dp).background(Theme.card, CircleShape), contentAlignment = Alignment.Center) {
                    if (near) Ic(R.drawable.ic_my_location, Theme.safe, 96.dp)
                    else Ic(R.drawable.ic_navigation, Theme.text, 120.dp, Modifier.rotate((b - north).toFloat()))
                }
                Txt(if (near) "Very close" else Geo.meters(d), 44, FontWeight.Black)
                Txt(if (near) "Within about ${Geo.meters(maxOf(15.0, (me.accuracy ?: 15).toDouble()))}. Look around or call out." else "${Geo.compassWord(b)} of you", 18, color = Theme.dim)
            }
            Txt("Position from ${ago(f.ts)}", 15, color = Theme.dim)
            if (!compass) Txt("This ${Here.device} has no compass right now, so the arrow assumes the top of the ${Here.device} points north.", 14, color = Theme.dim)
        }
        Card(padding = 16.dp) { PlusCodeRow(f.spot) }
        SecondaryButton("Open in a map app", icon = R.drawable.ic_map) {
            val geo = String.format(Locale.ROOT, "geo:%.5f,%.5f?q=%.5f,%.5f", f.spot.lat, f.spot.lon, f.spot.lat, f.spot.lon)
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo))) }.onFailure { Toast.makeText(ctx, "No map app on this ${Here.device}", Toast.LENGTH_LONG).show() }
        }
    }
}

// MARK: - Photos

@Composable
fun SendPhotoSheet(s: AppState, jpeg: ByteArray, close: () -> Unit) {
    val ctx = LocalContext.current
    // Opened while a reply was being written: the photo is the reply (its 4th field and the 5th together, spec "Replies").
    val to = remember { s.replying?.let { (code, id) -> MeshEngine.groupRows(code).firstOrNull { it.id == id } } }
    var caption by remember { mutableStateOf("") }
    val img = remember(jpeg) { decodePhoto(jpeg)?.asImageBitmap() }
    SheetPage("Send photo", close) {
        if (img != null) Image(img, "Photo", Modifier.fillMaxWidth().aspectRatio(Photo.shape(img.width, img.height)).clip(RoundedCornerShape(22.dp)), contentScale = ContentScale.Crop)
        Txt("${jpeg.size / 1000} KB, small enough to hop phone to phone.", 14, color = Theme.dim)
        InputField("Caption", caption, { caption = it.take(200) }, "Bridge is out", keyboard = KeyboardOptions(KeyboardCapitalization.Sentences))
        Txt("Only your group can see it. Phones without the newest DarkProof show “📷 Photo” instead.", 14, color = Theme.dim)
        if (to != null) Txt("Replying to ${if (to.sender == MeshEngine.deviceId) "yourself" else Quote.name(to, MeshEngine.deviceId)}", 14, FontWeight.SemiBold)
        PrimaryButton("Send photo") {
            if (!meshReady(ctx)) return@PrimaryButton
            if (to != null) s.replying = null
            Thread { MeshEngine.send(Photo.text(caption), photo = jpeg, group = to?.group, replyTo = to) }.start(); close()
        }
    }
}

@Composable
fun ViewPhotoSheet(jpeg: ByteArray, close: () -> Unit) {
    val img = remember(jpeg) { decodePhoto(jpeg)?.asImageBitmap() }
    SheetPage("Photo", close) {
        if (img != null) Image(img, "Photo", Modifier.fillMaxWidth().aspectRatio(Photo.shape(img.width, img.height)).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
    }
}

// MARK: - Ask

/** What the app knows right now, as plain text for the assistant, the same as the iPhone's Situation.swift. */
fun situation(g: SavedGroup, messages: List<Message>, me: Spot? = null): String {
    val lines = ArrayList<String>()
    val people = Person.all(messages, MeshEngine.deviceId)
    val phones = phonesInRange()
    lines += "Group \"${ai(g.name)}\": ${people.size} other ${if (people.size == 1) "person" else "people"} have written; $phones phone(s) in range right now."
    for (p in people.take(15)) {
        val parts = ArrayList<String>()
        parts += AiContext.status(p) { ago(it) } // a report says who reported it, never "checked in"
        parts += "last heard ${ago(p.lastTs)}"
        p.battery?.let { parts += "battery $it%" }
        p.spot?.let { sp ->
            parts += if (me != null) "was ${Geo.meters(Geo.distance(me, sp))} ${Geo.compassWord(Geo.bearing(me, sp)).lowercase()} of you ${ago(p.spotTs)}"
            else "shared a position ${ago(p.spotTs)}"
        }
        lines += "- ${ai(p.name)}: " + parts.joinToString(", ")
    }
    val mine = messages.lastOrNull { !it.public && it.sender == MeshEngine.deviceId && CheckIn.of(it.text) != null }
    lines += if (mine != null) "You (the person asking) last checked in as ${if (CheckIn.of(mine.text) == CheckIn.HELP) "needing help" else "safe"}, ${ago(mine.ts)}."
        else "You (the person asking) have not checked in yet."
    val places = messages.filter { it.public }.mapNotNull { m -> Place.of(m.text)?.let { Triple(it, if (m.sender == MeshEngine.deviceId) "you" else m.name, m.ts) } }
        .let { l -> if (me == null) l.reversed() else l.sortedBy { it.first.spot?.let { sp -> Geo.distance(me, sp) } ?: Double.MAX_VALUE } }.take(10)
    lines += if (places.isEmpty()) "Shared places: none yet." else "Shared places (nearest first):"
    for ((pl, by, ts) in places) {
        val d = if (pl.spot != null && me != null) ", ${Geo.meters(Geo.distance(me, pl.spot))} ${Geo.compassWord(Geo.bearing(me, pl.spot)).lowercase()}" else ""
        lines += "- ${pl.kind.word}${if (pl.note.isEmpty()) "" else ": " + ai(pl.note, 120)} (by ${ai(by)}, ${ago(ts)}$d)"
    }
    val recent = messages.filter { !it.public && CheckIn.of(it.text) == null }.takeLast(8)
    if (recent.isNotEmpty()) {
        lines += "Latest group messages, oldest first:"
        for (m in recent) {
            val who = if (m.sender == MeshEngine.deviceId) "You" else ai(m.name)
            val text = if (m.photo != null) "[photo] " + Photo.caption(m.text) else m.text
            lines += "- $who, ${ago(m.ts)}: ${ai(text, 160)}"
        }
    }
    // Other people's words fill most of it, so it has a size the model's window always holds (AiContext).
    return AiContext.head(lines.joinToString("\n"), AiContext.MAX_SITUATION_BYTES)
}

/** Text from other people, for the assistant: one line, short, and it can't close the <data> block around it. */
private fun ai(s: String, max: Int = 40) = AiContext.field(s, max)

/** The assistant's instructions and the group's situation, like the iPhone. */
fun aiSystem(g: SavedGroup, messages: List<Message>, me: Spot? = null) =
    LocalAi.instructions + "\n\n" + LocalAi.situationIntro + "\n<data>\n" + situation(g, messages, me) + "\n</data>"

private const val CATCH_UP = "Catch me up on the last 6 hours"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskTab(g: SavedGroup, messages: List<Message>) {
    var draft by remember { mutableStateOf("") }
    var choose by remember { mutableStateOf(false) }
    val m = LocalAi.model(LocalAi.selected)
    val ready = m != null && LocalAi.states[m.id] == LocalAi.State.Ready
    val turns = LocalAi.conversation
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) { if (turns.isNotEmpty()) list.scrollToItem(turns.size - 1) }
    fun send(q: String) { if (q.isNotBlank() && !LocalAi.busy) { LocalAi.ask(q.trim(), aiSystem(g, messages, Locator.lastKnown(ctx))); draft = "" } }
    /** "Catch me up": the group's messages of the last 6 hours go with what the app knows; the chat shows the short question. */
    fun catchUp() {
        if (LocalAi.busy) return
        val recent = messages.filter { !it.public && it.ts >= System.currentTimeMillis() - 6 * 3_600_000L }.takeLast(40)
        if (recent.isEmpty()) { Toast.makeText(ctx, "Nothing from ${g.name} in the last 6 hours", Toast.LENGTH_LONG).show(); return }
        val h = recent.joinToString("\n") { "${clock(ctx, it.ts)} ${if (it.sender == MeshEngine.deviceId) "You" else ai(it.name)}: ${ai(it.text, 300)}" }
        LocalAi.ask(CATCH_UP, LocalAi.instructions + "\n\n" + LocalAi.situationIntro + "\n<data>\n" + AiContext.head(situation(g, messages, Locator.lastKnown(ctx)), AiContext.MAX_SITUATION_BYTES / 2) +
            "\nAll group messages of the last 6 hours, oldest first:\n" + AiContext.tail(h, AiContext.MAX_CATCH_UP_BYTES) + "\n</data>\nSay who is safe, who is asking for help and what is missing. Do not make anything up.")
    }
    var menu by remember { mutableStateOf(false) }
    val typing = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(bottom = if (typing || !Here.tabBar) 0.dp else 84.dp)) {
        Row(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { ScreenTitle("Ask", "Works with no internet. It can be wrong: double-check anything important.") }
            Spacer(Modifier.width(12.dp))
            Box {
                RoundIcon(R.drawable.ic_more_horiz, desc = "More: new conversation, AI model") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = Theme.card) {
                    val canNew = ready && turns.isNotEmpty() && !LocalAi.busy
                    DropdownMenuItem(text = { Txt("New conversation", 16, color = if (canNew) Theme.text else Theme.faint) }, enabled = canNew,
                        onClick = { menu = false; LocalAi.newConversation() })
                    DropdownMenuItem(text = { Txt("AI model: " + (m?.title ?: "none yet"), 16) }, onClick = { menu = false; choose = true })
                    Here.aiSwitches.forEach { sw -> DropdownMenuItem(text = { Txt(sw.label, 16) }, onClick = { menu = false; sw.flip() }) }
                }
            }
        }
        if (!ready) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Card {
                    Txt("No AI model on this ${Here.device}", 18, FontWeight.Bold)
                    Txt("Download one while you still have internet. It stays on the ${Here.device} and works offline afterwards.", 15, color = Theme.dim)
                }
                ModelList()
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (turns.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Who in ${g.name} needs help?", "How do I treat a burn?", CATCH_UP, "How do I make water safe to drink?").forEach { q ->
                            Txt(q, 16, FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().tap(CardPress) { if (q == CATCH_UP) catchUp() else send(q) }
                                .background(Theme.card, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 14.dp))
                        }
                    }
                }
                items(turns.size) { i ->
                    val t = turns[i]; val me = t.role == "user"
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(if (t.text == "…") AnnotatedString("Thinking…") else if (me) AnnotatedString(t.text) else markdown(t.text),
                            Modifier.widthIn(max = if (Here.wide) 560.dp else 320.dp).clip(RoundedCornerShape(22.dp)).background(if (me) Color.White else Theme.tile)
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            color = when { me -> Color.Black; t.isError -> Theme.help; t.text == "…" -> Theme.dim; else -> Theme.text },
                            fontSize = 16.sp, lineHeight = 23.sp)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextBox(draft, { draft = it.take(1000) }, "Ask anything", singleLine = false, keyboard = KeyboardOptions(KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(26.dp), onEnter = if (Here.wide) ({ send(draft) }) else null) // at a computer's keyboard, Enter asks
                if (LocalAi.busy) RoundIcon(R.drawable.ic_close, 52.dp, desc = "Stop") { LocalAi.stop() }
                else RoundIcon(R.drawable.ic_arrow_upward, 52.dp, tint = if (draft.isBlank()) Theme.dim else Color.Black,
                    bg = if (draft.isBlank()) Theme.tile else Color.White, desc = "Ask") { send(draft) }
            }
        }
    }
    if (choose) SheetPage("AI model", { choose = false }) { ModelList() }
}

/** The little Markdown models write: **bold**, and "* " or "- " bullets. */
fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val line = raw.replace(Regex("^(\\s*)[*-] "), "$1• ").replace(Regex("^#{1,4} "), "")
        var bold = false
        line.split("**").forEachIndexed { k, part ->
            if (k > 0) bold = !bold
            if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
        }
    }
}

/** The models this phone can run, with download, use and delete. A big download on mobile data or with no internet asks first. */
@Composable
private fun ModelList() {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf<Pair<AiModel, String>?>(null) } // the model, and the network: "metered" or "none"
    fun start(m: AiModel) {
        LocalAi.spaceProblem(m)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); return }
        when (val net = LocalAi.network(ctx)) { "free" -> LocalAi.download(m); else -> ask = m to net }
    }
    Txt("Models run entirely on this ${Here.device}. Bigger ones answer better but slower. This ${Here.device} has ${LocalAi.memoryGB} GB of memory" +
        (if (Here.allModels) ". Each model says what it needs. They are uncensored versions that other people made: they answer anything, and they can be wrong." else ": only models that fit are shown."),
        14, color = Theme.dim)
    // Android's downloader (a computer: a plain web request) fetches the file directly, whatever the Tor switch says.
    Txt("Model downloads don't go through Tor.", 14, color = Theme.dim)
    if (LocalAi.catalog.isEmpty()) Txt("No model fits a phone with less than 4 GB of memory.", 15, FontWeight.SemiBold, Theme.help)
    LocalAi.catalog.forEach { m ->
        val st = LocalAi.states[m.id] ?: LocalAi.State.Missing
        val gb = "%.1f".format(m.bytes / 1e9)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(m.title, 18, FontWeight.Bold, modifier = Modifier.weight(1f))
                if (LocalAi.selected == m.id && st == LocalAi.State.Ready) Chip("In use", Theme.safe)
            }
            Txt(m.detail, 15, color = Theme.dim)
            Txt("$gb GB · ${m.license}" + (LocalAi.typicalSeconds(m)?.let { " · about $it s per answer here" } ?: ""), 14, color = Theme.dim)
            when (st) {
                LocalAi.State.Missing -> {
                    PrimaryButton("Download $gb GB", icon = R.drawable.ic_download) { start(m) }
                    Txt("Big: use Wi-Fi if you can. It takes a while; you can keep using DarkProof.", 14, color = Theme.dim)
                }
                is LocalAi.State.Downloading -> {
                    Txt(st.waiting ?: "Downloading ${(st.progress * 100).toInt()}%. Keep the internet on.", 15, FontWeight.SemiBold, if (st.waiting != null) Theme.dim else Theme.text)
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Theme.tile)) {
                        Box(Modifier.fillMaxWidth(st.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(Theme.text, CircleShape))
                    }
                    GhostButton("Cancel") { LocalAi.cancel(m) }
                }
                LocalAi.State.Verifying -> Txt("Checking the download…", 15, FontWeight.SemiBold, Theme.dim)
                LocalAi.State.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (LocalAi.selected != m.id) GhostButton("Use this model") { LocalAi.select(m) }
                    GhostButton("Delete", enabled = !LocalAi.busy) { LocalAi.delete(m) }
                }
                is LocalAi.State.Failed -> {
                    Txt(st.why, 15, color = Theme.help)
                    GhostButton("Try again") { start(m) }
                }
            }
        }
    }
    ask?.let { (m, net) ->
        androidx.compose.material3.AlertDialog({ ask = null }, containerColor = Theme.card,
            title = { Txt(if (net == "none") "No internet right now" else "You're on mobile data", 20, FontWeight.Bold) },
            text = { Txt(if (net == "none") "DarkProof can download the ${"%.1f".format(m.bytes / 1e9)} GB model by itself as soon as there is Wi-Fi."
                         else "This download is ${"%.1f".format(m.bytes / 1e9)} GB. On mobile data that can cost a lot or use up your plan.", 15, color = Theme.dim) },
            confirmButton = {
                Row {
                    if (net != "none") androidx.compose.material3.TextButton({ ask = null; LocalAi.download(m, mobileData = true) }) { Txt("Use mobile data", 16, FontWeight.SemiBold, Theme.dim) }
                    androidx.compose.material3.TextButton({ ask = null; LocalAi.download(m, mobileData = false) }) { Txt("Wait for Wi-Fi", 16, FontWeight.Bold) }
                }
            },
            dismissButton = { androidx.compose.material3.TextButton({ ask = null }) { Txt("Cancel", 16, FontWeight.SemiBold, Theme.dim) } })
    }
}
