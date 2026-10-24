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

Before reading each physical prefix, the resolver captures its canonical file,
length and modification time. It checks that state after parsing and passes it
with the payload window into `SplitVolumeInput`, which validates it on adoption
instead of recording a fresh baseline. This includes the first/single volume and
stripped continuation links; the legacy prefix without END remains accepted.
Handoff regressions resolve once, mutate physical framing and adopt those same
segments. They are uncompiled/unrun and do not establish additional codec coverage.

Extraction rechecks captured volume existence, length and modification time after
parsing and before accepting output. Solid blocks check the original source even
when reading authenticated packed spools; forward entry EOF/drain checks also cover
entries sharing a verified decoded block. Changed input retires the session and
its spools, while existing guards restore current output and retain previously
verified files. Ordinary file completion checks cancellation after output closure.
These are boundary checks, not per-byte I/O or a locked snapshot: same-metadata
edits/replacements and final-check races can evade detection. Added source-change
and rollback regression methods are uncompiled/unrun; cipher/codec math is unchanged.

Encrypted solid sessions also check the authenticated packed spool itself before
and after decoding each block. Forward viewing captures verified decoded-spool
length/mtime after output closure and checks it before chunk reuse, drain, entry
completion and block replacement. This detects same-length edits with observable
timestamp changes that original-source checks cannot see. Failed sessions clean
both spools and roll back pending output while retaining earlier verified files.
No extra handles, content digests or decoder replay are added. Same-metadata edits
and final-check races remain possible. Empty extraction now checks token-only
cancellation after total reporting even when there are no entry-loop iterations.
The added regression sources are uncompiled/unrun.

In 1.0.21, shared `SplitVolumeInput` also retires on unchecked volume-access/read
failures after a partial prefix. Existing bounded views cannot resume after access
returns. Framing, cipher ownership, authentication and codec calculations are
unchanged; the new injected-failure regression source is uncompiled/unrun.

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
The encryption details below distinguish this solid block-owner route from ordinary files.

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

The parser continues over length-checked COMMENT/DUMMY/SKIP records between files and
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
regression sources are uncompiled/unrun. The later LEA addition below uses the
scoped encrypted-solid path above; encrypted filenames remain excluded and
producer execution is pending.

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
| GLOBAL_ENCRYPT (opaque, not decrypted) | `0x08D144A8` |
| WINDOWS_FILEINFO | `0x2C86950B` |
| POSIX_FILEINFO | `0x1EE922E5` |
| COMMENT | `0x04C63672` |
| SPLIT | `0x24F5A262` |
| SOLID | `0x24E5A060` |
| SKIP | `0xFFFF0000` |
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

An extra field (at archive level, inside FILE and in the specified BLOCK extension area) is:
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
optional extra fields                            <- ExtraField3 in the specification
END                                              <- block header terminator
data[compressed]
```
The reader supports COMMENT/DUMMY/SKIP chains in this extension area only when
the existing missing-END interpretation cannot continue structurally. A legacy
packed endpoint at exact EOF or FILE/BLOCK/END/COMMENT/DUMMY/SKIP always retains
precedence, even if deeper parsing would fail. Only an impossible legacy successor
allows bounded extra lengths, mandatory END, checked packed extent and a recognized
successor to select another payload offset. There is no scan for signatures inside
payload, retry after I/O/cancellation failure, or change to cipher/MAC input.

This deliberately leaves ambiguous endpoints and unknown block extras outside the
extension. Treating every signature-like prefix as metadata could reinterpret valid
legacy stored or encrypted payload. Public specification pages 7–8 and 14 provide
the structure/SKIP contract; retained Bandizip 6.29 REA and the independent
[EggDotNet block reader](https://github.com/akolman/EggDotNet/blob/master/src/EggDotNet/Format/Egg/BlockHeader.cs)
do not supply a discriminator for all layouts. Eight added synthetic regression
methods cover framing priority, payload signatures, malformed extras, CRC rollback
and ZipCrypto/AES split/solid ownership. They are uncompiled/unrun, not evidence of
additional producer interoperability. Existing SKIP-between-records behavior remains.

For an empty packed block followed by extra records, the legacy packed endpoint
is the extra's start and therefore remains a possible outer record. Its following
END can be treated as archive termination, leaving later entries undiscovered.
This pre-existing ambiguity is not resolved by the scoped extension; changing that
preference without a distinguishing contract could reinterpret legacy input.

The current reader verifies a block's CRC32 when the field is nonzero. A zero
CRC field is treated as absent by the existing decoder, not compared to a
computed zero CRC. Declared decoded lengths are still checked; supported AES
entries also require footer authentication. Do not describe zero-CRC plain
blocks as checksum-verified.

Compression methods: 0 Store, 1 Deflate (raw), 2 BZip2, 3 AZO, 4 LZMA.

### AZO operation cancellation

Extraction and password probes pass the same optional operation token through
packed reads (64 KiB), framed validation/copy, raw output and x86 filter checkpoints.
Cancellation is terminal before fallback or password-candidate rejection. The
existing tokenless entry point and thread-interruption checks remain; probes do
not report extracted bytes. Individual allocations and array copies cannot be
preempted, and AZO still materializes each block in arrays.

Fresh Bandizip 6.29 static dispatcher/inner-block inspection corroborates the
existing version-1 framing, stored-block branch, independent compressed-block
state and block-local filter offset. It is not runtime interoperability evidence.
No proprietary code was copied and no compression/filter arithmetic changed.
Seven added cancellation/output/cipher regression methods are uncompiled/unrun.

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

To assemble the logical archive, the **first volume is kept
whole** (its own header prefix, including its SPLIT field, is part of the
logical stream) and every later volume contributes only the bytes **after its
own header prefix**. The vendor specification permits cuts in compressed block
data but requires headers and extra fields to stay intact. The segmented reader
also tolerates synthetic header cuts; this is not the documented producer layout.
`SplitVolumeInput` presents this concatenation as one seekable
stream. The resolver catalogs sibling
`volN` names by numeric ordinal, independent of case and leading zeroes, and
rejects ambiguous aliases or missing numbers. Both links must match: each
volume's `prev` equals the previous header id, and its own header id equals the
previous prefix's `next`. Public archive APIs resolve a continuation to the
same uniquely identified first part; direct first-party reader calls still
expect the first part. Missing members and link mismatches fail before payload
decoding. These changes have source regressions only, not runtime validation.

The specified SKIP marker (`0xFFFF0000`) now uses the existing bounded extra-field
skip between entries or before another block, like COMMENT and DUMMY. It does not
enter filename decoding, cipher input or authentication; later blocks retain
their existing owner and physical order. The vendor's split description on page
14 defines the marker, also recognized in saved Bandizip 6.29 static evidence.
Unknown inter-file signatures remain errors. Synthetic sources cover both length
widths, skipped bytes resembling encrypted-name records, solid/split boundaries,
ZipCrypto/AES continuity and malformed ranges; they are uncompiled/unrun.

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
  Legacy low-byte-per-char interpretation remains first. Strict UTF-8/MS949
  candidates are admitted only when every owned block has a nonzero CRC. When
  distinct candidates share the header check, read-only decoded-length/CRC probes
  across all owned blocks select before output. A single candidate avoids probing;
  physical I/O, cancellation and resource failures cannot become password retries.
  These encoding regressions are unrun and need real-producer validation.
- **Methods 1/2 - AES-128/AES-256** (supported): the payload after the method
  byte is `salt (8 bytes for AES-128, 16 for AES-256) + 2-byte password
  verifier + 10-byte footer`. This is the WinZip AES construction:
  PBKDF2-HMAC-SHA1 with **1000 iterations** over the raw password bytes and
  the salt derives `AES key (16/32) + HMAC-SHA1 key (16/32) + verifier (2)`;
  the stored block data is **AES-CTR ciphertext with a 16-byte little-endian
  counter starting at 1**, and the footer is the first 10 bytes of
  HMAC-SHA1 over the file's ciphertext (encrypt-then-MAC). One context spans
  the file: keystream and MAC run continuously across the file's blocks.
  A verifier mismatch fails before output; a matching 2-byte verifier alone is
  not authentication. Ordinary extraction checks the footer after the last owned
  block and before target commit; solid extraction verifies the complete owner's
  staged packed bytes before decoding. Password bytes use UTF-8 first, with
  Windows-949 as the non-ASCII alternative. Distinct candidates that both pass
  the short verifier are selected by authenticating all ciphertext blocks owned
  by that header, excluding intervening metadata, before any decoding/output.
  Exactly one full MAC must match. MAC finalization resets authentication and
  the probe leaves CTR untouched, so extraction starts fresh and verifies again.
  A single verifier match avoids the payload probe; non-ASCII passwords require
  the additional candidate derivation. Source/close/cancellation errors are terminal.
  Added ambiguity regressions inject matched contexts rather than claim a real
  colliding-password archive and remain uncompiled/unrun. As with ZipCrypto, the stored payload is
  ciphertext end to end, so extraction is decrypt -> decompress -> CRC.
  *Provenance*: implemented from the public EGG Specification 1.0 byte
  layout plus the published WinZip AES scheme with JCE primitives only
  (`Cipher AES/ECB`, `Mac HmacSHA1`, first-party PBKDF2); no vendor code was
  ported. The scheme was confirmed as a black-box test: first-party-built
  AES-128/256 store and deflate archives decrypt byte-identically through
  ESTsoft's own `unegg` 0.5 decoder with the password. That decoder also
  finalizes the footer MAC after *every* block. Those historical single-block
  results do not establish every ALZip producer's block layout or validate
  Readwide's later multi-block/solid owner-authentication path.
- **Methods 5/6 - LEA-128/LEA-256** (implemented, uncompiled/unrun): entries
  use `EggAuthenticatedCrypto` with the MIT-attributed `EggLeaCipher`
  adaptation from alkegi/unegg. Salt/verifier/key derivation/HMAC layout matches
  the AES route, but the 16-byte counter starts at zero and increments big-endian.
  Cipher state and HMAC continue across stored blocks. A missing footer is an error;
  password/size/available-CRC/HMAC failures retain the existing output guard.
  Three CC0 producer fixtures and block-cipher vectors are added as test sources,
  not reported as passed. Solid cipher ownership now uses the staged route above;
  encrypted filenames remain excluded.

## Solid archives (supported)

Terminal zero-output blocks remain part of full extraction and forward archive
completion. Two additional uncompiled/unrun regression methods place an empty
Store block after ordinary, ZipCrypto and AES solid data, with bounded outer SKIP
metadata. A bad final block CRC must reject completion and retire forward spools;
earlier files from verified blocks remain. Valid completion removes both packed
and decoded spools. Ciphertext/MAC bytes, selective early-range behavior and all
production EGG code are unchanged by this recheck. These are synthetic coverage
sources, not producer/runtime validation or tests of internal key erasure.

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
fails with "Solid EGG stream ended before entry data". The current target remains
guarded; files already committed earlier in a bulk operation are not rolled back
as one archive-wide transaction.

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

## Encrypted filename boundary

The vendor specification assigns FILENAME flag `0x04` and archive-prefix
GLOBAL_ENCRYPT signature `0x08D144A8`, but does not define their filename cipher,
key derivation, framing or authentication. The per-file ENCRYPT payload above is
not a filename-decryption contract. The inspected Bandizip 6.29 filename path did
not reveal such a decryptor; this does not cover every product version or prove
the feature is unused.

[EggDotNet](https://github.com/akolman/EggDotNet/blob/master/src/EggDotNet/Format/Egg/FilenameHeader.cs)
explicitly rejects encrypted names. The independent
[unegg parser](https://github.com/alkegi/unegg/blob/master/src/archive.rs) notes no
observed sample and decodes flagged bytes without decryption; that is not evidence
of encrypted-name support.

Readwide rejects flagged filenames with or without a password, before index
publication or output. Opaque prefix extras, including GLOBAL_ENCRYPT, retain
bounded skipping under the vendor's forward-compatibility rule on page 7. This
does not implement global decryption. New source cases protect late/split names,
the ALZ-family bridge, untouched destinations/spools and failed-index nonpublication.
The flag-only fixtures do not invent ciphertext or claim a producer encryption scheme.

## Not supported (deliberate)

The shared ALZ/EGG `SplitVolumeInput` has physical/logical
range checks, cancellation/failure retirement and independent bounded-view
lifecycle. Binary-search volume lookup and reusable one-byte storage reduce
specific overheads but have not been benchmarked. Raw skipping does not verify
payload bytes; EGG's length, available-CRC and AES/LEA checks remain separate. These
changes do not expand the codec/encryption scope below.

- **Encrypted filenames/global decryption**: no verified filename cipher and
  framing implementation is provided; flagged names are rejected. Opaque prefix
  metadata is range-checked and skipped, not decrypted or automatically rejected.
- **New continuous codec state across packed blocks**: the existing block decoder
  grammar is retained. Do not infer broader compressed-stream compatibility from
  the new encryption ownership/session layer.
