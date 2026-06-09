package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * PPMd range-decoder primitive used by RAR3/RAR4 PPM blocks.
 *
 * <p>This is deliberately only the arithmetic range layer. It does not implement the PPMd
 * statistical model, SEE contexts, memory allocator, or RAR-specific model initialization yet.
 * Those remain first-party gaps until real PPMd fixtures pass CRC.</p>
 */
final class RarPpmdRangeDecoder {
    private static final long MASK_32 = 0xffff_ffffL;
    private static final long TOP = 1L << 24;
    private static final long BOT = 1L << 15;

    @NonNull private final RarPpmdByteInput input;
    private long low;
    private long code;
    private long range;

    RarPpmdRangeDecoder(@NonNull RarPpmdByteInput input) throws IOException {
        this.input = input;
        low = 0;
        code = 0;
        range = MASK_32;
        for (int i = 0; i < 4; i++) {
            code = ((code << 8) | input.readByte()) & MASK_32;
        }
    }

    /** Test-only constructor for deterministic range arithmetic probes. */
    RarPpmdRangeDecoder(@NonNull RarPpmdByteInput input, long low, long code, long range) {
        this.input = input;
        this.low = low & MASK_32;
        this.code = code & MASK_32;
        this.range = range & MASK_32;
    }

    int currentCount(int scale) throws IOException {
        validateScale(scale);
        range = Long.divideUnsigned(range, scale) & MASK_32;
        if (range == 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd range decoder underflowed while reading a symbol");
        }
        long count = Long.divideUnsigned(code, range);
        if (count < 0 || count >= scale) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd range decoder produced an out-of-range count: "
                            + count + " / " + scale);
        }
        return (int) count;
    }

    void removeSubrange(int lowCount, int highCount, int scale) throws IOException {
        validateSubrange(lowCount, highCount, scale);
        long oldRange = range;
        code = (code - oldRange * lowCount) & MASK_32;
        low = (low + oldRange * lowCount) & MASK_32;
        range = (oldRange * (highCount - lowCount)) & MASK_32;
        normalize();
    }

    long low() {
        return low & MASK_32;
    }

    long code() {
        return code & MASK_32;
    }

    long range() {
        return range & MASK_32;
    }

    private void normalize() throws IOException {
        while (true) {
            long lowPlusRange = (low + range) & MASK_32;
            boolean highBytesStable = ((low ^ lowPlusRange) & 0xff00_0000L) == 0;
            boolean rangeTooSmall = range < BOT;
            if (!highBytesStable && !rangeTooSmall) return;
            if (rangeTooSmall) {
                range = (-low) & (BOT - 1);
                if (range == 0) range = BOT;
            }
            code = ((code << 8) | input.readByte()) & MASK_32;
            range = (range << 8) & MASK_32;
            low = (low << 8) & MASK_32;
            if (range == 0) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3/RAR4 PPMd range decoder normalized to an empty range");
            }
        }
    }

    private static void validateScale(int scale) throws IOException {
        if (scale <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd model supplied an invalid scale: " + scale);
        }
    }

    private static void validateSubrange(int lowCount, int highCount, int scale) throws IOException {
        validateScale(scale);
        if (lowCount < 0 || highCount <= lowCount || highCount > scale) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd model supplied an invalid subrange: "
                            + lowCount + ".." + highCount + " / " + scale);
        }
    }
}
