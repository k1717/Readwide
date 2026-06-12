package com.textview.reader.archive;

/** Decoded-byte sink that advances decoder side effects without materializing output bytes. */
final class RarDiscardDecodedOutput implements RarDecodedOutput {
    static final RarDiscardDecodedOutput INSTANCE = new RarDiscardDecodedOutput();

    private RarDiscardDecodedOutput() {}

    @Override
    public void writeDecodedByte(int value) {
        // Intentionally discard. RarLzWindow has already updated its dictionary before this call.
    }

    @Override
    public void writeDecodedBytes(byte[] data, int offset, int length) {
        if (data == null) throw new NullPointerException("data");
        if (offset < 0 || length < 0 || offset > data.length || offset + length < offset
                || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid RAR decoded output range");
        }
        // Intentionally discard.
    }
}
