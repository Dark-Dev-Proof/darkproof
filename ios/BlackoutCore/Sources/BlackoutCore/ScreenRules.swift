import Foundation

// Small rules of the iPhone's screens, kept here so that the tests can check them: what Finder says once the search is
// over and after "I'm with Marco", how swipe-to-reply decides, and what a voice note does when the phone's audio is
// taken away. The views only call these.

// MARK: - Finder's words (spec/FORMAT.md, "Finder")

/// What the Finder screen says beyond the band's word and line. Same words as Android's `FinderWords`.
public enum FinderWords {
    /// The button, and the plain group message it sends: no `✅` or `🆘`, so it is never taken for a check-in.
    public static func withThem(_ name: String) -> String { "I'm with \(name)" }

    /// The toast after "I'm with Marco": an ordinary group message, which waits on this phone with nothing in reach.
    /// Never "your group is told": the message leaving this phone isn't the group getting it.
    public static func withThemSent(group: String, waiting: Bool) -> String {
        waiting ? "Saved. It goes out when a phone or the internet is in reach." : "Sent to \(group)"
    }

    /// The one card left once the search is over: "I'm safe" came (`safe`), or the latest "I need help" carries no
    /// note (rule F6). The readings stop.
    public static func ended(_ name: String, safe: Bool) -> String {
        safe ? "\(name) is safe" : "\(name)'s phone can't be found by Bluetooth right now. A new “I need help” from \(name) makes it findable again."
    }

    /// Why there is no reading while nothing links the two phones by Bluetooth.
    public static func notLinked(_ name: String) -> String {
        "Find by Bluetooth works only when this phone and \(name)'s are linked by Bluetooth. If \(name)'s phone restarted after asking, it can't be recognised until it sends a new check-in."
    }

    /// Rule F6 on the screen: the search goes on only while the person's latest check-in is their own "I need help"
    /// with the note (not a report about them). Anything else ends it.
    public static func searching(_ p: Person) -> Bool { !p.isReport && p.checkIn == .help && p.finder != nil }

    /// The link the open screen looks for: the latest "I need help"'s note while `searching`, else nil (the search is
    /// over). A new "I need help" after the screen showed the search as over starts it again, with the link its note
    /// names, as on Android (screens re-check, finding 3).
    public static func looksFor(_ p: Person) -> String? { searching(p) ? p.finder : nil }
}

// MARK: - Swipe to reply

/// One touch of swipe-to-reply on a group message: dragged to the right past `threshold` and lifted, it replies. Only a
/// drag that begins sideways and to the right counts; a touch that also moves a voice note's bars (`scrubbing`) never
/// does, for the rest of that touch; a touch the system takes away (a call, an alert, a system gesture) never replies.
public struct SwipeReply: Equatable, Sendable {
    public static let threshold = 48.0
    /// Decided at the start of the touch: sideways to the right (a reply) or not.
    public private(set) var across: Bool?
    /// Past the threshold: lifting the finger now replies.
    public private(set) var armed = false
    /// How far the message is drawn to the right.
    public private(set) var moved = 0.0

    public init() {}

    /// The finger is `width`, `height` from where the touch began. True the moment it passes the threshold (the tap).
    public mutating func drag(width w: Double, height h: Double, scrubbing: Bool) -> Bool {
        if scrubbing { across = false; armed = false; moved = 0; return false }
        if across == nil { across = w > 0 && w > abs(h) * 1.5 }
        guard across == true else { return false }
        let way = max(0, w), t = Self.threshold
        // With the finger up to the threshold, then a little more slowly, and never more than half as far again.
        moved = way <= t ? way : min(t * 1.5, t + (way - t) * 0.35)
        let past = way >= t, tap = past && !armed
        armed = past
        return tap
    }

    /// The finger lifted: true if that replies. The touch is over either way.
    public mutating func lifted() -> Bool {
        let replies = across == true && armed
        self = SwipeReply()
        return replies
    }

    /// The system took the touch away: no reply, the message goes back.
    public mutating func cancelled() { self = SwipeReply() }
}

// MARK: - Voice notes and the phone's audio

/// What can take the phone's audio away from a voice note being played or recorded.
public enum VoiceBreak: Equatable, Sendable {
    /// A call, an alarm or another app's audio began (AVAudioSession's interruption, began).
    case interruptionBegan
    /// It ended: nothing comes back by itself.
    case interruptionEnded
    /// The route lost a device: headphones pulled out, AirPods gone.
    case deviceGone
    /// The route got a new device: headphones in, AirPods connected.
    case deviceAdded
    /// Any other route change (a category set by this app, an override, the phone waking): nothing to do.
    case otherRoute
    /// The audio engine playing or recording changed its setup; `running`: it still runs (it stops by itself when the
    /// hardware changed under it).
    case engineChanged(running: Bool)
}

/// What a voice note does about a `VoiceBreak`: a note playing pauses and keeps its place; a recording is cancelled
/// (with "Voice note cancelled"), never sent with a silent or missing part.
public struct VoiceBreakAction: Equatable, Sendable {
    public let pause: Bool, cancel: Bool
    public init(pause: Bool, cancel: Bool) { self.pause = pause; self.cancel = cancel }

    public static func of(_ b: VoiceBreak, playing: Bool, recording: Bool) -> VoiceBreakAction {
        switch b {
        case .interruptionBegan, .deviceGone, .engineChanged(running: false): return VoiceBreakAction(pause: playing, cancel: recording)
        // New headphones carry on playing (the engine reports it if it had to stop); a recording's input may have moved.
        case .deviceAdded: return VoiceBreakAction(pause: false, cancel: recording)
        case .interruptionEnded, .otherRoute, .engineChanged(running: true): return VoiceBreakAction(pause: false, cancel: false)
        }
    }
}
