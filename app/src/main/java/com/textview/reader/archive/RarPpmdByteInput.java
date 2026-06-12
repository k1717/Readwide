package com.textview.reader.archive;

import java.io.EOFException;
import java.io.IOException;

/** Supplies raw bytes to the RAR3/RAR4 PPMd range decoder. */
interface RarPpmdByteInput {
    /** Returns the next byte value in {@code 0..255}. */
    int readByte() throws IOException;

    final class ArrayInput implements RarPpmdByteInput {
        private final byte[] data;
        private final int end;
        private int offset;

        ArrayInput(byte[] data) {
            this(data, 0, data == null ? 0 : data.length);
        }

        ArrayInput(byte[] data, int offset, int length) {
            this.data = data != null ? data : new byte[0];
            int safeOffset = Math.max(0, Math.min(offset, this.data.length));
            int safeLength = Math.max(0, length);
            this.offset = safeOffset;
            long requestedEnd = (long) safeOffset + safeLength;
            this.end = (int) Math.min(this.data.length, requestedEnd);
        }

        @Override
        public int readByte() throws EOFException {
            if (offset >= end) throw new EOFException("RAR3/RAR4 PPMd range stream ended unexpectedly");
            return data[offset++] & 0xff;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return end - offset;
        }
    }
}
