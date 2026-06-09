package com.textview.reader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class ImageSequenceStateTest {
    @Test
    public void normalizeMetadataLists_addsMissingAndTrimsExtraMetadata() {
        ArrayList<String> paths = new ArrayList<>(Arrays.asList("C:\\img\\001.jpg", "C:\\img\\002.png"));
        ArrayList<String> names = new ArrayList<>(Arrays.asList("one", "extra", "too-many"));
        ArrayList<String> entries = new ArrayList<>();

        ImageSequenceState.normalizeMetadataLists(paths, names, entries);

        assertEquals(Arrays.asList("one", "extra"), names);
        assertEquals(Arrays.asList("", ""), entries);
    }

    @Test
    public void applyRename_updatesPathAndDisplayNameAtSameIndex() {
        ArrayList<String> paths = new ArrayList<>(Arrays.asList("a.jpg", "b.jpg"));
        ArrayList<String> names = new ArrayList<>(Arrays.asList("A", "B"));

        int index = ImageSequenceState.applyRename(paths, names, "b.jpg", "renamed.jpg", "Renamed");

        assertEquals(1, index);
        assertEquals(Arrays.asList("a.jpg", "renamed.jpg"), paths);
        assertEquals(Arrays.asList("A", "Renamed"), names);
    }

    @Test
    public void removePath_removesParallelMetadataAndClampsCurrentIndex() {
        ArrayList<String> paths = new ArrayList<>(Arrays.asList("a.jpg", "b.jpg", "c.jpg"));
        ArrayList<String> names = new ArrayList<>(Arrays.asList("A", "B", "C"));
        ArrayList<String> entries = new ArrayList<>(Arrays.asList("a", "b", "c"));

        ImageSequenceState.RemoveResult result =
                ImageSequenceState.removePath(paths, names, entries, "c.jpg", 2);

        assertFalse(result.empty);
        assertEquals(2, result.removedIndex);
        assertEquals(1, result.currentIndex);
        assertEquals("b.jpg", result.currentPath);
        assertEquals(Arrays.asList("a.jpg", "b.jpg"), paths);
        assertEquals(Arrays.asList("A", "B"), names);
        assertEquals(Arrays.asList("a", "b"), entries);
    }

    @Test
    public void removePath_reportsEmptyWhenLastImageRemoved() {
        ArrayList<String> paths = new ArrayList<>(Arrays.asList("only.jpg"));
        ArrayList<String> names = new ArrayList<>(Arrays.asList("Only"));
        ArrayList<String> entries = new ArrayList<>(Arrays.asList("entry"));

        ImageSequenceState.RemoveResult result =
                ImageSequenceState.removePath(paths, names, entries, "only.jpg", 0);

        assertTrue(result.empty);
        assertEquals(0, result.currentIndex);
        assertEquals(null, result.currentPath);
        assertTrue(paths.isEmpty());
        assertTrue(names.isEmpty());
        assertTrue(entries.isEmpty());
    }

    @Test
    public void entryPathAt_returnsOnlyCurrentValidEntry() {
        ArrayList<String> entries = new ArrayList<>(Arrays.asList("a", "b"));

        assertEquals(null, ImageSequenceState.entryPathAt(entries, -1));
        assertEquals("a", ImageSequenceState.entryPathAt(entries, 0));
        assertEquals("b", ImageSequenceState.entryPathAt(entries, 1));
        assertEquals(null, ImageSequenceState.entryPathAt(entries, 2));
    }
}
