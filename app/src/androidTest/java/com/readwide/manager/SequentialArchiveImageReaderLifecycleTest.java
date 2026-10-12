package com.readwide.manager;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.archive.ArchiveSupport;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Injected close failures only; this does not exercise native decoders. */
@RunWith(AndroidJUnit4.class)
public class SequentialArchiveImageReaderLifecycleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Test public void checkedCloseFailureClearsPasswordAndCanRetry() throws Exception {
        verifyClose(new IOException("close"));
    }
    @Test public void uncheckedCloseFailureClearsPasswordAndCanRetry() throws Exception {
        verifyClose(new IllegalStateException("close"));
    }
    @Test public void closeErrorStillClearsPasswordAndRetainsRetryOwnership() throws Exception {
        verifyClose(new AssertionError("close"));
    }

    private void verifyClose(Throwable failure) throws Exception {
        File cache = temporary.newFolder();
        Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return cache; }
        };
        Constructor<SequentialArchiveImageReader> constructor = SequentialArchiveImageReader.class
                .getDeclaredConstructor(Context.class, File.class, char[].class, boolean.class, Set.class);
        constructor.setAccessible(true);
        SequentialArchiveImageReader reader = constructor.newInstance(
                context, temporary.newFile("book.tar"), new char[]{'p'}, true, new HashSet<String>());
        Field active = SequentialArchiveImageReader.class.getDeclaredField("reader"); active.setAccessible(true);
        int[] closes = {0};
        ArchiveSupport.ForwardArchiveReader decoder = new ArchiveSupport.ForwardArchiveReader() {
            @Override public ArchiveSupport.ForwardEntry nextEntry() { throw new AssertionError("Must stay closed"); }
            @Override public int read(byte[] bytes) { throw new AssertionError("Must stay closed"); }
            @Override public void close() throws IOException {
                if (++closes[0] != 1) return;
                if (failure instanceof IOException) throw (IOException) failure;
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw (Error) failure;
            }
        };
        active.set(reader, decoder);
        try {
            if (failure instanceof Error) assertSame(failure, assertThrows(Error.class, reader::close));
            else reader.close();
            Field password = SequentialArchiveImageReader.class.getDeclaredField("password"); password.setAccessible(true);
            assertArrayEquals(new char[]{0}, (char[]) password.get(reader));
            assertSame(decoder, active.get(reader));
            assertFalse(reader.ensureExtracted("page.avif"));
            reader.close(); reader.close();
            assertEquals(2, closes[0]);
            assertNull(active.get(reader));
        } finally { reader.close(); }
    }
}
