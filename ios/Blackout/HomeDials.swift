import SwiftUI
import CoreBluetooth
import BlackoutCore

/// Home, inside the hero: one round dial per way messages travel, with one word for its state. White = switched on,
/// charcoal = off; a green dot and a green word = at least one live link. Bluetooth and Radio open Connections (they are
/// switched in Control Center, or need pairing); Wi-Fi and Internet are the app's own switches, flipped right here.
/// Switching one off asks first: on Home a dial is easy to touch by accident, and a link that is off reaches nobody.
struct LinkDials: View {
    @ObservedObject var engine: Engine
    @ObservedObject private var nav = Nav.shared
    @AppStorage("wifiNetwork") private var wifiOn = true
    @AppStorage("internet") private var internetOn = true
    @State private var askingInternetOff = false
    @State private var askingWifiOff = false

    private struct Dial { let on: Bool; var live = false; let word: String }

    var body: some View {
        HStack(alignment: .top, spacing: 4) {
            dial("Bluetooth", "dot.radiowaves.left.and.right", bluetooth, hint: "Opens Connections") { nav.sheet = .radio }
            dial("Wi-Fi", "wifi", wifi, hint: wifiOn ? "Asks before switching Wi-Fi links off" : "Switches Wi-Fi links on") {
                if wifiOn { askingWifiOff = true } else { setWifi(true) }
            }
            dial("Radio", "antenna.radiowaves.left.and.right", radio, hint: "Opens Connections") { nav.sheet = .radio }
            dial("Internet", "globe", internet, hint: internetOn ? "Asks before switching the internet off" : "Switches the internet on") {
                if internetOn { askingInternetOff = true } else { setInternet(true) }
            }
        }
        .fixedSize(horizontal: false, vertical: true) // the words shrink only for width
        .sheet(isPresented: $askingInternetOff) {
            LinkOffSheet(symbol: "globe", title: "Switch the internet off?",
                         text: "You keep the phones nearby. You lose anyone far away until you switch it back on.") { setInternet(false) }
        }
        .sheet(isPresented: $askingWifiOff) {
            LinkOffSheet(symbol: "wifi", title: "Switch Wi-Fi links off?",
                         text: "You lose the phones you reach over a Wi-Fi network until you switch it back on.") { setWifi(false) }
        }
    }

    private func phones(_ n: Int) -> String { n == 1 ? "1 phone" : "\(n) phones" }

    private var bluetooth: Dial {
        switch engine.bluetooth {
        case .unauthorized: return Dial(on: false, word: "Not allowed")
        case .poweredOff, .unsupported: return Dial(on: false, word: "Off")
        default:
            let n = engine.bluetoothLinks
            return Dial(on: true, live: n > 0, word: n > 0 ? phones(n) : "Looking")
        }
    }

    /// Every Wi-Fi link counts: Wi-Fi networks and Apple's peer-to-peer. The switch is the app's own, for Wi-Fi networks.
    private var wifi: Dial {
        let n = engine.wifiLinks
        if n > 0 { return Dial(on: wifiOn, live: true, word: phones(n)) }
        if !wifiOn { return Dial(on: false, word: "Off") }
        // Local Network refused: nothing can be found over Wi-Fi, so "Looking" would not be true. Home's card says how to allow it.
        if engine.localNetworkDenied { return Dial(on: true, word: "Not allowed") }
        return Dial(on: true, word: "Looking")
    }

    private var radio: Dial {
        if engine.radioLinked || engine.meshcoreLinked { return Dial(on: true, live: true, word: "Linked") }
        if engine.lora.hasRadio || engine.meshcore.hasRadio { return Dial(on: true, word: "Not in reach") }
        return Dial(on: false, word: "Not paired")
    }

    private var internet: Dial {
        if !internetOn { return Dial(on: false, word: "Off") }
        // With Tor wanted, nothing counts as online before the check says traffic leaves through Tor (as in Connections).
        if engine.throughTor != false && engine.relaysLinked > 0 {
            return Dial(on: true, live: true, word: engine.throughTor == true ? "Through Tor" : "Online")
        }
        return Dial(on: true, word: "Connecting")
    }

    private func setWifi(_ on: Bool) {
        wifiOn = on; engine.setWifi(on) // as the switch in Connections does
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private func setInternet(_ on: Bool) {
        internetOn = on; engine.setInternet(on) // as the switch in Connections does
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private func dial(_ name: String, _ symbol: String, _ d: Dial, hint: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: symbol).scaledFont(19, .semibold).foregroundStyle(d.on ? Color.black : Theme.faint)
                    .frame(width: 50, height: 50).background(Circle().fill(d.on ? Color.white : Theme.tile))
                    .overlay(alignment: .topTrailing) {
                        if d.live { // ringed in the card's colour so it reads on a white dial too
                            Circle().fill(Theme.safe).frame(width: 10, height: 10)
                                .padding(2.5).background(Circle().fill(Theme.card)).offset(x: 1, y: -1)
                        }
                    }
                    .padding(.bottom, 5)
                Text(name).scaledFont(13, .semibold).foregroundStyle(Theme.text).lineLimit(1).minimumScaleFactor(0.6)
                Text(d.word).scaledFont(12, .medium).foregroundStyle(d.live ? Theme.safe : Theme.dim)
                    .multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.5) // small enough that one long word is never broken
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
        .accessibilityValue(d.word)
        .accessibilityHint(hint)
    }
}

/// Asks before a link goes off (the internet is the only way to anyone far away; Wi-Fi may be the only way to a neighbour).
struct LinkOffSheet: View {
    let symbol: String, title: String, text: String
    let off: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        FittedSheet {
            Image(systemName: symbol).scaledFont(24, .semibold).foregroundStyle(Theme.text)
                .frame(width: 60, height: 60).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
            Text(title).scaledFont(22, .bold).foregroundStyle(Theme.text)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(text)
                .scaledFont(15).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true).padding(.bottom, 6)
            Button("Keep it on") { dismiss() }.buttonStyle(PrimaryButton())
            Button("Switch it off") { off(); dismiss() }.buttonStyle(SecondaryButton())
        }
    }
}

/// Under Home's hero: opens Group security and says how many of its rows need a look (the count that sheet shows).
struct HomeSecurityRow: View {
    @ObservedObject var engine: Engine
    @CurrentCode private var code

    var body: some View {
        let s = engine.securityState(code), n = s.flagged
        let line = !s.inGroup ? GroupSecurityState.outside(removed: engine.removed(code)) : GroupSecurityState.verdict(n)
        Button { Nav.shared.sheet = .security } label: {
            HStack(spacing: 14) {
                Image(systemName: "shield.fill").scaledFont(17, .semibold).foregroundStyle(n == 0 && s.inGroup ? Theme.safe : Theme.text)
                    .frame(width: 42, height: 42).background(Circle().fill(Theme.tile)).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Group security").scaledFont(17, .semibold).foregroundStyle(Theme.text)
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
