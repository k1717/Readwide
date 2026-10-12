# Readwide 1.0.20 release readiness

Release date: **2026-10-11**; version `1.0.20 / 10020`.
Release APK assembly, local-test compilation and Android-test APK assembly
completed successfully on that date. A subsequent signed release build passed after
build-setting/native warning cleanup, including v2 signature verification.
Subsequent system-bar/WebView edits have static review only. Tests were not executed.

## Completed release preparation

- Version/application identity and release documentation identify Readwide 1.0.20.
- CHANGELOG, PATCHNOTES, development notes and GitHub release notes use feature/subsystem sections. English/Korean store summaries retain the short release-summary format.
- Source and component notices are included. First-party code remains Apache-2.0; separate component licenses and corresponding-source requirements remain in force.
- Source packaging excludes generated/private files, preserves portable paths and executable modes, and refuses to overwrite existing ZIPs.
- The successful build includes the Home edits and the test I/O/builder corrections. No test suite was executed; compiling test code or building a test APK is not a passing assertion result.
- The subsequent signed release build includes Java/Gradle settings, shortened native staging paths and decoder-only declarations. It still displays deprecated API warnings; successful assembly is not zero-warning evidence.
- Subsequent document/PDF system-bar and WebView scale fixes add eight instrumentation regression methods. These changes have static review only, without follow-up build or test results. TXT canonical viewport/page geometry remains unchanged; required legacy APIs and gesture minimum-zoom probes are retained, without global warning suppression.

## Before publishing an installable APK

- Verify the already-built APK's version, application ID and signing certificate; retain its SHA-256. The public filename is `Readwide_1.0.20.apk`; local filenames are unrestricted.
- Confirm release debugging is disabled, `INTERNET` is absent, `allowBackup` and cleartext traffic are disabled, and required license assets are present.
- Record test-suite and device results separately from build success. New test sources alone are not executed-test evidence.
- Run representative use checks below when validating the APK. No source-only audit guarantees bug-free behavior, measured performance or complete format compatibility.

### TXT reading and persistence

- Open/reopen the known large TXT with unchanged font/settings; verify the saved exact count, progress and context return. Check source replacement, encoding/display-rule changes and imported settings invalidate only incompatible caches/hints.
- Cross partitions in both directions using slow/fast scroll, inertia, taps, automatic turning, slider, search, Contents and line/percentage moves. Preserve character continuity and correct page/row labels; test first/last partitions and true EOF.
- Start a new drag/tap/jump during pending prefetch, layout or exact-index adoption. The newest owned target must win without old-window autosave, blank text, stale seek preview or duplicate jumps.
- Recreate/background/trim the Activity during initial load, partition handoff and pending navigation. Check picker/SAF ownership, matching/missing context, stale source metadata and out-of-window initial destinations.
- Change font, margins, spacing, overlap and partition policy while stopped or loading. Preserve saved characters/context and existing bookmarks; do not reuse incompatible page/pixel hints.
- Check half-visible-row selection, tiny viewports, overlap-only EOF handling, coordinate overflow rejection, zero/short reads and worker allocation failures. Failure UI must belong to the current load.
- Check Contents heading options/custom patterns, manual/hidden records, rescans, cancel/reset/save, source revision changes and long labels. Detection remains heuristic.

### Settings and backup recovery

- Export/import supported settings, bookmarks, positions, annotations and durable Contents records; compare merge/replace results and old backups without Contents.
- Reject malformed/truncated JSON or UTF-8, invalid numeric types/ranges, duplicate/conflicting identities and invalid custom patterns before publishing partial state.
- Inject write/close/commit failures and failures after earlier stores changed. Verify rollback, retained recovery data and meaningful error reporting rather than false success.
- Restore on another device with unavailable folders, missing imported fonts and different storage grants. Preserve valid local fallbacks; do not create arbitrary paths or guess record relocation.
- Confirm document/font/theme files and automatic caches are not exported. Reapply restored display settings without saving old rendered page/pixel hints as current progress.
- Check PIN/unlock, Home navigation, settings reset/cancel and lifecycle changes while import or folder selection is pending.

### Images, documents and read aloud

- Leave archive JPG/PNG and GIF/WebP open during a long app switch, with/without memory trim. Retain the accepted page and pan/zoom or reload its released surface without requiring a page turn.
- Check process recreation, spreads, pending decode/prefetch, corrupt pages and later password-protected pages. Failed requests must not replace accepted reading progress or trigger background password dialogs.
- Change/delete a later volume after warming image/thumbnail caches. Retire stale sessions/results; check cancellation, low memory and close during slow initialization.
- Exercise rapid portrait PDF paging, continuous scrolling, spreads, zoom, sliders, search and restoration. Measure performance separately; no timing result is inferred from code changes.
- Compare DOC/DOCX/HWP scientific scripts, tables, styles, spacing, fields and malformed inputs against representative source documents. Test EPUB resource paths, CSS priorities, vertical writing, point CFI and media-overlay/audio-focus behavior.
- Check Markdown code/fence anchors, normalized searches, text-to-speech checkpoints and cross-document stale callbacks. Full renderer/format parity remains excluded.

### Archive integrity and file operations

- Check listing, selected extraction, bulk extraction and forward viewing independently; a passing route does not validate every other route or native fallback.
- Verify correct/wrong/missing passwords, authentication/CRC/trailer failures, truncated/extra data, short reads, cancellation, low disk and close/cleanup failures preserve prior output and retire failed decoder state.
- Cover split entry from first/middle/final volumes, gaps, ambiguous aliases, zero-byte parts, changed cached parts, large directory metadata, duplicate names and Unicode/legacy-name precedence.
- RAR: cover stored/LZ/PPMd mixed/solid streams, AES/header encryption, algorithms 0/1, dictionary transitions, empty/reset/control-only tails, admitted VM chains and rejected range/order cases. Unknown-size AES stored recovery requires a unique valid checksum result.
- 7z/ZIPX: check declared coder properties/graph arities, encoded/external headers, solid-prefix handling, optional LZMA endings, PPMd finalization/freeze revisions and source-built native codec paths. Actual missing external dictionaries must remain rejected.
- ALZ/EGG: test empty/comment-bearing and split metadata, both ALZ BZip2 variants, legacy password candidates, EGG solid/block ownership, LEA/AES authentication, AZO allocation/cancellation and unsupported encrypted names.
- CAB/LHA: check linked CAB continuations and supported Amiga byte-split framing with producer archives. Synthetic resources do not establish all compressed multipart cases.
- TAR/compressors: check sparse logical sizes, Unicode names, duplicate identity, special-member exclusion, end markers, concatenated frames and outer trailer errors. Early selected reads do not certify an unconsumed archive tail.
- Verify no-follow output traversal, file/directory collision preflight, replacement staging, source-volume protection, rollback and retryable cleanup. Test queued/paused/cancelled operations and UI teardown without losing committed results.
- Keep free-space protection and representability checks enabled. Their presence is compatible with removal of fixed payload-size caps.

## F-Droid source review

- Preserve pinned corresponding sources and notices; no proprietary UnRAR engine or optional local jars are part of the default source package.
- The direct-source license report/SBOM is not a resolved-transitive license audit. Review the resolved dependency graph and packaged notices for repository submission.
- The checked-in metadata stops at 1.0.19. Start from current upstream metadata and add 1.0.20 only against the real final 40-character commit hash. Do not invent a commit or overwrite upstream with the historical mirror.
- Local build success is not F-Droid build acceptance, signing, approval or publication. See [submission notes](FDROID_SUBMISSION.md).

## Deliberately deferred boundaries

- No universal archive compatibility, arbitrary external dictionary recovery, encrypted EGG filename implementation or general LHA multi-disc semantics.
- No RAR link/file-copy restoration or execution of 7z anti-item deletions; unsupported VM ranges/order remain errors.
- No complete Office/Hancom/CSS/SMIL/CFI fidelity, PDF OCR, unlimited memory or process-death filesystem transaction.
- Metadata identities do not detect same-size/same-time external edits or make external writes atomic.
- Cross-device backups do not transfer source documents/assets/storage permissions or automatically relocate path-bound records.

## History and privacy

Historical focused host/fixture reports retain their named scope. Do not sum repeated
neighbor-suite executions or present source-only regressions as passing device tests.
Keep signing keys, passwords, private samples, backups and local paths out of public
packages. Use a fresh ZIP filename and preserve earlier archives.

Build instructions: [RELEASE_BUILD.md](../RELEASE_BUILD.md).
Implementation: [development changes](DEV_CHANGES_1_0_20.md) and
[code map](CODE_MAP.md). Format details: [archive boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).
