import XCTest
@testable import BlackoutCore

final class AsciiFieldTests: XCTestCase {
    /// An iPhone 15 Pro / 16 / 17 screen in points.
    private let w = 393.0, h = 852.0

    private func field(_ d: AsciiField.Density = .medium) -> AsciiField.Field { AsciiField.Field(width: w, height: h, density: d) }

    func testOptionWordsMatchTheOtherApps() {
        XCTAssertEqual(AsciiField.Characters.allCases.map(\.label), ["Classic", "Blocks", "DarkProof marks", "Binary"])
        XCTAssertEqual(AsciiField.Density.allCases.map(\.label), ["Fine", "Medium", "Coarse"])
        XCTAssertEqual(AsciiField.Colour.allCases.map(\.label), ["Grey", "Navy", "Green"])
        XCTAssertEqual(AsciiField.Source.allCases.map(\.label), ["Clouds", "Light streak", "Both"])
    }

    func testCharacterSets() {
        XCTAssertEqual(String(AsciiField.Characters.classic.steps.map { $0[0] }), " .:-=+*#%@")
        XCTAssertEqual(String(AsciiField.Characters.blocks.steps.map { $0[0] }), " ░▒▓█")
        XCTAssertEqual(AsciiField.Characters.marks.steps.last, ["D", "P"])
        XCTAssertEqual(AsciiField.Characters.binary.steps.count, 6)
        for c in AsciiField.Characters.allCases { XCTAssertEqual(c.steps[0], [" "], "step 0 is empty") }
    }

    func testBayerIsAPermutation() {
        let ranks = AsciiField.bayer8.map { Int(($0 * 64 - 0.5).rounded()) }
        XCTAssertEqual(ranks.sorted(), Array(0..<64))
        XCTAssertEqual(Array(ranks.prefix(4)), [0, 32, 8, 40]) // the standard 8×8 matrix's first row
    }

    func testLayoutPerDensity() {
        let fine = AsciiField.layout(width: w, height: h, density: .fine)
        let medium = AsciiField.layout(width: w, height: h, density: .medium)
        let coarse = AsciiField.layout(width: w, height: h, density: .coarse)
        XCTAssertEqual(medium.cols, 36); XCTAssertEqual(medium.cellHeight, 19)
        XCTAssertGreaterThan(fine.cols * fine.rows, medium.cols * medium.rows)
        XCTAssertGreaterThan(medium.cols * medium.rows, coarse.cols * coarse.rows)
        for l in [fine, medium, coarse] {
            XCTAssertGreaterThanOrEqual(Double(l.cols) * l.cellWidth, w) // the screen is covered to its edges
            XCTAssertGreaterThanOrEqual(Double(l.rows) * l.cellHeight, h)
        }
    }

    func testSameInputSamePicture() {
        let a = field().cells(characters: .classic, source: .both)
        let b = field().cells(characters: .classic, source: .both)
        XCTAssertEqual(a, b)
    }

    func testStepsStayInRange() {
        for d in AsciiField.Density.allCases {
            let f = field(d)
            for c in AsciiField.Characters.allCases {
                for s in AsciiField.Source.allCases {
                    let cells = f.cells(characters: c, source: s)
                    XCTAssertEqual(cells.count, f.layout.cols * f.layout.rows)
                    for cell in cells {
                        XCTAssertLessThan(Int(cell.step), c.steps.count)
                        if cell.step > 0 { XCTAssertLessThan(Int(cell.pick), c.steps[Int(cell.step)].count) }
                    }
                    XCTAssertTrue(cells.contains { $0.step > 0 }, "\(d) \(c) \(s) draws something")
                    XCTAssertTrue(cells.contains { $0.step == 0 }, "\(d) \(c) \(s) leaves black between")
                }
            }
        }
    }

    /// Titles sit on black: the band under the status bar is (nearly) empty, and the light sits low on the right.
    func testTopIsDarkAndTheLightIsLowRight() {
        let f = field()
        let l = f.layout
        let cells = f.cells(characters: .classic, source: .both)
        func mean(rows: Range<Int>, cols: Range<Int>) -> Double {
            var sum = 0.0
            for y in rows { for x in cols { sum += Double(cells[y * l.cols + x].step) } }
            return sum / Double(rows.count * cols.count)
        }
        let topRows = 0..<Int(0.05 * h / l.cellHeight) // the status bar
        let top = mean(rows: topRows, cols: 0..<l.cols)
        let lowRight = mean(rows: Int(0.65 * Double(l.rows))..<Int(0.9 * Double(l.rows)), cols: l.cols / 2..<l.cols)
        XCTAssertLessThan(top, 0.5)
        XCTAssertGreaterThan(lowRight, 2 * max(top, 0.1))
    }

    func testSourcesDiffer() {
        let f = field()
        let clouds = f.cells(characters: .classic, source: .clouds)
        let streak = f.cells(characters: .classic, source: .streak)
        let both = f.cells(characters: .classic, source: .both)
        XCTAssertNotEqual(clouds, streak)
        XCTAssertNotEqual(clouds, both)
        XCTAssertNotEqual(streak, both)
    }

    /// The clouds repeat across the width, so the drift can run forever without a seam: moving them by a whole screen
    /// width gives the same picture.
    func testDriftWrapsWithoutSeam() {
        let f = field()
        let l = f.layout
        let oneWidth = Double(l.cols) * l.cellWidth / 3.6 // seconds for the clouds to cross the screen once
        let later = f.cells(characters: .classic, source: .clouds, time: oneWidth), now = f.cells(characters: .classic, source: .clouds)
        XCTAssertLessThanOrEqual(zip(later, now).filter { $0 != $1 }.count, 2) // rounding of the time can tip a cell or two
        XCTAssertNotEqual(f.cells(characters: .classic, source: .clouds, time: oneWidth / 2), f.cells(characters: .classic, source: .clouds))
        // The cloud field itself meets at its left and right edges.
        let cloud = AsciiField.cloudField(64, 32, aspect: 0.5, period: 2, seed: 11)
        var edge = 0.0, inside = 0.0
        for y in 0..<32 {
            edge += abs(cloud[y * 64] - cloud[y * 64 + 63])
            inside += abs(cloud[y * 64 + 31] - cloud[y * 64 + 32])
        }
        XCTAssertLessThan(edge, 3 * inside + 0.5)
    }

    /// Readability: at full strength, white text keeps at least 7:1 on the brightest character of every colour; green is
    /// darker than the others; the default strength leaves more room still.
    func testInkKeepsTextReadable() {
        let white = AsciiField.RGB(r: 1, g: 1, b: 1)
        for c in AsciiField.Colour.allCases {
            for chars in AsciiField.Characters.allCases {
                let n = chars.steps.count
                for step in 1..<n {
                    let ink = AsciiField.ink(c, chars, step: step)
                    XCTAssertGreaterThanOrEqual(AsciiField.RGB.contrast(white, ink), 7, "\(c) \(chars) step \(step)")
                    XCTAssertGreaterThanOrEqual(AsciiField.RGB.contrast(white, ink.scaled(AsciiField.defaultStrength)), 11, "\(c) \(chars) step \(step)")
                }
                XCTAssertEqual(AsciiField.ink(c, chars, step: n - 1), c.ink.bright.scaled(chars.inkScale))
                XCTAssertEqual(AsciiField.ink(c, chars, step: 1), c.ink.dim.scaled(chars.inkScale))
            }
        }
        // Blocks fill their cells, so they are held lower than the thin characters.
        for chars in AsciiField.Characters.allCases where chars != .blocks {
            XCTAssertLessThanOrEqual(AsciiField.Characters.blocks.inkScale, 0.5 * chars.inkScale)
            XCTAssertLessThanOrEqual(chars.inkScale, 1)
        }
        let green = AsciiField.Colour.green.ink.bright.luminance
        XCTAssertLessThan(green, 0.5 * AsciiField.Colour.grey.ink.bright.luminance)
        XCTAssertLessThan(green, 0.5 * AsciiField.Colour.navy.ink.bright.luminance)
        // The app's secondary grey (Theme.dim, 0.64) on green's brightest character at full strength.
        let dim = AsciiField.RGB(r: 0.64, g: 0.64, b: 0.64)
        XCTAssertGreaterThanOrEqual(AsciiField.RGB.contrast(dim, AsciiField.Colour.green.ink.bright), 4.5)
    }

    func testContrastFormula() {
        let white = AsciiField.RGB(r: 1, g: 1, b: 1), black = AsciiField.RGB(r: 0, g: 0, b: 0)
        XCTAssertEqual(AsciiField.RGB.contrast(white, black), 21, accuracy: 0.001)
        XCTAssertEqual(AsciiField.RGB.contrast(black, white), 21, accuracy: 0.001)
    }

    /// Building the field and one set of cells is cheap enough to do on every launch (Debug builds are slower).
    func testFieldIsCheap() {
        let start = Date()
        let f = field(.fine)
        _ = f.cells(characters: .classic, source: .both)
        XCTAssertLessThan(Date().timeIntervalSince(start), 2)
    }
}
