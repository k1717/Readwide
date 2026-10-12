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

## 1.0.20 container compatibility (source reviewed, not executed)

ZipCrypto password encodings now use read-only block probes when multiple distinct
legacy/UTF-8/MS949 states pass the one-byte header check and all owned blocks have
nonzero CRCs. Verify every block's decoded length/CRC with one continuing cipher;
consume remaining packed tail bytes after successful decoder closure, without
replaying prefetch. Select before creating an output stream or encrypted-solid
packed spool. Store/Deflate/BZip2/AZO/LZMA share extraction decoders with probes.
Single matches avoid an extra pass; no-CRC behavior remains legacy-only. Probes
write no output and add no extracted-byte progress. Source errors, cancellation,
close failures, unsupported physical metadata and resource failures stop selection;
only rejected candidate content advances it. Selected and temporary ZipCrypto key
states are cleared. AES/LEA authentication and guarded output remain unchanged.
AZO still uses arrays and actual heap/representation constraints, not a new cap.
Five container and eight probe methods are uncompiled/unrun, including mixed-codec
solid routes, split continuation, packed tails and rollback. Real-producer/Android
validation and encrypted filenames remain pending. CRCs are not authentication.

Encrypted-solid extraction is now wired through `SolidBlockSession`: encryption
state belongs to the header owning each packed block, not to the file receiving a
decoded slice. ZipCrypto continues across that owner's blocks; AES/LEA additionally
checks the complete owner's HMAC before opening staged packed bytes for decoding.
The spool uses guarded disk writes, retains one owner at a time, and is removed on
close/failure; forward sessions clone and clear their password. Plain blocks keep
direct access. Decoded-size/nonzero-CRC checks remain. Seven synthetic tests are
uncompiled/unrun, including multi-block authentication and early-file rollback;
actual producer-created encrypted-solid/split/AZO/LZMA/LEA validation is pending.
This does not add continuous codec state across packed blocks or encrypted names.
The [independent extraction reference](https://github.com/alkegi/unegg/blob/master/src/extract.rs)
also tracks cipher ownership by block owner; no implementation code was copied.
Earlier encrypted-solid exclusions below describe superseded checkpoints.

Bulk, selected-entry and forward extraction share a complete solid-layout preflight:
the overflow-checked sum of non-directory file sizes must equal decoded block sizes
before targets or spool directories change. Reuse the validated physical block order.
Directory-only extraction still checks any attached zero-output blocks instead of
skipping their decoding/nonzero CRC. Seven new synthetic regression methods are
uncompiled/unrun. The preflight alone does not add whole-archive rollback;
later payload corruption may still follow committed, independently verified blocks.

Standard BZip2 blocks consume concatenated streams up to their declared packed
boundary, preserving output-size, block CRC and file authentication checks.
Malformed later streams and trailing junk fail. This compatibility extension
is covered by new synthetic regression sources only; real producer execution,
including the existing LEA-BZip2 fixture, remains pending.

AZO framing now preflights inner packed ranges and cumulative unpacked size
against the enclosing block declaration before allocating decoded storage. One
exact-sized output array replaces accumulator growth/final copying, and stored
inner blocks copy directly into it. X86 offsets still restart per inner block.
Raw/framed prefix fallback remains. The 1.0.20 size-policy patch removes the fixed
512 MiB per-block quota, retaining array representability and actual heap constraints;
this is not a streaming rewrite. Long decode/filter loops check cancellation,
which must not be reclassified as an unsupported feature. Block CRC and full
encrypted-payload authentication are unchanged. New regression sources are unrun;
see [decode boundary notes](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

The parser continues over length-checked COMMENT/DUMMY records between files and
can resume the previous entry's blocks after such records, including solid payload
following the last file header. Unknown inter-file structures and partial signatures
fail instead of publishing a partial index. Header + prefix END + archive END is a
valid empty archive. Existing nonempty missing-final-END and missing-block-END
compatibility paths are retained; a missing file-header terminator is rejected.

Directory classification combines a trailing slash, Windows attribute `0x80`, and
POSIX type `0040000`; nonzero directory file sizes are rejected. Windows link flag
`0x08` and POSIX special types are refused, not materialized as ordinary files.
The name locale flag is `0x08`; the older reader's `0x20` interpretation is retained
as compatibility behavior, not described as a standard flag. Duplicate/missing
filenames, truncated locale data and duplicate encryption fields fail. Filenames
above the existing 65,535-byte metadata range now fail explicitly instead of being
silently truncated. Encrypted (`0x04`) names remain unsupported.

Parent-ID (`0x10`) names now resolve through explicit directory entries after all
names are decoded. The optional locale u16 precedes the optional parent ID u32;
neither contributes bytes to charset detection. File IDs preserve their full uint32
identity, including zero. Forward/nested references are resolved iteratively, once
per entry, without changing physical entry order or solid payload offsets.
Missing, ambiguous, cyclic or non-directory parent IDs fail before index publication
or output. Unsafe child/base paths cannot be made safe by prefixing a directory.
Expanded paths are bounded to 65,535 Java characters for metadata handling, separate
from the existing 65,535-byte raw field bound; neither is a file-payload size cap.
Archives without parent references retain their previous duplicate-ID behavior;
in referenced archives only ambiguous IDs actually used as parents are rejected.

Bulk destinations use the common read-only preflight before writing. Unsafe names
are hidden from listing and excluded from selected identity, but retained internally
so bulk extraction rejects them. Safe selected solid reads still consume/discard
preceding unsafe members' bytes to retain offsets. Duplicate-file listing metadata
matches the first occurrence used by selected extraction; bulk duplicate overwrite
semantics are unchanged. This is not a whole-archive transaction.

Format facts were cross-checked against the public
[EggDotNet filename definition](https://github.com/akolman/EggDotNet/blob/master/src/EggDotNet/Format/Egg/FilenameHeader.cs),
[attribute definition](https://github.com/akolman/EggDotNet/blob/master/src/EggDotNet/WindowsFileAttributes.cs)
and [CC0 EGG notes](https://github.com/alkegi/docs/blob/master/egg.md).
Those references are not runtime verification or a claim that all producers use
identical metadata. Parent-ID field order was also checked against pages 9 and 13 of
ESTsoft's [EGG specification](https://aldn.altools.co.kr/setup/EGG_Specification.zip).
No implementation code was copied or dependency added; the vendor specification is
not bundled. `EggParentPathTest` adds 19 synthetic regression methods for referenced
paths, extraction/solid order and rejection boundaries. These and the earlier new
regression sources are uncompiled/unrun. The later LEA addition below retains
the encrypted-solid/encrypted-filename exclusions; producer execution is pending.

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
retained by this index. Directory classification originally followed decoded filenames;
the 1.0.20 metadata handling above extends it.
No new codec support or runtime validation is claimed; see
`DEV_CHANGES_1_0_18.md` for implementation details.

Store/Deflate/BZip2/LZMA no longer inherit the old 512 MiB per-file/block
ceiling. At that checkpoint AZO retained a 512 MiB per-block array-memory guard
(removed by the 1.0.20 size-policy patch described above). Non-solid file
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
FILENAME payload: optional locale code page u16 (locale flag `0x08`, with the
reader's legacy `0x20` compatibility alias), optional parent path ID u32 (`0x10`),
then the name bytes (UTF-8 or the locale code page). This reader resolves parent
references only to entries classified as directories, not ordinary files.

A FILE entry is:
```
FILE sig, file id u32, uncompressed size u64
extra fields (FILENAME, WINDOWS_FILEINFO, ENCRYPT, ...)
END                                              <- per-file extras terminator
BLOCK ... BLOCK                                  <- the file's data blocks
```
Blocks run until the next FILE or the archive END; comments/padding between them
are skipped by their declared length. There is no separate per-file trailing END
after the blocks.

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
  12 check bytes, and require `plain[11] == crc >> 24` (a mismatch fails
  before output; this weak check alone cannot establish a correct password).
  The *same* keystream then continues into the
  file's block data in block order - one decryptor per file, never reset at a
  block boundary (resetting decodes block 2+ as garbage; verified against a
  real two-block file). Data is compressed first, then encrypted, so
  extraction is decrypt -> decompress -> CRC. The stored payload is
  ciphertext end to end, including the 9-byte LZMA preamble and AZO framing.
  Verified end to end (CRC) against a real ALZip ZipCrypto file.
  In 1.0.20, legacy low-byte-per-char password interpretation remains first.
  After header mismatch, strict UTF-8/MS949 alternatives are tried for non-ASCII
  passwords only when every block has a nonzero CRC. Size/CRC checking still gates
  output; a header collision is not retried with another encoding after decoding.
  These new encoding regressions are unrun and need real-producer validation.
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
  is checked after the last block ("EGG data authentication failed").
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
- **Methods 5/6 - LEA-128/LEA-256** (implemented, uncompiled/unrun): non-solid
  entries use `EggAuthenticatedCrypto` with the MIT-attributed `EggLeaCipher`
  adaptation from alkegi/unegg. Salt/verifier/key derivation/HMAC layout matches
  the AES route, but the 16-byte counter starts at zero and increments big-endian.
  Cipher state and HMAC continue across stored blocks. A missing footer is an error;
  password/size/available-CRC/HMAC failures retain the existing output guard.
  Three CC0 producer fixtures and block-cipher vectors are added as test sources,
  not reported as passed. Solid cipher ownership now uses the staged route above;
  encrypted filenames remain excluded.

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
typo.) These historical results do not validate the newer encrypted-solid route.

## Not supported (deliberate)

The shared ALZ/EGG `SplitVolumeInput` has physical/logical
range checks, cancellation/failure retirement and independent bounded-view
lifecycle. Binary-search volume lookup and reusable one-byte storage reduce
specific overheads but have not been benchmarked. Raw skipping does not verify
payload bytes; EGG's length, available-CRC and AES/LEA checks remain separate. These
changes do not expand the codec/encryption scope below.

- **Encrypted filenames/global encryption metadata**: no verified filename cipher
  and framing implementation is provided; encrypted names remain rejected.
- **New continuous codec state across packed blocks**: the existing block decoder
  grammar is retained. Do not infer broader compressed-stream compatibility from
  the new encryption ownership/session layer.
