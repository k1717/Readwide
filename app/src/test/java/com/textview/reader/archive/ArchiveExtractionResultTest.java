package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class ArchiveExtractionResultTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void extractArchiveDetailed_malformedEggReportsFailed() throws Exception {
        File egg = writeStubArchive("sample.egg", "EGGA");
        File destination = new File(tempFolder.getRoot(), "egg-out");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                egg,
                destination,
                false,
                null,
                null);

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertFalse(destination.exists());
    }

    @Test
    public void extractArchiveDetailed_malformedEggWithPasswordReportsFailed() throws Exception {
        File egg = writeStubArchive("sample.egg", "EGGA");
        File destination = new File(tempFolder.getRoot(), "egg-out");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                egg,
                destination,
                false,
                "secret".toCharArray(),
                null);

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertFalse(destination.exists());
    }

    @Test
    public void extractSingleEntryDetailed_malformedEggReportsFailed() throws Exception {
        File egg = writeStubArchive("sample.egg", "EGGA");
        File out = new File(tempFolder.getRoot(), "entry-out.txt");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                egg,
                "entry.txt",
                out,
                null);

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertFalse(out.exists());
    }

    @Test
    public void extractSingleEntryDetailed_malformedEggWithPasswordReportsFailed() throws Exception {
        File egg = writeStubArchive("sample.egg", "EGGA");
        File out = new File(tempFolder.getRoot(), "entry-out.txt");

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                egg,
                "entry.txt",
                out,
                "secret".toCharArray());

        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertFalse(out.exists());
    }

    private File writeStubArchive(String name, String signature) throws Exception {
        File archive = tempFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(signature.getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[] {0, 1, 2, 3});
        }
        return archive;
    }
}
