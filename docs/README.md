# Readwide documentation

Current source: **1.0.21 / 10021**. Release documents are dated **2026-10-24**
for the GitHub upload package. Earlier build results do not validate later changes;
see the current status/readiness records. Versioned documents describe their own
release or checkpoint. Historical support exclusions and test results are not
automatically current.

## Current release and project guides

Changelog entries summarize the core changes; patch notes describe feature behavior.
Development notes retain implementation and regression details, source status records
current support limits, and release readiness lists outstanding verification.

- [Project overview](../README.md), [changelog](../CHANGELOG.md) and [patch notes](../PATCHNOTES.md).
- [1.0.21 release notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_21.md), [development changes](DEV_CHANGES_1_0_21.md), [source status](CURRENT_SOURCE_STATUS_1_0_21.md) and [release readiness](RELEASE_READINESS_1_0_21.md).
- [Code map](CODE_MAP.md), [Android Studio setup](../ANDROID_STUDIO_SETUP_FOR_BEGINNERS.md), [build notes](../BUILD_FIX_NOTES.md) and [contributing](../CONTRIBUTING.md).
- [Release build](../RELEASE_BUILD.md) and [F-Droid submission](FDROID_SUBMISSION.md).
- [Privacy](../PRIVACY.md), [project license](../LICENSE), [notice](../NOTICE) and [third-party notices](../THIRD_PARTY_NOTICES.md).
- [FOSS status](FOSS_STATUS.md), [1.0.21 license report](LICENSE_REPORT_READWIDE_1_0_21.md), [source/direct-dependency SBOM](SBOM_READWIDE_1_0_21.spdx.json) and [asset provenance](ASSET_PROVENANCE.md).
- [Store descriptions and changelogs](../fastlane/metadata/android) and [historical F-Droid metadata](../fdroid/metadata/com.readwide.manager.yml). The latter stops at 1.0.19; it is not a current upstream submission file.

## Current feature references

- [Reader search](TXT_SEARCH_USAGE.md).
- [Archive support navigation](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md), [EGG](EGG_FORMAT_NOTES.md) and [ALZ](ALZ_FORMAT_NOTES.md).
- Current EPUB, TXT, document and image-viewer behavior is summarized in the [overview](../README.md) and [1.0.21 source status](CURRENT_SOURCE_STATUS_1_0_21.md); older audits below remain historical.

## Earlier implementation and validation records

These documents retain useful format details and recorded evidence. Read their
dates and validation boundaries; use the current references above for later changes.

### Archive implementation

- [1.0.20 decode boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md), [routing](ARCHIVE_ROUTING_1_0_20.md), [extraction](ARCHIVE_EXTRACTION_1_0_20.md), [codec additions](ARCHIVE_CODEC_ADDITIONS_1_0_20.md), [stream repairs](ARCHIVE_STREAM_REPAIR_1_0_20.md) and [selected RAR fallback validation](RAR_SELECTED_FALLBACK_VALIDATION_1_0_20.md).
- [CAB integration](CAB_MULTIVOLUME_INTEGRATION_1_0_20.md), [LZHAM integration](LZHAM_INTEGRATION_1_0_20.md), [ZIP PPMd-I integration](ZIP_PPMDI_INTEGRATION_1_0_20.md) and [LHA LH4 integration](LHA_LH4_INTEGRATION_1_0_20.md).
- [ALZ selector validation](ALZ_SELECTOR_VALIDATION_FIX.md) and [ALZ archive validation](ALZ_ARCHIVE_BOUNDARY_REVIEW.md).
- [RAR 6/7 boundaries, 1.0.17](RAR6_RAR7_BOUNDARY_READWIDE_1_0_17.md).
- [RAR/7z split-encryption revalidation, 1.0.11](RAR_7Z_SPLIT_ENCRYPTION_REVALIDATION_READWIDE_1_0_11.md), [RAR5 encrypted headers](RAR5_HEADER_ENCRYPTION_READWIDE_1_0_11.md), [7z BCJ2](SEVENZ_BCJ2_READER_READWIDE_1_0_11.md), [7z PPMd](SEVENZ_PPMD_READER_READWIDE_1_0_11.md) and [archive-name corpus](ARCHIVE_NAME_CORPUS_READWIDE_1_0_11.md).
- [1.0.2 support matrix](ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md), [RAR status](RAR_STATUS_READWIDE_1_0_2.md), [RAR split support](RAR_SPLIT_SUPPORT_READWIDE_1_0_2.md), [7z split/encryption](SEVENZ_SPLIT_ENCRYPTION_STATUS_READWIDE_1_0_2.md), [numeric splits](NUMERIC_SPLIT_ARCHIVE_STATUS_READWIDE_1_0_2.md), [selected-entry failure handling](SINGLE_ENTRY_EXTRACTION_FAILURE_STATUS_READWIDE_1_0_2.md) and [stream extraction safety](STREAM_EXTRACTION_SAFETY_READWIDE_1_0_2.md).

### Viewer and general audits

- [1.0.20 source status](CURRENT_SOURCE_STATUS_1_0_20.md) and [release readiness](RELEASE_READINESS_1_0_20.md).
- [EPUB compatibility, 1.0.16](EPUB_COMPATIBILITY_AUDIT_1_0_16.md).
- [Engine/archive/image audit, 1.0.15](ENGINE_ARCHIVE_IMAGE_AUDIT_1_0_15.md) and [general code audit, 1.0.15](GENERAL_CODE_AUDIT_1_0_15.md).
- [TTS/EPUB/PDF design, 1.0.11](TTS_STATUS_AND_EPUB_PDF_DESIGN_READWIDE_1_0_11.md).
- [Document fidelity notes, 1.0.3](DOCUMENT_VIEWER_FIDELITY_READWIDE_1_0_3.md), [fidelity matrix](DOCUMENT_VIEWER_FIDELITY_MATRIX_READWIDE_1_0_3.md) and [HWP status, 1.0.2](HWP_SUPPORT_STATUS_READWIDE_1_0_2.md).

## Release history

Release notes, development notes, license inventories and SPDX files retain
their existing document-family formats. A dash means no separate file is
present in this source tree; it does not imply validation or missing app support.

| Version | Release notes | Development notes | License report | Source SBOM |
| --- | --- | --- | --- | --- |
| 1.0.21 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_21.md) | [Changes](DEV_CHANGES_1_0_21.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_21.md) | [SPDX](SBOM_READWIDE_1_0_21.spdx.json) |
| 1.0.20 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_20.md) | [Changes](DEV_CHANGES_1_0_20.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_20.md) | [SPDX](SBOM_READWIDE_1_0_20.spdx.json) |
| 1.0.19 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_19.md) | [Changes](DEV_CHANGES_1_0_19.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_19.md) | [SPDX](SBOM_READWIDE_1_0_19.spdx.json) |
| 1.0.18 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_18.md) | [Changes](DEV_CHANGES_1_0_18.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_18.md) | [SPDX](SBOM_READWIDE_1_0_18.spdx.json) |
| 1.0.17 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_17.md) | [Changes](DEV_CHANGES_1_0_17.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_17.md) | [SPDX](SBOM_READWIDE_1_0_17.spdx.json) |
| 1.0.16 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_16.md) | [Changes](DEV_CHANGES_1_0_16.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_16.md) | [SPDX](SBOM_READWIDE_1_0_16.spdx.json) |
| 1.0.15 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_15.md) | [Changes](DEV_CHANGES_1_0_15.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_15.md) | [SPDX](SBOM_READWIDE_1_0_15.spdx.json) |
| 1.0.14 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_14.md) | [Changes](DEV_CHANGES_1_0_14.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_14.md) | [SPDX](SBOM_READWIDE_1_0_14.spdx.json) |
| 1.0.13 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_13.md) | [Changes](DEV_CHANGES_1_0_13.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_13.md) | [SPDX](SBOM_READWIDE_1_0_13.spdx.json) |
| 1.0.12 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_12.md) | [Changes](DEV_CHANGES_1_0_12.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_12.md) | [SPDX](SBOM_READWIDE_1_0_12.spdx.json) |
| 1.0.11 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_11.md) | [Changes](DEV_CHANGES_1_0_11.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_11.md) | [SPDX](SBOM_READWIDE_1_0_11.spdx.json) |
| 1.0.10 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_10.md) | [Changes](DEV_CHANGES_1_0_10.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_10.md) | [SPDX](SBOM_READWIDE_1_0_10.spdx.json) |
| 1.0.9 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_9.md) | [Changes](DEV_CHANGES_1_0_9.md) | [Licenses](LICENSE_REPORT_READWIDE_1_0_9.md) | [SPDX](SBOM_READWIDE_1_0_9.spdx.json) |
| 1.0.8 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_8.md) | — | [Licenses](LICENSE_REPORT_READWIDE_1_0_8.md) | [SPDX](SBOM_READWIDE_1_0_8.spdx.json) |
| 1.0.7 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_7.md) | — | [Licenses](LICENSE_REPORT_READWIDE_1_0_7.md) | [SPDX](SBOM_READWIDE_1_0_7.spdx.json) |
| 1.0.6 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_6.md) | — | [Licenses](LICENSE_REPORT_READWIDE_1_0_6.md) | [SPDX](SBOM_READWIDE_1_0_6.spdx.json) |
| 1.0.5 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_5.md) | — | [Licenses](LICENSE_REPORT_READWIDE_1_0_5.md) | [SPDX](SBOM_READWIDE_1_0_5.spdx.json) |
| 1.0.4 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_4.md) | — | [Licenses](LICENSE_REPORT_READWIDE_1_0_4.md) | [SPDX](SBOM_READWIDE_1_0_4.spdx.json) |
| 1.0.3 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_3.md) | — | — | — |
| 1.0.2 | [Notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_2.md) | — | [Licenses](LICENSE_REPORT_READWIDE_1_0_2.md) | [SPDX](SBOM_READWIDE_1_0_2.spdx.json) |

## Component provenance and test resources

- [Google Brotli](../third_party/brotli-java/README.md), [libarchive and bundled codecs](../third_party/libarchive-android/UPSTREAM.md), [ZIPX JPEG/WavPack](../third_party/zipx-codecs-android/UPSTREAM.md) and [LZ5/Lizard](../third_party/zipx-codecs-android/LZ_UPSTREAM.md).
- [LZHAM](../third_party/lzham/README.md), [PPMd-I](../third_party/ppmd8/README.md) and [libmspack CAB](../third_party/libmspack/README.md).
- [CAB fixture attribution](../app/src/test/resources/archives/cab-multipart/README.md) and [EGG LEA fixture attribution](../app/src/test/resources/archives/egg-lea/README.md).
- [APK notice assets](../app/src/main/assets/open_source_licenses). Preserve upstream license text and component-local notices; document reorganization does not relicense third-party material.

