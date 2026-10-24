package com.readwide.manager;

import android.content.Context;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.search.LargeTextSearchEngine;
import com.readwide.manager.util.SearchOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Regression source only; no device execution is implied. */
@RunWith(AndroidJUnit4.class)
public class ReaderSearchCountRecoveryInstrumentedTest {
    private static final String QUERY = "count-recovery-target";

    @Test public void allocationFailureKeepsTotalUnknownAndAllowsRetry() throws Exception {
        assertCountFailureRecovers(new OutOfMemoryError("synthetic count allocation"));
    }

    @Test public void regexStackFailureKeepsTotalUnknownAndAllowsRetry() throws Exception {
        assertCountFailureRecovers(new StackOverflowError("synthetic regex stack"));
    }

    private static void assertCountFailureRecovers(Error failure) throws Exception {
        AtomicInteger opens = new AtomicInteger();
        Fixture fixture = createFixture(file -> {
            if (opens.incrementAndGet() == 1) throw failure;
            return matchingReader();
        });
        try {
            onMain(() -> fixture.controller.startLargeTextSearchTotalCountInBackground(QUERY, fixture.status));
            drainCountWorker(fixture.activity);
            onMain(() -> {
                assertFalse(fixture.inFlight());
                assertEquals(-1, fixture.controller.getCachedLargeTextSearchTotal(QUERY));
                assertEquals("1 / …", fixture.status.getText().toString());
                fixture.controller.startLargeTextSearchTotalCountInBackground(QUERY, fixture.status);
            });
            drainCountWorker(fixture.activity);
            onMain(() -> {
                assertFalse(fixture.inFlight());
                assertEquals(1, fixture.controller.getCachedLargeTextSearchTotal(QUERY));
                assertEquals("1 / 1", fixture.status.getText().toString());
                assertEquals(2, opens.get());
            });
        } finally { fixture.close(); }
    }

    @Test public void staleSameQueryCallbackCannotClearRetryTicket() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch releaseRetry = new CountDownLatch(1);
        Fixture fixture = createFixture(file -> {
            if (opens.incrementAndGet() == 2) {
                try {
                    if (!releaseRetry.await(5, TimeUnit.SECONDS)) throw new IOException("retry timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            return matchingReader();
        });
        try {
            onMain(() -> {
                fixture.controller.startLargeTextSearchTotalCountInBackground(QUERY, fixture.status);
                // Hold the main queue until A has posted its completion. Install
                // B's same-key ticket before that stale completion can run.
                drainCountWorker(fixture.activity);
                // Query changes invalidate the count without necessarily clearing
                // the old ticket. Returning to the same query must still retry.
                fixture.activity.largeTextSearchCountGeneration.incrementAndGet();
                fixture.controller.startLargeTextSearchTotalCountInBackground(QUERY, fixture.status);
                assertTrue(fixture.inFlight());
            });
            onMain(() -> {
                assertTrue("A must not retire B's ticket", fixture.inFlight());
                assertEquals(-1, fixture.controller.getCachedLargeTextSearchTotal(QUERY));
                int request = fixture.activity.largeTextSearchCountGeneration.get();
                fixture.controller.startLargeTextSearchTotalCountInBackground(QUERY, fixture.status);
                assertEquals("B is still deduplicated", request,
                        fixture.activity.largeTextSearchCountGeneration.get());
            });
            releaseRetry.countDown();
            drainCountWorker(fixture.activity);
            onMain(() -> {
                assertFalse(fixture.inFlight());
                assertEquals(1, fixture.controller.getCachedLargeTextSearchTotal(QUERY));
                assertEquals(2, opens.get());
            });
        } finally {
            releaseRetry.countDown();
            fixture.close();
        }
    }

    @Test public void cancelledCountWithoutReplacementReleasesOnlyItsOwnTicket() throws Exception {
        Fixture fixture = createFixture(file -> matchingReader());
        try {
            onMain(() -> {
                fixture.controller.startLargeTextSearchTotalCountInBackground(QUERY, fixture.status);
                drainCountWorker(fixture.activity);
                fixture.activity.largeTextSearchCountGeneration.incrementAndGet();
            });
            onMain(() -> {
                assertFalse(fixture.inFlight());
                assertEquals(-1, fixture.controller.getCachedLargeTextSearchTotal(QUERY));
                assertEquals("1 / …", fixture.status.getText().toString());
            });
        } finally { fixture.close(); }
    }

    private static BufferedReader matchingReader() {
        return new BufferedReader(new StringReader(QUERY + "\n"));
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static void drainCountWorker(CountActivity activity) {
        try {
            activity.largeTextSearchCountExecutor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("count worker did not finish", failure);
        }
    }

    private static Fixture createFixture(LargeTextSearchEngine.ReaderOpener opener) throws IOException {
        File source = File.createTempFile("txt-count-recovery-", ".txt", context().getCacheDir());
        Fixture[] result = new Fixture[1];
        onMain(() -> result[0] = new Fixture(source, opener));
        return result[0];
    }

    private static final class Fixture {
        final CountActivity activity = new CountActivity();
        final ReaderSearchController controller = new ReaderSearchController(activity);
        final TextView status = new TextView(context());
        final File source;

        Fixture(File source, LargeTextSearchEngine.ReaderOpener opener) {
            this.source = source;
            activity.filePath = source.getAbsolutePath();
            activity.largeTextEstimateActive = true;
            activity.activeSearchQuery = QUERY;
            activity.activeSearchOrdinal = 1;
            activity.largeTextSearchEngine = new LargeTextSearchEngine(context(), opener);
            status.setText("1 / …");
        }

        boolean inFlight() {
            return activity.largeTextSearchTotalCache.isInFlight(activity.filePath, QUERY,
                    activity.loadGeneration.get(), SearchOptions.literal().signature());
        }

        void close() {
            onMain(() -> {
                activity.activityDestroyed = true;
                activity.handler.removeCallbacksAndMessages(null);
                activity.largeTextSearchEngine.close();
                controller.clearTextSearchWork(true);
                activity.executor.shutdownNow();
                activity.largeTextPartitionExecutor.shutdownNow();
                activity.largeTextSearchExecutor.shutdownNow();
                activity.largeTextSearchCountExecutor.shutdownNow();
            });
            source.delete();
        }
    }

    private static final class CountActivity extends ReaderActivity {
        @Override boolean ensureLoadedTextCurrent() { return true; }
        @Override SearchOptions currentSearchOptions() { return SearchOptions.literal(); }
    }
}
