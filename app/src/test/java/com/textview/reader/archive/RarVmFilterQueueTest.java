package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class RarVmFilterQueueTest {
    @Test
    public void queuedFilterValidatesRangeAndReportsPrimitiveAvailability() throws Exception {
        RarVmQueuedFilter filter = new RarVmQueuedFilter(
                10, 6, RarVmFilter.StandardFilter.E8, 20);

        assertEquals(10, filter.outputOffset);
        assertEquals(16, filter.endOffsetExclusive());
        assertTrue(filter.hasStandalonePrimitive());
        assertTrue(filter.diagnosticSummary().contains("standardFilter=E8"));
        assertTrue(filter.diagnosticSummary().contains("standalonePrimitive=true"));
    }

    @Test
    public void queueAcceptsNonOverlappingOrderedRanges() throws Exception {
        RarVmFilterQueue queue = new RarVmFilterQueue();

        queue.add(new RarVmQueuedFilter(10, 5, RarVmFilter.StandardFilter.E8, 0));
        queue.add(new RarVmQueuedFilter(15, 5, RarVmFilter.StandardFilter.E8E9, 5));

        assertFalse(queue.isEmpty());
        assertEquals(2, queue.size());
        assertEquals(20, queue.highestEndOffset());
        assertEquals(2, queue.snapshot().size());
        assertTrue(queue.diagnosticSummary().contains("queuedFilters=2"));
    }

    @Test(expected = IOException.class)
    public void queueRejectsOverlappingRange() throws Exception {
        RarVmFilterQueue queue = new RarVmFilterQueue();
        queue.add(new RarVmQueuedFilter(10, 6, RarVmFilter.StandardFilter.E8, 0));
        queue.add(new RarVmQueuedFilter(15, 5, RarVmFilter.StandardFilter.E8, 0));
    }

    @Test
    public void applyStandalonePrimitivesToCopyHonorsAbsoluteBufferBase() throws Exception {
        byte[] input = new byte[] {
                0x55, 0x55,
                (byte) 0xe8, 0x20, 0x00, 0x00, 0x00,
                0x66
        };
        RarVmFilterQueue queue = new RarVmFilterQueue();
        queue.add(new RarVmQueuedFilter(102, 5, RarVmFilter.StandardFilter.E8, 0x10));

        byte[] filtered = queue.applyStandalonePrimitivesToCopy(input, 100);

        assertArrayEquals(new byte[] {
                0x55, 0x55,
                (byte) 0xe8, 0x0b, 0x00, 0x00, 0x00,
                0x66
        }, filtered);
        assertEquals(0x20, input[3] & 0xff);
    }

    @Test
    public void applyStandalonePrimitivesInPlaceProcessesMultipleRanges() throws Exception {
        byte[] input = new byte[] {
                (byte) 0xe8, 0x20, 0x00, 0x00, 0x00,
                (byte) 0xe9, 0x30, 0x00, 0x00, 0x00
        };
        RarVmFilterQueue queue = new RarVmFilterQueue();
        queue.add(new RarVmQueuedFilter(0, 5, RarVmFilter.StandardFilter.E8, 0));
        queue.add(new RarVmQueuedFilter(5, 5, RarVmFilter.StandardFilter.E8E9, 5));

        queue.applyStandalonePrimitivesInPlace(input, 0);

        assertArrayEquals(new byte[] {
                (byte) 0xe8, 0x1b, 0x00, 0x00, 0x00,
                (byte) 0xe9, 0x26, 0x00, 0x00, 0x00
        }, input);
    }

    @Test(expected = IOException.class)
    public void applyStandalonePrimitivesRejectsIncompleteDelayedBuffer() throws Exception {
        RarVmFilterQueue queue = new RarVmFilterQueue();
        queue.add(new RarVmQueuedFilter(10, 5, RarVmFilter.StandardFilter.E8, 0));
        queue.applyStandalonePrimitivesInPlace(new byte[] {1, 2, 3, 4}, 10);
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void applyStandalonePrimitivesKeepsNonPrimitiveFiltersAsGap() throws Exception {
        RarVmFilterQueue queue = new RarVmFilterQueue();
        queue.add(new RarVmQueuedFilter(0, 5, RarVmFilter.StandardFilter.DELTA, 0));
        queue.applyStandalonePrimitivesInPlace(new byte[] {1, 2, 3, 4, 5}, 0);
    }

    @Test(expected = IOException.class)
    public void queuedFilterRejectsInvalidLength() throws Exception {
        new RarVmQueuedFilter(0, 0, RarVmFilter.StandardFilter.E8, 0);
    }
}
