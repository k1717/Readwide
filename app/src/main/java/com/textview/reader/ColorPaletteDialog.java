package com.textview.reader;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.PrefsManager;

import java.util.Locale;

/**
 * Lightweight HSV/RGB color picker used by main and reading theme editors.
 *
 * The color square is drawn with shaders instead of a pre-rendered bitmap, so it
 * does not keep a large palette image in memory.
 */
final class ColorPaletteDialog {

    interface OnColorPicked {
        void onColorPicked(int color);
    }

    private ColorPaletteDialog() {
    }

    static void show(@NonNull Context context,
                     @Nullable String title,
                     int initialColor,
                     @NonNull OnColorPicked listener) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int densityPadding = dp(context, 18);
        int panelBg = resolvePanelColor(context);
        int textColor = readableTextColor(panelBg);
        int subColor = UiColorUtils.blendColors(panelBg, textColor, isLight(panelBg) ? 0.58f : 0.68f);
        int outlineColor = UiColorUtils.blendColors(panelBg, textColor, isLight(panelBg) ? 0.22f : 0.30f);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(densityPadding, dp(context, 16), densityPadding, dp(context, 14));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(panelBg);
        rootBg.setCornerRadius(dp(context, 22));
        rootBg.setStroke(Math.max(1, dp(context, 1)), outlineColor);
        root.setBackground(rootBg);

        TextView titleView = new TextView(context);
        titleView.setText(title != null && title.trim().length() > 0 ? title : context.getString(R.string.choose_from_palette));
        titleView.setTextColor(textColor);
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, dp(context, 12));
        root.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View preview = new View(context);
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setCornerRadius(dp(context, 10));
        previewBg.setStroke(Math.max(1, dp(context, 1)), outlineColor);
        preview.setBackground(previewBg);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 34));
        previewLp.setMargins(0, 0, 0, dp(context, 10));
        root.addView(preview, previewLp);

        ColorSquareView square = new ColorSquareView(context);
        LinearLayout.LayoutParams squareLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 210));
        squareLp.setMargins(0, 0, 0, dp(context, 10));
        root.addView(square, squareLp);

        TextView hueLabel = new TextView(context);
        hueLabel.setText(context.getString(R.string.palette_hue));
        hueLabel.setTextColor(subColor);
        hueLabel.setTextSize(12f);
        root.addView(hueLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar hue = new SeekBar(context);
        hue.setMax(360);
        root.addView(hue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText hex = new EditText(context);
        hex.setSingleLine(true);
        hex.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        hex.setTextSize(14f);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hex.setKeyListener(DigitsKeyListener.getInstance("#0123456789abcdefABCDEF"));
        hex.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        hex.setHint("#RRGGBB");
        hex.setTextColor(textColor);
        hex.setHintTextColor(subColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            hex.setBackgroundTintList(android.content.res.ColorStateList.valueOf(outlineColor));
        }
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48));
        hexLp.setMargins(0, dp(context, 8), 0, dp(context, 8));
        root.addView(hex, hexLp);

        SeekBar red = new SeekBar(context);
        SeekBar green = new SeekBar(context);
        SeekBar blue = new SeekBar(context);
        TextView redValue = new TextView(context);
        TextView greenValue = new TextView(context);
        TextView blueValue = new TextView(context);
        for (SeekBar bar : new SeekBar[]{red, green, blue}) {
            bar.setMax(255);
        }
        addSliderRow(context, root, "R", red, redValue, textColor, subColor);
        addSliderRow(context, root, "G", green, greenValue, textColor, subColor);
        addSliderRow(context, root, "B", blue, blueValue, textColor, subColor);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        buttons.setPadding(0, dp(context, 10), 0, 0);
        int actionButtonBg = resolveActionButtonColor(context, panelBg, textColor);
        Button cancel = new Button(context);
        cancel.setText(android.R.string.cancel);
        stylePaletteActionButton(context, cancel, subColor, actionButtonBg);
        Button apply = new Button(context);
        apply.setText(android.R.string.ok);
        stylePaletteActionButton(context, apply, textColor, actionButtonBg);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(context, 44), 1f));
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(0, dp(context, 44), 1f);
        applyLp.setMargins(dp(context, 8), 0, 0, 0);
        buttons.addView(apply, applyLp);
        root.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final boolean[] suppress = new boolean[]{false};
        final int[] selected = new int[]{forceOpaque(initialColor)};

        Runnable updateLabels = () -> {
            redValue.setText(String.valueOf(red.getProgress()));
            greenValue.setText(String.valueOf(green.getProgress()));
            blueValue.setText(String.valueOf(blue.getProgress()));
        };

        Runnable updatePreview = () -> {
            previewBg.setColor(selected[0]);
            previewBg.setStroke(Math.max(1, dp(context, 1)), UiColorUtils.blendColors(selected[0], readableTextColor(selected[0]), 0.50f));
            preview.setBackground(previewBg);
        };

        Runnable syncAllFromSelected = () -> {
            suppress[0] = true;
            int color = selected[0];
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            square.setColor(color);
            hue.setProgress(Math.round(hsv[0]));
            red.setProgress(Color.red(color));
            green.setProgress(Color.green(color));
            blue.setProgress(Color.blue(color));
            hex.setText(colorToHex(color));
            hex.setSelection(hex.getText().length());
            updateLabels.run();
            updatePreview.run();
            hex.setError(null);
            suppress[0] = false;
        };

        square.setOnColorChangedListener(color -> {
            if (suppress[0]) return;
            selected[0] = forceOpaque(color);
            syncAllFromSelected.run();
        });

        hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || suppress[0]) return;
                square.setHue(progress);
                selected[0] = square.getColor();
                syncAllFromSelected.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        SeekBar.OnSeekBarChangeListener rgbListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateLabels.run();
                if (!fromUser || suppress[0]) return;
                selected[0] = Color.rgb(red.getProgress(), green.getProgress(), blue.getProgress());
                syncAllFromSelected.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        red.setOnSeekBarChangeListener(rgbListener);
        green.setOnSeekBarChangeListener(rgbListener);
        blue.setOnSeekBarChangeListener(rgbListener);

        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (suppress[0]) return;
                Integer parsed = parseHexColor(editable != null ? editable.toString() : null);
                if (parsed == null) return;
                selected[0] = parsed;
                syncAllFromSelected.run();
            }
        });

        cancel.setOnClickListener(v -> dialog.dismiss());
        apply.setOnClickListener(v -> {
            Integer parsed = parseHexColor(hex.getText() != null ? hex.getText().toString() : null);
            if (parsed == null) {
                hex.setError(context.getString(R.string.invalid_hex_color));
                return;
            }
            hideKeyboard(hex);
            listener.onColorPicked(parsed);
            dialog.dismiss();
        });

        dialog.setContentView(root);
        configurePaletteWindow(context, dialog.getWindow());
        dialog.setOnShowListener(d -> configurePaletteWindow(context, dialog.getWindow()));

        syncAllFromSelected.run();
        dialog.show();
    }


    private static void stylePaletteActionButton(@NonNull Context context,
                                                 @NonNull Button button,
                                                 int textColor,
                                                 int buttonBg) {
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(14f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(context, 44));
        button.setMinimumHeight(dp(context, 44));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(buttonBg);
        bg.setCornerRadius(dp(context, 10));
        bg.setStroke(0, Color.TRANSPARENT);
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
            button.setElevation(0f);
            button.setTranslationZ(0f);
        }
    }

    private static int resolveActionButtonColor(@NonNull Context context, int panelBg, int textColor) {
        PrefsManager prefs = PrefsManager.getInstance(context);
        if (prefs != null) return prefs.getMainElevatedPanelColor(context);
        return UiColorUtils.blendColors(panelBg, textColor, isLight(panelBg) ? 0.06f : 0.10f);
    }

    private static void addSliderRow(@NonNull Context context,
                                     @NonNull LinearLayout parent,
                                     @NonNull String label,
                                     @NonNull SeekBar seekBar,
                                     @NonNull TextView value,
                                     int textColor,
                                     int subColor) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 1), 0, dp(context, 5));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(textColor);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setGravity(Gravity.CENTER);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(context, 26), LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(seekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        value.setTextColor(subColor);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        value.setTextSize(12f);
        row.addView(value, new LinearLayout.LayoutParams(dp(context, 44), LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private static void configurePaletteWindow(@NonNull Context context, @Nullable Window window) {
        if (window == null) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        lp.width = Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(context, 32), dp(context, 420));
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        window.setAttributes(lp);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setWindowAnimations(0);
    }

    private static int forceOpaque(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private static String colorToHex(int color) {
        return String.format(Locale.US, "#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }

    private static Integer parseHexColor(@Nullable String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() != 6 || !value.matches("[0-9a-fA-F]{6}")) return null;
        try {
            return Color.rgb(
                    Integer.parseInt(value.substring(0, 2), 16),
                    Integer.parseInt(value.substring(2, 4), 16),
                    Integer.parseInt(value.substring(4, 6), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void hideKeyboard(@NonNull View view) {
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private static int resolvePanelColor(@NonNull Context context) {
        PrefsManager prefs = PrefsManager.getInstance(context);
        if (prefs != null) return prefs.getMainPanelColor(context);
        return Color.WHITE;
    }

    private static boolean isLight(int color) {
        return UiColorUtils.isHalfLightColor(color);
    }

    private static int readableTextColor(int color) {
        double luminance = UiColorUtils.luminance255(color) / 255.0;
        return luminance > 0.56 ? Color.rgb(24, 28, 34) : Color.rgb(238, 246, 255);
    }

    private static int dp(@NonNull Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static final class ColorSquareView extends View {
        interface OnColorChangedListener {
            void onColorChanged(int color);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float hue = 0f;
        private float saturation = 1f;
        private float value = 1f;
        private OnColorChangedListener listener;

        ColorSquareView(@NonNull Context context) {
            super(context);
            setFocusable(true);
            cursorPaint.setStyle(Paint.Style.STROKE);
            cursorPaint.setStrokeWidth(dp(context, 2));
            cursorPaint.setColor(Color.WHITE);
            cursorStrokePaint.setStyle(Paint.Style.STROKE);
            cursorStrokePaint.setStrokeWidth(dp(context, 4));
            cursorStrokePaint.setColor(Color.argb(150, 0, 0, 0));
        }

        void setOnColorChangedListener(@Nullable OnColorChangedListener listener) {
            this.listener = listener;
        }

        void setColor(int color) {
            float[] hsv = new float[3];
            Color.colorToHSV(forceOpaque(color), hsv);
            hue = hsv[0];
            saturation = clamp(hsv[1]);
            value = clamp(hsv[2]);
            invalidate();
        }

        void setHue(float hue) {
            this.hue = normalizeHue(hue);
            invalidate();
        }

        int getColor() {
            return Color.HSVToColor(new float[]{normalizeHue(hue), clamp(saturation), clamp(value)});
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            rect.set(0, 0, getWidth(), getHeight());
            float radius = dp(getContext(), 12);
            int hueColor = Color.HSVToColor(new float[]{normalizeHue(hue), 1f, 1f});

            // Draw the palette in two normal passes instead of using a
            // ComposeShader.  Some devices/GPU paths cache or flatten the
            // composed shader too aggressively, which made the square appear not
            // to change when the hue/RGB state changed.  Recreating and drawing
            // the two simple shaders every frame keeps the displayed square tied
            // directly to the current hue.
            paint.setShader(new LinearGradient(0, 0, getWidth(), 0,
                    Color.WHITE, hueColor, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            float cx = clamp(saturation) * getWidth();
            float cy = (1f - clamp(value)) * getHeight();
            float cursor = dp(getContext(), 7);
            canvas.drawCircle(cx, cy, cursor, cursorStrokePaint);
            canvas.drawCircle(cx, cy, cursor, cursorPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                updateFromTouch(event.getX(), event.getY());
                if (listener != null) listener.onColorChanged(getColor());
                return true;
            }
            return super.onTouchEvent(event);
        }

        private void updateFromTouch(float x, float y) {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            saturation = clamp(x / w);
            value = clamp(1f - (y / h));
            invalidate();
        }

        private static float normalizeHue(float hue) {
            float h = hue % 360f;
            return h < 0f ? h + 360f : h;
        }

        private static float clamp(float v) {
            if (v < 0f) return 0f;
            if (v > 1f) return 1f;
            return v;
        }
    }
}
