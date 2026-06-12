# Single-entry archive extraction failure boundary — Readwide 1.0.2

Readwide uses single-entry extraction for archive preview, internal document opening, and comic/image cache paths.

archive decoder note 220 tightens the failure boundary: when a single-entry extraction request returns `false` instead of throwing, the requested output file is removed before returning failure. This prevents stale or partial output from remaining after a missing entry, unsupported entry, cancelled stream path, or failed decoder handoff.

This does not expand format support. It only prevents failed extraction attempts from being mistaken for usable cached output.
