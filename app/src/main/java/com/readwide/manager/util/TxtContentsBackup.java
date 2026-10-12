package com.readwide.manager.util;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Durable contents records only: no auto-index caches, source files or arbitrary paths. */
final class TxtContentsBackup {
    private static final String HASH = "[0-9a-f]{64}";
    private static final String VERSION = "(?:text|large)-" + HASH;
    private final File files;

    TxtContentsBackup(Context context) throws IOException {
        files = context.getFilesDir().getCanonicalFile();
    }

    /** Validate the complete optional section before any import preparation/commit. */
    static Map<String, String> parse(JSONObject section) throws JSONException {
        try {
            Object version = section.get("version");
            if (!(version instanceof Number) || ((Number) version).doubleValue() != 1.0)
                throw new IOException("Unknown TXT contents backup version");
            Map<String, String> records = new TreeMap<>();
            JSONObject options = section.getJSONObject("options");
            Iterator<String> paths = options.keys();
            while (paths.hasNext()) {
                String path = paths.next();
                require(path, HASH);
                records.put("options/" + path,
                        TxtContentsRepository.validateBackupRecord(true, options.getJSONObject(path)));
            }
            JSONObject edits = section.getJSONObject("edits");
            paths = edits.keys();
            while (paths.hasNext()) {
                String path = paths.next();
                require(path, HASH);
                JSONObject versions = edits.getJSONObject(path);
                Iterator<String> keys = versions.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    require(key, VERSION);
                    records.put("edits/" + path + "/" + key,
                            TxtContentsRepository.validateBackupRecord(false, versions.getJSONObject(key)));
                }
            }
            return records;
        } catch (IOException invalid) {
            throw new JSONException("Invalid TXT contents backup: " + invalid.getMessage());
        }
    }

    JSONObject exportJson() throws IOException, JSONException {
        synchronized (TxtContentsRepository.EDIT_LOCK) {
            JSONObject options = new JSONObject(), edits = new JSONObject();
            for (Map.Entry<String, String> entry : snapshot().entrySet()) {
                String[] parts = entry.getKey().split("/");
                boolean option = parts[0].equals("options");
                JSONObject data = new JSONObject(TxtContentsRepository.validateBackupRecord(option,
                        new JSONObject(entry.getValue())));
                if (option) options.put(parts[1], data);
                else {
                    JSONObject versions = edits.optJSONObject(parts[1]);
                    if (versions == null) { versions = new JSONObject(); edits.put(parts[1], versions); }
                    versions.put(parts[2], data);
                }
            }
            return new JSONObject().put("version", 1).put("options", options).put("edits", edits);
        }
    }

    /** Caller holds EDIT_LOCK through the enclosing transaction, including rollback. */
    void appendImportSteps(List<BackupImportTransaction.Step> steps, Map<String, String> incoming,
                           boolean merge) throws IOException, JSONException {
        if (!Thread.holdsLock(TxtContentsRepository.EDIT_LOCK)) throw new IllegalStateException("Contents lock required");
        Map<String, String> previous = snapshot();
        // A damaged existing durable record is not silently discarded by replacement.
        for (Map.Entry<String, String> entry : previous.entrySet())
            TxtContentsRepository.validateBackupRecord(entry.getKey().startsWith("options/"),
                    new JSONObject(entry.getValue()));
        Map<String, String> planned = new TreeMap<>();
        if (merge) planned.putAll(previous);
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String value = TxtContentsRepository.validateBackupRecord(key.startsWith("options/"),
                    new JSONObject(entry.getValue()));
            target(key, false); // Validate even if a future caller bypasses parse().
            if (merge && key.startsWith("edits/") && previous.containsKey(key))
                value = TxtContentsRepository.mergeBackupEdits(previous.get(key), value);
            planned.put(key, value);
        }
        TreeSet<String> keys = new TreeSet<>(previous.keySet());
        keys.addAll(planned.keySet());
        for (String key : keys) {
            String before = previous.get(key), after = planned.get(key);
            if (java.util.Objects.equals(before, after)) continue;
            steps.add(BackupImportTransaction.step(() -> write(key, after), () -> write(key, before)));
        }
    }

    private Map<String, String> snapshot() throws IOException {
        Map<String, String> result = new TreeMap<>();
        File options = new File(files, "txt-contents-options");
        for (String name : recordNames(options)) {
            require(name, HASH);
            read(result, "options/" + name);
        }
        for (File directory : children(new File(files, "txt-contents"))) {
            require(directory.getName(), HASH);
            for (String name : recordNames(directory)) {
                require(name, VERSION);
                read(result, "edits/" + directory.getName() + "/" + name);
            }
        }
        return result;
    }

    private void read(Map<String, String> records, String key) throws IOException {
        String value = AtomicUtf8File.readIfPresent(target(key, false));
        if (value != null) records.put(key, value);
    }

    /** Include recovery-only AtomicFile records, deduplicating base/.bak/.new names. */
    private static TreeSet<String> recordNames(File directory) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        for (File entry : children(directory)) {
            if (FileTreeWalk.kind(entry) != FileTreeWalk.Kind.FILE) throw new IOException("Unsafe contents record");
            String name = entry.getName();
            if (name.endsWith(".bak") || name.endsWith(".new")) name = name.substring(0, name.length() - 4);
            if (!name.endsWith(".json")) throw new IOException("Unknown contents record");
            names.add(name.substring(0, name.length() - 5));
        }
        return names;
    }

    private static File[] children(File directory) throws IOException {
        FileTreeWalk.Kind kind = FileTreeWalk.kind(directory);
        if (kind == FileTreeWalk.Kind.MISSING) return new File[0];
        if (kind != FileTreeWalk.Kind.DIRECTORY) throw new IOException("Unsafe contents directory");
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot list contents directory");
        return entries;
    }

    private static void require(String value, String pattern) throws IOException {
        if (!value.matches(pattern)) throw new IOException("Invalid contents storage key");
    }

    private File target(String key, boolean create) throws IOException {
        String[] parts = key.split("/", -1);
        boolean option = parts.length == 2 && parts[0].equals("options");
        boolean edit = parts.length == 3 && parts[0].equals("edits");
        if (!option && !edit) throw new IOException("Invalid contents storage key");
        require(parts[1], HASH);
        if (edit) require(parts[2], VERSION);
        directory(files, false);
        File parent = new File(files, option ? "txt-contents-options" : "txt-contents");
        directory(parent, create);
        if (edit) { parent = new File(parent, parts[1]); directory(parent, create); }
        File file = new File(parent, parts[option ? 1 : 2] + ".json");
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            FileTreeWalk.Kind kind = FileTreeWalk.kind(new File(file.getPath() + suffix));
            if (kind != FileTreeWalk.Kind.FILE && kind != FileTreeWalk.Kind.MISSING)
                throw new IOException("Unsafe contents target");
        }
        return file;
    }

    private static void directory(File file, boolean create) throws IOException {
        FileTreeWalk.Kind kind = FileTreeWalk.kind(file);
        if (kind == FileTreeWalk.Kind.MISSING && create) {
            if (!file.mkdir()) throw new IOException("Cannot create contents directory");
            kind = FileTreeWalk.kind(file);
        }
        if (kind != FileTreeWalk.Kind.DIRECTORY && kind != FileTreeWalk.Kind.MISSING)
            throw new IOException("Unsafe contents directory");
    }

    private void write(String key, String value) throws IOException {
        File file = target(key, value != null);
        if (value != null) { AtomicUtf8File.write(file, value); return; }
        // Only validated app-owned record names; never recursively delete a directory.
        for (String suffix : new String[]{".new", ".bak", ""}) {
            File part = new File(file.getPath() + suffix);
            if (FileTreeWalk.kind(part) != FileTreeWalk.Kind.MISSING && !part.delete())
                throw new IOException("Cannot remove contents record during restoration");
        }
    }
}
