package com.textview.reader;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.textview.reader.model.Bookmark;
import com.textview.reader.model.Theme;
import com.textview.reader.util.ThemeManager;

final class ReaderDialogStyleController {
    private final ReaderActivity activity;

    ReaderDialogStyleController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

     boolean isLightColor(int color) {
        return UiColorUtils.isLightColor(color);
    }

    boolean isDarkColor(int color) {
        return !isLightColor(color);
    }

    int readableTextColorForBackground(int backgroundColor) {
        return UiColorUtils.readableTextColorForBackground(backgroundColor);
    }

     void syncReaderDialogThemeSnapshot() {
        if (activity.themeManager == null) {
            activity.themeManager = ThemeManager.getInstance(activity);
        }
        Theme theme = activity.themeManager.getActiveTheme();
        if (theme != null) {
            activity.currentReaderBackgroundColor = theme.getBackgroundColor();
            activity.currentReaderTextColor = theme.getTextColor();
        } else {
            activity.currentReaderTextColor = readableTextColorForBackground(activity.currentReaderBackgroundColor);
        }
    }

     int readerDialogBgColor() {
        syncReaderDialogThemeSnapshot();
        // Opaque, but theme-blended.
        // This keeps the Close/OK/Delete area non-transparent while avoiding a harsh flat gray block.
        boolean light = isLightColor(activity.currentReaderBackgroundColor);
        int overlay = light ? Color.WHITE : Color.BLACK;
        float mix = light ? 0.10f : 0.18f;
        int blended = blendColors(activity.currentReaderBackgroundColor, overlay, mix);
        return Color.rgb(Color.red(blended), Color.green(blended), Color.blue(blended));
    }

    int readerDialogPanelColor() {
        syncReaderDialogThemeSnapshot();
        // Match the PDF/EPUB/Word dialog card tone: start from the actual
        // dialog surface and separate cards by blending toward the readable
        // foreground, not by adding a white overlay. This keeps TXT More/Font
        // cards in the same tone family as the document viewers.
        int bg = readerDialogBgColor();
        int fg = readerDialogTextColor(bg);
        int blended = blendColors(bg, fg, isDarkColor(bg) ? 0.10f : 0.08f);
        return Color.rgb(Color.red(blended), Color.green(blended), Color.blue(blended));
    }

     int readerDialogTextColor(int bgColor) {
        syncReaderDialogThemeSnapshot();
        return activity.currentReaderTextColor;
    }

     int readerDialogSubTextColor(int bgColor) {
        syncReaderDialogThemeSnapshot();
        return blendColors(activity.currentReaderBackgroundColor, activity.currentReaderTextColor,
                isDarkColor(activity.currentReaderBackgroundColor) ? 0.72f : 0.64f);
    }

    int strongDialogBorderColor(int bgColor) {
        int fg = readerDialogTextColor(bgColor);

        // Match the PDF/EPUB/Word viewer dialog border tone.  The previous TXT
        // border used a much stronger foreground blend, which made the outline
        // look too heavy and separate from the document-viewer popups.
        return blendColors(bgColor, fg, isDarkColor(bgColor) ? 0.28f : 0.20f);
    }

     TextView makeReaderDialogTitle(String text, int bgColor, int fgColor) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextColor(fgColor);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(activity.dpToPx(18), activity.dpToPx(14), activity.dpToPx(18), activity.dpToPx(12));
        title.setBackgroundColor(Color.TRANSPARENT);
        return title;
    }

    TextView makeReaderActionRow(String text, int fgColor) {
        TextView row = new TextView(activity);
        row.setText(text);
        row.setTextColor(fgColor);
        row.setTextSize(16f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);

        // Match the EPUB/Word/PDF "More" window style: each TXT More row is a
        // separate rounded card, not a flat/bland text row.  The fill and stroke
        // are theme-derived so light themes get a soft gray card and dark themes
        // get a visible but not harsh raised card.
        int panel = readerDialogPanelColor();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panel);
        bg.setCornerRadius(activity.dpToPx(10));
        bg.setStroke(0, Color.TRANSPARENT);
        row.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(48));
        lp.setMargins(0, 0, 0, activity.dpToPx(8));
        row.setLayoutParams(lp);
        return row;
    }

     TextView makeReaderCenteredActionButton(String text, int fgColor) {
        TextView row = makeReaderActionRow(text, fgColor);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setIncludeFontPadding(false);
        row.setPadding(0, 0, 0, 0);
        return row;
    }

    TextView makeReaderFontDialogTitle(String text, int bgColor, int fgColor) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextColor(fgColor);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(18), activity.dpToPx(18), activity.dpToPx(18), activity.dpToPx(14));
        title.setBackgroundColor(Color.TRANSPARENT);
        return title;
    }

    TextView makeReaderFontActionRow(String text, int fgColor) {
        return makeReaderFontActionRow(text, fgColor, false);
    }

    TextView makeReaderFontActionRow(String text, int fgColor, boolean selected) {
        TextView row = new TextView(activity);
        row.setText(text);
        row.setTextColor(fgColor);
        row.setTextSize(16f);
        row.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Keep the rounded row "bubble" used by EPUB/Word, but remove the small
        // radio/circle bubble. Selection is shown by text weight and row outline.
        row.setPadding(activity.dpToPx(18), 0, activity.dpToPx(18), 0);
        row.setCompoundDrawables(null, null, null, null);
        row.setCompoundDrawablePadding(0);

        GradientDrawable bg = new GradientDrawable();
        int panel = readerDialogPanelColor();
        boolean darkPanel = isDarkColor(panel);
        int normalFill = blendColors(panel, fgColor, darkPanel ? 0.055f : 0.035f);
        int selectedFill = blendColors(panel, fgColor, darkPanel ? 0.120f : 0.075f);
        int normalStroke = blendColors(panel, fgColor, darkPanel ? 0.130f : 0.100f);
        int selectedStroke = blendColors(panel, fgColor, darkPanel ? 0.420f : 0.360f);

        // Every font row should look like the EPUB/Word rounded card row, not only
        // the selected row. Selection is shown by stronger text + stronger outline.
        bg.setColor(selected ? selectedFill : normalFill);
        bg.setCornerRadius(activity.dpToPx(10));
        bg.setStroke(1, selected ? selectedStroke : normalStroke);
        row.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(48));
        lp.setMargins(0, 0, 0, activity.dpToPx(8));
        row.setLayoutParams(lp);
        return row;
    }

    void applyOuterBorderToDialogPanel(AlertDialog dialog, int bgColor, int borderColor) {
        if (dialog == null || dialog.getWindow() == null) return;

        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        View decor = dialog.getWindow().getDecorView();
        View panel = decor;

        if (decor instanceof ViewGroup && ((ViewGroup) decor).getChildCount() > 0) {
            panel = ((ViewGroup) decor).getChildAt(0);
        }

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(bgColor);
        panelBg.setCornerRadius(activity.dpToPx(14));
        panelBg.setStroke(Math.max(1, activity.dpToPx(1)), borderColor);

        GradientDrawable foregroundBorder = new GradientDrawable();
        foregroundBorder.setColor(Color.TRANSPARENT);
        foregroundBorder.setCornerRadius(activity.dpToPx(14));
        foregroundBorder.setStroke(Math.max(1, activity.dpToPx(1)), borderColor);

        // No inner padding here. The overlay stroke sits exactly on the panel edge,
        // so any 2dp inset would push the content panel inward and cause a sub-pixel
        // mismatch where the inner rounded fill no longer aligns with the outer
        // rounded outline (the chipping seen at 북마크 / 글꼴 corners).
        panel.setBackground(panelBg);
        panel.setForeground(foregroundBorder);
        panel.setPadding(0, 0, 0, 0);
        panel.setClipToOutline(true);

        if (panel instanceof ViewGroup) {
            ((ViewGroup) panel).setClipChildren(true);
            ((ViewGroup) panel).setClipToPadding(true);
            // Make sure every nested AlertDialog sub-panel (topPanel / contentPanel /
            // buttonPanel) is also clipped to its parent. Without this, the bottom
            // action panel's rectangular fill paints past the rounded corner before
            // the overlay border is drawn, which is visible as a tiny straight
            // edge poking out from behind the curve.
            forceClipChildrenRecursive((ViewGroup) panel);
        }

        decor.addOnLayoutChangeListener((v, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) ->
                redrawDialogOuterBorder(dialog, borderColor));

        redrawDialogOuterBorder(dialog, borderColor);
    }

    void forceClipChildrenRecursive(ViewGroup group) {
        if (group == null) return;
        group.setClipChildren(true);
        group.setClipToPadding(true);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                forceClipChildrenRecursive((ViewGroup) child);
            }
        }
    }

    void redrawDialogOuterBorder(android.app.Dialog dialog, int borderColor) {
    if (dialog == null || dialog.getWindow() == null) return;

    View decor = dialog.getWindow().getDecorView();
    decor.post(() -> {
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return;

        final float density = activity.getResources().getDisplayMetrics().density;
        final float strokePx = Math.max(1f, activity.dpToPx(1));
        final float outerRadiusPx = activity.dpToPx(14);

        Drawable overlayBorder = new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();

            {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokePx);
                paint.setColor(borderColor);
            }

            @Override
            public void draw(Canvas canvas) {
                float half = strokePx / 2f;
                Rect bounds = getBounds();

                // Center the stroke half a stroke inside the window bounds.
                // This makes the OUTER edge of the stroke sit on the dialog/window edge,
                // instead of being pushed inward.
                rect.set(
                        bounds.left + half,
                        bounds.top + half,
                        bounds.right - half,
                        bounds.bottom - half
                );

                float centerRadius = Math.max(0f, outerRadiusPx - half);
                canvas.drawRoundRect(rect, centerRadius, centerRadius, paint);
            }

            @Override public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };

        overlayBorder.setBounds(0, 0, decor.getWidth(), decor.getHeight());

        decor.getOverlay().clear();
        decor.getOverlay().add(overlayBorder);
    });
}

    void styleReaderDialogWindow(AlertDialog dialog, int bgColor, int fgColor, int subColor) {
        if (dialog.getWindow() != null) {
            applyOuterBorderToDialogPanel(dialog, bgColor, strongDialogBorderColor(bgColor));

            android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.dimAmount = 0.16f;
            dialog.getWindow().setAttributes(lp);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        forceDialogButtonPanelBackground(dialog, bgColor);
        styleReaderDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), fgColor);
        styleReaderDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), subColor);
        styleReaderDialogButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), subColor);
    }

    void prepareReaderAlertDialogWindowNoJump(AlertDialog dialog, boolean hideUntilLaidOut) {
        if (dialog == null || dialog.getWindow() == null) return;

        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.CENTER);
        window.setWindowAnimations(0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        lp.width = Math.min(
                activity.getResources().getDisplayMetrics().widthPixels - activity.dpToPx(40),
                activity.dpToPx(420));
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.16f;
        lp.x = 0;
        lp.y = 0;
        window.setAttributes(lp);
        window.setLayout(lp.width, WindowManager.LayoutParams.WRAP_CONTENT);

        if (hideUntilLaidOut) {
            window.getDecorView().setAlpha(0f);
        }
    }

    void prepareReaderCustomThemeDialogWindowNoJump(AlertDialog dialog, boolean hideUntilLaidOut) {
        prepareReaderAlertDialogWindowNoJump(dialog, hideUntilLaidOut);
        if (dialog == null || dialog.getWindow() == null) return;

        android.view.Window window = dialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();

        // Custom reading-theme action/delete dialogs are short confirmation boxes.
        // Keep them visibly narrower than the full Settings-style dialogs.
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int compactWidth = Math.max(activity.dpToPx(240), Math.round(screenWidth * 0.70f));
        lp.width = compactWidth;
        lp.y = activity.dpToPx(44);
        window.setAttributes(lp);
        window.setLayout(compactWidth, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    GradientDrawable positionedReaderDialogBackground(int bgColor) {
        // Same outer card geometry as the PDF/EPUB/Word viewer popups: the
        // border is a thin theme-derived line, not the older heavy TXT outline.
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bgColor);
        drawable.setCornerRadius(activity.dpToPx(14));
        return drawable;
    }

    Drawable positionedReaderDialogBorderOverlay(int bgColor) {
        final int borderColor = strongDialogBorderColor(bgColor);
        final float strokeWidth = Math.max(1f, activity.dpToPx(1));
        final float radius = activity.dpToPx(14);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();

            {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidth);
                paint.setColor(borderColor);
            }

            @Override
            public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                float half = strokeWidth / 2f;
                rect.set(bounds.left + half, bounds.top + half,
                        bounds.right - half, bounds.bottom - half);
                canvas.drawRoundRect(rect, Math.max(0f, radius - half),
                        Math.max(0f, radius - half), paint);
            }

            @Override public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    android.app.Dialog createPositionedReaderDialog(@NonNull View content,
                                                            int bgColor,
                                                            int gravity,
                                                            int yDp,
                                                            int horizontalMarginDp,
                                                            int maxWidthDp,
                                                            boolean adjustResize) {
        return createPositionedReaderDialog(content, bgColor, gravity, yDp,
                horizontalMarginDp, maxWidthDp, 0f, adjustResize);
    }

     android.app.Dialog createNarrowPositionedReaderDialog(@NonNull View content,
                                                                  int bgColor,
                                                                  int gravity,
                                                                  int yDp,
                                                                  float widthFraction,
                                                                  int maxWidthDp,
                                                                  boolean adjustResize) {
        return createPositionedReaderDialog(content, bgColor, gravity, yDp,
                0, maxWidthDp, widthFraction, adjustResize);
    }

    android.app.Dialog createPositionedReaderDialog(@NonNull View content,
                                                            int bgColor,
                                                            int gravity,
                                                            int yDp,
                                                            int horizontalMarginDp,
                                                            int maxWidthDp,
                                                            float widthFraction,
                                                            boolean adjustResize) {
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        FrameLayout outerFrame = new FrameLayout(activity);
        outerFrame.setBackground(positionedReaderDialogBackground(bgColor));
        // Draw the rounded border above the title/list contents. This restores the
        // visible upper-left/upper-right rounded barrier on font dialogs even when
        // the scroll list/header use their own opaque backgrounds.
        outerFrame.setForeground(positionedReaderDialogBorderOverlay(bgColor));
        outerFrame.setClipToOutline(true);
        outerFrame.setClipChildren(true);
        outerFrame.setClipToPadding(true);
        int borderPad = 0;
        outerFrame.setPadding(borderPad, borderPad, borderPad, borderPad);

        content.setBackgroundColor(Color.TRANSPARENT);
        if (content instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) content;
            group.setClipChildren(true);
            group.setClipToPadding(true);
        }

        ScrollView adaptiveScroll = wrapAdaptiveDialogContent(content, outerFrame);

        dialog.setContentView(outerFrame);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(gravity);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int cappedWidth = Math.min(screenWidth - activity.dpToPx(horizontalMarginDp), activity.dpToPx(maxWidthDp));
            if (widthFraction > 0f && widthFraction < 1f) {
                cappedWidth = Math.min(Math.round(screenWidth * widthFraction), activity.dpToPx(maxWidthDp));
            }
            lp.width = Math.max(activity.dpToPx(220), cappedWidth);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.y = activity.dpToPx(yDp);
            lp.dimAmount = 0.16f;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (adjustResize) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        int widthPx = Math.max(activity.dpToPx(220), lpWidthForAdaptiveDialog(horizontalMarginDp, maxWidthDp, widthFraction));
        applyAdaptiveDialogMaxHeight(dialog, adaptiveScroll, widthPx);
        return dialog;
    }

    int lpWidthForAdaptiveDialog(int horizontalMarginDp, int maxWidthDp, float widthFraction) {
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int cappedWidth = Math.min(screenWidth - activity.dpToPx(horizontalMarginDp), activity.dpToPx(maxWidthDp));
        if (widthFraction > 0f && widthFraction < 1f) {
            cappedWidth = Math.min(Math.round(screenWidth * widthFraction), activity.dpToPx(maxWidthDp));
        }
        return cappedWidth;
    }

    ScrollView wrapAdaptiveDialogContent(@NonNull View content, @NonNull ViewGroup outerFrame) {
        return AdaptiveDialogLayoutHelper.wrapAdaptiveContent(activity, content, outerFrame);
    }

    void applyAdaptiveDialogMaxHeight(@NonNull android.app.Dialog dialog, @NonNull View adaptiveView, int widthPx) {
        AdaptiveDialogLayoutHelper.applyAdaptiveMaxHeight(activity, adaptiveView, widthPx);
    }

    boolean shouldApplyAdaptiveDialogMaxHeight(int availableHeightPx) {
        return AdaptiveDialogLayoutHelper.shouldApplyAdaptiveMaxHeight(activity, availableHeightPx);
    }

    int currentVisibleWindowHeightPx() {
        return AdaptiveDialogLayoutHelper.currentVisibleWindowHeightPx(activity);
    }

    void constrainDialogScrollArea(@NonNull View scrollContainer, @NonNull ViewGroup contentList) {
        scrollContainer.setClipToOutline(false);
        if (scrollContainer instanceof ScrollView) {
            ScrollView scrollView = (ScrollView) scrollContainer;
            scrollView.setClipToPadding(true);
            scrollView.setFillViewport(false);
            scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        }
        if (scrollContainer instanceof ViewGroup) {
            ViewGroup scrollGroup = (ViewGroup) scrollContainer;
            scrollGroup.setClipChildren(true);
            scrollGroup.setClipToPadding(true);
        }

        contentList.setClipChildren(true);
        contentList.setClipToPadding(true);
    }

    void updatePositionedReaderDialogYOffset(@NonNull android.app.Dialog dialog, int yDp) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        lp.y = activity.dpToPx(yDp);
        window.setAttributes(lp);
    }

     TextView makeReaderDialogActionText(String label, int textColor, int gravity) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(16f);
        button.setGravity(gravity);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(activity.dpToPx(18), 0, activity.dpToPx(18), 0);
        return button;
    }

     int dialogActionPanelFillColor(int bgColor) {
        // Match EPUB/Word/PDF positioned dialogs: the action area is part of the
        // same rounded card, not a separately tinted bottom zone.
        return bgColor;
    }

     int dialogActionPanelLineColor(int bgColor) {
        return strongDialogBorderColor(bgColor);
    }

    Drawable actionPanelBackground(int fillColor, int lineColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(0f);
        return drawable;
    }

     Drawable positionedActionPanelBackground(int fillColor, int lineColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        // The parent rounded dialog frame clips the lower corners. Keeping this
        // rectangular removes the visible horizontal separator/strip that TXT
        // dialogs had but the other document viewers do not.
        drawable.setCornerRadius(0f);
        return drawable;
    }

    void forceDialogButtonPanelBackground(AlertDialog dialog, int bgColor) {
        if (dialog == null) return;

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

        Button first = positive != null ? positive : (negative != null ? negative : neutral);
        if (first == null) return;

        View panel = first.getParent() instanceof View ? (View) first.getParent() : null;
        if (panel == null) return;

        int lineColor = dialogActionPanelLineColor(bgColor);
        int panelFill = dialogActionPanelFillColor(bgColor);

        // Natural fit: no outer rectangle or horizontal separator. The action
        // buttons sit on the same card background as the rest of the TXT dialog.
        panel.setBackground(actionPanelBackground(panelFill, lineColor));
        panel.setPadding(activity.dpToPx(12), activity.dpToPx(6), activity.dpToPx(12), activity.dpToPx(6));
        panel.setMinimumHeight(activity.dpToPx(50));
        panel.setClipToOutline(false);

        if (panel instanceof ViewGroup) {
            ((ViewGroup) panel).setClipChildren(false);
            ((ViewGroup) panel).setClipToPadding(false);
        }
    }

    void styleReaderDialogButton(Button button, int textColor) {
        if (button == null) return;

        // The border belongs to the whole button panel, not to the text widget.
        // Keep the button itself transparent so the full rectangular panel does not get clipped.
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setTextColor(textColor);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setAllCaps(false);
        button.setMinWidth(activity.dpToPx(72));
        button.setMinimumWidth(activity.dpToPx(72));
        button.setMinHeight(activity.dpToPx(40));
        button.setMinimumHeight(activity.dpToPx(40));
        button.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);

        ViewGroup.LayoutParams rawLp = button.getLayoutParams();
        if (rawLp instanceof LinearLayout.LayoutParams lp) {
            lp.setMargins(activity.dpToPx(2), 0, activity.dpToPx(2), 0);
            button.setLayoutParams(lp);
        }
    }

    void showBookmarkDeleteConfirm(Bookmark bookmark, Runnable afterDelete) {
        final int bg = readerDialogBgColor();
        final int fg = readerDialogTextColor(bg);
        final int sub = readerDialogSubTextColor(bg);
        final int danger = isLightColor(bg) ? Color.rgb(95, 35, 35) : Color.rgb(255, 170, 170);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = makeReaderDialogTitle(activity.getString(R.string.delete_bookmark), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(22), activity.dpToPx(12), activity.dpToPx(22), activity.dpToPx(8));

        TextView message = new TextView(activity);
        String body = bookmark.getFileName() + "\n\n" + bookmark.getDisplayText();
        message.setText(body);
        message.setTextColor(fg);
        message.setTextSize(14f);
        message.setLineSpacing(0f, 1.15f);
        message.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(8));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView warning = new TextView(activity);
        warning.setText(activity.getString(R.string.delete_this_bookmark));
        warning.setTextColor(sub);
        warning.setTextSize(13f);
        warning.setPadding(0, activity.dpToPx(4), 0, 0);
        box.addView(warning, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        actions.setBackground(actionPanelBackground(
                dialogActionPanelFillColor(bg), dialogActionPanelLineColor(bg)));
        actions.setPadding(activity.dpToPx(8), activity.dpToPx(4), activity.dpToPx(8), activity.dpToPx(4));
        TextView cancel = makeReaderDialogActionText(activity.getString(R.string.cancel), sub, Gravity.CENTER);
        TextView delete = makeReaderDialogActionText(activity.getString(R.string.delete), danger, Gravity.CENTER);
        actions.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        actions.addView(delete, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        panel.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = createPositionedReaderDialog(panel, bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 74, 14, 460, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        delete.setOnClickListener(v -> {
            activity.bookmarkManager.deleteBookmark(bookmark.getId());
            if (afterDelete != null) afterDelete.run();
            dialog.dismiss();
        });
        dialog.show();
    }

    void styleSeekBarForReaderDialog(SeekBar seekBar, int bgColor, int accentColor) {
        if (seekBar == null) return;
        int track = isLightColor(bgColor) ? Color.rgb(185, 185, 185) : Color.rgb(78, 78, 78);
        seekBar.setThumbTintList(ColorStateList.valueOf(accentColor));
        seekBar.setProgressTintList(ColorStateList.valueOf(accentColor));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(track));
    }

    void styleCompoundForReaderDialog(CompoundButton button, int bgColor, int fgColor) {
        if (button == null) return;
        button.setTextColor(fgColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int checked = isLightColor(bgColor) ? Color.rgb(72, 72, 72) : Color.rgb(210, 210, 210);
            int unchecked = isLightColor(bgColor) ? Color.rgb(170, 170, 170) : Color.rgb(110, 110, 110);
            button.setButtonTintList(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{checked, unchecked}
            ));
        }
    }

    void styleReaderDialogEditBox(EditText input, int bgColor, int fgColor, int subColor) {
        if (input == null) return;

        input.setTextColor(fgColor);
        input.setHintTextColor(subColor);
        input.setSingleLine(true);

        // Give text a real left gap so "검색할 텍스트" / "정확한 페이지 번호" is not
        // stuck to the beginning of the box.
        input.setPadding(activity.dpToPx(16), 0, activity.dpToPx(16), 0);

        int fill = blendColors(bgColor, fgColor, isLightColor(bgColor) ? 0.025f : 0.035f);
        int stroke = blendColors(bgColor, fgColor, isLightColor(bgColor) ? 0.10f : 0.14f);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(activity.dpToPx(6));
        // Subtle inner boundary only. The strong contrast border belongs to the OUTER dialog box.
        drawable.setStroke(activity.dpToPx(1), stroke);

        input.setBackgroundTintList(null);
        input.setBackground(drawable);
        tintReaderDialogEditHandles(input, bgColor, fgColor);
    }

    void tintReaderDialogEditHandles(EditText input, int bgColor, int fgColor) {
        if (input == null) return;

        boolean lightReaderDialog = isLightColor(bgColor);
        int accent = lightReaderDialog ? Color.rgb(34, 34, 34) : Color.WHITE;

        input.setHighlightColor(blendColors(bgColor, accent, lightReaderDialog ? 0.24f : 0.42f));

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(accent);
            cursor.setSize(Math.max(2, activity.dpToPx(2)), activity.dpToPx(28));
            input.setTextCursorDrawable(cursor);
        }

    }

     EditText makeReaderDialogEditText(String hint, int bgColor, int fgColor, int subColor) {
        // Build the EditText from a ContextThemeWrapper whose theme matches the
        // READER theme's background brightness, NOT the system night mode. The reader
        // has its own theme (ThemeManager.getActiveTheme()), so a Cream-on-Dark-system
        // configuration must still draw a light-mode dialog with a dark caret/handle.
        // Picking the local theme via ContextThemeWrapper makes the caret bar, the
        // selection-handle teardrop, and the floating action-mode toolbar (the
        // "복사 / 번역 / 모두 선택 / 공유" or paste tooltip popup) all inherit a
        // consistent high-contrast palette regardless of which system mode is active.
        int overlay = isLightColor(bgColor)
                ? R.style.ThemeOverlay_TextView_ReaderDialogLight
                : R.style.ThemeOverlay_TextView_ReaderDialogDark;
        android.view.ContextThemeWrapper themed = new android.view.ContextThemeWrapper(activity, overlay);
        EditText input = new EditText(themed);
        input.setHint(hint);
        styleReaderDialogEditBox(input, bgColor, fgColor, subColor);
        return input;
    }

    TextView makeReaderDialogLabel(String text, int color, float sp) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(sp);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

     int blendColors(int bottomColor, int topColor, float topAlpha) {
        return UiColorUtils.blendColors(bottomColor, topColor, topAlpha);
    }

}
