import SwiftUI
import UserNotifications
import BlackoutCore

/// High-risk mode (Settings), for when this phone could be taken or searched. Four things, each done where it belongs
/// (the sheet names a fourth, "Says less": no Finder note in "I need help" and no "how it arrived" in receipts, in the
/// engine):
/// - Locks every time: "Lock DarkProof" is on and can't be turned off, and it asks whenever the app was left, without
///   the minute (`AppLock`, `HighRiskLock`).
/// - Shows less: alerts carry no name and no text, only that there is a new message or that someone needs help
///   (`Notifier.post`); the app switcher shows the logo, as with the lock.
/// - Keeps less: the messages and receipts of the groups this phone is in leave it a day after they reached it; one
///   written here, a day after it first left, and never before (`KeepsLess`, in the engine's clean-up). What was
///   removed is remembered by name (`DayBook`), so a neighbour that still carries it can't bring it back onto the
///   screen. Turning the mode off brings nothing back.
/// Saved like the other switches (`HighRiskSetting.key`). Main thread.
final class HighRisk: ObservableObject {
    static let shared = HighRisk()
    @Published private(set) var isOn = HighRiskSetting.isOn()

    /// Why the mode stayed off.
    enum Refused {
        case noPasscode, notUnlocked
        var text: String {
            switch self {
            case .noPasscode: return "High-risk mode stays off. Set a passcode on this iPhone first: without one there is nothing to lock DarkProof with."
            case .notUnlocked: return "High-risk mode stays off: the iPhone's unlock didn't go through."
            }
        }
    }

    /// Switches the mode on, the lock first: through the lock's own switch, which asks for the phone's own unlock. If
    /// the phone has no passcode or the unlock doesn't go through, nothing changes and `done` says why.
    func switchOn(done: @escaping (Refused?) -> Void) {
        guard AppLock.shared.available else { return done(.noPasscode) }
        AppLock.shared.turnOn("Switch on high-risk mode") { ok in
            guard ok else { return done(.notUnlocked) }
            UserDefaults.standard.set(true, forKey: HighRiskSetting.key)
            self.isOn = true
            UNUserNotificationCenter.current().removeAllDeliveredNotifications() // alerts already showing say more than alerts do now
            Mesh.shared.engine?.cleanUpNow() // "Keeps less" starts now, not at the next full hour
            Mesh.shared.engine?.quietPass() // check-in clocks: no positions kept, alerts that wait say less
            done(nil)
        }
    }

    /// Switches the mode off, after the phone's own unlock. "Lock DarkProof" stays on, with its own switch again.
    /// Messages that left this phone don't come back. `done`: false if the unlock didn't go through.
    func switchOff(done: @escaping (Bool) -> Void) {
        AppLock.shared.ask("Switch off high-risk mode", failed: { done(false) }) {
            UserDefaults.standard.set(false, forKey: HighRiskSetting.key)
            self.isOn = false
            Mesh.shared.engine?.quietPass()
            done(true)
        }
    }

    /// "Erase everything": as on a fresh install.
    func reset() { isOn = false }
}

/// The question behind Settings' "High-risk mode" row: what it does and "Yes, switch it on", or, while it is on, the way
/// to switch it off.
struct HighRiskSheet: View {
    @ObservedObject private var risk = HighRisk.shared
    @Environment(\.dismiss) private var dismiss
    @State private var why: String?   // why the mode didn't change
    @State private var working = false // the phone's unlock is on screen

    var body: some View {
        FittedSheet {
            if risk.isOn { on } else { off }
        }
    }

    private var off: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Switch on high-risk mode?").scaledFont(22, .bold).foregroundStyle(Theme.text)
                    .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
                Text("For when this phone could be taken or searched.").scaledFont(15).foregroundStyle(Theme.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(alignment: .leading, spacing: 14) {
                point("lock.fill", "Locks every time.", "Face ID, Touch ID or your passcode whenever you open DarkProof.")
                point("eye.slash.fill", "Shows less.", "No names or message text in alerts or the app switcher.")
                point("timer", "Keeps less.", "Messages in your groups leave this phone 1 day after they arrive.")
                point("antenna.radiowaves.left.and.right.slash", "Says less.", "No Finder, and receipts don't say how a message arrived.")
            }
            reason
            VStack(spacing: 4) {
                Button("Yes, switch it on") {
                    working = true; why = nil
                    risk.switchOn { refused in
                        working = false
                        if let refused { why = refused.text; return }
                        Nav.shared.toast("High-risk mode is on")
                        dismiss()
                    }
                }
                .buttonStyle(PrimaryButton()).disabled(working)
                Button("Not now") { dismiss() }.buttonStyle(QuietButton())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var on: some View {
        VStack(spacing: 14) {
            Image(systemName: "shield.fill").scaledFont(30, .semibold).foregroundStyle(Theme.safe)
                .frame(width: 72, height: 72).background(Circle().fill(Theme.safe.opacity(0.14))).accessibilityHidden(true)
            Text("High-risk mode is on").scaledFont(22, .bold).foregroundStyle(Theme.text)
                .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
            Text("This phone locks every time, shows less and keeps less.").scaledFont(15).foregroundStyle(Theme.dim)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            reason
            VStack(spacing: 4) {
                Button("Switch it off") {
                    working = true; why = nil
                    risk.switchOff { ok in
                        working = false
                        guard ok else { why = "High-risk mode stays on: the iPhone's unlock didn't go through."; return }
                        Nav.shared.toast("High-risk mode is off")
                        dismiss()
                    }
                }
                .buttonStyle(SecondaryButton()).disabled(working)
                Button("Keep it on") { dismiss() }.buttonStyle(QuietButton())
            }
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private var reason: some View {
        if let why { // white, as on Android: red is only for help and for what destroys
            Text(why).scaledFont(15, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// One of the things the mode does: its name in white, what it means in grey.
    private func point(_ icon: String, _ lead: String, _ rest: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon).scaledFont(16, .semibold).foregroundStyle(Theme.text)
                .frame(width: 40, height: 40).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
            Text("\(Text(lead).fontWeight(.semibold).foregroundStyle(Theme.text)) \(rest)")
                .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                .padding(.top, 2)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The second, quieter choice under a main button: plain text, as tall as a finger needs.
struct QuietButton: ButtonStyle {
    var color = Theme.text
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaledFont(17, .semibold).foregroundStyle(color)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}
