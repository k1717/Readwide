package com.readwide.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android font/Canvas check; no activity navigation or saved settings changes. */
@RunWith(AndroidJUnit4.class)
public class HomeDisclosureSpanInstrumentedTest {
    @Test public void localizedTitleAndBothMarkersShareTheirVisibleCenter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (String title : new String[]{"고정 폴더", "Pinned folders", "固定フォルダ", "固定文件夹", "固定資料夾"}) {
                for (float size : new float[]{15f, 22f}) {
                    for (boolean expanded : new boolean[]{false, true}) {
                        verify(context, title, size, expanded);
                    }
                }
            }
        });
    }

    private void verify(Context context, String title, float size, boolean expanded) {
        TextView view = new TextView(context);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(Color.BLACK);
        view.setMinHeight(Math.round(42 * context.getResources().getDisplayMetrics().density));
        int width = (int) Math.ceil(view.getPaint().measureText(title) + view.getTextSize() * 4);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.setText(title);
        view.measure(widthSpec, heightSpec);
        int titleHeight = view.getMeasuredHeight();
        SpannableString label = new SpannableString(title + "\u00a0\u00a0" + (expanded ? "▾" : "▸"));
        int marker = label.length() - 1;
        label.setSpan(new HomeDisclosureSpan(title, expanded), marker, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(label);
        view.measure(widthSpec, heightSpec);
        assertEquals("Marker must not inflate the row", titleHeight, view.getMeasuredHeight());
        view.layout(0, 0, width, view.getMeasuredHeight());
        int split = (int) Math.floor(view.getLayout().getPrimaryHorizontal(marker));
        Bitmap bitmap = Bitmap.createBitmap(width, view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
            float textCenter = inkCenter(bitmap, 0, split);
            float markerCenter = inkCenter(bitmap, split, width);
            assertEquals(title + ": title/marker visible centers", textCenter, markerCenter, 1.5f);
        } finally {
            bitmap.recycle();
        }
    }

    private float inkCenter(Bitmap bitmap, int left, int right) {
        int top = bitmap.getHeight(), bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = left; x < right; x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        assertTrue("Expected visible ink", bottom >= top);
        return (top + bottom) / 2f;
    }
}
