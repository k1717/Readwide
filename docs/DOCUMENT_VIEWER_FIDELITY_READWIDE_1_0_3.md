# Readwide 1.0.3 Document Viewer Fidelity Plan

Readwide 1.0.3 starts a separate document-viewer fidelity cycle for DOCX, HWPX, and HWP.
The goal is **L3 content-fidelity HTML preview**: keep document structure and visible content such as paragraph styling, lists, tables, and images where verified, while still using the existing WebView document pipeline.

This is not a page-compatible office renderer.

## Fidelity ladder

| Level | Meaning | Readwide status |
| --- | --- | --- |
| L1 | Text-only extraction | Current HWP/HWPX fallback path |
| L2 | Semantic HTML | Current DOCX path is closest to this level |
| L3 | Content-fidelity HTML | 1.0.3 target |
| L4 | Page-fidelity layout | Explicit non-goal for this cycle |

## Non-goals

- Exact MS Word or Hancom Office pagination.
- Exact font metrics or line wrapping.
- Editing, saving, or round-trip document writing.
- Complete floating-object placement.
- Arbitrary embedded OLE/control rendering.
- Legacy binary `.doc` layout preview.
- Password/encrypted document support.

Floating/anchored objects may be downgraded to inline or block content in the nearest paragraph. That is an intentional consequence of targeting L3 instead of L4.

## Architecture direction

Do not grow a separate renderer for every format. Convert each document family into a shared intermediate model first.

```text
DOCX / HWPX / HWP
        ↓
format-specific extractor
        ↓
RenderedDocument model
        ↓
FixedHtmlRenderer
        ↓
DocumentPageActivity / DocumentWebViewController
```

The existing text-first/semantic paths must remain as fallback. A failed L3 conversion must not pretend success.

## Planned shared model

Initial package target:

```text
app/src/main/java/com/textview/reader/document/render/
```

Initial classes:

```text
RenderedDocument
RenderedPage
RenderedBlock
RenderedParagraph
RenderedRun
RenderedTable
RenderedTableCell
RenderedImage
TextStyle
ParagraphStyle
RenderedDocumentLimits
FixedHtmlRenderer
```

The first model should be flow-based page HTML, not absolute-positioned office layout. Avoid names or behavior that imply L4 page parity.

## Shared scaffold status

The 1.0.3 cycle now has a shared `RenderedDocument` / `FixedHtmlRenderer` scaffold. This is deliberately a contract layer, not a real converter yet. It lets DOCX, HWPX, and HWP extractors target the same flow-based HTML renderer instead of growing separate ad-hoc HTML emitters.

Current scaffold scope:

- Page containers with page size and margins.
- Paragraph/run blocks with text style fields.
- Table/cell blocks with merge and basic visual fields.
- Image blocks with WebView-safe local source references.
- Unsupported placeholders for future controls that cannot be faithfully rendered.
- Plain-text and anchor fields for later search/bookmark integration.

Runtime document loading still uses the existing DOCX/HWP/HWPX paths until a fixture-backed extractor is connected.

## Current DOCX converter audit

Current file:

```text
app/src/main/java/com/textview/reader/DocumentWordUtils.java
```

Observed coverage in the 1.0.2 base:

| Feature | Current status | Notes |
| --- | --- | --- |
| `word/document.xml` parsing | Supported | Main source for paragraphs/tables. |
| Relationship map | Supported | `word/_rels/document.xml.rels`, external targets skipped. |
| Paragraph text | Supported | Walks text runs and inline children. |
| Paragraph alignment | Partial | center/right/justify from direct `w:jc`. |
| Basic run style | Partial | bold, italic, underline, color, font size. |
| Images | Partial | `a:blip` relationship to local media; image sizing is generic CSS. |
| Text boxes | Partial | `txbxContent` downgraded to boxed block. |
| Tables | Basic | `<table>/<tr>/<td>` emitted, nested paragraphs/tables supported. |
| Page breaks | Partial | explicit page break detection and paragraph-count splitting. |
| Default font detection | Partial | styles/doc/fontTable scan only. |
| Style inheritance | Partial in new rendered bridge | `styles.xml` document defaults, paragraph styles, character styles, and based-on chains now feed paragraph/run styling; table styles and complex style-linked numbering remain future work. |
| `numbering.xml` lists | Partial in new rendered bridge | Ordered/bullet list markers, level-aware counters, and indentation are preserved for direct numbering definitions; complex style-linked numbering remains future work. |
| `gridSpan` / `vMerge` | Partial in new rendered bridge | Horizontal grid spans and basic vertical merge row spans are preserved when directly present in table cells. |
| Table borders/fill/widths | Partial in new rendered bridge | Basic table width, grid column proportions, cell width hints, table/cell border colors, and cell shading are mapped to HTML when directly present. |
| Highlight/strike/superscript/subscript | Not supported | Run-style coverage is incomplete. |
| Headers/footers | Partial in new rendered bridge | Referenced DOCX headers/footers are surfaced as reading-order sections before/after the body. Exact per-page repetition, first/even page placement, and page-number field layout are not claimed. |
| Footnotes/endnotes | Partial in new rendered bridge | Referenced DOCX footnotes/endnotes are collected at the end of the rendered document with body reference links and note backlinks; complex note layout is not L4-preserved. |
| Floating/anchored drawings | Not supported as layout | Can only be downgraded if visible as inline/image content. |
| Legacy binary `.doc` | Unsupported | Classified under Word filter but not rendered. |

## 1.0.3 recommended order

1. Add version metadata and documentation for the fidelity cycle.
2. Add `RenderedDocument` and `FixedHtmlRenderer` scaffold with fake fixtures.
3. Route a small DOCX subset into `RenderedDocument`, with the old path as fallback.
4. Add DOCX lists from `numbering.xml`.
5. Add DOCX table fidelity: gridSpan, vMerge, border, shading, widths.
6. Complete DOCX style inheritance and remaining run/paragraph properties that are cheap and testable.
7. Add DOCX inline image sizing and floating-image downgrade behavior. **Completed for directly expressed `wp:extent` / `a:ext` sizing; further image-wrapping fidelity remains out of scope for L3 v0.**
8. Add footnote/endnote output at the end of the rendered document. **Completed for referenced DOCX footnotes/endnotes as end-of-document sections with links; exact Word note layout is not claimed.**
9. Add header/footer preservation as reading-order sections. **Completed for directly referenced DOCX header/footer parts; exact per-page repetition and Word field layout are not claimed.**
10. Move to HWPX XML structure mapping.
11. Move to HWP 5.x binary layout extraction only after DOCX and HWPX paths are stable.

## Validation policy

Each fidelity feature needs a fixture and structural assertion before public support wording is updated.

- DOCX fixtures can be generated locally as small OOXML ZIPs.
- HWPX fixtures can be generated as small ZIP/XML documents, but real Hancom-created fixtures should be used before claiming broad behavior.
- HWP binary fixtures must come from real Hancom-created files.
- Visual inspection remains device-side; automated tests should assert HTML structure, style attributes, image/resource mapping, and fallback behavior.

## Wording rule

Use wording like:

```text
experimental content-fidelity preview
partial table/image/style preservation where verified
exact Word/Hancom pagination is not supported
```

Do not use wording like:

```text
full Word support
full HWP support
Hancom-compatible renderer
Word-compatible pagination
```


### DOCX rendered bridge status

- Added `DocumentDocxLayoutExtractor`, a conservative DOCX bridge that converts paragraphs, run styling, basic tables, inline images, and page margins into the shared `RenderedDocument` model.
- DOCX numbering now reads `word/numbering.xml` for direct `numId` / `abstractNumId` definitions and renders ordered/bullet markers through the shared paragraph style model.
- DOCX table fidelity now preserves basic table width, grid column proportions, cell widths, `gridSpan`, `vMerge` row spans, border colors, and cell shading through the shared table model.
- DOCX style inheritance now resolves `styles.xml` document defaults, paragraph styles, character styles, and simple based-on chains into the rendered bridge. Direct paragraph/run properties still override inherited styles. Table styles and complex style-linked numbering are not yet claimed.
- DOCX image fidelity now reads inline/floating drawing extent metadata, preserves preview-safe dimensions in HTML, and marks `wp:anchor` floating drawings as downgraded blocks. Exact wrap/position placement remains a non-goal for this L3 cycle.
- DOCX footnote/endnote fidelity now collects referenced notes at the end of the rendered document with body reference links and backlinks. Exact Word note placement is not claimed.
- DOCX header/footer fidelity now surfaces directly referenced header/footer parts as reading-order sections before and after the main body, including paragraph/table content and local header/footer image relationships. Exact per-page repetition, first/even placement, and dynamic field layout are not claimed.
- `DocumentPageActivity` now tries the new DOCX rendered path first and falls back to the previous semantic Word HTML path if conversion fails.
- DOCX/Word inline and conservative `$$...$$` display math now render to HTML+CSS without WebView JavaScript: delimiter-only expressions, sub/superscripts, fractions, square roots, Greek letters, and common symbols, including math whose delimiters are split across runs. Lone currency amounts are preserved as plain text.
- HWP binary documents now convert section/paragraph/control structure into the shared `RenderedDocument` model: tables with column spans, proportional widths, per-edge cell borders, and authored cell heights; character size/bold/italic/color/underline; paragraph alignment; paragraph-head bullet markers; empty-paragraph spacing; and GSO control lines as horizontal rules. The official-letter heuristic remains a fallback only.
- HWPX rendered output now carries header/run styles, page metrics, and table color where directly present. Cell vertical alignment, cell background fill, non-line GSO shapes, and encrypted HWP are not yet claimed.
- This is still not L4 pagination; it is only the first DOCX/HWP L3 infrastructure connection.
