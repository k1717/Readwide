package com.textview.reader;

import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.util.PrefsManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchiveEntryListControllerTest {
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

    private static ArchiveSupport.EntryInfo image(String path, long size) {
        return new ArchiveSupport.EntryInfo(path, false, size, 0L);
    }

    private static void assertPaths(List<ArchiveSupport.EntryInfo> entries, String... expected) {
        ArrayList<String> actual = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : entries) actual.add(entry.path);
        assertEquals(Arrays.asList(expected), actual);
    }
}
