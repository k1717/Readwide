package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Centralized diagnostic update/rescale path for the first-party RAR3/RAR4 PPMd model.
 *
 * <p>Earlier passes taught root/order-1/order-2 contexts directly inside {@link RarPpmdModel}.
 * That made it too easy for the future live decoder to diverge from the diagnostic path. This
 * helper owns the common post-symbol actions: ensure the decoded state has a successor context,
 * teach suffix contexts, promote states, rescale, and refresh allocator-backed state arrays. It is
 * still deliberately bounded to the current diagnostic context depths; exact RAR PPMd-I update
 * constants remain gated until the real fixture path verifies them.</p>
 */
final class RarPpmdModelUpdater {
    private final int maxOrder;
    @NonNull private final RarPpmdSubAllocator subAllocator;
    @NonNull private final RarPpmdContextChain contextChain;
    @NonNull private final RarPpmdRescalePolicy rescalePolicy;

    RarPpmdModelUpdater(int maxOrder,
                        @NonNull RarPpmdSubAllocator subAllocator,
                        @NonNull RarPpmdContextChain contextChain) {
        this.maxOrder = Math.max(0, maxOrder);
        this.subAllocator = subAllocator;
        this.contextChain = contextChain;
        this.rescalePolicy = RarPpmdRescalePolicy.diagnosticDefault();
    }

    @NonNull
    UpdateResult learnAfterContextDecode(int previous,
                                         int olderPrevious,
                                         int symbol,
                                         @NonNull RarPpmdContext decodedContext)
            throws IOException {
        return learnAfterContextDecode(previous, olderPrevious, symbol, decodedContext, false);
    }

    @NonNull
    UpdateResult learnAfterContextDecode(int previous,
                                         int olderPrevious,
                                         int symbol,
                                         @NonNull RarPpmdContext decodedContext,
                                         boolean pendingTextSuccessors)
            throws IOException {
        UpdateResult result = new UpdateResult();
        result.successorCreated = ensureSuccessorForDecodedSymbol(previous, decodedContext, symbol,
                pendingTextSuccessors);
        if (decodedContext != contextChain.rootContext()) {
            if (teachRoot(symbol)) result.rescaledContextCount++;
            result.taughtRoot = true;
        }
        if (previous >= 0) {
            RarPpmdContext order1 = contextChain.ensureOrder1Context(previous, subAllocator);
            if (decodedContext != order1) {
                if (contextChain.teachOrder1(previous, symbol, subAllocator, rescalePolicy)) {
                    result.rescaledContextCount++;
                }
                result.taughtOrder1 = true;
            }
        }
        if (maxOrder >= 2 && previous >= 0 && olderPrevious >= 0) {
            RarPpmdContext order2 = contextChain.ensureOrder2Context(previous, olderPrevious, subAllocator);
            if (decodedContext != order2) {
                if (contextChain.teachOrder2(previous, olderPrevious, symbol, subAllocator, rescalePolicy)) {
                    result.rescaledContextCount++;
                }
                result.taughtOrder2 = true;
            }
        }
        return result;
    }

    @NonNull
    UpdateResult learnAfterOrder0Fallback(int previous, int olderPrevious, int symbol)
            throws IOException {
        return learnAfterOrder0Fallback(previous, olderPrevious, symbol, false);
    }

    @NonNull
    UpdateResult learnAfterOrder0Fallback(int previous,
                                          int olderPrevious,
                                          int symbol,
                                          boolean pendingTextSuccessors)
            throws IOException {
        UpdateResult result = new UpdateResult();
        if (teachRoot(symbol)) result.rescaledContextCount++;
        result.taughtRoot = true;
        result.successorCreated = ensureSuccessorForDecodedSymbol(previous,
                contextChain.rootContext(), symbol, pendingTextSuccessors);
        if (previous >= 0) {
            if (contextChain.teachOrder1(previous, symbol, subAllocator, rescalePolicy)) {
                result.rescaledContextCount++;
            }
            result.taughtOrder1 = true;
        }
        if (maxOrder >= 2 && previous >= 0 && olderPrevious >= 0) {
            if (contextChain.teachOrder2(previous, olderPrevious, symbol, subAllocator, rescalePolicy)) {
                result.rescaledContextCount++;
            }
            result.taughtOrder2 = true;
        }
        return result;
    }

    @NonNull
    UpdateResult learnAfterRootOnlyDecode(int symbol) throws IOException {
        UpdateResult result = new UpdateResult();
        if (teachRoot(symbol)) result.rescaledContextCount++;
        result.taughtRoot = true;
        return result;
    }

    private boolean ensureSuccessorForDecodedSymbol(int previous,
                                                    @NonNull RarPpmdContext decodedContext,
                                                    int symbol,
                                                    boolean pendingTextSuccessors) throws IOException {
        if (decodedContext == contextChain.rootContext()) {
            if (pendingTextSuccessors) return contextChain.markRootStatePendingTextSuccessor(symbol);
            return contextChain.ensureRootStateSuccessorToOrder1Context(symbol, subAllocator);
        }
        if (previous >= 0) {
            RarPpmdContext previousOrder1 = contextChain.existingOrder1Context(previous);
            if (decodedContext == previousOrder1) {
                if (pendingTextSuccessors) {
                    return contextChain.markOrder1StatePendingTextSuccessor(previous, symbol);
                }
                return contextChain.ensureOrder1StateSuccessorToOrder2Context(previous, symbol,
                        subAllocator);
            }
        }
        RarPpmdStateRecord state = decodedContext.requireState(symbol);
        boolean hadSuccessor = state.hasSuccessor();
        contextChain.ensureSuccessorForDecodedState(decodedContext, symbol, subAllocator);
        return !hadSuccessor;
    }

    private boolean teachRoot(int symbol) throws IOException {
        RarPpmdContext root = contextChain.rootContext();
        root.insertOrUpdateState(symbol, 1, RarPpmdStateRecord.NO_SUCCESSOR);
        root.promoteState(symbol);
        boolean rescaled = root.rescaleIfNeeded(rescalePolicy);
        root.ensureStateArrayCapacity(subAllocator);
        return rescaled;
    }

    static final class UpdateResult {
        boolean successorCreated;
        boolean taughtRoot;
        boolean taughtOrder1;
        boolean taughtOrder2;
        int rescaledContextCount;

        int taughtContextCount() {
            int count = 0;
            if (taughtRoot) count++;
            if (taughtOrder1) count++;
            if (taughtOrder2) count++;
            return count;
        }

        @NonNull
        String diagnostic() {
            return "successorCreated=" + successorCreated
                    + "; taughtRoot=" + taughtRoot
                    + "; taughtOrder1=" + taughtOrder1
                    + "; taughtOrder2=" + taughtOrder2
                    + "; taughtContextCount=" + taughtContextCount()
                    + "; rescaledContextCount=" + rescaledContextCount;
        }
    }
}
