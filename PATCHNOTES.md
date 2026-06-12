## Readwide 1.0.2

### Release scope

- Readwide 1.0.2 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- This release focuses on new document reader formats, rendered-document viewer polish, scoped archive decoding boundaries, and release documentation for the 1.0.2 line.

### Final changes included in this release

- Markdown files now open in a dedicated themed WebView reader. Markdown rendering is separate from the exact TXT reader model and does not change plain TXT paging.
- HWP/HWPX files now have text-first read-only support through Apache-2.0 dogfoot libraries. The app does not claim Hancom-compatible layout rendering, editing/writing, password/encrypted HWP support, or original page-count parity.
- The visible Word filter remains compact while grouping OOXML Word, HWP/HWPX, and recognized legacy DOC files. Legacy binary DOC is recognized for classification but remains unsupported for rendering.
- Markdown, EPUB, Word, HWP/HWPX, and PDF bookmark rows now use a shared rendered-document display model with content/text anchors as the primary label and page/position/date as secondary metadata.
- WebView document chrome was adjusted so toolbar toggles do not move the rendered body. Compact top page labels, bottom toolbar shape, slider presentation, Markdown CSS isolation, and Android navigation-inset handling were refined without changing TXT.
- PDF system-bar and navigation-inset behavior was refined separately from WebView documents so fixed-layout PDF behavior is preserved.
- The project launcher source reference was updated at `docs/readwide_launcher_icon_source.png`; checked-in Android launcher/adaptive/play-store PNG resources were left unchanged.
- Unknown-size decoded stream extraction, failed single-entry extraction cleanup, 7z solid-member drains, and 7z split/password classification were tightened under conservative archive compatibility claims.
- RAR/CBR support remains limited and backend/scoped-path dependent, with scoped decode-only paths for covered unencrypted single-volume RAR3/RAR4 PPMd solid and RAR5 v5.0 compressed/solid cases. Full RAR, encrypted RAR, broad split RAR, SFX, VM-filtered RAR, and complete RAR compatibility are not claimed.
- Public GitHub/F-Droid documents, Fastlane changelogs, FOSS notes, license report, SBOM draft, release notes, and release checklist were updated for the Readwide 1.0.2 package.

### Archive and FOSS boundary

- The 1.0.2 package keeps the no-network/local-first privacy baseline from earlier releases while adding Apache-2.0 HWP/HWPX libraries and updated direct-dependency notices.
- Archive claims for 1.0.2 should point reviewers to `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`; this release adds 7z safety/classification work and narrows RAR wording around scoped decode-only paths.
- RAR/CBR marketing must stay limited to documented covered cases; complete, encrypted, broad split, SFX, or VM-filtered RAR compatibility is not claimed.

## Readwide 1.0.1

### Release scope

- Readwide 1.0.1 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- This release focuses on viewer polish, portable backup/bookmark handling, archive safety, lifecycle hardening, and public GitHub/F-Droid packaging cleanup.

### Final changes included in this release

- Missing bookmark target files remain visible with a theme-matched file-missing label. Tapping one opens an explanation dialog and keeps the bookmark for later file rebind.
- Backup import restores last directory, recent folders, and folder shortcuts only when those directories exist on the current device. Invalid imported paths are skipped without deleting valid current-device entries.
- TXT bookmark positions remain based on character position, line number, anchor text, and file fingerprint. Cached Page X/Y values are treated as layout-dependent cache and refresh under the current device layout when the file is opened.
- Zoomed PDF pages can fling/pan with inertia in single-page mode, and zoomed pages in vertical continuous mode can fling horizontally.
- Image viewer landscape safe-area handling was fixed for Android 3-button navigation. Wide images open fit-to-width, tall images open fit-to-height, and detail/original decode is kept after returning from zoom.
- Archive filename decoding, password-protected archive image preview, ALZ/EGG streaming extraction, archive preview cache pruning, and archive failure messages were hardened while keeping archive support claims conservative.
- Public GitHub/F-Droid documents, Fastlane changelogs, and release-note files were cleaned into result-focused Readwide 1.0.1 sections.

### Archive and FOSS boundary

- Default builds remain local-first and FOSS-oriented: no default `INTERNET` permission, no ads, no analytics, no account system, no app-network update check, Android Auto Backup disabled, and no Junrar/UnRAR-license fallback in the default build.
- ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR support remain as documented in the archive support matrix.
- RAR support is still limited and should not be advertised as complete compatibility.

## Readwide 1.0.0

### Release scope

- Readwide 1.0.0 is the public continuation of TextView Reader 2.2.6.
- The app name is now Readwide, but the Android package/application ID stays unchanged for update compatibility with the TextView 2.2.6 line when signed with the same key.
- Settings now points to `https://github.com/k1717/Readwide/releases` for update information.

### Final changes included in this upload

- Readwide branding is applied across app labels, Settings, backup text, TTS labels, public docs, and developer contact text.
- Developer contact email is `readwide.kj7w5@addy.io`.
- Main language selection uses a compact row and rounded picker dialog instead of a long settings page.
- Major UI language options were added with initial translated resources; missing strings fall back to English.
- Recent-file multi-select actions can wrap long English labels instead of clipping.
- Launcher icons were replaced with the approved Readwide book artwork and adjusted for safer launcher margins.
- The custom reading theme editor now respects status-bar/cutout insets, so the top back button no longer overlaps the system bar.
- Reading-theme selection now shows a normal check mark again instead of broken encoded text.
- The custom reading theme editor's top app-bar/status-inset area now follows the active main theme bar color instead of showing a gray strip.
- The update link now uses the standard GitHub releases URL: `https://github.com/k1717/Readwide/releases`.
- RAR implementation comments and public packaging notes were cleaned for clearer FOSS/provenance wording.
- Launcher icon provenance is documented as project-owned generated artwork.
- The unused optional local RAR5 decoder bridge/readme was removed from the default public source tree.
- RAR detailed failure messages no longer expose development-session wording.
- The public Gradle dependency graph no longer includes a local `app/libs/*.jar` hook.
- Release signing is conditional, so F-Droid-style source builders can assemble an unsigned release without a private keystore.
- The unused Foojay toolchain resolver plugin was removed from `settings.gradle`.
- Readwide backup export filenames are ignored by git.
- Public docs are cleaned into release-result sections instead of development-session notes.

### Archive and FOSS boundary

- Default builds remain FOSS-oriented: no Junrar/UnRAR-license fallback code and no default `INTERNET` permission.
- ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR support remain as documented in the archive support matrix.
- Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed.
- RAR solid archives, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual variants remain backend-dependent or unsupported.

## TextView Reader 2.2.6

### Release scope

- TextView Reader 2.2.6 is the direct base for Readwide 1.0.0.
- Privacy, license, and archive-backend boundaries from this line are preserved in Readwide unless noted otherwise.

### Final changes included in this release

- Android Auto Backup is disabled.
- New PIN storage uses salted PBKDF2 verifier strings with legacy migration.
- Default builds have no `INTERNET` permission, no app-network update check, no telemetry, no ads, and no account system.
- Developer contact uses the user's mail app or copies the address when no mail app is available.
- Junrar/UnRAR-license fallback code is removed from the default build.
- Common compressed RAR3/RAR4 attempts route through bundled libarchive-android, with first-party Java kept for metadata, stored entries, safe paths, diagnostics, and selected stored RAR5 paths.
- Archive password prompts use compact buttons and include a password visibility toggle.
- Long archive errors open in a scrollable/copyable dialog.

### Known support boundaries

- Split/multi-volume RAR and encrypted RAR are not guaranteed.
- First-party compressed RAR is not complete.
- RAR5 compressed/solid/encrypted-header cases remain backend-dependent.

## TextView Reader 2.2.5

### Release scope

- Focused on archive fallback handling, smoother folder navigation, file-operation progress, and activity refactoring.

### Final changes included in this release

- ZIP extraction falls back to Apache Commons Compress for non-encrypted unsupported methods where bundled codecs can decode them.
- Pending ZIP creation runs in the destination folder where the queued action is executed.
- Viewer returns, drawer shortcuts, recent-folder navigation, and already-loaded folder revisits preserve or restore cached folder state when safe.
- Multi-select delete progress can be reopened after confirmation/backgrounding.
- Browse-state, archive list shaping, archive image sequence loading, and archive create/extract planning were split into focused controllers/helpers.

### Known support boundaries

- Encrypted ZIP entries stay on Zip4j.
- AES plus unsupported ZIP methods remain unsupported.
- ZIP creation is plain ZIP only.

## TextView Reader 2.2.4

### Release scope

- Focused on public license packaging, queued archive work, archive safety, and theme editing.

### Final changes included in this release

- First-party source ships under Apache License 2.0 with `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
- Compress actions enter the pending-action queue instead of running immediately.
- Pending copy, move, extract, and compress actions share the same queue flow.
- ALZ supports Store/Deflate/BZip2 extraction with CRC verification.
- EGG supports Store/Deflate/BZip2/AZO/LZMA through the first-party parser.
- 7z/CB7 split volumes open from the first part through a concatenated seekable channel.
- Archive preview and extraction include safer path handling, overwrite handling, free-space guards, and cache pruning.
- Main-theme and reading-theme color editors include a palette picker plus HEX/RGB input.

### Known support boundaries

- The older 2.2.4 RAR fallback path is not part of the default Readwide 1.0.0 FOSS-oriented package.
- RAR creation is not implemented.
- ALZ/EGG encrypted, split, solid, and unusual legacy variants remain limited or unsupported.
