package com.textview.reader;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.textview.reader.util.PrefsManager;

import java.util.Locale;

final class SettingsMainCustomThemeController {

    private final SettingsActivity activity;

    SettingsMainCustomThemeController(@NonNull SettingsActivity activity) {
        this.activity = activity;
    }


    void setupCustomMainThemeColors() {
        EditText bg = activity.findViewById(R.id.main_custom_bg_hex);
        EditText panel = activity.findViewById(R.id.main_custom_panel_hex);
        EditText bar = activity.findViewById(R.id.main_custom_bar_hex);
        EditText text = activity.findViewById(R.id.main_custom_text_hex);
        EditText sub = activity.findViewById(R.id.main_custom_sub_hex);
        EditText outline = activity.findViewById(R.id.main_custom_outline_hex);
        EditText selected = activity.findViewById(R.id.main_custom_selected_hex);
        EditText fileTypeChip = activity.findViewById(R.id.main_custom_file_type_chip_hex);
        EditText selectedFileTypeChip = activity.findViewById(R.id.main_custom_file_type_chip_selected_hex);
        EditText readingCard = activity.findViewById(R.id.main_custom_reading_card_hex);
        EditText shortcutBox = activity.findViewById(R.id.main_custom_shortcut_box_hex);
        EditText drawerActionIcon = activity.findViewById(R.id.main_custom_drawer_action_icon_hex);
        MaterialButton apply = activity.findViewById(R.id.btn_apply_custom_main_theme);
        if (bg == null || panel == null || bar == null || text == null || sub == null || outline == null || selected == null || fileTypeChip == null || selectedFileTypeChip == null || readingCard == null || shortcutBox == null || drawerActionIcon == null || apply == null) return;

        bg.setText(colorToHex(activity.prefs.getMainCustomBgColor()));
        panel.setText(colorToHex(activity.prefs.getMainCustomPanelColor()));
        bar.setText(colorToHex(activity.prefs.getMainCustomBarColor()));
        text.setText(colorToHex(activity.prefs.getMainCustomTextColor()));
        sub.setText(colorToHex(activity.prefs.getMainCustomSubTextColor()));
        outline.setText(colorToHex(activity.prefs.getMainCustomOutlineColor()));
        selected.setText(colorToHex(activity.prefs.getMainCustomSelectedColor()));
        fileTypeChip.setText(colorToHex(activity.prefs.getMainCustomFileTypeChipColor()));
        selectedFileTypeChip.setText(colorToHex(activity.prefs.getMainCustomFileTypeChipSelectedColor()));
        readingCard.setText(colorToHex(activity.prefs.getMainCustomReadingThemeCardColor()));
        shortcutBox.setText(colorToHex(activity.prefs.getMainCustomShortcutBoxColor()));
        drawerActionIcon.setText(colorToHex(activity.prefs.getMainCustomDrawerActionIconColor()));

        for (EditText customHexField : new EditText[]{bg, panel, bar, text, sub, outline, selected, fileTypeChip, selectedFileTypeChip, readingCard, shortcutBox, drawerActionIcon}) {
            configureMainCustomHexInput(customHexField);
            applyMainCustomHexFieldPadding(customHexField);
        }

        attachMainCustomRgbSliders(bg, activity.prefs.getMainCustomBgColor());
        attachMainCustomRgbSliders(panel, activity.prefs.getMainCustomPanelColor());
        attachMainCustomRgbSliders(bar, activity.prefs.getMainCustomBarColor());
        attachMainCustomRgbSliders(text, activity.prefs.getMainCustomTextColor());
        attachMainCustomRgbSliders(sub, activity.prefs.getMainCustomSubTextColor());
        attachMainCustomRgbSliders(outline, activity.prefs.getMainCustomOutlineColor());
        attachMainCustomRgbSliders(selected, activity.prefs.getMainCustomSelectedColor());
        attachMainCustomRgbSliders(fileTypeChip, activity.prefs.getMainCustomFileTypeChipColor());
        attachMainCustomRgbSliders(selectedFileTypeChip, activity.prefs.getMainCustomFileTypeChipSelectedColor());
        attachMainCustomRgbSliders(readingCard, activity.prefs.getMainCustomReadingThemeCardColor());
        attachMainCustomRgbSliders(shortcutBox, activity.prefs.getMainCustomShortcutBoxColor());
        attachMainCustomRgbSliders(drawerActionIcon, activity.prefs.getMainCustomDrawerActionIconColor());

        apply.setOnClickListener(v -> {
            Integer bgColor = readHexColor(bg);
            Integer panelColor = readHexColor(panel);
            Integer barColor = readHexColor(bar);
            Integer textColor = readHexColor(text);
            Integer subColor = readHexColor(sub);
            Integer outlineColor = readHexColor(outline);
            Integer selectedColor = readHexColor(selected);
            Integer fileTypeChipColor = readHexColor(fileTypeChip);
            Integer selectedFileTypeChipColor = readHexColor(selectedFileTypeChip);
            Integer readingCardColor = readHexColor(readingCard);
            Integer shortcutBoxColor = readHexColor(shortcutBox);
            Integer drawerActionIconColor = readHexColor(drawerActionIcon);
            if (bgColor == null || panelColor == null || barColor == null
                    || textColor == null || subColor == null || outlineColor == null || selectedColor == null
                    || fileTypeChipColor == null || selectedFileTypeChipColor == null
                    || readingCardColor == null || shortcutBoxColor == null || drawerActionIconColor == null) {
                ShortToast.show(activity, R.string.invalid_hex_color);
                return;
            }

            activity.prefs.setMainCustomColors(bgColor, panelColor, barColor, textColor, subColor, outlineColor, selectedColor, readingCardColor, shortcutBoxColor, drawerActionIconColor);
            activity.prefs.setMainCustomFileTypeChipColors(fileTypeChipColor, selectedFileTypeChipColor);
            if (activity.prefs.getDarkMode() != PrefsManager.DARK_MODE_CUSTOM) {
                activity.prefs.setDarkMode(PrefsManager.DARK_MODE_CUSTOM);
            } else {
                activity.prefs.applyDarkMode(PrefsManager.DARK_MODE_CUSTOM);
            }
            ShortToast.show(activity, R.string.custom_main_theme_applied);
            activity.recreate();
        });

        updateCustomMainThemeSectionVisibility();
    }

    private void attachMainCustomRgbSliders(@NonNull EditText field, int initialColor) {
        ViewParent rawParent = field.getParent();
        if (!(rawParent instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) rawParent;
        final boolean[] suppress = new boolean[]{false};

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(10), activity.dpToPx(4), activity.dpToPx(10), activity.dpToPx(16));

        TextView sliderHint = new TextView(activity);
        sliderHint.setText(activity.getString(R.string.main_custom_rgb_sliders));
        sliderHint.setTextSize(12f);
        sliderHint.setTextColor(activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : Color.rgb(120, 120, 120));
        sliderHint.setPadding(0, 0, 0, activity.dpToPx(6));
        box.addView(sliderHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        MaterialButton paletteButton = new MaterialButton(activity);
        paletteButton.setText(R.string.choose_from_palette);
        paletteButton.setAllCaps(false);
        paletteButton.setMinHeight(activity.dpToPx(40));
        paletteButton.setTextColor(activity.prefs != null ? activity.prefs.getMainTextColor(activity) : Color.rgb(32, 33, 36));
        paletteButton.setStrokeWidth(Math.max(1, activity.dpToPx(1)));
        paletteButton.setStrokeColor(android.content.res.ColorStateList.valueOf(activity.prefs != null
                ? activity.prefs.getMainOutlineColor(activity)
                : Color.rgb(190, 190, 190)));
        paletteButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activity.prefs != null
                ? activity.prefs.getMainPanelColor(activity)
                : Color.WHITE));
        paletteButton.setCornerRadius(activity.dpToPx(10));
        LinearLayout.LayoutParams paletteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        paletteLp.setMargins(0, 0, 0, activity.dpToPx(8));
        box.addView(paletteButton, paletteLp);

        SeekBar red = new SeekBar(activity);
        SeekBar green = new SeekBar(activity);
        SeekBar blue = new SeekBar(activity);
        TextView redValue = new TextView(activity);
        TextView greenValue = new TextView(activity);
        TextView blueValue = new TextView(activity);

        addMainCustomColorSliderRow(box, "R", red, redValue);
        addMainCustomColorSliderRow(box, "G", green, greenValue);
        addMainCustomColorSliderRow(box, "B", blue, blueValue);

        SeekBar[] bars = new SeekBar[]{red, green, blue};
        TextView[] values = new TextView[]{redValue, greenValue, blueValue};
        for (SeekBar bar : bars) {
            bar.setMax(255);
        }

        Runnable updateHexFromSliders = () -> {
            int color = Color.rgb(red.getProgress(), green.getProgress(), blue.getProgress());
            suppress[0] = true;
            field.setText(colorToHex(color));
            field.setSelection(field.getText().length());
            suppress[0] = false;
            field.setError(null);
            applyMainCustomHexFieldPreview(field, color);
        };

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMainCustomSliderValueLabels(bars, values);
                if (fromUser && !suppress[0]) updateHexFromSliders.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        red.setOnSeekBarChangeListener(listener);
        green.setOnSeekBarChangeListener(listener);
        blue.setOnSeekBarChangeListener(listener);

        setMainCustomSlidersFromColor(bars, values, initialColor, suppress);
        applyMainCustomHexFieldPreview(field, initialColor);

        paletteButton.setOnClickListener(v -> {
            Integer parsed = parseHexColor(field.getText() != null ? field.getText().toString() : null);
            int startColor = parsed != null ? parsed : initialColor;
            ColorPaletteDialog.show(activity, activity.getString(R.string.choose_from_palette), startColor, color -> {
                suppress[0] = true;
                field.setText(colorToHex(color));
                field.setSelection(field.getText().length());
                suppress[0] = false;
                setMainCustomSlidersFromColor(bars, values, color, suppress);
                applyMainCustomHexFieldPreview(field, color);
                field.setError(null);
            });
        });

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (suppress[0]) return;
                Integer parsed = parseHexColor(text != null ? text.toString() : null);
                if (parsed != null) {
                    setMainCustomSlidersFromColor(bars, values, parsed, suppress);
                    applyMainCustomHexFieldPreview(field, parsed);
                    field.setError(null);
                } else {
                    applyMainCustomHexFieldInvalidPreview(field);
                }
            }
            @Override public void afterTextChanged(Editable editable) {}
        });

        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                Integer parsed = parseHexColor(field.getText().toString());
                if (parsed != null) {
                    suppress[0] = true;
                    field.setText(colorToHex(parsed));
                    field.setSelection(field.getText().length());
                    suppress[0] = false;
                    setMainCustomSlidersFromColor(bars, values, parsed, suppress);
                    applyMainCustomHexFieldPreview(field, parsed);
                    field.setError(null);
                }
            }
        });

        int index = parent.indexOfChild(field);
        if (index >= 0) {
            parent.addView(box, index + 1);
        }
    }

    private void applyMainCustomHexFieldPadding(@NonNull EditText field) {
        field.setMinHeight(activity.dpToPx(48));
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setPadding(activity.dpToPx(18), 0, activity.dpToPx(18), 0);
        field.setIncludeFontPadding(false);
    }

    void applyMainCustomHexFieldPreview(@NonNull EditText field, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(activity.dpToPx(10));
        int stroke = readableTextColorForMainCustomColor(color);
        bg.setStroke(Math.max(1, activity.dpToPx(1)), blendColorForMainPreview(color, stroke, 0.52f));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            field.setBackgroundTintList(null);
        }
        field.setBackground(bg);
        applyMainCustomHexFieldPadding(field);

        int textColor = readableTextColorForMainCustomColor(color);
        field.setTextColor(textColor);
        field.setHintTextColor(blendColorForMainPreview(color, textColor, 0.68f));
        field.invalidate();
    }

    void applyMainCustomHexFieldInvalidPreview(@NonNull EditText field) {
        GradientDrawable bg = new GradientDrawable();
        int base = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : Color.rgb(245, 245, 245);
        int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : Color.rgb(32, 33, 36);
        bg.setColor(base);
        bg.setCornerRadius(activity.dpToPx(10));
        bg.setStroke(Math.max(1, activity.dpToPx(1)), Color.rgb(190, 70, 70));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            field.setBackgroundTintList(null);
        }
        field.setBackground(bg);
        applyMainCustomHexFieldPadding(field);
        field.setTextColor(fg);
        field.setHintTextColor(activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : Color.rgb(120, 120, 120));
        field.invalidate();
    }

    boolean isMainCustomHexField(@NonNull EditText field) {
        int id = field.getId();
        return id == R.id.main_custom_bg_hex
                || id == R.id.main_custom_panel_hex
                || id == R.id.main_custom_bar_hex
                || id == R.id.main_custom_text_hex
                || id == R.id.main_custom_sub_hex
                || id == R.id.main_custom_outline_hex
                || id == R.id.main_custom_selected_hex
                || id == R.id.main_custom_file_type_chip_hex
                || id == R.id.main_custom_file_type_chip_selected_hex
                || id == R.id.main_custom_reading_card_hex
                || id == R.id.main_custom_shortcut_box_hex
                || id == R.id.main_custom_drawer_action_icon_hex;
    }

    void refreshMainCustomHexFieldPreviews() {
        int[] ids = new int[]{
                R.id.main_custom_bg_hex,
                R.id.main_custom_panel_hex,
                R.id.main_custom_bar_hex,
                R.id.main_custom_text_hex,
                R.id.main_custom_sub_hex,
                R.id.main_custom_outline_hex,
                R.id.main_custom_selected_hex,
                R.id.main_custom_file_type_chip_hex,
                R.id.main_custom_file_type_chip_selected_hex,
                R.id.main_custom_reading_card_hex,
                R.id.main_custom_shortcut_box_hex,
                R.id.main_custom_drawer_action_icon_hex
        };
        for (int id : ids) {
            EditText field = activity.findViewById(id);
            if (field == null) continue;
            Integer parsed = parseHexColor(field.getText() != null ? field.getText().toString() : null);
            if (parsed != null) {
                applyMainCustomHexFieldPreview(field, parsed);
            } else {
                applyMainCustomHexFieldInvalidPreview(field);
            }
        }
    }

    private int readableTextColorForMainCustomColor(int color) {
        double luminance = UiColorUtils.luminance255(color) / 255.0;
        return luminance > 0.56 ? Color.rgb(24, 28, 34) : Color.rgb(238, 246, 255);
    }

    private int blendColorForMainPreview(int bottomColor, int topColor, float topAlpha) {
        return UiColorUtils.blendColors(bottomColor, topColor, topAlpha);
    }

    private void addMainCustomColorSliderRow(@NonNull LinearLayout parent,
                                             @NonNull String label,
                                             @NonNull SeekBar seekBar,
                                             @NonNull TextView value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, activity.dpToPx(2), 0, activity.dpToPx(6));

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTextSize(12f);
        labelView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        labelView.setTextColor(activity.prefs != null ? activity.prefs.getMainTextColor(activity) : Color.rgb(32, 33, 36));
        row.addView(labelView, new LinearLayout.LayoutParams(activity.dpToPx(24), LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(seekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        value.setGravity(Gravity.CENTER);
        value.setTextSize(12f);
        value.setSingleLine(true);
        value.setTextColor(activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : Color.rgb(120, 120, 120));
        row.addView(value, new LinearLayout.LayoutParams(activity.dpToPx(42), LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void setMainCustomSlidersFromColor(@NonNull SeekBar[] bars,
                                               @NonNull TextView[] values,
                                               int color,
                                               @NonNull boolean[] suppress) {
        suppress[0] = true;
        bars[0].setProgress(Color.red(color));
        bars[1].setProgress(Color.green(color));
        bars[2].setProgress(Color.blue(color));
        suppress[0] = false;
        updateMainCustomSliderValueLabels(bars, values);
    }

    private void updateMainCustomSliderValueLabels(@NonNull SeekBar[] bars,
                                                   @NonNull TextView[] values) {
        for (int i = 0; i < bars.length && i < values.length; i++) {
            values[i].setText(String.valueOf(bars[i].getProgress()));
        }
    }

    void updateCustomMainThemeSectionVisibility() {
        View section = activity.findViewById(R.id.main_custom_theme_section);
        if (section != null) {
            section.setVisibility(activity.prefs != null && activity.prefs.getDarkMode() == PrefsManager.DARK_MODE_CUSTOM
                    ? View.VISIBLE
                    : View.GONE);
        }
    }

    private void configureMainCustomHexInput(@NonNull EditText field) {
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        field.setKeyListener(DigitsKeyListener.getInstance("#0123456789abcdefABCDEF"));
    }

    private Integer readHexColor(EditText field) {
        Integer color = parseHexColor(field != null ? field.getText().toString() : null);
        if (field != null) field.setError(color == null ? activity.getString(R.string.invalid_hex_color) : null);
        return color;
    }

    static Integer parseHexColor(String raw) {
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

    static String colorToHex(int color) {
        return String.format(Locale.US, "#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }

}
