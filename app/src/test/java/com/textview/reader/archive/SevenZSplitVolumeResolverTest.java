package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class SevenZSplitVolumeResolverTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void detectsOnlyStandardSevenZSplitParts() {
        assertTrue(SevenZSplitVolumeResolver.isSevenZSplitPartName("book.7z.001"));
        assertTrue(SevenZSplitVolumeResolver.isSevenZSplitPartName("comic.cb7.002"));
        assertFalse(SevenZSplitVolumeResolver.isSevenZSplitPartName("book.zip.001"));
        assertFalse(SevenZSplitVolumeResolver.isSevenZSplitPartName("book.7z.000"));
        assertFalse(SevenZSplitVolumeResolver.isSevenZSplitPartName("book.7z"));
    }

    @Test
    public void resolvesMiddlePartToFirstAndCollectsContiguousVolumes() throws Exception {
        File parent = tempFolder.getRoot();
        File part1 = write(parent, "book.7z.001");
        File part2 = write(parent, "book.7z.002");
        File part3 = write(parent, "book.7z.003");

        SevenZSplitVolumeResolver.VolumeSet volumeSet = SevenZSplitVolumeResolver.resolve(part2);

        assertEquals(part1.getCanonicalFile(), volumeSet.firstPart.getCanonicalFile());
        assertEquals("book.7z", volumeSet.displayName);
        assertEquals(3, volumeSet.parts.size());
        assertEquals(part1.getCanonicalFile(), volumeSet.parts.get(0).getCanonicalFile());
        assertEquals(part2.getCanonicalFile(), volumeSet.parts.get(1).getCanonicalFile());
        assertEquals(part3.getCanonicalFile(), volumeSet.parts.get(2).getCanonicalFile());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsMissingFirstVolume() throws Exception {
        File parent = tempFolder.getRoot();
        File part2 = write(parent, "book.7z.002");
        SevenZSplitVolumeResolver.resolve(part2);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsGappedVolumeSetWhenLaterVolumeExists() throws Exception {
        File parent = tempFolder.getRoot();
        write(parent, "book.7z.001");
        write(parent, "book.7z.003");
        SevenZSplitVolumeResolver.resolve(new File(parent, "book.7z.001"));
    }

    private static File write(File parent, String name) throws Exception {
        File file = new File(parent, name);
        Files.write(file.toPath(), name.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
