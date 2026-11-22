# darkproof.app

The website: what DarkProof is, the privacy policy, and the page behind invite links
(`https://darkproof.app/join#name=…&code=…`, see spec/FORMAT.md "Invites"). Plain static files: no build step, no
cookies, no analytics, no outside fonts or scripts.

| File | What it is |
|---|---|
| `index.html` | Home page: what DarkProof does, with a demo of the app on a simulated iPhone, and "coming soon" for the apps. |
| `home.css`, `home.js` | Since 2026-11-19 the page has eight sections (the two long "with internet" and "without internet" sections are one chapter beside the globe, with the detail folded away, and "Built for trust" became two questions), and the phone has depth: it arrives turned, turns toward the pointer on a computer and sways slowly on a touch screen while it plays the recordings. The home page's layout, menu, scroll effects, install instructions (with an AI assistant or by hand, Android or iPhone) and the switch between the demo and the recordings. The look follows one reference on purpose: a black stage for the phone, charcoal chapters, mono labels, dot matrix names, a faint dot lattice painted as each section's background (so it stays under the words), green only for things that are live. Hover is designed: light buttons invert, arrows move, the other closing links dim. |
| `demo.css`, `demo.js` | The demo. It is a simulation: made up people, scripted answers, and it sends nothing anywhere (the page's Content-Security-Policy has `connect-src 'none'`). Screens and wording follow the iPhone app, so update it when they change. Its people are the ones in the recordings (Anna and Marco, two phones in range): keep the two in step. |
| `404.html` | The "page not found" page. Cloudflare Pages, Netlify and GitHub Pages serve it by themselves; on Hostinger `.htaccess` does. |
| `og.png` | The picture shown when the site is shared (1200 x 630). |
| `robots.txt`, `sitemap.xml` | For search engines: everything may be read, and the two pages worth listing (home and privacy). Add a page to the sitemap when one is added. |
| `clips/` | Recordings of the real iPhone app (home, chat, places, people), made in the iPhone Simulator with three test phones linked directly, no internet. The phone on the home page plays them when `<body>` has `data-clips`; "Tap the demo" switches to the simulation. Record again when the app's screens change: 668 px wide H.264, no audio, with a first frame saved as the `.jpg` poster. Never record the Invite screen or a group code. |
| `downloads/` | The test builds offered in the install section: Android (`darkproof-0.4.apk`), Windows (`darkproof-0.4.13-windows.msi`) and Mac (`darkproof-1.4.13-mac.dmg`). **Not in git**, see "Android APK download", "Windows installer download" and "Mac download" below. |
| `fonts/` | Geist and Geist Mono, the site's typefaces (SIL Open Font License, see `fonts/LICENSE-Geist.txt`), served from this site. Mono is for the small capital labels and the dot matrix lettering. |
| `make-icons.py`, `LICENSE-phosphor-icons.txt` | The icons are Phosphor Icons (MIT), plus a few of our own drawings for the phone's system icons (status bar, battery, pin, arrow, sparkles), which are defined in the script. They are drawn in the style of iPhone icons but are not Apple's files: SF Symbols may only be used in apps on Apple devices. The script copies everything into `index.html` so nothing loads from outside. Run it after using a new icon. |
| `privacy.html` | The privacy policy. Generated from `../PRIVACY.md`; don't edit it by hand. |
| `terms.html` | The terms of use, at `https://darkproof.app/terms`. Generated from `../TERMS.md`; don't edit it by hand. App stores ask for this address as the "terms of service" link: it must show the terms, never the privacy policy. The Solana dApp Store needs two things in the text: that the Solana Mobile Parties are not a party to the terms and have no liability to users, and a "no warranty" and "limits of liability" part that protects them too. |
| `outages.html`, `outages.css`, `outages.js` | The outages page, at `https://darkproof.app/outages`: a globe of dots (drawn on a canvas, no library) that can unroll into a flat map, with the countries where IODA detected a drop in internet connectivity in the last 24 hours in red, and the same as a list. Its Content-Security-Policy has `connect-src 'self'`: it reads `outages.json` from this site and nothing from anywhere else. |
| `globe.js` | The globe itself: dots in 3D on a canvas, with no library. It turns by itself and by hand, can unroll into a flat map, and takes countries to colour red. The outages page and the home page ("How it travels", land only, with points of light) both use it. |
| `outage-map.js` | Where the land is: about 2,700 dots grouped by country code. Made by `make-outages.py map` from Natural Earth's 1:110m countries (public domain); don't edit it by hand. |
| `outages.json` | Our copy of IODA's country summary, in a short form. The file in the repo is the copy that ships with a deploy (`python3 site/make-outages.py seed` renews it; do that right before building an archive). On the server `outages-refresh.php` rewrites it. The data is © Georgia Tech Research Corporation, all rights reserved: **ask IODA for permission before this page goes live**, and keep the credit on the page. |
| `outages-refresh.php` | Renews `outages.json` on the server, at most once an hour. A scheduled job can call it; without one the page asks for it when its copy is over an hour old. It needs PHP with curl and the right to write in the website folder. It also writes `outages.try` (when it last asked the source). **Not tested as of 2026-11-19**: this Mac has no PHP, so the script has never run. Check on the server that PHP files run after a static site deploy, then call it once and look at `outages.json`. |
| `darkroom.html`, `darkroom.css`, `darkroom.js` | The dark room, at `https://darkproof.app/darkroom`: a page for holders of the coin with an assistant that has no content filter and runs in the visitor's browser. Three steps (connect a Solana wallet, sign a message, download the model, 1.27 GB), then the app's Ask screen on the phone. Not linked from any page and marked `noindex`. Its Content-Security-Policy allows WebAssembly, this site, and `huggingface.co` with its file servers under `hf.co`; nothing typed there is sent anywhere. The model file is pinned to an exact version on Hugging Face in `darkroom.js`; the browser fetches it from there, so the site never hosts it. A smaller model (0.53 GB) was tried and taken out on 2026-11-19 because its answers were too poor. The one that stayed gave a dangerously wrong ibuprofen dose in the same test, so any answer that mentions a medicine amount or a dose gets a fixed note under it ("Check doses on the package. This model gets doses wrong."); the note filters nothing. On `localhost` a "Skip the check" button appears, for testing without a wallet. Tested once, on 2026-11-19, in the app's own Chrome with the smaller model: it loads, keeps the model for the next visit and answers in seconds, but that model's answers are poor and one medical answer was wrong. Browsers without two newer WebAssembly features (Safari, iPhone and Firefox at that date, as far as known) get a plain "can't run the engine yet" message; supporting them means hosting the engine's fallback build (`@wllama/wllama-compat`) here too. Not tested: the larger model, a real wallet, other browsers. |
| `holder.php` | The holder check: verifies the signed message and asks a public Solana node whether the wallet holds the coin (the address on the home page). It stores nothing. It needs PHP with curl and sodium. **Not tested as of 2026-11-19** (no PHP on this Mac). It is a soft lock: the model is public. |
| `vendor/wllama/` | The AI engine, wllama 3.13.0 (MIT, `LICENCE` beside it): `esm/index.js` and `esm/wasm/wllama.wasm`, taken unchanged from the npm package `@wllama/wllama`. Outside code: update it on purpose, never by hand. |
| `join.html`, `join.js` | Invite page. Reads the invite from the `#fragment` in the browser only and offers "Open in DarkProof" (the `darkproof://` link, which every app version understands), "Copy invite" and the downloads. Its Content-Security-Policy (`connect-src 'none'`) stops scripts from sending anything anywhere. |
| `style.css`, `logo.png` | Shared style (colors, type styles, spacing) and the logo (from `../brand/darkproof-logo.png`). |
| `favicon.png`, `favicon-dark.png`, `favicon.svg` | The icon in the browser tab, without a background: the black mark for light tabs, the white mark for dark tabs, and the mark as a vector that is black and turns white in dark mode. All three are made from `../brand/darkproof-logo-black.png` by `../brand/make-favicon.swift` (see its first lines). Each page has three icon links ending in `?v=3`; after changing the pictures, raise that number in the four pages and in `make-privacy.py`, or browsers keep the old icon for 7 days. |
| `.well-known/apple-app-site-association` | Lets the iPhone app open `https://darkproof.app/join…` links (Universal Links). |
| `.well-known/assetlinks.json` | The same for Android (App Links). |
| `_headers` | Content type and privacy headers for Netlify and Cloudflare Pages. |
| `.htaccess` | The same for Hostinger (Apache or LiteSpeed), plus `/join`, the 404 page and the www and https redirects. Keep it in step with `_headers`. |
| `.nojekyll` | Makes GitHub Pages publish `.well-known/`. |
| `make-privacy.py`, `make-outages.py`, `README.md` | Tools and notes; no need to upload them. |

## Before going live

1. **Store links**: the home page (last section) and `join.html` ("Get DarkProof") say "coming soon" for both apps, so
   there are no dead links. Once the apps are out, replace those items with store buttons
   (`https://apps.apple.com/app/id` followed by the App Store id, and
   `https://play.google.com/store/apps/details?id=it.darkproof.mesh`) and update the "When can I download it?" answer.
2. **iPhone**: in `.well-known/apple-app-site-association`, replace `TEAMID` with the Apple Developer Team ID (the Xcode
   project currently signs with team `MT94WQCB6S`). The app needs the Associated Domains entitlement
   `applinks:darkproof.app`.
3. **Android**: in `.well-known/assetlinks.json`, replace `RELEASE_KEY_SHA256` with the SHA-256 of the release signing
   certificate (with Play App Signing: Play Console → Test and release → App integrity → App signing key certificate).
   The other entry is the debug key of the development Mac (`keytool -list -v -keystore ~/.android/debug.keystore
   -alias androiddebugkey -storepass android`), so debug builds can be tested; remove it for the public release. The
   app needs an intent filter with `android:autoVerify="true"` for `https://darkproof.app` with path prefix `/join`.
4. **Privacy policy and terms of use**: after any change to `PRIVACY.md` or `TERMS.md`, run
   `python3 site/make-privacy.py` from the repo root. It writes both pages. The terms have to match what the app
   really does; they name no company, address or court, because none has been decided.

## Hosting

Any static host with HTTPS works (Cloudflare Pages, Netlify, GitHub Pages, a plain web server). Requirements:

- `https://darkproof.app/join` (no `.html`) must show `join.html`. Cloudflare Pages, Netlify and GitHub Pages do this on
  their own; on nginx use `try_files $uri $uri.html $uri/ =404;`.
- `/.well-known/apple-app-site-association` and `/.well-known/assetlinks.json` must be served over HTTPS **without
  redirects**, as `application/json`. `_headers` sets this on Netlify and Cloudflare Pages; GitHub Pages can't set
  headers, so prefer one of the others.
- If `www.darkproof.app` is used too, redirect it to `darkproof.app` for normal pages (the apps accept both in links,
  but the app-link files are only checked on `darkproof.app`).
- Keep access logs short or off if the host allows it. Browsers never send the `#fragment`, so logs don't contain
  invites, but they do contain visitors' IP addresses. Consider adding a line about the website to `PRIVACY.md`.

Steps on Cloudflare Pages (free): create a project, upload the contents of `site/` (or connect the repo with build
output directory `site`), add the custom domain `darkproof.app`, done. Netlify: drag the `site` folder onto
app.netlify.com/drop, then add the domain.

### Hostinger

The site runs on Hostinger web hosting (website `darkproof.app`, files in `public_html`). `.htaccess` covers the
requirements above; `_headers` and `.nojekyll` do nothing there.

To update the site: make a zip with the contents of `site/` at its top level, including `.htaccess` and
`.well-known/`, without `README.md`, `make-privacy.py`, `make-icons.py`, `make-outages.py`, `_headers` and `.nojekyll`. Upload it to
`public_html` and deploy it as a static site (hPanel, or the Hostinger plugin's "deploy static site archive"). The
deploy **replaces everything** in `public_html`, so anything changed by hand on the server is lost.

If the upload or the site gives no answer while a VPN is on, switch the VPN server or turn it off. On 2026-11-10
Hostinger's file server and the site gave no answer over Mullvad's Milan server (`it-mil-wg-002`), but answered over
its Zurich server (`ch-zrh-wg-202`) and without a VPN. Visitors on some VPN servers may see the same.

Caching: Hostinger tells browsers to keep styles, scripts, fonts and pictures for 7 days, and its CDN keeps copies
too. A visitor who came before an update would then get the new page with old styles and scripts (seen on
2026-11-10: icons missing in the demo). So `.htaccess` sends `Cache-Control: no-cache` for `.html`, `.css` and `.js`.
After every deploy:

1. Clear the website cache (hPanel, or the plugin's "clear website cache"). Without it one CDN address can go on
   serving an old file.
2. Compare a changed file with the local one, for example `curl -s https://darkproof.app/demo.js | shasum -a 256`
   against `shasum -a 256 site/demo.js`.

Fonts and pictures are still kept for 7 days: give a changed font or picture a new file name. The CDN also
recompresses PNG files, so the live pictures are smaller than the local ones; that is expected.

Check after deploying:

```
curl -sI https://darkproof.app/.well-known/apple-app-site-association   # 200, application/json, no redirect
curl -s  https://app-site-association.cdn-apple.com/a/v1/darkproof.app   # what iPhones will see (Apple caches it)
curl -s 'https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://darkproof.app&relation=delegate_permission/common.handle_all_urls'
adb shell pm verify-app-links --re-verify it.darkproof.mesh && adb shell pm get-app-links it.darkproof.mesh
```

### Android APK download

The install section of the home page has a "Download Android APK" button for an early access test build. The file is
`downloads/darkproof-0.4.apk` and is served at `https://darkproof.app/downloads/darkproof-0.4.apk`.

- **The file is not in git** (`.gitignore` has `*.apk`), and every deploy replaces the whole website folder. So the
  file must be in `site/downloads/` when the archive is made, or the download disappears from the site. Check with
  `unzip -l` that the archive lists it.
- Only the `.apk` goes in that folder. Never put signing keys, keystores, password files or the signing folder
  anywhere under `site/`: everything in it becomes public.
- `.htaccess` serves `.apk` files as `application/vnd.android.package-archive`, so phones offer to install them.
- For a new version: put the new file beside the old one under a new name (`darkproof-<version>.apk`; browsers keep a
  downloaded address for 7 days), then in `index.html` change the button's address, the version, the size and the
  SHA256 line. Remove the old file once nothing links to it.
- The page must not say the app is on Google Play until it is. It says that this is a test build, that it does not
  update by itself, and that installing it does not count toward the Google Play closed test.

Version 0.4 (version code 4, package `it.darkproof.mesh`, Android 8 or newer): 49375472 bytes, SHA-256
`54544dfde6f92527c82c5f1b96cc9469e377524cc020bee05747e0f0dd6e648e`, signed with the upload certificate
(SHA-256 `675eac77af5d021b8dd102b3b2b241bf04b111557e7ea7e0aa65345c531ad055`). A build from Google Play may be signed
with another key (Play App Signing); Android then refuses to install one over the other.

Check after deploying:

```
curl -sI https://darkproof.app/downloads/darkproof-0.4.apk   # 200, application/vnd.android.package-archive
curl -s  https://darkproof.app/downloads/darkproof-0.4.apk | shasum -a 256
```

### Windows installer download

Under the Android panel the install section has a second one, "Download for Windows", for the desktop app. The file
is `downloads/darkproof-0.4.13-windows.msi`, served at `https://darkproof.app/downloads/darkproof-0.4.13-windows.msi`.

- **Never commit the file.** It is 152 MB, over GitHub's limit of 100 MB for one file (`.gitignore` has `*.msi`).
  Like the APK it must be in `site/downloads/` whenever an archive is made, or the link dies at the next deploy.
  With the three downloads the archive is about 350 MB.
- It is the installer GitHub built (`DarkProof-0.4.13.msi`, workflow "Desktop app for Windows", run 44833529072,
  commit `1f2cd37` on the branch `windows-desktop`), renamed and nothing else. To check a copy:
  `gh run download <run> --repo DEV-ENT/DarkProof --name DarkProof-windows-installer`, then compare with `cmp`.
- Earlier builds, no longer on the site: two under the name `darkproof-0.4.1-windows.msi` on 2026-11-20 (117386189
  bytes from `ca6dbf8`, without the offline AI, then 138356217 bytes from `0de8948` with it). That address answers
  "not found" since 0.4.13 went up. When a file is ever replaced under the same address, clear the website cache and
  check the live file's size and SHA256; the server sends no caching header for `.msi`.
- `.htaccess` serves `.msi` files as `application/x-msi`, so a browser saves the file.
- The installer is not signed, so Windows shows its blue warning; the panel says how to get past it.
- The wording keeps to what is known. The panel says "early access", lists what the desktop app does not do yet
  (Bluetooth, internet relays and Tor, the map), and says the build has only run on test machines, that no AI
  model has been loaded on Windows and that nothing has run on a real graphics card. Change those sentences only
  when someone has done it on a real PC. No words about how fast or how clever the AI is.
- The offline AI of the desktop app offers five models the person downloads in the app (1.3 GB to 16.8 GB). They are
  uncensored versions of Qwen 3.5 and Gemma 4 made by other people, and the panel says so, with the warning that they
  can be wrong, also about medicine doses. Don't describe the phones' AI that way: the phone apps use Google's Gemma.
- Since 0.4.13 every build has its own number, so a new installer goes over the old one. GitHub's Windows machine
  installed 0.4.1 and then 0.4.13 over it: one DarkProof was left and it started. That is why the panel no longer
  says to remove the earlier build first.
- For a new version: new file under a new name, then the button's address, the version, the size and the SHA256
  line in `index.html`. Remove the old file once nothing links to it.

Version 0.4.13 (Windows 10 and 11): 151994521 bytes, SHA-256
`2e305e28dd8ce1c07c605aec2ee833b800f6a72e399375d34f01674e69cb4835`.

Check after deploying:

```
curl -sI https://darkproof.app/downloads/darkproof-0.4.13-windows.msi   # 200, application/x-msi, 151994521 bytes
curl -s  https://darkproof.app/downloads/darkproof-0.4.13-windows.msi | shasum -a 256
```

### Mac download

The third panel, "Download for Mac", is the same desktop app as a disk image. The file is
`downloads/darkproof-1.4.13-mac.dmg`, served at `https://darkproof.app/downloads/darkproof-1.4.13-mac.dmg`.

- **Never commit the file** (142 MB; `.gitignore` has `*.dmg`). It must be in `site/downloads/` whenever an archive
  is made.
- It is the disk image GitHub built (`DarkProof-1.4.13.dmg`, artifact `DarkProof-mac`, the same run 44833529072 and
  commit `1f2cd37`), renamed and nothing else. Check a copy the same way as the Windows installer.
- The Mac shows the version as 1.4.13, because a Mac app's version may not start with 0. It is the same build as
  Windows 0.4.13, so the panel says "Build 13" and names 1.4.13 once.
- Apple Silicon only (every program inside is arm64). The panel says so, and that it has only run on macOS 26.
- It is signed "ad hoc": no Apple developer certificate, and Apple has not checked it. `spctl --assess` says
  rejected, so a Mac refuses the first open. The panel gives the way past it (System Settings, Privacy & Security,
  "Open Anyway") and says plainly that nobody has yet done those steps with this build.
- The app asks to find devices on the local network (it has the text for that question); the panel says to allow it.
- `.htaccess` serves `.dmg` files as `application/x-apple-diskimage`.
- Known and said in the panel: the engine and the AI engine started from the opened disk image on two test Macs.
  Not known: the window of the packaged app, opening it by double click, linking a phone. Change those sentences
  only when someone has done it.

Build 13 (version 1.4.13, bundle id `app.darkproof.desktop`): 141930695 bytes, SHA-256
`8ec6912ecae231c648ea2cc2f5244146d9f0d9d4f329d11169928e63493133a0`.

Check after deploying:

```
curl -sI https://darkproof.app/downloads/darkproof-1.4.13-mac.dmg   # 200, application/x-apple-diskimage, 141930695 bytes
curl -s  https://darkproof.app/downloads/darkproof-1.4.13-mac.dmg | shasum -a 256
```

## support@darkproof.app

The address is on every page and in the privacy policy. Set it up before the site goes live:

1. At the domain's DNS provider, turn on mail forwarding to the team's inbox (Cloudflare Email Routing and ImprovMX
   are free; a mail host such as Fastmail or Proton also works). This adds the MX and SPF (TXT) records.
2. To answer from the same address, set it up as a sending identity in the mail host and add its DKIM record.
3. Send a test message from an outside account and check that it arrives and that replies are not marked as spam.
