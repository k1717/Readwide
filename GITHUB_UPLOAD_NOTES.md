# Readwide 1.0.0 GitHub Upload Notes

Use this package as the public GitHub source upload for Readwide 1.0.0.

## Release Metadata

- App name: Readwide
- Version: 1.0.0
- Android versionCode: 10000
- Android package/application ID: unchanged from TextView Reader
- Update link used by Settings: `https://github.com/k1717/Readwide/releases`
- Developer contact: `readwide.kj7w5@addy.io`

## Compatibility Note

Readwide 1.0.0 continues from TextView Reader 2.2.6. The visible app name changed, but the Android `applicationId` remains unchanged so an APK signed with the same key can update an installed TextView 2.2.6 build.

## Suggested GitHub Release Title

`Readwide 1.0.0`

## Suggested GitHub Release Summary

Readwide 1.0.0 is the public successor to TextView Reader 2.2.6. This release keeps the same Android package identity for update compatibility while switching the visible app name, launcher assets, public docs, backup wording, Settings labels, and developer-contact information to Readwide.

The release keeps the 2.2.6 privacy/FOSS base: no default `INTERNET` permission, no analytics, no ads, no account system, Android Auto Backup disabled, and no Junrar/UnRAR-license fallback code in the default build.

## Included Highlights

- Readwide branding and icon assets.
- Static Settings update link for the new Readwide GitHub release page.
- Main language selector converted to a compact picker dialog.
- Expanded major-language choices with first-pass translated resources.
- Recent-file multi-select labels can wrap instead of clipping in English.
- Custom reading theme editor respects status-bar and display-cutout insets.
- Archive preview/image-sequence behavior from the late TextView 2.2.6 line is retained.
- Public docs are result-focused and no longer list internal pass-by-pass development logs.
- Launcher icon provenance is documented as project-owned generated artwork in `THIRD_PARTY_NOTICES.md` and `docs/ASSET_PROVENANCE.md`.

## Archive Claims To Keep Conservative

- ZIP/CBZ: Zip4j primary, Commons Compress fallback for non-encrypted special methods.
- 7z/CB7 and TAR-family: Commons Compress paths.
- ALZ/EGG: limited first-party paths with unsupported encrypted/split/solid variants.
- RAR/CBR: libarchive-android for common compressed attempts plus first-party Java metadata/stored-entry support.
- RAR split/multi-volume and encrypted RAR were not re-tested for this package and must not be advertised as guaranteed.
- RAR solid, PPMd, VM-filtered, broad SFX, and RAR5 compressed/solid/encrypted-header variants remain backend-dependent or unsupported.
- RAR creation is not supported.

## Privacy And License Checklist

- Include `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, and `PRIVACY.md`.
- Do not include private keystores, local SDK paths, APK outputs, IDE folders, build output, scratch logs, or generated internal pass reports.
- The default source package should not include optional decoder jars under `app/libs`.
- If any local jar is added later, re-audit the custom build before describing it as FOSS.
- Create public ZIPs with POSIX-style `/` entry separators so Linux/macOS/GitHub tooling sees normal directory paths.
- Preserve executable permissions for `gradlew` and `scripts/clean_removed_sources.sh` before tagging or zipping.
- Keep `fdroid/metadata/com.textview.reader.yml`, `docs/FDROID_SUBMISSION.md`, and `fastlane/metadata/android/` with the public source package.

## Upload Checklist

- Confirm `assembleDebug --offline` succeeds.
- Confirm `assembleRelease --offline` succeeds without signing environment variables and produces an unsigned release artifact for source-build review.
- Confirm local release APK signing separately with `build-release.ps1` and the private release keystore.
- Run `git update-index --chmod=+x gradlew` and `git update-index --chmod=+x scripts/clean_removed_sources.sh` before the release tag.
- For an F-Droid Data merge request, copy `fdroid/metadata/com.textview.reader.yml` into fdroiddata and replace the placeholder commit with the final immutable Git commit hash.
- Publish the minimal source ZIP, not a working directory dump.
- Mention that Readwide 1.0.0 is update-compatible with TextView Reader 2.2.6 when signed with the same key.
