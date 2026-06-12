package com.textview.reader.archive;

import java.util.zip.CRC32;

/**
 * Decodes a sequence of RAR3/RAR4 PPMd solid entries with one shared
 * {@link RarPpmdVarHDecoder} model and one shared LZ window.
 *
 * <p>Decode-only: this class performs decompression of existing archives
 * and contains no compression or encryption functionality.</p>
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
 *       end-of-data, {@code esc,3} VM filter (unsupported), {@code esc,4}
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

    /** Hard cap for the carried LZ window across a solid set. */
    private static final long MAX_WINDOW_BYTES = 64L * 1024 * 1024;
    /** Hard cap for a single PPMd model heap. */
    private static final int MAX_MODEL_BYTES = 256 * 1024 * 1024;

    private final RarPpmdVarHDecoder model = new RarPpmdVarHDecoder();
    private byte[] window = new byte[64 * 1024];
    private int windowPos;
    private int escapeChar = 2;
    private boolean modelReady;

    void setTraceSink(RarPpmdVarHDecoder.TraceSink sink) {
        model.setTraceSink(sink);
    }

    int windowSize() {
        return windowPos;
    }

    /** Result of decoding one solid entry. */
    static final class EntryResult {
        final byte[] data;
        final long crc32;

        EntryResult(byte[] data, long crc32) {
            this.data = data;
            this.crc32 = crc32;
        }
    }

    /**
     * Decodes the next entry of the solid set from its packed payload.
     *
     * @param packed       the packed payload bytes of this entry (block
     *                     header + range-coded data)
     * @param unpackedSize the expected unpacked size of this entry
     * @return the decoded bytes and their CRC32
     * @throws RarArchiveReader.UnsupportedRarFeatureException on any
     *         unsupported construct or detected desynchronisation
     */
    EntryResult decodeEntry(byte[] packed, long unpackedSize)
            throws RarArchiveReader.UnsupportedRarFeatureException {
        if (unpackedSize < 0 || unpackedSize > Integer.MAX_VALUE - 8) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3 PPMd entry unpacked size is outside supported bounds: " + unpackedSize);
        }
        if (packed.length < 2) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3 PPMd packed payload is too short: " + packed.length);
        }
        int want = (int) unpackedSize;
        if (windowPos + (long) want > MAX_WINDOW_BYTES) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3 PPMd solid window would exceed the supported limit");
        }

        int flags = packed[0] & 0xFF;
        int off = 1;
        if ((flags & 0x80) == 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3 entry does not start with a PPMd block header (flags=0x"
                            + Integer.toHexString(flags) + ")");
        }
        boolean reset = (flags & 0x20) != 0;
        int memMb = 0;
        if (reset) {
            if (off >= packed.length) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Truncated RAR3 PPMd reset block header");
            }
            memMb = (packed[off++] & 0xFF) + 1;
        }
        if ((flags & 0x40) != 0) {
            if (off >= packed.length) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Truncated RAR3 PPMd escape block header");
            }
            escapeChar = packed[off++] & 0xFF;
        } else {
            escapeChar = 2;
        }

        if (reset) {
            int maxOrder = (flags & 0x1F) + 1;
            if (maxOrder > 16) {
                maxOrder = 16 + (maxOrder - 16) * 3;
            }
            long heapBytes = (long) memMb << 20;
            if (heapBytes > MAX_MODEL_BYTES) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd model memory request is outside supported bounds: " + memMb + " MB");
            }
            if (!model.alloc((int) heapBytes)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd model allocation failed: " + memMb + " MB");
            }
            model.rangeInit(packed, off, packed.length - off);
            model.init(maxOrder);
            modelReady = true;
        } else {
            if (!modelReady) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd continuation block arrived before any model reset; "
                                + "solid predecessors must be decoded first");
            }
            model.rangeInit(packed, off, packed.length - off);
        }

        int start = windowPos;
        try {
            while (windowPos - start < want) {
                int sym = nextSymbol("entry data");
                if (sym != escapeChar) {
                    emitLiteral((byte) sym);
                    continue;
                }
                int code = nextSymbol("escape code");
                if (code == 0) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "RAR3 PPMd next-table boundary inside an entry is not supported yet");
                } else if (code == 2) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "RAR3 PPMd end-of-data arrived before the entry was complete ("
                                    + (windowPos - start) + " of " + want + " bytes)");
                } else if (code == 3) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "RAR3 PPMd VM filter blocks are not supported");
                } else if (code == 4) {
                    int offset = 0;
                    for (int i = 0; i < 3; i++) {
                        offset = (offset << 8) | nextSymbol("match offset");
                    }
                    int len = nextSymbol("match length");
                    emitMatch(offset + 2, len + 32);
                } else if (code == 5) {
                    int len = nextSymbol("run length");
                    emitMatch(1, len + 4);
                } else {
                    emitLiteral((byte) escapeChar);
                }
            }

            // Mandatory end-of-data marker: esc, 2. Both decodes mutate the
            // model and are required for solid continuation correctness.
            int s1 = nextSymbol("end-of-data marker");
            if (s1 != escapeChar) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd stream desynchronised: expected end-of-data escape, got " + s1);
            }
            int s2 = nextSymbol("end-of-data code");
            if (s2 != 2) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd stream desynchronised: expected end-of-data code 2, got " + s2);
            }
        } catch (RarPpmdVarHDecoder.PpmdDataException e) {
            modelReady = false;
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3 PPMd decode failed: " + e.getMessage());
        } catch (RarArchiveReader.UnsupportedRarFeatureException e) {
            modelReady = false;
            throw e;
        }

        byte[] out = new byte[want];
        System.arraycopy(window, start, out, 0, want);
        CRC32 crc = new CRC32();
        crc.update(out, 0, want);
        return new EntryResult(out, crc.getValue());
    }

    private int nextSymbol(String stage) throws RarArchiveReader.UnsupportedRarFeatureException {
        int sym = model.decodeSymbol();
        if (sym < 0) {
            modelReady = false;
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3 PPMd model returned error " + sym + " while decoding " + stage);
        }
        return sym;
    }

    private void ensureCapacity(int extra) {
        int needed = windowPos + extra;
        if (needed <= window.length) {
            return;
        }
        long grown = window.length;
        while (grown < needed) {
            grown <<= 1;
        }
        if (grown > MAX_WINDOW_BYTES) {
            grown = MAX_WINDOW_BYTES;
        }
        byte[] next = new byte[(int) grown];
        System.arraycopy(window, 0, next, 0, windowPos);
        window = next;
    }

    private void emitLiteral(byte b) {
        ensureCapacity(1);
        window[windowPos++] = b;
    }

    private void emitMatch(int distance, int length)
            throws RarArchiveReader.UnsupportedRarFeatureException {
        if (distance <= 0 || distance > windowPos) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3 PPMd match distance " + distance + " exceeds the available window ("
                            + windowPos + " bytes)");
        }
        ensureCapacity(length);
        for (int i = 0; i < length; i++) {
            window[windowPos] = window[windowPos - distance];
            windowPos++;
        }
    }
}
