package com.readwide.manager;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MainArchiveCreationWorkerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void ordinaryCreationKeepsExactPayload() throws Exception {
        File source = source(), output = output();
        assertTrue(MainArchiveCreateController.createForUi(Collections.singletonList(source), output, null));
        try (ZipFile zip = new ZipFile(output);
             java.io.InputStream input = zip.getInputStream(zip.getEntry(source.getName()))) {
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
        }
    }

    @Test public void nonfatalCallbackFailureReturnsFalseAfterOutputCleanup() throws Exception {
        File source = source(), output = output();
        assertFalse(MainArchiveCreateController.createForUi(Collections.singletonList(source), output,
                failingProgress(new IllegalStateException("callback"))));
        assertFalse(output.exists());
    }

    @Test public void wrappedCheckedCancellationIsNotAnOrdinaryCreationFailure() throws Exception {
        File source = source(), output = output();
        FileOperationProgress progress = failingProgress(
                new SecurityException("callback", new InterruptedIOException("cancelled")));
        try {
            assertFalse(MainArchiveCreateController.createForUi(Collections.singletonList(source), output, progress));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(progress.isCancelled());
            assertFalse(output.exists());
        } finally { Thread.interrupted(); }
    }

    @Test public void fatalCallbackFailureIsPreservedAfterCleanup() throws Exception {
        File source = source(), output = output(); AssertionError error = new AssertionError("fatal");
        assertSame(error, assertThrows(AssertionError.class, () -> MainArchiveCreateController.createForUi(
                Collections.singletonList(source), output, failingProgress(error))));
        assertFalse(output.exists());
    }

    @Test public void preInterruptedCreationUpdatesTokenWithoutReservingOutput() throws Exception {
        File source = source(), output = output(); FileOperationProgress progress = new FileOperationProgress("zip", null);
        try {
            Thread.currentThread().interrupt();
            assertFalse(MainArchiveCreateController.createForUi(Collections.singletonList(source), output, progress));
            assertTrue(progress.isCancelled()); assertTrue(progress.isComplete()); assertFalse(output.exists());
        } finally { Thread.interrupted(); }
    }

    @Test public void cancellationDuringPreflightNeverReservesOutput() throws Exception {
        File source = source(); int[] creates = {0};
        File output = new File(output().getPath()) {
            @Override public boolean createNewFile() throws IOException { creates[0]++; return super.createNewFile(); }
        };
        FileOperationProgress progress = new FileOperationProgress("zip", null);
        progress.setListener(snapshot -> {
            if (snapshot.totalBytes > 0 && !snapshot.cancelled) progress.cancel();
        });
        try {
            assertFalse(MainArchiveCreateController.createForUi(Collections.singletonList(source), output, progress));
            assertTrue(progress.isCancelled()); assertEquals(0, creates[0]); assertFalse(output.exists());
        } finally { Thread.interrupted(); }
    }

    private static FileOperationProgress failingProgress(Throwable failure) {
        return new FileOperationProgress("zip", snapshot -> {
            if (snapshot.doneBytes > 0) {
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw (Error) failure;
            }
        });
    }
    private File source() throws IOException {
        File source = temporary.newFile("page.txt"); Files.write(source.toPath(), new byte[]{1, 2, 3}); return source;
    }
    private File output() { return new File(temporary.getRoot(), "book.zip"); }
}
