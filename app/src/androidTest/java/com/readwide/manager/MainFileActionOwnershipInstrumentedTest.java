package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.system.Os;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.util.FileDeletionPlan;
import com.readwide.manager.util.FileOperationProgress;
import com.readwide.manager.util.FileSystemOps;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainFileActionOwnershipInstrumentedTest {
    @Test public void visibleDeleteStartsOnce() { checkDelete(0); }
    @Test public void dismissedDeleteDoesNotStart() { checkDelete(1); }
    @Test public void destroyedScreenCannotConfirmDelete() { checkDelete(2); }
    @Test public void finishingScreenCannotConfirmDelete() { checkDelete(3); }
    @Test public void frameworkDestroyedScreenCannotConfirmDelete() { checkDelete(4); }

    @Test public void unchangedParentCreatesFolder() { checkCreate(0); }
    @Test public void siblingChangeDoesNotBlockFolderCreation() { checkCreate(1); }
    @Test public void replacementParentCannotReceiveFolder() { checkCreate(2); }
    @Test public void replacementLinkCannotRedirectFolderCreation() { checkCreate(3); }
    @Test public void dismissedCreationDoesNotRun() { checkCreate(4); }
    @Test public void destroyedScreenCannotCreateFolder() { checkCreate(5); }
    @Test public void finishingScreenCannotCreateFolder() { checkCreate(6); }
    @Test public void missingParentCannotReceiveFolder() { checkCreate(7); }

    private void checkDelete(int mode) {
        onMain((screen, root) -> {
            File source = new File(root, "book.txt");
            try (FileOutputStream output = new FileOutputStream(source)) { output.write(1); }
            MainFileActionDialogController controller = new MainFileActionDialogController(screen);
            Method method = MainFileActionDialogController.class.getDeclaredMethod(
                    "showDeleteConfirm", File.class, FileDeletionPlan.class);
            method.setAccessible(true);
            method.invoke(controller, source, FileDeletionPlan.captureSingleFile(source));
            TextView confirm = findDeleteButton(screen.content, screen.getString(R.string.delete));
            assertNotNull(confirm);
            if (mode == 1) screen.dialog.dismiss();
            if (mode == 2) screen.activityDestroyed = true;
            if (mode == 3) screen.finishing = true;
            if (mode == 4) screen.destroyed = true;
            confirm.performClick();
            confirm.performClick();
            assertEquals(mode == 0 ? 1 : 0, screen.startedOperations);
            assertTrue(source.exists());
        });
    }

    private void checkCreate(int mode) {
        onMain((screen, root) -> {
            File parent = new File(root, "books");
            assertTrue(parent.mkdir());
            screen.currentDirectory = parent;
            new MainFileActionDialogController(screen).showNewFolderDialog();
            assertNotNull(screen.create);
            EditText input = findInput(screen.content);
            assertNotNull(input);
            input.setText("new-folder");
            File previous = new File(root, "previous");
            File outside = new File(root, "outside");
            if (mode == 1) assertTrue(new File(parent, "sibling").mkdir());
            if (mode == 2 || mode == 3 || mode == 7) {
                assertTrue(parent.renameTo(previous));
                if (mode == 2) assertTrue(parent.mkdir());
                if (mode == 3) {
                    assertTrue(outside.mkdir());
                    Os.symlink(outside.getAbsolutePath(), parent.getAbsolutePath());
                }
            }
            if (mode == 4) screen.dialog.dismiss();
            if (mode == 5) screen.activityDestroyed = true;
            if (mode == 6) screen.finishing = true;
            screen.create.run();
            screen.create.run();
            boolean accepted = mode == 0 || mode == 1;
            assertEquals(accepted, new File(parent, "new-folder").isDirectory());
            assertFalse(new File(previous, "new-folder").exists());
            assertFalse(new File(outside, "new-folder").exists());
            assertEquals(accepted ? 1 : 0, screen.refreshes);
            assertEquals(0, screen.safOffers);
        });
    }

    private interface Scenario { void run(Screen screen, File root) throws Exception; }

    private void onMain(Scenario scenario) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Screen screen = new Screen(context);
            File root = new File(context.getCacheDir(), "main-file-action-" + UUID.randomUUID());
            assertTrue(root.mkdir());
            try {
                scenario.run(screen, root);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                if (screen.dialog != null) screen.dialog.dismiss();
                screen.fileSearchExecutor.shutdownNow();
                screen.fileOperationExecutor.shutdownNow();
                screen.folderValidationExecutor.shutdownNow();
                screen.fileSearchHandler.removeCallbacksAndMessages(null);
                FileSystemOps.delete(root);
            }
        });
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText input = findInput(group.getChildAt(index));
                if (input != null) return input;
            }
        }
        return null;
    }

    private static TextView findDeleteButton(View view, String label) {
        if (view instanceof TextView && view.hasOnClickListeners()
                && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView result = findDeleteButton(group.getChildAt(index), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static final class Screen extends MainActivity {
        private final Context context;
        Runnable create;
        Dialog dialog;
        View content;
        boolean finishing, destroyed;
        int startedOperations, refreshes, safOffers;

        Screen(Context context) {
            this.context = new ContextThemeWrapper(context, R.style.Theme_TextViewReader);
            attachBaseContext(this.context);
        }

        @Override public Context getApplicationContext() { return context.getApplicationContext(); }
        @Override public Resources getResources() { return context.getResources(); }
        @Override public Resources.Theme getTheme() { return context.getTheme(); }
        @Override public Object getSystemService(String name) { return context.getSystemService(name); }
        @Override public boolean isFinishing() { return finishing; }
        @Override public boolean isDestroyed() { return destroyed; }
        @Override void addFileOpsRow(LinearLayout box, String label, int color, int panel, Runnable action) {
            create = action;
        }
        @Override Dialog createStableCenterDialog(View content, int offset, float dim) {
            return makeDialog(content);
        }
        @Override Dialog createStableBottomDialog(View content, int offset, float dim) {
            return makeDialog(content);
        }
        @Override void overrideDialogWidth(Dialog dialog, int width) { }
        @Override boolean offerSafFileOperationAccess(List<File> files) { safOffers++; return false; }
        @Override void refreshVisibleFileListAfterFileSystemChange() { refreshes++; }
        @Override FileOperationProgress showFileOperationProgress(String title, String detail) {
            startedOperations++;
            return new FileOperationProgress(title, null);
        }
        @Override boolean executeFolderBackgroundTask(Runnable task) { return false; }
        @Override void finishFileOperationProgress(FileOperationProgress progress) { }

        private Dialog makeDialog(View content) {
            this.content = content;
            dialog = new Dialog(context) {
                private boolean showing;
                @Override public void show() { showing = true; }
                @Override public void dismiss() { showing = false; }
                @Override public boolean isShowing() { return showing; }
            };
            return dialog;
        }
    }
}
