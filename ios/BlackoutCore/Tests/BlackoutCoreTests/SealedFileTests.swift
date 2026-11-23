import XCTest
@testable import BlackoutCore

/// Decision C (fix round 2026-11-23): the iPhone's check-in clock records (other people's positions) and the day book with
/// the gone list are sealed with the key of this phone's copies, not plain files. An older version's plain file is read
/// once, sealed, and its plain copy deleted.
final class SealedFileTests: XCTestCase {
    let key = Data(repeating: 0x42, count: 32), other = Data(repeating: 0x43, count: 32)

    private var clockBook: [String] {
        var b = QuietBook()
        b.take(member: "1111111111111111", id: "0123456789abcdef", mark: "0011223344556677", ts: 1_794_000_000_000,
               deadline: 1_794_003_600_000, spot: Spot(lat: 45.4642, lon: 9.19, accuracy: 12), now: 1_794_000_000_100, highRisk: false)
        return b.saved
    }

    func testAClockBookComesBackAsItWas() throws {
        let lines = clockBook, plain = Data(lines.joined(separator: "\n").utf8)
        let sealed = try XCTUnwrap(SealedFile.seal(plain, key: key, label: "quiet.g1"))
        // Nothing of it can be read in the file: no member id, no position.
        XCTAssertNil(sealed.range(of: Data("1111111111111111".utf8)))
        XCTAssertNil(sealed.range(of: Data("45.46420".utf8)))
        let back = try XCTUnwrap(SealedFile.open(sealed, key: key, label: "quiet.g1"))
        XCTAssertEqual(QuietBook(saved: String(decoding: back, as: UTF8.self).split(separator: "\n").map(String.init)), QuietBook(saved: lines))
        XCTAssertEqual(QuietBook(saved: lines).clock("1111111111111111")?.spot?.accuracy, 12)
    }

    func testTheDayBookComesBackAsItWas() throws {
        var book = DayBook()
        book.note("e608000000000001abcdef0123456789", at: 1_794_000_000_000)
        book.remove("e608000000000002abcdef0123456789", ts: 1_794_000_000_000, group: "g1")
        let sealed = try XCTUnwrap(SealedFile.seal(book.saved, key: key, label: "daybook"))
        XCTAssertEqual(DayBook(saved: try XCTUnwrap(SealedFile.open(sealed, key: key, label: "daybook"))), book)
    }

    func testOnlyItsOwnKeyAndLabelOpenIt() throws {
        let sealed = try XCTUnwrap(SealedFile.seal(Data("c x".utf8), key: key, label: "quiet.g1"))
        XCTAssertNil(SealedFile.open(sealed, key: other, label: "quiet.g1"), "another key (after Erase everything)")
        XCTAssertNil(SealedFile.open(sealed, key: key, label: "quiet.g2"), "another group's file")
        XCTAssertNil(SealedFile.open(sealed, key: key, label: "daybook"))
        var bent = sealed; bent[bent.count - 1] ^= 1
        XCTAssertNil(SealedFile.open(bent, key: key, label: "quiet.g1"), "damaged")
        XCTAssertNil(SealedFile.open(sealed.prefix(20), key: key, label: "quiet.g1"), "cut short")
        XCTAssertNil(SealedFile.seal(Data(), key: Data(count: 16), label: "x"), "a key that isn't 32 bytes")
        XCTAssertNotEqual(SealedFile.seal(Data("c x".utf8), key: key, label: "quiet.g1"), sealed, "a fresh nonce each time")
    }

    /// An older version's plain file is no sealed file: it is read once, then sealed, and its plain copy goes.
    func testAPlainFileIsMovedOnce() throws {
        let plain = Data(clockBook.joined(separator: "\n").utf8)
        XCTAssertNil(SealedFile.open(plain, key: key, label: "quiet.g1"), "a plain file never opens as a sealed one")
        let first = SealedFile.load(sealed: nil, plain: plain, key: key, label: "quiet.g1")
        XCTAssertEqual(first.data, plain); XCTAssertTrue(first.migrate)
        let sealed = try XCTUnwrap(SealedFile.seal(plain, key: key, label: "quiet.g1"))
        // Sealed now: read from the sealed file, also if the plain copy is still there (a move cut short).
        let again = SealedFile.load(sealed: sealed, plain: plain, key: key, label: "quiet.g1")
        XCTAssertEqual(again.data, plain); XCTAssertFalse(again.migrate)
        // A sealed file that doesn't open reads as nothing: never the plain copy beside it.
        let wrong = SealedFile.load(sealed: sealed, plain: plain, key: other, label: "quiet.g1")
        XCTAssertNil(wrong.data); XCTAssertFalse(wrong.migrate)
        let none = SealedFile.load(sealed: nil, plain: nil, key: key, label: "quiet.g1")
        XCTAssertNil(none.data); XCTAssertFalse(none.migrate)
    }
}
