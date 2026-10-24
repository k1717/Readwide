package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Failure-state ownership only; queued count workers are never executed. */
@RunWith(AndroidJUnit4.class)
public class DocumentSearchFailureInstrumentedTest {
    @Test public void failedCountReleasesBusyStateAndAllowsSameQueryRetry() {
        check(f -> {
            assertFalse(request(f.controller));
            int generation = (Integer) field(f.controller, "countGeneration");
            assertNotNull(field(f.controller, "countTask"));
            f.controller.completeCountFailure(generation, 3, new OutOfMemoryError("injected"));
            assertNull(field(f.controller, "countTask"));
            assertNull(field(f.controller, "afterCount"));
            assertEquals(-1, field(f.controller, "countsPageSize"));
            f.status.setText("…"); // Normal caller writes this after a setup failure returns false.
            f.status.delayed.remove(0).run();
            assertTrue(f.status.getText().toString().contains("OutOfMemoryError"));
            assertFalse(request(f.controller));
            assertNotNull(field(f.controller, "countTask"));
            assertEquals(2, f.worker.queued.size());
        });
    }

    @Test public void staleFailureCannotClearNewerQueryOrDocumentRequest() {
        check(f -> {
            request(f.controller);
            int generation = (Integer) field(f.controller, "countGeneration");
            Object task = field(f.controller, "countTask");
            f.controller.completeCountFailure(generation - 1, 3, new OutOfMemoryError("old query"));
            assertSame(task, field(f.controller, "countTask"));
            f.controller.completeCountFailure(generation, 2, new StackOverflowError("old document"));
            assertSame(task, field(f.controller, "countTask"));
            assertTrue(f.status.delayed.isEmpty());
        });
    }

    @Test public void queuedFailureLabelCannotOverwriteNewSearchStatus() {
        check(f -> {
            request(f.controller);
            int generation = (Integer) field(f.controller, "countGeneration");
            f.controller.completeCountFailure(generation, 3, new OutOfMemoryError("injected"));
            request(f.controller);
            f.status.setText("new request");
            f.status.delayed.remove(0).run();
            assertEquals("new request", f.status.getText().toString());
        });
    }

    @Test public void synchronousSubmissionMemoryFailureIsContainedAndRetryable() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Fixture[] holder = {null};
        try {
            instrumentation.runOnMainSync(() -> {
                Fixture f = holder[0] = new Fixture();
                f.worker.failSubmission = true;
                assertFalse(request(f.controller));
            });
            // The unattached fixture Activity posts runOnUiThread completion.
            instrumentation.runOnMainSync(() -> {
                Fixture f = holder[0];
                assertNull(field(f.controller, "countTask"));
                assertEquals(-1, field(f.controller, "countsPageSize"));
                f.worker.failSubmission = false;
                assertFalse(request(f.controller));
                assertEquals(1, f.worker.queued.size());
                assertNotNull(field(f.controller, "countTask"));
            });
        } finally {
            instrumentation.runOnMainSync(() -> { if (holder[0] != null) holder[0].close(); });
        }
    }

    private static boolean request(DocumentSearchController controller) {
        try {
            Method method = DocumentSearchController.class.getDeclaredMethod("ensureCounts", String.class, Runnable.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(controller, "chapter", (Runnable) () -> fail("Worker not executed"));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object field(DocumentSearchController controller, String name) {
        try {
            Field field = DocumentSearchController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(controller);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void check(Consumer<Fixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try { check.accept(f); } finally { f.close(); }
        });
    }

    private static final class Fixture {
        final Screen activity = new Screen();
        final DocumentSearchController controller = new DocumentSearchController(activity);
        final QueuedExecutor worker = new QueuedExecutor();
        final QueuedText status = new QueuedText(activity.getApplicationContext());
        Fixture() {
            activity.loadGeneration = 3;
            activity.pages.add(new DocumentPageActivity.Page("chapter", "<p>chapter</p>", null));
            activity.documentSearchStatusView = status;
            try {
                Field field = DocumentSearchController.class.getDeclaredField("countWorker");
                field.setAccessible(true);
                field.set(controller, worker);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        void close() {
            activity.activityDestroyed = true;
            controller.close();
            activity.executor.shutdownNow();
        }
    }

    private static final class Screen extends DocumentPageActivity {
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
    }

    private static final class QueuedExecutor extends ThreadPoolExecutor {
        final List<Runnable> queued = new ArrayList<>();
        boolean failSubmission;
        QueuedExecutor() { super(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable command) {
            if (failSubmission) throw new OutOfMemoryError("injected submission failure");
            queued.add(command);
        }
    }

    private static final class QueuedText extends TextView {
        final List<Runnable> delayed = new ArrayList<>();
        QueuedText(Context context) { super(context); }
        @Override public boolean post(Runnable action) {
            if (delayed == null) return super.post(action);
            delayed.add(action);
            return true;
        }
    }
}
