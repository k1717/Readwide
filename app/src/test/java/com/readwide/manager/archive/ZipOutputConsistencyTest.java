package com.readwide.manager.archive;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class ZipOutputConsistencyTest {
    @Parameterized.Parameters(name = "split={0}")
    public static Collection<Object[]> variants() { return Arrays.asList(new Object[][]{{false}, {true}}); }
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final boolean split;
    public ZipOutputConsistencyTest(boolean split) { this.split = split; }

    @Test public void fileThenDirectoryFailsBeforeWritingPrefix() throws Exception { reject("item", "item/"); }
    @Test public void directoryThenFileFailsBeforeWritingPrefix() throws Exception { reject("item/", "item"); }
    @Test public void fileThenChildFailsBeforeWritingPrefix() throws Exception { reject("item", "item/page"); }
    @Test public void childThenFileFailsBeforeWritingPrefix() throws Exception { reject("item/page", "item"); }

    private void reject(String first, String second) throws Exception {
        File archive = write(new File(temp.newFolder(), "book.zip"), split, "prefix", first, second);
        File target = temp.newFolder(), prefix = new File(target, "prefix");
        ArchiveTestTextFiles.writeString(prefix.toPath(), "keep");
        try { ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null); fail("Collision accepted"); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("collision"));
            assertEquals("keep", ArchiveTestTextFiles.readString(prefix.toPath()));
            assertEquals(1, Objects.requireNonNull(target.list()).length);
        }
    }

    @Test public void existingRegularFileCannotSatisfyDirectoryEntry() throws Exception {
        File archive = write(new File(temp.newFolder(), "book.zip"), split, "prefix", "item/");
        File target = temp.newFolder(), existing = new File(target, "item");
        ArchiveTestTextFiles.writeString(existing.toPath(), "keep");
        try { ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null); fail(); }
        catch (IOException expected) {
            assertEquals("keep", ArchiveTestTextFiles.readString(existing.toPath()));
            assertFalse(new File(target, "prefix").exists());
        }
    }

    @Test public void publicBulkFailurePreservesExistingDirectory() throws Exception {
        File archive = write(new File(temp.newFolder(), "book.zip"), split, "prefix", "item", "item/");
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        File target = temp.newFolder(), existing = new File(target, "sentinel");
        ArchiveTestTextFiles.writeString(existing.toPath(), "keep");
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive, target, true, null, null).success);
        assertEquals("keep", ArchiveTestTextFiles.readString(existing.toPath()));
        assertEquals(1, Objects.requireNonNull(target.list()).length);
    }

    @Test public void ordinaryDirectoriesAndChildrenStillExtract() throws Exception {
        File archive = write(new File(temp.newFolder(), "book.zip"), split, "item/", "item/page", "other");
        File target = temp.newFolder();
        assertTrue(ZipxAesArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null));
        assertTrue(new File(target, "item").isDirectory());
        assertEquals("payload", ArchiveTestTextFiles.readString(new File(target, "item/page").toPath()));
    }

    static File write(File file, boolean split, String... names) throws Exception {
        try (ZipArchiveOutputStream zip = split ? new ZipArchiveOutputStream(file, 65536L)
                : new ZipArchiveOutputStream(file)) {
            for (String name : names) {
                boolean directory = name.endsWith("/");
                byte[] data = directory ? new byte[0] : "payload".getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                if (!directory) try (BZip2CompressorOutputStream codec = new BZip2CompressorOutputStream(packed)) {
                    codec.write(data);
                }
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                entry.setMethod(directory ? 0 : 12);
                entry.setSize(data.length); entry.setCompressedSize(packed.size());
                CRC32 crc = new CRC32(); crc.update(data); entry.setCrc(crc.getValue());
                if (split) entry.setComment("x".repeat(40000));
                zip.addRawArchiveEntry(entry, new ByteArrayInputStream(packed.toByteArray()));
            }
        }
        return file;
    }
}
