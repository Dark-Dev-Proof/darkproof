import Foundation

// Rules of "Lock DarkProof" and of the one-tap answers, kept in the core so unit tests run. The Android app follows
// the same ones (LockRules.kt).

/// When "Lock DarkProof" asks again on coming back.
public enum LockGrace {
    public static let seconds: Double = 60

    /// `awaySeconds`: how long the app was away since it was last left unlocked, on a clock nobody can set (the app
    /// uses ContinuousClock, which keeps counting while the phone sleeps); nil if it wasn't left since. A time that
    /// can't be (below zero) asks again.
    public static func asksAgain(unlockedOnce: Bool, awaySeconds: Double?) -> Bool {
        !unlockedOnce || awaySeconds.map { $0 < 0 || $0 >= seconds } == true
    }
}

/// Where a one-tap answer under an "Are you safe?" alert goes. Alerts name their group by a tag of its code.
public enum AnswerGroup {
    public enum To: Equatable {
        /// The saved group's code now; nil: the group in use (the alert named no group).
        case group(String?)
        /// The group that asked isn't saved on this phone any more: the answer goes nowhere, not to another group.
        case gone
    }

    /// `tag`: the alert's tag ("" or nil: none). `saved`: tag -> code of each saved group. `moves`: what `noting` kept.
    public static func of(tag: String?, saved: [String: String], moves: [String]) -> To {
        guard var tag, !tag.isEmpty else { return .group(nil) }
        var seen: Set<String> = []
        while seen.insert(tag).inserted {
            if let code = saved[tag] { return .group(code) }
            guard let next = moves.last(where: { $0.hasPrefix(tag + ">") }) else { break }
            tag = String(next.dropFirst(tag.count + 1))
        }
        return .gone
    }

    /// The most moves kept: a group gets a new code only when someone is removed.
    public static let maxMoves = 200

    /// `moves` with "the group tagged `from` is now tagged `to`" added, newest last, as a plain array UserDefaults can save.
    public static func noting(from: String, to: String, in moves: [String]) -> [String] {
        Array((moves.filter { !$0.hasPrefix(from + ">") } + [from + ">" + to]).suffix(maxMoves))
    }
}

/// "I'm safe" and "I need help" given without opening DarkProof: from an alert's answer ("Are you safe?", the check-in
/// clock's reminder), Siri, the Action button or the Shortcuts app (spec "If I go quiet", Q8).
public enum SafeAnswer {
    /// Whether the phone must be unlocked before it sends `c` from outside the app. "I'm safe" always: it stops the
    /// sender's check-in clock on every phone (rule Q4) and answers "Are you safe?", so whoever holds a locked phone must
    /// not send it. "I need help" never: it is made to work from a locked phone.
    public static func needsUnlock(_ c: CheckIn) -> Bool { c == .safe }

    /// How long an answer from an alert keeps DarkProof running in the background for a phone, a radio or a relay to
    /// take it: iOS may suspend the app as soon as the answer is reported done.
    public static let waitSeconds: Double = 20

    public enum Outcome: Equatable, Sendable {
        /// A phone, a radio or a relay took it ("Sent").
        case left
        /// Written, but nothing took it within `waitSeconds`: it waits on this phone.
        case waiting
        /// Not written: another check-in was still being written, or DarkProof had stopped.
        case notSent
    }

    /// While an answer waits: nil to keep waiting, else how it ended. `handedOff`: a link took it; `elapsed`: seconds
    /// since it was written.
    public static func outcome(handedOff: Bool, elapsed: Double) -> Outcome? {
        if handedOff { return .left }
        return elapsed >= waitSeconds ? .waiting : nil
    }

    /// What the alert says when the answer didn't leave the phone; nil when it did. It never says the group got it.
    public static func line(_ o: Outcome, _ c: CheckIn) -> String? {
        let what = c == .safe ? "“I'm safe”" : "“I need help”"
        switch o {
        case .left: return nil
        case .waiting: return "\(what) is waiting on this phone. Open DarkProof so it can go."
        case .notSent: return "\(what) wasn't sent. Open DarkProof to send it."
        }
    }
}
