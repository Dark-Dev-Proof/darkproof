import Foundation
import CryptoKit

/// A small file this phone keeps for itself, sealed with a key that stays on the phone (the key of this phone's copies of
/// messages, spec "This phone's copy"): the check-in clocks of a group (`QuietBook`, with other people's positions) and
/// the day book with the gone list (`DayBook`). AES-256-GCM, bound to what the file is (`label`), so one group's file
/// can't be passed off as another's or as the day book. "DPS1", then the nonce, the sealed bytes and the tag.
/// An older version kept these files plain: such a file is read once more, sealed, and its plain copy deleted (`load`).
public enum SealedFile {
    static let magic = Data("DPS1".utf8)

    /// `plain` sealed for `label`; nil only if the key isn't 32 bytes.
    public static func seal(_ plain: Data, key: Data, label: String) -> Data? {
        guard key.count == 32, let box = try? AES.GCM.seal(plain, using: SymmetricKey(data: key), authenticating: Data(("darkproof-file|" + label).utf8)),
              let combined = box.combined else { return nil }
        return magic + combined
    }

    /// What `seal` sealed for `label` with `key`; nil for anything else: another key or label, a damaged or cut file, or
    /// an older version's plain file.
    public static func open(_ blob: Data, key: Data, label: String) -> Data? {
        guard key.count == 32, blob.starts(with: magic), let box = try? AES.GCM.SealedBox(combined: Data(blob.dropFirst(magic.count))) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: Data(("darkproof-file|" + label).utf8))
    }

    /// What to read from a file that may still have an older version's plain copy beside it. `sealed`: the sealed file's
    /// bytes, if there is one; `plain`: the plain copy's. The sealed file wins whenever it is there (a plain copy beside it
    /// is left over from a move cut short, and goes); one that doesn't open reads as nothing. Without it, the plain copy
    /// is read, and `migrate` says to seal it and delete the plain copy.
    public static func load(sealed: Data?, plain: Data?, key: Data, label: String) -> (data: Data?, migrate: Bool) {
        if let sealed { return (open(sealed, key: key, label: label), false) }
        if let plain { return (plain, true) }
        return (nil, false)
    }
}

/// The key that seals this phone's copies of messages and its own files (`SealedFile`), as the Keychain gives it. One that
/// is saved but can't be read right now is never replaced by a new one: everything sealed under it would no longer open,
/// read as empty and be written over (re-check N5, 2026-11-23; Android has the same guard). No key then, and it is asked
/// for again later.
public enum OwnKey {
    /// What the Keychain said: the saved bytes, nothing saved under that name, or anything else (it is there but can't
    /// be read now, e.g. before the phone's first unlock, or the Keychain failed).
    public enum Read: Equatable, Sendable { case found(Data), missing, unreadable }
    /// Use the saved key, make (and save) a new one, or go without a key for now.
    public enum Use: Equatable, Sendable { case use(Data), make, none }

    public static func use(_ r: Read) -> Use {
        switch r {
        case .found(let k): return k.count == 32 ? .use(k) : .make // one of another length never sealed anything (`SealedFile.seal`)
        case .missing: return .make
        case .unreadable: return .none
        }
    }
}

/// Own files that were read while their key couldn't be (`OwnKey.Use.none`): they read as empty, so writing them then
/// would put an empty or partial book over what they hold. Such a file is left as it is (neither written nor removed for
/// being empty) until it is read again with the key; a file removed on purpose (its group was left) goes all the same.
public struct UnreadFiles: Equatable, Sendable {
    public private(set) var names: Set<String> = []
    public init() {}

    /// `name` was read. `lost`: its sealed file is there but the key couldn't be read, so it read as nothing.
    public mutating func read(_ name: String, lost: Bool) {
        if lost { names.insert(name) } else { names.remove(name) }
    }

    /// The file was removed on purpose: nothing of it is held back any more.
    public mutating func removed(_ name: String) { names.remove(name) }

    /// Whether `name` may be written, or removed for being empty, now: only with the key, and not while it holds what
    /// couldn't be read.
    public func mayWrite(_ name: String, keyKnown: Bool) -> Bool { keyKnown && !names.contains(name) }
}
