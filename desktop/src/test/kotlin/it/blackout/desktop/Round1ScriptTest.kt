package it.blackout.desktop

import it.blackout.mesh.CheckedHow
import it.blackout.mesh.GroupCrypto
import it.blackout.mesh.Groups
import it.blackout.mesh.HighRisk
import it.blackout.mesh.MeshEngine
import it.blackout.mesh.MlKem
import it.blackout.mesh.P256
import it.blackout.mesh.Packet
import it.blackout.mesh.PersonCode
import it.blackout.mesh.codeScanned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six pieces of round 1 on the desktop, used by a script the way a person uses them ([Screen]), on the real
 * engine: join requests behind one line, the "+" tray, Group security, checking a person, high-risk mode, and leaving a
 * group. It all happens in a group of its own, made here and left at the end, so the group the other tests share is
 * as it was. Pictures along the way are left in build/screen-round1-*.png.
 */
class Round1ScriptTest {
    /** Someone asks to join [code]'s group, as their phone would: an introduction through the door, with an ML-KEM key. */
    private fun asks(code: String, name: String) {
        val cr = GroupCrypto(code).intros(); val pair = P256.newKeyPair(); val pub = P256.compress(pair.public)
        val id = Packet.newId(cr.mailbox); val now = System.currentTimeMillis()
        MeshEngine.router!!.receive("test", Packet(id, now, 16, cr.seal(id, now, P256.memberId(pub), name, "",
            extra = byteArrayOf(3) + pub + P256.uncompressed(P256.newKeyPair().public) + MlKem.newKey().first) { P256.sign(pair.private, it) }))
    }

    @Test fun theSixPiecesWorkOnAComputer() {
        RealEngine.started(); val a = RealEngine.activity; val c = a
        val mine = Groups.all(c).first { it.name == "My group" }
        Screen { App(a) }.use { s ->
            try {
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()

                // A group of its own for this test, made the way a person makes one
                s.tap("Join or start another group"); s.tap("Create a group"); s.typeInto(0, "Temp"); s.tap("Create group")
                s.await("the new group in use") { Groups.current(c)?.name == "Temp" && MeshEngine.currentCode == Groups.current(c)?.code }
                val code = Groups.current(c)!!.code; s.secrets += code
                s.await("its invite panel") { s.has("Invite to Temp") }; s.tap("Close")
                s.await("this computer to be its admin") { MeshEngine.isAdmin(code, MeshEngine.deviceId) }
                val groupId = MeshEngine.groupId(code)!!

                // Piece 7: two people ask under one name, and a third. One line above the chat, however many ask.
                asks(code, "Ben"); asks(code, "BEN"); asks(code, "Giulia")
                s.await("the one line") { s.has("3 people ask to join") && s.has("Let in only people you know") }
                assertFalse("no card per request above the chat any more", s.has("wants to join"))
                s.tap("3 people ask to join")
                assertTrue(s.has("Asking to join") && s.has("Read the sixteen emoji with them, out loud or side by side. They must be the same on both phones."))
                val who = MeshEngine.joinRequests(code).first().name // the sheet lists them in the engine's order: the first one is let in
                s.png("build/screen-round1-joins.png")
                // "Let in" asks once more, with the emoji still there, and only then lets them in
                val before = MeshEngine.memberCount(code)
                s.tap("Let in", 0)
                assertTrue(s.has("Let $who in?") && s.has("$who reads everything written from now on.")); assertEquals(before, MeshEngine.memberCount(code))
                s.tap("Cancel"); assertFalse(s.has("Let $who in?")); assertEquals(before, MeshEngine.memberCount(code))
                s.tap("Let in", 0); s.tap("Yes, let $who in")
                s.await("$who to be in") { MeshEngine.otherMembers(code).map { it.second } == listOf(who) }
                s.await("the list without them") { MeshEngine.joinRequests(code).size == 2 && !s.has("Letting $who in") }
                // Two requests under one name say so on both (names are compared without case), other ones don't
                val twins = MeshEngine.joinRequests(code).map { it.name.lowercase() }.let { it[0] == it[1] }
                s.await("the note under requests that share a name") { s.texts().count { it == "Another request uses this name. Compare the emoji." } == if (twins) 2 else 0 }
                // "Not now" turns one down; the last one answered closes the sheet and the line goes
                s.tap("Not now", 0); s.await("one request left") { MeshEngine.joinRequests(code).size == 1 }
                s.await("no note about the name with one request") { !s.has("Another request uses this name") }
                s.tap("Not now", 0)
                s.await("the sheet and the line gone") { !s.has("Asking to join") && !s.has("ask to join") && !s.has("asks to join") }
                s.await("the chat's line about them") { s.has("$who joined") }

                // Piece 8: a member who hasn't written yet is in People, with how they are checked
                s.await("$who in People") { s.says(who) && s.has("No check-in yet") && s.says("Not checked") }
                val id = MeshEngine.otherMembers(code).single().first
                s.tap(who); assertTrue(s.says("Check") && s.says("Ask if safe") && s.says("Make admin") && s.says("Remove from group")); assertFalse(s.says("Find"))
                s.png("build/screen-round1-person.png")
                s.tap("Check")
                // A computer has no camera: it starts on the emoji, and its Scan tab shows this computer's code and says so
                assertTrue(s.has("Check $who") && s.has("Ask $who to read these out. If $who's phone shows the same sixteen, nobody is in between."))
                s.tap("Scan"); assertTrue(s.has("Your person code in Temp, for $who to scan") && s.has("This device has no camera. Compare the emoji instead.")); assertFalse(s.has("Scan $who's code"))
                s.png("build/screen-round1-check-scan.png")
                s.tap("Compare emoji"); s.png("build/screen-round1-check-emoji.png"); s.tap("They match")
                assertTrue(s.has("$who is checked") && s.has("Checked by emoji, today. If $who's key ever changes, DarkProof shows it in People and under their messages."))
                assertEquals(CheckedHow.EMOJI, MeshEngine.checkedHow(code, id))
                s.tap("Done"); s.await("the badge in People") { s.says("Checked by emoji") }
                // A scanned code is judged the same way wherever it comes from (a phone's camera): theirs, this computer's own, another key
                codeScanned(a.state, code, id, who, PersonCode.encode(MeshEngine.myPublicKey(code)!!)); assertEquals(PersonCode.Result.OWN, a.state.checkEnded?.problem)
                codeScanned(a.state, code, id, who, PersonCode.encode(P256.compress(P256.newKeyPair().public))); assertEquals(PersonCode.Result.OTHER, a.state.checkEnded?.problem)
                codeScanned(a.state, code, id, who, "https://darkproof.app"); assertEquals(PersonCode.Result.NOT_A_CODE, a.state.checkEnded?.problem)
                assertEquals("none of those marked anything", CheckedHow.EMOJI, MeshEngine.checkedHow(code, id))
                codeScanned(a.state, code, id, who, PersonCode.encode(MeshEngine.publicKeyOf(code, id)!!)); assertEquals(CheckedHow.IN_PERSON, MeshEngine.checkedHow(code, id))
                a.state.checkEnded = null; s.await("the stronger badge") { s.says("Checked in person") }

                // Piece 6: Group security, from the chat's header
                s.tap("Security")
                assertTrue(s.has("Group security") && s.says("All checked") && s.has("Temp · 2 people"))
                assertTrue(s.says("People checked") && s.says("1 of 1") && s.says("Group key") && s.says("Last changed today") && s.says("Quantum-safe keys") && s.says("On, always"))
                assertTrue(s.says("Internet through Tor") && s.says("The internet is off") && s.says("Messages disappear") && s.says("After one to two weeks"))
                assertTrue(s.says("Invite") && s.says("Open. Anyone with the code can ask to join."))
                assertFalse("no new key within the hour of this computer's last one (it just let someone in)", s.says("New key"))
                s.png("build/screen-round1-security.png"); s.tap("Close")
                // "They don't match" takes the mark away, and Group security then has one thing to check
                s.tap(who); s.tap("Check"); s.tap("They don't match")
                s.await("the note") { s.has("$who stays unchecked") }; assertNull(MeshEngine.checkedHow(code, id))
                s.tap("Security"); assertTrue(s.says("1 thing to check") && s.says("0 of 1. $who is not.") && s.says("Check"))
                s.tap("Close")

                // Piece 2: the "+" in the group's message box. No camera here, and none offered.
                s.tap("Add something"); assertTrue(s.says("Photo") && s.says("I'm safe") && s.says("I need help") && s.says("Ask if safe")); assertFalse(s.says("Camera"))
                s.png("build/screen-round1-tray.png")
                s.tap("Ask if safe"); assertTrue(s.has("Ask if everyone is safe?")); s.tap("Cancel")

                // Piece 11: Settings. High-risk mode on a computer says what it does here, and no more.
                s.tap("Settings"); assertTrue(s.says("High-risk mode") && s.says("Off"))
                s.tap("High-risk mode")
                assertTrue(s.has("Switch on high-risk mode?") && s.has("Keeps less.") && s.has("Messages in your groups leave this computer 1 day after they arrive."))
                for (phoneOnly in listOf("Locks every time.", "Shows less.", "this phone")) assertFalse(phoneOnly, s.has(phoneOnly))
                s.png("build/screen-round1-highrisk.png")
                s.tap("Yes, switch it on"); s.await("high-risk mode on") { HighRisk.on(c) && a.state.highRisk && s.says("On") }
                s.tap("High-risk mode"); assertTrue(s.has("High-risk mode is on") && s.has("This computer keeps less. It can't lock DarkProof yet."))
                s.tap("Switch it off"); s.await("high-risk mode off") { !HighRisk.on(c) && s.says("Off") }
                // The page is longer than the panel: its last lines come with the wheel
                s.wheel("Advanced", 30); assertTrue(s.says("This computer") && s.says("Leave Temp") && s.says("Erase everything"))
                s.png("build/screen-round1-settings.png")
                // Erase everything: a page of its own, which this test only looks at
                s.tap("Erase everything"); assertTrue(s.says("Hold to erase") && s.says("Cancel") && s.has("Messages, groups, keys and the AI model leave this computer. This can't be undone."))
                assertTrue("the switch that tells the groups first", s.says("Tell my groups first") && s.has("Asks their admins' phones to remove this computer and change the key"))
                s.png("build/screen-round1-erase.png"); s.tap("Cancel"); assertFalse(s.says("Hold to erase"))

                // Leave: asks, then this computer forgets the group, its keys and what it stored of it, and shows the next group
                assertTrue(c.getSharedPreferences("vault", 0).all.keys.any { groupId in it })
                // Ben is in Temp and this computer is its only admin: it asks who runs the group first (spec "Leaving the group", LW2).
                s.tap("Leave Temp"); assertTrue(s.texts().toString(), s.has("Who runs Temp after you?") && s.says("Leave without choosing"))
                s.tap("Leave without choosing")
                assertTrue(s.texts().toString(), s.has("Leave Temp?") && s.has("This computer forgets the group, its messages and its keys. A note asks the group's admins to remove you and change the key."))
                s.tap("Stay"); assertEquals("Temp", Groups.current(c)!!.name)
                // Leaving sends a note and waits at most 15 s for a link to take it; "Leave now" ends that.
                s.tap("Leave Temp"); s.tap("Leave without choosing"); s.tap("Leave Temp")
                s.await("the note's wait, or the group left") { s.has("Telling Temp…") || s.has("You left Temp") }
                if (s.says("Leave now")) { assertTrue(s.has("Waiting for a phone or relay to take the note.")); s.png("build/screen-round1-leave-wait.png"); s.tap("Leave now") }
                s.await("the first group in use again") { Groups.current(c)?.code == mine.code && MeshEngine.currentCode == mine.code && a.state.group?.code == mine.code }
                s.await("the note") { s.has("You left Temp") }
                assertTrue(Groups.all(c).none { it.code == code })
                assertTrue("nothing under the group's id is left in the vault", c.getSharedPreferences("vault", 0).all.keys.none { groupId in it })
                assertTrue("nor in the preferences", c.getSharedPreferences("mesh", 0).all.keys.none { groupId in it })
                assertTrue(MeshEngine.otherMembers(code).isEmpty() && MeshEngine.snapshotAll().none { it.group == code })
            } finally {
                HighRisk.set(c, false); a.state.highRisk = false; a.state.checkEnded = null
                if (Groups.current(c)?.code != mine.code) a.switchTo(mine)
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null
            }
        }
    }
}
