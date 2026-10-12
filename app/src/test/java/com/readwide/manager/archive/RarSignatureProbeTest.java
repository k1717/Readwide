package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic marker/range checks; signatures alone do not establish valid archives. */
public class RarSignatureProbeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] RAR4 = {0x52, 0x61, 0x72, 0x21, 0x1a, 7, 0};
    private static final byte[] RAR5 = {0x52, 0x61, 0x72, 0x21, 0x1a, 7, 1, 0};

    @Test public void ordinaryRarOnlyReadsEightBytesFromALargeFile() throws Exception {
        for (int version : new int[]{4, 5}) {
            File file = temp.newFile();
            try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                output.write(version == 4 ? RAR4 : RAR5);
                output.setLength(2 * 1024 * 1024);
            }
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(input);
                assertEquals(version, signature.version);
                assertEquals(0, signature.offset);
                assertEquals(8, input.getFilePointer());
            }
        }
    }

    @Test public void prefixBoundaryAndShortRar4KeepTheirOffsets() throws Exception {
        for (byte[] marker : new byte[][]{RAR4, RAR5}) {
            for (int offset : new int[]{0, 1, 2, 7, 8, 65535}) {
                File file = markerFile(marker, offset, offset + marker.length);
                try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                    RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(input);
                    assertEquals(offset, signature.offset);
                    assertEquals(marker == RAR4 ? 4 : 5, signature.version);
                }
            }
        }
    }

    @Test public void earliestSignatureStillWinsAcrossBothVersions() throws Exception {
        for (boolean firstIsRar4 : new boolean[]{false, true}) {
            byte[] first = firstIsRar4 ? RAR4 : RAR5;
            byte[] second = firstIsRar4 ? RAR5 : RAR4;
            File file = markerFile(first, 17, 100);
            try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                output.seek(60); output.write(second);
            }
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(input);
                assertEquals(17, signature.offset);
                assertEquals(firstIsRar4 ? 4 : 5, signature.version);
            }
        }
    }

    @Test public void previousSfxScanExtentIsPreserved() throws Exception {
        int extent = 1024 * 1024 + 8;
        for (byte[] marker : new byte[][]{RAR4, RAR5}) {
            int lastOffset = extent - marker.length;
            for (int extra : new int[]{0, 1}) {
                File file = markerFile(marker, lastOffset + extra, extent + 20);
                try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                    RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(input);
                    if (extra == 0) assertEquals(lastOffset, signature.offset);
                    else assertNull(signature);
                    assertEquals(extent, input.getFilePointer());
                }
            }
        }
    }

    @Test public void cancellationPrecedesSeekAndFileOpen() throws Exception {
        File file = markerFile(RAR5, 0, 100);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(42);
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> RarArchiveLocator.findSignature(input));
                assertEquals(42, input.getFilePointer());
                assertThrows(InterruptedIOException.class, () -> RarArchiveLocator.findEmbeddedRarSignatureOffset(
                        new File(temp.getRoot(), "not-created.sfx")));
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void cancellationAfterPrefixReadPreventsSfxScan() throws Exception {
        File file = markerFile(RAR5, 100, 1000);
        try (RandomAccessFile input = new RandomAccessFile(file, "r") {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                Thread.currentThread().interrupt();
                return count;
            }
        }) {
            try {
                assertThrows(InterruptedIOException.class, () -> RarArchiveLocator.findSignature(input));
                assertEquals(8, input.getFilePointer());
            } finally { Thread.interrupted(); }
        }
    }

    private File markerFile(byte[] marker, int offset, int size) throws IOException {
        File file = temp.newFile();
        byte[] bytes = new byte[size];
        System.arraycopy(marker, 0, bytes, offset, marker.length);
        Files.write(file.toPath(), bytes);
        return file;
    }
}
