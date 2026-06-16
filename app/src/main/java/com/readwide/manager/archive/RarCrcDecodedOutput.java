package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * CRC-counting decoded-byte sink for first-party RAR3/RAR4 decode probes.
 *
 * <p>Solid primers must be fully decoded so the LZ/PPMd state advances, but their bytes should
 * not be materialized as temporary files when only a later target entry is requested. This adapter
 * lets the decoder validate the primer CRC while forwarding bytes either to a real sink or to a
 * discard sink.</p>
 */
final class RarCrcDecodedOutput implements RarDecodedOutput {
    @NonNull private final RarDecodedOutput delegate;
    @NonNull private final CRC32 crc32 = new CRC32();
    private long written;

    RarCrcDecodedOutput(@NonNull RarDecodedOutput delegate) {
        if (delegate == null) throw new IllegalArgumentException("Missing RAR CRC output delegate");
        this.delegate = delegate;
    }

    @NonNull
    static RarCrcDecodedOutput discarding() {
        return new RarCrcDecodedOutput(RarDiscardDecodedOutput.INSTANCE);
    }

    @Override
    public void writeDecodedByte(int value) throws IOException {
        int normalized = value & 0xff;
        crc32.update(normalized);
        written++;
        delegate.writeDecodedByte(normalized);
    }

    @Override
    public void writeDecodedBytes(byte[] data, int offset, int length) throws IOException {
        if (data == null) throw new NullPointerException("data");
        if (offset < 0 || length < 0 || offset > data.length || offset + length < offset
                || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid RAR decoded output range");
        }
        crc32.update(data, offset, length);
        written += length;
        delegate.writeDecodedBytes(data, offset, length);
    }

    long written() {
        return written;
    }

    long crcValue() {
        return crc32.getValue() & 0xffffffffL;
    }
}
