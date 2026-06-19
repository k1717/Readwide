package com.readwide.manager.view;

import android.content.Context;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * A TextView that, when its text would be clipped, rewrites it as
 * "head…<tailContext><extension>" so the file extension (and a little context
 * before it) stays visible across the allowed lines.
 *
 * The key property is that truncation is computed synchronously during layout
 * (in onMeasure, once the width is known) and applied before the next draw, so
 * there is no full-text -> clipped-text flash on bind/re-entry the way a
 * post()-deferred rewrite produced.
 */
public class ExtensionEllipsisTextView extends AppCompatTextView {
    private CharSequence fullText = "";
    private int tailContextChars = 20;
    private int lastWidth = -1;
    private CharSequence lastApplied = null;
    private boolean applyingInternally = false;

    public ExtensionEllipsisTextView(Context context) {
        this(context, null);
    }

    public ExtensionEllipsisTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ExtensionEllipsisTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setTailContextChars(int chars) {
        this.tailContextChars = Math.max(0, chars);
    }

    /**
     * Set the full (untruncated) file name. Truncation is recomputed on the next
     * layout pass against the measured width.
     */
    public void setFullName(@Nullable CharSequence name) {
        CharSequence next = name == null ? "" : name;
        if (next.equals(fullText)) {
            // Same content: nothing to reset; existing applied text stays valid.
            return;
        }
        fullText = next;
        lastWidth = -1;
        lastApplied = null;
        applyingInternally = true;
        setEllipsize(null);
        super.setText(fullText);
        applyingInternally = false;
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        // Direct setText (not via setFullName) resets the full-name baseline so
        // this view still behaves like a normal TextView when used that way.
        if (!applyingInternally) {
            fullText = text == null ? "" : text;
            lastWidth = -1;
            lastApplied = null;
        }
        super.setText(text, type);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // In a weight=1 row the final column width arrives as an EXACTLY spec on
        // the second measure pass; earlier AT_MOST/UNSPECIFIED passes report a
        // width that is too large and would wrongly conclude the name fits. Only
        // (re)compute truncation when we have the EXACTLY width.
        int specMode = MeasureSpec.getMode(widthMeasureSpec);
        if (specMode != MeasureSpec.EXACTLY) return;
        int avail = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (avail <= 0 || fullText.length() == 0) return;

        // Only recompute when the available width actually changed.
        if (avail == lastWidth && lastApplied != null) return;
        lastWidth = avail;

        CharSequence desired = computeTruncatedText(fullText.toString(), avail);
        if (!desired.equals(getText())) {
            applyingInternally = true;
            setEllipsize(desired.equals(fullText) ? TextUtils.TruncateAt.END : null);
            super.setText(desired);
            applyingInternally = false;
            lastApplied = desired;
            // Re-measure with the final text so height/lines are correct in one pass.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            lastApplied = desired;
        }
    }

    private CharSequence computeTruncatedText(String name, int availWidth) {
        int maxLines = Math.max(1, getMaxLines());
        TextPaint paint = getPaint();

        // If it already fits within maxLines, keep the full name (END ellipsis is
        // a harmless no-op when it fits).
        if (fitsWithinLines(name, paint, availWidth, maxLines)) {
            return name;
        }

        String ext = extensionOf(name);
        if (ext.isEmpty()) {
            // No useful extension to preserve: let the framework END-ellipsize.
            return name;
        }

        String stem = name.substring(0, name.length() - ext.length());
        int maxTailContext = Math.max(0, Math.min(tailContextChars, stem.length() - 4));
        String tailContext = maxTailContext > 0 ? stem.substring(stem.length() - maxTailContext) : "";
        String tail = "\u2026" + tailContext + ext;
        String head = stem.substring(0, stem.length() - tailContext.length());

        int lo = 0, hi = head.length(), best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            String candidate = head.substring(0, mid) + tail;
            if (fitsWithinLines(candidate, paint, availWidth, maxLines)) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return head.substring(0, best) + tail;
    }

    private boolean fitsWithinLines(String text, TextPaint paint, int widthPx, int maxLines) {
        StaticLayout sl = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, Math.max(1, widthPx))
                .setMaxLines(maxLines + 1)
                .build();
        return sl.getLineCount() <= maxLines;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        String ext = fileName.substring(dot);
        if (ext.length() > 8) return "";
        for (int i = 1; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (!Character.isLetterOrDigit(c)) return "";
        }
        return ext;
    }
}
