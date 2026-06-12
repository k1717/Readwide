package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.textview.reader.archive.ArchiveSupport.ExtractionResult;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SingleEntryPartialOutputCleanupTest {
    @Test
    public void missingSingleEntryRemovesExistingOrPartialOutput() throws Exception {
        File dir = createTempDir("single-entry-cleanup");
        File archive = new File(dir, "sample.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("actual.txt"));
            zip.write("ok".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        File out = new File(dir, "out.txt");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write("stale".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(out.exists());

        ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive, "missing.txt", out, null);

        assertFalse(result.success);
        assertFalse("failed single-entry extraction must not leave stale/partial output", out.exists());
    }

    private static File createTempDir(String prefix) throws Exception {
        File root = File.createTempFile(prefix, "");
        if (!root.delete()) throw new IllegalStateException("failed to delete temp file");
        if (!root.mkdirs()) throw new IllegalStateException("failed to create temp dir");
        root.deleteOnExit();
        return root;
    }
}
