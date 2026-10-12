# LHA LH4 and Amiga byte-split integration — Readwide 1.0.20

Source review: 2026-10-10; release date: 2026-10-11. The maintainer confirmed a
successful build on 2026-10-11. No tests, benchmarks or native/device execution
were run for this source review.

## Implemented method scope

The vendored libarchive 3.8.9 reader already identifies `-lh4-` headers, but its
decoder switch admitted only `-lh5-`, `-lh6-` and `-lh7-`. The retained local patch
now selects a 12-bit / 4 KiB history for `-lh4-` in that same decoder. No additional
engine, payload spool or decoder retry is introduced.

The existing algorithm derives the position alphabet size as `windowBits + 1`
and uses a four-bit position-table count for LH4/LH5. Thus LH4 position symbols
are 0–12, with distance-minus-one at most 4095. Both singleton and dynamic tables
reject larger symbols/counts. Initial references address the last 4 KiB of the
128 KiB implementation output ring, initialized to spaces on every entry. The
larger ring is a batching optimization, not permission to reference more history.
LH5/6/7 retain their previous 8/32/64 KiB method windows and table widths.

Ordinary listing/selected/bulk/forward routes still use `LibarchiveNativeBridge`
and their existing header checksum, supplied payload CRC, output guards, free-space
reserve, cancellation and source controls. Listing alone does not decode payloads.
No fixed extracted-size cap is added and no TXT paging code is changed.

Current compressed LHA method scope is LH4/LH5/LH6/LH7. Stored LH0/LZ4 and directory
LHD handling remain. LH1/LH2/LH3, LArc LZS/LZ5 and PMarc codecs are not newly
implemented; neither are password handling or archive creation. The Amiga byte-split
profile below is connected separately; it does not add more compression methods.

## Source evidence

- [7-Zip LZH method mapping](https://github.com/ip7z/7zip/blob/9128b80e3a471108678e2b3ec3c985861c8d7b0f/CPP/7zip/Archive/LzhHandler.cpp)
  groups LH4–LH7 and assigns LH4 a 12-bit dictionary.
- [7-Zip LZH decoder](https://github.com/ip7z/7zip/blob/9128b80e3a471108678e2b3ec3c985861c8d7b0f/CPP/7zip/Compress/LzhDecoder.cpp)
  uses the shared Huffman grammar, four-bit position tables for smaller windows
  and checks distances against the selected dictionary.
- [Lhasa's ISC LH4/LH5 decoder](https://github.com/fragglet/lhasa/blob/master/lib/lh5_decoder.c)
  also shares the new-method engine. It intentionally allocates a larger history,
  so it is corroboration for grammar reuse, not Readwide's window-bound authority.
- [Pinned upstream libarchive reader](https://github.com/libarchive/libarchive/blob/27cbc7827172698143e440801fc0ba39ccb4f1f5/libarchive/archive_read_support_format_lha.c)
  is the unchanged grammar/table implementation reused by the four-line admission.

These sources were read for semantics; no reference implementation was copied.
The local change remains recorded in the wrapper's retained `libarchive.patch`.

## Documented Amiga byte-split profile

The original [LhA 1.38 producer manual, sections 2.8–2.9](https://github.com/theflyingape/rpgbbs/blob/741923f0bd8e1accb9a8a12ddbda482edd3c6a19/LhA.man)
explicitly describes dividing one archive into byte pieces, starting with `.lha`
or `.lzh` and continuing as `.l01`, `.l02`, etc. It documents header levels 0/1/2
and refers to portable split/join utility sources. Those utilities were not
retrieved; the implementation relies on the manual's explicit layout, not merely
the newer producer's `-V` option. No producer source was copied or bundled.

`LhaVolumeResolver` scans the directory once, orders canonical ordinals and rejects
missing, ambiguous, unreadable or empty parts. Selecting a continuation resolves
the same first part for queue grouping and includes all parts in cache identity.
Ordinary `.lha`/`.lzh` files without continuations retain their single-file route.
Names beyond `.l99` follow the same decimal sequence without a new volume-count
cap; the historical manual's producer itself stopped at 100 volumes.

`ArchiveSupport` reuses `NumericSplitArchiveMerger`, preserving cancellation,
one-open-part I/O, source stamps, checked arithmetic and the 64 MiB free-space
reserve. A prepared forward-viewer session owns one merged spool rather than
merging again for each page. Listing and separate extraction operations still
prepare separately; no measured speedup is claimed.

Before native opening, `LhaSplitFraming` walks header sizes and seeks over payloads.
Level-1 skip sizes include extended headers; level-2/3 header sizes include theirs.
The 64-bit size extension can replace the initial packed size. A final zero byte
must occur exactly at the combined end, so an early end marker, missing tail or
independent archives appended as volumes are rejected. Native parsing retains
header checksums/CRC, entry CRC, method/path validation and guarded output.
This metadata pass is not payload authentication; same-size/time replacement is
not made impossible by source stamps, and legacy CRC-free entries lack payload CRC.

Level 3 is admitted by reusing the same walk with four-byte extension lengths.
Its two-byte field-width marker must be 4, its full header length is at offset 24,
and extensions start at offset 28. The extension terminator must end exactly at
the declared header boundary, matching where the native reader begins payload.
Both the pinned native reader above and [Lhasa's level-3 parser](https://github.com/fragglet/lhasa/blob/75ed83559f23e9538e0045c62f53f77ab03d03d6/lib/lha_file_header.c)
establish this grammar independently of the older Amiga manual. This does not claim
that LhA 1.38 produced level-3 headers. No reference implementation is copied.
The native reader's existing 65,536-byte level-3 header-metadata bound remains;
it is not an extracted-payload cap, and this change does not expand that bound.

This profile excludes SFX wrappers and separate `0x39` multi-disc semantics. The
unused Lhasa multi-disc constant still supplies no decoder/framing specification
for that separate feature. These exclusions are explicit, not a claim that all
LHA volume variants are implemented. Single-file level-3 behavior and other archive
routes remain unchanged. No payload cap, dependency, native-source, version or TXT
paging change is made here.

Seventeen host regression methods cover discovery, gaps, ambiguity, canonical
names, cache/queue identity, cancellation, header levels, size overrides,
truncation and premature/trailing markers. Three additional Android methods cover
public listing/selected/bulk/forward routing with splits inside a header and before
the end marker, a level-3 four-byte extension split, common-header CRC rejection,
and preservation of existing output on rejected chains. All are
uncompiled/unrun; producer/native/device validation remains pending.

## Historical volume references

The initial inspection found `-V`/`-Qv` options but no continuation framing in
those descriptions, Unix LHA notes or libxad's reader. Later work found the original
LhA 1.38 manual's explicit byte-split layout and implemented that scoped route.
Separate multi-disc semantics still lack an implementation; arbitrary independent
numbered archives are not accepted as one byte-split stream.

- [Amiga producer options](https://mviess.de/amiga/lha/en/)
- [LHa for Unix header description](https://github.com/jca02266/lha/blob/master/header.doc.md)
- [Reviewed libxad reader](https://github.com/ashang/libxad/blob/dbe67a865207e49cd917ad9170a11b9eaa83998a/libxad/portable/clients/LhA.c)

The earlier general framing uncertainty is superseded only for the documented
Amiga byte-split profile, not every LHA volume variant. Producer/native execution
remains pending.

## Regression source, not execution evidence

Five `LhaNativeDecoderInstrumentedTest` methods construct level-0 archives and
synthetic compressed blocks. They cover:

- LH4 plus unchanged stored/LH5/LH6/LH7 listing, selected, bulk and forward routes;
- dynamic literal/position tables and the maximum initial LH4 reference;
- output beyond the 128 KiB batching ring, partial consumer reads, per-entry history
  reset and switching from LH4 to LH5;
- singleton/dynamic out-of-window rejection with the same larger distance valid
  for LH5;
- malformed header checksums, payload CRC, truncation and invalid literal symbols,
  preserving an existing selected destination on failure.

All test methods are uncompiled/unrun. Real producer streams, remaining header
levels and device behavior still need execution validation. Synthetic blocks and
source reasoning do not establish universal LHA compatibility.
