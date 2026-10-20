#!/bin/sh
# Builds ThatOneGame.apk from the web site in ../thatonegame/public (admin pages left out).
# Needs the Android SDK (build-tools 36, platform android-36) and a JDK, e.g. the one bundled with Android Studio.
# Signing key: KEYSTORE (default ~/.android/thatonegame-release.jks) with its password in KEYSTORE_PASS_FILE.
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
rsync -a --exclude admin --exclude .DS_Store "$SITE/" assets/site/

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -R build/res.zip -A assets \
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
