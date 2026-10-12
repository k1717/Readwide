package com.readwide.manager.archive;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Synthetic naming/routing regressions; not ALZip-producer interoperability evidence. */
public class AlzVolumeRolloverTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void b00RolloverSupportsPublicRoutingAndSelectedExtraction() throws Exception {
        byte[] payload = Round4Fixtures.data(6011);
        List<File> parts = rollover(payload, 102, 0, false);
        File selected = parts.get(101);
        assertEquals("book.b00", selected.getName());
        assertEquals(parts, AlzVolumeResolver.resolve(selected).files);
        assertEquals(parts.get(0), AlzVolumeResolver.resolveFirstVolume(selected));
        assertEquals(ArchiveSupport.Type.ALZ, ArchiveSupport.getSupportedArchiveType(selected));
        assertEquals(parts.get(0), ArchiveSupport.normalizeExtractionQueueArchive(selected));
        assertEquals("book", ArchiveSupport.getArchiveOutputBaseName(selected, "fallback"));
        assertEquals(1, ArchiveSupport.listEntries(selected, null).size());
        File output = temp.newFile();
        assertTrue(ArchiveSupport.extractSingleEntry(selected, "data.bin", output, null));
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test public void c00EncryptedDeflateSupportsBulkAndSelectedReads() throws Exception {
        byte[] payload = Round4Fixtures.data(12023);
        List<File> parts = rollover(payload, 202, 2, true);
        File selected = parts.get(201);
        assertEquals("book.c00", selected.getName());
        assertTrue(ArchiveSupport.requiresPasswordForExtraction(selected));
        File directory = temp.newFolder();
        assertTrue(AlzipArchiveReader.extractArchiveIntoDirectory(selected, directory, Round4Fixtures.PASSWORD));
        assertArrayEquals(payload, Files.readAllBytes(new File(directory, "data.bin").toPath()));
        File output = temp.newFile();
        assertTrue(AlzipArchiveReader.extractSingleEntry(selected, "data.bin", output, Round4Fixtures.PASSWORD));
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test public void j99HasSameByteStreamWithoutAddingAVolumeCountCap() throws Exception {
        byte[] payload = Round4Fixtures.data(6011);
        List<File> parts = rollover(payload, 1001, 0, false);
        File selected = parts.get(1000);
        assertEquals("book.j99", selected.getName());
        assertEquals(1001, AlzVolumeResolver.resolve(selected).files.size());
        File output = temp.newFile();
        assertTrue(AlzipArchiveReader.extractSingleEntry(selected, "data.bin", output, null));
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test public void wideDecimalAliasesRemainReadableBeyondLetterRolloverRange() throws Exception {
        byte[] payload = Round4Fixtures.data(6011);
        List<File> parts = Round4Fixtures.splitAlz(temp.newFolder(), "book",
                Round4Fixtures.alz(payload, 0, false), 1003);
        assertEquals("book.a1001", parts.get(1002).getName());
        File output = temp.newFile();
        assertTrue(AlzipArchiveReader.extractSingleEntry(parts.get(1002), "data.bin", output, null));
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test public void decimalAndLetterAliasesCannotClaimTheSameVolume() throws Exception {
        List<File> parts = rollover(Round4Fixtures.data(6011), 102, 0, false);
        File last = parts.get(101);
        Files.copy(last.toPath(), new File(last.getParentFile(), "book.a100").toPath());
        assertRejectedBeforeOutput(last);
        assertThrows(IOException.class, () -> AlzVolumeResolver.resolve(parts.get(0)));
    }

    @Test public void missingPartOnEitherSideOfRolloverFails() throws Exception {
        for (int missing : new int[]{100, 101}) {
            List<File> parts = rollover(Round4Fixtures.data(6011), 103, 0, false);
            Files.delete(parts.get(missing).toPath());
            assertRejectedBeforeOutput(parts.get(102));
        }
    }

    @Test public void mixedSpellingsAndCaseShareOneLogicalOrder() throws Exception {
        byte[] payload = Round4Fixtures.data(6011);
        List<File> parts = rollover(payload, 104, 0, false);
        File decimal = new File(parts.get(102).getParentFile(), "book.a101");
        Files.move(parts.get(102).toPath(), decimal.toPath()); parts.set(102, decimal);
        File upper = new File(parts.get(103).getParentFile(), "BOOK.B02");
        Files.move(parts.get(103).toPath(), upper.toPath()); parts.set(103, upper);
        assertEquals(parts, AlzVolumeResolver.resolve(upper).files);
        assertEquals(ArchiveSupport.Type.ALZ, ArchiveSupport.getSupportedArchiveType(upper));
        File output = temp.newFile();
        assertTrue(AlzipArchiveReader.extractSingleEntry(upper, "data.bin", output, null));
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test public void rolloverPartsParticipateInPreviewAndIndexInvalidation() throws Exception {
        List<File> parts = rollover(Round4Fixtures.data(6011), 102, 0, false);
        File first = parts.get(0), last = parts.get(101);
        ArchiveSourceSnapshot snapshot = ArchiveSourceSnapshot.capture(first);
        assertTrue(snapshot.matches(first));
        assertEquals(snapshot.cacheFingerprint(), ArchiveSourceSnapshot.capture(last).cacheFingerprint());
        AlzipArchiveReader.Index index = AlzipArchiveReader.indexFor(first);
        assertSame(index, AlzipArchiveReader.indexFor(last));
        assertTrue(last.setLastModified(last.lastModified() + 10000));
        assertFalse(snapshot.matches(first));
        AlzipArchiveReader.Index changed = AlzipArchiveReader.indexFor(last);
        assertNotSame(index, changed);
        AlzipArchiveReader.releaseArchiveIndex(last);
        assertNotSame(changed, AlzipArchiveReader.indexFor(first));
        Files.delete(last.toPath());
        assertThrows(IOException.class, () -> AlzipArchiveReader.indexFor(first));
    }

    @Test public void orphanRolloverDoesNotBecomeAStandaloneArchive() throws Exception {
        File file = Round4Fixtures.write(temp.newFolder(), "book.b00", new byte[]{1});
        assertNull(ArchiveSupport.getSupportedArchiveType(file));
        assertThrows(IOException.class, () -> AlzVolumeResolver.resolve(file));
        assertFalse(ArchiveSourceSnapshot.capture(file).matches(file));
        // Keep old RAR split routing separate even beside an unrelated ALZ file.
        Round4Fixtures.write(file.getParentFile(), "book.alz", new byte[]{'A', 'L', 'Z', 1});
        File rar = Round4Fixtures.write(file.getParentFile(), "book.r00", new byte[]{1});
        assertEquals(ArchiveSupport.Type.RAR, ArchiveSupport.getSupportedArchiveType(rar));
        for (String suffix : new String[]{"r00", "s00", "z99", "b1", "b000", "b100", "k00"}) {
            assertFalse(AlzVolumeResolver.CONTINUATION.matcher("book." + suffix).matches());
        }
    }

    @Test public void damagedRolloverFramingFailsBeforeOutput() throws Exception {
        List<File> parts = rollover(Round4Fixtures.data(6011), 102, 0, false);
        File last = parts.get(101);
        byte[] bytes = Files.readAllBytes(last.toPath()); bytes[bytes.length - 1] = 99;
        Files.write(last.toPath(), bytes);
        assertRejectedBeforeOutput(last);
    }

    @Test public void rolloverPayloadCrcFailureRestoresExistingTarget() throws Exception {
        List<File> parts = rollover(Round4Fixtures.data(6011), 102, 0, false);
        File last = parts.get(101);
        byte[] bytes = Files.readAllBytes(last.toPath()); bytes[8] ^= 1;
        Files.write(last.toPath(), bytes);
        assertEquals(1, AlzipArchiveReader.listEntries(last, null).size());
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractSingleEntry(last, "data.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void rolloverDiscoveryHonorsInterruption() throws Exception {
        List<File> parts = rollover(Round4Fixtures.data(6011), 102, 0, false);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> AlzVolumeResolver.resolve(parts.get(101)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private List<File> rollover(byte[] payload, int count, int method, boolean encrypted) throws Exception {
        List<File> parts = new ArrayList<>(Round4Fixtures.splitAlz(temp.newFolder(), "book",
                Round4Fixtures.alz(payload, method, encrypted), count));
        // Independent naming oracle: continuation 100 follows .a99 as .b00.
        for (int i = 101; i < parts.size(); i++) {
            int ordinal = i - 1;
            String suffix = String.format(Locale.ROOT, ".%c%02d", 'a' + ordinal / 100, ordinal % 100);
            File renamed = new File(parts.get(i).getParentFile(), "book" + suffix);
            Files.move(parts.get(i).toPath(), renamed.toPath()); parts.set(i, renamed);
        }
        return parts;
    }

    private void assertRejectedBeforeOutput(File archive) throws Exception {
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AlzipArchiveReader.listEntries(archive, null));
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder(), existing = new File(directory, "data.bin");
        Files.write(existing.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, directory.list().length);
    }
}
