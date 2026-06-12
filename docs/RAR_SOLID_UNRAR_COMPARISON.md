# RAR solid handling comparison notes

This note is an implementation guide for Readwide's first-party RAR work. It is not a claim of full support.

## RAR3/RAR4 PPMd solid

Current target fixture:

- `testfile.png`: reset PPMd block, creates a new model.
- `testfile.jpg`: continuation PPMd block, reuses the model after the PNG entry.

The important UnRAR-shaped state variables to mirror conceptually are:

- `MinContext` / `MaxContext`
- decoded `FoundState`
- `FoundState.Successor`
- `OrderFall`
- suffix decode with masked symbols
- `RestartModelRare()` root initialization with 256 states and `SummFreq = 257`

Do not port UnRAR code. Keep this as behavioural comparison only.

## RAR5 solid

RAR5 solid is structurally different from the RAR3 PPMd target:

- The RAR5 file header compression-info field carries the solid flag.
- If the solid flag is set, decompression continues with the dictionary from preceding files.
- UnRAR's RAR5 unpack path enters `Unpack5(Solid)` and calls common unpack initialization with the solid flag.
- Non-solid entries reset distances, block tables, unpack pointers, and window state; solid entries preserve dictionary/window state.
- RAR5 filter state is still reset per file; it is not shared across solid files.

Implementation consequence for Readwide:

1. RAR5 solid image preview must be archive-sequential, not lazy single-entry extraction.
2. Target entry extraction must decode all preceding entries into discard output to preserve dictionary state.
3. Window size must be locked across a solid block. A later solid file requiring a larger dictionary is suspicious and must not silently reuse invalid state.
4. RAR5 block tables may be retained across solid files; do not assume each entry starts with fresh tables.
5. This path remains separate from RAR3 PPMd. Fixing the current RAR3 PPMd fixture does not automatically implement RAR5 solid.

## Archive decoder note 210 -  root-escape and cursor gate observation

UnRAR's PPMd `DecodeChar()` does not treat a fully masked 256-symbol root escape as a normal order-0 continuation. If the root alphabet has been fully masked, dropping into a separate order-0 bootstrap stream is only a diagnostic artefact and can create a misleading longer byte stream.

Readwide archive decoder note 210 therefore keeps two separate diagnostic ideas:

- `rar-primary-root-cursor-orderfall`: follows `FoundState.Successor` only after the cursor's OrderFall-like counter reaches zero.
- `rar-primary-root-cursor-loop-terminal`: treats a root escape that masks all 256 root symbols as a terminal unsupported boundary instead of falling through to order-0.

The current target fixture still matches only the 8-byte PNG signature. The new terminal variant makes the next boundary more honest: the apparent post-signature continuation was not a valid RAR PPMd-I decode path.


## Archive decoder note 211 -  pending successor and libarchive comparison observation

UnRAR's PPMd state successor is not always equivalent to an already materialized higher-order
context. In the real decoder, a decoded state's successor can first behave like a text pointer
(`pText`) and only later become a context through CreateSuccessors-style expansion. Readwide archive decoder note 211
therefore separates two diagnostic successor states:

- context successor: a pointer to a registered context node that can be traversed immediately.
- pending text successor: a decoded-symbol successor marker that must be materialized before it can
  be used as a context traversal candidate.

This does not complete RAR3 PPMd. It prevents the diagnostic model from pretending that every
FoundState.Successor is already an order-1/order-2 context.

Libarchive's RAR3 reader is useful for comparison but is not a drop-in answer for this fixture. Its
RAR3 state keeps LZ window and PPMd fields in one reader object (`ppmd_valid`, `ppmd_eod`,
`is_ppmd_block`, `ppmd_escape`, `CPpmd7 ppmd7_context`, and a range decoder), so state naturally
survives sequential reads. For solid previews, this reinforces Readwide's chosen architecture:
sequentially decode preceding entries into discard output instead of doing isolated lazy extraction.
Libarchive's RAR5 path similarly keeps an unpack state and reinitializes it only when the archive is
not solid or when no window exists. The important architectural match with UnRAR is therefore the
same: solid RAR needs a persistent decompression state across file entries, while per-entry filters
and output sinks can still be reset.

## Archive decoder note 212 -  CreateSuccessors skeleton and FOSS boundary

Readwide archive decoder note 212 keeps the FOSS boundary explicit:

- UnRAR remains a behavioural reference only. No UnRAR source code, constants, tables, or line-level
  implementation were copied into the first-party decoder.
- The new `RarPpmdCreateSuccessors` class is a Readwide-owned diagnostic skeleton. It models the
  distinction between a pending text successor and a materialized context successor, but it does not
  claim to be the complete UnRAR `CreateSuccessors()` implementation.
- The new `rar-primary-root-create-successors` variant is probe-only. It can seed a materialized
  pending context with a minimal frequency-1 state so we can compare it against the older empty
  materialization path.

Current expected outcome remains limited: if this changes the fixture trace, it is evidence about
which successor materialization rule matters. It is not evidence of full RAR3/RAR4 PPMd support until
`testfile.png` and `testfile.jpg` both decode to valid image bytes and pass CRC/image validation.

## Archive decoder note 213 -  CreateSuccessors seed-policy comparison

archive decoder note 212 showed that blindly seeding a newly materialized successor context with the owner state
symbol makes the target fixture worse. archive decoder note 213 keeps that variant for comparison but splits the
CreateSuccessors diagnostic into explicit seed policies:

- empty materialization: materialize the pending successor without adding a state.
- owner-symbol seed: seed with the state that owned the pending successor. This is the archive decoder note 212
  candidate and remains a negative control for this fixture.
- pending-symbol seed: seed with the pending text successor symbol.
- history-newest seed: seed with the current symbol history's newest byte.

This is still a first-party diagnostic scaffold, not a port of UnRAR CreateSuccessors. The purpose is
to prevent a single misleading seeded candidate from being treated as the model rule. The target
fixture currently favours the empty/pending-symbol materialization baseline over owner/history
seeding, while still matching only the first 8 bytes of the PNG signature. The next useful step is to
separate state creation from successor traversal timing and to add excluded-symbol/mask accounting to
the materialized context trace.
