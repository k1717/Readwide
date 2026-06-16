package com.readwide.manager;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class MainArchiveExtractionPlannerTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void newStyleRarSplitSelectionQueuesOnlyFirstVolume() throws Exception {
        File parent = tempFolder.getRoot();
        File part1 = write(parent, "comic.part1.rar");
        File part2 = write(parent, "comic.part2.rar");
        File part3 = write(parent, "comic.part3.rar");

        List<File> ready = MainArchiveExtractionPlanner.collectReadyArchives(
                Arrays.asList(part2, part1, part3));

        assertEquals(1, ready.size());
        assertEquals(part1.getCanonicalFile(), ready.get(0).getCanonicalFile());
    }

    @Test
    public void oldStyleRarSplitSelectionQueuesOnlyMainRar() throws Exception {
        File parent = tempFolder.getRoot();
        File rar = write(parent, "comic.rar");
        File r00 = write(parent, "comic.r00");
        File r01 = write(parent, "comic.r01");

        List<File> ready = MainArchiveExtractionPlanner.collectReadyArchives(
                Arrays.asList(r00, r01, rar));

        assertEquals(1, ready.size());
        assertEquals(rar.getCanonicalFile(), ready.get(0).getCanonicalFile());
    }

    @Test
    public void sevenZSplitSelectionQueuesOnlyFirstVolume() throws Exception {
        File parent = tempFolder.getRoot();
        File part1 = write(parent, "comic.7z.001");
        File part2 = write(parent, "comic.7z.002");
        File part3 = write(parent, "comic.7z.003");

        List<File> ready = MainArchiveExtractionPlanner.collectReadyArchives(
                Arrays.asList(part2, part3, part1));

        assertEquals(1, ready.size());
        assertEquals(part1.getCanonicalFile(), ready.get(0).getCanonicalFile());
    }

    @Test
    public void eggSplitSelectionQueuesOnlyFirstVolume() throws Exception {
        File parent = tempFolder.getRoot();
        File part1 = write(parent, "comic.vol1.egg");
        File part2 = write(parent, "comic.vol2.egg");
        File part3 = write(parent, "comic.vol3.egg");

        List<File> ready = MainArchiveExtractionPlanner.collectReadyArchives(
                Arrays.asList(part3, part2, part1));

        assertEquals(1, ready.size());
        assertEquals(part1.getCanonicalFile(), ready.get(0).getCanonicalFile());
    }

    private static File write(File parent, String name) throws Exception {
        File file = new File(parent, name);
        Files.write(file.toPath(), name.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
