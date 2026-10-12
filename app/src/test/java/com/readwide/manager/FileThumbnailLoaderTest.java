package com.readwide.manager;

import org.junit.Test;

import java.io.File;
import java.io.FileFilter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileThumbnailLoaderTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder();

    @Test public void archiveCoverIdentityIncludesLaterVolumes() throws Exception {
        File first = temp.newFile("comic.part1.rar"), second = temp.newFile("comic.part2.rar");
        java.nio.file.Files.write(first.toPath(), new byte[]{1, 2, 3});
        java.nio.file.Files.write(second.toPath(), new byte[]{1, 2, 3});
        FileThumbnailLoader.SourceStamp stamp = FileThumbnailLoader.SourceStamp.capture(first);
        String identity = FileThumbnailLoader.sourceIdentity(first);
        assertTrue(stamp.matches(first));
        java.nio.file.Files.write(second.toPath(), new byte[]{1, 2, 3, 4});
        assertFalse(stamp.matches(first));
        assertFalse(identity.equals(FileThumbnailLoader.sourceIdentity(first)));
    }

    @Test public void ordinaryImageStampKeepsSingleFileFastPath() throws Exception {
        File image = temp.newFile("page.jpg");
        FileThumbnailLoader.SourceStamp stamp = FileThumbnailLoader.SourceStamp.capture(image);
        org.junit.Assert.assertNull(stamp.archiveSnapshot);
        assertTrue(stamp.matches(image));
        assertEquals(FileThumbnailLoader.sourceIdentity(image), FileThumbnailLoader.sourceIdentity(image));
    }

    @Test public void boundedSelectionMatchesStableFullSortIncludingTies() {
        Random random = new Random(1720L);
        Comparator<int[]> order = Comparator.comparingInt(item -> item[0]);
        List<int[]> all = new ArrayList<>();
        List<int[]> selected = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            int[] item = {random.nextInt(100), i};
            all.add(item);
            FileThumbnailLoader.offerCoverCandidate(selected, item, order);
            assertTrue(selected.size() <= 8);
            if (i < 12 || i % 997 == 0) assertPrefix(all, selected, order);
        }
        assertPrefix(all, selected, order);
    }

    @Test public void boundedSelectionHandlesAscendingDescendingAndEqualKeys() {
        Comparator<Integer> order = Integer::compare;
        for (int mode = 0; mode < 3; mode++) {
            List<Integer> all = new ArrayList<>();
            List<Integer> selected = new ArrayList<>();
            assertPrefix(all, selected, order);
            for (int i = 0; i < 40; i++) {
                int value = mode == 0 ? i : mode == 1 ? 40 - i : 7;
                all.add(value);
                FileThumbnailLoader.offerCoverCandidate(selected, value, order);
                assertPrefix(all, selected, order);
            }
        }
    }

    private static <T> void assertPrefix(List<T> all, List<T> selected, Comparator<T> order) {
        List<T> sorted = new ArrayList<>(all);
        sorted.sort(order);
        assertEquals(sorted.subList(0, Math.min(8, sorted.size())), selected);
    }

    @Test public void folderSelectionStillPrioritizesImagesThenNaturalNames() throws Exception {
        File root = new File("folder-test") {
            @Override public File[] listFiles(FileFilter filter) {
                return new File[] {new File("a.pdf"), new File("page10.jpg"),
                        new File("b.epub"), new File("page2.jpg")};
            }
        };
        Method select = FileThumbnailLoader.class.getDeclaredMethod(
                "directThumbnailSources", File.class, boolean.class);
        select.setAccessible(true);
        @SuppressWarnings("unchecked") List<File> selected = (List<File>) select.invoke(null, root, false);
        assertEquals(java.util.Arrays.asList(new File("page2.jpg"), new File("page10.jpg"),
                new File("a.pdf"), new File("b.epub")), selected);
    }

    @Test public void trimSnapshotsMetadataOnceAndDeletesOldestFirst() throws Exception {
        CountingFile newest = new CountingFile("new.png", 30L << 20, 30);
        CountingFile oldest = new CountingFile("old.png", 30L << 20, 10);
        CountingFile middle = new CountingFile("middle.png", 30L << 20, 20);
        File root = new File("thumbnail-test") {
            @Override public File[] listFiles(FileFilter filter) {
                return new File[] {newest, oldest, middle};
            }
        };
        Method trim = FileThumbnailLoader.class.getDeclaredMethod("trimDiskCache", File.class);
        trim.setAccessible(true);
        trim.invoke(null, root);
        assertTrue(oldest.deleted);
        assertFalse(middle.deleted);
        assertFalse(newest.deleted);
        for (CountingFile file : new CountingFile[] {newest, oldest, middle}) {
            assertEquals(1, file.lengthReads);
            assertEquals(1, file.modifiedReads);
        }
    }

    private static final class CountingFile extends File {
        final long bytes;
        final long modified;
        int lengthReads;
        int modifiedReads;
        boolean deleted;
        CountingFile(String name, long bytes, long modified) {
            super(name);
            this.bytes = bytes;
            this.modified = modified;
        }
        @Override public boolean isFile() { return !deleted; }
        @Override public long length() { lengthReads++; return bytes; }
        @Override public long lastModified() { modifiedReads++; return modified; }
        @Override public boolean delete() { deleted = true; return true; }
    }
}
