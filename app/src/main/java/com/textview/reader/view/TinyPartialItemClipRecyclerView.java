package com.textview.reader.view;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Hides only the tiny trailing sliver of the next row at the bottom edge.
 * Unlike a full-row clip, rows that are meaningfully visible remain visible,
 * so the recent list does not leave an awkward large blank gap above controls.
 */
public class TinyPartialItemClipRecyclerView extends RecyclerView {

    private final int minimumBottomVisibleHeight;

    public TinyPartialItemClipRecyclerView(@NonNull Context context) {
        super(context);
        minimumBottomVisibleHeight = dpToPx(18);
    }

    public TinyPartialItemClipRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        minimumBottomVisibleHeight = dpToPx(18);
    }

    public TinyPartialItemClipRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        minimumBottomVisibleHeight = dpToPx(18);
    }

    @Override
    public boolean drawChild(Canvas canvas, View child, long drawingTime) {
        int viewportTop = getPaddingTop();
        int viewportBottom = getHeight() - getPaddingBottom();
        if (viewportBottom > viewportTop && child != null && child.getVisibility() == View.VISIBLE) {
            int childTop = Math.round(child.getTop() + child.getTranslationY());
            int childBottom = Math.round(child.getBottom() + child.getTranslationY());
            if (childTop < viewportBottom && childBottom > viewportBottom) {
                int visibleHeight = viewportBottom - Math.max(childTop, viewportTop);
                if (visibleHeight > 0 && visibleHeight < minimumBottomVisibleHeight) {
                    return true;
                }
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
