# 7z / CB7 split and encryption status for Readwide 1.0.2

> **Historical baseline:** This note records the 1.0.2 implementation. Current 1.0.15 scope also includes first-party AES plus PPMd/BCJ2 paths and libarchive forward/fallback routing; use `README.md` and the 1.0.15 release/revalidation notes for current claims.

## 1.0.2 implementation

Readwide keeps the Java 7z path as the primary 7z/CB7 backend through Apache Commons Compress. The app passes the archive password to `SevenZFile` when the user provides one.

For standard 7-Zip split names, Readwide recognizes only the common raw split form:

```text
name.7z.001
name.7z.002
...
name.cb7.001
name.cb7.002
...
```

When such a part is selected, Readwide resolves back to the first part and opens the contiguous parts through a concatenated read-only seekable channel. Gapped chains are treated as corrupt/incomplete instead of being reported as password-required.

## Supported, limited

- ordinary 7z/CB7 listing and extraction covered by Apache Commons Compress;
- password forwarding for 7z variants covered by Apache Commons Compress;
- standard raw split chains with contiguous `.001`, `.002`, ... parts.

## Not a broad support claim

Do not describe this as full 7z support. These remain limited or unverified:

- unusual split naming forms outside `.7z.001` / `.cb7.001`;
- missing or damaged split volumes;
- encrypted variants not supported by Apache Commons Compress;
- unsupported 7z method chains;
- creation of 7z/CB7 archives.

## Failure classification guard

Readwide should distinguish these cases:

```text
Encrypted 7z without a password -> PASSWORD_REQUIRED
Wrong/unsupported password       -> BAD_PASSWORD or UNSUPPORTED_FEATURE depending on backend message
Missing/gapped split volume      -> CORRUPT_ARCHIVE
Unsupported method chain         -> UNSUPPORTED_FEATURE
```

This prevents a missing split volume from being surfaced as a password prompt.

## Drain-safety check

Single-entry extraction from a solid 7z/CB7 may need to drain earlier entries before the requested target. The drain path now uses the same decoded-byte safety limit as normal extraction, so split/encrypted/solid previews cannot decode an unlimited amount of skipped data before the selected entry.

Password verification messages that explicitly mention failed verification or a wrong passphrase are classified as `BAD_PASSWORD`. Missing or gapped split volumes remain `CORRUPT_ARCHIVE`.

## Fixture-QA follow-up

The 7z password boundary was rechecked against the 7z split/encryption fixture-QA findings and folded into the current tree. Two app-level fixes are now part of the Java 7z path:

- visible-header, data-encrypted 7z archives are no longer detected only by entry listing. `requiresPasswordForExtraction()` opens the archive without a password and performs a bounded one-byte read probe on the first stream-bearing entry. This catches the common case where headers are visible but payload decoding still needs a password. The probe is intentionally bounded; Readwide does not scan or drain every solid member just to classify password state up front.
- wrong passwords for AES-protected 7z archives can surface from Commons Compress as generic corrupt compressed data. When a password was supplied and the archive has a 7z AES context, Readwide promotes that corrupt failure to `BAD_PASSWORD`. Corrupt unencrypted archives, including damaged headers and missing split volumes, remain `CORRUPT_ARCHIVE`.

This is a classification and UX fix, not a claim of broad encrypted-7z compatibility. Unsupported encryption variants and unsupported method chains remain backend-dependent or unsupported.
