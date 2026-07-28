# EPUB compatibility audit — Readwide 1.0.16

This audit compares the Readwide 1.0.16 EPUB loader with the 45 packaged and
extracted EPUB samples supplied from the IDPF EPUB 3 samples 20230704 set. It is
an off-device source and archive inspection, not a claim that every sample was
visually verified in Android System WebView.

## Structural results

| Check | Result |
|---|---:|
| EPUB ZIP/container/OPF parsed | 45 / 45 |
| Missing resolved spine resources | 0 |
| Unsupported resolved spine MIME after fallback | 0 |
| Whole-book fixed-layout classification after the mixed-layout fix | 9 books |
| Image-page landscape-spread classification | 7 books |
| Vertical-writing hint | 5 books |
| Direct image spine resources retained | 41 pages in 4 books |
| Valid OPF binding samples rewritten to local sandboxed handlers | 2 / 2 |
| OPF-linked SMIL documents parsed | 7 / 7 (1,137 validated cues) |
| Georgia scoped point CFIs parsed | 7 / 7 |

The audit compares every direct OPF spine itemref with the final supported spine
list, so an itemref silently omitted before the returned-list checks also fails.
The loader successfully retains standard XHTML spine items, direct raster/SVG
spine pages, Unicode paths, literal `+` characters in URI paths, percent escapes,
and supported manifest fallback chains. The `cole-voyage-of-life` mixed-layout
sample is no longer promoted to whole-book fixed layout merely because four
individual pages are pre-paginated; those item-level overrides remain fixed and
the other pages remain reflowable.

## Expected basic rendering

The following samples primarily exercise local XHTML/CSS/images that match the
current loader and should expose their main visual content, subject to the
installed Android WebView version:

- `childrens-media-query`, `epub30-spec`, `internallinks`
- `indexing-for-eds-and-auths-3f`, `indexing-for-eds-and-auths-3md`
- `jlreq-in-english`, `jlreq-in-japanese`
- `moby-dick`, `vertically-scrollable-manga`
- `wasteland`, `wasteland-otf`, `wasteland-woff`
- direct-image/SVG publications such as `haruko-jpeg`,
  `page-blanche-bitmaps-in-spine`, and `svg-in-spine`

Japanese vertical-writing CSS is recognized for the three Kusamakura variants,
`horizontally-scrollable-emakimono`, and `mymedia_lite`. This release also fixes
the WebView/Android pixel-unit mismatch that previously made a visible-sentence
bookmark capture collapse to a page-start bookmark on high-density devices.
The external-suite regression also verifies that the full Kusamakura vertical
sample remains classified as vertical writing and exposes 451 stable sentence/
block IDs to the viewport-caret bookmark path. Actual Android WebView geometry
still requires device QA; the test does not claim visual rendering equivalence.

## Scoped compatibility added in 1.0.16

### Scripted EPUB and bindings

Publisher JavaScript remains persistently enabled only for spine items carrying
the OPF `scripted` property, fixed-layout helper pages, and pages where a
validated OPF binding was actually rewritten. Ordinary pages may temporarily
enable short Readwide-owned anchor/CFI/highlight helpers and then restore their
page policy. Local JavaScript/XML/media resources retain their manifest MIME type,
literal `+` path semantics, and a small `navigator.epubReadingSystem` feature
surface. The `figure-gallery-bindings` and `quiz-bindings` objects are routed to
their declared local scripted XHTML handlers in iframes with
`sandbox="allow-scripts"`; they are not granted the parent publication's origin.
Binding XML image/link attributes are resolved against the XML payload before a
handler imports them.

Each opened publication receives a new synthetic local host, cookies are disabled,
unknown/missing resources return an explicit local error instead of falling through
to networking, and auxiliary handler XHTML cannot replace the primary reader page.
When a binding is inserted into a parent item not declared `scripted`, publisher
scripts, inline event handlers, and active script URLs are removed from that parent
before JavaScript is enabled for the sandbox frame. XHTML script/style CDATA wrappers
are normalized for the WebView's `text/html` parser.

This is deliberately not a claim of unrestricted browser compatibility. Remote
network content remains unavailable because the app has no `INTERNET` permission.
HTTP byte-range/206 emulation, publisher autoplay, `epub:trigger`, TTML, popups,
downloads, and arbitrary OPF handler privileges are not implemented. The core
local scripted paths in `trees`, `cole-voyage-of-life-tol`,
`childrens-literature`, and `cc-shared-culture` can execute, but behavior that
depends on those omitted browser/server features remains partial.

### Media overlays and pronunciation metadata

Readwide now follows only SMIL resources explicitly connected through a spine
manifest item's `media-overlay` attribute. The four supplied publications resolve
seven linked SMIL documents and 1,137 finite local text/audio cues: the three
Kusamakura variants plus `moby-dick-mo`. The document read-aloud button starts the
publisher narration when the current page has an overlay; long press retains the
separate Android TTS dialog. Playback is foreground-only, pauses when the activity
leaves the foreground, follows linked spine pages, and highlights the current DOM
fragment with the publisher active class when safe.

Playback begins a clip only after the OEM `MediaPlayer` seek callback completes.
Page-load generations prevent an obsolete WebView callback from starting a new cue
on the previous DOM, and automatic overlay page turns bypass the short user-gesture
turn lock.

This is a bounded sequential `par/text/audio` implementation, not a complete SMIL
timing engine. Repeats, event timing, concurrent streams, skippable/escapable
semantics, remote/DRM media, background audiobook playback, and guaranteed
sample-accurate seeking on every OEM codec are not claimed. PLS/SSML pronunciation
data in `georgia-pls-ssml` is still not applied by Android TTS.

### Navigation and package semantics

- Scoped EPUB point-CFI links are parsed and routed by itemref ID first, with the
  numeric spine step as a fallback. The Georgia samples cover element steps,
  logical text gaps, UTF-16 character offsets, assertions, percent escapes, and
  CFI caret escapes. Range CFIs, nested indirections, temporal/spatial offsets,
  and side-bias parameters are rejected safely. Readwide does not yet expose a
  dedicated UI for a non-spine navigation document's `page-list`.
- Cross-page CFI/fragment targets own the final post-load position, so the delayed
  logical-start alignment used by vertical writing cannot pull them back to the
  chapter start. Only the selected OPF path (or a literal fragment resolved from
  the current spine page) may supply a package CFI.
- `linear="no"` itemrefs are still retained in the page list. Thirteen such
  itemrefs occur across ten samples, so cover/nav/backmatter can increase the
  displayed total and enter ordinary next/previous progression.
- OPF `page-progression-direction`, `rendition:spread`,
  `rendition:orientation`, and `page-spread-left/right` are not yet authoritative
  reader inputs. Page direction remains a reader preference.
- In particular, `sous-le-vent_svg-in-spine` declares `rendition:spread=none`,
  while the image-book heuristic can still offer a landscape spread.
- Only the first `container.xml` rootfile is selected, so the alternate braille
  rendition in `WCAG` is not exposed.

### Specialized content

- IDPF-obfuscated fonts in `wasteland-otf-obf` and `wasteland-woff-obf` are not
  deobfuscated; readable system-font fallback may still be available.
- `epub:switch` in `hefty-water` is not normalized to a single supported/default
  branch.
- MathML in `linear-algebra` depends on Android System WebView because Readwide
  provides no independent MathML fallback.
- `mahabharata` has 2,014 spine items. The current eager page model can incur
  significant startup time and memory pressure.

## Scope of the result

The structural pass proves that the supplied archives, reading-order resources,
binding handlers, linked SMIL references, and scoped CFI syntax are reachable by
the Java loader. It does not prove CSS pixel-perfect layout, OEM media codec
behavior, every publisher script, font shaping, or Android System WebView behavior.
Those still require explicit device or emulator QA chosen by the maintainer.
