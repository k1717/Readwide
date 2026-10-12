package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.ActionBar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.LoadedTextSnapshot;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Source-only regression cases; no device execution is implied by their inclusion. */
@RunWith(AndroidJUnit4.class)
public class ReaderSnapshotRestoreInstrumentedTest {
    @Test public void sourceStampRejectsUnknownChangedTruncatedAndDeletedFiles() {
        File source = temporarySource("alpha");
        try {
            long length = source.length();
            long modified = source.lastModified();
            assertTrue(ReaderLoadedTextSnapshotController.sourceMatchesContent(source, length, modified));
            assertFalse(ReaderLoadedTextSnapshotController.sourceMatchesContent(source, -1, modified));
            assertFalse(ReaderLoadedTextSnapshotController.sourceMatchesContent(source, length, -1));
            assertFalse(ReaderLoadedTextSnapshotController.sourceMatchesContent(null, length, modified));
            assertFalse(ReaderLoadedTextSnapshotController.sourceMatchesContent(source.getParentFile(), length, modified));

            write(source, "bravo"); // Same length; the modification stamp must still be checked.
            assertTrue(source.setLastModified(modified + 2000L));
            assertFalse(ReaderLoadedTextSnapshotController.sourceMatchesContent(source, length, modified));
            assertTrue(ReaderLoadedTextSnapshotController.sourceMatchesContent(
                    source, source.length(), source.lastModified()));
            write(source, "a");
            assertTrue(source.setLastModified(modified));
            assertFalse(ReaderLoadedTextSnapshotController.sourceMatchesContent(source, length, modified));
            assertTrue(source.delete());
            assertFalse(ReaderLoadedTextSnapshotController.sourceMatchesContent(source, length, modified));
        } finally { deleteFixture(source); }
    }

    @Test public void cacheCannotGiveOldLoadedContentTheStampOfNewDiskBytes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            File source = temporarySource("alpha");
            SnapshotActivity activity = fixture(source.getAbsolutePath(), "alpha");
            ReaderLoadedTextSnapshotController controller = new ReaderLoadedTextSnapshotController(activity);
            LoadedTextSnapshot previous = snapshot();
            try {
                size(activity.readerView, 720, 960);
                activity.textContentReadyForPersistence = true;
                activity.contentsSourceLength = source.length();
                activity.contentsSourceModified = source.lastModified();
                controller.cacheLoadedTextSnapshot();
                LoadedTextSnapshot cached = snapshot();
                assertNotNull(cached);
                assertEquals(activity.contentsSourceLength, cached.sourceFileLength);
                assertEquals(activity.contentsSourceModified, cached.sourceLastModified);
                write(source, "bravo");
                assertTrue(source.setLastModified(cached.sourceLastModified + 2000L));
                controller.cacheLoadedTextSnapshot();
                assertNull("mismatching disk bytes must invalidate, not retag, the old content", snapshot());
                assertEquals("alpha", activity.fileContent);
                activity.contentsSourceLength = -1;
                activity.contentsSourceModified = -1;
                controller.cacheLoadedTextSnapshot();
                assertNull("unknown load stamps must not be treated as freshly verified", snapshot());
            } finally {
                setSnapshot(previous);
                close(activity, controller);
                deleteFixture(source);
            }
        });
    }

    @Test public void verifiedSnapshotRestoresContentStampsAndWaitsForItsFirstLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            File source = temporarySource("first\nsecond\nthird");
            SnapshotActivity producer = fixture(source.getAbsolutePath(), "first\nsecond\nthird");
            SnapshotActivity restored = fixture(source.getAbsolutePath(), "old content");
            ReaderLoadedTextSnapshotController producerController = new ReaderLoadedTextSnapshotController(producer);
            ReaderLoadedTextSnapshotController restoreController = new ReaderLoadedTextSnapshotController(restored);
            LoadedTextSnapshot previous = snapshot();
            try {
                size(producer.readerView, 720, 960);
                producer.textContentReadyForPersistence = true;
                producer.contentsSourceLength = source.length();
                producer.contentsSourceModified = source.lastModified();
                producerController.cacheLoadedTextSnapshot();
                LoadedTextSnapshot cached = snapshot();
                assertNotNull(cached);
                Bundle saved = new Bundle();
                saved.putBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, true);
                assertTrue(restoreController.restoreLoadedTextSnapshotIfAvailable(restored.getIntent(), saved));
                assertSame(cached.fileContent, restored.fileContent);
                assertEquals(cached.sourceFileLength, restored.contentsSourceLength);
                assertEquals(cached.sourceLastModified, restored.contentsSourceModified);
                assertFalse(restored.textContentReadyForPersistence);
                assertEquals(0, restored.landings);
                size(restored.readerView, 720, 960);
                assertEquals(1, restored.landings);
                assertEquals(cached.charPosition, restored.landedAt);
                assertTrue(restored.textContentReadyForPersistence);
                restoreController.finishPendingRestoreIfReady();
                assertEquals("a completed restore must not land twice", 1, restored.landings);
            } finally {
                setSnapshot(previous);
                close(producer, producerController);
                close(restored, restoreController);
                deleteFixture(source);
            }
        });
    }

    @Test public void pendingRuleChangeCannotRetagOldTextAsNewTransform() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            File source = temporarySource("alpha");
            SnapshotActivity activity = fixture(source.getAbsolutePath(), "alpha");
            ReaderLoadedTextSnapshotController controller = new ReaderLoadedTextSnapshotController(activity);
            LoadedTextSnapshot previous = snapshot();
            try {
                size(activity.readerView, 720, 960);
                activity.textContentReadyForPersistence = true;
                activity.contentsSourceLength = source.length();
                activity.contentsSourceModified = source.lastModified();
                controller.cacheLoadedTextSnapshot();
                assertNotNull(snapshot());
                activity.appliedTextDisplayRuleSignature = "older-transform";
                controller.cacheLoadedTextSnapshot();
                assertNull(snapshot());
                assertEquals("alpha", activity.fileContent);
            } finally {
                setSnapshot(previous);
                close(activity, controller);
                deleteFixture(source);
            }
        });
    }

    @Test public void partitionSnapshotRejectsAnotherPolicyButKeepsTheSavedViewport() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            File source = temporarySource("first\nsecond\nthird");
            SnapshotActivity producer = fixture(source.getAbsolutePath(), "first\nsecond\nthird");
            SnapshotActivity restored = fixture(source.getAbsolutePath(), "not replaced");
            ReaderLoadedTextSnapshotController cache = new ReaderLoadedTextSnapshotController(producer);
            ReaderLoadedTextSnapshotController restore = new ReaderLoadedTextSnapshotController(restored);
            LoadedTextSnapshot previous = snapshot();
            try {
                size(producer.readerView, 720, 960);
                producer.largeTextEstimateActive = true;
                producer.textContentReadyForPersistence = true;
                producer.appliedLargeTextPartitionMode = restored.getLargeTextPartitionMode() == 0 ? 1 : 0;
                producer.contentsSourceLength = source.length();
                producer.contentsSourceModified = source.lastModified();
                cache.cacheLoadedTextSnapshot();
                assertEquals(producer.appliedLargeTextPartitionMode, snapshot().appliedLargeTextPartitionMode);
                Bundle saved = new Bundle();
                saved.putBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, true);
                com.readwide.manager.model.ReaderState state = new com.readwide.manager.model.ReaderState();
                state.setCharPosition(snapshot().charPosition);
                saved.putBundle(ReaderLifecycleController.STATE_VIEWPORT,
                        ReaderLifecycleController.viewportBundle(restored.getIntent(), state));
                assertFalse(restore.restoreLoadedTextSnapshotIfAvailable(restored.getIntent(), saved));
                assertEquals("not replaced", restored.fileContent);
                assertEquals(state.getCharPosition(), ReaderLifecycleController.restoreViewportIntent(
                        restored.getIntent(), saved.getBundle(ReaderLifecycleController.STATE_VIEWPORT))
                        .getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
            } finally {
                setSnapshot(previous);
                close(producer, cache);
                close(restored, restore);
                deleteFixture(source);
            }
        });
    }

    @Test public void pendingPartitionCannotBeSnapshottedWithItsNewPolicy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            File source = temporarySource("alpha");
            SnapshotActivity activity = fixture(source.getAbsolutePath(), "alpha");
            ReaderLoadedTextSnapshotController controller = new ReaderLoadedTextSnapshotController(activity);
            LoadedTextSnapshot previous = snapshot();
            try {
                size(activity.readerView, 720, 960);
                activity.textContentReadyForPersistence = true;
                activity.contentsSourceLength = source.length();
                activity.contentsSourceModified = source.lastModified();
                controller.cacheLoadedTextSnapshot();
                assertNotNull(snapshot());
                activity.largeTextEstimateActive = true;
                activity.largeTextPartitionSwitchState.begin(0, 0, 1, 1);
                controller.cacheLoadedTextSnapshot();
                assertNull(snapshot());
            } finally {
                setSnapshot(previous);
                close(activity, controller);
                deleteFixture(source);
            }
        });
    }

    @Test public void matchingPartitionPolicyStillRestoresWithoutChangingCoordinates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            File source = temporarySource("first\nsecond\nthird");
            SnapshotActivity producer = fixture(source.getAbsolutePath(), "first\nsecond\nthird");
            SnapshotActivity restored = fixture(source.getAbsolutePath(), "old");
            ReaderLoadedTextSnapshotController cache = new ReaderLoadedTextSnapshotController(producer);
            ReaderLoadedTextSnapshotController restore = new ReaderLoadedTextSnapshotController(restored);
            LoadedTextSnapshot previous = snapshot();
            try {
                size(producer.readerView, 720, 960);
                producer.largeTextEstimateActive = true;
                producer.appliedLargeTextPartitionMode = producer.getLargeTextPartitionMode();
                producer.largeTextPartitionEndLine = producer.largeTextTotalLogicalLines = 3;
                producer.largeTextPartitionBodyCharCount = producer.fileContent.length();
                producer.largeTextEstimatedTotalChars = producer.fileContent.length();
                producer.textContentReadyForPersistence = true;
                producer.contentsSourceLength = source.length();
                producer.contentsSourceModified = source.lastModified();
                cache.cacheLoadedTextSnapshot();
                Bundle saved = new Bundle();
                saved.putBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, true);
                assertTrue(restore.restoreLoadedTextSnapshotIfAvailable(restored.getIntent(), saved));
                assertSame(producer.fileContent, restored.fileContent);
                size(restored.readerView, 720, 960);
                assertEquals(snapshot().charPosition, restored.landedAt);
                assertTrue(restored.textContentReadyForPersistence);
            } finally {
                setSnapshot(previous);
                close(producer, cache);
                close(restored, restore);
                deleteFixture(source);
            }
        });
    }

    @Test public void pendingRestoreWaitsForLayoutAndDetachesBeforeReentrantScroll() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SnapshotActivity activity = fixture("/snapshot-layout.txt", "first\nsecond\nthird");
            ReaderLoadedTextSnapshotController controller = new ReaderLoadedTextSnapshotController(activity);
            try {
                activity.onLanding = controller::finishPendingRestoreIfReady;
                controller.restorePositionAfterLayout(6);
                controller.finishPendingRestoreIfReady();
                assertEquals(0, activity.landings);
                assertFalse(activity.textContentReadyForPersistence);
                size(activity.readerView, 720, 960);
                assertEquals(1, activity.landings);
                assertEquals(6, activity.landedAt);
                assertTrue(activity.textContentReadyForPersistence);
                assertFalse(controller.cancelPendingRestore());
            } finally { close(activity, controller); }
        });
    }

    @Test public void cancelledAndStaleRestoreOwnersCannotLandOnAReplacementSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int mode = 0; mode < 8; mode++) {
                SnapshotActivity activity = fixture("/snapshot-owner.txt", "first\nsecond\nthird");
                ReaderLoadedTextSnapshotController controller = new ReaderLoadedTextSnapshotController(activity);
                CustomReaderView originalView = activity.readerView;
                try {
                    controller.restorePositionAfterLayout(6);
                    switch (mode) {
                        case 0: assertTrue(controller.cancelPendingRestore()); break;
                        case 1: activity.loadGeneration.incrementAndGet(); break;
                        case 2: activity.largeTextPartitionSwitchGeneration.incrementAndGet(); break;
                        case 3: activity.filePath = "/another.txt"; break;
                        case 4: activity.fileContent = new String(activity.fileContent); break;
                        case 5:
                            activity.readerView = reader();
                            activity.readerView.setTextContent(activity.fileContent);
                            break;
                        case 6: originalView.setTextContent("replacement view content"); break;
                        case 7: activity.activityDestroyed = true; break;
                        default: throw new AssertionError(mode);
                    }
                    size(originalView, 720, 960);
                    controller.finishPendingRestoreIfReady();
                    assertEquals("stale mode " + mode + " must not perform a restore", 0, activity.landings);
                    assertFalse(activity.textContentReadyForPersistence);
                    assertFalse(controller.cancelPendingRestore());
                } finally {
                    if (originalView != activity.readerView) originalView.releaseTextResources();
                    close(activity, controller);
                }
            }
        });
    }

    @Test public void sameBoundsStyleRecoveryCanFinishWithoutASecondLayoutEvent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SnapshotActivity activity = fixture("/snapshot-style.txt", "first\nsecond\nthird");
            ReaderLoadedTextSnapshotController controller = new ReaderLoadedTextSnapshotController(activity);
            try {
                activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 500, 16, Typeface.DEFAULT);
                size(activity.readerView, 720, 960); // Horizontal margins leave no usable layout width.
                assertFalse(activity.readerView.hasCurrentTextLayout());
                controller.restorePositionAfterLayout(6);
                activity.readerView.setReaderListener(new CustomReaderView.ReaderListener() {
                    @Override public void onSingleTap(float x, float y) { }
                    @Override public void onTextLongPress(String text, int start, int end, float x, float y) { }
                    @Override public void onReaderScrollChanged() { controller.finishPendingRestoreIfReady(); }
                    @Override public void onReaderManualScroll() { }
                    @Override public void onReaderManualOverscroll(int direction) { }
                });
                activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
                assertEquals(720, activity.readerView.getWidth());
                assertEquals(960, activity.readerView.getHeight());
                assertEquals(1, activity.landings);
                assertEquals(6, activity.landedAt);
                assertTrue(activity.textContentReadyForPersistence);
            } finally { close(activity, controller); }
        });
    }

    @Test public void newerRestoreCancelsThePreviousTargetBeforeLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SnapshotActivity activity = fixture("/snapshot-latest.txt", "first\nsecond\nthird");
            ReaderLoadedTextSnapshotController controller = new ReaderLoadedTextSnapshotController(activity);
            try {
                controller.restorePositionAfterLayout(6);
                controller.restorePositionAfterLayout(13);
                size(activity.readerView, 720, 960);
                assertEquals(1, activity.landings);
                assertEquals(13, activity.landedAt);
            } finally { close(activity, controller); }
        });
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static CustomReaderView reader() {
        CustomReaderView view = new CustomReaderView(context());
        view.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
        return view;
    }

    private static SnapshotActivity fixture(String path, String content) {
        SnapshotActivity activity = new SnapshotActivity();
        activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, path));
        activity.prefs = PrefsManager.getInstance(context());
        activity.filePath = path;
        activity.appliedTextDisplayRuleSignature = activity.textContentTransformSignatureForPath(path);
        activity.fileName = "fixture.txt";
        activity.fileContent = content;
        activity.totalChars = content.length();
        activity.totalLines = 3;
        activity.readerView = reader();
        activity.readerView.setTextContent(content);
        return activity;
    }

    private static void size(CustomReaderView view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static File temporarySource(String content) {
        try {
            File file = File.createTempFile("readwide-snapshot-", ".txt", context().getCacheDir());
            write(file, content);
            return file;
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static void write(File file, String content) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static void deleteFixture(File file) {
        if (file.exists()) assertTrue(file.delete());
    }

    private static LoadedTextSnapshot snapshot() {
        try { return (LoadedTextSnapshot) snapshotField().get(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void setSnapshot(LoadedTextSnapshot snapshot) {
        try { snapshotField().set(null, snapshot); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Field snapshotField() throws NoSuchFieldException {
        Field field = ReaderLoadedTextSnapshotController.class.getDeclaredField("lastLoadedTextSnapshot");
        field.setAccessible(true);
        return field;
    }

    private static void close(SnapshotActivity activity, ReaderLoadedTextSnapshotController controller) {
        controller.cancelPendingRestore();
        if (activity.readerView != null) activity.readerView.releaseTextResources();
        activity.handler.removeCallbacksAndMessages(null);
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }

    private static final class SnapshotActivity extends ReaderActivity {
        @Override boolean ensureLoadedTextCurrent() { return true; } // Metadata admission is checked separately.
        int landedAt = -1;
        int landings;
        Runnable onLanding;
        @Override public Resources getResources() { return context().getResources(); }
        @Override public ActionBar getSupportActionBar() { return null; }
        @Override void hideLoadingWindow() { }
        @Override void updateReaderFileTitle() { }
        @Override void refreshTxtAnnotationHighlights() { }
        @Override void applySearchHighlight() { }
        @Override void updatePositionLabel() { }
        @Override void scheduleLargeTextExactPageIndexingRestart() { }
        @Override void prefetchNeighborLargeTextPartitions() { }
        @Override String textContentTransformSignatureForPath(String path) { return "snapshot-test-transform"; }
        @Override void scrollToCharPosition(int position) {
            landedAt = position;
            landings++;
            if (onLanding != null) onLanding.run();
        }
    }
}
