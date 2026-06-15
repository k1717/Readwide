# Changelog

## Readwide 1.0.4 - 2026-06-14

### Release scope

- Android metadata is `versionCode 10004` and `versionName "1.0.4"`.
- Readwide moves to a new Android applicationId, `com.readwide.manager`, completing the rename away from the earlier TextView Reader package identity.
- Because the application ID changed, 1.0.4 is installed as a separate app rather than an in-place update over older TextView Reader/Readwide builds.
- This release keeps the 1.0.3 document-fidelity cycle and focuses on package identity cleanup, reader-search consistency, document-viewer search behavior, and translated UI clipping fixes.

### Final changes included in this upload

**Package identity**

- Renamed the Android applicationId and source package from `com.textview.reader` to `com.readwide.manager`, including all package declarations, the FileProvider authority (derived from `${applicationId}`), layout custom-view references, ProGuard keep rules, fixture report scripts, F-Droid metadata, and release materials.
- Existing users are not auto-updated to 1.0.4 because the package identity differs; bookmarks, reading positions, themes, and settings transfer through the in-app JSON backup export/import, which is independent of package name and signing key.

**PDF viewer**

- Improved PDF page sharpness by rendering pages above 1:1 screen density (supersampling) and downscaling them for display, which noticeably crispens text. The page is displayed at its fit width with the aspect ratio preserved, so it is never stretched (including when the toolbar is hidden and the viewport grows taller). Per-page pixel budgets still bound memory, so large pages are capped automatically.
- Fixed the PDF viewer hiding the lower part of the page behind the bottom toolbar when chrome is visible. The floating toolbar's height is now reserved at the bottom of the page viewport while it is shown (and released when hidden), so a full PDF page sits between the top title area and the toolbar. Applies to both single-page and continuous-scroll modes and on first load.

**Image viewer**

- Raised the preview decode budget from 12 to 16 megapixels, so higher-resolution images are shown at full detail before any downsampling. Larger images are still downsampled to fit the screen and memory, with the existing out-of-memory fallback intact.

**TXT find-in-page**

- Fixed TXT find-in-page being extremely slow for common words in some files. The matcher now prepares the comparison view once per search and reuses it, and the large-file engine scans each line in a single pass, keeping returned offsets aligned to the original text for bookmarks and page anchors.
- Improved TXT find-in-page with case-sensitive, whole-word, and regular-expression options. Unicode normalization is always applied, overlapping literal occurrences are counted correctly, and the in-memory and large-file search paths share the same matcher and option semantics.
- Improved TXT search reveal near the end of a file. Search jumps now use a search-only virtual bottom scroll allowance so a match in the final lines can be pulled above the search dialog without changing normal paging, manual scrolling, bookmarks, or saved-position restore.

**Document-viewer search (Markdown, EPUB, HWP/HWPX, Word)**

- Reworked Markdown, EPUB, HWP/HWPX, and Word-family document-viewer search to use the same TXT-style search options and match counter instead of relying on WebView native find. The document search dialog supports previous/next movement, nth-match jumps, current/total status, case-sensitive mode, whole-word mode, and regex mode.
- Fixed document-viewer search result visibility. Current matches now use explicit highlighted spans and popup-safe reveal logic; the selected result is placed near the upper safe area, with top/bottom document spacer handling so matches near the beginning or end of a rendered document can still be moved into view above the bottom search dialog.
- Reduced same-page Markdown search bounce by updating the current highlighted match inside the existing DOM when possible instead of reloading the whole page for every previous/next movement.
- Strengthened EPUB/HWP/Word search highlight styling so reader/theme CSS does not erase the yellow/current-result highlight.

**Toolbar and dialogs**

- Fixed tap-to-turn paging firing when tapping the visible bottom toolbar (and top bar). The toolbar floats over the full-screen reader/document/PDF view, so a tap on it also fell inside the page-turn zone underneath. Taps that land on a shown chrome bar now toggle/keep the toolbar instead of turning the page; with the toolbar hidden, the whole view still pages normally. Fixed in the TXT, document (Markdown/EPUB/Word/HWP/HWPX), and PDF viewers.

- Fixed reader toolbar buttons running their action multiple times when tapped repeatedly, which could open duplicate dialogs or trigger repeated loading. Toolbar taps are now debounced, and only one positioned reader dialog is shown at a time.
- Fixed the settings "Button / icon order" rows (main filter, TXT, EPUB/Word, PDF) being vertically clipped under longer translations such as German. The rows changed from a fixed `48dp` height to `wrap_content` with a `48dp` minimum height and vertical padding, so longer labels wrap instead of being cut off.
- Applied the same wrapping fix to the sort dialog's options and to the TXT search dialog's option/action rows, whose fixed-height controls could clip longer translations.

**Manifest and distribution**

- Removed the dead `android:requestLegacyExternalStorage="true"` manifest flag, which had no effect under `targetSdk 35` and added an unnecessary legacy-storage signal for static scanners. File access behavior is unchanged.
- Public GitHub/F-Droid materials were updated for the 1.0.4 package, including the renamed F-Droid metadata file, Fastlane changelogs, and the package/version references in the release and submission docs.

## Readwide 1.0.3 - 2026-06-14

### Release scope

- Android metadata is `versionCode 10003` and `versionName "1.0.3"`.
- Keeps the existing Android `applicationId` / package name so Readwide remains update-compatible with earlier compatible builds when signed with the same key.
- Starts the document viewer fidelity cycle for DOCX, HWPX, and HWP.
- The target for this cycle is L3 content-fidelity HTML preview: document structure, inline styling, tables, and images where verified.
- Exact MS Word/Hancom pagination, exact font metrics, editing/saving, and complete floating-object placement are explicit non-goals.

### Final changes included in this upload

- Existing text-first and semantic WebView document readers remain the fallback path, and a shared RenderedDocument / FixedHtmlRenderer scaffold was added before changing real document conversion behavior. The scaffold supports page containers, paragraph/run styles, tables, images, unsupported placeholders, and text anchors.
- DOCX now has a conservative bridge into the shared RenderedDocument model for paragraphs, run styling, basic tables, inline images, and page margins, with fallback to the previous Word semantic HTML path if conversion fails.
- DOCX `numbering.xml` lists now enter the rendered bridge as visible ordered/bullet markers with level-aware counters and indentation; malformed or missing numbering definitions still fall back to ordinary paragraphs.
- DOCX tables in the rendered bridge now preserve basic table width, grid column proportions, cell width hints, vertical merge row spans, table/cell border colors, and cell shading where those properties are directly present in the OOXML.
- DOCX `styles.xml` inheritance now feeds the rendered bridge for document defaults, paragraph styles, character styles, based-on style chains, and direct override precedence, improving heading/body/emphasis fidelity without changing the fallback path.
- DOCX images in the rendered bridge now read WordprocessingML/DrawingML extent metadata, emit width/height hints, and mark floating `wp:anchor` drawings as block-downgraded images instead of pretending to preserve exact floating layout.
- DOCX footnotes and endnotes in the rendered bridge are now preserved at the end of the rendered document, with superscript reference links in the body and backlink targets in the note section.
- DOCX headers and footers in the rendered bridge are now preserved as reading-order sections before and after the body, including paragraph/table content and local header/footer image relationships where directly referenced.
- DOCX Symbol/Wingdings bullet markers are normalized to standard Unicode bullets in the rendered document bridge, and list paragraphs no longer double-apply Word hanging indents on top of the flex marker layout.
- DOCX rendered tables now clamp cell overflow and use stronger word wrapping so narrow columns cannot draw text over neighboring cells on phone-width pages, while long first-column labels stay readable without introducing horizontal scrolling and cells prefer word-level wrapping over arbitrary letter breaks.
- DOCX split text runs are coalesced before rendering, and DOCX/Word lecture-note formulas now render inline and conservative `$$...$$` display-math fragments, including fractions, square roots, superscripts/subscripts, Greek letters, and symbols such as `\partial`. Covered examples include `$2Dt$`, `$L/W$`, `$\nu_0$`, `$\rho_{S}$`, and `$e^{-\text{barrier}/kT}$`, including expressions split across runs by spell/grammar markers. Lone currency amounts such as `$200` are left as plain text.
- HWP and HWPX rendered output now preserves partially-ruled table borders per edge (for example header bands with only top and bottom rules), paragraph-head bullet markers, authored cell heights so empty layout cells do not collapse, character size/bold/italic/color, paragraph alignment, and horizontal rules.
- RAR5 AES visible-header multi-volume handling was tightened against real encrypted multi-volume source fixtures. Covered RAR5 v5.0 compressed/solid split entries now assemble their packed stream across volumes, tolerate continuation encryption-flag differences when the actual AES material matches, and avoid plaintext CRC checks only when a RAR5 password-check value is present.
- Password-protected archive image preview caches now require current-session verification before a ready marker is trusted. Sensitive preview files that were produced by an older failed password/decode attempt are deleted and regenerated, preventing stale black/invalid images from being reused after the RAR5 visible-header multi-volume decoder succeeds.
- RAR5 encrypted/solid/split preview single-entry extraction now prefers the first-party ordered decoder instead of a backend call that may start from a later volume or an unprimed solid member. This targets archive preview document opens where whole extraction succeeds but preview opens the wrong or invalid cached file. RAR5 visible-header listing also prefers first-party header parsing before libarchive to reduce password-preview startup delay.
- Archive preview/list loading now uses the same centered loading window as archive entry preview extraction, removing the leftover tiny inline spinner from the archive browser.
- EPUB page transition animation settings were removed because WebView document pages now snap without slide/fade animation, and the compact top page counter shown while EPUB/Markdown/document/PDF toolbars are hidden now forces a 48dp strip plus the status-bar inset and nudges the page-number glyphs downward for better balance.
- The selectable UI languages were brought to full coverage: newly added archive support-boundary messages, bookmark "file missing" notices, and tap/image paging labels are now translated across all bundled locales (Arabic, German, Greek, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Dutch, Polish, Portuguese, Russian, Swedish, Thai, Turkish, Ukrainian, Vietnamese, Simplified Chinese, and Traditional Chinese), with English remaining the fallback for any future untranslated string.
- Public GitHub/F-Droid materials were updated for the 1.0.3 package, including the document viewer fidelity matrix and fidelity notes, Fastlane changelogs, and the F-Droid submission metadata draft.

## Readwide 1.0.2 - 2026-06-12

### Release scope

- Android metadata is `versionCode 10002` and `versionName "1.0.2"`.
- Keeps the existing Android `applicationId` / package name so Readwide remains update-compatible with earlier compatible builds when signed with the same key.
- Focuses on new document reader formats, rendered-document viewer polish, scoped archive decoding boundaries, and release documentation for the 1.0.2 line.

### Final changes included in this upload

- `.md` and `.markdown` files now open in a dedicated themed Markdown WebView reader. Markdown headings, emphasis, lists, links, code blocks, blockquotes, and tables are rendered as HTML while ordinary `.txt` files stay on the exact TXT reader model.
- HWP/HWPX files now have text-first read-only support through Apache-2.0 dogfoot libraries: `hwplib` for HWP 5.x and `hwpxlib` for HWPX. Hancom-compatible layout rendering, editing/writing, password/encrypted HWP, original page-count parity, and embedded object rendering are not claimed.
- The visible `Word` filter remains compact while grouping OOXML Word files, HWP/HWPX, and recognized legacy `.doc` files. Legacy binary `.doc` is recognized for classification/filtering but is still reported as unsupported for rendering.
- Markdown, EPUB, Word, HWP/HWPX, and PDF bookmark rows now use the same rendered-document display model: content/text anchors are shown as the primary label where available, while page/position/date metadata is shown separately.
- WebView-backed document viewer chrome was adjusted so toolbar toggles do not move the rendered WebView body. Compact top page labels, bottom toolbar shape, slider presentation, Markdown CSS isolation, and Android navigation-inset handling were refined without changing the TXT reader model.
- PDF viewer system-bar and navigation-inset handling was refined separately from WebView documents so the PDF viewport keeps fixed-layout behavior while avoiding stale toolbar-colored system bars.
- The Readwide launcher source reference was updated to the project-supplied image at `docs/readwide_launcher_icon_source.png`; checked-in Android launcher/adaptive/play-store PNG resources were left unchanged.
- Unknown-size decoded stream extraction, failed single-entry extraction cleanup, and 7z solid-member drain handling now use the decoded-byte safety boundary so unsupported or hostile streams cannot bypass the total extraction ceiling or leave stale output.
- 7z/CB7 password and split handling was tightened. Standard raw split chains such as `.7z.001` / `.7z.002` and `.cb7.001` / `.cb7.002` are resolved conservatively, missing/gapped chains are treated as corrupt/incomplete, and wrong-password AES failures are classified with password context where possible.
- RAR/CBR support remains conservatively documented. The default build uses libarchive-android as the compressed-RAR backend, plus scoped first-party decode-only paths for covered stored entries, eligible unencrypted single-volume RAR3/RAR4 PPMd solid cases, and eligible unencrypted single-volume RAR5 v5.0 compressed/solid cases. Full RAR, encrypted RAR, broad split RAR, SFX, VM-filtered RAR, and complete RAR compatibility are not claimed.
- ALZ/EGG first-party extraction paths remain limited and CRC-verified for covered methods, with unsupported encrypted/split/solid cases failing cleanly.
- Public GitHub/F-Droid materials were updated for the 1.0.2 package: `README.md`, `PRIVACY.md`, `THIRD_PARTY_NOTICES.md`, FOSS notes, F-Droid submission notes, license report, SBOM draft, Fastlane metadata, release checklist, and release notes.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for selected methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress, with the 1.0.2 split/password/drain safety boundaries documented separately.
- ALZ/EGG behavior is unchanged from the existing scoped coverage; 1.0.2 keeps those limits documented while focusing archive changes on 7z safety and RAR boundary wording.
- RAR/CBR remains limited and backend/scoped-path dependent. It includes covered stored paths, selected stored split paths, RAR4 Unicode metadata handling, eligible unencrypted single-volume RAR3/RAR4 PPMd solid extraction, and eligible unencrypted single-volume RAR5 v5.0 compressed/solid extraction.
- RAR creation/compression, password recovery, encrypted RAR support, broad split RAR support, broad SFX support, and complete RAR compatibility are not implemented or claimed.

## Readwide 1.0.1 - 2026-06-09

### Release scope

- Kept Android metadata at `versionCode 10001` and `versionName "1.0.1"`.
- Kept the existing Android `applicationId` / package name so Readwide 1.0.1 remains update-compatible with earlier compatible builds when signed with the same key.
- Focused on viewer polish, portable backup/bookmark handling, archive safety, lifecycle hardening, and public GitHub/F-Droid packaging cleanup.

### Final changes included in this upload

- Missing bookmark target files remain visible in the bookmark list with a theme-matched **File missing / 파일 없음** label. Tapping one opens a themed explanation dialog while preserving the bookmark for later portable rebind.
- Backup import keeps `last_directory`, `recent_folders`, and `folder_shortcuts` only when those folders exist on the current device. Invalid imported paths are skipped, and valid current-device entries are preserved when the backup has no accessible replacement.
- TXT bookmark imports treat `pageNumber`, `totalPages`, and `pageLayoutSignature` as layout-dependent cache. Character position, logical line, surrounding anchor text, and file fingerprint remain the stable bookmark location, and Page X/Y refreshes under the current device layout when the file is opened or the bookmark is used.
- Zoomed PDF pages can fling/pan with inertia in single-page mode, and zoomed pages in vertical continuous mode support horizontal fling across the visible page while original-scale swipes keep their page-turn behavior.
- Image viewer landscape safe-area handling now respects Android 3-button navigation on the right side of the screen.
- Image viewer default fitting is adaptive: wide images open fit-to-width and tall images open fit-to-height. Double tap toggles against true 1:1 scale when applicable.
- Image viewer keeps successfully decoded detail/original bitmaps after returning from zoom to adaptive-fit view, avoiding repeated detail re-decodes for the same image.
- Archive-backed image viewer recent/saved-position reopen paths were hardened so deferred image sequence metadata is applied before decoding, with fallback handling for missing or invalid handoff metadata.
- Legacy archive entry filename decoding was expanded for raw ZIP central-directory names and first-party ALZ/EGG name fields, covering Korean, Chinese, Japanese, Cyrillic, Greek, Turkish, Hebrew, Arabic, Thai, Vietnamese, Western/Central/Baltic Latin, and DOS ZIP code pages where raw name bytes are available.
- Password-protected archive image viewing uses selected-image-first lazy extraction after password entry. Password-sensitive preview cache reuse validates the current password before trusting cached output.
- ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/LZMA extraction paths stream to output with CRC verification where the format path allows it.
- Archive failure messages now distinguish password-required, bad-password, unsupported-feature, corrupt-archive, and generic failures, with conservative family-specific support-boundary details.
- Added external RAR and non-RAR archive fixture report tooling for local compatibility QA without broadening public archive compatibility claims.
- Removed forced process-wide `System.gc()` calls from image decode OOM retry; retries increase sample size and yield instead.
- Added lifecycle guards and cleanup for document WebView callbacks, PDF delayed callbacks, TXT TTS callbacks, reader-toolbar delayed work, drawer delayed work, image sequence handoffs, archive password snapshots, and font-scan callbacks.
- Bounded EPUB fixed-layout/font detection reads so large HTML/CSS entries are not fully loaded during detection-only scans.
- Public GitHub/F-Droid documentation, release notes, archive/RAR/license report filenames, and Fastlane changelogs were normalized for the Readwide 1.0.1 release line.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted unsupported ZIP methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress.
- ALZ and EGG remain limited first-party implementations with documented method coverage and unsupported encrypted/split/solid variants.
- RAR/CBR remains libarchive-primary with scoped first-party Java support for metadata, safe paths, stored entries, selected stored split paths, RAR4 Unicode names, diagnostics, and covered RAR5 stored-entry handling.
- RAR creation is not implemented.
- Split/multi-volume RAR and encrypted RAR are not guaranteed.
- Solid RAR, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual RAR variants remain backend-dependent or unsupported unless a specific file is covered by the bundled backend.

## Readwide 1.0.0 - 2026-06-09

### Release scope

- Renamed the public app line from TextView Reader to Readwide.
- Kept Android metadata at `versionCode 10000` and `versionName "1.0.0"`.
- Kept the existing Android `applicationId` / package name so Readwide 1.0.0 remains update-compatible with the TextView 2.2.6 line when signed with the same key.
- Updated the in-app static update link to `https://github.com/k1717/Readwide/releases`.

### Final changes included in this upload

- Updated launcher/app display branding, settings wording, backup wording, exported backup filename prefix, TTS media-session label, and developer-contact documentation to Readwide.
- Changed the developer contact address to `readwide.kj7w5@addy.io`.
- Reworked the main language setting from a long radio-button page into a compact selected-language row with a rounded picker dialog.
- Added selectable major UI languages and initial resource coverage for the expanded language list. Untranslated strings fall back to the default English resources.
- Fixed the recent-file multi-select menu so long English actions such as `Remove from recent list` can wrap instead of being clipped.
- Replaced launcher/adaptive/play-store icon assets with the approved Readwide book artwork and adjusted launcher safe margins to avoid clipped-looking edges.
- Fixed the custom reading theme create/edit screen so the top app bar respects the Android status bar and display cutout, preventing the back button from overlapping system UI.
- Fixed the reading-theme selection mark so the selected row shows a real check mark instead of mojibake text.
- Matched the custom reading theme editor's app-bar/status-inset background to the active main theme bar color instead of leaving a gray strip above the toolbar.
- Switched the public update URL to the standard GitHub releases page: `https://github.com/k1717/Readwide/releases`.
- Cleaned RAR source comments so the public FOSS package describes first-party RAR work as independent implementation based on public format behavior and fixture validation, not as UnRAR source porting.
- Documented launcher icon provenance as project-owned generated artwork and removed the unused optional local RAR5 decoder bridge/readme from the public source package.
- Removed development-session wording from RAR diagnostic strings and comments so detailed archive failures use release-facing wording.
- Removed the default `app/libs/*.jar` dependency hook so the public FOSS/F-Droid-oriented source tree has no local optional jar path in the Gradle dependency graph.
- Made release signing conditional so F-Droid-style source builds can run `assembleRelease` without a private developer keystore and produce an unsigned release artifact.
- Removed the unused Foojay toolchain resolver plugin from `settings.gradle` to keep the build script leaner for reproducible source-build review.
- Added Readwide backup filename patterns to `.gitignore` so exported user backups are not accidentally committed.
- Kept the TextView 2.2.6 privacy/license hardening base: Auto Backup disabled, no default `INTERNET` permission, no analytics, no ads, no account system, and no Junrar/UnRAR-license fallback in the default build.
- Preserved archive preview/image-sequence fixes from the late TextView 2.2.6 line, including direct comic-open ordering, preview-to-viewer ordering, archive folder sort-state restoration, and macOS resource-fork image filtering.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted unsupported ZIP methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress.
- ALZ and EGG remain limited first-party implementations with documented method coverage and unsupported encrypted/split/solid variants.
- RAR/CBR remains libarchive-primary with scoped first-party Java support for metadata, safe paths, stored entries, selected stored split paths, RAR4 Unicode names, diagnostics, and covered RAR5 stored-entry handling.
- RAR creation is not implemented.
- Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed.
- Solid RAR, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual RAR variants remain backend-dependent or unsupported unless a specific file is covered by the bundled backend.

## TextView Reader 2.2.6 - 2026-06-07

### Release scope

- TextView Reader 2.2.6 is the privacy/license hardening base that Readwide 1.0.0 continues from.
- Readwide 1.0.0 keeps the same application ID for update compatibility with this line.

### Final changes included in this release

- Disabled Android app-data Auto Backup in the manifest.
- Replaced new PIN storage with salted PBKDF2 verifier strings and kept migration for legacy plain-PIN data after successful verification.
- Removed the default `INTERNET` and `REQUEST_INSTALL_PACKAGES` permission paths.
- Replaced in-app update checking with a static, copyable release link in Settings.
- Added developer contact through the user's mail app, with copy fallback if no mail app is available.
- Removed Junrar/UnRAR-license fallback code from the default dependency path.
- Documented the default source/APK as the FOSS-friendly line with Apache-2.0 first-party source and third-party notices.
- Added bundled libarchive-android routing for common compressed RAR3/RAR4 attempts while keeping first-party RAR stored-entry and metadata paths.
- Added scrollable/copyable archive failure detail dialogs so long backend errors are no longer reduced to truncated toasts.
- Refined archive password dialogs with compact buttons and a show/hide password toggle.
- Kept ARM-only release native packaging and excluded unnecessary desktop native payloads from Android packaging.

### Known support boundaries

- Split/multi-volume RAR and encrypted RAR were not guaranteed for the public 2.2.6 package.
- First-party compressed RAR was not complete.
- RAR5 compressed/solid/encrypted-header extraction remained backend-dependent.

## TextView Reader 2.2.5 - 2026-06-02

### Release scope

- Android metadata: `versionCode 2250`, `versionName "2.2.5"`.
- Focused on archive fallback behavior, browse-state responsiveness, file-operation progress, and reducing large activity responsibilities.

### Final changes included in this release

- ZIP extraction uses Zip4j as the primary path and Apache Commons Compress as a fallback for non-encrypted unsupported compression methods such as Deflate64, BZip2, XZ, and ZSTD where available.
- Pending ZIP creation resolves the destination from the folder where the queued action is executed.
- Main file/folder action short-hold opens faster, while multi-select hold remains separate.
- Returning from internal viewers preserves the current main-folder list and scroll state when the folder has not changed.
- Fully loaded folder snapshots can be restored in both directions, including A -> B -> A and A -> B -> A -> B navigation.
- Drawer shortcut and recent-folder navigation restore cached target folders optimistically and validate them in the background.
- Multi-select delete exits selection mode after confirmation so background progress can be reopened from the toolbar.
- Browse-state logic moved into `MainBrowseStateController`.
- Archive list shaping, archive image sequence loading, and archive create/extract planning moved into focused helper classes.

### Known support boundaries

- Encrypted ZIP entries remain on Zip4j.
- AES entries that also use unsupported special ZIP methods remain unsupported.
- ZIP creation remains plain ZIP only.

## TextView Reader 2.2.4 - 2026-06-02

### Release scope

- Android metadata: `versionCode 2240`, `versionName "2.2.4"`.
- Focused on public license packaging, queued archive actions, archive preview safety, and theme editing.

### Final changes included in this release

- First-party project source is Apache License 2.0 and ships with `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
- Compress actions add pending ZIP creation tasks instead of running immediately.
- Pending copy, move, extract, and compress tasks are managed from the same pending-actions menu.
- ALZ supports Store/Deflate/BZip2 extraction with CRC verification.
- EGG supports Store/Deflate/BZip2/AZO/LZMA through the first-party parser.
- Standard 7z/CB7 split volumes resolve to the first part and open through a concatenated seekable channel.
- Archive management includes safer preview caching, stricter path sanitization, password preflight, backup/restore overwrite extraction, free-space guards, and cache pruning.
- Custom main-theme and reading-theme color editors include a lightweight shader-based color palette picker with HEX/RGB input.

### Known support boundaries

- The 2.2.4 RAR/CBR line still used a then-bundled Junrar fallback for older RAR extraction. That fallback is removed from the default Readwide 1.0.0 / TextView 2.2.6 FOSS-oriented line.
- RAR creation is not implemented.
- ALZ/EGG encrypted, split, solid, and unusual legacy variants remain limited or unsupported unless explicitly covered by tests.
