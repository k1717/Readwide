package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** OutputStream-backed adapter for the default unfiltered first-party RAR output path. */
final class RarOutputStreamDecodedOutput implements RarDecodedOutput {
    private final OutputStream out;

    RarOutputStreamDecodedOutput(@NonNull OutputStream out) {
        if (out == null) {
            throw new IllegalArgumentException("Missing RAR decoded output target");
        }
        this.out = out;
    }

    @NonNull
    static RarDecodedOutput wrapOrMemory(OutputStream out) {
        return new RarOutputStreamDecodedOutput(out != null ? out : new ByteArrayOutputStream());
    }

    @Override
    public void writeDecodedByte(int value) throws IOException {
        out.write(value & 0xff);
    }

    @Override
    public void writeDecodedBytes(byte[] data, int offset, int length) throws IOException {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length || offset + length < offset
                || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid RAR decoded output range");
        }
        out.write(data, offset, length);
    }
}