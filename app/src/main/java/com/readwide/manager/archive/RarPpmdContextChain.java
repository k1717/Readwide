package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * Minimal context-chain owner for the evolving first-party RAR3/RAR4 PPMd model.
 *
 * <p>This is still a deliberately bounded diagnostic scaffold, not a complete PPMd-I tree. It
 * gives the model a real suffix-like path to exercise before the production decoder is enabled:
 * an order-2 context selected by the two latest decoded symbols, then an order-1 context selected
 * by the latest decoded symbol, then the root/order-0 context. It also keeps an explicit successor
 * registry so decoded states can acquire successor contexts without pretending that the live RAR
 * PPMd statistical model is complete.</p>
 */
final class RarPpmdContextChain {
    static final class TraversalCandidate {
        @NonNull final RarPpmdContext context;
        final int orderDepth;
        @NonNull final String source;

        TraversalCandidate(@NonNull RarPpmdContext context, int orderDepth, @NonNull String source) {
            this.context = context;
            this.orderDepth = orderDepth;
            this.source = source;
        }
    }

    @NonNull private final RarPpmdContext rootContext = new RarPpmdContext();
    @NonNull private final RarPpmdContext[] order1Contexts = new RarPpmdContext[256];
    @NonNull private final RarPpmdContext[] order2Contexts = new RarPpmdContext[256 * 256];
    @NonNull private final RarPpmdSuccessorTable successorTable = new RarPpmdSuccessorTable();
    private int allocatedOrder1Contexts;
    private int allocatedOrder2Contexts;

    RarPpmdContextChain(@NonNull RarPpmdSubAllocator allocator) throws IOException {
        rootContext.attachContextPointer(allocator.allocUnits(1));
        rootContext.allocateStateArray(allocator);
        successorTable.registerRoot(rootContext);
    }

    void initializeRootFullAlphabet(@NonNull RarPpmdSubAllocator allocator) throws IOException {
        if (rootContext.stateCount() == 0) {
            for (int symbol = 0; symbol < 256; symbol++) {
                rootContext.insertOrUpdateState(symbol, 1, RarPpmdStateRecord.NO_SUCCESSOR);
            }
        }
        rootContext.setPrimaryEscapeScale(1);
        rootContext.ensureStateArrayCapacity(allocator);
    }

    @NonNull
    RarPpmdContext rootContext() {
        return rootContext;
    }

    int allocatedOrder1Contexts() {
        return allocatedOrder1Contexts;
    }

    int allocatedOrder2Contexts() {
        return allocatedOrder2Contexts;
    }

    int allocatedSuccessorContexts() {
        return successorTable.allocatedSuccessorContextCount();
    }

    int registeredSuccessorContexts() {
        return successorTable.registeredContextCount();
    }

    @Nullable
    RarPpmdContext successorContextForPointer(int pointer) throws IOException {
        return successorTable.contextForPointer(pointer);
    }

    @Nullable
    RarPpmdContext contextForPointer(int pointer) throws IOException {
        return successorTable.contextForPointer(pointer);
    }

    @NonNull
    RarPpmdContext requireContextForPointer(int pointer) throws IOException {
        return successorTable.requireContextForPointer(pointer);
    }

    @Nullable
    TraversalCandidate existingSuccessorTraversalCandidate(int newestPreviousSymbol,
                                                           int olderPreviousSymbol)
            throws IOException {
        if (newestPreviousSymbol >= 0 && olderPreviousSymbol >= 0) {
            RarPpmdContext order1 = existingOrder1Context(newestPreviousSymbol);
            if (order1 != null) {
                RarPpmdStateRecord order1State = order1.findState(olderPreviousSymbol);
                if (order1State != null && order1State.hasContextSuccessor()) {
                    RarPpmdContext successor = successorTable.requireContextForPointer(
                            order1State.successorPointer());
                    return new TraversalCandidate(successor, RarPpmdSeeTable.ORDER2,
                            "order1-state-successor");
                }
            }
        }
        if (newestPreviousSymbol >= 0) {
            RarPpmdStateRecord rootState = rootContext.findState(newestPreviousSymbol);
            if (rootState != null && rootState.hasContextSuccessor()) {
                RarPpmdContext successor = successorTable.requireContextForPointer(
                        rootState.successorPointer());
                return new TraversalCandidate(successor, RarPpmdSeeTable.ORDER1,
                        "root-state-successor");
            }
        }
        return null;
    }

    @Nullable
    TraversalCandidate existingSuccessorTraversalCandidate(@NonNull RarPpmdSymbolHistory history)
            throws IOException {
        return existingSuccessorTraversalCandidate(history.newest(), history.older());
    }


    @Nullable
    TraversalCandidate existingOrMaterializedSuccessorTraversalCandidate(
            @NonNull RarPpmdSymbolHistory history,
            @NonNull RarPpmdSubAllocator allocator,
            boolean materializePendingTextSuccessors) throws IOException {
        return existingOrMaterializedSuccessorTraversalCandidate(history, allocator,
                materializePendingTextSuccessors, RarPpmdCreateSuccessors.SEED_NONE);
    }

    @Nullable
    TraversalCandidate existingOrMaterializedSuccessorTraversalCandidate(
            @NonNull RarPpmdSymbolHistory history,
            @NonNull RarPpmdSubAllocator allocator,
            boolean materializePendingTextSuccessors,
            boolean seedMaterializedContext) throws IOException {
        return existingOrMaterializedSuccessorTraversalCandidate(history, allocator,
                materializePendingTextSuccessors,
                seedMaterializedContext ? RarPpmdCreateSuccessors.SEED_OWNER_SYMBOL
                        : RarPpmdCreateSuccessors.SEED_NONE);
    }

    @Nullable
    TraversalCandidate existingOrMaterializedSuccessorTraversalCandidate(
            @NonNull RarPpmdSymbolHistory history,
            @NonNull RarPpmdSubAllocator allocator,
            boolean materializePendingTextSuccessors,
            int seedMode) throws IOException {
        TraversalCandidate existing = existingSuccessorTraversalCandidate(history);
        if (existing != null || !materializePendingTextSuccessors) return existing;
        int newest = history.newest();
        int older = history.older();
        if (newest >= 0 && older >= 0) {
            RarPpmdContext order1 = existingOrder1Context(newest);
            if (order1 != null) {
                RarPpmdStateRecord order1State = order1.findState(older);
                if (order1State != null && order1State.hasPendingTextSuccessor()) {
                    RarPpmdContext order2 = RarPpmdCreateSuccessors
                            .materializeOrder1PendingSuccessor(this, allocator, newest,
                                    order1State, history, seedMode);
                    return new TraversalCandidate(order2, RarPpmdSeeTable.ORDER2,
                            materializedSource("order1", seedMode));
                }
            }
        }
        if (newest >= 0) {
            RarPpmdStateRecord rootState = rootContext.findState(newest);
            if (rootState != null && rootState.hasPendingTextSuccessor()) {
                RarPpmdContext order1 = RarPpmdCreateSuccessors
                        .materializeRootPendingSuccessor(this, allocator, rootState,
                                history, seedMode);
                return new TraversalCandidate(order1, RarPpmdSeeTable.ORDER1,
                        materializedSource("root", seedMode));
            }
        }
        return null;
    }


    @NonNull
    private static String materializedSource(@NonNull String owner, int seedMode) {
        String seed;
        switch (seedMode) {
            case RarPpmdCreateSuccessors.SEED_NONE:
                seed = "empty";
                break;
            case RarPpmdCreateSuccessors.SEED_OWNER_SYMBOL:
                seed = "owner-seed";
                break;
            case RarPpmdCreateSuccessors.SEED_PENDING_SYMBOL:
                seed = "pending-seed";
                break;
            case RarPpmdCreateSuccessors.SEED_HISTORY_NEWEST:
                seed = "history-seed";
                break;
            default:
                seed = "seed-" + seedMode;
                break;
        }
        return owner + "-create-successors-materialized/" + seed;
    }

    @Nullable
    RarPpmdContext existingOrder1Context(int previousSymbol) throws IOException {
        validateSymbol(previousSymbol);
        return order1Contexts[previousSymbol & 0xff];
    }

    @Nullable
    RarPpmdContext existingOrder2Context(int newestPreviousSymbol,
                                         int olderPreviousSymbol) throws IOException {
        validateSymbol(newestPreviousSymbol);
        validateSymbol(olderPreviousSymbol);
        return order2Contexts[order2Key(newestPreviousSymbol, olderPreviousSymbol)];
    }

    @NonNull
    RarPpmdContext ensureOrder1Context(int previousSymbol,
                                       @NonNull RarPpmdSubAllocator allocator) throws IOException {
        validateSymbol(previousSymbol);
        int normalized = previousSymbol & 0xff;
        RarPpmdContext context = order1Contexts[normalized];
        if (context != null) return context;
        context = successorTable.allocateSuccessorContext(allocator, rootContext.contextPointer());
        order1Contexts[normalized] = context;
        allocatedOrder1Contexts++;
        linkRootStateToOrder1SuccessorIfPresent(normalized, context);
        return context;
    }

    @NonNull
    RarPpmdContext ensureOrder2Context(int newestPreviousSymbol,
                                       int olderPreviousSymbol,
                                       @NonNull RarPpmdSubAllocator allocator) throws IOException {
        validateSymbol(newestPreviousSymbol);
        validateSymbol(olderPreviousSymbol);
        int key = order2Key(newestPreviousSymbol, olderPreviousSymbol);
        RarPpmdContext context = order2Contexts[key];
        if (context != null) return context;
        RarPpmdContext suffix = ensureOrder1Context(newestPreviousSymbol, allocator);
        context = successorTable.allocateSuccessorContext(allocator, suffix.contextPointer());
        order2Contexts[key] = context;
        allocatedOrder2Contexts++;
        linkOrder1StateToOrder2SuccessorIfPresent(suffix, olderPreviousSymbol, context);
        return context;
    }

    @NonNull
    RarPpmdContext ensureSuccessorForDecodedState(@NonNull RarPpmdContext ownerContext,
                                                  int symbol,
                                                  @NonNull RarPpmdSubAllocator allocator)
            throws IOException {
        validateSymbol(symbol);
        if (!ownerContext.hasContextPointer()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd owner context is not registered for successor creation");
        }
        RarPpmdStateRecord state = ownerContext.requireState(symbol);
        if (state.hasContextSuccessor()) {
            return successorTable.requireContextForPointer(state.successorPointer());
        }
        RarPpmdContext successor = successorTable.allocateSuccessorContext(
                allocator, ownerContext.contextPointer());
        state.setSuccessorPointer(successor.contextPointer());
        return successor;
    }

    boolean ensureRootStateSuccessorToOrder1Context(int decodedSymbol,
                                                    @NonNull RarPpmdSubAllocator allocator)
            throws IOException {
        validateSymbol(decodedSymbol);
        RarPpmdStateRecord rootState = rootContext.requireState(decodedSymbol);
        boolean hadContextSuccessor = rootState.hasContextSuccessor();
        RarPpmdContext order1 = ensureOrder1Context(decodedSymbol, allocator);
        if (!rootState.hasContextSuccessor()) {
            rootState.setSuccessorPointer(order1.contextPointer());
        }
        return !hadContextSuccessor;
    }

    boolean markRootStatePendingTextSuccessor(int decodedSymbol) throws IOException {
        validateSymbol(decodedSymbol);
        RarPpmdStateRecord rootState = rootContext.requireState(decodedSymbol);
        boolean hadSuccessor = rootState.hasSuccessor();
        if (!rootState.hasContextSuccessor()) rootState.setPendingTextSuccessor(decodedSymbol);
        return !hadSuccessor;
    }

    boolean ensureOrder1StateSuccessorToOrder2Context(int previousSymbol,
                                                      int decodedSymbol,
                                                      @NonNull RarPpmdSubAllocator allocator)
            throws IOException {
        validateSymbol(previousSymbol);
        validateSymbol(decodedSymbol);
        RarPpmdContext ownerOrder1 = existingOrder1Context(previousSymbol);
        if (ownerOrder1 == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd order-1 owner is missing for successor link: previous="
                            + previousSymbol + "; decoded=" + decodedSymbol);
        }
        RarPpmdStateRecord ownerState = ownerOrder1.requireState(decodedSymbol);
        boolean hadContextSuccessor = ownerState.hasContextSuccessor();
        RarPpmdContext order2 = ensureOrder2Context(decodedSymbol, previousSymbol, allocator);
        if (!ownerState.hasContextSuccessor()) {
            ownerState.setSuccessorPointer(order2.contextPointer());
        }
        return !hadContextSuccessor;
    }

    boolean markOrder1StatePendingTextSuccessor(int previousSymbol,
                                                int decodedSymbol) throws IOException {
        validateSymbol(previousSymbol);
        validateSymbol(decodedSymbol);
        RarPpmdContext ownerOrder1 = existingOrder1Context(previousSymbol);
        if (ownerOrder1 == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd order-1 owner is missing for pending successor: previous="
                            + previousSymbol + "; decoded=" + decodedSymbol);
        }
        RarPpmdStateRecord ownerState = ownerOrder1.requireState(decodedSymbol);
        boolean hadSuccessor = ownerState.hasSuccessor();
        if (!ownerState.hasContextSuccessor()) ownerState.setPendingTextSuccessor(decodedSymbol);
        return !hadSuccessor;
    }

    boolean teachOrder1(int previousSymbol,
                        int symbol,
                        @NonNull RarPpmdSubAllocator allocator,
                        @NonNull RarPpmdRescalePolicy rescalePolicy) throws IOException {
        RarPpmdContext context = ensureOrder1Context(previousSymbol, allocator);
        context.insertOrUpdateState(symbol, 1, RarPpmdStateRecord.NO_SUCCESSOR);
        context.promoteState(symbol);
        boolean rescaled = context.rescaleIfNeeded(rescalePolicy);
        context.ensureStateArrayCapacity(allocator);
        return rescaled;
    }

    boolean teachOrder2(int newestPreviousSymbol,
                        int olderPreviousSymbol,
                        int symbol,
                        @NonNull RarPpmdSubAllocator allocator,
                        @NonNull RarPpmdRescalePolicy rescalePolicy) throws IOException {
        RarPpmdContext context = ensureOrder2Context(newestPreviousSymbol, olderPreviousSymbol, allocator);
        context.insertOrUpdateState(symbol, 1, RarPpmdStateRecord.NO_SUCCESSOR);
        context.promoteState(symbol);
        boolean rescaled = context.rescaleIfNeeded(rescalePolicy);
        context.ensureStateArrayCapacity(allocator);
        return rescaled;
    }

    void teachOrder1(int previousSymbol,
                     int symbol,
                     @NonNull RarPpmdSubAllocator allocator) throws IOException {
        teachOrder1(previousSymbol, symbol, allocator, RarPpmdRescalePolicy.diagnosticDefault());
    }

    void teachOrder2(int newestPreviousSymbol,
                     int olderPreviousSymbol,
                     int symbol,
                     @NonNull RarPpmdSubAllocator allocator) throws IOException {
        teachOrder2(newestPreviousSymbol, olderPreviousSymbol, symbol, allocator,
                RarPpmdRescalePolicy.diagnosticDefault());
    }

    private void linkRootStateToOrder1SuccessorIfPresent(int previousSymbol,
                                                         @NonNull RarPpmdContext order1)
            throws IOException {
        RarPpmdStateRecord rootState = rootContext.findState(previousSymbol);
        if (rootState != null && !rootState.hasContextSuccessor()) {
            rootState.setSuccessorPointer(order1.contextPointer());
        }
    }

    private void linkOrder1StateToOrder2SuccessorIfPresent(@NonNull RarPpmdContext order1,
                                                           int olderPreviousSymbol,
                                                           @NonNull RarPpmdContext order2)
            throws IOException {
        RarPpmdStateRecord order1State = order1.findState(olderPreviousSymbol);
        if (order1State != null && !order1State.hasContextSuccessor()) {
            order1State.setSuccessorPointer(order2.contextPointer());
        }
    }

    private static int order2Key(int newestPreviousSymbol, int olderPreviousSymbol) {
        return ((olderPreviousSymbol & 0xff) << 8) | (newestPreviousSymbol & 0xff);
    }

    private static void validateSymbol(int symbol) throws IOException {
        if (symbol < 0 || symbol > 255) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context-chain symbol is out of byte range: " + symbol);
        }
    }
}
