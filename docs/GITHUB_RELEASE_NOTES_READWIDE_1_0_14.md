# Readwide 1.0.14 GitHub release notes

Readwide 1.0.14 adds automatic two-page landscape viewing for EPUB and PDF readers on both phones and tablets. Portrait orientation remains single-page. PDF vertical continuous mode is unchanged. It keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.13 and earlier compatible Readwide builds.

## Highlights

- EPUB pages show as a side-by-side two-page spread in landscape orientation.
- PDF single-page mode renders a side-by-side two-page spread in landscape orientation.
- Phone landscape and tablet landscape both use the same spread rule.
- Page buttons, tap zones, and page-swipe gestures advance by one spread in landscape two-page mode.
- Direct page jumps still land on the requested page.
- PDF vertical continuous mode remains unchanged.

## Android metadata

versionName 1.0.14
versionCode 10014
applicationId com.readwide.manager

## Privacy baseline

Unchanged: no INTERNET permission, no ads, no analytics SDKs, no account system, no cloud sync, and no telemetry.

## Dependency / license baseline

Identical to 1.0.13 - no dependency was added or updated. See `docs/LICENSE_REPORT_READWIDE_1_0_14.md` and `docs/SBOM_READWIDE_1_0_14.spdx.json`.
