package com.readwide.manager.archive;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class RarScanCompletenessTest {
    @Parameterized.Parameters(name = "RAR{0}")
    public static Collection<Object[]> variants() { return Arrays.asList(new Object[][]{{4}, {5}}); }
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final int version;
    public RarScanCompletenessTest(int version) { this.version = version; }

    @Test public void completeMetadataScanRejectsCorruptLaterVolume() throws Exception {
        File first = fixture(true);
        try { RarArchiveReader.readEntries(first, null); fail("Incomplete scan accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("RAR")); }
    }

    @Test public void wholeArchiveDoesNotPublishAReadablePrefix() throws Exception {
        Assume.assumeFalse(RarLibarchiveFallback.isAvailable()); // Exercise the first-party fallback.
        File first = fixture(true), output = temp.newFolder();
        File sentinel = new File(output, "page.txt");
        ArchiveTestTextFiles.writeString(sentinel.toPath(), "keep");
        try { RarArchiveReader.extractArchiveIntoDirectory(first, output, null); fail("Prefix accepted"); }
        catch (IOException expected) { assertEquals("keep", ArchiveTestTextFiles.readString(sentinel.toPath())); }
    }

    @Test public void selectedCompleteFileRemainsRecoverable() throws Exception {
        File first = fixture(true), output = temp.newFile();
        assertTrue(RarArchiveReader.extractSingleEntry(first, "page.txt", output, null));
        assertEquals("page", ArchiveTestTextFiles.readString(output.toPath()));
    }

    @Test public void missingSelectedFileDoesNotHideScanFailure() throws Exception {
        Assume.assumeFalse(RarLibarchiveFallback.isAvailable());
        File first = fixture(true), output = temp.newFile();
        ArchiveTestTextFiles.writeString(output.toPath(), "keep");
        try { RarArchiveReader.extractSingleEntry(first, "later.txt", output, null); fail("Scan failure hidden"); }
        catch (IOException expected) { assertEquals("keep", ArchiveTestTextFiles.readString(output.toPath())); }
    }

    @Test public void validSingleVolumeStillListsCompleteMetadata() throws Exception {
        assertEquals(1, RarArchiveReader.readEntries(fixture(false), null).size());
    }

    @Test public void cancellationIsNeverPrefixRecovery() throws Exception {
        File first = fixture(true);
        Thread.currentThread().interrupt();
        try { RarArchiveReader.readEntries(first, null); fail("Cancellation hidden"); }
        catch (InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }

    private File fixture(boolean corruptTail) throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(new byte[]{'R', 'a', 'r', '!', 0x1a, 7});
        archive.write(version == 4 ? 0 : 1);
        if (version == 5) archive.write(0);
        archive.write(version == 4 ? header4(0x73, 0, new byte[6]) : header5(new byte[]{1, 0, 0}));
        byte[] data = "page".getBytes(StandardCharsets.UTF_8), name = "page.txt".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (version == 4) {
            le(body, data.length, 4); le(body, data.length, 4); body.write(1);
            le(body, crc(data), 4); le(body, 0, 4); body.write(29); body.write(0x30);
            le(body, name.length, 2); le(body, 0, 4); body.write(name);
            archive.write(header4(0x74, 0x8000, body.toByteArray()));
        } else {
            vint(body, 2); vint(body, 2); vint(body, data.length); vint(body, 4);
            vint(body, data.length); vint(body, 0); le(body, crc(data), 4);
            vint(body, 0); vint(body, 1); vint(body, name.length); body.write(name);
            archive.write(header5(body.toByteArray()));
        }
        archive.write(data);
        archive.write(version == 4 ? header4(0x7b, 0, new byte[0]) : header5(new byte[]{5, 0, 0}));
        File first = temp.newFile("book.rar");
        Files.write(first.toPath(), archive.toByteArray());
        if (corruptTail) Files.write(temp.newFile("book.r00").toPath(), new byte[]{1, 2, 3});
        return first;
    }

    private static byte[] header4(int type, int flags, byte[] body) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(type); le(data, flags, 2); le(data, body.length + 7, 2); data.write(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); le(out, crc(data.toByteArray()), 2);
        out.write(data.toByteArray()); return out.toByteArray();
    }
    private static byte[] header5(byte[] body) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream(); vint(data, body.length); data.write(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); le(out, crc(data.toByteArray()), 4);
        out.write(data.toByteArray()); return out.toByteArray();
    }
    private static void vint(OutputStream out, long value) throws Exception {
        do { int next = (int) (value & 127); value >>>= 7; out.write(next | (value == 0 ? 0 : 128)); } while (value != 0);
    }
    private static void le(OutputStream out, long value, int size) throws Exception {
        for (int i = 0; i < size; i++) out.write((int) (value >>> (8 * i)) & 255);
    }
    private static long crc(byte[] data) { CRC32 crc = new CRC32(); crc.update(data); return crc.getValue(); }
}
