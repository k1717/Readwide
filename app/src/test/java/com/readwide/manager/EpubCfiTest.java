package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class EpubCfiTest {
    private static final String[] GEORGIA_CFIS = {
            "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/12[d10e85]/6[d10e93]/1:1552[Bryan,%20and])",
            "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/18[d10e150]/4[d10e155]/1:35)",
            "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/24[d10e209]/4[d10e214]/3:2180[for,%20taxation])",
            "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/26[d10e271]/4[d10e276]/3:1054)",
            "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/30[d10e304]/14[d10e345]/1:505)",
            "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/30[d10e304]/22[d10e386]/1:2032)",
            "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/30[d10e304]/34/2[d10e432]/1:0)"
    };

    private static final String[] TARGET_IDS = {
            "d10e93", "d10e155", "d10e214", "d10e276",
            "d10e345", "d10e386", "d10e432"
    };

    private static final int[] OFFSETS = {1552, 35, 2180, 1054, 505, 2032, 0};
    private static final int[] TEXT_GAPS = {0, 0, 1, 1, 0, 0, 0};

    @Test
    public void parsesAllGeorgiaPageListPointCfis() {
        for (int i = 0; i < GEORGIA_CFIS.length; i++) {
            EpubCfi cfi = EpubCfi.parse(GEORGIA_CFIS[i]);
            assertNotNull("Georgia CFI " + i, cfi);
            assertEquals("ct", cfi.itemRefIdAssertion());
            assertEquals(1, cfi.spineItemIndex());
            assertEquals(OFFSETS[i], cfi.characterOffset());

            List<EpubCfi.Step> steps = cfi.contentSteps();
            assertTrue(steps.size() >= 2);
            EpubCfi.Step parent = steps.get(steps.size() - 2);
            EpubCfi.Step text = steps.get(steps.size() - 1);
            assertEquals(TARGET_IDS[i], parent.idAssertion());
            assertTrue(parent.isElementStep());
            assertTrue(text.isTextStep());
            assertEquals(TEXT_GAPS[i], text.textGapIndex());
        }
    }

    @Test
    public void packageAssertionAndNumericSpineFallbackAreBothExposed() {
        EpubCfi asserted = EpubCfi.parse(
                "epubcfi(/6/4[chapter-itemref]!/4/2/1:0)");
        assertNotNull(asserted);
        assertEquals("chapter-itemref", asserted.itemRefIdAssertion());
        assertEquals(1, asserted.spineItemIndex());

        EpubCfi numericOnly = EpubCfi.parse(
                "epubcfi(/6/2!/4/2/1:0)");
        assertNotNull(numericOnly);
        assertEquals("", numericOnly.itemRefIdAssertion());
        assertEquals(0, numericOnly.spineItemIndex());
    }

    @Test
    public void oddStepsRepresentTextSlotsAroundInlineElements() {
        EpubCfi firstText = EpubCfi.parse("epubcfi(/6/2!/4/2/1:7)");
        EpubCfi afterFirstElement = EpubCfi.parse("epubcfi(/6/2!/4/2/3:11)");
        assertNotNull(firstText);
        assertNotNull(afterFirstElement);
        assertEquals(0, last(firstText).textGapIndex());
        assertEquals(1, last(afterFirstElement).textGapIndex());
    }

    @Test
    public void percentDecodesTextAssertionsWithoutFormPlusConversion() {
        EpubCfi cfi = EpubCfi.parse(GEORGIA_CFIS[0]);
        assertNotNull(cfi);
        assertTrue(cfi.hasTextAssertion());
        assertEquals("Bryan", cfi.textBefore());
        assertEquals(" and", cfi.textAfter());

        EpubCfi literalPlus = EpubCfi.parse(
                "epubcfi(/6/2[item+ref]!/4/2[p+id]/1:2[A+B,%20C])");
        assertNotNull(literalPlus);
        assertEquals("item+ref", literalPlus.itemRefIdAssertion());
        assertEquals("p+id", literalPlus.contentSteps().get(1).idAssertion());
        assertEquals("A+B", literalPlus.textBefore());
        assertEquals(" C", literalPlus.textAfter());
    }

    @Test
    public void caretEscapesAreRemovedFromAssertions() {
        EpubCfi cfi = EpubCfi.parse(
                "epubcfi(/6/2[item^]ref]!/4/2[p^^id]/1:0[left^,side,%20right])");
        assertNotNull(cfi);
        assertEquals("item]ref", cfi.itemRefIdAssertion());
        assertEquals("p^id", cfi.contentSteps().get(1).idAssertion());
        assertEquals("left,side", cfi.textBefore());
        assertEquals(" right", cfi.textAfter());
    }

    @Test
    public void rejectsMalformedRangeAndUnsupportedLocationForms() {
        assertNull(EpubCfi.parse(null));
        assertNull(EpubCfi.parse(""));
        assertNull(EpubCfi.parse("epubcfi(/6/2/4/2/1:0)")); // no indirection
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4/2/1:0!/2)")); // nested indirection
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4,/2,/6)")); // range
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4/1:0/2)")); // text node is not terminal
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4/2:3)")); // offset on element
        assertNull(EpubCfi.parse("epubcfi(/6/0!/4/2)"));
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4/2/1:2[open)"));
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4/2/1:2;s=b)"));
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4/2/1~1.5)"));
        assertNull(EpubCfi.parse("epubcfi(/6/2!/4/2/1@50:50)"));
    }

    @Test
    public void reportsElementTargetWhenThereIsNoCharacterOffset() {
        EpubCfi cfi = EpubCfi.parse("epubcfi(/6/2[chapter]!/4/2[target])");
        assertNotNull(cfi);
        assertFalse(cfi.hasCharacterOffset());
        assertEquals(-1, cfi.characterOffset());
        assertTrue(last(cfi).isElementStep());
    }

    private static EpubCfi.Step last(EpubCfi cfi) {
        List<EpubCfi.Step> steps = cfi.contentSteps();
        return steps.get(steps.size() - 1);
    }
}
