package com.readwide.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsFolderAccessInstrumentedTest {
    private Context context() {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_TextViewReader);
    }

    @Test public void folderAccessFollowsPinWithinSecuritySection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            ViewGroup container = screen.findViewById(R.id.settings_content_container);
            View folder = screen.findViewById(R.id.settings_folder_access);
            assertTrue(folder instanceof MaterialButton);
            assertEquals(screen.getString(R.string.saf_folder_access),
                    ((MaterialButton) folder).getText().toString());
            assertEquals(container.indexOfChild(screen.findViewById(R.id.btn_change_pin)) + 1,
                    container.indexOfChild(folder));
            assertTrue(container.indexOfChild(folder)
                    < container.indexOfChild(screen.findViewById(R.id.section_header_backup)));

            new SettingsCollapsibleSectionController(screen).setup(null);
            assertEquals(View.GONE, folder.getVisibility());
            screen.findViewById(R.id.section_header_security).performClick();
            assertEquals(View.VISIBLE, folder.getVisibility());
            screen.findViewById(R.id.section_header_security).performClick();
            assertEquals(View.GONE, folder.getVisibility());
        });
    }

    @Test public void expandedSecurityRestoresFolderAccessAfterRecreation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen old = new Screen(context());
            SettingsCollapsibleSectionController sections =
                    new SettingsCollapsibleSectionController(old);
            sections.setup(null);
            old.findViewById(R.id.section_header_security).performClick();
            Bundle saved = new Bundle();
            sections.saveState(saved);

            Screen restored = new Screen(context());
            new SettingsCollapsibleSectionController(restored).setup(saved);
            assertEquals(View.VISIBLE,
                    restored.findViewById(R.id.settings_folder_access).getVisibility());
        });
    }

    @Test public void appearanceSettingsDoNotShowFolderAccess() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            screen.appearanceSettingsMode = true;
            new SettingsCollapsibleSectionController(screen).setup(null);
            assertEquals(View.GONE,
                    screen.findViewById(R.id.settings_folder_access).getVisibility());
        });
    }

    @Test public void readingAndArchiveOptionsAreNotPartOfButtonOrder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            new SettingsCollapsibleSectionController(screen).setup(null);
            screen.findViewById(R.id.section_header_button_order).performClick();
            assertEquals(View.GONE, screen.findViewById(R.id.btn_tap_zones).getVisibility());
            assertEquals(View.GONE, screen.findViewById(R.id.spinner_archive_open_mode).getVisibility());
            screen.findViewById(R.id.section_header_reading).performClick();
            assertEquals(View.VISIBLE, screen.findViewById(R.id.btn_tap_zones).getVisibility());
            assertEquals(View.VISIBLE, screen.findViewById(R.id.switch_auto_save).getVisibility());
            assertEquals(View.VISIBLE, screen.findViewById(R.id.btn_txt_reading_options).getVisibility());
            assertEquals(View.GONE, screen.findViewById(R.id.spinner_archive_open_mode).getVisibility());
            screen.findViewById(R.id.section_header_behavior).performClick();
            assertEquals(View.VISIBLE, screen.findViewById(R.id.spinner_archive_open_mode).getVisibility());
        });
    }

    @Test public void appearanceIncludesDisplayAndTextButExcludesTapOptions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            screen.appearanceSettingsMode = true;
            new SettingsCollapsibleSectionController(screen).setup(null);
            screen.findViewById(R.id.section_header_text_style).performClick();
            screen.findViewById(R.id.section_header_reader_display).performClick();
            assertEquals(View.VISIBLE, screen.findViewById(R.id.font_size_seekbar).getVisibility());
            assertEquals(View.VISIBLE, screen.findViewById(R.id.switch_status_bar).getVisibility());
            assertEquals(View.GONE, screen.findViewById(R.id.btn_tap_zones).getVisibility());
            assertEquals(View.GONE, screen.findViewById(R.id.section_header_reading).getVisibility());
        });
    }

    @Test public void tapZonesAndTxtOptionsHaveCompactSpacing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)
                    screen.findViewById(R.id.btn_tap_zones).getLayoutParams();
            assertEquals(Math.round(8 * screen.getResources().getDisplayMetrics().density), params.bottomMargin);
        });
    }

    @Test public void cancelledOrClosedScreenResultClearsPendingWithoutLaunching() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            screen.closed = true;
            SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
            controller.bind(screen.findViewById(R.id.settings_folder_access));
            controller.open(false);
            controller.open(true);
            assertFalse(pending(controller));
            setPending(controller);
            deliver(controller, null);
            assertFalse(pending(controller));
            setPending(controller);
            deliver(controller, Uri.parse("content://example.documents/tree/storage"));
            assertFalse(pending(controller));
            assertEquals(0, screen.browserLaunches);
        });
    }

    @Test public void emptyRegistryShowsAddAndCloseWithoutLaunchingPicker() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            try {
                SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
                ViewGroup panel = controller.createRegistryPanel(screen, new Dialog(screen));
                assertEquals(4, panel.getChildCount());
                assertEquals(screen.getString(R.string.saf_add_folder),
                        ((MaterialButton) panel.getChildAt(2)).getText().toString());
                assertEquals(screen.getString(R.string.close),
                        ((MaterialButton) panel.getChildAt(3)).getText().toString());
                assertFalse(pending(controller));
            } finally { screen.cleanPreferences(); }
        });
    }

    @Test public void legacyRegisteredFolderIsVisibleWithVolumeIdentifier() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            try {
                screen.getSharedPreferences("saf_storage_access", Context.MODE_PRIVATE).edit()
                        .putString("tree_uri", "content://example.documents/tree/USB%3ABooks")
                        .putString("tree_label", "Books").apply();
                SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
                ViewGroup panel = controller.createRegistryPanel(screen, new Dialog(screen));
                MaterialButton entry = (MaterialButton) panel.getChildAt(1);
                String row = entry.getText().toString();
                assertTrue(row.contains("Books"));
                assertTrue(row.contains("example.documents / USB:Books"));
                assertEquals(2, entry.getMaxLines());
                assertEquals(TextUtils.TruncateAt.END, entry.getEllipsize());
                assertEquals(View.TEXT_ALIGNMENT_VIEW_START, entry.getTextAlignment());
                assertEquals(Gravity.START, entry.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
                assertEquals(14f, entry.getTextSize() / screen.getResources().getDisplayMetrics().scaledDensity, 0.01f);
                float density = screen.getResources().getDisplayMetrics().density;
                assertEquals(Math.round(44 * density), entry.getMinHeight());
                assertEquals(Math.round(44 * density), entry.getMinimumHeight());
                assertEquals(Math.round(6 * density), entry.getPaddingTop());
                assertEquals(Math.round(6 * density), entry.getPaddingBottom());
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, entry.getLayoutParams().height);
                assertEquals(Math.round(44 * density), panel.getChildAt(2).getMinimumHeight());
                assertEquals(4, panel.getChildCount());
            } finally { screen.cleanPreferences(); }
        });
    }

    @Test public void dismissedAndReplacedRegistryDialogsCannotRunActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
            TestDialog old = new TestDialog(screen);
            TestDialog current = new TestDialog(screen);
            View panel = new View(screen);
            own(controller, current, panel);
            int[] calls = {0};
            controller.runDialogAction(old, panel, () -> calls[0]++);
            assertEquals(0, calls[0]);
            controller.dismissDialog();
            controller.runDialogAction(current, panel, () -> calls[0]++);
            assertEquals(0, calls[0]);
            assertFalse(current.shown);
        });
    }

    @Test public void registryActionIsSingleUseAndBlockedAfterClose() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
            TestDialog dialog = new TestDialog(screen);
            View panel = new View(screen);
            own(controller, dialog, panel);
            int[] calls = {0};
            controller.runDialogAction(dialog, panel, () -> calls[0]++);
            controller.runDialogAction(dialog, panel, () -> calls[0]++);
            assertEquals(1, calls[0]);
            TestDialog closed = new TestDialog(screen);
            own(controller, closed, panel);
            screen.closed = true;
            controller.runDialogAction(closed, panel, () -> calls[0]++);
            assertEquals(1, calls[0]);
            controller.dismissDialog();
        });
    }

    @Test public void registryPagesReuseWindowAndRejectPreviousPageButtons() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
            try {
                screen.getSharedPreferences("saf_storage_access", Context.MODE_PRIVATE).edit()
                        .putString("tree_uri", "content://example.documents/tree/USB%3ABooks")
                        .putString("tree_label", "Books").apply();
                controller.showRegisteredFolders();
                TestDialog dialog = screen.lastDialog;
                View oldFolderButton = screen.lastPanel.getChildAt(1);
                oldFolderButton.performClick();
                ViewGroup actions = screen.lastPanel;
                oldFolderButton.performClick();
                assertSame(actions, screen.lastPanel);
                assertEquals(1, screen.replacements);
                actions.getChildAt(3).performClick();
                screen.lastPanel.getChildAt(3).performClick();
                screen.lastPanel.getChildAt(4).performClick();
                assertEquals(4, screen.replacements);
                assertEquals(1, screen.dialogCreations);
                assertEquals(1, screen.dialogShows);
                assertEquals(0, dialog.dismissals);
                assertTrue(dialog.shown);
                screen.lastPanel.getChildAt(3).performClick();
                assertEquals(1, dialog.dismissals);
                assertFalse(dialog.shown);
                controller.showRegisteredFolders();
                assertEquals(2, screen.dialogCreations);
                assertEquals(2, screen.dialogShows);
                assertNotSame(dialog, screen.lastDialog);
            } finally { controller.dismissDialog(); screen.cleanPreferences(); }
        });
    }

    @Test public void failedPageReplacementClosesWindowAndRejectsOldActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
            try {
                controller.showRegisteredFolders();
                TestDialog dialog = screen.lastDialog;
                View panel = screen.lastPanel;
                screen.failReplacement = true;
                controller.runDialogAction(dialog, panel, controller::showRegisteredFolders);
                assertFalse(dialog.shown);
                assertEquals(1, dialog.dismissals);
                int[] calls = {0};
                controller.runDialogAction(dialog, panel, () -> calls[0]++);
                assertEquals(0, calls[0]);
            } finally { controller.dismissDialog(); screen.cleanPreferences(); }
        });
    }

    @Test public void failedActionDoesNotLeaveInactiveWindow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
            TestDialog dialog = new TestDialog(screen);
            View panel = new View(screen);
            own(controller, dialog, panel);
            controller.runDialogAction(dialog, panel, () -> { throw new IllegalStateException("fixture"); });
            assertFalse(dialog.shown);
            assertEquals(1, dialog.dismissals);
        });
    }

    @Test public void pendingPickerBlocksPageActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            SettingsFolderAccessController controller = new SettingsFolderAccessController(screen);
            TestDialog dialog = new TestDialog(screen);
            View panel = new View(screen);
            own(controller, dialog, panel);
            setPending(controller);
            int[] calls = {0};
            controller.runDialogAction(dialog, panel, () -> calls[0]++);
            assertEquals(0, calls[0]);
            controller.dismissDialog();
        });
    }

    @Test public void contentReplacementKeepsWindowAlphaAndStartsFreshScroll() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(context());
            TestDialog dialog = new TestDialog(screen);
            dialog.getWindow().getDecorView().setAlpha(0.75f);
            float dim = dialog.getWindow().getAttributes().dimAmount;
            screen.replaceActualContent(dialog, new LinearLayout(screen));
            ScrollView first = (ScrollView) dialog.content;
            int freshHeight = first.getLayoutParams().height;
            first.scrollTo(0, 100);
            first.getLayoutParams().height = 200;
            LinearLayout next = new LinearLayout(screen);
            screen.replaceActualContent(dialog, next);
            ScrollView second = (ScrollView) dialog.content;
            assertNotSame(first, second);
            assertNull(first.getParent());
            assertSame(next, second.getChildAt(0));
            assertEquals(0, second.getScrollY());
            assertEquals(freshHeight, second.getLayoutParams().height);
            assertEquals(0.75f, dialog.getWindow().getDecorView().getAlpha(), 0f);
            assertEquals(dim, dialog.getWindow().getAttributes().dimAmount, 0f);
            assertEquals(0, dialog.dismissals);
        });
    }

    private static void own(SettingsFolderAccessController controller, Dialog dialog, View panel) {
        try {
            Field field = SettingsFolderAccessController.class.getDeclaredField("activeDialog");
            field.setAccessible(true);
            field.set(controller, dialog);
            Field page = SettingsFolderAccessController.class.getDeclaredField("activePanel");
            page.setAccessible(true);
            page.set(controller, panel);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static final class TestDialog extends Dialog {
        boolean shown = true;
        int dismissals;
        View content;
        TestDialog(Context context) { super(context); }
        @Override public boolean isShowing() { return shown; }
        @Override public void dismiss() { shown = false; dismissals++; }
        @Override public void setContentView(View view) {
            content = view;
            super.setContentView(view);
        }
    }

    private static boolean pending(SettingsFolderAccessController controller) {
        try {
            Field field = SettingsFolderAccessController.class.getDeclaredField("pickerPending");
            field.setAccessible(true);
            return field.getBoolean(controller);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void setPending(SettingsFolderAccessController controller) {
        try {
            Field field = SettingsFolderAccessController.class.getDeclaredField("pickerPending");
            field.setAccessible(true);
            field.setBoolean(controller, true);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void deliver(SettingsFolderAccessController controller, Uri tree) {
        try {
            Method method = SettingsFolderAccessController.class.getDeclaredMethod("onTreeSelected", Uri.class);
            method.setAccessible(true);
            method.invoke(controller, tree);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static final class Screen extends SettingsActivity {
        private final View root;
        private final String fixturePrefs = "settings-folder-fixture-" + java.util.UUID.randomUUID();
        boolean closed;
        int browserLaunches;
        int dialogCreations;
        int dialogShows;
        int replacements;
        boolean failReplacement;
        TestDialog lastDialog;
        LinearLayout lastPanel;

        Screen(Context context) {
            attachBaseContext(context);
            root = LayoutInflater.from(context).inflate(R.layout.activity_settings, null, false);
        }

        @Override public <T extends View> T findViewById(int id) { return root.findViewById(id); }
        @Override public boolean isFinishing() { return closed; }
        @Override public void startActivity(android.content.Intent intent) { browserLaunches++; }
        @Override Dialog createRoundedSettingsDialog() {
            dialogCreations++;
            lastDialog = new TestDialog(this);
            lastDialog.shown = false;
            return lastDialog;
        }
        @Override void showRoundedSettingsDialog(Dialog dialog, LinearLayout panel) {
            dialogShows++;
            lastPanel = panel;
            ((TestDialog) dialog).shown = true;
        }
        @Override void replaceRoundedSettingsDialogContent(Dialog dialog, LinearLayout panel) {
            replacements++;
            if (failReplacement) throw new IllegalStateException("fixture");
            lastPanel = panel;
        }
        void replaceActualContent(Dialog dialog, LinearLayout panel) {
            super.replaceRoundedSettingsDialogContent(dialog, panel);
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences(name.equals("saf_storage_access") ? fixturePrefs : name, mode);
        }
        void cleanPreferences() { getBaseContext().deleteSharedPreferences(fixturePrefs); }
    }
}
