package com.textview.reader.archive;

import java.io.EOFException;

final class RarBitInput {
    private final byte[] data;
    private int byteOffset;
    private int bitOffset;

    RarBitInput(byte[] data) {
        this.data = data != null ? data : new byte[0];
    }

    int readBit() throws EOFException {
        ensureBits(1);
        int value = (data[byteOffset] >> (7 - bitOffset)) & 1;
        advance(1);
        return value;
    }

    int readBits(int count) throws EOFException {
        if (count < 0 || count > 24) throw new IllegalArgumentException("Invalid RAR bit count");
        ensureBits(count);
        int value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 1) | readBit();
        }
        return value;
    }

    int peekBits(int count) throws EOFException {
        int savedByteOffset = byteOffset;
        int savedBitOffset = bitOffset;
        int value = readBits(count);
        byteOffset = savedByteOffset;
        bitOffset = savedBitOffset;
        return value;
    }

    void skipBits(int count) throws EOFException {
        if (count < 0) throw new IllegalArgumentException("Invalid RAR bit count");
        ensureBits(count);
        advance(count);
    }

    void alignToByte() {
        if (bitOffset == 0) return;
        byteOffset++;
        bitOffset = 0;
    }

    int remainingBits() {
        return data.length * 8 - (byteOffset * 8 + bitOffset);
    }

    long bitsRead() {
        return byteOffset * 8L + bitOffset;
    }

    private void ensureBits(int count) throws EOFException {
        if (remainingBits() < count) throw new EOFException("RAR bit stream ended unexpectedly");
    }

    private void advance(int count) {
        int total = bitOffset + count;
        byteOffset += total / 8;
        bitOffset = total % 8;
    }
}
