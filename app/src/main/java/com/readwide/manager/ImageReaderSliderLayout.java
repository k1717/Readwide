package com.readwide.manager;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/** Keeps slider gestures on the controls and lets empty app space reach the image. */
final class ImageReaderSliderLayout extends LinearLayout {
    private final Rect touchBounds = new Rect();
    private View pageLabel;
    private View slider;
    private int leftInset;
    private int rightInset;
    private int bottomInset;

    ImageReaderSliderLayout(Context context) {
        super(context);
        setClickable(true);
    }

    void setTouchTargets(View pageLabel, View slider) {
        this.pageLabel = pageLabel;
        this.slider = slider;
    }

    void setSystemInsets(int left, int right, int bottom) {
        leftInset = Math.max(0, left);
        rightInset = Math.max(0, right);
        bottomInset = Math.max(0, bottom);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();
            boolean inSystemInset = x < leftInset || x >= getWidth() - rightInset
                    || y >= getHeight() - bottomInset;
            if (!inSystemInset && !hits(pageLabel, x, y) && !hits(slider, x, y)) {
                // Decline DOWN so the parent assigns the whole gesture to the image below.
                return false;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean hits(View target, float x, float y) {
        if (target == null || target.getVisibility() != VISIBLE
                || target.getWidth() <= 0 || target.getHeight() <= 0) return false;
        target.getHitRect(touchBounds);
        if (target.getParent() != this) {
            offsetDescendantRectToMyCoords((View) target.getParent(), touchBounds);
        }
        return x >= touchBounds.left && x < touchBounds.right
                && y >= touchBounds.top && y < touchBounds.bottom;
    }
}
