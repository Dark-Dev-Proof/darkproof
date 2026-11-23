package it.blackout.mesh

/**
 * The names of the map's pin pictures (drawn by OfflineMap.kt's PinPictures on a phone): each name says everything needed
 * to draw the picture again, so the map can ask for it again by its name alone. Plain Kotlin, so the desktop compiles and
 * tests the same rules (MapPinsTest), though it has no map yet.
 */
object PinArt {
    const val PIN = "dp-pin|"
    const val CLUSTER = "dp-cluster-"

    /** The picture name of [p]. */
    fun nameOf(p: Pin): String = PIN + (p.icon ?: 0) + "|" + p.color + "|" + (if (p.icon == null) initial(p.title) else "")

    /** The first letter of a name as one drawn character (an emoji or accented letter stays whole), "?" for none. */
    fun initial(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "?"
        val b = java.text.BreakIterator.getCharacterInstance().apply { setText(t) }
        val end = b.next().takeIf { it != java.text.BreakIterator.DONE } ?: t.length
        return t.substring(0, end).uppercase()
    }

    /** What a bubble of [n] pins says: the number, shortened from a thousand on. */
    fun count(n: Int): String = when {
        n < 1_000 -> "$n"
        n < 10_000 -> "${n / 1_000}.${n % 1_000 / 100}k"
        else -> "${n / 1_000}k"
    }
}
