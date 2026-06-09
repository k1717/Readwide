# Readwide 1.0.0 GitHub release notes

Readwide 1.0.0 is the public continuation of TextView Reader 2.2.6. The Android package/application ID is unchanged for update compatibility when signed with the same key, but the launcher display name and public version line now use Readwide.

## Highlights

- Rebranded the launcher display name to **Readwide**.
- Set Android metadata to `versionCode 10000` and `versionName "1.0.0"`.
- Kept the TextView 2.2.6 privacy/license hardening base: Auto Backup is disabled, default build remains FOSS-focused, and Junrar/UnRAR-license fallback code is not part of the default dependency path.
- Retained archive image-order fixes from the late TextView 2.2.6 line: direct comic open uses full-path natural order; archive preview-to-viewer image navigation follows the currently visible preview row order.
- Restored archive-preview folder sort-state behavior: nested folders open in natural name order, local sort changes stay local, and returning restores the previous folder sort state.
- Preserved main-list image opening order from the current visible file-list snapshot.
- Kept long archive/libarchive failure details in a scrollable/copyable dialog.

## RAR/CBR boundary

RAR remains libarchive-primary with first-party Java code scoped to stored entries, metadata/safe-path handling, covered stored split paths, diagnostics, and limited fallback/gap-reducer paths. This release does not claim complete RAR support. Split/multi-volume RAR, encrypted RAR, compressed-solid RAR, first-party PPMd/VM decoding, broad SFX handling, and RAR5 compressed/solid/encrypted-header support remain limited, backend-dependent, or unsupported unless a specific path is tested and documented.
