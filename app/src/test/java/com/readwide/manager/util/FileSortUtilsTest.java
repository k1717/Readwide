package com.readwide.manager.util;

import com.readwide.manager.archive.ArchiveSupport.EntryInfo;
import com.readwide.manager.model.FileListItem;
import org.junit.Test;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

public class FileSortUtilsTest {
    @Test public void archiveModesPreserveThePreviousOrderingAndTies() {
        List<EntryInfo> original = Arrays.asList(entry("b/10.JPG", false, 5, 1), entry("a/2.png", false, -1, 4),
                entry("chapter10", true, 0, 2), entry("chapter2", true, 0, 3),
                entry("a/1.txt", false, 5, 1), entry("b/1.txt", false, 5, 1));
        for (int mode = 0; mode <= 7; mode++) {
            List<EntryInfo> expected = new ArrayList<>(original);
            expected.sort(reference(mode));
            List<EntryInfo> actual = new NoIndexedAccess<>();
            actual.addAll(original);
            FileSortUtils.sortArchiveEntries(actual, mode);
            assertArrayEquals(expected.toArray(), actual.toArray());
        }
    }

    @Test public void comicSortRetainsFolderPathsBeforeLeafNames() {
        EntryInfo part10 = entry("Part10/1.jpg", false, 1, 1);
        EntryInfo part2Page10 = entry("./Part2//10.jpg", false, 1, 1);
        EntryInfo part2Page2 = entry("Part2/2.jpg", false, 1, 1);
        EntryInfo part1 = entry("Part1/1.jpg", false, 1, 1);
        List<EntryInfo> entries = new NoIndexedAccess<>();
        entries.addAll(Arrays.asList(part10, part2Page10, part2Page2, part1));
        FileSortUtils.sortArchiveImageSequence(entries);
        assertArrayEquals(new Object[]{part1, part2Page2, part2Page10, part10}, entries.toArray());
    }

    @Test public void equivalentComicPathsKeepSizeTimeAndStableTieBreaks() {
        EntryInfo first = entry("a//1.jpg", false, 2, 5);
        EntryInfo tied = entry("./a/1.jpg", false, 2, 5);
        EntryInfo smaller = entry("a/1.jpg", false, 1, 9);
        EntryInfo earlier = entry("a/1.jpg", false, 2, 3);
        List<EntryInfo> entries = new ArrayList<>(Arrays.asList(first, tied, smaller, earlier));
        FileSortUtils.sortArchiveImageSequence(entries);
        assertArrayEquals(new Object[]{smaller, earlier, first, tied}, entries.toArray());
    }

    @Test public void linkedFileSortUsesIteratorsRatherThanQuadraticIndexedAccess() {
        File two = fake("page2.txt", false, 2, 20);
        File ten = fake("page10.txt", false, 10, 10);
        File dir = fake("folder", true, 0, 0);
        List<File> files = new NoIndexedAccess<>();
        files.addAll(Arrays.asList(ten, two, dir));
        assertTrue(FileSortUtils.sortMainFilesCancellable(null, files, PrefsManager.SORT_NAME_ASC));
        assertArrayEquals(new Object[]{dir, two, ten}, files.toArray());
    }

    @Test public void linkedItemSortKeepsObjectsAndLocationMetadata() {
        FileListItem first = item("page2.txt", 2, 20), second = item("page10.txt", 10, 10);
        List<FileListItem> items = new NoIndexedAccess<>();
        items.addAll(Arrays.asList(second, first));
        assertTrue(FileSortUtils.sortMainItems(items, PrefsManager.SORT_NAME_ASC));
        assertArrayEquals(new Object[]{first, second}, items.toArray());
        assertEquals("location", first.getDisplayLocation());
    }

    @Test public void linkedDateRefinementRetainsOverridesAndFallback() {
        FileListItem first = item("one", 1, 50), second = item("two", 1, 10);
        List<FileListItem> items = new NoIndexedAccess<>();
        items.addAll(Arrays.asList(first, second));
        java.util.Map<String, Long> overrides = new java.util.HashMap<>();
        overrides.put(second.getAbsolutePath(), 60L);
        assertTrue(FileSortUtils.sortMainItemsWithDateOverrides(items, PrefsManager.SORT_DATE_NEW, overrides));
        assertArrayEquals(new Object[]{second, first}, items.toArray());
    }

    @Test public void interruptedSortDoesNotPublishAnOrder() {
        FileListItem first = item("page10", 1, 0), second = item("page2", 1, 0);
        List<FileListItem> items = new ArrayList<>(Arrays.asList(first, second));
        Thread.currentThread().interrupt();
        try {
            assertFalse(FileSortUtils.sortMainItems(items, PrefsManager.SORT_NAME_ASC));
            assertArrayEquals(new Object[]{first, second}, items.toArray());
        } finally { Thread.interrupted(); }
    }

    private static EntryInfo entry(String path, boolean directory, long size, long time) {
        return new EntryInfo(path, directory, size, time);
    }
    private static FileListItem item(String name, long size, long date) {
        return new FileListItem(fake(name, false, size, date), false, size, date, date, "location");
    }
    private static File fake(String name, boolean directory, long size, long modified) {
        return new File(name) {
            @Override public boolean isDirectory() { return directory; }
            @Override public long length() { return size; }
            @Override public long lastModified() { return modified; }
        };
    }
    private static final class NoIndexedAccess<T> extends LinkedList<T> {
        @Override public T get(int index) { throw new AssertionError("Indexed read"); }
        @Override public T set(int index, T value) { throw new AssertionError("Indexed write"); }
    }
    private static Comparator<EntryInfo> reference(int mode) {
        return (a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            int cmp;
            switch (mode) {
                case PrefsManager.SORT_NAME_DESC: return NaturalSort.compare(b.name(), a.name());
                case PrefsManager.SORT_DATE_NEW: cmp = Long.compare(b.timeMillis, a.timeMillis); break;
                case PrefsManager.SORT_DATE_OLD: cmp = Long.compare(a.timeMillis, b.timeMillis); break;
                case PrefsManager.SORT_SIZE_LARGE: cmp = Long.compare(b.size, a.size); break;
                case PrefsManager.SORT_SIZE_SMALL: cmp = Long.compare(a.size, b.size); break;
                case PrefsManager.SORT_TYPE: cmp = extension(a.name()).compareTo(extension(b.name())); break;
                default: cmp = 0;
            }
            return cmp != 0 ? cmp : NaturalSort.compare(a.name(), b.name());
        };
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
