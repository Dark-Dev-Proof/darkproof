#!/usr/bin/env python3
"""Makes the two data files of the outages page (outages.html).

  python3 site/make-outages.py map PATH/ne_110m_admin_0_countries.geojson
      Writes site/outage-map.js: the land of the world as dots, grouped by country code. The outlines are Natural
      Earth's 1:110m countries (public domain, naturalearthdata.com). That file is not kept in the repo: download
      it again when the dots have to be remade.

  python3 site/make-outages.py seed
      Writes site/outages.json: IODA's summary of internet outages by country for the last 24 hours, in the short
      form the page reads. On the server outages-refresh.php rewrites the same file every hour; this is the copy
      that ships with a deploy, so the page is never empty.

The dots sit on rows of equal latitude, with fewer dots in a row the nearer it is to a pole, so they are evenly
spaced on a globe. Drawn flat with the same spacing (a sinusoidal map) they form a regular grid.
"""
import json
import math
import sys
import time
import urllib.request
from pathlib import Path

SITE = Path(__file__).resolve().parent
STEP = 2.0          # degrees between rows, and between dots at the equator
LAT0 = -55.0        # the first row; nothing is drawn south of it (Antarctica)
ROWS = 70           # the last row is at 83 degrees north
# Places Natural Earth draws apart that the outage feed counts under another country's code.
MERGE = {"CYN": "CY", "SOL": "SO"}
IODA = "https://api.ioda.inetintel.cc.gatech.edu/v2/outages/summary"


def code_of(props):
    for key in ("ISO_A2_EH", "ISO_A2"):
        value = props.get(key)
        if isinstance(value, str) and len(value) == 2 and value.isalpha():
            return value.upper()
    a3 = str(props.get("ADM0_A3", "")).upper()
    return MERGE.get(a3, a3)


def polygons_of(geometry):
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    return geometry["coordinates"]


def inside_ring(lon, lat, ring):
    hit = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def inside_polygon(lon, lat, rings):
    if not inside_ring(lon, lat, rings[0]):
        return False
    return not any(inside_ring(lon, lat, hole) for hole in rings[1:])


def ring_area_and_centre(ring):
    area = cx = cy = 0.0
    for i in range(len(ring) - 1):
        x0, y0 = ring[i][0], ring[i][1]
        x1, y1 = ring[i + 1][0], ring[i + 1][1]
        cross = x0 * y1 - x1 * y0
        area += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    if abs(area) < 1e-9:
        return 0.0, (ring[0][0], ring[0][1])
    return abs(area) / 2, (cx / (3 * area), cy / (3 * area))


def make_map(source):
    features = json.loads(Path(source).read_text(encoding="utf-8"))["features"]
    shapes = []                      # (code, bbox, rings)
    centres = {}                     # code -> (area, (lon, lat)) of its largest piece
    for feature in features:
        code = code_of(feature["properties"])
        for rings in polygons_of(feature["geometry"]):
            xs = [p[0] for p in rings[0]]
            ys = [p[1] for p in rings[0]]
            shapes.append((code, (min(xs), min(ys), max(xs), max(ys)), rings))
            area, centre = ring_area_and_centre(rings[0])
            if code not in centres or area > centres[code][0]:
                centres[code] = (area, centre)

    per = []                         # dots in each row
    owner = {}                       # (row, k) -> code
    for row in range(ROWS):
        lat = LAT0 + row * STEP
        count = max(1, round(360 / STEP * math.cos(math.radians(lat))))
        per.append(count)
        for k in range(count):
            lon = -180 + (k + 0.5) * 360 / count
            for code, (x0, y0, x1, y1), rings in shapes:
                if x0 <= lon <= x1 and y0 <= lat <= y1 and inside_polygon(lon, lat, rings):
                    owner[(row, k)] = code
                    break

    # Every country gets at least one dot: a small one takes the free dot nearest its middle, or one from a
    # neighbour that has plenty.
    counts = {}
    for code in owner.values():
        counts[code] = counts.get(code, 0) + 1
    for code in sorted(c for c in centres if c not in counts):
        lon0, lat0 = centres[code][1]
        if lat0 < LAT0 - STEP:
            continue
        best = None
        for row in range(ROWS):
            lat = LAT0 + row * STEP
            if abs(lat - lat0) > 6:
                continue
            for k in range(per[row]):
                lon = -180 + (k + 0.5) * 360 / per[row]
                held = owner.get((row, k))
                if held is not None and counts.get(held, 0) <= 6:
                    continue
                d = (lat - lat0) ** 2 + ((lon - lon0) * math.cos(math.radians(lat0))) ** 2
                if best is None or d < best[0]:
                    best = (d, row, k)
        if best is None:
            continue
        held = owner.get((best[1], best[2]))
        if held is not None:
            counts[held] -= 1
        owner[(best[1], best[2])] = code
        counts[code] = 1

    by_code = {}
    for (row, k), code in sorted(owner.items()):
        by_code.setdefault(code, []).append(f"{row} {k}")
    data = {"step": STEP, "lat0": LAT0, "per": per, "c": {c: " ".join(v) for c, v in sorted(by_code.items())}}
    text = ("/* The land of the world as dots, grouped by country code. Made by make-outages.py from Natural Earth's\n"
            "   1:110m countries (public domain). Don't edit by hand. */\n"
            "window.DP_MAP=" + json.dumps(data, separators=(",", ":")) + ";\n")
    (SITE / "outage-map.js").write_text(text, encoding="utf-8")
    print(f"outage-map.js: {len(owner)} dots, {len(by_code)} countries, {len(text)} bytes")
    missing = sorted(c for c in centres if c not in by_code)
    if missing:
        print("no dot:", " ".join(missing))


def make_seed():
    now = int(time.time())
    start = now - 86400
    url = f"{IODA}?entityType=country&from={start}&until={now}&limit=400"
    request = urllib.request.Request(url, headers={"User-Agent": "outage-map-cache/1"})
    with urllib.request.urlopen(request, timeout=30) as reply:
        data = json.load(reply)
    rows = []
    for item in data["data"]:
        code = item["entity"]["code"]
        name = item["entity"]["name"]
        score = item["scores"]["overall"]
        if len(code) == 2 and code.isalpha() and len(name) <= 120:
            rows.append([code.upper(), name, round(score), int(item.get("event_cnt", 0))])
    rows.sort(key=lambda r: -r[2])
    out = {"v": 1, "updated": now, "from": start, "until": now,
           "source": "IODA, Georgia Institute of Technology", "rows": rows}
    (SITE / "outages.json").write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n",
                                       encoding="utf-8")
    print(f"outages.json: {len(rows)} countries and territories")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "map":
        make_map(sys.argv[2])
    elif len(sys.argv) == 2 and sys.argv[1] == "seed":
        make_seed()
    else:
        sys.exit(__doc__)
