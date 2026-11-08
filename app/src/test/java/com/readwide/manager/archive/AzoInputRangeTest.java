package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class AzoInputRangeTest {
    @Test public void compressedRangeSharesInputAndMatchesIsolatedDecode() throws Exception {
        byte[] packed = literalBlock(0x40);
        byte[] envelope = new byte[packed.length + 11];
        Arrays.fill(envelope, (byte) 0xff);
        System.arraycopy(packed, 0, envelope, 3, packed.length);
        byte[] original = envelope.clone();

        Class<?> type = nested("RawBlockDecoder");
        Constructor<?> constructor = type.getDeclaredConstructor(byte[].class, int.class,
                int.class, int.class, FileOperationProgress.class);
        constructor.setAccessible(true);
        Object decoder = constructor.newInstance(envelope, 3, packed.length, 1, null);
        assertSame(envelope, field(type, "input").get(decoder));
        byte[] actual = (byte[]) invoke(method(type, "decode"), decoder);
        assertArrayEquals(new byte[] {0x40}, actual);
        assertArrayEquals(AzoDecoder.decode(packed, 1), actual);
        assertArrayEquals(original, envelope);
    }

    @Test public void storedRangeCopiesOnlyItsBytes() throws Exception {
        byte[] input = {99, 1, 2, 3, 88};
        byte[] result = rawRange(input, 1, 3, 3, null);
        assertArrayEquals(new byte[] {1, 2, 3}, result);
        assertNotSame(input, result);
        result[0] = 7;
        assertArrayEquals(new byte[] {99, 1, 2, 3, 88}, input);
    }

    @Test public void invalidRangesRejectBeforeOutputAllocation() {
        byte[] input = {1, 2, 3};
        for (int[] range : new int[][] {{-1, 1}, {0, -1}, {4, 0}, {2, 2},
                {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}}) {
            assertThrows(IOException.class,
                    () -> rawRange(input, range[0], range[1], Integer.MAX_VALUE, null));
        }
    }

    @Test public void emptyAndTruncatedRangesCannotBorrowFollowingBytes() throws Exception {
        byte[] input = new byte[32];
        assertArrayEquals(new byte[0], rawRange(input, input.length, 0, 0, null));
        assertArrayEquals(new byte[0], rawRange(input, 7, 0, 0, null));
        assertThrows(IOException.class, () -> rawRange(input, 7, 1, 0, null));
        assertThrows(IOException.class, () -> rawRange(input, 7, 0, 1, null));
        assertThrows(IOException.class, () -> rawRange(input, 7, 3, 1, null));
        assertThrows(IOException.class, () -> AzoDecoder.decode(new byte[3], 1));
    }

    @Test public void arithmeticCursorStopsAtRangeEnd() throws Exception {
        Class<?> type = nested("ArithDecoder");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object decoder = constructor.newInstance();
        byte[] input = {(byte) 0xff, (byte) 0xff, 0x12, 0x34, 0x56, 0x78, (byte) 0xa5, (byte) 0xff};
        invoke(method(type, "init", byte[].class, int.class, int.class), decoder, input, 2, 5);
        assertSame(input, field(type, "buffer").get(decoder));
        assertEquals(0x12345678L, field(type, "code").getLong(decoder));
        Method readBit = method(type, "readCodeBit");
        int next = 0;
        for (int i = 0; i < 8; i++) next = (next << 1) | (int) invoke(readBit, decoder);
        assertEquals(0xa5, next);
        assertEquals(0, invoke(readBit, decoder));
        assertEquals(7, field(type, "cursor").getInt(decoder));
        assertEquals(7, field(type, "inputEnd").getInt(decoder));
        assertThrows(IOException.class,
                () -> invoke(method(type, "init", byte[].class, int.class, int.class),
                        decoder, input, 7, Integer.MAX_VALUE));
    }

    @Test public void consecutiveFramedCompressedAndStoredBlocksStayIndependent() throws Exception {
        byte[] frame = frame(new int[] {1, 2, 1},
                literalBlock(0), new byte[] {7, 8}, literalBlock(0x40));
        byte[] expected = {0, 7, 8, 0x40};
        assertArrayEquals(expected, decodeFramed(frame, expected.length));
        assertArrayEquals(expected, AzoDecoder.decode(frame, expected.length));
    }

    @Test public void truncatedFramedBlockCannotConsumeNextHeader() throws Exception {
        byte[] frame = frame(new int[] {1, 1}, new byte[3], literalBlock(0x40));
        assertThrows(IOException.class, () -> decodeFramed(frame, 2));
    }

    @Test public void rangeCancellationKeepsStoredFallbackUnchanged() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("AZO range", null);
        progress.cancel();
        assertThrows(InterruptedIOException.class,
                () -> rawRange(new byte[] {1, 2, 3}, 1, 1, 1, progress));
        byte[] raw = {'1', 0, 8, 9, 10};
        assertArrayEquals(raw, AzoDecoder.decode(raw, raw.length));
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(0, progress.snapshot().doneBytes);
    }

    // With initial uniform literal probabilities, these leading eight bits
    // select one literal. Extra zero bytes keep this synthetic block complete.
    private static byte[] literalBlock(int literal) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) literal;
        return bytes;
    }

    private static byte[] frame(int[] sizes, byte[]... blocks) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte('1');
        out.writeByte(0);
        for (int i = 0; i < blocks.length; i++) {
            out.writeInt(sizes[i]);
            out.writeInt(blocks[i].length);
            out.writeInt(sizes[i] ^ blocks[i].length);
            out.write(blocks[i]);
        }
        out.write(new byte[12]);
        return bytes.toByteArray();
    }

    private static byte[] rawRange(byte[] bytes, int offset, int length, int size,
                                   FileOperationProgress progress) throws Exception {
        return (byte[]) invoke(method(AzoDecoder.class, "decodeRawBlock", byte[].class,
                int.class, int.class, int.class, FileOperationProgress.class),
                null, bytes, offset, length, size, progress);
    }

    private static byte[] decodeFramed(byte[] input, int size) throws Exception {
        Class<?> type = nested("FramedDecoder");
        Constructor<?> constructor = type.getDeclaredConstructor(byte[].class, int.class,
                FileOperationProgress.class);
        constructor.setAccessible(true);
        return (byte[]) invoke(method(type, "decode"), constructor.newInstance(input, size, null));
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(AzoDecoder.class.getName() + "$" + name);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw (Error) cause;
        }
    }
}
