# Readwide 1.0.22

Readwide 1.0.22 adds folder access for storage-provider file operations, adjusts image-viewer controls, and corrects TXT recovery, saved-data handling and archive decoding.

## Storage access and file operations

- Open Settings > Security and folder permissions > Folder access to manage registered folders. Add a folder through Android's picker, open a saved entry or revoke its permission without deleting files. The list, actions and confirmation share one rounded popup without repeated background dimming. Existing registration carries over, and adding folders keeps earlier permissions. Revocation is blocked while the folder is in use. Internal Storage, External Storage and Downloads shortcuts remain available, without a separate Folder access sidebar entry.
- Copy, move, rename, delete and create folders in the authorized-folder browser. New copies do not overwrite existing names. Provider-native moves are used where available; fallback folder or multi-parent moves retain the original alongside the verified copy for inspection.
- Offer folder access after failed file operations on shared internal storage and detected SD/USB volumes. Failed image rename/delete also offers it when the same local file remains selected and unchanged. Authorization never repeats an earlier operation automatically; choose the item again in the authorized folder.
- Check source identity, copied bytes, destination contents and current provider capabilities before completing transfers or removing originals. Detected replacement, relocation or destination loss stops unsafe removal. Partial-move warnings ask for both locations to be checked before retrying.
- Keep provider operations and results through rotation, retain permissions while they are in use, and keep failed or cancelled paste selections available for retry. Closed or replaced confirmation windows cannot act on an old selection.
- Detect additional mounted SD/USB volumes through Android's volume list on Android 11 and later, while retaining discovery paths for older devices. USB drives, external SSDs and HDDs depend on Android exposing a mounted filesystem or document provider.
- Stop rebuilding the sidebar when it opens or rebinding unchanged rows. Refresh storage discovery when returning to the app and retain explicit theme updates.
- End failed folder loads and clear partial results so refreshing or returning can retry. Ignore older failures and validation results after a new load starts, including when reopening the same folder.
- Cancel obsolete authorized-folder queries and discard older queued requests. Retry reloads a failed folder listing without repeating copy, move, rename or delete. Providers that ignore cancellation can still delay the next listing.

Folder access does not bypass Android or provider restrictions. Huawei/API 28 behavior, removable-drive disconnects and picker return still need device checks. Concurrent changes after the final check and recovery from process death are not covered by an atomic file transaction.

## Image viewing

- Fit the displayed image between the toolbar and bottom panel while controls are visible. Hiding the controls restores the full display area without decoding or replacing the image. Zoom and center are retained where bounds allow.
- Keep short images at the same vertical position when panels toggle, moving them only when a panel would cover them.
- Reduce the gaps around the filename, page counter and slider. Lower the page counter slightly while retaining cutout and system-navigation insets.
- Let taps in the bottom panel's empty app area use the existing previous/next-image zones. Limit page selection to the centered page label and keep the slider's full drag area.

## TXT navigation and saved data

- Release failed exact-page index requests so later requests can retry. Late results cannot replace a newer index. Rejected load/search work no longer leaves a pending operation or failed loading indicator indefinitely.
- After a blank or short row below a page start, a backward tap returns to that page's start before moving to the previous page. Forward navigation is unchanged.
- Preserve bookmark and reading-position stores after failed reads or invalid data. Load the complete store before allowing edits, backup export or import; missing files still start empty. Unreadable stores are retained, not repaired automatically.
- Keep exact page boundaries and counts, partition sizes and overlap, half-visible-row selection and existing bookmark coordinates unchanged.

## Settings and backup recovery

- Tighten backup-control spacing and the gap before the update link. Show the installed version beside App information.
- Use three aligned columns for tap profiles, allowing two lines and end ellipsis. Keep compact rounded rows, readable two-line folder entries and clearer spacing in the tap-zone preview.
- Group reading controls, button order, files/archives, security/folder permissions and backup/reset separately. View settings groups text size, TXT/EPUB layout and reader display; detailed help and TXT partition/overlap options open in rounded dialogs.
- Set tap zones per viewer under Reading and controls: follow TXT or choose separate orientation and proportions with a preview. Documents keep their existing TXT inheritance, and images start with their original 33/34/33 zones. Custom values survive switching modes and are included in backup/reset. Page calculations and bookmarks are unchanged.
- Keep the saved archive-viewer timeout after reset/import and screen recreation. Unsaved custom-color drafts retain their existing restoration behavior.
- Attempt preference rollback after memory or stack errors during reset or direct settings import. Failed resets keep the confirmation open. Backup work rejected before startup cannot run later, and a started import keeps its lock through commit or rollback.
- Show EPUB custom-font discovery errors alongside the built-in choices. Reopening the picker retries discovery; stale callbacks cannot replace a newer selection.

## Archive compatibility

- Correct EGG AZO's long-history decoding. Six public AZO archives now match Bandizip's extracted filenames and bytes, including four that previously failed.
- Accept the observed EGG encryption-header lengths in retained ALZip samples while preserving password, authentication and output checks. Report rejected EGG/ALZ ZipCrypto passwords through the password-retry flow.
- Correct encrypted RAR3/RAR4 header framing so each header reads its own salt. Validate supported RAR4 header CRC boundaries, retain full-size service-block offsets and reject overflowing RAR5 header integers.
- Preserve password requests through RAR forward-reader selection and allow retry for invalid encrypted Huffman data after cleanup. Missing volumes and truncated payloads remain integrity failures rather than unsupported-format results.
- Allow unsplit AES-stored RAR3/RAR4 members carrying a solid flag. Skip plain and AES-stored members without temporary decoded output while retaining size, split/final checksum and cancellation checks.
- Reuse parsed supplemental 7z headers, buffer packed checksum work and remove a redundant AZO input copy. Close split-7z input and allocated Deflate resources when construction fails. These changes do not establish a measured speedup.
- Recheck archive sources before decoder retry and output acceptance, including split volumes. Detectable source changes stop extraction or ZIP creation and retain existing-output protection.
- Reject nonempty ALZ and non-solid EGG directory payloads instead of silently discarding them. Valid EGG solid state and empty entries retain their existing handling.

Retained ALZip encrypted split samples and public RAR, solid EGG and 7z PPMd/BCJ2 samples passed scoped host comparisons. Encrypted EGG filenames remain unsupported. Complex encrypted/split/solid/VM combinations and encrypted solid AZO still lack complete real-producer validation. See [archive support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md) for the tested combinations and remaining limits.

## Privacy and dependencies

- No new Android permission or runtime dependency. Signing configuration is unchanged.
- No ads, analytics, account, cloud sync, telemetry or `INTERNET` permission. Android Auto Backup remains disabled.
- Documents are processed locally. Archive previews can still create local plaintext temporary files; these changes do not encrypt every cache or extracted file.
- First-party source remains Apache License 2.0. Bundled components retain their own licenses, corresponding source and notices.

## Install

- Version name: `1.0.22`
- Version code: `10022`
- Release document date: `2026-11-08`
- Android application ID: `com.readwide.manager`
- Source license: Apache License 2.0
- Source: https://github.com/k1717/Readwide
- APK releases: https://github.com/k1717/Readwide/releases
- Public APK filename: `Readwide_1.0.22.apk`. Local build filenames may differ.
- Updates over 1.0.6 and later require the same project signing key.
- Scoped host checks and isolated compilation do not establish a complete APK build or device validation. Manual bookmark-save feedback and continuous-PDF error recovery remain open. See [release readiness](RELEASE_READINESS_1_0_22.md) before APK publication.

Implementation details and verification results are in the [development notes](DEV_CHANGES_1_0_22.md) and [current source status](CURRENT_SOURCE_STATUS_1_0_22.md).
