package com.readwide.manager.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextDisplayRuleManager {
    private static final String PREF_KEY = "txt_display_replacement_rules_json";
    public static final int MAX_RULES = 50;

    private TextDisplayRuleManager() {}

    private static SharedPreferences prefs(Context context) {
        return PrefsManager.getInstance(context).getPrefs();
    }

    private static final Object RULE_LOCK = new Object();
    private static List<TextDisplayRule> cachedRules;
    private static String cachedRaw;
    private static int rulesVersion;

    public static int getRulesVersion() {
        synchronized (RULE_LOCK) { return rulesVersion; }
    }

    public static List<TextDisplayRule> getRules(Context context) {
        if (context == null) return new ArrayList<>();
        return getRules(prefs(context));
    }

    static List<TextDisplayRule> getRules(SharedPreferences preferences) {
        synchronized (RULE_LOCK) {
            refreshLocked(preferences.getString(PREF_KEY, "[]"));
            return copyRules(cachedRules);
        }
    }

    /** A single owned snapshot ties the cursor version to the actual transform. */
    public static final class ActiveSnapshot {
        public final int version;
        public final CompiledRules compiled;
        private ActiveSnapshot(int version, List<TextDisplayRule> rules) {
            this.version = version;
            this.compiled = compile(rules);
        }
    }

    public static ActiveSnapshot captureActive(Context context, String filePath) {
        if (context == null) return new ActiveSnapshot(0, new ArrayList<>());
        return captureActive(prefs(context), filePath);
    }

    static ActiveSnapshot captureActive(SharedPreferences preferences, String filePath) {
        List<TextDisplayRule> active = new ArrayList<>();
        int version;
        synchronized (RULE_LOCK) {
            refreshLocked(preferences.getString(PREF_KEY, "[]"));
            version = rulesVersion;
            for (TextDisplayRule rule : cachedRules) if (rule.appliesTo(filePath)) active.add(rule.copy());
        }
        return new ActiveSnapshot(version, active);
    }

    /** Called after preference imports; raw-value matching also covers external edits. */
    public static void invalidateCache() {
        synchronized (RULE_LOCK) { cachedRules = null; cachedRaw = null; rulesVersion++; }
    }

    private static List<TextDisplayRule> copyRules(List<TextDisplayRule> rules) {
        List<TextDisplayRule> copies = new ArrayList<>(rules.size());
        for (TextDisplayRule rule : rules) copies.add(rule.copy());
        return copies;
    }

    private static void refreshLocked(String raw) {
        if (raw == null) raw = "[]";
        if (cachedRules != null && raw.equals(cachedRaw)) return;
        ArrayList<TextDisplayRule> rules = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && rules.size() < MAX_RULES; i++) {
                TextDisplayRule rule = TextDisplayRule.fromJson(arr.optJSONObject(i));
                if (rule.isValid()) rules.add(rule);
            }
        } catch (Exception ignored) {
            // Broken user-edited JSON should not break opening TXT files.
        }
        cachedRules = rules;
        cachedRaw = raw;
        rulesVersion++;
    }

    public static List<TextDisplayRule> getActiveRules(Context context, String filePath) {
        ArrayList<TextDisplayRule> active = new ArrayList<>();
        for (TextDisplayRule rule : getRules(context)) {
            if (rule.appliesTo(filePath)) active.add(rule);
        }
        return active;
    }

    public static void saveRules(Context context, List<TextDisplayRule> rules) {
        if (context == null) return;
        JSONArray arr = new JSONArray();
        if (rules != null) {
            for (TextDisplayRule rule : rules) {
                if (rule == null || !rule.isValid()) continue;
                try {
                    arr.put(rule.toJson());
                    if (arr.length() >= MAX_RULES) break;
                } catch (JSONException ignored) {
                }
            }
        }
        // apply() updates SharedPreferences memory immediately and writes to disk in the
        // background, so rule windows can respond without blocking on synchronous I/O.
        SharedPreferences preferences = prefs(context);
        synchronized (RULE_LOCK) {
            preferences.edit().putString(PREF_KEY, arr.toString()).apply();
            cachedRules = null; cachedRaw = null; rulesVersion++;
        }
    }

    public static String getSignature(Context context, String filePath) {
        List<TextDisplayRule> active = getActiveRules(context, filePath);
        if (active.isEmpty()) return "none";
        JSONArray arr = new JSONArray();
        for (TextDisplayRule rule : active) {
            try {
                arr.put(rule.toJson());
            } catch (JSONException ignored) {
            }
        }
        return Integer.toHexString(arr.toString().hashCode()) + ":" + arr.length();
    }

    public static String apply(Context context, String text, String filePath) {
        if (text == null || text.isEmpty()) return text != null ? text : "";
        return apply(text, getActiveRules(context, filePath));
    }

    public static String apply(String text, List<TextDisplayRule> rules) {
        if (text == null || text.isEmpty() || rules == null || rules.isEmpty()) {
            return text != null ? text : "";
        }
        return apply(text, compile(rules));
    }

    /**
     * A set of display rules with their regex patterns compiled once. Build this
     * outside a per-line loop with {@link #compile(List)} and reuse it for every
     * line via {@link #apply(String, CompiledRules)}, instead of recompiling each
     * rule's pattern on every call.
     */
    public static final class CompiledRules {
        private final CompiledRule[] rules;

        private CompiledRules(CompiledRule[] rules) {
            this.rules = rules;
        }

        boolean isEmpty() {
            return rules.length == 0;
        }
    }

    private static final class CompiledRule {
        final Pattern pattern;          // regex or quoted case-insensitive literal
        final String find;              // case-sensitive literal only
        final String replacement;
        volatile boolean invalidReplacement;

        CompiledRule(Pattern pattern, String find, String replacement) {
            this.pattern = pattern;
            this.find = find;
            this.replacement = replacement;
        }
    }

    /**
     * Compile a rule list once. Invalid regex rules are skipped here rather than
     * per-line. This matches the previous fail-safe behavior: a pattern that
     * fails to compile fails identically for every line, so skipping it once at
     * compile time produces the same output as skipping it on each line.
     */
    public static CompiledRules compile(List<TextDisplayRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return new CompiledRules(new CompiledRule[0]);
        }
        ArrayList<CompiledRule> out = new ArrayList<>(rules.size());
        for (TextDisplayRule rule : rules) {
            if (rule == null || !rule.enabled || !rule.isValid()) continue;
            String repl = rule.replacementText != null ? rule.replacementText : "";
            if (rule.useRegex) {
                if (rule.findText == null || rule.findText.isEmpty()) continue;
                try {
                    int flags = Pattern.MULTILINE;
                    if (!rule.caseSensitive) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    Pattern pattern = Pattern.compile(rule.findText, flags);
                    if (!validReplacementSyntax(repl, pattern.matcher("").groupCount())) continue;
                    out.add(new CompiledRule(pattern, null, repl));
                } catch (IllegalArgumentException ex) {
                    // Bad user regex: skip this rule rather than break file loading.
                }
            } else {
                if (rule.findText == null || rule.findText.isEmpty()) continue;
                // Lowercasing a whole string can change its UTF-16 length (e.g. İ),
                // making offsets into the original string invalid. Match in place.
                if (rule.caseSensitive) out.add(new CompiledRule(null, rule.findText, repl));
                else out.add(new CompiledRule(Pattern.compile(Pattern.quote(rule.findText),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), null,
                        Matcher.quoteReplacement(repl)));
            }
        }
        return new CompiledRules(out.toArray(new CompiledRule[0]));
    }

    /** Validate even disabled drafts using the same compiler as the reader. */
    public static boolean isValidExpression(TextDisplayRule rule) {
        if (rule == null || !rule.isValid()) return false;
        TextDisplayRule draft = rule.copy();
        draft.enabled = true;
        return !compile(java.util.Collections.singletonList(draft)).isEmpty();
    }

    /** Apply pre-compiled rules to one piece of text, reusing compiled patterns. */
    public static String apply(String text, CompiledRules compiled) {
        if (text == null || text.isEmpty() || compiled == null || compiled.isEmpty()) {
            return text != null ? text : "";
        }
        String result = text;
        for (CompiledRule rule : compiled.rules) {
            if (rule.invalidReplacement) continue;
            if (rule.pattern != null) {
                try {
                    Matcher matcher = rule.pattern.matcher(result);
                    result = matcher.replaceAll(rule.replacement);
                } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
                    // Missing named groups are discovered on the first actual match.
                    // Skip the rule for the rest of this snapshot, not an exception per line.
                    rule.invalidReplacement = true;
                }
            } else {
                result = result.replace(rule.find, rule.replacement);
            }
        }
        return result;
    }

    private static boolean validReplacementSyntax(String replacement, int groups) {
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (++i == replacement.length()) return false;
            } else if (c == '$') {
                if (++i == replacement.length()) return false;
                c = replacement.charAt(i);
                if (c == '{') {
                    int start = ++i;
                    if (i == replacement.length() || !asciiLetter(replacement.charAt(i))) return false;
                    while (i < replacement.length() && (asciiLetter(replacement.charAt(i))
                            || asciiDigit(replacement.charAt(i)))) i++;
                    if (i == start || i == replacement.length() || replacement.charAt(i) != '}') return false;
                } else {
                    if (!asciiDigit(c) || c - '0' > groups) return false;
                    int group = c - '0';
                    while (i + 1 < replacement.length() && asciiDigit(replacement.charAt(i + 1))) {
                        long next = (long) group * 10 + replacement.charAt(i + 1) - '0';
                        if (next > groups) break; // Remaining digits are literal ($12 with one group).
                        group = (int) next;
                        i++;
                    }
                }
            }
        }
        return true;
    }

    private static boolean asciiDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean asciiLetter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
}
