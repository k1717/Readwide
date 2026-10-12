package com.readwide.manager.document.doc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Synthetic descriptors exercise corruption handling without allocating large documents. */
public class DocPieceTableValidationTest {
    @Test public void mixedPiecesKeepExactTextAndFilePositions() throws Exception {
        byte[] word = new byte[12];
        word[4] = 'A'; word[5] = 'B';
        ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN).putChar(8, '\uac00').putChar(10, '\ub098');
        Object result = reconstruct(word, pieces(new int[]{0, 2, 4}, new int[]{compressed(4), 8}), -1);
        assertEquals("AB\uac00\ub098", field(result, "text"));
        assertArrayEquals(new int[]{4, 5, 8, 10}, (int[]) field(result, "fc"));
        // CP/FC positions are UTF-16 code units, including a pair split across pieces.
        Object pair = reconstruct(new byte[]{0x3d, (byte) 0xd8, 0, (byte) 0xde},
                pieces(new int[]{0, 1, 2}, new int[]{0, 2}), -1);
        assertEquals("\ud83d\ude00", field(pair, "text"));
        assertArrayEquals(new int[]{0, 2}, (int[]) field(pair, "fc"));
    }

    @Test public void repeatedSourceRangesRemainLegal() throws Exception {
        Object result = reconstruct(new byte[]{'A', 'B'},
                pieces(new int[]{0, 2, 4}, new int[]{compressed(0), compressed(0)}), -1);
        assertEquals("ABAB", field(result, "text"));
        assertArrayEquals(new int[]{0, 1, 0, 1}, (int[]) field(result, "fc"));
    }

    @Test public void emptyPieceTableNeedsNoDocumentBytes() throws Exception {
        assertNull(reconstruct(new byte[0], pieces(new int[]{0}, new int[0]), -1));
    }

    @Test public void compressedMappingFollowsWordRatherThanWindows1252() throws Exception {
        byte[] word = {(byte) 0x80, (byte) 0x81, (byte) 0x82, (byte) 0x8E, (byte) 0x91, (byte) 0x9E, (byte) 0x9F};
        Object result = reconstruct(word, pieces(new int[]{0, word.length}, new int[]{compressed(0)}), -1);
        assertEquals("\u0080\u0081\u201a\u008e\u2018\u009e\u0178", field(result, "text"));
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, (int[]) field(result, "fc"));
    }

    @Test public void pcdCannotBorrowBytesOutsideDeclaredClx() throws Exception {
        byte[] table = pieces(new int[]{0, 1}, new int[]{compressed(0)});
        reject(new byte[]{'A'}, table, table.length - 1);
    }

    @Test public void prcCannotBorrowBytesOutsideDeclaredClx() throws Exception {
        reject(new byte[0], new byte[]{1, 4, 0, 0, 0, 0, 0, 2, 4, 0, 0, 0, 0, 0, 0, 0}, 3);
    }

    @Test public void pieceTableMustContainWholeDescriptors() throws Exception {
        byte[] valid = pieces(new int[]{0, 1}, new int[]{compressed(0)});
        byte[] invalid = java.util.Arrays.copyOf(valid, valid.length + 1);
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(1, 17);
        reject(new byte[]{'A'}, invalid, -1);
    }

    @Test public void decreasingNegativeAndNonzeroInitialCharacterPositionsFail() throws Exception {
        reject(new byte[0], pieces(new int[]{0, 0}, new int[]{100}), -1);
        reject(new byte[]{'A', 'B'}, pieces(new int[]{0, 2, 1}, new int[]{compressed(0), compressed(0)}), -1);
        reject(new byte[]{'A'}, pieces(new int[]{0, Integer.MIN_VALUE}, new int[]{0}), -1);
        reject(new byte[]{'A'}, pieces(new int[]{1, 2}, new int[]{compressed(0)}), -1);
    }

    @Test public void oversizedSinglePieceFailsBeforeReconstruction() throws Exception {
        IOException failure = reject(new byte[0], pieces(new int[]{0, 8 * 1024 * 1024 + 1}, new int[]{0}), -1);
        assertTrue(failure.getMessage().contains("preview budget"));
        reject(new byte[0], pieces(new int[]{0, Integer.MAX_VALUE}, new int[]{0}), -1);
    }

    @Test public void truncatedCompressedAndUnicodePiecesAreNotShortened() throws Exception {
        reject(new byte[]{'A'}, pieces(new int[]{0, 2}, new int[]{compressed(0)}), -1);
        reject(new byte[]{'A', 0, 'B'}, pieces(new int[]{0, 2}, new int[]{0}), -1);
    }

    private static int compressed(int offset) { return 0x40000000 | offset * 2; }

    private static byte[] pieces(int[] cp, int[] offsets) {
        int length = 4 + 12 * offsets.length;
        ByteBuffer out = ByteBuffer.allocate(5 + length).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 2).putInt(length);
        for (int value : cp) out.putInt(value);
        for (int offset : offsets) out.putShort((short) 0).putInt(offset).putShort((short) 0);
        return out.array();
    }

    private static Object reconstruct(byte[] word, byte[] table, int length) throws Exception {
        Method method = DocLegacyLayoutExtractor.class.getDeclaredMethod("reconstructText",
                byte[].class, byte[].class, int.class, int.class);
        method.setAccessible(true);
        try { return method.invoke(null, word, table, 0, length < 0 ? table.length : length); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof IOException) throw (IOException) failure.getCause();
            throw failure;
        }
    }

    private static IOException reject(byte[] word, byte[] table, int length) throws Exception {
        try { reconstruct(word, table, length); fail("Expected a checked corruption error"); }
        catch (IOException expected) { return expected; }
        throw new AssertionError("unreachable");
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
