package com.readwide.manager;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small, persistent recovery card. Loading and page-number UI remain separate. */
final class ViewerErrorPanel extends LinearLayout {
    private final TextView message;
    private final TextView retry;
    private final TextView close;
    private final ScrollView messageScroll;
    private final LinearLayout actions;
    private final int preferredMessageHeight;

    ViewerErrorPanel(Context context, int background, int foreground) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(12), dp(16), dp(8));
        setClickable(true);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(background);
        surface.setCornerRadius(dp(18));
        surface.setStroke(dp(1), foreground);
        setBackground(surface);
        message = new TextView(context);
        message.setTextColor(foreground);
        message.setTextSize(15f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(4), dp(8), dp(4), dp(12));
        messageScroll = new ScrollView(context);
        messageScroll.setFillViewport(false);
        messageScroll.addView(message);
        // Long decoder messages stay readable without pushing the actions off-screen.
        preferredMessageHeight = Math.min(dp(144), Math.max(dp(48),
                getResources().getDisplayMetrics().heightPixels / 5));
        addView(messageScroll, new LayoutParams(-1, preferredMessageHeight));
        actions = new LinearLayout(context);
        retry = action(R.string.viewer_retry, background, foreground);
        close = action(R.string.close, background, foreground);
        LayoutParams retryLayout = new LayoutParams(0, -2, 1f);
        retryLayout.setMarginEnd(dp(4));
        LayoutParams closeLayout = new LayoutParams(0, -2, 1f);
        closeLayout.setMarginStart(dp(4));
        actions.addView(retry, retryLayout);
        actions.addView(close, closeLayout);
        addView(actions);
        setVisibility(GONE);
    }

    void attachTo(FrameLayout parent) {
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                -1, -2, Gravity.CENTER);
        layout.setMargins(dp(24), dp(12), dp(24), dp(12));
        parent.addView(this, layout);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.min(dp(430), MeasureSpec.getSize(widthMeasureSpec));
        int cappedWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        // Measure the actions without a height cap first: translated labels may wrap.
        // The actual parent can be much shorter than the display in split-screen or
        // landscape. Only shrink the scrollable message, never the button row.
        measureChildWithMargins(actions, cappedWidthSpec, 0,
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);
        int messageHeight = preferredMessageHeight;
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int available = Math.max(0, MeasureSpec.getSize(heightMeasureSpec)
                    - getPaddingTop() - getPaddingBottom() - actions.getMeasuredHeight());
            messageHeight = Math.min(messageHeight, available);
        }
        messageScroll.getLayoutParams().height = messageHeight;
        super.onMeasure(cappedWidthSpec, heightMeasureSpec);
    }

    void show(String text, Runnable retryAction, Runnable closeAction) {
        message.setText(text);
        retry.setVisibility(retryAction != null ? VISIBLE : GONE);
        retry.setOnClickListener(v -> {
            if (getVisibility() != VISIBLE || retryAction == null) return;
            retryAction.run();
        });
        close.setOnClickListener(v -> closeAction.run());
        setVisibility(VISIBLE);
        bringToFront();
    }

    void hide() {
        setVisibility(GONE);
        retry.setOnClickListener(null);
        close.setOnClickListener(null);
    }

    private TextView action(int label, int background, int foreground) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(6), dp(8), dp(6), dp(8));
        button.setFocusable(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(10));
        shape.setStroke(dp(1), foreground);
        button.setBackground(shape);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
