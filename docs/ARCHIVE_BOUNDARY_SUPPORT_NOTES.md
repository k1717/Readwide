# Archive support notes — Readwide 1.0.21

Use the [current source status](CURRENT_SOURCE_STATUS_1_0_21.md#archives) and
[1.0.21 archive changes](DEV_CHANGES_1_0_21.md#archive-compatibility) together with
the [inherited 1.0.20 format boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).
The latter is a historical baseline, not a replacement for newer route/filter
changes. Per-format reports describe their own source/fixture scope, not complete
producer compatibility or a fresh execution of the current source.

## Current implementation references

- [Code map](CODE_MAP.md) for routing, decoder ownership and regression-source locations.
- [EGG format notes](EGG_FORMAT_NOTES.md) and [ALZ format notes](ALZ_FORMAT_NOTES.md).
- [Third-party notices](../THIRD_PARTY_NOTICES.md) and [FOSS status](FOSS_STATUS.md) for component boundaries.

## Inherited implementation and validation records

- [Routing, volume names and selected identities](ARCHIVE_ROUTING_1_0_20.md).
- [Extraction, cleanup and stream ownership](ARCHIVE_EXTRACTION_1_0_20.md).
- [Decoder additions and provenance](ARCHIVE_CODEC_ADDITIONS_1_0_20.md).
- [LZ frame and RAR stream corrections](ARCHIVE_STREAM_REPAIR_1_0_20.md).
- [Selected RAR fallback validation](RAR_SELECTED_FALLBACK_VALIDATION_1_0_20.md).
- [ALZ selector validation](ALZ_SELECTOR_VALIDATION_FIX.md) and
  [ALZ archive validation](ALZ_ARCHIVE_BOUNDARY_REVIEW.md).

## Release boundary

Earlier build/host-test results apply only to their recorded checkpoints.
Recent 1.0.21 transition/VM/encrypted-split and native regression sources are not
compiled/executed interoperability evidence. In particular, the scoped pending
RAR raw-prefix carry profile is synthetic and producer-unverified; completed
filter-result splitting across files is not established.

See [release readiness](RELEASE_READINESS_1_0_21.md) for outstanding checks. Use a
complete source package created by the current packaging policy; old incremental
patch names are historical records, not current installation instructions.
