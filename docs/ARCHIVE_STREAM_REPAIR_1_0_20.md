# Archive stream corrections — Readwide 1.0.20

No builds, tests, native execution or benchmarks ran.
These corrections extend the [codec additions](ARCHIVE_CODEC_ADDITIONS_1_0_20.md);
they do not establish complete RAR/7z interoperability.

## LZ5/Lizard block checksums and frame sizes

Both frame readers now accept the optional block-checksum flag. They read the
packed payload and its four-byte trailer, verify seed-zero XXH32 over that payload,
and only then decode or copy the block. The checksum itself is not passed to the
raw decoder. Existing block buffers reserve four additional bytes; checksummed
stored blocks are buffered, and unchecked stored blocks retain their streaming
fast path. This follows the format block size, not an archive/file-size ceiling.
Large declared blocks still require native block/history memory.

Empty stored blocks no longer act as EndMarks or reset linked history. An explicitly
present zero content size is distinct from an absent size. Declared-size overruns
fail without unsigned subtraction wrapping; underruns still fail at the EndMark.
Content checksums, header checksums, outer 7z lengths and MT boundaries remain checked.
No public frame-info struct, dependency pin, license or worker architecture changes.

References: upstream [LZ5 1.5 framing](https://github.com/inikep/lizard/blob/v1.5/lz5_Frame_format.md)
and [Lizard framing](https://github.com/inikep/lizard/blob/v2.1/doc/lizard_Frame_format.md).
These are format-based local changes, not a claim that all producer variants work.

LzFrameIntegrityTest adds 12 uncompiled/unrun Android/JNI methods: checksum builder,
stored payload/checksum fragmentation, linked LZ5 matches, Lizard inner stored blocks,
failure before block output, truncated trailers, empty blocks, explicit zero/incorrect
sizes, combined block/content checks, MT concatenation and descriptor bounds.
The hand-encoded Lizard case enters its raw decoder but does not exercise Huffman
compression. Real producer fixtures, ARMv7/ARM64 and memory-safety validation remain
pending. Earlier regression counts below describe the previous stream corrections.

## LZ5 history and malformed input

The imported frame reader allocated space for LZ5's 4 MiB dictionary but retained
only 64 KiB in several history-copy/compaction paths inherited from LZ4. Those paths
now use the codec window consistently, including output-buffer reuse and temporary
block flushing. This is a format history window, not a file-size cap.

The raw decoder checks every safe-mode match distance against the 22-bit format
maximum and available prefix/dictionary, even with a full dictionary. Zero and
oversized offsets fail before creating the match pointer. Truncated token and
literal-length-extension reads are checked before dereferencing input. Both frame
readers clear freed pointers before reallocation; LZ5 also accounts separately for
input-block capacity and combined output/history capacity. JNI creates a fresh
context per frame; the reallocation fixes additionally protect future context reuse.

Reference: pinned BSD [LZ5 frame code](https://github.com/mcmilk/7-Zip-zstd/blob/b5ac55ff587ec9c9315ffd624c5c93a2899340d2/C/lz5/lz5frame.c)
and [window/offset constants](https://github.com/mcmilk/7-Zip-zstd/blob/b5ac55ff587ec9c9315ffd624c5c93a2899340d2/C/lz5/lz5common.h).
These are local corrections, not a new upstream version or validation result.
Imported hashes and modified-file flags are in the native module's LZ_SOURCES.json.

## LZ5/Lizard metadata and ownership

LzFamilyInputStream skips metadata frames with magic IDs 0x184d2a51–5f between
data frames, using bounded reads rather than allocating their declared lengths.
ID 0x184d2a50 remains reserved for strict canonical MT framing. Metadata-only input,
truncated metadata, wrong MT lengths and trailing junk still fail. Metadata does
not contribute decoded bytes or replace the requirement for a real data frame.
Optional block-checksummed frames are handled by the local frame changes above;
this supersedes their earlier unsupported status.

Unchecked source failures now retire the native context and remain terminal.
A failed source close can be retried without reopening a decoder. Java transfers
remain 64 KiB; native frame/history memory is additional. No archive payload-size
ceiling or worker thread was added.

The ownership follow-through rejects reentrant reads/close from input or cleanup
callbacks, checks cancellation immediately after source/native reads and preserves
primary native-cleanup errors if source cleanup also fails. Three additional Android
regression methods are uncompiled/unrun; frame grammar and native code are unchanged.

## RAR output efficiency

The streaming unpacker's size-clipping adapter forwards bulk match output to
Rar3PpmdFilterOutput without falling back to byte-by-byte writes. The filter sink
writes plain spans in chunks and buffers only pending filter regions, stopping
precisely at region boundaries. Adjacent/chained filters, raw history, final-match
surplus clipping and downstream progress/CRC accounting remain intact. Cancellation
is checked every 64 KiB of bulk output; interruption retires the sink even if the
thread flag is later cleared. Pure PPMd symbol generation is not newly batched.
Later changes implement within-entry final/intermediate resizing and wholly future
descriptor carry across explicit solid-entry markers. Raw straddling blocks, late
descriptors and unsupported order remain rejected. See the
[current boundary](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md); no speedup is measured.

## Regression sources

Five new host methods cover bulk/bytewise filter equivalence, bounded plain writes,
early size rejection, sticky cancellation and invalid ranges. Eight new Android/JNI
methods cover hand-encoded compressed long-distance LZ5 matches, history compaction,
invalid offsets with a full dictionary, truncated literal lengths, metadata frames,
unchecked input failure and close retry. All are uncompiled/unrun. Hand-encoded
sequences are not real-producer fixtures. ARMv7/ARM64 execution, memory-safety tools,
allocation-failure injection, producer files and full RAR regressions remain pending.

Version remains 1.0.20 / 10020. Pins, licenses, signing, toolchain and Java inventory
(503 app main files, 174 archive files) are unchanged. No F-Droid acceptance is claimed.
