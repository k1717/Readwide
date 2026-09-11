# Current source status — Readwide 1.0.18

This is the current-source summary, not a claim that every change has passed
runtime validation. The dated batch sections in the performance note describe
implementation history: a gap stated in an early batch may be addressed later.

## Identity and verification

- A later presentation/resource follow-up rounds and pads the timeout field and
  refines 216 strings across 22 locales. It has static checks only and is not
  covered by the maintainer's earlier successful build report.
- `app/build.gradle`: applicationId `com.readwide.manager`, versionName `1.0.18`,
  versionCode `10018`, compileSdk 35. Root AGP is 9.2.0; wrapper is Gradle 9.4.1.
- The maintainer reported a successful earlier 1.0.18 snapshot build, before the
  UI/resource follow-up above. No build log, artifact
  hash, signing result or unit-test/device result was supplied with that report.
  That report supersedes the old unbuilt status only for that snapshot; the older second-batch
  APK record and batch-time validation notes remain historical.
- New regression methods are present; their execution results have not been reported. Source structure, package
  contents and hashes are checked separately; none prove device compatibility.
- No dependency, permission, signing-key or public release/tag changes were made
  by these follow-ups. The 1.0.18 license/SBOM files record the current release
  identity and unchanged source-declared dependencies; older reports remain historical.

## Document readers and file browsing

File/Recent thumbnails now track attached-holder demand, remove unused queued
work and use a separate bounded cache-only PNG lookup queue. Completed covers
update matching visible icons without a full-dataset scan/rebind; retries are
coalesced. Eight binding regressions are unexecuted; cold cover generation still
has latency. See [thumbnail scrolling](THUMBNAIL_SCROLL_FIXES_1_0_18.md).

PDF read-aloud now preserves inferred spaces and refuses ambiguous glyph-run
geometry without rejecting speech. Checkpoints carry a PDF extraction-format
version; legacy/unknown offsets resume at their saved page start. Twelve new
regressions are unexecuted. See [PDF read-aloud details](PDF_READ_ALOUD_FIXES_1_0_18.md).

PDF query edits/dismissal invalidate old navigation results immediately. Pending
scans are coalesced, options are snapshotted and one compiled query is reused
across pages. Original-text offsets and inferred whitespace keep glyph indices
aligned. Whole-document extraction and cooperative cancellation remain; eleven
regression sources are unexecuted. See [PDF search details](PDF_SEARCH_FIXES_1_0_18.md).

## Current archive scope

The current implementation includes scoped mixed RAR3 LZ/PPMd dispatch with one bit reservoir,
raw dictionary and standard-filter queue; production LZ output streams. A new
CRC/boundary-checked fallback admits plain single-volume compressed solid runs
with valid primers. Special 7z graphs now accept Deflate/Deflate64/BZip2/Delta and
six BCJ filters via bundled codecs. Twenty new regressions are unexecuted.
See [exact scope and remaining gaps](RAR_MIXED_AND_7Z_CODERS_1_0_18.md).

RAR5/RAR7 paged history retains the declared logical dictionary with bounded RAM
and encrypted temporary storage; the new path still needs real-device validation.
Resolved-volume metadata guards protect preparation/handoff, but complete shared
preview-cache identity/invalidation across all volumes remains unfinished.
Source-level scheduling/indexing improvements have not been benchmarked.

Earlier implementation stages are retained in the
[performance history](ARCHIVE_VIEWER_PERFORMANCE_1_0_18.md), not repeated here as
current limitations. See the [release handoff checklist](RELEASE_READINESS_1_0_18.md)
for the maintainer's remaining validation and intentionally deferred work.

| Area | Implemented source path | Still not established / retained boundary |
| --- | --- | --- |
| Separate RAR3/RAR4 PPMd-only route | Scoped streaming, AES/complete split chains, PPMd-to-PPMd tables, retained escape byte, standard-filter delayed output | This PPMd-only route does not dispatch mixed LZ; the plain mixed fallback is listed separately. Custom VM/cross-entry scheduling and real filtered-fixture validation remain gaps |
| Classic/mixed RAR3 LZ/PPMd | Streaming input/output, shared raw history and standard-filter queue, mixed table dispatch and CRC/boundary-checked plain single-volume solid fallback | Encrypted/split mixed runs, stored-member solid runs, custom VM/cross-entry or unsupported scheduling, broader boundaries and real mixed/solid-fixture validation remain gaps |
| RAR5-container v0/v1 | Scoped RAR 5/6 and RAR 7 streaming; CRC32/BLAKE2sp/HashMAC; covered intermediate split checks | Full declared history via bounded RAM/encrypted disk paging; storage and format bounds remain, and this new path is unvalidated; unknown sizes/other unsupported combinations remain gaps |
| ALZ | Reusable entry metadata, covered Store/Deflate/BZip2/ZipCrypto extraction | Trimmed-BZip2 decoder is derived from Commons Compress; not an entirely independent codec |
| EGG | Non-solid entry/block indexes; plain solid forwarding; covered Store/Deflate/BZip2/AZO/LZMA and non-solid ZipCrypto/AES; case/padding-independent split discovery | LEA/encrypted-solid remain unsupported; AZO is xunazo-derived and still array-based |
| EGG integrity | Declared decoded lengths, nonzero block CRCs and supported AES footer authentication | Zero CRC is treated as absent, not verified; metadata checks are not content hashes |
| Shared ALZ/EGG input | Physical/logical range checks, cancellation/failure retirement, binary-search volume lookup and independent bounded views | Raw skip does not authenticate data; no read-ahead buffer or measured speedup; all volume handles still open together |
| 7z / TAR | Covered special-folder reuse, BCJ2/PPMd streaming, added Deflate/Deflate64/BZip2/Delta/six BCJ graph coders and standard split special-forward adaptation; indexed ordinary plain TAR | Other split naming, early substream publication, runtime validation and broader sparse/compressed-TAR access pending; 7z model/metadata guards remain |

The fixed 128 GiB total extraction ceiling and the viewer-only 2 GiB ceiling
are removed. Available-space accounting/reserve, signed-long bounds and format
or decoder-memory guards remain. ALZ/EGG metadata-cache admission budgets govern
retention only: oversized indexes remain usable uncached. Shared preview-image
identity/retention is separate from the newer all-volume metadata indexes.

See [performance implementation history](ARCHIVE_VIEWER_PERFORMANCE_1_0_18.md),
[RAR details](RAR3_PPMD_AND_RAR5_CHECKSUMS_1_0_18.md),
[size policy](ARCHIVE_SIZE_POLICY_1_0_18.md), [EGG notes](EGG_FORMAT_NOTES.md),
and [third-party attribution](../THIRD_PARTY_NOTICES.md).

## Reading older documents

`DEV_CHANGES_*`, `GITHUB_RELEASE_NOTES_*`, dated audits and 1.0.2 status matrices
record their named versions. Do not replace their original version numbers or
past test outcomes with 1.0.18 claims. For current publication wording, use this
summary, the README, current release notes and the latest format-specific scope.

Build instructions in `RELEASE_BUILD.md` describe maintainer commands to run;
their presence is not evidence that those commands ran on this source snapshot.
