package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test

/** The names and labels of the map's pin pictures (OfflineMap.kt, PinArt): the map can ask for a picture again by its name alone. */
class MapPinsTest {
    @Test fun countBubblesShortenFromAThousand() {
        assertEquals("2", PinArt.count(2))
        assertEquals("999", PinArt.count(999))
        assertEquals("1.0k", PinArt.count(1_000))
        assertEquals("1.2k", PinArt.count(1_234))
        assertEquals("9.9k", PinArt.count(9_999))
        assertEquals("15k", PinArt.count(15_000))
    }

    @Test fun initialIsOneWholeCharacter() {
        assertEquals("A", PinArt.initial("  anna "))
        assertEquals("É", PinArt.initial("élan"))
        assertEquals("🙂", PinArt.initial("🙂 Sam")) // an emoji outside the basic plane is not cut in half
        assertEquals("?", PinArt.initial("   "))
    }

    @Test fun namesSayHowToDrawThePin() {
        val at = Spot(45.0, 9.0)
        assertEquals("dp-pin|42|#FFFFFF|", PinArt.nameOf(Pin("Water: at the gym", at, 1, "#FFFFFF", icon = 42)))
        assertEquals("dp-pin|0|#FF453B|B", PinArt.nameOf(Pin("bea", at, 1, "#FF453B", help = true)))
        // A name's letter goes last, so a "|" in it can't break the name apart.
        assertEquals("dp-pin|0|#FFFFFF||", PinArt.nameOf(Pin("|x", at, 1, "#FFFFFF")))
        // The same look gives the same picture: places of one kind share it.
        assertEquals(PinArt.nameOf(Pin("Water", at, 1, "#6B6B6B", icon = 7)), PinArt.nameOf(Pin("Water: tap", Spot(1.0, 2.0), 9, "#6B6B6B", icon = 7)))
        assertTrue(PinArt.nameOf(Pin("x", at, 1, "#FFFFFF")).startsWith(PinArt.PIN))
    }
}
