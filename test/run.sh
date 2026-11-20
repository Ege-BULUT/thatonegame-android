#!/bin/sh
# Runs UpdaterTest: the updater against a local server, with the manifests written by the site's real
# tools/offline_list.py (TOOLS, default ../thatonegame/tools/offline_list.py), and the updater of the released
# v2.0.0 app (commit 76d3948) against the same site. Needs python3 and Android Studio's JDK and org.json.
set -eu
cd "$(dirname "$0")/.."
J="/Applications/Android Studio.app/Contents"
JAVA="${JAVA_HOME:-$J/jbr/Contents/Home}/bin"
JSON="${JSON:-$J/plugins/grazie/lib/org.json-json.jar}"
TOOLS="${TOOLS:-../thatonegame/tools/offline_list.py}"
O=$(mktemp -d)
mkdir -p "$O/new" "$O/old" "$O/oldsrc"
git show 76d3948:src/io/github/egebulut/thatonegame/Updater.java > "$O/oldsrc/Updater.java"
"$JAVA/javac" -cp "$JSON" -d "$O/old" "$O/oldsrc/Updater.java"
"$JAVA/javac" -cp "$JSON" -d "$O/new" src/io/github/egebulut/thatonegame/Updater.java test/io/github/egebulut/thatonegame/UpdaterTest.java
"$JAVA/java" -Dtools="$TOOLS" -Dold="$O/old" -Djson="$JSON" -cp "$O/new:$JSON" io.github.egebulut.thatonegame.UpdaterTest
