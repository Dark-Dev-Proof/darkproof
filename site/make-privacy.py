#!/usr/bin/env python3
"""Writes site/privacy.html from PRIVACY.md and site/terms.html from TERMS.md, so each page always says the same as
the text in the repo.

Run from the repo root after changing PRIVACY.md or TERMS.md:  python3 site/make-privacy.py
Handles what those files use: # and ## headings, paragraphs, "- " lists (one level of nesting), **bold**, `code`,
https addresses and the support e-mail address, which become links.
"""
import html
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent


def inline(text):
    t = html.escape(text, quote=False)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\bhttps://[^\s<)]*[^\s<).,;:]", lambda m: '<a href="%s" rel="noopener">%s</a>' % (m.group(0), m.group(0)), t)
    t = re.sub(r"\b([\w.+-]+@darkproof\.app)\b", r'<a href="mailto:\1">\1</a>', t)
    return t


def render(md):
    out, para, depth = [], [], 0

    def flush():
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
            para.clear()

    def close_lists(to):
        nonlocal depth
        while depth > to:
            out.append("</li></ul>")
            depth -= 1

    for line in md.splitlines():
        m = re.match(r"^( *)- (.*)$", line)
        if line.startswith("#"):
            flush(); close_lists(0)
            level = len(line) - len(line.lstrip("#"))
            text = line[level:].strip()
            out.append(f"<h{level}>{inline(text)}</h{level}>")
        elif m:
            flush()
            level = len(m.group(1)) // 2 + 1
            if level > depth:
                while depth < level:
                    out.append("<ul><li>")
                    depth += 1
            else:
                close_lists(level)
                out.append("</li><li>")
            out.append(inline(m.group(2)))
        elif not line.strip():
            flush(); close_lists(0)
        else:
            if depth:
                out.append(" " + inline(line.strip()))
            else:
                para.append(line.strip())
    flush(); close_lists(0)
    return "\n".join(out)


def write(source, target, title, description, other_href, other_label):
    """One page: `source` in the repo root becomes site/`target`. The footer links to the other legal page."""
    body = render((ROOT / source).read_text(encoding="utf-8"))
    page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'self'; img-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'">
<meta name="referrer" content="no-referrer">
<title>{title}</title>
<meta name="description" content="{description}">
<link rel="canonical" href="https://darkproof.app/{target}">
<meta property="og:type" content="article">
<meta property="og:url" content="https://darkproof.app/{target}">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{description}">
<meta property="og:image" content="https://darkproof.app/og.png">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="favicon-dark.png?v=3" type="image/png" sizes="96x96" media="(prefers-color-scheme: dark)">
<link rel="icon" href="favicon.png?v=3" type="image/png" sizes="96x96" media="(prefers-color-scheme: light)">
<link rel="icon" href="favicon.svg?v=3" type="image/svg+xml" media="(prefers-color-scheme: light)">
<link rel="stylesheet" href="style.css">
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<div class="wrap">
<header class="site">
  <a class="home" href="./"><span class="dots">DarkProof</span></a>
  <a class="act act-light act-sm" href="./#stage">Try the demo</a>
</header>
<!-- Generated from {source} by site/make-privacy.py. Edit {source}, not this file. -->
<main id="main" class="doc">
{body}
</main>
<footer class="site">
  <nav aria-label="Footer">
    <a href="./">About DarkProof</a>
    <a href="{other_href}">{other_label}</a>
    <a href="mailto:support@darkproof.app">support@darkproof.app</a>
  </nav>
</footer>
</div>
</body>
</html>
"""
    (ROOT / "site" / target).write_text(page, encoding="utf-8")
    print("wrote site/" + target)


write("PRIVACY.md", "privacy.html", "DarkProof privacy policy",
      "What DarkProof keeps on your phone, what it sends, and to whom.", "terms.html", "Terms of use")
write("TERMS.md", "terms.html", "DarkProof terms of use",
      "The terms for using the DarkProof app and website: what it is, the rules for using it, and its limits.",
      "privacy.html", "Privacy policy")
