import Foundation

/// A person code (spec/FORMAT.md, "Person code"): one member's public key in one group, as text. A phone shows its own as
/// a QR code; another phone, held next to it, scans it and compares every byte with the key it holds for that person.
/// Optical only: it is never sent over the mesh. It carries exactly the bytes the person emoji are made from
/// (`MemberKey.emoji`), so both checks are about the same key. Same as Android's PersonCode.
public enum PersonCode {
    public static let prefix = "DPK1:"
    /// A member's public key: the 33-byte compressed point (spec "Member ids").
    public static let keySize = 33
    /// 33 bytes in base64url without padding: 44 characters with no bits left over, so a key has exactly one code.
    public static let bodyLength = 44

    /// True if `key` has the shape of a compressed point: 33 bytes, the first `02` or `03`.
    private static func isKey(_ key: Data) -> Bool { key.count == keySize && (key.first == 2 || key.first == 3) }

    /// `DPK1:` and the key in base64url without padding, or nil if `pub` isn't a compressed point's 33 bytes.
    public static func encode(_ pub: Data) -> String? {
        guard isKey(pub) else { return nil }
        let body = pub.base64EncodedString().utf8.map { $0 == 43 ? 45 : $0 == 47 ? 95 : $0 } // "+" → "-", "/" → "_"; 33 bytes need no "="
        return prefix + String(decoding: body, as: UTF8.self)
    }

    /// The public key in `text`, or nil if it isn't a person code: the prefix, then exactly 44 characters of the base64url
    /// alphabet, which give 33 bytes that start with `02` or `03`. Nothing is trimmed or guessed: a QR code holds it exactly.
    public static func decode(_ text: String) -> Data? {
        let all = Array(text.utf8), head = Array(prefix.utf8)
        guard all.count == head.count + bodyLength, all.starts(with: head) else { return nil }
        var body = [UInt8](); body.reserveCapacity(bodyLength)
        for c in all.dropFirst(head.count) {
            switch c {
            case 65...90, 97...122, 48...57: body.append(c) // A-Z a-z 0-9
            case 45: body.append(43)                        // "-" is base64's "+"
            case 95: body.append(47)                        // "_" is base64's "/"
            default: return nil
            }
        }
        guard let key = Data(base64Encoded: Data(body)), isKey(key) else { return nil }
        return key
    }

    /// What a scan says about one person.
    public enum Scan: Equatable, Sendable {
        /// Every byte equals the key this phone holds for the person.
        case same
        /// The phone scanned its own code.
        case own
        /// A person code, of another key.
        case other
        /// Not a person code at all.
        case notACode
    }

    /// Compares what was scanned with `theirs`, the key this phone holds for the person being checked. `mine`: this
    /// phone's own key in the group, if it is ready. Public keys aren't secret, so a plain comparison of all bytes.
    public static func check(_ scanned: String, theirs: Data, mine: Data?) -> Scan {
        guard let key = decode(scanned) else { return .notACode }
        if key == theirs { return .same }
        if let mine, key == mine { return .own }
        return .other
    }
}

/// How a checked person was checked (spec "Person code"), kept on this phone only, next to the checked marks (`Checked`).
extension Checked {
    /// `emoji`: the user compared the sixteen emoji. `inPerson`: the user scanned the person's code from their phone.
    public enum How: String, Equatable, Sendable { case emoji, inPerson }

    /// "<group>|<member id>": the entry of someone checked in person. A checked person without one was checked by emoji,
    /// so every mark made before this was kept counts as emoji.
    public static func inPersonEntry(group: String, id: String) -> String { "\(group)|\(id)" }

    /// How `id` of `group` was checked, or nil if they aren't checked. An in-person entry alone says nothing: the mark decides.
    public static func how(_ checked: [String], inPerson: [String], group: String, id: String) -> How? {
        guard checked.contains(where: { $0.hasPrefix("\(group)|\(id)|") }) else { return nil }
        return inPerson.contains(inPersonEntry(group: group, id: id)) ? .inPerson : .emoji
    }

    /// `inPerson` once `id` of `group` was checked `how`; `checked`: the marks after that check (`marked`). The last check
    /// says how. An entry whose mark is gone goes too: a name's older key, replaced by its new one, takes its entry with it.
    public static func markedHow(_ inPerson: [String], checked: [String], group: String, id: String, how: How) -> [String] {
        let entry = inPersonEntry(group: group, id: id)
        let kept = inPerson.filter { e in e != entry && checked.contains { $0.hasPrefix(e + "|") } }
        return how == .inPerson ? kept + [entry] : kept
    }

    /// `checked` without `id` of `group`: the user compared again and it didn't match.
    public static func unmarked(_ checked: [String], group: String, id: String) -> [String] {
        checked.filter { !$0.hasPrefix("\(group)|\(id)|") }
    }
}

extension Person {
    /// Someone in the group's member list who hasn't written yet: nothing heard, no check-in. `name`: from their introduction.
    public static func silent(id: String, name: String) -> Person { Person(id: id, name: name, lastTs: 0) }
}
