package com.readwide.manager.archive;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class Rar3VmFilterSafetyTest {
    @Test(timeout=1000) public void hugeChannelCountsOnlyVisitBytesThatExist() throws Exception {
        int[] registers = new int[7]; registers[0] = Integer.MAX_VALUE;
        for (Rar3VmFilter.StandardFilter type : new Rar3VmFilter.StandardFilter[]{
                Rar3VmFilter.StandardFilter.DELTA,Rar3VmFilter.StandardFilter.AUDIO}) {
            assertArrayEquals(new byte[]{-1,-2,-3},Rar3VmFilter.apply(type,new byte[]{1,2,3},3,registers,0));
        }
    }

    @Test public void ordinaryDeltaChannelOrderingIsUnchanged() throws Exception {
        int[] registers = new int[7]; registers[0] = 2;
        assertArrayEquals(new byte[]{-1,-4,-3,-9,-6,-15},Rar3VmFilter.apply(
                Rar3VmFilter.StandardFilter.DELTA,new byte[]{1,2,3,4,5,6},6,registers,0));
    }

    @Test public void negativeRgbPositionAndOverflowingWidthAreRejectedAsIoErrors() throws Exception {
        for (int[] registers : new int[][]{{6,-1,0,0,0,0,0},{6,3,0,0,0,0,0},
                {0,0,0,0,0,0,0},{1,0,0,0,0,0,0},{2,0,0,0,0,0,0},
                {Integer.MIN_VALUE,0,0,0,0,0,0}}) {
            try { Rar3VmFilter.apply(Rar3VmFilter.StandardFilter.RGB,new byte[9],9,registers,0); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("invalid params")); }
        }
    }

    @Test public void rgbAcceptsPartialPixelsAndRowsShorterThanTheirStride() throws Exception {
        for (int stride : new int[]{3, 12, Integer.MAX_VALUE}) {
            int[] registers = new int[7]; registers[0] = stride;
            assertArrayEquals(new byte[0], Rar3VmFilter.apply(
                    Rar3VmFilter.StandardFilter.RGB, new byte[0], 0, registers, 0));
            assertArrayEquals(new byte[]{-1}, Rar3VmFilter.apply(
                    Rar3VmFilter.StandardFilter.RGB, new byte[]{1}, 1, registers, 0));
            assertArrayEquals(new byte[]{-1,-2}, Rar3VmFilter.apply(
                    Rar3VmFilter.StandardFilter.RGB, new byte[]{1,2}, 2, registers, 0));
            assertArrayEquals(new byte[]{-3,-2,-5}, Rar3VmFilter.apply(
                    Rar3VmFilter.StandardFilter.RGB, new byte[]{1,2,3}, 3, registers, 0));
        }
    }

    @Test public void rgbWideRowAndMinimumStrideKeepDistinctPredictionRules() throws Exception {
        byte[] raw = {1,2,3,4,5,6};
        int[] registers = new int[7]; registers[0] = 12;
        assertArrayEquals(new byte[]{-4,-3,-8,-10,-7,-18}, Rar3VmFilter.apply(
                Rar3VmFilter.StandardFilter.RGB, raw, raw.length, registers, 0));
        registers[0] = 3;
        assertArrayEquals(new byte[]{-4,-3,-8,-6,-4,-10}, Rar3VmFilter.apply(
                Rar3VmFilter.StandardFilter.RGB, raw, raw.length, registers, 0));
        assertArrayEquals(new byte[]{1,2,3,4,5,6}, raw);
    }

    @Test public void rgbOrdinaryRowsKeepTheirUpperLeftPrediction() throws Exception {
        int[] registers = new int[7]; registers[0] = 6;
        assertArrayEquals(new byte[]{-5,-4,-11,-12,-9,-24,-21,-15,-39}, Rar3VmFilter.apply(
                Rar3VmFilter.StandardFilter.RGB, new byte[]{1,2,3,4,5,6,7,8,9}, 9, registers, 0));
    }

    @Test public void inPlaceFiltersReuseExactBlocksButNeverExposeTheirTail() throws Exception {
        for (Rar3VmFilter.StandardFilter type : new Rar3VmFilter.StandardFilter[]{
                Rar3VmFilter.StandardFilter.E8, Rar3VmFilter.StandardFilter.E8E9,
                Rar3VmFilter.StandardFilter.ITANIUM}) {
            byte[] exact = new byte[22];
            assertSame(exact, Rar3VmFilter.apply(type, exact, exact.length, new int[7], 0));
            byte[] oversized = new byte[24]; oversized[22] = 42; oversized[23] = 43;
            byte[] filtered = Rar3VmFilter.apply(type, oversized, 22, new int[7], 0);
            assertNotSame(oversized, filtered);
            assertEquals(22, filtered.length);
            assertEquals(42, oversized[22]); assertEquals(43, oversized[23]);
        }
        byte[] call = {(byte)0xe8,100,0,0,0};
        assertSame(call, Rar3VmFilter.apply(Rar3VmFilter.StandardFilter.E8, call, 5, new int[7], 0));
        assertArrayEquals(new byte[]{(byte)0xe8,99,0,0,0}, call);
    }

    @Test public void badInputBoundsAndRegistersFailBeforeMutation() throws Exception {
        byte[] input={(byte)0xe8,1,2,3,4}; byte[] original=input.clone();
        try { Rar3VmFilter.apply(Rar3VmFilter.StandardFilter.E8,input,6,new int[7],0); fail(); }
        catch (IOException expected) { assertArrayEquals(original,input); }
        try { Rar3VmFilter.apply(Rar3VmFilter.StandardFilter.DELTA,input,5,new int[0],0); fail(); }
        catch (IOException expected) { assertArrayEquals(original,input); }
    }

    @Test public void interruptedFilterStopsBeforeTransformingBytes() throws Exception {
        byte[] input={(byte)0xe8,1,2,3,4}; byte[] original=input.clone();
        Thread.currentThread().interrupt();
        try {
            try { Rar3VmFilter.apply(Rar3VmFilter.StandardFilter.E8,input,5,new int[7],0); fail(); }
            catch (IOException expected) { assertArrayEquals(original,input); }
        } finally { Thread.interrupted(); }
    }

    @Test public void itaniumBitHelpersPreserveSurroundingBits() throws Exception {
        java.lang.reflect.Method set = Rar3VmFilter.class.getDeclaredMethod(
                "itSetBits",byte[].class,int.class,long.class,int.class);
        java.lang.reflect.Method get = Rar3VmFilter.class.getDeclaredMethod(
                "itGetBits",byte[].class,long.class,int.class);
        set.setAccessible(true); get.setAccessible(true);
        byte[] bytes = new byte[8]; java.util.Arrays.fill(bytes,(byte)0xff);
        set.invoke(null,bytes,0xabcde,11L,20);
        assertEquals(0xabcde,((Integer)get.invoke(null,bytes,11L,20)).intValue());
        assertEquals(0x7ff,((Integer)get.invoke(null,bytes,0L,11)).intValue());
        assertEquals(1,((Integer)get.invoke(null,bytes,31L,1)).intValue());
    }

    @Test public void audioAdaptsAfterThirtyTwoSamplesWithIndependentChannelHistory() throws Exception {
        // Hand-derived constant-residual vector, not output from another decoder.
        // Samples 1..32 make diff[2] uniquely smallest (224 vs baseline 256), so k1 becomes +1.
        // The next sample then decreases by two instead of one. Each channel starts fresh.
        for (int channels : new int[]{1, 2}) {
            byte[] input = new byte[40 * channels];
            java.util.Arrays.fill(input, (byte) 1);
            byte[] expected = new byte[input.length];
            for (int sample = 0; sample < 40; sample++) {
                int value = sample <= 32 ? -(sample + 1) : 223 - 2 * (sample - 32);
                for (int channel = 0; channel < channels; channel++) {
                    expected[sample * channels + channel] = (byte) value;
                }
            }
            int[] registers = new int[7]; registers[0] = channels;
            assertArrayEquals(expected, Rar3VmFilter.apply(
                    Rar3VmFilter.StandardFilter.AUDIO, input, input.length, registers, 0));
            for (byte value : input) assertEquals(1, value);
        }
    }

    @Test public void itaniumTransformsActiveBundlesOnlyBeforeTheReservedTail() throws Exception {
        // Template 0x10 enables slot 2. Its operand occupies bits 100..119 and opcode 5
        // occupies bits 124..127. Literal bytes keep this oracle independent of the bit helpers.
        byte[] raw = new byte[38];
        raw[0] = 0x10; raw[12] = 0x5a; raw[13] = 0x34; raw[14] = 0x12; raw[15] = 0x5b;
        raw[16] = 0x10; raw[28] = 0x1a; raw[31] = 0x5b;
        java.util.Arrays.fill(raw, 32, raw.length, (byte) 0x66);
        for (int length : new int[]{21, 22, 37, 38}) {
            byte[] input = java.util.Arrays.copyOf(raw, length);
            byte[] expected = input.clone();
            if (length > 21) {
                // 0x12345 - (0x230 / 16) = 0x12322; neighboring nibbles stay intact.
                expected[12] = 0x2a; expected[13] = 0x32;
            }
            if (length > 37) {
                // Next bundle advances the offset: (1 - 0x24) mod 2^20 = 0xfffdd.
                expected[28] = (byte) 0xda; expected[29] = (byte) 0xfd; expected[30] = (byte) 0xff;
            }
            assertArrayEquals(expected, Rar3VmFilter.apply(
                    Rar3VmFilter.StandardFilter.ITANIUM, input, length, new int[7], 0x230));
        }
    }
}
