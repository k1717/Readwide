# Readwide 1.0.22 current source status

## Source identity

- Android identity: `com.readwide.manager`, `versionName "1.0.22"`, `versionCode 10022`.
- This version adds TXT indexing/navigation and task-startup fixes, saved-data load protection, backup task ownership, settings recovery, shared-storage folder access, image-control touch corrections and archive input corrections to 1.0.21. Dependencies, declared permissions and signing configuration are unchanged.
- See [1.0.21 source status](CURRENT_SOURCE_STATUS_1_0_21.md) for inherited codec coverage and runtime limits, and [development changes](DEV_CHANGES_1_0_22.md) for changes in this version.

## TXT corrections

- Exact indexing clears the matching pending request if submission is rejected, startup fails with a memory or stack error, or the result cannot be delivered. A later request can retry the same signature. Late work cannot replace a newer or completed index.
- A backward tap after a blank or short line returns to the current page start before moving to the previous page, matching normal TXT. Forward selection and page-anchor construction are unchanged.
- Regression sources were added but have not been compiled, run or checked on a device. The page model revision, saved indexes, partition rules, half-visible-row selection and existing bookmark coordinates are unchanged.

## Loading and backup corrections

- Failed bookmark/reading-state reads no longer become empty or partially loaded stores that the next save can overwrite. Later access retries the read; only a complete parse enables edits and persistence. Missing files still create empty stores.
- Backup export and import stop if either local store cannot load. Replacement import is also blocked because rollback needs complete originals. This preserves unreadable files but does not repair them or add a recovery interface.
- Rejected TXT load/search work is cancelled, matching pending state is cleared, and stale callbacks cannot replace newer work. Failed initial loads stop the spinner and retain the error. Highlight startup failures retry on changed input or range, not on every draw.
- Rejected queued backups cannot run later. An already-started operation retains its lock through commit/rollback, and only that task can release the lock or publish its result.
- Focused regression sources were added. They have not been compiled or executed; synthetic failure cases do not establish recovery under sustained device-wide memory exhaustion.

## Settings corrections

- App information shows the localized title and the installed build's version name. The update link keeps an 8dp combined margin below the header. Binding is repeatable and does not change section visibility or the link action; visual and accessibility checks remain pending.
- Backup help and reset use 8dp facing vertical margins. Export/import have 4dp facing margins each, totaling 8dp. Button sizing, insets and the gap before the next section are unchanged; this only changes four layout attributes.
- Tap-profile entries use three aligned 14sp columns for viewer, ratios or Same as TXT, and orientation. Ratios or Same as TXT are centered, and orientation has 6dp end padding that mirrors in RTL. Each column wraps to at most two lines, then uses end ellipsis. Each rounded row is one accessible action with the full spoken summary. Registered-folder entries use left-aligned 14sp text, limited to two lines with end ellipsis; the action panel retains the full location. Tap rows use a 36dp minimum height and 4dp top/bottom padding; folder rows use 44dp and 6dp respectively. Both retain content-driven height. General action buttons are unchanged. The Tap zones entry has an 8dp bottom margin. The preview keeps its existing 12dp top gap; a separate 12dp margin separates the boxes from the first percentage label below them.
- Settings now groups reading controls, button order, files/archives, security/folder permissions and backup/reset separately. View settings contains default TXT/Markdown size, TXT layout, EPUB typography and reader display. TXT partition/overlap options and longer explanations open in rounded dialogs.
- Tap zones can follow TXT or use independent orientation and proportions for EPUB, PDF, Markdown, Word/HWP and images. Existing documents follow TXT; images keep horizontal 33/34/33. Inheritance changes retain custom values, and backup/reset covers the new keys. Page calculations, gesture direction and bookmark coordinates are unchanged.
- Twenty-eight focused tap/image host tests passed. Scoped SDK compilation covered the related production classes and 78 Android regression methods using unrelated-dependency stubs and current-version build constants; these methods were not run. Six XML resources compiled without linking. Device checks remain for two-line labels, enlarged text, LTR/RTL alignment, inheritance toggles, all viewer touch paths, rotation, backup restore and popup appearance/accessibility. This is not a full app build or runtime result.

- The archive timeout input uses its saved preference after recreation, so an old hierarchy snapshot cannot undo reset/import. Custom-color HEX drafts retain their existing restoration behavior.
- Reset and direct preference import attempt rollback after memory or stack errors during persistence. Reset restores only its listed keys; a failed reset keeps the confirmation open. Planning errors in direct import do not trigger a write or rollback.
- EPUB custom-font discovery handles startup/discovery errors, cancels rejected queued work and rejects stale callbacks. The error stays in the picker alongside built-in choices; reopening starts another attempt.
- The timeout-restoration, reset/import resource-error and font-discovery failure regressions remain unexecuted; the tap/image results above do not cover them. Rollback can itself fail, and ongoing memory exhaustion can prevent error feedback or UI creation. No crash journal or guarantee of recovery from process death is added.

## Storage access and file operations

- Raw folder loads now finish after startup or worker runtime, memory and stack failures. The matching request clears partial rows and ends loading without treating every failure as a permission problem. Existing refresh/resume paths can retry. New loads invalidate older folder-signature validation, including at the same path; failed validation enters the normal refresh path. Sorting and progress batches are unchanged.
- Authorized-folder queries now cancel obsolete tasks/provider requests and discard older queued queries on navigation, replacement or pause. Late and duplicate completions cannot replace the current list. Retry is available after a listing failure and never replays a file operation. Providers that ignore cancellation can still hold the single query worker; no forced timeout is added.
- Settings > Security and folder permissions > Folder access shows registered folders with Add folder and per-folder open/revoke actions in a rounded popup. Internal pages share the visible window and dim layer; previous-page callbacks cannot act after a transition. Long-press opens the picker directly. The old single folder carries over, earlier grants survive new registrations, and the last selection is stored separately. Missing permissions retain the entry for reauthorization. Working raw-storage shortcuts and External Storage's long-press permission action remain; there is no standalone sidebar entry or automatic mutation retry.
- The authorized-folder browser exposes copy, move, rename, delete and new-folder actions with rounded confirmations. Creation/copy never overwrites a matching name. Copies compare complete output bytes, retain the pre-verification output snapshot, and recheck source metadata, all copied files and folder membership before acceptance. Native provider moves are preferred; fallback folder or multi-parent moves keep the original alongside the verified copy. Deletion requires write permission and provider support, then verifies absence. Confirmed provider folder deletion can include every child.
- A ViewModel retains operations and clipboard state through rotation; source/destination grants remain pinned until work ends. Each open browser also pins its folder independently of the remembered folder. Archive-open copy failures clear pending state and allow retry; rejected queued opening tasks are cancelled. Raw operations do not inherit SAF permissions: retry in Folder access. Failed raw transfers, including unwritable-destination preflight, delete preparation, rename and new-folder creation offer that route without replaying the previous operation.
- Raw deletion uses a confirmed identity/metadata/tree snapshot, excluding new children. Rename dialogs also bind the selected entry, and image-viewer delete/rename reject replaced files. Queued transfers recheck their selected source and every overwrite-target entry. Backup cleanup uses a fixed captured list and preserves changed contents or parent paths. Partial moves retire from the retry queue and retain remaining sources without rebinding the entire tree. Their warning asks for both locations to be checked: the destination may have disappeared or failed verification. New-folder names cannot traverse outside the captured parent.
- Raw copies reject growth beyond the captured size. Overwrite confirmation captures its destination before the dialog; queued work rechecks that approval and the destination parent. Provider-to-viewer cache copies check known sizes and timestamps and retain the previous cache after cancellation or detected change.
- SD discovery refreshes on resume. Opening the sidebar no longer forces a rebuild; unchanged entries and dimensions cause no row notifications or layout updates. Explicit theme updates reuse existing holders.
- API 30+ volume directories supplement legacy root discovery for attached SD/USB storage. Canonical roots are deduplicated and primary-storage aliases excluded. The path policy also covers internal shared storage; app-private internal paths outside those roots do not qualify. USB drive/SSD/HDD availability depends on Android exposing a mounted filesystem or document provider.
- Provider browsing and mutations reject loading/error metadata. Lost create/rename replies and unconfirmed deletion replies remain uncertain; a complete absence query can still confirm deletion after a failed reply. Rename and transfer recheck source metadata and parent membership after collision lookup. Fallback source deletion checks current remove-from-parent, partial and virtual flags, and checks parent membership after destination verification. Unsafe deletion retains both the verified copy and original; explicit delete behavior is unchanged.
- Extension renames may change MIME type and document ID; name, file/directory kind, known file size and parent membership remain checked. Provider dialogs cannot act after replacement, navigation or dismissal. Failed/cancelled paste retains its selection and source grant; completed or uncertain transfers clear only their captured selection.
- Provider failures or uncooperative cancellation can leave an unverified destination; cleanup keeps entries it cannot safely identify or that moved to another parent. Concurrent same-metadata edits and final-check races remain possible. Fallback folder moves require manual source inspection/removal, partial raw moves can leave saved references needing correction, and changed overwrite backups may remain in transfer directories without a recovery interface. No cross-provider atomic transaction or crash recovery journal is added.
- Registration, selection and explicit revocation share the pin/unpin lock. A browser, clipboard selection or transfer using a folder blocks revocation until it releases that folder. Failed revocation retains the registration; unknown registry state does not trigger grant cleanup. Removing a registration does not delete files, and overlapping grants can still allow access. The registry is local to this installation and is not exported with settings backup.
- Copy-fallback moves verify the committed destination tree and parent, then the matching output before each source deletion. Detectable loss or change retains remaining originals. New-folder creation rejects a changed parent, and dismissed single-delete callbacks cannot start work. Final-check races and sudden power-loss durability remain outside these guarantees.
- Image-viewer rename/delete failures offer Folder access for an unchanged selected local image. The rounded offer is single-use and invalidated on dismissal, pause, destruction or selection change. Invalid/occupied names, changed files and archive/content routes do not trigger it; source-capture failures still show the ordinary error. The picker does not retry the mutation, and no grant bypasses system/provider restrictions.
- The focused host suite again considered 73 tests: 43 passed, 30 were skipped for unavailable Windows file identity or symlink support, and none failed. All eight shared-storage path tests passed; the seven destination-loss move cases were skipped. Main/drawer controllers, SettingsActivity, SAF classes, image recovery/style controllers and URI-cache/helpers passed isolated SDK compilation with unrelated-app stubs; 217 distinct Android regression methods compiled but were not executed, including 22 added transfer-parent/capability cases and 14 image-recovery cases. MainActivity and ImageReaderActivity wiring were reviewed statically. Six XML resources compiled with AAPT2 without linking. Earlier wider checks had nineteen baseline failures and eight platform assumptions; they are not a current full-suite pass. Multiple mounted devices, hardware detach, sidebar appearance, picker return and Huawei/API 28 behavior still need device checks. No APK build was run.
- The folder-load fix adds 24 Android regression cases, compiled with the actual loading/cache controllers and row/sort helpers, with unrelated app dependencies stubbed. Existing main-operation/image-recovery and SAF checks also recompiled successfully. None of these Android tests were executed. The repeated host suite retained 43 passes, 30 platform skips and no failures. Persistent memory exhaustion, a blocked filesystem call, physical reconnect and UI recovery remain device-validation limits.
- The directory-query change adds 19 Android cases for cancellation, queue replacement, single completion, retry and cursor handling. The focused SAF check compiled 175 regression methods and five existing XML resources; it did not execute the tests or link resources. API 29+ provider fixtures are not an API 28 device result. File-operation and permission code is unchanged by this update; slow-provider cancellation, pause/resume and retry still need device checks.
- Multi-folder registration adds 12 grant/registry cases and four settings-dialog cases, with existing single-folder assertions updated for retained grants. Scoped SDK compilation passed for the changed production classes and 195 Android regression methods; the tests have not run. Six XML resources compiled without linking. Migration, revocation, overlapping permissions, picker recreation and the rounded list still need device checks. The preferences write and Android grant change are not a crash-atomic transaction.
- The popup-transition follow-up compiled 13 settings regression methods, including five new cases, and the related production classes with unrelated dependencies stubbed. One settings XML compiled without linking. No test execution, APK build or device animation/accessibility check was performed.

## Image controls

- The filename group starts 2dp below the safe top inset. The bottom panel has no top padding; its 36dp-minimum counter translates down by 2dp without resizing the panel. The -10dp slider top margin, 38dp slider height and system insets remain unchanged.
- Visible panels reserve their measured space for image display; hidden panels restore the full-screen area. The existing drawable is scaled and positioned without decoding or replacement. Relative zoom and center are preserved where bounds allow, and zoom focus accounts for padding. This is the default behavior, with no new preference or backup field.
- Short images keep the full surface's vertical center across panel toggles, including initial loading and zoom bounds correction. A panel collision shifts them only to the nearest visible edge. Scale and page calculations are unchanged.
- Blank app space in the visible bottom slider panel passes touches to the existing image navigation. Only the centered page label opens page selection; the seekbar keeps its full drag width. System insets do not become image-navigation targets.
- The image view, slider layout and twenty-nine Android viewport/dispatch/toolbar regression methods compiled. Device execution and full resource generation remain pending; detached-view tests do not establish Activity integration or OEM system-bar behavior. Twenty-eight image navigation, spread, zoom and vertical-placement host tests passed. Image sequence/TXT page calculations, bookmarks and system-bar visibility policy are unchanged.

## Archive corrections and remaining coverage

- Fallback encryption detection checks RAR4 MAIN CRC before accepting its password flag, preserving comment spans and the probe's existing skip policy for other blocks. RAR5 detection checks header CRC, integer width and common-field bounds. Wide unknown types cannot wrap into the encryption type; valid padding remains accepted. The detector does not verify passwords or replace the normal parser. Its focused host regression tests passed.
- ALZ index construction rejects directory entries with nonzero unpacked contents before output creation, including trailing-slash names. Previously bulk extraction could silently discard those payloads. Empty directories/files remain supported; plain/encrypted/split regression sources are unexecuted.
- Non-solid EGG directories also reject nonempty blocks before listing or extraction. Archive-wide solid state is resolved first so shared blocks after directory headers remain valid. Empty directory forms are preserved. `EggDirectoryBlockAdmissionTest` passed on the host.
- EGG AES/LEA and ZipCrypto encryption fields accept the observed prefix-inclusive size when the complete fixed payload is followed by END. A valid declared-length header tail takes precedence, preserving canonical padded fields. Other layouts keep their existing parser behavior; this adds no marker scan or missing-verifier/footer tolerance. Cipher calculations are unchanged; encrypted filenames remain unsupported.
- Rejected ALZ/EGG ZipCrypto password candidates now produce a typed `BAD_PASSWORD` result. Cancellation, source/probe failures and existing-output protection retain their behavior. The saved producer samples and two public ZipCrypto samples passed the corrected negative checks.
- Unsplit AES-stored RAR3/RAR4 members now accept a solid flag through the existing stored-data path. Alignment, size, CRC and rollback remain checked; compressed history is not changed. Synthetic selected/bulk/forward host tests passed.
- RAR4 service blocks retain their full 64-bit packed size through listing, encrypted rewrites, split lookup and encryption detection. Previously those paths could read inside a large service payload as though it were another header. FILE validation and service exclusion from the entry list remain intact; focused boundary/traversal host tests passed.
- RAR4 MAIN, FILE, NEWSUB and END headers are CRC-checked before metadata admission or payload rewriting. MAIN/FILE inline-comment boundaries are preserved. Invalid later headers keep only earlier validated selected-entry recovery; skipped historical auxiliary and unknown blocks retain their previous CRC policy. Focused CRC host tests passed after correcting their synthetic builders.
- Encrypted RAR3/RAR4 headers now read their own salt before each padded ciphertext block, including ENDARC. The previous one-salt assumption could reject valid later headers. The rewrite keeps payload bytes and split/solid flags, clears its derived parameters, and retains password-failure handling. Synthetic framing tests and retained public encrypted-header/solid/comment samples passed on the host; fresh RAR3/RAR4 production remains outside this validation.
- RAR5 integers reject overflow before shifting and preserve valid padded encodings. Invalid encrypted header sizes keep the existing password prompt classification. Split-7z construction failures close caller-owned channels, including on memory/stack errors.
- Owned Deflate construction now releases its allocated inflater if stream creation fails, including injected memory or stack errors. ZIP/7z/EGG/ALZ use the guarded factory; input cleanup remains with the caller until success. Decoding, cancellation and the 64 KiB buffer are unchanged. Host ownership and adjacent lifecycle tests passed; sustained device-memory exhaustion is unverified.
- Packed 7z checksum work runs on buffer fills instead of each scalar decoder read. Framed AZO decoding avoids a redundant compressed-block copy while retaining separate output and block bounds. These are source-level reductions in work, not measured speed or memory results.
- AZO long-history indices now use seven bits and 128 slots instead of four bits and 16 slots. Six public method-3 EGG archives match Bandizip's extracted filenames and bytes after this correction; four previously failed. Synthetic compressed-AZO tests also pass with all five EGG ciphers, physical splits and multi-file solid output. Those combinations still lack real-producer validation.
- Supplemental 7z extraction retains its probe's source and parsed header, removing a second metadata parse or encrypted-header decode. Ordinary fallback, direct extraction APIs and selected-route failure handling are preserved. Synthetic host tests passed; no speed measurement is claimed.
- Ordinary 7z extraction validates the same source-volume snapshot before native retry and after successful decoding. RAR, 7z, EGG and ALZ bulk extraction also checks the pre-listing snapshot after its final pause, before accepting output or replacing the destination. This catches observable changes to active or previously read split volumes; unchanged metadata and final-check races remain limitations. The ordinary 7z source-change and separate RAR/EGG/ALZ bulk source-change host tests passed.
- Supplemental 7z packed refills observe token cancellation, including repeated refills inside one decoder call. Header probes keep wrapped heap/stack failures terminal while preserving unsupported-header and missing-library fallback. The resource-error branch has only static verification; no memory-exhaustion test was run.
- ZIP creation now reuses source/descriptor metadata guards before and after reading each file, including its final progress callback. Detected changes remove only the newly reserved ZIP. Same-metadata edits and changes after the final check can still escape detection; this is not a lock or an atomic snapshot of the selected tree.
- The archive review compared routing, split/solid state, integrity and resource ownership with public format/FOSS contracts and retained Bandizip 6.29 / WinRAR 7.23 native evidence. Limited producer/output comparisons also passed as described below; neither source covers every format path or the latest Bandizip release. No new codec coverage is claimed.
- Encrypted EGG filenames remain unsupported. The vendor specification identifies the flag and global-encryption record, but does not define a filename cipher/KDF/authentication contract. The inspected Bandizip 6.29 paths and reviewed FOSS readers did not supply a working contract. This finding is limited to those sources, not every producer or Bandizip version.
- Scoped RAR raw-prefix filter carry and complex encrypted/split/solid/VM combinations remain producer-unverified. AZO still materializes blocks in arrays; removing the input copy does not make it a streaming decoder.
- Fixture-report commands now forward their named properties to the test JVM, preserve caller-relative path meaning and stop on task failure. The [fixture inventory](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md#fixture-scope) records available provenance and missing combinations. The Gradle report tasks remain unrun; separate private host harnesses supplied the executed checks. A skipped fixture is not validation.
- The logical RAR3/RAR4 forward reader skips plain and AES-stored members without temporary decoded output. It retains size, intermediate/final CRC, cancellation and failed-session retirement. AES skips reuse the existing CBC stream, consume padding-only volumes and finalize the cipher before accepting the entry. Synthetic boundary and failure tests passed; actual reads still publish only verified spools. No cipher calculation or RAR5 route changed.
- Invalid Huffman data in encrypted RAR3/RAR4 entries now allows password retry after successful input/output cleanup. The public encrypted-data fixture's previously generic failure passes selected, bulk and forward negative checks with output preservation. Cancellation, source/output failures, physical EOF, unsupported features and failed cleanup are not converted. Plain errors keep their existing handling. Other decoder-error forms remain outside this change; damaged ciphertext and wrong passwords are not distinguishable from invalid plaintext alone.
- Forward-reader selection preserves typed RAR3/RAR4 password and physical-integrity failures. A missing password on the public encrypted-solid sample previously fell through to an unavailable native decoder. Cancellation still wins, and other probe fallback behavior is unchanged.
- Missing physical RAR volumes, unfinished logical split chains and out-of-file RAR5 payload ranges now report integrity failures. Encrypted-header fallback no longer turns these into unsupported-feature errors. Selected recovery of unrelated complete members remains unchanged. An absent selected member can still return a generic failure.
- Failed source snapshots retain their original cause through extraction and replacement preflight. Missing RAR/7z parts keep their corruption diagnostic, access failures retain their reason, and cancellation remains terminal. No password-context probe follows a capture failure. Failed snapshots still reject all reuse and destination replacement, even after the source is repaired. Nine host regression tests passed.
- Selected RAR extraction rechecks its original source set before decoding and after prepared-source closure, before accepting output. Observable name, timestamp and volume-set changes fail with rollback, including numeric split sources. Twelve focused host tests passed. Stable complete-prefix recovery and password handling remain; metadata-preserving edits and changes after the final check can still escape detection.
- See [archive support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md) for the executed host scope, inherited format limits and remaining checks. Other archive regression classes are not covered by those results.

## Open reliability findings

- Manual bookmark persistence: `BookmarkManager` changes its in-memory list before writing it to disk. The writer logs storage errors without returning failure, so viewer actions can report success even when the file was not updated. An add, edit or deletion can be lost or reversed after restart. Saving needs a checked result and failure feedback, with saved coordinates preserved.
- PDF continuous-scroll failures: `PdfContinuousPageAdapter` discards render exceptions and delivers only non-null bitmaps. The queue marks the page as attempted, leaving a blank row without an error message or explicit retry until the request window changes. Single-page and spread modes already have separate failure controls.

These paths were traced in source without device reproduction. Bookmark-save
feedback and continuous-PDF recovery need separate fixes
and regression coverage before release.

## Efficiency opportunity

Backup export converts the entire JSON string to a UTF-8 byte array after opening
the document-provider output. Streaming the encoding could reduce peak memory.
Allocation and write failures are reported, but a newly created, incomplete export
may remain. The picker uses `CreateDocument`, so this path does not establish that
ordinary exports overwrite existing backups. Provider-specific atomicity and
incomplete-output cleanup need separate review.

## Validation boundary

The current 7z run passed 408 host tests in 26 classes without failures or skips,
including 30 new retained-fixture cases. Five p7zip PPMd/BCJ2 archives match fresh
Bandizip 6.29 reference extraction; the existing nine-family matrix also repeats
all 49 route checks successfully. Damaged copies preserve public outputs and
remove spools. Two stale test expectations were corrected after the initial run:
open-file deletion on Windows and the old PPMd EOF exception type. Runtime code
is unchanged. These single-member PPMd/BCJ2 samples and derived split copies do
not verify producer-created multi-member solid/split combinations or Android/native
routes. See [fixture scope](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md#fixture-scope).

The encrypted split EGG review passed 196 host tests across 12 classes with no
failures or skips. Five saved ALZip 12.37 cipher families match all original names
and bytes from first/last-volume entrypoints. Modified copies cover missing,
ambiguous and damaged volumes, stale-index rejection and public-output rollback.
Bandizip 6.29 CLI matches four families from their first volume; it rejects the
LEA-256 sample with a CRC error and all five last-volume entrypoints. Saved ALZip
reference output matches the originals for all five. Those reference-tool results
are separate from Readwide failures and do not describe newer Bandizip versions.
Runtime code and page calculations are unchanged; encrypted-solid/AZO combinations
and encrypted filenames remain outside this result.

Two public solid Deflate EGG archives now match Bandizip's complete filenames and
bytes through direct/public selected, bulk and forward paths. Their 28 listing,
extraction and cleanup checks passed. Four new fixture tests also cover partial
reads, explicit/implicit drains, early close and CRC-failure rollback. Across
11 related classes, 224 tests passed with zero assertion failures; one optional
output-above-512MiB case was skipped, so that suite's strict runner returned
nonzero. No production fix was needed. These samples do not establish encrypted,
split, multi-block or AZO solid interoperability; see [fixture scope](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md#fixture-scope).

The current RAR transition/filter review passed 319 distinct host tests across
16 classes, without failures or skips. Three public single-member archives passed
12 full byte comparisons against matching Bandizip/UnRAR output and six forward
drain checks. All 12 forward sessions left no spools. This includes the large
PPMd/LZ transition and RAR 6.24's pending-filter-to-PPMd case; no decoder fix was
needed. These unencrypted, unsplit samples do not validate cross-file solid state
or encrypted/split combinations. The new opt-in fixture tests also passed; see
[fixture scope](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md#fixture-scope) for reproduction.

The source-capture review passed 232 host tests across 21 classes. The selected-RAR
source recheck passed 244 across 22 classes. Both runs skipped eight additional
symbolic-link cases because Windows could not create the links; the
strict runner therefore returned nonzero despite zero assertion failures. The
retained damaged-RAR matrix again passed all 216 route/cleanup checks at 36 entrypoints.
Every bulk result was CORRUPT_ARCHIVE, with existing output preserved. The normal
matrix also repeated 153 exact-byte comparisons, 190 password checks and 90 forward
attempts successfully. Original archives/reference bytes were unchanged, and
temporary files were removed. These are host results, not Android/native validation.

The volume-integrity review passed 453 distinct host tests in 27 classes, with no
failures or skips. Eighteen damaged variants of three retained WinRAR encrypted
split families passed 216 route/cleanup checks across 36 first/last entrypoints.
UnRAR rejected the same variants. Existing bulk destinations and original samples
were preserved, and all 72 forward attempts left no spools. The variants cover
missing first/middle/last volumes, truncated middle/last volumes and a changed
middle payload byte. This is a bounded corruption matrix, not arbitrary-fuzz coverage.

The forward-password review passed 374 distinct host tests in 21 classes, without
failures or skips. Eight public RAR archives and four retained WinRAR archives
passed 153 exact-byte route checks and 190 missing/wrong-password checks across
15 entrypoints, including first/last split volumes. Public results match Bandizip
and UnRAR; WinRAR results match their original inputs. All 90 forward attempts
left no spools. Source and reference bytes were preserved. This includes legacy
encrypted headers, comments and solid files, not all mixed PPMd/LZ/VM combinations.
The same byte, password and forward checks passed again after the integrity fix.

The password-result and stored-discard review passed 236 and 292 host tests,
without failures or skips: 528 distinct tests in 31 classes. Three retained public
archives passed 30 selected/bulk/public-forward/logical-forward exact-byte checks
against matching Bandizip/UnRAR references. The encrypted compressed sample also
passed seven wrong-password checks with output preservation and failed-reader
retirement. Twelve positive and four negative forward sessions left no spools;
both reference scripts exit zero. AES-stored discard has synthetic coverage.
These samples do not establish all password-error forms, complex encrypted/split/
solid/VM producer compatibility or Android behavior.

The preceding construction/lifecycle checks passed 187 host tests across 18 classes
without failures or skips. They cover six new Deflate construction-ownership tests,
existing decoding and token checks, RAR/7z forward cleanup, position/drain behavior,
EGG solid/AZO combinations and RAR packed/split-stored cancellation. Native-forward
coverage uses a fake Java delegate. Saved ALZip EGG samples retained 265 passes;
ALZ retained 34 passes and 16 name/path failures. A restricted-sandbox run added
27 public-bulk EGG failures, all removed by rerunning unchanged source outside
that sandbox. These are host results, not Android navigation, background-resume
or sustained-memory-pressure results.

The review covered selected paths in TXT partitions and positions, EPUB/document
ownership, PDF rendering, image/archive handoff, settings/import rollback, file
operations and entry locking. It was not a line-by-line review of every source
file or a complete decoder check.

Direct Java compilation and host execution passed 292 RAR tests and 176 7z
tests, each across 13 focused classes. Four fresh WinRAR 7.23 RAR5 cases passed
36 member/negative checks. Four fresh Bandizip 6.29 solid LZMA2 cases and five
retained p7zip PPMd/BCJ2 cases passed 49 route checks. Comparisons used exact
output bytes; encrypted and split cases were included. The retained PPMd/BCJ2
fixtures each have one output substream and do not validate cross-file solid
state. Fresh RAR3/RAR4 PPMd/VM combinations remain outside the producer run.

The latest EGG/AZO and adjacent ALZ host run covered 20 classes: 352 tests passed,
none failed, and one opt-in large-output test was skipped. The runner reports a
nonzero exit for that skip; it is not counted as a pass. Eight synthetic AZO
combination tests cover all five EGG ciphers with splits and multi-file solid
output. Six public AZO archives passed 54 exact-name/byte checks against Bandizip;
two public ZipCrypto archives passed another 21 checks after the header fix.

Saved ALZip 12.37 samples passed 265 checks across all 12 EGG families, including
five encrypted split families and two plaintext solid families. Wrong-password
classification now passes. The ALZ sample adds 34 passed checks but retains 16
failed name/path comparisons across its first/last-volume runs: its Unicode
filename was already replaced with question marks in the archive. All four
selected ALZ payloads match; the missing name bytes cannot be recovered by decoding.
Encrypted EGG names remain unsupported, and producer-created encrypted-solid AZO
remains unverified despite passing synthetic combinations.

The harnesses used actual archive source with host dependencies; the 7z harness
substituted only Android build constants and a logging sink. These are not APK
or native-library results. No Gradle/APK build, device checks or benchmarks were
run. TXT, settings and saved-data regression sources remain unexecuted. Exact
TXT paging, partitions, half-visible-row selection, existing bookmark
coordinates and free-space protection are unchanged. The AZO history-index width
was corrected; arithmetic coding and cipher calculations are unchanged.
