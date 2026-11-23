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
