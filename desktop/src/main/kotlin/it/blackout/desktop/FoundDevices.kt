package it.blackout.desktop

/**
 * The devices a finder has heard of, and which of them to connect to now (spec/FORMAT.md, "Wi-Fi networks").
 *
 * A device on two networks answers from two addresses. It is kept at one of them, so that it gets one link and not a
 * new one with every answer. Only a link to the address kept here counts as linked: a link from elsewhere may only
 * claim that device's node id (spec, "Node ids prove nothing"). The kept address changes when the device isn't linked
 * there and answers from another one.
 *
 * Not thread-safe: the caller uses it from one thread.
 */
class FoundDevices(private val me: String, private val max: Int, private val linkedAt: (node: String, host: String) -> Boolean) {
    companion object { const val DIAL_MS = 5_000L } // how long a connection may take to be made (WifiLinks.dial)

    private val at = HashMap<String, Pair<String, Int>>()
    private val dialed = HashMap<String, Long>()

    /** Only the device with the smaller node id connects, and not to itself. */
    fun wanted(node: String) = node != me && me < node

    /** [node] answered from [host]. Returns the address to connect to now, or null. */
    fun heard(node: String, host: String, port: Int, now: Long): Pair<String, Int>? {
        if (!wanted(node) || (at.size >= max && node !in at)) return null
        val kept = at[node]
        if (kept != null && kept.first != host) {
            if (linkedAt(node, kept.first)) return null                // linked where it is kept: its other address changes nothing
            if (now - (dialed[node] ?: 0L) < DIAL_MS) return null     // a connection to the kept address is still being made
        }
        at[node] = host to port
        return dial(node, now)
    }

    fun gone(node: String) { at.remove(node); dialed.remove(node) }

    /** The kept devices that have no link, in a new order each time (only so many are dialed at once). */
    fun unlinked(now: Long): List<Pair<String, Int>> = at.keys.shuffled().mapNotNull { dial(it, now) }

    fun clear() { at.clear(); dialed.clear() }

    private fun dial(node: String, now: Long): Pair<String, Int>? {
        val a = at[node] ?: return null
        if (linkedAt(node, a.first)) return null
        dialed[node] = now; return a
    }
}
