import SwiftUI
import BlackoutCore

/// "Messages disappear after": the group's timer (spec/FORMAT.md, "Disappearing messages"). An admin picks a value,
/// which makes a new key that carries it; everyone else reads what the group has. Opened from the "+" tray and from
/// Group security.
struct TimerSheet: View {
    @ObservedObject var engine: Engine
    @CurrentCode private var code
    @AppStorage("groupName") private var groupName = "My group"
    @State private var making: Int?     // the value whose key is being made
    @State private var failed = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let t = engine.timer(code), admin = engine.canSetTimer(code)
        FittedSheet {
            VStack(alignment: .leading, spacing: 14) {
                Text("Messages disappear after").scaledFont(21, .heavy).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
                if admin {
                    VStack(spacing: 0) {
                        ForEach(GroupTimer.choices + [0], id: \.self) { s in
                            option(s, current: t.seconds)
                            if s != 0 { Rectangle().fill(Theme.line).frame(height: 1).padding(.leading, 16) }
                        }
                    }
                    .background(Theme.tile, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    // Decision D: what the timer also deletes, said where it is chosen.
                    Text(GroupTimer.alsoHelp).scaledFont(15, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
                    if let s = t.seconds, s > 0, !GroupTimer.choices.contains(s) { // set by another version: none of the rows has the check
                        Text("Now \(GroupTimer.label(s)).").scaledFont(15, .semibold).foregroundStyle(Theme.text)
                    } else if t.seconds == nil {
                        Text("Not known on this phone yet.").scaledFont(15, .semibold).foregroundStyle(Theme.text)
                    }
                    if failed {
                        Text("Not changed. This phone doesn't have every member's keys yet. Try again when more phones are in reach.")
                            .scaledFont(15, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
                    }
                    Text("Counted from when a message reaches each phone. A new value starts deleting after 10 minutes. Phones on older DarkProof versions keep messages 1 to 2 weeks.")
                        .scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                } else {
                    Text(GroupTimer.sheetLine(t.seconds)).scaledFont(17).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
                    Text(engine.removed(code) || engine.joining(code) ? "This phone isn't in \(groupName) now." : "Only admins can change this.")
                        .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func option(_ seconds: Int, current: Int?) -> some View {
        Button { choose(seconds) } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(seconds == 0 ? "Off" : GroupTimer.label(seconds)).scaledFont(17, .semibold).foregroundStyle(Theme.text)
                    if seconds == 0 { Text("1 to 2 weeks").scaledFont(14).foregroundStyle(Theme.dim) }
                }
                Spacer(minLength: 8)
                if making == seconds { ProgressView().tint(Theme.text) }
                else if current == seconds { Image(systemName: "checkmark").scaledFont(15, .bold).foregroundStyle(Theme.text) }
            }
            .padding(.horizontal, 16).frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(making != nil)
        .accessibilityLabel(seconds == 0 ? "Off, 1 to 2 weeks" : GroupTimer.label(seconds))
        .accessibilityAddTraits(current == seconds ? .isSelected : [])
    }

    private func choose(_ seconds: Int) {
        guard making == nil else { return }
        if engine.timer(code).seconds == seconds { dismiss(); return } // it is what the group has
        making = seconds; failed = false
        engine.setTimer(code, seconds: seconds) { ok in
            making = nil
            if ok { Nav.shared.toast("Timer set"); dismiss() } else { failed = true }
        }
    }
}
