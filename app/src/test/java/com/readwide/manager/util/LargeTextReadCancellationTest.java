package com.readwide.manager.util;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class LargeTextReadCancellationTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void staleQueuedReadDoesNotStartAndDoesNotPoisonTheNextTask() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        assertThrows(InterruptedIOException.class, () -> LargeTextPartitionReader.readWhile(
                () -> false, () -> { called.set(true); return 1; }));
        assertFalse(called.get());
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(7, (int) LargeTextPartitionReader.readWhile(() -> true, () -> 7));
    }

    @Test public void nestedReadCannotOverrideParentCancellationAndRestoresItsScope() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        assertThrows(InterruptedIOException.class, () -> LargeTextPartitionReader.readWhile(active::get, () -> {
            assertThrows(InterruptedIOException.class, () -> LargeTextPartitionReader.readWhile(
                    () -> false, () -> 1));
            assertEquals(2, (int) LargeTextPartitionReader.readWhile(() -> true, () -> 2));
            active.set(false);
            return LargeTextPartitionReader.readWhile(() -> true, () -> 3);
        }));
        assertEquals(4, (int) LargeTextPartitionReader.readWhile(() -> true, () -> 4));
    }

    @Test public void cancellationClosesCursorAndUncancelledRetryPreservesExactFields() throws Exception {
        File file = temporaryFolder.newFile("cancelled-partition.txt");
        Files.write(file.toPath(), "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8));
        AtomicBoolean active = new AtomicBoolean(true);
        LargeTextPartitionReader.ForwardCursor cursor = new LargeTextPartitionReader.ForwardCursor();
        try {
            assertThrows(InterruptedIOException.class, () -> LargeTextPartitionReader.readWhile(active::get, () ->
                    LargeTextPartitionReader.readPartitionAtStartLineTransformed(
                            file, "UTF-8", false, 1, 4, 19, 2, 1, 1, false, 1,
                            line -> { active.set(false); return line; }, cursor)));
            assertNull(cursor.reader);
            assertTrue(cursor.queue.isEmpty());
            assertFalse(Thread.currentThread().isInterrupted());
            LargeTextLinePartitionResult expected = read(file, null);
            LargeTextLinePartitionResult actual = LargeTextPartitionReader.readWhile(() -> true, () -> read(file, cursor));
            assertEquals(expected.content, actual.content);
            assertEquals(expected.baseCharOffset, actual.baseCharOffset);
            assertEquals(expected.bodyStartCharCount, actual.bodyStartCharCount);
            assertEquals(expected.bodyCharCount, actual.bodyCharCount);
            assertEquals(expected.startLine, actual.startLine);
            assertEquals(expected.endLine, actual.endLine);
            assertEquals(expected.totalLines, actual.totalLines);
            assertEquals(expected.totalChars, actual.totalChars);
        } finally { cursor.closeQuietly(); }
    }

    private LargeTextLinePartitionResult read(File file, LargeTextPartitionReader.ForwardCursor cursor)
            throws java.io.IOException {
        return LargeTextPartitionReader.readPartitionAtStartLineTransformed(
                file, "UTF-8", false, 1, 4, 19, 2, 1, 1, false, 1, line -> line, cursor);
    }
}
