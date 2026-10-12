package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LhaVolumeResolverTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void recognizesCanonicalContinuationNames() {
        assertEquals("comic", LhaVolumeResolver.continuationStem("comic.L01"));
        assertEquals("comic", LhaVolumeResolver.continuationStem("comic.l100"));
        for (String name : new String[]{"comic.l00", "comic.l1", "comic.l001", "comic.l2147483648", "comic.lha"}) {
            assertNull(LhaVolumeResolver.continuationStem(name));
        }
        assertEquals(ArchiveSupport.Type.LIBARCHIVE, ArchiveTypeDetector.fromFileName("comic.l01"));
        assertTrue(com.readwide.manager.util.FileUtils.isArchiveFile("comic.l01"));
        assertEquals("comic", ArchiveTypeDetector.outputBaseName(new File("comic.l01"), "fallback"));
    }

    @Test public void continuationSelectionUsesOrderedFirstVolumeAndSharedQueueIdentity() throws Exception {
        File first = part("Comic.LZH");
        File second = part("comic.l01");
        File third = part("comic.l02");
        List<File> parts = LhaVolumeResolver.resolve(third);
        assertEquals(java.util.Arrays.asList(first, second, third), parts);
        assertEquals(first, ArchiveSupport.normalizeExtractionQueueArchive(second));
        assertThrows(UnsupportedOperationException.class, () -> parts.add(first));
    }

    @Test public void ordinarySameStemArchivesRemainIndependent() throws Exception {
        File first = part("comic.lha");
        File other = part("comic.lzh");
        assertEquals(java.util.Collections.singletonList(first), LhaVolumeResolver.resolve(first));
        assertEquals(java.util.Collections.singletonList(other), LhaVolumeResolver.resolve(other));
        part("comic.l01");
        assertThrows(IOException.class, () -> LhaVolumeResolver.resolve(first));
    }

    @Test public void rejectsMissingFirstAndInternalGaps() throws Exception {
        File second = part("comic.l01");
        assertThrows(IOException.class, () -> LhaVolumeResolver.resolve(second));
        File first = part("comic.lha");
        part("comic.l03");
        assertThrows(IOException.class, () -> LhaVolumeResolver.resolve(first));
    }

    @Test public void rejectsEmptyAndDirectoryParts() throws Exception {
        File first = part("comic.lha");
        File second = temporary.newFile("comic.l01");
        assertThrows(IOException.class, () -> LhaVolumeResolver.resolve(first));
        Files.write(second.toPath(), new byte[]{1});
        temporary.newFolder("comic.l02");
        assertThrows(IOException.class, () -> LhaVolumeResolver.resolve(first));
    }

    @Test public void allVolumeStampsInvalidatePreviewOnChangeOrNewVolume() throws Exception {
        File first = part("comic.lha");
        File second = part("comic.l01");
        ArchiveSourceSnapshot initial = ArchiveSourceSnapshot.capture(first);
        assertTrue(initial.matches(first));
        assertEquals(initial.cacheFingerprint(), ArchiveSourceSnapshot.capture(second).cacheFingerprint());
        Files.write(second.toPath(), new byte[]{1, 2, 3});
        assertFalse(initial.matches(first));
        ArchiveSourceSnapshot next = ArchiveSourceSnapshot.capture(first);
        part("comic.l02");
        assertFalse(next.matches(first));
    }

    @Test public void cancellationSurvivesDiscovery() throws Exception {
        File first = part("comic.lha");
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, () -> LhaVolumeResolver.resolve(first)); }
        finally { Thread.interrupted(); }
    }

    private File part(String name) throws IOException {
        File file = temporary.newFile(name);
        Files.write(file.toPath(), new byte[]{1});
        return file;
    }
}
