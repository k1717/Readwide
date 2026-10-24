# LZ5 / Lizard decoder sources

Readwide uses a selected source subset from 7-Zip-zstd commit
`b5ac55ff587ec9c9315ffd624c5c93a2899340d2` (2026-10-04 source review),
under `C/lz5` (LZ5 1.5) and `C/lizard` (Lizard 2.1). These codec libraries
are BSD-2-Clause; they are not the 7-Zip archive engine or UnRAR implementation.
The selected Lizard entropy files offer BSD/GPL alternatives; Readwide selects
the BSD option and retains all Yann Collet/Facebook copyright headers.

`LZ_SOURCES.json` records the pinned upstream text hashes and functionally modified
files. Imported text uses normalized line endings/final newlines; these hashes
identify upstream content, not necessarily byte-identical local files.
Original notices remain in each source file and each codec's LICENSE. APK
assets retain the LZ5/Lizard and entropy notices. No binaries are downloaded or
fetched during the build. No Go/Rust runtime or 7-Zip executable is included.

Local changes:

- The shared JNI adapter defines `LZ5F_DISABLE_OBSOLETE_ENUMS` and
  `LIZARDF_DISABLE_OBSOLETE_ENUMS` before including the frame headers. Their
  obsolete unprefixed enum/typedef aliases collide in one translation unit;
  the adapter uses only the separate prefixed APIs. This integration fix does
  not modify the imported headers, decoder behavior or ABI. The ARM64
  compilation failure is patched; a subsequent 1.0.20 build passed ARM64/ARMv7
  native build tasks before an unrelated app Java compilation failure.
- Both frame decoders accept optional block checksums and verify seed-zero XXH32
  over packed block bytes before decoding/copying the block. Existing input buffers
  reserve four extra bytes for the checksum; checked stored blocks use the buffered
  path, while unchecked stored blocks remain streamed. Empty stored blocks do not
  end a frame. An explicit zero content size is enforced and size subtraction is
  checked before underflow. Private context fields retain the flags; public frame
  structs are unchanged. A checksum error is appended without renumbering existing
  error codes. The legacy unsupported-error enum is retained for compatibility.
- LZ5 frame dictionary copies/compaction retain the full 4 MiB codec window.
  The raw decoder checks safe-mode offsets even with a full dictionary and guards
  truncated literal-length input. Frame reallocation clears freed pointers on both
  codecs; LZ5 also considers input-block capacity independently of combined buffers.
  These are local changes, not an upstream version update or validated release.
- The stream adapter accepts bounded skippable metadata IDs 0x184d2a51–5f, keeps
  canonical MT ID 0x50 strict, retires unchecked failures and retries failed source
  close. Block checksums are a local extension of the imported frame APIs, based on
  the upstream [LZ5 1.5 frame specification](https://github.com/inikep/lizard/blob/v1.5/lz5_Frame_format.md)
  and [Lizard frame specification](https://github.com/inikep/lizard/blob/v2.1/doc/lizard_Frame_format.md).
- The two frame source files guard encoder functions with READWIDE_DECODE_ONLY;
  LZ5's encoder-only minimum compression level is guarded by the same switch.
  Encoder-only .c files are not included. Unused functions in shared LZ5 code
  are hidden and eligible for linker section removal.
- Frame checksum includes reuse the already vendored LZ4 xxHash source under
  a private RW_LZF_ namespace. Four Lizard helper wrappers include the existing
  libarchive module's Zstd portable headers through explicit CMake include paths.
- Readwide's LGPL-2.1-or-later JNI/Java adapter owns native decoder lifetimes,
  bounds direct-buffer calls, checks cancellation between steps, and validates
  declared 7z output and canonical MT packed-frame lengths.
- No upstream multithread wrapper, command-line tool, exposed compressor API, 7-Zip
  archive-engine source, or UnRAR-license file is included.

The decoders are linked into the existing replaceable LGPL ZIPX shared library;
its LGPL distribution/relinking obligations remain. All sources and build rules
are supplied. A subsequent 1.0.20 build passed ARMv7/ARM64 native compilation;
regression sources remain uncompiled/unrun. Device decoding, real compressed
producer files, AES/split/solid 7z compositions,
malformed input and native memory-safety checks remain release validation work.
