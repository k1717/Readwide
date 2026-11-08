package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.LargeTextExactPageIndexState;
import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.*;

/** Synthetic submission failures: no real memory exhaustion or document indexing. */
@RunWith(AndroidJUnit4.class)
public class ReaderExactIndexSubmissionInstrumentedTest {
    @Test public void rejectedTaskDoesNotLeaveCacheCheckPending() {
        assertRejected(new RejectedExecutionException("stopped"));
    }

    @Test public void workerStartupAllocationFailureRetiresRetainedTask() {
        assertRejected(new OutOfMemoryError("synthetic startup"));
    }

    @Test public void workerStartupStackFailureRetiresRetainedTask() {
        assertRejected(new StackOverflowError("synthetic startup"));
    }

    @Test public void delayedSubmissionFailureCannotReplaceNewerIndex() {
        onMain(() -> {
            SubmissionActivity activity = prepareReader();
            try {
                LargeTextExactPageIndexState state = activity.largeTextExactPageIndexState;
                Queue queue = new Queue(new RejectedExecutionException("late"));
                queue.beforeThrow = () -> {
                    int replacement = state.beginBuild("replacement");
                    state.completeCurrent(replacement, "replacement", anchors(), false, "");
                };
                new ReaderLargeTextExactPageIndexController(activity).startIndexing(activity.filePath, queue);
                assertTrue(state.isReady());
                assertFalse(state.isFailed());
                assertEquals(2, state.readyPageCount());
                assertEquals(0, activity.labelUpdates);
                queue.queued.run(); // Retained old task must exit before file/cache access.
                assertTrue(state.isReady());
                assertEquals(2, state.readyPageCount());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void acceptedSubmissionKeepsExistingDuplicateSuppression() {
        onMain(() -> {
            SubmissionActivity activity = prepareReader();
            try {
                Queue queue = new Queue(null);
                ReaderLargeTextExactPageIndexController controller =
                        new ReaderLargeTextExactPageIndexController(activity);
                controller.startIndexing(activity.filePath, queue);
                controller.startIndexing(activity.filePath, queue);
                assertEquals(1, queue.submissions);
                assertNotNull(queue.queued);
                assertTrue(activity.largeTextExactPageIndexState.isCheckingCache());
                assertFalse(activity.largeTextExactPageIndexState.isFailed());
                assertEquals(0, activity.labelUpdates);
            } finally { releaseReader(activity); }
        });
    }

    private static void assertRejected(Throwable failure) {
        onMain(() -> {
            SubmissionActivity activity = prepareReader();
            try {
                ReaderLargeTextExactPageIndexController controller =
                        new ReaderLargeTextExactPageIndexController(activity);
                LargeTextExactPageIndexState state = activity.largeTextExactPageIndexState;
                Queue rejected = new Queue(failure);
                controller.startIndexing(activity.filePath, rejected);
                assertTrue(state.isFailed());
                assertFalse(state.isCheckingCache());
                assertFalse(state.isReady());
                assertEquals(1, activity.labelUpdates);
                assertEquals(123, activity.largeTextEstimatedTotalPages);
                assertNotNull(rejected.queued);
                rejected.queued.run();
                assertTrue(state.isFailed());
                assertFalse(state.isCheckingCache());

                Queue retry = new Queue(null);
                controller.startIndexing(activity.filePath, retry);
                assertEquals(1, retry.submissions);
                assertFalse(state.isFailed());
                assertTrue(state.isCheckingCache());
                rejected.queued.run(); // Old work cannot cancel the same-signature retry.
                assertTrue(state.isCheckingCache());
                assertFalse(state.isFailed());
                assertEquals(1, activity.labelUpdates);
            } finally { releaseReader(activity); }
        });
    }

    private static SubmissionActivity prepareReader() {
        SubmissionActivity activity = new SubmissionActivity();
        activity.readerView = new CustomReaderView(activity.getApplicationContext());
        activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE,
                24, 16, Typeface.DEFAULT);
        activity.readerView.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        activity.readerView.layout(0, 0, 720, 960);
        activity.filePath = "/exact-index-submission.txt";
        activity.fileContent = "first line\nsecond line\nthird line";
        activity.readerView.setTextContent(activity.fileContent);
        activity.largeTextEstimateActive = true;
        activity.largeTextEstimatedTotalPages = 123;
        activity.labelUpdates = 0;
        return activity;
    }

    private static ArrayList<CustomReaderView.PageTextAnchor> anchors() {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        result.add(new CustomReaderView.PageTextAnchor(0, "", ""));
        result.add(new CustomReaderView.PageTextAnchor(11, "", ""));
        return result;
    }

    private static void releaseReader(SubmissionActivity activity) {
        activity.activityDestroyed = true;
        activity.largeTextExactPageIndexState.invalidate();
        activity.handler.removeCallbacksAndMessages(null);
        if (activity.readerView != null) activity.readerView.releaseTextResources();
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static final class Queue implements Executor {
        final Throwable failure;
        Runnable queued;
        Runnable beforeThrow;
        int submissions;
        Queue(Throwable failure) { this.failure = failure; }
        @Override public void execute(Runnable task) {
            submissions++;
            queued = task;
            if (beforeThrow != null) beforeThrow.run();
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
        }
    }

    private static final class SubmissionActivity extends ReaderActivity {
        int labelUpdates;
        @Override boolean ensureLoadedTextCurrent() { return true; }
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
        @Override void updatePositionLabel() { labelUpdates++; }
    }
}
