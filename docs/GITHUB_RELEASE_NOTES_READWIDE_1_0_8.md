# Readwide 1.0.8 GitHub release notes

Readwide 1.0.8 is a hotfix release. It keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.7 and 1.0.6. It fixes a regression that could turn a document blank while you were reading it, and changes nothing else, while keeping the local-first privacy baseline unchanged.

## Highlights

- **Fixed: document turning blank while reading**: a large text or PDF document could suddenly become blank at random while it was open — sometimes right after opening, sometimes after reading for a while, and still blank after reopening — which made it hard to return to your place. Under system memory-pressure signals the reader was releasing its on-screen text even while the app was still in the foreground, and that content is only restored when you return to the app, so the page stayed blank and the reading position was lost. The reader now releases that memory only when the app is actually in the background; foreground memory pressure no longer clears the page. The fix applies to both the text reader and the PDF reader.
- **Local-first privacy baseline (unchanged)**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.8
versionCode 10008
applicationId com.readwide.manager
```

1.0.8 keeps the `com.readwide.manager` application ID introduced in 1.0.4 and the `readwide` release signing key introduced in 1.0.6, so it installs in place over 1.0.7 and 1.0.6.

## Migration for existing users

- Updating from 1.0.6 or 1.0.7: 1.0.8 uses the same signing key, so it installs in place as a normal update; no uninstall or backup step is required.
- Updating from 1.0.4/1.0.5: those used the previous signing key, so Android will not install 1.0.8 over them (signature mismatch). Uninstall the previous version, install 1.0.8, then import a JSON backup to restore bookmarks, reading positions, themes, and settings.
- Coming from TextView Reader or an older Readwide build with the `com.textview.reader` application ID: export a backup (`readwide_backup_<timestamp>.json`) from the old app, install 1.0.8, then import the backup to restore bookmarks, reading positions, themes, and settings.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- TXT keeps the exact source-page reader model; Markdown, EPUB, PDF, Word, and HWP/HWPX page counts remain viewer/layout dependent.
- DOCX/HWP/HWPX document rendering targets content-fidelity HTML preview. Exact pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.
- HWP/HWPX is read-only. RAR/CBR support is limited and backend/scoped-path dependent.

See:

- `docs/FDROID_SUBMISSION.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.8` Git tag referenced by `fdroid/metadata/com.readwide.manager.yml`.
