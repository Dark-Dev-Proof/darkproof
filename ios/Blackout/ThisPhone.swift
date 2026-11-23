import SwiftUI
import BlackoutCore

// Settings, "This phone": the question before leaving a group, and the screen that erases everything. What they do is
// in Wipe.swift.

/// "Leave <group>?": staying is the main choice. Leaving sends a note that asks the group's admins to remove this phone
/// and change the key (spec/FORMAT.md, "Leaving the group"), then waits at most 15 s for a link to take it. The group's
/// only admin first picks who runs the group after them.
struct LeaveSheet: View {
    let groupName: String
    let code: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var telling = Telling.shared
    private enum Step { case choose, ask, cantHandOver }
    @State private var step: Step?
    @State private var picked: Set<String> = []
    @State private var sent = false // "Leave" was tapped: the note's wait, or the new key before it, is on

    private var engine: Engine? { Mesh.shared.engine }
    private var current: Step { step ?? (engine?.onlyAdmin(code) == true ? .choose : .ask) }

    var body: some View {
        FittedSheet {
            if sent, let left = telling.left { waiting(left) }
            else {
                switch current {
                case .choose: choose
                case .ask: ask
                case .cantHandOver: cantHandOver
                }
            }
        }
        .interactiveDismissDisabled(sent)
        #if DEBUG
        // `askLeave pressLeave` (tests on the Simulator): "Leave" is tapped 3 s after the sheet opens, if it asks no choice first.
        .onAppear {
            guard CommandLine.arguments.contains("pressLeave"), current == .ask else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { sent = true; Wipe.leaveTelling(code, name: groupName) { sent = false; step = .cantHandOver } }
        }
        #endif
    }

    private func title(_ s: String) -> some View {
        Text(s).scaledFont(22, .bold).foregroundStyle(Theme.text)
            .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
    }
    private func line(_ s: String, bold: Bool = false) -> some View {
        Text(s).scaledFont(15, bold ? .semibold : .regular).foregroundStyle(bold ? Theme.text : Theme.dim)
            .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
    }

    private var ask: some View {
        Group {
            Image(systemName: "rectangle.portrait.and.arrow.right").scaledFont(24, .semibold).foregroundStyle(Theme.text)
                .frame(width: 64, height: 64).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
            title(LeaveWords.sheetTitle(groupName))
            line(LeaveWords.sheetText)
            if engine?.canTell(code) != true { line(LeaveWords.cantTell(groupName), bold: true) }
            VStack(spacing: 4) {
                Button("Stay") { dismiss() }.buttonStyle(PrimaryButton())
                Button(LeaveWords.leaveButton(groupName)) {
                    sent = true
                    Wipe.leaveTelling(code, name: groupName, handOver: Array(picked)) { sent = false; step = .cantHandOver }
                }
                .buttonStyle(QuietButton(color: Theme.help)).disabled(sent)
            }
        }
    }

    /// Only admin (rule LW2): who runs the group afterwards. The new key that makes them admins is made when "Leave" is
    /// tapped, right before the note, so choosing "Stay" afterwards changes nothing in the group.
    private var choose: some View {
        Group {
            title(LeaveWords.chooseTitle(groupName))
            line(LeaveWords.chooseText)
            VStack(spacing: 0) {
                ForEach(engine?.otherMembers(code) ?? []) { m in
                    let on = picked.contains(m.id)
                    Button { if on { picked.remove(m.id) } else { picked.insert(m.id) } } label: {
                        HStack(spacing: 12) {
                            Text(m.name.isEmpty ? "Someone" : m.name).scaledFont(17).foregroundStyle(Theme.text).lineLimit(1)
                            Spacer()
                            Image(systemName: on ? "checkmark.circle.fill" : "circle").scaledFont(22).foregroundStyle(on ? Theme.text : Theme.dim)
                        }
                        .frame(minHeight: 48).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
            .padding(.horizontal, 14).background(Theme.tile, in: RoundedRectangle(cornerRadius: 14))
            VStack(spacing: 4) {
                Button("Continue") { step = .ask }.buttonStyle(PrimaryButton()).disabled(picked.isEmpty)
                Button(LeaveWords.leaveWithoutChoosing) { picked = []; step = .ask }.buttonStyle(QuietButton(color: Theme.help))
                line(LeaveWords.withoutChoosingWarning)
            }
        }
    }

    private var cantHandOver: some View {
        Group {
            title(LeaveWords.sheetTitle(groupName))
            line(LeaveWords.cantHandOver, bold: true)
            VStack(spacing: 4) {
                Button("Cancel") { dismiss() }.buttonStyle(PrimaryButton())
                Button(LeaveWords.leaveWithoutTelling) { Wipe.leave(code, name: groupName) }.buttonStyle(QuietButton(color: Theme.help))
            }
        }
    }

    private func waiting(_ left: Int) -> some View {
        Group {
            ProgressView().tint(Theme.text).padding(.top, 6).accessibilityHidden(true)
            title(LeaveWords.waitingTitle(groupName))
            line(LeaveWords.waitingText)
            Text("\(left)").scaledFont(34, .heavy).monospacedDigit().foregroundStyle(Theme.text)
                .accessibilityLabel("\(left) seconds left")
            Button(LeaveWords.leaveNow) { Telling.shared.now() }.buttonStyle(QuietButton(color: Theme.help))
        }
    }
}

/// "Erase everything": a whole screen, so it is never tapped in passing, and a button that must be held. With "Tell my
/// groups first" on, the hold sends a note to each group, then waits at most 15 s for a link to take them, with
/// "Erase now" always there (spec/FORMAT.md, "Leaving the group"); then the erase runs as without it.
struct EraseScreen: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var telling = Telling.shared
    @State private var erasing = false
    @State private var tell = true
    @State private var attempt = 0 // a hold that couldn't start the erase: the button starts over

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            if erasing, let left = telling.left {
                VStack(spacing: 16) {
                    ProgressView().tint(Theme.text).accessibilityHidden(true)
                    Text(LeaveWords.tellingTitle).scaledFont(28, .heavy).foregroundStyle(Theme.text)
                        .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                    Text(LeaveWords.waitingText).scaledFont(17).foregroundStyle(Theme.dim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    Text("\(left)").scaledFont(40, .heavy).monospacedDigit().foregroundStyle(Theme.text).accessibilityLabel("\(left) seconds left")
                }
                .padding(.horizontal, 28)
            } else {
                VStack(spacing: 16) {
                    Image(systemName: "trash.fill").scaledFont(30, .semibold).foregroundStyle(Theme.help)
                        .frame(width: 84, height: 84).background(Circle().fill(Theme.help.opacity(0.16))).accessibilityHidden(true)
                    Text("Erase everything").scaledFont(28, .heavy).foregroundStyle(Theme.text)
                        .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                    Text("Messages, groups, keys and the AI model leave this phone. \(Text("This can't be undone.").fontWeight(.bold).foregroundStyle(Theme.text))")
                        .scaledFont(17).foregroundStyle(Theme.dim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    Toggle(isOn: $tell) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(LeaveWords.tellFirst).scaledFont(17, .semibold).foregroundStyle(Theme.text)
                            Text(LeaveWords.tellFirstSub).scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .tint(Theme.safe).padding(14).background(Theme.card, in: RoundedRectangle(cornerRadius: 14)).padding(.top, 8)
                    .disabled(erasing)
                }
                .padding(.horizontal, 28)
            }
            Spacer()
            HStack(spacing: 12) {
                if erasing, telling.left != nil {
                    // Nothing waits on the network: the erase starts at once.
                    Button(LeaveWords.eraseNow) { Telling.shared.now() }.buttonStyle(RedButton())
                } else {
                    Button("Cancel") { dismiss() }.buttonStyle(SecondaryButton()).disabled(erasing)
                    HoldToErase(erasing: erasing) {
                        // With the switch on: the notes first, at most 15 s. Then keys and files go at once, then the engine
                        // and every link stop, then everything is deleted again, then "This phone is empty". It always
                        // starts: a group being left right now stops where it is, and the erase goes on (`Wipe`).
                        erasing = Wipe.eraseTellingFirst(tell)
                        if !erasing { attempt += 1 }
                    }
                    .id(attempt)
                }
            }
            .padding(.horizontal, 20).padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .modifier(ToastOverlay())
        .interactiveDismissDisabled(erasing)
    }
}

/// The red capsule of "Erase now": the same shape as the hold button beside which it stands.
private struct RedButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaledFont(17, .bold).foregroundStyle(Color.white)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Theme.help, in: Capsule())
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// After "Erase everything": DarkProof is empty on this phone, and what became of each group's note (`EraseResult`).
/// When the check at the end found something left (`left`), it says "Not everything could be erased" and how to remove
/// the rest instead, never "empty". Held in memory only; "Continue" goes on to the welcome screen.
struct EmptyScreen: View {
    let lines: [String]
    var left = false
    let done: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: left ? "exclamationmark" : "checkmark").scaledFont(30, .bold).foregroundStyle(Theme.text)
                .frame(width: 84, height: 84).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
            Text(LeaveWords.finalTitle(left: left)).scaledFont(28, .heavy).foregroundStyle(Theme.text).multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
            if left {
                Text(LeaveWords.deleteRest).scaledFont(17, .semibold).foregroundStyle(Theme.text).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                Text(l).scaledFont(17).foregroundStyle(Theme.dim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Button("Continue", action: done).buttonStyle(PrimaryButton())
        }
        .padding(.horizontal, 28).padding(.bottom, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }
}

/// The red "Hold to erase": it fills while a finger holds it, for about 1.3 seconds, and only then erases; letting go
/// early empties it again and nothing happens. With VoiceOver, Switch Control or a keyboard there is no holding: activating
/// it asks once more instead.
private struct HoldToErase: View {
    let erasing: Bool
    let action: () -> Void
    private static let hold = 1.3
    @State private var fill: CGFloat = 0
    @State private var pressedAt: Date?
    @State private var done = false
    @State private var confirming = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Text(erasing ? "Erasing…" : "Hold to erase")
            .scaledFont(17, .bold).foregroundStyle(Color.white).lineLimit(1).minimumScaleFactor(0.7)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Theme.help.opacity(0.45)                          // the part not held yet
                        Theme.help.frame(width: g.size.width * fill)     // filling from the left
                    }
                }
            }
            .clipShape(Capsule())
            .contentShape(Capsule())
            .onLongPressGesture(minimumDuration: Self.hold, maximumDistance: 80) {
                fire()
            } onPressingChanged: { pressing in
                guard !done else { return }
                if pressing {
                    // Something else is holding it for the user: a hold can't be asked of them.
                    if UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning { confirming = true; return }
                    pressedAt = Date()
                    if reduceMotion || Theme.lowPower { fill = 1 } else { withAnimation(.linear(duration: Self.hold)) { fill = 1 } }
                } else {
                    let short = pressedAt.map { Date().timeIntervalSince($0) < 0.4 } ?? false
                    pressedAt = nil
                    withAnimation(.easeOut(duration: 0.2)) { fill = 0 }
                    if short { Nav.shared.toast("Keep holding to erase") }
                }
            }
            .allowsHitTesting(!erasing)
            .focusable(!erasing)
            .onKeyPress(keys: [.return, .space]) { _ in confirming = true; return .handled }
            .accessibilityElement()
            .accessibilityLabel(erasing ? "Erasing" : "Erase everything")
            .accessibilityHint("Asks once more, then erases. This can't be undone.")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { if !erasing && !done { confirming = true } }
            .alert("Erase everything?", isPresented: $confirming) {
                Button("Erase everything", role: .destructive) { fire() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Messages, groups, keys and the AI model leave this phone. This can't be undone.") }
    }

    private func fire() {
        guard !done else { return }
        done = true; fill = 1
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        action()
    }
}
