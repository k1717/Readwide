package com.readwide.manager.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * Keeps the compact page-counter strip geometry untouched while nudging only the text.
 */
public class PageCounterTextView extends AppCompatTextView {
    private final float textOffsetYPx;

    public PageCounterTextView(Context context) {
        this(context, null);
    }

    public PageCounterTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public PageCounterTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        textOffsetYPx = context.getResources().getDisplayMetrics().density * 1f;
        setIncludeFontPadding(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(0f, textOffsetYPx);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }
}
