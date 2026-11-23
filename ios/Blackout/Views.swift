import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import UserNotifications
import BlackoutCore

struct RootView: View {
    @AppStorage("name") private var name = ""
    @CurrentCode private var code
    @AppStorage("groupName") private var groupName = "My group"
    @ObservedObject private var mesh = Mesh.shared
    @ObservedObject private var erased = EraseResult.shared // "DarkProof is empty on this phone", once, after "Erase everything"
    private var engine: Engine? { mesh.engine }
    @State private var invite: PendingInvite?   // from an invite link, before setup
    @State private var linkInvite: PendingInvite? // from an invite link, once in a group: asks before switching
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Group {
            if let engine {
                MainView(engine: engine)
                    .sheet(item: $linkInvite) { i in JoinGroupSheet(engine: engine, prefill: i) }
            } else if code.isEmpty {
                SetupView(invite: invite) { n, g, c, created in
                    SavedGroups.remember(.init(name: g, code: c))
                    name = n; groupName = g; code = c
                    Nav.shared.inviteAfterSetup = created
                    mesh.start(name: n, code: c)
                    invite = nil
                }
                .modifier(ToastOverlay()) // "You left <group>", when the last group was left (Settings)
            } else {
                Theme.bg.ignoresSafeArea().onAppear { mesh.startIfSetUp() }
            }
        }
        .overlay { if let lines = erased.lines, engine == nil { EmptyScreen(lines: lines, left: erased.leftBehind) { erased.lines = nil; erased.leftBehind = false } } }
        .tint(Theme.text)
        .onAppear { if !code.isEmpty { AppLock.shared.phaseChanged(.active) } }
        .onChange(of: phase) { AppLock.shared.phaseChanged($0) }
        .onOpenURL { url in
            guard let i = InviteLinks.read(url.absoluteString) else { return }
            if code.isEmpty { invite = i; return }
            if i.code == code { Nav.shared.toast("You're already in \(groupName)"); return }
            linkInvite = i // already in a group: ask first
        }
    }
}

/// An invite link that was read: the group's name and code, and the admins it names (`pins`), kept only once the user joins with it.
struct PendingInvite: Equatable, Identifiable { let name: String; let code: String; var pins = ""; var id: String { code } }

// MARK: - Setup

struct SetupView: View {
    var invite: PendingInvite?
    /// name, group name, group code, and whether the group was just created
    let done: (String, String, String, Bool) -> Void
    enum Mode { case welcome, create, join, ready }
    @State private var mode = Mode.welcome
    @State private var before = Mode.welcome // where "back" goes from the last step
    @State private var name = UserDefaults.standard.string(forKey: "name") ?? "" // kept when the last group was left (Settings)
    @State private var groupName = ""
    @State private var code = ""
    @State private var result: (String, String, String, Bool)?
    @State private var invited: PendingInvite? // the invite link the code in the join form came from
    @State private var pins = ""               // the admins named by the invite the user is joining with
    @FocusState private var focus: Field?
    @FocusState private var codeFocus: Bool
    enum Field { case name, group }

    private func clean(_ s: String, _ max: Int) -> String { String(s.trimmingCharacters(in: .whitespacesAndNewlines).prefix(max)) }
    private var cleanName: String { clean(name, 20) }
    private var cleanCode: String { Invite.normalizeCode(code) }

    var body: some View {
        Group {
            if mode == .ready {
                ready
            } else if let invite {
                form(title: "You're invited", subtitle: "Join \(invite.name) to message each other without internet.", back: false) {
                    nameField
                    Button("Join \(invite.name)") { next(cleanName, invite.name, invite.code, false, pins: invite.pins) }
                        .buttonStyle(PrimaryButton()).disabled(cleanName.isEmpty)
                }
            } else {
                switch mode {
                case .welcome, .ready: welcome
                case .create: create
                case .join: join
                }
            }
        }
        .background(Theme.bg.ignoresSafeArea())
    }

    /// Before the last step: what DarkProof will ask for, so the questions that follow make sense.
    private func next(_ n: String, _ g: String, _ c: String, _ created: Bool, pins named: String = "") {
        focus = nil; codeFocus = false
        result = (n, g, c, created); pins = named
        before = mode
        withAnimation(.easeOut(duration: 0.2)) { mode = .ready }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()
            Logo(size: 150).frame(maxWidth: .infinity)
            Spacer()
            Text("Stay connected when everything goes dark.")
                .scaledFont(40, .heavy).tracking(-1).foregroundStyle(Theme.text)
                .fixedSize(horizontal: false, vertical: true)
            Text("Messages travel phone to phone over Bluetooth and Wi-Fi, and over the internet when there is some. No account.")
                .scaledFont(17).foregroundStyle(Theme.dim).padding(.top, 14)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 12) {
                Button { mode = .create } label: { Label("Create a group", systemImage: "plus") }.buttonStyle(PrimaryButton())
                Button { mode = .join } label: { Label("Join a group", systemImage: "qrcode.viewfinder") }.buttonStyle(SecondaryButton())
            }
            .padding(.top, 36)
            Text("Free and private. No servers of ours.").font(.footnote).foregroundStyle(Theme.faint)
                .frame(maxWidth: .infinity).padding(.top, 14)
        }
        .padding(.horizontal, 24).padding(.bottom, 12)
    }

    private var create: some View {
        form(title: "Create a group", subtitle: "For your family, friends or building. DarkProof makes a secret code for it, then you invite people with a QR code.") {
            nameField
            InputField(label: "Group name (optional)", hint: "You can change it later.", active: focus == .group, tap: { focus = .group }) {
                TextField("", text: $groupName, prompt: Text(SavedGroups.defaultName(for: cleanName)).foregroundColor(Theme.faint))
                    .focused($focus, equals: .group).submitLabel(.done)
            }
            Button("Create group") {
                let g = clean(groupName, Invite.maxGroupName)
                let code = Invite.newCode()
                Engine.found(code) // its first epoch is made on this phone (v0.6)
                next(cleanName, g.isEmpty ? SavedGroups.defaultName(for: cleanName) : g, code, true)
            }
            .buttonStyle(PrimaryButton()).disabled(cleanName.isEmpty)
        }
    }

    private var join: some View {
        form(title: "Join a group", subtitle: "Scan your friend's invite QR code, or point the iPhone Camera at it. Or type their code.") {
            nameField
            CodeEntry(code: $code, groupName: $groupName, invited: $invited, focusCode: $codeFocus)
            Button("Join") {
                let g = clean(groupName, Invite.maxGroupName)
                next(cleanName, g.isEmpty ? "Group" : g, cleanCode, false, pins: InviteLinks.pins(invited, for: cleanCode))
            }
            .buttonStyle(PrimaryButton()).disabled(cleanName.isEmpty || !Invite.isGeneratedCode(cleanCode))
            if cleanName.isEmpty && !code.isEmpty {
                Text("Enter your name.").font(.footnote).foregroundStyle(Theme.dim)
            }
        }
    }

    private var ready: some View {
        form(title: "Almost done", subtitle: "DarkProof will ask for Bluetooth, Local Network and notifications so phones can find each other and you get alerts.",
             backTo: before) {
            Card {
                permission("dot.radiowaves.left.and.right", "Bluetooth", "Finds phones with DarkProof nearby, with no internet.")
                permission("wifi", "Local Network", "Connects over Wi-Fi, even a router or hotspot with no internet.")
                permission("bell.fill", "Notifications", "Tells you when your group writes or someone needs help.")
            }
            Text("Please tap Allow each time. You can change this later in Settings.").scaledFont(15).foregroundStyle(Theme.dim)
            Button("Continue") { if let r = result { GroupSecrets.shared.joining(r.2, pins: pins); done(r.0, r.1, r.2, r.3) } }.buttonStyle(PrimaryButton())
        }
    }

    private func permission(_ icon: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon).scaledFont(18, .semibold).foregroundStyle(Theme.text)
                .frame(width: 40, height: 40).background(Circle().fill(Theme.tile))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(17, .semibold).foregroundStyle(Theme.text)
                Text(text).scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var nameField: some View {
        InputField(label: "Your name", hint: "Shown next to your messages.", active: focus == .name, tap: { focus = .name }) {
            TextField("", text: $name, prompt: Text("Anna").foregroundColor(Theme.faint))
                .focused($focus, equals: .name).submitLabel(.done).textContentType(.givenName)
        }
    }

    private func form<C: View>(title: String, subtitle: String, back: Bool = true, backTo: Mode = .welcome,
                               @ViewBuilder _ content: () -> C) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if back {
                    Button { focus = nil; codeFocus = false; withAnimation(.easeOut(duration: 0.2)) { mode = backTo } } label: {
                        RoundIcon(symbol: "chevron.left")
                    }
                    .accessibilityLabel("Back")
                } else {
                    Logo(size: 44)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(title).scaledFont(34, .heavy).tracking(-0.5).foregroundStyle(Theme.text)
                    Text(subtitle).scaledFont(17).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
                content()
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
    }
}

/// Labeled text field; [active] outlines it while it has focus. VoiceOver reads the label and hint with the field.
struct InputField<Field: View>: View {
    let label: String
    var hint = ""
    var active = false
    var tap: () -> Void = {} // focuses the field when the box around the text is tapped
    @ViewBuilder let field: () -> Field

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Kicker(label).accessibilityHidden(true)
            field()
                .scaledFont(17).foregroundStyle(Theme.text).tint(Theme.text)
                .padding(.horizontal, 18).frame(minHeight: 54)
                .background(Theme.tile, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(active ? Theme.text.opacity(0.6) : .clear, lineWidth: 1.5))
                .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .onTapGesture(perform: tap)
                .accessibilityLabel(label)
                .accessibilityHint(hint)
            if !hint.isEmpty { Text(hint).font(.footnote).foregroundStyle(Theme.dim).accessibilityHidden(true) }
        }
    }
}

// MARK: - Main

struct MainView: View {
    enum Sheet: String, Identifiable { case invite, settings, radio, groups, help, practice, askSafe, vouch, shortcut, models, security, timer, quiet, finder; var id: String { rawValue } }
    @ObservedObject var engine: Engine
    @ObservedObject private var nav = Nav.shared
    @ObservedObject private var unread = Unread.shared
    @ObservedObject private var notifier = Notifier.shared
    @AppStorage("groupName") private var groupName = "My group"
    @CurrentCode private var code
    @AppStorage("askedNotifications") private var askedNotifications = false
    @State private var asking = false
    @State private var keyboardUp = false // the reach line hides under the keyboard

    var body: some View {
        TabView(selection: $nav.tab) {
            HomeView(engine: engine)
                .tabItem { Label("Home", systemImage: "house.fill") }.tag(Nav.Tab.home)
            ChatView(engine: engine)
                .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right.fill") }.tag(Nav.Tab.chat)
                .badge(unread.count(code, in: engine.allMessages, me: engine.deviceId))
            PlacesView(engine: engine).tabItem { Label("Places", systemImage: "mappin.and.ellipse") }.tag(Nav.Tab.places)
            PeopleView(engine: engine).tabItem { Label("People", systemImage: "person.2.fill") }.tag(Nav.Tab.people)
            AiView(engine: engine).tabItem { Label("Ask", systemImage: "sparkles") }.tag(Nav.Tab.ask)
        }
        .tint(Theme.text)
        .reachAccessory(engine, shown: nav.sheet == nil && !keyboardUp) // above the tab bar: how far messages reach (ReachLine.swift)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardUp = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardUp = false }
        .modifier(Overlays())
        .sheet(item: $nav.sheet) { s in
            switch s {
            case .invite: InviteSheet(groupName: groupName, code: code)
            case .settings: SettingsSheet(engine: engine)
            case .radio: ConnectionsView(engine: engine)
            case .groups: GroupSwitcher(engine: engine)
            case .help: HelpSheet(engine: engine)
            case .practice: PracticeSheet()
            case .askSafe: AskSafeSheet(engine: engine)
            case .vouch: VouchSheet(engine: engine)
            case .shortcut: ShortcutSheet()
            case .models: ModelPicker(ai: LocalAi.shared)
            case .security: GroupSecuritySheet(engine: engine)
            case .timer: TimerSheet(engine: engine)
            case .quiet: QuietView(engine: engine)
            case .finder: FinderSheet(engine: engine, person: FinderDemo.person(), code: code) // debug builds only (`sheet finder`)
            }
        }
        .onAppear { firstRun() }
        .onChange(of: engine.networkStarted) { _ in firstRun() }
    }

    /// Right after setup, iOS asks one thing at a time: Bluetooth (Engine), then Local Network once the Wi-Fi links
    /// start, then notifications here, once those questions are answered; the invite opens last.
    private func firstRun() {
        if askedNotifications {
            if nav.inviteAfterSetup { nav.inviteAfterSetup = false; nav.sheet = .invite }
            return
        }
        guard engine.networkStarted, !asking else { return }
        asking = true
        whenNoSystemQuestion(after: 2) {
            askedNotifications = true
            notifier.requestPermission {
                if nav.inviteAfterSetup { nav.inviteAfterSetup = false; nav.sheet = .invite }
            }
        }
    }

    /// Waits until no system question is on screen (the app is active again), at most a minute.
    private func whenNoSystemQuestion(after s: Double, tries: Int = 120, _ then: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + s) {
            if UIApplication.shared.applicationState == .active || tries <= 0 { then() }
            else { whenNoSystemQuestion(after: 0.5, tries: tries - 1, then) }
        }
    }
}

// MARK: - Home

struct HomeView: View {
    @ObservedObject var engine: Engine
    @ObservedObject private var nav = Nav.shared
    @ObservedObject private var battery = BatteryMonitor.shared
    @ObservedObject private var unread = Unread.shared
    @AppStorage("groupName") private var groupName = "My group"
    @CurrentCode private var code
    @State private var details = false
    @State private var safeSent = 0 // "I'm safe" sent from the tile: each one plays the sent moment once

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { t in
            let wrote = Person.all(in: engine.messages, me: engine.deviceId)
            let people = wrote + engine.silentMembers(code, besides: wrote) // members who haven't written yet come last, as in People
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    header.backdropHeader(above: 8)
                    SetupProblemCard(engine: engine)
                    hero
                    HomeSecurityRow(engine: engine)
                    if engine.reach == .none && engine.setupProblem == nil { ConnectionHelp(engine: engine) }
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 10) {
                            QuickAction(symbol: "checkmark.shield.fill", label: engine.checkInSending == .safe ? "Sending…" : "I'm safe", tint: Theme.safe,
                                        sent: safeSent) {
                                let e = engine
                                let taken = e.checkIn(.safe, haptic: false) { nav.toast(e.queuedToast("“I'm safe”")) }
                                if taken { safeSent += 1 } // the sent moment and its haptic (SafeSent.swift)
                            }
                            .disabled(engine.checkInSending != nil)
                            QuickAction(symbol: "sos", label: engine.checkInSending == .help ? "Sending…" : "Need help", tint: Theme.help) { nav.sheet = .help }
                                .disabled(engine.checkInSending != nil)
                            QuickAction(symbol: "qrcode", label: "Invite", tint: Theme.text) { nav.sheet = .invite }
                            QuickAction(symbol: "antenna.radiowaves.left.and.right", label: "Connect", tint: Theme.text) { nav.sheet = .radio }
                        }
                        LastCheckIn(engine: engine, now: t.date).padding(.horizontal, 4)
                        if let h = battery.hoursLeft {
                            Label("Battery: \(BatteryMonitor.text(h)) at this pace", systemImage: h < 4 ? "battery.25percent" : "battery.75percent")
                                .scaledFont(14).foregroundStyle(h < 4 ? Theme.help : Theme.dim).padding(.horizontal, 4)
                        }
                    }
                    HomeQuietRow(engine: engine, now: t.date) // under the check-in buttons, as on the approved board
                    GetReadyCard(engine: engine, hasPeople: !people.isEmpty)
                    SectionTitle(title: "Your people", action: people.count > 3 ? "See all" : nil) { nav.tab = .people }
                    if people.isEmpty {
                        NoPeopleCard(groupName: groupName) { nav.sheet = .invite }
                    } else {
                        PeopleList(people: Array(people.prefix(3)), engine: engine, now: t.date)
                    }
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 24)
            }
            .tabBackdrop()
        }
        .sensoryFeedback(.success, trigger: safeSent) // the sent moment's one haptic, also when calm
    }

    /// Other saved groups with something new: a dot next to the group name.
    private var othersNeedLook: Bool {
        SavedGroups.all().contains { g in g.code != code && unread.count(g.code, in: engine.allMessages, me: engine.deviceId) > 0 }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Logo(size: 30)
            Button { nav.sheet = .groups } label: {
                HStack(spacing: 5) {
                    Text(groupName).scaledFont(22, .bold).lineLimit(1)
                    Image(systemName: "chevron.down").scaledFont(13, .bold).foregroundStyle(Theme.dim)
                    if othersNeedLook { Circle().fill(Theme.text).frame(width: 8, height: 8).accessibilityHidden(true) }
                }
                .foregroundStyle(Theme.text)
            }
            .accessibilityLabel("Group: \(groupName)" + (othersNeedLook ? ", other groups have new messages" : ""))
            .accessibilityHint("Switch, join or create a group")
            Spacer()
            Button { nav.sheet = .settings } label: { RoundIcon(symbol: "gearshape.fill") }.accessibilityLabel("Settings")
        }
    }

    private var linkSummary: String {
        var via: [String] = []
        if engine.bluetoothLinks > 0 { via.append("Bluetooth") }
        if engine.wifiLinks > 0 { via.append("Wi-Fi") }
        if engine.radioLinked || engine.meshcoreLinked { via.append("LoRa radio") }
        let how = via.isEmpty ? "" : "Over \(ListFormatter.localizedString(byJoining: via)). "
        return how + "Your messages hop from phone to phone." + (engine.relaysLinked > 0 ? " Also online." : "")
    }

    private var hero: some View {
        let r = engine.reach
        let on = r != .none
        let label: String = {
            switch r {
            case .phones: return "Connected"
            case .online(let n): return "Internet · \(n) \(n == 1 ? "relay" : "relays")"
            case .radio: return "LoRa radio linked"
            case .none: return "Looking for phones"
            }
        }()
        // The number and its texts are one button (it shows the details); the dials under them are buttons of their own.
        let summary = Button { withAnimation(.easeOut(duration: 0.2)) { details.toggle() } } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Circle().fill(on ? Theme.safe : Theme.dim).frame(width: 9, height: 9)
                    Text(label).scaledFont(15, .semibold).foregroundStyle(on ? Theme.safe : Theme.dim)
                    Spacer()
                    Image(systemName: "chevron.down").scaledFont(13, .bold).foregroundStyle(Theme.decor)
                        .rotationEffect(.degrees(details ? 180 : 0))
                }
                switch r {
                case .online:
                    Text("Online").scaledFont(64, .heavy).tracking(-2).foregroundStyle(Theme.text).padding(.vertical, 6)
                    Text("Online · your messages also travel over the internet. No phone in reach yet: they hop phone to phone once one is.")
                        .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                case .radio:
                    Text("LoRa").scaledFont(64, .heavy).tracking(-2).foregroundStyle(Theme.text).padding(.vertical, 6)
                    Text("Short messages reach other radios kilometers away. No phone in reach yet.")
                        .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                default:
                    HStack(alignment: .lastTextBaseline, spacing: 12) {
                        Text("\(engine.phones)").scaledFont(84, .heavy).tracking(-3).foregroundStyle(Theme.text)
                        Text(engine.phones == 1 ? "phone\nin reach" : "phones\nin reach")
                            .scaledFont(21, .bold).foregroundStyle(Theme.dim).padding(.bottom, 12)
                    }
                    Text(r == .none ? "Keep DarkProof open. Your messages wait and go out as soon as a phone is in reach." : linkSummary)
                        .scaledFont(15).foregroundStyle(Theme.dim).multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if details {
                    VStack(alignment: .leading, spacing: 4) { ForEach(engine.friendlyStatus, id: \.self) { Text($0) } }
                        .scaledFont(14).foregroundStyle(Theme.dim).padding(.top, 10)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(details ? "Hides connection details" : "Shows connection details")
        return VStack(alignment: .leading, spacing: 20) {
            summary
            LinkDials(engine: engine).padding(.horizontal, -8) // four dials side by side need most of the card's width
        }
        .padding(24).frame(maxWidth: .infinity, alignment: .leading)
        .background(VaultShape().fill(Theme.card))
    }
}

/// One of the four square shortcuts on Home.
struct QuickAction: View {
    let symbol: String
    let label: String
    let tint: Color
    /// "I'm safe" only: how many were sent from here. Each one plays the sent moment (SafeSent.swift).
    var sent: Int? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                if let sent {
                    SafeCheck(sent: sent).scaledFont(20, .semibold).foregroundStyle(tint)
                        .frame(width: 44, height: 44).background(Circle().fill(tint.opacity(0.14)))
                        .safeRing(Circle(), grow: 0.8, sent: sent)
                } else {
                    Image(systemName: symbol).scaledFont(20, .semibold).foregroundStyle(tint)
                        .frame(width: 44, height: 44).background(Circle().fill(tint.opacity(0.14)))
                }
                Text(label).scaledFont(13, .semibold).foregroundStyle(Theme.text).lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 16)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
    }
}

/// Shrinks a custom-drawn button a little while pressed; dims it while disabled.
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Styled(configuration: configuration) }
    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isEnabled) private var enabled
        var body: some View {
            configuration.label.scaleEffect(configuration.isPressed ? 0.96 : 1)
                .opacity(!enabled ? 0.5 : configuration.isPressed ? 0.85 : 1)
                .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
        }
    }
}

// MARK: - Chat

/// Message times in the user's own format ("14:05" or "2:05 PM"); older messages get a day.
enum ChatTime {
    static let time: DateFormatter = { let f = DateFormatter(); f.dateStyle = .none; f.timeStyle = .short; return f }()
    private static let day: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("EEEEdMMMM"); return f }()
    private static let dayYear: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("EEEEdMMMMy"); return f }()
    private static let shortDay: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("dMMM"); return f }()

    static func date(_ ts: Int64) -> Date { Date(timeIntervalSince1970: Double(ts) / 1000) }

    /// "14:05" today, "Yesterday, 14:05", "3 Nov, 14:05" before.
    static func stamp(_ ts: Int64, now: Date = Date()) -> String {
        let d = date(ts), cal = Calendar.current, t = time.string(from: d)
        if cal.isDate(d, inSameDayAs: now) { return t }
        if cal.isDateInYesterday(d) { return "Yesterday, \(t)" }
        return "\(shortDay.string(from: d)), \(t)"
    }

    /// Day separator: "Today", "Yesterday", "Monday 3 November".
    static func dayTitle(_ ts: Int64, now: Date = Date()) -> String {
        let d = date(ts), cal = Calendar.current
        if cal.isDate(d, inSameDayAs: now) { return "Today" }
        if cal.isDateInYesterday(d) { return "Yesterday" }
        return cal.isDate(d, equalTo: now, toGranularity: .year) ? day.string(from: d) : dayYear.string(from: d)
    }

    static func sameDay(_ a: Int64, _ b: Int64) -> Bool { Calendar.current.isDate(date(a), inSameDayAs: date(b)) }
}

struct ChatView: View {
    @ObservedObject var engine: Engine
    @ObservedObject private var nav = Nav.shared
    @ObservedObject private var drafts = Drafts.shared
    @ObservedObject private var unread = Unread.shared
    @ObservedObject private var risk = HighRisk.shared // "Keeps less": the line under the title says so
    @State private var tooLong = false
    @State private var journey: JourneyItem?        // "How it got there", for one of the user's own group messages
    @State private var replyingTo: String?          // the row being answered: the strip above the composer
    @State private var jumpTo: String?              // a quote was tapped: the row to scroll to
    @State private var flashed: String?             // that row, lit for a moment
    @FocusState private var typing: Bool            // the message field: "Reply" puts the cursor there
    @State private var findingPlace: SharedPlace?
    @State private var photoItem: PhotosPickerItem?
    @State private var choosingPhoto = false
    @State private var takingPhoto = false
    @State private var pending: PendingPhoto?
    @State private var viewing: ViewedPhoto?
    @State private var blocking: Message?
    @State private var photoFailed = false
    @State private var atBottom = true
    @State private var newBelow = 0
    @State private var showingRequests = false      // the list behind "3 people ask to join"
    @State private var showingTray = false          // the "+" tray
    @State private var trayChoice: ChatTray.Choice? // picked in the tray, done once the tray has closed
    @State private var safeSent = 0                 // "I'm safe" sent from the tray: each one plays the sent moment once
    @State private var trayLeaving = false          // the tray stays for that moment, then closes
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var voice = VoiceComposer() // the mic in the composer: recording, and the questions before it
    @ObservedObject private var loc = LocationService.shared
    @ObservedObject private var notifier = Notifier.shared // whether alerts are allowed: a clock card says what this phone can do
    @AppStorage("groupName") private var groupName = "My group"
    @CurrentCode private var code
    @AppStorage("name") private var myName = ""
    @Environment(\.scenePhase) private var phase
    @State private var arrivals = Arrivals()       // which rows came while the chat was on screen: they fade and rise in

    private var toPublic: Bool { nav.chatEveryone }
    private var channel: String { toPublic ? Unread.everyone : code }
    private var shown: [Message] { engine.messages.filter { $0.isPublic == toPublic } }

    /// The row the user is answering, while it is still shown in this group's chat.
    private var replyTarget: Message? {
        guard !toPublic, let id = replyingTo else { return nil }
        return engine.messages.first { $0.id == id && !$0.isPublic && $0.group == code }
    }

    /// The quote above a reply (spec "Replies"): from this phone's own copy of the target, found by what the reply
    /// names. Nothing is quoted from the reply itself, and nothing while the reply isn't checked.
    private func quote(of m: Message, _ targets: [String: Message]) -> QuoteLine? {
        guard !m.isPublic, m.verified, case .reply(let t)? = m.about else { return nil }
        if let target = targets[t.key] { return QuoteLine(name: Quote.name(of: target, me: engine.deviceId), text: Quote.line(of: target), row: target.id) }
        return QuoteLine(name: nil, text: engine.hiddenTargets.contains(t.key) ? Quote.hidden : Quote.missing)
    }

    /// Starts a reply to `m` (the held menu's "Reply", a swipe, VoiceOver's "Reply"): the strip above the composer, then the
    /// cursor in the field `after` seconds later.
    private func startReply(_ m: Message, after: Double = 0) {
        replyingTo = m.id
        DispatchQueue.main.asyncAfter(deadline: .now() + after) { if replyingTo == m.id { typing = true } }
    }

    private func react(_ m: Message, _ emoji: String) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        engine.react(to: m, emoji)
    }

    /// What holding a message offers. A group message: the reactions in one row, Reply, Copy, for the user's own
    /// "How it got there", then what was there before, last. An Everyone message keeps the menu it had.
    @ViewBuilder private func messageMenu(_ m: Message, mine: Bool, canAnswer: Bool) -> some View {
        if !m.isPublic {
            if canAnswer {
                ControlGroup {
                    ForEach(Reaction.offered, id: \.self) { e in
                        Button { react(m, e) } label: { Text(e) }.accessibilityLabel("React with \(e)")
                    }
                }
                .controlGroupStyle(.palette)
                Button { startReply(m, after: 0.45) } label: { Label("Reply", systemImage: "arrowshape.turn.up.left") } // once the menu has closed
            }
            // A voice note has no text to copy (its text only says what it is).
            let text = m.voice != nil ? "" : m.photo == nil ? m.text : Photo.caption(m.text)
            if !text.isEmpty {
                Button {
                    Clipboard.copy(text) // stays on this iPhone, and for a minute
                    nav.toast("Copied")
                } label: { Label("Copy", systemImage: "doc.on.doc") }
            }
            if mine {
                Button { journey = JourneyItem(id: m.id) } label: { Label("How it got there", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
            }
        }
        moderationMenu(m, mine: mine)
    }

    @ViewBuilder private func moderationMenu(_ m: Message, mine: Bool) -> some View {
        if mine {
            if m.isPublic && Place.of(m.text) != nil {
                Button(role: .destructive) { Moderation.hide(m.id); engine.moderationChanged(); nav.toast("Removed from this phone") } label: {
                    Label("Remove from this phone", systemImage: "trash")
                }
            }
        } else {
            // While a message is "not verified yet" nobody knows who wrote it: it can be hidden, its sender can't be blocked (`Blocks`).
            if Blocks.canBlock(m, me: engine.deviceId) {
                Button(role: .destructive) { blocking = m } label: { Label("Block \(m.name)", systemImage: "hand.raised") }
            } else {
                Button(role: .destructive) { Moderation.hide(m.id); engine.moderationChanged(); nav.toast("Hidden on this phone") } label: {
                    Label("Hide this message", systemImage: "eye.slash")
                }
            }
            if m.isPublic {
                Button(role: .destructive) {
                    Moderation.report(m.id); engine.moderationChanged()
                    nav.toast("Reported and hidden on this phone")
                } label: {
                    Label("Report this message", systemImage: "exclamationmark.bubble")
                }
            }
        }
    }

    /// Whether I checked in, in this group, after the question was asked.
    private func answered(after ts: Int64) -> Bool {
        engine.messages.contains { !$0.isPublic && $0.sender == engine.deviceId && $0.ts > ts && CheckIn.of($0.text) != nil }
    }

    private func markRead() {
        guard nav.tab == .chat, phase == .active, atBottom, let last = shown.last else { return }
        unread.markRead(channel, upTo: last.ts)
    }

    var body: some View {
        let list = shown
        // What a reply or a reaction in this chat can be about: this group's checked messages, by what names each (`About.Target`).
        let targets = toPublic ? [:] : About.targets(in: list, group: code)
        let blocked = toPublic ? [] : Set(Moderation.blocked.keys)
        // A message that comes while the chat is on screen fades and rises in; not with Reduce Motion or in Low Power Mode.
        let still = reduceMotion || Theme.lowPower
        let _ = arrivals.note(place: "\(toPublic)|\(code)", ids: list.map(\.id), shown: nav.tab == .chat && phase == .active)
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    // The group's timer shows next to the reach line only while one is set.
                    ScreenTitle(title: toPublic ? "Everyone" : groupName, subtitle: engine.reachLine,
                                timer: toPublic ? nil : engine.timer(code).seconds.flatMap { $0 > 0 ? GroupTimer.label($0) : nil })
                    Spacer(minLength: 0)
                    if !toPublic {
                        Button { nav.sheet = .security } label: { RoundIcon(symbol: "shield.fill") }.accessibilityLabel("Group security")
                    }
                }
                ChannelSwitch(toPublic: $nav.chatEveryone, groupName: groupName,
                              groupNew: unread.count(code, in: engine.allMessages, me: engine.deviceId),
                              everyoneNew: unread.count(Unread.everyone, in: engine.allMessages, me: engine.deviceId))
                Text(toPublic ? "Anyone with DarkProof can read these, nearby or online." : "Only people in \(groupName) can read these. " + GroupTimer.chatLine(engine.timer(code).seconds, highRisk: risk.isOn))
                    .scaledFont(13).foregroundStyle(Theme.faint)
                if !toPublic && engine.removed(code) {
                    NoteCard(title: "You were removed from \(groupName)", text: "You can still read what's here, but you can't write or see anything new. To come back, ask for a new invite.")
                } else if !toPublic && engine.inviteClosed(code) {
                    NoteCard(title: "This invite no longer works", text: "The group has a new code. Ask someone in \(groupName) for a new invite.")
                } else if !toPublic && engine.rejoining(code) {
                    NoteCard(title: "Rejoining \(groupName)…", text: "You've been away for more than a week. A phone that's in the group needs to be in reach, nearby or online.")
                } else if !toPublic && engine.joining(code) {
                    NoteCard(title: "Asking to join \(groupName)…", text: "An admin of the group has to let you in, and their phone needs to be in reach, nearby or online. You can write here once they have.")
                }
                if !toPublic { // admins only: for everyone else the engine lists no requests
                    let asking = engine.joinRequests(code)
                    if !asking.isEmpty { JoinRequestsRow(requests: asking) { showingRequests = true } }
                }
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 8)
            .backdropHeader()

            if list.isEmpty {
                // With a timer, or in high-risk mode, an empty chat may be one whose messages have left: it doesn't say "yet".
                let leaves = !toPublic && (risk.isOn || (engine.timer(code).seconds ?? 0) > 0)
                // With no message to place them between, what last happened in the group still shows: a join, the timer.
                if !toPublic {
                    ForEach(Array(engine.groupLines(code).suffix(3).enumerated()), id: \.offset) { _, j in GroupLine(text: j.text) }
                        .padding(.horizontal, 16)
                }
                EmptyState(symbol: toPublic ? "megaphone.fill" : "bubble.left.and.bubble.right.fill",
                           title: leaves ? "No messages here now" : "No messages yet",
                           text: toPublic ? "Messages for everyone show up here, nearby or online, from any group."
                                          : leaves ? "What you write reaches everyone in \(groupName) who is in reach, or later when they are."
                                                   : "Write the first one. It reaches everyone in \(groupName) who is in reach, or later when they are.")
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            let joins = toPublic ? [] : engine.groupLines(code)
                            ForEach(Array(list.enumerated()), id: \.element.id) { i, m in
                                let prev = i > 0 ? list[i - 1] : nil
                                // By position: two lines can have the same time and text ("Someone joined" twice), so GroupEvent.id can repeat.
                                ForEach(Array(joins.filter { $0.ts > (prev?.ts ?? .min) && $0.ts <= m.ts }.enumerated()), id: \.offset) { _, j in GroupLine(text: j.text) }
                                if prev == nil || !ChatTime.sameDay(prev!.ts, m.ts) {
                                    Text(ChatTime.dayTitle(m.ts)).scaledFont(13, .semibold).foregroundStyle(Theme.dim)
                                        .padding(.horizontal, 12).padding(.vertical, 5).background(Theme.tile, in: Capsule())
                                        .padding(.vertical, 8).accessibilityAddTraits(.isHeader)
                                        .modifier(Arrival(arrivals, id: m.id, still: still)) // a new day's first message brings its day
                                }
                                row(m, prev: prev, first: i == 0, targets: targets, blocked: blocked)
                                    .modifier(Arrival(arrivals, id: m.id, still: still))
                            }
                            ForEach(Array(joins.filter { $0.ts > (list.last?.ts ?? .min) }.enumerated()), id: \.offset) { _, j in GroupLine(text: j.text) }
                            Color.clear.frame(height: 1).id("bottom")
                                .onAppear { atBottom = true; newBelow = 0; markRead() }
                                .onDisappear { atBottom = false }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 12)
                    }
                    .scrollDismissesKeyboard(.interactively)
                    .overlay(alignment: .bottomTrailing) {
                        if newBelow > 0 {
                            Button { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } } label: {
                                Label("\(newBelow) new", systemImage: "arrow.down").scaledFont(14, .bold).foregroundStyle(Color.black)
                                    .padding(.horizontal, 14).padding(.vertical, 9).background(Color.white, in: Capsule())
                            }
                            .padding(16)
                            .accessibilityLabel("\(newBelow) new messages. Go to the newest")
                        }
                    }
                    // A new message scrolls down only if you were at the bottom already, or wrote it.
                    .onChange(of: list.last?.id) { _ in
                        guard let last = shown.last else { return }
                        if atBottom || last.sender == engine.deviceId {
                            withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
                            DispatchQueue.main.async { markRead() }
                        } else {
                            newBelow += 1
                        }
                    }
                    .onChange(of: toPublic) { _ in
                        newBelow = 0
                        DispatchQueue.main.async { proxy.scrollTo("bottom", anchor: .bottom); markRead() }
                    }
                    // A quote was tapped: its message comes into view and is lit for a moment.
                    .onChange(of: jumpTo) { id in
                        guard let id else { return }
                        jumpTo = nil
                        withAnimation(Theme.lowPower ? nil : .easeOut(duration: 0.25)) { proxy.scrollTo(id, anchor: .center); flashed = id }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { if flashed == id { withAnimation(Theme.lowPower ? nil : .easeOut(duration: 0.4)) { flashed = nil } } }
                    }
                    .onAppear { // open on the newest messages, once the list has been laid out
                        DispatchQueue.main.async { proxy.scrollTo("bottom", anchor: .bottom); markRead() }
                    }
                    .onChange(of: nav.tab) { t in if t == .chat { markRead() } }
                    .onChange(of: phase) { p in if p == .active { markRead() } }
                }
            }
            if toPublic || !(engine.removed(code) || engine.joining(code)) { VStack(alignment: .leading, spacing: 6) {
                if toPublic {
                    Label("To everyone: anyone with DarkProof can read it", systemImage: "megaphone.fill")
                        .scaledFont(13, .semibold).foregroundStyle(Theme.text).padding(.horizontal, 6)
                }
                if let t = replyTarget {
                    ReplyStrip(to: t.sender == engine.deviceId ? "yourself" : Quote.name(of: t, me: engine.deviceId), text: Quote.line(of: t)) { replyingTo = nil }
                }
                HStack(alignment: .bottom, spacing: 10) {
                    if !toPublic {
                        Button { showingTray = true } label: {
                            Image(systemName: "plus").scaledFont(20, .semibold).foregroundStyle(Theme.text)
                                .frame(width: 50, height: 50).background(Circle().fill(Theme.tile))
                        }
                        .accessibilityLabel("Add something")
                        .accessibilityHint("Photo, voice note, I'm safe, I need help, ask if safe")
                    }
                    // A voice note goes only to the group (never Everyone), from a phone with an Opus encoder.
                    Composer(draft: drafts.binding(channel), placeholder: toPublic ? "Message everyone" : "Message \(groupName)",
                             isPublic: toPublic, focused: $typing, voice: toPublic || !VoiceAudio.shared.canRecord ? nil : voice, recording: voice.on, send: send)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10) }
        }
        .tabBackdrop()
        .onChange(of: toPublic) { _ in replyingTo = nil } // a reply belongs to the chat it was started in
        .onChange(of: code) { _ in replyingTo = nil }
        .modifier(VoiceHooks(voice: voice, place: "\(toPublic)|\(code)", shown: phase == .active && nav.tab == .chat, send: sendVoice))
        .onAppear { notifier.refresh() } // alerts may have been turned off in the phone's Settings meanwhile
        .onChange(of: phase) { _, p in if p == .active { notifier.refresh() } }
        .sheet(item: $journey) { JourneySheet(engine: engine, id: $0.id) }
        .sheet(item: $findingPlace) { FindSheet(place: $0.place, by: $0.by, ts: $0.ts) }
        .sheet(item: $viewing) { PhotoViewer(jpeg: $0.jpeg, caption: $0.caption, from: $0.from) }
        .sheet(isPresented: $showingRequests) { JoinRequestsSheet(engine: engine) }
        .sheet(isPresented: $showingTray, onDismiss: afterTray) {
            ChatTray(engine: engine, sent: safeSent) { c in
                // "I'm safe" goes out at once, like Home's tile, and the tray stays for its sent moment (SafeSent.swift),
                // then closes (at once when calm); the rest open something, which waits until the tray has closed.
                if c == .safe {
                    guard !trayLeaving else { return }
                    let e = engine
                    let taken = e.checkIn(.safe, haptic: false) { nav.toast(e.queuedToast("“I'm safe”")) }
                    if taken { safeSent += 1 }
                    if !taken || SafeSent.calm(reduceMotion) { showingTray = false; return }
                    trayLeaving = true
                    // Only this tray closes: one swiped away and opened again meanwhile (or another "I'm safe") stays.
                    let n = safeSent
                    DispatchQueue.main.asyncAfter(deadline: .now() + SafeSent.seconds) {
                        guard trayLeaving, safeSent == n else { return }
                        showingTray = false; trayLeaving = false
                    }
                } else { trayChoice = c; showingTray = false }
            }
        }
        .sensoryFeedback(.success, trigger: safeSent) // the sent moment's one haptic, also when calm
        .confirmationDialog("Block \(blocking?.name ?? "")?", isPresented: Binding(get: { blocking != nil }, set: { if !$0 { blocking = nil } }),
                            titleVisibility: .visible, presenting: blocking) { m in
            Button("Block", role: .destructive) {
                let done = engine.block(m) // in the channel the message came in; refused if it isn't checked any more
                blocking = nil
                if done { nav.toast("\(m.name) is blocked on this phone") }
            }
        } message: { _ in
            Text("You won't see their messages, places or check-ins on this phone. You can unblock them in Settings.")
        }
        .sheet(item: $pending) { p in
            PhotoSendSheet(photo: p, groupName: groupName) { caption in
                engine.send(Photo.text(caption: caption), photo: p.jpeg, replyTo: replyTarget)
                replyingTo = nil
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        }
        .photosPicker(isPresented: $choosingPhoto, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await MainActor.run { photoItem = nil; prepare(data.flatMap { UIImage(data: $0) }) }
            }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker { image in takingPhoto = false; prepare(image) }.ignoresSafeArea()
        }
        .alert("This photo couldn't be made small enough to send.", isPresented: $photoFailed) { Button("OK", role: .cancel) {} }
        .alert("Too long for the LoRa radio", isPresented: $tooLong) { Button("OK", role: .cancel) {} } message: {
            Text("It travels only over Bluetooth, Wi-Fi and the internet. Shorter messages also go over the radio.")
        }
    }

    @ViewBuilder private func row(_ m: Message, prev: Message?, first: Bool, targets: [String: Message], blocked: Set<String>) -> some View {
        let mine = m.sender == engine.deviceId
        let newRun = prev == nil || prev!.sender != m.sender || prev!.name != m.name || CheckIn.of(prev!.text) != nil
            || !ChatTime.sameDay(prev!.ts, m.ts)
        let time = ChatTime.time.string(from: ChatTime.date(m.ts))
        let delivery = engine.delivery(of: m)
        // The line under one of the user's own group messages opens "How it got there", wherever the message is.
        let showReaders: () -> Void = { if mine && !m.isPublic { journey = JourneyItem(id: m.id) } }
        // Check-ins, reports and "Are you safe?" are group messages: an Everyone post that looks like one is plain text (`ChatCard`).
        let card = ChatCard.of(m)
        // A check-in clock (spec "If I go quiet"): a card here; apps without the feature show its text as a bubble.
        let clock: Int64? = if !m.isPublic, case .clock(let d)? = m.notice { d } else { nil }
        // Replies and reactions are for groups only (spec "Replies and reactions"): on Everyone nothing of them is read.
        let quote = quote(of: m, targets)
        let chips = m.isPublic ? [] : engine.chips(for: m, blocked: blocked)
        let canAnswer = !m.isPublic && engine.canAnswer(m)
        let tapChip: ((String) -> Void)? = canAnswer ? { react(m, $0) } : nil
        let tapQuote: () -> Void = { jumpTo = quote?.row }
        VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
        if let quote, card != .text || clock != nil { // a card has no bubble to hold the quote: it sits above the card
            QuoteView(quote: quote, tap: tapQuote).padding(.horizontal, 12).padding(.vertical, 8)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        Group {
            if let clock {
                VStack(alignment: .trailing, spacing: 4) {
                    // Named as the alert names them (rule Q13): "Anna (not checked)", or with emoji where two share a name.
                    // Item 9: another member's card promises an alert only if this phone keeps that clock (the message
                    // checked, rule Q1 passed, newest, not cancelled), and says so when alerts are off for DarkProof.
                    let keeping = mine || QuietBook.keeps(card: clock, verified: m.verified, state: engine.quietState(m.group, m.sender, now: Engine.quietNow()))
                    let alertsOn = !notifier.statusKnown || [.authorized, .provisional, .ephemeral].contains(notifier.status) // no "Alerts are off" before iOS answered
                    QuietCard(deadline: clock, name: mine ? nil : (engine.quietName(m.group, m.sender) ?? m.name), time: time, spot: QuietClock.spot(in: m.text),
                              keeping: keeping, alertsOn: alertsOn)
                    if let delivery { StatusLine(time: nil, delivery: delivery).padding(.horizontal, 6).onTapGesture(perform: showReaders) }
                }
            } else if case .place(let place) = card {
                let shared = SharedPlace(id: m.id, place: place, by: mine ? "you" : m.name, ts: m.ts)
                VStack(alignment: .trailing, spacing: 4) {
                    PlaceRow(shared: shared, me: loc.location?.spot, now: Date())
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .onTapGesture { if place.spot != nil { findingPlace = shared } }
                    if let delivery { StatusLine(time: nil, delivery: delivery).padding(.horizontal, 6) }
                }
            } else if case .checkIn(let c) = card {
                VStack(alignment: .trailing, spacing: 4) {
                    CheckInRow(checkIn: c, name: mine ? nil : m.name, time: time, battery: CheckIn.battery(in: m.text),
                               needs: CheckIn.needs(in: m.text), people: CheckIn.people(in: m.text), spot: CheckIn.spot(in: m.text))
                    if let delivery { StatusLine(time: nil, delivery: delivery).padding(.horizontal, 6).onTapGesture(perform: showReaders) }
                }
            } else if case .vouch(let v) = card {
                VStack(alignment: .trailing, spacing: 4) {
                    VouchRow(vouch: v, by: mine ? "you" : m.name, time: time)
                    if let delivery { StatusLine(time: nil, delivery: delivery).padding(.horizontal, 6).onTapGesture(perform: showReaders) }
                }
            } else if case .ask(let a) = card {
                AskRow(ask: a, from: mine ? nil : m.name, time: time, delivery: delivery,
                       canAnswer: !mine && a.isFor(myName) && !answered(after: m.ts), sending: engine.checkInSending != nil) { c in
                    if c == .safe { let e = engine; e.checkIn(.safe) { nav.toast(e.queuedToast("“I'm safe”")) } }
                    else { nav.sheet = .help }
                }
            } else {
                // A voice note only from a group packet whose signature checked (Voice.playable); until then, or if its
                // field was ignored, the message is its text ("Can't play this voice note." under the ignored one).
                Bubble(text: m.photo == nil ? m.text : Photo.caption(m.text), mine: mine,
                       name: newRun && !mine ? m.name : nil, time: time,
                       delivery: delivery, tapStatus: showReaders, photo: m.photo,
                       quote: quote, tapQuote: tapQuote, chips: chips, tapChip: tapChip,
                       voice: Voice.playable(m) ? m.voice : nil, voiceId: m.id,
                       cantPlay: m.voice == nil && !m.isPublic && m.text == Voice.placeholder) {
                    if let p = m.photo {
                        viewing = ViewedPhoto(id: m.id, jpeg: p, caption: Photo.caption(m.text), from: mine ? "You" : m.name)
                    }
                }
            }
        }
        if !chips.isEmpty, card != .text || clock != nil { ReactionChips(chips: chips, tap: tapChip) } // under a card; a bubble shows its own
        if !mine { VerifyNote(engine: engine, message: m) }
        }
        .padding(.top, newRun && !first ? 10 : 0)
        .background { // lit for a moment after a quote of it was tapped
            if flashed == m.id { RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Theme.text.opacity(0.12)).padding(-6) }
        }
        // Dragged to the right, a message that can be answered starts a reply (VoiceOver: the "Reply" action); same rule as the menu's Reply.
        .modifier(SwipeToReply(reply: canAnswer ? { startReply(m) } : nil))
        .contextMenu { messageMenu(m, mine: mine, canAnswer: canAnswer) } // hold: react, reply, copy; then Block, Report on Everyone, Remove your place
        .id(m.id)
    }

    /// What was picked in the "+" tray, once the tray has closed: nothing else can open while it is still closing.
    private func afterTray() {
        trayLeaving = false // the tray is gone, however it went: a delayed close is for it only
        guard let c = trayChoice else { return }
        trayChoice = nil
        switch c {
        case .photo: choosingPhoto = true
        case .camera: takingPhoto = true
        case .help: nav.sheet = .help
        case .askSafe: nav.sheet = .askSafe
        case .timer: nav.sheet = .timer
        case .voice: nav.toast("Hold the mic to record, 10 seconds at most")
        case .safe: break // sent when it was tapped
        }
    }

    /// Shrinks a picked or taken photo and opens the send sheet.
    private func prepare(_ image: UIImage?) {
        guard let image else { return }
        guard let jpeg = PhotoShrinker.jpeg(image), let small = UIImage(data: jpeg) else { photoFailed = true; return }
        pending = PendingPhoto(jpeg: jpeg, image: small)
    }

    /// A finished voice note: to the group on screen, as a reply when one is being written. Never on LoRa, so no
    /// "too long for the radio" for it.
    private func sendVoice(_ frames: Data) {
        let target = replyTarget
        replyingTo = nil
        engine.send(Voice.placeholder, replyTo: target, voice: frames)
    }

    private func send() {
        let d = drafts.binding(channel)
        let t = String(d.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1000))
        guard !t.isEmpty else { return }
        d.wrappedValue = ""
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        let target = replyTarget
        replyingTo = nil
        if !engine.send(t, isPublic: toPublic, replyTo: target) { tooLong = true }
    }
}

/// Your group or everyone, with how many new messages each has.
struct ChannelSwitch: View {
    @Binding var toPublic: Bool
    let groupName: String
    var groupNew = 0
    var everyoneNew = 0

    var body: some View {
        HStack(spacing: 4) {
            segment(groupName, new: groupNew, selected: !toPublic) { toPublic = false }
            segment("Everyone", new: everyoneNew, selected: toPublic) { toPublic = true }
        }
        .padding(4).background(Capsule().fill(Theme.tile))
        .animation(.easeOut(duration: 0.15), value: toPublic)
    }

    private func segment(_ title: String, new: Int, selected: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(title).scaledFont(15, .semibold).lineLimit(1)
                if new > 0 && !selected {
                    Text(new > 99 ? "99+" : "\(new)").scaledFont(12, .bold).foregroundStyle(Color.black)
                        .padding(.horizontal, 6).padding(.vertical, 1).background(Color.white, in: Capsule())
                }
            }
            .foregroundStyle(selected ? Color.black : Theme.dim)
            .frame(maxWidth: .infinity, minHeight: 36)
            .background(Capsule().fill(selected ? Color.white : .clear))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title + (new > 0 && !selected ? ", \(new) new" : ""))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// One message. Yours are white on the right; others are dark grey on the left with their name above.
struct Bubble: View {
    let text: String
    let mine: Bool
    var name: String? = nil
    var time: String? = nil
    var delivery: Delivery? = nil
    var tapStatus: () -> Void = {}
    var photo: Data? = nil
    /// The message this one answers, above its text; tapping it shows that message when it is in the chat.
    var quote: QuoteLine? = nil
    var tapQuote: () -> Void = {}
    /// The reactions under it; `tapChip` is nil where they can only be looked at.
    var chips: [Reactions.Chip] = []
    var tapChip: ((String) -> Void)? = nil
    /// A voice note that may be played (`Voice.playable`), shown instead of the text; `voiceId` names its message.
    var voice: Data? = nil
    var voiceId = ""
    /// The text says it is a voice note, but its field was ignored: a small line under the text says so.
    var cantPlay = false
    var tapPhoto: () -> Void = {}
    var isError = false
    var busy = false

    var body: some View {
        VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
            if let name { Text(name).scaledFont(13, .semibold).foregroundStyle(Theme.dim).padding(.horizontal, 6) }
            if let photo, let ui = PhotoShrinker.received(photo) {
                VStack(alignment: .leading, spacing: 0) {
                    if let quote { QuoteView(quote: quote, onWhite: mine, tap: tapQuote).padding(.horizontal, 14).padding(.top, 11).padding(.bottom, 9) }
                    // The sender chooses the picture: the row keeps its shape between 1:3 and 3:1 and crops the rest
                    // (Photo.shape), so one a pixel wide and very tall can't make a row hundreds of screens high.
                    Color.clear.aspectRatio(CGFloat(Photo.shape(width: Double(ui.size.width), height: Double(ui.size.height))), contentMode: .fit)
                        .overlay { Image(uiImage: ui).resizable().scaledToFill() }
                        .clipped()
                        .frame(maxWidth: 250)
                    if !text.isEmpty {
                        Text(text).scaledFont(17).foregroundStyle(mine ? Color.black : Theme.text)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 14).padding(.vertical, 10)
                    }
                }
                .frame(maxWidth: 250, alignment: .leading)
                .background(mine ? Color.white : Theme.tile)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .onTapGesture(perform: tapPhoto)
                .accessibilityLabel(text.isEmpty ? "Photo" : "Photo: \(text)")
                .accessibilityAddTraits(.isButton)
            } else {
            VStack(alignment: .leading, spacing: 6) {
                if let quote { QuoteView(quote: quote, onWhite: mine, tap: tapQuote) }
                if let voice { VoiceNoteRow(id: voiceId, frames: voice, onWhite: mine) }
                else {
                Group {
                    if busy && text.isEmpty { Text("Thinking…").foregroundStyle(Theme.dim) }
                    else { Text(text).textSelection(.enabled) }
                }
                .scaledFont(17)
                .foregroundStyle(mine ? Color.black : (isError ? Theme.help : Theme.text))
                .fixedSize(horizontal: false, vertical: true)
                if cantPlay { Text("Can't play this voice note.").scaledFont(13).foregroundStyle((mine ? Color.black : Theme.text).opacity(0.6)) }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 11)
            .background(mine ? Color.white : Theme.tile, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            }
            if !chips.isEmpty { ReactionChips(chips: chips, tap: tapChip).padding(.horizontal, 8).padding(.top, -9) } // they sit on the bubble's lower edge
            if time != nil || delivery != nil {
                StatusLine(time: time, delivery: delivery).padding(.horizontal, 6).onTapGesture(perform: tapStatus)
            }
        }
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
        .padding(mine ? .leading : .trailing, 48)
    }
}

/// A note above the chat: asking to join, removed, an invite that no longer works.
struct NoteCard: View {
    let title: String, text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).scaledFont(16, .semibold).foregroundStyle(Theme.text)
            Text(text).scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// "Marco joined" (v0.6): from the group's epochs, not a message.
struct GroupLine: View {
    let text: String
    var body: some View { Text(text).scaledFont(14, .medium).foregroundStyle(Theme.dim).frame(maxWidth: .infinity).padding(.vertical, 6) }
}

/// Under someone else's message: "Not verified yet" (their key hasn't come), or a new key for a checked person's name.
struct VerifyNote: View {
    @ObservedObject var engine: Engine
    let message: Message
    var body: some View {
        if engine.newKeyFor(message.group, message.sender, message.name) {
            Text("New key for \(message.name): check their emoji").scaledFont(13, .semibold).foregroundStyle(Theme.help).padding(.leading, 14)
        } else if !message.verified {
            Text("Not verified yet").scaledFont(13).foregroundStyle(Theme.faint).padding(.leading, 14)
        }
    }
}

/// Time and, for your own messages, where the message is: waiting, sent, or delivered to whom.
struct StatusLine: View {
    let time: String?
    let delivery: Delivery?

    var body: some View {
        HStack(spacing: 4) {
            if let time { Text(time) }
            if let delivery {
                if time != nil { Text("·") }
                Image(systemName: delivery.symbol).scaledFont(10, .bold)
                Text(delivery.label)
            }
        }
        .scaledFont(12).lineLimit(1)
        .foregroundStyle(delivery.map { if case .delivered = $0 { return Theme.dim } else { return Theme.faint } } ?? Theme.faint)
    }
}

extension Delivery {
    var symbol: String {
        switch self {
        case .waiting: return "clock"
        case .sent: return "checkmark"
        case .delivered: return "checkmark.circle.fill"
        }
    }

    var label: String {
        switch self {
        case .waiting: return "Waiting for a phone or internet"
        case .sent: return "Sent"
        case .delivered(let r):
            let n = r.map(\.name)
            if n.count == 1 { return "Delivered to \(n[0])" }
            if n.count == 2 { return "Delivered to \(n[0]) and \(n[1])" }
            return "Delivered to \(n[0]), \(n[1]) and \(n.count - 2) more"
        }
    }
}

struct ViewedPhoto: Identifiable { let id: String; let jpeg: Data; let caption: String; let from: String }

/// "Injured · Need water · 3 people": what a check-in or report says someone needs.
func needsText(_ needs: Set<Need>, people: Int?) -> String {
    var parts = Need.allCases.filter(needs.contains).map(\.label)
    if let people, people > 1 { parts.append("\(people) people") }
    return parts.joined(separator: " · ")
}

/// A check-in in the chat: a green "is safe" or red "needs help" card, with needs, head count and Plus Code.
struct CheckInRow: View {
    let checkIn: CheckIn
    let name: String? // nil = you
    let time: String
    var battery: Int? = nil
    var needs: Set<Need> = []
    var people: Int? = nil
    var spot: Spot? = nil

    var body: some View {
        let safe = checkIn == .safe
        let extra = needsText(needs, people: people)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Image(systemName: safe ? "checkmark.shield.fill" : "sos").scaledFont(18, .bold).foregroundStyle(checkIn.color)
                Text(name.map { safe ? "\($0) is safe" : "\($0) needs help" } ?? (safe ? "You're safe" : "You asked for help"))
                    .scaledFont(16, .semibold).foregroundStyle(Theme.text)
                Spacer()
                if let battery { BatteryLabel(percent: battery) }
                Text(time).scaledFont(13).foregroundStyle(Theme.dim)
            }
            if !extra.isEmpty { Text(extra).scaledFont(15, .semibold).foregroundStyle(Theme.text).padding(.leading, 36) }
            if let spot { PlusCodeLabel(spot: spot).padding(.leading, 36) }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(checkIn.color.opacity(0.13), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// Someone's report about another person: neutral, dashed, "reported by", never like that person's own check-in.
struct VouchRow: View {
    let vouch: Vouch
    let by: String
    let time: String

    var body: some View {
        let safe = vouch.status == .safe
        let extra = needsText(vouch.needs, people: vouch.people)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle.badge.questionmark").scaledFont(18, .bold).foregroundStyle(vouch.status.color)
                Text("\(vouch.subject) \(safe ? "is safe" : "needs help")").scaledFont(16, .semibold).foregroundStyle(Theme.text)
                Spacer()
                Text(time).scaledFont(13).foregroundStyle(Theme.dim)
            }
            Text("Reported by \(by)").scaledFont(14).foregroundStyle(Theme.dim).padding(.leading, 36)
            if !extra.isEmpty { Text(extra).scaledFont(15, .semibold).foregroundStyle(Theme.text).padding(.leading, 36) }
            if let s = vouch.spot { PlusCodeLabel(spot: s).padding(.leading, 36) }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
            .stroke(vouch.status.color.opacity(0.5), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
        .accessibilityElement(children: .combine)
    }
}

/// "Is everyone safe?" in the chat, with one-tap answers for whoever it is for.
struct AskRow: View {
    let ask: AreYouSafe
    let from: String? // nil = you asked
    let time: String
    let delivery: Delivery?
    let canAnswer: Bool
    let sending: Bool
    let answer: (CheckIn) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: "questionmark.bubble.fill").scaledFont(18, .bold).foregroundStyle(Theme.text)
                Text(title).scaledFont(16, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
                Spacer()
                Text(time).scaledFont(13).foregroundStyle(Theme.dim)
            }
            if canAnswer {
                HStack(spacing: 10) {
                    Button { answer(.safe) } label: { Label(sending ? "Sending…" : "I'm safe", systemImage: "checkmark.shield.fill") }
                        .buttonStyle(GhostButton()).disabled(sending)
                    Button { answer(.help) } label: { Label("I need help", systemImage: "sos") }
                        .buttonStyle(GhostButton()).disabled(sending)
                }
            }
            if let delivery { StatusLine(time: nil, delivery: delivery) }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var title: String {
        guard let from else { return ask.to.map { "You asked \($0) if they are safe" } ?? "You asked: is everyone safe?" }
        return ask.to.map { "\(from) asks \($0): are you safe?" } ?? "\(from) asks: is everyone safe?"
    }
}

/// "8FQFF57R+M2": a position people can read out or type into any map app. Tap to copy.
struct PlusCodeLabel: View {
    let spot: Spot
    var body: some View {
        if let code = spot.plusCode {
            Button {
                Clipboard.copy(code) // someone's position: on this iPhone only, and for a minute
                Nav.shared.toast("Plus Code copied")
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "plus.viewfinder").scaledFont(12, .semibold)
                    Text(code).scaledFont(13, .semibold, design: .monospaced)
                    Image(systemName: "doc.on.doc").scaledFont(11)
                }
                .foregroundStyle(Theme.dim)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Plus Code \(code.map(String.init).joined(separator: " ")). Copy")
        }
    }
}

/// Message field with a round send button. For Everyone it has a white outline and a megaphone.
struct Composer: View {
    @Binding var draft: String
    let placeholder: String
    var enabled = true
    var isPublic = false
    /// Lets the screen put the cursor in the field (the chat does, after "Reply").
    var focused: FocusState<Bool>.Binding? = nil
    /// Voice notes (a group's chat): the send button is a mic while the field is empty, and `recording` shows the strip.
    var voice: VoiceComposer? = nil
    var recording = false
    let send: () -> Void
    private var canSend: Bool { enabled && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private struct Focus: ViewModifier {
        let binding: FocusState<Bool>.Binding?
        func body(content: Content) -> some View { if let binding { content.focused(binding) } else { content } }
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            // While recording, the strip covers the field, which stays (with its keyboard): nothing moves under the finger.
            ZStack {
            TextField("", text: $draft, prompt: Text(placeholder).foregroundColor(Theme.faint), axis: .vertical)
                .lineLimit(1...5)
                .scaledFont(17).foregroundStyle(Theme.text).tint(Theme.text)
                .padding(.horizontal, 18).padding(.vertical, 14)
                .background(Theme.tile, in: RoundedRectangle(cornerRadius: 25, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 25, style: .continuous).stroke(isPublic ? Theme.text.opacity(0.7) : .clear, lineWidth: 1.5))
                .accessibilityLabel(placeholder)
                .modifier(Focus(binding: focused))
                .opacity(recording ? 0 : 1).allowsHitTesting(!recording).accessibilityHidden(recording)
            if let voice, recording { RecordingStrip(voice: voice) }
            }
            if let voice, recording || (enabled && draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) { MicButton(voice: voice) } else {
            Button(action: send) {
                Image(systemName: isPublic ? "megaphone.fill" : "arrow.up").scaledFont(isPublic ? 17 : 19, .bold)
                    .foregroundStyle(canSend ? Color.black : Theme.faint)
                    .frame(width: 50, height: 50)
                    .background(Circle().fill(canSend ? Color.white : Theme.tile))
            }
            .disabled(!canSend)
            .accessibilityLabel(isPublic ? "Send to everyone" : "Send")
            }
        }
    }
}

struct EmptyState: View {
    let symbol: String
    let title: String
    let text: String

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: symbol).scaledFont(28, .semibold).foregroundStyle(Theme.text)
                .frame(width: 72, height: 72).background(Circle().fill(Theme.tile))
            Text(title).scaledFont(22, .bold).foregroundStyle(Theme.text)
            Text(text).scaledFont(16).foregroundStyle(Theme.dim).multilineTextAlignment(.center).frame(maxWidth: 320)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}

/// Sheet with a title and a Done button, used for the secondary screens.
struct SheetPage<Content: View>: View {
    let title: String
    var close = "Done" // "Cancel" where closing means not doing it
    @ViewBuilder let content: () -> Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView { VStack(alignment: .leading, spacing: 16) { content() }.padding(20) }
                #if DEBUG
                    // `scrollTo background`, with `sheet settings`: opens Settings at its Background section, for pictures.
                    .onAppear {
                        let args = CommandLine.arguments
                        if let i = args.firstIndex(of: "scrollTo"), args.indices.contains(i + 1) {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { proxy.scrollTo(args[i + 1], anchor: .top) }
                        }
                    }
                #endif
            }
                .scrollDismissesKeyboard(.interactively)
                .background(Theme.bg.ignoresSafeArea())
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                .toolbarBackground(Theme.bg, for: .navigationBar)
                .toolbar { Button(close) { dismiss() }.fontWeight(.semibold) }
        }
        .preferredColorScheme(.dark)
        .tint(Theme.text)
        .modifier(ToastOverlay()) // confirmations show over sheets too
    }
}
