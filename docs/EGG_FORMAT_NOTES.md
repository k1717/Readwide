# EGG container format notes (Readwide first-party reader)

Reference notes for `EggArchiveReader` / `SplitVolumeInput`. The layout below was
implemented as first-party Java from public EGG container concepts and then
**empirically verified against real ALZip-created EGG archives** (normal,
LZMA-compressed, Unicode-named, encrypted-flagged, and two-volume split
archives). The container parser is first-party Java, but the AZO decoder is a
modified Java port of zlib-licensed kippler/xunazo; other codecs use the bundled
FOSS dependencies. Test sources contain generated and embedded fixtures.
See `../THIRD_PARTY_NOTICES.md` for attribution. Historical fixture results do
not establish runtime verification of the later source-only patches.

All integers are little-endian. All top-level structures are identified by a
uint32 signature.

## 1.0.18 size-policy update

Metadata parsing rejects declared prefix/entry-extra/block ranges that exceed the
logical archive bounds and file sizes outside signed-long representation before
publishing metadata. Unknown extras within bounds and the existing missing-block-END
legacy layout remain accepted. The zero-CRC policy described below is unchanged.

The non-solid index adds non-solid entry/block metadata reuse for listing,
password/solid probes and single-member lookup. All declared volume prefixes
are resolved on reuse, with per-volume stat/payload-window identity. Cached
metadata never authorizes passwords or skips block CRC/AES authentication.
Retention budgets do not cap extraction size, and solid archives are not
retained by this index. Directory classification follows decoded filenames.
No new codec support or runtime validation is claimed; see
`DEV_CHANGES_1_0_18.md` for implementation details.

Store/Deflate/BZip2/LZMA no longer inherit the old 512 MiB per-file/block
ceiling. AZO retains a 512 MiB per-block array-memory guard. Non-solid file
sizes must match their block totals, decoded blocks must match declared
lengths, and solid offset arithmetic rejects overflow. The shared fixed
128 GiB ceiling has also been removed; available-space accounting remains.
See `DEV_CHANGES_1_0_18.md` for scope and implementation details.

## Signatures

| Name | Value |
| --- | --- |
| EGG header | `0x41474745` ("EGGA") |
| FILE | `0x0A8590E3` |
| FILENAME | `0x0A8591AC` |
| BLOCK | `0x02B50C13` |
| ENCRYPT | `0x08D1470F` |
| WINDOWS_FILEINFO | `0x2C86950B` |
| POSIX_FILEINFO | `0x1EE922E5` |
| COMMENT | `0x04C63672` |
| SPLIT | `0x24F5A262` |
| SOLID | `0x24E5A060` |
| END | `0x08E28222` |

## Logical archive layout

```
EGG header (14 bytes): signature u32, version u16, header id u32, reserved u32
archive-level extra fields (SPLIT, SOLID, ...)   <- may be empty
END                                              <- prefix terminator (always
                                                    present in real ALZip files)
FILE ... FILE                                    <- entries
END                                              <- archive terminator
```

An extra field (at archive level and inside FILE) is:
`signature u32, flags u8, size (u16, or u32 when flags bit 0 is set), payload[size]`.
FILENAME payload: optional locale code page u16 (when flags bit 5 is set)
followed by the name bytes (UTF-8 or the locale code page).

A FILE entry is:
```
FILE sig, file id u32, uncompressed size u64
extra fields (FILENAME, WINDOWS_FILEINFO, ENCRYPT, ...)
END                                              <- per-file extras terminator
BLOCK ... BLOCK                                  <- the file's data blocks
```
Blocks run until the next FILE or the archive END; there is no separate
per-file trailing END after the blocks.

A BLOCK is:
```
BLOCK sig, method u8, method hint u8, uncompressed u32, compressed u32, crc32 u32
END                                              <- block header terminator
data[compressed]
```
The current reader verifies a block's CRC32 when the field is nonzero. A zero
CRC field is treated as absent by the existing decoder, not compared to a
computed zero CRC. Declared decoded lengths are still checked; supported AES
entries also require footer authentication. Do not describe zero-CRC plain
blocks as checksum-verified.

Compression methods: 0 Store, 1 Deflate (raw), 2 BZip2, 3 AZO, 4 LZMA.

### LZMA block data preamble

The first 9 bytes of an LZMA block's data are a preamble: 4 bytes of
version/props-size words, then the 5-byte LZMA properties (props byte at
offset 4, dictionary size u32 at offsets 5..8). The raw LZMA1 stream starts at
offset 9 and decodes with the entry's known uncompressed size. Reading the
properties from offset 0 (a mistake an implementation can make if it assumes
the preamble is `props + dict + 4 unknown`) decodes garbage on real files.

## Split volumes

ALZip writes split archives as `name.vol1.egg`, `name.vol2.egg`, ... Each
volume is itself an EGG file whose header prefix carries a SPLIT field with
payload `prev u32, next u32`:

- `prev` is the **header id of the previous volume** (0 in the first volume);
- `next` is the header id of the next volume (0 in the last volume).

The logical archive is a plain byte-level cut: the **first volume is kept
whole** (its own header prefix, including its SPLIT field, is part of the
logical stream) and every later volume contributes only the bytes **after its
own header prefix**. Blocks may straddle volume boundaries at arbitrary byte
positions. `SplitVolumeInput` presents this concatenation as one seekable
stream. The resolver catalogs sibling
`volN` names by numeric ordinal, independent of case and leading zeroes, and
rejects ambiguous aliases or missing numbers. Both links must match: each
volume's `prev` equals the previous header id, and its own header id equals the
previous prefix's `next`. Public archive APIs resolve a continuation to the
same uniquely identified first part; direct first-party reader calls still
expect the first part. Missing members and link mismatches fail before payload
decoding. These changes have source regressions only, not runtime validation.

## Encryption

The ENCRYPT extra field (0x08D1470F) on a FILE carries `EncryptMethod u8`
followed by method-specific data:

- **Method 0 - ZipCrypto** (supported): payload is the 12-byte encrypted
  check data followed by a `u32 LE` CRC32. Decryption uses the classic PKWARE
  keystream: initialize the three keys from the password bytes, decrypt the
  12 check bytes, and require `plain[11] == crc >> 24` (wrong password fails
  here, before any output). The *same* keystream then continues into the
  file's block data in block order - one decryptor per file, never reset at a
  block boundary (resetting decodes block 2+ as garbage; verified against a
  real two-block file). Data is compressed first, then encrypted, so
  extraction is decrypt -> decompress -> CRC. The stored payload is
  ciphertext end to end, including the 9-byte LZMA preamble and AZO framing.
  Verified end to end (CRC) against a real ALZip ZipCrypto file.
- **Methods 1/2 - AES-128/AES-256** (supported): the payload after the method
  byte is `salt (8 bytes for AES-128, 16 for AES-256) + 2-byte password
  verifier + 10-byte footer`. This is the WinZip AES construction:
  PBKDF2-HMAC-SHA1 with **1000 iterations** over the raw password bytes and
  the salt derives `AES key (16/32) + HMAC-SHA1 key (16/32) + verifier (2)`;
  the stored block data is **AES-CTR ciphertext with a 16-byte little-endian
  counter starting at 1**, and the footer is the first 10 bytes of
  HMAC-SHA1 over the file's ciphertext (encrypt-then-MAC). One context spans
  the file: keystream and MAC run continuously across the file's blocks.
  Wrong password fails at the 2-byte verifier before any output; the footer
  is checked after the last block ("EGG AES data authentication failed").
  Password bytes are tried as UTF-8 and, for non-ASCII passwords, as
  Windows-949 (legacy ALZip). As with ZipCrypto, the stored payload is
  ciphertext end to end, so extraction is decrypt -> decompress -> CRC.
  *Provenance*: implemented from the public EGG Specification 1.0 byte
  layout plus the published WinZip AES scheme with JCE primitives only
  (`Cipher AES/ECB`, `Mac HmacSHA1`, first-party PBKDF2); no vendor code was
  ported. The scheme was confirmed as a black-box test: first-party-built
  AES-128/256 store and deflate archives decrypt byte-identically through
  ESTsoft's own `unegg` 0.5 decoder with the password. That decoder also
  finalizes the footer MAC after *every* block, which can only verify for
  single-block files - so real ALZip AES files are single-block; our
  whole-file MAC check is equivalent there and stricter for hypothetical
  multi-block input.
- **Methods 5/6 - LEA-128/LEA-256** (not supported): reported as unsupported
  by name after the password prompt; no partial output.

## Solid archives (supported)

The SOLID field (0x24E5A060, empty payload) in the archive prefix marks a
solid archive: **all FILE headers come first, then the archive's blocks**,
and the decoded concatenation of those blocks is every file's data back to
back in file-header order (the parser attaches those trailing blocks to the
last entry; extraction collects them across entries in order). Extraction is
one sequential pass that splits the decoded stream by each entry's declared
size; nonzero block CRC32 values are checked as in the non-solid path; zero
retains the absent-check behavior. Extracting a single entry decodes from the
stream start, discards bytes outside the entry, and finishes its final containing
block before success so its available CRC is checked too. The image viewer can
retain one forward session with a length/available-CRC-checked block spool reused
across entries; see `DEV_CHANGES_1_0_18.md` for disk/latency tradeoffs
and pending runtime validation. A stream shorter than the declared sizes
fails with "Solid EGG stream ended before entry data" and no partial output.

*Provenance*: layout and CRC semantics per the public EGG Specification 1.0
solid example, validated as a black box against ESTsoft's `unegg` 0.5: solid
deflate fixtures with one block and with a block boundary falling inside a
file both extract byte-identically through it. (Two `unegg` CLI quirks
surfaced while validating and are quirks of that tool, not the format: its
store coder ignores requested pop sizes, and it checks the block CRC as soon
as the compressed input is exhausted, which misfires when an entire block
fits one 4 KB read and small files follow. The spec's own solid example CRC
byte `9E 83 48 6D` equals CRC32("ab"), not CRC32("abc") - a documentation
typo.) Encrypted solid archives remain unsupported.

## Not supported (deliberate)

The shared ALZ/EGG `SplitVolumeInput` has physical/logical
range checks, cancellation/failure retirement and independent bounded-view
lifecycle. Binary-search volume lookup and reusable one-byte storage reduce
specific overheads but have not been benchmarked. Raw skipping does not verify
payload bytes; EGG's length, available-CRC and AES checks remain separate. These
changes do not expand the codec/encryption scope below.

- **LEA-encrypted entries**: detected and reported by method name.
- **Encrypted solid archives**: entries are listed but extraction is refused
  rather than risking partial or corrupt output.
