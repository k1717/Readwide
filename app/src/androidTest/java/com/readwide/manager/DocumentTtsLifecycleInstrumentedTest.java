package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Queued lifecycle callbacks only; no TTS engine, document load, or speech. */
@RunWith(AndroidJUnit4.class)
public class DocumentTtsLifecycleInstrumentedTest {
    @Test public void replacementIntentCanArmAutostartAgainButNotTwicePerLoad() {
        check(f -> {
            f.controller.onLoadFromIntent(autostart());
            assertEquals(1, f.view.polls.size());
            f.replaceDocument();
            f.controller.onLoadFromIntent(autostart());
            assertEquals(2, f.view.polls.size());
            assertEquals(true, field(f.controller, "pendingAutoStartTts"));
            assertEquals(0, field(f.controller, "autoStartTtsAttempts"));
            f.controller.onLoadFromIntent(autostart());
            assertEquals(2, f.view.polls.size());
        });
    }

    @Test public void oldPollCannotConsumeTheReplacementDocumentsRetryBudget() {
        check(f -> {
            f.controller.onLoadFromIntent(autostart());
            Runnable oldPoll = f.view.polls.get(0);
            f.replaceDocument();
            f.controller.onLoadFromIntent(autostart());
            oldPoll.run();
            assertEquals(0, field(f.controller, "autoStartTtsAttempts"));
            assertEquals(2, f.view.polls.size());
            f.view.polls.get(1).run();
            assertEquals(1, field(f.controller, "autoStartTtsAttempts"));
            assertEquals(3, f.view.polls.size());
        });
    }

    @Test public void ordinaryReplacementDoesNotInheritPendingAutostart() {
        check(f -> {
            f.controller.onLoadFromIntent(autostart());
            f.replaceDocument();
            f.controller.onLoadFromIntent(new Intent());
            f.view.polls.get(0).run();
            assertEquals(false, field(f.controller, "pendingAutoStartTts"));
            assertEquals(false, field(f.controller, "autoStartTtsConsumed"));
            assertEquals(1, f.view.polls.size());
        });
    }

    @Test public void staleBuildCannotClearANewerBuildOrPublishItsBuffer() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Fixture[] fixture = {null};
        int[] completions = {0};
        try {
            instrumentation.runOnMainSync(() -> {
                Fixture f = fixture[0] = new Fixture();
                build(f.controller, () -> completions[0]++);
                f.replaceDocument();
                build(f.controller, () -> completions[0]++);
                assertTrue(f.controller.isTextBuilding());
                f.activity.work.get(0).run();
            });
            // The unattached Activity posts runOnUiThread callbacks. Separate
            // main-queue steps let each completion arrive before its assertion.
            instrumentation.runOnMainSync(() -> {
                Fixture f = fixture[0];
                assertTrue(f.controller.isTextBuilding());
                assertNull(f.activity.documentTtsTextSource);
                assertEquals(0, completions[0]);
                f.activity.work.get(1).run();
            });
            instrumentation.runOnMainSync(() -> {
                Fixture f = fixture[0];
                assertFalse(f.controller.isTextBuilding());
                assertNotNull(f.activity.documentTtsTextSource);
                assertEquals(1, completions[0]);
            });
        } finally {
            instrumentation.runOnMainSync(() -> { if (fixture[0] != null) fixture[0].close(); });
        }
    }

    @Test public void shutdownOrDestroyedHostDoesNotLeaveABuildPending() {
        check(f -> {
            f.activity.executor.shutdown();
            build(f.controller, () -> fail("Stopped executor must not publish"));
            assertFalse(f.controller.isTextBuilding());
            assertTrue(f.activity.work.isEmpty());
            f.activity.activityDestroyed = true;
            build(f.controller, () -> fail("Destroyed host must not publish"));
            assertFalse(f.controller.isTextBuilding());
        });
    }

    @Test public void failedPendingBuildDoesNotRestartFromAutostartRetry() {
        check(f -> {
            f.activity.supportsTts = true;
            try {
                Field busy = DocumentTtsIntegrationController.class.getDeclaredField("textBuilding");
                busy.setAccessible(true);
                busy.setBoolean(f.controller, true);
                Method start = DocumentTtsIntegrationController.class.getDeclaredMethod("autostart");
                start.setAccessible(true);
                start.invoke(f.controller);
                assertEquals(1, f.view.polls.size());
                busy.setBoolean(f.controller, false);
                assertNull(f.activity.documentTtsTextSource);
                f.view.polls.get(0).run();
                assertTrue(f.activity.work.isEmpty());
                assertEquals(1, f.view.polls.size());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }

    private static Intent autostart() {
        return new Intent().putExtra(DocumentPageActivity.EXTRA_AUTOSTART_TTS, true);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = DocumentTtsIntegrationController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void build(DocumentTtsIntegrationController target, Runnable ready) {
        try {
            Method method = DocumentTtsIntegrationController.class.getDeclaredMethod(
                    "buildTextSourceThen", Runnable.class);
            method.setAccessible(true);
            method.invoke(target, ready);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void check(Consumer<Fixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try { check.accept(f); }
            finally {
                f.close();
            }
        });
    }

    private static final class Fixture {
        final Screen activity = new Screen();
        final QueuedWebView view = new QueuedWebView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        final DocumentTtsIntegrationController controller = new DocumentTtsIntegrationController(activity);
        Fixture() { activity.webView = view; activity.loadGeneration = 1; }
        void replaceDocument() { controller.reset(); activity.loadGeneration++; }
        void close() {
            activity.activityDestroyed = true;
            TtsPlaybackBridge.unregister(activity);
            activity.executor.shutdownNow();
            view.destroy();
        }
    }

    private static final class Screen extends DocumentPageActivity {
        final List<Runnable> work = new ArrayList<>();
        boolean supportsTts;
        @Override public void submitDocumentTask(Runnable task) { work.add(task); }
        @Override boolean documentSupportsTts() { return supportsTts; }
    }

    private static final class QueuedWebView extends WebView {
        final List<Runnable> polls = new ArrayList<>();
        QueuedWebView(Context context) { super(context); }
        @Override public boolean postDelayed(Runnable action, long delayMillis) {
            if (polls == null) return super.postDelayed(action, delayMillis);
            polls.add(action);
            return true;
        }
    }
}
