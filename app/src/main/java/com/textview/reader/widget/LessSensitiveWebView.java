package com.textview.reader.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.webkit.WebView;

/**
 * WebView with less accidental long-press selection.
 *
 * Instead of overriding WebView's long-click action, this delays only a still
 * finger's ACTION_DOWN before WebView receives it. WebView then runs its normal
 * text-selection pipeline and toolbar anchoring, but the user must hold longer.
 * If the finger starts moving, the real down event is forwarded immediately so
 * scrolling/panning still feels normal and accidental selection is avoided.
 */
public class LessSensitiveWebView extends WebView {
    private static final long EXTRA_STATIONARY_DELAY_MS = 350L;

    private final int touchSlop;
    private MotionEvent pendingDownEvent;
    private boolean pendingDown;
    private boolean fingerDown;
    private float downX;
    private float downY;

    private final Runnable forwardPendingDownRunnable = new Runnable() {
        @Override
        public void run() {
            if (fingerDown && pendingDown) {
                forwardPendingDown();
            }
        }
    };

    public LessSensitiveWebView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public LessSensitiveWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public LessSensitiveWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return super.onTouchEvent(null);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                clearPendingDown();
                fingerDown = true;
                pendingDown = true;
                downX = event.getX();
                downY = event.getY();
                pendingDownEvent = MotionEvent.obtain(event);
                postDelayed(forwardPendingDownRunnable, EXTRA_STATIONARY_DELAY_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pendingDown) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    float moveLimit = touchSlop * 1.15f;
                    if ((dx * dx + dy * dy) > (moveLimit * moveLimit)) {
                        forwardPendingDown();
                    } else {
                        return true;
                    }
                }
                return super.onTouchEvent(event);

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pendingDown) {
                    forwardPendingDown();
                }
                fingerDown = false;
                boolean handled = super.onTouchEvent(event);
                clearPendingDown();
                return handled;

            default:
                if (pendingDown) forwardPendingDown();
                return super.onTouchEvent(event);
        }
    }

    private void forwardPendingDown() {
        if (!pendingDown || pendingDownEvent == null) return;
        removeCallbacks(forwardPendingDownRunnable);
        pendingDown = false;
        MotionEvent event = pendingDownEvent;
        pendingDownEvent = null;
        super.onTouchEvent(event);
        event.recycle();
    }

    private void clearPendingDown() {
        removeCallbacks(forwardPendingDownRunnable);
        pendingDown = false;
        if (pendingDownEvent != null) {
            pendingDownEvent.recycle();
            pendingDownEvent = null;
        }
    }
}
