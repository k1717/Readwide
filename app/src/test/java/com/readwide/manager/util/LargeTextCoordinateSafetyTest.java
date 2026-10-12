package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.model.LargeTextLinePartitionResult;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/** Exercises the real cursor read path with tiny buffers and synthetic high coordinates. */
public class LargeTextCoordinateSafetyTest {
    private static final int RULES_VERSION = 79;
    private static final String SOURCE = "fresh\nnext\n";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void finalRealEndAtMaxIsAcceptedWithoutClampingItsBase() throws Exception {
        File file = sourceFile();
        LargeTextPartitionReader.ForwardCursor cursor = cursorAt(file, 1,
                Integer.MAX_VALUE - 3L, "abc");
        TrackingReader reader = (TrackingReader) cursor.reader;
        try {
            LargeTextLinePartitionResult result = readBoundary(file, cursor, 1, 1, 0);
            assertEquals("abc", result.content);
            assertEquals(Integer.MAX_VALUE - 3, result.baseCharOffset);
            assertEquals(Integer.MAX_VALUE, result.totalChars);
            assertEquals(3, result.bodyCharCount);
            assertEquals(Integer.MAX_VALUE, (long) result.baseCharOffset + result.content.length());
            assertCleared(cursor, reader);
        } finally {
            cursor.closeQuietly();
        }
    }

    @Test public void finalEmptyLineMayStartAtMax() throws Exception {
        File file = sourceFile();
        LargeTextPartitionReader.ForwardCursor cursor = cursorAt(file, 1,
                Integer.MAX_VALUE, "");
        TrackingReader reader = (TrackingReader) cursor.reader;
        try {
            LargeTextLinePartitionResult result = readBoundary(file, cursor, 1, 1, 0);
            assertEquals("", result.content);
            assertEquals(Integer.MAX_VALUE, result.baseCharOffset);
            assertEquals(Integer.MAX_VALUE, result.totalChars);
            assertEquals(0, result.bodyCharCount);
            assertCleared(cursor, reader);
        } finally {
            cursor.closeQuietly();
        }
    }

    @Test public void endBeyondMaxRejectsAndFreshReadRecoversAfterCursorCleanup() throws Exception {
        File file = sourceFile();
        LargeTextPartitionReader.ForwardCursor cursor = cursorAt(file, 1,
                Integer.MAX_VALUE - 2L, "abc");
        assertRejectedAndRecoverable(file, cursor, 1, 1, "TXT character position");
    }

    @Test public void nextEmptyLineCannotStartAtMaxPlusOneEvenAsCarry() throws Exception {
        File file = sourceFile();
        LargeTextPartitionReader.ForwardCursor cursor = cursorAt(file, 1,
                Integer.MAX_VALUE - 3L, "abc", "");
        // The first line itself is valid. The following carry would convert its
        // synthetic separator into real content and needs an unrepresentable start.
        assertRejectedAndRecoverable(file, cursor, 1, 2, "TXT character position");
    }

    @Test public void onePastLastLineCheckpointRetainsValidTailWithoutWrapping() throws Exception {
        File file = sourceFile();
        LargeTextPartitionReader.ForwardCursor cursor = cursorAt(file, Integer.MAX_VALUE,
                Integer.MAX_VALUE - 1L, "x");
        TrackingReader reader = (TrackingReader) cursor.reader;
        try {
            LargeTextLinePartitionResult first = readBoundary(file, cursor,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
            assertEquals("x", first.content);
            assertEquals(Integer.MAX_VALUE, first.startLine);
            assertEquals(Integer.MAX_VALUE, first.endLine);
            assertTrue(reader.closed);
            assertNull(cursor.reader);
            assertNotNull(cursor.filePath);
            assertEquals(Integer.MAX_VALUE, cursor.queueStartLine);
            assertEquals(Integer.MAX_VALUE - 1L, cursor.charsBeforeQueue);
            assertEquals(1, cursor.queue.size());
            assertEquals("x", cursor.queue.peekFirst());

            LargeTextLinePartitionResult replay = readBoundary(file, cursor,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
            assertEquivalent(first, replay);
            assertEquals(Integer.MAX_VALUE, cursor.queueStartLine);
        } finally {
            cursor.closeQuietly();
        }
    }

    @Test public void realLineBeyondMaxRejectsWithoutRetainingPoisonedCursor() throws Exception {
        File file = sourceFile();
        LargeTextPartitionReader.ForwardCursor cursor = cursorAt(file, Integer.MAX_VALUE,
                Integer.MAX_VALUE - 1L, "", "");
        assertRejectedAndRecoverable(file, cursor, Integer.MAX_VALUE,
                Integer.MAX_VALUE, "TXT line number");
    }

    @Test public void partitionResultAcceptsLargestRepresentableEnd() {
        LargeTextLinePartitionResult result = new LargeTextLinePartitionResult(
                "abc", 1, 1, 1, 1, Integer.MAX_VALUE - 3,
                0, 3, 1, false, 0);
        assertEquals(Integer.MAX_VALUE - 3, result.baseCharOffset);
        assertEquals(Integer.MAX_VALUE, result.totalChars);
        assertEquals("abc", result.content);
    }

    @Test public void partitionResultCannotPublishWrappedEnd() {
        assertThrows(IllegalArgumentException.class, () -> new LargeTextLinePartitionResult(
                "abc", 1, 1, 1, 1, Integer.MAX_VALUE - 2,
                0, 3, 1, false, Integer.MAX_VALUE));
    }

    private void assertRejectedAndRecoverable(File file,
            LargeTextPartitionReader.ForwardCursor cursor, int requestedLine,
            int totalLines, String expectedMessage) throws Exception {
        TrackingReader reader = (TrackingReader) cursor.reader;
        try {
            IOException failure = assertThrows(IOException.class,
                    () -> readBoundary(file, cursor, requestedLine, totalLines, 0));
            assertTrue(failure.getMessage().startsWith(expectedMessage));
            assertCleared(cursor, reader);

            LargeTextLinePartitionResult expected = readFresh(file, null);
            LargeTextLinePartitionResult recovered = readFresh(file, cursor);
            assertEquivalent(expected, recovered);
            assertEquals("fresh", recovered.content);
            assertEquals(0, recovered.baseCharOffset);
            assertEquals(file.getAbsolutePath(), cursor.filePath);
        } finally {
            cursor.closeQuietly();
        }
    }

    private File sourceFile() throws IOException {
        File file = temporaryFolder.newFile();
        Files.write(file.toPath(), SOURCE.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static LargeTextPartitionReader.ForwardCursor cursorAt(File file,
            int line, long charsBeforeQueue, String... pendingLines) {
        LargeTextPartitionReader.ForwardCursor cursor = new LargeTextPartitionReader.ForwardCursor();
        cursor.filePath = file.getAbsolutePath();
        cursor.fileLength = file.length();
        cursor.fileModified = file.lastModified();
        cursor.encoding = "UTF-8";
        cursor.collapseBlankLines = false;
        cursor.rulesVersion = RULES_VERSION;
        cursor.reader = new TrackingReader();
        cursor.queueStartLine = line;
        cursor.charsBeforeQueue = charsBeforeQueue;
        Collections.addAll(cursor.queue, pendingLines);
        return cursor;
    }

    private static LargeTextLinePartitionResult readBoundary(File file,
            LargeTextPartitionReader.ForwardCursor cursor, int requestedLine,
            int totalLines, int lookbehind) throws IOException {
        return LargeTextPartitionReader.readPartitionAtStartLineTransformed(file, "UTF-8", false,
                requestedLine, totalLines, Integer.MAX_VALUE, 1, 0, lookbehind,
                false, RULES_VERSION, line -> line, cursor);
    }

    private static LargeTextLinePartitionResult readFresh(File file,
            LargeTextPartitionReader.ForwardCursor cursor) throws IOException {
        return LargeTextPartitionReader.readPartitionAtStartLineTransformed(file, "UTF-8", false,
                1, 2, SOURCE.length(), 1, 0, 0,
                false, RULES_VERSION, line -> line, cursor);
    }

    private static void assertCleared(LargeTextPartitionReader.ForwardCursor cursor,
            TrackingReader reader) {
        assertTrue("the read-owned stream must be closed", reader.closed);
        assertNull(cursor.reader);
        assertNull(cursor.filePath);
        assertTrue(cursor.queue.isEmpty());
    }

    private static void assertEquivalent(LargeTextLinePartitionResult expected,
            LargeTextLinePartitionResult actual) {
        assertEquals(expected.content, actual.content);
        assertEquals(expected.lineCount, actual.lineCount);
        assertEquals(expected.startLine, actual.startLine);
        assertEquals(expected.endLine, actual.endLine);
        assertEquals(expected.totalLines, actual.totalLines);
        assertEquals(expected.baseCharOffset, actual.baseCharOffset);
        assertEquals(expected.bodyStartCharCount, actual.bodyStartCharCount);
        assertEquals(expected.bodyCharCount, actual.bodyCharCount);
        assertEquals(expected.windowStartLine, actual.windowStartLine);
        assertEquals(expected.includesLookbehind, actual.includesLookbehind);
        assertEquals(expected.totalChars, actual.totalChars);
    }

    private static final class TrackingReader extends BufferedReader {
        boolean closed;

        TrackingReader() { super(new StringReader("")); }

        @Override public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
