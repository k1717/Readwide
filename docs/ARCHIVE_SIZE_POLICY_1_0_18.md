# Archive size policy and streaming changes — 1.0.18

## Fixed total ceiling removed

The fixed 128 GiB extraction ceiling is removed.
`ArchiveSupport` no longer rejects an archive based on that constant, and
generic stream counters and native solid-primer drains now check only signed
64-bit counter overflow. ZIPX native codecs receive the entry's declared size,
or `Long.MAX_VALUE` when it is unknown, instead of the former 128 GiB ceiling.

All file-producing engines still use the shared output accounting stream.
Whole-archive operations derive their budget from starting usable storage
minus the existing 64 MiB reserve. Standalone entry outputs now also derive
their fallback budget from the target parent directory's usable space. An
active operation budget takes precedence over that standalone calculation.
Rewrites retain the existing path-aware accounting behavior.

The viewer also removes its separate sequential 2 GiB entry/drain
ceiling and routes image output through that guarded stream too. Guarded Java
writes refresh measurable free space roughly every MiB, including while a solid
EGG block spool and an image output coexist. This is periodic checking, not a
reservation against concurrent writers. See `ARCHIVE_VIEWER_PERFORMANCE_1_0_18.md`.

If storage availability cannot be queried (zero/negative result or security
exception), no fixed size substitute is imposed: counters retain their
signed-long representability boundary and filesystem I/O errors still stop
writes. The starting-space snapshot is not a reservation against other apps
concurrently consuming storage, and this is not compression-ratio bomb detection.
Native data still passes through checked output writes; the unchecked
file-descriptor extraction API is not reintroduced.

## EGG streaming paths

- Removed the blanket 512 MiB file/block guards for Store, Deflate, BZip2 and
  LZMA. These paths already use bounded input and 64 KiB output chunks.
- Existing supported non-solid encrypted and plain split paths use the same
  code. Plain solid entries no longer have that per-file policy ceiling.
  Encrypted solid EGG remains unsupported.
- AZO retains a 512 MiB **per-block** packed/unpacked memory guard because its
  decoder still uses byte arrays. Multi-block files are not restricted to
  512 MiB in total, provided every AZO block fits the decoder guard.
- Non-solid file sizes must match the sum of their declared block sizes.
  Actual streaming output must match each block's declared unpacked size,
  including when the CRC field is zero. Overshoot is rejected before writing
  the offending chunk; truncated output fails and is cleaned up.
- AZO output length is also checked, including zero-size declarations.
  Negative file sizes and overflow while accumulating solid offsets fail
  explicitly instead of becoming a different target range.

EGG block length fields are unsigned 32-bit values; file sizes and cumulative
positions use signed Java longs. Removing policy caps does not change the
format's field widths or filesystem/device constraints.

## Other decoder boundaries

The previous RAR5 streaming change removed its 64 MiB packed / 256 MiB
unpacked per-file caps. Encrypted temporary paging removes its 64 MiB history
ceiling; RAM remains bounded while older
history consumes storage. See `RAR5_RAR7_PAGED_HISTORY_1_0_18.md`. RAR3 PPMd now
also streams without its old packed-entry/cumulative-output caps, retaining
32 MiB of rolling history and its model heap; see
`RAR3_PPMD_AND_RAR5_CHECKSUMS_1_0_18.md`.
7z PPMd streaming removes the branch's 512 MiB packed/decoded array guards;
its 256 MiB model guard and the 512 MiB metadata guards remain.
The mixed-capable RAR path also streams production RAR classic-LZ output through a bounded
standard-filter queue. The mixed raw history grows with actual output from 4 MiB
to at most 32 MiB; it is not a file-size limit. See `RAR_MIXED_AND_7Z_CODERS_1_0_18.md`.
See `PPMD_LZ_STREAMING_GAPS_1_0_18.md`; these changes are runtime-unverified.
EGG AZO blocks retain their separate memory/size boundaries.
The 7z BCJ2 fallback now streams its Copy/LZMA/LZMA2/AES chains
without the former 512 MiB file/folder cap; see
`SEVENZ_STREAMING_READWIDE_1_0_18.md`. Unsupported codecs remain unsupported. No claim of unlimited
compatibility across all archives is made.

## Verification status

Regression sources cover streaming-method eligibility above 512 MiB and
2 GiB, AZO memory guards, negative sizes, solid offset overflow, mismatched
block/file lengths, preserved free-space reserves, and counters crossing
128 GiB without writing huge files. A generated 513 MiB EGG Deflate output
test is opt-in with the JVM property `readwide.largeArchiveTests=true`.

The maintainer reported a successful 1.0.18 build before the later timeout-field
and translation changes; those follow-ups are not covered. Unit-test and device
results have not been reported; see [release readiness](RELEASE_READINESS_1_0_18.md). Static source/package checks do not establish runtime success.
