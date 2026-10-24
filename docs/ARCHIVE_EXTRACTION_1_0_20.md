# Archive extraction and stream ownership — Readwide 1.0.20

This note groups decoder routing, cleanup and failure behavior by subsystem.
The local release build completed successfully;
focused host records and unexecuted regression sources remain separate from
native/device interoperability validation.

## Supplemental 7z input and forward reading

- Headers and payloads use one validated logical split-volume input for listing, probing, selected and bulk reads. AES, encoded headers and solid slices retain CRC/password/source checks.
- The Java libarchive bridge delegates admitted Swap2/Swap4/LZ4 payload or encoded-metadata paths to the first-party forward reader when the native container decoder cannot serve them. It does not replace working ordinary native routes or invent mid-stream recovery.
- Skipped solid prefixes are decoded and checked without unnecessary spool writes. Complete folder/packed/substream validation still precedes publication; the fundamental solid-history dependency remains.
- Owned temporary directories, decoders and packed streams close on completion, early close and failure. Delegate close and directory cleanup have independent completion states.
- A failed close does not reopen reads. Later close attempts retry only retained unfinished resources; successful cleanup remains idempotent.
- Decoder-construction/read failure stays primary even if cleanup throws checked or unchecked exceptions. Distinct later failures are suppressed without self-suppression.

## RAR forward-reader ownership

- Checked RAR3, logical-solid RAR3 and RAR5 viewers own validated spools and decoded history. Spool deletion is attempted even when input close throws; failed deletion paths remain available for retry.
- Read/drain failures retire the session rather than allowing partially advanced decoder state to continue. Preserve the initiating failure and any distinct cleanup failures.
- Source volume and packed-input checks are separate from decoded size/CRC/authentication. Plain, AES, split and solid paths must retain their own admission rules.
- Dictionary, filter scheduling and logical solid-stream details are recorded in [development notes](DEV_CHANGES_1_0_20.md) and [format boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

## ZIP/ZIPX selected and bulk output

- Plain XZ uses the existing supplemental decoder path. The earlier hypothesis that the public ZIP probe universally blocked extended methods was disproved by uncached public-route tests; no such blanket blocker is claimed.
- Displayed canonical identities take priority over compatibility aliases across supplemental and Commons selected routes. A damaged or unsupported canonical member must fail rather than return another file's alias.
- Supported split legacy codecs use the complete-volume source, not an accidental single-file fallback.
- Metadata-only recovery may bypass an unrelated damaged local header for a healthy selected member, but never bypasses the chosen member's bounds, integrity or authentication checks.
- Index construction, leases, failed-channel cleanup and source stamps retain ownership across cache warmup and release. See [routing and identity](ARCHIVE_ROUTING_1_0_20.md).

## ALZ and shared stream validation

- ALZ BZip2 selector indexes are validated against the active Huffman-group count before narrowing to bytes; empty selector lists fail before payload decoding.
- Active table/block bounds, code lengths, run counts and decoded block lengths are distinct from reusable allocation capacity. Malformed input cannot become valid through integer narrowing or buffer reuse.
- Raw Deflate source reads validate counts before inflater input, retain sticky failure state and honor cancellation. Flavor probing preserves source/corruption/cleanup failures instead of treating every error as an alternate codec.
- Detailed independent source provenance and historical host checks remain in [ALZ decoder validation](ALZ_SELECTOR_VALIDATION_FIX.md) and [ALZ archive validation](ALZ_ARCHIVE_BOUNDARY_REVIEW.md).

## Shared output and cancellation boundaries

- Reject unsafe paths, linked output targets and explicit/implied file-directory collisions before publication. Replacement must not cover a source archive volume or its ancestors.
- Use no-follow traversal for cache/replacement cleanup; retain recoverable backup paths when rollback cannot finish. Free-space accounting remains enabled.
- Final size, CRC, authentication, source identity and successful close are prerequisites for committing guarded output. Selected rollback and bulk staging are not atomic transactions against concurrent external writers.
- Cancellation is preserved through discovery, worker queues, fallback selection, extraction, post-read checks and completion. Cleanup does not erase the interrupt or substitute generic retry.
- Abandoned queue submissions release their credentials/claims; paused workers retain their own controls. A late callback cannot complete another operation.
- Viewer results belong to the current source/generation, and progress belongs to accepted output, not merely requested work.

## Validation boundary

Earlier focused host tests reproduced and checked split public routes, injected
close/delete failures, canonical-name collisions, encryption-probe offsets,
sparse TAR metadata and RAR/CBR naming. Native-availability substitutes and synthetic
fixtures are not JNI execution or real-producer cross-format evidence.

No build, test or benchmark was rerun during this documentation reorganization.
Do not aggregate repeated neighbor runs into an invented total or treat later
test additions as executed. The [release checklist](RELEASE_READINESS_1_0_20.md)
contains the current runtime/integrity checks; licensing stays in the
[component report](LICENSE_REPORT_READWIDE_1_0_20.md).
