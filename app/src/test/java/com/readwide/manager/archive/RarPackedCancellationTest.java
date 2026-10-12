package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarPackedCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cancellationBetweenEmptySegmentsStopsBeforeNextVolume() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("read", null);
        RarCryptoStreams.EncryptedSegment empty = segment(new byte[0]);
        List<RarCryptoStreams.EncryptedSegment> segments = new AbstractList<RarCryptoStreams.EncryptedSegment>() {
            @Override public int size() { return 2; }
            @Override public RarCryptoStreams.EncryptedSegment get(int index) {
                if (index != 0) throw new AssertionError("Read continued after cancellation");
                progress.cancel();
                return empty;
            }
        };
        try (RarPackedInputStream input = new RarPackedInputStream(segments, progress)) {
            assertThrows(InterruptedIOException.class, () -> input.read());
            assertNoOpenVolume(input);
        }
    }

    @Test public void interruptionClosesActiveVolumeAndCannotResumeAfterFlagCleared() throws Exception {
        try (RarPackedInputStream input = new RarPackedInputStream(
                Collections.singletonList(segment(new byte[]{7, 8})), null)) {
            assertEquals(7, input.read());
            InterruptedIOException failure;
            Thread.currentThread().interrupt();
            try {
                failure = assertThrows(InterruptedIOException.class, () -> input.read());
                assertTrue(Thread.currentThread().isInterrupted());
                assertNoOpenVolume(input);
            } finally { Thread.interrupted(); }
            assertSame(failure, assertThrows(InterruptedIOException.class, () -> input.read()));
        }
    }

    @Test public void preCancelledReadDoesNotAccessMissingVolume() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("read", null);
        progress.cancel();
        RarCryptoStreams.EncryptedSegment absent = new RarCryptoStreams.EncryptedSegment(
                new File(temporary.getRoot(), "absent.rar"), 0, 1);
        try (RarPackedInputStream input = new RarPackedInputStream(Collections.singletonList(absent), progress)) {
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> input.read());
            assertSame(failure, assertThrows(InterruptedIOException.class, () -> input.read()));
            assertNoOpenVolume(input);
        }
    }

    @Test public void uncancelledEmptySegmentsRemainTransparent() throws Exception {
        try (RarPackedInputStream input = new RarPackedInputStream(Arrays.asList(
                segment(new byte[0]), segment(new byte[]{7}), segment(new byte[0])), null)) {
            assertEquals(7, input.read());
            assertEquals(-1, input.read());
            assertNoOpenVolume(input);
        }
    }

    private RarCryptoStreams.EncryptedSegment segment(byte[] bytes) throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return new RarCryptoStreams.EncryptedSegment(file, 0, bytes.length);
    }

    private static void assertNoOpenVolume(RarPackedInputStream input) throws Exception {
        Field field = RarPackedInputStream.class.getDeclaredField("file");
        field.setAccessible(true);
        assertNull(field.get(input));
    }
}
