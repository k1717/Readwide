package com.readwide.manager.util;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final long MAX_FONT_BYTES = 64L * 1024 * 1024; // reject runaway content-stream copies (large CJK/TTC still fit)
    private static final int MAX_FONT_FILE_NAME_BYTES = 240; // bound the sanitized font filename in UTF-8 bytes to avoid ENAMETOOLONG (filesystem NAME_MAX is byte-based, ~255)
    private static final int MAX_FONT_SCAN_DEPTH = 8;
    private static final int MAX_FONT_SCAN_FILES_PER_ROOT = 2000;

    public interface OnScanCompleteListener {
        void onScanComplete(List<String> fontNames);
    }

    /**
     * Best-effort cancellation handle for asynchronous font scans. The scan may
     * already be inside Typeface/native font parsing, but a cancelled handle will
     * suppress the main-thread callback and release the listener reference earlier.
     */
    public static final class ScanHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<OnScanCompleteListener> listenerRef = new AtomicReference<>();

        private ScanHandle(OnScanCompleteListener listener) {
            listenerRef.set(listener);
        }

        public void cancel() {
            cancelled.set(true);
            listenerRef.set(null);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        OnScanCompleteListener listener() {
            return listenerRef.get();
        }

        void clearListener() {
            listenerRef.set(null);
        }
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
    public ScanHandle scanFonts(Context context, OnScanCompleteListener listener) {
        final ScanHandle handle = new ScanHandle(listener);
        final Context appContext = context != null ? context.getApplicationContext() : null;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            List<String> names = Collections.emptyList();
            try {
                if (appContext != null && !handle.isCancelled()) {
                    doScan(appContext);
                    names = getFontNames();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Font scan failed", e);
            } finally {
                final List<String> callbackNames = names;
                OnScanCompleteListener callback = handle.listener();
                if (callback != null && !handle.isCancelled()) {
                    boolean posted = mainHandler.post(() -> {
                        try {
                            OnScanCompleteListener current = handle.listener();
                            if (current != null && !handle.isCancelled()) {
                                current.onScanComplete(callbackNames);
                            }
                        } finally {
                            handle.clearListener();
                        }
                    });
                    if (!posted) {
                        handle.clearListener();
                    }
                } else {
                    handle.clearListener();
                }
                executor.shutdown();
            }
        });
        return handle;
    }

    /**
     * Scan fonts synchronously.
     */
    public void scanFontsSync(Context context) {
        if (context == null) return;
        doScan(context.getApplicationContext());
    }

    private synchronized void doScan(Context context) {
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
        scanDirectory(dir, userFont, systemInstalledFont, 0, new int[] {0});
    }

    private void scanDirectory(File dir, boolean userFont, boolean systemInstalledFont, int depth, int[] visitedFileCount) {
        if (dir == null || depth > MAX_FONT_SCAN_DEPTH || visitedFileCount[0] >= MAX_FONT_SCAN_FILES_PER_ROOT) return;
        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (visitedFileCount[0] >= MAX_FONT_SCAN_FILES_PER_ROOT) return;
            if (f == null) continue;
            if (f.isDirectory()) {
                scanDirectory(f, userFont, systemInstalledFont, depth + 1, visitedFileCount); // recursive
                continue;
            }
            visitedFileCount[0]++;
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

    public synchronized List<String> getFontNames() {
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

    public synchronized List<String> getAllSystemFontNames() {
        return getFontNames();
    }

    public synchronized List<String> getSystemInstalledFontNames() {
        List<String> names = new ArrayList<>(systemInstalledFontNames);
        Collections.sort(names, String::compareToIgnoreCase);
        return names;
    }

    public synchronized List<String> getUserFontNames() {
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
    public synchronized List<String> getUserAddedFontNames(Context context) {
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
    public synchronized boolean addUserFont(Context context, String fontName) {
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
    public synchronized boolean isRemovableUserFont(Context context, String fontName) {
        if (context == null || fontName == null || fontName.trim().isEmpty()) return false;
        String key = fontName.trim();
        return getUserAddedFontNames(context).contains(key);
    }

    public synchronized boolean removeUserFont(Context context, String fontName) {
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
    public synchronized Typeface getTypeface(String fontName) {
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
                    Log.e(TAG, "Failed to load font: " + new File(path).getName(), e);
                    return Typeface.DEFAULT;
                }
        }
    }

    public synchronized boolean isScanned() { return scanned; }

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
    public synchronized String getStableTypefaceKey(String fontName) {
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

    public synchronized String getFontPathForName(String fontName) {
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
    public synchronized String importFont(Context context, File sourceFile) {
        if (context == null || sourceFile == null) return null;
        File fontDir = new File(context.getFilesDir(), "fonts");
        if (!fontDir.exists() && !fontDir.mkdirs()) {
            Log.e(TAG, "Failed to create font directory");
            return null;
        }

        String safeName = safeFontFileName(sourceFile.getName());
        File destFile = uniqueFontDestination(fontDir, safeName);
        // Keep the copied file strictly inside the app font directory.
        try {
            String dirCanonical = fontDir.getCanonicalPath();
            String destCanonical = destFile.getCanonicalPath();
            if (!destCanonical.equals(dirCanonical)
                    && !destCanonical.startsWith(dirCanonical + File.separator)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        boolean copied = false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(sourceFile)) {
            copyBounded(in, destFile, MAX_FONT_BYTES);
            copied = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to import font", e);
        }
        if (!copied) {
            deleteQuietly(destFile);
            return null;
        }

        try {
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
            Log.e(TAG, "Imported file is not a usable font", e);
        }
        deleteQuietly(destFile);
        return null;
    }

    /**
     * Import a font file selected through SAF/DocumentsUI (a content:// Uri) into
     * the app internal font directory. The display name is derived from the
     * document display name and sanitized to a safe .ttf/.otf/.ttc filename. The
     * font is verified with Typeface before it is registered; an unreadable file is
     * deleted and null is returned.
     */
    public synchronized String importFont(Context context, android.net.Uri uri) {
        if (context == null || uri == null) return null;

        String requestedName = queryFontDisplayName(context, uri);
        String safeName = safeFontFileName(requestedName);

        File fontDir = new File(context.getFilesDir(), "fonts");
        if (!fontDir.exists() && !fontDir.mkdirs()) {
            Log.e(TAG, "Failed to create font directory");
            return null;
        }

        File destFile = uniqueFontDestination(fontDir, safeName);
        // Keep the copied file strictly inside the app font directory.
        try {
            String dirCanonical = fontDir.getCanonicalPath();
            String destCanonical = destFile.getCanonicalPath();
            if (!destCanonical.equals(dirCanonical)
                    && !destCanonical.startsWith(dirCanonical + File.separator)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        boolean copied = false;
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            copyBounded(in, destFile, MAX_FONT_BYTES);
            copied = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy imported font", e);
        }
        if (!copied) {
            deleteQuietly(destFile);
            return null;
        }

        try {
            Typeface tf = Typeface.createFromFile(destFile);
            if (tf != null) {
                String displayName = destFile.getName();
                int dotIdx = displayName.lastIndexOf('.');
                if (dotIdx > 0) displayName = displayName.substring(0, dotIdx);
                displayName = displayName.replace('-', ' ').replace('_', ' ').trim();
                if (displayName.isEmpty()) displayName = "Imported font";
                fontPaths.put(displayName, destFile.getAbsolutePath());
                fontCache.put(destFile.getAbsolutePath(), tf);
                if (!userFontNames.contains(displayName)) {
                    userFontNames.add(displayName);
                }
                return displayName;
            }
        } catch (Exception e) {
            Log.e(TAG, "Imported file is not a usable font", e);
        }

        // Not a valid/readable font: do not keep the copied file around.
        deleteQuietly(destFile);
        return null;
    }

    private String queryFontDisplayName(Context context, android.net.Uri uri) {
        String name = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            } catch (Exception ignored) {
                // Fall back to the path segment below.
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    /**
     * Reduce an untrusted document name to a safe font filename that stays inside
     * the app font directory. Strips path separators and control characters,
     * collapses parent-directory tokens, and guarantees a .ttf/.otf/.ttc extension.
     */
    private String safeFontFileName(String rawName) {
        String name = rawName != null ? rawName.trim() : "";

        // Keep only the final path component.
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 <= name.length()) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == 0) continue;
            if (c < 0x20) continue;
            sb.append(c);
        }
        name = sb.toString().replace("..", "").trim();

        String lower = name.toLowerCase(java.util.Locale.US);
        boolean hasFontExt = false;
        for (String ext : FONT_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                hasFontExt = true;
                break;
            }
        }
        if (name.isEmpty()) {
            name = "imported_font_" + System.currentTimeMillis() + ".ttf";
        } else if (!hasFontExt) {
            name = name + ".ttf";
        }
        if (utf8Length(name) > MAX_FONT_FILE_NAME_BYTES) {
            String ext = ".ttf";
            String lowerName = name.toLowerCase(java.util.Locale.US);
            for (String e : FONT_EXTENSIONS) {
                if (lowerName.endsWith(e)) {
                    ext = e;
                    break;
                }
            }
            String base = name.substring(0, name.length() - ext.length());
            int maxBaseBytes = Math.max(1, MAX_FONT_FILE_NAME_BYTES - utf8Length(ext));
            base = truncateToUtf8(base, maxBaseBytes);
            name = base + ext;
        }
        return name;
    }

    /** UTF-8 encoded byte length of a string. */
    private static int utf8Length(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Truncate to at most maxBytes UTF-8 bytes without splitting a multi-byte
     * character, so a long non-ASCII name (for example Korean, 3 bytes per glyph)
     * cannot exceed the filesystem byte limit for a single path component.
     */
    private static String truncateToUtf8(String s, int maxBytes) {
        if (s == null || maxBytes <= 0) return "";
        if (utf8Length(s) <= maxBytes) return s;
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpLen = cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            if (bytes + cpLen > maxBytes) break;
            bytes += cpLen;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    /**
     * Copy a stream into the destination file with a hard byte ceiling. Replaces
     * java.nio.file.Files.copy (which requires API 26) so font import works on the
     * minSdk 24 baseline, and bounds untrusted content provider streams.
     */
    // Package-private so the byte cap can be exercised directly from a unit test.
    static void copyBounded(java.io.InputStream in, File destFile, long maxBytes)
            throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new java.io.IOException("Font file exceeds size limit");
                }
                out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    /**
     * Pick a destination that does not clobber an existing font of the same name,
     * appending a numeric suffix before the extension when needed.
     */
    // Package-private so the collision-suffix byte cap can be exercised from a unit test.
    File uniqueFontDestination(File fontDir, String safeName) {
        File file = new File(fontDir, safeName);
        if (!file.exists()) return file;

        int dot = safeName.lastIndexOf('.');
        String base = dot > 0 ? safeName.substring(0, dot) : safeName;
        String ext = dot > 0 ? safeName.substring(dot) : ".ttf";
        for (int i = 2; i < 1000; i++) {
            String suffix = " (" + i + ")";
            // Re-truncate the base so the collision-resolved name still fits the byte cap.
            int maxBaseBytes = Math.max(1, MAX_FONT_FILE_NAME_BYTES - utf8Length(suffix) - utf8Length(ext));
            File candidate = new File(fontDir, truncateToUtf8(base, maxBaseBytes) + suffix + ext);
            if (!candidate.exists()) return candidate;
        }
        String timestampSuffix = "_" + System.currentTimeMillis();
        int maxFallbackBaseBytes = Math.max(1, MAX_FONT_FILE_NAME_BYTES - utf8Length(timestampSuffix) - utf8Length(ext));
        return new File(fontDir, truncateToUtf8(base, maxFallbackBaseBytes) + timestampSuffix + ext);
    }

    private void deleteQuietly(File file) {
        try {
            if (file != null && file.exists()) {
                file.delete();
            }
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }
}
