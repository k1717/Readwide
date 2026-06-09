package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileUtils;

import java.util.ArrayList;
import java.util.List;

final class TtsSegmenter {
    private TtsSegmenter() {
    }

    @NonNull
    static List<TtsSpeechSegment> segmentPage(@NonNull String pageText,
                                              int pageStartChar,
                                              int maxSegmentChars) {
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
            String spoken = normalizeForSpeech(raw);
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
        if (hardEnd >= text.length()) return text.length();

        int minUseful = start + Math.min(80, Math.max(8, (hardEnd - start) / 8));
        for (int i = Math.max(start, minUseful); i < hardEnd; i++) {
            if (isSentenceTerminator(text.charAt(i))) {
                int sentence = i + 1;
                while (sentence < text.length() && isClosingPunctuation(text.charAt(sentence))) {
                    sentence++;
                }
                return sentence;
            }
        }

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
    static String normalizeForSpeech(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
