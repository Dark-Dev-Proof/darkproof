package it.blackout.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** High-risk mode's "keeps less" (the 24-hour rule and its gone list), and how a person's check is kept. */
class HighRiskTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    // The 24 hours count from when a packet reached this phone, never from the date written in it.

    @Test fun whatArrivedGoesOnlyOnceItHasBeenHereMoreThan24Hours() {
        assertFalse(KeepLess.due(now, now))
        assertFalse(KeepLess.due(now - 23 * hour, now))
        assertFalse(KeepLess.due(now - 24 * hour, now))          // exactly a day: not over yet
        assertTrue(KeepLess.due(now - 24 * hour - 1, now))
        assertTrue(KeepLess.due(now - 8 * 24 * hour, now))
    }

    @Test fun aMessageThatArrivesLateIsKeptADay() {
        // Written five days ago, carried by other phones, here for an hour: it stays, and for 24 hours from its arrival.
        val late = KeepLess.Held(at = now - hour, ts = now - 5 * 24 * hour, group = "g")
        val held = mapOf("late" to late, "old" to KeepLess.Held(now - 25 * hour, now - 25 * hour, "g"))
        assertEquals(listOf("old"), KeepLess.due(held, now))
        assertEquals(emptyList<String>(), KeepLess.due(mapOf("late" to late), now + 22 * hour))
        assertEquals(listOf("late"), KeepLess.due(mapOf("late" to late), now + 23 * hour + 1))
    }

    @Test fun myOwnMessageThatHasNotLeftThisPhoneIsNeverRemoved() {
        // "I need help", written with nobody in reach: a day later, a month later, it is still here.
        val unsent = mapOf("help" to KeepLess.Held(KeepLess.UNSENT, now, "g"))
        for (later in listOf(hour, 25 * hour, 8 * 24 * hour, 30 * 24 * hour)) assertEquals(emptyList<String>(), KeepLess.due(unsent, now + later))
        assertFalse(KeepLess.due(KeepLess.UNSENT, now))
    }

    @Test fun myOwnMessageGoesADayAfterALinkTookIt() {
        // Written at [now], handed to a link ("Sent") three days later: the day starts at the hand-off.
        val handOff = now + 3 * 24 * hour
        val sent = mapOf("mine" to KeepLess.Held(handOff, now, "g"))
        assertEquals(emptyList<String>(), KeepLess.due(sent, handOff + 24 * hour))
        assertEquals(listOf("mine"), KeepLess.due(sent, handOff + 24 * hour + 1))
    }

    @Test fun aClockSetBackRemovesLessNeverMore() {
        val held = mapOf("a" to KeepLess.Held(now - 25 * hour, 0, "g"), "b" to KeepLess.Held(now - 72 * hour, 0, "g"), "c" to KeepLess.Held(now - 10 * 24 * hour, 0, "g"))
        val right = KeepLess.due(held, now).toSet()
        assertEquals(setOf("a", "b", "c"), right)
        // The clock jumps back by an hour, a day, a week: whatever is due then was due on the right clock too.
        for (back in listOf(hour, 24 * hour, 7 * 24 * hour)) assertTrue(right.containsAll(KeepLess.due(held, now - back)))
        assertEquals(setOf("b", "c"), KeepLess.due(held, now - 24 * hour).toSet())
        assertEquals(setOf("c"), KeepLess.due(held, now - 7 * 24 * hour).toSet())
        // A flat battery: the clock starts again in 2000. Every arrival lies ahead of it, and nothing is touched.
        assertEquals(emptyList<String>(), KeepLess.due(held, 946_684_800_000L))
        assertFalse(KeepLess.due(now + hour, now))               // an arrival ahead of the clock never counts
    }

    @Test fun aClockMovedForwardMakesMessagesLeaveEarly() {
        // Accepted, and said so in the code: it can't be told from time passing.
        assertTrue(KeepLess.due(now - hour, now + 2 * 24 * hour))
    }

    @Test fun whatWasStoredBeforeArrivalsWereNotedCountsFromItsOwnDate() {
        assertEquals(now - hour, KeepLess.arrival(now - hour, now - 5 * 24 * hour, ownUnsent = false))   // noted: that
        assertEquals(now - 5 * 24 * hour, KeepLess.arrival(0, now - 5 * 24 * hour, ownUnsent = false))  // not noted: its date
        assertEquals(KeepLess.UNSENT, KeepLess.arrival(0, now - 5 * 24 * hour, ownUnsent = true))       // mine, never taken by a link: stays
        assertEquals(KeepLess.UNSENT, KeepLess.arrival(KeepLess.UNSENT, now, ownUnsent = false))         // what the store says wins
    }

    @Test fun introductionsAndNewKeysStayEvenWithAMessagesMailbox() {
        // The rule's only input is the list of packets that opened as a message or a receipt. A group's introduction or
        // new key is never on it, so one that shares a message's 16-bit mailbox (the first four characters of a packet
        // id) is not selected, however old it is.
        val g = GroupCrypto.fromSecret(ByteArray(32) { 7 })
        val message = Packet.newId(g.mailbox); val door = Packet.newId(g.mailbox)
        assertEquals(Packet.mailbox(message), Packet.mailbox(door))
        val stored = listOf(message, door)                       // both a week old in the store
        val held = mapOf(message to KeepLess.Held(now - 7 * 24 * hour, now - 7 * 24 * hour, "g"))
        val due = KeepLess.due(held, now)
        assertEquals(listOf(message), due)
        assertEquals(listOf(door), stored - due.toSet())
    }

    @Test fun aDayIs24Hours() = assertEquals(24 * hour, KeepLess.KEEP_MS)

    // The gone list: what the rule removed, so that a copy another phone still carries is not read again.

    private val week = 7 * 24 * hour
    private fun entry(key: String, ts: Long, group: String = "g") = GoneList.Entry(key, ts, group)

    @Test fun aRemovedPacketThatComesBackIsNotReadAgainAndIsStillCarried() {
        val gone = GoneList(); gone.add(listOf(entry("id1:aa", now - 30 * hour)))
        // Not on the list: read like any packet. [opens] isn't even asked.
        assertEquals(GoneList.Arrival.READ, gone.arrival("id2:bb") { throw AssertionError("asked for a packet that isn't listed") })
        // On the list, and nothing here opens it any more (its own key was deleted when it was read): carried in the store.
        assertEquals(GoneList.Arrival.CARRY, gone.arrival("id1:aa") { false })
        // On the list, and a held key would still open it (a receipt): passed on, not written back to the disk.
        assertEquals(GoneList.Arrival.PASS_ON, gone.arrival("id1:aa") { true })
        // The same id with other content is another packet: it is not on the list.
        assertEquals(GoneList.Arrival.READ, gone.arrival("id1:cc") { true })
        // In no case is it read: READ is the only answer that opens, shows, alerts or confirms.
        assertTrue(GoneList.Arrival.values().filter { it != GoneList.Arrival.READ }.containsAll(listOf(gone.arrival("id1:aa") { false }, gone.arrival("id1:aa") { true })))
    }

    @Test fun theGoneListSurvivesARestart() {
        val before = GoneList(); before.add(listOf(entry("a:1", now - hour, "g1"), entry("b:2", now - 2 * hour, "g2")))
        // What is saved is each name with its header time and group id, and nothing else; a new list is filled from it.
        val saved = before.entries().map { Triple(it.key, it.ts, it.group) }
        val after = GoneList(); after.add(saved.map { GoneList.Entry(it.first, it.second, it.third) })
        assertTrue("a:1" in after && "b:2" in after); assertEquals(2, after.size)
        assertEquals(GoneList.Arrival.CARRY, after.arrival("a:1") { false })
        assertEquals(listOf("b:2", "a:1"), after.entries().map { it.key })    // oldest packet first
    }

    @Test fun theGoneListIsCappedOldestFirst() {
        val gone = GoneList(cap = 3)
        assertEquals(emptyList<String>(), gone.add(listOf(entry("a", now - 4 * hour), entry("b", now - 3 * hour), entry("c", now - 2 * hour))))
        assertEquals(listOf("a"), gone.add(listOf(entry("d", now - hour))))               // the oldest packet's name goes
        assertEquals(setOf("b", "c", "d"), gone.keys())
        assertEquals(listOf("b", "c"), gone.add(listOf(entry("e", now), entry("f", now))))
        assertEquals(3, gone.size)
        assertEquals(listOf("z"), gone.add(listOf(entry("z", now - 6 * 24 * hour))))      // older than all that is kept: it goes itself
        assertEquals(5_000, GoneList.CAP)
        val big = GoneList(); assertEquals(emptyList<String>(), big.add((1..5_000).map { entry("k$it", now - it) }))
        assertEquals(listOf("k5001"), big.add(listOf(entry("k5001", now - 5_001))))
        assertEquals(5_000, big.size)
    }

    @Test fun aNameGoesWhenItsPacketIsOlderThanSevenDays() {
        val gone = GoneList(); gone.add(listOf(entry("young", now - 6 * 24 * hour), entry("edge", now - week), entry("old", now - week - 1)))
        assertEquals(listOf("old"), gone.expire(now))            // no phone takes a packet that old any more
        assertTrue("young" in gone && "edge" in gone); assertFalse("old" in gone)
        assertEquals(listOf("edge"), gone.expire(now + 1))
        assertEquals(emptyList<String>(), gone.expire(now + 1))
        assertEquals(week, GoneList.AGE_MS)
    }

    @Test fun leavingAGroupTakesItsNamesOffTheGoneList() {
        val gone = GoneList(); gone.add(listOf(entry("a", now, "g1"), entry("b", now, "g2"), entry("c", now, "g1")))
        assertEquals(setOf("a", "c"), gone.forgetGroup("g1").toSet())
        assertEquals(setOf("b"), gone.keys())
        assertEquals(emptyList<String>(), gone.forgetGroup("g1"))
    }

    // How a person was checked, next to the checked mark.

    private val g = "group"
    private fun marked(checked: Set<String>, inPerson: Set<String>, id: String, name: String, how: CheckedHow): Pair<Set<String>, Set<String>> {
        val c = Checked.marked(checked, g, id, name); return c to Checked.inPerson(inPerson, c, g, id, how)
    }

    @Test fun marksFromBeforeCountAsEmoji() {
        val old = setOf(Checked.entry(g, "aa", "Ben"))
        assertEquals(CheckedHow.EMOJI, Checked.how(old, emptySet(), g, "aa"))
        assertNull(Checked.how(old, emptySet(), g, "bb"))
        assertNull(Checked.how(old, emptySet(), "other", "aa"))
    }

    @Test fun theLastCheckDecides() {
        var (c, p) = marked(emptySet(), emptySet(), "aa", "Ben", CheckedHow.IN_PERSON)
        assertEquals(CheckedHow.IN_PERSON, Checked.how(c, p, g, "aa"))
        marked(c, p, "aa", "Ben", CheckedHow.EMOJI).let { c = it.first; p = it.second }
        assertEquals(CheckedHow.EMOJI, Checked.how(c, p, g, "aa"))   // in person, then emoji: "checked by emoji"
        marked(c, p, "aa", "Ben", CheckedHow.IN_PERSON).let { c = it.first; p = it.second }
        assertEquals(CheckedHow.IN_PERSON, Checked.how(c, p, g, "aa"))
        assertEquals(1, c.size); assertEquals(1, p.size)
    }

    @Test fun aNewKeyForTheNameClearsHowTheOldOneWasChecked() {
        val (c, p) = marked(emptySet(), emptySet(), "aa", "Ben", CheckedHow.IN_PERSON)
        assertTrue(Checked.newKey(c, g, "bb", "Ben"))                // Ben writes with another key: a warning, and bb is not checked
        assertNull(Checked.how(c, p, g, "bb"))
        val (c2, p2) = marked(c, p, "bb", "ben ", CheckedHow.EMOJI)  // the new key is checked by emoji
        assertEquals(CheckedHow.EMOJI, Checked.how(c2, p2, g, "bb"))
        assertNull(Checked.how(c2, p2, g, "aa"))                     // the old key's mark went, and so did "in person"
        assertTrue(p2.isEmpty())
    }

    @Test fun oneCheckLeavesTheOthersAlone() {
        val (c, p) = marked(emptySet(), emptySet(), "aa", "Ben", CheckedHow.IN_PERSON)
        val (c2, p2) = marked(c, p, "cc", "Luca", CheckedHow.EMOJI)
        assertEquals(CheckedHow.IN_PERSON, Checked.how(c2, p2, g, "aa"))
        assertEquals(CheckedHow.EMOJI, Checked.how(c2, p2, g, "cc"))
    }

    @Test fun theyDoNotMatchRemovesAnEarlierMark() {
        val (c, p) = marked(emptySet(), emptySet(), "aa", "Ben", CheckedHow.IN_PERSON)
        val (c2, p2) = marked(c, p, "cc", "Luca", CheckedHow.EMOJI)
        val c3 = Checked.unmarked(c2, g, "aa"); val p3 = Checked.unmarked(p2, g, "aa")
        assertNull(Checked.how(c3, p3, g, "aa"))
        assertEquals(CheckedHow.EMOJI, Checked.how(c3, p3, g, "cc")) // nobody else's
        assertEquals(c2, Checked.unmarked(c2, g, "zz"))              // someone who wasn't checked: nothing changes
    }
}
