import Foundation
import Security
import UIKit
import UserNotifications
import MapLibre
import BlackoutCore

/// "Leave <group>" and "Erase everything" (Settings, "This phone"). Both shut the engine and its links down first and
/// wait until nothing of them runs (`Mesh.stop`), then delete, then let the app start again: nothing that still runs can
/// write back what was deleted. Before that, a leave note may go out (spec/FORMAT.md, "Leaving the group"): leaving
/// sends one to the group, and "Erase everything" with "Tell my groups first" to every saved group, then waits at most
/// 15 s for a link to take them (`Telling`). Nothing else is sent. Main thread.
enum Wipe {
    /// Saved before anything is deleted, and deleted last, with the preferences: if the app ends halfway, the next start
    /// finishes the job (`finishIfInterrupted`). Its value counts the starts that tried.
    static let marker = "erasing"
    private static let maxTries = 3
    /// An erase is running.
    private(set) static var busy = false
    /// A leave's shutdown is running (`leave`). An erase can still start: it waits for that shutdown and goes on.
    private(set) static var leavingBusy = false
    /// A leave note's wait (`Telling`), or the hand-over key before it, is on: no second leave starts meanwhile. An
    /// erase takes over (`eraseTellingFirst`).
    private(set) static var telling = false
    /// An erase started while a leave was running: the leave stops where it is and the erase goes on (rule LW4).
    private static var eraseTookOver = false
    /// What became of the note of a leave that an erase took over: sent, and whether it left this phone.
    private static var takenOverNote: (sent: Bool, left: Bool) = (false, false)
    /// What the last "Erase everything" could not remove (`left`); empty if it removed everything. For tests.
    private(set) static var lastLeft: [String] = []

    // MARK: - Telling first (spec "Leaving the group", LW3, LW4)

    /// Leaves `code`'s group after telling it (rules LW1 to LW3): with `handOver` (the only admin chose who runs the
    /// group), one admin new key first; then the note; then the wait, until a phone, a radio or a relay took it, 15 s,
    /// or "Leave now" (`Telling.now`); then `leave`, which keeps the note's packet to carry. A phone that can't write in
    /// the group (LW1) leaves without a note. `cantHandOver`: the new key couldn't be made (a member's keys are
    /// missing); nothing happened, and the screen asks what to do.
    static func leaveTelling(_ code: String, name: String, handOver: [String] = [], cantHandOver: (() -> Void)? = nil) {
        guard !busy, !leavingBusy, !telling, !code.isEmpty else { return }
        guard let e = Mesh.shared.engine, e.canTell(code) else { leave(code, name: name); return }
        telling = true; eraseTookOver = false
        func send() {
            guard !eraseTookOver else { return } // an erase took over during the hand-over: it goes on without this leave
            guard let id = e.sendLeaveNote(code) else { telling = false; leave(code, name: name); return }
            e.leavingCode = code // its phone makes no new key in the group while it waits (no removal, no answer)
            takenOverNote = (true, false)
            #if DEBUG
            say("leave: note sent")
            #endif
            Telling.shared.wait(for: [id]) { gone in
                telling = false
                takenOverNote = (true, !gone.isEmpty)
                #if DEBUG
                say("leave: the note " + (gone.isEmpty ? "didn't leave this phone in time" : "left this phone"))
                #endif
                guard !eraseTookOver else { return } // the erase that ended the wait goes on
                leave(code, name: name, note: id, noteLeft: !gone.isEmpty)
            }
        }
        if handOver.isEmpty { send(); return }
        e.handOver(code, to: handOver) { ok in
            guard !eraseTookOver else { return }
            if ok { send() } else { telling = false; cantHandOver?() }
        }
    }

    /// "Erase everything" (rule LW4). With `tell`: a note to every saved group this phone can write in, before anything
    /// else; then at most 15 s for links to take them, with "Erase now"; then `eraseEverything`, unchanged. Nothing can
    /// hold the erase up: the marker is saved first, so an app that ends while it waits erases at its next start; the
    /// wait runs in a background task, and ends at once if iOS ends that task's time, or if the app comes back to the
    /// front while it waits (`finishIfWaiting`). An erase always starts: one already running goes on, and a leave that
    /// runs (its hand-over key, its note's wait, its shutdown) stops where it is: the Keychain goes at once, the note's
    /// wait ends, and the erase goes on as soon as the leave's shutdown is over (`takeOverLeave`). Never "try again".
    @discardableResult static func eraseTellingFirst(_ tell: Bool) -> Bool {
        if busy { return true } // erasing already
        if telling || leavingBusy { return takeOverLeave() }
        let groups = savedGroups()
        guard tell, let e = Mesh.shared.engine else { return eraseEverything(lines: [LeaveWords.erased(sent: 0, left: 0)]) }
        let d = UserDefaults.standard
        d.set(max(1, d.integer(forKey: marker)), forKey: marker) // from here on, the erase happens whatever stops the app
        telling = true
        var task = UIBackgroundTaskIdentifier.invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "tell") { Telling.shared.now() } // out of time: erase now
        var sent: [(name: String, id: String?)] = []
        for g in groups { sent.append((g.name, e.sendLeaveNote(g.code))) }
        #if DEBUG
        say("erase: \(sent.filter { $0.id != nil }.count) note(s) sent, \(sent.filter { $0.id == nil }.count) group(s) this phone can't write in")
        #endif
        Telling.shared.wait(for: sent.compactMap(\.id)) { gone in
            telling = false
            // No group's name: the phone may be taken from its user at this moment (`LeaveWords.erased`).
            let ids = sent.compactMap(\.id)
            let lines = [LeaveWords.erased(sent: ids.count, left: ids.filter { gone.contains($0) }.count)]
            #if DEBUG
            say("erase: " + lines.joined(separator: " "))
            #endif
            eraseEverything(lines: lines)
            if task != .invalid { UIApplication.shared.endBackgroundTask(task); task = .invalid }
        }
        return true
    }

    /// An erase while a leave runs (rule LW4): the marker is saved and the Keychain goes at once, which is what opens
    /// the stored packets and the message copies; the leave's wait, if on, ends now and the leave stops there (its
    /// note stays in the store with everything else, which the erase deletes); the erase then runs, its engine stop
    /// waiting for the leave's shutdown if one is going on. The final screen says what became of the leave's note.
    private static func takeOverLeave() -> Bool {
        eraseTookOver = true
        let d = UserDefaults.standard
        d.set(max(1, d.integer(forKey: marker)), forKey: marker)
        removeKeychain()
        if Telling.shared.left != nil { Telling.shared.now() } // its callback sees `eraseTookOver` and stops there
        telling = false
        Mesh.shared.engine?.leavingCode = nil
        #if DEBUG
        say("erase: took over a leave that was running")
        #endif
        let note = takenOverNote
        return eraseEverything(lines: [LeaveWords.erased(sent: note.sent ? 1 : 0, left: note.left ? 1 : 0)])
    }

    /// Every saved group, the one in use first, once each: its code and name.
    private static func savedGroups() -> [(code: String, name: String)] {
        var out: [(code: String, name: String)] = []
        if let c = GroupSecrets.shared.current, !c.isEmpty { out.append((c, UserDefaults.standard.string(forKey: "groupName") ?? "My group")) }
        for g in SavedGroups.all() where !g.code.isEmpty && !out.contains(where: { $0.code == g.code }) { out.append((g.code, g.name)) }
        return out
    }

    // MARK: - Erase everything

    /// Everything DarkProof keeps on this phone goes, and the app is as on a fresh install: the welcome screen.
    /// False, and nothing happened, if it can't start right now: a group is being left, or an erase is already running.
    /// In order:
    /// 1. The marker is saved, so an erase the app's end cuts short is finished at the next start; with it, every other
    ///    preference is removed (the user's name, the people they checked or blocked).
    /// 2. At once, before anything is stopped: the Keychain entries, which are what opens the stored packets and the
    ///    message copies, then the files (the members' names and the conversation with the AI are plain files). A phone
    ///    taken a second later holds nothing readable, with one limit: iOS writes the preferences file by itself, up
    ///    to a few seconds after they change; the old file is deleted here, and the new one has the marker only. What
    ///    still runs may write a file, save a setting or make a key again in the next moments: step 4 deletes it.
    /// 3. What could still write is stopped, all at the same time: the AI (a download, an answer being written), a map
    ///    download, the engine with every link (Bluetooth, Apple peer-to-peer, Wi-Fi, both LoRa radios, the relays).
    /// 4. When all of it has stopped: `removeStored` deletes everything again, the Keychain included, then what the app
    ///    still holds in memory is forgotten, then `left` looks at what is still there.
    /// 5. With no group left, the welcome screen shows.
    /// The whole of it runs in a background task: locking the phone or switching app right after the hold doesn't
    /// suspend it halfway. Tor, once started, can't be stopped before the app quits (InAppTor): its folder is deleted
    /// and the links that used it are closed, but what it learned about the network stays in its memory until then.
    /// `lines`: what the final screen says of the notes ("The notes left this phone."), naming no group, held in memory
    /// only (`EraseResult`); nil shows no final screen (an erase finished at the next start).
    @discardableResult static func eraseEverything(lines: [String]? = nil) -> Bool {
        guard !busy else { return false }
        busy = true
        var task = UIBackgroundTaskIdentifier.invalid
        let endTask = { if task != .invalid { UIApplication.shared.endBackgroundTask(task); task = .invalid } }
        task = UIApplication.shared.beginBackgroundTask(withName: "erase", expirationHandler: endTask) // out of time: the marker finishes it at the next start
        // The marker is saved in the same breath as every other preference is removed, so that the first thing iOS
        // writes to the preferences file after this is the marker alone, not the old list with the marker added.
        removePreferences(keeping: [marker: max(1, UserDefaults.standard.integer(forKey: marker))])
        // iOS writes the preferences file when it sees fit, seconds later, and may first write what it had before:
        // the file is put right by hand meanwhile, so the old one is not on the phone for longer than a moment.
        for wait in [0.4, 1.5, 4.0] { DispatchQueue.main.asyncAfter(deadline: .now() + wait) { if busy { writePreferencesFile() } } }
        removeKeychain()
        removeFiles()
        Clipboard.clearIfOurs()
        Nav.shared.banner = nil; Nav.shared.sheet = nil
        OfflineMap.shared.cancel()
        let stopped = DispatchGroup()
        stopped.enter(); LocalAi.shared.forgetAll { stopped.leave() }
        stopped.enter(); stopEngine { stopped.leave() }
        #if DEBUG
        // `slowStop 12`: as if something took that many seconds to stop, to see the erase finish in the background.
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "slowStop"), args.indices.contains(i + 1), let s = Double(args[i + 1]) {
            stopped.enter(); DispatchQueue.global().asyncAfter(deadline: .now() + s) { stopped.leave() }
        }
        #endif
        stopped.notify(queue: .main) {
            removeStored()
            forgetInMemory()
            if let lines { EraseResult.shared.lines = lines }
            lastLeft = left()
            #if DEBUG
            say("erase: " + (lastLeft.isEmpty ? "nothing left" : "left \(lastLeft)") + (UIApplication.shared.applicationState == .background ? " (finished in the background)" : ""))
            #endif
            resetMap()
            busy = false; eraseTookOver = false; takenOverNote = (false, false)
            // Said once, here: the marker is gone, so nothing tries again by itself and erases what is set up next.
            if !lastLeft.isEmpty { Nav.shared.toast("Not everything could be erased. Delete DarkProof from this iPhone to remove the rest.") }
            endTask()
        }
        return true
    }

    /// Shuts the engine down for the erase. Only a leave or an erase stops an engine; when an erase takes over a leave
    /// whose shutdown is going on, this waits for that one and then stops whatever is left: the keys are deleted
    /// already, and the erase must not be left half done.
    private static func stopEngine(_ done: @escaping () -> Void) {
        if Mesh.shared.stop(then: { _ in done() }) { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { stopEngine(done) }
    }

    #if DEBUG
    /// Debug builds: a line for the tests, written out at once (a test reads it from a file while the app runs).
    private static func say(_ line: String) { print(line); fflush(stdout) }
    #endif

    /// First thing at launch (BlackoutApp), before anything reads the Keychain or opens a file.
    static func atLaunch() {
        finishIfInterrupted()
        #if DEBUG
        // Debug builds, for tests on the Simulator: `eraseAfter 15` and `leaveAfter 15` (seconds after launch) do what
        // the two rows in Settings do, without the questions. What is left afterwards is printed.
        let args = CommandLine.arguments
        func seconds(_ name: String) -> Double? { args.firstIndex(of: name).flatMap { args.indices.contains($0 + 1) ? Double(args[$0 + 1]) : nil } }
        if let s = seconds("eraseAfter") {
            DispatchQueue.main.asyncAfter(deadline: .now() + s) {
                // `tellFirst`: as with "Tell my groups first" on: the notes, at most 15 s for a link to take them, then the erase.
                say("erase: started \(args.contains("tellFirst") ? eraseTellingFirst(true) : eraseEverything())")
                // `thenBackground`: the app leaves the screen the moment the erase has started, as when the phone is
                // locked or another app opened right after the hold: the erase must still finish.
                if args.contains("thenBackground") {
                    UIControl().sendAction(#selector(URLSessionTask.suspend), to: UIApplication.shared, for: nil)
                    say("erase: sent to the background")
                }
            }
        }
        // `tab 1` (0 Home, 1 Chat, 2 Places, 3 People, 4 Ask) and `sheet settings` open a screen 7 s after launch, for pictures.
        if let i = args.firstIndex(of: "tab"), args.indices.contains(i + 1), let t = Int(args[i + 1]).flatMap(Nav.Tab.init(rawValue:)) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 7) { Nav.shared.tab = t }
        }
        if let i = args.firstIndex(of: "sheet"), args.indices.contains(i + 1), let sheet = MainView.Sheet(rawValue: args[i + 1]) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 7) { Nav.shared.sheet = sheet }
        }
        // `createGroup Second`: 8 s after launch, a new group as "Create a group" makes it; the one in use stays saved.
        if let i = args.firstIndex(of: "createGroup"), args.indices.contains(i + 1) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
                guard let e = Mesh.shared.engine else { say("createGroup: no engine"); return }
                let code = Invite.newCode()
                Engine.found(code)
                SavedGroups.switchTo(groupName: args[i + 1], code: code, engine: e)
                say("createGroup: now in \(args[i + 1])")
            }
        }
        // `leaveAtLaunch`: leaves the group in use before any engine has started (the path of `Engine.removeStored(ofGroupWithCode:)`).
        if args.contains("leaveAtLaunch"), let code = GroupSecrets.shared.current {
            leave(code, name: UserDefaults.standard.string(forKey: "groupName") ?? "My group")
        }
        if let s = seconds("leaveAfter") { // as "Leave My group" does: the note, at most 15 s for a link to take it, then the group is forgotten
            DispatchQueue.main.asyncAfter(deadline: .now() + s) {
                guard let code = GroupSecrets.shared.current else { say("leave: no group"); return }
                let name = UserDefaults.standard.string(forKey: "groupName") ?? "My group"
                if Mesh.shared.engine?.onlyAdmin(code) == true { say("leave: this phone is the only admin; the screen asks who runs the group first") }
                leaveTelling(code, name: name)
            }
        }
        #endif
    }

    /// An erase that the app's end cut short is finished. Nothing runs yet, so there is nothing to stop. If the Keychain can't be emptied (the phone hasn't
    /// been unlocked since it started), the files go now and the marker stays, for the next time the app comes to the
    /// front (`finishIfWaiting`) or the next start; after `maxTries` starts the marker goes whatever happened, so it
    /// can never erase, again and again, what was set up since.
    static func finishIfInterrupted() {
        let d = UserDefaults.standard, tries = d.integer(forKey: marker)
        guard tries > 0 else { return }
        if removeKeychain() || tries >= maxTries { removeStored() }
        else { removeFiles(); d.set(tries + 1, forKey: marker) }
    }

    /// The app came to the front with an erase still unfinished (see `finishIfInterrupted`): finished now, the engine
    /// stopped first if it has started meanwhile. That run ends with the marker gone.
    static func finishIfWaiting() {
        if telling { // an erase's notes are waiting (the marker is saved then): their wait ends here, and its end starts the erase (`eraseTellingFirst`)
            if UserDefaults.standard.integer(forKey: marker) > 0 { Telling.shared.now() }
            return
        }
        if UserDefaults.standard.integer(forKey: marker) > 0 && !busy { eraseEverything() }
    }

    /// THE LIST: everything DarkProof stores on this phone, deleted in this order. Called with everything stopped.
    private static func removeStored() {
        // 1. Keys and secrets. Every Keychain entry of the app: the saved groups and their codes ("groups"), each
        //    group's keys ("epochs.", "mkeys.", "parts.", "door.", "origin."), this phone's encryption keys there
        //    ("enc.", "chip.enc.", "mlkem."), its member keys ("chip.sign." and the "darkproof-member" entries) and the
        //    key of its message copies ("history"). Keys made in the Secure Enclave exist only as these entries.
        removeKeychain()
        // 2. Files. Everything in the folders the app can write in (`folders`): the packet stores and message copies
        //    (packets.bin, more.bin, history.bin), the members' names and keys (known.<group>.plist), the AI models and
        //    the conversation with the AI, the offline map and the map's cache, Tor's state, the URL cache and cookies,
        //    temporary files, debug logs, and the picture iOS keeps for the app switcher.
        removeFiles()
        // 3. Alerts: the ones showing and any still to come, and the number on the app's icon.
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications(); center.removeAllPendingNotificationRequests()
        center.setBadgeCount(0) { _ in }
        // 4. Preferences, last: every saved switch, name, list and date, and the marker with them.
        removePreferences()
    }

    private static func removeFiles() {
        URLCache.shared.removeAllCachedResponses()
        HTTPCookieStorage.shared.removeCookies(since: .distantPast)
        for f in folders { FolderWipe.empty(f.url, keep: f.keep) }
    }

    private static var preferencesFile: URL {
        FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Preferences/\(Bundle.main.bundleIdentifier ?? "").plist")
    }

    /// Writes the preferences file as the preferences are now. iOS does that by itself, but when it sees fit.
    private static func writePreferencesFile() {
        let now = UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "") ?? [:]
        guard let data = try? PropertyListSerialization.data(fromPropertyList: now, format: .binary, options: 0) else { return }
        try? data.write(to: preferencesFile, options: .atomic)
    }

    /// Every saved preference goes: key by key first, so the screens that show one notice, then the whole list.
    /// `keeping`: what is saved instead, in the same step (the marker, when an erase starts).
    /// The file itself is deleted first: iOS writes preferences to it by itself, some seconds after they change, and
    /// until then the old file, with the user's name and the people they blocked, would still be on the phone. It
    /// writes a new one with whatever is left.
    private static func removePreferences(keeping: [String: Any] = [:]) {
        let d = UserDefaults.standard, domain = Bundle.main.bundleIdentifier ?? ""
        try? FileManager.default.removeItem(at: preferencesFile)
        for key in (d.persistentDomain(forName: domain) ?? [:]).keys where keeping[key] == nil { d.removeObject(forKey: key) }
        if keeping.isEmpty { d.removePersistentDomain(forName: domain) }
        for (key, value) in keeping { d.set(value, forKey: key) }
        d.synchronize()
        if keeping.isEmpty { try? FileManager.default.removeItem(at: preferencesFile) } else { writePreferencesFile() }
    }

    /// What the app holds in memory of what was just deleted: without this it would be shown, or saved again later.
    private static func forgetInMemory() {
        MemberKeys.forget()                 // signing keys
        Engine.forgetHistoryKey()           // the key of the message copies
        GroupSecrets.shared.forgetAll()     // the groups and their codes: with none, the welcome screen shows
        Unread.shared.forget(nil); Drafts.shared.text = [:]
        BatteryMonitor.shared.forget()
        Notifier.shared.forgetAll()
        AppLock.shared.reset(); HighRisk.shared.reset()
        let nav = Nav.shared
        nav.tab = .home; nav.chatEveryone = false; nav.placesMap = false; nav.inviteAfterSetup = false
        removePreferences() // what was read and the battery levels saved themselves, empty, just now
    }

    /// The map library has its database open: deleted under it, it would keep writing to a file nobody can reach, and a
    /// map saved later would be gone at the next start. It starts an empty database instead (no tiles, no saved area).
    private static func resetMap() {
        let dir = MLNOfflineStorage.shared.databaseURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        MLNOfflineStorage.shared.resetDatabase { _ in }
    }

    // MARK: - What is left

    /// The folders the app can write in, each emptied: Application Support, Caches, Documents and the temporary folder,
    /// and in Library whatever else the system made there for the app (cookies, the app-switcher picture, saved
    /// state). `keep`: what stays in a folder. In Library that is `Preferences`, which the system empties (step 4 of
    /// `removeStored`), and the two folders listed on their own, which are emptied but stay: code expects them there.
    private static var folders: [(url: URL, keep: Set<String>)] {
        let fm = FileManager.default, library = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        return [(fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0], []),
                (fm.urls(for: .cachesDirectory, in: .userDomainMask)[0], []),
                (fm.urls(for: .documentDirectory, in: .userDomainMask)[0], []),
                (fm.temporaryDirectory, []),
                (library, ["Preferences", "Application Support", "Caches"])]
    }

    /// What is still on the phone after `removeStored`, for the check at the end of "Erase everything": Keychain
    /// entries of any kind, saved preferences, and files in the app's folders. Files iOS writes for the app by itself
    /// (their path has a part that starts with "com.apple.", or is the app-switcher picture or saved state) are not the
    /// app's and are left out. Empty when nothing is left.
    static func left() -> [String] {
        var out: [String] = []
        let n = keychainCount()
        if n > 0 { out.append("keychain: \(n) entries") }
        let prefs = (UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "") ?? [:]).keys.filter { $0 != marker }
        out += prefs.sorted().map { "preference: \($0)" }
        for f in folders {
            for path in FolderWipe.files(in: f.url, keep: f.keep) {
                let parts = path.split(separator: "/")
                if parts.contains(where: { $0.hasPrefix("com.apple.") }) || parts.first == "SplashBoard" || parts.first == "Saved Application State" { continue }
                out.append(f.url.lastPathComponent + "/" + path)
            }
        }
        return out
    }

    // MARK: - Keychain

    private static let keychainClasses: [CFString] = [kSecClassGenericPassword, kSecClassInternetPassword, kSecClassCertificate, kSecClassKey]

    /// Deletes every Keychain entry the app has, whatever it is kept under. The app shares no Keychain group with any
    /// other app, so these are its own. True if none is left.
    @discardableResult private static func removeKeychain() -> Bool {
        for c in keychainClasses {
            SecItemDelete([kSecClass as String: c, kSecAttrSynchronizable as String: kSecAttrSynchronizableAny] as CFDictionary)
        }
        return keychainCount() == 0
    }

    /// How many Keychain entries the app has. Entries that can't be read right now (the phone not unlocked yet) count.
    private static func keychainCount() -> Int {
        var n = 0
        for c in keychainClasses {
            let q: [String: Any] = [kSecClass as String: c, kSecMatchLimit as String: kSecMatchLimitAll, kSecReturnAttributes as String: true,
                                    kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
            var out: CFTypeRef?
            let status = SecItemCopyMatching(q as CFDictionary, &out)
            if status == errSecSuccess { n += (out as? [[String: Any]])?.count ?? 1 }
            // Everything the app keeps is a generic password: only there does "couldn't look" count as "something is left".
            else if status != errSecItemNotFound && (c as String) == (kSecClassGenericPassword as String) { n += 1 }
        }
        return n
    }

    /// The names of the app's entries under Keychain `service`.
    private static func keychainNames(_ service: String) -> [String] {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecMatchLimit as String: kSecMatchLimitAll, kSecReturnAttributes as String: true]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let all = out as? [[String: Any]] else { return [] }
        return all.compactMap { $0[kSecAttrAccount as String] as? String }
    }

    // MARK: - Leave a group

    /// This phone forgets `code`'s group, which is the one in use: its saved entry, every key and member record of it,
    /// its messages and the stored packets its keys open, what was read there and what was being typed. Then the app
    /// shows another saved group, or the welcome screen when none is left (the name stays). Nothing is sent here: the
    /// others count this phone as a member until an admin removes it, which the leave note asks for (`leaveTelling`).
    /// The engine is shut down for it and a new one started: a moment without links, and nothing of the group is still
    /// in use while it is deleted. `note`: the leave note's packet, which stays in the store as a packet this phone
    /// only carries (rule LW3). `noteLeft`: whether a link took it in time (nil: none was sent), for the toast.
    static func leave(_ code: String, name: String, note: String? = nil, noteLeft: Bool? = nil) {
        guard !busy, !leavingBusy, !code.isEmpty else { return }
        leavingBusy = true
        Nav.shared.sheet = nil; Nav.shared.banner = nil
        let stopping = Mesh.shared.stop(leaving: code, keeping: note) { left in
            let codes = left?.codes ?? [code]
            if let left { forget(left) }
            // An erase started meanwhile: it deletes everything once this shutdown is over; nothing here starts again.
            guard !busy else { leavingBusy = false; return }
            #if DEBUG
            if let left { say("leave: " + (leftOf(left).isEmpty ? "nothing of the group left" : "left \(leftOf(left))")) } else { say("leave: the group's keys were not found") }
            #endif
            for c in codes { Unread.shared.forget(c); Drafts.shared.text[c] = nil }
            QuietAlerts.forgetAll() // its check-in clock alerts go; the next engine schedules those of the groups that stay
            let next = SavedGroups.all().first { !codes.contains($0.code) }
            let d = UserDefaults.standard
            if let next { d.set(next.name, forKey: "groupName") } else { d.removeObject(forKey: "groupName") }
            GroupSecrets.shared.forget(codes, next: next?.code ?? "")
            if next == nil { DayBookFile.write(DayBook()) } // no group left: "Keeps less" has nothing to time or to keep away
            Nav.shared.tab = .home; Nav.shared.chatEveryone = false
            leavingBusy = false
            Mesh.shared.startIfSetUp()
            // The number on the app's icon counted the group too. With another group: once its messages are read from the store.
            if next == nil { UNUserNotificationCenter.current().setBadgeCount(0) { _ in } }
            else { DispatchQueue.main.asyncAfter(deadline: .now() + 6) { Unread.shared.updateAppBadge() } }
            Nav.shared.toast(LeaveWords.left(name, noteLeft: noteLeft))
        }
        if !stopping { leavingBusy = false }
    }

    #if DEBUG
    /// What still names a group after `forget`, for tests: Keychain entries, its member list, preferences. Empty when nothing does.
    private static func leftOf(_ g: Engine.Left) -> [String] {
        var out: [String] = []
        for service in Vault.services {
            let names = keychainNames(service)
            out += (LeftGroup.secrets(names, group: g.id) + names.filter { name in name.hasPrefix("origin.") && Vault.get(name).map { String(decoding: $0, as: UTF8.self) } == g.id }).map { "keychain: " + $0.prefix(12) + "…" }
        }
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        if FileManager.default.fileExists(atPath: support.appendingPathComponent("known.\(g.id).plist").path) { out.append("file: known list") }
        let r = LeftGroup.preferences(UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "") ?? [:], group: g.id,
                                      tags: Set(g.codes.map(GroupTag.of)), messages: g.messages)
        out += (r.remove + r.change.keys).map { "preference: " + $0.prefix(12) + "…" }
        return out
    }
    #endif

    /// What the phone keeps of a left group outside the packet stores (those were cleared when the engine shut down,
    /// `Engine.removeStored`): its keys, its members, and what the preferences say about it.
    private static func forget(_ g: Engine.Left) {
        // Keys: the group's and this phone's in it (`LeftGroup.secrets`), and the notes that lead its codes to it ("origin.").
        for service in Vault.services {
            let names = keychainNames(service)
            var gone = LeftGroup.secrets(names, group: g.id)
            gone += names.filter { name in name.hasPrefix("origin.") && Vault.get(name).map { String(decoding: $0, as: UTF8.self) } == g.id }
            for name in gone {
                SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name] as CFDictionary)
            }
        }
        MemberKeys.forget(g.id)
        QuietFile.remove(g.id) // its check-in clocks, with their positions
        // Members: their names and keys.
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.removeItem(at: support.appendingPathComponent("known.\(g.id).plist"))
        // Preferences: everything saved under the group's id, the people checked or blocked there, what was read (`LeftGroup.preferences`).
        let d = UserDefaults.standard, tags = Set(g.codes.map(GroupTag.of))
        let change = LeftGroup.preferences(d.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "") ?? [:], group: g.id, tags: tags, messages: g.messages)
        for key in change.remove { d.removeObject(forKey: key) }
        for (key, value) in change.change { d.set(value, forKey: key) }
        // Alerts of the group that are still showing: each is posted under its message's id and tagged with its group.
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { shown in
            let ids = shown.filter { g.messages.contains($0.request.identifier) || tags.contains($0.request.content.userInfo["group"] as? String ?? "") }.map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }
}

/// The wait after leave notes were sent (spec/FORMAT.md, "Leaving the group", LW3 and LW4): until a phone, a radio or a
/// relay took every note ("Sent", `Engine.noteLeft`), or `LeaveNote.waitSeconds`, or the user taps "Leave now" or
/// "Erase now" (`now`). The screens show `left`. Main thread.
final class Telling: ObservableObject {
    static let shared = Telling()
    /// Seconds left while waiting; nil when nothing waits.
    @Published private(set) var left: Int?
    private var notes: [String] = []
    private var started = Date()
    private var done: ((Set<String>) -> Void)?
    private var tick: Timer?

    /// Waits for `ids`; `then` gets the ones that left this phone. With none, it ends at once.
    func wait(for ids: [String], then: @escaping (Set<String>) -> Void) {
        end() // never two waits at once
        notes = ids; done = then; started = Date(); left = LeaveNote.waitSeconds
        tick = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.check() }
        check()
    }

    /// "Leave now", "Erase now": the wait ends at once.
    func now() { end() }

    private func gone() -> Set<String> { Set(notes.filter { Mesh.shared.engine?.noteLeft($0) ?? false }) }

    private func check() {
        let elapsed = Date().timeIntervalSince(started)
        let l = max(0, LeaveNote.waitSeconds - Int(elapsed.rounded(.down)))
        if left != l { left = l }
        if gone().count == notes.count || elapsed >= Double(LeaveNote.waitSeconds) { end() }
    }

    private func end() {
        guard let then = done else { return }
        let g = gone()
        tick?.invalidate(); tick = nil; done = nil; left = nil; notes = []
        then(g)
    }
}

/// What the final screen of "Erase everything" says about each group (`Wipe.eraseEverything(lines:)`), held in memory
/// only, until the user goes on. Main thread.
final class EraseResult: ObservableObject {
    static let shared = EraseResult()
    @Published var lines: [String]?
}
