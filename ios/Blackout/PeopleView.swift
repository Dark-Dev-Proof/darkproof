import SwiftUI
import AVFoundation
import CoreImage.CIFilterBuiltins
import BlackoutCore

// MARK: - Model

extension CheckIn {
    var label: String { self == .safe ? "Safe" : "Needs help" }
    var color: Color { self == .safe ? Theme.safe : Theme.help }
}

extension Engine {
    /// This phone's battery level, or nil when unknown (the Simulator has none).
    static var batteryPercent: Int? {
        #if DEBUG
        if let v = UserDefaults.standard.object(forKey: "debugBattery") as? Int { return v } // simctl spawn ... defaults write
        #endif
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        return level < 0 ? nil : Int((level * 100).rounded())
    }

    /// Your own most recent check-in in this group.
    var myLastCheckIn: Message? {
        messages.last { !$0.isPublic && $0.sender == deviceId && CheckIn.of($0.text) != nil }
    }

    /// The members of `code`'s group who haven't written yet (`people`: those who have). They read everything too, so
    /// the People list shows them and each can be checked. Not someone blocked: they stay hidden here, as before.
    func silentMembers(_ code: String?, besides people: [Person]) -> [Person] {
        let seen = Set(people.map(\.id))
        return otherMembers(code).filter { !seen.contains($0.id) && !isBlocked(code, $0.id) }
            .map { Person.silent(id: $0.id, name: $0.name.isEmpty ? "Someone" : $0.name) }
    }

    /// The one badge under a person's name. A new key for a checked person's name comes first: that is the warning.
    func personCheck(_ code: String?, _ id: String, _ name: String) -> PersonCheck {
        if newKeyFor(code, id, name) { return .newKey }
        switch checkedHow(code, id) {
        case .inPerson?: return .inPerson
        case .emoji?: return .emoji
        case nil: return .none
        }
    }
}

/// How a person's key stands on this phone (spec/FORMAT.md, "Person emoji", "Person code").
enum PersonCheck: Equatable { case inPerson, emoji, newKey, none }

/// "Checked in person", "Checked by emoji", "New key. Check again." or "Not checked": one line, under a name.
struct CheckBadge: View {
    let check: PersonCheck

    var body: some View {
        switch check {
        case .inPerson: label("Checked in person", Theme.safe, shield: true)
        case .emoji: label("Checked by emoji", Theme.dim, shield: true)
        case .newKey: label("New key. Check again.", Theme.help, shield: false)
        case .none: label("Not checked", Theme.faint, shield: false)
        }
    }

    private func label(_ text: String, _ color: Color, shield: Bool) -> some View {
        HStack(spacing: 4) {
            if shield { Image(systemName: "shield.fill").accessibilityHidden(true) }
            Text(text)
        }
        .scaledFont(13, .semibold).foregroundStyle(color)
    }
}

extension PersonCode {
    /// Black-on-white QR code of exactly `text` (like `Invite.qr`), sharp at any size.
    static func qr(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// "just now", "5 min ago", "2 h ago", "3 d ago".
func ago(_ ts: Int64, _ now: Date) -> String {
    let s = max(0, Int(now.timeIntervalSince1970 - Double(ts) / 1000))
    switch s {
    case ..<60: return "just now"
    case ..<3600: return "\(s / 60) min ago"
    case ..<86_400: return "\(s / 3600) h ago"
    default: return "\(s / 86_400) d ago"
    }
}

// MARK: - People tab

struct PeopleView: View {
    @ObservedObject var engine: Engine
    @ObservedObject private var nav = Nav.shared
    @AppStorage("groupName") private var groupName = "My group"
    @CurrentCode private var code
    @State private var safeSent = 0 // "I'm safe" sent from the tile: each one plays the sent moment once

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { t in
            let wrote = Person.all(in: engine.messages, me: engine.deviceId)
            let people = wrote + engine.silentMembers(code, besides: wrote) // members who haven't written yet come last
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    HStack(alignment: .top) {
                        ScreenTitle(title: "People", subtitle: people.isEmpty ? groupName :
                            "\(groupName) · \(people.count) \(people.count == 1 ? "person" : "people")")
                        Spacer()
                        Button { nav.sheet = .settings } label: { RoundIcon(symbol: "gearshape.fill") }.accessibilityLabel("Settings")
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 12) {
                            BigCheckIn(checkIn: .safe, sending: engine.checkInSending == .safe, sent: safeSent) {
                                let e = engine
                                let taken = e.checkIn(.safe, haptic: false) { nav.toast(e.queuedToast("“I'm safe”")) }
                                if taken { safeSent += 1 } // the sent moment and its haptic (SafeSent.swift)
                            }
                            BigCheckIn(checkIn: .help, sending: engine.checkInSending == .help) { nav.sheet = .help }
                        }
                        .disabled(engine.checkInSending != nil)
                        LastCheckIn(engine: engine, now: t.date).padding(.horizontal, 4)
                    }
                    SetupProblemCard(engine: engine)
                    if engine.reach == .none && engine.setupProblem == nil { ConnectionHelp(engine: engine) }
                    if people.isEmpty {
                        NoPeopleCard(groupName: groupName) { nav.sheet = .invite }
                    } else {
                        PeopleList(people: people, engine: engine, now: t.date)
                    }
                    VStack(spacing: 10) {
                        Button { nav.sheet = .askSafe } label: { Label("Ask “Are you safe?”", systemImage: "questionmark.bubble.fill") }
                            .buttonStyle(SecondaryButton())
                        Button { nav.sheet = .vouch } label: { Label("Check in for someone", systemImage: "person.crop.circle.badge.checkmark") }
                            .buttonStyle(SecondaryButton())
                        if !people.isEmpty {
                            Button { nav.sheet = .invite } label: { Label("Invite friends", systemImage: "qrcode") }
                                .buttonStyle(SecondaryButton())
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 24)
            }
            .background(Theme.bg.ignoresSafeArea())
        }
        .sensoryFeedback(.success, trigger: safeSent) // the sent moment's one haptic, also when calm
    }
}

/// Big green "I'm safe" or red "I need help" tile, in the logo's shape. `sent` ("I'm safe" only): how many were sent
/// from here; each one plays the sent moment (SafeSent.swift), the ring growing from the tile's own outline.
struct BigCheckIn: View {
    let checkIn: CheckIn
    var sending = false
    var sent: Int? = nil
    let action: () -> Void

    var body: some View {
        let safe = checkIn == .safe
        Button(action: action) {
            VStack(alignment: .leading, spacing: 18) {
                if let sent { SafeCheck(sent: sent).scaledFont(30, .bold) }
                else { Image(systemName: safe ? "checkmark.shield.fill" : "sos").scaledFont(30, .bold) }
                Text(sending ? "Sending…" : safe ? "I'm safe" : "I need help").scaledFont(21, .heavy)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            .foregroundStyle(safe ? Color.black : .white)
            .frame(maxWidth: .infinity, minHeight: 132, alignment: .bottomLeading).padding(18)
            .background(VaultShape(cut: 28, radius: 32, small: 6).fill(checkIn.color))
            .contentShape(VaultShape(cut: 28, radius: 32, small: 6))
        }
        .buttonStyle(PressScale())
        .safeRing(VaultShape(cut: 28, radius: 32, small: 6), grow: 0.1, sent: sent ?? 0) // outside the press: it doesn't shrink or dim with it
        .accessibilityLabel(sending ? "Sending" : safe ? "I'm safe" : "I need help")
        .accessibilityHint(safe ? "Tells your group you're safe" : "Asks your group for help")
    }
}

/// "Your last check-in: safe, 12 min ago."
struct LastCheckIn: View {
    @ObservedObject var engine: Engine
    let now: Date

    var body: some View {
        if engine.checkInSending != nil {
            Text("Getting your position, then sending…").scaledFont(14, .semibold).foregroundStyle(Theme.text)
        } else if let m = engine.myLastCheckIn, let c = CheckIn.of(m.text) {
            Text("Your last check-in: \(c.label.lowercased()), \(ago(m.ts, now)).").scaledFont(14).foregroundStyle(Theme.dim)
        } else {
            Text("One tap tells your group how you are.").scaledFont(14).foregroundStyle(Theme.dim)
        }
    }
}

/// Shown while nobody from the group has written yet.
struct NoPeopleCard: View {
    let groupName: String
    let invite: () -> Void

    var body: some View {
        Card {
            Text("No one from \(groupName) yet").scaledFont(20, .bold).foregroundStyle(Theme.text)
            Text("Invite friends with a QR code, then keep DarkProof open near each other. People show up here once they write.")
                .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
            Button(action: invite) { Label("Invite friends", systemImage: "qrcode") }.buttonStyle(PrimaryButton())
        }
    }
}

/// People in one card, divided by hairlines.
struct PeopleList: View {
    let people: [Person]
    @ObservedObject var engine: Engine
    let now: Date
    @CurrentCode private var code
    @State private var finding: Person?
    @State private var checking: Person?

    var body: some View {
        VStack(spacing: 0) {
            ForEach(people) { p in
                PersonRow(person: p, byMe: p.reporterId == engine.deviceId, now: now,
                          check: p.isReport ? nil : engine.personCheck(code, p.id, p.name), find: { finding = p },
                          open: p.isReport ? nil : { checking = p },
                          clock: p.isReport ? nil : QuietLine.of(engine.quietState(code, p.id, now: Engine.quietNow())))
                if p.id != people.last?.id { Rectangle().fill(Theme.line).frame(height: 1).padding(.leading, 76) }
            }
        }
        .sheet(item: $checking) { PersonCheckSheet(engine: engine, person: $0, code: code) }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .sheet(item: $finding) { FindSheet(person: $0) }
    }
}

struct PersonRow: View {
    let person: Person
    var byMe = false
    let now: Date
    var check: PersonCheck? = nil // how their key stands here; reports have no key
    var find: () -> Void = {} // opens the compass, for people who shared their position
    var open: (() -> Void)? = nil // their page, with the check; reports have none
    var clock: (String, Color)? = nil // their check-in clock as this phone holds it (spec "If I go quiet"); reports have none

    private var status: (String, Color) {
        if person.lastTs == 0 && person.checkIn == nil { return ("No check-in yet", Theme.dim) } // in the group, nothing written yet
        guard let c = person.checkIn else { return ("Last heard \(ago(person.lastTs, now))", Theme.dim) }
        if person.isReport {
            let by = byMe ? "by you" : "by \(person.reportedBy ?? "someone")"
            return ("\(c.label) (reported \(by)) · \(ago(person.checkInTs, now))", c.color)
        }
        return ("\(c.label) · \(ago(person.checkInTs, now))", c.color)
    }

    var body: some View {
        let s = status
        let extra = needsText(person.checkIn == .help ? person.needs : [], people: person.people)
        HStack(spacing: 14) {
            Group {
                if person.isReport {
                    Image(systemName: "person.fill").scaledFont(18, .bold).foregroundStyle(Theme.dim)
                        .frame(width: 46, height: 46).background(Circle().fill(Theme.tile))
                        .overlay(Circle().stroke(person.checkIn?.color ?? .clear, style: StrokeStyle(lineWidth: 2, dash: [4, 3])).padding(-3))
                } else {
                    Text(String(person.name.prefix(1)).uppercased()).scaledFont(18, .bold).foregroundStyle(Theme.text)
                        .frame(width: 46, height: 46).background(Circle().fill(Theme.tile))
                        .overlay(Circle().stroke(person.checkIn?.color ?? .clear, lineWidth: 2.5).padding(-3))
                }
            }
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(person.name).scaledFont(17, .semibold).foregroundStyle(Theme.text).lineLimit(1)
                if let check { CheckBadge(check: check) }
                Text(s.0).scaledFont(14, .medium).foregroundStyle(s.1).fixedSize(horizontal: false, vertical: true)
                if let clock { Label(clock.0, systemImage: "timer").scaledFont(14, .semibold).foregroundStyle(clock.1).fixedSize(horizontal: false, vertical: true) }
                if !extra.isEmpty { Text(extra).scaledFont(14, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 6) {
                if let b = person.battery { BatteryLabel(percent: b) }
            }
            if person.spot != nil {
                Button(action: find) { RoundIcon(symbol: "location.north.fill") }
                    .accessibilityLabel("Find \(person.name)")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .contentShape(Rectangle())
        .onTapGesture { if let open { open() } else if person.spot != nil { find() } }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Find \(person.name)") { if person.spot != nil { find() } }
        .accessibilityAction(named: "Check \(person.name)") { open?() }
    }
}

/// Battery level from someone's check-in. Red at 20% or less: their phone may soon go off.
struct BatteryLabel: View {
    let percent: Int

    var body: some View {
        let low = percent <= 20
        HStack(spacing: 4) {
            Image(systemName: percent <= 10 ? "battery.0percent" : percent <= 35 ? "battery.25percent" :
                    percent <= 60 ? "battery.50percent" : percent <= 85 ? "battery.75percent" : "battery.100percent")
            Text("\(percent)%")
        }
        .scaledFont(13, .semibold)
        .foregroundStyle(low ? Theme.help : Theme.dim)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Battery \(percent) percent")
    }
}

// MARK: - Connection help

/// Shown while nothing carries messages yet (no phone, relay or radio): what to check.
struct ConnectionHelp: View {
    @ObservedObject var engine: Engine

    var body: some View {
        Card {
            Text("No one in range yet").scaledFont(20, .bold).foregroundStyle(Theme.text)
            tip("iphone.radiowaves.left.and.right",
                "Open DarkProof on both phones and stay within about 10–50 m. Walls cut the range: try outside or by a window.")
            tip("wifi", "On the same Wi-Fi or hotspot, phones also connect with no internet.")
            tip("point.3.connected.trianglepath.dotted",
                "Farther away? Messages hop through any phone with DarkProof in between. For kilometers, pair a LoRa radio.")
        }
    }

    private func tip(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Image(systemName: icon).foregroundStyle(Theme.text).frame(width: 22).accessibilityHidden(true)
            Text(text).scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - A person, and checking them

/// A person's page: the big initial, the name and how their key stands on this phone, then what you can do: find them,
/// check them, ask if they are safe. An admin can also make them an admin or remove them, as before.
/// Warns when a checked person's name now comes with another key.
struct PersonCheckSheet: View {
    @ObservedObject var engine: Engine
    let person: Person
    let code: String
    @State private var finding = false
    @State private var bluetoothFinding = false
    @State private var checking = false
    @State private var askAdmin = false
    @State private var askRemove = false
    @Environment(\.dismiss) private var dismiss

    private func note(_ ok: Bool, _ done: String) { Nav.shared.toast(ok ? done : "Not everyone's keys have reached this phone yet. Try again when they're in reach.") }

    var body: some View {
        let name = person.name
        let check = engine.personCheck(code, person.id, name)
        let admin = engine.isAdmin(code, person.id)
        SheetPage(title: "") {
            VStack(spacing: 6) {
                Text(String(name.prefix(1)).uppercased()).scaledFont(32, .bold).foregroundStyle(Theme.text)
                    .frame(width: 88, height: 88).background(Circle().fill(Theme.tile)).padding(.bottom, 6)
                    .accessibilityHidden(true)
                Text(name).scaledFont(26, .heavy).foregroundStyle(Theme.text)
                    .multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.6)
                    .accessibilityAddTraits(.isHeader)
                CheckBadge(check: check)
                if admin {
                    Text("\(name) is an admin of this group.").scaledFont(14).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity)
            if check == .newKey {
                Card {
                    Text("New key for \(name)").scaledFont(18, .bold).foregroundStyle(Theme.help)
                    Text("You checked \(name) before, with another key. If \(name) didn't reinstall DarkProof or reset their phone, someone else may be using their name. Check again before you trust these messages.")
                        .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
            }
            HStack(alignment: .top, spacing: 6) {
                if person.spot != nil { PersonAction(label: "Find", symbol: "location.north.fill") { finding = true } }
                // Finder (spec "Finder"): their latest check-in asks for help and says which Bluetooth neighbour they are. Trial only.
                if Engine.finderTrial, person.checkIn == .help, person.finder != nil {
                    PersonAction(label: "Find by Bluetooth", symbol: "dot.radiowaves.left.and.right") { bluetoothFinding = true }
                }
                PersonAction(label: "Check", symbol: "qrcode") { checking = true }
                // Asking one person, as "Are you safe?" does. Not once this phone was removed (it can't write there any
                // more), nor someone whose name hasn't come yet: the question is addressed by name.
                if !engine.removed(code), person.lastTs != 0 || engine.publicKey(of: person.id, in: code) != nil {
                    PersonAction(label: "Ask if safe", symbol: "questionmark.bubble.fill") { askIfSafe() }
                }
            }
            .padding(.vertical, 4)
            if person.id != engine.deviceId, engine.isAdmin(code, engine.deviceId), engine.inGroup(code, person.id), !engine.removed(code) {
                VStack(spacing: 0) {
                    if !admin {
                        option("Make admin", "key.fill", Theme.text) { askAdmin = true }
                        Rectangle().fill(Theme.line).frame(height: 1).padding(.leading, 16)
                    }
                    option("Remove from group", "rectangle.portrait.and.arrow.right", Theme.help) { askRemove = true }
                }
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
        }
        .sheet(isPresented: $finding) { FindSheet(person: person) }
        .sheet(isPresented: $bluetoothFinding) { FinderSheet(engine: engine, person: person, code: code) }
        .sheet(isPresented: $checking) { CheckPersonSheet(engine: engine, person: person, code: code) }
        .alert("Make \(person.name) an admin?", isPresented: $askAdmin) {
            Button("Make admin") { engine.makeAdmin(code, person.id) { note($0, "\(person.name) is now an admin") } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("\(person.name) will be able to remove people and make others admins. The group sees this.") }
        .alert("Remove \(person.name)?", isPresented: $askRemove) {
            Button("Remove", role: .destructive) { engine.remove(code, person.id) { ok in note(ok, "\(person.name) was removed"); if ok { dismiss() } } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Everyone else gets a new key and a new invite code, and old invites stop working. \(person.name) can't read anything from now on. The group sees who removed them.") }
    }

    /// One line of the admin's choices: its words, then its icon.
    private func option(_ title: String, _ symbol: String, _ color: Color, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).scaledFont(17, .semibold)
                Spacer(minLength: 8)
                Image(systemName: symbol).scaledFont(16, .semibold).accessibilityHidden(true)
            }
            .foregroundStyle(color)
            .padding(.horizontal, 18).frame(minHeight: 54).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// "Marco, are you safe?" to the group, exactly as the "Are you safe?" sheet sends it for one person.
    private func askIfSafe() {
        engine.send(AreYouSafe(to: person.name).text)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        Nav.shared.toast(engine.queuedToast("the question to \(person.name)"))
    }
}

/// A round action with its word under it, like the ones in the chat's "+" tray.
private struct PersonAction: View {
    let label: String
    let symbol: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol).scaledFont(22, .semibold).foregroundStyle(Theme.text)
                    .frame(width: 58, height: 58).background(Circle().fill(Theme.tile))
                Text(label).scaledFont(13, .semibold).foregroundStyle(Theme.dim)
                    .multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
    }
}

/// A person's sixteen emoji (spec/FORMAT.md, "Person emoji"), eight to a line and never cut: each line shrinks to the
/// width it gets. VoiceOver says their names, the words people read out.
private struct PersonEmoji: View {
    let pub: Data

    var body: some View {
        let ix = MemberKey.emoji(pub)
        let lines = Fingerprint.emojiLines(ix).components(separatedBy: "\n")
        VStack(spacing: 8) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line).scaledFont(30).lineLimit(1).minimumScaleFactor(0.4)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Fingerprint.spoken(ix))
    }
}

/// Checking a person, two ways. Scan: this phone shows its own person code (spec/FORMAT.md, "Person code") and scans
/// theirs with the camera; every byte of the key is compared, nothing is read out or typed. Compare emoji: the sixteen
/// emoji, as before. Only a match marks the person, and the mark remembers which way it was.
private struct CheckPersonSheet: View {
    @ObservedObject var engine: Engine
    let person: Person
    let code: String
    @AppStorage("groupName") private var groupName = "My group"
    @State private var byEmoji = false     // the tab: Scan comes first, it is the stronger check
    @State private var scanning = false
    @State private var done: Checked.How?  // just checked: the closing page
    @State private var notTheirs = false   // the last scan was a person code, of another key
    @State private var qr: UIImage?        // this phone's own person code
    @State private var hasCamera = true
    @Environment(\.dismiss) private var dismiss

    private var name: String { person.name }

    var body: some View {
        let theirs = engine.publicKey(of: person.id, in: code)
        SheetPage(title: "Check \(name)", close: done == nil ? "Cancel" : "Done") {
            if let how = done {
                donePage(how)
            } else if let theirs {
                PillSwitch(left: "Scan", right: "Compare emoji", isRight: $byEmoji)
                if byEmoji { emojiTab(theirs) } else { scanTab }
            } else {
                Card {
                    Text("\(name)'s key hasn't reached this phone yet, so their messages show “Not verified yet”. It comes by itself when their phone or the internet is in reach.")
                        .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .sheet(isPresented: $scanning) { PersonScanSheet(name: name) { read($0) } }
        .task(id: code) {
            hasCamera = Self.cameraOrTestCode
            guard let mine = engine.myPublicKey(code), let text = PersonCode.encode(mine) else { qr = nil; return }
            qr = PersonCode.qr(text)
            #if DEBUG
            DebugLog.write("person code shown \(text)") // tests give it to the other test phone as "debugPersonScan"
            #endif
        }
    }

    // MARK: Scan

    @ViewBuilder private var scanTab: some View {
        VStack(spacing: 16) {
            if let qr {
                Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                    .padding(16)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .frame(maxWidth: 240)
                    .accessibilityLabel("Your person code in \(groupName)")
            }
            Text("In the same place: \(name) scans this, then you scan \(name)'s code. This is the strongest check.")
                .scaledFont(15).foregroundStyle(Theme.dim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        if notTheirs {
            Card {
                Text("This is not \(name)'s key.").scaledFont(18, .bold).foregroundStyle(Theme.help)
                Text("Someone else may be using the name. Do not trust this person's messages until you have sorted it out together.")
                    .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
            }
        }
        if hasCamera {
            Button("Scan \(name)'s code") { startScan() }.buttonStyle(PrimaryButton())
        } else {
            Text("This device has no camera. Compare the emoji instead.")
                .scaledFont(15).foregroundStyle(Theme.dim).multilineTextAlignment(.center).frame(maxWidth: .infinity)
        }
    }

    /// The Simulator has no camera. In test builds, a text saved under "debugPersonScan"
    /// (`simctl spawn <device> defaults write it.darkproof.mesh debugPersonScan "DPK1:…"`) is read instead of opening it.
    private static var testCode: String? {
        #if DEBUG
        return UserDefaults.standard.string(forKey: "debugPersonScan")
        #else
        return nil
        #endif
    }
    private static var cameraOrTestCode: Bool { testCode != nil || AVCaptureDevice.default(for: .video) != nil }

    private func startScan() {
        if let test = Self.testCode {
            if let hint = read(test) { Nav.shared.toast(hint) }
            return
        }
        scanning = true
    }

    /// What the camera read. Says what to tell the user while the camera stays open, or nil once it is settled and the
    /// scanner closes: their key (marked checked in person), or a person code of another key (nothing is marked).
    private func read(_ text: String) -> String? {
        guard let theirs = engine.publicKey(of: person.id, in: code) else { return nil } // the page then says their key isn't here yet
        switch PersonCode.check(text, theirs: theirs, mine: engine.myPublicKey(code)) {
        case .same:
            engine.markChecked(code, person.id, name, how: .inPerson)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            notTheirs = false; done = .inPerson
            return nil
        case .own:
            return "That is your own code. Scan \(name)'s phone."
        case .other:
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            notTheirs = true
            return nil
        case .notACode:
            return "That is not a DarkProof person code."
        }
    }

    // MARK: Compare emoji

    @ViewBuilder private func emojiTab(_ theirs: Data) -> some View {
        PersonEmoji(pub: theirs)
            .padding(.vertical, 18).padding(.horizontal, 12)
            .background(Theme.tile, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        Text(Fingerprint.spoken(MemberKey.emoji(theirs))).scaledFont(13).foregroundStyle(Theme.dim)
            .multilineTextAlignment(.center).frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
            .accessibilityHidden(true) // the emoji above already say these names
        Text("Ask \(name) to read these out. If \(name)'s phone shows the same sixteen, nobody is in between.")
            .scaledFont(15).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
        Text("They open Settings › Groups and read you “Your emoji”.")
            .scaledFont(13).foregroundStyle(Theme.faint).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
        Button("They match") { engine.markChecked(code, person.id, name, how: .emoji); done = .emoji }.buttonStyle(PrimaryButton())
        Button { noMatch() } label: {
            Text("They don't match").scaledFont(17, .semibold).foregroundStyle(Theme.help)
                .frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Nothing is marked, and a mark made earlier goes: the person is unchecked from here on, as the toast says.
    private func noMatch() {
        engine.unmarkChecked(code, person.id)
        Nav.shared.toast("\(name) stays unchecked")
        dismiss()
    }

    // MARK: Checked

    private func donePage(_ how: Checked.How) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "shield.fill").scaledFont(34, .semibold).foregroundStyle(Theme.safe)
                .frame(width: 84, height: 84).background(Circle().fill(Theme.tile)).padding(.bottom, 6)
                .accessibilityHidden(true)
            Text("\(name) is checked").scaledFont(26, .heavy).foregroundStyle(Theme.text)
                .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
            Text("\(how == .inPerson ? "Checked in person" : "Checked by emoji"), today. If \(name)'s key ever changes, DarkProof shows it in People and under their messages.")
                .scaledFont(15).foregroundStyle(Theme.dim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            Button("Done") { dismiss() }.buttonStyle(PrimaryButton()).padding(.top, 10)
        }
        .frame(maxWidth: .infinity).padding(.top, 12)
    }
}

/// The camera scanner invites use (`QRCamera`), for a person code. `read` gets what was scanned and says what to tell the
/// user while the camera stays open, or nil once it is settled: the scanner then closes.
private struct PersonScanSheet: View {
    let name: String
    let read: (String) -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var problem: (text: String, settings: Bool)?
    @State private var hint: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let problem {
                    VStack(spacing: 16) {
                        Image(systemName: "camera.fill").scaledFont(30, .semibold).foregroundStyle(Theme.dim)
                        Text(problem.text).scaledFont(17).foregroundStyle(Theme.text).multilineTextAlignment(.center)
                        if problem.settings { OpenSettingsButton() }
                    }
                    .padding(32)
                } else {
                    QRCamera(onCode: { s in
                        if let h = read(s) { hint = h; UINotificationFeedbackGenerator().notificationOccurred(.warning) } else { dismiss() }
                    }, onProblem: { p in problem = (Self.text(p), p.settings) })
                    .ignoresSafeArea()
                    VStack {
                        Spacer()
                        RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(Color.white, lineWidth: 3)
                            .frame(width: 250, height: 250).accessibilityHidden(true)
                        Spacer()
                        Text(hint ?? "Point the camera at the code on \(name)'s phone.")
                            .scaledFont(16, .semibold).foregroundStyle(Theme.text).multilineTextAlignment(.center)
                            .padding(.horizontal, 18).padding(.vertical, 12)
                            .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                            .padding(.horizontal, 20).padding(.bottom, 40)
                    }
                }
            }
            .navigationTitle("Scan \(name)'s code").navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Theme.bg, for: .navigationBar)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
        .tint(Theme.text)
    }

    /// QRCamera's own words are about invites ("type the code instead"); here the other way is the emoji.
    private static func text(_ p: (text: String, settings: Bool)) -> String {
        if p.settings { return "Camera access is off for DarkProof. Turn it on in Settings, or compare the emoji instead." }
        return AVCaptureDevice.default(for: .video) == nil ? "This device has no camera. Compare the emoji instead." : p.text
    }
}
