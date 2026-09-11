# EPUB path, narration and loading fixes — 1.0.18

## Decode URI references once

`DocumentPageActivity` no longer percent-decodes paths again after
`Uri.getPath()`. ZIP names containing literal sequences such as `%20`
now remain distinct from names containing spaces. Archive traversal rejection
and publication-local resource serving remain in place.
Outgoing chapter base URLs encode each ZIP directory segment while preserving
slashes, so literal percent sequences, spaces, `#` and `?` in folder names do
not turn into different paths, URL fragments or queries.

The OPF-resolved SMIL path uses `EpubSmilParser.parseResolvedPath`.
Resolved audio paths are passed unchanged to ZIP lookup. Raw-reference parsing
remains a separate API for encoded links. CFI navigation likewise uses
`EpubCfi.parseDecodedFragment` after `Uri.getFragment()`, preserving literal
percent sequences and hash characters inside supported assertions.
The distinction follows [Android Uri's decoded path/fragment APIs](https://developer.android.com/reference/android/net/Uri).

## Foreground-only media overlays

`EpubPlaybackGate` tracks foreground state, granted focus and transient-loss
resume intent. Backgrounding or explicit pause clears automatic resume intent
and abandons focus. Returning to the viewer does not automatically resume.
Late focus-gain callbacks from abandoned requests cannot restart playback.
Denied focus requests leave narration paused, following
[Android's audio-focus contract](https://developer.android.com/media/optimize/audio-focus).

Preparing, seeking, resuming and completing cues check current player/cue
state. A stale prepared callback no longer releases a newer player.
Pausing during preparation or an asynchronous seek no longer lets resume
start before the cue position is established. Completion while paused is
remembered for the next explicit resume instead of restarting the audio file.
Manual page changes preserve a paused narration state.
Changing audio files releases the previous player before extraction, and a
new worker request supersedes older requests. Resuming during that handoff
cannot seek the next cue in the previous audio file or reuse its completion flag.

## Audio cache

`EpubMediaCache` replaces 32-bit string-hash filenames with SHA-256 keys
over length-framed publication identity, resolved entry path, size and ZIP CRC.
Existing files must pass size and CRC verification before reuse.
Fresh extraction also checks actual output length/CRC, uses unique temporary
files and deletes unfinished temporary output. Same-sized content changes
with a different ZIP CRC no longer reuse stale audio.

These checks protect cache correctness; ZIP CRC is not cryptographic
authentication of a publication. The existing 256 MiB audio-cache entry
guard remains. Cache verification runs on the document worker, and a player
already prepared for the same audio file is still reused across its cues.

## Metadata and memory

All eight container/OPF DOM parse call sites in `DocumentArchiveUtils` now
use a bounded metadata read with the existing 32 MiB document-text guard.
Declared oversized text entries are rejected before decompression too.
The XML parser's existing security configuration is retained.
This is a document-memory guard, not a restored archive extraction ceiling.

During chapter preparation, the raw-HTML list relinquishes each reference
as its prepared page is stored, reducing simultaneous raw/prepared copies.
**The viewer still retains prepared chapter HTML for the whole book.**
Fully lazy chapter loading and reuse of parsed package metadata are separate
follow-ups; this patch does not claim bounded whole-book memory.

## Validation status

Regression sources cover decoded percent/CFI/SMIL references, focus denial,
background and repeated transient-focus loss, manual-pause behavior,
hash-collision cache names, corrupted same-size cache content, changed CRCs,
and metadata read boundaries.

The maintainer reported a successful 1.0.18 build before the later timeout-field
and translation changes; those follow-ups are not covered. Unit-test and device
results have not been reported; see [release readiness](RELEASE_READINESS_1_0_18.md).
Static checks do not establish runtime correctness. Device validation should
exercise background/focus changes during prepare/seek, paused page changes,
percent-bearing chapter/image/audio names, and large EPUB loading.
