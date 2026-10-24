package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Injected failures only; no resource pressure or real Activity/window is required. */
@RunWith(AndroidJUnit4.class)
public class EpubNavigationFailureInstrumentedTest {
    @Test public void rejectedContentsUsesSpineFallbackAndReopeningRetries() {
        check(f -> {
            f.worker.failure = new RejectedExecutionException("injected");
            f.controller.showContents();
        }, Fixture::assertFallbackContents, f -> {
            f.worker.failure = null;
            f.controller.showContents();
            assertEquals(f.activity.getString(R.string.loading), f.status().getText().toString());
            assertEquals(1, f.worker.queued.size());
            f.worker.runNext();
        }, Fixture::assertFallbackContents);
    }

    @Test public void failedContentsWorkerSettlesLoadingAndReleasesItsLease() {
        check(f -> {
            // Fail outside the optional XML parser's own fallback guard.
            f.activity.epubPackageResources = null;
            f.controller.showContents();
            f.worker.runNext();
        }, f -> {
            f.assertFallbackContents();
            f.session.close();
            try { f.zip.getEntry("OPS/chapter.xhtml"); fail("Worker retained a lease"); }
            catch (IllegalStateException expected) { }
        });
    }

    @Test public void oldContentsFailureCannotReplaceANewerDialog() {
        check(f -> {
            f.activity.epubPackageResources = null;
            f.controller.showContents();
            f.controller.close();
            f.activity.epubPackageResources = new DocumentArchiveUtils.EpubPackageResources();
            f.controller.showContents();
            f.worker.runNext();
        }, f -> {
            assertEquals(f.activity.getString(R.string.loading), f.status().getText().toString());
            assertEquals(0, f.list().getAdapter().getItemCount());
        });
    }

    @Test public void rejectedOrdinaryLinkFallsBackOnceAndLaterRequestsStillWork() {
        check(f -> {
            f.worker.failure = new RejectedExecutionException("injected");
            f.followOrdinaryLink();
        }, f -> assertEquals(1, f.navigations), f -> {
            f.worker.failure = null;
            f.followOrdinaryLink();
            f.worker.runNext();
        }, f -> assertEquals(2, f.navigations));
    }

    @Test public void submissionResourceFailuresUseTheOwnedOrdinaryFallback() {
        for (Error failure : resourceFailures()) {
            check(f -> {
                f.worker.failure = failure;
                f.followOrdinaryLink();
            }, f -> assertEquals(1, f.navigations));
        }
    }

    @Test public void workerResourceFailuresSettleWithoutRetryingTheWorker() {
        for (Error failure : resourceFailures()) {
            check(f -> {
                submit(f, () -> { throw failure; }, () -> f.navigations++);
                f.worker.runNext();
            }, f -> {
                assertEquals(1, f.navigations);
                assertTrue(f.worker.queued.isEmpty());
            });
        }
    }

    @Test public void stalePaneAndRequestOwnersCannotRunFailureFallback() {
        List<Consumer<Fixture>> changes = new ArrayList<>();
        changes.add(f -> f.activity.primaryDocumentPageLoadGeneration++);
        changes.add(f -> f.activity.rightDocumentPageLoadGeneration++);
        changes.add(f -> f.activity.documentAnchorPageGeneration++);
        changes.add(f -> f.activity.documentInteractionGeneration++);
        changes.add(f -> f.activity.loadGeneration++);
        changes.add(f -> f.activity.currentPage = 1);
        changes.add(f -> f.left.scrollTo(0, 20));
        changes.add(f -> f.activity.webView = f.right);
        changes.add(f -> f.activity.session = null);
        changes.add(f -> f.controller.close());
        changes.add(f -> f.activity.activityDestroyed = true);
        changes.add(f -> f.activity.finishing = true);
        for (Consumer<Fixture> change : changes) {
            check(f -> {
                // A malformed injected target fails in the queued worker;
                // the supplied ordinary action must still retain its owner.
                f.controller.followLink(f.left, null, () -> f.navigations++);
                change.accept(f);
                f.worker.runNext();
            }, f -> assertEquals(0, f.navigations));
        }
    }

    @Test public void retiredSessionCancelsWithoutOrdinaryFallback() {
        check(f -> {
            f.followOrdinaryLink();
            f.session.close();
            f.worker.runNext();
        }, f -> assertEquals(0, f.navigations));
    }

    @Test public void unrelatedWorkerErrorIsNotConvertedToNavigation() {
        check(f -> {
            AssertionError original = new AssertionError("injected fatal error");
            submit(f, () -> { throw original; }, () -> f.navigations++);
            try { f.worker.runNext(); fail("Unrelated Error must propagate"); }
            catch (AssertionError caught) { assertSame(original, caught); }
        }, f -> assertEquals(0, f.navigations));
    }

    @Test public void continuingResourceFailureInRecoveryDoesNotRetryOrEscape() {
        check(f -> {
            submit(f, () -> { throw new OutOfMemoryError("injected work"); },
                    () -> { throw new StackOverflowError("injected recovery"); });
            f.worker.runNext();
        }, f -> {
            assertTrue(f.worker.queued.isEmpty());
            f.followOrdinaryLink();
            f.worker.runNext();
        }, f -> assertEquals(1, f.navigations));
    }

    @Test public void semanticFootnoteStillLeavesBothReadingPanesUntouched() {
        check(f -> {
            f.controller.followLink(f.left,
                    new EpubNavigationParser.Target("OPS/chapter.xhtml", "note"),
                    () -> f.navigations++);
            f.worker.runNext();
        }, f -> {
            assertNotNull(f.activity.box);
            assertEquals(0, f.navigations);
            assertEquals(0, f.activity.currentPage);
            assertEquals(0, f.left.getScrollY());
            assertEquals(0, f.right.getScrollY());
            f.controller.close();
            assertEquals(0, f.navigations);
        });
    }

    private static Error[] resourceFailures() {
        return new Error[] {new OutOfMemoryError("injected"), new StackOverflowError("injected")};
    }

    private static void submit(Fixture f, Runnable work, Runnable failure) {
        try {
            Method method = EpubNavigationController.class.getDeclaredMethod(
                    "submitNavigationTask", Runnable.class, Runnable.class);
            method.setAccessible(true);
            method.invoke(f.controller, work, failure);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @SafeVarargs private static void check(Consumer<Fixture>... stages) {
        Fixture[] holder = {null};
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try {
            instrumentation.runOnMainSync(() -> holder[0] = new Fixture());
            for (Consumer<Fixture> stage : stages) {
                // A new main-loop message also allows posted completions to run.
                instrumentation.runOnMainSync(() -> stage.accept(holder[0]));
            }
        } finally {
            instrumentation.runOnMainSync(() -> { if (holder[0] != null) holder[0].close(); });
        }
    }

    private static final class Fixture {
        final Screen activity;
        final EpubNavigationController controller;
        final QueuedExecutor worker = new QueuedExecutor();
        final WebView left, right;
        final File file;
        final ZipFile zip;
        final DocumentZipSession session;
        int navigations;

        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            activity = new Screen(context);
            left = new WebView(context);
            right = new WebView(context);
            try {
                file = File.createTempFile("epub-nav-failure-", ".zip", context.getCacheDir());
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                    out.putNextEntry(new ZipEntry("OPS/chapter.xhtml"));
                    out.write(("<html><body><aside id='note' role='doc-footnote'>Note</aside>"
                            + "</body></html>").getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
                zip = new ZipFile(file);
                session = new DocumentZipSession(zip);
                activity.executor.shutdownNow();
                Field executor = DocumentPageActivity.class.getDeclaredField("executor");
                executor.setAccessible(true);
                executor.set(activity, worker);
            } catch (Exception failure) { throw new AssertionError(failure); }
            activity.session = session;
            activity.webView = left;
            activity.rightWebView = right;
            activity.docType = "EPUB";
            activity.loadGeneration = 3;
            activity.primaryDocumentPageLoadGeneration = 7;
            activity.rightDocumentPageLoadGeneration = 9;
            activity.documentAnchorPageGeneration = 11;
            activity.documentInteractionGeneration = 5;
            activity.pages.add(new DocumentPageActivity.Page("Chapter", "", "OPS/chapter.xhtml"));
            activity.pages.add(new DocumentPageActivity.Page("Second", "", "OPS/second.xhtml"));
            controller = new EpubNavigationController(activity);
        }

        void followOrdinaryLink() {
            controller.followLink(left, new EpubNavigationParser.Target("OPS/chapter.xhtml", ""),
                    () -> navigations++);
        }
        TextView status() { return (TextView) activity.box.getChildAt(2); }
        RecyclerView list() { return (RecyclerView) activity.box.getChildAt(3); }
        void assertFallbackContents() {
            assertEquals(activity.getString(R.string.epub_contents_fallback), status().getText().toString());
            assertEquals(2, list().getAdapter().getItemCount());
        }
        void close() {
            activity.activityDestroyed = true;
            controller.close();
            session.close();
            worker.shutdownNow();
            left.destroy();
            right.destroy();
            assertTrue(file.delete());
        }
    }

    private static final class Screen extends DocumentPageActivity {
        final Context context;
        DocumentZipSession session;
        LinearLayout box;
        boolean finishing;
        Screen(Context base) {
            context = new ContextThemeWrapper(base, R.style.Theme_TextViewReader);
            attachBaseContext(context);
        }
        @Override public Context getApplicationContext() { return context.getApplicationContext(); }
        @Override public Resources getResources() { return context.getResources(); }
        @Override public Resources.Theme getTheme() { return context.getTheme(); }
        @Override public Object getSystemService(String name) { return context.getSystemService(name); }
        @Override public boolean isFinishing() { return finishing; }
        @Override DocumentZipSession epubNavigationSession() { return session; }
        @Override int documentRightSpreadPageIndex() { return 1; }
        @Override Dialog createStablePositionedDialog(View content, int y, boolean resize, boolean legacy) {
            box = (LinearLayout) content;
            return new Dialog(context) {
                DialogInterface.OnDismissListener listener;
                @Override public void show() { }
                @Override public void setOnDismissListener(DialogInterface.OnDismissListener value) { listener = value; }
                @Override public void dismiss() { if (listener != null) listener.onDismiss(this); }
            };
        }
    }

    private static final class QueuedExecutor extends ThreadPoolExecutor {
        final List<Runnable> queued = new ArrayList<>();
        Throwable failure;
        QueuedExecutor() { super(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable command) {
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            queued.add(command);
        }
        void runNext() { queued.remove(0).run(); }
    }
}
