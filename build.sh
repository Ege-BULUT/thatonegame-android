#!/bin/sh
# Builds ThatOneGame.apk from the web site in ../thatonegame/public (admin pages left out).
# Needs the Android SDK (build-tools 36, platform android-36) and a JDK, e.g. the one bundled with Android Studio.
# Signing key: KEYSTORE (default ~/.android/thatonegame-release.jks) with its password in KEYSTORE_PASS_FILE.
# DEBUG=1 makes a test build that may be pointed at a local test server (README). Never publish one.
set -eu
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
SITE="${SITE:-../thatonegame/public}"
KEYSTORE="${KEYSTORE:-$HOME/.android/thatonegame-release.jks}"
KEYSTORE_PASS_FILE="${KEYSTORE_PASS_FILE:-$HOME/.android/thatonegame-release.pass}"
VERSION="${VERSION:-1.0.0}"
CODE="${CODE:-1}"

rm -rf build assets
mkdir -p build/classes build/dex assets
# The app packs exactly the files in the site's app manifest (tools/offline_list.py writes it): the deployed site
# without the admin pages. Each one must still match its sha256, so the app's update check starts from the truth.
python3 - "$SITE" assets/site <<'EOF'
import hashlib, json, os, shutil, sys
src, dst = sys.argv[1:]
name = 'assets/app-manifest.json'
for p, f in json.load(open(os.path.join(src, name)))['files'].items():
    os.makedirs(os.path.dirname(os.path.join(dst, p)), exist_ok=True)
    shutil.copyfile(os.path.join(src, p), os.path.join(dst, p))
    if hashlib.sha256(open(os.path.join(dst, p), 'rb').read()).hexdigest() != f['sha256']:
        sys.exit(p + ' changed after ' + name + ' was written: run python3 tools/offline_list.py in the site first')
shutil.copyfile(os.path.join(src, name), os.path.join(dst, name))
EOF

MANIFEST=AndroidManifest.xml DEBUG_MODE=
if [ "${DEBUG:-}" = 1 ]; then # debuggable, and plain http allowed for the local test server
  MANIFEST=build/AndroidManifest.xml DEBUG_MODE=--debug-mode
  sed 's#<application#<application android:usesCleartextTraffic="true"#' AndroidManifest.xml > "$MANIFEST"
fi
"$BT/aapt2" compile --dir res -o build/res.zip
# shellcheck disable=SC2086
"$BT/aapt2" link -I "$JAR" --manifest "$MANIFEST" $DEBUG_MODE -R build/res.zip -A assets \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$CODE" --version-name "$VERSION" \
  -0 glb -o build/unsigned.apk
"$JAVA_HOME/bin/javac" --release 11 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 24 --release --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "file:$KEYSTORE_PASS_FILE" \
  --out build/ThatOneGame.apk build/aligned.apk
"$BT/apksigner" verify build/ThatOneGame.apk
ls -lh build/ThatOneGame.apk
