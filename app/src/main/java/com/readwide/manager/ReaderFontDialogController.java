package com.readwide.manager;

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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.readwide.manager.model.ReadingFontOption;
import com.readwide.manager.util.FontManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the TXT reader font-picker UI. This keeps ReaderActivity focused on the
 * text/page continuity path while preserving the original dialog behavior.
 */
final class ReaderFontDialogController {
    private static final String FONT_OPTION_SYSTEM_CURRENT = "system_current";
    private static final int TXT_TOOLBAR_POPUP_Y_DP = 74;

    private final ReaderActivity activity;

    ReaderFontDialogController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void showFontDialog() {
        showFontPickerDialog(getReadingFontOptions());
    }

    private ReadingFontOption[] getReadingFontOptions() {
        List<ReadingFontOption> options = new ArrayList<>();

        options.add(new ReadingFontOption("default",
                "System Sans (recommended)",
                "시스템 산세리프 (추천)"));
        options.add(new ReadingFontOption(FONT_OPTION_SYSTEM_CURRENT,
                "Current system font",
                "현재 시스템 글꼴"));
        options.add(new ReadingFontOption("korean_sans",
                "Korean/System Sans",
                "한글 산세리프"));
        options.add(new ReadingFontOption("korean_serif",
                "Korean/System Serif",
                "한글 명조/세리프"));
        options.add(new ReadingFontOption("serif",
                "Serif",
                "세리프"));
        options.add(new ReadingFontOption("monospace",
                "Monospace",
                "고정폭"));
        options.add(new ReadingFontOption("sans_medium",
                "Sans Medium",
                "산세리프 미디엄"));
        options.add(new ReadingFontOption("sans_condensed",
                "Sans Condensed",
                "산세리프 압축"));
        options.add(new ReadingFontOption("sans_light",
                "Sans Light",
                "산세리프 라이트"));

        addUserFontOptions(options);


        String current = normalizeReadingFontValue(activity.prefs != null ? activity.prefs.getFontFamily() : "default");
        if (!isCuratedReadingFontValue(current) && !containsReadingFontOption(options, current)) {
            if (FontManager.isSystemFamilyValue(current)) {
                String familyName = FontManager.getSystemFamilyName(current);
                options.add(new ReadingFontOption(current,
                        "Saved system font: " + familyName,
                        "저장된 시스템 글꼴: " + familyName));
            } else {
                options.add(new ReadingFontOption(current,
                        "Installed/Custom: " + current,
                        "설치/사용자 글꼴: " + current));
            }
        }

        return options.toArray(new ReadingFontOption[0]);
    }

    private void addUserFontOptions(@NonNull List<ReadingFontOption> options) {
        try {
            FontManager fontManager = FontManager.getInstance();
            if (!fontManager.isScanned()) {
                fontManager.scanFontsSync(activity);
            }

            for (String fontName : fontManager.getUserAddedFontNames(activity)) {
                if (fontName == null || fontName.trim().isEmpty()) continue;
                String value = normalizeReadingFontValue(fontName);
                if (isCuratedReadingFontValue(value) || containsReadingFontOption(options, value)) continue;
                options.add(new ReadingFontOption(value,
                        "Added font: " + fontName,
                        "추가한 글꼴: " + fontName));
            }
        } catch (Throwable ignored) {
            // Font scanning should never block opening the font picker.
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
        String lang = Locale.getDefault().getLanguage();
        return "ko".equalsIgnoreCase(lang) ? option.koreanLabel : option.englishLabel;
    }

    private TextView makeReaderDialogTitle(String text, int bgColor, int fgColor) {
        return activity.dialogStyler().makeReaderDialogTitle(text, bgColor, fgColor);
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

    Typeface resolveReadingTypeface(String fontName) {
        String value = normalizeReadingFontValue(fontName);

        switch (value) {
            case "default":
            case "system_current":
                return Typeface.DEFAULT;
            case "korean_sans":
                // Android's system sans family uses the device CJK fallback chain,
                // so Korean glyphs are handled by Noto/Samsung CJK fonts without
                // exposing dozens of raw system font files to the user.
                return Typeface.create("sans-serif", Typeface.NORMAL);
            case "korean_serif":
                return Typeface.create("serif", Typeface.NORMAL);
            case "serif":
                return Typeface.SERIF;
            case "monospace":
                return Typeface.MONOSPACE;
            case "sans_medium":
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case "sans_condensed":
                return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            case "sans_light":
                return Typeface.create("sans-serif-light", Typeface.NORMAL);
            default:
                // Backward compatibility for a previously saved imported/scanned font.
                return FontManager.getInstance().getTypeface(value);
        }
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
        // The outer font-dialog frame owns the rounded corners and border.
        // Keep the header rectangular so the top border is not doubled or clipped.
        drawable.setCornerRadius(0f);
        return drawable;
    }

    private View fontDialogHeaderSeparator(int bgColor) {
        View separator = new View(activity);
        separator.setBackgroundColor(activity.dialogStyler().dialogActionPanelLineColor(bgColor));
        separator.setClickable(false);
        separator.setFocusable(false);
        return separator;
    }

    private View fontDialogBottomSeparator(int bgColor) {
        View separator = new View(activity);
        separator.setBackgroundColor(activity.dialogStyler().dialogActionPanelLineColor(bgColor));
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
        // The font rows were bleeding over the fixed header because the dialog panel
        // was allowed to draw children outside their own bounds. Keep clipping enabled
        // on the panel and on the scroll viewport; the outer rounded frame/background is
        // drawn by activity.dialogStyler().createPositionedReaderDialog(), so this does not cut the dialog border.
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        scrollClip.setClipToOutline(false);
        if (scrollClip instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) scrollClip;
            group.setClipChildren(true);
            group.setClipToPadding(true);
        }
    }

    private Drawable fontDialogFrameBackground(int bgColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bgColor);
        drawable.setCornerRadius(activity.dpToPx(16));
        return drawable;
    }

    private Drawable fontDialogFrameBorderOverlay(int bgColor) {
        final int borderColor = activity.dialogStyler().strongDialogBorderColor(bgColor);
        // Match the thin outer boundary used by PDF/EPUB/Word dialog frames.
        final float strokeWidth = 1f;
        final float radius = activity.dpToPx(16);
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

    private Drawable fontDialogActionPanelBackground(int bgColor) {
        GradientDrawable drawable = new GradientDrawable();
        // Use the same fill as the main dialog. A different bottom fill created
        // the visible blended strip between the frame and the action bar.
        drawable.setColor(bgColor);
        drawable.setCornerRadius(0f);
        return drawable;
    }

    private android.app.Dialog createReaderFontDialog(@NonNull View content, int bgColor, int maxWidthDp) {
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        FrameLayout outerFrame = new FrameLayout(activity);
        outerFrame.setBackground(fontDialogFrameBackground(bgColor));
        outerFrame.setForeground(fontDialogFrameBorderOverlay(bgColor));
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

        ScrollView adaptiveScroll = activity.dialogStyler().wrapAdaptiveDialogContent(content, outerFrame);

        dialog.setContentView(outerFrame);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int cappedWidth = Math.min(Math.round(screenWidth * 0.85f), activity.dpToPx(maxWidthDp));
            lp.width = Math.max(activity.dpToPx(220), cappedWidth);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.y = activity.dpToPx(TXT_TOOLBAR_POPUP_Y_DP);
            lp.dimAmount = 0.16f;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int cappedWidth = Math.min(Math.round(screenWidth * 0.85f), activity.dpToPx(maxWidthDp));
        activity.dialogStyler().applyAdaptiveDialogMaxHeight(dialog, adaptiveScroll, Math.max(activity.dpToPx(220), cappedWidth));
        return dialog;
    }

    private void showFontPickerDialog(ReadingFontOption[] fontOptions) {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);

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

        TextView title = activity.dialogStyler().makeReaderFontDialogTitle(activity.getString(R.string.select_font), bg, fg);
        title.setBackgroundColor(Color.TRANSPARENT);
        title.setPadding(title.getPaddingLeft(), title.getPaddingTop(),
                title.getPaddingRight(), activity.dpToPx(18));
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(fontDialogHeaderSeparator(bg), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, activity.dpToPx(1))));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.TRANSPARENT);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(8), pad, activity.dpToPx(8));

        ScrollView scroll = new ScrollView(activity);
        FrameLayout scrollClip = makeClippedDialogScrollFrame(scroll, list, bg);
        panel.addView(scrollClip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(activity.dpToPx(360), activity.getResources().getDisplayMetrics().heightPixels / 2)));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(fontDialogActionPanelBackground(bg));
        actionRow.setPadding(activity.dpToPx(30), 0, activity.dpToPx(30), 0);

        TextView addFont = activity.dialogStyler().makeReaderDialogActionText(
                localizedText("Add font", "글꼴 추가"),
                fg,
                Gravity.CENTER_VERTICAL | Gravity.START);
        TextView cancel = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.cancel), fg,
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
                Math.max(1, activity.dpToPx(1))));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(54)));

        android.app.Dialog dialog = createReaderFontDialog(panel, bg, 460);
        preserveFontDialogHeaderBarrier(panel, scrollClip);

        String currentFont = normalizeReadingFontValue(activity.prefs.getFontFamily());
        for (ReadingFontOption option : fontOptions) {
            String label = getReadingFontLabel(option);
            boolean selected = option.value.equals(currentFont);

            TextView row = activity.dialogStyler().makeReaderFontActionRow(label, fg, selected);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                activity.prefs.setFontFamily(option.value);
                activity.applyPreferences();
                activity.updatePositionLabel();
            });
            if (isRemovableUserFontValue(option.value)) {
                row.setOnLongClickListener(v -> {
                    showUserFontRemoveConfirm(option.value, label, () -> {
                        dialog.dismiss();
                        showFontDialog();
                    });
                    return true;
                });
            }
            list.addView(row);
        }

        addFont.setOnClickListener(v -> {
            dialog.dismiss();
            showAllSystemFontsDialog();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showAllSystemFontsDialog() {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

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

        TextView title = activity.dialogStyler().makeReaderFontDialogTitle(
                localizedText("All system fonts", "전체 시스템 글꼴"),
                bg,
                fg);
        title.setBackgroundColor(Color.TRANSPARENT);
        title.setPadding(title.getPaddingLeft(), title.getPaddingTop(),
                title.getPaddingRight(), activity.dpToPx(8));
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = activity.dialogStyler().makeReaderDialogLabel(
                localizedText(
                        "Select a font found from Android/system font folders.",
                        "Android/시스템 글꼴 폴더에서 찾은 글꼴을 선택합니다."),
                sub,
                12f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(activity.dpToPx(18), activity.dpToPx(4), activity.dpToPx(18), activity.dpToPx(16));
        hint.setBackgroundColor(Color.TRANSPARENT);
        header.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(fontDialogHeaderSeparator(bg), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, activity.dpToPx(1))));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.TRANSPARENT);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(8), pad, activity.dpToPx(8));

        ScrollView scroll = new ScrollView(activity);
        FrameLayout scrollClip = makeClippedDialogScrollFrame(scroll, list, bg);
        panel.addView(scrollClip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(activity.dpToPx(420), activity.getResources().getDisplayMetrics().heightPixels / 2)));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(fontDialogActionPanelBackground(bg));
        actionRow.setPadding(activity.dpToPx(30), 0, activity.dpToPx(30), 0);

        TextView cancel = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.cancel), fg,
                Gravity.CENTER_VERTICAL | Gravity.END);
        actionRow.addView(cancel, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));
        panel.addView(fontDialogBottomSeparator(bg), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, activity.dpToPx(1))));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(54)));

        android.app.Dialog dialog = createReaderFontDialog(panel, bg, 460);
        preserveFontDialogHeaderBarrier(panel, scrollClip);

        String currentFont = normalizeReadingFontValue(activity.prefs.getFontFamily());
        if (fontNames.isEmpty()) {
            TextView empty = activity.dialogStyler().makeReaderDialogLabel(
                    localizedText("No readable system fonts found.", "읽을 수 있는 시스템 글꼴을 찾지 못했습니다."),
                    sub,
                    14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(activity.dpToPx(12), activity.dpToPx(16), activity.dpToPx(12), activity.dpToPx(16));
            list.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            for (String fontName : fontNames) {
                if (fontName == null || fontName.trim().isEmpty()) continue;

                String value = normalizeReadingFontValue(fontName);
                String label = fontName;
                boolean selected = value.equals(currentFont);

                TextView row = activity.dialogStyler().makeReaderFontActionRow(label, fg, selected);
                row.setOnClickListener(v -> {
                    try {
                        FontManager.getInstance().addUserFont(activity, fontName);
                    } catch (Throwable ignored) {
                        // Selecting the font should still work even if persisting the shortcut fails.
                    }
                    dialog.dismiss();
                    activity.prefs.setFontFamily(value);
                    activity.applyPreferences();
                    activity.updatePositionLabel();
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
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);
        final int danger = activity.isLightColor(bg) ? Color.rgb(95, 35, 35) : Color.rgb(255, 170, 170);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = makeReaderDialogTitle(localizedText("Remove font", "글꼴 삭제"), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(22), activity.dpToPx(10), activity.dpToPx(22), activity.dpToPx(10));

        TextView message = new TextView(activity);
        String safeLabel = label != null && !label.trim().isEmpty() ? label.trim() : value;
        message.setText(safeLabel + "\n\n" + localizedText(
                "Remove this user-added font from Readwide?",
                "이 사용자 추가 글꼴을 Readwide에서 삭제할까요?")
                + "\n" + localizedText(
                "System fonts and document files are not affected.",
                "시스템 글꼴과 문서 파일은 영향받지 않습니다."));
        message.setTextColor(fg);
        message.setTextSize(14f);
        message.setLineSpacing(0f, 1.15f);
        message.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(8));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        actions.setBackground(activity.dialogStyler().actionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg), activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actions.setPadding(activity.dpToPx(8), activity.dpToPx(4), activity.dpToPx(8), activity.dpToPx(4));
        TextView cancel = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.cancel), sub, Gravity.CENTER);
        TextView delete = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.delete), danger, Gravity.CENTER);
        actions.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        actions.addView(delete, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
        panel.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = activity.dialogStyler().createPositionedReaderDialog(panel, bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 74, 14, 460, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
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
                if (normalizeReadingFontValue(activity.prefs.getFontFamily()).equals(normalizeReadingFontValue(value))) {
                    activity.prefs.setFontFamily("default");
                    activity.applyPreferences();
                    activity.updatePositionLabel();
                }
                ShortToast.show(activity, localizedText("Font removed", "글꼴을 삭제했습니다"));
                dialog.dismiss();
                if (afterRemove != null) afterRemove.run();
            } else {
                ShortToast.show(activity, localizedText(
                        "This font cannot be removed from inside the app.",
                        "이 글꼴은 앱 안에서 삭제할 수 없습니다."));
            }
        });
        dialog.show();
    }

    private String localizedText(String english, String korean) {
        return "ko".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? korean : english;
    }


}
