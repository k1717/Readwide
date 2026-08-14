# Third-party notices

Readwide first-party source code is licensed under the Apache License 2.0. Keep this file with source releases and with binary release materials such as APK/AAB release assets.

This notice summarizes direct dependencies and important source/provenance boundaries for the default Readwide 1.0.17 build. It does not replace a fully resolved transitive dependency report.

## Default build boundary

The default Readwide 1.0.17 package is the FOSS-oriented public line:

- first-party source: Apache-2.0;
- no Junrar or RARLAB UnRAR-license code bundled;
- no optional local decoder jar required under `app/libs`;
- HWP/HWPX support through Apache-2.0 dogfoot Java libraries;
- PDF text extraction through an Apache-2.0 pure-Java library (PdfBox-Android), with PDF page rendering still on the platform PdfRenderer;
- no Hancom proprietary SDK, LibreOffice bundle, or server conversion service;
- no ads, analytics, telemetry SDK, account system, or network update checker in the default app.

See `docs/FOSS_STATUS.md`, `docs/LICENSE_REPORT_READWIDE_1_0_17.md`, and `docs/SBOM_READWIDE_1_0_17.spdx.json`.

## Runtime dependencies

### AndroidX libraries

- `androidx.appcompat:appcompat:1.7.1`
- `androidx.recyclerview:recyclerview:1.4.0`
- `androidx.constraintlayout:constraintlayout:2.2.1`
- `androidx.activity:activity:1.10.1`
- `androidx.drawerlayout:drawerlayout:1.2.0`
- `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`

License: Apache License 2.0.

### Material Components for Android

- Artifact: `com.google.android.material:material:1.14.0`
- License: Apache License 2.0.

### JUniversalChardet

- Artifact: `com.github.albfernandez:juniversalchardet:2.5.0`
- Purpose: text encoding detection.
- License position used by this project: Mozilla Public License 1.1 option from the upstream tri-license.

### Apache Commons Compress

- Artifact: `org.apache.commons:commons-compress:1.28.0`
- Purpose: TAR/7z/stream archive support and selected ZIP method fallback.
- License: Apache License 2.0.
- Vendored modified source: `app/src/main/java/com/readwide/manager/archive/AlzBzip2InputStream.java` is a modified copy of this artifact's `BZip2CompressorInputStream` (which credits Keiron Liddle, Aftex Software), adapted for the trimmed bzip2 bitstream variant used inside ALZip `.alz` archives. The Apache-2.0 header and a change summary are kept in the file, satisfying the license's modification-notice requirement. The bitstream facts follow the zlib-licensed `unalz` 0.65 reference decoder by kippler@gmail.com (`http://www.kipple.pe.kr`); no `unalz` code is included.

### libarchive-android

- Source module: `project(':libarchiveAndroid')`, vendored under `third_party/libarchive-android`
- Project: `https://github.com/zhanghai/libarchive-android`
- Purpose: Android libarchive Java/JNI backend used for RAR and other backend-dependent archive paths.
- Pinned source: wrapper commit `3a592be028c7be41847f667570bd343c0010bd9d`, advanced by Readwide to official libarchive commit `27cbc7827172698143e440801fc0ba39ccb4f1f5` (libarchive 3.8.9). Exact codec commits are recorded in `third_party/libarchive-android/UPSTREAM.md`.
- Build boundary: Java/JNI and native codec sources are compiled by Gradle, Android NDK 29.0.14206865, and CMake 3.22.1. No prebuilt `.aar`, `.so`, or other native library is checked into the source module.
- License position: Android wrapper under Apache License 2.0; libarchive and its native codec inputs under the permissive upstream notices retained within the vendored source tree.
- 7z method note: 7z BCJ2 and PPMd entries (which Commons Compress cannot handle for AES-encrypted archives) are decoded by first-party Java in `SevenZBcj2ArchiveReader`/`SevenZBcj2Decoder`/`SevenZAesDecoder`/`SevenZPpmd7Decoder` (see `docs/SEVENZ_BCJ2_READER_READWIDE_1_0_11.md` and `docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md`). `SevenZPpmd7Decoder` is a Java port of the public-domain Ppmd7 reference - Dmitry Shkarin's PPMd var.H (2001, public domain) as maintained in Igor Pavlov's Ppmd7 codec (public domain) - obtained from pyppmd's source distribution; public-domain code carries no license obligations and is Apache-2.0 compatible. Vendored libarchive also decodes unencrypted 7z PPMd as a fallback, via the public-domain `Ppmd7.c` carried under libarchive's BSD-style licensing. No 7-Zip or UnRAR-license source is included; test fixtures for these methods are self-made with p7zip from first-party content.
- Binary-release requirement: `app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt` is packaged into the APK and reproduces the applicable wrapper, libarchive, UC Regents, BLAKE2, bzip2, liblzma, LZ4, Zstandard, zlib, Mbed TLS, and Apache-2.0 notices from the pinned source inputs. Keep this asset in binary builds and keep this project-level notice with source/release materials.

### ZIPX JPEG/WavPack native codec module

- Source module: `project(':zipxCodecsAndroid')`, vendored under `third_party/zipx-codecs-android`.
- Purpose: WinZip ZIPX method 96 (JPEG) and method 97 (WavPack) decoding after Readwide's bounded WinZip AES authentication/decryption layer.
- XADMaster WinZip JPEG: tag `v1.10.8`, commit `881e0ec25e249c9ad5bbc1b6782ae8dcdf48a6ed`, LGPL-2.1-or-later. Only the C WinZip JPEG decoder, its support files, and the public-domain LZMA SDK decoder subset are imported. Readwide's allocation/output hardening and modification summary are recorded in `third_party/zipx-codecs-android/UPSTREAM.md`.
- WavPack: official tag `5.9.0`, commit `5803634a030e2a11dba602ba057b89cc34486c67`, BSD-3-Clause. It is compiled from the pinned source; no upstream binary is bundled.
- Linking boundary: XAD-derived code and the Readwide JNI bridge are contained in the separate shared library `libreadwide-zipx-codecs.so`. The Apache-2.0 app calls its small Java API dynamically. Complete corresponding source and CMake/Gradle build scripts are included so recipients can modify and rebuild/relink the LGPL library, and Readwide imposes no reverse-engineering restriction for debugging such modifications.
- Binary-release requirement: the APK packages the complete LGPL-2.1 license at `assets/open_source_licenses/xadmaster_winzip_jpeg_lgpl_2_1.txt` and the WavPack BSD-3-Clause notice at `assets/open_source_licenses/wavpack_bsd_3_clause.txt`. Keep both assets in binary builds.

### XZ for Java

- Artifact: `org.tukaani:xz:1.12`
- Purpose: XZ/LZMA/LZMA2 codec support.
- License: 0BSD.

### zstd-jni

- Artifact: `com.github.luben:zstd-jni:1.5.7-9`
- Purpose: JVM unit-test fixtures for Commons Compress Zstandard streams. It is a `testImplementation` dependency and is not packaged in the Android APK; Android runtime Zstandard uses libarchive's bundled filter.
- License position: BSD-family licensing path for the JNI binding and bundled native Zstandard library.
- Distribution note: no zstd-jni binary notice is required for the APK because its classes/native resources are not shipped there. Keep this source/test notice with the repository.

### Zip4j

- Artifact: `net.lingala.zip4j:zip4j:2.11.6`
- Purpose: primary ZIP/CBZ listing, extraction, password, AES, and split ZIP handling.
- License: Apache License 2.0.

### hwplib

- Artifact: `kr.dogfoot:hwplib:1.1.10`
- Project: `https://github.com/neolord0/hwplib`
- Purpose: scoped HWP 5.x read/text extraction backend for `.hwp` files.
- License: Apache License 2.0.
- Boundary: Readwide uses it for read-only text extraction. Readwide does not claim HWP editing, writing, Hancom-compatible rendering, or password/encrypted HWP support.

### hwpxlib

- Artifact: `kr.dogfoot:hwpxlib:1.0.9`
- Project: `https://github.com/neolord0/hwpxlib`
- Purpose: scoped HWPX read/text extraction backend for `.hwpx` files.
- License: Apache License 2.0.
- Boundary: Readwide uses it for read-only text extraction. Readwide does not claim HWPX editing/writing, layout-compatible rendering, or cloud/server conversion.

### PdfBox-Android

- Artifact: `com.tom-roush:pdfbox-android:2.0.27.0`
- Project: `https://github.com/TomRoush/PdfBox-Android`
- Purpose: PDF text extraction with glyph positions, used for in-document find in the PDF reader. Rendering still uses the platform `PdfRenderer`; PdfBox is not used to render pages.
- License: Apache License 2.0.
- Boundary: Readwide uses it for read-only text extraction and search only. The optional JP2/JPEG2000 image decoder (`com.gemalto.jp2`) is not bundled, so JPX images are ignored; this does not affect text search.

## Test dependencies

- `junit:junit:4.13.2` — JVM unit tests — Eclipse Public License 1.0.
- `androidx.test:runner:1.7.0` — Android instrumentation tests — Apache License 2.0.
- `androidx.test.ext:junit:1.3.0` — AndroidX JUnit extension — Apache License 2.0.

## Build tooling

- Gradle wrapper / Gradle build tool: Apache License 2.0.
- Android Gradle Plugin `com.android.application` / `com.android.library` 9.2.0: Android SDK / Google Maven distribution terms for build tooling; not bundled as app runtime code.
- Android NDK 29.0.14206865 and CMake 3.22.1: compile the vendored native source module; build tooling only.

## First-party / bundled algorithm notices

### RAR3/RAR4 PPMd decoder

Readwide includes first-party extraction-only Java code for a scoped RAR3/RAR4 PPMd variant H decoding path. PPMd variant H is a public-domain algorithm by Dmitry Shkarin; the implementation follows public algorithm behavior and is used only for decoding covered unencrypted single-volume cases with CRC verification. This first-party implementation contains no copied RARLAB UnRAR or libarchive code; the separately vendored libarchive backend is described above.

### RAR5 compressed decoder

Readwide includes first-party extraction-only Java code for a scoped RAR5 v5.0 compressed decoding path. It is limited to covered cases, including fixture-tested visible-header AES multi-volume chains, and uses CRC/password-check safeguards. It does not implement RAR compression, RAR creation, password recovery, or broad encrypted/split/SFX compatibility. This first-party implementation contains no copied RARLAB UnRAR or libarchive code; the separately vendored libarchive backend is described above.

### AZO decoder

Readwide includes `AzoDecoder.java`, a modified Java extraction-only port of the `kippler/xunazo` AZO decoder for covered EGG method-3 payloads.

- Upstream: `https://github.com/kippler/xunazo`
- License: zlib license.
- Required upstream notice is retained in the source file.
- The port is used only for extraction and is not used to create EGG/AZO archives.

### JAXB DatatypeConverter compatibility shim

- Package path: `javax.xml.bind.DatatypeConverter`
- Origin: project-local compatibility shim written for Readwide.
- License: covered by Readwide's first-party Apache-2.0 license.
- Purpose: provides the single `parseBase64Binary()` method referenced by hwplib's legacy Base64 helper on Android/Java 17 builds.

## Test fixture provenance

RAR fixture archives embedded in unit tests or referenced by optional external fixture tests originate from Stephan Sokolow's "RAR Test Files" collection (`https://github.com/ssokolow/rar-test-files`), whose author dedicated the archives and their contents to the public domain under CC0 1.0 Universal. SFX stub variants from that collection are not embedded in this repository.

## Assets

The Readwide launcher artwork source and checked-in launcher/adaptive icon assets are project-owned artwork supplied by the project maintainer for this release line. See `docs/ASSET_PROVENANCE.md`.

Local vector UI icons are ordinary app UI assets. Several file-type and action icons — including the per-type file icons (`ic_file_*`) — are vector drawable glyph paths adapted from Google's Material Symbols / Material Icons:

- Source: Material Symbols / Material Icons (`https://github.com/google/material-design-icons`)
- Copyright: Google LLC
- License: Apache License 2.0
- Use: selected glyph paths were copied/adapted into local vector drawables for file-type and UI action icons. Any Material Symbols / Material Icons paths added later are covered by this same Apache-2.0 attribution.

## Release distribution note

The Gradle packaging block may exclude duplicate dependency `META-INF/LICENSE*` and `META-INF/NOTICE*` files from packaged Android resources to avoid merge conflicts. Public source and binary releases should still provide:

- `LICENSE`
- `NOTICE`
- `THIRD_PARTY_NOTICES.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_17.md`
- `docs/SBOM_READWIDE_1_0_17.spdx.json`
