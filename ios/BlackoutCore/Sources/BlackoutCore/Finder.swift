import Foundation

// MARK: - Finder (spec/FORMAT.md, "Finder")

/// The finder note: a "needs help" check-in (`🆘`) may carry the sender's current link id in its 5th field, type `0x06`
/// and the 6 raw bytes of the link id, so that group members nearby can tell which Bluetooth neighbour is the person
/// asking for help (rules F1 to F6). Same as Android's.
public enum FinderNote {
    public static let type: UInt8 = 0x06
    /// Type (1), link id (6).
    public static let size = 7
    /// What the note adds to a check-in's body: the empty 4th field's length, the 5th field's length, the field.
    public static let cost = 2 + 2 + size
    /// The settings switch "Let my group find this phone" (default on; off in high-risk mode whatever it says).
    public static let settingKey = "finderNote"
    /// The trial switch: the Finder screen is shown only while it is on (default off), until the real-phone tests pass.
    /// The note is read and written either way.
    public static let trialKey = "finderTrial"
    /// Rule F6: an own "I need help" this young, not followed by "I'm safe", is sent again after a restart, at most once
    /// every `resendEveryMs`, so the link id matches again.
    public static let resendWithinMs: Int64 = 24 * 3_600_000
    public static let resendEveryMs: Int64 = 10 * 60_000

    /// The field for `linkId` (12 lower-case hex characters, as in "Link ids"); nil for anything else.
    public static func field(linkId: String) -> Data? {
        guard linkId.count == 12, linkId.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) else { return nil }
        return Data([type]) + Hex.decode(linkId)
    }

    /// Rule F4 (the reader side): the link id a 5th field carries, as 12 lower-case hex characters: only on a "needs
    /// help" check-in (text starting with `🆘`), only exactly 7 bytes of type `0x06`. Whether the packet opened with a
    /// group's key and its signature checked is the caller's to add (`Person.all` takes it only from a checked group
    /// message).
    public static func read(field: Data?, text: String) -> String? {
        guard let field, field.count == size, field.first == type, CheckIn.of(text) == .help else { return nil }
        return Hex.encode(Data(field.dropFirst()))
    }

    /// Rules F1, F2: whether this phone adds the note to a message it writes: only to its own `🆘` check-ins in a group,
    /// with the switch on and high-risk mode off.
    public static func writes(text: String, isPublic: Bool, on: Bool, highRisk: Bool) -> Bool {
        !isPublic && on && !highRisk && CheckIn.of(text) == .help
    }

    /// Rule F3, radio first: false only when the check-in would fit a 163-byte LoRa packet without the note and not with
    /// it. `keyed`: sealed with its own message key (3 more bytes of header, spec "Message keys").
    public static func fits(sender: String, name: String, text: String, keyed: Bool) -> Bool {
        let without = Packet.overhead + (keyed ? 3 : 0) + 6 + sender.utf8.count + name.utf8.count + text.utf8.count
        return !(without <= MeshCore.payloadMax && without + cost > MeshCore.payloadMax)
    }

    /// Rule F6: whether to send this phone's own "I need help" again now, after a restart. `lastOwn`: this phone's newest
    /// own check-in in the group (its kind and time); `lastResend`: when this phone last did so for the group.
    public static func resends(lastOwn: (kind: CheckIn, ts: Int64)?, lastResend: Int64?, now: Int64, on: Bool, highRisk: Bool) -> Bool {
        guard on, !highRisk, let own = lastOwn, own.kind == .help else { return false }
        guard now - own.ts >= 0, now - own.ts < resendWithinMs else { return false }
        if let r = lastResend, now - r >= 0, now - r < resendEveryMs { return false }
        return true
    }
}

/// How strong the asker's signal is on this phone: a word, never a distance (spec "Finder", rules F7 to F12).
public enum FinderBand: Int, Comparable, Sendable, CaseIterable {
    case notInReach = 0, far, near, veryClose, here

    public static func < (a: FinderBand, b: FinderBand) -> Bool { a.rawValue < b.rawValue }

    /// The big word on the screen.
    public var word: String {
        switch self {
        case .notInReach: return "Not in reach"
        case .far: return "Far"
        case .near: return "Near"
        case .veryClose: return "Very close"
        case .here: return "Here"
        }
    }

    /// The line under it, for someone called `name`.
    public func hint(_ name: String) -> String {
        switch self {
        case .notInReach: return "Keep moving. This phone hasn't heard \(name)'s phone yet."
        case .far: return "Keep walking and watch the signal."
        case .near: return "Keep going the way the signal gets stronger."
        case .veryClose: return "Within a few rooms. Call out and listen."
        case .here: return "Within a few steps. Look around."
        }
    }

    /// The letter in `kind: finder-bands` vectors.
    public var code: Character { ["-", "F", "N", "V", "H"][rawValue] }

    /// The lower edge in dBm of a band you can be in by signal (rule F10).
    var edge: Double {
        switch self {
        case .notInReach, .far: return -.infinity
        case .near: return -82
        case .veryClose: return -70
        case .here: return -60
        }
    }

    /// Rule F10: the band a smoothed value falls in.
    static func level(_ s: Double) -> FinderBand { s >= -60 ? .here : s >= -70 ? .veryClose : s >= -82 ? .near : .far }
}

/// Stronger or weaker than a moment ago (rule F12). There is no direction.
public enum FinderTrend: Sendable {
    case stronger, weaker, steady

    public var words: String {
        switch self {
        case .stronger: return "Signal getting stronger"
        case .weaker: return "Signal getting weaker"
        case .steady: return "Signal steady"
        }
    }

    /// The sign in `kind: finder-bands` vectors.
    public var code: Character { self == .stronger ? "+" : self == .weaker ? "-" : "=" }
}

/// Turns one signal reading a second into a band and a trend (rules F7 to F12). Pure: the same readings give the same
/// bands on every platform, which `kind: finder-bands` checks. Call `step` once a second, with the second's number
/// (increasing by one) and the reading taken in it, or nil.
public struct FinderMeter: Sendable {
    public struct Reading: Equatable, Sendable {
        public let band: FinderBand
        public let trend: FinderTrend?
        /// The smoothed value in dBm; nil when not in reach. For the debug line only: never shown as a distance.
        public let smoothed: Double?
        public init(band: FinderBand, trend: FinderTrend?, smoothed: Double?) { self.band = band; self.trend = trend; self.smoothed = smoothed }
    }

    /// Rule F7: a reading outside this range is none (iOS gives 127 for "not available", some stacks 0).
    public static let lowest = -110, highest = -20
    /// Rule F8: no reading for this many seconds: "Not in reach", and everything starts over.
    public static let lostAfter = 10
    /// Rule F9: the median of the readings of the last 5 seconds.
    public static let window = 5
    /// Rule F11: "Here" needs the smoothed value at its edge or above for 3 seconds in a row.
    public static let hold = 3
    /// Rule F11: a band is left downwards only 3 dB below its lower edge.
    public static let margin = 3.0

    private var readings: [(t: Int, dbm: Int)] = []
    private var smoothed: Double?
    private var band: FinderBand?
    private var held = 0

    public init() {}

    /// Rule F7.
    public static func counts(_ dbm: Int) -> Bool { dbm >= lowest && dbm <= highest }

    public mutating func step(_ t: Int, _ dbm: Int?) -> Reading {
        let fresh = dbm.map(Self.counts) ?? false
        if fresh, let d = dbm { readings.append((t, d)) }
        readings.removeAll { $0.t <= t - Self.lostAfter } // only the last 10 seconds matter
        // F8: no reading for 10 s (or none yet).
        guard let last = readings.last?.t, t - last < Self.lostAfter else {
            smoothed = nil; band = nil; held = 0
            return Reading(band: .notInReach, trend: nil, smoothed: nil)
        }
        // F9: at a second with a reading, the median of the last 5 seconds' readings, then an exponential average.
        if fresh {
            let recent = readings.filter { $0.t > t - Self.window }.map(\.dbm).sorted(), n = recent.count
            let m = n % 2 == 1 ? Double(recent[n / 2]) : Double(recent[n / 2 - 1] + recent[n / 2]) / 2
            smoothed = smoothed.map { 0.3 * m + 0.7 * $0 } ?? m
        }
        let s = smoothed ?? Double(readings.last!.dbm)
        // F11: the hold for "Here", and the band with its margin downwards.
        held = s >= FinderBand.here.edge ? held + 1 : 0
        let level = FinderBand.level(s)
        let target = min(level, held >= Self.hold ? .here : .veryClose)
        if let b = band {
            if target > b { band = target } else if level < b, s < b.edge - Self.margin { band = level }
        } else { band = target }
        // F12: the mean of the last 5 seconds' readings against the 5 before.
        let a = readings.filter { $0.t > t - Self.window }.map(\.dbm), b = readings.filter { $0.t <= t - Self.window }.map(\.dbm)
        var trend: FinderTrend?
        if !a.isEmpty, !b.isEmpty {
            let d = Double(a.reduce(0, +)) / Double(a.count) - Double(b.reduce(0, +)) / Double(b.count)
            trend = d >= 4 ? .stronger : d <= -4 ? .weaker : .steady
        }
        return Reading(band: band ?? .notInReach, trend: trend, smoothed: s)
    }

    /// Every second of a trace: what `kind: finder-bands` lists.
    public static func run(_ trace: [Int?]) -> [Reading] {
        var m = FinderMeter()
        return trace.enumerated().map { m.step($0.offset, $0.element) }
    }
}
