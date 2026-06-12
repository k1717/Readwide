package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * Diagnostic cursor for the evolving RAR3/RAR4 PPMd model.
 *
 * <p>Real RAR PPMd does not simply select a context from the last one or two decoded bytes. The
 * decoder carries a current min/max context, the state that was found during the previous decode,
 * and an order-fall counter. This class captures that cursor state without enabling production
 * PPMd extraction. It gives the live fixture probe a successor-first path that is closer to the
 * UnRAR-shaped DecodeChar/UpdateModel flow, while still keeping the unsupported production
 * boundary in place.</p>
 */
final class RarPpmdContextCursor {
    @Nullable private RarPpmdContext minContext;
    @Nullable private RarPpmdContext maxContext;
    @Nullable private RarPpmdContext foundStateOwner;
    private int foundSymbol = -1;
    private int foundSuccessorPointer = RarPpmdStateRecord.NO_SUCCESSOR;
    private int orderFall;
    private int decodedSymbolCount;
    private int successorHitCount;
    private int suffixFallbackCount;
    @NonNull private String lastTransition = "not-initialized";

    void resetToRoot(@NonNull RarPpmdContext rootContext, int maxOrder) {
        minContext = rootContext;
        maxContext = rootContext;
        foundStateOwner = null;
        foundSymbol = -1;
        foundSuccessorPointer = RarPpmdStateRecord.NO_SUCCESSOR;
        orderFall = Math.max(0, maxOrder);
        decodedSymbolCount = 0;
        successorHitCount = 0;
        suffixFallbackCount = 0;
        lastTransition = "reset-root; orderFall=" + orderFall;
    }

    boolean initialized() {
        return minContext != null && maxContext != null;
    }

    @Nullable
    RarPpmdContextChain.TraversalCandidate successorCandidate(
            @NonNull RarPpmdContextChain contextChain) throws IOException {
        return successorCandidate(contextChain, false);
    }

    @Nullable
    RarPpmdContextChain.TraversalCandidate successorCandidate(
            @NonNull RarPpmdContextChain contextChain,
            boolean requireOrderFallExhausted) throws IOException {
        if (!initialized() || foundSuccessorPointer == RarPpmdStateRecord.NO_SUCCESSOR) {
            return null;
        }
        if (requireOrderFallExhausted && orderFall > 0) {
            lastTransition = "successor-gated; orderFall=" + orderFall
                    + "; foundSymbol=" + foundSymbol
                    + "; successorPointer=" + foundSuccessorPointer;
            return null;
        }
        RarPpmdContext successor = contextChain.requireContextForPointer(foundSuccessorPointer);
        int depth = Math.max(RarPpmdSeeTable.ORDER1, Math.min(RarPpmdSeeTable.ORDER2,
                (decodedSymbolCount <= 1) ? RarPpmdSeeTable.ORDER1 : RarPpmdSeeTable.ORDER2));
        successorHitCount++;
        lastTransition = "successor-candidate; depth=" + depth
                + "; foundSymbol=" + foundSymbol
                + "; successorPointer=" + foundSuccessorPointer;
        return new RarPpmdContextChain.TraversalCandidate(successor, depth,
                "cursor-found-state-successor");
    }

    @Nullable
    RarPpmdContext currentMaxContext() {
        return maxContext;
    }

    void noteSuffixFallback(@NonNull RarPpmdContext escapedContext,
                            @NonNull RarPpmdContextChain contextChain) throws IOException {
        suffixFallbackCount++;
        RarPpmdContext suffix = contextChain.contextForPointer(escapedContext.suffixPointer());
        if (suffix != null) {
            minContext = suffix;
            maxContext = suffix;
            lastTransition = "suffix-fallback; from=" + escapedContext.contextPointer()
                    + "; to=" + suffix.contextPointer();
        } else {
            lastTransition = "suffix-fallback; from=" + escapedContext.contextPointer()
                    + "; to=none";
        }
        if (orderFall < Integer.MAX_VALUE) orderFall++;
    }

    void noteDecoded(@NonNull RarPpmdContext ownerContext, int symbol) throws IOException {
        RarPpmdStateRecord state = ownerContext.requireState(symbol);
        foundStateOwner = ownerContext;
        foundSymbol = symbol & 0xff;
        foundSuccessorPointer = state.hasContextSuccessor()
                ? state.successorPointer() : RarPpmdStateRecord.NO_SUCCESSOR;
        minContext = ownerContext;
        maxContext = ownerContext;
        decodedSymbolCount++;
        if (orderFall > 0) orderFall--;
        lastTransition = "decoded; owner=" + ownerContext.contextPointer()
                + "; symbol=" + foundSymbol
                + "; successor=" + foundSuccessorPointer
                + "; orderFall=" + orderFall;
    }

    int orderFallForTest() {
        return orderFall;
    }

    int successorHitCountForTest() {
        return successorHitCount;
    }

    int suffixFallbackCountForTest() {
        return suffixFallbackCount;
    }

    @NonNull
    String diagnostic() {
        return "initialized=" + initialized()
                + "; min=" + pointerOf(minContext)
                + "; max=" + pointerOf(maxContext)
                + "; foundOwner=" + pointerOf(foundStateOwner)
                + "; foundSymbol=" + foundSymbol
                + "; foundSuccessor=" + foundSuccessorPointer
                + "; orderFall=" + orderFall
                + "; decoded=" + decodedSymbolCount
                + "; successorHits=" + successorHitCount
                + "; suffixFallbacks=" + suffixFallbackCount
                + "; last=" + lastTransition;
    }

    private static int pointerOf(@Nullable RarPpmdContext context) {
        return context != null ? context.contextPointer() : RarPpmdContext.NO_CONTEXT;
    }
}
