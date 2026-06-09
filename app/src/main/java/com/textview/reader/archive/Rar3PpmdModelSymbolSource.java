package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Placeholder adapter for the future full RAR3/RAR4 PPMd statistical model.
 *
 * <p>The source keeps byte/range-decoder, allocator/order-0, context/state, escape-mask, and
 * SEE skeleton primitives for diagnostics. The real context tree traversal, suffix fallback,
 * SEE table selection, masked-symbol arithmetic decoding, and model-update rules still remain
 * intentionally contained behind this class.</p>
 */
final class Rar3PpmdModelSymbolSource implements Rar3PpmdSymbolSource {
    @NonNull private final RarPpmdRangeDecoder rangeDecoder;
    @NonNull private final RarPpmdSubAllocator subAllocator;
    @NonNull private final Rar3PpmdOrder0Model order0Model;
    @NonNull private final RarPpmdContext rootContext;
    @NonNull private final RarPpmdEscapeMask escapeMask;
    @NonNull private final RarPpmdSeeContext seeContext;
    private final boolean keepOldTable;

    Rar3PpmdModelSymbolSource(@NonNull RarPpmdByteInput byteInput,
                              boolean keepOldTable) throws IOException {
        this.rangeDecoder = new RarPpmdRangeDecoder(byteInput);
        this.subAllocator = new RarPpmdSubAllocator(64);
        this.order0Model = new Rar3PpmdOrder0Model();
        this.rootContext = new RarPpmdContext();
        this.escapeMask = new RarPpmdEscapeMask();
        this.seeContext = RarPpmdSeeContext.defaultContext();
        this.keepOldTable = keepOldTable;
        seedOrder0Context();
    }

    @NonNull
    RarPpmdRangeDecoder rangeDecoderForTest() {
        return rangeDecoder;
    }

    @NonNull
    RarPpmdSubAllocator subAllocatorForTest() {
        return subAllocator;
    }

    @NonNull
    Rar3PpmdOrder0Model order0ModelForTest() {
        return order0Model;
    }

    @NonNull
    RarPpmdContext rootContextForTest() {
        return rootContext;
    }

    @NonNull
    RarPpmdEscapeMask escapeMaskForTest() {
        return escapeMask;
    }

    @NonNull
    RarPpmdSeeContext seeContextForTest() {
        return seeContext;
    }

    @Override
    public int decodeSymbol() throws IOException {
        throw Rar3FirstPartyGap.ppmdModel(keepOldTable);
    }

    private void seedOrder0Context() throws IOException {
        rootContext.insertOrUpdateState(0, 1, RarPpmdStateRecord.NO_SUCCESSOR);
        rootContext.allocateStateArray(subAllocator);
    }
}
