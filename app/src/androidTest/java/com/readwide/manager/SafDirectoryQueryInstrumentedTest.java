package com.readwide.manager;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.view.ContextThemeWrapper;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.adapter.SafDocumentAdapter;
import com.readwide.manager.model.SafDocumentEntry;
import com.readwide.manager.util.PrefsManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SafDirectoryQueryInstrumentedTest {
    private static final Uri TREE = Uri.parse("content://readwide.query.fixture/tree/books");
    private static final Uri PARENT = DocumentsContract.buildDocumentUriUsingTree(TREE, "books");

    @Test public void cancelRetiresTaskSignalAndQueue() {
        check(f -> {
            FutureTask<Void> task = f.ownPending();
            CancellationSignal signal = f.signal();
            f.executor.getQueue().add(task);
            int before = f.generation();
            f.screen.cancelPendingDirectoryQuery();
            assertTrue(task.isCancelled());
            assertTrue(signal.isCanceled());
            assertEquals(before + 1, f.generation());
            assertNull(field(f.screen, "pendingDirectoryQuery"));
            assertNull(field(f.screen, "directoryQueryCancellation"));
            assertFalse((Boolean) field(f.screen, "directoryQueryInProgress"));
            assertTrue(f.executor.getQueue().isEmpty());
            assertEquals(View.GONE, f.progress.getVisibility());
        });
    }

    @Test public void repeatedCancelDoesNotInvokeSignalTwice() {
        check(f -> {
            f.ownPending();
            AtomicInteger calls = new AtomicInteger();
            f.signal().setOnCancelListener(calls::incrementAndGet);
            f.screen.cancelPendingDirectoryQuery();
            f.screen.cancelPendingDirectoryQuery();
            assertEquals(1, calls.get());
        });
    }

    @Test public void failingCancellationStillRetiresOwnership() {
        check(f -> {
            FutureTask<Void> task = f.ownPending();
            f.signal().setOnCancelListener(() -> { throw new IllegalStateException("fixture"); });
            f.screen.cancelPendingDirectoryQuery();
            assertTrue(task.isCancelled());
            assertNull(field(f.screen, "pendingDirectoryQuery"));
            assertNull(field(f.screen, "directoryQueryCancellation"));
            assertEquals(View.GONE, f.progress.getVisibility());
        });
    }

    @Test public void cancellingQueryLeavesMutationAndDocumentOpenAlone() {
        check(f -> {
            f.ownPending();
            FutureTask<Void> opened = new FutureTask<>(() -> null);
            set(f.screen, "pendingDocumentOpen", opened);
            set(f.screen, "openingDocument", true);
            set(f.screen, "deletingDocument", true);
            f.screen.cancelPendingDirectoryQuery();
            assertSame(opened, field(f.screen, "pendingDocumentOpen"));
            assertFalse(opened.isCancelled());
            assertTrue((Boolean) field(f.screen, "deletingDocument"));
            assertTrue((Boolean) field(f.screen, "openingDocument"));
            assertEquals(View.VISIBLE, f.progress.getVisibility());
        });
    }

    @Test public void oldCompletionCannotClearNewQuery() {
        check(f -> {
            f.ownPending();
            int old = f.generation();
            f.screen.cancelPendingDirectoryQuery();
            FutureTask<Void> current = f.ownPending();
            f.screen.completeDirectoryQuery(old, SafBrowserActivity.QueryResult.error());
            assertSame(current, field(f.screen, "pendingDirectoryQuery"));
            assertTrue((Boolean) field(f.screen, "directoryQueryInProgress"));
            assertFalse(f.retry.isVisible());
        });
    }

    @Test public void failureFinishesAndMakesRetryAvailable() {
        check(f -> {
            f.ownPending();
            f.screen.completeDirectoryQuery(f.generation(), SafBrowserActivity.QueryResult.error());
            assertNull(field(f.screen, "pendingDirectoryQuery"));
            assertNull(field(f.screen, "directoryQueryCancellation"));
            assertTrue(f.retry.isVisible());
            assertTrue(f.retry.isEnabled());
            assertEquals(View.GONE, f.progress.getVisibility());
            assertEquals(View.VISIBLE, f.empty.getVisibility());
        });
    }

    @Test public void successClearsRetryWithoutCancellingCompletedWork() {
        check(f -> {
            FutureTask<Void> task = f.ownPending();
            set(f.screen, "directoryQueryFailed", true);
            f.screen.completeDirectoryQuery(f.generation(), SafBrowserActivity.QueryResult.success(
                    Collections.singletonList(entry())));
            assertFalse(task.isCancelled());
            assertFalse(f.retry.isVisible());
            assertEquals(1, f.adapter.getItemCount());
            assertEquals(View.GONE, f.empty.getVisibility());
        });
    }

    @Test public void duplicateCompletionCannotReplaceAcceptedRows() {
        check(f -> {
            f.ownPending();
            int generation = f.generation();
            f.screen.completeDirectoryQuery(generation, SafBrowserActivity.QueryResult.success(
                    Collections.singletonList(entry())));
            f.screen.completeDirectoryQuery(generation, SafBrowserActivity.QueryResult.error());
            assertEquals(1, f.adapter.getItemCount());
            assertFalse(f.retry.isVisible());
        });
    }

    @Test public void retryStartsOnlyOneFreshQuery() {
        check(f -> {
            set(f.screen, "directoryQueryFailed", true);
            f.screen.retryCurrentDirectory();
            FutureTask<?> submitted = (FutureTask<?>) field(f.screen, "pendingDirectoryQuery");
            assertNotNull(submitted);
            int generation = f.generation();
            f.screen.retryCurrentDirectory();
            assertSame(submitted, field(f.screen, "pendingDirectoryQuery"));
            assertEquals(generation, f.generation());
            assertEquals(1, f.executor.getQueue().size());
            assertFalse(f.retry.isVisible());
        });
    }

    @Test public void retryCannotRunDuringMutation() { checkRetryBlocked("deletingDocument"); }
    @Test public void retryCannotRunDuringDocumentOpen() { checkRetryBlocked("openingDocument"); }
    @Test public void retryCannotRunDuringDirectoryQuery() { checkRetryBlocked("directoryQueryInProgress"); }

    @Test public void replacementKeepsOnlyLatestQueuedQuery() {
        check(f -> {
            f.load();
            FutureTask<?> first = (FutureTask<?>) field(f.screen, "pendingDirectoryQuery");
            CancellationSignal firstSignal = f.signal();
            f.load();
            FutureTask<?> second = (FutureTask<?>) field(f.screen, "pendingDirectoryQuery");
            f.load();
            assertTrue(first.isCancelled());
            assertTrue(second.isCancelled());
            assertTrue(firstSignal.isCanceled());
            assertEquals(1, f.executor.getQueue().size());
            assertSame(field(f.screen, "pendingDirectoryQuery"), f.executor.getQueue().peek());
        });
    }

    @Test public void rejectedQueuedQueryCannotPublishAfterRetry() {
        check(f -> {
            f.executor.rejectAfterQueue = true;
            f.load();
            assertTrue(f.executor.last.isCancelled());
            assertTrue(f.executor.getQueue().isEmpty());
            assertTrue(f.retry.isVisible());
            assertEquals(View.GONE, f.progress.getVisibility());
            f.executor.rejectAfterQueue = false;
            f.screen.retryCurrentDirectory();
            assertEquals(1, f.executor.getQueue().size());
            assertFalse(f.retry.isVisible());
        });
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void preCancelledQueryDoesNotContactProvider() {
        Provider provider = new Provider();
        CancellationSignal signal = new CancellationSignal();
        signal.cancel();
        assertTrue(query(provider, signal, false).error);
        assertEquals(0, provider.queries);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void cancellationDuringCursorWalkClosesAndRejectsPartialRows() {
        Provider provider = new Provider();
        CancellationSignal signal = new CancellationSignal();
        provider.cancelOnRow = 1;
        provider.cancel = signal::cancel;
        SafBrowserActivity.QueryResult result = query(provider, signal, false);
        assertTrue(result.error);
        assertTrue(result.entries.isEmpty());
        assertTrue(provider.cursor.isClosed());
        assertNotNull(provider.receivedCancellation);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void successfulQueryPreservesFilterSortAndCursorClosure() {
        Provider provider = new Provider();
        SafBrowserActivity.QueryResult result = query(provider, new CancellationSignal(), false);
        assertFalse(result.error);
        assertEquals(3, result.entries.size());
        assertEquals("folder", result.entries.get(0).getName());
        assertEquals("a.txt", result.entries.get(1).getName());
        assertEquals("b.txt", result.entries.get(2).getName());
        assertTrue(provider.cursor.isClosed());
        assertNotNull(provider.receivedCancellation);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void showHiddenStillIncludesDotFiles() {
        assertEquals(4, query(new Provider(), new CancellationSignal(), true).entries.size());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void incompleteProviderResultStillFailsClosed() {
        Provider provider = new Provider();
        provider.loading = true;
        assertTrue(query(provider, new CancellationSignal(), false).error);
        assertTrue(provider.cursor.isClosed());
    }

    private void checkRetryBlocked(String field) {
        check(f -> {
            set(f.screen, "directoryQueryFailed", true);
            set(f.screen, field, true);
            int generation = f.generation();
            f.screen.retryCurrentDirectory();
            assertEquals(generation, f.generation());
            assertTrue(f.executor.getQueue().isEmpty());
        });
    }

    @android.annotation.TargetApi(29)
    private static SafBrowserActivity.QueryResult query(Provider provider, CancellationSignal signal,
                                                        boolean hidden) {
        return SafBrowserActivity.queryChildren(ContentResolver.wrap(provider), TREE, PARENT,
                hidden, PrefsManager.SORT_NAME_ASC, signal);
    }

    private static SafDocumentEntry entry() {
        return new SafDocumentEntry(DocumentsContract.buildDocumentUriUsingTree(TREE, "book"),
                "book", "book.txt", "text/plain", false, 1, 1, 0);
    }

    private interface Check { void run(Fixture fixture); }

    private static void check(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture fixture = new Fixture();
            try { check.run(fixture); }
            finally {
                fixture.screen.cancelPendingDirectoryQuery();
                fixture.executor.shutdownNow();
                ((ThreadPoolExecutor) field(fixture.screen, "documentOpenExecutor")).shutdownNow();
            }
        });
    }

    private static final class Fixture {
        final SafBrowserActivity screen = new SafBrowserActivity();
        final QueuedExecutor executor = new QueuedExecutor();
        final ProgressBar progress;
        final TextView empty;
        final MenuItem retry;
        final SafDocumentAdapter adapter = new SafDocumentAdapter(new SafDocumentAdapter.Listener() {
            @Override public void onEntryClick(SafDocumentEntry entry) { }
            @Override public void onEntryLongClick(SafDocumentEntry entry) { }
        });
        Fixture() {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.Theme_TextViewReader);
            Toolbar toolbar = new Toolbar(context);
            retry = toolbar.getMenu().add(R.string.viewer_retry).setVisible(false);
            progress = new ProgressBar(context);
            empty = new TextView(context);
            ((ThreadPoolExecutor) field(screen, "queryExecutor")).shutdownNow();
            set(screen, "queryExecutor", executor);
            set(screen, "retryDirectoryItem", retry);
            set(screen, "toolbar", toolbar);
            set(screen, "pathView", new TextView(context));
            set(screen, "progressView", progress);
            set(screen, "emptyView", empty);
            set(screen, "adapter", adapter);
            set(screen, "treeUri", TREE);
            set(screen, "currentUri", PARENT);
            set(screen, "rootName", "books");
            set(screen, "currentName", "books");
        }
        FutureTask<Void> ownPending() {
            FutureTask<Void> task = new FutureTask<>(() -> null);
            set(screen, "pendingDirectoryQuery", task);
            set(screen, "directoryQueryCancellation", new CancellationSignal());
            set(screen, "directoryQueryInProgress", true);
            return task;
        }
        CancellationSignal signal() { return (CancellationSignal) field(screen, "directoryQueryCancellation"); }
        int generation() { return ((AtomicInteger) field(screen, "queryGeneration")).get(); }
        void load() {
            try {
                Method method = SafBrowserActivity.class.getDeclaredMethod("loadCurrentDirectory");
                method.setAccessible(true);
                method.invoke(screen);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }

    private static final class QueuedExecutor extends ThreadPoolExecutor {
        boolean rejectAfterQueue;
        FutureTask<?> last;
        QueuedExecutor() { super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable task) {
            last = (FutureTask<?>) task;
            getQueue().add(task);
            if (rejectAfterQueue) throw new OutOfMemoryError("fixture");
        }
    }

    private static final class Provider extends ContentProvider {
        int queries, cancelOnRow = -1;
        boolean loading;
        Runnable cancel;
        MatrixCursor cursor;
        CancellationSignal receivedCancellation;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return DocumentsContract.Document.MIME_TYPE_DIR; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new AssertionError("read-only fixture"); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new AssertionError("read-only fixture"); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new AssertionError("read-only fixture"); }
        @Override public Cursor query(Uri uri, String[] projection, String s, String[] a, String order) {
            throw new AssertionError("Query must carry cancellation");
        }
        @Override public Cursor query(Uri uri, String[] projection, String s, String[] a,
                                      String order, CancellationSignal signal) {
            queries++;
            receivedCancellation = signal;
            cursor = new MatrixCursor(projection) {
                @Override public boolean onMove(int oldPosition, int newPosition) {
                    if (newPosition == cancelOnRow && cancel != null) cancel.run();
                    return true;
                }
            };
            Bundle extras = new Bundle();
            extras.putBoolean(DocumentsContract.EXTRA_LOADING, loading);
            cursor.setExtras(extras);
            String[] names = {"b.txt", "folder", ".hidden.txt", "a.txt"};
            for (String name : names) {
                MatrixCursor.RowBuilder row = cursor.newRow();
                for (String column : projection) {
                    switch (column) {
                        case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add("books/" + name); break;
                        case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(name); break;
                        case DocumentsContract.Document.COLUMN_MIME_TYPE:
                            row.add(name.equals("folder") ? DocumentsContract.Document.MIME_TYPE_DIR : "text/plain"); break;
                        case DocumentsContract.Document.COLUMN_SIZE: row.add(1); break;
                        case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row.add(1); break;
                        case DocumentsContract.Document.COLUMN_FLAGS: row.add(0); break;
                        default: row.add(null);
                    }
                }
            }
            return cursor;
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = SafBrowserActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = SafBrowserActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
