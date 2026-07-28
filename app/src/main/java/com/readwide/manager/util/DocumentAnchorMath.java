package com.readwide.manager.util;

/** Pure validation and preview helpers for rendered-document content anchors. */
public final class DocumentAnchorMath {
    private DocumentAnchorMath() {}

    public static boolean isPreciseVerticalSentence(boolean pageUsesVerticalWriting,
                                                    String anchorMode,
                                                    String writingMode,
                                                    boolean caretMatched,
                                                    String elementId,
                                                    String text) {
        return caretMatched && isStoredVerticalSentence(
                pageUsesVerticalWriting, anchorMode, writingMode, elementId, text);
    }

    /**
     * Recognizes a persisted vertical sentence anchor, including early v2
     * payloads created before {@code caretMatched} was stored. New captures
     * must still pass {@link #isPreciseVerticalSentence}.
     */
    public static boolean isStoredVerticalSentence(boolean pageUsesVerticalWriting,
                                                   String anchorMode,
                                                   String writingMode,
                                                   String elementId,
                                                   String text) {
        if (!"visible-sentence".equals(anchorMode)) return false;
        boolean vertical = pageUsesVerticalWriting
                || (writingMode != null && writingMode.startsWith("vertical-"));
        if (!vertical) return false;
        // A matched DOM caret plus block/context fields makes even a one-to-
        // three-character Japanese utterance a valid target. Requiring four
        // characters discarded real anchors such as 「え？ on Kusamakura.
        return !compact(elementId).isEmpty() || !compact(text).isEmpty();
    }

    /**
     * Treats two vertical sentence anchors as the same bookmark spot only
     * when they identify the same DOM root and essentially the same glyph.
     * A shared sentence or spine page is not enough: one vertical paragraph
     * can span several visible screens.
     */
    public static boolean isSameVerticalSentenceSpot(String oldElementId,
                                                     String newElementId,
                                                     int oldBlockIndex,
                                                     int newBlockIndex,
                                                     int oldCharOffset,
                                                     int newCharOffset) {
        if (oldCharOffset < 0 || newCharOffset < 0
                || Math.abs(oldCharOffset - newCharOffset) > 1) {
            return false;
        }
        String oldId = compact(oldElementId);
        String newId = compact(newElementId);
        if (!oldId.isEmpty() || !newId.isEmpty()) {
            return !oldId.isEmpty() && oldId.equals(newId);
        }
        return oldBlockIndex >= 0 && oldBlockIndex == newBlockIndex;
    }

    /**
     * Native-only WebView positions are not reliable duplicate identifiers for
     * vertical-rl content: some WebView versions report zero while the DOM is
     * horizontally scrolled. Only signed DOM positions may update an existing
     * position-fallback bookmark automatically.
     */
    public static boolean isSameVerticalPositionSpot(boolean oldHasDomPosition,
                                                     int oldScrollX,
                                                     int oldScrollY,
                                                     boolean newHasDomPosition,
                                                     int newScrollX,
                                                     int newScrollY) {
        return oldHasDomPosition
                && newHasDomPosition
                && Math.abs((long) oldScrollX - newScrollX) <= 2L
                && Math.abs((long) oldScrollY - newScrollY) <= 2L;
    }

    /** Old v1 rendered anchors did not identify a visible vertical sentence. */
    public static boolean isUpgradeableLegacyVerticalAnchor(String kind,
                                                            String anchorMode) {
        String normalizedKind = compact(kind);
        String normalizedMode = compact(anchorMode);
        if ("visible-sentence".equals(normalizedMode)
                || "vertical-position".equals(normalizedMode)) {
            return false;
        }
        return normalizedKind.endsWith("_CONTENT_ANCHOR_v1");
    }

    public static String focusedPreview(String focusText,
                                        String sentenceText,
                                        int sentenceOffset,
                                        int maxChars) {
        int limit = Math.max(1, maxChars);
        String focused = compact(focusText);
        if (!focused.isEmpty()) return ellipsize(focused, limit);

        String sentence = compact(sentenceText);
        if (sentence.isEmpty() || sentence.length() <= limit) return sentence;

        int focus = Math.max(0, Math.min(sentence.length(), sentenceOffset));
        int bodyLimit = Math.max(1, limit - 2);
        int start = Math.max(0, focus - Math.max(1, bodyLimit / 3));
        int end = Math.min(sentence.length(), start + bodyLimit);
        start = Math.max(0, end - bodyLimit);

        String body = sentence.substring(start, end).trim();
        String prefix = start > 0 ? "…" : "";
        String suffix = end < sentence.length() ? "…" : "";
        String result = prefix + body + suffix;
        return result.length() <= limit ? result : result.substring(0, limit);
    }

    /**
     * Bookmark labels for vertical writing prefer the first fully visible
     * glyph of the selected physical column. The focused glyph remains a
     * fallback for old anchors and remains authoritative for restoration.
     */
    public static String bookmarkPreview(String columnStartText,
                                         String focusText,
                                         String sentenceText,
                                         int sentenceOffset,
                                         int maxChars) {
        int limit = Math.max(1, maxChars);
        String columnStart = compact(columnStartText);
        if (!columnStart.isEmpty()) return ellipsize(columnStart, limit);
        return focusedPreview(focusText, sentenceText, sentenceOffset, limit);
    }

    private static String ellipsize(String text, int maxChars) {
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= maxChars) return text;
        if (maxChars == 1) return "…";
        int end = text.offsetByCodePoints(0, maxChars - 1);
        return text.substring(0, end).trim() + "…";
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
