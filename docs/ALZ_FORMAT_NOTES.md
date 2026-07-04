# ALZ container notes

Reference notes for `AlzipArchiveReader`. Verified empirically against real
ALZip-created archives (store, deflate, bzip2, ZipCrypto-encrypted, CP949
file names, and a two-segment split set). All multi-byte integers are little
endian. Provenance: the reader is first-party Java; no third-party extractor
code or fixtures are in this repository.

## Layout

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
    method u16                  (0 store, 1 bzip2, 2 deflate)
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

## Encryption (ZipCrypto)

Classic PKWARE keystream keyed from the password bytes. The 12 check bytes
decrypt first; `plain[11]` must equal the CRC's top byte (or 0 when the
data-descriptor bit is set). The same keystream continues into the entry's
data; compression is applied before encryption.

## Split archives

`name.alz` is the first segment; continuations are `name.a00`, `name.a01`,
... in order. The logical archive is a byte-level cut with per-segment
framing:

- every segment of a split set ends with a 16-byte trailer:
  `CLZ\x01 + 8 bytes + CLZ\x03` (more segments follow) or `... + CLZ\x02`
  (final segment);
- a continuation that starts with the ALZ signature carries an 8-byte
  segment header (`sig u32 + version u16 + segment-id u16`) before its
  payload bytes; a continuation without the signature is used as-is.

Reassembly = first segment minus its trailer, then each continuation minus
its header and trailer, concatenated. `SplitVolumeInput` presents this as one
seekable stream, so entry data straddling a boundary decodes normally. The
continuation set must be contiguous from `.a00`; a gap fails cleanly with no
partial output. Verified (CRC) against a real 76 KB two-segment set.
