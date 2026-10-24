package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic source regressions; no producer/device compatibility assertion. */
public class ArchiveBzip2InputStreamTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void fullAndHeaderlessAlzRetainEmptyAndConcatenatedMembers() throws Exception {
        byte[] plain = Round4Fixtures.data(12003);
        byte[] empty = pack(new byte[0]);
        ByteArrayOutputStream members = new ByteArrayOutputStream();
        members.write(empty); members.write(pack(plain)); members.write(empty);
        for (byte[] packed : new byte[][]{empty, members.toByteArray()}) {
            byte[] expected = packed == empty ? new byte[0] : plain;
            for (boolean headerless : new boolean[]{false, true}) {
                int[] closes = {0};
                byte[] payload = headerless ? Arrays.copyOfRange(packed, 2, packed.length) : packed;
                InputStream source = new ByteArrayInputStream(payload) {
                    @Override public void close() { closes[0]++; }
                };
                try (InputStream decoder = alzFactory(source, token())) {
                    assertArrayEquals(expected, decoder.readAllBytes());
                    assertEquals(-1, decoder.read());
                }
                assertEquals(1, closes[0]);
            }
        }
    }

    @Test public void scalarBitReadsUseBoundedBulkSourceRefills() throws Exception {
        byte[] plain = Round4Fixtures.data(190043), packed = pack(plain);
        int[] scalar = {0}, bulk = {0};
        InputStream source = new ByteArrayInputStream(packed) {
            @Override public synchronized int read() { scalar[0]++; return super.read(); }
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                bulk[0]++; assertTrue(length <= 65536);
                return super.read(bytes, offset, length);
            }
        };
        try (InputStream decoder = new ArchiveBzip2InputStream(source, token())) {
            assertArrayEquals(plain, decoder.readAllBytes());
        }
        assertEquals(0, scalar[0]);
        assertTrue(bulk[0] <= (packed.length + 65535) / 65536 + 2);
    }

    @Test public void preCancelledConstructionDoesNotReadOrTakeOwnership() {
        FileOperationProgress progress = token(); progress.cancel();
        int[] reads = {0}, closes = {0};
        InputStream source = new ByteArrayInputStream(new byte[0]) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads[0]++; return -1;
            }
            @Override public void close() { closes[0]++; }
        };
        assertThrows(InterruptedIOException.class, () -> new ArchiveBzip2InputStream(source, progress));
        assertEquals(0, reads[0]); assertEquals(0, closes[0]);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void constructorRefillCancellationAndSourceErrorsRemainDistinct() throws Exception {
        byte[] packed = pack(new byte[]{42});
        for (Throwable error : new Throwable[]{null, new IOException("source"), new AssertionError("source")}) {
            FileOperationProgress progress = token();
            int[] closes = {0};
            InputStream source = new InputStream() {
                @Override public int read() { fail("bulk refill expected"); return -1; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    progress.cancel();
                    if (error instanceof IOException) throw (IOException) error;
                    if (error instanceof Error) throw (Error) error;
                    System.arraycopy(packed, 0, bytes, offset, packed.length);
                    return packed.length;
                }
                @Override public void close() { closes[0]++; }
            };
            Throwable failure = assertThrows(Throwable.class, () -> new ArchiveBzip2InputStream(source, progress));
            if (error == null) assertTrue(failure instanceof InterruptedIOException);
            else assertSame(error, failure);
            assertEquals(0, closes[0]); // Original owner closes a rejected construction.
            source.close(); assertEquals(1, closes[0]);
        }
    }

    @Test public void bufferedCancellationIsStickyButHealthyZeroLengthRemainsNoOp() throws Exception {
        byte[] plain = Round4Fixtures.data(1000);
        FileOperationProgress progress = token();
        try (InputStream decoder = new ArchiveBzip2InputStream(new ByteArrayInputStream(pack(plain)), progress)) {
            assertThrows(IndexOutOfBoundsException.class, () -> decoder.read(new byte[1], -1, 1));
            assertEquals(plain[0] & 255, decoder.read());
            progress.cancel();
            assertEquals(0, decoder.read(new byte[1], 1, 0));
            byte[] output = {91};
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> decoder.read(output));
            assertArrayEquals(new byte[]{91}, output);
            assertSame(failure, assertThrows(IOException.class, decoder::read));
            assertSame(failure, assertThrows(IOException.class, decoder::available));
            assertSame(failure, assertThrows(IOException.class, () -> decoder.skip(1)));
            assertEquals(0, progress.snapshot().doneBytes);
        }
    }

    @Test public void closeFailureIsSuppressedUnderCancellationAndCloseIsNotRepeated() throws Exception {
        IOException closeFailure = new IOException("close");
        FileOperationProgress progress = token(); int[] closes = {0};
        InputStream source = new ByteArrayInputStream(pack(new byte[]{42})) {
            @Override public void close() throws IOException { closes[0]++; throw closeFailure; }
        };
        ArchiveBzip2InputStream decoder = new ArchiveBzip2InputStream(source, progress);
        progress.cancel();
        InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> {
            try (InputStream owned = decoder) { owned.read(); }
        });
        assertArrayEquals(new Throwable[]{closeFailure}, failure.getSuppressed());
        decoder.close(); assertEquals(1, closes[0]);
    }

    @Test public void headerlessEmptyAlzCloseFailureDoesNotBecomeSuccessfulEof() throws Exception {
        byte[] empty = pack(new byte[0]); IOException expected = new IOException("close");
        InputStream source = new ByteArrayInputStream(Arrays.copyOfRange(empty, 2, empty.length)) {
            @Override public void close() throws IOException { throw expected; }
        };
        // SequenceInputStream closes its final source while Commons checks concatenated EOF.
        assertSame(expected, assertThrows(IOException.class, () -> alzFactory(source, token())));
    }

    @Test public void encryptedSplitStandardBzipPreservesOutputAndCipherBoundaries() throws Exception {
        byte[] plain = Round4Fixtures.data(130007), packed = pack(plain);
        for (int format = 0; format < 3; format++) {
            File source = encryptedSplit(format, plain, packed), output = temp.newFolder();
            FileOperationProgress progress = token();
            assertTrue(extract(format == 0, source, output, progress));
            assertArrayEquals(plain, Files.readAllBytes(new File(output, "data.bin").toPath()));
            assertFalse(progress.isCancelled());
            assertEquals(plain.length, progress.snapshot().doneBytes);
        }
    }

    @Test public void cancellationAndCorruptionRestoreEncryptedSplitOutput() throws Exception {
        byte[] plain = Round4Fixtures.data(190043), packed = pack(plain);
        for (int format = 0; format < 3; format++) {
            for (boolean cancel : new boolean[]{false, true}) {
                byte[] input = packed.clone(); if (!cancel) input[input.length - 5] ^= 1;
                File source = encryptedSplit(format, plain, input), output = temp.newFolder();
                File target = new File(output, "data.bin"); byte[] old = {9, 8};
                Files.write(target.toPath(), old);
                FileOperationProgress progress = token();
                if (cancel) progress.setListener(snapshot -> {
                    if (snapshot.doneBytes > 0 && !snapshot.cancelled) progress.cancel();
                });
                final boolean egg = format == 0;
                IOException failure = assertThrows(IOException.class, () -> extract(egg, source, output, progress));
                if (cancel) assertTrue(failure instanceof InterruptedIOException);
                assertArrayEquals(old, Files.readAllBytes(target.toPath()));
                assertArrayEquals(new String[]{"data.bin"}, output.list());
            }
        }
    }

    private File encryptedSplit(int format, byte[] plain, byte[] packed) throws Exception {
        File directory = temp.newFolder();
        byte[] payload = format == 2 ? Arrays.copyOfRange(packed, 2, packed.length) : packed;
        byte[] bytes = format == 0 ? Round4Fixtures.eggStored(plain, 2, payload, true)
                : Round4Fixtures.alzStored(plain, 1, payload, true);
        List<File> parts = format == 0 ? Round4Fixtures.splitEgg(directory, "book", bytes, 5)
                : Round4Fixtures.splitAlz(directory, "book", bytes, 5);
        return parts.get(parts.size() - 1);
    }

    private static boolean extract(boolean egg, File source, File output, FileOperationProgress progress) throws IOException {
        return egg ? EggArchiveReader.extractArchiveIntoDirectory(source, output, Round4Fixtures.PASSWORD, progress)
                : AlzipArchiveReader.extractArchiveIntoDirectory(source, output, Round4Fixtures.PASSWORD, progress);
    }

    private static InputStream alzFactory(InputStream source, FileOperationProgress progress) throws Exception {
        Method method = AlzipArchiveReader.class.getDeclaredMethod("openAlzBzipStream", InputStream.class, FileOperationProgress.class);
        method.setAccessible(true);
        try { return (InputStream) method.invoke(null, source, progress); }
        catch (InvocationTargetException wrapper) {
            Throwable failure = wrapper.getCause();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    private static byte[] pack(byte[] plain) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream packed = new BZip2CompressorOutputStream(bytes)) { packed.write(plain); }
        return bytes.toByteArray();
    }

    private static FileOperationProgress token() { return new FileOperationProgress("test", null); }
}
