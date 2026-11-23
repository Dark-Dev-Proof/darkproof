import Foundation

/// "How it got there" (spec/FORMAT.md, "Journey notes"): what a reader's receipt may say about the way a message took
/// to its phone, and what the writer's phone remembers about how the message left. Same as Android's Journey.
public enum Journey {
    /// The kind of link a packet came or went over. Never which phone, and never a link id.
    public enum Link: Equatable, Sendable {
        case bluetooth, wifi, direct, radio, internet
        /// A letter this app doesn't know: a newer app's link. Read, never written.
        case other

        /// `b` Bluetooth, `w` a Wi-Fi network, `p` phone-to-phone Wi-Fi (Apple peer-to-peer, Nearby), `r` LoRa radio,
        /// `i` an internet relay. Any other lowercase letter a–z is `.other`; anything else is no link.
        public init?(letter: UInt8) {
            switch letter {
            case UInt8(ascii: "b"): self = .bluetooth
            case UInt8(ascii: "w"): self = .wifi
            case UInt8(ascii: "p"): self = .direct
            case UInt8(ascii: "r"): self = .radio
            case UInt8(ascii: "i"): self = .internet
            case UInt8(ascii: "a")...UInt8(ascii: "z"): self = .other
            default: return nil
            }
        }

        /// The letter it is written as; nil for `.other`, which is never written.
        public var letter: Character? {
            switch self {
            case .bluetooth: return "b"
            case .wifi: return "w"
            case .direct: return "p"
            case .radio: return "r"
            case .internet: return "i"
            case .other: return nil
            }
        }

        /// As the screen says it.
        public var over: String {
            switch self {
            case .bluetooth: return "Over Bluetooth"
            case .wifi: return "Over a Wi-Fi network"
            case .direct: return "Over phone-to-phone Wi-Fi"
            case .radio: return "Over the radio"
            case .internet: return "Over the internet"
            case .other: return "Over another link"
            }
        }
    }

    /// Most hops a note can say: a new packet starts with this many (`MeshRouter.maxTtl`).
    public static let maxHops = 16

    /// A journey note, `h<hops><link>`: after about how many hops, and over which kind of link, the first copy of a
    /// message reached the reader's phone. The ttl it is counted from is not authenticated: hops are "about".
    public struct Note: Equatable, Sendable {
        public let hops: Int
        public let link: Link

        /// What a reader's phone notes for a message whose first copy came with `ttl` hops left over `link`: 16 minus
        /// the ttl, not below 0. Nil when the link isn't one this app writes.
        public init?(ttl: Int, link: Link) {
            guard link.letter != nil else { return nil }
            hops = min(Journey.maxHops, max(0, MeshRouter.maxTtl - ttl)); self.link = link
        }

        /// Reads a token: `h`, 1 or 2 ASCII digits, one lowercase letter a–z, nothing else. Nil if it doesn't parse
        /// or says more than 16 hops.
        public init?(token: some Collection<UInt8>) {
            let b = Array(token)
            guard b.count == 3 || b.count == 4, b[0] == UInt8(ascii: "h"), let link = Link(letter: b[b.count - 1]) else { return nil }
            var n = 0
            for d in b[1..<(b.count - 1)] {
                guard (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(d) else { return nil }
                n = n * 10 + Int(d - UInt8(ascii: "0"))
            }
            guard n <= Journey.maxHops else { return nil }
            hops = n; self.link = link
        }

        /// As it is written, without a leading zero; nil for a link this app doesn't write.
        public var token: String? { link.letter.map { "h\(hops)\($0)" } }

        /// "Over Bluetooth · about 3 hops". Without a hop count when the note says none.
        public var text: String {
            hops == 0 ? link.over : "\(link.over) · about \(hops) \(hops == 1 ? "hop" : "hops")"
        }
    }

    /// Over which kind of link one of this phone's messages was first handed on, and when by this phone's clock: kept
    /// next to what the phone remembers for "Sent".
    public struct Left: Equatable, Sendable {
        public let link: Link
        public let at: Int64
        public init(link: Link, at: Int64) { self.link = link; self.at = at }

        /// As it is saved: the link's letter, then the time in ms. Nil for a link that has no letter.
        public var saved: String? { link.letter.map { "\($0)\(at)" } }

        public init?(saved: String) {
            guard let first = saved.utf8.first, let link = Link(letter: first), link != .other, let at = Int64(saved.dropFirst()), at >= 0 else { return nil }
            self.link = link; self.at = at
        }
    }

    public static let sealed = "Sealed the whole way"
    /// Not "nobody sees whose they are": a phone linked to a carrier, or a relay without Tor, can see an address. What
    /// is true is that the message and the receipt name no carrier.
    public static let carried = "Carried by other phones on the way. They can't read it, and DarkProof never notes whose they are."
    /// How long a packet is passed on (`MeshRouter`, spec "Forwarding rules").
    static let passedOnMs: Int64 = 7 * 24 * 3_600_000

    /// One line of the "How it got there" screen. `detail`: the line under the title, if there is one. `at`: a time
    /// to show (ms): when it left, or when the reader's receipt was written. `later`: "4 min later".
    public struct Step: Equatable, Sendable {
        public enum Kind: Sendable { case saved, left, reached, unconfirmed }
        public let kind: Kind, title: String, detail: String?, at: Int64?, later: String?
    }

    /// How long after the message a receipt was written, by the two phones' clocks: "Less than a minute later",
    /// "4 min later", "3 h later", "2 days later". Nil when the receipt is dated before the message: the clocks differ.
    public static func later(_ ms: Int64) -> String? {
        guard ms >= 0 else { return nil }
        let min = ms / 60_000
        if min < 1 { return "Less than a minute later" }
        if min < 60 { return "\(min) min later" }
        if min < 48 * 60 { return "\(min / 60) h later" }
        return "\(min / (24 * 60)) days later"
    }

    /// The lines of the screen for one of this phone's own group messages, top to bottom, without the last one (`sealed`).
    /// `written`: the message's time. `handedOff`: it was given to a phone, a radio or a relay ("Sent"); `left`: over what
    /// and when, if the phone noted it. `readers`: who confirmed it, earliest first (`Deliveries`).
    public static func steps(written: Int64, handedOff: Bool, left: Left?, readers: [Reader], now: Int64) -> [Step] {
        guard handedOff || !readers.isEmpty else {
            // Packets are passed on for 7 days: after that this one no longer leaves.
            let late = now - written > passedOnMs
            return [Step(kind: .saved, title: "Saved on this phone",
                         detail: late ? "No phone was in reach for 7 days. It is no longer passed on." : "No phone is in reach yet. It leaves by itself when one is.",
                         at: written, later: nil)]
        }
        var out = [Step(kind: .left, title: "Left your phone", detail: left?.link.over, at: left?.at, later: nil)]
        for r in readers {
            out.append(Step(kind: .reached, title: "Reached \(r.name.isEmpty ? "someone" : r.name)", detail: r.note?.text, at: r.ts, later: later(r.ts - written)))
        }
        if readers.isEmpty { out.append(Step(kind: .unconfirmed, title: "Not confirmed by anyone yet", detail: nil, at: nil, later: nil)) }
        return out
    }

    /// True when the line about other phones belongs on the screen: a reader's note says 2 hops or more.
    public static func wasCarried(_ readers: [Reader]) -> Bool { readers.contains { ($0.note?.hops ?? 0) >= 2 } }
}
