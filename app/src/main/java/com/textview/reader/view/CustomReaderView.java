package com.textview.reader.view;

import com.textview.reader.UiColorUtils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.annotation.Nullable;

import com.textview.reader.util.FileUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom scroll reader.
 *
 * This intentionally avoids RecyclerView for the main TXT body. The whole rendered text
 * has one fixed StaticLayout height, so page count is stable:
 *
 *   pageStep = visible screen height - overlap
 *   totalPages = floor(maxScrollY / pageStep) + 1
 *   currentPage = floor(scrollY / pageStep) + 1
 *
 * The denominator cannot grow while dragging because it is not based on RecyclerView's
 * lazy/estimated scroll range.
 */
public class CustomReaderView extends View {

    public interface ReaderListener {
        void onSingleTap(float x, float y);
        void onTextLongPress(String selectedText, int charPosition, float x, float y);
        void onReaderScrollChanged();
        void onReaderManualScroll();
        void onReaderManualOverscroll(int direction);
    }

    public static final class PageTextAnchor {
        public final int charPosition;
        public final String anchorTextBefore;
        public final String anchorTextAfter;

        public PageTextAnchor(int charPosition, String anchorTextBefore, String anchorTextAfter) {
            this.charPosition = Math.max(0, charPosition);
            this.anchorTextBefore = anchorTextBefore != null ? anchorTextBefore : "";
            this.anchorTextAfter = anchorTextAfter != null ? anchorTextAfter : "";
        }
    }



    private static int getContentHeightForPaging(StaticLayout sourceLayout,
                                                 String value,
                                                 int marginVerticalPx) {
        // Keep the paging height identical to the normal full-file TXT path.
        // Do not strip terminal blank lines here: doing so makes the final-page
        // visual anchor differ from simple full-file loading even when the total
        // page count happens to remain the same.
        int margin = Math.max(0, marginVerticalPx);
        return (sourceLayout != null ? sourceLayout.getHeight() : 0) + margin * 2;
    }

    public int getTextLayoutWidthForIndex() {
        return Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight()
                - marginHorizontalPx * 2 - leftTextInsetPx - rightTextInsetPx);
    }

    public int getMarginVerticalPxForIndex() {
        return Math.max(0, marginVerticalPx);
    }

    private int getEffectiveOverlapLines() {
        return Math.max(0, overlapLines);
    }

    public int getOverlapLinesForIndex() {
        return Math.max(0, overlapLines);
    }

    public TextPaint copyTextPaintForIndex() {
        TextPaint copy = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        copy.set(paint);
        return copy;
    }

    public float getLineSpacingMultiplierForIndex() {
        return Math.max(0.8f, lineSpacingMultiplier);
    }

    public static ArrayList<PageTextAnchor> buildPageTextAnchors(String value,
                                                                  TextPaint sourcePaint,
                                                                  int layoutWidth,
                                                                  int viewportHeight,
                                                                  int marginVerticalPx,
                                                                  int overlapLines,
                                                                  float lineSpacingMultiplier) {
        ArrayList<PageTextAnchor> result = new ArrayList<>();
        String fullText = value != null ? value : "";
        if (fullText.isEmpty()) {
            result.add(new PageTextAnchor(0, "", ""));
            return result;
        }

        TextPaint localPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        if (sourcePaint != null) {
            localPaint.set(sourcePaint);
        }

        StaticLayout localLayout = StaticLayout.Builder
                .obtain(fullText, 0, fullText.length(), localPaint, Math.max(1, layoutWidth))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, Math.max(0.8f, lineSpacingMultiplier))
                .setIncludePad(true)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        int lineCount = localLayout.getLineCount();
        if (lineCount <= 0) {
            result.add(new PageTextAnchor(0, "", ""));
            return result;
        }

        int firstPadCompensation = 0;
        if (lineCount >= 2) {
            int firstBaselineOffset = localLayout.getLineBaseline(0) - localLayout.getLineTop(0);
            int normalBaselineOffset = localLayout.getLineBaseline(1) - localLayout.getLineTop(1);
            firstPadCompensation = Math.max(0, firstBaselineOffset - normalBaselineOffset);
        }

        // Match the normal full-file CustomReaderView path exactly, including
        // terminal blank lines.  The large-TXT exact index must mirror what the
        // simple full-file reader would paginate, otherwise the last page can
        // show a different top line while still reporting the same page number.
        int contentHeight = getContentHeightForPaging(localLayout, fullText, marginVerticalPx);
        int viewport = Math.max(1, viewportHeight);
        int maxScroll = Math.max(0, contentHeight - viewport);
        int firstPageAnchor = Math.max(0, marginVerticalPx) + firstPadCompensation;
        int minPageScrollY = (lineCount <= 0 || maxScroll <= 0)
                ? 0
                : Math.max(0, Math.min(maxScroll, firstPageAnchor));

        int startLine = 0;
        int overlap = Math.max(0, Math.min(8, overlapLines));
        int lastChar = -1;
        while (startLine < lineCount) {
            int charPos = Math.max(0, Math.min(fullText.length(), localLayout.getLineStart(startLine)));
            charPos = FileUtils.clampToSurrogateSafeStart(fullText, charPos);
            if (charPos != lastChar) {
                int beforeStart = Math.max(0, charPos - 80);
                int afterEnd = Math.min(fullText.length(), charPos + 120);
                result.add(new PageTextAnchor(
                        charPos,
                        FileUtils.safeSubstring(fullText, beforeStart, charPos),
                        FileUtils.safeSubstring(fullText, charPos, afterEnd)));
                lastChar = charPos;
            }

            int rawAnchor = localLayout.getLineTop(startLine) + Math.max(0, marginVerticalPx);
            int anchor = Math.max(minPageScrollY, rawAnchor);
            int pageTop = Math.max(0, anchor - Math.max(0, marginVerticalPx));
            int pageBottomLimit = pageTop + viewport;

            int lastFullLine = startLine - 1;
            for (int line = startLine; line < lineCount; line++) {
                if (localLayout.getLineBottom(line) <= pageBottomLimit) {
                    lastFullLine = line;
                } else {
                    break;
                }
            }

            int nextStartLine = Math.max(startLine + 1, lastFullLine + 1 - overlap);
            if (nextStartLine <= startLine || nextStartLine >= lineCount) {
                break;
            }
            startLine = nextStartLine;
        }

        if (result.isEmpty()) {
            result.add(new PageTextAnchor(0, "", FileUtils.safeSubstring(fullText, 0, Math.min(fullText.length(), 120))));
        }
        return result;
    }

    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
    private final Paint searchHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeSearchHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ttsHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path searchHighlightPath = new Path();
    private final Path ttsHighlightPath = new Path();
    private final OverScroller scroller;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final int longPressTimeoutMs;

    private VelocityTracker velocityTracker;
    private ReaderListener listener;

    private String text = "";
    private StaticLayout layout;
    private int textColor = Color.rgb(224, 224, 224);
    private int backgroundColor = Color.BLACK;
    private float fontSizeSp = 18f;
    private float lineSpacingMultiplier = 1.5f;
    private int marginHorizontalPx = 24;
    private int marginVerticalPx = 16;
    private int overlapLines = 0;
    private int topTextZoneOffsetPx = 0;
    private int bottomTextZoneOffsetPx = 0;
    private int leftTextInsetPx = 0;
    private int rightTextInsetPx = 0;
    private Typeface typeface = Typeface.DEFAULT;
    private String searchQuery = "";
    private int activeSearchIndex = -1;
    private int ttsHighlightStart = -1;
    private int ttsHighlightEnd = -1;

    private int readerScrollY = 0;
    private int maxScrollY = 0;
    private final List<Integer> pageAnchors = new ArrayList<>();
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private boolean longPressTriggered;
    private Runnable pendingLongPressRunnable;

    public CustomReaderView(Context context) {
        this(context, null);
    }

    public CustomReaderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scroller = new OverScroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout();
        setFocusable(true);
        paint.setColor(textColor);
        paint.setTextSize(spToPx(fontSizeSp));
        paint.setTypeface(typeface);
        searchHighlightPaint.setStyle(Paint.Style.FILL);
        activeSearchHighlightPaint.setStyle(Paint.Style.FILL);
        ttsHighlightPaint.setStyle(Paint.Style.FILL);
        updateSearchHighlightColors();
        updateTtsHighlightColor();
    }

    public void setReaderListener(ReaderListener listener) {
        this.listener = listener;
    }

    public void releaseTextResources() {
        if (!scroller.isFinished()) {
            scroller.abortAnimation();
        }
        recycleVelocityTracker();
        cancelPendingLongPress();

        listener = null;
        text = "";
        layout = null;
        pageAnchors.clear();
        searchHighlightPath.reset();
        ttsHighlightPath.reset();
        searchQuery = "";
        activeSearchIndex = -1;
        ttsHighlightStart = -1;
        ttsHighlightEnd = -1;
        readerScrollY = 0;
        maxScrollY = 0;
        invalidate();
    }

    public void setTextContent(String value) {
        text = value != null ? value : "";
        clearTtsHighlight();
        readerScrollY = 0;
        rebuildLayout();
        invalidate();
        notifyScrollChanged();
    }

    public void setTextContentAtVisualEnd(String value) {
        text = value != null ? value : "";
        clearTtsHighlight();
        readerScrollY = 0;
        rebuildLayout();
        ensurePageAnchors();
        readerScrollY = Math.max(0, maxScrollY);
        invalidate();
        notifyScrollChanged();
    }

    public String getTextContent() {
        return text;
    }

    public void setReaderStyle(float fontSizeSp,
                               float lineSpacingMultiplier,
                               int textColor,
                               int backgroundColor,
                               int marginHorizontalPx,
                               int marginVerticalPx,
                               Typeface typeface) {
        float nextLineSpacing = Math.max(0.8f, lineSpacingMultiplier);
        int nextMarginHorizontal = Math.max(0, marginHorizontalPx);
        int nextMarginVertical = Math.max(0, marginVerticalPx);
        Typeface nextTypeface = typeface != null ? typeface : Typeface.DEFAULT;

        boolean layoutAffectingChange = Float.compare(this.fontSizeSp, fontSizeSp) != 0
                || Float.compare(this.lineSpacingMultiplier, nextLineSpacing) != 0
                || this.marginHorizontalPx != nextMarginHorizontal
                || this.marginVerticalPx != nextMarginVertical
                || this.typeface != nextTypeface;

        boolean colorChange = this.textColor != textColor || this.backgroundColor != backgroundColor;

        this.fontSizeSp = fontSizeSp;
        this.lineSpacingMultiplier = nextLineSpacing;
        this.textColor = textColor;
        this.backgroundColor = backgroundColor;
        this.marginHorizontalPx = nextMarginHorizontal;
        this.marginVerticalPx = nextMarginVertical;
        this.typeface = nextTypeface;

        paint.setColor(textColor);
        paint.setTextSize(spToPx(fontSizeSp));
        paint.setTypeface(this.typeface);
        updateSearchHighlightColors();
        updateTtsHighlightColor();

        if (layoutAffectingChange) {
            rebuildLayout();
            notifyScrollChanged();
        }

        if (layoutAffectingChange || colorChange) {
            invalidate();
        }
    }

    /**
     * Kept as a mode hook for ReaderActivity. Large TXT must not silently
     * override the user's page-overlap setting: if the user selected one or
     * more overlap lines, that repetition is intentional. Partition handoff
     * therefore prevents additional seam duplication beyond the configured
     * overlap instead of forcing overlap to zero.
     */
    public void setLargeTextPartitionMode(boolean enabled) {
        // No-op by design. Page anchoring and exact indexing always use the
        // same overlapLines value that normal TXT paging uses.
    }

    public void setOverlapLines(int overlapLines) {
        int next = Math.max(0, Math.min(8, overlapLines));
        if (this.overlapLines == next) return;
        this.overlapLines = next;
        rebuildPageAnchors();
        readerScrollY = clampScrollY(readerScrollY);
        invalidate();
        notifyScrollChanged();
    }

    public void setTextZoneAdjustments(int topOffsetPx, int bottomOffsetPx, int leftInsetPx, int rightInsetPx) {
        int nextTopOffset = Math.max(0, Math.min(240, topOffsetPx));
        int nextBottomOffset = Math.max(0, Math.min(240, bottomOffsetPx));
        int minHorizontalInset = -Math.max(0, marginHorizontalPx);
        int nextLeftInset = Math.max(minHorizontalInset, Math.min(240, leftInsetPx));
        int nextRightInset = Math.max(minHorizontalInset, Math.min(240, rightInsetPx));

        boolean widthChanged = this.leftTextInsetPx != nextLeftInset
                || this.rightTextInsetPx != nextRightInset;
        boolean viewportChanged = this.topTextZoneOffsetPx != nextTopOffset
                || this.bottomTextZoneOffsetPx != nextBottomOffset;

        if (!widthChanged && !viewportChanged) return;

        this.topTextZoneOffsetPx = nextTopOffset;
        this.bottomTextZoneOffsetPx = nextBottomOffset;
        this.leftTextInsetPx = nextLeftInset;
        this.rightTextInsetPx = nextRightInset;

        if (widthChanged) {
            rebuildLayout();
        } else {
            recalcMaxScroll();
        }

        invalidate();
        notifyScrollChanged();
    }

    public void setSearchHighlight(String query, int activeSearchIndex) {
        this.searchQuery = query != null ? query : "";
        this.activeSearchIndex = this.searchQuery.isEmpty() ? -1 : activeSearchIndex;
        invalidate();
    }

    public void setTtsHighlightRange(int startChar, int endChar) {
        int safeStart = Math.max(0, Math.min(text.length(), startChar));
        int safeEnd = Math.max(safeStart, Math.min(text.length(), endChar));
        if (safeEnd <= safeStart) {
            clearTtsHighlight();
            return;
        }
        ttsHighlightStart = safeStart;
        ttsHighlightEnd = safeEnd;
        invalidate();
    }

    public void clearTtsHighlight() {
        if (ttsHighlightStart < 0 && ttsHighlightEnd < 0) return;
        ttsHighlightStart = -1;
        ttsHighlightEnd = -1;
        ttsHighlightPath.reset();
        invalidate();
    }

    private void updateSearchHighlightColors() {
        boolean light = isLightColor(backgroundColor);
        int passive = themeSearchColor(light ? 0.32f : 0.38f, light ? 0.82f : 1.20f);
        int active = themeSearchColor(light ? 0.48f : 0.56f, light ? 0.66f : 1.34f);
        searchHighlightPaint.setColor(translucentColor(passive, light ? 72 : 88));
        activeSearchHighlightPaint.setColor(translucentColor(active, light ? 118 : 138));
    }

    private void updateTtsHighlightColor() {
        if (isLightColor(backgroundColor)) {
            ttsHighlightPaint.setColor(Color.argb(92, 72, 142, 255));
        } else {
            ttsHighlightPaint.setColor(Color.argb(105, 110, 172, 255));
        }
    }

    private int translucentColor(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private int themeSearchColor(float textMix, float contrastFactor) {
        int blended = UiColorUtils.blendColors(backgroundColor, textColor, textMix);
        return scaleColorAroundBackground(blended, contrastFactor);
    }

    private int scaleColorAroundBackground(int color, float factor) {
        int r = scaleChannelAroundBackground(Color.red(color), Color.red(backgroundColor), factor);
        int g = scaleChannelAroundBackground(Color.green(color), Color.green(backgroundColor), factor);
        int b = scaleChannelAroundBackground(Color.blue(color), Color.blue(backgroundColor), factor);
        return Color.rgb(r, g, b);
    }

    private int scaleChannelAroundBackground(int channel, int bgChannel, float factor) {
        return clampColor(Math.round(bgChannel + (channel - bgChannel) * factor));
    }

    private int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private boolean isLightColor(int color) {
        return UiColorUtils.isLightColor(color);
    }

    private void rebuildLayout() {
        int width = getWidth() - getPaddingLeft() - getPaddingRight()
                - marginHorizontalPx * 2 - leftTextInsetPx - rightTextInsetPx;
        if (width <= 0) return;

        layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(true)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        recalcMaxScroll();
    }

    private void recalcMaxScroll() {
        int contentHeight = getContentHeight();
        int viewport = getViewportHeight();
        maxScrollY = Math.max(0, contentHeight - viewport);
        rebuildPageAnchors();
        readerScrollY = clampScrollY(readerScrollY);
    }

    private int getContentHeight() {
        return getContentHeightForPaging(layout, text, marginVerticalPx);
    }

    public int getViewportHeight() {
        int top = getTextViewportTopY();
        int bottom = getTextViewportBottomY();
        return Math.max(1, bottom - top);
    }

    private int getTextViewportTopY() {
        int physicalTop = getPaddingTop();
        int physicalBottom = getHeight() - getPaddingBottom();
        int shiftedTop = physicalTop + topTextZoneOffsetPx;
        return Math.max(physicalTop, Math.min(physicalBottom - 1, shiftedTop));
    }

    private int getTextViewportBottomY() {
        int physicalTop = getTextViewportTopY();
        int physicalBottom = getHeight() - getPaddingBottom();
        int shiftedBottom = physicalBottom - bottomTextZoneOffsetPx;
        return Math.max(physicalTop + 1, Math.min(physicalBottom, shiftedBottom));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildLayout();
        notifyScrollChanged();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(backgroundColor);
        if (layout == null) return;

        canvas.save();
        int viewportTop = getTextViewportTopY();
        int visualScrollY = getVisualScrollYForDraw();
        canvas.clipRect(getPaddingLeft() + marginHorizontalPx + leftTextInsetPx,
                viewportTop,
                getWidth() - getPaddingRight() - marginHorizontalPx - rightTextInsetPx,
                getFullLineClipBottom());
        canvas.translate(getPaddingLeft() + marginHorizontalPx + leftTextInsetPx,
                viewportTop + marginVerticalPx - visualScrollY);
        drawTtsHighlight(canvas);
        drawSearchHighlights(canvas);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawTtsHighlight(Canvas canvas) {
        if (layout == null || ttsHighlightStart < 0 || ttsHighlightEnd <= ttsHighlightStart) return;

        int start = Math.max(0, Math.min(text.length(), ttsHighlightStart));
        int end = Math.max(start, Math.min(text.length(), ttsHighlightEnd));
        if (end <= start) return;

        int lineCount = layout.getLineCount();
        if (lineCount <= 0) return;
        int startLine = layout.getLineForOffset(start);
        int endLine = layout.getLineForOffset(Math.max(start, end - 1));
        int layoutTopY = Math.max(0, readerScrollY - marginVerticalPx);
        int layoutBottomY = Math.min(layout.getHeight(), layoutTopY + getViewportHeight());
        if (layout.getLineBottom(startLine) < layoutTopY || layout.getLineTop(endLine) > layoutBottomY) {
            return;
        }

        ttsHighlightPath.reset();
        layout.getSelectionPath(start, end, ttsHighlightPath);
        canvas.drawPath(ttsHighlightPath, ttsHighlightPaint);
    }

    private void drawSearchHighlights(Canvas canvas) {
        if (layout == null || text.isEmpty() || searchQuery == null || searchQuery.isEmpty()) return;

        int lineCount = layout.getLineCount();
        if (lineCount <= 0) return;

        int layoutTopY = Math.max(0, readerScrollY - marginVerticalPx);
        int viewportHeight = getViewportHeight();
        int layoutBottomY = Math.min(layout.getHeight(), layoutTopY + viewportHeight);

        int startLine = Math.max(0, layout.getLineForVertical(layoutTopY) - 1);
        int endLine = Math.min(lineCount - 1, layout.getLineForVertical(Math.max(0, layoutBottomY - 1)) + 1);

        int startChar = Math.max(0, layout.getLineStart(startLine) - Math.max(0, searchQuery.length() - 1));
        int endChar = Math.min(text.length(), layout.getLineEnd(endLine) + Math.max(0, searchQuery.length() - 1));
        int step = Math.max(1, searchQuery.length());

        int index = text.indexOf(searchQuery, startChar);
        while (index >= 0 && index < endChar) {
            int matchEnd = Math.min(text.length(), index + searchQuery.length());
            if (matchEnd > startChar) {
                searchHighlightPath.reset();
                layout.getSelectionPath(index, matchEnd, searchHighlightPath);
                canvas.drawPath(searchHighlightPath,
                        index == activeSearchIndex ? activeSearchHighlightPaint : searchHighlightPaint);
            }
            int nextStart = Math.max(index + step, index + 1);
            index = text.indexOf(searchQuery, nextStart);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.abortAnimation();
                downX = event.getX();
                downY = event.getY();
                lastY = downY;
                dragging = false;
                longPressTriggered = false;
                scheduleLongPress(downX, downY);
                return true;

            case MotionEvent.ACTION_MOVE:
                float y = event.getY();
                float dy = lastY - y;
                if (!dragging && Math.abs(y - downY) > touchSlop) {
                    dragging = true;
                    cancelPendingLongPress();
                    if (listener != null) listener.onReaderManualScroll();
                }
                if (dragging) {
                    if (listener != null && dy < 0 && readerScrollY <= getMinPageScrollY() + 2) {
                        listener.onReaderManualOverscroll(-1);
                    }
                    scrollByPixels((int) dy);
                    lastY = y;
                }
                return true;

            case MotionEvent.ACTION_UP:
                cancelPendingLongPress();
                if (longPressTriggered) {
                    recycleVelocityTracker();
                    return true;
                }
                if (!dragging && Math.abs(event.getX() - downX) < touchSlop && Math.abs(event.getY() - downY) < touchSlop) {
                    if (listener != null) listener.onSingleTap(event.getX(), event.getY());
                } else {
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float velocityY = velocityTracker.getYVelocity();
                    if (Math.abs(velocityY) > minFlingVelocity) {
                        scroller.fling(0, readerScrollY, 0, (int) -velocityY, 0, 0, 0, maxScrollY);
                        postInvalidateOnAnimation();
                    }
                }
                recycleVelocityTracker();
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelPendingLongPress();
                recycleVelocityTracker();
                return true;
        }
        return true;
    }

    private void scheduleLongPress(float x, float y) {
        cancelPendingLongPress();
        pendingLongPressRunnable = () -> {
            if (dragging || listener == null) return;
            TextHit hit = getWordHitAt(x, y);
            if (hit == null || hit.text == null || hit.text.trim().isEmpty()) return;
            longPressTriggered = true;
            listener.onTextLongPress(hit.text, hit.charPosition, x, y);
        };
        postDelayed(pendingLongPressRunnable, longPressTimeoutMs);
    }

    private void cancelPendingLongPress() {
        if (pendingLongPressRunnable != null) {
            removeCallbacks(pendingLongPressRunnable);
            pendingLongPressRunnable = null;
        }
    }

    private static final class TextHit {
        final String text;
        final int charPosition;
        TextHit(String text, int charPosition) {
            this.text = text;
            this.charPosition = charPosition;
        }
    }

    private TextHit getWordHitAt(float viewX, float viewY) {
        if (layout == null || text == null || text.isEmpty()) return null;
        int viewportTop = getTextViewportTopY();
        float layoutX = viewX - getPaddingLeft() - marginHorizontalPx - leftTextInsetPx;
        float layoutY = viewY - viewportTop - marginVerticalPx + getVisualScrollYForDraw();
        if (layoutX < 0 || layoutY < 0 || layoutY > layout.getHeight()) return null;
        int line = Math.max(0, Math.min(layout.getLineCount() - 1,
                layout.getLineForVertical(Math.max(0, Math.round(layoutY)))));
        int offset = Math.max(0, Math.min(text.length(), layout.getOffsetForHorizontal(line, layoutX)));
        if (offset >= text.length() && text.length() > 0) offset = text.length() - 1;
        if (offset < 0 || offset >= text.length()) return null;

        // If the exact offset is punctuation/space, try the previous char first.
        int seed = offset;
        if (!isWordChar(text.charAt(seed)) && seed > 0 && isWordChar(text.charAt(seed - 1))) {
            seed--;
        }
        if (!isWordChar(text.charAt(seed))) return null;

        int start = seed;
        int end = seed + 1;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isWordChar(text.charAt(end))) end++;
        String word = FileUtils.safeSubstring(text, start, end).trim();
        if (word.isEmpty()) return null;
        return new TextHit(word, start);
    }

    private static boolean isWordChar(char ch) {
        if (Character.isLetterOrDigit(ch)) return true;
        int type = Character.getType(ch);
        return ch == '_' || ch == '-' || type == Character.OTHER_LETTER;
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            setReaderScrollY(scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    private void scrollByPixels(int dy) {
        setReaderScrollY(readerScrollY + dy);
    }

    public void setReaderScrollY(int y) {
        int clamped = clampScrollY(y);
        if (clamped != readerScrollY) {
            readerScrollY = clamped;
            invalidate();
            notifyScrollChanged();
        }
    }

    public int getReaderScrollY() {
        return readerScrollY;
    }

    /**
     * Returns the top edge, in this view's coordinate space, of the first text
     * line currently visible in the TXT viewport.  This is intentionally based
     * on the actual StaticLayout line selected by readerScrollY, not just on
     * padding/margins, because page anchors after page 1 can start on different
     * layout line boundaries.
     */
    public int getFirstVisibleLineTopInView() {
        if (layout == null || layout.getLineCount() <= 0) {
            return getTextViewportTopY() + marginVerticalPx;
        }
        int layoutY = Math.max(0, readerScrollY - marginVerticalPx);
        int line = Math.max(0, Math.min(layout.getLineCount() - 1, layout.getLineForVertical(layoutY)));
        return getTextViewportTopY() + marginVerticalPx - readerScrollY + layout.getLineTop(line);
    }

    /**
     * Returns the bottom edge, in this view's coordinate space, of the first
     * visible text line.  This is useful for text selection/highlight behavior,
     * but overlay chrome should not depend on it because the last page can be
     * clamped to maxScrollY and shift the actual visible line grid.
     */
    public int getFirstVisibleLineBottomInView() {
        if (layout == null || layout.getLineCount() <= 0) {
            return getFirstVisibleLineTopInView() + Math.max(1, Math.round(getLineHeightPx()));
        }
        int layoutY = Math.max(0, readerScrollY - marginVerticalPx);
        int line = Math.max(0, Math.min(layout.getLineCount() - 1, layout.getLineForVertical(layoutY)));
        return getTextViewportTopY() + marginVerticalPx - readerScrollY + layout.getLineBottom(line);
    }

    /**
     * Stable visual slot for the first TXT row.  Unlike getFirstVisibleLine*(),
     * this does not depend on readerScrollY, so it stays fixed on the last page
     * even when the final page's scroll position is clamped.
     */
    public int getStableFirstRowTopInView() {
        return getTextViewportTopY();
    }

    public int getStableFirstRowBottomInView() {
        int top = getStableFirstRowTopInView();
        int rowHeight = Math.max(1, Math.round(getLineHeightPx()));
        return Math.min(getHeight(), top + rowHeight);
    }

    public int getMaxScrollY() {
        return maxScrollY;
    }

    public boolean isUserDraggingOrFlinging() {
        return dragging || !scroller.isFinished();
    }

    /**
     * StaticLayout includes extra top font padding on the very first line when
     * includePad=true.  Later page-start lines do not include that same leading
     * pad in their baseline offset, so page 1 can look slightly lower than page
     * 2+.  Compensate only the minimum/page-1 anchor by that extra first-line
     * pad; this keeps page 1 on the same visual row grid as the later pages
     * without changing the real line-boundary anchors used for pagination.
     */
    private int getFirstPageTopPadCompensationPx() {
        if (layout == null || layout.getLineCount() < 2) {
            return 0;
        }
        int firstBaselineOffset = layout.getLineBaseline(0) - layout.getLineTop(0);
        int normalBaselineOffset = layout.getLineBaseline(1) - layout.getLineTop(1);
        return Math.max(0, firstBaselineOffset - normalBaselineOffset);
    }

    private int getMinPageScrollY() {
        if (layout == null || layout.getLineCount() <= 0 || maxScrollY <= 0) {
            return 0;
        }
        int firstPageAnchor = getFirstPageVisualAnchorY();
        return Math.max(0, Math.min(maxScrollY, firstPageAnchor));
    }

    private int getFirstPageVisualAnchorY() {
        if (layout == null || layout.getLineCount() <= 0) {
            return 0;
        }
        return marginVerticalPx + getFirstPageTopPadCompensationPx();
    }

    private int getVisualScrollYForDraw() {
        int firstPageAnchor = getFirstPageVisualAnchorY();
        if (firstPageAnchor > 0 && maxScrollY <= firstPageAnchor) {
            return firstPageAnchor;
        }
        return readerScrollY;
    }

    private int rawScrollYForLine(int line) {
        if (layout == null || layout.getLineCount() <= 0) {
            return 0;
        }
        int clampedLine = Math.max(0, Math.min(layout.getLineCount() - 1, line));
        return layout.getLineTop(clampedLine) + marginVerticalPx;
    }

    private int scrollYForLine(int line) {
        int raw = rawScrollYForLine(line);
        return Math.max(getMinPageScrollY(), Math.min(maxScrollY, raw));
    }

    /**
     * Builds page anchors from actual StaticLayout line boundaries.  The next
     * page starts from the first line not fully visible on the previous page,
     * minus the user-configured overlap lines.  This keeps large-TXT partition
     * seams consistent with normal TXT paging: overlap is honored when enabled,
     * and no extra seam duplication is introduced when overlap is 0.
     */
    private void rebuildPageAnchors() {
        pageAnchors.clear();
        if (layout == null || layout.getLineCount() <= 0) {
            pageAnchors.add(0);
            return;
        }

        int lineCount = layout.getLineCount();
        int viewportHeight = Math.max(1, getViewportHeight());
        int overlap = Math.max(0, getEffectiveOverlapLines());

        int startLine = 0;
        while (startLine < lineCount) {
            int anchor = Math.max(getMinPageScrollY(), rawScrollYForLine(startLine));
            if (pageAnchors.isEmpty() || pageAnchors.get(pageAnchors.size() - 1) != anchor) {
                pageAnchors.add(anchor);
            }

            // Use the actual layout Y visible at this page anchor.  Page 1 can use
            // a small visual top-pad compensation while later pages start directly
            // on a line boundary; basing page capacity on lineTop(startLine) alone
            // can make page 2 repeat the last fully visible line from page 1 when
            // the TXT bottom boundary reduces the viewport.
            int pageTop = Math.max(0, anchor - marginVerticalPx);
            int pageBottomLimit = pageTop + viewportHeight;

            int lastFullLine = startLine - 1;
            for (int line = startLine; line < lineCount; line++) {
                if (layout.getLineBottom(line) <= pageBottomLimit) {
                    lastFullLine = line;
                } else {
                    break;
                }
            }

            int nextStartLine = Math.max(startLine + 1, lastFullLine + 1 - overlap);
            if (nextStartLine <= startLine || nextStartLine >= lineCount) {
                break;
            }
            startLine = nextStartLine;
        }

        if (pageAnchors.isEmpty()) {
            pageAnchors.add(getMinPageScrollY());
        }

        // The natural max scroll can land between StaticLayout line tops on the
        // final page.  If the final page anchor is forced down to that fractional
        // clamp, the top row can be clipped.  Keep enough virtual bottom space so
        // the last page can still start on its real line boundary.
        if (pageAnchors.size() > 1) {
            int lastAnchor = pageAnchors.get(pageAnchors.size() - 1);
            if (lastAnchor > maxScrollY) {
                maxScrollY = lastAnchor;
            }
        }
    }

    private void ensurePageAnchors() {
        if (pageAnchors.isEmpty()) {
            rebuildPageAnchors();
        }
    }

    private int clampScrollY(int y) {
        int minScrollY = getMinPageScrollY();
        if (maxScrollY <= minScrollY) {
            return 0;
        }
        return Math.max(minScrollY, Math.min(maxScrollY, y));
    }

    private int snapScrollYToLineTop(int y) {
        if (layout == null || text.isEmpty()) return clampScrollY(y);

        int clamped = clampScrollY(y);
        int minScrollY = getMinPageScrollY();
        if (clamped <= minScrollY) return minScrollY;

        int layoutY = Math.max(0, clamped - marginVerticalPx);
        int line = layout.getLineForVertical(layoutY);
        int aligned = rawScrollYForLine(line);
        if (aligned > clamped && line > 0) {
            aligned = rawScrollYForLine(line - 1);
        }

        // Align the visible top edge to a real StaticLayout line boundary.
        // This prevents the top line from being clipped in half after tap paging,
        // including the last page where the natural max scroll may be between rows.
        return clampScrollY(aligned);
    }

    private int getFullLineClipBottom() {
        if (layout == null) {
            return getTextViewportBottomY();
        }

        int viewTop = getTextViewportTopY();
        int viewBottom = getTextViewportBottomY();
        int viewportHeight = Math.max(1, viewBottom - viewTop);

        int visualScrollY = getVisualScrollYForDraw();
        int layoutTopY = Math.max(0, visualScrollY - marginVerticalPx);
        int visibleBottomInLayout = Math.min(layout.getHeight(), layoutTopY + viewportHeight);

        int line = layout.getLineForVertical(Math.max(0, visibleBottomInLayout));
        int lineBottom = layout.getLineBottom(line);

        if (lineBottom > visibleBottomInLayout) {
            int fullBottomInLayout = Math.max(0, layout.getLineTop(line));
            int screenBottom = viewTop + marginVerticalPx - visualScrollY + fullBottomInLayout;
            return Math.max(viewTop, Math.min(viewBottom, screenBottom));
        }

        return viewBottom;
    }

    private void notifyScrollChanged() {
        if (listener != null) listener.onReaderScrollChanged();
    }

    public int getPageStepPx() {
        int step = getViewportHeight() - getOverlapPx();
        return Math.max(1, step);
    }

    private int getOverlapPx() {
        int effectiveOverlap = getEffectiveOverlapLines();
        if (effectiveOverlap <= 0) return 0;
        return Math.max(0, Math.round(getLineHeightPx() * effectiveOverlap));
    }

    private float getLineHeightPx() {
        return paint.getTextSize() * lineSpacingMultiplier * 1.18f;
    }

    public int getTotalPageCount() {
        ensurePageAnchors();
        return Math.max(1, pageAnchors.size());
    }


    public int getPageNumberForCharPosition(int charPosition) {
        ensurePageAnchors();
        if (layout == null || pageAnchors.isEmpty()) return 1;

        // Use the same full StaticLayout line space as normal TXT paging.
        // Clamping to a "meaningful" non-newline character made EOF/last-page
        // status disagree with the simple full-file reader.
        int targetChar = Math.max(0, Math.min(text.length(), charPosition));
        int line = Math.max(0, Math.min(Math.max(0, layout.getLineCount() - 1),
                layout.getLineForOffset(targetChar)));
        int targetScrollY = scrollYForLine(line) + 1;

        int lo = 0;
        int hi = pageAnchors.size() - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int anchor = pageAnchors.get(mid);
            if (targetScrollY >= anchor) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(1, Math.min(pageAnchors.size(), best + 1));
    }

    public boolean isAtVisualEndOfText() {
        ensurePageAnchors();
        if (layout == null || text.isEmpty()) return true;
        return readerScrollY >= Math.max(0, maxScrollY - 2);
    }

    public int getCurrentPageNumber() {
        ensurePageAnchors();
        int total = pageAnchors.size();
        if (total <= 1) return 1;

        // Large TXT files can have tens of thousands of page anchors.  This method
        // is called during scroll/status/menu updates, so use binary search instead
        // of scanning from page 1 every time.  This keeps e-ink devices responsive
        // when opening the toolbar or dialogs on 30MB+ files.
        int target = readerScrollY + 1;
        int lo = 0;
        int hi = total - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int anchor = pageAnchors.get(mid);
            if (target >= anchor) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(1, Math.min(total, best + 1));
    }

    private int getPageAnchorScrollY(int page) {
        ensurePageAnchors();
        int total = Math.max(1, pageAnchors.size());
        int clampedPage = Math.max(1, Math.min(total, page));
        return pageAnchors.get(clampedPage - 1);
    }

    public int getPageStartCharPosition(int page) {
        if (layout == null || text.isEmpty()) return 0;
        return getCharPositionForScrollYLineStart(getPageAnchorScrollY(page));
    }

    public int getCurrentLineOffsetWithinPage() {
        if (layout == null || text.isEmpty()) return 1;
        ensurePageAnchors();
        if (pageAnchors.isEmpty()) return 1;

        int currentPage = getCurrentPageNumber();

        // Use the same visual row anchor that TXT bookmarks use.  The previous
        // implementation sampled readerScrollY directly; after restoring a
        // bookmark, the displayed text row could be correct while the status
        // label was off by one because the visual/title-covered row and the raw
        // scroll boundary were not always the same StaticLayout line.
        int pageStartChar = getPageStartCharPosition(currentPage);
        int currentVisualChar = getCharPositionAtTitleCoveredRow();

        int pageStartLine = getStableLineForCharPosition(pageStartChar);
        int currentLine = getStableLineForCharPosition(currentVisualChar);
        return Math.max(1, currentLine - pageStartLine + 1);
    }

    private int getStableLineForCharPosition(int charPosition) {
        if (layout == null || layout.getLineCount() <= 0 || text.isEmpty()) return 0;
        int pos = Math.max(0, Math.min(text.length(), charPosition));
        return Math.max(0, Math.min(layout.getLineCount() - 1, layout.getLineForOffset(pos)));
    }

    public void scrollToPage(int page) {
        setReaderScrollY(getPageAnchorScrollY(page));
    }

    /**
     * Moves to the physical bottom clamp of the rendered text, not merely the
     * last page anchor.  On some files the final global page is represented by
     * the small tail between the last line-aligned page anchor and maxScrollY;
     * tap paging must be able to reach that tail just like manual scroll can.
     */
    public void scrollToVisualEndOfText() {
        ensurePageAnchors();
        setReaderScrollY(maxScrollY);
    }

    public void pageBy(int direction) {
        if (direction == 0) return;

        int total = getTotalPageCount();
        int current = getCurrentPageNumber();

        if (direction > 0 && current >= total) {
            setReaderScrollY(getPageAnchorScrollY(total));
            return;
        }

        if (direction < 0 && current <= 1) {
            setReaderScrollY(getPageAnchorScrollY(1));
            return;
        }

        scrollToPage(current + direction);
    }

    /**
     * Previous tap/page navigation mirrors exact-anchor mode before the global
     * exact index is ready.  If the top row is already inside the current page
     * interval, the first previous tap snaps back to that page's own start.
     * Only when the viewport is already on that start row does the next previous
     * tap move to the previous page.  The comparison is line/char based rather
     * than raw pixel based, so tiny pixel offsets inside the same top line do not
     * create a false page transition.
     */
    public boolean pageBackwardWithoutSkippingContent() {
        if (layout == null || text.isEmpty()) {
            pageBy(-1);
            return false;
        }

        int current = getCurrentPageNumber();
        if (isCurrentLinePastCurrentPageAnchor()) {
            scrollToPage(current);
            return true;
        }

        if (current <= 1) {
            scrollToPage(1);
            return false;
        }

        scrollToPage(current - 1);
        return false;
    }

    public boolean isCurrentLinePastCurrentPageAnchor() {
        ensurePageAnchors();
        if (layout == null || text.isEmpty() || pageAnchors.isEmpty()) return false;
        int current = getCurrentPageNumber();
        int anchorChar = getCharPositionForScrollYLineStart(getPageAnchorScrollY(current));
        int currentChar = getCurrentPageStartCharPositionForCoverage();
        return currentChar > anchorChar;
    }

    private int getCharPositionForScrollYLineStart(int scrollYValue) {
        if (layout == null || text.isEmpty()) return 0;
        int layoutY = Math.max(0, scrollYValue - marginVerticalPx);
        int line = Math.max(0, Math.min(layout.getLineCount() - 1,
                layout.getLineForVertical(layoutY)));
        return Math.max(0, Math.min(text.length(), layout.getLineStart(line)));
    }

    /**
     * Forward tap/page navigation should never start after the first text row
     * that was not fully visible on the previous page.  Page anchors are already
     * built from that rule, but this guard makes the invariant explicit and
     * repairs edge cases caused by max-scroll clamping, layout-tail pages, or
     * future pagination changes.
     *
     * @return true if the guard had to correct the target position.
     */
    public boolean pageForwardWithoutSkippingContent() {
        if (layout == null || text.isEmpty()) {
            pageBy(1);
            return false;
        }

        int safeNextStart = getCharPositionForNextPageStartRespectingOverlap();
        int beforeStart = getCurrentPageStartCharPositionForCoverage();

        pageBy(1);

        if (safeNextStart >= text.length()) {
            return false;
        }

        int actualStart = getCurrentPageStartCharPositionForCoverage();
        if (actualStart > safeNextStart && safeNextStart >= beforeStart) {
            scrollToCharPosition(safeNextStart);
            return true;
        }
        return false;
    }

    public void scrollToPercent(float percent) {
        percent = Math.max(0f, Math.min(1f, percent));
        setReaderScrollY(snapScrollYToLineTop(Math.round(maxScrollY * percent)));
    }

    public int getCurrentCharPosition() {
        return getCharPositionFromCurrentLineOffset(0);
    }

    /**
     * Returns the first character position that has not been fully shown on the
     * current visual page, ignoring page-overlap. This is useful for coverage
     * checks, but it is not the visual start of the next page when overlap is
     * enabled.
     */
    public int getCharPositionAfterCurrentVisibleContent() {
        return getNextPageStartCharPosition(false);
    }

    /**
     * Returns the visual start character of the next page using the exact same
     * overlap rule as normal TXT paging. Large-TXT partition handoff uses this
     * to avoid extra seam duplicates while still honoring the user's configured
     * page-overlap setting.
     */
    public int getCharPositionForNextPageStartRespectingOverlap() {
        return getNextPageStartCharPosition(true);
    }

    private int getNextPageStartCharPosition(boolean includeConfiguredOverlap) {
        if (layout == null || text.isEmpty()) return 0;

        int viewportHeight = Math.max(1, getViewportHeight());
        int layoutTopY = Math.max(0, readerScrollY - marginVerticalPx);
        int pageBottomLimit = layoutTopY + viewportHeight;
        int startLine = Math.max(0, Math.min(layout.getLineCount() - 1,
                layout.getLineForVertical(layoutTopY)));

        int lastFullLine = startLine - 1;
        for (int line = startLine; line < layout.getLineCount(); line++) {
            if (layout.getLineBottom(line) <= pageBottomLimit) {
                lastFullLine = line;
            } else {
                break;
            }
        }

        int overlap = includeConfiguredOverlap ? Math.max(0, getEffectiveOverlapLines()) : 0;
        int nextLine = Math.max(startLine + 1, lastFullLine + 1 - overlap);
        nextLine = Math.max(0, Math.min(layout.getLineCount(), nextLine));
        if (nextLine >= layout.getLineCount()) return text.length();
        return Math.max(0, Math.min(text.length(), layout.getLineStart(nextLine)));
    }

    public int getCharPositionFromCurrentLineOffset(int lineOffset) {
        if (layout == null || text.isEmpty()) return 0;
        int layoutY = Math.max(0, readerScrollY - marginVerticalPx);
        int currentLine = layout.getLineForVertical(layoutY);
        int targetLine = Math.max(0, Math.min(Math.max(0, layout.getLineCount() - 1), currentLine + lineOffset));
        return Math.max(0, Math.min(text.length(), layout.getLineStart(targetLine)));
    }

    /**
     * First logical row currently used as the page start, in local text offsets.
     * This is intentionally line-based, not raw pixel-based, so coverage checks
     * compare the same anchors that TXT paging uses.
     */
    public int getCurrentPageStartCharPositionForCoverage() {
        return getCharPositionFromCurrentLineOffset(0);
    }

    /**
     * Returns the character offset for the TXT row selected by the filename/title
     * strip.  Sample an interior point of the title-covered row instead of the
     * top edge or the lower edge.  The top edge can resolve to the row above when
     * the viewport is between StaticLayout rows, while the lower-edge correction
     * can step into the next row.  An interior sample keeps the saved bookmark on
     * the actual title-covered row without a forced +/- one-line adjustment.
     */
    public int getCharPositionAtTitleCoveredRow() {
        if (layout == null || text.isEmpty()) return getCurrentCharPosition();

        int rowTop = getStableFirstRowTopInView();
        int rowBottom = Math.max(rowTop + 1, getStableFirstRowBottomInView());
        int targetY = rowTop + Math.max(1, Math.round((rowBottom - rowTop) * 0.55f));
        return getCharPositionAtViewY(targetY);
    }

    private int getCharPositionAtViewY(int viewY) {
        if (layout == null || text.isEmpty()) return 0;

        int viewportTop = getTextViewportTopY();
        int viewportBottom = getTextViewportBottomY();
        int safeY = Math.max(viewportTop, Math.min(viewportBottom - 1, viewY));

        int visualScrollY = getVisualScrollYForDraw();
        int layoutY = Math.max(0, visualScrollY - marginVerticalPx + (safeY - viewportTop));

        int line = Math.max(0, Math.min(layout.getLineCount() - 1,
                layout.getLineForVertical(layoutY)));
        return Math.max(0, Math.min(text.length(), layout.getLineStart(line)));
    }

    public int getCharPositionBelowViewY(int viewY) {
        if (layout == null || text.isEmpty()) return getCurrentCharPosition();

        int viewportTop = getTextViewportTopY();
        int viewportBottom = getTextViewportBottomY();
        if (viewY <= viewportTop) return getCurrentCharPosition();

        int safeY = Math.max(viewportTop, Math.min(viewportBottom - 1, viewY));
        int visualScrollY = getVisualScrollYForDraw();
        int layoutY = Math.max(0, visualScrollY - marginVerticalPx + (safeY - viewportTop));

        int line = Math.max(0, Math.min(layout.getLineCount() - 1,
                layout.getLineForVertical(layoutY)));
        int lineTopInView = viewportTop + marginVerticalPx - visualScrollY + layout.getLineTop(line);

        // If the requested Y cuts through a line, save the next fully visible line
        // instead of the line hidden under the page/title notification overlay.
        if (lineTopInView < safeY && line < layout.getLineCount() - 1) {
            line++;
        }

        return Math.max(0, Math.min(text.length(), layout.getLineStart(line)));
    }

    public void scrollToCharPosition(int charPosition) {
        scrollToCharPosition(charPosition, false);
    }

    public void scrollToCharPositionWithContext(int charPosition) {
        scrollToCharPosition(charPosition, true);
    }

    public void scrollToSearchResultPosition(int charPosition) {
        if (layout == null || text.isEmpty()) return;
        int pos = Math.max(0, Math.min(text.length(), charPosition));
        int line = layout.getLineForOffset(pos);

        int viewport = Math.max(1, getViewportHeight());
        int lineHeight = Math.max(1, Math.round(getLineHeightPx()));

        // Search is often performed while the Find popup is still open at the
        // bottom of the screen.  Keep the matching line in the upper safe zone:
        // low enough to avoid the title/page toolbar, but high enough that the
        // bottom search popup cannot cover it.  Compared with the previous 2.1.2b
        // placement, lift the result by roughly two text rows for better visibility
        // above the search popup.  Use the same positioning for next/previous,
        // cross-partition, and nth-result jumps.
        int safeOffset = Math.max(Math.round(lineHeight * 1.0f), Math.round(viewport * 0.075f));
        safeOffset = Math.min(safeOffset, Math.round(viewport * 0.14f));

        int scrollY = layout.getLineTop(line) + marginVerticalPx - safeOffset;
        setReaderScrollY(snapScrollYToLineTop(scrollY));
    }

    private void scrollToCharPosition(int charPosition, boolean keepContextAboveTarget) {
        if (layout == null || text.isEmpty()) return;
        int pos = Math.max(0, Math.min(text.length(), charPosition));
        int line = layout.getLineForOffset(pos);

        int scrollY = layout.getLineTop(line) + marginVerticalPx;
        if (keepContextAboveTarget) {
            // Search results are easier to see when the matching line is not pressed
            // directly against the top edge. Bookmark/position restore must not use
            // this offset, because saved TXT bookmarks are based on the first visible
            // line and should reload to that exact line.
            scrollY -= Math.round(getLineHeightPx() * 1.2f);
        }
        setReaderScrollY(snapScrollYToLineTop(scrollY));
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }
}
