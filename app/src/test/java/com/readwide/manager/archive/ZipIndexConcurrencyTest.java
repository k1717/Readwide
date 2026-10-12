package com.readwide.manager.archive;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ZipIndexConcurrencyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Before @After public void clear() { ZipxAesArchiveReader.clearCachedIndexes(); }

    private File archive(String name) throws Exception {
        return ZipOutputConsistencyTest.write(new File(temp.newFolder(), name + ".zip"), false, "page");
    }

    @Test public void slowConstructionDoesNotBlockUnrelatedArchive() throws Exception {
        BlockingFile slow = new BlockingFile(archive("slow"));
        File other = archive("other");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Object> first = workers.submit(() -> acquire(slow));
        Object firstIndex = null, otherIndex = null;
        try {
            assertTrue(slow.entered.await(5, TimeUnit.SECONDS));
            otherIndex = workers.submit(() -> acquire(other)).get(5, TimeUnit.SECONDS);
            assertNotNull(otherIndex);
        } finally {
            slow.proceed.countDown();
            try { firstIndex = first.get(5, TimeUnit.SECONDS); }
            finally { release(firstIndex); release(otherIndex); workers.shutdownNow(); }
        }
    }

    @Test public void sameArchiveSharesOnePublishedIndex() throws Exception {
        BlockingFile slow = new BlockingFile(archive("same"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Object> first = workers.submit(() -> acquire(slow));
        Object a = null, b = null;
        try {
            assertTrue(slow.entered.await(5, TimeUnit.SECONDS));
            Future<Object> second = workers.submit(() -> acquire(new File(slow.getPath())));
            slow.proceed.countDown();
            a = first.get(5, TimeUnit.SECONDS); b = second.get(5, TimeUnit.SECONDS);
            assertSame(a, b);
        } finally { slow.proceed.countDown(); release(a); release(b); workers.shutdownNow(); }
    }

    @Test public void clearingDuringConstructionDoesNotRepublishOldWork() throws Exception {
        BlockingFile slow = new BlockingFile(archive("clear"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<Object> pending = worker.submit(() -> acquire(slow));
        Object index = null;
        try {
            assertTrue(slow.entered.await(5, TimeUnit.SECONDS));
            ZipxAesArchiveReader.clearCachedIndexes();
            slow.proceed.countDown();
            index = pending.get(5, TimeUnit.SECONDS);
            assertFalse(ZipxAesArchiveReader.hasCachedIndex(slow));
            Field retired = index.getClass().getDeclaredField("retired"); retired.setAccessible(true);
            assertTrue(retired.getBoolean(index));
        } finally { slow.proceed.countDown(); release(index); worker.shutdownNow(); }
    }

    @Test public void interruptedAcquisitionRemainsTerminal() throws Exception {
        File file = archive("cancel"), out = temp.newFile();
        ArchiveTestTextFiles.writeString(out.toPath(), "keep");
        Thread.currentThread().interrupt();
        try { ZipxAesArchiveReader.tryExtractSingleEntry(file, "page", out, null); fail("Cancellation hidden"); }
        catch (InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        assertEquals("keep", ArchiveTestTextFiles.readString(out.toPath()));
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(file, "page", out, null));
    }

    @Test public void repeatedSelectedReadsReuseHeaderOwnerAndReleaseIt() throws Exception {
        File file = archive("warm"), out = temp.newFile();
        assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(file, "page", out, null));
        Object index = acquire(file);
        try {
            Field headers = index.getClass().getDeclaredField("headers"); headers.setAccessible(true);
            Object owner = headers.get(index); assertNotNull(owner);
            assertEquals(Boolean.TRUE, ZipxAesArchiveReader.tryExtractSingleEntry(file, "page", out, null));
            assertSame(owner, headers.get(index));
            ZipxAesArchiveReader.releaseArchiveIndex(file);
            assertFalse(ZipxAesArchiveReader.hasCachedIndex(file));
            // Eviction cannot close a header input while this explicit lease remains.
            ((SplitVolumeInput) owner).seek(0);
            assertEquals('P', ((SplitVolumeInput) owner).read());
        } finally { release(index); }
        assertTrue(file.delete());
    }

    private static Object acquire(File file) throws Exception {
        Method method = ZipxAesArchiveReader.class.getDeclaredMethod("acquireIndex", File.class);
        method.setAccessible(true);
        try { return method.invoke(null, file); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private static void release(Object index) throws Exception {
        if (index == null) return;
        Method method = ZipxAesArchiveReader.class.getDeclaredMethod("releaseIndex", index.getClass());
        method.setAccessible(true); method.invoke(null, index);
    }

    /** Pause in openZip's post-open source check, after acquiring the construction gate. */
    private static final class BlockingFile extends File {
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        final AtomicInteger lengths = new AtomicInteger();
        BlockingFile(File source) throws IOException { super(source.getCanonicalPath()); }
        @Override public File getCanonicalFile() { return this; }
        @Override public long length() {
            if (lengths.incrementAndGet() == 2) {
                entered.countDown();
                try {
                    if (!proceed.await(10, TimeUnit.SECONDS)) throw new AssertionError("Construction was not released");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            return super.length();
        }
    }
}
