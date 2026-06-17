package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * First-party diagnostic skeleton for RAR3/RAR4 PPMd CreateSuccessors-style materialization.
 *
 * <p>The reference RAR PPMd model does not treat every {@code FoundState.Successor} as an already-built
 * context pointer. Some successors are text-position/pending successors that are materialized into
 * contexts only when the model later needs to traverse them. This class keeps that distinction in
 * our own representation without copying reference RAR code or constants. The current implementation is
 * intentionally conservative: it creates a bounded order-1/order-2 context and seeds it with the
 * decoded state symbol at frequency 1 so fixture probes can distinguish an empty materialized
 * context from a minimally linked one. It remains a diagnostic scaffold and is not a production
 * RAR PPMd decoder.</p>
 */
final class RarPpmdCreateSuccessors {
    private RarPpmdCreateSuccessors() {}

    static final int SEED_NONE = 0;
    static final int SEED_OWNER_SYMBOL = 1;
    static final int SEED_PENDING_SYMBOL = 2;
    static final int SEED_HISTORY_NEWEST = 3;

    @NonNull
    static RarPpmdContext materializeRootPendingSuccessor(@NonNull RarPpmdContextChain chain,
                                                          @NonNull RarPpmdSubAllocator allocator,
                                                          @NonNull RarPpmdStateRecord rootState,
                                                          @NonNull RarPpmdSymbolHistory history,
                                                          int seedMode)
            throws IOException {
        if (!rootState.hasPendingTextSuccessor()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd root successor is not pending text: symbol="
                            + rootState.symbol());
        }
        RarPpmdContext order1 = chain.ensureOrder1Context(rootState.pendingSuccessorSymbol(),
                allocator);
        seedMaterializedContext(order1, rootState, history, seedMode, allocator);
        rootState.setSuccessorPointer(order1.contextPointer());
        return order1;
    }

    @NonNull
    static RarPpmdContext materializeOrder1PendingSuccessor(@NonNull RarPpmdContextChain chain,
                                                            @NonNull RarPpmdSubAllocator allocator,
                                                            int ownerPreviousSymbol,
                                                            @NonNull RarPpmdStateRecord ownerState,
                                                            @NonNull RarPpmdSymbolHistory history,
                                                            int seedMode)
            throws IOException {
        if (!ownerState.hasPendingTextSuccessor()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd order-1 successor is not pending text: previous="
                            + ownerPreviousSymbol + "; symbol=" + ownerState.symbol());
        }
        RarPpmdContext order2 = chain.ensureOrder2Context(ownerState.pendingSuccessorSymbol(),
                ownerPreviousSymbol, allocator);
        seedMaterializedContext(order2, ownerState, history, seedMode, allocator);
        ownerState.setSuccessorPointer(order2.contextPointer());
        return order2;
    }

    static int seedSymbolForDiagnostic(@NonNull RarPpmdStateRecord ownerState,
                                       @NonNull RarPpmdSymbolHistory history,
                                       int seedMode) throws IOException {
        switch (seedMode) {
            case SEED_NONE:
                return -1;
            case SEED_OWNER_SYMBOL:
                return ownerState.symbol();
            case SEED_PENDING_SYMBOL:
                return ownerState.pendingSuccessorSymbol();
            case SEED_HISTORY_NEWEST:
                return history.newest();
            default:
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3/RAR4 PPMd CreateSuccessors diagnostic seed mode is unknown: "
                                + seedMode);
        }
    }

    static boolean seedMaterializedContext(@NonNull RarPpmdContext context,
                                           @NonNull RarPpmdStateRecord ownerState,
                                           @NonNull RarPpmdSymbolHistory history,
                                           int seedMode,
                                           @NonNull RarPpmdSubAllocator allocator)
            throws IOException {
        int seedSymbol = seedSymbolForDiagnostic(ownerState, history, seedMode);
        if (seedSymbol < 0) return false;
        return seedContextIfMissing(context, seedSymbol, allocator);
    }

    static boolean seedContextIfMissing(@NonNull RarPpmdContext context,
                                        int symbol,
                                        @NonNull RarPpmdSubAllocator allocator) throws IOException {
        if (symbol < 0) return false;
        if (context.findState(symbol) != null) return false;
        context.insertOrUpdateState(symbol, 1, RarPpmdStateRecord.NO_SUCCESSOR);
        context.ensureStateArrayCapacity(allocator);
        return true;
    }
}
