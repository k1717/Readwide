package com.textview.reader.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.graphics.Color;
import android.graphics.Typeface;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.textview.reader.util.FileUtils;
import com.textview.reader.util.LargeTextContinuityMath;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class CustomReaderViewContinuityInstrumentedTest {
    private static final String REAL_FIXTURE_ASSET = "large_txt_real_fixture.txt";

    @Test
    public void localTapPagingMovesForwardWithoutSkippingComputedNextStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            view.setTextContent(numberedLines(180, "local"));

            int total = view.getTotalPageCount();
            assertTrue("test document should span multiple pages", total > 3);

            int previous = view.getCurrentCharPosition();
            for (int page = 1; page < total; page++) {
                int expectedNextStart = view.getCharPositionForNextPageStartRespectingOverlap();
                boolean corrected = view.pageForwardWithoutSkippingContent();
                int actual = view.getCurrentCharPosition();

                assertTrue("page " + page + " must move forward", actual > previous);
                assertTrue("page " + page + " must not skip past computed next start",
                        actual <= expectedNextStart);
                if (!corrected && actual < expectedNextStart) {
                    assertTrue("only the final clamp may land before computed next start",
                            page >= total - 1);
                }
                previous = actual;
            }
        });
    }

    @Test
    public void partitionHandoffTargetLandsOnNextPartitionLineStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            String[] lines = generatedLines(150, "partition");
            String firstWindow = joinLines(lines, 0, 110);
            String secondWindow = joinLines(lines, 100, 150);
            int bodyEnd = joinLines(lines, 0, 100).length();
            int secondBase = bodyEnd + 1;

            CustomReaderView first = laidOutReaderView();
            first.setOverlapLines(0);
            first.setTextContent(firstWindow);

            int nextStart = -1;
            int guard = first.getTotalPageCount() + 2;
            while (guard-- > 0) {
                nextStart = first.getCharPositionForNextPageStartRespectingOverlap();
                if (nextStart >= bodyEnd) break;
                first.pageForwardWithoutSkippingContent();
            }
            assertTrue("test must reach partition body seam", nextStart >= bodyEnd);

            int targetAbs = LargeTextContinuityMath.forwardHandoffTargetAbs(0, nextStart, bodyEnd);
            int expectedSecondLocal = targetAbs - secondBase;
            if (nextStart == bodyEnd) {
                assertEquals(0, expectedSecondLocal);
            } else {
                assertTrue(expectedSecondLocal > 0);
            }

            CustomReaderView second = laidOutReaderView();
            second.setOverlapLines(0);
            second.setTextContent(secondWindow);
            second.scrollToCharPosition(expectedSecondLocal);

            assertEquals(expectedSecondLocal, second.getCurrentCharPosition());
        });
    }

    @Test
    public void exactTextAnchorsMatchRenderedPageStarts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            String content = numberedLines(220, "anchor");
            view.setTextContent(content);

            ArrayList<CustomReaderView.PageTextAnchor> anchors =
                    CustomReaderView.buildPageTextAnchors(
                            content,
                            view.copyTextPaintForIndex(),
                            view.getTextLayoutWidthForIndex(),
                            view.getViewportHeight(),
                            view.getMarginVerticalPxForIndex(),
                            view.getOverlapLinesForIndex(),
                            view.getLineSpacingMultiplierForIndex());

            int total = view.getTotalPageCount();
            assertEquals("exact anchor count must match rendered page count", total, anchors.size());
            for (int page = 1; page <= total; page++) {
                view.scrollToPage(page);
                assertEquals("page " + page + " start char must match exact anchor",
                        anchors.get(page - 1).charPosition,
                        view.getCurrentCharPosition());
            }
        });
    }

    @Test
    public void backwardPagingReturnsToPreviousRenderedPageStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            view.setTextContent(numberedLines(180, "backward"));

            int total = view.getTotalPageCount();
            assertTrue("test document should span multiple pages", total > 3);

            int[] starts = new int[total];
            for (int page = 1; page <= total; page++) {
                view.scrollToPage(page);
                starts[page - 1] = view.getCurrentCharPosition();
            }

            view.scrollToPage(total);
            for (int page = total; page > 1; page--) {
                view.pageBackwardWithoutSkippingContent();
                assertEquals("previous from page " + page + " must land on page " + (page - 1),
                        starts[page - 2],
                        view.getCurrentCharPosition());
            }
        });
    }

    @Test
    public void configuredOverlapRepeatsOnlyExpectedPageStart() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(2);
            view.setTextContent(numberedLines(140, "overlap"));

            int total = view.getTotalPageCount();
            assertTrue("test document should span multiple pages", total > 3);

            for (int page = 1; page < Math.min(total, 6); page++) {
                int expectedNextStart = view.getCharPositionForNextPageStartRespectingOverlap();
                view.pageForwardWithoutSkippingContent();
                assertEquals("overlap page " + page + " must land on computed next start",
                        expectedNextStart,
                        view.getCurrentCharPosition());
            }
        });
    }

    @Test
    public void realFixture_decodesAsReadableKoreanText() throws Exception {
        assumeRealFixtureAvailable();
        File file = copyRealFixtureToCache();
        String text = FileUtils.readTextFile(file);

        assertTrue("real fixture should be a large TXT", text.length() > 1_000_000);
        assertTrue("decoded real fixture should contain strong Hangul signal",
                countHangul(text, 200_000) > 10_000);
        assertTrue("decoded real fixture should not look like common UTF-8 mojibake",
                countOccurrences(text, "ì", 200_000) < 50);
        assertTrue("decoded real fixture should not contain replacement-heavy decode",
                countOccurrences(text, "\uFFFD", 200_000) < 10);
    }

    @Test
    public void realFixture_sampledAnchorsMatchRenderedPageStarts() throws Exception {
        assumeRealFixtureAvailable();
        String text = FileUtils.readTextFile(copyRealFixtureToCache());
        int sampleLength = Math.min(80_000, text.length());
        int[] starts = new int[] {
                0,
                Math.max(0, text.length() / 2 - sampleLength / 2),
                Math.max(0, text.length() - sampleLength)
        };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int start : starts) {
                String sample = safeSlice(text, start, Math.min(text.length(), start + sampleLength));
                assertAnchorsMatchRenderedPages(sample);
            }
        });
    }

    @Test
    public void realFixture_firstFourThousandLineSeamUsesExpectedHandoffTarget() throws Exception {
        assumeRealFixtureAvailable();
        String text = FileUtils.readTextFile(copyRealFixtureToCache());
        String[] lines = text.split("\n", -1);
        assertTrue("real fixture should have enough lines for the 4000-line seam", lines.length > 4400);

        String firstWindow = joinLines(lines, 0, 4400);
        String secondWindow = joinLines(lines, 4000, 4400);
        int bodyEnd = joinLines(lines, 0, 4000).length();
        int secondBase = bodyEnd + 1;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView first = laidOutReaderView();
            first.setOverlapLines(0);
            first.setTextContent(firstWindow);

            int nextStart = -1;
            int guard = first.getTotalPageCount() + 2;
            while (guard-- > 0) {
                nextStart = first.getCharPositionForNextPageStartRespectingOverlap();
                if (nextStart >= bodyEnd) break;
                first.pageForwardWithoutSkippingContent();
            }
            assertTrue("real fixture should reach first 4000-line body seam", nextStart >= bodyEnd);

            int targetAbs = LargeTextContinuityMath.forwardHandoffTargetAbs(0, nextStart, bodyEnd);
            int expectedSecondLocal = targetAbs - secondBase;
            assertTrue("handoff target should land inside the next real fixture window",
                    expectedSecondLocal >= 0 && expectedSecondLocal < secondWindow.length());

            CustomReaderView second = laidOutReaderView();
            second.setOverlapLines(0);
            second.setTextContent(secondWindow);
            second.scrollToCharPosition(expectedSecondLocal);

            assertEquals(expectedSecondLocal, second.getCurrentCharPosition());
        });
    }

    @Test
    public void realFixture_tailPagesMoveForwardAndBackwardConsistently() throws Exception {
        assumeRealFixtureAvailable();
        String text = FileUtils.readTextFile(copyRealFixtureToCache());
        String tail = safeSlice(text, Math.max(0, text.length() - 100_000), text.length());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomReaderView view = laidOutReaderView();
            view.setOverlapLines(0);
            view.setTextContent(tail);

            int total = view.getTotalPageCount();
            assertTrue("real fixture tail should span multiple pages", total > 3);

            int[] starts = new int[total];
            for (int page = 1; page <= total; page++) {
                view.scrollToPage(page);
                starts[page - 1] = view.getCurrentCharPosition();
            }

            for (int i = 1; i < starts.length; i++) {
                assertTrue("tail page starts must be strictly increasing", starts[i] > starts[i - 1]);
            }

            view.scrollToPage(total);
            for (int page = total; page > Math.max(1, total - 6); page--) {
                view.pageBackwardWithoutSkippingContent();
                assertEquals(starts[page - 2], view.getCurrentCharPosition());
            }
        });
    }

    private static CustomReaderView laidOutReaderView() {
        CustomReaderView view = new CustomReaderView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        view.setReaderStyle(
                18f,
                1.35f,
                Color.BLACK,
                Color.WHITE,
                24,
                16,
                Typeface.DEFAULT);
        int width = 720;
        int height = 960;
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        return view;
    }

    private static void assertAnchorsMatchRenderedPages(String content) {
        CustomReaderView view = laidOutReaderView();
        view.setOverlapLines(0);
        view.setTextContent(content);

        ArrayList<CustomReaderView.PageTextAnchor> anchors =
                CustomReaderView.buildPageTextAnchors(
                        content,
                        view.copyTextPaintForIndex(),
                        view.getTextLayoutWidthForIndex(),
                        view.getViewportHeight(),
                        view.getMarginVerticalPxForIndex(),
                        view.getOverlapLinesForIndex(),
                        view.getLineSpacingMultiplierForIndex());

        int total = view.getTotalPageCount();
        assertEquals("exact anchor count must match rendered page count", total, anchors.size());
        for (int page = 1; page <= total; page++) {
            view.scrollToPage(page);
            assertEquals("page " + page + " start char must match exact anchor",
                    anchors.get(page - 1).charPosition,
                    view.getCurrentCharPosition());
        }
    }

    private static File copyRealFixtureToCache() throws IOException {
        Context assetContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File cacheDir = targetContext.getCacheDir();
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create instrumentation cache dir: " + cacheDir);
        }
        File out = new File(cacheDir, REAL_FIXTURE_ASSET);
        if (out.isFile() && out.length() > 0) return out;

        try (InputStream in = assetContext.getAssets().open(REAL_FIXTURE_ASSET);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                fos.write(buffer, 0, read);
            }
        }
        return out;
    }

    private static void assumeRealFixtureAvailable() {
        assumeTrue("local-only real TXT fixture is absent", realFixtureAssetAvailable());
    }

    private static boolean realFixtureAssetAvailable() {
        Context assetContext = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream ignored = assetContext.getAssets().open(REAL_FIXTURE_ASSET)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String safeSlice(String text, int start, int end) {
        int safeStart = Math.max(0, Math.min(text.length(), start));
        int safeEnd = Math.max(safeStart, Math.min(text.length(), end));
        safeStart = FileUtils.clampToSurrogateSafeStart(text, safeStart);
        safeEnd = FileUtils.clampToSurrogateSafeEnd(text, safeEnd);
        return text.substring(safeStart, safeEnd);
    }

    private static int countHangul(String text, int limit) {
        int count = 0;
        int end = Math.min(text.length(), Math.max(0, limit));
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '\uAC00' && c <= '\uD7A3') count++;
        }
        return count;
    }

    private static int countOccurrences(String text, String needle, int limit) {
        int count = 0;
        int end = Math.min(text.length(), Math.max(0, limit));
        int idx = 0;
        while (idx >= 0 && idx < end) {
            idx = text.indexOf(needle, idx);
            if (idx < 0 || idx >= end) break;
            count++;
            idx += Math.max(1, needle.length());
        }
        return count;
    }

    private static String numberedLines(int count, String prefix) {
        return joinLines(generatedLines(count, prefix), 0, count);
    }

    private static String[] generatedLines(int count, String prefix) {
        String[] lines = new String[count];
        for (int i = 0; i < count; i++) {
            lines[i] = prefix + " line " + i
                    + " abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ "
                    + "0123456789 repeated text for stable wrapping.";
        }
        return lines;
    }

    private static String joinLines(String[] lines, int start, int endExclusive) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < endExclusive; i++) {
            if (i > start) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }
}
