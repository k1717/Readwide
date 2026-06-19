# Asset Provenance

## Readwide Launcher Artwork

The Readwide launcher artwork source and checked-in Android launcher icon resources are project-owned artwork supplied by the project maintainer for the Readwide 1.0.x release line.

- Source reference: `docs/readwide_launcher_icon_source.png` (project-supplied Readwide book icon artwork; updated for the 1.0.5 public documentation package)
- Checked-in Android assets: existing `app/src/main/res/mipmap-*` launcher/adaptive PNG resources and `app/src/main/ic_launcher-playstore.png`
- Scope: app launcher, round launcher, adaptive foreground, and Play Store style icon material
- Third-party dependency: none required for this artwork

The source reference and checked-in launcher assets are treated as project-owned release assets. For 1.0.5 the launcher icon was re-derived from the project-owned source artwork: the book was re-centered (raised slightly for visual balance) and enlarged, composited onto the existing blue gradient, and regenerated across all `mipmap-*` densities as legacy, round, adaptive-foreground, and adaptive-background PNGs. No external stock image, third-party logo, or copied upstream icon path is required for the Readwide launcher artwork.

## UI Vector Icons

The project also contains local vector drawable icons for ordinary UI actions such as search, settings, bookmarks, sorting, file actions, and navigation, plus per-type file icons (`ic_file_*`). These are treated separately from the Readwide launcher artwork. Several of them — including the per-type file icons — are glyph paths adapted from Google's Material Symbols / Material Icons, which are licensed under the Apache License 2.0; that attribution is recorded in `THIRD_PARTY_NOTICES.md`. Any further Material Symbols / Material Icons paths added later are covered by the same attribution.

## RAR Test Fixtures

Unit tests embed several tiny `.rar`/`.cbr` fixture archives (and reference more via an external fixture root). These originate from the "RAR Test Files" collection by Stephan Sokolow (https://github.com/ssokolow/rar-test-files). The author created the archived contents from scratch and dedicated everything they hold copyright to into the public domain (CC0 1.0 Universal). Self-extractor (SFX) stub variants from that collection are not embedded in this repository.
