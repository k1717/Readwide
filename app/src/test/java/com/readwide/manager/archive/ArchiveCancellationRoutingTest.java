package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveCancellationRoutingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void interruptedCheckpointUpdatesTokenWithoutCallingListener() {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> ArchiveCancellation.checkpoint(progress));
            assertTrue(progress.isCancelled());
            assertTrue(progress.isComplete());
        } finally { Thread.interrupted(); }
    }

    @Test public void resultAdapterPreservesCancellationUnderTypedFailures() {
        Exception[] failures = {
                new ArchiveSupport.PasswordRequiredException(),
                new ArchiveSupport.UnsupportedArchiveFeatureException("unsupported"),
                new IOException("checksum mismatch"), new SecurityException("denied")
        };
        for (Exception failure : failures) {
            failure.addSuppressed(new IOException("cleanup", new InterruptedIOException("cancelled")));
            FileOperationProgress progress = new FileOperationProgress("extract", null);
            try {
                ArchiveSupport.ExtractionResult result = ArchiveSupport.extractionFailureResult(
                        new File("must-not-open.7z"), new char[]{'x'}, failure, progress);
                assertFalse(result.success);
                assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(progress.isCancelled());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void ordinaryTypedResultFailuresKeepTheirClassification() {
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,
                ArchiveSupport.extractionFailureResult(new File("missing.zip"), null,
                        new ArchiveSupport.PasswordRequiredException(), null).failure);
        assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE,
                ArchiveSupport.extractionFailureResult(new File("missing.zip"), null,
                        new ArchiveSupport.UnsupportedArchiveFeatureException("method"), null).failure);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void restorationDoesNotInvokeFailingProgressListener() {
        FileOperationProgress progress = new FileOperationProgress("extract", snapshot -> {
            if (snapshot.cancelled) throw new AssertionError("no callback during exception conversion");
        });
        try {
            assertTrue(ArchiveCancellation.restoreIfCancelled(new InterruptedIOException("cancelled"), progress));
            assertTrue(progress.isCancelled());
            assertFalse(progress.isPaused());
        } finally { Thread.interrupted(); }
    }

    @Test public void suppressedCleanupCancellationPreventsCodecFallback() {
        IOException primary = new IOException("codec rejected payload");
        InterruptedIOException cancelled = new InterruptedIOException("cleanup cancelled");
        primary.addSuppressed(cancelled);
        assertSame(cancelled, assertThrows(InterruptedIOException.class,
                () -> ArchiveCancellation.rethrowIfCancelled(primary, null)));
    }

    @Test public void wrappedSuppressedCancellationIsFoundWithoutThreadFlag() {
        InterruptedIOException cancelled = new InterruptedIOException("callback cancelled");
        IOException primary = new IOException("primary");
        primary.addSuppressed(new IOException("cleanup wrapper", cancelled));
        assertSame(cancelled, assertThrows(InterruptedIOException.class,
                () -> ArchiveCancellation.rethrowIfCancelled(new IOException("outer", primary), null)));
    }

    @Test public void cyclicCausesAndSuppressedFailuresTerminateWithoutCancellation() throws Exception {
        IOException first = new IOException("first"), second = new IOException("second");
        first.initCause(second); second.initCause(first);
        first.addSuppressed(second); second.addSuppressed(first);
        ArchiveCancellation.rethrowIfCancelled(first, null);
    }

    @Test public void cancellationInSuppressedCycleIsNotSkipped() {
        IOException first = new IOException("first"), second = new IOException("second");
        InterruptedIOException cancelled = new InterruptedIOException("cancelled");
        first.addSuppressed(second); second.addSuppressed(first); second.addSuppressed(cancelled);
        assertSame(cancelled, assertThrows(InterruptedIOException.class,
                () -> ArchiveCancellation.rethrowIfCancelled(first, null)));
    }

    @Test public void typedCancellationIsPreservedEvenWithoutAnInterruptFlag() {
        InterruptedIOException expected = new InterruptedIOException("cancelled by backend");
        assertSame(expected, assertThrows(InterruptedIOException.class,
                () -> ArchiveCancellation.rethrowIfCancelled(expected, null)));
    }

    @Test public void progressCancellationPreventsOrdinaryIoFallback() {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.cancel();
        IOException failure = new IOException("native read failed");
        InterruptedIOException cancelled = assertThrows(InterruptedIOException.class,
                () -> ArchiveCancellation.rethrowIfCancelled(failure, progress));
        assertSame(failure, cancelled.getCause());
    }

    @Test public void interruptedSecurityFailurePreservesFlagAndCause() {
        SecurityException failure = new SecurityException("read denied");
        try {
            Thread.currentThread().interrupt();
            InterruptedIOException cancelled = assertThrows(InterruptedIOException.class,
                    () -> ArchiveCancellation.rethrowIfCancelled(failure, null));
            assertSame(failure, cancelled.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void ordinaryDecoderFailureStillAllowsFallback() throws Exception {
        ArchiveCancellation.rethrowIfCancelled(new IOException("unsupported codec"), null);
        ArchiveCancellation.rethrowIfCancelled(new ArchiveSupport.PasswordRequiredException(), null);
    }

    @Test public void cancelledBulkStopsBeforeOpeningArchiveOrOutput() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.cancel();
        File root = temporary.newFolder(), output = new File(root, "out");
        assertThrows(InterruptedIOException.class, () -> RarArchiveReader.extractArchiveIntoDirectory(
                new File(root, "missing.rar"), output, null, progress, null));
        assertFalse(output.exists());
    }

    @Test public void interruptedSelectionDoesNotTouchExistingOutput() throws Exception {
        File output = temporary.newFile();
        Files.write(output.toPath(), new byte[]{42});
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> RarArchiveReader.extractSingleEntry(
                    new File(temporary.getRoot(), "missing.rar"), "page.jpg", output, null));
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void interruptedListingStopsBeforeBackendSelection() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> RarArchiveReader.listEntries(
                    new File(temporary.getRoot(), "missing.rar"), null));
        } finally { Thread.interrupted(); }
    }
}
