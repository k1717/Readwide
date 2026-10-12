# Archive support boundaries — Readwide 1.0.20

Release date: 2026-10-11. The maintainer confirmed a successful build.
This document distinguishes implemented decoding paths from unsupported combinations
and from runtime evidence. It is not a compatibility percentage or a performance result.

## Support labels

- **Implemented:** routed in the current source with its documented integrity and failure checks.
- **Scoped:** restricted to stated properties, graph shapes, volume layouts or filter order.
- **Pending validation:** producer/native/device behavior is not established by source review or compilation.
- **Excluded:** no supported route is claimed; fail rather than fabricate content or guess missing metadata.

## Current format scope

| Family | Implemented paths and boundaries |
| --- | --- |
| ZIP/CBZ/ZIPX | Store/Deflate through Zip4j, plus independent checked plain image reads; supplemental Shrink, Reduce (2–5), Implode, Deflate64, BZip2, LZMA, XZ, Zstandard, JPEG, WavPack and PPMd-I routes with plain, ZipCrypto and WinZip-AES handling. Zstandard/JPEG/WavPack require bundled native decoders. [Independent PPMd-I streaming](ZIP_PPMDI_INTEGRATION_1_0_20.md) uses pinned public-domain decoder source; freeze checks both supported revisions and rejects differing valid outputs. Libarchive fallback remains when the supplemental library is unavailable. ZIP64, canonical names and PKZIP splits are implemented. New PPMd-I/Reduce regressions are unrun; freeze-exhaustion and native combinations still need device checks. |
| 7z/CB7 | Copy, LZMA/LZMA2, PPMd, BCJ2, Deflate/Deflate64, BZip2, Zstandard, Brotli, LZ4, LZ5, Lizard and LZHAM (property versions 0x10/0x11), plus Delta, Swap2/Swap4 and x86/PowerPC/IA64/ARM/Thumb/SPARC/ARM64/RISC-V filters. AES, solid folders, encoded/external metadata and standard numbered split chains are implemented. The supplemental graph accepts registered one-output coders (BCJ2 has four inputs). Brotli accepts standard/large-window raw streams and canonical Brotli-MT frames under coder 04F71102 with three-byte properties, using pinned Google Brotli v1.2.0 Java source. Extension window exponents 10–30 are accepted, subject to device memory; no external custom dictionary is supplied. New regressions and producer long-distance checks are pending. Unknown coders/properties and other graph arities are rejected. Differential anti-items are omitted from the readable-content view, never applied as deletions; differential-backup restoration is not supported. Zstandard requires the bundled native decoder; LZ5/Lizard use the separate native codec module with standard frames or canonical MT framing. LZ5/Lizard retains full linked history and accepts non-MT metadata IDs 0x184d2a51–5f; optional block checksums are verified before decode/output, and explicit frame sizes (including zero) are enforced. [LZHAM](LZHAM_INTEGRATION_1_0_20.md) uses a separate source-built MIT decoder with Adler32, exact size/end and outer 7z integrity checks; other LZHAM property versions are rejected. New LZHAM regressions use synthetic raw blocks, not real compressed producer samples. Code and device regressions are uncompiled/unrun. |
| TAR family / single compressor streams | Commons Compress for pure-Java covered combinations; LZ4 adds first-party modern/legacy frame sequencing with bounded legacy-block buffering (new regressions unrun). Legacy LZ4 has no checksum. Android Zstandard (`.tar.zst`/`.tzst`/`.zst`) routes through the bundled libarchive Zstd filter, including raw single-stream handling with shared storage-based output accounting. Standalone `.zst` extraction applies exactly one layer so nested compressed payloads remain intact. |
| RAR/CBR | libarchive plus first-party stored, LZ/PPMd, solid, AES, split and encrypted-header paths. RAR3/4 mixed LZ/PPMd solid decoding includes plain and AES/split logical streams; stored members preserve compressed history without becoming dictionary bytes. Plain independent LZ forward reading is available without native decoding. Six standard RAR3 filters are implemented: E8, E8/E9, Itanium, Delta, RGB and Audio. Custom VM bytecode is implemented through a BSD-adapted interpreter for within-entry chains with final/intermediate resizing. Raw dictionary and final output counts are separate; each stage consumes the preceding result, later filters use final file offsets, and size changes require explicit EOF and final length/CRC checks. Trailing VM/table controls and zero-final-size compressed streams are handled with explicit markers; ordinary excess data and incomplete regions still fail. Forward-only descriptors can carry between explicit solid-entry markers without carrying raw blocks; final file checks and failure retirement remain. Partially consumed/straddling blocks, late descriptors and unsupported ranges/order remain work. New synthetic VM regressions and producer/device validation are pending. RAR5-container algorithm versions 0/1 (RAR5/6 and RAR7), AES/split and solid history are implemented; unknown algorithm/filter types are rejected. Large history can use temporary storage. Unknown compressed/plain stored sizes are handled. Unknown AES stored sizes use a unique final CRC32/BLAKE2sp match over alignment-tail candidates, including keyed checks and split streams; ambiguous/no-checksum nonempty cases remain excluded. This recovery path has synthetic regressions, not completed producer/device validation. Solid dictionary minima may decrease without shrinking history. Selected/bulk/viewer paths plan the run's required capacity before decoding to preserve bytes for later growth; a direct unplanned decoder still rejects growth after history loss. RAR5-container links, junctions and file-copy references are excluded, not restored as empty files. New transition/redirection regressions are unrun. See [decode boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md). |
| CAB | Read-only listing, image browsing, and extraction. Normal single-file paths retain libarchive; linked sets route through guarded libmspack input/output and checked viewer spools in source. Compressed-multipart producer and device validation remain pending; upstream decoder boundaries still apply. No archive creation or password handling is claimed. See [integration status](CAB_MULTIVOLUME_INTEGRATION_1_0_20.md). |
| LHA / LZH | Stored LH0/LZ4 and compressed LH4/LH5/LH6/LH7 use libarchive for read-only extraction and image browsing. Amiga-style byte splits (`.lha`/`.lzh`, `.l01`, `.l02`, ...) use guarded merging and complete framing checks for header levels 0/1/2/3. Other legacy methods, passwords, creation and separate multi-disc semantics are not implemented. New regression source is uncompiled/unrun. See [scope and evidence](LHA_LH4_INTEGRATION_1_0_20.md). |
| ALZ/EGG | ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/AZO/LZMA, with split-volume support. ALZ handles ZipCrypto, terminated empty archives, tail-sized comments and valid DOS listing timestamps; malformed records fail instead of producing a partial index or a false password requirement. EGG handles inter-file comments/padding, directory/locale/parent-ID names and canonical empty archives. EGG ZipCrypto, AES-128/256 and LEA-128/256 are wired to non-solid and solid paths; solid extraction stages each encrypted block owner's packed stream, checks AES/LEA authentication before decoding and retains decoded-length/nonzero-CRC checks. This uses the existing block-decoding grammar, not new cross-block codec-state support. Encrypted EGG filenames remain unsupported. Parent references must identify directories. New encrypted-solid regressions are unrun, and producer interoperability and Android checks remain pending. |

## ZIP names, volumes and PPMd-I

- ZIP64 and large central directories use metadata-only fallback when the compact path cannot represent the directory. Canonical Unicode/legacy names remain consistent across listing and extraction.
- PKZIP split-directory spans, later-volume entry, case-preserving names and cached earlier-volume changes share complete-volume source identity.
- Duplicate listing metadata follows selected first-member identity; canonical names precede aliases. Metadata recovery past an unrelated damaged local header still validates the requested payload.
- PPMd-I is not 7z PPMd-H. Exact length/end/range/packed checks supplement CRC/MAC; explicit freeze revisions are checked sequentially and differing valid outputs are rejected.
- Historical producer restoration variants, encryption/split/native ABI combinations and resource exhaustion still require representative runtime checks.

## RAR logical streams and filters

- Stored/LZ/PPMd, solid state, packed-part checks and final output checks remain separate. First-party logical input supports the admitted plain/AES/split combinations, not every archive ever produced.
- Forward-only RAR3/4 filter descriptors can carry between explicit solid-entry markers with rebased raw coordinates. Raw regions already partly consumed or spanning file endings, late descriptors and unsupported overlap/order remain rejected.
- Within-entry VM chains can resize intermediate/final results only with explicit completion and final size/CRC checks; raw dictionary bytes never become transformed output history.
- RAR5-container algorithms 0/1 retain their format-specific block bits, Huffman tables, empty/reset/control-tail handling and planned dictionary history. Unknown algorithms/filter types remain errors.
- Unknown-size AES stored members require a unique valid final checksum match over alignment-tail candidates. Ambiguous or no-checksum nonempty cases cannot be safely inferred.
- Link/junction/file-copy records are not regular-file output, and are not restored as empty placeholders.

## 7z graphs and external dictionaries

- Registered one-output coders and four-input BCJ2 retain graph/property validation. Reserved coder flags and unsupported graph arities are rejected.
- LZHAM accepts its stated property versions and dictionary declarations, subject to available resources; other versions are not guessed.
- Brotli standard/large-window decoding retains the built-in dictionary. That dictionary does not satisfy an unrelated custom dictionary requirement.
- LZ4 dictionary-tagged frames can decode when blocks depend only on actual available history. A match that needs absent preset bytes is rejected before publishing that block.
- A dictionary ID is not the dictionary contents. No external dictionary is fabricated from an identifier.
- Anti-items are omitted from read-only content, never executed as deletions. This is not differential-backup restoration.

## ALZ and EGG metadata and encryption

- ALZ Stored/Deflate/BZip2 password candidates that share a header check are disambiguated using decoded length/CRC before output. A single candidate avoids unnecessary probing.
- Valid ALZ DOS timestamps appear in listings; invalid dates remain unknown. A parse error does not imply that a password is required.
- EGG ambiguous ZipCrypto candidates are checked across owned blocks with nonzero CRCs using existing codecs. Source I/O, cancellation, close and resource failures remain terminal instead of prompting a password retry.
- Encrypted-solid EGG maintains each block owner's cipher and stages complete packed data on disk; AES/LEA authentication precedes decoded publication. This does not implement arbitrary cross-block codec state.
- Solid layout totals and attached empty blocks are validated before output. Parent IDs must identify directories; metadata cannot turn ciphertext into a valid pathname.
- Encrypted EGG filenames remain unsupported because a verified filename cipher/framing route is absent.

## TAR, CAB and LHA

- TAR exposes expanded sparse size while preserving physical payload bounds. Links, FIFO/device and unknown special members do not masquerade as ordinary files.
- Bulk/full completion consumes appropriate TAR end markers and outer compressor trailers. Early selected-file reads stay lazy and do not validate an unread tail; concatenated compressor frames do not merge independent TAR archives.
- CAB linked-set discovery validates header relationships and approved volume handles; the separate libmspack bridge retains checked output/spools. Upstream folder/block and decoder-size boundaries still apply.
- LHA supports the listed methods and guarded Amiga byte-split framing, including header levels 0/1/2/3. It rejects independently terminated archives masquerading as one byte-split stream.
- General LHA multi-disc semantics, unsupported legacy methods, passwords and creation are not implied by byte-split support.

## Storage, output and lifecycle

- No blanket payload-size ceiling is imposed on extraction or archive preview. Available disk space, checked arithmetic, Java array/address representability and actual device memory still bound work.
- Fixed thumbnail/AZO/ZIPX LZMA/supplemental 7z policy quotas were removed; format/memory limits are not an unlimited-allocation promise.
- Outputs retain no-follow path checks, file/directory collision checks, guarded staging, source-volume protection and failure rollback. Known source metadata is checked before reuse and publication.
- Length/mtime identities are not cryptographic hashes and cannot detect all external edits or make concurrent writes transactional.
- Decoder/source failures retire state; cancellation cannot become success or trigger blind codec retries. Failed cleanup remains owned where retry is supported.
- Solid first-image latency can remain substantial because preceding data/history and whole-folder integrity may be required. Reduced redundant work is not a measured speedup.

## Validation and source references

Historical host fixtures and added regression sources establish different things.
Some routing/identity cases were executed with native decoding disabled; later
decoder/device cases remain unexecuted. A successful release build is not a passing
matrix of encrypted/split/solid/filter combinations.

- [Routing and identity](ARCHIVE_ROUTING_1_0_20.md)
- [Extraction and ownership](ARCHIVE_EXTRACTION_1_0_20.md)
- [Codec additions and provenance](ARCHIVE_CODEC_ADDITIONS_1_0_20.md)
- [LZ frame corrections](ARCHIVE_STREAM_REPAIR_1_0_20.md)
- [PPMd-I integration](ZIP_PPMDI_INTEGRATION_1_0_20.md)
- [LZHAM integration](LZHAM_INTEGRATION_1_0_20.md)
- [CAB integration](CAB_MULTIVOLUME_INTEGRATION_1_0_20.md)
- [LHA methods and split framing](LHA_LH4_INTEGRATION_1_0_20.md)
- [Release validation checklist](RELEASE_READINESS_1_0_20.md)

Historical reference sources include the [RAR technical note](https://www.rarlab.com/technote.htm),
[7z format](https://github.com/ip7z/7zip/blob/main/DOC/7zFormat.txt),
[LZ4 framing](https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md),
[Google Brotli v1.2.0](https://github.com/google/brotli/tree/v1.2.0/java),
and [independent EGG extraction](https://github.com/alkegi/unegg/blob/master/src/extract.rs).
Pinned imported-source provenance and licensing remain in the corresponding
`third_party` directories and [license report](LICENSE_REPORT_READWIDE_1_0_20.md);
these reference links are not new validation results.
