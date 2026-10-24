# Third-party notices

Readwide first-party source code is licensed under the Apache License 2.0. Keep this file with source releases and with binary release materials such as APK/AAB release assets.

This notice summarizes direct dependencies and bundled source for Readwide 1.0.21, retaining the 1.0.20 dependency baseline. The matching license report and source dependency SBOM record the declared versions and licenses. They do not replace a Gradle-resolved transitive dependency report.

## Default build boundary

The default Readwide 1.0.21 package retains the FOSS-oriented public line:

- first-party source: Apache-2.0;
- no Junrar or RARLAB UnRAR-license code bundled;
- no optional local decoder jar required under `app/libs`;
- HWP/HWPX support through Apache-2.0 dogfoot Java libraries;
- PDF text extraction through an Apache-2.0 pure-Java library (PdfBox-Android), with PDF page rendering still on the platform PdfRenderer;
- no Hancom proprietary SDK, LibreOffice bundle, or server conversion service;
- no ads, analytics, telemetry SDK, account system, or network update checker in the default app.

See `docs/FOSS_STATUS.md`, `docs/LICENSE_REPORT_READWIDE_1_0_21.md`, and `docs/SBOM_READWIDE_1_0_21.spdx.json`.

## ALZ Obfuscated Deflate format references

`AlzObfuscatedDeflateInputStream.java` is independently written Apache-2.0 source.
Its bitstream grammar follows RFC 1951 and the method-3 format variation described
in public XADMaster source. XADMaster is LGPL-2.1-or-later; no XADMaster code is
copied, translated, linked or bundled. No proprietary Bandizip implementation is
included. Format references and validation limits are in
[ALZ format notes](docs/ALZ_FORMAT_NOTES.md).

## RE2/J contents rules

`com.google.re2j:re2j:1.8` supplies the pure-Java, non-backtracking matcher for
custom TXT contents expressions. Upstream source is unchanged; retain the
BSD-3-Clause copyright and full license in the APK asset
`open_source_licenses/re2j_bsd_3_clause.txt`. See the
[tagged source](https://github.com/google/re2j/tree/re2j-1.8) and
[license](https://github.com/google/re2j/blob/re2j-1.8/LICENSE).
This is a declared dependency; the source inventory does not verify the final APK
contents or a resolved transitive dependency graph.

## 7z LZHAM and its private C++ runtime

`third_party/lzham` retains 31 source/license files from Richard Geldreich, Jr.'s
MIT `lzham_codec_devel`, commit `d09fc6676a0313c3814457fbc76351a68f653092`.
Four files have marked Readwide changes for allocation failure, nonfatal assertions,
placement-new declarations and strict stream endings. Original/local hashes and
full license are retained. Only the decoder/common subset is built; no compressor
translation units, 7-Zip plugin, CLI or binary are imported. Readwide's Apache-2.0
Java/JNI adapter builds a separate `libreadwide-lzham.so` for the verified 7z
property layout/version `0x11`. Other property versions remain unsupported.

This target privately links the NDK 29.0.14206865 static C++ runtime. The pinned
LLVM libc++/libc++abi/libunwind license texts (Apache-2.0 WITH LLVM-exception and
applicable legacy MIT terms) are retained in `open_source_licenses/llvm_cxx_runtime.txt`;
`open_source_licenses/lzham_mit.txt` retains the full LZHAM MIT notice. Existing
JPEG/CAB/PPMd targets remain C-only; no C++ object/exception crosses JNI boundaries.
These are declared source/build boundaries, not verified APK linkage or F-Droid
approval. See [provenance](third_party/lzham/README.md) and
[scope and pending checks](docs/LZHAM_INTEGRATION_1_0_20.md).

## ZIP PPMd-I native stream

`third_party/ppmd8` retains eight public-domain C/header files from
7-Zip commit `9128b80e3a471108678e2b3ec3c985861c8d7b0f`. Per-file declarations by
Igor Pavlov, Dmitry Shkarin and Dmitry Subbotin and original/current hashes are
retained. Two files contain marked Readwide Apache-2.0 freeze-profile additions;
the other six are unchanged. The app uses checked selection of both freeze profiles;
raw decoding alone does not establish which revision a container used.
Only the Ppmd8 decoder/common model is compiled; no encoder, archive parser or
UnRAR implementation. The Readwide Apache-2.0 Java/JNI adapter builds a separate
`libreadwide-ppmd.so`, independent of libarchive and the LGPL codec targets.
The production APK retains `open_source_licenses/ppmd8_public_domain.txt`.

Two attributed libarchive ZIP regression resources are test-only under retained
BSD-2-Clause test notices, not production assets. Native builds/tests are pending.
See [provenance](third_party/ppmd8/README.md),
[fixture notices](app/src/test/resources/archives/zip-ppmdi/PPMD_FIXTURES.md) and
[implementation boundaries](docs/ZIP_PPMDI_INTEGRATION_1_0_20.md).

## libmspack CAB native integration

`third_party/libmspack` contains the CAB decoder subset from Stuart Caie's
libmspack, commit `55d501976171397ccd5d5a7a1ca7da065b1d9a06`, LGPL-2.1. Per-file
copyright headers, full `COPYING.LIB`, upstream README/additional incorporation
terms and SHA-256 provenance are retained. The LGPL-2.1 `cabd.c` modification requires
ordered continuation size/offset/name/encoding identity and disables partial-match
merges under `READWIDE_CAB_STRICT_SET`. Original and local hashes are recorded.
Six upstream stored/multipart and MSZIP/LZX/Quantum CAB regression
resources are represented as attributed Base64 test-only files under
`app/src/test/resources/archives/cab-multipart`; they are not production assets.

The existing native module declares the separate LGPL-2.1-only `libreadwide-cab.so`
target. Its Readwide JNI adapter is LGPL-2.1-only and uses the public mspack API;
the Java app/API connects dynamically. Corresponding source and CMake/Gradle rebuild
scripts are included, with no restriction on reverse engineering to debug modified
library versions. The full license/relinking notice is packaged at
`assets/open_source_licenses/libmspack_cab_lgpl_2_1.txt`. Default native filesystem I/O
is disabled. Linked-set app extraction/viewer routes are connected in source;
compilation/native validation remain pending. See [source provenance](third_party/libmspack/README.md)
and [integration status](docs/CAB_MULTIVOLUME_INTEGRATION_1_0_20.md).

## RAR5 checksum algorithm reference

The 1.0.18 key-dependent CRC32 implementation uses Readwide Java and platform
JCE. Its format behavior was cross-checked with Nicholas Waples'
[rardecode](https://github.com/nwaples/rardecode) (BSD-2-Clause;
[license](https://github.com/nwaples/rardecode/blob/main/LICENSE)).
No Go source or new runtime component is bundled. See
`docs/DEV_CHANGES_1_0_18.md` for scope and provenance.
No UnRAR source was used for this change.

## EGG LEA and RAR3 VM source adaptations

- `EggLeaCipher.java` adapts `alkegi/unegg` `src/lea.rs`, commit
  `9929009ca4d010e8cd88bb796f7a1868162026d4`, MIT, Copyright 2026 Sungjoon Moon.
  This is the independent Rust project, not ESTsoft's proprietary UnEGG decoder.
  Only the cipher logic is adapted; no Rust runtime is bundled. The full notice
  is in `app/src/main/assets/open_source_licenses/unegg_mit.txt`.
- `Rar3VirtualMachine.java` adapts Nicholas Waples' `rardecode` `vm.go` and VM
  portions of `filters.go`, commit `76ac572a52e7550ccbbc75395d2e08e39c069e36`.
  Copyright 2015 Nicholas Waples, BSD-2-Clause. The full notice is in
  `app/src/main/assets/open_source_licenses/rardecode_bsd2.txt`. No Go runtime or
  UnRAR implementation is bundled. This new adaptation is separate from the older
  RAR5 checksum algorithm reference described above.
- EGG LEA producer fixtures are CC0 test-only resources, with upstream URLs and
  decoded-byte SHA-256 values in `app/src/test/resources/archives/egg-lea/README.md`.

## Native LZ5/Lizard source subset

LZ5 1.5 and Lizard 2.1 decoder sources are selected from 7-Zip-zstd commit
`b5ac55ff587ec9c9315ffd624c5c93a2899340d2` (`C/lz5`, `C/lizard`). Their BSD
licenses and all per-file copyright headers are retained. For the dual-licensed
Lizard entropy code, the BSD option is selected. Source hashes and modifications
are recorded in `third_party/zipx-codecs-android/LZ_SOURCES.json` and `LZ_UPSTREAM.md`.
APK notices include `lz5_bsd2.txt`, `lizard_bsd2.txt` and `lizard_entropy_bsd.txt`
under `open_source_licenses`. Existing vendored xxHash and Zstd helper headers are
reused with their existing notices. These are codec libraries, not 7-Zip's archive
engine or its UnRAR-license files. The existing combined shared object and Readwide
native adapter retain LGPL-2.1-or-later licensing and supplied relinking sources.

## Runtime dependencies

### Brotli for Java

- Source module: `project(':brotliJava')`, Google Brotli v1.2.0 at commit
  `028fb5a23661f123017c060daa546b55cf4bde29`, replacing `org.brotli:dec:0.1.2`.
- Purpose: standard and large-window Brotli behind Readwide's first-party 7z raw/Brotli-MT adapter.
  Twelve unchanged runtime Java files (including the built-in dictionary) are compiled
  from `third_party/brotli-java`; no native/prebuilt decoder or runtime fetch is added.
- License: MIT. The full notice is included in
  `app/src/main/assets/open_source_licenses/brotli_mit.txt` for binary distribution.
- Sources: [pinned Google Brotli](https://github.com/google/brotli/tree/028fb5a23661f123017c060daa546b55cf4bde29/java/org/brotli/dec),
  [local provenance and blob hashes](third_party/brotli-java/README.md),
  [retained license](third_party/brotli-java/LICENSE). The MIT SynthTest dictionary
  vector is adapted in `SevenZBrotliInputStreamTest` with its copyright attribution.
  Upstream reflection keep rules are included in the release R8 configuration.
- No Bandizip/Ark/proprietary ESTsoft UnEGG SDK or binary is included. ZIP Reduce and Brotli-MT framing
  are first-party format implementations; no vendor decoder source was copied.

### AndroidX libraries

- `androidx.appcompat:appcompat:1.8.0`
- `androidx.recyclerview:recyclerview:1.4.0`
- `androidx.constraintlayout:constraintlayout:2.2.2`
- `androidx.activity:activity:1.10.1`
- `androidx.drawerlayout:drawerlayout:1.2.0`
- `androidx.swiperefreshlayout:swiperefreshlayout:1.2.0`
- `androidx.annotation:annotation:1.9.1` (libarchive Android wrapper)

License: Apache License 2.0.

### Material Components for Android

- Artifact: `com.google.android.material:material:1.14.0`
- License: Apache License 2.0.

### JUniversalChardet

- Artifact: `com.github.albfernandez:juniversalchardet:2.5.0`
- Purpose: text encoding detection.
- License position used by this project: Mozilla Public License 1.1 option from the upstream tri-license.

### Apache Commons Compress

- Readwide adapter: `org/apache/commons/compress/archivers/zip/ReadwideZipLegacyStreams.java`
  is original glue in the dependency package to call its package-private Shrink/Implode
  constructors after WinZip AES decryption. It copies no decoder implementation and
  adds no dependency; review this adapter when upgrading Commons Compress 1.28.0.
  Constructor contracts: https://github.com/apache/commons-compress/tree/rel/commons-compress-1.28.0/src/main/java/org/apache/commons/compress/archivers/zip
  Format reference: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT

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
- Local LHA modification: CRC-bearing level-0 PMarc stored PM0 reuses the existing stored-data path, with matching size checks and comments kept separate from Unix metadata. Compressed PM1/PM2 are excluded. The retained `libarchive.patch` and `UPSTREAM.md` record this change; no PMarc reference-decoder source or new dependency is included.
- Local 7z modification: encoded headers require one folder instead of silently decoding only the first of several. Ordinary multi-folder file data is unaffected. The retained patch records this check; native execution remains pending.
- 7z method note: 7z BCJ2 and PPMd entries (which Commons Compress cannot handle for AES-encrypted archives) are decoded by first-party Java in `SevenZBcj2ArchiveReader`/`SevenZBcj2Decoder`/`SevenZAesDecoder`/`SevenZPpmd7Decoder` (see `docs/SEVENZ_BCJ2_READER_READWIDE_1_0_11.md` and `docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md`). `SevenZPpmd7Decoder` is a Java port of the public-domain Ppmd7 reference - Dmitry Shkarin's PPMd var.H (2001, public domain) as maintained in Igor Pavlov's Ppmd7 codec (public domain) - obtained from pyppmd's source distribution; public-domain code carries no license obligations and is Apache-2.0 compatible. Vendored libarchive also decodes unencrypted 7z PPMd as a fallback, via the public-domain `Ppmd7.c` carried under libarchive's BSD-style licensing. No 7-Zip archive-engine or UnRAR-license source is included; test fixtures for these methods are self-made with p7zip from first-party content.
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

- Artifact: `com.github.luben:zstd-jni:1.5.7-17`
- Purpose: JVM unit-test fixtures for Commons Compress Zstandard streams. It is a `testImplementation` dependency and is not packaged in the Android APK; Android runtime Zstandard uses libarchive's bundled filter.
- License position: BSD-family licensing path for the JNI binding and bundled native Zstandard library.
- Distribution note: no zstd-jni binary notice is required for the APK because its classes/native resources are not shipped there. Keep this source/test notice with the repository.

### Zip4j

- Artifact: `net.lingala.zip4j:zip4j:2.11.6`
- Purpose: primary ZIP/CBZ listing, extraction, password, AES, and split ZIP handling.
- License: Apache License 2.0.

### hwplib

- Artifact: `kr.dogfoot:hwplib:1.1.11`
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
- Android NDK 29.0.14206865 and CMake 3.22.1: compile vendored native sources. The tooling itself is not bundled; LZHAM also links the NDK C++ runtime, with its separate binary notices recorded above.

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

The Readwide launcher artwork source and checked-in launcher/adaptive icon assets are project-owned artwork for this release line. See `docs/ASSET_PROVENANCE.md`.

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
- `docs/LICENSE_REPORT_READWIDE_1_0_21.md` (current source-declared report)
- `docs/SBOM_READWIDE_1_0_21.spdx.json` (current source-declared report)
