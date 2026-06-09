package com.textview.reader.controller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class ReaderToolbarController {
    private final Activity activity;
    private final View bottomBar;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private HorizontalScrollView actionScroll;
    private LinearLayout actionContainer;
    private int visibleScrollableButtons = 5;
    private int defaultFirstButtonIndex = 0;
    private int buttonWidthPx = 0;
    private boolean snapScheduled = false;
    private boolean userTouchingScroll = false;

    public ReaderToolbarController(@NonNull Activity activity, @Nullable View bottomBar) {
        this.activity = activity;
        this.bottomBar = bottomBar;
    }

    public void prepareToolbarContainer() {
        if (bottomBar == null) return;
        bottomBar.setClickable(true);
        bottomBar.setFocusable(false);
        bottomBar.bringToFront();
    }

    public void setupScrollableActionStrip(@IdRes int scrollViewId,
                                           @IdRes int actionContainerId,
                                           int visibleButtons,
                                           int defaultFirstIndex) {
        View scroll = activity.findViewById(scrollViewId);
        View container = activity.findViewById(actionContainerId);
        if (!(scroll instanceof HorizontalScrollView) || !(container instanceof LinearLayout)) return;

        actionScroll = (HorizontalScrollView) scroll;
        actionContainer = (LinearLayout) container;
        visibleScrollableButtons = Math.max(1, visibleButtons);
        defaultFirstButtonIndex = Math.max(0, defaultFirstIndex);

        actionScroll.setFillViewport(false);
        actionScroll.setSmoothScrollingEnabled(true);
        actionScroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                userTouchingScroll = true;
                snapScheduled = false;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                userTouchingScroll = false;
                scheduleSnapToNearestButton();
            }
            return false;
        });
        actionScroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.max(0, right - left);
            int oldWidth = Math.max(0, oldRight - oldLeft);
            if (width > 0 && width != oldWidth) {
                applyBalancedButtonWidths();
                resetScrollToDefault();
            }
        });
        actionScroll.post(() -> {
            applyBalancedButtonWidths();
            resetScrollToDefault();
        });
    }

    public void resetScrollToDefault() {
        if (actionScroll == null) return;
        actionScroll.post(() -> {
            applyBalancedButtonWidths();
            int width = getButtonWidthPx();
            if (width <= 0) return;
            int target = clampScrollX(defaultFirstButtonIndex * width);
            actionScroll.scrollTo(target, 0);
        });
    }

    public void bindScrollableButton(@IdRes int viewId, @Nullable Runnable action) {
        View button = activity.findViewById(viewId);
        if (button == null) return;
        configureBasicButton(button, action);
    }

    public void bindFixedButton(@IdRes int viewId, @Nullable Runnable action) {
        View button = activity.findViewById(viewId);
        if (button == null) return;
        configureBasicButton(button, action);
        button.setOnTouchListener((v, event) -> handleFixedToolbarTouch(v, event));
    }

    private void configureBasicButton(@NonNull View button, @Nullable Runnable action) {
        button.setClickable(true);
        button.setFocusable(true);
        button.setLongClickable(false);
        button.setHapticFeedbackEnabled(false);
        button.setOnClickListener(v -> {
            if (action != null) action.run();
        });
    }

    private void applyBalancedButtonWidths() {
        if (actionScroll == null || actionContainer == null) return;
        int scrollWidth = actionScroll.getWidth();
        if (scrollWidth <= 0) return;
        int targetWidth = Math.max(dpToPx(48), scrollWidth / visibleScrollableButtons);
        if (targetWidth == buttonWidthPx) return;
        buttonWidthPx = targetWidth;
        for (int i = 0; i < actionContainer.getChildCount(); i++) {
            View child = actionContainer.getChildAt(i);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params == null) {
                params = new LinearLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.MATCH_PARENT);
            } else {
                params.width = targetWidth;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            child.setLayoutParams(params);
        }
    }

    private void scheduleSnapToNearestButton() {
        if (snapScheduled) return;
        snapScheduled = true;
        handler.postDelayed(() -> {
            snapScheduled = false;
            if (!userTouchingScroll) snapToNearestButton();
        }, 260L);
    }

    private void snapToNearestButton() {
        if (actionScroll == null) return;
        int width = getButtonWidthPx();
        if (width <= 0) return;
        int target = Math.round(actionScroll.getScrollX() / (float) width) * width;
        target = clampScrollX(target);
        if (Math.abs(target - actionScroll.getScrollX()) <= 1) return;
        actionScroll.smoothScrollTo(target, 0);
    }

    private int getButtonWidthPx() {
        if (buttonWidthPx <= 0) applyBalancedButtonWidths();
        return buttonWidthPx;
    }

    private int clampScrollX(int target) {
        if (actionScroll == null || actionContainer == null) return Math.max(0, target);
        int contentWidth = actionContainer.getWidth();
        if (buttonWidthPx > 0) {
            contentWidth = Math.max(contentWidth, actionContainer.getChildCount() * buttonWidthPx);
        }
        int max = Math.max(0, contentWidth - actionScroll.getWidth());
        return Math.max(0, Math.min(target, max));
    }

    private boolean handleFixedToolbarTouch(@NonNull View view, @NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestParentIntercept(view, true);
                if (bottomBar != null) bottomBar.bringToFront();
                return true;

            case MotionEvent.ACTION_UP:
                requestParentIntercept(view, false);
                if (isTouchInsideView(view, event)) view.performClick();
                return true;

            case MotionEvent.ACTION_CANCEL:
                requestParentIntercept(view, false);
                return true;

            default:
                return true;
        }
    }

    private void requestParentIntercept(@NonNull View view, boolean disallow) {
        if (view.getParent() != null) {
            view.getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private boolean isTouchInsideView(@NonNull View view, @NonNull MotionEvent event) {
        int slop = dpToPx(10);
        return event.getX() >= -slop
                && event.getX() <= view.getWidth() + slop
                && event.getY() >= -slop
                && event.getY() <= view.getHeight() + slop;
    }

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                activity.getResources().getDisplayMetrics()));
    }
}
