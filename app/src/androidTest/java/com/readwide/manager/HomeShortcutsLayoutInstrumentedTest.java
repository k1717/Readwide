package com.readwide.manager;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android resource inflation checks. These do not modify saved shortcuts/history. */
@RunWith(AndroidJUnit4.class)
public class HomeShortcutsLayoutInstrumentedTest {
    private Context themed() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_TextViewReader);
    }
    @Test public void pinsAreAboveRecentWithoutSharingItsAdapterView() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = LayoutInflater.from(themed()).inflate(R.layout.activity_main, null, false);
            LinearLayout recent = root.findViewById(R.id.recent_section);
            assertEquals(R.id.home_shortcuts_section, recent.getChildAt(0).getId());
            assertNotSame(root.findViewById(R.id.home_shortcuts_list), root.findViewById(R.id.recent_list));
            assertNotNull(root.findViewById(R.id.recent_empty_text));
        });
    }
    @Test public void emptyPinsStartCollapsedBehindFocusableTitle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed();
            View root = LayoutInflater.from(context).inflate(R.layout.home_shortcuts_section, null, false);
            assertEquals(View.GONE, root.findViewById(R.id.home_shortcuts_list).getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.home_shortcuts_empty).getVisibility());
            View title = root.findViewById(R.id.home_shortcuts_title);
            int minimum = Math.round(38 * context.getResources().getDisplayMetrics().density);
            assertTrue(title.getLayoutParams().height >= minimum || title.getMinimumHeight() >= minimum);
            assertTrue(title.isClickable());
            assertTrue(title.isFocusable());
            ViewGroup header = (ViewGroup) title.getParent();
            assertEquals(1, header.getChildCount());
            assertSame(title, header.getChildAt(0));
            Drawable background = title.getBackground();
            assertTrue(background == null || (background instanceof ColorDrawable
                    && ((ColorDrawable) background).getColor() == Color.TRANSPARENT));
        });
    }
    @Test public void homeHeadingsUseCompactMatchingSizesWithoutShrinkingClearControl() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed();
            View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false);
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, metrics);
            TextView recent = root.findViewById(R.id.recent_section_title);
            TextView pinned = root.findViewById(R.id.home_shortcuts_title);
            assertEquals(textSize, recent.getTextSize(), 0.01f);
            assertEquals(textSize, pinned.getTextSize(), 0.01f);
            View row = root.findViewById(R.id.recent_header_row);
            assertEquals(Math.round(38 * metrics.density), row.getMinimumHeight());
            assertEquals(Math.round(38 * metrics.density), pinned.getMinimumHeight());
            assertEquals(Math.round(2 * metrics.density), row.getPaddingTop());
            assertEquals(Math.round(2 * metrics.density), row.getPaddingBottom());
            assertEquals(Math.round(34 * metrics.density),
                    root.findViewById(R.id.recent_clear_all).getLayoutParams().height);
        });
    }
    @Test public void removeControlHasIndependentFortyEightDpTarget() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed();
            View root = LayoutInflater.from(context).inflate(R.layout.item_home_shortcut,
                    new LinearLayout(context), false);
            View remove = root.findViewById(R.id.home_shortcut_remove);
            int minimum = Math.round(48 * context.getResources().getDisplayMetrics().density);
            assertTrue(remove.getLayoutParams().width >= minimum);
            assertTrue(remove.getLayoutParams().height >= minimum);
            assertTrue(remove.isClickable());
            assertTrue(remove.isFocusable());
            assertTrue(root.isClickable());
            assertNotNull(root.findViewById(R.id.home_shortcut_path));
        });
    }
}
