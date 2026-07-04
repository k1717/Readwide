# Archive-wide filename charset detection (name corpus)

Reference notes for `ArchiveFilenameDecoder.NameCorpus` and the naturalness
scoring added in 1.0.11, wired into the ZIP, ALZ, and EGG entry-name paths.

## The question this answers

Whether the text viewer's encoding-detection engine
(`TextEncodingDetector`) can be applied to archive filename mojibake. Direct
reuse: no - it is built on ICU/Mozilla statistical detectors sampling
192-256 KB of document text, and statistical detection collapses on 5-50
byte filenames (it is also Android-bound, while the archive layer is pure
Java). But its two operating principles transfer:

1. **Judge one large sample once, not many small samples independently.**
2. **Score naturalness, not just script membership.**

## The measured problem

`ArchiveFilenameDecoder` scored each name independently. On real inputs:

- CP949 one-syllable names ("가.txt" = 2 bytes) decoded to Thai gibberish
  while their long siblings decoded correctly - mixed encodings within one
  archive, which never happens in reality.
- IBM866 Russian names lost to windows-874 *at any length* (Thai scores
  11/char + a flat bonus vs Cyrillic's 9/char, so the ratio never flips).
- One GB18030 name flipped to MS949 because its byte sequence happened to
  decode into two Hangul syllables.

## Principle 1: the name corpus

`NameCorpus` collects every legacy-path raw name during a reader's entry
scan (ASCII names carry no signal and are skipped; ZIP names with the UTF-8
flag keep their flag path and are excluded). Resolution picks the single
charset - UTF-8 plus the existing 18 legacy candidates - that decodes
*every* observed name usably with the highest summed score. Each name is
then decoded with that shared decision, so a short ambiguous name inherits
the code page its longer siblings established.

Per-name precedence is unchanged ahead of the corpus: ASCII, flagged/valid
UTF-8, and explicit EGG locale code-page hints still win; the corpus only
replaces the per-name scoring fallback, and a name the corpus charset cannot
decode still falls through to per-name scoring.

Wiring: ZIP central-directory listing and the tail-scan recovery path
(`ArchiveSupport`, two-pass), ALZ (`PendingAlzEntry` holder, names decoded
after the full scan), EGG (raw FILENAME payloads kept on the entry, decoded
after the scan), and RAR4 (`Rar4FileBlock` holders captured during the walk -
which keeps the historical seek semantics bit-for-bit - then re-parsed with
the corpus; legacy non-Unicode RAR4 names previously fell back to a hardcoded
IBM437, so CP949/Shift_JIS/CP866 archives were garbled). RAR4's
Unicode-flagged name path is untouched. RAR5 and 7z need no wiring by
construction: RAR5 names are UTF-8 and 7z names UTF-16LE by format
specification, so no legacy code-page path exists.

## Principle 2: structural naturalness

Script-membership counting is what let misreads win: bytes misread into the
wrong code page still land on *letters*. What they cannot do is land on
letters in *legal positions*. Four hard structural checks (all cheap,
position-based, effective even on short names):

- **Thai**: combining vowels/tone marks must follow a consonant; leading
  vowels (U+0E40-U+0E44) must be followed by one; six-plus consonants with
  no vowel anywhere is not Thai. Cyrillic misread as windows-874 violates
  these constantly. (Also fixed en route: legal non-combining Thai vowels
  such as U+0E32 were previously counted as suspicious.)
- **Greek**: final sigma only ends words; accented capitals only start
  them. Genuine Greek collects position bonuses, misreads collect
  penalties. Greek-block non-letters (loose tonos marks and archaic
  letters) count as suspicious, and the accented-letter bonus that CP949
  and CP1251 bytes were farming (+120 per name) is now small and gated.
- **Russian**: marker letters (\u0451 \u044b \u044d \u044e \u044f \u0439 \u0449) pin text as Russian and now
  earn a bonus for the Cyrillic code pages - but word-initial \u0451/\u044b/\u0439, which
  real Russian essentially never produces and misreads constantly do,
  cancels it.
- **Bicameral case structure** (Cyrillic and Greek): real words are
  all-caps, all-lower, or Capitalized; a lowercase letter immediately
  followed by an uppercase one ("Жэ**Й**ЕКОР") is the signature of a
  misread. Latin is exempt - camel-case filenames are legitimate.

## Validation

Harness matrix, every set round-tripping byte-exact through its archive's
corpus: MS949 (including four one-syllable names), Shift_JIS (including
single-kanji names), IBM866, windows-1251, GB18030, Big5, and - guarding
against over-correction - genuine windows-1253 Greek, windows-1254 Turkish,
and windows-874 Thai. Per-name (no corpus): the IBM866 long-name case now
survives alone. End to end: a self-made EGG archive with locale-hint-free
MS949 names ("가.txt" among them) lists correctly through `EggArchiveReader`.
All nine pre-existing decoder tests pass unchanged; four new corpus and
naturalness tests are in `ArchiveFilenameDecoderTest`.

## Boundaries

A corpus of *only* short ambiguous names (e.g. a single two-byte name and
nothing else) still has little signal and falls back to the same best-effort
scoring as before - the corpus can only amplify evidence that exists
somewhere in the archive. Single-name archives gain nothing by construction.
The full `TextEncodingDetector` machinery (ICU, Mozilla, document-scale
statistics) remains text-viewer-only by design.
