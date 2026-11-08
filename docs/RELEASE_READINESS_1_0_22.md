# Readwide 1.0.22 release readiness

Version: `1.0.22 / 10022`; source documents dated **2026-11-08**.
Source preparation is separate from APK validation,
tagging and publication. Earlier release builds do not validate later changes.

## Completed source preparation

- Release notes, development notes and source status use the existing versioned document families. Changelog and patch notes retain subsystem groups and Release boundary sections. Historical references are repaired without changing their recorded test outcomes or treating them as current validation.
- The [documentation index](README.md) links current guides separately from historical evidence. The 1.0.21 license report and source/direct-dependency SBOM remain inherited inventories, not a built 1.0.22 artifact report.
- The source packager excludes generated/private files, refuses existing ZIP destinations and verifies inventory, CRC and portable modes. Direct file-byte comparison checks the archive against the working source without digest files. These checks do not validate runtime behavior or guarantee that every possible secret has been detected.
- Scoped compilation covered the current settings classes and 78 Android regression methods, with unrelated dependencies stubbed and build constants taken from the version declaration. The Android methods were not executed. All 28 focused tap/image host tests passed; six XML resources compiled without linking.
- Recorded archive and storage checks remain scoped results. See [source status](CURRENT_SOURCE_STATUS_1_0_22.md#validation-boundary) for sample coverage, skipped checks, producer limitations and earlier failures rather than treating the recorded runs as one full-suite pass.

## Before publishing an installable APK

### Build and packaging

- Build the final source and check APK identity, version, intended signing certificate, native libraries and packaged notices using [release build instructions](../RELEASE_BUILD.md). No complete APK/Gradle build or device validation was run for this preparation.
- Recheck release debugging, cleartext and Android Auto Backup settings, the absence of `INTERNET`, and the final artifact's dependency/license inventory. Do not substitute inherited reports for that inspection.

### Settings, storage and viewers

- Check the App information version label, update-link spacing and action, two-line tap/folder rows, larger fonts, RTL alignment, popup transitions and accessibility on a device. Check the general/View settings split and recreation.
- Verify per-viewer tap inheritance, backup/import/reset, saved timeout restoration and unchanged page/partition/bookmark behavior. The TXT and saved-data failure regression sources still need execution.
- Verify folder registration and revocation, picker return, overlapping grants, provider failures, copy/move/delete recovery and physical SD/USB detach. Huawei/API 28 behavior has not been confirmed on the reported device.
- Check image panel fit and short-image placement, bottom-padding tap navigation, centered page selection, zoom and system insets. Review inherited EPUB/PDF/TXT device checks in [1.0.21 release readiness](RELEASE_READINESS_1_0_21.md).

### Open findings and archive limits

- Manual bookmark writes can still report success after a storage error; continuous-PDF rendering can still leave a blank row without explicit retry. These are open code findings, not completed fixes. Details are in [source status](CURRENT_SOURCE_STATUS_1_0_22.md#open-reliability-findings).
- Encrypted EGG filenames remain unsupported. Producer-created encrypted-solid AZO and some cross-file split/solid/VM combinations remain unverified despite scoped host and synthetic coverage.
- The retained ALZ sample lost Unicode filename bytes during production; payload matches do not recover those names. Keep that producer limitation separate from Readwide decoder failures.
- Source metadata checks do not lock external writers, guarantee crash-atomic file operations or prove recovery from sustained device-wide memory exhaustion.

## F-Droid source review

Follow [F-Droid submission](FDROID_SUBMISSION.md) using current upstream metadata
and the final immutable commit. The checked-in metadata is historical through
1.0.21. Do not invent a commit or relabel inherited dependency reports as a newly
verified artifact inventory.

## Preserved boundaries

Exact page calculations, partition sizes and overlap, half-visible-row selection,
existing bookmark coordinates, free-space protection, dependency pins, signing
configuration and app version remain unchanged by documentation preparation and
the App information layout update. No commit, tag, push or publication is implied
by a prepared source ZIP.
