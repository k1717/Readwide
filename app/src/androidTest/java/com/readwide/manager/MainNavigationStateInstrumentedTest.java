package com.readwide.manager;

import android.content.Context;
import android.os.Bundle;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

/** Snapshot/dispatch checks only; does not launch activities or touch live preferences. */
@RunWith(AndroidJUnit4.class)
public class MainNavigationStateInstrumentedTest {
    private static final class Screen extends MainActivity {
        int searches;
        Screen(Context context) { fileSearchInput = new EditText(context); }
        @Override void showHomeMode() {
            navigationState.navigating(); homeMode = true; searchMode = false;
        }
        @Override void showBrowseMode(File directory) {
            navigationState.navigating(); homeMode = false; searchMode = false;
            currentDirectory = directory;
        }
        @Override void clearSearchDebounce() {}
        @Override void updateFileSearchClearButtonVisibility() {}
        @Override void runLiveFileSearchNow() { searches++; }
        void closeWorkers() {
            fileSearchExecutor.shutdownNow(); fileOperationExecutor.shutdownNow();
            folderValidationExecutor.shutdownNow();
        }
    }

    private interface Check { void run(Screen before, Screen after, File folder); }
    private void check(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Screen before = new Screen(context);
            Screen after = new Screen(context);
            try { check.run(before, after, context.getCacheDir()); }
            finally { before.closeWorkers(); after.closeWorkers(); }
        });
    }

    @Test public void restoresFolderWithoutSerializingItsContents() {
        check((before, after, folder) -> {
            before.showBrowseMode(folder);
            Bundle state = new Bundle(); before.navigationState.save(state);
            after.navigationState.read(state);
            assertTrue(after.navigationState.restore());
            assertFalse(after.homeMode);
            assertEquals(folder, after.currentDirectory);
            assertEquals(0, after.searches);
            assertFalse(after.navigationState.hasPendingRestore());
            assertTrue(state.getBundle("main_navigation").size() < 15);
        });
    }

    @Test public void restoresSearchAndBackDestination() {
        check((before, after, folder) -> {
            before.showBrowseMode(folder);
            before.searchMode = true;
            before.activeFileFilter = MainActivity.FILTER_EPUB;
            before.fileSearchInput.setText("book");
            before.searchReturnToHome = false;
            before.searchReturnDirectory = folder;
            before.fileTypeFilterActivatedDirectory = folder;
            Bundle state = new Bundle(); before.navigationState.save(state);
            after.navigationState.read(state); after.navigationState.restore();
            assertEquals(1, after.searches);
            assertEquals("book", after.fileSearchInput.getText().toString());
            assertEquals(MainActivity.FILTER_EPUB, after.activeFileFilter);
            assertFalse(after.searchReturnToHome);
            assertEquals(folder, after.searchReturnDirectory);
            assertEquals(folder, after.fileTypeFilterActivatedDirectory);
        });
    }

    @Test public void restoresHomeQueryWithoutStartingFilesystemSearch() {
        check((before, after, folder) -> {
            before.showHomeMode(); before.fileSearchInput.setText("recent title");
            Bundle state = new Bundle(); before.navigationState.save(state);
            after.navigationState.read(state); after.navigationState.restore();
            assertTrue(after.homeMode);
            assertEquals("recent title", after.fileSearchInput.getText().toString());
            assertEquals(0, after.searches);
        });
    }

    @Test public void deferredSnapshotSurvivesAnotherSaveBeforeUnlock() {
        check((before, after, folder) -> {
            before.showBrowseMode(folder);
            Bundle state = new Bundle(); before.navigationState.save(state);
            after.navigationState.read(state);
            Bundle deferred = new Bundle(); after.navigationState.save(deferred);
            assertEquals(folder.getAbsolutePath(), deferred.getBundle("main_navigation").getString("directory"));
            assertTrue(after.navigationState.hasPendingRestore());
        });
    }

    @Test public void unavailableDirectoryFallsBackToHome() {
        check((before, after, folder) -> {
            before.showBrowseMode(new File(folder, "missing-" + java.util.UUID.randomUUID()));
            Bundle state = new Bundle(); before.navigationState.save(state);
            after.navigationState.read(state); after.navigationState.restore();
            assertTrue(after.homeMode);
            assertEquals(0, after.searches);
        });
    }
}
