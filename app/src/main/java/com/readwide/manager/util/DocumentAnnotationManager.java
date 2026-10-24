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
    private List<DocumentAnnotation> annotations = new ArrayList<>();

    public static final class PersistenceException extends java.io.IOException {
        private static final long serialVersionUID = 1L;
        private PersistenceException(Exception cause) { super("Could not save annotations", cause); }
    }

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
            if (filePath.equals(annotation.getFilePath())) result.add(annotation.copy());
        }
        result.sort(Comparator
                .comparingInt(DocumentAnnotation::getStartPosition)
                .thenComparingLong(DocumentAnnotation::getCreatedAt));
        return result;
    }

    public synchronized boolean add(DocumentAnnotation annotation) throws PersistenceException {
        if (annotation == null) return false;
        if (annotation.isHighlight() && containsEquivalentHighlight(annotation)) {
            return false;
        }
        DocumentAnnotation stored = annotation.copy();
        stored.touch();
        List<DocumentAnnotation> planned = new ArrayList<>(annotations);
        planned.add(stored);
        commitEdit(planned);
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

    /** Edits an existing note without replacing its current source/path metadata. */
    public synchronized void update(DocumentAnnotation annotation) throws PersistenceException {
        if (annotation == null) return;
        List<DocumentAnnotation> planned = new ArrayList<>(annotations);
        for (int i = 0; i < planned.size(); i++) {
            if (annotation.getId().equals(planned.get(i).getId())) {
                // The note editor owns a detached snapshot. Keep coordinates
                // and any path relocation performed since that editor opened.
                DocumentAnnotation stored = planned.get(i).copy();
                stored.setNote(annotation.getNote());
                stored.touch();
                planned.set(i, stored);
                commitEdit(planned);
                return;
            }
        }
        DocumentAnnotation stored = annotation.copy();
        stored.touch();
        planned.add(stored);
        commitEdit(planned);
    }

    public synchronized void delete(String id) throws PersistenceException {
        if (id == null) return;
        List<DocumentAnnotation> planned = new ArrayList<>(annotations);
        Iterator<DocumentAnnotation> iterator = planned.iterator();
        while (iterator.hasNext()) {
            if (id.equals(iterator.next().getId())) {
                iterator.remove();
                commitEdit(planned);
                return;
            }
        }
    }

    private void commitEdit(List<DocumentAnnotation> planned) throws PersistenceException {
        try {
            writeImportAnnotations(planned);
        } catch (org.json.JSONException | java.io.IOException | RuntimeException failure) {
            throw new PersistenceException(failure);
        }
        // Publish only after durable success. The reference swap cannot allocate
        // or expose a partially copied list after the on-disk commit.
        annotations = planned;
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

    public synchronized JSONArray exportJson() throws org.json.JSONException {
        JSONArray array = new JSONArray();
        for (DocumentAnnotation annotation : annotations) {
            array.put(annotation.toJson());
        }
        return array;
    }

    public synchronized void importJson(JSONArray array, boolean merge)
            throws org.json.JSONException, java.io.IOException {
        if (array == null) return;
        List<DocumentAnnotation> imported = new ArrayList<>();
        for (int i = 0; i < array.length(); i++)
            imported.add(DocumentAnnotation.fromJson(array.getJSONObject(i)));
        replaceFromImport(planImport(imported, merge));
    }

    synchronized List<DocumentAnnotation> snapshotForImport() { return new ArrayList<>(annotations); }

    synchronized List<DocumentAnnotation> planImport(List<DocumentAnnotation> imported, boolean merge) {
        List<DocumentAnnotation> planned = IndexedBackupMerge.merge(
                merge ? annotations : java.util.Collections.emptyList(), imported, DocumentAnnotation::getId, null);
        removeDuplicateHighlights(planned);
        return planned;
    }

    synchronized void replaceFromImport(List<DocumentAnnotation> planned)
            throws org.json.JSONException, java.io.IOException {
        writeImportAnnotations(planned);
        annotations.clear(); annotations.addAll(planned);
    }

    synchronized void restoreAfterImportFailure(List<DocumentAnnotation> previous)
            throws org.json.JSONException, java.io.IOException {
        annotations.clear(); annotations.addAll(previous);
        writeImportAnnotations(previous);
    }

    private void writeImportAnnotations(List<DocumentAnnotation> planned)
            throws org.json.JSONException, java.io.IOException {
        JSONArray rows = new JSONArray();
        for (DocumentAnnotation annotation : planned) rows.put(annotation.toJson());
        JSONObject root = new JSONObject();
        root.put("version", FORMAT_VERSION);
        root.put("annotations", rows);
        AtomicUtf8File.write(new File(context.getFilesDir(), FILE_NAME), root.toString(2));
    }

    private void load() {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try {
            String saved = AtomicUtf8File.readIfPresent(file);
            if (saved == null) return;
            JSONObject root = new JSONObject(saved);
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
        return removeDuplicateHighlights(annotations);
    }

    private static boolean removeDuplicateHighlights(List<DocumentAnnotation> values) {
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        Iterator<DocumentAnnotation> iterator = values.iterator();
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
