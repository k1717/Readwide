package com.readwide.manager.util;

import static org.junit.Assert.*;
import org.junit.Test;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

public class SearchNormalizationRound2Test {
    private SearchMatcher matcher(String query) { return SearchMatcher.compile(query, new SearchOptions(true, false, false, true)); }
    private void span(String query, String text, int start, int end) {
        SearchMatcher.Match hit = matcher(query).firstFrom(text, 0);
        assertNotNull(hit); assertEquals(start, hit.start); assertEquals(end, hit.end);
    }
    @Test public void compensatingContractionAndExpansionDoNotShiftInteriorHit() {
        span("X", "a\u0301 X \u0344", 3, 4);
    }
    @Test public void composedQueryMatchesDecomposedTextAndCoversAccent() { span("é", "cafe\u0301!", 3, 5); }
    @Test public void decomposedQueryMatchesComposedText() { span("e\u0301", "café!", 3, 4); }
    @Test public void hangulJamoComposeWithOriginalSpan() { span("각", "x\u1100\u1161\u11a8y", 1, 4); }
    @Test public void hangulLvAndTrailingJamoCompose() { span("각", "x가\u11a8y", 1, 3); }
    @Test public void expansionReturnsOneOriginalCharacterSpan() { span("\u0308\u0301", " \u0344z", 1, 2); }
    @Test public void reorderedCombiningMarksCoverTheirSourceSegment() {
        span("\u0323\u0301", "prefix q\u0301\u0323z", 8, 10);
        span("z", "prefix q\u0301\u0323z", 10, 11);
    }
    @Test public void supplementaryPrefixDoesNotMoveOffsets() { span("é", "😀e\u0301!", 2, 4); }
    @Test public void unchangedAsciiAfterTransformedSequenceKeepsExactSpan() { span("b", "a\u0301 bc", 3, 4); }
    @Test public void normalizationOffRetainsLiteralBehavior() {
        assertNull(SearchMatcher.compile("é", SearchOptions.literal()).firstFrom("e\u0301", 0));
    }
    @Test public void caseInsensitiveNormalizedSearchAndWholeWordUseOriginalText() {
        SearchMatcher matcher = SearchMatcher.compile("CAFÉ", new SearchOptions(false, true, false, true));
        SearchMatcher.Match hit = matcher.firstFrom("xcafe\u0301 cafe\u0301!", 0);
        assertNotNull(hit); assertEquals(7, hit.start); assertEquals(12, hit.end);
    }
    @Test public void normalizedOverlapAndOrdinalsStayConsistent() {
        SearchMatcher m = matcher("éé"); String t = "e\u0301e\u0301e\u0301";
        assertEquals(2, m.count(t)); assertEquals(2, m.nthStart(t, 2));
        assertEquals(2, m.firstFrom(t, 1).start); assertEquals(2, m.lastUpTo(t, 5).start);
        assertEquals(0, m.lastUpTo(t, 1).start);
    }
    @Test public void rangesInOriginalCoordinatesKeepCrossBandTail() {
        SearchMatcher m = matcher("éx"); String t = "e\u0301x e\u0301x";
        List<String> hits = new ArrayList<>();
        m.prepareText(t).forEachInRange(4, 5, (s,e) -> { hits.add(s+":"+e); return true; });
        assertEquals(Arrays.asList("4:7"), hits);
    }
    @Test public void regexWithNormalizationNeverRequestsUnsupportedAndroidFlag() throws Exception {
        SearchMatcher m = SearchMatcher.compile("a+", new SearchOptions(true, false, true, true));
        java.lang.reflect.Field field = SearchMatcher.class.getDeclaredField("regexPattern"); field.setAccessible(true);
        assertEquals(0, ((Pattern) field.get(m)).flags() & Pattern.CANON_EQ);
        assertEquals(2, m.count("aaa aa"));
    }
    @Test public void regexSyntaxAndOriginalTextAreNotSilentlyRewritten() {
        SearchMatcher m = SearchMatcher.compile("e\\u0301", new SearchOptions(true, false, true, true));
        assertEquals(1, m.count("é e\u0301")); assertEquals(2, m.firstFrom("é e\u0301", 0).start);
    }
    @Test public void literalNormalizationUsesNfcNotCompatibilityFolding() {
        assertNull(matcher("fi").firstFrom("\ufb01", 0));
        assertNull(matcher("a").firstFrom("á", 0));
    }
    @Test public void repeatedCanonicalOccurrencesKeepOriginalOrder() {
        String t="x e\u0301 café e\u0301";
        List<Integer> starts=new ArrayList<>(); matcher("é").forEachMatch(t,(s,e)->{starts.add(s);return true;});
        assertEquals(Arrays.asList(2,8,10),starts);
    }
    @Test public void randomizedNfcViewAndOriginalRangesAgree() {
        String[] alphabet={"a","b","q","é","\u0301","\u0323","\u0315","\u0344","\u212b",
                "\u1100","\u1161","\u11a8","가","😀","\u0f73","\u0958","\u1e0b","\u0307"," "};
        Random random=new Random(718230);
        for(int trial=0;trial<1500;trial++) {
            StringBuilder text=new StringBuilder(); for(int j=0,n=1+random.nextInt(25);j<n;j++)text.append(alphabet[random.nextInt(alphabet.length)]);
            String t=text.toString(); NormalizedSearchText view=NormalizedSearchText.nfc(t);
            assertEquals(Normalizer.normalize(t,Normalizer.Form.NFC),view.value);
            int priorStart=-1,priorEnd=-1;
            for(int i=0;i<view.value.length();i++) {
                int start=view.originalStart(i),end=view.originalEnd(i+1);
                assertTrue(start>=priorStart); assertTrue(end>=priorEnd); assertTrue(start<end); assertTrue(end<=t.length());
                priorStart=start; priorEnd=end;
            }
            String query=alphabet[random.nextInt(alphabet.length)]; SearchMatcher m=matcher(query);
            List<String> all=new ArrayList<>(); m.forEachMatch(t,(s,e)->{all.add(s+":"+e);return true;});
            assertEquals(all.size(),m.count(t));
            for (String hit : all) {
                String[] ends = hit.split(":");
                String sourceSpan = t.substring(Integer.parseInt(ends[0]), Integer.parseInt(ends[1]));
                assertTrue("mapped span loses query in trial=" + trial,
                        Normalizer.normalize(sourceSpan, Normalizer.Form.NFC).contains(Normalizer.normalize(query, Normalizer.Form.NFC)));
            }
            for(int from=0;from<=t.length();from++) {
                final int f=from,limit=Math.min(t.length(),from+3);
                List<String> expected=new ArrayList<>(); for(String hit:all){int s=Integer.parseInt(hit.split(":")[0]);if(s>=f&&s<limit)expected.add(hit);}
                List<String> actual=new ArrayList<>();m.prepareText(t).forEachInRange(from,limit,(s,e)->{actual.add(s+":"+e);return true;});
                assertEquals("range trial="+trial+" from="+from,expected,actual);
                SearchMatcher.Match first=m.firstFrom(t,from);
                String expectedFirst=null;for(String h:all){if(Integer.parseInt(h.split(":")[0])>=from){expectedFirst=h;break;}}
                assertEquals(expectedFirst,first==null?null:first.start+":"+first.end);
            }
        }
    }
}
