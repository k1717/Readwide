package com.readwide.manager;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;

/** Shared side fast-scroll thumb for viewer surfaces with vertical scrolling. */
final class ProportionalFastScrollController {
    private static final float MIN_THUMB_HEIGHT_DP = 32f;
    private static final float THUMB_VERTICAL_TOUCH_PADDING_DP = 8f;
    private static final float MIN_INTERACTIVE_ALPHA = 0.05f;
    private static final int INVALID_POINTER_ID = -1;
    private static final long METRICS_SETTLE_DELAY_MS = 260L;
    private static final long METRICS_LATE_SETTLE_DELAY_MS = 1_000L;
    private static final long THUMB_FADE_DELAY_MS = 450L;
    private static final long THUMB_FADE_DURATION_MS = 180L;

    interface ScrollSource {
        boolean isEnabled();
        long scrollRange();
        long scrollExtent();
        long scrollOffset();
        void scrollToFraction(float fraction);
        void onFastScrollStart();
        void onFastScrollStop();
    }

    private static final class Metrics {
        final long range;
        final long extent;
        final long offset;

        Metrics(long range, long extent, long offset) {
            this.range = Math.max(0L, range);
            this.extent = Math.max(0L, extent);
            long maxOffset = Math.max(0L, this.range - this.extent);
            this.offset = Math.max(0L, Math.min(maxOffset, offset));
        }

        boolean isScrollable() {
            return extent > 0L && range > extent;
        }

        long maxOffset() {
            return Math.max(0L, range - extent);
        }
    }

    private final View rail;
    private final View thumb;
    private final ScrollSource source;

    private boolean installed;
    private boolean suspended;
    private boolean dragging;
    private boolean destroyed;
    private boolean updatePosted;
    private boolean revealOnNextUpdate;
    private int activePointerId = INVALID_POINTER_ID;
    private int dragRailHeight;
    private int dragThumbHeight;
    private float dragTravel;
    private float dragGrabOffset;
    private ViewParent interceptedParent;

    private final Runnable updateRunnable = () -> {
        updatePosted = false;
        updateThumbFromSource();
    };
    private final Runnable settledUpdateRunnable = this::requestFrameUpdate;
    private final Runnable lateSettledUpdateRunnable = this::requestFrameUpdate;
    private final Runnable fadeRunnable = this::fadeThumbOut;
    private final View.OnLayoutChangeListener railLayoutChangeListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if ((right - left) != (oldRight - oldLeft)
                        || (bottom - top) != (oldBottom - oldTop)) {
                    scheduleMetricsUpdate();
                }
            };

    ProportionalFastScrollController(@NonNull View rail,
                                     @NonNull View thumb,
                                     @NonNull ScrollSource source) {
        this.rail = rail;
        this.thumb = thumb;
        this.source = source;
    }

    void install() {
        if (installed || destroyed) return;
        installed = true;
        rail.setClickable(false);
        rail.setOnTouchListener(this::onRailTouch);
        rail.addOnLayoutChangeListener(railLayoutChangeListener);
        hideThumbImmediately();
        scheduleMetricsUpdate();
    }

    /** Refreshes range/extent/position without revealing the thumb. */
    void scheduleMetricsUpdate() {
        if (!isOperational()) return;
        requestFrameUpdate();
        rail.removeCallbacks(settledUpdateRunnable);
        rail.removeCallbacks(lateSettledUpdateRunnable);
        rail.postDelayed(settledUpdateRunnable, METRICS_SETTLE_DELAY_MS);
        rail.postDelayed(lateSettledUpdateRunnable, METRICS_LATE_SETTLE_DELAY_MS);
    }

    /** Records real viewport motion; multiple callbacks in one frame are coalesced. */
    void notifyScrollActivity() {
        if (!isOperational()) return;
        revealOnNextUpdate = true;
        requestFrameUpdate();
    }

    /** Hides stale page geometry before a viewer replaces its content. */
    void beginContentChange() {
        if (!isOperational()) return;
        finishDrag(false);
        revealOnNextUpdate = false;
        hideThumbImmediately();
        scheduleMetricsUpdate();
    }

    void suspend() {
        if (destroyed || suspended) return;
        suspended = true;
        finishDrag(false);
        cancelPendingWork();
        revealOnNextUpdate = false;
        hideThumbImmediately();
        rail.setVisibility(View.GONE);
    }

    void resume() {
        if (destroyed || !suspended) return;
        suspended = false;
        scheduleMetricsUpdate();
    }

    void destroy() {
        if (destroyed) return;
        finishDrag(false);
        destroyed = true;
        suspended = true;
        cancelPendingWork();
        rail.removeOnLayoutChangeListener(railLayoutChangeListener);
        rail.setOnTouchListener(null);
        rail.setClickable(false);
        setThumbPressed(false);
        hideThumbImmediately();
        rail.setVisibility(View.GONE);
    }

    /** Used by the PDF Activity to keep a thumb drag out of page-tap gesture routing. */
    boolean shouldHandleTouch(float rawX, float rawY) {
        if (!isOperational() || thumb.getAlpha() <= MIN_INTERACTIVE_ALPHA) return false;
        Metrics metrics = readMetrics();
        if (!metrics.isScrollable() || rail.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        rail.getLocationOnScreen(location);
        if (rawX < location[0] || rawX > location[0] + rail.getWidth()) return false;
        return isWithinThumbTouchBounds(rawY - location[1]);
    }

    private boolean onRailTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return startDrag(event);

            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                if (!source.isEnabled()) {
                    finishDrag(false);
                    hideRailAndThumb();
                    return true;
                }
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) {
                    finishDrag(true);
                    return true;
                }
                scrollDragToY(event.getY(pointerIndex));
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (!dragging) return false;
                if (event.getPointerId(event.getActionIndex()) == activePointerId) {
                    // End instead of silently switching fingers; this prevents a
                    // second pointer from jumping the thumb to an unrelated Y.
                    finishDrag(true);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                finishDrag(true);
                return true;

            default:
                return dragging;
        }
    }

    private boolean startDrag(@NonNull MotionEvent event) {
        if (!isOperational() || thumb.getAlpha() <= MIN_INTERACTIVE_ALPHA) return false;
        Metrics metrics = readMetrics();
        if (!metrics.isScrollable() || !isWithinThumbTouchBounds(event.getY())) return false;

        if (dragging) finishDrag(false);
        dragging = true;
        activePointerId = event.getPointerId(event.getActionIndex());
        dragRailHeight = Math.max(0, rail.getHeight());
        dragThumbHeight = updateThumbHeight(metrics.range, metrics.extent);
        dragTravel = Math.max(0f, dragRailHeight - dragThumbHeight);

        float thumbTop = clamp(thumb.getTranslationY(), 0f, dragTravel);
        // Keep the exact grab point, including the small vertical touch padding,
        // so a DOWN never recenters or nudges a large proportional thumb.
        dragGrabOffset = event.getY() - thumbTop;
        interceptedParent = rail.getParent();
        if (interceptedParent != null) {
            interceptedParent.requestDisallowInterceptTouchEvent(true);
        }
        source.onFastScrollStart();
        setThumbPressed(true);
        revealThumb();
        scrollDragToY(event.getY());
        return true;
    }

    private void finishDrag(boolean fadeAfterRelease) {
        boolean wasDragging = dragging;
        dragging = false;
        activePointerId = INVALID_POINTER_ID;
        if (interceptedParent != null) {
            interceptedParent.requestDisallowInterceptTouchEvent(false);
            interceptedParent = null;
        }
        setThumbPressed(false);
        if (wasDragging) {
            source.onFastScrollStop();
        }
        dragRailHeight = 0;
        dragThumbHeight = 0;
        dragTravel = 0f;
        dragGrabOffset = 0f;

        if (!wasDragging || !isOperational()) return;
        Metrics metrics = readMetrics();
        if (!metrics.isScrollable()) {
            hideRailAndThumb();
            return;
        }
        requestFrameUpdate();
        if (fadeAfterRelease) revealThumbTemporarily();
    }

    private void scrollDragToY(float y) {
        float fraction;
        float thumbTop;
        if (dragTravel <= 0f) {
            // In an extremely short split-screen rail the minimum thumb can fill
            // the rail. Keep it at Y=0, but still map the finger over the rail.
            thumbTop = 0f;
            fraction = dragRailHeight <= 0 ? 0f : clamp(y / dragRailHeight, 0f, 1f);
        } else {
            thumbTop = clamp(y - dragGrabOffset, 0f, dragTravel);
            fraction = thumbTop / dragTravel;
        }
        source.scrollToFraction(fraction);
        moveThumbTo(thumbTop);
    }

    private void requestFrameUpdate() {
        if (!isOperational() || updatePosted) return;
        updatePosted = true;
        rail.postOnAnimation(updateRunnable);
    }

    private void updateThumbFromSource() {
        if (!isOperational()) return;
        if (dragging) {
            revealOnNextUpdate = false;
            return;
        }

        boolean reveal = revealOnNextUpdate;
        revealOnNextUpdate = false;
        Metrics metrics = readMetrics();
        if (!metrics.isScrollable()) {
            hideRailAndThumb();
            return;
        }

        rail.setVisibility(View.VISIBLE);
        int thumbHeight = updateThumbHeight(metrics.range, metrics.extent);
        int travel = Math.max(0, rail.getHeight() - thumbHeight);
        long maxOffset = Math.max(1L, metrics.maxOffset());
        float top = travel <= 0
                ? 0f
                : (float) Math.min(travel,
                (metrics.offset / (double) maxOffset) * travel);
        moveThumbTo(top);
        if (reveal) revealThumbTemporarily();
    }

    private Metrics readMetrics() {
        if (!source.isEnabled()) return new Metrics(0L, 0L, 0L);
        return new Metrics(source.scrollRange(), source.scrollExtent(), source.scrollOffset());
    }

    private int updateThumbHeight(long range, long extent) {
        int railHeight = Math.max(0, rail.getHeight());
        if (railHeight <= 0) return Math.max(1, thumb.getHeight());

        int proportionalHeight = (int) Math.round(
                railHeight * (extent / (double) Math.max(1L, range)));
        float density = Math.max(0.1f, rail.getResources().getDisplayMetrics().density);
        int minimumHeight = Math.min(
                railHeight, Math.max(1, Math.round(MIN_THUMB_HEIGHT_DP * density)));
        int targetHeight = Math.max(
                minimumHeight, Math.min(railHeight, proportionalHeight));
        ViewGroup.LayoutParams layoutParams = thumb.getLayoutParams();
        if (layoutParams != null && layoutParams.height != targetHeight) {
            layoutParams.height = targetHeight;
            thumb.setLayoutParams(layoutParams);
        }
        return targetHeight;
    }

    private boolean isWithinThumbTouchBounds(float railY) {
        int thumbHeight = Math.max(1, thumb.getHeight());
        float top = thumb.getTranslationY();
        float density = Math.max(0.1f, rail.getResources().getDisplayMetrics().density);
        float padding = THUMB_VERTICAL_TOUCH_PADDING_DP * density;
        return railY >= top - padding && railY <= top + thumbHeight + padding;
    }

    private void moveThumbTo(float top) {
        thumb.setTranslationY(top);
    }

    private void setThumbPressed(boolean pressed) {
        rail.setPressed(pressed);
        thumb.setPressed(pressed);
    }

    private void revealThumb() {
        if (!isOperational()) return;
        rail.removeCallbacks(fadeRunnable);
        thumb.animate().cancel();
        thumb.setAlpha(1f);
    }

    private void revealThumbTemporarily() {
        revealThumb();
        if (!dragging && isOperational()) {
            rail.postDelayed(fadeRunnable, THUMB_FADE_DELAY_MS);
        }
    }

    private void fadeThumbOut() {
        if (!isOperational() || dragging || thumb.getAlpha() <= 0f) return;
        thumb.animate()
                .alpha(0f)
                .setDuration(THUMB_FADE_DURATION_MS)
                .start();
    }

    private void hideRailAndThumb() {
        hideThumbImmediately();
        rail.setVisibility(View.GONE);
    }

    private void hideThumbImmediately() {
        rail.removeCallbacks(fadeRunnable);
        thumb.animate().cancel();
        thumb.setAlpha(0f);
    }

    private void cancelPendingWork() {
        rail.removeCallbacks(updateRunnable);
        rail.removeCallbacks(settledUpdateRunnable);
        rail.removeCallbacks(lateSettledUpdateRunnable);
        rail.removeCallbacks(fadeRunnable);
        updatePosted = false;
    }

    private boolean isOperational() {
        return installed && !destroyed && !suspended;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
