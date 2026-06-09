package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/** Connects the pass-32 order-0 model primitive to the pass-30 PPMd control layer. */
final class Rar3PpmdOrder0SymbolSource implements Rar3PpmdSymbolSource {
    @NonNull private final RarPpmdRangeDecoder rangeDecoder;
    @NonNull private final Rar3PpmdOrder0Model model;

    Rar3PpmdOrder0SymbolSource(@NonNull RarPpmdByteInput byteInput,
                               @NonNull Rar3PpmdOrder0Model model) throws IOException {
        this(new RarPpmdRangeDecoder(byteInput), model);
    }

    Rar3PpmdOrder0SymbolSource(@NonNull RarPpmdRangeDecoder rangeDecoder,
                               @NonNull Rar3PpmdOrder0Model model) {
        this.rangeDecoder = rangeDecoder;
        this.model = model;
    }

    @Override
    public int decodeSymbol() throws IOException {
        return model.decodeSymbol(rangeDecoder);
    }

    @NonNull
    RarPpmdRangeDecoder rangeDecoderForTest() {
        return rangeDecoder;
    }

    @NonNull
    Rar3PpmdOrder0Model modelForTest() {
        return model;
    }
}
