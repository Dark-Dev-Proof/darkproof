import SwiftUI
import MapLibre
import BlackoutCore

/// Something to show on the street map: a shared place or a friend's last check-in.
struct MapPin: Equatable, Identifiable {
    let id: String
    let lat: Double
    let lon: Double
    let title: String
    let subtitle: String
    let symbol: String?  // SF Symbol, or nil to show `letter`
    let letter: String
    let tint: UIColor   // the ring: white, grey for an old place, green for "I'm safe", red for help
    var help = false    // someone asking for help: always a pin of its own with its VoiceOver label, never in a cluster
}

/// How pins look: a charcoal disc with a white glyph and a coloured ring, and for nearby pins merged together, a charcoal
/// bubble with the count. Drawn on the phone, so the map needs nothing from the internet for them (no sprite, no font).
enum PinArt {
    static let size: CGFloat = 38
    static let clusterPrefix = "dp-cluster-"

    static func pin(_ p: MapPin) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { _ in
            let disc = UIBezierPath(ovalIn: CGRect(x: 1.25, y: 1.25, width: size - 2.5, height: size - 2.5))
            UIColor(white: 0.13, alpha: 1).setFill(); disc.fill()
            p.tint.setStroke(); disc.lineWidth = 2.5; disc.stroke()
            if let s = p.symbol, let img = UIImage(systemName: s, withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .bold)) {
                let w = img.withTintColor(.white, renderingMode: .alwaysOriginal)
                w.draw(at: CGPoint(x: (size - w.size.width) / 2, y: (size - w.size.height) / 2))
            } else {
                centered(p.letter, .systemFont(ofSize: 16, weight: .bold), in: size)
            }
        }
    }

    /// The bubble for [n] pins: the number, shortened from a thousand on.
    static func cluster(_ n: Int) -> UIImage {
        let label = n < 1_000 ? "\(n)" : n < 10_000 ? "\(n / 1_000).\(n % 1_000 / 100)k" : "\(n / 1_000)k"
        let font = UIFont.systemFont(ofSize: 15, weight: .bold)
        let disc = max(40, ceil((label as NSString).size(withAttributes: [.font: font]).width) + 24), halo: CGFloat = 5
        let s = disc + 2 * halo
        return UIGraphicsImageRenderer(size: CGSize(width: s, height: s)).image { _ in
            UIColor(white: 1, alpha: 0.16).setFill(); UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: s, height: s)).fill()
            let d = UIBezierPath(ovalIn: CGRect(x: halo + 0.75, y: halo + 0.75, width: disc - 1.5, height: disc - 1.5))
            UIColor(white: 0.13, alpha: 1).setFill(); d.fill()
            UIColor.white.setStroke(); d.lineWidth = 1.5; d.stroke()
            centered(label, font, in: s)
        }
    }

    /// The image name of a pin's look: pins that look the same share one image.
    static func name(_ p: MapPin) -> String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        p.tint.getRed(&r, green: &g, blue: &b, alpha: &a)
        return "dp-pin|\(p.symbol ?? "")|\(Int(r * 255)),\(Int(g * 255)),\(Int(b * 255)),\(Int(a * 255))|\(p.symbol == nil ? p.letter : "")"
    }

    private static func centered(_ text: String, _ font: UIFont, in side: CGFloat) {
        let s = text as NSString, attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
        let sz = s.size(withAttributes: attrs)
        s.draw(at: CGPoint(x: (side - sz.width) / 2, y: (side - sz.height) / 2), withAttributes: attrs)
    }
}

/// The dark OpenFreeMap street map with you, friends and places. Works offline in the saved area.
///
/// Up to `clusterAbove` pins, each pin is its own view with a VoiceOver label and a callout, as before. Above that, the
/// map draws the pins itself and merges nearby ones into a count bubble (tap it to zoom in); drawn pins have no VoiceOver
/// label, so people asking for help always stay views, and while VoiceOver is on every pin stays a view. The Places list
/// shows every place in any case.
struct StreetMap: UIViewRepresentable {
    static let clusterAbove = 20
    let pins: [MapPin]
    let center: Spot?
    var open: (String) -> Void = { _ in } // pin id
    var moved: (Spot?) -> Void = { _ in } // the middle of the map once zoomed in on an area (zoom 9+), else nil

    func makeUIView(context: Context) -> MLNMapView {
        let m = MLNMapView(frame: .zero, styleURL: OfflineMap.styleURL)
        m.delegate = context.coordinator
        m.showsUserLocation = true
        m.tintColor = .white
        m.maximumZoomLevel = 18
        m.compassView.compassVisibility = .adaptive
        if let c = center { m.setCenter(CLLocationCoordinate2D(latitude: c.lat, longitude: c.lon), zoomLevel: 14, animated: false) }
        context.coordinator.attach(m)
        return m
    }

    func updateUIView(_ m: MLNMapView, context: Context) {
        context.coordinator.open = open
        context.coordinator.moved = moved
        context.coordinator.show(pins, on: m)
        if !context.coordinator.centered, let c = center {
            context.coordinator.centered = true
            m.setCenter(CLLocationCoordinate2D(latitude: c.lat, longitude: c.lon), zoomLevel: 14, animated: false)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(centered: center != nil) }

    final class PinAnnotation: MLNPointAnnotation {
        let pin: MapPin
        init(_ pin: MapPin) {
            self.pin = pin
            super.init()
            coordinate = CLLocationCoordinate2D(latitude: pin.lat, longitude: pin.lon)
            title = pin.title; subtitle = pin.subtitle
        }
        required init?(coder: NSCoder) { fatalError("not used") }
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        static let source = "dp-pins", singles = "dp-pins", clusters = "dp-clusters"
        var centered: Bool
        var open: (String) -> Void = { _ in }
        var moved: (Spot?) -> Void = { _ in }
        private weak var map: MLNMapView?
        private var pins: [MapPin] = []
        private var drawn: [MapPin] = []          // pins the map draws itself (clustered)
        private var drawnKeys: [String]? = nil    // what the map's source holds now
        private var images: [String: UIImage] = [:]
        private var voiceOver: NSObjectProtocol?
        init(centered: Bool) { self.centered = centered }
        deinit { if let voiceOver { NotificationCenter.default.removeObserver(voiceOver) } }

        func attach(_ m: MLNMapView) {
            map = m
            // A tap on a drawn pin or a bubble. It waits for the map's own taps (zoom, choosing a pin view) to pass.
            let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
            for r in m.gestureRecognizers ?? [] where r is UITapGestureRecognizer { tap.require(toFail: r) }
            m.addGestureRecognizer(tap)
            voiceOver = NotificationCenter.default.addObserver(forName: UIAccessibility.voiceOverStatusDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
                guard let self, let m = self.map else { return }
                self.show(self.pins, on: m)
            }
        }

        /// Pin views for few pins (or with VoiceOver on), drawn and clustered pins for many; help pins are always views.
        func show(_ pins: [MapPin], on m: MLNMapView) {
            self.pins = pins
            let cluster = pins.count > StreetMap.clusterAbove && !UIAccessibility.isVoiceOverRunning
            let views = cluster ? pins.filter(\.help) : pins
            drawn = cluster ? pins.filter { !$0.help } : []
            let shown = (m.annotations ?? []).compactMap { $0 as? PinAnnotation }
            let current = Dictionary(views.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            m.removeAnnotations(shown.filter { a in current[a.pin.id] != a.pin }) // gone, changed or now drawn
            let kept = Set(shown.filter { a in current[a.pin.id] == a.pin }.map(\.pin.id))
            m.addAnnotations(views.filter { !kept.contains($0.id) }.map(PinAnnotation.init))
            if let style = m.style { draw(on: style) }
        }

        private func draw(on style: MLNStyle) {
            guard let source = style.source(withIdentifier: Self.source) as? MLNShapeSource else { return }
            let named = drawn.map { ($0, PinArt.name($0)) }
            let keys = named.map { p, n in "\(p.id)|\(p.lat)|\(p.lon)|\(n)" }
            if keys == drawnKeys { return }
            drawnKeys = keys
            source.shape = MLNShapeCollectionFeature(shapes: named.map { p, n in
                if images[n] == nil { let img = PinArt.pin(p); images[n] = img; style.setImage(img, forName: n) }
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
                f.attributes = ["pin": p.id, "icon": n]
                return f
            })
        }

        func mapView(_ m: MLNMapView, didFinishLoading style: MLNStyle) {
            images = [:]; drawnKeys = nil
            let source = MLNShapeSource(identifier: Self.source, features: [], options: [
                .clustered: true, .clusterRadius: 50, .maximumZoomLevelForClustering: 14])
            style.addSource(source)
            let singles = MLNSymbolStyleLayer(identifier: Self.singles, source: source)
            singles.predicate = NSPredicate(format: "cluster != YES")
            singles.iconImageName = NSExpression(forKeyPath: "icon")
            let clusters = MLNSymbolStyleLayer(identifier: Self.clusters, source: source)
            clusters.predicate = NSPredicate(format: "cluster == YES")
            let toString: [Any] = ["to-string", ["get", "point_count"]]
            clusters.iconImageName = NSExpression(mglJSONObject: ["concat", PinArt.clusterPrefix, toString] as [Any]) // "dp-cluster-12"
            for l in [singles, clusters] {
                l.iconAllowsOverlap = NSExpression(forConstantValue: true)
                l.iconIgnoresPlacement = NSExpression(forConstantValue: true)
                style.addLayer(l)
            }
            draw(on: style)
        }

        /// The map asks for a bubble (or a pin image it dropped while unused) when it needs one.
        func mapView(_ m: MLNMapView, didFailToLoadImage name: String) -> UIImage? {
            if let img = images[name] { return img }
            if name.hasPrefix(PinArt.clusterPrefix), let n = Int(name.dropFirst(PinArt.clusterPrefix.count)) { return PinArt.cluster(n) }
            return nil
        }

        @objc private func tapped(_ g: UITapGestureRecognizer) {
            guard g.state == .ended, let m = map, !drawn.isEmpty else { return }
            let p = g.location(in: m), box = CGRect(x: p.x - 8, y: p.y - 8, width: 16, height: 16)
            if let f = m.visibleFeatures(in: box, styleLayerIdentifiers: [Self.singles]).first, let id = f.attribute(forKey: "pin") as? String {
                open(id); return
            }
            guard let c = m.visibleFeatures(in: box, styleLayerIdentifiers: [Self.clusters]).compactMap({ $0 as? MLNPointFeatureCluster }).first,
                  let source = m.style?.source(withIdentifier: Self.source) as? MLNShapeSource else { return }
            let z = source.zoomLevel(forExpanding: c)
            m.setCenter(c.coordinate, zoomLevel: z > m.zoomLevel ? z : m.zoomLevel + 2, animated: !(UIAccessibility.isReduceMotionEnabled || Theme.lowPower))
        }

        func mapView(_ m: MLNMapView, regionDidChangeAnimated animated: Bool) {
            let c = m.centerCoordinate, z = m.zoomLevel
            DispatchQueue.main.async { self.moved(z >= 9 ? Spot(lat: c.latitude, lon: c.longitude) : nil) }
        }

        func mapView(_ m: MLNMapView, viewFor annotation: MLNAnnotation) -> MLNAnnotationView? {
            guard let a = annotation as? PinAnnotation else { return nil }
            let v = MLNAnnotationView(reuseIdentifier: nil)
            v.frame = CGRect(x: 0, y: 0, width: PinArt.size, height: PinArt.size)
            let iv = UIImageView(image: PinArt.pin(a.pin)); iv.frame = v.bounds; v.addSubview(iv)
            v.accessibilityLabel = "\(a.pin.title), \(a.pin.subtitle)"
            return v
        }

        func mapView(_ m: MLNMapView, annotationCanShowCallout annotation: MLNAnnotation) -> Bool { annotation is PinAnnotation }

        func mapView(_ m: MLNMapView, rightCalloutAccessoryViewFor annotation: MLNAnnotation) -> UIView? {
            guard annotation is PinAnnotation else { return nil }
            let b = UIButton(type: .system)
            b.setImage(UIImage(systemName: "location.north.fill"), for: .normal)
            b.tintColor = .white
            b.frame = CGRect(x: 0, y: 0, width: 32, height: 32)
            b.accessibilityLabel = "Compass"
            return b
        }

        func mapView(_ m: MLNMapView, annotation: MLNAnnotation, calloutAccessoryControlTapped control: UIControl) {
            if let a = annotation as? PinAnnotation { m.deselectAnnotation(a, animated: false); open(a.pin.id) }
        }
    }
}

/// Save, update or delete the offline map of your area: around you, or around the middle of the map on screen when
/// the phone has no position (location off, or no GPS fix indoors).
struct OfflineMapCard: View {
    @ObservedObject var map = OfflineMap.shared
    @ObservedObject private var loc = LocationService.shared
    let me: Spot?
    var mapCenter: Spot? = nil
    private var target: Spot? { me ?? mapCenter }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch map.state {
            case .none:
                Text("Save this map for the blackout").scaledFont(17, .bold).foregroundStyle(Theme.text)
                Text(me != nil ? "Downloads the streets about 10 km around you (up to 50 MB) while you still have internet. Then the map works with none."
                               : "Downloads the streets about 10 km around the middle of the map (up to 50 MB) while you still have internet.")
                    .scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                if me == nil {
                    Text(loc.denied ? "Location is off for DarkProof, so move and zoom the map to your area first."
                                    : mapCenter == nil ? "Finding your position… or zoom the map to your area." : "No position yet: it saves the area on screen.")
                        .scaledFont(14, .semibold).foregroundStyle(Theme.text).fixedSize(horizontal: false, vertical: true)
                }
                download(me != nil ? "Download map" : "Save the area on screen")
            case .downloading(let p):
                Text("Saving the map… \(Int(p * 100))%").scaledFont(17, .bold).foregroundStyle(Theme.text)
                ProgressLine(progress: p)
                Button("Cancel") { map.cancel() }.buttonStyle(GhostButton())
            case .ready(let bytes, let center, let ts):
                HStack(alignment: .firstTextBaseline) {
                    Label("Saved for offline use", systemImage: "checkmark.circle.fill").scaledFont(15, .bold)
                        .foregroundStyle(Theme.safe)
                    Spacer()
                    Text(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)).scaledFont(13).foregroundStyle(Theme.dim)
                }
                Text(savedText(center, ts)).scaledFont(14).foregroundStyle(Theme.dim).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 10) {
                    download(me != nil ? "Update to here" : "Save the area on screen", ghost: true)
                    Button("Delete") { map.delete() }.buttonStyle(GhostButton())
                }
            case .failed(let why):
                Text("The map wasn't saved").scaledFont(17, .bold).foregroundStyle(Theme.text)
                Text(why).scaledFont(14).foregroundStyle(Theme.help)
                download("Try again")
            }
        }
        .padding(18)
        .background(Theme.card.opacity(0.96), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private func savedText(_ c: Spot, _ ts: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ts) / 1000).formatted(date: .abbreviated, time: .omitted)
        if let me {
            let d = Geo.distance(me, c)
            return d < 300 ? "About 20 km across, around where you are. Saved \(date)."
                           : "About 20 km across, centered \(FindSheet.meters(d)) from here. Saved \(date)."
        }
        return "About 20 km across. Saved \(date)."
    }

    @ViewBuilder private func download(_ title: String, ghost: Bool = false) -> some View {
        let b = Button(title) { if let t = target { map.download(around: t) } }.disabled(target == nil)
        if ghost { b.buttonStyle(GhostButton()) } else { b.buttonStyle(PrimaryButton()) }
    }
}
