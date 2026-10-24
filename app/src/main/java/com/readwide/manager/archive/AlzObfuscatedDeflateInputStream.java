package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Independently written RFC 1951 reader for ALZ method 3 only. The format's
 * dynamic code-length alphabet order is documented in docs/ALZ_FORMAT_NOTES.md.
 * Ordinary Deflate continues to use OwnedDeflateInputStream. No XADMaster or
 * proprietary decoder implementation is incorporated here.
 *
 * The 32 KiB history is a Deflate distance window, not an entry-size limit.
 * The container remains responsible for exact decoded length and CRC checks.
 */
final class AlzObfuscatedDeflateInputStream extends InputStream {
    private static final int WINDOW_MASK = 32767;
    private static final int[] LENGTH_BASE = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258};
    private static final int[] LENGTH_EXTRA = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0};
    private static final int[] DISTANCE_BASE = {
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577};
    private static final int[] DISTANCE_EXTRA = {
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13};

    private final InputStream source;
    private final FileOperationProgress progress;
    private final int[] codeLengthOrder;
    private final byte[] input = new byte[16 * 1024];
    private final byte[] window = new byte[WINDOW_MASK + 1];
    private int inputOffset, inputLength, bits, bitCount;
    private int windowOffset, historySize, storedRemaining, matchRemaining, matchDistance;
    private Huffman literals, distances;
    private Huffman fixedLiterals, fixedDistances;
    private boolean needBlock = true, lastBlock, finished, closed;
    private Throwable failure;

    AlzObfuscatedDeflateInputStream(InputStream source, long unpackedSize) throws IOException {
        this(source, unpackedSize, null);
    }

    AlzObfuscatedDeflateInputStream(InputStream source, long unpackedSize,
                                   FileOperationProgress progress) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.progress = progress;
        if (unpackedSize < 0) throw new IOException("Invalid ALZ decoded size");
        codeLengthOrder = codeLengthOrder(unpackedSize);
    }

    /** Format permutation; long sizes must not pass through signed 32-bit modulo. */
    static int[] codeLengthOrder(long unpackedSize) {
        if (unpackedSize < 0) throw new IllegalArgumentException("unpackedSize");
        int[] order = new int[19];
        Arrays.setAll(order, index -> index);
        int parameter = (int) (unpackedSize & 15L);
        for (int position = 0; position < order.length; position++) {
            int other = parameter + 3 * (position % 6);
            if (other > 18) other -= 18; // The maximum is 30; index 18 is not wrapped.
            int saved = order[position];
            order[position] = order[other];
            order[other] = saved;
        }
        return order;
    }

    @Override public int read() throws IOException {
        requireReadable();
        try {
            checkpoint();
            return nextByte();
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        requireReadable();
        if (length == 0) return 0;
        try {
            int count = 0;
            while (count < length) {
                if ((count & 4095) == 0) checkpoint();
                if (matchRemaining > 0 || storedRemaining > 0) {
                    // Do not skip the existing 4096-output-byte cancellation boundary.
                    int run = Math.min(length - count, 4096 - (count & 4095));
                    if (matchRemaining > 0) {
                        run = Math.min(run, matchRemaining);
                        copyMatch(buffer, offset + count, run);
                        matchRemaining -= run;
                    } else {
                        // Stored payloads are byte-aligned after LEN/NLEN. Refill
                        // through the same checked source path as scalar reads.
                        requireInput();
                        run = Math.min(run, Math.min(storedRemaining, inputLength - inputOffset));
                        System.arraycopy(input, inputOffset, buffer, offset + count, run);
                        remember(buffer, offset + count, run);
                        inputOffset += run;
                        storedRemaining -= run;
                    }
                    count += run;
                    continue;
                }
                int value = nextByte();
                if (value < 0) break;
                buffer[offset + count++] = (byte) value;
            }
            return count == 0 ? -1 : count;
        } catch (IOException | RuntimeException | Error e) {
            // Never resume from partially consumed input after a failed read.
            failure = e;
            throw e;
        }
    }

    private int nextByte() throws IOException {
        while (!finished) {
            if (matchRemaining > 0) {
                int value = window[(windowOffset - matchDistance) & WINDOW_MASK] & 255;
                matchRemaining--;
                return remember(value);
            }
            if (storedRemaining > 0) {
                storedRemaining--;
                return remember(readBits(8));
            }
            if (needBlock) {
                readBlock();
                continue;
            }
            if (literals == null) { // End of a stored block, including an empty one.
                endBlock();
                continue;
            }
            int symbol = literals.readSymbol(this);
            if (symbol < 256) return remember(symbol);
            if (symbol == 256) {
                endBlock();
                continue;
            }
            if (symbol > 285) throw new IOException("Reserved ALZ Deflate length symbol");
            int lengthIndex = symbol - 257;
            int count = LENGTH_BASE[lengthIndex] + readBits(LENGTH_EXTRA[lengthIndex]);
            int distanceSymbol = distances.readSymbol(this);
            if (distanceSymbol >= DISTANCE_BASE.length) throw new IOException("Reserved ALZ Deflate distance symbol");
            int distance = DISTANCE_BASE[distanceSymbol] + readBits(DISTANCE_EXTRA[distanceSymbol]);
            if (distance > historySize) throw new IOException("ALZ Deflate distance precedes output");
            matchDistance = distance;
            matchRemaining = count;
        }
        return -1;
    }

    private int remember(int value) {
        window[windowOffset] = (byte) value;
        windowOffset = (windowOffset + 1) & WINDOW_MASK;
        if (historySize < window.length) historySize++;
        return value;
    }

    private void copyMatch(byte[] buffer, int offset, int count) {
        // Seed from old history before changing it. Repeated copies of the
        // initialized prefix preserve distance < length (including distance 1).
        int seed = Math.min(matchDistance, count);
        int start = (windowOffset - matchDistance) & WINDOW_MASK;
        int first = Math.min(seed, window.length - start);
        System.arraycopy(window, start, buffer, offset, first);
        if (first < seed) System.arraycopy(window, 0, buffer, offset + first, seed - first);
        for (int filled = seed; filled < count;) {
            int copied = Math.min(filled, count - filled);
            System.arraycopy(buffer, offset, buffer, offset + filled, copied);
            filled += copied;
        }
        remember(buffer, offset, count);
    }

    private void remember(byte[] buffer, int offset, int count) {
        // Bulk runs are bounded by 4096 bytes, smaller than the history window.
        int first = Math.min(count, window.length - windowOffset);
        System.arraycopy(buffer, offset, window, windowOffset, first);
        if (first < count) System.arraycopy(buffer, offset + first, window, 0, count - first);
        windowOffset = (windowOffset + count) & WINDOW_MASK;
        historySize = Math.min(window.length, historySize + count);
    }

    private void endBlock() {
        if (lastBlock) finished = true;
        else needBlock = true;
    }

    private void readBlock() throws IOException {
        checkpoint();
        lastBlock = readBits(1) != 0;
        int type = readBits(2);
        needBlock = false;
        if (type == 0) {
            // readBits keeps fewer than eight unused bits after each request.
            bits = bitCount = 0;
            int size = readBits(16);
            if ((size ^ 65535) != readBits(16)) throw new IOException("Invalid ALZ Deflate stored length");
            storedRemaining = size;
            literals = distances = null;
        } else if (type == 1) {
            if (fixedLiterals == null) {
                int[] lengths = new int[288];
                Arrays.fill(lengths, 0, 144, 8);
                Arrays.fill(lengths, 144, 256, 9);
                Arrays.fill(lengths, 256, 280, 7);
                Arrays.fill(lengths, 280, 288, 8);
                fixedLiterals = new Huffman(lengths, 15, false, false);
                lengths = new int[32];
                Arrays.fill(lengths, 5);
                fixedDistances = new Huffman(lengths, 15, false, false);
            }
            literals = fixedLiterals;
            distances = fixedDistances;
        } else if (type == 2) {
            readDynamicTrees();
        } else {
            throw new IOException("Reserved ALZ Deflate block type");
        }
    }

    private void readDynamicTrees() throws IOException {
        int literalCount = readBits(5) + 257;
        int distanceCount = readBits(5) + 1;
        int metaCount = readBits(4) + 4;
        if (literalCount > 286) throw new IOException("Invalid ALZ Deflate literal count");
        int[] metaLengths = new int[19];
        for (int i = 0; i < metaCount; i++) metaLengths[codeLengthOrder[i]] = readBits(3);
        Huffman meta = new Huffman(metaLengths, 7, false, false);
        int[] lengths = new int[literalCount + distanceCount];
        int position = 0;
        while (position < lengths.length) {
            int value = meta.readSymbol(this);
            if (value < 16) {
                lengths[position++] = value;
            } else {
                int repeat, repeated = 0;
                if (value == 16) {
                    if (position == 0) throw new IOException("ALZ Deflate repeat has no preceding length");
                    repeated = lengths[position - 1];
                    repeat = readBits(2) + 3;
                } else {
                    repeat = value == 17 ? readBits(3) + 3 : readBits(7) + 11;
                }
                if (repeat > lengths.length - position) throw new IOException("ALZ Deflate length repeat exceeds table");
                Arrays.fill(lengths, position, position + repeat, repeated);
                position += repeat;
            }
        }
        if (lengths[256] == 0) throw new IOException("ALZ Deflate end-of-block code is absent");
        literals = new Huffman(lengths, 0, literalCount, 15, true, false);
        // An all-literal block may have no distance codes. A match still fails.
        distances = new Huffman(lengths, literalCount, distanceCount, 15, true, true);
    }

    private int readBits(int count) throws IOException {
        while (bitCount < count) {
            requireInput();
            bits |= (input[inputOffset++] & 255) << bitCount;
            bitCount += 8;
        }
        int value = bits & ((1 << count) - 1);
        bits >>>= count;
        bitCount -= count;
        return value;
    }

    private void requireInput() throws IOException {
        if (inputOffset != inputLength) return;
        checkpoint();
        int read = source.read(input, 0, input.length);
        if (read == -1) throw new EOFException("Truncated ALZ Deflate stream");
        if (read <= 0 || read > input.length) throw new IOException("Invalid ALZ Deflate source read");
        inputOffset = 0;
        inputLength = read;
    }

    private void checkpoint() throws InterruptedIOException {
        ArchiveCancellation.checkpoint(progress);
    }

    private void requireReadable() throws IOException {
        if (closed) throw new IOException("ALZ Deflate stream is closed");
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Override public int available() throws IOException {
        requireReadable();
        return 0; // Do not consume compressed input to predict decoded availability.
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { source.close(); }
        finally {
            Arrays.fill(window, (byte) 0);
            Arrays.fill(input, (byte) 0);
            literals = distances = null;
            fixedLiterals = fixedDistances = null;
            bits = bitCount = inputOffset = inputLength = 0;
            matchRemaining = storedRemaining = historySize = 0;
        }
    }

    /** Canonical Huffman codes: lengths determine codes, symbols break ties. */
    private static final class Huffman {
        private final int[] counts, firstCodes, firstSymbols, symbols;
        private final int maximum;

        Huffman(int[] lengths, int maximum, boolean allowSingle, boolean allowEmpty) throws IOException {
            this(lengths, 0, lengths.length, maximum, allowSingle, allowEmpty);
        }

        Huffman(int[] lengths, int offset, int count, int maximum,
                boolean allowSingle, boolean allowEmpty) throws IOException {
            if ((offset | count) < 0 || count > lengths.length - offset) {
                throw new IOException("Invalid ALZ Huffman length range");
            }
            this.maximum = maximum;
            counts = new int[maximum + 1];
            firstCodes = new int[maximum + 1];
            firstSymbols = new int[maximum + 1];
            int used = 0;
            for (int symbol = 0; symbol < count; symbol++) {
                int length = lengths[offset + symbol];
                if (length < 0 || length > maximum) throw new IOException("Invalid ALZ Huffman code length");
                if (length != 0) { counts[length]++; used++; }
            }
            int space = 1, code = 0, index = 0;
            for (int length = 1; length <= maximum; length++) {
                space = (space << 1) - counts[length];
                if (space < 0) throw new IOException("Oversubscribed ALZ Huffman tree");
                code = (code + counts[length - 1]) << 1;
                firstCodes[length] = code;
                firstSymbols[length] = index;
                index += counts[length];
            }
            if (space != 0 && !(allowSingle && used == 1 && counts[1] == 1)
                    && !(allowEmpty && used == 0)) throw new IOException("Incomplete ALZ Huffman tree");
            symbols = new int[used];
            int[] next = firstSymbols.clone();
            for (int symbol = 0; symbol < count; symbol++) {
                int length = lengths[offset + symbol];
                if (length != 0) symbols[next[length]++] = symbol;
            }
        }

        int readSymbol(AlzObfuscatedDeflateInputStream input) throws IOException {
            if (symbols.length == 0) throw new IOException("ALZ Deflate match has no distance tree");
            int code = 0;
            for (int length = 1; length <= maximum; length++) {
                // RFC 1951 Huffman codes are MSB-first within the LSB-packed bitstream.
                code = (code << 1) | input.readBits(1);
                int offset = code - firstCodes[length];
                if (offset >= 0 && offset < counts[length]) return symbols[firstSymbols[length] + offset];
            }
            throw new IOException("Invalid ALZ Huffman symbol");
        }
    }
}
