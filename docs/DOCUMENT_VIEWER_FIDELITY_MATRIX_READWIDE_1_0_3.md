# Readwide 1.0.3 Document Fidelity Matrix

This matrix tracks the 1.0.3 L3 document-viewer fidelity cycle. It should be updated only when a fixture-backed implementation is added.

| Format | Current baseline | 1.0.3 target | Current support claim |
| --- | --- | --- | --- |
| DOCX / DOCM / DOTX / DOTM | L2-ish semantic HTML | L3 content-fidelity HTML | Existing converter supports semantic Word HTML. The new rendered bridge now covers paragraphs, run styling, `styles.xml` document/paragraph/character style inheritance, direct numbering.xml ordered/bullet lists, tables with basic width, grid, horizontal/vertical merge, border color and shading support, inline images with extent-derived size hints, floating-image block downgrade, referenced footnotes/endnotes as end-of-document sections, directly referenced headers/footers as reading-order sections, and page margins behind fallback. Table styles, complex style-linked numbering, complex border styles, dynamic fields/page numbers, exact per-page header/footer repetition, and exact floating layout are not yet verified. |
| HWPX | L1 text-first fallback | L3 content-fidelity HTML after DOCX | HWPX text extraction exists, and the rendered path now carries header/run styles, page metrics, table color where directly present, and raster embedded images (PNG/JPEG/GIF/BMP/WebP) as data URIs at authored size (with a placeholder frame for unrenderable WMF/EMF/OLE pictures). Broad OWPML layout/style/table fidelity is still not claimed. |
| HWP 5.x | L1 text-first fallback | Partial L3 after HWPX | HWP binary now converts section/paragraph/control structure into the rendered model: tables with column spans, proportional widths, per-edge cell borders, and authored cell heights; character size/bold/italic/color and underline; paragraph alignment; paragraph-head bullet markers; empty-paragraph spacing; GSO control lines as horizontal rules; and raster embedded pictures (PNG/JPEG/GIF/BMP/WebP) as data URIs at authored size, with a placeholder frame for unrenderable WMF/EMF/OLE pictures. The official-letter heuristic remains only as a fallback. Cell vertical alignment, cell background fill, non-picture non-line GSO shapes (rectangles/curves), and encrypted/password HWP are not yet claimed. |
| Legacy `.doc` | recognized classification only | not targeted for L3 in this cycle | Rendering remains unsupported. |
| EPUB | Existing EPUB WebView path | no fidelity-cycle changes | EPUB is not part of the 1.0.3 DOCX/HWP fidelity work. |
| Markdown | Existing Markdown WebView path | no fidelity-cycle changes | Markdown is not part of the 1.0.3 DOCX/HWP fidelity work. |
| PDF | Existing fixed-layout PDF path | no fidelity-cycle changes | PDF keeps separate fixed-layout behavior. |

## Stage checklist

| Stage | Scope | Status |
| --- | --- | --- |
| 1.0.3 version bump | versionCode 10003 / versionName 1.0.3 | started |
| DOCX converter audit | Record current coverage and gaps | started |
| RenderedDocument scaffold | Common intermediate model | scaffold added |
| FixedHtmlRenderer scaffold | Common HTML output for L3 preview | scaffold added |
| DOCX L3 v0 | paragraphs, run styles, lists, tables, images | infrastructure started; paragraphs/run style/style inheritance/direct numbering/table width/grid/span/border/shading/inline image sizing/floating downgrade/footnote-endnote sections/page margins connected behind fallback |
| DOCX inline/display math | LaTeX-like `$...$` and `$$...$$` fragments | connected; delimiter-only, cross-run, fractions, roots, sub/superscripts, Greek and symbols rendered; lone currency left as text |
| HWPX L3 v0 | OWPML paragraphs/runs/tables/images | partial; header/run styles, page metrics, table color, and raster embedded images (with placeholder for unrenderable WMF/EMF/OLE) connected behind fallback |
| HWP 5.x partial L3 | char/para shape, table, BinData image | partial; binary section/paragraph/control structure, tables with spans/widths/per-edge borders/cell heights, char and paragraph styles, bullet heads, and control-line horizontal rules connected; raster BinData images (PNG/JPEG/GIF/BMP/WebP) render as data URIs at authored size, with a placeholder frame for unrenderable WMF/EMF/OLE pictures |

## Fallback rule

If an L3 converter fails or exceeds safety limits, the viewer must fall back to the existing safe reader path or fail honestly with a clear unsupported/corrupt/encrypted message. It must not silently drop major content while claiming successful L3 preview.

## Shared scaffold baseline

The first shared L3 preview scaffold is now present under:

```text
app/src/main/java/com/textview/reader/document/render/
```

It adds a format-neutral `RenderedDocument` model and `FixedHtmlRenderer` output path with fixture tests. DOCX now has a conservative runtime bridge into this path; HWPX/HWP still use the existing text-first readers while the shared renderer contract is validated.

Initial model coverage:

- Flow-based rendered pages with page size and margins.
- Paragraph blocks with run-level style placeholders.
- Table blocks with row/cell structure, basic colspan/rowspan, width, border color, and cell background fields.
- Image blocks with local WebView-safe `src`, alt text, width/height, and floating-downgrade marker.
- Unsupported-placeholder blocks so future extractors can surface dropped features honestly instead of silently losing content.
- Plain-text aggregation and anchor ranges for future search/bookmark mapping.

Still not connected or not complete:

- Full DOCX L3 coverage; only conservative paragraph/run/basic-table/inline-image/page-margin bridge is connected.
- HWPX parser to `RenderedDocument`.
- HWP binary parser to `RenderedDocument`.
- Runtime switching for HWPX/HWP; DOCX has a first bridge with fallback.


### DOCX rendered bridge status

- Added `DocumentDocxLayoutExtractor`, a conservative DOCX bridge that converts paragraphs, run styling, `styles.xml` inheritance, direct `numbering.xml` ordered/bullet lists, tables with basic width/grid/span/border/shading support, inline images with size hints, floating-anchor image downgrade, referenced footnote/endnote sections, and page margins into the shared `RenderedDocument` model.
- `DocumentPageActivity` now tries the new DOCX rendered path first and falls back to the previous semantic Word HTML path if conversion fails.
- This is still not L4 pagination; it is only the first DOCX L3 infrastructure connection.

### DOCX inline/display math status

- `FixedHtmlRenderer` converts LaTeX-like math between `$...$` and `$$...$$` delimiters into HTML+CSS without enabling WebView JavaScript or network loading.
- Coverage includes delimiter-only expressions such as `$2Dt$` and `$L/W$`, sub/superscripts, `\frac`/`\sqrt`, Greek letters (for example `\rho`), and common operators/relations.
- Math whose delimiters span multiple runs (a frequent DOCX pattern when only part of an expression is italic/bold) is re-rendered as a unit, and lone currency amounts such as `$200` are left as plain text.

### HWP / HWPX rendered bridge status

- `DocumentHwpLayoutExtractor` converts HWP binary section/paragraph/control structure into the shared `RenderedDocument` model: tables with column spans, proportional column widths, per-edge cell borders, and authored cell heights; character size/bold/italic/color and underline; paragraph alignment; paragraph-head bullet markers; empty-paragraph spacing; and GSO control lines as horizontal rules.
- The earlier official-letter heuristic is retained only as a fallback when structural parsing does not produce blocks.
- HWPX rendered output carries header/run styles, page metrics, table color, and raster embedded images where directly present (unrenderable WMF/EMF/OLE pictures show a placeholder frame); broad OWPML table/layout fidelity is not yet claimed.
