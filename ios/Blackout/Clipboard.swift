import UIKit
import UniformTypeIdentifiers

/// Everything DarkProof puts on the clipboard goes through here: a group's code, a Plus Code (someone's position), the
/// text of a message. It stays on this iPhone (not handed to the user's other devices) and iOS clears it after a
/// minute. "Erase everything" clears it at once if what is on the clipboard is still what the app put there. Main thread.
enum Clipboard {
    static let seconds: TimeInterval = 60
    /// The clipboard's change count right after this app last copied something; nil once that was cleared or erased.
    private static var ours: Int?

    static func copy(_ text: String) {
        let board = UIPasteboard.general
        board.setItems([[UTType.utf8PlainText.identifier: text]], options: [.expirationDate: Date().addingTimeInterval(seconds), .localOnly: true])
        ours = board.changeCount
    }

    /// "Erase everything": clears the clipboard if it still holds the app's own item. What the user copied elsewhere
    /// since is theirs, and stays. Nothing is read, so iOS shows no paste notice.
    static func clearIfOurs() {
        if let ours, UIPasteboard.general.changeCount == ours { UIPasteboard.general.items = [] }
        ours = nil
    }
}
