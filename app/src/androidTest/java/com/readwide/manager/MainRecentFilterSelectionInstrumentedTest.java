package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseArray;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.adapter.FileAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import static org.junit.Assert.*;

/** Real search listeners with synthetic views; no filesystem search or Activity launch. */
@RunWith(AndroidJUnit4.class)
public class MainRecentFilterSelectionInstrumentedTest {
    @Test public void changedQueryRetiresSelectionBeforeTheDebounceRuns() {
        check((screen, search) -> {
            screen.select();
            screen.fileSearchInput.setText("novel");
            screen.assertSelectionCleared();
            assertNotNull(screen.pendingFileSearchRunnable);
            assertEquals(0, screen.searches);
        });
    }

    @Test public void equivalentQueryAndRepeatedSubmitKeepSelection() {
        check((screen, search) -> {
            screen.fileSearchInput.setText("Book");
            search.clearSearchDebounce();
            screen.select();
            screen.fileSearchInput.setText(" book ");
            search.runLiveFileSearchNow();
            search.runLiveFileSearchNow();
            screen.assertSelectionKept();
            assertEquals(2, screen.searches);
        });
    }

    @Test public void clearButtonAndProgrammaticClearRetireSelection() {
        check((screen, search) -> {
            screen.fileSearchInput.setText("book");
            search.clearSearchDebounce();
            screen.select();
            screen.fileSearchClearButton.performClick();
            screen.assertSelectionCleared();
            screen.fileSearchInput.setText("another");
            screen.select();
            // Back/other navigation clears the same EditText rather than the clear button.
            screen.fileSearchInput.setText("");
            screen.assertSelectionCleared();
        });
    }

    @Test public void typeChangesWithAndWithoutQueryRetireSelectionButRepeatTapDoesNot() {
        check((screen, search) -> {
            screen.select();
            screen.filterTxtChip.performClick();
            screen.assertSelectionCleared();
            assertEquals(MainActivity.FILTER_TXT, screen.activeFileFilter);
            int reloads = screen.reloads;
            screen.select();
            screen.filterTxtChip.performClick();
            screen.assertSelectionKept();
            assertEquals(reloads, screen.reloads);
            screen.fileSearchInput.setText("book");
            search.clearSearchDebounce();
            screen.select();
            screen.filterPdfChip.performClick();
            screen.assertSelectionCleared();
            assertEquals(MainActivity.FILTER_PDF, screen.activeFileFilter);
        });
    }

    @Test public void chipReturnFromFilesystemSearchRetiresOutgoingSelections() {
        check((screen, search) -> {
            screen.homeMode = false;
            screen.searchMode = true;
            screen.searchReturnToHome = true;
            screen.activeFileFilter = MainActivity.FILTER_TXT;
            screen.select();
            screen.filterTxtChip.performClick();
            screen.assertSelectionCleared();
            assertTrue(screen.homeMode);
            assertFalse(screen.searchMode);
        });
    }

    @Test public void ordinaryRecentSnapshotRefreshKeepsSelection() {
        checkRecent((screen, search, recent) -> {
            screen.select();
            // Initial adoption establishes ownership, not a criteria change.
            apply(recent, "", MainActivity.FILTER_ALL);
            screen.assertSelectionKept();
            apply(recent, "", MainActivity.FILTER_ALL);
            screen.recentAdapter.refreshReadingProgress();
            screen.assertSelectionKept();
        });
    }

    @Test public void selectionMadeDuringQueryDebounceIsRetiredAtPublication() {
        checkRecent((screen, search, recent) -> {
            apply(recent, "", MainActivity.FILTER_ALL);
            screen.select();
            screen.fileSearchInput.setText("book");
            screen.assertSelectionCleared();
            screen.select(); // Old rows can still be touched during the debounce.
            recent.applyRecentSearch("book");
            screen.assertSelectionCleared();
            screen.select();
            recent.applyRecentSearch(" BOOK ");
            screen.assertSelectionKept();
        });
    }

    @Test public void queryOnOldCorpusDoesNotClaimThePendingTypeSnapshot() {
        checkRecent((screen, search, recent) -> {
            screen.fileSearchInput.setText("book");
            search.clearSearchDebounce();
            apply(recent, "book", MainActivity.FILTER_ALL);
            screen.select();
            screen.filterTxtChip.performClick();
            screen.assertSelectionCleared();
            assertEquals(MainActivity.FILTER_TXT, screen.activeFileFilter);

            screen.select(); // New chip is set, but the old corpus is still visible.
            recent.applyRecentSearch("book");
            screen.assertSelectionKept();
            // Mirror acceptance of the asynchronous TXT corpus, then apply its query.
            setCorpusFilter(recent, MainActivity.FILTER_TXT);
            recent.applyRecentSearch("book");
            screen.assertSelectionCleared();
            screen.select();
            recent.applyRecentSearch("book");
            screen.assertSelectionKept();
        });
    }

    @Test public void backgroundRecentCriteriaChangesKeepFilesystemSelection() {
        checkRecent((screen, search, recent) -> {
            apply(recent, "", MainActivity.FILTER_ALL);
            screen.homeMode = false;
            screen.searchMode = true;
            screen.select();
            apply(recent, "book", MainActivity.FILTER_TXT);
            screen.assertSelectionKept();
        });
    }

    @Test public void queryOwnershipUsesTheSameRootCaseMappingAsSearch() {
        checkRecent((screen, search, recent) -> {
            screen.fileSearchInput.setText("\u0130");
            search.clearSearchDebounce();
            apply(recent, "\u0130", MainActivity.FILTER_ALL);
            screen.select();
            // equalsIgnoreCase treats these as equal, but ROOT-lowercase
            // filename matching produces different search needles.
            screen.fileSearchInput.setText("i");
            screen.assertSelectionCleared();
            screen.select();
            recent.applyRecentSearch("i");
            screen.assertSelectionCleared();
        });
    }

    private interface RecentCheck {
        void run(Screen screen, MainSearchFilterController search, MainRecentFilesController recent);
    }
    private void checkRecent(RecentCheck check) {
        check((screen, search) -> {
            MainRecentFilesController recent = new MainRecentFilesController(screen);
            screen.recentAdapter = new FileAdapter();
            try { check.run(screen, search, recent); }
            finally {
                recent.close();
                screen.recentAdapter.release();
            }
        });
    }

    private static void apply(MainRecentFilesController recent, String query, int filter) {
        try {
            Method apply = MainRecentFilesController.class.getDeclaredMethod(
                    "applyRecentFiles", java.util.List.class, java.util.List.class,
                    String.class, int.class);
            apply.setAccessible(true);
            apply.invoke(recent, Collections.emptyList(), Collections.emptyList(), query, filter);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setCorpusFilter(MainRecentFilesController recent, int filter) {
        try {
            Field field = MainRecentFilesController.class.getDeclaredField("fullRecentFilter");
            field.setAccessible(true);
            field.setInt(recent, filter);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private interface Check { void run(Screen screen, MainSearchFilterController search); }
    private void check(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen();
            MainSearchFilterController search = new MainSearchFilterController(screen);
            try {
                screen.homeMode = true;
                search.setupFileSearch();
                check.run(screen, search);
            } finally {
                screen.activityDestroyed = true;
                search.clearSearchDebounce();
                screen.fileSearchHandler.removeCallbacksAndMessages(null);
                screen.fileSearchExecutor.shutdownNow();
                screen.fileOperationExecutor.shutdownNow();
                screen.folderValidationExecutor.shutdownNow();
            }
        });
    }

    private static final class Screen extends MainActivity {
        private final SparseArray<View> views = new SparseArray<>();
        int searches, reloads;
        Screen() {
            Context context = getApplicationContext();
            views.put(R.id.file_search_input, new EditText(context));
            for (int id : new int[]{R.id.file_search_clear_button, R.id.filter_all,
                    R.id.filter_txt, R.id.filter_pdf}) views.put(id, new TextView(context));
        }
        void select() { fileSelectionMode = true; selectedFilePaths.add("/selected.txt"); }
        void assertSelectionKept() {
            assertTrue(fileSelectionMode);
            assertTrue(selectedFilePaths.contains("/selected.txt"));
        }
        void assertSelectionCleared() {
            assertFalse(fileSelectionMode);
            assertTrue(selectedFilePaths.isEmpty());
        }
        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
        @SuppressWarnings("unchecked")
        @Override public <T extends View> T findViewById(int id) { return (T) views.get(id); }
        @Override public ActionBar getSupportActionBar() { return null; }
        @Override public void invalidateOptionsMenu() {}
        @Override void installToolbarMenuButton(Toolbar toolbar) {}
        @Override void updateMainOverflowButtonVisibility() {}
        @Override void setPathBarVisible(boolean visible) {}
        @Override void scrollListToTop(RecyclerView list) {}
        @Override void loadRecentFiles() { assertSelectionCleared(); reloads++; }
        @Override void applyRecentSearch(String query) { searches++; }
        @Override void clearRecentSearch() { searches++; }
    }
}
