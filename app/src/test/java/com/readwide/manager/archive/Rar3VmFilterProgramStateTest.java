package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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

        assertTrue(parsedFirst.resetProgramSlots);
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

        assertFalse(parsedReuse.resetProgramSlots);
        assertEquals(Rar3VmFilter.StandardFilter.E8, parsedReuse.filter.type);
        assertEquals(204L, parsedReuse.filter.blockStartAbs);
        assertEquals(5, parsedReuse.filter.blockLength);
        assertEquals(1, parsedReuse.filter.initR[5]);
        assertEquals(1, state.programCount());
    }

    @Test public void zeroIntermediateLengthCanBeExplicitOrReused() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        BitWriter first = definition(0, 0);
        assertEquals(0, state.parse(0xa0, first.toByteArray(), 0, 1 << 20).filter.blockLength);
        BitWriter reused = new BitWriter(); reused.writeData(0);
        Rar3VmFilter.PendingFilter filter = state.parse(0, reused.toByteArray(), 0, 1 << 20).filter;
        assertEquals(0, filter.blockLength); assertEquals(0, filter.initR[4]);
        assertEquals(1, filter.initR[5]); assertEquals(1, state.programCount());
    }

    @Test public void uniqueProgramSlotsAreMemoryBounded() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        for (int i=0;i<1025;i++) {
            BitWriter record = new BitWriter();
            record.writeData(i+1); record.writeData(0); record.writeData(5);
            record.writeData(E8_PROGRAM.length); record.writeBytes(E8_PROGRAM);
            try {
                state.parse(0x80|0x20,record.toByteArray(),0,1<<20);
                if(i==1024) org.junit.Assert.fail("Program state must not grow without a bound");
            } catch (java.io.IOException expected) {
                if(i!=1024) throw expected;
                assertEquals(1024,state.programCount());
            }
        }
    }

    @Test public void biasedOffsetsWrapBeforeAddingTheLongEntryPosition() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        define(state, 1, 5);
        for (boolean compact : new boolean[]{false, true}) {
            for (int lowByte : new int[]{0, 255}) {
                BitWriter record = new BitWriter();
                if (compact) record.writeBits(0x1000 | lowByte, 14); // 01 0000 + signed byte
                else record.writeData(0xffffff00L | lowByte);
                long current = 0x100000000L + 123;
                Rar3VmFilter.PendingFilter filter = state.parse(
                        0x40, record.toByteArray(), current, 1 << 20).filter;
                assertEquals(current + lowByte + 2, filter.blockStartAbs);
                assertEquals(filter.blockStartAbs, filter.fileOffset);
            }
        }
    }

    @Test public void unbiasedOffsetsRemainUnsignedAndLongOverflowFails() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        define(state, 1, 5);
        BitWriter unsigned = new BitWriter(); unsigned.writeData(0xffffff00L);
        assertEquals(0xffffff00L + 100, state.parse(
                0, unsigned.toByteArray(), 100, 1 << 20).filter.blockStartAbs);
        BitWriter overflow = new BitWriter(); overflow.writeData(2);
        assertThrows(IOException.class, () -> state.parse(
                0, overflow.toByteArray(), Long.MAX_VALUE - 1, 1 << 20));
    }

    @Test public void invalidContextDoesNotConsumeAProgramUse() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        define(state, 1, 5);
        BitWriter record = new BitWriter(); record.writeData(0);
        byte[] bytes = record.toByteArray();
        assertThrows(IOException.class, () -> state.parse(0, bytes, -1, 1 << 20));
        assertThrows(IOException.class, () -> state.parse(0, bytes, 0, 0));
        assertThrows(IOException.class, () -> state.parse(0, null, 0, 1 << 20));
        assertEquals(1, state.parse(0, bytes, 0, 1 << 20).filter.initR[5]);
    }

    @Test public void truncatedResetPreservesBothSlotsAndRememberedSelection() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        define(state, 1, 5); define(state, 2, 7);
        BitWriter record = definition(0, 9);
        record.writeData(2); record.writeBits(42, 8); // second global byte is missing
        assertThrows(IOException.class, () -> state.parse(
                0xa8, record.toByteArray(), 0, 1 << 20));
        assertEquals(2, state.programCount());
        assertRemembered(state, 7, 1);
    }

    @Test public void truncatedReuseCannotChangeSelectionLengthOrUseCount() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        define(state, 1, 5); define(state, 2, 7);
        BitWriter record = new BitWriter();
        record.writeData(1); record.writeData(0); record.writeData(9);
        record.writeData(2); record.writeBits(42, 8);
        assertThrows(IOException.class, () -> state.parse(
                0xa8, record.toByteArray(), 0, 1 << 20));
        assertRemembered(state, 7, 1); // slot one is still selected
        BitWriter firstSlot = new BitWriter(); firstSlot.writeData(1); firstSlot.writeData(0);
        Rar3VmFilter.PendingFilter first = state.parse(
                0x80, firstSlot.toByteArray(), 0, 1 << 20).filter;
        assertEquals(5, first.blockLength);
        assertEquals(1, first.initR[5]);
    }

    @Test public void truncatedNewProgramDoesNotReserveASlot() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        define(state, 1, 5);
        BitWriter record = definition(2, 9);
        record.writeData(2); record.writeBits(42, 8);
        assertThrows(IOException.class, () -> state.parse(
                0xa8, record.toByteArray(), 0, 1 << 20));
        assertEquals(1, state.programCount());
        assertRemembered(state, 5, 1);
        define(state, 2, 9);
        assertEquals(2, state.programCount());
    }

    @Test public void completeResetReplacesThePreviousProgramState() throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        define(state, 1, 5); define(state, 2, 7);
        Rar3VmFilter.ProgramState.Parsed parsed = state.parse(
                0xa0, definition(0, 9).toByteArray(), 0, 1 << 20);
        assertTrue(parsed.resetProgramSlots);
        assertEquals(1, state.programCount());
        assertEquals(0, parsed.filter.initR[5]);
        assertRemembered(state, 9, 1);
    }

    @Test public void legacyClassicCollectorDoesNotDiscardFiltersOnSlotReset() throws Exception {
        java.lang.reflect.Constructor<Rar3ClassicLzEngine> constructor = Rar3ClassicLzEngine.class.getDeclaredConstructor(
                RarBitInput.class, RarLzWindow.class, long.class, int[].class, Rar3VmFilter.ProgramState.class);
        constructor.setAccessible(true);
        Rar3ClassicLzEngine engine = constructor.newInstance(new RarBitInput(new byte[0]),
                new RarLzWindow(16, new ByteArrayOutputStream()), 0L, null, new Rar3VmFilter.ProgramState());
        java.lang.reflect.Method add = Rar3ClassicLzEngine.class.getDeclaredMethod("addVMCode", int.class, byte[].class);
        add.setAccessible(true);
        add.invoke(engine, 0xa0, definition(0, 5).toByteArray());
        Rar3VmFilter.PendingFilter first = engine.filters().get(0);
        add.invoke(engine, 0xa0, definition(0, 5).toByteArray());
        assertEquals(2, engine.filters().size());
        org.junit.Assert.assertSame(first, engine.filters().get(0));
    }

    private static BitWriter definition(int selector, int length) {
        BitWriter record = new BitWriter();
        record.writeData(selector); record.writeData(0); record.writeData(length);
        record.writeData(E8_PROGRAM.length); record.writeBytes(E8_PROGRAM);
        return record;
    }

    private static void define(Rar3VmFilter.ProgramState state, int selector, int length)
            throws IOException {
        state.parse(0xa0, definition(selector, length).toByteArray(), 0, 1 << 20);
    }

    private static void assertRemembered(Rar3VmFilter.ProgramState state, int length, int uses)
            throws IOException {
        BitWriter record = new BitWriter(); record.writeData(0);
        Rar3VmFilter.PendingFilter filter = state.parse(0, record.toByteArray(), 0, 1 << 20).filter;
        assertEquals(length, filter.blockLength);
        assertEquals(uses, filter.initR[5]);
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
