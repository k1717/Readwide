package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.Assert.*;

/** Isolated on-device storage fixtures; never writes the user's contents metadata. */
@RunWith(AndroidJUnit4.class)
public class TxtContentsRepositoryInstrumentedTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    private TxtContentsRepository repository;
    private File cache, files;
    private static final String TEXT = "Chapter 1\n\nbody\n\nChapter 2\n\nend";

    @Before public void setUp() throws Exception {
        cache = temp.newFolder("cache");
        files = temp.newFolder("files");
        Context isolated = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public File getFilesDir() { return files; }
            @Override public File getCacheDir() { return cache; }
        };
        repository = new TxtContentsRepository(isolated);
    }

    @Test public void manualAndHiddenEntriesSurviveForcedRescan() throws Exception {
        TxtContentsRepository.Result result = repository.loadText("/book.txt", TEXT, false, () -> {});
        result = repository.edit(result, new TxtContentsScanner.Entry("manual:test", "My heading", 12, 0, "", "body", true),
                result.entries.get(0).id, false);
        assertEquals(2, result.entries.size());
        result = repository.loadText("/book.txt", TEXT, true, () -> {});
        assertEquals("My heading", result.entries.get(0).title);
        result = repository.edit(result, null, null, true);
        assertEquals(3, result.entries.size());
    }

    @Test public void textVersionChangeDoesNotReuseOrDestroyEdits() throws Exception {
        TxtContentsRepository.Result result = repository.loadText("/book.txt", TEXT, false, () -> {});
        repository.edit(result, null, result.entries.get(0).id, false);
        TxtContentsRepository.Result changed = repository.loadText("/book.txt", "prefix\n\n" + TEXT, false, () -> {});
        assertEquals(2, changed.entries.size());
        assertTrue(changed.previousEdits);
        assertEquals(1, repository.loadText("/book.txt", TEXT, false, () -> {}).entries.size());
    }

    @Test public void staleWindowsMergeEditsInsteadOfReplacingThem() throws Exception {
        TxtContentsRepository.Result first = repository.loadText("/book.txt", TEXT, false, () -> {});
        TxtContentsRepository.Result second = repository.loadText("/book.txt", TEXT, false, () -> {});
        repository.edit(first, null, first.entries.get(0).id, false);
        repository.edit(second, null, second.entries.get(1).id, false);
        assertTrue(repository.loadText("/book.txt", TEXT, false, () -> {}).entries.isEmpty());
    }

    @Test public void corruptCacheIsRebuiltButCorruptEditsAreNotOverwritten() throws Exception {
        TxtContentsRepository.Result result = repository.loadText("/book.txt", TEXT, false, () -> {});
        File[] cached = new File(cache, "txt-contents").listFiles();
        assertNotNull(cached);
        write(cached[0], "broken");
        assertEquals(2, repository.loadText("/book.txt", TEXT, false, () -> {}).entries.size());
        repository.edit(result, null, result.entries.get(0).id, false);
        File[] directories = new File(files, "txt-contents").listFiles();
        assertNotNull(directories);
        File[] edits = directories[0].listFiles();
        assertNotNull(edits);
        write(edits[0], "broken");
        try { repository.edit(result, null, null, true); fail("Must preserve damaged durable edits"); }
        catch (IOException expected) { assertEquals("broken", AtomicUtf8File.read(edits[0])); }
    }

    @Test public void largeTextMatchesCollapsedDisplayedCoordinates() throws Exception {
        File source = temp.newFile("book.txt");
        write(source, "Chapter 1\r\n\r\n\r\nbody\r\n\r\nChapter 2\r\n\r\nend");
        TxtContentsRepository.Result result = repository.loadLarge(source, "UTF-8", "none",
                TextDisplayRuleManager.compile(Collections.emptyList()), true,
                source.length(), source.lastModified(), false, () -> {});
        String displayed = TEXT + "\n";
        assertEquals(displayed.length(), result.characters);
        assertEquals(displayed.indexOf("Chapter 2"), result.entries.get(1).position);
        assertEquals(result.version, repository.loadLarge(source, "UTF-8", "none",
                TextDisplayRuleManager.compile(Collections.emptyList()), true,
                source.length(), source.lastModified(), false, () -> {}).version);
    }

    @Test public void changedLargeSourceStampIsRejected() throws Exception {
        File source = temp.newFile("book.txt");
        write(source, TEXT);
        try {
            repository.loadLarge(source, "UTF-8", "none", TextDisplayRuleManager.compile(Collections.emptyList()), false,
                    source.length() + 1, source.lastModified(), false, () -> {});
            fail("Changed source accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
    }

    @Test public void cancellationCannotPublishAResult() throws Exception {
        try {
            repository.loadText("/book.txt", TEXT, false, () -> { throw new InterruptedIOException("cancel"); });
            fail("Cancelled scan returned");
        } catch (InterruptedIOException expected) { assertEquals("cancel", expected.getMessage()); }
    }

    @Test public void detectorUpgradeRebuildsAutomaticCacheWithoutLosingEdits() throws Exception {
        String text = "345===\n\nbody\n\n346===\n\nend";
        TxtContentsRepository.Result result = repository.loadText("/book.txt", text, false, () -> {});
        repository.edit(result, new TxtContentsScanner.Entry("manual:keep", "My title", 9, 0, "", "body", true),
                result.entries.get(0).id, false);
        File[] cached = new File(cache, "txt-contents").listFiles();
        assertNotNull(cached);
        org.json.JSONObject old = new org.json.JSONObject(AtomicUtf8File.read(cached[0]));
        old.put("revision", TxtContentsScanner.REVISION - 1);
        old.put("entries", new org.json.JSONArray());
        AtomicUtf8File.write(cached[0], old.toString());
        TxtContentsRepository.Result upgraded = repository.loadText("/book.txt", text, false, () -> {});
        assertEquals(result.version, upgraded.version);
        assertEquals(2, upgraded.entries.size());
        assertEquals("My title", upgraded.entries.get(0).title);
        assertEquals("346===", upgraded.entries.get(1).title);
    }

    @Test public void revisionTwoCacheRescansForHyphensAndKeepsManualEntries() throws Exception {
        String text = "345---\n\nbody\n\n346---\n\nend";
        TxtContentsRepository.Result result = repository.loadText("/book.txt", text, false, () -> {});
        repository.edit(result, new TxtContentsScanner.Entry("manual:keep", "My title", 8, 0, "", "body", true), null, false);
        File[] cached = new File(cache, "txt-contents").listFiles();
        assertNotNull(cached);
        org.json.JSONObject old = new org.json.JSONObject(AtomicUtf8File.read(cached[0]));
        old.put("revision", 2);
        old.put("entries", new org.json.JSONArray());
        AtomicUtf8File.write(cached[0], old.toString());
        TxtContentsRepository.Result upgraded = repository.loadText("/book.txt", text, false, () -> {});
        assertEquals(result.version, upgraded.version);
        assertEquals(3, upgraded.entries.size());
        assertEquals("345---", upgraded.entries.get(0).title);
        assertEquals("My title", upgraded.entries.get(1).title);
        assertEquals("346---", upgraded.entries.get(2).title);
    }

    @Test public void revisionThreeCacheRescansTwoLineTitlesAndKeepsEdits() throws Exception {
        String text = "345===\n첫 제목\n\n346===\n다음 제목\n\n347===\n끝 제목\n\n"
                + "500화 축하 감사합니다.\n\n800화 축하 감사합니다.";
        TxtContentsRepository.Result result = repository.loadText("/book.txt", text, false, () -> {});
        repository.edit(result, new TxtContentsScanner.Entry("manual:keep", "My title", 0, 0, "", "345===", true),
                "auto:0", false);
        // Represent the old detector's actual output: it missed the blocks and kept only prose.
        org.json.JSONArray stale = new org.json.JSONArray();
        for (String title : new String[] {"500화 축하 감사합니다.", "800화 축하 감사합니다."}) {
            int position = text.indexOf(title);
            stale.put(new org.json.JSONObject().put("id", "auto:" + position).put("title", title)
                    .put("position", position).put("level", 0).put("before", "").put("after", title));
        }
        File[] cached = new File(cache, "txt-contents").listFiles();
        assertNotNull(cached);
        org.json.JSONObject old = new org.json.JSONObject(AtomicUtf8File.read(cached[0]));
        old.put("revision", 3);
        old.put("entries", stale);
        AtomicUtf8File.write(cached[0], old.toString());
        TxtContentsRepository.Result upgraded = repository.loadText("/book.txt", text, false, () -> {});
        assertEquals(result.version, upgraded.version);
        assertEquals(3, upgraded.entries.size());
        assertEquals("My title", upgraded.entries.get(0).title);
        assertEquals("346 — 다음 제목", upgraded.entries.get(1).title);
        assertEquals("347 — 끝 제목", upgraded.entries.get(2).title);
        TxtContentsRepository.Result reused = repository.loadText("/book.txt", text, false, () -> {});
        assertEquals(3, reused.entries.size());
        assertEquals(upgraded.entries.get(1).after, reused.entries.get(1).after);
        for (TxtContentsScanner.Entry entry : reused.entries) assertTrue(text.startsWith(entry.after, entry.position));
    }

    @Test public void largeTextTwoLineBlocksKeepNormalizedAnchors() throws Exception {
        File source = temp.newFile("two-lines.txt");
        write(source, "intro\r\n\r\n\r\n  345===\r\n첫 제목\r\n\r\n\r\n본문\r\n\r\n346===\r\n다음 제목");
        String displayed = "intro\n\n  345===\n첫 제목\n\n본문\n\n346===\n다음 제목\n";
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result result = repository.loadLarge(source, "UTF-8", "none",
                    TextDisplayRuleManager.compile(Collections.emptyList()), true,
                    source.length(), source.lastModified(), false, () -> {});
            assertEquals(displayed.length(), result.characters);
            assertEquals(2, result.entries.size());
            assertEquals("345 — 첫 제목", result.entries.get(0).title);
            assertEquals(displayed.indexOf("345"), result.entries.get(0).position);
            assertEquals(displayed.indexOf("346"), result.entries.get(1).position);
            for (TxtContentsScanner.Entry entry : result.entries) assertTrue(displayed.startsWith(entry.after, entry.position));
        }
    }

    @Test public void normalTextCacheRetainsAllTwoThousandHeadings() throws Exception {
        String text = manyHeadings();
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result result = repository.loadText("/many-headings.txt", text, false, () -> {});
            assertEquals(2000, result.entries.size());
            assertEquals("761 — Title", result.entries.get(760).title);
            assertEquals("2000 — Title", result.entries.get(1999).title);
            assertEquals(text.indexOf("2000===\n"), result.entries.get(1999).position);
        }
    }

    @Test public void largeTextScansToEofAndCacheRetainsAllTwoThousandHeadings() throws Exception {
        File source = temp.newFile("many-headings.txt");
        String text = manyHeadings();
        write(source, text);
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result result = repository.loadLarge(source, "UTF-8", "none",
                    TextDisplayRuleManager.compile(Collections.emptyList()), false,
                    source.length(), source.lastModified(), false, () -> {});
            assertEquals(text.length(), result.characters);
            assertEquals(2000, result.entries.size());
            assertEquals("761 — Title", result.entries.get(760).title);
            assertEquals("2000 — Title", result.entries.get(1999).title);
            assertEquals(text.indexOf("2000===\n"), result.entries.get(1999).position);
        }
    }

    @Test public void revisionFourCacheRebuildsWrappedTitlesWithoutResettingEdits() throws Exception {
        String text = "759===\n\n760===\n\n<--Next title-->\n\n<--Last title-->";
        TxtContentsRepository.Result result = repository.loadText("/changed-style.txt", text, false, () -> {});
        repository.edit(result, new TxtContentsScanner.Entry("manual:keep", "My title", 0, 0, "", "759===", true),
                result.entries.get(0).id, false);
        File[] cached = new File(cache, "txt-contents").listFiles();
        assertNotNull(cached);
        org.json.JSONObject old = new org.json.JSONObject(AtomicUtf8File.read(cached[0]));
        org.json.JSONArray oldEntries = old.getJSONArray("entries");
        old.put("revision", 4);
        old.put("entries", new org.json.JSONArray().put(oldEntries.getJSONObject(0)).put(oldEntries.getJSONObject(1)));
        AtomicUtf8File.write(cached[0], old.toString());
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result upgraded = repository.loadText("/changed-style.txt", text, false, () -> {});
            assertEquals(result.version, upgraded.version);
            assertEquals(4, upgraded.entries.size());
            assertEquals("My title", upgraded.entries.get(0).title);
            assertEquals("760===", upgraded.entries.get(1).title);
            assertEquals("Next title", upgraded.entries.get(2).title);
            assertEquals("Last title", upgraded.entries.get(3).title);
            for (TxtContentsScanner.Entry entry : upgraded.entries) assertTrue(text.startsWith(entry.after, entry.position));
        }
    }

    @Test public void largeTextRetainsHeadingsAfterThe760StyleChangeAndReopen() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int chapter = 1; chapter <= 1571; chapter++) {
            if (chapter <= 760) text.append(chapter).append("===\r\nTitle");
            else text.append("<--Title ").append(chapter).append("-->");
            text.append("\r\n\r\n\r\nbody\r\n\r\n");
        }
        File source = temp.newFile("changed-style.txt");
        write(source, text.toString());
        String displayed = text.toString().replace("\r\n", "\n").replace("\n\n\n", "\n\n");
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result result = repository.loadLarge(source, "UTF-8", "none",
                    TextDisplayRuleManager.compile(Collections.emptyList()), true,
                    source.length(), source.lastModified(), false, () -> {});
            assertEquals(displayed.length(), result.characters);
            assertEquals(1571, result.entries.size());
            assertEquals("Title 761", result.entries.get(760).title);
            assertEquals("Title 1571", result.entries.get(1570).title);
            assertEquals(displayed.indexOf("<--Title 761-->"), result.entries.get(760).position);
            assertEquals(displayed.indexOf("<--Title 1571-->"), result.entries.get(1570).position);
        }
    }

    @Test public void revisionFiveCacheRescansTwoLineEpisodesAndKeepsEdits() throws Exception {
        String text = "761화\n첫 제목\n\n762화\n다음 제목\n\n763화\n끝 제목";
        TxtContentsRepository.Result result = repository.loadText("/episodes.txt", text, false, () -> {});
        repository.edit(result, new TxtContentsScanner.Entry("manual:keep", "My title", 0, 0, "", "761화", true),
                result.entries.get(0).id, false);
        File[] cached = new File(cache, "txt-contents").listFiles();
        assertNotNull(cached);
        org.json.JSONObject old = new org.json.JSONObject(AtomicUtf8File.read(cached[0]));
        old.put("revision", 5);
        old.put("entries", new org.json.JSONArray());
        AtomicUtf8File.write(cached[0], old.toString());
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result upgraded = repository.loadText("/episodes.txt", text, false, () -> {});
            assertEquals(result.version, upgraded.version);
            assertEquals(3, upgraded.entries.size());
            assertEquals("My title", upgraded.entries.get(0).title);
            assertEquals("762화 — 다음 제목", upgraded.entries.get(1).title);
            assertEquals("763화 — 끝 제목", upgraded.entries.get(2).title);
            for (TxtContentsScanner.Entry entry : upgraded.entries) assertTrue(text.startsWith(entry.after, entry.position));
        }
    }

    @Test public void largeTextTwoLineEpisodesKeepNormalizedOffsetsAcrossReopen() throws Exception {
        File source = temp.newFile("episodes.txt");
        write(source, "761화\r\n첫 제목\r\n\r\n\r\n본문\r\n\r\n제762화\r\n다음 제목");
        String displayed = "761화\n첫 제목\n\n본문\n\n제762화\n다음 제목\n";
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result result = repository.loadLarge(source, "UTF-8", "none",
                    TextDisplayRuleManager.compile(Collections.emptyList()), true,
                    source.length(), source.lastModified(), false, () -> {});
            assertEquals(displayed.length(), result.characters);
            assertEquals(2, result.entries.size());
            assertEquals("761화 — 첫 제목", result.entries.get(0).title);
            assertEquals("제762화 — 다음 제목", result.entries.get(1).title);
            assertEquals(displayed.indexOf("제762화"), result.entries.get(1).position);
            for (TxtContentsScanner.Entry entry : result.entries) assertTrue(displayed.startsWith(entry.after, entry.position));
        }
    }

    @Test public void optionChangesInvalidateOnlyAutomaticCacheAndKeepManualAndHiddenEntries() throws Exception {
        TxtContentsRepository.Result initial = repository.loadText("/book.txt", TEXT, false, () -> {});
        repository.edit(initial, new TxtContentsScanner.Entry("manual:keep", "My heading", 0, 0, "", "Chapter 1", true),
                initial.entries.get(0).id, false);
        repository.saveOptions("/book.txt", new TxtContentsOptions(0, true, true, true, true, ""));
        TxtContentsRepository.Result manual = repository.loadText("/book.txt", TEXT, false, () -> {});
        assertEquals(initial.version, manual.version);
        assertFalse(manual.previousEdits);
        assertEquals(1, manual.entries.size());
        assertTrue(manual.entries.get(0).manual);
        repository.saveOptions("/book.txt", TxtContentsOptions.DEFAULT);
        TxtContentsRepository.Result restored = repository.loadText("/book.txt", TEXT, false, () -> {});
        assertEquals(initial.version, restored.version);
        assertEquals(2, restored.entries.size());
        assertEquals("Chapter 2", restored.entries.get(1).title);
    }

    @Test public void optionsArePerPathAndInvalidRulesNeverReplaceSavedOptions() throws Exception {
        TxtContentsOptions saved = new TxtContentsOptions(64, false, false, false, false, "^\\[(.+)\\]$");
        repository.saveOptions("/one.txt", saved);
        assertEquals(saved.cacheKey(), repository.loadOptions("/one.txt").cacheKey());
        assertEquals(TxtContentsOptions.DEFAULT.cacheKey(), repository.loadOptions("/two.txt").cacheKey());
        try { repository.saveOptions("/one.txt", new TxtContentsOptions(64, true, true, true, true, "(")); fail(); }
        catch (TxtContentsOptions.InvalidPattern expected) { assertEquals(1, expected.line); }
        assertEquals(saved.cacheKey(), repository.loadOptions("/one.txt").cacheKey());
        assertEquals("Title", repository.loadText("/one.txt", "[Title]", false, () -> {}).entries.get(0).title);
    }

    @Test public void corruptOptionsAreReportedAndNotSilentlyOverwritten() throws Exception {
        repository.saveOptions("/book.txt", TxtContentsOptions.DEFAULT);
        File[] stored = new File(files, "txt-contents-options").listFiles();
        assertNotNull(stored);
        write(stored[0], "broken");
        try { repository.loadText("/book.txt", TEXT, false, () -> {}); fail(); }
        catch (IOException expected) { assertEquals("broken", AtomicUtf8File.read(stored[0])); }
    }

    @Test public void largeTextOptionsChangeDetectionWithoutChangingDocumentCoordinates() throws Exception {
        File source = temp.newFile("custom.txt");
        write(source, "intro\r\n[First]\r\nbody\r\n[Second]");
        TextDisplayRuleManager.CompiledRules rules = TextDisplayRuleManager.compile(Collections.emptyList());
        TxtContentsRepository.Result original = repository.loadLarge(source, "UTF-8", "none", rules, false,
                source.length(), source.lastModified(), false, () -> {});
        assertTrue(original.entries.isEmpty());
        repository.saveOptions(source.getAbsolutePath(), new TxtContentsOptions(64, false, true, true, true, "^\\[(.+)\\]$"));
        for (int pass = 0; pass < 2; pass++) {
            TxtContentsRepository.Result updated = repository.loadLarge(source, "UTF-8", "none", rules, false,
                    source.length(), source.lastModified(), false, () -> {});
            assertEquals(original.version, updated.version);
            assertEquals(original.characters, updated.characters);
            assertEquals(2, updated.entries.size());
            assertEquals("First", updated.entries.get(0).title);
            assertEquals("intro\n".length(), updated.entries.get(0).position);
        }
    }

    private static String manyHeadings() {
        StringBuilder text = new StringBuilder();
        for (int chapter = 1; chapter <= 2000; chapter++) {
            text.append(chapter).append("===\nTitle\n\nbody\n\n");
        }
        return text.toString();
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
