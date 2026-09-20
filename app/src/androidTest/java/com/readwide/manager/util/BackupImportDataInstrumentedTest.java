package com.readwide.manager.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONException;
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
}
