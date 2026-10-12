package com.readwide.manager.util;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Source-only regressions; execution is left to the maintainer. */
public class TxtContentsScannerTest {
    private List<TxtContentsScanner.Entry> scan(String text) {
        TxtContentsScanner scanner = new TxtContentsScanner();
        scanner.append(text);
        return scanner.finish();
    }

    @Test public void exactUtf16OffsetsIncludeIndentationAndSupplementaryCharacters() {
        String text = "😀 intro\n\n  Chapter 1: Start\n\nbody\n\nChapter 2: End";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(2, entries.size());
        assertEquals(text.indexOf("Chapter 1"), entries.get(0).position);
        assertEquals(text.indexOf("Chapter 2"), entries.get(1).position);
    }

    @Test public void chunkBoundariesDoNotChangeCoordinatesOrHeadings() {
        String text = "Chapter I\n\nbody\n\nChapter II\n\n";
        TxtContentsScanner scanner = new TxtContentsScanner();
        for (int i = 0; i < text.length(); i++) scanner.append(text.substring(i, i + 1));
        List<TxtContentsScanner.Entry> entries = scanner.finish();
        assertEquals(2, entries.size());
        assertEquals(text.indexOf("Chapter II"), entries.get(1).position);
    }

    @Test public void rejectsOrdinaryNumberedListsAndAnIsolatedFamily() {
        assertTrue(scan("1. Apple\n\n2. Pear\n\nChapter 12\n\nbody").isEmpty());
    }

    @Test public void requiresBlankBoundariesAroundHeadingLines() {
        assertTrue(scan("Chapter 1\nbody\n\nChapter 2\nbody\n").isEmpty());
        assertTrue(scan("body\nChapter 1\n\nbody\nChapter 2\n\n").isEmpty());
    }

    @Test public void koreanAndCjkFamiliesRemainIndependent() {
        List<TxtContentsScanner.Entry> entries = scan("제12장 시작\n\n본문\n\n제13장 끝\n\n본문\n\n第十二章 始\n\n文\n\n第十三章 終");
        assertEquals(4, entries.size());
        assertEquals("第十三章 終", entries.get(3).title);
        assertTrue(scan("Chapter 1\n\n제2장").isEmpty());
    }

    @Test public void partsIndentFollowingChaptersWithoutSortingTheText() {
        List<TxtContentsScanner.Entry> entries = scan("Part I\n\nChapter 1\n\nbody\n\nPart II\n\nChapter 2\n\nbody");
        assertEquals(4, entries.size());
        assertEquals(0, entries.get(0).level);
        assertEquals(1, entries.get(1).level);
        assertEquals(0, entries.get(2).level);
        assertEquals(1, entries.get(3).level);
    }

    @Test public void longBodyLinesCannotBecomeTruncatedHeadings() {
        StringBuilder body = new StringBuilder("Chapter 1 ");
        for (int i = 0; i < 10000; i++) body.append('x');
        assertTrue(scan(body + "\n\nChapter 2\n\n").isEmpty());
    }

    @Test public void bomFullWidthSpacesAndFinalNewlinePreserveStartOffsets() {
        String text = "\uFEFFChapter 1\n\nbody\n\n\u3000Chapter 2\n";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).position);
        assertEquals(text.indexOf("Chapter 2"), entries.get(1).position);
    }

    @Test public void recognizesRepeatedNumberEqualsHeadingsWithOptionalTitles() {
        String text = "345===\n\nbody\n\n346 === Next chapter\n\nbody\n\n３４７\u3000===== 다음 장";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(3, entries.size());
        assertEquals("345===", entries.get(0).title);
        assertEquals(text.indexOf("346"), entries.get(1).position);
        assertEquals(text.indexOf("３４７"), entries.get(2).position);
    }

    @Test public void rejectsBareNumbersSeparatorsAndNumericComparisons() {
        assertTrue(scan("345\n\n346\n\n===\n\n====\n\n345=\n\n346==\n\n"
                + "345===678\n\n346 === 679\n\n347.5===\n\n348===Title\n\n349===").isEmpty());
    }

    @Test public void numberEqualsStillRequiresRepeatedFamilyAndBlankBoundaries() {
        assertTrue(scan("345===\n\nbody").isEmpty());
        assertTrue(scan("345===\nbody\ncontinues\n\n346===\nbody\ncontinues").isEmpty());
        assertTrue(scan("body\n345===\n\nbody\n346===\n\n").isEmpty());
        assertTrue(scan("Chapter 1\n\n345===").isEmpty());
    }

    @Test public void numberEqualsSurvivesChunkSplitsWithoutChangingOffsets() {
        String text = "😀 intro\n\n  345===\n\ntext\n\n346 === Title\n";
        TxtContentsScanner scanner = new TxtContentsScanner();
        for (int i = 0; i < text.length(); i++) scanner.append(text.substring(i, i + 1));
        List<TxtContentsScanner.Entry> entries = scanner.finish();
        assertEquals(2, entries.size());
        assertEquals(text.indexOf("345"), entries.get(0).position);
        assertEquals(text.indexOf("346"), entries.get(1).position);
    }

    @Test public void numberEqualsEntriesFollowTheExistingPartHierarchy() {
        List<TxtContentsScanner.Entry> entries = scan("Part I\n\n345===\n\nbody\n\nPart II\n\n346===\n\nbody");
        assertEquals(4, entries.size());
        assertEquals(1, entries.get(1).level);
        assertEquals(1, entries.get(3).level);
    }

    @Test public void recognizesNumberHyphensWithTitlesAndChunkSafeOffsets() {
        String text = "😀 intro\n\n  345---\n\nbody\n\n346 --- Next chapter\n\nbody\n\n３４７\u3000----- 다음 장";
        TxtContentsScanner scanner = new TxtContentsScanner();
        for (int i = 0; i < text.length(); i++) scanner.append(text.substring(i, i + 1));
        List<TxtContentsScanner.Entry> entries = scanner.finish();
        assertEquals(3, entries.size());
        assertEquals("345---", entries.get(0).title);
        assertEquals(text.indexOf("345"), entries.get(0).position);
        assertEquals(text.indexOf("346"), entries.get(1).position);
        assertEquals(text.indexOf("３４７"), entries.get(2).position);
    }

    @Test public void numberHyphensRejectsDatesExpressionsAndMixedDelimiters() {
        assertTrue(scan("345\n\n---\n\n345-\n\n346--\n\n2026-10-11\n\n"
                + "345---678\n\n346 --- 679\n\n347.5---\n\n348---Title\n\n"
                + "349===---\n\n350---===\n\n351-=--\n\n352---").isEmpty());
    }

    @Test public void numberHyphensRequiresItsOwnRepeatedFamilyAndBlankBoundaries() {
        assertTrue(scan("345---\n\nbody").isEmpty());
        assertTrue(scan("345---\nbody\ncontinues\n\n346---\nbody\ncontinues").isEmpty());
        assertTrue(scan("body\n345---\n\nbody\n346---\n\n").isEmpty());
        assertTrue(scan("345===\n\n346---").isEmpty());
        List<TxtContentsScanner.Entry> entries = scan("Part I\n\n345---\n\nbody\n\nPart II\n\n346---\n\nbody");
        assertEquals(4, entries.size());
        assertEquals(1, entries.get(1).level);
        assertEquals(1, entries.get(3).level);
    }

    @Test public void twoLineEqualsTitleUsesMarkerPositionAndRealAnchor() {
        String text = "😀 intro\n\n  345====================\n거대 마석의 정체\n\n본문\n\n346===\n다음 장";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(2, entries.size());
        assertEquals("345 — 거대 마석의 정체", entries.get(0).title);
        assertEquals("346 — 다음 장", entries.get(1).title);
        for (TxtContentsScanner.Entry entry : entries) {
            assertEquals("auto:" + entry.position, entry.id);
            assertTrue(text.startsWith(entry.after, entry.position));
        }
        assertEquals(text.indexOf("345"), entries.get(0).position);
        assertEquals(text.indexOf("346"), entries.get(1).position);
    }

    @Test public void twoLineHyphenTitlesSurviveEveryChunkBoundaryCrLfAndEof() {
        String text = "\uFEFF ３４５--- \r\n\u3000첫 제목\r\n\r\n본문\r\n\r\n346 ---\r\n  Next title";
        for (int split = 0; split <= text.length(); split++) {
            TxtContentsScanner scanner = new TxtContentsScanner();
            scanner.append(text.substring(0, split));
            scanner.append(text.substring(split));
            List<TxtContentsScanner.Entry> entries = scanner.finish();
            assertEquals(2, entries.size());
            assertEquals("３４５ — 첫 제목", entries.get(0).title);
            assertEquals("346 — Next title", entries.get(1).title);
            assertEquals(text.indexOf("３４５"), entries.get(0).position);
            assertEquals(text.indexOf("346"), entries.get(1).position);
            for (TxtContentsScanner.Entry entry : entries) assertTrue(text.startsWith(entry.after, entry.position));
        }
    }

    @Test public void twoLineBlocksStillRequireOuterBlankBoundaries() {
        assertTrue(scan("body\n345===\nTitle\n\nbody\n346===\nTitle\n\n").isEmpty());
        assertTrue(scan("345===\nTitle\nbody\n\n346===\nTitle\nbody\n").isEmpty());
        assertTrue(scan("345===\nTitle\n\n").isEmpty());
        assertTrue(scan("345===\nTitle\n\n346---\nTitle").isEmpty());
    }

    @Test public void twoLineHeadingCannotConsumeAnotherHeadingOrSeparator() {
        assertTrue(scan("345===\nChapter 1\n\n346===\nChapter 2\n").isEmpty());
        assertTrue(scan("345===\n===\n\n346===\n===\n").isEmpty());
        assertTrue(scan("345---\n346---\n\n347---\n348---\n").isEmpty());
    }

    @Test public void singleLineTitleCannotConsumeFollowingBody() {
        assertTrue(scan("345=== First\nbody\n\n346=== Second\nbody\n").isEmpty());
        List<TxtContentsScanner.Entry> entries = scan("345=== First\n\nbody\n\n346===\nSecond\n");
        assertEquals(2, entries.size());
        assertEquals("345=== First", entries.get(0).title);
        assertEquals("346 — Second", entries.get(1).title);
    }

    @Test public void twoLineLabelBoundDoesNotTruncateATitleOrItsCoordinates() {
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < TxtContentsScanner.MAX_TITLE - "345 — ".length(); i++) title.append('a');
        String text = "345===\n" + title + "\n\n346===\nEnd";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(2, entries.size());
        assertEquals(TxtContentsScanner.MAX_TITLE, entries.get(0).title.length());
        assertEquals(120, entries.get(0).after.length());
        assertTrue(text.startsWith(entries.get(0).after, entries.get(0).position));
        assertTrue(scan("345===\n" + title + "a\n\n346===\nEnd").isEmpty());
    }

    @Test public void strongDecoratedSequenceRejectsSparseKoreanAuthorNotes() {
        String text = "345====================\n거대 마석의 정체\n\n본문\n\n"
                + "500화 축하 코멘트도 정말 감사합니다. 앞으로도 완결까지 열심히 쓰겠습니다.\n\n"
                + "346===\n다음 장\n\n본문\n\n"
                + "800화 축하 감사합니다. 완결까지 힘내겠습니다.\n\n"
                + "347===\n마지막 장\n\n본문";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(3, entries.size());
        assertEquals("345 — 거대 마석의 정체", entries.get(0).title);
        assertEquals("346 — 다음 장", entries.get(1).title);
        assertEquals("347 — 마지막 장", entries.get(2).title);
    }

    @Test public void sequentialKoreanHeadingsAndPartsSurviveDecoratedPreference() {
        String text = "제1부\n\n345===\nTitle\n\n346===\nTitle\n\n347===\nTitle\n\n"
                + "제3부\n\n제500화 First\n\n제501화 Second";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(7, entries.size());
        assertEquals(0, entries.get(4).level);
        assertEquals("제500화 First", entries.get(5).title);
        assertEquals(1, entries.get(5).level);
    }

    @Test public void weakOrAbsentDecoratedEvidenceDoesNotSuppressOtherFamilies() {
        String sparse = "500화 First\n\n800화 Second\n\n";
        assertEquals(2, scan(sparse).size());
        assertEquals(4, scan(sparse + "345===\n\n346===").size());
        assertEquals(5, scan(sparse + "345===\n\n347===\n\n349===").size());
        assertEquals(6, scan(sparse + "900화 Third\n\n345===\n\n346===\n\n347===").size());
    }

    @Test public void numericEvidenceHandlesFullWidthLeadingZerosAndOverflow() {
        String sparse = "500화 First\n\n800화 Second\n\n";
        assertEquals(3, scan(sparse + "００１---\n\n002---\n\n３---").size());
        assertEquals(5, scan(sparse + "9223372036854775807===\n\n9223372036854775808===\n\n9223372036854775809===").size());
        assertEquals(5, scan(sparse + "99999999999999999999===\n\n0===\n\n1===").size());
    }

    @Test public void nondecimalChapterFamiliesSurviveDecoratedPreference() {
        List<TxtContentsScanner.Entry> entries = scan("345===\n\n346===\n\n347===\n\n"
                + "Chapter I\n\nChapter V\n\n第十二章 始\n\n第十五章 終");
        assertEquals(7, entries.size());
    }

    @Test public void allTenThousandHeadingsRemainAvailableBeyondChapter760() {
        StringBuilder text = new StringBuilder();
        for (int chapter = 1; chapter <= 10000; chapter++) {
            text.append(chapter).append("===\nTitle\n\nbody\n\n");
        }
        TxtContentsScanner scanner = new TxtContentsScanner();
        for (int start = 0; start < text.length(); start += 4096) {
            scanner.append(text.subSequence(start, Math.min(start + 4096, text.length())));
        }
        List<TxtContentsScanner.Entry> entries = scanner.finish();
        assertEquals(10000, entries.size());
        for (int chapter : new int[] {1, 760, 761, 1571, 9999, 10000}) {
            TxtContentsScanner.Entry entry = entries.get(chapter - 1);
            assertEquals(chapter + " — Title", entry.title);
            assertEquals(text.indexOf(chapter + "===\n"), entry.position);
        }
    }

    @Test public void angleWrappedTitlesKeepOriginalAnchorsAcrossChunkSplits() {
        String text = "😀 intro\r\n\r\n  <--첫 제목-->\r\n\r\nbody\r\n\r\n<--\u3000다음 제목\u3000-->";
        for (int split = 0; split <= text.length(); split++) {
            TxtContentsScanner scanner = new TxtContentsScanner();
            scanner.append(text.substring(0, split));
            scanner.append(text.substring(split));
            List<TxtContentsScanner.Entry> entries = scanner.finish();
            assertEquals(2, entries.size());
            assertEquals("첫 제목", entries.get(0).title);
            assertEquals("다음 제목", entries.get(1).title);
            assertEquals(text.indexOf("<--첫"), entries.get(0).position);
            assertEquals(text.indexOf("<--\u3000"), entries.get(1).position);
            for (TxtContentsScanner.Entry entry : entries) {
                assertEquals("auto:" + entry.position, entry.id);
                assertTrue(text.startsWith(entry.after, entry.position));
            }
        }
    }

    @Test public void headingStyleChangeAfter760KeepsAllRemainingChapters() {
        StringBuilder text = new StringBuilder();
        for (int chapter = 1; chapter <= 1571; chapter++) {
            if (chapter <= 760) text.append(chapter).append("===\nTitle");
            else text.append("<--Title ").append(chapter).append("-->");
            text.append("\n\nbody\n\n");
        }
        List<TxtContentsScanner.Entry> entries = scan(text.toString());
        assertEquals(1571, entries.size());
        assertEquals("760 — Title", entries.get(759).title);
        assertEquals("Title 761", entries.get(760).title);
        assertEquals(text.indexOf("<--Title 761-->"), entries.get(760).position);
        assertEquals("Title 1571", entries.get(1570).title);
    }

    @Test public void angleWrappedTitlesRequireRepetitionAndBlankBoundaries() {
        assertTrue(scan("<--Only title-->\n\nbody").isEmpty());
        assertTrue(scan("body\n<--First-->\n\nbody\n<--Second-->\n").isEmpty());
        assertTrue(scan("<--First-->\nbody\n\n<--Second-->\nbody").isEmpty());
        assertEquals(2, scan("345===\n\n346===\n\n<--Only title-->").size());
    }

    @Test public void angleWrappedTitlesRejectMarkupEmptyAndInlineDecorations() {
        assertTrue(scan("<---->\n\n<--   -->\n\n<--===-->\n\n"
                + "<!--First-->\n\n<!--Second-->\n\n<--<b>First</b>-->\n\n"
                + "<--<b>Second</b>-->\n\nintro <--First-->\n\n<--Second--> ending\n\n"
                + "<-First->\n\n<-Second->\n\n<--First--><--Second-->").isEmpty());
    }

    @Test public void angleWrappedTitlesSurviveNumericPreferenceAndPartHierarchy() {
        List<TxtContentsScanner.Entry> entries = scan("Part I\n\n758===\n\n759===\n\n760===\n\n"
                + "Part II\n\n<--761: First-->\n\n<--‘Second’-->\n\n"
                + "500화 감사합니다.\n\n800화 감사합니다.");
        assertEquals(7, entries.size());
        assertEquals("761: First", entries.get(5).title);
        assertEquals("‘Second’", entries.get(6).title);
        assertEquals(1, entries.get(5).level);
        assertEquals(1, entries.get(6).level);
    }

    @Test public void oversizedWrappedLineDoesNotCreateATruncatedHeading() {
        StringBuilder title = new StringBuilder("<--");
        for (int i = 0; i < TxtContentsScanner.MAX_TITLE; i++) title.append('a');
        title.append("-->");
        assertTrue(scan(title + "\n\n<--Second-->").isEmpty());
    }

    @Test public void koreanEpisodesAcceptBareAndInlineNumberedForms() {
        String text = "761화\n\n본문\n\n제762화\n\n본문\n\n763화 새 제목\n\n"
                + "제 764 화: 다음 제목\n\n７６５화 - 마지막 제목";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(5, entries.size());
        assertEquals("761화", entries.get(0).title);
        assertEquals("제762화", entries.get(1).title);
        assertEquals("763화 새 제목", entries.get(2).title);
        assertEquals("제 764 화: 다음 제목", entries.get(3).title);
        assertEquals("７６５화 - 마지막 제목", entries.get(4).title);
        for (TxtContentsScanner.Entry entry : entries) assertTrue(text.startsWith(entry.after, entry.position));
    }

    @Test public void twoLineKoreanEpisodesKeepNumberLineAndExactSourceAnchor() {
        String text = "😀 intro\r\n\r\n  761화\r\n첫 제목\r\n\r\n본문\r\n\r\n제762화\r\n\u3000다음 제목";
        for (int split = 0; split <= text.length(); split++) {
            TxtContentsScanner scanner = new TxtContentsScanner();
            scanner.append(text.substring(0, split));
            scanner.append(text.substring(split));
            List<TxtContentsScanner.Entry> entries = scanner.finish();
            assertEquals(2, entries.size());
            assertEquals("761화 — 첫 제목", entries.get(0).title);
            assertEquals("제762화 — 다음 제목", entries.get(1).title);
            assertEquals(text.indexOf("761화"), entries.get(0).position);
            assertEquals(text.indexOf("제762화"), entries.get(1).position);
            for (TxtContentsScanner.Entry entry : entries) {
                assertEquals("auto:" + entry.position, entry.id);
                assertTrue(text.startsWith(entry.after, entry.position));
            }
        }
    }

    @Test public void koreanEpisodesStillRejectInlineMentionsAndContinuingParagraphs() {
        assertTrue(scan("761화까지 읽었다\n\n762화도 읽었다\n\n761화재\n\n762화면").isEmpty());
        assertTrue(scan("본문의 761화\n\n본문의 762화").isEmpty());
        assertTrue(scan("본문\n761화\n제목\n\n본문\n762화\n제목").isEmpty());
        assertTrue(scan("761화\n제목\n본문\n\n762화\n제목\n본문").isEmpty());
        assertTrue(scan("761화 제목\n본문\n\n762화 제목\n본문").isEmpty());
        assertTrue(scan("761화\n제목\n\n본문").isEmpty());
    }

    @Test public void numberedStylesCanChangeToTwoLineKoreanEpisodes() {
        String text = "758===\n\n759===\n\n760===\n\n761화\n첫 제목\n\n제762화\n다음 제목";
        List<TxtContentsScanner.Entry> entries = scan(text);
        assertEquals(5, entries.size());
        assertEquals("761화 — 첫 제목", entries.get(3).title);
        assertEquals("제762화 — 다음 제목", entries.get(4).title);
    }

    private static List<TxtContentsScanner.Entry> scan(String text, TxtContentsOptions options) {
        TxtContentsScanner scanner = new TxtContentsScanner(options);
        scanner.append(text);
        return scanner.finish();
    }

    @Test public void formatSelectionKeepsOnlyEnabledFamilies() {
        String text = "Chapter 1\n\nChapter 2\n\n761화\n\n762화\n\n1===\n\n2===\n\n"
                + "1---\n\n2---\n\n<--First-->\n\n<--Second-->\n\n第1章\n\n第2章";
        for (int format : new int[] { TxtContentsOptions.ENGLISH, TxtContentsOptions.KOREAN,
                TxtContentsOptions.CJK, TxtContentsOptions.EQUALS, TxtContentsOptions.HYPHENS, TxtContentsOptions.WRAPPED }) {
            assertEquals(2, scan(text, new TxtContentsOptions(format, true, true, true, true, "")).size());
        }
        assertTrue(scan(text, new TxtContentsOptions(0, true, true, true, true, "")).isEmpty());
    }

    @Test public void boundaryAndRepetitionConditionsAreIndependent() {
        String text = "body\nChapter 1\nChapter 2\nbody";
        assertTrue(scan(text).isEmpty());
        assertEquals(2, scan(text, new TxtContentsOptions(63, false, true, true, false, "")).size());
        assertTrue(scan("Chapter 1", TxtContentsOptions.DEFAULT).isEmpty());
        assertEquals(1, scan("Chapter 1", new TxtContentsOptions(63, true, false, true, true, "")).size());
    }

    @Test public void combineTitleCanBeDisabledWithoutChangingMarkerPosition() {
        String text = "761화\n첫 제목\n\n762화\n다음 제목";
        List<TxtContentsScanner.Entry> separate = scan(text, new TxtContentsOptions(63, false, true, true, false, ""));
        assertEquals(2, separate.size());
        assertEquals("761화", separate.get(0).title);
        assertEquals(text.indexOf("762화"), separate.get(1).position);
        assertTrue(scan(text, new TxtContentsOptions(63, true, true, true, false, "")).isEmpty());
    }

    @Test public void prosePreferenceCanBeDisabled() {
        String text = "1===\n\n2===\n\n3===\n\n500화 감사합니다\n\n800화 감사합니다";
        assertEquals(3, scan(text).size());
        assertEquals(5, scan(text, new TxtContentsOptions(63, true, true, false, true, "")).size());
    }

    @Test public void customCaptureKeepsSourceAnchorsAndWholeLineMatching() {
        String text = "intro [Ignored]\n\n  [First]\n\n[Second]";
        TxtContentsOptions options = new TxtContentsOptions(TxtContentsOptions.CUSTOM, true, true, true, true, "^\\[(.+)\\]$");
        for (int split = 0; split <= text.length(); split++) {
            TxtContentsScanner scanner = new TxtContentsScanner(options);
            scanner.append(text.substring(0, split)); scanner.append(text.substring(split));
            List<TxtContentsScanner.Entry> entries = scanner.finish();
            assertEquals(2, entries.size());
            assertEquals("First", entries.get(0).title);
            assertEquals(text.indexOf("[First]"), entries.get(0).position);
            assertEquals("Second", entries.get(1).title);
            for (TxtContentsScanner.Entry entry : entries) assertTrue(text.startsWith(entry.after, entry.position));
        }
    }

    @Test public void firstCustomRulePrecedesBuiltInsAndHasItsOwnRepetitionEvidence() {
        TxtContentsOptions options = new TxtContentsOptions(127, true, true, true, true,
                "^Chapter ([0-9]+)$\n^(Chapter .+)$\n^\\[(.+)\\]$");
        List<TxtContentsScanner.Entry> entries = scan("Chapter 1\n\n[Only]\n\nChapter 2", options);
        assertEquals(2, entries.size());
        assertEquals("1", entries.get(0).title);
        assertEquals("2", entries.get(1).title);
        assertEquals("[Only]", scan("[Only]", new TxtContentsOptions(64, true, false, true, true,
                "\\[.+\\]")).get(0).title);
    }

    @Test public void unusableCapturesFallThroughAndDisabledDraftDoesNotCompile() {
        TxtContentsOptions options = new TxtContentsOptions(64, true, false, true, true,
                "^(missing)?Title$\n^()Title$\n^(Title)$");
        assertEquals("Title", scan("Title", options).get(0).title);
        assertEquals("Title", scan("Title\u3000end", new TxtContentsOptions(64, true, false, true, true,
                "^Title(\u3000)end$\n^(Title)\u3000end$")).get(0).title);
        assertEquals(2, scan("Chapter 1\n\nChapter 2", new TxtContentsOptions(63, true, true, true, true,
                "(")).size());
    }

    @Test public void invalidCustomRuleReportsOriginalLineNumber() {
        for (String invalid : new String[] { "(", "(?=Title)Title", "(Title)\\1" }) {
            try {
                new TxtContentsScanner(new TxtContentsOptions(64, true, true, true, true, "\n^ok$\n" + invalid));
                fail("Unsupported expression must be rejected before scanning");
            } catch (TxtContentsOptions.InvalidPattern expected) { assertEquals(3, expected.line); }
        }
    }

    @Test public void optionKeysIncludeEveryPolicyAndNormalizeNewlines() {
        String base = TxtContentsOptions.DEFAULT.cacheKey();
        for (TxtContentsOptions changed : new TxtContentsOptions[] {
                new TxtContentsOptions(0, true, true, true, true, ""),
                new TxtContentsOptions(63, false, true, true, true, ""),
                new TxtContentsOptions(63, true, false, true, true, ""),
                new TxtContentsOptions(63, true, true, false, true, ""),
                new TxtContentsOptions(63, true, true, true, false, ""),
                new TxtContentsOptions(63, true, true, true, true, "draft") }) assertNotEquals(base, changed.cacheKey());
        assertEquals("a\nb\nc", new TxtContentsOptions(0, true, true, true, true, "a\r\nb\rc").customRules);
        try { new TxtContentsOptions(128, true, true, true, true, ""); fail(); }
        catch (IllegalArgumentException expected) { /* Unknown flags cannot silently affect detection. */ }
        char[] oversized = new char[TxtContentsOptions.MAX_CUSTOM_TEXT + 1];
        try { new TxtContentsOptions(0, true, true, true, true, new String(oversized)); fail(); }
        catch (IllegalArgumentException expected) { /* Bound the editable rule set, not chapter count. */ }
    }

    @Test public void bareKoreanEpisodeDoesNotConsumeAnotherRecognizedHeading() {
        assertTrue(scan("761화\n<--첫 제목-->\n\n762화\n<--다음 제목-->").isEmpty());
        assertTrue(scan("761화\n762화\n\n763화\n764화").isEmpty());
        assertTrue(scan("제1장\n첫 제목\n\n제2장\n다음 제목").isEmpty());
    }
}
