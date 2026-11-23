import SwiftUI
import LocalAuthentication
import UserNotifications
import BlackoutCore

// MARK: - Lock

/// "Lock DarkProof" (Settings), off unless turned on: the app asks for Face ID, Touch ID or the phone's passcode when it
/// opens, and again after a minute away. While it is on, alerts don't show message text and the app switcher shows a
/// blank screen. Asking for help never needs it: the lock screen has the button, and shortcuts keep working. Main thread.
/// High-risk mode (HighRisk.swift) keeps it on, and without the minute: it asks every time the app was left.
final class AppLock: ObservableObject {
    static let shared = AppLock()
    /// Always true while high-risk mode is on: whatever asks "is the lock on?" (alerts, shortcuts) then hides as much.
    @Published private(set) var enabled = UserDefaults.standard.bool(forKey: "appLock") || HighRiskSetting.isOn()
    private(set) var isLocked = false
    // When the app was left: on a clock nobody can set, which keeps counting while the phone sleeps. The phone's date
    // and time can be set back in Settings, which made the time away negative and skipped the lock.
    private var unlockedOnce = false, leftAt: ContinuousClock.Instant?
    private var window: UIWindow? // above everything, sheets included
    private var covered: [UIWindow] = [] // the app's own windows, out of VoiceOver's and touch's reach while locked
    private weak var keyBefore: UIWindow? // made key again on unlocking
    private var context: LAContext?

    /// False if the phone has no passcode: there would be nothing to ask for.
    var available: Bool { LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) }

    /// Asks for the phone's own unlock, then runs `ok`. If the phone has no passcode any more, nobody is shut out.
    /// `failed`: the unlock didn't go through, was cancelled, or another request took its place.
    func ask(_ reason: String, failed: (() -> Void)? = nil, ok: @escaping () -> Void) {
        context?.invalidate() // a second tap replaces the first request
        let c = LAContext()
        context = c
        guard c.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return ok() }
        c.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, _ in
            DispatchQueue.main.async { if success { ok() } else { failed?() } }
        }
    }

    /// The switch in Settings. It can't be turned off while high-risk mode is on (the switch says so).
    func change(_ on: Bool) {
        if !on && HighRisk.shared.isOn { return }
        if on && !available { Nav.shared.toast("Set a passcode on this iPhone first"); return }
        ask(on ? "Turn on the lock" : "Turn off the lock") { self.save(on) }
    }

    /// The switch, turned on for high-risk mode: asks for the phone's own unlock like `change`, also when the lock is on
    /// already, and tells `done` whether that went through. The lock is on afterwards only if it did.
    func turnOn(_ reason: String, then done: @escaping (Bool) -> Void) {
        ask(reason, failed: { done(false) }) {
            if !self.enabled { self.save(true) }
            done(true)
        }
    }

    private func save(_ on: Bool) {
        enabled = on; unlockedOnce = true
        UserDefaults.standard.set(on, forKey: "appLock")
        Notifier.shared.updateActions()
        if on { UNUserNotificationCenter.current().removeAllDeliveredNotifications() } // alerts already showing still have their text
        Mesh.shared.engine?.quietPass() // check-in clock alerts that wait are made again with what they may say now
    }

    /// "Erase everything": as on a fresh install, the lock is off and nothing is covered.
    func reset() {
        context?.invalidate(); context = nil
        enabled = false; isLocked = false; unlockedOnce = false; leftAt = nil
        hide()
    }

    func unlock() { ask("Unlock DarkProof") { self.unlocked() } }

    private func unlocked() {
        isLocked = false; unlockedOnce = true; leftAt = nil
        hide()
    }

    #if DEBUG
    /// What a successful unlock does, for tests: nobody types a passcode into the system's prompt.
    func unlockForTests() { unlocked() }
    #endif

    func phaseChanged(_ phase: ScenePhase) {
        if phase == .active { Wipe.finishIfWaiting() } // an "Erase everything" the app's end cut short
        // A group's timer: iOS stops the app's own timers while it is suspended, so what is due goes on coming back.
        if phase == .active { Mesh.shared.engine?.cleanUpIfTimed() }
        guard enabled else { return hide() }
        switch phase {
        case .active:
            let risk = HighRisk.shared.isOn
            // "Keeps less": iOS stops the hourly clean-up while the app is suspended, so it also runs on coming back.
            if risk { Mesh.shared.engine?.cleanUpNow() }
            if isLocked { return }
            let away = leftAt.map { Self.seconds(ContinuousClock.now - $0) }
            // High-risk mode: no minute. Left at all (`leftAt` is set in the background, cleared on unlocking), it asks.
            let asks = risk ? HighRiskLock.asksAgain(unlockedOnce: unlockedOnce, awaySeconds: away)
                            : LockGrace.asksAgain(unlockedOnce: unlockedOnce, awaySeconds: away)
            if asks { lock() } else { hide() }
        case .inactive:
            if !isLocked { show(AnyView(LockCover())) }
        case .background:
            leftAt = ContinuousClock.now
            if !isLocked { show(AnyView(LockCover())) }
        @unknown default: break
        }
    }

    private func lock() {
        isLocked = true
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) // the keyboard would stay on top
        show(AnyView(LockView()))
        // The app stays loaded under the lock: VoiceOver, Voice Control, Speak Screen and a keyboard reach only the lock.
        guard let w = window else { return }
        w.rootViewController?.view.accessibilityViewIsModal = true
        if let k = w.windowScene?.keyWindow, k !== w { keyBefore = k }
        w.makeKey()
        for other in w.windowScene?.windows ?? [] where other !== w && !covered.contains(where: { $0 === other }) {
            other.accessibilityElementsHidden = true
            other.isUserInteractionEnabled = false
            covered.append(other)
        }
        UIAccessibility.post(notification: .screenChanged, argument: w.rootViewController?.view)
    }

    private static func seconds(_ d: Duration) -> Double {
        Double(d.components.seconds) + Double(d.components.attoseconds) / 1e18
    }

    private func show(_ v: AnyView) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let w = window ?? UIWindow(windowScene: scene)
        w.windowLevel = .alert + 1
        w.rootViewController = UIHostingController(rootView: v)
        w.isHidden = false
        window = w
    }

    private func hide() {
        window?.isHidden = true; window = nil
        for w in covered { w.accessibilityElementsHidden = false; w.isUserInteractionEnabled = true }
        covered = []
        if let k = keyBefore, !k.isHidden { k.makeKey() }
        keyBefore = nil
    }
}

/// In the app switcher, and while something else is in front.
private struct LockCover: View {
    var body: some View { ZStack { Theme.bg.ignoresSafeArea(); Logo(size: 88) }.preferredColorScheme(.dark) }
}

/// Nothing of the app shows here, but asking for help doesn't need unlocking.
private struct LockView: View {
    @State private var asking = false
    @State private var sent = false

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            VStack(spacing: 16) {
                Logo(size: 88)
                Text("DarkProof is locked").scaledFont(26, .black).foregroundStyle(Theme.text).padding(.bottom, 16)
                Button("Unlock") { AppLock.shared.unlock() }.buttonStyle(PrimaryButton())
                Button("I need help") { asking = true }.buttonStyle(DangerButton())
                if sent { Text("Sent to your group.").scaledFont(16, .semibold).foregroundStyle(Theme.safe) }
            }
            .padding(24)
        }
        .preferredColorScheme(.dark)
        .alert("Send “I need help”?", isPresented: $asking) {
            Button("Send", role: .destructive) { Mesh.shared.engine?.checkIn(.help); sent = true }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Your group gets it right away. You don't need to unlock.") }
    }
}

// MARK: - Settings

/// Settings: the everyday things first, one line each, then the ways messages travel under Advanced.
/// A line with more to it opens its own page; a plain on/off switches right in the list.
struct SettingsSheet: View {
    @ObservedObject var engine: Engine
    @AppStorage("groupName") private var groupName = "My group"
    @CurrentCode private var code
    @AppStorage("name") private var myName = ""
    @AppStorage(LocationService.shareKey) private var shareLocation = false
    @AppStorage(FinderNote.settingKey) private var findable = true
    @ObservedObject private var loc = LocationService.shared
    @ObservedObject private var notifier = Notifier.shared
    @ObservedObject private var lock = AppLock.shared
    @ObservedObject private var risk = HighRisk.shared
    @State private var askingRisk = false
    @State private var askingLeave = false
    @State private var erasing = false

    var body: some View {
        SheetPage(title: "Settings") {
            RowCard {
                LinkRow(icon: "person.fill", title: "Your name", value: myName) { NamePage(engine: engine) }
                LinkRow(icon: "person.2.fill", title: "Groups", value: groupNames) { GroupsPage(engine: engine) }
                SwitchRow(icon: "location.fill", title: "Share my location",
                          detail: shareLocation && loc.denied ? "Location is off for DarkProof, so check-ins go out without it."
                                                              : "When you check in, your group sees where you are and can find you with a compass.",
                          detailColor: shareLocation && loc.denied ? Theme.help : Theme.dim,
                          isOn: Binding(get: { shareLocation }, set: { shareLocation = $0; if $0 { LocationService.shared.requestPermission() } }))
                if shareLocation && loc.denied { OpenSettingsButton().padding(.bottom, 8) }
                // Finder (spec "Finder", F2): the link id in "I need help". High-risk mode turns it off whatever this says.
                SwitchRow(icon: "dot.radiowaves.left.and.right", title: "Let my group find this phone",
                          detail: risk.isOn ? "Off in high-risk mode." : "When you send “I need help”, your group can tell which nearby phone is yours until DarkProof restarts.",
                          isOn: Binding(get: { findable && !risk.isOn }, set: { findable = $0 }))
                    .disabled(risk.isOn)
                LinkRow(icon: "bell.fill", title: "Notifications", value: notificationsValue) { NotificationsPage() }
                SwitchRow(icon: "lock.fill", title: "Lock DarkProof",
                          detail: lock.enabled && !lock.available ? "This iPhone has no passcode any more, so this lock can't protect DarkProof. Set one in the iPhone's Settings."
                              : risk.isOn ? "On because of high-risk mode"
                              : lock.enabled || lock.available
                              ? "Asks for Face ID or your passcode when you open DarkProof. Hides message text in alerts and in the app switcher. “I need help” still works while it's locked."
                              : "Set a passcode on this iPhone first.",
                          detailColor: lock.enabled && !lock.available ? Theme.help : Theme.dim,
                          isOn: Binding(get: { lock.enabled }, set: { lock.change($0) }))
                    .disabled(risk.isOn) // high-risk mode keeps it on
                ActionRow(icon: "shield.fill", title: "High-risk mode", value: risk.isOn ? "On" : "Off", on: risk.isOn) { askingRisk = true }
                LinkRow(icon: "questionmark.circle.fill", title: "Help and privacy") { HelpPrivacyPage() }
            }
            Kicker("Background").padding(.top, 8).padding(.leading, 4).id("background")
            BackgroundRows() // Backdrop.swift
            Kicker("Advanced").padding(.top, 8).padding(.leading, 4)
            AdvancedRows(engine: engine)
            Kicker("This phone").padding(.top, 8).padding(.leading, 4)
            RowCard {
                ActionRow(icon: "rectangle.portrait.and.arrow.right", title: "Leave \(groupName)") { askingLeave = true }
                ActionRow(icon: "trash.fill", title: "Erase everything", tint: Theme.help) { erasing = true }
            }
        }
        .onAppear {
            notifier.refresh()
            #if DEBUG
            // With `sheet settings`, for pictures: `askLeave` opens the leave sheet, `askErase` the erase screen.
            let args = CommandLine.arguments
            if args.contains("askLeave") { askingLeave = true } else if args.contains("askErase") { erasing = true }
            #endif
        }
        .sheet(isPresented: $askingRisk) { HighRiskSheet() }
        .sheet(isPresented: $askingLeave) { LeaveSheet(groupName: groupName, code: code) }
        .fullScreenCover(isPresented: $erasing) { EraseScreen() }
    }

    private var groupNames: String {
        ([groupName] + SavedGroups.all().filter { $0.code != code }.map(\.name)).joined(separator: ", ")
    }

    private var notificationsValue: String {
        switch notifier.status {
        case .authorized, .provisional, .ephemeral: return "On"
        case .denied: return "Off"
        default: return "Not set"
        }
    }
}

struct NamePage: View {
    @ObservedObject var engine: Engine
    @CurrentCode private var code
    @AppStorage("name") private var myName = ""
    @State private var draft = ""
    @Environment(\.dismiss) private var dismiss

    private var clean: String { String(draft.trimmingCharacters(in: .whitespacesAndNewlines).prefix(20)) }

    var body: some View {
        SettingsPage(title: "Your name") {
            InputField(label: "Your name", hint: "Shown next to your messages and check-ins.") {
                TextField("", text: $draft).submitLabel(.done).onSubmit(save)
            }
            Button("Save") { save() }.buttonStyle(PrimaryButton()).disabled(clean.isEmpty || clean == myName)
        }
        .onAppear { draft = myName }
    }

    private func save() {
        guard !clean.isEmpty, clean != myName else { return }
        myName = clean
        engine.rejoin(name: clean, code: code)
        Nav.shared.toast("Saved")
        dismiss()
    }
}

struct GroupsPage: View {
    @ObservedObject var engine: Engine
    @AppStorage("groupName") private var groupName = "My group"
    @CurrentCode private var code
    @State private var draft = ""

    private var clean: String { String(draft.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Invite.maxGroupName)) }

    var body: some View {
        SettingsPage(title: "Groups") {
            GroupsCard(engine: engine)
            Card {
                InputField(label: "Name of this group", hint: "Only on this phone. Each person can name it as they like.") {
                    TextField("", text: $draft, prompt: Text(groupName).foregroundColor(Theme.faint)).submitLabel(.done).onSubmit(rename)
                }
                Button("Rename") { rename() }.buttonStyle(GhostButton()).disabled(clean.isEmpty || clean == groupName)
            }
            Card {
                Text("Check your group").scaledFont(17, .bold).foregroundStyle(Theme.text)
                FingerprintView(code: code)
            }
            if let pub = engine.myPublicKey(code) {
                let ix = MemberKey.emoji(pub)
                Card {
                    Text("Your emoji in \(groupName)").scaledFont(17, .bold).foregroundStyle(Theme.text)
                    Text(Fingerprint.emojiLines(ix)).scaledFont(30)
                    Text(Fingerprint.spoken(ix)).scaledFont(13).foregroundStyle(Theme.dim)
                    Text("When someone checks you, read them these. If their phone shows the same, it's really you writing.")
                        .scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func rename() {
        guard !clean.isEmpty, clean != groupName else { return }
        groupName = clean
        SavedGroups.remember(.init(name: clean, code: code))
        draft = ""
        Nav.shared.toast("Renamed")
    }
}

struct NotificationsPage: View {
    @ObservedObject private var notifier = Notifier.shared

    var body: some View {
        SettingsPage(title: "Notifications") {
            Card {
                HStack {
                    Text("Notifications").scaledFont(17, .bold).foregroundStyle(Theme.text)
                    Spacer()
                    switch notifier.status {
                    case .authorized, .provisional, .ephemeral: Chip("On", Theme.safe)
                    case .denied: Chip("Off")
                    default: EmptyView()
                    }
                }
                Text("Alerts when your groups write or check in, and always when someone needs help. In the background iOS keeps only Bluetooth running, so alerts then come from phones nearby. Made on your phone: no internet needed.")
                    .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                if notifier.status == .notDetermined {
                    Button("Turn on notifications") { notifier.requestPermission() }.buttonStyle(GhostButton())
                } else if notifier.status == .denied {
                    OpenSettingsButton()
                }
            }
        }
        .onAppear { notifier.refresh() }
    }
}

struct HelpPrivacyPage: View {
    var body: some View {
        SettingsPage(title: "Help and privacy") {
            Card {
                Text("Practice and shortcuts").scaledFont(17, .bold).foregroundStyle(Theme.text)
                HStack(spacing: 10) {
                    Button("Practice asking for help") { open(.practice) }.buttonStyle(GhostButton())
                    Button("Shortcut") { open(.shortcut) }.buttonStyle(GhostButton())
                }
                Button("Show the Get ready list") { UserDefaults.standard.set(false, forKey: "hideGetReady"); Nav.shared.toast("It's on Home") }
                    .buttonStyle(GhostButton())
            }
            Card {
                Text("Help and safety").scaledFont(17, .bold).foregroundStyle(Theme.text)
                Text("In Everyone, hold a message to report it or block its sender. To report abuse or a problem, write to \(Contact.email).")
                    .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 10) {
                    Button("Email us") { UIApplication.shared.open(Contact.mail) }.buttonStyle(GhostButton())
                    Button("Privacy policy") { UIApplication.shared.open(Contact.privacy) }.buttonStyle(GhostButton())
                }
                // On its own line: three in a row do not fit a narrow phone.
                Button("Terms of use") { UIApplication.shared.open(Contact.terms) }.buttonStyle(GhostButton())
            }
        }
    }

    /// Closes Settings, then opens the other sheet once this one is gone.
    private func open(_ s: MainView.Sheet) {
        Nav.shared.sheet = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { Nav.shared.sheet = s }
    }
}

// MARK: - Rows

/// Settings lines on one charcoal card.
struct RowCard<Content: View>: View {
    private let content: Content
    init(@ViewBuilder _ content: () -> Content) { self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Icon, title and an optional line under it.
struct RowLabel: View {
    let icon: String
    let title: String
    var detail: String? = nil
    var detailColor = Theme.dim

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).scaledFont(17, .semibold).foregroundStyle(Theme.text).frame(width: 30).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(17, .semibold).foregroundStyle(Theme.text)
                if let detail, !detail.isEmpty {
                    Text(detail).scaledFont(14).foregroundStyle(detailColor).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// A line that opens a page: the current value on the right, then a chevron.
struct LinkRow<Destination: View>: View {
    let icon: String
    let title: String
    var value: String? = nil
    var detail: String? = nil
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink { destination() } label: {
            HStack(spacing: 8) {
                RowLabel(icon: icon, title: title, detail: detail)
                Spacer(minLength: 8)
                if let value, !value.isEmpty {
                    Text(value).scaledFont(15).foregroundStyle(Theme.dim).lineLimit(1)
                }
                Image(systemName: "chevron.right").scaledFont(14, .semibold).foregroundStyle(Theme.decor).accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A line that opens a question or does something: like `LinkRow`, without a page behind it. `on`: a green dot before
/// the value. `tint`: red for the one thing that can't be undone.
struct ActionRow: View {
    let icon: String
    let title: String
    var value: String? = nil
    var on = false
    var tint = Theme.text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                HStack(spacing: 14) {
                    Image(systemName: icon).scaledFont(17, .semibold).foregroundStyle(tint).frame(width: 30).accessibilityHidden(true)
                    Text(title).scaledFont(17, .semibold).foregroundStyle(tint).lineLimit(2).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                if on { Circle().fill(Theme.safe).frame(width: 8, height: 8).accessibilityHidden(true) }
                if let value, !value.isEmpty {
                    Text(value).scaledFont(15).foregroundStyle(on ? Theme.safe : Theme.dim).lineLimit(1)
                }
                Image(systemName: "chevron.right").scaledFont(14, .semibold).foregroundStyle(Theme.decor).accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// An on/off line, with what it does (or what is happening) under the title.
struct SwitchRow: View {
    let icon: String
    let title: String
    var detail: String? = nil
    var detailColor = Theme.dim
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) { RowLabel(icon: icon, title: title, detail: detail, detailColor: detailColor) }
            .tint(Theme.safe)
            .frame(minHeight: 44)
            .padding(.vertical, 8)
    }
}

/// A page opened from a Settings line: same look as SheetPage, with the back button.
struct SettingsPage<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) { content() }.padding(20) }
            .scrollDismissesKeyboard(.interactively)
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Theme.bg, for: .navigationBar)
    }
}
