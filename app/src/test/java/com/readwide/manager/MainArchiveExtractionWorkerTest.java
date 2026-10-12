package com.readwide.manager;

import static org.junit.Assert.*;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InterruptedIOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MainArchiveExtractionWorkerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nonfatalWorkerFailureReturnsFailureAfterOwnedOutputCleanup() throws Exception {
        File archive = archive(), output = new File(temporary.getRoot(), "output");
        FileOperationProgress progress = failingProgress(new IllegalStateException("callback"));
        ArchiveSupport.ExtractionResult result = MainArchiveExtractController.extractForUi(
                archive, output, false, null, progress);
        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertEquals("callback", result.detail);
        assertFalse(output.exists());
    }

    @Test public void wrappedCancellationCannotTurnIntoRetryableUiFailure() throws Exception {
        File archive = archive(), output = new File(temporary.getRoot(), "output");
        FileOperationProgress progress = failingProgress(
                new IllegalStateException("callback", new InterruptedIOException("cancelled")));
        try {
            assertFalse(MainArchiveExtractController.extractForUi(archive, output, false, null, progress).success);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(progress.isCancelled());
            assertFalse(output.exists());
        } finally { Thread.interrupted(); }
    }

    @Test public void fatalErrorsRemainErrorsAndSuccessfulRequestsRemainSuccessful() throws Exception {
        File archive = archive(), output = new File(temporary.getRoot(), "output");
        AssertionError fatal = new AssertionError("fatal");
        assertSame(fatal, assertThrows(AssertionError.class, () -> MainArchiveExtractController.extractForUi(
                archive, output, false, null, failingProgress(fatal))));
        assertFalse(output.exists());
        assertTrue(MainArchiveExtractController.extractForUi(archive, output, false, null, null).success);
        assertTrue(new File(output, "page").isFile());
    }

    @Test public void coreResultConversionKeepsWrappedCancellationAfterCleanup() throws Exception {
        File archive = archive(), output = new File(temporary.getRoot(), "output");
        FileOperationProgress progress = failingProgress(
                new SecurityException("read interrupted", new InterruptedIOException("cancelled")));
        try {
            ArchiveSupport.ExtractionResult result = MainArchiveExtractController.extractForUi(
                    archive, output, false, null, progress);
            assertFalse(result.success);
            assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(progress.isCancelled());
            assertFalse(output.exists());
        } finally { Thread.interrupted(); }
    }

    @Test public void interruptedBulkRequestMarksUiTokenBeforeReturning() throws Exception {
        File archive = archive(), output = new File(temporary.getRoot(), "output");
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        try {
            Thread.currentThread().interrupt();
            assertFalse(MainArchiveExtractController.extractForUi(archive, output, false, null, progress).success);
            assertTrue(progress.isCancelled()); assertTrue(progress.isComplete());
            assertFalse(output.exists());
        } finally { Thread.interrupted(); }
    }

    private static FileOperationProgress failingProgress(Throwable failure) {
        return new FileOperationProgress("extract", snapshot -> {
            if (snapshot.doneBytes > 0) {
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw (Error) failure;
            }
        });
    }

    private File archive() throws Exception {
        File archive = temporary.newFile("input.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("page")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
        }
        return archive;
    }
}
