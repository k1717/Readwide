package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.util.FileUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TtsSegmenter {
    private TtsSegmenter() {
    }

    @NonNull
    static List<TtsSpeechSegment> segmentPage(@NonNull String pageText,
                                              int pageStartChar,
                                              int maxSegmentChars) {
        return segmentPage(pageText, pageStartChar, maxSegmentChars, 0);
    }

    @NonNull
    static List<TtsSpeechSegment> segmentPage(@NonNull String pageText,
                                              int pageStartChar,
                                              int maxSegmentChars,
                                              int pauseReduction) {
        ArrayList<TtsSpeechSegment> result = new ArrayList<>();
        int length = pageText.length();
        int maxChars = Math.max(16, maxSegmentChars);
        int cursor = 0;

        while (cursor < length) {
            while (cursor < length && Character.isWhitespace(pageText.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length) break;

            int hardEnd = safeCodePointEnd(pageText, cursor,
                    cursor + Math.min(length - cursor, maxChars));
            int end = findPreferredEnd(pageText, cursor, hardEnd);
            if (end <= cursor) end = hardEnd;

            while (end > cursor && Character.isWhitespace(pageText.charAt(end - 1))) {
                end--;
            }

            String raw = FileUtils.safeSubstring(pageText, cursor, end);
            String spoken = normalizeForSpeech(raw, pauseReduction);
            if (!spoken.isEmpty()) {
                result.add(new TtsSpeechSegment(
                        pageStartChar + cursor,
                        pageStartChar + end,
                        spoken));
            }
            cursor = Math.max(end, cursor + 1);
        }

        return result;
    }

    private static int findPreferredEnd(@NonNull String text, int start, int hardEnd) {
        boolean reachesEnd = hardEnd >= text.length();
        int scanEnd = Math.min(hardEnd, text.length());

        int minUseful = start + Math.min(80, Math.max(8, (scanEnd - start) / 8));
        for (int i = start; i < scanEnd; i++) {
            if (isSentenceEnd(text, i)) {
                int sentence = i + 1;
                while (sentence < scanEnd && (isClosingPunctuation(text.charAt(sentence))
                        || isSentenceTerminator(text.charAt(sentence)))) {
                    sentence++;
                }
                return sentence;
            }
        }

        // No sentence terminator found in range.
        if (reachesEnd) return text.length();

        int paragraph = text.lastIndexOf("\n\n", hardEnd - 1);
        if (paragraph >= minUseful) return paragraph + 1;

        int line = text.lastIndexOf('\n', hardEnd - 1);
        if (line >= minUseful) return line + 1;

        int space = text.lastIndexOf(' ', hardEnd - 1);
        if (space >= minUseful) return space + 1;

        return hardEnd;
    }

    private static boolean isSentenceTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == ';'
                || c == '\u3002' || c == '\uff01' || c == '\uff1f'
                || c == '\u2026';
    }

    private static boolean isSentenceEnd(String text, int index) {
        char c = text.charAt(index);
        if (!isSentenceTerminator(c)) return false;
        if (c != '.') return true;
        // Decimal points, domain names and dotted abbreviations are not stops.
        if (index + 1 < text.length() && Character.isLetterOrDigit(text.charAt(index + 1))) {
            return false;
        }
        int wordStart = index;
        // Abbreviation recognition is deliberately bounded and Latin-specific;
        // do not scan/copy a huge unbroken token or treat a lone CJK word as an initial.
        int minimum = Math.max(0, index - 6);
        while (wordStart > minimum && (isLatinLetter(text.charAt(wordStart - 1))
                || text.charAt(wordStart - 1) == '.')) wordStart--;
        String token = text.substring(wordStart, index).toLowerCase(Locale.ROOT);
        if (index + 1 < text.length()) {
            if (token.length() == 1 && isLatinLetter(token.charAt(0))) return false;
            switch (token) {
                case "mr": case "mrs": case "ms": case "dr": case "prof":
                case "sr": case "jr": case "vs": case "e.g": case "i.e":
                    return false;
                default: break;
            }
        }
        return true;
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /** Bound a prefetch window without cutting a normal word or a surrogate pair. */
    static int windowEnd(String text, int start, int limit, int budget) {
        int end = safeCodePointEnd(text, start, start + Math.min(limit - start, budget));
        if (end >= limit) return limit;
        if (Character.isWhitespace(text.charAt(end))) return end;
        for (int i = end - 1; i > start; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
            if (isSentenceEnd(text, i)) return i + 1;
        }
        // An overlong unbroken token still needs a bounded, code-point-safe split.
        return end;
    }

    private static int safeCodePointEnd(String text, int start, int end) {
        if (end > start && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) return end - 1;
        return end;
    }

    /** Called against the original buffer, not the normalized spoken text. */
    static boolean endsSentence(String text, int end) {
        int cursor = Math.min(end, text.length());
        while (cursor > 0 && (Character.isWhitespace(text.charAt(cursor - 1))
                || isClosingPunctuation(text.charAt(cursor - 1)))) cursor--;
        return end >= text.length() || (cursor > 0 && isSentenceEnd(text, cursor - 1));
    }

    private static boolean isClosingPunctuation(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '}'
                || c == '\u201d' || c == '\u2019' || c == '\u300d'
                || c == '\u300f' || c == '\u300b' || c == '\u3009';
    }

    @NonNull
    /** Maps the stored phrase-length level (0/1/2) to a target chunk size in chars. */
    static int phraseLengthToChars(int level) {
        switch (level) {
            case 0: return 200;   // Short - neural "sweet spot", snappier
            case 1: return 400;   // Medium
            default: return 700;  // Long - best prosody, pre-1.0.11 default
        }
    }

    static String normalizeForSpeech(String raw) {
        return normalizeForSpeech(raw, 0);
    }

    static String normalizeForSpeech(String raw, int pauseReduction) {
        if (raw == null) return "";
        String text = raw.replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                // Mute ellipses. The speech engine names consecutive dots ("점" =
                // "dot"), so it reads "..." / "…" aloud as "점점". Drop runs of 2+
                // ASCII dots and the "…" character; a lone "." is left untouched
                // (normal period / decimal point / abbreviation). Collapse the
                // spaces the removal leaves behind.
                .replaceAll("(?:\\.{2,}|\u2026)+", " ")
                // Underscores are often read aloud (e.g. "밑줄"), and a run of them
                // is just a visual rule — mute to a space so identifiers/URLs read
                // naturally. Semicolons become a comma: if the engine voices ";"
                // its name is dropped, while the clause-level pause is preserved.
                .replace('_', ' ')
                .replaceAll(";+", ",");

        // Optional pause reduction for engines (e.g. Kokoro) that stop too long at
        // punctuation. This is a text transform only; it never touches audio.
        // Medium: drop commas so clauses run together. Aggressive: also turn
        // sentence stops into commas so the cadence keeps moving instead of a full
        // stop. Applied before whitespace collapse so the gaps left behind merge.
        if (pauseReduction >= 2) {
            // Mute quotation marks first (straight/curly doubles, guillemets, CJK
            // corner brackets) - some neural engines pause at every quote, which
            // makes dialogue crawl. Quotes-first also matters for dialogue-final
            // stops: the stop-softening below only matches a stop followed by
            // whitespace, so "...!\u201D must become "...! " before it can soften.
            // Apostrophes/single quotes are deliberately left alone (contractions
            // like "don't" would break). Off and Medium keep quotes: they carry
            // meaning in fiction, so only the strongest level trades them away.
            text = text.replaceAll("[\"\u201C\u201D\u201E\u00AB\u00BB\u300C\u300D\u300E\u300F]+", " ");
            // Drop the original commas first, THEN soften sentence-final stops to
            // a comma-length pause. Order matters: doing it the other way around
            // would delete the commas the stop-conversion just created, removing
            // every pause instead of shortening them.
            text = text.replace(",", " ").replaceAll("[.!?]+(?=\\s|$)", ",");
        } else if (pauseReduction >= 1) {
            text = text.replace(",", " ");
        }

        return text.replaceAll(" {2,}", " ").trim();
    }
}
