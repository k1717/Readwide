package com.textview.reader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.textview.reader.model.Theme;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.EdgeToEdgeUtil;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;
import com.textview.reader.util.ThemeManager;
import com.textview.reader.util.TextDisplayRule;
import com.textview.reader.util.TextDisplayRuleManager;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private static final String RELEASES_URL = "https://github.com/k1717/Readwide/releases";
    private static final String DEVELOPER_CONTACT_EMAIL = "readwide.kj7w5@addy.io";
    private static final String TXT_ACTUAL_FILE_EDIT_PREFS = "txt_actual_file_edit";
    private static final String KEY_TXT_ACTUAL_FILE_EDIT_PATH = "modified_path";
    private static final String KEY_TXT_ACTUAL_FILE_EDIT_TOKEN = "modified_token";
    private static final String KEY_TXT_ACTUAL_FILE_EDIT_LENGTH = "modified_length";
    private static final String KEY_TXT_ACTUAL_FILE_EDIT_LAST_MODIFIED = "modified_last_modified";
    private static final long TXT_ACTUAL_FILE_EDIT_LARGE_WARNING_BYTES = 32L * 1024L * 1024L;

    PrefsManager prefs;
    private BookmarkManager bookmarkManager;
    private ThemeManager themeManager;
    String currentTxtFilePath;

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> { if (uri != null) exportBackupTo(uri); });
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importBackupFrom(uri); });
    private final ActivityResultLauncher<Intent> lockSetLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Switch lockSwitch = findViewById(R.id.switch_lock);
                    if (lockSwitch != null) lockSwitch.setChecked(prefs.isLockEnabled());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        prefs.applyLanguage(prefs.getLanguageMode());
        prefs.applyDarkMode(prefs.getDarkMode());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        EdgeToEdgeUtil.applyStandardInsets(this, findViewById(R.id.settings_root),
                findViewById(R.id.settings_appbar), findViewById(R.id.settings_scroll));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.title_settings));
        }
        tintToolbarNavigation(toolbar);

        bookmarkManager = BookmarkManager.getInstance(this);
        themeManager = ThemeManager.getInstance(this);
        currentTxtFilePath = getIntent() != null ? getIntent().getStringExtra("txt_file_path") : null;

        setupLanguage();
        suppressLanguageRadioEffects();
        setupDarkMode();
        setupCustomMainThemeColors();
        setupReaderControls();
        setupButtonOrderSettings();
        setupTextDisplayRules();
        setupTextDisplayRulesActualFile();
        setupLock();
        setupExportImport();
        setupResetSettings();
        setupTheme();
        setupUpdateLink();
        setupDeveloperContact();

        // Force readable colors for every control after Android/Material defaults are applied.
        applySettingsReadableTheme();
        suppressLanguageRadioEffects();
        renderReadingThemeRows();
        refreshMainCustomHexFieldPreviews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (themeManager != null) {
            themeManager.reloadFromStorage();
        }
        if (prefs != null && themeManager != null) {
            applySettingsReadableTheme();
            suppressLanguageRadioEffects();
            renderReadingThemeRows();
            refreshMainCustomHexFieldPreviews();
        }
    }

    private void tintToolbarNavigation(Toolbar toolbar) {
        tintToolbarNavigation(toolbar, Color.WHITE);
    }


    private void setupUpdateLink() {
        TextView updateLink = findViewById(R.id.update_release_link);
        if (updateLink == null) return;

        updateLink.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) return;

            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.settings_section_updates),
                    RELEASES_URL));
        });
    }

    private void setupDeveloperContact() {
        View contact = findViewById(R.id.btn_contact_developer);
        if (contact == null) return;

        contact.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:" + Uri.encode(DEVELOPER_CONTACT_EMAIL)));
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{DEVELOPER_CONTACT_EMAIL});
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.contact_developer_subject));
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.contact_developer_chooser)));
            } catch (Exception e) {
                copyDeveloperContactAddress();
            }
        });
    }

    private void copyDeveloperContactAddress() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.contact_developer),
                DEVELOPER_CONTACT_EMAIL));
        ShortToast.show(this, R.string.contact_developer_address_copied);
    }

    private void setupLanguage() {
        TextView value = findViewById(R.id.language_current_value);
        if (value == null) return;
        updateLanguageValue(value);
        value.setOnClickListener(v -> showLanguageSelectionDialog(value));
    }

    private void updateLanguageValue(@NonNull TextView value) {
        value.setText(getString(R.string.settings_language_current,
                languageLabelForMode(prefs.getLanguageMode())));
    }

    private void showLanguageSelectionDialog(@NonNull TextView value) {
        android.app.Dialog dialog = createRoundedSettingsDialog();
        LinearLayout panel = createRoundedSettingsDialogPanel();
        int text = dialogTextColor();
        int sub = dialogSubTextColor();
        int outline = dialogOutlineColor();

        panel.addView(makeSettingsDialogTitle(getString(R.string.settings_language_dialog_title), text));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int current = prefs.getLanguageMode();
        for (LanguageOption option : languageOptions()) {
            list.addView(makeLanguageOptionRow(option, option.mode == current, text, sub, outline, dialog, value));
        }

        ScrollView languageScroll = new ScrollView(this);
        languageScroll.setFillViewport(false);
        languageScroll.setClipChildren(true);
        languageScroll.setClipToPadding(true);
        languageScroll.setVerticalScrollBarEnabled(false);
        languageScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        languageScroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, languageDialogListHeightPx());
        panel.addView(languageScroll, scrollLp);

        showRoundedSettingsDialog(dialog, panel, true, 0.76f, 280);
    }

    private TextView makeLanguageOptionRow(@NonNull LanguageOption option,
                                           boolean selected,
                                           int text,
                                           int sub,
                                           int outline,
                                           @NonNull android.app.Dialog dialog,
                                           @NonNull TextView value) {
        TextView row = new TextView(this);
        row.setText(selected
                ? getString(R.string.settings_language_selected, option.label)
                : option.label);
        row.setTextColor(selected ? text : sub);
        row.setTextSize(14.5f);
        row.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setSingleLine(false);
        row.setMaxLines(2);
        row.setIncludeFontPadding(false);
        row.setPadding(dpToPx(14), 0, dpToPx(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? prefs.getMainSelectedColor(this) : dialogRowBackgroundColor());
        bg.setCornerRadius(dpToPx(13));
        bg.setStroke(dpToPx(1), selected ? prefs.getMainFileLongHoldColor(this) : outline);
        row.setBackground(bg);
        row.setOnClickListener(v -> {
            if (prefs.getLanguageMode() != option.mode) {
                prefs.setLanguageMode(option.mode);
            } else {
                updateLanguageValue(value);
            }
            dialog.dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(40));
        lp.setMargins(0, dpToPx(4), 0, 0);
        row.setLayoutParams(lp);
        return row;
    }

    private int languageDialogListHeightPx() {
        int visibleHeight = currentVisibleWindowHeightPx();
        int target = visibleHeight > 0 ? Math.round(visibleHeight * 0.54f) : dpToPx(420);
        return Math.max(dpToPx(260), Math.min(dpToPx(420), target));
    }

    private List<LanguageOption> languageOptions() {
        ArrayList<LanguageOption> options = new ArrayList<>();
        options.add(new LanguageOption(PrefsManager.LANGUAGE_SYSTEM, getString(R.string.tts_language_system)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_ENGLISH, getString(R.string.language_english)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_KOREAN, getString(R.string.language_korean)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_JAPANESE, getString(R.string.language_japanese)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_CHINESE_SIMPLIFIED, getString(R.string.language_chinese_simplified)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_CHINESE_TRADITIONAL, getString(R.string.language_chinese_traditional)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_SPANISH, getString(R.string.language_spanish)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_FRENCH, getString(R.string.language_french)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_GERMAN, getString(R.string.language_german)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_ITALIAN, getString(R.string.language_italian)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_PORTUGUESE, getString(R.string.language_portuguese)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_RUSSIAN, getString(R.string.language_russian)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_ARABIC, getString(R.string.language_arabic)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_HINDI, getString(R.string.language_hindi)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_INDONESIAN, getString(R.string.language_indonesian)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_VIETNAMESE, getString(R.string.language_vietnamese)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_THAI, getString(R.string.language_thai)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_DUTCH, getString(R.string.language_dutch)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_POLISH, getString(R.string.language_polish)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_TURKISH, getString(R.string.language_turkish)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_UKRAINIAN, getString(R.string.language_ukrainian)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_GREEK, getString(R.string.language_greek)));
        options.add(new LanguageOption(PrefsManager.LANGUAGE_SWEDISH, getString(R.string.language_swedish)));
        return options;
    }

    private String languageLabelForMode(int mode) {
        int normalized = mode;
        if (normalized == PrefsManager.LANGUAGE_SYSTEM) {
            return getString(R.string.tts_language_system);
        }
        for (LanguageOption option : languageOptions()) {
            if (option.mode == normalized) return option.label;
        }
        return getString(R.string.language_english);
    }

    private void suppressLanguageRadioEffects() {
        // Language selection now uses a compact dialog instead of in-page radio rows.
    }

    private static final class LanguageOption {
        final int mode;
        final String label;

        LanguageOption(int mode, @NonNull String label) {
            this.mode = mode;
            this.label = label;
        }
    }

    private void setupDarkMode() {
        RadioGroup group = findViewById(R.id.dark_mode_group);
        int current = prefs.getDarkMode();
        if (current == PrefsManager.DARK_MODE_OFF) ((RadioButton) findViewById(R.id.radio_light)).setChecked(true);
        else if (current == PrefsManager.DARK_MODE_ON) ((RadioButton) findViewById(R.id.radio_dark)).setChecked(true);
        else if (current == PrefsManager.DARK_MODE_DARK_NAVY) ((RadioButton) findViewById(R.id.radio_dark_navy)).setChecked(true);
        else if (current == PrefsManager.DARK_MODE_CUSTOM) ((RadioButton) findViewById(R.id.radio_custom_main)).setChecked(true);
        else ((RadioButton) findViewById(R.id.radio_system)).setChecked(true);

        group.setOnCheckedChangeListener((g, checkedId) -> {
            int mode;
            if (checkedId == R.id.radio_light) mode = PrefsManager.DARK_MODE_OFF;
            else if (checkedId == R.id.radio_dark) mode = PrefsManager.DARK_MODE_ON;
            else if (checkedId == R.id.radio_dark_navy) mode = PrefsManager.DARK_MODE_DARK_NAVY;
            else if (checkedId == R.id.radio_custom_main) mode = PrefsManager.DARK_MODE_CUSTOM;
            else mode = PrefsManager.DARK_MODE_FOLLOW_SYSTEM;
            if (prefs.getDarkMode() != mode) {
                prefs.setDarkMode(mode);
                updateCustomMainThemeSectionVisibility();
                recreate();
            }
        });
    }

    private SettingsMainCustomThemeController mainCustomThemeController() {
        return new SettingsMainCustomThemeController(this);
    }

    private void setupCustomMainThemeColors() {
        mainCustomThemeController().setupCustomMainThemeColors();
    }

    private void refreshMainCustomHexFieldPreviews() {
        mainCustomThemeController().refreshMainCustomHexFieldPreviews();
    }

    private void updateCustomMainThemeSectionVisibility() {
        mainCustomThemeController().updateCustomMainThemeSectionVisibility();
    }

    private Integer parseHexColor(String raw) {
        return SettingsMainCustomThemeController.parseHexColor(raw);
    }

    private void applyMainCustomHexFieldPreview(@NonNull EditText field, int color) {
        mainCustomThemeController().applyMainCustomHexFieldPreview(field, color);
    }

    private void applyMainCustomHexFieldInvalidPreview(@NonNull EditText field) {
        mainCustomThemeController().applyMainCustomHexFieldInvalidPreview(field);
    }

    private boolean isMainCustomHexField(@NonNull EditText field) {
        return mainCustomThemeController().isMainCustomHexField(field);
    }

    private void setupReaderControls() {
        new SettingsReaderControlsController(this).setupReaderControls();
    }

    private void setupButtonOrderSettings() {
        new SettingsButtonOrderController(this).setupButtonOrderSettings();
    }

    private void setupTextDisplayRules() {
        View button = findViewById(R.id.btn_txt_display_rules);
        if (button == null) return;
        button.setOnClickListener(v -> showTextDisplayRulesDialog());
    }

    private void setupTextDisplayRulesActualFile() {
        View button = findViewById(R.id.btn_txt_display_rules_actual_file);
        if (button == null) return;
        button.setOnClickListener(v -> {
            if (currentTxtFilePath == null || currentTxtFilePath.isEmpty()) {
                ShortToast.show(this, R.string.txt_display_rules_actual_file_unavailable);
                return;
            }
            showTextDisplayRulesActualFileDialog();
        });
    }

    private void showTextDisplayRulesActualFileDialog() {
        File sourceFile = getCurrentTxtFileOrShowError();
        if (sourceFile == null) return;

        List<TextDisplayRule> activeRules = TextDisplayRuleManager.getActiveRules(this, sourceFile.getAbsolutePath());
        if (activeRules.isEmpty()) {
            ShortToast.show(this, R.string.txt_display_rules_actual_file_no_rules);
            return;
        }

        final android.app.Dialog dialog = createRoundedSettingsDialog();
        LinearLayout panel = createRoundedSettingsDialogPanel();

        int text = dialogTextColor();
        int sub = dialogSubTextColor();
        int outline = dialogOutlineColor();

        panel.addView(makeSettingsDialogTitle(getString(R.string.txt_display_rules_actual_file_title), text));

        TextView message = makeSettingsDialogMessage(
                getString(R.string.txt_display_rules_actual_file_message, sourceFile.getName()), sub);
        message.setGravity(Gravity.CENTER);
        panel.addView(message);

        File copyTarget = makeActualRuleCopyFile(this, sourceFile);
        TextView sequenceWarning = makeSettingsDialogWarningBox(
                getString(R.string.txt_display_rules_actual_file_sequence_warning, activeRules.size()), false);
        panel.addView(sequenceWarning);

        TextView overwriteWarning = makeSettingsDialogWarningBox(
                getString(R.string.txt_display_rules_actual_file_overwrite_warning, copyTarget.getName()), true);
        LinearLayout.LayoutParams overwriteWarningLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        overwriteWarningLp.setMargins(0, dpToPx(6), 0, dpToPx(8));
        overwriteWarning.setLayoutParams(overwriteWarningLp);
        panel.addView(overwriteWarning);

        if (sourceFile.length() >= TXT_ACTUAL_FILE_EDIT_LARGE_WARNING_BYTES) {
            TextView largeWarning = makeSettingsDialogWarningBox(
                    getString(R.string.txt_display_rules_actual_file_large_warning,
                            FileUtils.formatFileSize(sourceFile.length())), false);
            LinearLayout.LayoutParams largeWarningLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            largeWarningLp.setMargins(0, 0, 0, dpToPx(8));
            largeWarning.setLayoutParams(largeWarningLp);
            panel.addView(largeWarning);
        }

        MaterialButton original = makeTextRuleDialogButton(
                getString(R.string.txt_display_rules_actual_file_original), text);
        original.setOnClickListener(v -> {
            dialog.dismiss();
            showTextDisplayRulesActualFileConfirmDialog(true, sourceFile, copyTarget, activeRules.size());
        });
        panel.addView(original);

        MaterialButton copy = makeTextRuleDialogButton(
                getString(R.string.txt_display_rules_actual_file_copy), text);
        copy.setOnClickListener(v -> {
            dialog.dismiss();
            showTextDisplayRulesActualFileConfirmDialog(false, sourceFile, copyTarget, activeRules.size());
        });
        panel.addView(copy);

        MaterialButton cancel = makeTextRuleDialogButton(getString(R.string.cancel), text);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        showRoundedSettingsDialog(dialog, panel, true, 0.72f, 280);
    }

    private CharSequence makeActualFileConfirmMergedWarning(boolean editOriginal, @NonNull String targetName) {
        String overwriteText = getString(editOriginal
                ? R.string.txt_display_rules_actual_file_confirm_original_warning
                : R.string.txt_display_rules_actual_file_confirm_copy_warning, targetName);
        String noTurningBackText = getString(R.string.txt_display_rules_actual_file_no_turning_back);
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(overwriteText);
        builder.append("\n\n");
        int start = builder.length();
        builder.append(noTurningBackText);
        int end = builder.length();
        builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new RelativeSizeSpan(1.28f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    private void showTextDisplayRulesActualFileConfirmDialog(boolean editOriginal,
                                                            @NonNull File sourceFile,
                                                            @NonNull File copyTarget,
                                                            int ruleCount) {
        final android.app.Dialog dialog = createRoundedSettingsDialog();
        LinearLayout panel = createRoundedSettingsDialogPanel();

        int text = dialogTextColor();
        int sub = dialogSubTextColor();
        int outline = dialogOutlineColor();

        panel.addView(makeSettingsDialogTitle(getString(R.string.txt_display_rules_actual_file_confirm_title), text));

        String targetName = editOriginal ? sourceFile.getName() : copyTarget.getName();
        TextView message = makeSettingsDialogMessage(
                getString(editOriginal
                                ? R.string.txt_display_rules_actual_file_confirm_original_message
                                : R.string.txt_display_rules_actual_file_confirm_copy_message,
                        targetName, ruleCount), sub);
        message.setGravity(Gravity.CENTER);
        panel.addView(message);

        TextView overwriteWarning = makeSettingsDialogWarningBox(
                makeActualFileConfirmMergedWarning(editOriginal, targetName), true);
        LinearLayout.LayoutParams overwriteWarningLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        overwriteWarningLp.setMargins(0, dpToPx(6), 0, dpToPx(8));
        overwriteWarning.setLayoutParams(overwriteWarningLp);
        panel.addView(overwriteWarning);

        if (sourceFile.length() >= TXT_ACTUAL_FILE_EDIT_LARGE_WARNING_BYTES) {
            TextView largeWarning = makeSettingsDialogWarningBox(
                    getString(R.string.txt_display_rules_actual_file_large_warning,
                            FileUtils.formatFileSize(sourceFile.length())), false);
            LinearLayout.LayoutParams largeWarningLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            largeWarningLp.setMargins(0, 0, 0, dpToPx(8));
            largeWarning.setLayoutParams(largeWarningLp);
            panel.addView(largeWarning);
        }

        MaterialButton apply = makeTextRuleDialogButton(
                getString(editOriginal
                        ? R.string.txt_display_rules_actual_file_confirm_original_button
                        : R.string.txt_display_rules_actual_file_confirm_copy_button), text);
        apply.setOnClickListener(v -> {
            dialog.dismiss();
            applyTextDisplayRulesToActualFile(editOriginal);
        });
        panel.addView(apply);

        MaterialButton cancel = makeTextRuleDialogButton(getString(R.string.cancel), text);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        showRoundedSettingsDialog(dialog, panel, true, 0.72f, 280);
    }

    private File getCurrentTxtFileOrShowError() {
        if (currentTxtFilePath == null || currentTxtFilePath.isEmpty()) {
            ShortToast.show(this, R.string.txt_display_rules_actual_file_unavailable);
            return null;
        }
        File sourceFile = new File(currentTxtFilePath);
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            ShortToast.show(this, R.string.txt_display_rules_actual_file_unavailable);
            return null;
        }
        return sourceFile.getAbsoluteFile();
    }

    private void applyTextDisplayRulesToActualFile(boolean editOriginal) {
        final File sourceFile = getCurrentTxtFileOrShowError();
        if (sourceFile == null) return;

        final Context appContext = getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ArrayList<TextDisplayRule> activeRules = new ArrayList<>(
                TextDisplayRuleManager.getActiveRules(appContext, sourceFile.getAbsolutePath()));
        if (activeRules.isEmpty()) {
            ShortToast.show(appContext, R.string.txt_display_rules_actual_file_no_rules);
            return;
        }

        ShortToast.show(appContext, R.string.txt_display_rules_actual_file_applying);
        new Thread(() -> {
            try {
                String encoding = FileUtils.detectEncoding(sourceFile);
                String originalText = FileUtils.readTextFile(sourceFile, encoding);
                String fixedText = TextDisplayRuleManager.apply(originalText, activeRules);
                if (originalText.equals(fixedText)) {
                    mainHandler.post(() -> ShortToast.show(
                            appContext,
                            R.string.txt_display_rules_actual_file_no_changes));
                    return;
                }

                File outputFile = editOriginal ? sourceFile : makeActualRuleCopyFile(appContext, sourceFile);
                writeTextWithEncodingSafely(outputFile, fixedText, encoding);
                if (editOriginal) {
                    markOriginalTxtFilePhysicallyModified(appContext, outputFile);
                }

                final String successMessage = editOriginal
                        ? appContext.getString(R.string.txt_display_rules_actual_file_original_done)
                        : appContext.getString(R.string.txt_display_rules_actual_file_copy_done, outputFile.getName());
                mainHandler.post(() -> Toast.makeText(
                        appContext,
                        successMessage,
                        Toast.LENGTH_LONG).show());
            } catch (OutOfMemoryError oom) {
                mainHandler.post(() -> Toast.makeText(
                        appContext,
                        appContext.getString(R.string.txt_display_rules_actual_file_failed,
                                appContext.getString(R.string.txt_display_rules_actual_file_too_large_runtime)),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = e.getClass().getSimpleName();
                }
                final String finalMessage = message;
                mainHandler.post(() -> Toast.makeText(
                        appContext,
                        appContext.getString(R.string.txt_display_rules_actual_file_failed, finalMessage),
                        Toast.LENGTH_LONG).show());
            }
        }, "txt-display-rules-actual-file").start();
    }

    private static void markOriginalTxtFilePhysicallyModified(@NonNull Context context, @NonNull File file) {
        context.getSharedPreferences(TXT_ACTUAL_FILE_EDIT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TXT_ACTUAL_FILE_EDIT_PATH, file.getAbsolutePath())
                .putLong(KEY_TXT_ACTUAL_FILE_EDIT_TOKEN, System.currentTimeMillis())
                .putLong(KEY_TXT_ACTUAL_FILE_EDIT_LENGTH, file.length())
                .putLong(KEY_TXT_ACTUAL_FILE_EDIT_LAST_MODIFIED, file.lastModified())
                .apply();
    }

    private static File makeActualRuleCopyFile(@NonNull Context context, @NonNull File sourceFile) {
        File parent = sourceFile.getParentFile();
        if (parent == null) parent = context.getFilesDir();
        String name = sourceFile.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        return new File(parent, base + "_edited" + ext);
    }

    private static void writeTextWithEncodingSafely(@NonNull File outputFile,
                                                    @NonNull String text,
                                                    String encoding) throws IOException {
        Charset charset;
        try {
            charset = (encoding != null && !encoding.trim().isEmpty())
                    ? Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
        } catch (Exception ignored) {
            charset = StandardCharsets.UTF_8;
        }

        File target = outputFile.getAbsoluteFile();
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create folder: " + parent.getAbsolutePath());
        }
        if (parent == null) {
            throw new IOException("Cannot resolve output folder");
        }

        File temp = File.createTempFile(target.getName() + ".", ".tmp", parent);
        boolean moved = false;
        try (FileOutputStream fos = new FileOutputStream(temp, false);
             OutputStreamWriter writer = new OutputStreamWriter(fos, charset)) {
            writer.write(text);
            writer.flush();
            fos.getFD().sync();
        }

        try {
            try {
                // Same-directory POSIX rename replaces the target atomically on Android's
                // Linux-backed file systems, so a crash is much less likely to leave the
                // original file half-written.
                Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
                moved = true;
            } catch (ErrnoException errno) {
                // Conservative fallback for unusual storage providers/filesystems.
                if (temp.renameTo(target)) {
                    moved = true;
                } else {
                    throw new IOException("Cannot replace output file safely", errno);
                }
            }
        } finally {
            if (!moved && temp.exists()) {
                // Best-effort cleanup.  If deletion fails, the temp file is harmless and
                // keeps the original target untouched.
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private void showTextDisplayRulesDialog() {
        new SettingsTextDisplayRuleController(this).showTextDisplayRulesDialog();
    }

    private boolean isDarkUi() {
        if (prefs != null) return prefs.shouldUseDarkColors(this);
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private ColorStateList twoState(int checkedColor, int uncheckedColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{checkedColor, uncheckedColor}
        );
    }

    private void applySettingsReadableTheme() {
        boolean dark = isDarkUi();

        int bg = prefs != null ? prefs.getMainBgColor(this) : (dark ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255));
        int panel = prefs != null ? prefs.getMainPanelColor(this) : (dark ? Color.rgb(16, 16, 16) : Color.rgb(248, 249, 250));
        int text = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36));
        int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(176, 176, 176) : Color.rgb(95, 99, 104));
        int bar = prefs != null ? prefs.getMainBarColor(this) : (dark ? Color.rgb(0, 0, 0) : Color.rgb(32, 33, 36));
        int outline = prefs != null ? prefs.getMainOutlineColor(this) : (dark ? Color.rgb(70, 70, 70) : Color.rgb(210, 210, 210));

        // Monochrome / navy-safe controls. Keep checked/unchecked states visible on dark navy.
        int checked = prefs != null ? prefs.getMainControlColor(this) : (dark ? Color.rgb(210, 210, 210) : Color.rgb(80, 80, 80));
        int uncheckedThumb = prefs != null && prefs.isDarkNavyMode() ? Color.rgb(116, 143, 178) : (dark ? Color.rgb(145, 145, 145) : Color.rgb(170, 170, 170));
        int uncheckedTrack = prefs != null && prefs.isDarkNavyMode() ? Color.rgb(34, 53, 78) : (dark ? Color.rgb(70, 70, 70) : Color.rgb(210, 210, 210));
        int checkedTrack = prefs != null && prefs.isDarkNavyMode() ? Color.rgb(55, 82, 115) : (dark ? Color.rgb(95, 95, 95) : Color.rgb(180, 180, 180));

        View root = findViewById(R.id.settings_root);
        View appbar = findViewById(R.id.settings_appbar);
        View scroll = findViewById(R.id.settings_scroll);
        Toolbar toolbar = findViewById(R.id.toolbar);

        if (root != null) root.setBackgroundColor(bg);
        if (scroll != null) scroll.setBackgroundColor(bg);
        if (appbar != null) appbar.setBackgroundColor(bar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(bar);
            toolbar.setTitleTextColor(Color.WHITE);
            tintToolbarNavigation(toolbar, Color.WHITE);
        }

        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bg);

        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(!dark);

        ColorStateList radioButtonTint = twoState(checked, uncheckedThumb);
        ColorStateList switchThumbTint = twoState(checked, uncheckedThumb);
        ColorStateList switchTrackTint = twoState(checkedTrack, uncheckedTrack);
        ColorStateList seekTint = ColorStateList.valueOf(checked);
        ColorStateList seekBgTint = ColorStateList.valueOf(outline);
        ColorStateList outlineTint = ColorStateList.valueOf(outline);
        ColorStateList rippleTint = ColorStateList.valueOf(panel);

        applyReadableColorsRecursive(root, text, sub, bg,
                radioButtonTint, switchThumbTint, switchTrackTint,
                seekTint, seekBgTint, outlineTint, rippleTint);

        TextView updateLink = findViewById(R.id.update_release_link);
        if (updateLink != null) {
            updateLink.setTextColor(checked);
            }

        // Page Overlap spinner: no weird boxed field. Blend into background.
        Spinner overlapSpinner = findViewById(R.id.spinner_overlap_lines);
        Spinner tapZoneSpinner = findViewById(R.id.spinner_tap_zone_mode);
        Spinner largeTxtModeSpinner = findViewById(R.id.spinner_large_txt_partition_mode);
        Spinner epubDirectionSpinner = findViewById(R.id.spinner_epub_page_direction);
        Spinner[] spinners = new Spinner[]{overlapSpinner, tapZoneSpinner, largeTxtModeSpinner, epubDirectionSpinner};
        for (Spinner spinner : spinners) {
            if (spinner == null) continue;
            spinner.setBackgroundColor(bg);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                spinner.setBackgroundTintList(ColorStateList.valueOf(bg));
            }
            int spinPad = Math.round(14 * getResources().getDisplayMetrics().density);
            spinner.setPadding(spinPad, 0, spinPad, 0);
        }

        TextView font = findViewById(R.id.font_size_label);
        if (font != null) font.setTextColor(sub);
        TextView spacing = findViewById(R.id.line_spacing_label);
        if (spacing != null) spacing.setTextColor(sub);
        TextView boundaryDescription = findViewById(R.id.txt_boundary_description);
        if (boundaryDescription != null) boundaryDescription.setTextColor(sub);
        TextView topOffset = findViewById(R.id.txt_top_offset_label);
        if (topOffset != null) topOffset.setTextColor(sub);
        TextView bottomOffset = findViewById(R.id.txt_bottom_offset_label);
        if (bottomOffset != null) bottomOffset.setTextColor(sub);
        TextView leftInset = findViewById(R.id.txt_left_inset_label);
        if (leftInset != null) leftInset.setTextColor(sub);
        TextView rightInset = findViewById(R.id.txt_right_inset_label);
        if (rightInset != null) rightInset.setTextColor(sub);
        TextView epubLeft = findViewById(R.id.epub_left_spacing_label);
        if (epubLeft != null) epubLeft.setTextColor(sub);
        TextView epubRight = findViewById(R.id.epub_right_spacing_label);
        if (epubRight != null) epubRight.setTextColor(sub);
        TextView epubTop = findViewById(R.id.epub_top_spacing_label);
        if (epubTop != null) epubTop.setTextColor(sub);
        TextView epubBottom = findViewById(R.id.epub_bottom_spacing_label);
        if (epubBottom != null) epubBottom.setTextColor(sub);
        TextView ratio = findViewById(R.id.tap_zone_ratio_label);
        if (ratio != null) ratio.setTextColor(sub);
        TextView leading = findViewById(R.id.tap_zone_leading_label);
        if (leading != null) leading.setTextColor(sub);
        TextView trailing = findViewById(R.id.tap_zone_trailing_label);
        if (trailing != null) trailing.setTextColor(sub);
    }

    private void applyReadableColorsRecursive(
            View view,
            int text,
            int sub,
            int bg,
            ColorStateList radioButtonTint,
            ColorStateList switchThumbTint,
            ColorStateList switchTrackTint,
            ColorStateList seekTint,
            ColorStateList seekBgTint,
            ColorStateList outlineTint,
            ColorStateList rippleTint
    ) {
        if (view == null) return;

        if (view instanceof EditText) {
            EditText et = (EditText) view;
            if (isMainCustomHexField(et)) {
                Integer parsed = parseHexColor(et.getText() != null ? et.getText().toString() : null);
                if (parsed != null) {
                    applyMainCustomHexFieldPreview(et, parsed);
                } else {
                    applyMainCustomHexFieldInvalidPreview(et);
                }
            } else {
                et.setTextColor(text);
                et.setHintTextColor(sub);
                et.setBackgroundColor(bg);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    et.setBackgroundTintList(outlineTint);
                }
            }
        } else if (view instanceof Switch) {
            Switch sw = (Switch) view;
            sw.setTextColor(text);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                sw.setThumbTintList(switchThumbTint);
                sw.setTrackTintList(switchTrackTint);
            }
        } else if (view instanceof RadioButton) {
            RadioButton rb = (RadioButton) view;
            rb.setTextColor(text);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                rb.setButtonTintList(radioButtonTint);
            }
        } else if (view instanceof CompoundButton) {
            CompoundButton cb = (CompoundButton) view;
            cb.setTextColor(text);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cb.setButtonTintList(radioButtonTint);
            }
        } else if (view instanceof MaterialButton) {
            MaterialButton mb = (MaterialButton) view;
            mb.setTextColor(text);
            mb.setStrokeColor(outlineTint);
            mb.setRippleColor(rippleTint);
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            tv.setTextColor(text);
        }

        if (view instanceof SeekBar && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            SeekBar sb = (SeekBar) view;
            sb.setThumbTintList(seekTint);
            sb.setProgressTintList(seekTint);
            sb.setProgressBackgroundTintList(seekBgTint);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyReadableColorsRecursive(group.getChildAt(i), text, sub, bg,
                        radioButtonTint, switchThumbTint, switchTrackTint,
                        seekTint, seekBgTint, outlineTint, rippleTint);
            }
        }
    }

    private void tintToolbarNavigation(Toolbar toolbar, int color) {
        Drawable nav = toolbar.getNavigationIcon();
        if (nav != null) {
            Drawable wrapped = DrawableCompat.wrap(nav.mutate());
            DrawableCompat.setTint(wrapped, color);
            toolbar.setNavigationIcon(wrapped);
        }
    }


    private void setupLock() {
        Switch switchLock = findViewById(R.id.switch_lock);
        switchLock.setChecked(prefs.isLockEnabled());
        switchLock.setOnCheckedChangeListener((v, checked) -> {
            if (checked) {
                Intent intent = new Intent(this, LockActivity.class);
                intent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_SET_PIN);
                lockSetLauncher.launch(intent);
            } else {
                prefs.setLockEnabled(false);
                prefs.setLockPin("");
            }
        });

        findViewById(R.id.btn_change_pin).setOnClickListener(v -> {
            if (prefs.isLockEnabled()) {
                Intent intent = new Intent(this, LockActivity.class);
                intent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_CHANGE_PIN);
                lockSetLauncher.launch(intent);
            } else {
                ShortToast.show(this, getString(R.string.enable_lock_first));
            }
        });
    }

    private void setupExportImport() {
        findViewById(R.id.btn_export).setOnClickListener(v -> exportLauncher.launch(makeBackupFileName()));
        findViewById(R.id.btn_import).setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "text/*"}));
    }


    private void setupResetSettings() {
        View reset = findViewById(R.id.btn_reset_settings);
        if (reset == null) return;
        reset.setOnClickListener(v -> showResetSettingsConfirmDialog());
    }

    private void showResetSettingsConfirmDialog() {
        final android.app.Dialog dialog = createRoundedSettingsDialog();
        LinearLayout panel = createRoundedSettingsDialogPanel();

        int text = dialogTextColor();
        int sub = dialogSubTextColor();
        int outline = dialogOutlineColor();

        panel.addView(makeSettingsDialogTitle(getString(R.string.settings_reset_confirm_title), text));
        panel.addView(makeSettingsDialogMessage(getString(R.string.settings_reset_confirm_message), sub));

        MaterialButton reset = makeSettingsDialogButtonNoShade(getString(R.string.settings_reset), text, outline);
        reset.setOnClickListener(v -> {
            prefs.resetReaderAndAppSettings();
            ShortToast.show(this, R.string.settings_reset_done);
            dialog.dismiss();
            recreate();
        });
        panel.addView(reset);

        MaterialButton cancel = makeSettingsDialogButtonNoShade(getString(R.string.cancel), text, outline);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        showRoundedSettingsDialog(dialog, panel, true);
    }

    private String makeBackupFileName() {
        String timestamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(new Date());
        return "readwide_backup_" + timestamp + ".json";
    }

    private void setupTheme() {
        findViewById(R.id.btn_manage_themes).setOnClickListener(v ->
                startActivity(new Intent(this, ThemeEditorActivity.class)));
    }

    private void renderReadingThemeRows() {
        LinearLayout container = findViewById(R.id.reading_theme_list);
        if (container == null || themeManager == null) return;

        container.removeAllViews();

        List<Theme> themes = themeManager.getAllThemes();
        Theme activeTheme = themeManager.getActiveTheme();
        String activeId = activeTheme != null ? activeTheme.getId() : "";

        boolean dark = prefs != null ? prefs.shouldUseDarkColors(this) : isDarkUi();
        int rowBg = prefs != null
                ? prefs.getMainReadingThemeCardColor(this)
                : (dark ? Color.rgb(10, 10, 10) : Color.rgb(255, 255, 255));
        int text = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36));
        int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(176, 176, 176) : Color.rgb(95, 99, 104));
        int outline = prefs != null
                ? prefs.getMainOutlineColor(this)
                : (dark ? Color.rgb(70, 70, 70) : Color.rgb(218, 220, 224));
        int selectedOutline = toneAwareReadingThemeSelectedOutlineColor(outline, dark);

        for (Theme theme : themes) {
            boolean selected = theme.getId().equals(activeId);
            View row = makeReadingThemeRow(theme, selected, rowBg, text, sub,
                    outline, selectedOutline);
            row.setOnClickListener(v -> {
                themeManager.setActiveTheme(theme.getId());
                renderReadingThemeRows();
            });
            if (!theme.isBuiltIn()) {
                row.setOnLongClickListener(v -> {
                    showCustomThemeActionsDialog(theme);
                    return true;
                });
            }
            container.addView(row);
        }
    }

    private int toneAwareReadingThemeSelectedOutlineColor(int outline, boolean darkTone) {
        return darkTone ? brightenColor(outline, 0.30f) : darkenColor(outline, 0.22f);
    }

    private int brightenColor(int color, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(color) + (255 - Color.red(color)) * amount);
        int g = Math.round(Color.green(color) + (255 - Color.green(color)) * amount);
        int b = Math.round(Color.blue(color) + (255 - Color.blue(color)) * amount);
        return Color.rgb(r, g, b);
    }

    private int darkenColor(int color, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(color) * (1f - amount));
        int g = Math.round(Color.green(color) * (1f - amount));
        int b = Math.round(Color.blue(color) * (1f - amount));
        return Color.rgb(r, g, b);
    }

    private View makeReadingThemeRow(Theme theme, boolean selected, int rowBg,
                                     int text, int sub, int outline, int selectedOutline) {
        int pad12 = dpToPx(12);
        int pad14 = dpToPx(14);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(pad14, pad12, pad14, pad12);

        GradientDrawable rowDrawable = new GradientDrawable();
        rowDrawable.setColor(rowBg);
        rowDrawable.setCornerRadius(dpToPx(12));
        rowDrawable.setStroke(dpToPx(selected ? 2 : 1), selected ? selectedOutline : outline);
        row.setBackground(rowDrawable);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dpToPx(8));
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setFocusable(true);

        View preview = new View(this);
        GradientDrawable previewDrawable = new GradientDrawable();
        previewDrawable.setColor(theme.getBackgroundColor());
        previewDrawable.setCornerRadius(dpToPx(8));
        previewDrawable.setStroke(dpToPx(1), outline);
        preview.setBackground(previewDrawable);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
        previewLp.setMarginEnd(pad12);
        row.addView(preview, previewLp);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        row.addView(textBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(theme.getName());
        name.setTextColor(text);
        name.setTextSize(16f);
        name.setSingleLine(true);
        textBox.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sample = new TextView(this);
        sample.setText(getString(R.string.theme_list_preview_text));
        sample.setTextColor(theme.getTextColor());
        sample.setTextSize(12f);
        sample.setSingleLine(true);
        sample.setPadding(pad12, dpToPx(4), pad12, dpToPx(4));
        GradientDrawable sampleBg = new GradientDrawable();
        sampleBg.setColor(theme.getBackgroundColor());
        sampleBg.setCornerRadius(dpToPx(6));
        sample.setBackground(sampleBg);
        LinearLayout.LayoutParams sampleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sampleLp.setMargins(0, dpToPx(4), 0, 0);
        textBox.addView(sample, sampleLp);

        TextView check = new TextView(this);
        check.setText(selected ? "\u2713" : "");
        check.setTextColor(selected ? selectedOutline : text);
        check.setTextSize(24f);
        check.setGravity(android.view.Gravity.CENTER);
        row.addView(check, new LinearLayout.LayoutParams(dpToPx(36),
                LinearLayout.LayoutParams.MATCH_PARENT));


        return row;
    }

    private void showCustomThemeActionsDialog(@NonNull Theme theme) {
        final android.app.Dialog dialog = createRoundedSettingsDialog();
        LinearLayout panel = createRoundedSettingsDialogPanel();

        int text = dialogTextColor();
        int sub = dialogSubTextColor();
        int outline = dialogOutlineColor();

        TextView title = makeSettingsDialogTitle(theme.getName(), text);
        panel.addView(title);

        TextView message = makeSettingsDialogMessage(getString(R.string.custom_theme_options_message), sub);
        panel.addView(message);

        MaterialButton edit = makeSettingsDialogButtonNoShade(getString(R.string.edit_theme), text, outline);
        edit.setOnClickListener(v -> {
            dialog.dismiss();
            Intent editIntent = new Intent(this, ThemeEditorActivity.class);
            editIntent.putExtra(ThemeEditorActivity.EXTRA_THEME_ID, theme.getId());
            startActivity(editIntent);
        });
        panel.addView(edit);

        MaterialButton delete = makeSettingsDialogButtonNoShade(getString(R.string.delete_theme), text, outline);
        delete.setOnClickListener(v -> {
            dialog.dismiss();
            showDeleteCustomThemeDialog(theme);
        });
        panel.addView(delete);

        MaterialButton cancel = makeSettingsDialogButtonNoShade(getString(R.string.cancel), text, outline);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        showRoundedSettingsDialog(dialog, panel, true);
    }

    private void showDeleteCustomThemeDialog(@NonNull Theme theme) {
        final android.app.Dialog dialog = createRoundedSettingsDialog();
        LinearLayout panel = createRoundedSettingsDialogPanel();

        int text = dialogTextColor();
        int sub = dialogSubTextColor();
        int outline = dialogOutlineColor();

        TextView title = makeSettingsDialogTitle(getString(R.string.delete_theme), text);
        panel.addView(title);

        TextView message = makeSettingsDialogMessage(
                getString(R.string.delete_theme_confirm, theme.getName()), sub);
        panel.addView(message);

        MaterialButton delete = makeSettingsDialogButtonNoShade(getString(R.string.delete), text, outline);
        delete.setOnClickListener(v -> {
            dialog.dismiss();
            themeManager.deleteCustomTheme(theme.getId());
            themeManager.reloadFromStorage();
            applySettingsReadableTheme();
            renderReadingThemeRows();
            ShortToast.show(this, getString(R.string.theme_deleted));
        });
        panel.addView(delete);

        MaterialButton cancel = makeSettingsDialogButtonNoShade(getString(R.string.cancel), text, outline);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        showRoundedSettingsDialog(dialog, panel, true);
    }

    android.app.Dialog createRoundedSettingsDialog() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        return dialog;
    }

    LinearLayout createRoundedSettingsDialogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(18);
        panel.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dialogPanelBackgroundColor());
        bg.setCornerRadius(dpToPx(22));
        bg.setStroke(dpToPx(1), dialogOutlineColor());
        panel.setBackground(bg);

        return panel;
    }

    void showRoundedSettingsDialog(android.app.Dialog dialog, LinearLayout panel) {
        showRoundedSettingsDialog(dialog, panel, false);
    }

    void showRoundedSettingsDialog(android.app.Dialog dialog, LinearLayout panel, boolean compactWidth) {
        showRoundedSettingsDialog(dialog, panel, compactWidth, 0.70f, 240);
    }

    void showRoundedSettingsDialog(android.app.Dialog dialog, LinearLayout panel, boolean compactWidth, float compactWidthFraction, int compactMinWidthDp) {
        ScrollView adaptiveScroll = new ScrollView(this);
        adaptiveScroll.setFillViewport(false);
        adaptiveScroll.setClipChildren(true);
        adaptiveScroll.setClipToPadding(true);
        adaptiveScroll.setVerticalScrollBarEnabled(false);
        adaptiveScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        adaptiveScroll.addView(panel, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(adaptiveScroll);
        prepareRoundedSettingsDialogWindow(dialog, true, compactWidth, compactWidthFraction, compactMinWidthDp);
        dialog.setOnShowListener(d -> {
            prepareRoundedSettingsDialogWindow(dialog, false, compactWidth, compactWidthFraction, compactMinWidthDp);
            applyAdaptiveSettingsDialogMaxHeight(adaptiveScroll);
            android.view.Window shownWindow = dialog.getWindow();
            if (shownWindow != null) {
                shownWindow.getDecorView().post(() -> shownWindow.getDecorView().setAlpha(1f));
            }
        });
        dialog.show();
        prepareRoundedSettingsDialogWindow(dialog, false, compactWidth, compactWidthFraction, compactMinWidthDp);
    }

    private void applyAdaptiveSettingsDialogMaxHeight(@NonNull View adaptiveView) {
        int widthPx = Math.min(getResources().getDisplayMetrics().widthPixels - dpToPx(40), dpToPx(420));
        AdaptiveDialogLayoutHelper.applyAdaptiveMaxHeight(this, adaptiveView, widthPx);
    }

    private boolean shouldApplyAdaptiveDialogMaxHeight(int availableHeightPx) {
        return AdaptiveDialogLayoutHelper.shouldApplyAdaptiveMaxHeight(this, availableHeightPx);
    }

    int currentVisibleWindowHeightPx() {
        return AdaptiveDialogLayoutHelper.currentVisibleWindowHeightPx(this);
    }

    private void prepareRoundedSettingsDialogWindow(android.app.Dialog dialog, boolean hideUntilLaidOut) {
        prepareRoundedSettingsDialogWindow(dialog, hideUntilLaidOut, false);
    }

    private void prepareRoundedSettingsDialogWindow(android.app.Dialog dialog, boolean hideUntilLaidOut, boolean compactWidth) {
        prepareRoundedSettingsDialogWindow(dialog, hideUntilLaidOut, compactWidth, 0.70f, 240);
    }

    private void prepareRoundedSettingsDialogWindow(android.app.Dialog dialog, boolean hideUntilLaidOut, boolean compactWidth, float compactWidthFraction, int compactMinWidthDp) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;

        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setGravity(android.view.Gravity.CENTER);
        window.setWindowAnimations(0);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        if (compactWidth) {
            // Short custom-theme action/delete popups should be visibly compact.
            // Use the actual screen percentage, not the normal 420dp settings-dialog cap.
            lp.width = Math.max(dpToPx(compactMinWidthDp), Math.round(screenWidth * compactWidthFraction));
        } else {
            lp.width = Math.min(
                    screenWidth - dpToPx(40),
                    dpToPx(420));
        }
        lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.18f;
        lp.x = 0;
        lp.y = dpToPx(44);
        window.setAttributes(lp);
        window.setLayout(lp.width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);

        if (hideUntilLaidOut) {
            window.getDecorView().setAlpha(0f);
        }
    }

    TextView makeSettingsDialogTitle(String value, int color) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextColor(color);
        title.setTextSize(18f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setSingleLine(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(10));
        title.setLayoutParams(lp);
        return title;
    }

    TextView makeSettingsDialogMessage(String value, int color) {
        TextView message = new TextView(this);
        message.setText(value);
        message.setTextColor(color);
        message.setTextSize(14f);
        message.setGravity(android.view.Gravity.CENTER);
        message.setLineSpacing(0, 1.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(12));
        message.setLayoutParams(lp);
        return message;
    }

    private TextView makeSettingsDialogWarning(String value, int textColor, int outlineColor) {
        return makeSettingsDialogWarning(value, textColor, dialogRowBackgroundColor(), outlineColor);
    }

    private TextView makeSettingsDialogWarningBox(CharSequence value, boolean severe) {
        return makeSettingsDialogWarning(
                value,
                warningTextColor(severe),
                warningBackgroundColor(severe),
                warningOutlineColor(severe));
    }

    private TextView makeSettingsDialogWarning(CharSequence value, int textColor, int bgColor, int outlineColor) {
        TextView warning = new TextView(this);
        warning.setText(value);
        warning.setTextColor(textColor);
        warning.setTextSize(12.5f);
        warning.setGravity(android.view.Gravity.CENTER);
        warning.setLineSpacing(0, 1.08f);
        warning.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int padH = dpToPx(12);
        int padV = dpToPx(9);
        warning.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dpToPx(14));
        bg.setStroke(dpToPx(1), outlineColor);
        warning.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 0);
        warning.setLayoutParams(lp);
        return warning;
    }

    private int warningBackgroundColor(boolean severe) {
        if (severe) {
            return isDarkUi() ? Color.rgb(92, 27, 32) : Color.rgb(248, 215, 218);
        }
        return isDarkUi() ? Color.rgb(92, 70, 20) : Color.rgb(255, 243, 205);
    }

    private int warningOutlineColor(boolean severe) {
        if (severe) {
            return isDarkUi() ? Color.rgb(170, 68, 78) : Color.rgb(220, 53, 69);
        }
        return isDarkUi() ? Color.rgb(188, 140, 36) : Color.rgb(230, 175, 46);
    }

    private int warningTextColor(boolean severe) {
        if (severe) {
            return isDarkUi() ? Color.rgb(255, 226, 230) : Color.rgb(132, 32, 41);
        }
        return isDarkUi() ? Color.rgb(255, 243, 205) : Color.rgb(95, 67, 0);
    }

    private MaterialButton makeSettingsDialogButton(String label, int text, int bg, int outline) {
        return makeSettingsDialogButton(label, text, bg, outline, false);
    }

    private MaterialButton makeSettingsDialogButtonNoShade(String label, int text, int outline) {
        return makeSettingsDialogButton(label, text, dialogActionButtonBackgroundColor(), outline, true);
    }

    MaterialButton makeTextRuleDialogButton(String label, int text) {
        MaterialButton button = makeSettingsDialogButton(label, text,
                dialogActionButtonBackgroundColor(), Color.TRANSPARENT, true);
        button.setStrokeWidth(0);
        button.setStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
        return button;
    }

    private MaterialButton makeSettingsDialogButton(String label, int text, int bg, int outline,
                                                    boolean noShade) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15f);
        button.setTextColor(text);
        button.setGravity(android.view.Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setIncludeFontPadding(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dpToPx(44));
        button.setMinimumHeight(dpToPx(44));
        button.setCornerRadius(dpToPx(14));
        button.setStrokeWidth(dpToPx(1));
        button.setStrokeColor(ColorStateList.valueOf(outline));
        button.setBackgroundTintList(ColorStateList.valueOf(bg));
        button.setElevation(0f);
        button.setTranslationZ(0f);
        button.setStateListAnimator(null);
        button.setRippleColor(ColorStateList.valueOf(noShade ? Color.TRANSPARENT :
                (isDarkUi() ? Color.rgb(58, 58, 58) : Color.rgb(238, 238, 238))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dpToPx(6), 0, 0);
        button.setLayoutParams(lp);
        return button;
    }

    private int dialogPanelBackgroundColor() {
        if (prefs != null) return prefs.getMainBgColor(this);
        return isDarkUi() ? Color.rgb(16, 16, 16) : Color.rgb(255, 255, 255);
    }

    private int dialogActionButtonBackgroundColor() {
        if (prefs != null) return prefs.getMainPanelColor(this);
        return isDarkUi() ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245);
    }

    int dialogRowBackgroundColor() {
        return dialogActionButtonBackgroundColor();
    }

    int dialogTextColor() {
        if (prefs != null) return prefs.getMainTextColor(this);
        return isDarkUi() ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36);
    }

    int dialogSubTextColor() {
        if (prefs != null) return prefs.getMainSubTextColor(this);
        return isDarkUi() ? Color.rgb(176, 176, 176) : Color.rgb(95, 99, 104);
    }

    int dialogOutlineColor() {
        if (prefs != null) return prefs.getMainOutlineColor(this);
        return isDarkUi() ? Color.rgb(70, 70, 70) : Color.rgb(218, 220, 224);
    }
    int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void exportBackupTo(Uri uri) {
        try {
            String json = bookmarkManager.exportAll();
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    ShortToast.show(this, getString(R.string.exported_successfully));
                }
            }
        } catch (Exception e) {
            ShortToast.show(this, getString(R.string.export_failed_prefix) + e.getMessage());
        }
    }

    private void importBackupFrom(Uri uri) {
        try {
            String json = FileUtils.readTextFromUri(this, uri);
            showImportBackupConfirmDialog(json);
        } catch (Exception e) {
            ShortToast.show(this, getString(R.string.import_failed_prefix) + e.getMessage());
        }
    }

    private void showImportBackupConfirmDialog(@NonNull String json) {
        final android.app.Dialog dialog = createRoundedSettingsDialog();
        LinearLayout panel = createRoundedSettingsDialogPanel();

        int text = dialogTextColor();
        int sub = dialogSubTextColor();
        int outline = dialogOutlineColor();

        TextView title = makeSettingsDialogTitle(getString(R.string.import_backup), text);
        panel.addView(title);

        TextView message = makeSettingsDialogMessage(getString(R.string.merge_or_replace), sub);
        panel.addView(message);

        MaterialButton merge = makeSettingsDialogButtonNoShade(getString(R.string.merge), text, outline);
        merge.setOnClickListener(v -> {
            dialog.dismiss();
            bookmarkManager.importAll(json, true);
            finishImportBackup(getString(R.string.imported_merged));
        });
        panel.addView(merge);

        MaterialButton replace = makeSettingsDialogButtonNoShade(getString(R.string.replace), text, outline);
        replace.setOnClickListener(v -> {
            dialog.dismiss();
            bookmarkManager.importAll(json, false);
            finishImportBackup(getString(R.string.imported_replaced));
        });
        panel.addView(replace);

        MaterialButton cancel = makeSettingsDialogButtonNoShade(getString(R.string.cancel), text, outline);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        showRoundedSettingsDialog(dialog, panel, true);
    }


    private void finishImportBackup(String message) {
        if (prefs != null) {
            prefs.applyLanguage(prefs.getLanguageMode());
            prefs.applyDarkMode(prefs.getDarkMode());
        }
        if (themeManager != null) {
            themeManager.reloadFromStorage();
        }
        ShortToast.show(this, message);
        recreate();
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
