import SwiftUI
import BlackoutCore

/// "If I go quiet" (spec/FORMAT.md, "If I go quiet"; the approved board's `quiet`): this phone's check-in clock. The
/// group's phones that get the clock message keep it; if no check-in from this phone reaches one of them by the
/// deadline, it tells its owner. Who keeps it comes from the clock message's delivery receipts, never the member list.
struct QuietView: View {
    @ObservedObject var engine: Engine
    @CurrentCode private var code
    @AppStorage("groupName") private var groupName = "My group"
    @AppStorage(LocationService.shareKey) private var sharing = false
    @State private var minutes = 60
    @State private var withSpot = true
    @State private var busy = false

    var body: some View {
        SheetPage(title: QuietWords.title) {
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                let now = Engine.quietNow(), own = engine.quietOwn(code, now: now)
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(QuietWords.lead).scaledFont(19, .bold).foregroundStyle(Theme.text)
                        Text(QuietWords.sub).scaledFont(15).foregroundStyle(Theme.dim)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    dial(own, now: now).frame(maxWidth: .infinity)
                    if let own { running(own.clock, state: own.state) } else { start }
                    Text("Phones on older DarkProof versions only show the message.")
                        .scaledFont(13).foregroundStyle(Theme.faint).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // MARK: The ring

    private func dial(_ own: (clock: QuietBook.Clock, state: QuietBook.State)?, now: Int64) -> some View {
        let total: Int64, left: Int64, line: String
        if let own {
            total = max(1, own.clock.deadline - own.clock.ts); left = max(0, own.clock.deadline - now)
            line = QuietWords.ends(Engine.hm(own.clock.deadline))
        } else {
            total = Int64(minutes) * 60_000; left = total
            line = QuietWords.checkInBy(Engine.hm(QuietClock.deadline(minutes: minutes, now: now)))
        }
        let lit = left == 0 ? 0 : min(60, Int((Double(left) / Double(total) * 60).rounded(.up)))
        return ZStack {
            QuietRing(lit: lit)
            VStack(spacing: 2) {
                Text(Self.countdown(left)).scaledFont(38, .heavy).monospacedDigit().tracking(-0.8).foregroundStyle(Theme.text)
                Text(line).scaledFont(14, .medium).foregroundStyle(Theme.dim).multilineTextAlignment(.center).frame(maxWidth: 150)
            }
        }
        .frame(width: 220, height: 220)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(own == nil ? line : "\(Self.countdown(left)) left. \(line)")
    }

    /// "1:00:00", "29:59", "0:00".
    static func countdown(_ ms: Int64) -> String {
        let s = Int((Double(max(0, ms)) / 1000).rounded(.up)), h = s / 3600, m = s % 3600 / 60, x = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, x) : String(format: "%d:%02d", m, x)
    }

    // MARK: Before it runs

    @ViewBuilder private var start: some View {
        HStack(spacing: 8) {
            ForEach(QuietClock.choices, id: \.self) { m in
                Button { minutes = m } label: {
                    Text(QuietWords.chip(m)).scaledFont(15, .semibold).lineLimit(1).minimumScaleFactor(0.8)
                        .foregroundStyle(m == minutes ? Color.black : Theme.text)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(m == minutes ? Color.white : Theme.tile, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(PressScale())
                .accessibilityAddTraits(m == minutes ? .isSelected : [])
            }
        }
        if sharing {
            Toggle(isOn: $withSpot) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(QuietWords.addWhere).scaledFont(17, .semibold).foregroundStyle(Theme.text)
                    Text(QuietWords.addWhereSub).scaledFont(14).foregroundStyle(Theme.dim)
                }
            }
            .tint(Theme.safe)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        } else {
            Text(QuietWords.shareOff).scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
        }
        if engine.canTell(code) {
            Button(busy ? "Getting your position…" : QuietWords.start) {
                let m = minutes
                send(deadline: { QuietClock.deadline(minutes: m, now: $0) }, spot: sharing && withSpot) { QuietWords.sent(waiting: $0) }
            }
            .buttonStyle(PrimaryButton()).disabled(busy)
        } else { // rule QW2: never held until this phone is let in
            Text(QuietWords.cantWrite(groupName)).scaledFont(15, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: While it runs

    @ViewBuilder private func running(_ c: QuietBook.Clock, state: QuietBook.State) -> some View {
        if case .ranOut(let d) = state {
            Text(QuietWords.ranOut(Engine.hm(d))).scaledFont(15, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
        }
        Text(QuietWords.kept(engine.quietKeepers(code))).scaledFont(15).foregroundStyle(Theme.dim)
            .frame(maxWidth: .infinity).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        Button {
            let e = engine, waiting = e.reach == .none
            e.checkIn(.safe, group: code) { Nav.shared.toast(QuietWords.safeSent(waiting: waiting)) } // a check-in stops the clock everywhere it reaches (rule Q4)
        } label: { Label(QuietWords.safe, systemImage: "checkmark.shield.fill") }
            .buttonStyle(PrimaryButton()).disabled(engine.checkInSending != nil || busy)
        Button(QuietWords.addMore) {
            let old = c.deadline
            send(deadline: { QuietClock.added(to: old, now: $0) }, spot: sharing && withSpot) { QuietWords.added(waiting: $0) }
        }
        .buttonStyle(SecondaryButton()).disabled(busy)
        Button(QuietWords.stopWithout) { send(deadline: { _ in 0 }, spot: false) { QuietWords.stopped(waiting: $0) } }
            .scaledFont(16, .semibold).foregroundStyle(Theme.dim).frame(maxWidth: .infinity, minHeight: 44).disabled(busy)
    }

    private func send(deadline: @escaping (Int64) -> Int64, spot: Bool, toast: @escaping (Bool) -> String) {
        guard !busy else { return }
        busy = true
        let e = engine, waiting = e.reach == .none
        e.sendClock(code, deadline: deadline, spot: spot) { ok in
            busy = false
            UINotificationFeedbackGenerator().notificationOccurred(ok ? .success : .error)
            Nav.shared.toast(ok ? toast(waiting) : QuietWords.cantWrite(groupName))
        }
    }
}

/// 60 ticks around the countdown, lit from the top clockwise for the time that is left (the board's ring).
struct QuietRing: View {
    let lit: Int
    var body: some View {
        ZStack {
            ForEach(0..<60, id: \.self) { i in
                Capsule().fill(i < lit ? Theme.text : Color(white: 0.17))
                    .frame(width: 3.5, height: 10)
                    .offset(y: -99)
                    .rotationEffect(.degrees(Double(i) * 6))
            }
        }
        .frame(width: 220, height: 220)
        .animation(Theme.lowPower ? nil : .easeOut(duration: 0.2), value: lit)
        .accessibilityHidden(true)
    }
}

/// Home's row under the check-in buttons: opens "If I go quiet" and says how this phone's clock stands.
struct HomeQuietRow: View {
    @ObservedObject var engine: Engine
    let now: Date // Home's timeline: the line follows the time
    @CurrentCode private var code

    var body: some View {
        let own = engine.quietOwn(code, now: Engine.quietNow())
        let line: String = switch own?.state {
        case .running(let d)?: QuietWords.ends(Engine.hm(d))
        case .ranOut(let d)?: "Ran out at \(Engine.hm(d))"
        default: "Off"
        }
        Button { Nav.shared.sheet = .quiet } label: {
            HStack(spacing: 14) {
                Image(systemName: "timer").scaledFont(17, .semibold).foregroundStyle(Theme.text)
                    .frame(width: 42, height: 42).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(QuietWords.title).scaledFont(17, .semibold).foregroundStyle(Theme.text)
                    Text(line).scaledFont(14).foregroundStyle(Theme.dim)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").scaledFont(14, .semibold).foregroundStyle(Theme.decor).accessibilityHidden(true)
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(PressScale())
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
    }
}

/// A clock message in the chat: what this phone will do about it, in words that claim no more than it knows.
struct QuietCard: View {
    let deadline: Int64 // 0: a stop
    let name: String?   // nil: this phone's own
    let time: String
    var spot: Spot? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Image(systemName: deadline == 0 ? "timer.circle" : "timer").scaledFont(18, .bold).foregroundStyle(Theme.text)
                Text(deadline == 0 ? QuietWords.stoppedLine(name) : QuietWords.started(name)).scaledFont(16, .semibold).foregroundStyle(Theme.text)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 4)
                Text(time).scaledFont(13).foregroundStyle(Theme.dim)
            }
            if deadline != 0 {
                Text(QuietWords.startedSub(name, ends: Engine.hm(deadline))).scaledFont(15).foregroundStyle(Theme.dim)
                    .fixedSize(horizontal: false, vertical: true).padding(.leading, 36)
            }
            if let spot { PlusCodeLabel(spot: spot).padding(.leading, 36) }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(Theme.tile, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// The line under a person in People (rule Q6): their clock while it runs, "overdue" once it ran out with no check-in
/// reaching this phone, and their check-in after it.
enum QuietLine {
    static func of(_ s: QuietBook.State?) -> (String, Color)? {
        switch s {
        case .running(let d)?: return (QuietWords.running(Engine.hm(d)), Theme.dim)
        case .ranOut(let d)?: return (QuietWords.overdue(Engine.hm(d)), Theme.text)
        case .checkedIn(let at, _)?: return (QuietWords.checkedIn(Engine.hm(at)), Theme.safe)
        case nil: return nil
        }
    }
}
