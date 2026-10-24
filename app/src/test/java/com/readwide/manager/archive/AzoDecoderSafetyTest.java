package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic framing/bounds checks; not evidence for new compressed AZO variants. */
public class AzoDecoderSafetyTest {
    @Test public void arrayValidationAcceptsBeyondOld512MiBWithoutAllocating() throws Exception {
        assertEquals(513 * 1024 * 1024, AzoDecoder.checkedArraySize(513L * 1024 * 1024));
        assertEquals(Integer.MAX_VALUE, AzoDecoder.checkedArraySize(Integer.MAX_VALUE));
    }

    @Test public void arrayValidationStillRejectsUnrepresentableSizes() {
        for (long size : new long[] {-1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
            assertThrows(IOException.class, () -> AzoDecoder.checkedArraySize(size));
        }
    }

    @Test public void storedBlocksUseExactDeclaredOutput() throws Exception {
        assertArrayEquals(new byte[]{1,2,3,4,5},
                AzoDecoder.decode(frame(false,new byte[]{1,2},new byte[]{3,4,5}),5));
        assertArrayEquals(new byte[0],AzoDecoder.decode(frame(false),0));
    }

    @Test public void rawStoredPrefixCollisionKeepsFallback() throws Exception {
        byte[] raw={'1',0,8,9,10};
        assertArrayEquals(raw,AzoDecoder.decode(raw,raw.length));
    }

    @Test public void x86OffsetsRestartForEveryInnerBlock() throws Exception {
        byte[] block={5,(byte)0xe8,5,0,0,0};
        assertArrayEquals(new byte[]{42,43,44,5,(byte)0xe8,4,0,0,0,5,(byte)0xe8,4,0,0,0},
                AzoDecoder.decode(frame(true,new byte[]{42,43,44},block,block),15));
    }

    @Test public void oversizedInnerDeclarationFailsBeforeDecodingOrAllocatingIt() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte('1');out.writeByte(0);
        int unpacked=512*1024*1024;
        out.writeInt(unpacked);out.writeInt(1);out.writeInt(unpacked^1);out.writeByte(0);
        out.write(new byte[12]);
        IOException error=assertThrows(IOException.class,()->decodeFramed(bytes.toByteArray(),1));
        assertTrue(error.getMessage().contains("declared"));
    }

    @Test public void framedUnderflowZeroMismatchAndTruncationFailPreflight() throws Exception {
        byte[] packed=frame(false,new byte[]{1,2});
        assertThrows(IOException.class,()->decodeFramed(packed,3));
        assertThrows(IOException.class,()->decodeFramed(packed,0));
        assertThrows(IOException.class,()->decodeFramed(
                java.util.Arrays.copyOf(packed,packed.length-1),2));
        packed[13]=1; // Nonzero check must agree with unpacked XOR packed.
        assertThrows(IOException.class,()->decodeFramed(packed,2));
    }

    @Test public void cancellationIsNotReinterpretedAsRawFallback() throws Exception {
        byte[] packed=frame(false,new byte[]{1});
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,()->AzoDecoder.decode(packed,1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void tokenCancellationIsTerminalWithoutThreadInterruption() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("AZO", null);
        progress.cancel();
        for (byte[] packed : new byte[][] {frame(false, new byte[] {1}), {'1', 0, 8, 9, 10}}) {
            assertThrows(InterruptedIOException.class, () -> AzoDecoder.decode(packed, packed.length, progress));
        }
        assertThrows(InterruptedIOException.class, () -> AzoDecoder.decode(new byte[0], Long.MAX_VALUE, progress));
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(0, progress.snapshot().doneBytes);
    }

    @Test public void activeTokenPreservesStoredFallbackAndFilteredBytes() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("AZO", null);
        byte[] raw = {'1', 0, 8, 9, 10};
        assertArrayEquals(raw, AzoDecoder.decode(raw, raw.length, progress));
        byte[] block = {5, (byte) 0xe8, 5, 0, 0, 0};
        assertArrayEquals(new byte[] {42, 5, (byte) 0xe8, 4, 0, 0, 0},
                AzoDecoder.decode(frame(true, new byte[] {42}, block), 7, progress));
        assertArrayEquals(new byte[0], AzoDecoder.decode(frame(false), 0, progress));
        assertEquals(0, progress.snapshot().doneBytes);
    }

    @Test public void framedCheckpointsObserveCancellationAfterConstruction() throws Exception {
        Class<?> type = Class.forName(AzoDecoder.class.getName() + "$FramedDecoder");
        Constructor<?> constructor = type.getDeclaredConstructor(byte[].class, int.class, FileOperationProgress.class);
        constructor.setAccessible(true);
        java.lang.reflect.Field readPos = type.getDeclaredField("readPos"); readPos.setAccessible(true);
        for (String stage : new String[] {"validateLayout", "decodeOneBlock"}) {
            FileOperationProgress progress = new FileOperationProgress("AZO", null);
            Object decoder = constructor.newInstance(frame(false, new byte[] {1}), 1, progress);
            readPos.setInt(decoder, 2);
            progress.cancel();
            Method method = type.getDeclaredMethod(stage); method.setAccessible(true);
            assertThrows(InterruptedIOException.class, () -> invoke(method, decoder));
            assertEquals(2, readPos.getInt(decoder));
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void rawOutputCheckpointObservesTokenWithoutChangingNextByte() throws Exception {
        Class<?> type = Class.forName(AzoDecoder.class.getName() + "$RawBlockDecoder");
        Constructor<?> constructor = type.getDeclaredConstructor(byte[].class, int.class, FileOperationProgress.class);
        constructor.setAccessible(true);
        FileOperationProgress progress = new FileOperationProgress("AZO", null);
        Object decoder = constructor.newInstance(new byte[0], 65537, progress);
        Method write = type.getDeclaredMethod("write", int.class); write.setAccessible(true);
        invoke(write, decoder, 7);
        // Exercise the existing output checkpoint, not the arithmetic decoder.
        java.lang.reflect.Field position = type.getDeclaredField("writePos"); position.setAccessible(true);
        position.setInt(decoder, 65536);
        progress.cancel();
        assertThrows(InterruptedIOException.class, () -> invoke(write, decoder, 8));
        assertEquals(65536, position.getInt(decoder));
        java.lang.reflect.Field output = type.getDeclaredField("output"); output.setAccessible(true);
        byte[] bytes = (byte[]) output.get(decoder);
        assertEquals(7, bytes[0]); assertEquals(0, bytes[65536]);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void x86CheckpointObservesTokenBeforeModifyingBytes() throws Exception {
        Method filter = AzoDecoder.class.getDeclaredMethod("applyX86Filter",
                byte[].class, int.class, int.class, FileOperationProgress.class);
        filter.setAccessible(true);
        byte[] bytes = {5, (byte) 0xe8, 5, 0, 0, 0};
        byte[] original = bytes.clone();
        FileOperationProgress progress = new FileOperationProgress("AZO", null);
        progress.cancel();
        assertThrows(InterruptedIOException.class, () -> invoke(filter, null, bytes, 0, bytes.length, progress));
        assertArrayEquals(original, bytes);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw (Error) cause;
        }
    }

    private static byte[] frame(boolean filter,byte[]... blocks) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte('1');out.writeByte(filter?1:0);
        for(byte[] block:blocks) {
            out.writeInt(block.length);out.writeInt(block.length);out.writeInt(0);out.write(block);
        }
        out.write(new byte[12]);return bytes.toByteArray();
    }

    // Inspect the framed parser itself: the public entry point intentionally
    // retries raw AZO when stored bytes happen to share the two-byte prefix.
    private static byte[] decodeFramed(byte[] input,int size) throws Exception {
        Class<?> type=Class.forName(AzoDecoder.class.getName()+"$FramedDecoder");
        Constructor<?> constructor=type.getDeclaredConstructor(byte[].class,int.class,FileOperationProgress.class);
        constructor.setAccessible(true);
        Method decode=type.getDeclaredMethod("decode");decode.setAccessible(true);
        try { return (byte[])decode.invoke(constructor.newInstance(input,size,null)); }
        catch(InvocationTargetException wrapper) {
            Throwable cause=wrapper.getCause();
            if(cause instanceof Exception)throw (Exception)cause;
            throw (Error)cause;
        }
    }
}
