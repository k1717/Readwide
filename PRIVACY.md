# Privacy

Readwide, formerly TextView Reader, is designed as an offline local reader. The app does not include an account system, analytics, advertising, cloud sync, telemetry, or any developer-operated server upload path. The default manifest does not request the `INTERNET` permission.

## What the app does not include

- No analytics SDK.
- No advertising SDK.
- No account system.
- No cloud sync backend.
- No remote telemetry collection.
- No in-app network update check.
- No automatic developer contact or log upload.

## Android backup policy

The app manifest sets:

```xml
android:allowBackup="false"
```

This is intentional. App-private settings, reading metadata, recent-file metadata, bookmarks, display rules, cache bookkeeping, and the optional PIN state are not opted into Android Auto Backup by the app.

This does not stop actions outside the app, such as the user manually exporting settings, copying files, using a device/OEM transfer tool, using rooted-device tools, or backing up the whole device through another mechanism. Exported backup files and copied app data should still be treated as user data.

## Data stored locally

The app may store local data needed for reading and file-browser behavior:

- recent files and recent folders;
- user-added folder shortcuts;
- reading positions;
- bookmarks and bookmark labels;
- TXT/Markdown notes, selected excerpts, highlight ranges, and source-position anchors stored separately from the original document;
- reader, toolbar, sort, search, and view settings;
- theme settings and custom reading themes;
- optional imported fonts;
- optional PIN-lock enabled state and salted PIN verifier;
- PDF reading-mode preference;
- the last reader search query and reader search option states, used only to prefill the search dialog locally;
- saved TXT display rules, including rule text, scope, enabled state, case-sensitivity setting, regex setting, ordering, and source-file labels/paths used for current-file-only rules;
- disposable TXT page/index cache metadata for large-file handling;
- temporary extracted archive entries used when opening files from archives, including a separate shorter-lived sensitive cache for password-protected archive previews;
- local audio extracted from an opened EPUB into app-private cache when the user starts publisher-provided media-overlay narration. Each extracted audio resource is limited to 256 MB and remains disposable cache data.

This data is used locally by the app. It is not uploaded by Readwide.

EPUB media-overlay playback is foreground-only. Readwide reads the linked audio
from the local EPUB, places the needed resource in app-private cache, and plays
it through Android's local media APIs. It does not upload the audio or fetch a
remote narration track. The cached copy can be removed by Android or by clearing
the app cache.

## Folder shortcuts and file paths

Folder shortcuts and recent-file records may store local path strings selected or opened by the user. They are used only for app navigation, restoring reading state, and showing faster drawer/recent entries. Removing a shortcut or recent entry removes the app metadata; it does not delete the underlying user folder or file.

## Archive browsing and extraction

Opening a file inside an archive may temporarily extract that selected entry into app cache so the appropriate viewer can read it. Long-press archive extraction writes files only after the user chooses a destination and confirms the extraction/conflict choice. Temporary archive cache data is disposable and is not a cloud upload or network transfer. Password-protected archive previews use a separate app-private sensitive preview cache with shorter/smaller pruning limits, because previewing such archives necessarily creates temporary decoded files for the image/PDF/TXT viewers.

The archive image viewer also offers an explicit **Save** action for the current image page. The user chooses either the public Downloads collection or a destination through Android's document picker. Readwide copies the already extracted original image bytes locally without recompression or upload; the resulting saved image is ordinary user-visible data and is no longer disposable app cache.

## Opening or sharing files with other apps

Some file types that Readwide does not render internally, such as video and audio files, may be handed to another app through Android's normal open-with / viewer intent flow. Sharing also uses Android's normal user-triggered share flow.

For these flows, Readwide grants the chosen external app temporary read access to the selected file URI through Android `FileProvider`. Readwide does not upload the file itself. The receiving app decides what it does with the file after the user chooses it.

Readwide also intentionally accepts inbound open requests: it registers an exported `ACTION_VIEW` intent filter, including the `BROWSABLE` category, so a document can be opened in Readwide from a browser, messenger, file manager, or document provider. When a file is opened this way through a `content://` URI, Readwide copies it into an app-private cache (`opened_files`) before rendering. That copy applies display-name sanitization, a canonical-path containment check so the cached file cannot escape the cache directory, a per-file copy limit of 2 GB, and cache pruning before and after the copy (the just-opened file is preserved so a legitimate large document can still be read). The source file is read locally; nothing is uploaded.

## APK installation behavior

The default build does not request `REQUEST_INSTALL_PACKAGES` and does not route APK files into Android's package-installer flow. This avoids treating Readwide as an APK installer or app-update mechanism.

## TXT display rules and actual-file editing

TXT display rules are stored locally and may be included in user-triggered settings backup/import. Normal display rules change only the viewer output and do not edit the source TXT file.

The separate **Edit Actual TXT File** action is user-triggered and can write changed text into local storage. Original mode overwrites the current TXT file. Copy mode writes to `*_edited.txt` and overwrites that edited copy if it already exists. These file writes happen only after explicit confirmation in the app. The app writes through a temporary file in the same folder before replacing the target, but the user should still treat original-file editing as destructive because there is no internal undo.

## Bookmark/settings export and import

Backup/export uses JSON. The exported JSON can include file paths, file names, reading positions, bookmark labels, annotation notes/highlight excerpts and positions, app settings, layout settings, display rules, and custom reading themes. Treat exported backup files as user data. Backup import accepts JSON files up to 256 MB, which is generous for large reading-history and bookmark exports; oversized input is rejected rather than partially imported.

Imported reader font files are stored only in app-private storage (see "Data stored locally") and are not included in the JSON backup. The backup may record the selected font name, but not the font file itself or the imported-font list, so an imported font must be re-imported after reinstalling the app or switching distribution channels.

Lock PIN data is intentionally excluded from the plain JSON backup. New PIN values are stored as salted PBKDF2 verifier strings rather than as a plain PIN. Legacy plain-PIN preferences from older installs are migrated to the verifier format after the first successful PIN verification. The optional PIN lock is still only an app-level convenience lock and is not a substitute for Android device encryption, lock-screen security, or secure storage of sensitive documents.

## Manual update link

Settings shows the static line `Check updates at https://github.com/k1717/Readwide/releases`. Tapping that line copies the release URL to the clipboard. The app does not call the GitHub API, does not perform in-app update checks, and does not run background update checks.

## Developer contact button

Settings includes a **Contact developer** button for `readwide.kj7w5@addy.io`. Tapping it opens the user's installed mail app through a `mailto:` intent with that address and a default subject. Readwide does not send email itself, does not collect the user's email address, and does not transmit logs, files, settings, bookmarks, or reading history automatically. Any message content, attachments, sender address, and network transmission are controlled by the user's chosen mail app.

If no mail app is available, the app copies the contact address to the clipboard so the user can paste it elsewhere.

## File permissions

The app requests storage access so it can open local documents selected by the user and act as a local file browser. On Android versions that require scoped-storage handling, the app may request broader storage access for file-browser behavior. These permissions are for local file access; they are not paired with an app network upload path.

As a compatibility alternative, the user can select a folder through Android's Storage Access Framework. Android grants Readwide access only to that selected provider tree, and Readwide persists the grant so the folder remains available after restart. The stored value is a local content-provider URI and display label; it is not transmitted. Choosing this path is sufficient for the read-oriented SAF browser and avoids repeat broad-storage permission prompts.

## Broad file access and FileProvider scope

The default manifest requests `MANAGE_EXTERNAL_STORAGE` for local file-browser behavior on Android versions where broad file browsing cannot be implemented with older storage permissions alone. This gives the app broad local-file visibility when the user grants that permission. Readwide uses that access for local browsing, opening, copying, moving, deleting, extracting, compressing, and reading files selected through the app. It is not paired with an `INTERNET` permission or developer-operated upload path.

The `FileProvider` path configuration includes app-private files/cache, external app files/cache, and a broad `external-path` entry. The broad entry is used so explicit user actions such as **Open with** or **Share** can grant another selected app temporary read access to a file chosen from normal external storage. The provider is `exported=false`; access is granted through Android's URI permission mechanism for the selected intent target. After the handoff, the receiving app controls its own copy/access behavior.

## Generated cache data

Disposable TXT page/index cache bookkeeping, temporary archive-entry extraction
data, and locally extracted EPUB media-overlay audio are generated only under app
cache storage. Cache cleanup must not remove bookmarks, reading history, saved
reading position, folder shortcuts, or user documents.


## Sensitive archive preview cache

Password-protected archive images may be temporarily extracted to the app-private sensitive preview cache for viewing. Readwide now validates the currently supplied password against the requested archive entry before reusing a cached sensitive preview file, stores archive-image handoff passwords only in memory, and clears password character arrays when the archive/image viewer flow is closed.
