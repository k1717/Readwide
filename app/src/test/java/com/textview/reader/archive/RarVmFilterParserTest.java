package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.IOException;

public class RarVmFilterParserTest {
    @Test
    public void read_inlineLengthParsesFirstByteAndCode() throws Exception {
        RarVmFilter filter = RarVmFilterParser.read(
                bitInput("00000010" + "10101010" + "10111011" + "11001100"),
                12);

        assertEquals(12, filter.outputOffset);
        assertEquals(0, filter.bitOffset);
        assertEquals(0x02, filter.firstByte);
        assertEquals(RarVmFilter.LengthEncoding.INLINE, filter.lengthEncoding);
        assertArrayEquals(new byte[] {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc}, filter.code);
        assertEquals(RarVmFilter.StandardFilter.UNKNOWN, filter.standardFilter);
        assertFalse(filter.hasStandaloneStandardFilterPrimitive());
    }

    @Test
    public void read_extended8LengthAddsSeven() throws Exception {
        RarVmFilter filter = RarVmFilterParser.read(
                bitInput("00000110" + "00000001"
                        + "00010001" + "00100010" + "00110011" + "01000100"
                        + "01010101" + "01100110" + "01110111" + "10001000"),
                0);

        assertEquals(RarVmFilter.LengthEncoding.EXTENDED_8, filter.lengthEncoding);
        assertEquals(8, filter.codeLength());
        assertEquals(0x11, filter.code[0] & 0xff);
        assertEquals(0x88, filter.code[7] & 0xff);
    }

    @Test
    public void read_extended16LengthUsesSixteenBitSize() throws Exception {
        RarVmFilter filter = RarVmFilterParser.read(
                bitInput("00000111" + "0000000000000010" + "11011110" + "10101101"),
                5);

        assertEquals(RarVmFilter.LengthEncoding.EXTENDED_16, filter.lengthEncoding);
        assertEquals(2, filter.codeLength());
        assertArrayEquals(new byte[] {(byte) 0xde, (byte) 0xad}, filter.code);
    }

    @Test(expected = IOException.class)
    public void read_rejectsTruncatedVmCode() throws Exception {
        RarVmFilterParser.read(bitInput("00000010" + "10101010"), 0);
    }

    @Test(expected = IOException.class)
    public void read_rejectsZeroExtended16Length() throws Exception {
        RarVmFilterParser.read(bitInput("00000111" + "0000000000000000"), 0);
    }

    private static RarBitInput bitInput(String bits) {
        int byteCount = (bits.length() + 7) / 8;
        byte[] data = new byte[byteCount];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) == '1') {
                data[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return new RarBitInput(data);
    }
}
