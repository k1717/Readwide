# FOSS status for Readwide 1.0.21

This is the project-level FOSS status note for the default Readwide 1.0.21 source package and default release build. It is not legal advice; it records the current release position and the checks a reviewer should make.

Version 1.0.21 retains the permissions, dependency pins and bundled sources from
1.0.20. That version replaced
`org.brotli:dec:0.1.2` with pinned MIT Google Brotli v1.2.0 Java source in the local
`:brotliJava` module for standard/large-window 7z payloads. Its source, license and
reflection keep rules are included; the notice is bundled as an APK asset. See
[source provenance](../third_party/brotli-java/README.md). Other inherited adaptations include MIT alkegi/unegg
LEA (not ESTsoft's proprietary UnEGG), BSD rardecode VM logic and BSD LZ5/Lizard
codec sources selected from 7-Zip-zstd. No Bandizip/Ark, proprietary ESTsoft UnEGG,
7-Zip archive engine or UnRAR-license implementation is included. Exact source
revisions, notices and scope are recorded in ARCHIVE_CODEC_ADDITIONS_1_0_20.md.
This is a declared-source review, not a resolved build or F-Droid approval. See the
[release notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_21.md).

## Current assessment

Custom TXT contents rules retain the pure-Java Maven dependency RE2/J 1.8 under
BSD-3-Clause. The full upstream license is retained in APK assets, the direct
license report and source SBOM record the declaration, and no network permission
or runtime service is added. Build, resolved-dependency licensing and final APK
review for 1.0.21 remain pending. This is not a new F-Droid approval claim.

The 7z LZHAM `0x10`/`0x11` profiles use a pinned MIT decoder/common subset with four marked
local changes and a separate Apache-2.0 JNI adapter/shared target. It privately
links the NDK static C++ runtime; full LLVM and applicable legacy MIT license texts
are included alongside the LZHAM MIT APK notice. Source and original/local hashes
are retained in `third_party/lzham`. No compressor/plugin/CLI binary is imported.
This records intended source/build boundaries, not completed native validation,
binary license audit or F-Droid approval. See [scope](LZHAM_INTEGRATION_1_0_20.md).

The independent ZIP PPMd-I stream uses eight public-domain 7-Zip files, two with
marked Readwide Apache-2.0 freeze-profile additions. Original/current hashes,
declarations and a binary attribution asset are retained. Its Apache-2.0
Readwide Java/JNI adapter builds a separate `readwide-ppmd` target without encoder,
UnRAR or LGPL codec linkage. Two attributed libarchive test ZIPs retain BSD-2-Clause
notices and stay outside production assets. Native runtime validation remains pending;
see [source boundary](ZIP_PPMDI_INTEGRATION_1_0_20.md). This is not F-Droid approval.

The source package also retains an LGPL-2.1 libmspack CAB decoder
subset and attributed test-only resources, pinned at
`55d501976171397ccd5d5a7a1ca7da065b1d9a06`, with a marked LGPL-2.1 strict-continuation
patch in cabd.c. A separate LGPL-2.1-only CAB JNI/shared-library target is declared
by CMake; default native filesystem I/O is disabled. Source incorporation terms,
upstream/local hashes and corresponding rebuild sources remain; the APK includes
the full LGPL license/relinking notice. Linked-set app extraction routing is connected
in source; native runtime validation is not established by build success. See
[CAB integration status](CAB_MULTIVOLUME_INTEGRATION_1_0_20.md).

The default Readwide 1.0.21 source package is intended to remain FOSS-friendly:

- First-party app source is Apache-2.0; separately marked native-library adapters
  retain their stated LGPL terms, including the LGPL-2.1-only CAB adapter.
- Source code needed for the default build is included in the repository/source package.
- No proprietary app EULA is added by the project.
- The default build does not bundle Junrar or RARLAB UnRAR-license code.
- The default manifest does not request `INTERNET`.
- The app contains no ads, analytics, telemetry SDK, Firebase, Google Play Services dependency, account system, cloud sync, developer-operated upload backend, or in-app network update checker.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Private signing files, build outputs, and optional proprietary binary dependencies are not part of the default source package.

## Dependency and bundled-component summary

| Component | Use | License position recorded for this release |
| --- | --- | --- |
| Readwide first-party source | App code | Apache-2.0 |
| alkegi/unegg LEA adaptation | EGG LEA-128/256 cipher | MIT; full upstream notice in APK assets |
| nwaples/rardecode VM adaptation | Scoped custom RAR3 filters | BSD-2-Clause; full upstream notice in APK assets |
| LZ5/Lizard and entropy decoder subset | Native 7z frame decoding | BSD options; retained source and APK notices; existing combined library remains LGPL-2.1-or-later |
| AndroidX / Material Components | Android UI/runtime support | Apache-2.0 |
| JUniversalChardet | Text encoding detection | MPL-1.1 option used by this project |
| Apache Commons Compress | TAR/7z/stream archive support, ZIP method fallback, and decoded ZIPX Deflate64/BZip2 streams after WinZip AES handling | Apache-2.0 |
| libarchive-android / libarchive 3.8.9 | Source-built Android archive backend, including ZIPX PPMd/Zstandard fallback and CAB/LHA/LZH read paths | Android wrapper under Apache-2.0; vendored libarchive and codec source under retained permissive notices |
| XADMaster WinZip JPEG / WavPack 5.9.0 | Separate source-built shared library for WinZip-AES ZIPX JPEG and WavPack extraction | XAD WinZip JPEG and the JNI bridge under LGPL-2.1-or-later; WavPack under BSD-3-Clause; complete source/build scripts and in-APK license texts retained |
| XZ for Java | XZ/LZMA support | 0BSD |
| Google Brotli v1.2.0 Java source (`:brotliJava`) | Standard/large-window Brotli inside 7z; first-party raw/MT adapter | MIT; pinned runtime source and notice retained in source/APK assets |
| zstd-jni | JVM-only Zstandard archive fixtures (`testImplementation`); not packaged in the APK | BSD-family licensing path recorded in `THIRD_PARTY_NOTICES.md` |
| Zip4j | ZIP/CBZ/ZIPX listing, extraction, split support, and WinZip AES password/decryption primitives | Apache-2.0 |
| hwplib | HWP 5.x read/text extraction backend | Apache-2.0 |
| hwpxlib | HWPX read/text extraction backend | Apache-2.0 |
| PdfBox-Android | PDF text extraction for in-document find (rendering stays on the platform PdfRenderer) | Apache-2.0 |
| xunazo-derived AZO port | EGG AZO extraction | zlib license notice retained in source |

See the 1.0.21 source/direct-dependency reports `docs/LICENSE_REPORT_READWIDE_1_0_21.md`, `docs/SBOM_READWIDE_1_0_21.spdx.json`, and `THIRD_PARTY_NOTICES.md` for detail.

## RAR / CBR boundary

Readwide is extraction/read-only for RAR/CBR. It does not create RAR archives and does not implement password recovery.

Default RAR handling is deliberately conservative:

1. libarchive-android is the primary backend for common compressed RAR read/extract attempts.
2. First-party Java handles covered metadata, safe paths, stored entries, stored split paths, and selected validation/cleanup paths.
3. Scoped first-party decode-only fallbacks exist for admitted RAR3/RAR4 PPMd and mixed LZ/PPMd solid runs, including covered AES and logical split routes, plus covered RAR5-container algorithm-v0 (RAR 5/6), algorithm-v1 (RAR 7) and RAR5 AES paths. CRC, framing and password checks remain. The latest transition/filter regression sources are not executed interoperability evidence.
4. RAR7 support is an original first-party implementation of the published container fields and decompression changes: 80 distance codes, fractional dictionaries, non-power-of-two wrapping, and 64-bit distance reads. It does not bundle or depend on Junrar or UnRAR. A 1 TiB declaration is parsed without allocating that amount in RAM. The inherited paged-history implementation retains older history in AES-GCM temporary pages using platform JCE, without a new dependency. See `DEV_CHANGES_1_0_18.md`.
5. The first-party classic-LZ path recognizes the six standard RAR3 VM filters and preserves program/length reuse across VM records and solid state. Scoped custom-VM and encrypted/split paths retain the restrictions in [current archive support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md); complete RAR compatibility is not claimed. Long-history RAR5/RAR7 handling is implemented but not yet runtime-validated.

No Junrar or RARLAB UnRAR-license source code is bundled in the default build.

## HWP / HWPX boundary

HWP/HWPX support is read-only and text-first:

- `.hwp` uses `kr.dogfoot:hwplib:1.1.11` in the 1.0.21 source.
- `.hwpx` uses `kr.dogfoot:hwpxlib:1.0.9`.
- Both are recorded as Apache-2.0 dependencies.
- Readwide does not bundle Hancom proprietary SDKs, LibreOffice, a server conversion service, or non-FOSS HWP code.
- Hancom-compatible layout rendering, editing/writing, original page-count parity, and password/encrypted HWP support are not claimed.

## Permission and privacy boundary

The app is a local file browser/reader and requests broad storage access for that purpose. Broad storage access is not a FOSS license issue by itself, but it is a privacy/review-sensitive Android permission and must stay documented in `PRIVACY.md`, the F-Droid metadata, and release notes.

Readwide 1.0.21 retains the persisted, read-oriented Storage Access Framework tree browser introduced in 1.0.16. It introduces no dependency, network service, or permission and does not weaken the disclosure for the separate raw-path file-management mode.

The `FileProvider` configuration includes broad external storage sharing support so explicitly initiated open-with/share actions can grant temporary read access to selected files outside app-private storage. The provider is not exported and grants access through Android intent URI grants; this behavior still needs to remain documented because static scanners may flag broad `external-path` use.

## Optional local jars

The default source package does not require optional local jars under `app/libs`. If a developer adds local jars in a private fork, that is a separate custom build and must be re-audited before calling the resulting APK FOSS.

## Release signing boundary

Release signing is conditional. If `READWIDE_KEYSTORE_PATH`, `READWIDE_KEYSTORE_PASSWORD`, `READWIDE_KEY_ALIAS`, and `READWIDE_KEY_PASSWORD` (or the legacy `TEXTVIEW_*` names) are absent, `assembleRelease` should build an unsigned release artifact instead of requiring a developer keystore. Local GitHub APK releases should be signed outside the source tree.

## GitHub vs F-Droid source handling

The GitHub source package keeps the official Gradle 9.4.1 wrapper and its pinned
distribution configuration in `gradle/wrapper/gradle-wrapper.properties`. The
historical F-Droid metadata does not remove the wrapper. Source ZIP inventory/CRC
checks are separate from the build tooling's distribution verification.

## Binary release notice checklist

When distributing APK/AAB files, keep these alongside the binary release assets:

- `LICENSE`
- `NOTICE`
- `THIRD_PARTY_NOTICES.md`
- `PRIVACY.md`
- `docs/FOSS_STATUS.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_21.md` (current source-declared report)
- `docs/SBOM_READWIDE_1_0_21.spdx.json` (current source-declared report)

The complete source-controlled `app/src/main/assets/open_source_licenses/`
directory is the expected APK notice inventory. It includes libarchive/codecs,
ZIPX JPEG/WavPack and the additional Brotli, RE2/J, LEA, RAR VM, LZHAM, PPMd-I and
CAB notices. Compare all required entries and their contents against the release
APK; checking only the original three libarchive/ZIPX assets is insufficient.

The Android packaging block excludes duplicate dependency `META-INF/LICENSE*` / `META-INF/NOTICE*` resources to avoid resource merge conflicts. That does not remove the obligation to provide project-level and third-party notices with source and binary release materials; the native notice asset above supplies the in-APK copy for libarchive-android and its statically linked codecs.

## Caveats

- The current `docs/LICENSE_REPORT_READWIDE_1_0_21.md` and `docs/SBOM_READWIDE_1_0_21.spdx.json` reports are direct-dependency/source-declared documents, not a fully resolved transitive Gradle SBOM.
- A strict repository submission should regenerate a resolved dependency report/SBOM from a clean, network-enabled build environment.
- The Android runtime native backend is built from the vendored libarchive-android source and therefore requires Android NDK 29.0.14206865 and CMake 3.22.1. `zstd-jni` is test-only; F-Droid release assembly does not need its desktop native resources.
- Archive compatibility claims must align with `README.md` and [current archive support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md). Older RAR/7z revalidation reports and the 1.0.2 matrix retain their historical scope, not current validation authority.
