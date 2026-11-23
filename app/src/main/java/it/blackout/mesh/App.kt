package it.blackout.mesh

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * QR codes and shared invites use the app link (darkproof://) for now. Set to true once darkproof.app serves its App
 * Links / Universal Links files, so invites also work for people who don't have the app yet (Invite.webUrl).
 */
const val INVITE_WEB_LINKS = false
fun inviteLink(g: SavedGroup, pins: String) = if (INVITE_WEB_LINKS) Invite.webUrl(g.name, g.code, pins) else Invite.url(g.name, g.code, pins)

@Composable
fun DarkProofApp(s: AppState, a: MainActivity) {
    // The photo picker is asked for here, above the lock screen: the chat isn't drawn while the app is locked (in
    // high-risk mode it locks whenever it is left, the picker included), and a photo coming back to it would be lost.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> photoChosen(a, uri) { s.sheet = it } }
    MaterialTheme(colorScheme = darkColorScheme(background = Theme.bg, surface = Theme.bg, primary = Theme.text, onPrimary = Color.Black)) {
        CompositionLocalProvider(LocalPhotoPicker provides { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        Box(Modifier.fillMaxSize().background(Theme.bg)) {
            Wipe.erased?.let { lines -> EmptyScreen(lines) { Wipe.emptySeen() } } // "This phone is empty", once, after "Erase everything"
            val g = s.group
            if (g == null) {
                SetupFlow(s.invite, askName = true, modifier = Modifier.statusBarsPadding().navigationBarsPadding(), myName = s.name, dropInvite = { s.invite = null }) { name, group, created ->
                    a.started(name, group); s.invite = null
                    if (created) s.sheet = Sheet.Invite
                }
            } else if (s.locked) {
                LockScreen(a, s.waitingSafe)
            } else if (s.introPermissions) {
                PermissionIntro(a)
            } else {
                MainView(s, a)
                s.invite?.let { i ->
                    val saved = Groups.find(a, i.code) != null
                    val twin = !saved && Groups.all(a).any { it.name.equals(i.name, ignoreCase = true) } // a look-alike: check-ins would go to someone else
                    Confirm(if (twin) "Another group called ${i.name}?" else if (saved) "Switch to ${i.name}?" else "Join ${i.name}?",
                        if (twin) "You already have a group called ${i.name}, and this invite is for a different one. Someone could be copying the name to get your check-ins. Only join if you trust who sent it." else "You'll see ${i.name}'s messages instead of ${g.name}'s. To switch back, tap the group name on Home.", if (saved) "Switch" else "Join",
                        onYes = { a.switchTo(Groups.find(a, i.code) ?: i); s.invite = null; s.tab = 0 }, onNo = { s.invite = null })
                }
            }
        }
        }
    }
}

/** "Lock DarkProof": nothing of the app shows here, but asking for help doesn't need unlocking. */
@Composable
private fun LockScreen(a: MainActivity, waitingSafe: Boolean) {
    var ask by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Logo(88.dp)
        Txt("DarkProof is locked", 26, FontWeight.Black)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Unlock") { AppLock.ask(a, "Unlock DarkProof") { a.justUnlocked() } }
        DangerButton("I need help") { ask = true }
        if (sent) Txt("Sent to your group.", 16, FontWeight.SemiBold, Theme.safe)
        if (waitingSafe) { // from the shortcut: shown, so an unlock never sends it unseen
            Txt("“I'm safe” goes to your group when you unlock, if you do within 2 minutes.", 16, color = Theme.dim)
            GhostButton("Don't send it") { a.dropWaiting() }
        }
    }
    if (ask) Confirm("Send “I need help”?", "Your group gets it right away. You don't need to unlock.", "Send", danger = true,
        onYes = { ask = false; QuickCheckIn.send(a, CheckIn.HELP); sent = true }, onNo = { ask = false })
}

// MARK: - Setup

/**
 * Welcome, then create or join a group. [askName] is false when adding another group later ([myName] is known then).
 * [dropInvite]: back from an invite that was opened, to the welcome screen.
 */
@Composable
fun SetupFlow(invite: SavedGroup?, askName: Boolean, modifier: Modifier = Modifier, inSheet: Boolean = false, myName: String = "",
              dropInvite: () -> Unit = {}, done: (name: String, group: SavedGroup, created: Boolean) -> Unit) {
    val start = if (askName) "welcome" else "choose"
    var mode by rememberSaveable(invite) { mutableStateOf(if (invite != null) "join" else start) }
    var name by rememberSaveable { mutableStateOf(if (askName) myName else "") } // kept from before, when the last group was left
    var groupName by rememberSaveable(invite) { mutableStateOf(invite?.name ?: "") }
    var code by rememberSaveable(invite) { mutableStateOf(invite?.code ?: "") }
    // The admins named by the invite link the code came from (spec "Invites"). They count only if the code is still that one when the user joins.
    var linkCode by rememberSaveable(invite) { mutableStateOf(invite?.code ?: "") }
    var linkPins by rememberSaveable(invite) { mutableStateOf(invite?.pins ?: "") }
    val ctx = LocalContext.current
    fun back() { if (invite != null) dropInvite() else mode = start }
    BackHandler(enabled = mode != start) { back() } // the system Back goes back a step, not out of the app
    // In a sheet, the sheet scrolls: a second vertical scroll inside it would crash.
    val frame = if (inSheet) modifier.fillMaxWidth() else modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 16.dp)
    Column(frame, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (mode) {
            "welcome", "choose" -> {
                Spacer(Modifier.height(if (askName) 56.dp else 8.dp))
                Logo(96.dp)
                Spacer(Modifier.height(8.dp))
                Txt("Stay connected when everything goes dark.", 36, FontWeight.Black, lineHeight = 40)
                Txt("Messages travel phone to phone over Bluetooth and Wi-Fi, and over the internet when there is some. No account.", 17, color = Theme.dim)
                if (askName) NotAllErased()
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Create a group") { mode = "create" }
                SecondaryButton("Join a group") { mode = "join" }
                Txt("Free and private. No servers of ours.", 14, color = Theme.dim, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            "create" -> {
                FormTitle("Create a group", "For your family, friends or building. DarkProof makes a secret code for it, then you invite people with a QR code.") { back() }
                if (askName) InputField("Your name", name, { name = it.take(40) }, "Anna", keyboard = KeyboardOptions(KeyboardCapitalization.Words))
                val who = (if (askName) name else myName).trim()
                val fallback = remember(who) { // "Ana's group", or "Ana's group 2" when that name is taken
                    val base = (if (who.isNotEmpty()) "$who's group" else "My group").take(Invite.MAX_GROUP_NAME - 2)
                    val taken = Groups.all(ctx).map { it.name }.toSet()
                    generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
                }
                InputField("Group name (you can leave it)", groupName, { groupName = it.take(Invite.MAX_GROUP_NAME) }, fallback, keyboard = KeyboardOptions(KeyboardCapitalization.Words))
                PrimaryButton("Create group", enabled = !askName || name.isNotBlank()) {
                    val code = Invite.newCode()
                    MeshEngine.found(ctx, code) // its first epoch is made on this phone (v0.6)
                    done(name.trim(), SavedGroup(groupName.trim().ifEmpty { fallback }, code), true)
                }
                if (askName && name.isBlank()) Txt("Type your name first. Your group sees it next to your messages.", 14, color = Theme.dim)
            }
            else -> {
                if (invite != null) FormTitle("You're invited", "Join ${invite.name} to message each other without internet.") { back() }
                else FormTitle("Join a group", if (Here.scan) "Scan the invite QR code on your friend's phone, or type the code they tell you." else "Type the code your friend tells you, or paste the invite link they send.") { back() }
                if (askName) InputField("Your name", name, { name = it.take(40) }, "Anna", keyboard = KeyboardOptions(KeyboardCapitalization.Words))
                if (invite == null) {
                    if (Here.scan) SecondaryButton("Scan invite QR code", icon = R.drawable.ic_qr_code_2) {
                        scanInvite(ctx) { g -> code = g.code; groupName = g.name; linkCode = g.code; linkPins = g.pins }
                    }
                    // A pasted invite link works too: its code is used and its name filled in.
                    InputField("Group code", code, { v -> Groups.readInvite(v)?.let { g -> code = g.code; linkCode = g.code; linkPins = g.pins; if (groupName.isBlank() || groupName == "Group") groupName = g.name } ?: run { code = v.take(64) } },
                        "XXXX-XXXX-XXXX-XXXX", keyboard = KeyboardOptions(KeyboardCapitalization.Characters, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                    InputField("Group name (you can leave it)", groupName, { groupName = it.take(Invite.MAX_GROUP_NAME) }, "Family", keyboard = KeyboardOptions(KeyboardCapitalization.Words))
                }
                val clean = Invite.normalizeCode(code)
                val why = when {
                    askName && name.isBlank() -> "Type your name first. Your group sees it next to your messages."
                    code.isBlank() -> if (Here.scan) "Scan the QR code, or type or paste the group code." else "Type or paste the group code or the invite link."
                    !Invite.isGeneratedCode(clean) -> "That isn't a code DarkProof made. Codes look like XXXX-XXXX-XXXX-XXXX."
                    else -> null
                }
                PrimaryButton(if (invite != null) "Join ${invite.name}" else "Join", enabled = why == null) {
                    done(name.trim(), SavedGroup(groupName.trim().ifEmpty { invite?.name ?: "Group" }, clean, if (clean == linkCode) linkPins else ""), false)
                }
                if (why != null) Txt(why, 14, color = Theme.dim)
            }
        }
    }
}

@Composable private fun FormTitle(title: String, sub: String, back: (() -> Unit)?) {
    if (back != null) Txt("Back", 16, FontWeight.SemiBold, Theme.dim, Modifier.tap(Press(down = 0.95f, shape = RoundedCornerShape(10.dp), dim = 0.35f, buzz = Buzz.Tap), onClick = back).padding(vertical = 12.dp, horizontal = 4.dp))
    else Spacer(Modifier.height(24.dp))
    Txt(title, 32, FontWeight.Black, lineHeight = 38)
    Txt(sub, 16, color = Theme.dim)
    Spacer(Modifier.height(4.dp))
}

/** The last setup step: what Android is about to ask, and why, before it asks. */
@Composable
private fun PermissionIntro(a: MainActivity) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(40.dp))
        Logo(64.dp)
        Txt("One more step", 32, FontWeight.Black, lineHeight = 38)
        Txt("DarkProof will ask for nearby devices, location and notifications so phones can find each other and you get alerts.", 17, color = Theme.dim)
        IntroRow(R.drawable.ic_wifi, "Nearby devices", "Connects to phones around you over Bluetooth and Wi-Fi. Without it DarkProof can't reach anyone.")
        IntroRow(R.drawable.ic_location_on, "Location", "Android needs it to find phones nearby. Others see where you are only if you turn that on.")
        IntroRow(R.drawable.ic_notifications, "Notifications", "Tells you when someone in your group needs help or writes.")
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Continue") { a.permissions() }
    }
}

@Composable private fun IntroRow(@DrawableRes icon: Int, title: String, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(44.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Ic(icon, Theme.text, 22.dp) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(title, 17, FontWeight.SemiBold); Txt(text, 15, color = Theme.dim)
    }
}

// MARK: - Main

sealed class Sheet {
    object Invite : Sheet(); object Groups : Sheet(); object Settings : Sheet(); object Radio : Sheet(); object NewGroup : Sheet()
    data class Find(val title: String, val spot: Spot, val ts: Long) : Sheet()
    object AddPlace : Sheet()
    data class SendPhoto(val jpeg: ByteArray) : Sheet()
    data class ViewPhoto(val jpeg: ByteArray) : Sheet()
    /** After "I need help" went out: what is needed, how many people. [group]: where it went (null: the group in use). */
    data class HelpDetails(val group: String?) : Sheet()
    object Practice : Sheet()
    object Vouch : Sheet()
    data class Person(val id: String) : Sheet()
    /** "Check Ben": their code to scan, or their emoji to compare. */
    data class Check(val id: String) : Sheet()
    object Joins : Sheet()
    object Security : Sheet()
    /** "Messages disappear after": the group's timer (spec "Disappearing messages"), from the "+" tray and Group security. */
    object Timer : Sheet()
    /** "How it got there" for one of this phone's own group messages (spec "Journey notes"). */
    data class Journey(val id: String) : Sheet()
    /** "If I go quiet": this phone's check-in clock in the group in use (spec "If I go quiet"). */
    object Quiet : Sheet()
    /** "Finding Marco": the Bluetooth signal of person [id]'s phone, after they asked for help (round 2c, behind the trial switch). */
    data class Finder(val id: String) : Sheet()

    companion object {
        /** As text, to come back after Android ended the app in the background (photos are not kept). */
        fun save(s: Sheet): String? = when (s) {
            Invite -> "invite"; Groups -> "groups"; Settings -> "settings"; Radio -> "radio"; NewGroup -> "newgroup"; AddPlace -> "addplace"
            Practice -> "practice"; Vouch -> "vouch"; Joins -> "joins"; Security -> "security"; Timer -> "timer"; Quiet -> "quiet"
            is Person -> "person\n${s.id}"
            is Check -> "check\n${s.id}"
            is Journey -> "journey\n${s.id}"
            is Finder -> "finder\n${s.id}"
            is HelpDetails -> "help\n${s.group ?: ""}"
            is Find -> "find\n${s.title}\n${s.spot.lat}\n${s.spot.lon}\n${s.spot.accuracy ?: ""}\n${s.ts}"
            is SendPhoto, is ViewPhoto -> null
        }
        fun restore(t: String): Sheet? {
            val p = t.split("\n")
            return when (p[0]) {
                "invite" -> Invite; "groups" -> Groups; "settings" -> Settings; "radio" -> Radio; "newgroup" -> NewGroup; "addplace" -> AddPlace
                "practice" -> Practice; "vouch" -> Vouch; "joins" -> Joins; "security" -> Security; "timer" -> Timer; "quiet" -> Quiet
                "person" -> p.getOrNull(1)?.let(::Person)
                "check" -> p.getOrNull(1)?.let(::Check)
                "journey" -> p.getOrNull(1)?.let(::Journey)
                "finder" -> p.getOrNull(1)?.let(::Finder)
                "help" -> HelpDetails(p.getOrNull(1)?.ifEmpty { null })
                "find" -> runCatching { Find(p[1], Spot(p[2].toDouble(), p[3].toDouble(), p[4].toIntOrNull()), p[5].toLong()) }.getOrNull()
                else -> null
            }
        }
    }
}

/** Phones linked directly right now (Nearby, Bluetooth LE, Wi-Fi network), each once; not radios or relays. */
fun phonesInRange(): Int = MeshEngine.linkedNodes().size

/** Every way out of this phone right now, so texts say truthfully where a message goes. */
data class Links(val phones: Int, val relays: Int, val radio: Boolean) {
    val any get() = phones > 0 || relays > 0 || radio
    companion object {
        fun now() = Links(phonesInRange(), MeshEngine.nostr?.connected ?: 0, MeshEngine.lora?.connected == true || MeshEngine.meshcore?.connected == true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainView(s: AppState, a: MainActivity) {
    val tick = s.tick
    val moderated = s.moderated
    val messages = remember(tick, moderated) { MeshEngine.snapshot().filter { Moderation.visible(a, it) } } // blocked and reported hidden everywhere
    val all = remember(tick, moderated) { MeshEngine.snapshotAll().filter { Moderation.visible(a, it) } }
    val phones = remember(tick) { phonesInRange() }
    val g = s.group ?: return
    // Read: the group's chat on screen. Unread: the Chat tab's badge.
    LaunchedEffect(s.tab, s.everyone, all, g) {
        if (s.tab == 1 && !s.everyone) all.lastOrNull { it.group == g.code }?.let { ReadMarks.markRead(a, g.code, it.ts) }
    }
    val unread = remember(all, s.tab, s.everyone, g) { if (s.tab == 1 && !s.everyone) 0 else ReadMarks.unread(a, all, g.code) }
    BackHandler(enabled = s.tab != 0) { s.tab = 0 } // Back from another tab goes Home first
    Box(Modifier.fillMaxSize()) {
        AsciiBackdrop(AsciiBackground.style(a), Modifier.matchParentSize()) // Settings > Background: behind the screens, not behind sheets
        Box(Modifier.fillMaxSize().glassSource().statusBarsPadding()) { // the tab bar floats over it
            when (s.tab) {
                1 -> ChatTab(s, g, messages, phones) { s.sheet = it }
                2 -> PlacesTab(s, messages) { s.sheet = it }
                3 -> PeopleTab(s, a, g, messages, phones) { s.sheet = it }
                4 -> AskTab(g, messages)
                else -> HomeTab(s, a, g, messages, all) { s.sheet = it }
            }
        }
        if (!((s.tab == 1 || s.tab == 4) && WindowInsets.isImeVisible)) TabBar(s.tab, unread, Modifier.align(Alignment.BottomCenter)) { s.tab = it } // hidden while typing
        s.banner?.let { AlertBanner(it, s, a, Modifier.align(Alignment.TopCenter)) }
    }
    Overlays(s, a, g, messages, all)
}

/** The sheets and notices that open over the main view, whichever layout it has (the phones' tabs, a computer's panes). */
@Composable
internal fun Overlays(s: AppState, a: MainActivity, g: SavedGroup, messages: List<Message>, all: List<Message>) {
    val close = { s.sheet = null }
    when (val sh = s.sheet) {
        Sheet.Invite -> { LaunchedEffect(Unit) { Ready.mark(a, Ready.INVITE) }; InviteSheet(g, a, close) }
        Sheet.Groups -> GroupsSheet(g, a, all, { s.sheet = Sheet.Invite }, { s.sheet = Sheet.NewGroup }, close)
        Sheet.NewGroup -> SheetPage("Another group", close) {
            SetupFlow(null, askName = false, inSheet = true, myName = s.name) { _, group, created -> a.switchTo(group); s.tab = 0; s.sheet = if (created) Sheet.Invite else null }
        }
        Sheet.Settings -> SettingsSheet(s, a, all, { s.sheet = Sheet.Invite }, { s.sheet = Sheet.NewGroup }, close)
        Sheet.Radio -> SettingsSheet(s, a, all, { s.sheet = Sheet.Invite }, { s.sheet = Sheet.NewGroup }, close, connections = true)
        is Sheet.Find -> FindSheet(sh, close)
        Sheet.AddPlace -> AddPlaceSheet(close)
        is Sheet.SendPhoto -> SendPhotoSheet(s, sh.jpeg, close)
        is Sheet.ViewPhoto -> ViewPhotoSheet(sh.jpeg, close)
        is Sheet.HelpDetails -> HelpDetailsSheet(s, a, sh.group, close)
        Sheet.Practice -> PracticeSheet(a, close)
        Sheet.Vouch -> VouchSheet(s, a, close)
        is Sheet.Person -> PersonSheet(s, a, messages, sh.id, close)
        is Sheet.Check -> CheckSheet(s, a, messages, sh.id, close)
        Sheet.Joins -> JoinSheet(s, g, close)
        Sheet.Security -> SecuritySheet(s, a, g, close)
        Sheet.Timer -> TimerSheet(s, a, g, close)
        is Sheet.Journey -> JourneySheet(s, sh.id, close)
        Sheet.Quiet -> QuietSheet(s, g, close)
        is Sheet.Finder -> FinderSheet(s, a, messages, sh.id, close)
        null -> {}
    }
    if (s.askHelp) HelpConfirm(s, a)
    if (s.tileHowTo) Info("Add the “I'm safe” tile", "Swipe down twice from the top of the screen, tap the pencil, then drag “I'm safe” into your tiles. " +
        "One tap then tells your group you're safe, without opening DarkProof.") { s.tileHowTo = false; Ready.mark(a, Ready.TILE) }
}

/**
 * "I need help" from Home, People, a shortcut, a tile, a banner or a notification: opens the help sheet, where one tap
 * sends it, with what is needed if the user adds it first. One message, like the iPhone (the old confirm dialog is unused).
 */
@Composable
private fun HelpConfirm(s: AppState, a: MainActivity) {
    LaunchedEffect(Unit) { s.askHelp = false; s.sheet = Sheet.HelpDetails(s.helpGroup) }
}

@Suppress("unused")
@Composable
private fun HelpConfirmDialog(s: AppState, a: MainActivity) {
    val links = remember(s.tick) { Links.now() }
    val to = Groups.find(a, s.helpGroup)?.name ?: s.group?.name ?: "your group"
    val where = if (s.shareLocation) ", with where you are" else ""
    Confirm("Send “I need help” to $to?",
        (if (links.any) "It goes out now and hops from phone to phone to your group$where."
         else "No phone or internet is in reach right now. It waits on this ${Here.device}$where and goes out as soon as one is.") + " Next you can say what you need.",
        "Send", danger = true,
        onYes = { s.askHelp = false; if (checkIn(a, s, CheckIn.HELP, group = s.helpGroup)) s.sheet = Sheet.HelpDetails(s.helpGroup) },
        onNo = { s.askHelp = false })
}

/** While DarkProof is open: someone needs help, asks if I'm safe, or another group wrote. */
@Composable
private fun AlertBanner(al: Notifier.Alert, s: AppState, a: MainActivity, modifier: Modifier) {
    // A check-in clock ran out (spec "If I go quiet"): neutral, not red, it stays until closed, and it opens People.
    val quiet = al.messageId.startsWith("quiet-")
    LaunchedEffect(al) { if (!al.urgent && !quiet) { kotlinx.coroutines.delay(6_000); if (s.banner == al) s.banner = null } }
    fun openChat() {
        s.banner = null
        Groups.find(a, al.group)?.takeIf { it.code != s.group?.code }?.let(a::switchTo)
        if (quiet) { s.tab = 3; return }
        s.everyone = false; s.tab = 1
    }
    val color = if (al.urgent && !al.ask) Theme.help else Theme.text
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
        .glow(Color.Black, 0.5f, 12.dp, 0.dp) // it floats over the screen
        .tap(Press(down = 0.98f, shape = shape, dim = 0f, light = 0.05f, buzz = Buzz.None)) { openChat() }
        .background(Theme.tile, shape).litEdge(shape, 0.13f)
        .then(if (al.urgent) Modifier.border(1.dp, color.copy(alpha = 0.6f), shape) else Modifier) // red only for help
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic(if (quiet) R.drawable.ic_schedule else if (al.ask) R.drawable.ic_notifications else if (al.urgent) R.drawable.ic_sos else R.drawable.ic_chat, color, 24.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(al.title, 17, FontWeight.Bold, color, maxLines = 2)
                Txt(al.body, 14, color = Theme.dim, maxLines = if (quiet) 3 else 2)
            }
            RoundIcon(R.drawable.ic_close, 34.dp, desc = "Close") { s.banner = null }
        }
        if (al.ask) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapsuleChoice("I'm safe", Theme.safe, Color.Black, Modifier.weight(1f)) { s.banner = null; checkIn(a, s, CheckIn.SAFE, group = al.group) }
            CapsuleChoice("I need help", Theme.help, Color.White, Modifier.weight(1f)) { s.banner = null; s.helpGroup = al.group; s.askHelp = true }
        }
    }
}

/** A colored capsule button for the one-tap answers. */
@Composable fun CapsuleChoice(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, tap: () -> Unit) =
    Box(modifier.heightIn(min = 48.dp).tap(onClick = tap).background(bg, CircleShape).litEdge(CircleShape, 0.25f), contentAlignment = Alignment.Center) {
        Txt(text, 16, FontWeight.Bold, fg, maxLines = 1)
    }

/** The phone's five tabs, in order: the name and the icon. */
private val PhoneTabs = listOf("Home" to R.drawable.ic_home, "Chat" to R.drawable.ic_chat,
    "Places" to R.drawable.ic_location_on, "People" to R.drawable.ic_group, "Ask" to R.drawable.ic_auto_awesome)

private val PillWhite = Brush.verticalGradient(listOf(Color.White, Color(0xFFE5E5E5)))
private val PillInk = Color(0xFF111111)

/**
 * The floating tab bar: a glass capsule over the screen (solid, so nothing under it shows through: [glass]) with a lit
 * top edge. The tab on screen is a white pill with its black icon and name; the others are icons only, in dim white,
 * and TalkBack reads each by name ("Chat, 3 new"). The tab on screen is twice as wide, so the pill slides and stretches
 * to it with one spring while the widths move with it. In calm mode it is simply there ([Motion]).
 */
@Composable
private fun TabBar(selected: Int, unread: Int, modifier: Modifier, pick: (Int) -> Unit) {
    val grow = PhoneTabs.indices.map { i -> animateFloatAsState(if (i == selected) 1f else 0f, Motion.slide(), label = "pill$i") }
    val press = remember { Press(down = 0.9f, shape = CircleShape, dim = 0f, lift = 0.04f, light = 0.07f, buzz = Buzz.Tick) }
    Row(modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().height(60.dp)
        .glow(Color.Black, 0.5f, 12.dp, 0.dp)
        .glass(CircleShape, Color(0xFF2F2F31), Color(0xFF242426)).litEdge(CircleShape, 0.13f)
        .padding(5.dp).selectableGroup()
        .drawBehind {
            // Where the widths put the pill: each tab is 1 wide, the one on screen 2. While they change, the pill is
            // the blend of the tabs that are growing and shrinking (the springs always add up to one), so it stays under them.
            val unit = size.width / (PhoneTabs.size + 1)
            var x = 0f; var left = 0f; var width = 0f
            grow.forEach { g -> val e = g.value; val w = unit * (1 + e); left += e * x; width += e * w; x += w }
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(left, 2.dp.toPx()), Size(width, size.height), r) // it sits on the glass
            drawRoundRect(PillWhite, Offset(left, 0f), Size(width, size.height), r)
        }, verticalAlignment = Alignment.CenterVertically) {
        PhoneTabs.forEachIndexed { i, (label, icon) ->
            val e = grow[i].value
            val t = e.coerceIn(0f, 1f)
            val badge = if (i == 1 && unread > 0) unread else 0
            Row(Modifier.weight(1f + e).fillMaxHeight().sizeIn(minWidth = 48.dp, minHeight = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                .selectable(i == selected, interactionSource = null, indication = press, role = Role.Tab) { pick(i) }
                .semantics { contentDescription = if (badge > 0) "$label, $badge new" else label },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Ic(icon, lerp(Theme.dim, PillInk, t), 22.dp)
                    // The count of new messages: a small white dot with the number, black on the white pill.
                    if (badge > 0) Text(if (badge > 99) "99+" else "$badge", Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-5).dp)
                        .clearAndSetSemantics {}.background(if (t > 0.5f) PillInk else Color.White, CircleShape)
                        .defaultMinSize(minWidth = 16.dp).padding(horizontal = 4.dp),
                        color = if (t > 0.5f) Color.White else Color.Black, fontSize = 10.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
                }
                if (t > 0.02f) {
                    Spacer(Modifier.width(((if (badge > 0) 16 else 7) * t).dp)) // room for the badge on the icon's corner
                    Text(label, Modifier.clearAndSetSemantics {}, color = PillInk.copy(alpha = t * t), fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

/** False (after telling the user) while the message store is still opening, so nothing is silently lost. */
fun meshReady(c: android.content.Context): Boolean {
    if (MeshEngine.router != null) return true
    Toast.makeText(c, "DarkProof is still starting. Try again in a moment.", Toast.LENGTH_LONG).show()
    return false
}

/**
 * Sends a check-in with battery and, if the user turned it on, position (waiting up to 10 s for one), to [group] or
 * the group in use. Says truthfully whether it went out and whether the position is in it. False if it couldn't be sent.
 */
fun checkIn(a: MainActivity, s: AppState, c: CheckIn, needs: Set<Need> = emptySet(), people: Int? = null, group: String? = null): Boolean {
    if (!meshReady(a)) return false
    val battery = batteryPercent(a)
    val to = Groups.find(a, group)?.name ?: s.group?.name ?: "your group"
    var answered = false
    fun go(spot: Spot?, lost: Boolean) {
        answered = true
        Thread { MeshEngine.send(c.text(battery, spot, needs, people), group = group) }.start()
        val pos = if (lost) " without your position: no GPS fix yet" else ""
        Toast.makeText(a, if (Links.now().any) "Sent to $to$pos" else "Saved$pos. It goes out when a phone or the internet is in reach.", Toast.LENGTH_LONG).show()
    }
    if (s.shareLocation && Locator.allowed(a)) {
        Locator.fix(a) { go(it, it == null) }
        if (!answered) Toast.makeText(a, "Finding your position…", Toast.LENGTH_SHORT).show()
    } else go(null, false)
    return true
}

/**
 * "I'm safe" from Home's tile, People's tile or the chat's "+" tray: sent as [checkIn] sends it, then one Buzz.Confirm
 * (also in calm mode), the buzz of the sent moment ([SafeMoment]). True if it went out or waits to.
 */
fun sendSafe(a: MainActivity, s: AppState): Boolean = checkIn(a, s, CheckIn.SAFE).also { if (it) Buzz.play(Buzz.Confirm) }

@Composable
private fun HomeTab(s: AppState, a: MainActivity, g: SavedGroup, messages: List<Message>, all: List<Message>, open: (Sheet) -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    val tick = s.tick
    val links = remember(tick) { Links.now() }
    val people = remember(messages) { Person.all(messages, MeshEngine.deviceId) }
    val mine = remember(messages) { messages.lastOrNull { !it.public && it.sender == MeshEngine.deviceId && CheckIn.of(it.text) != null } }
    val others = remember(all) { Groups.others(a).map { o -> ReadMarks.unread(a, all, o.code) to groupNeedsHelp(all, o.code) } }
    val hours = remember(tick / 20) { BatteryLife.hoursLeft(a) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).tap(Press(down = 0.97f, shape = RoundedCornerShape(16.dp), dim = 0f, light = 0.05f, buzz = Buzz.Tap)) { open(Sheet.Groups) }
                .semantics { contentDescription = "${g.name}. Switch group" },
                verticalAlignment = Alignment.CenterVertically) {
                Logo(36.dp); Spacer(Modifier.width(12.dp))
                Txt(g.name, 26, FontWeight.Black, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Ic(R.drawable.ic_expand_more, Theme.dim, 26.dp)
                // Something new in another group: a dot (red if someone there needs help).
                if (others.any { it.second }) Box(Modifier.size(10.dp).background(Theme.help, CircleShape))
                else if (others.any { it.first > 0 }) Box(Modifier.size(10.dp).background(Theme.text, CircleShape))
            }
            RoundIcon(R.drawable.ic_settings, desc = "Settings") { open(Sheet.Settings) }
        }
        PhoneProblems(s, a)
        // Hero: the logo's shape, the number of phones in range, and every other way out.
        val hero = remember { vaultShape() }
        val heroPress = remember { Press(down = 0.99f, shape = hero, dim = 0f, light = 0.03f, lift = 0.03f, buzz = Buzz.None) }
        Column(Modifier.fillMaxWidth().tap(heroPress, role = null) { details = !details }.background(Theme.card, hero).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val (label, on) = when {
                links.phones > 0 -> "Connected" to true
                links.relays > 0 -> "Online" to true
                links.radio -> "LoRa radio linked" to true
                s.permissionsMissing -> "Not looking for phones" to false
                else -> "Looking for phones" to false
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (on) Theme.safe else Theme.dim, CircleShape))
                Spacer(Modifier.width(10.dp))
                Txt(label, 17, FontWeight.SemiBold, if (on) Theme.safe else Theme.dim, Modifier.weight(1f))
                Ic(R.drawable.ic_expand_more, Theme.dim, 24.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("${links.phones}", 88, FontWeight.Black, lineHeight = 96)
                Spacer(Modifier.width(14.dp))
                Txt(if (links.phones == 1) "phone\nin range" else "phones\nin range", 26, FontWeight.Bold, Theme.dim, lineHeight = 28)
            }
            if (links.phones > 0) Txt("Your messages hop from phone to phone.", 15, color = Theme.dim)
            if (links.relays > 0) Txt("Online · your messages also travel over the internet (${links.relays} ${if (links.relays == 1) "relay" else "relays"}).", 15, color = Theme.dim)
            if (links.radio) Txt("LoRa radio linked · short messages also reach radios kilometers away.", 15, color = Theme.dim)
            if (!links.any) Txt(if (s.permissionsMissing) "Your messages wait on this ${Here.device}. DarkProof can look for phones once you allow Nearby devices."
                else "Your messages wait on this ${Here.device} and go out as soon as a phone is in range. " +
                    (if (Here.background) "DarkProof keeps looking, also when the app is closed." else "DarkProof looks for phones while it is open."), 15, color = Theme.dim)
            Spacer(Modifier.height(4.dp))
            LinkDials(s) { open(Sheet.Radio) }
            if (details) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))
                linkLines().forEach { Txt(it, 14, color = Theme.dim) }
            }
        }
        SecurityRow(s, a, g) { open(Sheet.Security) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            var safeSent by remember { mutableIntStateOf(0) }
            ActionTile("I'm safe", R.drawable.ic_verified_user, Theme.safe, true, Modifier.weight(1f), rememberSafeMoment(safeSent)) { if (sendSafe(a, s)) safeSent++ }
            ActionTile("Need help", R.drawable.ic_sos, Theme.help, true, Modifier.weight(1f)) { s.helpGroup = null; s.askHelp = true }
            ActionTile("Invite", R.drawable.ic_qr_code_2, Theme.text, false, Modifier.weight(1f)) { open(Sheet.Invite) }
            ActionTile("Connect", R.drawable.ic_settings_input_antenna, Theme.text, false, Modifier.weight(1f)) { open(Sheet.Radio) }
        }
        HomeQuietRow(s, g) { open(Sheet.Quiet) } // under the check-in buttons, as on the approved board (spec "If I go quiet")
        mine?.let { m -> Txt("Your last check-in: ${if (CheckIn.of(m.text) == CheckIn.HELP) "need help" else "safe"}, ${ago(m.ts)}.", 15, color = Theme.dim) }
        hours?.let { h -> batteryPercent(a)?.let { p -> Txt("Battery $p% · about $h h left at this rate.", 15, color = if (h <= 3) Theme.help else Theme.dim) } }
        ReadyCard(s, a, people.isNotEmpty())
        if (!links.any) Card {
            Txt("No one in range yet", 20, FontWeight.Bold)
            Tip(R.drawable.ic_wifi, "Open DarkProof on both phones and stay within about 10–50 m. Walls cut the range: try outside or by a window.")
            Tip(R.drawable.ic_group, "Farther away? Messages hop through any phone with DarkProof in between. For kilometers, pair a LoRa radio.")
        }
        SectionTitle("Your people", if (people.size > 3) "See all" else null) { s.tab = 3 }
        if (people.isEmpty()) Card {
            Txt("No one from ${g.name} yet", 18, FontWeight.Bold)
            Txt("Invite friends with a QR code. People show up here once they write or check in.", 15, color = Theme.dim)
            GhostButton("Invite") { open(Sheet.Invite) }
        } else Card(padding = 12.dp) {
            people.take(3).forEach { p -> PersonRow(p, { open(Sheet.Person(p.id)) }) { sp -> open(Sheet.Find(p.name, sp, p.spotTs)) } }
        }
    }
}

/** Whether someone in the saved group [code] needs help (their latest check-in, or a report). */
fun groupNeedsHelp(all: List<Message>, code: String) =
    Person.all(all.filter { it.group == code }, MeshEngine.deviceId).any { it.checkIn == CheckIn.HELP }

/** Each way messages travel, in plain words (Home details, Connections). */
fun linkLines(): List<String> = listOfNotNull(
    ("Nearby: " + MeshEngine.transportStatus.replaceFirstChar { it.lowercase() }).takeIf { Here.bluetooth },
    (MeshEngine.ble?.statusLine() ?: "Bluetooth: not started").takeIf { Here.bluetooth },
    MeshEngine.wifi?.links?.statusLine() ?: "Wi-Fi network: off",
    (MeshEngine.nostr?.statusLine() ?: "Internet: off").takeIf { Here.internet },
    MeshEngine.lora?.takeIf { it.paired || it.connected }?.status,
    MeshEngine.meshcore?.takeIf { it.paired || it.connected }?.status,
)

/** Missing permissions and phone switches that stop DarkProof from working well, each with the button that fixes it. */
@Composable
private fun PhoneProblems(s: AppState, a: MainActivity) {
    if (s.permissionsMissing) Card {
        Txt("Nearby devices permission needed", 18, FontWeight.Bold, Theme.help)
        Txt("Without it DarkProof can't connect to phones nearby. Your messages are kept and go out once you allow it.", 15, color = Theme.dim)
        GhostButton("Allow") { a.askNearby() }
    } else if (s.bluetoothOff) Card {
        Txt("Bluetooth is off", 18, FontWeight.Bold, Theme.help)
        Txt("Phones nearby can't reach you. Turn it on to send and receive without internet.", 15, color = Theme.dim)
        GhostButton("Turn on Bluetooth") { a.turnOnBluetooth() }
    } else if (s.locationMissing) Card {
        Txt("Location isn't allowed", 18, FontWeight.Bold)
        Txt("Android uses it to find nearby phones over Bluetooth and Wi-Fi, so DarkProof may find fewer phones. Check-ins and places go out without your position.", 15, color = Theme.dim)
        GhostButton("Allow location") { a.askLocation() }
    } else if (s.locationOff && (androidSdk() < 31 || s.shareLocation)) Card {
        Txt("Location is off", 18, FontWeight.Bold)
        Txt(if (androidSdk() < 31) "This phone needs it on to find other phones. Turn it on in the phone's settings."
            else "Your check-ins go out without your position until you turn it on.", 15, color = Theme.dim)
        GhostButton("Turn on location") { a.openLocationSettings() }
    }
}

@Composable private fun Tip(@DrawableRes icon: Int, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Ic(icon, Theme.dim, 24.dp); Txt(text, 15, color = Theme.dim)
}

/** [moment]: this is "I'm safe", which plays its sent moment ([SafeMoment]) and buzzes when sent, not at the touch. */
@Composable
private fun ActionTile(label: String, @DrawableRes icon: Int, color: Color, signature: Boolean, modifier: Modifier, moment: SafeMoment? = null, tap: () -> Unit) {
    val shape = remember(signature) { if (signature) vaultShape(18.dp, 26.dp, 6.dp) else RoundedCornerShape(26.dp) }
    val press = remember(shape) { Press(down = 0.96f, shape = shape, dim = 0.1f, lift = 0.05f, buzz = if (moment != null) Buzz.None else Buzz.Key) }
    Column(modifier.tap(press, onClick = tap).background(Theme.card, shape).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(52.dp).then(if (moment != null) Modifier.safeRing(moment, CircleShape, 0.7f) else Modifier)
            .background(if (color == Theme.text) Theme.tile else color.copy(alpha = 0.16f), CircleShape).litEdge(CircleShape), contentAlignment = Alignment.Center) {
            if (moment != null) SafeShield(color, 28.dp, moment) else Ic(icon, color, 28.dp)
        }
        Txt(label, 14, FontWeight.SemiBold, maxLines = 1)
    }
}

// MARK: - Sheets

@Composable
private fun InviteSheet(g: SavedGroup, a: MainActivity, close: () -> Unit) {
    // The group's keys load in the background: a group just created can get here before they're ready.
    val pins by produceState<String?>(null, g.code) {
        repeat(40) { MeshEngine.invitePins(g.code)?.let { value = it; return@produceState }; kotlinx.coroutines.delay(250) }
        value = ""
    }
    val link = remember(g, pins) { inviteLink(g, pins.orEmpty()) }
    val qr = remember(link) { qrBitmap(link).asImageBitmap() }
    var showCode by rememberSaveable { mutableStateOf(false) }
    SheetPage("Invite", close) {
        Txt("Invite to ${g.name}", 20, FontWeight.Bold)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (pins != null) Image(qr, "Invite QR code", Modifier.size(260.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp))
        }
        Txt("Your friend points their phone camera at this code, or scans it in DarkProof.", 15, color = Theme.dim)
        PrimaryButton("Share invite link", enabled = pins != null, icon = R.drawable.ic_share) {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Join my group ${g.name} on DarkProof: $link\n\nDon't have DarkProof? https://darkproof.app"), "Invite"))
        }
        FingerprintCard(g.code, "Check together", "Once your friend has joined, compare these with their phone. The same emoji mean the same group.")
        Kicker("Or tell them the code")
        if (!showCode) GhostButton("Show code") { showCode = true }
        else Card {
            Txt(g.code, 22, FontWeight.Bold)
            GhostButton("Copy code") { copyText(a, "Group code", g.code, sensitive = true, done = "Code copied") }
        }
        if (Here.shareApp) {
            Kicker("Friend doesn't have DarkProof?")
            SecondaryButton("Share the app", icon = R.drawable.ic_share) { ApkShare.share(a) }
            Txt("Sends DarkProof to an Android phone nearby with Quick Share or Bluetooth, no internet needed.", 14, color = Theme.dim)
        }
    }
}

@Composable
private fun GroupsSheet(g: SavedGroup, a: MainActivity, all: List<Message>, invite: () -> Unit, add: () -> Unit, close: () -> Unit) {
    SheetPage("Groups", close) { GroupsContent(g, a, all, invite, add, close) }
}

/** The group in use, the others to switch to, and "Join or start another group" (the Groups sheet, Settings > Groups). */
@Composable
private fun GroupsContent(g: SavedGroup, a: MainActivity, all: List<Message>, invite: () -> Unit, add: () -> Unit, close: () -> Unit) {
    val others = remember(g) { Groups.others(a) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(g.name, 18, FontWeight.Bold, modifier = Modifier.weight(1f)); Chip("In use", Theme.safe)
        }
        GhostButton("Invite people") { invite() }
    }
    if (others.isNotEmpty()) {
        Kicker("Your other groups")
        Card(padding = 16.dp) {
            others.forEach { o ->
                val unread = remember(all, o) { ReadMarks.unread(a, all, o.code) }
                val help = remember(all, o) { groupNeedsHelp(all, o.code) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(o.name, 17, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    if (help) Chip("Needs help", Theme.help)
                    if (unread > 0) Chip("$unread new", Theme.text)
                    GhostButton("Switch") { a.switchTo(o); close() }
                }
            }
        }
        Txt("Switching shows the other group's messages. You still get alerts from every group, and their messages stay on the ${Here.device}.", 14, color = Theme.dim)
    }
    SecondaryButton("Join or start another group", icon = R.drawable.ic_add) { add() }
}

/**
 * Settings, and Home's "Connect" tile ([connections]: only the Advanced lines). One line each, in plain words: a line
 * with more to it opens a page inside the sheet (the back arrow or Back returns); a plain on/off switches in the list.
 */
@Composable
private fun SettingsSheet(s: AppState, a: MainActivity, all: List<Message>, invite: () -> Unit, add: () -> Unit, close: () -> Unit,
                          connections: Boolean = false) {
    var page by rememberSaveable { mutableStateOf("") }
    var over by rememberSaveable { mutableStateOf("") } // what is open over Settings: "risk", "leave" or "erase"
    val title = when (page) {
        "name" -> "Your name"; "groups" -> "Groups"; "notifications" -> "Notifications"; "help" -> "Help and privacy"
        "nearby" -> "Nearby phones"; "radio" -> "Long-range radio"; "board" -> "Wi-Fi board"; "share" -> "Send through another app"
        "blocked" -> "Blocked people"; "background" -> "Background"; else -> if (connections) "Connections" else "Settings"
    }
    SheetPage(title, close, onBack = if (page.isEmpty()) null else ({ page = "" }), scrollKey = page) {
        BackHandler(enabled = page.isNotEmpty()) { page = "" }
        Perms.version // re-read permissions when they change
        when (page) {
            "name" -> NamePage(s, a) { page = "" }
            "groups" -> s.group?.let { g ->
                GroupsContent(g, a, all, invite, add, close)
                FingerprintCard(g.code, "Check your group", "Compare these with the other phone. The same emoji mean you're in the same group; different ones mean a wrong code.")
                MeshEngine.myPublicKey(g.code)?.let { pub ->
                    val ix = remember(pub) { P256.emoji(pub) }
                    Card {
                        Txt("Your emoji in ${g.name}", 16, FontWeight.SemiBold)
                        PersonEmoji(ix, 30)
                        Txt(Fingerprint.spoken(ix), 13, color = Theme.dim)
                        Txt("When someone checks you, read them these. If their phone shows the same, it's really you writing.", 14, color = Theme.dim)
                    }
                }
            }
            "notifications" -> Card {
                Txt("Notifications", 16, FontWeight.SemiBold)
                Txt("Alerts when any of your groups writes or checks in, also when DarkProof is closed. While it is open, someone needing help or asking if you're safe shows at the top of the screen. Made on your phone: no internet needed.",
                    14, color = Theme.dim)
                if (s.notificationsOff) GhostButton("Turn on notifications") { a.askNotifications() }
                else Txt("On", 15, FontWeight.SemiBold, Theme.safe)
            }
            "help" -> HelpPage(s, a, close)
            "nearby" -> NearbyPage(s, a)
            "radio" -> RadioPage(s)
            "board" -> BoardPage(s, a)
            "share" -> Card {
                Txt("For satellite messengers such as Garmin inReach or Zoleo, text messages once the network is back, or any app. It includes your battery and, if location is on, where you are. It leaves DarkProof: that app decides who can read it.", 15, color = Theme.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Share “I'm safe”") { shareStatus(a, s, CheckIn.SAFE) }
                    GhostButton("Share “I need help”") { shareStatus(a, s, CheckIn.HELP) }
                }
            }
            "blocked" -> BlockedPage(s, a)
            "background" -> BackgroundPage(a)
            else -> {
                if (connections) {
                    Txt(if (Here.internet) "The ways your messages travel. All but Internet keep working in a blackout." else "The ways your messages travel. They keep working in a blackout.", 15, color = Theme.dim)
                    if (s.permissionsMissing) NearbyPermissionCard(a)
                } else {
                    EverydayRows(s, a, { over = "risk" }) { page = it }
                    Box(Modifier.padding(start = 6.dp, top = 6.dp)) { Kicker("Advanced") }
                }
                AdvancedRows(s, a, showBlocked = !connections) { page = it }
                if (!connections) s.group?.let { g ->
                    Box(Modifier.padding(start = 6.dp, top = 6.dp)) { Kicker("This ${Here.device}") }
                    RowCard {
                        LinkRow(R.drawable.ic_logout, "Leave ${g.name}") { over = "leave" }
                        LinkRow(R.drawable.ic_delete, "Erase everything", danger = true) { over = "erase" }
                    }
                }
            }
        }
    }
    when (over) {
        "risk" -> HighRiskSheet(s, a) { over = "" }
        "leave" -> s.group?.let { g -> LeaveSheet(s, a, g) { over = "" } }
        "erase" -> EraseScreen(a) { over = "" }
    }
}

/** Settings' first card: name, groups, location, notifications, the lock, high-risk mode, sharing the app, help. */
@Composable
private fun EverydayRows(s: AppState, a: MainActivity, highRisk: () -> Unit, open: (String) -> Unit) {
    val g = s.group
    val groups = remember(g) { (listOfNotNull(g?.name) + Groups.others(a).map { it.name }).joinToString(", ") }
    val noLocation = s.shareLocation && !Locator.allowed(a)
    RowCard {
        LinkRow(R.drawable.ic_person, "Your name", s.name) { open("name") }
        LinkRow(R.drawable.ic_group, "Groups", groups) { open("groups") }
        if (Here.position) {
            ToggleRow(R.drawable.ic_location_on, "Share my location", s.shareLocation,
                if (noLocation) "Location isn't allowed for DarkProof, so check-ins go out without it."
                else "When you check in, your group sees where you are and can find you with a compass.",
                if (noLocation) Theme.help else Theme.dim) { a.shareLocation(it) }
            if (noLocation) Box(Modifier.padding(start = 50.dp, bottom = 8.dp)) { GhostButton("Allow location") { a.askLocation() } }
        }
        // Finder (decision 2): what "I need help" carries for the group's Bluetooth search. A computer has no Bluetooth links.
        if (Here.bluetooth) ToggleRow(R.drawable.ic_bluetooth, "Let my group find this ${Here.device}", s.findable && !s.highRisk,
            if (s.highRisk) "Off in high-risk mode." else "When you send “I need help”, your group can tell which nearby phone is yours until DarkProof restarts.") { on ->
            if (s.highRisk) Toast.makeText(a, "Off in high-risk mode", Toast.LENGTH_LONG).show() // the switch stays as it was for later
            else { s.findable = on; FinderPrefs.setFindable(a, on) }
        }
        if (Here.notifications) LinkRow(R.drawable.ic_notifications, "Notifications", if (s.notificationsOff) "Off" else "On") { open("notifications") }
        if (Here.lock) {
            val noScreenLock = !AppLock.available(a); val canLock = s.lockOn || !noScreenLock
            ToggleRow(R.drawable.ic_lock, "Lock DarkProof", s.lockOn,
                if (s.lockOn && noScreenLock) "This phone has no screen lock any more, so this lock can't protect DarkProof. Set one in the phone's settings."
                else if (s.highRisk) "On because of high-risk mode"
                else if (canLock) "Asks for your fingerprint, face or phone PIN when you open DarkProof. Hides message text in notifications and blocks screenshots. “I need help” still works while it's locked."
                else "Set a screen lock on this phone first.", if (s.lockOn && noScreenLock) Theme.help else Theme.dim) { on ->
                if (s.highRisk) Toast.makeText(a, "On because of high-risk mode", Toast.LENGTH_LONG).show() // it can't be turned off while that is on
                else if (canLock) AppLock.ask(a, if (on) "Turn on the lock" else "Turn off the lock") { a.setAppLock(on) }
                else Toast.makeText(a, "Set a screen lock on this phone first", Toast.LENGTH_LONG).show()
            }
        }
        LinkRow(R.drawable.ic_shield, "High-risk mode", if (s.highRisk) "On" else "Off", dot = s.highRisk, onClick = highRisk)
        if (Here.shareApp) LinkRow(R.drawable.ic_share, "Share the app", detail = "To an Android phone nearby, no internet needed") { ApkShare.share(a) }
        LinkRow(R.drawable.ic_image, "Background", AsciiBackground.summary(AsciiBackground.style(a))) { open("background") }
        LinkRow(R.drawable.ic_help, "Help and privacy") { open("help") }
    }
}

/** Settings > Advanced (and the Connect tile): every way messages travel, the battery saver and blocked people. */
@Composable
private fun AdvancedRows(s: AppState, a: MainActivity, showBlocked: Boolean, open: (String) -> Unit) {
    var clock by remember { mutableIntStateOf(0) } // Tor's "can't connect" appears after 2 minutes without other news
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); clock++ } }
    s.tick; clock // read here so the lines are drawn again with fresh link states
    val phones = phonesInRange()
    val wifi = MeshEngine.wifi
    val n = MeshEngine.nostr
    RowCard {
        LinkRow(R.drawable.ic_bluetooth, "Nearby phones",
            when { phones > 0 -> "$phones linked"; !Here.bluetooth -> "None linked"; s.permissionsMissing || s.bluetoothOff -> "Off"; else -> "Searching" }) { open("nearby") }
        val wl = wifi?.links?.linkCount() ?: 0
        ToggleRow(R.drawable.ic_wifi, "Wi-Fi network", wifi != null,
            when { wifi == null -> "Off"; wl > 0 -> "$wl phone${if (wl == 1) "" else "s"} linked"; else -> "Finds phones on the same Wi-Fi, even with no internet" },
            if (wl > 0) Theme.safe else Theme.dim) { MeshEngine.setWifi(it); s.tick++ }
        if (Here.internet) {
            val (net, netColor) = when {
                n == null -> "Off: messages go only from phone to phone" to Theme.dim
                n.torStuck -> "Tor can't connect right now. Nearby phones still work." to Theme.help
                n.throughTor == false -> "Starting Tor… the first time can take a minute" to Theme.dim
                n.connected > 0 -> ("Online" + (if (n.throughTor == true) " through Tor" else "") + ": reaches friends far away") to Theme.safe
                else -> "No internet right now" to Theme.dim
            }
            ToggleRow(R.drawable.ic_public, "Internet", n != null, net, netColor) { MeshEngine.setInternet(it); s.tick++ }
            if (n != null) ToggleRow(R.drawable.ic_shield, "Through Tor", Tor.enabled(a),
                if (Here.batterySaver) "Hides your internet address from the relays. Slower, uses more battery." else "Hides your internet address from the relays. Slower.") { MeshEngine.setTor(it); s.tick++ }
        }
        if (Here.radios) {
            val radio = when {
                MeshEngine.lora?.connected == true || MeshEngine.meshcore?.connected == true -> "Linked"
                MeshEngine.lora?.paired == true || MeshEngine.meshcore?.paired == true -> "Paired"
                else -> "None"
            }
            LinkRow(R.drawable.ic_settings_input_antenna, "Long-range radio", radio) { open("radio") }
        }
        if (Here.batterySaver) {
            val hours = remember(s.tick / 20) { BatteryLife.hoursLeft(a) }
            ToggleRow(R.drawable.ic_battery_full, "Battery saver", MeshEngine.batterySaver(a),
                hours?.let { "Battery: about $it h left at this rate" } ?: "Looks for phones less often, so the battery lasts longer") {
                MeshEngine.setBatterySaver(it); Motion.calm = calmNow(a); s.tick++
            }
        }
        if (Here.wifiBoard) LinkRow(R.drawable.ic_chat, "Wi-Fi board", if (WifiBoard.running) "On" else "Off") { open("board") }
        LinkRow(R.drawable.ic_share, "Send through another app") { open("share") }
        if (showBlocked) {
            val blocked = remember(s.moderated) { Moderation.blocked(a).size }
            LinkRow(R.drawable.ic_block, "Blocked people", if (blocked == 0) "None" else "$blocked") { open("blocked") }
        }
    }
}

@Composable
private fun NearbyPermissionCard(a: MainActivity) = Card {
    Txt("Nearby devices permission needed", 18, FontWeight.Bold, Theme.help)
    Txt("Without it DarkProof can't use Bluetooth or Wi-Fi Direct, so it can't find phones or radios. Wi-Fi networks and the internet still work.", 15, color = Theme.dim)
    GhostButton("Allow") { a.askNearby() }
}

@Composable
private fun NamePage(s: AppState, a: MainActivity, done: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(s.name) }
    InputField("Your name", name, { name = it.take(40) }, keyboard = KeyboardOptions(KeyboardCapitalization.Words))
    Txt("Shown next to your messages and check-ins.", 14, color = Theme.dim)
    PrimaryButton("Save", enabled = name.isNotBlank() && name.trim() != s.name) {
        s.name = name.trim(); a.getSharedPreferences("mesh", 0).edit().putString("name", s.name).apply(); MeshEngine.rename(s.name)
        Toast.makeText(a, "Saved", Toast.LENGTH_SHORT).show()
        done()
    }
}

@Composable
private fun HelpPage(s: AppState, a: MainActivity, close: () -> Unit) {
    Card {
        Txt("Practice", 16, FontWeight.SemiBold)
        Txt("Walk through asking for help, step by step. Nothing is sent.", 14, color = Theme.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Practice now") { s.sheet = Sheet.Practice }
            if (Ready.done(a, Ready.HIDDEN)) GhostButton("Show “Get ready”") { a.getSharedPreferences("ready", 0).edit().remove(Ready.HIDDEN).apply(); s.tick++; close() }
        }
    }
    Card {
        Txt("Help and safety", 16, FontWeight.SemiBold)
        Txt("In Everyone, hold a message to report it or block its sender. To report abuse or a problem, write to ${Contact.EMAIL}.", 14, color = Theme.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Email us") { Contact.open(a, Contact.MAIL) }
            GhostButton("Privacy policy") { Contact.open(a, Contact.PRIVACY) }
        }
        GhostButton("Terms of use") { Contact.open(a, Contact.TERMS) } // on its own line: three in a row do not fit a narrow phone
    }
}

/** Settings > Advanced > Nearby phones: Bluetooth and Wi-Fi Direct, and a Wi-Fi address to dial by hand. */
@Composable
private fun NearbyPage(s: AppState, a: MainActivity) {
    val tick = s.tick
    val phones = phonesInRange()
    if (s.permissionsMissing) NearbyPermissionCard(a)
    if (Here.bluetooth) Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Bluetooth", 18, FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(if (phones > 0) "$phones linked" else if (s.permissionsMissing || s.bluetoothOff) "Off" else "Searching", if (phones > 0) Theme.safe else Theme.dim)
        }
        Txt("Phones with DarkProof within about 10–50 m connect by themselves, over Bluetooth and Wi-Fi Direct.", 15, color = Theme.dim)
        if (!s.permissionsMissing) {
            Txt("Nearby: ${MeshEngine.transportStatus.replaceFirstChar { it.lowercase() }} · ${MeshEngine.ble?.statusLine() ?: "Bluetooth: not started"}", 14, color = Theme.dim)
            if (s.bluetoothOff) GhostButton("Turn on Bluetooth") { a.turnOnBluetooth() }
        }
    }
    WifiCard(tick)
}

/** Settings > Advanced > Long-range radio: pair a Meshtastic or MeshCore LoRa radio. */
@Composable
private fun RadioPage(s: AppState) {
    s.tick
    Txt("A LoRa radio paired with this phone reaches other radios kilometers away, with no network at all. Short messages only.", 15, color = Theme.dim)
    val blocked = if (s.permissionsMissing) "Allow Nearby devices first: DarkProof needs it to use Bluetooth." else if (s.bluetoothOff) "Turn on Bluetooth first." else null
    RadioCard("LoRa radio · Meshtastic",
        "Reaches other radios kilometers away: about 2–10 km in a city, more from high places. Short messages only. Use a Meshtastic radio with the region set (EU_868 in Europe).",
        MeshEngine.lora?.status?.removePrefix("LoRa: "), MeshEngine.lora?.connected == true, MeshEngine.lora?.paired == true, blocked,
        MeshEngine.lora?.let { l -> { found: (String, String) -> Unit -> l.scan(found) } }, { MeshEngine.lora?.select(it) }, { MeshEngine.lora?.forget() })
    RadioCard("LoRa radio · MeshCore",
        "The same kind of radio with MeshCore firmware, version 1.15 or newer. DarkProof adds a #darkproof channel to the radio and every MeshCore repeater passes it on. Short messages only. The radio's PIN is often 123456 or shown on its screen.",
        MeshEngine.meshcore?.status?.removePrefix("MeshCore: "), MeshEngine.meshcore?.connected == true, MeshEngine.meshcore?.paired == true, blocked,
        MeshEngine.meshcore?.let { l -> { found: (String, String) -> Unit -> l.scan(found) } }, { MeshEngine.meshcore?.select(it) }, { MeshEngine.meshcore?.forget() })
}

/** Settings > Advanced > Wi-Fi board: Everyone in a browser, for people without the app. */
@Composable
private fun BoardPage(s: AppState, a: MainActivity) {
    val tick = s.tick
    Card {
        Txt("People without the app join this phone's Wi-Fi network, then read and post Everyone messages in their browser. Android users can also download DarkProof there.", 15, color = Theme.dim)
        key(tick) {
            GhostButton(if (WifiBoard.running) "Stop the Wi-Fi board" else "Start the Wi-Fi board") {
                if (WifiBoard.running) WifiBoard.stop() else WifiBoard.start(a) { MeshEngine.notifyUi() }
                s.tick++
            }
            WifiBoard.status.takeIf { it != "Off" }?.let { Txt(it, 14, color = if (WifiBoard.running) Theme.safe else Theme.help) }
        }
    }
}

@Composable
private fun BlockedPage(s: AppState, a: MainActivity) {
    Txt("Their messages, places and check-ins are hidden on this ${Here.device}. In Everyone, hold a message to block its sender.", 15, color = Theme.dim)
    key(s.moderated) {
        val blocked = Moderation.blocked(a)
        Card {
            if (blocked.isEmpty()) Txt("No one is blocked.", 16, FontWeight.SemiBold)
            blocked.forEach { (id, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(n, 16, modifier = Modifier.weight(1f), maxLines = 1)
                    GhostButton("Unblock") { Moderation.unblock(a, id); s.moderated++ }
                }
            }
        }
    }
}

/** The example in the Wi-Fi card's address field and its hint. Words on the screen: the app never connects to it. */
private const val ADDRESS_EXAMPLE = "192.168.1.20:7777" // NOSONAR kotlin:S1313: an example for the person to read, not an address the app uses

/** Links over Wi-Fi networks: what is linked, this phone's address, and an address to dial by hand. [tick] redraws it. */
@Composable
private fun WifiCard(tick: Int) {
    tick // read here so the card is drawn again when a link comes or goes: a parameter nothing reads doesn't redraw it
    val ctx = LocalContext.current
    val wifi = MeshEngine.wifi
    var address by rememberSaveable { mutableStateOf(wifi?.links?.manual ?: "") }
    Card {
        Txt("Same Wi-Fi network", 18, FontWeight.Bold)
        Txt("Phones on the same Wi-Fi find each other: a router with no internet, a phone hotspot, a neighbourhood network or a long-range rooftop link.", 15, color = Theme.dim)
        if (wifi == null) Txt("Wi-Fi network is off in Settings.", 15, FontWeight.SemiBold, Theme.dim)
        else {
            val n = wifi.links.linkCount()
            Txt(if (n > 0) "$n phone${if (n == 1) "" else "s"} linked over Wi-Fi" else "Looking on this network", 15, FontWeight.SemiBold, if (n > 0) Theme.safe else Theme.dim)
            localAddress()?.let { ip -> if (wifi.links.port > 0) Txt("This ${Here.device}: $ip:${wifi.links.port}", 14, color = Theme.dim) }
            InputField("Connect to an address", address, { address = it.trim() }, ADDRESS_EXAMPLE)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Connect") {
                    val host = address.substringBeforeLast(':', ""); val port = address.substringAfterLast(':').toIntOrNull()
                    if (host.isEmpty() || port == null || port !in 1..65535) Toast.makeText(ctx, "Type it as address:port, for example $ADDRESS_EXAMPLE", Toast.LENGTH_LONG).show()
                    else wifi.links.connectManually(host, port)
                }
                if (wifi.links.manual != null) GhostButton("Stop") { wifi.links.stopManual() }
            }
            Txt("Only needed when phones don't find each other on their own, for example on networks that block it.", 14, color = Theme.dim)
            wifi.links.note.takeIf { it.isNotEmpty() }?.let { Txt(it.replaceFirstChar { c -> c.uppercase() }, 14, color = Theme.dim) }
        }
    }
}

/**
 * Search, pair and forget for one kind of LoRa radio. [scan] is null until the radio link is ready; [blocked] says why
 * it can't search (permission, Bluetooth off).
 */
@Composable
private fun RadioCard(title: String, about: String, status: String?, linked: Boolean, paired: Boolean, blocked: String?,
                      scan: ((found: (String, String) -> Unit) -> Unit)?, select: (String) -> Unit, forget: () -> Unit) {
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    var scanning by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(scanning) { if (scanning) { kotlinx.coroutines.delay(15_500); scanning = false; searched = true } } // radios scan for 15 s
    val ctx = LocalContext.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(title, 18, FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(if (linked) "Linked" else if (paired) "Paired" else "Not linked", if (linked) Theme.safe else Theme.dim)
        }
        Txt(about, 15, color = Theme.dim)
        status?.takeIf { it != "no radio" }?.let { Txt(it.replaceFirstChar { c -> c.uppercase() }, 14, FontWeight.SemiBold, if (linked) Theme.safe else Theme.text) }
        if (blocked != null) { Txt(blocked, 14, color = Theme.dim); return@Card }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(if (scanning) "Searching…" else "Search for radios", enabled = !scanning) {
                if (scan == null) { Toast.makeText(ctx, "DarkProof is still starting. Try again in a moment.", Toast.LENGTH_LONG).show(); return@GhostButton }
                scanning = true; searched = false; found.clear()
                scan { addr, nm -> if (scanning && found.none { it.first == addr }) found += addr to nm }
            }
            if (paired || linked) GhostButton("Forget radio") { forget(); found.clear(); searched = false }
        }
        if (scanning && found.isEmpty()) Txt("Looking for radios nearby… Turn the radio on and keep it next to the phone.", 14, color = Theme.dim)
        if (searched && found.isEmpty()) Txt("No radios found. Turn the radio on, keep it next to the phone and search again.", 14, color = Theme.dim)
        found.forEach { (addr, nm) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(nm, 16, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                GhostButton("Connect") {
                    select(addr); scanning = false; searched = false; found.clear()
                    Toast.makeText(ctx, "Connecting: if asked, enter the PIN shown on the radio", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** This phone's address on its Wi-Fi network, for people who type it on another phone. */
private fun localAddress(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

/** Shares a check-in as plain text through another app (StatusShare), with battery and position when available. */
fun shareStatus(a: MainActivity, s: AppState, c: CheckIn) {
    val battery = batteryPercent(a)
    fun go(spot: Spot?) = a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, StatusShare.text(c, s.name, battery, spot)), "Send via"))
    val last = Locator.lastKnown(a)
    when {
        last != null || !Locator.allowed(a) -> go(last)
        else -> { Toast.makeText(a, "Finding your position…", Toast.LENGTH_SHORT).show(); Locator.fix(a) { go(it) } }
    }
}

/** Avatar with a colored ring: green safe, red help. A report about someone has a faded ring: it isn't their own check-in. */
@Composable fun Avatar(name: String, check: CheckIn?, size: Int = 56, report: Boolean = false) {
    val ring = when (check) { CheckIn.SAFE -> Theme.safe; CheckIn.HELP -> Theme.help; null -> Theme.line }.let { if (report) it.copy(alpha = 0.45f) else it }
    Box(Modifier.size(size.dp).border(if (report) 2.dp else 3.dp, ring, CircleShape).padding(5.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) {
        Txt(name.trim().take(1).uppercase().ifEmpty { "?" }, (size * 0.4).toInt(), FontWeight.Bold)
    }
}

/** "Safe · 5 min ago", or for a report "Safe · reported by Anna · 5 min ago" (never as the person's own check-in). */
fun statusLine(p: Person): String? {
    val by = if (p.isReport) " · reported by " + (if (p.reporterId == MeshEngine.deviceId) "you" else p.reportedBy) else ""
    return when (p.checkIn) {
        CheckIn.SAFE -> "Safe$by · ${ago(p.checkInTs)}"
        CheckIn.HELP -> "Needs help$by · ${ago(p.checkInTs)}"
        null -> null
    }
}

/** What someone needs and how many people they speak for: "Injured · Need water · 3 people". */
fun needsLine(p: Person): String? =
    (p.needs.sortedBy { it.ordinal }.map { it.label } + listOfNotNull(p.people?.takeIf { it > 1 }?.let { "$it people" })).joinToString(" · ").ifEmpty { null }

@Composable
fun PersonRow(p: Person, tap: () -> Unit, find: (Spot) -> Unit) {
    Row(Modifier.fillMaxWidth().tap(RowPress, onClick = tap).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.name, p.checkIn, report = p.isReport)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(p.name, 18, FontWeight.SemiBold, maxLines = 1)
            val line = statusLine(p)
            when (p.checkIn) {
                CheckIn.SAFE -> Txt(line!!, 15, FontWeight.Medium, Theme.safe, maxLines = 2)
                CheckIn.HELP -> Txt(line!!, 15, FontWeight.Medium, Theme.help, maxLines = 2)
                null -> Txt(if (p.lastTs == 0L) "No check-in yet" else "Last seen ${ago(p.lastTs)}", 15, color = Theme.dim)
            }
            needsLine(p)?.let { Txt(it, 14, color = if (p.checkIn == CheckIn.HELP) Theme.text else Theme.dim, maxLines = 2) }
            if (!p.isReport) QuietLine(MeshEngine.quietState(MeshEngine.currentCode, p.id)) // their check-in clock as this phone holds it (spec "If I go quiet")
            if (!p.isReport) CheckBadge(MeshEngine.currentCode, p)
        }
        p.battery?.let { BatteryLabel(it); Spacer(Modifier.width(12.dp)) }
        p.spot?.let { sp -> RoundIcon(R.drawable.ic_navigation, 46.dp, desc = "Find ${p.name}") { find(sp) } }
    }
}

/** Monospaced text helper for codes. */
val Mono = FontFamily.Monospace
