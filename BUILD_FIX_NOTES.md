# Build / Release Fix Notes

## Readwide 1.0.0

- Android metadata is `versionCode 10000`, `versionName "1.0.0"`.
- The Android package/application ID remains unchanged for update compatibility.
- This line continues from the TextView Reader 2.2.6 privacy/license hardening base and keeps the later Readwide archive preview/image-sequence fixes.

## 2.2.6 stale Junrar source cleanup

If this ZIP is extracted over an older working folder, removed files from the old tree can remain on disk. In particular, a stale `app/src/main/java/com/textview/reader/archive/RarJunrarFallback.java` will fail compilation because the Junrar dependency was intentionally removed.

The app Gradle module now registers `deleteRemovedLegacySourceFiles` and wires it into `preBuild` and Java compile tasks, so this stale source is deleted automatically before compilation. Manual cleanup scripts are also available:

```powershell
.\scripts\clean_removed_sources.ps1
```

```bash
./scripts/clean_removed_sources.sh
```


## 2.2.6

 RAR/libarchive note

- RAR3/RAR4 default libarchive extraction was tightened against the uploaded `rar-test-files-master(1).zip` fixtures: normal compressed `.rar`/`.cbr` extraction is now treated as the default target path; multi-file solid CBR and some SFX wrappers remain limited.
- 2.2.6 removes Junrar and adds `me.zhanghai.android.libarchive:library:1.1.6` as the normal APK RAR3/RAR4 normal non-encrypted compressed fallback backend.
- Default builds bundle libarchive-android through Maven/Gradle; no manual NDK/CMake flag or local `libarchive.so` is required.
- Java passes the resolved RAR volume path list to the bundled backend, so later-volume selections still start from the first available volume.
- SFX, encrypted, solid, and unusual split cases remain fixture-dependent and should not be advertised as guaranteed.


This source uses the modern Gradle layout for the Android project root:

- root `plugins { ... }` block;
- `pluginManagement` and `dependencyResolutionManagement` in `settings.gradle`;
- Android Gradle Plugin `9.2.0`;
- Gradle wrapper `9.4.1`;
- `compileSdk 35` and `targetSdk 35`;
- Java compatibility set to 17.

This avoids the older Gradle error:

> Cannot mutate the dependencies of configuration ':app:debugCompileClasspath' after the configuration was resolved.

Open the repository root folder in Android Studio, not the `app/` folder, then click **Sync Now**. If Android Studio asks to install SDK Platform 35 or Build Tools, click **Install**.

## Current Gradle notes

This package removes the deprecated AGP compatibility toggles that previously produced AGP-10 removal warnings. The project uses `android.dependency.useConstraints=false` instead of the deprecated `android.dependency.excludeLibraryComponentsFromConstraints=true` flag. If a future build still prints warnings, treat the first red compile/test failure as the priority and clean non-blocking Gradle warnings separately.


## Java toolchain notes

`settings.gradle` intentionally avoids an extra Java toolchain resolver plugin. Local builds should run with Android Studio's bundled JDK 17 or another compatible JDK configured through `JAVA_HOME`.

## 2.2.5/2.2.6 archive/drawer/progress note

- RAR3/RAR4 volume discovery was tightened for libarchive fallback preparation: zero-padded `part001.rar` style volumes and three-digit old-style `.r000` suffixes are now resolved, and later-volume selection no longer fails just because a following placeholder/incomplete volume cannot be parsed after the first volume metadata was recovered.

2.2.5 keeps the 2.2.4 archive/drawer baseline and adds a multi-select delete progress re-entry fix.

The 2.2.5 source includes archive-support matrix work plus drawer gesture repairs. Build failures in this package should be debugged as normal Java/XML/Gradle errors; drawer behavior changes are implemented in `MainDrawerGestureController` and drawer bottom action routing is in `MainDrawerController`.

## ZIP Commons fallback note

The ZIP Commons fallback uses the existing `org.apache.commons:commons-compress:1.28.0` dependency. No additional Maven module was added for this fallback; update `THIRD_PARTY_NOTICES.md` when changing archive-engine usage descriptions.

## 2.2.5 Bundled Zstandard codec for Commons ZIP fallback

Apache Commons Compress 1.28.0 can decode ZIP method 93 through `com.github.luben:zstd-jni`. TextView Reader now bundles `zstd-jni`, so release minification no longer needs to suppress a missing Zstandard class. ZIP Commons fallback can now attempt non-encrypted ZSTD entries in addition to Deflate64, BZip2, and XZ. Runtime extraction still catches `LinkageError` so ABI-specific native-load failures are reported as unsupported instead of crashing.


## libarchive-android RAR3/RAR4 backend

2.2.6 uses `me.zhanghai.android.libarchive:library:1.1.6` in the normal APK for RAR3/RAR4 normal non-encrypted compressed fallback. No `-PtextviewEnableLibarchive=true`, manual `libarchive.so`, or NDK/CMake step is required for the default build.

If RAR fallback fails, treat it as a format/fixture limitation first, not as a missing native-library setup issue. Verify with common RAR3/RAR4 normal compressed samples before advertising wider solid/encrypted/SFX coverage.

## 2.2.6 APK size note: ARM-only release ABI packaging

Release APK packaging now filters Android native ABIs to `armeabi-v7a` and `arm64-v8a`. This removes x86/x86_64 native payloads from bundled native dependencies such as libarchive-android while preserving ARM device support.
## 2.2.6 APK size cleanup

- Release APK keeps Android ARM ABIs only (`armeabi-v7a`, `arm64-v8a`).
- zstd-jni desktop resource binaries (`win/**`, `darwin/**`, `linux/**`, `freebsd/**`, `aix/**`, `sunos/**`) are excluded from APK packaging.
- This does not remove Android ARM native libraries under `lib/armeabi-v7a/` or `lib/arm64-v8a/`.


## 2.2.6 FOSS status / license cleanup

The default source package is now documented as the FOSS-friendly 2.2.6 line:

- Junrar/UnRAR-license fallback code is removed from the default build.
- `libarchive-android` is the bundled default RAR3/RAR4 normal non-encrypted compressed fallback backend.
- `app/libs` contains no optional decoder jar in the public source package.
- `docs/FOSS_STATUS.md` records the default-build boundary and explains that optional local jars create custom builds that need separate license review.
