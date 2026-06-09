package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.GZIPOutputStream;

public class ArchivePasswordDetectionTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void requiresPasswordForExtraction_tarFamilyReturnsFalse() throws Exception {
        File tar = buildTar("sample.tar");
        File tgz = buildTarGz("sample.tar.gz");

        assertFalse(ArchiveSupport.requiresPasswordForExtraction(tar));
        assertFalse(ArchiveSupport.requiresPasswordForExtraction(tgz));
    }

    @Test
    public void requiresPasswordForExtraction_singleUnixCompressionReturnsFalse() throws Exception {
        File gzip = tempFolder.newFile("sample.txt.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(gzip))) {
            out.write("payload".getBytes(StandardCharsets.UTF_8));
        }

        assertFalse(ArchiveSupport.requiresPasswordForExtraction(gzip));
    }

    @Test
    public void requiresPasswordForExtraction_alzipMalformedBoundaryIsConservative() throws Exception {
        File alz = writeStubArchive("sample.alz", "ALZ\1");
        File egg = writeStubArchive("sample.egg", "EGGA");

        assertTrue(ArchiveSupport.requiresPasswordForExtraction(alz));
        assertFalse(ArchiveSupport.requiresPasswordForExtraction(egg));
    }

    private File buildTar(String name) throws Exception {
        File archive = tempFolder.newFile(name);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new FileOutputStream(archive))) {
            addTarEntry(tar, "file.txt", "payload".getBytes(StandardCharsets.UTF_8));
        }
        return archive;
    }

    private File buildTarGz(String name) throws Exception {
        File archive = tempFolder.newFile(name);
        try (GZIPOutputStream gzip = new GZIPOutputStream(new FileOutputStream(archive));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            addTarEntry(tar, "file.txt", "payload".getBytes(StandardCharsets.UTF_8));
        }
        return archive;
    }

    private void addTarEntry(TarArchiveOutputStream tar, String name, byte[] payload) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(payload.length);
        tar.putArchiveEntry(entry);
        tar.write(payload);
        tar.closeArchiveEntry();
    }

    private File writeStubArchive(String name, String signature) throws Exception {
        File archive = tempFolder.newFile(name);
        Files.write(archive.toPath(), signature.getBytes(StandardCharsets.ISO_8859_1));
        return archive;
    }
}
