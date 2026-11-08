package com.readwide.manager;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.SafDocumentEntry;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SafBrowserOwnershipInstrumentedTest {
    @Test public void recreatedBrowserDoesNotAcquireADuplicatePin() {
        checkModels((first, second, treeA, treeB) -> {
            first.setBrowserTree(treeA);
            first.setBrowserTree(treeA);
            assertEquals(1, pins(treeA));
            first.onCleared();
            assertEquals(0, pins(treeA));
        });
    }

    @Test public void browserSwitchKeepsClipboardSourcePinned() {
        checkModels((first, second, treeA, treeB) -> {
            first.setBrowserTree(treeA);
            first.select(treeA, treeA, entry(treeA), false);
            assertEquals(2, pins(treeA));
            first.setBrowserTree(treeB);
            assertEquals(1, pins(treeA));
            assertEquals(1, pins(treeB));
            first.clearClipboard();
            assertEquals(0, pins(treeA));
            assertEquals(1, pins(treeB));
        });
    }

    @Test public void clearingOneBrowserDoesNotUnpinAnotherBrowser() {
        checkModels((first, second, treeA, treeB) -> {
            first.setBrowserTree(treeA);
            second.setBrowserTree(treeA);
            assertEquals(2, pins(treeA));
            first.onCleared();
            assertEquals(1, pins(treeA));
            second.onCleared();
            assertEquals(0, pins(treeA));
        });
    }

    @Test public void clearedModelCannotReacquireItsBrowsingGrant() {
        checkModels((first, second, treeA, treeB) -> {
            first.setBrowserTree(treeA);
            first.onCleared();
            first.setBrowserTree(treeB);
            assertEquals(0, pins(treeA));
            assertEquals(0, pins(treeB));
        });
    }

    @Test public void archiveResolutionPreservesSuccessfulCopy() {
        File file = new File("copied-archive.zip");
        assertSame(file, SafBrowserActivity.resolveArchiveCopy(() -> file));
    }

    @Test public void archiveResolutionContainsIoAndMemoryFailures() {
        assertNull(SafBrowserActivity.resolveArchiveCopy(() -> { throw new IOException("unavailable"); }));
        assertNull(SafBrowserActivity.resolveArchiveCopy(() -> { throw new OutOfMemoryError("fixture"); }));
        assertNull(SafBrowserActivity.resolveArchiveCopy(() -> { throw new StackOverflowError("fixture"); }));
    }

    @Test public void successfulDirectoryQueryKeepsItsResult() {
        SafBrowserActivity.QueryResult result = SafBrowserActivity.QueryResult.success(
                java.util.Collections.emptyList());
        assertSame(result, SafBrowserActivity.resolveDirectoryQuery(() -> result));
        assertFalse(result.error);
    }

    @Test public void directoryQueryContainsIoAndMemoryFailures() {
        assertTrue(SafBrowserActivity.resolveDirectoryQuery(() -> {
            throw new IOException("unavailable");
        }).error);
        assertTrue(SafBrowserActivity.resolveDirectoryQuery(() -> {
            throw new OutOfMemoryError("fixture");
        }).error);
        assertTrue(SafBrowserActivity.resolveDirectoryQuery(() -> {
            throw new StackOverflowError("fixture");
        }).error);
    }

    @Test public void absentDirectoryResultIsFailureInsteadOfAnEmptySuccess() {
        SafBrowserActivity.QueryResult result = SafBrowserActivity.resolveDirectoryQuery(() -> null);
        assertTrue(result.error);
        assertTrue(result.entries.isEmpty());
    }

    @Test public void staleArchiveCompletionDoesNotClearNewerOpen() {
        checkScreen(screen -> {
            ((AtomicInteger) field(screen, "documentOpenGeneration")).set(3);
            set(screen, "openingDocument", true);
            FutureTask<Void> pending = new FutureTask<>(() -> null);
            set(screen, "pendingDocumentOpen", pending);
            screen.completeArchiveOpen(2, 0, null);
            assertEquals(true, field(screen, "openingDocument"));
            assertSame(pending, field(screen, "pendingDocumentOpen"));
        });
    }

    @Test public void abandonedBrowseCompletionReleasesOpenStateWithoutLaunching() {
        checkScreen(screen -> {
            ((AtomicInteger) field(screen, "documentOpenGeneration")).set(3);
            ((AtomicInteger) field(screen, "queryGeneration")).set(4);
            set(screen, "openingDocument", true);
            set(screen, "pendingDocumentOpen", new FutureTask<Void>(() -> null));
            screen.completeArchiveOpen(3, 2, null);
            assertEquals(false, field(screen, "openingDocument"));
            assertNull(field(screen, "pendingDocumentOpen"));
        });
    }

    @Test public void liveDialogActionRunsOnlyOnce() {
        checkDialog(fixture -> {
            AtomicInteger calls = new AtomicInteger();
            fixture.run(calls::incrementAndGet);
            fixture.run(calls::incrementAndGet);
            assertEquals(1, calls.get());
            assertFalse(fixture.button.isEnabled());
            assertEquals(1, fixture.dialog.dismissCalls);
            assertNull(field(fixture.screen, "deleteDialog"));
        });
    }

    @Test public void dismissedDialogRejectsAQueuedActionBeforeItsDismissCallback() {
        checkDialog(fixture -> {
            fixture.dialog.dismiss();
            AtomicInteger calls = new AtomicInteger();
            fixture.run(calls::incrementAndGet);
            assertEquals(0, calls.get());
            assertTrue(fixture.button.isEnabled());
        });
    }

    @Test public void oldActionCannotDismissOrActForAReplacementDialog() {
        checkDialog(fixture -> {
            DelayedDialog replacement = new DelayedDialog(fixture.context);
            SafBrowserActivity.DialogActionScope replacementScope = fixture.screen.captureDialogScope();
            fixture.screen.ownActionDialog(replacement, replacementScope);
            replacement.show();
            AtomicInteger calls = new AtomicInteger();
            fixture.run(calls::incrementAndGet);
            assertEquals(0, calls.get());
            assertTrue(replacement.isShowing());
            assertEquals(0, replacement.dismissCalls);
            assertSame(replacement, field(fixture.screen, "deleteDialog"));
            fixture.dialog.dispatchDismiss();
            assertSame(replacement, field(fixture.screen, "deleteDialog"));
            fixture.screen.runDialogAction(replacementScope, new View(fixture.context), calls::incrementAndGet);
            assertEquals(1, calls.get());
        });
    }

    @Test public void treeChangeRejectsAnOldEntryAction() {
        checkDialog(fixture -> {
            set(fixture.screen, "treeUri", Uri.parse("content://readwide.dialog.fixture/tree/other"));
            AtomicInteger calls = new AtomicInteger();
            fixture.run(calls::incrementAndGet);
            assertEquals(0, calls.get());
        });
    }

    @Test public void parentChangeRejectsAnOldEntryAction() {
        checkDialog(fixture -> {
            set(fixture.screen, "currentUri", Uri.parse("content://readwide.dialog.fixture/document/other"));
            AtomicInteger calls = new AtomicInteger();
            fixture.run(calls::incrementAndGet);
            assertEquals(0, calls.get());
        });
    }

    @Test public void directoryRefreshRejectsAnOldActionEvenAtTheSamePath() {
        checkDialog(fixture -> {
            ((AtomicInteger) field(fixture.screen, "queryGeneration")).incrementAndGet();
            AtomicInteger calls = new AtomicInteger();
            fixture.run(calls::incrementAndGet);
            assertEquals(0, calls.get());
        });
    }

    @Test public void browserLeavingClearsDialogOwnershipBeforeDismissCompletes() {
        checkDialog(fixture -> {
            fixture.screen.dismissActionDialog();
            assertNull(field(fixture.screen, "deleteDialog"));
            AtomicInteger calls = new AtomicInteger();
            fixture.run(calls::incrementAndGet);
            assertEquals(0, calls.get());
            fixture.screen.dismissActionDialog();
            assertEquals(1, fixture.dialog.dismissCalls);
        });
    }

    @Test public void acceptedActionMayOpenItsSuccessorWithoutOldCallbacksClosingIt() {
        checkDialog(fixture -> {
            DelayedDialog successor = new DelayedDialog(fixture.context);
            fixture.run(() -> {
                fixture.screen.ownActionDialog(successor, fixture.screen.captureDialogScope());
                successor.show();
            });
            fixture.dialog.dispatchDismiss();
            fixture.run(() -> fail("The accepted dialog must not run again"));
            assertSame(successor, field(fixture.screen, "deleteDialog"));
            assertTrue(successor.isShowing());
            assertEquals(0, successor.dismissCalls);
        });
    }

    private interface DialogCheck { void run(DialogFixture fixture); }

    private static void checkDialog(DialogCheck check) {
        checkScreen(screen -> {
            DialogFixture fixture = new DialogFixture(screen);
            try { check.run(fixture); }
            finally { screen.dismissActionDialog(); }
        });
    }

    private static final class DialogFixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SafBrowserActivity screen;
        final DelayedDialog dialog = new DelayedDialog(context);
        final View button = new View(context);
        final SafBrowserActivity.DialogActionScope scope;

        DialogFixture(SafBrowserActivity screen) {
            this.screen = screen;
            set(screen, "treeUri", Uri.parse("content://readwide.dialog.fixture/tree/current"));
            set(screen, "currentUri", Uri.parse("content://readwide.dialog.fixture/document/current"));
            scope = screen.captureDialogScope();
            screen.ownActionDialog(dialog, scope);
            dialog.show();
        }

        void run(Runnable action) { screen.runDialogAction(scope, button, action); }
    }

    private static final class DelayedDialog extends Dialog {
        boolean showing;
        int dismissCalls;
        DialogInterface.OnDismissListener dismissed;

        DelayedDialog(Context context) { super(context); }

        @Override public void show() { showing = true; }
        @Override public boolean isShowing() { return showing; }
        @Override public void dismiss() { showing = false; dismissCalls++; }
        @Override public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
            dismissed = listener;
        }
        void dispatchDismiss() { if (dismissed != null) dismissed.onDismiss(this); }
    }

    private interface ModelCheck {
        void run(SafOperationViewModel first, SafOperationViewModel second, Uri treeA, Uri treeB);
    }

    private static void checkModels(ModelCheck check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String prefsName = "saf-ownership-fixture-" + UUID.randomUUID();
            SharedPreferences fixturePrefs = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            Context context = new ContextWrapper(target) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return fixturePrefs;
                }
            };
            Application application = new FixtureApplication(context);
            SafOperationViewModel first = new SafOperationViewModel(application);
            SafOperationViewModel second = new SafOperationViewModel(application);
            Uri treeA = Uri.parse("content://readwide.ownership.fixture/tree/" + UUID.randomUUID());
            Uri treeB = Uri.parse("content://readwide.ownership.fixture/tree/" + UUID.randomUUID());
            try { check.run(first, second, treeA, treeB); }
            finally {
                first.onCleared();
                second.onCleared();
                target.deleteSharedPreferences(prefsName);
            }
        });
    }

    private static final class FixtureApplication extends Application {
        FixtureApplication(Context context) { attachBaseContext(context); }
    }

    private static SafDocumentEntry entry(Uri tree) {
        return new SafDocumentEntry(tree, "entry", "book.txt", "text/plain", false, 4, 1, 0);
    }

    @SuppressWarnings("unchecked")
    private static int pins(Uri tree) {
        try {
            Field field = SafStorageAccess.class.getDeclaredField("pinnedTrees");
            field.setAccessible(true);
            synchronized (SafStorageAccess.class) {
                return ((Map<Uri, Integer>) field.get(null)).getOrDefault(tree, 0);
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private interface ScreenCheck { void run(SafBrowserActivity screen); }

    private static void checkScreen(ScreenCheck check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SafBrowserActivity screen = new SafBrowserActivity();
            try { check.run(screen); }
            finally {
                ((ExecutorService) field(screen, "queryExecutor")).shutdownNow();
                ((ExecutorService) field(screen, "documentOpenExecutor")).shutdownNow();
            }
        });
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
