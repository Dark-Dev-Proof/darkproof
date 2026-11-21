// Makes favicons without a background from a picture of a dark mark on white:
//   <outDir>/favicon.png       black mark, see-through around it (for light browser tabs)
//   <outDir>/favicon-dark.png  white mark, see-through around it (for dark browser tabs)
//   <outDir>/favicon.svg       the mark as shapes: black, and white when the browser is in dark mode
// Usage: swift make-favicon-clear.swift <in.png> <outDir> <size> <margin 0..0.3>
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count == 5, let size = Int(args[3]), let margin = Double(args[4]) else {
    print("usage: make-favicon-clear.swift in outDir size margin"); exit(2)
}
guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[1]) as CFURL, nil),
      let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { print("cannot read input"); exit(1) }
let outDir = args[2]
let w = img.width, h = img.height
let cs = CGColorSpace(name: CGColorSpace.sRGB)!

// Brightness (0 dark .. 255 light) of a picture drawn on white. Row 0 is the top row.
func brightness(width: Int, height: Int, draw: (CGContext) -> Void) -> [UInt8] {
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                        space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
    ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    draw(ctx)
    let p = ctx.data!.bindMemory(to: UInt8.self, capacity: width * height * 4)
    var out = [UInt8](repeating: 255, count: width * height)
    for i in 0..<(width * height) {
        out[i] = UInt8((Int(p[i * 4]) * 299 + Int(p[i * 4 + 1]) * 587 + Int(p[i * 4 + 2]) * 114) / 1000)
    }
    return out
}

// 1. Where the mark is.
let lum = brightness(width: w, height: h) { $0.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h)) }
@inline(__always) func dark(_ x: Int, _ y: Int) -> Bool { x >= 0 && y >= 0 && x < w && y < h && lum[y * w + x] < 128 }
var minX = w, minY = h, maxX = -1, maxY = -1
for y in 0..<h { for x in 0..<w where lum[y * w + x] < 128 {
    if x < minX { minX = x }; if x > maxX { maxX = x }; if y < minY { minY = y }; if y > maxY { maxY = y }
} }
guard maxX >= 0 else { print("no dark mark found"); exit(1) }
let bw = Double(maxX - minX + 1), bh = Double(maxY - minY + 1)
let side = max(bw, bh) * (1 + 2 * margin)
let cropLeft = Double(minX) + bw / 2 - side / 2, cropTop = Double(minY) + bh / 2 - side / 2
print("mark \(Int(bw))x\(Int(bh)) at (\(minX),\(minY)); square of \(Int(side.rounded()))")

// 2. The two PNG files: the mark's darkness becomes how solid each pixel is.
let scale = Double(size) / side
let small = brightness(width: size, height: size) { ctx in
    ctx.interpolationQuality = .high
    let cropBottom = Double(h) - (cropTop + side)
    ctx.draw(img, in: CGRect(x: -cropLeft * scale, y: -cropBottom * scale, width: Double(w) * scale, height: Double(h) * scale))
}
func writePNG(_ name: String, white: Bool) {
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                        space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let p = ctx.data!.bindMemory(to: UInt8.self, capacity: size * size * 4)
    for i in 0..<(size * size) {
        let a = 255 - small[i]
        let c: UInt8 = white ? a : 0          // colours are stored already multiplied by how solid the pixel is
        p[i * 4] = c; p[i * 4 + 1] = c; p[i * 4 + 2] = c; p[i * 4 + 3] = a
    }
    let url = URL(fileURLWithPath: outDir + "/" + name)
    let dst = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dst, ctx.makeImage()!, nil)
    guard CGImageDestinationFinalize(dst) else { print("cannot write \(name)"); exit(1) }
    print("wrote \(name) \(size)x\(size)")
}
writePNG("favicon.png", white: false)
writePNG("favicon-dark.png", white: true)

// 3. The outline of the mark, as closed loops along the pixel edges (dark always on the right hand side).
let vw = w + 1
var outA = [Int32](repeating: -1, count: vw * (h + 1)), outB = [Int32](repeating: -1, count: vw * (h + 1))
var usedA = [Bool](repeating: false, count: vw * (h + 1)), usedB = [Bool](repeating: false, count: vw * (h + 1))
func addEdge(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) {
    let a = y0 * vw + x0, b = Int32(y1 * vw + x1)
    if outA[a] < 0 { outA[a] = b } else { outB[a] = b }
}
for y in 0..<h { for x in 0..<w where dark(x, y) {
    if !dark(x, y - 1) { addEdge(x, y, x + 1, y) }
    if !dark(x + 1, y) { addEdge(x + 1, y, x + 1, y + 1) }
    if !dark(x, y + 1) { addEdge(x + 1, y + 1, x, y + 1) }
    if !dark(x - 1, y) { addEdge(x, y + 1, x, y) }
} }
struct P { var x: Double; var y: Double }
func pt(_ v: Int) -> P { P(x: Double(v % vw), y: Double(v / vw)) }
var loops: [[P]] = []
for start in 0..<(vw * (h + 1)) {
    for slot in 0..<2 {
        let first = slot == 0 ? outA[start] : outB[start]
        if first < 0 || (slot == 0 ? usedA[start] : usedB[start]) { continue }
        if slot == 0 { usedA[start] = true } else { usedB[start] = true }
        var loop = [pt(start)]
        var prev = start, cur = Int(first)
        while cur != start {
            loop.append(pt(cur))
            let a = usedA[cur] ? -1 : Int(outA[cur]), b = usedB[cur] ? -1 : Int(outB[cur])
            var takeA = a >= 0
            if a >= 0 && b >= 0 {      // two ways on: take the one that turns right, around the same dark pixel
                let p0 = pt(prev), p1 = pt(cur), pa = pt(a)
                let cross = (p1.x - p0.x) * (pa.y - p1.y) - (p1.y - p0.y) * (pa.x - p1.x)
                takeA = cross > 0
            } else if a < 0 && b < 0 { break }
            prev = cur
            if takeA { usedA[cur] = true; cur = a } else { usedB[cur] = true; cur = b }
        }
        loops.append(loop)
    }
}

// Fewer points: keep a point only where the outline strays more than `eps` pixels from a straight line.
func simplify(_ pts: [P], eps: Double) -> [P] {
    let n = pts.count
    if n < 8 { return pts }
    var far = 0; var best = -1.0
    for i in 1..<n { let d = hypot(pts[i].x - pts[0].x, pts[i].y - pts[0].y); if d > best { best = d; far = i } }
    let ring = pts + [pts[0]]
    var keep = [Bool](repeating: false, count: n + 1)
    keep[0] = true; keep[far] = true; keep[n] = true
    var stack = [(0, far), (far, n)]
    while let (i, j) = stack.popLast() {
        if j - i < 2 { continue }
        let a = ring[i], b = ring[j]
        let len = hypot(b.x - a.x, b.y - a.y)
        var worst = -1.0, at = -1
        for k in (i + 1)..<j {
            let d = len == 0 ? hypot(ring[k].x - a.x, ring[k].y - a.y)
                             : abs((b.x - a.x) * (a.y - ring[k].y) - (a.x - ring[k].x) * (b.y - a.y)) / len
            if d > worst { worst = d; at = k }
        }
        if worst > eps { keep[at] = true; stack.append((i, at)); stack.append((at, j)) }
    }
    return (0..<n).filter { keep[$0] }.map { ring[$0] }
}
func area(_ p: [P]) -> Double {
    var s = 0.0
    for i in 0..<p.count { let q = p[(i + 1) % p.count]; s += p[i].x * q.y - q.x * p[i].y }
    return abs(s) / 2
}
// The outline follows single pixels, so it is a staircase. Average each point with its neighbours to get the
// smooth line the staircase stands for (corners get rounded by about 3 of the picture's 1254 pixels).
func smooth(_ pts: [P], reach: Int) -> [P] {
    let n = pts.count
    if n < reach * 4 { return pts }
    return (0..<n).map { i in
        var sx = 0.0, sy = 0.0
        for k in -reach...reach { let q = pts[((i + k) % n + n) % n]; sx += q.x; sy += q.y }
        return P(x: sx / Double(2 * reach + 1), y: sy / Double(2 * reach + 1))
    }
}
func tenths(_ t: Int) -> String {
    let a = abs(t), sign = t < 0 ? "-" : ""
    return a % 10 == 0 ? "\(sign)\(a / 10)" : "\(sign)\(a / 10).\(a % 10)"
}
var d = "", kept = 0, points = 0
for loop in loops where area(loop) >= 40 {
    let s = simplify(smooth(loop, reach: 3), eps: 0.45)
    if s.count < 3 { continue }
    kept += 1; points += s.count
    // Tenths of a pixel, measured from the corner of the square, written as steps from one point to the next.
    var px = Int(((s[0].x - cropLeft) * 10).rounded()), py = Int(((s[0].y - cropTop) * 10).rounded())
    d += "M\(tenths(px)) \(tenths(py))"
    for q in s.dropFirst() {
        let x = Int(((q.x - cropLeft) * 10).rounded()), y = Int(((q.y - cropTop) * 10).rounded())
        d += "l\(tenths(x - px)) \(tenths(y - py))"; px = x; py = y
    }
    d += "z"
}
let box = Int(side.rounded())
let svg = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 \(box) \(box)"><style>path{fill:#000}@media (prefers-color-scheme:dark){path{fill:#fff}}</style><path fill-rule="evenodd" d="\(d)"/></svg>

"""
try! svg.write(toFile: outDir + "/favicon.svg", atomically: true, encoding: .utf8)
print("wrote favicon.svg: \(kept) shapes from \(loops.count) outlines, \(points) points, \(svg.utf8.count) bytes")
