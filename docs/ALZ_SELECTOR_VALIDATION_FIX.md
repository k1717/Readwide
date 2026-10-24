# ALZ selector validation — Readwide 1.0.20

## Scope and provenance

This retained host-validation record uses source ZIP SHA-256
`0ea72b839b2dd603be50ffb8fac66b796138ce5ef25a7bca4f26fb90165b3783`.
That input already contained the selector fix. The review added group-count
validation and focused checks to `AlzBzip2InputStream`, without changing other
production decoders. It is not a fresh run of the current complete release.

## Correct behavior in recvDecodingTables()

1. Require `2 <= nGroups <= N_GROUPS` (six). This additionally rejects a one-group
   stream that the uploaded implementation accepted.
2. Reject `selectors <= 0` before selector or payload decoding. The field is an
   unsigned 15-bit value; the previously negative-only check did not reject zero.
3. Check the integer on every unary increment, before any byte narrowing:

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

4. Use `checkBounds(v, nGroups, "selectorMtf")` when undoing the move-to-front
   selector values. The six-slot storage capacity is not the active group count.
5. Validate *all* declared selectors, including those beyond retained storage.
   Keep the existing compatibility behavior that consumes valid surplus selectors
   without storing them. This does not introduce a smaller selector-count limit.

The increment check prevents both byte wrapping and long unchecked unary runs:
the count cannot progress beyond the active group bound. The final integer check
also makes the pre-cast invariant explicit. Empty end-marker-only streams remain
valid because they contain no data block or selector list.

### Reference cross-check

The vendored bzip2 decoder in
`third_party/libarchive-android/library/src/main/jni/external/bzip2/decompress.c`
checks the same group range, positive selector count, and unary active-group bound.
The independently retrieved bzip2 1.0.8 source has matching behavior:
https://raw.githubusercontent.com/libarchive/bzip2/bzip2-1.0.8/decompress.c
(selectors section, lines 264–304 in the retrieved source). ALZ uses different block
framing; the selector-table checks are the portion compared here, not proof of all
ALZ format compatibility.

## Recorded host validation

All three runs used the same new 19-check dependency-free host suite, compiling the
real production `AlzBzip2InputStream` with `javac --release 17` on OpenJDK 21.0.11.
Only a compile-time `androidx.annotation.NonNull` declaration is supplied. There
are **no decoder stubs and no JUnit-compatibility runner**. Normal JVM settings are
used; no 64-descriptor or 128 MiB restriction is imposed.

| Decoder under test | Passing checks | Failing checks |
|---|---:|---:|
| Uploaded file, unchanged | 16 | 3 |
| Reconstructed quoted vulnerable selector logic | 8 | 11 |
| Packaged corrected file | **19** | **0** |

These are 19 named checks with parameter loops, not 19 independently generated
archives or a full-application test count. The unchanged upload's three failures
are the group-count checks: zero and seven groups already failed but had different
diagnostics; **one group was actually accepted**. Do not interpret all three as
newly accepted corruption. The reconstructed baseline reverses only the selector
hunks quoted in the request in a copy of the uploaded decoder. It is explicitly
**not** a recovered local source snapshot.

### Before/after reproduction

| Input | Reconstructed vulnerable logic | Corrected file |
|---|---|---|
| Existing real-fixture payload plus 256 unary one-bits | Accepted; exact original bytes | Selector error |
| Same, plus 512 unary one-bits | Accepted; exact original bytes | Selector error |
| Same, plus 768 unary one-bits | Accepted; exact original bytes | Selector error |
| Two groups, selector value two | Accepted | Selector error |
| Zero selectors in an otherwise decodable block | Accepted | Selector-count error |
| One active group | Accepted | Group-count error |

"Existing fixture" refers to the fixture already present in the uploaded tests;
its claimed vendor/oracle provenance has not been newly independently verified.
Fresh probes check actual decoded bytes, rather than just inspecting a cast.

### Coverage

- Existing single-block fixture remains byte-exact (1,240 bytes).
- Existing three-block fixture remains byte-exact (260,000 bytes).
- Every valid selector for group counts two through six decodes a complete
  synthetic one-byte stream (20 combinations).
- Short compressed reads of one through 19 bytes preserve decoding.
- Selector values 255, 256, 257, 511, 512, 513, 768, and 1,024 are rejected for
  group counts two through six; inactive selectors below six are also rejected.
- Zero selector counts and group counts zero, one, and seven fail explicitly.
- Valid lists of 18,002, 18,003, and 32,767 selectors remain decodable for two and
  six groups. Malformed selectors at and beyond the retained boundary fail.
- A source with no unary terminator and one-byte input reads fails at the active
  bound, without reading onward to find a terminator.
- 129 consecutive synthetic blocks decode exactly with changing group counts.
- A malformed later block retains its original terminal IOException on subsequent
  scalar and bulk reads; a later zero-selector block is also rejected.
- Truncated selector lists fail; a valid end-marker-only stream stays empty.

Synthetic streams intentionally give all active Huffman groups identical valid
codes so each legal selector can be exercised without changing expected output.
The regression suite is not a fuzzer or a new decompression implementation.

## Re-run the checks

From the extracted project root, with Python 3.9+ and JDK 17+ on PATH:

```powershell
python scripts/check-alz-selector-validation.py
python scripts/check-alz-selector-validation.py --probe
```

The same 19 methods have a normal JUnit adapter:
`app/src/test/java/com/readwide/manager/archive/AlzSelectorBoundaryTest.java`.
The original seven-method `AlzSelectorValidationTest` and 14-method
`AlzBzipStreamRound6Test` are retained unchanged. Their full Gradle execution,
including the original dispatch/rollback integration tests, was **not completed**.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.readwide.manager.archive.AlzSelectorValidationTest" --tests "com.readwide.manager.archive.AlzSelectorBoundaryTest" --tests "com.readwide.manager.archive.AlzBzipStreamRound6Test"
```

## Release boundary

The historical Gradle test attempt stopped at a download DNS failure before
compilation. A subsequent release build completed successfully
on 2026-10-11; that success does not establish execution of these test suites or
native/device interoperability.

Use the current complete source package. No old hotfix-only ZIP or incremental
copy instruction is needed. The [ALZ archive validation](ALZ_ARCHIVE_BOUNDARY_REVIEW.md)
records the broader decoder/container checks, and [current support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md)
describe later format additions and remaining exclusions.
