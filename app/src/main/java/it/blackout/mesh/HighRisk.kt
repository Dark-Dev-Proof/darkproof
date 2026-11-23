package it.blackout.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Settings: high-risk mode, leaving a group, and erasing everything. The rule of "keeps less" and its list of what it
// removed are plain Kotlin so unit tests run them; what each of the three removes is in the engine (MeshEngine.compact,
// leave) and in Wipe. Leaving and erasing may first tell the groups (spec/FORMAT.md, "Leaving the group"): the note goes
// out, then at most 15 s for a link to take it ([Telling]). Nothing else is sent.

/**
 * "High-risk mode" (Settings), for when this phone could be taken or searched. Saved like the app's other switches.
 * While it is on: the app's lock is on and asks every time the app is left ([AppLock]), alerts and the app switcher
 * show nothing (they follow the lock), and group messages leave this phone a day after they arrive ([KeepLess]).
 */
object HighRisk {
    private fun prefs(c: Context) = c.getSharedPreferences("mesh", 0)
    fun on(c: Context) = prefs(c).getBoolean("highRisk", false)
    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("highRisk", on).apply()

    /**
     * The sheet's "Says less." line (re-check screens 7, the same words on the iPhone): receipts leave out how a message
     * arrived, and Find by Bluetooth stops too, named only where it is offered ([finder]: the Finder trial on, on a phone).
     */
    fun saysLess(finder: Boolean) =
        if (finder) "No Find by Bluetooth, and receipts don't say how a message arrived." else "Receipts don't say how a message arrived."
}

/**
 * "Keeps less": in high-risk mode a group's messages and receipts leave this phone 24 hours after they reached it.
 * - What counts is when the packet ARRIVED here, by this phone's clock at that moment, not the date written inside it:
 *   a message that arrives days late is still readable for a day.
 * - A message written on this phone counts from when a link first took it (the moment it says "Sent"). Until then it
 *   never goes by this rule ([UNSENT]): a call for help that hasn't left the phone must not vanish.
 * - Only an arrival in the past counts. A clock set back removes less, never more (a flat battery costs no message).
 *   A clock moved FORWARD by days makes messages leave early: that can't be told from time passing, and the mode
 *   exists to keep less, so nothing here works around it.
 * Which packets the rule covers is decided by what a packet opened as, a group message or a receipt (MeshEngine.kept),
 * never by its mailbox, which an introduction or a new key of some group can share. What it removed is remembered by
 * name ([GoneList]), so a copy another phone still carries doesn't bring it back.
 */
object KeepLess {
    const val KEEP_MS = 24 * 3600_000L
    /** The arrival time of a message written on this phone that no link has taken yet. */
    const val UNSENT = -1L

    /**
     * One stored packet that opened as a group message or a receipt: when it reached this phone ([UNSENT] for one written
     * here that hasn't left), its header time, and its group's id.
     */
    class Held(val at: Long, val ts: Long, val group: String)

    /** True if what arrived at [at] leaves at [now]: more than 24 hours after an arrival that lies in the past. */
    fun due(at: Long, now: Long) = at in 1..now && now - at > KEEP_MS

    /** The names of [held] whose day is over at [now]. */
    fun due(held: Map<String, Held>, now: Long): List<String> = held.filter { due(it.value.at, now) }.map { it.key }

    /**
     * A stored packet's arrival time. [saved] is what the store holds: 0 for a packet stored before arrivals were noted.
     * Such a packet counts from its own date ([written]), the earliest it can have come; except a message of this phone's
     * that no link has taken yet ([ownUnsent]), which stays.
     */
    fun arrival(saved: Long, written: Long, ownUnsent: Boolean) = when { saved != 0L -> saved; ownUnsent -> UNSENT; else -> written }
}

/**
 * The packets "keeps less" removed, by name: the packet's id with the key of its content ([MeshRouter.key]), its header
 * time and its group's id. Nothing of what was in it. Other phones go on carrying a packet for 7 days and bring it
 * again, after a restart of this app too, so the list is saved with the store (MeshEngine, table g).
 *
 * A packet on the list is never opened for the chat, shown, alerted, counted as unread, confirmed with a receipt or
 * copied to the history again: it is only carried ([arrival]). A name goes once its packet is older than the 7 days
 * phones pass packets on, because no phone takes it after that. At most [cap] names: past that the oldest packets'
 * names go first. Erase removes the list with the store, and leaving a group removes that group's names.
 * Not thread-safe: the engine guards it with its messages lock.
 */
class GoneList(private val cap: Int = CAP) {
    class Entry(val key: String, val ts: Long, val group: String)

    /** What this phone does with a packet that arrives. */
    enum class Arrival {
        /** Not on the list: read like any packet (opened, shown, alerted, confirmed). */
        READ,
        /** On the list, and nothing on this phone opens it: stored and passed on like every packet it can't open. */
        CARRY,
        /**
         * On the list, and a key this phone still holds would open it (a receipt, or a message that has no key of its
         * own to delete): passed on to the phones linked now, but not written to the store, where it would be readable.
         */
        PASS_ON,
    }

    private val map = HashMap<String, Entry>()
    val size get() = map.size
    operator fun contains(key: String) = map.containsKey(key)
    /** The names, for reading the store without holding the engine's lock. */
    fun keys(): Set<String> = HashSet(map.keys)
    /** Everything on the list, oldest packet first: what is saved. */
    fun entries(): List<Entry> = map.values.sortedBy { it.ts }

    /** Adds [new]. Returns the names that go to make room: those over the cap, oldest packets first. */
    fun add(new: Collection<Entry>): List<String> {
        for (e in new) map[e.key] = e
        if (map.size <= cap) return emptyList()
        return map.values.sortedBy { it.ts }.take(map.size - cap).map { it.key }.onEach { map.remove(it) }
    }

    /** Removes, and returns, the names whose packets are older at [now] than the 7 days phones pass packets on. */
    fun expire(now: Long): List<String> = map.values.filter { it.ts < now - AGE_MS }.map { it.key }.onEach { map.remove(it) }

    /** The group [group] was left: its names go. Returns them. */
    fun forgetGroup(group: String): List<String> = map.values.filter { it.group == group }.map { it.key }.onEach { map.remove(it) }

    /** What happens to the packet named [key] when it arrives. [opens] is asked only for a packet on the list. */
    fun arrival(key: String, opens: () -> Boolean): Arrival = if (key !in map) Arrival.READ else if (opens()) Arrival.PASS_ON else Arrival.CARRY

    companion object {
        const val CAP = 5_000
        /** How long phones pass a packet on ([MeshRouter]). */
        const val AGE_MS = 7 * 24 * 3600_000L
    }
}

/** Switches high-risk mode on or off, once the phone's own unlock has agreed (the caller asks). */
fun switchHighRisk(a: MainActivity, s: AppState, on: Boolean) {
    HighRisk.set(a, on); s.highRisk = on
    // The lock goes on through the same switch as "Lock DarkProof", and stays on when high-risk mode is switched off again.
    if (on && Here.lock) a.setAppLock(true)
    if (on) MeshEngine.keepLess() // what arrived more than a day ago goes at once; switched off, nothing comes back
    MeshEngine.quietPass() // check-in clocks: positions go, and their alerts are worded again (spec "If I go quiet")
    s.tick++
}

/** Settings' "High-risk mode": what it does before it is switched on, and the way back once it is. */
@Composable
fun HighRiskSheet(s: AppState, a: MainActivity, close: () -> Unit) {
    var why by remember { mutableStateOf<String?>(null) } // why it stayed as it was
    fun done(on: Boolean) { switchHighRisk(a, s, on); close(); Toast.makeText(a, if (on) "High-risk mode is on" else "High-risk mode is off", Toast.LENGTH_SHORT).show() }
    SmallSheet(close) {
        if (s.highRisk) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp).background(Theme.safe.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) { Ic(R.drawable.ic_shield, Theme.safe, 30.dp) }
                Txt("High-risk mode is on", 21, FontWeight.Black, align = TextAlign.Center)
                // A computer has no lock and shows no alerts yet: it says what it does, not what a phone does.
                Txt(if (Here.lock) "This phone locks every time, shows less and keeps less." else "This ${Here.device} keeps less. It can't lock DarkProof yet.", 15, color = Theme.dim, align = TextAlign.Center)
            }
            why?.let { Txt(it, 15, FontWeight.SemiBold) }
            SecondaryButton("Switch it off") { AppLock.ask(a, "Switch off high-risk mode", { why = "High-risk mode stays on: the phone's unlock didn't go through." }) { done(false) } }
            TextAction("Keep it on") { close() }
        } else {
            Txt("Switch on high-risk mode?", 22, FontWeight.Black, lineHeight = 27)
            Txt("For when this ${Here.device} could be taken or searched.", 15, color = Theme.dim)
            if (Here.lock) {
                Point(R.drawable.ic_lock, "Locks every time.", "Your fingerprint, face or screen lock whenever you open DarkProof.")
                Point(R.drawable.ic_visibility_off, "Shows less.", "No names or message text in alerts or the app switcher.")
            }
            Point(R.drawable.ic_schedule, "Keeps less.", "Messages in your groups leave this ${Here.device} 1 day after they arrive.")
            // Receipts leave out how a message arrived (design review L6), and Find by Bluetooth stops where it is offered:
            // only with the Finder trial on, on a phone (re-check screens 7).
            Point(R.drawable.ic_route, "Says less.", HighRisk.saysLess(FinderPrefs.offered(remember { FinderPrefs.trial(a) })))
            if (!Here.lock) Txt("On a ${Here.device} DarkProof has no lock and shows no alerts yet, so here it only keeps less.", 14, color = Theme.dim)
            why?.let { Txt(it, 15, FontWeight.SemiBold) }
            PrimaryButton("Yes, switch it on") {
                when {
                    !Here.lock -> done(true)
                    !AppLock.available(a) -> why = "High-risk mode stays off. Set a screen lock on this phone first: without one there is nothing to lock DarkProof with."
                    else -> AppLock.ask(a, "Switch on high-risk mode", { why = "High-risk mode stays off: the phone's unlock didn't go through." }) { done(true) }
                }
            }
            TextAction("Not now") { close() }
        }
    }
}

/** One thing high-risk mode does: what it is in two words, then what that means. */
@Composable
private fun Point(@DrawableRes icon: Int, lead: String, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(40.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Ic(icon, Theme.text, 20.dp) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(lead, 16, FontWeight.Bold); Txt(text, 15, color = Theme.dim)
    }
}

/**
 * The wait after leave notes were sent (spec/FORMAT.md, "Leaving the group", LW3 and LW4): until a phone, a radio or a
 * relay took every note ("Sent", [MeshEngine.noteLeft]), or [LeaveNote.WAIT_SECONDS], or the user taps "Leave now" or
 * "Erase now" ([now]). The screens show [left]. Same as the iPhone's Telling.
 */
object Telling {
    /** Seconds left while waiting; null when nothing waits. */
    var left by mutableStateOf<Int?>(null); private set
    private val main = Handler(Looper.getMainLooper())
    private var notes: List<String> = emptyList()
    private var started = 0L
    private var done: ((Set<String>) -> Unit)? = null
    private var forErase = false
    private val tick = object : Runnable { override fun run() { if (check()) main.postDelayed(this, 250) } }

    /** A wait is on: no second leave starts meanwhile. */
    val waiting: Boolean @Synchronized get() = done != null

    /**
     * "Erase everything" began: from now on a leave that was under way does nothing more (the erase takes precedence and
     * removes that group with everything else), and no leave starts. The app starts again empty afterwards.
     */
    @Volatile var erasing = false; private set

    /**
     * The erase takes over (rule M6 of the review: an erase never waits for a leave, and never asks to try again): a leave
     * waiting for its note stops waiting, without its last step. The ids of the notes it waited for: the erase waits for
     * them instead of sending a second note to that group. None when no leave waited.
     */
    @Synchronized fun takeOver(): List<String> {
        erasing = true
        if (done == null || forErase) return emptyList()
        val ids = notes
        main.removeCallbacks(tick); done = null; left = null; notes = emptyList()
        return ids
    }

    /** Debug builds' erase that is only shown ([Wipe.dryRun]): nothing was erased, and leaving works again. */
    fun dryRunOver() { erasing = false }

    /** Waits for [ids]; [then] gets, on the main thread, the ones that left this phone. With none, it ends at once. [erase]: an erase waits ([backInFront]). */
    @Synchronized fun wait(ids: List<String>, erase: Boolean = false, then: (Set<String>) -> Unit) {
        if (erasing && !erase) return // a leave's note after the erase took over: the erase waits, not the leave
        end() // never two waits at once
        notes = ids; done = then; forErase = erase; started = System.nanoTime(); left = LeaveNote.WAIT_SECONDS
        main.post(tick)
    }

    /** "Leave now", "Erase now": the wait ends at once. */
    fun now() = end()

    /** The app came back to the front while an erase waited for its notes: the wait ends now, and the erase runs (LW4). */
    fun backInFront() { if (synchronized(this) { forErase }) end() }

    private fun gone(): Set<String> = notes.filter(MeshEngine::noteLeft).toSet()

    /** True while it still waits. */
    private fun check(): Boolean {
        val end = synchronized(this) {
            if (done == null) return false
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            val l = maxOf(0, LeaveNote.WAIT_SECONDS - (elapsed / 1_000L).toInt())
            if (left != l) left = l
            gone().size == notes.size || elapsed >= LeaveNote.WAIT_SECONDS * 1_000L
        }
        if (end) this.end()
        return !end
    }

    private fun end() {
        val (then, g) = synchronized(this) {
            val then = done ?: return
            val g = gone()
            main.removeCallbacks(tick); done = null; left = null; notes = emptyList(); forErase = false
            then to g
        }
        main.post { then(g) }
    }
}

/**
 * Leaves [g] after telling it (rules LW1 to LW3): with [handOver] (the only admin chose who runs the group), one admin new
 * key first; then the note; then the wait, until a phone, a radio or a relay took it, 15 s, or "Leave now"; then the
 * group is forgotten, its note's packet kept to carry. A phone that can't write in the group (LW1) leaves without a note.
 * [cantHandOver]: the new key couldn't be made (a member's keys are missing); nothing happened, and the screen asks what
 * to do. [finished]: the sheet closes. This phone never removes itself: an admin's phone that stays does. Main thread.
 */
fun leaveTelling(a: MainActivity, s: AppState, g: SavedGroup, handOver: List<String> = emptyList(), cantHandOver: () -> Unit = {}, finished: () -> Unit) {
    if (MeshEngine.isLeaving || Telling.waiting || Telling.erasing) return
    if (!MeshEngine.canTell(g.code)) return leaveGroup(a, s, g, null, null, finished)
    fun send() {
        if (Telling.erasing) return // an erase took over while the new key was made: it tells this group too
        val id = MeshEngine.sendLeaveNote(g.code) ?: return leaveGroup(a, s, g, null, null, finished)
        Telling.wait(listOf(id)) { gone ->
            DebugTools.log("leave: the note ${if (gone.isEmpty()) "didn't leave this phone in time" else "left this phone"}")
            leaveGroup(a, s, g, id, gone.isNotEmpty(), finished)
        }
    }
    if (handOver.isEmpty()) send() else MeshEngine.handOver(g.code, handOver) { ok -> if (Telling.erasing) Unit else if (ok) send() else cantHandOver() }
}

/**
 * This device forgets [g] ([MeshEngine.leave]). [note]: the leave note's packet, which stays as one this device only
 * carries. [noteLeft]: whether a link took it in time (null: none was sent), for the toast.
 */
fun leaveGroup(a: MainActivity, s: AppState, g: SavedGroup, note: String?, noteLeft: Boolean?, finished: () -> Unit) {
    finished()
    MeshEngine.leave(a, g.code, note) { next ->
        // The group now in use, or none: then the app is back at its welcome screen, with the name kept.
        s.drafts.remove(g.code); s.everyone = false; s.tab = 0
        s.group = next; s.tick++
        Toast.makeText(a, LeaveWords.left(g.name, noteLeft, Here.device), Toast.LENGTH_LONG).show()
    }
}

/**
 * "Erase everything" (rule LW4). With [tell]: a note to every saved group this device can write in, before anything
 * else; then at most 15 s for links to take them, with "Erase now"; then [Wipe.start], unchanged, with the last screen's
 * line about the notes, which names no group. Nothing can hold the erase up: the erase's mark is saved before the notes go
 * ([Wipe.markFirst]), so an app that ends while it waits erases at its next start, and the wait ends at once if the app
 * comes back to the front ([Telling.backInFront]). A leave under way never holds it up either: a leave waiting for its
 * note stops waiting ([Telling.takeOver]) and the erase waits for that note instead of sending a second one, a leave's
 * new key that is still being made sends no note afterwards, and a group that is being forgotten is let finish by
 * [Wipe.start]. A second call while the erase runs does nothing.
 */
fun eraseTellingFirst(a: MainActivity, tell: Boolean) {
    if (Telling.erasing) return
    val waited = Telling.takeOver()
    val groups = Groups.all(a); val d = Here.device
    if (!tell) return Wipe.start(a, listOfNotNull(LeaveWords.erased(groups.size, 0, 0, d)))
    Wipe.markFirst(a) // from here on, the erase happens whatever stops the app
    val sent = groups.mapNotNull { g -> MeshEngine.toldNote(g.code)?.takeIf { it in waited } ?: MeshEngine.sendLeaveNote(g.code) }
    DebugTools.log("erase: ${sent.size} note(s) on their way, ${groups.size - sent.size} group(s) this ${Here.device} can't write in")
    Telling.wait(sent, erase = true) { gone ->
        DebugTools.log("erase: ${gone.size} of ${sent.size} note(s) left this ${Here.device}")
        Wipe.start(a, listOfNotNull(LeaveWords.erased(groups.size, sent.size, gone.size, d)))
    }
}

/**
 * Settings' "Leave <group>": staying is the main choice. Leaving sends a note that asks the group's admins to remove this
 * phone and change the key (spec/FORMAT.md, "Leaving the group"), then waits at most 15 s for a link to take it. The
 * group's only admin first picks who runs the group after them. Same steps as the iPhone's LeaveSheet.
 */
@Composable
fun LeaveSheet(s: AppState, a: MainActivity, g: SavedGroup, close: () -> Unit) {
    var step by remember { mutableStateOf<String?>(null) } // "choose", "ask", "cantHandOver"; null: the first that applies
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var sent by remember { mutableStateOf(false) } // "Leave" was tapped: the note's wait, or the new key before it, is on
    val onlyAdmin = remember(s.tick, g) { MeshEngine.onlyAdmin(g.code) }
    val canTell = remember(s.tick, g) { MeshEngine.canTell(g.code) }
    val left = Telling.left
    val d = Here.device
    fun finished() { close(); s.sheet = null }
    fun closeUnlessSent() { if (!sent) close() }
    if (sent && left != null) {
        SmallSheet({}) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(LeaveWords.waitingTitle(g.name), 21, FontWeight.Black, align = TextAlign.Center, lineHeight = 26)
                Txt(LeaveWords.WAITING_TEXT, 15, color = Theme.dim, align = TextAlign.Center)
                Txt("$left", 34, FontWeight.Black, align = TextAlign.Center, modifier = Modifier.semantics { contentDescription = "$left seconds left" })
            }
            SecondaryButton(LeaveWords.LEAVE_NOW, color = Theme.help) { Telling.now() }
        }
        return
    }
    when (step ?: if (onlyAdmin) "choose" else "ask") {
        // Only admin (rule LW2): who runs the group afterwards. The new key that makes them admins is made when "Leave" is
        // tapped, right before the note, so choosing "Stay" afterwards changes nothing in the group.
        "choose" -> SmallSheet(::closeUnlessSent) {
            Txt(LeaveWords.chooseTitle(g.name), 21, FontWeight.Black, lineHeight = 26)
            Txt(LeaveWords.CHOOSE_TEXT, 15, color = Theme.dim)
            val members = remember(s.tick, g) { MeshEngine.otherMembers(g.code) }
            RowCard {
                for ((id, name) in members) {
                    val on = id in picked
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(on, role = Role.Checkbox) { picked = if (it) picked + id else picked - id }
                        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(name.ifEmpty { "Someone" }, 17, maxLines = 1, modifier = Modifier.weight(1f))
                        if (on) Ic(R.drawable.ic_check_circle, Theme.text, 24.dp) else Box(Modifier.size(22.dp).border(2.dp, Theme.faint, CircleShape))
                    }
                }
            }
            PrimaryButton("Continue", enabled = picked.isNotEmpty()) { step = "ask" }
            SecondaryButton(LeaveWords.LEAVE_WITHOUT_CHOOSING, color = Theme.help) { picked = emptySet(); step = "ask" }
            Txt(LeaveWords.WITHOUT_CHOOSING_WARNING, 14, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        "cantHandOver" -> ChoiceSheet(LeaveWords.sheetTitle(g.name), LeaveWords.cantHandOver(d), "Cancel", LeaveWords.LEAVE_WITHOUT_TELLING, icon = R.drawable.ic_logout,
            otherDanger = true, onMain = close, onClose = close, onOther = { leaveGroup(a, s, g, null, null, ::finished) })
        else -> ChoiceSheet(LeaveWords.sheetTitle(g.name), LeaveWords.sheetText(d), "Stay", LeaveWords.leaveButton(g.name), icon = R.drawable.ic_logout,
            otherDanger = true, onMain = ::closeUnlessSent, onClose = ::closeUnlessSent, onOther = {
                if (!sent) { sent = true; leaveTelling(a, s, g, picked.toList(), cantHandOver = { sent = false; step = "cantHandOver" }, finished = ::finished) }
            }) {
            if (!canTell) Txt(LeaveWords.cantTell(g.name, d), 15, FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** This phone is an admin of [code]'s group, others are in it, and none of them is an admin ([MeshEngine.onlyAdmin]). */
fun onlyAdmin(code: String): Boolean = MeshEngine.onlyAdmin(code)

/**
 * "Erase everything": a whole screen, not an alert. The red button ([HoldButton]) has to be held about 1.3 seconds while it
 * fills; letting go early stops it. With TalkBack, a switch or a keyboard there is no holding: activating it asks once more.
 * With "Tell my groups first" on, the hold sends a note to each group, then waits at most 15 s for a link to take them,
 * with "Erase now" always there (spec/FORMAT.md, "Leaving the group"); then the erase runs as without it.
 */
@Composable
fun EraseScreen(a: MainActivity, close: () -> Unit) {
    var sure by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var tell by remember { mutableStateOf(true) }
    val left = Telling.left
    val what = "Messages, groups, keys and the AI model leave this ${Here.device}. This can't be undone."
    // It always starts: a leave under way gives way to it ([eraseTellingFirst]).
    fun erase() {
        if (erasing) return
        erasing = true
        eraseTellingFirst(a, tell)
    }
    // The page covers the whole screen, the system's bars included; where those are is known out here, not inside it.
    val bars = WindowInsets.systemBars.asPaddingValues()
    androidx.compose.ui.window.Dialog({ if (!erasing) close() }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Theme.bg).padding(bars).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                if (erasing && left != null) {
                    Txt(LeaveWords.TELLING_TITLE, 28, FontWeight.Black, align = TextAlign.Center, lineHeight = 34)
                    Txt(LeaveWords.WAITING_TEXT, 16, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
                    Txt("$left", 40, FontWeight.Black, align = TextAlign.Center, modifier = Modifier.semantics { contentDescription = "$left seconds left" })
                } else {
                    Box(Modifier.size(104.dp).background(Theme.help.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) { Ic(R.drawable.ic_delete, Theme.help, 46.dp) }
                    Spacer(Modifier.height(6.dp))
                    Txt(if (erasing) "Erasing…" else "Erase everything", 28, FontWeight.Black, align = TextAlign.Center, lineHeight = 34)
                    Txt(what, 16, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
                    if (!erasing) Box(Modifier.widthIn(max = 400.dp).fillMaxWidth().background(Theme.card, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        SwitchRow(LeaveWords.TELL_FIRST, tell, { tell = it }, note = LeaveWords.tellFirstSub(Here.device))
                    }
                    NotAllErased()
                }
            }
            // Nothing waits on the network: the erase starts at once.
            if (erasing && left != null) DangerButton(LeaveWords.ERASE_NOW, Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) { Telling.now() }
            // Side by side, and no wider than a phone's screen in a wide window.
            if (!erasing) Row(Modifier.widthIn(max = 520.dp).align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f)) { close() }
                HoldButton("Hold to erase", Modifier.weight(1f), action = "Erase everything", onTap = { Toast.makeText(a, "Keep holding to erase", Toast.LENGTH_SHORT).show() },
                    ask = { sure = true }, onDone = { erase() })
            }
        }
    }
    if (sure) Confirm("Erase everything?", what, "Erase everything", danger = true, onYes = { sure = false; erase() }, onNo = { sure = false })
}

/**
 * After "Erase everything": DarkProof is empty on this device, and what became of the notes ([Wipe.erased]), counted,
 * never by group name ([LeaveWords.erased]). When something was left behind the headline says so instead, and never
 * "empty" (design review M4). Held in memory only; "Continue" goes on to the welcome screen.
 */
@Composable
fun EmptyScreen(lines: List<String>, done: () -> Unit) {
    val bars = WindowInsets.systemBars.asPaddingValues()
    val notAll = Wipe.notAll
    androidx.compose.ui.window.Dialog({}, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Theme.bg).padding(bars).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                Box(Modifier.size(104.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Ic(if (notAll) R.drawable.ic_delete else R.drawable.ic_check, Theme.text, 46.dp) }
                Spacer(Modifier.height(6.dp))
                Txt(if (notAll) LeaveWords.NOT_ALL else LeaveWords.empty(Here.device), 28, FontWeight.Black, align = TextAlign.Center, lineHeight = 34)
                if (notAll) Txt(LeaveWords.notAllLine(Here.device), 16, FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
                for (l in lines) Txt(l, 16, color = Theme.dim, align = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
            }
            PrimaryButton("Continue", Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) { done() }
        }
    }
}

/** After an erase that couldn't remove everything, and only then: what is left to do. On the erase page and on the welcome screen. */
@Composable
fun NotAllErased() {
    if (Wipe.notAll) Txt("${LeaveWords.NOT_ALL}. ${LeaveWords.notAllLine(Here.device)}", 15, FontWeight.SemiBold, Theme.text, align = TextAlign.Center) // white, as the iPhone: red is for help (re-check screens 8)
}
