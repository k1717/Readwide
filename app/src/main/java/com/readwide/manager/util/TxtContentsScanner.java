package com.readwide.manager.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Incremental heading detection in the reader's already-transformed UTF-16 coordinates. */
public final class TxtContentsScanner {
    public static final int REVISION = 6;
    public static final int MAX_TITLE = 180;
    private static final String NUMBER = "(?:[0-9０-９]+|[IVXLCDM]+|[一二三四五六七八九十百千万零〇壱弐参]+)";
    private static final Pattern ENGLISH = Pattern.compile(
            "^(chapter|part|book)\\s+(" + NUMBER + ")(?:$|[\\s:：.．—–-].*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KOREAN = Pattern.compile(
            "^(?:제\\s*)?(" + NUMBER + ")\\s*([장화부권])(?:$|([\\s:：.．—–-].*))");
    private static final Pattern CJK = Pattern.compile(
            "^第\\s*(" + NUMBER + ")\\s*([章回節卷巻部])(?:.*)$");
    // A decorated chapter number, not a bare number or a numeric expression.
    // An optional title must start with a letter after whitespace. Mixed delimiters are not headings.
    private static final Pattern NUMBER_DECORATED = Pattern.compile(
            "^([0-9０-９]+)[ \\t\u00A0\u3000]*(={3,}|-{3,})(?:[ \\t\u00A0\u3000]+(\\p{L}.*))?$");

    public static final class Entry {
        public final String id, title, before, after;
        public final int position, level;
        public final boolean manual;

        public Entry(String id, String title, int position, int level,
                     String before, String after, boolean manual) {
            this.id = id;
            this.title = title;
            this.position = position;
            this.level = level;
            this.before = before;
            this.after = after;
            this.manual = manual;
        }
    }

    private static final class Candidate {
        final String title, family, anchor, nextLinePrefix;
        final int position;
        final long number;
        final boolean part;
        Candidate(String title, String family, int position, boolean part,
                  long number, String anchor, String nextLinePrefix) {
            this.title = title;
            this.family = family;
            this.position = position;
            this.part = part;
            this.number = number;
            this.anchor = anchor;
            this.nextLinePrefix = nextLinePrefix;
        }
    }

    private static final class FamilyEvidence {
        int count, decimalCount, run, longestRun;
        long lastNumber = -1;

        void add(Candidate c) {
            count++;
            if (c.number >= 0) decimalCount++;
            run = c.number >= 0 && lastNumber >= 0 && lastNumber == c.number - 1 ? run + 1 : 1;
            longestRun = Math.max(longestRun, run);
            lastNumber = c.number;
        }
    }

    private final List<Candidate> candidates = new ArrayList<>();
    private final TxtContentsOptions options;
    private final List<com.google.re2j.Pattern> customPatterns;
    private final StringBuilder line = new StringBuilder();
    private long offset, lineStart;
    private boolean oversized, previousBlank = true, finished;
    private Candidate pending;

    public TxtContentsScanner() { this(TxtContentsOptions.DEFAULT); }

    public TxtContentsScanner(TxtContentsOptions options) {
        this.options = java.util.Objects.requireNonNull(options);
        customPatterns = options.compileCustomPatterns();
    }

    public void append(CharSequence text) {
        if (finished) throw new IllegalStateException("Contents scan already finished");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                finishLine();
                lineStart = offset + 1;
            } else if (line.length() < MAX_TITLE) {
                line.append(ch);
            } else {
                oversized = true; // A body line is not a heading; never retain its whole text.
            }
            offset++;
        }
    }

    public List<Entry> finish() {
        if (finished) throw new IllegalStateException("Contents scan already finished");
        finishLine();
        if (pending != null) candidates.add(pending); // EOF is a boundary too.
        finished = true;
        Map<String, FamilyEvidence> families = new HashMap<>();
        for (Candidate c : candidates) {
            if (!options.accepts(c.family)) continue;
            FamilyEvidence evidence = families.get(c.family);
            if (evidence == null) {
                evidence = new FamilyEvidence();
                families.put(c.family, evidence);
            }
            evidence.add(c);
        }
        int strongDecoratedCount = 0;
        for (Map.Entry<String, FamilyEvidence> family : families.entrySet()) {
            FamilyEvidence evidence = family.getValue();
            if (isDecorated(family.getKey()) && evidence.longestRun >= 3) {
                strongDecoratedCount = Math.max(strongDecoratedCount, evidence.count);
            }
        }
        List<Entry> result = new ArrayList<>();
        boolean insidePart = false;
        for (Candidate c : candidates) {
            if (!options.accepts(c.family)) continue;
            // Require a repeated explicit heading family, not arbitrary numbered paragraphs.
            FamilyEvidence evidence = families.get(c.family);
            if (options.requireRepeated && evidence.count < 2) continue;
            // A clear decorated chapter sequence outweighs sparse numeric mentions in prose.
            // Preserve parts, nondecimal numbering and competing sequential chapter families.
            if (options.reduceProse && !c.part && !isDecorated(c.family) && strongDecoratedCount > evidence.count
                    && evidence.decimalCount == evidence.count && evidence.longestRun < 2) continue;
            if (c.part) insidePart = true;
            result.add(new Entry("auto:" + c.position, c.title, c.position,
                    !c.part && insidePart ? 1 : 0, "", c.anchor.substring(0, Math.min(120, c.anchor.length())), false));
        }
        return result;
    }

    private void finishLine() {
        String raw = line.toString();
        int leading = 0, end = raw.length();
        while (leading < end && space(raw.charAt(leading))) leading++;
        while (end > leading && space(raw.charAt(end - 1))) end--;
        boolean blank = !oversized && leading == end;
        long position = lineStart + leading;
        String title = raw.substring(leading, end);
        Candidate continued = null;
        if (pending != null) {
            if (blank) {
                candidates.add(pending);
            } else if (options.combineTitle && !oversized && pending.nextLinePrefix != null && position <= Integer.MAX_VALUE
                    && !title.isEmpty() && Character.isLetter(title.codePointAt(0))
                    && classify(title, (int) position, raw.substring(leading)) == null) {
                String label = pending.nextLinePrefix + " — " + title;
                if (label.length() <= MAX_TITLE) {
                    // The label is presentation only. Navigation must still match actual source text.
                    continued = new Candidate(label, pending.family, pending.position, false,
                            pending.number, pending.anchor + "\n" + raw, null);
                }
            }
            if (!options.requireBlank && !blank && continued == null) candidates.add(pending);
        }
        pending = continued;
        if (pending == null && !oversized && !blank && (previousBlank || !options.requireBlank)
                && position <= Integer.MAX_VALUE) {
            pending = classify(title, (int) position, raw.substring(leading));
        }
        previousBlank = blank;
        line.setLength(0);
        oversized = false;
    }

    private static boolean space(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch == '\uFEFF';
    }

    private static boolean isDecorated(String family) {
        return family.equals("number-equals") || family.equals("number-hyphens");
    }

    private static long decimalNumber(String value) {
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int digit = ch >= '0' && ch <= '9' ? ch - '0'
                    : ch >= '０' && ch <= '９' ? ch - '０' : -1;
            if (digit < 0 || result > (Long.MAX_VALUE - digit) / 10) return -1;
            result = result * 10 + digit;
        }
        return result;
    }

    private Candidate classify(String title, int position, String anchor) {
        for (int i = 0; i < customPatterns.size(); i++) {
            com.google.re2j.Matcher match = customPatterns.get(i).matcher(title);
            if (!match.matches()) continue;
            String label = match.groupCount() > 0 ? match.group(1) : title;
            if (label == null) continue;
            int start = 0, end = label.length();
            while (start < end && space(label.charAt(start))) start++;
            while (end > start && space(label.charAt(end - 1))) end--;
            label = label.substring(start, end);
            if (!label.isEmpty()) return new Candidate(label, "custom:" + i, position, false, -1, anchor, null);
        }
        if (title.startsWith("<--") && title.endsWith("-->") && title.length() > 6) {
            int start = 3, end = title.length() - 3;
            while (start < end && space(title.charAt(start))) start++;
            while (end > start && space(title.charAt(end - 1))) end--;
            String label = title.substring(start, end);
            // Explicit plain-text decoration, not HTML comments/tags or empty separator lines.
            if (label.indexOf('<') < 0 && label.indexOf('>') < 0
                    && label.codePoints().anyMatch(Character::isLetterOrDigit)) {
                return new Candidate(label, "angle-title", position, false, -1, anchor, null);
            }
        }
        Matcher decorated = NUMBER_DECORATED.matcher(title);
        if (decorated.matches()) {
            String family = decorated.group(2).charAt(0) == '=' ? "number-equals" : "number-hyphens";
            return new Candidate(title, family, position, false, decimalNumber(decorated.group(1)),
                    anchor, decorated.group(3) == null ? decorated.group(1) : null);
        }
        Matcher english = ENGLISH.matcher(title);
        if (english.matches()) {
            String family = english.group(1).toLowerCase(java.util.Locale.ROOT);
            return new Candidate(title, family, position, !family.equals("chapter"),
                    decimalNumber(english.group(2)), anchor, null);
        }
        Matcher korean = KOREAN.matcher(title);
        if (korean.matches()) {
            String family = korean.group(2);
            return new Candidate(title, family, position, family.equals("부") || family.equals("권"),
                    decimalNumber(korean.group(1)), anchor,
                    family.equals("화") && korean.group(3) == null ? title : null);
        }
        Matcher cjk = CJK.matcher(title);
        if (cjk.matches()) {
            String family = cjk.group(2);
            return new Candidate(title, family, position, "卷巻部".contains(family),
                    decimalNumber(cjk.group(1)), anchor, null);
        }
        return null;
    }
}
