# ALZ decoder and archive validation — Readwide 1.0.20

## Scope and provenance

This is the retained focused host-validation record for the imported ALZ repair.
Its baseline was `Readwide-1.0.20-alz-selector-fixed-source.zip`, SHA-256
`75170a98a21bdc006f56b38d73823355983aed66e28abe15905f3da8b2035735`.
Results below describe that source/fixture checkpoint, not a new run of the current
release. A subsequent release build completed successfully on 2026-10-11.
Use the current complete source package, not an old incremental patch.

The repair concerns `AlzBzip2InputStream`, `AlzipArchiveReader` and
`OwnedDeflateInputStream`. Existing attribution and source licensing are retained.

## Selector bounds

In `AlzBzip2InputStream.recvDecodingTables()` the integer is checked on every
unary increment and again before narrowing:

```java
int j = 0;
while (bsGetBit(bin)) {
    checkBounds(++j, nGroups, "selectorMtf");
}
checkBounds(j, nGroups, "selectorMtf");
if (i < MAX_SELECTORS) {
    selectorMtf[i] = (byte) j;
}
```

The later move-to-front lookup uses `checkBounds(v, nGroups, "selectorMtf")`.
Selector counts must be positive; active group counts must be two through six.
All declared selectors are validated, including those beyond retained storage.
Valid surplus selector lists through the 15-bit field maximum remain accepted.

These checks were already present in the fixed-source baseline and have **not**
been removed. All 19 existing focused checks pass both before and after this
follow-up. Fresh complete-fixture probes reject the added 256/512/768 unary-bit
mutations, inactive selectors, zero selectors, and one active group. See
`selector-probe-final.log` and `AlzSelectorBoundaryChecks.log`. No paused result
was reused in place of a new execution.

## Decoder table and block bounds

### Huffman lengths were narrowed without validating their integer history

The same class accumulated `curr` as an integer, then stored `(char) curr` without
checking each delta. Adding or subtracting 65,536 could wrap to the original
small length. The baseline accepted both complete mutated one-byte streams.
It also accepted invalid initial/transient lengths that returned to legal values
before storage, including malformed tables not chosen by any selector.

Every initial/current Huffman length is now checked in the range 1..20 before
reading another change bit and before narrowing. Decoding also stops at that
maximum instead of treating the 258-slot alphabet arrays as a code-length bound.
Valid lengths 1..20, legal up/down transitions, and incomplete but unambiguous
code spaces remain covered by positive tests.

The bzip2 reference validates `curr` in 1..20 on every delta and limits decoded
code lengths to 20. This is a format check, not an application memory/file limit.

### Decoding could use undeclared or stale selectors

Later MTF decoding checked `groupNo` against `MAX_SELECTORS`, not the number
actually declared for the current block. Uninitialized or previous-block entries
could therefore supply another table after the selector list ran out.

The actual retained selector count and active group count are now kept per block.
Both normal-symbol and RUNA/RUNB paths enforce them. Tests cover exhaustion on
both paths, shorter lists after earlier blocks, and valid 50-symbol boundaries.

### RUNA/RUNB integer overflow could become a short accepted run

An unbounded doubled weight and accumulated `int` length could wrap back to one
byte. A concrete 32-symbol mutation decoded `A` even though it described a far
larger run. Complete ALZ container tests with a valid CRC for `A` also accepted
that malformed stream in the baseline.

The weight is checked before doubling can overflow, and every increment must
fit the remaining 900,000-byte transformed-block capacity. This preserves the
existing ALZ block format bound; it does not limit total archive/output size.

### Reused BWT capacity was mistaken for the current block length

The `tt` array can remain larger after a long block. `origPtr` previously used
`tt.length`, allowing a later short block to address positions left over from
an earlier block. It now uses the current initialized length (`last + 1`).
Pointer checks precede data lookup and use the current block, not array capacity.
A missing first-stage RLE count byte is rejected instead of reading a cyclic or
stale byte as its count. An empty in-use alphabet is rejected explicitly before
it can collide with RUNB/end-of-block meanings.

Five shrinking-block pointer variants are covered. Valid shrinking blocks,
existing multiblock fixtures, and independently generated two/three-block
payloads still decode exactly. The empty-map tests improve *where/how* corruption
is rejected; the baseline already failed those particular fixtures later with EOF.

## ALZ error reporting and shared Deflate hardening

`AlzipArchiveReader` previously converted essentially every BZip2 constructor
failure into `UnsupportedArchiveFeatureException`. A broken selector table in a
supported format was consequently reported as an unsupported codec. The catch
now preserves the original exception and adds any cleanup error as suppressed.
Unknown compression methods still use the unsupported-feature exception.

The small BZip2 flavor probe now distinguishes real EOF from zero, invalid
negative or oversized read counts and observes cancellation. Tests exercise the
real, complete ALZ reader, CRC checks, existing/new-output rollback, ZipCrypto,
later-volume selections and the error classifier. The private flavor probe is
also tested directly for short/invalid reads.

`OwnedDeflateInputStream.fill()` formerly checked `len` *after* calling
`super.fill()`. The superclass has already passed the count to
`Inflater.setInput()` by then. Injected negative/oversized source counts therefore
threw unchecked array bounds exceptions before the intended check ran.

The override now validates the count before `setInput()`, uses checked EOF/I/O
errors, and retains the existing terminal-error/cancellation behavior. This class
is used by the ALZ, EGG and supplemental 7z Deflate paths. The real shared stream
is tested; complete EGG and 7z dispatch were **not** rerun in this follow-up.
These faults require a misbehaving source stream in the tests; they are not a
claim that an ordinary well-formed file input spontaneously returns invalid counts.

## Algorithm change and measured boundary

Bulk ALZ reads now fill the remaining identical RLE output bytes in one
`Arrays.fill()` operation instead of invoking the byte state machine for each
one. The scalar transition logic remains in charge at each run/block boundary.
Cancellation checkpoints remain bounded across bulk fills. Mixed scalar/bulk,
short-input, many-block and reference-output tests cover the change.

One isolated host benchmark decodes the 3,220,000-byte repetitive reference
fixture and computes CRC32, including stream construction. Five warmups and nine
measured samples give these medians:

| Baseline | Candidate | Baseline/candidate ratio |
|---:|---:|---:|
| 10.611 ms | 6.875 ms | 1.54x |

Both runs produce CRC32 `1230281271`. The fixture also passed the separate
SHA-256 check against its original input. This is **not** an Android benchmark,
whole-archive speedup, memory-use guarantee or a performance prediction for
nonrepetitive data. See the two benchmark JSON files and supplied Java probe.

## Recorded host validation

| Same named host checks | Before | After |
|---|---:|---:|
| Existing selector regressions | 19 passed / 0 failed | 19 passed / 0 failed |
| New Huffman/block checks | 10 passed / 20 failed | 30 passed / 0 failed |
| New Deflate/container checks | 18 passed / 13 failed | 31 passed / 0 failed |
| **Total** | **47 passed / 33 failed** | **80 passed / 0 failed** |

The 33 failures are not 33 distinct bugs. Four Huffman-suite baseline failures
are only diagnostic/earlier-rejection differences (two oversized-run tests and
two empty-alphabet tests). The invalid-probe baseline tests encounter the explicit
unavailable Commons seam rather than proving a particular real Commons result.
All positive final suites make **zero** calls to unavailable codec seams.

Additional validation, not added to the 80-check total:

- Twelve standard-bzip2 payloads generated with Python's system-backed `bz2`,
  round-tripped independently, then converted only at the documented ALZ framing
  boundaries. Patched decoding matches expected output length and SHA-256 for all
  twelve. The corpus includes empty data, Unicode, random data, mixed RLE lengths,
  small/large blocks and 3,220,000-byte output. These are not vendor ALZip archives.
- Real extraction of the existing 260,000-byte trimmed-BZip2 fixture in complete
  plain/encrypted ALZ containers and 137-part plain/encrypted sets. These cases are
  already included in the 31 container checks and must not be counted again.
- A seeded 1,000-case mutation probe finds no unchecked runtime exceptions in
  either baseline or candidate. Candidate results: 505 checked rejections,
  484 decoder completions without container CRC verification, and 11 test-budget
  stops. Completed mutation streams are **not** declared valid; this raw decoder
  relies on the container CRC. The test-only per-case output budget is 2 MiB and
  no production limit is added. The probe does not prove exhaustive safety.

## Host harness boundary

OpenJDK 21.0.11 compiled with `javac --release 17`. All three changed production
classes and seven relevant unchanged production classes compile in full.
Selected `ArchiveSupport` API/output-budget members are extracted verbatim from
the supplied source, not reimplemented for the tests. The complete Android
`ArchiveSupport` dispatcher is not compiled by this runner.

The harness has explicit Android progress, EGG, standard Commons BZip2 and one
RAR exception-type declaration seams. EGG/Commons decoding and non-null progress
calls throw rather than simulate successful decoding. The first-party ALZ
reader, filename decoding, volume resolver, split stream, ZipCrypto, output guard,
byte-budget class, failure classifier and JDK inflater are real production code.
Standard BZip2 payload routing/decoding through Commons is not verified here.

The test methods run directly; no replacement JUnit assertion runner is used.
JUnit adapter files call the same checks for the real project test task. Their
actual JUnit/Gradle execution remains unverified. Earlier 497/1,158-method suites
were not rerun and are not included in the new totals.

Run from an extracted source root with Python and JDK 17+:

```powershell
python scripts/check-alz-selector-validation.py
python scripts/check-archive-decoder-boundaries.py --work-dir .archive-boundary-tests
```

The runner uses no `-Xmx128m`, `ulimit -n 64`, or maximum archive-file count. The
one-active-volume design, existing free-space checks and other resource policy
remain unchanged. A retained work directory is test evidence, not source; keep it
out of release source packages.

## Release and support boundary

The historical Android test/build attempt stopped at Gradle-download DNS failure
before producing an APK. A subsequent release build completed successfully on
2026-10-11; it did not establish execution of these test suites.

These host checks do not execute real Commons BZip2 decoding, the complete EGG/7z
dispatcher, JNI or Android storage/UI paths. Current EGG LEA/encrypted-solid scope
is described in [current archive support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md), not inferred
from this earlier ALZ validation. Producer/device checks remain separate; do not
disable integrity checks to accommodate unsupported inputs.

## Primary reference cross-checks

- bzip2 1.0.8 decompressor, selector/length/MTF/run/current-block checks:
  https://raw.githubusercontent.com/libarchive/bzip2/bzip2-1.0.8/decompress.c
  (notably selector checks around 264–309, decoded-code checks around 65–92,
  run checks around 356–389 and current-block `origPtr` around 459–463).
- Apache Commons Compress 1.28.0 BZip2 input stream, this file's documented base:
  https://raw.githubusercontent.com/apache/commons-compress/rel/commons-compress-1.28.0/src/main/java/org/apache/commons/compress/compressors/bzip2/BZip2CompressorInputStream.java
- OpenJDK 17 inflater fill contract/order:
  https://raw.githubusercontent.com/openjdk/jdk17u/master/src/java.base/share/classes/java/util/zip/InflaterInputStream.java
  (`fill()` passes the source count to `setInput()` before it returns).

No third-party implementation was copied into production by this follow-up;
existing attribution and licensing notices are retained.
