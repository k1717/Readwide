# Readwide 1.0.19 Build / Release Notes

This file records source-build notes for **Readwide 1.0.19**.

## Version metadata

- Android metadata is `versionCode 10019`, `versionName "1.0.19"`.
- The Android package/application ID has been `com.readwide.manager` since 1.0.4. 1.0.19 keeps the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.18 through 1.0.6. Updating from 1.0.4/1.0.5 (previous key) still requires uninstalling first, installing 1.0.19, then migrating data with the in-app JSON backup export/import, because of the 1.0.6 signing-key change. Earlier builds using `com.textview.reader` likewise install as a separate app.
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

`settings.gradle` intentionally avoids an extra Java toolchain resolver plugin. Local builds should use a compatible Gradle JDK configured in Android Studio or through `JAVA_HOME`; Java source/target compatibility remains 17.

## Archive backend notes

- ZIP/CBZ uses Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted methods where bundled codecs can read them.
- 7z/CB7 and most TAR-family paths use Apache Commons Compress; Android Zstandard uses the Zstd filter in the bundled libarchive backend.
- ALZ/EGG are limited first-party extraction paths with documented method boundaries.
- RAR/CBR is read/extract only: libarchive remains primary for common compressed cases; first-party Java supplies scoped stored, PPMd/mixed-LZ and RAR5-container streaming paths. The 3.8.9 backend and Java follow-ups do not imply complete RAR support; see [release notes](docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_19.md).
- CAB and LHA/LZH use the same bundled backend for read-only listing, image browsing, and extraction; creation, password, and broad multi-volume support are not claimed.
- No manual `libarchive.so`, Junrar dependency, or optional local decoder jar is required. The checked-in libarchive-android 3.8.9 source module is compiled by Gradle and requires Android NDK 29.0.14206865 plus CMake 3.22.1.

## APK size / ABI notes

Release APK packaging filters Android native ABIs to `armeabi-v7a` and `arm64-v8a`. This removes x86/x86_64 native payloads from bundled native dependencies while preserving ARM device support.

`zstd-jni` is a JVM-test-only dependency and is not packaged into the APK. Android `.tar.zst` and raw `.zst` decoding uses the Zstandard support compiled into the ARM libarchive payload under `lib/armeabi-v7a/` and `lib/arm64-v8a/`.

## FOSS / F-Droid build boundary

- The default source package contains no optional decoder jar under `app/libs`.
- Release signing is conditional, so source-build review can run `assembleRelease` without a private developer keystore.
- The 1.0.19 F-Droid build entry must declare `ndk: 29.0.14206865` and `buildjni: no`; Gradle/CMake performs the JNI build.
- The vendored libarchive path `third_party/libarchive-android/library/src/main/jni/external/libarchive/build/cmake/` contains upstream CMake source modules despite its `build` directory name. `.gitignore` and source-ZIP filtering must retain it; omitting it makes CMake fail before native compilation.
- `fdroid/metadata/com.readwide.manager.yml` is a historical local mirror. For an F-Droid merge request, start from current fdroiddata upstream and add only the 1.0.19 release, pinned to the final 40-character release commit hash.
- If any local jar or native binary is added later, re-audit that custom build before describing it as FOSS.
