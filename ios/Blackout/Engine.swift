import Foundation
import CryptoKit
import CoreBluetooth
import UIKit
import UserNotifications
import BlackoutCore

/// Owns the one Engine. It starts as soon as the app process starts, if a group is set up, so it also runs when iOS
/// relaunches DarkProof in the background for Bluetooth (BleLink's restore identifiers), with no screen at all.
final class Mesh: ObservableObject {
    static let shared = Mesh()
    @Published private(set) var engine: Engine?

    /// Starts the mesh for the saved group, if there is one. Safe to call more than once.
    func startIfSetUp() {
        let d = UserDefaults.standard
        guard engine == nil, !stopping, let code = GroupSecrets.shared.current, !code.isEmpty else { return }
        engine = Engine(name: d.string(forKey: "name") ?? "", code: code)
    }

    private var stopping = false // main thread: an engine is being shut down, and none starts until it has

    /// Shuts the engine down for good (Settings: a group was left, or everything is erased). The screens lose it at
    /// once. `done` runs on the main thread when its links are closed and nothing of it runs or writes any more
    /// (`Engine.shutDown`), with what was removed of `code`'s group if one was named. Only then can an engine start
    /// again. Main thread. False, and nothing happens, while another shutdown is still going on.
    /// `keeping`: a leave note's packet that stays in the store, to be carried (`Engine.removeStored(of:keeping:)`).
    @discardableResult func stop(leaving code: String? = nil, keeping note: String? = nil, then done: @escaping (Engine.Left?) -> Void) -> Bool {
        guard !stopping else { return false }
        stopping = true
        let e = engine
        engine = nil
        let finish: (Engine.Left?) -> Void = { left in self.stopping = false; done(left) }
        if let e { e.shutDown(leaving: code, keeping: note, then: finish) }
        else if let code { // no engine is running, and the group goes all the same: what can be removed of it without one
            DispatchQueue.global(qos: .userInitiated).async {
                let left = Engine.removeStored(ofGroupWithCode: code)
                DispatchQueue.main.async { finish(left) }
            }
        }
        else { DispatchQueue.main.async { finish(nil) } }
        return true
    }

    /// Starts the mesh right after setup. Bluetooth starts first; the Wi-Fi links (which ask for Local Network)
    /// only once the Bluetooth question is answered, so iOS asks one thing at a time.
    func start(name: String, code: String) {
        if engine == nil && !stopping { engine = Engine(name: name, code: code, askInOrder: true) }
    }
}

/// State shared between the transports and the UI, like the Android MeshEngine.
final class Engine: ObservableObject {
    static let maxTtl = MeshRouter.maxTtl
    private static let handedOffMax = 2_000  // "Sent" labels remembered across restarts
    private static let maxPublic = 2_000     // Public messages kept on the phone; older ones are removed
    private static let week: Int64 = 7 * 24 * 3_600_000
    /// This group's messages and the Public channel's, oldest first, without blocked people and reported messages.
    @Published private(set) var messages: [Message] = []
    /// Every saved group's messages and the Public channel's, likewise; `Message.group` tells which group.
    @Published private(set) var allMessages: [Message] = []
    @Published private(set) var status: [String: String] = [:] // transport -> status line
    @Published private(set) var phones = 0        // other devices linked directly, each counted once
    @Published private(set) var bluetoothLinks = 0
    @Published private(set) var wifiLinks = 0
    @Published private(set) var wifiNetworkLinks = 0 // phones linked over a Wi-Fi network (WifiLink)
    @Published private(set) var radioLinked = false
    @Published private(set) var meshcoreLinked = false
    @Published private(set) var relaysLinked = 0     // internet relays connected right now (NostrLink)
    @Published private(set) var throughTor: Bool?    // "Connect through Tor": true when checked, false while waiting
    @Published private(set) var linkedNodes: Set<String> = [] // node ids linked directly right now
    @Published private(set) var name: String
    @Published private(set) var deliveries = Deliveries()  // who confirmed which message (spec/FORMAT.md, "Delivery receipts")
    @Published private(set) var handedOff: Set<String> = [] // ids of my messages given to a phone, radio or the relays
    /// Over which kind of link each of those first left this phone, and when ("How it got there"); main thread.
    @Published private(set) var leftOver: [String: Journey.Left] = [:]
    /// The members' checked reactions (spec/FORMAT.md, "Reactions"); main thread.
    @Published private(set) var reactions = Reactions()
    /// Messages that are here but hidden on this phone (a blocked sender, a hidden or reported message), by what a
    /// reply to them names (`About.Target.key`): a reply to one says so instead of quoting it. Main thread.
    @Published private(set) var hiddenTargets: Set<String> = []
    /// A check-in waiting for its GPS fix (up to 10 s): the buttons show "Sending…" and stay disabled meanwhile.
    @Published private(set) var checkInSending: CheckIn?
    /// Changes whenever a group's check-in clocks change (spec "If I go quiet"): the screens read them from the groups' books.
    @Published private(set) var quietVersion = 0
    private var quietQueued = false          // main thread: a pass of the clocks is on its way
    private var quietWake: DispatchWorkItem? // main thread: the next pass, at the nearest deadline or reminder
    @Published private(set) var bluetooth = CBManagerState.unknown  // this phone's Bluetooth: off, not allowed…
    @Published private(set) var localNetworkDenied = false          // Local Network refused: no Wi-Fi links
    @Published private(set) var networkStarted = false              // the Wi-Fi links started (after the Bluetooth question)
    @Published private(set) var torSlow = false                     // "Connect through Tor" found no way out for 2 minutes
    private var torWaitingSince: Date?
    /// Marks this phone's own messages on this phone only (`Message.sender`); never sent: packets carry member ids.
    let deviceId: String
    /// The node id other phones see when they link (spec/FORMAT.md, "Link ids"): random, new at every app start,
    /// unrelated to member ids, so a phone seen over the air can't be matched to a person or followed across restarts.
    let linkId: String = {
        var b = [UInt8](repeating: 0, count: 6)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        return b.map { String(format: "%02x", $0) }.joined()
    }()
    let startedAt = Int64(Date().timeIntervalSince1970 * 1000)

    /// What a group's code opens (spec/DESIGN-v0.6-groups.md, "Keys"): introductions, new keys, and messages from phones not in yet.
    final class Door: @unchecked Sendable {
        let code: String, crypto: GroupCrypto, receipts: GroupCrypto, intros: GroupCrypto, rekeys: GroupCrypto, id: String
        init(_ code: String) {
            self.code = code; crypto = GroupCrypto(code: code); receipts = crypto.receipts(); intros = crypto.intros(); rekeys = crypto.rekeys()
            id = crypto.groupId()
        }
    }

    /// A saved group's keys (spec/FORMAT.md, "Encryption", "Delivery receipts", "Introductions"), this phone's member
    /// key there (Keychain), and the members' public keys this phone knows (`known`, guarded by `lock`).
    final class Keys: @unchecked Sendable {
        private var doors: (now: Door, old: [Door]) // lock
        private var state: (removedAt: (number: Int64, rank: Int)?, removedBy: String?, closed: Bool, rejoiningSince: Int64, leftOut: LeftOut?) = (nil, nil, false, 0, nil) // lock
        /// The door in use. It changes when someone is removed; the old ones stay a week, for removals that crossed.
        var door: Door { lock.withLock { doors.now } }
        var oldDoors: [Door] { lock.withLock { doors.old } }
        func setDoors(_ now: Door, old: [Door]) { lock.withLock { doors = (now, old) } }
        var code: String { door.code }
        var intros: GroupCrypto { door.intros }
        var rekeys: GroupCrypto { door.rekeys }
        /// This phone was removed from the group: that removal's epoch number and its maker's rank, and `removedBy` its maker.
        var removedAt: (number: Int64, rank: Int)? { get { lock.withLock { state.removedAt } } set { lock.withLock { state.removedAt = newValue } } }
        var removedBy: String? { get { lock.withLock { state.removedBy } } set { lock.withLock { state.removedBy = newValue } } }
        /// A new key that lists this phone but gave it no part it can open (`Keyring.Taken.leftOut`): its number and rank, and
        /// how many keys this phone has made to get back in.
        typealias LeftOut = (number: Int64, rank: Int, tries: Int)
        var leftOut: LeftOut? { get { lock.withLock { state.leftOut } } set { lock.withLock { state.leftOut = newValue } } }
        var removed: Bool { removedAt != nil }
        /// This phone had been away past the weekly key and asked to come back (its new key is the request): since when, or 0.
        var rejoiningSince: Int64 { get { lock.withLock { state.rejoiningSince } } set { lock.withLock { state.rejoiningSince = newValue } } }
        var rejoining: Bool { rejoiningSince > 0 }
        /// How long after the week is up this phone makes the new key.
        let weeklyDelay = Int64.random(in: 0..<Keyring.maxDelayMs)
        /// Still joining or rejoining, and a removal came through this door: this invite no longer works.
        var closed: Bool { get { lock.withLock { state.closed } } set { lock.withLock { state.closed = newValue } } }
        /// Never changes for the group, even when its code does: member keys hang off it.
        let id: String
        /// The group's random epoch secrets. Empty while this phone is joining.
        let ring = Keyring()
        var joined: Bool { ring.currentOrNil != nil }
        /// Written with: the current epoch's keys, or the door's while joining.
        var group: GroupCrypto { ring.currentOrNil?.group ?? door.crypto }
        var receipts: GroupCrypto { ring.currentOrNil?.receipts ?? door.receipts }
        var members: [String] { ring.currentOrNil?.members ?? [] }
        let key: MemberKeys.SigningKey, pub: Data, me: String
        /// The HPKE keys other members seal new epoch secrets to: a new one every week.
        let encs: EncKeys
        var encPub: Data { encs.currentPub }
        var isPublic: Bool { code == GroupCrypto.publicCode }
        let lock = NSLock(), moveLock = NSLock()
        var known: [String: Known] = [:] // member id -> their keys and name
        let mine: MemberKey.Checker
        init(code: String) {
            let d = Door(code)
            doors = (d, [])
            id = Vault.get("origin.\(d.id)").flatMap { String(data: $0, encoding: .utf8) } ?? d.id
            key = MemberKeys.forGroup(id); pub = key.publicKey.compressedRepresentation; me = MemberKey.id(pub)
            encs = EncKeys(id)
            mine = MemberKey.Checker(pub)!
            #if DEBUG
            DebugLog.write("member keys: signing \(key.inChip ? "in the Secure Enclave" : "in the Keychain"), encryption \(encs.inChip ? "in the Secure Enclave" : "in the Keychain")")
            #endif
        }
        func sign(_ msg: Data) throws -> Data { try key.sign(msg) }
        func checker(_ id: String) -> MemberKey.Checker? { id == me ? mine : lock.withLock { known[id]?.checker } }

        /// The group's timer for disappearing messages as this phone last saw it, and since when (`Disappear.Watch`):
        /// a value deletes nothing until it has been here ten minutes. Saved, so a restart doesn't cut the wait short.
        private var timerWatch = Disappear.Watch() // lock
        var watch: Disappear.Watch { lock.withLock { timerWatch } }
        func loadWatch() { if let w = UserDefaults.standard.string(forKey: "timer.\(id)").flatMap(Disappear.Watch.init(saved:)) { lock.withLock { timerWatch = w } } }
        /// Notes the timer the keyring has now. True if the value changed: its ten minutes start at `now`.
        @discardableResult func observeTimer(now: Int64) -> Bool {
            guard !isPublic else { return false }
            let seconds = ring.timer.seconds
            let (changed, saved) = lock.withLock { () -> (Bool, String) in let was = timerWatch; timerWatch.observe(seconds, now: now); return (was.seconds != timerWatch.seconds, was == timerWatch ? "" : timerWatch.saved) }
            if !saved.isEmpty { UserDefaults.standard.set(saved, forKey: "timer.\(id)") }
            return changed
        }

        /// Members who left with a note, and the removals that followed (spec "Leaving the group", `LeaveBook`). Saved
        /// under the group's id, so leaving the group or erasing the phone takes it with the group's other preferences.
        private var leaveBook = LeaveBook() // lock
        var leaving: LeaveBook { lock.withLock { leaveBook } }
        func loadLeaving() { let s = UserDefaults.standard.stringArray(forKey: "leaving.\(id)") ?? []; lock.withLock { leaveBook = LeaveBook(saved: s) } }
        /// Changes the records; saved if `change` says it changed them.
        @discardableResult func changeLeaving(_ change: (inout LeaveBook) -> Bool) -> Bool {
            let saved: [String]? = lock.withLock { change(&leaveBook) ? leaveBook.saved : nil }
            guard let saved else { return false }
            if saved.isEmpty { UserDefaults.standard.removeObject(forKey: "leaving.\(id)") } else { UserDefaults.standard.set(saved, forKey: "leaving.\(id)") }
            return true
        }

        /// The members' check-in clocks (spec "If I go quiet", `QuietBook`), this phone's own among them. Saved under the
        /// group's id, apart from the chat, in the app's own files beside the day book (excluded from backups, not in the
        /// preferences): a clock outlives its message when the group's timer or high-risk mode deletes it, and app
        /// restarts. Leaving the group or erasing the phone deletes it.
        private var quietBook = QuietBook() // lock
        var quiet: QuietBook { lock.withLock { quietBook } }
        func loadQuiet() { let s = QuietFile.read(id); lock.withLock { quietBook = QuietBook(saved: s) } }
        /// Changes the clocks; saved if `change` says it changed them.
        @discardableResult func changeQuiet(_ change: (inout QuietBook) -> Bool) -> Bool {
            let saved: [String]? = lock.withLock { change(&quietBook) ? quietBook.saved : nil }
            guard let saved else { return false }
            QuietFile.write(id, saved)
            return true
        }
    }

    /// A member's public keys, from their introduction.
    /// Group id -> what was written before an admin let this phone in. Main thread.
    private var waitingToJoin: [String: [(text: String, photo: Data?, voice: Data?)]] = [:]

    struct Known { let checker: MemberKey.Checker; let enc: Data?; let name: String; var encAt: Int64 = 0; var pq: Data? = nil; var seen: Int64 = 0 } // seen: when it last changed here

    /// A packet opened with one of `Keys`: not shown before its signature is checked (spec "Signatures").
    private enum Seen {
        case msg(Message, Keys, GroupCrypto.Opened, Bool) // the last: from this phone's copy
        case receipt(Message, Keys, GroupCrypto.Opened)
        case intro(Keys, String, MemberKey.Checker, Data, String, Int64, Data?) // keys, member id, its key, encryption key, name, when, ML-KEM key
        case newKey(Keys, GroupCrypto.Opened, Rekey, String, Door) // keys, opened packet, rekey, packet id, the door it came through
        case forged                                       // an introduction that doesn't hold up
    }

    /// A message or receipt from a member whose public key isn't known yet; checked when the introduction comes.
    /// `row`: for a message, the chat row it is shown as (its store name) so the rescan binds its check to that row and
    /// not to the bare packet id, which another packet may share; nil for a receipt (receipts have no row).
    /// `reaction`: for a reaction (or a type 0x02 message that isn't one), the name its packet goes by and what it says, if anything: it has no row either.
    private struct Pending { let k: Keys; let o: GroupCrypto.Opened; let receipt: Message?; var row: String? = nil; var reaction: (name: String, entry: Reactions.Entry?)? = nil }
    /// What waits for a key, each group and the Public channel apart (`KeyWait`). Main thread.
    private var unchecked = KeyWait<ObjectIdentifier, Pending>()
    private static let maxKnown = 500, introEvery: Int64 = 3 * 24 * 3_600_000
    private let keyLock = NSLock()
    private let knownLock = NSLock()
    private var knownDirty: [String: Keys] = [:] // group id -> keys whose saved key list is behind memory; knownLock
    private var knownUnread: Set<String> = [] // group ids whose key-list file is there but couldn't be read (phone not unlocked yet): never written over; knownLock
    private let rescanLock = NSLock(), rescanFlag = Flag() // new keys arriving together: one reading of the store
    private final class Flag { var on = false }
    private var keyCache: [String: Keys] = [:] // code -> keys: each derivation takes 32 MiB of scrypt, so only once
    private let cryptoLock = NSLock()          // guards the three below: read from the router's thread
    private var _active: Keys?                 // the group on screen
    private var _others: [Keys] = []           // the other saved groups: read, alerted and confirmed too
    private var _public: Keys?                 // the Public channel's
    private var keySet: (active: Keys?, others: [Keys], pub: Keys?) { cryptoLock.withLock { (_active, _others, _public) } }
    private var activeCode: String             // main thread: the group on screen, even while its keys are derived

    private let idsLock = NSLock()
    private var mine = Set<String>()       // ids of messages written on this phone; guarded by idsLock
    private var myReceipts = Set<String>() // ids of receipts written on this phone (they go up to the relays first); idsLock
    private var sentIds: RecentIds         // my messages already handed off, saved on the phone; idsLock
    private var sentOver: [String: String] = [:] // of those, over which link each first left and when (`Journey.Left.saved`), saved on the phone; idsLock
    private static let sentOverKey = "handedOffOver"
    /// Receipt and reaction packets this phone can open, kept off LoRa: each by its key (`MeshRouter.key`), not its id,
    /// so that a receipt or reaction sent under the id of a message doesn't keep that message off the radio. idsLock.
    private var receiptIds = RecentIds(max: 20_000)
    private var shownAs: [String: String] = [:] // what was signed (`GroupCrypto.Opened.mark`) -> the row that shows it; idsLock
    /// Until the store has been read once this phone doesn't know which packets it wrote (`mine`, `myReceipts`), and a
    /// link can be up before that: iOS relaunches the app because a phone connected. What is handed to a link meanwhile
    /// is noted here, packet id -> the link (nil: a relay took it), and settled when the store is read (`load`): one of
    /// this phone's own then counts as handed off, for "Sent" and for "Keeps less", which never removes what hasn't
    /// left. Nil once every saved group's key has read the store. idsLock.
    private var early: [String: String?]? = [:]

    private let storeLock = NSLock()
    private var index: [String: StoreCap.Entry] = [:] // every stored packet, for the store cap; guarded by storeLock
    private var indexBytes = 0                        // storeLock
    private var doorCount = 0, doorBytes = 0          // what the index holds of `StoreCap.Kind.door`; storeLock
    private var joinAlertAt: [String: Int64] = [:]    // group id -> when this phone last alerted "asks to join" there; storeLock
    private var allKeys = false                       // every saved group's key was tried on the whole store; storeLock
    private var compacting = false                    // storeLock
    private var raisedTtl: [String: Int] = [:]        // stored packets whose ttl a later copy raised (MeshRouter.key → ttl), until written; storeLock
    /// "Keeps less" (high-risk mode, `KeepsLess`): the stored packets that opened as a message or a receipt of a group on
    /// this phone, by the name each goes by: the packet's key, its group's id, and whether this phone wrote it. Chosen by
    /// what a packet opened as, never by its mailbox. storeLock.
    /// `opens`: a key on this phone still opens the packet (a receipt, a message sealed with the epoch's key or still
    /// waiting for its sender's key); false once its own message key is deleted. The group's timer keeps carrying only
    /// what this phone can no longer read (`Disappear.carries`).
    private var covered: [String: (key: String, group: String, own: Bool, opens: Bool)] = [:]
    private var dueAt: Int64?                         // when the next clean-up for a group's timer is set to run, if one is; storeLock
    /// Since when each of those has been on this phone, and the gone list: what the rule removed, which may come back
    /// from a neighbour and is then carried but never opened again (`DayBook`). Kept whether the mode is on or off, so
    /// that switching it on counts from the real arrivals. Saved in "daybook.txt". storeLock.
    private var book = DayBookFile.read()
    private var bookQueued = false                    // a save of `book` is on its way; storeLock
    /// Saved once every saved group's key has read the store under a version that keeps those times: what was stored
    /// before has none, and counts from its own date (`load`).
    private static let timedKey = "timed"
    private let work = DispatchQueue(label: "engine", qos: .userInitiated) // loading, keys and compaction, in order
    private let stopLock = NSLock()
    private var stopFlag = false // stopLock
    private var leavingCodeHeld: String? // stopLock: `leavingCode`
    /// `shutDown` was called: what was still queued or timed must write, send and start nothing. Any thread.
    private var stopped: Bool { stopLock.withLock { stopFlag } }

    private var raw: [Message] = []          // main thread: every readable message, before moderation
    private var rawIds = Set<String>()       // main thread
    private var pendingAcks: [(id: String, key: Keys, note: Journey.Note?)] = [] // main thread: messages to confirm, with their group's keys and how each arrived
    private var firstAck: Int64 = 0, lastAck: Int64 = 0            // main thread

    private let store = PacketStore()
    /// Packets that arrived under an id another packet had already taken: each goes by its key (`StoreCap.name`).
    private let more = PacketStore(file: "more.bin", naming: .key)
    private let history = PacketStore(file: "history.bin")
    private(set) var router: MeshRouter!
    private var ble: BleLink!
    private var mc: MultipeerLink!
    private(set) var lora: LoraLink!
    private(set) var meshcore: MeshCoreLink!
    private(set) var wifi: WifiLink!
    private(set) var nostr: NostrLink!
    /// Packets a relay brought first: they never go back up, after a restart either (spec "Publish"). Saved by the relay links.
    private let fromRelays = FromRelays(UserDefaults.standard.string(forKey: FromRelays.pref) ?? "")

    /// Starts the transports at once; keys and stored messages are read in the background (no scrypt key
    /// derivation or store reading on the main thread), then published.
    init(name: String, code: String, askInOrder: Bool = false) {
        let d = UserDefaults.standard
        let upgrade = d.string(forKey: "device") != nil // this phone ran DarkProof before (a device id exists from then)
        if let id = d.string(forKey: "device") { deviceId = id } else {
            deviceId = String(UUID().uuidString.lowercased().prefix(12)) // same shape as Android ids, e.g. 7c0d995b-2c4
            d.set(deviceId, forKey: "device")
        }
        self.name = name
        activeCode = code
        if !d.bool(forKey: "v06") { // groups saved before v0.6 count as founded here (spec "Making the first epoch")
            let saved = upgrade ? Set(SavedGroups.all().map(\.code) + (GroupSecrets.shared.current.map { [$0] } ?? [])) : []
            GroupSecrets.shared.setFound(Array(Set(GroupSecrets.shared.found).union(saved)))
            d.set(true, forKey: "v06")
            for key in d.dictionaryRepresentation().keys where key.hasPrefix("introAt.") { d.removeObject(forKey: key) } // introductions now carry the encryption key
        }
        sentIds = RecentIds(max: Self.handedOffMax, d.stringArray(forKey: "handedOff") ?? [])
        handedOff = Set(sentIds.list)
        // Only for messages still remembered as handed off: a group that was left took its ids out of that list.
        let over = d.dictionary(forKey: Self.sentOverKey) as? [String: String] ?? [:]
        sentOver = over.filter { handedOff.contains($0.key) }
        if sentOver.count != over.count { d.set(sentOver, forKey: Self.sentOverKey) }
        leftOver = sentOver.compactMapValues(Journey.Left.init(saved:))
        router = MeshRouter(send: { [weak self] peer, p in self?.route(peer, p) },
                            deliver: { [weak self] p in self?.onDeliver(p) },
                            minCipher: MeshRouter.minSealed, maxPerMinute: MeshRouter.linkPerMinute, unlimited: { $0 == NostrLink.peer },
                            raised: { [weak self] p in self?.onRaised(p) })
        mc = MultipeerLink(nodeId: linkId, router: router) { [weak self] s in self?.setStatus("1-mc", s) }
        ble = BleLink(nodeId: linkId, router: router) { [weak self] s in self?.setStatus("2-ble", s) }
        lora = LoraLink(router: router) { [weak self] s in self?.setStatus("3-lora", s) }
        meshcore = MeshCoreLink(router: router) { [weak self] s in self?.setStatus("3b-meshcore", s) }
        wifi = WifiLink(nodeId: linkId, router: router) { [weak self] s in self?.setStatus("4-wifi", s) }
        nostr = NostrLink(router: router, viaTor: TorSetting.isOn(d), scopes: { [weak self] in self?.relayScopes() ?? [] },
                          packetKey: { [weak self] id in self?.packetKey(id) }, fromRelays: fromRelays,
                          onRelays: { [weak self] id in self?.markHandedOff(id, over: .internet) }) { [weak self] s in
            self?.setStatus("5-internet", s)
        }
        nostr.setSaver(Self.saver)
        if d.object(forKey: "internet") as? Bool ?? true { nostr.start() }
        #if DEBUG
        DebugLog.start()
        GroupSecrets.shared.noteForTests()
        BleLink.restoredHook = { DebugLog.write("bluetooth " + $0) }
        #endif
        ble.start()
        lora.start()
        meshcore.start()
        if askInOrder && CBManager.authorization == .notDetermined {
            afterBluetoothAnswer(tries: 240) { [weak self] in self?.startNetwork() }
        } else {
            startNetwork()
        }
        work.async { self.startUp(code) }
        // Back on screen: clocks that ran out while the app was away are settled (spec "If I go quiet").
        NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in self?.quietPass() }
    }

    /// Apple peer-to-peer and Wi-Fi networks: both make iOS ask for Local Network.
    private func startNetwork() {
        guard !stopped else { return }
        mc.start()
        if wifiWanted { wifi.start() }
        networkStarted = true
    }

    /// Waits (at most `tries` × 0.5 s) until the user has answered the Bluetooth question. Main thread.
    private func afterBluetoothAnswer(tries: Int, _ then: @escaping () -> Void) {
        if CBManager.authorization != .notDetermined || tries <= 0 { return then() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.afterBluetoothAnswer(tries: tries - 1, then) }
    }

    /// "Battery saver" (Connections): no Wi-Fi network links and fewer relay requests. Bluetooth keeps running.
    static var saver: Bool { UserDefaults.standard.bool(forKey: "saver") }
    private var wifiWanted: Bool { (UserDefaults.standard.object(forKey: "wifiNetwork") as? Bool ?? true) && !Self.saver }

    func setSaver(_ on: Bool) {
        guard !stopped else { return } // shut down (a group was left, everything erased): a late tap starts no link and saves no setting
        UserDefaults.standard.set(on, forKey: "saver")
        if wifiWanted { wifi.start() } else { wifi.stop() }
        nostr.setSaver(on)
    }

    var statusLines: [String] { status.keys.sorted().compactMap { status[$0] } }

    /// Turns linking over Wi-Fi networks on or off (Connections screen), and remembers it.
    func setWifi(_ on: Bool) {
        guard !stopped else { return }
        UserDefaults.standard.set(on, forKey: "wifiNetwork")
        if wifiWanted { wifi.start() } else { wifi.stop() }
    }

    /// Turns the internet relays on or off (Connections screen), and remembers it.
    func setInternet(_ on: Bool) {
        guard !stopped else { return }
        UserDefaults.standard.set(on, forKey: "internet")
        if on { nostr.start() } else { nostr.stop() }
    }

    /// Turns "Connect through Tor" on or off (Connections screen), and remembers it.
    func setTor(_ on: Bool) {
        guard !stopped else { return }
        UserDefaults.standard.set(on, forKey: "tor")
        nostr.setTor(on)
        OfflineMap.shared.applyNetwork()
    }

    /// The very packet a message is shown from (`MeshRouter.key`), or nil: what a report of that message is about.
    /// `id` is the name the packet goes by in the store (`StoreCap.name`): the packet stored first under an id goes by
    /// that id, any other by its key. Reads the whole store (once per reported message); any thread.
    private func packetKey(_ id: String) -> String? {
        if id.count > 16 { return id }
        var key: String?
        store.forEachNamed { p, name in if name == id { key = MeshRouter.key(p) } }
        return key
    }

    /// This phone's relay mailboxes (spec/FORMAT.md, "Mailboxes"): its group's, the other saved groups', the Public
    /// channel's last. Any thread.
    private func relayScopes() -> [NostrLink.Scope] {
        let set = keySet
        let groups: [NostrLink.Scope] = ([set.active].compactMap { $0 } + set.others).filter { !$0.isPublic }.map { k in
            var boxes: [UInt16] = []
            for m in k.ring.all.map(\.group.mailbox) + ([k.door] + k.oldDoors).map(\.crypto.mailbox) where !boxes.contains(m) { boxes.append(m) }
            return NostrLink.Scope(id: k.id, mailboxes: boxes)
        }
        return groups + [NostrLink.Scope(id: "", mailboxes: [Nostr.publicMailbox])]
    }

    /// The cached keys of `code`, derived the first time (32 MiB of scrypt). Any thread; never holds a lock while
    /// deriving, so the main thread never waits for another group's key. A code its group has moved on from (an old
    /// invite, an alert from before a removal) gives the keys that group has under its new code, if it is saved here: a
    /// second holder would put the old door on the group's current keyring and save over it.
    private func keys(_ code: String, follow: Bool = true) -> Keys {
        if let k = keyLock.withLock({ keyCache[code] }) { return k }
        let k = Keys(code: code)
        if follow, let now = movedTo(k) { return keys(now, follow: false) }
        loadKnown(k)
        loadRing(k)
        return keyLock.withLock { if let other = keyCache[code] { return other }; keyCache[code] = k; return k }
    }

    /// The code `k`'s group has now, if `k` was made from another one and the group is saved on this phone under it (`moveDoor` notes it).
    private func movedTo(_ k: Keys) -> String? {
        guard let now = Vault.get("door.\(k.id)").flatMap({ String(data: $0, encoding: .utf8) }), now != k.code,
              SavedGroups.all().contains(where: { $0.code == now }) else { return nil }
        return now
    }

    // MARK: - Member keys (spec/FORMAT.md, "Signatures", "Introductions")

    /// The public keys saved for `k`'s group: member id -> ["s": seen ms, "p": signing key hex, "e": encryption key hex, "a": when it was introduced, "q": ML-KEM key hex, "n": name].
    /// The list is kept in a file left out of backups ("known.<group id>.plist"): UserDefaults goes into them, and the
    /// list holds names. A list an earlier version kept in UserDefaults is read from there and moved at the next save.
    private func loadKnown(_ k: Keys) {
        let url = Self.knownURL(k.id), d = UserDefaults.standard
        let file = (try? Data(contentsOf: url)).flatMap { try? PropertyListSerialization.propertyList(from: $0, format: nil) } as? [String: [String: String]]
        if file == nil && FileManager.default.fileExists(atPath: url.path) { knownLock.withLock { _ = knownUnread.insert(k.id) } }
        if file != nil { d.removeObject(forKey: "known.\(k.id)") } // moved already
        let old = file == nil ? d.dictionary(forKey: "known.\(k.id)") as? [String: [String: String]] : nil
        let saved = file ?? old ?? [:]
        var out: [String: Known] = [:]
        for (id, v) in saved {
            guard let p = v["p"], let c = MemberKey.Checker(Self.unhex(p)) else { continue }
            out[id] = Known(checker: c, enc: v["e"].map(Self.unhex), name: v["n"] ?? "", encAt: Int64(v["a"] ?? "") ?? 0, pq: v["q"].map(Self.unhex), seen: Int64(v["s"] ?? "") ?? 0)
        }
        k.lock.withLock { k.known = out }
        if old != nil { saveKnownSoon(k) } // into the file, out of UserDefaults
    }

    private static func knownURL(_ id: String) -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("known.\(id).plist")
    }

    /// A member's public keys from a checked introduction: kept (the 500 most recently changed per group stay), and saved
    /// soon after. True if new. Someone who isn't in the group and isn't known here yet is kept only while there is room
    /// among those asking to join (`Joining`).
    @discardableResult private func learn(_ k: Keys, _ id: String, _ c: MemberKey.Checker, enc: Data? = nil, encAt: Int64 = 0, pq: Data? = nil, name who: String = "") -> Bool {
        if !k.isPublic, id != k.me, k.lock.withLock({ k.known[id] == nil }), !Joining.keeps(id, asking: asking(k), listed: k.ring.everyone(), me: k.me) { return false }
        let members = Set(k.members)
        let (fresh, changed): (Bool, Bool) = k.lock.withLock {
            let had = k.known[id]
            let newer = enc != nil && EncSchedule.isNewer(known: had?.encAt ?? 0, ts: encAt)
            let name = who.isEmpty ? had?.name ?? "" : who
            let changed = had == nil || newer || had?.checker.pub != c.pub || had?.name != name
            k.known[id] = Known(checker: c, enc: newer ? enc : had?.enc, name: name, encAt: newer ? encAt : had?.encAt ?? 0, pq: newer ? pq : had?.pq,
                                seen: changed ? Self.nowMs() : had?.seen ?? 0)
            if k.known.count > Self.maxKnown {
                // The least recently changed go, but never someone in the group, or new keys for them couldn't be sealed.
                let old = k.known.filter { !members.contains($0.key) && $0.key != id }.sorted { $0.value.seen < $1.value.seen }
                for (gone, _) in old.prefix(k.known.count - Self.maxKnown) { k.known[gone] = nil } // a flood of made-up members can't fill the memory
            }
            return (had == nil, changed)
        }
        // An introduction that brings nothing new (one read again from the store, or played back) writes nothing.
        if changed { saveKnownSoon(k) }
        return fresh
    }

    /// Saves `k`'s key list 2 s from now, with whatever else changes until then: the whole list is written once, not once
    /// for every introduction that arrives. What isn't saved when the app ends is learned again from the stored packets.
    private func saveKnownSoon(_ k: Keys) {
        let first = knownLock.withLock { () -> Bool in let was = knownDirty.isEmpty; knownDirty[k.id] = k; return was }
        guard first else { return }
        work.asyncAfter(deadline: .now() + 2) { self.saveKnown() }
    }

    /// Writes the key lists that changed: member id -> ["s": when it last changed here, ms, "p": signing key hex, "e":
    /// encryption key hex, "a": when it was introduced, "q": ML-KEM key hex, "n": name]. On `work`.
    private func saveKnown() {
        guard !stopped else { return }
        let all = knownLock.withLock { () -> [Keys] in let a = Array(knownDirty.values); knownDirty = [:]; return a }
        for k in all {
            var saved: [String: [String: String]] = [:]
            for (id, w) in k.lock.withLock({ k.known }) {
                var v = ["s": String(w.seen), "p": Self.hex(w.checker.pub)]
                if let e = w.enc { v["e"] = Self.hex(e); v["a"] = String(w.encAt); v["q"] = w.pq.map(Self.hex) }
                if !w.name.isEmpty { v["n"] = w.name }
                saved[id] = v
            }
            guard !knownLock.withLock({ knownUnread.contains(k.id) }) else { continue } // the file there would be lost
            let url = Self.knownURL(k.id)
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard let data = try? PropertyListSerialization.data(fromPropertyList: saved, format: .binary, options: 0),
                  (try? data.write(to: url, options: .atomic)) != nil else { continue } // UserDefaults keeps it meanwhile
            var u = url, v = URLResourceValues(); v.isExcludedFromBackup = true; try? u.setResourceValues(v)
            UserDefaults.standard.removeObject(forKey: "known.\(k.id)")
        }
    }

    /// `k`'s epoch secrets from the Keychain. A group this phone founded (created here, or saved before v0.6) and that
    /// has none gets epoch 1 now, with this phone as its only member and admin (spec "Making the first epoch").
    private func loadRing(_ k: Keys) {
        guard !k.isPublic else { return }
        if let raw = Vault.get("epochs.\(k.id)"), let list = try? JSONSerialization.jsonObject(with: raw) as? [[String: Any]] {
            for o in list {
                guard let n = (o["n"] as? NSNumber)?.int64Value, let made = (o["made"] as? NSNumber)?.int64Value, let s = o["s"] as? String else { continue }
                func text(_ n: String) -> String? { (o[n] as? String).flatMap { $0.isEmpty ? nil : $0 } }
                func int(_ n: String) -> Int? { (o[n] as? NSNumber)?.intValue }
                k.ring.add(.init(number: n, made: made, secret: Self.unhex(s), members: o["m"] as? [String] ?? [], admins: o["a"] as? [String] ?? [],
                                 code: text("c"), via: text("v"), removes: text("r"), by: text("b"), rank: (o["k"] as? NSNumber)?.intValue ?? .max,
                                 taken: (o["t"] as? NSNumber)?.int64Value, // saved before "t": its date
                                 // The group's timer (spec "Disappearing messages"); none of these in an epoch saved before it: not known.
                                 timerRaw: int("tr"), timerSetHere: o["th"] as? Bool ?? false, timer: int("tv"), timerBy: text("tb"), timerCounts: o["tc"] as? Bool ?? false))
            }
        }
        if let raw = Vault.get("mkeys.\(k.id)") {
            let b = [UInt8](raw); var i = 0
            while i + 68 <= b.count {
                let hash = String(decoding: b[i..<(i + 64)], as: UTF8.self), n = b[(i + 64)..<(i + 68)].reduce(0) { $0 << 8 | Int($1) }
                i += 68
                guard i + n <= b.count else { break }
                // By the epoch's id: two epochs can share a secret. By its hash for keys saved before epochs had an id.
                let all = k.ring.all
                if let e = all.first(where: { $0.id == hash }) ?? all.first(where: { $0.hash == hash }) { e.messageKeys = MessageKeys.decode(Data(b[i..<(i + n)])) }
                i += n
            }
        }
        // The parts it opened, only together with the epochs they brought: without those, the stored new keys are read again.
        if k.joined, let raw = Vault.get("parts.\(k.id)") { k.ring.restoreParts(String(decoding: raw, as: UTF8.self).split(separator: ",").map(String.init)) }
        var via: [String] = []
        for c in k.ring.all.compactMap(\.via) where c != k.code && !via.contains(c) { via.append(c) }
        k.setDoors(k.door, old: via.map(Door.init))
        if k.ring.lastMove?.code == k.code, Vault.get("door.\(k.id)") == nil { Vault.put("door.\(k.id)", Data(k.code.utf8)) } // moved before this was noted
        let d = UserDefaults.standard
        k.rejoiningSince = Int64(d.double(forKey: "rejoining.\(k.id)"))
        if let p = d.string(forKey: "removedAt.\(k.id)")?.split(separator: ":"), p.count >= 2, let n = Int64(p[0]), let r = Int(p[1]) {
            k.removedAt = (n, r); k.removedBy = p.count > 2 ? String(p[2]) : nil
        }
        if d.bool(forKey: "closed.\(k.id)") { k.closed = true; k.ring.closed = true }
        var found = Set(GroupSecrets.shared.found)
        if !k.joined && found.contains(k.code) {
            var b = [UInt8](repeating: 0, count: 32); _ = SecRandomCopyBytes(kSecRandomDefault, 32, &b)
            // A group made here starts with the timer off, and knows it: its first admin says so in every key it makes.
            k.ring.add(.fresh(number: 1, made: Self.nowMs(), secret: Data(b), members: [k.me], admins: [k.me], by: k.me, timerRaw: 0, timerCounts: true))
            saveRing(k)
            found.remove(k.code); GroupSecrets.shared.setFound(Array(found))
            #if DEBUG
            DebugLog.write("epoch 1 made for a group founded here")
            #endif
        }
        k.loadWatch()
        k.observeTimer(now: Self.clock())
        k.loadLeaving()
        k.loadQuiet()
    }

    private func saveRing(_ k: Keys) {
        saveMessageKeys(k)
        let hex: (Data) -> String = { $0.map { String(format: "%02x", $0) }.joined() }
        let timers = k.ring.timers
        let list: [[String: Any]] = k.ring.all.map { e in
            var o: [String: Any] = ["n": e.number, "made": e.made, "s": hex(e.secret), "m": e.members, "a": e.admins,
                                    "c": e.code ?? "", "v": e.via ?? "", "r": e.removes ?? "", "b": e.by ?? "", "k": e.rank, "t": e.taken]
            // The group's timer in this epoch, as worked out last (`Keyring.retime`): kept when the epochs before it are gone.
            if let raw = e.timerRaw { o["tr"] = raw }
            if e.timerSetHere { o["th"] = true }
            if let t = timers[e.id] { if let v = t.seconds { o["tv"] = v }; if let by = t.by { o["tb"] = by }; if t.counts { o["tc"] = true } }
            return o
        }
        Vault.put("epochs.\(k.id)", try? JSONSerialization.data(withJSONObject: list))
        saveParts(k)
    }

    /// The sealed parts `k`'s keyring has opened (`Keyring.openedParts`): saved after the epochs they brought, never before.
    private func saveParts(_ k: Keys) {
        _ = k.ring.partsToSave()
        Vault.put("parts.\(k.id)", Data(k.ring.openedParts.joined(separator: ",").utf8))
    }

    /// Marks `code` as founded on this phone (a new group): its first epoch is made here.
    static func found(_ code: String) {
        let d = UserDefaults.standard
        GroupSecrets.shared.setFound(Array(Set(GroupSecrets.shared.found).union([code])))
    }

    /// True while this phone has the code of `code`'s group but no epoch yet: it is asking to join.
    func joining(_ code: String?) -> Bool {
        guard let code, let k = keyLock.withLock({ keyCache[code] }) else { return false }
        return !k.isPublic && !k.joined
    }

    private func held(_ code: String?) -> Keys? { guard let code else { return nil }; return keyLock.withLock { keyCache[code] } }
    private func memberId(_ k: Keys, _ id: String) -> String { id == deviceId ? k.me : id.hasPrefix("web-") ? String(id.dropFirst(4)) : id }

    /// True if this phone was removed from `code`'s group.
    func removed(_ code: String?) -> Bool { held(code)?.removed ?? false }
    /// True if `code` is an invite this phone is trying to join with and the group has moved on from it.
    func inviteClosed(_ code: String?) -> Bool { held(code).map { $0.closed && (!$0.joined || $0.rejoining) } ?? false }
    /// True while this phone, back after more than a week, waits for someone in `code`'s group to answer.
    func rejoining(_ code: String?) -> Bool { held(code)?.rejoining ?? false }

    /// Makes the weekly new key of every saved group that is due. On `work`.
    func weeklyCheck() { let s = keySet; for k in [s.active].compactMap({ $0 }) + s.others { rekeyIfDue(k); finishRemovals(k); finishLeaves(k); answerLeftOut(k) } }

    private func hourly() {
        work.asyncAfter(deadline: .now() + .seconds(Int(Keyring.hourMs / 1000))) { [weak self] in
            guard let self, !self.stopped else { return }
            self.weeklyCheck(); self.compact(); self.hourly()
            DispatchQueue.main.async { self.quietPass() } // clocks that ended go, and what iOS holds is checked again
        }
    }

    /// A new key when the current one is a week old (spec "Keys"), after this phone's random delay so that the group's
    /// phones don't all do it at once; the first to do it settles it for the rest. Well past that delay this phone has
    /// missed the group's new keys, or the whole group was quiet: its new key is then also how it asks to come back.
    private func rekeyIfDue(_ k: Keys) {
        // Counted from when this phone got the key, if that was after its date: a member's key dated long ago isn't due at once.
        guard let cur = k.ring.currentOrNil, !k.isPublic, !k.removed, Keyring.weeklyDue(made: cur.since, now: Self.clock(), delay: k.weeklyDelay) else { return }
        let away = Keyring.overdue(made: cur.since, now: Self.clock())
        if makeRekey(k, add: [], answers: nil) && away { setRejoining(k, Self.nowMs()) }
    }

    /// The time, for deciding when a key is due; tests can move it forward.
    private static func clock() -> Int64 {
        #if DEBUG
        return nowMs() + ahead
        #else
        return nowMs()
        #endif
    }
    #if DEBUG
    /// Moves the clock that decides when a key is due: launch argument `ahead 8` (days).
    static var ahead: Int64 = 0
    /// Moves the clock that check-in clocks are judged by: launch argument `quietAhead 35` (minutes).
    static var quietAhead: Int64 = 0
    #endif

    private func setRejoining(_ k: Keys, _ since: Int64) {
        guard k.rejoiningSince != since else { return }
        k.rejoiningSince = since
        UserDefaults.standard.set(Double(since), forKey: "rejoining.\(k.id)")
        DispatchQueue.main.async { self.refresh() }
    }

    /// The rekey really is from who it says: its key matches the sender's id, and it signed the packet.
    private func signed(_ o: GroupCrypto.Opened, _ r: Rekey) -> Bool {
        guard MemberKey.id(r.maker) == o.message.sender, let c = MemberKey.Checker(r.maker) else { return false }
        return c.verify(digest: o.digest, signature: o.signature)
    }

    /// A member's phone missed our keys: a new epoch, sealed to everyone, brings it back (spec "Away for a while"). Not if
    /// another member answered first, or this phone made a key in the last hour (a flood of these must not cost more than that).
    /// `at`: this phone's current epoch when that member's key came. Another one since then is the answer, whatever date
    /// the key carried: its maker writes the date, and one a few minutes ahead would have every phone answer.
    private func answerBehind(_ k: Keys, made: Int64, at: Keyring.Epoch?) {
        guard let cur = k.ring.currentOrNil, !k.removed, !leavingNow(k), cur === at, cur.made < made, !k.ring.tooSoon(k.me, made: Self.clock()) else { return }
        makeRekey(k, add: [], answers: nil)
    }

    /// A new key listed this phone but its part didn't open (`Keyring.Taken.leftOut`): the others may have moved on to it,
    /// and can't tell. Like a phone that was away, this one makes a key of its own, sealed to everyone, and shows that it is
    /// rejoining. That key has the same number as the one it couldn't open if that was the next one, so it can lose to it:
    /// an hour later this phone makes one more, numbered above both. Not once a key that settles it got here: a
    /// higher-numbered one, or one of the same number that comes before the one it couldn't open whatever the hashes.
    private func answerLeftOut(_ k: Keys) {
        guard let t = k.leftOut, let cur = k.ring.currentOrNil else { return }
        if k.removed || cur.number > t.number || (cur.number == t.number && (cur.code != nil || cur.rank < t.rank)) { k.leftOut = nil; return }
        if k.ring.tooSoon(k.me, made: Self.clock()) { return } // the hourly check tries again
        guard makeRekey(k, add: [], answers: nil) else { return }
        setRejoining(k, Self.nowMs())
        k.leftOut = t.tries == 0 ? (t.number, t.rank, 1) : nil
    }
    /// The admins an invite to `code`'s group names (spec "Invites"), first listed first, or nil until its keys are loaded.
    func invitePins(_ code: String) -> String? {
        guard let k = held(code) else { return nil }
        let pubs = (k.ring.currentOrNil?.admins ?? []).compactMap { $0 == k.me ? k.pub : k.checker($0)?.pub }
        return pubs.prefix(Invite.maxPins).map(Invite.pin).joined()
    }

    func isAdmin(_ code: String?, _ id: String) -> Bool { held(code).map { $0.ring.currentOrNil?.admins.contains(memberId($0, id)) ?? false } ?? false }
    func inGroup(_ code: String?, _ id: String) -> Bool { held(code).map { $0.members.contains(memberId($0, id)) } ?? false }

    /// Gives `id` the admin role, with a new key. `done` says on the main thread if it worked.
    func makeAdmin(_ code: String?, _ id: String, done: @escaping (Bool) -> Void) {
        adminChange(code, id, done) { k, m, cur in !cur.admins.contains(m) && self.makeRekey(k, add: [], answers: nil, admins: cur.admins + [m]) }
    }

    /// Removes `id`: a new key and a new code for everyone else (spec "Removing and the code").
    func remove(_ code: String?, _ id: String, done: @escaping (Bool) -> Void) {
        adminChange(code, id, done) { k, m, _ in m != k.me && self.makeRekey(k, add: [], answers: nil, remove: m) }
    }

    /// Before the group's only admin leaves (spec "Leaving the group", LW2): one admin new key that adds `ids` to the
    /// admins (`makeAdmin`'s path), so that someone who stays can remove this phone. `done`, on the main thread: true
    /// once it is made; false if it can't be (a member's encryption keys haven't reached this phone).
    func handOver(_ code: String?, to ids: [String], done: @escaping (Bool) -> Void) {
        work.async {
            var ok = false
            if let k = self.held(code), let cur = k.ring.currentOrNil, !k.removed, cur.admins.contains(k.me) {
                let add = ids.map { self.memberId(k, $0) }.filter { cur.members.contains($0) && !cur.admins.contains($0) }
                ok = !add.isEmpty && self.makeRekey(k, add: [], answers: nil, admins: cur.admins + add)
            }
            DispatchQueue.main.async { done(ok); self.refresh() }
        }
    }

    private func adminChange(_ code: String?, _ id: String, _ done: @escaping (Bool) -> Void, _ act: @escaping (Keys, String, Keyring.Epoch) -> Bool) {
        work.async {
            var ok = false
            if let k = self.held(code), let cur = k.ring.currentOrNil, !k.removed, cur.admins.contains(k.me) {
                let m = self.memberId(k, id)
                ok = cur.members.contains(m) && act(k, m, cur)
            }
            DispatchQueue.main.async { done(ok); self.refresh() }
        }
    }

    /// What happened in `code`'s group, by name and when: "Marco joined", "Anna removed Marco", "Anna made Ben an admin".
    func groupLines(_ code: String?) -> [GroupEvent] {
        guard let k = held(code), !k.isPublic else { return [] }
        // Per number, the epoch that counts: never a late key kept only to read with that doesn't speak for the group
        // (a demoted admin's, or one that lost), whose list its maker wrote (`Keyring.counted`).
        let eps = k.ring.counted
        let timers = k.ring.timers, leaving = k.leaving
        func name(_ id: String?) -> String { let n = id.flatMap { i in k.lock.withLock { k.known[i]?.name } } ?? ""; return n.isEmpty ? "Someone" : n }
        func subject(_ id: String?) -> String { id == k.me ? "You" : name(id) }
        func object(_ id: String?) -> String { id == k.me ? "you" : name(id) }
        var out: [GroupEvent] = []
        // Someone who left with a note and is still listed (spec "Leaving the group"): one line at the note's time. An
        // admin's phone removes them in a moment; anyone else's says it waits for one.
        let admin = !k.removed && (k.ring.currentOrNil?.admins.contains(k.me) ?? false)
        for r in leaving.records.values { out.append(GroupEvent(ts: r.ts, text: LeaveWords.line(name(r.member), waiting: !admin))) }
        for (a, b) in zip(eps, eps.dropFirst()) {
            for id in b.roster where !a.roster.contains(id) && id != k.me { out.append(GroupEvent(ts: b.made, text: "\(name(id)) joined")) }
            for id in b.admins where !a.admins.contains(id) { out.append(GroupEvent(ts: b.made, text: "\(subject(b.by)) made \(object(id)) an admin")) }
            // A removal that followed the member's own note reads as their leaving.
            if let id = b.removes { out.append(GroupEvent(ts: b.made, text: leaving.removals[b.id]?.member == id ? LeaveWords.line(name(id), waiting: false) : "\(subject(b.by)) removed \(object(id))")) }
            // The timer changed with this key (spec "Disappearing messages"). Who did it is said only where this phone saw it.
            if let now = timers[b.id]?.seconds, let was = timers[a.id], was.seconds != now, was.seconds != nil || now > 0 {
                out.append(GroupEvent(ts: b.made, text: GroupTimer.changeLine(now, who: timers[b.id]?.by.map { subject($0) })))
            }
        }
        return out.enumerated().sorted { ($0.element.ts, $0.offset) < ($1.element.ts, $1.offset) }.map(\.element) // by time, the epochs' lines in their order
    }

    // MARK: - The group's timer (spec/FORMAT.md, "Disappearing messages")

    /// The timer of `code`'s group: seconds, 0 for off, nil while this phone doesn't know it. `by`: who set it, as the
    /// screen says it ("Anna", "you"), if this phone saw the change.
    func timer(_ code: String?) -> (seconds: Int?, by: String?) {
        guard let k = held(code), !k.isPublic, k.joined else { return (nil, nil) }
        let t = k.ring.timer
        let by = t.by.map { id -> String in id == k.me ? "you" : { let n = k.lock.withLock { k.known[id]?.name } ?? ""; return n.isEmpty ? "an admin" : n }() }
        return (t.seconds, by)
    }

    /// True if alerts about `code`'s group carry no message text: its messages disappear after a day or less.
    func alertsWithoutText(_ code: String?) -> Bool { timer(code).seconds.map { $0 > 0 && $0 <= GroupTimer.briefMax } ?? false }

    /// The clean-up, now, if a group here has a timer: the app came to the front. Any thread.
    func cleanUpIfTimed() {
        let timed = keyLock.withLock { Array(keyCache.values) }.contains { !$0.isPublic && ($0.watch.seconds ?? 0) > 0 }
        if timed { cleanUpNow() }
    }

    /// True if this phone can change the timer of `code`'s group now: it is an admin there and wasn't removed.
    func canSetTimer(_ code: String?) -> Bool {
        guard let k = held(code), !k.isPublic, !k.removed, let cur = k.ring.currentOrNil else { return false }
        return cur.admins.contains(k.me)
    }

    /// An admin sets the group's timer: a new key that carries it, then one message that says so for apps that don't
    /// follow the timer. `done`, on the main thread: true if the key was made; false if it couldn't be (a member's
    /// keys haven't reached this phone, or this phone may not). Choosing the value the group has changes nothing.
    func setTimer(_ code: String?, seconds: Int, done: @escaping (Bool) -> Void) {
        work.async {
            var ok = false
            if self.canSetTimer(code), let k = self.held(code) {
                if k.ring.timer.seconds == seconds { ok = true }
                else if self.makeRekey(k, add: [], answers: nil, timer: seconds) {
                    ok = true
                    DispatchQueue.main.async { self.sendTimerNotice(k, seconds) }
                }
            }
            DispatchQueue.main.async { done(ok); self.refresh() }
        }
    }

    /// The notice after a change: an ordinary group message whose text says what changed, for apps that don't follow
    /// the timer, with the timer in its 5th field. Apps that do follow it show nothing of it. Kept off LoRa. Main thread.
    private func sendTimerNotice(_ k: Keys, _ seconds: Int) {
        guard !stopped, k.joined, !k.removed else { return }
        let cr = k.group, id = Packet.newId(mailbox: cr.mailbox), ts = Self.nowMs()
        guard let sealed = sealOne(k, cr, isPublic: false, id: id, ts: ts, text: GroupTimer.notice(seconds), photo: nil, fifth: GroupTimer.field(seconds)) else { return }
        let p = Packet(id: id, ts: cr.hideTime(ts, sealed.cipher), ttl: Self.maxTtl, cipher: sealed.cipher)
        idsLock.withLock { _ = myReceipts.insert(id); _ = receiptIds.add(MeshRouter.key(p)) } // written here; no "Sent", no "Delivered", not on the radio
        store.append(p)
        indexed(p, .group)
        cover(id, p, k, own: true, arrived: false, opens: !sealed.keyed)
        router.publish(p)
    }

    /// The keyring of `k` changed: if the group's timer did, its ten minutes start now, and the next clean-up is set anew.
    private func timerMayHaveChanged(_ k: Keys) {
        if k.observeTimer(now: Self.clock()) {
            #if DEBUG
            DebugLog.write("timer: \(k.ring.timer.seconds.map(String.init) ?? "not known")")
            #endif
            DispatchQueue.main.async { self.refresh() }
        }
        work.async { self.scheduleDue() }
    }

    /// Sets the clean-up to run when the next message of a group with a timer is due, or when a new value has settled.
    /// On `work`. One is pending at a time; it sets the next when it has run.
    private func scheduleDue() {
        guard !stopped else { return }
        let now = Self.clock()
        var next: Int64?
        for k in keyLock.withLock({ Array(keyCache.values) }) where !k.isPublic {
            let w = k.watch
            guard let seconds = w.seconds, seconds > 0 else { continue }
            let held = storeLock.withLock { covered.filter { $0.value.group == k.id }.map { KeepsLess.Held(name: $0.key, since: book.since($0.value.key)) } }
            guard let due = Disappear.next(held, timerMs: Int64(seconds) * 1_000) else { continue }
            let at = Swift.max(due, w.settledAt)
            if next == nil || at < next! { next = at }
        }
        guard let next else { storeLock.withLock { dueAt = nil }; return }
        let fresh = storeLock.withLock { () -> Bool in if let d = dueAt, d <= next, d > now { return false }; dueAt = next; return true }
        guard fresh else { return }
        work.asyncAfter(deadline: .now() + .milliseconds(Int(Swift.min(Swift.max(next - now, 0) + 1_000, 3_600_000)))) { [weak self] in
            guard let self, !self.stopped, self.storeLock.withLock({ self.dueAt == next }) else { return }
            self.storeLock.withLock { self.dueAt = nil }
            self.compact()
        }
    }

    /// A new epoch from a rekey (spec "New key"): signed by its maker, allowed by `Keyring.admit`, with a part this
    /// phone can open. A removal also moves the group to its new code. True if it was new.
    private func acceptRekey(_ k: Keys, _ o: GroupCrypto.Opened, _ r: Rekey, _ packetId: String, _ door: Door) -> Bool {
        let maker = o.message.sender
        guard signed(o, r) else { return false }
        let pins = GroupSecrets.shared.pins(k.code)
        let pinned = Invite.allows(pins, maker: r.maker) // only read while this phone has no epoch
        #if DEBUG
        if !pinned && !k.joined { DebugLog.write("new key from someone this invite doesn't name: ignored") }
        #endif
        // The group may have moved on while this phone was away. Only on an admin's word: anyone who has the code can sign a removal.
        if r.removal && k.rejoining && maker != k.me && door === k.door && k.ring.movedOn(r, maker: maker) { k.closed = true }
        defer { if k.ring.partsToSave() { saveParts(k) } } // a part opened for an epoch this phone holds already: not opened again either
        // The 5th field of a new key is the group's timer (spec "Disappearing messages"). It is read only from a key the
        // keyring takes, and whether it counts is the keyring's to say (`Keyring.retime`).
        switch k.ring.take(r, maker: maker, me: k.me, door: door.code, oldDoor: door !== k.door, removedAt: k.removedAt, pinned: pinned, removedBy: k.removedBy, named: Invite.names(pins, maker: r.maker), inUse: otherCodes(k), timer: GroupTimer.read(field: o.fifth), open: { k.encs.open($0, info: Rekey.info(door.id), aad: Rekey.aad(packetId, k.me)) }) {
        case .ignored:
            return false
        case .leftOut(let number, let rank):
            // The others may have taken this key and be writing with it; this phone can't. It asks its way back with a key of its own.
            #if DEBUG
            DebugLog.write("epoch \(number) lists this phone, but its part doesn't open")
            #endif
            if (k.leftOut?.number ?? 0) < number { k.leftOut = (number, rank, 0) } // read again from the store: the count stays
            work.asyncAfter(deadline: .now() + .milliseconds(Int.random(in: 0..<3_000))) { self.answerLeftOut(k) }
            return false
        case .closed:
            k.closed = true
            UserDefaults.standard.set(true, forKey: "closed.\(k.id)") // saved: the keyring takes no key through this code from now on
            DispatchQueue.main.async { self.refresh() }
            return false
        case .removed(let number, let rank, let by):
            k.removedAt = (number, rank); k.removedBy = by
            UserDefaults.standard.set("\(number):\(rank):\(by)", forKey: "removedAt.\(k.id)")
            #if DEBUG
            DebugLog.write("removed from a group")
            #endif
            DispatchQueue.main.async { self.refresh() }
            return false
        case .added(let e, let moveTo, let back):
            if back { k.removedAt = nil; k.removedBy = nil; UserDefaults.standard.removeObject(forKey: "removedAt.\(k.id)") }
            if moveTo != nil { moveDoor(k) }
            saveRing(k)
            timerMayHaveChanged(k)
            if k.ring.current === e && maker != k.me { setRejoining(k, 0) }
            DispatchQueue.main.async { if let held = self.waitingToJoin.removeValue(forKey: k.id) { for w in held { self.send(w.text, photo: w.photo, group: k.code, voice: w.voice) } } }
            #if DEBUG
            DebugLog.write("epoch \(r.epoch): \(r.members.count) member(s)\(r.removal ? ", after a removal" : "")")
            #endif
            return true
        }
    }

    /// The codes of the other groups on this phone, saved or held: a removal can't move `k`'s group onto one of them.
    private func otherCodes(_ k: Keys) -> [String] {
        let held = keyLock.withLock { keyCache.filter { $0.value !== k }.map(\.key) }
        return (SavedGroups.all().map(\.code) + [GroupSecrets.shared.code] + held).filter { !$0.isEmpty && $0 != k.code }
    }

    /// Two admins removed different people at once: only one removal won, so the other person is still in the current
    /// epoch. An admin's phone finishes it, after 0-3 s unless another admin did (spec "Two new keys at the same time").
    private func finishRemovals(_ k: Keys) {
        guard !k.removed, !leavingNow(k), k.ring.currentOrNil?.admins.contains(k.me) == true else { return }
        for m in k.ring.stillToRemove() where m != k.me { if !makeRekey(k, add: [], answers: nil, remove: m) { return } }
    }

    /// The group this phone is leaving now, during its note's wait (`Wipe.leaveTelling`): its phone makes no new key
    /// there meanwhile (no removal of someone else who left, no finished removal, no answer to a phone that is behind),
    /// since it would make epochs and a new code it then forgets. Main thread sets it.
    var leavingCode: String? { get { stopLock.withLock { leavingCodeHeld } } set { stopLock.withLock { leavingCodeHeld = newValue } } }
    private func leavingNow(_ k: Keys) -> Bool { leavingCode.map { $0 == k.code } ?? false }

    // MARK: - Someone else left (spec/FORMAT.md, "Leaving the group")

    /// A leave note whose signature checked, or can't be checked yet (`checked` nil: it waits, and comes back here when
    /// it checks). Only a note that passes rule L1 (`LeaveNote.verdict`) counts: then this phone keeps a record (L2), from
    /// which nothing the member dates later is shown (L3), and an admin's phone removes them after 0-3 s (L4). The note
    /// itself is never a bubble, an alert or a receipt. Any thread.
    private func tookNote(_ k: Keys, _ o: GroupCrypto.Opened, checked: Bool?) {
        guard !stopped, !k.isPublic, o.message.notice == .leave else { return }
        let sender = o.message.sender.hasPrefix("web-") ? String(o.message.sender.dropFirst(4)) : o.message.sender
        let verdict = LeaveNote.verdict(sender: sender, me: k.me, checked: checked, opener: o.opener, ring: k.ring)
        #if DEBUG
        DebugLog.write("leave note \(o.message.id): \(verdict)")
        #endif
        guard verdict == .valid, let epoch = o.opener?.number else { return }
        guard k.changeLeaving({ $0.take(member: sender, ts: o.message.ts, epoch: epoch, now: Self.nowMs()) }) else { return } // later notes change nothing
        // Their check-in clock stays: it alerts at its deadline as usual (spec "If I go quiet", rule Q9).
        DispatchQueue.main.async { self.refresh() } // the line, and what they wrote after it goes
        work.asyncAfter(deadline: .now() + .milliseconds(Int.random(in: 0..<LeaveNote.maxDelayMs))) { _ = self.removeLeaver(k, sender) }
    }

    /// Rule L4: on an admin's phone, the normal removal of a member who left, unless they are out already or a removal of
    /// them is here (another admin's, or a rival one, which "Two admins at once" settles). False if it couldn't be made
    /// now: the hourly check tries again (`finishLeaves`) while the record lasts. The leaving phone never makes this
    /// key: a removal made by the person who goes would give them the new secret and the new code. On `work`.
    private func removeLeaver(_ k: Keys, _ m: String) -> Bool {
        guard !stopped, !leavingNow(k), m != k.me, k.leaving.record(m) != nil, let cur = k.ring.currentOrNil else { return true }
        let rest = cur.members.filter { $0 != m && $0 != k.me }
        let complete = k.lock.withLock { rest.allSatisfy { k.known[$0]?.enc != nil && k.known[$0]?.pq != nil } }
        switch LeaveNote.act(admin: !k.removed && cur.admins.contains(k.me), inCurrent: cur.members.contains(m), removalHeld: k.ring.all.contains { $0.removes == m }, keysComplete: complete) {
        case .removeNow:
            let ok = makeRekey(k, add: [], answers: nil, remove: m)
            #if DEBUG
            DebugLog.write("leave: removing a member who left: \(ok ? "new key made" : "couldn't, tries again hourly")")
            #endif
            return ok
        case .retryHourly:
            #if DEBUG
            DebugLog.write("leave: a member's encryption key is missing, tries again hourly")
            #endif
            return false
        case .nothing: return true
        }
    }

    /// Beside `finishRemovals`, every hour and when the saved groups are read: records end (`sweepLeaving`), and an
    /// admin's phone removes whoever left and is still listed.
    private func finishLeaves(_ k: Keys) {
        guard !k.isPublic else { return }
        sweepLeaving(k)
        for m in k.leaving.records.keys.sorted() where !removeLeaver(k, m) { return }
    }

    /// Ends the records of members no longer in the current epoch, keeping for the chat's line the removal that took
    /// them out, while that epoch is held; and records 7 days old (`LeaveBook.sweep`). Any thread.
    private func sweepLeaving(_ k: Keys) {
        sweepQuiet(k) // a removed member's check-in clock goes too, unless the removal followed their leave note (rule Q9)
        guard !k.isPublic, !k.leaving.isEmpty, let cur = k.ring.currentOrNil else { return }
        let all = k.ring.all
        if k.changeLeaving({ b in b.sweep(inCurrent: { cur.members.contains($0) }, removal: { m in all.first { $0.removes == m && k.ring.won($0) }?.id },
                                         held: Set(all.map(\.id)), now: Self.nowMs()) }) {
            DispatchQueue.main.async { self.refresh() }
        }
    }

    /// The group follows the code of its newest removal (`Keyring.lastMove`): same group id, member keys and messages, a
    /// new door and a new invite. Taken from the keyring, so a removal made here and one arriving at the same moment end
    /// up on the same code.
    private func moveDoor(_ k: Keys) {
        k.moveLock.lock(); defer { k.moveLock.unlock() }
        guard let code = k.ring.lastMove?.code else { return }
        let old = k.door
        guard old.code != code else { return }
        let next = Door(code)
        Vault.put("origin.\(next.id)", Data(k.id.utf8))
        k.setDoors(next, old: [old] + k.oldDoors.filter { $0.code != code })
        k.closed = false // that was about the code it left
        keyLock.withLock { keyCache.removeValue(forKey: old.code); keyCache[code] = k }
        Vault.put("door.\(k.id)", Data(code.utf8)) // where the group is now: its old codes lead here (`keys`)
        SavedGroups.replaceCode(old.code, code)
        GroupTag.moved(from: old.code, to: code) // alerts already showing still answer this group
        let d = UserDefaults.standard
        d.removeObject(forKey: "introAt.\(k.id)") // introduce ourselves through the new door
        #if DEBUG
        DebugLog.write("the group moved to a new code")
        #endif
        DispatchQueue.main.async {
            if GroupSecrets.shared.current == old.code { GroupSecrets.shared.setCode(code) } // on the main thread: the screens watch it
            if self.activeCode == old.code { self.activeCode = code }
            Unread.shared.rename(old.code, code)
            for i in self.raw.indices where self.raw[i].group == old.code { self.raw[i].group = code }
            self.refresh()
        }
    }

    private func declined(_ k: Keys) -> Set<String> { Set(UserDefaults.standard.stringArray(forKey: "declined.\(k.id)") ?? []) }

    /// People asking to join `code`'s group, for this phone to answer if it is an admin: they introduced themselves through
    /// the door in the last week and aren't in the group, weren't removed and weren't turned down.
    func joinRequests(_ code: String?) -> [JoinRequest] {
        guard let k = held(code), let cur = k.ring.currentOrNil, !k.removed, cur.admins.contains(k.me) else { return [] }
        let gone = Set(k.ring.all.compactMap(\.removes)), ids = asking(k).filter { !gone.contains($0) }
        return k.lock.withLock {
            ids.compactMap { id in k.known[id].map { JoinRequest(id: id, name: $0.name.isEmpty ? "Someone" : $0.name, pub: $0.checker.pub) } }
        }.sorted { $0.id < $1.id }
    }

    /// Who is asking to join `k`'s group (`Joining.asking`): they introduced themselves through the door in the last week,
    /// aren't in the group and weren't turned down. At most `Joining.maxAsking`: anyone with the code can make up keys.
    private func asking(_ k: Keys) -> [String] {
        let at = k.lock.withLock { k.known.filter { $0.value.pq != nil }.mapValues(\.encAt) }
        return Joining.asking(at, members: Set(k.members), declined: declined(k), me: k.me, now: Self.nowMs())
    }

    /// The class of a packet `k`'s door opened (`StoreCap.door`): the group's own only if someone in the group wrote it,
    /// or this phone. `signed`: the packet names `by` and their signature checks. The door opens with the code alone, so
    /// anything else gets the small room of people who aren't in (`StoreCap.Kind.door`) and can't push a message out.
    private func doorKind(_ k: Keys, _ by: String, signed: Bool) -> StoreCap.Kind {
        StoreCap.door(mine: signed && by == k.me, member: signed && k.ring.inGroup(by))
    }

    /// An admin lets `id` in: a new epoch for everyone, them included (spec "Joining"). `done` says on the main thread if it worked.
    func approveJoin(_ code: String?, _ id: String, done: @escaping (Bool) -> Void) {
        work.async {
            var ok = false
            if let k = self.held(code), let cur = k.ring.currentOrNil, !k.removed, cur.admins.contains(k.me), !cur.members.contains(id) {
                ok = self.makeRekey(k, add: [id], answers: id)
            }
            DispatchQueue.main.async { done(ok); self.refresh() }
        }
    }

    /// An admin turns `id` down. They can ask again only with a new key, that is by reinstalling.
    func declineJoin(_ code: String?, _ id: String) {
        guard !stopped, let k = held(code) else { return }
        UserDefaults.standard.set(Array(declined(k).union([id])), forKey: "declined.\(k.id)")
        DispatchQueue.main.async { self.refresh() }
    }

    /// Makes and sends a new epoch for the current members plus `add`, sealed to each member's encryption key. `admins`:
    /// a new admin list. `remove`: who leaves; everyone else also gets a new code. False if a member's encryption key
    /// hasn't reached this phone yet: leaving them out would quietly remove them.
    /// `timer`: this phone's user chose a new timer for disappearing messages (seconds, 0 for off). Every new key
    /// carries the timer its maker knows, this choice or the current one, so that phones that join or come back learn
    /// it; one whose maker doesn't know it carries none. Only an admin's counts (`Keyring.retime`).
    @discardableResult private func makeRekey(_ k: Keys, add: [String], answers: String?, admins: [String]? = nil, remove: String? = nil, timer: Int? = nil) -> Bool {
        guard !stopped, let cur = k.ring.currentOrNil else { return false }
        let timed = timer ?? k.ring.timer.seconds
        var members = cur.members.filter { $0 != remove }
        for m in [k.me] + add where !members.contains(m) { members.append(m) }
        guard members.count <= Keyring.maxMembers else {
            #if DEBUG
            DebugLog.write("rekey: the group is full")
            #endif
            return false
        }
        let door = k.door, code = remove == nil ? nil : Invite.newCode(), now = Self.nowMs()
        var b = [UInt8](repeating: 0, count: 32); _ = SecRandomCopyBytes(kSecRandomDefault, 32, &b)
        let secret = Data(b), plain = Rekey.box(secret, code: code)
        // An admin's rekey also keeps every admin added by a rekey of the same number that didn't win.
        var list: [String] = []
        for a in (admins ?? cur.admins) + (cur.admins.contains(k.me) ? k.ring.rivalAdmins() : []) where members.contains(a) && !list.contains(a) { list.append(a) }
        let who = DispatchQueue.main.sync { name }
        // A big group's key goes in several packets, each with the whole member list and some members' parts.
        var packets: [Packet] = [], made: Rekey?
        for some in Rekey.split(members) {
            let pid = Packet.newId(mailbox: door.rekeys.mailbox)
            var parts: [(id: String, box: Data)] = []
            for m in some {
                let known = m == k.me ? nil : k.lock.withLock { k.known[m] }
                let enc = m == k.me ? k.encPub : known?.enc, pq = m == k.me ? k.encs.currentPq : known?.pq
                guard let enc, let pq, let pk = try? P256.KeyAgreement.PublicKey(x963Representation: enc),
                      let box = try? Hybrid.seal(plain, to: pk, pq: pq, info: Rekey.info(door.id), aad: Rekey.aad(pid, m)) else {
                    #if DEBUG
                    DebugLog.write("rekey: no encryption key for a member yet")
                    #endif
                    return false
                }
                parts.append((m, box))
            }
            let one = Rekey(epoch: cur.number + 1, made: now, maker: k.pub, admins: list, answers: remove ?? answers, parts: parts, removal: remove != nil, members: members)
            guard let c = try? door.rekeys.seal(id: pid, ts: now, sender: k.me, name: who, text: "", extra: one.encode(), fifth: timed.map(GroupTimer.field), pad: .full, sign: { try k.sign($0) }) else { return false } // always the largest size: its length would count the members
            packets.append(Packet(id: pid, ts: door.rekeys.hideTime(now, c), ttl: Self.maxTtl, cipher: c))
            made = one
        }
        guard let rk = made else { return false }
        k.ring.add(.fresh(number: rk.epoch, made: now, secret: secret, members: members, admins: list, code: code, via: remove == nil ? nil : door.code, removes: remove, by: k.me,
                         rank: k.ring.standing(rk, maker: k.me),
                         timerRaw: timed, timerSetHere: timer != nil, timer: k.ring.timer.seconds, timerCounts: timed != nil && k.ring.setsTimer(rk, maker: k.me)))
        if code != nil { moveDoor(k) }
        saveRing(k)
        timerMayHaveChanged(k)
        for p in packets {
            idsLock.withLock { _ = myReceipts.insert(p.id) }
            store.append(p)
            indexed(p, .group)
            router.publish(p)
        }
        if code != nil { introIfDue(k) }
        sweepLeaving(k) // someone who left may be out now
        nostr.resubscribe()
        #if DEBUG
        DebugLog.write("epoch \(rk.epoch) made:\(members.count) member(s) in \(packets.count) packet(s)" + (answers == nil ? "" : ", answering a join") + (remove == nil ? "" : ", removing a member"))
        #endif
        DispatchQueue.main.async { self.refresh() }
        return true
    }

    /// true: signed by that member; false: not (forged or broken), or its sender isn't in the group or was removed; nil:
    /// the member's public key isn't known yet. `had`: this phone accepted the packet before, so it is read from its own
    /// copy or was waiting for its sender's key. Only then does a removed member's date count (`Keyring.shutOut`).
    private func check(_ k: Keys, _ o: GroupCrypto.Opened, had: Bool = false) -> Bool? {
        let id = o.message.sender.hasPrefix("web-") ? String(o.message.sender.dropFirst(4)) : o.message.sender
        if k.ring.shutOut(id, at: o.message.ts, had: had) { return false } // not in the group; or removed, and seen only now or dated after the removal
        guard let c = k.checker(id) else { return nil }
        return c.verify(digest: o.digest, signature: o.signature)
    }

    /// Messages and receipts of `id` in `k`'s group that waited for its key: shown as checked, or dropped. Main thread.
    private func resolve(_ k: Keys, _ id: String) {
        guard k.checker(id) != nil else { return } // its key wasn't kept (`Joining`): nothing can be checked
        let waited = unchecked.take(channel: ObjectIdentifier(k), sender: id)
        guard !waited.isEmpty else { return }
        for e in waited {
            let w = e.item
            let ok = check(k, w.o, had: true) == true
            if let r = w.receipt { if ok { deliveries.add(group: k.id, r); quietReceipt(w.k, r) }; continue }
            if w.o.message.notice == .leave { // a leave note is decided when it checks (rule L1)
                if ok { w.o.leaf?.forget(); saveMessageKeys(w.k); tookNote(w.k, w.o, checked: true) }
                continue
            }
            if let rx = w.reaction { // counted from now on; its key goes like a message's
                if ok { w.o.leaf?.forget(); saveMessageKeys(w.k); if let e = rx.entry { reactions.add(rx.name, e) } }
                continue
            }
            // Only a packet that is the one shown under its id waits (`KeyWait.message`), so this row is that packet's.
            guard let row = e.row, let i = raw.firstIndex(where: { $0.id == row }), !raw[i].verified else { continue }
            if ok { raw[i].verified = true; w.o.leaf?.forget(); saveMessageKeys(w.k); if w.o.leaf != nil { storeLock.withLock { covered[row]?.opens = false } }; quietSaw(w.k, w.o, checked: true) }
            else { rawIds.remove(raw[i].id); idsLock.withLock { shownAs[w.o.mark] = nil }; raw.remove(at: i) }
        }
        refresh()
    }

    /// Sends this phone's introduction to `k`'s group when the last one there is more than 3 days old (or there was
    /// none), so members' introductions are always among the packets of the last 7 days. Any thread.
    private func introIfDue(_ k: Keys, writing: Bool = false) {
        guard !k.removed, !stopped else { return }
        let d = UserDefaults.standard, key = "introAt.\(k.id)", now = Self.nowMs()
        // The Public channel: only when the user writes there, then while a post of theirs is still shown. A phone
        // whose user never posts on Everyone doesn't announce itself there.
        if k.isPublic && !writing {
            let last = DispatchQueue.main.sync { raw.filter { $0.isPublic && $0.sender == deviceId }.map(\.ts).max() }
            guard PublicIntro.wanted(writing: false, lastOwnPost: last, now: now) else { return }
        }
        let rotated = !k.isPublic && k.encs.rotateIfDue(now: Self.clock()) // a new encryption key has to be introduced at once
        guard rotated || now - Int64(d.double(forKey: key)) >= Self.introEvery else { return }
        let id = Packet.newId(mailbox: k.intros.mailbox), who = DispatchQueue.main.sync { name }
        let pq = k.isPublic ? nil : k.encs.currentPq // the Public channel has no new keys to seal
        // No name on the Public channel: nothing reads it there, and a post carries its sender's name itself.
        guard let c = try? k.intros.seal(id: id, ts: now, sender: k.me, name: k.isPublic ? "" : who, text: "", extra: pq.map { Data([3]) + k.pub + k.encPub + $0 } ?? Data([2]) + k.pub + k.encPub, sign: { try k.sign($0) })
        else { return }
        let p = Packet(id: id, ts: k.intros.hideTime(now, c), ttl: Self.maxTtl, cipher: c)
        idsLock.withLock { _ = myReceipts.insert(id) } // written here: goes up to the relays first, like receipts
        store.append(p)
        indexed(p, k.isPublic ? .isPublic : .group)
        d.set(Double(now), forKey: key)
        router.publish(p)
        #if DEBUG
        DebugLog.write("introduction sent to \(k.isPublic ? "Everyone" : "a group")")
        #endif
    }

    // People whose emoji the user compared (spec "Person emoji"): "<group tag>|<member id>|<name key>".
    private func tagOf(_ code: String?) -> String? { keyLock.withLock { keyCache[code ?? GroupCrypto.publicCode] }?.id }
    private var checkedList: [String] { UserDefaults.standard.stringArray(forKey: "checked") ?? [] }

    func isChecked(_ code: String?, _ id: String) -> Bool {
        guard let t = tagOf(code) else { return false }
        return checkedList.contains { $0.hasPrefix("\(t)|\(id)|") }
    }

    /// The user compared `id`'s emoji and they matched: this key is that person's from now on (an older one for the name goes).
    /// `how`: by emoji, or in person by scanning their person code (spec "Person code"); kept next to the mark.
    func markChecked(_ code: String?, _ id: String, _ name: String, how: Checked.How = .emoji) {
        guard !stopped, let t = tagOf(code) else { return }
        let d = UserDefaults.standard, list = Checked.marked(checkedList, group: t, id: id, name: name)
        d.set(list, forKey: "checked")
        d.set(Checked.markedHow(d.stringArray(forKey: Self.inPersonKey) ?? [], checked: list, group: t, id: id, how: how), forKey: Self.inPersonKey)
        objectWillChange.send()
    }

    /// True if `name` belongs to a checked person but `id` is another key: a reinstall, or someone using their name.
    func newKeyFor(_ code: String?, _ id: String, _ name: String) -> Bool {
        guard id != deviceId, let t = tagOf(code) else { return false }
        let checked = checkedList
        return !checked.isEmpty && Checked.newKey(checked, group: t, id: id, name: name)
    }

    /// The id of the group whose code is or was `code` (`Message.group`): it stays when the code changes, so blocks hang
    /// off it (`Blocks`). The door a group has just left still counts, for a message read a moment before the move.
    private func groupId(_ code: String?) -> String? {
        guard let code else { return nil }
        if let k = keyLock.withLock({ keyCache[code] }) { return k.id }
        let all = keyLock.withLock { Array(keyCache.values) }
        return all.first { k in k.oldDoors.contains { $0.code == code } }?.id
    }

    /// The channel a message's sender is blocked in (`Blocks`): Everyone, or its group's id.
    private func channel(of m: Message) -> String? { m.isPublic ? Blocks.everyone : groupId(m.group) }

    /// Blocks the sender of `m` in the channel `m` came in, and shows the messages again. False, and nothing saved, if
    /// that sender can't be blocked: `m` is this phone's own, or its signature wasn't checked (`Blocks.canBlock`). Main thread.
    @discardableResult func block(_ m: Message) -> Bool {
        guard !stopped, Blocks.canBlock(m, me: deviceId), let c = channel(of: m) else { return false }
        Moderation.block(Blocks.key(channel: c, sender: m.sender), name: m.name)
        refresh()
        return true
    }

    /// False for a message hidden on this phone: reported or hidden itself, or from someone blocked in its channel. Main thread.
    private func visible(_ m: Message) -> Bool {
        if Moderation.hidden.contains(m.id) { return false }
        let blocked = Moderation.blocked
        return blocked.isEmpty || !Blocks.hides(Set(blocked.keys), channel: channel(of: m), sender: m.sender)
    }

    /// This phone's public key in `code`'s group ("your emoji"), or nil if its keys aren't ready.
    func myPublicKey(_ code: String?) -> Data? { keyLock.withLock { keyCache[code ?? GroupCrypto.publicCode] }?.pub }

    /// The public key of member `id` in `code`'s group, if an introduction brought it.
    func publicKey(of id: String, in code: String?) -> Data? {
        guard let k = keyLock.withLock({ keyCache[code ?? GroupCrypto.publicCode] }) else { return nil }
        return k.checker(id.hasPrefix("web-") ? String(id.dropFirst(4)) : id)?.pub
    }

    /// Switches to another group (or just another name) without restarting the radios: every phone relays every
    /// group anyway, and saved groups' messages are already read. Only the name changed: nothing else happens. A new
    /// group's key is derived in the background. Call on the main thread.
    func rejoin(name: String, code: String) {
        self.name = name
        guard !stopped, code != activeCode else { return }
        flushAcks() // confirmations owed so far go out first
        activeCode = code
        refresh() // a saved group's messages show at once
        work.async { self.savedGroupsChanged(code) }
    }

    /// After blocking, unblocking or reporting: shows the messages again with the new rules. Main thread; no reading.
    func moderationChanged() { refresh() }

    // MARK: - Loading (background, on `work`)

    /// Passes stored packets on right away (no keys needed), then reads them with this group's and the Public key, then
    /// with the other saved groups' keys, then trims the store.
    private func startUp(_ code: String) {
        let now = Self.nowMs(), cut = now - Self.week
        var recent: [Packet] = [], seen = Set<String>()
        for s in [store, more] {
            s.forEach { p in
                guard p.ts >= cut, !MeshRouter.inFuture(p.ts, now: now) else { return }
                seen.insert(MeshRouter.key(p)); recent.append(p)
                if recent.count >= 2_000 { recent.sort { $0.ts > $1.ts }; recent.removeLast(recent.count - 500) }
            }
        }
        // The router keeps the newest 500. What the relays brought before the restart doesn't go back to them.
        router.restore(recent, seenIds: seen, from: { [fromRelays] key in fromRelays.contains(key) ? NostrLink.peer : "" })
        let a = keys(code), pub = keys(GroupCrypto.publicCode)
        cryptoLock.withLock { _active = a; _public = pub }
        load()
        nostr.resubscribe()
        savedGroupsChanged(code)
        hourly()
    }

    /// `code` is the group on screen and the saved list may have changed: derives the keys not seen before, reads the
    /// store again only if there are any, then trims the store.
    private func savedGroupsChanged(_ code: String) {
        guard !stopped else { return }
        let known = keyLock.withLock { Set(keyCache.keys) }
        let a = keys(code)
        if a.code != code { // an old code of a group saved here: that group is shown, not a second one
            let now = a.code
            DispatchQueue.main.async { SavedGroups.follow(code, now); if self.activeCode == code { self.activeCode = now }; self.refresh() }
            return savedGroupsChanged(now)
        }
        var codes: [String] = [], o: [Keys] = []
        for c in SavedGroups.all().map(\.code) where c != code && !codes.contains(c) {
            let k = keys(c)
            if k.code != c { SavedGroups.follow(c, k.code) } else { codes.append(c) } // an old code among the saved ones isn't kept: its group is saved too
            if k !== a && !o.contains(where: { $0 === k }) { o.append(k) }
        }
        cryptoLock.withLock { _active = a; _others = o }
        if !(codes + [code]).allSatisfy(known.contains) { load() }
        storeLock.withLock { allKeys = true }
        idsLock.withLock { early = nil } // every saved group's key has read the store: this phone knows its own packets now
        UserDefaults.standard.set(true, forKey: Self.timedKey) // and from now on every covered packet has its time noted when it comes
        for k in [a] + o + [keySet.pub].compactMap({ $0 }) { introIfDue(k) }
        for k in [a] + o { rekeyIfDue(k); finishLeaves(k) }
        compact()
        nostr.resubscribe() // the relays now send every saved group's mailbox
        DispatchQueue.main.async { self.quietPass() } // every saved group's clocks are read: alerts and reminders as they stand
    }

    /// Reads every stored packet with every key known so far: messages, receipts, my own ids and the store index.
    /// Packets dated more than 10 minutes ahead are removed. Publishes on the main thread.
    private func load() {
        guard !stopped else { return }
        let k = keySet, now = Self.nowMs()
        var msgs: [(Message, String)] = [], recs: [(group: String, receipt: Message)] = [], own = Set<String>(), ownR = Set<String>(), rIds = Set<String>() // msgs: with what was signed (`mark`)
        var sent: [(group: String, id: String)] = [] // this phone's own group messages, with their group's id (`Deliveries.sent`)
        var idx: [String: StoreCap.Entry] = [:], future = Set<String>()
        var opened: [(Message, Keys, GroupCrypto.Opened, Bool, Bool, Bool, String)] = [] // checked once every introduction is known; 6th: another packet had its id first; last: its store name
        var copies: [String: Data] = [:]
        history.forEach { copies[$0.id] = $0.cipher }
        // "Keeps less": what the rule removed and a neighbour brought back is read as a packet this phone can't open.
        let wasGone = storeLock.withLock { book }
        var goneCopies = Set<String>(), goneOpen = Set<String>(), keyOf: [String: String] = [:] // keyOf: name -> packet key, for what opens as a group message or receipt
        var cov: [String: (key: String, group: String, own: Bool, opens: Bool)] = [:]
        var dated: [String: (ts: Int64, receipt: Bool)] = [:] // name -> the covered packet's own date, and whether it is a receipt
        // `name`: what the packet goes by (`StoreCap.name`): its id, or its key if another packet had that id first.
        // `extra`: it comes from the file of those other packets.
        func read(_ stored: Packet, _ name: String, _ extra: Bool) {
            let p = MeshRouter.clamp(stored)
            if MeshRouter.inFuture(p.ts, now: now) { future.insert(name); return }
            if let kind = wasGone.kind(of: p) {
                // Stays in the store, for carrying, only while no key here opens it (`DayBook.storesAgain`): one that a key
                // still opens (the app ended while it was being removed, or it came back before the keys were loaded) goes.
                if DayBook.storesAgain(stillOpens: see(p, k) != nil) { idx[name] = StoreCap.Entry(id: name, ts: p.ts, size: Packet.headerSize + p.cipher.count, kind: kind) }
                else { goneOpen.insert(name) }
                if copies[Self.copyId(name)] != nil { goneCopies.insert(Self.copyId(name)) }
                return
            }
            // This phone's copy is kept under the packet's name. In "packets.bin" a later record under an id (written by
            // an older version: a readable packet after junk) may still have its copy under that id.
            let underId = extra ? nil : copies[p.id]
            let sealed = underId == nil ? name : p.id, copy = underId ?? copies[Self.copyId(name)]
            // Introductions are learned at once; messages and receipts are checked after the loop, when every
            // introduction in the store is known, whatever their order. This phone's own introductions and new keys
            // count as written here, like its receipts: after a restart too they go up on their group's relay links,
            // not with the packets it passes on (Nostr.scope).
            // What came through a group's door is the group's own only from someone in it (`doorKind`).
            let kind: StoreCap.Kind
            switch copy.flatMap({ fromHistory(sealed, $0, k) }) ?? see(p, k) {
            case .intro(let keys, let id, let c, let enc, let who, let ts, let pq)?:
                if id == keys.me { ownR.insert(p.id) }
                learn(keys, id, c, enc: enc, encAt: ts, pq: pq, name: who); kind = keys.isPublic ? .isPublic : doorKind(keys, id, signed: true)
            case .newKey(let keys, let o, let r, let pid, let d)?:
                let ok = signed(o, r)
                if o.message.sender == keys.me, ok { ownR.insert(p.id) }
                _ = acceptRekey(keys, o, r, pid, d); kind = doorKind(keys, o.message.sender, signed: ok)
            case .msg(let m0, let keys, let o, let copy)?:
                var m = m0.id == name ? m0 : m0.withId(name)
                m.mark = About.Target(o) // what a reply or a reaction to it names: the same whichever copy of the message this is
                if m.offRadio { rIds.insert(MeshRouter.key(p)) } // a reaction or the timer's notice stays off LoRa, like a receipt
                if !m.isPublic { keyOf[name] = MeshRouter.key(p) }
                opened.append((m, keys, o, false, copy, name != p.id, name)); kind = m.isPublic ? .isPublic : .group
            case .receipt(let r, let keys, let o)?: opened.append((r, keys, o, true, false, false, name)); rIds.insert(MeshRouter.key(p)); keyOf[name] = MeshRouter.key(p); kind = .group
            default: kind = .unreadable
            }
            idx[name] = StoreCap.Entry(id: name, ts: p.ts, size: Packet.headerSize + p.cipher.count, kind: kind)
        }
        store.forEachNamed { read($0, $1, false) }
        more.forEachNamed { read($0, $1, true) }
        var waiting: [Pending] = [], spent: [Keys] = []
        var reacted: [(name: String, entry: Reactions.Entry?, mark: String)] = [] // checked reactions: never rows (spec "Reactions")
        for (m, keys, o, receipt, copy, other, name) in opened {
            // What opened as a message or a receipt of a group is what "Keeps less" covers, shown or not; `false` below takes it out again.
            // `opens`: whether a key here still opens it once this reading is done: a receipt, or a message without a key of
            // its own. One with its own key loses it below when its signature checks; until then it opens too.
            let checked = check(keys, o, had: copy)
            if let key = keyOf[name] { cov[name] = (key, keys.id, m.sender == deviceId && !other, receipt || o.leaf == nil || checked != true); dated[name] = (m.ts, receipt) }
            if other, !receipt, m.sender == deviceId { continue } // another packet under an id that was taken, naming this phone as its sender: never a row of its own
            let silent = !receipt && m.isSilent
            switch checked {
            case true?:
                if receipt { recs.append((keys.id, m)); if m.sender == deviceId { ownR.insert(m.id) } }
                else if silent {
                    if m.notice == .leave { tookNote(keys, o, checked: true) } // read before its key went (it had waited for its sender's key): decided now
                    else { reacted.append((name, reaction(m, keys, name), o.mark)) }
                    if m.sender == deviceId { ownR.insert(StoreCap.packetId(name)) } // written here: up to the relays first, like a receipt; never "Sent" or "Delivered"
                    if !copy { remember(m.id, keys, o) }
                    if let l = o.leaf { l.forget(); if !spent.contains(where: { $0 === keys }) { spent.append(keys) } }
                }
                else {
                    msgs.append((m, o.mark))
                    if !m.isPublic { quietSaw(keys, o, checked: true) } // the clocks, rebuilt from what is stored: the newer wins (rule Q10)
                    if m.sender == deviceId { own.insert(m.id); if !m.isPublic { sent.append((keys.id, m.id)) } }
                    if !copy { remember(m.id, keys, o) }
                    if let l = o.leaf { l.forget(); if !spent.contains(where: { $0 === keys }) { spent.append(keys) } }
                }
            case nil:
                if receipt { waiting.append(Pending(k: keys, o: o, receipt: m)) }
                else if silent { waiting.append(Pending(k: keys, o: o, receipt: nil, reaction: (name, reaction(m, keys, name)))); if !copy { remember(m.id, keys, o) } }
                else { var u = m; u.verified = false; msgs.append((u, o.mark)); waiting.append(Pending(k: keys, o: o, receipt: nil, row: m.id)); if !copy { remember(m.id, keys, o) } }
            case false?:
                // A forged message or receipt is not the group's own, whatever it is stored under (`name`): like a packet nobody can open.
                if let e = idx[name] { idx[name] = StoreCap.Entry(id: e.id, ts: e.ts, size: e.size, kind: .unreadable) }
                cov[name] = nil
            }
        }
        spent.forEach(saveMessageKeys)
        if !future.isEmpty {
            store.remove(future); more.remove(future)
            history.remove(Set(future.map(Self.copyId)))
            #if DEBUG
            DebugLog.write("store: removed \(future.count) packets dated in the future")
            #endif
        }
        if !goneCopies.isEmpty { history.remove(goneCopies) }
        if !goneOpen.isEmpty {
            store.remove(goneOpen); more.remove(goneOpen)
            #if DEBUG
            DebugLog.write("store: took out \(goneOpen.count) gone packet(s) a key here still opens")
            #endif
        }
        // What was handed to a link before this phone knew which packets are its own (`early`) is settled once the rest
        // of this reading is in place, below.
        let handedEarly: [String: String?] = idsLock.withLock {
            mine.formUnion(own); myReceipts.formUnion(ownR); rIds.forEach { receiptIds.add($0) }
            let ours = (early ?? [:]).filter { mine.contains($0.key) || myReceipts.contains($0.key) }
            for id in ours.keys { early?.removeValue(forKey: id) }
            return ours
        }
        // "Keeps less": a covered packet with no time yet. If it was stored before times were kept (this version's first
        // reading of the store), it counts from its own date; later, its time wasn't saved when the app ended (it is saved
        // within 2 s), and its day starts now. A message written here has no time until it leaves, and stays; one that
        // left before has the time of its "Sent". A receipt written here counts from when it was written.
        let clock = Self.clock(), before = !UserDefaults.standard.bool(forKey: Self.timedKey)
        let leftBefore: [String: Int64?] = idsLock.withLock {
            var out: [String: Int64?] = [:]
            for (name, c) in cov where c.own && sentIds.contains(name) { out[name] = .some(sentOver[name].flatMap(Journey.Left.init(saved:))?.at) }
            return out
        }
        let noted = storeLock.withLock { () -> Bool in
            let added = index.filter { idx[$0.key] == nil } // stored while reading
            for (id, e) in added { idx[id] = e; if let c = covered[id] { cov[id] = c } }
            index = idx; indexBytes = idx.values.reduce(0) { $0 + $1.size }
            let door = idx.values.filter { $0.kind == .door }
            doorCount = door.count; doorBytes = door.reduce(0) { $0 + $1.size }
            var noted = false
            for (name, c) in cov where book.since(c.key) == nil {
                let at = KeepsLess.sinceUnnoted(own: c.own, receipt: dated[name]?.receipt == true, date: dated[name]?.ts ?? clock, handedOff: leftBefore[name],
                                                beforeTimes: before, now: clock)
                if let at { noted = book.note(c.key, at: at) || noted }
            }
            covered = cov
            return book.keepOnly(Set(cov.values.map(\.key))) || noted // a packet that is no longer stored isn't timed
        }
        if noted { saveBookSoon() }
        for (id, peer) in handedEarly { markHandedOff(id, over: peer.flatMap { Self.link($0) } ?? (peer == nil ? .internet : nil)) }
        // Who confirmed what, worked out here and not on the main thread. A receipt counts only for a message written
        // on this phone, in the group whose receipt key opened it; the receipts themselves aren't kept in memory.
        var found = Deliveries()
        for (group, id) in sent { found.sent(group: group, id) }
        for (group, r) in recs { found.add(group: group, r) }
        let confirmed = found
        DispatchQueue.main.async {
            var added = Set<String>()
            for (m, mark) in msgs {
                // Another packet already shows this very message: it is one row, whatever packets bring it.
                let free = self.idsLock.withLock { () -> Bool in if let row = self.shownAs[mark], row != m.id { return false }; return true }
                guard free else { continue }
                if self.rawIds.insert(m.id).inserted { self.raw.append(m); added.insert(m.id) }
                self.idsLock.withLock { self.shownAs[mark] = m.id }
            }
            self.raw.sort { $0.ts < $1.ts }
            self.deliveries.merge(confirmed)
            for r in reacted {
                self.idsLock.withLock { if self.shownAs[r.mark] == nil { self.shownAs[r.mark] = r.name } } // the same reaction sealed again isn't kept twice
                if let e = r.entry { self.reactions.add(r.name, e) }
            }
            // A message waits only if its row was made just now from the stored packet: a row that was there already
            // may show another packet with that id.
            for w in waiting {
                let channel = ObjectIdentifier(w.k), sender = w.o.message.sender
                if w.receipt != nil || w.reaction != nil { self.unchecked.receipt(w.o.key, channel: channel, sender: sender, w) }
                else { let row = w.row ?? w.o.message.id; self.unchecked.message(w.o.key, channel: channel, sender: sender, id: row, shown: added.contains(row), w) } // the row it is shown as (its store name), not the shared packet id
            }
            self.refresh()
        }
    }

    /// Keeps the store small, once every saved group's key has been tried: packets older than 7 days that no saved
    /// group can read go (phones stop passing them on after 7 days anyway), and so do those that came through a group's
    /// door from someone who isn't in it; Public packets beyond 2,000 go (no sender more than its share:
    /// StoreCap.everyone), and then the store cap (StoreCap: about 40 MB and 20,000 packets, unreadable packets first,
    /// and the door's own small room). Same rules as the Android app (MeshEngine.compact).
    private func compact() {
        guard !stopped else { return }
        let now = Self.nowMs()
        var expired: [String: (gone: [Keyring.Epoch], held: [Keyring.Epoch])] = [:] // group id -> the epochs deleted now, and those still held
        for k in keyLock.withLock({ Array(keyCache.values) }) {
            // A removed phone still deletes its replaced encryption keys on time (spec "Changing keys"); it only stops introducing itself.
            if k.removed && !k.isPublic { _ = k.encs.rotateIfDue(now: Self.clock()) }
            let gone = k.ring.expire(now: now)
            guard !gone.isEmpty else { continue } // keys replaced more than 7 days ago go (v0.6), from the Keychain too
            saveRing(k)
            k.setDoors(k.door, old: k.oldDoors.filter { d in k.ring.all.contains { $0.via == d.code } })
            expired[k.id] = (gone, k.ring.all)
        }
        let stale = copies(of: expired)
        // Nor is an alert kept past a week, whatever happens to its message (Android: a timeout from the message's date).
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { ns in
            let old = ns.filter { Date().timeIntervalSince($0.date) > 7 * 86_400 }.map(\.request.identifier)
            if !old.isEmpty { center.removeDeliveredNotifications(withIdentifiers: old) }
        }
        // Over the Everyone channel's cap, not simply the oldest go: one sender can't push everyone else's messages and
        // places out (StoreCap.everyone). Who sent what is read before the store lock is taken: the main thread may be
        // waiting for that lock.
        let many = storeLock.withLock { allKeys && index.values.filter { $0.kind == .isPublic }.count > Self.maxPublic }
        var shown: [String: (sender: String, verified: Bool)] = [:]
        if many { shown = DispatchQueue.main.sync { everyoneSenders() } }
        let written: Set<String> = many ? idsLock.withLock { myReceipts } : []
        // High-risk mode, "Keeps less" (`KeepsLess`): by the clock the weekly keys count with. Moved forward by days, it
        // makes messages leave early: accepted, the mode exists to keep less. Set back, it removes less.
        let less: Int64? = HighRiskSetting.isOn() ? Self.clock() : nil
        // The groups' timers (spec "Disappearing messages"): group id -> the time after which a message leaves this phone,
        // for the groups whose value has settled. The keyring is asked again first: a value that changed a moment ago
        // starts its ten minutes here at the latest, and deletes nothing in this pass.
        let clock = Self.clock()
        var timers: [String: Int64] = [:]
        for k in keyLock.withLock({ Array(keyCache.values) }) where !k.isPublic {
            k.observeTimer(now: clock)
            if let t = k.watch.deletes(now: clock) { timers[k.id] = t }
        }
        let (drop, faded, gone, ran): (Set<String>, Set<String>, Set<String>, Bool) = storeLock.withLock {
            compacting = false
            guard allKeys else { return ([], [], [], false) }
            let cut = now - Self.week
            var drop = Set(index.values.filter { ($0.kind == .unreadable || $0.kind == .door) && $0.ts < cut }.map(\.id))
            drop.formUnion(stale) // the messages of an epoch deleted now: their packets no longer open either
            // A group's messages and receipts go once they have been on this phone for a day, and onto the gone list:
            // brought back by a neighbour they are carried, never opened again. One written here that hasn't left stays.
            var gone = Set<String>() // what goes onto the gone list in this pass
            if let less {
                for name in KeepsLess.expired(covered.map { KeepsLess.Held(name: $0.key, since: book.since($0.value.key)) }, now: less) {
                    guard let c = covered[name] else { continue }
                    book.remove(c.key, ts: index[name]?.ts ?? less, group: c.group)
                    drop.insert(name); gone.insert(name)
                }
            }
            // A group's timer: what has been on this phone that long goes the same way, counted from the same times, and
            // onto the same gone list. Whichever of the two rules is due first removes. The difference: a packet nothing
            // here opens any more stays in the store as one this phone only carries, for the rest of its week, so that a
            // member who was away still gets the message; one a key here still opens leaves the store (`Disappear.carries`).
            var faded = Set<String>()
            for (group, timerMs) in timers {
                let held = covered.filter { $0.value.group == group && !drop.contains($0.key) }.map { KeepsLess.Held(name: $0.key, since: book.since($0.value.key)) }
                for name in Disappear.due(held, timerMs: timerMs, now: clock) {
                    guard let c = covered[name] else { continue }
                    book.remove(c.key, ts: index[name]?.ts ?? clock, group: c.group)
                    gone.insert(name)
                    if Disappear.carries(stillOpens: c.opens), let e = index[name] {
                        index[name] = StoreCap.Entry(id: e.id, ts: e.ts, size: e.size, kind: .unreadable)
                        covered[name] = nil
                        faded.insert(name)
                    } else { drop.insert(name) }
                }
            }
            let pub = index.values.filter { $0.kind == .isPublic }
            if many, pub.count > Self.maxPublic {
                // A Public packet that isn't a message is an introduction; this phone's own are the ones it wrote.
                let held = pub.map { e -> StoreCap.Held in
                    guard let m = shown[e.id] else { return StoreCap.Held(id: e.id, ts: e.ts, holder: "introductions", own: written.contains(e.id)) }
                    let sender = m.sender.hasPrefix("web-") ? String(m.sender.dropFirst(4)) : m.sender
                    return StoreCap.Held(id: e.id, ts: e.ts, holder: m.verified ? sender : "not verified", own: m.sender == deviceId)
                }
                drop.formUnion(StoreCap.everyone(held, max: Self.maxPublic))
            }
            drop.formUnion(StoreCap.overflow(index.values.filter { !drop.contains($0.id) }))
            for id in drop {
                if let e = index.removeValue(forKey: id) { indexBytes -= e.size; if e.kind == .door { doorCount -= 1; doorBytes -= e.size } }
                if let c = covered.removeValue(forKey: id) { book.forget([c.key]) } // gone from the store by any rule: no longer timed
            }
            faded.subtract(drop) // the store's cap took it after all
            return (drop, faded, gone, true)
        }
        let removed = !gone.isEmpty
        // The gone list is on the phone before the packets leave the store: if the app ends in between, they are read
        // as gone at the next start (`load`), not shown again.
        if removed { DayBookFile.write(storeLock.withLock { book }) }
        #if DEBUG
        if removed, CommandLine.arguments.contains("keepRemoved") { // what the rule removes is put aside, to deliver again in a test (`redeliver`)
            let list = storeLock.withLock { book }
            var kept: [Packet] = []
            for st in [store, more] { st.forEach { if list.isGone($0) { kept.append($0) } } }
            DebugRemoved.save(kept)
            DebugLog.write("keeps less: on the gone list and in the store before this removal: \(kept.map(\.id).joined(separator: ","))")
        }
        #endif
        if !drop.isEmpty { remove(drop, gone: gone) }
        if !faded.isEmpty { remove(faded, packets: false, gone: gone) }
        if ran { scheduleDue() }
        // Only now that the store has dropped the packets a week old (the same week, counted from the same moment) do
        // their names leave the gone list: a stored copy must not outlive its name there, or the next start would open it.
        if ran, storeLock.withLock({ book.expire(now: now) }) { DayBookFile.write(storeLock.withLock { book }) }
    }

    /// Takes the packets that go by these names out of the stores, with this phone's copies, their alerts and their rows. On `work`.
    /// `packets` false: the packets themselves stay, as ones this phone only carries (a group's timer, `compact`);
    /// everything this phone could read or show of them goes all the same. `gone`: the names that went onto the gone
    /// list ("Keeps less", a group's timer): one of them still waiting for its sender's key loses its message key too.
    private func remove(_ drop: Set<String>, packets: Bool = true, gone: Set<String> = []) {
        if packets { store.remove(drop); more.remove(drop) }
        history.remove(Set(drop.map(Self.copyId)))
        // An alert is posted under its message's id: it must not keep showing the text of a message that is gone.
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: Array(drop))
        #if DEBUG
        DebugLog.write(packets ? "store: removed \(drop.count) packets" : "timer: \(drop.count) message(s) gone, their packets still carried")
        #endif
        DispatchQueue.main.async {
            self.raw.removeAll { drop.contains($0.id) }; self.rawIds.subtract(drop)
            self.idsLock.withLock { self.shownAs = self.shownAs.filter { !drop.contains($0.value) } }
            let waited = self.unchecked.forget(drop); self.deliveries.forget(drop) // nothing waits for, or confirms, a message that is gone
            // A message that went by a rule while it waited for its sender's key: its own key, still held, goes now (spec
            // "Disappearing messages"). Brought back by a neighbour it is on the gone list and never opened again anyway.
            var spent: [Keys] = []
            for e in waited where gone.contains(e.row ?? "") {
                guard let l = e.item.o.leaf else { continue }
                l.forget(); if !spent.contains(where: { $0 === e.item.k }) { spent.append(e.item.k) }
            }
            spent.forEach(self.saveMessageKeys)
            self.reactions.forget(drop) // a reaction counts while its packet is here
            self.refresh()
            Unread.shared.updateAppBadge() // what is gone no longer counts as unread
        }
    }

    /// The Everyone messages by id: who sent each, and whether that was checked. Main thread.
    private func everyoneSenders() -> [String: (sender: String, verified: Bool)] {
        var out: [String: (sender: String, verified: Bool)] = [:]
        for m in raw where m.isPublic { out[m.id] = (sender: m.sender, verified: m.verified) }
        return out
    }

    /// The store grew well past its cap while running: trim it in the background. Any thread.
    private func compactIfFull() {
        let full = storeLock.withLock {
            guard allKeys, !compacting, indexBytes > StoreCap.maxBytes / 10 * 11 || index.count > StoreCap.maxCount + 1_000 || StoreCap.doorFull(count: doorCount, bytes: doorBytes) else { return false }
            compacting = true; return true
        }
        if full { work.async { self.compact() } }
    }

    /// Counts `p` in the store. False if the store already has a packet under its id that this one doesn't replace.
    @discardableResult private func indexed(_ p: Packet, _ kind: StoreCap.Kind) -> Bool {
        storeLock.withLock {
            let had = index[p.id]
            guard had == nil || (had?.kind == .unreadable && kind != .unreadable) else { return false } // a readable packet replaces junk sent first under its id
            index[p.id] = StoreCap.Entry(id: p.id, ts: p.ts, size: Packet.headerSize + p.cipher.count, kind: kind)
            indexBytes += Packet.headerSize + p.cipher.count - (had?.size ?? 0)
            return true
        }
    }

    /// A packet that arrived: kept, and counted, under the name it goes by (`StoreCap.name`), which is returned. The first
    /// packet stored under an id goes by that id; any other packet with that id is kept too, by its key, in a file of its
    /// own. Nothing replaces anything: whoever sends junk, or a message of their own, first under an id doesn't keep the
    /// real packet out of the store.
    @discardableResult private func keep(_ p: Packet, _ kind: StoreCap.Kind) -> String {
        let (name, first, isNew): (String, Bool, Bool) = storeLock.withLock {
            let first = index[p.id] == nil, name = StoreCap.name(p, first: first)
            if index[name] != nil { return (name, first, false) }
            let size = Packet.headerSize + p.cipher.count
            index[name] = StoreCap.Entry(id: name, ts: p.ts, size: size, kind: kind)
            indexBytes += size
            if kind == .door { doorCount += 1; doorBytes += size }
            return (name, first, true)
        }
        if isNew { (first ? store : more).append(p) }
        return name
    }

    // MARK: - Keeps less (high-risk mode): since when, and the gone list

    /// A packet that opened as a message or a receipt of `k`'s group is in the store under `name`: "Keeps less" covers it.
    /// `arrived`: a link brought it just now, and that is when its day starts, by this phone's clock, whenever it was
    /// written. False for a packet written here: its day starts when it first leaves (`leftPhone`). Any thread.
    /// `opens`: a key on this phone still opens it (`covered`).
    private func cover(_ name: String, _ p: Packet, _ k: Keys, own: Bool, arrived: Bool, opens: Bool) {
        let key = MeshRouter.key(p), now = Self.clock()
        let (noted, idle) = storeLock.withLock { () -> (Bool, Bool) in
            covered[name] = (key, k.id, own, opens)
            return (arrived && book.note(key, at: now), dueAt == nil)
        }
        if noted { saveBookSoon() }
        // The group's timer: if no clean-up is set, this is the next packet due.
        if idle, k.watch.seconds ?? 0 > 0 { work.async { self.scheduleDue() } }
    }

    /// One of this phone's own packets (a message, a reaction, a receipt) was handed to a link, or a relay took it: if
    /// that is the first time, its day starts now. Until then "Keeps less" never removes it. Any thread; called by the
    /// router's `send` while it holds its lock, so it only notes.
    private func leftPhone(_ id: String) {
        let now = Self.clock()
        let noted = storeLock.withLock { () -> Bool in
            guard let c = covered[id], c.own else { return false }
            return book.note(c.key, at: now)
        }
        if noted { saveBookSoon(); if storeLock.withLock({ dueAt == nil }) { work.async { self.scheduleDue() } } }
    }

    /// Saves the book 2 s from now, with whatever else is noted until then: catching up can bring hundreds of packets.
    /// A time that isn't saved when the app ends is noted again at the next start (`load`), so that packet stays a
    /// little longer, never shorter. What the rule removes is saved at once (`compact`).
    private func saveBookSoon() {
        let first = storeLock.withLock { () -> Bool in let was = bookQueued; bookQueued = true; return !was }
        guard first else { return }
        work.asyncAfter(deadline: .now() + 2) { self.saveBook() }
    }

    /// On `work`.
    private func saveBook() {
        guard !stopped else { return }
        DayBookFile.write(storeLock.withLock { () -> DayBook in bookQueued = false; return book })
    }

    /// The id this phone's copy of a message is kept under in "history.bin": the packet's name, or for a packet that goes
    /// by its key the second half of it (a record's id is 8 bytes).
    private static func copyId(_ name: String) -> String { name.count > 16 ? String(name.suffix(16)) : name }

    /// Called by the router while it holds its lock: a copy of a stored packet came with more hops left. The saved ttl
    /// is raised too, or a restart would pass the packet on with the first copy's. Written 10 s later, together with
    /// those that came meanwhile, in one pass over the file.
    private func onRaised(_ p: Packet) {
        let first = storeLock.withLock { () -> Bool in
            let was = raisedTtl.isEmpty
            if raisedTtl.count < 1_000 { raisedTtl[MeshRouter.key(p)] = p.ttl }
            return was
        }
        guard first else { return }
        work.asyncAfter(deadline: .now() + 10) {
            let ttls = self.storeLock.withLock { () -> [String: Int] in let all = self.raisedTtl; self.raisedTtl = [:]; return all }
            self.store.raise(ttls); self.more.raise(ttls)
        }
    }

    // MARK: - Messages

    /// Shows the messages again: this group's and the Public channel's in `messages`, all groups' in `allMessages`,
    /// without blocked people and reported messages. Main thread.
    private func refresh() {
        let blocked = Set(Moderation.blocked.keys), hidden = Moderation.hidden // read once, not per message
        var ids: [String: String] = [:] // group code -> group id ("" if not known), looked up once per group
        var books: [String: LeaveBook] = [:] // group code -> who left there (spec "Leaving the group"), read once per group
        let visible = raw.filter { m in
            if hidden.contains(m.id) { return false }
            // What a member who left dates after their note isn't shown (rule L3), also if it came before the note.
            if !m.isPublic, m.sender != deviceId {
                let code = m.group ?? ""
                if books[code] == nil { books[code] = held(code)?.leaving ?? LeaveBook() }
                if books[code]!.hides(m.sender, ts: m.ts) { return false }
            }
            if blocked.isEmpty { return true }
            var channel: String? = Blocks.everyone // a block holds in the channel it was made in (`Blocks`)
            if !m.isPublic {
                let code = m.group ?? ""
                if ids[code] == nil { ids[code] = groupId(code) ?? "" }
                channel = ids[code].flatMap { $0.isEmpty ? nil : $0 }
            }
            return !Blocks.hides(blocked, channel: channel, sender: m.sender)
        }
        allMessages = visible
        messages = visible.filter { $0.isPublic || $0.group == activeCode }
        // What is here but not shown: a reply to it says "hidden on this phone", not that the phone doesn't have it.
        var gone = Set<String>()
        if visible.count != raw.count {
            let seen = Set(visible.map(\.id))
            for m in raw where !seen.contains(m.id) { if let t = About.target(of: m, group: m.group) { gone.insert(t.key) } }
        }
        if gone != hiddenTargets { hiddenTargets = gone }
    }

    /// What a reaction packet says, for `Reactions`: nil for a type 0x02 message that is no reaction. `name`: what its packet goes by.
    private func reaction(_ m: Message, _ k: Keys, _ name: String) -> Reactions.Entry? {
        guard case .reaction(let t)? = m.about else { return nil }
        return Reactions.Entry(group: k.id, target: t.key, sender: m.sender, text: m.text, ts: m.ts, id: StoreCap.packetId(name))
    }

    /// The reactions under `m`, as chips. `blocked`: `Moderation.blocked`'s keys, read once by the caller: what someone
    /// blocked in this group reacts with isn't shown here, like their messages. Main thread.
    func chips(for m: Message, blocked: Set<String>) -> [Reactions.Chip] {
        guard let t = About.target(of: m, group: m.group), let g = groupId(m.group) else { return [] }
        return reactions.chips(group: g, target: t.key, me: deviceId, hidden: { blocked.isEmpty ? false : Blocks.hides(blocked, channel: g, sender: $0) })
    }

    /// True if this phone can write to `m`'s group now: only then are "Reply" and the reactions offered. Main thread.
    func canAnswer(_ m: Message) -> Bool {
        guard let k = held(m.group), !k.isPublic, !k.removed, k.joined else { return false }
        return About.target(of: m, group: k.code) != nil
    }

    /// Sets this phone's reaction to `m`, changes it, or takes it back when `emoji` is the one it already has (spec
    /// "Reactions"). It shows at once. Nothing happens if `m` can't be reacted to or the group can't be written to. Main thread.
    func react(to m: Message, _ emoji: String) {
        guard !stopped, canAnswer(m), Reaction.sendable(emoji), let k = held(m.group), let target = About.target(of: m, group: k.code) else { return }
        let had = reactions.mine(group: k.id, target: target.key, me: deviceId)
        let text = had?.text == emoji ? "" : emoji
        work.async { self.introIfDue(k, writing: true) }
        // The latest counts: later than this phone's last one, whatever the clock did meanwhile.
        let cr = k.group, id = Packet.newId(mailbox: cr.mailbox), ts = Swift.max(Self.nowMs(), (had?.ts ?? 0) + 1)
        guard let sealed = sealOne(k, cr, isPublic: false, id: id, ts: ts, text: text, photo: nil, about: .reaction(target)) else { return }
        let c = sealed.cipher, p = Packet(id: id, ts: cr.hideTime(ts, c), ttl: Self.maxTtl, cipher: c)
        // Written here, so it goes up to the relays first; kept off LoRa. Not one of `mine`: no "Sent", no "Delivered".
        idsLock.withLock { _ = myReceipts.insert(id); _ = receiptIds.add(MeshRouter.key(p)) }
        store.append(p)
        indexed(p, .group)
        cover(id, p, k, own: true, arrived: false, opens: !sealed.keyed)
        reactions.add(id, Reactions.Entry(group: k.id, target: target.key, sender: deviceId, text: text, ts: ts, id: id))
        router.publish(p)
    }

    /// Adds a message in time order. Main thread.
    /// False if a message with this id is already there.
    @discardableResult private func add(_ m: Message) -> Bool {
        guard rawIds.insert(m.id).inserted else { return false }
        let i = raw.lastIndex { $0.ts <= m.ts }.map { $0 + 1 } ?? 0
        raw.insert(m, at: i)
        refresh()
        return true
    }

    /// Adds a received message unless another row already shows the very message it carries (`GroupCrypto.Opened.mark`):
    /// the same signed message can come in several packets. False if nothing was added. Main thread.
    private func show(_ m: Message, mark: String) -> Bool {
        let free = idsLock.withLock { () -> Bool in if let row = shownAs[mark], row != m.id { return false }; return true }
        guard free, add(m) else { return false }
        idsLock.withLock { shownAs[mark] = m.id }
        return true
    }

    /// The saved name of the group whose code is `code` (`Message.group`), for alerts. Main thread.
    func groupName(_ code: String?) -> String {
        if code == activeCode { return UserDefaults.standard.string(forKey: "groupName") ?? "your group" }
        return SavedGroups.all().first { $0.code == code }?.name ?? "a saved group"
    }

    /// Writes a message from this phone, to the group on screen (or the saved group `group`, e.g. answering its
    /// "Are you safe?") or to the Public channel. Call on the main thread.
    /// Returns false if it is too long for a LoRa radio that is linked now (it still travels the other ways).
    /// `replyTo`: the message this one answers (spec "Replies"), in the same group; anything that can't be a target is
    /// left out, and the message goes as an ordinary one.
    /// `fifth`, `at`: a check-in clock's 5th field and the time its text was written for (spec "If I go quiet"), from
    /// `sendClock` only, which first checks that this phone can write in the group: a clock is never held for later.
    /// `voice`: a voice note's frames (`Voice`), with `Voice.placeholder` as the text; groups only, never LoRa (too big).
    @discardableResult func send(_ text: String, isPublic: Bool = false, photo: Data? = nil, group: String? = nil, replyTo: Message? = nil,
                                 fifth: Data? = nil, at: Int64? = nil, voice: Data? = nil) -> Bool {
        guard !stopped else { return true } // shut down (a group was left, or everything erased): nothing is written any more
        if voice != nil && isPublic { return true } // never on the Public channel (spec "Voice notes")
        let target = group.flatMap { $0.isEmpty ? nil : $0 } ?? activeCode
        let code = isPublic ? GroupCrypto.publicCode : target
        let k = keys(code), cr = k.group // cached; derived here only if the user writes within a second of switching
        if !isPublic && k.removed { return true } // the screen no longer offers this
        if !isPublic && !k.joined { waitingToJoin[k.id, default: []].append((text, photo, voice)); return true } // sent once an admin lets this phone in
        work.async { self.introIfDue(k, writing: true) } // members can check this message only with this phone's key
        let id = Packet.newId(mailbox: cr.mailbox), ts = at ?? Int64(Date().timeIntervalSince1970 * 1000)
        let about: About? = isPublic ? nil : replyTo.flatMap { About.target(of: $0, group: k.code) }.map { .reply($0) } // never on the Public channel
        let fifth = isPublic || about != nil ? nil : fifth
        guard let (c, mark, keyed) = sealOne(k, cr, isPublic: isPublic, id: id, ts: ts, text: text, photo: photo, about: about, fifth: fifth, voice: voice) else { return true }
        let p = Packet(id: id, ts: cr.hideTime(ts, c), ttl: Self.maxTtl, cipher: c)
        idsLock.withLock { _ = mine.insert(id) }
        store.append(p)
        indexed(p, isPublic ? .isPublic : .group)
        if !isPublic { cover(id, p, k, own: true, arrived: false, opens: !keyed) } // "Keeps less" and the group's timer leave it alone until it has left this phone
        var m = Message(id: id, ts: ts, sender: deviceId, name: name, text: text, isPublic: isPublic, photo: isPublic ? nil : photo,
                        group: isPublic ? nil : k.code) // the group's code now, if `target` was one it has moved on from
        m.about = about; m.mark = mark; m.notice = fifth.flatMap { Notice.read(field: $0, ts: ts) }; m.voice = isPublic ? nil : voice
        add(m)
        if !isPublic { deliveries.sent(group: k.id, id) } // only this group's receipts confirm it
        // This phone's own clock, or a check-in of its own that stops it (spec "If I go quiet").
        if let effect = QuietClock.effect(of: m) { quietApply(k, sender: k.me, id: id, mark: mark?.mark ?? "", ts: ts, effect: effect, text: text) }
        router.publish(p)
        return (!lora.connected || Meshtastic.fits(p)) && (!meshcore.connected || MeshCore.fits(p))
    }

    /// Sends a check-in with this phone's battery level and, if you share it, your position (a fresh GPS fix can take
    /// up to 10 s: `checkInSending` is set meanwhile and a second tap is ignored). The haptic comes right away.
    /// `group`: a saved group other than the one on screen. Main thread.
    func checkIn(_ c: CheckIn, needs: Set<Need> = [], people: Int? = nil, group: String? = nil, done: (() -> Void)? = nil) {
        guard !stopped, checkInSending == nil else { return }
        checkInSending = c
        UINotificationFeedbackGenerator().notificationOccurred(c == .safe ? .success : .warning)
        let battery = Self.batteryPercent
        LocationService.shared.forCheckIn { [weak self] spot in
            guard let self else { return }
            self.send(c.text(battery: battery, spot: spot, needs: needs, people: people), group: group)
            self.checkInSending = nil
            done?()
        }
    }

    private func setStatus(_ key: String, _ line: String?) {
        DispatchQueue.main.async {
            self.status[key] = line
            let bt = self.ble.nodes, mc = self.mc.nodes, net = self.wifi.nodes
            let all = Set(bt).union(mc).union(net) // a phone linked several ways counts once
            self.bluetoothLinks = bt.count; self.wifiLinks = mc.count + net.count; self.wifiNetworkLinks = net.count
            self.linkedNodes = all
            self.phones = all.count; self.radioLinked = self.lora.connected; self.meshcoreLinked = self.meshcore.connected
            self.relaysLinked = self.nostr?.connected ?? 0
            #if DEBUG
            if CommandLine.arguments.contains("fakeRelays") { self.relaysLinked = 4 } // screenshots of the online state, offline
            #endif
            self.throughTor = self.nostr?.throughTor
            self.bluetooth = self.ble.state
            self.localNetworkDenied = self.wifi.localNetworkDenied
            self.watchTor()
        }
    }

    /// With "Connect through Tor": after 2 minutes without getting through, say so (ConnectionsView). Main thread.
    private func watchTor() {
        guard throughTor == false else { torWaitingSince = nil; torSlow = false; return }
        guard torWaitingSince == nil else { return }
        let since = Date()
        torWaitingSince = since
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) { [weak self] in
            guard let self, self.torWaitingSince == since, self.throughTor == false else { return }
            self.torSlow = true
        }
    }

    /// Called by the router while it holds its lock: every transport only queues the packet.
    private func route(_ peer: String, _ p: Packet) {
        // Only the radios ask whether this very packet is a receipt or a reaction: its key is a hash of the whole packet.
        let radio = peer == LoraLink.peer || peer == MeshCoreLink.peer, key = radio ? MeshRouter.key(p) : ""
        let (newlyMine, ownPacket, receipt, before) = idsLock.withLock {
            (mine.contains(p.id) && !sentIds.contains(p.id), mine.contains(p.id) || myReceipts.contains(p.id), radio && receiptIds.contains(key), early != nil)
        }
        // For the relays and the radios: did this phone write this very packet, or did a link bring it? Its id alone
        // doesn't say: anyone can send another packet under the id of one of this phone's (MeshRouter.source).
        let from = peer == LoraLink.peer || peer == MeshCoreLink.peer || peer == NostrLink.peer ? router.source(p) : nil
        let own = ownPacket && from == ""
        if peer == LoraLink.peer {
            if receipt { return }             // receipts and reactions stay off LoRa, where airtime is scarce
            lora.offer(p, mine: own)          // the radios send this phone's own packets first (LoraQueue)
            if !Meshtastic.fits(p) { return } // too long for the radio: not really sent
        }
        else if peer == MeshCoreLink.peer {
            if receipt { return }             // same rules as Meshtastic
            meshcore.offer(p, mine: own)
            if !MeshCore.fits(p) { return }
        }
        else if peer.hasPrefix(BleLink.prefix) { ble.send(peer, p) }
        else if peer.hasPrefix(MultipeerLink.prefix) { mc.send(peer, p) }
        else if peer.hasPrefix(WifiLink.prefix) { wifi.send(peer, p) }
        // Written on this phone (a message or a receipt): goes up first. "Sent" once a relay accepts it.
        else if peer == NostrLink.peer { nostr.send(p, mine: own, from: from ?? ""); return }
        if newlyMine { markHandedOff(p.id, over: Self.link(peer)) }
        else if ownPacket { leftPhone(p.id) } // a receipt or a reaction written here: "Keeps less" counts from now
        else if before { idsLock.withLock { if let n = early?.count, n < 4_000 { early?[p.id] = peer } } } // whose it is isn't known yet
    }

    /// The kind of link a router peer is (spec "Journey notes"); nil for none this app knows.
    private static func link(_ peer: String?) -> Journey.Link? {
        guard let peer else { return nil }
        if peer.hasPrefix(BleLink.prefix) { return .bluetooth }
        if peer.hasPrefix(WifiLink.prefix) { return .wifi }
        if peer.hasPrefix(MultipeerLink.prefix) { return .direct }
        if peer == LoraLink.peer || peer == MeshCoreLink.peer { return .radio }
        if peer == NostrLink.peer { return .internet }
        return nil
    }

    /// One of my messages left this phone: to a phone, a radio or the internet relays ("Sent"). Saved on the phone,
    /// so the label survives restarts, and with it over which kind of link it first left and when ("How it got
    /// there"): the kind only, never which phone or link. Any thread.
    private func markHandedOff(_ id: String, over link: Journey.Link?) {
        guard !stopped else { return }
        // A relay took it before the store was read: settled then (`early`).
        if idsLock.withLock({ () -> Bool in guard early != nil, !mine.contains(id), !myReceipts.contains(id) else { return false }; if early!.count < 4_000 { early?[id] = .some(nil) }; return true }) { return }
        leftPhone(id)
        let saved: (list: [String], over: [String: String])? = idsLock.withLock {
            guard mine.contains(id), sentIds.add(id) else { return nil }
            if let how = link.flatMap({ Journey.Left(link: $0, at: Self.nowMs()).saved }) { sentOver[id] = how }
            if sentOver.count > sentIds.count { sentOver = sentOver.filter { sentIds.contains($0.key) } } // forgotten with the "Sent" label
            return (sentIds.list, sentOver)
        }
        guard let saved else { return }
        UserDefaults.standard.set(saved.list, forKey: "handedOff")
        UserDefaults.standard.set(saved.over, forKey: Self.sentOverKey)
        DispatchQueue.main.async { self.handedOff = Set(saved.list); self.leftOver = saved.over.compactMapValues(Journey.Left.init(saved:)) }
    }

    /// What `p` is: tried with this group's keys, then the Public channel's (photos are group-only; in someone else's
    /// Everyone messages and places, offensive words are masked), then the other saved groups'. `group` says which
    /// group. Nothing is checked yet, except introductions, which carry their own key.
    private func see(_ p: Packet, _ k: (active: Keys?, others: [Keys], pub: Keys?)) -> Seen? {
        let box = Packet.mailbox(of: p.id) // every key has one mailbox: junk costs one try, not one per key this phone holds
        for keys in [k.active, k.pub].compactMap({ $0 }) + k.others {
          for e in keys.ring.all where e.group.mailbox == box {
            if let o = e.open(p) { return message(keys, o) }
            if let o = e.openReceipt(p) {
                guard let r = asMine(o.message, keys.me) else { return .forged }
                return .receipt(r, keys, o)
            }
          }
          // Only the Public channel reads through its door: in a group the code alone doesn't let anyone write.
          if keys.isPublic, keys.door.crypto.mailbox == box, let o = keys.door.crypto.openSigned(p) { return message(keys, o) }
          if keys.intros.mailbox == box, let o = keys.intros.openSigned(p) { return introduction(keys, o) ?? .forged }
          if !keys.isPublic {
              for d in [keys.door] + keys.oldDoors where d.rekeys.mailbox == box { // old doors: for removals that crossed
                  guard let o = d.rekeys.openSigned(p) else { continue }
                  guard let r = o.extra.flatMap(Rekey.decode) else { return .forged }
                  return .newKey(keys, o, r, p.id, d)
              }
          }
        }
        return nil
    }

    private func message(_ keys: Keys, _ o: GroupCrypto.Opened) -> Seen {
        guard var m = asMine(o.message, keys.me) else { return .forged } // a sender that is no member id: like a packet nobody can open
        if keys.isPublic { m = m.onPublicChannel(mine: m.sender == deviceId) } // no photo, voice note or 5th field there, whatever the packet carries
        else { m.group = keys.code }
        return .msg(m, keys, o, false)
    }

    private static let historyLock = NSLock()
    private static var historyKeyHeld: Data? // historyLock; forgotten by "Erase everything" (`forgetHistoryKey`)
    private static var historyKey: Data {
        historyLock.withLock {
            if let k = historyKeyHeld { return k }
            if let k = Vault.get("history"), k.count == 32 { historyKeyHeld = k; return k }
            var b = [UInt8](repeating: 0, count: 32); _ = SecRandomCopyBytes(kSecRandomDefault, 32, &b)
            Vault.put("history", Data(b))
            historyKeyHeld = Data(b)
            return Data(b)
        }
    }

    /// Keeps a readable copy of a message opened with its own key: that key is about to be deleted. `id`: the name its
    /// packet goes by (`StoreCap.name`).
    private func remember(_ id: String, _ k: Keys, _ o: GroupCrypto.Opened) {
        guard let l = o.leaf, let blob = History.seal(key: Self.historyKey, id: id, group: k.id, epoch: l.epoch.id, o, index: l.index, counter: l.counter) else { return }
        history.append(Packet(id: Self.copyId(id), ts: o.message.ts, ttl: 0, cipher: blob))
    }

    /// A message from this phone's copy, while its epoch is still held: when the epoch goes, the message goes too.
    private func fromHistory(_ id: String, _ blob: Data, _ set: (active: Keys?, others: [Keys], pub: Keys?)) -> Seen? {
        guard let s = History.open(key: Self.historyKey, id: id, blob: blob),
              let k = ([set.active].compactMap({ $0 }) + set.others).first(where: { $0.id == s.group }),
              let e = k.ring.all.first(where: { $0.id == s.epoch }) ?? k.ring.all.first(where: { $0.hash == s.epoch }) else { return nil } // by hash: copies kept before epochs had an id
        var o = s.opened
        o.leaf = Leaf(epoch: e, index: s.index, counter: s.counter); o.opener = e
        guard var m = asMine(o.message, k.me) else { return nil }
        m.group = k.code
        return .msg(m, k, o, true)
    }

    /// The names of the packets whose copies in "history.bin" go with the epochs `compact` just deleted (`History.goes`):
    /// group id -> the epochs deleted, and those still held. A copy is sealed under its packet's name, and kept under
    /// `copyId` of it: a name longer than that is found in the index. On `work`.
    private func copies(of expired: [String: (gone: [Keyring.Epoch], held: [Keyring.Epoch])]) -> Set<String> {
        guard !expired.isEmpty else { return [] }
        let long = storeLock.withLock { Dictionary(index.keys.filter { $0.count > 16 }.map { (Self.copyId($0), $0) }, uniquingKeysWith: { a, _ in a }) }
        var out = Set<String>()
        history.forEach { p in
            for name in [p.id, long[p.id]].compactMap({ $0 }) {
                guard let s = History.open(key: Self.historyKey, id: name, blob: p.cipher) else { continue }
                if let e = expired[s.group], History.goes(s, with: e.gone, held: e.held) { out.insert(name) }
                break
            }
        }
        return out
    }

    /// Each held epoch's message keys, in the Keychain: they are saved after every message whose key was deleted.
    private func saveMessageKeys(_ k: Keys) {
        k.moveLock.withLock {
            var d = Data()
            for e in k.ring.all {
                guard let m = e.messageKeys?.encode() else { continue }
                d.append(Data(e.id.utf8))
                d.append(contentsOf: [UInt8(m.count >> 24 & 255), UInt8(m.count >> 16 & 255), UInt8(m.count >> 8 & 255), UInt8(m.count & 255)])
                d.append(m)
            }
            Vault.put("mkeys.\(k.id)", d)
        }
    }

    /// A group message goes under its own key, deleted here at once: from then on only this phone's copy can show it (spec "Message keys").
    /// `about`: the 5th field of a reply or reaction (spec "Replies and reactions"); `fifth`: another 5th field as it is
    /// (spec "The 5th field"). Neither ever goes on the Public channel.
    /// With the cipher comes what a reply or reaction to this message names (`About.Target`), for a group message, and
    /// `keyed`: it was sealed with its own message key, which is deleted, so nothing on this phone opens the packet again.
    /// `voice`: a voice note's frames, the 4th field (spec "Voice notes"); never on the Public channel.
    private func sealOne(_ k: Keys, _ cr: GroupCrypto, isPublic: Bool, id: String, ts: Int64, text: String, photo: Data?, about: About? = nil, fifth: Data? = nil, voice: Data? = nil) -> (cipher: Data, mark: About.Target?, keyed: Bool)? {
        if !isPublic, let e = k.ring.currentOrNil, let keys = e.messageKeys, let i = keys.index(k.me), let n = keys.takeNext(), let mk = keys.key(i, n) {
            guard let c = try? cr.seal(messageKey: mk, index: i, counter: n, id: id, ts: ts, sender: k.me, name: name, text: text, photo: photo, about: about, fifth: fifth, voice: voice, sign: { try k.sign($0) }) else { return nil }
            guard c.count <= Packet.maxCipher else { keys.forget(i, n); saveMessageKeys(k); return nil } // longer than any phone takes, and than the store reads back
            var mark: About.Target?
            if var o = cr.open(messageKey: mk, index: i, counter: n, Packet(id: id, ts: ts, ttl: 0, cipher: c), at: ts) {
                o.leaf = Leaf(epoch: e, index: i, counter: n)
                remember(id, k, o)
                mark = About.Target(o)
            }
            keys.forget(i, n)
            saveMessageKeys(k)
            return (c, mark, true)
        }
        guard let c = try? cr.seal(id: id, ts: ts, sender: k.me, name: name, text: text, photo: isPublic ? nil : photo, about: isPublic ? nil : about, fifth: isPublic ? nil : fifth,
                                   voice: isPublic ? nil : voice, sign: { try k.sign($0) }),
              c.count <= Packet.maxCipher else { return nil }
        // Sealed with the epoch's own key (this phone's numbers ran out): read back once for what names the message.
        return (c, isPublic ? nil : cr.openSigned(Packet(id: id, ts: ts, ttl: 0, cipher: c)).map { About.Target($0) }, false)
    }

    /// A valid introduction: the 4th field is 0x02 and a public key whose hash starts with the sender's id, and it signed it.
    private func introduction(_ k: Keys, _ o: GroupCrypto.Opened) -> Seen? {
        guard let e = o.extra else { return nil }
        let withPq = e.count == 99 + MlKem.publicSize && e.first == 3 // 0x03, signing key (33), encryption key (65), ML-KEM key (1184)
        guard withPq || (k.isPublic && e.count == 99 && e.first == 2) else { return nil } // 0x02, without the ML-KEM key: the Public channel only
        if withPq, MlKem.encapsulate(Data(e.suffix(MlKem.publicSize))) == nil { return nil } // a key nobody could seal to would block every new key
        let pub = Data(e[e.startIndex + 1 ..< e.startIndex + 34]), enc = Data(e[e.startIndex + 34 ..< e.startIndex + 99]), id = o.message.sender
        guard o.message.name.utf8.count <= 200, MemberKey.id(pub) == id, (try? P256.KeyAgreement.PublicKey(x963Representation: enc)) != nil, // names are 40 characters at most in the app
              let c = MemberKey.Checker(pub), c.verify(digest: o.digest, signature: o.signature) else { return nil }
        return .intro(k, id, c, enc, o.message.name, o.message.ts, withPq ? Data(e.suffix(MlKem.publicSize)) : nil)
    }

    /// On this phone, its own messages carry `deviceId` as sender: one id for "me" in every group. Nil for a packet whose
    /// sender is no member id, or is `deviceId` itself (`Senders.shown`): nothing received may pass for written here.
    private func asMine(_ m: Message, _ me: String) -> Message? {
        guard let s = Senders.shown(wire: m.sender, me: me, device: deviceId) else { return nil }
        return s == m.sender ? m : m.withSender(s)
    }

    /// A packet seen for the first time: keep it, and show it if a saved group or the Public channel can read it and
    /// its signature checks (or its sender's key isn't known yet: shown as not verified, checked when it comes).
    /// Messages of every saved group are confirmed with that group's receipt key and alerted.
    private func onDeliver(_ p: Packet) {
        guard !stopped else { return }
        #if DEBUG
        DebugLog.write("received \(p.id) sent-at \(p.ts)")
        #endif
        // "Keeps less" removed this very packet and a neighbour, which carries it for 7 days, brought it back. The router
        // has passed it on already, like a packet this phone can't open, and here it gets no row, no alert, no receipt
        // and no copy. It goes into the store again, to be carried across a restart, only if no key on this phone opens
        // it any more (`DayBook.storesAgain`): what this phone could still read (a receipt, a message whose own key
        // wasn't deleted yet) never returns to the store. Until every saved group's key is loaded that can't be told,
        // and nothing is written.
        if let kind = storeLock.withLock({ book.kind(of: p) }) {
            let ready = storeLock.withLock { allKeys }, opens = ready && see(p, keySet) != nil
            let stored = ready && DayBook.storesAgain(stillOpens: opens)
            if stored { keep(p, kind) }
            #if DEBUG
            DebugLog.write("gone \(p.id) came back: passed on, " + (stored ? "kept for carrying (no key here opens it)" : opens ? "not stored (a key here still opens it)" : "not stored (keys not loaded yet)"))
            #endif
            compactIfFull()
            return
        }
        switch see(p, keySet) {
        case .intro(let k, let id, let c, let enc, let who, let ts, let pq)?:
            keep(p, k.isPublic ? .isPublic : doorKind(k, id, signed: true)) // through a group's door: the group's own only from someone in it
            let fresh = learn(k, id, c, enc: enc, encAt: ts, pq: pq, name: who)
            #if DEBUG
            if fresh { DebugLog.write("introduction \(p.id): a new member key") }
            #endif
            // Not in the current epoch: a request to join. It waits for an admin here to let them in.
            // At most one alert a minute in a group: the requests themselves are in the chat (`joinRequests`).
            let asking = fresh && !k.isPublic && !k.removed && id != k.me && !k.members.contains(id) && k.ring.currentOrNil?.admins.contains(k.me) == true && !declined(k).contains(id)
                && storeLock.withLock { () -> Bool in
                    let at = Self.nowMs()
                    guard Joining.alertDue(last: joinAlertAt[k.id] ?? 0, now: at) else { return false }
                    joinAlertAt[k.id] = at; return true
                }
            DispatchQueue.main.async {
                self.resolve(k, id)
                self.refresh()
                if asking { Notifier.shared.received(Message(id: "join-\(id)", ts: Self.nowMs(), sender: id, name: who, text: "asks to join", group: k.code)) }
            }
        case .newKey(let k, let o, let r, let pid, let d)?:
            let ok = signed(o, r), was = k.ring.currentOrNil
            if acceptRekey(k, o, r, pid, d) {
                // Our introduction again, at once: newcomers need our keys to check our messages. Messages stored
                // before the epoch came may open now; relays follow its mailbox.
                UserDefaults.standard.removeObject(forKey: "introAt.\(k.id)")
                let first = rescanLock.withLock { () -> Bool in defer { rescanFlag.on = true }; return !rescanFlag.on }
                if first { work.async {
                    self.rescanLock.withLock { self.rescanFlag.on = false }
                    self.load()
                    self.cleanUpIfTimed() // a row read again that a group's timer has made due goes now, not at the next clean-up
                    self.nostr.resubscribe()
                    for g in [self.keySet.active].compactMap({ $0 }) + self.keySet.others { self.introIfDue(g) }
                } }
            }
            // Classed once the keyring has decided: the key that lets this phone in is from someone in the group it now knows.
            keep(p, doorKind(k, o.message.sender, signed: ok))
            // Made after our current epoch, yet numbered below it: that phone missed our keys. Answered after 0-3 s unless another member did.
            if d === k.door, ok, k.ring.behind(r, maker: o.message.sender) {
                work.asyncAfter(deadline: .now() + .milliseconds(Int.random(in: 0..<3_000))) { self.answerBehind(k, made: r.made, at: was) }
            }
            if !k.ring.stillToRemove().isEmpty { work.asyncAfter(deadline: .now() + .milliseconds(Int.random(in: 0..<3_000))) { self.finishRemovals(k) } }
            sweepLeaving(k) // a member who left may be out now
        case .msg(let m0, let k, let o, _)?:
            // A reaction (spec "Reactions"): kept and passed on like any group message, but never a row, an alert or a
            // receipt, and off LoRa like a receipt, whatever its signature turns out to be.
            // So is a notice written only for apps that don't know what it is about (spec "The 5th field"). A leave
            // note is never a row either, but goes on the radio (spec "Leaving the group").
            let silent = m0.isSilent
            if m0.offRadio { idsLock.withLock { _ = receiptIds.add(MeshRouter.key(p)) } }
            let ok = check(k, o)
            if ok == false {
                keep(p, .unreadable)
                #if DEBUG
                DebugLog.write("dropped \(p.id): signature doesn't check")
                #endif
                break
            }
            // Another packet under an id that is taken, which only brings again a message already shown, or names this phone
            // as its sender (the same message sealed again): nothing to keep or show.
            if storeLock.withLock({ index[p.id] != nil }), m0.sender == deviceId || idsLock.withLock({ shownAs[o.mark] != nil }) { break }
            // Any other packet under an id that is taken is a message of its own: kept and shown by its name (`StoreCap.name`).
            let name = keep(p, m0.isPublic ? .isPublic : .group)
            if !m0.isPublic { cover(name, p, k, own: m0.sender == deviceId, arrived: true, opens: o.leaf == nil || ok != true) } // its time on this phone starts now
            var m = name == p.id ? m0 : m0.withId(name); m.verified = ok == true
            m.mark = About.Target(o) // what a reply or a reaction to it names: the same whichever copy of the message this is
            remember(name, k, o)
            if ok == true, let l = o.leaf { l.forget(); saveMessageKeys(k) } // this message's key is gone: only this phone's copy can show it now
            if k.rejoining, m.sender != deviceId, m.ts >= k.rejoiningSince { setRejoining(k, 0) } // someone wrote in a key this phone has: it's back
            if m.notice == .leave, !m.isPublic { // someone left (spec "Leaving the group"): a line, never a bubble, an alert or a receipt
                idsLock.withLock { shownAs[o.mark] = name } // the same note sealed again isn't kept twice
                if ok == true { tookNote(k, o, checked: true) }
                else { DispatchQueue.main.async { self.unchecked.receipt(o.key, channel: ObjectIdentifier(k), sender: o.message.sender, Pending(k: k, o: o, receipt: nil, reaction: (name, nil))) } }
                break
            }
            // What a member who left dates after their note isn't shown, alerted, confirmed or counted (rule L3).
            if !m.isPublic, m.sender != deviceId, k.leaving.hides(o.message.sender, ts: m.ts) { break }
            if silent {
                idsLock.withLock { shownAs[o.mark] = name } // the same reaction sealed again isn't kept twice
                let entry = reaction(m, k, name)
                DispatchQueue.main.async {
                    // Counted only once its signature has checked; until its sender's key comes it waits, like a receipt.
                    if ok == true { if let entry { self.reactions.add(name, entry) } }
                    else { self.unchecked.receipt(o.key, channel: ObjectIdentifier(k), sender: o.message.sender, Pending(k: k, o: o, receipt: nil, reaction: (name, entry))) }
                }
                break
            }
            // A check-in clock, or a check-in that cancels one (spec "If I go quiet"): only once its signature checked
            // for a member (rule Q1); one waiting for its sender's key is decided when it checks (`resolve`).
            if ok == true, !m.isPublic { quietSaw(k, o, checked: true) }
            // How this first copy came (spec "Journey notes"): the hops it had left, and the kind of link that brought it.
            let note = Self.link(router.source(p)).flatMap { Journey.Note(ttl: p.ttl, link: $0) }
            #if DEBUG
            if let v = m.voice { DebugLog.write("received voice note \(m.id): \(v.count + 1) bytes, \(Voice.split(v)?.count ?? 0) packets, verified \(m.verified), playable \(Voice.playable(m))") }
            #endif
            DispatchQueue.main.async {
                let others = !m.isPublic && m.sender != self.deviceId
                let fresh = self.show(m, mark: o.mark)
                // It waits for its sender's key only as the message shown under its id: another packet with that id,
                // checked later, must not mark or remove this row.
                if ok == nil { self.unchecked.message(o.key, channel: ObjectIdentifier(k), sender: o.message.sender, id: m.id, shown: fresh, Pending(k: k, o: o, receipt: nil)) }
                if fresh, !m.isPublic, m.sender == self.deviceId { self.deliveries.sent(group: k.id, m.id) } // written here before the store was lost: its receipts count again
                // Not for a message already shown: another packet under its id, sealed again by a member, alerts and confirms nothing.
                guard fresh, others else { return }
                self.ack(p.id, k, note) // confirmed by its packet id, whatever name it goes by here
                guard self.visible(m) else { return }
                Notifier.shared.received(m)
            }
        case .receipt(let r, let k, let o)?:
            let ok = check(k, o)
            let name = keep(p, StoreCap.received(ok)) // classed by its signature, like a message: unreadable until it checks, so junk receipts can't push real messages out
            if ok != false { cover(name, p, k, own: r.sender == deviceId, arrived: true, opens: true) } // the receipt key opens it for as long as the epoch is held
            idsLock.withLock { _ = receiptIds.add(MeshRouter.key(p)) }
            switch ok {
            case true?: DispatchQueue.main.async { self.deliveries.add(group: k.id, r) }; quietReceipt(k, r)
            case nil: DispatchQueue.main.async { self.unchecked.receipt(o.key, channel: ObjectIdentifier(k), sender: o.message.sender, Pending(k: k, o: o, receipt: r)) }
            case false?: break
            }
        default:
            keep(p, .unreadable)
        }
        compactIfFull()
    }

    /// Confirms a group message in the next receipt, with that group's key. Messages arriving less than 2 s apart
    /// share one receipt, for at most 10 s (ReceiptBatch). Main thread.
    /// `note`: how the message reached this phone, for the receipt to say (spec "Journey notes"), if that is known.
    private func ack(_ id: String, _ key: Keys, _ note: Journey.Note?) {
        let now = Self.uptimeMs()
        if pendingAcks.isEmpty { firstAck = now; ackTimer(after: ReceiptBatch.quietMs) }
        lastAck = now
        pendingAcks.append((id, key, note))
    }

    private func ackTimer(after ms: Int64) {
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(ms))) { [weak self] in
            guard let self, !self.pendingAcks.isEmpty else { return }
            let now = Self.uptimeMs()
            if ReceiptBatch.due(firstMs: self.firstAck, lastMs: self.lastAck, nowMs: now) { // a receipt doesn't show how soon this phone got the message
                DispatchQueue.main.asyncAfter(deadline: .now() + Double.random(in: 0..<20)) { [weak self] in self?.flushAcks() }
            }
            else { self.ackTimer(after: Swift.max(10, Swift.min(self.lastAck + ReceiptBatch.quietMs, self.firstAck + ReceiptBatch.maxWaitMs) - now)) }
        }
    }

    private func flushAcks() {
        let batch = stopped ? [] : pendingAcks
        pendingAcks = []
        var groups: [(key: Keys, ids: [Receipt.Entry])] = []
        for a in batch {
            let e = Receipt.Entry(id: a.id, note: a.note)
            if let i = groups.firstIndex(where: { $0.key.code == a.key.code }) { groups[i].ids.append(e) } else { groups.append((a.key, [e])) }
        }
        let highRisk = HighRiskSetting.isOn() // in high-risk mode a receipt says nothing about how the message came
        for (k, ids) in groups {
            let rc = k.receipts
            for b in Receipt.batches(of: ids) {
                let id = Packet.newId(mailbox: rc.mailbox), ts = Int64(Date().timeIntervalSince1970 * 1000)
                guard let c = try? rc.seal(id: id, ts: ts, sender: k.me, name: name, text: Receipt.text(for: b, highRisk: highRisk), sign: { try k.sign($0) }) else { continue }
                let p = Packet(id: id, ts: rc.hideTime(ts, c), ttl: Self.maxTtl, cipher: c)
                idsLock.withLock { _ = receiptIds.add(MeshRouter.key(p)); _ = myReceipts.insert(id) }
                store.append(p)
                indexed(p, .group)
                cover(id, p, k, own: true, arrived: true, opens: true) // a receipt counts from when it was written: it is no message that could be lost unsent
                router.publish(p)
            }
        }
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private static let hexDigits = Array("0123456789abcdef".utf8)
    private static func hex(_ d: Data) -> String {
        var out = [UInt8](); out.reserveCapacity(d.count * 2)
        for b in d { out.append(hexDigits[Int(b >> 4)]); out.append(hexDigits[Int(b & 15)]) }
        return String(decoding: out, as: UTF8.self)
    }
    private static func unhex(_ h: String) -> Data {
        var d = Data(), i = h.startIndex
        while i < h.endIndex { let j = h.index(i, offsetBy: 2); d.append(UInt8(h[i..<j], radix: 16) ?? 0); i = j }
        return d
    }
    private static func uptimeMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }
}

/// Someone with the code asking to be let into a group; its admins decide (spec "Joining").
struct JoinRequest: Identifiable { let id: String, name: String, pub: Data }

/// A line in the group's chat: "Marco joined", "Anna removed Marco".
struct GroupEvent: Identifiable { let ts: Int64, text: String; var id: String { "\(ts)-\(text)" } }

/// Where one of my messages is: still on this phone, given to a phone in range, or confirmed by group members.
enum Delivery: Equatable {
    case waiting, sent, delivered([Reader])
}

extension Engine {
    /// nil for other people's messages, and for my older ones that haven't gone out yet.
    func delivery(of m: Message) -> Delivery? {
        guard m.sender == deviceId else { return nil }
        if !m.isPublic, let r = deliveries.readers[m.id], !r.isEmpty { return .delivered(r) }
        if handedOff.contains(m.id) { return .sent }
        return m.ts >= startedAt ? .waiting : nil
    }
}

// MARK: - Group security and checking people (GroupSecurity.swift, PeopleView.swift)

/// Someone else in a group, from its member list. `name`: from their introduction, "" if it hasn't reached this phone.
struct GroupMember: Identifiable { let id: String, name: String }

extension Engine {
    /// People checked in person (`Checked.inPersonEntry`), next to the "checked" marks: "<group tag>|<member id>".
    fileprivate static let inPersonKey = "checkedInPerson"

    /// Everyone else in `code`'s group: the current key's member list without this phone, in its order. Empty while joining.
    func otherMembers(_ code: String?) -> [GroupMember] {
        guard let k = held(code), !k.isPublic else { return [] }
        let ids = k.members.filter { $0 != k.me }
        return k.lock.withLock { ids.map { GroupMember(id: $0, name: k.known[$0]?.name ?? "") } }
    }

    /// When `code`'s current key started, as the weekly change counts it (`rekeyIfDue`): its date, or when this phone got
    /// it if that was later, so its maker can't make it look older. Nil while this phone has no key of the group.
    func keySince(_ code: String?) -> Int64? { held(code).flatMap { $0.isPublic ? nil : $0.ring.currentOrNil?.since } }

    /// True if member `id` is blocked in `code`'s group on this phone (`Blocks`, as `visible` reads them): hidden in People too.
    func isBlocked(_ code: String?, _ id: String) -> Bool {
        let blocked = Moderation.blocked
        return !blocked.isEmpty && Blocks.hides(Set(blocked.keys), channel: groupId(code), sender: id)
    }

    /// True if this phone can start a new key for `code`'s group now (`newKey`): it is an admin there, wasn't removed,
    /// the current key has been in use for a day (`Keyring.mayAsk`: a join or a removal makes a key anyway, and one asked
    /// for every hour would flood the mesh and push older keys out early), and this phone hasn't made a key in the last
    /// hour (`Keyring.tooSoon`, the limit its answering keys keep to).
    func canStartNewKey(_ code: String?) -> Bool {
        guard !stopped, let k = held(code), let cur = k.ring.currentOrNil, !k.isPublic, !k.removed else { return false }
        return cur.admins.contains(k.me) && Keyring.mayAsk(since: cur.since, now: Self.clock()) && !k.ring.tooSoon(k.me, made: Self.clock())
    }

    /// An admin asks for a new key now (Group security). Made exactly as the weekly one is (`rekeyIfDue`), only without
    /// waiting for the week: the same members and admins, sealed to everyone. `done`, on the main thread: true if it was
    /// made, false if a member's keys haven't reached this phone (`makeRekey`), nil if this phone may not make one now.
    func newKey(_ code: String?, done: @escaping (Bool?) -> Void) {
        work.async {
            var made: Bool?
            if self.canStartNewKey(code), let k = self.held(code), let cur = k.ring.currentOrNil {
                let away = Keyring.overdue(made: cur.since, now: Self.clock())
                made = self.makeRekey(k, add: [], answers: nil)
                if made == true && away { self.setRejoining(k, Self.nowMs()) }
            }
            DispatchQueue.main.async { done(made); self.refresh() }
        }
    }

    /// How `id` was checked in `code`'s group: by emoji (also every mark made before this was kept), or in person by
    /// scanning their person code. Nil: not checked.
    func checkedHow(_ code: String?, _ id: String) -> Checked.How? {
        guard let t = tagOf(code) else { return nil }
        return Checked.how(checkedList, inPerson: UserDefaults.standard.stringArray(forKey: Self.inPersonKey) ?? [], group: t, id: id)
    }

    /// The user compared `id`'s emoji again and they didn't match: a mark made earlier goes.
    func unmarkChecked(_ code: String?, _ id: String) {
        guard !stopped, let t = tagOf(code), isChecked(code, id) else { return }
        let d = UserDefaults.standard, list = Checked.unmarked(checkedList, group: t, id: id)
        d.set(list, forKey: "checked")
        d.set(Checked.markedHow(d.stringArray(forKey: Self.inPersonKey) ?? [], checked: list, group: t, id: id, how: .emoji), forKey: Self.inPersonKey) // its in-person entry goes with it
        objectWillChange.send()
    }
}

// MARK: - High-risk mode, leaving a group, erasing everything (HighRisk.swift, Wipe.swift)

extension Engine {
    // MARK: Telling the group before leaving (spec/FORMAT.md, "Leaving the group")

    /// Rule LW1: this phone can tell `code`'s group that it leaves: it has a current epoch there, wasn't removed and
    /// isn't still waiting to be let in. Otherwise the screens say "This phone isn't let in, so My group can't be told."
    func canTell(_ code: String?) -> Bool {
        guard !stopped, let k = held(code), !k.isPublic, !k.removed, k.joined else { return false }
        return true
    }

    /// Rule LW2: this phone is the only admin of `code`'s current epoch and others are in it: it asks who runs the group
    /// before it leaves, or nobody could let people in or remove anyone afterwards.
    func onlyAdmin(_ code: String?) -> Bool {
        guard let k = held(code), !k.isPublic, !k.removed, let cur = k.ring.currentOrNil, cur.admins.contains(k.me) else { return false }
        let others = cur.members.filter { $0 != k.me }
        return !others.isEmpty && !others.contains { cur.admins.contains($0) }
    }

    /// Seals and sends the leave note to `code`'s group (rules LW3, LW4): an ordinary group message with the text
    /// `👋 I left the group` and the 5th field `05 01`, with this phone's message key in its current epoch, ttl 16. It
    /// goes to the relays with this phone's own packets first and on the radios. Never a row here, and nothing is
    /// awaited (no receipt, no retry): "Sent" bookkeeping (`markHandedOff`) only tells when a link took it
    /// (`noteLeft`). This phone never removes itself (rule 3 of the design): an admin's phone that stays does. The
    /// packet's id, or nil if this phone can't tell the group (`canTell`). Main thread.
    func sendLeaveNote(_ code: String?) -> String? {
        guard canTell(code), let k = held(code) else { return nil }
        let cr = k.group, id = Packet.newId(mailbox: cr.mailbox), ts = Self.nowMs()
        guard let sealed = sealOne(k, cr, isPublic: false, id: id, ts: ts, text: LeaveNote.text, photo: nil, fifth: LeaveNote.field) else { return nil }
        let p = Packet(id: id, ts: cr.hideTime(ts, sealed.cipher), ttl: Self.maxTtl, cipher: sealed.cipher)
        idsLock.withLock { _ = mine.insert(id) } // written here: up to the relays first, and "Sent" once a link takes it
        store.append(p)
        indexed(p, .group)
        router.publish(p)
        #if DEBUG
        DebugLog.write("leave note \(id) sent")
        #endif
        return id
    }

    /// True once the note `id` was handed to a phone, a radio or a relay (`markHandedOff`). Any thread.
    func noteLeft(_ id: String) -> Bool {
        #if DEBUG
        if CommandLine.arguments.contains("noteStays") { return false } // tests: the note's wait runs its full 15 s
        #endif
        return idsLock.withLock { sentIds.contains(id) }
    }

    /// What leaving a group took out of the packet store, for the rest to follow (`Wipe.leave`): the group's id, the
    /// codes it has and had on this phone, and the names its messages and other packets went by.
    struct Left { let id: String, codes: [String], messages: Set<String> }

    /// Ends this engine for good: a group was left, or everything is erased (`Mesh.stop`). From this call on it takes no
    /// packet, sends nothing and saves nothing new. Its links are closed first, each waited for, then whatever was
    /// loading, saving or making a key is waited for, then the stores are closed: `done`, on the main thread, runs when
    /// nothing of this engine runs any more, so what it kept can be deleted and nothing writes it again. What is still
    /// timed (the hourly check, a key list to save, receipts to send) finds it stopped and does nothing.
    /// `code`: the group that is left; what its keys open goes out of the stores before they close (`removeStored`).
    /// `keeping`: the leave note's packet, which stays as a packet this phone only carries (rule LW3).
    func shutDown(leaving code: String? = nil, keeping note: String? = nil, then done: @escaping (Left?) -> Void) {
        stopLock.withLock { stopFlag = true }
        DispatchQueue.global(qos: .userInitiated).async { // not the main thread: `work` asks it for things while it runs
            self.nostr.stopAndWait(); self.wifi.stopAndWait(); self.mc.stopAndWait()
            self.ble.stopAndWait(); self.lora.stopAndWait(); self.meshcore.stopAndWait()
            self.work.sync {}
            let left = code.map { self.keys($0) }.flatMap { $0.isPublic ? nil : self.removeStored(of: $0, keeping: note) }
            self.store.close(); self.more.close(); self.history.close()
            DispatchQueue.main.async { done(left) }
        }
    }

    /// Leaving `k`'s group, with the engine shut down: every stored packet its keys open, and every copy this phone kept
    /// of its messages, goes out of the stores. A packet whose own message key is deleted is found by its copy, which
    /// names the group. The keys, and what the preferences hold of the group, are deleted by the caller. Not on the main thread.
    /// `note`: the leave note's packet (rule LW3). It stays in the store: once the keys are gone it is one more packet this
    /// phone carries and can't open, until its 7 days end, so the note still reaches the group when phones come into
    /// reach. Its name is still listed in `Left.messages`, so the "Sent" lists lose it with the group's other messages.
    private func removeStored(of k: Keys, keeping note: String? = nil) -> Left {
        let code = k.code
        var names = DispatchQueue.main.sync { Set(raw.filter { !$0.isPublic && $0.group == code }.map(\.id)) }
        let only = (active: Optional(k), others: [Keys](), pub: Optional<Keys>.none)
        for s in [store, more] { s.forEachNamed { p, name in if self.see(MeshRouter.clamp(p), only) != nil { names.insert(name) } } }
        // A copy is sealed under its packet's name and kept under `copyId` of it (`copies(of:)`).
        let long = storeLock.withLock { Dictionary(index.keys.filter { $0.count > 16 }.map { (Self.copyId($0), $0) }, uniquingKeysWith: { a, _ in a }) }
        var found: [(kept: String, packet: String)] = []
        history.forEach { p in
            for name in [p.id, long[p.id]].compactMap({ $0 }) {
                guard let s = History.open(key: Self.historyKey, id: name, blob: p.cipher) else { continue }
                if s.group == k.id { found.append((p.id, name)) }
                break
            }
        }
        // The note's packet stays, also if a copy of it named it: its copy goes with the others (`LeaveCleanup`).
        let cleanup = LeaveCleanup.removed(packets: names, copies: found, keeping: note)
        names = cleanup.packets
        let copies = cleanup.copies
        store.remove(names); more.remove(names)
        history.remove(copies.union(names.map(Self.copyId)))
        // "Keeps less": nothing of the group stays in its book, neither since when its packets were here nor its gone list.
        DayBookFile.write(storeLock.withLock { () -> DayBook in
            for name in names { if let c = covered.removeValue(forKey: name) { book.forget([c.key]) } }
            book.forget(group: k.id)
            return book
        })
        // Only codes that were this group's door here: the one it has, and those its removals came through.
        var codes = [code]
        for c in k.oldDoors.map(\.code) + k.ring.all.compactMap(\.via) where !codes.contains(c) { codes.append(c) }
        return Left(id: k.id, codes: codes, messages: names.union(note.map { [$0] } ?? []))
    }

    /// Leaving `code`'s group while no engine is running (`Mesh.stop`): the group's id, worked out from the code as an
    /// engine does, and what can be removed of it with no keys in memory: this phone's copies of its messages, which
    /// name the group, the packets those are copies of, and its names on the gone list. Its other packets stay as
    /// packets nobody here can open: the caller deletes the group's keys next. Not on the main thread: the code's key
    /// takes a moment to work out.
    static func removeStored(ofGroupWithCode code: String) -> Left {
        let door = Door(code)
        let id = Vault.get("origin.\(door.id)").flatMap { String(data: $0, encoding: .utf8) } ?? door.id
        let store = PacketStore(), more = PacketStore(file: "more.bin", naming: .key), history = PacketStore(file: "history.bin")
        var long: [String: String] = [:] // a copy is kept under `copyId` of its packet's name, and sealed under the name
        more.forEachNamed { _, name in long[copyId(name)] = name }
        var names = Set<String>(), copies = Set<String>()
        history.forEach { p in
            for name in [p.id, long[p.id]].compactMap({ $0 }) {
                guard let s = History.open(key: historyKey, id: name, blob: p.cipher) else { continue }
                if s.group == id { names.insert(name); copies.insert(p.id) }
                break
            }
        }
        store.remove(names); more.remove(names); history.remove(copies)
        var book = DayBookFile.read()
        if book.forget(group: id) { DayBookFile.write(book) }
        return Left(id: id, codes: [code], messages: names)
    }

    /// The clean-up, now and not at the next full hour: high-risk mode was switched on, or the app came to the front with
    /// it on (iOS stops the hourly timer while the app is suspended). Any thread.
    func cleanUpNow() { work.async { self.compact() } }

    /// "Erase everything" deleted the key of this phone's message copies from the Keychain: the next engine makes a new one.
    static func forgetHistoryKey() { historyLock.withLock { historyKeyHeld = nil } }
}

// MARK: - If I go quiet (spec/FORMAT.md, "If I go quiet")

extension Engine {
    /// The time check-in clocks are judged by: this phone's clock (debug builds: `quietAhead` moves it forward for tests).
    static func quietNow() -> Int64 {
        #if DEBUG
        return nowMs() + quietAhead
        #else
        return nowMs()
        #endif
    }

    /// A group message that may act on its sender's clock: a clock message (rules Q2, Q3) or an own check-in (Q4). It
    /// counts only if its signature checked for a member listed from the epoch that opened it through the current one,
    /// with no leave note of theirs held (Q1): nobody can set, move or stop someone else's clock. Any thread.
    private func quietSaw(_ k: Keys, _ o: GroupCrypto.Opened, checked: Bool?) {
        guard !stopped, !k.isPublic, let effect = QuietClock.effect(of: o.message) else { return }
        let sender = o.message.sender
        guard QuietClock.counts(sender: sender, checked: checked, opener: o.opener, ring: k.ring, left: k.leaving.hides(sender, ts: o.message.ts)) else { return }
        let t = About.Target(o) // its packet id and the start of its signed digest
        quietApply(k, sender: sender, id: t.id, mark: t.mark, ts: o.message.ts, effect: effect, text: o.message.text)
    }

    /// Takes a clock message or a check-in of `sender` (a member id; this phone's own is `k.me`) into the group's book.
    /// One clock per member: the newest by its own time counts (Q3). Any thread.
    private func quietApply(_ k: Keys, sender: String, id: String, mark: String, ts: Int64, effect: QuietClock.Effect, text: String) {
        guard !stopped, !k.isPublic else { return }
        let now = Self.quietNow(), high = HighRiskSetting.isOn()
        let changed = k.changeQuiet { b in
            switch effect {
            case .clock(let d): return b.take(member: sender, id: id, mark: mark, ts: ts, deadline: d, spot: QuietClock.spot(in: text), now: now, highRisk: high)
            case .checkIn: return b.checkIn(member: sender, ts: ts, now: now, highRisk: high)
            }
        }
        if changed { quietChanged() }
    }

    /// The screens read the books again, and a pass follows (many changes at once, as at a start, make one). Any thread.
    private func quietChanged() {
        DispatchQueue.main.async {
            self.quietVersion &+= 1
            guard !self.quietQueued else { return }
            self.quietQueued = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.quietQueued = false; self.quietPass() }
        }
    }

    /// Rules Q9, Q10: the clocks of members removed without a leave note go without an alert; a member who left with a
    /// note keeps theirs (it alerts at its deadline as usual), also after the removal that followed the note. Records
    /// past their time go, and a record's position goes when the group's timer would have deleted the clock message
    /// (`QuietBook.sweep`). Any thread.
    private func sweepQuiet(_ k: Keys) {
        guard !k.isPublic, let cur = k.ring.currentOrNil else { return }
        let leaving = k.leaving, now = Self.quietNow(), high = HighRiskSetting.isOn(), timer = k.watch.deletes(now: Self.nowMs())
        if k.changeQuiet({ $0.sweep(now: now, highRisk: high, positionMs: timer) { cur.members.contains($0) || leaving.left($0) } }) { quietChanged() }
    }

    /// Decides every clock alert and this phone's own reminder against what waits with iOS (rules Q5 to Q8, Q12): at
    /// the start, on screen again, hourly, after any change, at the next deadline, and when the lock or high-risk mode
    /// is switched (the alerts that wait are made again with what they may say now). Main thread.
    ///
    /// What can't be guaranteed: an alert is a local notification that iOS posts at the deadline by this phone's clock,
    /// also while DarkProof is suspended or ended, but not while the phone is off or alerts are off for DarkProof. While
    /// DarkProof is suspended it can't take a check-in from the relays or a neighbour, so it can't take the alert back:
    /// the alert can come although the member checked in. Its words cover that ("has reached this phone"). A check-in
    /// that arrives while the app runs (on screen, or woken in the background for Bluetooth) removes it in time.
    func quietPass() {
        guard !stopped else { return }
        let s = keySet, groups = ([s.active].compactMap { $0 } + s.others).filter { !$0.isPublic }
        for k in groups { sweepQuiet(k) }
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { st in
            // Without permission iOS takes nothing to post later: then no alert counts as handed to it, and while the app
            // runs it shows the banner itself at the deadline (the next pass is timed for it).
            let allowed = [.authorized, .provisional, .ephemeral].contains(st.authorizationStatus)
            center.getPendingNotificationRequests { reqs in
                let pending = Set(reqs.map(\.identifier).filter { $0.hasPrefix(QuietAlerts.prefix) })
                DispatchQueue.main.async { self.quietDecide(groups, pending: pending, allowed: allowed) }
            }
        }
    }

    private func quietDecide(_ groups: [Keys], pending: Set<String>, allowed: Bool) {
        guard !stopped else { return }
        let center = UNUserNotificationCenter.current(), now = Self.quietNow(), hide = AppLock.shared.enabled || HighRisk.shared.isOn
        var want: [String: UNNotificationRequest] = [:], coming: [(k: Keys, c: QuietBook.Clock)] = [], over: [String] = [], wake: Int64?
        for k in groups {
            let book = k.quiet
            for (c, due) in book.alerts(now: now, me: k.me) {
                let id = QuietAlerts.id(group: k.id, member: c.member)
                switch due {
                case .at: coming.append((k, c))
                case .none: k.changeQuiet { $0.note(c.member, mark: c.mark, alerted: true, scheduled: false) } // more than 12 hours after: never
                case .now, .late:
                    // Still waiting with iOS (this phone's clock was set back), or never handed to it (learned late, or not
                    // among the 32 nearest): posted now. Handed to iOS and gone from its list: iOS posted it at the deadline.
                    // At most one alert about a member every 10 minutes, and a late one only for a newer clock than the
                    // last alerted: a member can't make every phone ring again and again (`QuietBook.postableAt`).
                    if pending.contains(id) || !c.scheduled {
                        guard let at = book.postableAt(c, late: due == .late, now: now) else {
                            k.changeQuiet { $0.note(c.member, mark: c.mark, alerted: true, scheduled: false) }; continue
                        }
                        if at > now { wake = min(wake ?? at, at); continue } // posted when the 10 minutes are up
                        quietPost(k, c, late: due == .late, hide: hide, id: id)
                        k.changeQuiet { $0.didPost(c, now: now) }
                    }
                    k.changeQuiet { $0.note(c.member, mark: c.mark, alerted: true, scheduled: false) }
                }
            }
            // A check-in or a stop came: an alert already showing for that clock goes.
            for c in book.clocks.values where c.member != k.me && !book.armed(c.member) { over.append(QuietAlerts.id(group: k.id, member: c.member)) }
            // Rule Q8: this phone's own clock never alerts it; it reminds 10 minutes before, for clocks of 20 minutes or more.
            if let mine = book.clock(k.me), book.armed(k.me), let at = QuietClock.remindAt(ts: mine.ts, deadline: mine.deadline), at > now {
                let id = QuietAlerts.remindId(group: k.id)
                want[id] = UNNotificationRequest(identifier: id, content: quietReminder(k, mine, hide: hide), trigger: QuietAlerts.trigger(at, now: now))
                wake = min(wake ?? at, at)
            }
        }
        // Rule Q12: the 32 nearest deadlines wait with iOS; the others are handed to it as those go.
        let chosen = allowed ? Set(QuietClock.scheduled(coming) { $0.c.deadline }.map { QuietAlerts.id(group: $0.k.id, member: $0.c.member) }) : []
        if !allowed { want = [:] }
        for (k, c) in coming {
            let id = QuietAlerts.id(group: k.id, member: c.member), on = chosen.contains(id)
            if on { want[id] = UNNotificationRequest(identifier: id, content: quietContent(k, c, late: false, hide: hide), trigger: QuietAlerts.trigger(c.deadline, now: now)) }
            k.changeQuiet { $0.note(c.member, mark: c.mark, scheduled: on) }
            wake = min(wake ?? c.deadline, c.deadline)
        }
        let drop = pending.subtracting(want.keys)
        if !drop.isEmpty { center.removePendingNotificationRequests(withIdentifiers: Array(drop)) } // cancelled, replaced, or its group left
        if !over.isEmpty { center.removeDeliveredNotifications(withIdentifiers: over) }
        for r in want.values { center.add(r) } // the same identifier replaces what waits: a newer clock, or other words
        #if DEBUG
        let armed = groups.reduce(0) { n, k in let b = k.quiet; return n + b.clocks.keys.filter { b.armed($0) }.count }
        DebugLog.write("quiet: \(armed) clock(s) armed, \(want.count) alert(s) waiting with iOS, \(drop.count) taken back, \(over.count) cancelled\(allowed ? "" : ", alerts not allowed")")
        #endif
        quietWake?.cancel(); quietWake = nil
        if let wake {
            let item = DispatchWorkItem { [weak self] in self?.quietPass() }
            quietWake = item
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(Swift.max(1_000, Swift.min(wake - now + 500, Keyring.hourMs)))), execute: item)
        }
    }

    /// An alert now: as a banner while DarkProof is on screen and unlocked, else as a notification. Main thread.
    private func quietPost(_ k: Keys, _ c: QuietBook.Clock, late: Bool, hide: Bool, id: String) {
        #if DEBUG
        DebugLog.write("quiet: alert \(id) now, \(late ? "learned late" : "on time")\(hide ? ", without names" : "")") // for the tests' log: never who
        #endif
        if UIApplication.shared.applicationState == .active && !AppLock.shared.isLocked { return quietBanner(k, c, late: late) }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: quietContent(k, c, late: late, hide: hide), trigger: nil))
    }

    /// The in-app banner: neutral, not red. Main thread.
    private func quietBanner(_ k: Keys, _ c: QuietBook.Clock, late: Bool) {
        let name = quietName(k, c.member)
        Nav.shared.show(Banner(id: "quiet-\(c.mark)", kind: .ask, title: QuietWords.alertTitle(name, late: late, at: Self.hm(c.deadline)),
                               body: QuietWords.banner(name, group: groupName(k.code)), group: k.code, tab: .people, answers: false, symbol: "timer"))
    }

    /// iOS posts the clock alert or reminder `id` while DarkProof is on screen: the banner instead. False if it isn't one
    /// of this engine's. Main thread.
    func quietPresented(_ id: String) -> Bool {
        let s = keySet
        for k in ([s.active].compactMap { $0 } + s.others) where !k.isPublic {
            let book = k.quiet
            if let c = book.clocks.values.first(where: { QuietAlerts.id(group: k.id, member: $0.member) == id }) {
                if book.armed(c.member) { quietBanner(k, c, late: false) }
                k.changeQuiet { $0.note(c.member, mark: c.mark, alerted: true, scheduled: false) }
                return true
            }
            if QuietAlerts.remindId(group: k.id) == id, let mine = book.clock(k.me) {
                Nav.shared.show(Banner(id: "quiet-r-\(mine.mark)", kind: .ask, title: QuietWords.remindTitle(Self.hm(mine.deadline)), body: QuietWords.remindBody, group: k.code, tab: .home, symbol: "timer"))
                return true
            }
        }
        return false
    }

    /// Who a clock is from, as alerts and the card say it (rule Q13, `QuietWords.who`): "Anna (not checked)" for a
    /// member not checked, "Anna · 🐶🐱🦁🐴" for a checked one whose name another member of the group has too.
    func quietName(_ k: Keys, _ member: String) -> String {
        let members = Set(k.members) // the keyring's lock, not taken under the keys' own
        let (n, pub, others) = k.lock.withLock { () -> (String, Data?, [String]) in
            (k.known[member]?.name ?? "", k.known[member]?.checker.pub, k.known.filter { $0.key != member && members.contains($0.key) }.map(\.value.name))
        }
        let name = n.isEmpty ? "Someone" : n, key = Vouch.key(name)
        let shared = others.contains { Vouch.key($0.isEmpty ? "Someone" : $0) == key }
        let emoji = pub.map { MemberKey.emoji($0).map { Fingerprint.emoji[$0] } } ?? []
        return QuietWords.who(name, checked: isChecked(k.code, member), shared: shared, emoji: emoji)
    }

    /// The same for the chat's card of a clock in `code`'s group. Main thread.
    func quietName(_ code: String?, _ member: String) -> String? {
        guard let k = held(code), !k.isPublic else { return nil }
        return quietName(k, member)
    }

    /// "18:30", in the phone's own format.
    static func hm(_ ms: Int64) -> String { ChatTime.time.string(from: ChatTime.date(ms)) }

    /// Rule Q11: when the newest position known of the clock's member was sent: from the clock message or any check-in of
    /// theirs that reached this phone. None in high-risk mode. Main thread.
    private func quietPosition(_ k: Keys, _ c: QuietBook.Clock) -> Int64? {
        guard !HighRisk.shared.isOn else { return nil }
        let theirs = allMessages.filter { $0.group == k.code && !$0.isPublic && $0.verified && $0.sender == c.member }
        let checkIn = Person.all(in: theirs, me: deviceId).first { $0.id == c.member }.flatMap { $0.spot == nil ? nil : $0.spotTs }
        return [c.spot == nil ? nil : c.ts, checkIn].compactMap { $0 }.max()
    }

    /// What the alert says. With "Lock DarkProof" or high-risk mode on: no name, no time, no text. Main thread.
    private func quietContent(_ k: Keys, _ c: QuietBook.Clock, late: Bool, hide: Bool) -> UNMutableNotificationContent {
        let n = UNMutableNotificationContent(), name = quietName(k, c.member)
        n.title = hide ? QuietWords.hiddenTitle : QuietWords.alertTitle(name, late: late, at: Self.hm(c.deadline))
        n.body = hide ? QuietWords.hiddenBody : QuietWords.alertBody(name, since: Self.hm(c.ts), late: late, position: quietPosition(k, c).map(Self.hm))
        if !hide { n.subtitle = groupName(k.code) }
        n.sound = .default; n.threadIdentifier = "quiet"; n.interruptionLevel = .active
        n.userInfo = ["tab": Nav.Tab.people.rawValue, "group": GroupTag.of(k.code)] // a tag stands for the group: codes never sit in the alert database
        return n
    }

    /// This phone's own reminder, 10 minutes before its deadline (rule Q8), with "I'm safe" under it unless the lock is on.
    private func quietReminder(_ k: Keys, _ c: QuietBook.Clock, hide: Bool) -> UNMutableNotificationContent {
        let n = UNMutableNotificationContent()
        n.title = hide ? QuietWords.hiddenTitle : QuietWords.remindTitle(Self.hm(c.deadline))
        n.body = hide ? QuietWords.remindHidden : QuietWords.remindBody
        if !hide { n.subtitle = groupName(k.code); n.categoryIdentifier = Notifier.remindCategory }
        n.sound = .default; n.threadIdentifier = "quiet"
        n.userInfo = ["tab": Nav.Tab.home.rawValue, "group": GroupTag.of(k.code)]
        return n
    }

    // MARK: What the screens read and do

    /// This phone's own clock in `code`'s group while it runs or has run out (the "If I go quiet" screen, Home). Main thread.
    func quietOwn(_ code: String?, now: Int64) -> (clock: QuietBook.Clock, state: QuietBook.State)? {
        guard let k = held(code), !k.isPublic else { return nil }
        let b = k.quiet
        guard let c = b.clock(k.me), let s = b.state(k.me, now: now), b.armed(k.me) else { return nil }
        return (c, s)
    }

    /// How `member`'s clock stands on this phone (People, rule Q6).
    func quietState(_ code: String?, _ member: String, now: Int64) -> QuietBook.State? {
        guard let k = held(code), !k.isPublic, member != k.me else { return nil }
        return k.quiet.state(member, now: now)
    }

    /// Who confirmed this phone's own clock in `code`'s group, from the clock message's delivery receipts kept in its
    /// record (so the list outlives the message when the group's timer deletes it), never from the member list: only
    /// phones that got the clock can tell anyone. Only people who are members now: whoever left or was removed since
    /// forgot the group. Main thread.
    func quietKeepers(_ code: String?) -> [String] {
        guard let k = held(code), !k.isPublic, let c = k.quiet.clock(k.me), let cur = k.ring.currentOrNil else { return [] }
        let leaving = k.leaving
        return c.keepers.filter { cur.members.contains($0) && !leaving.left($0) }.map { id in
            let n = k.lock.withLock { k.known[id]?.name } ?? ""
            return n.isEmpty ? "Someone" : n
        }
    }

    /// A checked receipt in `k`'s group: if it names this phone's own clock message, its sender keeps the clock. Any thread.
    private func quietReceipt(_ k: Keys, _ r: Message) {
        guard !k.isPublic, let mine = k.quiet.clock(k.me) else { return }
        guard Receipt.entries(in: r.text).contains(where: { $0.id == mine.id }) else { return }
        if k.changeQuiet({ $0.addKeeper(me: k.me, clockId: mine.id, keeper: r.sender) }) { DispatchQueue.main.async { self.quietVersion &+= 1 } }
    }

    /// Rules QW1, QW2: sends this phone's clock message to `code`'s group, deadline `deadline(ts)` (0 stops), with where
    /// this phone is if `spot` and the user shares their position. Never on the Public channel and never held until this
    /// phone is let in: `done(false)`, and nothing is sent, if it can't write there (`canTell`). Main thread.
    func sendClock(_ code: String?, deadline: @escaping (Int64) -> Int64, spot: Bool, done: @escaping (Bool) -> Void) {
        guard canTell(code) else { return done(false) }
        let go: (Spot?) -> Void = { [weak self] where_ in
            guard let self, self.canTell(code) else { return done(false) }
            let ts = Self.nowMs(), d = deadline(ts)
            guard QuietClock.valid(deadline: d, ts: ts) else { return done(false) }
            self.send(QuietClock.text(ts: ts, deadline: d, spot: d == 0 ? nil : where_), group: code, fifth: QuietClock.field(deadline: d), at: ts)
            #if DEBUG
            DebugLog.write("quiet: clock \(d == 0 ? "stopped" : "of \((d - ts) / 60_000) min") sent")
            #endif
            done(true)
        }
        if spot { LocationService.shared.forCheckIn(go) } else { go(nil) }
    }
}

#if DEBUG
import UIKit

extension Engine {
    /// Debug builds only (launch argument `samples`): what replies, reactions and "How it got there" look like, on one
    /// simulator with nobody else in the group. Other people's messages, reactions and receipts are put straight into
    /// memory here: nothing of them is sealed, stored or sent, and they are gone at the next start. This phone's own
    /// reply, reaction and message go the real way, so their packets carry the real fields. Main thread.
    /// Debug builds only (launch argument `declineAsking`): turns down everyone the group on screen counts as asking to
    /// join, members removed in the last week included, so that a test phone can ask again once `Joining.maxAsking` is reached.
    func debugDeclineAsking() {
        let code = activeCode
        work.async {
            guard let k = self.held(code), !k.isPublic else { return }
            let ids = self.asking(k)
            UserDefaults.standard.set(Array(self.declined(k).union(ids)), forKey: "declined.\(k.id)")
            DebugLog.write("declineAsking: \(ids.count) turned down")
        }
    }

    func debugConversation() {
        guard let k = held(activeCode), !k.isPublic, k.joined else { DebugLog.write("samples: no group to write in"); return }
        let now = Self.nowMs(), marco = "00000000000000a1", sara = "00000000000000a2", lucia = "00000000000000a3"
        func other(_ sender: String, _ name: String, _ text: String, ago: Int64, about: About? = nil) -> Message {
            var m = Message(id: Packet.newId(), ts: now - ago, sender: sender, name: name, text: text, group: k.code)
            m.mark = About.Target(key: m.id + ":" + Packet.newId())
            m.about = about
            add(m)
            return m
        }
        func reacts(_ sender: String, _ text: String, to m: Message?, at ts: Int64) {
            guard let t = m?.mark else { return }
            let id = Packet.newId()
            reactions.add(id, Reactions.Entry(group: k.id, target: t.key, sender: sender, text: text, ts: ts, id: id))
        }
        let water = other(marco, "Marco", "Water is back at the school. Bring bottles.", ago: 9 * 60_000)
        send("On my way. Does anyone need some?", replyTo: water)
        let mine = raw.last { $0.sender == deviceId }
        // A reply to a message this phone never got.
        _ = other(sara, "Sara", "Two bottles for us, please.", ago: -1_000,
                  about: About.Target(key: Packet.newId() + ":" + Packet.newId()).map { .reply($0) })
        reacts(sara, "👍", to: water, at: now - 8 * 60_000)
        reacts(lucia, "👍", to: water, at: now - 7 * 60_000)
        reacts(lucia, "😮", to: water, at: now - 6 * 60_000) // Lucia changed hers: only the later one counts
        react(to: water, "❤️")                               // this phone's own, the real way
        reacts(marco, "🙏", to: mine, at: now)
        // One of this phone's messages, confirmed by two readers whose receipts say how it reached them.
        send("Sara, can you look in on Nonna?")
        if let asked = raw.last(where: { $0.sender == deviceId }) {
            markHandedOff(asked.id, over: .bluetooth)
            deliveries.add(group: k.id, Message(id: "debug-receipt-1", ts: asked.ts + 40_000, sender: marco, name: "Marco", text: "\(asked.id) h1b"))
            deliveries.add(group: k.id, Message(id: "debug-receipt-2", ts: asked.ts + 4 * 60_000, sender: sara, name: "Sara", text: "\(asked.id) h3i"))
        }
        DebugLog.write("samples: reply, reactions, journey")
    }
}

extension Engine {
    /// Debug builds only (launch argument `redeliver`): the packets "Keeps less" removed and `keepRemoved` put aside come
    /// in again, as from a neighbour that still carries them, through the router like any packet. Any thread.
    /// For one the router has had already in this run (a real neighbour was quicker) nothing is delivered twice: the log
    /// says whether it is on the gone list and whether a key on this phone would still open it, now that the keys are loaded.
    func debugRedeliver() {
        let packets = DebugRemoved.read()
        var taken = 0
        for p in packets {
            if router.receive(from: "debug-neighbour", p) { taken += 1; continue }
            let gone = storeLock.withLock { book.isGone(p) }
            DebugLog.write("redeliver \(p.id): the router has it already; on the gone list: \(gone); a key here still opens it: \(see(p, keySet) != nil)")
        }
        DebugLog.write("redeliver: \(packets.count) packet(s) came again, \(taken) new to the router")
    }

    /// Debug builds only: how many stored packets count as which kind, and how the book stands, for the tests' log.
    func debugStore() {
        let (kinds, cov, since, gone) = storeLock.withLock { (Dictionary(grouping: index.values, by: \.kind).mapValues(\.count), covered.count, book.since.count, book.gone.count) }
        DebugLog.write("STORE group \(kinds[.group] ?? 0) everyone \(kinds[.isPublic] ?? 0) door \(kinds[.door] ?? 0) unreadable \(kinds[.unreadable] ?? 0) | covered \(cov) timed \(since) gone \(gone)")
    }
}

/// Debug builds only, and only with the launch argument `keepRemoved`: the packets "Keeps less" removed, put aside so a
/// test can deliver them again (`redeliver`). Sealed packets as they travel, nothing opened.
enum DebugRemoved {
    private static let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("debug-removed.bin")

    static func save(_ packets: [Packet]) {
        var all = read()
        for p in packets where !all.contains(p) { all.append(p) }
        var d = Data()
        for e in all.map({ $0.encode() }) { d.append(contentsOf: [UInt8(e.count >> 24 & 255), UInt8(e.count >> 16 & 255), UInt8(e.count >> 8 & 255), UInt8(e.count & 255)]); d.append(e) }
        try? d.write(to: url, options: .atomic)
    }

    static func read() -> [Packet] {
        guard let d = try? Data(contentsOf: url) else { return [] }
        let b = [UInt8](d)
        var out: [Packet] = [], i = 0
        while i + 4 <= b.count {
            let n = Int(b[i]) << 24 | Int(b[i + 1]) << 16 | Int(b[i + 2]) << 8 | Int(b[i + 3])
            guard n > 0, i + 4 + n <= b.count else { break }
            if let p = Packet.decode(Data(b[(i + 4)..<(i + 4 + n)])) { out.append(p) }
            i += 4 + n
        }
        return out
    }
}

/// Debug builds only: Library/Application Support/debug.log with receive times and app state changes (no message
/// text), to check from a Mac (`devicectl device copy from ...`) whether messages arrive while the phone is locked.
enum DebugLog {
    private static let lock = NSLock()
    private static let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("debug.log")

    static func start() {
        DispatchQueue.main.async { write(UIApplication.shared.applicationState == .background ? "app started in background" : "app started") }
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { _ in write("app in background") }
        nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil) { _ in write("app in foreground") }
        nc.addObserver(forName: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil, queue: nil) { _ in write("phone locked") }
        nc.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: nil) { _ in write("phone unlocked") }
    }

    /// The log is kept to about 2 MB: past 1 MB it becomes "debug.old.log", replacing the one before. Neither goes in a backup.
    private static let maxBytes: UInt64 = 1 << 20
    private static let old = url.deletingLastPathComponent().appendingPathComponent("debug.old.log")

    static func write(_ s: String) {
        let line = Data("\(Int64(Date().timeIntervalSince1970 * 1000)) \(s)\n".utf8)
        lock.lock(); defer { lock.unlock() }
        if let h = try? FileHandle(forWritingTo: url) {
            let end = (try? h.seekToEnd()) ?? 0
            try? h.write(contentsOf: line)
            try? h.close()
            if end + UInt64(line.count) > maxBytes {
                try? FileManager.default.removeItem(at: old)
                try? FileManager.default.moveItem(at: url, to: old)
                var u = old, v = URLResourceValues(); v.isExcludedFromBackup = true; try? u.setResourceValues(v) // a log an earlier version began
            }
        } else {
            try? line.write(to: url)
            var u = url, v = URLResourceValues(); v.isExcludedFromBackup = true; try? u.setResourceValues(v)
        }
    }
}
#endif
