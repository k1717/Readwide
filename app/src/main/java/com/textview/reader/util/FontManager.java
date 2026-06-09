package com.textview.reader.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scans for and manages fonts available on the device.
 */
public class FontManager {
    private static final String TAG = "FontManager";
    public static final String SYSTEM_FAMILY_PREFIX = "system_family:";
    private static final String FONT_PREFS = "font_manager_prefs";
    private static final String KEY_ADDED_FONTS = "added_font_names";
    private static final String KEY_HIDDEN_FONTS = "hidden_added_font_names";
    private static FontManager instance;

    private final Map<String, String> fontPaths = new HashMap<>(); // displayName -> path
    private final Map<String, Typeface> fontCache = new HashMap<>();
    private final List<String> userFontNames = new ArrayList<>();
    private final List<String> systemInstalledFontNames = new ArrayList<>();
    private boolean scanned = false;

    // Built-in Android font directories. These are scanned for the optional
    // full system-font picker opened from the Add font button.
    private static final String[] BUILT_IN_FONT_DIRS = {
            "/system/fonts",
            "/system/font",
            "/product/fonts",
            "/system_ext/fonts",
            "/vendor/fonts",
            "/odm/fonts"
    };

    // OS/user-installed font locations used by some Android/Samsung builds.
    // These are the "system installed custom fonts" the picker should expose.
    private static final String[] SYSTEM_INSTALLED_FONT_DIRS = {
            "/data/fonts",
            "/data/font",
            "/data/app_fonts",
            "/data/overlays/fonts"
    };

    private static final String[] FONT_EXTENSIONS = {".ttf", ".otf", ".ttc"};

    public interface OnScanCompleteListener {
        void onScanComplete(List<String> fontNames);
    }

    private FontManager() {}

    public static synchronized FontManager getInstance() {
        if (instance == null) {
            instance = new FontManager();
        }
        return instance;
    }

    /**
     * Scan for fonts asynchronously.
     */
    public void scanFonts(Context context, OnScanCompleteListener listener) {
        final Context appContext = context != null ? context.getApplicationContext() : null;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            List<String> names = Collections.emptyList();
            try {
                if (appContext != null) {
                    doScan(appContext);
                    names = getFontNames();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Font scan failed", e);
            } finally {
                final List<String> callbackNames = names;
                if (listener != null) {
                    mainHandler.post(() -> listener.onScanComplete(callbackNames));
                }
                executor.shutdown();
            }
        });
    }

    /**
     * Scan fonts synchronously.
     */
    public void scanFontsSync(Context context) {
        doScan(context);
    }

    private void doScan(Context context) {
        fontPaths.clear();
        userFontNames.clear();
        systemInstalledFontNames.clear();

        // Always include defaults
        fontPaths.put("Default (Sans-serif)", "DEFAULT");
        fontPaths.put("Serif", "SERIF");
        fontPaths.put("Monospace", "MONOSPACE");

        // Scan built-in system fonts for backward compatibility only.
        // Do not expose these raw Roboto/Noto files in the visible picker.
        for (String dir : BUILT_IN_FONT_DIRS) {
            scanDirectory(new File(dir), false, false);
        }

        // Scan OS/user-installed custom fonts separately and expose only these
        // as "Installed" fonts in the picker.
        for (String dir : SYSTEM_INSTALLED_FONT_DIRS) {
            scanDirectory(new File(dir), false, true);
        }

        // Manually provided font directories remain supported, but they are listed
        // separately from OS-installed fonts.
        File externalFonts = new File(Environment.getExternalStorageDirectory(), "Fonts");
        if (externalFonts.exists()) {
            scanDirectory(externalFonts, true, false);
        }

        File downloadFonts = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "Fonts");
        if (downloadFonts.exists()) {
            scanDirectory(downloadFonts, true, false);
        }

        // App-specific font directory
        File appFonts = new File(context.getFilesDir(), "fonts");
        if (appFonts.exists()) {
            scanDirectory(appFonts, true, false);
        }

        scanned = true;
        Log.i(TAG, "Font scan complete: " + fontPaths.size() + " fonts found");
    }

    private void scanDirectory(File dir) {
        scanDirectory(dir, false, false);
    }

    private void scanDirectory(File dir, boolean userFont) {
        scanDirectory(dir, userFont, false);
    }

    private void scanDirectory(File dir, boolean userFont, boolean systemInstalledFont) {
        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(f, userFont, systemInstalledFont); // recursive
                continue;
            }
            String name = f.getName().toLowerCase();
            for (String ext : FONT_EXTENSIONS) {
                if (name.endsWith(ext)) {
                    try {
                        // Try to load it to verify it's valid
                        Typeface tf = Typeface.createFromFile(f);
                        if (tf != null) {
                            String displayName = f.getName();
                            // Remove extension for display
                            int dotIdx = displayName.lastIndexOf('.');
                            if (dotIdx > 0) displayName = displayName.substring(0, dotIdx);
                            // Replace hyphens/underscores with spaces
                            displayName = displayName.replace('-', ' ').replace('_', ' ');
                            fontPaths.put(displayName, f.getAbsolutePath());
                            fontCache.put(f.getAbsolutePath(), tf);
                            if (systemInstalledFont && !systemInstalledFontNames.contains(displayName)) {
                                systemInstalledFontNames.add(displayName);
                            } else if (userFont && !userFontNames.contains(displayName)) {
                                userFontNames.add(displayName);
                            }
                        }
                    } catch (Exception e) {
                        // Skip invalid font files
                    }
                    break;
                }
            }
        }
    }

    public List<String> getFontNames() {
        List<String> names = new ArrayList<>(fontPaths.keySet());
        Collections.sort(names, (a, b) -> {
            // Keep defaults at top
            boolean aDefault = a.startsWith("Default") || a.equals("Serif") || a.equals("Monospace");
            boolean bDefault = b.startsWith("Default") || b.equals("Serif") || b.equals("Monospace");
            if (aDefault && !bDefault) return -1;
            if (!aDefault && bDefault) return 1;
            return a.compareToIgnoreCase(b);
        });
        return names;
    }

    public List<String> getAllSystemFontNames() {
        return getFontNames();
    }

    public List<String> getSystemInstalledFontNames() {
        List<String> names = new ArrayList<>(systemInstalledFontNames);
        Collections.sort(names, String::compareToIgnoreCase);
        return names;
    }

    public List<String> getUserFontNames() {
        List<String> names = new ArrayList<>(userFontNames);
        Collections.sort(names, String::compareToIgnoreCase);
        return names;
    }

    private SharedPreferences fontPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FONT_PREFS, Context.MODE_PRIVATE);
    }

    private Set<String> loadFontSet(Context context, String key) {
        Set<String> raw = fontPrefs(context).getStringSet(key, null);
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String name : raw) {
                if (name != null && !name.trim().isEmpty()) result.add(name.trim());
            }
        }
        return result;
    }

    private void saveFontSet(Context context, String key, Set<String> values) {
        fontPrefs(context).edit().putStringSet(key, new HashSet<>(values)).apply();
    }

    private boolean isBuiltInLogicalFont(String fontName) {
        if (fontName == null) return true;
        String path = fontPaths.get(fontName.trim());
        if (path == null) return false;
        return "DEFAULT".equals(path) || "SERIF".equals(path) || "MONOSPACE".equals(path);
    }

    /**
     * Fonts that the user explicitly added to the compact Font picker.
     * This is intentionally separate from the full Add font / All system fonts list:
     * removing a font from the compact picker only hides it from that picker, and the
     * source font remains available in the full list for re-adding later.
     */
    public List<String> getUserAddedFontNames(Context context) {
        if (context == null) return getUserFontNames();
        if (!scanned) doScan(context);

        Set<String> hidden = loadFontSet(context, KEY_HIDDEN_FONTS);
        Set<String> names = new LinkedHashSet<>();

        for (String name : loadFontSet(context, KEY_ADDED_FONTS)) {
            if (fontPaths.containsKey(name) && !hidden.contains(name) && !isBuiltInLogicalFont(name)) {
                names.add(name);
            }
        }

        // Preserve compatibility with font files that were previously copied into or
        // placed under this app's font locations.  These remain removable from the
        // compact picker, but the actual file is not deleted.
        for (String name : userFontNames) {
            if (name != null && fontPaths.containsKey(name) && !hidden.contains(name) && !isBuiltInLogicalFont(name)) {
                names.add(name);
            }
        }

        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted, String::compareToIgnoreCase);
        return sorted;
    }

    /**
     * Add a selected font to the compact Font picker.  Multiple fonts are retained.
     */
    public boolean addUserFont(Context context, String fontName) {
        if (context == null || fontName == null || fontName.trim().isEmpty()) return false;
        if (!scanned) doScan(context);

        String key = fontName.trim();
        if (!fontPaths.containsKey(key) || isBuiltInLogicalFont(key)) return false;

        Set<String> added = loadFontSet(context, KEY_ADDED_FONTS);
        Set<String> hidden = loadFontSet(context, KEY_HIDDEN_FONTS);
        added.add(key);
        hidden.remove(key);
        saveFontSet(context, KEY_ADDED_FONTS, added);
        saveFontSet(context, KEY_HIDDEN_FONTS, hidden);
        return true;
    }

    /**
     * Remove a user-added font from the compact Font picker only.  This does not
     * delete system fonts or font files, so the font can be added again from the full
     * Add font / All system fonts list.
     */
    public boolean isRemovableUserFont(Context context, String fontName) {
        if (context == null || fontName == null || fontName.trim().isEmpty()) return false;
        String key = fontName.trim();
        return getUserAddedFontNames(context).contains(key);
    }

    public boolean removeUserFont(Context context, String fontName) {
        if (!isRemovableUserFont(context, fontName)) return false;

        String key = fontName.trim();
        Set<String> added = loadFontSet(context, KEY_ADDED_FONTS);
        Set<String> hidden = loadFontSet(context, KEY_HIDDEN_FONTS);
        added.remove(key);
        hidden.add(key);
        saveFontSet(context, KEY_ADDED_FONTS, added);
        saveFontSet(context, KEY_HIDDEN_FONTS, hidden);
        return true;
    }

    public static String toSystemFamilyValue(String familyName) {
        if (familyName == null) return SYSTEM_FAMILY_PREFIX;
        return SYSTEM_FAMILY_PREFIX + familyName.trim();
    }

    public static boolean isSystemFamilyValue(String value) {
        return value != null && value.startsWith(SYSTEM_FAMILY_PREFIX);
    }

    public static String getSystemFamilyName(String value) {
        if (!isSystemFamilyValue(value)) return "";
        return value.substring(SYSTEM_FAMILY_PREFIX.length()).trim();
    }

    /**
     * Get Typeface for a font name.
     */
    public Typeface getTypeface(String fontName) {
        if (fontName == null || fontName.equals("default") || fontName.equals("DEFAULT")) {
            return Typeface.DEFAULT;
        }

        if (isSystemFamilyValue(fontName)) {
            String familyName = getSystemFamilyName(fontName);
            if (familyName.isEmpty()) return Typeface.DEFAULT;
            try {
                return Typeface.create(familyName, Typeface.NORMAL);
            } catch (RuntimeException ignored) {
                return Typeface.DEFAULT;
            }
        }

        String path = fontPaths.get(fontName);
        if (path == null) {
            // Try matching by stored path directly
            path = fontName;
        }

        switch (path) {
            case "DEFAULT":
                return Typeface.DEFAULT;
            case "SERIF":
                return Typeface.SERIF;
            case "MONOSPACE":
                return Typeface.MONOSPACE;
            default:
                // Load from file
                if (fontCache.containsKey(path)) {
                    return fontCache.get(path);
                }
                try {
                    Typeface tf = Typeface.createFromFile(path);
                    fontCache.put(path, tf);
                    return tf;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load font: " + path, e);
                    return Typeface.DEFAULT;
                }
        }
    }

    public boolean isScanned() { return scanned; }

    /**
     * Return the backing font file path for a scanned/imported font name.
     * Built-in logical families such as DEFAULT/SERIF/MONOSPACE return null
     * because WebView can address those through CSS family names directly.
     */
    /**
     * Return a stable, process-invariant identifier for a typeface that is selected
     * by the given font name. Used by page-index signatures so that the exact page
     * index is not rebuilt across app restarts just because the in-memory Typeface
     * object was re-created with a different object hash.
     *
     * Logical built-in families collapse to a fixed string. File-backed fonts use
     * their canonical filesystem path. System-family-selected fonts use the
     * family name as it appears in the user preference.
     */
    public String getStableTypefaceKey(String fontName) {
        if (fontName == null || fontName.trim().isEmpty()
                || fontName.equals("default") || fontName.equals("DEFAULT")) {
            return "default";
        }
        if (isSystemFamilyValue(fontName)) {
            String familyName = getSystemFamilyName(fontName);
            return familyName.isEmpty() ? "default" : ("family:" + familyName);
        }
        String path = fontPaths.get(fontName);
        if (path == null) path = fontName;
        if (path == null) return "default";
        switch (path) {
            case "DEFAULT": return "default";
            case "SERIF": return "serif";
            case "MONOSPACE": return "monospace";
            default: return "file:" + path;
        }
    }

    public String getFontPathForName(String fontName) {
        if (fontName == null || fontName.trim().isEmpty()) return null;
        if (isSystemFamilyValue(fontName)) return null;

        String path = fontPaths.get(fontName);
        if (path == null) path = fontName;
        if (path == null) return null;

        switch (path) {
            case "DEFAULT":
            case "SERIF":
            case "MONOSPACE":
                return null;
            default:
                File file = new File(path);
                return file.isFile() ? file.getAbsolutePath() : null;
        }
    }

    /**
     * Copy a font file to the app's internal font directory.
     */
    public String importFont(Context context, File sourceFile) {
        File fontDir = new File(context.getFilesDir(), "fonts");
        if (!fontDir.exists()) fontDir.mkdirs();

        File destFile = new File(fontDir, sourceFile.getName());
        try {
            java.nio.file.Files.copy(sourceFile.toPath(), destFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // Verify and register
            Typeface tf = Typeface.createFromFile(destFile);
            if (tf != null) {
                String displayName = destFile.getName();
                int dotIdx = displayName.lastIndexOf('.');
                if (dotIdx > 0) displayName = displayName.substring(0, dotIdx);
                displayName = displayName.replace('-', ' ').replace('_', ' ');
                fontPaths.put(displayName, destFile.getAbsolutePath());
                fontCache.put(destFile.getAbsolutePath(), tf);
                if (!userFontNames.contains(displayName)) {
                    userFontNames.add(displayName);
                }
                return displayName;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to import font", e);
        }
        return null;
    }
}
