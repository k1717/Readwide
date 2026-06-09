package com.textview.reader.util;

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

    public static List<TextDisplayRule> getRules(Context context) {
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
        String result = text;
        for (TextDisplayRule rule : rules) {
            if (rule == null || !rule.enabled || !rule.isValid()) continue;
            result = rule.useRegex
                    ? replaceRegexSafely(result, rule.findText, rule.replacementText, rule.caseSensitive)
                    : replaceLiteral(result, rule.findText, rule.replacementText, rule.caseSensitive);
        }
        return result;
    }

    private static String replaceRegexSafely(String source, String patternText, String replacement, boolean caseSensitive) {
        if (source == null || source.isEmpty() || patternText == null || patternText.isEmpty()) {
            return source != null ? source : "";
        }
        String repl = replacement != null ? replacement : "";
        try {
            int flags = Pattern.MULTILINE;
            if (!caseSensitive) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            Pattern pattern = Pattern.compile(patternText, flags);
            Matcher matcher = pattern.matcher(source);
            return matcher.replaceAll(repl);
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            // Bad user regex or invalid replacement group should not break file loading.
            return source;
        }
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
