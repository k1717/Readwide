package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Fault injection at existing ownership boundaries; no RAR decoder fixture claim. */
public class Rar5HistoryCleanupRetryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class RetryFile extends File {
        int attempts;
        RetryFile(File file) { super(file.getPath()); }
        @Override public boolean delete() { return ++attempts > 1 && super.delete(); }
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Rar5HistoryStore history(RetryFile spool) throws Exception {
        Rar5HistoryStore history = new Rar5HistoryStore(4096, 2, temporary.getRoot(), file -> Long.MAX_VALUE);
        field(history, "spool").set(history, spool);
        return history;
    }

    @Test public void decoderRetainsHistoryUntilCleanupSucceeds() throws Exception {
        RetryFile spool = new RetryFile(temporary.newFile());
        Rar5HistoryStore history = history(spool);
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        field(decoder, "window").set(decoder, history);
        field(decoder, "primed").setBoolean(decoder, true);
        assertThrows(IOException.class, decoder::close);
        assertSame(history, field(decoder, "window").get(decoder));
        assertFalse(field(decoder, "primed").getBoolean(decoder));
        assertTrue(spool.isFile());
        decoder.close();
        assertNull(field(decoder, "window").get(decoder));
        assertFalse(spool.exists());
        decoder.close();
        assertEquals(2, spool.attempts);
    }

    @Test public void forwardReaderRetainsDecoderUntilCleanupSucceeds() throws Exception {
        ArchiveSupport.ForwardArchiveReader reader = new Rar5ForwardReader(
                Collections.emptyList(), new char[]{'p'}, temporary.getRoot(), RarVolumeChain.index(Collections.emptyList()));
        Rar5CompressedDecoder decoder = (Rar5CompressedDecoder) field(reader, "decoder").get(reader);
        RetryFile spool = new RetryFile(temporary.newFile());
        field(decoder, "window").set(decoder, history(spool));
        assertThrows(IOException.class, reader::close);
        assertSame(decoder, field(reader, "decoder").get(reader));
        assertArrayEquals(new char[]{0}, (char[]) field(reader, "password").get(reader));
        assertThrows(IOException.class, reader::nextEntry);
        reader.close();
        assertNull(field(reader, "decoder").get(reader));
        assertFalse(spool.exists());
        reader.close();
        assertEquals(2, spool.attempts);
    }
}
