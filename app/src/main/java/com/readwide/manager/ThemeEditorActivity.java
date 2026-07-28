package com.readwide.manager;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.readwide.manager.model.Theme;
import com.readwide.manager.util.ThemeManager;
import com.readwide.manager.util.PrefsManager;

import com.google.android.material.button.MaterialButton;

/**
 * Activity for creating and editing custom reading themes.
 * Provides RGB sliders for text and background colors,
 * optional background image selection, and live preview.
 */
public class ThemeEditorActivity extends AppCompatActivity {

    public static final String EXTRA_THEME_ID = "theme_id";

    private EditText nameInput;
    private static final int COLOR_TARGET_BACKGROUND = 0;
    private static final int COLOR_TARGET_TEXT = 1;
    private static final int COLOR_TARGET_TOOLBAR = 2;

    private View bgPreview, textPreview, toolbarPreview;
    private TextView previewText;
    private EditText bgHexInput, textHexInput, toolbarHexInput;
    private SeekBar bgR, bgG, bgB, txtR, txtG, txtB, toolbarR, toolbarG, toolbarB;
    private boolean updatingHexFields = false;

    private int currentBgColor = 0xFFFAFAFA;
    private int currentTextColor = 0xFF212121;
    private int currentToolbarColor = 0xFFFAFAFA;
    private String backgroundImagePath = null;
    private Theme editingTheme = null;
    private PrefsManager prefs;

    private final ActivityResultLauncher<String[]> imagePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    SafStorageAccess.tryTakePersistableReadGrant(this, uri);
                    backgroundImagePath = uri.toString();
                    ShortToast.show(this, R.string.theme_editor_background_image_set);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        if (prefs != null) {
            prefs.applyLanguage(prefs.getLanguageMode());
            prefs.applyDarkMode(prefs.getDarkMode());
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_editor);
        applyThemeEditorSafeInsets();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        nameInput = findViewById(R.id.theme_name_input);
        bgPreview = findViewById(R.id.bg_color_preview);
        textPreview = findViewById(R.id.text_color_preview);
        toolbarPreview = findViewById(R.id.toolbar_color_preview);
        previewText = findViewById(R.id.preview_text);
        bgHexInput = findViewById(R.id.bg_hex_input);
        textHexInput = findViewById(R.id.text_hex_input);
        toolbarHexInput = findViewById(R.id.toolbar_hex_input);

        bgR = findViewById(R.id.bg_red);
        bgG = findViewById(R.id.bg_green);
        bgB = findViewById(R.id.bg_blue);
        txtR = findViewById(R.id.txt_red);
        txtG = findViewById(R.id.txt_green);
        txtB = findViewById(R.id.txt_blue);
        toolbarR = findViewById(R.id.toolbar_red);
        toolbarG = findViewById(R.id.toolbar_green);
        toolbarB = findViewById(R.id.toolbar_blue);

        applyReadableThemeEditorColors();

        ThemeManager tm = ThemeManager.getInstance(this);

        // Check if editing existing theme
        String themeId = getIntent().getStringExtra(EXTRA_THEME_ID);
        if (themeId != null) {
            for (Theme t : tm.getAllThemes()) {
                if (t.getId().equals(themeId) && !t.isBuiltIn()) {
                    editingTheme = t;
                    break;
                }
            }
        }

        if (editingTheme != null) {
            getSupportActionBar().setTitle(R.string.theme_editor_edit_title);
            nameInput.setText(editingTheme.getName());
            currentBgColor = editingTheme.getBackgroundColor();
            currentTextColor = editingTheme.getTextColor();
            currentToolbarColor = editingTheme.getToolbarColor();
            backgroundImagePath = editingTheme.getBackgroundImagePath();
        } else {
            getSupportActionBar().setTitle(R.string.theme_editor_new_title);
            nameInput.setText(R.string.theme_editor_default_name);

            Theme activeTheme = tm.getActiveTheme();
            if (activeTheme != null) {
                currentBgColor = activeTheme.getBackgroundColor();
                currentTextColor = activeTheme.getTextColor();
                currentToolbarColor = activeTheme.getToolbarColor();
            } else if (prefs != null) {
                currentBgColor = prefs.getMainBgColor(this);
                currentTextColor = prefs.getMainTextColor(this);
                currentToolbarColor = currentBgColor;
            }
        }

        // Set slider positions from colors
        bgR.setProgress(Color.red(currentBgColor));
        bgG.setProgress(Color.green(currentBgColor));
        bgB.setProgress(Color.blue(currentBgColor));
        txtR.setProgress(Color.red(currentTextColor));
        txtG.setProgress(Color.green(currentTextColor));
        txtB.setProgress(Color.blue(currentTextColor));
        toolbarR.setProgress(Color.red(currentToolbarColor));
        toolbarG.setProgress(Color.green(currentToolbarColor));
        toolbarB.setProgress(Color.blue(currentToolbarColor));

        SeekBar.OnSeekBarChangeListener colorListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { updatePreview(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };

        bgR.setOnSeekBarChangeListener(colorListener);
        bgG.setOnSeekBarChangeListener(colorListener);
        bgB.setOnSeekBarChangeListener(colorListener);
        txtR.setOnSeekBarChangeListener(colorListener);
        txtG.setOnSeekBarChangeListener(colorListener);
        txtB.setOnSeekBarChangeListener(colorListener);
        toolbarR.setOnSeekBarChangeListener(colorListener);
        toolbarG.setOnSeekBarChangeListener(colorListener);
        toolbarB.setOnSeekBarChangeListener(colorListener);

        installHexInput(bgHexInput, COLOR_TARGET_BACKGROUND);
        installHexInput(textHexInput, COLOR_TARGET_TEXT);
        installHexInput(toolbarHexInput, COLOR_TARGET_TOOLBAR);
        installPaletteButton(R.id.btn_bg_palette, COLOR_TARGET_BACKGROUND);
        installPaletteButton(R.id.btn_text_palette, COLOR_TARGET_TEXT);
        installPaletteButton(R.id.btn_toolbar_palette, COLOR_TARGET_TOOLBAR);

        findViewById(R.id.btn_pick_bg_image).setOnClickListener(v ->
                imagePicker.launch(new String[]{"image/*"}));

        findViewById(R.id.btn_clear_bg_image).setOnClickListener(v -> {
            backgroundImagePath = null;
            ShortToast.show(this, R.string.theme_editor_background_image_cleared);
        });

        findViewById(R.id.btn_save_theme).setOnClickListener(v -> saveTheme());

        updatePreview();
    }


    private void applyThemeEditorSafeInsets() {
        View root = findViewById(R.id.theme_editor_root);
        View appbar = findViewById(R.id.theme_editor_appbar);
        ScrollView scroll = findViewById(R.id.theme_editor_scroll);
        if (root == null || scroll == null) return;

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        final int appbarLeft = appbar != null ? appbar.getPaddingLeft() : 0;
        final int appbarTop = appbar != null ? appbar.getPaddingTop() : 0;
        final int appbarRight = appbar != null ? appbar.getPaddingRight() : 0;
        final int appbarBottom = appbar != null ? appbar.getPaddingBottom() : 0;
        final int left = scroll.getPaddingLeft();
        final int top = scroll.getPaddingTop();
        final int right = scroll.getPaddingRight();
        final int bottom = scroll.getPaddingBottom();
        final int extraBottomGap = dpToPx(16);
        scroll.setClipToPadding(false);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            int bottomInset = imeVisible ? Math.max(bars.bottom, ime.bottom) : bars.bottom;
            if (appbar != null) {
                appbar.setPadding(appbarLeft + bars.left, appbarTop + bars.top,
                        appbarRight + bars.right, appbarBottom);
            }
            scroll.setPadding(left, top, right, bottom + bottomInset + extraBottomGap);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private boolean isDarkUi() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyReadableThemeEditorColors() {
        boolean dark = prefs != null ? prefs.shouldUseDarkColors(this) : isDarkUi();
        int bg = prefs != null ? prefs.getMainBgColor(this) : (dark ? Color.rgb(0, 0, 0) : Color.WHITE);
        int panel = prefs != null ? prefs.getMainPanelColor(this) : (dark ? Color.rgb(16, 16, 16) : Color.WHITE);
        int text = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36));
        int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(176, 176, 176) : Color.rgb(95, 99, 104));
        int bar = prefs != null ? prefs.getMainBarColor(this) : (dark ? Color.rgb(0, 0, 0) : Color.rgb(32, 33, 36));
        int outline = prefs != null ? prefs.getMainOutlineColor(this) : (dark ? Color.rgb(68, 68, 68) : Color.rgb(218, 220, 224));
        int accent = prefs != null ? prefs.getMainControlColor(this) : (dark ? Color.rgb(189, 189, 189) : Color.rgb(70, 70, 70));

        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bg);
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(!dark);

        Toolbar tb = findViewById(R.id.toolbar);
        View appbar = findViewById(R.id.theme_editor_appbar);
        if (appbar != null) {
            appbar.setBackgroundColor(bar);
        }
        if (tb != null) {
            tb.setBackgroundColor(bar);
            tb.setTitleTextColor(text);
            if (tb.getNavigationIcon() != null) {
                tb.getNavigationIcon().setTint(text);
            }
        }

        View root = findViewById(android.R.id.content);
        View rootLayout = findViewById(R.id.theme_editor_root);
        ScrollView scroll = findViewById(R.id.theme_editor_scroll);
        if (root != null) root.setBackgroundColor(bg);
        if (rootLayout != null) rootLayout.setBackgroundColor(bg);
        if (scroll != null) scroll.setBackgroundColor(bg);
        applyThemeEditorColorsRecursive(root, text, sub,
                ColorStateList.valueOf(accent),
                ColorStateList.valueOf(outline),
                ColorStateList.valueOf(panel));

        // Match the theme editor action buttons to the same card color used by
        // main file operation rows such as Open / File Info.
        ColorStateList actionButtonBackground = ColorStateList.valueOf(panel);
        ColorStateList actionButtonRipple = ColorStateList.valueOf(accent);
        applyThemeEditorActionButtonStyle(findViewById(R.id.btn_bg_palette), text,
                actionButtonBackground, actionButtonRipple);
        applyThemeEditorActionButtonStyle(findViewById(R.id.btn_text_palette), text,
                actionButtonBackground, actionButtonRipple);
        applyThemeEditorActionButtonStyle(findViewById(R.id.btn_toolbar_palette), text,
                actionButtonBackground, actionButtonRipple);
        applyThemeEditorActionButtonStyle(findViewById(R.id.btn_pick_bg_image), text,
                actionButtonBackground, actionButtonRipple);
        applyThemeEditorActionButtonStyle(findViewById(R.id.btn_clear_bg_image), text,
                actionButtonBackground, actionButtonRipple);
        applyThemeEditorActionButtonStyle(findViewById(R.id.btn_save_theme), text,
                actionButtonBackground, actionButtonRipple);
    }


    private void applyThemeEditorActionButtonStyle(
            MaterialButton button, int textColor, ColorStateList background, ColorStateList ripple) {
        if (button == null) return;
        button.setTextColor(textColor);
        button.setBackgroundTintList(background);
        button.setRippleColor(ripple);
        button.setStrokeWidth(0);
        button.setStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setCornerRadius(dpToPx(10));
        button.setElevation(0f);
        button.setTranslationZ(0f);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
    }

    private void applyThemeEditorColorsRecursive(
            View view, int text, int sub, ColorStateList accentState, ColorStateList outlineState,
            ColorStateList buttonBackgroundState) {
        if (view == null) return;

        if (view instanceof EditText) {
            EditText et = (EditText) view;
            et.setTextColor(text);
            et.setHintTextColor(sub);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                et.setBackgroundTintList(outlineState);
            }
        } else if (view instanceof MaterialButton) {
            MaterialButton mb = (MaterialButton) view;
            mb.setTextColor(text);
            mb.setStrokeColor(outlineState);
            mb.setBackgroundTintList(buttonBackgroundState);
        } else if (view instanceof TextView && view != previewText) {
            ((TextView) view).setTextColor(text);
        }

        if (view instanceof SeekBar && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            SeekBar sb = (SeekBar) view;
            sb.setThumbTintList(accentState);
            sb.setProgressTintList(accentState);
            sb.setProgressBackgroundTintList(outlineState);
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyThemeEditorColorsRecursive(group.getChildAt(i), text, sub, accentState, outlineState, buttonBackgroundState);
            }
        }
    }


    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void updatePreview() {
        if (updatingHexFields) return;
        currentBgColor = Color.rgb(bgR.getProgress(), bgG.getProgress(), bgB.getProgress());
        currentTextColor = Color.rgb(txtR.getProgress(), txtG.getProgress(), txtB.getProgress());
        currentToolbarColor = Color.rgb(toolbarR.getProgress(), toolbarG.getProgress(), toolbarB.getProgress());
        renderPreview(true);
    }

    private void renderPreview(boolean syncHexFields) {
        bgPreview.setBackgroundColor(currentBgColor);
        textPreview.setBackgroundColor(currentTextColor);
        if (toolbarPreview != null) toolbarPreview.setBackgroundColor(currentToolbarColor);
        previewText.setBackgroundColor(currentBgColor);
        previewText.setTextColor(currentTextColor);
        if (syncHexFields) {
            updatingHexFields = true;
            if (bgHexInput != null) bgHexInput.setText(colorToHex(currentBgColor));
            if (textHexInput != null) textHexInput.setText(colorToHex(currentTextColor));
            if (toolbarHexInput != null) toolbarHexInput.setText(colorToHex(currentToolbarColor));
            updatingHexFields = false;
        }
    }

    private void installHexInput(EditText input, int target) {
        if (input == null) return;
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (updatingHexFields) return;
                Integer color = parseHexColor(editable != null ? editable.toString() : null);
                if (color == null) return;
                if (target == COLOR_TARGET_BACKGROUND) {
                    currentBgColor = color;
                    setSeekBarsFromColor(bgR, bgG, bgB, color);
                } else if (target == COLOR_TARGET_TEXT) {
                    currentTextColor = color;
                    setSeekBarsFromColor(txtR, txtG, txtB, color);
                } else if (target == COLOR_TARGET_TOOLBAR) {
                    currentToolbarColor = color;
                    setSeekBarsFromColor(toolbarR, toolbarG, toolbarB, color);
                }
                renderPreview(false);
            }
        });
    }

    private void installPaletteButton(int buttonId, int target) {
        View raw = findViewById(buttonId);
        if (raw == null) return;
        raw.setOnClickListener(v -> {
            int startColor = colorForTarget(target);
            ColorPaletteDialog.show(this, getString(R.string.choose_from_palette), startColor, color -> {
                if (target == COLOR_TARGET_BACKGROUND) {
                    currentBgColor = color;
                    setSeekBarsFromColor(bgR, bgG, bgB, color);
                } else if (target == COLOR_TARGET_TEXT) {
                    currentTextColor = color;
                    setSeekBarsFromColor(txtR, txtG, txtB, color);
                } else if (target == COLOR_TARGET_TOOLBAR) {
                    currentToolbarColor = color;
                    setSeekBarsFromColor(toolbarR, toolbarG, toolbarB, color);
                }
                renderPreview(true);
            });
        });
    }

    private int colorForTarget(int target) {
        if (target == COLOR_TARGET_BACKGROUND) return currentBgColor;
        if (target == COLOR_TARGET_TEXT) return currentTextColor;
        if (target == COLOR_TARGET_TOOLBAR) return currentToolbarColor;
        return Color.WHITE;
    }

    private void setSeekBarsFromColor(SeekBar r, SeekBar g, SeekBar b, int color) {
        boolean wasUpdating = updatingHexFields;
        updatingHexFields = true;
        try {
            r.setProgress(Color.red(color));
            g.setProgress(Color.green(color));
            b.setProgress(Color.blue(color));
        } finally {
            updatingHexFields = wasUpdating;
        }
    }

    private Integer parseHexColor(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        // Do not treat 3 typed characters as shorthand while editing.
        // Otherwise input such as #050D1A is rewritten as #005500 at #050.
        // Reading theme colors are RGB-only: RRGGBB or #RRGGBB.
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

    private String colorToHex(int color) {
        return String.format(java.util.Locale.US, "#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }

    private boolean validateHexInputs() {
        boolean ok = true;
        if (bgHexInput != null && parseHexColor(bgHexInput.getText().toString()) == null) {
            bgHexInput.setError(getString(R.string.invalid_hex_color));
            ok = false;
        }
        if (textHexInput != null && parseHexColor(textHexInput.getText().toString()) == null) {
            textHexInput.setError(getString(R.string.invalid_hex_color));
            ok = false;
        }
        if (toolbarHexInput != null && parseHexColor(toolbarHexInput.getText().toString()) == null) {
            toolbarHexInput.setError(getString(R.string.invalid_hex_color));
            ok = false;
        }
        return ok;
    }

    private void saveTheme() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError(getString(R.string.theme_editor_name_required));
            return;
        }
        if (!validateHexInputs()) {
            ShortToast.show(this, R.string.invalid_hex_color);
            return;
        }

        ThemeManager tm = ThemeManager.getInstance(this);

        if (editingTheme != null) {
            editingTheme.setName(name);
            editingTheme.setBackgroundColor(currentBgColor);
            editingTheme.setTextColor(currentTextColor);
            editingTheme.setToolbarColor(currentToolbarColor);
            editingTheme.setBackgroundImagePath(backgroundImagePath);
            tm.updateCustomTheme(editingTheme);
        } else {
            Theme newTheme = new Theme();
            newTheme.setName(name);
            newTheme.setBackgroundColor(currentBgColor);
            newTheme.setTextColor(currentTextColor);
            newTheme.setToolbarColor(currentToolbarColor);
            newTheme.setBackgroundImagePath(backgroundImagePath);
            tm.addCustomTheme(newTheme);
        }

        ShortToast.show(this, R.string.theme_editor_saved);
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
