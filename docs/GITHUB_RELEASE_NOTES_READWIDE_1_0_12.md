# Readwide 1.0.12 GitHub release notes

Readwide 1.0.12 is a small patch release on top of 1.0.11, focused on read-aloud (text-to-speech). It fixes a crash when starting read-aloud in the PDF viewer, makes the strongest pause-reduction level also mute quotation marks for smoother dialogue on neural voices, and adds logcat diagnostics so read-aloud failure reports can be triaged from release builds. It keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. The local-first privacy baseline is unchanged, and this release adds no new dependency.

## Fixes

- **PDF read-aloud no longer crashes on start.** Starting read-aloud in the PDF viewer could crash the app (or close the viewer) when PDF search had never been opened first: the text extractor relied on the search feature having initialized the bundled PDF library. The extractor now initializes the library itself, extraction failures are contained instead of taking the process down (a PDF that genuinely has no readable text shows the existing "no selectable text" message), and a failed preparation no longer leaves the read-aloud entry point stuck at "Preparing read-aloud...".

## Improvements

- **Quieter dialogue at Aggressive pause reduction.** The Aggressive pause-reduction level now also mutes quotation marks (straight and curly double quotes, guillemets, and CJK corner brackets), so dialogue-heavy text keeps moving on neural voices that pause at every quote. Off and Medium leave quotation marks untouched, since they carry meaning in fiction. Apostrophes and single quotes are never removed, so contractions read correctly.
- **Read-aloud diagnostics.** Read-aloud now writes concise diagnostic lines to logcat under the `ReadwideTts` tag (engine initialization, language/voice selection results including fallbacks, queue sizes, and any engine failures). This has no user-visible effect; it exists so that "read-aloud is silent" or "read-aloud stopped" reports can be diagnosed with `adb logcat -s ReadwideTts` on any build.

## Version metadata

```text
versionName 1.0.12
versionCode 10012
applicationId com.readwide.manager
```

## Update path

Installs in place over 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6 (same application ID and signing key). No data migration is involved.

## Privacy baseline (unchanged)

No default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Dependencies

Identical to 1.0.11 - no dependency was added or updated. See `docs/LICENSE_REPORT_READWIDE_1_0_12.md` and `docs/SBOM_READWIDE_1_0_12.spdx.json` (same packages as 1.0.11 with only the version stamp changed).
