package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarFirstPartyCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cancelledTokenStopsSelectedAndBulkBeforeSourceOrOutput() throws Exception {
        File output = existingOutput();
        FileOperationProgress progress = new FileOperationProgress("RAR", null);
        progress.cancel();
        for (IOCall route : routes(output, progress)) assertThrows(InterruptedIOException.class, route::run);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void interruptedThreadStopsSelectedAndBulkWithoutProgressToken() throws Exception {
        File output = existingOutput();
        IOCall[] routes = routes(output, null);
        Thread.currentThread().interrupt();
        try {
            for (IOCall route : routes) {
                assertThrows(InterruptedIOException.class, route::run);
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void cancellationDuringBulkPlanningDoesNotReturnFallbackOrCreateOutput() throws Exception {
        for (int version : new int[]{4, 5}) {
            FileOperationProgress progress = new FileOperationProgress("RAR", null);
            RarArchiveReader.RarEntry entry = entry(version);
            File source = temporary.newFile();
            // A probeable PPMd prefix is sufficient: cancellation must precede decoding.
            Files.write(source.toPath(), new byte[]{(byte) 0x80, 0});
            entry.sourceArchive = source;
            List<RarArchiveReader.RarEntry> entries = new AbstractList<RarArchiveReader.RarEntry>() {
                @Override public int size() { return 1; }
                @Override public RarArchiveReader.RarEntry get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException();
                    progress.cancel();
                    return entry;
                }
            };
            File output = new File(temporary.getRoot(), "cancelled-bulk-" + version);
            assertThrows(InterruptedIOException.class, () -> {
                if (version == 4) Rar3PpmdSolidArchiveExtractor.tryExtractArchivePpmdSolid(
                        entries, output, null, progress, null);
                else Rar5CompressedArchiveExtractor.tryExtractArchive(entries, output, null, progress, null);
            });
            assertFalse(output.exists());
        }
    }

    @Test public void uncancelledEmptyArchivesStillReturnIneligible() throws Exception {
        File output = new File(temporary.getRoot(), "not-created");
        assertFalse(Rar3PpmdSolidArchiveExtractor.tryExtractArchivePpmdSolid(
                Collections.emptyList(), output, null, null, null));
        assertFalse(Rar5CompressedArchiveExtractor.tryExtractArchive(
                Collections.emptyList(), output, null, null, null));
        assertFalse(output.exists());
    }

    private File existingOutput() throws Exception {
        File output = temporary.newFile();
        Files.write(output.toPath(), new byte[]{42});
        return output;
    }

    private IOCall[] routes(File output, FileOperationProgress progress) {
        RarArchiveReader.RarEntry rar3 = entry(4);
        RarArchiveReader.RarEntry rar5 = entry(5);
        return new IOCall[]{
                () -> Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(rar3,
                        Collections.singletonList(rar3), output, null, progress),
                () -> Rar5CompressedArchiveExtractor.tryExtractEntry(rar5,
                        Collections.singletonList(rar5), output, null, progress),
                () -> Rar3PpmdSolidArchiveExtractor.tryExtractArchivePpmdSolid(
                        Collections.singletonList(rar3), output, null, progress, null),
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                        Collections.singletonList(rar5), output, null, progress, null)
        };
    }

    private RarArchiveReader.RarEntry entry(int version) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("entry.bin", false,
                8, 2, 0, version, version == 4 ? 0x33 : 1, false, false, false, null, 0, 0, 0);
        entry.sourceArchive = new File(temporary.getRoot(), "absent.rar");
        return entry;
    }

    private interface IOCall { void run() throws IOException; }
}
