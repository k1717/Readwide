package com.readwide.manager;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure, Android-free classifier for page-image EPUB landscape spreads. */
final class EpubImagePageClassifier {
    private static final int MAX_SAMPLES = 32;
    private static final double REQUIRED_IMAGE_RATIO = 0.75d;
    private static final Pattern EMBEDDED_IMAGE_TAG = Pattern.compile(
            "(?is)<(?:object|embed)\\b[^>]*(?:"
                    + "type\\s*=\\s*['\"]image/|"
                    + "(?:data|src)\\s*=\\s*['\"][^'\"]+\\."
                    + "(?:jpe?g|png|webp|gif|svg)(?:[?#][^'\"]*)?['\"])");
    private static final Pattern CSS_PAGE_IMAGE = Pattern.compile(
            "(?is)(?:background-image|background)\\s*:[^;{}]*"
                    + "(?:url\\s*\\(|image-set\\s*\\()");

    private EpubImagePageClassifier() {}

    static boolean isImagePageBook(List<String> htmlPages, boolean fixedLayoutHint) {
        if (htmlPages == null || htmlPages.size() < 2) return false;

        int sampleCount = Math.min(MAX_SAMPLES, htmlPages.size());
        int evaluated = 0;
        int imageDominant = 0;
        int nonCoverImageDominant = 0;
        int stronglyTextual = 0;

        for (int i = 0; i < sampleCount; i++) {
            int index = sampleCount == 1 ? 0
                    : Math.round(i * (htmlPages.size() - 1f) / (sampleCount - 1f));
            String html = htmlPages.get(index);
            if (html == null || html.trim().isEmpty()) continue;
            evaluated++;

            int textLength = strippedHtmlTextLength(html);
            int textBlockCount = countHtmlTag(html, "p")
                    + countHtmlTag(html, "li")
                    + countHtmlTag(html, "blockquote")
                    + countHtmlTag(html, "h1")
                    + countHtmlTag(html, "h2")
                    + countHtmlTag(html, "h3");
            int imageSignals = countHtmlTag(html, "img")
                    + countHtmlTag(html, "svg")
                    + countHtmlTag(html, "image")
                    + countHtmlTag(html, "canvas")
                    + (containsEmbeddedPageImage(html) ? 1 : 0)
                    + (containsCssPageImage(html) ? 1 : 0);

            int textLimit = fixedLayoutHint ? 420 : 300;
            boolean dominant = imageSignals > 0
                    && textLength <= textLimit
                    && textBlockCount <= 4;
            if (dominant) {
                imageDominant++;
                if (index > 0) nonCoverImageDominant++;
            }
            if (textLength >= 1200 || textBlockCount >= 8) stronglyTextual++;
        }

        if (evaluated < 2 || nonCoverImageDominant == 0) return false;
        int required = Math.max(2, (int) Math.ceil(evaluated * REQUIRED_IMAGE_RATIO));
        int allowedTextual = Math.max(1, evaluated / 8);
        return imageDominant >= required && stronglyTextual <= allowedTextual;
    }

    private static boolean containsEmbeddedPageImage(String html) {
        return EMBEDDED_IMAGE_TAG.matcher(html).find();
    }

    private static boolean containsCssPageImage(String html) {
        return CSS_PAGE_IMAGE.matcher(html).find();
    }

    private static int countHtmlTag(String html, String tag) {
        if (html == null || tag == null || tag.isEmpty()) return 0;
        String lower = html.toLowerCase(Locale.US);
        String needle = "<" + tag.toLowerCase(Locale.US);
        int count = 0;
        int from = 0;
        while ((from = lower.indexOf(needle, from)) >= 0) {
            int next = from + needle.length();
            if (next >= lower.length()) {
                count++;
            } else {
                char c = lower.charAt(next);
                if (Character.isWhitespace(c) || c == '>' || c == '/') count++;
            }
            from = Math.max(next, from + 1);
        }
        return count;
    }

    private static int strippedHtmlTextLength(String html) {
        if (html == null || html.isEmpty()) return 0;
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length();
    }
}
