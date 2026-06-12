# Readwide 1.0.2 public release checklist

## GitHub

- [ ] Tag final source commit as `v1.0.2`.
- [ ] Confirm `versionName "1.0.2"` and `versionCode 10002` in `app/build.gradle`.
- [ ] Run `./gradlew clean testDebugUnitTest assembleRelease` or Windows equivalent.
- [ ] Verify no private signing files, personal names, account identifiers, personal paths, or private fixtures are committed.
- [ ] Attach `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_2.md` content to the GitHub release.
- [ ] Include source archive and, if publishing APK, include license/notice files with the binary materials.

## F-Droid

- [ ] Copy `fdroid/metadata/com.textview.reader.yml` to `fdroiddata/metadata/com.textview.reader.yml`.
- [ ] Create and push the immutable `v1.0.2` release tag, and confirm the F-Droid metadata points to that tag or an equivalent exact commit hash.
- [ ] Confirm the metadata removes `gradle/wrapper/gradle-wrapper.jar`.
- [ ] Confirm the build works without private signing environment variables.
- [ ] Mention that `applicationId` remains `com.textview.reader` for update compatibility.
- [ ] Keep broad storage access rationale tied to local file browsing/reading.
- [ ] Keep RAR and HWP/HWPX support wording conservative.

## Required public wording

Use:

- local-first reader and file browser;
- text-first HWP/HWPX reading;
- limited/scoped/backend-dependent archive support;
- no default network permission / no ads / no analytics / no account system.

Avoid:

- complete RAR support;
- encrypted RAR support;
- full HWP support;
- Hancom-compatible rendering;
- legacy DOC rendering support.
