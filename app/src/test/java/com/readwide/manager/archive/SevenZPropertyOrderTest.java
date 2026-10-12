package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic header cases; these are not producer interoperability results. */
public class SevenZPropertyOrderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void emptyFileBeforeEmptyStreamKeepsFileAndDirectoryKinds() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = SevenZBcj2ArchiveReader.listEntries(archive(new byte[]{15,1,(byte)128}), null);
        assertEquals(2, entries.size());
        assertFalse(entries.get(0).directory);
        assertTrue(entries.get(1).directory);
    }

    @Test public void reorderedPropertiesExtractAnEmptyFileAndDirectory() throws Exception {
        File output = temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                archive(new byte[]{15,1,(byte)128,16,1,0}), output, null, null, null));
        assertTrue(new File(output, "empty").isFile());
        assertEquals(0, new File(output, "empty").length());
        assertTrue(new File(output, "folder").isDirectory());
    }

    @Test public void antiBeforeEmptyStreamStillRejectsDeletionMarkers() throws Exception {
        File archive = archive(new byte[]{16,1,(byte)128,15,1,(byte)128});
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }

    @Test public void delayedEmptyFileStillRejectsTruncation() throws Exception {
        rejects(new byte[]{15,0});
    }

    @Test public void delayedEmptyFileStillRejectsTrailingBytes() throws Exception {
        rejects(new byte[]{15,2,(byte)128,0});
    }

    @Test public void delayedAntiStillRejectsTruncation() throws Exception {
        rejects(new byte[]{16,0});
    }

    @Test public void delayedDuplicatePropertyStillFails() throws Exception {
        rejects(new byte[]{15,1,0,15,1,0});
    }

    private void rejects(byte[] before) throws Exception {
        File archive = archive(before);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }

    private File archive(byte[] before) throws Exception {
        ByteArrayOutputStream properties = new ByteArrayOutputStream();
        properties.write(before);
        properties.write(new byte[]{14,1,(byte)192}); // Both files have no stream.
        byte[] names = "empty\0folder\0".getBytes(StandardCharsets.UTF_16LE);
        properties.write(17); Round4Fixtures.number(properties, names.length + 1);
        properties.write(0); properties.write(names);
        return Round5Fixtures.emptySevenZ(temp.newFolder(), 2, properties.toByteArray());
    }
}
