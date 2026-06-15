package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.util.zip.CRC32;

/**
 * Signature table for known RAR VM standard filters.
 *
 * <p>RAR3/RAR4 streams do not carry a simple public "standard filter id" here; common decoder-style
 * implementations recognize common filters by VM-bytecode length plus CRC signature. This table
 * is used for diagnostics and to mark the narrow E8/E8E9 standalone primitive candidates. Unknown/custom filters remain clean first-party gaps.</p>
 */
final class RarStandardFilters {
    private RarStandardFilters() {}

    @NonNull
    static RarVmFilter.StandardFilter identify(@NonNull byte[] code) {
        CRC32 crc32 = new CRC32();
        crc32.update(code, 0, code.length);
        return identifyBySignature(code.length, crc32.getValue());
    }

    static boolean hasStandalonePrimitive(@NonNull RarVmFilter.StandardFilter filter) {
        return RarStandardFilterApplier.hasStandalonePrimitive(filter);
    }

    @NonNull
    static RarVmFilter.StandardFilter identifyBySignature(int codeLength, long codeCrc32) {
        long crc = codeCrc32 & 0xffff_ffffL;
        if (codeLength == 53 && crc == 0xad576887L) return RarVmFilter.StandardFilter.E8;
        if (codeLength == 57 && crc == 0x3cd7e57eL) return RarVmFilter.StandardFilter.E8E9;
        if (codeLength == 120 && crc == 0x3769893fL) return RarVmFilter.StandardFilter.ITANIUM;
        if (codeLength == 29 && crc == 0x0e06077dL) return RarVmFilter.StandardFilter.DELTA;
        if (codeLength == 149 && crc == 0x1c2c5dc8L) return RarVmFilter.StandardFilter.RGB;
        if (codeLength == 216 && crc == 0xbc85e701L) return RarVmFilter.StandardFilter.AUDIO;
        if (codeLength == 40 && crc == 0x46b9c560L) return RarVmFilter.StandardFilter.UPCASE;
        return RarVmFilter.StandardFilter.UNKNOWN;
    }
}
