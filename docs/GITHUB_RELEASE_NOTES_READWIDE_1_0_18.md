# Readwide 1.0.18

Readwide 1.0.18 improves archive reading and compatibility, EPUB and PDF restoration, thumbnail scrolling, and local file operations.

## Archive reader reliability

- Replaced the fixed ten-minute background close with a numeric Settings option. Enter `0` to disable automatic closing, or `1` to `10080` minutes to choose a timeout. The default is `0` (disabled).
- Background memory cleanup retains the selected archive page so it can be reloaded when returning to the app.
- Direct comic opening respects the internal folder structure instead of grouping equal page filenames from different chapters together: `Part1/1.jpg`, `Part1/2.jpg`, then `Part2/1.jpg`.
- Archive metadata and indexes are reused where supported, with scoped sequential decoding and verified special-7z folder reuse. Directional and two-page prefetch avoid redundant work; large solid folders can still delay the first image.

## Archive compatibility

- RAR3/RAR4 gains scoped mixed LZ/PPMd decoding, streaming standard-filter output, and a CRC/boundary-checked fallback for plain single-volume compressed solid runs with valid starting entries. Existing PPMd-only encrypted and split paths remain separate.
- RAR5/RAR6/RAR7 retains streaming extraction and supported checksum/authentication paths. The former 64 MiB retained-history ceiling is removed: older dictionary pages can spill from bounded RAM into encrypted temporary storage. Large dictionaries can require more disk space and take longer to decode.
- Special 7z/CB7 PPMd/BCJ2 graphs now accept bundled Deflate, Deflate64, BZip2, Delta, and six BCJ filters. Supported split special-forward reading uses segmented input without creating another joined archive copy; covered AES and encrypted-header paths use the same graph machinery.
- ALZ and EGG gain scoped metadata reuse, split discovery fixes, and bounded segment reads. Ordinary plain-TAR members can use direct indexed access.
- Removed the fixed 128 GiB total extraction ceiling, the viewer-only 2 GiB ceiling, and former per-file limits in supported streaming paths. Available-space checks and format/decoder-memory guards remain. The separate 2 GB inbound copy limit for files opened from other apps is unchanged.
- Complete archive compatibility is not claimed. Encrypted/split mixed RAR, stored-member solid runs in the new RAR3 fallback, custom RAR VM programs, and unsupported filter scheduling remain excluded. EGG LEA/encrypted-solid support and complete all-volume preview-cache invalidation remain unfinished.

## File browser and thumbnails

- Thumbnail scrolling prioritizes attached rows and cached images. Off-screen queued requests are removed, cached PNG reads use a separate bounded queue, and completed covers update only matching visible icons.
- Cold archive and PDF cover generation can still take time; these changes do not imply a measured speedup for every format.
- File replacements use staging to protect existing data. Unsafe ancestor-folder pastes and conflicting case-only renames are rejected.
- Document-search scheduling, match counts, and large-TXT indexing are improved.

## EPUB viewing

- Corrected handling of percent-escaped resource references and CFI navigation.
- Media-overlay narration follows foreground and audio-focus state, and cached audio is validated before use.
- Image-page overflow handling remains separate from text-page scrolling. Full EPUB, SMIL, and CFI compatibility is not claimed.

## PDF viewing

- Restoration retains the active viewport rather than replacing it with outdated coordinates.
- Search invalidates outdated results while preserving text and highlight offsets.
- Read-aloud retains inferred word spaces and resumes older text checkpoints from their saved page.
- Scanned/image-only PDFs still have no OCR.

## Localization

- Improved archive support/error explanations and shared read-aloud instructions across all 22 locales, with clearer bookmark hints and Korean settings wording.
- The numeric archive background-timeout field now has gently rounded corners and more space around the number.

## Privacy and dependencies

- No new Android permission or runtime dependency.
- No ads, analytics, account, cloud sync, telemetry, or `INTERNET` permission. Android Auto Backup remains disabled.
- Documents are processed locally. Archive previews may create local plaintext temporary files; encrypted RAR history paging does not encrypt every preview or extracted file.
- First-party source remains Apache License 2.0. Bundled components retain their own licenses, including the ZIPX library's LGPL terms; corresponding source and notices remain included.

## Install

- Version name: `1.0.18`
- Version code: `10018`
- Source license: Apache License 2.0
- Source: https://github.com/k1717/Readwide
- APK releases: https://github.com/k1717/Readwide/releases
- Public APK filename: `Readwide_1.0.18.apk`. Local build filenames may differ.
- With the same project release key, this version updates the GitHub 1.0.17 build in place.
