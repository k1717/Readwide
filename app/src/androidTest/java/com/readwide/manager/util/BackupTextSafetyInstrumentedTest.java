package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.model.Bookmark;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackupTextSafetyInstrumentedTest {
    private File directory;
    private BookmarkManager manager;
    @Before public void setup() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = new File(base.getCacheDir(), "backup-text-" + java.util.UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return directory; }
        };
        Constructor<BookmarkManager> ctor = BookmarkManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        manager = ctor.newInstance(isolated);
    }
    @After public void cleanup() {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(directory.delete());
    }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = BookmarkManager.class.getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(manager, args);
    }
    private File text(String content) throws Exception {
        File file = new File(directory, "book.txt");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(content.getBytes(StandardCharsets.UTF_8)); }
        return file;
    }

    @Test public void backupUnicodeIsNotChangedToReaderPresentation() throws Exception {
        String json = "{\"filePath\":\"/✨❤️.txt\",\"memo\":\"한국어 ☀️\"}";
        assertEquals(json, TextEncodingDetector.decodeBackupText(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(json, TextEncodingDetector.decodeBackupText(("\uFEFF" + json).getBytes(StandardCharsets.UTF_8)));
        assertEquals(json, TextEncodingDetector.decodeBackupText(("\uFEFF" + json).getBytes(StandardCharsets.UTF_16LE)));
    }
    @Test public void malformedBomMarkedBackupIsRejectedNotRepaired() throws Exception {
        try { TextEncodingDetector.decodeBackupText(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xc3}); fail(); }
        catch (IOException expected) { }
    }
    @Test public void prospectiveRulesAndCollapseAreUsedWithoutChangingLiveSettings() throws Exception {
        File file = text("cat\r\n\r\n\r\nlast");
        TextDisplayRule rule = new TextDisplayRule();
        rule.findText = "cat"; rule.replacementText = "longer title";
        Map<String, Object> settings = new HashMap<>();
        settings.put("txt_collapse_blank_lines", true);
        settings.put("txt_display_replacement_rules_json", new JSONArray().put(rule.toJson()).toString());
        assertEquals("longer title\n\nlast", new BackupTextContext(settings).read(file));
    }
    @Test public void sourceMutationInvalidatesOneFileReuse() throws Exception {
        BackupTextContext snapshot = new BackupTextContext(new HashMap<>());
        File file = text("first");
        assertEquals("first", snapshot.read(file));
        text("changed length");
        assertEquals("changed length", snapshot.read(file));
    }

    @Test public void editedLineUsesTheProspectiveDisplayedText() throws Exception {
        File file = text("cat\r\n\r\n\r\nlast");
        TextDisplayRule rule = new TextDisplayRule();
        rule.findText = "cat"; rule.replacementText = "longer title";
        Map<String, Object> settings = new HashMap<>();
        settings.put("txt_collapse_blank_lines", true);
        settings.put("txt_display_replacement_rules_json", new JSONArray().put(rule.toJson()).toString());
        BackupTextContext policy = new BackupTextContext(settings);
        Bookmark bookmark = new Bookmark();
        bookmark.setFilePath(file.getAbsolutePath());
        invoke("applyPcTextLinePosition", new Class<?>[]{Bookmark.class, int.class, BackupTextContext.class}, bookmark, 3, policy);
        assertEquals(policy.read(file).indexOf("last"), bookmark.getCharPosition());
        assertFalse(bookmark.hasPendingPcTextEdit());
    }

    @Test public void largeTextPolicyMatchesPartitionLineJoining() throws Exception {
        File file = new File(directory, "large.txt");
        byte[] line = "cat\n".getBytes(StandardCharsets.UTF_8);
        byte[] block = new byte[4096];
        for (int i = 0; i < block.length; i += line.length) System.arraycopy(line, 0, block, i, line.length);
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int i = 0; i < 768; i++) out.write(block); // Exactly the existing 3 MiB switch point.
        }
        TextDisplayRule rule = new TextDisplayRule();
        rule.findText = "^cat$"; rule.replacementText = "dog"; rule.useRegex = true;
        Map<String, Object> settings = new HashMap<>();
        settings.put("txt_display_replacement_rules_json", new JSONArray().put(rule.toJson()).toString());
        String result = new BackupTextContext(settings).read(file);
        assertTrue(result.startsWith("dog\ndog\n"));
        assertTrue(result.endsWith("\ndog"));
        assertEquals(file.length() - 1, result.length());
    }
    @Test public void caseInsensitiveFindKeepsOriginalUnicodeOffsets() throws Exception {
        assertEquals(2, invoke("findTextPosition", new Class<?>[]{String.class, String.class, int.class, boolean.class},
                "İ target", "TARGET", 1, false));
        assertEquals(-1, invoke("findTextPosition", new Class<?>[]{String.class, String.class, int.class, boolean.class},
                "target", "target", 2, false));
    }
    @Test public void oversizedJsonNumberDoesNotWrapIntoAnotherPosition() throws Exception {
        JSONObject json = new JSONObject().put("line", 4294967297L);
        assertEquals(-1, invoke("readPositiveInt", new Class<?>[]{JSONObject.class, String.class, int.class}, json, "line", -1));
        json.put("line", 1.5);
        assertEquals(-1, invoke("readPositiveInt", new Class<?>[]{JSONObject.class, String.class, int.class}, json, "line", -1));
    }
    @Test public void relativePositionSaturatesInsteadOfWrappingToStart() throws Exception {
        assertEquals(Integer.MAX_VALUE, invoke("saturatedPosition", new Class<?>[]{int.class, int.class}, Integer.MAX_VALUE, 20));
        assertEquals(1, invoke("saturatedPosition", new Class<?>[]{int.class, int.class}, 2, Integer.MIN_VALUE));
    }
    @Test public void absentFileKeepsEditedLineForLaterRebinding() throws Exception {
        Bookmark bookmark = new Bookmark();
        bookmark.setFilePath(new File(directory, "absent.txt").getAbsolutePath());
        invoke("applyPcTextLinePosition", new Class<?>[]{Bookmark.class, int.class, BackupTextContext.class},
                bookmark, 23, new BackupTextContext(new HashMap<>()));
        assertTrue(bookmark.hasPendingPcTextEdit());
        assertEquals(23, bookmark.getPendingPcEditLine());
    }

    @Test public void malformedLateSectionCannotReplaceLiveBookmarksOrTheirFile() throws Exception {
        Bookmark original = new Bookmark();
        original.setFilePath(new File(directory, "absent.txt").getAbsolutePath());
        original.setCharPosition(57);
        manager.addBookmark(original);
        File saved = new File(directory, "bookmarks.json");
        String before = AtomicUtf8File.readIfPresent(saved);
        assertNotNull(before);
        String incoming = new JSONObject().put("bookmarks", new JSONArray()).put("annotations",
                new JSONArray().put(new JSONObject().put("filePath", original.getFilePath())
                        .put("startPosition", 4294967297L))).toString();
        try { manager.importAll(incoming, false); fail("Expected validation before replacement"); }
        catch (org.json.JSONException expected) { }
        assertEquals(1, manager.getAllBookmarks().size());
        assertSame(original, manager.getAllBookmarks().get(0));
        assertEquals(57, original.getCharPosition());
        assertEquals(before, AtomicUtf8File.readIfPresent(saved));
    }

    @Test public void exportedEditingHintDoesNotWrapAtMaximumCharacter() throws Exception {
        Bookmark bookmark = new Bookmark();
        bookmark.setFilePath("/book.txt"); bookmark.setCharPosition(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, bookmark.getPcEditPositionForManager());
        assertEquals(Integer.MAX_VALUE, bookmark.toJson().getInt("pcEditPosition"));
        assertEquals(Integer.MAX_VALUE, bookmark.toJson().getInt("pcEditOriginalPosition"));
        assertEquals(Integer.MAX_VALUE, bookmark.getCharPosition());
    }
}
