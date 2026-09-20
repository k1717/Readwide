package com.readwide.manager.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class SearchUnicodeReviewTest {
    private static final SearchOptions FOLD=new SearchOptions(false,false,false,false);
    private static final SearchOptions WORD=new SearchOptions(true,true,false,false);
    @Test public void finalSigmaMatchesOtherSigmaForms(){assertEquals(3,SearchMatcher.compile("σ",FOLD).count("Σσς"));}
    @Test public void longSMatchesAsciiS(){assertEquals(3,SearchMatcher.compile("s",FOLD).count("sSſ"));}
    @Test public void supplementaryCaseFoldingKeepsOriginalUtf16Offsets(){
        SearchMatcher.Match match=SearchMatcher.compile("\uD801\uDC00",FOLD).firstFrom("ab\uD801\uDC28z",0);
        assertNotNull(match);assertEquals(2,match.start);assertEquals(4,match.end);
    }
    @Test public void supplementaryLettersAreNotWordBoundaries(){
        assertEquals(0,SearchMatcher.compile("a",WORD).count("\uD801\uDC28a a\uD801\uDC28"));
    }
    @Test public void combiningMarksRemainAttachedToTheirWord(){assertEquals(0,SearchMatcher.compile("a",WORD).count("a\u0301"));}
    @Test public void regexWholeWordUsesTheSameUnicodeBoundaries(){
        assertEquals(0,SearchMatcher.compile("a",new SearchOptions(true,true,true,false)).count("a\u0301 a\uD801\uDC28"));
    }
    @Test public void caseSensitiveAndNonExpandingSearchRemainDistinct(){
        assertEquals(0,SearchMatcher.compile("σ",SearchOptions.literal()).count("ς"));
        assertEquals(0,SearchMatcher.compile("ss",FOLD).count("ß"));
    }
    @Test public void indexNavigationUsesOriginalSupplementaryPositions(){
        TextMatchIndex index=TextMatchIndex.build("x\uD801\uDC28y\uD801\uDC00","\uD801\uDC28",FOLD,null);
        assertEquals(2,index.count());assertEquals(1,index.occurrence(1,null).position);assertEquals(4,index.occurrence(2,null).position);
    }
}
