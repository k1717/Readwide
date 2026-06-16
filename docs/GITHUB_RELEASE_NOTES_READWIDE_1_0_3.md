# Readwide 1.0.3 GitHub release notes

Readwide 1.0.3 is a local-first Android reader and file-browser release that starts the document viewer fidelity cycle for DOCX, HWPX, and HWP, targeting L3 content-fidelity HTML preview while keeping the existing text-first readers as fallback.

## Highlights

- **Local-first privacy baseline**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.
- **Shared rendered-document model**: a format-neutral model (page containers, paragraph/run styles, tables, images, text anchors, unsupported placeholders) now backs the document viewer, with fallback to the previous semantic HTML path when conversion fails.
- **DOCX content fidelity**: paragraphs, run/character styles with `styles.xml` inheritance, `numbering.xml` ordered/bullet lists, basic tables (width, column proportions, vertical merges, border colors, shading), inline images with extent hints, footnotes/endnotes, and headers/footers are bridged into the rendered model.
- **DOCX list and table polish**: Symbol/Wingdings bullets are normalized to standard Unicode markers, hanging indents are no longer double-applied, and rendered tables clamp cell overflow and wrap by word so narrow phone-width columns stay readable without horizontal scrolling.
- **DOCX lecture-note math**: inline and conservative `$$...$$` display fragments render to HTML+CSS without WebView JavaScript, including fractions, square roots, superscripts/subscripts, Greek letters, and symbols, and including expressions split across runs by spell/grammar markers. Lone currency amounts such as `$200` stay as plain text.
- **HWP/HWPX content fidelity**: HWP binary documents now convert section/paragraph/control structure into the rendered model, preserving partially-ruled table borders per edge, column spans and proportional widths, authored cell heights for empty layout cells, character size/bold/italic/color/underline, paragraph alignment, paragraph-head bullet markers, and control-line horizontal rules. HWPX carries header/run styles, page metrics, and table color where directly present.
- **Archive preview safety**: RAR5 AES visible-header multi-volume handling and password-protected archive image preview caching were tightened so stale or wrong-password preview images are regenerated instead of reused.
- **Viewer polish**: EPUB/Markdown/document/PDF pages now snap without slide/fade animation, and the compact hidden-toolbar top page counter height was refined.
- **Full UI localization**: the newly added archive support-boundary messages, bookmark "file missing" notices, and tap/image paging labels are now translated across all 20 non-default bundled locales (Arabic, German, Greek, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Dutch, Polish, Portuguese, Russian, Swedish, Thai, Turkish, Ukrainian, Vietnamese, Simplified and Traditional Chinese). English remains the fallback for any future untranslated string.
- **GitHub/F-Droid docs updated for 1.0.3**: the document viewer fidelity matrix and fidelity notes, Fastlane changelogs, and the F-Droid metadata draft were updated for the 1.0.3 public package.

## Version metadata

```text
versionName 1.0.3
versionCode 10003
applicationId com.textview.reader
```

The application ID remains `com.textview.reader` for update compatibility with earlier compatible builds when signed with the same key.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- DOCX/HWP/HWPX document rendering targets L3 content-fidelity HTML preview. Exact MS Word/Hancom pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.
- HWP/HWPX is read-only. Cell vertical alignment, cell background fill, non-line GSO shapes, embedded images, and encrypted/password HWP are not yet claimed.
- Legacy binary `.doc` is recognized but unsupported for rendering.
- PDF/EPUB/Word/HWP/Markdown page counts are viewer/layout dependent, except TXT which keeps its exact source-page model.
- RAR/CBR support is limited and backend/scoped-path dependent.
- Archive creation is limited; RAR/7z/HWP/HWPX creation or editing is not provided.

See:

- `docs/DOCUMENT_VIEWER_FIDELITY_MATRIX_READWIDE_1_0_3.md`
- `docs/DOCUMENT_VIEWER_FIDELITY_READWIDE_1_0_3.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.3` Git tag referenced by `fdroid/metadata/com.textview.reader.yml`.
