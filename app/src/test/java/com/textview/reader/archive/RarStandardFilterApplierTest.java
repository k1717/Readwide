package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

public class RarStandardFilterApplierTest {
    @Test
    public void applyToCopy_e8ConvertsAbsoluteTargetToRelativeDisplacement() throws Exception {
        byte[] input = new byte[] {
                (byte) 0xe8, 0x10, 0x00, 0x00, 0x00, 0x55
        };

        byte[] filtered = RarStandardFilterApplier.applyToCopy(
                input, 0, input.length, RarVmFilter.StandardFilter.E8, 0);

        assertArrayEquals(new byte[] {
                (byte) 0xe8, 0x0b, 0x00, 0x00, 0x00, 0x55
        }, filtered);
        assertEquals(0x10, input[1] & 0xff);
    }

    @Test
    public void applyInPlace_e8E9AlsoConvertsJumpOpcode() throws Exception {
        byte[] input = new byte[] {
                (byte) 0xe9, 0x20, 0x00, 0x00, 0x00,
                (byte) 0xe8, 0x30, 0x00, 0x00, 0x00
        };

        RarStandardFilterApplier.applyInPlace(
                input, 0, input.length, RarVmFilter.StandardFilter.E8E9, 0);

        assertArrayEquals(new byte[] {
                (byte) 0xe9, 0x1b, 0x00, 0x00, 0x00,
                (byte) 0xe8, 0x26, 0x00, 0x00, 0x00
        }, input);
    }

    @Test
    public void applyInPlace_e8LeavesE9OpcodeUntouched() throws Exception {
        byte[] input = new byte[] {
                (byte) 0xe9, 0x20, 0x00, 0x00, 0x00,
                (byte) 0xe8, 0x30, 0x00, 0x00, 0x00
        };

        RarStandardFilterApplier.applyInPlace(
                input, 0, input.length, RarVmFilter.StandardFilter.E8, 0);

        assertArrayEquals(new byte[] {
                (byte) 0xe9, 0x20, 0x00, 0x00, 0x00,
                (byte) 0xe8, 0x26, 0x00, 0x00, 0x00
        }, input);
    }

    @Test
    public void applyInPlace_honorsRangeOffsetAndFileOffset() throws Exception {
        byte[] input = new byte[] {
                0x7f, 0x7f,
                (byte) 0xe8, 0x40, 0x00, 0x00, 0x00,
                0x7f
        };

        RarStandardFilterApplier.applyInPlace(
                input, 2, 5, RarVmFilter.StandardFilter.E8, 0x10);

        assertArrayEquals(new byte[] {
                0x7f, 0x7f,
                (byte) 0xe8, 0x2b, 0x00, 0x00, 0x00,
                0x7f
        }, input);
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void applyInPlace_rejectsNonPrimitiveStandardFilter() throws Exception {
        RarStandardFilterApplier.applyInPlace(
                new byte[] {1, 2, 3}, 0, 3, RarVmFilter.StandardFilter.DELTA, 0);
    }

    @Test(expected = IOException.class)
    public void applyInPlace_rejectsInvalidRange() throws Exception {
        RarStandardFilterApplier.applyInPlace(
                new byte[] {1, 2, 3}, 2, 2, RarVmFilter.StandardFilter.E8, 0);
    }
}
