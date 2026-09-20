package com.readwide.manager.util;

import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.model.Theme;
import com.readwide.manager.model.DocumentAnnotation;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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

    BackupImportData(String json) throws JSONException {
        root = new JSONObject(json);
        JSONArray rows = array(root, "bookmarks");
        bookmarks = rows == null ? null : new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            requirePath(row);
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
                ReaderState state = ReaderState.fromJson(row);
                if (!path.equals(state.getFilePath())) throw new JSONException("Reading-state path does not match its key");
                states.put(path, state);
            }
        }
        rows = array(root, "customThemes");
        themes = rows == null ? null : new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            Theme theme = Theme.fromJson(rows.getJSONObject(i));
            theme.toJson(); // Reject non-finite edited numeric values before any commit.
            if (!theme.isBuiltIn()) themes.add(theme);
        }
        rows = array(root, "annotations");
        annotations = rows == null ? null : new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            requirePath(row);
            DocumentAnnotation annotation = DocumentAnnotation.fromJson(row);
            annotation.toJson();
            annotations.add(annotation);
        }
        settings = object(root, "settings");
        if (settings != null) PrefsManager.validateImportSettings(settings);
        if (bookmarks == null && states == null && themes == null && annotations == null && settings == null)
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
}
