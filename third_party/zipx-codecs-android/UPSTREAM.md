# ZIPX native codec source provenance

The `zipxCodecsAndroid` module is built entirely from source. No prebuilt AAR or native shared
library is checked in.

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

The XAD-derived code and the Readwide JNI bridge are compiled into the separate shared library
`libreadwide-zipx-codecs.so`. The Apache-2.0 app calls that library through
`com.readwide.codecs.ZipxNativeCodecs`; it is not copied into the app's first-party Java source.
The complete corresponding source and build scripts are present in this module so recipients can
modify and rebuild the LGPL library. The app license does not prohibit reverse engineering needed
to debug modifications to the LGPL library.

APK builds include the full LGPL-2.1 text and WavPack BSD notice under
`assets/open_source_licenses/`.
