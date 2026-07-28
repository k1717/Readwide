# Readwide 1.0.14 development changes (vs 1.0.13)

Baseline: 1.0.13 (versionCode 10013), the released source package.

## Viewer layout

- EPUB document pages now automatically switch to a two-page side-by-side spread in landscape orientation.
- The rule applies to phones and tablets; portrait orientation remains the existing single-page view.
- PDF single-page mode now renders a side-by-side two-page spread in landscape orientation.
- PDF vertical continuous mode is unchanged.
- Page buttons, tap zones, and page-swipe gestures advance by one visible spread in landscape two-page mode.
- Direct page jumps still land on the requested page.

## Metadata

- versionCode 10013 -> 10014.
- versionName "1.0.13" -> "1.0.14".
- No dependency or permission change.

## Spread chrome overlay (PDF)

The PDF viewer's fit-zoom bottom reserve (viewport bottom padding sized to the
visible bottom bar) is skipped in the landscape two-page spread, the same way
it is skipped when zoomed: the controls overlay the spread instead of shrinking
it, so toggling them never resizes the pages. The chrome-toggle re-fit/
re-render is also skipped in spread mode, since the layout no longer changes
(the spread has no cache, so it would re-render both pages per toggle for no
visual change). The EPUB document viewer needs no change - its chrome is
already a pure overlay outside the content column.

## EPUB spread input and margins

The document touch pipeline was attached to the left WebView only, so in the
landscape spread the right half of the screen ignored taps entirely - the
chrome toggle, tap-zone page turns, and fling paging never fired there. The
right view now routes events through the same shared gesture pipeline
(fast-tap paging + the shared GestureDetector, which owns the toggle, zones,
and flings); the Word-selection/word-swipe machinery stays left-view only (the
spread is EPUB-only), and unhandled events still scroll tall right pages.
EPUB boundary margins are also mirrored onto the right view so the spread's
two pages stay symmetric.

## Landscape sweep over the other viewers

- Markdown: the visual-page model's stable viewport caches
  (`lastStableMarkdownContentHeightPx` / `lastStableMarkdownViewportHeightPx`)
  were reset on document load and text-zoom changes but not on rotation, so
  after rotating (with the chrome hidden) the page count, visual page turns,
  and the read-aloud start anchor were computed against the previous
  orientation's viewport height. `onConfigurationChanged` now performs the same
  reset the text-zoom path does before its existing same-page re-show.
- Word/HWP landscape stays single-page by design (the spread gate is
  EPUB-only); the right WebView is GONE and its idle load is a no-op.
- Single-page PDFs (pageCount == 1) never enter spread mode; PDF vertical
  continuous mode is excluded by the spread gate and its rotation relayout is
  the RecyclerView's own.
- The TXT reader, image viewer, and CustomReaderView are byte-identical to the
  1.0.13 release sources.

## Spread tap zones and the PDF top bar

- EPUB spread center-tap toggle: tap zones were computed per WebView
  (view-relative x against the view's width), so the screen center - the left
  view's trailing zone plus the right view's leading zone - turned pages
  instead of toggling the controls. In spread mode the zones are now computed
  against the whole spread container in screen coordinates: far page edges
  page, the middle (including the seam) toggles.
- PDF top title bar (landscape spread only): the app bar moved out of the
  content column to a root-level top overlay. Outside the spread the viewport
  gets a top reserve equal to the bar's current height (both chrome states -
  the bar swaps between the toolbar and the compact page strip), reproducing
  the previous portrait/continuous layout; in the spread the reserve is the
  compact page strip's height in BOTH chrome states, pinning the rendering to
  the controls-hidden layout: toggling the controls never re-renders or
  resizes the spread, and the taller title toolbar overlays the strip area
  while shown (strip height cached when visible, force-measured before first
  hide, 32dp minHeight fallback). The inset method was renamed
  applyPdfViewportBarInsets since it now manages both edges. The reserve rides
  the existing startup post (PdfReaderStartupController), the chrome toggle
  (with a posted reapply for the bar's post-layout height), the zoom path, and
  a new rotation reapply so leaving the spread restores it.

## Review sweep fixes

- The spread's right WebView was never destroyed with the activity
  (destroyDocumentWebView tore down the left view only) - a WebView/renderer
  leak; it is now torn down alongside.
- Historical correction: the intended spread boundary behavior was checked
  during 1.0.14 work, but the committed forward clamp still allowed an
  even-length document's already-visible right page to be repeated alone.
  Readwide 1.0.15 fixes that edge and adds a committed JVM regression test.
  The last odd page remains reachable as a standalone final page.

## Viewer audit (document/text/image)

Sweeps: locale-less case conversions and String.format (clean - the two hits
are inside the injected JavaScript, which intentionally uses JS semantics with
a 1:1-fold guard), stream lifecycles (try-with-resources), handler/executor
teardown (document: activityDestroyed flag + generation bump + executor
shutdown; image viewer: handler drain + four executors shut down + LruCache
recycle-on-evict with a min(128MB, heap/5) budget), and the TXT view's TTS
highlight range (fully bounds-clamped).

Verified with evidence rather than assumption: the right spread view's touch
listener registration order (bindViews at startup line 36 precedes
installSwipePaging at 49, so the view is bound when the listener installs),
and ImageSequenceState.applyRename matches the exact pre-rename path, so
case-only renames resolve correctly.

One fix: in portrait (and for non-EPUB documents) every page turn issued an
unconditional `about:blank` load to the hidden right view; it now blanks only
when the view actually holds content, tracked by a flag.

## Refactoring pass + code map

- NEW `util/SpreadMath`: the two-page spread's index math (displayStep,
  rightIndex, clampIndex, turnTarget), previously carried as parallel private
  copies in DocumentPageActivity and PdfReaderActivity, now shared so the two
  viewers' paging semantics cannot drift. Both activities' helpers are now
  one-line delegates. Historical correction: the ad-hoc harness used during
  1.0.14 did not catch the even-final-spread clamp regression; 1.0.15 adds
  `SpreadMathTest` to the repository and fixes that case.
- NEW `util/PdfGlyphBoxMath`: the per-glyph highlight box formula, previously
  byte-identical private copies in PdfTextSearchEngine and
  PdfPlainTextExtractor (constants pre-verified identical: 0.32/0.42 pads),
  now a single source used by both, so search and read-aloud highlights can
  never disagree about glyph bounds.
- NEW `docs/CODE_MAP.md`: a verified connection map of the codebase -
  per-activity controller trees (Main ~20, TXT 36, document, PDF, image),
  the shared TTS core and its seams (TtsHost, text sources, highlight
  controllers, floating card, playback service), the spread subsystem, the
  load-bearing util classes, and conventions (controller pattern, pure *Math
  classes with JVM harnesses). Areas not explored class-by-class (archive/,
  document/render, ...) are explicitly marked as package-level summaries.

## Refactoring (continued) + user-feedback verification

- NEW `util/TtsAnchorTextMath`: `indexOfCollapsed` (whitespace-insensitive
  buffer search) and `snapToNaturalStart` (line/sentence/word start snapping)
  moved out of DocumentTtsTextSource into a pure *Math class per the CODE_MAP
  convention; the text source keeps 1-line delegates. JVM harness re-run
  after the move: 10 cases ALL PASS.
- Reddit feedback (user cositas_, .txt title styling + "some .txt files do
  not display the title") verified against the sources: `.md` routes to the
  document viewer whose Markdown renderer styles the first heading
  (1.78em/bold/underline, theme currentColor - the app itself paints nothing
  green; the green in the user's screenshot is their own annotation);
  `.txt` routes to the plain-text reader, which has NO title detection or
  styling of any kind, and there is no txt-as-markdown setting. So "some
  .txt titles don't display" cannot be a styling bug in the current code -
  the likeliest candidates are resume-position restore (title scrolled
  off-screen) or an encoding issue on the first line, both needing the
  user's sample to confirm. A clarification reply was drafted
  (reddit_reply_txt_titles.md, outside the repo); no speculative code change
  was made.

## TXT reader title fix (user report)

A Reddit user reported that some .txt files open with no title shown. Root
cause: `FileUtils.normalizeDisplayFileName(null)` returns "" (empty), so a
content provider that reports no display name yields an EMPTY name - which
slips past every `!= null` fallback and lands as a blank toolbar title. The
image, PDF, and document viewers already guard with trim-empty checks; the
TXT loader was the only caller with a null-only check. Fixed in three layers:
the TXT load path treats blank as missing and falls back to the local copy's
file name; `applyDocumentIdentity` prefers the path's basename before the app
name; and the background-snapshot restore applies the same guard for old
sessions. The reader's themed in-chrome title (`readerFileTitle`, colored by
`applyReaderSystemBarColors`) displays the same corrected name, which also
addresses the "title color like .md" half of the report.

### TXT title report, part 2 (with the user's screenshot)

The screenshot showed the real primary cause: the TXT reader was the only
viewer that opened fully immersive (`toolbarVisible = false` initial, no
startup reveal), so a fresh .txt open showed no title at all while .md (the
document viewer) opens with its title bar - "some show the title and others
do not" was chrome state, not the file. The reader now reveals the controls
(bottom bar + the theme-colored `readerFileTitle`) once when the first load
applies, matching the other viewers' open state; a per-activity flag keeps
reloads/encoding changes from re-forcing it, and the background-snapshot
restore pre-sets the flag so a resumed read keeps whatever state it had.
The blank-display-name fallback from part 1 remains as hardening for the
provider-name case.

## Second refactor + bug-hunt round

- `util/FileUtils.displayNameOrBasename(name, path, fallback)`: the blank-safe
  display-name resolution added for the TXT title report existed as two
  near-identical inline copies (apply + snapshot restore) within a day of
  being written; extracted and JVM-verified (6 cases incl. whitespace-only
  names and trailing-slash paths).
- Initial controls reveal re-keyed from a per-activity boolean to the file
  path (`chromeRevealedForPath`): ReaderActivity is singleTop, so a second
  file arriving through onNewIntent would have consumed the one-shot flag and
  opened without the reveal; path keying reveals per file while still never
  re-forcing controls on reloads/encoding changes of the same file. The
  snapshot restore pre-sets the path to preserve a resumed read's state.
- MainActivity's open-from-app router had the same empty-display-name hazard
  as the TXT loader: a provider reporting no name yields "" (no exception),
  which failed every extension check and could misroute the file; a blank
  guard now falls back to the URI's last path segment like the catch branch.
- Verified non-issues this round: `dpToPx` exists in PdfReaderActivity (the
  strip-reserve measure path compiles), and `applyDocumentIdentity` sets
  `filePath` before the reveal check reads it.

## Image viewer revisit efficiency

The decode pipeline itself was already strong (bounds decode -> sample size
with tall-image and pixel-cap handling, OOM sample-doubling fallback, a
preview/detail two-tier with an `originalQuality` skip). The gap was the
cache layer: only PREVIEW bitmaps were ever cached, and the cache-hit path
unconditionally reset `currentImageDetailLoaded` and fired the detail refine -
so every revisit of a page re-ran the detail decode, even for pages whose
cached bitmap was already full quality. Now: the completed detail bitmap is
put into the cache (replacing the preview AFTER the display swap, so the
evict hook's recycle is safe and its `currentBitmap` guard holds regardless),
and a main-thread-only per-index full-quality set (populated by
original-quality preview puts, prefetch puts, and detail puts; invalidated in
`entryRemoved` so evictions can never leave a stale record) lets the hit path
skip the refine entirely. Verified along the way: all cache mutations are
main-thread, `LoadedImage.forBitmap`'s quality flag now reflects the cached
state, and `ArchiveImageEntryCache`'s decode call is a bounds-only validity
check (no efficiency concern).

## TXT title strip: font-true one-line height (superseded below)

The title overlay was not literally fixed-height - `updateReaderFileTitleMaskBounds`
already grows it to mask the content's first row - but its FLOOR was a 24dp
constant (XML initial 30dp) with `Gravity.TOP`, so a title font whose full
extents exceed the floor (accessibility font scale, fonts with tall
ascenders/descenders) spilled below the strip. The floor is now one full line
of the title's own font (`FontMetricsInt.bottom - top`, the full extents
rather than ascent/descent, plus 6dp breathing room), the text is vertically
centered, and the XML initial height is wrap_content so the pre-mask frame is
font-true as well. The first-row masking behavior is preserved: the row-based
bound still wins whenever the content row is taller.

## TXT title strip, corrected: hard-capped strip + text fit

The first attempt (raising the strip's height floor to one full line of the
title font) fixed the spill but introduced the opposite defect: with a large
system font scale or an unusual-metric title font the strip grew PAST the
first-row slot and clipped the second content row. Covering row one is the
intended masking; row two is untouchable. Final design: the strip is
hard-capped to the first-row slot (rowBottom + 2dp; one title-font line only
as the fallback when row metrics are unusable, 16dp minimum), and the TITLE
TEXT is what adapts - `fitReaderFileTitleTextToStrip` re-baselines to 14sp on
every mask update and proportionally shrinks (floor 8dp) whenever one full
line of the font would exceed the strip. The proportional-fit math is
JVM-verified (shrink, no-shrink, extreme-font floor, zero-budget guard). The
theme path's own `setTextSize(14f)` was removed so it can't undo a fitted
size between theme apply and the next mask update; the fit helper owns
sizing.

### TXT title strip, final form: body-font title

Per follow-up direction, the title's baseline is no longer a fixed 14sp: it
uses the BODY text's typeface and pixel size (new CustomReaderView accessors
`getContentTypeface`/`getContentTextSizePx`, reading the view's actual paint
state rather than re-resolving prefs), so the title's line height tracks the
body font and fills the first-row slot like a body line. The strip hard-cap
(first-row slot, second row untouchable), the vertical centering, and the
proportional shrink guard are unchanged; the guard now only engages when the
body font's full extents exceed the row slot (tight line-spacing settings).

### TXT title strip: baseline alignment (position parity with the masked row)

With body font/size and CENTER gravity the title still sat visibly lower than
the line it masks. Root cause: the body renders through a StaticLayout with
`setIncludePad(true)`, where only the FIRST layout line carries extra top font
padding (the code base already compensates for this in the large-TXT anchor
path via `firstPadCompensation`) - and a single-line overlay TextView is
always a "first line", so its glyphs sit lower than a normal body row by
exactly that padding. Fix: `CustomReaderView.getFirstLinePadCompensationPx`
measures the difference with a two-line probe layout using the title's own
paint under the body's spacing/include-pad conventions, and the strip's top
is extended upward by that amount with TOP gravity - the title's baseline
then lands where the masked row's baseline was. Horizontal centering, the
row-slot hard cap at the bottom (second row untouchable), and the shrink
guard (now applied before the probe so compensation uses the final size)
are unchanged.

### TXT title strip: bottom edge tightened

The strip's bottom previously extended 2dp past the masked row's bottom into
the inter-row gap; per follow-up direction it now ends exactly at
`rowBottom`, so the mask covers precisely the first row and nothing below it.

### TXT title strip: body font minus one step

Per follow-up direction: the masking region is untouched (the first-line pad
compensation is now measured with the BODY paint via a no-arg overload, so
the strip is fixed by the row's own metrics), and the title renders one app
font-size step (1sp) below the body size. With TOP gravity, the smaller
line's shorter first-line offset floats the text slightly upward inside the
unchanged strip - exactly the requested top/bottom rebalance, with no extra
shift mechanics.

## TXT viewer hunt round

- Performance regression fixed (self-inflicted): `updateReaderFileTitleMaskBounds`
  runs on EVERY scroll event, and the recent title work made each run set the
  typeface/text size (view invalidation) and build a probe StaticLayout for
  the first-line compensation - per scroll frame. Added: an early no-op while
  the title is hidden, and an input-key fast path (row geometry + status
  height + body font size/typeface identity) that skips the whole recompute
  when nothing changed; the reveal path was reordered to set visibility
  BEFORE the bounds update so the fast path's hidden-check can't skip the
  first layout.
- `applyPreferences` now ends with a title-visibility refresh: font size /
  typeface / line-spacing changes move the first-row geometry, and the scroll
  hook doesn't fire when the restored scroll position is unchanged.
- Verified non-issues: the title view consumes no touches (not clickable -
  taps pass through to the reader), rotation needs no extra hook (the row top
  is padding-derived, not width-derived, and the scroll/insets paths follow),
  both delayed posts are guarded (destroyed flag / scheduled flag), and the
  Reader* controllers are clean on the locale/format sweeps.

### TXT title strip: follow-up trims

Title size lowered one more app step (body - 2sp), and the strip's bottom
edge trimmed 2dp above the masked row's bottom.

## Large-TXT partition logic audit

Verified sound: partitions are LINE-based over a streaming charset decoder
(no multibyte-split hazard by construction), the terminal-newline off-by-one
is explicitly handled, blank-line collapsing advances the canonical line
counter only for emitted lines (consistent with the renderer), the partition
LRU caches (normal + manual-handoff) make revisits free, prefetch is
generation-guarded, and `LargeTextContinuityMath` passed an 8-case JVM edge
battery (grid alignment, negative/beyond-EOF clamps, lookbehind at line 1,
tail clamping). Beyond-EOF start lines cannot crash: jump inputs come from
bounded UI sources (the seek bar's max is the page count; bookmarks are
in-range), and even a degenerate window just ends the read loop at EOF and
returns an empty capture (`capturedAny=false`) for the caller's fallback.

Fixed: `TextDisplayRuleManager.getRules` re-read SharedPreferences and
re-parsed the rules JSON on every partition read (including every prefetch);
it is now memoized behind a volatile cache invalidated by saveRules (every
mutating caller already copies the returned list).

Known improvement, deliberately deferred (design note): a partition's FIRST
load streams the file from line 1 to the window (the seek path exists only
for the byte-preview helper), so first-visit cost grows with position and a
sequential read of a huge file totals O(N^2) decode work. A forward read
cursor would make sequential reading O(N). IMPLEMENTED - see the section
below (the release schedule allowed it after all).

## Large-TXT forward read cursor (O(N^2) -> O(N))

`LargeTextPartitionReader` gained a session-scoped `ForwardCursor`: the open
reader plus the tiny streaming state (checkpoint line, emitted-char total,
the blank-collapse boolean, the display-rules version, and file identity).
`readPartitionAtStartLine` resumes from it for forward requests and resets it
(re-priming along the way) on backward jumps or any state change; jumps via
`readForChar` deliberately bypass the cursor so their latency never waits on
a prefetch.

The non-obvious part: consecutive partitions OVERLAP by the lookahead region,
so a naive cursor parked after the capture end can never serve the next
request (the first benchmark proved it: 1.1x). A BufferedReader cannot
rewind - but emitted text can be replayed. The cursor therefore checkpoints
at the BODY end and carries the already-decoded lookahead lines (plus the
carry line, plus any unprocessed remainder) as a replay queue; a resume
consumes the queue first, never re-filtering (collapse state was already
advanced), then continues with the reader. Char accounting uses the join
identity (k emitted lines always total out.length()+1 chars, blank lines
included). EOF inside the lookahead leaves a reader-less queue-only cursor.

Ownership: `ReaderLargeTextPartitionReadController` holds the cursor behind a
lock (reads arrive from different single-thread executors - foreground loads
vs prefetch - and serializing them lets a prefetch continue exactly where the
foreground read stopped); `ReaderLifecycleController` closes it at teardown,
and the stats-scanning convenience overload (initial load only - verified the
sole caller) routes through the same cursor path. `TxtBlankLineCollapser.Filter`
gained a state restore constructor/getter, and `TextDisplayRuleManager` a
rules version the cursor keys on.

Verification: a JVM equivalence harness runs the REAL reader (android-free
stubs for the two per-line transforms) comparing legacy full-scan vs
cursor-threaded results field-by-field across 13 scenarios - sequential
(collapse on/off, CRLF), forward gaps, backward resets, repeated windows,
lookbehind mixes, EOF tails, tiny files, blank-run collapse edges, small
partitions with oversized lookahead (queue overflowing the next capture), EOF
inside the lookahead (queue-only resume), and collapse-state handoff chains -
ALL PASS. Benchmark (100 sequential partitions over a 400k-line file):
full-scan 4055 ms vs cursor 320 ms, 12.7x here and growing with partition
count.

### Forward cursor, part 2: lookbehind and the real partition models

Follow-up verification against the app's ACTUAL parameters exposed a second
overlap the first design missed: `includeLookbehind=true` requests come from
the manual-scroll handoff path (prefetchManualHandoffPartitionByStartLine /
the navigator's useManualLookbehind), whose windows start a LOOKBEHIND before
the next body - below the cursor's checkpoint, so every scroll-driven forward
handoff would have reset to a full rescan (scroll readers would have kept the
O(N^2) behavior).

Fix: the cursor also retains the last lookbehind-many emitted lines below the
body-end checkpoint as a tail, spliced in front of the replay queue; the
cursor's semantics generalized to (queueStartLine, charsBeforeQueue, queue)
with queueStartLine = bodyEnd + 1 - tail. Tail upkeep is a bounded deque with
incremental char accounting (evictions subtracted), fed by every emitted line
at or below the body end (skip regions included, so forward gaps stay
resumable). Partition-mode switches (4000/400 standard vs 12000/600
high-buffer, constants verified in PrefsManager) need no cursor key: the
accounting is line-based and window-geometry-agnostic, and a larger new
lookbehind than the retained tail simply fails canResume into a safe reset.

Equivalence battery extended to 18 scenarios with the real models - standard
4000/400 handoff chains (lb=true sequential), 12000/600 sequential, handoff,
and collapse-off variants, page-tap/scroll interleaves (lb false/true mixed),
and backward handoffs with forward recovery - ALL PASS, field-identical.
Benchmark: the lookbehind handoff chain now runs in 303 ms where the
full-scan behavior took ~4.2 s (the plain sequential chain: 337 ms), i.e. the
scroll path got the same O(N) win as page taps.

### Forward cursor, part 3: adversarial verification (skip/dup + the page-count question)

An external review (GPT) proposed reverting the controller to the pre-cursor
static reader ("legacy-partition-safe") out of field-compatibility concern,
without a specific root cause. Adjudicated with evidence instead:

- Randomized differential battery: 2,400 trials across 4 seeds (~17,800
  requests) - random files (blank runs, empty lines, trailing/no-trailing
  newline), random P/LA/LB, forward/backward/gap/repeat sequences with random
  includeLookbehind, collapse on/off, and NON-IDENTITY per-line transforms
  (including a rule that blanks lines, exercising the transform-then-collapse
  interplay the earlier stubs were blind to). Legacy full-scan vs cursor:
  field-identical and content-identical in every request.
- Tiling proof: per trial, the sequential partitions' BODY regions
  (bodyStartCharCount..bodyCharCount) concatenated with single joins must
  reconstruct the canonical document exactly - a direct no-skip/no-duplicate
  proof at the content level. ALL PASS.
- The only failures ever observed were in the HARNESS's reference model, not
  the reader: a file ending without a trailing newline never yields a final
  empty line from readLine, which the reference initially miscounted
  (systematically exactly one character, ~10% of trials - both signatures
  matched the diagnosis, and the fix cleared everything).
- The two-arg initial-load rewrite was also line-diffed against the original
  stats+clamp overload: identical semantics.

Conclusion: the cursor cannot change partition fields, so it cannot change
the total page count; the proposed revert would trade a verified ~13x
sequential win for a non-bug. If a device page-count difference between
(9) and (11) is reproduced, the comparison conditions (same file, same
partition mode 4000/12000, same blank-line-collapse and font settings) and
the two displayed totals are the next diagnostic inputs - the codebase's own
history notes total-page sensitivity to viewport height, which the canonical
padding already pins and which none of the (11) changes touch.

### Forward cursor, part 4: the page-count question closed (input-closure audit)

Deep audit of the total-page pipeline (ReaderLargeTextExactPageIndexController,
LargeTextExactPageIndexState, ReaderPagePositionController,
LargeTextPageModelMath), adjudicating the external review's refined
hypothesis. Findings:

- The reviewer's #1 suspect - (11)'s new `applyPreferences()` tail calling
  `updateReaderFileTitleVisibility()` and thereby refreshing content padding
  before the exact-index build captures the viewport - is DISPROVEN by (9)'s
  own code: `ReaderPreferencesController.applyPreferences()` lines 85/92
  already set the stable top-padding extra and call
  `updateReaderContentTopPadding()` in BOTH versions, so the (11) tail is a
  same-value re-apply for padding (its purpose is the title strip). A stale
  viewport also cannot be captured structurally: `handleRestartIndexingTick`
  only starts a build after the layout-only signature is STABLE across the
  200 ms debounce, and any late padding change alters the signature and
  re-debounces into a rebuild.
- Input closure for the exact total between (9) and (11): the builder code
  (ExactAnchorBuild controller + LargeTextExactAnchorBuilder) is
  diff-identical; the layout inputs use identical formulas behind the
  stability gate; the collapse basis, partition mode/lines/buffer (PrefsManager
  is diff-identical - no default change or migration delta), the display-rules
  signature (`getSignature` untouched; the memo returns identical content),
  and expectedTotalLines/Chars (initial load path line-diffed equivalent) are
  all the same. With the same file, settings, and session state, the exact
  total CANNOT differ between (9) and (11).
- The displayed number is a convergence pipeline
  (`displayedTotalPages`: pending -> exact -> estimated -> local), and the UI
  marks the estimating phase with a "~" prefix on both current and total
  (`setPageLabels`). The estimate derives from the CURRENT partition's local
  pagination scaled by totalLines/partitionLines, so it legitimately shifts as
  partitions change - and the cursor makes partition turnover ~13x faster, so
  the moment-by-moment "~" number differs between (9) and (11) by timing
  alone before converging to the same exact value.
- The exact signature deliberately includes the partition mode (chunked
  building), so a 4000/400 vs 12000/600 settings difference changes the exact
  total by design - a control variable for any device comparison.

Device protocol for the reported difference: same file, verify identical
settings (partition mode, blank-line collapse, font family/size, line
spacing), wait until the page label loses the "~" prefix on both builds, then
compare. A difference in the un-tilded totals would contradict the closure
argument above and warrants the audit-log build; a difference only while "~"
is showing is the expected estimate-timing effect, not a skip.

### TXT title strip: bottom trim rolled back

The 2dp bottom trim from the follow-up round is reverted per direction: the
strip once again ends exactly at the masked row's bottom (`rowBottom`). The
body-2sp title size and everything else from the strip work stay.

## TXT viewer: 1.0.12 (released) vs 1.0.14 comparison audit

Full-tree comparison against the released 1.0.12 sources, scoped to the TXT
viewer (ReaderActivity + all Reader* controllers + CustomReaderView + the
large-text utils + prefs):

- Structure: identical - no controller added or removed; PrefsManager is
  byte-identical (the 4000/400 and 12000/600 partition models both predate
  1.0.12's release).
- Pagination-critical layer: byte-identical - `getReaderContentTopPadding`,
  the canonical `getStableStatusOffTopPaddingPx` / stable-bottom-padding
  invariants (already present in 1.0.12, comments included), the 1.18f line
  height formula, and every exact-index input file. Combined with the cursor
  equivalence proofs, an upgrading user's page counts do not change.
- Every changed file (11) is exactly this cycle's work, nothing else:
  the forward-cursor set (reader, read controller, collapser state ctor,
  rules memo/version, lifecycle close - engine-equivalence proven), the
  open-with-controls + blank-display-name fixes (apply 15 / load 10 /
  snapshot 8 / activity 13 diff lines - the activity's only new identifier is
  `chromeRevealedForPath`), the title strip work (chrome controller), and
  CustomReaderView's three accessors (39 diff lines; the view's layout and
  pagination core untouched).
- Notably, no 1.0.13-cycle change touched the TXT viewer at all: the released
  1.0.12 TXT code survived unchanged until this cycle.
- One measurement correction made during the audit: an initial grep-context
  comparison flagged the padding formulas as differing; reading the actual
  bodies showed they are identical (the context windows had sliced different
  neighboring lines). Recorded so the earlier false positive doesn't resurface.

### Forward cursor, part 5: linearity proven by exact decode counts

Is the current algorithm actually O(N)? Answered with an instruction-count
proof rather than wall-clock (which JIT noise polluted): the harness counts
every physical line decode (one per readLine). Sequential full-file reads at
the real 4000/400 model:

| N lines | legacy decodes | cursor decodes | cursor+lookbehind |
|---------|----------------|----------------|-------------------|
| 100,000 | 1,309,624 (~N^2/2P = 1.25M) | 100,000 | 100,000 |
| 200,000 | 5,119,649 (~5.0M)           | 200,000 | 200,000 |
| 400,000 | 20,239,699 (~20.0M)         | 400,000 | 400,000 |

Legacy quadruples per doubling - exactly the O(N^2/2P) theory value. The
cursor decodes EXACTLY N lines - every physical line once, zero re-decodes
(replayed queue lines are stored strings, not decodes), on both the page-turn
and the manual-scroll (lookbehind) chains. That is not just O(N), it is the
information-theoretic floor: every line must be decoded at least once.

Stated boundary of the claim: forward-sequential reading is O(N) total.
Backward passes, char-position jumps (readForChar, by design outside the
cursor), and post-reset first reads remain O(position) per operation - making
those cheap too would require byte-offset checkpoints, a separate future
step. Cache hits stay O(1).

### Forward cursor, part 6: guard re-audit + exact-build latency (honest zero)

Skip/dup guard re-audit: all eleven structural guards verified present by
direct assertion (file identity length+mtime, encoding, collapse flag, rules
version, monotone queueStartLine <= windowStartLine, reset-and-reprime via
closeQuietly, EOF handling, pending-remainder splice, controller lock,
teardown close), and the randomized battery was extended with MID-SESSION
STATE CHANGES - random file appends (identity reset) and collapse toggles
between requests - 1,800 fresh trials ALL PASS. One more harness-reference
defect was found and fixed along the way (appending "\n..." to a file that
ended WITH a newline creates a blank line the model missed; the reader then
correctly clamped to the stale knownTotalLines the test supplied - the reader
honored its contract, the test lied to it). The real app cannot hit this:
scanLineStats recounts whenever the file changes.

Exact ("final") page computation speed: measured and reasoned - it does NOT
get faster, and saying so plainly: the builder is a single O(N) streaming
scan (one openReader call, diff-identical to v9) that never touches the
cursor. The two imagined indirect speedups both dissolve on inspection:
(a) font-change re-pagination pulls the partition from the LRU cache in both
versions (text unchanged - no reader involved), and (b) the invalidation
scenarios that DO force a re-read (collapse toggle, backward re-read of the
current partition) reset the cursor by design, so v11's re-read equals v9's.
Simulation on a 400k-line file confirmed it: v11's "resume" for a
current-partition reload measured 115 ms vs v9's 142 ms full scan -
i.e., a reset, not a resume, exactly as the guards dictate. The cursor's win
is partition loads (page turns, scroll handoffs, prefetch); the exact build's
own ~137 ms/400k-line scan floor (real builds add layout work on top) is
unchanged, and it shares `activity.executor` with loads without taking the
cursor lock, so v11 adds no new contention either.

### TXT title strip: opaque gradient mask

Per request, the strip's flat background became a document-viewer-like
gradient. Investigation first: the MD viewer has NO actual color gradient -
its GradientDrawables are solid dialog/sheet shapes, and the perceived fade
is the app bar's 11-12dp elevation shadow. Reproducing elevation on the strip
was rejected (shadow intensity is theme-dependent and near-invisible on dark
themes); instead the strip now uses a fully OPAQUE TOP_BOTTOM
GradientDrawable from a toolbar-toned edge (the theme's toolbarColor, or a 6%
text-color blend when toolbar equals background) into the content background.
Opacity is the hard constraint: any transparent fade would let the masked
first row's glyphs show through the title.

### TXT title strip: solid toolbar-colored top band (gradient superseded)

Per follow-up direction the gradient was replaced by a toolbar-state band:
`applyTopBandColors()` paints the window status-bar color (covers the
camera-cutout region), the status-bar icon appearance, the page-status bar,
and the title strip in `currentReaderToolbarColor` while the toolbar is ON,
and reverts everything to the reader background when OFF. Wired from theme
apply and from both toolbar-visibility writers (the shell toggle and the
auto-page-turn body-mode entry). The mask pass now anchors the strip's top at
the page-status bottom (sealing the gap into one seamless block) while the
title text keeps its exact masked-row baseline via a computed top padding -
the fit budget and the fast-path key are input-compatible, so neither
changed. Known cosmetic edge: with the page-status alignment preference set
to HIDDEN the (INVISIBLE) indicator draws no background, leaving that slice
of the band in the page color. Solid colors only, by masking necessity. A status-bar-color stomp was also caught and fixed in the right place: the loading window's SHOW path legitimately paints the plain background for the loading screen, but nothing restored the band afterwards - hideLoadingWindow now reapplies the toolbar-state band as the loading UI goes (an earlier attempt patched the show path itself; reverted once the context showed it was the wrong site).

### TXT title strip: mid-leading bottom + slight title drop (+ a real bug)

Per direction: (1) the strip's bottom now ends at the MIDDLE of the row's
trailing leading (the row box carries its inter-line space below the glyphs,
so ending exactly at rowBottom hugged line two) - measured with a body-paint
probe, clamped so tight line-spacing settings degrade to rowBottom, then raised one further dp per visual tuning; (2) the
title drops by HALF its natural float (the difference between the body's and
the fitted title paint's first-line baseline offsets, via a new
CustomReaderView accessor), keeping some of the balance lift. JVM checks: mid
computation, tight-spacing clamp, half-float, negative-float guard - 4/4.

Found while investigating the reported misalignment, likely its actual
cause: the theme path reset the title's padding with hardcoded zero vertical
(setPadding(36dp, 0, 36dp, 0)) - but since the band change, the top padding
POSITIONS the text within the strip, so any theme apply between mask updates
made the title jump up to the band top. The theme path now preserves the
current top padding and owns only the horizontal insets.

## TXT hunt round (post-band) + release docs pass

Hunt verdicts: the status-bar visibility preference only shows/hides the
system bar (no color writes - band-safe); rotation reapplies the band via
onConfigurationChanged -> applyTheme -> band tail; no remaining stompers of
the page-status background. One structural cleanup from the hunt: the theme
path still wrote the status-bar color, its light/dark appearance, and the
page-status background directly before the band tail overrode them - color
ownership is now solely `applyTopBandColors` (three redundant writes
removed), so no code path can ever paint a non-band-aware top color.

Docs pass: fastlane 10014 changelogs (en-US/ko-KR) rewritten - they still
described only the two-page spread; now they cover the spread, the large-TXT
speedup, the TXT open-with-title behavior and toolbar-colored top band, the
no-name share fixes, and the image revisit caching, within the F-Droid
length limit. CODE_MAP gained the ForwardCursor (engine note + util table).
README badges were verified already present.

## Engine accuracy audit: duplication / skip / line splitting

Focused re-audit of the line-splitting layer across every text path.
Verified sound (each read in full): the exact anchor builder DOES account on
the collapse-emitted line (`normalized = emitted;` follows the accept - an
earlier external excerpt omitted that line and made it look like a
normalized-space leak); `scanLineStats` and `readPartitionForChar` use the
identical normalize->rules->collapse->len+1 chain, so jump landings share the
partition grid's coordinate space; and `TxtBlankLineCollapser.collapse(String)`
splits on \n, \r\n, AND lone \r with the same Filter, rejoining with '\n'.

Two real defects found and fixed, both in the SMALL-file (whole-load) path
with blank-line collapse OFF:
1. `readTextFile` performs no newline normalization, so lone-CR (old Mac)
   files rendered as ONE merged line (StaticLayout does not break on \r) and
   CRLF files carried a stray char per line, shifting bookmark/search/TTS
   character offsets against the large-engine space - and since
   collapse(String) incidentally normalizes, the coordinate space DEPENDED ON
   THE COLLAPSE SETTING.
2. `enforceTextPresentationSelectors` (which INSERTS characters, so it is
   coordinate-bearing) ran in every streaming path but never in the
   whole-file chain.
Fix: `FileUtils.normalizeTextForDisplay` - CRLF/CR -> '\n', a single
trailing newline dropped (readLine never yields a final empty line; the
partition joiner adds no trailing separator), then the selector pass -
applied at the small path's single entry. Equivalence with
BufferedReader.readLine joining proven over 3,000 randomized newline-mix
cases (mixed \n / \r\n / \r, blank runs, trailing variants). Existing
bookmarks in affected files re-land via the anchor-text restore mechanism
that already exists for coordinate-space changes.

## Release documentation pass (1.0.14)

- CHANGELOG/PATCHNOTES: release date set to 2026-07-08; the title-strip
  bullet's wording corrected to the final shipped behavior (the earlier text
  still described the exact-row-bottom edge and exact-baseline alignment from
  intermediate iterations; it now states the mid-line-gap lower edge and the
  balance-nudged baseline).
- fastlane: this project's changelog locales are en-US and ko-KR (matching
  every previous release); both 10014 files are current (401/227 chars,
  limit 500).
- README: the large-TXT bullet now mentions the O(N) sequential reading; the
  two-page spread bullet and the badges were already present.
- Historical pre-tag state: versionCode 10014 / versionName 1.0.14 was verified,
  while the draft F-Droid yml still contained an unresolved 1.0.14 block. That
  placeholder is not present in the current 1.0.16 source package; its local
  mirror intentionally tracks the published fdroiddata entries through 1.0.13.

Remaining MANUAL release steps (require the device/repo):
1. Device test matrix: spread (EPUB/PDF, rotation, zoom, controls toggle,
   fixed-layout EPUB), large-TXT forward/backward/scroll-handoff + mode
   switch, TXT open-with-title + top band per theme, CR/CRLF small files,
   image revisit quality, TTS resume per viewer.
2. Historical step: tag v1.0.14 on its release commit and submit an immutable
   F-Droid build block. The current source does not retain a fake commit value.
3. Build/sign the release APK with the readwide key; attach to the GitHub
   release; verify the shields badges pick it up.
