# ThatOneGame for Android

The Android app of [ThatOneGame](https://thatonegame.vercel.app): childhood flash classics and ad-free .io games,
rebuilt as original HTML5 games. The app packs the whole mobile site, so every game plays offline with touch controls.

## Install

1. On your phone, download `ThatOneGame.apk` from the [latest release](https://github.com/Ege-BULUT/thatonegame-android/releases/latest).
2. Open it. Android asks you to allow installs from your browser or file manager once, because the app does not come from the Play Store.
3. Open ThatOneGame. No internet is needed after installing.

Android 7.0 or newer.

## Build

`./build.sh` packs `../thatonegame/public` (without the admin pages) into `build/ThatOneGame.apk`.
It needs the Android SDK (build-tools 36.0.0, platform android-36) and a JDK 17 or newer, such as the one bundled with Android Studio,
plus a signing key (`KEYSTORE`, `KEYSTORE_PASS_FILE`).

The app is a single activity with a WebView. It serves the packed site from `https://appassets.androidplatform.net/`
so absolute paths, ES modules and saved games work as they do on the web. It has no permissions and makes no network requests.
