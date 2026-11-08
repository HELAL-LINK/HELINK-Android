#!/bin/sh
# Publishes a release of HELINK for Android on its page, github.com/HELAL-LINK/HELINK-Android,
# which is also where every HELINK (from 1.6.0 on) finds its updates (core/Updates.java). Two
# commits on main:
#   1. the APK of build/HELINK.apk as HELINK-<VERSION>.apk at the root of the repository (the
#      previous version's leaves the branch; the commit that added it keeps it, so its addresses
#      work for good);
#   2. latest.json, signed with the update key (tools/updsign), naming that APK at commit 1, and
#      README.md, the download page, made again from tools/download-page.md with the notes of
#      docs/release-notes/<VERSION>.ar.txt and .en.txt (assets/: the icon, the pictures).
# Both go up in one push of main, so HELINK never reads a latest.json whose APK is not there.
# (A tag v<VERSION> is made too, and pushed when the connection allows it; nothing needs it.)
# Run again for the same version with the same APK, it reuses the commit that holds it.
# usage: ./build.sh, then
#        tools/publish_update.sh <clone of HELINK-Android> [key file] [--no-push]
# The key file (default ~/helink-release-keys/update-ed25519.key) is the private half of the key in
# Updates.java (the same key as HELINK for Windows): keep it safe, and never in a repository or a
# package. To make a later version, raise VERSION_CODE and VERSION_NAME in build.sh, write
# docs/release-notes/<VERSION>.ar.txt and .en.txt (one line each thing new), ./build.sh, then this.
# PUBLISH_TRAILER: lines put at the end of the commit messages, if any.
set -e
cd "$(dirname "$0")/.."
SRC=$(pwd)
REPO=${1:?usage: tools/publish_update.sh <clone of HELINK-Android> [key file] [--no-push]}
KEY=${2:-$HOME/helink-release-keys/update-ed25519.key}
PUSH=1
[ "$3" = "--no-push" ] && PUSH=0
VERSION=$(sed -n 's/^VERSION_NAME=\${VERSION_NAME:-\(.*\)}$/\1/p' build.sh)
CODE=$(sed -n 's/^VERSION_CODE=\${VERSION_CODE:-\(.*\)}$/\1/p' build.sh)
GH=HELAL-LINK/HELINK-Android
APK=HELINK-$VERSION.apk
# HELINK's signing certificate (keystore/alihelal-release.jks): phones only take an update signed
# with the key of the app they have.
CERT=1a85dbe8db5d27301947cd5412443163d00238f78c408f00eb4d9ac82f297dbb
for f in build/HELINK.apk "docs/release-notes/$VERSION.ar.txt" "docs/release-notes/$VERSION.en.txt"; do
  [ -f "$f" ] || { echo "missing $f (./build.sh first, and the notes)"; exit 1; }
done
[ -d "$REPO/.git" ] || { echo "$REPO is not a clone of $GH"; exit 1; }

# The app must know its own version (or the update would be offered again after it is installed),
# and the APK must be this version, signed with HELINK's key.
grep -q "String VERSION = \"$VERSION\";" src/com/alihelal/core/Engine.java ||
  { echo "Engine.VERSION is not $VERSION"; exit 1; }
BADGING=$(aapt2 dump badging build/HELINK.apk 2>/dev/null | head -1)
echo "$BADGING" | grep -q "name='com.alihelal.app' versionCode='$CODE' versionName='$VERSION'" ||
  { echo "build/HELINK.apk is not $VERSION ($CODE): $BADGING"; exit 1; }
apksigner verify --print-certs build/HELINK.apk 2>/dev/null | grep -q "SHA-256 digest: $CERT" ||
  { echo "build/HELINK.apk is not signed with HELINK's key"; exit 1; }
mkdir -p release
cp build/HELINK.apk "release/$APK"

# The update key must be the one HELINK knows, or every HELINK would turn this update down.
PUB=$(go run tools/updsign/main.go -key "$KEY" -pubkey)
grep -q "\"$PUB\"" src/com/alihelal/core/Updates.java || { echo "$KEY is not HELINK's update key (Updates.java has another)"; exit 1; }

commit() { # commit <title> <body>
  printf '%s\n\n%s\n%s' "$1" "$2" "${PUBLISH_TRAILER:+
$PUBLISH_TRAILER
}" | git commit -q -F -
}

cd "$REPO"
git pull -q --ff-only origin main || true
# 1. The APK. The same version must be the same file (a new build is a new version).
if [ -f "$APK" ]; then
  cmp -s "$APK" "$SRC/release/$APK" || { echo "$VERSION is published with another APK: raise VERSION_CODE and VERSION_NAME in build.sh"; exit 1; }
else
  for old in HELINK-*.apk; do
    if [ -f "$old" ]; then git rm -q "$old"; fi
  done
  cp "$SRC/release/$APK" .
  git add "$APK"
  commit "HELINK $VERSION: its APK" "HELINK for Android $VERSION (versionCode $CODE), signed with HELINK's key."
fi
FILES=$(git log -1 --format=%H -- "$APK")
git merge-base --is-ancestor "$FILES" HEAD

# 2. latest.json naming it at that commit, and the download page.
(cd "$SRC" && go run tools/updsign/main.go -key "$KEY" -version "$VERSION" -code "$CODE" -apk "release/$APK" \
  -notes "docs/release-notes/$VERSION" -repo "$GH" -ref "$FILES" -out release/latest.json)
cp "$SRC/release/latest.json" .
mkdir -p assets
for p in helink clip-ar clip-en wa-ar wa-en course-ar course-en; do
  if [ -f "$SRC/docs/screenshots/$p.png" ]; then cp "$SRC/docs/screenshots/$p.png" "assets/$p.png"; fi
done
python3 - "$SRC" "$VERSION" "$GH" "$APK" "$FILES" <<'EOF'
import os, re, sys, datetime
src, version, gh, apk, ref = sys.argv[1:6]
page = open(os.path.join(src, 'tools', 'download-page.md'), encoding='utf-8').read()
def notes(lang):
    lines = [l.strip() for l in open(os.path.join(src, 'docs', 'release-notes', version + '.' + lang + '.txt'), encoding='utf-8')]
    return '\n'.join('- ' + l for l in lines if l and not l.startswith('#'))
mb = max(1, round(os.path.getsize(apk) / (1 << 20)))
words = {
    '@VERSION@': version, '@APK@': apk, '@APK_URL@': 'https://github.com/%s/raw/%s/%s' % (gh, ref, apk),
    '@APK_MB_AR@': '%d ميجابايت' % mb, '@APK_MB_EN@': '%d MB' % mb,
    '@NOTES_AR@': notes('ar'), '@NOTES_EN@': notes('en'),
    '@DATE@': datetime.date.today().isoformat(),
}
for k, v in words.items():
    page = page.replace(k, v)
for name in ('helink', 'clip-ar', 'clip-en', 'wa-ar', 'wa-en', 'course-ar', 'course-en'):
    if not os.path.exists(os.path.join('assets', name + '.png')):
        page = re.sub(r'<img src="assets/%s\.png"[^>]*> ?' % re.escape(name), '', page)
open('README.md', 'w', encoding='utf-8').write(page)
EOF
git add -A
if git diff --cached --quiet; then
  echo "latest.json and the page are as they were"
else
  commit "HELINK $VERSION" "latest.json (signed) offers HELINK $VERSION to every HELINK from 1.6.0 on, its APK at
$FILES; the download page names it too."
fi
git tag -f "v$VERSION" >/dev/null
git log --stat -2 | cat
if [ $PUSH = 1 ]; then
  git push origin HEAD:main
  git push -f origin "refs/tags/v$VERSION" 2>/dev/null || echo "(the tag v$VERSION stays here: this connection does not take tags; nothing needs it)"
  echo "published: https://github.com/$GH (HELINK reads https://raw.githubusercontent.com/$GH/main/latest.json)"
else
  echo "not pushed (--no-push): git -C $REPO push origin HEAD:main"
fi
