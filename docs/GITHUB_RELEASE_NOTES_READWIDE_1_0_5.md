# Readwide 1.0.5 GitHub release notes

Readwide 1.0.5 is an in-place update over 1.0.4 (same `com.readwide.manager` application ID). It centers on a text-to-speech overhaul, a folder-aware multi-select delete confirmation, two PDF mode-switch crash fixes, a text-rule performance fix, and internal code cleanup, while keeping the local-first privacy baseline unchanged.

## Highlights

- **Text-to-speech sleep timer**: Off / 15 / 30 / 45 / 60-minute presets plus a custom-minutes entry, counting playback time only (paused time does not accrue), with an optional "finish the current sentence" setting. The timer can be changed mid-playback and stops playback when it expires.
- **Real pause and resume**: pausing keeps the session and the current sentence, and resuming continues from that sentence instead of restarting from a saved position. Because Android's speech engine cannot pause mid-sentence, resume replays from the start of the interrupted sentence. A paused session is remembered if you leave the app.
- **Floating playback control**: a draggable play/pause/stop card over the text reader, kept in sync with the existing foreground-service notification, lock-screen, and Bluetooth/media-button controls.
- **Smarter audio policy**: TTS keeps reading while you scroll; a transient focus loss (such as a phone call) pauses and auto-resumes; a permanent loss stops playback; and unplugging headphones pauses (rather than stops) so playback can resume after reconnecting.
- **All-locale text-to-speech translations**: the new timer, pause/resume, and folder-warning strings are translated across the bundled locales, with English as the fallback.
- **Folder-aware multi-select delete**: the multi-select delete confirmation now warns when the selection includes folders, since all of their contents are deleted too. The warning appears only when at least one folder is selected; existing single-file and image-viewer delete confirmations are unchanged.
- **PDF crash fixes**: fixed an oversized-bitmap crash when switching from a zoomed continuous view into single-page mode, and a freed-bitmap crash when switching display modes.
- **Text-to-speech segmentation fix**: short pages now split by sentence, so resume no longer rewinds to the start of the page.
- **Recent shortcut responsiveness**: the navigation drawer's Recent list builds on a background thread, so the shortcut responds immediately.
- **Text-rule performance**: text display rules compile their regular expressions once per file load instead of once per line, so large files with active regex rules load faster.
- **Local-first privacy baseline (unchanged)**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.5
versionCode 10005
applicationId com.readwide.manager
```

1.0.5 keeps the `com.readwide.manager` application ID introduced in 1.0.4, so it installs as an in-place update over 1.0.4 when signed with the same key.

## Migration for existing users

- Updating from 1.0.4 (same signing key): install 1.0.5 over it; no data migration step is needed.
- Coming from TextView Reader or an older Readwide build with the `com.textview.reader` application ID: export a backup (`readwide_backup_<timestamp>.json`) from the old app, install 1.0.5, then import the backup to restore bookmarks, reading positions, themes, and settings.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- TXT keeps the exact source-page reader model; Markdown, EPUB, PDF, Word, and HWP/HWPX page counts remain viewer/layout dependent.
- DOCX/HWP/HWPX document rendering targets L3 content-fidelity HTML preview. Exact pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.
- Text-to-speech uses the on-device Android speech engine. Available languages, voices, and the lack of true mid-sentence pause depend on that engine.
- HWP/HWPX is read-only. RAR/CBR support is limited and backend/scoped-path dependent.

See:

- `docs/DOCUMENT_VIEWER_FIDELITY_MATRIX_READWIDE_1_0_3.md`
- `docs/DOCUMENT_VIEWER_FIDELITY_READWIDE_1_0_3.md`
- `docs/TXT_SEARCH_USAGE.md`
- `docs/FDROID_SUBMISSION.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.5` Git tag referenced by `fdroid/metadata/com.readwide.manager.yml`.
