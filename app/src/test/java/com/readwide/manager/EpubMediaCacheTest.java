package com.readwide.manager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class EpubMediaCacheTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void javaHashCollisionsAndEqualSizedDifferentAudioStaySeparate() throws Exception {
        assertEquals("Aa.mp3".hashCode(), "BB.mp3".hashCode());
        assertFalse(EpubMediaCache.cacheKey("book", "Aa.mp3", 3, 1).equals(
                EpubMediaCache.cacheKey("book", "BB.mp3", 3, 1)));
        File book = zip(new String[] {"Aa.mp3", "BB.mp3"}, new byte[][] {{1,2,3}, {4,5,6}});
        File cache = temp.newFolder();
        try (ZipFile archive = new ZipFile(book)) {
            File a = EpubMediaCache.extract(archive, "Aa.mp3", cache, "book", 10);
            File b = EpubMediaCache.extract(archive, "BB.mp3", cache, "book", 10);
            assertFalse(a.equals(b));
            assertArrayEquals(new byte[] {1,2,3}, Files.readAllBytes(a.toPath()));
            assertArrayEquals(new byte[] {4,5,6}, Files.readAllBytes(b.toPath()));
        }
    }

    @Test public void literalPercentNameIsNotDecodedAgainAndCorruptCacheIsReplaced() throws Exception {
        File book = zip(new String[] {"audio%20.mp3", "audio .mp3"}, new byte[][] {{1,2,3}, {4,5,6}});
        File cache = temp.newFolder();
        try (ZipFile archive = new ZipFile(book)) {
            File output = EpubMediaCache.extract(archive, "audio%20.mp3", cache, "book", 10);
            assertArrayEquals(new byte[] {1,2,3}, Files.readAllBytes(output.toPath()));
            Files.write(output.toPath(), new byte[] {9,9,9}); // same size must not count as valid
            File repaired = EpubMediaCache.extract(archive, "audio%20.mp3", cache, "book", 10);
            assertEquals(output, repaired);
            assertArrayEquals(new byte[] {1,2,3}, Files.readAllBytes(repaired.toPath()));
        }
    }

    @Test public void changedCrcChangesCacheKeyEvenIfPublicationMetadataAndSizeMatch() throws Exception {
        assertFalse(EpubMediaCache.cacheKey("book|10|20", "audio.mp3", 3, 123).equals(
                EpubMediaCache.cacheKey("book|10|20", "audio.mp3", 3, 456)));
        assertFalse(EpubMediaCache.cacheKey("Aa", "audio.mp3", 3, 1).equals(
                EpubMediaCache.cacheKey("BB", "audio.mp3", 3, 1)));
    }

    @Test public void oversizedEntryCreatesNoPartialOutput() throws Exception {
        File book = zip(new String[] {"audio.mp3"}, new byte[][] {{1,2,3}});
        File cache = temp.newFolder();
        try (ZipFile archive = new ZipFile(book)) {
            try {
                EpubMediaCache.extract(archive, "audio.mp3", cache, "book", 2);
                fail("Expected size guard");
            } catch (IOException expected) { assertEquals(0, cache.list().length); }
        }
    }

    private File zip(String[] names, byte[][] data) throws Exception {
        File file = temp.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(data[i]);
                out.closeEntry();
            }
        }
        return file;
    }
}
