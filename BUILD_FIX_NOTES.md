# Readwide 1.0.15 Build / Release Notes

This file records source-build notes for **Readwide 1.0.15**.

## Version metadata

- Android metadata is `versionCode 10015`, `versionName "1.0.15"`.
- The Android package/application ID has been `com.readwide.manager` since 1.0.4. 1.0.15 keeps the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.14, 1.0.13, 1.0.12, 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. Updating from 1.0.4/1.0.5 (previous key) still requires uninstalling first, installing 1.0.15, then migrating data with the in-app JSON backup export/import, because of the 1.0.6 signing-key change. Earlier builds using `com.textview.reader` likewise install as a separate app.
- The default source package is Junrar-free, UnRAR-license-fallback-free, analytics-free, ads-free, and does not request the `INTERNET` permission.

## Stale removed-source cleanup

If this ZIP is extracted over an older working folder, removed files from the old tree can remain on disk. In particular, stale archive fallback source files from older development builds can fail compilation because their dependencies are no longer part of the public source package.

The app Gradle module registers `deleteRemovedLegacySourceFiles` and wires it into `preBuild` and Java compile tasks. Manual cleanup scripts are also available:

```powershell
.\scripts\clean_removed_sources.ps1
```

```bash
./scripts/clean_removed_sources.sh
```

## Gradle / Android Studio notes

This source uses the modern Gradle layout for the Android project root:

- root `plugins { ... }` block;
- `pluginManagement` and `dependencyResolutionManagement` in `settings.gradle`;
- Android Gradle Plugin `9.2.0`;
- Gradle wrapper `9.4.1`;
- `compileSdk 35` and `targetSdk 35`;
- Java compatibility set to 17.

Open the repository root folder in Android Studio, not the `app/` folder, then click **Sync Now**. If Android Studio asks to install SDK Platform 35 or Build Tools, click **Install**.

## Current Gradle notes

This package removes the deprecated AGP compatibility toggles that previously produced AGP-10 removal warnings. The project uses `android.dependency.useConstraints=false` instead of the deprecated `android.dependency.excludeLibraryComponentsFromConstraints=true` flag. If a future build still prints warnings, treat the first red compile/test failure as the priority and clean non-blocking Gradle warnings separately.

`settings.gradle` intentionally avoids an extra Java toolchain resolver plugin. Local builds should run with Android Studio's bundled JDK 17 or another compatible JDK configured through `JAVA_HOME`.

## Archive backend notes

- ZIP/CBZ uses Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted methods where bundled codecs can read them.
- 7z/CB7 and most TAR-family paths use Apache Commons Compress; Android Zstandard uses the Zstd filter in the bundled libarchive backend.
- ALZ/EGG are limited first-party extraction paths with documented method boundaries.
- RAR/CBR is read/extract only: first-party Java handles metadata/stored-entry boundaries and `libarchive-android` is attempted for common compressed cases. Do not advertise complete RAR support.
- No manual `libarchive.so`, NDK/CMake flag, Junrar dependency, or optional local decoder jar is required for the default build.

## APK size / ABI notes

Release APK packaging filters Android native ABIs to `armeabi-v7a` and `arm64-v8a`. This removes x86/x86_64 native payloads from bundled native dependencies while preserving ARM device support.

`zstd-jni` is a JVM-test-only dependency and is not packaged into the APK. Android `.tar.zst` and raw `.zst` decoding uses the Zstandard support compiled into the ARM libarchive payload under `lib/armeabi-v7a/` and `lib/arm64-v8a/`.

## FOSS / F-Droid build boundary

- The default source package contains no optional decoder jar under `app/libs`.
- Release signing is conditional, so source-build review can run `assembleRelease` without a private developer keystore.
- `fdroid/metadata/com.readwide.manager.yml` is a local draft. For an F-Droid merge request, start from current fdroiddata upstream and add only the release actually being submitted (`1.0.15` if `1.0.14` is skipped), pinned to the final 40-character release commit hash.
- If any local jar or native binary is added later, re-audit that custom build before describing it as FOSS.
