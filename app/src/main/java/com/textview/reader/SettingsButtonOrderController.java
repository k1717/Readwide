package com.textview.reader;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Settings UI for ordering main filter chips and viewer bottom buttons. */
final class SettingsButtonOrderController {
    private static final int TXT_DEFAULT_FIRST_VISIBLE_INDEX = 2;
    private static final int TXT_DEFAULT_VISIBLE_SLOT_COUNT = 5;

    private final SettingsActivity activity;

    SettingsButtonOrderController(@NonNull SettingsActivity activity) {
        this.activity = activity;
    }

    void setupButtonOrderSettings() {
        bind(R.id.button_order_main_filters, ButtonOrderManager.GROUP_MAIN_FILTERS, R.string.button_order_main_filters_title);
        bind(R.id.button_order_txt_reader, ButtonOrderManager.GROUP_TXT_READER, R.string.button_order_txt_reader_title);
        bind(R.id.button_order_document_viewer, ButtonOrderManager.GROUP_DOCUMENT_VIEWER, R.string.button_order_document_viewer_title);
        bind(R.id.button_order_pdf_viewer, ButtonOrderManager.GROUP_PDF_VIEWER, R.string.button_order_pdf_viewer_title);
    }

    private void bind(int viewId, @NonNull String group, int titleRes) {
        View view = activity.findViewById(viewId);
        if (view == null) return;
        view.setOnClickListener(v -> showOrderDialog(group, titleRes));
    }

    private void showOrderDialog(@NonNull String group, int titleRes) {
        Dialog dialog = activity.createRoundedSettingsDialog();
        LinearLayout panel = activity.createRoundedSettingsDialogPanel();
        int text = activity.dialogTextColor();
        int sub = activity.dialogSubTextColor();
        int outline = activity.dialogOutlineColor();
        int rowBg = activity.dialogRowBackgroundColor();
        int visibleSlotBg = resolveVisibleSlotBackground(rowBg, text);
        int visibleSlotStroke = resolveVisibleSlotStroke(outline, text);
        int visibleSlotText = text;
        int visibleSlotSub = sub;

        List<ButtonOrderManager.Item> working = new ArrayList<>(ButtonOrderManager.orderedItems(activity.prefs, group));
        panel.addView(activity.makeSettingsDialogTitle(activity.getString(titleRes), text));
        panel.addView(activity.makeSettingsDialogMessage(activity.getString(R.string.button_order_dialog_message), sub));

        if (ButtonOrderManager.GROUP_TXT_READER.equals(group)) {
            TextView hint = makeTxtVisibleHint(text);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            hintLp.setMargins(0, 0, 0, activity.dpToPx(8));
            panel.addView(hint, hintLp);
        }

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);

        ScrollView listScroll = makeBoundedListScrollView();
        listScroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollLp.setMargins(0, 0, 0, activity.dpToPx(6));
        panel.addView(listScroll, scrollLp);

        Runnable[] render = new Runnable[1];
        render[0] = () -> renderOrderRows(group, list, working, rowBg, text, sub, outline,
                visibleSlotBg, visibleSlotStroke, visibleSlotText, visibleSlotSub, render[0]);
        render[0].run();

        MaterialButton save = activity.makeTextRuleDialogButton(activity.getString(R.string.save), text);
        save.setOnClickListener(v -> {
            ButtonOrderManager.saveOrder(activity.prefs, group, working);
            ShortToast.show(activity, R.string.button_order_saved);
            dialog.dismiss();
        });
        panel.addView(save);

        MaterialButton reset = activity.makeTextRuleDialogButton(activity.getString(R.string.reset_to_default), text);
        reset.setOnClickListener(v -> {
            ButtonOrderManager.resetOrder(activity.prefs, group);
            working.clear();
            working.addAll(ButtonOrderManager.defaultItems(group));
            render[0].run();
            ShortToast.show(activity, R.string.button_order_reset);
        });
        panel.addView(reset);

        MaterialButton cancel = activity.makeTextRuleDialogButton(activity.getString(R.string.cancel), text);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        activity.showRoundedSettingsDialog(dialog, panel, true, 0.86f, 300);
    }

    private ScrollView makeBoundedListScrollView() {
        final int maxHeight = Math.max(activity.dpToPx(184), Math.min(activity.dpToPx(300),
                Math.round(activity.currentVisibleWindowHeightPx() * 0.42f)));
        ScrollView scrollView = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int limitedHeight = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, limitedHeight);
            }
        };
        scrollView.setFillViewport(false);
        scrollView.setClipChildren(true);
        scrollView.setClipToPadding(true);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        return scrollView;
    }

    private void renderOrderRows(@NonNull String group,
                                 @NonNull LinearLayout list,
                                 @NonNull List<ButtonOrderManager.Item> items,
                                 int rowBg,
                                 int text,
                                 int sub,
                                 int outline,
                                 int visibleSlotBg,
                                 int visibleSlotStroke,
                                 int visibleSlotText,
                                 int visibleSlotSub,
                                 @NonNull Runnable rerender) {
        list.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            ButtonOrderManager.Item item = items.get(i);
            boolean defaultVisible = isTxtDefaultVisibleSlot(group, index);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(activity.dpToPx(12), 0, activity.dpToPx(8), 0);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(defaultVisible ? visibleSlotBg : rowBg);
            bg.setCornerRadius(activity.dpToPx(14));
            bg.setStroke(activity.dpToPx(defaultVisible ? 2 : 1), defaultVisible ? visibleSlotStroke : outline);
            row.setBackground(bg);

            TextView label = new TextView(activity);
            label.setText(activity.getString(item.labelRes));
            label.setTextColor(defaultVisible ? visibleSlotText : text);
            label.setTextSize(14f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setSingleLine(true);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setIncludeFontPadding(false);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(makeMoveButton("↑", defaultVisible ? visibleSlotSub : sub, index > 0, () -> {
                Collections.swap(items, index, index - 1);
                rerender.run();
            }));
            row.addView(makeMoveButton("↓", defaultVisible ? visibleSlotSub : sub, index < items.size() - 1, () -> {
                Collections.swap(items, index, index + 1);
                rerender.run();
            }));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dpToPx(40));
            lp.setMargins(0, 0, activity.dpToPx(4), activity.dpToPx(5));
            list.addView(row, lp);
        }
    }

    private TextView makeTxtVisibleHint(int text) {
        TextView hint = new TextView(activity);
        hint.setText(activity.getString(R.string.button_order_txt_default_visible_hint));
        hint.setTextColor(text);
        hint.setTextSize(12.5f);
        hint.setGravity(Gravity.CENTER);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setLineSpacing(0, 1.05f);
        hint.setPadding(activity.dpToPx(10), activity.dpToPx(7), activity.dpToPx(10), activity.dpToPx(7));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(resolveVisibleSlotBackground(activity.dialogRowBackgroundColor(), text));
        bg.setCornerRadius(activity.dpToPx(13));
        bg.setStroke(activity.dpToPx(1), resolveVisibleSlotStroke(activity.dialogOutlineColor(), text));
        hint.setBackground(bg);
        return hint;
    }

    private boolean isTxtDefaultVisibleSlot(@NonNull String group, int index) {
        return ButtonOrderManager.GROUP_TXT_READER.equals(group)
                && index >= TXT_DEFAULT_FIRST_VISIBLE_INDEX
                && index < TXT_DEFAULT_FIRST_VISIBLE_INDEX + TXT_DEFAULT_VISIBLE_SLOT_COUNT;
    }

    private TextView makeMoveButton(@NonNull String label, int color, boolean enabled, @NonNull Runnable action) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextSize(19f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(enabled ? color : Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)));
        button.setEnabled(enabled);
        button.setOnClickListener(v -> action.run());
        int pad = activity.dpToPx(4);
        button.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(activity.dpToPx(34), activity.dpToPx(34));
        lp.setMargins(activity.dpToPx(3), 0, 0, 0);
        button.setLayoutParams(lp);
        return button;
    }

    private int resolveVisibleSlotBackground(int rowBg, int text) {
        return blend(rowBg, text, 0.065f);
    }

    private int resolveVisibleSlotStroke(int outline, int text) {
        return blend(outline, text, 0.28f);
    }


    private int blend(int base, int overlay, float amount) {
        float clamped = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(base) * (1f - clamped) + Color.red(overlay) * clamped);
        int g = Math.round(Color.green(base) * (1f - clamped) + Color.green(overlay) * clamped);
        int b = Math.round(Color.blue(base) * (1f - clamped) + Color.blue(overlay) * clamped);
        return Color.rgb(r, g, b);
    }
}
