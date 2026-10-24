# Independent ZIP PPMd-I stream — Readwide 1.0.20

Source review only; no builds, compilation, tests or native execution.

## Scope implemented in source

- ZIP method 98 now has a streaming adapter independent of libarchive's container
  reader. It uses eight pinned public-domain 7-Zip files, two with marked local
  freeze-profile changes, not the 7z
  PPMd-H model. Only two decoder C files compile into a separate `libreadwide-ppmd.so`;
  no encoder, UnRAR code, executable or build/runtime download is introduced.
- `PpmdZipInputStream` and the Apache-2.0 JNI adapter own one native model and bounded
  transfer buffers, not a whole-file allocation or decoder spool. Property order
  2–16 and model memory 1–256 MiB follow the ZIP property fields. Restart/cut-off
  modes are implemented upstream. Explicit raw-stream freeze revision profiles now
  serve the checked container resolver below. Raw `open` still rejects freeze unless
  a revision is explicitly supplied through `openFreeze`. Allocation failure is reported,
  not replaced by a smaller model.
- Declared decoded length, explicit end marker, final range state and exact packed
  exhaustion must all agree. No fake EOF zero bytes, ignored suffix or partial
  success. Input errors/cancellation abort the C decoder immediately; failed state
  cannot be reused. Reentrant reads/close are rejected and cleanup is idempotent.
- `ZipxAesArchiveReader` routes eligible plain, ZipCrypto and WinZip-AES entries
  through the stream while retaining source/entry identity, CRC, authentication,
  free-space output accounting, cancellation and destination rollback. Container
  checks remain outside the raw decoder. An unavailable supplemental library leaves
  the prior libarchive path eligible; decode/integrity failures are not ignored.
- ZIP support text in all 22 locales describes checking both supported freeze
  revisions and rejecting conflicting valid output. App version and TXT paging are unchanged. This adds
  no application-wide payload limit. APK size has not been measured.

## Evidence and remaining validation

`ZipPpmdEntryExtractor` now connects revision selection to plain, ZipCrypto and
WinZip AES selected/bulk extraction, including the plain Commons fallback. It
tries revision 2 and then revision 1 sequentially: only one model is live. The first
candidate uses the existing guarded destination, not a second plaintext spool.
If it is valid, the second output is compared byte-for-byte against that file;
otherwise the second attempt truncates/replaces the failed candidate under the
same guard and byte-accounting path. No full-output heap allocation or digest-based
equality shortcut is used. Restart/cut-off entries take only one decoding pass.

Only decoder-origin `DataException` or decoded size/CRC mismatch can reject a
candidate. Actual source faults are independently tracked; cancellation, wrapped/
suppressed faults, cleanup, output I/O and allocation failures abort. Each candidate
finishes authentication before a retry, even after decoder rejection. Source
snapshots and the complete property word are checked between attempts; volume-set
membership is checked before outer commit. Plain/ZipCrypto/AE-1 use decoded CRC;
AE-2 has no decoded CRC. A single valid result is accepted, two valid identical
results are accepted, and two valid differing results are rejected. Authentication
cannot select a revision: [WinZip AES](https://www.winzip.com/en/support/aes-encryption/)
authenticates compressed ciphertext, not the decoded interpretation. Covered
failures are terminal across public ZIP backend fallback. The existing 64 MiB
free-space reserve and rollback remain; accepted progress is counted only once.

Fifteen host policy methods and four app Android methods are added, uncompiled/
unrun. The host seam uses injected decoded bytes and is not a codec oracle. Android
cases use small upstream payloads with a freeze-property mutation inside plain,
synthetic ZipCrypto and AE-1/AE-2 all-strength containers; they cover public
selected/bulk routes and CRC/tail/password/MAC rollback, not actual model exhaustion.

`openFreeze` requires an explicit revision enum and rejects non-freeze properties.
The core/JNI share the freeze compile definition. Revision 1 omits only the
upstream-documented `Refresh` high-sum correction; revision 2 retains it. Ordinary
restart/cut-off correction and upstream range/allocator safety fixes remain.
Native decoder grammar/size/end failures use `DataException`; existing pending
source, cancellation, allocation and cleanup exceptions are not relabeled. JNI's
new exception class has an explicit shrinker keep rule. Callers must still track
source/cleanup failures independently before considering any decoder retry.

Five added Android methods cover explicit/default profile boundaries, small
pre-exhaustion payloads under both revisions, invalid end/size/range, source errors,
cancellation and cleanup. They are uncompiled/unrun and do not validate freeze
transitions or the revision-divergent state. No producer decoder was executed.
A standalone [C regression source](../third_party/ppmd8/tests/freeze_refresh_test.c)
checks `Refresh` directly around the high-sum boundary under both flags and all
restoration states. It is outside the app, uncompiled/unrun and not a producer
stream oracle.
Container-side selection is now connected as described above. Theoretical support
is for the two explicit profiles, not every historical PPMd producer variation.
Native execution at genuine freeze transitions/high-sum divergence remains pending.

The cleanup follow-through validates both scalar property bytes, retries failed
source close without reopening decoding and rejects cleanup callback reentry.
Primary native-cleanup failures retain secondary source failures as suppressed
exceptions. That earlier cleanup-only change added three Android methods and one
revised close expectation, uncompiled/unrun; the later explicit-profile work above
does modify the native model/profile boundary.

The [vendor record](../third_party/ppmd8/README.md) retains the pinned commit and
original and current source hashes. [Two upstream ZIP fixtures](../app/src/test/resources/archives/zip-ppmdi/PPMD_FIXTURES.md)
retain decoded-archive hashes, metadata and upstream expected CRC32 values, without
being decoded here. Their producer/version is not newly established.

The initial fourteen `PpmdZipInputStreamInstrumentedTest` methods cover the three member oracles,
small/short reads, malformed properties/range headers, truncation/trailing data,
declared sizes, checked/unchecked source errors, zero progress, cancellation,
reentrancy and close ownership. A synthetic cut-off property case does not exhaust
the model and is not a real restoration fixture. Five app Android methods cover
public selected/bulk paths, supplemental routing, CRC/trailing-data rollback and
synthetic AES AE-1/AE-2 containers at all three key strengths, wrong passwords and
bad MACs. One existing host fallback expectation is renamed/guarded to describe
an unavailable supplemental decoder. All methods are uncompiled/unrun.

Required later checks: both Android ABIs, shrinker/JNI callbacks, native sanitizers,
real model-exhaustion/restart/cut-off producers, memory pressure and actual low-disk,
ZipCrypto, split/ZIP64 combinations, encrypted producer interoperability and device
viewer/extraction behavior. The path is independent integration of upstream model
code, not a separately implemented first-party PPMd algorithm or universal ZIP claim.

Primary references: [ZIP PPMd framing](https://github.com/ip7z/7zip/blob/9128b80e3a471108678e2b3ec3c985861c8d7b0f/CPP/7zip/Compress/PpmdZip.cpp),
[Ppmd8 API and freeze boundary](https://github.com/ip7z/7zip/blob/9128b80e3a471108678e2b3ec3c985861c8d7b0f/C/Ppmd8.h),
[upstream ZIP test oracles](https://github.com/libarchive/libarchive/blob/27cbc7827172698143e440801fc0ba39ccb4f1f5/libarchive/test/test_read_format_zip.c).
