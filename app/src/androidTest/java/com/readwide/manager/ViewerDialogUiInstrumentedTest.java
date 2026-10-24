package com.readwide.manager;

import android.content.Context;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Unattached UI fixtures only; no preferences, document loads or position writes. */
@RunWith(AndroidJUnit4.class)
public class ViewerDialogUiInstrumentedTest {
    @Test public void defaultSearchOptionsStartCollapsedWithoutChangingConditions() {
        onMain(() -> {
            SearchFixture f = new SearchFixture(false);
            assertEquals(View.GONE, f.content.getVisibility());
            assertFalse(f.condition.isChecked());
            f.toggle.performClick();
            assertEquals(View.VISIBLE, f.content.getVisibility());
            assertFalse(f.condition.isChecked());
        });
    }

    @Test public void savedConditionsAreVisibleWhenSearchOpens() {
        onMain(() -> {
            SearchFixture f = new SearchFixture(true);
            assertEquals(View.VISIBLE, f.content.getVisibility());
            assertTrue(f.condition.isChecked());
        });
    }

    @Test public void foldedSummaryIncludesEnabledConditionsAndKeepsTheirListener() {
        onMain(() -> {
            SearchFixture f = new SearchFixture(false);
            int[] changes = {0};
            f.condition.setOnCheckedChangeListener((v, checked) -> changes[0]++);
            f.toggle.performClick();
            f.condition.setChecked(true);
            f.toggle.performClick();
            assertTrue(f.toggle.getText().toString().contains("Regex"));
            assertEquals(1, changes[0]);
            f.toggle.performClick();
            assertTrue(f.condition.isChecked());
            assertEquals(1, changes[0]);
        });
    }

    @Test public void foldingKeepsOccurrenceInputForNextUse() {
        onMain(() -> {
            SearchFixture f = new SearchFixture(false);
            EditText occurrence = new EditText(context());
            f.content.addView(occurrence);
            f.toggle.performClick();
            occurrence.setText("21");
            f.toggle.performClick();
            f.toggle.performClick();
            assertEquals("21", occurrence.getText().toString());
        });
    }

    @Test public void retryStopsDispatchingWhenItsOwnerStartsLoading() {
        onMain(() -> {
            ViewerErrorPanel panel = errorPanel();
            int[] requests = {0};
            panel.show("Decode failed", () -> { requests[0]++; panel.hide(); }, () -> { });
            TextView retry = action(panel, 0);
            retry.performClick();
            retry.performClick();
            assertEquals(1, requests[0]);
            assertEquals(View.GONE, panel.getVisibility());
        });
    }

    @Test public void nonRetryableErrorStillAllowsClose() {
        onMain(() -> {
            ViewerErrorPanel panel = errorPanel();
            int[] closes = {0};
            panel.show("Archive changed", null, () -> closes[0]++);
            assertEquals(View.GONE, action(panel, 0).getVisibility());
            action(panel, 1).performClick();
            assertEquals(1, closes[0]);
        });
    }

    @Test public void hideRemovesOldRecoveryCallbacks() {
        onMain(() -> {
            ViewerErrorPanel panel = errorPanel();
            int[] calls = {0};
            panel.show("Old failure", () -> calls[0]++, () -> calls[0]++);
            panel.hide();
            action(panel, 0).performClick();
            action(panel, 1).performClick();
            assertEquals(0, calls[0]);
            panel.show("New failure", () -> calls[0] += 2, () -> { });
            action(panel, 0).performClick();
            assertEquals(2, calls[0]);
        });
    }

    @Test public void recoveryCardFitsAfterWindowBecomesNarrower() {
        onMain(() -> {
            FrameLayout parent = new FrameLayout(context());
            ViewerErrorPanel panel = errorPanel();
            panel.attachTo(parent);
            panel.show("Failure", () -> { }, () -> { });
            int width = Math.round(280 * context().getResources().getDisplayMetrics().density);
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY));
            assertTrue(panel.getMeasuredWidth() > 0);
            assertTrue(panel.getMeasuredWidth() < width);
        });
    }

    @Test public void shortRecoveryCardKeepsBothActionsInsideItsBounds() {
        onMain(() -> {
            ViewerErrorPanel panel = errorPanel();
            panel.show("A decoder message that remains scrollable in a short window.",
                    () -> { }, () -> { });
            measureAndLayout(panel, 320, 130);
            LinearLayout actions = (LinearLayout) panel.getChildAt(1);
            assertTrue(panel.getChildAt(0).getMeasuredHeight() > 0);
            assertTrue(actions.getBottom() <= panel.getMeasuredHeight() - panel.getPaddingBottom());
            for (int i = 0; i < 2; i++) {
                assertTrue(action(panel, i).getMeasuredHeight() >= dp(48));
                assertTrue(action(panel, i).getBottom() <= actions.getMeasuredHeight());
            }
        });
    }

    @Test public void recoveryMessageRegainsHeightAfterWindowExpands() {
        onMain(() -> {
            ViewerErrorPanel panel = errorPanel();
            panel.show("Failure", () -> { }, () -> { });
            measureAndLayout(panel, 320, 500);
            int preferred = panel.getChildAt(0).getMeasuredHeight();
            int shortHeight = preferred + panel.getChildAt(1).getMeasuredHeight()
                    + panel.getPaddingTop() + panel.getPaddingBottom() - dp(16);
            panel.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(shortHeight, View.MeasureSpec.AT_MOST));
            assertTrue(panel.getChildAt(0).getMeasuredHeight() < preferred);
            measureAndLayout(panel, 320, 500);
            assertEquals(preferred, panel.getChildAt(0).getMeasuredHeight());
        });
    }

    private static void measureAndLayout(View view, int widthDp, int heightDp) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private static int dp(int value) {
        return Math.round(value * context().getResources().getDisplayMetrics().density);
    }

    private static TextView action(ViewerErrorPanel panel, int index) {
        return (TextView) ((LinearLayout) panel.getChildAt(1)).getChildAt(index);
    }
    private static ViewerErrorPanel errorPanel() {
        return new ViewerErrorPanel(context(), Color.BLACK, Color.WHITE);
    }
    private static Context context() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_TextViewReader);
    }
    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static final class SearchFixture {
        final TextView toggle = new TextView(context());
        final LinearLayout content = new LinearLayout(context());
        final CheckBox condition = new CheckBox(context());
        SearchFixture(boolean active) {
            toggle.setText("Options");
            condition.setText("Regex");
            condition.setChecked(active);
            content.addView(condition);
            SearchOptionsDisclosure.bind(toggle, content, condition);
        }
    }
}
