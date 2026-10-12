package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ZipVolumeChannelBoundsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void redistributedDiskLengthsAreRejectedEvenWhenTotalMatches() throws Exception {
        File first = file(0x50, 0x4b, 7, 8, 9), second = file(1, 2, 3);
        IOException failure = assertThrows(IOException.class,
                () -> ZipVolumeChannels.open(Arrays.asList(first, second), new long[]{4, 4}));
        assertTrue(failure.getMessage().contains("volume lengths changed"));
    }

    @Test public void everyExpectedLengthMustMatchCapturedMetadata() throws Exception {
        try (SplitSeekableByteChannel channel = new SplitSeekableByteChannel(Arrays.asList(file(1, 2), file(3)))) {
            channel.requireVolumeLengths(new long[]{2, 1});
            for (long[] lengths : new long[][]{null, {}, {3}, {1, 2}, {2, 1, 0}, {-1, 4}}) {
                assertThrows(IOException.class, () -> channel.requireVolumeLengths(lengths));
            }
            assertEquals(3, channel.size()); // Invalid caller metadata does not poison the physical reader.
        }
    }

    @Test public void matchingZipWindowsRetainDiskBoundaryReads() throws Exception {
        File first = file(0x50, 0x4b, 7, 8), second = file(5, 6, 7);
        try (SeekableByteChannel channel = ZipVolumeChannels.open(Arrays.asList(first, second), new long[]{4, 3})) {
            assertEquals(7, channel.size());
            channel.position(4);
            ByteBuffer bytes = ByteBuffer.allocate(3);
            assertEquals(3, channel.read(bytes));
            assertArrayEquals(new byte[]{5, 6, 7}, bytes.array());
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        }
    }

    @Test public void activeVolumeFastPathPreservesBackwardsReadsAndEmptyParts() throws Exception {
        File empty = file(), first = file(1, 2), second = file(3, 4);
        try (SplitSeekableByteChannel channel = new SplitSeekableByteChannel(Arrays.asList(empty, first, empty, second, empty))) {
            ByteBuffer one = ByteBuffer.allocate(1);
            for (int position : new int[]{0, 1, 0, 3, 2, 3, 1, 2}) {
                channel.position(position); one.clear();
                assertEquals(1, channel.read(one));
                assertEquals(position + 1, one.array()[0]);
            }
        }
    }

    @Test public void lengthValidationHonorsClosedAndCancelledOwnerState() throws Exception {
        try (SplitSeekableByteChannel channel = new SplitSeekableByteChannel(Arrays.asList(file(1)))) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedIOException.class, () -> channel.requireVolumeLengths(new long[]{1})); }
            finally { Thread.interrupted(); }
            assertThrows(IOException.class, () -> channel.requireVolumeLengths(new long[]{1}));
            channel.close();
            assertThrows(IOException.class, () -> channel.requireVolumeLengths(new long[]{1}));
        }
    }

    private File file(int... bytes) throws IOException {
        File file = temporary.newFile();
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) data[i] = (byte) bytes[i];
        Files.write(file.toPath(), data);
        return file;
    }
}
