package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarSplitStoredCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void typedCancellationKeepsIdentityCauseAndSuppressedCleanup() {
        InterruptedIOException stopped = new InterruptedIOException("stopped");
        IOException cause = new IOException("source");
        IOException cleanup = new IOException("cleanup");
        stopped.initCause(cause);
        stopped.addSuppressed(cleanup);
        IOException result = RarSplitStoredFailure.wrap(null, null, stopped);
        assertSame(stopped, result);
        assertSame(cause, result.getCause());
        assertSame(cleanup, result.getSuppressed()[0]);
    }

    @Test public void typedCancellationClassifiesWithoutCancellationWords() {
        for (String message : new String[]{null, "stopped", "checksum operation interrupted"}) {
            RarSplitStoredFailure failure = RarSplitStoredFailure.classify(null, null,
                    new InterruptedIOException(message));
            assertEquals(RarSplitStoredFailure.Code.CANCELLED, failure.code);
            assertEquals(RarSplitStoredFailure.Kind.USER_ACTION, failure.kind);
        }
    }

    @Test public void preCancelledExtractionDoesNotTraverseMetadataOrCreateOutput() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.cancel();
        List<RarArchiveReader.RarEntry> unreadable = new AbstractList<RarArchiveReader.RarEntry>() {
            @Override public int size() { throw new AssertionError("Planning started after cancellation"); }
            @Override public RarArchiveReader.RarEntry get(int index) { throw new AssertionError(); }
        };
        File output = new File(temporary.getRoot(), "absent/out.bin");
        assertThrows(InterruptedIOException.class,
                () -> RarSplitStoredExtractor.extract(null, output, null, unreadable, progress));
        assertFalse(output.getParentFile().exists());
    }

    @Test public void interruptedDirectPlanValidationStopsBeforeEmptyChainFailure() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> RarSplitStoredPlan.fromChain(Collections.emptyList()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void ordinaryFailuresStillRetainStableWrapperAndCause() {
        IOException source = new IOException("source read failed");
        IOException wrapped = RarSplitStoredFailure.wrap(null, null, source);
        assertTrue(wrapped instanceof RarSplitStoredFailure.ExtractionException);
        assertSame(source, wrapped.getCause());
        assertSame(wrapped, RarSplitStoredFailure.wrap(null, null, wrapped));
    }
}
