package com.textview.reader.view;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/** Lightweight pan/zoom/fling image surface for ImageReaderActivity. */
public class ZoomImageView extends AppCompatImageView {
    private static final float DOUBLE_TAP_BASE_EPSILON = 0.005f;

    public interface Callbacks {
        void onSingleTap();
        void onSwipeLeft();
        void onSwipeRight();
        void onZoomRequested();
    }

    private final Matrix matrix = new Matrix();
    private final RectF imageRect = new RectF();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
    private final int touchSlop;
    private float minScale = 1f;
    private float fitScale = 1f;
    private float defaultScale = 1f;
    private float maxScale = 5f;
    private boolean smallImageWidthFillDefault;
    private float lastX;
    private float lastY;
    private int lastScrollerX;
    private int lastScrollerY;
    private boolean dragging;
    private boolean panGestureStarted;
    private int imageWidth;
    private int imageHeight;
    private Callbacks callbacks;

    public ZoomImageView(@NonNull android.content.Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setAdjustViewBounds(false);
        scroller = new OverScroller(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                stopImageFling();
                if (callbacks != null) callbacks.onZoomRequested();
                disallowParentIntercept(true);
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float current = getCurrentScale();
                if (current <= 0f) return false;
                float requested = detector.getScaleFactor();
                float target = current * requested;
                if (target < minScale) requested = minScale / current;
                if (target > maxScale) requested = maxScale / current;
                matrix.postScale(requested, requested, detector.getFocusX(), detector.getFocusY());
                applyBounds();
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                applyBounds();
                if (!canPanCurrentImage()) disallowParentIntercept(false);
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (callbacks != null) callbacks.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                stopImageFling();
                if (willDoubleTapZoomIn() && callbacks != null) callbacks.onZoomRequested();
                toggleZoom(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && callbacks != null) {
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    if (isHorizontalPageSwipe(dx, dy) && !canPanHorizontally()) {
                        if (dx < 0) callbacks.onSwipeLeft(); else callbacks.onSwipeRight();
                        return true;
                    }
                }
                if (canPanCurrentImage()) {
                    return startImageFling(velocityX, velocityY);
                }
                if (e1 == null || callbacks == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (!isHorizontalPageSwipe(dx, dy)) return false;
                if (dx < 0) callbacks.onSwipeLeft(); else callbacks.onSwipeRight();
                return true;
            }
        });
    }

    public void setCallbacks(@Nullable Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setImageBitmapReady(@NonNull Bitmap nextBitmap) {
        setImageBitmapReady(nextBitmap, false);
    }

    public void setImageBitmapReady(@NonNull Bitmap nextBitmap, boolean preserveViewport) {
        if (preserveViewport && imageWidth > 0 && imageHeight > 0 && getWidth() > 0 && getHeight() > 0) {
            setImageBitmapPreserveViewport(nextBitmap);
            return;
        }
        stopImageFling();
        imageWidth = nextBitmap.getWidth();
        imageHeight = nextBitmap.getHeight();
        setImageBitmap(nextBitmap);
        configureBaseMatrixWhenReady();
    }

    public void setImageDrawableReady(@NonNull Drawable nextDrawable) {
        stopImageFling();
        imageWidth = Math.max(1, nextDrawable.getIntrinsicWidth());
        imageHeight = Math.max(1, nextDrawable.getIntrinsicHeight());
        setImageDrawable(nextDrawable);
        if (nextDrawable instanceof AnimatedImageDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ((AnimatedImageDrawable) nextDrawable).start();
        }
        configureBaseMatrixWhenReady();
    }

    private void configureBaseMatrixWhenReady() {
        if (getWidth() > 0 && getHeight() > 0 && getContentWidth() > 0 && getContentHeight() > 0) {
            configureBaseMatrix();
        } else {
            post(this::configureBaseMatrix);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        configureBaseMatrix();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopImageFling();
                lastX = event.getX();
                lastY = event.getY();
                dragging = false;
                panGestureStarted = false;
                disallowParentIntercept(canPanCurrentImage());
                return true;
            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                stopImageFling();
                dragging = false;
                panGestureStarted = false;
                disallowParentIntercept(true);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                updateLastTouchFromRemainingPointer(event);
                dragging = false;
                panGestureStarted = false;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                panGestureStarted = false;
                if (!canPanCurrentImage()) disallowParentIntercept(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public void computeScroll() {
        if (!scroller.computeScrollOffset()) return;
        int currentX = scroller.getCurrX();
        int currentY = scroller.getCurrY();
        matrix.postTranslate(lastScrollerX - currentX, lastScrollerY - currentY);
        lastScrollerX = currentX;
        lastScrollerY = currentY;
        applyBounds();
        postInvalidateOnAnimation();
    }

    public void configureBaseMatrix() {
        stopImageFling();
        if (imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        int contentW = getContentWidth();
        int contentH = getContentHeight();
        if (contentW <= 0 || contentH <= 0) return;
        float bw = imageWidth;
        float bh = imageHeight;
        if (bw <= 0 || bh <= 0) return;
        matrix.reset();
        fitScale = Math.min(contentW / bw, contentH / bh);
        if (fitScale <= 0f) fitScale = 1f;
        float fitWidthScale = contentW / bw;

        // Small images now open in a width-filled reading state instead of the
        // old original-size state. Keep the true original size as minScale so
        // double tap and pinch can still return to 1:1 without treating the
        // initial width-filled view as an extra zoomed-in state.
        smallImageWidthFillDefault = bw <= contentW && bh <= contentH && fitWidthScale > 1f;
        minScale = smallImageWidthFillDefault ? 1f : Math.min(1f, fitScale);
        defaultScale = smallImageWidthFillDefault ? fitWidthScale : minScale;
        maxScale = Math.max(Math.max(Math.max(minScale, fitScale), defaultScale) * 5f, 5f);
        float dx = getPaddingLeft() + (contentW - bw * defaultScale) * 0.5f;
        float dy = getPaddingTop() + (contentH - bh * defaultScale) * 0.5f;
        matrix.postScale(defaultScale, defaultScale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    private void handleMove(@NonNull MotionEvent event) {
        if (scaleDetector.isInProgress() || event.getPointerCount() != 1) return;
        float x = event.getX();
        float y = event.getY();
        float dx = x - lastX;
        float dy = y - lastY;
        if (!panGestureStarted) {
            float distanceSquared = dx * dx + dy * dy;
            if (distanceSquared < touchSlop * touchSlop) return;
            panGestureStarted = true;
            dragging = canPanCurrentImage();
        }
        if (dragging && canPanCurrentImage()) {
            disallowParentIntercept(true);
            matrix.postTranslate(dx, dy);
            applyBounds();
        }
        lastX = x;
        lastY = y;
    }

    private void updateLastTouchFromRemainingPointer(@NonNull MotionEvent event) {
        int pointerIndex = event.getActionIndex() == 0 ? 1 : 0;
        if (pointerIndex >= event.getPointerCount()) pointerIndex = 0;
        lastX = event.getX(pointerIndex);
        lastY = event.getY(pointerIndex);
    }

    private void toggleZoom(float focusX, float focusY) {
        if (imageWidth <= 0 || imageHeight <= 0) return;
        float current = getCurrentScale();
        if (current <= 0f) return;
        float target = getDoubleTapTargetScale(current);
        target = Math.min(maxScale, Math.max(minScale, target));
        float factor = target / current;
        matrix.postScale(factor, factor, focusX, focusY);
        applyBounds();
    }

    private boolean willDoubleTapZoomIn() {
        return willDoubleTapZoomIn(getCurrentScale());
    }

    private boolean willDoubleTapZoomIn(float current) {
        if (smallImageWidthFillDefault) {
            return current <= minScale + getDoubleTapBaseEpsilon();
        }
        return current <= defaultScale + getDoubleTapBaseEpsilon();
    }

    private float getDoubleTapTargetScale(float current) {
        if (smallImageWidthFillDefault) {
            // Small image toggle is intentionally reversed from the previous build:
            // initial/default = width-filled, double tap = original 1:1, next double
            // tap = width-filled again.
            return current <= minScale + getDoubleTapBaseEpsilon() ? defaultScale : minScale;
        }
        return willDoubleTapZoomIn(current) ? getDoubleTapZoomScale() : defaultScale;
    }

    private float getDoubleTapZoomScale() {
        if (smallImageWidthFillDefault) return defaultScale;
        if (fitScale > minScale + getDoubleTapBaseEpsilon()) {
            return fitScale;
        }
        int contentW = getContentWidth();
        float fitWidthScale = contentW > 0 ? contentW / (float) imageWidth : minScale;
        return Math.max(minScale * 2.5f, fitWidthScale);
    }

    private float getDoubleTapBaseEpsilon() {
        return Math.max(DOUBLE_TAP_BASE_EPSILON, minScale * DOUBLE_TAP_BASE_EPSILON);
    }

    private boolean startImageFling(float velocityX, float velocityY) {
        if (imageWidth <= 0 || imageHeight <= 0 || !canPanCurrentImage()) return false;
        mapImageRect();
        int contentW = getContentWidth();
        int contentH = getContentHeight();
        if (contentW <= 0 || contentH <= 0) return false;

        float leftBound = getPaddingLeft();
        float topBound = getPaddingTop();
        float rightBound = leftBound + contentW;
        float bottomBound = topBound + contentH;

        boolean canPanX = imageRect.width() > contentW + 1f;
        boolean canPanY = imageRect.height() > contentH + 1f;
        if (!canPanX && !canPanY) return false;

        int startX = Math.round(-imageRect.left);
        int startY = Math.round(-imageRect.top);
        int minX = canPanX ? Math.round(-leftBound) : startX;
        int maxX = canPanX ? Math.round(-(rightBound - imageRect.width())) : startX;
        int minY = canPanY ? Math.round(-topBound) : startY;
        int maxY = canPanY ? Math.round(-(bottomBound - imageRect.height())) : startY;
        startX = clamp(startX, minX, maxX);
        startY = clamp(startY, minY, maxY);

        lastScrollerX = startX;
        lastScrollerY = startY;
        scroller.fling(
                startX,
                startY,
                canPanX ? Math.round(-velocityX) : 0,
                canPanY ? Math.round(-velocityY) : 0,
                minX,
                maxX,
                minY,
                maxY);
        postInvalidateOnAnimation();
        return true;
    }


    private void setImageBitmapPreserveViewport(@NonNull Bitmap nextBitmap) {
        stopImageFling();
        float oldBaseScale = defaultScale <= 0f ? (minScale <= 0f ? 1f : minScale) : defaultScale;
        float oldScaleRatio = getCurrentScale() / oldBaseScale;
        if (Float.isNaN(oldScaleRatio) || Float.isInfinite(oldScaleRatio) || oldScaleRatio <= 0f) oldScaleRatio = 1f;
        float normX = 0.5f;
        float normY = 0.5f;
        Matrix inverse = new Matrix();
        if (matrix.invert(inverse)) {
            float[] center = new float[] {
                    getPaddingLeft() + getContentWidth() * 0.5f,
                    getPaddingTop() + getContentHeight() * 0.5f
            };
            inverse.mapPoints(center);
            normX = clamp01(center[0] / Math.max(1f, imageWidth));
            normY = clamp01(center[1] / Math.max(1f, imageHeight));
        }

        imageWidth = nextBitmap.getWidth();
        imageHeight = nextBitmap.getHeight();
        setImageBitmap(nextBitmap);
        configureBaseMatrix();

        float current = getCurrentScale();
        float target = Math.max(minScale, Math.min(maxScale, defaultScale * oldScaleRatio));
        if (current > 0f && Math.abs(target - current) > 0.001f) {
            float cx = getPaddingLeft() + getContentWidth() * 0.5f;
            float cy = getPaddingTop() + getContentHeight() * 0.5f;
            matrix.postScale(target / current, target / current, cx, cy);
        }
        float[] mapped = new float[] { normX * imageWidth, normY * imageHeight };
        matrix.mapPoints(mapped);
        float desiredX = getPaddingLeft() + getContentWidth() * 0.5f;
        float desiredY = getPaddingTop() + getContentHeight() * 0.5f;
        matrix.postTranslate(desiredX - mapped[0], desiredY - mapped[1]);
        applyBounds();
        postInvalidateOnAnimation();
    }

    private float getCurrentScale() {
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        return scale == 0f ? 1f : scale;
    }

    private boolean isZoomed() {
        return getCurrentScale() > defaultScale * 1.03f;
    }

    private boolean canPanCurrentImage() {
        if (imageWidth <= 0 || imageHeight <= 0) return false;
        int contentW = getContentWidth();
        int contentH = getContentHeight();
        if (contentW <= 0 || contentH <= 0) return false;
        mapImageRect();
        return imageRect.width() > contentW + 1f || imageRect.height() > contentH + 1f;
    }

    private boolean canPanHorizontally() {
        if (imageWidth <= 0 || imageHeight <= 0) return false;
        int contentW = getContentWidth();
        if (contentW <= 0) return false;
        mapImageRect();
        return imageRect.width() > contentW + 1f;
    }

    private boolean isHorizontalPageSwipe(float dx, float dy) {
        float threshold = Math.max(72f, getResources().getDisplayMetrics().density * 56f);
        return Math.abs(dx) >= threshold && Math.abs(dx) >= Math.abs(dy) * 1.25f;
    }

    private void applyBounds() {
        if (imageWidth <= 0 || imageHeight <= 0) return;
        int contentW = getContentWidth();
        int contentH = getContentHeight();
        if (contentW <= 0 || contentH <= 0) return;
        mapImageRect();

        float leftBound = getPaddingLeft();
        float topBound = getPaddingTop();
        float rightBound = leftBound + contentW;
        float bottomBound = topBound + contentH;
        float dx = 0f;
        float dy = 0f;

        if (imageRect.width() <= contentW) {
            dx = leftBound + (contentW - imageRect.width()) * 0.5f - imageRect.left;
        } else if (imageRect.left > leftBound) {
            dx = leftBound - imageRect.left;
        } else if (imageRect.right < rightBound) {
            dx = rightBound - imageRect.right;
        }

        if (imageRect.height() <= contentH) {
            dy = topBound + (contentH - imageRect.height()) * 0.5f - imageRect.top;
        } else if (imageRect.top > topBound) {
            dy = topBound - imageRect.top;
        } else if (imageRect.bottom < bottomBound) {
            dy = bottomBound - imageRect.bottom;
        }

        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    private void mapImageRect() {
        imageRect.set(0, 0, imageWidth, imageHeight);
        matrix.mapRect(imageRect);
    }

    private int getContentWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    private int getContentHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    private void stopImageFling() {
        if (!scroller.isFinished()) scroller.forceFinished(true);
    }

    private void disallowParentIntercept(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    private int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
