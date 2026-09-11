package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Source regressions for the raw stream shared by ALZ and EGG; no codec claims. */
public class SplitVolumeInputTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void physicalSlicesCrossVolumesWithoutFramingBytes() throws Exception {
        File first = file(90, 1, 2, 91);
        File second = file(92, 93, 3, 4, 5, 94);
        try (SplitVolumeInput in = input(segment(first, 1, 2), segment(second, 2, 3))) {
            assertEquals(5L, in.length());
            byte[] all = new byte[5];
            in.readFully(all);
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, all);
            assertEquals(-1, in.read());
            byte[] middle = new byte[3];
            assertEquals(3, in.readAt(1, middle, 0, 3));
            assertArrayEquals(new byte[] {2, 3, 4}, middle);
            assertEquals(5L, in.getFilePointer());
            try (InputStream child = in.boundedStream(1, 3)) {
                assertEquals(3, child.read(middle));
                assertArrayEquals(new byte[] {2, 3, 4}, middle);
                assertEquals(-1, child.read());
            }
        }
    }

    @Test public void binaryLookupSkipsEmptySegmentsAndPreservesEveryByte() throws Exception {
        File data = temp.newFile();
        byte[] expected = new byte[128];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
        Files.write(data.toPath(), expected);
        List<SplitVolumeInput.Segment> parts = new ArrayList<>();
        for (int i = 0; i < expected.length; i++) {
            parts.add(segment(data, i, 0));
            parts.add(segment(data, i, 1));
        }
        parts.add(segment(data, expected.length, 0));
        try (SplitVolumeInput in = new SplitVolumeInput(parts)) {
            byte[] result = new byte[expected.length];
            in.readFully(result);
            assertArrayEquals(expected, result);
            for (int i = expected.length - 1; i >= 0; i--) {
                assertEquals(1, in.readAt(i, result, 0, 1));
                assertEquals(expected[i], result[0]);
            }
            assertEquals(-1, in.readAt(expected.length, result, 0, 1));
        }
    }

    @Test public void allEmptySegmentsHaveNormalEofAndZeroLengthReads() throws Exception {
        File empty = file();
        try (SplitVolumeInput in = input(segment(empty, 0, 0), segment(empty, 0, 0));
             InputStream child = in.boundedStream(0, 0)) {
            assertEquals(0L, in.length());
            assertEquals(-1, in.read());
            assertEquals(-1, child.read());
            assertEquals(0, child.read(new byte[0]));
            assertEquals(0L, child.skip(Long.MAX_VALUE));
        }
    }

    @Test public void constructorRejectsInvalidPhysicalExtentsAndClosesEarlierFiles() throws Exception {
        File data = file(1, 2, 3);
        long[][] ranges = {{-1, 1}, {0, -1}, {4, 0}, {2, 2}, {1, Long.MAX_VALUE}};
        for (long[] range : ranges) {
            expectIo(() -> {
                try (SplitVolumeInput ignored = input(segment(data, 0, 1),
                        segment(data, range[0], range[1]))) {
                    fail("Invalid physical range accepted");
                }
            });
        }
        // On Windows, an unclosed RandomAccessFile would prevent this move.
        Files.move(data.toPath(), new File(temp.getRoot(), "closed-after-constructor.bin").toPath());
    }

    @Test public void boundedWindowsRejectOverrunWithoutOverflowOrPoisoningReader() throws Exception {
        File data = file(1, 2, 3);
        try (SplitVolumeInput in = input(segment(data, 0, 3))) {
            long[][] ranges = {{-1, 0}, {0, -1}, {4, 0}, {2, 2},
                    {1, Long.MAX_VALUE}, {Long.MAX_VALUE, 1}};
            for (long[] range : ranges) {
                expectIo(() -> in.boundedStream(range[0], range[1]));
            }
            try (InputStream eof = in.boundedStream(3, 0)) {
                assertEquals(-1, eof.read());
            }
            assertEquals(1, in.read());
        }
    }

    @Test public void callerArgumentErrorsAreCheckedAtEofAndDoNotRetireOwner() throws Exception {
        File data = file(7);
        try (SplitVolumeInput in = input(segment(data, 0, 1));
             InputStream eof = in.boundedStream(1, 0)) {
            expectBounds(() -> eof.read(new byte[1], -1, 1));
            expectBounds(() -> eof.read(new byte[1], 0, -1));
            expectBounds(() -> eof.read(new byte[1], 2, 0));
            expectBounds(() -> eof.read(new byte[1], 0, 2));
            try {
                eof.read(null, 0, 0);
                fail("Null buffer accepted");
            } catch (NullPointerException expected) { }
            expectBounds(() -> in.readAt(0, new byte[1], Integer.MAX_VALUE, 1));
            expectIo(() -> in.readAt(-1, new byte[1], 0, 1));
            assertEquals(7, in.read());
        }
    }

    @Test public void childCloseDoesNotCloseOwnerOrSibling() throws Exception {
        File data = file(1, 2, 3);
        try (SplitVolumeInput in = input(segment(data, 0, 3));
             InputStream first = in.boundedStream(0, 1);
             InputStream sibling = in.boundedStream(1, 2)) {
            first.close();
            first.close();
            expectIo(first::read);
            expectIo(() -> first.read(new byte[0]));
            expectIo(() -> first.skip(0));
            expectIo(first::available);
            assertEquals(2, sibling.read());
            assertEquals(1, in.read());
        }
    }

    @Test public void parentCloseInvalidatesEvenEmptyChildren() throws Exception {
        File data = file(1);
        try (SplitVolumeInput in = input(segment(data, 0, 1));
             InputStream child = in.boundedStream(1, 0)) {
            in.close();
            expectIo(child::read);
            expectIo(() -> child.read(new byte[0]));
            expectIo(() -> child.skip(0));
            expectIo(child::available);
            expectIo(() -> in.boundedStream(0, 0));
            expectIo(() -> in.seek(0));
        }
    }

    @Test public void boundedSkipClampsWithoutMovingSequentialCursor() throws Exception {
        File data = file(1, 2, 3, 4, 5);
        try (SplitVolumeInput in = input(segment(data, 0, 5));
             InputStream child = in.boundedStream(1, 3)) {
            assertEquals(3, child.available());
            assertEquals(0L, child.skip(-1));
            assertEquals(1L, child.skip(1));
            assertEquals(3, child.read());
            assertEquals(1L, child.skip(Long.MAX_VALUE));
            assertEquals(0, child.available());
            assertEquals(-1, child.read());
            assertEquals(0L, in.getFilePointer());
            assertEquals(1, in.read());
        }
    }

    @Test public void positionalAndSequentialSingleByteViewsStayIndependent() throws Exception {
        File data = file(255, 2, 128, 4);
        try (SplitVolumeInput in = input(segment(data, 0, 2), segment(data, 2, 2));
             InputStream left = in.boundedStream(0, 2);
             InputStream right = in.boundedStream(2, 2)) {
            assertEquals(255, left.read());
            assertEquals(128, right.read());
            assertEquals(255, in.read());
            assertEquals(2, left.read());
            assertEquals(4, right.read());
            assertEquals(1L, in.getFilePointer());
            in.seek(Long.MAX_VALUE);
            assertEquals(-1, in.read());
            assertEquals(0, in.read(new byte[0], 0, 0));
            in.seek(0);
            assertEquals(255, in.read());
        }
    }

    @Test public void interruptionRetiresReaderWithoutClearingThreadFlag() throws Exception {
        File data = file(1, 2);
        try (SplitVolumeInput in = input(segment(data, 0, 2));
             InputStream child = in.boundedStream(0, 2)) {
            try {
                Thread.currentThread().interrupt();
                try {
                    child.read();
                    fail("Interrupted read accepted");
                } catch (InterruptedIOException expected) {
                    assertTrue(Thread.currentThread().isInterrupted());
                }
            } finally {
                Thread.interrupted();
            }
            expectIo(in::read);
            expectIo(child::read);
            expectIo(() -> in.seek(0));
        }
    }

    @Test public void interruptedConstructionFailsBeforeOpeningVolume() throws Exception {
        File data = file(1);
        try {
            Thread.currentThread().interrupt();
            try (SplitVolumeInput ignored = input(segment(data, 0, 1))) {
                fail("Interrupted construction accepted");
            } catch (InterruptedIOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {
            Thread.interrupted();
        }
        Files.move(data.toPath(), new File(temp.getRoot(), "cancelled.bin").toPath());
    }

    @Test public void partialPhysicalFailureCannotReplayAfterFileIsRepaired() throws Exception {
        File first = file(1, 2);
        File second = file(3, 4);
        try (SplitVolumeInput in = input(segment(first, 0, 2), segment(second, 0, 2));
             InputStream child = in.boundedStream(0, 4)) {
            try (RandomAccessFile truncated = new RandomAccessFile(second, "rw")) {
                truncated.setLength(0);
            }
            byte[] buffer = new byte[4];
            expectIo(() -> in.read(buffer, 0, buffer.length));
            assertArrayEquals(new byte[] {1, 2, 0, 0}, buffer);
            assertEquals(0L, in.getFilePointer());
            Files.write(second.toPath(), new byte[] {3, 4});
            expectIo(in::read);
            expectIo(child::read);
            expectIo(() -> in.readAt(0, buffer, 0, buffer.length));
            Files.move(first.toPath(), new File(temp.getRoot(), "retired-first.bin").toPath());
            Files.move(second.toPath(), new File(temp.getRoot(), "retired-second.bin").toPath());
        }
    }

    private File file(int... values) throws IOException {
        File file = temp.newFile();
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static SplitVolumeInput.Segment segment(File file, long offset, long length) {
        return new SplitVolumeInput.Segment(file, offset, length);
    }

    private static SplitVolumeInput input(SplitVolumeInput.Segment... segments) throws IOException {
        return new SplitVolumeInput(Arrays.asList(segments));
    }

    private interface IoAction { void run() throws IOException; }

    private static void expectIo(IoAction action) throws IOException {
        try {
            action.run();
            fail("Expected IOException");
        } catch (IOException expected) { }
    }

    private static void expectBounds(IoAction action) throws IOException {
        try {
            action.run();
            fail("Expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) { }
    }
}
