package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.InterruptedIOException;
import org.junit.Test;

public class ArchiveCancellationBoundaryTest {
    @Test(timeout = 2000) public void finalObservationDoesNotWaitOnPauseOrClearIt() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("boundary", null);
        progress.setPaused(true);
        try {
            ArchiveCancellation.checkCancelled(progress);
            assertTrue(progress.isPaused());
            assertFalse(progress.isCancelled());
        } finally { progress.setPaused(false); }
        ArchiveCancellation.checkCancelled(null);
        ArchiveCancellation.checkCancelled(progress);
    }

    @Test public void finalObservationKeepsCancellationTerminal() {
        FileOperationProgress progress = new FileOperationProgress("boundary", null);
        progress.cancel();
        assertThrows(InterruptedIOException.class, () -> ArchiveCancellation.checkCancelled(progress));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void finalObservationRecordsThreadInterruptionWithoutClearingFlag() {
        for (FileOperationProgress progress : new FileOperationProgress[]{null, new FileOperationProgress("boundary", null)}) {
            try {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedIOException.class, () -> ArchiveCancellation.checkCancelled(progress));
                assertTrue(Thread.currentThread().isInterrupted());
                if (progress != null) assertTrue(progress.isCancelled());
            } finally { Thread.interrupted(); }
        }
    }
}
