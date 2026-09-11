# Stream extraction safety — Readwide 1.0.2

This note documents a safety boundary for archive formats whose decoded size may
be unknown during listing.

## Covered scope

The original 1.0.2 guard used a 32 GiB per-stream ceiling, followed by a
128 GiB operation-wide ceiling in 1.0.17. In 1.0.18 the fixed ceiling is removed.
The operation budget is based on starting usable storage minus a 64 MiB reserve.
Every decoded output file shares that budget, including entries whose decoded
size is unknown. When usable storage cannot be measured, accounting is limited
only by signed-long representation and filesystem I/O failures. This covers:

- single-file compressed payloads such as `.gz`, `.bz2`, `.xz`, `.lzma`, and `.Z`
- ZIP/TAR/libarchive stream-copy paths that use the shared archive stream writer
- 7z/CB7 entry extraction through the Commons Compress `SevenZFile` loops
- native libarchive extraction, which is copied in checked blocks rather than written unchecked to a file descriptor

If cumulative materialized output crosses the effective safety limit, extraction fails with an explicit
unsupported-feature style failure instead of writing until storage is exhausted.

## Not a new format-support claim

This does not add support for new compression methods. It only prevents
unknown-size decoded streams from bypassing the extraction size and free-space guards.

## Still intentionally limited

- Unsupported codecs remain unsupported.
- Encrypted entries still require a password or fail according to the backend.
- This is not zip-bomb detection by compression ratio. It retains cumulative
  available-space accounting, not a fixed application-wide decoded-byte cap.
- The starting-space snapshot is not a reservation against other apps' writes.

## 7z solid-drain update

Earlier 7z solid members and native solid primers may still need decoding to
reach a selected entry. Discarded bytes no longer have a fixed size ceiling;
their counters check overflow and their loops retain cancellation checks.
Bytes actually written still pass through storage-budgeted output streams.
See `ARCHIVE_SIZE_POLICY_1_0_18.md` for remaining decoder-memory boundaries.
