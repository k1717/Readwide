package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarStoredRangePreflightTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void negativeLengthPreservesDestination() throws Exception { rejected(0, -1); }
    @Test public void truncatedRangeAtNonzeroPositionPreservesDestination() throws Exception { rejected(2, 3); }
    @Test public void emptyRangePastEofIsRejected() throws Exception { rejected(5, 0); }
    @Test public void extremeLengthCannotOverflowRangeCheck() throws Exception { rejected(2, Long.MAX_VALUE); }

    @Test public void validRangeAndEmptyEofRangeRemainSupported() throws Exception {
        File source = file(new byte[]{1, 2, 3, 4});
        File output = file(new byte[]{42});
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            input.seek(2);
            RarStoredPayloadIO.copyPlainEntryToFile(input, 2, output, null);
            assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(output.toPath()));
            RarStoredPayloadIO.copyPlainEntryToFile(input, 0, output, null);
            assertEquals(0, output.length());
            assertEquals(4, input.getFilePointer());
        }
    }

    private void rejected(long offset, long size) throws Exception {
        File source = file(new byte[]{1, 2, 3, 4});
        File output = file(new byte[]{42});
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(42);
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            input.seek(offset);
            assertThrows(IOException.class,
                    () -> RarStoredPayloadIO.copyPlainEntryToFile(input, size, output, null));
            assertThrows(IOException.class,
                    () -> RarStoredPayloadIO.copyToStream(input, size, stream, null));
            assertEquals(offset, input.getFilePointer());
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new byte[]{42}, stream.toByteArray());
    }

    private File file(byte[] bytes) throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }
}
