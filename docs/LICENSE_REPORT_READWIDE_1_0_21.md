# Readwide 1.0.21 direct dependency license report

This report is reconciled from the dependencies declared in the app and native library Gradle files plus source-bundled notices that are relevant to the default Readwide 1.0.21 build.

It is a **direct-dependency/source-declared report**, not a fully resolved transitive SBOM. For strict repository submission, regenerate a resolved Gradle dependency report/SBOM from a clean, network-enabled build environment.

## App identity

| Field | Value |
| --- | --- |
| App name | Readwide |
| Android applicationId | `com.readwide.manager` |
| Version name | `1.0.21` |
| Version code | `10021` |
| First-party license | Apache-2.0 |

## Runtime dependencies declared in `app/build.gradle`

| Dependency/source module | Purpose | Recorded license position |
| --- | --- | --- |
| `androidx.appcompat:appcompat:1.8.0` | AppCompat UI/runtime support | Apache-2.0 |
| `com.google.android.material:material:1.14.0` | Material Components UI support | Apache-2.0 |
| `androidx.recyclerview:recyclerview:1.4.0` | RecyclerView UI lists | Apache-2.0 |
| `androidx.constraintlayout:constraintlayout:2.2.2` | ConstraintLayout UI layouts | Apache-2.0 |
| `androidx.activity:activity:1.10.1` | AndroidX Activity APIs | Apache-2.0 |
| `androidx.drawerlayout:drawerlayout:1.2.0` | DrawerLayout UI | Apache-2.0 |
| `androidx.swiperefreshlayout:swiperefreshlayout:1.2.0` | Pull-to-refresh for the file list | Apache-2.0 |
| `com.github.albfernandez:juniversalchardet:2.5.0` | Text encoding detection | MPL-1.1 option from upstream tri-license |
| `com.google.re2j:re2j:1.8` | Non-backtracking custom TXT contents rules | BSD-3-Clause; full notice retained in APK assets |
| `org.apache.commons:commons-compress:1.28.0` | TAR/7z/stream archive support and ZIP method fallback | Apache-2.0 |
| `project(':libarchiveAndroid')` (`third_party/libarchive-android`) | Source-built Android libarchive 3.8.9 backend for RAR/backend-dependent archive, CAB/LHA/LZH/PMA (stored PM0), and ZIPX PPMd/Zstandard paths | Apache-2.0 (Android wrapper); libarchive and zlib/bzip2/xz/lz4/Zstandard/Mbed TLS inputs retain their permissive FOSS notices |
| `project(':zipxCodecsAndroid')` (`third_party/zipx-codecs-android`) | Separate source-built shared library for ZIPX JPEG (method 96), WavPack (method 97) and 7z LZ5/Lizard frames | XADMaster v1.10.8 WinZip JPEG and Readwide JNI bridge: LGPL-2.1-or-later; WavPack 5.9.0: BSD-3-Clause; LZ5/Lizard and selected entropy code: BSD-2-Clause; XAD LZMA SDK subset: public domain |
| `org.tukaani:xz:1.12` | XZ/LZMA/LZMA2 codec support | 0BSD |
| `project(':brotliJava')` (`third_party/brotli-java`, Google Brotli v1.2.0) | Standard/large-window Brotli payload decoding for 7z | MIT; unchanged pinned upstream runtime source |
| `net.lingala.zip4j:zip4j:2.11.6` | ZIP/CBZ listing/extraction/password/split support | Apache-2.0 |
| `kr.dogfoot:hwplib:1.1.11` | HWP 5.x text extraction backend | Apache-2.0 |
| `kr.dogfoot:hwpxlib:1.0.9` | HWPX text extraction backend | Apache-2.0 |
| `com.tom-roush:pdfbox-android:2.0.27.0` | PDF text extraction with glyph positions for in-document find, and per-page plain text for PDF read-aloud (rendering still uses the platform PdfRenderer) | Apache-2.0 |

### Native wrapper dependency

| Maven coordinate | Purpose | Declared license |
| --- | --- | --- |
| `androidx.annotation:annotation:1.9.1` | Source annotations used by `libarchiveAndroid` | Apache-2.0 |

The coordinate is declared in `third_party/libarchive-android/library/build.gradle`;
the license was checked against its [Google Maven metadata](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.9.1/annotation-1.9.1.pom).

## Test dependencies declared in `app/build.gradle`

| Maven coordinate | Purpose | Recorded license position |
| --- | --- | --- |
| `com.github.luben:zstd-jni:1.5.7-17` | JVM archive-fixture Zstandard codec (`testImplementation`; not packaged in the APK) | BSD-2-Clause (wrapper); test artifact bundles Zstandard (BSD-3-Clause OR GPLv2 dual; used under BSD-3-Clause) |
| `junit:junit:4.13.2` | JVM unit tests | EPL-1.0 |
| `androidx.test:runner:1.7.0` | Android instrumentation test runner | Apache-2.0 |
| `androidx.test.ext:junit:1.3.0` | AndroidX JUnit extension | Apache-2.0 |

## Build tooling

| Component | Purpose | License / terms position |
| --- | --- | --- |
| Gradle wrapper / Gradle | Build system wrapper and build runtime | Apache-2.0 |
| Android Gradle Plugin `com.android.application` / `com.android.library` 9.2.0 | Android build plugins resolved from Google Maven | Android SDK / Google Maven distribution terms; not vendored as app runtime code |
| Android NDK 29.0.14206865 and CMake 3.22.1 | Compile the vendored native modules for ARM ABIs | Tooling itself is not bundled; LZHAM's statically linked C++ runtime has separate LLVM/legacy MIT binary notices above |

## Source-bundled / first-party notices

| Component | Purpose | License/provenance note |
| --- | --- | --- |
| Readwide first-party source | App code | Apache-2.0 |
| RAR3/RAR4 PPMd and mixed-LZ decoder code | Scoped streaming decode-only fallbacks and standard filters; admitted plain/AES/split mixed-solid paths retain output, payload and boundary checks | First-party Java implementation of public-domain algorithm behavior; contains no copied UnRAR/libarchive code |
| RAR5-container v0/v1 compressed decoder code | Scoped decode-only fallback for covered RAR 5/6 algorithm-v0 and bounded RAR 7 algorithm-v1 compressed/AES cases | First-party Java implementation based on published format behavior; contains no copied Junrar/UnRAR/libarchive code and no RAR compression/creation/password recovery |
| xunazo-derived AZO Java port | EGG AZO extraction | zlib license notice retained in source; modified extraction-only port |
| `third_party/libarchive-android` | Android Java/JNI wrapper plus libarchive 3.8.9 and codec sources | Exact source revisions and retained licenses recorded in `third_party/libarchive-android/UPSTREAM.md`; no prebuilt native binaries are checked in |
| `third_party/zipx-codecs-android` | XADMaster WinZip JPEG, WavPack, LZ5 and Lizard decoder shared library | Exact pins, imported files, local hardening changes, LGPL relinking boundary, and binary notice requirements recorded in `third_party/zipx-codecs-android/UPSTREAM.md`; no prebuilt native binaries are checked in |
| `javax.xml.bind.DatatypeConverter` shim | hwplib Java 17/Android compatibility | Project-local shim covered by Readwide Apache-2.0 license |
| Launcher artwork | App icon | Project-owned generated artwork; see `docs/ASSET_PROVENANCE.md` |

### Added source components

TXT contents custom rules add `com.google.re2j:re2j:1.8`, BSD-3-Clause, with the
complete upstream license retained in the APK asset
`open_source_licenses/re2j_bsd_3_clause.txt`. Build and resolved-graph license
validation for 1.0.21 remain pending.

Dependency pins are retained from 1.0.20. Its archive work replaced the initial
`org.brotli:dec:0.1.2` dependency with MIT Google
Brotli v1.2.0 Java runtime source, pinned at `028fb5a23661f123017c060daa546b55cf4bde29`.
The local `:brotliJava` module has no declared runtime dependencies; source selection,
blob hashes and license are retained in `third_party/brotli-java`. The MIT notice is
retained in APK assets. This is not a resolved transitive license audit.

Additional source components: alkegi/unegg LEA adaptation (MIT, commit
9929009ca4d010e8cd88bb796f7a1868162026d4), nwaples/rardecode VM adaptation
(BSD-2-Clause, commit 76ac572a52e7550ccbbc75395d2e08e39c069e36), and LZ5 1.5/
Lizard 2.1 decoder sources selected from 7-Zip-zstd commit
b5ac55ff587ec9c9315ffd624c5c93a2899340d2 (BSD licensing options). The native codecs
join the existing LGPL shared module; no UnRAR implementation or full 7-Zip engine
is imported. New notices are retained in source/APK assets, and EGG test samples
are CC0. See THIRD_PARTY_NOTICES.md and the native module's LZ_UPSTREAM.md.

### LZHAM and C++ runtime boundary

The MIT LZHAM decoder/common subset is pinned at
`d09fc6676a0313c3814457fbc76351a68f653092`, with 31 original/local hash entries and
four marked local changes. Its Apache-2.0 Java/JNI adapter builds the independent
`libreadwide-lzham.so`. No compressor/plugin/CLI source or binary is imported.
The target privately links the NDK 29.0.14206865 static C++ runtime; retained LLVM
libc++/libc++abi/libunwind notices use Apache-2.0 WITH LLVM-exception and applicable
legacy MIT terms. Keep `open_source_licenses/lzham_mit.txt` and
`open_source_licenses/llvm_cxx_runtime.txt` in binaries. Build, binary notice/linkage
inspection and compressed-producer device interoperability for 1.0.21 remain pending.
See [source provenance](../third_party/lzham/README.md).

### ZIP PPMd-I source boundary

Eight C/header files from 7-Zip commit
`9128b80e3a471108678e2b3ec3c985861c8d7b0f` carry explicit public-domain declarations
by Igor Pavlov/Dmitry Shkarin/Dmitry Subbotin. Two carry marked Readwide Apache-2.0
freeze-profile additions; six are unchanged. The decoder-only subset and original/
current hashes are in `third_party/ppmd8`; no encoder or UnRAR code. The Java/JNI adapter is
Apache-2.0 and uses a separate native target without libarchive/LGPL codec linkage.
Retain `open_source_licenses/ppmd8_public_domain.txt` in binaries. Two libarchive ZIP
fixtures carry BSD-2-Clause test notices and are not production assets. The SBOM
records a custom public-domain license reference, not a successful build/legal
certification. See [provenance](../third_party/ppmd8/README.md).

### CAB native-library source boundary

The libmspack CAB decoder subset and six
test-only upstream CAB resources are pinned at
`55d501976171397ccd5d5a7a1ca7da065b1d9a06`, LGPL-2.1. Full license, copyright
headers, upstream incorporation terms and original/local hashes are in
`third_party/libmspack`. A marked LGPL-2.1 modification tightens continuation table
matching. CMake declares a separate `libreadwide-cab.so`; its Readwide JNI adapter
is LGPL-2.1-only and uses the public API, with Java dynamic linkage. The APK license
asset retains the complete LGPL text and relinking/source notice; corresponding
source/build scripts are included. The SBOM records this declared native dependency,
not a successful build. App routes are connected in source; native validation remains pending. This is
not a legal certification or proof of F-Droid approval.


## F-Droid / strict repository caveats

- The default source package does not include optional local jars under `app/libs`.
- The GitHub source package keeps the official Gradle 9.4.1 wrapper (`gradle-wrapper.jar` SHA-256 `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`) and pins the 9.4.1 binary distribution SHA-256 (`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`). F-Droid verifies known official wrapper hashes, so the metadata does not remove it.
- `libarchive-android` and `zipxCodecsAndroid` are compiled from pinned source, using NDK 29.0.14206865 and CMake 3.22.1. Their native-input notices are source-controlled under `app/src/main/assets/open_source_licenses/` and packaged into the APK. The ZIPX LGPL component is a separate replaceable shared library with complete corresponding source/build scripts. `zstd-jni` is test-only and is absent from the APK runtime graph.
- This report is not a full transitive Gradle resolution. Regenerate a resolved dependency tree and SBOM before a strict repository submission.

## Release checklist tied to this report

Before publishing source or APK release materials:

1. Include `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, `docs/FOSS_STATUS.md`, this report, and `docs/SBOM_READWIDE_1_0_21.spdx.json`.
2. Confirm `app/libs` is absent or contains no optional local jars.
3. Confirm no Junrar/UnRAR-license code or jar is bundled in the default source/APK.
4. Confirm the APK contains `assets/open_source_licenses/libarchive_android_and_codecs.txt`, `xadmaster_winzip_jpeg_lgpl_2_1.txt`, and `wavpack_bsd_3_clause.txt`; retain the zstd-jni notice with source/test materials only.
5. Regenerate a resolved Gradle/transitive dependency report before stricter repository submission.
