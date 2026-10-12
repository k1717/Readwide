package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

/** Boundary inputs only: no multi-gigabyte String or fixture allocation required. */
public class LargeTextCoordinateMathTest {
    @Test public void representableCoordinatesAreUnchanged() throws IOException {
        assertEquals(0, LargeTextCoordinateMath.checkedPosition(0L));
        assertEquals(Integer.MAX_VALUE,
                LargeTextCoordinateMath.checkedPosition(Integer.MAX_VALUE));
        assertEquals(4L, LargeTextCoordinateMath.advancePastLine(0L, 3));
        assertEquals(5L, LargeTextCoordinateMath.advancePastLine(4L, 0));
        assertEquals(10L, LargeTextCoordinateMath.advancePastLine(5L, 4));
    }

    @Test public void finalSyntheticSeparatorMayBePastTheLargestRealPosition() throws IOException {
        long pastEnd = (long) Integer.MAX_VALUE + 1L;
        assertEquals(pastEnd,
                LargeTextCoordinateMath.advancePastLine(Integer.MAX_VALUE - 3L, 3));
        assertEquals(pastEnd, LargeTextCoordinateMath.advancePastLine(Integer.MAX_VALUE, 0));
        // Even an empty subsequent line would make the synthetic separator real.
        expectLineFailure(pastEnd, 0);
    }

    @Test public void oversizedRealLineEndFailsBeforeIntegerWrapping() throws IOException {
        expectLineFailure(Integer.MAX_VALUE - 1L, 2);
        expectLineFailure(Integer.MAX_VALUE, 1);
        expectLineFailure(-1L, 0);
        expectLineFailure(0L, -1);
        expectLineFailure(Long.MAX_VALUE, 1);
    }

    @Test public void lineNumbersUseTheirOwnPositiveCoordinateRange() throws IOException {
        assertEquals(1, LargeTextCoordinateMath.checkedLineNumber(1L));
        assertEquals(Integer.MAX_VALUE,
                LargeTextCoordinateMath.checkedLineNumber(Integer.MAX_VALUE));
        for (long invalid : new long[]{0L, -1L, (long) Integer.MAX_VALUE + 1L, Long.MAX_VALUE}) {
            try {
                LargeTextCoordinateMath.checkedLineNumber(invalid);
                fail("An unrepresentable line number must not be narrowed to int");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("TXT line"));
            }
        }
    }

    private static void expectLineFailure(long start, int length) throws IOException {
        try {
            LargeTextCoordinateMath.advancePastLine(start, length);
            fail("An unrepresentable real coordinate must not be narrowed to int");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("TXT"));
        }
    }
}
