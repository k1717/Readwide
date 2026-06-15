package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class NumericSplitArchiveBoundaryTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void genericNumericSplitRejectsGappedChainWhenLaterPartExists() throws Exception {
        File parent = tempFolder.getRoot();
        File part1 = write(parent, "book.zip.001");
        write(parent, "book.zip.003");

        try {
            ArchiveSupport.listEntries(part1, null);
            fail("gapped numeric split chain should not be silently combined");
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            assertTrue(message.contains("missing numeric split archive part"));
            assertTrue(message.contains("book.zip.002"));
        }
    }

    @Test
    public void genericNumericSplitMissingPartIsClassifiedAsCorrupt() {
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(
                        new IOException("Missing numeric split archive part: book.zip.002")));
    }

    private static File write(File parent, String name) throws Exception {
        File file = new File(parent, name);
        Files.write(file.toPath(), name.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
