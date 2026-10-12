# Archive support notes — Readwide 1.0.20

Use the [current format boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md) for
implemented codecs, admitted combinations and exclusions. Historical per-format
reports describe their own source/fixture scope, not the whole current release.

## Implementation references

- [Routing, volume names and selected identities](ARCHIVE_ROUTING_1_0_20.md).
- [Extraction, cleanup and stream ownership](ARCHIVE_EXTRACTION_1_0_20.md).
- [Decoder additions and provenance](ARCHIVE_CODEC_ADDITIONS_1_0_20.md).
- [LZ frame and RAR stream corrections](ARCHIVE_STREAM_REPAIR_1_0_20.md).
- [ALZ selector validation](ALZ_SELECTOR_VALIDATION_FIX.md) and
  [ALZ archive validation](ALZ_ARCHIVE_BOUNDARY_REVIEW.md).

## Release boundary

The local 1.0.20 release build completed successfully on 2026-10-11. This does not
establish full producer/native/device interoperability. See the
[release checklist](RELEASE_READINESS_1_0_20.md) for remaining runtime checks.
Complete source packages use the current packaging policy; old incremental-patch
instructions and historical ZIP names are not installation instructions for this release.
