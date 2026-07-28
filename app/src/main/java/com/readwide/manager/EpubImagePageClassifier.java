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

            boolean dominant = isBookSampleImageDominant(html, fixedLayoutHint);
            if (dominant) {
                imageDominant++;
                if (index > 0) nonCoverImageDominant++;
            }
            if (isStronglyTextualPage(html)) stronglyTextual++;
        }

        if (evaluated < 2 || nonCoverImageDominant == 0) return false;
        int required = Math.max(2, (int) Math.ceil(evaluated * REQUIRED_IMAGE_RATIO));
        int allowedTextual = Math.max(1, evaluated / 8);
        return imageDominant >= required && stronglyTextual <= allowedTextual;
    }

    /**
     * Page-local form of the book classifier. A book may satisfy the 75% image
     * ratio while still containing a few text pages, so layout code must not use
     * the book-level result to clip every fixed-layout spine item.
     */
    static boolean isImageDominantPage(String html, boolean fixedLayoutHint) {
        // A magazine page with a background image and a few paragraphs must
        // retain ordinary scrolling even when most other spine items are scans.
        return matchesImageDominance(html, fixedLayoutHint, 120, 96, 1);
    }

    private static boolean isBookSampleImageDominant(String html, boolean fixedLayoutHint) {
        // Keep the established book/spread sampling tolerance, including scanned
        // books that carry hidden OCR text. The page-local clipping decision is
        // intentionally much stricter and cannot be inferred from this result.
        return matchesImageDominance(html, fixedLayoutHint, 420, 300, 4);
    }

    private static boolean matchesImageDominance(String html,
                                                 boolean fixedLayoutHint,
                                                 int fixedTextLimit,
                                                 int reflowTextLimit,
                                                 int textBlockLimit) {
        if (html == null || html.trim().isEmpty()) return false;
        int textLength = strippedHtmlTextLength(html);
        int textBlockCount = textBlockCount(html);
        int imageSignals = countHtmlTag(html, "img")
                + countHtmlTag(html, "svg")
                + countHtmlTag(html, "image")
                + countHtmlTag(html, "canvas")
                + (containsEmbeddedPageImage(html) ? 1 : 0)
                + (containsCssPageImage(html) ? 1 : 0);
        int textLimit = fixedLayoutHint ? fixedTextLimit : reflowTextLimit;
        return imageSignals > 0
                && textLength <= textLimit
                && textBlockCount <= textBlockLimit;
    }

    private static boolean isStronglyTextualPage(String html) {
        return strippedHtmlTextLength(html) >= 1200 || textBlockCount(html) >= 8;
    }

    private static int textBlockCount(String html) {
        return countHtmlTag(html, "p")
                + countHtmlTag(html, "li")
                + countHtmlTag(html, "blockquote")
                + countHtmlTag(html, "h1")
                + countHtmlTag(html, "h2")
                + countHtmlTag(html, "h3");
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
