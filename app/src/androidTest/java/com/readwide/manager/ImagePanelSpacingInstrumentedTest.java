package com.readwide.manager;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Real toolbar layout checks; system safe insets remain separate from title spacing. */
@RunWith(AndroidJUnit4.class)
public class ImagePanelSpacingInstrumentedTest {
    private static void checkTitle(boolean withSubtitle, int topInsetDp, boolean rtl) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        float density = context.getResources().getDisplayMetrics().density;
        int inset = Math.round(topInsetDp * density);
        int row = Math.round(48 * density);
        FrameLayout root = new FrameLayout(context);
        int layout = context.getResources().getIdentifier("image_reader_toolbar", "layout", context.getPackageName());
        assertNotEquals(0, layout);
        Toolbar toolbar = (Toolbar) LayoutInflater.from(context).inflate(layout, root, false);
        toolbar.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        toolbar.setTitle("page.jpg");
        if (withSubtitle) toolbar.setSubtitle("1 / 145");
        toolbar.setPadding(0, inset, 0, 0);
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, row + inset));
        root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(360 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(row + inset, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), row + inset);
        TextView title = null;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof TextView && "page.jpg".contentEquals(((TextView) child).getText())) {
                title = (TextView) child;
                break;
            }
        }
        assertNotNull(title);
        assertEquals(inset + Math.round(2 * density), title.getTop());
        assertEquals(row + inset, toolbar.getHeight());
        assertEquals(inset, toolbar.getPaddingTop());
    }

    @Test public void singleTitleStartsBelowSafeInset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> checkTitle(false, 30, false));
    }

    @Test public void titleAndCounterKeepSameTopInRtl() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> checkTitle(true, 30, true));
    }

    @Test public void noTopInsetStillKeepsSmallTitleGap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> checkTitle(true, 0, false));
    }
}
