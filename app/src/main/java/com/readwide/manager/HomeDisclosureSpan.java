package com.readwide.manager;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.style.ReplacementSpan;

/** Inline vector marker: reserves width without enlarging the title's line metrics. */
final class HomeDisclosureSpan extends ReplacementSpan {
    private final String titleText;
    private final boolean expanded;

    HomeDisclosureSpan(String titleText, boolean expanded) {
        this.titleText = titleText;
        this.expanded = expanded;
    }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                 Paint.FontMetricsInt metrics) {
        // Leave the title's ascent/descent alone so both labels stay centered in 42dp.
        return (int) Math.ceil(paint.getTextSize() * 0.8f);
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                               float x, int top, int baseline, int bottom, Paint paint) {
        Rect titleBounds = new Rect();
        paint.getTextBounds(titleText, 0, titleText.length(), titleBounds);
        float centerY = baseline + (titleBounds.top + titleBounds.bottom) / 2f;
        float size = paint.getTextSize() * 0.6f;
        float left = x + (getSize(paint, text, start, end, null) - size) / 2f;
        float upper = centerY - size / 2f;
        Path triangle = new Path();
        if (expanded) {
            triangle.moveTo(left, upper);
            triangle.lineTo(left + size, upper);
            triangle.lineTo(left + size / 2f, upper + size);
        } else {
            triangle.moveTo(left, upper);
            triangle.lineTo(left + size, centerY);
            triangle.lineTo(left, upper + size);
        }
        triangle.close();
        Paint markerPaint = new Paint(paint);
        markerPaint.setAntiAlias(true);
        markerPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(triangle, markerPaint);
    }
}
