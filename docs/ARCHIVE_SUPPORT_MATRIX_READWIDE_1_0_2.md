# Archive support matrix for Readwide 1.0.2

> **Note (current as of 1.0.11):** This file is the original 1.0.2 wording snapshot and is kept for history. Since 1.0.2 the archive support has grown: Zstandard and LZ4 were added to the TAR family and as single-compressor streams (`.tar.zst`/`.tzst`/`.tar.lz4`/`.zst`/`.lz4`), ALZ and EGG gained split-volume and ZipCrypto-encrypted-entry support, and the RAR/7z encryption boundaries were re-audited against real archives. For the current recognized-extension list see `README.md`; for the current RAR/7z encryption boundaries (including the finding that RAR5 header-encryption is unsupported by every bundled backend and that RAR5 stored/compressed AES is first-party-verified) see `docs/RAR_7Z_SPLIT_ENCRYPTION_REVALIDATION_READWIDE_1_0_11.md`. The labels and general structure below still describe the model accurately.

- Stream extraction safety: unknown-size decoded streams, including single-file compressed payloads and 7z entry extraction loops, now enforce the existing extraction safety limit while bytes are written.

This matrix is the public wording source for archive support claims. It separates recognized formats, implemented paths, backend-dependent attempts, and unsupported boundaries.

## Support labels

| Label | Meaning |
|---|---|
| Supported | Implemented in the default build for the stated scope. |
| Supported, limited | Implemented for common/covered cases, but not a complete format claim. |
| Backend-dependent | Attempted through a bundled library backend; success depends on that backend and the file variant. |
| Best-effort / unverified | Code paths or routing helpers exist, but this package does not claim release-tested compatibility. |
| Unsupported | Should fail cleanly or fall back to external/open-with behavior where applicable. |

## Recognized archive/comic types

| Family | Extensions |
|---|---|
| ZIP / CBZ / ZIPX | `.zip`, `.cbz`, `.zipx` |
| 7z / CB7 | `.7z`, `.cb7`, split-style `.7z.001` / `.cb7.001` where recognized |
| TAR family | `.tar`, `.tar.gz`, `.tgz`, `.tar.bz2`, `.tbz2`, `.tar.xz`, `.txz`, `.tar.lzma`, `.tlzma`, `.tar.Z`, `.taz` |
| Single compressor streams | `.gz`, `.bz2`, `.xz`, `.lzma`, `.Z` |
| RAR / CBR | `.rar`, `.cbr`, recognized RAR signatures in selected files |
| ALZ / EGG | `.alz`, `.egg` |

## Filename encoding notes

Readwide 1.0.2 adds best-effort automatic decoding for legacy archive entry names in the paths where raw name bytes are available. ZIP raw central-directory names, first-party ALZ names, and first-party EGG names can fall back from UTF-8 to common legacy code pages including MS949/CP949, GB18030/GBK, Big5, Shift_JIS, Windows-1252, Windows-1250, Windows-1257, Windows-1251, KOI8-R, IBM866, Windows-1253, Windows-1254, Windows-1255, Windows-1256, Windows-874, Windows-1258, and IBM437. EGG locale code-page hints are honored when present. 7z and libarchive-backed RAR paths generally receive already-decoded names from their backend, so they are not re-decoded here.

## ZIP / ZIPX / CBZ

| Case | Status | Notes |
|---|---|---|
| Standard ZIP/CBZ stored/deflated entries | Supported | Zip4j remains primary. |
| ZIP encryption covered by Zip4j | Supported, limited | Password prompt path is available for covered ZipCrypto/AES cases. Fixture-verified: listing without a password raises the prompt by design; no/wrong/correct password classify as PASSWORD_REQUIRED / BAD_PASSWORD / success with byte-exact output. |
| Non-encrypted uncommon ZIP methods | Supported, limited | Commons Compress fallback is attempted when Zip4j rejects a method and the bundled codecs can read it. |
| Generic raw `.001/.002/...` numeric split chain | Supported, limited | Contiguous raw split chains are combined before normal archive handling. Gapped chains such as `.001` + `.003` with `.002` missing are corrupt/incomplete and are not silently combined. |
| ZIP spanned `.z01/.z02 + .zip` chain | Backend-dependent / not broadly claimed | This is distinct from raw `.001` splits and is not claimed as a covered generic path. |
| AES plus an unsupported non-Zip4j method | Unsupported | No single bundled path combines those requirements. |
| ZIP creation | Supported, limited | Plain ZIP creation only. |

## 7z / CB7

| Case | Status | Notes |
|---|---|---|
| Standard 7z listing/extraction | Supported, limited | Java 7z path remains primary. |
| Password-protected 7z | Supported, limited | Password is forwarded to Apache Commons Compress. Visible-header data-encrypted archives get a bounded first-stream read probe for password preflight. Only encryption variants covered by that backend are claimed. |
| Split 7z chains | Supported, limited | Standard raw split chains named `.7z.001`, `.7z.002`, ... or `.cb7.001`, `.cb7.002`, ... are resolved to the first part and opened through a concatenated read-only channel. Missing/gapped chains are corrupt/incomplete, not password-required. |
| 7z split + password | Supported, limited | The same password-forwarding and split-channel paths are combined where Commons Compress supports the archive. Missing/gapped chains remain corrupt/incomplete; wrong-password AES corruptions are promoted to `BAD_PASSWORD` only when password context and AES context are present. |
| 7z creation | Unsupported | Read/extract only. |

## TAR family and single compressor streams

| Case | Status | Notes |
|---|---|---|
| TAR and common TAR+compressor combinations | Supported | Commons Compress primary. |
| Single `.gz`, `.bz2`, `.xz`, `.lzma`, `.Z` streams | Supported, limited | Extracted as single decompressed files where the stream format is covered. |
| Unsafe paths / traversal entries | Unsupported | Should be rejected or sanitized before write. |

## RAR / CBR

| Case | Status | Notes |
|---|---|---|
| RAR metadata listing and safe path handling | Supported, limited | First-party parser handles metadata/safety boundaries used by the app. |
| RAR3/RAR4 stored method-0/0x30 entries | Supported, limited | First-party Java path, with CRC and partial-output cleanup where covered. |
| RAR5 v5.0 compressed entries (incl. solid runs), dict <= 64 MB | Supported, limited | First-party decoder with solid window carryover, predecessor priming, DELTA/x86/ARM filters, and CRC/password-check safeguards. Single-volume unencrypted cases remain CRC32-verified. Covered visible-header AES cases use the RAR5 password-check value where present. Non-5.0 (RAR7-era) algorithm versions still fail cleanly as unsupported. |
| RAR3/RAR4 PPMd entries (incl. PPMd solid sets), unencrypted single-volume | Supported, limited | First-party PPMd variant H engine with solid model/window carryover, predecessor priming, mandatory end-of-data marker validation, and per-entry CRC32 verification. Runs after the libarchive-primary attempt fails or is unavailable. Classic-LZ solid members, VM filters, and mid-entry table switches still fail cleanly as unsupported. |
| Covered stored split chains | Supported, limited | Routing/validation exists for covered plain and encrypted stored chains; public wording should still be conservative. |
| Common RAR3/RAR4 compressed non-encrypted entries | Backend-dependent | Attempted through bundled libarchive-android. Do not describe as complete RAR support. |
| RAR5 stored entries | Supported, limited | Covered first-party stored-data paths exist. |
| Covered RAR5 AES visible-header compressed split chains | Supported, limited | Fixture-verified against real encrypted multi-volume `.partN.rar` chains using password `password`: listing, opening later volumes, single-entry extraction, and whole-archive extraction pass for covered v5.0 compressed/solid entries. Continuation encryption flags may differ, but AES material must match across parts of the same file. |
| RAR5 compressed entries outside the eligible v5.0 covered subset | Backend-dependent / unsupported | Non-5.0 algorithm versions, damaged/gapped split chains, unsupported filters, unusual encryption/header-encryption cases, and variants outside the tested visible-header AES path remain unsupported or backend-dependent. |
| Stored split RAR chains | Supported, limited | Covered first-party path concatenates stored split payload segments and verifies CRC. New-style `.partN.rar` and old-style `.rar` + `.rNN` name discovery helpers exist; real fixture QA is still required for broad public claims. |
| RAR3/RAR4 compressed split chains | Backend-dependent / limited | Visible-header compressed split helpers can rewrite selected chains for libarchive, but this is not broad first-party compressed split decoding. |
| Other RAR5 compressed split chains | Backend-dependent / unsupported | Covered visible-header AES v5.0 chains have a first-party path; other compressed split variants must not fall through to stored-entry extraction. |
| Encrypted RAR | Supported, limited / best-effort by variant | Covered RAR5 AES visible-header cases are tested. RAR3/RAR4 encrypted stored paths have scoped support. Other encrypted/header-encrypted RAR variants remain backend-dependent or unsupported. |
| RAR3/RAR4 classic-LZ compressed solid, VM-filtered, compressed split, broad SFX, unusual variants | Unsupported / backend-dependent | These remain outside public compatibility claims unless a specific file succeeds through the bundled backend or a covered first-party path. |
| RAR creation/compression | Unsupported | Extraction/read-only only. |

## ALZ

| Case | Status | Notes |
|---|---|---|
| Store/Deflate/BZip2 ALZ entries | Supported, limited | First-party reader streams supported payloads to the output file with CRC verification; BZip2 depends on Commons Compress. |
| Covered ALZ ZipCrypto-style encryption | Supported, limited | Only covered ALZ encryption cases. Needs broader real fixture QA. |
| Broad legacy ALZ variants and split edge cases | Not guaranteed | Do not claim broad ALZ compatibility. |

## EGG

| Case | Status | Notes |
|---|---|---|
| Store/Deflate/BZip2/AZO/LZMA EGG entries | Supported, limited | First-party reader streams Store/Deflate/BZip2/LZMA blocks with per-block CRC checks; AZO remains block-buffered through the xunazo-derived decoder. Needs broader real fixture QA. |
| Encrypted EGG | Unsupported | Fails cleanly. |
| Split EGG | Unsupported | Fails cleanly. |
| Solid EGG | Unsupported | Fails cleanly. |

## Public wording

Use wording like this:

> ZIP remains Zip4j-primary, TAR-family remains Commons-Compress-primary, 7z remains Java-7z-primary, and compressed RAR is attempted through bundled libarchive-android. Stored RAR entries, scoped RAR3/RAR4 PPMd entries, covered RAR5 v5.0 compressed entries, and fixture-tested RAR5 AES visible-header multi-volume chains have first-party handling with CRC/password-check safeguards. RAR/CBR support is otherwise limited: broad compressed split RAR, broad encrypted RAR, classic-LZ compressed-solid RAR3/RAR4, broad SFX handling, VM-filtered decoding, RAR7-era algorithm versions, and encrypted-header extraction are not guaranteed. Covered stored split chains also have a narrow CRC-verified path, but complete split-volume RAR support is not claimed.

Avoid wording such as:

- complete RAR support;
- RAR3/RAR4 solid supported;
- complete encrypted RAR support;
- complete split/multi-volume RAR support;
- RAR5 compressed supported by first-party Java;
- libarchive handles all RAR variants.


## Archive preview cache and failure UI

Archive image preview uses temporary app-private cache files so the normal image/PDF/TXT viewers can open archive entries through file paths. Readwide 1.0.2 separates ordinary preview cache from password/sensitive archive preview cache. The sensitive cache has smaller size/count limits and a shorter age limit. These files are disposable generated cache data, not user documents.

For RAR/CBR single-image extraction, libarchive-backed reads now sequentially consume earlier regular entries into temporary throwaway files before extracting a later solid RAR3/RAR4 member. This improves solid comic archives that require previous members to be read to prime the decoder dictionary, while broad solid RAR support remains backend-dependent rather than a guaranteed first-party claim.

Archive failures are surfaced with family-specific support-boundary messages where possible. The UI distinguishes password required, bad password or unsupported encryption, unsupported feature/backend boundary, and corrupt/incomplete/CRC failure cases instead of collapsing every archive failure into one generic message.


## Single-entry extraction failure cleanup

Failed single-entry extraction attempts remove stale or partial output files. This is a cache-safety boundary, not a new format-support claim.


## Split/encryption boundary note

7z/CB7 single-entry extraction now applies the stream safety limit while draining earlier solid members before a requested target entry. This is a safety boundary, not a broader split/encryption support claim.

## 7z password classification note

7z password preflight no longer relies only on listing entries. For visible-header encrypted 7z files, Readwide probes at most one byte from the first stream-bearing entry to detect data-encrypted payloads before extraction. If extraction later fails as generic corruption after a password was supplied, Readwide only promotes the failure to `BAD_PASSWORD` when the archive has a 7z AES context; corrupt unencrypted archives and missing/gapped split volumes stay `CORRUPT_ARCHIVE`.
