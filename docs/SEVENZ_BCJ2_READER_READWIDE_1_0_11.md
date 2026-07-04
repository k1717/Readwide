# 7z BCJ2 first-party reader notes

Reference notes for `SevenZBcj2ArchiveReader`, the first-party 7z extraction
path for folders that use the **BCJ2** branch filter (coder id
`03 03 01 1B`). Apache Commons Compress cannot decode BCJ2 - it rejects the
folder with "Multi input/output stream coders are not yet supported" - and the
bundled native libarchive cannot decrypt 7z at all, so **AES-encrypted BCJ2
archives had no working path** before this reader, and plain BCJ2 relied on the
native fallback. This reader closes both gaps.

Provenance: the 7z container parser and every decoder here are first-party Java
written from the documented 7z format and published algorithms. No 7-Zip or
libarchive source code is copied. Test fixtures are self-made with p7zip from a
deterministic first-party payload and were confirmed to round-trip through the
reference `7z` tool before embedding.

## Why BCJ2 needs a custom path

BCJ2 is a four-input coder. It takes a main byte stream plus three side
streams - a "call" stream of 4-byte big-endian absolute targets for converted
`E8` instructions, a "jump" stream of the same for `E9` and two-byte `0F 8x`
conditional jumps, and a range-coded control stream of one bit per candidate
instruction - and rejoins them, turning each converted absolute address back
into the original PC-relative displacement. Commons Compress only models
single-input coder chains, so it cannot even build the decoder graph for a
BCJ2 folder, whether or not the folder is also AES-encrypted.

## What the reader parses

`SevenZBcj2ArchiveReader` parses the 7z header directly:

- the 32-byte start header (signature `37 7A BC AF 27 1C`, next-header offset
  and size);
- an **encoded (compressed and/or AES-encrypted) header** (`kEncodedHeader`,
  0x17): the reader decodes that folder first - decrypting with the password
  where the header itself is AES-wrapped - then parses the real header from the
  result;
- `MainStreamsInfo` (PackInfo pack positions/sizes, UnpackInfo folders with
  their coders, bind pairs, packed-stream indices and per-coder unpack sizes,
  SubStreamsInfo substream counts and sizes);
- `FilesInfo` (names as UTF-16LE, empty-stream/empty-file bit vectors), mapping
  each file to its folder and byte offset within the folder's decoded output.

## Coder graph resolution

Each folder is a small dependency graph: every coder input is either a packed
stream (read from disk) or another coder's output (via a bind pair), and the
one coder output not consumed by any bind pair is the folder's result. The
reader resolves that output recursively (with cycle detection), decoding each
coder with:

- **Copy** (`00`): pass-through.
- **LZMA** (`03 01 01`) and **LZMA2** (`21`): the app's bundled xz-java.
- **AES-256** (`06 F1 07 01`): `SevenZAesDecoder` - AES-256-CBC/NoPadding with
  the 7z key schedule (SHA-256 over `salt + UTF-16LE(password) + counter`
  repeated `2^power` times), JCE only.
- **BCJ2** (`03 03 01 1B`): `SevenZBcj2Decoder` - the range-coded branch join
  described above.
- **PPMd** (`03 04 01`): `SevenZPpmd7Decoder` - see
  `docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md`.

Unimplemented coders raise a clear unsupported error rather than guessing.
The reader is invoked only as a fallback, and only when
`archiveUsesSpecialCoder` confirms a BCJ2 or PPMd folder is present, so all
other archives (LZMA/LZMA2/BZip2/plain AES) are untouched and keep flowing
through Commons Compress or the native fallback exactly as before.

## Validation

Every layer was validated as a black box against the reference `7z` tool.
Self-made fixtures - a deterministic 2,868-byte payload of random bytes
interleaved with real `E8`/`E9`/`0F 8x` branch instructions and 4-byte
operands - were packed in three chains and decode byte-identically:

- **BCJ2 over stored inputs** (`-m0=BCJ2`): the four raw streams feed BCJ2
  directly.
- **BCJ2 over LZMA** (`-m0=BCJ2 -m1=LZMA`): the main stream is LZMA-compressed;
  the reader runs LZMA then the BCJ2 join.
- **AES-256 + LZMA + BCJ2 with an encrypted header** (`-mhe=on -ppw1717
  -m0=BCJ2 -m1=LZMA`): the header is AES-encrypted (decoded via the encoded-
  header path), and each BCJ2 input is AES+LZMA wrapped. Wrong or missing
  passwords fail cleanly with no partial output.

Larger real fixtures (a 14 KB stripped ELF, plain / LZMA / AES) were also
verified during development against `7z x`. The BCJ2 transform itself was first
prototyped in Python against the oracle to pin the algorithm, then ported and
re-verified in Java.

## Still handled elsewhere / not covered

- **PPMd** 7z folders are now also decoded first party by this reader via
  `SevenZPpmd7Decoder` (a port of the public-domain Ppmd7 reference),
  including AES-encrypted PPMd; see
  `docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md`. The native libarchive
  fallback remains behind it for unencrypted archives.
- This reader does not encode 7z, and implements only the coders above.
