# Readwide 1.0.21

Readwide 1.0.21 adds EPUB typography, contents and note controls, expands archive handling, and improves viewer navigation, saved-state recovery and file operations.

## EPUB viewing

- Set EPUB text size independently of TXT. Choose Book default or 120–200% line spacing, and Book default, start-aligned or justified paragraphs in Settings and More.
- Retain the previous size during migration and include the options in backup/import/reset. Fixed/image/vertical layouts and protected book formatting keep their existing restrictions.
- Use rounded font-style pickers and repeated size controls. Mixed-layout spreads apply zoom and reset separately to each pane.
- Open searchable EPUB 3/NCX contents near the current section, with a section-list fallback when navigation is unavailable.
- Read marked footnotes/endnotes in a rounded text popup without moving the underlying page. Ordinary links retain existing behavior; rich or dynamically generated notes are not reproduced.
- Show within-section scroll progress, such as `3 (42%) / 20`, for ready, scrollable, horizontal single-pane text. Other layouts retain plain section ranges; these are not screen-page totals.
- Keep navigation, script work and saved positions tied to the active document and pane. Retire failed ZIP/navigation work without overwriting newer reading state.
- Match the bottom system-bar background to the open menu and restore the reading palette on close. Correct physical side backgrounds in right-to-left and landscape cutout layouts without changing page calculation.

## TXT and document reading

- Open TXT Contents near the current heading, fold detailed search conditions without resetting them, and explain empty bookmark lists.
- Release rejected Contents/partition tasks so controls can recover without replacing visible text.
- Correct the TXT cutout policy for the reported Galaxy S20 black strip when hiding the status bar. Device confirmation remains pending.
- Reject stale bookmark/annotation edits and keep failed note/highlight saves available for retry.
- Close the affected document reader safely after Android 8+ renderer termination, retaining the last saved position. Unsaved movement is not reconstructed automatically.
- Correct stale Markdown totals after content shrinks and accept supported document MIME types without requiring a matching filename extension.
- Preserve exact TXT paging, partition sizes/overlap, half-visible-row selection and existing bookmark coordinates.

## PDF and image viewing

- Connect PDF menu zoom/reset to the displayed single-page/spread view while retaining continuous-mode controls and scale limits.
- Apply keyboard/accessibility slider changes immediately; touch dragging still commits on release.
- Add validated image page-number entry, consistent spread counters and a compact rounded popup that retains input through rotation.
- Keep rounded failure-card actions visible in short windows, and clean temporary PDF spread images after failed page operations.
- Resume unfinished local-image loads after rename. Retire failed/exhausted archive-image decoders while retaining verified caches and existing fallback.
- Reuse forward-reading scratch with sensitive-data clearing and avoid redundant seeks in verified 7z folders. These are allocation/I/O improvements, not measured speed claims.

## Archive compatibility

- Add streaming ALZ method-3 (Obfuscated Deflate) decoding with existing split, ZipCrypto, decoded-length/CRC and output-rollback checks. Methods 0/1/2 retain their existing decoders.
- Reuse ALZ fixed Huffman trees, remove temporary dynamic-table slices and batch stored/repeated output while preserving overlapping references, ring history and cancellation boundaries.
- Handle RAR files that start with PPMd and switch internally to LZ while retaining model, dictionary, VM filters and solid state. Preserve stored RAR3/RAR4 solid-flagged splits and reject incompatible legacy decoder generations.
- Extend checked RAR VM combinations and scoped raw-prefix carry across planned solid successors. Complete filtered results are not split between files, and compatibility with every producer/filter combination is not claimed.
- Read checked prefixed 7z content without executing stubs. Reject malformed encoded headers, confirmed payload corruption, short output and incomplete BCJ2/intermediate-decoder termination.
- Verify unused 7z folders before bulk extraction and check cancellation before committing output, including empty files and final source-validation boundaries.
- Read bounded EGG SKIP records and scoped block-header extras while preserving the legacy interpretation of ambiguous layouts. Encrypted EGG filenames remain unsupported.
- Recheck EGG/ALZ source volumes across framing, reader creation, password probes and output acceptance. Revalidate EGG solid spools before reuse; restore affected pending output after failure while retaining earlier verified files. Metadata checks do not lock files or detect every same-metadata edit.
- Carry Cancel/Pause through supported EGG/ALZ decoder preparation and buffered work, custom RAR VM execution and 7z metadata reads. Individual blocking/native operations can still delay cancellation.
- Reuse bounded BZip2 input buffering for EGG/ALZ/7z/ZIPX, remove redundant EGG checksum work and reduce RAR table/filter scratch allocations without removing integrity checks.
- Recognize `.pma` and CRC-bearing level-0 PMarc stored PM0. Compressed PM1/PM2 remain unsupported.
- Keep free-space protection, cipher continuity and existing failure cleanup. See the [development notes](DEV_CHANGES_1_0_21.md#archive-compatibility) for exact support boundaries; complete archive compatibility is not claimed.

## Settings, saved data and files

- Check settings-reset persistence, attempt rollback after failure and release failed backup/import operations instead of reporting false success.
- Retain matching bookmark bindings when opening duplicate copies and allow later reading-state saves to retry after background failures.
- Protect originals after unencodable or changed-source TXT edits. Copy/move operations retain changed or newly created entries and refresh partial results.
- Resolve storage-provider metadata off the main thread and reject obsolete opens.
- Return from read-aloud notifications to the same live session; replacement documents receive separate preparation state.
- Use 13sp Pinned folders/Recently read headings with unchanged boxes. Clear Recent selection on search/type changes or confirmed history clearing, not ordinary refreshes.
- Update compatible platform APIs while retaining older-Android behavior and existing transitions.

## Privacy and dependencies

- No new permissions, network services, analytics or dependencies.
- First-party source remains Apache License 2.0. Bundled components retain their own licenses, corresponding source and notices.
- Archive previews can still create local plaintext temporary files; clearing scratch does not encrypt every cache or extracted file.
- Dependency versions, signing configuration and the existing local-data policy remain unchanged.

## Install

- Version name: `1.0.21`
- Version code: `10021`
- Release document date: `2026-10-24`
- Android application ID: `com.readwide.manager`.
- Source: https://github.com/k1717/Readwide
- APK releases: https://github.com/k1717/Readwide/releases
- Public APK filename: `Readwide_1.0.21.apk`. Local build filenames may differ.
- Updates over 1.0.6 and later require the same project signing key.
- Source-package checks are complete. The latest code and added regression sources have not been compiled or executed; see [release readiness](RELEASE_READINESS_1_0_21.md) before publishing an installable APK.
