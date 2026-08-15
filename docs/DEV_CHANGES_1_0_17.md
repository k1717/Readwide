# Readwide 1.0.17 development changes

This note records the implementation delta from 1.0.16.

## Browser display policy

- `PrefsManager`
  - adds `FILE_DISPLAY_LIST` and `FILE_DISPLAY_TILES`
  - persists `file_display_mode`
  - defaults unknown or absent values to the existing list mode
- `MainHomeDialogController`
  - adds the current display state to the fixed upper-right overflow beside the current location title
  - opens a localized list/tile radio dialog
  - does not change row, selection, or long-hold menus
- `MainActivityStartupController`
  - applies the policy to browser and Recent adapters
  - selects `LinearLayoutManager` or `GridLayoutManager`
  - preserves the first visible adapter item and pixel offset during a switch
  - uses a fixed two-column tile grid for wider covers and filenames
- `FileAdapter`
  - exposes stable list and tile view types
  - keeps one binding and interaction path for both layouts
  - includes layout kind in thumbnail memory/request keys
- `item_file_tile.xml`
  - adds a 96dp cover area, two-line filename, metadata/path lines, progress badge, and selection marker using the existing view IDs

## TXT and Markdown annotations

- `DocumentAnnotation` defines a source-positioned note/highlight with selected text and recovery anchors.
- `DocumentAnnotationManager` owns crash-safe app-private `annotations.json` storage. It never writes the source document, rejects exact duplicate highlight ranges, and removes historical duplicates during load/import.
- TXT selection reports a start/end range; large-TXT local offsets are converted to absolute offsets and persistent highlights are projected back into the active partition.
- Markdown selection actions resolve the nearest renderer source block, store the source offset plus quote, and reapply highlight markup after WebView reloads.
- TXT and Markdown reader toolbars now provide a direct annotation-list shortcut in addition to the More-menu entry.
- `DocumentAnnotationDialogController` supplies an adaptive themed list, navigation, note editor, and delete path. Long lists are bounded and scrollable; edit/delete rebuild the visible list immediately.
- File/folder moves and JSON backup export/import include annotation data.

## Large-screen EPUB layout

- `SpreadMath.shouldUseEpubSpread()` treats `sw600dp` as the large-screen boundary for ordinary EPUB text while retaining image-page landscape spreads on compact devices.
- The document viewer reuses its existing two-WebView spread, page-range status, navigation, RTL placement, and resource routing for ordinary EPUB spine pages on large landscape screens.
- PDF spread code and compact-phone/portrait EPUB behavior are unchanged.

## Resource and release changes

- Added localized display-mode strings and annotation UI resources.
- Refined annotation status/delete wording across affected locales, standardized Korean sidebar/selection terms, and replaced several mixed English Indonesian UI labels.
- Replaced the remaining PIN, reader-menu, bookmark, and font-picker literals with Android resources across English and all 21 non-default locales. Font-family choices now share the existing localized EPUB labels instead of selecting only English or Korean in Java; user/device font names remain unchanged.
- Moved non-language UI glyphs and composition-only layouts into non-translatable resources, leaving Android lint with no hardcoded-text, concatenated-localized-text, missing-translation, or extra-translation findings.
- Changed stopped image/archive memory handling from activity termination to bitmap release plus same-index reload. The ten-minute stale-viewer expiry remains unchanged.
- Added a scoped ZIPX supplement for WinZip-AES Deflate64/BZip2/LZMA/XZ entries, with password-verifier, HMAC authentication, decoded-size/AE-1 CRC checks, and LZMA EOS/memory handling. AES PPMd and Zstandard route to source-built libarchive 3.8.9. AES JPEG/WavPack route through the separate source-built `zipxCodecsAndroid` module (XADMaster WinZip JPEG LGPL-2.1-or-later and WavPack 5.9.0 BSD-3-Clause), with bounded streaming and guarded output. Updated every localized archive-boundary description to state the same matrix.
- Replaced the Maven `libarchive-android:1.1.6` runtime AAR (libarchive 3.8.1) with the pinned, vendored upstream wrapper/native sources. `:libarchiveAndroid` builds official libarchive 3.8.9 and its permissive codec stack for ARM32/ARM64 with NDK 29; no prebuilt AAR or `.so` is stored in source. The update adds upstream ZIPX streaming and RAR/RAR5/7z/CAB/LHA parser hardening without claiming full RAR compatibility.
- Exposed the backend's already-compiled CAB and LHA/LZH readers for read-only listing, archive-image browsing, and extraction. Creation, password handling, and broad multi-volume support are deliberately excluded.
- Extended the first-party RAR5-container decoder from algorithm v0 (RAR 5/6) to bounded algorithm v1 (RAR 7): 80 distance symbols, dynamic table size, fractional/non-power-of-two dictionary parsing, 64-bit distance reads, and the v0-solid compatibility marker. The logical declaration can reach 1 TiB while physical retained history stays capped at 64 MiB.
- Exempted libarchive's checked-in `external/libarchive/build/cmake/` source modules from generated-build ignore/package filters and added a portable source-ZIP creator that preserves POSIX modes and validates the complete filtered file list.
- Updated version metadata to `1.0.17` / `10017`.
- Updated the changelog, patch notes, code map, release notes, Fastlane changelogs, license report, SBOM, FOSS status, and release/submission checklists.

## Validation boundary

- Resource XML, duplicate string names, translation coverage, format-placeholder parity, JSON syntax, archive contents, and source-only packaging are checked statically.
- No Android permission changed. The existing native backend changed from a Maven binary to its pinned source build.
- Generated AES-256+BZip2/XZ/LZMA ZIPX fixtures are verified byte-for-byte for whole and single extraction; password, authentication-damage, normal AES-Deflate rename, PPMd/Zstandard fallback routing, and JPEG/WavPack native routing are regression-tested. The Android native test decodes the official WavPack PCM regression stream to a structurally valid RIFF/WAVE file.
- The Junrar-free RAR3 classic-LZ VM record reader now preserves standard filter program slots across records/solid state, handles reset/selection, reuses omitted block lengths, consumes bounded global data, and applies E8/E8E9/Itanium with the correct file offset. Custom VM bytecode remains explicitly unsupported.
- RAR7 unit coverage constructs a version-1 Huffman block from the public grammar, exercises the 80-entry distance table and extended slot 64, verifies initial-window zero filling without a 1 TiB allocation, checks fractional dictionary parsing, and keeps all existing real RAR5/AES fixtures passing. A genuine large-dictionary RAR7 archive remains an external/device fixture requirement.
- Device execution remains a release-maintainer check.
