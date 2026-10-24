# CAB multi-volume integration — Readwide 1.0.20

App routing has source review only; no compilation, tests or native execution.
**Linked CAB listing, selected/bulk extraction and forward viewing are connected
in source, not runtime-validated.** Normal single-CAB/LHA paths still prefer the
existing libarchive backend. A linked set requires the supplemental CAB decoder;
missing/corrupt sets are not retried as a single cabinet or concatenated bytes.

## Prepared source

- `CabVolumeResolver` reads CFHEADER/optional reserve and linked-volume names from
  any selected part, then walks explicit previous/next links. One directory catalog
  is reused; numbered filenames and concatenating cabinet files are not substitutes.
- Every edge must have matching set IDs, consecutive 16-bit indices and reciprocal
  names. A complete chain must reach index zero. Cycles, missing parts, case/name
  ambiguity, path components/ADS and canonical links outside the selected directory
  fail. Unrelated files are not parsed. Returned lists are immutable.
- Names must round-trip under a supported encoding and identify exactly one real
  sibling (UTF-8, Windows-1252, CP437, MS949, Shift_JIS, GB18030 or Big5 when available).
  This does not establish support for every producer locale. No replacement decoding
  or ambiguous filename guess is used. Header strings follow the decoder's 256-byte
  metadata API boundary, not a payload/extraction quota.
- Declared cabinet/table extents are checked against physical length. Optional
  reserves are skipped within metadata bounds. Snapshots detect path, size, mtime
  and availability changes; they are not cryptographic identity or a complete
  concurrent-mutation defense. App inputs and the native bridge recheck opened inputs.
- The 14-file CAB decoder subset of libmspack is vendored with LGPL-2.1
  notices, pinned provenance and hashes in [third_party/libmspack](../third_party/libmspack/README.md).
  `cabd.c` now has a marked strict-continuation modification; the other thirteen
  C/header files remain unchanged. Both upstream and local hashes are retained.

The public upstream [append API](https://github.com/kyz/libmspack/blob/55d501976171397ccd5d5a7a1ca7da065b1d9a06/libmspack/mspack/mspack.h)
supports joined cabinet sets, but its implementation only warns on differing set
IDs/odd order. The wrapper therefore treats these as errors before merging. The
[CAB reader](https://github.com/kyz/libmspack/blob/55d501976171397ccd5d5a7a1ca7da065b1d9a06/libmspack/mspack/cabd.c)
includes stored/MSZIP/LZX/Quantum and split-CFDATA handling. Its remaining codec
limitations still apply; vendoring it is not proof of universal CAB compatibility.

## Native bridge now in source

- `CabNativeDecoder` and `cab_decoder_jni.c` expose a closeable session with immutable
  entry metadata and indexed extraction. The caller supplies positional input reads
  and an output stream; native code uses opaque approved tokens, never archive names
  as filesystem paths. It owns neither the app's source nor its output stream.
- A 64 KiB transfer buffer is reused. Short positive reads are filled; premature EOF,
  zero progress, callback exceptions, output length mismatch and cancellation fail.
  JNI preserves the original Java exception. Any failed extraction retires decoder
  state; synchronized operations reject reentrant close/extract from callbacks.
- Before opening each volume, the bridge rechecks version/reserved fields, set ID,
  consecutive index, predecessor/successor flags, declared extent and contiguous
  file-table offset. Atypical `coffFiles` is rejected, not silently ignored or salvaged.
  Native operations do not replace app-side reciprocal-name and source-identity checks.
- libmspack normally permits a partial file-table match while merging folders even
  when salvage is off. `READWIDE_CAB_STRICT_SET` now requires the ordered continuation
  records to match length, offset, raw name and UTF-name flag, without silently
  dropping extra records from the continued folder; no best-effort merge.
  MSZIP repair and salvage remain off, including checksum suppression.
- Folder state can be reused for forward extraction. Entries use an O(1) pointer
  index and folder IDs are built with sorting/binary lookup rather than a per-file
  linear scan of all folders. Declared unsigned lengths reach Java as `long`; the
  bridge adds no payload quota. Existing upstream CAB folder/block/2 GiB decoder
  boundaries still apply and have not been expanded by this work.
- CMake declares a separate LGPL-2.1-only `libreadwide-cab.so`, with default filesystem
  I/O compiled out, 64-bit `off_t` on both Android ABIs and the public ABI self-test.
  Shrinker keeps and the full APK license/relinking notice are included. This is
  source wiring, not evidence of a successful build or measured APK size.

## App ownership and routes now in source

- `CabArchiveInputs` owns one read-only file handle and a reusable 64 KiB read-ahead
  buffer across approved parts. Recheck metadata at session boundaries/cache refills,
  opened lengths and cancellation; cleanup still runs after cancellation. This is
  metadata-based change detection, not cryptographic identity or a mutation lock.
- `CabArchiveReader` maps strict flagged UTF-8 or existing best-effort legacy names
  to one first-normalized entry identity for listing, selection, bulk and viewing.
  Unsafe paths, file/directory collisions and canonical output aliases fail. Bulk
  preflights all destinations; no output may overwrite any source cabinet.
- Writes use the existing shared byte accounting/free-space reserve and progress
  stream, plus exact declared-length checks. Selected output commits only after
  decoder/source close succeeds. Bulk uses the app's existing staging/rollback
  route; the internal per-file helper is not a separate whole-archive transaction.
- `CabForwardReader` retains native folder state. Unrequested headers need no spool;
  requested entries complete decoding and checks of supplied block checksums before spool
  bytes are exposed. A zero CAB block checksum means no checksum was supplied, not
  authenticated content. Spools are removed on advance/failure/close. Discard drains
  can decode/check without writing payloads to disk.
- All parts enter `ArchiveSourceSnapshot`'s preview namespace/source protection.
  Queue grouping resolves to the first linked cabinet. Explicit links take priority
  over numeric-split concatenation even for a `*.cab.001` name. Normal single-CAB
  and LHA behavior is preserved; single CAB can use the new decoder if libarchive
  is unavailable. Atypical file tables and upstream codec boundaries remain explicit.

## Remaining validation and coverage

The cleanup follow-through retains a physical input handle when its close fails,
including during a volume switch. Session reads remain permanently closed, while
failed decoder/input cleanup can retry without repeating successful decoder close.
Four additional host methods use injected cleanup failures, not native decoding;
they are uncompiled/unrun. This does not expand codec or volume-layout support.

Both ABI builds/public ABI self-test, app/native regression execution and real
producer stored/MSZIP/LZX/Quantum multipart coverage remain pending. Expand fixtures
for compressed split blocks/folders, locale-specific names and reserved headers;
check checksum failures, cancellation, actual low-disk/output failures, rollback and
viewer ordering. Source wiring and synthetic failure cases are not measured device
coverage. These changes have source review only, without build or test results. Do not enable
salvage, repair or checksum suppression to make incomplete sets appear successful.
Upstream folder/block/2 GiB decoder boundaries remain. The separate
[LHA changes](LHA_LH4_INTEGRATION_1_0_20.md) add LH4 and the documented Amiga byte-split
route using guarded merging and native decoding; they do not add general LHA
multi-disc semantics or change this CAB implementation.

## Regression sources

Nineteen `CabVolumeResolverTest` methods are uncompiled/unrun. They cover metadata
discovery, lossless name matching, malformed/reserved headers, missing/mismatched/
cyclic sets, cancellation, snapshots and immutable results. The symlink case is
conditional on host permissions. One method consumes the attributed upstream
[five-part stored fixture](../app/src/test/resources/archives/cab-multipart/README.md)
from every selected part, but does not decode payloads. Synthetic header cases do
not stand in for MSZIP/LZX/Quantum or producer/device validation.

Nineteen `CabNativeDecoderInstrumentedTest` methods are also uncompiled/unrun. They
add exact stored-set bytes and the pinned upstream MSZIP/LZX/Quantum digest oracles,
backward/forward selection, strict continuation identity, partial reads, source/output
exception identity, cancellation, corruption, reentrant close, unsigned metadata and
closed-session handling. The compressed fixture is a sixth attributed resource.
Mock output failures exercise the callback contract, not real device low-disk.

Twenty-two `CabArchiveReaderTest` host methods exercise approved reads, metadata
changes, cache/queue identity, duplicate names, invalid paths/UTF-8, output preflight,
source protection, declared sizes, shared-budget/close/decode failure rollback,
cancellation and forward spool ownership. Payload decoding is fake in these tests.
Seven `CabArchiveRoutingInstrumentedTest` methods use the real decoder and stored
fixture through public app listing/selected/bulk/viewer paths, including selecting
any part, numeric names, missing/changed parts and source-output protection. Both
sets are uncompiled/unrun; test assets are excluded from the production APK.
