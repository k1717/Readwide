package com.readwide.manager.archive;

import java.util.Arrays;
import org.tukaani.xz.ArrayCache;

/** Small, reader-owned cache of idle XZ byte buffers, never decoder history or a global pool. */
final class SevenZDecoderArrayCache extends ArrayCache implements AutoCloseable {
    // Retention budget only: larger dictionaries still allocate normally, without a new decode limit.
    static final int MAX_RETAINED_BYTES = 8 * 1024 * 1024;
    private byte[] first, second;
    private boolean closed;

    @Override public synchronized byte[] getByteArray(int size, boolean fillWithZeros) {
        // Exact sizes keep retention predictable and avoid mismatched decoder dictionary lengths.
        byte[] result = null;
        if (!closed && first != null && first.length == size) {
            result = first;
            first = second;
            second = null;
        } else if (!closed && second != null && second.length == size) {
            result = second;
            second = null;
        }
        // Retained arrays were zeroed on return, including when fillWithZeros is false.
        return result == null ? new byte[size] : result;
    }

    @Override public synchronized void putArray(byte[] array) {
        if (closed || array == null || array.length == 0 || array.length > MAX_RETAINED_BYTES
                || array == first || array == second) return;
        // Don't keep decrypted plaintext in an idle cache. Uncached arrays follow normal XZ ownership.
        Arrays.fill(array, (byte) 0);
        second = first;
        first = array;
        if (second != null && second.length > MAX_RETAINED_BYTES - first.length) second = null;
    }

    @Override public synchronized void close() {
        // Arrays are already cleared. Late returns cannot repopulate a retired reader's cache.
        closed = true;
        first = null;
        second = null;
    }
}
