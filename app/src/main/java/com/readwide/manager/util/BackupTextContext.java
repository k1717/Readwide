package com.readwide.manager.util;

import com.readwide.manager.ReaderActivity;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Owned import policy: resolve edited locations under the settings being restored,
 * without installing those settings before the backup transaction succeeds. */
final class BackupTextContext {
    private final Map<String, ?> settings;
    private File cachedFile;
    private long cachedLength, cachedModified;
    private String cachedText;

    BackupTextContext(Map<String, ?> settings) { this.settings = new java.util.HashMap<>(settings); }

    String read(File file) throws IOException, JSONException {
        long length = file.length(), modified = file.lastModified();
        if (file.equals(cachedFile) && length == cachedLength && modified == cachedModified) return cachedText;
        cachedFile = null;
        cachedText = null; // Retain at most one file, not all books in the backup.
        String suffix = file.getAbsolutePath() + "::" + length + "::" + modified;
        String encoding = FileUtils.normalizeManualEncodingName(string("manual_text_encoding::" + suffix));
        if (encoding == null) encoding = FileUtils.normalizeManualEncodingName(string("auto_text_encoding::" + suffix));
        if (encoding == null) encoding = FileUtils.detectEncoding(file);
        List<TextDisplayRule> rules = new ArrayList<>();
        String raw = string("txt_display_replacement_rules_json");
        JSONArray rows = new JSONArray(raw == null ? "[]" : raw);
        for (int i = 0; i < rows.length() && rules.size() < TextDisplayRuleManager.MAX_RULES; i++) {
            TextDisplayRule rule = TextDisplayRule.fromJson(rows.optJSONObject(i));
            if (rule.isValid()) rules.add(rule);
        }
        rules.removeIf(rule -> !rule.appliesTo(file.getAbsolutePath()));
        TextDisplayRuleManager.CompiledRules compiled = TextDisplayRuleManager.compile(rules);
        boolean collapse = Boolean.TRUE.equals(settings.get("txt_collapse_blank_lines"));
        String text;
        if (length >= ReaderActivity.LARGE_TEXT_FAST_OPEN_THRESHOLD_BYTES && FileUtils.isTextFile(file.getName())) {
            StringBuilder out = new StringBuilder();
            boolean first = true;
            TxtBlankLineCollapser.Filter filter = new TxtBlankLineCollapser.Filter(collapse);
            try (BufferedReader reader = LargeTextPartitionReader.openReader(file, encoding)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    String emitted = filter.accept(TextDisplayRuleManager.apply(
                            FileUtils.enforceTextPresentationSelectors(line), compiled));
                    if (emitted == null) continue;
                    if ((long) out.length() + emitted.length() + (first ? 0 : 1) > Integer.MAX_VALUE)
                        throw new IOException("TXT position is not representable by the reader");
                    if (!first) out.append('\n');
                    first = false;
                    out.append(emitted);
                }
            }
            text = out.toString();
        } else {
            text = TextDisplayRuleManager.apply(FileUtils.normalizeTextForDisplay(
                    FileUtils.readTextFile(file, encoding)), compiled);
            if (collapse) text = TxtBlankLineCollapser.collapse(text);
        }
        if (!file.isFile() || file.length() != length || file.lastModified() != modified)
            throw new IOException("TXT changed while resolving backup locations");
        cachedFile = file;
        cachedLength = length;
        cachedModified = modified;
        cachedText = text;
        return text;
    }

    private String string(String key) {
        Object value = settings.get(key);
        return value instanceof String ? (String) value : null;
    }
}
