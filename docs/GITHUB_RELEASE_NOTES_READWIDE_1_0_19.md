# Readwide 1.0.19

Readwide 1.0.19 adds collapsible pinned folders and improves settings, reading controls, search, backups, and archive handling.

## Home and settings

- The EPUB font selector in Settings uses the shared rounded dialog and current theme colors.
- Tap **Pinned folders** to show or hide its horizontally scrolling list. Home headings take up less space, and the arrow stays aligned with the title in each language.
- Switching between system, light, dark, and dark navy themes keeps Settings open without repeated flashing.
- Reset and backup restore show the new values instead of old control states or color drafts. Canceling button-order changes keeps the saved order. Dropdowns also preserve imported values when Settings rotates during import.
- Theme edits and PIN setup keep unfinished input through rotation. The PIN screen scrolls when space is limited, and canceled setup leaves the lock switch in the correct state.
- Failed theme saves or deletions keep the dialog open for retry. View settings also retains the current TXT file when opened from its reader.

## EPUB and document reading

- Markdown read-aloud locates its starting passage with less repeated scanning.
- Document search creates fewer temporary objects. Markdown scrolling combines pending position updates and ignores results from an earlier page load.
- Reduced repeated scanning when searching document text containing many ampersands.
- Keep your reading position after rotation, font or theme changes, spacing adjustments, and closing search.
- Vertical Japanese bookmarks retain the opening characters in their previews. Search brings matches in later columns into view. Rapid searches across chapters keep the selected result and displayed passage together.
- Read-aloud follows vertical columns and highlights the correct occurrence of repeated sentences, including after chapter changes.
- Links in two-page view open the intended page. Double-tapping an enlarged page resets the pane you touched. A delayed error from the previous document cannot close the current book.
- Read-aloud media buttons respond once per press. Play and Pause keep the requested state, and pausing cancels a pending restart.

## PDF and image viewers

- PDF search highlights look up the current page without scanning every result in the document.
- Rapid PDF taps remain unrestricted at fit size. When enlarged, double-tapping resets the page instead of turning it; an outward swipe from the page edge can turn the page. Page labels remain at the target while you hold the page slider.
- PDF loading prioritizes visible and nearby pages, reuses pending work, and skips obsolete requests. Cached pages keep their intended display size when rendering resolution changes.
- Continuous PDF reading restores the visible page and position, including gaps between pages. Search updates no longer undo a manual pan.
- Double-tapping an enlarged image resets zoom without also changing pages. Image loading no longer moves the page slider while you are dragging it.
- Document, PDF, and image viewers honor **Keep screen on**. TXT brightness updates after settings reset or restore.

## TXT, backups, and file operations

- Selecting many files scans the list once. Sorting reuses the file details already loaded in the background.
- TXT search counts and navigation agree, including dense results, regular expressions, and wrapping to the previous result.
- Reloading TXT uses the current reading position instead of an old launch bookmark. Imported display rules take effect, and invalid replacements no longer interrupt loading. Opening another TXT file no longer jumps to the previous file's position, and unfinished loads preserve saved positions.
- The rule editor keeps file-specific targets, checks invalid expressions before saving, and explains the 50-rule limit.
- Backup import rejects incorrectly typed settings before applying them and reports save failures. Backup operations run in the background and keep confirmation dialogs through rotation.
- File copy, move, and delete handle cancellation more consistently. Folder operations avoid following symbolic links, and natural sorting handles digits from different languages.

## Archive handling

- Archive filtering sorts only the matching entries while keeping the existing folder and image order. Long archive paths require less copying when preparing previews and image order.
- Plain RAR4 archives can contain independent stored files alongside supported compressed runs. Empty folders are preserved, and eligible archives can use the fallback decoder for image browsing.
- Added 7z ARM64 and RISC-V filter support. Small LZMA/LZMA2 streams no longer need to allocate the full dictionary size declared by the archive.
- GZIP, BZip2, XZ, and framed LZ4 read all concatenated compressed members, including a TAR stream split across them.
- Improved checks for damaged archives and incomplete temporary files across RAR, 7z, ZIP/ZIPX, ALZ, EGG, and TAR. Failed extraction protects the current output file and allows temporary-file cleanup to be retried.
- If replacing an archive folder and restoring it both fail, the original backup is retained and its location is reported.

RAR still excludes encrypted or split mixed-compression archives, stored files used as solid history, and custom VM programs. 7z filter combinations and split naming remain limited; EGG LEA and encrypted solid archives remain unsupported.

## Privacy and dependencies

- Updated the interface libraries and HWP reader library.
- No new Android permission, ads, analytics or account requirement.
- Files, bookmarks, annotations and reading history remain on the device.

## Install

- Version name: `1.0.19`
- Version code: `10019`
- Source license: Apache License 2.0
- Source: https://github.com/k1717/Readwide
- APK releases: https://github.com/k1717/Readwide/releases
