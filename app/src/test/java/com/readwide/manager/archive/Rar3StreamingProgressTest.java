package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import org.junit.Test;

public class Rar3StreamingProgressTest {
    @Test public void emittedBytesAreCountedOnceForSingleAndBulkWrites() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FileOperationProgress progress = new FileOperationProgress("decode", null);
        OutputStream target = target(bytes, progress);
        target.write(7);
        target.write(new byte[]{1, 2, 3, 4}, 1, 2);
        assertArrayEquals(new byte[]{7, 2, 3}, bytes.toByteArray());
        assertEquals(3, progress.snapshot().doneBytes);
    }

    @Test public void preCancelledOutputDoesNotPublishAlreadyDecodedBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FileOperationProgress progress = new FileOperationProgress("decode", null);
        progress.cancel();
        OutputStream target = target(bytes, progress);
        assertThrows(InterruptedIOException.class, () -> target.write(7));
        assertThrows(InterruptedIOException.class, () -> target.write(new byte[]{1, 2}));
        assertEquals(0, bytes.size());
        assertEquals(0, progress.snapshot().doneBytes);
    }

    @Test public void cancellationFromFinalProgressNotificationIsNotSuccess() throws Exception {
        FileOperationProgress[] holder = new FileOperationProgress[1];
        holder[0] = new FileOperationProgress("decode", snapshot -> {
            if (snapshot.doneBytes > 0 && !snapshot.cancelled) holder[0].cancel();
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream target = target(bytes, holder[0]);
        assertThrows(InterruptedIOException.class, () -> target.write(new byte[]{1, 2}));
        assertArrayEquals(new byte[]{1, 2}, bytes.toByteArray());
        assertEquals(2, holder[0].snapshot().doneBytes);
    }

    @Test public void failedDestinationWriteDoesNotAdvanceProgress() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("decode", null);
        OutputStream target = target(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("storage failed"); }
        }, progress);
        IOException failure = assertThrows(IOException.class, () -> target.write(7));
        assertEquals("storage failed", failure.getMessage());
        assertEquals(0, progress.snapshot().doneBytes);
    }

    @Test public void interruptionWithoutTokenPreventsPublication() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream target = target(bytes, null);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> target.write(7));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(0, bytes.size());
    }

    private static OutputStream target(OutputStream output, FileOperationProgress progress) throws Exception {
        Method method = Rar3Unpacker.class.getDeclaredMethod("streamingTarget",
                RarDecodedOutput.class, FileOperationProgress.class);
        method.setAccessible(true);
        return (OutputStream) method.invoke(null, new RarOutputStreamDecodedOutput(output), progress);
    }
}
