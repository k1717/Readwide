package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.BookmarkManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Exercises the real confirmation callback with isolated history and no Activity launch. */
@RunWith(AndroidJUnit4.class)
public class MainRecentClearSelectionInstrumentedTest {
    @Rule public TemporaryFolder files = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Test public void confirmingClearRetiresInvisibleSelectionsBeforeReload() throws Exception {
        check(true);
    }

    @Test public void cancellingClearKeepsHistoryAndSelections() throws Exception {
        check(false);
    }

    private void check(boolean confirm) throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File storage = files.newFolder("history");
        File document = files.newFile("selected.txt");
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return storage; }
        };
        Constructor<BookmarkManager> constructor = BookmarkManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        BookmarkManager history = constructor.newInstance(isolated);
        history.saveReadingState(new ReaderState(document.getAbsolutePath()));
        Method show = MainActivity.class.getDeclaredMethod("showClearAllRecentFilesDialog");
        show.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(target);
            screen.bookmarkManager = history;
            screen.homeMode = true;
            screen.fileSelectionMode = true;
            screen.selectedFilePaths.add(document.getAbsolutePath());
            try {
                show.invoke(screen);
                assertNotNull("clear confirmation is reachable in selection mode", screen.confirm);
                assertTrue(screen.fileSelectionMode);
                assertTrue(history.hasRecentFiles());
                if (confirm) {
                    screen.confirm.run();
                    assertFalse(screen.fileSelectionMode);
                    assertTrue(screen.selectedFilePaths.isEmpty());
                    assertTrue(screen.getSelectedFilesSnapshot().isEmpty());
                    assertFalse(history.hasRecentFiles());
                    assertEquals(1, screen.reloads);
                } else {
                    screen.box.getChildAt(screen.box.getChildCount() - 1).performClick();
                    assertTrue(screen.fileSelectionMode);
                    assertTrue(screen.selectedFilePaths.contains(document.getAbsolutePath()));
                    assertTrue(history.hasRecentFiles());
                    assertEquals(0, screen.reloads);
                }
                assertTrue("clearing history must never delete the document", document.isFile());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                screen.fileSearchExecutor.shutdownNow();
                screen.fileOperationExecutor.shutdownNow();
                screen.folderValidationExecutor.shutdownNow();
                screen.fileSearchHandler.removeCallbacksAndMessages(null);
            }
        });
    }

    private static final class Screen extends MainActivity {
        private final Context context;
        Runnable confirm;
        LinearLayout box;
        int reloads;
        Screen(Context context) {
            this.context = new ContextThemeWrapper(context, R.style.Theme_TextViewReader);
            attachBaseContext(this.context);
        }
        @Override public Context getApplicationContext() { return context.getApplicationContext(); }
        @Override public Resources getResources() { return context.getResources(); }
        @Override public Resources.Theme getTheme() { return context.getTheme(); }
        @Override public Object getSystemService(String name) { return context.getSystemService(name); }
        @Override public ActionBar getSupportActionBar() { return null; }
        @Override void installToolbarMenuButton(Toolbar toolbar) {}
        @Override void updateMainOverflowButtonVisibility() {}
        @Override void rebuildDrawerStorageEntries() {}
        @Override void loadRecentFiles() {
            assertFalse(fileSelectionMode);
            assertTrue(selectedFilePaths.isEmpty());
            reloads++;
        }
        @Override int mainFileTypeAlignedDialogYOffsetPx() { return 0; }
        @Override void addFileOpsRow(LinearLayout box, String label, int color, int panel, Runnable action) {
            confirm = action;
        }
        @Override Dialog createStableBottomDialog(View content, int yPx, float dimAmount) {
            box = (LinearLayout) content;
            return new Dialog(context) {
                @Override public void show() {}
                @Override public void dismiss() {}
            };
        }
    }
}
