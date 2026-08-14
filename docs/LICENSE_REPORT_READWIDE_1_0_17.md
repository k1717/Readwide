# Readwide 1.0.17 direct dependency license report

This report is generated from the dependencies declared in `app/build.gradle` plus source-bundled notices that are relevant to the default Readwide 1.0.17 build.

It is a **direct-dependency/source-declared report**, not a fully resolved transitive SBOM. For strict repository submission, regenerate a resolved Gradle dependency report/SBOM from a clean, network-enabled build environment.

## App identity

| Field | Value |
| --- | --- |
| App name | Readwide |
| Android applicationId | `com.readwide.manager` |
| Version name | `1.0.17` |
| Version code | `10017` |
| First-party license | Apache-2.0 |

## Runtime dependencies declared in `app/build.gradle`

| Dependency/source module | Purpose | Recorded license position |
| --- | --- | --- |
| `androidx.appcompat:appcompat:1.7.1` | AppCompat UI/runtime support | Apache-2.0 |
| `com.google.android.material:material:1.14.0` | Material Components UI support | Apache-2.0 |
| `androidx.recyclerview:recyclerview:1.4.0` | RecyclerView UI lists | Apache-2.0 |
| `androidx.constraintlayout:constraintlayout:2.2.1` | ConstraintLayout UI layouts | Apache-2.0 |
| `androidx.activity:activity:1.10.1` | AndroidX Activity APIs | Apache-2.0 |
| `androidx.drawerlayout:drawerlayout:1.2.0` | DrawerLayout UI | Apache-2.0 |
| `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` | Pull-to-refresh for the file list | Apache-2.0 |
| `com.github.albfernandez:juniversalchardet:2.5.0` | Text encoding detection | MPL-1.1 option from upstream tri-license |
| `org.apache.commons:commons-compress:1.28.0` | TAR/7z/stream archive support and ZIP method fallback | Apache-2.0 |
| `project(':libarchiveAndroid')` (`third_party/libarchive-android`) | Source-built Android libarchive 3.8.9 backend for RAR/backend-dependent archive, CAB/LHA/LZH, and ZIPX PPMd/Zstandard paths | Apache-2.0 (Android wrapper); libarchive and zlib/bzip2/xz/lz4/Zstandard/Mbed TLS inputs retain their permissive FOSS notices |
| `project(':zipxCodecsAndroid')` (`third_party/zipx-codecs-android`) | Separate source-built shared library for WinZip-AES ZIPX JPEG (method 96) and WavPack (method 97) | XADMaster v1.10.8 WinZip JPEG and Readwide JNI bridge: LGPL-2.1-or-later; WavPack 5.9.0: BSD-3-Clause; XAD LZMA SDK subset: public domain |
| `org.tukaani:xz:1.12` | XZ/LZMA/LZMA2 codec support | 0BSD |
| `net.lingala.zip4j:zip4j:2.11.6` | ZIP/CBZ listing/extraction/password/split support | Apache-2.0 |
| `kr.dogfoot:hwplib:1.1.10` | HWP 5.x text extraction backend | Apache-2.0 |
| `kr.dogfoot:hwpxlib:1.0.9` | HWPX text extraction backend | Apache-2.0 |
| `com.tom-roush:pdfbox-android:2.0.27.0` | PDF text extraction with glyph positions for in-document find, and per-page plain text for PDF read-aloud (rendering still uses the platform PdfRenderer) | Apache-2.0 |

## Test dependencies declared in `app/build.gradle`

| Maven coordinate | Purpose | Recorded license position |
| --- | --- | --- |
| `com.github.luben:zstd-jni:1.5.7-9` | JVM archive-fixture Zstandard codec (`testImplementation`; not packaged in the APK) | BSD-2-Clause (wrapper); test artifact bundles Zstandard (BSD-3-Clause OR GPLv2 dual; used under BSD-3-Clause) |
| `junit:junit:4.13.2` | JVM unit tests | EPL-1.0 |
| `androidx.test:runner:1.7.0` | Android instrumentation test runner | Apache-2.0 |
| `androidx.test.ext:junit:1.3.0` | AndroidX JUnit extension | Apache-2.0 |

## Build tooling

| Component | Purpose | License / terms position |
| --- | --- | --- |
| Gradle wrapper / Gradle | Build system wrapper and build runtime | Apache-2.0 |
| Android Gradle Plugin `com.android.application` / `com.android.library` 9.2.0 | Android build plugins resolved from Google Maven | Android SDK / Google Maven distribution terms; not vendored as app runtime code |
| Android NDK 29.0.14206865 and CMake 3.22.1 | Compile the vendored libarchive-android source module for ARM ABIs | Build tooling; not bundled as source or runtime license payload |

## Source-bundled / first-party notices

| Component | Purpose | License/provenance note |
| --- | --- | --- |
| Readwide first-party source | App code | Apache-2.0 |
| RAR3/RAR4 PPMd decoder code | Scoped decode-only fallback | First-party Java implementation of public-domain algorithm behavior; contains no copied UnRAR/libarchive code |
| RAR5-container v0/v1 compressed decoder code | Scoped decode-only fallback for covered RAR 5/6 algorithm-v0 and bounded RAR 7 algorithm-v1 compressed/AES cases | First-party Java implementation based on published format behavior; contains no copied Junrar/UnRAR/libarchive code and no RAR compression/creation/password recovery |
| xunazo-derived AZO Java port | EGG AZO extraction | zlib license notice retained in source; modified extraction-only port |
| `third_party/libarchive-android` | Android Java/JNI wrapper plus libarchive 3.8.9 and codec sources | Exact source revisions and retained licenses recorded in `third_party/libarchive-android/UPSTREAM.md`; no prebuilt native binaries are checked in |
| `third_party/zipx-codecs-android` | XADMaster WinZip JPEG and WavPack ZIPX decoder shared library | Exact pins, imported files, local hardening changes, LGPL relinking boundary, and binary notice requirements recorded in `third_party/zipx-codecs-android/UPSTREAM.md`; no prebuilt native binaries are checked in |
| `javax.xml.bind.DatatypeConverter` shim | hwplib Java 17/Android compatibility | Project-local shim covered by Readwide Apache-2.0 license |
| Launcher artwork | App icon | Project-owned generated artwork; see `docs/ASSET_PROVENANCE.md` |

## F-Droid / strict repository caveats

- The default source package does not include optional local jars under `app/libs`.
- The GitHub source package keeps the official Gradle 9.4.1 wrapper (`gradle-wrapper.jar` SHA-256 `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`) and pins the 9.4.1 binary distribution SHA-256 (`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`). F-Droid verifies known official wrapper hashes, so the metadata does not remove it.
- `libarchive-android` and `zipxCodecsAndroid` are compiled from pinned source, using NDK 29.0.14206865 and CMake 3.22.1. Their native-input notices are source-controlled under `app/src/main/assets/open_source_licenses/` and packaged into the APK. The ZIPX LGPL component is a separate replaceable shared library with complete corresponding source/build scripts. `zstd-jni` is test-only and is absent from the APK runtime graph.
- This report is not a full transitive Gradle resolution. Regenerate a resolved dependency tree and SBOM before a strict repository submission.

## Release checklist tied to this report

Before publishing source or APK release materials:

1. Include `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, `docs/FOSS_STATUS.md`, this report, and `docs/SBOM_READWIDE_1_0_17.spdx.json`.
2. Confirm `app/libs` is absent or contains no optional local jars.
3. Confirm no Junrar/UnRAR-license code or jar is bundled in the default source/APK.
4. Confirm the APK contains `assets/open_source_licenses/libarchive_android_and_codecs.txt`, `xadmaster_winzip_jpeg_lgpl_2_1.txt`, and `wavpack_bsd_3_clause.txt`; retain the zstd-jni notice with source/test materials only.
5. Regenerate a resolved Gradle/transitive dependency report before stricter repository submission.
