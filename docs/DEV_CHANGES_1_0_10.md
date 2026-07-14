# Readwide 1.0.10 development changes (vs 1.0.9)

Baseline: 1.0.9 (versionCode 10009), the released source package.

Public-facing docs are updated to 1.0.10: README, CHANGELOG.md, PATCHNOTES.md,
fastlane changelog 10010.txt (en-US + ko-KR), THIRD_PARTY_NOTICES.md,
docs/FOSS_STATUS.md, docs/FDROID_SUBMISSION.md, RELEASE_BUILD.md,
GITHUB_UPLOAD_NOTES.md, BUILD_FIX_NOTES.md, F-Droid metadata
(com.readwide.manager.yml: 1.0.10 build entry + CurrentVersion 1.0.10), and the
new versioned docs docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_10.md,
docs/LICENSE_REPORT_READWIDE_1_0_10.md, docs/SBOM_READWIDE_1_0_10.spdx.json. The
dependency set is identical to 1.0.9 (no new dependency), so the license report
and SBOM carry the same packages as 1.0.9 with only the version stamp changed.
"Current public version" is 1.0.10.

Note: the F-Droid metadata carries real commit hashes for the 1.0.7, 1.0.8, and
1.0.9 build entries; the 1.0.10 entry's `commit` field is still
24de699a8f763e4de768ccaecb8418fbeb3ff498 and must be set to the full 40-character
commit hash that the tag points to (this app's F-Droid maintainer requires a full
hash, not the tag name) once the 1.0.10 tag exists.

Theme: archive image viewer performance + correctness for solid/sequential
archives. Fixes the CB7/7z paging bug where the previous image stayed on screen
(root cause: O(N) per-page re-decompression of the solid stream, so the next
image's extraction was too slow to replace the current one in time, and the
viewer intentionally does not blank the current image until a replacement is
ready). Pure-Java, minSdk 24, no new dependency.

This release also tunes PDF single-page prefetch (directional buffering toward the
reading direction) so rapid PDF page-flipping is smoother. The PDF reader is a
separate viewer from the archive image path; see the PdfReaderActivity entry below.

## Root cause

Solid 7z (Commons Compress `SevenZFile`) and the TAR family
(`TarArchiveInputStream`) are forward-only: there is no cheap random access to a
single entry, so reaching entry N decompresses the shared stream from the start
through N. The viewer's per-image extraction (`ArchiveImageEntryCache.ensureReady`
-> `extractSingleSevenZEntry` / `extractSingleTarEntry`) re-opened the archive and
re-decompressed from the beginning for every page: O(N) per page, O(N^2) over a
full read-through. ZIP/CBZ (zip4j central directory), ALZ, and EGG are
random-access and were never affected; RAR was already served by whole-archive
extraction.

## Approach

Keep one forward stream open per viewing session for the forward-readable types,
read strictly forward, and cache every image passed. Paging forward is then one
decode; already-passed pages are cache hits. Any reader failure or an
out-of-reach request falls back to the existing whole-archive extraction, so the
worst case is the previous behaviour (no regression). RAR is deliberately left on
its whole-archive path; random-access types keep direct per-entry extraction.

## Changes in the tree

### app/build.gradle
- versionCode 10009 -> 10010, versionName "1.0.9" -> "1.0.10". No dependency
  change.

### archive/ArchiveSupport.java
- New public forward-reader API, inserted before `extractSingleEntryDetailed`:
  - `interface ForwardArchiveReader extends Closeable { ForwardEntry nextEntry();
    int read(byte[]); }` and `final class ForwardEntry { String path; boolean
    directory; boolean hasData; }` (path null = skip: unsafe/unreadable entry).
  - `isForwardImageReadableType(Type)` / `isForwardImageReadableType(File)` - true
    for SEVEN_Z and the TAR family (TAR, TAR_GZ, TAR_BZ2, TAR_XZ, TAR_LZMA,
    TAR_Z). The `File` variant additionally special-cases RAR via
    `isRarForwardImageReadable(File)` - true only when
    `LibarchiveNativeBridge.isRarFormatAvailable()` and
    `RarArchiveLocator.detectRarVersion` is 4 or 5; everything else (and any
    exception) is false, so RAR keeps the whole-archive path when libarchive is
    absent or the version is not libarchive-readable.
  - `openForwardReader(File, char[])` - builds on the existing
    `prepareArchiveForRead` / `openSevenZFile` / `wrapTarPayloadInputStream`;
    closes the prepared archive on any failure and propagates IOException. A new
    `case RAR` resolves the volume chain with
    `RarArchiveLocator.collectReadableVolumes(prepared.file)` (one file for a
    single-volume archive, the ordered chain for split RAR), opens a
    `LibarchiveNativeBridge.ForwardStream` over those paths, and returns a
    `LibarchiveForwardReader`. libarchive's own volume input handles split-volume
    concatenation and embedded-SFX offsets, so no temp combine is needed.
  - `SevenZForwardReader` (wraps `SevenZFile.getNextEntry`/`read`/`close`) and
    `TarForwardReader` (wraps `TarArchiveInputStream`; returns a null-path
    ForwardEntry for entries it cannot read, and for symlink/hardlink entries).
- The existing whole-archive image cache for these types is retained as the
  correctness fallback.

### archive/LibarchiveNativeBridge.java
- New `openForwardStream(String[] paths, char[] password)` plus a package
  `ForwardStream` and `ForwardStreamEntry`. `ForwardStream` wraps one open
  libarchive `Reader` (reusing the existing `openReader` / volume-input / SFX-offset
  path) and exposes forward iteration: `nextEntry()` calls `readNextHeader`
  (libarchive can header-skip unread data; current callers first decode-drain
  stateful solid entries, because header skipping alone is unsafe for some RARs) and returns the
  sanitized path, directory/regular-file flags, encryption flag, and size;
  `read(byte[])` calls `Archive.readData` into a reused 64 KiB direct buffer and
  returns the bytes read or -1 at end of the current entry; `close()` frees the
  reader. The `readData` byte count comes from the buffer position the binding
  advances (count 0 = end of entry), per the binding's JNI.
- `openForwardStream` frees the native reader if `ForwardStream` construction does
  not complete. The `ForwardStream` field initializer allocates the 64 KiB direct
  buffer, which can throw `OutOfMemoryError` after `openReader` has already opened the
  native handle; an `opened`/finally guard (matching `openReaderWithFileNames` /
  `openReaderWithCallbacks`) closes the reader and its volume input on that path
  instead of leaking the handle. Success-path behaviour is unchanged.

### archive/LibarchiveForwardReader.java (new)
- `final class LibarchiveForwardReader implements ArchiveSupport.ForwardArchiveReader`
  over a `LibarchiveNativeBridge.ForwardStream`. `nextEntry()` maps a
  `ForwardStreamEntry` to a `ForwardEntry` (hasData = regular file with a non-null
  sanitized path); `read`/`close` delegate to the stream. Any libarchive limitation
  surfaces as an IOException so the sequential reader abandons it and falls back to
  whole-archive extraction; the forward reader is a pure performance path.

### ArchiveImageEntryCache.java
- New package-static `commitReadyImageFile(entryPath, tmpFile, outFile,
  sensitiveCache, verifiedSensitivePaths)`: validates an already-extracted temp
  image (`isUsableFile` + `looksLikeExpectedImage`), `replaceReadyFile`,
  `writeReadyMarker`, tracks the sensitive set, deletes the temp on any failure,
  all under `lockFor(outFile)`. This lets the forward reader populate the same
  preview cache `ensureReady` reads, reusing the shared validation/marker logic.
- `shouldPreferWholeArchiveImageCache` / `isSequentialEntryArchiveType` still
  include 7z + TAR so whole-archive extraction remains the fallback for them.
- RAR forward path: RAR/CBR now uses a libarchive-backed forward reader (see the
  `LibarchiveNativeBridge.ForwardStream` / `LibarchiveForwardReader` notes below),
  gated on the libarchive engine being present and the file being RAR v4/v5, so the
  forward reader is RAR's primary path just like 7z/TAR. The whole-archive path is
  now RAR's fallback. The process-static `WHOLE_ARCHIVE_BULK_DONE` set (keyed by
  path+size+mtime, marked in `ensureReadyByWholeArchiveExtraction` on success) still
  lets `shouldPreferWholeArchiveImageCache` return false for a RAR whose bulk already
  succeeded, so when the fallback runs a later cache miss takes the single-entry path
  and extracts just that member instead of re-extracting the whole archive. The
  single-entry path still falls back to whole-archive extraction on failure
  (`tryEnsureReadyByWholeArchiveExtraction`), and the
  `isLikelyUnsupportedRar3PpmdSolidImage` guard still short-circuits solid PPMd RAR3
  before single-entry. When the libarchive engine is unavailable RAR is not
  forward-readable and uses the whole-archive path as before, so there is no
  regression.

### SequentialArchiveImageReader.java (new, manager package, Closeable)
- Session-scoped forward reader. `openIfSupported(...)` returns null for
  non-forward-readable archives. The unified entry point
  `ensureImageReady(context, archive, entryPath, outFile, password, sensitive,
  verifiedSensitivePaths, sessionReader)` returns an `ArchiveSupport.ExtractionResult`:
  cache hit or forward-reader success -> `ExtractionResult.success()`; otherwise it
  returns the whole-archive `ArchiveImageEntryCache.ensureReady(...)` result
  unchanged. Returning the real result (rather than a bare boolean) is required so a
  failure reason such as `PASSWORD_REQUIRED` survives the forward-reader path - the
  image-open controller reads `result.failure` to drive the password prompt, and an
  encrypted RAR now reaches the forward reader first, fails "password required", and
  falls back; collapsing that to a generic failure would have suppressed the prompt.
  Callers: `ArchiveImageSequenceLoader.loadLazy` uses the result directly;
  `ImageReaderActivity.ensureArchiveImageExtracted` takes `.success`.
- Observability: `ensureOpenLocked` logs (warn level, tag `ReadwideArchiveImg`)
  whether a forward reader was engaged or the archive fell back to whole-archive, and
  `advanceUntilLocked` logs the reason when the reader breaks mid-archive (e.g. an
  encryption or compression variant libarchive cannot decode). Warn level is used so
  the lines survive the release proguard strip of `Log.v/d/i`, letting on-device
  testing on a minified build confirm which extraction path actually runs.
- Instance `ensureExtracted(entryPath)` / `ensureExtracted(entryPath,
  extractBehindFrontier)`: cache-hit fast path, then under a lock re-checks the
  cache. It tracks every image entry the stream has advanced past in a
  `passedEntries` frontier set. A request for an entry in that set is behind the
  open stream (its cache file was evicted, or it never extracted); instead of
  scanning the stream forward to the end - which can never reach a passed entry and
  would then exhaust the reader - an on-demand request
  (`extractBehindFrontier=true`, the default 1-arg overload) re-reads just that one
  member via `extractPassedEntryLocked` (a fresh single-entry
  `extractSingleEntryDetailed` + `commitReadyImageFile`), falling back to the caller
  only if that fails. Prefetch passes `extractBehindFrontier=false`, so a behind
  page returns false immediately and is left for the on-demand path rather than
  running a single-entry decode under the reader lock. For an entry ahead of the
  frontier it opens the reader lazily and advances. `advanceUntilLocked` reads
  forward, draining non-image/dir/already-cached entries to keep the stream aligned,
  extracts images via `extractCurrentLocked` -> `commitReadyImageFile`, records each
  image it passes in `passedEntries`, and returns true once the target is extracted.
  On any IOException/RuntimeException it marks itself broken, closes the reader, and
  returns false (-> caller falls back). MAX_ENTRY_BYTES = 2 GiB per entry, 64 KiB
  buffer. `close()` clears the password and closes the reader.

### ImageReaderActivity.java
- Fields: `sequentialReaderLock`, `sequentialImageReader`,
  `sequentialReaderClosed`. Added `import ...archive.ArchiveSupport`.
- `ensureSequentialReader(File)`: returns null if closed or not
  forward-readable; otherwise lazily opens one shared reader for the session
  (cloning the source archive password under `archiveExtractLock`).
- `ensureArchiveImageExtracted(...)` now routes through
  `SequentialArchiveImageReader.ensureImageReady(..., ensureSequentialReader(archive))`
  instead of calling `ArchiveImageEntryCache.ensureReady` directly.
- `prefetchArchiveImageEntry(...)` changed from static to instance and rerouted:
  it branches through the memoized session reader (`ensureSequentialReader`) instead
  of re-detecting the archive type on every neighbour, so for a forward-readable
  archive (now including libarchive-eligible RAR) it prefetches only through the
  shared reader with no whole-archive fallback. A neighbour the reader has already
  passed is a cache hit; a neighbour still behind the read position is skipped
  (`ensureExtracted(entryPath, false)`) and left to the on-demand path, so a
  background fetch never holds the reader lock for a single-entry decode while an
  on-demand page waits. This is the fix that keeps background prefetch from
  triggering a whole-archive decompress right after the first page. Random-access
  types (ZIP/CBZ, ALZ, EGG) and any RAR that is not forward-readable keep the direct
  `ArchiveImageEntryCache.ensureReady` prefetch.
- Reader-yield for rapid paging: the forward reader is one stream behind one lock,
  so a deep read-ahead could hold it across several image extractions and delay
  the page the user just tapped to. `ensureArchiveImageExtracted` now raises an
  `onDemandReaderWaiters` counter around the forward-readable extract, and the
  sequential prefetch loop breaks out while that counter is non-zero, so the
  on-demand page gets the reader first (waiting at most one in-flight prefetch
  image) and prefetch resumes once the page settles. The forward-readable
  determination is memoized (`sourceArchiveForwardReadable`) and
  `ensureSequentialReader` returns the open reader without re-reading the archive
  signature, so page turns no longer re-detect the archive type each time.
- `onDestroy`: under `sequentialReaderLock`, sets `sequentialReaderClosed`,
  detaches and closes the reader (after the executors are shut down).

### ArchiveImageSequenceLoader.java
- `loadLazy` target extraction: for a forward-readable archive it calls
  `SequentialArchiveImageReader.ensureImageReady(context, archive, targetPath,
  targetFile, password, sensitive, null, null)` (one-shot, bounded 0..target) and
  uses the returned `ExtractionResult` directly; otherwise the existing
  `ensureEntryReady` path. This bounds first-page extraction to the target entry
  instead of decompressing the whole archive. Using the result as-is (rather than
  re-synthesizing a generic failure from a boolean) keeps `PASSWORD_REQUIRED` intact
  for an encrypted RAR that the forward reader could not decrypt, so the image-open
  controller still raises the password prompt. The alternate-entry fallback loop and
  `loadFully` are unchanged (they still fall back via the whole-archive gate).
  Password-protected 7z is detected at list time and never reaches loadLazy
  extraction, so sensitive=false / verified=null is safe there.

### PdfReaderActivity.java (separate viewer, same release)
- Single-page (horizontal) prefetch was symmetric `{+1, -1, +2, -2}`, so only two
  pages ahead were pre-rendered; a rapid forward tap-through exhausted the forward
  buffer after two pages, paused for the on-demand render, then refilled - the
  "two pages, pause, two pages" feel. `prefetchAdjacentSinglePages` now picks the
  neighbour set by `pendingPageSlideDirection`: forward `{+1, +2, +3, -1}`,
  backward `{-1, -2, -3, +1}`, and the old even split only when there is no
  direction yet. Same render count, redistributed toward travel, so the side being
  read toward is buffered one page deeper and no render budget is spent on the side
  being left. The in-flight staleness guard `Math.abs(currentPage - centerPage)`
  was widened 2 -> 3 to match. No change to the render path, PDF_SUPERSAMPLE,
  `singlePageCache` budget, the prefetch thread/renderer, or continuous mode.

## Flow

Open a solid/sequential archive -> loadLazy extracts forward only up to the
opened image (first page fast) -> the viewer opens one session reader -> each page
turn calls ensureArchiveImageExtracted -> ensureImageReady: cache hit, else the
session reader advances forward one image (caching what it passes) -> on any
reader failure, fall back to whole-archive ensureReady. Neighbour prefetch
advances the same reader (forward neighbours decode once; backward/seen neighbours
are cache hits). onDestroy closes the reader.

## Verification still owed (local builds / on-device)

- Build gates: `gradlew.bat clean testDebugUnitTest assembleRelease`.
- CB7/7z forward paging: smooth, and the previous image no longer lingers.
- Large solid 7z: first page appears without a whole-archive wait.
- Backward navigation and far-forward jumps: seen pages are instant (cache hits);
  a far jump advances the reader (caching the pages in between).
- Corrupt/non-standard 7z: the reader fails and the whole-archive fallback still
  opens the image (no regression).
- TAR-family / CBT (including gzip/bzip2/xz/lzma/compress variants): same forward
  path; confirm symlink/hardlink and non-image entries are skipped without
  breaking stream alignment.
- Password-protected 7z: the existing password prompt / extraction path is
  unchanged (the forward reader either has the password or fails and falls back).
- RAR/CBR forward reader (device-confirmed for RAR5 and the password prompt; the
  rest of the matrix still owed): non-encrypted RAR4/RAR5 engages the forward reader
  (warn-level `ReadwideArchiveImg` log shows "engaged"); an encrypted RAR engages,
  fails "password required", falls back, and now raises the password prompt -
  entering the password then reads it (confirmed working). Still owed across the
  matrix: split-volume RAR, solid RAR, embedded-SFX, and confirming encrypted RAR
  decodes through the forward reader once the password is supplied (vs falling back).
- RAR cases libarchive cannot decode on its own (e.g. some encryption/compression
  variants): the reader breaks mid-archive (logged with the reason) and the
  whole-archive fallback still opens the images (no regression).
- Residual: if both the reader and the whole-archive fallback fail for a
  pathological archive, the previous image stays on screen - this is the existing
  intentional non-blanking behaviour in `loadImageAsync`, not a new regression.
