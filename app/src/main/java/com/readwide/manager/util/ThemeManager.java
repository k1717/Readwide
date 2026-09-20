package com.readwide.manager.util;

import android.content.Context;
import android.util.Log;

import com.readwide.manager.model.Theme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages reading themes (built-in + custom).
 */
public class ThemeManager {
    private static final String TAG = "ThemeManager";
    private static final String THEMES_FILE = "custom_themes.json";

    private static ThemeManager instance;
    private final Context context;
    private List<Theme> customThemes;
    private String activeThemeId;

    private ThemeManager(Context context) {
        this.context = context.getApplicationContext();
        loadCustomThemes();
        activeThemeId = PrefsManager.getInstance(context)
                .getPrefs().getString("active_theme_id", "dark");
    }

    public static synchronized ThemeManager getInstance(Context context) {
        if (instance == null) {
            instance = new ThemeManager(context);
        }
        return instance;
    }

    /**
     * Re-read theme state that can be changed by another Activity before a viewer
     * resumes. This prevents viewer popups from using a stale cached theme after
     * returning from Settings or the theme editor.
     */
    public synchronized void reloadFromStorage() {
        loadCustomThemes();
        syncActiveThemeIdFromPrefs();
    }

    private void syncActiveThemeIdFromPrefs() {
        activeThemeId = PrefsManager.getInstance(context)
                .getPrefs().getString("active_theme_id", "dark");
    }

    public synchronized List<Theme> getAllThemes() {
        syncActiveThemeIdFromPrefs();
        List<Theme> all = new ArrayList<>();
        for (Theme t : Theme.BUILT_IN_THEMES) {
            all.add(t);
        }
        all.addAll(customThemes);
        return all;
    }

    public synchronized Theme getActiveTheme() {
        syncActiveThemeIdFromPrefs();
        for (Theme t : Theme.BUILT_IN_THEMES) {
            if (t.getId().equals(activeThemeId)) return t;
        }
        for (Theme t : customThemes) {
            if (t.getId().equals(activeThemeId)) return t;
        }
        return Theme.LIGHT;
    }

    public synchronized void setActiveTheme(String themeId) {
        this.activeThemeId = themeId;
        PrefsManager.getInstance(context).getPrefs().edit()
                .putString("active_theme_id", themeId).commit();
    }

    public synchronized boolean addCustomTheme(Theme theme) {
        List<Theme> planned = new ArrayList<>(customThemes);
        planned.add(theme.copy());
        return saveCustomThemes(planned);
    }

    public synchronized boolean updateCustomTheme(Theme theme) {
        for (int i = 0; i < customThemes.size(); i++) {
            if (customThemes.get(i).getId().equals(theme.getId())) {
                List<Theme> planned = new ArrayList<>(customThemes);
                planned.set(i, theme.copy());
                return saveCustomThemes(planned);
            }
        }
        return false;
    }

    public synchronized boolean deleteCustomTheme(String themeId) {
        List<Theme> planned = new ArrayList<>(customThemes);
        if (!planned.removeIf(t -> t.getId().equals(themeId))) return false;
        if (!saveCustomThemes(planned)) return false;
        syncActiveThemeIdFromPrefs();
        if (themeId.equals(activeThemeId)) {
            setActiveTheme("light");
        }
        return true;
    }

    private void loadCustomThemes() {
        customThemes = new ArrayList<>();
        File file = new File(context.getFilesDir(), THEMES_FILE);
        try {
            String saved = AtomicUtf8File.readIfPresent(file);
            if (saved == null) return;
            JSONObject root = new JSONObject(saved);
            JSONArray arr = root.optJSONArray("themes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    customThemes.add(Theme.fromJson(arr.getJSONObject(i)));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load custom themes", e);
        }
    }

    private boolean saveCustomThemes(List<Theme> planned) {
        try {
            // Publish new objects only after the atomic file write succeeds.
            writeImportThemes(planned);
            customThemes = new ArrayList<>(planned);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save custom themes", e);
            return false;
        }
    }

    public synchronized JSONArray exportCustomThemesToJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Theme t : customThemes) {
            arr.put(t.toJson());
        }
        return arr;
    }

    public synchronized void importCustomThemesFromJson(JSONArray arr, boolean merge)
            throws JSONException, java.io.IOException {
        if (arr == null) return;
        List<Theme> imported = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Theme theme = Theme.fromJson(arr.getJSONObject(i));
            if (!theme.isBuiltIn()) imported.add(theme);
        }
        replaceFromImport(IndexedBackupMerge.merge(
                merge ? customThemes : java.util.Collections.emptyList(), imported, Theme::getId, null));
    }

    synchronized List<Theme> snapshotForImport() { return new ArrayList<>(customThemes); }

    synchronized void replaceFromImport(List<Theme> planned) throws JSONException, java.io.IOException {
        writeImportThemes(planned);
        customThemes = new ArrayList<>(planned);
    }

    synchronized void restoreAfterImportFailure(List<Theme> previous) throws JSONException, java.io.IOException {
        customThemes = new ArrayList<>(previous);
        writeImportThemes(previous);
    }

    private void writeImportThemes(List<Theme> planned) throws JSONException, java.io.IOException {
        JSONArray rows = new JSONArray();
        for (Theme theme : planned) rows.put(theme.toJson());
        JSONObject root = new JSONObject();
        root.put("themes", rows);
        AtomicUtf8File.write(new File(context.getFilesDir(), THEMES_FILE), root.toString(2));
    }

}
