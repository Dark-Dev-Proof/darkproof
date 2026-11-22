package it.blackout.mesh

import android.content.Context

/** Reports of Public messages waiting to go to the internet relays (spec/FORMAT.md, "Internet relays"). */
object NostrReports {
    private fun prefs(c: Context) = c.getSharedPreferences("mesh", 0)
    fun report(c: Context, messageId: String) {
        val s = (pending(c) + messageId).toList().takeLast(200).toSet()
        prefs(c).edit().putStringSet("reports", s).apply()
    }
    fun pending(c: Context): Set<String> = prefs(c).getStringSet("reports", emptySet())!!.toSet()
    fun sent(c: Context, ids: Collection<String>) = prefs(c).edit().putStringSet("reports", pending(c) - ids.toSet()).apply()
}

/**
 * Links to public Nostr relays while there is internet (spec/FORMAT.md, "Internet relays"). One peer for forwarding:
 * the router gives it every packet and it hands the router what arrives for this phone's mailboxes. Packets written on
 * this phone go up first. Anyone else's (this phone bridging its neighbourhood) go up only if no relay has them yet,
 * and slowly, so the same packet isn't posted by every phone that holds it and relays don't throttle us ([RelayOutbox]).
 * Each group has its own connections, key and subscription ([RelayScope]), so one connection never shows that two
 * groups share a phone. Packets this phone only passes on go out with the Everyone channel's, whatever mailbox their
 * id names ([Nostr.scope]); a link set takes only packets of its own mailboxes ([Nostr.takes]); and what came from the
 * relays never goes back up ([MeshRouter.peerUp], [FromRelays]).
 */
class NostrLink(
    private val ctx: Context,
    private val router: () -> MeshRouter?,
    private val scopes: () -> List<Scope>,
    private val changed: () -> Unit,
    private val viaTor: Boolean = false, // "Connect through Tor": links go only through the app's own Tor
    private val onRelays: (String) -> Unit = {}, // a relay accepted a packet, or had it already
    private val packetKey: (String) -> String? = { null }, // message id -> the packet this phone shows under it (MeshRouter.key), for reports
    private val fromRelays: FromRelays = FromRelays(), // the packets relays brought first; the caller gives them to the router after a restart
) {
    /** One group's mailboxes; [id] is empty for the Everyone channel. */
    class Scope(val id: String, val mailboxes: List<Int>)

    companion object {
        const val PEER = "nostr"
        fun owns(peer: String) = peer == PEER
        /** Free public relays that stored a 22 KB event of this kind from a new key (tested 2026-11-06). */
        val RELAYS = listOf("wss://nos.lol", "wss://relay.damus.io", "wss://relay.primal.net", "wss://nostr.mom", "wss://nostr.oxtr.dev", "wss://relay.ditto.pub")
        /** Through Tor: nos.lol and nostr.mom don't answer Tor exits (tested 2026-11-06), so two others take their place. */
        val TOR_RELAYS = listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nostr.oxtr.dev", "wss://relay.ditto.pub", "wss://relay.nostr.net", "wss://nostr.data.haus")
    }

    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val rnd = java.security.SecureRandom()
    private val POST_SPREAD_MS = 8_000 // so the time a packet reaches a relay says less about when it was written
    /** Scope id -> its links. Whether this link runs is decided under the same lock, so once stopped it never gets a new link set. */
    private val links: LinkSets<RelayScope> = LinkSets(
        { id -> RelayScope(ctx, id, router, ::scopeUp, { links.boxes[id] ?: emptyList() }, changed, viaTor, onRelays, packetKey, fromRelays) },
        { it.start() }, // only queues tasks: nothing here calls the router
    )
    private val upLock = Any()
    private var linksUp = 0 // scopes with at least one relay linked (lock upLock)

    private val everyone get() = links[""]
    val connected get() = links.all().maxOfOrNull { it.connected } ?: 0
    val isUp get() = synchronized(upLock) { linksUp > 0 }
    val total get() = everyone?.total ?: 0
    val throughTor get() = everyone?.throughTor
    val torStuck get() = everyone?.torStuck == true
    fun statusLine() = everyone?.statusLine() ?: "Internet: off"

    fun start() { links.start(); sync() }

    /** For good: the Internet or Tor switch makes a new NostrLink. A resubscribe still running on another thread finds this one stopped. */
    fun stop() = links.stop().forEach { it.stop() }

    /** The groups or their keys changed: new groups get their own links, and each link whose mailboxes changed asks for them. */
    fun resubscribe() { sync(); links.all().forEach { it.resubscribe() } }

    /**
     * Called by the router: a packet written on this phone goes up on the links of its group, and anything else with
     * the Everyone channel's ([Nostr.scope]). This phone's own wait a moment, at random. [mine]: this very packet was
     * written here, not another one under its id. [from]: the link that brought it ([MeshRouter.source]), for its turn
     * among the packets passed on ([RelayOutbox]).
     */
    fun send(p: Packet, mine: Boolean, from: String = "") { if (mine) h.postDelayed({ route(p, true, "") }, rnd.nextInt(POST_SPREAD_MS).toLong()) else route(p, false, from) }

    private fun route(p: Packet, mine: Boolean, from: String) { (links[Nostr.scope(p.id, mine, links.boxes)] ?: links[""])?.send(p, mine, from) }

    private fun sync() { links.sync { scopes().distinctBy { it.id }.associate { it.id to it.mailboxes } }?.forEach { it.stop() } }

    /** The router sees one peer, up while any scope has a relay. */
    private fun scopeUp(on: Boolean) {
        val edge = synchronized(upLock) { linksUp += if (on) 1 else -1; if (on) linksUp == 1 else linksUp == 0 }
        if (edge) router()?.let { if (on) it.peerUp(PEER) else it.peerDown(PEER) }
    }
}

/** One group's links to the relays (or the Everyone channel's, when [scope] is empty): its own sockets, key and subscription. */
private class RelayScope(
    private val ctx: Context,
    private val scope: String,
    private val router: () -> MeshRouter?,
    private val onUp: (Boolean) -> Unit,
    private val mailboxes: () -> List<Int>,
    private val changed: () -> Unit,
    private val viaTor: Boolean,
    private val onRelays: (String) -> Unit,
    private val packetKey: (String) -> String?,
    private val fromRelays: FromRelays,
) {
    companion object {
        private const val START_SPREAD_MS = 8_000   // groups link at different moments, so their connections don't line up
        private const val TICK_MS = 1_500L          // at most one own packet per tick
        private const val BRIDGE_EVERY = 3          // and one bridged packet every 3 ticks
        private const val CHECK_MS = 10_000L        // how often to ask relays which bridged packets they have
        private const val CHECK_WAIT_MS = 6_000L    // how long to wait for their answers
        private const val CHECK_BATCH = 50
        private const val REST_MS = 10 * 60_000L    // pause posting to a relay that says to slow down
        private const val CATCH_UP_WAIT_S = 20L     // a catch-up REQ with no EOSE by then is given up on this link
    }

    private val secret = Schnorr.newSecret() // a new throwaway key every start and for each group: relays can't link them by key
    private val relays = DebugTools.relays(ctx) ?: if (viaTor) NostrLink.TOR_RELAYS else NostrLink.RELAYS // the override exists in debug builds only
    private val saved = if (scope.isEmpty()) "" else ".$scope" // the Everyone links keep the names older builds used
    private val torUser = ByteArray(8).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } // its own Tor circuits
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    // Battery saver: the radio wakes less often for keep-alive pings.
    private val direct = okhttp3.OkHttpClient.Builder().pingInterval(if (MeshEngine.batterySaver(ctx)) 120L else 30L, java.util.concurrent.TimeUnit.SECONDS)
        // No compression and no message over 1 MiB: a hostile relay could otherwise end the app (WsLimit).
        .apply { WsLimit.apply(this, plain = relays.any { it.startsWith("ws://") }) }.build()
    private var client = direct // through Tor's proxy once Tor is up (viaTor)
    private val open = java.util.concurrent.ConcurrentHashMap<String, okhttp3.WebSocket>()
    private val sockets = RelaySockets<okhttp3.WebSocket>() // one for each relay; nothing from any other socket is handled
    private val subscribed = java.util.concurrent.ConcurrentHashMap<String, List<Int>>() // relay -> the mailboxes its live subscription asks for
    private val backoff = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val resting = java.util.concurrent.ConcurrentHashMap<String, Long>() // relay -> post again after
    private val catchUps = java.util.concurrent.ConcurrentHashMap<String, CatchUp>() // relay -> its catch-up (lock it)
    private val paces = java.util.concurrent.ConcurrentHashMap<String, RelayPace>()  // relay -> what it sent this minute
    private val prefs = ctx.getSharedPreferences("mesh", 0)
    private val outbox = RelayOutbox(prefs.getString("relaySent$saved", "")!!.split(',').filter { it.isNotEmpty() })
    private val since = RelaySince(prefs.getString("relaySince$saved", "") ?: "")
    private val subjects = ReportSubjects() // the relay events that carried Public packets, for reports
    private val shown = object : LinkedHashMap<String, String>() { // reported message id -> the packet shown under it ("" if none); main thread
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 200
    }
    @Volatile private var running = false
    @Volatile private var stopped = false // a link set that was stopped never starts again
    @Volatile private var up = false
    @Volatile private var live = Nostr.subId("live") // random: an id made from the clock would be almost the same on every link set of this phone
    @Volatile private var lookup: Set<String> = emptySet() // the packet ids of the #x lookup in flight
    private var ticks = 0
    private var checking = false
    private var torCheck = false // a Tor check is running

    /** With Tor: true once a check says traffic leaves through Tor, false while Tor starts. Null without Tor. */
    @Volatile var throughTor: Boolean? = if (viaTor) false else null; private set
    /** Since when this link has been waiting for Tor (0 while it isn't). */
    @Volatile private var torWaitSince = if (viaTor) System.currentTimeMillis() else 0L
    /** Tor has been trying for over 2 minutes without a working circuit (no internet, or Tor blocked). */
    val torStuck get() = throughTor == false && torWaitSince > 0 && System.currentTimeMillis() - torWaitSince > 120_000L

    val connected get() = open.size
    /** True while at least one relay is linked (it then counts as one peer for the router, not as a phone). */
    val isUp get() = up
    val total get() = relays.size

    /**
     * OkHttp has no limit on one message, so a hostile relay could send one big enough to end the app. When memory runs
     * short, every relay link is dropped, which frees what it was reading; they reconnect as after any failure.
     */
    private val memoryWatch = object : Runnable {
        override fun run() {
            if (!running) return
            val rt = Runtime.getRuntime()
            if (rt.totalMemory() - rt.freeMemory() > rt.maxMemory() / 10 * 8) { DebugTools.log("relays: memory is short, dropping links"); sockets.all().forEach { it.cancel() } }
            h.postDelayed(this, 2_000)
        }
    }

    fun start() {
        if (running || stopped) return
        running = true
        h.removeCallbacks(memoryWatch); h.post(memoryWatch)
        val wait = if (scope.isEmpty()) 0L else java.security.SecureRandom().nextInt(START_SPREAD_MS).toLong()
        h.postDelayed({ if (!viaTor) relays.forEach(::connect) else waitForTor() }, wait) // Tor checks run on the main thread
        h.postDelayed(::pump, TICK_MS)
        h.postDelayed(::check, CHECK_MS)
    }

    fun stop() {
        stopped = true; running = false
        h.removeCallbacksAndMessages(null)
        sockets.close().forEach { it.close(1000, null) }; open.clear(); catchUps.clear(); subscribed.clear()
        if (up) { up = false; onUp(false) }
        save()
        changed()
    }

    /**
     * The groups changed: if this link set's mailboxes did, every relay gets the new live subscription and catches up
     * the new mailboxes. A link set whose mailboxes are the same sends nothing, so a change in one group doesn't show
     * on the links of all the others at the same moment.
     */
    fun resubscribe() {
        val boxes = mailboxes()
        if (open.keys.all { subscribed[it] == boxes }) return
        val old = live; live = Nostr.subId("live")
        for ((url, ws) in open) {
            ws.send(Nostr.close(old)); ws.send(Nostr.live(live, boxes)); subscribed[url] = boxes
            val cu = catchUps[url] ?: continue
            synchronized(cu) { cu.add(boxes); cu.next(nowS()) }?.let { ws.send(it.text()) }
        }
    }

    /**
     * Called by the router: queues the packet unless it came from the relays, is on them already or is too old.
     * [mine] = written on this phone (a message or a receipt). Older own packets may be on the relays already (the
     * app can be killed before it saves what it posted), so they are looked up first, like anyone else's.
     */
    fun send(p: Packet, mine: Boolean, from: String = "") = outbox.offer(p, mine, from)

    /**
     * Starts the app's Tor if needed, waits for it, checks through it that traffic leaves through Tor, then links to
     * the relays; else tries again soon.
     */
    private fun waitForTor() {
        if (!running || torCheck) return
        Tor.start(ctx)
        val port = Tor.socksPort() ?: run { h.postDelayed(::waitForTor, 2_000); return } // still starting
        val socks = Socks5.factory(port, torUser)
        client = direct.newBuilder().socketFactory(socks).dns(Socks5.noDns).build()
        torCheck = true
        val c = okhttp3.OkHttpClient.Builder().socketFactory(socks).dns(Socks5.noDns).build() // an ordinary request: the relay links' guard takes only WebSocket answers
        Thread {
            val ok = Tor.check(c, scope.take(6).ifEmpty { "everyone" })
            h.post {
                torCheck = false
                if (!running) return@post
                throughTor = ok; if (ok) torWaitSince = 0L; changed()
                if (ok) relays.forEach(::connect) else h.postDelayed(::waitForTor, 15_000) // relays that still have a socket are left alone
            }
        }.start()
    }

    private fun connect(url: String) {
        if (!running || (viaTor && throughTor != true)) return // with Tor: only after the check
        // One link for each relay: the reconnect after a loss and the connect after a Tor check can both ask for the same one.
        sockets.connect(url) { client.newWebSocket(okhttp3.Request.Builder().url(url).build(), listener(url)) }
    }

    private fun listener(url: String) = object : okhttp3.WebSocketListener() {
            override fun onOpen(ws: okhttp3.WebSocket, r: okhttp3.Response) {
                if (!sockets.tracks(url, ws)) { ws.cancel(); return } // not this set's link to the relay (any more)
                open[url] = ws; backoff.remove(url)
                DebugTools.log("relay open ${url.substringAfter("//")} ${scope.take(6).ifEmpty { "everyone" }}")
                val boxes = mailboxes()
                ws.send(Nostr.live(live, boxes)); subscribed[url] = boxes
                val cu = CatchUp(url, since, pace = paces.getOrPut(url) { RelayPace() }).also { it.add(boxes) }
                catchUps[url] = cu
                synchronized(cu) { cu.next(nowS()) }?.let { ws.send(it.text()) }
                if (!up) { up = true; onUp(true) } // the router then offers every stored packet
                changed()
            }
            override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                if (!sockets.tracks(url, ws)) { ws.cancel(); return } // nothing from a socket this set doesn't track is handled
                Nostr.ok(text)?.let { ok ->
                    if (!ok.accepted) {
                        android.util.Log.w("DarkProof", "relay $url refused an event: ${ok.reason.take(150)}")
                        if (Nostr.isThrottle(ok.reason)) resting[url] = System.currentTimeMillis() + REST_MS
                    }
                    outbox.answer(url, ok)?.let(onRelays) // "Sent" only once a relay took it
                    return
                }
                Nostr.end(text)?.let { e ->
                    val cu = catchUps[url] ?: return
                    synchronized(cu) { cu.end(e.subId, e.complete, nowS()) }?.let { ws.send(it.text()) } // older page or next mailbox
                    return
                }
                val cu = catchUps[url] ?: return // no catch-up, no share of the relay's limit: nothing is taken without being counted
                if (!synchronized(cu) { cu.allow(nowS()) }) return
                synchronized(cu) { cu.frame(text) } // every event of a catch-up page counts, also one that carries no packet or isn't taken
                val r = Nostr.received(text) ?: return
                // Only this link set's own mailboxes, or what its lookup asked for: a relay can't plant anything else here.
                if (!Nostr.takes(r.packet, mailboxes(), lookup)) return
                subjects.note(url, r)
                if (r.subId == live) synchronized(cu) { cu.live(Packet.mailbox(r.packet.id), r.createdAt, nowS()) }
                outbox.fromRelay(r)?.let(onRelays) // a copy the packet's recipients don't get isn't "on the relays"
                // Also answers to the #x lookups: the router ignores packets it already has. One that is new came from
                // the relays: it never goes back up, after a restart either.
                if (router()?.receive(NostrLink.PEER, r.packet) == true) fromRelays.add(r.packet)
            }
            override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) = lost(url, ws)
            override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, r: okhttp3.Response?) = lost(url, ws)
    }

    private fun lost(url: String, ws: okhttp3.WebSocket) {
        if (!sockets.lost(url, ws)) return
        open.remove(url); catchUps.remove(url); subscribed.remove(url)
        if (open.isEmpty() && up) { up = false; onUp(false) }
        if (open.isEmpty() && throughTor == true) { throughTor = false; torWaitSince = System.currentTimeMillis(); h.post(::waitForTor) } // Tor lost its circuits? check again
        changed()
        if (!running) return
        val wait = ((backoff[url] ?: 2_500L) * 2).coerceAtMost(120_000L) // no internet: try again, less and less often
        backoff[url] = wait
        h.postDelayed({ connect(url) }, wait)
    }

    /**
     * Every tick: one own packet, else (every few ticks) one bridged packet no relay had, else pending reports. Only
     * to relays that aren't resting; with none, packets stay queued.
     */
    private fun pump() {
        if (!running) return
        ticks++
        outbox.expire() // posts no relay accepted in time go back in the queue
        val now = System.currentTimeMillis()
        val ready = open.filterKeys { (resting[it] ?: 0L) < now }
        if (ready.isNotEmpty()) {
            val next = outbox.next(bridged = ticks % BRIDGE_EVERY == 0)
            if (next != null) post(next.first, next.second, ready) else sendReports()
        }
        save()
        h.postDelayed(::pump, TICK_MS)
    }

    private fun post(p: Packet, fromOwn: Boolean, to: Map<String, okhttp3.WebSocket>) {
        val e = Nostr.event(p, secret, Nostr.blurred(nowS()))
        val msg = Nostr.publish(e)
        outbox.posted(e.getString("id"), p, fromOwn, to.filter { (_, ws) -> ws.send(msg) }.keys.toSet())
    }

    /** The sent list, the catch-up starts and what came from the relays, when they changed. */
    private fun save() {
        if (outbox.changed) prefs.edit().putString("relaySent$saved", outbox.sentList().joinToString(",")).apply()
        if (since.changed) prefs.edit().putString("relaySince$saved", since.save()).apply()
        if (fromRelays.changed) prefs.edit().putString(FromRelays.PREF, fromRelays.save()).apply()
    }

    /** Asks the relays which of the newest unchecked bridged packets they have; the rest get posted. Also ends stuck catch-ups. */
    private fun check() {
        if (!running) return
        h.postDelayed(::check, CHECK_MS)
        catchUpTimeouts()
        if (checking || open.isEmpty()) return
        val batch = outbox.toCheck(CHECK_BATCH)
        if (batch.isEmpty()) return
        checking = true
        val subId = Nostr.subId("have")
        lookup = batch.mapTo(HashSet()) { it.id } // answers to it are taken whatever their mailbox (Nostr.takes)
        val req = Nostr.have(subId, batch.map { it.id })
        open.values.forEach { it.send(req) }
        h.postDelayed({
            open.values.forEach { it.send(Nostr.close(subId)) }
            outbox.checked(batch).forEach(onRelays) // only copies with the same contents count (Nostr.digest), in an event the recipients get
            lookup = emptySet(); checking = false
        }, CHECK_WAIT_MS)
    }

    private fun catchUpTimeouts() {
        val now = nowS()
        for ((url, cu) in catchUps) {
            val ws = open[url] ?: continue
            val next = synchronized(cu) {
                val c = cu.current ?: return@synchronized null
                if (now - cu.sentAt < CATCH_UP_WAIT_S) return@synchronized null
                ws.send(Nostr.close(c.subId)); cu.end(c.subId, false, now)
            }
            next?.let { ws.send(it.text()) }
        }
    }

    private fun nowS() = System.currentTimeMillis() / 1000

    private fun sendReports() {
        val done = ArrayList<String>()
        for (id in NostrReports.pending(ctx)) {
            if (!subjects.knows(id)) continue // only messages that came from relays can be reported there
            val key = shown.getOrPut(id) { packetKey(id) ?: "" }
            // Named: an event that carried the very packet this phone shows, and that its author signed. Else the report waits.
            val subject = subjects.of(key) ?: continue
            val msg = Nostr.publish(Nostr.report(subject.eventId, subject.pubkey, secret))
            open.values.forEach { it.send(msg) }
            done += id
        }
        if (done.isNotEmpty()) NostrReports.sent(ctx, done)
    }

    fun statusLine(): String = when {
        !running -> "Internet: off"
        throughTor == false -> "Internet: waiting for Tor"
        else -> "Internet: $connected of $total relays" + if (throughTor == true) " through Tor" else ""
    }
}
