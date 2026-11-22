import SwiftUI
import BlackoutCore

/// Above the group chat, for admins: everyone asking to join behind one line, so requests never bury the chat.
/// Tap opens the list (`JoinRequestsSheet`).
struct JoinRequestsRow: View {
    let requests: [JoinRequest]
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                HStack(spacing: -14) {
                    ForEach(Array(requests.prefix(3))) { r in
                        Text(JoinRequestsSheet.initial(r.name)).font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.text) // fixed: the circle doesn't grow
                            .frame(width: 30, height: 30).background(Circle().fill(Theme.tile))
                            .padding(2.5).background(Circle().fill(Theme.card)) // a ring in the row's colour keeps overlapping initials apart
                    }
                }
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(requests.count == 1 ? "1 person asks to join" : "\(requests.count) people ask to join")
                        .scaledFont(16, .semibold).foregroundStyle(Theme.text)
                    Text("Let in only people you know").scaledFont(14).foregroundStyle(Theme.dim)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").scaledFont(14, .semibold).foregroundStyle(Theme.decor).accessibilityHidden(true)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(PressScale())
        .fixedSize(horizontal: false, vertical: true) // the chat below must not squeeze it
        .accessibilityElement(children: .combine)
        .accessibilityHint("Shows who is asking")
    }
}

/// Everyone asking to join, one row each: two requests with one name are two phones, so nothing is merged. "Let in"
/// asks once more before the existing approve path runs, because whoever is let in reads everything from then on.
/// Closes by itself when the last request is gone.
struct JoinRequestsSheet: View {
    @ObservedObject var engine: Engine
    @CurrentCode private var code
    @State private var confirming: String?          // the request whose "Let in" was tapped: its row asks once more
    @State private var approving: Set<String> = []  // a new key is being made for these: a second tap would report a failure
    @Environment(\.dismiss) private var dismiss

    static func initial(_ name: String) -> String { String(name.prefix(1)).uppercased() }
    /// Names that read the same are the same name (the app's own rule, `Vouch.key`): "Ben", "ben " and a "Ben" with a
    /// character in it that draws nothing.
    private static func nameKey(_ name: String) -> String { Vouch.key(name) }
    @AppStorage("name") private var myName = ""

    var body: some View {
        let requests = engine.joinRequests(code)
        let byName = Dictionary(grouping: requests, by: { Self.nameKey($0.name) })
        // Someone asking under a name the group already has: it may be that person's new phone, or someone else.
        let members = Set((engine.otherMembers(code).map(\.name) + [myName]).map(Self.nameKey).filter { !$0.isEmpty })
        SheetPage(title: "Asking to join") {
            Text("Read the sixteen emoji with them, out loud or side by side. They must be the same on both phones.")
                .scaledFont(15).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
            ForEach(requests) { r in
                row(r, sameName: (byName[Self.nameKey(r.name)]?.count ?? 0) > 1, memberName: members.contains(Self.nameKey(r.name)))
            }
        }
        .onChange(of: requests.isEmpty) { _, gone in if gone { dismiss() } }
        .onAppear { if engine.joinRequests(code).isEmpty { dismiss() } }
    }

    @ViewBuilder private func row(_ r: JoinRequest, sameName: Bool, memberName: Bool) -> some View {
        let busy = approving.contains(r.id)
        VStack(alignment: .leading, spacing: 12) {
            if confirming == r.id {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Let \(r.name) in?").scaledFont(17, .bold).foregroundStyle(Theme.text)
                    Text("\(r.name) reads everything written from now on.").scaledFont(15).foregroundStyle(Theme.dim)
                }
                .fixedSize(horizontal: false, vertical: true)
                JoinEmojiRows(pub: r.pub) // still in view: with two requests under one name, the emoji say which one this is
                // Side by side while the name is short ("Cancel" only as wide as its word); a long name gets a line of its own.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) {
                        cancelButton.fixedSize(horizontal: true, vertical: false)
                        yesButton(r, lines: 1)
                    }
                    VStack(spacing: 10) {
                        yesButton(r, lines: 3)
                        cancelButton
                    }
                }
                .disabled(busy)
            } else {
                HStack(spacing: 12) {
                    Text(Self.initial(r.name)).scaledFont(18, .bold).foregroundStyle(Theme.text)
                        .frame(width: 44, height: 44).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(r.name).scaledFont(17, .semibold).foregroundStyle(Theme.text).lineLimit(1)
                        Text("Not checked").scaledFont(14).foregroundStyle(Theme.faint)
                    }
                    .accessibilityElement(children: .combine)
                }
                JoinEmojiRows(pub: r.pub)
                if memberName {
                    Text("A member already uses this name. Compare the emoji.").scaledFont(14, .semibold).foregroundStyle(Theme.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if sameName {
                    Text("Another request uses this name. Compare the emoji.").scaledFont(14, .semibold).foregroundStyle(Theme.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 10) {
                    Button("Let in") { withAnimation(.easeOut(duration: 0.2)) { confirming = r.id } }.buttonStyle(PrimaryButton())
                        .accessibilityLabel("Let \(r.name) in")
                    Button("Not now") { engine.declineJoin(code, r.id) }.buttonStyle(SecondaryButton())
                        .accessibilityLabel("Not now: \(r.name)")
                }
                .disabled(busy)
            }
        }
        .fixedSize(horizontal: false, vertical: true) // its full height: a taller row must not shrink its emoji to make room
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var cancelButton: some View {
        Button { withAnimation(.easeOut(duration: 0.2)) { confirming = nil } } label: { Text("Cancel").padding(.horizontal, 22) }
            .buttonStyle(SecondaryButton())
    }

    private func yesButton(_ r: JoinRequest, lines: Int) -> some View {
        Button { approve(r) } label: {
            Text("Yes, let \(r.name) in").lineLimit(lines).multilineTextAlignment(.center).padding(.horizontal, 12)
        }
        .buttonStyle(PrimaryButton())
    }

    /// The existing approve path (a new key for everyone, them included), with its failure message as before.
    private func approve(_ r: JoinRequest) {
        guard !approving.contains(r.id) else { return }
        approving.insert(r.id)
        engine.approveJoin(code, r.id) { ok in
            approving.remove(r.id)
            if confirming == r.id { confirming = nil }
            if !ok { Nav.shared.toast("Not everyone's keys have reached this phone yet. Try again when they're in reach.") }
        }
    }
}

/// A person's sixteen emoji (spec/FORMAT.md, "Person emoji"), eight to a line and never cut: each line shrinks to the
/// width it gets. VoiceOver says their names, the words people read out.
private struct JoinEmojiRows: View {
    let pub: Data

    var body: some View {
        let ix = MemberKey.emoji(pub)
        let lines = Fingerprint.emojiLines(ix).components(separatedBy: "\n")
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line).scaledFont(26).lineLimit(1).minimumScaleFactor(0.4)
            }
        }
        .fixedSize(horizontal: false, vertical: true) // shrinks only for width, never because the list is short of height
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Fingerprint.spoken(ix))
    }
}
