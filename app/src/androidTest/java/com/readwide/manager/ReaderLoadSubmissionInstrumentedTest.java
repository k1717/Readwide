package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReaderLoadSubmissionInstrumentedTest {
    @Test public void retainedFailureStopsSpinnerAndCannotRunOnRetry() {
        for (Throwable failure : new Throwable[] {new RejectedExecutionException("rejected"),
                new OutOfMemoryError("startup allocation"), new StackOverflowError("startup stack")}) {
            LoadActivity[] result = new LoadActivity[1];
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            AtomicInteger reads = new AtomicInteger();
            try {
                main(() -> {
                    LoadActivity activity = result[0] = new LoadActivity();
                    activity.progressBar = new ProgressBar(activity.getApplicationContext());
                    activity.progressText = new TextView(activity.getApplicationContext());
                    activity.textContentReadyForPersistence = true;
                    new ReaderFileLoadController(activity).submitLoadTask(activity.loadGeneration.get(), task -> {
                        queue.add(task);
                        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                        throw (Error) failure;
                    }, () -> reads.addAndGet(100));
                });
                main(() -> {
                    LoadActivity activity = result[0];
                    assertEquals(View.GONE, activity.progressBar.getVisibility());
                    assertFalse(activity.textContentReadyForPersistence);
                    assertTrue(activity.progressText.getText().toString().contains(failure.getMessage()));
                    new ReaderFileLoadController(activity).submitLoadTask(
                            activity.loadGeneration.incrementAndGet(), queue::add, reads::incrementAndGet);
                });
                queue.remove().run();
                queue.remove().run();
                assertEquals(1, reads.get());
            } finally { main(() -> close(result[0])); }
        }
    }

    @Test public void lateRejectionCannotRetireOrFailNewerLoad() {
        main(() -> {
            LoadActivity activity = new LoadActivity();
            try {
                activity.progressText = new TextView(activity.getApplicationContext());
                activity.progressText.setText("newer load");
                new ReaderFileLoadController(activity).submitLoadTask(activity.loadGeneration.get(), task -> {
                    activity.loadGeneration.incrementAndGet();
                    throw new RejectedExecutionException("old load");
                }, () -> fail("Rejected task must not run"));
                assertEquals(1, activity.loadGeneration.get());
                assertEquals("newer load", activity.progressText.getText().toString());
            } finally { close(activity); }
        });
    }

    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void close(LoadActivity activity) {
        if (activity == null) return;
        activity.activityDestroyed = true;
        activity.handler.removeCallbacksAndMessages(null);
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }
    private static final class LoadActivity extends ReaderActivity {
        @Override public Context getApplicationContext() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
    }
}
