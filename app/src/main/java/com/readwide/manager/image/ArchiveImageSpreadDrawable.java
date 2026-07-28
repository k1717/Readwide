package com.readwide.manager.image;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Allocation-free two-page comic surface. Both bitmaps remain owned by the
 * image reader's decoded-page cache; this drawable only paints them.
 */
public final class ArchiveImageSpreadDrawable extends Drawable {
    private static final float GAP_TO_HEIGHT = 0.008f;

    @NonNull private final Bitmap first;
    @NonNull private final Bitmap second;
    private final boolean rtl;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int intrinsicHeight;
    private final int firstWidthAtHeight;
    private final int secondWidthAtHeight;
    private final int intrinsicGap;
    private final int intrinsicWidth;

    public ArchiveImageSpreadDrawable(@NonNull Bitmap first,
                                      @NonNull Bitmap second,
                                      boolean rtl) {
        this.first = first;
        this.second = second;
        this.rtl = rtl;
        intrinsicHeight = Math.max(1, Math.max(first.getHeight(), second.getHeight()));
        firstWidthAtHeight = scaledWidth(first, intrinsicHeight);
        secondWidthAtHeight = scaledWidth(second, intrinsicHeight);
        intrinsicGap = Math.max(1, Math.round(intrinsicHeight * GAP_TO_HEIGHT));
        intrinsicWidth = Math.max(1, firstWidthAtHeight + intrinsicGap + secondWidthAtHeight);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || first.isRecycled() || second.isRecycled()) return;
        canvas.drawColor(Color.BLACK);
        float sx = bounds.width() / (float) intrinsicWidth;
        float sy = bounds.height() / (float) intrinsicHeight;
        int gap = Math.max(1, Math.round(intrinsicGap * sx));
        int firstW = Math.max(1, Math.round(firstWidthAtHeight * sx));
        int secondW = Math.max(1, bounds.width() - gap - firstW);
        Bitmap physicalLeft = rtl ? second : first;
        Bitmap physicalRight = rtl ? first : second;
        int physicalLeftW = rtl ? secondW : firstW;
        Rect leftDst = new Rect(bounds.left, bounds.top,
                bounds.left + physicalLeftW, bounds.bottom);
        Rect rightDst = new Rect(leftDst.right + gap, bounds.top,
                bounds.right, bounds.bottom);
        canvas.drawBitmap(physicalLeft, null, leftDst, paint);
        canvas.drawBitmap(physicalRight, null, rightDst, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    @Override public int getIntrinsicWidth() { return intrinsicWidth; }
    @Override public int getIntrinsicHeight() { return intrinsicHeight; }

    private static int scaledWidth(@NonNull Bitmap bitmap, int targetHeight) {
        return Math.max(1, Math.round(bitmap.getWidth()
                * (targetHeight / (float) Math.max(1, bitmap.getHeight()))));
    }
}
