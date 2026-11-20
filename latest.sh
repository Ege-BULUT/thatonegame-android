#!/bin/sh
# Writes the site's assets/app-latest.json for the APK in build/, which the app reads to offer its new version
# (AppUpdate.java). Run it after the GitHub release vVERSION holds this exact APK, and deploy the file to the site only
# then: the url must already exist. Usage: ./latest.sh "short notes" > app-latest.json
set -eu
cd "$(dirname "$0")"
APK=build/ThatOneGame.apk
BADGE=$("${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/36.0.0/aapt2" dump badging "$APK" | head -1)
python3 -I - "$APK" "$BADGE" "${1:-}" <<'EOF'
import hashlib, json, os, re, sys
apk, badge, notes = sys.argv[1:]
code, name = re.search(r"versionCode='(\d+)'", badge).group(1), re.search(r"versionName='([^']+)'", badge).group(1)
print(json.dumps({'versionCode': int(code), 'versionName': name, 'size': os.path.getsize(apk),
                  'sha256': hashlib.sha256(open(apk, 'rb').read()).hexdigest(), 'notes': notes,
                  'url': f'https://github.com/Ege-BULUT/thatonegame-android/releases/download/v{name}/ThatOneGame.apk'}, indent=1))
EOF
