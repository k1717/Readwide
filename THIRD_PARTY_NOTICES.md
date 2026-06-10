# Third-party notices

Readwide first-party source code is licensed under the Apache License 2.0 in `LICENSE`. The root `LICENSE` file includes the applied first-party copyright notice, and the root `NOTICE` file is part of the Apache-2.0 source distribution.
This file records third-party components that are used by the Android build or by the app at runtime. Keep this file with source releases and with binary release materials such as APK/AAB release assets.


## Default FOSS build boundary

The default Readwide 1.0.1 source package is the current FOSS-friendly public line: first-party source is Apache License 2.0, Junrar/UnRAR-license fallback code is not bundled, and the normal dependency graph uses FOSS-compatible licenses recorded below. The default source package contains no optional decoder jar in `app/libs`; in a clean package the folder may be absent. Adding any local jar creates a custom build that must be audited separately before being described as FOSS.

See `docs/FOSS_STATUS.md` for the project-level FOSS assessment and release checklist. A direct-dependency license report is included at `docs/LICENSE_REPORT_READWIDE_1_0_1.md`, and a source-declared direct-dependency SPDX draft is included at `docs/SBOM_READWIDE_1_0_1.spdx.json`. These do not replace a fully resolved Gradle/transitive SBOM for stricter repository submission.

## Runtime / app dependencies

### Android platform APIs

TXT text-to-speech in 2.2.2 uses Android platform TTS, foreground-service notification, and media-button APIs. The app does not bundle a third-party TTS engine or voice model; user-installed Android TTS voices remain managed by the device TTS engine and system settings.

### RAR / CBR support boundary

The RAR/CBR reader parses RAR5 and RAR4/RAR3-style archive metadata in this source tree, decodes RAR4 Unicode filename records, extracts entries stored without compression, assembles stored split payloads, and includes a limited first-party RAR5 encrypted stored-data decrypt attempt. The default build no longer bundles Junrar or other UnRAR-license/provenance-risk fallback code. Common compressed RAR paths route by default to the bundled Apache-2.0 libarchive-android dependency. RAR cases not handled by libarchive or the stored-data Java path fail cleanly as unsupported.



### zstd-jni
- Artifact: `com.github.luben:zstd-jni:1.5.7-9`
- License position used by this project: BSD 2-Clause for the JNI binding artifact, with the bundled/native Zstandard library distributed under the permissive BSD licensing path rather than a GPL path.
- Purpose: Provides the native Zstandard codec used by Apache Commons Compress for non-encrypted ZIP method 93 fallback and Zstandard stream support.
- Binary-release notice requirement: keep this notice file available with APK/AAB release materials and, when producing a stricter repository submission, regenerate a resolved dependency notice bundle from the exact Gradle artifacts so the native Zstandard notice text is carried forward. The APK packaging excludes desktop native resource folders that Android does not use, but the Android ARM native payload remains part of the runtime build.

### libarchive-android

Artifact: `me.zhanghai.android.libarchive:library:1.1.6`

Project: https://github.com/zhanghai/libarchive-android

Use in this project: default RAR3/RAR4 compressed fallback backend through Android libarchive native libraries and Java bindings. It is used for RAR listing, password preflight, single-entry extraction, and whole-archive extraction when the first-party stored-RAR reader cannot handle a compressed entry.

License position used by this project: Apache License 2.0 for the Android library artifact, plus permissive BSD-style upstream notices for the bundled native libarchive code.

Binary-release notice requirement: keep this notice file available with APK/AAB release materials and, for stricter repository submission, regenerate/copy the resolved libarchive-android/libarchive native notices from the exact Gradle artifact used to build the APK. Do not describe an APK as having complete native notices if the binary release materials omit the libarchive native notice bundle.

### First-party ALZ / EGG support boundary

ALZ/EGG archive names are recognized by the app's archive type system. This package does not bundle ALZip, the UnEGG binary module, or any other third-party ALZ/EGG decoder; the readers are first-party code.

The ALZ path parses local headers and extracts Store, Deflate, and BZip2 entries (BZip2 via Apache Commons Compress), including ZipCrypto for the covered encrypted cases, with CRC verification.

The EGG container reader (`EggArchiveReader`) is first-party Java code. It implements publicly documented EGG container concepts and interoperability behavior for read/extraction use only. No ESTsoft source files, unEGG source files, or UnEGG binary module are copied or bundled in this repository. The decoder handles Store/Deflate/BZip2/AZO/LZMA, verifies each extracted block's CRC32, and sanitizes entry paths against directory traversal. Encrypted, split, and solid EGG archives are reported as unsupported. The decoder is not used to create EGG archives.

### ZIP / CBZ routing boundary

Production ZIP/CBZ routing uses Zip4j as the primary path for listing, extraction, encrypted ZIP handling, and standard split ZIP compatibility. When Zip4j rejects a non-encrypted entry because of an unsupported compression method, extraction can fall back to Apache Commons Compress for methods Commons can decode with bundled dependencies, such as Deflate64, BZip2, XZ through XZ for Java, and ZSTD through zstd-jni. Encrypted ZIP entries remain on Zip4j and do not use the Commons fallback, so AES plus a non-Zip4j method such as AES+XZ remains unsupported. ZSTD ZIP entries are enabled through the bundled zstd-jni dependency. The older first-party lightweight ZIP reader remains experimental/benchmark-oriented and is not the default production route.

### AZO decoder notice

This release includes `AzoDecoder.java`, an extraction-only modified Java port of the `kippler/xunazo` AZO decoder for EGG method-3 payloads. The upstream xunazo project is distributed under the zlib license. The Java port is marked as modified and is used only for extraction; it is not used to create EGG/AZO archives.

Required xunazo notice is retained in the source file and summarized here:

- Copyright (C) 2018 kippler@gmail.com
- Software is provided as-is, without express or implied warranty.
- The origin of the software must not be misrepresented.
- Altered source versions must be plainly marked as altered.
- The notice must not be removed or altered from source distributions.

### AndroidX libraries

Used artifacts:

- `androidx.appcompat:appcompat:1.7.1`
- `androidx.recyclerview:recyclerview:1.4.0`
- `androidx.constraintlayout:constraintlayout:2.2.1`
- `androidx.activity:activity:1.10.1`
- `androidx.drawerlayout:drawerlayout:1.2.0`

License: Apache License 2.0.

### Material Components for Android

Artifact: `com.google.android.material:material:1.14.0`

License: Apache License 2.0.

### Material-style vector icons

The project contains local vector drawable icons for standard UI actions such as menu, search, settings, bookmark, delete, sort, storage, and download. These are treated as Material-style icon assets. If any icon path is replaced with a copied upstream Material Symbols / Material Icons path, preserve the Apache License 2.0 attribution for that source.

Material Symbols / Material Icons are distributed by Google under the Apache License 2.0.

### Readwide launcher icon artwork

The Readwide launcher/adaptive/play-store icon assets are project-owned generated artwork supplied by the project maintainer for this release line. The source reference image is kept at `docs/readwide_launcher_icon_source.png`; generated Android density assets are derived from that project-owned source. No external stock image, third-party logo, or copied upstream icon path is required for the Readwide launcher artwork.

See `docs/ASSET_PROVENANCE.md` for the concise release-facing asset provenance note.


### Apache Commons Compress

Artifact: `org.apache.commons:commons-compress:1.28.0`

Use in this project: archive extraction support for 7z, standard 7z/CB7 split-volume chains through a concatenated seekable channel, TAR-family formats, numeric split archives that are reassembled before extraction where appropriate, single-file compressor streams such as GZ/BZ2/XZ/LZMA/Z, ALZ/EGG BZip2 payloads, and the non-encrypted ZIP fallback path for ZIP-internal compression methods Zip4j cannot decode but Commons Compress can read, including Deflate64, BZip2, XZ through XZ for Java, and ZSTD through zstd-jni.

License: Apache License 2.0.

### XZ for Java

Artifact: `org.tukaani:xz:1.12`

Use in this project: XZ/LZMA2 decompression support used by 7z, `.tar.xz` / `.txz` extraction, `.xz` single-stream extraction, and non-encrypted ZIP-internal XZ fallback through Apache Commons Compress.

License: 0BSD.


### Zip4j

Artifact: `net.lingala.zip4j:zip4j:2.11.6`

Use in this project: primary ZIP/CBZ listing and extraction path, encrypted ZIP handling, and standard split ZIP archive handling. Non-encrypted entries with Zip4j-unsupported methods can fall back to Apache Commons Compress; encrypted entries remain on Zip4j.

License: Apache License 2.0.

### JUniversalChardet

Artifact: `com.github.albfernandez:juniversalchardet:2.5.0`

Use in this project: Mozilla universalchardet-based helper in the TXT encoding decision pipeline.

License path used by this project: MPL-1.1.

Upstream license information lists JUniversalChardet as tri-licensed under MPL-1.1, GPL, and LGPL alternatives. This project should rely on the MPL-1.1 option rather than a GPL option so the app source can remain under Apache-2.0. Do not modify and vendor JUniversalChardet source files into this repository without preserving the applicable upstream notices and rechecking MPL file-level obligations.

## Test dependencies

### JUnit

Artifact: `junit:junit:4.13.2`

License: Eclipse Public License 1.0.

JUnit also depends on Hamcrest at test runtime in standard Gradle/Maven resolution. Keep its BSD-style notice if distributing a bundled test-runtime package. Normal source releases that only declare the Maven dependency do not vendor Hamcrest source or binaries.

### AndroidX Test

Used artifacts:

- `androidx.test:runner:1.7.0`
- `androidx.test.ext:junit:1.3.0`

Use in this project: Android instrumentation tests for TXT reader paging continuity.

License: Apache License 2.0.

## Build tooling

### Gradle wrapper / Gradle Build Tool

Files: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

License: Apache License 2.0.

### Android Gradle Plugin

Artifact: `com.android.application` plugin version `9.2.0`

License: Android Software Development Kit License / Google Maven distribution terms for the Android build tooling. The plugin is resolved by Gradle and is not vendored in this repository.

## Release-distribution note

The Gradle packaging block may exclude duplicate `META-INF/LICENSE*` and `META-INF/NOTICE*` files from packaged Android resources to avoid resource merge conflicts. That does not remove the need to provide third-party notices with public binary releases. Include this file, the root `LICENSE`, `NOTICE`, `docs/LICENSE_REPORT_READWIDE_1_0_1.md`, and `docs/SBOM_READWIDE_1_0_1.spdx.json` with public source and binary release materials.

## RAR5 key-derivation algorithm reference

The RAR5 password-based key derivation (PBKDF2-HMAC-SHA256 producing the AES key, HMAC hash key, and 8-byte password-check value) is a first-party Java implementation written from publicly documented RAR5 algorithm behavior and published reference-vector descriptions. No third-party code is copied; only the algorithm/parameter layout is followed. The implementation was validated against published reference vectors.
