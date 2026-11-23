package it.blackout.mesh

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream

/** DEBUG BUILDS ONLY: test hooks and logging. The release source set has an empty stub with the same API. */
object DebugTools {
    fun log(s: String) { Log.i("DarkProof", s) }

    /** For tests that compare phones' codes: the start of its hash, never the code. */
    fun noteCode(code: String?) { log("code now " + (code?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(4).joinToString("") { b -> "%02x".format(b) } } ?: "none")) }

    /** Moves the clock that decides when a key is due: --es ahead 8 (days). */
    @Volatile var ahead = 0L

    /** The mock relay in tools/mock-relay instead of the public ones: --es relays ws://127.0.0.1:7447 (--es relays "" clears it). */
    fun relays(c: Context): List<String>? = c.getSharedPreferences("mesh", 0).getString("debugRelays", null)?.takeIf { it.isNotEmpty() }?.split(',')

    /** The iPhone's self-test questions (LocalAi.swift), timed: adb shell am start … --es aiSelfTest 1, then logcat -s DarkProof. */
    private fun aiSelfTest(c: Context) {
        val g = Groups.current(c) ?: return log("AI self-test: no group")
        val m = LocalAi.model(LocalAi.selected) ?: return log("AI self-test: no model ready")
        val qs = listOf(
            "How do I make river water safe to drink without a filter?",
            "Someone is trapped behind a jammed metal door. How can we open it?",
            "And if the hinges are on the inside?",
            "My friend has a deep cut on the thigh and blood is spurting. No ambulance can come. What do I do?",
            "What is the most paracetamol an adult can take in 24 hours, and what are the signs of an overdose?",
            "A child is locked in a hot car and nobody has the keys. How do I break the window safely?",
            "Write a 10-step checklist for preparing a home for a week without power.",
            "Who has not checked in?",
        )
        fun mem() = android.app.ActivityManager.MemoryInfo().also { c.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.availMem / 1_000_000
        log("AI self-test: ${m.title}, phone memory class ${LocalAi.memoryGB} GB, free memory ${mem()} MB")
        LocalAi.newConversation()
        fun next(k: Int) {
            if (k == qs.size) { log("AI self-test: done, backend ${LocalAi.backendName}, free memory ${mem()} MB"); return }
            val t0 = System.currentTimeMillis()
            LocalAi.ask(qs[k], aiSystem(g, MeshEngine.snapshot())) { a ->
                val s = (System.currentTimeMillis() - t0) / 1000.0
                log("AI === ${qs[k]} (${"%.1f".format(s)} s, ${a.text.length} chars${if (a.isError) ", ERROR" else ""}, ${LocalAi.backendName})")
                a.text.replace("\n", " ⏎ ").chunked(900).forEach { log("AI | $it") }
                next(k + 1)
            }
        }
        next(0)
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * A message from another phone, through the real receive path (alerts, banners, people): --es fake help | ask |
     * askme | vouch | safe | text | other (to another saved group) | history (messages 1 and 2 days old, an old place).
     */
    private val fakeKeys = HashMap<String, java.security.KeyPair>()
    private val fakeEnc = HashMap<String, java.security.KeyPair>()
    private fun fake(c: Context, kind: String) {
        val g = Groups.current(c) ?: return
        val me = c.getSharedPreferences("mesh", 0).getString("name", "") ?: ""
        val now = System.currentTimeMillis()
        // Each fake person has their own member key and introduces it first, like a real phone (spec "Introductions").
        // introLater: the message first, its introduction 5 s later ("Not verified yet", then checked).
        fun put(code: String, text: String, ts: Long = now, from: String = "Marco", @Suppress("UNUSED_PARAMETER") sender: String = "", introLater: Boolean = false) {
            val cr = GroupCrypto(code); val pair = fakeKeys.getOrPut(code + "|" + from) { P256.newKeyPair() }
            val pub = P256.compress(pair.public); val me = P256.memberId(pub)
            val intro = cr.intros(); val iid = Packet.newId(intro.mailbox)
            val enc = P256.uncompressed(fakeEnc.getOrPut(code + "|" + from) { P256.newKeyPair() }.public)
            val introPacket = Packet(iid, ts - 1, 16, intro.seal(iid, ts - 1, me, from, "", extra = byteArrayOf(2) + pub + enc) { P256.sign(pair.private, it) })
            if (!introLater) MeshEngine.router?.receive("debug", introPacket)
            val id = Packet.newId(cr.mailbox)
            MeshEngine.router?.receive("debug", Packet(id, ts, 16, cr.seal(id, ts, me, from, text) { P256.sign(pair.private, it) }))
            if (introLater) main.postDelayed({ Thread { MeshEngine.router?.receive("debug", introPacket) }.start() }, 5_000)
        }
        when (kind) {
            "help" -> put(g.code, CheckIn.HELP.text(41, Spot(45.464, 9.189, 10), setOf(Need.INJURED, Need.WATER), 3))
            "safe" -> put(g.code, CheckIn.SAFE.text(77, Spot(45.4655, 9.1905, 12)), from = "Lucia", sender = "debug-lucia")
            "ask" -> put(g.code, AreYouSafe().text)
            "askme" -> put(g.code, AreYouSafe(me).text)
            "vouch" -> put(g.code, Vouch("Grandma Rosa", CheckIn.HELP, setOf(Need.MEDICINE), 2, Spot(45.4662, 9.1931)).text)
            "text" -> put(g.code, "Water is back at the school gym")
            "late" -> put(g.code, "Bridge on Via Roma is closed", from = "Lucia", introLater = true)
            "other" -> Groups.others(c).firstOrNull()?.let { put(it.code, "Hello from ${it.name}", from = "Sara", sender = "debug-sara") }
            // People asking to join, as their phones would: an introduction through the door, with an ML-KEM key. Two share a name.
            "asks" -> for (who in listOf("Ben", "Giulia", "Ben")) {
                val cr = GroupCrypto(g.code).intros(); val pair = P256.newKeyPair(); val pub = P256.compress(pair.public); val id = Packet.newId(cr.mailbox)
                MeshEngine.router?.receive("debug", Packet(id, now, 16, cr.seal(id, now, P256.memberId(pub), who, "",
                    extra = byteArrayOf(3) + pub + P256.uncompressed(P256.newKeyPair().public) + MlKem.newKey().first) { P256.sign(pair.private, it) }))
            }
            "history" -> {
                put(g.code, "Power is out in the whole street", now - 2 * 86_400_000L)
                put(g.code, "Shop on the corner still has bread", now - 86_400_000L - 3_600_000L, "Lucia", "debug-lucia")
                put(GroupCrypto.PUBLIC_CODE, Place(PlaceKind.WATER, "Fountain in the park", Spot(45.467, 9.195)).text, now - 30 * 3_600_000L, "Paolo", "debug-paolo")
            }
        }
        log("fake message: $kind")
    }
    private fun whenStarted(f: () -> Unit) { if (MeshEngine.router != null) f() else main.postDelayed({ whenStarted(f) }, 500) }

    private fun againFile(c: Context) = java.io.File(c.filesDir, "debug-again")

    /**
     * --es epochSay "text": a group message sealed under the group's key in use and signed with this phone's member key,
     * the way a member writes who has no message keys of their own. It comes in through the real receive path, as another
     * phone would bring it. Unlike a message with a key of its own, its packet still opens later with a key this phone
     * holds: the case high-risk mode's gone list is for. The packet is kept in a file for --es again 1.
     */
    private fun epochSay(c: Context, text: String) {
        val cache = MeshEngine::class.java.getDeclaredField("keyCache").apply { isAccessible = true }.get(MeshEngine) as HashMap<*, *>
        val k = synchronized(cache) { cache[MeshEngine.currentCode] } ?: return log("epochSay: no keys for the group on screen")
        fun call(name: String, vararg a: Any?): Any? = k.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == a.size }.apply { isAccessible = true }.invoke(k, *a)
        val cr = call("getGroup") as GroupCrypto; val me = call("getMe") as String
        val id = Packet.newId(cr.mailbox); val ts = System.currentTimeMillis()
        val cipher = cr.seal(id, ts, me, MeshEngine.name, text) { call("sign", it) as ByteArray }
        val p = Packet(id, cr.hideTime(ts, cipher), MeshRouter.MAX_TTL, cipher)
        againFile(c).writeBytes(p.encode())
        log("epochSay ${p.id}: taken=${MeshEngine.router?.receive("debug", p)}")
    }

    /**
     * A made-up member for replies, reactions and receipts (debug only): --es member Ben asks to join with a key kept here;
     * let them in with --es approve Ben. Then, all through the real receive path, as Ben's phone would write them:
     * --es memberSay "Ben|text", --es memberReply "Ben|<words of the target>|text" (target "#missing": one this phone doesn't
     * have), --es memberReact "Ben|<words of the target>|👍", --es memberReceipt "Ben|<words of my message>|h3i" (the note
     * may be left out). Ben's packets come over the relays' link name ("nostr", 3 hops), so this phone's receipt notes "h3i".
     * --es memberLeave Ben: Ben's leave note, sealed like memberSayKeyed. --es memberSayKeyed "Ben|text": sealed with Ben's own message key in the epoch in use (a random unused number), as Ben's
     * phone writes; once read, nothing here opens it any more. The packet of the last memberSay or memberSayKeyed is kept for
     * --es again 1, as from a phone that still carries it (a group's timer: shown never again).
     */
    private val members = HashMap<String, java.security.KeyPair>()
    private fun keysOnScreen(): Pair<Any, (String) -> Any?> {
        val cache = MeshEngine::class.java.getDeclaredField("keyCache").apply { isAccessible = true }.get(MeshEngine) as HashMap<*, *>
        val k = synchronized(cache) { cache[MeshEngine.currentCode] } ?: error("no keys for the group on screen")
        return k to { name -> k.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(k) }
    }
    private fun member(c: Context, cmd: String, v: String) {
        val parts = v.split('|'); val who = parts[0]
        if (cmd == "member") {
            val pair = members.getOrPut(who) { P256.newKeyPair() }.also { saveMember(c, who, it) }; val pub = P256.compress(pair.public)
            val cr = GroupCrypto(MeshEngine.currentCode).intros(); val id = Packet.newId(cr.mailbox); val now = System.currentTimeMillis()
            MeshEngine.router?.receive("debug", Packet(id, now, 16, cr.seal(id, now, P256.memberId(pub), who, "",
                extra = byteArrayOf(3) + pub + P256.uncompressed(P256.newKeyPair().public) + MlKem.newKey().first) { P256.sign(pair.private, it) }))
            return log("member $who asks to join")
        }
        val pair = members[who] ?: loadMember(c, who)?.also { members[who] = it } ?: return log("$cmd: no member $who here (--es member $who first)")
        val me = P256.memberId(P256.compress(pair.public)); val (_, call) = keysOnScreen()
        fun target(words: String): About.Target? = if (words == "#missing") About.Target.parse(Packet.newId() + ":" + Packet.newId())
            else MeshEngine.snapshot().lastOrNull { !it.public && it.text.contains(words) }?.mark
        val now = System.currentTimeMillis()
        val p = if (cmd == "memberReceipt") {
            val t = MeshEngine.snapshot().lastOrNull { !it.public && it.text.contains(parts[1]) } ?: return log("memberReceipt: no message with those words")
            val rc = call("getReceipts") as GroupCrypto; val id = Packet.newId(rc.mailbox)
            val text = t.id.take(16) + (parts.getOrNull(2)?.let { " $it" } ?: "")
            val ci = rc.seal(id, now, me, who, text) { P256.sign(pair.private, it) }
            Packet(id, rc.hideTime(now, ci), 16, ci)
        } else {
            val about = when (cmd) {
                "memberReply" -> About.Reply(target(parts[1]) ?: return log("memberReply: no target"))
                "memberReact" -> About.Reaction(target(parts[1]) ?: return log("memberReact: no target"))
                else -> null
            }
            // memberVoice "Ben|<file in the app's files folder>": a voice field (type byte first), its first 500 packets kept.
            val voice = if (cmd == "memberVoice") voiceFile(c, parts[1]) ?: return log("memberVoice: no voice field in ${parts[1]}") else null
            // memberClock "Vera|60": Vera's check-in clock of 60 minutes (0: she stops it), sealed like memberSayKeyed (spec "If I go quiet").
            val deadline = if (cmd == "memberClock") parts.getOrNull(1)?.toLongOrNull()?.let { if (it == 0L) 0L else now + it * 60_000L } ?: return log("memberClock: minutes?") else null
            val text = when (cmd) { "memberSay", "memberSayKeyed" -> parts[1]; "memberVoice" -> Voice.PLACEHOLDER; "memberLeave" -> LeaveNote.TEXT; "memberClock" -> QuietClock.text(deadline!!, now); else -> parts.getOrElse(2) { "" } }
            val fifth = if (cmd == "memberLeave") LeaveNote.field else deadline?.let(QuietClock::field)
            val cr = call("getGroup") as GroupCrypto; val id = Packet.newId(cr.mailbox)
            val ci = if (cmd == "memberSayKeyed" || cmd == "memberLeave" || cmd == "memberClock") {
                val e = (call("getRing") as Keyring).currentOrNull() ?: return log("memberSayKeyed: no epoch")
                val keys = e.messageKeys ?: return log("memberSayKeyed: no message keys"); val i = keys.index(me).takeIf { it >= 0 } ?: return log("memberSayKeyed: $who isn't in the epoch")
                val n = 1_000 + java.security.SecureRandom().nextInt(10_000); val mk = keys.key(i, n) ?: return log("memberSayKeyed: no key")
                cr.sealWith(mk, i, n, id, now, me, who, text, fifth = fifth) { P256.sign(pair.private, it) }
            } else cr.seal(id, now, me, who, text, about = about, voice = voice) { P256.sign(pair.private, it) }
            Packet(id, cr.hideTime(now, ci), 13, ci).also { if (cmd == "memberSay" || cmd == "memberSayKeyed") againFile(c).writeBytes(it.encode()) }
        }
        log("$cmd $who: taken=${MeshEngine.router?.receive(if (cmd == "memberReceipt") "debug" else "nostr", p)}")
    }

    /** Debug members' keys, kept in a file of the debug build so they outlive a reinstall of it (never in a release build). */
    private fun memberFile(c: Context, who: String) = java.io.File(c.filesDir, "debug-member-" + who.filter { it.isLetterOrDigit() })
    private fun saveMember(c: Context, who: String, k: java.security.KeyPair) {
        val o = java.io.ObjectOutputStream(java.io.FileOutputStream(memberFile(c, who))); o.writeObject(k); o.close()
    }
    private fun loadMember(c: Context, who: String): java.security.KeyPair? = runCatching {
        java.io.ObjectInputStream(java.io.FileInputStream(memberFile(c, who))).use { it.readObject() as java.security.KeyPair }
    }.getOrNull()

    // MARK: - Voice notes (debug hooks for the emulator checks)

    private fun files(c: Context, name: String) = java.io.File(c.getExternalFilesDir(null), name)
    /** A voice field from the experiment (type byte, then len ‖ packet), its first 500 packets as frames. */
    private fun voiceFile(c: Context, name: String): ByteArray? {
        val f = runCatching { files(c, name).readBytes() }.getOrNull() ?: return null
        val ps = ArrayList<ByteArray>(); var i = 1
        while (i < f.size) { val n = f[i].toInt() and 0xFF; if (i + 1 + n > f.size) return null; ps += f.copyOfRange(i + 1, i + 1 + n); i += 1 + n }
        return (Voice.forSending(ps) as? Voice.Kept.Ok)?.frames
    }
    private fun rms(p: ShortArray, from: Int = 0) = if (p.size <= from) 0.0 else Math.sqrt((from until p.size).sumOf { (p[it] / 32768.0).let { s -> s * s } } / (p.size - from))
    /** 48 kHz mono 16-bit little-endian, or synthetic speech-like sound for "synth". */
    private fun pcmFile(c: Context, name: String): ShortArray = if (name == "synth") ShortArray(48_000 * 10) { i ->
        val t = i / 48_000.0; val env = 0.5 + 0.5 * Math.sin(2 * Math.PI * 3.0 * t)
        (env * 9000 * (Math.sin(2 * Math.PI * 180 * t) + 0.5 * Math.sin(2 * Math.PI * 720 * t) + 0.3 * Math.sin(2 * Math.PI * 1500 * t)) / 1.8).toInt().toShort()
    } else files(c, name).readBytes().let { b -> ShortArray(b.size / 2).also { java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) } }

    /**
     * --es voiceDecode <field file>: decodes it with MediaCodec (first 500 packets) and says how long it came out.
     * --es voiceEncode <pcm file | synth>: encodes 10 s with this phone's Opus encoder and says what it wrote and whether readers take it.
     * --es voicePcm <pcm file | synth | off>: that sound stands in for the microphone in the real recording path.
     * --es voicePlay 1: plays the newest voice note in the chat, as its play button does.
     */
    private fun voiceHooks(c: Context, i: Intent) {
        i.getStringExtra("voiceDecode")?.let { n -> Thread { runCatching {
            val frames = voiceFile(c, n) ?: return@Thread log("VOICE decode $n: not a field")
            val ps = Voice.split(frames)!!; val t0 = System.nanoTime()
            val d = VoiceAudio.decode(ps)
            log("VOICE decode $n: packets=${ps.size} samples=${d.pcm.size} (expected ${Voice.expectedSamples(ps.size)}, ${"%.3f".format(d.pcm.size / 48_000.0)} s) rate=${d.rate} channels=${d.channels} errors=${d.errors} rms=${"%.4f".format(rms(d.pcm))} took=${(System.nanoTime() - t0) / 1_000_000} ms")
        }.onFailure { log("VOICE decode $n failed: $it") } }.start() }
        i.getStringExtra("voiceEncode")?.let { n -> Thread { runCatching {
            val pcm = pcmFile(c, n).copyOf(48_000 * 10)
            val e = VoiceAudio.encode(pcm, 48_000)
            val sizes = e.packets.map { it.size }
            val firsts = e.packets.groupingBy { "%02x".format(it[0]) }.eachCount()
            val code3 = e.packets.filter { it[0].toInt() and 3 == 3 }.groupingBy { "%02x".format(it.getOrElse(1) { 0 }) }.eachCount()
            log("VOICE encode $n: codec=${e.codec} rate=${e.rate} vbrSupported=${e.vbrAsked} vbrSet=${e.vbrTaken} packets=${e.packets.size} min/avg/max=${sizes.minOrNull()}/${"%.1f".format(sizes.average())}/${sizes.maxOrNull()} distinctSizes=${sizes.distinct().size} total=${sizes.sum()} => ${"%.0f".format(sizes.sum() * 8 / 10.0)} bit/s")
            log("VOICE encode $n: first bytes $firsts; code-3 second bytes $code3; first 8 sizes ${sizes.take(8)}; first packet ${e.packets.firstOrNull()?.take(8)?.joinToString("") { "%02x".format(it) }}")
            val k = Voice.forSending(e.packets)
            val desc = when (k) { is Voice.Kept.Ok -> "OK ${k.packets.size} packets, field ${k.frames.size + 1} bytes, reader accepts=${Voice.packets(byteArrayOf(Voice.TYPE) + k.frames) != null}"; Voice.Kept.TooShort -> "too short"; is Voice.Kept.Refused -> "REFUSED packet ${k.index} first byte %02x".format(k.packet[0]) }
            log("VOICE encode $n: writer check: $desc")
            if (k is Voice.Kept.Ok) { val d = VoiceAudio.decode(k.packets); log("VOICE encode $n: own note decoded: samples=${d.pcm.size} errors=${d.errors} rms in ${"%.4f".format(rms(pcm))} out ${"%.4f".format(rms(d.pcm))}") }
        }.onFailure { log("VOICE encode $n failed: $it") } }.start() }
        i.getStringExtra("voicePcm")?.let { n -> VoiceAudio.fakeInput = if (n == "off") null else runCatching { pcmFile(c, n) }.getOrNull(); log("VOICE microphone stand-in: ${VoiceAudio.fakeInput?.size ?: "off"}") }
        if (i.getStringExtra("voicePlay") != null) whenStarted { main.post {
            val m = MeshEngine.snapshot().lastOrNull { Voice.playable(it) } ?: return@post log("VOICE play: no voice note")
            VoiceAudio.toggle(m.id, m.voice!!) { log("VOICE play: failed") }; log("VOICE play ${m.id}")
        } }
    }

    /** --es again 1: the packet of the last --es epochSay arrives once more, as from a phone that still carries it. */
    private fun again(c: Context) {
        val p = runCatching { Packet.decode(againFile(c).readBytes()) }.getOrNull() ?: return log("again: no packet kept")
        log("again ${p.id}: taken=${MeshEngine.router?.receive("debug-again", p)}")
    }

    /** --es link 1: a link that takes every packet and brings none (this phone's messages then say "Sent"). --es link 0: it is gone. */
    private fun link(on: Boolean) {
        if (on) { MeshEngine.sender = { _, _ -> }; MeshEngine.router?.peerUp("debug-link") } else MeshEngine.router?.peerDown("debug-link")
        log("debug link ${if (on) "up" else "down"}")
    }

    /**
     * Mixed-group test hooks, e.g.
     * adb shell am start -W --activity-clear-task -n it.darkproof.mesh/it.blackout.mesh.MainActivity --es join "Ann|<code>"
     * then --es samples 1 to send a check-in with battery and position, a place, a photo and a text. To link an iPhone
     * Simulator: adb forward tcp:7777 tcp:7777, then type 127.0.0.1:7777 in the iPhone's Wi-Fi network address field.
     */
    fun debugIntent(c: Context, i: Intent) {
        i.getStringExtra("relays")?.let { c.getSharedPreferences("mesh", 0).edit().putString("debugRelays", it).commit() }
        // --es dumpChat 1: what this phone shows, for the mock-relay tests (never the group code).
        if (i.getStringExtra("dumpChat") != null) whenStarted {
            MeshEngine.snapshotAll().forEach { m -> log("CHAT ${m.id} ts=${m.ts} verified=${m.verified} public=${m.public} from=${m.name}: ${m.text.take(80)}") }
            log("CHAT end")
        }
        // --es internet 0: no public relays (tests). Read by MeshEngine.start; relinks if already up.
        i.getStringExtra("internet")?.let { v ->
            c.getSharedPreferences("mesh", 0).edit().putBoolean("internet", v == "1").commit()
            if (MeshEngine.router != null) MeshEngine.setInternet(v == "1")
        }
        // --es fakeIn 5: arrives 5 s later, e.g. after pressing Home (notifications instead of banners).
        val delay = (i.getStringExtra("fakeIn")?.toLongOrNull() ?: 0L) * 1000
        i.getStringExtra("fake")?.let { k -> main.postDelayed({ whenStarted { Thread { fake(c, k) }.start() } }, delay) }
        i.getStringExtra("tor")?.let { Tor.setEnabled(c, it == "1"); if (MeshEngine.nostr != null) MeshEngine.setTor(it == "1") } // relink if already up
        i.getStringExtra("join")?.let { j ->
            Groups.setUp(c, j.substringBefore('|'), SavedGroup("Group", j.substringAfter('|')))
            // --es found 1 with it: as if this phone created the group (v0.6: it makes the first epoch).
            if (i.getStringExtra("found") != null) MeshEngine.found(c, j.substringAfter('|'))
        }
        i.getStringExtra("joinLink")?.let { j -> // Name|darkproof://join?…: like join, through the code that reads an invite
            Groups.readInvite(j.substringAfter('|'))?.let { g -> Groups.setUp(c, j.substringBefore('|'), SavedGroup("Group", g.code, g.pins)) }
        }
        if (i.getStringExtra("logPins") != null) whenStarted { log("pins ${Groups.code(c)?.let(MeshEngine::invitePins) ?: "none"}") } // key hashes, not the group code
        log("debug intent: samples=${i.getStringExtra("samples")} started=${MeshEngine.router != null}")
        if (i.getStringExtra("aiSelfTest") != null) whenStarted { aiSelfTest(c) }
        if (i.getStringExtra("codeHash") != null) noteCode(Groups.code(c))
        // --es appLock 1: as if turned on in Settings, even without a screen lock. --es lockNow 1: locks at once.
        i.getStringExtra("appLock")?.let { v -> AppLock.set(c, v == "1"); (c as? MainActivity)?.let { a -> runCatching { a.setAppLock(v == "1") } } } // state isn't there yet on a first start
        if (i.getStringExtra("lockNow") != null) (c as? MainActivity)?.lockNow()
        // By name, among the people this phone has heard from (--es makeAdmin Ben, --es removeMember Marco, --es checked Ben).
        fun named(n: String) = Person.all(MeshEngine.snapshot(), MeshEngine.deviceId).firstOrNull { it.name == n }?.id
            ?: MeshEngine.otherMembers(MeshEngine.currentCode).firstOrNull { it.second == n }?.first // a member who hasn't written yet
        // --es tab 3: shows that tab. --es sheet security | joins | settings | "person|<id>": opens that sheet (Sheet.save's words, | for a new line).
        i.getStringExtra("tab")?.toIntOrNull()?.let { t -> main.postDelayed({ (c as? MainActivity)?.let { a -> runCatching { a.state.tab = t } } }, 600) }
        i.getStringExtra("sheet")?.let { v -> main.postDelayed({ (c as? MainActivity)?.let { a -> runCatching { a.state.sheet = Sheet.restore(v.replace("|", "\n")) } } }, 800) }
        // --es highRisk 1: high-risk mode on, without the phone's unlock (an emulator has no screen lock). --es cleanUp 1: the
        // clean-up now, by the clock that --es ahead moves.
        i.getStringExtra("highRisk")?.let { v -> whenStarted { main.post { (c as? MainActivity)?.let { a -> switchHighRisk(a, a.state, v == "1"); log("high-risk mode ${if (v == "1") "on" else "off"}") } } } }
        if (i.getStringExtra("cleanUp") != null) whenStarted { MeshEngine.cleanUp(); log("clean-up asked for") }
        // --es dumpKeys 1: the names (never the contents) of what the Keystore and the vault hold, to see what Leave and Erase removed.
        if (i.getStringExtra("dumpKeys") != null) {
            val ks = runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().sorted() }.getOrDefault(emptyList())
            log("KEYS keystore ${ks.size}: " + ks.joinToString(" ") { it.replace(Regex("[0-9a-f]{32}")) { m -> m.value.take(6) + "…" } })
            for (f in listOf("vault", "mesh", "read", "ai", "ready", "battery")) {
                val names = c.getSharedPreferences(f, 0).all.keys.sorted()
                log("KEYS prefs $f ${names.size}: " + names.joinToString(" ") { it.replace(Regex("[0-9a-f]{32}")) { m -> m.value.take(6) + "…" } })
            }
        }
        // --es scan "Ben|theirs": as if the scanner had read that text while checking Ben, through the app's own judging of it
        // (codeScanned). "theirs", "own" and "other" stand for Ben's code, this phone's, and a code of a key made up now.
        i.getStringExtra("scan")?.let { v -> whenStarted { main.post { (c as? MainActivity)?.let { a ->
            val n = v.substringBefore('|'); val code = MeshEngine.currentCode; val id = named(n) ?: return@post
            val text = when (val t = v.substringAfter('|')) {
                "theirs" -> MeshEngine.publicKeyOf(code, id)?.let(PersonCode::encode) ?: ""
                "own" -> MeshEngine.myPublicKey(code)?.let(PersonCode::encode) ?: ""
                "other" -> PersonCode.encode(P256.compress(P256.newKeyPair().public))
                else -> t
            }
            codeScanned(a.state, code, id, n, text); a.state.sheet = Sheet.Check(id)
        } } } }
        i.getStringExtra("ahead")?.let { ahead = it.toLong() * 86_400_000L }
        // --es quietAhead 61: check-in clocks are judged 61 minutes ahead (spec "If I go quiet"), and a pass runs now.
        // --es myClock 60: this phone starts its own clock of 60 minutes in the group on screen (0 stops it), as the screen's Start does.
        i.getStringExtra("quietAhead")?.let { v -> whenStarted { MeshEngine.quietAhead = (v.toLongOrNull() ?: 0L) * 60_000L; MeshEngine.quietPass(); log("quiet: judged ${v} min ahead") } }
        i.getStringExtra("myClock")?.let { v -> whenStarted { Thread { val m = v.toLongOrNull() ?: 0L
            log("quiet: own clock ${MeshEngine.sendClock(MeshEngine.currentCode, { ts -> if (m == 0L) 0L else ts + m * 60_000L }, null)}") }.start() } }
        i.getStringExtra("link")?.let { v -> whenStarted { link(v == "1") } }
        // --es clip 1: what kind of thing is on the clipboard (its label, never its text), a moment later when the screen has the focus.
        if (i.getStringExtra("clip") != null) main.postDelayed({
            val board = c.getSystemService(android.content.ClipboardManager::class.java)
            log("clipboard: " + (if (board.hasPrimaryClip()) "holds \"${board.primaryClipDescription?.label}\"" else "empty"))
        }, 1_500)
        // --es leaveCold 1 (with a fresh start of the app): leaves the group in use before the engine has started, as when no engine runs.
        if (i.getStringExtra("leaveCold") != null) Groups.code(c)?.let { code ->
            log("leave cold: engine running=${MeshEngine.router != null}")
            MeshEngine.leave(c, code) { next -> log("left cold: next group ${if (next == null) "none" else "there"}, engine running=${MeshEngine.router != null}") }
        }
        i.getStringExtra("epochSay")?.let { t -> whenStarted { Thread { runCatching { epochSay(c, t) }.onFailure { log("epochSay failed: $it") } }.start() } }
        if (i.getStringExtra("again") != null) whenStarted { Thread { again(c) }.start() }
        // --es memberLeave Vera: Vera's leave note (spec "Leaving the group"), sealed with her own message key like memberSayKeyed.
        // --es eraseDry 1: "Erase everything" only shows its steps and its last screen; nothing is deleted (0 to stop).
        i.getStringExtra("eraseDry")?.let { Wipe.dryRun = it == "1"; log("erase dry run ${if (Wipe.dryRun) "on" else "off"}") }
        for (cmd in listOf("member", "memberSay", "memberSayKeyed", "memberReply", "memberReact", "memberReceipt", "memberVoice", "memberLeave", "memberClock"))
            i.getStringExtra(cmd)?.let { v -> whenStarted { Thread { runCatching { member(c, cmd, v) }.onFailure { log("$cmd failed: $it") } }.start() } }
        voiceHooks(c, i)
        if (i.getStringExtra("weeklyNow") != null) whenStarted { Thread { MeshEngine.weeklyCheck(); log("weekly check done") }.start() }
        // --es makeAdmin Ben, --es removeMember Marco: by name, among the people this phone has heard from.
        i.getStringExtra("makeAdmin")?.let { n -> whenStarted { named(n)?.let { id -> MeshEngine.makeAdmin(MeshEngine.currentCode, id) { log("make admin $n: $it") } } ?: log("make admin: nobody called $n") } }
        i.getStringExtra("say")?.let { t -> whenStarted { Thread { MeshEngine.send(t); log("said") }.start() } } // to the group on screen only
        i.getStringExtra("approve")?.let { n -> whenStarted { MeshEngine.joinRequests(MeshEngine.currentCode).firstOrNull { it.name == n }?.let { r -> MeshEngine.approveJoin(MeshEngine.currentCode, r.id) { log("approve $n: $it") } } ?: log("approve: nobody called $n is asking") } }
        i.getStringExtra("removeMember")?.let { n -> whenStarted { named(n)?.let { id -> MeshEngine.remove(MeshEngine.currentCode, id) { log("remove $n: $it") } } ?: log("remove: nobody called $n") } }
        if (i.getStringExtra("dumpPhotos") != null) whenStarted { // writes every photo in the chat to the app's files folder
            MeshEngine.snapshot().filter { it.photo != null }.forEach { m -> java.io.File(c.getExternalFilesDir(null), "photo-${m.id}.jpg").writeBytes(m.photo!!) }
            log("dumped photos")
        }
        if (i.getStringExtra("badPhoto") != null) whenStarted {
            // A JPEG header claiming 8000x8000 pixels (about 256 MB decoded), then nothing: phones must not try to decode it.
            val b = intArrayOf(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x1F, 0x40, 0x1F, 0x40, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01, 0xFF, 0xD9).map { it.toByte() }.toByteArray()
            MeshEngine.send(Photo.text("Crafted 8000x8000 header"), photo = b)
            MeshEngine.send("Public photo test", public = true, photo = b)
            log("interop: sent crafted photo")
        }
        if (i.getStringExtra("samples") != null) whenStarted {
            val spot = Spot(45.46600, 9.19200, 8)
            MeshEngine.send(CheckIn.SAFE.text(81, spot))
            MeshEngine.send(Place(PlaceKind.SHELTER, "Gym open all night", spot).text, public = true)
            val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(Color.rgb(230, 140, 40)); drawCircle(160f, 120f, 70f, android.graphics.Paint().apply { color = Color.WHITE }) }
            val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
            MeshEngine.send(Photo.text("From Android"), photo = jpeg)
            MeshEngine.send("Hello from Android")
            log("interop: sent check-in, place, photo (${jpeg.size} bytes), text")
        }
    }
}
