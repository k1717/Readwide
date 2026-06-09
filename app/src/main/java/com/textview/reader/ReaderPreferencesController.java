package com.textview.reader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;

import com.textview.reader.model.Theme;

final class ReaderPreferencesController {
    private final ReaderActivity activity;

    private float appliedFontSize = Float.NaN;
    private float appliedLineSpacing = Float.NaN;
    private int appliedTextColor = Integer.MIN_VALUE;
    private int appliedBackgroundColor = Integer.MIN_VALUE;
    private int appliedMarginHorizontalPx = Integer.MIN_VALUE;
    private int appliedMarginVerticalPx = Integer.MIN_VALUE;
    private int appliedTopTextZoneOffsetPx = Integer.MIN_VALUE;
    private int appliedBottomTextZoneOffsetPx = Integer.MIN_VALUE;
    private int appliedLeftTextInsetPx = Integer.MIN_VALUE;
    private int appliedRightTextInsetPx = Integer.MIN_VALUE;
    private int appliedPagingOverlapLines = Integer.MIN_VALUE;
    private Typeface appliedTypeface = null;

    ReaderPreferencesController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void applyPreferences() {
        float fontSize = activity.prefs.getFontSize();
        float lineSpacing = activity.prefs.getLineSpacing();
        int marginH = activity.dpToPx(activity.prefs.getMarginHorizontal());
        int marginV = activity.dpToPx(activity.prefs.getMarginVertical());
        int topTextZoneOffsetPx = activity.prefs.getReaderTextTopOffsetPx();
        int bottomTextZoneOffsetPx = activity.prefs.getReaderTextBottomOffsetPx();
        int leftTextInsetPx = activity.prefs.getReaderTextLeftInsetPx();
        int rightTextInsetPx = activity.prefs.getReaderTextRightInsetPx();
        int overlapLines = activity.prefs.getPagingOverlapLines();

        Typeface tf = new ReaderFontDialogController(activity)
                .resolveReadingTypeface(activity.prefs.getFontFamily());

        Theme theme = activity.themeManager != null ? activity.themeManager.getActiveTheme() : null;
        int textColor = theme != null ? theme.getTextColor() : 0xFFE0E0E0;
        int bgColor = theme != null ? theme.getBackgroundColor() : Color.BLACK;
        int toolbarColor = theme != null ? theme.getToolbarColor() : bgColor;

        if (activity.readerView != null) {
            applyReaderViewPreferences(fontSize, lineSpacing, marginH, marginV,
                    topTextZoneOffsetPx, bottomTextZoneOffsetPx,
                    leftTextInsetPx, rightTextInsetPx, overlapLines,
                    tf, textColor, bgColor);
        }

        activity.applyReaderSystemBarColors(bgColor, textColor, toolbarColor);

        if (activity.prefs.getKeepScreenOn()) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        activity.applyStatusBarVisibilityPreference();
        activity.applyPageStatusAlignment(activity.lastReaderTopInset);
        if (activity.readerRoot != null) ViewCompat.requestApplyInsets(activity.readerRoot);
    }

    private void applyReaderViewPreferences(float fontSize,
                                            float lineSpacing,
                                            int marginH,
                                            int marginV,
                                            int topTextZoneOffsetPx,
                                            int bottomTextZoneOffsetPx,
                                            int leftTextInsetPx,
                                            int rightTextInsetPx,
                                            int overlapLines,
                                            Typeface tf,
                                            int textColor,
                                            int bgColor) {
        activity.lastStatusOffExtraTopPadding = activity.getStableStatusOffTopPaddingPx();
        if (activity.readerPageStatus != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) activity.readerPageStatus.getLayoutParams();
            lp.height = activity.getReaderPageStatusVisualHeight();
            activity.readerPageStatus.setLayoutParams(lp);
            activity.applyPageStatusAlignment(activity.lastReaderTopInset);
        }
        activity.updateReaderContentTopPadding();

        activity.readerView.setLargeTextPartitionMode(activity.largeTextEstimateActive);
        boolean overlapChanged = appliedPagingOverlapLines != Integer.MIN_VALUE
                && appliedPagingOverlapLines != overlapLines;
        activity.readerView.setOverlapLines(overlapLines);
        appliedPagingOverlapLines = overlapLines;
        if (overlapChanged) {
            if (activity.largeTextEstimateActive) {
                activity.scheduleLargeTextExactPageIndexingRestartForUserPageModelChange();
            } else {
                activity.updatePositionLabel();
            }
        }

        if (appliedTopTextZoneOffsetPx != topTextZoneOffsetPx
                || appliedBottomTextZoneOffsetPx != bottomTextZoneOffsetPx
                || appliedLeftTextInsetPx != leftTextInsetPx
                || appliedRightTextInsetPx != rightTextInsetPx) {
            activity.readerView.setTextZoneAdjustments(topTextZoneOffsetPx, bottomTextZoneOffsetPx,
                    leftTextInsetPx, rightTextInsetPx);
            if (activity.largeTextEstimateActive) {
                activity.scheduleLargeTextExactPageIndexingRestart();
            }
            appliedTopTextZoneOffsetPx = topTextZoneOffsetPx;
            appliedBottomTextZoneOffsetPx = bottomTextZoneOffsetPx;
            appliedLeftTextInsetPx = leftTextInsetPx;
            appliedRightTextInsetPx = rightTextInsetPx;
        }

        boolean styleChanged = Float.compare(appliedFontSize, fontSize) != 0
                || Float.compare(appliedLineSpacing, lineSpacing) != 0
                || appliedTextColor != textColor
                || appliedBackgroundColor != bgColor
                || appliedMarginHorizontalPx != marginH
                || appliedMarginVerticalPx != marginV
                || appliedTypeface != tf;

        if (styleChanged) {
            activity.readerView.setReaderStyle(fontSize, lineSpacing, textColor, bgColor, marginH, marginV, tf);
            appliedFontSize = fontSize;
            appliedLineSpacing = lineSpacing;
            appliedTextColor = textColor;
            appliedBackgroundColor = bgColor;
            appliedMarginHorizontalPx = marginH;
            appliedMarginVerticalPx = marginV;
            appliedTypeface = tf;
            if (activity.largeTextEstimateActive) {
                activity.scheduleLargeTextExactPageIndexingRestart();
            }
        }
    }
}
