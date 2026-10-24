package com.readwide.manager;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.DocumentAnnotation;
import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Manual saves must not capture an unlanded text window or wrap excerpt ranges. */
@RunWith(AndroidJUnit4.class)
public class ReaderBookmarkActionInstrumentedTest {
    @Test public void unreadyTextDoesNotReadOrSaveAPositionEvenWithAnOldLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            BookmarkActivity activity = prepareReader(true);
            try {
                assertTrue(activity.readerView.hasCurrentTextLayout());
                activity.textContentReadyForPersistence = false;
                assertRejected(activity);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void readyFlagWithoutAViewOrCurrentLayoutCannotSave() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            BookmarkActivity activity = prepareReader(false);
            try {
                activity.textContentReadyForPersistence = true;
                assertFalse(activity.readerView.hasCurrentTextLayout());
                assertRejected(activity);
                activity.readerView.releaseTextResources();
                activity.readerView = null;
                assertRejected(activity);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void readySaveAndDuplicateUpdateKeepNormalDocumentEndBound() throws Exception {
        withManager(manager -> {
            BookmarkActivity activity = prepareReader(true);
            try {
                activity.bookmarkManager = manager;
                activity.savedPosition = activity.totalChars - 2;
                int[] completed = {0};
                ReaderBookmarkActionController controller = new ReaderBookmarkActionController(activity);
                controller.saveCurrentBookmarkTekStyle(() -> completed[0]++);
                List<Bookmark> saved = manager.getBookmarksForFile(activity.filePath);
                assertEquals(1, saved.size());
                assertEquals(activity.savedPosition, saved.get(0).getCharPosition());
                assertEquals(activity.totalChars, saved.get(0).getEndPosition());
                controller.saveCurrentBookmarkTekStyle(() -> completed[0]++);
                assertEquals(1, manager.getBookmarksForFile(activity.filePath).size());
                assertEquals(2, completed[0]);
                assertEquals(2, activity.positionReads);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void staleSourceOrTransformCannotCreateOrUpdateABookmark() throws Exception {
        withManager(manager -> {
            BookmarkActivity activity = prepareReader(true);
            try {
                activity.bookmarkManager = manager;
                activity.sourceCurrent = false;
                assertRejected(activity);
                assertTrue(manager.getBookmarksForFile(activity.filePath).isEmpty());
                assertEquals(1, activity.sourceChecks);

                activity.sourceCurrent = true;
                new ReaderBookmarkActionController(activity).saveCurrentBookmarkTekStyle(null);
                Bookmark saved = manager.getBookmarksForFile(activity.filePath).get(0);
                saved.setExcerpt("existing bookmark");
                manager.updateBookmark(saved);
                activity.positionReads = 0;
                activity.sourceCurrent = false;
                assertRejected(activity);
                assertEquals(1, manager.getBookmarksForFile(activity.filePath).size());
                assertEquals("existing bookmark", manager.getBookmarksForFile(activity.filePath).get(0).getExcerpt());
                assertEquals(3, activity.sourceChecks);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void partitionBookmarkExcerptEndSaturatesInsteadOfWrapping() throws Exception {
        withManager(manager -> {
            BookmarkActivity activity = prepareReader(true);
            try {
                activity.bookmarkManager = manager;
                activity.largeTextEstimateActive = true;
                activity.savedPosition = Integer.MAX_VALUE - 2;
                new ReaderBookmarkActionController(activity).saveCurrentBookmarkTekStyle(null);
                Bookmark saved = manager.getBookmarksForFile(activity.filePath).get(0);
                assertEquals(Integer.MAX_VALUE - 2, saved.getCharPosition());
                assertEquals(Integer.MAX_VALUE, saved.getEndPosition());
            } finally { releaseReader(activity); }
        });
    }

    @Test public void staleSourceCannotCreateANoteOrHighlightFromOldSelection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            BookmarkActivity activity = prepareReader(true);
            try {
                activity.sourceCurrent = false;
                callAnnotation(activity, "addTxtAnnotation", DocumentAnnotation.TYPE_NOTE, 0, 5);
                callAnnotation(activity, "addTxtAnnotation", DocumentAnnotation.TYPE_HIGHLIGHT, 0, 5);
                assertEquals(2, activity.sourceChecks);
                assertEquals(0, activity.positionReads);
            } finally { releaseReader(activity); }
        });
    }

    @Test public void annotationCoordinatesKeepOrdinaryPartitionOffsetsAndNeverWrap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            BookmarkActivity activity = prepareReader(true);
            try {
                activity.largeTextEstimateActive = true;
                activity.largeTextPreviewBaseCharOffset = 100;
                DocumentAnnotation normal = (DocumentAnnotation) callAnnotation(activity,
                        "buildTxtAnnotation", DocumentAnnotation.TYPE_HIGHLIGHT, 2, 9);
                assertEquals(102, normal.getStartPosition());
                assertEquals(109, normal.getEndPosition());
                activity.largeTextPreviewBaseCharOffset = Integer.MAX_VALUE - 3;
                DocumentAnnotation end = (DocumentAnnotation) callAnnotation(activity,
                        "buildTxtAnnotation", DocumentAnnotation.TYPE_HIGHLIGHT, 2, 9);
                assertEquals(Integer.MAX_VALUE - 1, end.getStartPosition());
                assertEquals(Integer.MAX_VALUE, end.getEndPosition());
            } finally { releaseReader(activity); }
        });
    }

    private static Object callAnnotation(BookmarkActivity activity, String methodName,
                                         String type, int start, int end) {
        try {
            Method method = ReaderActivity.class.getDeclaredMethod(methodName,
                    String.class, String.class, int.class, int.class);
            method.setAccessible(true);
            return method.invoke(activity, type, "first", start, end);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertRejected(BookmarkActivity activity) {
        // No manager is installed: any attempt past the guard would also fail loudly.
        int[] completed = {0};
        new ReaderBookmarkActionController(activity).saveCurrentBookmarkTekStyle(() -> completed[0]++);
        assertEquals(0, completed[0]);
        assertEquals(0, activity.positionReads);
    }

    private interface SaveCase { void run(BookmarkManager manager); }

    private static void withManager(SaveCase testCase) throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File storage = new File(target.getCacheDir(), "bookmark-save-" + UUID.randomUUID());
        assertTrue(storage.mkdir());
        try {
            Context isolated = new ContextWrapper(target) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return storage; }
            };
            Constructor<BookmarkManager> constructor = BookmarkManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            BookmarkManager manager = constructor.newInstance(isolated);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> testCase.run(manager));
        } finally {
            File[] files = storage.listFiles();
            if (files != null) for (File file : files) assertTrue(file.delete());
            assertTrue(storage.delete());
        }
    }

    private static BookmarkActivity prepareReader(boolean layout) {
        BookmarkActivity activity = new BookmarkActivity();
        activity.filePath = "/bookmark-save-test.txt";
        activity.fileName = "bookmark-save-test.txt";
        activity.fileContent = "first line\nsecond line\nthird line";
        activity.totalChars = activity.fileContent.length();
        activity.readerView = new CustomReaderView(activity.getApplicationContext());
        activity.readerView.setReaderStyle(18f, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
        activity.readerView.setTextContent(activity.fileContent);
        if (layout) {
            activity.readerView.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
            activity.readerView.layout(0, 0, 720, 960);
        }
        activity.textContentReadyForPersistence = true;
        return activity;
    }

    private static void releaseReader(BookmarkActivity activity) {
        activity.activityDestroyed = true;
        activity.handler.removeCallbacksAndMessages(null);
        if (activity.readerView != null) activity.readerView.releaseTextResources();
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }

    private static final class BookmarkActivity extends ReaderActivity {
        int savedPosition;
        int positionReads;
        int sourceChecks;
        boolean sourceCurrent = true;
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
        @Override boolean ensureLoadedTextCurrent() { sourceChecks++; return sourceCurrent; }
        @Override int getBookmarkSaveCharPosition() { positionReads++; return savedPosition; }
        @Override int countLinesUntilChar(int position) { return 1; }
        @Override String getExcerpt(int position) { return "excerpt for save"; }
        @Override String getAnchorTextBefore(int position) { return "before"; }
        @Override String getAnchorTextAfter(int position) { return "after"; }
        @Override int getStatusCurrentPageNumber() { return 1; }
        @Override int getDisplayedTotalPageCount() { return 1; }
        @Override String buildCurrentLargeTextExactPageIndexSignature(String path) { return ""; }
        @Override void updatePositionLabel() { }
    }
}
