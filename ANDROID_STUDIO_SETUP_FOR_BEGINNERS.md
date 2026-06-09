# TextView Reader - Android Studio Beginner Setup

TextView Reader is a Java Android reader for TXT, PDF, EPUB, Word, image, and archive workflows. It includes local file browsing, bookmarks, reader themes, custom fonts, image sequence viewing, and viewer-specific layout controls.

## 1. Open the project

1. Unzip this folder.
2. Open Android Studio.
3. Click **Open**.
4. Select the unzipped project root folder, not the `app/` folder.
   - Correct: the extracted project root folder that contains `app/`, `gradle/`, `README.md`, and `settings.gradle`
   - Wrong: the nested `app/` folder
5. Wait for **Gradle Sync**.

## 2. Install missing SDK if Android Studio asks

The project uses:

- compileSdk 35
- targetSdk 35
- minSdk 24

If Android Studio says SDK Platform 35 is missing, click the install/fix link.

## 3. Build a debug APK

Use:

- **Build > Make Project**

Then:

- **Build > Build Bundle(s) / APK(s) > Build APK(s)**

The debug APK should appear under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 4. Run on your phone

1. Enable Developer Options on the phone.
2. Enable USB Debugging.
3. Connect by USB.
4. In Android Studio, choose your phone from the device dropdown.
5. Press the green Run button.

## 5. First test checklist

After the app installs:

1. Give storage/all-files permission if prompted.
2. Open a `.txt` file.
3. Drag-scroll normally.
4. Use the TXT bottom toolbar: Find, Go to Page/Position, Bookmarks, Settings, and More.
5. Add a TXT bookmark, then close and reopen the file to confirm the saved position restores.
6. In the TXT viewer, use **More > Add display rule** on a disposable TXT file and confirm the visible text changes after the rule window closes.
7. In Settings opened from that TXT viewer, check **TXT Display Rules** and confirm the rule appears with its enabled/scope/case/regex options.
8. Only with a disposable TXT file, test **Edit Actual TXT File** once in copy mode and confirm it creates or overwrites the same `*_edited.txt` copy.
9. Open a PDF and verify horizontal/vertical reading mode behavior.
10. Open a ZIP/CBZ archive and verify archive browsing, selected-image-first viewing, next/previous lazy image loading, and zoom-to-detail behavior if you changed archive/image code.
11. Open an EPUB and verify Settings > EPUB layout changes apply after returning to the viewer.
12. Change the reader theme from Settings while an EPUB is open and confirm the page refreshes to the new theme.
13. Export and import a backup if you need to verify bookmarks/settings backup behavior.

## 6. Current notes

- All reader data is local unless the user manually exports, shares, or backs it up.
- Backup JSON can include bookmarks, reading positions, app settings, layout settings, TXT display rules, and custom reading themes.
- Lock PIN data is intentionally excluded from backup JSON because that format is plain text.
- The optional PIN lock is a convenience lock, not a substitute for Android device security.
- Android storage behavior can vary by device and Android version, especially around all-files access and the Storage Access Framework.
- **Edit Actual TXT File** can overwrite real TXT content. Test that feature only with disposable files or copy mode.

## 7. If Gradle fails

Copy the exact first red error line and send it back. Common first failures are:

- Missing SDK Platform 35.
- Android Gradle plugin sync/update prompt.
- Wrong JDK selected; use JDK 17.
- Network unavailable while Gradle is downloading dependencies.
- Resource compile error from an edited XML file.

Do not randomly change files before capturing the first error.


## 2.2.6 quick manual QA

- Multi-select delete progress: select multiple files/folders, delete, pause, send the progress window to Background, and confirm the toolbar progress button immediately reopens it.

- Open and close the left drawer with gestures: right-swipe should open it from the main browser area, and left-swipe from the outside area should close it only when the drawer is open or partially open. A light outside tap should not close it.
- Tap the drawer bottom **Open File**, **Bookmarks**, and **Settings** actions and confirm each action starts immediately while the drawer closes behind it.
- Open RAR/CBR, ALZ, EGG, ZIP/CBZ, ZIPX, Deflate64/BZip2 ZIP fallback fixtures, and standard `.7z.001` / `.7z.002` split fixtures that match the documented support matrix. Confirm unsupported variants show clear unsupported-feature messages instead of partial output.
- Verify pending copy/move/extract/compress tasks can be inspected while an operation is backgrounded, without allowing a second active worker to start.
- In Settings, open a custom main-theme or reading-theme color field and confirm the shader-based palette changes preview color and saves the selected HEX value.
- In the TXT viewer, confirm the page label remains `current / total` at exact page starts and becomes `current (line-in-page) / total` only when the visible position is mid-page.
- Run optional external archive fixture tests with `TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR` when sample archives are available.

## 2.2.2 quick manual QA

- Open a large ZIP/CBZ image archive: the selected image should appear before the whole archive is extracted, nearby pages should load lazily/prefetch, and zoom should request higher detail using the 12MP preview / 48MP detail policy.
- Change folder sort order in a large folder: the currently loaded list should reorder without a long full reload.
- Open Settings > Button / icon order: main filter and TXT / EPUB-Word / PDF orders should save, reset, and apply after returning.
- On the recent-files home screen, the IMG filter chip should be hidden; in normal browse/search mode it should reappear.
- Short feedback toasts should be readable but brief, using the app-wide roughly 700ms window.
