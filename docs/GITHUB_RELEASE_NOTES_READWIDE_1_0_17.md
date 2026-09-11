# Readwide 1.0.17

Readwide 1.0.17 adds a persistent tile view to the built-in file browser while keeping the existing compact list as the default.

## File display

- Open the fixed three-dot menu beside the current location title (`Readwide`, `Download`, or a folder), choose **File display**, then select **List** or **Tiles**.
- The choice applies to the normal browser and Recent view and remains selected after restarting the app.
- Tile mode uses two columns in both the normal browser and Recent view, giving covers and filenames more room.
- Switching modes preserves the visible position instead of returning to the first item.
- Tile items keep cover thumbnails, file-type icons, filenames, metadata, optional paths, reading progress, selection, tapping, and long-hold actions.
- Thumbnail requests use a tile-specific size/cache key so a smaller list preview is not stretched into the grid.

The setting is intentionally located only in the fixed upper-right browser menu. It is not added to individual-file or long-hold action menus.

## Notes and highlights

- Select text in the TXT or Markdown viewer and choose **Note** or **Highlight**.
- The original document remains unchanged. Readwide stores annotations separately in its private app data.
- Open **Notes & highlights** directly from the TXT/Markdown reader toolbar (or from More) to return to a saved position, edit a note, or delete an item.
- Duplicate highlight ranges are not stored. Existing exact duplicates are cleaned automatically, and the themed list refreshes immediately after edit or deletion.
- Highlights return after reopening the file; large TXT ranges remain tied to absolute document positions rather than the currently loaded partition.
- Annotation data follows browser move operations and is included in Readwide JSON backups.

## EPUB on tablets and other large screens

- Ordinary EPUBs now use a two-page side-by-side layout in landscape on Android large-screen devices (`sw600dp` or wider), reducing excessively long text lines on tablets.
- Image-page EPUBs retain their existing landscape spread on phones and tablets. Portrait and smaller-phone text layouts remain single-page.
- The existing PDF landscape layout is unchanged.

## Archive reader reliability

- Returning from another app now keeps the active archive/image viewer and its current page instead of exposing the Recent list when Android sends a background-memory callback.
- When memory must be released in the background, Readwide discards decoded bitmaps and reloads the same archive page on return. The existing long-background expiry remains in place for genuinely stale viewer sessions.
- Password-protected ZIPX is no longer an uncertain blanket case: WinZip-AES entries using Deflate64, BZip2, LZMA, or XZ extract with password and authentication verification. The source-built libarchive 3.8.9 backend adds PPMd and Zstandard, while the separate source-built FOSS native codec module adds JPEG and WavPack with the same authentication-before-commit rule.
- RAR remains a conservative, extraction-only claim. The Junrar-free classic-LZ fallback now reuses the six standard VM filter programs and prior block lengths correctly; custom VM programs and complete proprietary-format compatibility are still not claimed.
- RAR 6 archives continue through the established RAR5-container algorithm-v0 path. The first-party fallback now also understands bounded RAR 7 algorithm-v1 streams, including 80 distance codes and extended/non-power-of-two dictionary declarations. It never allocates a declared multi-gigabyte/terabyte dictionary: only 64 MiB of history is retained, and files that truly require older history stop cleanly rather than risking an out-of-memory failure.
- CAB and LHA/LZH now support read-only listing, archive-image browsing, and extraction through the bundled source-built backend. Creation, password handling, and broad multi-volume compatibility are not claimed. The 3.8.9 update also hardens RAR/RAR5 parsing and seek/declared-size handling but does not change the limited-RAR compatibility claim.
- Full extraction now applies one cumulative decoded-byte budget across every entry and backend, including native libarchive output. The ceiling is 128 GiB, but extraction stops earlier when the starting usable storage minus a 64 MiB reserve is smaller; unknown entry sizes cannot bypass either boundary.

## Localization

- Improved annotation terminology across locales and cleaned up Korean and Indonesian reader/file-browser wording.

## Privacy and dependencies

- No new Android permission.
- Runtime archive components changed: the vendored libarchive backend and the separate ZIPX JPEG/WavPack codec module are built from the published source tree, and XZ for Java is declared normally in Gradle. All are FOSS and their corresponding source or license notices are included.
- No document text, note, or highlight is uploaded.
- No ads, analytics, account, cloud sync, telemetry, or `INTERNET` permission.

## Install

- Version name: `1.0.17`
- Version code: `10017`
- Source license: Apache License 2.0
- Source: https://github.com/k1717/Readwide
- APK releases: https://github.com/k1717/Readwide/releases
