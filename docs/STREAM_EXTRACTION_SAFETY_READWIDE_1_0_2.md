# Stream extraction safety — Readwide 1.0.2

This note documents a safety boundary for archive formats whose decoded size may
be unknown during listing.

## Covered scope

The original 1.0.2 guard used a 32 GiB per-stream ceiling. The current implementation
uses a 128 GiB operation-wide ceiling and also caps the operation at the starting
usable storage minus a 64 MiB reserve. Every decoded output file shares that
budget, including entries whose decoded size is unknown. This covers:

- single-file compressed payloads such as `.gz`, `.bz2`, `.xz`, `.lzma`, and `.Z`
- ZIP/TAR/libarchive stream-copy paths that use the shared archive stream writer
- 7z/CB7 entry extraction through the Commons Compress `SevenZFile` loops
- 7z/CB7 single-entry solid-member drain loops used to skip earlier entries before the requested target
- native libarchive extraction, which is copied in checked blocks rather than written unchecked to a file descriptor

If cumulative materialized output crosses the effective safety limit, extraction fails with an explicit
unsupported-feature style failure instead of writing until storage is exhausted.

## Not a new format-support claim

This does not add support for new compression methods. It only prevents
unknown-size decoded streams from bypassing the extraction size and free-space guards.

## Still intentionally limited

- Unsupported codecs remain unsupported.
- Encrypted entries still require a password or fail according to the backend.
- This is not zip-bomb detection by compression ratio; it is a hard cumulative
  decoded-byte and available-space ceiling for extraction.

## 7z solid-drain update

The same stream-time guard also applies while draining earlier 7z solid members during single-entry extraction. This matters for split/encrypted/solid 7z previews because skipping to a later file can still require decoding previous member streams. Draining is not allowed to bypass the decoded-byte ceiling.
