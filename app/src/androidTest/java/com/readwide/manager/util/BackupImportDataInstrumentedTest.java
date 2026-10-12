package com.readwide.manager.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.DocumentAnnotation;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.model.Theme;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackupImportDataInstrumentedTest {
    private static void reject(String json) {
        try { new BackupImportData(json); fail("Expected invalid backup to be rejected"); }
        catch (JSONException expected) { /* No live managers are touched by parsing. */ }
    }

    @Test public void supportsLegacyBookmarkOnlyBackupAndIntentionalEmptyReplacement() throws Exception {
        BackupImportData old = new BackupImportData("{\"bookmarks\":[{\"filePath\":\"/book.txt\"}]}");
        assertEquals(1, old.bookmarks.size());
        assertNull(old.states);
        assertNull(old.settings);
        assertNull(old.txtContents); // An absent optional section must not clear durable contents.
        assertTrue(new BackupImportData("{\"bookmarks\":[]}").bookmarks.isEmpty());
    }

    @Test public void rejectsBadLateRowAndWrongSectionShapes() {
        reject("{\"bookmarks\":[{\"filePath\":\"/book.txt\"},false]}");
        reject("{\"bookmarks\":[],\"customThemes\":[{}]}");
        reject("{\"bookmarks\":null}");
        reject("{\"bookmarks\":{}}");
        reject("{\"readingStates\":[]}");
        reject("{\"annotations\":[null]}");
        reject("{\"version\":8}");
    }

    @Test public void rejectsMissingPathsAndMismatchedReadingStateKey() {
        reject("{\"bookmarks\":[{}]}");
        reject("{\"annotations\":[{\"filePath\":\"  \"}]}");
        reject("{\"readingStates\":{\"/a.txt\":{\"filePath\":\"/b.txt\"}}}");
    }

    @Test public void validatesPreferencesBeforeAnyCommit() {
        reject("{\"bookmarks\":[],\"settings\":{\"values\":{\"flag\":{\"type\":\"boolean\",\"value\":\"true\"}}}}");
        reject("{\"settings\":{\"values\":{\"number\":{\"type\":\"int\",\"value\":2147483648}}}}");
        reject("{\"settings\":{\"values\":{\"set\":{\"type\":\"stringSet\",\"value\":[\"ok\",3]}}}}");
        reject("{\"settings\":{\"values\":{\"txt_display_replacement_rules_json\":{\"type\":\"string\",\"value\":\"[false]\"}}}}");
    }

    @Test public void rejectsNonFiniteThemeValueBeforeCommit() {
        reject("{\"customThemes\":[{\"id\":\"theme\",\"textColor\":0,\"backgroundColor\":0,\"backgroundImageAlpha\":\"NaN\"}]}");
    }

    @Test public void optionalContentsSectionRequiresCompleteValidatedShape() throws Exception {
        BackupImportData data = new BackupImportData("{\"txtContents\":{\"version\":1,\"options\":{},\"edits\":{}}}");
        assertTrue(data.txtContents.isEmpty());
        assertNull(data.bookmarks);
        reject("{\"bookmarks\":[],\"txtContents\":null}");
        reject("{\"bookmarks\":[],\"txtContents\":{\"version\":1,\"options\":{}}}");
        reject("{\"bookmarks\":[],\"txtContents\":{\"version\":1,\"options\":{},\"edits\":{\"../outside\":{}}}}");
    }

    private static String bookmarkField(String key, Object value) throws Exception {
        return new JSONObject().put("bookmarks", new JSONArray().put(
                new JSONObject().put("filePath", "/book.txt").put(key, value))).toString();
    }

    @Test public void rejectsLossyBookmarkCoordinatesInsteadOfDefaultingOrWrapping() throws Exception {
        for (String key : new String[]{"charPosition", "endPosition", "lineNumber", "pageNumber", "totalPages"}) {
            for (Object value : new Object[]{4294967297L, -2147483649L, 1.5, "1.5", "NaN", "bad", true, JSONObject.NULL}) {
                reject(bookmarkField(key, value));
            }
        }
    }

    @Test public void validatesReadingStateAndAnnotationCoordinates() throws Exception {
        for (String key : new String[]{"charPosition", "scrollY", "pageNumber", "totalPages"}) {
            JSONObject row = new JSONObject().put("filePath", "/book.txt").put(key, 4294967297L);
            reject(new JSONObject().put("readingStates", new JSONObject().put("/book.txt", row)).toString());
        }
        for (String key : new String[]{"startPosition", "endPosition", "lineNumber"}) {
            JSONObject row = new JSONObject().put("filePath", "/book.txt").put(key, 1.5);
            reject(new JSONObject().put("annotations", new JSONArray().put(row)).toString());
        }
    }

    @Test public void rejectsUnrepresentableLongMetadata() throws Exception {
        for (String key : new String[]{"fileSizeBytes", "createdAt", "updatedAt"}) {
            reject(bookmarkField(key, "9223372036854775808"));
            reject(bookmarkField(key, "-9223372036854775809"));
        }
        for (String key : new String[]{"fileLength", "lastReadAt"}) {
            JSONObject row = new JSONObject().put("filePath", "/book.txt").put(key, "9223372036854775808");
            reject(new JSONObject().put("readingStates", new JSONObject().put("/book.txt", row)).toString());
        }
        for (String key : new String[]{"createdAt", "updatedAt"}) {
            JSONObject row = new JSONObject().put("filePath", "/book.txt").put(key, "9223372036854775808");
            reject(new JSONObject().put("annotations", new JSONArray().put(row)).toString());
        }
    }

    @Test public void nestedPendingEditsAndPortableSizeCannotBypassValidation() throws Exception {
        for (String key : new String[]{"line", "moveByLines", "findOccurrence"}) {
            reject(bookmarkField("pendingPcTextEdit", new JSONObject().put(key, 4294967297L)));
        }
        reject(bookmarkField("fileIdentity", new JSONObject().put("sizeBytes", "9223372036854775808")));
        reject(bookmarkField("pendingPcTextEdit", false));
        reject(bookmarkField("fileIdentity", "not an object"));
    }

    @Test public void validIntegerBoundariesAndSignedPendingMovesKeepTheirValues() throws Exception {
        JSONObject row = new JSONObject().put("filePath", "/book.txt")
                .put("charPosition", Integer.MAX_VALUE).put("endPosition", "2147483647")
                .put("lineNumber", "123.0").put("pendingPcTextEdit",
                        new JSONObject().put("moveByLines", Integer.MIN_VALUE));
        BackupImportData data = new BackupImportData(new JSONObject()
                .put("bookmarks", new JSONArray().put(row)).toString());
        assertEquals(Integer.MAX_VALUE, data.bookmarks.get(0).getCharPosition());
        assertEquals(Integer.MAX_VALUE, data.bookmarks.get(0).getEndPosition());
        assertEquals(123, data.bookmarks.get(0).getLineNumber());
        assertEquals(Integer.MIN_VALUE, data.bookmarks.get(0).getPendingPcMoveByLines());
    }

    @Test public void numericLongStringsAreNormalizedWithoutDoubleRounding() throws Exception {
        long exact = 9007199254740993L; // First odd integer beyond double's exact range.
        JSONObject bookmark = new JSONObject().put("filePath", "/book.txt").put("createdAt", Long.toString(exact))
                .put("fileIdentity", new JSONObject().put("sizeBytes", Long.toString(exact)));
        JSONObject state = new JSONObject().put("filePath", "/book.txt").put("lastReadAt", Long.toString(Long.MAX_VALUE));
        BackupImportData data = new BackupImportData(new JSONObject().put("bookmarks", new JSONArray().put(bookmark))
                .put("readingStates", new JSONObject().put("/book.txt", state)).toString());
        assertEquals(exact, data.bookmarks.get(0).getCreatedAt());
        assertEquals(exact, data.bookmarks.get(0).getFileSizeBytes());
        assertEquals(Long.MAX_VALUE, data.states.get("/book.txt").getLastReadAt());
    }

    @Test public void themeNumbersMustBeRepresentableWithBoundedOpacity() throws Exception {
        JSONObject theme = new JSONObject().put("id", "custom").put("textColor", -1).put("backgroundColor", 0);
        for (Object value : new Object[]{-0.1, 1.1, "oops", "Infinity", true, JSONObject.NULL}) {
            theme.put("backgroundImageAlpha", value);
            reject(new JSONObject().put("customThemes", new JSONArray().put(theme)).toString());
        }
        theme.put("backgroundImageAlpha", "0.25");
        BackupImportData valid = new BackupImportData(new JSONObject().put("customThemes", new JSONArray().put(theme)).toString());
        assertEquals(0.25f, valid.themes.get(0).getBackgroundImageAlpha(), 0f);
        assertEquals(-1, valid.themes.get(0).getTextColor());
        for (String key : new String[]{"textColor", "backgroundColor", "linkColor", "toolbarColor"}) {
            JSONObject bad = new JSONObject(theme.toString()).put(key, 4294967295L);
            reject(new JSONObject().put("customThemes", new JSONArray().put(bad)).toString());
        }
    }

    @Test public void exportedModelRowsStillRoundTripWithoutCoordinateChanges() throws Exception {
        Bookmark bookmark = new Bookmark("/book.txt", "book.txt", 250, 13, "text");
        ReaderState state = new ReaderState("/book.txt");
        state.setCharPosition(345); state.setScrollY(67);
        DocumentAnnotation annotation = new DocumentAnnotation();
        annotation.setFilePath("/book.txt"); annotation.setStartPosition(12); annotation.setEndPosition(18);
        Theme theme = new Theme(); theme.setName("Custom");
        BackupImportData data = new BackupImportData(new JSONObject()
                .put("bookmarks", new JSONArray().put(bookmark.toJson()))
                .put("readingStates", new JSONObject().put("/book.txt", state.toJson()))
                .put("annotations", new JSONArray().put(annotation.toJson()))
                .put("customThemes", new JSONArray().put(theme.toJson())).toString());
        assertEquals(bookmark.toJson().toString(), data.bookmarks.get(0).toJson().toString());
        assertEquals(state.toJson().toString(), data.states.get("/book.txt").toJson().toString());
        assertEquals(annotation.toJson().toString(), data.annotations.get(0).toJson().toString());
        assertEquals(theme.toJson().toString(), data.themes.get(0).toJson().toString());
    }
}
