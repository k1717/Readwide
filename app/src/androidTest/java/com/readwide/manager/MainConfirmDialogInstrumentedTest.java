package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainConfirmDialogInstrumentedTest {
    @Test public void repeatedConfirmationRunsOnce() { check(0); }
    @Test public void dismissedConfirmationDoesNotRun() { check(1); }
    @Test public void destroyedActivityRejectsStaleConfirmation() { check(2); }
    @Test public void destroyedActivityDoesNotCreateConfirmation() { check(3); }

    private void check(int mode) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(InstrumentationRegistry.getInstrumentation().getTargetContext());
            int[] calls = {0};
            try {
                if (mode == 3) screen.activityDestroyed = true;
                new MainConfirmDialogController(screen).showSimpleConfirmDialog("Title", "Message", "Confirm",
                        () -> calls[0]++);
                if (mode == 3) {
                    assertNull(screen.confirm);
                } else {
                    assertNotNull(screen.confirm);
                    if (mode == 1) screen.dialog.dismiss();
                    if (mode == 2) screen.activityDestroyed = true;
                    screen.confirm.run();
                    screen.confirm.run();
                }
                assertEquals(mode == 0 ? 1 : 0, calls[0]);
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
        Dialog dialog;
        Screen(Context context) {
            this.context = new ContextThemeWrapper(context, R.style.Theme_TextViewReader);
            attachBaseContext(this.context);
        }
        @Override public Context getApplicationContext() { return context.getApplicationContext(); }
        @Override public Resources getResources() { return context.getResources(); }
        @Override public Resources.Theme getTheme() { return context.getTheme(); }
        @Override public Object getSystemService(String name) { return context.getSystemService(name); }
        @Override void addFileOpsRow(LinearLayout box, String label, int color, int panel, Runnable action) {
            confirm = action;
        }
        @Override Dialog createStableCenterDialog(View content, int offset, float dim) {
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
