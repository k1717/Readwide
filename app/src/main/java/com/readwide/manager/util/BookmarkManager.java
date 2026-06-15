package com.readwide.manager.util;

import android.content.Context;
import android.util.Log;

import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Manages bookmarks and reading states.
 *
 * Storage format: plain JSON files (no compression, easy to edit manually).
 *
 * bookmarks.json:
 * {
 *   "version": 1,
 *   "bookmarks": [ ... ]
 * }
 *
 * reading_states.json:
 * {
 *   "version": 1,
 *   "states": { "filePath": { ... }, ... }
 * }
 */
public class BookmarkManager {
    private static final String TAG = "BookmarkManager";
    private static final String BOOKMARKS_FILE = "bookmarks.json";
    private static final String STATES_FILE = "reading_states.json";
    private static final int FORMAT_VERSION = 8;
    private static final int QUICK_FINGERPRINT_SAMPLE_BYTES = 4096;

    private static BookmarkManager instance;
    private final Context context;
    private List<Bookmark> bookmarks;
    private Map<String, ReaderState> readingStates;

    private BookmarkManager(Context context) {
        this.context = context.getApplicationContext();
        loadBookmarks();
        loadReadingStates();
    }

    public static synchronized BookmarkManager getInstance(Context context) {
        if (instance == null) {
            instance = new BookmarkManager(context);
        }
        return instance;
    }

    // ========== Bookmark Operations ==========

    public List<Bookmark> getAllBookmarks() {
        return new ArrayList<>(bookmarks);
    }

    public List<Bookmark> getBookmarksForFile(String filePath) {
        List<Bookmark> result = collectBookmarksForExactPath(filePath);

        // Fast path above avoids any file I/O during normal use.  Portable matching
        // only runs when the local path/URI did not match, e.g., after importing a
        // backup on another device or moving the same file to a different folder.
        if (result.isEmpty() && bindPortableBookmarksToFile(filePath) > 0) {
            result = collectBookmarksForExactPath(filePath);
        }

        resolvePendingPcTextEditsForFile(filePath, result);

        // Sort by position
        Collections.sort(result, Comparator.comparingInt(Bookmark::getCharPosition));
        return result;
    }

    private List<Bookmark> collectBookmarksForExactPath(String filePath) {
        List<Bookmark> result = new ArrayList<>();
        if (filePath == null) return result;
        for (Bookmark b : bookmarks) {
            String path = b.getFilePath();
            if (filePath.equals(path)) {
                result.add(b);
            }
        }
        return result;
    }

    public void addBookmark(Bookmark bookmark) {
        enrichPortableIdentity(bookmark);
        bookmarks.add(bookmark);
        saveBookmarks();
    }

    public void updateBookmark(Bookmark bookmark) {
        enrichPortableIdentity(bookmark);
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).getId().equals(bookmark.getId())) {
                bookmark.setUpdatedAt(System.currentTimeMillis());
                bookmarks.set(i, bookmark);
                saveBookmarks();
                return;
            }
        }
    }

    /**
     * Save automatic page-model metadata refreshes without changing the user's
     * visible bookmark edit/update date. This is used when TXT pagination changes
     * because the large-file partition mode changed, while the bookmarked passage
     * itself remains the same.
     */
    public void saveBookmarkPageMetadataRefresh(List<Bookmark> changedBookmarks) {
        if (changedBookmarks == null || changedBookmarks.isEmpty()) return;
        boolean changed = false;
        for (Bookmark changedBookmark : changedBookmarks) {
            if (changedBookmark == null || changedBookmark.getId() == null) continue;
            for (int i = 0; i < bookmarks.size(); i++) {
                Bookmark existing = bookmarks.get(i);
                if (existing != null && changedBookmark.getId().equals(existing.getId())) {
                    enrichPortableIdentity(changedBookmark);
                    bookmarks.set(i, changedBookmark);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) saveBookmarks();
    }

    public void deleteBookmark(String bookmarkId) {
        Iterator<Bookmark> it = bookmarks.iterator();
        while (it.hasNext()) {
            if (it.next().getId().equals(bookmarkId)) {
                it.remove();
                saveBookmarks();
                return;
            }
        }
    }

    public void deleteBookmarksForFile(String filePath) {
        Iterator<Bookmark> it = bookmarks.iterator();
        while (it.hasNext()) {
            Bookmark bookmark = it.next();
            String path = bookmark.getFilePath();
            if (filePath == null ? path == null : filePath.equals(path)) {
                it.remove();
            }
        }
        saveBookmarks();
    }

    // ========== Reading State Operations ==========

    public ReaderState getReadingState(String filePath) {
        return readingStates.get(filePath);
    }

    public void saveReadingState(ReaderState state) {
        state.setLastReadAt(System.currentTimeMillis());
        readingStates.put(state.getFilePath(), state);
        saveReadingStates();
    }

    public void deleteReadingState(String filePath) {
        readingStates.remove(filePath);
        saveReadingStates();
    }

    /**
     * Rebind saved progress and bookmarks after the app moves a local file.
     */
    public void moveFileReferences(String oldPath, String newPath) {
        if (oldPath == null || newPath == null || oldPath.equals(newPath)) return;

        boolean statesChanged = false;
        ReaderState state = readingStates.remove(oldPath);
        if (state != null) {
            state.setFilePath(newPath);
            readingStates.put(newPath, state);
            statesChanged = true;
        }

        boolean bookmarksChanged = false;
        String newName = new File(newPath).getName();
        for (Bookmark bookmark : bookmarks) {
            if (oldPath.equals(bookmark.getFilePath())) {
                bookmark.setFilePath(newPath);
                bookmark.setFileName(newName);
                if (isTxtBookmark(bookmark)) {
                    clearBookmarkPageMetadata(bookmark);
                }
                bookmarksChanged = true;
            }
        }

        if (statesChanged) saveReadingStates();
        if (bookmarksChanged) saveBookmarks();
    }

    /**
     * Rebind saved progress and bookmarks after the app moves a local folder.
     */
    public void movePathPrefixReferences(String oldRootPath, String newRootPath) {
        if (oldRootPath == null || newRootPath == null || oldRootPath.equals(newRootPath)) return;
        String oldPrefix = oldRootPath.endsWith(File.separator) ? oldRootPath : oldRootPath + File.separator;
        String newPrefix = newRootPath.endsWith(File.separator) ? newRootPath : newRootPath + File.separator;

        boolean statesChanged = false;
        Map<String, ReaderState> updatedStates = new HashMap<>();
        Iterator<Map.Entry<String, ReaderState>> stateIterator = readingStates.entrySet().iterator();
        while (stateIterator.hasNext()) {
            Map.Entry<String, ReaderState> entry = stateIterator.next();
            String path = entry.getKey();
            if (path == null) continue;

            String replacement = null;
            if (path.equals(oldRootPath)) {
                replacement = newRootPath;
            } else if (path.startsWith(oldPrefix)) {
                replacement = newPrefix + path.substring(oldPrefix.length());
            }

            if (replacement != null) {
                ReaderState state = entry.getValue();
                stateIterator.remove();
                if (state != null) {
                    state.setFilePath(replacement);
                    updatedStates.put(replacement, state);
                }
                statesChanged = true;
            }
        }
        if (!updatedStates.isEmpty()) {
            readingStates.putAll(updatedStates);
        }

        boolean bookmarksChanged = false;
        for (Bookmark bookmark : bookmarks) {
            String path = bookmark.getFilePath();
            if (path == null) continue;

            String replacement = null;
            if (path.equals(oldRootPath)) {
                replacement = newRootPath;
            } else if (path.startsWith(oldPrefix)) {
                replacement = newPrefix + path.substring(oldPrefix.length());
            }

            if (replacement != null) {
                bookmark.setFilePath(replacement);
                bookmark.setFileName(new File(replacement).getName());
                if (isTxtBookmark(bookmark)) {
                    clearBookmarkPageMetadata(bookmark);
                }
                bookmarksChanged = true;
            }
        }

        if (statesChanged) saveReadingStates();
        if (bookmarksChanged) saveBookmarks();
    }

    /**
     * Clear all recent-file entries / saved reading states without touching bookmarks.
     */
    public void clearReadingStates() {
        readingStates.clear();
        saveReadingStates();
    }

    /**
     * Get recently read files, sorted by last read time (most recent first).
     */
    public List<ReaderState> getRecentFiles(int limit) {
        List<ReaderState> states = new ArrayList<>(readingStates.values());
        Collections.sort(states, (a, b) -> Long.compare(b.getLastReadAt(), a.getLastReadAt()));
        if (states.size() > limit) {
            states = states.subList(0, limit);
        }
        return states;
    }

    /**
     * Fast existence check for UI visibility / clear-all actions.
     * Avoids sorting the whole recent-file map when only emptiness matters.
     */
    public boolean hasRecentFiles() {
        return !readingStates.isEmpty();
    }

    // ========== Export / Import ==========

    /**
     * Export all bookmarks and states to a single JSON string.
     * User can save this to a file for backup.
     */
    public String exportAll() {
        ensurePortableIdentitiesForExistingBookmarks();
        try {
            JSONObject root = new JSONObject();
            root.put("appName", "Readwide");
            root.put("backupType", "textview_reader_full_backup");
            root.put("schema", "textview-full-backup-v10");
            root.put("version", FORMAT_VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            PrefsManager exportPrefs = PrefsManager.getInstance(context);
            root.put("largeTxtCurrentPartitionPageModel", buildCurrentLargeTextPartitionPageModelJson(exportPrefs));
            root.put("largeTxtBookmarkPageModelSummary", buildLargeTextBookmarkPageModelSummaryJson(exportPrefs));
            root.put("_READ_ME_FIRST_EN", "This backup is safe to edit if you stay in bookmarkEdits.beginner. You do not need programming knowledge. Change only the values inside each edit object, then import this JSON back into Readwide. For TXT, edit by line or findText; cached page totals depend on the selected large-TXT partition page model. Export does not open every TXT file to recalculate page totals; stale or unknown cached totals are explicitly marked.");
            root.put("_읽으세요_KO", "bookmarkEdits.beginner 안에서만 수정하면 안전합니다. 프로그래밍 지식이 없어도 됩니다. 각 항목의 edit 안 값만 바꾼 뒤 이 JSON을 Readwide로 다시 가져오세요. TXT는 줄 번호나 findText로 수정하세요. 캐시된 페이지 총수는 선택된 대용량 TXT 파티션 페이지 모델에 따라 달라집니다. 백업 내보내기는 모든 TXT 파일을 열어 페이지 총수를 다시 계산하지 않으며, 오래되었거나 알 수 없는 캐시 총수는 명시적으로 표시됩니다.");

            JSONObject editGuide = new JSONObject();
            editGuide.put("purpose_EN", "This guide explains the two editable bookmark areas. The beginner area is for normal use. The developer area is for careful repair or migration.");
            editGuide.put("purpose_KO", "이 안내는 두 가지 북마크 수정 영역을 설명합니다. beginner 영역은 일반 사용자를 위한 곳이고, developer 영역은 신중한 복구나 마이그레이션을 위한 곳입니다.");

            JSONObject beginnerGuideEn = new JSONObject();
            beginnerGuideEn.put("whereToEdit", "For normal edits, use bookmarkEdits.beginner[].edit only.");
            beginnerGuideEn.put("memo", "Change memo when you only want to change the bookmark note.");
            beginnerGuideEn.put("lineOrPage", "Change lineOrPage when you know the target line or page number. For TXT, this means logical line, not displayed page.");
            beginnerGuideEn.put("moveBy", "Use moveBy for small corrections. Positive numbers move later; negative numbers move earlier.");
            beginnerGuideEn.put("txtFindText", "For TXT files, use findText when you know a sentence but do not know the line number. Leave findText empty when you are not using text search.");
            beginnerGuideEn.put("txtPageModel", "TXT pageNumber/totalPages are cached display metadata from the selected large-TXT partition mode. They may differ between Standard 4000/400 and High-buffer 12000/600, so normal bookmark edits should use setLine, moveByLines, or findText instead.");
            beginnerGuideEn.put("txtCachedPageStatus", "Each TXT edit row includes largeTxtPartitionPageModelState. If cachedPageModelMatchesCurrentPartitionMode is false, the cached Page X/Y was made under another or unknown partition mode, or has not yet been recalculated for the export mode. The bookmark location is still preserved by character/line/anchor data and Page X/Y refreshes after that file is opened and exact page anchors rebuild.");
            beginnerGuideEn.put("simpleRule", "Usually change only one thing: memo, lineOrPage, moveBy, or findText. Keeping the rest unchanged is safest.");
            editGuide.put("beginnerGuide_EN", beginnerGuideEn);

            JSONObject beginnerGuideKo = new JSONObject();
            beginnerGuideKo.put("whereToEdit", "일반적인 수정은 bookmarkEdits.beginner[].edit 안의 값만 바꾸면 됩니다.");
            beginnerGuideKo.put("memo", "북마크 메모만 바꾸고 싶으면 memo만 수정하세요.");
            beginnerGuideKo.put("lineOrPage", "목표 줄 번호나 페이지 번호를 알면 lineOrPage를 수정하세요. TXT에서는 표시 페이지가 아니라 논리 줄 번호를 뜻합니다.");
            beginnerGuideKo.put("moveBy", "조금만 위치를 보정하려면 moveBy를 쓰세요. 양수는 뒤쪽, 음수는 앞쪽으로 이동합니다.");
            beginnerGuideKo.put("txtFindText", "TXT 파일에서 줄 번호는 모르지만 문장을 알고 있으면 findText를 쓰세요. 문장 검색을 쓰지 않을 때는 findText를 빈칸으로 두면 됩니다.");
            beginnerGuideKo.put("txtPageModel", "TXT pageNumber/totalPages는 선택된 대용량 TXT 파티션 모드에서 나온 표시용 캐시입니다. 기본 4000/400과 고버퍼 12000/600 사이에서 달라질 수 있으므로, 일반 북마크 수정은 setLine, moveByLines, findText를 사용하세요.");
            beginnerGuideKo.put("txtCachedPageStatus", "각 TXT 수정 행에는 largeTxtPartitionPageModelState가 포함됩니다. cachedPageModelMatchesCurrentPartitionMode가 false이면 캐시된 Page X/Y가 다른 모드 또는 알 수 없는 모드에서 만들어졌거나, 백업 시점의 모드로 아직 재계산되지 않은 값입니다. 북마크 위치 자체는 문자/줄/앵커 정보로 유지되고, 해당 파일을 열어 exact page anchor가 다시 만들어지면 Page X/Y가 갱신됩니다.");
            beginnerGuideKo.put("simpleRule", "보통은 memo, lineOrPage, moveBy, findText 중 하나만 바꾸는 것이 가장 안전합니다. 나머지는 그대로 두세요.");
            editGuide.put("beginnerGuide_KO", beginnerGuideKo);

            JSONObject developerGuideEn = new JSONObject();
            developerGuideEn.put("whereToEdit", "Use bookmarkEdits.developer only for manual recovery, migration, or precise repair.");
            developerGuideEn.put("preferredRepair", "Prefer lineOrPage, setLine, setPage, or setPageOrSection before editing raw charPosition.");
            developerGuideEn.put("txtPageModel", "For TXT, pageNumber, totalPages, and pageLayoutSignature describe the cached page model that produced the displayed page label. Treat them as metadata unless you are repairing the page-model cache deliberately.");
            developerGuideEn.put("charPositionWarning", "charPosition is powerful but easy to break. Edit it only when you intentionally want raw internal positioning.");
            developerGuideEn.put("anchors", "anchorTextBefore and anchorTextAfter help TXT bookmarks recover after layout changes. Avoid editing them unless you are repairing anchors deliberately.");
            developerGuideEn.put("previewRefresh", "TXT preview and anchors are refreshed after import when the local file is available, or later after the file is rebound.");
            editGuide.put("developerGuide_EN", developerGuideEn);

            JSONObject developerGuideKo = new JSONObject();
            developerGuideKo.put("whereToEdit", "bookmarkEdits.developer는 수동 복구, 마이그레이션, 정밀 수정을 할 때만 사용하세요.");
            developerGuideKo.put("preferredRepair", "raw charPosition을 직접 고치기 전에 lineOrPage, setLine, setPage, setPageOrSection 수정을 먼저 권장합니다.");
            developerGuideKo.put("txtPageModel", "TXT의 pageNumber, totalPages, pageLayoutSignature는 표시 페이지 라벨을 만든 캐시 페이지 모델을 설명하는 메타데이터입니다. 페이지 모델 캐시를 의도적으로 복구하는 경우가 아니라면 수정하지 마세요.");
            developerGuideKo.put("charPositionWarning", "charPosition은 강력하지만 잘못 수정하기 쉽습니다. 내부 위치값을 직접 지정해야 할 때만 수정하세요.");
            developerGuideKo.put("anchors", "anchorTextBefore와 anchorTextAfter는 TXT 북마크가 레이아웃 변경 후에도 복구되도록 돕습니다. 앵커를 의도적으로 복구하는 경우가 아니면 수정하지 않는 것이 좋습니다.");
            developerGuideKo.put("previewRefresh", "TXT 미리보기와 앵커는 가져오기 시 로컬 파일을 찾을 수 있거나 나중에 파일이 다시 연결되면 새 위치 기준으로 갱신됩니다.");
            editGuide.put("developerGuide_KO", developerGuideKo);

            editGuide.put("safeRule_EN", "Do not edit bookmarkId, current, fileIdentity, anchors, pageLayoutSignature, cached page numbers, or the machine backup sections unless you are intentionally doing advanced repair.");
            editGuide.put("safeRule_KO", "고급 복구 목적이 아니라면 bookmarkId, current, fileIdentity, anchors, pageLayoutSignature, 캐시 페이지 번호, machine backup 영역은 수정하지 마세요.");
            editGuide.put("previewRule_EN", "current.preview is only a reference to help you recognize the bookmark. It is not a live preview while editing this file on a PC.");
            editGuide.put("previewRule_KO", "current.preview는 어떤 북마크인지 알아보기 위한 참고 문장입니다. PC에서 이 파일을 수정하는 동안 실시간으로 바뀌는 미리보기가 아닙니다.");
            root.put("bookmarkEditGuide", editGuide);

            JSONArray beginnerArr = new JSONArray();
            JSONArray developerArr = new JSONArray();
            for (Bookmark b : bookmarks) {
                JSONObject beginnerRow = b.toBeginnerEditJson();
                JSONObject developerRow = b.toDeveloperEditJson();
                decorateLargeTextPartitionPageModelState(beginnerRow, b, exportPrefs);
                decorateLargeTextPartitionPageModelState(developerRow, b, exportPrefs);
                beginnerArr.put(beginnerRow);
                developerArr.put(developerRow);
            }
            JSONObject bookmarkEdits = new JSONObject();
            bookmarkEdits.put("beginner", beginnerArr);
            bookmarkEdits.put("developer", developerArr);
            root.put("bookmarkEdits", bookmarkEdits);
            root.put("internalSectionsNote_EN", "Machine backup starts below. Normal users should not edit the sections below. Developers should prefer bookmarkEdits.developer first and use bookmarks[] only for low-level recovery.");
            root.put("internalSectionsNote_KO", "아래부터는 앱 내부 백업 영역입니다. 일반 사용자는 아래 영역을 수정하지 않는 것이 안전합니다. 개발자도 먼저 bookmarkEdits.developer를 사용하고, bookmarks[]는 저수준 복구가 필요할 때만 사용하세요.");

            // Bookmarks
            JSONArray arr = new JSONArray();
            for (Bookmark b : bookmarks) {
                arr.put(b.toJson());
            }
            root.put("bookmarks", arr);

            // Reading states
            JSONObject statesObj = new JSONObject();
            for (Map.Entry<String, ReaderState> entry : readingStates.entrySet()) {
                statesObj.put(entry.getKey(), entry.getValue().toJson());
            }
            root.put("readingStates", statesObj);

            // App settings, including layout, theme selection, behavior, sorting,
            // brightness, TXT/EPUB boundaries, font family, and recent folder prefs.
            // Security PIN data is skipped inside PrefsManager.
            root.put("settings", PrefsManager.getInstance(context).exportSettingsToJson());

            // Custom reading themes are stored outside SharedPreferences, so keep
            // them in the same backup JSON as the active_theme_id setting.
            root.put("customThemes", ThemeManager.getInstance(context).exportCustomThemesToJson());

            return root.toString(2); // pretty-printed for readability
        } catch (JSONException e) {
            Log.e(TAG, "Export failed", e);
            return "{}";
        }
    }


    private JSONObject buildCurrentLargeTextPartitionPageModelJson(PrefsManager prefs) throws JSONException {
        JSONObject obj = new JSONObject();
        int mode = prefs != null
                ? prefs.getLargeTextPartitionMode()
                : PrefsManager.LARGE_TEXT_PARTITION_MODE_STANDARD;
        int lines = prefs != null
                ? prefs.getLargeTextPartitionLines()
                : PrefsManager.LARGE_TEXT_PARTITION_LINES_STANDARD;
        int buffer = prefs != null
                ? prefs.getLargeTextPartitionBufferLines()
                : PrefsManager.LARGE_TEXT_PARTITION_BUFFER_LINES_STANDARD;
        obj.put("mode", mode == PrefsManager.LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                ? "highBuffer"
                : "standard");
        obj.put("partitionLines", lines);
        obj.put("lookaheadLines", buffer);
        obj.put("lookbehindLines", buffer);
        obj.put("meaning_EN", "Current selected large-TXT partition page model at export time. TXT cached Page X/Y values are current only when the bookmark row says cachedPageModelMatchesCurrentPartitionMode=true.");
        obj.put("meaning_KO", "백업을 내보낼 때 선택되어 있던 대용량 TXT 파티션 페이지 모델입니다. TXT의 캐시된 Page X/Y 값은 북마크 행의 cachedPageModelMatchesCurrentPartitionMode가 true일 때만 현재 모드 기준입니다.");
        return obj;
    }

    private JSONObject buildLargeTextBookmarkPageModelSummaryJson(PrefsManager prefs) throws JSONException {
        JSONObject summary = new JSONObject();
        int txtTotal = 0;
        int currentModeCached = 0;
        int staleOrUnknown = 0;
        int noCachedPage = 0;
        for (Bookmark b : bookmarks) {
            if (!"TXT".equals(bookmarkFileType(b))) continue;
            txtTotal++;
            if (largeTextCachedPageMatchesCurrentMode(b, prefs)) {
                currentModeCached++;
            } else if (hasLargeTextCachedPage(b)) {
                staleOrUnknown++;
            } else {
                noCachedPage++;
            }
        }
        summary.put("txtBookmarksTotal", txtTotal);
        summary.put("cachedForCurrentPartitionMode", currentModeCached);
        summary.put("cachedForOtherOrUnknownPartitionMode", staleOrUnknown);
        summary.put("notYetCalculatedForCurrentPartitionMode", staleOrUnknown + noCachedPage);
        summary.put("withoutCachedPageNumber", noCachedPage);
        summary.put("meaning_EN", "Backup export does not open every TXT file to rebuild page totals after a partition-mode change. Bookmarks remain position-safe through charPosition, logical line, and anchor text. Only cachedForCurrentPartitionMode rows have Page X/Y known to match this export's selected partition mode.");
        summary.put("meaning_KO", "백업 내보내기는 파티션 모드 변경 후 모든 TXT 파일을 열어 페이지 총수를 다시 계산하지 않습니다. 북마크 위치는 charPosition, 논리 줄, 앵커 텍스트로 유지됩니다. cachedForCurrentPartitionMode에 해당하는 항목만 이 백업의 선택 모드와 Page X/Y가 일치한다고 볼 수 있습니다.");
        return summary;
    }

    private void decorateLargeTextPartitionPageModelState(JSONObject row, Bookmark bookmark, PrefsManager prefs) throws JSONException {
        if (row == null || bookmark == null || !"TXT".equals(bookmarkFileType(bookmark))) return;

        int lines = prefs != null
                ? prefs.getLargeTextPartitionLines()
                : PrefsManager.LARGE_TEXT_PARTITION_LINES_STANDARD;
        int buffer = prefs != null
                ? prefs.getLargeTextPartitionBufferLines()
                : PrefsManager.LARGE_TEXT_PARTITION_BUFFER_LINES_STANDARD;
        String savedSignature = bookmark.getPageLayoutSignature() != null
                ? bookmark.getPageLayoutSignature()
                : "";
        boolean hasCachedPage = hasLargeTextCachedPage(bookmark);
        boolean matchesCurrentPartitionMode = largeTextCachedPageMatchesCurrentMode(bookmark, prefs);

        JSONObject state = new JSONObject();
        state.put("currentMode", currentLargeTextPartitionModeName(prefs));
        state.put("currentPartitionLines", lines);
        state.put("currentLookaheadLines", buffer);
        state.put("currentLookbehindLines", buffer);
        state.put("hasCachedPageNumber", hasCachedPage);
        state.put("cachedPageModelMatchesCurrentPartitionMode", matchesCurrentPartitionMode);
        state.put("cachedPageNumber", bookmark.getPageNumber());
        state.put("cachedTotalPages", bookmark.getTotalPages());
        state.put("cachedPageModel", savedSignature);
        state.put("requiresOpenFileRebuildForCurrentPageModel", !matchesCurrentPartitionMode);
        state.put("note_EN", matchesCurrentPartitionMode
                ? "The cached TXT Page X/Y was produced with the same partition mode as the current export setting."
                : "Do not treat the cached TXT Page X/Y as current for this export setting. Backup export does not open every TXT file to recalculate totals. The bookmark location remains anchored by charPosition/line/anchor text, and Page X/Y will refresh after this file is opened and exact anchors rebuild.");
        state.put("note_KO", matchesCurrentPartitionMode
                ? "캐시된 TXT Page X/Y가 현재 백업 설정과 같은 파티션 모드에서 만들어진 값입니다."
                : "캐시된 TXT Page X/Y를 현재 백업 설정 기준으로 보지 마세요. 백업 내보내기는 모든 TXT 파일을 열어 총수를 다시 계산하지 않습니다. 북마크 위치 자체는 charPosition/line/anchor text 기준으로 유지되며, 이 파일을 열고 exact anchor가 다시 만들어지면 Page X/Y가 갱신됩니다.");
        row.put("largeTxtPartitionPageModelState", state);

        JSONObject current = row.optJSONObject("current");
        if (current != null) {
            current.put("cachedPageModelMatchesCurrentPartitionMode", matchesCurrentPartitionMode);
            current.put("cachedPageStatus", matchesCurrentPartitionMode
                    ? "current partition mode"
                    : "stale, unknown, or not yet rebuilt for current partition mode");
        }
    }

    private boolean hasLargeTextCachedPage(Bookmark bookmark) {
        if (bookmark == null) return false;
        String savedSignature = bookmark.getPageLayoutSignature() != null
                ? bookmark.getPageLayoutSignature()
                : "";
        return bookmark.getPageNumber() > 0
                && bookmark.getTotalPages() > 0
                && !savedSignature.isEmpty();
    }

    private boolean largeTextCachedPageMatchesCurrentMode(Bookmark bookmark, PrefsManager prefs) {
        if (!hasLargeTextCachedPage(bookmark)) return false;
        int mode = prefs != null
                ? prefs.getLargeTextPartitionMode()
                : PrefsManager.LARGE_TEXT_PARTITION_MODE_STANDARD;
        int lines = prefs != null
                ? prefs.getLargeTextPartitionLines()
                : PrefsManager.LARGE_TEXT_PARTITION_LINES_STANDARD;
        int buffer = prefs != null
                ? prefs.getLargeTextPartitionBufferLines()
                : PrefsManager.LARGE_TEXT_PARTITION_BUFFER_LINES_STANDARD;
        String savedSignature = bookmark.getPageLayoutSignature();
        return savedSignature != null
                && savedSignature.contains("|partitionMode=" + mode)
                && savedSignature.contains("|partitionLines=" + lines)
                && savedSignature.contains("|partitionBuffer=" + buffer);
    }

    private String currentLargeTextPartitionModeName(PrefsManager prefs) {
        int mode = prefs != null
                ? prefs.getLargeTextPartitionMode()
                : PrefsManager.LARGE_TEXT_PARTITION_MODE_STANDARD;
        return mode == PrefsManager.LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                ? "highBuffer"
                : "standard";
    }

    /**
     * Import bookmarks and states from a JSON string.
     * @param merge if true, merge with existing; if false, replace all
     */
    public void importAll(String jsonString, boolean merge) {
        try {
            JSONObject root = new JSONObject(jsonString);
            boolean importedBookmarks = false;
            boolean importedReadingStates = false;
            boolean importedSettings = false;

            // Import bookmarks
            Map<String, JSONObject> beginnerEditMap = readBeginnerEditableBookmarkMap(root);
            Map<String, JSONObject> developerEditMap = readDeveloperEditableBookmarkMap(root);
            JSONArray arr = root.optJSONArray("bookmarks");
            if (arr != null) {
                if (!merge) {
                    bookmarks.clear();
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject bookmarkObj = arr.getJSONObject(i);
                    Bookmark b = Bookmark.fromJson(bookmarkObj);
                    applyPcEditableBookmarkFields(bookmarkObj, b);
                    applyDeveloperEditableBookmarkFields(developerEditMap.get(b.getId()), b);
                    applyBeginnerEditableBookmarkFields(beginnerEditMap.get(b.getId()), b);
                    enrichPortableIdentity(b);
                    if (merge) {
                        // If this is the same bookmark id, replace it so PC edits
                        // from the backup are not lost during a merge import.
                        int sameIdIndex = -1;
                        for (int j = 0; j < bookmarks.size(); j++) {
                            if (safeEquals(bookmarks.get(j).getId(), b.getId())) {
                                sameIdIndex = j;
                                break;
                            }
                        }
                        if (sameIdIndex >= 0) {
                            bookmarks.set(sameIdIndex, b);
                        } else {
                            boolean duplicate = false;
                            for (Bookmark existing : bookmarks) {
                                if (isSameBookmarkLocation(existing, b)) {
                                    duplicate = true;
                                    break;
                                }
                            }
                            if (!duplicate) {
                                bookmarks.add(b);
                            }
                        }
                    } else {
                        bookmarks.add(b);
                    }
                }
                saveBookmarks();
                importedBookmarks = true;
            }

            // Import reading states
            JSONObject statesObj = root.optJSONObject("readingStates");
            if (statesObj != null) {
                if (!merge) {
                    readingStates.clear();
                }
                Iterator<String> keys = statesObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    ReaderState state = ReaderState.fromJson(statesObj.getJSONObject(key));
                    readingStates.put(key, state);
                }
                saveReadingStates();
                importedReadingStates = true;
            }

            // Import settings and custom themes when present. Older backups that
            // only contain bookmarks/reading states remain valid.
            JSONObject settingsObj = root.optJSONObject("settings");
            if (settingsObj != null) {
                PrefsManager.getInstance(context).importSettingsFromJson(settingsObj, merge);
                importedSettings = true;
            }

            JSONArray themesArr = root.optJSONArray("customThemes");
            if (themesArr != null) {
                ThemeManager.getInstance(context).importCustomThemesFromJson(themesArr, merge);
            }

            if (importedBookmarks || importedReadingStates || importedSettings) {
                invalidateTxtLayoutDependentPageMetadata("backup import or settings migration");
            }
        } catch (Exception e) {
            Log.e(TAG, "Import failed", e);
        }
    }


    private Map<String, JSONObject> readBeginnerEditableBookmarkMap(JSONObject root) {
        Map<String, JSONObject> result = new HashMap<>();
        if (root == null) return result;

        JSONObject bookmarkEdits = root.optJSONObject("bookmarkEdits");
        if (bookmarkEdits != null) {
            addBookmarkEditRowsToMap(result, bookmarkEdits.optJSONArray("beginner"));
        }

        // Backward compatibility with 2.1.0/early-2.1.1 backups.
        addBookmarkEditRowsToMap(result, root.optJSONArray("beginnerEditableBookmarks"));
        return result;
    }

    private Map<String, JSONObject> readDeveloperEditableBookmarkMap(JSONObject root) {
        Map<String, JSONObject> result = new HashMap<>();
        if (root == null) return result;

        JSONObject bookmarkEdits = root.optJSONObject("bookmarkEdits");
        if (bookmarkEdits != null) {
            addBookmarkEditRowsToMap(result, bookmarkEdits.optJSONArray("developer"));
        }

        // Optional legacy/experimental alias.  It is not exported by the new
        // backup shape, but accepting it keeps manually edited files flexible.
        addBookmarkEditRowsToMap(result, root.optJSONArray("developerEditableBookmarks"));
        return result;
    }

    private void addBookmarkEditRowsToMap(Map<String, JSONObject> result, JSONArray arr) {
        if (result == null || arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject editObj = arr.optJSONObject(i);
            if (editObj == null) continue;
            String id = readBookmarkEditId(editObj);
            if (id != null && !id.trim().isEmpty()) {
                result.put(id, editObj);
            }
        }
    }

    private String readBookmarkEditId(JSONObject obj) {
        if (obj == null) return "";
        String id = obj.optString("bookmarkId", "");
        if (id == null || id.trim().isEmpty()) id = obj.optString("id", "");
        if (id == null || id.trim().isEmpty()) id = obj.optString("bookmarkId_DoNotEdit", "");
        if (id == null || id.trim().isEmpty()) id = obj.optString("bookmarkId_DO_NOT_EDIT", "");
        return id != null ? id.trim() : "";
    }

    private JSONObject normalizeBeginnerBookmarkEdit(JSONObject editObj) {
        JSONObject flat = new JSONObject();
        if (editObj == null) return flat;
        copyJsonValues(editObj, flat);

        JSONObject current = editObj.optJSONObject("current");
        if (current != null) {
            putIfMissing(flat, "currentLine_DoNotEdit", current.opt("line"));
            putIfMissing(flat, "currentPage_DoNotEdit", current.opt("page"));
            putIfMissing(flat, "currentPageOrSection_DoNotEdit", current.opt("pageOrSection"));
            putIfMissing(flat, "currentPreview_DoNotEdit", current.opt("preview"));
            putIfMissing(flat, "originalMemo_DoNotEdit", current.opt("memo"));
            Object original = current.has("originalLineOrPage") ? current.opt("originalLineOrPage") : current.opt("lineOrPage");
            putIfMissing(flat, "originalPosition_DoNotEdit", original);
        }

        JSONObject edit = editObj.optJSONObject("edit");
        if (edit != null) {
            copyJsonValues(edit, flat);
            Object lineOrPage = edit.opt("lineOrPage");
            String type = editObj.optString("fileType", editObj.optString("fileType_DoNotEdit", editObj.optString("fileType_DO_NOT_EDIT", "")));
            if (lineOrPage != null) {
                Object originalLineOrPage = flat.opt("originalPosition_DoNotEdit");
                boolean lineOrPageChanged = originalLineOrPage == null
                        || JSONObject.NULL.equals(originalLineOrPage)
                        || !String.valueOf(lineOrPage).equals(String.valueOf(originalLineOrPage));
                String targetKey;
                if ("EPUB".equalsIgnoreCase(type)) {
                    targetKey = "setPageOrSection";
                } else if ("PDF".equalsIgnoreCase(type) || "WORD".equalsIgnoreCase(type) || "HWP".equalsIgnoreCase(type)) {
                    targetKey = "setPage";
                } else {
                    targetKey = "setLine";
                }
                if (lineOrPageChanged) {
                    putQuietly(flat, targetKey, lineOrPage);
                } else {
                    putIfMissing(flat, targetKey, lineOrPage);
                }
            }
            putIfMissing(flat, "setLine", edit.opt("line"));
            putIfMissing(flat, "setPage", edit.opt("page"));
            putIfMissing(flat, "setPageOrSection", edit.opt("pageOrSection"));
            putIfMissing(flat, "moveByLines", edit.opt("lineMoveBy"));
            putIfMissing(flat, "moveByPages", edit.opt("pageMoveBy"));
            putIfMissing(flat, "findTextCaseSensitive", edit.opt("caseSensitive"));
        }
        return flat;
    }

    private void copyJsonValues(JSONObject source, JSONObject target) {
        if (source == null || target == null) return;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("current".equals(key) || "edit".equals(key) || "internal".equals(key)
                    || "anchors".equals(key) || "fileIdentity".equals(key)) {
                continue;
            }
            putQuietly(target, key, source.opt(key));
        }
    }

    private void putIfMissing(JSONObject obj, String key, Object value) {
        if (obj == null || key == null || obj.has(key) || value == null || JSONObject.NULL.equals(value)) return;
        putQuietly(obj, key, value);
    }

    private void putQuietly(JSONObject obj, String key, Object value) {
        if (obj == null || key == null || value == null || JSONObject.NULL.equals(value)) return;
        try {
            obj.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private void applyDeveloperEditableBookmarkFields(JSONObject developerObj, Bookmark bookmark) {
        if (developerObj == null || bookmark == null) return;

        JSONObject flat = normalizeBeginnerBookmarkEdit(developerObj);
        JSONObject edit = developerObj.optJSONObject("edit");
        if (edit == null) edit = developerObj;
        JSONObject current = developerObj.optJSONObject("current");
        JSONObject internal = developerObj.optJSONObject("internal");

        // Developer rows intentionally accept the same safe line/page/search edit
        // fields as beginner rows, plus optional direct charPosition repair below.
        applyBeginnerEditableBookmarkFields(flat, bookmark);

        int originalChar = readNonNegativeInt(current, "charPosition", Integer.MIN_VALUE);
        if (originalChar == Integer.MIN_VALUE) {
            originalChar = readNonNegativeInt(internal, "charPosition", Integer.MIN_VALUE);
        }
        if (originalChar == Integer.MIN_VALUE) {
            originalChar = readNonNegativeInt(edit, "originalCharPosition", Integer.MIN_VALUE);
        }

        int targetChar = readNonNegativeInt(edit, "charPosition", Integer.MIN_VALUE);
        if (targetChar == Integer.MIN_VALUE) {
            targetChar = readNonNegativeInt(edit, "targetCharPosition", Integer.MIN_VALUE);
        }
        if (targetChar == Integer.MIN_VALUE) return;
        if (originalChar != Integer.MIN_VALUE && targetChar == originalChar) return;

        if (isPdfBookmark(bookmark) || isEpubBookmark(bookmark) || isWordBookmark(bookmark)) {
            bookmark.setCharPosition(Math.max(0, targetChar));
            bookmark.setEndPosition(Math.max(bookmark.getCharPosition() + 1, bookmark.getEndPosition()));
            return;
        }

        File file = fileFromPath(bookmark.getFilePath());
        if (file != null && file.exists() && file.isFile()) {
            try {
                String text = FileUtils.readTextFile(file);
                applyPcTextCharPositionFromText(bookmark, text, targetChar);
                bookmark.clearPendingPcTextEdit();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply developer charPosition edit for " + file, e);
            }
        }

        bookmark.setCharPosition(Math.max(0, targetChar));
        bookmark.setEndPosition(Math.max(bookmark.getCharPosition() + 1, bookmark.getEndPosition()));
        bookmark.setPageNumber(0);
        bookmark.setTotalPages(0);
        bookmark.setPageLayoutSignature("");
    }

    private int readNonNegativeInt(JSONObject obj, String key, int fallback) {
        if (obj == null || key == null || !obj.has(key)) return fallback;
        try {
            Object raw = obj.opt(key);
            if (raw instanceof Number) {
                int value = ((Number) raw).intValue();
                return value >= 0 ? value : fallback;
            }
            String text = obj.optString(key, "").trim();
            if (text.isEmpty()) return fallback;
            int value = Integer.parseInt(text);
            return value >= 0 ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Apply the beginner-facing edit row when it exists.
     *
     * Unlike the first beginner JSON shape, this does not limit users to one
     * absolute position field.  TXT can be edited by exact line, relative line
     * movement, or search text; document bookmarks can be edited by exact page
     * and/or relative page movement.
     */
    private void applyBeginnerEditableBookmarkFields(JSONObject editObj, Bookmark bookmark) {
        if (editObj == null || bookmark == null) return;
        editObj = normalizeBeginnerBookmarkEdit(editObj);

        if (editObj.has("memo")) {
            String editedMemo = editObj.optString("memo", "");
            String originalMemo = editObj.has("originalMemo_DoNotEdit")
                    ? editObj.optString("originalMemo_DoNotEdit", "")
                    : null;
            if (originalMemo == null || !editedMemo.equals(originalMemo)) {
                bookmark.setLabel(editedMemo);
            }
        }

        if (isPdfBookmark(bookmark) || isEpubBookmark(bookmark) || isWordBookmark(bookmark)) {
            applyBeginnerDocumentEdit(editObj, bookmark);
        } else {
            applyBeginnerTextEdit(editObj, bookmark);
        }
    }

    private void applyBeginnerDocumentEdit(JSONObject editObj, Bookmark bookmark) {
        String type = bookmarkFileType(bookmark);
        int current;
        int setPosition;
        int moveBy;

        if ("EPUB".equals(type)) {
            current = readPositiveInt(editObj, "currentPageOrSection_DoNotEdit", Integer.MIN_VALUE);
            setPosition = readPositiveInt(editObj, "setPageOrSection", -1);
        } else {
            current = readPositiveInt(editObj, "currentPage_DoNotEdit", Integer.MIN_VALUE);
            setPosition = readPositiveInt(editObj, "setPage", -1);
        }

        int originalPosition = readPositiveInt(editObj, "originalPosition_DoNotEdit", Integer.MIN_VALUE);
        if (current == Integer.MIN_VALUE) {
            current = originalPosition;
        }
        int legacyPosition = readPositiveInt(editObj, "position", -1);
        boolean legacyPositionEdited = legacyPosition > 0
                && (originalPosition == Integer.MIN_VALUE || legacyPosition != originalPosition);
        if (legacyPositionEdited && (setPosition <= 0 || setPosition == current)) {
            setPosition = legacyPosition;
        } else if (setPosition <= 0) {
            setPosition = legacyPosition;
        }
        moveBy = readSignedInt(editObj, "moveByPages", 0);
        if (moveBy == 0) {
            moveBy = readSignedInt(editObj, "moveBySections", 0);
        }
        if (moveBy == 0) {
            moveBy = readSignedInt(editObj, "moveBy", 0);
        }

        boolean changedAbsolute = setPosition > 0
                && (current == Integer.MIN_VALUE || setPosition != current);
        boolean changedRelative = moveBy != 0;
        if (!changedAbsolute && !changedRelative) return;

        int base = setPosition > 0 ? setPosition : (current != Integer.MIN_VALUE ? current : bookmark.getPcEditPositionForManager());
        int target = Math.max(1, base + moveBy);
        applyPcDocumentPosition(bookmark, target);
    }

    private void applyBeginnerTextEdit(JSONObject editObj, Bookmark bookmark) {
        int currentLine = readPositiveInt(editObj, "currentLine_DoNotEdit", Integer.MIN_VALUE);
        int originalPosition = readPositiveInt(editObj, "originalPosition_DoNotEdit", Integer.MIN_VALUE);
        if (currentLine == Integer.MIN_VALUE) {
            currentLine = originalPosition;
        }

        int setLine = readPositiveInt(editObj, "setLine", -1);
        int legacyPosition = readPositiveInt(editObj, "position", -1);
        boolean legacyPositionEdited = legacyPosition > 0
                && (originalPosition == Integer.MIN_VALUE || legacyPosition != originalPosition);
        if (legacyPositionEdited && (setLine <= 0 || setLine == currentLine)) {
            setLine = legacyPosition;
        } else if (setLine <= 0) {
            // Backward-compatible alias from the earlier beginner JSON shape.
            setLine = legacyPosition;
        }

        int moveByLines = readSignedInt(editObj, "moveByLines", 0);
        if (moveByLines == 0) {
            moveByLines = readSignedInt(editObj, "moveBy", 0);
        }

        String findText = readTrimmedString(editObj, "findText");
        int findOccurrence = Math.max(1, readPositiveInt(editObj, "findOccurrence", 1));
        boolean findCaseSensitive = readBoolean(editObj, "findTextCaseSensitive", false);

        boolean changedByFind = !findText.isEmpty();
        boolean changedAbsolute = setLine > 0
                && (currentLine == Integer.MIN_VALUE || setLine != currentLine);
        boolean changedRelative = moveByLines != 0;
        if (!changedByFind && !changedAbsolute && !changedRelative) return;

        File file = fileFromPath(bookmark.getFilePath());
        if (file != null && file.exists() && file.isFile()) {
            try {
                String text = FileUtils.readTextFile(file);
                boolean applied = applyTextEditWithContent(
                        bookmark,
                        text,
                        findText,
                        findOccurrence,
                        findCaseSensitive,
                        setLine,
                        currentLine,
                        moveByLines);
                if (applied) {
                    bookmark.clearPendingPcTextEdit();
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply beginner TXT bookmark edit immediately for " + file, e);
            }
        }

        // The file may not exist on this device yet.  Keep the edit as a pending
        // portable action and resolve it the first time the matching local file is
        // opened/rebound.  This prevents cross-device backup edits from silently
        // falling back to the old charPosition.
        int fallbackLine = setLine > 0 ? setLine : (currentLine != Integer.MIN_VALUE ? currentLine : bookmark.getLineNumber());
        bookmark.setPendingPcEditLine(Math.max(1, fallbackLine));
        bookmark.setPendingPcMoveByLines(moveByLines);
        bookmark.setPendingPcFindText(findText);
        bookmark.setPendingPcFindOccurrence(findOccurrence);
        bookmark.setPendingPcFindCaseSensitive(findCaseSensitive);
        if (setLine > 0 || moveByLines != 0) {
            bookmark.setLineNumber(Math.max(1, fallbackLine + moveByLines));
        }
        bookmark.setPageNumber(0);
        bookmark.setTotalPages(0);
        bookmark.setPageLayoutSignature("");
    }

    private int readPositiveInt(JSONObject obj, String key, int fallback) {
        if (obj == null || key == null || !obj.has(key)) return fallback;
        try {
            Object raw = obj.opt(key);
            if (raw instanceof Number) {
                int value = ((Number) raw).intValue();
                return value > 0 ? value : fallback;
            }
            String text = obj.optString(key, "").trim();
            if (text.isEmpty()) return fallback;
            int value = Integer.parseInt(text);
            return value > 0 ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int readSignedInt(JSONObject obj, String key, int fallback) {
        if (obj == null || key == null || !obj.has(key)) return fallback;
        try {
            Object raw = obj.opt(key);
            if (raw instanceof Number) return ((Number) raw).intValue();
            String text = obj.optString(key, "").trim();
            if (text.isEmpty()) return fallback;
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String readTrimmedString(JSONObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) return "";
        String value = obj.optString(key, "");
        return value != null ? value.trim() : "";
    }

    private boolean readBoolean(JSONObject obj, String key, boolean fallback) {
        if (obj == null || key == null || !obj.has(key)) return fallback;
        Object raw = obj.opt(key);
        if (raw instanceof Boolean) return (Boolean) raw;
        String text = obj.optString(key, "").trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "yes".equals(text) || "1".equals(text)) return true;
        if ("false".equals(text) || "no".equals(text) || "0".equals(text)) return false;
        return fallback;
    }

    /**
     * Apply fields intended for PC-side editing of exported JSON backups.
     *
     * Internal bookmarks still use charPosition for fast/exact restore.  For a
     * human-editable backup, charPosition is annoying to calculate manually, so
     * exported bookmarks also include pcEditPosition plus pcEditOriginalPosition.
     * The importer only treats pcEditPosition as authoritative when it differs
     * from pcEditOriginalPosition, which means an unedited backup imports without
     * losing the exact original character offset inside long TXT lines.
     */
    private void applyPcEditableBookmarkFields(JSONObject obj, Bookmark bookmark) {
        if (obj == null || bookmark == null || !obj.has("pcEditPosition")) return;

        int pcPosition = obj.optInt("pcEditPosition", -1);
        if (pcPosition <= 0) return;

        int original = obj.has("pcEditOriginalPosition")
                ? obj.optInt("pcEditOriginalPosition", pcPosition)
                : Integer.MIN_VALUE;
        boolean editedOnPc = !obj.has("pcEditOriginalPosition") || pcPosition != original;
        if (!editedOnPc) return;

        if (isPdfBookmark(bookmark) || isEpubBookmark(bookmark) || isWordBookmark(bookmark)) {
            applyPcDocumentPosition(bookmark, pcPosition);
        } else {
            applyPcTextLinePosition(bookmark, pcPosition);
        }
    }

    private void applyPcDocumentPosition(Bookmark bookmark, int oneBasedPosition) {
        int zeroBased = Math.max(0, oneBasedPosition - 1);
        bookmark.setCharPosition(zeroBased);
        bookmark.setEndPosition(zeroBased + 1);
        bookmark.setLineNumber(oneBasedPosition);
        bookmark.setPageNumber(oneBasedPosition);
        // Keep totalPages when it was known. The viewer will still clamp invalid
        // document page jumps against the actual opened file.
    }

    private void applyPcTextLinePosition(Bookmark bookmark, int oneBasedLine) {
        bookmark.setLineNumber(Math.max(1, oneBasedLine));
        bookmark.setPageNumber(0);
        bookmark.setTotalPages(0);
        bookmark.setPageLayoutSignature("");

        String path = bookmark.getFilePath();
        if (path == null || path.trim().isEmpty()) return;

        File file = new File(path);
        if (!file.exists() || !file.isFile()) return;

        try {
            String text = FileUtils.readTextFile(file);
            applyPcTextLinePositionFromText(bookmark, text, oneBasedLine);
            bookmark.clearPendingPcTextEdit();
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply PC-edited bookmark position for " + path, e);
        }
    }

    private void applyPcTextLinePositionFromText(Bookmark bookmark, String text, int oneBasedLine) {
        int safeLine = Math.max(1, oneBasedLine);
        int charPosition = findCharPositionForOneBasedLine(text, safeLine);
        applyPcTextCharPositionFromText(bookmark, text, charPosition);
        bookmark.setLineNumber(safeLine);
    }

    private void applyPcTextCharPositionFromText(Bookmark bookmark, String text, int charPosition) {
        if (bookmark == null) return;
        int safePosition = Math.max(0, Math.min(text != null ? text.length() : 0, charPosition));
        String excerpt = makeBookmarkExcerpt(text, safePosition);
        bookmark.setCharPosition(safePosition);
        bookmark.setLineNumber(countOneBasedLineForChar(text, safePosition));
        bookmark.setPageNumber(0);
        bookmark.setTotalPages(0);
        bookmark.setPageLayoutSignature("");
        bookmark.setExcerpt(excerpt);
        bookmark.setAnchorTextBefore(makeAnchorTextBefore(text, safePosition));
        bookmark.setAnchorTextAfter(makeAnchorTextAfter(text, safePosition));
        bookmark.setEndPosition(Math.min(
                text != null ? text.length() : safePosition,
                safePosition + Math.max(1, excerpt.length())));
    }

    private boolean applyTextEditWithContent(Bookmark bookmark, String text, String findText,
                                             int findOccurrence, boolean caseSensitive,
                                             int setLine, int currentLine, int moveByLines) {
        if (bookmark == null || text == null) return false;

        int baseChar = -1;
        if (findText != null && !findText.isEmpty()) {
            baseChar = findTextPosition(text, findText, findOccurrence, caseSensitive);
        }

        if (baseChar >= 0) {
            if (moveByLines != 0) {
                int line = countOneBasedLineForChar(text, baseChar);
                applyPcTextLinePositionFromText(bookmark, text, Math.max(1, line + moveByLines));
            } else {
                applyPcTextCharPositionFromText(bookmark, text, baseChar);
            }
            return true;
        }

        int baseLine = setLine > 0 ? setLine : currentLine;
        if (baseLine == Integer.MIN_VALUE || baseLine <= 0) {
            baseLine = Math.max(1, bookmark.getLineNumber());
        }
        if (setLine > 0 || moveByLines != 0) {
            applyPcTextLinePositionFromText(bookmark, text, Math.max(1, baseLine + moveByLines));
            return true;
        }

        return false;
    }

    private int findTextPosition(String text, String needle, int occurrence, boolean caseSensitive) {
        if (text == null || needle == null || needle.isEmpty()) return -1;
        int wanted = Math.max(1, occurrence);
        String haystack = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        String target = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);
        int from = 0;
        int found = -1;
        for (int i = 0; i < wanted; i++) {
            found = haystack.indexOf(target, from);
            if (found < 0) return -1;
            from = Math.min(haystack.length(), found + Math.max(1, target.length()));
        }
        return Math.max(0, Math.min(text.length(), found));
    }

    private int countOneBasedLineForChar(String text, int charPosition) {
        if (text == null || text.isEmpty()) return 1;
        int safePosition = Math.max(0, Math.min(text.length(), charPosition));
        int line = 1;
        for (int i = 0; i < safePosition; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private void resolvePendingPcTextEditsForFile(String filePath, List<Bookmark> result) {
        if (filePath == null || result == null || result.isEmpty()) return;
        boolean hasPending = false;
        for (Bookmark bookmark : result) {
            if (bookmark != null && bookmark.hasPendingPcTextEdit()
                    && !isPdfBookmark(bookmark) && !isEpubBookmark(bookmark) && !isWordBookmark(bookmark)) {
                hasPending = true;
                break;
            }
        }
        if (!hasPending) return;

        File file = fileFromPath(filePath);
        if (file == null || !file.exists() || !file.isFile()) return;

        try {
            String text = FileUtils.readTextFile(file);
            boolean changed = false;
            for (Bookmark bookmark : result) {
                if (bookmark == null || !bookmark.hasPendingPcTextEdit()) continue;
                boolean applied = applyTextEditWithContent(
                        bookmark,
                        text,
                        bookmark.getPendingPcFindText(),
                        bookmark.getPendingPcFindOccurrence(),
                        bookmark.isPendingPcFindCaseSensitive(),
                        bookmark.getPendingPcEditLine(),
                        bookmark.getLineNumber(),
                        bookmark.getPendingPcMoveByLines());
                if (applied) {
                    bookmark.clearPendingPcTextEdit();
                    bookmark.setUpdatedAt(System.currentTimeMillis());
                    changed = true;
                }
            }
            if (changed) saveBookmarks();
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve pending PC TXT bookmark edits for " + filePath, e);
        }
    }

    private int findCharPositionForOneBasedLine(String text, int targetLine) {
        if (text == null || text.isEmpty() || targetLine <= 1) return 0;
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                if (line >= targetLine) return Math.min(i + 1, text.length());
            }
        }
        return text.length();
    }

    private String makeBookmarkExcerpt(String text, int charPosition) {
        if (text == null || text.isEmpty()) return "";
        int start = FileUtils.clampToSurrogateSafeStart(text,
                Math.max(0, Math.min(text.length(), charPosition)));
        int end = Math.min(text.length(), start + 90);
        return FileUtils.safeSubstring(text, start, end).trim().replaceAll("[\r\n]+", " ");
    }

    private String makeAnchorTextBefore(String text, int charPosition) {
        if (text == null || text.isEmpty()) return "";
        int pos = FileUtils.clampToSurrogateSafeStart(text,
                Math.max(0, Math.min(text.length(), charPosition)));
        int start = Math.max(0, pos - 80);
        return FileUtils.safeSubstring(text, start, pos);
    }

    private String makeAnchorTextAfter(String text, int charPosition) {
        if (text == null || text.isEmpty()) return "";
        int pos = FileUtils.clampToSurrogateSafeStart(text,
                Math.max(0, Math.min(text.length(), charPosition)));
        int end = Math.min(text.length(), pos + 120);
        return FileUtils.safeSubstring(text, pos, end);
    }

    private boolean isPdfBookmark(Bookmark bookmark) {
        return FileUtils.isPdfFile(bookmarkFileName(bookmark));
    }

    private boolean isEpubBookmark(Bookmark bookmark) {
        return FileUtils.isEpubFile(bookmarkFileName(bookmark));
    }

    private boolean isWordBookmark(Bookmark bookmark) {
        return FileUtils.isWordOrHwpFile(bookmarkFileName(bookmark));
    }

    private String bookmarkFileName(Bookmark bookmark) {
        if (bookmark == null) return "";
        if (bookmark.getFileName() != null && !bookmark.getFileName().isEmpty()) {
            return bookmark.getFileName();
        }
        String path = bookmark.getFilePath();
        if (path == null || path.isEmpty()) return "";
        return new File(path).getName();
    }

    private String bookmarkFileType(Bookmark bookmark) {
        String name = bookmarkFileName(bookmark).toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return "PDF";
        if (name.endsWith(".epub")) return "EPUB";
        if (name.endsWith(".hwp") || name.endsWith(".hwpx")) return "HWP";
        if (name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".rtf")) return "WORD";
        return "TXT";
    }


    // ========== Portable bookmark identity ==========

    /**
     * Attach cheap portable identity fields to a bookmark when the target local
     * file is available.  This reads at most three 4KB samples, not the whole
     * file, so save/export stays responsive even for large TXT files.
     */
    private void enrichPortableIdentity(Bookmark bookmark) {
        if (bookmark == null) return;
        File file = fileFromPath(bookmark.getFilePath());
        if (file == null || !file.exists() || !file.isFile()) return;

        if (bookmark.getFileName() == null || bookmark.getFileName().trim().isEmpty()) {
            bookmark.setFileName(file.getName());
        }

        long size = file.length();
        boolean needsFingerprint = bookmark.getQuickFingerprint() == null
                || bookmark.getQuickFingerprint().trim().isEmpty()
                || bookmark.getFileSizeBytes() != size;
        bookmark.setFileSizeBytes(size);

        if (needsFingerprint) {
            String fp = quickFingerprint(file);
            if (fp != null && !fp.isEmpty()) {
                bookmark.setQuickFingerprint(fp);
            }
        }
    }

    private void ensurePortableIdentitiesForExistingBookmarks() {
        boolean changed = false;
        for (Bookmark bookmark : bookmarks) {
            long oldSize = bookmark.getFileSizeBytes();
            String oldFp = bookmark.getQuickFingerprint();
            enrichPortableIdentity(bookmark);
            if (oldSize != bookmark.getFileSizeBytes()
                    || !safeEquals(oldFp, bookmark.getQuickFingerprint())) {
                changed = true;
            }
        }
        if (changed) saveBookmarks();
    }

    /**
     * Rebind imported/moved bookmarks to the currently opened file only when the
     * exact path lookup failed.  No phone-wide scan is performed: the user opens
     * or selects a file, then we compare that single file against stored portable
     * identity fields.
     */
    private int bindPortableBookmarksToFile(String filePath) {
        if (filePath == null || bookmarks.isEmpty()) return 0;
        File file = fileFromPath(filePath);
        if (file == null || !file.exists() || !file.isFile()) return 0;

        String displayName = file.getName();
        boolean hasCandidate = false;
        for (Bookmark bookmark : bookmarks) {
            String existingPath = bookmark.getFilePath();
            if (filePath.equals(existingPath)) continue;
            if (bookmarkFileName(bookmark).equalsIgnoreCase(displayName)) {
                hasCandidate = true;
                break;
            }
        }
        if (!hasCandidate) return 0;

        PortableFileIdentity target = buildPortableFileIdentity(file);
        if (target == null || target.displayName == null || target.displayName.isEmpty()) return 0;

        int bound = 0;
        for (Bookmark bookmark : bookmarks) {
            String existingPath = bookmark.getFilePath();
            if (filePath.equals(existingPath)) continue;
            if (!portableIdentityMatches(bookmark, target)) continue;

            bookmark.setFilePath(filePath);
            bookmark.setFileName(target.displayName);
            bookmark.setFileSizeBytes(target.sizeBytes);
            bookmark.setQuickFingerprint(target.quickFingerprint);
            if (isTxtBookmark(bookmark)) {
                clearBookmarkPageMetadata(bookmark);
            }
            bookmark.setUpdatedAt(System.currentTimeMillis());
            bound++;
        }

        if (bound > 0) saveBookmarks();
        return bound;
    }

    private boolean portableIdentityMatches(Bookmark bookmark, PortableFileIdentity target) {
        if (bookmark == null || target == null) return false;

        String bookmarkName = bookmarkFileName(bookmark);
        if (bookmarkName == null || bookmarkName.isEmpty()) return false;
        if (!bookmarkName.equalsIgnoreCase(target.displayName)) return false;

        long bookmarkSize = bookmark.getFileSizeBytes();
        String bookmarkFp = bookmark.getQuickFingerprint();

        if (bookmarkFp != null && !bookmarkFp.trim().isEmpty()
                && target.quickFingerprint != null && !target.quickFingerprint.trim().isEmpty()) {
            return bookmarkFp.equals(target.quickFingerprint)
                    && (bookmarkSize <= 0L || bookmarkSize == target.sizeBytes);
        }

        // Conservative fallback for older backups made before quickFingerprint
        // existed: same display name + same byte length.  Do not bind by name only.
        return bookmarkSize > 0L && bookmarkSize == target.sizeBytes;
    }

    private PortableFileIdentity buildPortableFileIdentity(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        PortableFileIdentity identity = new PortableFileIdentity();
        identity.displayName = file.getName();
        identity.sizeBytes = file.length();
        identity.quickFingerprint = quickFingerprint(file);
        return identity;
    }

    private String quickFingerprint(File file) {
        if (file == null || !file.exists() || !file.isFile()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long length = file.length();
            updateDigestWithLong(digest, length);
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                if (length <= QUICK_FINGERPRINT_SAMPLE_BYTES * 3L) {
                    updateDigestWithRange(digest, raf, 0L, length);
                } else {
                    long sample = QUICK_FINGERPRINT_SAMPLE_BYTES;
                    updateDigestWithRange(digest, raf, 0L, sample);
                    long middle = Math.max(0L, (length / 2L) - (sample / 2L));
                    updateDigestWithRange(digest, raf, middle, sample);
                    updateDigestWithRange(digest, raf, Math.max(0L, length - sample), sample);
                }
            }
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute quick bookmark fingerprint for " + file, e);
            return "";
        }
    }

    private void updateDigestWithRange(MessageDigest digest, RandomAccessFile raf, long start, long count) throws Exception {
        if (digest == null || raf == null || count <= 0L) return;
        long safeStart = Math.max(0L, Math.min(start, raf.length()));
        long remaining = Math.min(count, raf.length() - safeStart);
        raf.seek(safeStart);
        updateDigestWithLong(digest, safeStart);
        updateDigestWithLong(digest, remaining);

        byte[] buffer = new byte[8192];
        while (remaining > 0L) {
            int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read <= 0) break;
            digest.update(buffer, 0, read);
            remaining -= read;
        }
    }

    private void updateDigestWithLong(MessageDigest digest, long value) {
        for (int i = 7; i >= 0; i--) {
            digest.update((byte) ((value >> (i * 8)) & 0xff));
        }
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        char[] hex = new char[bytes.length * 2];
        final char[] table = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            hex[i * 2] = table[v >>> 4];
            hex[i * 2 + 1] = table[v & 0x0f];
        }
        return new String(hex);
    }

    private File fileFromPath(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        // content:// paths are local Android bindings; this manager only hashes
        // actual local files.  Current readers usually pass resolved local paths.
        if (path.startsWith("content://")) return null;
        try {
            return new File(path);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSameBookmarkLocation(Bookmark a, Bookmark b) {
        if (a == null || b == null) return false;
        if (safeEquals(a.getFilePath(), b.getFilePath())
                && a.getCharPosition() == b.getCharPosition()) {
            return true;
        }
        if (!bookmarkFileName(a).equalsIgnoreCase(bookmarkFileName(b))) return false;
        if (a.getCharPosition() != b.getCharPosition()) return false;
        String fpA = a.getQuickFingerprint();
        String fpB = b.getQuickFingerprint();
        return fpA != null && !fpA.isEmpty() && fpA.equals(fpB);
    }

    private boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static class PortableFileIdentity {
        String displayName;
        long sizeBytes;
        String quickFingerprint;
    }

    /**
     * Batch replace path prefix in all bookmarks and reading states.
     * Useful when moving files to a new SD card or device.
     * e.g., replacePathPrefix("/storage/AAAA-BBBB", "/storage/CCCC-DDDD")
     */
    public int replacePathPrefix(String oldPrefix, String newPrefix) {
        int count = 0;

        // Update bookmarks
        for (Bookmark b : bookmarks) {
            String bookmarkPath = b.getFilePath();
            if (bookmarkPath != null && bookmarkPath.startsWith(oldPrefix)) {
                b.setFilePath(bookmarkPath.replace(oldPrefix, newPrefix));
                if (isTxtBookmark(b)) {
                    clearBookmarkPageMetadata(b);
                }
                b.setUpdatedAt(System.currentTimeMillis());
                count++;
            }
        }
        saveBookmarks();

        // Update reading states
        Map<String, ReaderState> updated = new HashMap<>();
        for (Map.Entry<String, ReaderState> entry : readingStates.entrySet()) {
            String path = entry.getKey();
            ReaderState state = entry.getValue();
            if (path.startsWith(oldPrefix)) {
                String newPath = path.replace(oldPrefix, newPrefix);
                state.setFilePath(newPath);
                updated.put(newPath, state);
                count++;
            } else {
                updated.put(path, state);
            }
        }
        readingStates = updated;
        saveReadingStates();

        return count;
    }


    /**
     * TXT displayed Page X/Y is layout dependent. Importing settings from another
     * device or older app version can change font, line spacing, boundaries,
     * screen-size assumptions, and the large-TXT partition model. Keep the stable
     * location fields (charPosition, lineNumber, anchors) but discard stale page
     * labels so the next real open recalculates them from the current layout.
     */
    private void invalidateTxtLayoutDependentPageMetadata(String reason) {
        boolean bookmarksChanged = false;
        for (Bookmark bookmark : bookmarks) {
            if (!isTxtBookmark(bookmark)) continue;
            if (clearBookmarkPageMetadata(bookmark)) {
                bookmarksChanged = true;
            }
        }
        if (bookmarksChanged) {
            saveBookmarks();
        }

        boolean statesChanged = false;
        for (ReaderState state : readingStates.values()) {
            if (state == null || !isTxtLikePath(state.getFilePath())) continue;
            if (state.getPageNumber() != 0 || state.getTotalPages() != 0) {
                state.setPageNumber(0);
                state.setTotalPages(0);
                statesChanged = true;
            }
        }
        if (statesChanged) {
            saveReadingStates();
        }
    }

    private boolean clearBookmarkPageMetadata(Bookmark bookmark) {
        if (bookmark == null) return false;
        boolean changed = bookmark.getPageNumber() != 0
                || bookmark.getTotalPages() != 0
                || (bookmark.getPageLayoutSignature() != null
                && !bookmark.getPageLayoutSignature().isEmpty());
        if (!changed) return false;
        bookmark.setPageNumber(0);
        bookmark.setTotalPages(0);
        bookmark.setPageLayoutSignature("");
        return true;
    }

    private boolean isTxtBookmark(Bookmark bookmark) {
        return "TXT".equals(bookmarkFileType(bookmark));
    }

    private boolean isTxtLikePath(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        return FileUtils.isTextFile(path);
    }

    // ========== Private I/O ==========

    private void loadBookmarks() {
        bookmarks = new ArrayList<>();
        String json = readFile(BOOKMARKS_FILE);
        if (json == null) return;

        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("bookmarks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    bookmarks.add(Bookmark.fromJson(arr.getJSONObject(i)));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load bookmarks", e);
        }
    }

    private void saveBookmarks() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            JSONArray arr = new JSONArray();
            for (Bookmark b : bookmarks) {
                arr.put(b.toJson());
            }
            root.put("bookmarks", arr);
            writeFile(BOOKMARKS_FILE, root.toString(2));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save bookmarks", e);
        }
    }

    private void loadReadingStates() {
        readingStates = new HashMap<>();
        String json = readFile(STATES_FILE);
        if (json == null) return;

        try {
            JSONObject root = new JSONObject(json);
            JSONObject statesObj = root.optJSONObject("states");
            if (statesObj != null) {
                Iterator<String> keys = statesObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    readingStates.put(key, ReaderState.fromJson(statesObj.getJSONObject(key)));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load reading states", e);
        }
    }

    private void saveReadingStates() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            JSONObject statesObj = new JSONObject();
            for (Map.Entry<String, ReaderState> entry : readingStates.entrySet()) {
                statesObj.put(entry.getKey(), entry.getValue().toJson());
            }
            root.put("states", statesObj);
            writeFile(STATES_FILE, root.toString(2));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save reading states", e);
        }
    }

    private String readFile(String fileName) {
        File file = new File(context.getFilesDir(), fileName);
        if (!file.exists()) return null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read " + fileName, e);
            return null;
        }
    }

    private void writeFile(String fileName, String content) {
        File file = new File(context.getFilesDir(), fileName);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write " + fileName, e);
        }
    }
}
