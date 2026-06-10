package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ArchiveFixtureMatrixReportTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void generate_reportsZipListAndExtract() throws Exception {
        File zip = new File(tempFolder.getRoot(), "sample.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("hello.txt"));
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        ArchiveFixtureMatrixReport report = ArchiveFixtureMatrixReport.generate(
                tempFolder.getRoot(), null, tempFolder.newFolder("probes"));

        assertEquals(1, report.rows().size());
        ArchiveFixtureMatrixReport.Row row = report.rows().get(0);
        assertEquals(ArchiveSupport.Type.ZIP, row.type);
        assertEquals(ArchiveFixtureMatrixReport.Status.OK, row.listStatus);
        assertEquals(ArchiveFixtureMatrixReport.Status.EXTRACT_OK, row.extractStatus);
        assertTrue(report.toMarkdown().contains("Archive fixture matrix"));
        assertTrue(report.toMarkdown().contains("hello.txt"));
    }
}
