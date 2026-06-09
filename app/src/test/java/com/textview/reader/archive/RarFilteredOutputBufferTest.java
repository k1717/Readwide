package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

public class RarFilteredOutputBufferTest {
    @Test
    public void unfilteredOutputIsByteIdentical() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(out);
        byte[] input = new byte[] {1, 2, 3, 4, 5};

        buffer.write(input);
        buffer.finish();

        assertArrayEquals(input, out.toByteArray());
        assertEquals(5, buffer.writtenOffset());
        assertEquals(5, buffer.emittedOffset());
        assertEquals(0, buffer.pendingByteCount());
    }

    @Test
    public void queuedFilterDelaysRangeUntilFullyAvailable() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(out);
        buffer.queueFilter(new RarVmQueuedFilter(2, 5, RarVmFilter.StandardFilter.E8, 0x10));

        buffer.write(new byte[] {
                0x55, 0x66, (byte) 0xe8, 0x20
        });

        assertArrayEquals(new byte[] {0x55, 0x66}, out.toByteArray());
        assertEquals(2, buffer.emittedOffset());
        assertEquals(2, buffer.pendingByteCount());

        buffer.write(new byte[] {0x00, 0x00, 0x00, 0x77});
        buffer.finish();

        assertArrayEquals(new byte[] {
                0x55, 0x66,
                (byte) 0xe8, 0x0b, 0x00, 0x00, 0x00,
                0x77
        }, out.toByteArray());
    }

    @Test(expected = IOException.class)
    public void overlappingFiltersAreRejected() throws Exception {
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(new ByteArrayOutputStream());
        buffer.queueFilter(new RarVmQueuedFilter(10, 8, RarVmFilter.StandardFilter.E8, 0));
        buffer.queueFilter(new RarVmQueuedFilter(12, 5, RarVmFilter.StandardFilter.E8, 0));
    }

    @Test(expected = IOException.class)
    public void outOfOrderFiltersAreRejected() throws Exception {
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(new ByteArrayOutputStream());
        buffer.queueFilter(new RarVmQueuedFilter(10, 8, RarVmFilter.StandardFilter.E8, 0));
        buffer.queueFilter(new RarVmQueuedFilter(9, 1, RarVmFilter.StandardFilter.E8, 0));
    }

    @Test
    public void flushDoesNotReleaseBytesNeededByPendingFilter() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(out);
        buffer.queueFilter(new RarVmQueuedFilter(0, 5, RarVmFilter.StandardFilter.E8, 0));

        buffer.write(new byte[] {(byte) 0xe8, 0x20, 0x00});
        buffer.flush();

        assertEquals(0, out.size());
        assertEquals(3, buffer.pendingByteCount());
    }

    @Test(expected = IOException.class)
    public void finishRejectsIncompleteFilterRange() throws Exception {
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(new ByteArrayOutputStream());
        buffer.queueFilter(new RarVmQueuedFilter(0, 5, RarVmFilter.StandardFilter.E8, 0));
        buffer.write(new byte[] {(byte) 0xe8, 0x20, 0x00});
        buffer.finish();
    }

    @Test(expected = IOException.class)
    public void filterQueuedAfterRangeWasEmittedIsRejected() throws Exception {
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(new ByteArrayOutputStream());
        buffer.write(new byte[] {1, 2, 3});
        buffer.queueFilter(new RarVmQueuedFilter(1, 2, RarVmFilter.StandardFilter.E8, 0));
    }

    @Test
    public void crcIsCalculatedOnFilteredBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(out);
        buffer.queueFilter(new RarVmQueuedFilter(0, 5, RarVmFilter.StandardFilter.E8, 0));

        buffer.write(new byte[] {(byte) 0xe8, 0x20, 0x00, 0x00, 0x00});
        buffer.finish();

        byte[] filtered = out.toByteArray();
        CRC32 expected = new CRC32();
        expected.update(filtered, 0, filtered.length);
        assertEquals(expected.getValue(), buffer.filteredCrcValue());
        assertTrue(filtered[1] != 0x20);
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void queueFilterRejectsRangeLargerThanDelayedBufferLimit() throws Exception {
        RarFilteredOutputBuffer buffer = new RarFilteredOutputBuffer(new ByteArrayOutputStream(), 4);
        buffer.queueFilter(new RarVmQueuedFilter(0, 5, RarVmFilter.StandardFilter.E8, 0));
    }

}
