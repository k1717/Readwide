package com.readwide.manager;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.util.PrefsManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MainArchiveImageOpenControllerTest {
    @Test
    public void directComicOpenKeepsChapterFoldersAheadOfRepeatedBasenames() {
        List<ArchiveSupport.EntryInfo> entries = Arrays.asList(
                image("Part3/2.jpg"),
                image("Part1/2.jpg"),
                image("Part2/1.jpg"),
                image("Part3/1.jpg"),
                image("Part1/1.jpg"),
                image("Part2/2.jpg"),
                image("__MACOSX/._cover.jpg")
        );

        List<ArchiveSupport.EntryInfo> sequence =
                MainArchiveImageOpenController.collectDirectArchiveImages(
                        entries, PrefsManager.SORT_SIZE_LARGE);

        ArrayList<String> paths = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : sequence) paths.add(entry.path);
        assertEquals(Arrays.asList(
                "Part1/1.jpg",
                "Part1/2.jpg",
                "Part2/1.jpg",
                "Part2/2.jpg",
                "Part3/1.jpg",
                "Part3/2.jpg"), paths);
    }

    private static ArchiveSupport.EntryInfo image(String path) {
        return new ArchiveSupport.EntryInfo(path, false, 1L, 0L);
    }
}

