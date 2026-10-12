# ZIPX native codec source provenance

The `zipxCodecsAndroid` module is built entirely from source. No prebuilt AAR or native shared
library is checked in.

The local Gradle/CMake build uses short external `cxx/zipx` staging and `wv`
WavPack binary directories to leave room for nested architecture probes on Windows.
This changes output paths only; upstream WavPack sources and probe logic are unchanged.

## WavPack

- Upstream: https://github.com/dbry/WavPack
- Version/tag: `5.9.0`
- Commit: `5803634a030e2a11dba602ba057b89cc34486c67`
- License: BSD-3-Clause (`library/src/main/jni/external/wavpack/COPYING`)
- Imported files: the upstream `src`, `include`, and CMake build inputs needed to build
  `libwavpack` from source. Programs, documentation, and unrelated test material are not built.
- Readwide use: lossless decoding of WinZip ZIPX WavPack method 97 after WinZip AES
  authentication/decryption. The official `fuzzing/regression/pcm16.wv` input is retained only as
  the Android instrumentation fixture `src/androidTest/assets/wavpack-pcm16.wv`.

## XADMaster WinZip JPEG

- Upstream: https://github.com/MacPaw/XADMaster
- Version/tag: `v1.10.8`
- Commit: `881e0ec25e249c9ad5bbc1b6782ae8dcdf48a6ed`
- License: LGPL-2.1-or-later for the WinZip JPEG decoder and support files; the included LZMA SDK
  decoder files state that they are public domain. The full LGPL-2.1 text is retained at
  `library/src/main/jni/external/xad-winzip-jpeg/LICENSE`.
- Imported files: `WinZipJPEG/{ArithmeticDecoder,Decompressor,InputStream,JPEG,LZMA}` plus
  `lzma/{LzmaDec,LzmaDec.h,Types.h}` and `ClangAnalyser.h`.
- Readwide modifications: `Decompressor.c` caps compressed/uncompressed per-bundle metadata,
  rejects zero/oversized bundles, requires the expected LZMA output size, fixes unsigned
  little-endian shifts, and checks slice-allocation multiplication and memory bounds. The JNI
  bridge adds streamed Java I/O, decoded-size limits, progress/cancellation propagation, JPEG
  SOI/EOI validation, and Java `IOException` error mapping.

## Relinking and binary distribution

The 1.0.20 7z LZHAM integration adds an independent MIT decoder/common source target
at commit `d09fc6676a0313c3814457fbc76351a68f653092`. The separate Apache-2.0 Java/JNI
adapter owns `libreadwide-lzham.so`; verified properties are limited to version
`0x11`. Four marked upstream changes handle allocation failure, nonfatal assertions,
placement new and strict endings. Full source/original/local hashes are in
[LZHAM provenance](../lzham/README.md). No compressor translation units, plugin or
CLI is imported. This target privately links NDK `c++_static`, with full LLVM
libc++/libc++abi/libunwind notices in the APK alongside the LZHAM MIT asset. Existing
C targets retain their linkage/licensing boundaries. Synthetic regression source
is uncompiled/unrun; real compressed producer/ABI checks remain pending. See
[scope](../../docs/LZHAM_INTEGRATION_1_0_20.md).

The 1.0.20 ZIP PPMd-I stream uses an additional independent `libreadwide-ppmd.so`
target with eight public-domain 7-Zip files (two with marked Readwide freeze-profile
changes) and Apache-2.0 Java/JNI adapters. Explicit raw-stream freeze revisions are
available and connected to checked app/container selection; raw default opening
still rejects implicit freeze selection. It does not link libarchive or the LGPL JPEG/CAB targets. No encoder or
UnRAR implementation is included. [Pinned provenance](../ppmd8/README.md), hashes
and `open_source_licenses/ppmd8_public_domain.txt` are retained. New native/app
regression source is uncompiled/unrun; see [scope](../../docs/ZIP_PPMDI_INTEGRATION_1_0_20.md).

The 1.0.20 CAB work adds a separate LGPL-2.1-only `libreadwide-cab.so` target, not
additional XAD/ZIPX code. It builds the pinned libmspack subset with a strict
continuation-table modification and a public-API-only callback JNI adapter.
Default native filesystem I/O is disabled; `CabNativeDecoder` is the Java API.
See [CAB provenance](../libmspack/README.md) for original/local hashes and licensing,
and [integration status](../../docs/CAB_MULTIVOLUME_INTEGRATION_1_0_20.md) for connected
app routing and unexecuted native validation. The complete LGPL text and source/
relinking notice are bundled as `open_source_licenses/libmspack_cab_lgpl_2_1.txt`.

The 1.0.20 LZ5/Lizard decoder-only additions share this native module. See
`LZ_UPSTREAM.md` for their BSD source provenance, modified-file manifest, reused
xxHash/Zstd helpers, and the Java `LzFamilyInputStream` streaming adapter.

The XAD-derived code and the Readwide JNI bridge are compiled into the separate shared library
`libreadwide-zipx-codecs.so`. The Apache-2.0 app calls that library through
`com.readwide.codecs.ZipxNativeCodecs`; it is not copied into the app's first-party Java source.
The complete corresponding source and build scripts are present in this module so recipients can
modify and rebuild the LGPL library. The app license does not prohibit reverse engineering needed
to debug modifications to the LGPL library.

APK builds include the full LGPL-2.1 text and WavPack/LZ5/Lizard BSD notices under
`assets/open_source_licenses/`.
