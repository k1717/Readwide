package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

public class RarFilteredDecodedOutputTest {
    @Test
    public void unfilteredLzWindowOutputRemainsByteIdenticalThroughFilteredAdapter()
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredDecodedOutput decoded = new RarFilteredDecodedOutput(out);
        RarLzWindow window = new RarLzWindow(16, decoded);

        window.writeLiteral('a');
        window.writeLiteral('b');
        window.writeLiteral('c');
        window.copyMatch(3, 3);
        decoded.finish();

        assertArrayEquals(new byte[] {'a', 'b', 'c', 'a', 'b', 'c'}, out.toByteArray());
        assertEquals(6L, window.written());
        assertEquals(6L, decoded.writtenOffset());
        assertEquals(6L, decoded.emittedOffset());
        assertEquals(0L, decoded.pendingByteCount());
    }

    @Test
    public void queuedExecutionPlanFiltersBytesProducedByLzWindow() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredDecodedOutput decoded = new RarFilteredDecodedOutput(out);
        RarVmFilterExecutionPlan plan = RarVmFilterExecutionPlan
                .fromParsedFilter(filterAt(0, RarVmFilter.StandardFilter.E8))
                .outputLength(5)
                .fileOffset(0)
                .build();

        decoded.queueExecutionPlan(plan);
        RarLzWindow window = new RarLzWindow(16, decoded);
        window.writeLiteral(0xe8);
        window.writeLiteral(0x20);
        window.writeLiteral(0x00);
        window.writeLiteral(0x00);
        window.writeLiteral(0x00);
        decoded.finish();

        assertArrayEquals(new byte[] {(byte) 0xe8, 0x1b, 0x00, 0x00, 0x00}, out.toByteArray());
        assertEquals(5L, window.written());
        assertEquals(5L, decoded.emittedOffset());
    }

    @Test
    public void flushDoesNotEmitIncompleteFilteredRangeFromDecodedAdapter() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredDecodedOutput decoded = new RarFilteredDecodedOutput(out);
        decoded.queueStandaloneFilter(filterAt(0, RarVmFilter.StandardFilter.E8), 5, 0);

        decoded.writeDecodedByte(0xe8);
        decoded.writeDecodedByte(0x20);
        decoded.flush();

        assertEquals(0, out.size());
        assertEquals(2L, decoded.pendingByteCount());
    }

    @Test(expected = IOException.class)
    public void finishRejectsIncompleteFilteredRangeFromDecodedAdapter() throws Exception {
        RarFilteredDecodedOutput decoded = new RarFilteredDecodedOutput(new ByteArrayOutputStream());
        decoded.queueStandaloneFilter(filterAt(0, RarVmFilter.StandardFilter.E8), 5, 0);
        decoded.writeDecodedByte(0xe8);
        decoded.writeDecodedByte(0x20);
        decoded.finish();
    }

    @Test
    public void filteredDecodedOutputCrcIsCalculatedAfterFilterApplication() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredDecodedOutput decoded = new RarFilteredDecodedOutput(out);
        decoded.queueStandaloneFilter(filterAt(0, RarVmFilter.StandardFilter.E8), 5, 0);
        decoded.writeDecodedBytes(new byte[] {(byte) 0xe8, 0x20, 0x00, 0x00, 0x00}, 0, 5);
        decoded.finish();

        byte[] filtered = out.toByteArray();
        CRC32 expected = new CRC32();
        expected.update(filtered, 0, filtered.length);
        assertEquals(expected.getValue(), decoded.filteredCrcValue());
        assertTrue((filtered[1] & 0xff) != 0x20);
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void unsupportedStandardFilterStillFailsBeforeDecodedAdapterQueueing()
            throws Exception {
        RarFilteredDecodedOutput decoded = new RarFilteredDecodedOutput(new ByteArrayOutputStream());
        decoded.queueStandaloneFilter(filterAt(0, RarVmFilter.StandardFilter.DELTA), 8, 0);
    }

    private static RarVmFilter filterAt(long outputOffset,
                                        RarVmFilter.StandardFilter standardFilter) {
        return new RarVmFilter(
                outputOffset,
                0,
                0,
                RarVmFilter.LengthEncoding.INLINE,
                new byte[] {1},
                0,
                standardFilter);
    }
}