package com.readwide.manager.util;

import android.content.Context;
import android.util.Log;

import com.readwide.manager.model.DocumentAnnotation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * App-private storage for source annotations. The original TXT/Markdown file is
 * never opened for writing.
 */
public final class DocumentAnnotationManager {
    private static final String TAG = "DocumentAnnotations";
    private static final String FILE_NAME = "annotations.json";
    private static final int FORMAT_VERSION = 1;

    private static DocumentAnnotationManager instance;
    private final Context context;
    private final List<DocumentAnnotation> annotations = new ArrayList<>();

    private DocumentAnnotationManager(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    public static synchronized DocumentAnnotationManager getInstance(Context context) {
        if (instance == null) instance = new DocumentAnnotationManager(context);
        return instance;
    }

    public synchronized List<DocumentAnnotation> getForFile(String filePath) {
        List<DocumentAnnotation> result = new ArrayList<>();
        if (filePath == null) return result;
        for (DocumentAnnotation annotation : annotations) {
            if (filePath.equals(annotation.getFilePath())) result.add(annotation);
        }
        result.sort(Comparator
                .comparingInt(DocumentAnnotation::getStartPosition)
                .thenComparingLong(DocumentAnnotation::getCreatedAt));
        return result;
    }

    public synchronized boolean add(DocumentAnnotation annotation) {
        if (annotation == null) return false;
        if (annotation.isHighlight() && containsEquivalentHighlight(annotation)) {
            return false;
        }
        annotation.touch();
        annotations.add(annotation);
        save();
        return true;
    }

    private boolean containsEquivalentHighlight(DocumentAnnotation candidate) {
        for (DocumentAnnotation existing : annotations) {
            if (!existing.isHighlight()) continue;
            if (!candidate.getFilePath().equals(existing.getFilePath())) continue;
            if (!candidate.getDocumentType().equals(existing.getDocumentType())) continue;
            if (candidate.getStartPosition() != existing.getStartPosition()) continue;
            if (candidate.getEndPosition() != existing.getEndPosition()) continue;
            return true;
        }
        return false;
    }

    public synchronized void update(DocumentAnnotation annotation) {
        if (annotation == null) return;
        annotation.touch();
        for (int i = 0; i < annotations.size(); i++) {
            if (annotation.getId().equals(annotations.get(i).getId())) {
                annotations.set(i, annotation);
                save();
                return;
            }
        }
        annotations.add(annotation);
        save();
    }

    public synchronized void delete(String id) {
        if (id == null) return;
        Iterator<DocumentAnnotation> iterator = annotations.iterator();
        while (iterator.hasNext()) {
            if (id.equals(iterator.next().getId())) {
                iterator.remove();
                save();
                return;
            }
        }
    }

    public synchronized void moveFileReferences(String oldPath, String newPath) {
        if (oldPath == null || newPath == null || oldPath.equals(newPath)) return;
        boolean changed = false;
        String newName = new File(newPath).getName();
        for (DocumentAnnotation annotation : annotations) {
            if (oldPath.equals(annotation.getFilePath())) {
                annotation.setFilePath(newPath);
                annotation.setFileName(newName);
                annotation.touch();
                changed = true;
            }
        }
        if (changed) save();
    }

    public synchronized void movePathPrefixReferences(String oldRootPath, String newRootPath) {
        if (oldRootPath == null || newRootPath == null || oldRootPath.equals(newRootPath)) return;
        String oldPrefix = oldRootPath.endsWith(File.separator) ? oldRootPath : oldRootPath + File.separator;
        String newPrefix = newRootPath.endsWith(File.separator) ? newRootPath : newRootPath + File.separator;
        boolean changed = false;
        for (DocumentAnnotation annotation : annotations) {
            String path = annotation.getFilePath();
            String replacement = null;
            if (oldRootPath.equals(path)) replacement = newRootPath;
            else if (path != null && path.startsWith(oldPrefix)) {
                replacement = newPrefix + path.substring(oldPrefix.length());
            }
            if (replacement != null) {
                annotation.setFilePath(replacement);
                annotation.setFileName(new File(replacement).getName());
                annotation.touch();
                changed = true;
            }
        }
        if (changed) save();
    }

    public synchronized JSONArray exportJson() {
        JSONArray array = new JSONArray();
        for (DocumentAnnotation annotation : annotations) {
            try {
                array.put(annotation.toJson());
            } catch (Exception e) {
                Log.e(TAG, "Could not export annotation", e);
            }
        }
        return array;
    }

    public synchronized void importJson(JSONArray array, boolean merge) {
        if (array == null) return;
        if (!merge) annotations.clear();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            DocumentAnnotation incoming = DocumentAnnotation.fromJson(obj);
            int sameId = -1;
            for (int j = 0; j < annotations.size(); j++) {
                if (incoming.getId().equals(annotations.get(j).getId())) {
                    sameId = j;
                    break;
                }
            }
            if (sameId >= 0) annotations.set(sameId, incoming);
            else annotations.add(incoming);
        }
        removeDuplicateHighlights();
        save();
    }

    private void load() {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return;
        try {
            JSONObject root = new JSONObject(AtomicUtf8File.read(file));
            JSONArray array = root.optJSONArray("annotations");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) annotations.add(DocumentAnnotation.fromJson(obj));
            }
            if (removeDuplicateHighlights()) save();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load annotations", e);
        }
    }

    /** Removes exact duplicate highlight ranges left by older builds. */
    private boolean removeDuplicateHighlights() {
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        Iterator<DocumentAnnotation> iterator = annotations.iterator();
        while (iterator.hasNext()) {
            DocumentAnnotation annotation = iterator.next();
            if (!annotation.isHighlight()) continue;
            String key = annotation.getFilePath() + '\u0000'
                    + annotation.getDocumentType() + '\u0000'
                    + annotation.getStartPosition() + '\u0000'
                    + annotation.getEndPosition();
            if (!seen.add(key)) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            root.put("annotations", exportJson());
            AtomicUtf8File.write(new File(context.getFilesDir(), FILE_NAME), root.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save annotations", e);
        }
    }
}
