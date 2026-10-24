package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;

public class ArchiveExtractionByteBudgetTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void rejectedBufferedAdoptionClosesItsUnreturnedOutput() throws Exception {
        int[] closes = {0};
        OutputStream raw = new OutputStream() {
            @Override public void write(int value) { fail("No write before buffer adoption"); }
            @Override public void close() { closes[0]++; }
        };
        Error primary = new AssertionError("simulated wrapper allocation failure");
        closeUnadopted(raw, primary);
        assertEquals(1, closes[0]);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test public void rejectedBufferedAdoptionSuppressesCleanupWithoutReplacingFailure() throws Exception {
        for (Throwable cleanup : new Throwable[]{new IOException("close"),
                new IllegalStateException("close"), new AssertionError("close")}) {
            Error primary = new AssertionError("simulated wrapper allocation failure");
            closeUnadopted(failedClose(cleanup), primary);
            assertEquals(1, primary.getSuppressed().length);
            assertSame(cleanup, primary.getSuppressed()[0]);
        }
        Error same = new AssertionError("shared failure");
        closeUnadopted(failedClose(same), same);
        assertEquals(0, same.getSuppressed().length);
    }

    @Test public void publicBufferedOutputRetainsAccountingAndCancelledOpenProtection() throws Exception {
        File file = temp.newFile();
        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(8)) {
            try (OutputStream output = ArchiveSupport.openExtractionOutputStream(file)) {
                output.write(new byte[]{4, 5, 6});
                output.flush();
                assertEquals(3, scope.budgetForTest().totalBytesForTest());
            }
        }
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(file.toPath()));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ArchiveSupport.openExtractionOutputStream(file));
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(file.toPath()));
    }

    private static OutputStream failedClose(Throwable failure) {
        return new OutputStream() {
            @Override public void write(int value) { fail("No write before buffer adoption"); }
            @Override public void close() throws IOException {
                if (failure instanceof IOException) throw (IOException) failure;
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw (Error) failure;
            }
        };
    }

    private static void closeUnadopted(OutputStream raw, Throwable failure) throws Exception {
        java.lang.reflect.Method method = ArchiveSupport.class.getDeclaredMethod(
                "closeUnadoptedExtractionOutput", OutputStream.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(null, raw, failure);
    }

    @Test public void cancelledOpenPreservesExistingOutput() throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), new byte[]{7, 8});
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ArchiveExtractionByteBudget.openOutputStream(file, 10));
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(file.toPath()));
    }

    @Test public void rejectedReservationRetiresOnlyThatStream() throws Exception {
        File file = temp.newFile();
        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(2)) {
            try (OutputStream out = ArchiveExtractionByteBudget.openOutputStream(file, 2)) {
                IOException original = assertThrows(IOException.class, () -> out.write(new byte[3]));
                assertSame(original, assertThrows(IOException.class, () -> out.write(1)).getCause());
                assertSame(original, assertThrows(IOException.class, out::flush).getCause());
                assertEquals(0, file.length());
                assertEquals(0, scope.budgetForTest().totalBytesForTest());
            }
            write(file, new byte[]{1, 2}); // Fallback may open a new stream, not reuse the failed one.
            assertEquals(2, scope.budgetForTest().totalBytesForTest());
        }
    }

    @Test public void cancellationStaysTerminalAfterInterruptFlagIsCleared() throws Exception {
        try (OutputStream out = ArchiveExtractionByteBudget.openOutputStream(temp.newFile(), 5)) {
            InterruptedIOException original;
            Thread.currentThread().interrupt();
            try { original = assertThrows(InterruptedIOException.class, () -> out.write(1)); }
            finally { Thread.interrupted(); }
            assertSame(original, assertThrows(IOException.class, () -> out.write(2)).getCause());
        }
    }

    @Test public void invalidArgumentsDoNotPoisonAnOtherwiseWritableStream() throws Exception {
        File file = temp.newFile();
        try (OutputStream out = ArchiveExtractionByteBudget.openOutputStream(file, 5)) {
            assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[1], 2, 1));
            assertThrows(NullPointerException.class, () -> out.write(null, 0, 0));
            out.write(9);
        }
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(file.toPath()));
    }

    @Test public void closedOutputRejectsEmptyWritesAndFlushes() throws Exception {
        OutputStream out = ArchiveExtractionByteBudget.openOutputStream(temp.newFile(), 5);
        out.close(); out.close();
        assertThrows(IOException.class, () -> out.write(new byte[0]));
        assertThrows(IOException.class, () -> out.write(1));
        assertThrows(IOException.class, out::flush);
    }

    @Test public void bufferedCloseCannotReplayAFailedDelegateWrite() throws Exception {
        ProbeOutput raw = new ProbeOutput(); raw.writeFailure = new IOException("partial device write");
        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(10)) {
            BufferedOutputStream out = new BufferedOutputStream(wrap(raw, scope), 8);
            out.write(new byte[]{1, 2, 3});
            assertSame(raw.writeFailure, assertThrows(IOException.class, out::flush));
            assertThrows(IOException.class, out::close);
            assertEquals(1, raw.writes);
            assertEquals(1, raw.closes);
            assertEquals(0, scope.budgetForTest().totalBytesForTest());
        }
    }

    @Test public void uncheckedDelegateFailureRollsBackReservationAndRetiresStream() throws Exception {
        for (Throwable error : new Throwable[]{new IllegalStateException("write failed"), new AssertionError("write failed")}) {
            ProbeOutput raw = new ProbeOutput(); raw.writeFailure = error;
            try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(10);
                 OutputStream out = wrap(raw, scope)) {
                assertSame(error, assertThrows(error.getClass(), () -> out.write(1)));
                assertSame(error, assertThrows(IOException.class, () -> out.write(2)).getCause());
                assertEquals(0, scope.budgetForTest().totalBytesForTest());
            }
            assertEquals(1, raw.writes);
            assertEquals(1, raw.closes);
        }
    }

    @Test public void flushFailureRetiresStreamButDoesNotBlockCleanup() throws Exception {
        ProbeOutput raw = new ProbeOutput(); raw.flushFailure = new IOException("flush failed");
        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(10);
             OutputStream out = wrap(raw, scope)) {
            out.write(1);
            assertSame(raw.flushFailure, assertThrows(IOException.class, out::flush));
            assertSame(raw.flushFailure, assertThrows(IOException.class, () -> out.write(2)).getCause());
            assertEquals(1, scope.budgetForTest().totalBytesForTest());
        }
        assertEquals(1, raw.writes);
        assertEquals(1, raw.closes);
    }

    @Test public void nestedScopeBorrowsAccountingAndDoesNotRemoveOuterScope() throws Exception {
        ArchiveExtractionByteBudget original;
        try (ArchiveExtractionByteBudget.Scope outer = ArchiveExtractionByteBudget.begin(5)) {
            original = outer.budgetForTest();
            try (ArchiveExtractionByteBudget.Scope inner = ArchiveExtractionByteBudget.begin(1)) {
                assertSame(original, inner.budgetForTest());
            }
            write(temp.newFile(), new byte[]{1, 2, 3});
            assertEquals(3, original.totalBytesForTest());
        }
        try (ArchiveExtractionByteBudget.Scope next = ArchiveExtractionByteBudget.begin(5)) {
            assertNotSame(original, next.budgetForTest());
            assertEquals(0, next.budgetForTest().totalBytesForTest());
        }
    }

    private static OutputStream wrap(ProbeOutput raw, ArchiveExtractionByteBudget.Scope scope) throws Exception {
        Class<?> type = Class.forName(ArchiveExtractionByteBudget.class.getName() + "$BudgetedOutputStream");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(
                OutputStream.class, ArchiveExtractionByteBudget.class, String.class, File.class);
        constructor.setAccessible(true);
        return (OutputStream) constructor.newInstance(raw, scope.budgetForTest(), "virtual-output", null);
    }

    private static final class ProbeOutput extends OutputStream {
        Throwable writeFailure;
        IOException flushFailure;
        int writes, closes;
        @Override public void write(int value) throws IOException { attempt(); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { attempt(); }
        private void attempt() throws IOException {
            writes++;
            if (writeFailure instanceof IOException) throw (IOException) writeFailure;
            if (writeFailure instanceof RuntimeException) throw (RuntimeException) writeFailure;
            if (writeFailure instanceof Error) throw (Error) writeFailure;
        }
        @Override public void flush() throws IOException { if (flushFailure != null) throw flushFailure; }
        @Override public void close() { closes++; }
    }

    @Test public void interruptedWriterDoesNotCommitBytes() throws Exception {
        File file = temp.newFile("interrupted.bin");
        try (OutputStream out = ArchiveExtractionByteBudget.openOutputStream(file, Long.MAX_VALUE)) {
            Thread.currentThread().interrupt();
            try { out.write(1); fail("Interrupted extraction must stop"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("interrupted")); }
            finally { Thread.interrupted(); }
        }
        assertEquals(0L, file.length());
    }

    @Test
    public void storageBudgetCanExceedFormer128GiBCeiling() {
        long free = 512L * 1024 * 1024 * 1024;
        assertEquals(free - 64L * 1024 * 1024,
                ArchiveSupport.runtimeExtractionBudgetForUsableSpace(free));
    }

    @Test
    public void freeSpaceReserveAndUnknownSpaceBehaviorArePreserved() {
        assertEquals(0L, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(64L * 1024 * 1024));
        assertEquals(1L, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(64L * 1024 * 1024 + 1));
        assertEquals(Long.MAX_VALUE, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(0L));
        assertEquals(Long.MAX_VALUE, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(-1L));
    }

    @Test
    public void streamAccountingCrosses128GiBButRejectsLongOverflow() throws Exception {
        long formerLimit = 128L * 1024 * 1024 * 1024;
        assertEquals(formerLimit + 1, ArchiveSupport.checkedAddDecodedStreamBytes(formerLimit, 1));
        try {
            ArchiveSupport.checkedAddDecodedStreamBytes(Long.MAX_VALUE, 1);
            fail("Counter overflow must fail");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("overflow")); }
    }

    @Test
    public void sharedAccountingCanCross128GiBWithoutWritingHugeFiles() throws Exception {
        long spaceBudget = 512L * 1024 * 1024 * 1024;
        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(spaceBudget)) {
            ArchiveExtractionByteBudget budget = scope.budgetForTest();
            java.lang.reflect.Method reserve = ArchiveExtractionByteBudget.class.getDeclaredMethod(
                    "reserve", String.class, int.class);
            reserve.setAccessible(true);
            for (int i = 0; i < 65; i++) reserve.invoke(budget, "virtual-entry", Integer.MAX_VALUE);
            assertEquals(65L * Integer.MAX_VALUE, budget.totalBytesForTest());
            assertTrue(budget.totalBytesForTest() > 128L * 1024 * 1024 * 1024);
        }
    }

    @Test
    public void separateUnknownSizeEntriesShareOneOperationBudget() throws Exception {
        File first = temp.newFile("first.bin");
        File second = temp.newFile("second.bin");
        File overflow = temp.newFile("overflow.bin");

        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(5L)) {
            write(first, new byte[] {1, 2, 3});
            write(second, new byte[] {4, 5});
            assertEquals(5L, scope.budgetForTest().totalBytesForTest());

            try {
                write(overflow, new byte[] {6});
                fail("Expected the operation-wide extraction limit to reject the third file");
            } catch (ArchiveSupport.UnsupportedArchiveFeatureException expected) {
                assertTrue(expected.getMessage().contains("safety limit"));
            }
            assertEquals(0L, overflow.length());
            assertEquals(5L, scope.budgetForTest().totalBytesForTest());
        }
    }

    @Test
    public void fallbackRewriteReplacesEarlierPartialFileAccounting() throws Exception {
        File retried = temp.newFile("retried.bin");
        File finalEntry = temp.newFile("final.bin");

        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(5L)) {
            write(retried, new byte[] {1, 2, 3, 4});
            write(retried, new byte[] {8, 9});
            write(finalEntry, new byte[] {5, 6, 7});

            assertEquals(2L, retried.length());
            assertEquals(3L, finalEntry.length());
            assertEquals(5L, scope.budgetForTest().totalBytesForTest());
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        try (OutputStream out = ArchiveExtractionByteBudget.openOutputStream(file, 5L)) {
            out.write(data);
            out.flush();
        }
    }
}
