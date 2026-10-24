package com.readwide.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.util.LruCache;
import android.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.view.ZoomImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Source-only regression cases for rename state adoption; no real file operation or decode. */
@RunWith(AndroidJUnit4.class)
public class ImageRenameRecoveryInstrumentedTest {
    @Test public void renamingPendingBitmapRestartsItWithoutAcceptingPreviousPage() {
        assertPendingRenameRecovers(false);
    }

    @Test public void renamingPendingDrawableRestartsItWithoutAcceptingPreviousPage() {
        assertPendingRenameRecovers(true);
    }

    @Test public void renamingDisplayedBitmapPreservesItsSurfaceZoomAndCache() {
        assertDisplayedRenamePreservesSurface(false);
    }

    @Test public void renamingDisplayedDrawablePreservesItsSurfaceAndZoom() {
        assertDisplayedRenamePreservesSurface(true);
    }

    private static void assertPendingRenameRecovers(boolean drawable) {
        onMain(fixture -> {
            fixture.show(0, drawable);
            invoke(fixture.activity, "loadImageAsync", new Class<?>[0]);
            int oldGeneration = (Integer) get(fixture.activity, "imageLoadGeneration");
            assertEquals(1, fixture.queue.work.size());
            Object retained = fixture.view.getDrawable();
            fixture.rename();
            assertEquals(2, fixture.queue.work.size());
            assertSame(retained, fixture.view.getDrawable());
            assertEquals(fixture.firstPath, get(fixture.activity, "acceptedImagePath"));
            assertEquals(0, invoke(fixture.activity, "acceptedProgressIndex", new Class<?>[0]));
            assertEquals(fixture.renamedPath, get(fixture.activity, "filePath"));
            assertEquals(fixture.renamedPath, list(fixture.activity, "imagePaths").get(1));
            assertEquals("renamed.gif", list(fixture.activity, "sourceDisplayNames").get(1));
            assertFalse((Boolean) invoke(fixture.activity, "isActiveImageRequest",
                    new Class<?>[]{int.class, int.class, String.class, String.class},
                    oldGeneration, 1, fixture.targetPath, null));
            assertEquals(get(fixture.activity, "imageLoadGeneration"),
                    get(fixture.activity, "activeImageLoadGeneration"));
        });
    }

    private static void assertDisplayedRenamePreservesSurface(boolean drawable) {
        onMain(fixture -> {
            fixture.show(1, drawable);
            LruCache<Integer, Bitmap> cache = new LruCache<>(4);
            if (!drawable) cache.put(1, fixture.bitmap);
            set(fixture.activity, "decodedBitmapCache", cache);
            Object retained = fixture.view.getDrawable();
            float[] before = new float[9];
            float[] after = new float[9];
            fixture.view.getImageMatrix().getValues(before);
            fixture.rename();
            fixture.view.getImageMatrix().getValues(after);
            assertEquals(0, fixture.queue.work.size());
            assertSame(retained, fixture.view.getDrawable());
            assertArrayEquals(before, after, 0f);
            assertEquals(fixture.renamedPath, get(fixture.activity, "displayedImagePath"));
            assertEquals(fixture.renamedPath, get(fixture.activity, "acceptedImagePath"));
            assertEquals(1, invoke(fixture.activity, "acceptedProgressIndex", new Class<?>[0]));
            assertTrue((Boolean) invoke(fixture.activity, "hasUsableCurrentSurface", new Class<?>[0]));
            if (!drawable) {
                assertSame(fixture.bitmap, cache.get(1));
                assertFalse(fixture.bitmap.isRecycled());
            }
        });
    }

    private interface Inspection { void inspect(Fixture fixture) throws Exception; }

    private static void onMain(Inspection inspection) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = null;
            try {
                fixture = new Fixture();
                inspection.inspect(fixture);
            } catch (Exception e) { throw new AssertionError(e); }
            finally { if (fixture != null) fixture.close(); }
        });
    }

    private static final class Fixture {
        final String firstPath = new File("/previous.png").getAbsolutePath();
        final String targetPath = new File("/pending.gif").getAbsolutePath();
        final String renamedPath = new File("/renamed.gif").getAbsolutePath();
        final ImageReaderActivity activity = new ImageReaderActivity() {
            @Override public Context getApplicationContext() {
                return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
            }
        };
        final RecordingExecutor queue = new RecordingExecutor();
        final ZoomImageView view;
        Bitmap bitmap;

        Fixture() throws Exception {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    androidx.appcompat.R.style.Theme_AppCompat);
            view = new ZoomImageView(context);
            view.layout(0, 0, 100, 200);
            set(activity, "imageView", view);
            list(activity, "imagePaths").add(firstPath);
            list(activity, "imagePaths").add(targetPath);
            list(activity, "sourceDisplayNames").add("previous.png");
            list(activity, "sourceDisplayNames").add("pending.gif");
            set(activity, "currentIndex", 1);
            set(activity, "filePath", targetPath);
            ((ExecutorService) get(activity, "executor")).shutdownNow();
            set(activity, "executor", queue);
        }

        void show(int index, boolean drawable) throws Exception {
            if (drawable) {
                ColorDrawable surface = new ColorDrawable() {
                    @Override public int getIntrinsicWidth() { return 16; }
                    @Override public int getIntrinsicHeight() { return 32; }
                };
                set(activity, "currentDrawable", surface);
                view.setImageDrawableReady(surface);
            } else {
                bitmap = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
                set(activity, "currentBitmap", bitmap);
                view.setImageBitmapReady(bitmap);
            }
            String displayedPath = index == 0 ? firstPath : targetPath;
            set(activity, "displayedImageIndex", index);
            set(activity, "displayedImagePath", displayedPath);
            set(activity, "acceptedImagePath", displayedPath);
            Field matrixField = ZoomImageView.class.getDeclaredField("matrix");
            matrixField.setAccessible(true);
            Matrix matrix = (Matrix) matrixField.get(view);
            matrix.postScale(2f, 2f);
            matrix.postTranslate(-7f, -13f);
            view.setImageMatrix(matrix);
        }

        void rename() throws Exception {
            invoke(activity, "applyRenamedLocalImage", new Class<?>[]{File.class, File.class},
                    new File(targetPath), new File(renamedPath));
        }

        void close() {
            try {
                view.clearImageReady();
                for (String name : new String[]{"executor", "sequenceExecutor", "detailExecutor",
                        "imageExportExecutor", "sourceCheckExecutor", "prefetchDecodeExecutor"}) {
                    ((ExecutorService) get(activity, name)).shutdownNow();
                }
                ((Handler) get(activity, "mainHandler")).removeCallbacksAndMessages(null);
                ((Handler) get(activity, "imageResultHandler")).removeCallbacksAndMessages(null);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { if (bitmap != null) bitmap.recycle(); }
        }
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

    @SuppressWarnings("unchecked")
    private static List<String> list(ImageReaderActivity activity, String name) throws ReflectiveOperationException {
        return (List<String>) get(activity, name);
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
}
