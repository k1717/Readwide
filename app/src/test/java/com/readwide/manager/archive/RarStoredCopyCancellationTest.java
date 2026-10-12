package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarStoredCopyCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void interruptedPlainCopyReadsNothingWithoutTokenIncludingEmptyPayload() throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[]{1}), "r")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread.currentThread().interrupt();
            try {
                for (long size : new long[]{0, 1}) assertThrows(InterruptedIOException.class,
                        () -> RarStoredPayloadIO.copyToStream(input, size, output, null));
                assertEquals(0, input.getFilePointer());
                assertEquals(0, output.size());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void preCancelledEmptyFileCopyPreservesExistingOutput() throws Exception {
        File output = file(new byte[]{42});
        FileOperationProgress progress = new FileOperationProgress("copy", null);
        progress.cancel();
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[0]), "r")) {
            assertThrows(InterruptedIOException.class,
                    () -> RarStoredPayloadIO.copyPlainEntryToFile(input, 0, output, progress));
        }
        assertThrows(InterruptedIOException.class,
                () -> RarStoredPayloadIO.copySegmentsToFile(Collections.emptyList(), output, progress));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void finalWriteInterruptionIsNotSuccessfulCopy() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                Thread.currentThread().interrupt();
            }
        };
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[]{7}), "r")) {
            try {
                assertThrows(InterruptedIOException.class,
                        () -> RarStoredPayloadIO.copyToStream(input, 1, output, null));
                assertTrue(Thread.currentThread().isInterrupted());
                assertArrayEquals(new byte[]{7}, output.toByteArray());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void finalWriteTokenCancellationIsNotSuccessfulCopy() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("copy", null);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                progress.cancel();
            }
        };
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[]{7}), "r")) {
            assertThrows(InterruptedIOException.class,
                    () -> RarStoredPayloadIO.copyToStream(input, 1, output, progress));
            assertArrayEquals(new byte[]{7}, output.toByteArray());
        }
    }

    @Test public void successfulCopyStillReadsOnlyRequestedRange() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (RandomAccessFile input = new RandomAccessFile(file(new byte[]{1, 2, 3, 4}), "r")) {
            input.seek(1);
            RarStoredPayloadIO.copyToStream(input, 2, output, null);
            assertEquals(3, input.getFilePointer());
        }
        assertArrayEquals(new byte[]{2, 3}, output.toByteArray());
    }

    private File file(byte[] bytes) throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }
}
