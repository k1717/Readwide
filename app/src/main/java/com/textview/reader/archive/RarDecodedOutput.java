package com.textview.reader.archive;

import java.io.IOException;

/**
 * Minimal decoded-byte sink used by the first-party RAR3/RAR4 LZ window.
 *
 * <p>The live first-party decoder still writes unfiltered bytes to the normal file output path.
 * This interface only removes the hard dependency from {@link RarLzWindow} to {@code OutputStream}
 * so future VM-filter buffering can be inserted behind a narrow, testable adapter. Normal
 * compressed RAR remains libarchive-primary; this is only a first-party gap-reduction building
 * block and does not enable VM-filtered extraction by itself.</p>
 */
interface RarDecodedOutput {
    void writeDecodedByte(int value) throws IOException;

    default void writeDecodedBytes(byte[] data, int offset, int length) throws IOException {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length || offset + length < offset
                || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid RAR decoded output range");
        }
        for (int i = 0; i < length; i++) {
            writeDecodedByte(data[offset + i] & 0xff);
        }
    }
}