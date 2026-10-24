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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic boundary regressions, not producer/device compatibility evidence. */
public class DeflateTokenCancellationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void cancelledTokenKeepsZeroLengthNoOpButStopsTheFirstRead() throws Exception {
        FileOperationProgress progress = token();
        progress.cancel();
        int[] reads = {0}, closes = {0};
        InputStream source = new ByteArrayInputStream(new byte[0]) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads[0]++; return super.read(bytes, offset, length);
            }
            @Override public void close() { closes[0]++; }
        };
        OwnedDeflateInputStream decoder = new OwnedDeflateInputStream(source, progress);
        try {
            assertEquals(0, decoder.read(new byte[1], 1, 0));
            assertEquals(0, decoder.skip(0));
            assertThrows(IndexOutOfBoundsException.class, () -> decoder.read(new byte[1], -1, 1));
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, decoder::read);
            assertSame(failure, assertThrows(IOException.class, decoder::available));
            assertSame(failure, assertThrows(IOException.class, () -> decoder.read(new byte[1], 0, 0)));
            assertEquals(0, reads[0]);
            assertFalse(Thread.currentThread().isInterrupted());
        } finally { decoder.close(); decoder.close(); }
        assertEquals(1, closes[0]);
    }

    @Test public void bufferedInputCancellationStopsScalarBulkAndSkip() throws Exception {
        byte[] plain = Round4Fixtures.data(1000);
        byte[] packed = Round4Fixtures.deflate(plain);
        for (int operation = 0; operation < 3; operation++) {
            FileOperationProgress progress = token();
            final int selected = operation;
            int[] reads = {0};
            InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    reads[0]++; return super.read(bytes, offset, length);
                }
            };
            try (InputStream decoder = new OwnedDeflateInputStream(source, progress)) {
                assertEquals(plain[0] & 255, decoder.read());
                int readsBeforeCancel = reads[0];
                progress.cancel();
                byte[] destination = new byte[32]; Arrays.fill(destination, (byte) 91);
                InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> {
                    if (selected == 0) decoder.read();
                    else if (selected == 1) decoder.read(destination, 2, 28);
                    else decoder.skip(10);
                });
                byte[] untouched = new byte[32]; Arrays.fill(untouched, (byte) 91);
                assertArrayEquals(untouched, destination);
                assertEquals(readsBeforeCancel, reads[0]);
                assertSame(failure, assertThrows(IOException.class, decoder::read));
                assertSame(failure, assertThrows(IOException.class, () -> decoder.skip(1)));
                assertSame(failure, assertThrows(IOException.class, decoder::available));
                assertFalse(Thread.currentThread().isInterrupted());
            }
        }
    }

    @Test public void cancellationDuringRefillStopsEmptyBlockChainsBeforeOutput() throws Exception {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        // Byte-aligned nonfinal empty stored blocks, then one final stored byte.
        for (int i = 0; i < 8; i++) packed.write(new byte[]{0, 0, 0, -1, -1});
        packed.write(new byte[]{1, 1, 0, -2, -1, 42});
        for (int cancelAt : new int[]{1, 4}) {
            FileOperationProgress progress = token();
            int[] reads = {0};
            InputStream source = new ByteArrayInputStream(packed.toByteArray()) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    int count = super.read(bytes, offset, Math.min(length, 5));
                    if (++reads[0] == cancelAt) progress.cancel();
                    return count;
                }
            };
            try (InputStream decoder = new OwnedDeflateInputStream(source, progress)) {
                byte[] output = {91};
                InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> decoder.read(output));
                assertArrayEquals(new byte[]{91}, output);
                assertEquals(cancelAt, reads[0]);
                assertSame(failure, assertThrows(IOException.class, decoder::read));
                assertEquals(0, progress.snapshot().doneBytes);
            }
        }
        try (InputStream decoder = new OwnedDeflateInputStream(new ByteArrayInputStream(packed.toByteArray()), token())) {
            assertEquals(42, decoder.read()); assertEquals(-1, decoder.read());
        }
    }

    @Test public void sourceFailureKeepsItsIdentityEvenWhenItCancelsTheToken() throws Exception {
        for (Throwable original : new Throwable[]{new IOException("source"),
                new IllegalStateException("source"), new AssertionError("source")}) {
            FileOperationProgress progress = token();
            InputStream source = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("bulk expected"); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    progress.cancel();
                    if (original instanceof IOException) throw (IOException) original;
                    if (original instanceof RuntimeException) throw (RuntimeException) original;
                    throw (Error) original;
                }
            };
            try (InputStream decoder = new OwnedDeflateInputStream(source, progress)) {
                assertSame(original, assertThrows(Throwable.class, decoder::read));
                assertSame(original, assertThrows(Throwable.class, decoder::read));
                assertSame(original, assertThrows(Throwable.class, decoder::available));
            }
        }
    }

    @Test public void invalidSourceCountsKeepTheirErrorBeforePostRefillCancellation() throws Exception {
        for (int count : new int[]{-1, -2, 0, 65537}) {
            FileOperationProgress progress = token();
            InputStream source = new InputStream() {
                @Override public int read() { return -1; }
                @Override public int read(byte[] bytes, int offset, int length) {
                    progress.cancel(); return count;
                }
            };
            try (InputStream decoder = new OwnedDeflateInputStream(source, progress)) {
                IOException failure = assertThrows(IOException.class, decoder::read);
                assertFalse(failure instanceof InterruptedIOException);
                assertEquals(count == -1, failure instanceof java.io.EOFException);
                assertSame(failure, assertThrows(IOException.class, decoder::read));
            }
        }
    }

    @Test public void activeAndAbsentTokensPreserveOutputAndSkip() throws Exception {
        byte[] plain = Round4Fixtures.data(130007), packed = Round4Fixtures.deflate(plain);
        for (FileOperationProgress progress : new FileOperationProgress[]{null, token()}) {
            for (int chunk : new int[]{1, 7, 65536}) {
                InputStream source = new ByteArrayInputStream(packed) {
                    @Override public synchronized int read(byte[] bytes, int offset, int length) {
                        return super.read(bytes, offset, Math.min(length, chunk));
                    }
                };
                try (InputStream decoder = new OwnedDeflateInputStream(source, progress)) {
                    assertEquals(plain[0] & 255, decoder.read());
                    assertEquals(19, decoder.skip(19));
                    assertArrayEquals(Arrays.copyOfRange(plain, 20, plain.length), decoder.readAllBytes());
                    assertEquals(-1, decoder.read());
                }
            }
            if (progress != null) {
                assertFalse(progress.isCancelled());
                assertEquals(0, progress.snapshot().doneBytes);
            }
        }
    }

    @Test public void cancellationAtEofRemainsTerminal() throws Exception {
        FileOperationProgress progress = token();
        try (InputStream decoder = new OwnedDeflateInputStream(
                new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{42})), progress)) {
            assertEquals(42, decoder.read()); assertEquals(-1, decoder.read());
            progress.cancel();
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, decoder::read);
            assertSame(failure, assertThrows(IOException.class, decoder::available));
        }
    }

    @Test public void alzAndEggFactoriesForwardTokenWithoutReadingAhead() throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            FileOperationProgress progress = token(); progress.cancel();
            int[] reads = {0}, closes = {0};
            InputStream source = new ByteArrayInputStream(new byte[0]) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    reads[0]++; return super.read(bytes, offset, length);
                }
                @Override public void close() { closes[0]++; }
            };
            try (InputStream decoder = factory(egg, source, progress)) {
                assertTrue(decoder instanceof OwnedDeflateInputStream);
                assertThrows(InterruptedIOException.class, decoder::read);
                assertEquals(0, reads[0]);
            }
            assertEquals(1, closes[0]);
        }
    }

    @Test public void activeTokenKeepsEncryptedSplitExtractionUnchanged() throws Exception {
        byte[] plain = Round4Fixtures.data(190043);
        for (boolean egg : new boolean[]{false, true}) {
            File archive = encryptedSplit(egg, plain), output = temp.newFolder();
            FileOperationProgress progress = token();
            assertTrue(extract(egg, archive, output, progress));
            assertArrayEquals(plain, Files.readAllBytes(new File(output, "data.bin").toPath()));
            assertFalse(progress.isCancelled());
        }
    }

    @Test public void cancelledEncryptedSplitExtractionRestoresExistingOutput() throws Exception {
        byte[] plain = Round4Fixtures.data(190043);
        for (boolean egg : new boolean[]{false, true}) {
            File archive = encryptedSplit(egg, plain), output = temp.newFolder();
            File target = new File(output, "data.bin"); byte[] old = {9, 8, 7};
            Files.write(target.toPath(), old);
            FileOperationProgress progress = token();
            progress.setListener(snapshot -> {
                if (snapshot.doneBytes > 0 && !snapshot.cancelled) progress.cancel();
            });
            assertThrows(InterruptedIOException.class, () -> extract(egg, archive, output, progress));
            assertArrayEquals(old, Files.readAllBytes(target.toPath()));
            assertArrayEquals(new String[]{"data.bin"}, output.list());
        }
    }

    private File encryptedSplit(boolean egg, byte[] plain) throws Exception {
        File directory = temp.newFolder();
        byte[] bytes = egg ? Round4Fixtures.egg(plain, 1, true) : Round4Fixtures.alz(plain, 2, true);
        List<File> parts = egg ? Round4Fixtures.splitEgg(directory, "book", bytes, 5)
                : Round4Fixtures.splitAlz(directory, "book", bytes, 5);
        return parts.get(parts.size() - 1);
    }

    private static boolean extract(boolean egg, File archive, File output, FileOperationProgress progress) throws IOException {
        return egg ? EggArchiveReader.extractArchiveIntoDirectory(archive, output, Round4Fixtures.PASSWORD, progress)
                : AlzipArchiveReader.extractArchiveIntoDirectory(archive, output, Round4Fixtures.PASSWORD, progress);
    }

    private static InputStream factory(boolean egg, InputStream source, FileOperationProgress progress) throws Exception {
        Method method;
        if (!egg) {
            method = AlzipArchiveReader.class.getDeclaredMethod("decodePayload", InputStream.class,
                    int.class, long.class, FileOperationProgress.class);
            method.setAccessible(true);
            return (InputStream) method.invoke(null, source, 2, 1L, progress);
        }
        Class<?> blockType = Class.forName(EggArchiveReader.class.getName() + "$EggBlock");
        Constructor<?> constructor = blockType.getDeclaredConstructor(); constructor.setAccessible(true);
        Object block = constructor.newInstance();
        Field compression = blockType.getDeclaredField("method"); compression.setAccessible(true); compression.setInt(block, 1);
        method = EggArchiveReader.class.getDeclaredMethod("openDecodedBlock", InputStream.class, blockType, FileOperationProgress.class);
        method.setAccessible(true);
        return (InputStream) method.invoke(null, source, block, progress);
    }

    private static FileOperationProgress token() { return new FileOperationProgress("test", null); }
}
