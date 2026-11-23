package it.blackout.mesh

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Executors

/** The store's version ([Db]). */
private const val STORE_VERSION = 9

private class Db(c: Context) : SQLiteOpenHelper(c, "mesh.db", null, STORE_VERSION) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE p(id TEXT PRIMARY KEY, ts INTEGER, ttl INTEGER, cipher BLOB)")
        onUpgrade(d, 2, 3)
    }
    /**
     * 1 → 2 (protocol v0.5): older packets were sealed with v0.4 keys that no phone uses any more; don't pass them on.
     * 2 → 3: members' public keys learned from introductions (spec "Signatures"), per group id ([GroupCrypto.groupId]).
     * 5 → 6: the id of a row in p (and h) is the name its packet goes by ([StoreCap.name]): the packet id, or the packet's
     * key for another packet under an id that was taken. The tables stay as they are; an older app can't read such rows.
     * 6 → 7: when each packet reached this phone (p.at; 0 for those stored before, [KeepLess.arrival]), and the names of
     * the packets high-risk mode removed (g: [GoneList]).
     * 7 → 8: each group's check-in clocks (q: [QuietBook.saved], spec "If I go quiet", rule Q10), here beside the gone list
     * rather than in the preferences: the app's own storage, out of backups.
     * 8 → 9: those clocks sealed (q.box: [QuietSeal], decision C of the final review, 2026-11-23); q.data, where version 8
     * kept them readable, stays empty. A row saved readable before is sealed at the next start, and its readable copy
     * emptied ([MeshEngine.sealPlainClocks]).
     */
    override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) {
        if (a < 2) d.execSQL("DELETE FROM p")
        if (a < 3) d.execSQL("CREATE TABLE k(grp TEXT, id TEXT, pub BLOB, seen INTEGER, PRIMARY KEY(grp, id))")
        if (a < 4) d.execSQL("ALTER TABLE k ADD COLUMN enc BLOB") // 3 → 4: their encryption keys too (v0.6)
        if (a < 5) { // 4 → 5: when that key was introduced, so an older one can't replace it, and their ML-KEM key
            d.execSQL("ALTER TABLE k ADD COLUMN encAt INTEGER DEFAULT 0"); d.execSQL("ALTER TABLE k ADD COLUMN pq BLOB")
            d.execSQL("CREATE TABLE h(id TEXT PRIMARY KEY, data BLOB)") // readable copies of messages whose keys are deleted
        }
        if (a < 7) {
            d.execSQL("ALTER TABLE p ADD COLUMN at INTEGER DEFAULT 0")
            d.execSQL("CREATE TABLE g(key TEXT PRIMARY KEY, ts INTEGER, grp TEXT)") // a name, a header time and a group id: nothing of the packet's content
        }
        if (a < 8) d.execSQL("CREATE TABLE q(grp TEXT PRIMARY KEY, data TEXT)")
        if (a < 9) d.execSQL("ALTER TABLE q ADD COLUMN box BLOB")
    }

    /**
     * A store written by a newer DarkProof (this one is older, final review 2026-11-23): refused with words that say so,
     * never opened half understood. The engine's start catches it and says it ([MeshEngine.start]) instead of ending the app.
     */
    override fun onDowngrade(d: SQLiteDatabase, a: Int, b: Int) { throw StoreTooNew(a, b) }
}

/** The store was written by a newer DarkProof: version [had]; this one reads [reads]. */
class StoreTooNew(had: Int, reads: Int) : android.database.sqlite.SQLiteException(
    "The saved messages were written by a newer version of DarkProof (store $had, this version reads $reads). Install the newer version again.", null)

/**
 * Table q's rows (decision C of the final review, 2026-11-23): a group's check-in clocks as [QuietBook.saved] writes them
 * (member ids, times, positions, who confirmed), sealed with AES-256-GCM under the key of this phone's readable copies
 * ([History], in the vault), and bound to the group's id, so that a row can't be moved to another group. A phone whose
 * storage is read shows none of it without the vault's key; the alarm's receiver opens it with the app closed, since the
 * Keystore opens the vault then too. Same key as the copies, under its own label. Pure: tested.
 */
object QuietSeal {
    private const val IV = 12
    private fun aad(group: String) = "darkproof-quiet-v1|$group".toByteArray(Charsets.UTF_8)

    fun seal(key: ByteArray, group: String, lines: List<String>): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES")); c.updateAAD(aad(group))
        return c.iv + c.doFinal(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** The lines sealed for [group], or null if [blob] doesn't open with [key] for that group. */
    fun open(key: ByteArray, group: String, blob: ByteArray): List<String>? = runCatching {
        if (blob.size <= IV) return null
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, blob, 0, IV)); c.updateAAD(aad(group))
        String(c.doFinal(blob, IV, blob.size - IV), Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * What the backup alarm does when it rings with no engine running ([MeshEngine.quietAlarmRang]), from the clocks as table
 * q holds them ([books], by group id) at [now]: the alerts to post, what is still to come (armed again), and the groups
 * whose records changed. An alert whose group or clock is no longer on this phone (the group was left or the phone
 * erased; the clock was cancelled, replaced or alerted already) is neither posted nor armed again, so the alarm doesn't
 * carry its words any longer than that. At most one alert about a member every 10 minutes (rule Q12). Pure: tested.
 */
object QuietRang {
    class Result(val post: List<QuietAlert>, val left: List<QuietAlert>, val changed: Set<String>)

    fun handle(due: List<QuietAlert>, books: Map<String, QuietBook>, now: Long): Result {
        val post = ArrayList<QuietAlert>(); val left = ArrayList<QuietAlert>(); val changed = HashSet<String>()
        for (a in due.sortedBy { it.at }) {
            val (grp, book) = books.entries.firstOrNull { (g, b) -> if (a.remind) QuietAlerts.remindId(g) == a.id else b.clocks.values.any { QuietAlerts.id(g, it.member) == a.id } } ?: continue
            val cl = book.clocks.values.firstOrNull { it.mark == a.mark && !it.alerted && book.armed(it.member) } ?: continue // cancelled, replaced or posted already
            if (a.at > now) { left += a; continue }
            if (a.remind) { if (now < cl.deadline) { post += a; book.note(cl.member, cl.mark, alerted = true); changed += grp }; continue }
            if (QuietClock.due(cl.deadline, cl.learned, now) == QuietClock.Due.None) continue
            val at = book.postableAt(cl, false, now) ?: continue
            if (at > now) { left += a.copy(at = at); continue }
            post += a; book.didPost(cl, now); book.note(cl.member, cl.mark, alerted = true, scheduled = false); changed += grp
        }
        return Result(post, left, changed)
    }
}

/** Someone with the code asking to be let into a group; its admins decide (spec "Joining"). */
class JoinRequest(val id: String, val name: String, val pub: ByteArray)

/** Where one of my messages is: still on this phone, given to a phone in range or the relays, or confirmed. */
sealed class Delivery {
    object Waiting : Delivery()
    object Sent : Delivery()
    data class Delivered(val readers: List<Reader>) : Delivery()
}

/** State shared between the service and the UI. */
object MeshEngine {
    const val MAX_TTL = MeshRouter.MAX_TTL
    private const val WEEK = 7 * 24 * 3600_000L // how long phones pass messages on (MeshRouter)
    private const val MAX_PUBLIC = 2_000        // Public messages kept on the phone; older ones are removed
    private const val HANDED_OFF_MAX = 2_000    // "Sent" labels remembered across restarts
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var db: SQLiteDatabase? = null

    /** What a group's code opens (spec/DESIGN-v0.6-groups.md, "Keys"): introductions, new keys, and messages from phones not in yet. */
    private class Door(val code: String) {
        val crypto = GroupCrypto(code); val receipts = crypto.receipts(); val intros = crypto.intros(); val rekeys = crypto.rekeys()
        val id: String = crypto.groupId()
    }

    /**
     * A saved group's keys (spec/FORMAT.md, "Encryption", "Delivery receipts", "Introductions"), this phone's member
     * key there (in the Keystore), and the members' public keys this phone knows ([known], guarded by itself).
     */
    private class Keys(code: String, c: Context) {
        /** The door in use. It changes when someone is removed; the [oldDoors] stay a week, for removals that crossed. */
        @Volatile var door = Door(code)
        @Volatile var oldDoors: List<Door> = emptyList()
        val code get() = door.code
        val intros get() = door.intros
        val rekeys get() = door.rekeys
        /** Never changes for the group, even when its code does: member keys hang off it. */
        val id: String = Vault.get(c, "origin.${door.id}")?.let { String(it) } ?: door.id
        /** The group's random epoch secrets. Empty while this phone is joining. */
        val ring = Keyring()
        val joined get() = ring.currentOrNull() != null
        /** This phone was removed from the group: that removal's epoch number and its maker's rank, and [removedBy] its maker. */
        @Volatile var removedAt: Pair<Long, Int>? = null
        @Volatile var removedBy: String? = null
        /** A new key that lists this phone but gave it no part it can open: this phone asks its way back with a key of its own. */
        @Volatile var leftOut: LeftOut? = null
        val removed get() = removedAt != null
        /** Still joining or rejoining, and a removal came through this door: this invite no longer works. */
        @Volatile var closed = false
        /** This phone had been away past the weekly key and asked to come back (its new key is the request): since when, or 0. */
        @Volatile var rejoiningSince = 0L
        val rejoining get() = rejoiningSince > 0
        /** How long after the week is up this phone makes the new key. */
        val weeklyDelay = rnd.nextInt(Keyring.MAX_DELAY_MS.toInt()).toLong()
        /** Written with: the current epoch's keys, or the door's while joining. */
        val group get() = ring.currentOrNull()?.group ?: door.crypto
        val receipts get() = ring.currentOrNull()?.receipts ?: door.receipts
        val members: List<String> get() = ring.currentOrNull()?.members ?: emptyList()
        val pair = MemberKeys.forGroup(id)
        val pub: ByteArray = P256.compress(pair.public)
        val me = P256.memberId(pub)
        /** The HPKE keys other members seal new epoch secrets to: a new one every week. */
        val encs = EncKeys(c, id)
        val encPub: ByteArray get() = encs.currentPub()
        /** Member id -> their keys and name; the most recently used stay, so a flood of made-up members can't fill the memory. */
        val known = LinkedHashMap<String, Known>(16, 0.75f, true)
        fun sign(msg: ByteArray) = P256.sign(pair.private, msg)
        val isPublic get() = code == GroupCrypto.PUBLIC_CODE
        /**
         * The group's timer for disappearing messages as this phone last saw it, and since when ([Disappear.Watch]): a
         * value deletes nothing until it has been here ten minutes. Saved ("timer.<group id>"), so a restart doesn't cut
         * the wait short. Replaced whole under [watchLock].
         */
        @Volatile var watch = Disappear.Watch()
        val watchLock = Any()
        /**
         * Members who left with a note, and the removals that followed (spec "Leaving the group", [LeaveBook]). Saved
         * ("leaving.<group id>"), so leaving the group or erasing the phone takes it with the group's other settings.
         * Guarded by itself; read through [leavingOf], changed through [changeLeaving].
         */
        val leaveBook = LeaveBook()
        /**
         * The members' check-in clocks (spec "If I go quiet", [QuietBook]), this phone's own among them. Saved in the store's
         * table q under the group's id. Guarded by itself; read through [quietOf], changed through [changeQuiet].
         */
        val quietBook = QuietBook()
    }

    /** A key this phone couldn't open ([Keyring.Taken.LeftOut]): its number and rank, and how many keys this phone has made to get back in. */
    private class LeftOut(val number: Long, val rank: Int, val tries: Int)

    /** A member's public keys, from their introduction. */
    private class Known(val pub: ByteArray, val key: java.security.PublicKey, val enc: ByteArray?, val name: String, val encAt: Long = 0, val pq: ByteArray? = null)

    /** A packet opened with one of [Keys]: not shown before its signature is checked (spec "Signatures"). */
    private sealed class Seen {
        class Msg(val m: Message, val k: Keys, val o: GroupCrypto.Opened, val fromHistory: Boolean = false) : Seen()
        class Receipt(val r: Message, val k: Keys, val o: GroupCrypto.Opened, val name: String = "") : Seen()
        class Intro(val k: Keys, val id: String, val pub: ByteArray, val key: java.security.PublicKey, val enc: ByteArray, val name: String, val ts: Long, val pq: ByteArray?) : Seen()
        class NewKey(val k: Keys, val o: GroupCrypto.Opened, val r: Rekey, val packetId: String, val door: Door) : Seen()
        object Forged : Seen() // an introduction that doesn't hold up: like a packet nobody can open
    }

    /**
     * A message or receipt from a member whose public key isn't known yet; checked when the introduction comes.
     * [row]: for a message, the chat row it is shown as (its store name) so the rescan binds its check to that row and
     * not to the bare packet id, which another packet may share; null for a receipt (receipts have no row).
     */
    private class Pending(val k: Keys, val o: GroupCrypto.Opened, val receipt: Message?, val row: String? = null,
                          /** For a reaction (or a type 0x02 message that isn't one): the name its packet goes by and what it says, if anything. It has no row either. */
                          val reaction: Pair<String, Reactions.Entry?>? = null)
    /** What waits for a key, each group and the Public channel apart ([KeyWait]); guarded by the messages lock. */
    private val unchecked = KeyWait<Keys, Pending>()
    private class Waiting(val from: String, val text: String, val photo: ByteArray?, val voice: ByteArray? = null)
    private val waiting = HashMap<String, MutableList<Waiting>>() // group id -> what was written before an admin let this phone in
    private const val ACK_SPREAD_MS = 20_000
    private const val MAX_NAME_BYTES = 200                 // names are 40 characters at most in the app
    private const val MAX_KNOWN = 500                      // public keys kept per group
    private const val INTRO_EVERY = 3 * 24 * 3600_000L     // spec "Introductions"
    private val rnd = java.security.SecureRandom()
    private val rescanQueued = java.util.concurrent.atomic.AtomicBoolean() // new keys arriving together: one reading of the store
    private val keyCache = HashMap<String, Keys>() // code -> keys: each derivation takes 32 MiB of scrypt, so only once
    private var publicKeys: Keys? = null              // the Public channel's
    @Volatile private var active: Keys? = null     // the group on screen
    @Volatile private var others: List<Keys> = emptyList() // the other saved groups: read, alerted and confirmed too
    @Volatile private var allKeys = false          // every saved group's key has been tried on the whole store
    @Volatile private var activeCode = ""
    /**
     * The node id other phones see when they link (spec/FORMAT.md, "Link ids"): random, new at every app start,
     * unrelated to member ids, so a phone seen over the air can't be matched to a person or followed across restarts.
     */
    val linkId: String = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // This lock guards messages, messageIds, deliveries, index, the database writes and the key lists while they
    // change, so a packet that arrives during a group switch is neither lost nor shown twice.
    private val messages = ArrayList<Message>()      // every readable message: all saved groups and the Public channel
    private val messageIds = HashSet<String>()
    private val shownAs = HashMap<String, String>()  // what was signed ([GroupCrypto.Opened.mark]) -> the row that shows it
    private var deliveries = Deliveries()            // who confirmed which message
    private var reactions = Reactions()              // the members' checked reactions (spec "Reactions"): never rows
    private var index = HashMap<String, StoreCap.Entry>() // every stored packet, for the store cap
    private var indexBytes = 0L
    private var doorCount = 0; private var doorBytes = 0L // what the index holds of [StoreCap.Kind.DOOR]
    /** The stored packets that opened as a saved group's message or receipt, by store name: what high-risk mode's 24 hours count for ([KeepLess]). */
    private val kept = HashMap<String, KeepLess.Held>()
    /** What high-risk mode removed, so that a copy another phone brings is not read again ([GoneList]); saved in table g. */
    private val gone = GoneList()
    private val joinAlertAt = HashMap<String, Long>() // group id -> when this phone last alerted "asks to join" there
    @Volatile private var compacting = false
    private val ids = Any()                          // guards the sets below
    /**
     * Receipt and reaction packets this phone can open, kept off LoRa: each by its key ([MeshRouter.key]), not its id, so
     * that a receipt or reaction sent under the id of a message doesn't keep that message off the radio (spec "Reactions").
     */
    private val receiptIds = RecentIds(20_000)
    private val mine = HashSet<String>()             // ids of my own messages
    private val myReceipts = HashSet<String>()       // ids of receipts written on this phone (they go up to the relays first)
    private var handedOff = RecentIds(HANDED_OFF_MAX) // my messages already given to another phone, a radio or the relays
    private val handedOffOver = HashMap<String, String>() // of those, over which kind of link each first left and when ([Journey.Left.saved])
    @Volatile private var fromRelays = FromRelays()  // packets a relay brought first: they never go back up, after a restart either
    private val pendingAcks = ArrayList<Triple<String, Keys, Journey.Note?>>() // message id + its group's keys + how it arrived; main thread only
    private var firstAck = 0L; private var lastAck = 0L // when the first and the last pending ack came; main thread only
    @Volatile var router: MeshRouter? = null; private set
    /**
     * Set when everything is about to be erased ([stopping], [halt]) and never taken back: the app ends right after.
     * From then on nothing starts, links, sends, or writes a setting or a key, whatever is still tapped or running.
     */
    @Volatile var stopped = false; private set
    var sender: ((String, Packet) -> Unit)? = null
    var lora: LoraLink? = null
    var meshcore: MeshCoreLink? = null
    var ble: BleLink? = null
    /** Phones on the same Wi-Fi network; runs without the service, so it works even without Bluetooth permission. */
    @Volatile var wifi: WifiLink? = null; private set
    /** Public Nostr relays while there is internet; also bridges this phone's neighbourhood. */
    @Volatile var nostr: NostrLink? = null; private set
    /** Set by the service: node ids (Nearby endpoint names) of the phones linked over Nearby right now. */
    @Volatile var nearbyNodes: () -> Set<String> = { emptySet() }
    var listener: (() -> Unit)? = null
    /** A new message from someone else in any saved group, on the main thread (for notifications); see [Message.group]. */
    var onIncoming: ((Message) -> Unit)? = null
    /** Marks this phone's own messages on this phone only ([Message.sender]); never sent: packets carry member ids. */
    var deviceId = ""; private set
    var name = ""; private set
    var transportStatus = "Starting…"
    private var startedAt = 0L

    /**
     * Opens the store and the group's keys. Called by the screen and by the service; only the first call does anything.
     * The other saved groups' keys are derived afterwards in the background, then their messages are read too.
     */
    @Synchronized fun start(c: Context, name: String, code: String) {
        if (router != null || stopped) return
        if (leftColdBlocks(c, code)) return // asked to start in a group that was left a moment ago, before any engine ran: the screen moves on to the next one
        app = c.applicationContext
        val prefs = c.getSharedPreferences("mesh", 0)
        val upgrade = prefs.contains("device") // this phone ran DarkProof before (a device id exists from then)
        deviceId = prefs.getString("device", null) ?: UUID.randomUUID().toString().take(12).also { prefs.edit().putString("device", it).apply() }
        this.name = name
        startedAt = System.currentTimeMillis()
        synchronized(ids) {
            handedOff = RecentIds(HANDED_OFF_MAX, prefs.getString("handedOff", "")!!.split(',').filter { it.isNotEmpty() })
            handedOffOver.clear(); handedOffOver.putAll(parseOver(prefs.getString(OVER_PREF, "") ?: ""))
        }
        // Open already if a group was left before the engine started ([leave]). A store this version can't open (one a newer
        // DarkProof wrote, [StoreTooNew]) leaves the engine off and says why, instead of ending the app at every start.
        if (db == null) db = try { Db(c.applicationContext).writableDatabase } catch (e: android.database.sqlite.SQLiteException) {
            DebugTools.log("store: can't open: $e")
            transportStatus = (e as? StoreTooNew)?.message ?: "Can't open the saved messages"; notifyUi()
            return
        }
        loadGone()
        sealPlainClocks() // decision C: clocks a version before this one saved readable are sealed now, and their readable copy emptied
        if (!prefs.getBoolean("v06", false)) { // groups saved before v0.6 count as founded here (spec "Making the first epoch")
            val saved = if (upgrade) (listOfNotNull(Groups.current(c)) + Groups.others(c)).map { it.code }.toSet() else emptySet()
            Groups.setFounded(c, Groups.founded(c) + saved)
            prefs.edit().putBoolean("v06", true)
                .apply { prefs.all.keys.filter { it.startsWith("introAt.") }.forEach(::remove) }.apply() // introductions now carry the encryption key
        }
        publicKeys = keys(GroupCrypto.PUBLIC_CODE)
        val k = keys(code)
        activeCode = code; active = k
        val loaded = scan()
        synchronized(messages) { apply(loaded) }
        val relayed = FromRelays(prefs.getString(FromRelays.PREF, "") ?: "").also { fromRelays = it }
        if (stopped) return // the erase began while the keys were being made: no router, no links
        router = MeshRouter(send = ::route, deliver = ::onDeliver, minCipher = MeshRouter.MIN_SEALED, maxPerMinute = MeshRouter.LINK_PER_MINUTE, unlimited = NostrLink::owns, raised = ::onRaised)
            .also { it.restore(loaded.recent, loaded.seen) { key -> if (key in relayed) NostrLink.PEER else "" } } // what the relays brought doesn't go back to them
        if (prefs.getBoolean("wifiNetwork", true)) setWifi(true)
        if (prefs.getBoolean("internet", true)) setInternet(true)
        notifyUi()
        io.execute { savedGroupsChanged(code) }
        hourly()
    }

    private fun hourly() { main.postDelayed({ io.execute { weeklyCheck(); compact(); quietPassNow() }; hourly() }, Keyring.HOUR_MS) }

    /** Runs the clean-up now, as the hourly timer does (tests). */
    fun cleanUp() = io.execute(::compact)

    /** Makes the weekly new key of every saved group that is due. Background thread. */
    fun weeklyCheck() { if (!stopped) (listOfNotNull(active) + others).forEach { rekeyIfDue(it); finishRemovals(it); finishLeaves(it); answerLeftOut(it) } }

    /**
     * A new key when the current one is a week old (spec "Keys"), after this phone's random delay so that the group's
     * phones don't all do it at once; the first to do it settles it for the rest. Well past that delay this phone has
     * missed the group's new keys, or the whole group was quiet: its new key is then also how it asks to come back.
     */
    private fun rekeyIfDue(k: Keys) {
        val cur = k.ring.currentOrNull() ?: return
        val now = clock()
        // Counted from when this phone got the key, if that was after its date: a member's key dated long ago isn't due at once.
        if (k.isPublic || k.removed || !Keyring.weeklyDue(cur.since, now, k.weeklyDelay)) return
        val away = Keyring.overdue(cur.since, now)
        if (makeRekey(k, emptyList(), null) && away) setRejoining(k, System.currentTimeMillis())
    }

    /** The time, for deciding when a key is due; tests can move it forward. */
    private fun clock() = System.currentTimeMillis() + DebugTools.ahead

    private fun setRejoining(k: Keys, since: Long) {
        if (k.rejoiningSince == since) return
        k.rejoiningSince = since
        app?.getSharedPreferences("mesh", 0)?.edit()?.putLong("rejoining.${k.id}", since)?.apply()
        notifyUi()
    }

    /**
     * The very packet a message is shown from ([MeshRouter.key]), or null: what a report of that message is about.
     * [id] is the name the packet goes by in the store ([StoreCap.name]): the packet stored first under an id goes by
     * that id, any other by its key.
     */
    private fun packetKey(id: String): String? = if (id.length > 16) id else try {
        db?.rawQuery("SELECT ts, cipher FROM p WHERE id = ?", arrayOf(id))?.use { q -> if (q.moveToNext()) MeshRouter.key(Packet(id, q.getLong(0), 1, q.getBlob(1))) else null }
    } catch (e: RuntimeException) { DebugTools.log("store: can't read a packet: $e"); null }

    /** Turns the internet relays on or off (Connections screen), and remembers it. */
    @Synchronized fun setInternet(on: Boolean) {
        if (stopped) return
        val c = app ?: return
        c.getSharedPreferences("mesh", 0).edit().putBoolean("internet", on).apply()
        nostr?.stop(); nostr = null
        val tor = Tor.enabled(c) && DebugTools.relays(c) == null // the mock relay of debug builds is on this computer
        if (on) nostr = NostrLink(c, { router }, ::relayScopes, ::notifyUi, tor, ::markHandedOff, ::packetKey, fromRelays).also { it.start() }
        if (!on || !tor) main.post { Tor.stop(c) } // Tor runs only while the relays need it
        notifyUi()
    }

    /** Turns "Connect through Tor" on or off (Connections screen): the relays link again, through the app's own Tor or not. */
    @Synchronized fun setTor(on: Boolean) {
        if (stopped) return
        val c = app ?: return
        Tor.setEnabled(c, on)
        OfflineMap.torChanged(on)
        if (nostr != null) setInternet(true) else notifyUi()
    }

    /** This phone's relay mailboxes (spec/FORMAT.md, "Mailboxes"): each saved group's apart, so it can have its own links, then the Public channel's. */
    private fun relayScopes(): List<NostrLink.Scope> =
        (listOfNotNull(active) + others).filter { !it.isPublic }.map { k ->
            NostrLink.Scope(k.id, (k.ring.all().map { it.group.mailbox } + (k.oldDoors + k.door).map { it.crypto.mailbox }).distinct())
        } + NostrLink.Scope("", listOf(Nostr.PUBLIC_MAILBOX))

    /** Turns linking over Wi-Fi networks on or off (Connections screen), and remembers it. */
    @Synchronized fun setWifi(on: Boolean) {
        if (stopped) return
        val c = app ?: return
        c.getSharedPreferences("mesh", 0).edit().putBoolean("wifiNetwork", on).apply()
        if (on && wifi == null) wifi = WifiLink(c, linkId, { router }, ::notifyUi).also { w -> main.post { w.start(); notifyUi() } }
        if (!on) { wifi?.let { w -> main.post { w.stop() } }; wifi = null; notifyUi() }
    }

    /**
     * Shows another group. Confirmations owed so far go out first. Main thread; the work runs in the background, and
     * for a saved group it is quick: its key and messages are already there.
     */
    fun switchGroup(code: String) {
        if (stopped) return
        flushAcks()
        activeCode = code // shown at once; messages written now use this group's key (see post)
        io.execute { savedGroupsChanged(code) }
    }

    fun rename(newName: String) { if (stopped) return; name = newName.trim().take(40).ifEmpty { name }; notifyUi() }

    /**
     * The cached keys of [code], derived the first time (slow: call in the background). A code its group has moved on
     * from (an old invite, an alert from before a removal) gives the keys that group has under its new code, if it is
     * saved here: a second holder would put the old door on the group's current keyring and save over it.
     */
    private fun keys(code: String, follow: Boolean = true): Keys = synchronized(keyCache) { keyCache[code] } ?: Keys(code, app!!).let { k ->
        if (follow) movedTo(k)?.let { return keys(it, false) }
        loadKnown(k)
        loadRing(k)
        loadQuiet(k)
        synchronized(keyCache) { keyCache[code] = k }
        k
    }

    /** The code [k]'s group has now, if [k] was made from another one and the group is saved on this phone under it ([moveDoor] notes it). */
    private fun movedTo(k: Keys): String? {
        val c = app ?: return null
        return Vault.get(c, "door.${k.id}")?.let { String(it) }?.takeIf { it != k.code && Groups.find(c, it) != null }
    }

    /** The public keys saved for [k]'s group. */
    private fun loadKnown(k: Keys) {
        db?.rawQuery("SELECT id, pub, enc, encAt, pq FROM k WHERE grp = ?", arrayOf(k.id))?.use { q ->
            while (q.moveToNext()) {
                val pub = q.getBlob(1); val enc = if (q.isNull(2)) null else q.getBlob(2); val pq = if (q.isNull(4)) null else q.getBlob(4)
                P256.decompress(pub)?.let { key -> synchronized(k.known) { k.known[q.getString(0)] = Known(pub, key, enc, "", q.getLong(3), pq) } }
            }
        }
    }

    /**
     * [k]'s epoch secrets from the vault. A group this phone founded (created here, or saved before v0.6) and that
     * has none gets epoch 1 now, with this phone as its only member and admin (spec "Making the first epoch").
     */
    private fun loadRing(k: Keys) {
        val c = app ?: return
        if (k.isPublic) return
        Vault.get(c, "epochs.${k.id}")?.let { raw ->
            runCatching {
                val a = org.json.JSONArray(String(raw))
                for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                    fun ids(n: String) = o.getJSONArray(n).let { l -> (0 until l.length()).map(l::getString) }
                    fun text(n: String) = o.optString(n).ifEmpty { null }
                    fun int(n: String) = if (o.has(n)) o.getInt(n) else null
                    k.ring.add(Keyring.Epoch(o.getLong("n"), o.getLong("made"), o.getString("s").chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray(), ids("m"), ids("a"),
                        text("c"), text("v"), text("r"), text("b"), o.optInt("k", Int.MAX_VALUE), taken = o.optLong("t", o.getLong("made")), // saved before "t": its date
                        // The group's timer (spec "Disappearing messages"); none of these in an epoch saved before it: not known.
                        timerRaw = int("tr"), timerSetHere = o.optBoolean("th", false), timer = int("tv"), timerBy = text("tb"), timerCounts = o.optBoolean("tc", false)))
                }
            }
        }
        Vault.get(c, "mkeys.${k.id}")?.let { raw ->
            runCatching {
                val i = java.io.DataInputStream(raw.inputStream()); val saved = HashMap<String, MessageKeys>()
                while (i.available() > 0) { val hash = i.readUTF(); MessageKeys.decode(ByteArray(i.readInt()).also(i::readFully))?.let { saved[hash] = it } }
                k.ring.all().forEach { e -> (saved[e.id] ?: saved[e.hash])?.let { e.messageKeys = it } } // by hash: saved before epochs had an id
            }
        }
        // The parts it opened, only together with the epochs they brought: without those, the stored new keys are read again.
        if (k.joined) Vault.get(c, "parts.${k.id}")?.let { raw -> k.ring.restoreParts(String(raw).split(',').filter { it.isNotEmpty() }) }
        k.oldDoors = k.ring.all().mapNotNull { it.via }.distinct().filter { it != k.code }.map(::Door)
        if (k.ring.lastMove()?.code == k.code && Vault.get(c, "door.${k.id}") == null) Vault.put(c, "door.${k.id}", k.code.toByteArray()) // moved before this was noted
        val prefs = c.getSharedPreferences("mesh", 0)
        prefs.getString("removedAt.${k.id}", null)?.split(':')?.let { k.removedAt = it[0].toLong() to it[1].toInt(); k.removedBy = it.getOrNull(2) }
        k.rejoiningSince = prefs.getLong("rejoining.${k.id}", 0L)
        if (prefs.getBoolean("closed.${k.id}", false)) { k.closed = true; k.ring.closed = true }
        if (!k.joined && k.code in Groups.founded(c)) {
            // A group made here starts with the timer off, and knows it: its first admin says so in every key it makes.
            k.ring.add(Keyring.Epoch.fresh(1, System.currentTimeMillis(), ByteArray(32).also(rnd::nextBytes), listOf(k.me), listOf(k.me), timerRaw = 0, timerCounts = true))
            saveRing(k)
            Groups.setFounded(c, Groups.founded(c) - k.code)
            DebugTools.log("epoch 1 made for a group founded here")
        }
        prefs.getString("timer.${k.id}", null)?.let(Disappear.Watch::parse)?.let { w -> synchronized(k.watchLock) { k.watch = w } }
        observeTimer(k, clock())
        prefs.getString("leaving.${k.id}", null)?.let { s -> LeaveBook.parse(s.split('\n')) }?.let { b -> synchronized(k.leaveBook) { k.leaveBook.records.putAll(b.records); k.leaveBook.removals.putAll(b.removals) } }
    }

    /** A copy of who left [k]'s group with a note ([Keys.leaveBook]). Any thread. */
    private fun leavingOf(k: Keys): LeaveBook = synchronized(k.leaveBook) { k.leaveBook.copy() }

    /** Changes [k]'s leaving records; saved if [change] says it changed them. Any thread. */
    private fun changeLeaving(k: Keys, change: (LeaveBook) -> Boolean): Boolean {
        val saved = synchronized(k.leaveBook) { if (change(k.leaveBook)) k.leaveBook.saved else null } ?: return false
        if (stopped) return true // an erase began: nothing is written any more
        app?.getSharedPreferences("mesh", 0)?.edit()?.apply { if (saved.isEmpty()) remove("leaving.${k.id}") else putString("leaving.${k.id}", saved.joinToString("\n")) }?.apply()
        return true
    }

    /** Each held epoch's message keys, in the vault: they are saved after every message whose key was deleted. */
    private fun saveMessageKeys(k: Keys) {
        val c = app ?: return
        synchronized(k) {
            val b = java.io.ByteArrayOutputStream(); val o = java.io.DataOutputStream(b)
            for (e in k.ring.all()) e.messageKeys?.encode()?.let { x -> o.writeUTF(e.id); o.writeInt(x.size); o.write(x) }
            Vault.put(c, "mkeys.${k.id}", b.toByteArray())
        }
    }

    private fun saveRing(k: Keys) {
        saveMessageKeys(k)
        val c = app ?: return
        val a = org.json.JSONArray(); val timers = k.ring.timers()
        for (e in k.ring.all()) a.put(org.json.JSONObject().put("n", e.number).put("made", e.made)
            .put("s", e.secret.joinToString("") { "%02x".format(it) }).put("m", org.json.JSONArray(e.members)).put("a", org.json.JSONArray(e.admins))
            .put("c", e.code ?: "").put("v", e.via ?: "").put("r", e.removes ?: "").put("b", e.by ?: "").put("k", e.rank).put("t", e.taken).also { o ->
                // The group's timer in this epoch, as worked out last ([Keyring.retime]): kept when the epochs before it are gone.
                e.timerRaw?.let { o.put("tr", it) }
                if (e.timerSetHere) o.put("th", true)
                timers[e.id]?.let { t -> t.seconds?.let { o.put("tv", it) }; t.by?.let { o.put("tb", it) }; if (t.counts) o.put("tc", true) }
            })
        Vault.put(c, "epochs.${k.id}", a.toString().toByteArray())
        saveParts(k)
    }

    /** The sealed parts [k]'s keyring has opened ([Keyring.openedParts]): saved after the epochs they brought, never before. */
    private fun saveParts(k: Keys) {
        val c = app ?: return
        k.ring.partsToSave()
        Vault.put(c, "parts.${k.id}", k.ring.openedParts().joinToString(",").toByteArray())
    }

    /** Marks [code] as founded on this phone (a new group): its first epoch is made here. */
    fun found(c: Context, code: String) = Groups.setFounded(c, Groups.founded(c) + code)

    /** True while this phone has the code of [code]'s group but no epoch yet: it is asking to join. */
    fun joining(code: String?): Boolean = code != null && synchronized(keyCache) { keyCache[code] }?.let { !it.isPublic && !it.joined } ?: false

    /** The admins an invite to [code]'s group names (spec "Invites"), first listed first, or null until its keys are loaded. */
    fun invitePins(code: String): String? = held(code)?.let { k ->
        k.ring.currentOrNull()?.admins.orEmpty().mapNotNull { id -> if (id == k.me) k.pub else synchronized(k.known) { k.known[id]?.pub } }
            .take(Invite.MAX_PINS).joinToString("") { Invite.pin(it) }
    }

    /** True if this phone was removed from [code]'s group. */
    fun removed(code: String?): Boolean = held(code)?.removed ?: false

    /** True if [code] is an invite this phone is trying to join with and the group has moved on from it. */
    fun inviteClosed(code: String?): Boolean = held(code)?.let { it.closed && (!it.joined || it.rejoining) } ?: false

    /** True while this phone, back after more than a week, waits for someone in [code]'s group to answer. */
    fun rejoining(code: String?): Boolean = held(code)?.rejoining ?: false

    private fun held(code: String?) = synchronized(keyCache) { keyCache[code ?: return null] }
    private fun memberId(k: Keys, id: String) = if (id == deviceId) k.me else id.removePrefix("web-")

    fun isAdmin(code: String?, id: String): Boolean = held(code)?.let { k -> memberId(k, id) in (k.ring.currentOrNull()?.admins ?: emptyList()) } ?: false
    fun inGroup(code: String?, id: String): Boolean = held(code)?.let { k -> memberId(k, id) in k.members } ?: false

    /**
     * The other members of [code]'s group, as the key in use lists them (Group security): member id to name, "" for
     * someone whose introduction hasn't reached this phone. Empty while this phone has no key there.
     */
    fun otherMembers(code: String?): List<Pair<String, String>> {
        val k = held(code) ?: return emptyList()
        return synchronized(k.known) { k.members.filter { it != k.me }.map { it to (k.known[it]?.name ?: "") } }
    }

    /** How many people the key in use in [code]'s group was made for, this phone included; 0 while it has none. */
    fun memberCount(code: String?): Int = held(code)?.members?.size ?: 0

    /** Since when this phone counts the age of the key in use in [code]'s group ([Keyring.Epoch.since]), or null while it has none. */
    fun keySince(code: String?): Long? = held(code)?.takeIf { !it.isPublic }?.ring?.currentOrNull()?.since

    /** How old a group's key must be before "New key" is offered: a join or a removal makes a key anyway. */
    const val NEW_KEY_AFTER_MS = 24 * 3600_000L

    /**
     * True if this phone may make [code]'s group a new key now (Group security's "New key"): it is an admin there, wasn't
     * removed, and the key in use has been in use on this phone for at least 24 hours ([Keyring.Epoch.since]). The button
     * is for a key that has been used a while: every key is several packets for every member, and a phone keeps only so
     * many keys, so one made every hour would crowd the mesh and push older keys out before their week is up. The hour the
     * engine keeps between its own keys ([Keyring.tooSoon]) holds as well.
     */
    fun canMakeKey(code: String?): Boolean {
        val k = held(code) ?: return false; val cur = k.ring.currentOrNull() ?: return false
        val now = clock()
        return !stopped && !k.isPublic && !k.removed && k.me in cur.admins && SecurityWords.keyOldEnough(cur.since, now, NEW_KEY_AFTER_MS) && !k.ring.tooSoon(k.me, now)
    }

    /**
     * An admin makes a new key now: the very call the weekly change makes ([rekeyIfDue]), for the same members and admins,
     * sealed to everyone; nothing about it is new to the other phones. [done] says on the main thread if it worked: false
     * if a member's keys haven't reached this phone, as with every new key ([makeRekey]); null if this phone may not
     * make one now ([canMakeKey]).
     */
    fun newKey(code: String?, done: (Boolean?) -> Unit) = io.execute {
        val k = held(code)
        val ok = if (k == null || !canMakeKey(code)) null else makeRekey(k, emptyList(), null)
        main.post { done(ok) }
        notifyUi()
    }

    /** Gives [id] the admin role, with a new key. [done] says on the main thread if it worked. */
    fun makeAdmin(code: String?, id: String, done: (Boolean) -> Unit) =
        adminChange(code, id, done) { k, m, cur -> m !in cur.admins && makeRekey(k, emptyList(), null, admins = cur.admins + m) }

    /** Removes [id]: a new key and a new code for everyone else (spec "Removing and the code"). */
    fun remove(code: String?, id: String, done: (Boolean) -> Unit) =
        adminChange(code, id, done) { k, m, _ -> m != k.me && makeRekey(k, emptyList(), null, remove = m) }

    private fun adminChange(code: String?, id: String, done: (Boolean) -> Unit, act: (Keys, String, Keyring.Epoch) -> Boolean) = io.execute {
        val k = held(code); val cur = k?.ring?.currentOrNull(); val m = k?.let { memberId(it, id) }
        val ok = k != null && cur != null && m != null && !k.removed && k.me in cur.admins && m in cur.members && act(k, m, cur)
        main.post { done(ok) }
        notifyUi()
    }

    /** What happened in [code]'s group, by name and when: "Marco joined", "Anna removed Marco", "Anna made Ben an admin". */
    fun groupLines(code: String?): List<Pair<Long, String>> {
        val k = held(code) ?: return emptyList()
        fun name(id: String?) = synchronized(k.known) { k.known[id]?.name }?.ifEmpty { null } ?: "Someone"
        fun subject(id: String?) = if (id == k.me) "You" else name(id)
        fun obj(id: String?) = if (id == k.me) "you" else name(id)
        val timers = k.ring.timers(); val leaving = if (k.isPublic) LeaveBook() else leavingOf(k)
        // As the records will be once the keyring's change is swept in ([sweepLeaving]): a removal that has just come reads as the leaving at once.
        k.ring.currentOrNull()?.let { cur -> val all = k.ring.all(); leaving.sweep({ it in cur.members }, { m -> all.firstOrNull { it.removes == m && k.ring.won(it) }?.id }, all.mapTo(HashSet()) { it.id }, System.currentTimeMillis()) }
        // Someone who left with a note and is still listed (spec "Leaving the group"): one line at the note's time. An
        // admin's phone removes them in a moment; anyone else's says it waits for one.
        val admin = !k.removed && k.me in (k.ring.currentOrNull()?.admins ?: emptyList())
        val left = leaving.records.values.map { r -> r.ts to LeaveWords.line(name(r.member), waiting = !admin) }
        // One epoch per number ([Keyring.lineEpochs]): a key kept only to read with counts only where it stands in for its
        // number, and then by the members its secret was sealed to ([Keyring.Epoch.roster]), the last known if it has none.
        val chain = k.ring.lineEpochs(); var known: List<String> = emptyList()
        val people = chain.associate { e -> e.id to e.members.ifEmpty { e.roster }.ifEmpty { known }.also { known = it } }
        return left + chain.zipWithNext().flatMap { (a, b) ->
            (people.getValue(b.id) - people.getValue(a.id).toSet() - k.me).map { id -> b.made to "${name(id)} joined" } +
                (b.admins - a.admins.toSet()).map { id -> b.made to "${subject(b.by)} made ${obj(id)} an admin" } +
                // A removal that followed the member's own note reads as their leaving.
                listOfNotNull(b.removes?.let { id -> b.made to if (leaving.removals[b.id]?.member == id) LeaveWords.line(name(id), waiting = false) else "${subject(b.by)} removed ${obj(id)}" }) +
                // The timer changed with this key (spec "Disappearing messages"). Who did it is said only where this phone saw it.
                listOfNotNull(timers[b.id]?.seconds?.let { now -> timers[a.id]?.takeIf { was -> was.seconds != now && (was.seconds != null || now > 0) }
                    ?.let { b.made to GroupTimer.changeLine(now, timers[b.id]?.by?.let(::subject)) } })
        }
    }

    // MARK: - The group's timer (spec/FORMAT.md, "Disappearing messages")

    /**
     * The timer of [code]'s group: seconds, 0 for off, null while this phone doesn't know it. [Keyring.Timer.by]: who set
     * it, as the screen says it ("Anna", "you"), if this phone saw the change.
     */
    fun timer(code: String?): Keyring.Timer {
        val k = held(code)?.takeIf { !it.isPublic && it.joined } ?: return Keyring.Timer(null, null)
        val t = k.ring.timer()
        val by = t.by?.let { id -> if (id == k.me) "you" else synchronized(k.known) { k.known[id]?.name }?.ifEmpty { null } ?: "an admin" }
        return Keyring.Timer(t.seconds, by, t.counts)
    }

    /** True if this phone can change the timer of [code]'s group now: it is an admin there and wasn't removed. */
    fun canSetTimer(code: String?): Boolean {
        val k = held(code) ?: return false
        if (k.isPublic || k.removed || stopped) return false
        return k.me in (k.ring.currentOrNull()?.admins ?: return false)
    }

    /**
     * An admin sets the group's timer: a new key that carries it, then one message that says so for apps that don't follow
     * the timer. [done], on the main thread: true if the key was made; false if it couldn't be (a member's keys haven't
     * reached this phone, or this phone may not). Choosing the value the group has changes nothing.
     */
    fun setTimer(code: String?, seconds: Int, done: (Boolean) -> Unit) = io.execute {
        val k = held(code)
        var ok = false
        if (k != null && canSetTimer(code)) {
            if (k.ring.timer().seconds == seconds) ok = true
            else if (makeRekey(k, emptyList(), null, timer = seconds)) { ok = true; sendTimerNotice(k, seconds) }
        }
        main.post { done(ok) }
        notifyUi()
    }

    /**
     * The notice after a change: an ordinary group message whose text says what changed, for apps that don't follow the
     * timer, with the timer in its 5th field. Apps that do follow it show nothing of it: no row, no "Sent", no "Delivered".
     * Kept off LoRa, like a reaction; up to the relays first, like everything written here.
     */
    private fun sendTimerNotice(k: Keys, seconds: Int) {
        if (stopped || !k.joined || k.removed) return
        val r = router ?: return
        val p = synchronized(messages) {
            val cr = k.group; val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
            val p = cr.packet(id, ts, sealOne(k, cr, false, id, ts, k.me, name, GroupTimer.notice(seconds), null, fifth = GroupTimer.field(seconds)).first)
            MeshRouter.key(p).let { key -> synchronized(ids) { myReceipts += id; receiptIds.add(key) } }
            save(p, StoreCap.Kind.GROUP, KeepLess.UNSENT); kept[id] = KeepLess.Held(KeepLess.UNSENT, p.ts, k.id) // its time starts when a link takes it
            p
        }
        r.publish(p)
    }

    /** The timer the keyring of [k] has now, noted with since when ([Disappear.Watch]). True if the value changed: its ten minutes start at [now]. */
    private fun observeTimer(k: Keys, now: Long): Boolean {
        if (k.isPublic) return false
        val seconds = k.ring.timer().seconds
        val (changed, saved) = synchronized(k.watchLock) {
            val w = k.watch.copy(); val changed = w.observe(seconds, now)
            val moved = w != k.watch; k.watch = w
            changed to (if (moved) w.saved else null)
        }
        if (saved != null) app?.getSharedPreferences("mesh", 0)?.edit()?.putString("timer.${k.id}", saved)?.apply()
        return changed
    }

    /** The keyring of [k] changed: if the group's timer did, its ten minutes start now, and the next clean-up is set anew. */
    private fun timerMayHaveChanged(k: Keys) {
        if (observeTimer(k, clock())) { DebugTools.log("timer: ${k.ring.timer().seconds ?: "not known"}"); notifyUi() }
        io.execute(::scheduleDue)
    }

    /** The clean-up, now, if a group here has a timer: the app came to the front. */
    fun cleanUpIfTimed() {
        if (groupsHeld().any { (it.watch.seconds ?: 0) > 0 }) io.execute(::compact)
    }

    /** Every saved group's keys this phone holds, the Public channel's left out. */
    private fun groupsHeld(): List<Keys> = synchronized(keyCache) { keyCache.values.distinct() }.filter { !it.isPublic }

    @Volatile private var dueAt = 0L // when the next clean-up for a group's timer is set to run (by [clock]), 0 if none
    private val dueLock = Any()

    /**
     * Sets the clean-up to run when the next message of a group with a timer is due, or when a new value has settled. One
     * is pending at a time; [compact] sets the next when it has run. Any thread.
     */
    private fun scheduleDue() {
        if (stopped) return
        val now = clock()
        var next: Long? = null
        for (k in groupsHeld()) {
            val w = k.watch; val seconds = w.seconds ?: continue
            if (seconds <= 0) continue
            val arrived = synchronized(messages) { kept.values.filter { it.group == k.id }.map { it.at } }
            val due = Disappear.next(arrived, seconds * 1_000L) ?: continue
            val at = maxOf(due, w.settledAt)
            if (next == null || at < next) next = at
        }
        val target = next ?: run { dueAt = 0L; return }
        synchronized(dueLock) { if (dueAt in (now + 1)..target) return; dueAt = target }
        main.postDelayed({ if (dueAt == target && !stopped) { dueAt = 0L; io.execute(::compact) } }, (target - now).coerceIn(0L, Keyring.HOUR_MS) + 1_000L)
    }

    /** A packet of [k]'s group is now timed (it arrived, or this phone's own one left): if no clean-up is set and the group has a timer, set one. */
    private fun dueSoon(k: Keys) { if (dueAt == 0L && (k.watch.seconds ?: 0) > 0) io.execute(::scheduleDue) }

    /**
     * A new epoch from a rekey (spec "New key"): signed by its maker, allowed by [Keyring.admit], with a part this phone
     * can open. A removal also moves the group to its new code. True if it was new. Hold the messages lock or scan.
     */
    private fun acceptRekey(x: Seen.NewKey): Boolean {
        val k = x.k; val r = x.r; val maker = x.o.message.sender
        if (!signed(x)) return false
        val pins = Groups.pins(app ?: return false, k.code)
        val pinned = Invite.allows(pins, r.maker) // only read while this phone has no epoch
        if (!pinned && !k.joined) DebugTools.log("new key from someone this invite doesn't name: ignored")
        // The group may have moved on while this phone was away. Only on an admin's word: anyone who has the code can sign a removal.
        if (r.removal && k.rejoining && maker != k.me && x.door === k.door && k.ring.movedOn(r, maker)) k.closed = true
        val prefs = app?.getSharedPreferences("mesh", 0)
        // The 5th field of a new key is the group's timer (spec "Disappearing messages"). It is read only from a key the
        // keyring takes, and whether it counts is the keyring's to say ([Keyring.retime]).
        return when (val t = k.ring.take(r, maker, k.me, x.door.code, x.door !== k.door, k.removedAt, pinned = pinned, removedBy = k.removedBy, named = Invite.names(pins, r.maker), inUse = otherCodes(k),
            timer = GroupTimer.read(x.o.fifth)) { box -> k.encs.open(box, Rekey.info(x.door.id), Rekey.aad(x.packetId, k.me)) }) {
            Keyring.Taken.Ignored -> false
            is Keyring.Taken.LeftOut -> {
                // The others may have taken this key and be writing with it; this phone can't. It asks its way back with a key of its own.
                DebugTools.log("epoch ${t.number} lists this phone, but its part doesn't open")
                if ((k.leftOut?.number ?: 0L) < t.number) k.leftOut = LeftOut(t.number, t.rank, 0) // read again from the store: the count stays
                main.postDelayed({ io.execute { answerLeftOut(k) } }, rnd.nextInt(3_000).toLong())
                false
            }
            Keyring.Taken.Closed -> { k.closed = true; prefs?.edit()?.putBoolean("closed.${k.id}", true)?.apply(); false } // saved: the keyring takes no key through this code from now on
            is Keyring.Taken.Removed -> {
                k.removedAt = t.number to t.rank; k.removedBy = t.by; prefs?.edit()?.putString("removedAt.${k.id}", "${t.number}:${t.rank}:${t.by}")?.apply()
                DebugTools.log("removed from a group")
                false
            }
            is Keyring.Taken.Added -> {
                if (t.back) { k.removedAt = null; k.removedBy = null; prefs?.edit()?.remove("removedAt.${k.id}")?.apply() }
                if (t.moveTo != null) moveDoor(k)
                saveRing(k)
                timerMayHaveChanged(k)
                if (k.ring.current() === t.epoch && maker != k.me) setRejoining(k, 0)
                synchronized(waiting) { waiting.remove(k.id) }?.let { held -> io.execute { held.forEach { post(false, it.from, it.text, false, it.photo, k.code, voice = it.voice) } } }
                DebugTools.log("epoch ${r.epoch}: ${r.members.size} member(s)${if (r.removal) ", after a removal" else ""}")
                true
            }
        }.also { if (k.ring.partsToSave()) saveParts(k) } // a part opened for an epoch this phone holds already: not opened again either
    }

    /** The codes of the other groups on this phone, saved or held: a removal can't move [k]'s group onto one of them. */
    private fun otherCodes(k: Keys): List<String> {
        val c = app ?: return emptyList()
        return (Groups.all(c).map { it.code } + synchronized(keyCache) { keyCache.filterValues { it !== k }.keys.toList() }).filter { it != k.code }
    }

    /**
     * Two admins removed different people at once: only one removal won, so the other person is still in the current
     * epoch. An admin's phone finishes it, after 0-3 s unless another admin did (spec "Two new keys at the same time").
     */
    private fun finishRemovals(k: Keys) {
        if (k.removed || k.me !in (k.ring.currentOrNull()?.admins ?: return)) return
        for (m in k.ring.stillToRemove()) if (m != k.me && !makeRekey(k, emptyList(), null, remove = m)) return
    }

    // MARK: - Someone else left (spec/FORMAT.md, "Leaving the group")

    /**
     * A leave note whose signature checked, or can't be checked yet ([checked] null: it waits, and comes back here when it
     * checks). Only a note that passes rule L1 ([LeaveNote.verdict]) counts: then this phone keeps a record (L2), from which
     * nothing the member dates later is shown (L3), and an admin's phone removes them after 0-3 s (L4). The note itself is
     * never a row, an alert or a receipt. Any thread.
     */
    private fun tookNote(k: Keys, o: GroupCrypto.Opened, checked: Boolean?) {
        if (stopped || k.isPublic || o.message.notice != Notice.Leave) return
        val sender = o.message.sender.removePrefix("web-")
        val verdict = LeaveNote.verdict(sender, k.me, checked, o.opener, k.ring)
        DebugTools.log("leave note ${o.message.id}: $verdict")
        if (verdict != LeaveNote.Verdict.VALID) return
        val epoch = o.opener?.number ?: return
        if (!changeLeaving(k) { it.take(sender, o.message.ts, epoch, System.currentTimeMillis()) }) return // later notes change nothing
        notifyUi() // the line, and what they wrote after it goes
        main.postDelayed({ io.execute { removeLeaver(k, sender) } }, rnd.nextInt(LeaveNote.MAX_DELAY_MS).toLong())
    }

    /**
     * Rule L4: on an admin's phone, the normal removal of a member who left, unless they are out already or a removal of
     * them is here (another admin's, or a rival one, which "Two admins at once" settles). False if it couldn't be made now:
     * the hourly check tries again ([finishLeaves]) while the record lasts. The leaving phone never makes this key: a
     * removal made by the person who goes would give them the new secret and the new code. Background thread (io).
     */
    private fun removeLeaver(k: Keys, m: String): Boolean {
        if (stopped || m == k.me || leaving == k.code || leavingOf(k).record(m) == null) return true
        val cur = k.ring.currentOrNull() ?: return true
        val rest = cur.members.filter { it != m && it != k.me }
        val complete = synchronized(k.known) { rest.all { k.known[it]?.enc != null && k.known[it]?.pq != null } }
        return when (LeaveNote.act(!k.removed && k.me in cur.admins, m in cur.members, k.ring.all().any { it.removes == m }, complete)) {
            LeaveNote.Act.REMOVE_NOW -> makeRekey(k, emptyList(), null, remove = m).also { DebugTools.log("leave: removing a member who left: ${if (it) "new key made" else "couldn't, tries again hourly"}") }
            LeaveNote.Act.RETRY_HOURLY -> false.also { DebugTools.log("leave: a member's encryption key is missing, tries again hourly") }
            LeaveNote.Act.NOTHING -> true
        }
    }

    /** Beside [finishRemovals], every hour and when the saved groups are read: records end ([sweepLeaving]), and an admin's phone removes whoever left and is still listed. */
    private fun finishLeaves(k: Keys) {
        if (k.isPublic || stopped) return
        sweepLeaving(k)
        for (m in leavingOf(k).records.keys.sorted()) if (!removeLeaver(k, m)) return
    }

    /** Ends the records of members no longer in the current epoch, keeping for the chat's line the removal that took them out while that epoch is held; and records 7 days old ([LeaveBook.sweep]). Any thread. */
    private fun sweepLeaving(k: Keys) {
        if (k.isPublic || synchronized(k.leaveBook) { k.leaveBook.isEmpty }) return
        val cur = k.ring.currentOrNull() ?: return
        val all = k.ring.all(); val held = all.mapTo(HashSet()) { it.id }
        if (changeLeaving(k) { b -> b.sweep({ it in cur.members }, { m -> all.firstOrNull { it.removes == m && k.ring.won(it) }?.id }, held, System.currentTimeMillis()) }) notifyUi()
    }

    /** The rekey really is from who it says: its key matches the sender's id, and it signed the packet. */
    private fun signed(x: Seen.NewKey): Boolean {
        if (P256.memberId(x.r.maker) != x.o.message.sender) return false
        val key = P256.decompress(x.r.maker) ?: return false
        return P256.verify(key, x.o.digest, x.o.signature)
    }

    /**
     * A member's phone missed our keys: a new epoch, sealed to everyone, brings it back (spec "Away for a while"). Not if
     * another member answered first, or this phone made a key in the last hour (a flood of these must not cost more than that).
     * [at]: this phone's current epoch when that member's key came. Another one since then is the answer, whatever date
     * the key carried: its maker writes the date, and one a few minutes ahead would have every phone answer.
     */
    private fun answerBehind(k: Keys, made: Long, at: Keyring.Epoch?) {
        val cur = k.ring.currentOrNull() ?: return
        if (k.removed || cur !== at || cur.made >= made || k.ring.tooSoon(k.me, clock())) return
        makeRekey(k, emptyList(), null)
    }

    /**
     * A new key listed this phone but its part didn't open ([Keyring.Taken.LeftOut]): the others may have moved on to it,
     * and can't tell. Like a phone that was away, this one makes a key of its own, sealed to everyone, and shows that it is
     * rejoining. That key has the same number as the one it couldn't open if that was the next one, so it can lose to it:
     * an hour later this phone makes one more, numbered above both. Not once a key that settles it got here: a
     * higher-numbered one, or one of the same number that comes before the one it couldn't open whatever the hashes.
     */
    private fun answerLeftOut(k: Keys) {
        val t = k.leftOut ?: return; val cur = k.ring.currentOrNull() ?: return
        if (k.removed || cur.number > t.number || (cur.number == t.number && (cur.code != null || cur.rank < t.rank))) { k.leftOut = null; return }
        if (k.ring.tooSoon(k.me, clock())) return // the hourly check tries again
        if (!makeRekey(k, emptyList(), null)) return
        setRejoining(k, System.currentTimeMillis())
        k.leftOut = if (t.tries == 0) LeftOut(t.number, t.rank, 1) else null
    }

    /**
     * The group follows the code of its newest removal ([Keyring.lastMove]): same group id, member keys and messages, a
     * new door and a new invite. Taken from the keyring, so a removal made here and one arriving at the same moment end
     * up on the same code. Locks in the order messages, then keys, like the delivery path.
     */
    private fun moveDoor(k: Keys) {
        val c = app ?: return
        while (true) {
            val code = k.ring.lastMove()?.code ?: return
            if (k.door.code == code) return
            val next = Door(code) // slow (scrypt): outside the locks
            val done = synchronized(messages) { synchronized(k) {
                if (k.ring.lastMove()?.code != code) return@synchronized false
                val old = k.door
                if (old.code == code) return@synchronized true
                Vault.put(c, "origin.${next.id}", k.id.toByteArray())
                k.oldDoors = listOf(old) + k.oldDoors.filter { it.code != code }
                k.door = next
                k.closed = false // that was about the code it left
                synchronized(keyCache) { keyCache.remove(old.code); keyCache[code] = k }
                Vault.put(c, "door.${k.id}", code.toByteArray()) // where the group is now: its old codes lead here ([keys])
                Groups.replaceCode(c, old.code, code)
                ReadMarks.rename(c, old.code, code)
                c.getSharedPreferences("mesh", 0).edit().remove("introAt.${k.id}").apply() // introduce ourselves through the new door
                if (activeCode == old.code) activeCode = code
                messages.replaceAll { if (it.group == old.code) it.copy(group = code) else it }
                true
            } }
            if (done) break
        }
        DebugTools.log("the group moved to a new code")
        notifyUi()
    }

    private fun declined(k: Keys): Set<String> = app?.getSharedPreferences("mesh", 0)?.getStringSet("declined.${k.id}", emptySet()) ?: emptySet()

    /**
     * Who is asking to join [k]'s group ([Joining.asking]): they introduced themselves through the door in the last week,
     * aren't in the group and weren't turned down. At most [Joining.MAX_ASKING]: anyone with the code can make up keys.
     */
    private fun asking(k: Keys): List<String> {
        val at = synchronized(k.known) { k.known.entries.filter { it.value.pq != null }.associate { it.key to it.value.encAt } }
        return Joining.asking(at, k.members.toSet(), declined(k), k.me, System.currentTimeMillis())
    }

    /**
     * People asking to join [code]'s group, for this phone to answer if it is an admin: they introduced themselves through
     * the door in the last week and aren't in the group, weren't removed and weren't turned down.
     */
    fun joinRequests(code: String?): List<JoinRequest> {
        val k = held(code) ?: return emptyList(); val cur = k.ring.currentOrNull() ?: return emptyList()
        if (k.removed || k.me !in cur.admins) return emptyList()
        val gone = k.ring.all().mapNotNull { it.removes }.toSet(); val ids = asking(k).filter { it !in gone }
        return synchronized(k.known) { ids.mapNotNull { id -> k.known[id]?.let { w -> JoinRequest(id, w.name.ifEmpty { "Someone" }, w.pub) } } }
    }

    /** An admin lets [id] in: a new epoch for everyone, them included (spec "Joining"). [done] says on the main thread if it worked. */
    fun approveJoin(code: String?, id: String, done: (Boolean) -> Unit) = io.execute {
        val k = held(code); val cur = k?.ring?.currentOrNull()
        val ok = k != null && cur != null && !k.removed && k.me in cur.admins && id !in cur.members && makeRekey(k, add = listOf(id), answers = id)
        main.post { done(ok) }
        notifyUi()
    }

    /** An admin turns [id] down. They can ask again only with a new key, that is by reinstalling. */
    fun declineJoin(code: String?, id: String) {
        if (stopped) return
        val k = held(code) ?: return
        app?.getSharedPreferences("mesh", 0)?.edit()?.putStringSet("declined.${k.id}", declined(k) + id)?.apply()
        notifyUi()
    }

    /**
     * Makes and sends a new epoch for the current members plus [add], sealed to each member's encryption key. [admins]:
     * a new admin list. [remove]: who leaves; everyone else also gets a new code. False if a member's encryption key
     * hasn't reached this phone yet: leaving them out would quietly remove them.
     * [timer]: this phone's user chose a new timer for disappearing messages (seconds, 0 for off). Every new key carries
     * the timer its maker knows, this choice or the current one, so that phones that join or come back learn it; one whose
     * maker doesn't know it carries none. Only an admin's counts ([Keyring.retime]).
     */
    private fun makeRekey(k: Keys, add: List<String>, answers: String?, admins: List<String>? = null, remove: String? = null, timer: Int? = null): Boolean {
        if (stopped) return false
        // This phone told the group it leaves and waits for the note to go: it makes no key there any more (no removal of
        // another leaver, no answer, no weekly key), not one with a new code and secret it would forget a moment later.
        if (synchronized(told) { k.id in told }) { DebugTools.log("rekey: this phone is leaving the group, no key made"); return false }
        val r = router ?: return false
        val cur = k.ring.currentOrNull() ?: return false
        val timed = timer ?: k.ring.timer().seconds
        val members = (cur.members + k.me + add).distinct().filter { it != remove }
        if (members.size > Keyring.MAX_MEMBERS) { DebugTools.log("rekey: the group is full"); return false }
        val door = k.door; val code = if (remove != null) Invite.newCode() else null
        val now = System.currentTimeMillis(); val secret = ByteArray(32).also(rnd::nextBytes); val plain = Rekey.box(secret, code)
        // An admin's rekey also keeps every admin added by a rekey of the same number that didn't win.
        val list = ((admins ?: cur.admins) + if (k.me in cur.admins) k.ring.rivalAdmins() else emptyList()).distinct().filter { it in members }
        // A big group's key goes in several packets, each with the whole member list and some members' parts.
        var rk: Rekey? = null
        val packets = Rekey.split(members).map { some ->
            val pid = Packet.newId(door.rekeys.mailbox)
            val parts = some.map { m ->
                val known = if (m == k.me) null else synchronized(k.known) { k.known[m] }
                val to = (if (m == k.me) k.encPub else known?.enc)?.let(P256::fromUncompressed)
                val pq = if (m == k.me) k.encs.currentPq() else known?.pq
                val box = if (to != null && pq != null) Hybrid.seal(plain, to, pq, Rekey.info(door.id), Rekey.aad(pid, m)) else null
                m to (box ?: run { DebugTools.log("rekey: no encryption key for a member yet"); return false })
            }
            val one = Rekey(cur.number + 1, now, k.pub, list, remove ?: answers, parts, remove != null, members).also { rk = it }
            door.rekeys.packet(pid, now, door.rekeys.seal(pid, now, k.me, name, "", extra = one.encode(), pad = Pad.FULL, fifth = timed?.let(GroupTimer::field)) { k.sign(it) }) // always the largest size: its length would count the members
        }
        val made = rk ?: return false
        k.ring.add(Keyring.Epoch.fresh(made.epoch, now, secret, members, list, code, if (remove != null) door.code else null, remove, k.me, k.ring.standing(made, k.me),
            timerRaw = timed, timerSetHere = timer != null, timer = k.ring.timer().seconds, timerCounts = timed != null && k.ring.setsTimer(made, k.me)))
        if (code != null) moveDoor(k)
        saveRing(k)
        timerMayHaveChanged(k)
        for (p in packets) {
            synchronized(ids) { myReceipts += p.id }
            synchronized(messages) { save(p, StoreCap.Kind.GROUP) }
            r.publish(p)
        }
        if (code != null) introIfDue(k)
        sweepLeaving(k) // someone who left may be out now
        nostr?.resubscribe() // the new epoch's mailbox
        DebugTools.log("epoch ${made.epoch} made: ${members.size} member(s) in ${packets.size} packet(s)" + (if (answers != null) ", answering a join" else "") + (if (remove != null) ", removing a member" else ""))
        notifyUi()
        return true
    }

    /**
     * A member's public key from a checked introduction: saved (the [MAX_KNOWN] most recently seen per group stay),
     * then that member's messages waiting for it are checked. True if it was new.
     */
    private fun learn(k: Keys, id: String, pub: ByteArray, key: java.security.PublicKey, enc: ByteArray?, who: String, encAt: Long, pq: ByteArray?): Boolean {
        val members = k.members.toSet()
        // Someone who isn't in the group and isn't known here yet: kept only while there is room among those asking to join.
        if (!k.isPublic && id != k.me && synchronized(k.known) { !k.known.containsKey(id) } && !Joining.keeps(id, asking(k), k.ring.everyone(), k.me)) return false
        val (fresh, cur, changed) = synchronized(k.known) {
            val had = k.known[id]
            val newer = enc != null && EncSchedule.isNewer(had?.encAt ?: 0L, encAt)
            val now = Known(pub, key, if (newer) enc else had?.enc, who.ifEmpty { had?.name ?: "" }, if (newer) encAt else had?.encAt ?: 0L, if (newer) pq else had?.pq)
            k.known[id] = now
            // Full: the least recently used go, but never someone in the group, or new keys for them couldn't be sealed.
            if (k.known.size > MAX_KNOWN) k.known.keys.firstOrNull { it != id && it !in members }?.let { k.known.remove(it) }
            Triple(had == null, now, had == null || newer || !had.pub.contentEquals(pub))
        }
        // An introduction that brings nothing new (one read again from the store, or played back) writes nothing.
        if (changed) db?.let { d ->
            d.insertWithOnConflict("k", null, ContentValues().apply { put("grp", k.id); put("id", id); put("pub", pub); if (cur.enc != null) put("enc", cur.enc); put("encAt", cur.encAt); if (cur.pq != null) put("pq", cur.pq); put("seen", System.currentTimeMillis()) },
                SQLiteDatabase.CONFLICT_REPLACE)
            if (fresh) d.execSQL("DELETE FROM k WHERE grp = ? AND id NOT IN (${members.joinToString(",") { "?" }.ifEmpty { "''" }}) AND id NOT IN (SELECT id FROM k WHERE grp = ? ORDER BY seen DESC LIMIT $MAX_KNOWN)", (listOf(k.id) + members + k.id).toTypedArray())
        }
        return fresh
    }

    /** Messages and receipts of [id] in [k]'s group that waited for its key: shown as checked, or dropped. Hold the messages lock. */
    private fun resolve(k: Keys, id: String) {
        if (id != k.me && synchronized(k.known) { !k.known.containsKey(id) }) return // its key wasn't kept ([Joining]): nothing can be checked
        val waited = unchecked.take(k, id)
        if (waited.isEmpty()) return
        for (e in waited) {
            val w = e.item
            val ok = check(k, w.o, had = true) == true
            if (w.receipt != null) { if (ok) { deliveries.add(k.id, w.receipt); quietReceipt(w.k, w.receipt) }; continue }
            if (w.o.message.notice == Notice.Leave) { // a leave note is decided when it checks (rule L1)
                if (ok) { w.o.leaf?.forget(); saveMessageKeys(w.k); tookNote(w.k, w.o, checked = true) }
                continue
            }
            w.reaction?.let { (name, entry) -> // counted from now on; its key goes like a message's
                if (ok) { w.o.leaf?.forget(); saveMessageKeys(w.k); if (entry != null && index.containsKey(name)) reactions.add(name, entry) } // only while its packet is here
                continue
            }
            if (ok) quietSaw(w.k, w.o, true) // a clock or a check-in, decided when it checks (rule Q1)
            // Only a packet that is the one shown under its id waits ([KeyWait.message]), so this row is that packet's.
            val row = e.row ?: continue; val i = messages.indexOfFirst { it.id == row }
            if (i < 0 || messages[i].verified) continue
            if (ok) { messages[i] = messages[i].copy(verified = true); w.o.leaf?.forget(); saveMessageKeys(w.k) } else { messages.removeAt(i); messageIds.remove(row); shownAs.remove(w.o.mark); index[row]?.let { x -> index[x.id] = StoreCap.Entry(x.id, x.ts, x.size, StoreCap.Kind.UNREADABLE) } }
        }
        DebugTools.log("a member's key: checked ${waited.size} waiting packet(s)")
    }

    /**
     * true: signed by that member; false: not (forged or broken), or its sender isn't in the group or was removed; null:
     * the member's public key isn't known yet. [had]: this phone accepted the packet before, so it is read from its own
     * copy or was waiting for its sender's key. Only then does a removed member's date count ([Keyring.shutOut]).
     */
    private fun check(k: Keys, o: GroupCrypto.Opened, had: Boolean = false): Boolean? {
        val id = o.message.sender.removePrefix("web-")
        if (k.ring.shutOut(id, o.message.ts, had)) return false // not in the group; or removed, and seen only now or dated after the removal
        val key = if (id == k.me) k.pair.public else synchronized(k.known) { k.known[id]?.key } ?: return null
        return P256.verify(key, o.digest, o.signature)
    }

    /**
     * Sends this phone's introduction to [k]'s group when the last one there is more than 3 days old (or there was
     * none), so members' introductions are always among the packets of the last 7 days (spec "Introductions").
     */
    private fun introIfDue(k: Keys, writing: Boolean = false) {
        val c = app ?: return; val r = router ?: return
        if (k.removed) return
        val prefs = c.getSharedPreferences("mesh", 0); val now = System.currentTimeMillis()
        // The Public channel: only when the user writes there, then while a post of theirs (or one typed on this phone's
        // Wi-Fi board) is still shown. A phone whose user never posts on Everyone doesn't announce itself there.
        if (k.isPublic && !PublicIntro.wanted(writing, synchronized(messages) { messages.filter { it.public && (it.sender == deviceId || it.sender == "web-${k.me}") }.maxOfOrNull { it.ts } }, now)) return
        val rotated = !k.isPublic && k.encs.rotateIfDue(clock()) // a new encryption key has to be introduced at once
        if (!rotated && now - prefs.getLong("introAt.${k.id}", 0L) < INTRO_EVERY) return
        val id = Packet.newId(k.intros.mailbox)
        // No name on the Public channel: nothing reads it there, and a post carries its sender's name itself.
        val p = k.intros.packet(id, now, k.intros.seal(id, now, k.me, if (k.isPublic) "" else name, "", extra = if (k.isPublic) byteArrayOf(2) + k.pub + k.encPub else byteArrayOf(3) + k.pub + k.encPub + k.encs.currentPq()) { k.sign(it) })
        synchronized(ids) { myReceipts += id } // written here: goes up to the relays first, like receipts
        synchronized(messages) { save(p, if (k.isPublic) StoreCap.Kind.PUBLIC else StoreCap.Kind.GROUP) }
        prefs.edit().putLong("introAt.${k.id}", now).apply()
        r.publish(p)
        DebugTools.log("introduction sent to ${if (k.isPublic) "Everyone" else "a group"}")
    }

    /** The group on screen. */
    val currentCode: String get() = activeCode

    // People whose emoji the user compared (spec "Person emoji"): "<group id>|<member id>|<name key>".
    private fun tagOf(code: String?) = synchronized(keyCache) { keyCache[code ?: GroupCrypto.PUBLIC_CODE] }?.id
    private fun checkedSet(): Set<String> = app?.getSharedPreferences("mesh", 0)?.getStringSet("checked", emptySet()) ?: emptySet()

    fun isChecked(code: String?, id: String): Boolean { val t = tagOf(code) ?: return false; return checkedSet().any { it.startsWith("$t|$id|") } }

    private fun inPersonSet(): Set<String> = app?.getSharedPreferences("mesh", 0)?.getStringSet("checkedInPerson", emptySet()) ?: emptySet()

    /** How [id] was checked in [code]'s group: their code was scanned in the same place, or their emoji compared; null if not checked. */
    fun checkedHow(code: String?, id: String): CheckedHow? { val t = tagOf(code) ?: return null; return Checked.how(checkedSet(), inPersonSet(), t, id) }

    /**
     * The user compared [id]'s emoji and they matched, or scanned their code ([how]): this key is that person's from now on
     * (an older one for the name goes). How it was checked is kept next to the mark ([Checked.inPerson]).
     */
    fun markChecked(code: String?, id: String, name: String, how: CheckedHow = CheckedHow.EMOJI) {
        if (stopped) return
        val c = app ?: return; val t = tagOf(code) ?: return
        val checked = Checked.marked(checkedSet(), t, id, name)
        c.getSharedPreferences("mesh", 0).edit().putStringSet("checked", checked).putStringSet("checkedInPerson", Checked.inPerson(inPersonSet(), checked, t, id, how)).apply()
        notifyUi()
    }

    /** The emoji on the two phones didn't match: [id] is not checked, whatever was marked before. */
    fun unmarkChecked(code: String?, id: String) {
        if (stopped) return
        val c = app ?: return; val t = tagOf(code) ?: return
        c.getSharedPreferences("mesh", 0).edit().putStringSet("checked", Checked.unmarked(checkedSet(), t, id))
            .putStringSet("checkedInPerson", Checked.unmarked(inPersonSet(), t, id)).apply()
        notifyUi()
    }

    /** True if [name] belongs to a checked person but [id] is another key: a reinstall, or someone using their name. */
    fun newKeyFor(code: String?, id: String, name: String): Boolean {
        if (id == deviceId) return false
        val t = tagOf(code) ?: return false; val checked = checkedSet()
        return checked.isNotEmpty() && Checked.newKey(checked, t, id, name)
    }

    /**
     * The id of the group whose code is or was [code] ([Message.group]): it stays when the code changes, so blocks hang
     * off it ([Blocks]). The door a group has just left still counts, for a message read a moment before the move.
     */
    fun groupId(code: String?): String? {
        if (code == null) return null
        return synchronized(keyCache) { keyCache[code] ?: keyCache.values.firstOrNull { k -> k.oldDoors.any { it.code == code } } }?.id
    }

    /**
     * The code the group of [code] has now, if [code] is one it has moved on from ([moveDoor] notes where it went), or
     * null. Through the saved notes, not only the cache, so it also works right after a restart. Slow the first time
     * (derives the door): call in the background.
     */
    fun movedCode(code: String): String? {
        val c = app ?: return null
        synchronized(keyCache) { keyCache.values.firstOrNull { k -> k.oldDoors.any { it.code == code } } }?.let { return it.door.code }
        val door = Door(code).id
        val id = Vault.get(c, "origin.$door")?.let { String(it) } ?: door
        return Vault.get(c, "door.$id")?.let { String(it) }?.takeIf { it != code }
    }

    /** This phone's public key in [code]'s group (Person emoji: "your emoji"), or null if its keys aren't ready. */
    fun myPublicKey(code: String?): ByteArray? = synchronized(keyCache) { keyCache[code ?: GroupCrypto.PUBLIC_CODE] }?.pub

    /** The public key of member [id] in [code]'s group, if an introduction brought it. */
    fun publicKeyOf(code: String?, id: String): ByteArray? =
        synchronized(keyCache) { keyCache[code ?: GroupCrypto.PUBLIC_CODE] }?.let { k -> synchronized(k.known) { k.known[id.removePrefix("web-")]?.pub } }

    /**
     * [code] is the group on screen, and the saved list may have changed: derives the keys not seen before, reads the
     * store again only if there are any, then removes what the store cap says. Background thread (io).
     */
    private fun savedGroupsChanged(code: String) {
        if (stopped) return // queued before an erase began: no key is derived or made for it now
        val c = app ?: return
        val known = synchronized(keyCache) { keyCache.keys.toSet() }
        val a = keys(code)
        if (a.code != code) { Groups.follow(c, code, a.code); return savedGroupsChanged(a.code) } // an old code of a group saved here: that group is shown, not a second one
        val o = Groups.others(c).map { it.code }.filter { it != code }.distinct()
            .map { old -> keys(old).also { if (it.code != old) Groups.follow(c, old, it.code) } }.distinct().filter { it !== a } // an old code among the saved ones isn't kept: its group is saved too
        val fresh = (o.map { it.code } + code).any { it !in known }
        synchronized(messages) { activeCode = code; active = a; others = o }
        if (fresh) scan().let { l -> synchronized(messages) { apply(l) } } // the screen keeps working while the store is read
        allKeys = true
        (listOf(a) + o + listOfNotNull(publicKeys)).forEach(::introIfDue)
        (listOf(a) + o).forEach { rekeyIfDue(it); finishLeaves(it) }
        resendHelpOnce(listOf(a) + o)
        compact()
        quietPassNow() // every saved group's clocks are read: alerts, reminders and the alarm as they stand (also after a reboot)
        nostr?.resubscribe() // the relays now send every saved group's mailbox
        notifyUi()
    }

    /** Set once the help check-ins of this start were looked at ([resendHelpOnce]). */
    @Volatile private var helpLookedAt = false

    /**
     * Finder (decision 3): after a restart this phone has a new link id, so the finder note of an "I need help" it sent
     * before names a phone nobody can find any more. Once per start, in each saved group whose newest check-in from this
     * phone is "I need help", under 24 hours old, it goes again ([FinderResend]) with a fresh battery level and, if location
     * sharing is on, the last known position; at most every 10 minutes per group. Only where the note is written
     * ([FinderPrefs.writes]: the trial switch on (decision B of the final review), the switch on, high-risk mode off), and
     * never while an own clock message newer than that help call is held there (item 3): the re-sent check-in would be
     * dated after the clock and stop it on every phone (rule Q4). It alerts the group again, as any "I need help" does.
     * Background thread.
     */
    private fun resendHelpOnce(groups: List<Keys>) {
        if (helpLookedAt || stopped) return
        helpLookedAt = true
        val c = app ?: return
        if (!FinderPrefs.writes(c)) return
        val prefs = c.getSharedPreferences("mesh", 0); val now = System.currentTimeMillis()
        for (k in groups) {
            if (k.isPublic || k.removed || !k.joined || k.code == leaving) continue
            val (newest, clockTs) = synchronized(messages) {
                val own = messages.filter { !it.public && it.group == k.code && it.sender == deviceId }
                own.filter { CheckIn.of(it.text) != null }.maxByOrNull { it.ts } to own.filter { it.notice is Notice.Clock }.maxOfOrNull { it.ts }
            }
            if (newest == null) continue
            // This phone's own newest clock: its message, or its record when the message is gone (the group's timer, high-risk mode).
            val ownClock = listOfNotNull(clockTs, synchronized(k.quietBook) { k.quietBook.clock(k.me)?.ts }).maxOrNull()
            if (!FinderResend.due(CheckIn.of(newest.text), newest.ts, now, prefs.getLong(FinderResend.PREF + k.id, 0L), ownClockAfter = ownClock != null && ownClock > newest.ts)) continue
            prefs.edit().putLong(FinderResend.PREF + k.id, now).apply()
            val spot = if (prefs.getBoolean("shareLocation", false)) runCatching { Locator.lastKnown(c) }.getOrNull() else null
            DebugTools.log("finder: help check-in sent again after a restart")
            post(false, name, FinderResend.text(newest.text, batteryPercent(c), spot), false, to = k.code)
        }
    }

    /** Finder (rule F7): how this phone is linked to the phone whose link id is [node] right now. Any thread. */
    fun finderReach(node: String): FinderReach = when {
        node in (ble?.nodes() ?: emptySet()) -> FinderReach.BLUETOOTH
        node in nearbyNodes() || node in (wifi?.links?.nodes() ?: emptySet()) -> FinderReach.OTHER
        else -> FinderReach.NONE
    }

    /** The name of the saved group whose code is [code] ([Message.group]), for alerts and labels; null if unknown. */
    fun groupName(code: String?): String? {
        val c = app ?: return null
        if (code == null) return null
        return Groups.current(c)?.takeIf { it.code == code }?.name ?: Groups.others(c).firstOrNull { it.code == code }?.name
    }

    /** What [scan] found in the store. */
    private class Loaded(val recent: List<Packet>, val seen: Set<String>, val messages: List<Message>, val receipts: List<Pair<String, Message>>,
                         val index: Map<String, StoreCap.Entry>, val own: Set<String>, val ownReceipts: Set<String>,
                         val receiptIds: Set<String>, val future: List<String>, val pending: List<Pending>,
                         /** This phone's own group messages: group id and message id ([Deliveries.sent]). [receipts] carry their group's id too. */
                         val sent: List<Pair<String, String>>,
                         /** Chat row -> what was signed there ([GroupCrypto.Opened.mark]): a message is shown once, whatever packets bring it. */
                         val marks: Map<String, String> = emptyMap(),
                         /** The rows that opened as a saved group's message or receipt, with when each reached this phone ([KeepLess]). */
                         val kept: Map<String, KeepLess.Held> = emptyMap(),
                         /** Checked reactions (spec "Reactions"), never rows: the name each packet goes by, what it says, and what was signed. */
                         val reacted: List<Triple<String, Reactions.Entry?, String>> = emptyList())

    /**
     * Reads the database one row at a time with every key known so far, without holding the messages lock (the
     * screen keeps working meanwhile). Also returns what the router keeps passing on (the newest 500 of the last 7
     * days) and every id it has seen in that time. Background thread.
     */
    private fun scan(): Loaded {
        val d = db ?: return Loaded(emptyList(), emptySet(), emptyList(), emptyList(), emptyMap(), emptySet(), emptySet(), emptySet(), emptyList(), emptyList(), emptyList())
        val now = System.currentTimeMillis(); val cut = now - WEEK
        val recent = ArrayDeque<Packet>(); val seen = HashSet<String>()
        val msgs = ArrayList<Message>(); val recs = ArrayList<Pair<String, Message>>(); val idx = HashMap<String, StoreCap.Entry>(); val opened = ArrayList<Seen>()
        val sent = ArrayList<Pair<String, String>>()
        val copies = HashMap<String, ByteArray>()
        d.rawQuery("SELECT id,data FROM h", null).use { q -> while (q.moveToNext()) copies[q.getString(0)] = q.getBlob(1) }
        val own = HashSet<String>(); val ownReceipts = HashSet<String>(); val rIds = HashSet<String>(); val future = ArrayList<String>()
        val goneNow = synchronized(messages) { gone.keys() }
        val arrived = HashMap<String, Pair<Long, Long>>(); val held = HashMap<String, KeepLess.Held>() // row -> when it reached this phone, and its header time
        d.rawQuery("SELECT id,ts,ttl,cipher,at FROM p ORDER BY ts", null).use { q ->
            while (q.moveToNext()) {
                val name = q.getString(0) // what the packet goes by: its id, or its key if another packet had that id first
                val p = MeshRouter.clamp(Packet(StoreCap.packetId(name), q.getLong(1), q.getInt(2), q.getBlob(3)))
                if (MeshRouter.inFuture(p.ts, now)) { future += name; continue }
                val key = MeshRouter.key(p)
                if (p.ts >= cut) { seen += key; recent.addLast(p); if (recent.size > 500) recent.removeFirst() }
                // High-risk mode removed this packet once and it was stored again to be carried: it is never opened again ([GoneList]).
                if (key in goneNow) { idx[name] = StoreCap.Entry(name, p.ts, 18 + p.cipher.size, StoreCap.Kind.UNREADABLE); continue }
                arrived[name] = q.getLong(4) to p.ts
                // Introductions are learned at once; messages and receipts are checked after the loop, when every
                // introduction in the store is known, whatever their order. This phone's own introductions and new
                // keys count as written here, like its receipts: after a restart too they go up on their group's
                // relay links, not with the packets it passes on (Nostr.scope).
                // What came through a group's door is the group's own only from someone in it ([doorKind]).
                val kind = when (val x = copies[name]?.let { fromHistory(name, it) } ?: see(p)) {
                    is Seen.Intro -> { if (x.id == x.k.me) ownReceipts += p.id; learn(x.k, x.id, x.pub, x.key, x.enc, x.name, x.ts, x.pq); if (x.k.isPublic) StoreCap.Kind.PUBLIC else doorKind(x.k, x.id, true) }
                    is Seen.NewKey -> { val ok = signed(x); if (x.o.message.sender == x.k.me && ok) ownReceipts += p.id; acceptRekey(x); doorKind(x.k, x.o.message.sender, ok) }
                    is Seen.Msg -> {
                        if (x.m.offRadio) rIds += key // a reaction or the timer's notice stays off LoRa, like a receipt
                        opened += if (x.m.id == name) x else Seen.Msg(x.m.copy(id = name), x.k, x.o, x.fromHistory); if (x.m.public) StoreCap.Kind.PUBLIC else StoreCap.Kind.GROUP
                    }
                    is Seen.Receipt -> { opened += Seen.Receipt(x.r, x.k, x.o, name); rIds += key; StoreCap.Kind.GROUP }
                    else -> StoreCap.Kind.UNREADABLE
                }
                idx[name] = StoreCap.Entry(name, p.ts, 18 + p.cipher.size, kind)
            }
        }
        val waiting = ArrayList<Pending>(); val spent = HashSet<Keys>(); val marks = HashMap<String, String>()
        val reacted = ArrayList<Triple<String, Reactions.Entry?, String>>()
        // The packet stored under an id first: another packet that brings the same message again is not a second row
        // ([apply]), and one that names this phone as its sender is never a row of its own.
        for (x in opened.sortedBy { it is Seen.Msg && it.m.id.length > 16 }) when (x) {
            // A reaction (spec "Reactions"), or the timer's notice (spec "Disappearing messages"): kept and checked like any group message, but never a row.
            is Seen.Msg -> if (x.m.id.length > 16 && x.m.sender == deviceId) Unit else if (x.m.silent) when (check(x.k, x.o, x.fromHistory)) {
                true -> {
                    // A leave note read before its key went (it had waited for its sender's key): decided now. A reaction
                    // the member who left dates after their note isn't counted (rule L3).
                    if (x.m.notice == Notice.Leave) tookNote(x.k, x.o, checked = true)
                    else if (x.m.public || x.m.sender == deviceId || !leavingOf(x.k).hides(x.o.message.sender, x.m.ts)) reacted += Triple(x.m.id, reaction(x.m, x.k, x.m.id), x.o.mark)
                    if (x.m.sender == deviceId) ownReceipts += StoreCap.packetId(x.m.id) // written here: up to the relays first, like a receipt; never "Sent" or "Delivered"
                    if (!x.fromHistory) remember(x.m.id, x.k, x.o)
                    if (x.o.leaf != null) { x.o.leaf?.forget(); spent += x.k }
                    hold(held, arrived, x.m.id, x.k, x.m)
                }
                null -> { waiting += Pending(x.k, x.o, null, reaction = x.m.id to reaction(x.m, x.k, x.m.id)); if (!x.fromHistory) remember(x.m.id, x.k, x.o); hold(held, arrived, x.m.id, x.k, x.m) }
                false -> idx[x.m.id]?.let { e -> idx[e.id] = StoreCap.Entry(e.id, e.ts, e.size, StoreCap.Kind.UNREADABLE) }
            } else when (check(x.k, x.o, x.fromHistory)) {
                true -> { msgs += x.m; marks[x.m.id] = x.o.mark; if (x.m.sender == deviceId) { own += x.m.id; if (!x.m.public) sent += x.k.id to x.m.id }; if (!x.fromHistory) remember(x.m.id, x.k, x.o); if (x.o.leaf != null) { x.o.leaf?.forget(); spent += x.k }; hold(held, arrived, x.m.id, x.k, x.m)
                    if (!x.m.public) quietSaw(x.k, x.o, true) } // the clocks are rebuilt from what is stored too, the newer winning (rule Q10)
                null -> { msgs += x.m.copy(verified = false); marks[x.m.id] = x.o.mark; waiting += Pending(x.k, x.o, null, x.m.id); if (!x.fromHistory) remember(x.m.id, x.k, x.o); hold(held, arrived, x.m.id, x.k, x.m) }
                false -> idx[x.m.id]?.let { e -> idx[e.id] = StoreCap.Entry(e.id, e.ts, e.size, StoreCap.Kind.UNREADABLE) }
            }
            is Seen.Receipt -> when (check(x.k, x.o)) {
                true -> { recs += x.k.id to x.r; if (x.r.sender == deviceId) ownReceipts += x.r.id; hold(held, arrived, x.name, x.k, null); quietReceipt(x.k, x.r) }
                null -> { waiting += Pending(x.k, x.o, x.r); hold(held, arrived, x.name, x.k, null) }
                false -> idx[x.name]?.let { e -> idx[e.id] = StoreCap.Entry(e.id, e.ts, e.size, StoreCap.Kind.UNREADABLE) } // a forged receipt is not the group's own, like a message that doesn't check (mirrors the line above)
            }
            else -> {}
        }
        spent.forEach(::saveMessageKeys)
        return Loaded(recent.toList(), seen, msgs, recs, idx, own, ownReceipts, rIds, future, waiting, sent, marks, held, reacted)
    }

    /**
     * [scan] notes a stored packet that opened as a saved group's message ([m]) or receipt (null) and whose signature
     * didn't fail, with when it reached this phone: what high-risk mode's 24 hours count for ([KeepLess]). Decided by what
     * the packet opened as; Everyone's messages and everything else in the store are never noted.
     */
    private fun hold(into: HashMap<String, KeepLess.Held>, arrived: Map<String, Pair<Long, Long>>, name: String, k: Keys, m: Message?) {
        if (k.isPublic || m?.public == true) return
        val (at, ts) = arrived[name] ?: return
        val unsent = m != null && m.sender == deviceId && synchronized(ids) { m.id !in handedOff }
        into[name] = KeepLess.Held(KeepLess.arrival(at, m?.ts ?: ts, unsent), ts, k.id)
    }

    /**
     * The class of a packet [k]'s door opened ([StoreCap.door]): the group's own only if someone in the group wrote it,
     * or this phone. [signed]: the packet names [by] and their signature checks. The door opens with the code alone, so
     * anything else gets the small room of people who aren't in ([StoreCap.Kind.DOOR]) and can't push a message out.
     */
    private fun doorKind(k: Keys, by: String, signed: Boolean) = StoreCap.door(signed && by == k.me, signed && k.ring.inGroup(by))

    /**
     * Adds what [scan] found to what is shown (packets that arrived meanwhile stay, none shows twice), and removes
     * packets dated more than 10 minutes ahead. Hold the messages lock.
     */
    private fun apply(l: Loaded) {
        if (l.future.isNotEmpty()) { delete(l.future); DebugTools.log("store: removed ${l.future.size} packets dated in the future") }
        synchronized(ids) { mine += l.own; myReceipts += l.ownReceipts; l.receiptIds.forEach { receiptIds.add(it) } }
        val added = HashSet<String>()
        for (m in l.messages) {
            val mark = l.marks[m.id]
            if (mark != null && shownAs[mark].let { it != null && it != m.id }) continue // another packet already shows this very message
            if (messageIds.add(m.id)) { messages += m; added += m.id }
            if (mark != null) shownAs[mark] = m.id
        }
        if (added.isNotEmpty()) messages.sortBy { it.ts }
        for ((group, id) in l.sent) deliveries.sent(group, id) // before the receipts: one counts only for a message written here, in its group
        for ((group, r) in l.receipts) deliveries.add(group, r)
        for ((name, entry, mark) in l.reacted) {
            if (!shownAs.containsKey(mark)) shownAs[mark] = name // the same reaction sealed again isn't kept twice
            if (entry != null) reactions.add(name, entry)
        }
        // A message waits only if its row was made just now from the stored packet: a row that was there already may show another packet with that id.
        for (w in l.pending) {
            if (w.receipt != null || w.reaction != null) unchecked.receipt(w.o.key, w.k, w.o.message.sender, w)
            else { val row = w.row ?: w.o.message.id; unchecked.message(w.o.key, w.k, w.o.message.sender, row, row in added, w) } // bind to the row it is shown as (its store name), not the shared packet id
        }
        index.putAll(l.index) // read with the newest keys: a packet may have become readable
        kept.putAll(l.kept)
        // A message of this phone's that a link took, but whose hand-off time was not saved (the app ended in that moment): it counts from now.
        l.kept.filter { it.value.at == KeepLess.UNSENT && synchronized(ids) { it.key in handedOff } }.keys.forEach(::handedOffNow)
        indexBytes = index.values.sumOf { it.size.toLong() }
        index.values.filter { it.kind == StoreCap.Kind.DOOR }.let { d -> doorCount = d.size; doorBytes = d.sumOf { it.size.toLong() } }
    }

    /**
     * Keeps the store small, once every saved group's key has been tried: packets older than 7 days that no saved group
     * can read go (phones stop passing them on after 7 days anyway), and so do those that came through a group's door
     * from someone who isn't in it; Public packets beyond 2,000 go (no sender more than its share: StoreCap.everyone),
     * and then the store cap (StoreCap: about 40 MB and 20,000 packets, unreadable packets first, and the door's own
     * small room). Background thread.
     */
    private fun compact() {
        if (!allKeys || stopped) return
        val now = System.currentTimeMillis()
        val expired = HashMap<String, Pair<List<Keyring.Epoch>, List<Keyring.Epoch>>>() // group id -> the epochs deleted now, and those still held
        synchronized(keyCache) { keyCache.values.toList() }.forEach { k -> // keys replaced more than 7 days ago go (v0.6), from the vault too
            // A removed phone still deletes its replaced encryption keys on time (spec "Changing keys"); it only stops introducing itself.
            if (k.removed && !k.isPublic) k.encs.rotateIfDue(clock())
            val gone = k.ring.expire(now)
            if (gone.isNotEmpty()) { saveRing(k); k.oldDoors = k.oldDoors.filter { d -> k.ring.all().any { it.via == d.code } }; expired[k.id] = gone to k.ring.all() }
        }
        val stale = copiesOf(expired)
        // The groups' timers (spec "Disappearing messages"): group id -> how long a message stays on this phone, for the
        // groups whose value has settled. The keyring is asked again first: a value that changed a moment ago starts its
        // ten minutes here at the latest, and deletes nothing in this pass. By the clock the keys and high-risk mode count with.
        val clockNow = clock()
        val timers = HashMap<String, Long>()
        for (k in groupsHeld()) { observeTimer(k, clockNow); k.watch.deletes(clockNow)?.let { timers[k.id] = it } }
        val faded = HashSet<String>()
        val gone = synchronized(messages) {
            val cut = System.currentTimeMillis() - WEEK
            val drop = LinkedHashSet<String>()
            index.values.filter { (it.kind == StoreCap.Kind.UNREADABLE || it.kind == StoreCap.Kind.DOOR) && it.ts < cut }.mapTo(drop) { it.id }
            drop += stale // the messages of an epoch deleted now: their packets no longer open either
            val listed = HashSet<String>() // what goes onto the gone list in this pass
            keepLessNow().let { drop += it; listed += it } // high-risk mode: what reached this phone more than a day ago
            // A group's timer: whichever of the two rules is due first removes.
            timerNow(timers, drop, clockNow).let { (out, carried) -> drop += out; listed += out; listed += carried; faded += carried }
            forgetGone(gone.expire(now)) // names of packets too old for any phone to bring again
            val pub = index.values.filter { it.kind == StoreCap.Kind.PUBLIC }
            if (pub.size > MAX_PUBLIC) {
                // Not simply the oldest: one sender can't push everyone else's messages and places out (StoreCap.everyone).
                // A Public packet that isn't a message is an introduction; this phone's own are the ones it wrote.
                val shown = messages.filter { it.public }.associateBy { it.id }
                val written = synchronized(ids) { pub.filter { it.id in myReceipts }.mapTo(HashSet()) { it.id } }
                drop += StoreCap.everyone(pub.map { e ->
                    val m = shown[e.id]
                    StoreCap.Held(e.id, e.ts, if (m == null) "introductions" else if (m.verified) m.sender.removePrefix("web-") else "not verified",
                        if (m == null) e.id in written else m.sender == deviceId)
                }, MAX_PUBLIC)
            }
            // The timer's packets that stay to be carried: what this phone could read or show of them goes all the same.
            faded -= drop
            forgetStored(faded, packets = false, listed = listed)
            drop += StoreCap.overflow(index.values.filter { it.id !in drop })
            forgetStored(drop, listed = listed)
            drop.size
        }
        compacting = false
        if (faded.isNotEmpty()) { DebugTools.log("timer: ${faded.size} message(s) gone, their packets still carried"); notifyUi() }
        if (gone > 0) { DebugTools.log("store: removed $gone packets"); notifyUi() }
        scheduleDue()
    }

    /**
     * The groups' timers ([Disappear]), [timers]: group id -> ms, for the groups whose value has settled. What has been on
     * this phone that long goes, counted from the same arrival times as high-risk mode's day ([KeepLess.Held.at]: from when
     * a packet reached this phone, or, written here, first left it; one that hasn't left never goes), and onto the same gone
     * list ([GoneList], table g). [skip]: what goes in this pass anyway. Returns the names that leave the store (a key here
     * still opens them: a receipt, a message sealed with the epoch's key or still waiting for its sender's key) and those
     * whose packets stay as ones this phone only carries for the rest of their week ([Disappear.carries]), so that a member
     * who was away still gets the message. Hold the messages lock.
     */
    private fun timerNow(timers: Map<String, Long>, skip: Set<String>, now: Long): Pair<Set<String>, Set<String>> {
        if (timers.isEmpty()) return emptySet<String>() to emptySet()
        val due = timers.flatMap { (group, ms) -> Disappear.due(kept.filter { it.value.group == group && it.key !in skip }.mapValues { it.value.at }, ms, now) }
        if (due.isEmpty()) return emptySet<String>() to emptySet()
        val (carried, out) = due.partition { Disappear.carries(stillOpens(it)) } // asked before anything of theirs is forgotten
        putGone(due)
        return out.toHashSet() to carried.toHashSet()
    }

    /** True if a key this phone holds still opens the stored packet named [name]. Hold the messages lock. */
    private fun stillOpens(name: String): Boolean = try {
        db?.rawQuery("SELECT ts, ttl, cipher FROM p WHERE id = ?", arrayOf(name))?.use { q ->
            q.moveToNext() && see(MeshRouter.clamp(Packet(StoreCap.packetId(name), q.getLong(0), q.getInt(1), q.getBlob(2)))) != null
        } ?: false
    } catch (e: RuntimeException) { DebugTools.log("store: can't read a packet: $e"); false }

    /**
     * Removes the stored packets and chat rows [drop] names: the rows of both tables, what is shown, and what waited for
     * them, confirmed them or alerted for them. [packets] false: the packets themselves stay, as ones this phone only
     * carries (a group's timer, [compact]); everything this phone could read or show of them goes all the same. [listed]:
     * the names that went onto the gone list (high-risk mode, a group's timer): one of them still waiting for its sender's
     * key loses its message key too (spec "Disappearing messages"). Hold the messages lock.
     */
    private fun forgetStored(drop: Collection<String>, packets: Boolean = true, listed: Set<String> = emptySet()) {
        if (drop.isEmpty()) return
        if (packets) delete(drop.toList()) else deleteCopies(drop.toList())
        for (id in drop) {
            kept.remove(id)
            if (packets) index.remove(id)?.let { indexBytes -= it.size; if (it.kind == StoreCap.Kind.DOOR) { doorCount--; doorBytes -= it.size } }
            else index[id]?.let { e -> index[id] = StoreCap.Entry(e.id, e.ts, e.size, StoreCap.Kind.UNREADABLE) } // a packet this phone can't read: carried for its week
        }
        val gone = drop as? Set<String> ?: drop.toHashSet()
        messages.removeAll { it.id in gone }; messageIds.removeAll(gone); shownAs.values.removeAll(gone)
        val waited = unchecked.forget(gone); deliveries.forget(gone) // nothing waits for, or confirms, a message that is gone
        // A message that went by a rule while it waited for its sender's key: its own key, still held, goes now. Brought back
        // by a neighbour it is on the gone list and never opened again anyway.
        val spent = HashSet<Keys>()
        for (e in waited) if (e.row != null && e.row in listed) e.item.o.leaf?.let { it.forget(); spent += e.item.k }
        spent.forEach(::saveMessageKeys)
        reactions.forget(gone) // a reaction counts while its packet is here
        app?.let { Notifier.forget(it, gone) } // nor does an alert show its text
    }

    /**
     * High-risk mode's "keeps less" ([KeepLess]): the names of what leaves this phone now, or nothing when the mode is off.
     * - What goes: the saved groups' messages (the chat row, the packet that carried it and this phone's readable copy)
     *   and receipts, 24 hours after they reached this phone; a message written here, 24 hours after a link first took it,
     *   and never before one did.
     * - What stays under the usual rules: Everyone posts; packets this phone only carries and can't open; and the groups'
     *   introductions and new keys: without those, a member away for more than a day could never get the new key from
     *   this phone. They are not in [kept] at all, which lists packets by what they opened as, so sharing a mailbox with a
     *   message never removes one.
     * Each name is put on the gone list first ([GoneList], saved in table g), so that a copy another phone still carries
     * is not read again; the caller then deletes the rows ([forgetStored]). The clock is the clean-up's own (tests can
     * move theirs forward, [clock]); a clock moved forward makes messages leave early ([KeepLess]). Hold the messages lock.
     */
    private fun keepLessNow(): Set<String> {
        val c = app ?: return emptySet()
        if (!HighRisk.on(c)) return emptySet()
        val due = KeepLess.due(kept, clock())
        if (due.isEmpty()) return emptySet()
        putGone(due)
        return due.toHashSet()
    }

    /** Puts the stored packets [due] names on the gone list ([GoneList]), in memory and in table g. Hold the messages lock. */
    private fun putGone(due: Collection<String>) {
        val names = due.mapNotNull { name -> val h = kept[name]; val key = packetKey(name); if (h == null || key == null) null else GoneList.Entry(key, h.ts, h.group) }
        val out = gone.add(names)
        try {
            db?.let { d ->
                d.beginTransaction()
                try {
                    for (e in names) d.insertWithOnConflict("g", null, ContentValues().apply { put("key", e.key); put("ts", e.ts); put("grp", e.group) }, SQLiteDatabase.CONFLICT_REPLACE)
                    d.setTransactionSuccessful()
                } finally { d.endTransaction() }
            }
        } catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't save the gone list: $e") }
        forgetGone(out)
    }

    /** Takes [keys] off the saved gone list (they are off the one in memory already). Hold the messages lock. */
    private fun forgetGone(keys: List<String>) {
        if (keys.isEmpty()) return
        try { keys.chunked(500).forEach { c -> db?.delete("g", "key IN (${c.joinToString(",") { "?" }})", c.toTypedArray()) } }
        catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't trim the gone list: $e") }
    }

    /** The gone list as it was saved, without the names that are too old by now. At the start, before the store is read. */
    private fun loadGone() = synchronized(messages) {
        try {
            val rows = ArrayList<GoneList.Entry>()
            db?.rawQuery("SELECT key,ts,grp FROM g", null)?.use { q -> while (q.moveToNext()) rows += GoneList.Entry(q.getString(0), q.getLong(1), q.getString(2)) }
            forgetGone(gone.add(rows) + gone.expire(System.currentTimeMillis()))
        } catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't read the gone list: $e") }
    }

    /**
     * Runs high-risk mode's 24-hour rule now, on its own: when the mode is switched on and each time the app comes to the
     * front (the hourly clean-up runs it too). It deletes rows and nothing else: it doesn't read the store again.
     */
    fun keepLess() = io.execute {
        if (!allKeys || stopped) return@execute // the other groups' keys are still being made: the clean-up that follows runs the rule
        val n = synchronized(messages) { keepLessNow().also { forgetStored(it, listed = it) }.size }
        if (n > 0) { DebugTools.log("keeps less: removed $n packets"); notifyUi() }
    }

    /**
     * The ids of the copies in table h that go with the epochs [compact] just deleted ([History.goesWith]): group id ->
     * the epochs deleted, and those still held. Background thread.
     */
    private fun copiesOf(expired: Map<String, Pair<List<Keyring.Epoch>, List<Keyring.Epoch>>>): List<String> {
        if (expired.isEmpty()) return emptyList()
        val d = db ?: return emptyList(); val key = historyKey ?: return emptyList()
        val out = ArrayList<String>()
        runCatching {
            d.rawQuery("SELECT id,data FROM h", null).use { q ->
                while (q.moveToNext()) {
                    val id = q.getString(0); val s = History.open(key, id, q.getBlob(1)) ?: continue
                    val (gone, held) = expired[s.group] ?: continue
                    if (History.goesWith(s, gone, held)) out += id
                }
            }
        }
        return out
    }

    /** The store grew well past its cap while running: trim it in the background. */
    private fun compactIfFull() {
        if (compacting || !allKeys) return
        val full = synchronized(messages) { indexBytes > StoreCap.MAX_BYTES * 11 / 10 || index.size > StoreCap.MAX_COUNT + 1_000 || StoreCap.doorFull(doorCount, doorBytes) }
        if (full) { compacting = true; io.execute(::compact) }
    }

    /** Deletes this phone's readable copies (table h) of [ids], leaving their packets in the store. Hold the messages lock. */
    private fun deleteCopies(ids: List<String>) {
        val d = db ?: return
        try { ids.chunked(500).forEach { c -> d.delete("h", "id IN (${c.joinToString(",") { "?" }})", c.toTypedArray()) } }
        catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't delete copies: $e") }
    }

    /** Hold the messages lock. */
    private fun delete(ids: List<String>) {
        val d = db ?: return
        d.beginTransaction()
        try {
            ids.chunked(500).forEach { c -> d.delete("p", "id IN (${c.joinToString(",") { "?" }})", c.toTypedArray()); d.delete("h", "id IN (${c.joinToString(",") { "?" }})", c.toTypedArray()) }
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    /**
     * Keeps [p] and returns the name it goes by ([StoreCap.name]). The first packet stored under an id goes by that id;
     * any other packet with that id is kept too, by its key. Nothing replaces anything: whoever sends junk, or a message
     * of their own, first under an id doesn't keep the real packet out of the store. [at]: when it reached this phone,
     * by the clock high-risk mode counts with ([KeepLess]). Hold the messages lock.
     */
    private fun save(p: Packet, kind: StoreCap.Kind, at: Long = clock()): String {
        var name = p.id
        if (index.containsKey(p.id)) {
            name = StoreCap.name(p, false)
            if (packetKey(p.id) == name) return p.id // this very packet is the one stored under its id
            if (index.containsKey(name)) return name
        }
        try {
            db?.insertWithOnConflict("p", null, ContentValues().apply {
                put("id", name); put("ts", p.ts); put("ttl", p.ttl); put("cipher", p.cipher); put("at", at)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        } catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't save a packet: $e"); return name } // a full disk must not end the app
        val e = StoreCap.Entry(name, p.ts, 18 + p.cipher.size, kind)
        index[name] = e; indexBytes += e.size
        if (kind == StoreCap.Kind.DOOR) { doorCount++; doorBytes += e.size }
        return name
    }

    /**
     * Called by the router while it holds its lock: a copy of a stored packet came with more hops left. The saved ttl
     * is raised too, or a restart would pass the packet on with the first copy's. Only the row of this very packet:
     * the one stored under its id may be another, and this one then goes by its key ([StoreCap.name]).
     */
    private fun onRaised(p: Packet) {
        try { db?.execSQL("UPDATE p SET ttl = ? WHERE (id = ? OR id = ?) AND ts = ? AND ttl < ? AND cipher = ?", arrayOf<Any>(p.ttl, p.id, MeshRouter.key(p), p.ts, p.ttl, p.cipher)) }
        catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't raise a ttl: $e") }
    }

    /** Called by the router while it holds its lock: every transport only queues the packet. */
    private fun route(peer: String, p: Packet) {
        val lora = peer == LoraLink.PEER || peer == MeshCoreLink.PEER
        // Receipts and reactions stay off LoRa, where airtime is scarce. Decided for this very packet (its key, a hash of
        // the whole packet), never for its id: one sent under the id of a message must not keep that message off the radio.
        if (lora && MeshRouter.key(p).let { k -> synchronized(ids) { k in receiptIds } }) return
        // For the relays and the radios: did this phone write this very packet, or did a link bring it? Its id alone
        // doesn't say: anyone can send another packet under the id of one of this phone's (MeshRouter.source).
        val from = if (lora || NostrLink.owns(peer)) router?.source(p) else null
        val own = from == "" && synchronized(ids) { p.id in mine || p.id in myReceipts }
        when {
            WifiLinks.owns(peer) -> wifi?.links?.send(peer, p)
            // Written on this phone (a message or a receipt): goes up first. "Sent" once a relay accepts it.
            NostrLink.owns(peer) -> { nostr?.send(p, mine = own, from = from ?: ""); return }
            // The radios send this phone's own packets first (LoraQueue).
            peer == LoraLink.PEER -> (this.lora ?: return).offer(p, own)
            peer == MeshCoreLink.PEER -> (meshcore ?: return).offer(p, own)
            else -> (sender ?: return)(peer, p)
        }
        if (lora && !(if (peer == LoraLink.PEER) Meshtastic.fits(p) else MeshCore.fits(p))) return // too long for the radio: not really sent
        markHandedOff(p.id, Journey.Link.ofPeer(peer))
        if (synchronized(ids) { p.id in myReceipts }) handedOffNow(p.id) // a reaction written here: high-risk mode's day starts when it leaves
    }

    /**
     * One of my messages left this phone: to a phone, a radio or the internet relays ("Sent"). Saved on the phone, so the
     * label survives restarts, and with it over which kind of link it first left and when ("How it got there"): the kind
     * only, never which phone or link.
     */
    private fun markHandedOff(id: String, link: Journey.Link? = null) {
        val saved = synchronized(ids) {
            if (id !in mine || !handedOff.add(id)) return@synchronized null
            link?.let { Journey.Left(it, System.currentTimeMillis()).saved }?.let { handedOffOver[id] = it }
            if (handedOffOver.size > handedOff.size) handedOffOver.keys.retainAll(handedOff.toList().toSet()) // forgotten with the "Sent" label
            handedOff.toList() to saveOver(handedOffOver)
        } ?: return
        handedOffNow(id)
        app?.getSharedPreferences("mesh", 0)?.edit()?.putString("handedOff", saved.first.joinToString(","))?.putString(OVER_PREF, saved.second)?.apply()
        notifyUi()
    }

    private const val OVER_PREF = "handedOffOver"
    /** "<id>=<letter><ms>,…": over what each of this phone's messages first left ([Journey.Left.saved]). */
    private fun saveOver(m: Map<String, String>) = m.entries.joinToString(",") { "${it.key}=${it.value}" }
    private fun parseOver(s: String): Map<String, String> = s.split(',').mapNotNull { e ->
        val i = e.indexOf('='); if (i <= 0) null else (e.substring(0, i) to e.substring(i + 1)).takeIf { Journey.Left.parse(it.second) != null }
    }.toMap()

    /** High-risk mode's 24 hours for a message written here start now, when a link first takes it ([KeepLess.UNSENT] until then). */
    private fun handedOffNow(id: String) = synchronized(messages) {
        val h = kept[id]?.takeIf { it.at == KeepLess.UNSENT } ?: return@synchronized
        val at = clock()
        kept[id] = KeepLess.Held(at, h.ts, h.group)
        try { db?.execSQL("UPDATE p SET at = ? WHERE id = ? AND at = ?", arrayOf<Any>(at, id, KeepLess.UNSENT)) }
        catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't note a hand-off: $e") }
        groupsHeld().firstOrNull { it.id == h.group }?.let(::dueSoon) // the group's timer counts from now too
    }

    /**
     * A packet seen for the first time: keep it, and show it if a saved group or the Public channel can read it and
     * its signature checks (or its sender's key isn't known yet: shown as not verified, checked when it comes).
     */
    private fun onDeliver(p: Packet) {
        var incoming: Message? = null; var receiptKey: Keys? = null; var newEpoch = false; var note: Journey.Note? = null
        // High-risk mode removed this very packet and another phone brings it again: it is carried, never read ([GoneList]).
        // The router has it for the phones linked now either way. It goes back into the store only if nothing on this phone
        // opens it any more (a message's own key was deleted when it was read); what a held key would still open, a receipt
        // or a message without a key of its own, is not put back on the disk.
        val again = synchronized(messages) {
            if (gone.size == 0) GoneList.Arrival.READ
            else gone.arrival(MeshRouter.key(p)) { see(p) != null }.also { if (it == GoneList.Arrival.CARRY) save(p, StoreCap.Kind.UNREADABLE) }
        }
        if (again != GoneList.Arrival.READ) { DebugTools.log("${p.id} again: on the gone list (high-risk mode or a group's timer), only carried"); compactIfFull(); return }
        synchronized(messages) {
            when (val x = see(p)) {
                is Seen.Intro -> {
                    save(p, if (x.k.isPublic) StoreCap.Kind.PUBLIC else doorKind(x.k, x.id, true)) // through a group's door: the group's own only from someone in it
                    val fresh = learn(x.k, x.id, x.pub, x.key, x.enc, x.name, x.ts, x.pq)
                    if (fresh) DebugTools.log("introduction ${p.id}: a new member key")
                    resolve(x.k, x.id)
                    // Not in the current epoch: a request to join. It waits for an admin here to let them in.
                    val k = x.k
                    // At most one alert a minute in a group: the requests themselves are in the chat ([joinRequests]).
                    val at = System.currentTimeMillis()
                    if (fresh && !k.isPublic && !k.removed && x.id != k.me && x.id !in k.members && k.me in (k.ring.currentOrNull()?.admins ?: emptyList()) && x.id !in declined(k)
                        && Joining.alertDue(joinAlertAt[k.id] ?: 0L, at)) {
                        joinAlertAt[k.id] = at
                        main.post { onIncoming?.invoke(Message("join-${x.id}", System.currentTimeMillis(), x.id, x.name, "asks to join", group = k.code)) }
                    }
                }
                is Seen.NewKey -> {
                    val ok = signed(x); val was = x.k.ring.currentOrNull()
                    if (acceptRekey(x)) {
                        newEpoch = true
                        // Our introduction again, at once: the newcomers need our keys to check our messages.
                        app?.getSharedPreferences("mesh", 0)?.edit()?.remove("introAt.${x.k.id}")?.apply()
                    }
                    val k = x.k
                    // Classed once the keyring has decided: the key that lets this phone in is from someone in the group it now knows.
                    save(p, doorKind(k, x.o.message.sender, ok))
                    // Made after our current epoch, yet numbered below it: that phone missed our keys. Answered after 0-3 s unless another member did.
                    if (x.door === k.door && ok && k.ring.behind(x.r, x.o.message.sender))
                        main.postDelayed({ io.execute { answerBehind(k, x.r.made, was) } }, rnd.nextInt(3_000).toLong())
                    if (k.ring.stillToRemove().isNotEmpty()) main.postDelayed({ io.execute { finishRemovals(k) } }, rnd.nextInt(3_000).toLong())
                    sweepLeaving(k) // a member who left may be out now
                    sweepQuiet(k) // a removed member's clock goes, unless the removal followed their leave note (rule Q9)
                }
                is Seen.Msg -> {
                    // A reaction (spec "Reactions"): kept and passed on like any group message, but never a row, an alert or a
                    // receipt, and off LoRa like a receipt, whatever its signature turns out to be. So is the timer's notice,
                    // written only for apps that don't follow the timer (spec "Disappearing messages"): nothing is taken from it.
                    val silent = !x.m.public && x.m.silent
                    if (!x.m.public && x.m.offRadio) MeshRouter.key(p).let { key -> synchronized(ids) { receiptIds.add(key) } }
                    val ok = check(x.k, x.o)
                    if (ok == false) { save(p, StoreCap.Kind.UNREADABLE); DebugTools.log("dropped ${p.id}: signature doesn't check") }
                    // Another packet under an id that is taken, which only brings again a message already shown, or names this
                    // phone as its sender (the same message sealed again): nothing to keep or show.
                    else if (index.containsKey(p.id) && (shownAs.containsKey(x.o.mark) || x.m.sender == deviceId)) DebugTools.log("${p.id} again: already shown")
                    else {
                        // Any other packet under an id that is taken is a message of its own: kept and shown by its name ([StoreCap.name]).
                        val at = clock()
                        val name = save(p, if (x.m.public) StoreCap.Kind.PUBLIC else StoreCap.Kind.GROUP, at)
                        if (!x.m.public && name !in kept) { kept[name] = KeepLess.Held(at, p.ts, x.k.id); dueSoon(x.k) } // high-risk mode's day, and the group's timer, start when it arrives
                        val m = (if (ok == true) x.m else x.m.copy(verified = false)).let { if (name == p.id) it else it.copy(id = name) }
                        remember(name, x.k, x.o)
                        if (ok == true && x.o.leaf != null) { x.o.leaf?.forget(); saveMessageKeys(x.k) } // this message's key is gone: only this phone's copy can show it now
                        if (x.k.rejoining && m.sender != deviceId && m.ts >= x.k.rejoiningSince) setRejoining(x.k, 0) // someone wrote in a key this phone has: it's back
                        // Someone left (spec "Leaving the group"): a line, never a bubble, an alert or a receipt; it goes on the radio.
                        if (!m.public && m.notice == Notice.Leave) {
                            shownAs[x.o.mark] = name // the same note sealed again isn't kept twice
                            if (ok == true) tookNote(x.k, x.o, checked = true)
                            else unchecked.receipt(x.o.key, x.k, x.o.message.sender, Pending(x.k, x.o, null, reaction = name to null))
                            DebugTools.log("received ${p.id} leave note verified=${ok == true}")
                            return@synchronized
                        }
                        // What a member who left dates after their note isn't shown, alerted, confirmed or counted (rule L3).
                        if (!m.public && m.sender != deviceId && leavingOf(x.k).hides(x.o.message.sender, m.ts)) { DebugTools.log("received ${p.id}: dated after its sender's leave note, not shown"); return@synchronized }
                        if (silent) {
                            shownAs[x.o.mark] = name // the same reaction sealed again isn't kept twice
                            val entry = reaction(m, x.k, name)
                            // Counted only once its signature has checked; until its sender's key comes it waits, like a receipt.
                            if (ok == true) { if (entry != null) reactions.add(name, entry) }
                            else unchecked.receipt(x.o.key, x.k, x.o.message.sender, Pending(x.k, x.o, null, reaction = name to entry))
                            DebugTools.log("received ${p.id} ${if (m.notice != null) "timer notice" else "reaction"} verified=${ok == true} listed=${entry != null}")
                            return@synchronized
                        }
                        // A check-in clock, or a check-in that cancels one (spec "If I go quiet"): only once its signature checked (rule Q1).
                        if (!m.public && ok == true) quietSaw(x.k, x.o, true)
                        val fresh = messageIds.add(m.id)
                        if (fresh) { messages.add(messages.indexOfLast { it.ts <= m.ts } + 1, m); shownAs[x.o.mark] = m.id } // oldest first
                        // It waits for its sender's key only as the message shown under its id: another packet with that id, checked later, must not mark or remove this row.
                        if (ok == null) unchecked.message(x.o.key, x.k, x.o.message.sender, m.id, fresh, Pending(x.k, x.o, null))
                        if (fresh && !m.public && m.sender == deviceId) deliveries.sent(x.k.id, m.id) // written here before the store was lost: its receipts count again
                        DebugTools.log("received ${p.id} ${if (m.public) "public" else "group"} photo=${m.photo?.size ?: 0} voice=${m.voice?.size ?: 0} verified=${m.verified}")
                        // Not for a message already shown: another packet under its id, sealed again by a member, alerts and confirms nothing.
                        if (fresh && !m.public && m.sender != deviceId) { incoming = m; receiptKey = x.k }
                    }
                }
                is Seen.Receipt -> {
                    val ok = check(x.k, x.o)
                    val at = clock()
                    val name = save(p, StoreCap.received(ok), at) // classed by its signature, like a message: UNREADABLE until it checks, so junk receipts can't push real messages out
                    if (ok != false && name !in kept) { kept[name] = KeepLess.Held(at, p.ts, x.k.id); dueSoon(x.k) }
                    MeshRouter.key(p).let { key -> synchronized(ids) { receiptIds.add(key) } }
                    when (ok) {
                        true -> {
                            deliveries.add(x.k.id, x.r)
                            quietReceipt(x.k, x.r) // it may confirm this phone's own clock (rule Q14)
                            val confirmed = Receipt.ids(x.r.text); val mineNow = synchronized(ids) { confirmed.count { it in mine } }
                            DebugTools.log("receipt ${p.id} confirms ${confirmed.size} message(s), $mineNow of mine")
                        }
                        null -> unchecked.receipt(x.o.key, x.k, x.o.message.sender, Pending(x.k, x.o, x.r))
                        false -> DebugTools.log("dropped receipt ${p.id}: signature doesn't check")
                    }
                }
                else -> save(p, StoreCap.Kind.UNREADABLE)
            }
        }
        incoming?.let { m ->
            val key = receiptKey
            // How this first copy came (spec "Journey notes"): the hops it had left, and the kind of link that brought it.
            note = Journey.Link.ofPeer(router?.source(p))?.let { Journey.Note.of(p.ttl, it) }
            val how = note
            main.post { if (key != null) ack(p.id, key, how); onIncoming?.invoke(m) } // alerts for every saved group; confirmed by its packet id, whatever name it goes by here
        }
        // A new epoch: messages stored before it came may open now; relays follow its mailbox; we introduce ourselves.
        if (newEpoch && rescanQueued.compareAndSet(false, true)) io.execute {
            rescanQueued.set(false)
            scan().let { l -> synchronized(messages) { apply(l) } }
            nostr?.resubscribe()
            (listOfNotNull(active) + others).forEach(::introIfDue)
            notifyUi()
        }
        compactIfFull()
        notifyUi()
    }

    private fun keysOf(code: String?): Keys? = active?.takeIf { it.code == code } ?: others.firstOrNull { it.code == code }

    /**
     * What [p] is: tried with this group's keys, then the Public channel's (photos are group-only: dropped; others'
     * words filtered), then the other saved groups'. [Message.group] says which group. Nothing is checked yet, except
     * introductions, which carry their own key.
     */
    private fun see(p: Packet, with: List<Keys> = listOfNotNull(active, publicKeys) + others): Seen? {
        val box = Packet.mailbox(p.id) // every key has one mailbox: junk costs one try, not one per key this phone holds
        for (k in with) {
            for (e in k.ring.all()) {
                if (e.group.mailbox != box) continue
                e.open(p)?.let { o -> return message(k, o) }
                e.openReceipt(p)?.let { o -> return asMine(o.message, k.me)?.let { r -> Seen.Receipt(r, k, o) } ?: Seen.Forged }
            }
            // Only the Public channel reads through its door: in a group the code alone doesn't let anyone write.
            if (k.isPublic && k.door.crypto.mailbox == box) k.door.crypto.openSigned(p)?.let { o -> return message(k, o) }
            if (k.intros.mailbox == box) k.intros.openSigned(p)?.let { o -> return introduction(k, o) ?: Seen.Forged }
            if (!k.isPublic) for (d in listOf(k.door) + k.oldDoors) if (d.rekeys.mailbox == box) d.rekeys.openSigned(p)?.let { o -> // old doors: for removals that crossed
                val r = o.extra?.let(Rekey::decode) ?: return Seen.Forged
                return Seen.NewKey(k, o, r, p.id, d)
            }
        }
        return null
    }

    private fun message(k: Keys, o: GroupCrypto.Opened): Seen {
        val m = (asMine(o.message, k.me) ?: return Seen.Forged).copy(mark = About.Target.of(o)) // a sender that is no member id: like a packet nobody can open
        // Photos, voice notes and the 5th field are for groups only (spec "Photos", "Voice notes", "Replies and reactions"): dropped on the Public channel, whatever the packet carries.
        return Seen.Msg(if (k.isPublic) filtered(m.onPublic(o.said)) else m.copy(group = k.code), k, o)
    }

    /** What a reaction packet says, for [Reactions]: null for a type 0x02 message that is no reaction. [name]: what its packet goes by. */
    private fun reaction(m: Message, k: Keys, name: String): Reactions.Entry? {
        val t = (m.about as? About.Reaction)?.target ?: return null
        return Reactions.Entry(k.id, t.key, m.sender, m.text, m.ts, StoreCap.packetId(name))
    }

    /**
     * The key of this phone's readable copies ([History]) and of its stored clocks ([QuietSeal]), in the vault; made the
     * first time. One that is saved but can't be opened right now is never replaced by a new one, which would lose every
     * copy and clock sealed under it: null then, and asked for again next time (the alarm's receiver reaches for it too).
     */
    @Volatile private var historyKeyHeld: ByteArray? = null
    private val historyLock = Any()
    private val historyKey: ByteArray? get() = historyKeyHeld ?: synchronized(historyLock) {
        historyKeyHeld ?: app?.let { c ->
            Vault.get(c, "history") ?: if (c.getSharedPreferences("vault", 0).contains("history")) null
                else ByteArray(32).also(rnd::nextBytes).also { Vault.put(c, "history", it) }
        }?.also { historyKeyHeld = it }
    }

    /** Keeps a readable copy of a message opened with its own key: that key is about to be deleted. */
    private fun remember(id: String, k: Keys, o: GroupCrypto.Opened) {
        val leaf = o.leaf ?: return; val key = historyKey ?: return
        val blob = History.seal(key, id, k.id, leaf.epoch.id, o, leaf.index, leaf.counter) ?: return // a type 0x02 message that is no reaction: nothing to show, no copy
        runCatching { db?.insertWithOnConflict("h", null, ContentValues().apply { put("id", id); put("data", blob) }, SQLiteDatabase.CONFLICT_IGNORE) }
    }

    /** A message from this phone's copy, while its epoch is still held: when the epoch goes, the message goes too. */
    private fun fromHistory(id: String, blob: ByteArray): Seen.Msg? {
        val s = History.open(historyKey ?: return null, id, blob) ?: return null
        val k = (listOfNotNull(active) + others).firstOrNull { it.id == s.group } ?: return null
        val e = k.ring.all().let { all -> all.firstOrNull { it.id == s.epoch } ?: all.firstOrNull { it.hash == s.epoch } } ?: return null // by hash: copies kept before epochs had an id
        s.opened.leaf = Leaf(e, s.index, s.counter); s.opened.opener = e
        return Seen.Msg((asMine(s.opened.message, k.me) ?: return null).copy(group = k.code, mark = About.Target.of(s.opened)), k, s.opened, fromHistory = true)
    }

    private fun GroupCrypto.packet(id: String, ts: Long, cipher: ByteArray) = Packet(id, hideTime(ts, cipher), MAX_TTL, cipher)

    /** A valid introduction: the 4th field is 0x02 and a public key whose hash starts with the sender's id, and it signed it. */
    private fun introduction(k: Keys, o: GroupCrypto.Opened): Seen.Intro? {
        val e = o.extra ?: return null
        val withPq = e.size == 99 + MlKem.PUBLIC_SIZE && e[0] == 3.toByte() // 0x03, signing key (33), encryption key (65), ML-KEM key (1184)
        if (!withPq && (!k.isPublic || e.size != 99 || e[0] != 2.toByte())) return null // 0x02, without the ML-KEM key: the Public channel only
        if (withPq && MlKem.encapsulate(e.copyOfRange(99, e.size)) == null) return null  // a key nobody could seal to would block every new key
        val pub = e.copyOfRange(1, 34); val enc = e.copyOfRange(34, 99); val id = o.message.sender
        if (P256.memberId(pub) != id || P256.fromUncompressed(enc) == null || o.message.name.toByteArray().size > MAX_NAME_BYTES) return null
        val key = P256.decompress(pub) ?: return null
        return if (P256.verify(key, o.digest, o.signature)) Seen.Intro(k, id, pub, key, enc, o.message.name, o.message.ts, if (withPq) e.copyOfRange(99, e.size) else null) else null
    }

    /**
     * On this phone, its own messages carry [deviceId] as sender: one id for "me" in every group. Null for a packet whose
     * sender is no member id, or is [deviceId] itself ([Senders.shown]): nothing received may pass for written here.
     */
    private fun asMine(m: Message, me: String): Message? = Senders.shown(m.sender, me, deviceId)?.let { if (it == m.sender) m else m.copy(sender = it) }

    /** Someone else's Everyone message or place with offensive words masked (WordFilter); mine as written. */
    private fun filtered(m: Message) = if (m.sender == deviceId) m else m.copy(name = WordFilter.clean(m.name), text = WordFilter.clean(m.text))

    /**
     * Confirms a group message in the next receipt, with that group's key. Messages arriving less than 2 s apart share
     * one receipt, for at most 10 s (ReceiptBatch). Main thread.
     */
    private fun ack(id: String, key: Keys, note: Journey.Note? = null) {
        val now = SystemClock.uptimeMillis()
        if (pendingAcks.isEmpty()) { firstAck = now; main.postDelayed(::ackTimer, ReceiptBatch.QUIET_MS) }
        lastAck = now
        pendingAcks += Triple(id, key, note)
    }

    private fun ackTimer() {
        if (pendingAcks.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (ReceiptBatch.due(firstAck, lastAck, now)) main.postDelayed(::flushAcks, rnd.nextInt(ACK_SPREAD_MS).toLong()) // a receipt doesn't show how soon this phone got the message
        else main.postDelayed(::ackTimer, maxOf(10L, minOf(lastAck + ReceiptBatch.QUIET_MS, firstAck + ReceiptBatch.MAX_WAIT_MS) - now))
    }

    private fun flushAcks() {
        if (stopped) return
        val r = router ?: return
        val batch = pendingAcks.toList(); pendingAcks.clear()
        val highRisk = app?.let(HighRisk::on) ?: false // in high-risk mode a receipt says nothing about how the message came (spec "Journey notes")
        for ((k, entries) in batch.groupBy({ it.second }, { Receipt.Entry(it.first, it.third) })) for (b in Receipt.batchesOf(entries)) {
            val rc = k.receipts
            val id = Packet.newId(rc.mailbox); val ts = System.currentTimeMillis()
            val p = rc.packet(id, ts, rc.seal(id, ts, k.me, name, Receipt.text(b, highRisk)) { k.sign(it) })
            MeshRouter.key(p).let { key -> synchronized(ids) { receiptIds.add(key); myReceipts += id } }
            // A receipt written here counts for high-risk mode from now: it confirms what arrived a moment ago.
            synchronized(messages) { val at = clock(); kept[save(p, StoreCap.Kind.GROUP, at)] = KeepLess.Held(at, p.ts, k.id) }
            r.publish(p)
        }
    }

    /**
     * Returns false if the message is too long for a LoRa packet. [public]: send on the Public channel. [photo]: small
     * JPEG. [group]: the code of the saved group to send to (an answer from a notification); the group on screen if null.
     * [voice]: a voice note's frames ([Voice]), with [Voice.PLACEHOLDER] as the text; groups only, never LoRa (too big).
     */
    fun send(text: String, public: Boolean = false, photo: ByteArray? = null, group: String? = null, replyTo: Message? = null, voice: ByteArray? = null): Boolean =
        post(false, name, text, public, photo, group, replyTo, voice)

    /**
     * A Public message typed on the Wi-Fi board by someone without the app (WifiBoard.kt): someone else's, not mine.
     * In the mesh it is a packet this phone passes on: it takes no turn of this phone's own packets on the relays, and
     * all board posts together count as one link's share of the packets kept for passing on ([BOARD]).
     */
    fun sendFromBoard(who: String, text: String) { post(true, "$who (Wi-Fi board)", text, public = true) }

    /** What the router counts Wi-Fi board posts under: no link has this name. */
    const val BOARD = "board"

    /**
     * [replyTo]: the message this one answers (spec "Replies"), in the same group; anything that can't be a target is left
     * out, and the message goes as an ordinary one. Never on the Public channel. [voice]: a voice note, never on the Public
     * channel or from the Wi-Fi board: such a message is not sent at all.
     */
    private fun post(board: Boolean, from: String, text: String, public: Boolean, photo: ByteArray? = null, to: String? = null, replyTo: Message? = null,
                     voice: ByteArray? = null, clock: Long? = null, at: Long? = null): Boolean {
        if (stopped) return true
        if (voice != null && (public || board)) return true
        if (clock != null && (public || board)) return true // a check-in clock is for groups only (rule QW2)
        val r = router ?: return true
        val ts = at ?: System.currentTimeMillis()
        val p: Packet; val id: String
        // The group on screen (or [to]); right after switching to a new group its key may still be on its way: derive it here.
        val code = to ?: activeCode
        if (!public && (code.isEmpty() || code == leaving)) return true // no group in use, or it is being left: its keys must not be made again
        val group = if (public) null else active?.takeIf { it.code == code } ?: others.firstOrNull { it.code == code } ?: keys(code)
        val k = (if (public) publicKeys else group) ?: return true
        if (!public && k.removed) return true // the screen no longer offers this
        if (clock != null && (!k.joined || code == leaving)) return true // never held until this phone is let in (rule QW2)
        if (!public && !k.joined) { synchronized(waiting) { waiting.getOrPut(k.id) { ArrayList() } += Waiting(from, text, photo, voice) }; return true } // sent once an admin lets this phone in
        introIfDue(k, writing = true) // members can check this message only with this phone's key
        var quiet: Pair<About.Target?, Message>? = null
        synchronized(messages) {
            val cr = k.group
            id = Packet.newId(cr.mailbox)
            val wire = if (board) "web-${k.me}" else k.me // member ids (spec "Member ids")
            val about = if (public || board || clock != null) null else replyTo?.let { About.target(it, k.code) }?.let { About.Reply(it) }
            // Finder (decisions 1 and 2): this phone's own "I need help" carries its link id, only while the trial switch is on
            // (decision B of the final review), and not if "Let my group find this phone" is off or high-risk mode is on.
            // A check-in clock's field takes the 5th field first (sealOne puts the note only where nothing else is).
            val note = if (clock == null && FinderNote.wanted(text, public, board, photo != null, voice != null, about != null) && app?.let(FinderPrefs::writes) == true) FinderNote.field(linkId) else null
            val (cipher, mark) = sealOne(k, cr, public, id, ts, wire, from, text, photo, about, voice, fifth = clock?.let(QuietClock::field), note = note)
            p = cr.packet(id, ts, cipher)
            // A group message written here: high-risk mode's day starts only when a link takes it ([handedOffNow]).
            if (public) save(p, StoreCap.Kind.PUBLIC)
            else { save(p, StoreCap.Kind.GROUP, KeepLess.UNSENT); kept[id] = KeepLess.Held(KeepLess.UNSENT, p.ts, k.id) }
            val m = Message(id, ts, if (board) wire else deviceId, from, text, public, if (public) null else photo, if (public) null else group?.code, about = about, mark = mark,
                voice = if (public) null else voice, notice = if (public || board) null else clock?.let { Notice.Clock(it) })
            messages += if (public) filtered(m) else m; messageIds += id // Wi-Fi board posts are someone else's
            if (!public) deliveries.sent(k.id, id) // only this group's receipts confirm it
            if (!public && !board) quiet = mark to m
        }
        if (!board) synchronized(ids) { mine += id } // a board post is someone else's: on the relays it waits its turn like any packet passed on
        // This phone's own clock, or a check-in of its own that stops it (spec "If I go quiet").
        quiet?.let { (mark, m) -> QuietClock.effect(m)?.let { e -> quietApply(k, k.me, id.take(16), mark?.mark ?: "", ts, e, text) } }
        r.publish(p, if (board) BOARD else ""); notifyUi()
        return Meshtastic.fits(p)
    }

    /** A group message goes under its own key, deleted here at once: from then on only this phone's copy can show it (spec "Message keys"). */
    /**
     * [about]: the 5th field of a reply or reaction (spec "Replies and reactions"); never on the Public channel. With the
     * cipher comes what a reply or reaction to this message names ([About.Target]), for a group message.
     */
    private fun sealOne(k: Keys, cr: GroupCrypto, public: Boolean, id: String, ts: Long, wire: String, from: String, text: String, photo: ByteArray?,
                        about: About? = null, voice: ByteArray? = null, fifth: ByteArray? = null, note: ByteArray? = null): Pair<ByteArray, About.Target?> {
        val e = if (public) null else k.ring.currentOrNull(); val keys = e?.messageKeys
        val i = keys?.index(k.me) ?: -1; val n = if (i >= 0) keys?.takeNext() else null; val mk = if (n != null) keys?.key(i, n) else null
        // A finder note goes in the 5th field only where nothing else is, and never when it alone would keep the check-in off a LoRa radio (rule F4).
        val keyed = !(e == null || keys == null || n == null || mk == null)
        @Suppress("NAME_SHADOWING") val fifth = fifth ?: note?.takeIf { about == null && !public }?.let { FinderNote.radioFirst(it, wire, from, text, keyed) }
        if (e == null || keys == null || n == null || mk == null) {
            val c = cr.seal(id, ts, wire, from, text, if (public) null else photo, about = if (public) null else about, voice = if (public) null else voice, fifth = if (public) null else fifth) { k.sign(it) }
            // Sealed with the epoch's own key (this phone's numbers ran out): read back once for what names the message.
            return c to (if (public) null else cr.openSigned(Packet(id, ts, 0, c))?.let(About.Target::of))
        }
        val c = cr.sealWith(mk, i, n, id, ts, wire, from, text, photo, about = about, voice = voice, fifth = fifth) { k.sign(it) }
        var mark: About.Target? = null
        cr.openWith(mk, i, n, Packet(id, ts, 0, c), at = ts)?.let { o -> o.leaf = Leaf(e, i, n); remember(id, k, o); mark = About.Target.of(o) }
        keys.forget(i, n)
        saveMessageKeys(k)
        return c to mark
    }

    /**
     * True if this phone can write to [m]'s group now: only then are "Reply" and the reactions offered. A message that
     * can't be a target (Everyone, not checked yet, or one this phone no longer has) offers neither.
     */
    fun canAnswer(m: Message): Boolean {
        val k = held(m.group) ?: return false
        if (k.isPublic || k.removed || !k.joined || stopped) return false
        return About.target(m, k.code) != null
    }

    /**
     * The reactions under [m], as chips (spec "Reactions"). What someone blocked in this group reacts with isn't shown here,
     * like their messages. Empty for anything that can't be a target.
     */
    fun chips(m: Message): List<Reactions.Chip> {
        val t = About.target(m, m.group) ?: return emptyList()
        val g = groupId(m.group) ?: return emptyList()
        val blocked = app?.let { Moderation.blocked(it).keys } ?: emptySet()
        return synchronized(messages) { reactions.chips(g, t.key, deviceId) { blocked.isNotEmpty() && Blocks.hides(blocked, g, it) } }
    }

    /**
     * Sets this phone's reaction to [m], changes it, or takes it back when [emoji] is the one it already has (spec
     * "Reactions"). It shows at once. Nothing happens if [m] can't be reacted to or its group can't be written to. A
     * reaction is not one of this phone's messages: no "Sent", no "Delivered", never on LoRa. Background thread (it signs).
     */
    fun react(m: Message, emoji: String) {
        if (stopped || !canAnswer(m) || !ReactionList.sendable(emoji)) return
        val r = router ?: return
        val k = held(m.group) ?: return
        val target = About.target(m, k.code) ?: return
        introIfDue(k, writing = true) // members can check this reaction only with this phone's key
        val p = synchronized(messages) {
            val had = reactions.mine(k.id, target.key, deviceId)
            val text = if (had?.text == emoji) "" else emoji
            // The latest counts: later than this phone's last one, whatever the clock did meanwhile.
            val cr = k.group; val id = Packet.newId(cr.mailbox); val ts = maxOf(System.currentTimeMillis(), (had?.ts ?: 0L) + 1)
            val p = cr.packet(id, ts, sealOne(k, cr, false, id, ts, k.me, name, text, null, About.Reaction(target)).first)
            // Written here, so it goes up to the relays first; kept off LoRa. Not one of `mine`: no "Sent", no "Delivered".
            MeshRouter.key(p).let { key -> synchronized(ids) { myReceipts += id; receiptIds.add(key) } }
            save(p, StoreCap.Kind.GROUP, KeepLess.UNSENT); kept[id] = KeepLess.Held(KeepLess.UNSENT, p.ts, k.id) // high-risk mode's day starts when a link takes it
            reactions.add(id, Reactions.Entry(k.id, target.key, deviceId, text, ts, id))
            p
        }
        r.publish(p); notifyUi()
    }

    /** What "How it got there" shows for one of this phone's own group messages (spec "Journey notes"), or null for anything else. */
    class Journeyed(val steps: List<Journey.Step>, val carried: Boolean, val confirmed: Boolean)

    fun journey(m: Message, now: Long = System.currentTimeMillis()): Journeyed? {
        if (m.sender != deviceId || m.public) return null
        val readers = synchronized(messages) { deliveries.readers(m.id) }
        val (sent, left) = synchronized(ids) { (m.id in handedOff) to handedOffOver[m.id]?.let(Journey.Left::parse) }
        return Journeyed(Journey.steps(m.ts, sent, left, readers, now), Journey.wasCarried(readers), readers.isNotEmpty())
    }

    /**
     * Every message of [code]'s group this phone holds, hidden ones too: a reply's quote is looked up here ([About.targets]),
     * so that a target that is hidden on this phone says so instead of "A message this phone doesn't have".
     */
    fun groupRows(code: String?): List<Message> = synchronized(messages) { messages.filter { !it.public && it.group == code } }

    /** This group's messages and the Public channel's, oldest first. */
    fun snapshot(): List<Message> { val code = activeCode; return shown(synchronized(messages) { messages.filter { it.public || it.group == code } }) }

    /** Every saved group's messages and the Public channel's, oldest first; [Message.group] tells which group. */
    fun snapshotAll(): List<Message> = shown(synchronized(messages) { messages.toList() })

    /** Without what a member who left dates after their note (rule L3), also if it came before the note. Each group's records read once. */
    private fun shown(all: List<Message>): List<Message> {
        val books = HashMap<String, LeaveBook?>()
        fun book(code: String?) = books.getOrPut(code ?: "") { held(code)?.takeIf { !it.isPublic }?.let(::leavingOf)?.takeIf { !it.isEmpty } }
        return all.filter { m -> m.public || m.sender == deviceId || book(m.group)?.hides(m.sender, m.ts) != true }
    }

    /** "Battery saver" (Connections): slower Bluetooth search, Nearby search in bursts, fewer relay pings. Remembered. */
    fun batterySaver(c: Context) = c.getSharedPreferences("mesh", 0).getBoolean("batterySaver", false)
    fun setBatterySaver(on: Boolean) {
        if (stopped) return
        val c = app ?: return
        c.getSharedPreferences("mesh", 0).edit().putBoolean("batterySaver", on).apply()
        ble?.setSaver(on)
        if (nostr != null) setInternet(true) // relinks with the new ping interval
        notifyUi()
    }

    /** Stops every link without changing any setting, before the process ends ("Stop DarkProof"). */
    fun shutdown() {
        flushAcks()
        nostr?.stop(); nostr = null
        wifi?.stop(); wifi = null
        app?.let { Tor.stop(it) }
    }

    // MARK: Telling the group before leaving (spec/FORMAT.md, "Leaving the group")

    /**
     * Rule LW1: this phone can tell [code]'s group that it leaves: it has a current epoch there, wasn't removed and isn't
     * still waiting to be let in. Otherwise the screens say "This phone isn't let in, so My group can't be told."
     */
    fun canTell(code: String?): Boolean {
        val k = held(code) ?: return false
        return !stopped && router != null && !k.isPublic && !k.removed && k.joined && code != leaving
    }

    /** Rule LW2: this phone is the only admin of [code]'s current epoch and others are in it: it asks who runs the group before it leaves. */
    fun onlyAdmin(code: String?): Boolean {
        val k = held(code) ?: return false; val cur = k.ring.currentOrNull() ?: return false
        if (k.isPublic || k.removed || k.me !in cur.admins) return false
        val others = cur.members.filter { it != k.me }
        return others.isNotEmpty() && others.none { it in cur.admins }
    }

    /**
     * Before the group's only admin leaves (LW2): one admin new key that adds [ids] to the admins ([makeAdmin]'s path), so
     * that someone who stays can remove this phone. [done], on the main thread: true once it is made; false if it can't be
     * (a member's encryption keys haven't reached this phone).
     */
    fun handOver(code: String?, ids: List<String>, done: (Boolean) -> Unit) = io.execute {
        val k = held(code); val cur = k?.ring?.currentOrNull()
        var ok = false
        if (k != null && cur != null && !k.removed && k.me in cur.admins) {
            val add = ids.map { memberId(k, it) }.filter { it in cur.members && it !in cur.admins }.distinct()
            ok = add.isNotEmpty() && makeRekey(k, emptyList(), null, admins = cur.admins + add)
        }
        main.post { done(ok) }
        notifyUi()
    }

    /**
     * Seals and sends the leave note to [code]'s group (LW3, LW4): an ordinary group message with [LeaveNote.TEXT] and the
     * 5th field `05 01`, under this phone's message key in its current epoch, ttl 16. It goes up to the relays with this
     * phone's own packets first, and on the radios. Never a row here, and nothing is awaited (no receipt, no retry): the
     * "Sent" bookkeeping ([markHandedOff]) only tells when a link took it ([noteLeft]). This phone never removes itself: an
     * admin's phone that stays does. The packet's id, or null if this phone can't tell the group ([canTell]).
     */
    fun sendLeaveNote(code: String?): String? {
        if (!canTell(code)) return null
        val k = held(code) ?: return null; val r = router ?: return null
        val p = synchronized(messages) {
            val cr = k.group; val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
            val p = cr.packet(id, ts, sealOne(k, cr, false, id, ts, k.me, name, LeaveNote.TEXT, null, fifth = LeaveNote.field).first)
            save(p, StoreCap.Kind.GROUP, KeepLess.UNSENT); kept[id] = KeepLess.Held(KeepLess.UNSENT, p.ts, k.id)
            p
        }
        synchronized(ids) { mine += p.id } // written here: up to the relays first, and "Sent" once a link takes it
        synchronized(told) { told[k.id] = p.id }
        r.publish(p)
        DebugTools.log("leave note ${p.id} sent")
        return p.id
    }

    /**
     * Group id -> the leave note this phone sent there ([sendLeaveNote]), until the group is forgotten ([leave]): from the
     * note on, this phone makes no key in that group ([makeRekey]). In memory only.
     */
    private val told = HashMap<String, String>()

    /** The leave note this phone sent to [code]'s group and still waits on, or null: an erase that takes over a leave waits for it instead of sending a second one. */
    fun toldNote(code: String?): String? { val k = held(code) ?: return null; return synchronized(told) { told[k.id] } }

    /** Debug builds' erase that is only shown ([Wipe.dryRun]): nothing was erased, so the groups that were told make keys again. */
    fun untold() = synchronized(told) { told.clear() }

    /** True once the note [id] was handed to a phone, a radio or a relay ([markHandedOff]). Any thread. */
    fun noteLeft(id: String): Boolean = synchronized(ids) { id in handedOff }

    /** The code of a group that is being left right now ([leave]), or null. */
    @Volatile private var leaving: String? = null
    /** Codes of groups left while no engine ran ([leave]): a start still on its way with one of them does nothing. Guarded by the engine's own lock. */
    private val leftCold = HashSet<String>()

    /**
     * True if a start with [code] must do nothing: the group was left while no engine ran ([leftCold]) and isn't saved
     * again. Once it is saved again (joined anew with the same code, in the same run of the app), the entry goes and the
     * start goes on (final review 2026-11-23: the group showed but never linked until the app was restarted). Under the
     * engine's own lock.
     */
    @Synchronized private fun leftColdBlocks(c: Context, code: String): Boolean {
        if (code !in leftCold) return false
        if (Groups.find(c, code) == null) return true
        leftCold -= code
        return false
    }
    /** True while a group is being forgotten ([leave]): an erase that starts meanwhile lets that end first ([awaitLeft]). */
    val isLeaving get() = leaving != null

    /**
     * Waits up to [ms] for a group that is being forgotten ([leave]) to be done. An erase takes precedence over a leave:
     * it deletes the keys at once, then lets that last step of the leave, which only deletes on this device, end before it
     * stops the engine and removes everything. Never on the main thread.
     */
    fun awaitLeft(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000L
        while (leaving != null && System.nanoTime() < end) Thread.sleep(20)
    }

    /**
     * "Leave" (Settings): this phone forgets [code]'s group. Gone are its saved entry and the admins its invite named, every
     * key of it (the epochs and message keys, this phone's member key and encryption keys, the notes on its codes), the
     * members' keys and who was checked, blocked or turned down there, its messages with this phone's readable copies, and
     * every stored packet its keys open, what the router still held of them for passing on, and the group's names on high-risk
     * mode's gone list. Nothing is sent: the others still list this phone until an admin removes it.
     * It works the same when the engine isn't running (nothing started it yet): the store is opened and the group's id and
     * keys are worked out from [code], so that its keys, members and settings go, not only its saved entry.
     * [done] gets, on the main thread, the saved group now in use, or null if none is left. Call on the main thread.
     * [keeping]: the leave note's packet ([sendLeaveNote], rule LW3). It stays in the store and with the router: once the
     * keys are gone it is one more packet this phone carries and can't open, until its 7 days end, so the note still
     * reaches the group when phones come into reach. Its id leaves this phone's lists with the group's other messages.
     */
    fun leave(context: Context, code: String, keeping: String? = null, done: (SavedGroup?) -> Unit) {
        if (stopped) return // everything is being erased
        val c = context.applicationContext
        held(code)?.let { k -> pendingAcks.removeAll { it.second === k } } // nothing is confirmed in a group that was left
        leaving = code // nor written there from now on ([post])
        io.execute {
            // Whatever happens in here (an erase that began deletes the keys it reaches for), it ends being left: an erase
            // waits for that ([awaitLeft]).
            try {
                if (stopped) return@execute
                // With no engine running, the whole of it happens before one can start ([start] waits for this lock): the store
                // is opened here, and a start that comes meanwhile finds the group gone.
                if (router == null) synchronized(this) {
                    if (router == null) leftCold += code
                    if (app == null) app = c
                    if (db == null) runCatching { db = Db(c).writableDatabase }.onFailure { DebugTools.log("leave: can't open the store: $it") }
                    leaveNow(c, code, keeping, done)
                } else leaveNow(c, code, keeping, done)
            } finally { leaving = null }
        }
    }

    /** [leave]'s work. Background thread (io). */
    private fun leaveNow(c: Context, code: String, note: String?, done: (SavedGroup?) -> Unit) {
        run {
            val k = held(code) ?: keys(code)
            // What its keys open, read like every packet is (see), and its readable copies: found before anything is deleted.
            val drop = HashSet<String>(); val passedOn = HashSet<String>() // store names, and the same packets as the router knows them
            runCatching {
                val key = historyKey
                db?.rawQuery("SELECT id,data FROM h", null)?.use { q -> while (q.moveToNext()) { val id = q.getString(0); if (key != null && History.open(key, id, q.getBlob(1))?.group == k.id) drop += id } }
                db?.rawQuery("SELECT id,ts,ttl,cipher FROM p", null)?.use { q ->
                    while (q.moveToNext()) {
                        val name = q.getString(0); val p = Packet(StoreCap.packetId(name), q.getLong(1), q.getInt(2), q.getBlob(3))
                        if (name in drop || see(p, listOf(k)) != null) { drop += name; passedOn += MeshRouter.key(p) }
                    }
                }
            }.onFailure { DebugTools.log("leave: can't read the store: $it") }
            // The leave note's packet stays, to be carried (LW3): only what this phone could read or show of it goes.
            val noteKey = note?.let { n -> drop.remove(n); synchronized(messages) { packetKey(n) }?.also(passedOn::remove) }
            // Its clocks' alerts, by their names ([QuietAlerts]): of every member its book or its keys know, and this phone's reminder.
            val clockAlerts = (synchronized(k.quietBook) { k.quietBook.clocks.keys + k.quietBook.checkIns.keys + k.quietBook.posted.keys } +
                k.members + synchronized(k.known) { k.known.keys.toList() }).map { QuietAlerts.id(k.id, it) } + QuietAlerts.remindId(k.id)
            val wasActive = synchronized(messages) {
                if (note != null && index.containsKey(note)) forgetStored(listOf(note), packets = false)
                messages.filter { it.group == k.code }.mapTo(drop) { it.id }
                // What high-risk mode removed of this group and another phone brought back to be carried: nothing opens those
                // packets, the gone list knows them by name. They go from the store too, and the names go from the list.
                val names = gone.forgetGroup(k.id); passedOn += names
                for (n in names) { val id = StoreCap.packetId(n); if (index.containsKey(n)) drop += n else if (index.containsKey(id) && packetKey(id) == n) drop += id }
                forgetStored(drop)
                kept.values.removeAll { it.group == k.id }
                forgetGone(names); runCatching { db?.delete("g", "grp = ?", arrayOf(k.id)) } // also the names saved before the engine last started
                synchronized(k.quietBook) { k.quietBook.clocks.clear(); k.quietBook.checkIns.clear(); k.quietBook.posted.clear() } // its check-in clocks (rule Q10)
                runCatching { db?.delete("q", "grp = ?", arrayOf(k.id)) }
                Notifier.forgetGroup(c, clockAlerts) // its alerts from before the app last started, too; other groups' clock alerts stay
                runCatching { db?.delete("k", "grp = ?", arrayOf(k.id)) }
                unchecked.forgetChannel(k)
                synchronized(keyCache) { keyCache.values.removeAll { it === k } }
                others = others.filter { it !== k }
                (active === k).also { if (it) { active = null; activeCode = "" } }
            }
            // What the router still held of the group for passing on, in memory: gone too, now that no packet is read with
            // its keys any more. Outside the store's lock: the router calls into the engine with its own lock held.
            val fewer = router?.forgetWhere { MeshRouter.key(it).let { key -> key != noteKey && (key in passedOn || see(it, listOf(k)) != null) } } ?: 0
            if (fewer > 0) DebugTools.log("left a group: $fewer packet(s) no longer passed on")
            synchronized(waiting) { waiting.remove(k.id) }
            joinAlertAt.remove(k.id)
            // Its messages' ids in the lists kept beside the store: what was handed off, hidden or waits to be reported. The note's too.
            val ids = drop.mapTo(HashSet()) { StoreCap.packetId(it) } + drop + listOfNotNull(note)
            synchronized(this.ids) { mine -= ids; myReceipts -= ids; handedOff.removeAll(ids); handedOffOver.keys.removeAll(ids); handedOff.toList() to saveOver(handedOffOver) }
                .let { (list, over) -> c.getSharedPreferences("mesh", 0).edit().putString("handedOff", list.joinToString(",")).putString(OVER_PREF, over).apply() }
            Moderation.forgetMessages(c, ids); NostrReports.sent(c, ids)
            // Its keys: from the vault and the phone's key store, and from memory, so nothing left over can open anything.
            k.encs.wipe()
            MemberKeys.forget(k.id)
            for (d in k.oldDoors + k.door) Vault.put(c, "origin.${d.id}", null)
            // Everything else the vault keeps under the group's id: its epochs, message keys, opened parts, where its code moved to.
            c.getSharedPreferences("vault", 0).all.keys.filter { k.id in it }.forEach { Vault.put(c, it, null) }
            k.ring.dropAbove(-1); synchronized(k.known) { k.known.clear() }
            // And in the preferences everything under its id (its introductions' dates, who was turned down, its relay
            // marks, a removal's note), with the people checked there.
            val prefs = c.getSharedPreferences("mesh", 0)
            prefs.edit().apply { prefs.all.keys.filter { k.id in it }.forEach(::remove) }
                .putStringSet("checked", checkedSet().filterNot { it.startsWith("${k.id}|") }.toSet())
                .putStringSet("checkedInPerson", inPersonSet().filterNot { it.startsWith("${k.id}|") }.toSet()).apply()
            Moderation.forgetGroup(c, k.id)
            ReadMarks.forget(c, k.code)
            if (code != k.code) Groups.forget(c, code) // it was saved under a code the group has moved on from
            val next = Groups.forget(c, k.code)
            synchronized(told) { told.remove(k.id) }
            leaving = null
            DebugTools.log("left a group: ${drop.size} packet(s) removed")
            if (router != null && wasActive && next != null) savedGroupsChanged(next.code) else { nostr?.resubscribe(); notifyUi() } // its mailboxes are no longer asked for
            if (router != null) quietPassNow() // the alarm no longer holds anything of the group
            // With no engine running nothing can work out what the other groups still have to come: the alarm goes now, with
            // the group's words and code in it (final review 2026-11-23, item 5), and the next start arms the rest again.
            else quietOut?.arm(emptyList())
            // Nothing is said if an erase began meanwhile ([Wipe]): the group's name isn't shown again on its way out.
            main.post { if (!stopped) done(Groups.current(c)) }
        }
    }

    /**
     * Before everything on this device is erased (Wipe): the links the engine holds stop, nothing waits to be confirmed or
     * sent, and the store is let go, so that nothing is read from it, written to it or passed on any more: the router with
     * the packets it held, the links and every key in memory are let go of too. The app ends right after.
     * [closeStore]: also close the store's file, where an open file can't be deleted (a computer).
     */
    fun halt(closeStore: Boolean) {
        stopped = true
        main.removeCallbacksAndMessages(null); pendingAcks.clear()
        runCatching { quietOut?.arm(emptyList()) } // the clocks' backup alarm outlives the process: it goes now, with its words (item 5)
        listener = null; onIncoming = null; sender = null
        router = null
        runCatching { nostr?.stop() }; nostr = null
        runCatching { wifi?.stop() }; wifi = null
        app?.let { c -> runCatching { Tor.stop(c) } }
        synchronized(messages) {
            if (closeStore) runCatching { db?.close() }
            db = null
            messages.clear(); messageIds.clear(); shownAs.clear(); index.clear(); kept.clear(); deliveries = Deliveries(); reactions = Reactions()
        }
        synchronized(keyCache) { keyCache.clear() }
        active = null; others = emptyList(); publicKeys = null; activeCode = ""
        lora = null; meshcore = null; ble = null; nearbyNodes = { emptySet() }
    }

    /**
     * The first thing an erase does, before any key is deleted ([Wipe]): from this moment nothing starts, links, sends or
     * writes a setting or a key ([stopped]), so nothing reaches for a key that is about to be gone. [halt] then stops what runs.
     */
    fun stopping() { stopped = true }

    /** Distinct node ids of the phones linked directly right now (Nearby, Bluetooth LE, Wi-Fi network): each phone once. */
    fun linkedNodes(): Set<String> = nearbyNodes() + (ble?.nodes() ?: emptySet()) + (wifi?.links?.nodes() ?: emptySet())

    /** The phones among them that are linked over Bluetooth (Nearby, which starts on Bluetooth, and Bluetooth LE), for Home's dial. */
    fun bluetoothNodes(): Set<String> = nearbyNodes() + (ble?.nodes() ?: emptySet())

    /** null for other people's messages, and for my older ones that haven't gone out yet. */
    fun delivery(m: Message): Delivery? {
        if (m.sender != deviceId) return null
        if (!m.public) synchronized(messages) { deliveries.readers(m.id) }.takeIf { it.isNotEmpty() }?.let { return Delivery.Delivered(it) }
        if (synchronized(ids) { m.id in handedOff }) return Delivery.Sent
        return if (m.ts >= startedAt) Delivery.Waiting else null
    }

    fun notifyUi() = main.post { listener?.invoke() }

    // MARK: - If I go quiet (spec/FORMAT.md, "If I go quiet")

    /** Where clock alerts go: a phone's notifications and backup alarm (Notifier.setUp sets QuietAlarms). Null on the desktop: nothing is posted there yet. */
    @Volatile var quietOut: QuietOut? = null
    /** Moves the clock that check-in clocks are judged by, in ms: only debug builds set it (--es quietAhead, minutes). */
    @Volatile var quietAhead = 0L

    /** The time check-in clocks are judged by: this phone's clock. */
    fun quietNow() = System.currentTimeMillis() + quietAhead

    private fun highRiskNow() = app?.let(HighRisk::on) ?: false

    /** [k]'s clocks as the store's table q has them, at the start. */
    private fun loadQuiet(k: Keys) {
        if (k.isPublic) return
        val lines = try { db?.rawQuery("SELECT data, box FROM q WHERE grp = ?", arrayOf(k.id))?.use { q -> if (q.moveToNext()) quietRow(k.id, q.getString(0), q.getBlob(1)) else null } }
            catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't read the clocks: $e"); null } ?: return
        val b = QuietBook.parse(lines)
        synchronized(k.quietBook) { k.quietBook.clocks.putAll(b.clocks); k.quietBook.checkIns.putAll(b.checkIns); k.quietBook.posted.putAll(b.posted) }
    }

    /**
     * The lines of a row of table q for [group]: its sealed column opened ([box]), or, for a row a version before 9 saved
     * and not yet sealed ([sealPlainClocks]), its readable one ([data]). Null if neither reads (the vault's key can't be
     * had right now: the row stays as it is).
     */
    private fun quietRow(group: String, data: String?, box: ByteArray?): List<String>? = when {
        box != null -> historyKey?.let { QuietSeal.open(it, group, box) }.also { if (it == null) DebugTools.log("store: a group's clocks don't open") }
        data != null -> data.split('\n').filter { it.isNotEmpty() }
        else -> null
    }

    /**
     * Decision C: every row of table q still readable (saved by a version before 9) is sealed, and its readable copy goes
     * from the row; SQLite's secure delete overwrites what the row held before, as far as the file goes. Rows are left as
     * they are while the vault's key can't be had. At every start of the engine and when the alarm rings without it. Any thread.
     */
    internal fun sealPlainClocks() {
        val d = db ?: return
        val plain = ArrayList<Pair<String, String>>()
        try { d.rawQuery("SELECT grp, data FROM q WHERE box IS NULL AND data IS NOT NULL", null).use { q -> while (q.moveToNext()) plain += q.getString(0) to q.getString(1) } }
        catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't read the clocks: $e"); return }
        if (plain.isEmpty() || historyKey == null) return
        runCatching { d.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToNext() } }
        for ((g, data) in plain) saveQuiet(g, data.split('\n').filter { it.isNotEmpty() })
        runCatching { d.rawQuery("PRAGMA secure_delete = OFF", null).use { it.moveToNext() } }
        DebugTools.log("store: ${plain.size} group(s) of clocks sealed")
    }

    /** Every group's clocks as table q holds them, by group id; null if the table can't be read. */
    private fun quietBooks(d: SQLiteDatabase): Map<String, QuietBook>? {
        val books = HashMap<String, QuietBook>()
        try { d.rawQuery("SELECT grp, data, box FROM q", null).use { q -> while (q.moveToNext()) { val g = q.getString(0); quietRow(g, q.getString(1), q.getBlob(2))?.let { books[g] = QuietBook.parse(it) } } } }
        catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("quiet: can't read the clocks: $e"); return null }
        return books
    }

    /** A copy of [k]'s clocks. Any thread. */
    private fun quietOf(k: Keys): QuietBook = synchronized(k.quietBook) { k.quietBook.copy() }

    /** Changes [k]'s clocks; saved in table q, under the same lock so that saves can't cross, if [change] says it changed them. Any thread. */
    private fun changeQuiet(k: Keys, change: (QuietBook) -> Boolean): Boolean = synchronized(k.quietBook) {
        if (!change(k.quietBook)) return false
        if (!stopped) saveQuiet(k.id, k.quietBook.saved) // an erase began: nothing is written any more
        true
    }

    /**
     * Saves [group]'s clocks in table q, sealed ([QuietSeal], decision C); the readable column is left empty (the row is
     * replaced whole). Without the vault's key nothing is written: the clocks are kept in memory only until it can be had,
     * never written readable.
     */
    private fun saveQuiet(group: String, saved: List<String>) {
        try {
            if (saved.isEmpty()) { db?.delete("q", "grp = ?", arrayOf(group)); return }
            val key = historyKey ?: run { DebugTools.log("store: no key for the clocks, not saved"); return }
            db?.insertWithOnConflict("q", null, ContentValues().apply { put("grp", group); put("box", QuietSeal.seal(key, group, saved)) }, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: android.database.sqlite.SQLiteException) { DebugTools.log("store: can't save the clocks: $e") }
    }

    /**
     * A group message that may act on its sender's clock: a clock message (rules Q2, Q3) or an own check-in (Q4). It counts
     * only if its signature checked for a member listed from the epoch that opened it through the current one, with no
     * leave note of theirs held that it is dated after (Q1): nobody can set, move or stop someone else's clock. Any thread.
     */
    private fun quietSaw(k: Keys, o: GroupCrypto.Opened, checked: Boolean?) {
        if (stopped || k.isPublic) return
        val effect = QuietClock.effect(o.message) ?: return
        val sender = o.message.sender
        if (!QuietClock.counts(sender, checked, o.opener, k.ring, leavingOf(k).hides(sender, o.message.ts))) return
        val t = About.Target.of(o) // its packet id and the start of its signed digest
        quietApply(k, sender, t.id, t.mark, o.message.ts, effect, o.message.text)
    }

    /** Takes a clock message or a check-in of [sender] (a member id; this phone's own is [Keys.me]) into the group's book. One clock per member: the newest by its own time counts (Q3). Any thread. */
    private fun quietApply(k: Keys, sender: String, id: String, mark: String, ts: Long, effect: QuietClock.Effect, text: String) {
        if (stopped || k.isPublic) return
        val now = quietNow(); val high = highRiskNow()
        val changed = changeQuiet(k) { b ->
            when (effect) {
                is QuietClock.Effect.Clock -> b.take(sender, id, mark, ts, effect.deadline, QuietClock.spot(text), now, high)
                QuietClock.Effect.CheckIn -> b.checkIn(sender, ts, now, high)
            }
        }
        if (!changed) return
        DebugTools.log("quiet: ${if (effect is QuietClock.Effect.Clock) (if (effect.deadline == 0L) "a stop" else "a clock") else "a check-in"} taken${if (sender == k.me) " (this phone's)" else ""}")
        quietChanged()
    }

    private val quietQueued = java.util.concurrent.atomic.AtomicBoolean()
    /** The screens read the books again, and a pass follows (many changes at once, as at a start, make one). Any thread. */
    private fun quietChanged() {
        notifyUi()
        if (quietQueued.compareAndSet(false, true)) main.postDelayed({ quietQueued.set(false); quietPass() }, 300)
    }

    /**
     * Rules Q9, Q10: the clocks of members removed without a leave note go without an alert; a member who left with a note
     * keeps theirs (it alerts at its deadline as usual), also after the removal that followed the note. Records past their
     * time go, and a record's position goes when the group's timer would have deleted the clock message, or at once in
     * high-risk mode ([QuietBook.sweep]). Any thread.
     */
    private fun sweepQuiet(k: Keys, pass: Boolean = true) {
        if (k.isPublic || stopped || synchronized(k.quietBook) { k.quietBook.isEmpty }) return
        val cur = k.ring.currentOrNull() ?: return
        val leaving = leavingOf(k); val now = quietNow(); val high = highRiskNow(); val timer = k.watch.deletes(clock())
        if (changeQuiet(k) { it.sweep(now, high, timer) { m -> m in cur.members || leaving.left(m) } } && pass) quietChanged()
    }

    /** A checked receipt in [k]'s group: if it confirms this phone's own clock message, its sender's phone keeps the clock (rule Q14). Any thread. */
    private fun quietReceipt(k: Keys, r: Message) {
        if (k.isPublic || r.sender == deviceId) return
        val mine = synchronized(k.quietBook) { k.quietBook.clock(k.me) } ?: return
        if (mine.id !in Receipt.ids(r.text)) return
        if (changeQuiet(k) { it.addKeeper(k.me, mine.id, r.sender) }) notifyUi()
    }

    /** Decides every clock alert and this phone's own reminder now, on the engine's thread: at the start, hourly, after any change, at the next deadline, and when the lock or high-risk mode is switched. */
    fun quietPass() { if (!stopped) io.execute(::quietPassNow) }

    private val quietTimer = Runnable { quietPass() }

    /**
     * Rules Q5 to Q8 and Q12. What is due now is posted (at most one alert about a member every 10 minutes, and one learned
     * late only for a clock newer than the last one alerted); what is to come goes to the backup alarm with its words
     * decided now, and the running service's own timer is set for the nearest. What can't be guaranteed (spec "Alerts,
     * and what can't be guaranteed"): Doze may hold the alarm back by minutes, no exact-alarm permission is asked for, a
     * reboot or a force stop loses it until the app or its service starts again, and a check-in that reached the relays
     * or a neighbour while Android had ended the app isn't seen in time: the words cover that ("has reached this phone").
     * Background thread (io).
     */
    private fun quietPassNow() {
        if (stopped) return
        val c = app ?: return
        val out = quietOut
        val now = quietNow(); val hide = AppLock.enabled(c) // includes high-risk mode: no name, no time, no text
        val coming = ArrayList<QuietAlert>(); val over = ArrayList<String>(); var wake: Long? = null
        fun soon(at: Long) { if (wake.let { it == null || at < it }) wake = at }
        for (k in groupsHeld()) {
            sweepQuiet(k, pass = false)
            val book = quietOf(k)
            if (book.isEmpty) continue
            for ((cl, due) in book.alerts(now, k.me)) when (due) {
                is QuietClock.Due.At -> { soon(due.at); if (out != null) { coming += quietAlert(k, cl, false, hide, due.at); changeQuiet(k) { it.note(cl.member, cl.mark, scheduled = true) } } }
                QuietClock.Due.None -> changeQuiet(k) { it.note(cl.member, cl.mark, alerted = true, scheduled = false) } // more than 12 hours after: never
                else -> {
                    if (out == null) continue // nowhere to post it (a computer): nothing is marked
                    val late = due == QuietClock.Due.Late
                    val at = book.postableAt(cl, late, now)
                    if (at == null) { changeQuiet(k) { it.note(cl.member, cl.mark, alerted = true, scheduled = false) }; continue }
                    if (at > now) { soon(at); continue } // posted when the 10 minutes are up
                    out.post(quietAlert(k, cl, late, hide, now))
                    DebugTools.log("quiet: alert now, ${if (late) "learned late" else "on time"}${if (hide) ", without names" else ""}") // never who
                    changeQuiet(k) { it.didPost(cl, now); it.note(cl.member, cl.mark, alerted = true, scheduled = false); true }
                }
            }
            // A check-in or a stop came: an alert still showing for that clock goes.
            for (cl in book.clocks.values) if (cl.member != k.me && !book.armed(cl.member)) over += QuietAlerts.id(k.id, cl.member)
            // Rule Q8: this phone's own clock never alerts it; it reminds 10 minutes before, for clocks of 20 minutes or more
            // ([QuietBook.Clock.alerted] marks the reminder of this phone's own clock).
            val mine = book.clock(k.me)
            if (mine == null || !book.armed(k.me)) over += QuietAlerts.remindId(k.id)
            else {
                if (mine.deadline > now) soon(mine.deadline) // the ring and Home end then
                if (!mine.alerted) QuietClock.remindAt(mine.ts, mine.deadline)?.let { at ->
                    if (at > now) { soon(at); if (out != null) coming += quietReminder(k, mine, hide, at) }
                    else if (now < mine.deadline && out != null) { out.post(quietReminder(k, mine, hide, now)); changeQuiet(k) { it.note(k.me, mine.mark, alerted = true) } }
                }
            }
        }
        if (out != null) { out.take(over); out.arm(coming.sortedBy { it.at }) }
        DebugTools.log("quiet: pass, ${coming.size} to come${if (out == null) ", no alerts here" else ""}")
        notifyUi() // People's lines and the ring follow the time
        main.removeCallbacks(quietTimer)
        wake?.let { w -> main.postDelayed(quietTimer, (w - now + 500).coerceIn(1_000L, Keyring.HOUR_MS)) }
    }

    /**
     * The backup alarm rang with [due] (see [QuietOut.arm]). With the engine running, a pass decides everything. Without it
     * (Android ended the app), what is due is posted unless its record was cancelled, replaced or alerted meanwhile, read
     * from the store itself, with the same limit of one alert about a member every 10 minutes; what is still to come is
     * returned, to be armed again ([QuietRang]). All of it under the engine's own lock, which [start] takes too: a start
     * can't read table q between this read and this save, so the engine never posts the same alert a second time
     * (final review 2026-11-23). Background thread.
     */
    fun quietAlarmRang(c: Context, due: List<QuietAlert>, out: QuietOut): List<QuietAlert> {
        if (stopped) return emptyList()
        if (router != null) { quietPass(); return emptyList() } // the pass arms the alarm again
        synchronized(this) {
            if (stopped) return emptyList()
            if (router != null) { quietPass(); return emptyList() } // a start came first
            val now = quietNow()
            if (app == null) app = c.applicationContext
            if (db == null) runCatching { db = Db(c.applicationContext).writableDatabase }.onFailure { DebugTools.log("quiet: can't open the store: $it") }
            val d = db ?: return due.filter { it.at > now } // what is due now can't be checked: it isn't armed again to ring at once, again and again
            sealPlainClocks()
            val books = quietBooks(d) ?: return due.filter { it.at > now }
            val r = QuietRang.handle(due, books, now)
            r.post.forEach(out::post)
            for (g in r.changed) saveQuiet(g, books.getValue(g).saved)
            DebugTools.log("quiet: the alarm rang, ${r.post.size} posted, ${r.left.size} to come, ${due.size - r.post.size - r.left.size} dropped")
            return r.left
        }
    }

    /** "18:30", in the phone's own format. */
    private fun hm(ms: Long): String = app?.let { clock(it, ms) } ?: ""

    /** The alert about [cl], worded now ([hide]: the lock or high-risk mode is on). */
    private fun quietAlert(k: Keys, cl: QuietBook.Clock, late: Boolean, hide: Boolean, at: Long): QuietAlert {
        val name = quietName(k, cl.member); val group = groupName(k.code) ?: "your group"
        val title = QuietWords.alertTitle(name, late, hm(cl.deadline))
        return QuietAlert(QuietAlerts.id(k.id, cl.member), cl.mark, at, if (hide) QuietWords.HIDDEN_TITLE else title,
            if (hide) QuietWords.HIDDEN_BODY else QuietWords.alertBody(name, hm(cl.ts), late, quietPosition(k, cl)?.let(::hm)),
            title, QuietWords.banner(name, group), k.code, remind = false, safe = false)
    }

    /** This phone's own reminder (rule Q8), with "I'm safe" under it unless the lock is on. */
    private fun quietReminder(k: Keys, mine: QuietBook.Clock, hide: Boolean, at: Long): QuietAlert {
        val title = QuietWords.remindTitle(hm(mine.deadline))
        return QuietAlert(QuietAlerts.remindId(k.id), mine.mark, at, if (hide) QuietWords.HIDDEN_TITLE else title,
            if (hide) QuietWords.REMIND_HIDDEN else QuietWords.REMIND_BODY, title, QuietWords.REMIND_BODY, k.code, remind = true, safe = !hide)
    }

    /**
     * Who a clock is from, as alerts and the card say it (rule Q13, [QuietWords.who]): "Anna (not checked)" for a member not
     * checked, "Anna · 🐶🐱🦁🐴" for a checked one whose name another member of the group has too.
     */
    private fun quietName(k: Keys, member: String): String {
        val members = k.members.toSet()
        val (n, pub, others) = synchronized(k.known) {
            Triple(k.known[member]?.name ?: "", k.known[member]?.pub, k.known.entries.filter { it.key != member && it.key in members }.map { it.value.name })
        }
        val name = n.ifEmpty { "Someone" }; val key = Vouch.key(name)
        val shared = others.any { Vouch.key(it.ifEmpty { "Someone" }) == key }
        val emoji = pub?.let { P256.emoji(it).map { i -> Fingerprint.EMOJI[i] } } ?: emptyList()
        return QuietWords.who(name, isChecked(k.code, member), shared, emoji)
    }

    /** The same for the chat's card of a clock from [member] in [code]'s group. */
    fun quietName(code: String?, member: String): String? = held(code)?.takeIf { !it.isPublic }?.let { quietName(it, member.removePrefix("web-")) }

    /** Rule Q11: when the newest position known of the clock's member was sent: from the clock message or any check-in of theirs here. None in high-risk mode. */
    private fun quietPosition(k: Keys, cl: QuietBook.Clock): Long? {
        if (highRiskNow()) return null
        val checkIn = synchronized(messages) { messages.lastOrNull { !it.public && it.group == k.code && it.verified && it.sender == cl.member && CheckIn.spot(it.text) != null }?.ts }
        return listOfNotNull(if (cl.spot != null) cl.ts else null, checkIn).maxOrNull()
    }

    // What the screens read and do

    /** This phone's own clock in [code]'s group while it runs or has run out (the "If I go quiet" screen, Home). */
    fun quietOwn(code: String?, now: Long = quietNow()): Pair<QuietBook.Clock, QuietBook.State>? {
        val k = held(code)?.takeIf { !it.isPublic } ?: return null
        return synchronized(k.quietBook) {
            val b = k.quietBook; val c = b.clock(k.me); val s = b.state(k.me, now)
            if (c != null && s != null && b.armed(k.me)) c to s else null
        }
    }

    /** How [member]'s clock stands on this phone (People, rule Q6); null for this phone's own and for none. */
    fun quietState(code: String?, member: String, now: Long = quietNow()): QuietBook.State? {
        val k = held(code)?.takeIf { !it.isPublic } ?: return null
        val m = memberId(k, member)
        if (m == k.me) return null
        return synchronized(k.quietBook) { k.quietBook.state(m, now) }
    }

    /**
     * Who confirmed this phone's own clock in [code]'s group, from the clock message's delivery receipts kept in its record
     * (so the list outlives the message), never from the member list: only phones that got the clock can tell anyone. Only
     * people who are members now: whoever left or was removed since forgot the group (review round 2b M7).
     */
    fun quietKeepers(code: String?): List<String> {
        val k = held(code)?.takeIf { !it.isPublic } ?: return emptyList()
        val cur = k.ring.currentOrNull() ?: return emptyList()
        val leaving = leavingOf(k)
        val ids = synchronized(k.quietBook) { k.quietBook.keptBy(k.me) { it in cur.members && !leaving.left(it) } }
        return ids.map { id -> synchronized(k.known) { k.known[id]?.name }?.ifEmpty { null } ?: "Someone" }
    }

    /**
     * Rules QW1, QW2: sends this phone's clock message to [code]'s group, deadline [deadline] of the message's time (0
     * stops), with [spot] (only when the user shares their position and chose "Add where I am"). Never on the Public channel
     * and never held until this phone is let in: false, and nothing is sent, if it can't write there ([canTell]). Background thread.
     */
    fun sendClock(code: String?, deadline: (Long) -> Long, spot: Spot?): Boolean {
        if (code == null || !canTell(code)) return false
        val ts = System.currentTimeMillis(); val d = deadline(ts)
        if (!QuietClock.valid(d, ts)) return false
        post(false, name, QuietClock.text(d, ts, if (d == 0L) null else spot), false, to = code, clock = d, at = ts)
        DebugTools.log("quiet: clock ${if (d == 0L) "stopped" else "of ${(d - ts) / 60_000} min"} sent")
        return true
    }
}
