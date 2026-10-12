package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarRewriteCopyCancellationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void interruptionDuringPlainCopyStopsBothRewritersAfterOneChunk() throws Exception {
        for (boolean headerEncrypted : new boolean[]{false, true}) checkStoppedCopy(headerEncrypted, true);
    }

    @Test public void tokenCancellationDuringPlainCopyStopsBothRewritersAfterOneChunk() throws Exception {
        for (boolean headerEncrypted : new boolean[]{false, true}) checkStoppedCopy(headerEncrypted, false);
    }

    private void checkStoppedCopy(boolean headerEncrypted, boolean interrupt) throws Exception {
        File inputFile = temp.newFile(), outputFile = temp.newFile();
        Files.write(inputFile.toPath(), new byte[128 * 1024]);
        FileOperationProgress progress = interrupt ? null : new FileOperationProgress("RAR", null);
        try (RandomAccessFile input = new RandomAccessFile(inputFile, "r");
             FileOutputStream output = new FileOutputStream(outputFile) {
                 @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                     super.write(bytes, offset, length);
                     if (interrupt) Thread.currentThread().interrupt(); else progress.cancel();
                 }
             }) {
            try {
                assertThrows(InterruptedIOException.class,
                        () -> copy(headerEncrypted, input, output, 128 * 1024, progress));
                assertEquals(64 * 1024, input.getFilePointer());
                assertEquals(64 * 1024, outputFile.length());
                if (interrupt) assertTrue(Thread.currentThread().isInterrupted());
            } finally { if (interrupt) Thread.interrupted(); }
        }
    }

    @Test public void cancelledEmptyCopiesDoNotReportSuccess() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("RAR", null);
        progress.cancel();
        for (boolean headerEncrypted : new boolean[]{false, true}) {
            try (RandomAccessFile input = new RandomAccessFile(temp.newFile(), "r");
                 FileOutputStream output = new FileOutputStream(temp.newFile())) {
                assertThrows(InterruptedIOException.class, () -> copy(headerEncrypted, input, output, 0, progress));
            }
        }
    }

    @Test public void preCancelledEntryPointsAvoidSourceAccessAndPreserveExistingDestination() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("RAR", null);
        progress.cancel();
        File absent = new File(temp.getRoot(), "absent.rar");
        File output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertThrows(InterruptedIOException.class,
                () -> Rar4EncryptedPayloadRewriter.buildDecryptedCopy(absent, new char[]{'p'}, progress));
        assertThrows(InterruptedIOException.class,
                () -> Rar4HeaderEncryptedArchiveRewriter.buildDecryptedHeaderCopy(absent, new char[]{'p'}, progress));
        assertThrows(InterruptedIOException.class,
                () -> Rar4HeaderEncryptedArchiveRewriter.writeDecryptedHeaderCopy(absent, output, new char[]{'p'}, progress));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void uninterruptedCopiesKeepRequestedBytesAndPosition() throws Exception {
        byte[] bytes = {1, 2, 3, 4, 5};
        for (boolean headerEncrypted : new boolean[]{false, true}) {
            File inputFile = temp.newFile(), outputFile = temp.newFile();
            Files.write(inputFile.toPath(), bytes);
            try (RandomAccessFile input = new RandomAccessFile(inputFile, "r");
                 FileOutputStream output = new FileOutputStream(outputFile)) {
                copy(headerEncrypted, input, output, 3, null);
                assertEquals(3, input.getFilePointer());
            }
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(outputFile.toPath()));
        }
    }

    private static void copy(boolean headerEncrypted, RandomAccessFile input, FileOutputStream output,
            long size, FileOperationProgress progress) throws Exception {
        java.lang.reflect.Method method;
        Object[] arguments;
        if (headerEncrypted) {
            method = Rar4HeaderEncryptedArchiveRewriter.class.getDeclaredMethod("copyRange",
                    RandomAccessFile.class, long.class, long.class, FileOutputStream.class, FileOperationProgress.class);
            arguments = new Object[]{input, 0L, size, output, progress};
        } else {
            method = Rar4EncryptedPayloadRewriter.class.getDeclaredMethod("copyBytes",
                    RandomAccessFile.class, FileOutputStream.class, long.class, byte[].class, FileOperationProgress.class);
            arguments = new Object[]{input, output, size, new byte[64 * 1024], progress};
        }
        method.setAccessible(true);
        try { method.invoke(null, arguments); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }
}
