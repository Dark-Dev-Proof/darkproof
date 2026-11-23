package it.blackout.mesh

import org.junit.Assert.*
import org.junit.Test

/** Which chat rows come in moving ([Arrivals]): only rows that came while the chat was open, each once, none in calm mode. */
class ArrivalsTest {
    @Test fun whatWasThereSimplyShows() {
        val a = Arrivals(listOf("day-1", "m1", "m2"))
        assertFalse(a.take("m1")); assertFalse(a.take("day-1"))
    }

    @Test fun aNewRowMovesOnce() {
        val a = Arrivals(listOf("m1"))
        assertTrue(a.take("m2"))
        assertFalse(a.take("m2")) // scrolled back into view: it simply shows
    }

    @Test fun nothingMovesInCalmMode() {
        val was = Motion.calm
        try {
            Motion.calm = true
            val a = Arrivals(listOf("m1"))
            assertFalse(a.take("m2"))
        } finally { Motion.calm = was }
    }
}
