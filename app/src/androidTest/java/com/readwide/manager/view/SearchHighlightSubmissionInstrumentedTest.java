package com.readwide.manager.view;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.util.SearchOptions;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SearchHighlightSubmissionInstrumentedTest {
    @Test public void rejectedHighlightDoesNotRetryOnEveryDrawOrLeaveRunnableWork() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = new CustomReaderView(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
            RetainingWorker worker = new RetainingWorker();
            try {
                view.setTextContent("target first\ntarget second\n");
                view.setSearchHighlight("target", -1, SearchOptions.literal());
                Field field = CustomReaderView.class.getDeclaredField("highlightWorker");
                field.setAccessible(true);
                field.set(view, worker);
                Method request = CustomReaderView.class.getDeclaredMethod("requestSearchHighlights", int.class, int.class);
                request.setAccessible(true);
                request.invoke(view, 0, 12);
                assertEquals(1, worker.calls);
                assertTrue(((FutureTask<?>) worker.retained).isCancelled());
                worker.retained.run();
                request.invoke(view, 0, 12);
                assertEquals("The same draw must not spin on allocation failure", 1, worker.calls);
                request.invoke(view, 12, 26);
                assertEquals("A new visible range may retry", 2, worker.calls);
                assertTrue(((FutureTask<?>) worker.retained).isCancelled());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                view.releaseTextResources();
                worker.shutdownNow();
            }
        });
    }

    private static final class RetainingWorker extends ThreadPoolExecutor {
        int calls;
        Runnable retained;
        RetainingWorker() { super(1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1)); }
        @Override public void execute(Runnable task) {
            calls++;
            retained = task;
            throw new OutOfMemoryError("synthetic highlight startup");
        }
    }
}
