package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EpubSpreadSlotMathTest {

    @Test
    public void ltrPlacesPrimaryLeftAndSecondaryRight() {
        assertEquals(EpubSpreadSlotMath.PHYSICAL_LEFT,
                EpubSpreadSlotMath.physicalSlot(4, 4, 5, false));
        assertEquals(EpubSpreadSlotMath.PHYSICAL_RIGHT,
                EpubSpreadSlotMath.physicalSlot(5, 4, 5, false));
    }

    @Test
    public void rtlReversesThePhysicalSlots() {
        assertEquals(EpubSpreadSlotMath.PHYSICAL_RIGHT,
                EpubSpreadSlotMath.physicalSlot(4, 4, 5, true));
        assertEquals(EpubSpreadSlotMath.PHYSICAL_LEFT,
                EpubSpreadSlotMath.physicalSlot(5, 4, 5, true));
    }

    @Test
    public void missingSecondaryKeepsStandalonePageCentered() {
        assertEquals(EpubSpreadSlotMath.CENTER,
                EpubSpreadSlotMath.physicalSlot(4, 4, -1, false));
        assertEquals(EpubSpreadSlotMath.CENTER,
                EpubSpreadSlotMath.physicalSlot(4, 4, -1, true));
    }

    @Test
    public void unrelatedPageIsCentered() {
        assertEquals(EpubSpreadSlotMath.CENTER,
                EpubSpreadSlotMath.physicalSlot(9, 4, 5, false));
    }

    @Test
    public void invalidOrDuplicatePairIsCentered() {
        assertEquals(EpubSpreadSlotMath.CENTER,
                EpubSpreadSlotMath.physicalSlot(5, -1, 5, false));
        assertEquals(EpubSpreadSlotMath.CENTER,
                EpubSpreadSlotMath.physicalSlot(5, 5, 5, false));
    }
}
