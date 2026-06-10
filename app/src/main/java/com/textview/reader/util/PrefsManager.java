package com.textview.reader.util;

import com.textview.reader.UiColorUtils;

import java.io.File;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Base64;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class PrefsManager {
    private static final int DEFAULT_PAGE_MARGIN_HORIZONTAL_DP = 24;
    private static final int DEFAULT_READER_TEXT_BOUNDARY_PX = 68;
    private static final String KEY_READER_TEXT_LEFT_OFFSET = "reader_text_left_inset_px";
    private static final String KEY_READER_TEXT_RIGHT_OFFSET = "reader_text_right_inset_px";
    private static final String PREFS_NAME = "textview_reader_prefs";
    public static final float DEFAULT_FONT_SIZE = 16f;
    public static final float DEFAULT_LINE_SPACING = 1.4f;
    public static final int DARK_MODE_FOLLOW_SYSTEM = 0;
    public static final int DARK_MODE_OFF = 1;
    public static final int DARK_MODE_ON = 2;
    public static final int DARK_MODE_DARK_NAVY = 3;
    public static final int DARK_MODE_CUSTOM = 4;
    public static final int SORT_RECENT_READ = -1;
    public static final int SORT_NAME_ASC = 0;
    public static final int SORT_NAME_DESC = 1;
    public static final int SORT_DATE_NEW = 2;
    public static final int SORT_DATE_OLD = 3;
    public static final int SORT_SIZE_LARGE = 4;
    public static final int SORT_SIZE_SMALL = 5;
    public static final int SORT_TYPE = 6;
    public static final int LANGUAGE_SYSTEM = -1;
    public static final int LANGUAGE_ENGLISH = 0;
    public static final int LANGUAGE_KOREAN = 1;
    public static final int LANGUAGE_JAPANESE = 2;
    public static final int LANGUAGE_CHINESE_SIMPLIFIED = 3;
    public static final int LANGUAGE_CHINESE_TRADITIONAL = 4;
    public static final int LANGUAGE_SPANISH = 5;
    public static final int LANGUAGE_FRENCH = 6;
    public static final int LANGUAGE_GERMAN = 7;
    public static final int LANGUAGE_ITALIAN = 8;
    public static final int LANGUAGE_PORTUGUESE = 9;
    public static final int LANGUAGE_RUSSIAN = 10;
    public static final int LANGUAGE_ARABIC = 11;
    public static final int LANGUAGE_HINDI = 12;
    public static final int LANGUAGE_INDONESIAN = 13;
    public static final int LANGUAGE_VIETNAMESE = 14;
    public static final int LANGUAGE_THAI = 15;
    public static final int LANGUAGE_DUTCH = 16;
    public static final int LANGUAGE_POLISH = 17;
    public static final int LANGUAGE_TURKISH = 18;
    public static final int LANGUAGE_UKRAINIAN = 19;
    public static final int LANGUAGE_GREEK = 20;
    public static final int LANGUAGE_SWEDISH = 21;
    public static final int TAP_ZONE_VERTICAL = 0;
    public static final int TAP_ZONE_HORIZONTAL = 1;
    public static final int PAGE_STATUS_ALIGN_LEFT = 0;
    public static final int PAGE_STATUS_ALIGN_CENTER = 1;
    public static final int PAGE_STATUS_ALIGN_RIGHT = 2;
    public static final int PAGE_STATUS_ALIGN_HIDDEN = 3;
    public static final int EPUB_PAGE_DIRECTION_LTR = 0;
    public static final int EPUB_PAGE_DIRECTION_RTL = 1;
    public static final int EPUB_PAGE_EFFECT_SLIDE = 0;
    public static final int EPUB_PAGE_EFFECT_NONE = 1;
    public static final int LARGE_TEXT_PARTITION_MODE_STANDARD = 0;
    public static final int LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER = 1;
    public static final int LARGE_TEXT_PARTITION_LINES_STANDARD = 4000;
    public static final int LARGE_TEXT_PARTITION_BUFFER_LINES_STANDARD = 400;
    public static final int LARGE_TEXT_PARTITION_LINES_HIGH_BUFFER = 12000;
    public static final int LARGE_TEXT_PARTITION_BUFFER_LINES_HIGH_BUFFER = 600;
    public static final int ARCHIVE_OPEN_MODE_NORMAL = 0;
    public static final int ARCHIVE_OPEN_MODE_COMIC = 1;

    private static final String KEY_LOCK_PIN = "lock_pin";
    private static final String KEY_LOCK_ENABLED = "lock_enabled";
    private static final String LOCK_PIN_SCHEME_SHA256 = "pbkdf2-sha256";
    private static final String LOCK_PIN_SCHEME_SHA1 = "pbkdf2-sha1";
    private static final int LOCK_PIN_ITERATIONS = 120_000;
    private static final int LOCK_PIN_SALT_BYTES = 16;
    private static final int LOCK_PIN_HASH_BITS = 256;

    private final SharedPreferences prefs;
    private final Context appContext;
    private static PrefsManager instance;

    private PrefsManager(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    public static synchronized PrefsManager getInstance(Context context) {
        if (instance == null) instance = new PrefsManager(context);
        return instance;
    }
    public SharedPreferences getPrefs() { return prefs; }

    // ========== Backup / restore settings ==========
    // Security PINs are intentionally not exported/imported. Restoring lock_enabled
    // without a matching PIN can lock the user into a broken state, and exporting the
    // PIN would place sensitive data in a plain JSON backup file.
    private boolean isBackupExcludedKey(String key) {
        return KEY_LOCK_PIN.equals(key)
                || KEY_LOCK_ENABLED.equals(key)
                || (key != null && key.startsWith("auto_text_encoding::"))
                || (key != null && key.startsWith("auto_text_encoding_label::"));
    }

    public JSONObject exportSettingsToJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);

        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null || isBackupExcludedKey(key)) continue;

            JSONObject item = new JSONObject();
            if (value instanceof Boolean) {
                item.put("type", "boolean");
                item.put("value", value);
            } else if (value instanceof Float) {
                item.put("type", "float");
                item.put("value", ((Float) value).doubleValue());
            } else if (value instanceof Integer) {
                item.put("type", "int");
                item.put("value", value);
            } else if (value instanceof Long) {
                item.put("type", "long");
                item.put("value", value);
            } else if (value instanceof String) {
                item.put("type", "string");
                item.put("value", value);
            } else if (value instanceof Set) {
                item.put("type", "stringSet");
                JSONArray arr = new JSONArray();
                for (Object setItem : (Set<?>) value) {
                    if (setItem != null) arr.put(String.valueOf(setItem));
                }
                item.put("value", arr);
            } else {
                item.put("type", "string");
                item.put("value", String.valueOf(value));
            }
            values.put(key, item);
        }

        root.put("values", values);
        return root;
    }

    public void importSettingsFromJson(JSONObject root, boolean merge) throws JSONException {
        if (root == null) return;
        JSONObject values = root.optJSONObject("values");
        if (values == null) return;

        String previousLastDirectory = prefs.getString("last_directory", null);
        String previousRecentFolders = prefs.getString("recent_folders", "");
        String previousFolderShortcuts = prefs.getString("folder_shortcuts", "");

        SharedPreferences.Editor editor = prefs.edit();
        if (!merge) {
            for (String key : prefs.getAll().keySet()) {
                if (!isBackupExcludedKey(key)) editor.remove(key);
            }
        }

        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null || isBackupExcludedKey(key)) continue;
            if (isDeviceLocalDirectoryPreferenceKey(key)) continue;

            JSONObject item = values.optJSONObject(key);
            if (item == null) continue;
            String type = item.optString("type", "string");

            if ("boolean".equals(type)) {
                editor.putBoolean(key, item.optBoolean("value", false));
            } else if ("float".equals(type)) {
                editor.putFloat(key, (float) item.optDouble("value", 0.0));
            } else if ("int".equals(type)) {
                editor.putInt(key, item.optInt("value", 0));
            } else if ("long".equals(type)) {
                editor.putLong(key, item.optLong("value", 0L));
            } else if ("stringSet".equals(type)) {
                JSONArray arr = item.optJSONArray("value");
                LinkedHashSet<String> set = new LinkedHashSet<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String setItem = arr.optString(i, null);
                        if (setItem != null) set.add(setItem);
                    }
                }
                editor.putStringSet(key, set);
            } else {
                editor.putString(key, item.optString("value", ""));
            }
        }

        editor.commit();
        importDeviceLocalDirectoryPreferences(values,
                previousLastDirectory,
                previousRecentFolders,
                previousFolderShortcuts);
    }

    /**
     * Directory UI preferences are useful in a backup, but only if the restored
     * paths are real directories on the current device.  Import accessible
     * backup paths; when a backup path does not exist here, do not import it.
     * Existing local drawer/recent directory settings are kept if the backup has
     * no accessible replacement.  Bookmarks and reading states are intentionally
     * handled elsewhere because they can rebind through portable file identity.
     */
    private void importDeviceLocalDirectoryPreferences(JSONObject values,
                                                       String previousLastDirectory,
                                                       String previousRecentFolders,
                                                       String previousFolderShortcuts) {
        SharedPreferences.Editor editor = prefs.edit();

        String importedLastDirectory = readBackupStringPreference(values, "last_directory");
        String validLastDirectory = isExistingDirectory(importedLastDirectory)
                ? importedLastDirectory.trim()
                : firstExistingDirectory(previousLastDirectory, 1);
        putOrRemoveString(editor, "last_directory", validLastDirectory);

        String importedRecentFolders = readBackupStringPreference(values, "recent_folders");
        String validRecentFolders = joinExistingDirectories(importedRecentFolders, 20);
        if (validRecentFolders == null || validRecentFolders.isEmpty()) {
            validRecentFolders = joinExistingDirectories(previousRecentFolders, 20);
        }
        putOrRemoveString(editor, "recent_folders", validRecentFolders);

        String importedFolderShortcuts = readBackupStringPreference(values, "folder_shortcuts");
        String validFolderShortcuts = joinExistingDirectories(importedFolderShortcuts, 30);
        if (validFolderShortcuts == null || validFolderShortcuts.isEmpty()) {
            validFolderShortcuts = joinExistingDirectories(previousFolderShortcuts, 30);
        }
        putOrRemoveString(editor, "folder_shortcuts", validFolderShortcuts);

        editor.apply();
    }

    private boolean isDeviceLocalDirectoryPreferenceKey(String key) {
        return "last_directory".equals(key)
                || "recent_folders".equals(key)
                || "folder_shortcuts".equals(key);
    }

    private String readBackupStringPreference(JSONObject values, String key) {
        if (values == null || key == null) return null;
        JSONObject item = values.optJSONObject(key);
        if (item == null) return null;
        return item.optString("value", null);
    }

    private void putOrRemoveString(SharedPreferences.Editor editor, String key, String value) {
        if (editor == null || key == null) return;
        if (value == null || value.trim().isEmpty()) editor.remove(key);
        else editor.putString(key, value.trim());
    }

    private String firstExistingDirectory(String raw, int limit) {
        String joined = joinExistingDirectories(raw, limit);
        if (joined == null || joined.isEmpty()) return null;
        int newline = joined.indexOf('\n');
        return newline >= 0 ? joined.substring(0, newline) : joined;
    }

    private String joinExistingDirectories(String raw, int limit) {
        if (raw == null || raw.trim().isEmpty()) return "";
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        String[] parts = raw.split("\n");
        for (String part : parts) {
            if (part == null) continue;
            String path = part.trim();
            if (path.isEmpty()) continue;
            if (!isExistingDirectory(path)) continue;
            kept.add(path);
            if (limit > 0 && kept.size() >= limit) break;
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String path : kept) {
            if (count++ > 0) sb.append('\n');
            sb.append(path);
        }
        return sb.toString();
    }

    private boolean isExistingDirectory(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        if (path.startsWith("content://")) return false;
        try {
            File file = new File(path.trim());
            return file.exists() && file.isDirectory();
        } catch (Exception ignored) {
            return false;
        }
    }


    public void resetReaderAndAppSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        String[] keys = new String[]{
                "font_size",
                "line_spacing",
                "font_family",
                "epub_left_padding_dp",
                "epub_right_padding_dp",
                "epub_side_padding_dp",
                "document_side_padding_dp",
                "epub_top_padding_dp",
                "epub_bottom_padding_dp",
                "epub_page_direction",
                "epub_page_effect",
                "dark_mode",
                "language_mode",
                "keep_screen_on",
                "show_status_bar",
                "page_status_alignment",
                "auto_save_position",
                "auto_page_turn_interval_seconds",
                "last_reader_search_query",
                "page_margin_h",
                "page_margin_v",
                "reader_text_top_offset_px",
                "reader_text_bottom_offset_px",
                KEY_READER_TEXT_LEFT_OFFSET,
                KEY_READER_TEXT_RIGHT_OFFSET,
                "sort_mode",
                "recent_sort_mode",
                "file_search_all_folders",
                "show_hidden",
                "brightness_override",
                "brightness_value",
                "volume_key_scroll",
                "tap_paging_enabled",
                "tap_zone_mode",
                "tap_leading_zone_percent",
                "tap_trailing_zone_percent",
                "paging_overlap_lines",
                "active_theme_id",
                "large_text_partition_mode",
                "archive_open_mode",
                "main_custom_bg",
                "main_custom_panel",
                "main_custom_bar",
                "main_custom_text",
                "main_custom_sub_text",
                "main_custom_outline",
                "main_custom_selected",
                "main_custom_file_type_chip",
                "main_custom_file_type_chip_selected",
                "main_custom_reading_card",
                "main_custom_shortcut_box",
                "main_custom_drawer_action_icon",
                "button_order_main_filters",
                "button_order_txt_reader",
                "button_order_document_viewer",
                "button_order_pdf_viewer"
        };
        for (String key : keys) editor.remove(key);
        editor.commit();
        applyDarkMode(getDarkMode());
        applyLanguage(getLanguageMode());
    }

    public float getFontSize() { return prefs.getFloat("font_size", DEFAULT_FONT_SIZE); }
    public void setFontSize(float s) { prefs.edit().putFloat("font_size", Math.max(8f, Math.min(48f, s))).apply(); }
    public float getLineSpacing() { return prefs.getFloat("line_spacing", DEFAULT_LINE_SPACING); }
    public void setLineSpacing(float s) { prefs.edit().putFloat("line_spacing", s).apply(); }
    public String getFontFamily() { return prefs.getString("font_family", "default"); }
    public void setFontFamily(String f) { prefs.edit().putString("font_family", f).apply(); }

    // EPUB WebView reader boundary. Stored in raw px units.
    private int clampEpubPaddingDp(int px) {
        int clamped = Math.max(0, Math.min(240, px));
        return Math.round(clamped / 5f) * 5;
    }

    public int getEpubLeftPaddingDp() {
        return clampEpubPaddingDp(prefs.getInt("epub_left_padding_dp",
                prefs.getInt("epub_side_padding_dp",
                        prefs.getInt("document_side_padding_dp", 30))));
    }
    public void setEpubLeftPaddingDp(int dp) {
        prefs.edit().putInt("epub_left_padding_dp", clampEpubPaddingDp(dp)).apply();
    }
    public int getEpubRightPaddingDp() {
        return clampEpubPaddingDp(prefs.getInt("epub_right_padding_dp",
                prefs.getInt("epub_side_padding_dp",
                        prefs.getInt("document_side_padding_dp", 30))));
    }
    public void setEpubRightPaddingDp(int dp) {
        prefs.edit().putInt("epub_right_padding_dp", clampEpubPaddingDp(dp)).apply();
    }

    // Kept for migration/compatibility with older 2.0.7 builds that stored one side value.
    public int getEpubSidePaddingDp() {
        return Math.round((getEpubLeftPaddingDp() + getEpubRightPaddingDp()) / 2f);
    }
    public void setEpubSidePaddingDp(int dp) {
        int value = clampEpubPaddingDp(dp);
        prefs.edit()
                .putInt("epub_left_padding_dp", value)
                .putInt("epub_right_padding_dp", value)
                .putInt("epub_side_padding_dp", value)
                .apply();
    }
    public int getEpubTopPaddingDp() {
        return clampEpubPaddingDp(prefs.getInt("epub_top_padding_dp", 0));
    }
    public void setEpubTopPaddingDp(int dp) {
        prefs.edit().putInt("epub_top_padding_dp", clampEpubPaddingDp(dp)).apply();
    }
    public int getEpubBottomPaddingDp() {
        return clampEpubPaddingDp(prefs.getInt("epub_bottom_padding_dp", 0));
    }
    public void setEpubBottomPaddingDp(int dp) {
        prefs.edit().putInt("epub_bottom_padding_dp", clampEpubPaddingDp(dp)).apply();
    }

    public int getEpubPageDirection() {
        int value = prefs.getInt("epub_page_direction", EPUB_PAGE_DIRECTION_LTR);
        return value == EPUB_PAGE_DIRECTION_RTL ? EPUB_PAGE_DIRECTION_RTL : EPUB_PAGE_DIRECTION_LTR;
    }
    public void setEpubPageDirection(int direction) {
        prefs.edit().putInt("epub_page_direction",
                direction == EPUB_PAGE_DIRECTION_RTL ? EPUB_PAGE_DIRECTION_RTL : EPUB_PAGE_DIRECTION_LTR).apply();
    }
    public int getEpubPageEffect() {
        int value = prefs.getInt("epub_page_effect", EPUB_PAGE_EFFECT_SLIDE);
        return value == EPUB_PAGE_EFFECT_NONE ? EPUB_PAGE_EFFECT_NONE : EPUB_PAGE_EFFECT_SLIDE;
    }
    public void setEpubPageEffect(int effect) {
        prefs.edit().putInt("epub_page_effect",
                effect == EPUB_PAGE_EFFECT_NONE ? EPUB_PAGE_EFFECT_NONE : EPUB_PAGE_EFFECT_SLIDE).apply();
    }

    public int getDarkMode() { return prefs.getInt("dark_mode", DARK_MODE_FOLLOW_SYSTEM); }
    public void setDarkMode(int m) { prefs.edit().putInt("dark_mode", m).apply(); applyDarkMode(m); }
    public void applyDarkMode(int mode) {
        switch (mode) {
            case DARK_MODE_OFF:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case DARK_MODE_ON:
            case DARK_MODE_DARK_NAVY:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case DARK_MODE_CUSTOM:
                AppCompatDelegate.setDefaultNightMode(isCustomMainDark()
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public boolean isDarkNavyMode() {
        return getDarkMode() == DARK_MODE_DARK_NAVY;
    }

    public boolean isMainCustomMode() {
        return getDarkMode() == DARK_MODE_CUSTOM;
    }

    public boolean shouldUseDarkColors(Context context) {
        int mode = getDarkMode();
        if (mode == DARK_MODE_ON || mode == DARK_MODE_DARK_NAVY) return true;
        if (mode == DARK_MODE_CUSTOM) return isCustomMainDark();
        if (mode == DARK_MODE_OFF) return false;
        int mask = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static final int DEFAULT_MAIN_CUSTOM_BG = Color.rgb(5, 13, 26);
    private static final int DEFAULT_MAIN_CUSTOM_PANEL = Color.rgb(9, 18, 42);
    private static final int DEFAULT_MAIN_CUSTOM_BAR = Color.rgb(3, 10, 22);
    private static final int DEFAULT_MAIN_CUSTOM_TEXT = Color.rgb(234, 242, 255);
    private static final int DEFAULT_MAIN_CUSTOM_SUB = Color.rgb(180, 200, 226);
    private static final int DEFAULT_MAIN_CUSTOM_OUTLINE = Color.rgb(4, 32, 69);
    private static final int DEFAULT_MAIN_CUSTOM_SELECTED = Color.rgb(10, 29, 66);
    private static final int DEFAULT_MAIN_CUSTOM_FILE_TYPE_CHIP = Color.rgb(6, 22, 58);
    private static final int DEFAULT_MAIN_CUSTOM_FILE_TYPE_CHIP_SELECTED = Color.rgb(10, 36, 85);
    private static final int DEFAULT_MAIN_CUSTOM_READING_CARD = Color.rgb(0, 9, 29);
    private static final int DEFAULT_MAIN_CUSTOM_SHORTCUT_BOX = Color.rgb(0, 21, 48);
    private static final int DEFAULT_MAIN_CUSTOM_DRAWER_ACTION_ICON = DEFAULT_MAIN_CUSTOM_TEXT;

    public int getMainCustomBgColor() { return prefs.getInt("main_custom_bg", DEFAULT_MAIN_CUSTOM_BG); }
    public int getMainCustomPanelColor() { return prefs.getInt("main_custom_panel", DEFAULT_MAIN_CUSTOM_PANEL); }
    public int getMainCustomBarColor() { return prefs.getInt("main_custom_bar", DEFAULT_MAIN_CUSTOM_BAR); }
    public int getMainCustomTextColor() { return prefs.getInt("main_custom_text", DEFAULT_MAIN_CUSTOM_TEXT); }
    public int getMainCustomSubTextColor() { return prefs.getInt("main_custom_sub_text", DEFAULT_MAIN_CUSTOM_SUB); }
    public int getMainCustomOutlineColor() { return prefs.getInt("main_custom_outline", DEFAULT_MAIN_CUSTOM_OUTLINE); }
    public int getMainCustomSelectedColor() {
        if (prefs.contains("main_custom_selected")) {
            return prefs.getInt("main_custom_selected", DEFAULT_MAIN_CUSTOM_SELECTED);
        }
        return deriveMainCustomSelectedColor();
    }
    public int getMainCustomFileTypeChipColor() { return prefs.getInt("main_custom_file_type_chip", DEFAULT_MAIN_CUSTOM_FILE_TYPE_CHIP); }
    public int getMainCustomFileTypeChipSelectedColor() { return prefs.getInt("main_custom_file_type_chip_selected", DEFAULT_MAIN_CUSTOM_FILE_TYPE_CHIP_SELECTED); }
    public int getMainCustomReadingThemeCardColor() { return prefs.getInt("main_custom_reading_card", DEFAULT_MAIN_CUSTOM_READING_CARD); }
    public int getMainCustomShortcutBoxColor() {
        if (prefs.contains("main_custom_shortcut_box")) {
            return prefs.getInt("main_custom_shortcut_box", DEFAULT_MAIN_CUSTOM_SHORTCUT_BOX);
        }
        return getMainCustomReadingThemeCardColor();
    }

    public int getMainCustomDrawerActionIconColor() {
        return prefs.getInt("main_custom_drawer_action_icon", DEFAULT_MAIN_CUSTOM_DRAWER_ACTION_ICON);
    }

    public void setMainCustomColors(int bg, int panel, int bar, int text, int subText, int outline, int selected, int readingThemeCard, int shortcutBox, int drawerActionIcon) {
        prefs.edit()
                .putInt("main_custom_bg", forceOpaque(bg))
                .putInt("main_custom_panel", forceOpaque(panel))
                .putInt("main_custom_bar", forceOpaque(bar))
                .putInt("main_custom_text", forceOpaque(text))
                .putInt("main_custom_sub_text", forceOpaque(subText))
                .putInt("main_custom_outline", forceOpaque(outline))
                .putInt("main_custom_selected", forceOpaque(selected))
                .putInt("main_custom_reading_card", forceOpaque(readingThemeCard))
                .putInt("main_custom_shortcut_box", forceOpaque(shortcutBox))
                .putInt("main_custom_drawer_action_icon", forceOpaque(drawerActionIcon))
                .apply();
    }

    public void setMainCustomFileTypeChipColors(int fileTypeChip, int selectedFileTypeChip) {
        prefs.edit()
                .putInt("main_custom_file_type_chip", forceOpaque(fileTypeChip))
                .putInt("main_custom_file_type_chip_selected", forceOpaque(selectedFileTypeChip))
                .apply();
    }

    private int forceOpaque(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private int deriveMainCustomSelectedColor() {
        return blendColors(getMainCustomPanelColor(), getMainCustomTextColor(), isCustomMainDark() ? 0.22f : 0.16f);
    }

    private boolean isCustomMainDark() {
        return !isLightColor(getMainCustomBgColor());
    }

    private boolean isLightColor(int color) {
        return UiColorUtils.isLightColor(color);
    }

    private int blendColors(int bottomColor, int topColor, float topAlpha) {
        return UiColorUtils.blendColors(bottomColor, topColor, topAlpha);
    }

    public int getMainBgColor(Context context) {
        if (isMainCustomMode()) return getMainCustomBgColor();
        if (isDarkNavyMode()) return Color.rgb(5, 13, 26);
        return shouldUseDarkColors(context) ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255);
    }

    public int getMainPanelColor(Context context) {
        if (isMainCustomMode()) return getMainCustomPanelColor();
        if (isDarkNavyMode()) return Color.rgb(9, 18, 42);
        return shouldUseDarkColors(context) ? Color.rgb(17, 17, 17) : Color.rgb(240, 241, 244);
    }

    public int getMainElevatedPanelColor(Context context) {
        if (isMainCustomMode()) return blendColors(getMainCustomPanelColor(), getMainCustomTextColor(), isCustomMainDark() ? 0.10f : 0.055f);
        if (isDarkNavyMode()) return Color.rgb(16, 35, 58);
        return shouldUseDarkColors(context) ? Color.rgb(30, 30, 30) : Color.rgb(232, 234, 237);
    }

    public int getMainReadingThemeCardColor(Context context) {
        if (isMainCustomMode()) return getMainCustomReadingThemeCardColor();
        if (isDarkNavyMode()) return Color.rgb(0, 9, 29);
        return shouldUseDarkColors(context) ? Color.rgb(3, 3, 3) : Color.rgb(254, 254, 254);
    }

    public int getMainShortcutBoxColor(Context context) {
        if (isMainCustomMode()) return getMainCustomShortcutBoxColor();
        if (isDarkNavyMode()) return Color.rgb(0, 21, 48);
        return shouldUseDarkColors(context) ? Color.rgb(18, 18, 18) : Color.rgb(232, 234, 237);
    }

    public int getMainTextColor(Context context) {
        if (isMainCustomMode()) return getMainCustomTextColor();
        if (isDarkNavyMode()) return Color.rgb(234, 242, 255);
        return shouldUseDarkColors(context) ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36);
    }

    public int getMainSubTextColor(Context context) {
        if (isMainCustomMode()) return getMainCustomSubTextColor();
        if (isDarkNavyMode()) return Color.rgb(180, 200, 226);
        return shouldUseDarkColors(context) ? Color.rgb(176, 176, 176) : Color.rgb(95, 99, 104);
    }

    public int getMainMutedTextColor(Context context) {
        if (isMainCustomMode()) return blendColors(getMainCustomBgColor(), getMainCustomSubTextColor(), 0.82f);
        if (isDarkNavyMode()) return Color.rgb(142, 165, 196);
        return shouldUseDarkColors(context) ? Color.rgb(154, 160, 166) : Color.rgb(95, 99, 104);
    }

    public int getMainBarColor(Context context) {
        if (isMainCustomMode()) return getMainCustomBarColor();
        if (isDarkNavyMode()) return Color.rgb(3, 10, 22);
        return shouldUseDarkColors(context) ? Color.rgb(0, 0, 0) : Color.rgb(32, 33, 36);
    }

    public int getMainOutlineColor(Context context) {
        if (isMainCustomMode()) return getMainCustomOutlineColor();
        if (isDarkNavyMode()) return Color.rgb(4, 32, 69);
        return shouldUseDarkColors(context) ? Color.rgb(70, 70, 70) : Color.rgb(210, 210, 210);
    }

    public int getMainSelectedColor(Context context) {
        if (isMainCustomMode()) return getMainCustomSelectedColor();
        if (isDarkNavyMode()) return Color.rgb(10, 29, 66);
        return shouldUseDarkColors(context) ? Color.rgb(72, 72, 72) : Color.rgb(226, 228, 232);
    }

    public int getMainFileTypeChipColor(Context context) {
        if (isMainCustomMode()) return getMainCustomFileTypeChipColor();
        if (isDarkNavyMode()) return Color.rgb(6, 22, 58);
        return getMainElevatedPanelColor(context);
    }

    public int getMainFileTypeChipSelectedColor(Context context) {
        if (isMainCustomMode()) return getMainCustomFileTypeChipSelectedColor();
        if (isDarkNavyMode()) return Color.rgb(10, 36, 85);
        return getMainSelectedColor(context);
    }

    public int getMainFileLongHoldColor(Context context) {
        if (isMainCustomMode()) {
            return blendColors(getMainCustomBgColor(), getMainCustomSelectedColor(), isCustomMainDark() ? 0.58f : 0.36f);
        }
        if (isDarkNavyMode()) {
            return blendColors(getMainBgColor(context), getMainSelectedColor(context), 0.58f);
        }
        return getMainSelectedColor(context);
    }

    public int getMainControlColor(Context context) {
        if (isMainCustomMode()) return getMainCustomTextColor();
        if (isDarkNavyMode()) return Color.rgb(206, 222, 246);
        return shouldUseDarkColors(context) ? Color.rgb(210, 210, 210) : Color.rgb(80, 80, 80);
    }

    public int getMainDrawerActionIconColor(Context context) {
        if (isMainCustomMode()) return getMainCustomDrawerActionIconColor();
        if (isDarkNavyMode()) return getMainTextColor(context);
        return getMainControlColor(context);
    }


    // Language
    public int getLanguageMode() {
        if (!prefs.contains("language_mode")) {
            return LANGUAGE_SYSTEM;
        }
        return normalizeLanguageMode(prefs.getInt("language_mode", LANGUAGE_SYSTEM));
    }

    public void setLanguageMode(int mode) {
        int normalized = normalizeLanguageMode(mode);

        // Use commit(), not apply(), because changing AppCompat locale can recreate the
        // activity immediately. With apply(), a recreated SettingsActivity can sometimes
        // read the old language value and appear to flip to the opposite selection.
        if (normalized == LANGUAGE_SYSTEM) {
            prefs.edit().remove("language_mode").commit();
        } else {
            prefs.edit().putInt("language_mode", normalized).commit();
        }
        applyLanguage(normalized);
    }

    public void applyLanguage(int mode) {
        LocaleListCompat target;
        if (mode == LANGUAGE_SYSTEM || !prefs.contains("language_mode")) {
            // System default: let Android/AppCompat follow the device locale list.
            target = LocaleListCompat.getEmptyLocaleList();
        } else {
            String tag = languageTagForMode(normalizeLanguageMode(mode));
            target = LocaleListCompat.forLanguageTags(tag);
        }

        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();

        // Avoid redundant locale sets. Re-setting the same locale can cause unnecessary
        // activity recreation and intermittent stale UI state around the language radio group.
        if (!target.toLanguageTags().equals(current.toLanguageTags())) {
            AppCompatDelegate.setApplicationLocales(target);
        }
    }

    private int normalizeLanguageMode(int mode) {
        if (mode == LANGUAGE_SYSTEM) return LANGUAGE_SYSTEM;
        return mode >= LANGUAGE_ENGLISH && mode <= LANGUAGE_SWEDISH ? mode : LANGUAGE_ENGLISH;
    }

    private int detectSystemLanguageMode() {
        Locale locale = Locale.getDefault();
        String language = locale != null ? locale.getLanguage() : "";
        if ("ko".equalsIgnoreCase(language)) return LANGUAGE_KOREAN;
        if ("ja".equalsIgnoreCase(language)) return LANGUAGE_JAPANESE;
        if ("zh".equalsIgnoreCase(language)) {
            String country = locale != null ? locale.getCountry() : "";
            return "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)
                    ? LANGUAGE_CHINESE_TRADITIONAL
                    : LANGUAGE_CHINESE_SIMPLIFIED;
        }
        if ("es".equalsIgnoreCase(language)) return LANGUAGE_SPANISH;
        if ("fr".equalsIgnoreCase(language)) return LANGUAGE_FRENCH;
        if ("de".equalsIgnoreCase(language)) return LANGUAGE_GERMAN;
        if ("it".equalsIgnoreCase(language)) return LANGUAGE_ITALIAN;
        if ("pt".equalsIgnoreCase(language)) return LANGUAGE_PORTUGUESE;
        if ("ru".equalsIgnoreCase(language)) return LANGUAGE_RUSSIAN;
        if ("ar".equalsIgnoreCase(language)) return LANGUAGE_ARABIC;
        if ("hi".equalsIgnoreCase(language)) return LANGUAGE_HINDI;
        if ("id".equalsIgnoreCase(language) || "in".equalsIgnoreCase(language)) return LANGUAGE_INDONESIAN;
        if ("vi".equalsIgnoreCase(language)) return LANGUAGE_VIETNAMESE;
        if ("th".equalsIgnoreCase(language)) return LANGUAGE_THAI;
        if ("nl".equalsIgnoreCase(language)) return LANGUAGE_DUTCH;
        if ("pl".equalsIgnoreCase(language)) return LANGUAGE_POLISH;
        if ("tr".equalsIgnoreCase(language)) return LANGUAGE_TURKISH;
        if ("uk".equalsIgnoreCase(language)) return LANGUAGE_UKRAINIAN;
        if ("el".equalsIgnoreCase(language)) return LANGUAGE_GREEK;
        if ("sv".equalsIgnoreCase(language)) return LANGUAGE_SWEDISH;
        return LANGUAGE_ENGLISH;
    }

    private String languageTagForMode(int mode) {
        switch (normalizeLanguageMode(mode)) {
            case LANGUAGE_KOREAN:
                return "ko";
            case LANGUAGE_JAPANESE:
                return "ja";
            case LANGUAGE_CHINESE_SIMPLIFIED:
                return "zh-CN";
            case LANGUAGE_CHINESE_TRADITIONAL:
                return "zh-TW";
            case LANGUAGE_SPANISH:
                return "es";
            case LANGUAGE_FRENCH:
                return "fr";
            case LANGUAGE_GERMAN:
                return "de";
            case LANGUAGE_ITALIAN:
                return "it";
            case LANGUAGE_PORTUGUESE:
                return "pt";
            case LANGUAGE_RUSSIAN:
                return "ru";
            case LANGUAGE_ARABIC:
                return "ar";
            case LANGUAGE_HINDI:
                return "hi";
            case LANGUAGE_INDONESIAN:
                return "id";
            case LANGUAGE_VIETNAMESE:
                return "vi";
            case LANGUAGE_THAI:
                return "th";
            case LANGUAGE_DUTCH:
                return "nl";
            case LANGUAGE_POLISH:
                return "pl";
            case LANGUAGE_TURKISH:
                return "tr";
            case LANGUAGE_UKRAINIAN:
                return "uk";
            case LANGUAGE_GREEK:
                return "el";
            case LANGUAGE_SWEDISH:
                return "sv";
            case LANGUAGE_ENGLISH:
            default:
                return "en";
        }
    }

    public boolean getKeepScreenOn() { return prefs.getBoolean("keep_screen_on", true); }
    public void setKeepScreenOn(boolean v) { prefs.edit().putBoolean("keep_screen_on", v).apply(); }
    public boolean getShowStatusBar() { return prefs.getBoolean("show_status_bar", false); }
    public void setShowStatusBar(boolean v) { prefs.edit().putBoolean("show_status_bar", v).apply(); }
    public int getPageStatusAlignment() {
        return normalizePageStatusAlignment(prefs.getInt("page_status_alignment", PAGE_STATUS_ALIGN_CENTER));
    }
    public void setPageStatusAlignment(int alignment) {
        prefs.edit().putInt("page_status_alignment", normalizePageStatusAlignment(alignment)).apply();
    }
    private int normalizePageStatusAlignment(int alignment) {
        if (alignment == PAGE_STATUS_ALIGN_LEFT
                || alignment == PAGE_STATUS_ALIGN_RIGHT
                || alignment == PAGE_STATUS_ALIGN_HIDDEN) return alignment;
        return PAGE_STATUS_ALIGN_CENTER;
    }
    public boolean getAutoSavePosition() { return prefs.getBoolean("auto_save_position", true); }
    public void setAutoSavePosition(boolean v) { prefs.edit().putBoolean("auto_save_position", v).apply(); }
    public int getAutoPageTurnIntervalSeconds() {
        return Math.max(2, Math.min(120, prefs.getInt("auto_page_turn_interval_seconds", 8)));
    }
    public void setAutoPageTurnIntervalSeconds(int seconds) {
        prefs.edit().putInt("auto_page_turn_interval_seconds", Math.max(2, Math.min(120, seconds))).apply();
    }

    public String getTtsLanguageTag() {
        return normalizeTtsLanguageTag(prefs.getString("tts_language_tag", "system"));
    }

    public void setTtsLanguageTag(String tag) {
        prefs.edit().putString("tts_language_tag", normalizeTtsLanguageTag(tag)).apply();
    }

    public String getTtsVoiceName() {
        String value = prefs.getString("tts_voice_name", "");
        return value != null ? value : "";
    }

    public void setTtsVoiceName(String voiceName) {
        prefs.edit().putString("tts_voice_name", voiceName == null ? "" : voiceName).apply();
    }

    public int getTtsSpeechRatePercent() {
        return Math.max(50, Math.min(200, prefs.getInt("tts_speech_rate_percent", 100)));
    }

    public void setTtsSpeechRatePercent(int percent) {
        prefs.edit().putInt("tts_speech_rate_percent", Math.max(50, Math.min(200, percent))).apply();
    }

    public int getTtsPitchPercent() {
        return Math.max(50, Math.min(200, prefs.getInt("tts_pitch_percent", 100)));
    }

    public void setTtsPitchPercent(int percent) {
        prefs.edit().putInt("tts_pitch_percent", Math.max(50, Math.min(200, percent))).apply();
    }

    public void setTtsLastPlaybackState(String filePath, int charPosition, int pageNumber, boolean continuous) {
        prefs.edit()
                .putString("tts_last_file_path", filePath == null ? "" : filePath)
                .putInt("tts_last_char_position", Math.max(0, charPosition))
                .putInt("tts_last_page_number", Math.max(1, pageNumber))
                .putBoolean("tts_last_continuous", continuous)
                .putLong("tts_last_timestamp", System.currentTimeMillis())
                .apply();
    }

    public String getTtsLastFilePath() {
        String value = prefs.getString("tts_last_file_path", "");
        return value != null ? value : "";
    }

    public int getTtsLastCharPosition() {
        return Math.max(0, prefs.getInt("tts_last_char_position", 0));
    }

    public int getTtsLastPageNumber() {
        return Math.max(1, prefs.getInt("tts_last_page_number", 1));
    }

    public boolean getTtsLastContinuous() {
        return prefs.getBoolean("tts_last_continuous", false);
    }

    public long getTtsLastTimestamp() {
        return Math.max(0L, prefs.getLong("tts_last_timestamp", 0L));
    }

    private String normalizeTtsLanguageTag(String tag) {
        if ("ko".equals(tag) || "en".equals(tag) || "ja".equals(tag)
                || "zh-CN".equals(tag) || "zh-TW".equals(tag)
                || "es".equals(tag) || "fr".equals(tag) || "de".equals(tag)
                || "it".equals(tag) || "pt".equals(tag) || "ru".equals(tag)
                || "ar".equals(tag) || "hi".equals(tag) || "id".equals(tag)
                || "vi".equals(tag) || "th".equals(tag)) {
            return tag;
        }
        return "system";
    }

    public int getLargeTextPartitionMode() {
        return normalizeLargeTextPartitionMode(
                prefs.getInt("large_text_partition_mode", LARGE_TEXT_PARTITION_MODE_STANDARD));
    }

    public void setLargeTextPartitionMode(int mode) {
        prefs.edit().putInt("large_text_partition_mode", normalizeLargeTextPartitionMode(mode)).apply();
    }

    public int getLargeTextPartitionLines() {
        return getLargeTextPartitionMode() == LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                ? LARGE_TEXT_PARTITION_LINES_HIGH_BUFFER
                : LARGE_TEXT_PARTITION_LINES_STANDARD;
    }

    public int getLargeTextPartitionBufferLines() {
        return getLargeTextPartitionMode() == LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                ? LARGE_TEXT_PARTITION_BUFFER_LINES_HIGH_BUFFER
                : LARGE_TEXT_PARTITION_BUFFER_LINES_STANDARD;
    }

    private int normalizeLargeTextPartitionMode(int mode) {
        return mode == LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                ? LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                : LARGE_TEXT_PARTITION_MODE_STANDARD;
    }

    public int getArchiveOpenMode() {
        int mode = prefs.getInt("archive_open_mode", ARCHIVE_OPEN_MODE_NORMAL);
        return mode == ARCHIVE_OPEN_MODE_COMIC ? ARCHIVE_OPEN_MODE_COMIC : ARCHIVE_OPEN_MODE_NORMAL;
    }

    public void setArchiveOpenMode(int mode) {
        prefs.edit().putInt("archive_open_mode",
                mode == ARCHIVE_OPEN_MODE_COMIC ? ARCHIVE_OPEN_MODE_COMIC : ARCHIVE_OPEN_MODE_NORMAL).apply();
    }

    public boolean shouldOpenGenericArchivesAsComics() {
        return getArchiveOpenMode() == ARCHIVE_OPEN_MODE_COMIC;
    }

    public String getLastDirectory() { return prefs.getString("last_directory", null); }

    public String getLastReaderSearchQuery() { return prefs.getString("last_reader_search_query", ""); }
    public void setLastReaderSearchQuery(String query) {
        prefs.edit().putString("last_reader_search_query", query == null ? "" : query.trim()).apply();
    }

    public boolean getFileSearchAllFolders() {
        return prefs.getBoolean("file_search_all_folders", false);
    }

    public void setFileSearchAllFolders(boolean enabled) {
        prefs.edit().putBoolean("file_search_all_folders", enabled).apply();
    }
    public void setLastDirectory(String p) { prefs.edit().putString("last_directory", p).apply(); }

    public List<String> getRecentFolders(int limit) {
        String raw = prefs.getString("recent_folders", "");
        ArrayList<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        String[] parts = raw.split("\n");
        for (String part : parts) {
            if (part == null) continue;
            String path = part.trim();
            if (path.isEmpty()) continue;
            result.add(path);
            if (limit > 0 && result.size() >= limit) break;
        }
        return result;
    }

    public void addRecentFolder(String path) {
        if (path == null) return;
        String clean = path.trim();
        if (clean.isEmpty()) return;

        // If the user cleared this folder from the recent-folder list before,
        // opening it again should make it eligible to appear again.
        removeHiddenRecentFolder(clean);

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(clean);
        for (String old : getRecentFolders(32)) {
            if (old != null && !old.trim().isEmpty()) ordered.add(old.trim());
            if (ordered.size() >= 20) break;
        }

        saveRecentFolders(ordered, 20);
    }

    public void removeRecentFolder(String path) {
        if (path == null) return;
        String clean = path.trim();
        if (clean.isEmpty()) return;

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String old : getRecentFolders(64)) {
            if (old == null) continue;
            String item = old.trim();
            if (item.isEmpty() || item.equals(clean)) continue;
            ordered.add(item);
        }

        SharedPreferences.Editor editor = prefs.edit();
        putJoinedPaths(editor, "recent_folders", ordered, 20);
        String last = getLastDirectory();
        if (last != null && last.trim().equals(clean)) editor.remove("last_directory");
        editor.apply();
        hideRecentFolder(clean);
    }

    public void clearRecentFolders(Collection<String> pathsToHide) {
        LinkedHashSet<String> hidden = getHiddenRecentFolderSet();
        if (pathsToHide != null) {
            for (String path : pathsToHide) {
                if (path == null) continue;
                String clean = path.trim();
                if (!clean.isEmpty()) hidden.add(clean);
            }
        }

        SharedPreferences.Editor editor = prefs.edit()
                .remove("recent_folders")
                .remove("last_directory");
        putJoinedPaths(editor, "hidden_recent_folders", hidden, 256);
        editor.apply();
    }

    public boolean isRecentFolderHidden(String path) {
        if (path == null) return false;
        String clean = path.trim();
        if (clean.isEmpty()) return false;
        return getHiddenRecentFolderSet().contains(clean);
    }

    private void hideRecentFolder(String path) {
        if (path == null) return;
        String clean = path.trim();
        if (clean.isEmpty()) return;
        LinkedHashSet<String> hidden = getHiddenRecentFolderSet();
        hidden.add(clean);
        SharedPreferences.Editor editor = prefs.edit();
        putJoinedPaths(editor, "hidden_recent_folders", hidden, 256);
        editor.apply();
    }

    private void removeHiddenRecentFolder(String path) {
        if (path == null) return;
        String clean = path.trim();
        if (clean.isEmpty()) return;
        LinkedHashSet<String> hidden = getHiddenRecentFolderSet();
        if (!hidden.remove(clean)) return;
        SharedPreferences.Editor editor = prefs.edit();
        putJoinedPaths(editor, "hidden_recent_folders", hidden, 256);
        editor.apply();
    }

    private LinkedHashSet<String> getHiddenRecentFolderSet() {
        return readPathSet("hidden_recent_folders", 256);
    }

    private void saveRecentFolders(LinkedHashSet<String> ordered, int limit) {
        SharedPreferences.Editor editor = prefs.edit();
        putJoinedPaths(editor, "recent_folders", ordered, limit);
        editor.apply();
    }

    private LinkedHashSet<String> readPathSet(String key, int limit) {
        String raw = prefs.getString(key, "");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return result;
        String[] parts = raw.split("\n");
        for (String part : parts) {
            if (part == null) continue;
            String path = part.trim();
            if (path.isEmpty()) continue;
            result.add(path);
            if (limit > 0 && result.size() >= limit) break;
        }
        return result;
    }

    private void putJoinedPaths(SharedPreferences.Editor editor, String key, Collection<String> paths, int limit) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (paths != null) {
            for (String item : paths) {
                if (item == null || item.trim().isEmpty()) continue;
                if (count++ > 0) sb.append('\n');
                sb.append(item.trim());
                if (limit > 0 && count >= limit) break;
            }
        }
        if (sb.length() == 0) editor.remove(key);
        else editor.putString(key, sb.toString());
    }
    public List<String> getFolderShortcuts(int limit) {
        String raw = prefs.getString("folder_shortcuts", "");
        ArrayList<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        String[] parts = raw.split("\n");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null) continue;
            String path = part.trim();
            if (path.isEmpty() || !seen.add(path)) continue;
            result.add(path);
            if (limit > 0 && result.size() >= limit) break;
        }
        return result;
    }

    public boolean isFolderShortcut(String path) {
        if (path == null) return false;
        String clean = path.trim();
        if (clean.isEmpty()) return false;
        for (String shortcut : getFolderShortcuts(0)) {
            if (clean.equals(shortcut)) return true;
        }
        return false;
    }

    public void addFolderShortcut(String path) {
        if (path == null) return;
        String clean = path.trim();
        if (clean.isEmpty()) return;

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(clean);
        for (String old : getFolderShortcuts(64)) {
            if (old != null && !old.trim().isEmpty()) ordered.add(old.trim());
            if (ordered.size() >= 30) break;
        }
        saveFolderShortcuts(ordered, 30);
    }

    public void removeFolderShortcut(String path) {
        if (path == null) return;
        String clean = path.trim();
        if (clean.isEmpty()) return;

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String old : getFolderShortcuts(64)) {
            if (old == null) continue;
            String item = old.trim();
            if (item.isEmpty() || item.equals(clean)) continue;
            ordered.add(item);
        }
        saveFolderShortcuts(ordered, 30);
    }

    private void saveFolderShortcuts(LinkedHashSet<String> ordered, int limit) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String item : ordered) {
            if (item == null || item.trim().isEmpty()) continue;
            if (count++ > 0) sb.append('\n');
            sb.append(item.trim());
            if (limit > 0 && count >= limit) break;
        }
        prefs.edit().putString("folder_shortcuts", sb.toString()).apply();
    }

    public int getMarginHorizontal() { return prefs.getInt("page_margin_h", DEFAULT_PAGE_MARGIN_HORIZONTAL_DP); }
    public void setMarginHorizontal(int dp) { prefs.edit().putInt("page_margin_h", dp).apply(); }
    public int getMarginVertical() { return prefs.getInt("page_margin_v", 16); }
    public void setMarginVertical(int dp) { prefs.edit().putInt("page_margin_v", dp).apply(); }

    // TXT reader layout tuning. Horizontal sliders show the actual boundary in px,
    // while paging still uses the legacy pixel inset fields for stable page counts.
    // Top: positive moves the top boundary down. Bottom: negative moves the bottom boundary up.
    // Left/right default to page_margin_h converted to pixels, preserving the old page width.
    public int getReaderTextTopOffsetPx() { return prefs.getInt("reader_text_top_offset_px", 0); }
    public void setReaderTextTopOffsetPx(int px) {
        prefs.edit().putInt("reader_text_top_offset_px", Math.max(0, Math.min(240, px))).apply();
    }
    public int getReaderTextBottomOffsetPx() { return prefs.getInt("reader_text_bottom_offset_px", 0); }
    public void setReaderTextBottomOffsetPx(int px) {
        prefs.edit().putInt("reader_text_bottom_offset_px", Math.max(0, Math.min(240, px))).apply();
    }
    public int getReaderTextLeftInsetPx() {
        return normalizeReaderTextInsetPx(prefs.getInt(
                KEY_READER_TEXT_LEFT_OFFSET,
                DEFAULT_READER_TEXT_BOUNDARY_PX - getMarginHorizontalPx()));
    }
    public void setReaderTextLeftInsetPx(int px) {
        prefs.edit().putInt(KEY_READER_TEXT_LEFT_OFFSET, normalizeReaderTextInsetPx(px)).apply();
    }
    public int getReaderTextRightInsetPx() {
        return normalizeReaderTextInsetPx(prefs.getInt(
                KEY_READER_TEXT_RIGHT_OFFSET,
                DEFAULT_READER_TEXT_BOUNDARY_PX - getMarginHorizontalPx()));
    }
    public void setReaderTextRightInsetPx(int px) {
        prefs.edit().putInt(KEY_READER_TEXT_RIGHT_OFFSET, normalizeReaderTextInsetPx(px)).apply();
    }

    public int getReaderTextLeftBoundaryPx() {
        return Math.max(0, getMarginHorizontalPx() + getReaderTextLeftInsetPx());
    }

    public void setReaderTextLeftBoundaryPx(int px) {
        setReaderTextLeftInsetPx(Math.max(0, Math.min(360, px)) - getMarginHorizontalPx());
    }

    public int getReaderTextRightBoundaryPx() {
        return Math.max(0, getMarginHorizontalPx() + getReaderTextRightInsetPx());
    }

    public void setReaderTextRightBoundaryPx(int px) {
        setReaderTextRightInsetPx(Math.max(0, Math.min(360, px)) - getMarginHorizontalPx());
    }

    public int getDefaultReaderTextBoundaryPx() {
        return DEFAULT_READER_TEXT_BOUNDARY_PX;
    }

    public void resetReaderTextSideBoundariesToDefault() {
        setReaderTextLeftBoundaryPx(DEFAULT_READER_TEXT_BOUNDARY_PX);
        setReaderTextRightBoundaryPx(DEFAULT_READER_TEXT_BOUNDARY_PX);
    }

    private int normalizeReaderTextInsetPx(int px) {
        return Math.max(-getMarginHorizontalPx(), Math.min(240, px));
    }

    private int getMarginHorizontalPx() {
        float density = appContext.getResources().getDisplayMetrics().density;
        return Math.max(0, Math.round(getMarginHorizontal() * density));
    }

    // Lock
    public boolean isLockEnabled() { return prefs.getBoolean(KEY_LOCK_ENABLED, false); }
    public void setLockEnabled(boolean v) { prefs.edit().putBoolean(KEY_LOCK_ENABLED, v).apply(); }


    public void setLockPin(String pin) {
        if (pin == null || pin.isEmpty()) {
            prefs.edit().remove(KEY_LOCK_PIN).apply();
            return;
        }
        prefs.edit().putString(KEY_LOCK_PIN, createLockPinVerifier(pin)).apply();
    }

    public boolean verifyLockPin(String pin) {
        if (pin == null || pin.isEmpty()) return false;
        String stored = prefs.getString(KEY_LOCK_PIN, "");
        if (stored == null || stored.isEmpty()) return false;

        if (isLockPinVerifier(stored)) {
            return verifyLockPinVerifier(pin, stored);
        }

        // Legacy migration path: older 2.2.6 development builds stored the PIN in
        // plain SharedPreferences. On the first successful unlock/change, replace
        // it with a salted PBKDF2 verifier and never re-export the plain value.
        boolean matchesLegacyPlainPin = MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                pin.getBytes(StandardCharsets.UTF_8));
        if (matchesLegacyPlainPin) {
            setLockPin(pin);
        }
        return matchesLegacyPlainPin;
    }

    private boolean isLockPinVerifier(String stored) {
        return stored.startsWith(LOCK_PIN_SCHEME_SHA256 + "$")
                || stored.startsWith(LOCK_PIN_SCHEME_SHA1 + "$");
    }

    private String createLockPinVerifier(String pin) {
        byte[] salt = new byte[LOCK_PIN_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        try {
            byte[] hash = deriveLockPinHash(pin, salt, LOCK_PIN_ITERATIONS, "PBKDF2WithHmacSHA256");
            return LOCK_PIN_SCHEME_SHA256 + "$" + LOCK_PIN_ITERATIONS + "$"
                    + base64(salt) + "$" + base64(hash);
        } catch (RuntimeException sha256Failure) {
            byte[] hash = deriveLockPinHash(pin, salt, LOCK_PIN_ITERATIONS, "PBKDF2WithHmacSHA1");
            return LOCK_PIN_SCHEME_SHA1 + "$" + LOCK_PIN_ITERATIONS + "$"
                    + base64(salt) + "$" + base64(hash);
        }
    }

    private boolean verifyLockPinVerifier(String pin, String stored) {
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4) return false;
            String scheme = parts[0];
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 10_000) return false;
            byte[] salt = Base64.decode(parts[2], Base64.NO_WRAP);
            byte[] expected = Base64.decode(parts[3], Base64.NO_WRAP);
            if (salt.length < 8 || expected.length < 16) return false;
            String algorithm;
            if (LOCK_PIN_SCHEME_SHA256.equals(scheme)) {
                algorithm = "PBKDF2WithHmacSHA256";
            } else if (LOCK_PIN_SCHEME_SHA1.equals(scheme)) {
                algorithm = "PBKDF2WithHmacSHA1";
            } else {
                return false;
            }
            byte[] actual = deriveLockPinHash(pin, salt, iterations, algorithm);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] deriveLockPinHash(String pin, byte[] salt, int iterations, String algorithm) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, LOCK_PIN_HASH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(algorithm);
            return factory.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("PIN hash algorithm unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    private String base64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // Sort
    public int getSortMode() { return prefs.getInt("sort_mode", SORT_NAME_ASC); }
    public void setSortMode(int m) { prefs.edit().putInt("sort_mode", m).apply(); }
    public int getRecentSortMode() { return prefs.getInt("recent_sort_mode", SORT_RECENT_READ); }
    public void setRecentSortMode(int m) { prefs.edit().putInt("recent_sort_mode", m).apply(); }
    public int getArchiveSortMode() { return prefs.getInt("archive_sort_mode", SORT_NAME_ASC); }
    public void setArchiveSortMode(int m) { prefs.edit().putInt("archive_sort_mode", m).apply(); }
    public String getArchiveLastImageEntryPath(String archivePath) {
        if (archivePath == null || archivePath.trim().isEmpty()) return "";
        return prefs.getString("archive_last_image_" + Integer.toHexString(archivePath.hashCode()), "");
    }
    public void setArchiveLastImageEntryPath(String archivePath, String entryPath) {
        if (archivePath == null || archivePath.trim().isEmpty()) return;
        prefs.edit().putString("archive_last_image_" + Integer.toHexString(archivePath.hashCode()), entryPath == null ? "" : entryPath).apply();
    }
    public boolean getShowHiddenFiles() { return prefs.getBoolean("show_hidden", false); }
    public void setShowHiddenFiles(boolean v) { prefs.edit().putBoolean("show_hidden", v).apply(); }

    // Brightness
    public boolean getBrightnessOverride() { return prefs.getBoolean("brightness_override", false); }
    public void setBrightnessOverride(boolean v) { prefs.edit().putBoolean("brightness_override", v).apply(); }
    public float getBrightnessValue() { return prefs.getFloat("brightness_value", 0.5f); }
    public void setBrightnessValue(float v) { prefs.edit().putFloat("brightness_value", v).apply(); }


    // Volume key paging
    public boolean getVolumeKeyScroll() { return prefs.getBoolean("volume_key_scroll", false); }
    public void setVolumeKeyScroll(boolean v) { prefs.edit().putBoolean("volume_key_scroll", v).apply(); }

    // Tap paging
    public boolean getTapPagingEnabled() { return prefs.getBoolean("tap_paging_enabled", true); }
    public void setTapPagingEnabled(boolean v) { prefs.edit().putBoolean("tap_paging_enabled", v).apply(); }
    public int getTapZoneMode() { return prefs.getInt("tap_zone_mode", TAP_ZONE_HORIZONTAL); }
    public void setTapZoneMode(int mode) {
        int clamped = (mode == TAP_ZONE_HORIZONTAL) ? TAP_ZONE_HORIZONTAL : TAP_ZONE_VERTICAL;
        prefs.edit().putInt("tap_zone_mode", clamped).apply();
    }
    public int getTapLeadingZonePercent() { return prefs.getInt("tap_leading_zone_percent", 35); }
    public int getTapTrailingZonePercent() { return prefs.getInt("tap_trailing_zone_percent", 35); }
    public void setTapZonePercents(int leadingPercent, int trailingPercent) {
        int leading = Math.max(5, Math.min(80, leadingPercent));
        int trailing = Math.max(5, Math.min(80, trailingPercent));

        // Keep at least 10% for the middle/menu zone.
        if (leading + trailing > 90) {
            int overflow = leading + trailing - 90;
            if (leading >= trailing) {
                leading = Math.max(5, leading - overflow);
            } else {
                trailing = Math.max(5, trailing - overflow);
            }
        }

        prefs.edit()
                .putInt("tap_leading_zone_percent", leading)
                .putInt("tap_trailing_zone_percent", trailing)
                .apply();
    }
    public int getPagingOverlapLines() { return prefs.getInt("paging_overlap_lines", 0); }
    public void setPagingOverlapLines(int lines) { prefs.edit().putInt("paging_overlap_lines", Math.max(0, Math.min(4, lines))).apply(); }

    public String getManualTextEncodingForFile(File file) {
        if (file == null) return null;
        String value = prefs.getString(manualTextEncodingKey(file), null);
        if (value == null || value.trim().isEmpty()) return null;
        return value;
    }

    public void setManualTextEncodingForFile(File file, String encoding) {
        if (file == null) return;
        String key = manualTextEncodingKey(file);
        if (encoding == null || encoding.trim().isEmpty() || "Auto".equalsIgnoreCase(encoding.trim())) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, encoding.trim()).apply();
        }
    }

    public boolean hasManualTextEncodingForFile(File file) {
        return getManualTextEncodingForFile(file) != null;
    }

    public String getCachedAutoTextEncodingForFile(File file) {
        if (file == null) return null;
        String value = prefs.getString(autoTextEncodingKey(file), null);
        if (value == null || value.trim().isEmpty()) return null;
        return value;
    }

    public String getCachedAutoTextEncodingLabelForFile(File file) {
        if (file == null) return null;
        String value = prefs.getString(autoTextEncodingLabelKey(file), null);
        if (value == null || value.trim().isEmpty()) return null;
        return value;
    }

    public void setCachedAutoTextEncodingForFile(File file, String encoding, String label) {
        if (file == null || encoding == null || encoding.trim().isEmpty()) return;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(autoTextEncodingKey(file), encoding.trim());
        if (label != null && !label.trim().isEmpty()) {
            editor.putString(autoTextEncodingLabelKey(file), label.trim());
        } else {
            editor.remove(autoTextEncodingLabelKey(file));
        }
        editor.apply();
    }

    public void clearCachedAutoTextEncodingForFile(File file) {
        if (file == null) return;
        prefs.edit()
                .remove(autoTextEncodingKey(file))
                .remove(autoTextEncodingLabelKey(file))
                .apply();
    }

    private String autoTextEncodingKey(File file) {
        String path = file.getAbsolutePath();
        long length = file.length();
        long modified = file.lastModified();
        return "auto_text_encoding::" + path + "::" + length + "::" + modified;
    }

    private String autoTextEncodingLabelKey(File file) {
        String path = file.getAbsolutePath();
        long length = file.length();
        long modified = file.lastModified();
        return "auto_text_encoding_label::" + path + "::" + length + "::" + modified;
    }

    private String manualTextEncodingKey(File file) {
        String path = file.getAbsolutePath();
        long length = file.length();
        long modified = file.lastModified();
        return "manual_text_encoding::" + path + "::" + length + "::" + modified;
    }


}
