# Readwide 1.0.11 GitHub release notes

Readwide 1.0.11 is a large reader-and-archive release. It adds read-aloud (text-to-speech) to the document viewer (EPUB, Word-family, HWP/HWPX, Markdown) and the PDF viewer, opens legacy `.doc` Word files, extends archive support with Zstandard/LZ4 and several previously-unsupported 7z and RAR encryption/compression combinations, and renders embedded images in HWP/HWPX documents. It keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. The local-first privacy baseline is unchanged, and this release adds no new dependency.

## Highlights

- **Read-aloud everywhere it makes sense.** Text-to-speech, previously only in the TXT/Markdown reader, now also works in the document viewer (EPUB, `.docx`, legacy `.doc`, HWP/HWPX, and Markdown) and the PDF viewer (text-based PDFs). In Markdown - which renders as one long scrolling page - playback follows along by scrolling to roughly the passage being read. It uses the Android `TextToSpeech` API, so any installed engine works, including neural engines exposed as system TTS. It offers language and voice selection, speed and pitch, adjustable phrase length and pause reduction for neural voices that over-pause or lag, pause/resume, a sleep timer, a playback notification with media controls, and continuous reading that turns the page across boundaries with ahead-of-the-seam queueing so a high-latency neural voice does not pause between pages. A read-aloud button sits next to the bookmark button in each viewer's toolbar, and "continue reading aloud" from the main screen resumes at the saved spot (now for documents and Markdown too, not just plain text). Scanned/image-only PDFs report that they have no selectable text instead of playing silence. Two neural-engine issues are also fixed: read-aloud no longer goes silent when the engine reports the selected locale as unsupported (it falls back to the engine/device default voice), and continuous reading no longer leaves an audible gap at page boundaries.
- **Legacy `.doc` documents open.** Word 97-2003 binary `.doc` files, previously recognized but not rendered, now open through a self-contained pure-Java compound-file reader (piece table, both UTF-16 and 8-bit/compressed piece forms) shown through the same paginated viewer as `.docx` and HWP, so paging, search, bookmarks, and read-aloud work the same way. No new dependency and no new permission.
- **HWP/HWPX embedded images render.** Raster pictures (PNG/JPEG/GIF/BMP/WebP) embedded in HWP and HWPX documents now render at their authored size in the rendered-layout path. Pictures in formats the reader cannot display (WMF/EMF/OLE) show a placeholder frame at the original size instead of silently disappearing.
- **More archives, more encryption cases.** Zstandard and LZ4 are now supported in the TAR family and as single-compressor streams (`.tar.zst`/`.tzst`/`.tar.lz4`/`.zst`/`.lz4`), using the already-bundled codecs. 7z PPMd and BCJ2 archives now extract (including AES-encrypted ones, via a first-party PPMd decoder and BCJ2 reader), and Deflate64 coverage was verified. EGG gained real-ALZip-file fixes, split-volume support, solid-archive extraction, ALZ 4.x bzip2 decoding, and AES-encrypted-entry extraction. RAR5 header-encrypted (`-hp`) archives now open through the first-party header-decryption path. The RAR and 7z encryption boundaries were re-audited against real WinRAR/p7zip archives, and the error reporting was made precise; RAR5 header encryption and covered RAR5 stored and v5.0 compressed AES cases are first-party-verified to extract byte-for-byte.
- **Smoother reading and paging.** Continuous paging through images inside sequential archives is smoother, the recent-files list swipe was refined, EPUB images are kept inside the screen, and HWP paragraph formatting was improved. A TXT-reader bug where switching files could resurrect the previous file's content was fixed, and a large-TXT autosave case that could store an inconsistent page/position during a partition switch was corrected.
- **Local-first privacy baseline (unchanged)**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.11
versionCode 10011
applicationId com.readwide.manager
```

1.0.11 keeps the `com.readwide.manager` application ID introduced in 1.0.4 and the `readwide` release signing key introduced in 1.0.6, so it installs in place over 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6.

## Dependencies

No new dependency. PDF read-aloud reuses the `pdfbox-android` library already bundled for in-document PDF search; the dependency set is identical to 1.0.10. See `THIRD_PARTY_NOTICES.md`, `docs/LICENSE_REPORT_READWIDE_1_0_11.md`, and `docs/SBOM_READWIDE_1_0_11.spdx.json`.

## Migration for existing users

- Updating from 1.0.6, 1.0.7, 1.0.8, 1.0.9, or 1.0.10: 1.0.11 uses the same signing key, so it installs in place as a normal update; no uninstall or backup step is required.
- Updating from 1.0.4/1.0.5: those used the previous signing key, so Android will not install 1.0.11 over them (signature mismatch). Uninstall the previous version, install 1.0.11, then import a JSON backup to restore bookmarks, reading positions, themes, and settings.
- Coming from TextView Reader or an older Readwide build with the `com.textview.reader` application ID: export a backup (`readwide_backup_<timestamp>.json`) from the old app, install 1.0.11, then import the backup to restore bookmarks, reading positions, themes, and settings.

## Known boundaries

- HWP/HWPX rendering is text-first with a partial rendered-layout path; it is not Hancom-compatible layout parity. Vector/OLE objects, drawings, charts, and equations are not rendered (WMF/EMF/OLE pictures show a placeholder frame).
- RAR/CBR is read/extract only, and not every encryption/compression combination is supported; unsupported cases fail cleanly. See `docs/RAR_7Z_SPLIT_ENCRYPTION_REVALIDATION_READWIDE_1_0_11.md` for the audited boundaries.
- Read-aloud follows pages; it does not yet highlight the spoken word on the WebView or PDF page.
