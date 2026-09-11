# RAR3/RAR4 classic-LZ table continuation — 1.0.18 — implementation history

> Later batch twenty-two adds scoped mixed RAR dispatch, streaming LZ output,
> checked plain solid admission and additional special-7z coders. See
> [current scope](RAR_MIXED_AND_7Z_CODERS_1_0_18.md). The original stage below
> is historical. Current build success is maintainer-reported; remaining tests
> and device checks are tracked in [release readiness](RELEASE_READINESS_1_0_18.md).

Archive batch nineteen, source-only. This changes the diagnostic solid sequence,
not the production admission gate: libarchive remains primary, and the limited
first-party classic-LZ fallback still requires a non-solid candidate.
Bulk extraction also uses this context infrastructure but resets it for each
admitted independent entry; this does not admit solid archives into production.

Later batch twenty streams classic-LZ packed input and corrects older PPMd
control state, but not mixed-mode dispatch or LZ output buffering; see
[subsequent streaming changes](PPMD_LZ_STREAMING_GAPS_1_0_18.md).

## Implemented

- `Rar3ClassicLzEngine.decodeSolid` captures a complete file-end marker immediately
  after an exact output boundary, or one encountered while decoding. Its second
  flag determines whether the next entry has a new block header. The ordinary
  non-solid size-limited overloads keep their existing behavior.
- `Rar3SolidState` carries the explicit table-reuse decision through
  `Rar3UnpackContext`. Reuse rebuilds canonical decoders from retained lengths
  without consuming a header or resetting saved matches/low-distance repetition.
- `Rar3Unpacker` uses that decision before PPMd probing. A table-less payload
  beginning with a high Huffman data bit is no longer mistaken for a PPMd header.
  A new table within the same entry still takes the existing aligned-table path.
- Decode, size, CRC, cancellation and output failures invalidate solid state.
  Diagnostic CRC mismatches still return their result but also invalidate it.
  Further entries cannot use failed state until the sequence is explicitly reset.
  CRC/output failure does not leave a usable table handoff.

## Deliberate boundaries

This is conservative continuation, not a complete RAR3 block orchestrator.
If there is no complete immediate file-end marker, no table reuse is enabled.
Legacy size-limited decoding may still return its CRC-checked output without a
marker; it does not gain permission to assume table-less continuation. A partial
tail marker at that boundary is treated as absent; errors during normal decoding
remain errors. No trailing output, filter or table is executed by the boundary
probe. Final-match overshoot retains the existing truncation behavior and does
not enable this new handoff. Trailing controls before EOF need further work.

Mixed PPMd/LZ dispatch, general solid classic-LZ admission, classic-LZ input/output
streaming, cross-entry filter scheduling and custom VM execution remain gaps.
No archive-size cap, dependency, encryption algorithm or production fallback
ordering was changed. No user-visible compatibility expansion is assured yet.

## Verification and provenance

Eleven new regression methods in `Rar3UnpackerTest` cover multi-entry table reuse,
high-bit literals, saved matches/low-distance state, in-file table reloads,
unequal/maximum-length Huffman end codes,
missing/partial markers, short output, CRC failure/reset, diagnostic CRC failure,
sink failure and cancellation. Three older synthetic solid fixtures now explicitly
encode the new-table-next-file flag instead of relying on ignored marker bits.
Existing test methods are retained. These tests have **not been executed**; no
build, benchmark or real solid/filtered/mixed-mode fixture validation was run.

The flag and reset behavior was checked against the BSD-licensed primary
[rardecode block orchestrator](https://github.com/nwaples/rardecode/blob/v2.2.2/decode29.go)
and [LZ reader](https://github.com/nwaples/rardecode/blob/v2.2.2/decode29_lz.go).
This patch implements the state handoff in the existing independent Java engine;
it does not copy or bundle another decoder or UnRAR code.

Before widening production admission, run the regressions and compare entry CRCs
on independently produced solid fixtures, including omitted/new tables, empty
entries, final matches, filters, corrupted primers and mixed PPMd/LZ blocks.
