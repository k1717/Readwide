package com.readwide.manager;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.util.PrefsManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ArchiveEntryListControllerTest {
    @Test public void restoreComicSequenceKeepsChaptersInNaturalOrder() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(
                image("Part2/1.jpg", 1), image("Part1/2.jpg", 1), image("Part1/1.jpg", 1));
        assertPaths(ArchiveEntryListController.restoreImageSequence(entries, null, null,
                false, PrefsManager.SORT_NAME_DESC, "Part1/2.jpg"),
                "Part1/1.jpg", "Part1/2.jpg", "Part2/1.jpg");
    }

    @Test public void restorePreviewKeepsFolderFilterAndSortOrder() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(
                image("folder/page1.jpg", 1), image("folder/page2.jpg", 20),
                image("folder/cover.jpg", 30), image("folder/sub/page3.jpg", 40),
                image("other/page4.jpg", 50));
        assertPaths(ArchiveEntryListController.restoreImageSequence(entries, "folder/", "page",
                true, PrefsManager.SORT_SIZE_LARGE, "folder/page1.jpg"),
                "folder/page2.jpg", "folder/page1.jpg");
    }

    @Test public void restoreMissingSelectionStillReturnsAvailableFolderImages() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(
                image("folder/1.jpg", 1), image("other/1.jpg", 1));
        assertPaths(ArchiveEntryListController.restoreImageSequence(entries, "folder/", "",
                false, PrefsManager.SORT_NAME_ASC, "folder/removed.jpg"), "folder/1.jpg");
    }

    @Test
    public void comicImageSequenceUsesFullPathNaturalOrderAndIgnoresArchiveSortMode() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(
                image("vol10/001.jpg", 10),
                image("vol1/002.jpg", 200),
                image("vol2/001.jpg", 2),
                image("vol1/001.jpg", 300),
                image("__MACOSX/._001.jpg", 1),
                image("vol1/._002.jpg", 1)
        );

        List<ArchiveSupport.EntryInfo> sequence = ArchiveEntryListController.collectImageSequence(
                entries,
                "",
                null,
                PrefsManager.SORT_SIZE_LARGE);

        assertPaths(sequence,
                "vol1/001.jpg",
                "vol1/002.jpg",
                "vol2/001.jpg",
                "vol10/001.jpg");
    }

    @Test
    public void comicImageSequenceRespectsCurrentFolderPrefixButStillUsesFullPathOrder() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(
                image("book/chapter2/page1.jpg", 1),
                image("book/chapter1/page2.jpg", 1),
                image("book/chapter1/page1.jpg", 1),
                image("other/page1.jpg", 1)
        );

        List<ArchiveSupport.EntryInfo> sequence = ArchiveEntryListController.collectImageSequence(
                entries,
                "book/",
                null,
                PrefsManager.SORT_NAME_DESC);

        assertPaths(sequence,
                "book/chapter1/page1.jpg",
                "book/chapter1/page2.jpg",
                "book/chapter2/page1.jpg");
    }


    @Test
    public void comicImageSequenceUsesSameOrderForGenericArchiveEntryLists() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(
                image("chapter10/page1.png", 1),
                image("chapter2/page10.png", 1),
                image("chapter2/page2.png", 1),
                image("chapter1/page1.png", 1)
        );

        List<ArchiveSupport.EntryInfo> sequence = ArchiveEntryListController.collectImageSequence(
                entries,
                "",
                null,
                PrefsManager.SORT_DATE_NEW);

        assertPaths(sequence,
                "chapter1/page1.png",
                "chapter2/page2.png",
                "chapter2/page10.png",
                "chapter10/page1.png");
    }


    @Test
    public void previewImageSequencePreservesVisiblePreviewOrder() {
        List<ArchiveSupport.EntryInfo> visible = Arrays.asList(
                image("folder/page10.jpg", 10),
                image("folder/page2.jpg", 20),
                new ArchiveSupport.EntryInfo("folder/sub/", true, -1L, 0L),
                image("folder/page1.jpg", 30)
        );

        List<ArchiveSupport.EntryInfo> sequence = ArchiveEntryListController.collectPreviewOrderedImageSequence(
                visible,
                visible.get(1));

        assertPaths(sequence,
                "folder/page10.jpg",
                "folder/page2.jpg",
                "folder/page1.jpg");
    }

    @Test
    public void previewImageSequenceKeepsSelectedFallbackWhenFilteredOut() {
        ArchiveSupport.EntryInfo selected = image("folder/page5.jpg", 5);
        List<ArchiveSupport.EntryInfo> visible = Arrays.asList(
                image("folder/page1.jpg", 1),
                image("folder/page2.jpg", 2)
        );

        List<ArchiveSupport.EntryInfo> sequence = ArchiveEntryListController.collectPreviewOrderedImageSequence(
                visible,
                selected);

        assertPaths(sequence,
                "folder/page1.jpg",
                "folder/page2.jpg",
                "folder/page5.jpg");
    }

    @Test
    public void comicImageSequenceRejectsMacResourceForkImages() {
        assertFalse(ArchiveEntryListController.isArchiveImageSequenceEntry(image("__MACOSX/cover.jpg", 1)));
        assertFalse(ArchiveEntryListController.isArchiveImageSequenceEntry(image("chapter/._page001.jpg", 1)));
        assertTrue(ArchiveEntryListController.isArchiveImageSequenceEntry(image("chapter/page001.jpg", 1)));
    }

    @Test public void folderViewReusesTheListingAcrossQueryAndFilterChanges() {
        CountingEntries entries = new CountingEntries(Arrays.asList(
                image("chapter/page2.jpg", 2), image("other/page.jpg", 9),
                image("chapter/page1.jpg", 1), image("chapter/note.txt", 3)));
        ArchiveEntryListController.FolderView view = new ArchiveEntryListController.FolderView();
        assertPaths(view.visibleChildren(entries, "chapter/", "", ArchiveEntryListController.FILTER_ALL,
                PrefsManager.SORT_NAME_ASC), "chapter/note.txt", "chapter/page1.jpg", "chapter/page2.jpg");
        int reads = entries.reads;
        assertEquals(entries.size(), reads);
        assertPaths(view.visibleChildren(entries, new String("chapter/"), "PAGE",
                ArchiveEntryListController.FILTER_IMAGE, PrefsManager.SORT_NAME_ASC),
                "chapter/page1.jpg", "chapter/page2.jpg");
        assertPaths(view.visibleChildren(entries, "chapter/", "", ArchiveEntryListController.FILTER_TXT,
                PrefsManager.SORT_NAME_ASC), "chapter/note.txt");
        assertEquals("Query/filter changes must not revisit the full archive", reads, entries.reads);
    }

    @Test public void folderViewAgreesWithUncachedOrderAndMetadataForEverySortAndFilter() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(null,
                image("chapter/page10.jpg", 1), image("chapter/PAGE2.jpg", 5),
                image("chapter/page2.jpg", 7), image("chapter/ page3.jpg", 3),
                image("chapter/note.txt", 20), image("chapter/book.epub", 8),
                new ArchiveSupport.EntryInfo("chapter/report.pdf", false, 9, 40L), image("chapter/inner.zip", 10),
                image("chapter/word.docx", 11), image("chapter/sub/page.jpg", 100),
                image("other/page.jpg", 40), image("chapter/page10.jpg", 50));
        ArchiveEntryListController.FolderView view = new ArchiveEntryListController.FolderView();
        for (int sort = PrefsManager.SORT_NAME_ASC; sort <= PrefsManager.SORT_TYPE; sort++) {
            for (int filter = ArchiveEntryListController.FILTER_ALL;
                    filter <= ArchiveEntryListController.FILTER_IMAGE; filter++) {
                for (String query : new String[]{null, "", "pAgE", " ", "sub", "missing"}) {
                    List<ArchiveSupport.EntryInfo> expected = ArchiveEntryListController.visibleChildren(
                            entries, "chapter/", query, filter, sort);
                    List<ArchiveSupport.EntryInfo> actual = view.visibleChildren(
                            entries, "chapter/", query, filter, sort);
                    assertEntrySequence(expected, actual);
                }
            }
        }
    }

    @Test public void folderViewRefreshesOnFolderSortAndSourceReplacement() {
        CountingEntries entries = new CountingEntries(Arrays.asList(image("one/1.jpg", 1),
                image("one/2.jpg", 2), image("two/3.jpg", 3)));
        ArchiveEntryListController.FolderView view = new ArchiveEntryListController.FolderView();
        view.visibleChildren(entries, "one/", "", 0, PrefsManager.SORT_NAME_ASC);
        assertPaths(view.visibleChildren(entries, "two/", "", 0, PrefsManager.SORT_NAME_ASC), "two/3.jpg");
        assertEquals(2 * entries.size(), entries.reads);
        assertPaths(view.visibleChildren(entries, "one/", "", 0, PrefsManager.SORT_NAME_DESC),
                "one/2.jpg", "one/1.jpg");
        assertEquals(3 * entries.size(), entries.reads);
        assertPaths(view.visibleChildren(Arrays.asList(image("one/new.jpg", 9)), "one/", "", 0,
                PrefsManager.SORT_NAME_DESC), "one/new.jpg");
    }

    @Test public void changingSortDoesNotMakeStableTiesInheritPreviousSortOrder() {
        ArchiveSupport.EntryInfo first = image("chapter/a.jpg", 1), second = image("chapter/A.jpg", 5);
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(first, second);
        ArchiveEntryListController.FolderView view = new ArchiveEntryListController.FolderView();
        assertSame(second, view.visibleChildren(entries, "chapter/", "", 0, PrefsManager.SORT_SIZE_LARGE).get(0));
        assertSame(first, view.visibleChildren(entries, "chapter/", "", 0, PrefsManager.SORT_NAME_ASC).get(0));
    }

    @Test public void mutatingReturnedRowsDoesNotChangeTheCachedFolder() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(image("folder/1.jpg", 1), image("folder/2.jpg", 2));
        ArchiveEntryListController.FolderView view = new ArchiveEntryListController.FolderView();
        view.visibleChildren(entries, "folder/", "", 0, 0).clear();
        List<ArchiveSupport.EntryInfo> filtered = view.visibleChildren(entries, "folder/", "1", 0, 0);
        filtered.add(image("folder/injected.jpg", 5));
        assertPaths(view.visibleChildren(entries, "folder/", "", 0, 0), "folder/1.jpg", "folder/2.jpg");
    }

    @Test public void emptyFolderCacheDoesNotRetainThePreviousFolderRows() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(image("folder/1.jpg", 1));
        ArchiveEntryListController.FolderView view = new ArchiveEntryListController.FolderView();
        view.visibleChildren(entries, "folder/", "", 0, 0);
        assertTrue(view.visibleChildren(entries, "missing/", "", 0, 0).isEmpty());
        assertTrue(view.visibleChildren(entries, "missing/", "page", 0, 0).isEmpty());
        assertPaths(view.visibleChildren(entries, "folder/", "", 0, 0), "folder/1.jpg");
    }

    @Test public void metadataComponentScanKeepsLegacyImageExclusions() {
        String[] parts = {"", "chapter", "__MACOSX", "__macosx-more", ".DS_STORE", ".ds_store_more",
                "._", "._chapter", "visible", "한글", "İ", "ſ", "//"};
        for (String a : parts) for (String b : parts) {
            ArchiveSupport.EntryInfo entry = image(a + "/" + b + "/PAGE.JPG", 1);
            assertEquals(entry.path, legacyImageEntry(entry), ArchiveEntryListController.isArchiveImageSequenceEntry(entry));
        }
        assertFalse(ArchiveEntryListController.isArchiveImageSequenceEntry(image("folder\\._nested\\page.jpg", 1)));
        assertFalse(ArchiveEntryListController.isArchiveImageSequenceEntry(image("folder/note.txt", 1)));
        assertFalse(ArchiveEntryListController.isArchiveImageSequenceEntry(new ArchiveSupport.EntryInfo("folder.jpg", true, 0, 0)));
    }

    @Test public void imageSequenceStillFallsBackToTheSelectedEntriesParent() {
        ArchiveSupport.EntryInfo selected = image("other/2.jpg", 2);
        assertPaths(ArchiveEntryListController.collectImageSequence(Arrays.asList(
                image("chapter/1.jpg", 1), selected, image("other/1.jpg", 1),
                image("other/._fork.jpg", 1)), "chapter/", selected, PrefsManager.SORT_SIZE_LARGE),
                "other/1.jpg", "other/2.jpg");
    }

    private static boolean legacyImageEntry(ArchiveSupport.EntryInfo entry) {
        if (entry.directory || !com.readwide.manager.util.FileUtils.isImageFile(entry.name())) return false;
        String path = entry.path.replace('\\', '/');
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("__macosx/") || lower.contains("/__macosx/")) return false;
        for (String part : path.split("/")) {
            String value = part.toLowerCase(java.util.Locale.ROOT);
            if (value.equals(".ds_store") || value.startsWith("._")) return false;
        }
        return !path.isEmpty();
    }

    private static void assertEntrySequence(List<ArchiveSupport.EntryInfo> expected, List<ArchiveSupport.EntryInfo> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            ArchiveSupport.EntryInfo a = expected.get(i), b = actual.get(i);
            assertEquals(a.path, b.path);
            assertEquals(a.directory, b.directory);
            assertEquals(a.size, b.size);
            assertEquals(a.timeMillis, b.timeMillis);
        }
    }

    private static final class CountingEntries extends java.util.AbstractList<ArchiveSupport.EntryInfo> {
        private final List<ArchiveSupport.EntryInfo> entries;
        int reads;
        CountingEntries(List<ArchiveSupport.EntryInfo> entries) { this.entries = entries; }
        @Override public ArchiveSupport.EntryInfo get(int index) { reads++; return entries.get(index); }
        @Override public int size() { return entries.size(); }
    }

    private static ArchiveSupport.EntryInfo image(String path, long size) {
        return new ArchiveSupport.EntryInfo(path, false, size, 0L);
    }

    private static void assertPaths(List<ArchiveSupport.EntryInfo> entries, String... expected) {
        ArrayList<String> actual = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : entries) actual.add(entry.path);
        assertEquals(Arrays.asList(expected), actual);
    }
}
