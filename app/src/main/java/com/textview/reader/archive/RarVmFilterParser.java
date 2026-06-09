package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.zip.CRC32;

/** Minimal RAR3/RAR4 VM-filter block parser for first-party diagnostics. */
final class RarVmFilterParser {
    private static final int MAX_VM_CODE_LENGTH = 1 << 20;

    private RarVmFilterParser() {}

    @NonNull
    static RarVmFilter read(@NonNull RarBitInput input, long outputOffset) throws IOException {
        int bitOffset = checkedBitOffset(input.bitsRead());
        int firstByte = input.readBits(8);
        int lowLength = (firstByte & 0x07) + 1;
        int codeLength;
        RarVmFilter.LengthEncoding lengthEncoding;
        if (lowLength == 7) {
            codeLength = input.readBits(8) + 7;
            lengthEncoding = RarVmFilter.LengthEncoding.EXTENDED_8;
        } else if (lowLength == 8) {
            codeLength = input.readBits(16);
            lengthEncoding = RarVmFilter.LengthEncoding.EXTENDED_16;
        } else {
            codeLength = lowLength;
            lengthEncoding = RarVmFilter.LengthEncoding.INLINE;
        }
        if (codeLength <= 0 || codeLength > MAX_VM_CODE_LENGTH) {
            throw new IOException("Invalid RAR3/RAR4 VM filter code length: " + codeLength);
        }
        if (input.remainingBits() < codeLength * 8L) {
            throw new IOException("RAR3/RAR4 VM filter code is truncated");
        }
        byte[] code = new byte[codeLength];
        CRC32 crc32 = new CRC32();
        for (int i = 0; i < code.length; i++) {
            int value = input.readBits(8);
            code[i] = (byte) value;
            crc32.update(value);
        }
        long crc = crc32.getValue();
        return new RarVmFilter(
                outputOffset,
                bitOffset,
                firstByte,
                lengthEncoding,
                code,
                crc,
                RarStandardFilters.identifyBySignature(code.length, crc));
    }

    private static int checkedBitOffset(long bitsRead) throws IOException {
        if (bitsRead > Integer.MAX_VALUE) {
            throw new IOException("RAR bit offset is too large for VM filter diagnostics");
        }
        return (int) bitsRead;
    }
}
