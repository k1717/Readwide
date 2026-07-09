package com.readwide.manager.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextDisplayRuleManager {
    private static final String PREF_KEY = "txt_display_replacement_rules_json";
    private static final int MAX_RULES = 50;

    private TextDisplayRuleManager() {}

    private static SharedPreferences prefs(Context context) {
        return PrefsManager.getInstance(context).getPrefs();
    }

    /**
     * Parsed-rules memo. The large-TXT partition reader calls getActiveRules on
     * every partition read (including prefetches), which used to re-read prefs
     * and re-parse the JSON each time. Volatile immutable-by-convention list:
     * every caller that mutates already copies first; saveRules invalidates.
     */
    private static volatile List<TextDisplayRule> cachedRules;
    /** Bumped whenever the rules change; the partition forward-cursor keys on it. */
    private static volatile int rulesVersion = 0;

    public static int getRulesVersion() {
        return rulesVersion;
    }

    public static List<TextDisplayRule> getRules(Context context) {
        List<TextDisplayRule> memo = cachedRules;
        if (memo != null) return memo;
        ArrayList<TextDisplayRule> rules = new ArrayList<>();
        if (context == null) return rules;
        String raw = prefs(context).getString(PREF_KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            for (int i = 0; i < arr.length() && rules.size() < MAX_RULES; i++) {
                TextDisplayRule rule = TextDisplayRule.fromJson(arr.optJSONObject(i));
                if (rule.isValid()) rules.add(rule);
            }
        } catch (Exception ignored) {
            // Broken user-edited JSON should not break opening TXT files.
        }
        cachedRules = rules;
        return rules;
    }

    public static List<TextDisplayRule> getActiveRules(Context context, String filePath) {
        ArrayList<TextDisplayRule> active = new ArrayList<>();
        for (TextDisplayRule rule : getRules(context)) {
            if (rule.appliesTo(filePath)) active.add(rule);
        }
        return active;
    }

    public static void saveRules(Context context, List<TextDisplayRule> rules) {
        cachedRules = null; // rules changed; next read re-parses
        rulesVersion++;
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
        prefs(context).edit().putString(PREF_KEY, arr.toString()).apply();
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
        final Pattern pattern;          // non-null for regex rules; null for literal rules
        final String find;              // literal find text (literal rules only)
        final String replacement;
        final boolean caseSensitive;

        CompiledRule(Pattern pattern, String find, String replacement, boolean caseSensitive) {
            this.pattern = pattern;
            this.find = find;
            this.replacement = replacement;
            this.caseSensitive = caseSensitive;
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
                    out.add(new CompiledRule(pattern, null, repl, rule.caseSensitive));
                } catch (IllegalArgumentException ex) {
                    // Bad user regex: skip this rule rather than break file loading.
                }
            } else {
                if (rule.findText == null || rule.findText.isEmpty()) continue;
                out.add(new CompiledRule(null, rule.findText, repl, rule.caseSensitive));
            }
        }
        return new CompiledRules(out.toArray(new CompiledRule[0]));
    }

    /** Apply pre-compiled rules to one piece of text, reusing compiled patterns. */
    public static String apply(String text, CompiledRules compiled) {
        if (text == null || text.isEmpty() || compiled == null || compiled.isEmpty()) {
            return text != null ? text : "";
        }
        String result = text;
        for (CompiledRule rule : compiled.rules) {
            if (rule.pattern != null) {
                try {
                    Matcher matcher = rule.pattern.matcher(result);
                    result = matcher.replaceAll(rule.replacement);
                } catch (IndexOutOfBoundsException ex) {
                    // Invalid replacement group reference: leave this text unchanged.
                }
            } else {
                result = replaceLiteral(result, rule.find, rule.replacement, rule.caseSensitive);
            }
        }
        return result;
    }

    private static String replaceLiteral(String source, String find, String replacement, boolean caseSensitive) {
        if (source == null || source.isEmpty() || find == null || find.isEmpty()) {
            return source != null ? source : "";
        }
        String repl = replacement != null ? replacement : "";
        if (caseSensitive) {
            return source.replace(find, repl);
        }

        String lowerSource = source.toLowerCase(Locale.ROOT);
        String lowerFind = find.toLowerCase(Locale.ROOT);
        StringBuilder out = null;
        int from = 0;
        int idx;
        while ((idx = lowerSource.indexOf(lowerFind, from)) >= 0) {
            if (out == null) out = new StringBuilder(source.length());
            out.append(source, from, idx);
            out.append(repl);
            from = idx + find.length();
        }
        if (out == null) return source;
        out.append(source, from, source.length());
        return out.toString();
    }
}
