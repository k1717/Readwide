package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarStandardFiltersTest {
    @Test
    public void identifyBySignatureRecognizesKnownRarVmStandardFilters() {
        assertEquals(RarVmFilter.StandardFilter.E8,
                RarStandardFilters.identifyBySignature(53, 0xad576887L));
        assertEquals(RarVmFilter.StandardFilter.E8E9,
                RarStandardFilters.identifyBySignature(57, 0x3cd7e57eL));
        assertEquals(RarVmFilter.StandardFilter.ITANIUM,
                RarStandardFilters.identifyBySignature(120, 0x3769893fL));
        assertEquals(RarVmFilter.StandardFilter.DELTA,
                RarStandardFilters.identifyBySignature(29, 0x0e06077dL));
        assertEquals(RarVmFilter.StandardFilter.RGB,
                RarStandardFilters.identifyBySignature(149, 0x1c2c5dc8L));
        assertEquals(RarVmFilter.StandardFilter.AUDIO,
                RarStandardFilters.identifyBySignature(216, 0xbc85e701L));
        assertEquals(RarVmFilter.StandardFilter.UPCASE,
                RarStandardFilters.identifyBySignature(40, 0x46b9c560L));
    }

    @Test
    public void identifyBySignatureKeepsUnknownFiltersUnknown() {
        assertEquals(RarVmFilter.StandardFilter.UNKNOWN,
                RarStandardFilters.identifyBySignature(3, 0x12345678L));
    }

    @Test
    public void hasStandalonePrimitiveOnlyAllowsE8AndE8E9() {
        assertTrue(RarStandardFilters.hasStandalonePrimitive(RarVmFilter.StandardFilter.E8));
        assertTrue(RarStandardFilters.hasStandalonePrimitive(RarVmFilter.StandardFilter.E8E9));
        assertFalse(RarStandardFilters.hasStandalonePrimitive(RarVmFilter.StandardFilter.DELTA));
        assertFalse(RarStandardFilters.hasStandalonePrimitive(RarVmFilter.StandardFilter.UNKNOWN));
    }
}
