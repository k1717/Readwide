package com.readwide.manager;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.Bookmark;
import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.LargeTextPageIndexCache;
import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.Assert.*;

/** Revision migration only: exact anchors are supplied without starting a worker. */
@RunWith(AndroidJUnit4.class)
public class ReaderEofBookmarkMigrationInstrumentedTest {
    private static final String OLD_REVISION = "txt-v5-px-boundary";
    private static final String NEW_REVISION = "txt-v6-eof-overlap";

    @Test public void oldPagesAreRejectedThenRefreshedWithoutMovingBookmark() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File storage = new File(target.getCacheDir(), "eof-bookmark-" + UUID.randomUUID());
        assertTrue(storage.mkdir());
        Context isolated = isolatedContext(target, storage);
        BookmarkManager manager = newManager(isolated);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MigrationActivity activity = prepareReader();
                activity.bookmarkManager = manager;
                try {
                    String signature = activity.buildCurrentLargeTextExactPageIndexSignature(activity.filePath);
                    assertTrue(signature.startsWith(NEW_REVISION + "|"));
                    String oldSignature = OLD_REVISION + signature.substring(NEW_REVISION.length());
                    Bookmark bookmark = new Bookmark(activity.filePath, "eof-overlap.txt", 23, 3, "third line");
                    bookmark.setAnchorTextBefore("second line\n");
                    bookmark.setAnchorTextAfter("third line");
                    bookmark.setPageNumber(3);
                    bookmark.setTotalPages(3);
                    bookmark.setPageLayoutSignature(oldSignature);
                    bookmark.setLabel("keep this passage");
                    bookmark.setUpdatedAt(123456L);
                    manager.addBookmark(bookmark);
                    int endPosition = bookmark.getEndPosition();

                    int oldGeneration = activity.largeTextExactPageIndexState.beginBuild(oldSignature);
                    assertTrue(activity.largeTextExactPageIndexState.completeCurrent(
                            oldGeneration, oldSignature, anchors(0, 11, 23), false, "").ready);
                    ReaderBookmarkPageModelController pages = new ReaderBookmarkPageModelController(activity);
                    assertArrayEquals(new int[]{0, 0},
                            pages.resolveBookmarkDisplayPageTarget(bookmark, bookmark.getCharPosition()));

                    assertTrue(activity.largeTextExactPageIndexState.invalidateIfSignatureChanged(signature));
                    pages.syncCurrentFileBookmarksToLargeTextExactPageModel();
                    assertEquals("No replacement metadata before the new index is ready", oldSignature,
                            bookmark.getPageLayoutSignature());
                    int generation = activity.largeTextExactPageIndexState.beginBuild(signature);
                    assertTrue(activity.largeTextExactPageIndexState.completeCurrent(
                            generation, signature, anchors(0, 11), false, "").ready);
                    pages.syncCurrentFileBookmarksToLargeTextExactPageModel();

                    assertArrayEquals(new int[]{2, 2},
                            pages.resolveBookmarkDisplayPageTarget(bookmark, bookmark.getCharPosition()));
                    assertEquals(signature, bookmark.getPageLayoutSignature());
                    assertBookmarkLocationUnchanged(bookmark, endPosition);
                } finally {
                    releaseReader(activity);
                }
            });

            Bookmark persisted = newManager(isolated).getBookmarksForFile("/eof-overlap.txt").get(0);
            assertEquals(2, persisted.getPageNumber());
            assertEquals(2, persisted.getTotalPages());
            assertTrue(persisted.getPageLayoutSignature().startsWith(NEW_REVISION + "|"));
            assertBookmarkLocationUnchanged(persisted, 33);
        } finally {
            deleteTestStorage(storage);
        }
    }

    @Test public void previousRevisionDiskAnchorsAreACacheMiss() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File cache = File.createTempFile("eof-pages-", ".bin", target.getCacheDir());
        byte[] digest = LargeTextPageIndexCache.newDigest().digest();
        try {
            LargeTextPageIndexCache.save(cache, OLD_REVISION, digest, 33, anchors(0, 11, 23), () -> { });
            assertNotNull(LargeTextPageIndexCache.load(cache, OLD_REVISION, digest, 33, () -> { }));
            assertNull(LargeTextPageIndexCache.load(cache, NEW_REVISION, digest, 33, () -> { }));
        } finally {
            assertTrue(cache.delete());
        }
    }

    private static void assertBookmarkLocationUnchanged(Bookmark bookmark, int endPosition) {
        assertEquals(23, bookmark.getCharPosition());
        assertEquals(endPosition, bookmark.getEndPosition());
        assertEquals(3, bookmark.getLineNumber());
        assertEquals("second line\n", bookmark.getAnchorTextBefore());
        assertEquals("third line", bookmark.getAnchorTextAfter());
        assertEquals("third line", bookmark.getExcerpt());
        assertEquals("keep this passage", bookmark.getLabel());
        assertEquals(123456L, bookmark.getUpdatedAt());
    }

    private static Context isolatedContext(Context target, File storage) {
        return new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return storage; }
        };
    }

    private static BookmarkManager newManager(Context context) throws Exception {
        Constructor<BookmarkManager> constructor = BookmarkManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private static MigrationActivity prepareReader() {
        MigrationActivity activity = new MigrationActivity();
        activity.readerView = new CustomReaderView(activity.getApplicationContext());
        activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
        activity.readerView.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        activity.readerView.layout(0, 0, 720, 960);
        activity.filePath = "/eof-overlap.txt";
        activity.fileContent = "first line\nsecond line\nthird line";
        activity.readerView.setTextContent(activity.fileContent);
        activity.largeTextEstimateActive = true;
        return activity;
    }

    private static ArrayList<CustomReaderView.PageTextAnchor> anchors(int... positions) {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        for (int position : positions) result.add(new CustomReaderView.PageTextAnchor(position, "", ""));
        return result;
    }

    private static void releaseReader(MigrationActivity activity) {
        activity.activityDestroyed = true;
        activity.handler.removeCallbacksAndMessages(null);
        activity.readerView.releaseTextResources();
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }

    private static void deleteTestStorage(File storage) {
        File[] files = storage.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(storage.delete());
    }

    private static final class MigrationActivity extends ReaderActivity {
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }

        // Page migration outside the loaded body uses the existing absolute character anchor.
        @Override boolean isAbsoluteCharPositionInCurrentLargeTextBody(int position) { return false; }
        @Override void updatePositionLabel() { }
    }
}
