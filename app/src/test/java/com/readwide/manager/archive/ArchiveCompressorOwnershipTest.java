package com.readwide.manager.archive;

import org.junit.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

/** Builder migration must preserve concatenation and existing input ownership. */
public class ArchiveCompressorOwnershipTest {
    private static final byte[] FIRST = {1, 2, 3};
    private static final byte[] SECOND = {4, 5};
    private static final byte[] ALL = {1, 2, 3, 4, 5};

    @Test public void singleCompressedWrappersKeepEveryMemberAndCloseTheirInput() throws Exception {
        verifyWrapper("wrapSingleCompressedInputStream", ArchiveSupport.Type.SINGLE_GZ, false);
        verifyWrapper("wrapSingleCompressedInputStream", ArchiveSupport.Type.SINGLE_XZ, true);
    }

    @Test public void tarPayloadWrappersKeepEveryMemberAndCloseTheirInput() throws Exception {
        verifyWrapper("wrapTarPayloadInputStream", ArchiveSupport.Type.TAR_GZ, false);
        verifyWrapper("wrapTarPayloadInputStream", ArchiveSupport.Type.TAR_XZ, true);
    }

    @Test public void zipxXzWrapperKeepsEveryMemberAndClosesDecryptedInput() throws Exception {
        TrackedInput input = new TrackedInput(concatenated(true));
        try (InputStream decoded = zipx(input)) {
            assertFalse(input.closed);
            assertArrayEquals(ALL, readAll(decoded));
        }
        assertTrue(input.closed);
    }

    @Test public void rejectedCompressionHeaderLeavesInputWithTheCaller() throws Exception {
        for (ArchiveSupport.Type type : new ArchiveSupport.Type[]{
                ArchiveSupport.Type.SINGLE_GZ, ArchiveSupport.Type.SINGLE_XZ,
                ArchiveSupport.Type.TAR_GZ, ArchiveSupport.Type.TAR_XZ}) {
            TrackedInput input = new TrackedInput(new byte[]{1, 2, 3});
            String method = type == ArchiveSupport.Type.SINGLE_GZ || type == ArchiveSupport.Type.SINGLE_XZ
                    ? "wrapSingleCompressedInputStream" : "wrapTarPayloadInputStream";
            try {
                assertThrows(IOException.class, () -> wrap(method, input, type));
                assertFalse(input.closed);
            } finally { input.close(); }
        }
        TrackedInput input = new TrackedInput(new byte[]{1, 2, 3});
        try {
            assertThrows(IOException.class, () -> zipx(input));
            assertFalse(input.closed);
        } finally { input.close(); }
    }

    private static void verifyWrapper(String method, ArchiveSupport.Type type, boolean xz) throws Exception {
        TrackedInput input = new TrackedInput(concatenated(xz));
        try (InputStream decoded = wrap(method, input, type)) {
            assertFalse(input.closed);
            assertArrayEquals(ALL, readAll(decoded));
        }
        assertTrue(input.closed);
    }

    private static InputStream wrap(String name, InputStream input, ArchiveSupport.Type type) throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod(name, InputStream.class, ArchiveSupport.Type.class);
        return invoke(method, input, type);
    }

    private static InputStream zipx(InputStream input) throws Exception {
        Method method = ZipxAesArchiveReader.class.getDeclaredMethod("wrapCompressedStream",
                InputStream.class, int.class, long.class, boolean.class);
        return invoke(method, input, 95, (long) ALL.length, false);
    }

    private static InputStream invoke(Method method, Object... arguments) throws Exception {
        method.setAccessible(true);
        try { return (InputStream) method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    private static byte[] concatenated(boolean xz) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] member : new byte[][]{FIRST, new byte[0], SECOND}) {
            try (OutputStream output = xz ? new XZOutputStream(bytes, new LZMA2Options(0))
                    : new GZIPOutputStream(bytes)) {
                output.write(member);
            }
            if (xz) bytes.write(new byte[4]); // Legal four-byte-aligned XZ padding.
        }
        return bytes.toByteArray();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
