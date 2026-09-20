package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

public class SevenZDecodePlanTest {
    @Test public void shuffledCodersAreScheduledByDependencies() throws Exception {
        SevenZDecodePlan plan = SevenZDecodePlan.compile(copies(3), new long[]{10,10,10},
                new int[]{0,2}, new int[]{2,1}, new int[]{1});
        assertEquals(1, plan.step(0).coderIndex);
        assertEquals(2, plan.step(1).coderIndex);
        assertEquals(0, plan.step(2).coderIndex);
        assertEquals(-1, plan.step(0).source(0));
        assertEquals(1, plan.step(1).source(0));
        assertEquals(2, plan.step(2).source(0));
        assertEquals(0, plan.finalCoder);
        assertEquals(10, plan.outputSize);
    }

    @Test public void bcj2PreservesInputSlotsAndPackedStreamOrder() throws Exception {
        SevenZDecodePlan.Coder[] coders = {coder("0303011B", null, 4, 1), copy(), copy()};
        SevenZDecodePlan plan = SevenZDecodePlan.compile(coders, new long[]{10,8,2},
                new int[]{0,2}, new int[]{1,2}, new int[]{5,3,4,1});
        assertEquals(4, plan.packedStreamCount);
        assertEquals(1, plan.step(0).coderIndex);
        assertEquals(-3, plan.step(0).source(0));
        assertEquals(2, plan.step(1).coderIndex);
        assertEquals(-1, plan.step(1).source(0));
        SevenZDecodePlan.Step bcj2 = plan.step(2);
        assertEquals(0, bcj2.coderIndex);
        assertEquals(4, bcj2.inputCount());
        assertEquals(1, bcj2.source(0));
        assertEquals(-4, bcj2.source(1));
        assertEquals(2, bcj2.source(2));
        assertEquals(-2, bcj2.source(3));
    }

    @Test public void duplicateBindingEndpointsAreRejected() {
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(3), new long[3],
                new int[]{1,1}, new int[]{0,1}, new int[]{2}));
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(3), new long[3],
                new int[]{1,2}, new int[]{0,0}, new int[]{0}));
    }

    @Test public void invalidOrDuplicatePackedSlotsAreRejected() {
        SevenZDecodePlan.Coder[] bcj2 = {coder("0303011B", null, 4, 1)};
        for (int[] slots : new int[][]{{0,1,2,2}, {0,1,2,4}, {0,1,2,-1}, {0,1,2}}) {
            assertThrows(IOException.class, () -> SevenZDecodePlan.compile(bcj2, new long[]{0}, new int[0], new int[0], slots));
        }
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(2), new long[2],
                new int[]{1}, new int[]{0}, new int[]{1}));
    }

    @Test public void cyclicAndSelfLinkedComponentsAreRejected() {
        // One valid packed stream/final output must not hide a disconnected cycle.
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(3), new long[3],
                new int[]{1,2}, new int[]{2,1}, new int[]{0}));
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(2), new long[2],
                new int[]{1}, new int[]{1}, new int[]{0}));
    }

    @Test public void unsupportedMethodsAritiesAndPropertiesFailDuringPlanning() {
        SevenZDecodePlan.Coder[][] invalid = {
                {copy(), coder("FF", null, 1, 1)},
                {copy(), coder("00", null, 2, 1)},
                {copy(), coder("00", null, 1, 2)},
                {copy(), coder("21", new byte[]{0x40}, 1, 1)},
                {copy(), coder("06F10701", new byte[]{1}, 1, 1)}
        };
        for (SevenZDecodePlan.Coder[] coders : invalid) {
            assertThrows(IOException.class, () -> SevenZDecodePlan.compile(coders, new long[2],
                    new int[]{1}, new int[]{0}, new int[]{0}));
        }
    }

    @Test public void dimensionsSizesAndOutOfRangeBindingsAreRejected() {
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(1), new long[0], new int[0], new int[0], new int[]{0}));
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(1), new long[]{-1}, new int[0], new int[0], new int[]{0}));
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(2), new long[2], new int[]{2}, new int[]{0}, new int[]{0}));
        assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(2), new long[2], new int[]{1}, new int[]{2}, new int[]{0}));
    }

    @Test public void planOwnsMetadataAndDoesNotRequirePassword() throws Exception {
        byte[] method = SevenZCoderRegistryTest.id("06F10701"), properties = {0x3f,0};
        SevenZDecodePlan.Coder[] coders = {new SevenZDecodePlan.Coder(method, properties, 1, 1)};
        method[0] = 0; properties[1] = (byte)0xff;
        long[] sizes = {Long.MAX_VALUE}; int[] packed = {0};
        SevenZDecodePlan plan = SevenZDecodePlan.compile(coders, sizes, new int[0], new int[0], packed);
        sizes[0] = -1; packed[0] = 5; coders[0] = null;
        assertEquals("AES", plan.step(0).decoder.entry.name);
        assertEquals(Long.MAX_VALUE, plan.outputSize);
        assertEquals(-1, plan.step(0).source(0));
    }

    @Test public void longChainPlanningDoesNotUseJavaRecursion() throws Exception {
        int count = 20000;
        int[] inputs = new int[count - 1], outputs = new int[count - 1];
        for (int i = 0; i < inputs.length; i++) { inputs[i] = i + 1; outputs[i] = i; }
        SevenZDecodePlan plan = SevenZDecodePlan.compile(copies(count), new long[count], inputs, outputs, new int[]{0});
        assertEquals(count, plan.stepCount());
        assertEquals(count - 1, plan.finalCoder);
        assertEquals(count - 2, plan.step(count - 1).source(0));
        // Intentionally tests metadata only, not deeply nested decoder read/close calls.
    }

    @Test public void planningHonorsCancellationWithoutClearingInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> SevenZDecodePlan.compile(copies(1), new long[1], new int[0], new int[0], new int[]{0}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static SevenZDecodePlan.Coder copy() { return coder("00", null, 1, 1); }
    private static SevenZDecodePlan.Coder[] copies(int count) {
        SevenZDecodePlan.Coder[] result = new SevenZDecodePlan.Coder[count];
        Arrays.fill(result, copy());
        return result;
    }
    private static SevenZDecodePlan.Coder coder(String id, byte[] properties, int inputs, int outputs) {
        return new SevenZDecodePlan.Coder(SevenZCoderRegistryTest.id(id), properties, inputs, outputs);
    }
}
