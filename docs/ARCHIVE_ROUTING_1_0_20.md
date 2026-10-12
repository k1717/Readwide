# Archive routing and identity — Readwide 1.0.20

This note describes public listing, selected-entry and bulk routing by format.
It records implementation and earlier focused host evidence, not a fresh test run
or complete producer/device interoperability result.

## ZIP split volumes and metadata

- Plain Shrink, Implode, Deflate64 and BZip2 use the complete-volume supplemental source for selected and bulk reads, retaining encryption/capability checks. Store/ordinary Deflate keep their established routes.
- A matching ZIP/ZIPX/CBZ final volume disambiguates `.zNN` from old-style RAR names. Selection, queue identity and snapshots resolve the same validated final-volume owner; disk-relative input is not blindly concatenated.
- Central-directory records may span disks. Listing and selected/bulk reads share complete-volume metadata, canonical names and password checks, including supported stored/Deflate AES and traditional encryption.
- Discovery retains actual case-preserving filenames and rejects duplicate ordinals/finals and unsupported aliases. A later-volume selection must have the same source identity as the final volume.
- Cached split indexes recheck known lengths/timestamps and complete membership at the appropriate acquisition/commit boundaries. Changing or removing an earlier volume invalidates stale reads; this is metadata identity, not content hashing.

## ZIP names and large directories

- Unicode Path extra field `0x7075` requires version 1, matching raw-name CRC, valid UTF-8 and consistent records. The UTF-8 flag retains precedence; existing NFC/slash normalization and legacy-name policy remain.
- Reserve all displayed canonical names before adding local/parser compatibility aliases. An alias cannot impersonate another listed member; CRC/unsupported-codec failure of the canonical target cannot return an alias payload instead.
- ZIP64 sentinels and directories beyond the compact 4 MiB metadata fast path use Commons central-directory parsing. The fast-path buffer is not an archive-size support limit.
- Central-directory-only metadata avoids opening unrelated local headers. A healthy selected entry can remain readable past unrelated damaged local metadata, but its own local header, bounds, CRC/authentication and source checks must pass. Bulk still fails on a damaged member it reaches.
- Per-disk channel windows share the one-open-volume owner, retaining disk-relative offsets and split-signature checks. Index leases and cleanup own that handle; single-file routing is unchanged.
- Raw encryption probing uses local-header flag/method offsets 6/8 and central-header offsets 8/10. Plain XZ must not be misclassified as encrypted.

## Consistent selected identities

- ZIP and TAR listings retain the first readable normalized identity, matching selected extraction. Synthetic directories cannot take that identity from a real file.
- Ordinary and supplemental 7z listing/lookup share path normalization: `./`, backslashes and repeated separators do not create unselectable aliases. Whitespace remains significant; unsafe paths are excluded.
- Visible-header first-party RAR listing reserves the first logical file head; split continuations are not independent selectable files. Native/header-encrypted routing is not newly established by those tests.
- Duplicate metadata describes the selected first member's size/content. Bulk overwrite order remains its existing policy; these fixes do not impose first-entry-only bulk output.

## RAR and CBR volume names

- Old-style RAR discovery covers `.r00`–`.z99`, retaining decimal `.rNNN` compatibility. Colliding spellings, such as `.r100` plus `.s00`, are rejected.
- Legacy `.cbr` bases and numbered `.partN.cbr` parts share validated RAR chain resolution, source identity and output-base naming.
- Competing RAR/CBR bases or duplicate ordinals fail closed when they would identify the same split set. Standalone files retain separate identities.
- Missing/gapped volumes, invalid ordinals and ambiguous chains do not silently redirect to another payload.

## TAR and empty-container behavior

- Plain and streaming/compressed TAR listings report sparse logical size with `getRealSize()`; physical bounds and direct-copy slices retain packed `getSize()`.
- Only ordinary/old-normal/contiguous, directory and recognized sparse members are eligible. FIFO/device/unknown entries and links do not masquerade as regular files or reserve their identities.
- GNU/PAX Unicode long names retain their exact listed, selected, bulk and forward-reader identities.
- Valid structured empty ZIP and 7z archives complete bulk extraction successfully; missing selected members and malformed containers still fail.
- Empty TAR requires its complete two-record end marker. Zero-byte, partial and single-marker payloads fail without replacing an existing destination. Supported compressor wrappers retain their trailer checks.

## Cancellation and integrity

- Public listing checks pre-existing interruption before format dispatch. Warm selected/bulk reads retain cancellation and preserve existing output.
- Split AES reads preserve logical CBC continuity while packed-part checks and final decoded integrity are enforced at their own boundaries.
- First-party 7z AES finalization honors short/fragmented reads, exact declared output and complete ciphertext blocks. Failure remains terminal, not a later clean EOF.
- Selected output is guarded through close/source verification; bulk retains its documented staging/rollback boundaries. Cleanup cannot turn a failure into success.

## Focused validation record

The recorded archive routing/identity host run covered 362 added cases together
with native support disabled. The earlier ZIP handle probe on a synthetic 25-volume
archive observed 25 retained archive handles before the channel-window change and
one afterward; this is not a general process-memory or Android performance result.

Fixtures include synthetic ZIP/ZIP64/split metadata, Unicode/legacy names, stored
RAR4/RAR5 chains, AES boundaries, special 7z graphs and compressed TAR. Repeated
neighbor-suite executions are not additional distinct tests. No tests were rerun
for this documentation reorganization. Later fixes and native/producer combinations
retain the separate [release checks](RELEASE_READINESS_1_0_20.md).

See [stream ownership](ARCHIVE_EXTRACTION_1_0_20.md) and
[current format boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).
