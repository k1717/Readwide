package com.textview.reader;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ArchivePreviewCacheTest {
    @Test
    public void entryCacheNameKeepsFullPathHashToAvoidSanitizedNameCollisions() {
        String nested = ArchivePreviewCache.cacheFileNameForEntry("a/b.png");
        String flat = ArchivePreviewCache.cacheFileNameForEntry("a_b.png");

        assertTrue(nested.endsWith("_b.png"));
        assertTrue(flat.endsWith("_a_b.png"));
        assertNotEquals(nested, flat);
    }

    @Test
    public void archiveFingerprintChangesWhenArchiveMetadataChanges() throws Exception {
        File temp = File.createTempFile("archive-preview-cache", ".zip");
        try {
            java.nio.file.Files.write(temp.toPath(), new byte[] {1, 2, 3});
            temp.setLastModified(1700000000000L);
            String first = ArchivePreviewCache.archiveFingerprint(temp);

            java.nio.file.Files.write(temp.toPath(), new byte[] {1, 2, 3, 4});
            temp.setLastModified(1700000001000L);
            String second = ArchivePreviewCache.archiveFingerprint(temp);

            assertNotEquals(first, second);
            assertEquals(24, first.length());
            assertEquals(24, second.length());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }
}
