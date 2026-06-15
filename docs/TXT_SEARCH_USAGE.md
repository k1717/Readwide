# Reader find-in-page

The TXT reader's search dialog (the search button in the bottom toolbar) supports moving to the next or previous match, jumping to the nth match by number, and a live match counter. When TXT display rules are active, the search runs against the text as displayed on screen.

Markdown, EPUB, HWP/HWPX, and Word-family document viewers use the same search option model for their document-search dialog instead of Android WebView native find. In those viewers, the current result is highlighted in the rendered document and revealed in a popup-safe position where possible; page counts and visual positions still depend on the rendered document layout rather than the TXT exact source-page model.

Three options can be toggled in the dialog. The selected state is remembered until the dialog is next opened, and Unicode normalization (NFC) is always applied so equivalent compositions of the same characters match.

## Case sensitive

When on, uppercase and lowercase are treated as different characters. When off (the default), `Apple` also matches `apple`.

## Whole word

When on, the query matches only where it stands as a complete word, and is skipped when it appears as part of a longer word. A word boundary is where the adjacent character is not a letter, digit, or underscore.

- `cat` matches `cat` and `cat-dog`, but is skipped in `category` and `scatter`.

This option is a poor fit for languages written without spaces between words, such as Korean, Chinese, and Japanese. For example, searching `구원` with whole word on will skip `구원자` because the following character `자` is a word character. In such text, leave whole word off.

## Regular expression

When on, the query is interpreted as a pattern rather than literal characters, using Java regular-expression syntax. Common constructs:

- `.` any single character
- `\d` a digit, `\w` a word character, `\s` whitespace
- `+` one or more, `*` zero or more, `?` zero or one of the preceding
- `[a-z]`, `[가-힣]` any character in the range
- `|` alternation (either side)
- `^` start of line, `$` end of line

Examples: `colou?r` matches both `color` and `colour`; `제\d+장` matches `제1장`, `제12장`, and `제345장`; `[가-힣]+님` matches name-plus-honorific forms such as `홍길동님`.

An invalid pattern (for example an unclosed parenthesis) is treated as "no matches" and never crashes the app. Regex matches are counted without overlap, so `\d+` finds three matches in `a1 b22 c333`.

## Document viewer notes

For Markdown, EPUB, HWP/HWPX, and Word-family documents, the options below are applied to rendered text segments. Search results can move between rendered pages and can be pulled away from the bottom search dialog when they are near the end of the document. Exact source offsets and exact pagination remain a TXT-only guarantee.

## Combining options

Options can be combined. For instance, whole word together with regex restricts pattern matches to standalone words. Case sensitivity applies to regex matches as well.

For ordinary reading, leaving all three off is the simplest behavior; regular expression is an advanced option for finding numbers or specific formats. Match offsets are always reported in original-text coordinates, so bookmarks and page anchors are unaffected by any option.
