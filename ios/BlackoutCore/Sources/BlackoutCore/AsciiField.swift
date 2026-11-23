import Foundation

/// The ASCII background behind the main tabs ("style C" on the backgrounds board, the owner's pick): the dark is typed
/// out in monospace characters, denser where it is brighter. The field is DarkProof's own code, the same maths as the
/// board (darkproof-app-design/backgrounds.html):
/// - **Clouds:** gradient noise that repeats across the width (so it can drift forever), warped fractal noise shaped
///   into cumulus whose tops are lit from above.
/// - **One light:** a soft spot above the tab bar, on the right, where the screens have the least on them, and a
///   diagonal light streak near the bottom.
/// - **Characters:** each cell takes the brightness step its value reaches after an 8×8 Bayer threshold, which spreads
///   the in-between values out evenly.
/// - **Top:** the field fades out toward the top, so titles sit on black (the app also lays black over each header).
///
/// Pure: no drawing here. The app draws the cells once per screen size and set of options, into one image.
public enum AsciiField {

    // MARK: Options (the same words on every DarkProof app)

    public enum Characters: String, CaseIterable, Sendable {
        case classic, blocks, marks, binary

        public var label: String {
            switch self {
            case .classic: return "Classic"
            case .blocks: return "Blocks"
            case .marks: return "DarkProof marks"
            case .binary: return "Binary"
            }
        }

        /// One entry per brightness step, darkest first; step 0 stays empty. A step with several characters gives each
        /// cell one of them, at random but always the same one.
        public var steps: [[Character]] {
            switch self {
            case .classic: return [" ", ".", ":", "-", "=", "+", "*", "#", "%", "@"].map { [Character($0)] }
            case .blocks: return [" ", "░", "▒", "▓", "█"].map { [Character($0)] }
            case .marks: return [[" "], ["·"], [":"], ["•"], ["/", "\\"], ["D", "P"]]
            case .binary: return [[" "]] + Array(repeating: ["0", "1"], count: 5)
            }
        }

        /// How much of the colour's ink the set uses. A line of text over blocks, which fill most of their cell, gets
        /// far more light behind it than over thin characters, so blocks are held lower. Measured on the iPhone app at
        /// full strength: the app's secondary grey keeps at least 7:1 on the brightest 200 × 20 point area with every
        /// set and density.
        public var inkScale: Double { self == .blocks ? 0.4 : 0.86 }
    }

    public enum Density: String, CaseIterable, Sendable {
        case fine, medium, coarse

        public var label: String {
            switch self {
            case .fine: return "Fine"
            case .medium: return "Medium"
            case .coarse: return "Coarse"
            }
        }

        /// Cell width in points; a cell is 1.75 times as tall, about a monospace character's shape.
        public var cellWidth: Double {
            switch self {
            case .fine: return 8
            case .medium: return 11
            case .coarse: return 14
            }
        }
    }

    public enum Colour: String, CaseIterable, Sendable {
        case grey, navy, green

        public var label: String {
            switch self {
            case .grey: return "Grey"
            case .navy: return "Navy"
            case .green: return "Green"
            }
        }

        /// Ink of the dimmest and the brightest step at full strength. The dimmest is the board's; the brightest is held
        /// down from the board's so that white text keeps at least 7:1 on it even at full strength (the board's grey,
        /// #9c9c9c, gave 5.2:1 at its 70%). Green stays darker still, so green keeps meaning "safe".
        public var ink: (dim: RGB, bright: RGB) {
            switch self {
            case .grey: return (RGB(0x2a, 0x2a, 0x2a), RGB(0x56, 0x56, 0x56))
            case .navy: return (RGB(0x10, 0x1b, 0x36), RGB(0x42, 0x55, 0x86))
            case .green: return (RGB(0x0f, 0x19, 0x14), RGB(0x29, 0x3c, 0x32))
            }
        }
    }

    public enum Source: String, CaseIterable, Sendable {
        case clouds, streak, both

        public var label: String {
            switch self {
            case .clouds: return "Clouds"
            case .streak: return "Light streak"
            case .both: return "Both"
            }
        }
    }

    /// Strength is the share of the full-strength ink that shows (0…1). The default leaves white text above 11:1 on
    /// the brightest character.
    public static let defaultStrength = 0.7

    /// The ink of brightness step `step` (1 up to the set's last) at full strength: between the colour's dimmest and
    /// brightest, times the set's `inkScale`.
    public static func ink(_ colour: Colour, _ characters: Characters, step: Int) -> RGB {
        let (a, b) = colour.ink, steps = characters.steps.count
        let t = steps > 2 ? Double(max(0, step - 1)) / Double(steps - 2) : 1
        return RGB(r: a.r + (b.r - a.r) * t, g: a.g + (b.g - a.g) * t, b: a.b + (b.b - a.b) * t).scaled(characters.inkScale)
    }

    /// An sRGB colour, each channel 0…1.
    public struct RGB: Equatable, Sendable {
        public let r, g, b: Double
        public init(r: Double, g: Double, b: Double) { self.r = r; self.g = g; self.b = b }
        init(_ r: Int, _ g: Int, _ b: Int) { self.init(r: Double(r) / 255, g: Double(g) / 255, b: Double(b) / 255) }

        public func scaled(_ k: Double) -> RGB { RGB(r: r * k, g: g * k, b: b * k) }

        /// WCAG relative luminance.
        public var luminance: Double {
            func lin(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }

        /// WCAG contrast ratio between two colours (1…21).
        public static func contrast(_ x: RGB, _ y: RGB) -> Double {
            let a = x.luminance, b = y.luminance
            return (max(a, b) + 0.05) / (min(a, b) + 0.05)
        }
    }

    // MARK: Grid

    /// How the screen divides into cells, in points.
    public struct Layout: Equatable, Sendable {
        public let cols, rows: Int
        public let cellWidth, cellHeight: Double
        /// Font size for the characters: the board's 1.375 times the cell width.
        public var fontSize: Double { cellWidth * 1.375 }
    }

    public static func layout(width: Double, height: Double, density: Density) -> Layout {
        let cw = density.cellWidth, ch = (cw * 1.75).rounded()
        return Layout(cols: max(1, Int((width / cw).rounded(.up))), rows: max(1, Int((height / ch).rounded(.up))), cellWidth: cw, cellHeight: ch)
    }

    /// One cell: its brightness step (0 = empty) and which of that step's characters it shows.
    public struct Cell: Equatable, Sendable {
        public let step: UInt8
        public let pick: UInt8
        public init(step: UInt8, pick: UInt8) { self.step = step; self.pick = pick }
        public static let empty = Cell(step: 0, pick: 0)
    }

    /// Everything that depends only on the screen size and the density, computed once. Each frame of the slow drift
    /// then only samples it again (`cells(…time:)`), which is cheap.
    public struct Field: Sendable {
        public let layout: Layout
        public let width, height: Double
        let clouds: [Double]  // cols × rows, 0…1, repeats across the width
        let light: [Double]   // the light spot and the top fade, per cell
        let fade: [Double]    // the top fade alone, per row
        let alt: [UInt8]      // which character a cell takes when a step has several

        public init(width: Double, height: Double, density: Density) {
            let l = AsciiField.layout(width: width, height: height, density: density)
            layout = l; self.width = width; self.height = height
            let aspect = (Double(l.rows) * l.cellHeight) / (Double(l.cols) * l.cellWidth)
            clouds = AsciiField.cloudField(l.cols, l.rows, aspect: aspect, period: 2, seed: 11)
            var light = [Double](repeating: 0, count: l.cols * l.rows), alt = [UInt8](repeating: 0, count: l.cols * l.rows)
            var fade = [Double](repeating: 0, count: l.rows)
            for y in 0..<l.rows {
                let v = (Double(y) + 0.5) * l.cellHeight / height
                fade[y] = AsciiField.topFade(v)
                for x in 0..<l.cols {
                    let u = (Double(x) + 0.5) * l.cellWidth / width
                    light[y * l.cols + x] = AsciiField.lightAt(u: u, v: v, aspect: height / width)
                    alt[y * l.cols + x] = UInt8(AsciiField.ihash(Int32(x), Int32(y), 99) & 255)
                }
            }
            self.light = light; self.fade = fade; self.alt = alt
        }

        /// The cells, row by row from the top. `time` is seconds of slow drift: the clouds move at 3.6 points a second
        /// whatever the density, and the streak sways a little from side to side. 0 is the still picture.
        public func cells(characters: Characters, source: Source, time: Double = 0) -> [Cell] {
            let steps = characters.steps, n = steps.count
            let counts = steps.map { UInt8($0.count) }
            let cols = layout.cols, rows = layout.rows
            let shift = time * 3.6 / layout.cellWidth
            let sway = sin(time * 0.12) * 0.08
            let useClouds = source != .streak, useStreak = source != .clouds
            var out = [Cell](repeating: .empty, count: cols * rows)
            for y in 0..<rows {
                let v = (Double(y) + 0.5) * layout.cellHeight / height
                for x in 0..<cols {
                    let i = y * cols + x
                    var value = useClouds ? 0 : 0.02
                    if useClouds { value += AsciiField.sampleWrap(clouds, cols, rows, Double(x) + shift, Double(y)) * light[i] * (useStreak ? 0.95 : 1.25) }
                    if useStreak { value += AsciiField.streak(u: (Double(x) + 0.5) * layout.cellWidth / width - sway, v: v, width: width, height: height) * fade[y] * 1.1 }
                    var k = Int((value * Double(n - 1) + AsciiField.bayer8[((y & 7) << 3) + (x & 7)]).rounded(.down))
                    if k < 1 { continue }
                    if k > n - 1 { k = n - 1 }
                    let c = counts[k]
                    out[i] = Cell(step: UInt8(k), pick: c > 1 ? alt[i] % c : 0)
                }
            }
            return out
        }
    }

    // MARK: The maths (as on the board)

    /// Ordered dithering thresholds, Bayer 8×8, row by row, each in (0, 1).
    public static let bayer8: [Double] = {
        let base = [[0, 2], [3, 1]]
        var m = base
        while m.count < 8 {
            let n = m.count
            m = (0..<(2 * n)).map { i in (0..<(2 * n)).map { j in 4 * m[i % n][j % n] + base[i / n][j / n] } }
        }
        return m.flatMap { $0 }.map { (Double($0) + 0.5) / 64 }
    }()

    static func clamp(_ x: Double, _ a: Double, _ b: Double) -> Double { x < a ? a : x > b ? b : x }
    static func smooth(_ e0: Double, _ e1: Double, _ x: Double) -> Double {
        let t = clamp((x - e0) / (e1 - e0), 0, 1)
        return t * t * (3 - 2 * t)
    }

    /// An integer hash (the board's, with JavaScript's 32-bit multiply).
    static func ihash(_ x: Int32, _ y: Int32, _ s: Int32) -> UInt32 {
        var h = UInt32(bitPattern: x) &* 0x27d4eb2d ^ UInt32(bitPattern: y) &* 0x165667b1 ^ UInt32(bitPattern: s) &* 0x9e3779b1
        h = (h ^ (h >> 15)) &* 0x85ebca6b
        h = (h ^ (h >> 13)) &* 0xc2b2ae35
        return h ^ (h >> 16)
    }

    private static let gradients: [(Double, Double)] = (0..<256).map { i in
        let a = Double(i) / 256 * 2 * .pi
        return (cos(a), sin(a))
    }

    private static func wrap(_ i: Int, _ p: Int) -> Int { p > 0 ? ((i % p) + p) % p : i }

    /// Gradient noise, repeating every `period` units across x (0: no repeat).
    static func noise(_ x: Double, _ y: Double, period: Int, seed: Int32) -> Double {
        let xi = Int(x.rounded(.down)), yi = Int(y.rounded(.down))
        let xf = x - Double(xi), yf = y - Double(yi)
        let x0 = Int32(truncatingIfNeeded: wrap(xi, period)), x1 = Int32(truncatingIfNeeded: wrap(xi + 1, period))
        let y0 = Int32(truncatingIfNeeded: yi), y1 = Int32(truncatingIfNeeded: yi + 1)
        let g00 = gradients[Int(ihash(x0, y0, seed) & 255)], g10 = gradients[Int(ihash(x1, y0, seed) & 255)]
        let g01 = gradients[Int(ihash(x0, y1, seed) & 255)], g11 = gradients[Int(ihash(x1, y1, seed) & 255)]
        let n00 = g00.0 * xf + g00.1 * yf
        let n10 = g10.0 * (xf - 1) + g10.1 * yf
        let n01 = g01.0 * xf + g01.1 * (yf - 1)
        let n11 = g11.0 * (xf - 1) + g11.1 * (yf - 1)
        let u = xf * xf * xf * (xf * (xf * 6 - 15) + 10)
        let v = yf * yf * yf * (yf * (yf * 6 - 15) + 10)
        let a = n00 + (n10 - n00) * u, b = n01 + (n11 - n01) * u
        return a + (b - a) * v
    }

    static func fbm(_ x: Double, _ y: Double, octaves: Int, period: Int, seed: Int32) -> Double {
        var sum = 0.0, amp = 1.0, norm = 0.0, f = 1.0
        for o in 0..<octaves {
            sum += amp * noise(x * f, y * f, period: period * Int(f), seed: seed &+ Int32(o) &* 31)
            norm += amp; amp *= 0.5; f *= 2
        }
        return sum / norm
    }

    /// The cloud field: domain-warped fractal noise, shaped into cumulus with lit tops. `period` noise units across
    /// the width, so the left and right edges meet. Values 0…1, row by row.
    static func cloudField(_ fw: Int, _ fh: Int, aspect: Double, period: Int, seed: Int32) -> [Double] {
        var raw = [Double](repeating: 0, count: fw * fh)
        var lo = Double.infinity, hi = -Double.infinity
        let c = Double(period)
        for y in 0..<fh {
            let ny = (Double(y) + 0.5) / Double(fh) * aspect * c
            for x in 0..<fw {
                let nx = (Double(x) + 0.5) / Double(fw) * c
                let wx = fbm(nx + 5.2, ny + 1.3, octaves: 2, period: period, seed: seed &+ 101)
                let wy = fbm(nx + 1.7, ny + 9.2, octaves: 2, period: period, seed: seed &+ 202)
                let n = fbm(nx + 0.8 * wx, ny + 0.8 * wy, octaves: 5, period: period, seed: seed)
                raw[y * fw + x] = n
                lo = min(lo, n); hi = max(hi, n)
            }
        }
        let inv = 1 / ((hi - lo) == 0 ? 1 : hi - lo)
        let k = max(1, Int((Double(fh) / (aspect * 60)).rounded()))
        var out = [Double](repeating: 0, count: fw * fh)
        for y in 0..<fh {
            let row = y * fw, up = max(0, y - k) * fw, down = min(fh - 1, y + k) * fw
            for x in 0..<fw {
                let n = (raw[row + x] - lo) * inv
                let cloud = smooth(0.4, 0.88, n)
                let lit = clamp(0.5 + (raw[down + x] - raw[up + x]) * inv * 5, 0, 1)
                out[row + x] = min(1, cloud * (0.45 + 0.55 * lit) + 0.1 * n * n)
            }
        }
        return out
    }

    /// Bilinear sample that wraps across x and stops at the top and bottom.
    static func sampleWrap(_ f: [Double], _ fw: Int, _ fh: Int, _ x: Double, _ y: Double) -> Double {
        let xw = (x.truncatingRemainder(dividingBy: Double(fw)) + Double(fw)).truncatingRemainder(dividingBy: Double(fw))
        let yc = clamp(y, 0, Double(fh - 1))
        let x0 = Int(xw), y0 = Int(yc), x1 = (x0 + 1) % fw, y1 = min(fh - 1, y0 + 1)
        let tx = xw - Double(x0), ty = yc - Double(y0)
        let a = f[y0 * fw + x0], b = f[y0 * fw + x1], c = f[y1 * fw + x0], d = f[y1 * fw + x1]
        return a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty
    }

    /// The phone's light: brightest above the tab bar on the right, never fully dark elsewhere.
    static let lightX = 0.72, lightY = 0.8, lightSigma = 0.5, lightFloor = 0.14

    /// The top fades out (v = 0 at the top, 1 at the bottom): titles sit on black.
    static func topFade(_ v: Double) -> Double { 0.12 + 0.88 * smooth(0.05, 0.16, v) }

    static func lightAt(u: Double, v: Double, aspect: Double) -> Double {
        let dx = u - lightX, dy = (v - lightY) * aspect
        return (lightFloor + (1 - lightFloor) * exp(-(dx * dx + dy * dy) / (2 * lightSigma * lightSigma))) * topFade(v)
    }

    /// The light streak: a soft diagonal band from the bottom left up to the right, in the empty space above the tab
    /// bar. `u`, `v` are 0…1 across and down; distances are in points, so it keeps its width on every screen.
    static func streak(u: Double, v: Double, width: Double, height: Double) -> Double {
        let vc = 0.97 - 0.36 * u + 0.05 * sin(.pi * u)
        let slope = -0.36 + 0.05 * .pi * cos(.pi * u)
        let sn = sin(.pi * clamp(u, 0, 1))
        let sp = slope * height / width
        let d = (v - vc) * height / (1 + sp * sp).squareRoot()
        let w = 8 + 18 * sn
        let q = d * d / (w * w)
        return (0.55 * exp(-q) + 0.25 * exp(-q / 16) + 0.08 * exp(-q / 100)) * (0.2 + 0.8 * sn)
    }
}
