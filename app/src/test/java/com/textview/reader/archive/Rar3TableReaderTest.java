package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class Rar3TableReaderTest {
    @Test
    public void readTables_acceptsRepeatZeroLengthTables() throws Exception {
        BitWriter bits = new BitWriter();
        bits.writeBits(0, 2);
        for (int i = 0; i < Rar3HuffmanTables.BC; i++) {
            bits.writeBits(i == 19 ? 1 : 0, 4);
        }
        for (int i = 0; i < 3; i++) {
            bits.writeBits(0, 1);
            bits.writeBits(127, 7);
        }

        Rar3HuffmanTables tables = Rar3TableReader.readTables(
                new RarBitInput(bits.toByteArray()),
                new int[Rar3HuffmanTables.TABLE_SIZE]);

        assertFalse(tables.keepOldTable);
        assertEquals(Rar3HuffmanTables.TABLE_SIZE, tables.tableLengths.length);
        for (int length : tables.tableLengths) assertEquals(0, length);
    }

    @Test
    public void readTables_symbol17RepeatsPreviousLengthNotZeroRun() throws Exception {
        BitWriter bits = new BitWriter();
        bits.writeBits(0, 2);
        for (int i = 0; i < Rar3HuffmanTables.BC; i++) {
            bits.writeBits(i == 0 || i == 15 || i == 17 || i == 19 ? 2 : 0, 4);
        }
        bits.writeBitString("01"); // Direct length 15.
        bits.writeBitString("10"); // Symbol 17: repeat previous length 11 + extra.
        bits.writeBits(0, 7);
        for (int written = 12; written < Rar3HuffmanTables.TABLE_SIZE;) {
            int remaining = Rar3HuffmanTables.TABLE_SIZE - written;
            if (remaining < 11) {
                bits.writeBitString("00"); // Direct length 0.
                written++;
            } else {
                int count = Math.min(138, remaining);
                bits.writeBitString("11"); // Symbol 19: zero run 11 + extra.
                bits.writeBits(count - 11, 7);
                written += count;
            }
        }

        Rar3HuffmanTables tables = Rar3TableReader.readTables(
                new RarBitInput(bits.toByteArray()),
                new int[Rar3HuffmanTables.TABLE_SIZE]);

        for (int i = 0; i < 12; i++) assertEquals(15, tables.tableLengths[i]);
        for (int i = 12; i < tables.tableLengths.length; i++) assertEquals(0, tables.tableLengths[i]);
    }

    @Test
    public void readTables_rejectsPpmdBlockAsPreciseFirstPartyGap() throws Exception {
        try {
            Rar3TableReader.readTables(
                    new RarBitInput(new byte[] {(byte) 0x80, 0x00}),
                    new int[Rar3HuffmanTables.TABLE_SIZE]);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("PPMd"));
            assertTrue(expected.getMessage().contains("first-party"));
            assertTrue(expected.getMessage().contains("keepOldTable=false"));
            return;
        }
        throw new AssertionError("PPMd blocks must remain a precise first-party gap");
    }

    @Test
    public void readTables_reportsPpmdKeepOldFlag() throws Exception {
        try {
            Rar3TableReader.readTables(
                    new RarBitInput(new byte[] {(byte) 0xc0, 0x00}),
                    new int[Rar3HuffmanTables.TABLE_SIZE]);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("PPMd"));
            assertTrue(expected.getMessage().contains("keepOldTable=true"));
            return;
        }
        throw new AssertionError("PPMd keep-old blocks must remain a precise first-party gap");
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int bitsInCurrent;

        void writeBitString(String bits) {
            for (int i = 0; i < bits.length(); i++) {
                char c = bits.charAt(i);
                if (c != '0' && c != '1') continue;
                writeBits(c == '1' ? 1 : 0, 1);
            }
        }

        void writeBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                bitsInCurrent++;
                if (bitsInCurrent == 8) {
                    out.write(current);
                    current = 0;
                    bitsInCurrent = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bitsInCurrent > 0) {
                out.write(current << (8 - bitsInCurrent));
                current = 0;
                bitsInCurrent = 0;
            }
            return out.toByteArray();
        }
    }
}
