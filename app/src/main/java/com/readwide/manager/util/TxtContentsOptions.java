package com.readwide.manager.util;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.util.ArrayList;
import java.util.List;

/** Immutable, per-file detection policy. It never changes displayed text or pagination. */
public final class TxtContentsOptions {
    public static final int ENGLISH = 1, KOREAN = 2, CJK = 4, EQUALS = 8, HYPHENS = 16,
            WRAPPED = 32, CUSTOM = 64, BUILT_INS = 63;
    public static final int MAX_CUSTOM_TEXT = 8192;
    public static final TxtContentsOptions DEFAULT = new TxtContentsOptions(BUILT_INS, true, true, true, true, "");
    public final int formats;
    public final boolean requireBlank, requireRepeated, reduceProse, combineTitle;
    public final String customRules;

    public TxtContentsOptions(int formats, boolean requireBlank, boolean requireRepeated,
                              boolean reduceProse, boolean combineTitle, String customRules) {
        if ((formats & ~(BUILT_INS | CUSTOM)) != 0 || customRules == null
                || customRules.length() > MAX_CUSTOM_TEXT) throw new IllegalArgumentException("Invalid contents options");
        this.formats = formats;
        this.requireBlank = requireBlank;
        this.requireRepeated = requireRepeated;
        this.reduceProse = reduceProse;
        this.combineTitle = combineTitle;
        this.customRules = customRules.replace("\r\n", "\n").replace('\r', '\n');
    }

    public boolean enabled(int format) { return (formats & format) != 0; }

    boolean accepts(String family) {
        int format = switch (family) {
            case "chapter", "part", "book" -> ENGLISH;
            case "장", "화", "부", "권" -> KOREAN;
            case "章", "回", "節", "卷", "巻", "部" -> CJK;
            case "number-equals" -> EQUALS;
            case "number-hyphens" -> HYPHENS;
            case "angle-title" -> WRAPPED;
            default -> family.startsWith("custom:") ? CUSTOM : 0;
        };
        return enabled(format);
    }

    public String cacheKey() {
        return formats + ":" + requireBlank + ":" + requireRepeated + ":" + reduceProse
                + ":" + combineTitle + ":" + customRules;
    }

    /** Compile off the UI thread. RE2/J avoids a user-controlled backtracking engine. */
    List<Pattern> compileCustomPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        if (!enabled(CUSTOM)) return patterns;
        String[] lines = customRules.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String rule = lines[i].trim();
            if (rule.isEmpty()) continue;
            try { patterns.add(Pattern.compile(rule)); }
            catch (PatternSyntaxException invalid) { throw new InvalidPattern(i + 1, invalid.getDescription()); }
        }
        return patterns;
    }

    public static final class InvalidPattern extends IllegalArgumentException {
        public final int line;
        InvalidPattern(int line, String reason) { super(reason); this.line = line; }
    }
}
