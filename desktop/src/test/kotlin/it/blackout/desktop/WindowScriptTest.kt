package it.blackout.desktop

import it.blackout.mesh.Groups
import it.blackout.mesh.Invite
import it.blackout.mesh.MeshEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.input.key.Key
import org.junit.Test

/**
 * The desktop app used the way a person uses it, by a script: it reads what the window says, taps what is written
 * there and types into its fields ([Screen]), and checks both what the window then says and what the engine underneath
 * did. The first setup ("Create a group") is scripted too, in [RealEngine]. Pictures of the window along the way are
 * left in build/screen-*.png for a look.
 */
class WindowScriptTest {
    @Test fun aPersonCanUseTheApp() {
        RealEngine.started(); val a = RealEngine.activity; val c = a // the app's one Context
        val peer = EveryonePeer("Anna", loopback = true)
        Screen { App(a) }.use { s ->
            try {
                // The window after the scripted setup: the group's rail and sidebar, its chat, and the people at the side
                a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; s.settle()
                assertTrue(s.has("My group") && s.has("Looking for phones") && s.has("Channels") && s.has("This computer")); assertEquals("My group", Groups.current(c)!!.name)
                assertTrue("the people pane is at the side", s.has("I'm safe") && s.has("I need help") && s.has("No one from My group yet"))
                for (phoneOnly in listOf("Home", "this phone", "Searching", "Bluetooth")) assertFalse(phoneOnly, s.has(phoneOnly))

                // The invite panel: a QR code, a link to share, and the emoji to compare
                s.tap("Invite"); assertTrue(s.has("Invite to My group") && s.has("Invite QR code") && s.has("Share invite link"))
                s.wheel("Check together", 8); assertTrue("the panel scrolls with the mouse wheel", s.has("Show code")); s.wheel("Check together", -8)
                assertFalse(s.has("Share the app")) // only an Android phone can send the Android app
                // no picture of this panel: its QR code is the group's code
                s.tap("Share invite link") // a phone opens its share sheet; a computer copies it
                s.await("the note that the link was copied") { s.has("Copied. Paste it where you want to send it.") }
                s.tap("Close")

                // A message to the group
                assertTrue(s.has("Only people in My group can read these"))
                s.type("Message My group", "Hello from the window"); s.tap("Send")
                s.await("the group message in the engine") { MeshEngine.snapshot().any { it.text == "Hello from the window" && !it.public && it.sender == MeshEngine.deviceId } }
                s.await("the group message in the window") { s.has("Hello from the window") }
                // …and one sent the way a keyboard does it: Enter sends, Shift+Enter does not
                s.type("Message My group", "Sent with Enter"); s.press(Key.Enter, shift = true)
                assertEquals(listOf("Sent with Enter\n"), a.state.drafts.values.toList()); assertFalse(MeshEngine.snapshot().any { it.text.startsWith("Sent with Enter") })
                s.press(Key.Enter)
                s.await("the message sent with Enter") { MeshEngine.snapshot().any { it.text == "Sent with Enter" && it.sender == MeshEngine.deviceId } }
                assertTrue(a.state.drafts.isEmpty())
                s.png("build/screen-chat-group.png")

                // A message to Everyone, chosen at the side
                s.tap("Everyone"); assertTrue(s.has("Anyone with DarkProof can read these"))
                s.type("Message everyone", "Hello everyone, from the window"); s.tap("Send")
                s.await("the Everyone message in the engine") { MeshEngine.snapshotAll().any { it.text == "Hello everyone, from the window" && it.public } }

                // Connections, from the status card: link to another device by typing its address
                s.tap("Looking for phones"); assertTrue(s.has("Connections") && s.says("Wi-Fi network") && s.has("Send through another app"))
                assertTrue(s.has("None linked")); for (phoneOnly in listOf("Searching", "Long-range radio", "Battery saver", "Wi-Fi board")) assertFalse(phoneOnly, s.says(phoneOnly))
                // The internet relays are there and off: the test never switches them on, so nothing of it reaches a real relay
                assertTrue(s.says("Internet") && s.has("Off: messages go only from phone to phone") && s.has("All but Internet keep working in a blackout."))
                assertFalse("Tor's own row shows once the internet is on", s.says("Through Tor")); assertTrue(MeshEngine.nostr == null)
                s.png("build/screen-connections.png")
                s.tap("Nearby phones"); assertTrue(s.has("Same Wi-Fi network") && s.has("Looking on this network") && s.has("This computer: "))
                assertFalse(s.has("This phone"))
                s.type("192.168.1.20:7777", "not an address"); s.tap("Connect")
                s.await("the note about a wrong address") { s.has("Type it as address:port") }
                s.type("not an address", "127.0.0.1:${peer.start(0)}"); s.tap("Connect")
                s.await("the link") { peer.wifi.linkCount() == 1 && MeshEngine.linkedNodes().size == 1 }
                s.await("the panel to say so") { s.has("1 phone linked over Wi-Fi") }
                s.tap("Close"); s.await("the status card to count it") { s.has("Connected") }

                // What the other device posts shows up, signed; what was posted here before the link reached it
                peer.send("Is anyone near the station?")
                s.await("the other device's post in the window") { s.has("Is anyone near the station?") && s.has("Anna") }
                RealEngine.await("this computer's earlier post at the other device") { peer.lines().any { it.text == "Hello everyone, from the window" && it.name == "Desktop" && it.verified } }
                s.png("build/screen-chat-everyone.png")

                // "I'm safe", in the people pane that is always there
                s.tap("I'm safe")
                s.await("the check-in in the window") { s.has("Your last check-in: safe, just now.") }
                assertTrue(MeshEngine.snapshot().count { !it.public && it.sender == MeshEngine.deviceId } >= 2) // the message and the check-in

                // The other pages, from the side
                s.tap("Places"); assertTrue(s.has("Share a place") && s.has("Nothing shared yet")); assertFalse(s.says("Map")); s.png("build/screen-places.png")
                s.tap("Ask"); assertTrue(s.has("No AI model on this computer") && s.says("Qwen 3.5 4B uncensored")) // the page itself: AskScriptTest

                // Settings: only what a computer can do, and a new name
                s.tap("Settings"); assertTrue(s.has("Your name") && s.has("Groups") && s.has("Help and privacy"))
                for (phoneOnly in listOf("Notifications", "Lock DarkProof", "Share the app", "Share my location", "this phone")) assertFalse(phoneOnly, s.has(phoneOnly))
                s.png("build/screen-settings.png")
                s.tap("Your name"); s.type("Desktop", "Dana"); s.tap("Save")
                s.await("the new name in the engine") { MeshEngine.name == "Dana" }
                assertEquals("Dana", c.getSharedPreferences("mesh", 0).getString("name", null))
                s.tap("Your name"); s.type("Dana", "Desktop"); s.tap("Save"); s.await("the old name back") { MeshEngine.name == "Desktop" }
                s.tap("Close")

                // Another group, joined with a code someone told us: it is saved and shown, and waits to be let in
                val mine = Groups.current(c)!!; val code = Invite.newCode()
                s.tap("Join or start another group"); s.tap("Join a group")
                assertTrue(s.has("Type the code your friend tells you, or paste the invite link they send.")); assertFalse(s.has("Scan"))
                s.typeInto(0, code); s.typeInto(1, "Neighbours"); s.tap("Join")
                s.await("the new group in use") { Groups.current(c)?.code == code && MeshEngine.currentCode == code }
                assertEquals("Neighbours", Groups.current(c)!!.name)
                s.await("the engine to be asking to join") { MeshEngine.joining(code) } // it says so a moment after the group is in use
                // The engine takes the join on its own thread and the window follows it: wait for the window, as a person would
                s.secrets += code
                s.await("the new group's chat, asking to join, with the code gone from the screen") { s.has("Neighbours") && s.has("Asking to join Neighbours") && !s.has(code) }
                s.png("build/screen-joining.png")
                // …and back to the first group, from the rail
                s.tap("Switch to My group")
                s.await("the first group in use again") { Groups.current(c)?.code == mine.code && MeshEngine.currentCode == mine.code }
            } finally { a.state.tab = 1; a.state.everyone = false; a.state.sheet = null; MeshEngine.wifi?.links?.stopManual(); peer.stop() }
        }
    }
}
