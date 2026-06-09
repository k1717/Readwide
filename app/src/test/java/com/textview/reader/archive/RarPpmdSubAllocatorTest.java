package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdSubAllocatorTest {
    @Test
    public void allocUnitsReturnsZeroedAlignedPointers() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(1);

        int first = allocator.allocUnits(2);
        int second = allocator.allocUnits(1);

        assertEquals(0, first);
        assertEquals(2 * RarPpmdSubAllocator.UNIT_SIZE_BYTES, second);
        assertEquals(3 * RarPpmdSubAllocator.UNIT_SIZE_BYTES, allocator.usedBytes());
        assertEquals(0, allocator.readByte(first, 0));
    }

    @Test
    public void freeUnitsReusesAndSplitsLargerBlocks() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(1);
        int block = allocator.allocUnits(4);
        allocator.writeByte(block, 0, 0x7f);
        allocator.freeUnits(block, 4);

        int reused = allocator.allocUnits(2);

        assertEquals(block, reused);
        assertEquals(2 * RarPpmdSubAllocator.UNIT_SIZE_BYTES, allocator.reusableBytes());
        assertEquals(0, allocator.readByte(reused, 0));
    }

    @Test
    public void invalidUnitRequestFailsCleanly() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(1);
        try {
            allocator.allocUnits(0);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("unit request"));
            return;
        }
        throw new AssertionError("Invalid PPMd unit request must fail cleanly");
    }

    @Test
    public void clearDropsUsedAndReusableMemory() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(1);
        int block = allocator.allocUnits(3);
        allocator.freeUnits(block, 3);

        allocator.clear();

        assertEquals(0, allocator.usedBytes());
        assertEquals(0, allocator.reusableBytes());
    }
}
