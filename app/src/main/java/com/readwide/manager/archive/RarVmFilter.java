package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Metadata parsed from a RAR3/RAR4 VM-filter marker in the first-party unpacker path.
 *
 * <p>This helper stores bytecode and standard-filter identity for explicit execution plans.
 * It is not a general VM interpreter. Live archive decoding separately uses
 * {@link Rar3VmFilter} and {@link Rar3PpmdFilterOutput} for recognized standard filters;
 * the presence or absence of a plan here does not describe all archive decoder paths.</p>
 */
final class RarVmFilter {
    enum LengthEncoding {
        INLINE,
        EXTENDED_8,
        EXTENDED_16
    }

    enum StandardFilter {
        UNKNOWN("unknown"),
        E8("E8"),
        E8E9("E8/E9"),
        ITANIUM("Itanium"),
        DELTA("Delta"),
        RGB("RGB"),
        AUDIO("Audio"),
        UPCASE("Upcase");

        final String displayName;

        StandardFilter(String displayName) {
            this.displayName = displayName;
        }
    }

    final long outputOffset;
    final int bitOffset;
    final int firstByte;
    final LengthEncoding lengthEncoding;
    final byte[] code;
    final long codeCrc32;
    final StandardFilter standardFilter;

    RarVmFilter(long outputOffset,
                int bitOffset,
                int firstByte,
                @NonNull LengthEncoding lengthEncoding,
                @NonNull byte[] code,
                long codeCrc32,
                @NonNull StandardFilter standardFilter) {
        this.outputOffset = outputOffset;
        this.bitOffset = bitOffset;
        this.firstByte = firstByte & 0xff;
        this.lengthEncoding = lengthEncoding;
        this.code = Arrays.copyOf(code, code.length);
        this.codeCrc32 = codeCrc32 & 0xffff_ffffL;
        this.standardFilter = standardFilter;
    }

    int codeLength() {
        return code.length;
    }

    boolean hasStandaloneStandardFilterPrimitive() {
        return RarStandardFilters.hasStandalonePrimitive(standardFilter);
    }

    @NonNull
    String diagnosticSummary() {
        return "outputOffset=" + outputOffset
                + ", bitOffset=" + bitOffset
                + ", firstByte=0x" + hexByte(firstByte)
                + ", codeLength=" + code.length
                + ", lengthEncoding=" + lengthEncoding
                + ", standardFilter=" + standardFilter.displayName
                + ", standalonePrimitive=" + hasStandaloneStandardFilterPrimitive()
                + ", codeCrc32=0x" + hexWord(codeCrc32);
    }

    private static String hexByte(int value) {
        String text = Integer.toHexString(value & 0xff);
        return text.length() == 1 ? "0" + text : text;
    }

    private static String hexWord(long value) {
        String text = Long.toHexString(value & 0xffff_ffffL);
        StringBuilder builder = new StringBuilder();
        for (int i = text.length(); i < 8; i++) builder.append('0');
        return builder.append(text).toString();
    }
}
