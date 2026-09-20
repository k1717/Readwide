package com.readwide.manager.archive;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Single-owner, paged RAR5/7 dictionary. Only produced history consumes storage. */
final class Rar5HistoryStore implements Closeable {
    private static final int PAGE_SIZE = 64 * 1024;
    private static final int RESIDENT_PAGES = 1024; // 64 MiB RAM cache; older history spills, never disappears.
    private static final int RECORD_SIZE = PAGE_SIZE + 12 + 16; // nonce + GCM tag

    interface SpaceAvailable { long bytes(File directory); }

    private final long capacity;
    private final int residentLimit;
    private final File directory;
    private final SpaceAvailable space;
    private final LinkedHashMap<Long, Page> pages = new LinkedHashMap<>(16, 0.75f, true);
    private Page readPage;
    private Page writePage;
    private long produced;
    private long nonceCounter;
    private File spool;
    private RandomAccessFile disk;
    private byte[] key;
    private Cipher cipher;
    private boolean closed;
    private boolean cleanupComplete;

    Rar5HistoryStore(long capacity) {
        this(capacity, RESIDENT_PAGES, null, ArchiveSupport::runtimeExtractionBudgetBytes);
    }

    // Small cache / isolated directory / deterministic space policy for regression sources.
    Rar5HistoryStore(long capacity, int residentLimit, File directory, SpaceAvailable space) {
        if (capacity <= 0 || capacity > (1L << 40) || residentLimit < 2 || space == null) {
            throw new IllegalArgumentException("Invalid RAR history configuration");
        }
        this.capacity = capacity;
        this.residentLimit = residentLimit;
        this.directory = directory;
        this.space = space;
    }

    void append(byte value) throws IOException {
        try {
            requireOpen();
            if ((produced & 4095) == 0) checkpoint();
            if (produced == Long.MAX_VALUE) throw new IOException("RAR history position overflow");
            long slot = produced % capacity;
            long index = slot / PAGE_SIZE;
            if (writePage == null || writePage.index != index) writePage = page(index);
            writePage.data[(int) (slot % PAGE_SIZE)] = value;
            writePage.dirty = true;
            produced++;
        } catch (IOException | RuntimeException failure) {
            retire(failure);
            throw failure;
        }
    }

    byte read(long absolutePosition) throws IOException {
        try {
            requireOpen();
            if (absolutePosition < 0) return 0; // Initial, unproduced logical dictionary.
            if (absolutePosition >= produced || produced - absolutePosition > capacity) {
                throw new IOException("RAR history reference outside the declared dictionary");
            }
            long slot = absolutePosition % capacity;
            long index = slot / PAGE_SIZE;
            if (readPage == null || readPage.index != index) readPage = page(index);
            return readPage.data[(int) (slot % PAGE_SIZE)];
        } catch (IOException | RuntimeException failure) {
            retire(failure);
            throw failure;
        }
    }

    private Page page(long index) throws IOException {
        checkpoint();
        Page found = pages.get(index);
        if (found != null) return found;
        if (pages.size() == residentLimit) {
            Map.Entry<Long, Page> oldest = pages.entrySet().iterator().next();
            Page evicted = oldest.getValue();
            if (evicted.dirty) spill(evicted);
            pages.remove(oldest.getKey());
            if (readPage == evicted) readPage = null;
            if (writePage == evicted) writePage = null;
            Arrays.fill(evicted.data, (byte) 0);
        }
        Page next = new Page(index);
        if (index * PAGE_SIZE < Math.min(produced, capacity)) {
            // Every previously produced page missing from RAM was committed on eviction.
            if (disk == null) throw new IOException("Missing RAR history page");
            byte[] nonce = new byte[12];
            byte[] encrypted = new byte[PAGE_SIZE + 16];
            disk.seek(index * RECORD_SIZE);
            disk.readFully(nonce);
            disk.readFully(encrypted);
            byte[] plain = crypt(Cipher.DECRYPT_MODE, index, nonce, encrypted);
            try { System.arraycopy(plain, 0, next.data, 0, PAGE_SIZE); }
            finally { Arrays.fill(plain, (byte) 0); }
        }
        pages.put(index, next);
        return next;
    }

    private void spill(Page page) throws IOException {
        checkpoint();
        if (disk == null) {
            spool = File.createTempFile("readwide_rar_history_", ".spool", directory);
            disk = new RandomAccessFile(spool, "rw");
            key = new byte[16];
            new SecureRandom().nextBytes(key);
        }
        // Random-access dictionary storage uses the same free-space reserve as extraction.
        // Check each record, including rewrites; never preallocate the declared dictionary.
        if (space.bytes(spool.getAbsoluteFile().getParentFile()) < RECORD_SIZE) {
            throw new IOException("Not enough free space for RAR dictionary history");
        }
        if (nonceCounter == Long.MAX_VALUE) throw new IOException("RAR history nonce overflow");
        byte[] nonce = new byte[12];
        putLong(nonce, 4, nonceCounter++); // Unique for every write under this random session key.
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, page.index, nonce, page.data);
        disk.seek(page.index * RECORD_SIZE);
        disk.write(nonce);
        disk.write(encrypted);
        page.dirty = false;
    }

    private byte[] crypt(int mode, long index, byte[] nonce, byte[] bytes) throws IOException {
        try {
            if (cipher == null) cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] address = new byte[8];
            putLong(address, 0, index);
            cipher.updateAAD(address); // A valid record cannot be moved to a different page.
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException failure) {
            throw new IOException("RAR temporary history encryption or authentication failed", failure);
        }
    }

    private static void putLong(byte[] out, int offset, long value) {
        for (int i = 7; i >= 0; i--) { out[offset + i] = (byte) value; value >>>= 8; }
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("RAR history is closed or failed");
    }

    private static void checkpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR history interrupted");
    }

    private void retire(Throwable failure) {
        try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    File spoolForTest() { return spool; }
    int residentPagesForTest() { return pages.size(); }

    @Override public void close() throws IOException {
        if (closed && cleanupComplete) return;
        closed = true;
        for (Page page : pages.values()) Arrays.fill(page.data, (byte) 0);
        pages.clear();
        readPage = writePage = null;
        if (key != null) Arrays.fill(key, (byte) 0);
        key = null;
        cipher = null;
        IOException failure = null;
        try {
            if (disk != null) {
                disk.close();
                disk = null; // Retain a failed-close handle for a subsequent cleanup attempt.
            }
        } catch (IOException e) { failure = e; }
        finally {
            try {
                if (spool != null && spool.exists() && !spool.delete()) {
                    throw new IOException("Cannot delete RAR temporary history");
                }
                cleanupComplete = disk == null; // Keep spoolForTest() diagnostic identity; retry failures.
            } catch (IOException | SecurityException e) {
                IOException cleanup = new IOException("Cannot delete RAR temporary history", e);
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    private static final class Page {
        final long index;
        final byte[] data = new byte[PAGE_SIZE];
        boolean dirty;
        Page(long index) { this.index = index; }
    }
}
