package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic bitstream tests for the 7-bit AZO length/position history. */
public class AzoLongHistoryTest {
    @Test public void longHistoryConsumesSevenBitsIncludingLowIndices() throws Exception {
        for (int index : new int[] {0, 1, 15, 16, 63, 64, 126, 127}) {
            byte[] packed = longHistoryBlock(index);
            byte[] original = packed.clone();
            assertArrayEquals(repeated(index), AzoDecoder.decode(packed, index + 3));
            assertArrayEquals(original, packed);
        }
    }

    @Test public void framedHistoryUsesTheLastSlot() throws Exception {
        assertArrayEquals(repeated(127), AzoDecoder.decode(frame(127), 130));
    }

    @Test public void consecutiveCompressedBlocksResetHistory() throws Exception {
        byte[] expected = new byte[133];
        Arrays.fill(expected, (byte) 'A');
        assertArrayEquals(expected, AzoDecoder.decode(frame(127, 0), expected.length));
    }

    @Test public void longHistoryMatchCannotOverrunDeclaredOutput() {
        assertThrows(IOException.class, () -> AzoDecoder.decode(longHistoryBlock(127), 129));
        assertThrows(IOException.class, () -> AzoDecoder.decode(frame(127), 129));
    }

    private static byte[] repeated(int index) {
        byte[] bytes = new byte[index + 3];
        Arrays.fill(bytes, (byte) 'A');
        return bytes;
    }

    private static byte[] longHistoryBlock(int index) {
        // Initial probabilities are uniform and each tree node is visited once:
        // literal 'A' (8 bits), match/history/long flags (110), then a 7-bit index.
        // Slot i starts with length i + 2 at position 0, repeating the first byte.
        int prefix = (((('A' << 3) | 6) << 7) | index) << 14;
        byte[] bytes = new byte[16];
        bytes[0] = (byte) (prefix >>> 24);
        bytes[1] = (byte) (prefix >>> 16);
        bytes[2] = (byte) (prefix >>> 8);
        bytes[3] = (byte) prefix;
        return bytes;
    }

    private static byte[] frame(int... indices) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte('1');
        out.writeByte(0);
        for (int index : indices) {
            byte[] packed = longHistoryBlock(index);
            int size = index + 3;
            out.writeInt(size);
            out.writeInt(packed.length);
            out.writeInt(size ^ packed.length);
            out.write(packed);
        }
        out.write(new byte[12]);
        return bytes.toByteArray();
    }
}
