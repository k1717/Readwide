package com.readwide.manager.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;

/** On-demand contents I/O. All methods run on a worker, never the reader/UI thread. */
public final class TxtContentsRepository {
    // Backup snapshot/commit shares the same lock as ordinary durable edits.
    static final Object EDIT_LOCK = new Object();
    private static final Object CACHE_LOCK = new Object();
    private final File cacheRoot, editsRoot, optionsRoot;

    public TxtContentsRepository(Context context) {
        cacheRoot = new File(context.getCacheDir(), "txt-contents");
        editsRoot = new File(context.getFilesDir(), "txt-contents");
        optionsRoot = new File(context.getFilesDir(), "txt-contents-options");
    }

    public static final class Result {
        public final String path, version;
        public final int characters;
        public final List<TxtContentsScanner.Entry> entries;
        public final boolean previousEdits, cacheUnavailable;
        public final TxtContentsOptions options;
        private final List<TxtContentsScanner.Entry> automatic;

        private Result(String path, String version, int characters, List<TxtContentsScanner.Entry> automatic,
                       Edits edits, boolean previousEdits, boolean cacheUnavailable, TxtContentsOptions options) {
            this.path = path;
            this.version = version;
            this.characters = characters;
            this.automatic = automatic;
            this.previousEdits = previousEdits;
            this.cacheUnavailable = cacheUnavailable;
            this.options = options;
            entries = new ArrayList<>();
            for (TxtContentsScanner.Entry e : automatic) if (!edits.hidden.contains(e.id)) entries.add(e);
            entries.addAll(edits.manual);
            entries.sort(Comparator.comparingInt(e -> e.position));
        }
    }

    private static final class Edits {
        final Set<String> hidden = new HashSet<>();
        final List<TxtContentsScanner.Entry> manual = new ArrayList<>();
    }

    public Result loadText(String path, String text, boolean force,
                           LargeTextPageIndexCache.Checkpoint check) throws IOException {
        TxtContentsOptions options = loadOptions(path);
        String version = "text-" + textDigest(text, check);
        List<TxtContentsScanner.Entry> entries = force ? null : cached(path, version, text.length(), options);
        boolean cacheUnavailable = false;
        if (entries == null) {
            TxtContentsScanner scanner = new TxtContentsScanner(options);
            for (int start = 0; start < text.length();) {
                check.check();
                int end = start + Math.min(4096, text.length() - start);
                scanner.append(text.subSequence(start, end));
                start = end;
            }
            entries = scanner.finish();
            check.check();
            cacheUnavailable = !cache(path, version, text.length(), entries, options);
        }
        check.check();
        return result(path, version, text.length(), entries, cacheUnavailable, options);
    }

    public Result loadLarge(File file, String encoding, String transform,
                            TextDisplayRuleManager.CompiledRules rules, boolean collapse,
                            long expectedLength, long expectedModified, boolean force,
                            LargeTextPageIndexCache.Checkpoint check) throws IOException {
        String path = file.getAbsolutePath();
        TxtContentsOptions options = loadOptions(path);
        verifySource(file, expectedLength, expectedModified);
        byte[] sourceDigest = LargeTextPageIndexCache.fingerprint(file, check);
        // Include decoding/transform policy, but deliberately exclude every page-layout input.
        String version = "large-" + textDigest(hex(sourceDigest) + "\n" + encoding + "\n"
                + transform + "\n" + collapse + "\nnormalized-lines-v1", check);
        Cached saved = force ? null : readCache(path, version, options);
        if (saved != null) {
            verifySource(file, expectedLength, expectedModified);
            check.check();
            return result(path, version, saved.characters, saved.entries, false, options);
        }
        TxtContentsScanner scanner = new TxtContentsScanner(options);
        TxtBlankLineCollapser.Filter filter = new TxtBlankLineCollapser.Filter(collapse);
        long characters = 0;
        try (BufferedReader reader = LargeTextPartitionReader.openReader(file, encoding)) {
            String line;
            while ((line = reader.readLine()) != null) {
                check.check();
                String normalized = TextDisplayRuleManager.apply(FileUtils.enforceTextPresentationSelectors(line), rules);
                String emitted = filter.accept(normalized);
                if (emitted == null) continue;
                characters += (long) emitted.length() + 1;
                if (characters > Integer.MAX_VALUE) throw new IOException("TXT position is not representable by the reader");
                // Rule output may contain newlines. Feed the exact displayed characters, not one synthetic heading per source line.
                for (int start = 0; start < emitted.length();) {
                    check.check();
                    int end = start + Math.min(4096, emitted.length() - start);
                    scanner.append(emitted.subSequence(start, end));
                    start = end;
                }
                scanner.append("\n");
            }
        }
        List<TxtContentsScanner.Entry> entries = scanner.finish();
        verifySource(file, expectedLength, expectedModified);
        if (!MessageDigest.isEqual(sourceDigest, LargeTextPageIndexCache.fingerprint(file, check))) {
            throw new IOException("TXT changed while indexing contents");
        }
        check.check();
        boolean cacheUnavailable = !cache(path, version, (int) characters, entries, options);
        return result(path, version, (int) characters, entries, cacheUnavailable, options);
    }

    public TxtContentsOptions loadOptions(String path) throws IOException {
        synchronized (EDIT_LOCK) {
            String raw = AtomicUtf8File.readIfPresent(optionsFile(path));
            if (raw == null) return TxtContentsOptions.DEFAULT;
            try {
                JSONObject json = new JSONObject(raw);
                return decodeOptions(json);
            } catch (JSONException | IllegalArgumentException invalid) {
                throw new IOException("Cannot read contents options", invalid);
            }
        }
    }

    /** Validate custom expressions on the caller's worker before replacing any saved options. */
    public void saveOptions(String path, TxtContentsOptions options) throws IOException {
        options.compileCustomPatterns();
        synchronized (EDIT_LOCK) {
            try {
                JSONObject json = encodeOptions(options);
                ensureDirectory(optionsRoot);
                AtomicUtf8File.write(optionsFile(path), json.toString());
            } catch (JSONException invalid) { throw new IOException(invalid); }
        }
    }

    private File optionsFile(String path) throws IOException {
        return new File(optionsRoot, textDigest(path, () -> {}) + ".json");
    }

    private static void verifySource(File file, long length, long modified) throws IOException {
        if (!file.isFile() || length < 0 || file.length() != length || file.lastModified() != modified) {
            throw new IOException("TXT source changed; reopen the document");
        }
    }

    /** Read-modify-write under one process-wide lock prevents competing reader windows losing edits. */
    public Result edit(Result current, TxtContentsScanner.Entry add, String removeId, boolean restoreHidden) throws IOException {
        synchronized (EDIT_LOCK) {
            File file = editsFile(current.path, current.version);
            Edits edits = readEdits(file, current.characters);
            if (restoreHidden) edits.hidden.clear();
            if (removeId != null) {
                if (removeId.startsWith("auto:")) edits.hidden.add(removeId);
                else edits.manual.removeIf(e -> e.id.equals(removeId));
            }
            if (add != null) {
                validate(add, current.characters);
                if (!add.manual) throw new IOException("Expected manual contents entry");
                edits.manual.add(add);
            }
            try {
                JSONObject json = encodeEdits(edits);
                ensureDirectory(file.getParentFile());
                AtomicUtf8File.write(file, json.toString());
            } catch (JSONException invalid) { throw new IOException(invalid); }
            return new Result(current.path, current.version, current.characters, current.automatic, edits,
                    false, current.cacheUnavailable, current.options);
        }
    }

    private Result result(String path, String version, int characters, List<TxtContentsScanner.Entry> automatic,
                          boolean cacheUnavailable, TxtContentsOptions options) throws IOException {
        synchronized (EDIT_LOCK) {
            File file = editsFile(path, version);
            Edits edits = readEdits(file, characters); // A damaged durable store is an error, never silently replaced.
            File[] previous = file.getParentFile().listFiles((dir, name) -> name.endsWith(".json"));
            return new Result(path, version, characters, automatic, edits,
                    !file.exists() && previous != null && previous.length > 0, cacheUnavailable, options);
        }
    }

    private File editsFile(String path, String version) throws IOException {
        return new File(new File(editsRoot, textDigest(path, () -> {})), version + ".json");
    }

    private Edits readEdits(File file, int characters) throws IOException {
        String raw = AtomicUtf8File.readIfPresent(file);
        Edits edits = new Edits();
        if (raw == null) return edits;
        try {
            return decodeEdits(new JSONObject(raw), characters);
        } catch (JSONException invalid) { throw new IOException("Cannot read contents edits", invalid); }
    }

    private static TxtContentsOptions decodeOptions(JSONObject json) throws IOException, JSONException {
        if (exactInt(json, "schema") != 1) throw new IOException("Unknown contents options schema");
        try {
            return new TxtContentsOptions(exactInt(json, "formats"),
                    bool(json, "blank"), bool(json, "repeated"), bool(json, "reduceProse"),
                    bool(json, "combineTitle"), string(json, "custom"));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid contents options", invalid); }
    }

    private static JSONObject encodeOptions(TxtContentsOptions options) throws JSONException {
        return new JSONObject().put("schema", 1).put("formats", options.formats)
                .put("blank", options.requireBlank).put("repeated", options.requireRepeated)
                .put("reduceProse", options.reduceProse).put("combineTitle", options.combineTitle)
                .put("custom", options.customRules);
    }

    private static Edits decodeEdits(JSONObject json, int characters) throws IOException, JSONException {
        if (exactInt(json, "schema") != 1) throw new IOException("Unknown contents edit schema");
        Edits edits = new Edits();
        edits.manual.addAll(decode(json.getJSONArray("manual"), characters, true));
        JSONArray hidden = json.getJSONArray("hidden");
        for (int i = 0; i < hidden.length(); i++) {
            Object raw = hidden.get(i);
            if (!(raw instanceof String) || !((String) raw).matches("auto:(0|[1-9][0-9]{0,9})"))
                throw new IOException("Invalid hidden heading");
            String id = (String) raw;
            if (Long.parseLong(id.substring(5)) > characters) throw new IOException("Invalid hidden heading position");
            edits.hidden.add(id);
        }
        return edits;
    }

    private static JSONObject encodeEdits(Edits edits) throws JSONException {
        JSONObject json = new JSONObject().put("schema", 1).put("manual", encode(edits.manual));
        JSONArray hidden = new JSONArray();
        for (String id : new java.util.TreeSet<>(edits.hidden)) hidden.put(id);
        return json.put("hidden", hidden);
    }

    /** The importer has no source file to read; actual length is checked again when opened. */
    static String validateBackupRecord(boolean options, JSONObject json) throws IOException, JSONException {
        if (!options) return encodeEdits(decodeEdits(json, Integer.MAX_VALUE)).toString();
        TxtContentsOptions decoded = decodeOptions(json);
        try { decoded.compileCustomPatterns(); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid contents expression", invalid); }
        return encodeOptions(decoded).toString();
    }

    static String mergeBackupEdits(String previous, String incoming) throws IOException, JSONException {
        Edits old = decodeEdits(new JSONObject(previous), Integer.MAX_VALUE);
        Edits added = decodeEdits(new JSONObject(incoming), Integer.MAX_VALUE);
        LinkedHashMap<String, TxtContentsScanner.Entry> manual = new LinkedHashMap<>();
        for (TxtContentsScanner.Entry entry : old.manual) manual.put(entry.id, entry);
        for (TxtContentsScanner.Entry entry : added.manual) manual.put(entry.id, entry);
        old.manual.clear(); old.manual.addAll(manual.values());
        old.hidden.addAll(added.hidden);
        return encodeEdits(old).toString();
    }

    private static int exactInt(JSONObject json, String key) throws IOException, JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof Number)) throw new IOException("Invalid contents integer: " + key);
        try { return new BigDecimal(raw.toString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IOException("Invalid contents integer: " + key, invalid); }
    }

    private static String string(JSONObject json, String key) throws IOException, JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof String)) throw new IOException("Invalid contents string: " + key);
        return (String) raw;
    }

    private static boolean bool(JSONObject json, String key) throws IOException, JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof Boolean)) throw new IOException("Invalid contents flag: " + key);
        return (Boolean) raw;
    }

    private static final class Cached {
        final int characters;
        final List<TxtContentsScanner.Entry> entries;
        Cached(int characters, List<TxtContentsScanner.Entry> entries) {
            this.characters = characters;
            this.entries = entries;
        }
    }

    private List<TxtContentsScanner.Entry> cached(String path, String version, int characters, TxtContentsOptions options) {
        Cached saved = readCache(path, version, options);
        return saved != null && saved.characters == characters ? saved.entries : null;
    }

    private Cached readCache(String path, String version, TxtContentsOptions options) {
        synchronized (CACHE_LOCK) {
            try {
                File file = new File(cacheRoot, textDigest(path, () -> {}) + ".json");
                String raw = AtomicUtf8File.readIfPresent(file);
                if (raw == null) return null;
                JSONObject json = new JSONObject(raw);
                if (json.getInt("revision") != TxtContentsScanner.REVISION || !version.equals(json.getString("version"))) return null;
                if (!options.cacheKey().equals(json.getString("options"))) return null;
                int characters = json.getInt("characters");
                if (characters < 0) return null;
                return new Cached(characters, decode(json.getJSONArray("entries"), characters, false));
            } catch (IOException | JSONException | RuntimeException invalid) { return null; }
        }
    }

    private boolean cache(String path, String version, int characters, List<TxtContentsScanner.Entry> entries,
                          TxtContentsOptions options) {
        synchronized (CACHE_LOCK) {
            try {
                ensureDirectory(cacheRoot);
                JSONObject json = new JSONObject().put("revision", TxtContentsScanner.REVISION)
                        .put("options", options.cacheKey())
                        .put("version", version).put("characters", characters).put("entries", encode(entries));
                AtomicUtf8File.write(new File(cacheRoot, textDigest(path, () -> {}) + ".json"), json.toString());
                return true;
            } catch (IOException | JSONException | RuntimeException unavailable) { return false; }
        }
    }

    private static JSONArray encode(List<TxtContentsScanner.Entry> entries) throws JSONException {
        JSONArray result = new JSONArray();
        for (TxtContentsScanner.Entry e : entries) result.put(new JSONObject().put("id", e.id).put("title", e.title)
                .put("position", e.position).put("level", e.level).put("before", e.before).put("after", e.after));
        return result;
    }

    private static List<TxtContentsScanner.Entry> decode(JSONArray array, int characters, boolean manual) throws IOException, JSONException {
        List<TxtContentsScanner.Entry> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int previous = -1;
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.getJSONObject(i);
            TxtContentsScanner.Entry entry = new TxtContentsScanner.Entry(string(json, "id"), string(json, "title"),
                    exactInt(json, "position"), exactInt(json, "level"), string(json, "before"), string(json, "after"), manual);
            validate(entry, characters);
            if (!ids.add(entry.id) || (!manual && (entry.position <= previous || !entry.id.equals("auto:" + entry.position)))
                    || (manual && !entry.id.startsWith("manual:"))) throw new IOException("Invalid contents identity/order");
            previous = entry.position;
            result.add(entry);
        }
        return result;
    }

    private static void validate(TxtContentsScanner.Entry e, int characters) throws IOException {
        if (e.position < 0 || e.position > characters || e.title.trim().isEmpty() || e.title.length() > TxtContentsScanner.MAX_TITLE
                || e.level < 0 || e.level > 1 || e.before.length() > 81 || e.after.length() > 121 || e.id.length() > 64) {
            throw new IOException("Invalid contents entry");
        }
    }

    private static void ensureDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create contents directory");
    }

    private static String textDigest(String text, LargeTextPageIndexCache.Checkpoint check) throws IOException {
        MessageDigest digest = LargeTextPageIndexCache.newDigest();
        byte[] bytes = new byte[8192];
        for (int start = 0; start < text.length();) {
            check.check();
            int count = Math.min(4096, text.length() - start);
            for (int i = 0; i < count; i++) {
                char ch = text.charAt(start + i);
                bytes[2 * i] = (byte) (ch >>> 8);
                bytes[2 * i + 1] = (byte) ch;
            }
            digest.update(bytes, 0, count * 2);
            start += count;
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] digest) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(alphabet[(value & 255) >>> 4]).append(alphabet[value & 15]);
        return result.toString();
    }
}
