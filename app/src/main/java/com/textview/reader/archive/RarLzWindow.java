package com.textview.reader.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class RarLzWindow {
    private final byte[] window;
    private final RarDecodedOutput out;
    private int position;
    private long written;

    RarLzWindow(int size, OutputStream out) {
        this(size, RarOutputStreamDecodedOutput.wrapOrMemory(out));
    }

    RarLzWindow(int size, RarDecodedOutput out) {
        this(new byte[validateWindowSize(size)], 0, out);
    }

    RarLzWindow(byte[] sharedWindow, int initialPosition, OutputStream out) {
        this(sharedWindow, initialPosition, RarOutputStreamDecodedOutput.wrapOrMemory(out));
    }

    RarLzWindow(byte[] sharedWindow, int initialPosition, RarDecodedOutput out) {
        if (sharedWindow == null || sharedWindow.length <= 0
                || (sharedWindow.length & (sharedWindow.length - 1)) != 0) {
            throw new IllegalArgumentException("RAR LZ window size must be a power of two");
        }
        this.window = sharedWindow;
        this.position = initialPosition & (sharedWindow.length - 1);
        this.out = out != null ? out : new RarOutputStreamDecodedOutput(new ByteArrayOutputStream());
    }

    private static int validateWindowSize(int size) {
        if (size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("RAR LZ window size must be a power of two");
        }
        return size;
    }

    void writeLiteral(int value) throws IOException {
        byte b = (byte) value;
        window[position] = b;
        position = (position + 1) & (window.length - 1);
        out.writeDecodedByte(b & 0xff);
        written++;
    }

    void copyMatch(int distance, int length) throws IOException {
        if (distance <= 0 || distance > window.length) {
            throw new IOException("Invalid RAR LZ distance");
        }
        if (length < 0) throw new IOException("Invalid RAR LZ length");
        for (int i = 0; i < length; i++) {
            int source = (position - distance) & (window.length - 1);
            writeLiteral(window[source] & 0xff);
        }
    }

    long written() {
        return written;
    }

    int position() {
        return position;
    }
}
