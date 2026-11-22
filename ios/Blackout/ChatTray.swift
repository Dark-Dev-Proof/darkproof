import SwiftUI
import BlackoutCore

/// A short sheet from the bottom, as tall as what is in it: charcoal, with a grabber, the screen it belongs to still
/// showing above. If its content is taller than the screen (very large text), it scrolls.
struct FittedSheet<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @State private var height: CGFloat = 300

    var body: some View {
        ScrollView {
            VStack(spacing: 14) { content() }
                .padding(.horizontal, 20).padding(.top, 30).padding(.bottom, 14)
                .frame(maxWidth: .infinity)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.height(height)])
        .presentationDragIndicator(.visible)
        .presentationBackground(Theme.card)
        .preferredColorScheme(.dark)
        .tint(Theme.text)
        .modifier(ToastOverlay()) // confirmations show over sheets too
    }
}

/// The "+" in the group composer: what isn't typed, as round buttons four to a row. Photo and Camera do what the camera
/// menu did; Voice note says how a voice note is recorded (by holding the mic); the rest are Home's "I'm safe", the
/// "I need help" flow and the "Are you safe?" sheet.
struct ChatTray: View {
    enum Choice { case photo, camera, voice, safe, help, askSafe, timer }
    @ObservedObject var engine: Engine
    let pick: (Choice) -> Void

    var body: some View {
        let busy = engine.checkInSending != nil // a check-in is waiting for its position: no second one, as on Home
        FittedSheet {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6, alignment: .top), count: 4), spacing: 18) {
                item("Photo", "photo.on.rectangle", Theme.text) { pick(.photo) }
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    item("Camera", "camera.fill", Theme.text) { pick(.camera) }
                }
                if VoiceAudio.shared.canRecord { item("Voice note", "mic.fill", Theme.text) { pick(.voice) } }
                item(engine.checkInSending == .safe ? "Sending…" : "I'm safe", "checkmark.shield.fill", Theme.safe) { pick(.safe) }
                    .disabled(busy)
                // The widest symbol: smaller, so it stays inside its circle with large text.
                item("I need help", "sos", Theme.help, size: 16) { pick(.help) }
                    .disabled(busy)
                item("Ask if safe", "questionmark.bubble.fill", Theme.text) { pick(.askSafe) }
                // The group's timer: admins choose in the sheet behind it, everyone else reads the value there.
                item("Timer", "timer", Theme.text) { pick(.timer) }
            }
        }
    }

    private func item(_ label: String, _ symbol: String, _ tint: Color, size: CGFloat = 22, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol).scaledFont(size, size < 20 ? .heavy : .semibold).foregroundStyle(tint)
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
