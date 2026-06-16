package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Locale;

/**
 * Decoded routing metadata for a RAR3/RAR4 PPMd block initialization header.
 *
 * <p>RAR3 reads PPMd blocks through {@code ReadTables30()} and then calls the PPMd model
 * initialization routine on the same byte-aligned stream. The first byte still carries the
 * PPM-block marker in bit 7, but the following bits are not the classic-LZ keep-old-table field:
 * bit 5 is the PPMd model reset flag and bit 6 means that a new escape character byte follows.
 * This class parses those visible bytes into the state transitions needed by the first-party
 * diagnostic model, without copying or embedding UnRAR code.</p>
 */
final class Rar3PpmdBlockHeader {
    static final int MIN_HEADER_BYTES = 1;

    @NonNull private static final Rar3PpmdBlockHeader TOO_SMALL = new Rar3PpmdBlockHeader(
            false, false, false, false, -1, -1, -1, -1, -1, 0,
            "packed payload is too small");

    private final boolean ppmd;
    private final boolean keepOldTable;
    private final boolean resetModel;
    private final boolean escapeCharPresent;
    private final int rawFlags;
    private final int controlByte;
    private final int maxOrderHint;
    private final int memoryMbHint;
    private final int escapeCharHint;
    private final int payloadOffset;
    @NonNull private final String detail;

    private Rar3PpmdBlockHeader(boolean ppmd,
                                boolean keepOldTable,
                                boolean resetModel,
                                boolean escapeCharPresent,
                                int rawFlags,
                                int controlByte,
                                int maxOrderHint,
                                int memoryMbHint,
                                int escapeCharHint,
                                int payloadOffset,
                                @NonNull String detail) {
        this.ppmd = ppmd;
        this.keepOldTable = keepOldTable;
        this.resetModel = resetModel;
        this.escapeCharPresent = escapeCharPresent;
        this.rawFlags = rawFlags;
        this.controlByte = controlByte;
        this.maxOrderHint = maxOrderHint;
        this.memoryMbHint = memoryMbHint;
        this.escapeCharHint = escapeCharHint;
        this.payloadOffset = payloadOffset;
        this.detail = detail;
    }

    @NonNull
    static Rar3PpmdBlockHeader fromPackedPayload(@NonNull byte[] packed) {
        if (packed.length < MIN_HEADER_BYTES) return TOO_SMALL;
        int control = packed[0] & 0xff;
        int rawFlags = ((packed[0] & 0xff) << 8) | (packed.length > 1 ? (packed[1] & 0xff) : 0);
        boolean isPpmd = (control & 0x80) != 0;
        if (!isPpmd) {
            boolean keepOld = (control & 0x40) != 0;
            return new Rar3PpmdBlockHeader(false, keepOld, false, false, rawFlags,
                    control, -1, -1, -1, Math.min(2, packed.length),
                    "RAR3/RAR4 classic-LZ block flag detected");
        }

        boolean reset = (control & 0x20) != 0;
        boolean escapePresent = (control & 0x40) != 0;
        boolean keepOld = !reset;
        int offset = 1;
        int maxOrderHint = -1;
        int memoryMbHint = -1;
        int escapeCharHint = -1;
        String detail = "RAR3/RAR4 PPMd decode-init header detected";

        if (reset) {
            maxOrderHint = decodeMaxOrder(control);
            if (packed.length <= offset) {
                return new Rar3PpmdBlockHeader(true, keepOld, true, escapePresent, rawFlags,
                        control, maxOrderHint, -1, -1, offset + 1,
                        detail + "; missing reset memory byte");
            }
            int maxMb = packed[offset++] & 0xff;
            memoryMbHint = maxMb + 1;
        }
        if (escapePresent) {
            if (packed.length <= offset) {
                return new Rar3PpmdBlockHeader(true, keepOld, reset, true, rawFlags,
                        control, maxOrderHint, memoryMbHint, -1, offset + 1,
                        detail + "; missing escape character byte");
            }
            escapeCharHint = packed[offset++] & 0xff;
        }

        return new Rar3PpmdBlockHeader(true, keepOld, reset, escapePresent, rawFlags,
                control, maxOrderHint, memoryMbHint, escapeCharHint, offset, detail);
    }

    @NonNull
    static Rar3PpmdBlockHeader syntheticForTest(boolean keepOldTable) {
        int control = keepOldTable ? 0x80 : 0xa0; // PPM flag, optionally reset bit.
        int raw = control << 8;
        return new Rar3PpmdBlockHeader(true, keepOldTable, !keepOldTable, false, raw,
                control, keepOldTable ? -1 : 1, keepOldTable ? -1 : 1,
                -1, 1, "synthetic test header");
    }

    private static int decodeMaxOrder(int control) {
        int maxOrder = (control & 0x1f) + 1;
        if (maxOrder > 16) maxOrder = 16 + (maxOrder - 16) * 3;
        return maxOrder;
    }

    boolean isPpmd() {
        return ppmd;
    }

    boolean keepOldTable() {
        return keepOldTable;
    }

    boolean resetModel() {
        return resetModel;
    }

    boolean escapeCharPresent() {
        return escapeCharPresent;
    }

    int rawFlags() {
        return rawFlags;
    }

    int controlByte() {
        return controlByte;
    }

    int maxOrderHint() {
        return maxOrderHint;
    }

    int memoryMbHint() {
        return memoryMbHint;
    }

    int escapeCharHint() {
        return escapeCharHint;
    }

    int payloadOffset() {
        return payloadOffset;
    }

    void requirePpmd() throws IOException {
        if (!ppmd) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd header was requested for a non-PPMd block: " + diagnostic());
        }
    }

    void requireResetParameters() throws IOException {
        requirePpmd();
        if (!resetModel) return;
        if (maxOrderHint <= 0 || memoryMbHint <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd reset header is incomplete: " + diagnostic());
        }
    }

    @NonNull
    String diagnostic() {
        String flagsText = rawFlags < 0 ? "n/a" : String.format(Locale.US, "0x%04x", rawFlags);
        String controlText = controlByte < 0 ? "n/a" : String.format(Locale.US, "0x%02x", controlByte);
        return "ppmd=" + ppmd
                + "; keepOldTable=" + keepOldTable
                + "; resetModel=" + resetModel
                + "; escapeCharPresent=" + escapeCharPresent
                + "; rawFlags=" + flagsText
                + "; controlByte=" + controlText
                + "; maxOrderHint=" + maxOrderHint
                + "; memoryMbHint=" + memoryMbHint
                + "; escapeCharHint=" + escapeCharHint
                + "; payloadOffset=" + payloadOffset
                + "; " + detail;
    }
}
