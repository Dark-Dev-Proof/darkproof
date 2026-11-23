import SwiftUI
import CoreBluetooth
import BlackoutCore

/// Finder (spec/FORMAT.md, "Finder"): how strong the Bluetooth signal of someone who asked for help is on this phone,
/// as a word and rings, and whether it is getting stronger. Never a distance or a direction. Shown only with the trial
/// switch on (`Engine.finderTrial`).
final class FinderSession: ObservableObject {
    @Published private(set) var reading = FinderMeter.Reading(band: .notInReach, trend: nil, smoothed: nil)
    @Published private(set) var role: BleLink.RssiRole = .none
    @Published private(set) var otherWay = false
    @Published private(set) var lastDbm: Int?
    let node: String
    private weak var engine: Engine?
    private var meter = FinderMeter()
    private var second = 0
    private var pending: Int?
    private var timer: Timer?
    #if DEBUG
    /// Debug builds: readings to use instead of Bluetooth, one a second (`finderFeed`; the Simulator has no Bluetooth).
    static var fakeFeed: [Int?]?
    private var feed: [Int?]?
    var faked: Bool { feed != nil }
    #else
    var faked: Bool { false }
    #endif

    init(engine: Engine, node: String) { self.engine = engine; self.node = node }

    func start() {
        guard timer == nil else { return }
        UIApplication.shared.isIdleTimerDisabled = true // the searcher walks with the screen on
        #if DEBUG
        feed = Self.fakeFeed
        if feed != nil { role = .central } else { listen() }
        #else
        listen()
        #endif
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    func stop() {
        timer?.invalidate(); timer = nil
        engine?.finderStop()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func listen() {
        engine?.finderStart(node) { [weak self] role, dbm in
            guard let self else { return }
            self.role = role
            if let dbm { self.pending = dbm; self.lastDbm = dbm }
        }
    }

    /// Once a second: the reading of that second into the meter (rules F7 to F12); a band change is felt and said.
    private func tick() {
        var dbm = pending
        pending = nil
        #if DEBUG
        if var f = feed { dbm = f.isEmpty ? nil : f.removeFirst(); feed = f; lastDbm = dbm }
        #endif
        if let e = engine, !faked { otherWay = e.finderLinked(node).otherWay }
        let before = reading.band
        reading = meter.step(second, dbm)
        second += 1
        guard reading.band != before else { return }
        let styles: [UIImpactFeedbackGenerator.FeedbackStyle] = [.soft, .soft, .light, .medium, .heavy]
        UIImpactFeedbackGenerator(style: styles[reading.band.rawValue]).impactOccurred()
        UIAccessibility.post(notification: .announcement, argument: reading.band.word)
    }
}

struct FinderSheet: View {
    @ObservedObject var engine: Engine
    let person: Person
    let code: String?
    @StateObject private var session: FinderSession

    init(engine: Engine, person: Person, code: String?) {
        self.engine = engine; self.person = person; self.code = code
        _session = StateObject(wrappedValue: FinderSession(engine: engine, node: person.finder ?? ""))
    }

    /// The person as the latest messages show them: "I'm safe" ends the search (F5).
    private var now: Person { Person.all(in: engine.messages, me: engine.deviceId).first { $0.id == person.id } ?? person }

    var body: some View {
        let p = now, name = person.name, band = session.reading.band
        SheetPage(title: "Finding \(name)") {
            VStack(spacing: 10) {
                FinderRings(band: band)
                    .padding(.bottom, 6)
                Text(band.word).scaledFont(44, .heavy).foregroundStyle(band == .here ? Theme.safe : Theme.text)
                    .accessibilityAddTraits(.updatesFrequently)
                Text(p.checkIn == .safe ? "\(name) checked in safe." : band.hint(name))
                    .scaledFont(16).foregroundStyle(Theme.dim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                if band != .notInReach, let t = session.reading.trend {
                    Text(t.words).scaledFont(15, .semibold).foregroundStyle(Theme.text)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .accessibilityElement(children: .combine)

            if band == .notInReach, let why = reason(name) {
                Card {
                    Text(why).scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                    if engine.bluetooth == .poweredOff || engine.bluetooth == .unauthorized { OpenSettingsButton() }
                }
            }

            Card {
                HStack(alignment: .firstTextBaseline) {
                    Text(p.checkIn == .safe ? "\(name) is safe" : "\(name) needs help").scaledFont(18, .bold).foregroundStyle(Theme.text)
                    Spacer()
                    if let c = p.checkIn { Chip(c.label, c.color) }
                }
                TimelineView(.periodic(from: .now, by: 30)) { t in
                    Text("Asked \(ago(p.checkInTs, t.date))" + (p.battery.map { " · 🔋 \($0)%" } ?? ""))
                        .scaledFont(15).foregroundStyle(Theme.dim)
                }
            }

            Button("I'm with \(name)") {
                engine.send("I'm with \(name)", group: code) // an ordinary message: never a check-in
                Nav.shared.toast("Your group is told you're with \(name).")
            }
            .buttonStyle(PrimaryButton())

            Text("Bluetooth can't tell direction, only stronger or weaker. Walls and people weaken it.")
                .font(.footnote).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
            #if DEBUG
            Text("debug: \(session.faked ? "fake feed" : session.role.rawValue) · \(session.lastDbm.map { "\($0) dBm" } ?? "no reading")"
                 + (session.reading.smoothed.map { String(format: " · S %.1f", $0) } ?? "") + " · \(session.node)")
                .font(.caption2.monospaced()).foregroundStyle(Theme.faint)
            #endif
        }
        .onAppear { session.start() }
        .onDisappear { session.stop() }
    }

    /// Why there is no reading (shown only while "Not in reach").
    private func reason(_ name: String) -> String? {
        if session.faked { return nil }
        if engine.bluetooth == .poweredOff || engine.bluetooth == .unauthorized { return "Turn on Bluetooth to search." }
        switch session.role {
        case .unmeasurable: return "Linked to \(name)'s phone, but this phone can't measure the signal on this link."
        case .central, .client: return nil
        case .none:
            if session.otherWay { return "Linked to \(name)'s phone, but not over Bluetooth, so the signal can't be measured." }
            return "Finder works only when this phone and \(name)'s are linked by Bluetooth. If \(name)'s phone restarted after asking, it can't be recognised until it sends a new check-in."
        }
    }
}

/// Four rings lit from the inside out, one more per band; green only at "Here". They change with the band, and nothing
/// moves on its own.
struct FinderRings: View {
    let band: FinderBand

    var body: some View {
        let lit = band.rawValue, here = band == .here
        ZStack {
            ForEach(0..<4) { i in
                let on = 4 - i <= lit // ring 3 is the innermost: it lights first
                Circle()
                    .stroke(on ? (here ? Theme.safe : Theme.text) : Theme.line, lineWidth: on ? 3 : 2)
                    .frame(width: CGFloat(250 - i * 56), height: CGFloat(250 - i * 56))
            }
            Circle().fill(here ? Theme.safe : band == .notInReach ? Theme.tile : Theme.card)
                .frame(width: 70, height: 70)
            Image(systemName: "iphone.radiowaves.left.and.right")
                .scaledFont(26, .semibold)
                .foregroundStyle(here ? Color.black : band == .notInReach ? Theme.decor : Theme.text)
        }
        .frame(width: 250, height: 250)
        .animation(Theme.lowPower ? nil : .easeOut(duration: 0.35), value: band)
        .accessibilityHidden(true)
    }
}
