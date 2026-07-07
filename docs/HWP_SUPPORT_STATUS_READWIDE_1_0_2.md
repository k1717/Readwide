# HWP / HWPX support status for Readwide 1.0.2

Readwide's HWP/HWPX support is a scoped, text-first document-reader path.
It is not a Hancom-compatible layout renderer, document editor, or conversion
engine.

## Backend

The default build uses Apache-2.0 dogfoot libraries:

- `kr.dogfoot:hwplib:1.1.10` for `.hwp` HWP 5.x reading and text extraction.
- `kr.dogfoot:hwpxlib:1.0.9` for `.hwpx` HWPX reading and text extraction.

A tiny project-local `javax.xml.bind.DatatypeConverter` compatibility shim is
included because hwplib still exposes a helper that references JAXB's legacy
Base64 API on Java 17/Android builds. The shim implements only
`parseBase64Binary()` and does not add an external dependency.

## Extraction route

`.hwp` files:

1. Readwide first uses hwplib's text-only `HWPReader.forExtractText()` route.
   This avoids loading embedded BinData/images when the app only needs readable
   document text.
2. If the text-only route cannot handle a supported document variant, Readwide
   tries the full `HWPReader.fromFile()` + `TextExtractor.extract()` route.
3. Password/encrypted HWP failures are not retried as generic fallbacks; they are
   reported as unsupported encrypted/password-protected documents.

`.hwpx` files:

1. Readwide uses `HWPXReader.fromFile()` and hwpxlib's `TextExtractor` with
   document-reader marks for paragraphs, line breaks, tabs, and table cells.
2. A narrow XML fallback remains only for very small/synthetic HWPX packages
   where the library route cannot start. Each fallback section XML stream is
   capped before parsing.

## Supported, limited

- HWP 5.x text extraction.
- HWPX text extraction.
- Paragraph-oriented display through the existing `DocumentPageActivity` WebView
  document reader.
- Reader integration with recent files, bookmarks/content anchors, page controls,
  search, archive-preview document opening, and the Word file filter.

## Explicitly not claimed

- Hancom Office layout parity.
- Original page-count parity.
- HWP/HWPX editing or writing.
- PDF/image/HTML conversion.
- Password/encrypted HWP support.
- Legacy pre-v5 HWP support.
- Full embedded object, drawing, chart, or equation rendering. (Raster embedded images - PNG/JPEG/GIF/BMP/WebP - do render in the later rendered-layout path at their authored size, with a placeholder frame for unrenderable WMF/EMF/OLE pictures; vector/OLE objects, drawings, charts, and equations are still not rendered.)

## Safety guards

- Input HWP/HWPX files are capped before extraction.
- Extracted text is capped before it is handed to the document WebView paging
  path.
- HWP streaming extraction uses a bounded paragraph collector.
- The last-resort HWPX XML fallback caps each section XML stream before DOM
  parsing and still uses a hardened XML parser configuration.

## FOSS boundary

Readwide does not bundle Hancom proprietary SDKs, LibreOffice, server conversion
services, or non-FOSS HWP code. HWP/HWPX support is read-only, local, and based
on Apache-2.0 Java libraries plus small project-local adapter code.


## Word filter note

Historical note: in Readwide 1.0.2, the compact `Word` filter included legacy `.doc` files only for classification/filtering consistency, and legacy binary `.doc` rendering was not supported. Since Readwide 1.0.11, legacy `.doc` files have a basic read-only pure-Java rendering path. This file remains the HWP/HWPX backend scope and license note, not the current Word `.doc` support matrix.
