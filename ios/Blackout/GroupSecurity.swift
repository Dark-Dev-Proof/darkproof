import SwiftUI
import BlackoutCore

/// What Group security says about one group, worked out from what the engine already knows and the app's own switches.
/// Home's row shows `flagged`, the same count the sheet's verdict shows.
struct GroupSecurityState {
    enum Tor: Equatable { case on, internetOff, off }

    /// This phone has a key of the group and wasn't removed: only then does it know who is in and how old the key is.
    let inGroup: Bool
    /// Everyone else in the group (not this phone), and those of them who aren't checked.
    let others: [GroupMember], unchecked: [GroupMember]
    /// When the current key started (`Engine.keySince`).
    let keySince: Int64?
    /// The key is more than a day past the week after which a phone makes the new one (`Keyring.weeklyDue`).
    let keyOld: Bool
    let tor: Tor
    let inviteClosed: Bool

    /// Rows that need a look: someone not checked, a key past its time, the internet on without Tor.
    var flagged: Int { (unchecked.isEmpty ? 0 : 1) + (keyOld ? 1 : 0) + (tor == .off ? 1 : 0) }

    /// The app's own change period (a week: `Keyring.keepMs`) plus one day.
    static let keyOldMs = Keyring.keepMs + 24 * 3_600_000

    /// "On" only when the relays are reached through Tor: with that switch on, the relay links wait for Tor and use
    /// nothing else (`RelayScope.connect`). A switch nobody touched is on, as everywhere (`TorSetting`).
    static func tor(internet: Bool, tor: Bool) -> Tor { !internet ? .internetOff : tor ? .on : .off }

    /// "All checked", "1 thing to check", "2 things to check".
    static func verdict(_ flagged: Int) -> String { flagged == 0 ? "All checked" : flagged == 1 ? "1 thing to check" : "\(flagged) things to check" }

    /// The verdict for a group this phone isn't in (yet, or any more): there is nothing it could have checked, so it
    /// never says "All checked". `removed`: an admin took this phone out.
    static func outside(removed: Bool) -> String { removed ? "You were removed" : "Waiting to be let in" }

    /// "3 of 5. Ben and Luca are not.": up to two names, then how many more.
    var peopleLine: String {
        guard !others.isEmpty else { return "Nobody else yet" }
        let head = "\(others.count - unchecked.count) of \(others.count)"
        let names = unchecked.map { $0.name.isEmpty ? "Someone" : $0.name }
        switch names.count {
        case 0: return head
        case 1: return "\(head). \(names[0]) is not."
        case 2: return "\(head). \(names[0]) and \(names[1]) are not."
        default: return "\(head). \(names[0]), \(names[1]) and \(names.count - 2) more are not."
        }
    }

    /// "Last changed today", "yesterday", "9 days ago": by calendar days, as people count them.
    static func keyLine(since: Int64, now: Date) -> String {
        let cal = Calendar.current, then = Date(timeIntervalSince1970: Double(since) / 1000)
        let days = cal.dateComponents([.day], from: cal.startOfDay(for: then), to: cal.startOfDay(for: now)).day ?? 0
        return days <= 0 ? "Last changed today" : days == 1 ? "Last changed yesterday" : "Last changed \(days) days ago"
    }
}

extension Engine {
    /// Group security for `code`'s group right now. `internet`, `tor`: the two switches, if the caller watches them.
    func securityState(_ code: String?, now: Date = Date(), internet: Bool? = nil, tor: Bool? = nil) -> GroupSecurityState {
        let d = UserDefaults.standard
        let since = keySince(code), inGroup = since != nil && !removed(code)
        let others = inGroup ? otherMembers(code) : []
        let age = since.map { Int64(now.timeIntervalSince1970 * 1000) - $0 } ?? 0
        return GroupSecurityState(inGroup: inGroup, others: others, unchecked: others.filter { !isChecked(code, $0.id) },
                                  keySince: since, keyOld: inGroup && age > GroupSecurityState.keyOldMs,
                                  tor: GroupSecurityState.tor(internet: internet ?? (d.object(forKey: "internet") as? Bool ?? true), tor: tor ?? TorSetting.isOn(d)),
                                  inviteClosed: inviteClosed(code))
    }

    /// How many rows of Group security need a look in `code`'s group; 0 when all is fine.
    func securityIssues(_ code: String?) -> Int { securityState(code).flagged }
}

/// Group security: a round shield, a verdict and six rows about the group on screen. Every value comes from the engine
/// or the app's switches; a row has a button only where the engine already has that action.
struct GroupSecuritySheet: View {
    @ObservedObject var engine: Engine
    @CurrentCode private var code
    @AppStorage("groupName") private var groupName = "My group"
    @AppStorage("internet") private var internetOn = true // watched: the Tor row follows the switches wherever they are flipped
    @AppStorage("tor") private var torOn = true
    @State private var makingKey = false
    @State private var keyChangedNow = false // this phone just made the new key: "Changed just now"
    @State private var timerExplained = false // the lines under "Messages disappear" are open

    init(engine: Engine) { self.engine = engine }

    var body: some View {
        let s = engine.securityState(code, internet: internetOn, tor: torOn)
        SheetPage(title: "Group security") {
            verdict(s)
            // Not in the group (yet, or any more): today's words for it, and no rows about people or a key this phone doesn't have.
            if engine.removed(code) {
                NoteCard(title: "You were removed from \(groupName)", text: "You can still read what's here, but you can't write or see anything new. To come back, ask for a new invite.")
            } else if engine.joining(code) {
                NoteCard(title: "Asking to join \(groupName)…", text: "An admin of the group has to let you in, and their phone needs to be in reach, nearby or online. You can write here once they have.")
            }
            VStack(alignment: .leading, spacing: 0) {
                if s.inGroup {
                    peopleRow(s)
                    keyRow(s)
                }
                SecurityRow(icon: "lock.fill", title: "Quantum-safe keys", detail: "On, always") { OkMark() }
                torRow(s)
                timerRow(s)
                SecurityRow(icon: "link", title: "Invite",
                            detail: s.inviteClosed ? "Closed. The old code no longer works." : "Open. Anyone with the code can ask to join.") { EmptyView() }
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
    }

    private func verdict(_ s: GroupSecurityState) -> some View {
        let ok = s.flagged == 0 && s.inGroup // green only for a group this phone is in
        let people = s.others.count + 1 // with this phone
        return VStack(spacing: 6) {
            Image(systemName: "shield.fill").scaledFont(34, .semibold).foregroundStyle(ok ? Theme.safe : Theme.text)
                .frame(width: 84, height: 84).background(Circle().fill(Theme.tile)).padding(.bottom, 6)
                .accessibilityHidden(true)
            Text(s.inGroup ? GroupSecurityState.verdict(s.flagged) : GroupSecurityState.outside(removed: engine.removed(code))).scaledFont(26, .heavy).foregroundStyle(Theme.text)
                .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
            Text(s.inGroup ? "\(groupName) · \(people) \(people == 1 ? "person" : "people")" : groupName)
                .scaledFont(15, .medium).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.top, 4).padding(.bottom, 8)
    }

    /// "Messages disappear": the group's timer as this phone knows it. While one is set, the row explains what it does
    /// and doesn't do; an admin changes it from here too.
    @ViewBuilder private func timerRow(_ s: GroupSecurityState) -> some View {
        let t = s.inGroup ? engine.timer(code) : (seconds: Optional(0), by: Optional<String>.none)
        SecurityRow(icon: "timer", title: "Messages disappear", detail: GroupTimer.securityLine(t.seconds, by: t.by, highRisk: HighRiskSetting.isOn())) {
            if s.inGroup && engine.canSetTimer(code) {
                Button("Change") { Nav.shared.sheet = .timer }.buttonStyle(SmallButton(main: false)).accessibilityLabel("Change: Messages disappear")
            } else if let seconds = t.seconds, seconds > 0 {
                Button { timerExplained.toggle() } label: { Image(systemName: timerExplained ? "chevron.up" : "info.circle").scaledFont(17, .semibold).foregroundStyle(Theme.dim).frame(width: 44, height: 44) }
                    .accessibilityLabel(timerExplained ? "Hide how the timer works" : "How the timer works")
            } else { OkMark() }
        }
        if let seconds = t.seconds, seconds > 0, timerExplained || (s.inGroup && engine.canSetTimer(code)) {
            Text(GroupTimer.explainer(seconds)).scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                .padding(.leading, 54).padding(.bottom, 10)
        }
    }

    @ViewBuilder private func peopleRow(_ s: GroupSecurityState) -> some View {
        SecurityRow(icon: "person.2.fill", title: "People checked", detail: s.peopleLine) {
            if s.others.isEmpty { EmptyView() }
            else if s.unchecked.isEmpty { OkMark() }
            else {
                Button("Check") { Nav.shared.open(group: nil, tab: .people) }.buttonStyle(SmallButton())
                    .accessibilityLabel("Check: People checked")
            }
        }
    }

    @ViewBuilder private func keyRow(_ s: GroupSecurityState) -> some View {
        let detail = keyChangedNow ? "Changed just now" : s.keySince.map { GroupSecurityState.keyLine(since: $0, now: Date()) } ?? ""
        SecurityRow(icon: "key.fill", title: "Group key", detail: detail) {
            if keyChangedNow { OkMark() }
            else if makingKey || engine.canStartNewKey(code) {
                // An admin's button: in the main action's white while the key is past its time, dark otherwise.
                Button { newKey() } label: {
                    if makingKey { ProgressView().tint(s.keyOld ? Color.black : Theme.text) } else { Text("New key") }
                }
                .buttonStyle(SmallButton(main: s.keyOld)).disabled(makingKey)
                .accessibilityLabel("New key: Group key")
            }
            else if s.keyOld { FlagMark() }
            else { OkMark() }
        }
    }

    @ViewBuilder private func torRow(_ s: GroupSecurityState) -> some View {
        switch s.tor {
        case .on: SecurityRow(icon: "globe", title: "Connect through Tor", detail: "On") { OkMark() }
        case .internetOff: SecurityRow(icon: "globe", title: "Connect through Tor", detail: "The internet is off") { EmptyView() }
        case .off:
            SecurityRow(icon: "globe", title: "Connect through Tor", detail: "Off") {
                Button("Switch on") { engine.setTor(true) }.buttonStyle(SmallButton())
                    .accessibilityLabel("Switch on: Connect through Tor")
            }
        }
    }

    /// The engine's own path for a new key (`Engine.newKey`), with today's message when a member's keys aren't here yet.
    private func newKey() {
        guard !makingKey else { return }
        makingKey = true
        engine.newKey(code) { made in
            makingKey = false
            switch made {
            case true?: keyChangedNow = true; Nav.shared.toast("Updated")
            case false?: Nav.shared.toast("Not everyone's keys have reached this phone yet. Try again when they're in reach.")
            case nil: break // the key changed meanwhile, or this phone is no longer an admin: the row shows it
            }
        }
    }
}

/// One line of Group security: a round icon, a bold title, a grey line under it, then a small button or a mark.
private struct SecurityRow<Trailing: View>: View {
    let icon: String
    let title: String
    let detail: String
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).scaledFont(16, .semibold).foregroundStyle(Theme.text)
                .frame(width: 40, height: 40).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(17, .semibold).foregroundStyle(Theme.text)
                if !detail.isEmpty {
                    Text(detail).scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            trailing()
        }
        .frame(minHeight: 44)
        .padding(.vertical, 10)
    }
}

/// Green check: this row is as it should be.
private struct OkMark: View {
    var body: some View {
        Image(systemName: "checkmark").scaledFont(15, .bold).foregroundStyle(Theme.safe).frame(width: 30)
            .accessibilityHidden(true) // the line next to it says it
    }
}

/// A row that needs a look and has nothing to tap: the white dot the app uses for "look here".
private struct FlagMark: View {
    var body: some View {
        Circle().fill(Theme.text).frame(width: 8, height: 8).frame(width: 30)
            .accessibilityLabel("To check")
    }
}

/// A small inline action in a row: GhostButton's size. `main`: in the main action's white, for the step that settles a
/// row that needs a look; otherwise dark, like GhostButton.
private struct SmallButton: ButtonStyle {
    var main = true
    func makeBody(configuration: Configuration) -> some View { Styled(configuration: configuration, main: main) }
    private struct Styled: View {
        let configuration: Configuration
        let main: Bool
        @Environment(\.isEnabled) private var enabled
        var body: some View {
            configuration.label
                .scaledFont(15, .semibold).foregroundStyle(main ? Color.black : Theme.text)
                .padding(.horizontal, 14).frame(minHeight: 36)
                .background(main ? Color.white : Theme.tile, in: Capsule())
                .frame(minHeight: 44).contentShape(Rectangle()).padding(.vertical, -4) // 44 pt to touch, laid out as 36
                .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.6)
        }
    }
}
