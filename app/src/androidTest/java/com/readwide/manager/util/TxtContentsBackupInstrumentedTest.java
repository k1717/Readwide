package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Isolated app-private fixtures. Never reads/writes the user's live managers or records. */
@RunWith(AndroidJUnit4.class)
public class TxtContentsBackupInstrumentedTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    private static final String PATH = "/book.txt";
    private static final String TEXT = "Chapter 1\n\nbody\n\nChapter 2\n\nend";

    private Context store(String name) throws Exception {
        File files = temp.newFolder(name + "-files"), cache = temp.newFolder(name + "-cache");
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public File getCacheDir() { return cache; }
        };
    }

    private static void seed(Context context, String path, String id, String title, int hide) throws Exception {
        TxtContentsRepository repository = new TxtContentsRepository(context);
        repository.saveOptions(path, TxtContentsOptions.DEFAULT);
        TxtContentsRepository.Result result = repository.loadText(path, TEXT, false, () -> {});
        repository.edit(result, new TxtContentsScanner.Entry("manual:" + id, title, 12, 0, "", "body", true),
                hide < 0 ? null : result.entries.get(hide).id, false);
    }

    private static void restore(Context context, JSONObject section, boolean merge) throws Exception {
        Map<String, String> incoming = TxtContentsBackup.parse(section);
        synchronized (TxtContentsRepository.EDIT_LOCK) {
            List<BackupImportTransaction.Step> steps = new ArrayList<>();
            new TxtContentsBackup(context).appendImportSteps(steps, incoming, merge);
            BackupImportTransaction.run(steps);
        }
    }

    private static JSONObject copy(JSONObject json) throws Exception { return new JSONObject(json.toString()); }
    private static JSONObject empty() throws Exception {
        return new JSONObject().put("version", 1).put("options", new JSONObject()).put("edits", new JSONObject());
    }
    private static JSONObject firstEdits(JSONObject section) throws Exception {
        JSONObject paths = section.getJSONObject("edits");
        JSONObject versions = paths.getJSONObject(paths.keys().next());
        return versions.getJSONObject(versions.keys().next());
    }
    private static JSONObject firstOptions(JSONObject section) throws Exception {
        JSONObject options = section.getJSONObject("options");
        return options.getJSONObject(options.keys().next());
    }
    private static void reject(JSONObject section) throws Exception {
        try { TxtContentsBackup.parse(section); fail("Invalid section accepted"); }
        catch (JSONException expected) { }
    }

    @Test public void restoresOptionsManualAndHiddenEntriesWithoutCopyingAutomaticCache() throws Exception {
        Context source = store("source"), destination = store("destination");
        seed(source, PATH, "one", "My heading", 0);
        TxtContentsOptions custom = new TxtContentsOptions(127, false, false, true, false, "^Appendix (.+)$");
        new TxtContentsRepository(source).saveOptions(PATH, custom);
        JSONObject section = new TxtContentsBackup(source).exportJson();
        restore(destination, section, false);
        assertFalse(new File(destination.getCacheDir(), "txt-contents").exists());
        TxtContentsRepository repository = new TxtContentsRepository(destination);
        assertEquals(custom.cacheKey(), repository.loadOptions(PATH).cacheKey());
        TxtContentsRepository.Result result = repository.loadText(PATH, TEXT, false, () -> {});
        assertEquals(2, result.entries.size());
        assertEquals("manual:one", result.entries.get(0).id);
        assertEquals("My heading", result.entries.get(0).title);
        assertEquals(12, result.entries.get(0).position);
        assertEquals(section.toString(), new TxtContentsBackup(destination).exportJson().toString());
    }

    @Test public void differentPathOrContentCannotAdoptRestoredEdits() throws Exception {
        Context source = store("source"), destination = store("destination");
        seed(source, PATH, "one", "My heading", 0);
        restore(destination, new TxtContentsBackup(source).exportJson(), false);
        TxtContentsRepository repository = new TxtContentsRepository(destination);
        assertEquals(2, repository.loadText("/moved.txt", TEXT, false, () -> {}).entries.size());
        TxtContentsRepository.Result changed = repository.loadText(PATH, "prefix\n\n" + TEXT, false, () -> {});
        assertEquals(2, changed.entries.size());
        assertTrue(changed.previousEdits);
        for (TxtContentsScanner.Entry entry : changed.entries) assertFalse(entry.manual);
    }

    @Test public void largeTextRestorationRetainsItsSourceAndTransformIdentity() throws Exception {
        Context source = store("source"), destination = store("destination");
        File document = temp.newFile("large-path.txt");
        AtomicUtf8File.write(document, TEXT);
        TextDisplayRuleManager.CompiledRules rules = TextDisplayRuleManager.compile(java.util.Collections.emptyList());
        TxtContentsRepository first = new TxtContentsRepository(source);
        TxtContentsRepository.Result original = first.loadLarge(document, "UTF-8", "unchanged", rules, false,
                document.length(), document.lastModified(), false, () -> {});
        first.edit(original, new TxtContentsScanner.Entry("manual:large", "Large heading", 12, 0, "", "body", true),
                original.entries.get(0).id, false);
        restore(destination, new TxtContentsBackup(source).exportJson(), false);
        TxtContentsRepository second = new TxtContentsRepository(destination);
        TxtContentsRepository.Result restored = second.loadLarge(document, "UTF-8", "unchanged", rules, false,
                document.length(), document.lastModified(), false, () -> {});
        assertEquals(original.version, restored.version);
        assertEquals("manual:large", restored.entries.get(0).id);
        TxtContentsRepository.Result changed = second.loadLarge(document, "UTF-8", "changed", rules, false,
                document.length(), document.lastModified(), false, () -> {});
        assertNotEquals(original.version, changed.version);
        assertTrue(changed.previousEdits);
        for (TxtContentsScanner.Entry entry : changed.entries) assertFalse(entry.manual);
    }

    @Test public void mergeUnionsHiddenAndManualEntriesWithIncomingIdReplacement() throws Exception {
        Context source = store("source"), destination = store("destination");
        seed(source, PATH, "same", "Incoming", 0);
        seed(destination, PATH, "same", "Previous", 1);
        TxtContentsRepository repository = new TxtContentsRepository(destination);
        repository.edit(repository.loadText(PATH, TEXT, false, () -> {}),
                new TxtContentsScanner.Entry("manual:keep", "Keep", 14, 0, "", "", true), null, false);
        seed(destination, "/other.txt", "other", "Other book", -1);
        restore(destination, new TxtContentsBackup(source).exportJson(), true);
        TxtContentsRepository.Result result = repository.loadText(PATH, TEXT, false, () -> {});
        assertEquals(2, result.entries.size());
        assertEquals("Incoming", result.entries.get(0).title);
        assertEquals("Keep", result.entries.get(1).title);
        assertEquals(3, repository.loadText("/other.txt", TEXT, false, () -> {}).entries.size());
    }

    @Test public void explicitEmptyReplacementClearsOnlyDurableContentsRecords() throws Exception {
        Context destination = store("destination");
        seed(destination, PATH, "one", "Previous", 0);
        File unrelated = new File(destination.getFilesDir(), "bookmarks.json");
        AtomicUtf8File.write(unrelated, "keep");
        restore(destination, empty(), false);
        assertEquals(empty().toString(), new TxtContentsBackup(destination).exportJson().toString());
        assertEquals("keep", AtomicUtf8File.read(unrelated));
        assertEquals(2, new TxtContentsRepository(destination).loadText(PATH, TEXT, false, () -> {}).entries.size());
    }

    @Test public void laterFailureRestoresRemovedRecordsAndRemovesNewRecords() throws Exception {
        Context source = store("source"), destination = store("destination");
        seed(source, "/new.txt", "new", "New", 0);
        seed(destination, PATH, "old", "Old", 1);
        TxtContentsBackup backup = new TxtContentsBackup(destination);
        String before = backup.exportJson().toString();
        synchronized (TxtContentsRepository.EDIT_LOCK) {
            List<BackupImportTransaction.Step> steps = new ArrayList<>();
            backup.appendImportSteps(steps, TxtContentsBackup.parse(new TxtContentsBackup(source).exportJson()), false);
            steps.add(BackupImportTransaction.step(() -> { throw new IOException("late failure"); }, () -> {}));
            try { BackupImportTransaction.run(steps); fail(); }
            catch (IOException expected) { assertEquals("late failure", expected.getMessage()); }
        }
        assertEquals(before, backup.exportJson().toString());
    }

    @Test public void partiallyAppliedContentsFailureAlsoRollsBackEarlierBackupSections() throws Exception {
        Context source = store("source"), destination = store("destination");
        seed(source, PATH, "new", "New", 0);
        TxtContentsBackup backup = new TxtContentsBackup(destination);
        String before = backup.exportJson().toString();
        int[] priorSection = {0};
        synchronized (TxtContentsRepository.EDIT_LOCK) {
            List<BackupImportTransaction.Step> steps = new ArrayList<>();
            steps.add(BackupImportTransaction.step(() -> priorSection[0] = 1, () -> priorSection[0] = 0));
            backup.appendImportSteps(steps, TxtContentsBackup.parse(new TxtContentsBackup(source).exportJson()), false);
            BackupImportTransaction.Step first = steps.get(1);
            steps.set(1, BackupImportTransaction.step(() -> { first.apply(); throw new IOException("partial"); }, first::rollback));
            try { BackupImportTransaction.run(steps); fail(); }
            catch (IOException expected) { assertEquals("partial", expected.getMessage()); }
        }
        assertEquals(0, priorSection[0]);
        assertEquals(before, backup.exportJson().toString());
    }

    @Test public void malformedKeysNumbersPatternsAndTypesFailPreflight() throws Exception {
        Context source = store("source"); seed(source, PATH, "one", "Title", 0);
        JSONObject valid = new TxtContentsBackup(source).exportJson();
        reject(copy(valid).put("version", 2));
        reject(copy(valid).put("options", new JSONArray()));
        JSONObject bad = copy(valid);
        bad.getJSONObject("options").put("../bookmarks", firstOptions(valid)); reject(bad);
        bad = copy(valid);
        JSONObject versions = bad.getJSONObject("edits").getJSONObject(bad.getJSONObject("edits").keys().next());
        versions.put("../../bookmarks", firstEdits(valid)); reject(bad);
        for (Object number : new Object[]{4294967297L, 1.5, "12", JSONObject.NULL}) {
            bad = copy(valid); firstEdits(bad).getJSONArray("manual").getJSONObject(0).put("position", number); reject(bad);
        }
        bad = copy(valid); firstEdits(bad).put("hidden", new JSONArray().put("auto:4294967297")); reject(bad);
        bad = copy(valid); firstOptions(bad).put("formats", 64).put("custom", "("); reject(bad);
        bad = copy(valid); firstOptions(bad).put("blank", "false"); reject(bad);
        bad = copy(valid); firstEdits(bad).getJSONArray("manual").getJSONObject(0).put("title", true); reject(bad);
    }

    @Test public void corruptExistingDurableDataStopsExportAndReplacementWithoutOverwritingIt() throws Exception {
        Context destination = store("destination"); seed(destination, PATH, "one", "Title", 0);
        File options = new File(destination.getFilesDir(), "txt-contents-options").listFiles()[0];
        AtomicUtf8File.write(options, "broken");
        try { new TxtContentsBackup(destination).exportJson(); fail(); }
        catch (JSONException expected) { }
        try { restore(destination, empty(), false); fail(); }
        catch (JSONException expected) { }
        assertEquals("broken", AtomicUtf8File.read(options));
    }

    @Test public void backupOnlyAtomicRecoveryRecordIsIncluded() throws Exception {
        Context source = store("source"); seed(source, PATH, "one", "Title", 0);
        TxtContentsBackup backup = new TxtContentsBackup(source);
        String expected = backup.exportJson().toString();
        File options = new File(source.getFilesDir(), "txt-contents-options").listFiles()[0];
        assertTrue(options.renameTo(new File(options.getPath() + ".bak")));
        assertEquals(expected, backup.exportJson().toString());
        assertTrue(options.isFile());
    }

    @Test public void malformedUtf8TitleStopsExportAndReplacementWithoutChangingBytes() throws Exception {
        Context destination = store("destination"); seed(destination, PATH, "one", "Title", 0);
        File versionDirectory = new File(destination.getFilesDir(), "txt-contents").listFiles()[0];
        File edits = versionDirectory.listFiles()[0];
        String json = AtomicUtf8File.read(edits);
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int titleOffset = json.indexOf("Title"); // This seeded JSON is ASCII.
        assertTrue(titleOffset >= 0);
        bytes[titleOffset] = (byte) 0xc3; // Followed by ASCII 'i': malformed, but replacement would parse.
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(edits)) { out.write(bytes); }
        try { new TxtContentsBackup(destination).exportJson(); fail(); }
        catch (java.nio.charset.CharacterCodingException expected) { }
        try { restore(destination, empty(), false); fail(); }
        catch (java.nio.charset.CharacterCodingException expected) { }
        byte[] after = new byte[bytes.length];
        try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.FileInputStream(edits))) {
            input.readFully(after); assertEquals(-1, input.read());
        }
        assertArrayEquals(bytes, after);
    }

    @Test public void symbolicLinkDirectoryCannotRedirectRestoration() throws Exception {
        Context source = store("source"), destination = store("destination");
        seed(source, PATH, "one", "Title", 0);
        File outside = temp.newFolder("outside");
        File link = new File(destination.getFilesDir(), "txt-contents-options");
        Os.symlink(outside.getAbsolutePath(), link.getAbsolutePath());
        try {
            try { restore(destination, new TxtContentsBackup(source).exportJson(), false); fail(); }
            catch (IOException expected) { assertEquals(0, outside.listFiles().length); }
        } finally { assertTrue(link.delete()); }
    }
}
