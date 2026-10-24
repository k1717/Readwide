package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

/** Synthetic cancellation/ownership boundaries, not producer or timing validation. */
public class EggLzmaCancellationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void preCancelledFactoryDoesNotReadOrAdoptSource() throws Exception {
        FileOperationProgress progress = token(); progress.cancel();
        int[] reads = {0}, closes = {0};
        InputStream source = new ByteArrayInputStream(new byte[9]) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads[0]++; return super.read(bytes, offset, length);
            }
            @Override public void close() { closes[0]++; }
        };
        assertThrows(InterruptedIOException.class, () -> open(source, 9, 1, progress));
        assertEquals(0, reads[0]); assertEquals(0, closes[0]);
        source.close(); assertEquals(1, closes[0]);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void preambleCancellationStopsBeforeBodyAndDictionaryConstruction() throws Exception {
        byte[] packed = pack(new byte[]{42});
        for (int chunk : new int[]{1, 9}) {
            FileOperationProgress progress = token(); int[] reads = {0};
            InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    reads[0]++;
                    int count = super.read(bytes, offset, Math.min(chunk, length));
                    progress.cancel(); return count;
                }
            };
            assertThrows(InterruptedIOException.class, () -> open(source, packed.length, 1, progress));
            assertEquals(1, reads[0]);
            assertEquals(packed.length - chunk, source.available());
        }
    }

    @Test public void constructorRefillCancellationLeavesCloseToOuterOwner() throws Exception {
        byte[] packed = pack(new byte[]{42}); FileOperationProgress progress = token();
        int[] reads = {0}, closes = {0}; IOException closeFailure = new IOException("close");
        InputStream source = new ByteArrayInputStream(packed) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                if (++reads[0] == 2) progress.cancel();
                return count;
            }
            @Override public void close() throws IOException { closes[0]++; throw closeFailure; }
        };
        InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> {
            try (InputStream owned = source) { open(owned, packed.length, 1, progress); }
        });
        assertEquals(2, reads[0]); assertEquals(1, closes[0]);
        assertArrayEquals(new Throwable[]{closeFailure}, failure.getSuppressed());
    }

    @Test public void inputErrorsAndInvalidCountsAreNotReclassifiedAsCancellation() throws Exception {
        byte[] packed = pack(new byte[]{42});
        for (boolean body : new boolean[]{false, true}) {
            IOException original = new IOException("source");
            FileOperationProgress progress = token();
            InputStream source = new InputStream() {
                final ByteArrayInputStream bytes = new ByteArrayInputStream(packed);
                int reads;
                @Override public int read() { throw new AssertionError("scalar physical read"); }
                @Override public int read(byte[] out, int offset, int length) throws IOException {
                    if (body && reads++ == 0) return bytes.read(out, offset, length);
                    progress.cancel(); throw original;
                }
            };
            assertSame(original, assertThrows(IOException.class, () -> open(source, packed.length, 1, progress)));
            for (int bad : new int[]{-2, 0, 70000}) {
                FileOperationProgress invalidProgress = token();
                InputStream invalid = new InputStream() {
                    final ByteArrayInputStream bytes = new ByteArrayInputStream(packed);
                    int reads;
                    @Override public int read() { throw new AssertionError("scalar physical read"); }
                    @Override public int read(byte[] out, int offset, int length) {
                        if (body && reads++ == 0) return bytes.read(out, offset, length);
                        invalidProgress.cancel(); return bad;
                    }
                };
                IOException failure = assertThrows(IOException.class, () -> open(invalid, packed.length, 1, invalidProgress));
                assertFalse(failure instanceof InterruptedIOException);
                assertTrue(failure.getMessage().contains("input progress"));
            }
        }
    }

    @Test public void laterPackedRefillCancellationIsStickyInXz() throws Exception {
        byte[] plain = Round4Fixtures.data(180007), packed = pack(plain);
        assertTrue(packed.length > 65545); FileOperationProgress progress = token();
        int[] reads = {0};
        InputStream source = new ByteArrayInputStream(packed) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                if (++reads[0] == 3) progress.cancel();
                return count;
            }
        };
        try (InputStream decoder = open(source, packed.length, plain.length, progress)) {
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, decoder::readAllBytes);
            assertSame(failure, assertThrows(IOException.class, decoder::read));
            assertEquals(3, reads[0]);
            assertEquals(0, progress.snapshot().doneBytes);
        }
    }

    @Test public void activeAndAbsentTokensRetainPreambleAndDecodedBytes() throws Exception {
        for (byte[] plain : new byte[][]{new byte[0], Round4Fixtures.data(9001)}) {
            byte[] packed = pack(plain);
            for (FileOperationProgress progress : new FileOperationProgress[]{null, token()}) {
                for (int chunk : new int[]{1, 7, 65536}) {
                    int[] closes = {0};
                    InputStream source = new ByteArrayInputStream(packed) {
                        @Override public synchronized int read(byte[] bytes, int offset, int length) {
                            return super.read(bytes, offset, Math.min(chunk, length));
                        }
                        @Override public void close() { closes[0]++; }
                    };
                    InputStream decoder = open(source, packed.length, plain.length, progress);
                    try { assertArrayEquals(plain, decoder.readAllBytes()); assertEquals(-1, decoder.read()); }
                    finally { decoder.close(); decoder.close(); }
                    assertEquals(1, closes[0]);
                }
                if (progress != null) assertEquals(0, progress.snapshot().doneBytes);
            }
        }
    }

    @Test public void encryptedSplitLzmaKeepsBytesAndCancellationRollback() throws Exception {
        byte[] plain = Round4Fixtures.data(190043), packed = pack(plain);
        List<File> parts = Round4Fixtures.splitEgg(temp.newFolder(), "book",
                Round4Fixtures.eggStored(plain, 4, packed, true), 5);
        File archive = parts.get(parts.size() - 1), output = temp.newFolder();
        FileOperationProgress active = token();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, output, Round4Fixtures.PASSWORD, active));
        File target = new File(output, "data.bin");
        assertArrayEquals(plain, Files.readAllBytes(target.toPath()));
        byte[] old = {9, 8, 7}; Files.write(target.toPath(), old);
        FileOperationProgress cancelled = token();
        cancelled.setListener(snapshot -> {
            if (snapshot.doneBytes > 0 && !snapshot.cancelled) cancelled.cancel();
        });
        assertThrows(InterruptedIOException.class, () -> EggArchiveReader.extractArchiveIntoDirectory(
                archive, output, Round4Fixtures.PASSWORD, cancelled));
        assertArrayEquals(old, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new String[]{"data.bin"}, output.list());
    }

    private static byte[] pack(byte[] plain) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(); options.setDictSize(4096);
        try (LZMAOutputStream encoder = new LZMAOutputStream(bytes, options, false)) {
            bytes.write(new byte[]{0, 0, 5, 0, (byte)encoder.getProps(), 0, 0x10, 0, 0});
            encoder.write(plain);
        }
        return bytes.toByteArray();
    }

    private static InputStream open(InputStream source, long packed, long unpacked,
                                    FileOperationProgress progress) throws Exception {
        Class<?> type = Class.forName(EggArchiveReader.class.getName() + "$EggBlock");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object block = constructor.newInstance();
        for (String name : new String[]{"method", "compSize", "uncompSize"}) {
            Field field = type.getDeclaredField(name); field.setAccessible(true);
            if (name.equals("method")) field.setInt(block, 4);
            else field.setLong(block, name.equals("compSize") ? packed : unpacked);
        }
        Method method = EggArchiveReader.class.getDeclaredMethod("openDecodedBlock", InputStream.class,
                type, FileOperationProgress.class); method.setAccessible(true);
        try { return (InputStream) method.invoke(null, source, block, progress); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw (Error) cause;
        }
    }

    private static FileOperationProgress token() { return new FileOperationProgress("test", null); }
}
