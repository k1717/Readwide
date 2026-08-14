package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class Rar3VmFilterProgramStateTest {
    // A synthetic 53-byte payload with the published RAR E8 standard-filter
    // fingerprint (CRC32 ad576887) and the required first-byte XOR checksum.
    private static final byte[] E8_PROGRAM = new byte[] {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            (byte) 0xfe, 0x53, (byte) 0xaa, (byte) 0xa9, (byte) 0xae
    };

    @Test
    public void reusesProgramAndPreviousBlockLength() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();

        BitWriter first = new BitWriter();
        first.writeData(0); // reset program slots, then define slot zero
        first.writeData(3); // block start relative to current output
        first.writeData(5); // explicit block length
        first.writeData(E8_PROGRAM.length);
        first.writeBytes(E8_PROGRAM);
        Rar3VmFilter.ProgramState.Parsed parsedFirst = state.parse(
                0x80 | 0x20, first.toByteArray(), 100, 1 << 20);

        assertTrue(parsedFirst.resetPendingFilters);
        assertEquals(Rar3VmFilter.StandardFilter.E8, parsedFirst.filter.type);
        assertEquals(103L, parsedFirst.filter.blockStartAbs);
        assertEquals(103L, parsedFirst.filter.fileOffset);
        assertEquals(5, parsedFirst.filter.blockLength);
        assertEquals(1, state.programCount());

        BitWriter reused = new BitWriter();
        reused.writeData(1); // one-based selector for slot zero
        reused.writeData(4);
        reused.writeData(2); // user global-data length
        reused.writeBits(0x12, 8);
        reused.writeBits(0x34, 8);
        Rar3VmFilter.ProgramState.Parsed parsedReuse = state.parse(
                0x80 | 0x08, reused.toByteArray(), 200, 1 << 20);

        assertFalse(parsedReuse.resetPendingFilters);
        assertEquals(Rar3VmFilter.StandardFilter.E8, parsedReuse.filter.type);
        assertEquals(204L, parsedReuse.filter.blockStartAbs);
        assertEquals(5, parsedReuse.filter.blockLength);
        assertEquals(1, parsedReuse.filter.initR[5]);
        assertEquals(1, state.programCount());
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int current;
        private int bitCount;

        void writeData(long value) {
            if (value >= 0 && value <= 15) {
                writeBits(value, 6); // 00 + four-bit value
            } else if (value >= 16 && value <= 255) {
                writeBits(1, 2); // 01
                writeBits(value, 8);
            } else if (value >= 0 && value <= 0xffff) {
                writeBits(2, 2); // 10
                writeBits(value, 16);
            } else {
                writeBits(3, 2); // 11
                writeBits(value >>> 16, 16);
                writeBits(value, 16);
            }
        }

        void writeBytes(byte[] values) {
            for (byte value : values) writeBits(value & 0xffL, 8);
        }

        void writeBits(long value, int count) {
            for (int bit = count - 1; bit >= 0; bit--) {
                current = (current << 1) | (int) ((value >>> bit) & 1L);
                if (++bitCount == 8) {
                    bytes.write(current);
                    current = 0;
                    bitCount = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bitCount != 0) {
                bytes.write(current << (8 - bitCount));
                current = 0;
                bitCount = 0;
            }
            return bytes.toByteArray();
        }
    }
}
