package com.textview.reader.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Disposable TXT page/index cache bookkeeping.
 *
 * This manager must never delete bookmarks, reading history, or saved reading state.
 * It only tracks generated/derived cache files under app cache/txt_page_cache.
 */
public final class PageIndexCacheManager {
    private static final String CACHE_DIR = "txt_page_cache";
    private static final String INDEX_FILE = "index.json";

    private static final long MAX_CACHE_BYTES = 32L * 1024L * 1024L;
    private static final long STALE_AFTER_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int PROTECT_RECENT_COUNT = 10;

    private static PageIndexCacheManager instance;

    private final Context appContext;
    private final File cacheRoot;
    private final File indexFile;

    private PageIndexCacheManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.cacheRoot = new File(appContext.getCacheDir(), CACHE_DIR);
        this.indexFile = new File(cacheRoot, INDEX_FILE);
        if (!cacheRoot.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheRoot.mkdirs();
        }
    }

    public static synchronized PageIndexCacheManager getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new PageIndexCacheManager(context);
        }
        return instance;
    }

    /**
     * Record that a TXT file was opened with the current layout. This also runs
     * LFU/LRU cleanup for disposable page/index cache files.
     */
    public synchronized void recordFileAccess(@NonNull File file, @Nullable String layoutSignature) {
        if (!file.exists() || !file.isFile()) return;

        long now = System.currentTimeMillis();
        ArrayList<Entry> entries = readEntries();

        String key = keyForPath(file.getAbsolutePath());
        Entry target = null;
        for (Entry e : entries) {
            if (key.equals(e.key)) {
                target = e;
                break;
            }
        }

        if (target == null) {
            target = new Entry();
            target.key = key;
            target.accessCount = 0;
            entries.add(target);
        } else if (target.layoutSignature != null
                && layoutSignature != null
                && !target.layoutSignature.equals(layoutSignature)) {
            deleteEntryCache(target.key);
            target.accessCount = 0;
        }

        target.filePath = file.getAbsolutePath();
        target.fileLength = file.length();
        target.lastModified = file.lastModified();
        target.layoutSignature = layoutSignature != null ? layoutSignature : "";
        target.lastAccessAt = now;
        target.accessCount = Math.max(0, target.accessCount) + 1;
        target.cacheBytes = dirSize(entryDir(target.key));

        cleanupLocked(entries, now);
        writeEntries(entries);
    }

    /**
     * Future page-anchor/index code can use this directory for a file-specific
     * disposable cache. The directory is created only when actually requested.
     */
    @NonNull
    public synchronized File getEntryCacheDir(@NonNull File file, @Nullable String layoutSignature) {
        recordFileAccess(file, layoutSignature);
        File dir = entryDir(keyForPath(file.getAbsolutePath()));
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        ArrayList<Entry> entries = readEntries();
        cleanupLocked(entries, now);
        writeEntries(entries);
    }

    private void cleanupLocked(@NonNull ArrayList<Entry> entries, long now) {
        Set<String> knownKeys = new HashSet<>();
        ArrayList<Entry> valid = new ArrayList<>();

        for (Entry e : entries) {
            if (e == null || e.key == null || e.filePath == null) continue;

            File source = new File(e.filePath);
            boolean invalid = !source.exists()
                    || !source.isFile()
                    || source.length() != e.fileLength
                    || source.lastModified() != e.lastModified;

            if (invalid) {
                deleteEntryCache(e.key);
                continue;
            }

            e.cacheBytes = dirSize(entryDir(e.key));
            valid.add(e);
            knownKeys.add(e.key);
        }

        deleteOrphanCacheDirs(knownKeys);

        Collections.sort(valid, (a, b) -> Long.compare(b.lastAccessAt, a.lastAccessAt));
        Set<String> protectedKeys = new HashSet<>();
        for (int i = 0; i < valid.size() && i < PROTECT_RECENT_COUNT; i++) {
            protectedKeys.add(valid.get(i).key);
        }

        ArrayList<Entry> kept = new ArrayList<>();
        for (Entry e : valid) {
            boolean stale = now - e.lastAccessAt > STALE_AFTER_MS;
            if (stale && !protectedKeys.contains(e.key)) {
                deleteEntryCache(e.key);
            } else {
                kept.add(e);
            }
        }

        long totalBytes = 0L;
        for (Entry e : kept) {
            e.cacheBytes = dirSize(entryDir(e.key));
            totalBytes += e.cacheBytes;
        }

        if (totalBytes > MAX_CACHE_BYTES) {
            Collections.sort(kept, (a, b) -> {
                int count = Integer.compare(a.accessCount, b.accessCount);
                if (count != 0) return count;
                return Long.compare(a.lastAccessAt, b.lastAccessAt);
            });

            ArrayList<Entry> survivors = new ArrayList<>();
            for (Entry e : kept) {
                if (totalBytes > MAX_CACHE_BYTES && !protectedKeys.contains(e.key)) {
                    deleteEntryCache(e.key);
                    totalBytes -= Math.max(0L, e.cacheBytes);
                } else {
                    survivors.add(e);
                }
            }
            kept = survivors;
        }

        entries.clear();
        entries.addAll(kept);
    }

    private ArrayList<Entry> readEntries() {
        ArrayList<Entry> entries = new ArrayList<>();
        try {
            if (!indexFile.exists()) return entries;

            String json = readUtf8(indexFile);
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return entries;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.key = o.optString("key", "");
                e.filePath = o.optString("filePath", "");
                e.fileLength = o.optLong("fileLength", 0L);
                e.lastModified = o.optLong("lastModified", 0L);
                e.layoutSignature = o.optString("layoutSignature", "");
                e.totalPages = o.optInt("totalPages", 0);
                e.lastAccessAt = o.optLong("lastAccessAt", 0L);
                e.accessCount = o.optInt("accessCount", 0);
                e.cacheBytes = o.optLong("cacheBytes", 0L);
                if (!e.key.isEmpty() && !e.filePath.isEmpty()) entries.add(e);
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            entries.clear();
        }
        return entries;
    }

    private void writeEntries(@NonNull ArrayList<Entry> entries) {
        try {
            if (!cacheRoot.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheRoot.mkdirs();
            }

            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray arr = new JSONArray();

            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("key", e.key);
                o.put("filePath", e.filePath);
                o.put("fileLength", e.fileLength);
                o.put("lastModified", e.lastModified);
                o.put("layoutSignature", e.layoutSignature != null ? e.layoutSignature : "");
                o.put("totalPages", e.totalPages);
                o.put("lastAccessAt", e.lastAccessAt);
                o.put("accessCount", e.accessCount);
                o.put("cacheBytes", dirSize(entryDir(e.key)));
                arr.put(o);
            }

            root.put("entries", arr);

            File tmp = new File(cacheRoot, INDEX_FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (!tmp.renameTo(indexFile)) {
                try (FileOutputStream out = new FileOutputStream(indexFile)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
        }
    }

    private String readUtf8(@NonNull File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private File entryDir(@NonNull String key) {
        return new File(cacheRoot, key);
    }

    private void deleteEntryCache(@Nullable String key) {
        if (key == null || key.isEmpty()) return;
        deleteRecursive(entryDir(key));
    }

    private void deleteOrphanCacheDirs(@NonNull Set<String> knownKeys) {
        File[] children = cacheRoot.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child == null) continue;
            if (INDEX_FILE.equals(child.getName()) || (INDEX_FILE + ".tmp").equals(child.getName())) {
                continue;
            }
            if (child.isDirectory() && !knownKeys.contains(child.getName())) {
                deleteRecursive(child);
            }
        }
    }

    private long dirSize(@NonNull File file) {
        if (!file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());

        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child != null) total += dirSize(child);
            }
        }
        return total;
    }

    private void deleteRecursive(@NonNull File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null) deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private String keyForPath(@NonNull String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            final char[] hex = "0123456789abcdef".toCharArray();
            for (byte b : out) {
                int value = b & 0xFF;
                sb.append(hex[value >>> 4]);
                sb.append(hex[value & 0x0F]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | RuntimeException ignored) {
            return Integer.toHexString(path.hashCode());
        }
    }

    private static final class Entry {
        String key;
        String filePath;
        long fileLength;
        long lastModified;
        String layoutSignature;
        int totalPages;
        long lastAccessAt;
        int accessCount;
        long cacheBytes;
    }
}
