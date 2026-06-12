package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * Adapter between the RAR3/RAR4 PPMd model state and the RAR-specific PPM control layer.
 *
 * <p>The class now owns a real model-state holder and a decode facade instead of throwing
 * directly from {@link #decodeSymbol()}. Production decoding still stops at the documented
 * PPMd-I statistical-model boundary until multi-order context traversal, production SEE-table
 * selection, masked-symbol decoding, and exact model updates are verified by real fixtures.</p>
 */
final class Rar3PpmdModelSymbolSource implements Rar3PpmdSymbolSource {
    @NonNull private final RarPpmdRangeDecoder rangeDecoder;
    @NonNull private final RarPpmdModel model;
    @NonNull private final RarPpmdModelDecoder modelDecoder;
    @NonNull private final Rar3PpmdState ppmdState;
    @NonNull private final Rar3PpmdBlockHeader blockHeader;

    Rar3PpmdModelSymbolSource(@NonNull RarPpmdByteInput byteInput,
                              boolean keepOldTable) throws IOException {
        this(byteInput, syntheticStateForTest(keepOldTable),
                Rar3PpmdBlockHeader.syntheticForTest(keepOldTable));
    }

    Rar3PpmdModelSymbolSource(@NonNull RarPpmdByteInput byteInput,
                              @NonNull Rar3PpmdState ppmdState,
                              @NonNull Rar3PpmdBlockHeader blockHeader) throws IOException {
        this(new RarPpmdRangeDecoder(byteInput), ppmdState, blockHeader, false);
    }

    private Rar3PpmdModelSymbolSource(@NonNull RarPpmdRangeDecoder rangeDecoder,
                                      @NonNull Rar3PpmdState ppmdState,
                                      @NonNull Rar3PpmdBlockHeader blockHeader,
                                      boolean allowDiagnosticModelDecode) throws IOException {
        this(rangeDecoder, ppmdState, blockHeader, allowDiagnosticModelDecode,
                RarPpmdDiagnosticOptions.standard());
    }

    private Rar3PpmdModelSymbolSource(@NonNull RarPpmdRangeDecoder rangeDecoder,
                                      @NonNull Rar3PpmdState ppmdState,
                                      @NonNull Rar3PpmdBlockHeader blockHeader,
                                      boolean allowDiagnosticModelDecode,
                                      @NonNull RarPpmdDiagnosticOptions diagnosticOptions) throws IOException {
        blockHeader.requirePpmd();
        ppmdState.applyHeader(blockHeader);
        this.rangeDecoder = rangeDecoder;
        this.ppmdState = ppmdState;
        this.blockHeader = blockHeader;
        this.model = ppmdState.requireModel();
        if (allowDiagnosticModelDecode) {
            this.model.applyDiagnosticOptionsForTest(diagnosticOptions, blockHeader.resetModel());
        }
        this.modelDecoder = new RarPpmdModelDecoder(
                model,
                rangeDecoder,
                ppmdState.diagnostic(),
                blockHeader.diagnostic(),
                allowDiagnosticModelDecode,
                diagnosticOptions);
    }

    @NonNull
    static Rar3PpmdModelSymbolSource diagnosticOrder0ForTest(
            @NonNull RarPpmdRangeDecoder rangeDecoder,
            @NonNull Rar3PpmdState ppmdState,
            @NonNull Rar3PpmdBlockHeader blockHeader) throws IOException {
        return diagnosticContextFallbackForTest(rangeDecoder, ppmdState, blockHeader);
    }

    @NonNull
    static Rar3PpmdModelSymbolSource diagnosticContextFallbackForTest(
            @NonNull RarPpmdRangeDecoder rangeDecoder,
            @NonNull Rar3PpmdState ppmdState,
            @NonNull Rar3PpmdBlockHeader blockHeader) throws IOException {
        return diagnosticWithOptionsForTest(rangeDecoder, ppmdState, blockHeader,
                RarPpmdDiagnosticOptions.standard());
    }

    @NonNull
    static Rar3PpmdModelSymbolSource diagnosticWithOptionsForTest(
            @NonNull RarPpmdRangeDecoder rangeDecoder,
            @NonNull Rar3PpmdState ppmdState,
            @NonNull Rar3PpmdBlockHeader blockHeader,
            @NonNull RarPpmdDiagnosticOptions diagnosticOptions) throws IOException {
        return new Rar3PpmdModelSymbolSource(rangeDecoder, ppmdState, blockHeader, true,
                diagnosticOptions);
    }

    @NonNull
    RarPpmdRangeDecoder rangeDecoderForTest() {
        return rangeDecoder;
    }

    @NonNull
    RarPpmdSubAllocator subAllocatorForTest() {
        return model.subAllocatorForTest();
    }

    @NonNull
    Rar3PpmdOrder0Model order0ModelForTest() {
        return model.order0BootstrapModelForTest();
    }

    @NonNull
    RarPpmdContext rootContextForTest() {
        return model.rootContextForTest();
    }

    @Nullable
    RarPpmdContext order1ContextForTest(int previousSymbol) throws IOException {
        return model.order1ContextForTest(previousSymbol);
    }

    @NonNull
    RarPpmdContext ensureOrder1ContextForTest(int previousSymbol) throws IOException {
        return model.ensureOrder1ContextForTest(previousSymbol);
    }

    @Nullable
    RarPpmdContext order2ContextForTest(int newestPreviousSymbol, int olderPreviousSymbol) throws IOException {
        return model.order2ContextForTest(newestPreviousSymbol, olderPreviousSymbol);
    }

    @NonNull
    RarPpmdContext ensureOrder2ContextForTest(int newestPreviousSymbol, int olderPreviousSymbol) throws IOException {
        return model.ensureOrder2ContextForTest(newestPreviousSymbol, olderPreviousSymbol);
    }

    int allocatedOrder1ContextCountForTest() {
        return model.allocatedOrder1ContextCountForTest();
    }

    int allocatedOrder2ContextCountForTest() {
        return model.allocatedOrder2ContextCountForTest();
    }

    int allocatedSuccessorContextCountForTest() {
        return model.allocatedSuccessorContextCountForTest();
    }

    int registeredSuccessorContextCountForTest() {
        return model.registeredSuccessorContextCountForTest();
    }

    int diagnosticRescaleCountForTest() {
        return model.diagnosticRescaleCountForTest();
    }

    @NonNull
    String lastDecodeTraceForTest() {
        return model.lastDecodeTraceForTest();
    }

    @NonNull
    int[] symbolHistorySnapshotForTest() {
        return model.symbolHistorySnapshotForTest();
    }

    @Nullable
    RarPpmdContext successorContextForTest(int pointer) throws IOException {
        return model.successorContextForTest(pointer);
    }

    @Nullable
    RarPpmdContext successorTraversalCandidateForTest(int newestPreviousSymbol,
                                                       int olderPreviousSymbol)
            throws IOException {
        return model.successorTraversalCandidateForTest(newestPreviousSymbol, olderPreviousSymbol);
    }

    @NonNull
    RarPpmdContext ensureSuccessorForStateForTest(@NonNull RarPpmdContext ownerContext,
                                                  int symbol) throws IOException {
        return model.ensureSuccessorForStateForTest(ownerContext, symbol);
    }

    @Nullable
    RarPpmdContext materializedSuccessorTraversalCandidateForTest() throws IOException {
        return model.materializedSuccessorTraversalCandidateForTest();
    }

    @Nullable
    RarPpmdContext seededMaterializedSuccessorTraversalCandidateForTest() throws IOException {
        return model.materializedSuccessorTraversalCandidateForTest(true);
    }

    int symbolHistoryForTest(int historyIndex) {
        return model.symbolHistoryForTest(historyIndex);
    }

    void seedPreviousSymbolForTest(int symbol) throws IOException {
        model.seedPreviousSymbolForTest(symbol);
    }

    @NonNull
    RarPpmdEscapeMask escapeMaskForTest() {
        return model.escapeMaskForTest();
    }

    @NonNull
    RarPpmdSeeContext seeContextForTest() {
        return model.seeContextForTest();
    }

    @NonNull
    RarPpmdSeeTable seeTableForTest() {
        return model.seeTableForTest();
    }

    @NonNull
    Rar3PpmdState ppmdStateForTest() {
        return ppmdState;
    }

    @NonNull
    RarPpmdModel modelForTest() {
        return model;
    }

    @NonNull
    Rar3PpmdBlockHeader blockHeaderForTest() {
        return blockHeader;
    }

    @Override
    public int decodeSymbol() throws IOException {
        return modelDecoder.decodeSymbol();
    }

    @NonNull
    private static Rar3PpmdState syntheticStateForTest(boolean keepOldTable) throws IOException {
        Rar3PpmdState state = new Rar3PpmdState();
        if (keepOldTable) {
            state.applyHeader(Rar3PpmdBlockHeader.syntheticForTest(false));
        }
        return state;
    }
}
