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

/** Injected decoder/close failures only; this does not exercise native decoders. */
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

    @Test public void decoderAllocationFailureRetiresForwardState() throws Exception {
        verifyResourceFailure(new OutOfMemoryError("decoder allocation"), null);
    }

    @Test public void decoderStackFailureRetiresForwardState() throws Exception {
        verifyResourceFailure(new StackOverflowError("decoder stack"), null);
    }

    @Test public void decoderFailureKeepsIdentityWhenCleanupAlsoResourceFails() throws Exception {
        verifyResourceFailure(new OutOfMemoryError("decoder allocation"), new StackOverflowError("close"));
    }

    @Test public void exhaustedScanReleasesDecoderWithoutWaitingForViewerClose() throws Exception {
        verifyExhaustedCleanup(null);
    }

    @Test public void exhaustedScanRetainsFailedCheckedCleanupForRetry() throws Exception {
        verifyExhaustedCleanup(new IOException("close"));
    }

    @Test public void exhaustedScanRetainsFailedUncheckedCleanupForRetry() throws Exception {
        verifyExhaustedCleanup(new IllegalStateException("close"));
    }

    @Test public void cooperativeYieldDoesNotCloseOrExhaustLiveDecoder() throws Exception {
        SequentialArchiveImageReader reader = newSession(false, null);
        int[] advances = {0}, closes = {0};
        ArchiveSupport.ForwardArchiveReader decoder = eofDecoder(advances, closes, null);
        field("reader").set(reader, decoder);
        try {
            assertFalse(reader.ensureExtracted("missing.avif", false, () -> false));
            assertEquals(0, advances[0]);
            assertEquals(0, closes[0]);
            assertSame(decoder, field("reader").get(reader));
            assertFalse(field("exhausted").getBoolean(reader));
            assertFalse(reader.ensureExtracted("missing.avif"));
            assertEquals(1, advances[0]);
            assertEquals(1, closes[0]);
        } finally { reader.close(); }
    }

    @Test public void exhaustedSessionRetainsVerifiedSensitiveCacheAndPasswordForFallback() throws Exception {
        Set<String> verified = new HashSet<>();
        SequentialArchiveImageReader reader = newSession(true, verified);
        int[] advances = {0}, closes = {0};
        field("reader").set(reader, eofDecoder(advances, closes, null));
        ArchivePreviewCache.EntryFiles files = (ArchivePreviewCache.EntryFiles) field("cacheFiles").get(reader);
        File output = files.file("cached.avif");
        File pending = File.createTempFile("ready-", ".tmp", output.getParentFile());
        // Exercise the existing AVIF cache-header gate, not an image decoder.
        java.nio.file.Files.write(pending.toPath(), new byte[]{0,0,0,12,'f','t','y','p','a','v','i','f'});
        try {
            assertTrue(ArchiveImageEntryCache.commitReadyImageFile("cached.avif", pending, output, true, verified));
            assertFalse(reader.ensureExtracted("missing.avif"));
            assertTrue(reader.ensureExtracted("cached.avif"));
            assertTrue(verified.contains(output.getAbsolutePath()));
            assertArrayEquals(new char[]{'p'}, (char[]) field("password").get(reader));
            assertFalse("EOF is not decoder corruption", field("broken").getBoolean(reader));
            assertFalse("Keep independent cache/fallback session alive", field("closed").getBoolean(reader));
            assertEquals(1, advances[0]);
            assertEquals(1, closes[0]);
        } finally { reader.close(); }
    }

    @Test public void forwardBufferIsReusedAcrossPagesAndReleasedAtEof() throws Exception {
        verifyBufferReuse(false);
    }

    @Test public void sensitiveBufferIsScrubbedBetweenPagesWithoutReallocation() throws Exception {
        verifyBufferReuse(true);
    }

    @Test public void failedDecoderCloseStillClearsAndReleasesScratch() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("close"), new IllegalStateException("close"), new AssertionError("close")}) {
            SequentialArchiveImageReader reader = newSession(true, new HashSet<>());
            byte[] scratch = {1, 2, 3}; field("readBuffer").set(reader, scratch);
            int[] closes = {0};
            field("reader").set(reader, new ArchiveSupport.ForwardArchiveReader() {
                @Override public ArchiveSupport.ForwardEntry nextEntry() { throw new AssertionError("Closed"); }
                @Override public int read(byte[] bytes) { throw new AssertionError("Closed"); }
                @Override public void close() throws IOException {
                    if (++closes[0] != 1) return;
                    if (failure instanceof IOException) throw (IOException) failure;
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    throw (Error) failure;
                }
            });
            try {
                if (failure instanceof Error) assertSame(failure, assertThrows(Error.class, reader::close));
                else reader.close();
                assertNull(field("readBuffer").get(reader));
                assertArrayEquals(new byte[3], scratch);
                reader.close(); assertEquals(2, closes[0]);
            } finally { reader.close(); }
        }
    }

    @Test public void midEntryFailureClearsScratchAndKeepsExistingRetirementPolicy() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("read"), new IllegalStateException("read"), new OutOfMemoryError("read")}) {
            SequentialArchiveImageReader reader = newSession(false, null);
            ArchiveSupport.ForwardEntry entry = imageEntry("failed.avif");
            byte[][] used = {null}; int[] closes = {0};
            field("reader").set(reader, new ArchiveSupport.ForwardArchiveReader() {
                @Override public ArchiveSupport.ForwardEntry nextEntry() { return entry; }
                @Override public int read(byte[] bytes) throws IOException {
                    used[0] = bytes; java.util.Arrays.fill(bytes, (byte) 42);
                    if (failure instanceof IOException) throw (IOException) failure;
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    throw (Error) failure;
                }
                @Override public void close() { closes[0]++; }
            });
            try {
                if (failure instanceof Error) assertSame(failure, assertThrows(Error.class, () -> reader.ensureExtracted("failed.avif")));
                else assertFalse(reader.ensureExtracted("failed.avif"));
                assertNotNull(used[0]); assertArrayEquals(new byte[used[0].length], used[0]);
                assertNull(field("readBuffer").get(reader));
                assertTrue(field("broken").getBoolean(reader));
                assertEquals(1, closes[0]);
                ArchivePreviewCache.EntryFiles files = (ArchivePreviewCache.EntryFiles) field("cacheFiles").get(reader);
                assertFalse(files.file("failed.avif").exists());
            } finally { reader.close(); }
        }
    }

    private void verifyBufferReuse(boolean sensitive) throws Exception {
        SequentialArchiveImageReader reader = newSession(sensitive, new HashSet<>());
        ArchiveSupport.ForwardEntry[] entries = {imageEntry("first.avif"), imageEntry("second.avif")};
        byte[] image = {0,0,0,12,'f','t','y','p','a','v','i','f'};
        java.util.List<byte[]> buffers = new java.util.ArrayList<>();
        int[] closes = {0};
        field("reader").set(reader, new ArchiveSupport.ForwardArchiveReader() {
            int position; boolean supplied;
            @Override public ArchiveSupport.ForwardEntry nextEntry() {
                supplied = false; return position < entries.length ? entries[position++] : null;
            }
            @Override public int read(byte[] bytes) {
                if (supplied) return -1;
                supplied = true; buffers.add(bytes); System.arraycopy(image, 0, bytes, 0, image.length); return image.length;
            }
            @Override public void close() { closes[0]++; }
        });
        try {
            assertNull(field("readBuffer").get(reader));
            for (String name : new String[]{"first.avif", "second.avif"}) {
                assertTrue(reader.ensureExtracted(name));
                assertSame(buffers.get(0), field("readBuffer").get(reader));
                if (sensitive) assertArrayEquals(new byte[65536], buffers.get(0));
                assertFalse(reader.ensureExtracted("missing.avif", false, () -> false));
                assertSame(buffers.get(0), field("readBuffer").get(reader));
                assertEquals(0, closes[0]);
            }
            assertEquals(2, buffers.size()); assertSame(buffers.get(0), buffers.get(1));
            assertFalse(reader.ensureExtracted("missing.avif"));
            assertNull(field("readBuffer").get(reader));
            assertArrayEquals(new byte[65536], buffers.get(0));
            assertEquals(1, closes[0]);
            assertTrue(reader.ensureExtracted("first.avif")); // Ready cache does not reallocate scratch.
            assertNull(field("readBuffer").get(reader));
        } finally { reader.close(); }
    }

    private static ArchiveSupport.ForwardEntry imageEntry(String path) throws Exception {
        Constructor<ArchiveSupport.ForwardEntry> constructor = ArchiveSupport.ForwardEntry.class
                .getDeclaredConstructor(String.class, boolean.class, boolean.class);
        constructor.setAccessible(true); return constructor.newInstance(path, false, true);
    }

    private void verifyExhaustedCleanup(Throwable cleanup) throws Exception {
        SequentialArchiveImageReader reader = newSession(false, null);
        int[] advances = {0}, closes = {0};
        ArchiveSupport.ForwardArchiveReader decoder = eofDecoder(advances, closes, cleanup);
        Field active = field("reader");
        active.set(reader, decoder);
        try {
            assertFalse(reader.ensureExtracted("missing.avif"));
            assertEquals(1, advances[0]);
            assertEquals(1, closes[0]);
            assertTrue(field("exhausted").getBoolean(reader));
            if (cleanup == null) assertNull(active.get(reader));
            else assertSame(decoder, active.get(reader));
            assertFalse(reader.ensureExtracted("another.avif"));
            assertEquals("EOF must never reopen or advance", 1, advances[0]);
            reader.close(); reader.close();
            assertEquals(cleanup == null ? 1 : 2, closes[0]);
            assertNull(active.get(reader));
        } finally { reader.close(); }
    }

    private static ArchiveSupport.ForwardArchiveReader eofDecoder(int[] advances, int[] closes, Throwable cleanup) {
        return new ArchiveSupport.ForwardArchiveReader() {
            @Override public ArchiveSupport.ForwardEntry nextEntry() { advances[0]++; return null; }
            @Override public int read(byte[] bytes) { throw new AssertionError("EOF must not read data"); }
            @Override public void close() throws IOException {
                if (++closes[0] != 1 || cleanup == null) return;
                if (cleanup instanceof IOException) throw (IOException) cleanup;
                throw (RuntimeException) cleanup;
            }
        };
    }

    private SequentialArchiveImageReader newSession(boolean sensitive, Set<String> verified) throws Exception {
        File cache = temporary.newFolder();
        Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return cache; }
        };
        Constructor<SequentialArchiveImageReader> constructor = SequentialArchiveImageReader.class
                .getDeclaredConstructor(Context.class, File.class, char[].class, boolean.class, Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context, temporary.newFile(), new char[]{'p'}, sensitive, verified);
    }

    private static Field field(String name) throws Exception {
        Field field = SequentialArchiveImageReader.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void verifyResourceFailure(Error failure, Error cleanup) throws Exception {
        File cache = temporary.newFolder();
        Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return cache; }
        };
        Constructor<SequentialArchiveImageReader> constructor = SequentialArchiveImageReader.class
                .getDeclaredConstructor(Context.class, File.class, char[].class, boolean.class, Set.class);
        constructor.setAccessible(true);
        SequentialArchiveImageReader reader = constructor.newInstance(
                context, temporary.newFile("resource.tar"), null, false, null);
        Field active = SequentialArchiveImageReader.class.getDeclaredField("reader"); active.setAccessible(true);
        int[] advances = {0}, closes = {0};
        ArchiveSupport.ForwardArchiveReader decoder = new ArchiveSupport.ForwardArchiveReader() {
            @Override public ArchiveSupport.ForwardEntry nextEntry() {
                advances[0]++;
                throw failure;
            }
            @Override public int read(byte[] bytes) { throw new AssertionError("Failed header must not be read"); }
            @Override public void close() {
                if (++closes[0] == 1 && cleanup != null) throw cleanup;
            }
        };
        active.set(reader, decoder);
        try {
            assertSame(failure, assertThrows(Error.class, () -> reader.ensureExtracted("first.avif")));
            assertEquals(1, advances[0]);
            assertEquals(1, closes[0]);
            assertFalse(reader.ensureExtracted("second.avif"));
            assertEquals("Retired state must not advance again", 1, advances[0]);
            if (cleanup == null) assertNull(active.get(reader));
            else {
                assertSame(decoder, active.get(reader));
                assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
                reader.close();
                assertEquals(2, closes[0]);
                assertNull(active.get(reader));
            }
        } finally { reader.close(); }
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
