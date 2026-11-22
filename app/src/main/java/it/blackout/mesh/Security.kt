package it.blackout.mesh

import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// "Group security": one page that says, line by line, what protects this group on this phone and what is left to
// check. Every line reads what the engine already knows; nothing here changes a key or a rule, except through the
// engine's own calls. The words are plain Kotlin so unit tests run them (the iPhone shows the same ones).

object SecurityWords {
    const val DAY_MS = 24 * 3600_000L

    /** "All checked", "1 thing to check", "2 things to check": the page's verdict and Home's line. */
    fun verdict(flagged: Int) = when (flagged) { 0 -> "All checked"; 1 -> "1 thing to check"; else -> "$flagged things to check" }

    /**
     * The verdict for a group this phone isn't in, yet or any more ([removed]: an admin took it out). There is nothing it
     * could have checked, so it never says "All checked".
     */
    fun outside(removed: Boolean) = if (removed) "You were removed" else "Waiting to be let in"

    /**
     * "3 of 5. Ben and Luca are not.": how many of the other members are checked, and who isn't: two names at most, then
     * "and 2 more". [unchecked]: their names, "" for someone whose name hasn't reached this phone (counted, not named).
     */
    fun people(total: Int, unchecked: List<String>): String {
        if (total == 0) return "Nobody else yet"
        val head = "${total - unchecked.size} of $total"
        if (unchecked.isEmpty()) return head
        val named = unchecked.filter { it.isNotBlank() }; val shown = named.take(2); val more = unchecked.size - shown.size
        val who = when {
            shown.isEmpty() -> "$more"
            more > 0 -> shown.joinToString(", ") + " and $more more"
            else -> shown.joinToString(" and ")
        }
        return "$head. $who ${if (unchecked.size == 1) "is" else "are"} not."
    }

    /** "Last changed today", "yesterday", "9 days ago": from when this phone counts the key's age, in whole days. */
    fun keyAge(since: Long, now: Long): String = when (val days = ((now - since).coerceAtLeast(0) / DAY_MS).toInt()) {
        0 -> "Last changed today"; 1 -> "Last changed yesterday"; else -> "Last changed $days days ago"
    }

    /** True if the key is older than the app's own change period (7 days) plus one day: the weekly change didn't happen. */
    fun keyOld(since: Long, now: Long) = now - since > Keyring.KEEP_MS + DAY_MS

    /**
     * True if a key in use since [since] is at least [min] old at [now]: only then is "New key" offered
     * (MeshEngine.canMakeKey). A date ahead of the clock is not old.
     */
    fun keyOldEnough(since: Long, now: Long, min: Long) = now - since >= min

    /** "Messages disappear": the usual rule, and in high-risk mode this device's own, counted from when a message arrives. */
    fun disappear(highRisk: Boolean, device: String) = if (highRisk) "After one to two weeks. On this $device, 1 day after they arrive." else "After one to two weeks"

    /**
     * The line above a group's chat: who reads it, and how long it stays. [timer]: the group's timer for disappearing
     * messages (seconds, 0 off, null not known; spec "Disappearing messages").
     */
    fun chatNote(group: String, highRisk: Boolean, device: String, timer: Int? = null) =
        "Only people in $group can read these. " + GroupTimer.chatLine(timer, highRisk, device)
}

/**
 * One line of Group security. [flagged]: it counts as a thing to check. [button]: what this phone can do about it, if
 * anything. [ok]: a green check, only where something is positively fine; a line that is neither shows nothing at its right.
 */
class SecurityLine(val key: String, @DrawableRes val icon: Int, val title: String, val text: String, val flagged: Boolean = false,
                   val button: String? = null, val ok: Boolean = !flagged,
                   /** What the line does and doesn't do, under it: shown with a [button], else behind an (i) that opens it. */
                   val explain: String? = null)

object GroupSecurity {
    /**
     * The lines for group [g], from what the engine knows right now. Six of them; while this phone is still asking to
     * join, or was removed, it knows neither the members nor a key in use, so those two lines are left out (the page shows
     * the chat's note instead). [justChanged]: this phone made a new key a moment ago.
     */
    fun lines(c: Context, g: SavedGroup, justChanged: Boolean = false, now: Long = System.currentTimeMillis()): List<SecurityLine> {
        val code = g.code
        val others = MeshEngine.otherMembers(code)
        val unchecked = others.filter { !MeshEngine.isChecked(code, it.first) }
        val since = MeshEngine.keySince(code)?.takeIf { !MeshEngine.removed(code) }
        val internet = MeshEngine.nostr != null
        return listOfNotNull(
            // Everyone the key in use was made for, except this phone; someone blocked counts too.
            // Nobody else yet: nothing checked and nothing to check, so no mark.
            if (since == null) null else SecurityLine("people", R.drawable.ic_group, "People checked", SecurityWords.people(others.size, unchecked.map { it.second }),
                flagged = unchecked.isNotEmpty(), button = "Check".takeIf { unchecked.isNotEmpty() }, ok = others.isNotEmpty() && unchecked.isEmpty()),
            // The key: the members' phones change it every week by themselves; an admin can also change it now.
            if (since == null) null else SecurityLine("key", R.drawable.ic_key, "Group key", if (justChanged) "Changed just now" else SecurityWords.keyAge(since, now),
                flagged = SecurityWords.keyOld(since, now), button = "New key".takeIf { !justChanged && MeshEngine.canMakeKey(code) }),
            // Every new key of a group is sealed to each member with ML-KEM-768 next to P-256: the engine makes none without (MeshEngine.makeRekey, Hybrid).
            SecurityLine("pq", R.drawable.ic_lock, "Quantum-safe keys", "On, always"),
            // By the app's two switches: with Tor on, the relay links go through Tor or not at all.
            when {
                !internet -> SecurityLine("tor", R.drawable.ic_public, "Internet through Tor", "The internet is off", ok = false) // nothing goes out, so nothing to praise
                Tor.enabled(c) -> SecurityLine("tor", R.drawable.ic_public, "Internet through Tor", "On")
                else -> SecurityLine("tor", R.drawable.ic_public, "Internet through Tor", "Off", flagged = true, button = "Switch on")
            },
            // The group's timer as this phone knows it (spec "Disappearing messages"). While one is set the line explains
            // what it does and doesn't do; an admin changes it from here too.
            timerLine(code, since != null, HighRisk.on(c)),
            // Open or closed is how the group works, neither fine nor wrong: no mark.
            SecurityLine("invite", R.drawable.ic_link, "Invite",
                if (MeshEngine.inviteClosed(code)) "Closed. The old code no longer works." else "Open. Anyone with the code can ask to join.", ok = false),
        )
    }

    /** "Messages disappear": the group's timer, or for a group this phone isn't in, the normal rule. */
    private fun timerLine(code: String, inGroup: Boolean, highRisk: Boolean): SecurityLine {
        val t = if (inGroup) MeshEngine.timer(code) else Keyring.Timer(0, null)
        val admin = inGroup && MeshEngine.canSetTimer(code)
        val seconds = t.seconds
        return SecurityLine("gone", R.drawable.ic_schedule, "Messages disappear", GroupTimer.securityLine(seconds, t.by, highRisk, Here.device),
            button = "Change".takeIf { admin }, ok = !admin && (seconds ?: 0) <= 0, explain = seconds?.takeIf { it > 0 }?.let(GroupTimer::explainer))
    }

    /** How many things there are to check: what the page's verdict and Home's line both show. */
    fun flagged(lines: List<SecurityLine>) = lines.count { it.flagged }

    /** This phone is in the group: it has a key in use and wasn't removed (only then are there people and a key to show). */
    fun inGroup(lines: List<SecurityLine>) = lines.any { it.key == "people" }

    /** The verdict, on the page and as Home's line: "All checked", "1 thing to check", or why there is nothing to check. */
    fun verdict(lines: List<SecurityLine>, removed: Boolean) =
        if (inGroup(lines)) SecurityWords.verdict(flagged(lines)) else SecurityWords.outside(removed)

    /** Green only for a group this phone is in, with nothing to check. */
    fun allFine(lines: List<SecurityLine>) = inGroup(lines) && flagged(lines) == 0
}

/** Home's line under the hero: opens Group security, and says how much there is to check. */
@Composable
fun SecurityRow(s: AppState, a: MainActivity, g: SavedGroup, open: () -> Unit) {
    Marks.version // a person was just checked
    val lines = remember(s.tick, Marks.version, g, s.highRisk) { GroupSecurity.lines(a, g) }
    val removed = remember(s.tick, g) { MeshEngine.removed(g.code) }
    val shape = RoundedCornerShape(22.dp)
    Row(Modifier.fillMaxWidth().tap(CardPress, onClick = open).background(Theme.card, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Theme.tile, CircleShape).litEdge(CircleShape), contentAlignment = Alignment.Center) {
            Ic(R.drawable.ic_shield, if (GroupSecurity.allFine(lines)) Theme.safe else Theme.text, 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt("Group security", 17, FontWeight.SemiBold)
            Txt(GroupSecurity.verdict(lines, removed), 14, color = Theme.dim)
        }
        Ic(R.drawable.ic_chevron_right, Theme.faint, 22.dp)
    }
}

/** The page: a round shield, the verdict, and the six lines, each with a small button or a green check. */
@Composable
fun SecuritySheet(s: AppState, a: MainActivity, g: SavedGroup, close: () -> Unit) {
    var justChanged by remember(g) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) } // a new key is being made
    val lines = remember(s.tick, Marks.version, g, justChanged, s.highRisk) { GroupSecurity.lines(a, g, justChanged) }
    val fine = GroupSecurity.allFine(lines)
    val verdict = GroupSecurity.verdict(lines, remember(s.tick, g) { MeshEngine.removed(g.code) })
    // Everyone the key in use was made for, this phone included; not said while this phone doesn't know (joining, removed).
    val people = remember(s.tick, g, lines) { if (lines.any { it.key == "people" }) MeshEngine.memberCount(g.code) else 0 }
    val note = remember(s.tick, g) { groupNote(g) }
    var explained by remember { mutableStateOf<String?>(null) } // the line whose explanation is open
    SheetPage("Group security", close) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(76.dp).background(if (fine) Theme.safe.copy(alpha = 0.16f) else Theme.tile, CircleShape), contentAlignment = Alignment.Center) {
                Ic(R.drawable.ic_shield, if (fine) Theme.safe else Theme.text, 38.dp)
            }
            Spacer(Modifier.height(6.dp))
            Txt(verdict, 26, FontWeight.Black, align = TextAlign.Center)
            Txt(if (people > 0) "${g.name} · $people ${if (people == 1) "person" else "people"}" else g.name, 15, FontWeight.Medium, Theme.dim, align = TextAlign.Center)
        }
        // Removed, or not let in yet: said in the chat's own words.
        note?.let { (title, text) -> Card(padding = 16.dp) { Txt(title, 16, FontWeight.SemiBold); Txt(text, 14, color = Theme.dim) } }
        RowCard {
            lines.forEach { l ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).semantics(mergeDescendants = true) {}.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Theme.tile, CircleShape).litEdge(CircleShape), contentAlignment = Alignment.Center) { Ic(l.icon, Theme.text, 18.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(l.title, 16, FontWeight.SemiBold)
                        Txt(l.text, 14, color = Theme.dim)
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        l.button != null -> GhostButton(if (l.key == "key" && busy) "…" else l.button, enabled = !(l.key == "key" && busy), primary = true) {
                            when (l.key) {
                                "gone" -> s.sheet = Sheet.Timer
                                "people" -> { close(); s.tab = 3 }
                                "key" -> {
                                    busy = true
                                    MeshEngine.newKey(g.code) { ok ->
                                        busy = false
                                        if (ok == true) justChanged = true
                                        // null: this phone may not make one right now; the line no longer offers it.
                                        if (ok != null) Toast.makeText(a, if (ok) "Updated" else "Not everyone's keys have reached this ${Here.device} yet. Try again when they're in reach.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                "tor" -> { MeshEngine.setTor(true); s.tick++ }
                            }
                        }
                        l.explain != null -> RoundIcon(if (explained == l.key) R.drawable.ic_expand_more else R.drawable.ic_help, 36.dp, Theme.dim, Theme.tile,
                            if (explained == l.key) "Hide how the timer works" else "How the timer works") { explained = if (explained == l.key) null else l.key }
                        l.ok -> Box(Modifier.semantics { contentDescription = "Fine" }) { Ic(R.drawable.ic_check_circle, Theme.safe, 24.dp) }
                        // To check, and nothing this phone can do about it itself: a small dot says "look here".
                        l.flagged -> Box(Modifier.padding(horizontal = 8.dp).size(8.dp).background(Theme.text, CircleShape).semantics { contentDescription = "To check" })
                        else -> {} // neither fine nor to check: nothing
                    }
                }
                if (l.explain != null && (l.button != null || explained == l.key)) Txt(l.explain, 14, color = Theme.dim, modifier = Modifier.padding(start = 58.dp, end = 10.dp, bottom = 10.dp))
            }
        }
    }
}

/**
 * "Messages disappear after": the group's timer (spec/FORMAT.md, "Disappearing messages"). An admin picks a value, which
 * makes a new key that carries it; everyone else reads what the group has. Opened from the "+" tray and from Group
 * security. Same as the iPhone's TimerSheet.
 */
@Composable
fun TimerSheet(s: AppState, a: MainActivity, g: SavedGroup, close: () -> Unit) {
    var making by remember { mutableStateOf<Int?>(null) } // the value whose key is being made
    var failed by remember { mutableStateOf(false) }
    val t = remember(s.tick, g, making) { MeshEngine.timer(g.code) }
    val admin = remember(s.tick, g, making) { MeshEngine.canSetTimer(g.code) }
    fun choose(seconds: Int) {
        if (making != null) return
        if (t.seconds == seconds) { close(); return } // it is what the group has
        making = seconds; failed = false
        MeshEngine.setTimer(g.code, seconds) { ok ->
            making = null
            if (ok) { Toast.makeText(a, "Timer set", Toast.LENGTH_SHORT).show(); close() } else failed = true
        }
    }
    SmallSheet(close) {
        Txt("Messages disappear after", 21, FontWeight.Black, lineHeight = 26, modifier = Modifier.semantics { heading() })
        if (admin) {
            RowCard {
                (GroupTimer.CHOICES + 0).forEach { seconds ->
                    val label = if (seconds == 0) "Off" else GroupTimer.label(seconds)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(RowPress, enabled = making == null, role = Role.RadioButton,
                        label = if (seconds == 0) "Off, one to two weeks" else label) { choose(seconds) }
                        .semantics { selected = t.seconds == seconds }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Txt(label, 17, FontWeight.SemiBold)
                            if (seconds == 0) Txt("One to two weeks", 14, color = Theme.dim)
                        }
                        when {
                            making == seconds -> Txt("…", 17, FontWeight.Bold)
                            t.seconds == seconds -> Ic(R.drawable.ic_check, Theme.text, 20.dp)
                        }
                    }
                }
            }
            val now = t.seconds
            if (now != null && now > 0 && now !in GroupTimer.CHOICES) Txt("Now ${GroupTimer.label(now)}.", 15, FontWeight.SemiBold) // set by another version: none of the rows has the check
            else if (now == null) Txt("Not known on this ${Here.device} yet.", 15, FontWeight.SemiBold)
            if (failed) Txt("Not changed. This ${Here.device} doesn't have every member's keys yet. Try again when more phones are in reach.", 15, FontWeight.SemiBold)
            Txt("Counted from when a message reaches each phone. A new value starts deleting after 10 minutes. Phones on older DarkProof versions keep messages one to two weeks.", 14, color = Theme.dim)
        } else {
            Txt(GroupTimer.sheetLine(t.seconds, Here.device), 17)
            Txt(if (MeshEngine.removed(g.code) || MeshEngine.joining(g.code)) "This ${Here.device} isn't in ${g.name} now." else "Only admins can change this.", 15, color = Theme.dim)
        }
    }
}
