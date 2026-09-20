package com.readwide.manager.util;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class NaturalSortTest {
    @Test public void asciiNaturalOrderAndZeroPaddingArePreserved() {
        String[] names = {"page10", "page002", "page2", "page01", "page1"};
        Arrays.sort(names, NaturalSort::compare);
        assertArrayEquals(new String[]{"page1", "page01", "page2", "page002", "page10"}, names);
    }

    @Test public void arabicIndicDigitsDoNotCreateAnOrderingCycle() {
        assertTrue(NaturalSort.compare("١", "22") < 0);
        assertTrue(NaturalSort.compare("22", "c") < 0);
        assertTrue(NaturalSort.compare("١", "c") < 0);
    }

    @Test public void decimalScriptsShareNumericValuesAndZeroPadding() {
        assertEquals(0, NaturalSort.compare("x٠٢", "x02"));
        assertEquals(0, NaturalSort.compare("x１２", "x12"));
        assertTrue(NaturalSort.compare("x२", "x10") < 0);
        assertTrue(NaturalSort.compare("x２", "x０２") < 0);
    }

    @Test public void supplementaryDigitsCountAsOneDigit() {
        String one = new String(Character.toChars(0x1D7D9));
        String two = new String(Character.toChars(0x1D7DA));
        assertEquals(0, NaturalSort.compare(one + two, "12"));
        assertTrue(NaturalSort.compare(two, "10") < 0);
    }

    @Test public void comparatorContractHoldsAcrossMixedScriptsAndPrefixes() {
        String[] values = {null, "", "0", "00", "٠", "١", "22", "c", "C", "/", ":",
                "a", "a1", "a01", "a٠١", "a2", "a10", "١a", "1a", "１", "२", "-1", "😀"};
        for (String a : values) for (String b : values) {
            int ab = Integer.signum(NaturalSort.compare(a, b));
            assertEquals(-ab, Integer.signum(NaturalSort.compare(b, a)));
            for (String c : values) {
                int bc = NaturalSort.compare(b, c), ac = NaturalSort.compare(a, c);
                if (ab <= 0 && bc <= 0) assertTrue("Transitivity", ac <= 0);
                if (ab == 0) assertEquals("Equivalent ordering", Integer.signum(bc), Integer.signum(ac));
            }
        }
    }
}
