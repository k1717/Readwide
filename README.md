# Readwide

Readwide is a local Android reader and file-browser app for TXT, PDF, EPUB, Word, image, comic archive, and general archive workflows.

Readwide 1.0.0 is the public successor to TextView Reader 2.2.6. The launcher name and public release line changed to Readwide, but the Android `applicationId` remains unchanged for update compatibility with the TextView 2.2.6 line when the APK is signed with the same key.

Current version: **Readwide 1.0.0**

Update page: `https://github.com/k1717/Readwide/releases`

## Highlights

- Local-first reader app with no default `INTERNET` permission, no analytics, no ads, no account system, and Android Auto Backup disabled.
- TXT reader with bookmarks, search highlighting, themes, custom fonts, page navigation, large-TXT paging safeguards, and TTS controls.
- File browser with recent files, filters, folder/search scope controls, fast scrolling, multi-select actions, queued copy/move/delete/extract/compress operations, and progress UI.
- Image and comic viewing for local folders and supported archive formats, including sorted image sequences and saved comic positions.
- Archive preview/extraction support for common ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR paths.
- Readwide branding, icon assets, settings text, backup labels, and developer-contact documentation are updated for the 1.0.0 line.

## Archive Support Summary

| Format family | Current path | Notes |
| --- | --- | --- |
| ZIP / ZIPX / CBZ | Zip4j primary, Apache Commons Compress fallback for non-encrypted special methods | Encrypted ZIP remains on Zip4j. AES plus unsupported special compression methods are not guaranteed. |
| 7z / CB7 | Apache Commons Compress 7z path | Password forwarding is attempted. Unsupported method chains depend on Commons Compress coverage. |
| TAR / CBT and TAR.GZ / TAR.BZ2 / TAR.XZ / TAR.LZMA / TAR.Z | Apache Commons Compress stream wrappers | Regular-file extraction is supported. Special entries are handled conservatively. |
| GZ / BZ2 / XZ / LZMA / Z | Single-file compressor streams | These are not multi-file archive containers. |
| RAR / CBR | Bundled libarchive-android for common compressed RAR attempts plus first-party Java paths for metadata and stored entries | RAR creation is not supported. Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed. Solid, PPMd, VM-filtered, broad SFX, and RAR5 compressed/solid/encrypted-header cases remain backend-dependent or unsupported. |
| ALZ | First-party parser for Store/Deflate/BZip2 and covered ZipCrypto-style cases | Broader legacy/split/encrypted variants still need real-world fixture QA. |
| EGG | First-party parser for Store/Deflate/BZip2/AZO/LZMA | Encrypted, split, and solid EGG archives are unsupported. |
| Archive creation | Plain ZIP creation through queued file actions | RAR/7z/ALZ/EGG creation and encrypted ZIP creation are not implemented. |

See `docs/ARCHIVE_SUPPORT_MATRIX_2_2_6.md` and `docs/RAR_STATUS_2_2_6.md` for the detailed archive boundary inherited from the TextView 2.2.6 base.

## Privacy And FOSS Notes

- Default builds do not request `INTERNET`.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- The developer contact button opens the user's mail app with `readwide.kj7w5@addy.io` or copies the address if no mail app is available.
- Junrar and other UnRAR-license fallback code are not part of the default FOSS-oriented build.
- First-party source is Apache License 2.0. Runtime dependency notices are kept in `THIRD_PARTY_NOTICES.md`.
- Any local jar added under `app/libs` must be audited separately before that custom APK is described as FOSS.

## Build

Requirements:

- Android Studio / Android Gradle Plugin compatible JDK
- Android SDK installed locally

Common local commands:

```powershell
.\gradlew.bat assembleDebug --offline
.\gradlew.bat testDebugUnitTest --offline
```

Release signing is intentionally local. The public source package does not include private keystores, APK outputs, IDE metadata, local SDK paths, or build directories.

## Public Release Docs

- `CHANGELOG.md` lists result-focused release changes.
- `PATCHNOTES.md` is the shorter user-facing release note format.
- `GITHUB_UPLOAD_NOTES.md` contains upload/checklist notes for publishing the GitHub source package.
- `docs/FDROID_SUBMISSION.md` and `fdroid/metadata/com.textview.reader.yml` provide the F-Droid submission draft and build hygiene notes.
- `fastlane/metadata/android/` contains source-embedded store/F-Droid metadata for English and Korean.
- `PRIVACY.md`, `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md` should be included with source releases.
- `docs/ASSET_PROVENANCE.md` records the release-facing provenance note for launcher artwork.
