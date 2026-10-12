package com.readwide.codecs;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Decoder-only libmspack session. The caller owns approved inputs and outputs;
 * native code receives volume tokens, never filesystem paths or entry filenames.
 * A session retains folder decoder state for forward extraction. Any failed
 * extraction retires it rather than reusing possibly damaged decoder history.
 */
public final class CabNativeDecoder implements Closeable {
    public interface Source {
        /** Positional read from a pre-approved volume; -1 is EOF, never return zero. */
        int read(int volume, long offset, byte[] target, int count) throws IOException;
        /** Must check cancellation and, where applicable, source availability. */
        void checkpoint() throws IOException;
    }

    public static final class Entry {
        private final byte[] name;
        public final long length;
        public final int attributes;
        public final int compression;
        public final long folderOffset;
        public final int folderIndex;

        // Called by JNI. Name bytes must be decoded by the app using the UTF-name flag.
        private Entry(byte[] name, long length, int attributes, int compression,
                long folderOffset, int folderIndex) {
            this.name = name;
            this.length = length;
            this.attributes = attributes;
            this.compression = compression;
            this.folderOffset = folderOffset;
            this.folderIndex = folderIndex;
        }

        public byte[] nameBytes() { return name.clone(); }
    }

    private final Source source;
    private final List<Entry> entries;
    private long handle;
    private boolean busy;

    /**
     * Lengths are declared CFHEADER cabinet extents in consecutive set order,
     * starting at volume zero. The app must resolve reciprocal links first.
     * This constructor does not take ownership of {@code source}.
     */
    public CabNativeDecoder(Source source, int setId, long[] cabinetLengths) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cabinetLengths, "cabinetLengths");
        if (setId < 0 || setId > 65535 || cabinetLengths.length == 0
                || cabinetLengths.length > 65536) {
            throw new IOException("Invalid CAB set metadata");
        }
        long[] lengths = cabinetLengths.clone();
        for (long length : lengths) {
            // CAB fields are unsigned 32-bit; this is a format bound, not a quota.
            if (length < 36 || length > 0xffffffffL) throw new IOException("Invalid CAB extent");
        }
        ensureLoaded();
        source.checkpoint();
        try {
            handle = nativeOpen(this, setId, lengths);
            if (handle == 0) throw new IOException("Cannot create CAB decoder");
            Entry[] listed = nativeEntries(handle);
            if (listed == null) throw new IOException("Missing CAB entry list");
            entries = Collections.unmodifiableList(Arrays.asList(listed));
            source.checkpoint();
        } catch (IOException | RuntimeException | Error failure) {
            retire();
            throw failure;
        }
    }

    public List<Entry> entries() { return entries; }

    /** Writes exactly one entry, without closing/flushing the caller's output. */
    public synchronized void extract(int index, OutputStream output) throws IOException {
        if (handle == 0) throw new IOException("CAB decoder is closed");
        if (busy) throw new IOException("Reentrant CAB decoder operation");
        if (index < 0 || index >= entries.size()) throw new IndexOutOfBoundsException("CAB entry index");
        Objects.requireNonNull(output, "output");
        busy = true;
        try {
            source.checkpoint();
            nativeExtract(handle, index, output);
            source.checkpoint();
        } catch (IOException | RuntimeException | Error failure) {
            retire();
            throw failure;
        } finally {
            busy = false;
        }
    }

    @Override public synchronized void close() throws IOException {
        if (busy) throw new IOException("Cannot close CAB decoder from an I/O callback");
        retire();
    }

    private void retire() {
        long current = handle;
        handle = 0;
        if (current != 0) nativeClose(current);
    }

    // JNI calls these stable names instead of implementation-specific Source methods.
    private int readSource(int volume, long offset, byte[] target, int count) throws IOException {
        return source.read(volume, offset, target, count);
    }

    private void checkpoint() throws IOException { source.checkpoint(); }

    private static boolean loaded;
    private static synchronized void ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("readwide-cab");
            loaded = true;
        }
    }

    public static boolean isAvailable() {
        try { ensureLoaded(); return true; }
        catch (LinkageError | SecurityException unavailable) { return false; }
    }

    private static native long nativeOpen(CabNativeDecoder owner, int setId, long[] lengths) throws IOException;
    private static native Entry[] nativeEntries(long handle) throws IOException;
    private static native void nativeExtract(long handle, int index, OutputStream output) throws IOException;
    private static native void nativeClose(long handle);
}
