package com.textview.reader.view;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView variant that hides the trailing row when it is only partially
 * visible at the bottom edge. This mirrors the TXT reader behavior that clips
 * the drawing region to the last fully visible text line instead of showing a
 * cut-off line.
 */
public class FullItemClipRecyclerView extends RecyclerView {

    public FullItemClipRecyclerView(@NonNull Context context) {
        super(context);
    }

    public FullItemClipRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FullItemClipRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int clipBottom = getLastFullItemClipBottom();
        int save = canvas.save();
        canvas.clipRect(getPaddingLeft(), getPaddingTop(),
                getWidth() - getPaddingRight(), clipBottom);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    private int getLastFullItemClipBottom() {
        int viewportTop = getPaddingTop();
        int viewportBottom = getHeight() - getPaddingBottom();
        if (viewportBottom <= viewportTop || getChildCount() == 0) {
            return viewportBottom;
        }

        int lastFullBottom = viewportTop;
        boolean hasFullVisibleChild = false;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;

            int childTop = Math.round(child.getTop() + child.getTranslationY());
            int childBottom = Math.round(child.getBottom() + child.getTranslationY());

            if (childBottom <= viewportTop || childTop >= viewportBottom) continue;

            if (childBottom <= viewportBottom) {
                if (childBottom > lastFullBottom) {
                    lastFullBottom = childBottom;
                    hasFullVisibleChild = true;
                }
                continue;
            }

            if (childTop < viewportBottom) {
                if (hasFullVisibleChild) {
                    return Math.max(viewportTop, Math.min(viewportBottom, lastFullBottom));
                }
                // Extremely tall first visible rows should remain usable rather
                // than making the list look empty. Normal file rows are shorter
                // than the viewport, so this is only a safety fallback.
                if (childTop <= viewportTop) {
                    return viewportBottom;
                }
                return Math.max(viewportTop, Math.min(viewportBottom, childTop));
            }
        }

        return viewportBottom;
    }
}
