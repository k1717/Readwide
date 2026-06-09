package com.textview.reader;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Shared rounded dialog styling for ImageReaderActivity. */
final class ImageDialogStyleController {
    private final Context context;

    ImageDialogStyleController(@NonNull Context context) {
        this.context = context;
    }

    @NonNull
    Dialog makeDialog() {
        Dialog dialog = new Dialog(context);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        return dialog;
    }

    @NonNull
    LinearLayout makeBox() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpToPx(18), dpToPx(16), dpToPx(18), dpToPx(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor());
        bg.setCornerRadius(dpToPx(18));
        box.setBackground(bg);
        return box;
    }

    @NonNull
    TextView makeTitle(@NonNull String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(textColor());
        title.setTextSize(21f);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(14));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        return title;
    }

    @NonNull
    TextView makeButton(@NonNull String text, int color) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(color);
        button.setTextSize(16f);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        return button;
    }

    void addInfoRow(@NonNull LinearLayout box,
                    @NonNull String label,
                    @Nullable String value,
                    int fg,
                    int sub,
                    int panel) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(14), dpToPx(9), dpToPx(14), dpToPx(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panel);
        bg.setCornerRadius(dpToPx(12));
        row.setBackground(bg);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(sub);
        labelView.setTextSize(12f);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(labelView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(context);
        valueView.setText(value == null ? "" : value);
        valueView.setTextColor(fg);
        valueView.setTextSize(15f);
        valueView.setPadding(0, dpToPx(3), 0, 0);
        row.addView(valueView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(8));
        box.addView(row, lp);
    }

    void setDialogWidth(@NonNull Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        lp.width = Math.min(context.getResources().getDisplayMetrics().widthPixels - dpToPx(32), dpToPx(430));
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        window.setAttributes(lp);
    }

    int bgColor() {
        return Color.rgb(24, 24, 24);
    }

    int panelColor() {
        return Color.rgb(38, 38, 38);
    }

    int textColor() {
        return Color.WHITE;
    }

    int subTextColor() {
        return Color.rgb(190, 190, 190);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
