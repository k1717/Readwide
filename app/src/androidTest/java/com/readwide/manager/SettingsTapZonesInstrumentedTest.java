package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.TapZoneProfile;
import com.google.android.material.button.MaterialButton;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsTapZonesInstrumentedTest {
    @Test public void listSummariesUseRatiosOrTxtInheritanceInAlignedColumns() {
        run(screen -> {
            screen.prefs.setTapZoneMode(PrefsManager.TAP_ZONE_HORIZONTAL);
            screen.prefs.setTapZonePercents(35, 35);
            screen.prefs.setUsesTxtTapZones(TapZoneProfile.Viewer.PDF, false);
            screen.prefs.setViewerTapZones(TapZoneProfile.Viewer.PDF,
                    new TapZoneProfile(PrefsManager.TAP_ZONE_VERTICAL, 30, 36));
            java.util.Map<String, ?> before = screen.store.getAll();
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            controller.showList();
            assertEquals(8, screen.panel.getChildCount());
            assertSummary(screen, 1, "TXT", "35/30/35", R.string.tap_profile_horizontal);
            assertSummary(screen, 2, "EPUB", screen.getString(R.string.tap_profile_follow_txt),
                    R.string.tap_profile_horizontal);
            assertSummary(screen, 3, "PDF", "30/34/36", R.string.tap_profile_vertical);
            MaterialButton close = (MaterialButton) screen.panel.getChildAt(7);
            assertEquals(Math.round(44 * screen.getResources().getDisplayMetrics().density), close.getMinimumHeight());
            assertEquals(before, screen.store.getAll());
            View oldEntry = screen.panel.getChildAt(2);
            oldEntry.performClick();
            assertEquals(1, screen.shows);
            assertEquals(1, screen.replacements);
            find(screen.panel, Switch.class, 1).setChecked(false);
            controller.showList();
            assertSummary(screen, 2, "EPUB", "35/30/35", R.string.tap_profile_horizontal);
            int replacements = screen.replacements;
            oldEntry.performClick();
            assertEquals(replacements, screen.replacements);
            controller.dismiss();
        });
    }

    @Test public void previewKeepsItsGapWhenOrientationChanges() {
        run(screen -> {
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            controller.showEditor(TapZoneProfile.Viewer.TXT);
            LinearLayout preview = find(screen.panel, LinearLayout.class, 0);
            int gap = Math.round(12 * screen.getResources().getDisplayMetrics().density);
            assertEquals(gap, ((LinearLayout.LayoutParams) preview.getLayoutParams()).topMargin);
            TextView leading = (TextView) screen.panel.getChildAt(screen.panel.indexOfChild(preview) + 1);
            assertEquals(gap, ((LinearLayout.LayoutParams) leading.getLayoutParams()).topMargin);
            Spinner mode = find(screen.panel, Spinner.class, 0);
            int next = screen.prefs.getTapZoneMode() == PrefsManager.TAP_ZONE_HORIZONTAL
                    ? PrefsManager.TAP_ZONE_VERTICAL : PrefsManager.TAP_ZONE_HORIZONTAL;
            mode.getOnItemSelectedListener().onItemSelected(mode, null, next, next);
            assertEquals(next, screen.prefs.getTapZoneMode());
            assertEquals(gap, ((LinearLayout.LayoutParams) preview.getLayoutParams()).topMargin);
            assertEquals(gap, ((LinearLayout.LayoutParams) leading.getLayoutParams()).topMargin);
            controller.dismiss();
        });
    }

    @Test public void profileColumnsAlignAcrossRowsAtNarrowWidthsAndInRtl() {
        run(screen -> {
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            controller.showList();
            float density = screen.getResources().getDisplayMetrics().density;
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                for (int widthDp : new int[]{240, 320}) {
                    int width = Math.round(widthDp * density);
                    int[] left = new int[3], right = new int[3];
                    for (int rowIndex = 1; rowIndex <= 6; rowIndex++) {
                        LinearLayout row = (LinearLayout) screen.panel.getChildAt(rowIndex);
                        row.setLayoutDirection(direction);
                        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        row.layout(0, 0, width, row.getMeasuredHeight());
                        for (int column = 0; column < 3; column++) {
                            View cell = row.getChildAt(column);
                            assertTrue(cell.getWidth() > 0);
                            assertTrue(cell.getLeft() >= row.getPaddingLeft());
                            assertTrue(cell.getRight() <= width - row.getPaddingRight());
                            if (rowIndex == 1) {
                                left[column] = cell.getLeft();
                                right[column] = cell.getRight();
                            } else {
                                assertEquals(left[column], cell.getLeft());
                                assertEquals(right[column], cell.getRight());
                            }
                        }
                    }
                    if (direction == View.LAYOUT_DIRECTION_LTR) {
                        assertTrue(right[0] < left[1]);
                        assertTrue(right[1] < left[2]);
                    } else {
                        assertTrue(right[2] < left[1]);
                        assertTrue(right[1] < left[0]);
                    }
                    assertEquals(width / 2f, (left[1] + right[1]) / 2f, 1f);
                    for (int rowIndex = 1; rowIndex <= 6; rowIndex++) {
                        LinearLayout row = (LinearLayout) screen.panel.getChildAt(rowIndex);
                        TextView middle = (TextView) row.getChildAt(1);
                        TextView orientation = (TextView) row.getChildAt(2);
                        assertEquals(View.TEXT_ALIGNMENT_CENTER, middle.getTextAlignment());
                        int inset = Math.round(6 * density);
                        assertEquals(inset, orientation.getPaddingEnd());
                        assertEquals(direction == View.LAYOUT_DIRECTION_LTR ? inset : 0,
                                orientation.getPaddingRight());
                        assertEquals(direction == View.LAYOUT_DIRECTION_RTL ? inset : 0,
                                orientation.getPaddingLeft());
                    }
                }
            }
            controller.dismiss();
        });
    }

    @Test public void profileColumnsWrapToTwoLinesAndGrowWithoutChangingWidths() {
        run(screen -> {
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            controller.showList();
            LinearLayout row = (LinearLayout) screen.panel.getChildAt(1);
            java.util.Map<String, ?> before = screen.store.getAll();
            float density = screen.getResources().getDisplayMetrics().density;
            String longLabel = "Misma configuración que TXT con un nombre muy largo para comprobar el ajuste";
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                row.setLayoutDirection(direction);
                for (int widthDp : new int[]{240, 320}) {
                    int width = Math.round(widthDp * density);
                    for (float textSize : new float[]{14f, 21f}) {
                        for (int column = 0; column < 3; column++) {
                            TextView cell = (TextView) row.getChildAt(column);
                            cell.setText("TXT");
                            cell.setTextSize(textSize);
                        }
                        measureRow(row, width);
                        int shortHeight = row.getMeasuredHeight();
                        int[] left = new int[3], right = new int[3];
                        for (int column = 0; column < 3; column++) {
                            TextView cell = (TextView) row.getChildAt(column);
                            assertEquals(1, cell.getLineCount());
                            left[column] = cell.getLeft();
                            right[column] = cell.getRight();
                            cell.setText(longLabel);
                        }
                        measureRow(row, width);
                        assertTrue(row.getMeasuredHeight() > shortHeight);
                        for (int column = 0; column < 3; column++) {
                            TextView cell = (TextView) row.getChildAt(column);
                            assertEquals(longLabel, cell.getText().toString());
                            assertEquals(2, cell.getLineCount());
                            assertTrue(cell.getLayout().getEllipsisCount(1) > 0);
                            assertEquals(left[column], cell.getLeft());
                            assertEquals(right[column], cell.getRight());
                            assertTrue(cell.getTop() >= row.getPaddingTop());
                            assertTrue(cell.getBottom() <= row.getHeight() - row.getPaddingBottom());
                            assertTrue(cell.getHeight() >= cell.getLayout().getLineBottom(1)
                                    + cell.getCompoundPaddingTop() + cell.getCompoundPaddingBottom());
                        }
                    }
                }
            }
            assertEquals(before, screen.store.getAll());
            controller.dismiss();
        });
    }

    private static void measureRow(LinearLayout row, int width) {
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
    }

    private static void assertSummary(Screen screen, int index, String viewer, String value, int direction) {
        LinearLayout entry = (LinearLayout) screen.panel.getChildAt(index);
        String expected = screen.getString(R.string.tap_profile_summary, viewer, value, screen.getString(direction));
        assertEquals(expected, entry.getContentDescription().toString());
        String[] labels = {viewer, value, screen.getString(direction)};
        assertEquals(3, entry.getChildCount());
        for (int column = 0; column < 3; column++) {
            TextView cell = (TextView) entry.getChildAt(column);
            assertEquals(labels[column], cell.getText().toString());
            assertEquals(2, cell.getMaxLines());
            assertEquals(TextUtils.TruncateAt.END, cell.getEllipsize());
            assertEquals(column == 1 ? View.TEXT_ALIGNMENT_CENTER
                            : column == 2 ? View.TEXT_ALIGNMENT_VIEW_END : View.TEXT_ALIGNMENT_VIEW_START,
                    cell.getTextAlignment());
            int horizontalGravity = column == 1 ? Gravity.CENTER_HORIZONTAL
                    : column == 2 ? Gravity.END : Gravity.START;
            assertEquals(horizontalGravity | Gravity.CENTER_VERTICAL, cell.getGravity());
            assertEquals(column == 2 ? Math.round(6 * screen.getResources().getDisplayMetrics().density) : 0,
                    cell.getPaddingEnd());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, cell.getImportantForAccessibility());
            assertFalse(cell.isClickable());
            assertFalse(cell.isFocusable());
            assertEquals(14f, cell.getTextSize() / screen.getResources().getDisplayMetrics().scaledDensity, 0.01f);
        }
        assertTrue(entry.isClickable());
        assertTrue(entry.isFocusable());
        AccessibilityNodeInfo info = entry.createAccessibilityNodeInfo();
        try { assertEquals("android.widget.Button", info.getClassName().toString()); }
        finally { info.recycle(); }
        float density = screen.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(36 * density), entry.getMinimumHeight());
        assertEquals(Math.round(4 * density), entry.getPaddingTop());
        assertEquals(Math.round(4 * density), entry.getPaddingBottom());
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, entry.getLayoutParams().height);
    }

    @Test public void inheritedControlsAreDisabledAndBindingDoesNotWrite() {
        run(screen -> {
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            java.util.Map<String, ?> before = screen.store.getAll();
            controller.showEditor(TapZoneProfile.Viewer.EPUB);
            assertFalse(find(screen.panel, Spinner.class, 0).isEnabled());
            assertFalse(find(screen.panel, SeekBar.class, 0).isEnabled());
            assertTrue(find(screen.panel, Switch.class, 0).isEnabled());
            assertEquals(before, screen.store.getAll());
            controller.dismiss();
        });
    }

    @Test public void switchingInheritanceReusesWindowAndKeepsSeparateValues() {
        run(screen -> {
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            TapZoneProfile.Viewer viewer = TapZoneProfile.Viewer.PDF;
            screen.prefs.setTapZonePercents(20, 40);
            controller.showEditor(viewer);
            find(screen.panel, Switch.class, 1).setChecked(false);
            assertTrue(find(screen.panel, Spinner.class, 0).isEnabled());
            assertEquals(20, screen.prefs.getViewerTapZones(viewer).leading);
            screen.prefs.setViewerTapZones(viewer, new TapZoneProfile(1, 25, 45));
            find(screen.panel, Switch.class, 1).setChecked(true);
            screen.prefs.setTapZonePercents(40, 20);
            find(screen.panel, Switch.class, 1).setChecked(false);
            assertEquals(25, screen.prefs.getViewerTapZones(viewer).leading);
            assertEquals(1, screen.shows);
            assertEquals(3, screen.replacements);
            controller.dismiss();
        });
    }

    @Test public void oldPanelAndDismissedDialogCannotWritePreferences() {
        run(screen -> {
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            controller.showEditor(TapZoneProfile.Viewer.TXT);
            Switch old = find(screen.panel, Switch.class, 0);
            controller.showEditor(TapZoneProfile.Viewer.IMAGE);
            old.setChecked(!old.isChecked());
            assertTrue(screen.prefs.getTapPagingEnabled());
            Switch current = find(screen.panel, Switch.class, 0);
            controller.dismiss();
            current.setChecked(!current.isChecked());
            assertFalse(screen.prefs.getImageTapPagingEnabled());
        });
    }

    @Test public void initialSpinnerNotificationCannotUndoNewerPreference() {
        run(screen -> {
            SettingsTapZonesController controller = new SettingsTapZonesController(screen);
            screen.prefs.setTapZoneMode(0);
            controller.showEditor(TapZoneProfile.Viewer.TXT);
            Spinner mode = find(screen.panel, Spinner.class, 0);
            screen.prefs.setTapZoneMode(1);
            mode.getOnItemSelectedListener().onItemSelected(mode, null, 0, 0);
            assertEquals(1, screen.prefs.getTapZoneMode());
            controller.dismiss();
        });
    }

    private interface Check { void run(Screen screen); }
    private void run(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try (Screen screen = new Screen()) { check.run(screen); }
        });
    }

    private static <T> T find(ViewGroup group, Class<T> type, int index) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (type.isInstance(child) && index-- == 0) return type.cast(child);
        }
        throw new AssertionError(type.getName());
    }

    private static final class Screen extends SettingsActivity implements AutoCloseable {
        final String storeName = "tap-profiles-test-" + java.util.UUID.randomUUID();
        final SharedPreferences store;
        LinearLayout panel;
        TestDialog shown;
        int shows, replacements;
        Screen() {
            Context context = new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation()
                    .getTargetContext(), R.style.Theme_TextViewReader);
            attachBaseContext(context);
            store = context.getSharedPreferences(storeName, Context.MODE_PRIVATE);
            try {
                java.lang.reflect.Constructor<PrefsManager> constructor = PrefsManager.class.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                prefs = constructor.newInstance(new ContextWrapper(context) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public SharedPreferences getSharedPreferences(String name, int mode) { return store; }
                });
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        @Override Dialog createRoundedSettingsDialog() { return new TestDialog(this); }
        @Override void showRoundedSettingsDialog(Dialog dialog, LinearLayout content) {
            shown = (TestDialog) dialog;
            shown.visible = true;
            panel = content;
            shows++;
        }
        @Override void replaceRoundedSettingsDialogContent(Dialog dialog, LinearLayout content) {
            panel = content;
            replacements++;
        }
        @Override public void close() {
            if (shown != null) shown.dismiss();
            getBaseContext().deleteSharedPreferences(storeName);
        }
    }
    private static final class TestDialog extends Dialog {
        boolean visible;
        TestDialog(Context context) { super(context); }
        @Override public boolean isShowing() { return visible; }
        @Override public void dismiss() { visible = false; }
    }
}
