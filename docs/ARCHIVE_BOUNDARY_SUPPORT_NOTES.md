# Archive support notes — Readwide 1.0.22

Use the [current source status](CURRENT_SOURCE_STATUS_1_0_22.md#archive-corrections-and-remaining-coverage),
[1.0.22 archive changes](DEV_CHANGES_1_0_22.md#archive-parsing-and-input-ownership),
[1.0.21 archive changes](DEV_CHANGES_1_0_21.md#archive-compatibility) and
the [inherited 1.0.20 format boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).
The latter is a historical baseline, not a replacement for newer route/filter
changes. Per-format reports describe their own source/fixture scope, not complete
producer compatibility or a fresh execution of the current source.

## Current implementation references

- [Code map](CODE_MAP.md) for routing, decoder ownership and regression-source locations.
- [EGG format notes](EGG_FORMAT_NOTES.md) and [ALZ format notes](ALZ_FORMAT_NOTES.md).
- [Third-party notices](../THIRD_PARTY_NOTICES.md) and [FOSS status](FOSS_STATUS.md) for component boundaries.

## Inherited implementation and validation records

- [Routing, volume names and selected identities](ARCHIVE_ROUTING_1_0_20.md).
- [Extraction, cleanup and stream ownership](ARCHIVE_EXTRACTION_1_0_20.md).
- [Decoder additions and provenance](ARCHIVE_CODEC_ADDITIONS_1_0_20.md).
- [LZ frame and RAR stream corrections](ARCHIVE_STREAM_REPAIR_1_0_20.md).
- [Selected RAR fallback validation](RAR_SELECTED_FALLBACK_VALIDATION_1_0_20.md).
- [ALZ selector validation](ALZ_SELECTOR_VALIDATION_FIX.md) and
  [ALZ archive validation](ALZ_ARCHIVE_BOUNDARY_REVIEW.md).

## Fixture scope

This inventory records fixture provenance and validation targets. Executed checks
are summarized under Release boundary; they do not cover every combination in
the table. Historical results apply to their recorded source checkpoints.

| Target | Available evidence | Remaining validation |
| --- | --- | --- |
| RAR3 solid PPMd continuation | The embedded 381-byte CBR in `Rar3PpmdEngineFixtureProbeTest` comes from the CC0 [RAR Test Files](https://github.com/ssokolow/rar-test-files) collection. Both images passed ten current selected/bulk/forward byte comparisons against matching Bandizip and UnRAR output, including drain-then-read continuation. | Larger and fresh producer samples; encrypted/split/VM combinations and Android navigation. |
| RAR3 solid text | The pinned [rarfile `rar3-solid.rar`](https://github.com/markokr/rarfile/blob/d2f7df6fc843dae356fd6b0a85971dc36fd6e757/test/files/rar3-solid.rar) contains two 2,048-byte entries. Ten current selected/bulk/forward byte comparisons match Bandizip and UnRAR. | Larger and encrypted/split combinations; Android execution. |
| RAR3 encrypted compressed entries | The pinned [libarchive encrypted-data fixture](https://github.com/libarchive/libarchive/blob/7e9bfb0469afa0bb7362a56e79be87bedcb91946/libarchive/test/test_read_format_rar_encryption_data.rar.uu) contains two independent 16-byte entries. Ten correct-password route checks match Bandizip and UnRAR. Seven wrong-password selected/bulk/forward checks now pass with output preservation and reader retirement. | Other decoder-error forms; this compressed fixture does not validate AES-stored discard. Invalid plaintext alone cannot distinguish a wrong password from damaged ciphertext. |
| RAR3 plain and one-member solid text | Both 98-byte CC0 `testfile.rar3` fixtures contain the same PPMd reset payload, declare 12 output bytes and CRC `0x6ec18ffe`, and match the original `Testing 123\n` text contract. Header CRCs were inspected without decoding. | Execute the corrected exact-output smoke assertions; a one-member solid archive does not validate cross-file continuation. |
| RAR3 PPMd/LZ switching | The pinned [libarchive transition fixture](https://github.com/libarchive/libarchive/blob/7e9bfb0469afa0bb7362a56e79be87bedcb91946/libarchive/test/test_read_format_rar_ppmd_lzss_conversion.rar.uu) now passes current selected/bulk/public-forward/logical-forward checks. Its complete 241,647,978-byte member matches Bandizip and UnRAR; both forward drain routes pass. The [earlier report](RAR_SELECTED_FALLBACK_VALIDATION_1_0_20.md#upstream-fixtures-and-limits) remains historical. | Encrypted, split and multi-member solid state; Android/native execution. |
| RAR3 VM filter and pending filter-to-PPMd transition | Pinned libarchive [executable-filter](https://github.com/libarchive/libarchive/blob/7e9bfb0469afa0bb7362a56e79be87bedcb91946/libarchive/test/test_read_format_rar_filter.c) and [pending-filter](https://github.com/libarchive/libarchive/blob/7e9bfb0469afa0bb7362a56e79be87bedcb91946/libarchive/test/test_read_format_rar_filter_incomplete_block.c) samples match Bandizip and UnRAR on every tested read route. The latter is ordinary RAR 6.24 output (`rar a -ma4 -m5 -mct+`), not a malformed archive; its upstream test records a libarchive limitation. | Other filter types and combinations, cross-file carry, encryption and physical splits. |
| RAR3 mixed state with VM filters, encryption and splits | `Rar3PpmdModeSwitchTest`, `Rar3PpmdFilterOutputTest` and derived solid wrappers exercise synthetic controls or modified containers. | A producer-created archive combining these features, with exact output checks for every member. |
| RAR3/RAR4 encrypted headers and CRC spans | Pinned [libarchive encrypted/header/solid fixtures](https://github.com/libarchive/libarchive/blob/7e9bfb0469afa0bb7362a56e79be87bedcb91946/libarchive/test/test_read_format_rar_encryption.c), its [earlier header fixture](https://github.com/libarchive/libarchive/blob/7e9bfb0469afa0bb7362a56e79be87bedcb91946/libarchive/test/test_read_format_rar_encryption_header.c), and [rarfile's encrypted-comment archive](https://github.com/markokr/rarfile/blob/d2f7df6fc843dae356fd6b0a85971dc36fd6e757/test/files/rar3-comment-hpsw.rar.exp) pass selected/bulk/forward checks against Bandizip and UnRAR. Missing/wrong passwords preserve outputs and remove temporary files. Synthetic CRC-span and salt-framing tests also pass. | Producer-created encrypted splits combined with mixed PPMd/LZ/VM state; larger legacy samples and Android execution. |
| RAR5 encrypted payloads and headers | Public libarchive encrypted-header/solid samples pass current route and password checks. Four retained WinRAR 7.23 archives cover AES stored, stored split, compressed solid split and header-encrypted compressed solid split; first/last volume entrypoints match original bytes. Eighteen damaged split variants passed 36 first/last entrypoint checks for rejection, output preservation and cleanup; UnRAR also rejected them. Earlier RAR/WinRAR 7.00 fixtures remain embedded in focused tests. | Broader producer/content and corruption combinations, concurrent source changes and Android execution. |
| 7z PPMd and BCJ2, including AES headers | Five retained p7zip archives embedded in `Sevenz7PpmdFixtures` and `Sevenz7Bcj2Fixtures` match current host output and fresh Bandizip reference bytes. Public selected/bulk, partial forward reads, drains, failed-output preservation and cleanup pass. | Each producer graph has one member. Split variants are byte-split derivatives; mixed-folder fixtures are synthetic. Producer-created multi-member solid/split combinations and Android/native routes remain unverified. |
| EGG LEA/AES/ZipCrypto | [Five CC0 unegg samples](../app/src/test/resources/archives/egg-lea/README.md) cover LEA-128/256, AES-256/BZip2 and ZipCrypto with prefix-inclusive encryption fields. Focused crypto/header tests passed. | Encrypted filenames and producer-created encrypted split-solid combinations. |
| EGG encrypted splits | Five saved ALZip 12.37 sets use ZipCrypto, AES-128/256 and LEA-128/256, with five physical volumes each. Current first/last-volume selected and bulk output matches the four original files. Modified copies pass missing/damaged-volume, warmed-index and output-protection tests. | These are non-solid samples without encrypted filenames or AZO; Android/native integration remains untested. |
| EGG AZO | Six public archives from [unegg](https://github.com/alkegi/unegg/tree/9929009ca4d010e8cd88bb796f7a1868162026d4/tests/data) and [EggDotNet](https://github.com/akolman/EggDotNet/tree/78b64f54026a8687dac1cdd4c86eb5fe5abf04ae/src/EggDotNet.Tests/test_files) contain method-3 blocks. All match Bandizip 6.29 after the seven-bit history-index correction. Synthetic compressed-block tests also pass with all five EGG ciphers, splits and multiple solid files. | Real-producer combinations of those features; Android execution. |
| EGG solid Deflate | Pinned [unegg `solid.egg`](https://github.com/alkegi/unegg/blob/9929009ca4d010e8cd88bb796f7a1868162026d4/tests/data/solid.egg) and [EggDotNet `solid.egg`](https://github.com/akolman/EggDotNet/blob/78b64f54026a8687dac1cdd4c86eb5fe5abf04ae/src/EggDotNet.Tests/test_files/solid.egg) match Bandizip through current direct/public selected, bulk and forward routes. They hold five and two files in one shared Deflate block, including an empty file and nested paths. Partial-read/drain/close and CRC-rollback tests pass too. | Encrypted, split, multi-block and AZO solid combinations; exact producer versions/options are not established by these fixture records. |
| ALZ method 3 | Existing obfuscated-Deflate, ownership and split tests construct or modify containers. | Independent producer archives for encrypted/split method-3 ALZ. |

WinRAR 7.x no longer creates RAR4 archives, and Bandizip's documented creation
formats do not include RAR, EGG or ALZ. Those programs can provide extraction
comparisons, but cannot generate every missing case. See the
[WinRAR changes](https://www.rarlab.com/rarnew.htm) and
[Bandizip command formats](https://en.bandisoft.com/bandizip/help/parameter/).

Keep any additional fixture's upstream revision, redistribution terms, producer
version/options when known, expected member paths and original payloads together.
Treat extracted executable members as bytes. Do not add producer binaries or
private documents to the source package. For success cases, check all member
bytes through selected, bulk and forward reads; for corruption, wrong passwords,
missing volumes and source changes, check rejection and output cleanup separately.

The `generate_*_fixture_reports` scripts resolve fixture/report directories from
the calling directory and use the repository's wrapper and project directory.
Only the existing named fixture/report properties
are forwarded; ordinary app builds and signing properties are unchanged. The
PowerShell scripts stop on a failed Gradle exit. A successful task may still
contain skipped fixtures, and the optional reports inspect only their documented
sample paths. Review the JUnit results before treating any row as validated.
`scripts/tests/check_fixture_report_wiring.py` contains source-level checks for
this wiring; it has not been run in this review.

The external smoke test now requires successful extraction and exact size/CRC
for the RAR5 CBR images, RAR5 solid text and RAR3 solid CBR images already embedded
in focused tests. The `testfile.rar3.rar` and `testfile.rar3.solid.rar` cases also
compare exact bytes with the [original text](https://github.com/ssokolow/rar-test-files/blob/master/sources/testfile.txt).
Their expectations no longer depend on native-backend availability.
Fixture bundle extraction also rejects paths outside its
temporary directory before creating output. These assertions have not been run.

`RarTransitionFixtureTest` uses the existing `textview.externalArchiveFixtureDir`
property or `TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR` environment variable. Place
the decoded `.rar` files under its `rar-transitions/` subdirectory, keeping their
upstream basenames. The three `.rar.uu` files are in the pinned libarchive test
directory linked above. Archives are external and are not redistributed in this
source package. Missing samples produce explicit skips, not passes.

The expected members are `ppmd_lzss_conversion_test.txt` (241,647,978 bytes,
CRC `f72b4477`), `bsdcat.exe` (204,288 bytes, CRC `4db10349`) and `f2500.bin`
(10,000 bytes, CRC `13078c9e`). Each test checks listing, complete size/CRC,
selected/bulk byte equality, public/logical forward reads, drains and spool cleanup.
It reads output in bounded buffers and never executes extracted files. Separate
host comparisons checked every byte against both reference extractors; the
size/CRC assertions alone are not an independent byte-for-byte oracle.

`EggSolidFixtureTest` uses the same external-fixture property/environment variable.
Place the two pinned `solid.egg` downloads under `egg-solid/`, renamed `unegg.egg`
and `eggdotnet.egg`. The tests do not fetch or redistribute archives; missing files
explicitly skip. Both contain one unencrypted Deflate block: 6,342 decoded bytes
for unegg and 1,050 for EggDotNet. Member size/CRC assertions come from the verified
Bandizip output. The four methods check full and partial forward reads, explicit
and implicit drains, empty-file handling and early-close cleanup. CRC-negative
tests alter a private copy's block checksum, preserving the original compressed
data, and check new/existing selected and bulk targets plus failed-reader retirement.
Those damaged copies are test derivatives, not additional producer samples.

`EggEncryptedSplitFixtureTest` uses the same external-fixture root. Supply
`egg-encrypted-split/input/` and `egg-encrypted-split/archives/` from the retained
ALZip sample bundle, plus its password in `TEXTVIEW_EGG_FIXTURE_PASSWORD`.
Archive names follow `egg-{zipcrypto,aes128,aes256,lea128,lea256}-split.volN.egg`
for N=1 through 5. No archives, originals or password are redistributed. Missing
configuration skips explicitly; incomplete configured samples fail. The ten
methods run for each cipher and compare output with the four original files
(458,144 bytes total). Every destructive variant changes a temporary copy after
warming the index. Header failures remain separate from password rejection;
changed ciphertext can still be indistinguishable from a wrong password.

## Release boundary

The current 7z review passed 408 host tests in 26 classes with no failures or
skips. The new `SevenZProducerFixtureTest` contributes 30 cases across five
retained p7zip graphs. Fresh Bandizip 6.29 extraction matches all five saved
reference trees exactly. The retained four Bandizip LZMA2 families and five
p7zip families also repeat all 49 direct/public route checks successfully.
No production decoder changed. The initial run exposed two stale tests: Windows
cannot delete the held archive handle, and truncated PPMd initialization now
throws a typed integrity error. The corrected checks assert live spool reuse,
post-close source release and sticky integrity failure. Output and corruption
assertions are unchanged.

For the opt-in test, set `textview.externalSevenZFixtureDir` in the test JVM to a
directory containing `embedded-ppmd-plain`, `embedded-ppmd-aes`,
`embedded-bcj2-store`, `embedded-bcj2-lzma` and `embedded-bcj2-aes`. Each contains
`book.7z` and a `reference/` tree from independent extraction. The corresponding
public fixture constants identify the archive bytes; AES uses their existing
test password. An absent property causes explicit skips; incomplete configured
fixtures fail.
Tests split temporary copies across signature/data/header boundaries, check
first/middle/last entrypoints, and reject damaged packed bytes, missing parts or
post-open size changes without losing previous output. These copies are not new
producer-created split archives, and the one-member graphs do not establish
cross-file PPMd/BCJ2 solid state. Archives and reference output remain unchanged.

The encrypted split EGG review passed 196 host tests in 12 classes, including all
50 new parameterized fixture cases, with zero failures or skips. Intact first/last
entrypoints pass 80 direct/public selected-file comparisons and ten public bulk
tree comparisons. Missing first/middle/tail parts, signature/link/ordinal damage,
prefix/tail truncation and changed middle ciphertext reject with public-output
preservation and cleanup. The five original sets and input files are unchanged.
No production decoder or page calculation changed.

Bandizip 6.29 CLI reproduced original names and bytes for ZipCrypto, AES-128/256
and LEA-128 from their first volume. Its LEA-256 extraction failed on
`nested/binary.bin` with a CRC error; all last-volume attempts failed to open.
These results persisted with short staging paths. Saved ALZip reference output
matches the originals for all five families, and Readwide matches both first and
last entrypoints. This is a bounded comparison with that old CLI, not a claim
about later Bandizip releases or proof of every LEA/split combination.

The solid EGG review passed 28 reference listing/extraction/cleanup checks over
the two public archives. Original archives and reference bytes were unchanged.
The four new fixture tests passed, including 20 successful forward sessions and
four CRC-rejected sessions with no spool leaks. Ten selected and two bulk CRC
failure cases preserved existing targets or removed new output. Together with
the existing EGG ownership, authentication, AZO and EGG/ALZ source-handoff classes,
224 tests passed across 11 classes. One large-output opt-in test was skipped;
the strict runner returned nonzero for that skip, with zero assertion failures.
No production decoder or page calculation changed.

The RAR transition/filter review passed 319 distinct tests in 16 classes: the
292-test suite and 27 additional filter-safety, selected-source and external-fixture
tests. A repeated 20-test filter-output class also passed. There were no failures
or skips. Three public archives passed 12 reference-byte comparisons and six
forward drain checks; all 12 forward sessions left no spools. Archives and
reference bytes were preserved. This closes the current-host verification gap
for those samples without changing production source or proving encrypted,
split or cross-file solid/filter combinations.

The source-capture review passed 232 host tests in 21 classes; the selected-RAR
source recheck passed 244 in 22 classes. Both skipped eight Windows symbolic-link
cases and had no assertion failures. The strict runner treats
those skips as a nonzero exit. Failed capture causes now survive extraction and
replacement preflight; failed snapshots still cannot authorize output. Selected
RAR extraction also checks its original sources before decoding and before commit,
with 12 tests for changed/stable sources, encrypted entries, numeric splits and
rollback. These metadata checks cannot detect every concurrent edit. The same
36 damaged-RAR entrypoints passed all 216 route/cleanup checks, with corruption
reported for every bulk failure. The normal matrix also repeated all 153 byte
comparisons, 190 password checks and 90 forward attempts successfully. Source,
reference, destination-preservation and spool-cleanup checks passed.

The volume-integrity review passed 453 distinct host tests across 27 classes,
with no failures or skips. Eight public archives and four retained WinRAR archives
passed 153 exact-byte route checks and 190 missing/wrong-password checks across
15 entrypoints. All 90 forward attempts left no spools; original archive and
reference bytes were unchanged. The separate damaged-volume matrix passed 216
route/cleanup checks across 36 entrypoints, with no incomplete output or leaked
spools. Missing first/middle/last volumes, truncated middle/last volumes and a
changed middle payload byte were tested on three encrypted split families.
These results do not establish every
encrypted/split/solid/VM combination or Android/native-backend behavior.

Plain and AES-stored RAR3/RAR4 logical-forward skips avoid decoded spools while
checking size and intermediate/final CRCs. AES skips consume all ciphertext and
finalize CBC; actual reads keep verified spools. Host runs of 236 and 292 tests
passed without failures or skips, covering 528 distinct tests in 31 classes.
This includes the typed Huffman password-retry fix and rollback/cancellation
checks. Three retained public archives passed 30 exact-byte route checks against
matching Bandizip/UnRAR output. The encrypted compressed sample passed seven
wrong-password checks, including the previously failing result. No spools remained
in 12 positive and four negative forward sessions; both reference scripts exit
zero. AES-stored discard tests are synthetic. Other decoder-error forms, fresh
legacy producer combinations, Android behavior and performance remain unverified.

Deflate construction and adjacent archive lifecycle checks passed 187 host tests
across 18 classes without failures or skips. Six new tests inject construction
errors and check inflater release, unchanged input ownership and retry. Existing
tests cover decoding/cancellation, RAR/7z forward cleanup, position/drain behavior,
EGG solid/AZO combinations and RAR packed/split-stored cancellation. The
native-forward bridge uses a fake delegate; no native codec runs in that test.
The saved ALZip rerun retained 265 EGG passes and the same 16 ALZ name/path
failures described below. This does not establish Android UI or real exhausted-heap
behavior.

Direct host runs passed 292 focused RAR tests and 176 focused 7z tests. Four fresh
WinRAR RAR5 cases passed 36 member/negative checks. Four Bandizip LZMA2 cases and
five retained p7zip PPMd/BCJ2 cases passed 49 route checks. Their exact scope is in
[current source status](CURRENT_SOURCE_STATUS_1_0_22.md#validation-boundary).
Synthetic framing and state tests do not establish producer interoperability for
every encrypted/split/solid/VM combination. The retained PPMd/BCJ2 samples each
have one output substream; cross-file state remains a separate target.

Six public AZO archives passed 54 direct/public listing and extraction checks
against Bandizip's exact filenames and bytes. Four failed before the long-history
index was corrected from four bits to seven. The latest 20-class EGG/AZO and
adjacent ALZ run passed 352 tests, with no failures and one opt-in large-output
skip. It includes eight synthetic compressed-AZO cipher/split/solid tests.
The runner's nonzero exit for the skip is retained. Those six public archives
cover x86 filtering, multiple inner blocks and mixed-codec entries, not encrypted
or split AZO.

Saved ALZip 12.37 samples passed correct-password checks for 12 EGG families,
including five encrypted split families and two plaintext solid families.
They cover Store, Deflate, BZip2 and LZMA, with ZipCrypto, AES and LEA. These
separate samples do not establish encrypted-solid compatibility. All 265 EGG
checks pass, including wrong-password classification and output preservation.
Two public prefix-inclusive ZipCrypto samples pass another 21 Bandizip comparison
and negative checks. The ALZ producer sample already contains a damaged Unicode
name; its four selected payloads match, while the damaged name prevents an
original-name round trip and is rejected as a Windows bulk-output path. Across
its first/last-volume entrypoints, 34 checks pass and 16 name/path comparisons fail;
these are retained in the report, not counted as successful extraction.

Encrypted EGG filenames remain unsupported and lack a genuine test vector.
Complex RAR raw-prefix carry and completed-filter-result splitting across files
remain producer-unverified. Resource-exhaustion handling and regression classes
outside the named runs remain statically reviewed only. Observable source-change
checks do not exclude same-metadata edits or changes after the final check.

No Gradle/APK build, Readwide native-backend execution, device test or benchmark was run
for these host checks. They do not establish release-APK behavior, a speed
improvement or full producer compatibility. ALZip and its bundled software were
not reinstalled for the saved-sample verification.

See [current status](CURRENT_SOURCE_STATUS_1_0_22.md) for this version and
[1.0.21 release readiness](RELEASE_READINESS_1_0_21.md) for inherited checks. Use a
complete source package created by the current packaging policy; old incremental
patch names are historical records, not current installation instructions.
