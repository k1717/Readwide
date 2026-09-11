package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;

/** PPMd model retained across LZ blocks and solid entries; raw history belongs to RarLzWindow. */
final class Rar3MixedPpmdState implements Rar3ClassicLzEngine.PpmdSource {
    private RarPpmdVarHDecoder model;
    private int escape = 2;
    private boolean ready;

    void reset() { model = null; escape = 2; ready = false; }
    @Override public int escape() { return escape; }

    @Override public void readTable(InputStream input) throws IOException {
        int flags = required(input);
        if ((flags & 0x80) == 0) throw new IOException("Expected RAR3 PPMd table");
        boolean reset = (flags & 0x20) != 0;
        int megabytes = reset ? required(input) + 1 : 0;
        if ((flags & 0x40) != 0) escape = required(input);
        try {
            if (reset) {
                int order = (flags & 31) + 1;
                if (order == 1) throw new IOException("Invalid RAR3 PPMd order");
                if (order > 16) order = 16 + (order - 16) * 3;
                if (model == null) model = new RarPpmdVarHDecoder();
                if (!model.alloc(megabytes << 20)) throw new IOException("RAR3 PPMd model allocation failed");
                model.rangeInit(input);
                model.init(order);
                ready = true;
            } else {
                if (!ready) throw new IOException("RAR3 PPMd continuation lacks a model");
                model.rangeInit(input);
            }
        } catch (RarPpmdVarHDecoder.PpmdDataException error) { throw convert(error); }
    }

    @Override public int symbol() throws IOException {
        if (!ready) throw new IOException("RAR3 PPMd model is not ready");
        try {
            int value = model.decodeSymbol();
            if (value < 0 || value > 255) throw new IOException("Invalid RAR3 PPMd symbol");
            return value;
        } catch (RarPpmdVarHDecoder.PpmdDataException error) { throw convert(error); }
    }

    private static int required(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new java.io.EOFException("Truncated RAR3 PPMd header");
        return value;
    }

    private static IOException convert(RarPpmdVarHDecoder.PpmdDataException error) {
        return error.getCause() instanceof IOException ? (IOException) error.getCause()
                : new IOException("RAR3 PPMd decode failed", error);
    }
}
