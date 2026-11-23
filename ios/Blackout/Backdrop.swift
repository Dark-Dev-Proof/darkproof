import SwiftUI
import CoreText
import BlackoutCore

/// Settings > Background, saved on the phone. Defaults: on, Classic, Medium, Grey, Both, 70 %, no drift.
enum BackdropSetting {
    static let on = "bgOn", characters = "bgCharacters", density = "bgDensity", colour = "bgColour", source = "bgSource"
    static let strength = "bgStrength", drift = "bgDrift"

    /// Why the slow drift can't run right now, or nil if it can: it never runs in Low Power Mode, with Reduce Motion or
    /// with the app's Battery saver.
    static var driftBlocker: String? {
        driftBlocker(lowPower: ProcessInfo.processInfo.isLowPowerModeEnabled, reduceMotion: UIAccessibility.isReduceMotionEnabled, saver: Engine.saver)
    }

    static func driftBlocker(lowPower: Bool, reduceMotion: Bool, saver: Bool) -> String? {
        if lowPower { return "Paused while Low Power Mode is on" }
        if reduceMotion { return "Off while Reduce Motion is on" }
        if saver { return "Paused by Battery saver" }
        return nil
    }
}

/// The background's look: everything that changes the picture (strength only changes how much of it shows).
struct BackdropLook: Hashable {
    var characters = AsciiField.Characters.classic
    var density = AsciiField.Density.medium
    var colour = AsciiField.Colour.grey
    var source = AsciiField.Source.both

    init(characters: String, density: String, colour: String, source: String) {
        self.characters = AsciiField.Characters(rawValue: characters) ?? .classic
        self.density = AsciiField.Density(rawValue: density) ?? .medium
        self.colour = AsciiField.Colour(rawValue: colour) ?? .grey
        self.source = AsciiField.Source(rawValue: source) ?? .both
    }
}

/// Draws the background into one image per screen size and look, once, at the screen's own scale; the tabs only show
/// that image. The slow drift, off unless turned on, draws again at most 10 times a second, and only while a tab is on
/// screen, the app is active, and neither Low Power Mode, Reduce Motion nor Battery saver is on. Main thread.
final class Backdrop: ObservableObject {
    static let shared = Backdrop()

    struct Key: Equatable {
        let size: CGSize      // the whole screen, in points
        let scale: CGFloat    // pixels per point
        let look: BackdropLook
    }

    struct Picture {
        let image: CGImage
        let key: Key
    }

    @Published private(set) var picture: Picture?

    private var wanted: Key?
    private var visible = 0       // tabs showing the background
    private var active = UIApplication.shared.applicationState == .active
    private var busy = false, again = false
    private var timer: Timer?
    private var time = 0.0, lastTick = 0.0 // seconds of drift so far: a still picture keeps the last one
    private let queue = DispatchQueue(label: "darkproof.backdrop", qos: .userInitiated)
    private var painter: Painter? // on `queue` only

    private init() {
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.active = true; self?.updateDrift() }
        nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.active = false; self?.updateDrift() }
        for name in [Notification.Name.NSProcessInfoPowerStateDidChange, UIAccessibility.reduceMotionStatusDidChangeNotification, UserDefaults.didChangeNotification] {
            nc.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in self?.updateDrift() }
        }
    }

    /// A tab's background wants the picture for this screen and look; nil when the background is off, which also lets
    /// the picture go.
    func want(_ key: Key?) {
        guard key != wanted else { return }
        wanted = key
        if key == nil { picture = nil; queue.async { self.painter = nil } } else { draw() }
        updateDrift()
    }

    func appeared() { visible += 1; updateDrift() }
    func disappeared() { visible = max(0, visible - 1); updateDrift() }

    private var drifting: Bool { timer != nil }

    /// Draws the picture for `wanted` on the background queue; a request that comes in meanwhile is drawn right after.
    private func draw() {
        guard let key = wanted else { return }
        if busy { again = true; return }
        busy = true
        let t = time, keep = drifting
        queue.async {
            #if DEBUG
            let start = CACurrentMediaTime()
            #endif
            if self.painter?.key != key { self.painter = Painter(key: key) }
            let image = self.painter?.paint(time: t)
            #if DEBUG
            if !keep, let p = self.painter { // the one-off picture: how long it took, for the notes
                let line = String(format: "backdrop: %d×%d px (%@ %@ %@ %@), %d characters, %.1f ms",
                                  p.pixelWidth, p.pixelHeight, key.look.characters.rawValue, key.look.density.rawValue,
                                  key.look.colour.rawValue, key.look.source.rawValue, p.drawn, (CACurrentMediaTime() - start) * 1000)
                print(line); fflush(stdout); DebugLog.write(line)
                if CommandLine.arguments.contains("measureBackdrop") { // slow in Debug builds, so only when asked
                    let saved = UserDefaults.standard.object(forKey: BackdropSetting.strength) as? Double ?? AsciiField.defaultStrength
                    for s in [saved, 1] { let r = p.readability(strength: s); print(r); fflush(stdout); DebugLog.write(r) }
                }
            }
            #endif
            if !keep { self.painter = nil } // a still picture needs no drawing memory afterwards
            DispatchQueue.main.async {
                self.busy = false
                if let image, key == self.wanted { self.picture = Picture(image: image, key: key) }
                if self.again { self.again = false; self.draw() }
            }
        }
    }

    private var driftAllowed: Bool {
        wanted != nil && visible > 0 && active && UserDefaults.standard.bool(forKey: BackdropSetting.drift) && BackdropSetting.driftBlocker == nil
    }

    private func updateDrift() {
        if driftAllowed {
            guard timer == nil else { return }
            lastTick = CACurrentMediaTime()
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.tick() }
        } else if let t = timer {
            t.invalidate(); timer = nil
            queue.async { self.painter = nil } // the last frame stays on screen as the still picture
        }
    }

    /// One frame of drift: skipped while the one before is still being drawn, so frames never pile up.
    private func tick() {
        let now = CACurrentMediaTime()
        time += min(now - lastTick, 0.25)
        lastTick = now
        if !busy { draw() }
    }
}

/// Draws the cells with Core Text into one opaque bitmap at the screen's scale. Kept between frames while drifting,
/// when only the cells that changed are drawn again. On the backdrop queue only.
private final class Painter {
    let key: Backdrop.Key
    let pixelWidth, pixelHeight: Int
    private(set) var drawn = 0 // characters in the last full picture
    private let field: AsciiField.Field
    private let context: CGContext
    private let stamps: [[Stamp]]      // per step, per character of the step
    private let inks: [CGColor]        // per step
    private let baseline: CGFloat      // from a cell's top
    private var shown: [AsciiField.Cell]?

    /// One character, ready to draw: its font (the system's monospaced one, or one that has the character), glyph,
    /// and where it goes in its cell.
    private struct Stamp {
        let font: CTFont
        let glyph: CGGlyph
        let dx: CGFloat
        let baseline: CGFloat
    }

    init?(key: Backdrop.Key) {
        self.key = key
        let size = key.size, look = key.look
        field = AsciiField.Field(width: Double(size.width), height: Double(size.height), density: look.density)
        let l = field.layout
        let cw = CGFloat(l.cellWidth), ch = CGFloat(l.cellHeight), fontSize = CGFloat(l.fontSize)
        pixelWidth = Int((size.width * key.scale).rounded())
        pixelHeight = Int((size.height * key.scale).rounded())
        guard pixelWidth > 0, pixelHeight > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let c = CGContext(data: nil, width: pixelWidth, height: pixelHeight, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }
        context = c
        c.scaleBy(x: key.scale, y: key.scale)
        c.textMatrix = .identity
        c.setShouldAntialias(true)

        // The board's "middle" baseline: the middle of the em box in the middle of the cell, a touch lower.
        let base = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular) as CTFont
        let asc = CTFontGetAscent(base), desc = CTFontGetDescent(base)
        let mid = ch / 2 + fontSize * (asc - desc) / (asc + desc) / 2 + fontSize * 0.04
        baseline = mid

        let steps = look.characters.steps
        stamps = steps.enumerated().map { i, chars in
            i == 0 ? [] : chars.map { Self.stamp(String($0), base: base, cell: CGSize(width: cw, height: ch), baseline: mid) }
        }
        inks = (0..<steps.count).map { i in
            let rgb = AsciiField.ink(look.colour, look.characters, step: i)
            return CGColor(srgbRed: rgb.r, green: rgb.g, blue: rgb.b, alpha: 1)
        }
    }

    private static func stamp(_ s: String, base: CTFont, cell: CGSize, baseline: CGFloat) -> Stamp {
        let utf16 = Array(s.utf16)
        var font = CTFontCreateForString(base, s as CFString, CFRange(location: 0, length: utf16.count)) // a fallback font if SF Mono lacks it
        func measure(_ f: CTFont) -> (CGGlyph, CGFloat, CGRect) {
            var chars = utf16, glyphs = [CGGlyph](repeating: 0, count: utf16.count)
            CTFontGetGlyphsForCharacters(f, &chars, &glyphs, utf16.count)
            var g = glyphs[0], advance = CGSize.zero, box = CGRect.zero
            CTFontGetAdvancesForGlyphs(f, .horizontal, &g, &advance, 1)
            CTFontGetBoundingRectsForGlyphs(f, .horizontal, &g, &box, 1)
            return (g, advance.width, box)
        }
        var (glyph, advance, box) = measure(font)
        // Every character stays inside its own cell, so the drift can redraw one cell without touching the next.
        let fit = min(1, cell.width * 0.98 / max(advance, box.width, 1), cell.height * 0.98 / max(box.height, 1))
        var line = baseline
        if fit < 1 {
            font = CTFontCreateCopyWithAttributes(font, CTFontGetSize(font) * fit, nil, nil)
            (glyph, advance, box) = measure(font)
        }
        if line - box.maxY < 0 || line - box.minY > cell.height { line = (cell.height + box.height) / 2 + box.minY } // centre it instead
        return Stamp(font: font, glyph: glyph, dx: (cell.width - advance) / 2, baseline: line)
    }

    /// The picture at `time` seconds of drift.
    func paint(time: Double) -> CGImage? {
        let cells = field.cells(characters: key.look.characters, source: key.look.source, time: time)
        let l = field.layout, cols = l.cols
        let cw = CGFloat(l.cellWidth), ch = CGFloat(l.cellHeight), height = key.size.height
        var todo: [Int]
        context.setFillColor(CGColor(gray: 0, alpha: 1))
        if let before = shown, before.count == cells.count {
            todo = cells.indices.filter { cells[$0] != before[$0] }
            for i in todo { // clear the cells that change
                context.fill(CGRect(x: CGFloat(i % cols) * cw, y: height - CGFloat(i / cols + 1) * ch, width: cw, height: ch))
            }
        } else {
            context.fill(CGRect(origin: .zero, size: key.size))
            todo = Array(cells.indices)
        }
        // One call per character and colour.
        var groups: [Int: (glyphs: [CGGlyph], points: [CGPoint])] = [:]
        var count = 0
        for i in todo {
            let c = cells[i]
            guard c.step > 0 else { continue }
            let s = stamps[Int(c.step)][Int(c.pick)]
            let at = CGPoint(x: CGFloat(i % cols) * cw + s.dx, y: height - CGFloat(i / cols) * ch - s.baseline)
            groups[Int(c.step) << 8 | Int(c.pick), default: ([], [])].glyphs.append(s.glyph)
            groups[Int(c.step) << 8 | Int(c.pick)]!.points.append(at)
            count += 1
        }
        for (k, g) in groups {
            context.setFillColor(inks[k >> 8])
            CTFontDrawGlyphs(stamps[k >> 8][k & 255].font, g.glyphs, g.points, g.glyphs.count, context)
        }
        if shown == nil { drawn = count }
        shown = cells
        return context.makeImage()
    }

    #if DEBUG
    /// Debug builds, for the notes: contrast of the app's text on the picture as shown at `strength`. Against the
    /// brightest pixel (white text), and against the busiest line of text: the brightest 200 × 20 point area, on average.
    func readability(strength: Double) -> String {
        guard let data = context.data else { return "backdrop: no pixels" }
        let px = data.assumingMemoryBound(to: UInt8.self), row = context.bytesPerRow
        let lin: [Double] = (0..<256).map { i in
            let c = Double(i) / 255 * strength
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        let s = Int(key.scale.rounded()), w = pixelWidth / s, h = pixelHeight / s
        var points = [Double](repeating: 0, count: w * h), brightest = 0.0
        for y in 0..<(h * s) {
            for x in 0..<(w * s) {
                let p = y * row + x * 4 // B G R X
                let l = 0.0722 * lin[Int(px[p])] + 0.7152 * lin[Int(px[p + 1])] + 0.2126 * lin[Int(px[p + 2])]
                brightest = max(brightest, l)
                points[(y / s) * w + x / s] += l / Double(s * s)
            }
        }
        var sum = [Double](repeating: 0, count: (w + 1) * (h + 1)) // summed-area table
        for y in 0..<h { for x in 0..<w { sum[(y + 1) * (w + 1) + x + 1] = points[y * w + x] + sum[y * (w + 1) + x + 1] + sum[(y + 1) * (w + 1) + x] - sum[y * (w + 1) + x] } }
        let ww = min(200, w), wh = min(20, h)
        var busiest = 0.0
        for y in 0...(h - wh) { for x in stride(from: 0, through: w - ww, by: 4) {
            let a = sum[(y + wh) * (w + 1) + x + ww] - sum[y * (w + 1) + x + ww] - sum[(y + wh) * (w + 1) + x] + sum[y * (w + 1) + x]
            busiest = max(busiest, a / Double(ww * wh))
        } }
        let white = 1.0, dim = 0.3672, faint = 0.2542 // Theme.text, Theme.dim (0.64), Theme.faint (0.54)
        return String(format: "backdrop readability at %.0f%%: white on brightest pixel %.1f:1; on busiest line: white %.1f:1, dim %.1f:1, faint %.1f:1",
                      strength * 100, (white + 0.05) / (brightest + 0.05), (white + 0.05) / (busiest + 0.05), (dim + 0.05) / (busiest + 0.05), (faint + 0.05) / (busiest + 0.05))
    }
    #endif
}

// MARK: - On the tabs

extension View {
    /// The main tabs' background: pure black, with the ASCII field when it's on. Not for sheets or cards.
    func tabBackdrop() -> some View { modifier(TabBackdrop()) }

    /// Marks a tab's header: the background stays black behind it and fades in below, so no title sits on texture.
    /// `above`: the space between the top of the safe area and the header.
    func backdropHeader(above: CGFloat = 0) -> some View {
        background(GeometryReader { g in Color.clear.preference(key: BackdropHeaderKey.self, value: above + g.size.height) })
    }
}

private struct BackdropHeaderKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct TabBackdrop: ViewModifier {
    @State private var header: CGFloat = 0
    func body(content: Content) -> some View {
        content
            .onPreferenceChange(BackdropHeaderKey.self) { header = $0 }
            .background { BackdropLayer(header: header) }
    }
}

private struct BackdropLayer: View {
    let header: CGFloat
    @ObservedObject private var backdrop = Backdrop.shared
    @AppStorage(BackdropSetting.on) private var on = true
    @AppStorage(BackdropSetting.characters) private var characters = AsciiField.Characters.classic.rawValue
    @AppStorage(BackdropSetting.density) private var density = AsciiField.Density.medium.rawValue
    @AppStorage(BackdropSetting.colour) private var colour = AsciiField.Colour.grey.rawValue
    @AppStorage(BackdropSetting.source) private var source = AsciiField.Source.both.rawValue
    @AppStorage(BackdropSetting.strength) private var strength = AsciiField.defaultStrength
    @Environment(\.displayScale) private var scale
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        GeometryReader { outer in
            let safeTop = outer.frame(in: .global).minY + outer.safeAreaInsets.top
            GeometryReader { full in
                let key = Backdrop.Key(size: full.size, scale: scale,
                                       look: BackdropLook(characters: characters, density: density, colour: colour, source: source))
                ZStack(alignment: .topLeading) {
                    Color.black
                    if on, let p = backdrop.picture {
                        Image(decorative: p.image, scale: p.key.scale).interpolation(.none)
                            .opacity(min(1, max(0, strength)) * (contrast == .increased ? 0.5 : 1)) // Increase Contrast: fainter still
                        fade(clear: safeTop - full.frame(in: .global).minY + header, height: full.size.height)
                    }
                }
                .onAppear { backdrop.appeared(); backdrop.want(on ? key : nil) }
                .onDisappear { backdrop.disappeared() }
                .onChange(of: key) { _, k in backdrop.want(on ? k : nil) }
                .onChange(of: on) { _, isOn in backdrop.want(isOn ? key : nil) }
            }
            .ignoresSafeArea()
        }
        .accessibilityHidden(true)
    }

    /// Black down to the bottom of the header, then the field fades in over 64 points.
    private func fade(clear: CGFloat, height: CGFloat) -> some View {
        let a = min(1, max(0, clear / max(height, 1))), b = min(1, a + 64 / max(height, 1))
        return LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: a), .init(color: .black.opacity(0), location: b)],
                              startPoint: .top, endPoint: .bottom)
    }
}

// MARK: - Settings

/// A small live window on the background, at its real size: the corner where the light is.
struct BackdropPreview: View {
    let strength: Double
    @ObservedObject private var backdrop = Backdrop.shared

    var body: some View {
        GeometryReader { g in
            if let p = backdrop.picture, let crop = Self.crop(p, to: g.size) {
                Image(decorative: crop, scale: p.key.scale).interpolation(.none).opacity(strength)
            }
        }
        .frame(height: 120)
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line, lineWidth: 1))
        .accessibilityElement()
        .accessibilityLabel("Preview of the background")
    }

    private static func crop(_ p: Backdrop.Picture, to size: CGSize) -> CGImage? {
        let s = p.key.scale, W = p.key.size.width, H = p.key.size.height
        let w = min(size.width, W), h = min(size.height, H)
        guard w > 0, h > 0 else { return nil }
        let x = min(max(0, W * 0.72 - w / 2), W - w), y = min(max(0, H * 0.78 - h / 2), H - h)
        return p.image.cropping(to: CGRect(x: x * s, y: y * s, width: w * s, height: h * s).integral)
    }
}

/// A choice among a few words, as capsules side by side: the chosen one white, the others dark (PillSwitch's look,
/// for three or four options).
struct ChoiceRow<T: Hashable>: View {
    let icon: String
    let title: String
    let options: [T]
    let label: (T) -> String
    @Binding var selection: T
    var columns = 3

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            RowLabel(icon: icon, title: title)
            Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                ForEach(Array(stride(from: 0, to: options.count, by: columns)), id: \.self) { start in
                    GridRow {
                        ForEach(options[start..<min(start + columns, options.count)], id: \.self) { o in segment(o) }
                    }
                }
            }
        }
        .padding(.vertical, 10)
    }

    private func segment(_ o: T) -> some View {
        let on = o == selection
        return Button { selection = o } label: {
            Text(label(o)).scaledFont(15, .semibold).lineLimit(1).minimumScaleFactor(0.75)
                .foregroundStyle(on ? Color.black : Theme.dim)
                .frame(maxWidth: .infinity, minHeight: 40)
                .padding(.horizontal, 6)
                .background(Capsule().fill(on ? Color.white : Theme.tile))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title): \(label(o))")
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Settings > Background: the characters behind the main tabs, and how they look.
struct BackgroundRows: View {
    @AppStorage(BackdropSetting.on) private var on = true
    @AppStorage(BackdropSetting.characters) private var characters = AsciiField.Characters.classic.rawValue
    @AppStorage(BackdropSetting.density) private var density = AsciiField.Density.medium.rawValue
    @AppStorage(BackdropSetting.colour) private var colour = AsciiField.Colour.grey.rawValue
    @AppStorage(BackdropSetting.source) private var source = AsciiField.Source.both.rawValue
    @AppStorage(BackdropSetting.strength) private var strength = AsciiField.defaultStrength
    @AppStorage(BackdropSetting.drift) private var drift = false
    @AppStorage("saver") private var saver = false // only to show at once why the drift is paused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    var body: some View {
        let blocker = BackdropSetting.driftBlocker(lowPower: lowPower, reduceMotion: reduceMotion, saver: saver)
        RowCard {
            SwitchRow(icon: "character.textbox", title: "Background",
                      detail: on ? "Drawn once, so it costs almost no battery. Pure black saves the most." : "Pure black", isOn: $on)
            if on {
                BackdropPreview(strength: strength).padding(.bottom, 6)
                ChoiceRow(icon: "textformat", title: "Characters", options: AsciiField.Characters.allCases, label: \.label,
                          selection: pick($characters, AsciiField.Characters.classic), columns: 2)
                ChoiceRow(icon: "square.grid.3x3", title: "Density", options: AsciiField.Density.allCases, label: \.label,
                          selection: pick($density, AsciiField.Density.medium))
                ChoiceRow(icon: "paintpalette.fill", title: "Colour", options: AsciiField.Colour.allCases, label: \.label,
                          selection: pick($colour, AsciiField.Colour.grey))
                ChoiceRow(icon: "sun.max.fill", title: "Source", options: AsciiField.Source.allCases, label: \.label,
                          selection: pick($source, AsciiField.Source.both))
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        RowLabel(icon: "circle.lefthalf.filled", title: "Strength", detail: "Text stays readable at every strength.")
                        Spacer(minLength: 8)
                        Text("\(Int((strength * 100).rounded())) %").scaledFont(15).foregroundStyle(Theme.dim).monospacedDigit()
                    }
                    Slider(value: $strength, in: 0.2...1).tint(Theme.text)
                        .accessibilityLabel("Strength").accessibilityValue("\(Int((strength * 100).rounded())) percent")
                }
                .padding(.vertical, 10)
                SwitchRow(icon: "wind", title: "Slow drift",
                          detail: blocker ?? "The clouds move slowly while DarkProof is open. Uses a little battery.",
                          isOn: Binding(get: { drift && blocker == nil }, set: { drift = $0 }))
                    .disabled(blocker != nil)
                    .id("slowDrift")
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
            lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        }
    }

    /// A saved word as one of the options (the default if what is saved isn't one).
    private func pick<T: RawRepresentable>(_ saved: Binding<String>, _ fallback: T) -> Binding<T> where T.RawValue == String {
        Binding(get: { T(rawValue: saved.wrappedValue) ?? fallback }, set: { saved.wrappedValue = $0.rawValue })
    }
}
