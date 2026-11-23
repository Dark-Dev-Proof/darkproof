import SwiftUI
import BlackoutCore

extension Engine {
    /// The reach line's words and whether something carries messages right now (only then is its dot green).
    /// Nothing linked: own messages still on this phone come first, then what stops phones from finding each other.
    var reachStatus: (text: String, live: Bool) {
        switch reach {
        case .phones(let n): return ("\(n) \(n == 1 ? "phone" : "phones") in reach" + (relaysLinked > 0 ? " · online" : ""), true)
        case .online: return (throughTor == true ? "Online through Tor" : "Online", true)
        case .radio: return ("LoRa radio linked", true)
        case .none:
            let n = waitingCount
            if n > 0 { return ("Messages waiting: \(n)", false) }
            if let p = setupProblem { return (p.title, false) }
            return ("Looking for phones", false)
        }
    }

    /// My messages written since the app started that no phone, radio or relay has taken yet, in every group (`delivery`).
    var waitingCount: Int {
        allMessages.reduce(0) { $0 + ($1.sender == deviceId && $1.ts >= startedAt && delivery(of: $1) == .waiting ? 1 : 0) }
    }
}

/// One line above the tab bar (iOS 26 `tabViewBottomAccessory`): how far messages reach right now, in plain words.
/// Tapping it opens Connections. Static text, no animation; with the tab bar minimised it shows nothing.
struct ReachLine: View {
    @ObservedObject var engine: Engine
    @ObservedObject private var nav = Nav.shared
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @Environment(\.accessibilityReduceTransparency) private var solid

    var body: some View {
        if placement != .inline { line }
    }

    private var line: some View {
        let s = engine.reachStatus
        return Button { nav.sheet = .radio } label: {
            HStack(spacing: 10) {
                Circle().fill(s.live ? Theme.safe : Theme.dim).frame(width: 8, height: 8)
                Text(s.text).scaledFont(15, .semibold).foregroundStyle(Theme.text)
                    .lineLimit(1).minimumScaleFactor(0.8)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").scaledFont(13, .bold).foregroundStyle(Theme.decor)
            }
            .padding(.horizontal, 18)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background { if solid { Capsule().fill(Theme.card) } } // Reduce Transparency: solid charcoal, not glass
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(s.text)
        .accessibilityHint("Opens Connections")
        .accessibilityAddTraits(.isButton)
    }
}

extension View {
    /// The reach line under every tab. Hidden while `shown` is false (a sheet or the keyboard is up); iOS 26.0 has no
    /// switch for it (`isEnabled` came in 26.1), so there the line always shows.
    @ViewBuilder func reachAccessory(_ engine: Engine, shown: Bool) -> some View {
        if #available(iOS 26.1, *) {
            tabViewBottomAccessory(isEnabled: shown) { ReachLine(engine: engine) }
        } else {
            tabViewBottomAccessory { ReachLine(engine: engine) }
        }
    }
}
