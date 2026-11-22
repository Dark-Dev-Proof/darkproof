#!/bin/bash
# Makes the Mac's disk image one a Mac opens without the notice "Apple could not verify DarkProof is free of malware".
#
# It takes the disk image the build made (packageDmg), signs every program and library of the app with a Developer ID
# certificate (also the ones packed inside the Java archives), has Apple check the app (notarization) and attaches
# Apple's answer to it (stapling), puts the app back into the image, and does the same for the image. The app carries
# Apple's answer itself, so a Mac that is offline when DarkProof is first opened needs to ask nobody.
#
#   desktop/mac-sign.sh <built.dmg> <signed.dmg> <identity> [notary profile]
#
# identity        The certificate, by name or by its 40-character fingerprint, as this lists it:
#                     security find-identity -v -p codesigning
#                 It must be a "Developer ID Application" one: no other kind is accepted by other people's Macs.
#                 "-" makes a test signature: the same steps, on this Mac only. Its result is for trying whether the
#                 signed app still works; other Macs treat it like an unsigned one.
#                 Any other certificate of this Mac (an "Apple Development" one) makes a trial: signed exactly as the
#                 real thing will be, so it shows whether the app runs that way, but accepted on this Mac only.
# notary profile  The name under which the person stored their Apple ID's app password in the keychain, themselves:
#                     xcrun notarytool store-credentials <name>
#                 Without it nothing is sent to Apple, and the notice stays.
#
# No password passes through this script. The signing key and the app password stay in the keychain.
set -euo pipefail

[ $# -ge 3 ] || { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 64; }
IN=$1; OUT=$2; ID=$3; PROFILE=${4:-}
HERE=$(cd "$(dirname "$0")" && pwd)
[ -f "$IN" ] || { echo "no such disk image: $IN" >&2; exit 66; }
TEST=false; [ "$ID" = "-" ] && TEST=true
REAL=false; [[ $(security find-identity -v -p codesigning | grep -F -- "$ID" | head -1) == *"Developer ID Application"* ]] && REAL=true
if ! $REAL && [ -n "$PROFILE" ]; then echo "only an app signed with a Developer ID Application certificate is sent to Apple: leave the notary profile out" >&2; exit 64; fi

T=$(mktemp -d "${TMPDIR:-/tmp}/darkproof-sign.XXXXXX")
MNT="$T/mnt"; mkdir "$MNT"
cleanup() { hdiutil detach "$MNT" -quiet 2>/dev/null || true; rm -rf "${T:?}"; }
trap cleanup EXIT
say() { echo; echo "== $* =="; }

# What the app may do under the signature. A test signature has no team behind it, so the system can't tell that the
# app's libraries belong to it, and the app has to be allowed foreign ones; the real signature is not.
ENT="$T/app.plist"; cp "$HERE/mac-entitlements.plist" "$ENT"
CHILD=()
if $TEST; then
    /usr/libexec/PlistBuddy -c "Add :com.apple.security.cs.disable-library-validation bool true" "$ENT" >/dev/null
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>com.apple.security.cs.disable-library-validation</key><true/></dict></plist>' > "$T/child.plist"
    CHILD=(--entitlements "$T/child.plist")
    STAMP=(--timestamp=none)
elif $REAL; then
    STAMP=(--timestamp) # Apple's clock signs the time, so the signature stays good after the certificate runs out
else
    STAMP=(--timestamp=none)
fi
sign() { codesign --force --options runtime "${STAMP[@]}" --prefix app.darkproof. --sign "$ID" "$@"; }
# (Asked with [[ ]] and not with grep -q: grep stops reading at its first find, and with pipefail that reads as a failure.)
macho() { [[ $(file -b "$1") == *Mach-O* ]]; }

say "the app out of $(basename "$IN")"
hdiutil attach "$IN" -readonly -nobrowse -noautoopen -mountpoint "$MNT" -quiet
APPNAME=$(cd "$MNT" && ls -d ./*.app | head -1); APPNAME=${APPNAME#./}
[ -n "$APPNAME" ] || { echo "no app in the disk image" >&2; exit 65; }
ditto "$MNT/$APPNAME" "$T/$APPNAME"
hdiutil detach "$MNT" -quiet
APP="$T/$APPNAME"
xattr -cr "$APP"
echo "$APPNAME $(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"

say "libraries inside the Java archives"
# Apple's check opens the archives too. A library in one is taken out, signed and put back under the same name.
find "$APP" -type f -name '*.jar' | while read -r jar; do
    { unzip -Z1 "$jar" 2>/dev/null | grep -E '\.(dylib|jnilib)$' || true; } | while read -r entry; do
        d="$T/jar"; rm -rf "${d:?}"; mkdir "$d"
        unzip -q "$jar" "$entry" -d "$d"
        macho "$d/$entry" || continue
        sign "$d/$entry" 2>/dev/null
        (cd "$d" && zip -q -X "$jar" "$entry")
        echo "$(basename "$jar"): $entry"
    done
done

say "programs and libraries of the app"
MAIN=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")
n=0
while read -r f; do
    macho "$f" || continue
    [ "$f" = "$APP/Contents/MacOS/$MAIN" ] && continue # signed last, with the app
    if [[ $(file -b "$f") == *executable* ]]; then sign ${CHILD[@]+"${CHILD[@]}"} "$f" 2>/dev/null; else sign "$f" 2>/dev/null; fi
    n=$((n + 1))
done < <(find "$APP/Contents" -type f)
echo "$n signed"
[ -d "$APP/Contents/runtime" ] && sign "$APP/Contents/runtime" 2>/dev/null && echo "Java runtime signed"
sign --entitlements "$ENT" "$APP"
codesign --verify --deep --strict "$APP" && echo "the app's signature holds"
codesign -dv "$APP" 2>&1 | grep -E "^(Identifier|TeamIdentifier|Timestamp|Signature|Runtime Version|CodeDirectory)" || true
# Nothing may be left with another signature or none: one such file and Apple refuses the whole app.
left=0
while read -r f; do
    macho "$f" || continue
    info=$(codesign -dvv "$f" 2>&1 || true)
    if $TEST; then [[ $info == *"(adhoc,runtime)"* ]] || { echo "not signed as asked: ${f#"$APP"/}"; left=1; }
    else if $REAL; then [[ $info == *"Authority=Developer ID Application"* ]] || { echo "not signed with the Developer ID: ${f#"$APP"/}"; left=1; }; fi
         [[ $info == *"(runtime)"* && $info != *"TeamIdentifier=not set"* ]] || { echo "not signed as asked: ${f#"$APP"/}"; left=1; }
    fi
done < <(find "$APP/Contents" -type f)
[ $left = 0 ] || exit 1

notarize() { # $1: what is sent, $2: what Apple's answer is attached to
    local answer id status
    answer=$(xcrun notarytool submit "$1" --keychain-profile "$PROFILE" --wait --output-format json) || { echo "$answer"; return 1; }
    id=$(echo "$answer" | plutil -extract id raw -o - - 2>/dev/null || true)
    status=$(echo "$answer" | plutil -extract status raw -o - - 2>/dev/null || true)
    echo "Apple's answer: ${status:-none} ($id)"
    if [ "$status" != "Accepted" ]; then xcrun notarytool log "$id" --keychain-profile "$PROFILE" || true; return 1; fi
    xcrun stapler staple -q "$2" && echo "answer attached to $(basename "$2")"
}

if [ -n "$PROFILE" ]; then
    say "Apple checks the app"
    ditto -c -k --keepParent "$APP" "$T/app.zip"
    notarize "$T/app.zip" "$APP"
fi

say "the app back into the disk image"
# The image the build made keeps its window, its picture and where the icons lie: only the app in it is replaced.
hdiutil convert "$IN" -format UDRW -o "$T/rw.dmg" -quiet
mb=$(( $(stat -f %z "$T/rw.dmg") / 1048576 + 64 ))
hdiutil resize -size "${mb}m" "$T/rw.dmg" >/dev/null 2>&1 || true # room for the signatures; an image that can't grow may still have it
hdiutil attach "$T/rw.dmg" -nobrowse -noautoopen -mountpoint "$MNT" -quiet
rm -rf "${MNT:?}/${APPNAME:?}"
ditto "$APP" "$MNT/$APPNAME"
codesign --verify --deep --strict "$MNT/$APPNAME"
hdiutil detach "$MNT" -quiet
rm -f "$OUT"
hdiutil convert "$T/rw.dmg" -format UDZO -imagekey zlib-level=9 -o "$OUT" -quiet
if $REAL; then codesign --force --timestamp --sign "$ID" "$OUT"; fi

if [ -n "$PROFILE" ]; then
    say "Apple checks the disk image"
    notarize "$OUT" "$OUT"
    say "what a Mac will say"
    xcrun stapler validate "$OUT"
    spctl --assess --type open --context context:primary-signature -vv "$OUT" 2>&1
    hdiutil attach "$OUT" -readonly -nobrowse -noautoopen -mountpoint "$MNT" -quiet
    spctl --assess --type execute -vv "$MNT/$APPNAME" 2>&1
    xcrun stapler validate "$MNT/$APPNAME"
    hdiutil detach "$MNT" -quiet
elif ! $REAL; then
    echo; echo "TEST SIGNATURE: for trying on this Mac only. Nothing was sent to Apple."
else
    echo; echo "Signed, but NOT checked by Apple: other Macs still show the notice. Give a notary profile to finish."
fi
echo; echo "$OUT  $(stat -f %z "$OUT") bytes  sha256 $(shasum -a 256 "$OUT" | cut -d' ' -f1)"
