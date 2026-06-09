package com.textview.reader;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Shared dialog shell/layout utilities for bottom-positioned and adaptive popups.
 *
 * Several viewer screens used to carry their own copy of the same ScrollView
 * wrapper, constrained-window max-height logic, and bottom Dialog window setup.
 * Keeping that mechanics here prevents subtle one-screen drift while preserving
 * each caller's own content styling and width policy.
 */
final class AdaptiveDialogLayoutHelper {
    private AdaptiveDialogLayoutHelper() {}

    static Dialog createStableBottomDialog(@NonNull AppCompatActivity activity,
                                           @NonNull View content,
                                           int yDp,
                                           boolean adjustResize,
                                           int widthPx) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        FrameLayout outerFrame = new FrameLayout(activity);
        outerFrame.setBackgroundColor(Color.TRANSPARENT);
        outerFrame.setClipChildren(true);
        outerFrame.setClipToPadding(true);

        if (content instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) content;
            group.setClipChildren(true);
            group.setClipToPadding(true);
        }

        ScrollView adaptiveScroll = wrapAdaptiveContent(activity, content, outerFrame);
        dialog.setContentView(outerFrame);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = widthPx;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.y = dpToPx(activity, yDp);
            lp.dimAmount = 0.16f;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (adjustResize) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }

        applyAdaptiveMaxHeight(activity, adaptiveScroll, widthPx);
        return dialog;
    }

    static ScrollView wrapAdaptiveContent(@NonNull Context context,
                                          @NonNull View content,
                                          @NonNull ViewGroup outerFrame) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        outerFrame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    static void applyAdaptiveMaxHeight(@NonNull AppCompatActivity activity,
                                       @NonNull View adaptiveView,
                                       int widthPx) {
        int availableHeight = currentVisibleWindowHeightPx(activity);
        if (availableHeight <= 0) return;
        if (!shouldApplyAdaptiveMaxHeight(activity, availableHeight)) return;

        int maxHeight = Math.max(dpToPx(activity, 220),
                Math.round(availableHeight * 0.88f) - dpToPx(activity, 24));
        adaptiveView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measured = adaptiveView.getMeasuredHeight();
        if (measured > maxHeight) {
            ViewGroup.LayoutParams lp = adaptiveView.getLayoutParams();
            lp.height = maxHeight;
            adaptiveView.setLayoutParams(lp);
        }
    }

    static boolean shouldApplyAdaptiveMaxHeight(@NonNull AppCompatActivity activity,
                                                int availableHeightPx) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
                && activity.isInMultiWindowMode()) {
            return true;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && activity.isInPictureInPictureMode()) {
            return true;
        }
        int fullHeightPx = activity.getResources().getDisplayMetrics().heightPixels;
        return fullHeightPx > 0 && availableHeightPx < Math.round(fullHeightPx * 0.82f);
    }

    static int currentVisibleWindowHeightPx(@NonNull AppCompatActivity activity) {
        Rect rect = new Rect();
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor != null) {
            decor.getWindowVisibleDisplayFrame(rect);
            if (rect.height() > dpToPx(activity, 240)) return rect.height();
            if (decor.getHeight() > dpToPx(activity, 240)) return decor.getHeight();
        }
        return activity.getResources().getDisplayMetrics().heightPixels;
    }

    static int dpToPx(@NonNull Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()));
    }
}
