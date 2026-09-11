package com.readwide.manager;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;

import com.readwide.manager.util.EpubFontPreferenceMath;
import com.readwide.manager.util.FontManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DocumentFontDialogController {
    private static final String FONT_PREFIX = "/font/";
    private static final String DOCUMENT_FONT_DEFAULT = EpubFontPreferenceMath.BOOK_FONT;
    private static final String FONT_OPTION_SYSTEM_CURRENT = "system_current";

    private final DocumentPageActivity activity;

    DocumentFontDialogController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    void showDocumentFontDialog() {
        showDocumentFontPickerDialog(getReadingFontOptions());
    }

    private void showDocumentFontPickerDialog(List<ReadingFontOption> fontOptions) {
        final int bg = dialogBg();
        final int fg = dialogFg();

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(fontDialogHeaderBackground(bg));
        header.setClipChildren(true);
        header.setClipToPadding(true);

        TextView title = makeDocumentFontDialogTitle(getString(R.string.select_font), bg, fg);
        title.setBackgroundColor(Color.TRANSPARENT);
        title.setPadding(title.getPaddingLeft(), title.getPaddingTop(),
                title.getPaddingRight(), dpToPx(18));
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(fontDialogHeaderSeparator(bg), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dpToPx(1))));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.TRANSPARENT);
        int pad = dpToPx(14);
        list.setPadding(pad, dpToPx(8), pad, dpToPx(8));

        ScrollView scroll = new ScrollView(activity);
        FrameLayout scrollClip = makeClippedDialogScrollFrame(scroll, list, bg);
        panel.addView(scrollClip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dpToPx(360), activity.getResources().getDisplayMetrics().heightPixels / 2)));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(positionedActionPanelBackground(
                dialogActionPanelFillColor(bg),
                dialogActionPanelLineColor(bg)));
        actionRow.setPadding(dpToPx(30), 0, dpToPx(30), 0);

        TextView addFont = makeDocumentDialogActionText(
                getString(R.string.select_font),
                fg,
                Gravity.CENTER_VERTICAL | Gravity.START);
        TextView cancel = makeDocumentDialogActionText(getString(R.string.cancel), fg,
                Gravity.CENTER_VERTICAL | Gravity.END);

        actionRow.addView(addFont, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));
        actionRow.addView(cancel, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));
        panel.addView(fontDialogBottomSeparator(bg), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dpToPx(1))));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(54)));

        android.app.Dialog dialog = createDocumentFontDialog(panel, 460);
        preserveFontDialogHeaderBarrier(panel, scrollClip);

        String currentFont = currentDocumentFontSelection();
        for (ReadingFontOption option : fontOptions) {
            String label = getReadingFontLabel(option);
            boolean selected = option.value.equals(currentFont);

            TextView row = makeDocumentFontActionRow(label, fg, selected);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                setDocumentFontSelection(option.value);
            });
            if (isRemovableUserFontValue(option.value)) {
                row.setOnLongClickListener(v -> {
                    showUserFontRemoveConfirm(option.value, label, () -> {
                        dialog.dismiss();
                        showDocumentFontDialog();
                    });
                    return true;
                });
            }
            list.addView(row);
        }

        addFont.setOnClickListener(v -> {
            dialog.dismiss();
            showAllDocumentFontsDialog();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showAllDocumentFontsDialog() {
        final int bg = dialogBg();
        final int fg = dialogFg();
        final int sub = dialogSub();

        List<String> fontNames = new ArrayList<>();
        try {
            FontManager fontManager = FontManager.getInstance();
            if (!fontManager.isScanned()) fontManager.scanFontsSync(activity);
            fontNames.addAll(fontManager.getFontNames());
        } catch (Throwable ignored) {
            // Keep the dialog usable even if a device blocks one of the font paths.
        }

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(fontDialogHeaderBackground(bg));
        header.setClipChildren(true);
        header.setClipToPadding(true);

        TextView title = makeDocumentFontDialogTitle(
                getString(R.string.select_font),
                bg,
                fg);
        title.setBackgroundColor(Color.TRANSPARENT);
        title.setPadding(title.getPaddingLeft(), title.getPaddingTop(),
                title.getPaddingRight(), dpToPx(8));
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = makeDocumentDialogLabel(
                getString(R.string.font_picker_hint),
                sub,
                12f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dpToPx(18), dpToPx(4), dpToPx(18), dpToPx(16));
        hint.setBackgroundColor(Color.TRANSPARENT);
        header.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(fontDialogHeaderSeparator(bg), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dpToPx(1))));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.TRANSPARENT);
        int pad = dpToPx(14);
        list.setPadding(pad, dpToPx(8), pad, dpToPx(8));

        ScrollView scroll = new ScrollView(activity);
        FrameLayout scrollClip = makeClippedDialogScrollFrame(scroll, list, bg);
        panel.addView(scrollClip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dpToPx(420), activity.getResources().getDisplayMetrics().heightPixels / 2)));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(positionedActionPanelBackground(
                dialogActionPanelFillColor(bg),
                dialogActionPanelLineColor(bg)));
        actionRow.setPadding(dpToPx(30), 0, dpToPx(30), 0);

        TextView cancel = makeDocumentDialogActionText(getString(R.string.cancel), fg,
                Gravity.CENTER_VERTICAL | Gravity.END);
        actionRow.addView(cancel, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));
        panel.addView(fontDialogBottomSeparator(bg), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dpToPx(1))));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(54)));

        android.app.Dialog dialog = createDocumentFontDialog(panel, 460);
        preserveFontDialogHeaderBarrier(panel, scrollClip);

        String currentFont = currentDocumentFontSelection();

        TextView importRow = makeDocumentFontActionRow(
                getString(R.string.font_add_file), fg, false);
        importRow.setOnClickListener(v -> {
            dialog.dismiss();
            activity.launchDocumentFontImport();
        });
        list.addView(importRow);

        if (fontNames.isEmpty()) {
            TextView empty = makeDocumentDialogLabel(
                    getString(R.string.font_no_system_found),
                    sub,
                    14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dpToPx(12), dpToPx(16), dpToPx(12), dpToPx(16));
            list.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            for (String fontName : fontNames) {
                if (fontName == null || fontName.trim().isEmpty()) continue;

                String value = normalizeReadingFontValue(fontName);
                String label = fontName;
                boolean selected = value.equals(currentFont);

                TextView row = makeDocumentFontActionRow(label, fg, selected);
                row.setOnClickListener(v -> {
                    try {
                        FontManager.getInstance().addUserFont(activity, fontName);
                    } catch (Throwable ignored) {
                        // Selecting the font should still work even if persisting the shortcut fails.
                    }
                    dialog.dismiss();
                    setDocumentFontSelection(value);
                });
                list.addView(row);
            }
        }

        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private boolean isRemovableUserFontValue(String value) {
        try {
            FontManager fontManager = FontManager.getInstance();
            if (!fontManager.isScanned()) fontManager.scanFontsSync(activity);
            return fontManager.isRemovableUserFont(activity, value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showUserFontRemoveConfirm(String value, String label, Runnable afterRemove) {
        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.font_remove_title)));

        TextView message = new TextView(activity);
        String safeLabel = label != null && !label.trim().isEmpty() ? label.trim() : value;
        message.setText(activity.getString(R.string.font_remove_confirm, safeLabel));
        message.setTextColor(dialogSub());
        message.setTextSize(14f);
        message.setLineSpacing(0f, 1.15f);
        message.setPadding(0, 0, 0, dpToPx(12));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        LinearLayout actions = new LinearLayout(activity);
        actions.setTag("dialog_actions");
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        actions.setPadding(0, dpToPx(8), 0, 0);

        TextView cancel = new TextView(activity);
        cancel.setText(getString(R.string.cancel));
        cancel.setTextColor(dialogSub());
        cancel.setTextSize(16f);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setPadding(dpToPx(14), 0, dpToPx(14), 0);

        TextView delete = new TextView(activity);
        delete.setText(getString(R.string.delete));
        delete.setTextColor(!activity.isDarkColor(dialogBg()) ? Color.rgb(95, 35, 35) : Color.rgb(255, 170, 170));
        delete.setTextSize(16f);
        delete.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        delete.setGravity(android.view.Gravity.CENTER);
        delete.setPadding(dpToPx(14), 0, dpToPx(14), 0);

        actions.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(46)));
        actions.addView(delete, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(46)));
        box.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        cancel.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        delete.setOnClickListener(v -> {
            boolean removed = false;
            try {
                FontManager fontManager = FontManager.getInstance();
                if (!fontManager.isScanned()) fontManager.scanFontsSync(activity);
                removed = fontManager.removeUserFont(activity, value);
            } catch (Throwable ignored) {
                removed = false;
            }

            if (removed) {
                if (normalizeReadingFontValue(currentDocumentFontSelection()).equals(normalizeReadingFontValue(value))) {
                    setDocumentFontSelection("default");
                }
                ShortToast.show(activity, R.string.font_removed);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                if (afterRemove != null) afterRemove.run();
            } else {
                ShortToast.show(activity, R.string.font_remove_failed);
            }
        });

        dialogRef[0] = createStablePositionedDialog(box, DocumentPageActivity.DOCUMENT_TOOLBAR_POPUP_Y_DP, false, false);
        dialogRef[0].show();
    }

    private boolean shouldOfferDocumentDefaultFont() {
        return "EPUB".equals(activity.docType)
                || ("Word".equals(activity.docType) && activity.wordHasDocumentFont);
    }

    private String currentDocumentFontSelection() {
        if ("EPUB".equals(activity.docType)) {
            return EpubFontPreferenceMath.resolveActiveSelection(
                    null,
                    activity.prefs != null
                            ? activity.prefs.getEpubFontFamily()
                            : DOCUMENT_FONT_DEFAULT);
        }
        if (DOCUMENT_FONT_DEFAULT.equals(activity.documentFontOverride)) return DOCUMENT_FONT_DEFAULT;
        if (activity.documentFontOverride != null && !activity.documentFontOverride.trim().isEmpty()) {
            return normalizeReadingFontValue(activity.documentFontOverride);
        }
        if (shouldOfferDocumentDefaultFont()) return DOCUMENT_FONT_DEFAULT;
        return normalizeReadingFontValue(activity.prefs != null ? activity.prefs.getFontFamily() : "default");
    }

    private void setDocumentFontSelection(String value) {
        if ("EPUB".equals(activity.docType)) {
            String normalized = normalizeReadingFontValue(value);
            // EPUB uses one dedicated persisted preference. Keeping a second
            // activity-local override made the menu display the selected font
            // while a newly opened book silently reverted to its old default.
            activity.documentFontOverride = null;
            if (activity.prefs != null) activity.prefs.setEpubFontFamily(normalized);
            activity.lastAppliedDocumentThemeSignature = activity.documentThemeSignature();
            refreshCurrentDocumentFont();
            return;
        }
        if (DOCUMENT_FONT_DEFAULT.equals(value)) {
            activity.documentFontOverride = DOCUMENT_FONT_DEFAULT;
        } else {
            String normalized = normalizeReadingFontValue(value);
            activity.documentFontOverride = normalized;
            if (activity.prefs != null) activity.prefs.setFontFamily(normalized);
        }
        refreshCurrentDocumentFont();
    }

    void applyImportedDocumentFont(@NonNull String importedName) {
        try {
            FontManager.getInstance().addUserFont(activity, importedName);
        } catch (Throwable ignored) {
            // The font is still usable even when the picker shortcut fails to persist.
        }
        setDocumentFontSelection(importedName);
        showDocumentFontDialog();
    }

    private void constrainDialogScrollArea(@NonNull View scrollContainer, @NonNull ViewGroup contentList) {
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

    private Drawable fontDialogHeaderBackground(int bgColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bgColor);
        // The outer font-dialog frame owns the rounded corners/border. Making
        // the header rectangular prevents the visible double-border/blended edge.
        drawable.setCornerRadius(0f);
        return drawable;
    }

    private View fontDialogHeaderSeparator(int bgColor) {
        View separator = new View(activity);
        separator.setBackgroundColor(dialogActionPanelLineColor(bgColor));
        separator.setClickable(false);
        separator.setFocusable(false);
        return separator;
    }

    private FrameLayout makeClippedDialogScrollFrame(@NonNull ScrollView scroll,
                                                     @NonNull ViewGroup contentList,
                                                     int bgColor) {
        constrainDialogScrollArea(scroll, contentList);

        FrameLayout clipFrame = new FrameLayout(activity);
        clipFrame.setBackgroundColor(bgColor);
        clipFrame.setClipChildren(true);
        clipFrame.setClipToPadding(true);
        clipFrame.setOverScrollMode(View.OVER_SCROLL_NEVER);

        scroll.setBackgroundColor(bgColor);
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setPadding(0, 0, 0, 0);

        contentList.setBackgroundColor(bgColor);
        contentList.setClipChildren(true);
        contentList.setClipToPadding(true);
        scroll.addView(contentList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        clipFrame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return clipFrame;
    }

    private void preserveFontDialogHeaderBarrier(@NonNull ViewGroup panel, @NonNull View scrollClip) {
        panel.setClipChildren(true);
        panel.setClipToPadding(true);
        scrollClip.setClipToOutline(false);
        if (scrollClip instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) scrollClip;
            group.setClipChildren(true);
            group.setClipToPadding(true);
        }
    }

    private TextView makeDocumentFontDialogTitle(String text, int bgColor, int fgColor) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextColor(fgColor);
        title.setTextSize(22f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(14));
        return title;
    }

    private TextView makeDocumentDialogLabel(String text, int color, float sp) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setLineSpacing(0f, 1.05f);
        return tv;
    }

    private TextView makeDocumentDialogActionText(String text, int fgColor, int gravity) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(fgColor);
        tv.setTextSize(16f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(gravity);
        tv.setSingleLine(true);
        tv.setPadding(0, 0, 0, 0);
        return tv;
    }

    private TextView makeDocumentFontActionRow(String text, int fgColor) {
        return makeDocumentFontActionRow(text, fgColor, false);
    }

    private TextView makeDocumentFontActionRow(String text, int fgColor, boolean selected) {
        TextView row = new TextView(activity);
        row.setText(text);
        row.setTextColor(fgColor);
        row.setTextSize(16f);
        row.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Keep the rounded row background but remove the radio/circle icon.
        // This matches the intended TXT/EPUB/Word font picker style.
        row.setPadding(dpToPx(18), 0, dpToPx(18), 0);
        row.setCompoundDrawables(null, null, null, null);
        row.setCompoundDrawablePadding(0);
        GradientDrawable bg = new GradientDrawable();
        int panel = dialogPanel();
        boolean darkPanel = activity.isDarkColor(panel);
        int normalFill = activity.blendColors(panel, fgColor, darkPanel ? 0.055f : 0.035f);
        int selectedFill = activity.blendColors(panel, fgColor, darkPanel ? 0.120f : 0.075f);
        int normalStroke = activity.blendColors(panel, fgColor, darkPanel ? 0.130f : 0.100f);
        int selectedStroke = activity.blendColors(panel, fgColor, darkPanel ? 0.420f : 0.360f);

        // Keep every font row as a rounded card. Selection is indicated only by
        // stronger text weight and outline; no radio/circle icon is used.
        bg.setColor(selected ? selectedFill : normalFill);
        bg.setCornerRadius(dpToPx(10));
        bg.setStroke(1, selected ? selectedStroke : normalStroke);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48));
        lp.setMargins(0, 0, 0, dpToPx(8));
        row.setLayoutParams(lp);
        return row;
    }

    private GradientDrawable positionedActionPanelBackground(int fill, int line) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        // Let the outer rounded frame clip the bottom corners. This removes the
        // extra color-blend strip between the border and the bottom action row.
        drawable.setCornerRadius(0f);
        return drawable;
    }

    private View fontDialogBottomSeparator(int bgColor) {
        View separator = new View(activity);
        separator.setBackgroundColor(dialogActionPanelLineColor(bgColor));
        separator.setClickable(false);
        separator.setFocusable(false);
        return separator;
    }

    private int dialogActionPanelFillColor(int bgColor) {
        // Keep the bottom action panel the same color as the main font dialog.
        // A separate panel color created a visible blended strip near the bottom border.
        return bgColor;
    }

    private int dialogActionPanelLineColor(int bgColor) {
        return activity.readerLine;
    }

    private Drawable fontDialogOuterBackground(int bgColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bgColor);
        drawable.setCornerRadius(dpToPx(16));
        return drawable;
    }

    private Drawable fontDialogOuterBorderOverlay(int bgColor) {
        final int borderColor = dialogActionPanelLineColor(bgColor);
        final float strokeWidth = 1f;
        final float radius = dpToPx(16);
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

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private android.app.Dialog createDocumentFontDialog(@NonNull View content, int maxWidthDp) {
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        final int bg = dialogBg();

        FrameLayout outerFrame = new FrameLayout(activity);
        outerFrame.setBackground(fontDialogOuterBackground(bg));
        outerFrame.setForeground(fontDialogOuterBorderOverlay(bg));
        outerFrame.setClipChildren(true);
        outerFrame.setClipToPadding(true);
        outerFrame.setClipToOutline(true);
        outerFrame.setPadding(0, 0, 0, 0);

        content.setBackgroundColor(Color.TRANSPARENT);
        if (content instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) content;
            group.setClipChildren(true);
            group.setClipToPadding(true);
        }

        outerFrame.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(outerFrame);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int cappedWidth = Math.min(Math.round(screenWidth * 0.85f), dpToPx(maxWidthDp));
            lp.width = Math.max(dpToPx(220), cappedWidth);
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.y = dpToPx(DocumentPageActivity.DOCUMENT_TOOLBAR_POPUP_Y_DP);
            lp.dimAmount = 0.16f;
            window.setAttributes(lp);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return dialog;
    }


    private TextView makeDialogActionRow(String text, Runnable action) {
        TextView row = new TextView(activity);
        row.setText(text);
        row.setTextColor(dialogFg());
        row.setTextSize(16f);
        row.setGravity(android.view.Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dialogPanel());
        bg.setCornerRadius(dpToPx(10));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48));
        lp.setMargins(0, 0, 0, dpToPx(8));
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void refreshCurrentDocumentFont() {
        activity.clearDocumentEdgeArm();
        if (activity.hasValidCurrentDocumentPage()) {
            activity.showPage(activity.currentPage, 0);
        }
    }

    private static final class ReadingFontOption {
        final String value;
        final String label;

        ReadingFontOption(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    private List<ReadingFontOption> getReadingFontOptions() {
        List<ReadingFontOption> options = new ArrayList<>();
        if (shouldOfferDocumentDefaultFont()) {
            options.add(new ReadingFontOption(DOCUMENT_FONT_DEFAULT, getString(R.string.epub_font_book)));
        }
        options.add(new ReadingFontOption("default", getString(R.string.epub_font_system_sans)));
        options.add(new ReadingFontOption(FONT_OPTION_SYSTEM_CURRENT, getString(R.string.epub_font_current_system)));
        options.add(new ReadingFontOption("korean_sans", getString(R.string.epub_font_korean_sans)));
        options.add(new ReadingFontOption("korean_serif", getString(R.string.epub_font_korean_serif)));
        options.add(new ReadingFontOption("serif", getString(R.string.epub_font_serif)));
        options.add(new ReadingFontOption("monospace", getString(R.string.epub_font_monospace)));
        options.add(new ReadingFontOption("sans_medium", getString(R.string.epub_font_sans_medium)));
        options.add(new ReadingFontOption("sans_condensed", getString(R.string.epub_font_sans_condensed)));
        options.add(new ReadingFontOption("sans_light", getString(R.string.epub_font_sans_light)));
        addDocumentUserFontOptions(options);

        String current = currentDocumentFontSelection();
        if (!DOCUMENT_FONT_DEFAULT.equals(current) && !isCuratedReadingFontValue(current) && !containsReadingFontOption(options, current)) {
            if (FontManager.isSystemFamilyValue(current)) {
                String familyName = FontManager.getSystemFamilyName(current);
                options.add(new ReadingFontOption(current, familyName));
            } else {
                options.add(new ReadingFontOption(current, current));
            }
        }
        return options;
    }

    private void addDocumentUserFontOptions(@NonNull List<ReadingFontOption> options) {
        try {
            FontManager fontManager = FontManager.getInstance();
            if (!fontManager.isScanned()) fontManager.scanFontsSync(activity);

            for (String fontName : fontManager.getUserAddedFontNames(activity)) {
                if (fontName == null || fontName.trim().isEmpty()) continue;
                String value = normalizeReadingFontValue(fontName);
                if (isCuratedReadingFontValue(value) || containsReadingFontOption(options, value)) continue;
                options.add(new ReadingFontOption(value, fontName));
            }
        } catch (Throwable ignored) {
            // Font scanning should not block the Word/EPUB More menu.
        }
    }

    private boolean containsReadingFontOption(@NonNull List<ReadingFontOption> options, String value) {
        for (ReadingFontOption option : options) {
            if (option.value.equals(value)) return true;
        }
        return false;
    }

    private boolean isCuratedReadingFontValue(String value) {
        switch (normalizeReadingFontValue(value)) {
            case "default":
            case DOCUMENT_FONT_DEFAULT:
            case "system_current":
            case "korean_sans":
            case "korean_serif":
            case "serif":
            case "monospace":
            case "sans_medium":
            case "sans_condensed":
            case "sans_light":
                return true;
            default:
                return false;
        }
    }

    private String getReadingFontLabel(@NonNull ReadingFontOption option) {
        return option.label;
    }

    private String normalizeReadingFontValue(String fontName) {
        if (fontName == null || fontName.trim().isEmpty()) return "default";
        String trimmed = fontName.trim();
        if (FontManager.isSystemFamilyValue(trimmed)) return trimmed;
        switch (trimmed) {
            case "Default (Sans-serif)":
            case "DEFAULT":
                return "default";
            case "Serif":
            case "SERIF":
                return "serif";
            case "Monospace":
            case "MONOSPACE":
                return "monospace";
            case "default":
            case DOCUMENT_FONT_DEFAULT:
            case "system_current":
            case "korean_sans":
            case "korean_serif":
            case "serif":
            case "monospace":
            case "sans_medium":
            case "sans_condensed":
            case "sans_light":
                return trimmed;
            default:
                return trimmed;
        }
    }

    String buildDocumentFontCss() {
        activity.selectedDocumentFontFile = null;
        String value = currentDocumentFontSelection();
        if (DOCUMENT_FONT_DEFAULT.equals(value)) {
            if ("Word".equals(activity.docType)) return wordDefaultFontRule();
            return "";
        }
        String fallback = documentFontFallbackCss(value);
        String family = fallback;
        String filePath = resolveDocumentFontFilePath(value);
        if (filePath != null) {
            activity.selectedDocumentFontFile = new File(filePath);
            family = "'TextViewSelectedDocumentFont', " + fallback;
            return "@font-face{font-family:'TextViewSelectedDocumentFont';src:url('https://"
                    + activity.localDocumentHost() + FONT_PREFIX + "selected');}" +
                    documentFontRule(family);
        }
        if (FontManager.isSystemFamilyValue(value)) {
            String name = FontManager.getSystemFamilyName(value);
            if (!name.isEmpty()) family = "'" + cssQuote(name) + "', " + fallback;
        } else if (!isCuratedReadingFontValue(value)) {
            family = "'" + cssQuote(value) + "', " + fallback;
        }
        return documentFontRule(family);
    }

    private String documentFontRule(String family) {
        // EPUB publisher styles commonly use class-qualified !important rules.
        // Give an explicit user font enough specificity to win those rules while
        // still leaving fixed-layout/image pages untouched (they bypass theme CSS).
        String selector = "html body:not(#rw-font-force),"
                + "html body:not(#rw-font-force) p,"
                + "html body:not(#rw-font-force) div,"
                + "html body:not(#rw-font-force) span,"
                + "html body:not(#rw-font-force) td,"
                + "html body:not(#rw-font-force) th,"
                + "html body:not(#rw-font-force) li,"
                + "html body:not(#rw-font-force) pre,"
                + "html body:not(#rw-font-force) h1,"
                + "html body:not(#rw-font-force) h2,"
                + "html body:not(#rw-font-force) h3,"
                + "html body:not(#rw-font-force) h4,"
                + "html body:not(#rw-font-force) h5,"
                + "html body:not(#rw-font-force) h6,"
                + "html body:not(#rw-font-force) a,"
                + "html body:not(#rw-font-force) blockquote,"
                + "html body:not(#rw-font-force) em,"
                + "html body:not(#rw-font-force) strong,"
                + "html body:not(#rw-font-force) ruby,"
                + "html body:not(#rw-font-force) rt";
        return selector + "{font-family:" + family + " !important;}";
    }

    private String wordDefaultFontRule() {
        if (activity.wordDefaultFontFamily == null || activity.wordDefaultFontFamily.trim().isEmpty()) return "";
        String family = "'" + cssQuote(activity.wordDefaultFontFamily.trim()) + "', " + wordFontFallbackFamily(activity.wordDefaultFontFamily);
        return "body,.page,p,div,span,td,th,li,pre{font-family:" + family + ";}";
    }

    private String wordFontFallbackFamily(String family) {
        String lower = family == null ? "" : family.toLowerCase(Locale.US);
        if (lower.contains("serif") || lower.contains("명조") || lower.contains("times") || lower.contains("batang")) {
            return "serif";
        }
        if (lower.contains("mono") || lower.contains("courier") || lower.contains("consolas")) {
            return "monospace";
        }
        return "sans-serif";
    }

    private String documentFontFallbackCss(String value) {
        switch (normalizeReadingFontValue(value)) {
            case "serif":
            case "korean_serif":
                return "serif";
            case "monospace":
                return "monospace";
            case "sans_medium":
                return "'sans-serif-medium', sans-serif";
            case "sans_condensed":
                return "'sans-serif-condensed', sans-serif";
            case "sans_light":
                return "'sans-serif-light', sans-serif";
            case "default":
            case DOCUMENT_FONT_DEFAULT:
            case "system_current":
            case "korean_sans":
            default:
                return "sans-serif";
        }
    }

    private String resolveDocumentFontFilePath(String value) {
        if (isCuratedReadingFontValue(value) || FontManager.isSystemFamilyValue(value)) return null;
        try {
            FontManager fontManager = FontManager.getInstance();
            if (!fontManager.isScanned()) fontManager.scanFontsSync(activity);
            String path = fontManager.getFontPathForName(value);
            if (path != null && new File(path).isFile()) return path;
        } catch (Throwable ignored) {
            // Fall back to CSS family name if the font file is not directly readable.
        }
        return null;
    }

    WebResourceResponse interceptSelectedDocumentFont() {
        File fontFile = activity.selectedDocumentFontFile;
        if (fontFile == null || !fontFile.isFile()) return null;
        try {
            return new WebResourceResponse(
                    activity.mimeForPath(fontFile.getName()),
                    "UTF-8",
                    new FileInputStream(fontFile));
        } catch (IOException e) {
            return null;
        }
    }

    private String cssQuote(String text) {
        return CssUtils.cssQuote(text);
    }

    private String getString(int resId) {
        return activity.getString(resId);
    }

    private int dpToPx(float dp) {
        return activity.dpToPx(dp);
    }

    private int dialogBg() {
        return activity.dialogBg();
    }

    private int dialogPanel() {
        return activity.dialogPanel();
    }

    private int dialogFg() {
        return activity.dialogFg();
    }

    private int dialogSub() {
        return activity.dialogSub();
    }

    private LinearLayout makeDialogBox() {
        return activity.makeDialogBox();
    }

    private TextView makeDialogTitle(String text) {
        return activity.makeDialogTitle(text);
    }

    private void addDialogBottomActions(LinearLayout box, String primaryText, Runnable primaryAction) {
        activity.addDialogBottomActions(box, primaryText, primaryAction);
    }

    private android.app.Dialog createStablePositionedDialog(@NonNull View content, int yDp, boolean allowImeLift, boolean bookmarkStyle) {
        return activity.createStablePositionedDialog(content, yDp, allowImeLift, bookmarkStyle);
    }

}
