package com.textview.reader.archive;

import java.io.EOFException;
import java.io.IOException;

/** Supplies raw bytes to the RAR3/RAR4 PPMd range decoder. */
interface RarPpmdByteInput {
    /** Returns the next byte value in {@code 0..255}. */
    int readByte() throws IOException;

    final class ArrayInput implements RarPpmdByteInput {
        private final byte[] data;
        private int offset;

        ArrayInput(byte[] data) {
            this.data = data != null ? data : new byte[0];
        }

        @Override
        public int readByte() throws EOFException {
            if (offset >= data.length) throw new EOFException("RAR3/RAR4 PPMd range stream ended unexpectedly");
            return data[offset++] & 0xff;
        }

        int offset() {
            return offset;
        }
    }
}
