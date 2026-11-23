import AppIntents
import BlackoutCore

/// "I'm safe" and "I need help" for Siri, Spotlight, the Action button and the Shortcuts app. They run without opening
/// DarkProof: the mesh starts if it isn't running and the check-in is queued like one sent from the app, then goes out
/// over whatever link is in reach. (A Control Center or Lock Screen control would need a widget extension: not added.)
/// "I'm safe" asks for the phone's unlock first (`SafeAnswer.needsUnlock`): it stops the check-in clock on every phone and
/// answers "Are you safe?", so whoever holds a locked phone can't say it through Siri or the Action button. "I need
/// help" works from a locked phone, by design.
struct SafeIntent: AppIntent {
    static var title: LocalizedStringResource = "I'm safe"
    static var description = IntentDescription("Tells your DarkProof group you're safe, with your battery level and, if you share it, your position.")
    static var openAppWhenRun = false
    static var authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication // `SafeAnswer.needsUnlock(.safe)`; a constant for the intents' metadata

    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: IntentDialog(stringLiteral: await CheckInShortcut.send(.safe)))
    }
}

struct HelpIntent: AppIntent {
    static var title: LocalizedStringResource = "I need help"
    static var description = IntentDescription("Tells your DarkProof group you need help, with your battery level and, if you share it, your position.")
    static var openAppWhenRun = false

    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: IntentDialog(stringLiteral: await CheckInShortcut.send(.help)))
    }
}

enum CheckInShortcut {
    /// Queues the check-in in the group on screen and says what happened. Main thread.
    /// "I'm safe" waits, like an alert's answer (`CheckInWait`), until a phone, a radio or a relay took it, at most
    /// `SafeAnswer.waitSeconds`: iOS may suspend DarkProof as soon as the intent returns, and the result must not say
    /// "Sent" for a check-in that still waits here (it already stopped the clock on this phone). Otherwise it says it
    /// waits on this phone, or that it wasn't sent (`SafeAnswer.said`).
    @MainActor static func send(_ c: CheckIn) async -> String {
        Mesh.shared.startIfSetUp()
        guard let e = Mesh.shared.engine else { return c == .safe ? SafeAnswer.line(.notSent, .safe)! : "Open DarkProof and join a group first." }
        // With "Lock DarkProof" on, whoever holds the phone can't tell the group you're safe without unlocking.
        if c == .safe && AppLock.shared.enabled { return "Open DarkProof and unlock it to say you're safe." }
        let group = AppLock.shared.enabled ? "your group" : UserDefaults.standard.string(forKey: "groupName") ?? "your group"
        if c == .safe {
            let o = await withCheckedContinuation { (ended: CheckedContinuation<SafeAnswer.Outcome, Never>) in
                CheckInWait.send(.safe, e, group: nil) { ended.resume(returning: $0) }
            }
            return SafeAnswer.said(o, .safe, group: group)
        }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            if e.checkInSending != nil { return done.resume() } // one is on its way already
            e.checkIn(c) { done.resume() }
        }
        let what = "“I need help”"
        return e.reach == .none ? "\(what) for \(group) waits on this iPhone and goes out as soon as a phone is in reach."
                                : "Sent \(what) to \(group)."
    }
}

struct DarkProofShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SafeIntent(), phrases: ["I'm safe in \(.applicationName)", "Tell \(.applicationName) I'm safe",
                                                    "\(.applicationName) I'm safe"],
                    shortTitle: "I'm safe", systemImageName: "checkmark.shield.fill")
        AppShortcut(intent: HelpIntent(), phrases: ["I need help in \(.applicationName)", "Tell \(.applicationName) I need help",
                                                    "\(.applicationName) I need help"],
                    shortTitle: "I need help", systemImageName: "sos")
    }
}
