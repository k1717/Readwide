package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Resolve, mutate, then adopt the same segments: reparsing would hide the handoff gap. */
public class AlzEggSourceHandoffTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void eggDiscardedContinuationLinkChangeIsRejected() throws Exception {
        List<File> files = split(true);
        List<SplitVolumeInput.Segment> segments = resolve(true, files.get(0));
        assertTrue(segments.get(1).dataOffset > 21);
        editByte(files.get(1), 21); // Previous-volume ID, stripped from the logical stream.
        assertRejected(segments);
    }

    @Test public void alzDiscardedIntermediateTrailerChangeIsRejected() throws Exception {
        List<File> files = split(false);
        List<SplitVolumeInput.Segment> segments = resolve(false, files.get(0));
        File middle = files.get(1);
        assertEquals(middle.length() - 16, segments.get(1).dataOffset + segments.get(1).length);
        editByte(middle, middle.length() - 1);
        assertRejected(segments);
    }

    @Test public void alzDiscardedContinuationHeaderChangeIsRejected() throws Exception {
        List<File> files = split(false);
        List<SplitVolumeInput.Segment> segments = resolve(false, files.get(2));
        assertEquals(8, segments.get(1).dataOffset);
        editByte(files.get(1), 0);
        assertRejected(segments);
    }

    @Test public void firstVolumeChangeIsRejectedForBothFormats() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            List<File> files = split(egg);
            List<SplitVolumeInput.Segment> segments = resolve(egg, files.get(2));
            editByte(files.get(0), 0);
            assertRejected(segments);
        }
    }

    @Test public void standaloneChangeIsRejectedForBothFormats() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            File file = standalone(egg);
            List<SplitVolumeInput.Segment> segments = resolve(egg, file);
            editByte(file, 0);
            assertRejected(segments);
        }
    }

    @Test public void rawAlzContinuationChangeIsRejected() throws Exception {
        List<File> files = split(false);
        removeAlzFraming(files.get(1));
        List<SplitVolumeInput.Segment> segments = resolve(false, files.get(0));
        assertEquals(0, segments.get(1).dataOffset);
        editByte(files.get(1), 0);
        assertRejected(segments);
    }

    @Test public void growthIsRejectedEvenWhenOldPayloadWindowStillFits() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            List<File> files = split(egg);
            List<SplitVolumeInput.Segment> segments = resolve(egg, files.get(0));
            File middle = files.get(1);
            long modified = middle.lastModified();
            try (RandomAccessFile edit = new RandomAccessFile(middle, "rw")) {
                edit.setLength(edit.length() + 1);
            }
            assertTrue(middle.setLastModified(modified)); // Length alone must reject it.
            assertRejected(segments);
        }
    }

    @Test public void disappearedVolumeIsRejectedDuringAdoption() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            List<File> files = split(egg);
            List<SplitVolumeInput.Segment> segments = resolve(egg, files.get(0));
            Files.delete(files.get(1).toPath());
            assertRejected(segments);
        }
    }

    @Test public void stableEggChainRetainsLogicalBytesAndTrimmedWindows() throws Exception {
        List<File> files = split(true);
        List<SplitVolumeInput.Segment> segments = resolve(true, files.get(2));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < files.size(); i++) {
            byte[] physical = Files.readAllBytes(files.get(i).toPath());
            int start = i == 0 ? 0 : 33;
            assertEquals(start, segments.get(i).dataOffset);
            assertEquals(physical.length - start, segments.get(i).length);
            expected.write(physical, start, physical.length - start);
        }
        assertLogicalBytes(segments, expected.toByteArray());
    }

    @Test public void stableAlzChainRetainsFinalTrailerAndLogicalBytes() throws Exception {
        List<File> files = split(false);
        AlzVolumeResolver.VolumeSet set = AlzVolumeResolver.resolve(files.get(2));
        assertTrue(set.retainedFinalTrailer);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < files.size(); i++) {
            byte[] physical = Files.readAllBytes(files.get(i).toPath());
            int start = i == 0 ? 0 : 8;
            int end = physical.length - (i == files.size() - 1 ? 0 : 16);
            assertEquals(start, set.segments.get(i).dataOffset);
            assertEquals(end - start, set.segments.get(i).length);
            expected.write(physical, start, end - start);
        }
        assertLogicalBytes(set.segments, expected.toByteArray());
    }

    @Test public void stableStandaloneArchivesKeepAllBytes() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            File file = standalone(egg);
            assertLogicalBytes(resolve(egg, file), Files.readAllBytes(file.toPath()));
        }
    }

    @Test public void legacyEggPrefixWithoutEndMarkerStillWorks() throws Exception {
        File file = standalone(true);
        byte[] original = Files.readAllBytes(file.toPath());
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        legacy.write(original, 0, 14);
        legacy.write(original, 18, original.length - 18);
        Files.write(file.toPath(), legacy.toByteArray());
        assertLogicalBytes(resolve(true, file), legacy.toByteArray());
    }

    @Test public void stableRawAlzContinuationKeepsItsWholeWindow() throws Exception {
        List<File> files = split(false);
        removeAlzFraming(files.get(1));
        List<SplitVolumeInput.Segment> segments = resolve(false, files.get(0));
        assertEquals(0, segments.get(1).dataOffset);
        assertEquals(files.get(1).length(), segments.get(1).length);
        try (SplitVolumeInput input = new SplitVolumeInput(segments)) {
            input.seek(segments.get(0).length);
            byte[] actual = new byte[(int) segments.get(1).length];
            input.readFully(actual);
            assertArrayEquals(Files.readAllBytes(files.get(1).toPath()), actual);
            input.requireUnchanged();
        }
    }

    @Test public void plainSegmentsStillCaptureMetadataWhenAdopted() throws Exception {
        File file = Round4Fixtures.write(temp.newFolder(), "plain.7z.001", new byte[]{1, 2, 3});
        SplitVolumeInput.Segment segment = new SplitVolumeInput.Segment(file, 0, file.length());
        editByte(file, 0);
        assertLogicalBytes(Collections.singletonList(segment), new byte[]{0, 2, 3});
    }

    @Test public void capturedStateRejectsMetadataChangeEvenWithOpenHandle() throws Exception {
        File file = standalone(true);
        SplitVolumeInput.SourceState state = SplitVolumeInput.SourceState.capture(file);
        try (RandomAccessFile input = new RandomAccessFile(state.file, "r")) {
            state.requireUnchanged(input.length());
            editByte(file, 0);
            assertThrows(IOException.class, () -> state.requireUnchanged(input.length()));
        }
    }

    @Test public void adoptedReaderKeepsCapturedBaselineForLaterChecks() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            List<File> files = split(egg);
            try (SplitVolumeInput input = new SplitVolumeInput(resolve(egg, files.get(0)))) {
                input.read();
                editByte(files.get(0), 0);
                assertThrows(IOException.class, input::requireUnchanged);
                assertThrows(IOException.class, input::read);
            }
        }
    }

    @Test public void cancelledAdoptionPreservesInterruptFlag() throws Exception {
        for (boolean egg : new boolean[]{true, false}) {
            List<SplitVolumeInput.Segment> segments = resolve(egg, standalone(egg));
            try {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedIOException.class, () -> adoptAndClose(segments));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    private File standalone(boolean egg) throws Exception {
        byte[] data = Round4Fixtures.data(1024);
        return Round4Fixtures.write(temp.newFolder(), egg ? "book.egg" : "book.alz",
                egg ? Round4Fixtures.egg(data, 0, false) : Round4Fixtures.alz(data, 0, false));
    }

    private List<File> split(boolean egg) throws Exception {
        byte[] data = Round4Fixtures.data(1024);
        File folder = temp.newFolder();
        return egg ? Round4Fixtures.splitEgg(folder, "book", Round4Fixtures.egg(data, 0, false), 3)
                : Round4Fixtures.splitAlz(folder, "book", Round4Fixtures.alz(data, 0, false), 3);
    }

    private static List<SplitVolumeInput.Segment> resolve(boolean egg, File file) throws IOException {
        return egg ? EggVolumeResolver.resolve(file).segments : AlzVolumeResolver.resolve(file).segments;
    }

    private static void editByte(File file, long offset) throws IOException {
        long size = file.length(), modified = file.lastModified();
        try (RandomAccessFile edit = new RandomAccessFile(file, "rw")) {
            edit.seek(offset);
            int value = edit.readUnsignedByte();
            edit.seek(offset);
            edit.write(value ^ 1);
        }
        assertEquals(size, file.length());
        assertTrue(file.setLastModified(modified + 10000));
        assertTrue(file.lastModified() != modified);
    }

    private static void removeAlzFraming(File file) throws IOException {
        byte[] physical = Files.readAllBytes(file.toPath());
        Files.write(file.toPath(), Arrays.copyOfRange(physical, 8, physical.length - 16));
    }

    private static void assertRejected(List<SplitVolumeInput.Segment> segments) {
        assertThrows(IOException.class, () -> adoptAndClose(segments));
    }

    private static void adoptAndClose(List<SplitVolumeInput.Segment> segments) throws IOException {
        try (SplitVolumeInput ignored = new SplitVolumeInput(segments)) { }
    }

    private static void assertLogicalBytes(List<SplitVolumeInput.Segment> segments, byte[] expected)
            throws IOException {
        try (SplitVolumeInput input = new SplitVolumeInput(segments)) {
            assertEquals(expected.length, input.length());
            byte[] actual = new byte[expected.length];
            input.readFully(actual);
            input.requireUnchanged();
            assertArrayEquals(expected, actual);
            assertEquals(expected.length, input.getFilePointer());
            assertEquals(-1, input.read());
        }
    }
}
