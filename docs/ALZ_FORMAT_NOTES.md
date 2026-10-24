# ALZ container notes

Reference notes for `AlzipArchiveReader`. Earlier empirical checks used real
ALZip-created archives (store, ordinary deflate, bzip2, ZipCrypto-encrypted, CP949
file names, and a two-segment split set). They do not validate the new method-3
decoder, whose regression sources are uncompiled/unrun. All multi-byte integers are little
endian. Provenance: the container parser is first-party Java. Its trimmed-BZip2
decoder is a modified Apache Commons Compress source file, with attribution
retained in `AlzBzip2InputStream.java`; no unalz extractor code is included.
Synthetic and embedded codec fixtures are present in the test sources. See
`../THIRD_PARTY_NOTICES.md` for the decoder provenance.

## Support boundary

Readwide retains methods 0/1/2 (Store, the supported BZip2 variants and ordinary
Deflate) and adds a separate method-3 Obfuscated Deflate decoder in source.
Bandizip 6.29 binary inspection shows a separate method-3 dispatch, and its
[official format guide](https://en.bandisoft.com/bandizip/help/how-to-open-alz-and-egg-files/)
lists Obfuscated Deflate separately. Public XADMaster supplies the missing format
contract described below; no proprietary decoder or XADMaster implementation is
incorporated. Unknown methods still fail without replacing existing output.
Method-3 synthetic sources cover plain/encrypted/split paths, but no new producer
fixture, decoder execution or device validation is claimed.

## Layout

Physical length and modification time are captured before header/trailer reads,
checked after parsing and carried into `SplitVolumeInput` segment adoption. An
observable change between framing validation and reader creation is rejected,
including changes only to an omitted continuation header or intermediate trailer.
Single files and raw continuations carry the same baseline; payload trimming and
final-trailer retention are unchanged. Handoff regression sources are uncompiled/unrun.

After parsing and before file acceptance, ALZ checks existence, physical length
and modification time for every captured volume, including an already-open handle
and earlier split parts. Changes after final byte-progress callbacks still roll
back current output; earlier verified files remain. No per-byte metadata probes,
new decoder or source locking is added. Same-metadata edits/replacements and
final-check races remain limitations. Added regression sources are uncompiled/unrun.

ALZ extraction checks cancellation after decoder/output closure and before target
commit. A request from the final byte-progress callback therefore restores an
existing destination or removes the new one. Earlier verified entries remain.
The added regression source is uncompiled/unrun; codec and ZipCrypto rules are unchanged.

In 1.0.21, shared `SplitVolumeInput` also retires on unchecked volume-access/read
failures after a partial prefix. Existing bounded views cannot replay that prefix
after access returns. Volume framing, ZipCrypto and decoder selection are unchanged;
the new injected-failure regression source is uncompiled/unrun.

Local-header DOS timestamps are decoded non-leniently in the local timezone for
entry listings; absent/invalid calendar values remain unknown (`0`). File-system
mtime restoration is unchanged. Password probes require an indexed encrypted file;
a truncated header, missing part or parse error alone is not encryption evidence.
Listing/extraction still report their actual errors. Three new regression methods
and the malformed split-probe expectation are uncompiled/unrun.

In 1.0.20, explicitly terminated zero-member ALZ archives list/extract successfully.
The parser rejects unknown/partial inter-entry records and incomplete central endings
before publishing an index or writing output. It preserves legacy nonempty exact-EOF
layouts and the existing treatment of bytes after an accepted terminator; this is
not a claim of complete truncation detection. Fourteen new regression methods are
uncompiled/unrun; producer empty/comment/split validation remains pending.

Optional `ELZ\x01` (`0x015a4c45`) comments use the unsigned size in the first metadata
word of a terminal `CLZ\x01 + size + metadata + CLZ\x02` trailer. The size includes
the ELZ signature and must end exactly before that trailer. Each comment has a
four-byte file index, two-byte byte count, then that many text bytes. The parser
checks these bounds and skips the text without allocating it. It does not display
comments or interpret embedded signatures as file headers. This implements the
[published ALZ implementation notes](https://github.com/alkegi/docs/blob/master/alz.md),
not a producer-validated extension. No extractor code was copied.

The 1.0.20 standard BZip2 path consumes concatenated streams within the declared
payload, including when only the first leading BZ prefix is stripped. It retains
decoded-size/CRC/encryption checks and rejects malformed later streams or trailing
junk. The distinct DLZ variant decoder is unchanged. New encrypted/split and
rollback regression sources are uncompiled/unrun; producer validation is pending.
See [decode-boundary notes](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

In 1.0.18, listing and single-entry extraction reuse an
immutable local-header index (including archive-wide name decoding). All ordered
volume paths/sizes/timestamps participate in invalidation; payload password/CRC
checks still run independently. Retention is bounded, larger indexes work
uncached, and no file handles or decrypted data are cached. This changes lookup,
not container/codec support. Source regressions are unexecuted; see
`DEV_CHANGES_1_0_18.md` for implementation details.

```
"ALZ\x01" (0x015a4c41)  version u16  segment-id u16
repeat:
  0x015a4c42 local file header
    nameLen u16
    fileAttribute u8            (0x10 = directory)
    fileTimeDate u32            (DOS time)
    descriptor u16              low byte: bit0 encrypted, bit3 data descriptor,
                                high nibble = size-field width (0/1/2/4/8)
    -- present only when the size nibble is non-zero --
    method u8                   (0 store, 1 bzip2, 2 deflate, 3 obfuscated deflate)
    reserved u8
    crc32 u32
    compressedSize  uN
    uncompressedSize uN
    -- always --
    name bytes[nameLen]         (UTF-8 if valid, else CP949; ALZip bakes '?'
                                 for characters CP949 cannot encode)
    encrypted check data [12]   (only when descriptor bit0 set)
    data bytes[compressedSize]
terminator: 0x015a4c43 ("CLZ\x01") ... 0x025a4c43 ("CLZ\x02")
```

A zero size nibble means the method/CRC/size fields are entirely absent
(directories and empty files); the entry still carries its name.

## Compression method 1: the ALZ bzip2 variant

Extraction and password probes now pass the optional operation token into the
trimmed decoder. Existing bit/output checkpoints, successful input refills and
inverse-BWT table preparation observe Cancel/Pause. Constructor/factory overloads
preserve tokenless callers; I/O failures remain sticky and the owning factory
closes failed construction. Codec arithmetic, framing, CRC and cipher ownership
are unchanged. Seven added regression method sources are uncompiled/unrun.
Standard third-party BZip2 buffered loops and individual allocations are not made
preemptible by this change. Bandizip 6.29 static dispatcher inspection corroborates
separate decrypt/decode/final-integrity responsibilities, not executed compatibility.

Method 1 payloads come in **two flavors**. Some real archives carry plain
bzip2 (with or without the `BZh` magic; one such real file was CRC-verified
during the 1.0.11 revalidation), while ALZip 4.x-era archives carry a
**trimmed bitstream variant that standard decoders cannot read** - prepending
a `BZh` magic does not help because every block's framing differs at the bit
level. `openAlzBzipStream` selects the decoder from the first payload bytes,
which is deterministic because the variant's first block header is byte
aligned at offset 0 (`DLZ` -> variant, `BZh`/`h<digit>` -> standard).

Relative to standard bzip2, the variant (facts per the zlib-licensed `unalz`
reference decoder, `UnAlzBz2decompress.c` by kippler@gmail.com):

- no `BZh<level>` stream magic and no block-size byte; the block size is
  implicitly 900k (`'9'`);
- each block starts with the 32 bits `'D','L','Z',0x01` instead of the
  48-bit pi magic `0x314159265359`;
- end of stream is `'D','L','Z',0x02` instead of the 48-bit sqrt-pi magic,
  with **no 32-bit combined CRC** after it;
- the 32-bit stored block CRC and the 1-bit "randomised" flag are absent, so
  every following bit shifts by 33.

Everything after that point in a block (`origPtr`, mapping table, selectors,
Huffman payload) is bit-identical to standard bzip2. `AlzBzip2InputStream`
decodes this variant; it is a modified copy of Apache Commons Compress
1.28.0's `BZip2CompressorInputStream` (Apache-2.0, same license as the app,
modification notice in the file header) with the framing changes above and
the CRC/randomised machinery removed - integrity comes from the ALZ
container's per-entry CRC32, which is verified after decoding as for every
other method.

*Provenance*: the variant was confirmed empirically - standard `bz2` output
was bit-exactly transformed into this framing and both a single-block (900k)
and a four-block (100k level-1) stream extract byte-identically through a
compiled `unalz` 0.65. The embedded test fixtures in
`AlzipArchiveReaderTest` are those oracle-validated streams. No `unalz` code
was ported; only the bitstream facts above (its zlib license would permit it,
but the Apache-2.0 commons-compress base needed fewer changes).

## Compression method 3: Obfuscated Deflate

`AlzObfuscatedDeflateInputStream` follows [RFC 1951](https://www.rfc-editor.org/rfc/rfc1951.html)
with one format variation: the dynamic header's 19-symbol code-length order.
The public [XADMaster ALZ parser](https://raw.githubusercontent.com/MacPaw/XADMaster/master/XADALZipParser.m)
specifies an identity ordering, then swaps position `i` with
`3 * (i % 6) + (unpackedSize % 16)` for `i=0..18`; an index greater than 18 is
reduced modulo 18, while index 18 stays 18. Its
[Deflate reader](https://raw.githubusercontent.com/MacPaw/XADMaster/master/XADDeflateHandle.m)
uses that order only for the dynamic code-length alphabet. Bandizip static
inspection corroborates the table seam and normal Deflate mode. These are format
facts, not a port of either implementation.

Readwide uses the nonnegative long entry size, not a signed 32-bit narrowing.
Stored/fixed blocks, repeat codes and cross-block LZ77 history retain standard
Deflate rules. The 32 KiB history is a format window, not an entry-size cap.
Huffman validation rejects oversubscribed/incomplete trees except permitted
single-code/empty-distance cases; invalid references and truncation fail.
Failures are terminal and closing owns/wipes the compressed buffer and history.
Progress checkpoints include empty-block chains, input refill and buffered output.

Fixed Huffman trees are created lazily once per decoder; dynamic trees consume
checked length-array ranges without changing local symbol numbers. Bulk reads
copy pending stored bytes and validated matches in runs bounded by the existing
4,096-byte output checkpoint. Match copies seed from old ring history before
repeating the initialized prefix, preserving overlapping distances and wrap.
The scalar path and bit grammar remain unchanged. Regression sources cover
mixed reads, short refills, encrypted splits, cancellation and rollback, but
are uncompiled/unrun; no throughput result is claimed.

Selected/bulk extraction and password probes use the same decoder after bounded
split input and optional ZipCrypto. Existing length/CRC, source-change,
free-space and output-rollback checks remain. Synthetic sources cover table
residues, shortened headers, repeats, window wrap, errors, cancellation and
encrypted splits. They are uncompiled/unrun; real method-3 archives, especially
large-size and encrypted/split combinations, still need interoperability checks.

The encrypted/split recheck found no new mismatch in the reviewed production
paths. Bandizip static inspection retains one ZipCrypto key state across input
chunks; the method-3 route decrypts before decoding and uses the shared final
check without an additional ZipCrypto authentication trailer. Readwide likewise
keeps one post-header cipher and decoder across logical split segments. This is
static corroboration, not an observation of successful extraction.

Seven additional regression sources cover 70,003-byte encrypted payloads with
UTF-8/MS949 header collisions, cuts around 16 KiB input refills, 64 KiB output
chunks and unchanged progress during candidate probes. Other cases place every
cut of the 12-byte check header under the existing data-descriptor flag, combine
stored/fixed/dynamic blocks with a 32 KiB backward distance, reject wrong
passwords or damaged payloads, change either physical volume at final progress,
and isolate state across empty and mixed-method entries. These sources have not
been compiled or run. Decoder/cipher/container code is unchanged by this recheck;
producer fixtures and runtime regression validation remain outstanding.

## Encryption (ZipCrypto)

The streaming adapter reuses private one-byte scratch for scalar reads rather
than allocating on every call. Bulk reads, key updates and split-input ownership
are unchanged. Close clears both scratch and key state even when source closure
fails. Focused regression sources cover mixed reads and cleanup; they are
uncompiled/unrun and do not establish a measured performance improvement.

Classic PKWARE keystream keyed from the password bytes. The 12 check bytes
decrypt first; `plain[11]` must equal the CRC's top byte (or 0 when the
data-descriptor bit is set). The same keystream continues into the entry's
data; compression is applied before encryption.

In 1.0.20, `AlzipZipCrypto.checked` preserves the legacy low-byte-per-Java-char
interpretation first. Non-ASCII passwords can also use strict UTF-8 and MS949
bytes, but only when the caller enforces a payload CRC. For supported ALZ methods, collect
distinct header-matching key states; if more than one matches, check each copy
against decoded length/CRC before opening the output writer. Source views
are independently bounded and use a 64 KiB working buffer, not a whole-file spool.
The selected key state is fresh for extraction, which still checks length and CRC.
Decoder format/length/CRC rejection can advance candidates; tracked source, cancellation, close and unchecked
errors abort. Probes do not advance extracted-byte progress. A single header match
avoids the extra read. Temporary encoded password buffers and rejected/probe key
states are wiped; no immutable password String is created.

The one-byte header and payload CRC are not cryptographic authentication or password
recovery. The shared factory covers ordinary/Obfuscated Deflate, standard/stripped BZip2 and DLZ. Source
failures are recorded below the decoder; resource closure is outside the content-
rejection catch. EGG now supplies its own multi-block verifier, described in
[EGG notes](EGG_FORMAT_NOTES.md). Nineteen synthetic
crypto/container/probe methods are uncompiled/unrun; real non-ASCII producer checks remain
pending. No new size limit is introduced and free-space protection is unchanged.

## Split archives

Shared-stream safeguards: `SplitVolumeInput`
validates physical segments and logical payload windows, retires handles after
cancellation/physical I/O failure, and gives bounded views independent cursors
and close state. Raw positional skipping does not verify skipped data; ALZ's
decoded-length/CRC checks remain separate. Binary-search volume lookup and
reusable one-byte storage are unbenchmarked, with no codec or size-policy change.

`name.alz` is the first segment. Continuations advance from `name.a00` through
`name.a99`, then `name.b00` and onward through the implemented `name.j99` range.
The [original unalz implementation](https://sources.debian.org/src/unalz/0.65-7/UnAlz.cpp/)
generates this letter rollover. Readwide also retains its wide decimal `.aNN`
compatibility names: `.a100` aliases `.b00`, `.a200` aliases `.c00`, and larger
decimal names remain available beyond the letter range. Two aliases for one
ordinal fail as ambiguous. Mixed spellings/case can form one contiguous set;
there is no new volume-count cap or loop through absent ordinal numbers.

First-part routing, extraction, source snapshots and index invalidation use the
same catalogue. RAR `.r00`/`.s00` suffixes remain separate. Alphabetic suffixes
beyond `.j99` and noncanonical `.b100` names are not claimed. Twelve new synthetic
naming/routing regression methods are uncompiled/unrun; ALZip-produced rollover
sets still need validation. No reference extractor code was copied.

The logical archive is a byte-level cut with per-segment framing:

- every segment of a split set ends with a 16-byte trailer:
  `CLZ\x01 + 8 bytes + CLZ\x03` (more segments follow) or `... + CLZ\x02`
  (final segment);
- a continuation that starts with the ALZ signature carries an 8-byte
  segment header (`sig u32 + version u16 + segment-id u16`) before its
  payload bytes; a continuation without the signature is used as-is.

The current parser removes intermediate trailers and continuation headers, retains
the last framed trailer for end/comment checks, and leaves unframed continuations
unchanged. Retaining that trailer does not move payload offsets or make its bytes
available as entry data. `SplitVolumeInput` presents the result as one
seekable stream, so entry data straddling a boundary decodes normally. The
continuation set must be contiguous from `.a00`; a gap fails cleanly with no
partial output. The earlier split implementation was verified (CRC) against a real
76 KB two-segment set; the new final-trailer/empty/comment paths require revalidation.
