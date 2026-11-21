#!/usr/bin/env python3
"""Puts the icons the home page uses into index.html, between the "icons:start" and "icons:end" lines.

Two kinds of icons, all copied into the page so that the site loads nothing from outside:
- Phosphor Icons (MIT, see LICENSE-phosphor-icons.txt), written "i-<name>" for the regular weight and
  "i-<name>-bold" or "i-<name>-fill" for the others.
- Our own drawings in OWN below, written "i-sys-<name>": the status bar, battery, pin, arrow and sparkles of the
  simulated phone, drawn here in the style of a phone's system icons. They are not Apple's files: SF Symbols may only
  be used in apps on Apple devices, so they can't be put on a website.

Run it after using a new icon in index.html or demo.js:

    npm pack @phosphor-icons/core@2.1.1 && tar -xzf phosphor-icons-core-2.1.1.tgz
    python3 site/make-icons.py package
"""
import pathlib
import re
import sys


def star(cx, cy, r, k=0.2):
    """A four-pointed star with curved sides."""
    c = k * r
    return ("M%g %gQ%g %g %g %gQ%g %g %g %gQ%g %g %g %gQ%g %g %g %gZ" % (
        cx, cy - r, cx + c, cy - c, cx + r, cy, cx + c, cy + c, cx, cy + r, cx - c, cy + c, cx - r, cy, cx - c, cy - c, cx, cy - r))


def play():
    """The play triangle in four separate pieces, each pulled in a little so gaps show between them."""
    a, b, d, o, top, bot = (70, 48), (214, 128), (70, 208), (134, 128), (158.6, 97.2), (158.6, 158.8)
    out = ""
    for piece in ((a, o, d), (a, top, o), (d, o, bot), (o, top, b, bot)):
        cx = sum(p[0] for p in piece) / len(piece); cy = sum(p[1] for p in piece) / len(piece)
        pts = " ".join("%.1f,%.1f" % (cx + (x - cx) * 0.8, cy + (y - cy) * 0.8) for x, y in piece)
        out += '<polygon points="%s" stroke="currentColor" stroke-width="12" stroke-linejoin="round"/>' % pts
    return out


def battery(level):
    """A battery lying on its side, filled up to level (0 to 1)."""
    return ('<rect x="0.5" y="0.5" width="23" height="12" rx="3.6" fill="none" stroke="currentColor" stroke-opacity="0.45"/>'
            '<rect x="2" y="2" width="%g" height="9" rx="2.2"/>'
            '<path d="M25 4.5C26.2 4.9 26.5 5.8 26.5 6.5C26.5 7.2 26.2 8.1 25 8.5Z" fill-opacity="0.5"/>' % max(1.5, 20 * level))


# name: (viewBox, drawing)
OWN = {
    "sys-signal": ("0 0 18 12", '<rect y="8" width="3" height="4" rx="1"/><rect x="5" y="5.5" width="3" height="6.5" rx="1"/>'
                                '<rect x="10" y="3" width="3" height="9" rx="1"/><rect x="15" width="3" height="12" rx="1"/>'),
    "sys-wifi": ("0 0 16 12", '<path d="M0.01 3.31A11.3 11.3 0 0 1 15.99 3.31L14.505 4.795A9.2 9.2 0 0 0 1.495 4.795Z"/>'
                              '<path d="M2.697 5.997A7.5 7.5 0 0 1 13.303 5.997L11.818 7.482A5.4 5.4 0 0 0 4.182 7.482Z"/>'
                              '<path d="M8 11.3L5.596 8.896A3.4 3.4 0 0 1 10.404 8.896Z"/>'),
    "sys-battery-0": ("0 0 27 13", battery(0)),
    "sys-battery-25": ("0 0 27 13", battery(0.25)),
    "sys-battery-50": ("0 0 27 13", battery(0.5)),
    "sys-battery-75": ("0 0 27 13", battery(0.75)),
    "sys-battery-100": ("0 0 27 13", battery(1)),
    "sys-arrow": ("0 0 256 256", '<path d="M128 34L206 212L128 172L50 212Z" stroke="currentColor" stroke-width="22" stroke-linejoin="round"/>'),
    "sys-sparkles": ("0 0 256 256", '<path d="%s"/><path d="%s"/><path d="%s"/>' % (star(104, 148, 96), star(198, 60, 46), star(210, 200, 32))),
    # The two stores, redrawn in the site's solid white style (the user chose this over dotted and shaded versions)
    "sys-app-store": ("0 0 256 256", '<path d="M88 164L146 60M62 208L72 190M114 64L194 206M44 164H140M200 164H214" fill="none" stroke="currentColor" stroke-width="28" stroke-linecap="round"/>'),
    "sys-google-play": ("0 0 256 256", play()),
    # Product Hunt's mark (a P in a disc), in one colour, with the path taken from their own badge picture
    "sys-product-hunt": ("0 0 31 31", '<path fill-rule="evenodd" d="M31 15.5C31 24.06 24.06 31 15.5 31S0 24.06 0 15.5 6.94 0 15.5 0 31 6.94 31 15.5ZM17.43 15.96H13.09V11.31H17.43C18.7 11.31 19.73 12.35 19.73 13.63S18.7 15.96 17.43 15.96ZM17.43 8.21H10.03V23.71H13.09V19.06H17.43C20.39 19.06 22.79 16.63 22.79 13.63S20.39 8.21 17.43 8.21Z"/>'),
    "sys-pin": ("0 0 256 256", '<ellipse cx="128" cy="206" rx="82" ry="28" fill="none" stroke="currentColor" stroke-width="18"/>'
                               '<rect x="118" y="112" width="20" height="96" rx="10"/><circle cx="128" cy="74" r="54"/>'),
}

site = pathlib.Path(__file__).resolve().parent
if len(sys.argv) != 2:
    sys.exit("usage: make-icons.py <folder of the unpacked @phosphor-icons/core package>")
assets = pathlib.Path(sys.argv[1]) / "assets"

page = (site / "index.html").read_text(encoding="utf-8")
script = (site / "demo.js").read_text(encoding="utf-8")
names = set(re.findall(r'href="#i-([a-z0-9-]+)"', page))
names |= set(re.findall(r'"([a-z0-9]+(?:-[a-z0-9]+)*-(?:fill|bold))"', script))
names |= set(OWN)  # demo.js builds some of these names, so all of them go in

symbols = []
for name in sorted(names):
    if name in OWN:
        symbols.append('<symbol id="i-%s" viewBox="%s">%s</symbol>' % (name, OWN[name][0], OWN[name][1]))
        continue
    weight = "fill" if name.endswith("-fill") else "bold" if name.endswith("-bold") else "regular"
    path = assets / weight / (name + ".svg")
    if not path.is_file():
        sys.exit("no such icon: " + name)
    svg = path.read_text(encoding="utf-8")
    inner = re.search(r"<svg[^>]*>(.*)</svg>", svg, re.S).group(1).strip()
    if re.search(r"<(?!/?(?:path|rect|circle|line|polyline|polygon|ellipse)\b)", inner) or re.search(r"\son\w+=|href=", inner):
        sys.exit("unexpected content in icon: " + name)
    symbols.append('<symbol id="i-%s" viewBox="0 0 256 256">%s</symbol>' % (name, inner))

block = '<!-- icons:start -->\n<svg class="sprite" aria-hidden="true">\n' + "\n".join(symbols) + "\n</svg>\n<!-- icons:end -->"
new, count = re.subn(r"<!-- icons:start -->.*?<!-- icons:end -->", lambda _: block, page, flags=re.S)
if count != 1:
    sys.exit("index.html has no icons:start / icons:end lines")
(site / "index.html").write_text(new, encoding="utf-8")
print("%d icons written to index.html (%d our own)" % (len(symbols), len(OWN)))
