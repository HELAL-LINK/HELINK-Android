#!/bin/bash
# Builds HELINK.apk without Gradle: aapt2 → javac → dx → zip → zipalign → apksigner.
#
# Needs: JDK 8+, Python 3, Go 1.24+ (the music remover), aapt2, dalvik-exchange (dx),
# zipalign, apksigner (Debian/Ubuntu packages: aapt, dalvik-exchange, zipalign, apksigner),
# and two android.jar files:
#   ANDROID_JAR      the platform the code is compiled against (API 35)
#   ANDROID_RES_JAR  the platform resources are linked against (API 33: the aapt2 shipped by
#                    Debian cannot read the newer resource tables)
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/home/claude/android-deps/Sable_android-platforms/android-35/android.jar}
ANDROID_RES_JAR=${ANDROID_RES_JAR:-/home/claude/android-deps/Sable_android-platforms/android-33/android.jar}
KEYSTORE=${KEYSTORE:-keystore/alihelal-release.jks}
KEY_ALIAS=${KEY_ALIAS:-alihelal}
KEY_PASS=${KEY_PASS:-$(cat keystore/password.txt)}
VERSION_CODE=${VERSION_CODE:-10}
VERSION_NAME=${VERSION_NAME:-1.7.1}
OUT=build/apk
APK=${APK:-build/HELINK.apk}

rm -rf "$OUT"
mkdir -p "$OUT"/{gen,classes,stage}

echo "== page and yt-dlp"
python3 tools/gen_html.py ui third_party/fonts assets/index.html
python3 -I - assets/ytdlp.zip > assets/ytdlp.version <<'PY'
import re, sys, zipfile
s = zipfile.ZipFile(sys.argv[1]).read('yt_dlp/version.py').decode()
print(re.search(r"__version__ = '([^']+)'", s).group(1), end='')
PY
echo "   yt-dlp $(cat assets/ytdlp.version)"

cp docs/THIRD-PARTY-NOTICES.txt assets/THIRD-PARTY-NOTICES.txt
rm -f assets/sep_install.py

# Python's and ffmpeg's libraries (40-50 MB per phone type) are downloaded by the app on
# first launch from youtubedl-android's repository, pinned to this commit and checked
# against these SHA-256 sums. FULL=1 ships them inside the APK instead (about 95 MB).
YDL_REPO=yausername/youtubedl-android
YDL_COMMIT=d725d5c9a18c3a99a13ee0308bf78275dc310760
python3 -I - "$YDL_REPO" "$YDL_COMMIT" > assets/runtime.json <<'PY'
import hashlib, json, sys, zipfile
repo, commit = sys.argv[1:3]
out = {"repo": repo, "commit": commit, "abis": {}}
for abi in ("arm64-v8a", "armeabi-v7a"):
    for part, name, module in (("python", "libpython.zip.so", "library"), ("ffmpeg", "libffmpeg.zip.so", "ffmpeg")):
        path = "third_party/jniLibs/%s/%s" % (abi, name)
        data = open(path, "rb").read()
        out["abis"].setdefault(abi, {})[part] = {
            "file": name,
            "path": "%s/src/main/jniLibs/%s/%s" % (module, abi, name),
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "unpacked": sum(i.file_size for i in zipfile.ZipFile(path).infolist()),
        }
print(json.dumps(out, indent=1))
PY

echo "== resources"
aapt2 compile --dir res -o "$OUT/res.zip"
aapt2 link -I "$ANDROID_RES_JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 30 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A assets -0 zip \
  --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "== java"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -nowarn \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src -name '*.java') $(find "$OUT/gen" -name '*.java') 2>&1 | grep -v '^Picked\|^Note' || true
test -f "$OUT/classes/com/alihelal/app/MainActivity.class"

echo "== dex"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/stage/classes.dex" "$OUT/classes" 2>&1 | grep -v '^Picked' || true
test -s "$OUT/stage/classes.dex"

echo "== native programs"
for abi in arm64-v8a armeabi-v7a; do
  mkdir -p "$OUT/stage/lib/$abi"
  cp third_party/jniLibs/$abi/*.so "$OUT/stage/lib/$abi/"
  if [ "${FULL:-0}" != "1" ]; then rm -f "$OUT/stage/lib/$abi/"*.zip.so; fi
done
# The music remover (native/sep): a program of its own, static, NEON on both kinds of ARM.
for pair in arm64-v8a:arm64 armeabi-v7a:arm; do
  abi=${pair%%:*} arch=${pair##*:}
  (cd native/sep && CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOARM=7 GOFLAGS=-trimpath \
    go build -ldflags="-s -w -buildid=" -o "../../$OUT/stage/lib/$abi/libhelinksep.so" .)
done

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# Native programs are compressed (Android unpacks them when installing: extractNativeLibs);
# the big *.zip.so files are zip files already and stay stored.
(cd "$OUT/stage" && zip -q -X -D "../unsigned.apk" classes.dex && zip -q -X -D -r -9 -n .zip.so "../unsigned.apk" lib)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== sign"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk" 2>&1 | grep -v '^Picked' || true
apksigner verify "$APK" 2>&1 | grep -v '^Picked' || true
ls -la "$APK"
