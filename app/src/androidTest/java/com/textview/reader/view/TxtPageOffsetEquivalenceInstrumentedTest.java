package com.readwide.manager.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.LargeTextPageIndexCache;
import com.readwide.manager.util.TextPageFontIdentity;
import com.readwide.manager.view.CustomReaderView.PageTextAnchor;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Frozen pre-optimization oracle; v6 may remove only its already-visible EOF suffix. */
@RunWith(AndroidJUnit4.class)
public class TxtPageOffsetEquivalenceInstrumentedTest {
    @Test public void configurationCacheKeyIsSafeOnAndroidBeforeIndexingStarts() {
        // Desktop regex accepts some unescaped braces that Android ICU rejects.
        // Keep this on-device regression even though normalization is now literal.
        String stable = "{1.0 [en_US] 420dpi winConfig={bounds} as.7}";
        assertEquals(stable, LargeTextPageIndexCache.configurationKey(stable));
        assertEquals(stable, LargeTextPageIndexCache.configurationKey(
                "{1.0 [en_US] 420dpi winConfig={bounds} as.7 s.123}"));
        assertEquals(stable, LargeTextPageIndexCache.configurationKey(
                "{1.0 [en_US] 420dpi winConfig={bounds} s.-42 as.7}"));
        assertEquals("", LargeTextPageIndexCache.configurationKey(null));
        assertNotEquals(stable, LargeTextPageIndexCache.configurationKey(
                "{1.0 [en_US] 420dpi winConfig={bounds} as.8 s.123}"));
    }

    @Test public void readerSystemFamiliesUseStableKeysInsteadOfSessionTokens() {
        for (String family : new String[]{"sans-serif", "sans-serif-medium", "sans-serif-condensed", "sans-serif-light"}) {
            Typeface face = Typeface.create(family, Typeface.NORMAL);
            String key = TextPageFontIdentity.key(face);
            assertTrue(family + ": " + key, key.startsWith("builtin:"));
            assertEquals(key, TextPageFontIdentity.key(Typeface.create(family, Typeface.NORMAL)));
            TextPaint paint = new TextPaint();
            paint.setTypeface(face);
            assertEquals(key, TextPageFontIdentity.key(new TextPaint(paint).getTypeface()));
        }
    }

    @Test public void offsetsAndContextMatchFrozenPagingCalculation() {
        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < 900; i++) repeated.append("line ").append(i)
                .append(" 한글 中文 English العربية 😀 e\u0301\tlong word\n");
        String[] inputs = {"", "\n\n", "short", "끝😀\n\n", repeated.toString(),
                repeated.toString().replace("\n", " ")};
        for (String input : inputs) {
            for (Typeface face : new Typeface[]{Typeface.DEFAULT, Typeface.MONOSPACE}) {
                TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
                paint.setTypeface(face);
                paint.setTextSize(23.25f);
                for (int overlap : new int[]{0, 1, 8}) {
                    for (int width : new int[]{137, 530}) {
                        compare(input, input, paint, width, 641, 17, overlap, 1.15f);
                    }
                }
            }
        }
    }

    @Test public void styledAndTinyViewportOffsetsMatchFrozenPagingCalculation() {
        String text = "# Heading\n\n**bold** and normal 한글 😀\n\n\n";
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.SERIF);
        paint.setTextSize(31f);
        CharSequence styled = MarkdownSyntaxHighlighter.apply(text, Color.WHITE, Color.BLACK);
        for (int height : new int[]{1, 45, 320}) {
            for (int overlap : new int[]{0, 2, 8}) compare(styled, text, paint, 181, height, 9, overlap, 1.3f);
        }
        compare("mismatch", text, paint, 181, 320, 9, 0, 1.3f);
    }

    @Test public void resolvedFontIdentitySeparatesFacesWithTheSameStyle() {
        assertEquals("builtin:default", TextPageFontIdentity.key(null));
        assertEquals("builtin:serif", TextPageFontIdentity.key(Typeface.SERIF));
        Typeface serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        String serifKey = TextPageFontIdentity.key(serifBold);
        assertTrue(serifKey.startsWith("session:"));
        assertEquals(serifKey, TextPageFontIdentity.key(serifBold));
        assertNotEquals(serifKey, TextPageFontIdentity.key(monoBold));
        TextPaint original = new TextPaint();
        original.setTypeface(serifBold);
        assertEquals(serifKey, TextPageFontIdentity.key(new TextPaint(original).getTypeface()));
    }

    @Test public void styledCachePreservesAnchorsAndRejectsThemeOrFaceChange() throws Exception {
        String block = "# Heading 한글 😀\n\n**bold** _italic_ [link](url)\n"
                + "- bullet one\n- bullet two\n> quote\n```\ncode\n```\n"
                + "| left | right |\n| --- | --- |\n| 1 | 2 |\n\n";
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 12; i++) textBuilder.append(block);
        String text = textBuilder.toString();
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        paint.setTextSize(23.25f);
        CharSequence styled = MarkdownSyntaxHighlighter.apply(text, Color.WHITE, Color.BLACK);
        compare(styled, text, paint, 241, 511, 11, 1, 1.15f);
        ArrayList<PageTextAnchor> expected = CustomReaderView.buildPageTextAnchors(
                styled, text, paint, 241, 511, 11, 1, 1.15f);
        String font = TextPageFontIdentity.key(paint.getTypeface());
        String style = MarkdownSyntaxHighlighter.pageLayoutCacheKey(Color.WHITE, Color.BLACK);
        String signature = font + "|" + style;
        byte[] digest = LargeTextPageIndexCache.newDigest().digest(text.getBytes(StandardCharsets.UTF_8));
        LargeTextPageIndexCache.Checkpoint live = () -> {};
        File cache = File.createTempFile("txt-styled-index", ".bin",
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir());
        try {
            LargeTextPageIndexCache.save(cache, signature, digest, text.length(), expected, live);
            ArrayList<PageTextAnchor> actual = LargeTextPageIndexCache.load(
                    cache, signature, digest, text.length(), live);
            assertNotNull(actual);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i).charPosition, actual.get(i).charPosition);
                assertEquals(expected.get(i).anchorTextBefore, actual.get(i).anchorTextBefore);
                assertEquals(expected.get(i).anchorTextAfter, actual.get(i).anchorTextAfter);
            }
            assertNull(LargeTextPageIndexCache.load(cache, font + "|"
                    + MarkdownSyntaxHighlighter.pageLayoutCacheKey(Color.BLACK, Color.WHITE),
                    digest, text.length(), live));
            assertNull(LargeTextPageIndexCache.load(cache,
                    TextPageFontIdentity.key(Typeface.MONOSPACE) + "|" + style,
                    digest, text.length(), live));
            assertNull(LargeTextPageIndexCache.load(cache, signature + ":new-renderer",
                    digest, text.length(), live));
        } finally { cache.delete(); }
    }

    private static void compare(CharSequence layoutText, String text, TextPaint paint,
            int width, int height, int margin, int overlap, float spacing) {
        ArrayList<PageTextAnchor> expected = referenceAnchors(layoutText, text, paint,
                width, height, margin, overlap, spacing);
        int[] offsets = CustomReaderView.buildPageStartOffsets(layoutText, text, paint,
                width, height, margin, overlap, spacing);
        ArrayList<PageTextAnchor> actual = CustomReaderView.buildPageTextAnchors(layoutText, text, paint,
                width, height, margin, overlap, spacing);
        int[] continuingWindow = CustomReaderView.buildPageStartOffsets(layoutText, text, paint,
                width, height, margin, overlap, spacing, false);
        assertEquals("nonterminal window arithmetic must remain unchanged", expected.size(), continuingWindow.length);
        for (int i = 0; i < continuingWindow.length; i++) {
            assertEquals(expected.get(i).charPosition, continuingWindow[i]);
        }
        int retained = retainedEofPrefix(expected, layoutText, text, paint, width, height, margin, spacing);
        assertEquals(retained, offsets.length);
        assertEquals(retained, actual.size());
        for (int i = 0; i < offsets.length; i++) {
            assertEquals(expected.get(i).charPosition, offsets[i]);
            assertEquals(expected.get(i).charPosition, actual.get(i).charPosition);
            assertEquals(expected.get(i).anchorTextBefore, actual.get(i).anchorTextBefore);
            assertEquals(expected.get(i).anchorTextAfter, actual.get(i).anchorTextAfter);
        }
    }

    /** Inspect old page coverage, without changing its frozen overlap-step oracle below. */
    private static int retainedEofPrefix(ArrayList<PageTextAnchor> oldPages, CharSequence styled,
                                         String text, TextPaint paint, int width, int height,
                                         int margin, float spacing) {
        if (text.isEmpty()) return oldPages.size();
        CharSequence content = styled != null && styled.length() == text.length() ? styled : text;
        StaticLayout layout = StaticLayout.Builder.obtain(content, 0, content.length(), new TextPaint(paint),
                        Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, Math.max(0.8f, spacing))
                .setIncludePad(true).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
        int firstPad = layout.getLineCount() < 2 ? 0 : Math.max(0,
                layout.getLineBaseline(0) - layout.getLineTop(0)
                        - (layout.getLineBaseline(1) - layout.getLineTop(1)));
        int viewport = Math.max(1, height);
        int naturalMax = Math.max(0, layout.getHeight() + 2 * Math.max(0, margin) - viewport);
        int firstAnchor = Math.min(naturalMax, Math.max(0, margin) + firstPad);
        int finalBottom = layout.getLineBottom(layout.getLineCount() - 1);
        for (int i = 0; i < oldPages.size(); i++) {
            int line = layout.getLineForOffset(oldPages.get(i).charPosition);
            int top = Math.max(0, Math.max(firstAnchor, layout.getLineTop(line) + Math.max(0, margin))
                    - Math.max(0, margin));
            if (finalBottom <= top + viewport) return i + 1;
        }
        return oldPages.size(); // A viewport shorter than a rendered row is a separate issue.
    }

    private static int getContentHeightForPaging(StaticLayout sourceLayout,
                                                 String value,
                                                 int marginVerticalPx) {
        // Keep the paging height identical to the normal full-file TXT path.
        // Do not strip terminal blank lines here: doing so makes the final-page
        // visual anchor differ from simple full-file loading even when the total
        // page count happens to remain the same.
        int margin = Math.max(0, marginVerticalPx);
        return (sourceLayout != null ? sourceLayout.getHeight() : 0) + margin * 2;
    }

    private static ArrayList<PageTextAnchor> referenceAnchors(CharSequence layoutValue,
                                                                  String value,
                                                                  TextPaint sourcePaint,
                                                                  int layoutWidth,
                                                                  int viewportHeight,
                                                                  int marginVerticalPx,
                                                                  int overlapLines,
                                                                  float lineSpacingMultiplier) {
        ArrayList<PageTextAnchor> result = new ArrayList<>();
        String fullText = value != null ? value : "";
        CharSequence fullLayoutText = layoutValue != null ? layoutValue : fullText;
        if (fullLayoutText.length() != fullText.length()) {
            fullLayoutText = fullText;
        }
        if (fullText.isEmpty()) {
            result.add(new PageTextAnchor(0, "", ""));
            return result;
        }

        TextPaint localPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        if (sourcePaint != null) {
            localPaint.set(sourcePaint);
        }

        StaticLayout localLayout = StaticLayout.Builder
                .obtain(fullLayoutText, 0, fullLayoutText.length(), localPaint, Math.max(1, layoutWidth))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, Math.max(0.8f, lineSpacingMultiplier))
                .setIncludePad(true)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        int lineCount = localLayout.getLineCount();
        if (lineCount <= 0) {
            result.add(new PageTextAnchor(0, "", ""));
            return result;
        }

        int firstPadCompensation = 0;
        if (lineCount >= 2) {
            int firstBaselineOffset = localLayout.getLineBaseline(0) - localLayout.getLineTop(0);
            int normalBaselineOffset = localLayout.getLineBaseline(1) - localLayout.getLineTop(1);
            firstPadCompensation = Math.max(0, firstBaselineOffset - normalBaselineOffset);
        }

        // Match the normal full-file CustomReaderView path exactly, including
        // terminal blank lines.  The large-TXT exact index must mirror what the
        // simple full-file reader would paginate, otherwise the last page can
        // show a different top line while still reporting the same page number.
        int contentHeight = getContentHeightForPaging(localLayout, fullText, marginVerticalPx);
        int viewport = Math.max(1, viewportHeight);
        int maxScroll = Math.max(0, contentHeight - viewport);
        int firstPageAnchor = Math.max(0, marginVerticalPx) + firstPadCompensation;
        int minPageScrollY = (lineCount <= 0 || maxScroll <= 0)
                ? 0
                : Math.max(0, Math.min(maxScroll, firstPageAnchor));

        int startLine = 0;
        int overlap = Math.max(0, Math.min(8, overlapLines));
        int lastChar = -1;
        while (startLine < lineCount) {
            int charPos = Math.max(0, Math.min(fullText.length(), localLayout.getLineStart(startLine)));
            charPos = FileUtils.clampToSurrogateSafeStart(fullText, charPos);
            if (charPos != lastChar) {
                int beforeStart = Math.max(0, charPos - 80);
                int afterEnd = Math.min(fullText.length(), charPos + 120);
                result.add(new PageTextAnchor(
                        charPos,
                        FileUtils.safeSubstring(fullText, beforeStart, charPos),
                        FileUtils.safeSubstring(fullText, charPos, afterEnd)));
                lastChar = charPos;
            }

            int rawAnchor = localLayout.getLineTop(startLine) + Math.max(0, marginVerticalPx);
            int anchor = Math.max(minPageScrollY, rawAnchor);
            int pageTop = Math.max(0, anchor - Math.max(0, marginVerticalPx));
            int pageBottomLimit = pageTop + viewport;

            int lastFullLine = startLine - 1;
            for (int line = startLine; line < lineCount; line++) {
                if (localLayout.getLineBottom(line) <= pageBottomLimit) {
                    lastFullLine = line;
                } else {
                    break;
                }
            }

            int nextStartLine = Math.max(startLine + 1, lastFullLine + 1 - overlap);
            if (nextStartLine <= startLine || nextStartLine >= lineCount) {
                break;
            }
            startLine = nextStartLine;
        }

        if (result.isEmpty()) {
            result.add(new PageTextAnchor(0, "", FileUtils.safeSubstring(fullText, 0, Math.min(fullText.length(), 120))));
        }
        return result;
    }
}
