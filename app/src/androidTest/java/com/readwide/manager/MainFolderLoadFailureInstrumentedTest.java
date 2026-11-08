package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.adapter.FileAdapter;
import com.readwide.manager.model.FileListItem;
import com.readwide.manager.util.PrefsManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainFolderLoadFailureInstrumentedTest {
    @Test public void initialRuntimeFailureCompletes() { checkFailure(false, 1); }
    @Test public void initialMemoryFailureCompletes() { checkFailure(false, 2); }
    @Test public void initialStackFailureCompletes() { checkFailure(false, 3); }
    @Test public void refreshRuntimeFailureCompletes() { checkFailure(true, 1); }
    @Test public void refreshMemoryFailureCompletes() { checkFailure(true, 2); }
    @Test public void refreshStackFailureCompletes() { checkFailure(true, 3); }
    @Test public void permissionFailureStillOffersFolderAccess() { checkFailure(false, 4); }
    @Test public void nullListingStillOffersFolderAccess() { checkFailure(true, 5); }

    @Test public void rejectedSubmissionCompletes() { checkSubmission(false); }
    @Test public void acceptedThenRejectedTaskCannotRunLater() { checkSubmission(true); }

    @Test public void failureAfterProgressClearsPartialRows() {
        check(f -> {
            f.directory.children = new File[40];
            for (int i = 0; i < 39; i++) f.directory.children[i] = new File(f.directory, i + ".txt");
            f.directory.children[39] = new File(f.directory, "broken.txt") {
                @Override public boolean isDirectory() { throw new IllegalStateException("fixture"); }
            };
            f.start(false);
            f.executor.runNext();
        }, f -> f.assertFailed(false));
    }

    @Test public void signatureFailureCompletes() {
        check(f -> {
            f.screen.failSignature = true;
            f.start(false);
            f.executor.runNext();
        }, f -> f.assertFailed(false));
    }

    @Test public void retryAfterFailureCanCompleteAtSamePath() {
        check(f -> {
            f.directory.failure = 1;
            f.start(false);
            f.executor.runNext();
        }, f -> {
            f.assertFailed(false);
            f.directory.failure = 0;
            f.start(true);
            f.executor.runNext();
        }, f -> {
            assertEquals(1, f.screen.completed);
            assertEquals(1, f.screen.failed);
            assertEquals(View.GONE, f.screen.fileSearchProgress.getVisibility());
        });
    }

    @Test public void oldFailureCannotClearNewLoadAtSamePath() { checkStale(0); }
    @Test public void oldFailureCannotClearAnotherFolder() { checkStale(1); }
    @Test public void cancelledFailureCannotChangeCurrentUi() { checkStale(2); }
    @Test public void destroyedActivityIgnoresFailure() { checkStale(3); }

    @Test public void successfulLoadKeepsFinalSortAndCompletion() {
        check(f -> {
            f.directory.children = new File[] {
                    new File(f.directory, "b.txt"), new File(f.directory, "a.txt") };
            f.start(false);
            f.executor.runNext();
        }, f -> {
            assertEquals(1, f.screen.completed);
            assertEquals(0, f.screen.failed);
            assertEquals("a.txt", f.screen.fileAdapter.getItemsSnapshot().get(0).getName());
            assertEquals("b.txt", f.screen.fileAdapter.getItemsSnapshot().get(1).getName());
            assertEquals(View.GONE, f.screen.fileSearchProgress.getVisibility());
        });
    }

    @Test public void validationRuntimeFailureRefreshes() { checkValidationFailure(1); }
    @Test public void validationMemoryFailureRefreshes() { checkValidationFailure(2); }
    @Test public void validationStackFailureRefreshes() { checkValidationFailure(3); }

    @Test public void matchingValidationDoesNotRefresh() {
        check(f -> {
            f.prepareValidation(f.browse.captureFolderSignature(f.directory));
            f.screen.validation.run();
        }, f -> assertEquals(0, f.screen.refreshes));
    }

    @Test public void oldValidationCannotRestartNewLoadAtSamePath() {
        check(f -> {
            f.prepareValidation("old");
            f.screen.validation.run();
            f.browse.markLoadStarted(f.directory);
        }, f -> assertEquals(0, f.screen.refreshes));
    }

    @Test public void queuedValidationSkipsIoAfterNewLoadStarts() {
        check(f -> {
            f.prepareValidation("old");
            f.browse.markLoadStarted(f.directory);
            f.screen.validation.run();
        }, f -> {
            assertEquals(0, f.directory.listCalls);
            assertEquals(0, f.screen.refreshes);
        });
    }

    private void checkFailure(boolean refresh, int failure) {
        check(f -> {
            f.directory.failure = failure;
            f.start(refresh);
            f.executor.runNext();
        }, f -> f.assertFailed(failure >= 4));
    }

    private void checkSubmission(boolean accepted) {
        check(f -> {
            f.executor.reject = accepted ? 2 : 1;
            f.start(false);
            if (accepted) f.executor.runNext();
        }, f -> {
            f.assertFailed(false);
            assertEquals(0, f.directory.listCalls);
        });
    }

    private void checkStale(int mode) {
        check(f -> {
            f.directory.failure = 1;
            f.start(false);
            f.executor.runNext();
            if (mode == 0) {
                f.directory.failure = 0;
                f.start(false);
            } else if (mode == 1) {
                f.screen.currentDirectory = new File("/different-fixture");
            } else if (mode == 2) {
                f.loader.cancelPendingFolderLoad();
            } else {
                f.screen.activityDestroyed = true;
            }
        }, f -> {
            assertEquals(0, f.screen.failed);
            assertEquals(View.VISIBLE, f.screen.fileSearchProgress.getVisibility());
            assertEquals(0, f.screen.offers);
        });
    }

    private void checkValidationFailure(int kind) {
        check(f -> {
            f.prepareValidation("previous");
            f.directory.failure = kind;
            f.screen.validation.run();
        }, f -> assertEquals(1, f.screen.refreshes));
    }

    private interface Step { void run(Fixture fixture); }

    private void check(Step... steps) {
        Fixture[] slot = new Fixture[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> slot[0] = new Fixture());
        try {
            for (Step step : steps) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> step.run(slot[0]));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(slot[0]::close);
        }
    }

    private static final class Fixture {
        final Screen screen = new Screen(InstrumentationRegistry.getInstrumentation().getTargetContext());
        final MainFolderLoadController loader = new MainFolderLoadController(screen);
        final MainBrowseStateController browse = new MainBrowseStateController(screen);
        final QueuedExecutor executor = new QueuedExecutor();
        final Directory directory = new Directory();

        Fixture() {
            try {
                Field field = MainFolderLoadController.class.getDeclaredField("executor");
                field.setAccessible(true);
                ((ThreadPoolExecutor) field.get(loader)).shutdownNow();
                field.set(loader, executor);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }

        void start(boolean refresh) {
            if (refresh) loader.refreshCurrentDirectoryWithoutClearing(directory);
            else loader.loadDirectory(directory);
        }

        void assertFailed(boolean access) {
            assertEquals(1, screen.failed);
            assertEquals(0, screen.completed);
            assertEquals(access ? 1 : 0, screen.offers);
            assertEquals(0, screen.fileAdapter.getItemCount());
            assertEquals(View.GONE, screen.fileSearchProgress.getVisibility());
            assertEquals(View.VISIBLE, screen.emptyText.getVisibility());
            assertEquals(screen.getString(R.string.containing_folder_unavailable), screen.emptyText.getText().toString());
        }

        void prepareValidation(String signature) {
            screen.currentDirectory = directory;
            browse.markLoadComplete(directory, Collections.emptyList(), PrefsManager.SORT_NAME_ASC, signature);
            browse.reloadVisibleFolderIfChanged();
            assertNotNull(screen.validation);
        }

        void close() {
            loader.shutdownNow();
            screen.fileSearchExecutor.shutdownNow();
            screen.fileOperationExecutor.shutdownNow();
            screen.folderValidationExecutor.shutdownNow();
            screen.fileSearchHandler.removeCallbacksAndMessages(null);
        }
    }

    private static final class Directory extends File {
        int failure, listCalls;
        File[] children = new File[0];
        Directory() { super("/readwide-folder-load-fixture"); }
        @Override public boolean isDirectory() { return true; }
        @Override public boolean canRead() { return true; }
        @Override public File[] listFiles() {
            listCalls++;
            if (failure == 1) throw new IllegalStateException("fixture");
            if (failure == 2) throw new OutOfMemoryError("fixture");
            if (failure == 3) throw new StackOverflowError("fixture");
            if (failure == 4) throw new SecurityException("fixture");
            return failure == 5 ? null : children;
        }
    }

    private static final class QueuedExecutor extends ThreadPoolExecutor {
        final List<Runnable> tasks = new ArrayList<>();
        int reject;
        QueuedExecutor() { super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable task) {
            if (reject == 1) throw new RejectedExecutionException("fixture");
            tasks.add(task);
            if (reject == 2) throw new OutOfMemoryError("fixture");
        }
        void runNext() { tasks.remove(0).run(); }
    }

    private static final class Screen extends MainActivity {
        private final Context context;
        int failed, completed, offers, refreshes;
        boolean failSignature;
        Runnable validation;
        Screen(Context context) {
            this.context = new ContextThemeWrapper(context, R.style.Theme_TextViewReader);
            attachBaseContext(this.context);
            fileAdapter = new FileAdapter();
            fileSearchProgress = new ProgressBar(this.context);
            emptyText = new TextView(this.context);
            homeMode = false;
        }
        @Override public Context getApplicationContext() { return context.getApplicationContext(); }
        @Override public Resources getResources() { return context.getResources(); }
        @Override public Resources.Theme getTheme() { return context.getTheme(); }
        @Override public Object getSystemService(String name) { return context.getSystemService(name); }
        @Override public ActionBar getSupportActionBar() { return null; }
        @Override void exitFileSelectionMode(boolean refresh) { }
        @Override void syncVisibleFolderChangeObserver() { }
        @Override void updateParentFolderButtonState() { }
        @Override void scrollListToTop(RecyclerView list) { }
        @Override void rebuildDrawerStorageEntries() { }
        @Override void markCurrentBrowseFolderLoadStarted(File directory) { }
        @Override void markCurrentBrowseFolderLoadFailed(File directory) { failed++; }
        @Override void markCurrentBrowseFolderLoadComplete(File directory, List<FileListItem> items,
                                                         int mode, String signature) { completed++; }
        @Override String captureBrowseFolderSignatureFromItems(File directory, List<FileListItem> items) {
            if (failSignature) throw new OutOfMemoryError("fixture");
            return "fixture";
        }
        @Override String storageAccessDiagnosticSummary(File directory) { return "fixture"; }
        @Override void offerSafFolderFallback() { offers++; }
        @Override void submitFolderValidationTask(Runnable task) { validation = task; }
        @Override void refreshCurrentDirectoryWithoutClearing(File directory) { refreshes++; }
    }
}
