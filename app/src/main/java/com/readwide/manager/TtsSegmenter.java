package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.util.FileUtils;

import java.util.ArrayList;
import java.util.List;

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

            int hardEnd = Math.min(length, cursor + maxChars);
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
        for (int i = Math.max(start, minUseful); i < scanEnd; i++) {
            if (isSentenceTerminator(text.charAt(i))) {
                int sentence = i + 1;
                while (sentence < text.length() && isClosingPunctuation(text.charAt(sentence))) {
                    sentence++;
                }
                return sentence;
            }
        }

        // No sentence terminator found in range.
        if (reachesEnd) return text.length();

        int paragraph = text.lastIndexOf("\n\n", hardEnd);
        if (paragraph >= minUseful) return paragraph + 1;

        int line = text.lastIndexOf('\n', hardEnd);
        if (line >= minUseful) return line + 1;

        int space = text.lastIndexOf(' ', hardEnd);
        if (space >= minUseful) return space + 1;

        return hardEnd;
    }

    private static boolean isSentenceTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == ';'
                || c == '\u3002' || c == '\uff01' || c == '\uff1f'
                || c == '\u2026';
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
