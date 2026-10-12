package com.readwide.manager.util;

import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.model.Theme;
import com.readwide.manager.model.DocumentAnnotation;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Validates every supplied section before any model/preferences/disk mutation. */
final class BackupImportData {
    final JSONObject root;
    final List<Bookmark> bookmarks;
    final Map<String, ReaderState> states;
    final List<Theme> themes;
    final List<DocumentAnnotation> annotations;
    final JSONObject settings;
    final Map<String, String> txtContents;

    BackupImportData(String json) throws JSONException {
        root = new JSONObject(json);
        JSONArray rows = array(root, "bookmarks");
        bookmarks = rows == null ? null : new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            requirePath(row);
            intFields(row, "charPosition", "endPosition", "lineNumber", "pageNumber", "totalPages");
            longFields(row, "fileSizeBytes", "createdAt", "updatedAt");
            JSONObject identity = object(row, "fileIdentity");
            if (identity != null) longFields(identity, "sizeBytes");
            JSONObject pending = object(row, "pendingPcTextEdit");
            if (pending != null) intFields(pending, "line", "moveByLines", "findOccurrence");
            bookmarks.add(Bookmark.fromJson(row));
        }
        JSONObject stateRows = object(root, "readingStates");
        states = stateRows == null ? null : new HashMap<>();
        if (stateRows != null) {
            Iterator<String> keys = stateRows.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                JSONObject row = stateRows.getJSONObject(path);
                requirePath(row);
                intFields(row, "charPosition", "scrollY", "pageNumber", "totalPages");
                longFields(row, "fileLength", "lastReadAt");
                ReaderState state = ReaderState.fromJson(row);
                if (!path.equals(state.getFilePath())) throw new JSONException("Reading-state path does not match its key");
                states.put(path, state);
            }
        }
        rows = array(root, "customThemes");
        themes = rows == null ? null : new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            intFields(row, "textColor", "backgroundColor", "linkColor", "toolbarColor");
            validateThemeAlpha(row);
            Theme theme = Theme.fromJson(row);
            theme.toJson(); // Reject non-finite edited numeric values before any commit.
            if (!theme.isBuiltIn()) themes.add(theme);
        }
        rows = array(root, "annotations");
        annotations = rows == null ? null : new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            requirePath(row);
            intFields(row, "startPosition", "endPosition", "lineNumber");
            longFields(row, "createdAt", "updatedAt");
            DocumentAnnotation annotation = DocumentAnnotation.fromJson(row);
            annotation.toJson();
            annotations.add(annotation);
        }
        settings = object(root, "settings");
        if (settings != null) PrefsManager.validateImportSettings(settings);
        JSONObject contents = object(root, "txtContents");
        txtContents = contents == null ? null : TxtContentsBackup.parse(contents);
        if (bookmarks == null && states == null && themes == null && annotations == null && settings == null && txtContents == null)
            throw new JSONException("No supported backup sections found");
    }

    private static JSONArray array(JSONObject root, String name) throws JSONException {
        return root.has(name) ? root.getJSONArray(name) : null;
    }
    private static JSONObject object(JSONObject root, String name) throws JSONException {
        return root.has(name) ? root.getJSONObject(name) : null;
    }
    private static void requirePath(JSONObject row) throws JSONException {
        Object path = row.get("filePath");
        if (!(path instanceof String) || ((String) path).trim().isEmpty()) throw new JSONException("Missing backup file path");
    }

    private static void intFields(JSONObject row, String... keys) throws JSONException {
        integerFields(row, Integer.MIN_VALUE, Integer.MAX_VALUE, keys);
    }

    private static void longFields(JSONObject row, String... keys) throws JSONException {
        integerFields(row, Long.MIN_VALUE, Long.MAX_VALUE, keys);
    }

    /**
     * Model readers intentionally tolerate old on-device data. At the import boundary,
     * reject lossy narrowing instead of letting optInt/optLong wrap, truncate or default.
     * Missing fields retain legacy defaults and signed values retain model semantics.
     * Normalize exact numeric strings on this owned JSON tree: Android's string-to-long
     * coercion otherwise passes through double and can round integers above 2^53.
     */
    private static void integerFields(JSONObject row, long min, long max, String... keys)
            throws JSONException {
        for (String key : keys) {
            if (!row.has(key)) continue;
            Object raw = row.get(key);
            if (!(raw instanceof Number) && !(raw instanceof String))
                throw new JSONException("Invalid integer backup field: " + key);
            final long value;
            try {
                value = new BigDecimal(raw.toString().trim()).longValueExact();
            } catch (NumberFormatException | ArithmeticException invalid) {
                throw new JSONException("Invalid integer backup field: " + key);
            }
            if (value < min || value > max)
                throw new JSONException("Out-of-range backup field: " + key);
            row.put(key, value);
        }
    }

    private static void validateThemeAlpha(JSONObject row) throws JSONException {
        if (!row.has("backgroundImageAlpha")) return;
        Object raw = row.get("backgroundImageAlpha");
        if (!(raw instanceof Number) && !(raw instanceof String))
            throw new JSONException("Invalid theme background alpha");
        final double alpha;
        try {
            alpha = Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException invalid) {
            throw new JSONException("Invalid theme background alpha");
        }
        if (Double.isNaN(alpha) || Double.isInfinite(alpha) || alpha < 0.0 || alpha > 1.0)
            throw new JSONException("Theme background alpha must be between 0 and 1");
        row.put("backgroundImageAlpha", alpha);
    }
}
