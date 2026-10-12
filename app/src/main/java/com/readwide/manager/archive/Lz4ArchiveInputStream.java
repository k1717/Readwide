package com.readwide.manager.archive;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;
import org.apache.commons.codec.digest.XXHash32;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;

/** Modern/legacy framing and integrity checks around the bundled raw-block decoder. */
final class Lz4ArchiveInputStream extends InputStream {
    private static final long MODERN = 0x184d2204L, LEGACY = 0x184c2102L;
    // Format block bounds, not an archive/file-size ceiling.
    private static final int LEGACY_BLOCK_SIZE = 8 * 1024 * 1024;
    private static final int LEGACY_PACKED_MAX = LEGACY_BLOCK_SIZE + LEGACY_BLOCK_SIZE / 255 + 16;
    private static final int WINDOW_SIZE = 64 * 1024;
    private final InputStream source;
    private final byte[] word = new byte[4], one = new byte[1], scratch = new byte[8192];
    private final byte[] descriptor = new byte[14];
    private final XXHash32 blockHash = new XXHash32(), contentHash = new XXHash32();
    private InputStream decoder;
    private byte[] packed = new byte[0];
    private byte[] history = new byte[0], historySnapshot = new byte[0];
    private int blockExpected, blockProduced;
    private int blockMaximum, historySize, historyPosition;
    // Unsigned 64-bit countdown: no whole-file allocation or signed size ceiling.
    private long contentRemaining;
    private boolean modern, independent, blockChecksum, contentChecksum, hasContentSize, hasDictionaryId;
    private boolean legacy, sawFrame, ended, closed, sourceClosed;
    private Throwable failure;

    Lz4ArchiveInputStream(InputStream source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public int read() throws IOException {
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("LZ4 stream is closed");
        rethrowFailure();
        if (length == 0) return 0;
        try {
            while (!ended) {
                checkpoint();
                if (decoder == null && !openNext()) { ended = true; break; }
                int want = Math.min(length, WINDOW_SIZE);
                int count = decoder.read(buffer, offset, want);
                checkpoint();
                if (count > 0) {
                    if (count > want) throw new IOException("Invalid LZ4 decoder read");
                    if (count > blockExpected - blockProduced) throw new IOException("LZ4 block overrun");
                    blockProduced += count;
                    if (modern) {
                        if (contentChecksum) contentHash.update(buffer, offset, count);
                        if (!independent) appendHistory(buffer, offset, count);
                    }
                    return count;
                }
                if (count != -1) throw new IOException("Zero-progress LZ4 decoder");
                if (blockProduced != blockExpected) throw new IOException("LZ4 block size mismatch");
                releaseDecoder();
            }
            return -1;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            clearBuffers();
            try { releaseDecoder(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != error) error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private boolean openNext() throws IOException {
        while (true) {
            checkpoint();
            if (modern) {
                if (openModernBlock()) return true;
                continue;
            }
            long value = readWord(true);
            if (value == -1) {
                if (!sawFrame) throw new EOFException("Missing LZ4 frame");
                return false;
            }
            if (legacy) {
                if (value == 0) { legacy = false; continue; } // Linux legacy end marker.
                if (isMagic(value)) legacy = false; // Known magic also ends a legacy frame.
                else {
                    if (value > LEGACY_PACKED_MAX) throw new IOException("Invalid LZ4 legacy packed block size");
                    int size = (int) value;
                    if (packed.length < size) packed = new byte[size];
                    readFully(packed, size);
                    blockExpected = validateBlock(packed, size, LEGACY_BLOCK_SIZE, 0, false);
                    blockProduced = 0;
                    decoder = new BlockLZ4CompressorInputStream(new ByteArrayInputStream(packed, 0, size));
                    return true;
                }
            }
            if (value == LEGACY) {
                sawFrame = true;
                legacy = true;
            } else if (value == MODERN) {
                sawFrame = true;
                readModernDescriptor();
            } else if (isSkippable(value)) {
                long remaining = readWord(false);
                while (remaining > 0) {
                    int count = readSource(scratch, 0, (int) Math.min(remaining, scratch.length));
                    if (count == -1) throw new EOFException("Truncated LZ4 skippable frame");
                    remaining -= count;
                }
            } else throw new IOException("Unknown LZ4 frame or trailing data");
        }
    }

    private static boolean isMagic(long value) { return value == MODERN || value == LEGACY || isSkippable(value); }
    private static boolean isSkippable(long value) { return (value & 0xfffffff0L) == 0x184d2a50L; }

    private void readModernDescriptor() throws IOException {
        readFully(descriptor, 2);
        int flags = descriptor[0] & 255, bd = descriptor[1] & 255;
        int blockCode = (bd >>> 4) & 7;
        if ((flags & 0xc0) != 0x40 || (flags & 2) != 0 || (bd & 0x8f) != 0
                || blockCode < 4) throw new IOException("Invalid or unsupported LZ4 frame descriptor");
        hasContentSize = (flags & 8) != 0;
        hasDictionaryId = (flags & 1) != 0;
        int size = 2 + (hasContentSize ? 8 : 0) + (hasDictionaryId ? 4 : 0);
        readFully(descriptor, 2, size - 2);
        blockHash.reset(); blockHash.update(descriptor, 0, size);
        readFully(one, 1);
        if (((blockHash.getValue() >>> 8) & 255) != (one[0] & 255)) {
            throw new IOException("LZ4 descriptor checksum mismatch");
        }
        // Dict-ID identifies external bytes, but does not prove that any match
        // uses them. Stored/literal data and matches within actual frame history
        // need no provider. Preflight every compressed block before output and
        // reject an external reference instead of inventing dictionary bytes.
        contentRemaining = 0;
        if (hasContentSize) {
            for (int i = 0; i < 8; i++) contentRemaining |= (descriptor[2 + i] & 255L) << (8 * i);
        }
        independent = (flags & 0x20) != 0;
        blockChecksum = (flags & 0x10) != 0;
        contentChecksum = (flags & 4) != 0;
        blockMaximum = 1 << (2 * blockCode + 8);
        historySize = historyPosition = 0;
        contentHash.reset();
        modern = true;
    }

    private boolean openModernBlock() throws IOException {
        long value = readWord(false);
        if (value == 0) {
            if (hasContentSize && contentRemaining != 0) throw new IOException("LZ4 content size mismatch");
            if (contentChecksum && readWord(false) != contentHash.getValue()) {
                throw new IOException("LZ4 content checksum mismatch");
            }
            modern = false;
            historySize = historyPosition = 0;
            return false;
        }
        boolean stored = (value & 0x80000000L) != 0;
        int size = (int) (value & 0x7fffffffL);
        if (size > blockMaximum) throw new IOException("LZ4 packed block exceeds descriptor size");
        if (packed.length < size) packed = new byte[size];
        readFully(packed, size);
        // Hash original bytes before validateBlock normalizes the unused nibble.
        if (blockChecksum) {
            blockHash.reset();
            for (int offset = 0; offset < size; offset += WINDOW_SIZE) {
                checkpoint();
                blockHash.update(packed, offset, Math.min(WINDOW_SIZE, size - offset));
            }
            if (readWord(false) != blockHash.getValue()) throw new IOException("LZ4 block checksum mismatch");
        }
        blockExpected = stored ? size : validateBlock(packed, size, blockMaximum,
                independent ? 0 : historySize, hasDictionaryId);
        if (hasContentSize) {
            // A set sign bit means an unsigned value above Long.MAX_VALUE, not
            // a negative/unknown size. Avoid API-26-only unsigned helpers (minSdk 24).
            if (contentRemaining >= 0 && contentRemaining < blockExpected) throw new IOException("LZ4 content size overrun");
            contentRemaining -= blockExpected;
        }
        blockProduced = 0;
        ByteArrayInputStream block = new ByteArrayInputStream(packed, 0, size);
        if (stored) decoder = block;
        else {
            BlockLZ4CompressorInputStream raw = new BlockLZ4CompressorInputStream(block);
            decoder = raw;
            if (!independent && historySize != 0) {
                if (historySnapshot.length != historySize) historySnapshot = new byte[historySize];
                int start = (historyPosition - historySize + WINDOW_SIZE) % WINDOW_SIZE;
                int first = Math.min(historySize, WINDOW_SIZE - start);
                System.arraycopy(history, start, historySnapshot, 0, first);
                System.arraycopy(history, 0, historySnapshot, first, historySize - first);
                raw.prefill(historySnapshot);
            }
        }
        // 0x80000000 is a real empty stored block, not an EndMark. The outer read
        // loop consumes its EOF and continues, preserving linked history.
        return true;
    }

    private void appendHistory(byte[] bytes, int offset, int length) {
        if (history.length == 0) history = new byte[WINDOW_SIZE];
        int first = Math.min(length, WINDOW_SIZE - historyPosition);
        System.arraycopy(bytes, offset, history, historyPosition, first);
        System.arraycopy(bytes, offset + first, history, 0, length - first);
        historyPosition = (historyPosition + length) % WINDOW_SIZE;
        historySize = Math.min(WINDOW_SIZE, historySize + length);
    }

    private long readWord(boolean allowEof) throws IOException {
        int count = readSource(word, 0, 4);
        if (count == -1) {
            if (allowEof) return -1;
            throw new EOFException("Truncated LZ4 size");
        }
        while (count < 4) {
            int read = readSource(word, count, 4 - count);
            if (read == -1) throw new EOFException("Truncated LZ4 header");
            count += read;
        }
        return (word[0] & 255L) | ((word[1] & 255L) << 8)
                | ((word[2] & 255L) << 16) | ((word[3] & 255L) << 24);
    }

    private void readFully(byte[] bytes, int length) throws IOException {
        readFully(bytes, 0, length);
    }

    private void readFully(byte[] bytes, int offset, int length) throws IOException {
        int end = offset + length;
        while (offset < end) {
            int count = readSource(bytes, offset, Math.min(end - offset, WINDOW_SIZE));
            if (count == -1) throw new EOFException("Truncated LZ4 frame or block");
            offset += count;
        }
    }

    private int readSource(byte[] bytes, int offset, int length) throws IOException {
        checkpoint();
        int count = source.read(bytes, offset, length);
        checkpoint();
        if (count < -1 || count == 0 || count > length) throw new IOException("Invalid or zero-progress LZ4 source read");
        return count;
    }

    /** Check all token ranges and real available history before any block output. */
    private static int validateBlock(byte[] data, int length, int maximum, int historySize,
                                     boolean hasDictionaryId) throws IOException {
        int position = 0;
        long produced = 0;
        while (position < length) {
            checkpoint();
            int tokenPosition = position;
            int token = data[position++] & 255;
            long literals = token >>> 4;
            if (literals == 15) {
                int extension;
                do {
                    if (position == length) throw new EOFException("Truncated LZ4 literal length");
                    extension = data[position++] & 255; literals += extension;
                    if ((position & 4095) == 0) checkpoint();
                } while (extension == 255);
            }
            if (literals > length - position || literals > maximum - produced) {
                throw new IOException("Invalid LZ4 literal range");
            }
            position += (int) literals; produced += literals;
            if (position == length) {
                // There is no match in the final literal-only sequence. Commons
                // expects zero in this unused nibble; normalize only our private
                // buffer after checking the complete range (not the source bytes).
                data[tokenPosition] = (byte) (token & 0xf0);
                return (int) produced;
            }
            if (length - position < 2) throw new EOFException("Truncated LZ4 match offset");
            int distance = (data[position] & 255) | ((data[position + 1] & 255) << 8);
            position += 2;
            if (distance == 0) throw new IOException("Invalid LZ4 match offset");
            if (distance > produced + historySize) {
                if (hasDictionaryId) throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "LZ4 match requires an unavailable preset dictionary");
                throw new IOException("Invalid LZ4 match offset");
            }
            long match = token & 15;
            if (match == 15) {
                int extension;
                do {
                    if (position == length) throw new EOFException("Truncated LZ4 match length");
                    extension = data[position++] & 255; match += extension;
                    if ((position & 4095) == 0) checkpoint();
                } while (extension == 255);
            }
            match += 4;
            if (match > maximum - produced) throw new IOException("LZ4 block exceeds format size");
            produced += match;
        }
        throw new EOFException("Missing LZ4 final literal sequence");
    }

    private void rethrowFailure() throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("LZ4 decoding cancelled");
    }
    private void releaseDecoder() throws IOException {
        InputStream old = decoder;
        decoder = null;
        if (old != null) old.close();
    }
    private void clearBuffers() {
        packed = history = historySnapshot = new byte[0];
        historySize = historyPosition = 0;
    }
    @Override public void close() throws IOException {
        closed = true;
        clearBuffers();
        Throwable first = null;
        try { releaseDecoder(); }
        catch (IOException | RuntimeException | Error error) { first = error; throw error; }
        finally {
            try { if (!sourceClosed) { source.close(); sourceClosed = true; } }
            catch (IOException | RuntimeException | Error cleanup) {
                if (first == null) throw cleanup;
                if (cleanup != first) first.addSuppressed(cleanup);
            }
        }
    }
}
