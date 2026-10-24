# Current source status — Readwide 1.0.21

## Identity and verification

- Application ID: `com.readwide.manager`; version: `1.0.21 / 10021`. Release documents and the GitHub source package are prepared for 2026-10-24; remote upload, tag creation and APK publication are separate.
- Current verification is static source/resource/document inspection and package inventory/CRC/mode/direct-byte checks. The latest code and added regression sources have not been compiled or executed. Earlier successful builds do not validate later changes.
- This document summarizes the current support boundary. Implementation details are in [development changes](DEV_CHANGES_1_0_21.md); outstanding execution checks are in [release readiness](RELEASE_READINESS_1_0_21.md).

## Document viewers

- EPUB has independent typography settings, searchable EPUB 3/NCX Contents, marked-note text popups and section scroll percentages. Percentages apply only to ready, scrollable horizontal single-pane text; counts remain sections, not screen pages.
- Fixed/image/vertical restrictions and protected book formatting remain. Rich/dynamic notes, non-spine Contents targets, page lists and general Back history are not added.
- Document work, ZIP leases, scripts, navigation and position captures are scoped to the active load/pane. Android 8+ renderer termination closes the reader while retaining durable progress; Android 7.x lacks the callback.
- EPUB menu/system-bar colors and physical side/cutout backgrounds have source corrections without viewport changes. Markdown shrinkage, MIME-only opening, PDF zoom/sliders, image page entry and failure-card sizing are also corrected in source.
- Missing renderer callbacks, unobserved book-driven DOM movement, rotation-induced clamping and recovery of unsaved movement remain limitations. Device rendering and lifecycle behavior still require confirmation.

## TXT, images and settings

- TXT Contents/partition submission failures release pending work; bookmark/annotation admission checks loaded-source identity. Manual annotations publish after storage, and failed deferred saves allow later retry.
- The Galaxy S20 hidden-status-bar strip has a short-edge cutout correction; physical-device confirmation is pending.
- Sequential archive-image readers release failed/exhausted decoders, reuse sensitive-cleared scratch and retain verified cache/fallback paths. Verified 7z folder reads avoid repeated seeks within a member; runtime speed gains are unmeasured.
- Settings reset and backup failures attempt rollback and release operation ownership. Copy/move/physical TXT edits guard against observable source/target changes; duplicate copies retain matching original bookmark bindings.
- Sustained memory/storage failure can still prevent saving or recovery. Backup import is not a cross-file power-loss transaction, and metadata checks do not provide cross-process atomicity.

## Archives

- **RAR:** parsed-generation admission, stored solid-flagged splits and PPMd-starting internal LZ transitions are connected to existing plain/AES/split/solid paths. Failed input, output and integrity checks retire shared state.
- **RAR VM:** checked same-start chains include zero-byte intermediate stages and bounded raw-prefix carry to a planned solid successor. The complete result belongs to the receiving file. Completed-result splitting, late/unsupported overlaps and unrestricted cross-file filters are not supported; prefix carry remains a producer-unverified profile.
- **7z:** checked prefixed origins, encoded-header folder counts, typed PPMd/LZMA/LZMA2 integrity failures, BCJ2/intermediate-decoder finishing and unused-folder verification are enforced in source. Valid raw packed padding and partial-read scope remain; generic unsupported-codec fallback is not universal fail-closed metadata validation.
- **ALZ:** an independent streaming method-3 Obfuscated Deflate decoder joins existing Store/Deflate/BZip2 and ZipCrypto paths. Split, length/CRC, source and rollback checks apply; representative ALZip-producer validation remains pending.
- **EGG:** bounded SKIP records and scoped block-header extras retain legacy priority for ambiguous layouts. AES/LEA candidate ambiguity uses full ciphertext authentication. Encrypted filenames and an undefined discriminator for ambiguous/unknown block extras remain outside support.
- **EGG/ALZ acceptance:** source identity is retained from framing through reader creation, probes and output acceptance. Authenticated packed/verified decoded EGG spools are checked before reuse; failures restore pending output, not all previously verified files.
- **Other formats:** CRC-bearing level-0 stored PM0 and `.pma` recognition are added; PM1/PM2 and general PMarc multi-disc restoration are not. Compressed TAR observes cancellation.
- Bounded buffering, ALZ/RAR scratch/table reuse and removal of unused EGG CRC work retain existing codec/cipher validation. Standard third-party decoders remain in use; no general first-party replacement or measured throughput claim is made.
- Cancel/Pause checks cover supported decoder preparation, refills, VM work, metadata and acceptance boundaries. Individual native/codec/KDF calls, reads and allocations may delay cancellation.
- Same-metadata edits/replacements, newly added volumes and final-check races are not eliminated. Synthetic/injected regression sources and static WinRAR/Bandizip observations do not establish producer interoperability or real exhausted-heap recovery.

## Home screen and platform behavior

- Pinned folders/Recently read headings are 13sp with existing boxes. Recent selection clears on changed search/type criteria or confirmed history clearing, not ordinary refresh.
- Main/SAF metadata opens are asynchronous; read-aloud notification returns retain a matching live session. Equivalent API updates retain older-Android Back/PIN/search, audio, launch and window behavior.

## Preserved behavior and support

- Exact TXT paging, partition sizes/overlap, half-visible-row selection and existing bookmark coordinates are unchanged.
- Dependency pins, licenses, permissions, signing and free-space protection retain the preceding baseline.
- Heuristic TXT Contents, path-bound backups and partial Office/Hancom/EPUB fidelity retain the [1.0.20 baseline](CURRENT_SOURCE_STATUS_1_0_20.md). The [archive baseline](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md) applies except for the explicit additions/restrictions above.

## Release documents

- [Changelog](../CHANGELOG.md) lists core changes; [patch notes](../PATCHNOTES.md) describe feature behavior; [development changes](DEV_CHANGES_1_0_21.md) and [code map](CODE_MAP.md) cover implementation.
- [Release readiness](RELEASE_READINESS_1_0_21.md) contains remaining validation and publication requirements, rather than a second implementation history.
- [License report](LICENSE_REPORT_READWIDE_1_0_21.md) and [source/direct-dependency SBOM](SBOM_READWIDE_1_0_21.spdx.json) retain their dependency baseline; neither is a resolved-transitive audit. [Documentation index](README.md) separates current references from historical records.
