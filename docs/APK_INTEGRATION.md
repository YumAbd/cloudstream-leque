# CloudStream Android integration: clean, reproducible fork

The official CloudStream source is licensed GPL-3.0. Keep upstream copyright notices, publish your modified source as required when distributing GPL-covered binaries, and respect third-party extension authors' independent licenses.

## IMPORTANT: Existing cloned APK

`CloudStreamMODV5.apk` is a third-party cloned binary (`com.bayustecu.stecustream`) and ships with App Cloner-specific assets, including an `assets/.appDataFile`. This starter does NOT modify or re-sign that APK. Do not claim a matching signing certificate unless you possess the real signing key. A new fork signed with your own key cannot be installed as a normal update over a differently signed application of the same package name.

## Fresh-source build

1. Install Android Studio / Android SDK (compileSdk 37 for the official source snapshot checked 2026-10-28), a suitable Gradle/JDK toolchain (the official project targets Java 17 toolchain). The project's own Gradle wrapper determines the Gradle version.
2. Obtain the official Git repository: `git clone https://github.com/recloudstream/cloudstream.git`.
3. First create and publish your GitHub repository `cloudstream-leque` using these starter files. Its `main/repo.json` URL must be publicly accessible over HTTPS.
4. From this starter's folder, patch the CloudStream checkout:

   `python scripts/patch_cloudstream_source.py --source "C:/Projects/cloudstream" --github-user YumAbd`

5. Inspect changes: specifically `RepositoryManager.kt`, `donottranslate-strings.xml`, `app/build.gradle.kts` and `AndroidManifest.xml`. Do not silently reapply if newer upstream source has changed those locations.
6. Build debug variant via Android Studio or `./gradlew :app:assembleDebug` (on Windows `gradlew.bat :app:assembleDebug`). Confirm the app launches on a clean Android device/emulator.
7. Test the launcher displays `CloudStream Leque V5`; Settings -> profile shows `Mod v5 by Leque`; Settings -> Extensions shows your own repository; no legacy Bayu/Phisher repo is present by default. Extensions are NOT silently pre-installed.
8. Build a release APK with a persistent, private Android signing key that you control; keep keystore and password securely backed up. App updates require the same app ID and signing key across releases.

## Important: official APK update feed is NOT your Leque APK feed

The official CloudStream application has app-updater paths pointing to `recloudstream/cloudstream` GitHub releases (for example `GithubReleases.kt` and `InAppUpdater.kt`). Before publishing the Leque APK, disable that upstream APK-update path or replace it with an update feed that serves ONLY APKs signed using your own Leque signing key. This is **different from extension auto-updates**: keep extension auto-update enabled, but do not attempt to install official CloudStream APK releases over a Leque package. Test both the "updates available" UI and manual update actions before distribution.

## How auto-updates work

The official `PluginManager` checks installed plugin `internalName`, matches by the **same repository listing URL**, and upgrades when `version` is greater than the installed version. Changing a plugin URL without increasing `version` will not trigger the normal update. Changing the root repository URL after distribution also does not migrate users automatically.

- Keep the fixed URL `https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json` stable.
- In your own `sumber.json`, retain `internalName` and increment `version` when publishing a changed `.cs3`.
- `fileHash` must match the actual bytes if given; changing it without updating the binary will cause a verification failure.
- Auto-update usually runs as the app starts with network access and the setting enabled; it is not an immediate remote push and not guaranteed while the app is closed.
- New extensions are not automatically installed just because they are listed in the repo.
- A broken upstream streaming site requires provider code changes or a working replacement; editing JSON alone cannot fix it.

## Popup

The repository JSON itself does not contain the startup donation popup. The legacy APK includes App Cloner app-data and several plugins from Phisher98; without original source/data decoding, the exact startup popup injection path cannot be certified. A clean official-source build that does not include a donation overlay is a safer starting point than patching an untrusted, third-party cloned APK. Test any Phisher-owned plugin separately before distribution; an extension may itself display messages.

## Distributing responsibly

Use only authorized or properly licensed extension sources and streaming material. Keep authors' attribution, comply with CloudStream GPL-3.0, avoid blindly mirroring unknown `.cs3` files. Supply your modified app source and license notices as required by applicable licenses.

## IPTV playlist file

The original Bayu repository also contains a small `cloudstream.m3u8`, but CloudStream does not automatically load it merely because it resides beside `repo.json`. This template ships an empty M3U header only. Populate it only with sources you have permission to share, and configure the appropriate IPTV extension to read its stable raw GitHub URL.
