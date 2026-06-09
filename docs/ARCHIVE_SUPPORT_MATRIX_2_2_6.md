# Archive support matrix for Readwide 1.0.0 / TextView Reader 2.2.6 base

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

## ZIP / ZIPX / CBZ

| Case | Status | Notes |
|---|---|---|
| Standard ZIP/CBZ stored/deflated entries | Supported | Zip4j remains primary. |
| ZIP encryption covered by Zip4j | Supported, limited | Password prompt path is available for covered ZipCrypto/AES cases. |
| Non-encrypted uncommon ZIP methods | Supported, limited | Commons Compress fallback is attempted when Zip4j rejects a method and the bundled codecs can read it. |
| AES plus an unsupported non-Zip4j method | Unsupported | No single bundled path combines those requirements. |
| ZIP creation | Supported, limited | Plain ZIP creation only. |

## 7z / CB7

| Case | Status | Notes |
|---|---|---|
| Standard 7z listing/extraction | Supported, limited | Java 7z path remains primary. |
| Password-protected 7z | Supported, limited | Covered by the existing password prompt path where the Java backend supports the archive. |
| Split 7z chains | Supported, limited | Recognized split-start variants are routed where covered; unusual/missing chains are not guaranteed. |
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
| Covered stored split chains | Supported, limited | Routing/validation exists for covered plain and encrypted stored chains; public wording should still be conservative. |
| Common RAR3/RAR4 compressed non-encrypted entries | Backend-dependent | Attempted through bundled libarchive-android. Do not describe as complete RAR support. |
| RAR5 stored entries | Supported, limited | Covered first-party stored-data paths exist. |
| RAR5 compressed entries | Backend-dependent | Attempted through bundled backend where possible; no first-party RAR5 compressed decoder is present. |
| Split/multi-volume RAR | Best-effort / unverified | Some routing helpers exist, but split RAR was not re-tested for this package. |
| Encrypted RAR | Best-effort / unverified | Password passing is an attempt, not a compatibility claim. Encrypted RAR was not re-tested for this package. |
| RAR3/RAR4 compressed solid, PPMd, VM-filtered, compressed split, broad SFX, unusual variants | Unsupported / backend-dependent | These remain outside public compatibility claims unless a specific file succeeds through the bundled backend or a covered first-party stored path. |
| RAR creation/compression | Unsupported | Extraction/read-only only. |

## ALZ

| Case | Status | Notes |
|---|---|---|
| Store/Deflate/BZip2 ALZ entries | Supported, limited | First-party reader with CRC verification; BZip2 depends on Commons Compress. |
| Covered ALZ ZipCrypto-style encryption | Supported, limited | Only covered ALZ encryption cases. Needs broader real fixture QA. |
| Broad legacy ALZ variants and split edge cases | Not guaranteed | Do not claim broad ALZ compatibility. |

## EGG

| Case | Status | Notes |
|---|---|---|
| Store/Deflate/BZip2/AZO/LZMA EGG entries | Supported, limited | First-party reader, xunazo-derived AZO path, CRC checks. Needs broader real fixture QA. |
| Encrypted EGG | Unsupported | Fails cleanly. |
| Split EGG | Unsupported | Fails cleanly. |
| Solid EGG | Unsupported | Fails cleanly. |

## Public wording

Use wording like this:

> ZIP remains Zip4j-primary, TAR-family remains Commons-Compress-primary, 7z remains Java-7z-primary, and compressed RAR is attempted through bundled libarchive-android. Stored RAR entries have covered first-party handling. RAR/CBR support is limited: split-volume RAR, encrypted RAR, compressed-solid RAR, broad SFX handling, PPMd/VM-filtered first-party decoding, and RAR5 compressed/solid/encrypted-header extraction are not guaranteed.

Avoid wording such as:

- complete RAR support;
- RAR3/RAR4 solid supported;
- encrypted RAR supported;
- split/multi-volume RAR supported;
- RAR5 compressed supported by first-party Java;
- libarchive handles all RAR variants.
