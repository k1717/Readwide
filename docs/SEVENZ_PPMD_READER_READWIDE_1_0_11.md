# 7z PPMd first-party decoder notes

Reference notes for `SevenZPpmd7Decoder`, the first-party decoder for the 7z
PPMd coder (id `03 04 01`), wired into the `SevenZBcj2ArchiveReader` fallback
path alongside BCJ2.

## What it closes

Apache Commons Compress has no PPMd coder ("Unsupported compression method
[3, 4, 1]"), and the bundled native libarchive cannot decrypt 7z, so
**AES-encrypted PPMd archives had no working path** - the last such gap in the
app's 7z coverage. Plain PPMd previously worked only through the native
libarchive fallback; it now also decodes first party (with the native path
kept as a fallback behind it, so a failure in the new code cannot regress
what worked before - see the gating section).

## Provenance (licensing)

The decoder is a Java port of the **public-domain Ppmd7 reference**: Dmitry
Shkarin's PPMd var.H (2001, public domain) as maintained in Igor Pavlov's
Ppmd7 codec ("PPMdH", explicitly public domain - the same code libarchive
vendors for the same reason). Public-domain code carries no license
obligations and is compatible with this project's Apache-2.0 licensing; the
project's standing rule against UnRAR/libarchive/7-Zip-*licensed* code is not
implicated, and the provenance is recorded in `THIRD_PARTY_NOTICES.md` and in
the class javadoc. The reference was obtained from pyppmd's source
distribution (`src/lib/ppmd/Ppmd7.c`, `Ppmd7Dec.c`), whose headers carry the
public-domain notices verbatim.

## Algorithm shape

PPMd var.H is an adaptive context-model coder; unlike LZ-family codecs there
is no framing to parse - correctness means reproducing the encoder's model
*bit for bit*, including its memory allocator:

- **Model memory** is one flat byte array of the size declared in the coder
  properties (order `u8` + memory bytes `u32 LE`; this decoder accepts orders
  2-64 and memory 2 KiB-256 MiB, rejecting anything else up front). Contexts
  (12 bytes: NumStats/SummFreq/Stats/Suffix) and state arrays (6 bytes per
  state) are addressed by byte offsets; a context with one symbol stores its
  state inline in the SummFreq/Stats field. Refs below `UnitsStart` point
  into the text area (pending successors), refs above it to allocated units.
- **The sub-allocator is part of the format.** Unit sizing (the 38-entry
  index table), block splitting, the free-block glue pass (a doubly-linked
  node list built inside the free blocks themselves, coalescing neighbours),
  and the model-restart-on-exhaustion rule all decide *when the model resets*
  - and the encoder and decoder must reset at the same symbol or everything
  after diverges. This is why the port keeps the flat-array layout instead of
  idiomatic Java objects.
- **The range decoder** is the 7z Ppmd7z variant (init byte `0`, 4-byte code,
  bottom normalization at 2^24), distinct from the RAR PPMd range coder - the
  reason the app's existing first-party RAR PPMd machinery could not be
  reused.
- The remaining machinery - SEE escape estimation, binary-context probability
  tables, rescale with its stable insertion sort, `CreateSuccessors` building
  pending contexts from text successors, and `UpdateModel`'s frequency
  seeding of new states - follows the reference exactly.

## Validation

Development followed the same oracle discipline as the BCJ2 work, with one
addition: a full Python reference of the model was written first and debugged
to byte-exactness, then the Java was ported mechanically from it and
re-verified. Oracles: the reference `7z` tool (fixture round trips) and
pyppmd's `Ppmd7Decoder` (raw-stream decodes).

- **Fixture matrix** (all byte-exact through both the Python reference and
  the Java port): orders 2, 3, 6, 8, 10, 16, 32; memory 64 KiB, 128 KiB,
  256 KiB, 1 MiB; payloads of natural text, high repetition, uniform random
  bytes, byte-run mixes, and 30 KB of zeros.
- **Hard paths exercised and instrumented**: the order-32/64 KiB/random
  fixture alone triggers 8 model restarts, 7 free-block glue passes,
  7 rescales, and ~1,200 rare-allocation calls - all byte-exact, confirming
  the allocator matches the encoder's.
- **End to end through the reader**: plain PPMd, and AES-256+PPMd with an
  encrypted header (`-mhe=on`) - listing, whole-archive, and single-entry
  extraction all byte-exact; wrong or missing passwords fail cleanly with no
  partial output ("7z header could not be read (wrong password?)").
- Two self-made fixtures (plain and AES+encrypted-header, 743/849 bytes,
  payload pinned by SHA-256) are embedded in `SevenZPpmdArchiveTest`.

## Gating and fallback order

`ArchiveSupport`'s 7z paths consult `archiveUsesSpecialCoder` (BCJ2 or PPMd
present) before invoking the first-party reader, so ordinary
LZMA/LZMA2/BZip2/plain-AES archives never touch it. If the first-party read
fails for any reason other than a missing password, the helper returns null
and the previous libarchive fallback runs as before - the new code can only
add capability, not remove it.
