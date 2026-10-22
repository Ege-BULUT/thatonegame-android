# ThatOneGame for Android

The Android app of [ThatOneGame](https://thatonegame.vercel.app): childhood flash classics and ad-free .io games,
rebuilt as original HTML5 games. The app packs the whole mobile site, so every game plays offline with touch controls.

## Install

1. On your phone, download `ThatOneGame.apk` from the [latest release](https://github.com/Ege-BULUT/thatonegame-android/releases/latest).
2. Open it. Android asks you to allow installs from your browser or file manager once, because the app does not come from the Play Store.
3. Open ThatOneGame. No internet is needed after installing: the games play offline, and when the phone is online the
   app fetches new and changed games on start (see Updates).

Android 7.0 or newer.

## Build

`./build.sh` packs `../thatonegame/public` (without the admin pages) into `build/ThatOneGame.apk`. It packs exactly the files
listed in the site's `public/assets/app-manifest.json`, so run `python3 tools/offline_list.py` in the site first (it is
already the step before every deploy); the build stops if a file changed after that.
It needs the Android SDK (build-tools 36.0.0, platform android-36) and a JDK 17 or newer, such as the one bundled with Android Studio,
plus a signing key (`KEYSTORE`, `KEYSTORE_PASS_FILE`).

The app is a single activity with a WebView. It serves the packed site from `https://appassets.androidplatform.net/`
so absolute paths, ES modules and saved games work as they do on the web. Its only permission is INTERNET, used for the
update check below.

Game saves stay in the WebView's storage. The site's Saves page exports them to Downloads and imports a save file
through Android's file picker. The app also copies every save to `saves-backup.json` in its own storage (on pause and
every 3 minutes) and writes them back on start if the WebView's storage was emptied.

## Updates

A new site deploy reaches the app without a new APK:

1. `tools/offline_list.py` in the site writes `public/assets/app-manifest.json` with every file the app packs, each with
   its sha256 and size, and a version. It goes out with the deploy.
2. On start the app shows a short "Checking for updates" screen and fetches
   `https://thatonegame.vercel.app/assets/app-manifest.json` (3 second timeout). Offline, or on any error, it opens the
   games straight away with what it has.
3. If the version differs, it downloads only the files whose sha256 differs from its own copy, with a progress bar.
   Each file is checked against its sha256 and kept in the app's own storage under `files/update/`, named by its sha256.
4. Only when every file arrived and matched does the app replace `files/update/manifest.json`, its record of the
   installed site. Then it deletes the files the new manifest no longer needs and opens the games, with
   "Updated: N games" when games changed. A corrupt or interrupted download changes nothing: the app plays the version
   it had and tries again on the next start, keeping the files that already arrived.
5. Each request in the WebView is served from the downloaded copy when there is one, else from the APK. A file the
   installed manifest no longer lists is not served, even if the APK still holds it.

"Skip" appears after 3 seconds and opens the games at once; nothing is installed then. The check runs once per app start.

A new APK is only needed when the app's own code changes (`src/`, `AndroidManifest.xml`, `res/`, `build.sh`), for
example when the site starts using something new from the app's `TOGApp` bridge, or if the manifest's `format` changes.
A new APK packs the current site and starts from that, leaving earlier downloads behind.

### Testing updates

`test/io/github/egebulut/thatonegame/UpdaterTest.java` checks the update logic against a local server (how to run it
is at the top of the file). For a test on a phone or emulator, `DEBUG=1 ./build.sh` (with a throwaway `KEYSTORE`)
makes a debuggable build that allows plain http and takes the manifest URL from the launch, for example
`adb shell am start -n io.github.egebulut.thatonegame/.MainActivity --es manifest http://10.0.2.2:8000/assets/app-manifest.json`.
Release builds ignore that URL. Never publish a `DEBUG=1` build.
