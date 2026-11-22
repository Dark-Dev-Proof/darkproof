package it.blackout.mesh

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// Asking for help, practising it, checking in for someone else, one person's page, checking a person, "Get ready", and the group check.

/** Needs as a saveable text ("injured,water"), so a half-filled form survives. */
private fun needsOf(s: String) = Need.parse(s)
private fun needsText(n: Set<Need>) = Need.text(n).replace(" ", "")

/** What is needed (toggles) and how many people, for "I need help" and reports about others. */
@Composable
fun NeedsForm(needs: Set<Need>, setNeeds: (Set<Need>) -> Unit, people: Int, setPeople: (Int) -> Unit, showNeeds: Boolean = true,
              peopleLabel: String = "How many people, you included?", needsLabel: String? = "What do you need?") {
    if (showNeeds) {
        needsLabel?.let { Kicker(it) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Need.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { n -> ToggleTile(n.label, n in needs, Modifier.weight(1f)) { setNeeds(if (n in needs) needs - n else needs + n) } }
                }
            }
        }
    }
    Kicker(peopleLabel)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        RoundIcon(R.drawable.ic_remove, 48.dp, desc = "One less") { setPeople((people - 1).coerceAtLeast(1)) }
        Txt("$people", 30, FontWeight.Black, modifier = Modifier.widthIn(min = 44.dp).semantics { contentDescription = "$people people" })
        RoundIcon(R.drawable.ic_add, 48.dp, desc = "One more") { setPeople((people + 1).coerceAtMost(99)) }
    }
}

@Composable
fun ToggleTile(label: String, on: Boolean, modifier: Modifier = Modifier, tap: () -> Unit) =
    Row(modifier.heightIn(min = 52.dp).pointerHoverIcon(PointerIcon.Hand)
        .toggleable(on, interactionSource = null, indication = Press(down = 0.96f, shape = RoundedCornerShape(20.dp), dim = 0.1f, buzz = Buzz.None), role = Role.Checkbox) {
            Buzz.play(if (it) Buzz.On else Buzz.Off); tap()
        }
        .background(if (on) Color(0xFFFAFAFA) else Theme.card, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (on) { Ic(R.drawable.ic_check_circle, Color.Black, 20.dp); Spacer(Modifier.width(8.dp)) }
        Txt(label, 16, FontWeight.SemiBold, if (on) Color.Black else Theme.text, maxLines = 1)
    }

/**
 * "I need help": one tap on the red button sends it to [group]; what is needed and how many people are optional and go
 * in the same message (one check-in, like the iPhone, so the group doesn't get two help cards).
 */
@Composable
fun HelpDetailsSheet(s: AppState, a: MainActivity, group: String?, close: () -> Unit) {
    var needs by rememberSaveable { mutableStateOf("") }
    var people by rememberSaveable { mutableIntStateOf(1) }
    val to = Groups.find(a, group)?.name ?: s.group?.name ?: "your group"
    val links = remember(s.tick) { Links.now() }
    val where = if (s.shareLocation) ", with where you are" else ""
    fun sent() { close(); s.everyone = false; s.tab = 1 }
    SheetPage("I need help", close) {
        Txt(if (links.any) "Goes to $to now and hops from phone to phone$where."
            else "No phone or internet is in reach right now. It waits on this ${Here.device}$where and goes out as soon as one is.", 16, color = Theme.dim)
        DangerButton("Send “I need help”", icon = R.drawable.ic_sos) {
            if (checkIn(a, s, CheckIn.HELP, needsOf(needs), people.takeIf { it > 1 }, group)) sent()
        }
        NeedsForm(needsOf(needs), { needs = needsText(it) }, people, { people = it }, needsLabel = "Optional: what do you need?")
        SecondaryButton("Cancel") { close() }
        Txt("Then write in Chat where you are and what happened.", 14, color = Theme.dim)
    }
}

/** A walk through asking for help, clearly marked: nothing is sent. */
@Composable
fun PracticeSheet(a: MainActivity, close: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var needs by rememberSaveable { mutableStateOf("") }
    var people by rememberSaveable { mutableIntStateOf(1) }
    SheetPage("Practice", close) {
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(Theme.help.copy(alpha = 0.16f)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_schedule, Theme.help, 20.dp); Spacer(Modifier.width(10.dp))
            Txt("Practice · nothing is sent · step ${step + 1} of 4", 15, FontWeight.Bold, Theme.help)
        }
        when (step) {
            0 -> {
                Txt("When you need help, tap “Need help” on Home or “I need help” in People. Try it here:", 17)
                val shape = remember { vaultShape(34.dp, 40.dp, 8.dp) }
                val press = remember(shape) { Press(down = 0.96f, shape = shape, dim = 0.18f) }
                Column(Modifier.fillMaxWidth().height(150.dp).tap(press) { step = 1 }.background(Theme.help, shape)
                    .padding(20.dp), verticalArrangement = Arrangement.Bottom) {
                    Ic(R.drawable.ic_sos, Color.White, 48.dp); Spacer(Modifier.height(8.dp))
                    Txt("I need help", 22, FontWeight.Black, Color.White)
                }
            }
            1 -> {
                Txt("DarkProof asks first, so it is never sent by mistake:", 17)
                Card {
                    Txt("Send “I need help”?", 20, FontWeight.Bold)
                    Txt("It goes out now and hops from phone to phone to your group. Next you can say what you need.", 15, color = Theme.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("Cancel") { step = 0 }
                        CapsuleChoice("Send", Theme.help, Color.White, Modifier.weight(1f)) { step = 2 }
                    }
                }
            }
            2 -> {
                Txt("It's out. Now say what you need, with a few taps:", 17)
                NeedsForm(needsOf(needs), { needs = needsText(it) }, people, { people = it })
                PrimaryButton("Send details") { step = 3 }
                SecondaryButton("Skip") { step = 3 }
            }
            else -> {
                Txt("Done. In a real emergency your group would now see:", 17)
                val n = needsOf(needs)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Theme.help.copy(alpha = 0.12f)).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Ic(R.drawable.ic_sos, Theme.help, 26.dp); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt("${a.state.name.ifBlank { "You" }} needs help", 18, FontWeight.SemiBold)
                        val extra = (n.sortedBy { it.ordinal }.map { it.label } + listOfNotNull(people.takeIf { it > 1 }?.let { "$it people" })).joinToString(" · ")
                        if (extra.isNotEmpty()) Txt(extra, 15, color = Theme.text)
                    }
                    batteryPercent(a)?.let { BatteryLabel(it) }
                }
                Tip("Then write in Chat where you are and what happened.")
                Tip("Phones nearby pass it on, with no internet. DarkProof keeps doing it when the app is closed.")
                Tip("Someone in your group can then find you with the compass, if you share your location (Settings).")
                PrimaryButton("Done") { Ready.mark(a, Ready.PRACTICE); a.state.tick++; close() }
            }
        }
    }
}

@Composable private fun Tip(text: String) = Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Ic(R.drawable.ic_check_circle, Theme.dim, 20.dp); Txt(text, 15, color = Theme.dim)
}

/** A check-in for someone without a phone or signal (spec "Reports about others"): shown as reported by me. */
@Composable
fun VouchSheet(s: AppState, a: MainActivity, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var help by rememberSaveable { mutableStateOf(false) }
    var needs by rememberSaveable { mutableStateOf("") }
    var people by rememberSaveable { mutableIntStateOf(1) }
    var withSpot by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { cancel?.invoke() } } // closed while finding the position: nothing is sent
    val g = s.group ?: return
    Perms.version
    SheetPage("Check in for someone", close) {
        Txt("For someone without a phone or signal, for example a neighbour you found at the shelter. ${g.name} sees it as reported by you.", 15, color = Theme.dim)
        InputField("Their name", name, { name = it.take(Vouch.MAX_NAME) }, "Grandma Rosa", keyboard = KeyboardOptions(KeyboardCapitalization.Words))
        PillSwitch("Is safe", "Needs help", help) { help = it }
        NeedsForm(needsOf(needs), { needs = needsText(it) }, people, { people = it }, showNeeds = help,
            peopleLabel = "How many people, them included?", needsLabel = "What do they need?")
        Card { SwitchRow("Add my position", withSpot, { withSpot = it }, "Only if you are with them right now, so people can find them.") }
        if (withSpot && !Locator.allowed(a)) GhostButton("Allow location") { a.askLocation() }
        PrimaryButton(if (sending) "Finding your position…" else "Send to ${g.name}", enabled = name.isNotBlank() && !sending) {
            if (!meshReady(a)) return@PrimaryButton
            fun go(sp: Spot?) {
                val v = Vouch(name, if (help) CheckIn.HELP else CheckIn.SAFE, if (help) needsOf(needs) else emptySet(), people.takeIf { it > 1 }, sp)
                Thread { MeshEngine.send(v.text) }.start()
                val pos = if (withSpot && sp == null) " without a position: no GPS fix yet" else ""
                Toast.makeText(a, if (Links.now().any) "Sent to ${g.name}$pos" else "Saved$pos. It goes out when a phone or the internet is in reach.", Toast.LENGTH_LONG).show()
                close()
            }
            if (withSpot && Locator.allowed(a)) { sending = true; cancel = Locator.fix(a) { go(it) } } else go(null)
        }
    }
}

/** How a person is checked, as one line: under their name in People and on their page. Nothing for a report about someone. */
@Composable
fun CheckBadge(code: String?, p: Person, size: Int = 14) {
    val how = remember(Marks.version, code, p.id) { MeshEngine.checkedHow(code, p.id) }
    val newKey = how == null && remember(Marks.version, code, p.id, p.name) { MeshEngine.newKeyFor(code, p.id, p.name) }
    when {
        how != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val color = if (how == CheckedHow.IN_PERSON) Theme.safe else Theme.dim
            Ic(R.drawable.ic_shield, color, (size + 1).dp)
            Txt(if (how == CheckedHow.IN_PERSON) "Checked in person" else "Checked by emoji", size, FontWeight.SemiBold, color, maxLines = 1)
        }
        newKey -> Txt("New key. Check again.", size, FontWeight.SemiBold, Theme.help)
        else -> Txt("Not checked", size, color = Theme.faint)
    }
}

/** One person: a big initial, the name, how they are checked, then what can be done: find them, check them, ask if they're safe. */
@Composable
fun PersonSheet(s: AppState, a: MainActivity, messages: List<Message>, id: String, close: () -> Unit) {
    val code = s.group?.code
    val p = remember(messages, id, s.tick) { peopleOf(a, messages, code).firstOrNull { it.id == id } }
    var ask by remember { mutableStateOf(false) }
    if (p == null) { SheetPage("Person", close) { Txt("No news from this person on this ${Here.device} any more.", 16, color = Theme.dim) }; return }
    val me = remember { Locator.lastKnown(a) }
    SheetPage("", close) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(p.name, p.checkIn, 88, report = p.isReport)
            Spacer(Modifier.height(4.dp))
            Txt(p.name, 26, FontWeight.Black, maxLines = 2, lineHeight = 31, align = TextAlign.Center)
            if (!p.isReport) CheckBadge(code, p, 15)
            Txt(statusLine(p) ?: "No check-in yet", 16, FontWeight.SemiBold,
                when (p.checkIn) { CheckIn.SAFE -> Theme.safe; CheckIn.HELP -> Theme.help; null -> Theme.dim }, align = TextAlign.Center)
            needsLine(p)?.let { Txt(it, 16, align = TextAlign.Center) }
        }
        if (p.isReport) Txt("This isn't ${p.name}'s own check-in: ${if (p.reporterId == MeshEngine.deviceId) "you" else p.reportedBy} sent it for them.", 15, color = Theme.dim)
        // What can be done, as round buttons with their names under them. A report is about someone without a phone: only "Find".
        val spot = p.spot
        if (spot != null || !p.isReport) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            if (spot != null) CircleAction(R.drawable.ic_navigation, "Find", Modifier.width(96.dp)) { s.sheet = Sheet.Find(p.name, spot, p.spotTs) }
            if (!p.isReport) CircleAction(R.drawable.ic_qr_code_2, "Check", Modifier.width(96.dp)) { s.checkEnded = null; s.sheet = Sheet.Check(p.id) }
            if (!p.isReport) CircleAction(R.drawable.ic_notifications, "Ask if safe", Modifier.width(96.dp)) { ask = true }
        }
        Card {
            Fact("Last heard", if (p.lastTs == 0L) "Nothing yet" else ago(p.lastTs))
            p.battery?.let { Fact("Battery", "$it%") }
            p.spot?.let { sp ->
                Fact("Position", if (me != null) "${Geo.meters(Geo.distance(me, sp))} ${Geo.compassWord(Geo.bearing(me, sp)).lowercase()} of you, ${ago(p.spotTs)}" else "Shared ${ago(p.spotTs)}")
                PlusCodeRow(sp)
            }
        }
        if (!p.isReport) AdminButtons(s, a, p, close)
    }
    if (ask) Confirm("Ask ${p.name}?", "${p.name} gets “${AreYouSafe(p.name).text.removePrefix("❓ ")}” with one-tap answers. The whole group sees the question.", "Ask",
        onYes = { ask = false; if (meshReady(a)) { Thread { MeshEngine.send(AreYouSafe(p.name).text) }.start(); Toast.makeText(a, "Asked ${p.name}", Toast.LENGTH_SHORT).show() } },
        onNo = { ask = false })
}

/** "Make admin" and, last and in red, "Remove from group", for admins (spec/DESIGN-v0.6-groups.md, "How it works for people"). */
@Composable
private fun AdminButtons(s: AppState, a: MainActivity, p: Person, close: () -> Unit) {
    val code = s.group?.code; val tick = s.tick; val me = MeshEngine.deviceId
    val admin = remember(tick, code, p.id) { MeshEngine.isAdmin(code, p.id) }
    val canAct = remember(tick, code, p.id) { p.id != me && MeshEngine.isAdmin(code, me) && MeshEngine.inGroup(code, p.id) && !MeshEngine.removed(code) }
    var ask by remember { mutableStateOf<String?>(null) }
    fun result(ok: Boolean, done: String) { Toast.makeText(a, if (ok) done else "Not everyone's keys have reached this ${Here.device} yet. Try again when they're in reach.", Toast.LENGTH_LONG).show() }
    if (admin) Txt("${p.name} is an admin of this group.", 15, color = Theme.dim)
    if (canAct) {
        if (!admin) SecondaryButton("Make admin") { ask = "admin" }
        SecondaryButton("Remove from group", color = Theme.help) { ask = "remove" }
    }
    if (ask == "admin") Confirm("Make ${p.name} an admin?", "${p.name} will be able to remove people and make others admins. The group sees this.", "Make admin",
        onYes = { ask = null; MeshEngine.makeAdmin(code, p.id) { result(it, "${p.name} is now an admin") } }, onNo = { ask = null })
    if (ask == "remove") Confirm("Remove ${p.name}?", "Everyone else gets a new key and a new invite code, and old invites stop working. ${p.name} can't read anything from now on. The group sees who removed them.", "Remove", danger = true,
        onYes = { ask = null; MeshEngine.remove(code, p.id) { ok -> result(ok, "${p.name} was removed"); if (ok) close() } }, onNo = { ask = null })
}

/**
 * A code was scanned for person [id] of [code]'s group: judged with the keys the engine holds at this moment
 * ([PersonCode.check]). Their key: they are marked checked in person. Anything else marks nothing. How it ended goes to
 * [AppState.checkEnded], for the Check sheet to show.
 */
fun codeScanned(s: AppState, code: String?, id: String, name: String, text: String) {
    val r = PersonCode.check(text, MeshEngine.publicKeyOf(code, id), MeshEngine.myPublicKey(code))
    if (r == PersonCode.Result.MATCH) { MeshEngine.markChecked(code, id, name, CheckedHow.IN_PERSON); Marks.version++; s.checkEnded = CheckEnded(id, CheckedHow.IN_PERSON) }
    else s.checkEnded = CheckEnded(id, problem = r)
}

/**
 * "Check Ben": two ways to make sure a member's key is really that person's (spec "Person emoji", "Person code").
 * Scan: this phone shows its own person code for the other one to scan, and scans theirs; a match marks them checked in
 * person. Compare emoji: the sixteen emoji of their key, read out against what their phone shows. How a check ended is
 * kept in [AppState.checkEnded], not here: the app may lock while the scanner is open, and this sheet is then drawn anew.
 */
@Composable
fun CheckSheet(s: AppState, a: MainActivity, messages: List<Message>, id: String, close: () -> Unit) {
    val code = s.group?.code
    val p = remember(messages, id, s.tick) { peopleOf(a, messages, code).firstOrNull { it.id == id } }
    if (p == null) { SheetPage("Check", close) { Txt("No news from this person on this ${Here.device} any more.", 16, color = Theme.dim) }; return }
    val name = p.name
    val theirs = remember(id, s.tick) { MeshEngine.publicKeyOf(code, id) }
    val mine = remember(code, s.tick) { MeshEngine.myPublicKey(code) }
    var emoji by rememberSaveable { mutableStateOf(!Here.scan) } // the tab: a device that can't scan starts on the emoji
    val ended = s.checkEnded?.takeIf { it.id == id }
    val noKey = "$name's key hasn't reached this ${Here.device} yet, so their messages show “Not verified yet”. It comes by itself when their phone or the internet is in reach."
    fun checked(how: CheckedHow) { MeshEngine.markChecked(code, id, name, how); Marks.version++; s.checkEnded = CheckEnded(id, how) }
    if (ended?.how != null) { // done: the page that says so
        SheetPage("", { s.checkEnded = null; close() }) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(76.dp).background(Theme.safe.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) { Ic(R.drawable.ic_shield, Theme.safe, 38.dp) }
                Txt("$name is checked", 24, FontWeight.Black, align = TextAlign.Center)
                Txt("${if (ended.how == CheckedHow.IN_PERSON) "Checked in person" else "Checked by emoji"}, today. If $name's key ever changes, DarkProof shows it in People and under their messages.",
                    15, color = Theme.dim, align = TextAlign.Center)
            }
            PrimaryButton("Done") { s.checkEnded = null; close() }
        }
        return
    }
    SheetPage("Check $name", { s.checkEnded = null; close() }) {
        PillSwitch("Scan", "Compare emoji", emoji) { emoji = it }
        if (!emoji) {
            // What the last scan turned out to be, if it wasn't their key.
            when (ended?.problem) {
                PersonCode.Result.OTHER -> Card(padding = 16.dp) {
                    Txt("This is not $name's key.", 17, FontWeight.Bold, Theme.help)
                    Txt("Someone else may be using the name. Do not trust this person's messages until you have sorted it out together.", 15, color = Theme.dim)
                }
                PersonCode.Result.OWN -> Card(padding = 16.dp) { Txt("That is your own code. Scan $name's phone.", 15, FontWeight.SemiBold) }
                PersonCode.Result.NOT_A_CODE -> Card(padding = 16.dp) { Txt("That is not a DarkProof person code.", 15, FontWeight.SemiBold) }
                PersonCode.Result.NO_KEY -> Card(padding = 16.dp) { Txt(noKey, 15, color = Theme.dim) }
                else -> {}
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (mine != null) Image(remember(mine) { qrBitmap(PersonCode.encode(mine)).asImageBitmap() }, "Your person code in ${s.group?.name ?: "this group"}, for $name to scan",
                    Modifier.size(240.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp))
                else Txt("Working out your code…", 15, color = Theme.dim)
            }
            if (Here.scan) {
                Txt("In the same place: $name scans this, then you scan $name's code. This is the strongest check.", 15, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                PrimaryButton("Scan $name's code", icon = R.drawable.ic_qr_code_2) { scanCode(a) { text -> codeScanned(s, code, id, name, text) } }
            } else {
                // A computer can show its code, but can't scan one: it says so instead of offering it.
                Txt("In the same place: $name scans this to check you.", 15, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Txt("This device has no camera. Compare the emoji instead.", 15, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        } else if (theirs == null) Card(padding = 16.dp) { Txt(noKey, 15, color = Theme.dim) }
        else {
            val ix = remember(theirs) { P256.emoji(theirs) }
            Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(24.dp)).padding(horizontal = 12.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonEmoji(ix, 30, center = true)
                Txt(Fingerprint.spoken(ix), 13, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Txt("Ask $name to read these out. If $name's phone shows the same sixteen, nobody is in between.", 15, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Txt("$name finds them in Settings › Groups, under “Your emoji”.", 14, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            PrimaryButton("They match") { checked(CheckedHow.EMOJI) }
            TextAction("They don't match", Theme.help) {
                // Not checked, whatever was marked before: then the words below are true.
                MeshEngine.unmarkChecked(code, id); Marks.version++
                s.checkEnded = null; close()
                Toast.makeText(a, "$name stays unchecked", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable private fun Fact(label: String, value: String) = Row {
    Txt(label, 15, color = Theme.dim, modifier = Modifier.width(110.dp)); Txt(value, 15, modifier = Modifier.weight(1f))
}

/** A position's Plus Code (map apps understand it, people can read it out), tap to copy. */
@Composable
fun PlusCodeRow(sp: Spot) {
    val ctx = LocalContext.current
    val code = sp.plusCode ?: return
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tap(Press(down = 0.98f, shape = RoundedCornerShape(12.dp), dim = 0f, lift = 0.05f, light = 0.05f, buzz = Buzz.Tap)) { copyText(ctx, "Plus Code", code) }
        .semantics { contentDescription = "Plus Code $code. Tap to copy" }, verticalAlignment = Alignment.CenterVertically) {
        Txt("Plus Code", 15, color = Theme.dim, modifier = Modifier.width(110.dp))
        Txt(code, 16, FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Ic(R.drawable.ic_content_copy, Theme.dim, 18.dp)
    }
}

/** A small "8FQFF57R+M2" label that copies itself, next to a position. */
@Composable
fun PlusCodeLabel(sp: Spot, size: Int = 14) {
    val ctx = LocalContext.current
    val code = sp.plusCode ?: return
    Txt(code, size, FontWeight.SemiBold, Theme.dim, Modifier.tap(Press(down = 0.95f, shape = RoundedCornerShape(8.dp), dim = 0.35f, buzz = Buzz.Tap)) { copyText(ctx, "Plus Code", code) }
        .semantics { contentDescription = "Plus Code $code. Tap to copy" }.padding(vertical = 4.dp))
}

/** Fingerprints take a key derivation each (a fraction of a second): worked out once per code, off the main thread. */
object FingerprintCache {
    private val known = ConcurrentHashMap<String, List<Int>>()
    fun get(code: String): List<Int> = known.getOrPut(code) { Fingerprint.forCode(code) }
}

/** The group's five emoji, to compare with another phone (spec "Group fingerprint"). */
@Composable
fun FingerprintCard(code: String, title: String, text: String) {
    val fp by produceState<List<Int>?>(null, code) { value = withContext(Dispatchers.Default) { FingerprintCache.get(code) } }
    Card {
        Txt(title, 16, FontWeight.SemiBold)
        Txt(text, 14, color = Theme.dim)
        val f = fp
        if (f == null) Txt("Working them out…", 15, color = Theme.dim)
        else {
            Txt(Fingerprint.emojiText(f), 34, lineHeight = 44, modifier = Modifier.semantics { contentDescription = Fingerprint.spoken(f) })
            Txt(Fingerprint.spoken(f), 15, color = Theme.dim)
        }
    }
}

private val ReadyPress = Press(down = 1f, shape = RoundedCornerShape(16.dp), dim = 0f, lift = 0.05f, light = 0.05f, buzz = Buzz.None)

/** One "Get ready" step. */
private class Step(val title: String, val sub: String, val done: Boolean, @DrawableRes val icon: Int, val go: () -> Unit)

/** What to do while there is still power and internet, with progress; each step opens the screen that does it. */
@Composable
fun ReadyCard(s: AppState, a: MainActivity, hasPeople: Boolean) {
    s.tick; Perms.version
    if (Ready.done(a, Ready.HIDDEN)) return
    val ai = LocalAi.catalog.firstOrNull()
    val steps = listOfNotNull(
        Step("Invite your people", "So you can reach each other", hasPeople || Ready.done(a, Ready.INVITE), R.drawable.ic_qr_code_2) { s.sheet = Sheet.Invite },
        Step("Save the map of your area", "It then works with no internet", OfflineMap.state is OfflineMap.State.Ready, R.drawable.ic_map) { s.placesMap = true; s.tab = 2 }.takeIf { Here.map },
        Step("Add the “I'm safe” tile", "One tap from the top of the screen", Ready.done(a, Ready.TILE), R.drawable.ic_verified_user) { a.addTile() }.takeIf { Here.tile },
        Step("Try a practice run", "Ask for help without sending anything", Ready.done(a, Ready.PRACTICE), R.drawable.ic_sos) { s.sheet = Sheet.Practice },
        Step("Turn on notifications", "Know when someone needs help", !s.notificationsOff, R.drawable.ic_notifications) { a.askNotifications() }.takeIf { Here.notifications },
        ai?.takeIf { Here.ai }?.let { m -> Step("Download the offline AI", "Optional. Big: ${"%.1f".format(m.bytes / 1e9)} GB, use Wi-Fi.",
            LocalAi.states.values.any { it == LocalAi.State.Ready }, R.drawable.ic_auto_awesome) { s.tab = 4 } },
    )
    val done = steps.count { it.done }
    if (done == steps.size) return
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Get ready", 20, FontWeight.Bold)
                Txt("$done of ${steps.size} done, while there is power", 14, color = Theme.dim)
            }
            GhostButton("Hide") { Ready.mark(a, Ready.HIDDEN); s.tick++ }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Theme.tile)) {
            Box(Modifier.fillMaxWidth((done.toFloat() / steps.size).coerceAtLeast(0.03f)).fillMaxHeight().background(Theme.safe, CircleShape))
        }
        Column {
            steps.forEach { st ->
                Row(Modifier.fillMaxWidth().tap(ReadyPress, enabled = !st.done, onClick = st.go)
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(if (st.done) Theme.safe.copy(alpha = 0.16f) else Theme.tile, CircleShape), contentAlignment = Alignment.Center) {
                        Ic(if (st.done) R.drawable.ic_check_circle else st.icon, if (st.done) Theme.safe else Theme.text, 20.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(st.title, 16, FontWeight.SemiBold, if (st.done) Theme.dim else Theme.text)
                        if (!st.done) Txt(st.sub, 14, color = Theme.dim)
                    }
                    if (!st.done) Ic(R.drawable.ic_expand_more, Theme.dim, 22.dp, Modifier.rotate(-90f)) // ›
                }
            }
        }
    }
}
