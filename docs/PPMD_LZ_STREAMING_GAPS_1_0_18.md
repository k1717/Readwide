# PPMd/LZ and 7z gap reduction — 1.0.18 — implementation history

> Later batch twenty-two adds scoped mixed RAR dispatch, streaming LZ output,
> checked plain solid admission and additional special-7z coders. See
> [current scope](RAR_MIXED_AND_7Z_CODERS_1_0_18.md). The original stage below
> is historical. Current build success is maintainer-reported; remaining tests
> and device checks are tracked in [release readiness](RELEASE_READINESS_1_0_18.md).

Archive batch twenty. Source changes only: no build, tests, benchmark or device
verification was run. This is not complete mixed-mode RAR or complete 7z support.

Subsequent batch twenty-one adds standard split special-coder forwarding; see
[the scoped adapter and unexecuted checks](SEVENZ_SPLIT_FORWARD_1_0_18.md).

## 7z PPMd: streaming input and output

`SevenZPpmd7Decoder.decodeStream` is now selected by
`SevenZBcj2ArchiveReader.runStreamingCoder`. The old whole-packed-stream and
whole-decoded-stream arrays, and their 512 MiB admission guards in that branch,
are removed. Output length uses a signed long and model allocation is delayed
until the first nonempty read. The existing 2 KiB–256 MiB model-memory guard
and order range remain; the array convenience API still needs an array-sized
result. Encoded-header/metadata arrays retain their separate 512 MiB guards.

Range input now reports physical EOF instead of silently substituting zero
bytes; it also rejects the invalid all-ones initial range code. Reads observe
interruption, fail permanently after decode/I/O failure, close owned input on
failure/close, and release the model reference at completion/close/failure.
This is reference release, not a secure memory-erasure guarantee. Three model
scratch arrays are reused instead of allocated per symbol/escape traversal.

Plain and AES PPMd use the same lazy coder path. Existing bounded coder streams,
packed/folder/substream CRC checks, guarded file commit and verified-folder
forward spools remain in place. Known decoded size still controls codec EOF;
this does not introduce a new PPMd end-marker or range-tail certificate. The
folder owner still drains declared packed tails and validates available CRCs.
No early unverified image publication is added.

Split-volume first-party forward adaptation, substream-first publication,
unsupported coder graphs and real large/solid/encrypted runtime coverage remain
separate work. Streaming is not a measured speedup and model allocation can
still be expensive.

## RAR3/RAR4 classic-LZ: bounded packed input

`Rar3UnpackContext.openPackedPayload` reuses `RarPackedInputStream` for a single
bounded plain segment and a 64 KiB buffer. `Rar3Unpacker` peeks/restores one mode
byte and feeds the shared `RarBitInput` stream constructor for classic-LZ.
Explicit table-less continuation still takes precedence over PPMd probing.
The classic-LZ packed-array/Integer.MAX_VALUE restriction is no longer on this
path; physical segment bounds, cancellation, output size/CRC and failed-solid
state handling remain. This does not verify otherwise-unused packed tail bytes.

Classic-LZ **output still buffers** for VM filters; it is not a fully streaming
decoder. The older PPMd diagnostic adapter still uses input/output arrays. The
separate live scoped PPMd decoder already streams and is not replaced here.
Production classic-LZ admission remains non-solid, and no new mixed PPMd/LZ
block dispatcher, raw-history bridge or cross-entry filter scheduler is added.

## RAR PPMd/LZ state correctness

The older `Rar3PpmdBlockDecoder` now keeps PPMd escape-match output out of the
classic-LZ saved-distance/last-length cache. The raw window is still updated;
low-distance history is also left alone. Non-control escape codes emit the
configured escape byte instead of being rejected, matching the live PPMd path.
Legacy match truncation and its VM-filter gap remain unchanged. These corrections
prepare the control layer for mixed-mode integration; they do not claim that
integration is finished.

## Regression sources and pending verification

Seventeen methods were added, **not executed**:

- Twelve in `SevenZPpmdStreamingTest`: pinned fixture digest across read sizes,
  array-wrapper parity, lazy/partial input, long declarations, truncated headers
  and payload, invalid initial code, sticky I/O failure, cancellation, close,
  zero output and invalid-properties ownership.
- Three in `Rar3UnpackerTest`: physical offset/bounds, truncation after context
  creation and an opt-in >2 GiB declared packed payload. The latter is guarded
  by `readwide.largeArchiveTests` and may consume over 2 GiB of disk space on
  filesystems that do not make the test file sparse.
- Two in `Rar3PpmdBlockDecoderTest`: custom escape literals and isolation of
  classic-LZ match history across both PPMd escape-match forms.

All older regression methods are retained. The existing plain/AES encrypted-header
7z fixture tests now exercise the streaming production branch but have not been
rerun. Before release, run those and the new tests, then compare CRCs/digests on
large, solid, corrupted and split archives and check close/cancellation on Android.

Behavior references: the public-domain
[Ppmd7 range decoder](https://github.com/ip7z/7zip/blob/main/C/Ppmd7Dec.c)
and BSD-licensed [RAR PPMd control reader](https://github.com/nwaples/rardecode/blob/v2.2.2/decode29_ppm.go).
The existing Ppmd7 port's provenance remains unchanged. No new library, licensed
decoder import, signing change or public release/tag operation was introduced.
