package it.blackout.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The group in use and the ones the user switched away from, so they can come back (like the iPhone's SavedGroups).
 * [pins]: only on an invite that was read, the admins it names (spec "Invites"); kept when the user joins with it.
 */
data class SavedGroup(val name: String, val code: String, val pins: String = "")

object Groups {
    private fun prefs(c: Context) = c.getSharedPreferences("mesh", 0)

    /** The group in use, the others, the codes of groups made here that have no first epoch yet, and the admins invites named, by code. */
    private data class Saved(val code: String? = null, val name: String? = null, val others: List<SavedGroup> = emptyList(), val founded: Set<String> = emptySet(),
                             val pins: Map<String, String> = emptyMap())
    private var cache: Saved? = null

    private fun groupsFrom(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map { SavedGroup(it.optString("name", "Group"), it.optString("code")) }

    /**
     * Group codes are kept in the [Vault], encrypted with a key that never leaves the Keystore: a code is what lets
     * someone ask to join. Read once, then from memory. Codes saved in plain preferences by older builds move here.
     * Saved codes the vault can't open right now (the Keystore failed for a moment) are not kept in memory as "no group":
     * the next call reads them again, so the alarm armed again after such a moment (re-check N3) and the screens find
     * them once the Keystore answers. Nothing is moved meanwhile.
     */
    @Synchronized private fun saved(c: Context): Saved {
        cache?.let { return it }
        val p = prefs(c)
        val vaulted = Vault.get(c, "groups")?.let { raw -> runCatching { JSONObject(String(raw)).let { o ->
            Saved(o.optString("code").ifEmpty { null }, o.optString("name").ifEmpty { null }, groupsFrom(o.getJSONArray("others")),
                o.getJSONArray("founded").let { f -> (0 until f.length()).map(f::getString).toSet() },
                o.optJSONObject("pins")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty())
        } }.getOrNull() }
        val s = vaulted ?: Saved(p.getString("code", null), p.getString("groupName", null),
            runCatching { groupsFrom(JSONArray(p.getString("groups", "[]"))) }.getOrDefault(emptyList()), p.getStringSet("found", emptySet())!!.toSet())
        if (vaulted == null && c.getSharedPreferences("vault", 0).contains("groups")) return s
        cache = s
        if (p.contains("code") || p.contains("groups") || p.contains("found")) {
            write(c, s)
            if (Vault.get(c, "groups") != null) p.edit().remove("code").remove("groupName").remove("groups").remove("found").apply()
        }
        return s
    }

    @Synchronized private fun write(c: Context, s: Saved) {
        if (s.code != cache?.code) DebugTools.noteCode(s.code)
        cache = s
        Vault.put(c, "groups", JSONObject().put("code", s.code ?: "").put("name", s.name ?: "")
            .put("others", JSONArray(s.others.map { JSONObject().put("name", it.name).put("code", it.code) }))
            .put("founded", JSONArray(s.founded.toList())).put("pins", JSONObject(s.pins)).toString().toByteArray())
    }

    /** The code of the group in use, or null before setup. */
    fun code(c: Context) = saved(c).code
    fun current(c: Context) = saved(c).let { s -> s.code?.let { SavedGroup(s.name ?: "Group", it) } }
    fun others(c: Context): List<SavedGroup> = saved(c).let { s -> s.others.filter { it.code.isNotEmpty() && it.code != s.code } }
    @Synchronized private fun remember(c: Context, g: SavedGroup) = write(c, saved(c).copy(others = (listOf(g) + others(c).filter { it.code != g.code }).take(20)))
    /** The group in use, then the others. */
    fun all(c: Context): List<SavedGroup> = listOfNotNull(current(c)) + others(c)
    fun find(c: Context, code: String?): SavedGroup? = code?.let { k -> all(c).firstOrNull { it.code == k } }
    /** First setup: saves the name and group. The mesh service starts afterwards. */
    @Synchronized fun setUp(c: Context, name: String, g: SavedGroup) {
        prefs(c).edit().putString("name", name).apply()
        joining(c, g)
        write(c, saved(c).copy(code = g.code, name = g.name))
    }
    /** Someone was removed from a group: it now has a new code (spec "Removing and the code"). Its name stays. */
    @Synchronized fun replaceCode(c: Context, old: String, new: String) = saved(c).let { s ->
        write(c, s.copy(code = if (s.code == old) new else s.code, others = s.others.map { if (it.code == old) it.copy(code = new) else it }))
    }
    /** [old] turned out to be an old code of the saved group whose code is [now]: that group is shown in its place, and [old] isn't kept. */
    @Synchronized fun follow(c: Context, old: String, now: String) = saved(c).let { s ->
        val g = find(c, now) ?: return@let
        write(c, s.copy(code = if (s.code == old) g.code else s.code, name = if (s.code == old) g.name else s.name, others = s.others.filter { it.code != old }))
    }
    /** Shows another group's messages; this group's stay on the phone. */
    @Synchronized fun switchTo(c: Context, g: SavedGroup) {
        joining(c, g)
        current(c)?.let { remember(c, it) }
        write(c, saved(c).copy(code = g.code, name = g.name))
        MeshEngine.switchGroup(g.code)
    }

    /**
     * "Leave": forgets the saved group [code], with the admins its invite named. If it was the one in use, the other
     * group used last takes its place. Returns the group now in use, or null if none is left (the name stays).
     */
    @Synchronized fun forget(c: Context, code: String): SavedGroup? = saved(c).let { s ->
        val rest = s.others.filter { it.code.isNotEmpty() && it.code != code && it.code != s.code }
        val next = if (s.code == code) rest.firstOrNull() else current(c)
        write(c, s.copy(code = next?.code, name = next?.name, others = rest.filter { it.code != next?.code }, founded = s.founded - code, pins = s.pins - code))
        next
    }

    /** Groups made on this phone, or saved before v0.6, that have no first epoch yet (spec "Making the first epoch"). */
    fun founded(c: Context) = saved(c).founded
    @Synchronized fun setFounded(c: Context, codes: Set<String>) = write(c, saved(c).copy(founded = codes))

    /** The admins the invite for [code] named ("" if none): only they can let this phone in (spec "Invites"). */
    fun pins(c: Context, code: String) = saved(c).pins[code].orEmpty()
    /**
     * The user chose to join [g], or to show it: if this phone doesn't have its code yet, the admins its invite names
     * are kept from now on ([Invite.joinPins]). Nothing else writes them. Call before [g] is saved.
     */
    @Synchronized private fun joining(c: Context, g: SavedGroup) = saved(c).let { s ->
        val pins = Invite.joinPins(s.pins, all(c).map { it.code }, g.code, g.pins)
        if (pins != s.pins) write(c, s.copy(pins = pins))
    }

    /**
     * The group name and code in invite [link] and the admins it names, or null if it isn't one. Reading a link keeps
     * nothing: the admins are saved only when the user joins with it ([switchTo], [setUp]).
     */
    fun readInvite(link: String): SavedGroup? = Invite.parse(link)?.let { (name, code) -> SavedGroup(name, code, Invite.pins(link)) }
}

/** When each saved group's chat was last read, so new messages show as unread (Chat tab, group switcher). */
object ReadMarks {
    private fun prefs(c: Context) = c.getSharedPreferences("read", 0)
    fun lastRead(c: Context, code: String): Long = prefs(c).getLong(key(code), 0L)
    fun markRead(c: Context, code: String, ts: Long) { if (ts > lastRead(c, code)) prefs(c).edit().putLong(key(code), ts).apply() }
    /** Messages from others in [code] newer than the last read one. */
    fun unread(c: Context, all: List<Message>, code: String): Int {
        val since = lastRead(c, code)
        return all.count { !it.public && it.group == code && it.ts > since && it.sender != MeshEngine.deviceId }
    }
    fun rename(c: Context, old: String, new: String) { prefs(c).edit().putLong(key(new), lastRead(c, old)).remove(key(old)).apply() }
    /** The group [code] was left: its mark goes too. */
    fun forget(c: Context, code: String) { prefs(c).edit().remove(key(code)).apply() }
    private fun key(code: String) = code.hashCode().toString() // not the code itself
}
