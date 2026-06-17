package com.readwide.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.Nullable;

/**
 * Single-page PDF view driven entirely by an image {@link Matrix}.
 *
 * <p>This is the stage-1 replacement for the old HorizontalScrollView/ScrollView/
 * ImageView zoom stack. All zoom and pan state lives in ONE matrix, so the focus
 * point of a double-tap or pinch maps directly to {@link Matrix#postScale} pivot
 * arguments. There is no separate scroll position, no pivot/scrollTo coordinate
 * mismatch, and no alpha-hidden reveal timing to get wrong — the classes of bug
 * that plagued the old approach simply cannot occur here.
 *
 * <p>Sharpness: the view displays a "fit" bitmap (the page at fit-to-width/height
 * size, supersampled) and scales it up with the matrix while the user interacts.
 * When motion settles, it asks the host (via {@link SharpenRequestListener}) to
 * render just the visible region at the current scale; the host hands back a
 * crisp patch through {@link #setSharpenPatch}.
 */
public class PdfPageView extends View {

    /** Host renders the visible region at high resolution when asked. */
    public interface SharpenRequestListener {
        /**
         * @param normLeft   visible region left, as a fraction of page width [0..1]
         * @param normTop    visible region top, as a fraction of page height [0..1]
         * @param normRight  visible region right, as a fraction of page width [0..1]
         * @param normBottom visible region bottom, as a fraction of page height [0..1]
         * @param scale      on-screen pixels per page-point (display scale)
         */
        void onSharpenRequested(float normLeft, float normTop,
                                float normRight, float normBottom, float scale);
    }

    /** Single tap that was not part of a zoom/pan. Host decides: page-turn zone or chrome toggle. */
    public interface TapListener { void onSingleTap(float x, float y); }

    /** Horizontal swipe at the page edge — host turns the page. -1 prev, +1 next. */
    public interface PageSwipeListener { boolean onPageSwipe(int direction); }

    /** Host reports whether a tap at x/y falls in a page-turn zone (suppress double-tap zoom there). */
    public interface TapZoneQuery { boolean isPageTurnZone(float x, float y); }

    private Bitmap fitBitmap;          // base fit bitmap (supersampled)
    private float supersample = 1f;    // fitBitmap is this much bigger than fit size
    private int pageWidthPts;          // original PDF page width  (points)
    private int pageHeightPts;         // original PDF page height (points)

    // Sharp patch overlay (visible region rendered crisp), in page-normalized rect.
    @Nullable private Bitmap sharpPatch;
    private final RectF sharpPatchPageRect = new RectF(); // normalized [0..1]

    private final Matrix matrix = new Matrix();
    private final Matrix tmpMatrix = new Matrix();
    private final float[] matrixVals = new float[9];

    private float minScale = 1f;       // fit (page fully visible)
    private float maxScale = 4.5f;
    private float fitScale = 1f;       // matrix scale that yields the fit size

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller scroller;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean scaling = false;
    private boolean dragging = false;
    private boolean handledByTapUp = false;

    @Nullable private SharpenRequestListener sharpenListener;
    @Nullable private TapListener tapListener;
    @Nullable private PageSwipeListener pageSwipeListener;
    @Nullable private TapZoneQuery tapZoneQuery;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private final Runnable sharpenRunnable = this::requestSharpenNow;

    public PdfPageView(Context c) { super(c); init(c); }
    public PdfPageView(Context c, @Nullable AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        scroller = new OverScroller(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleListener());
        gestureDetector = new GestureDetector(c, new GestureListener());
        setClickable(true);
        setFocusable(true);
    }

    public void setSharpenRequestListener(@Nullable SharpenRequestListener l) { sharpenListener = l; }
    public void setTapListener(@Nullable TapListener l) { tapListener = l; }
    public void setPageSwipeListener(@Nullable PageSwipeListener l) { pageSwipeListener = l; }
    public void setTapZoneQuery(@Nullable TapZoneQuery q) { tapZoneQuery = q; }

    public void setMaxScale(float m) { maxScale = Math.max(1.5f, m); }

    /**
     * Provide the base fit bitmap for a page.
     *
     * @param bmp          supersampled fit bitmap
     * @param supersample  how much bigger bmp is than the on-screen fit size
     * @param pageWidthPts original page width in points
     * @param pageHeightPts original page height in points
     * @param resetView    true to reset zoom/pan (new page); false to keep state
     */
    public void setFitBitmap(Bitmap bmp, float supersample, int pageWidthPts,
                             int pageHeightPts, boolean resetView) {
        this.fitBitmap = bmp;
        this.supersample = Math.max(0.01f, supersample);
        this.pageWidthPts = Math.max(1, pageWidthPts);
        this.pageHeightPts = Math.max(1, pageHeightPts);
        clearSharpPatch();
        if (resetView) {
            post(this::resetToFit);
        } else if (getWidth() > 0) {
            resetToFit();
        }
        invalidate();
    }

    private void clearSharpPatch() {
        if (sharpPatch != null && !sharpPatch.isRecycled()) sharpPatch.recycle();
        sharpPatch = null;
        sharpPatchPageRect.setEmpty();
    }

    /**
     * Drop references to the page bitmap without recycling it (the host owns the
     * fit bitmap and its cache). Call before the host recycles the current bitmap
     * — e.g. on a mode switch — so onDraw can't touch a freed bitmap.
     */
    public void detachBitmaps() {
        handler.removeCallbacks(sharpenRunnable);
        clearSharpPatch();
        fitBitmap = null;
        invalidate();
    }

    /** Compute the fit matrix: page fully visible, horizontally centered, vertically centered. */
    private void resetToFit() {
        if (fitBitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float vw = getWidth();
        float vh = getHeight();
        // The fit bitmap is supersampled; its on-screen fit size is bmp/supersample.
        float bmpW = fitBitmap.getWidth();
        float bmpH = fitBitmap.getHeight();
        // Scale that maps the supersampled bitmap to the fit size on screen.
        float fitW = bmpW / supersample;
        float fitH = bmpH / supersample;
        // Fit fully inside the viewport (both width and height).
        float s = Math.min(vw / fitW, vh / fitH);
        // Matrix scale applied to the supersampled bitmap to reach the fit size.
        float scale = s / supersample;
        fitScale = scale;
        minScale = scale;
        float drawnW = bmpW * scale;
        float drawnH = bmpH * scale;
        float dx = (vw - drawnW) / 2f;
        float dy = (vh - drawnH) / 2f;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        clampMatrix();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetToFit();
    }

    private float currentScale() {
        matrix.getValues(matrixVals);
        return matrixVals[Matrix.MSCALE_X];
    }

    /** Keep the page within the viewport: no empty gutters, center axis if smaller. */
    private void clampMatrix() {
        if (fitBitmap == null) return;
        matrix.getValues(matrixVals);
        float scale = matrixVals[Matrix.MSCALE_X];
        float tx = matrixVals[Matrix.MTRANS_X];
        float ty = matrixVals[Matrix.MTRANS_Y];
        float drawnW = fitBitmap.getWidth() * scale;
        float drawnH = fitBitmap.getHeight() * scale;
        float vw = getWidth();
        float vh = getHeight();

        float newTx = tx, newTy = ty;
        if (drawnW <= vw) {
            newTx = (vw - drawnW) / 2f;            // center horizontally
        } else {
            if (tx > 0) newTx = 0;
            else if (tx < vw - drawnW) newTx = vw - drawnW;
        }
        if (drawnH <= vh) {
            newTy = (vh - drawnH) / 2f;            // center vertically
        } else {
            if (ty > 0) newTy = 0;
            else if (ty < vh - drawnH) newTy = vh - drawnH;
        }
        if (newTx != tx || newTy != ty) {
            matrix.postTranslate(newTx - tx, newTy - ty);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (fitBitmap == null || fitBitmap.isRecycled()) return;
        canvas.drawBitmap(fitBitmap, matrix, bitmapPaint);

        // Draw the crisp patch on top, positioned by the same matrix mapped from
        // the page-normalized rect to current bitmap space.
        if (sharpPatch != null && !sharpPatch.isRecycled() && !sharpPatchPageRect.isEmpty()) {
            float bmpW = fitBitmap.getWidth();
            float bmpH = fitBitmap.getHeight();
            RectF dst = new RectF(
                    sharpPatchPageRect.left * bmpW,
                    sharpPatchPageRect.top * bmpH,
                    sharpPatchPageRect.right * bmpW,
                    sharpPatchPageRect.bottom * bmpH);
            matrix.mapRect(dst);
            canvas.drawBitmap(sharpPatch, null, dst, bitmapPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean r = scaleDetector.onTouchEvent(event);
        r = gestureDetector.onTouchEvent(event) || r;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeStartX = event.getX();
                swipeStartY = event.getY();
                swipeTracking = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                swipeTracking = false; // multi-touch => pinch, not a swipe
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeTracking && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    maybePageSwipe(event);
                }
                swipeTracking = false;
                if (dragging) {
                    dragging = false;
                    scheduleSharpen();
                }
                break;
        }
        return true;
    }

    private float swipeStartX, swipeStartY;
    private boolean swipeTracking = false;

    /** A horizontal drag, when the page can't pan horizontally, turns the page. */
    private void maybePageSwipe(MotionEvent up) {
        if (pageSwipeListener == null || fitBitmap == null || scaling) return;
        float scale = currentScale();
        float drawnW = fitBitmap.getWidth() * scale;
        // Only when the page is NOT wider than the viewport (i.e. not zoomed in
        // horizontally) — otherwise a horizontal drag is a pan, not a page turn.
        if (drawnW > getWidth() + 1) return;
        float dx = up.getX() - swipeStartX;
        float dy = up.getY() - swipeStartY;
        float threshold = getWidth() * 0.18f;
        if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.3f) {
            int direction = dx < 0 ? +1 : -1; // swipe left => next page
            pageSwipeListener.onPageSwipe(direction);
        }
    }

    /** Map a viewport point to a page-normalized point [0..1]. */
    private void scheduleSharpen() {
        handler.removeCallbacks(sharpenRunnable);
        handler.postDelayed(sharpenRunnable, 120);
    }

    private void requestSharpenNow() {
        if (sharpenListener == null || fitBitmap == null || getWidth() == 0) return;
        // Invert matrix to find which part of the bitmap is visible.
        if (!matrix.invert(tmpMatrix)) return;
        float[] pts = {0, 0, getWidth(), getHeight()};
        tmpMatrix.mapPoints(pts);
        float bmpW = fitBitmap.getWidth();
        float bmpH = fitBitmap.getHeight();
        float nl = clamp01(pts[0] / bmpW);
        float nt = clamp01(pts[1] / bmpH);
        float nr = clamp01(pts[2] / bmpW);
        float nb = clamp01(pts[3] / bmpH);
        if (nr - nl < 0.001f || nb - nt < 0.001f) return;
        // Screen pixels per page-point at the current zoom. The page is drawn at
        // width = bmpW * currentScale on screen and spans pageWidthPts points, so
        // px-per-point = bmpW * currentScale / pageWidthPts. (Passing
        // currentScale*supersample is wrong and under-samples the sharp render.)
        float pxPerPoint = (pageWidthPts > 0)
                ? (bmpW * currentScale()) / pageWidthPts
                : currentScale() * supersample;
        sharpenListener.onSharpenRequested(nl, nt, nr, nb, pxPerPoint);
    }

    /**
     * Host supplies a crisp bitmap for the previously requested visible region.
     * The rect is page-normalized [0..1] matching the request.
     */
    public void setSharpenPatch(@Nullable Bitmap patch, float normLeft, float normTop,
                                float normRight, float normBottom) {
        if (sharpPatch != null && sharpPatch != patch && !sharpPatch.isRecycled()) {
            sharpPatch.recycle();
        }
        sharpPatch = patch;
        sharpPatchPageRect.set(normLeft, normTop, normRight, normBottom);
        invalidate();
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            matrix.getValues(matrixVals);
            float tx = matrixVals[Matrix.MTRANS_X];
            float ty = matrixVals[Matrix.MTRANS_Y];
            float nx = scroller.getCurrX();
            float ny = scroller.getCurrY();
            matrix.postTranslate(nx - tx, ny - ty);
            clampMatrix();
            invalidate();
            if (scroller.isFinished()) scheduleSharpen();
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector d) {
            scaling = true;
            scroller.forceFinished(true);
            handler.removeCallbacks(sharpenRunnable);
            clearSharpPatch(); // crisp patch is stale once we start zooming
            return true;
        }

        @Override public boolean onScale(ScaleGestureDetector d) {
            float factor = d.getScaleFactor();
            float scale = currentScale();
            float target = scale * factor;
            float clamped = Math.max(minScale, Math.min(maxScale, target));
            float applied = (scale != 0) ? clamped / scale : 1f;
            // Pivot at the gesture focus — this is what keeps the pinch anchored.
            matrix.postScale(applied, applied, d.getFocusX(), d.getFocusY());
            clampMatrix();
            invalidate();
            return true;
        }

        @Override public void onScaleEnd(ScaleGestureDetector d) {
            scaling = false;
            scheduleSharpen();
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            scroller.forceFinished(true);
            return true;
        }

        @Override public boolean onSingleTapUp(MotionEvent e) {
            // Page-turn zones suppress double-tap zoom, so there's no need to wait
            // out the double-tap timeout there — act immediately for snappy paging
            // that matches swipe responsiveness.
            if (tapZoneQuery != null && tapZoneQuery.isPageTurnZone(e.getX(), e.getY())) {
                handledByTapUp = true;
                if (tapListener != null) tapListener.onSingleTap(e.getX(), e.getY());
                return true;
            }
            return false;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (handledByTapUp) { handledByTapUp = false; return true; }
            if (tapListener != null) tapListener.onSingleTap(e.getX(), e.getY());
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            handledByTapUp = false;
            if (fitBitmap == null) return false;
            // In a page-turn zone, don't zoom — turn the page instead (a fast
            // double tap to flip pages must not be captured as a zoom toggle).
            if (tapZoneQuery != null && tapZoneQuery.isPageTurnZone(e.getX(), e.getY())) {
                if (tapListener != null) tapListener.onSingleTap(e.getX(), e.getY());
                return true;
            }
            clearSharpPatch();
            float scale = currentScale();
            boolean zoomedIn = scale > minScale * 1.08f;
            float targetScale = zoomedIn ? minScale : Math.min(maxScale, minScale * 2.5f);
            float factor = (scale != 0) ? targetScale / scale : 1f;
            matrix.postScale(factor, factor, e.getX(), e.getY());
            clampMatrix();
            invalidate();
            scheduleSharpen();
            return true;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            // Never treat a multi-touch (pinch) as a pan. scaling alone can miss the
            // brief moment after the 2nd finger lands but before onScaleBegin fires,
            // and that leaked onScroll yanks the page to a wrong position.
            if (scaling || (e2 != null && e2.getPointerCount() > 1)
                    || scaleDetector.isInProgress()) {
                return false;
            }
            float scale = currentScale();
            float drawnW = fitBitmap != null ? fitBitmap.getWidth() * scale : 0;
            float drawnH = fitBitmap != null ? fitBitmap.getHeight() * scale : 0;
            boolean canPanX = drawnW > getWidth() + 1;
            boolean canPanY = drawnH > getHeight() + 1;

            // Page-turn: if the page isn't horizontally pannable and the drag is
            // mostly horizontal, let the host turn the page on release. (Stage 2
            // wires this fully; here we just report it.)
            if (!canPanX && Math.abs(dx) > Math.abs(dy)) {
                return false; // host handles swipe via its own detector (stage 2)
            }
            dragging = true;
            handler.removeCallbacks(sharpenRunnable);
            float appliedDx = canPanX ? -dx : 0;
            float appliedDy = canPanY ? -dy : 0;
            matrix.postTranslate(appliedDx, appliedDy);
            clampMatrix();
            invalidate();
            return true;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (fitBitmap == null || scaling) return false;
            matrix.getValues(matrixVals);
            float scale = matrixVals[Matrix.MSCALE_X];
            float tx = matrixVals[Matrix.MTRANS_X];
            float ty = matrixVals[Matrix.MTRANS_Y];
            float drawnW = fitBitmap.getWidth() * scale;
            float drawnH = fitBitmap.getHeight() * scale;
            int minX = Math.round(Math.min(0, getWidth() - drawnW));
            int minY = Math.round(Math.min(0, getHeight() - drawnH));
            int maxX = 0, maxY = 0;
            if (drawnW <= getWidth()) { minX = maxX = Math.round((getWidth() - drawnW) / 2f); }
            if (drawnH <= getHeight()) { minY = maxY = Math.round((getHeight() - drawnH) / 2f); }
            scroller.forceFinished(true);
            scroller.fling(Math.round(tx), Math.round(ty),
                    Math.round(vx), Math.round(vy),
                    minX, maxX, minY, maxY);
            postInvalidateOnAnimation();
            return true;
        }
    }
}
