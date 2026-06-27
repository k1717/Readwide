# Readwide 1.0.8

Readwide 1.0.8 is a hotfix over 1.0.7. It fixes a regression that could turn a document blank while you were reading it, and changes nothing else. Android metadata is `versionCode 10008` / `versionName "1.0.8"`.

## Fixes

- **Blank document while reading**: a large text or PDF document could suddenly become blank at random while it was open — sometimes right after opening, sometimes after reading for a while, and again after reopening, which made it hard to return to your place. Under system memory-pressure signals the reader was releasing its on-screen text even while the app was still in the foreground, and that content is only restored when you return to the app, so the page stayed blank and the reading position was lost. The reader now releases that memory only when the app is actually in the background; foreground memory pressure no longer clears the page. The fix applies to both the text reader and the PDF reader.

## Install / update

- 1.0.8 keeps the `com.readwide.manager` applicationId and the `readwide` release signing key from 1.0.6/1.0.7, so it updates in place over 1.0.7 and 1.0.6.
- Updating from 1.0.4/1.0.5 (a previous signing key) still requires uninstalling first, then migrating with the in-app JSON backup export/import.
- The F-Droid build is signed by F-Droid and does not update over a GitHub-signed APK. When switching channels, export a JSON backup first, install the target build fresh, then import the backup.

## Verifying the APK signature

```
apksigner verify --print-certs Readwide-1.0.8.apk
```

Expected signing certificate:

```
CN=k1717, OU=Unknown, O=Readwide
```

## Privacy baseline (unchanged)

No default `INTERNET` permission, no ads, no analytics or telemetry SDKs, no Firebase or Google Play Services, no account system, no cloud sync, and Android Auto Backup disabled.
