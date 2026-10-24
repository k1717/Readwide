package com.readwide.manager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.model.LargeTextLinePartitionResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Exercises worker route selection with in-memory read stubs; source only. */
@RunWith(AndroidJUnit4.class)
public class ReaderLineJumpCacheInstrumentedTest {
    @Test public void resolvedLineReusesItsCachedOwnerWithoutASecondRead() throws Exception {
        runJump(true, false, 0, 0);
    }

    @Test public void mappedResidentWindowUsesKnownStatsOnCacheMiss() throws Exception {
        runJump(false, false, 1, 0);
    }

    @Test public void newlineExpandedWindowKeepsTheFullReadFallback() throws Exception {
        runJump(false, true, 0, 1);
    }

    @Test public void rejectedSubmissionReleasesPendingSwitchAndLoading() {
        assertSubmissionFailure(new java.util.concurrent.RejectedExecutionException("synthetic rejection"));
    }

    @Test public void workerStartupAllocationFailureReleasesPendingSwitchAndLoading() {
        assertSubmissionFailure(new OutOfMemoryError("synthetic startup"));
    }

    private static void assertSubmissionFailure(Throwable failure) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            JumpActivity a = new JumpActivity();
            try {
                a.filePath = "/line-cache.txt";
                a.largeTextEstimateActive = true;
                a.fileContent = "a\nb";
                a.largeTextPartitionExecutor.setThreadFactory(task -> {
                    if (failure instanceof Error error) throw error;
                    throw (RuntimeException) failure;
                });
                new ReaderLargeTextPartitionNavigator(a).switchToDisplayLine(2);
                assertFalse(a.largeTextPartitionSwitchState.isInProgress());
                assertFalse(a.largeTextPartitionExecutor.hasForegroundWork());
                assertFalse(a.loading);
                assertEquals(2, a.largeTextPartitionSwitchGeneration.get());
                assertNull(a.applied);
                assertEquals("a\nb", a.fileContent);
            } finally {
                a.activityDestroyed = true;
                a.handler.removeCallbacksAndMessages(null);
                a.executor.shutdownNow();
                a.largeTextPartitionExecutor.shutdownNow();
                a.largeTextSearchExecutor.shutdownNow();
                a.largeTextSearchCountExecutor.shutdownNow();
            }
        });
    }

    private static void runJump(boolean cached, boolean expanded, int atLineReads, int fullReads) throws Exception {
        JumpActivity[] holder = new JumpActivity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            JumpActivity a = new JumpActivity();
            holder[0] = a;
            a.filePath = "/line-cache.txt";
            a.largeTextEstimateActive = true;
            a.fileContent = expanded ? "a\nx\nb\nc" : "a\nb\nc";
            a.largeTextPartitionWindowStartLine = 1;
            a.largeTextPartitionEndLine = 3;
            a.largeTextTotalLogicalLines = 3;
            a.largeTextEstimatedTotalChars = a.fileContent.length();
            a.result = new LargeTextLinePartitionResult(a.fileContent, 3, 1, 3, 3,
                    0, 0, a.fileContent.length(), 1, false, a.fileContent.length());
            if (cached) a.largeTextPartitionCache.cache(a.result);
            new ReaderLargeTextPartitionNavigator(a).switchToDisplayLine(2);
        });
        JumpActivity a = holder[0];
        try {
            assertTrue("worker must deliver its landing", a.landed.await(5, TimeUnit.SECONDS));
            assertEquals(1, a.lineScans);
            assertEquals(atLineReads, a.atLineReads);
            assertEquals(fullReads, a.fullReads);
            assertEquals(2, a.target);
            assertSame(a.result, a.applied);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                a.activityDestroyed = true;
                a.handler.removeCallbacksAndMessages(null);
                a.executor.shutdownNow();
                a.largeTextPartitionExecutor.shutdownNow();
                a.largeTextSearchExecutor.shutdownNow();
                a.largeTextSearchCountExecutor.shutdownNow();
            });
        }
    }

    private static final class JumpActivity extends ReaderActivity {
        JumpActivity() { attachBaseContext(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
        final CountDownLatch landed = new CountDownLatch(1);
        boolean loading;
        int lineScans, atLineReads, fullReads, target;
        LargeTextLinePartitionResult result, applied;
        @Override boolean ensureLoadedTextCurrent() { return true; }
        @Override int getDisplayedCurrentPageNumber() { return 1; }
        @Override int getDisplayedTotalPageCount() { return 1; }
        @Override void cancelLargeTextBoundaryHandoffs() { }
        @Override void showLoadingWindowForPartitionJump(int generation) { loading = true; }
        @Override void hideLoadingWindowForPartitionJumpIfCurrent(boolean hide, int generation) { if (hide) loading = false; }
        @Override int resolveLargeTextCharPositionForLine(File file, int line, BooleanSupplier keepReading) {
            lineScans++;
            return 2;
        }
        @Override LargeTextLinePartitionResult readLargeTextLinePartitionForChar(File file, int target) {
            fullReads++;
            return result;
        }
        @Override LargeTextLinePartitionResult readLargeTextLinePartitionAtStartLine(
                File file, int start, int lines, int chars, boolean lookbehind) {
            atLineReads++;
            return result;
        }
        @Override void applyLargeTextPartitionInPlace(LargeTextLinePartitionResult partition,
                int target, int page, int total, String before, String after,
                boolean hide, int generation, ReaderLargeTextPartitionNavigator.ViewportHandoff viewport) {
            this.applied = partition;
            this.target = target;
            landed.countDown();
        }
    }
}
