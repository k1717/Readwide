# RAR split / multi-volume status for Readwide 1.0.2

This document is deliberately conservative. Passing single-file solid RAR3/RAR5 CBR tests does not imply split-volume support.

## Confirmed boundaries

| Case | Status | Notes |
|---|---|---|
| RAR volume name discovery | Implemented, needs real-file QA | Recognizes new-style `.part1.rar` / `.part01.rar` chains and old-style `.rar` + `.r00` / `.r01` chains. Missing gaps are detected by the resolver. |
| Stored split RAR payloads | Supported, limited | The first-party stored split path concatenates segments, verifies CRC, and deletes partial output on failure. Covered by unit tests. |
| Encrypted stored split RAR payloads | Supported, limited | RAR4/RAR5 AES stored split helpers exist for covered visible-header cases. This is still a narrow path, not broad encrypted RAR support. |
| RAR3/RAR4 compressed split payloads | Backend-dependent / limited | Existing rewrite helpers can concatenate selected visible-header RAR4 compressed split payloads and delegate to libarchive. This is not a broad first-party compressed split decoder. |
| RAR5 compressed split payloads | Unsupported after libarchive failure | The scoped first-party RAR5 decoder intentionally refuses split/multi-volume compressed payloads. They must not fall through to stored-entry extraction. |
| Solid + split compressed RAR | Unsupported / unverified | Requires a logical multi-volume packed stream plus sequential discard decode. Do not claim support yet. |

## Next implementation order

1. Keep the stored split path as the baseline.
2. Add real fixtures for stored split, missing-volume, and CRC-failure cases.
3. Add logical packed stream support before attempting compressed split.
4. Test compressed non-solid split before solid+split.
5. Treat RAR3/RAR4 and RAR5 as separate decoder families.

## Public wording

Safe wording:

> Readwide has limited RAR split handling for covered stored split chains. Compressed split and solid+split RAR files remain backend-dependent or unsupported unless a specific file succeeds through the bundled backend.

Unsafe wording:

- Full split RAR support
- RAR5 split support
- Solid split RAR support
- All CBR multi-volume archives supported
