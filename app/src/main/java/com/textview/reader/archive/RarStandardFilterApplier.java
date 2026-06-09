package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Standalone primitive implementations for a small, safe subset of RAR3/RAR4 standard filters.
 *
 * <p>RAR VM-filtered output cannot be wired into the archive decoder merely by applying a filter
 * when symbol {@code 257} is seen: the real pipeline needs queued filter metadata, delayed output
 * windows, and CRC validation after filtered bytes are emitted. This helper is therefore limited to
 * isolated byte ranges and tests. The live first-party path deliberately exposes only E8/E8E9
 * x86 branch conversion as a primitive, with an in-memory delayed-output queue scaffold on top.
 * Delta/RGB/Audio/
 * Itanium/Upcase/custom VM bytecode remain first-party gaps.</p>
 */
final class RarStandardFilterApplier {
    private RarStandardFilterApplier() {}

    static boolean hasStandalonePrimitive(@NonNull RarVmFilter.StandardFilter filter) {
        return filter == RarVmFilter.StandardFilter.E8
                || filter == RarVmFilter.StandardFilter.E8E9;
    }

    @NonNull
    static byte[] applyToCopy(@NonNull byte[] data,
                              int offset,
                              int length,
                              @NonNull RarVmFilter.StandardFilter filter,
                              long fileOffset) throws IOException {
        validateRange(data, offset, length, fileOffset);
        byte[] copy = Arrays.copyOf(data, data.length);
        applyInPlace(copy, offset, length, filter, fileOffset);
        return copy;
    }

    static void applyInPlace(@NonNull byte[] data,
                             int offset,
                             int length,
                             @NonNull RarVmFilter.StandardFilter filter,
                             long fileOffset) throws IOException {
        validateRange(data, offset, length, fileOffset);
        switch (filter) {
            case E8:
                applyX86BranchFilter(data, offset, length, fileOffset, false);
                return;
            case E8E9:
                applyX86BranchFilter(data, offset, length, fileOffset, true);
                return;
            default:
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3/RAR4 standard filter " + filter.displayName
                                + " has no standalone first-party primitive yet");
        }
    }

    /**
     * Applies a BCJ-style x86 branch post-filter to an isolated byte range.
     *
     * <p>The input value after an E8/E9 opcode is treated as an absolute 32-bit target and converted
     * to a signed 32-bit relative displacement for the byte position within this range. Arithmetic is
     * intentionally 32-bit wrapping, matching the branch displacement width.</p>
     */
    private static void applyX86BranchFilter(@NonNull byte[] data,
                                             int offset,
                                             int length,
                                             long fileOffset,
                                             boolean includeE9) {
        int lastOpcode = offset + length - 5;
        for (int pos = offset; pos <= lastOpcode; pos++) {
            int opcode = data[pos] & 0xff;
            if (opcode == 0xe8 || (includeE9 && opcode == 0xe9)) {
                int absoluteTarget = readIntLE(data, pos + 1);
                int branchBase = (int) (fileOffset + (pos - offset) + 5L);
                int relativeTarget = absoluteTarget - branchBase;
                writeIntLE(data, pos + 1, relativeTarget);
                pos += 4;
            }
        }
    }

    private static int readIntLE(@NonNull byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static void writeIntLE(@NonNull byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void validateRange(@NonNull byte[] data,
                                      int offset,
                                      int length,
                                      long fileOffset) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length || offset + length < offset
                || offset + length > data.length) {
            throw new IOException("Invalid RAR standard-filter byte range");
        }
        if (fileOffset < 0) {
            throw new IOException("Invalid RAR standard-filter file offset");
        }
    }
}
