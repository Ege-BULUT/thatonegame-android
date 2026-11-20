# ThatOneGame for Android

The Android app of [ThatOneGame](https://thatonegame.vercel.app): childhood flash classics and ad-free .io games,
rebuilt as original HTML5 games. Every game plays with touch controls, and offline once it has been opened.

## Install

1. On your phone, download `ThatOneGame.apk` from the [latest release](https://github.com/Ege-BULUT/thatonegame-android/releases/latest).
2. Open it. Android asks you to allow installs from your browser or file manager once, because the app does not come from the Play Store.
3. Open ThatOneGame. Each game downloads the first time you open it and then plays offline. When the phone is online the
   app fetches the new game list on start, and a game's update when you open it or tap its update button (see Updates).

Android 7.0 or newer.

## Build

`./build.sh` packs the shell of `../thatonegame/public` into `build/ThatOneGame.apk`. It packs exactly the shell files
listed in the site's `public/assets/app-manifest-v2.json`, so run `python3 tools/offline_list.py` in the site first (it is
already the step before every deploy); the build stops if a file changed after that.
It needs the Android SDK (build-tools 36.0.0, platform android-36) and a JDK 17 or newer, such as the one bundled with Android Studio,
plus a signing key (`KEYSTORE`, `KEYSTORE_PASS_FILE`).

The app is a single activity with a WebView. It serves the site (the packed shell and the downloaded games)
from `https://appassets.androidplatform.net/` so absolute paths, ES modules and saved games work as they do on the web.
Its permissions are INTERNET, for the updates and game downloads below, and REQUEST_INSTALL_PACKAGES, for installing
its own new versions.

Game saves stay in the WebView's storage. The site's Saves page exports them to Downloads and imports a save file
through Android's file picker. The app also copies every save to `saves-backup.json` in its own storage (on pause and
every 3 minutes) and writes them back on start if the WebView's storage was emptied.

## Updates

The APK packs only the site's shell: its pages, the game list, every game's thumbnail and the shared libraries in
`/vendor/` (about 5 MB). Games are not in the APK. Each one downloads the first time it is opened, and after that it
changes only when the player opens it or taps its update button. A site deploy reaches the app without a new APK, and
a player is never affected by an update of a game they do not open.

1. `tools/offline_list.py` in the site writes `public/assets/app-manifest-v2.json`: the shell and one pack per live
   game (`games/<slug>/`, `io/<slug>/`, without the thumbnail), each file with its sha256 and size, each part with its
   own hash. It goes out with the deploy. (It also still writes `app-manifest.json`, format 1, unchanged, for the
   apps before 3.0.0, which update every file on start.)
2. On start the app shows a short "Checking for updates" screen and fetches
   `https://thatonegame.vercel.app/assets/app-manifest-v2.json` (3 second timeout). Offline, or on any error, it opens
   the games straight away with what it has.
3. If the version differs, it downloads only the shell files whose sha256 differs from its own copy, with a progress
   bar, and records the new manifest. New games appear in the list ("2 new games"). No game file is downloaded here.
4. In the game list, the app marks each card by the game's state (`web/tog.js`, added to the site's pages as the app
   serves them, so the site needs no change for it):
   - never downloaded: a small dark grey chip with a download arrow and the size, at the thumbnail's corner;
   - update available: a dark grey cover over the thumbnail with an update icon, "Update" and the size;
   - downloading: the cover with a spinning icon, the percentage and an orange progress bar;
   - up to date: no mark.
   A tap on the chip or the cover downloads the game in place without opening it; the mark goes away when it is done.
   A tap anywhere else on the card opens the game as before.
5. Opening a game that is not downloaded, or has an update, shows the app's download screen (`web/gate.html`) in place
   of the game, with the progress; when the game is installed the screen reloads into the game. Offline, a game with
   an installed copy opens that copy; a game never downloaded says it needs the internet the first time, with "Try
   again".
6. Every file is checked against its sha256 and kept in the app's own storage under `files/update/`, named by its
   sha256, so a file shared by two games or two versions is stored once. `files/update/state.json` records the newest
   manifest and the installed games. It changes only after every file of a step arrived and matched: a corrupt or
   interrupted download changes nothing, the game keeps its installed version, and the files that already arrived are
   kept for the next try. One download runs at a time; files nothing needs any more are deleted after each step.
7. A game's files are served only from its installed copy, never from the APK or an older download; a file the
   installed manifest no longer lists is not served.

"Skip" appears after 3 seconds and opens the games at once; the shell update is not installed then. The check runs
once per app start. Game saves are in the WebView's storage, which updates never touch (see the saves backup above).

A new APK starts from its own shell and keeps the games already downloaded. The first 3.0.0 start after an older APK
turns the old updater's downloads into reusable files and deletes the rest; games played before download again once
(their saves stay). A new APK is only needed when the app's own code changes (`src/`, `web/`, `AndroidManifest.xml`,
`res/`, `build.sh`), or if the manifest's `format` changes.

### New versions of the app

The app also updates itself when a new APK is released, through Android's own install prompt (an app outside the
Play Store cannot install silently):

1. Each release publishes `assets/app-latest.json` on the site: `{versionCode, versionName, url, sha256, size, notes}`,
   with the url of the release's versioned asset (`releases/download/vX.Y.Z/ThatOneGame.apk`). `./latest.sh "notes" >
   app-latest.json` writes it from `build/ThatOneGame.apk`. Deploy it only after the GitHub release holds that exact
   APK, so the url always exists. It is read from our own site, not the GitHub API (60 requests an hour per address).
2. After the start update, in the background, the app fetches it at most once every 6 hours; offline nothing happens
   (`AppUpdate.java`, state in `files/app-update.json`).
3. If its versionCode is higher than the app's, the game list shows a banner: "Update available: 3.0.2 (3.4 MB)",
   Update and Later. Later hides it for that version for 24 hours.
4. Update downloads the APK into the app's cache with the same progress ring as the games, and keeps it only when its
   size and sha256 match and it is this app at that version. A failed or wrong download is deleted with a short
   message; an unverified APK is never installed.
5. The app hands it to Android's PackageInstaller (a session, Android 5 and newer), which shows the install prompt.
   Without the "install unknown apps" permission (Android 8 and newer, `REQUEST_INSTALL_PACKAGES`), the app says so in
   one line and opens that setting for ThatOneGame; coming back resumes the install. Android installs it only if it
   is signed with the same key, and keeps the app's data, saves included.

### Testing updates

`test/io/github/egebulut/thatonegame/UpdaterTest.java` checks the update logic (and `AppUpdate`: the version compare,
the 6-hour check, Later and the sha256 check) against a local server, with manifests written by the
site's real `tools/offline_list.py`, and checks the released v2.0.0 updater against the same site: run `test/run.sh`
(`TOOLS=` points at another copy of `offline_list.py`). For a test on a phone or emulator, `DEBUG=1 ./build.sh` (with a throwaway `KEYSTORE`)
makes a debuggable build that allows plain http and takes the manifest URL from the launch, for example
`adb shell am start -n io.github.egebulut.thatonegame/.MainActivity --es manifest http://10.0.2.2:8000/assets/app-manifest-v2.json`
(games then download from the same server).
Release builds ignore that URL. Never publish a `DEBUG=1` build.
