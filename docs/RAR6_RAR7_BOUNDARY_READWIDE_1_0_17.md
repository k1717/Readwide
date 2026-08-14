# RAR 6 and RAR 7 decode boundary in Readwide 1.0.17

## Format distinction

RAR 6 is an application-generation label, not a new archive signature or
compression algorithm. Archives produced by WinRAR 6 use the RAR5 container
and compression algorithm version 0, so they already use Readwide's existing
`RarArchiveReader` and `Rar5CompressedDecoder` path.

RAR 7 retains the RAR5 container signature and adds compression algorithm
version 1. Its published differences relevant to this decoder are:

- 80 distance codes instead of 64;
- a larger combined Huffman table;
- dictionary exponents through the 1 TiB field limit;
- 1/32 fractional dictionary-size steps and non-power-of-two wrapping;
- a compatibility flag indicating version-0 compressed data stored with the
  version-1 dictionary field.

Primary format references:

- RARLAB RAR 5.0 container note: https://www.rarlab.com/technote.htm
- RARLAB RAR 7 decompression changes: https://www.rarlab.com/unrar7notes.htm

## Readwide implementation

`Rar5CompressedDecoder` accepts algorithm versions 0 and 1. Version 1 selects
the 80-entry distance table, grows the table-length stream accordingly, reads
extended distances into `long`, parses the extended dictionary field, and
uses modulo ring indexing instead of a power-of-two mask.

The declared dictionary is logical metadata, not an allocation request.
Readwide accepts a valid declaration up to 1 TiB but physically retains at
most 64 MiB. Matches into the initial unused logical dictionary yield zeroes,
as required for safe malformed/edge-stream handling. If decoded data actually
needs history older than the retained 64 MiB, extraction stops with a clean
unsupported error and does not commit a partial output file.

This policy is intentional for Android: allocating a multi-gigabyte or 1 TiB
dictionary from an untrusted archive header would be an out-of-memory denial
of service. It also means Readwide does not claim complete RAR 7 support.

## Verification and FOSS boundary

The JVM suite includes a first-party synthetic version-1 block constructed
from the public grammar. It exercises the 446-length combined Huffman table,
distance slot 64, a distance above 32 bits, the 1 TiB declaration, bounded
window allocation, and zero-filled initial history. Separate real RAR5 and AES
fixtures keep algorithm-version-0 compatibility locked.

A genuine RAR7 archive that both declares and uses history beyond 64 MiB is an
explicit unsupported boundary and remains an external/device test item.

No Junrar dependency and no RARLAB UnRAR-licensed source or binary is added.
The implementation is first-party Apache-2.0 Java based on published fields,
behavioral interoperability testing, and independently constructed fixtures.
