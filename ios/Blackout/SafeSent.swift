import SwiftUI

/// The "I'm safe" sent moment, the same as Android's (Home's tile, People's tile, the chat's "+" tray): each time the
/// count of "I'm safe" sent from there goes up, the check mark draws itself (SF Symbols' Draw On) and one green ring
/// grows once from the button and fades, about half a second. Once: nothing loops. The screen gives the one haptic
/// itself (`.sensoryFeedback(.success)`, so the engine doesn't), also when calm. Calm: Reduce Motion, Low Power Mode
/// or DarkProof's battery saver; then nothing moves and the tile only changes as it always did.
enum SafeSent {
    /// How long the ring takes; the tray stays open for it.
    static let seconds = 0.55
    static func calm(_ reduceMotion: Bool) -> Bool { reduceMotion || Theme.lowPower || Engine.saver }
}

/// The check-in symbol (`checkmark.shield.fill`), drawn again each time `sent` goes up.
struct SafeCheck: View {
    let sent: Int
    @State private var drawn = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Image(systemName: "checkmark.shield.fill")
            .transition(AsymmetricTransition(insertion: .symbolEffect(.drawOn, options: .speed(2)), removal: .identity))
            .id(drawn) // a new symbol, which draws itself in; the one before goes at once
            .onChange(of: sent) { if !SafeSent.calm(reduceMotion) { withAnimation { drawn += 1 } } }
    }
}

/// One green ring in `shape` behind the view, grown by `grow` (0.5: half as big again) and faded each time `sent` goes up.
struct SafeRing<S: Shape>: ViewModifier {
    let shape: S
    let grow: CGFloat
    let sent: Int
    @State private var played = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .background {
                shape.stroke(Theme.safe, lineWidth: 2)
                    .keyframeAnimator(initialValue: 1.0, trigger: played) { [grow] ring, r in
                        ring.scaleEffect(1 + grow * r).opacity(r < 1 ? 0.9 * (1 - r) : 0) // 1: gone, also between moments
                    } keyframes: { _ in
                        KeyframeTrack {
                            MoveKeyframe(0)
                            LinearKeyframe(1, duration: SafeSent.seconds, timingCurve: .easeOut)
                        }
                    }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .onChange(of: sent) { if !SafeSent.calm(reduceMotion) { played += 1 } }
    }
}

extension View {
    /// The "I'm safe" moment's ring around this view (`SafeRing`).
    func safeRing<S: Shape>(_ shape: S, grow: CGFloat, sent: Int) -> some View {
        modifier(SafeRing(shape: shape, grow: grow, sent: sent))
    }
}
