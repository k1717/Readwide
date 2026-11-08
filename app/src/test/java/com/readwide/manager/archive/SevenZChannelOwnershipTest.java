package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.junit.Test;

public final class SevenZChannelOwnershipTest {
    @Test public void failedConstructionClosesSuppliedChannelAndPreservesFailure() throws Exception {
        for (char[] password : new char[][]{null, new char[0], {'p'}}) {
            for (Throwable failure : new Throwable[]{new IOException("read"),
                    new IllegalStateException("read"), new OutOfMemoryError("read"),
                    new StackOverflowError("read"), new AssertionError("read")}) {
                ProbeChannel channel = new ProbeChannel(failure, null);
                InvocationTargetException caught = assertThrows(InvocationTargetException.class,
                        () -> open(channel, password));
                assertSame(failure, caught.getCause());
                assertFalse(channel.isOpen());
                assertEquals(1, channel.closes);
            }
        }
    }

    @Test public void cleanupFailureIsSuppressedWithoutReplacingOpeningError() throws Exception {
        for (Throwable cleanup : new Throwable[]{new IOException("close"),
                new IllegalStateException("close"), new OutOfMemoryError("close"),
                new StackOverflowError("close")}) {
            OutOfMemoryError failure = new OutOfMemoryError("opening");
            ProbeChannel channel = new ProbeChannel(failure, cleanup);
            InvocationTargetException caught = assertThrows(InvocationTargetException.class,
                    () -> open(channel, null));
            assertSame(failure, caught.getCause());
            assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
            assertEquals(1, channel.closes);
        }
    }

    @Test public void sharedOpeningAndCleanupFailureDoesNotSelfSuppress() throws Exception {
        AssertionError failure = new AssertionError("shared");
        ProbeChannel channel = new ProbeChannel(failure, failure);
        InvocationTargetException caught = assertThrows(InvocationTargetException.class,
                () -> open(channel, new char[]{'p'}));
        assertSame(failure, caught.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, channel.closes);
    }

    @Test public void successfulConstructionTransfersCloseOwnershipToReader() throws Exception {
        for (char[] password : new char[][]{null, new char[0], {'p'}}) {
            ProbeChannel channel = new ProbeChannel(null, null);
            SevenZFile reader = open(channel, password);
            assertTrue(channel.isOpen());
            assertEquals(0, channel.closes);
            assertFalse(reader.getEntries().iterator().hasNext());
            reader.close();
            reader.close();
            assertFalse(channel.isOpen());
            assertEquals(1, channel.closes);
        }
    }

    private static SevenZFile open(SeekableByteChannel channel, char[] password) throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod("openSevenZChannel",
                SeekableByteChannel.class, char[].class);
        method.setAccessible(true);
        return (SevenZFile) method.invoke(null, channel, password);
    }

    private static final class ProbeChannel implements SeekableByteChannel {
        private final byte[] bytes = emptyArchive();
        private final Throwable readFailure, closeFailure;
        private long position;
        private boolean open = true;
        int closes;

        ProbeChannel(Throwable readFailure, Throwable closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override public int read(ByteBuffer target) throws IOException {
            requireOpen();
            throwFailure(readFailure);
            if (!target.hasRemaining()) return 0;
            if (position >= bytes.length) return -1;
            int count = Math.min(target.remaining(), bytes.length - (int) position);
            target.put(bytes, (int) position, count);
            position += count;
            return count;
        }
        @Override public int write(ByteBuffer source) { throw new NonWritableChannelException(); }
        @Override public long position() throws IOException { requireOpen(); return position; }
        @Override public SeekableByteChannel position(long value) throws IOException {
            requireOpen();
            if (value < 0) throw new IllegalArgumentException("position");
            position = value;
            return this;
        }
        @Override public long size() throws IOException { requireOpen(); return bytes.length; }
        @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() throws IOException {
            closes++;
            open = false;
            throwFailure(closeFailure);
        }
        private void requireOpen() throws IOException { if (!open) throw new ClosedChannelException(); }
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    private static byte[] emptyArchive() {
        byte[] bytes = new byte[34];
        byte[] signature = {'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c, 0, 4};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        bytes[20] = 2;
        bytes[32] = 1;
        CRC32 headerCrc = new CRC32(); headerCrc.update(bytes, 32, 2);
        long headerValue = headerCrc.getValue();
        for (int i = 0; i < 4; i++) bytes[28 + i] = (byte) (headerValue >>> (8 * i));
        CRC32 crc = new CRC32(); crc.update(bytes, 12, 20);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) bytes[8 + i] = (byte) (value >>> (8 * i));
        return bytes;
    }
}
