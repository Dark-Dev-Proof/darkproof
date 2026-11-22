package it.blackout.mesh

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// People asking to join a group (spec "Joining"), for its admins: one line above the chat, and a sheet with each request.

/** The members' names the way the app compares names ([Vouch.key]), without empty ones: a name not known yet matches nothing. */
fun memberNameKeys(names: List<String>): Set<String> = names.map { Vouch.key(it) }.filterTo(HashSet()) { it.isNotEmpty() }

/** The first letter of a name, for a round initial. */
fun initialOf(name: String) = name.trim().take(1).uppercase().ifEmpty { "?" }

/** Above the group chat: who is asking to join, behind one line, so the requests don't bury the chat. Opens [JoinSheet]. */
@Composable
fun JoinRow(requests: List<JoinRequest>, open: () -> Unit) {
    if (requests.isEmpty()) return
    val n = requests.size
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().tap(Press(down = 0.98f, shape = shape, dim = 0f, lift = 0.05f, light = 0.05f), onClick = open)
        .background(Theme.card, shape).litEdge(shape)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy((-9).dp)) { // up to three initials, overlapping
            requests.take(3).forEach { r ->
                Box(Modifier.size(34.dp).background(Theme.card, CircleShape).padding(2.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) {
                    Txt(initialOf(r.name), 13, FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(if (n == 1) "1 person asks to join" else "$n people ask to join", 16, FontWeight.SemiBold, maxLines = 1)
            Txt("Let in only people you know", 14, color = Theme.dim, maxLines = 1)
        }
        Ic(R.drawable.ic_chevron_right, Theme.faint, 22.dp)
    }
}

/**
 * Every request, never merged: two with the same name are two different phones. "Let in" asks once more before it
 * calls the engine; "Not now" turns the request down, as before. Closes by itself when the last one is answered.
 */
@Composable
fun JoinSheet(s: AppState, g: SavedGroup, close: () -> Unit) {
    val ctx = LocalContext.current
    val requests = remember(s.tick, g) { MeshEngine.joinRequests(g.code) }
    var sure by rememberSaveable { mutableStateOf<String?>(null) }   // the request "Let in" was tapped on
    val letting = remember { mutableStateListOf<String>() }          // being let in: the new key is on its way
    LaunchedEffect(requests.isEmpty()) { if (requests.isEmpty()) close() }
    // The same name the way the app compares names (Vouch.key): case and spaces around it don't matter ("Ben" is "ben "),
    // and neither does anything that draws the same, so a name that only looks like another one counts too.
    val shared = remember(requests) { requests.groupingBy { Vouch.key(it.name) }.eachCount().filterValues { it > 1 }.keys }
    // Someone asking under a name the group already has (this phone's own included): it may be that person's new phone, or someone else.
    val members = remember(s.tick, g, s.name) { memberNameKeys(MeshEngine.otherMembers(g.code).map { it.second } + s.name) }
    SheetPage("Asking to join", close) {
        Txt("Read the sixteen emoji with them, out loud or side by side. They must be the same on both phones.", 15, color = Theme.dim)
        requests.forEach { r ->
            key(r.id) {
                Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val emoji = remember(r.id) { P256.emoji(r.pub) }
                    if (sure == r.id) {
                        Txt("Let ${r.name} in?", 18, FontWeight.Bold)
                        Txt("${r.name} reads everything written from now on.", 15, color = Theme.dim)
                        PersonEmoji(emoji, 24) // still here: with two requests under one name, the emoji say which one this is
                        PrimaryButton("Yes, let ${r.name} in") {
                            sure = null; letting += r.id
                            MeshEngine.approveJoin(g.code, r.id) { ok ->
                                letting -= r.id
                                if (!ok) Toast.makeText(ctx, "Not everyone's keys have reached this ${Here.device} yet. Try again when they're in reach.", Toast.LENGTH_LONG).show()
                            }
                        }
                        SecondaryButton("Cancel") { sure = null }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).background(Theme.tile, CircleShape), contentAlignment = Alignment.Center) { Txt(initialOf(r.name), 18, FontWeight.Bold) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(r.name, 17, FontWeight.SemiBold, maxLines = 1)
                                Txt("Not checked", 14, color = Theme.faint)
                            }
                        }
                        PersonEmoji(emoji, 24)
                        if (Vouch.key(r.name) in members) Txt("A member already uses this name. Compare the emoji.", 14, FontWeight.SemiBold)
                        if (Vouch.key(r.name) in shared) Txt("Another request uses this name. Compare the emoji.", 14, FontWeight.SemiBold)
                        if (r.id in letting) Txt("Letting ${r.name} in…", 15, FontWeight.SemiBold, Theme.dim)
                        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("Let in", Modifier.weight(1f)) { sure = r.id }
                            SecondaryButton("Not now", Modifier.weight(1f)) { if (sure == r.id) sure = null; MeshEngine.declineJoin(g.code, r.id) }
                        }
                    }
                }
            }
        }
    }
}
