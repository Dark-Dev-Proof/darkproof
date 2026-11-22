package it.blackout.mesh

import android.content.Context
import org.json.JSONObject

/**
 * People the user blocked and messages the user reported or hid: both are hidden everywhere (chats, places, people,
 * alerts). Kept only on this phone. Reports also go to the internet relays (NostrLink) when there is internet.
 * A block holds for the channel it was made in, Everyone or one group, and only a checked message's sender can be
 * blocked ([Blocks]): an unchecked message can be hidden, not its sender.
 */
object Moderation {
    private fun prefs(c: Context) = c.getSharedPreferences("mesh", 0)
    @Volatile private var blockedCache: Map<String, String>? = null
    @Volatile private var hiddenCache: Set<String>? = null

    /** What is blocked ([Blocks.key]: the channel and the sender id) and the name that sender had. */
    fun blocked(c: Context): Map<String, String> = blockedCache ?: runCatching {
        val o = JSONObject(prefs(c).getString("blocked", "{}")!!); o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap()).also { blockedCache = it }

    /** Blocks the sender of [m] in the channel [m] came in. False, and nothing saved, if that sender can't be blocked ([Blocks.canBlock]). */
    fun block(c: Context, m: Message): Boolean {
        val channel = channelOf(m)
        if (channel == null || !Blocks.canBlock(m, MeshEngine.deviceId)) return false
        saveBlocked(c, blocked(c) + (Blocks.key(channel, m.sender) to m.name))
        return true
    }
    /** [key]: a key of [blocked]. */
    fun unblock(c: Context, key: String) = saveBlocked(c, blocked(c) - key)
    /** The channel a message's sender is blocked in: Everyone, or its group's id (which stays when the group's code changes). */
    private fun channelOf(m: Message): String? = if (m.public) Blocks.EVERYONE else MeshEngine.groupId(m.group)
    private fun saveBlocked(c: Context, m: Map<String, String>) {
        blockedCache = m; prefs(c).edit().putString("blocked", JSONObject(m).toString()).apply()
    }

    /** The group whose id is [groupId] was left: who was blocked there is forgotten with it. */
    fun forgetGroup(c: Context, groupId: String) { val b = blocked(c); val rest = b.filterKeys { !it.startsWith("$groupId|") }; if (rest.size != b.size) saveBlocked(c, rest) }

    /** Ids of messages the user reported or hid. */
    fun hidden(c: Context): Set<String> = hiddenCache ?: (prefs(c).getStringSet("hidden", emptySet())!!.toSet()).also { hiddenCache = it }
    fun hide(c: Context, messageId: String) {
        val s = (hidden(c) + messageId).toList().takeLast(2_000).toSet()
        hiddenCache = s; prefs(c).edit().putStringSet("hidden", s).apply()
    }

    /** Messages that are gone from this phone with their group: their ids leave the hidden list too. */
    fun forgetMessages(c: Context, ids: Collection<String>) {
        val h = hidden(c); val rest = h - ids.toSet()
        if (rest.size != h.size) { hiddenCache = rest; prefs(c).edit().putStringSet("hidden", rest).apply() }
    }

    fun visible(c: Context, m: Message): Boolean {
        if (m.id in hidden(c)) return false
        val b = blocked(c)
        return b.isEmpty() || !Blocks.hides(b.keys, channelOf(m), m.sender)
    }
}
