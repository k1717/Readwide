# Readwide 1.0.2 GitHub release notes

Readwide 1.0.2 is a local-first Android reader and file-browser release focused on new document reader formats, rendered-document viewer polish, scoped archive decoding boundaries, and FOSS/F-Droid-ready packaging for the 1.0.2 line.

## Highlights

- **Local-first privacy baseline**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.
- **Markdown reader**: `.md` / `.markdown` files open through a themed WebView reader with rendered headings, lists, links, code blocks, blockquotes, and HTML tables. Markdown uses rendered visual pages; TXT keeps its exact source-page model.
- **HWP/HWPX text-first reading**: `.hwp` and `.hwpx` open through a read-only, text-first WebView path powered by Apache-2.0 dogfoot libraries. This is not Hancom-compatible layout rendering, editing, writing, or encrypted-HWP support.
- **Word filter cleanup**: the visible `Word` filter remains compact while grouping OOXML Word, HWP/HWPX, and recognized legacy `.doc` files. Legacy `.doc` is recognized but not rendered yet.
- **Rendered-document bookmark display**: Markdown, EPUB, Word, HWP/HWPX, and PDF bookmark rows now prioritize text/content anchors where available while keeping page/position metadata as secondary information.
- **WebView viewer polish**: WebView document chrome, compact top page labels, bottom toolbar shape, slider behavior, Markdown CSS isolation, and Android navigation-inset handling were refined without changing the TXT reader model.
- **7z and decoded-stream safety**: 7z/CB7 split/password handling, AES wrong-password classification, decoded-stream ceilings, single-entry extraction cleanup, and solid-member drain safety were tightened under conservative archive compatibility claims.
- **RAR wording kept conservative**: Readwide includes limited extraction/read paths and scoped first-party decode-only fallbacks for eligible unencrypted single-volume cases, but it does not claim full RAR, encrypted RAR, broad split RAR, SFX, or VM-filtered compatibility.
- **Launcher source reference updated**: `docs/readwide_launcher_icon_source.png` now stores the project-supplied Readwide icon source image; checked-in Android launcher/adaptive/play-store PNG resources were not regenerated in this cleanup package.
- **GitHub/F-Droid docs updated for 1.0.2**: README, privacy notes, third-party notices, FOSS status, license report, SBOM draft, F-Droid metadata draft, Fastlane metadata, and release checklist were updated for the 1.0.2 public package.

## Version metadata

```text
versionName 1.0.2
versionCode 10002
applicationId com.textview.reader
```

The application ID remains `com.textview.reader` for update compatibility with earlier compatible builds when signed with the same key.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- HWP/HWPX is text-first and read-only.
- Legacy binary `.doc` is recognized but unsupported for rendering.
- PDF/EPUB/Word/HWP/Markdown page counts are viewer/layout dependent, except TXT which keeps its exact source-page model.
- RAR/CBR support is limited and backend/scoped-path dependent.
- Archive creation is limited; RAR/7z/HWP/HWPX creation or editing is not provided.

See:

- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment and replace the placeholder F-Droid commit hash in `fdroid/metadata/com.textview.reader.yml` with the final immutable `v1.0.2` commit.
