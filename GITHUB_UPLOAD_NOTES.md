# Readwide 1.0.1 GitHub Upload Notes

Use this package as the public GitHub source upload for Readwide 1.0.1.

## Release Metadata

- App name: Readwide
- Version: 1.0.1
- Android versionCode: 10001
- Android package/application ID: `com.textview.reader`
- Update link used by Settings: `https://github.com/k1717/Readwide/releases`
- Developer contact: `readwide.kj7w5@addy.io`

## Compatibility Note

Readwide 1.0.1 is the current public package. The Android `applicationId` remains `com.textview.reader`, so an APK signed with the same key can update earlier compatible builds.

## Suggested GitHub Release Title

`Readwide 1.0.1`

## Suggested GitHub Release Summary

Readwide 1.0.1 improves viewer behavior, backup/bookmark portability, archive handling, lifecycle cleanup, and public GitHub/F-Droid packaging hygiene while keeping the same Android package identity for update compatibility.

The release keeps the privacy/FOSS base: no default `INTERNET` permission, no analytics, no ads, no account system, Android Auto Backup disabled, and no Junrar/UnRAR-license fallback code in the default build.

## Included Highlights

- Missing bookmark target files stay visible with a theme-matched missing-file label and explanation dialog while remaining saved for later portable rebind.
- Backup import restores last directory, recent folders, and drawer folder shortcuts only for directories that exist on the current device.
- TXT bookmark Page X/Y values are treated as layout-dependent cache after import/migration; character position, logical line, anchor text, and file fingerprint remain the stable target.
- Zoomed PDF pages support inertial fling panning, while original-scale swipes keep their page-turn behavior.
- Image viewer landscape mode respects Android 3-button navigation safe areas.
- Image viewer default fit is adaptive: wide images fit to width and tall images fit to height.
- Image viewer keeps successfully decoded detail/original bitmaps after returning from zoom.
- Archive-backed image viewer recent/saved-position reopen paths were hardened around deferred image sequence metadata.
- Legacy ZIP, ALZ, and EGG archive entry names use best-effort automatic encoding detection where raw name bytes are available.
- Password-protected archive image preview uses selected-image-first lazy extraction after password entry.
- Password-sensitive archive preview cache reuse validates the current password before trusting cached output.
- ALZ/EGG extraction streams supported large entries/blocks where possible to reduce peak memory use.
- Archive failures show clearer support-boundary messages and distinguish bad password, unsupported feature, and corrupt/incomplete archive cases.
- External archive fixture report tooling is included for local QA without broadening compatibility claims.
- Public docs are result-focused and use Readwide 1.0.1 release-facing archive/RAR/license/SBOM filenames.

## Archive Claims To Keep Conservative

- ZIP/CBZ: Zip4j primary, Commons Compress fallback for non-encrypted special methods.
- 7z/CB7 and TAR-family: Commons Compress paths.
- ALZ/EGG: limited first-party paths with unsupported encrypted/split/solid variants.
- RAR/CBR: libarchive-android for common compressed attempts plus first-party Java metadata/stored-entry support.
- RAR split/multi-volume and encrypted RAR must not be advertised as guaranteed.
- RAR fixture reports in `docs/RAR_FIXTURE_QA.md` are QA evidence only and should not be used to claim complete RAR support.
- RAR solid, PPMd, VM-filtered, broad SFX, and RAR5 compressed/solid/encrypted-header variants remain backend-dependent or unsupported.
- RAR creation is not supported.

## Privacy And License Checklist

- Include `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, and `PRIVACY.md`.
- Do not include private keystores, local SDK paths, APK outputs, IDE folders, build output, scratch logs, or generated temporary reports.
- The default source package should not include optional decoder jars under `app/libs`.
- If any local jar is added later, re-audit the custom build before describing it as FOSS.
- Create public ZIPs with POSIX-style `/` entry separators so Linux/macOS/GitHub tooling sees normal directory paths.
- Preserve executable permissions for `gradlew`, `scripts/clean_removed_sources.sh`, `scripts/generate_rar_fixture_reports.sh`, and `scripts/generate_archive_fixture_reports.sh` before tagging or zipping.
- Keep `fdroid/metadata/com.textview.reader.yml`, `docs/FDROID_SUBMISSION.md`, and `fastlane/metadata/android/` with the public source package.

## Upload Checklist

- Confirm `assembleDebug --offline` succeeds.
- Confirm `testDebugUnitTest --offline` succeeds.
- Confirm `assembleRelease --offline` succeeds without signing environment variables and produces an unsigned release artifact for source-build review.
- Confirm local release APK signing separately with `build-release.ps1` and the private release keystore.
- Run `git update-index --chmod=+x gradlew`, `git update-index --chmod=+x scripts/clean_removed_sources.sh`, `git update-index --chmod=+x scripts/generate_rar_fixture_reports.sh`, and `git update-index --chmod=+x scripts/generate_archive_fixture_reports.sh` before the release tag.
- For an F-Droid Data merge request, copy `fdroid/metadata/com.textview.reader.yml` into fdroiddata and replace the placeholder commit with the final immutable Git commit hash.
- Publish the minimal source ZIP, not a working directory dump.
- Mention that Readwide 1.0.1 is update-compatible with earlier compatible builds when signed with the same key.
