package com.readwide.manager;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.os.Handler;
import android.util.LruCache;
import android.util.SparseIntArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.image.LoadedImage;
import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.view.ZoomImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Focused handler/cache ownership checks, not a full Activity recreation/device smoke test. */
@RunWith(AndroidJUnit4.class)
public class ImageResultOwnershipInstrumentedTest {
    @Test public void requestedPageDoesNotReplaceAcceptedArchiveProgress() {
        onMain(activity -> {
            paths(activity).add("/cache/old.png");
            paths(activity).add("/cache/new.png");
            entries(activity).add("old.png");
            entries(activity).add("new.png");
            set(activity, "sourceArchivePath", "/comic.zip");
            set(activity, "acceptedImagePath", "/cache/old.png");
            set(activity, "acceptedImageEntryPath", "old.png");
            set(activity, "currentIndex", 1);
            set(activity, "filePath", "/cache/new.png");
            assertEquals(0, invoke(activity, "acceptedProgressIndex", new Class<?>[0]));
            invoke(activity, "clearCurrentImageSurface", new Class<?>[]{boolean.class}, true);
            assertEquals(0, invoke(activity, "acceptedProgressIndex", new Class<?>[0]));
            entries(activity).remove(0);
            assertEquals(-1, invoke(activity, "acceptedProgressIndex", new Class<?>[0]));
        });
    }

    @Test public void acceptedArchiveEntrySurvivesPasswordCachePathChange() {
        onMain(activity -> {
            paths(activity).add("/sensitive/new-cache.png");
            entries(activity).add("chapter/page.png");
            set(activity, "sourceArchivePath", "/comic.zip");
            set(activity, "acceptedImagePath", "/plain/old-cache.png");
            set(activity, "acceptedImageEntryPath", "chapter/page.png");
            assertEquals(0, invoke(activity, "acceptedProgressIndex", new Class<?>[0]));
        });
    }

    @Test public void unshownPageHasNoAcceptedProgress() {
        onMain(activity -> {
            paths(activity).add("/photo.png");
            set(activity, "filePath", "/photo.png");
            assertEquals(-1, invoke(activity, "acceptedProgressIndex", new Class<?>[0]));
        });
    }

    @Test public void blankSurfaceWithoutTrimFlagQueuesOneRecovery() {
        onMain(activity -> {
            RecordingExecutor queue = installSurface(activity);
            invoke(activity, "restoreMissingImageSurface", new Class<?>[0]);
            invoke(activity, "restoreMissingImageSurface", new Class<?>[0]);
            assertEquals(1, queue.work.size());
        });
    }

    @Test public void trimAndSequenceRestoreDoNotStartPrematureRecovery() {
        onMain(activity -> {
            RecordingExecutor queue = installSurface(activity);
            set(activity, "backgroundImageMemoryReleased", true);
            invoke(activity, "restoreMissingImageSurface", new Class<?>[0]);
            set(activity, "backgroundImageMemoryReleased", false);
            set(activity, "restoringArchiveSequence", true);
            invoke(activity, "restoreMissingImageSurface", new Class<?>[0]);
            assertEquals(0, queue.work.size());
            set(activity, "restoringArchiveSequence", false);
            invoke(activity, "restoreMissingImageSurface", new Class<?>[0]);
            assertEquals(1, queue.work.size());
        });
    }

    @Test public void healthySurfaceIsNotDecodedAgainOnResume() {
        onMain(activity -> {
            RecordingExecutor queue = installSurface(activity);
            Bitmap bitmap = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
            try {
                ((ZoomImageView) get(activity, "imageView")).setImageBitmapReady(bitmap);
                set(activity, "currentBitmap", bitmap);
                set(activity, "displayedImageIndex", 0);
                set(activity, "displayedImagePath", "/photo.png");
                invoke(activity, "restoreMissingImageSurface", new Class<?>[0]);
                assertTrue((Boolean) invoke(activity, "hasUsableCurrentSurface", new Class<?>[0]));
                assertEquals(0, queue.work.size());
            } finally { bitmap.recycle(); }
        });
    }

    @Test public void detachedOrRecycledSurfaceIsNotMistakenForDisplayedContent() {
        onMain(activity -> {
            installSurface(activity);
            Bitmap bitmap = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
            try {
                set(activity, "currentBitmap", bitmap);
                set(activity, "displayedImageIndex", 0);
                set(activity, "displayedImagePath", "/photo.png");
                assertFalse((Boolean) invoke(activity, "hasUsableCurrentSurface", new Class<?>[0]));
                ((ZoomImageView) get(activity, "imageView")).setImageBitmapReady(bitmap);
                bitmap.recycle();
                assertFalse((Boolean) invoke(activity, "hasUsableCurrentSurface", new Class<?>[0]));
            } finally { bitmap.recycle(); }
        });
    }

    @Test public void resumedDrawableIsReboundWithoutChangingPanOrZoom() {
        onMain(activity -> {
            RecordingExecutor queue = installSurface(activity);
            Bitmap bitmap = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
            try {
                ZoomImageView view = (ZoomImageView) get(activity, "imageView");
                view.setImageBitmapReady(bitmap);
                Field matrixField = ZoomImageView.class.getDeclaredField("matrix");
                matrixField.setAccessible(true);
                Matrix matrix = (Matrix) matrixField.get(view);
                matrix.postScale(2f, 2f);
                matrix.postTranslate(-17f, -23f);
                view.setImageMatrix(matrix);
                float[] before = new float[9];
                float[] after = new float[9];
                view.getImageMatrix().getValues(before);
                Object drawable = view.getDrawable();
                set(activity, "currentBitmap", bitmap);
                set(activity, "displayedImageIndex", 0);
                set(activity, "displayedImagePath", "/photo.png");
                set(activity, "imageSurfaceNeedsRebind", true);
                invoke(activity, "restoreMissingImageSurface", new Class<?>[0]);
                view.getImageMatrix().getValues(after);
                assertArrayEquals(before, after, 0f);
                assertSame(drawable, view.getDrawable());
                assertEquals(false, get(activity, "imageSurfaceNeedsRebind"));
                assertEquals(0, queue.work.size());
            } finally { bitmap.recycle(); }
        });
    }

    @Test public void passwordFailuresRemainDistinctFromOrdinaryImageFailure() {
        assertTrue(ImageReaderActivity.needsArchivePassword(ArchiveSupport.ExtractionResult.failed(
                ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, null)));
        assertTrue(ImageReaderActivity.needsArchivePassword(ArchiveSupport.ExtractionResult.failed(
                ArchiveSupport.ExtractionFailure.BAD_PASSWORD, null)));
        assertFalse(ImageReaderActivity.needsArchivePassword(ArchiveSupport.ExtractionResult.failed(
                ArchiveSupport.ExtractionFailure.FAILED, null)));
        assertFalse(ImageReaderActivity.needsArchivePassword(ArchiveSupport.ExtractionResult.success()));
        assertFalse(ImageReaderActivity.needsArchivePassword(null));
    }

    @Test public void readerRetirementInvalidatesCreatorsBeforeClosing() {
        onMain(activity -> {
            assertNull(invoke(activity, "detachSequentialReader", new Class<?>[]{boolean.class}, false));
            assertEquals(1, get(activity, "sequentialReaderEpoch"));
            assertEquals(false, get(activity, "sequentialReaderClosed"));
            invoke(activity, "detachSequentialReader", new Class<?>[]{boolean.class}, true);
            assertEquals(2, get(activity, "sequentialReaderEpoch"));
            assertEquals(true, get(activity, "sequentialReaderClosed"));
        });
    }

    private interface Inspection { void inspect(ImageReaderActivity activity) throws Exception; }

    private static void onMain(Inspection inspection) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ImageReaderActivity activity = new ImageReaderActivity() {
                @Override public Context getApplicationContext() {
                    return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
                }
            };
            try { inspection.inspect(activity); }
            catch (Exception e) { throw new AssertionError(e); }
            finally { close(activity); }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> paths(ImageReaderActivity activity) throws Exception {
        return (List<String>) get(activity, "imagePaths");
    }

    @SuppressWarnings("unchecked")
    private static List<String> entries(ImageReaderActivity activity) throws Exception {
        return (List<String>) get(activity, "sourceEntryPaths");
    }

    private static RecordingExecutor installSurface(ImageReaderActivity activity) throws Exception {
        Context context = new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                androidx.appcompat.R.style.Theme_AppCompat);
        ZoomImageView view = new ZoomImageView(context);
        view.layout(0, 0, 100, 200);
        set(activity, "imageView", view);
        set(activity, "filePath", "/photo.png");
        paths(activity).add("/photo.png");
        ((ExecutorService) get(activity, "executor")).shutdownNow();
        RecordingExecutor queue = new RecordingExecutor();
        set(activity, "executor", queue);
        return queue;
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        final List<Runnable> work = new ArrayList<>();
        boolean stopped;
        @Override public void execute(Runnable task) { work.add(task); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; work.clear(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
    @Test public void cancellingUiCallbacksDoesNotDropPendingBitmapCleanup() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean uiRan = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ImageReaderActivity activity = new ImageReaderActivity();
            Bitmap bitmap = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
            LoadedImage result = LoadedImage.forBitmap(bitmap);
            try {
                Handler ui = (Handler) get(activity, "mainHandler");
                Handler results = (Handler) get(activity, "imageResultHandler");
                assertNotSame(ui, results);
                assertTrue(ui.post(() -> uiRan.set(true)));
                assertTrue(results.post(() -> {
                    try {
                        assertEquals(Boolean.TRUE, get(activity, "destroyed"));
                        invoke(activity, "recycleLoadedImage", new Class<?>[]{LoadedImage.class}, result);
                        assertTrue(bitmap.isRecycled());
                    } catch (Throwable t) { failure.set(t); }
                    finally { bitmap.recycle(); close(activity); delivered.countDown(); }
                }));
                // The same order used during destruction: invalidate, then cancel ordinary UI work.
                set(activity, "destroyed", true);
                ui.removeCallbacksAndMessages(null);
            } catch (Throwable t) {
                failure.set(t);
                bitmap.recycle();
                close(activity);
                delivered.countDown();
            }
        });
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertFalse(uiRan.get());
    }

    @Test public void rejectedBitmapLeavesNoQualityFlagsButKeepsAspectMetadata() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ImageReaderActivity activity = new ImageReaderActivity();
            Bitmap bitmap = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
            try {
                set(activity, "decodedBitmapCache", recyclingCache(1));
                assertFalse(cache(activity, bitmap, true, false));
                assertTrue(bitmap.isRecycled());
                assertFalse(((Set<?>) get(activity, "fullQualityCachedIndices")).contains(5));
                assertFalse(((Set<?>) get(activity, "singlePageProfileCachedIndices")).contains(5));
                assertEquals(1, ((SparseIntArray) get(activity, "portraitPageByIndex")).get(5, -1));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { bitmap.recycle(); close(activity); }
        });
    }

    @Test public void fullResolutionWarmupQualifiesButLowResolutionReplacementDoesNot() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ImageReaderActivity activity = new ImageReaderActivity();
            Bitmap full = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
            Bitmap low = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888);
            try {
                set(activity, "decodedBitmapCache", recyclingCache(4096));
                assertTrue(cache(activity, full, true, false));
                assertTrue(((Set<?>) get(activity, "fullQualityCachedIndices")).contains(5));
                assertTrue(((Set<?>) get(activity, "singlePageProfileCachedIndices")).contains(5));
                assertTrue(cache(activity, low, false, false));
                assertTrue(full.isRecycled());
                assertFalse(((Set<?>) get(activity, "fullQualityCachedIndices")).contains(5));
                assertFalse(((Set<?>) get(activity, "singlePageProfileCachedIndices")).contains(5));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { full.recycle(); low.recycle(); close(activity); }
        });
    }

    private static LruCache<Integer, Bitmap> recyclingCache(int bytes) {
        return new LruCache<Integer, Bitmap>(bytes) {
            @Override protected int sizeOf(Integer key, Bitmap value) { return value.getByteCount(); }
            @Override protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != newValue) oldValue.recycle();
            }
        };
    }

    private static boolean cache(ImageReaderActivity activity, Bitmap bitmap, boolean full, boolean single)
            throws ReflectiveOperationException {
        return (Boolean) invoke(activity, "cacheDecodedBitmap",
                new Class<?>[]{int.class, Bitmap.class, boolean.class, boolean.class}, 5, bitmap, full, single);
    }

    private static Object invoke(Object activity, String name, Class<?>[] types, Object... arguments)
            throws ReflectiveOperationException {
        Method method = ImageReaderActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, arguments);
    }

    private static Object get(Object activity, String name) throws ReflectiveOperationException {
        Field field = ImageReaderActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private static void set(Object activity, String name, Object value) throws ReflectiveOperationException {
        Field field = ImageReaderActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private static void close(ImageReaderActivity activity) {
        try {
            for (String name : new String[]{"executor", "sequenceExecutor", "detailExecutor",
                    "imageExportExecutor", "sourceCheckExecutor", "prefetchDecodeExecutor"}) {
                ((ExecutorService) get(activity, name)).shutdownNow();
            }
            ((Handler) get(activity, "mainHandler")).removeCallbacksAndMessages(null);
            ((Handler) get(activity, "imageResultHandler")).removeCallbacksAndMessages(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
