package com.readwide.manager.archive;

import java.util.zip.CRC32;

/**
 * Decodes a sequence of RAR3/RAR4 PPMd solid entries with one shared
 * {@link RarPpmdVarHDecoder} model and one shared LZ window.
 *
 * <p>Decode-only: this class performs decompression of existing archives
 * and contains no compression or encryption functionality.</p>
 *
 * <p>A PPMd start is not a promise about later blocks. At the first internal LZ
 * table this session continues through the shared mixed engine, retaining the
 * same model, raw dictionary and VM queue without replaying published output.</p>
 *
 * <p>Solid semantics implemented here (verified against the local C
 * reference harness on the target fixture, both entries CRC-matching):</p>
 * <ul>
 *   <li>The PPMd model persists across entries; only an explicit reset
 *       flag (0x20) in a block header re-initialises it.</li>
 *   <li>The LZ window persists across entries: match copies may reach
 *       into bytes produced by earlier entries of the solid set.</li>
 *   <li>The range decoder is re-primed (4 bytes) at every block header.</li>
 *   <li>Escape sequences: {@code esc,0} next-table boundary, {@code esc,2}
 *       end-of-data, {@code esc,3} scoped standard VM filter, {@code esc,4}
 *       match with 3-byte offset (+2) and length byte (+32),
 *       {@code esc,5} run match (dist 1, length byte +4), any other code
 *       emits the escape character itself as a literal.</li>
 *   <li><b>End-of-data marker:</b> after an entry's unpacked size has been
 *       produced, the stream still contains the {@code esc,2} marker pair.
 *       Both symbols must be decoded — they mutate the model — or the next
 *       solid entry desynchronises. A missing/mismatched marker is treated
 *       as a hard decode failure, never silent success.</li>
 * </ul>
 */
final class Rar3PpmdSolidStreamDecoder {
    // esc,4 carries a 24-bit distance plus two. 32 MiB retains every such match,
    // independently of the total number of bytes decoded in a solid set.
    private static final int HISTORY_BYTES = 32 * 1024 * 1024;
    private final RarPpmdVarHDecoder model = new RarPpmdVarHDecoder();
    private final HistoryWindow window = new HistoryWindow(HISTORY_BYTES);
    private final Rar3VmFilter.ProgramState vmPrograms = new Rar3VmFilter.ProgramState();
    private final Rar3PpmdFilterOutput.DeferredFilters deferredFilters =
            new Rar3PpmdFilterOutput.DeferredFilters();
    private int escapeChar = 2;
    private boolean modelReady;
    private boolean invalid;
    private MixedState mixed;

    /** Allocated only after an actual PPMd-to-LZ table transition. */
    private static final class MixedState {
        final int[] oldTable = new int[Rar3HuffmanTables.TABLE_SIZE];
        final Rar3UnpackState matches = new Rar3UnpackState();
        boolean reuseTables;
    }

    void setTraceSink(RarPpmdVarHDecoder.TraceSink sink) { model.setTraceSink(sink); }
    int windowSize() { return window.retained; }

    /** No following file may reuse model/history after failed decode, integrity or output. */
    void invalidate() {
        invalid = true;
        modelReady = false;
        vmPrograms.reset();
        deferredFilters.reset();
        mixed = null;
    }

    void requireCompleteFilterRun() throws java.io.IOException {
        try { deferredFilters.requireEmpty(); }
        catch (java.io.IOException failure) {
            invalidate();
            throw failure;
        }
    }

    static final class EntryResult {
        final byte[] data;
        final long crc32;
        EntryResult(byte[] data, long crc32) { this.data = data; this.crc32 = crc32; }
    }

    /** Array convenience API for small fixture callers; production uses the stream overload. */
    EntryResult decodeEntry(byte[] packed, long unpackedSize)
            throws RarArchiveReader.UnsupportedRarFeatureException {
        if (unpackedSize < 0 || unpackedSize > Integer.MAX_VALUE - 8) {
            throw unsupported("RAR3 PPMd byte-array output size is outside supported bounds");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            long crc = decodeEntry(new java.io.ByteArrayInputStream(packed), unpackedSize, out);
            return new EntryResult(out.toByteArray(), crc);
        } catch (RarArchiveReader.UnsupportedRarFeatureException failure) {
            throw failure;
        } catch (java.io.IOException failure) {
            RarArchiveReader.UnsupportedRarFeatureException wrapped = unsupported(failure.getMessage());
            wrapped.initCause(failure);
            throw wrapped;
        }
    }

    /** Neither stream is closed here. The caller owns payload bounds and output commit. */
    long decodeEntry(java.io.InputStream packed, long unpackedSize, java.io.OutputStream destination)
            throws java.io.IOException {
        return decodeEntry(packed, unpackedSize, destination, false);
    }

    long decodeEntry(java.io.InputStream packed, long unpackedSize, java.io.OutputStream destination,
            boolean continuesSolid) throws java.io.IOException {
        return decodeEntry(packed, unpackedSize, destination, continuesSolid, null);
    }

    long decodeEntry(java.io.InputStream packed, long unpackedSize, java.io.OutputStream destination,
            boolean continuesSolid, com.readwide.manager.util.FileOperationProgress progress) throws java.io.IOException {
        if (invalid) throw unsupported("RAR3 PPMd decoder is retired after failure or close");
        if (unpackedSize < 0) throw unsupported("Invalid RAR3 PPMd unpacked size");
        if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("RAR extraction cancelled");
        CRC32 crc = new CRC32();
        java.io.OutputStream checked = new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException {
                destination.write(value); crc.update(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
                destination.write(bytes, offset, length); crc.update(bytes, offset, length);
            }
            @Override public void flush() throws java.io.IOException { destination.flush(); }
        };
        java.io.BufferedOutputStream buffered = new java.io.BufferedOutputStream(checked, 64 * 1024);
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(buffered, unpackedSize, vmPrograms,
                Rar3PpmdFilterOutput.MAX_FILTER_BYTES, deferredFilters, continuesSolid, progress)) {
            if (mixed != null) {
                continueMixed(packed, out, -1);
                out.finish(true);
                return crc.getValue();
            }
            readPpmdTable(packed);

            int untilCheckpoint = 0;
            // Consume through the marker, not just final byte count: a trailing
            // VM record may legitimately consume raw bytes and emit no output.
            while (true) {
                if (--untilCheckpoint <= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("RAR extraction cancelled");
                    untilCheckpoint = 1024;
                }
                int sym = nextSymbol("entry data");
                if (sym != escapeChar) {
                    if (!out.needsInput()) throw unsupported("RAR3 PPMd data follows declared entry size");
                    window.literal(sym, out); continue;
                }
                int code = nextSymbol("escape code");
                if (code == 0) {
                    int flags = requiredByte(packed);
                    if ((flags & 0x80) != 0) { readPpmdTable(packed, flags); continue; }
                    mixed = new MixedState();
                    continueMixed(packed, out, flags);
                    break;
                }
                if (code == 2) {
                    if (!out.canFinishEntry()) throw unsupported("RAR3 PPMd end-of-data arrived before the entry was complete");
                    break;
                }
                if (code == 3) { out.readRecord(() -> nextSymbol("VM record")); continue; }
                if (!out.needsInput()) throw unsupported("RAR3 PPMd data follows declared entry size");
                if (code == 4 || code == 5) {
                    int distance = 1;
                    if (code == 4) {
                        int offset = 0;
                        for (int i = 0; i < 3; i++) offset = (offset << 8) | nextSymbol("match offset");
                        distance = offset + 2;
                    }
                    int length = nextSymbol("match length") + (code == 4 ? 32 : 4);
                    // Check before emitting any part of an overlong match.
                    if (!out.hasPendingFilters() && length > out.rawBytesUntilBoundary()) {
                        throw unsupported("RAR3 PPMd match exceeds entry size");
                    }
                    window.match(distance, length, out);
                } else {
                    window.literal(escapeChar, out);
                }
            }
            // Marker symbols above also advance the model for the next solid entry.
            out.finish(true);
            return crc.getValue();
        } catch (RarPpmdVarHDecoder.PpmdDataException failure) {
            invalidate();
            if (failure.getCause() instanceof java.io.IOException) {
                throw (java.io.IOException) failure.getCause();
            }
            throw unsupported("RAR3 PPMd decode failed: " + failure.getMessage());
        } catch (java.io.IOException | RuntimeException | Error failure) {
            invalidate();
            throw failure;
        }
    }

    /** The range coder consumes bytes directly; escape 0 starts a byte-aligned table. */
    private void readPpmdTable(java.io.InputStream packed) throws java.io.IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("RAR extraction cancelled");
        readPpmdTable(packed, requiredByte(packed));
    }

    private void readPpmdTable(java.io.InputStream packed, int flags) throws java.io.IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("RAR extraction cancelled");
        if ((flags & 0x80) == 0) {
            throw unsupported("RAR3 PPMd/classic-LZ table transitions require the mixed-mode decoder");
        }
        boolean reset = (flags & 0x20) != 0;
        int memMb = reset ? requiredByte(packed) + 1 : 0;
        // An omitted escape field preserves the current symbol across table changes.
        if ((flags & 0x40) != 0) escapeChar = requiredByte(packed);
        if (reset) {
            int maxOrder = (flags & 0x1f) + 1;
            if (maxOrder == 1) throw unsupported("Invalid RAR3 PPMd model order");
            if (maxOrder > 16) maxOrder = 16 + (maxOrder - 16) * 3;
            // The format's unsigned byte plus one encodes 1..256 MiB, not an app quota.
            int heapBytes = memMb << 20;
            if (!model.alloc(heapBytes)) {
                throw unsupported("RAR3 PPMd model allocation failed: " + memMb + " MB");
            }
            model.rangeInit(packed);
            model.init(maxOrder);
            modelReady = true;
        } else {
            if (!modelReady) throw unsupported("RAR3 PPMd continuation requires solid predecessors");
            model.rangeInit(packed);
        }
    }

    /** Continue the same verified entry, then retain mixed tables for solid successors. */
    private void continueMixed(java.io.InputStream packed, Rar3PpmdFilterOutput out,
            int firstByte) throws java.io.IOException {
        // Only the mode byte was consumed. Replay it, not the file or any output.
        // Unlike SequenceInputStream this non-owning view never closes the payload.
        java.io.InputStream remaining = new java.io.InputStream() {
            int prefix = firstByte;
            @Override public int read() throws java.io.IOException {
                if (prefix < 0) return packed.read();
                int value = prefix; prefix = -1; return value;
            }
        };
        RarLzWindow history = new RarLzWindow(window.bytes, window.next, window.retained,
                out.rawBytes(), RarOutputStreamDecodedOutput.wrapOrMemory(out));
        // LZ VM descriptors can precede their raw bytes. Do not restrict their
        // addressable block to the smaller, lazily grown PPMd history buffer.
        history.ensureHistoryCapacity(Rar3PpmdFilterOutput.MAX_FILTER_BYTES);
        Rar3ClassicLzEngine.PpmdSource ppmd = new Rar3ClassicLzEngine.PpmdSource() {
            @Override public void readTable(java.io.InputStream input) throws java.io.IOException {
                readPpmdTable(input);
            }
            @Override public int symbol() throws java.io.IOException { return nextSymbol("mixed block"); }
            @Override public int escape() { return escapeChar; }
        };
        // Rar3PackedPayload (or the fixture's array stream) owns physical bounds.
        // A resumed substream has no independent declared packed length.
        Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decodeMixed(new RarBitInput(remaining),
                history, 0, mixed.oldTable, vmPrograms, mixed.matches, ppmd, out,
                mixed.reuseTables, true);
        if (!engine.fileEndSeen()) throw unsupported("RAR3 mixed entry requires an explicit file boundary");
        System.arraycopy(engine.tableState(), 0, mixed.oldTable, 0, mixed.oldTable.length);
        mixed.reuseTables = engine.reuseTablesForNextEntry();
        window.bytes = history.bytes(); window.next = history.position(); window.retained = history.retained();
    }

    private int nextSymbol(String stage) throws RarArchiveReader.UnsupportedRarFeatureException {
        int value = model.decodeSymbol();
        if (value < 0) throw unsupported("RAR3 PPMd model error while decoding " + stage);
        return value;
    }

    private static int requiredByte(java.io.InputStream in) throws java.io.IOException {
        int value = in.read();
        if (value < 0) throw new java.io.EOFException("Truncated RAR3 PPMd block header");
        return value;
    }

    private static RarArchiveReader.UnsupportedRarFeatureException unsupported(String message) {
        return new RarArchiveReader.UnsupportedRarFeatureException(message);
    }

    /** Rolling history; package visibility permits small wraparound regressions. */
    static final class HistoryWindow {
        private byte[] bytes;
        private final int maximum;
        private int next;
        private int retained;

        HistoryWindow(int maximum) {
            if (maximum < 1 || (maximum & (maximum - 1)) != 0) throw new IllegalArgumentException("window size");
            this.maximum = maximum;
            bytes = new byte[Math.min(maximum, 64 * 1024)];
        }

        void literal(int value, java.io.OutputStream out) throws java.io.IOException {
            if (retained == bytes.length && bytes.length < maximum) {
                // Growth occurs before the first overwrite, so the history is still linear.
                bytes = java.util.Arrays.copyOf(bytes, Math.min(maximum, bytes.length * 2));
                next = retained;
            }
            bytes[next] = (byte) value;
            next = (next + 1) & (bytes.length - 1);
            if (retained < bytes.length) retained++;
            out.write(value);
        }

        void match(int distance, int length, java.io.OutputStream out) throws java.io.IOException {
            if (distance < 1 || distance > retained || length < 0) {
                throw unsupported("RAR3 PPMd match exceeds available history");
            }
            for (int i = 0; i < length; i++) {
                literal(bytes[(next - distance) & (bytes.length - 1)] & 255, out);
            }
        }
    }
}
