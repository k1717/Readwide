# Readwide 1.0.13 GitHub release notes

Readwide 1.0.13 brings read-aloud sentence highlighting to the document and PDF viewers, fixes the "continue reading aloud" resume flow in every viewer, and fixes case-only file renames. It keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.12, 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. The local-first privacy baseline is unchanged, and this release adds no new dependency.

## Read-aloud

- **The sentence being read aloud is now highlighted in every viewer.** The document viewer (EPUB, Word-family, HWP/HWPX, Markdown) and the PDF viewer highlight the current sentence like the text reader already did; the document viewer also scrolls it into view when it is off-screen. Sentences that span a header and the following paragraph are highlighted in full, and the highlight stays visible over book or theme styles.
- **Page titles are no longer spoken twice.** EPUB-style documents carry an invisible page title alongside the visible heading; read-aloud used to speak it before the content on every page.
- **"Continue reading aloud" resumes correctly in every viewer.** Markdown opens in the document viewer it normally uses (it used to switch to the plain-text reader); EPUB, Word, and PDF resume from the exact position playback stopped instead of the top of the saved page; and PDF now auto-starts (scanned/image-only PDFs stay silent on this automatic path).
- **Restarting reads what you expect.** The read-aloud dialog's page restart begins from the top of the page. In Markdown, playback starts from the beginning of the first line on screen - scrolled to the top means from the beginning, and stopping then restarting continues from the sentence that was being heard.

## Fixes

- **Case-only renames work.** Renaming a file or folder to the same name in a different case (for example `test` to `tESt`, or Title Case to lowercase) used to report success while silently leaving the name unchanged on storage that treats upper- and lowercase names as the same (common for SD cards and USB storage). Both rename entry points now perform case-only renames through a safe two-step move.
- **Turkish-locale extension matching.** Three file-extension checks were locale-sensitive and could misidentify files (a skipped font, a mislabeled bookmark) on Turkish-locale devices; they now match extensions locale-independently.

## Version metadata

```text
versionName 1.0.13
versionCode 10013
applicationId com.readwide.manager
```

## Update path

Installs in place over 1.0.12, 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6 (same application ID and signing key). No data migration is involved. One note: read-aloud positions saved by 1.0.12 or earlier in EPUB-style documents may land a few characters off on the first resume, because page titles are no longer part of the read-aloud text.

## Privacy baseline (unchanged)

No default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Dependencies

Identical to 1.0.12 - no dependency was added or updated. See `docs/LICENSE_REPORT_READWIDE_1_0_13.md` and `docs/SBOM_READWIDE_1_0_13.spdx.json`.
