package com.readwide.manager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Delayed retry ownership only; no PDF extraction, TTS engine or speech. */
@RunWith(AndroidJUnit4.class)
public class PdfTtsRetryInstrumentedTest {
    @Test public void failedPendingExtractionDoesNotAutomaticallyBuildAgain() {
        check(f -> {
            assertNull(f.activity.pdfTtsTextSource);
            assertFalse(f.controller.isTextBuilding());
            f.controller.retryAutoStart(0);
            assertFalse(f.controller.isTextBuilding());
            assertTrue(f.worker.queued.isEmpty());
            assertEquals(0, f.activity.playbackEntries);
        });
    }

    @Test public void successfullyPreparedTextStillReachesPlayback() {
        check(f -> {
            f.activity.pdfTtsTextSource = source(f.activity, true);
            try {
                f.controller.retryAutoStart(0);
                fail("Expected the fake playback entry");
            } catch (PlaybackReached expected) { }
            assertEquals(1, f.activity.playbackEntries);
            assertTrue(f.worker.queued.isEmpty());
        });
    }

    @Test public void completedScannedDocumentDoesNotRetryExtraction() {
        check(f -> {
            f.activity.pdfTtsTextSource = source(f.activity, false);
            f.controller.retryAutoStart(0);
            assertEquals(0, f.activity.playbackEntries);
            assertTrue(f.worker.queued.isEmpty());
        });
    }

    @Test public void replacementOrDestroyedHostRejectsOldRetry() {
        check(f -> {
            f.activity.pdfTtsTextSource = source(f.activity, true);
            f.controller.onDocumentChanged();
            f.controller.retryAutoStart(0);
            f.activity.activityDestroyed = true;
            f.controller.retryAutoStart(1);
            assertEquals(0, f.activity.playbackEntries);
            assertTrue(f.worker.queued.isEmpty());
        });
    }

    private static PdfTtsTextSource source(Screen activity, boolean hasText) {
        try {
            Constructor<PdfTtsTextSource> constructor = PdfTtsTextSource.class.getDeclaredConstructor(
                    PdfReaderActivity.class, String.class, int[].class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(activity, hasText ? "text" : "", new int[]{0, hasText ? 4 : 0}, hasText);
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
        final PdfTtsIntegrationController controller = new PdfTtsIntegrationController(activity);
        final QueuedExecutor worker = new QueuedExecutor();
        Fixture() {
            activity.executor.shutdownNow();
            try {
                Field field = PdfReaderActivity.class.getDeclaredField("executor");
                field.setAccessible(true);
                field.set(activity, worker);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        void close() {
            activity.activityDestroyed = true;
            TtsPlaybackBridge.unregister(activity);
            activity.executor.shutdownNow();
            activity.handler.removeCallbacksAndMessages(null);
        }
    }

    private static final class PlaybackReached extends RuntimeException { }

    private static final class Screen extends PdfReaderActivity {
        int playbackEntries;
        @Override boolean pdfSupportsTts() { return true; }
        @Override ReaderTtsController pdfTts() {
            playbackEntries++;
            throw new PlaybackReached();
        }
    }

    private static final class QueuedExecutor extends ThreadPoolExecutor {
        final List<Runnable> queued = new ArrayList<>();
        QueuedExecutor() { super(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable command) { queued.add(command); }
    }
}
