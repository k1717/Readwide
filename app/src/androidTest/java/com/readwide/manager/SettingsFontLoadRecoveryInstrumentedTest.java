package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Exercises task ownership without opening an Activity, scanning fonts or changing preferences. */
@RunWith(AndroidJUnit4.class)
public class SettingsFontLoadRecoveryInstrumentedTest {
    @Test public void retainedStartupFailureCannotRunAfterRetry() {
        for (Throwable failure : failures()) {
            Fixture fixture = fixture();
            AtomicInteger reads = new AtomicInteger();
            try {
                main(() -> {
                    fixture.worker.failure = failure;
                    fixture.start(() -> { reads.addAndGet(100); return Collections.emptyList(); });
                    assertTrue(((Future<?>) fixture.worker.tasks.peek()).isCancelled());
                    assertNull(field(fixture.controller, "fontLoad"));
                    assertTrue(fixture.loading.getText().toString().contains(failure.getClass().getSimpleName()));
                    fixture.worker.failure = null;
                    fixture.start(() -> { reads.incrementAndGet(); return Collections.singletonList("font"); });
                });
                fixture.worker.tasks.remove().run();
                fixture.worker.tasks.remove().run();
                main(() -> {
                    assertEquals(1, reads.get());
                    assertEquals(Collections.singletonList("font"), fixture.delivered);
                    assertNull(field(fixture.controller, "fontLoad"));
                });
            } finally { main(fixture.controller::close); }
        }
    }

    @Test public void discoveryFailureLeavesDialogAvailableAndAllowsRetry() {
        for (Throwable failure : failures()) {
            Fixture fixture = fixture();
            try {
                main(() -> fixture.start(() -> { raise(failure); return Collections.emptyList(); }));
                fixture.worker.tasks.remove().run();
                main(() -> {
                    assertNull(field(fixture.controller, "fontLoad"));
                    assertTrue(fixture.dialog.isShowing());
                    assertTrue(fixture.loading.getText().toString().contains(failure.getClass().getSimpleName()));
                    assertTrue(fixture.delivered.isEmpty());
                    fixture.start(() -> Collections.singletonList("retry"));
                });
                fixture.worker.tasks.remove().run();
                main(() -> assertEquals(Collections.singletonList("retry"), fixture.delivered));
            } finally { main(fixture.controller::close); }
        }
    }

    @Test public void lateSuccessAndFailureCannotRetireNewRequest() {
        for (boolean failOld : new boolean[]{false, true}) {
            Fixture fixture = fixture();
            try {
                main(() -> fixture.start(() -> {
                    main(() -> fixture.start(() -> Collections.singletonList("current")));
                    if (failOld) throw new OutOfMemoryError("old discovery");
                    return Collections.singletonList("obsolete");
                }));
                fixture.worker.tasks.remove().run();
                main(() -> {
                    assertTrue(fixture.delivered.isEmpty());
                    assertEquals("loading", fixture.loading.getText().toString());
                    assertSame(fixture.worker.tasks.peek(), field(fixture.controller, "fontLoad"));
                });
                fixture.worker.tasks.remove().run();
                main(() -> assertEquals(Collections.singletonList("current"), fixture.delivered));
            } finally { main(fixture.controller::close); }
        }
    }

    @Test public void closeCancelsRetainedDiscoveryAndPreventsDelivery() {
        Fixture fixture = fixture();
        AtomicInteger reads = new AtomicInteger();
        main(() -> {
            fixture.start(() -> { reads.incrementAndGet(); return Collections.singletonList("closed"); });
            fixture.controller.close();
            assertTrue(((Future<?>) fixture.worker.tasks.peek()).isCancelled());
        });
        fixture.worker.tasks.remove().run();
        main(() -> {
            assertEquals(0, reads.get());
            assertTrue(fixture.delivered.isEmpty());
            assertTrue(fixture.worker.isShutdown());
        });
    }

    private static Throwable[] failures() {
        return new Throwable[]{new RejectedExecutionException("rejected"),
                new OutOfMemoryError("allocation"), new StackOverflowError("stack")};
    }
    private static void raise(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        throw (Error) failure;
    }
    private static void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static Fixture fixture() {
        Fixture[] result = new Fixture[1];
        main(() -> result[0] = new Fixture());
        return result[0];
    }
    private static Object field(Object target, String name, Object... replacement) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            if (replacement.length != 0) field.set(target, replacement[0]);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class Fixture {
        final SettingsReaderControlsController controller = new SettingsReaderControlsController(new FontActivity());
        final RecordingExecutor worker = new RecordingExecutor();
        final List<String> delivered = new ArrayList<>();
        final TextView loading;
        final Dialog dialog;
        Fixture() {
            Context context = new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_TextViewReader);
            loading = new TextView(context);
            dialog = new Dialog(context) {
                @Override public boolean isShowing() { return true; }
                @Override public void dismiss() {}
            };
            field(controller, "fontWorker", worker);
            field(controller, "fontDialog", dialog);
        }
        void start(Supplier<List<String>> readNames) {
            loading.setText("loading");
            controller.loadEpubFontNames(dialog, loading, readNames, delivered::addAll);
        }
    }
    private static final class FontActivity extends SettingsActivity {
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
    }
    private static final class RecordingExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Throwable failure;
        boolean stopped;
        @Override public void execute(Runnable task) {
            tasks.add(task);
            if (failure != null) raise(failure);
        }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return new ArrayList<>(tasks); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
}
