package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SevenZForwardCloseRetryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void inputCloseFailureBeforePhysicalCloseRemainsRetryable() throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), Round4Fixtures.data(103), 4, false, false, false, false);
        File directory = temporary.newFolder();
        ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, directory);
        assertNotNull(reader);
        Field input = reader.getClass().getDeclaredField("input"); input.setAccessible(true);
        File file = temporary.newFile(); IOException failure = new IOException("close before release");
        int[] closes = {0};
        input.set(reader, new RandomAccessFile(file, "r") {
            boolean closing;
            @Override public void close() throws IOException {
                if (closing) return;
                if (++closes[0] == 1) throw failure;
                closing = true;
                try { super.close(); } finally { closing = false; }
            }
        });
        try {
            assertSame(failure, assertThrows(IOException.class, reader::close));
            assertNotNull(input.get(reader));
            reader.close(); reader.close();
            assertEquals(2, closes[0]); assertNull(input.get(reader));
            assertThrows(IOException.class, reader::nextEntry);
        } finally { reader.close(); }
    }
}
