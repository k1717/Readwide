package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;

import androidx.appcompat.app.ActionBar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Initial-load completion must be tied to the current text's usable layout. */
@RunWith(AndroidJUnit4.class)
public class ReaderFileLayoutInstrumentedTest {
    private static final String TEXT = "first\nsecond\nthird";

    @Test public void normalLoadWaitsForLayoutAndDetachesBeforeReentrantLanding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                activity.textContentReadyForPersistence = true; // Previous document was ready.
                ReaderFileApplyController controller = activity.fileApplier();
                activity.onLanding = controller::finishPendingLayoutIfReady;
                controller.onFileLoaded(TEXT, 3, "/load.txt", "load.txt", 6, null, null);
                controller.finishPendingLayoutIfReady();
                assertFalse(activity.textContentReadyForPersistence);
                assertEquals(0, activity.landings);
                assertEquals(0, activity.hides);
                size(activity.readerView);
                assertEquals(1, activity.landings);
                assertEquals(6, activity.landedAt);
                assertTrue(activity.textContentReadyForPersistence);
                assertEquals(1f, activity.readerView.getAlpha(), 0f);
                assertEquals(1, activity.hides);
                controller.finishPendingLayoutIfReady();
                assertEquals(1, activity.landings);
            } finally { close(activity); }
        });
    }

    @Test public void largePreviewDoesNotStartIndexingOrPersistBeforeLanding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                activity.fileApplier().onLargeTextPreviewLoaded(TEXT, 3, "/load.txt", "load.txt",
                        106, 200L, 0L, 200L, 100, 200, true, 0, 0, null, null,
                        1f, 0, TEXT.length(), 10, 10, 12, 20);
                assertTrue(activity.largeTextEstimateActive);
                assertFalse(activity.textContentReadyForPersistence);
                assertEquals(0, activity.indexStarts);
                size(activity.readerView);
                assertEquals(106, activity.landedAt);
                assertEquals(1, activity.landings);
                assertEquals(1, activity.indexStarts);
                assertEquals(1, activity.prefetches);
                assertTrue(activity.textContentReadyForPersistence);
            } finally { close(activity); }
        });
    }

    @Test public void alreadySizedAndEmptyDocumentsCompleteWithoutAnExtraPost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                size(activity.readerView);
                activity.fileApplier().onFileLoaded(TEXT, 3, "/load.txt", "load.txt", 6, null, null);
                assertEquals(6, activity.landedAt);
                assertTrue(activity.textContentReadyForPersistence);
                activity.fileApplier().onFileLoaded("", 1, "/empty.txt", "empty.txt", 0, null, null);
                assertEquals(0, activity.landedAt);
                assertEquals(2, activity.landings);
                assertEquals(2, activity.hides);
                assertTrue(activity.textContentReadyForPersistence);
            } finally { close(activity); }
        });
    }

    @Test public void finalPartitionEofRestoresBeforeBecomingReady() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (String text : new String[]{"last", "last\n", ""}) {
                LoadActivity activity = fixture();
                try {
                    activity.onLanding = () -> assertFalse(activity.textContentReadyForPersistence);
                    activity.fileApplier().onLargeTextPreviewLoaded(text, 2, "/load.txt", "load.txt",
                            100 + text.length(), 200L, 0L, 200L, 100, 100 + text.length(),
                            true, 0, 0, null, null, 1f, 0, text.length(), 10, 10, 11, 11);
                    size(activity.readerView);
                    int expected = text.endsWith("\n") ? text.length() : Math.max(0, text.length() - 1);
                    assertEquals(100 + expected, activity.landedAt);
                    assertEquals(1, activity.landings);
                    assertEquals(-1, activity.pendingLargeTextRestorePosition);
                    assertTrue(activity.textContentReadyForPersistence);
                    assertEquals(1f, activity.readerView.getAlpha(), 0f);
                    assertEquals(1, activity.indexStarts);
                } finally { close(activity); }
            }
        });
    }

    @Test public void truncatedDocumentResumeClampsIntoFinalPartition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                activity.fileApplier().onLargeTextPreviewLoaded(TEXT, 3, "/load.txt", "load.txt",
                        99999, 200L, 0L, 200L, 100, 100 + TEXT.length(), true, 0, 0,
                        null, null, 1f, 0, TEXT.length(), 10, 10, 12, 12);
                size(activity.readerView);
                assertEquals(100 + TEXT.length() - 1, activity.landedAt);
                assertTrue(activity.textContentReadyForPersistence);
            } finally { close(activity); }
        });
    }

    @Test public void initialRestoreOutsideWindowRestartsOwnedLoadWithoutStaleHints() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int target : new int[]{6, 206}) {
                LoadActivity activity = fixture();
                try {
                    Intent launch = activity.getIntent();
                    launch.putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, 10);
                    launch.putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE, 999L);
                    activity.fileApplier().onLargeTextPreviewLoaded(TEXT, 3, "/load.txt", "load.txt",
                            target, 300L, 0L, 300L, 100, 300, true, 12, 30, null, null,
                            1f, 0, TEXT.length(), 10, 10, 12, 30);
                    size(activity.readerView);
                    assertNotNull(activity.reload);
                    assertEquals(target, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
                    assertEquals("/load.txt", activity.reload.getStringExtra(ReaderActivity.EXTRA_FILE_PATH));
                    assertEquals(12, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, 0));
                    assertEquals(30, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, 0));
                    assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE));
                    assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE));
                    assertEquals(10, launch.getIntExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, 0));
                    assertEquals(0, activity.partitionReloads);
                    assertEquals(0, activity.landings);
                    assertEquals(0, activity.indexStarts);
                    assertEquals(0, activity.hides);
                    assertFalse(activity.textContentReadyForPersistence);
                } finally { close(activity); }
            }
        });
    }

    @Test public void matchingInitialContextStillWinsOverOutsideNumericHint() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                activity.fileApplier().onLargeTextPreviewLoaded(TEXT, 3, "/load.txt", "load.txt",
                        999, 300L, 0L, 300L, 100, 300, true, 0, 0, null, "second\nthird",
                        1f, 0, TEXT.length(), 10, 10, 12, 30);
                size(activity.readerView);
                assertNull(activity.reload);
                assertEquals(106, activity.landedAt);
                assertEquals(1, activity.hides);
                assertTrue(activity.textContentReadyForPersistence);
            } finally { close(activity); }
        });
    }

    @Test public void missingInitialContextDoesNotClampTheOutsideTarget() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                activity.fileApplier().onLargeTextPreviewLoaded(TEXT, 3, "/load.txt", "load.txt",
                        206, 300L, 0L, 300L, 100, 300, true, 0, 0, "missing before", "missing after",
                        1f, 0, TEXT.length(), 10, 10, 12, 30);
                size(activity.readerView);
                assertNotNull(activity.reload);
                assertEquals(206, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
                assertEquals("missing before", activity.reload.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
                assertEquals("missing after", activity.reload.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER));
                assertFalse(activity.textContentReadyForPersistence);
                assertEquals(0, activity.landings);
            } finally { close(activity); }
        });
    }

    @Test public void allocationFailureNotificationIsOwnedByTheCurrentLoad() {
        LoadActivity[] holder = new LoadActivity[1];
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                LoadActivity activity = fixture();
                holder[0] = activity;
                activity.progressText = new android.widget.TextView(context());
                ReaderFileLoadController controller = new ReaderFileLoadController(activity);
                postFailure(controller, activity.loadGeneration.get(), new OutOfMemoryError("obsolete-allocation"));
                activity.loadGeneration.incrementAndGet();
                postFailure(controller, activity.loadGeneration.get(), new OutOfMemoryError("current-allocation"));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                String message = holder[0].progressText.getText().toString();
                assertTrue(message.contains("current-allocation"));
                assertFalse(message.contains("obsolete-allocation"));
                assertFalse(holder[0].textContentReadyForPersistence);
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (holder[0] != null) close(holder[0]);
            });
        }
    }

    @Test public void redirectedInitialLoadRevealsOnlyItsCompletedReplacement() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                activity.fileApplier().onLargeTextPreviewLoaded(TEXT, 3, "/load.txt", "load.txt",
                        206, 300L, 0L, 300L, 100, 300, true, 0, 0, null, null,
                        1f, 0, TEXT.length(), 10, 10, 12, 30);
                size(activity.readerView);
                assertNotNull(activity.reload);
                assertEquals(0f, activity.readerView.getAlpha(), 0f);
                assertEquals(0, activity.hides);
                activity.loadGeneration.incrementAndGet();
                activity.fileApplier().onLargeTextPreviewLoaded(TEXT, 3, "/load.txt", "load.txt",
                        206, 300L, 0L, 300L, 200, 300, true, 0, 0, null, null,
                        1f, 0, TEXT.length(), 20, 20, 22, 30);
                activity.fileApplier().finishPendingLayoutIfReady();
                assertEquals(206, activity.landedAt);
                assertEquals(1, activity.landings);
                assertEquals(1, activity.indexStarts);
                assertEquals(1, activity.prefetches);
                assertEquals(1, activity.hides);
                assertEquals(1f, activity.readerView.getAlpha(), 0f);
                assertTrue(activity.textContentReadyForPersistence);
            } finally { close(activity); }
        });
    }

    @Test public void sharedEndClampDoesNotInventEofAtAnInteriorWindow() {
        assertEquals(103, ReaderLargeTextPartitionApplier.clampResolvedPosition(104, 100, "end\n", false));
        assertEquals(104, ReaderLargeTextPartitionApplier.clampResolvedPosition(104, 100, "end\n", true));
        assertEquals(102, ReaderLargeTextPartitionApplier.clampResolvedPosition(999, 100, "end", true));
        assertEquals(100, ReaderLargeTextPartitionApplier.clampResolvedPosition(0, 100, "end", true));
    }

    @Test public void unusableWidthRecoversThroughStyleNotificationAtTheSameBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                ReaderFileApplyController controller = activity.fileApplier();
                activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 500, 16, Typeface.DEFAULT);
                size(activity.readerView);
                controller.onFileLoaded(TEXT, 3, "/load.txt", "load.txt", 6, null, null);
                assertFalse(activity.textContentReadyForPersistence);
                activity.readerView.setReaderListener(new CustomReaderView.ReaderListener() {
                    @Override public void onSingleTap(float x, float y) { }
                    @Override public void onTextLongPress(String text, int start, int end, float x, float y) { }
                    @Override public void onReaderScrollChanged() { controller.finishPendingLayoutIfReady(); }
                    @Override public void onReaderManualScroll() { }
                    @Override public void onReaderManualOverscroll(int direction) { }
                });
                activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
                assertEquals(720, activity.readerView.getWidth());
                assertEquals(960, activity.readerView.getHeight());
                assertEquals(6, activity.landedAt);
                assertEquals(1, activity.landings);
                assertTrue(activity.textContentReadyForPersistence);
            } finally { close(activity); }
        });
    }

    @Test public void cancellationAndStaleOwnersCannotFinishAnotherSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int mode = 0; mode < 8; mode++) {
                LoadActivity activity = fixture();
                CustomReaderView originalView = activity.readerView;
                try {
                    ReaderFileApplyController controller = activity.fileApplier();
                    controller.onFileLoaded(TEXT, 3, "/load.txt", "load.txt", 6, null, null);
                    switch (mode) {
                        case 0: activity.cancelPendingFileLayout(); break;
                        case 1: activity.loadGeneration.incrementAndGet(); break;
                        case 2: activity.largeTextPartitionSwitchGeneration.incrementAndGet(); break;
                        case 3: activity.filePath = "/other.txt"; break;
                        case 4: activity.fileContent = new String(activity.fileContent); break;
                        case 5: activity.readerView = reader(); break;
                        case 6: originalView.setTextContent("replacement"); break;
                        case 7: activity.activityDestroyed = true; break;
                        default: throw new AssertionError(mode);
                    }
                    size(originalView);
                    controller.finishPendingLayoutIfReady();
                    assertEquals("stale owner " + mode, 0, activity.landings);
                    assertEquals(0, activity.hides);
                    assertFalse(activity.textContentReadyForPersistence);
                } finally {
                    if (originalView != activity.readerView) originalView.releaseTextResources();
                    close(activity);
                }
            }
        });
    }

    @Test public void anotherAdoptionInTheSameGenerationReplacesTheOldCompletion() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LoadActivity activity = fixture();
            try {
                ReaderFileApplyController controller = activity.fileApplier();
                controller.onFileLoaded(TEXT, 3, "/load.txt", "load.txt", 6, null, null);
                controller.onFileLoaded(TEXT, 3, "/load.txt", "load.txt", 13, null, null);
                size(activity.readerView);
                assertEquals(13, activity.landedAt);
                assertEquals(1, activity.landings);
                assertEquals(1, activity.hides);
            } finally { close(activity); }
        });
    }

    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private static void postFailure(ReaderFileLoadController controller, int generation, Throwable failure) {
        try {
            java.lang.reflect.Method method = ReaderFileLoadController.class.getDeclaredMethod(
                    "postLoadError", int.class, Throwable.class);
            method.setAccessible(true);
            method.invoke(controller, generation, failure);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static CustomReaderView reader() {
        CustomReaderView view = new CustomReaderView(context());
        view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
        view.setAlpha(0f);
        return view;
    }

    private static LoadActivity fixture() {
        LoadActivity activity = new LoadActivity();
        activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, "/load.txt"));
        activity.prefs = PrefsManager.getInstance(context());
        activity.readerView = reader();
        return activity;
    }

    private static void size(CustomReaderView view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 720, 960);
    }

    private static void close(LoadActivity activity) {
        activity.cancelPendingFileLayout();
        activity.readerView.releaseTextResources();
        activity.handler.removeCallbacksAndMessages(null);
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }

    private static final class LoadActivity extends ReaderActivity {
        @Override boolean ensureLoadedTextCurrent() { return true; } // In-memory adoption fixture.
        int landedAt = -1, landings, hides, indexStarts, prefetches;
        Runnable onLanding;
        Intent reload;
        int partitionReloads;
        @Override void loadFileFromIntent(Intent intent) { reload = new Intent(intent); }
        @Override void reloadLargeTextPreviewAround(int position, int page, int total,
                String before, String after, int startLine) { partitionReloads++; }
        @Override public Resources getResources() { return context().getResources(); }
        @Override public Context getApplicationContext() { return context().getApplicationContext(); }
        @Override public ActionBar getSupportActionBar() { return null; }
        @Override void showToolbar() { }
        @Override void hideLoadingWindow() { hides++; }
        @Override void updateReaderFileTitle() { }
        @Override void refreshTxtAnnotationHighlights() { }
        @Override void applySearchHighlight() { }
        @Override void updatePositionLabel() { }
        @Override void resetLargeTextExactPageIndex() { }
        @Override void clearLargeTextPartitionCache() { }
        @Override void cacheLargeTextPartition(LargeTextLinePartitionResult partition) { }
        @Override void startLargeTextExactPageIndexing(String path) { indexStarts++; }
        @Override void scheduleLargeTextExactPageIndexingRestart() { }
        @Override void prefetchNeighborLargeTextPartitions() { prefetches++; }
        @Override String textContentTransformSignatureForPath(String path) { return "load-test-transform"; }
        @Override void scrollToCharPosition(int position) {
            landedAt = position;
            landings++;
            if (onLanding != null) onLanding.run();
        }
    }
}
