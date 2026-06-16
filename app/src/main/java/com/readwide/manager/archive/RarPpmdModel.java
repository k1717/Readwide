package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * First-party state holder for the RAR3/RAR4 PPMd model work.
 *
 * <p>This class is intentionally still smaller than the real RAR PPMd-I model. It owns the
 * model lifetime objects that must survive solid PPMd continuation blocks: root/context metadata,
 * the suballocator, the order-0 bootstrap primitive, SEE/escape helpers, and recent-symbol
 * history. Full context traversal, suffix fallback, masked decoding, and RAR's exact update rules
 * are implemented in later passes, not hidden behind a false success path here.</p>
 */
final class RarPpmdModel {
    static final int DIAGNOSTIC_ESCAPE = -1;

    private static final int MIN_ALLOCATOR_KIB = 64;
    private final int maxOrder;
    private final int memoryMb;
    private final int escapeChar;
    @NonNull private final RarPpmdSubAllocator subAllocator;
    @NonNull private final Rar3PpmdOrder0Model order0BootstrapModel;
    @NonNull private final RarPpmdContextChain contextChain;
    @NonNull private final RarPpmdEscapeMask escapeMask;
    @NonNull private final RarPpmdSeeTable seeTable;
    @NonNull private final RarPpmdModelUpdater modelUpdater;
    @NonNull private final RarPpmdSymbolHistory symbolHistory;
    @NonNull private final RarPpmdContextCursor contextCursor;
    private int continuationCount;
    private int diagnosticContextEscapeCount;
    private int diagnosticOrder0FallbackCount;
    private int diagnosticSuccessorCreationCount;
    private int diagnosticSuccessorTraversalCount;
    private int diagnosticSuffixLoopCount;
    private int diagnosticSuffixLoopEscapeCount;
    private int diagnosticContextUpdateCount;
    private int diagnosticRescaleCount;
    private boolean diagnosticPrimaryRootInitialized;
    @NonNull private String lastDecodeTrace = "not-started";

    RarPpmdModel(int maxOrderHint, int memoryMbHint, int escapeChar) throws IOException {
        if (escapeChar < 0 || escapeChar > 255) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd model escape character is invalid: " + escapeChar);
        }
        maxOrder = Math.max(0, maxOrderHint);
        memoryMb = Math.max(1, memoryMbHint);
        this.escapeChar = escapeChar;
        subAllocator = new RarPpmdSubAllocator(Math.max(MIN_ALLOCATOR_KIB, memoryMb * 1024));
        order0BootstrapModel = new Rar3PpmdOrder0Model();
        contextChain = new RarPpmdContextChain(subAllocator);
        escapeMask = new RarPpmdEscapeMask();
        seeTable = new RarPpmdSeeTable();
        modelUpdater = new RarPpmdModelUpdater(maxOrder, subAllocator, contextChain);
        symbolHistory = new RarPpmdSymbolHistory();
        contextCursor = new RarPpmdContextCursor();
    }

    void applyDiagnosticOptionsForTest(@NonNull RarPpmdDiagnosticOptions options,
                                       boolean resetBlock) throws IOException {
        if (options.rarPrimaryRootEnabled() && resetBlock && !diagnosticPrimaryRootInitialized) {
            contextChain.initializeRootFullAlphabet(subAllocator);
            contextCursor.resetToRoot(contextChain.rootContext(), maxOrder);
            diagnosticPrimaryRootInitialized = true;
        }
    }

    int decodeDiagnosticOrder0Symbol(@NonNull RarPpmdRangeDecoder rangeDecoder) throws IOException {
        int symbol = order0BootstrapModel.decodeSymbol(rangeDecoder);
        applyUpdate(modelUpdater.learnAfterRootOnlyDecode(symbol));
        rememberSymbol(symbol);
        return symbol;
    }

    int decodeDiagnosticContextOrOrder0Symbol(@NonNull RarPpmdRangeDecoder rangeDecoder)
            throws IOException {
        return decodeDiagnosticContextOrOrder0Symbol(rangeDecoder,
                RarPpmdDiagnosticOptions.standard());
    }

    int decodeDiagnosticContextOrOrder0Symbol(@NonNull RarPpmdRangeDecoder rangeDecoder,
                                             @NonNull RarPpmdDiagnosticOptions options)
            throws IOException {
        int previous = symbolHistory.newest();
        int olderPrevious = symbolHistory.older();
        escapeMask.clear();
        lastDecodeTrace = "start; variant={" + options.diagnostic() + "}; history={"
                + symbolHistory.diagnostic() + "}";

        if (options.isRarPrimaryRootOnly()) {
            RarPpmdContext root = contextChain.rootContext();
            if (root.primaryEscapeScale() <= 0 || root.scale() <= 0) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3/RAR4 PPMd primary-root-only diagnostic was requested before root initialization");
            }
            setPrimaryContextTrace("root-primary-only", root, options);
            int symbol = root.decodePrimarySymbolOrEscape(rangeDecoder, escapeMask, options.primaryUpdatePolicy());
            if (symbol == DIAGNOSTIC_ESCAPE) {
                lastDecodeTrace += "; result=escape";
                diagnosticContextEscapeCount++;
                if (isTerminalRootEscape(root, options)) throw terminalRootEscapeBoundary(root, options);
                if (options.contextCursorEnabled()) contextCursor.noteSuffixFallback(root, contextChain);
            } else {
                lastDecodeTrace += "; result=symbol; symbol=" + symbol;
                return finishContextSymbol(previous, olderPrevious, symbol, root, options);
            }
        }

        if (!options.contextFallbackEnabled()) {
            setOrder0Trace(options);
            int symbol = order0BootstrapModel.decodeSymbol(rangeDecoder, escapeMask);
            lastDecodeTrace += "; result=symbol; symbol=" + symbol;
            diagnosticOrder0FallbackCount++;
            return finishOrder0Symbol(previous, olderPrevious, symbol, options);
        }

        if (options.suffixLoopEnabled()) {
            RarPpmdContextChain.TraversalCandidate loopStart = suffixLoopStartCandidate(options,
                    previous, olderPrevious);
            if (loopStart != null) {
                Integer loopSymbol = decodeDiagnosticSuffixLoop(rangeDecoder, options, previous,
                        olderPrevious, loopStart);
                if (loopSymbol != null) return loopSymbol;
                setOrder0Trace(options);
                int symbol = order0BootstrapModel.decodeSymbol(rangeDecoder, escapeMask);
                lastDecodeTrace += "; result=symbol; symbol=" + symbol
                        + "; suffixLoopOrder0Fallback=true";
                diagnosticOrder0FallbackCount++;
                return finishOrder0Symbol(previous, olderPrevious, symbol, options);
            }
        }

        RarPpmdContextChain.TraversalCandidate cursorCandidate = options.contextCursorEnabled()
                ? contextCursor.successorCandidate(contextChain, options.cursorOrderFallGateEnabled()) : null;
        if (cursorCandidate != null && cursorCandidate.context.unmaskedScale(escapeMask) > 0) {
            RarPpmdSeeContext seeContext = selectSeeContext(options, cursorCandidate.orderDepth,
                    cursorCandidate.context);
            setContextTrace("cursor-successor", cursorCandidate.orderDepth,
                    cursorCandidate.context, seeContext, options);
            int symbol = cursorCandidate.context.decodeSymbolOrEscape(rangeDecoder, escapeMask,
                    seeContext);
            if (symbol != DIAGNOSTIC_ESCAPE) {
                lastDecodeTrace += "; result=symbol; symbol=" + symbol;
                diagnosticSuccessorTraversalCount++;
                return finishContextSymbol(previous, olderPrevious, symbol, cursorCandidate.context, options);
            }
            lastDecodeTrace += "; result=escape";
            diagnosticContextEscapeCount++;
            contextCursor.noteSuffixFallback(cursorCandidate.context, contextChain);
        }

        RarPpmdContextChain.TraversalCandidate successorCandidate =
                contextChain.existingOrMaterializedSuccessorTraversalCandidate(symbolHistory,
                        subAllocator, options.pendingTextSuccessorsEnabled(),
                        options.createSuccessorSeedMode());
        if (successorCandidate != null && successorCandidate.context.unmaskedScale(escapeMask) > 0) {
            RarPpmdSeeContext seeContext = selectSeeContext(options, successorCandidate.orderDepth,
                    successorCandidate.context);
            setContextTrace("successor", successorCandidate.orderDepth,
                    successorCandidate.context, seeContext, options);
            int symbol = successorCandidate.context.decodeSymbolOrEscape(rangeDecoder, escapeMask,
                    seeContext);
            if (symbol != DIAGNOSTIC_ESCAPE) {
                lastDecodeTrace += "; result=symbol; symbol=" + symbol;
                diagnosticSuccessorTraversalCount++;
                return finishContextSymbol(previous, olderPrevious, symbol, successorCandidate.context, options);
            }
            lastDecodeTrace += "; result=escape";
            diagnosticContextEscapeCount++;
            if (options.contextCursorEnabled()) contextCursor.noteSuffixFallback(successorCandidate.context, contextChain);
        }

        if (maxOrder >= 2 && previous >= 0 && olderPrevious >= 0) {
            RarPpmdContext order2 = contextChain.existingOrder2Context(previous, olderPrevious);
            if (order2 != null && order2 != (successorCandidate != null ? successorCandidate.context : null)
                    && order2.unmaskedScale(escapeMask) > 0) {
                RarPpmdSeeContext seeContext = selectSeeContext(options, RarPpmdSeeTable.ORDER2,
                        order2);
                setContextTrace("order2", RarPpmdSeeTable.ORDER2, order2, seeContext, options);
                int symbol = order2.decodeSymbolOrEscape(rangeDecoder, escapeMask, seeContext);
                if (symbol != DIAGNOSTIC_ESCAPE) {
                    lastDecodeTrace += "; result=symbol; symbol=" + symbol;
                return finishContextSymbol(previous, olderPrevious, symbol, order2, options);
                }
                lastDecodeTrace += "; result=escape";
                diagnosticContextEscapeCount++;
                if (options.contextCursorEnabled()) contextCursor.noteSuffixFallback(order2, contextChain);
            }
        }

        if (previous >= 0) {
            RarPpmdContext order1 = contextChain.existingOrder1Context(previous);
            if (order1 != null && order1.unmaskedScale(escapeMask) > 0) {
                RarPpmdSeeContext seeContext = selectSeeContext(options, RarPpmdSeeTable.ORDER1,
                        order1);
                setContextTrace("order1", RarPpmdSeeTable.ORDER1, order1, seeContext, options);
                int symbol = order1.decodeSymbolOrEscape(rangeDecoder, escapeMask, seeContext);
                if (symbol != DIAGNOSTIC_ESCAPE) {
                    lastDecodeTrace += "; result=symbol; symbol=" + symbol;
                return finishContextSymbol(previous, olderPrevious, symbol, order1, options);
                }
                lastDecodeTrace += "; result=escape";
                diagnosticContextEscapeCount++;
                if (options.contextCursorEnabled()) contextCursor.noteSuffixFallback(order1, contextChain);
            }
        }

        RarPpmdContext root = contextChain.rootContext();
        if (options.rootContextEnabled() && options.rarPrimaryRootEnabled()
                && escapeMask.maskedCount() == 0 && root.primaryEscapeScale() > 0
                && root.scale() > 0) {
            setPrimaryContextTrace("root-primary", root, options);
            int symbol = root.decodePrimarySymbolOrEscape(rangeDecoder, escapeMask, options.primaryUpdatePolicy());
            if (symbol != DIAGNOSTIC_ESCAPE) {
                lastDecodeTrace += "; result=symbol; symbol=" + symbol;
                return finishContextSymbol(previous, olderPrevious, symbol, root, options);
            }
            lastDecodeTrace += "; result=escape";
            diagnosticContextEscapeCount++;
            if (isTerminalRootEscape(root, options)) throw terminalRootEscapeBoundary(root, options);
            if (options.contextCursorEnabled()) contextCursor.noteSuffixFallback(root, contextChain);
        }

        if (options.rootContextEnabled() && root.unmaskedScale(escapeMask) > 0) {
            RarPpmdSeeContext seeContext = selectSeeContext(options, RarPpmdSeeTable.ROOT_ORDER,
                    root);
            setContextTrace("root", RarPpmdSeeTable.ROOT_ORDER, root, seeContext, options);
            int symbol = root.decodeSymbolOrEscape(rangeDecoder, escapeMask, seeContext);
            if (symbol != DIAGNOSTIC_ESCAPE) {
                lastDecodeTrace += "; result=symbol; symbol=" + symbol;
                return finishContextSymbol(previous, olderPrevious, symbol, root, options);
            }
            lastDecodeTrace += "; result=escape";
            diagnosticContextEscapeCount++;
            if (isTerminalRootEscape(root, options)) throw terminalRootEscapeBoundary(root, options);
            if (options.contextCursorEnabled()) contextCursor.noteSuffixFallback(root, contextChain);
        }

        setOrder0Trace(options);
        int symbol = order0BootstrapModel.decodeSymbol(rangeDecoder, escapeMask);
        lastDecodeTrace += "; result=symbol; symbol=" + symbol;
        diagnosticOrder0FallbackCount++;
        return finishOrder0Symbol(previous, olderPrevious, symbol, options);
    }

    @Nullable
    private RarPpmdContextChain.TraversalCandidate suffixLoopStartCandidate(
            @NonNull RarPpmdDiagnosticOptions options,
            int previous,
            int olderPrevious) throws IOException {
        if (options.contextCursorEnabled()) {
            RarPpmdContextChain.TraversalCandidate cursorCandidate =
                    contextCursor.successorCandidate(contextChain, options.cursorOrderFallGateEnabled());
            if (cursorCandidate != null) return cursorCandidate;
        }
        RarPpmdContextChain.TraversalCandidate successorCandidate =
                contextChain.existingOrMaterializedSuccessorTraversalCandidate(symbolHistory,
                        subAllocator, options.pendingTextSuccessorsEnabled(),
                        options.createSuccessorSeedMode());
        if (successorCandidate != null) return successorCandidate;
        if (maxOrder >= 2 && previous >= 0 && olderPrevious >= 0) {
            RarPpmdContext order2 = contextChain.existingOrder2Context(previous, olderPrevious);
            if (order2 != null) {
                return new RarPpmdContextChain.TraversalCandidate(order2,
                        RarPpmdSeeTable.ORDER2, "history-order2");
            }
        }
        if (previous >= 0) {
            RarPpmdContext order1 = contextChain.existingOrder1Context(previous);
            if (order1 != null) {
                return new RarPpmdContextChain.TraversalCandidate(order1,
                        RarPpmdSeeTable.ORDER1, "history-order1");
            }
        }
        return null;
    }

    @Nullable
    private Integer decodeDiagnosticSuffixLoop(@NonNull RarPpmdRangeDecoder rangeDecoder,
                                               @NonNull RarPpmdDiagnosticOptions options,
                                               int previous,
                                               int olderPrevious,
                                               @NonNull RarPpmdContextChain.TraversalCandidate start)
            throws IOException {
        diagnosticSuffixLoopCount++;
        RarPpmdContext context = start.context;
        int orderDepth = start.orderDepth;
        int visited = 0;
        StringBuilder loopTrace = new StringBuilder();
        while (context != null && visited <= maxOrder + 4) {
            visited++;
            int unmasked = context.unmaskedScale(escapeMask);
            loopTrace.append(" -> ").append(context.contextPointer())
                    .append("[d=").append(orderDepth)
                    .append(", states=").append(context.stateCount())
                    .append(", unmasked=").append(unmasked)
                    .append(", masked=").append(escapeMask.maskedCount())
                    .append(']');
            if (unmasked > 0) {
                if (context == contextChain.rootContext() && options.rarPrimaryRootEnabled()
                        && escapeMask.maskedCount() == 0 && context.primaryEscapeScale() > 0) {
                    setPrimaryContextTrace("suffix-loop-root-primary/" + start.source, context,
                            options);
                    int symbol = context.decodePrimarySymbolOrEscape(rangeDecoder, escapeMask,
                            options.primaryUpdatePolicy());
                    if (symbol != DIAGNOSTIC_ESCAPE) {
                        lastDecodeTrace += "; result=symbol; symbol=" + symbol
                                + "; suffixLoopTrace={" + loopTrace + "}";
                        diagnosticSuccessorTraversalCount++;
                        return finishContextSymbol(previous, olderPrevious, symbol, context, options);
                    }
                    diagnosticContextEscapeCount++;
                    diagnosticSuffixLoopEscapeCount++;
                    lastDecodeTrace += "; result=escape";
                    if (isTerminalRootEscape(context, options)) throw terminalRootEscapeBoundary(context, options);
                } else {
                    RarPpmdSeeContext seeContext = selectSeeContext(options, orderDepth, context);
                    setContextTrace("suffix-loop/" + start.source, orderDepth, context, seeContext,
                            options);
                    int symbol = context.decodeSymbolOrEscape(rangeDecoder, escapeMask, seeContext);
                    if (symbol != DIAGNOSTIC_ESCAPE) {
                        lastDecodeTrace += "; result=symbol; symbol=" + symbol
                                + "; suffixLoopTrace={" + loopTrace + "}";
                        diagnosticSuccessorTraversalCount++;
                        return finishContextSymbol(previous, olderPrevious, symbol, context, options);
                    }
                    diagnosticContextEscapeCount++;
                    diagnosticSuffixLoopEscapeCount++;
                    lastDecodeTrace += "; result=escape";
                    if (isTerminalRootEscape(context, options)) throw terminalRootEscapeBoundary(context, options);
                }
            }
            if (options.contextCursorEnabled()) contextCursor.noteSuffixFallback(context, contextChain);
            RarPpmdContext suffix = contextChain.contextForPointer(context.suffixPointer());
            if (suffix == null || suffix == context) break;
            context = suffix;
            if (orderDepth > RarPpmdSeeTable.ROOT_ORDER) orderDepth--;
        }
        lastDecodeTrace = "stage=suffix-loop-order0-fallback"
                + "; variant={" + options.diagnostic() + "}"
                + "; start=" + start.source
                + "; visited=" + visited
                + "; masked=" + escapeMask.maskedCount()
                + "; trace={" + loopTrace + "}"
                + "; history={" + symbolHistory.diagnostic() + "}";
        return null;
    }

    private boolean isTerminalRootEscape(@NonNull RarPpmdContext context,
                                         @NonNull RarPpmdDiagnosticOptions options) {
        return options.terminalRootEscapeEnabled()
                && context == contextChain.rootContext()
                && context.stateCount() >= 256
                && escapeMask.maskedCount() >= 256;
    }

    @NonNull
    private IOException terminalRootEscapeBoundary(@NonNull RarPpmdContext context,
                                                   @NonNull RarPpmdDiagnosticOptions options) {
        lastDecodeTrace += "; terminalRootEscape=true"
                + "; rootStates=" + context.stateCount()
                + "; masked=" + escapeMask.maskedCount();
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 PPMd diagnostic reached terminal root escape: rootStates="
                        + context.stateCount() + "; masked=" + escapeMask.maskedCount()
                        + "; variant=" + options.name());
    }

    private int finishContextSymbol(int previous,
                                    int olderPrevious,
                                    int symbol,
                                    @NonNull RarPpmdContext decodedContext,
                                    @NonNull RarPpmdDiagnosticOptions options)
            throws IOException {
        applyUpdate(modelUpdater.learnAfterContextDecode(previous, olderPrevious, symbol,
                decodedContext, options.pendingTextSuccessorsEnabled()));
        if (options.contextCursorEnabled()) contextCursor.noteDecoded(decodedContext, symbol);
        rememberSymbol(symbol);
        return symbol;
    }

    private int finishOrder0Symbol(int previous,
                                   int olderPrevious,
                                   int symbol,
                                   @NonNull RarPpmdDiagnosticOptions options)
            throws IOException {
        applyUpdate(modelUpdater.learnAfterOrder0Fallback(previous, olderPrevious, symbol,
                options.pendingTextSuccessorsEnabled()));
        if (options.contextCursorEnabled()) contextCursor.noteDecoded(contextChain.rootContext(), symbol);
        rememberSymbol(symbol);
        return symbol;
    }

    void markSolidContinuation() {
        continuationCount++;
    }

    int maxOrder() {
        return maxOrder;
    }

    int memoryMb() {
        return memoryMb;
    }

    int escapeChar() {
        return escapeChar;
    }

    int previousSymbolCount() {
        return symbolHistory.count();
    }

    int latestSymbolForTest() {
        return symbolHistoryForTest(0);
    }

    int symbolHistoryForTest(int historyIndex) {
        return symbolHistory.symbolAt(historyIndex);
    }

    @NonNull
    int[] symbolHistorySnapshotForTest() {
        return symbolHistory.snapshotForTest();
    }

    int continuationCountForTest() {
        return continuationCount;
    }

    int diagnosticContextEscapeCountForTest() {
        return diagnosticContextEscapeCount;
    }

    int diagnosticOrder0FallbackCountForTest() {
        return diagnosticOrder0FallbackCount;
    }

    int diagnosticSuccessorCreationCountForTest() {
        return diagnosticSuccessorCreationCount;
    }

    int diagnosticSuccessorTraversalCountForTest() {
        return diagnosticSuccessorTraversalCount;
    }

    int diagnosticSuffixLoopCountForTest() {
        return diagnosticSuffixLoopCount;
    }

    int diagnosticSuffixLoopEscapeCountForTest() {
        return diagnosticSuffixLoopEscapeCount;
    }

    int diagnosticContextUpdateCountForTest() {
        return diagnosticContextUpdateCount;
    }

    int diagnosticRescaleCountForTest() {
        return diagnosticRescaleCount;
    }

    @NonNull
    String lastDecodeTraceForTest() {
        return lastDecodeTrace;
    }

    @NonNull
    String contextCursorDiagnosticForTest() {
        return contextCursor.diagnostic();
    }

    @NonNull
    RarPpmdSubAllocator subAllocatorForTest() {
        return subAllocator;
    }

    @NonNull
    Rar3PpmdOrder0Model order0BootstrapModelForTest() {
        return order0BootstrapModel;
    }

    @NonNull
    RarPpmdContext rootContextForTest() {
        return contextChain.rootContext();
    }


    @Nullable
    RarPpmdContext order1ContextForTest(int previousSymbol) throws IOException {
        return contextChain.existingOrder1Context(previousSymbol);
    }

    @NonNull
    RarPpmdContext ensureOrder1ContextForTest(int previousSymbol) throws IOException {
        return contextChain.ensureOrder1Context(previousSymbol, subAllocator);
    }

    @Nullable
    RarPpmdContext order2ContextForTest(int newestPreviousSymbol, int olderPreviousSymbol) throws IOException {
        return contextChain.existingOrder2Context(newestPreviousSymbol, olderPreviousSymbol);
    }

    @NonNull
    RarPpmdContext ensureOrder2ContextForTest(int newestPreviousSymbol, int olderPreviousSymbol) throws IOException {
        return contextChain.ensureOrder2Context(newestPreviousSymbol, olderPreviousSymbol, subAllocator);
    }

    int allocatedOrder1ContextCountForTest() {
        return contextChain.allocatedOrder1Contexts();
    }

    int allocatedOrder2ContextCountForTest() {
        return contextChain.allocatedOrder2Contexts();
    }

    int allocatedSuccessorContextCountForTest() {
        return contextChain.allocatedSuccessorContexts();
    }

    int registeredSuccessorContextCountForTest() {
        return contextChain.registeredSuccessorContexts();
    }

    @Nullable
    RarPpmdContext successorContextForTest(int pointer) throws IOException {
        return contextChain.successorContextForPointer(pointer);
    }

    @Nullable
    RarPpmdContext successorTraversalCandidateForTest(int newestPreviousSymbol,
                                                       int olderPreviousSymbol)
            throws IOException {
        RarPpmdContextChain.TraversalCandidate candidate =
                contextChain.existingSuccessorTraversalCandidate(newestPreviousSymbol,
                        olderPreviousSymbol);
        return candidate != null ? candidate.context : null;
    }

    @Nullable
    RarPpmdContext materializedSuccessorTraversalCandidateForTest() throws IOException {
        return materializedSuccessorTraversalCandidateForTest(false);
    }

    @Nullable
    RarPpmdContext materializedSuccessorTraversalCandidateForTest(boolean seedContext)
            throws IOException {
        RarPpmdContextChain.TraversalCandidate candidate =
                contextChain.existingOrMaterializedSuccessorTraversalCandidate(symbolHistory,
                        subAllocator, true, seedContext);
        return candidate != null ? candidate.context : null;
    }

    @NonNull
    RarPpmdContext ensureSuccessorForStateForTest(@NonNull RarPpmdContext ownerContext,
                                                  int symbol) throws IOException {
        return contextChain.ensureSuccessorForDecodedState(ownerContext, symbol, subAllocator);
    }

    void seedPreviousSymbolForTest(int symbol) throws IOException {
        rememberSymbol(symbol);
    }

    @NonNull
    RarPpmdEscapeMask escapeMaskForTest() {
        return escapeMask;
    }

    @NonNull
    RarPpmdSeeContext seeContextForTest() {
        return seeTable.contextForTest(RarPpmdSeeTable.ROOT_ORDER, 0, 0);
    }

    @NonNull
    RarPpmdSeeTable seeTableForTest() {
        return seeTable;
    }

    @NonNull
    String diagnostic() {
        return "maxOrder=" + maxOrder
                + "; memoryMb=" + memoryMb
                + "; escapeChar=" + escapeChar
                + "; rootStates=" + contextChain.rootContext().stateCount()
                + "; rootPrimaryEscapeScale=" + contextChain.rootContext().primaryEscapeScale()
                + "; diagnosticPrimaryRootInitialized=" + diagnosticPrimaryRootInitialized
                + "; order1Contexts=" + contextChain.allocatedOrder1Contexts()
                + "; order2Contexts=" + contextChain.allocatedOrder2Contexts()
                + "; successorContexts=" + contextChain.allocatedSuccessorContexts()
                + "; registeredContexts=" + contextChain.registeredSuccessorContexts()
                + "; allocatorUsedBytes=" + subAllocator.usedBytes()
                + "; history={" + symbolHistory.diagnostic() + "}"
                + "; continuationCount=" + continuationCount
                + "; diagnosticContextEscapes=" + diagnosticContextEscapeCount
                + "; diagnosticOrder0Fallbacks=" + diagnosticOrder0FallbackCount
                + "; diagnosticSuccessorCreations=" + diagnosticSuccessorCreationCount
                + "; diagnosticSuccessorTraversals=" + diagnosticSuccessorTraversalCount
                + "; diagnosticSuffixLoops=" + diagnosticSuffixLoopCount
                + "; diagnosticSuffixLoopEscapes=" + diagnosticSuffixLoopEscapeCount
                + "; diagnosticContextUpdates=" + diagnosticContextUpdateCount
                + "; diagnosticRescales=" + diagnosticRescaleCount
                + "; rescalePolicy={" + RarPpmdRescalePolicy.diagnosticDefault().diagnostic() + "}"
                + "; seeTable={" + seeTable.diagnostic() + "}"
                + "; contextCursor={" + contextCursor.diagnostic() + "}";
    }

    private void applyUpdate(@NonNull RarPpmdModelUpdater.UpdateResult result) {
        if (result.successorCreated) diagnosticSuccessorCreationCount++;
        diagnosticContextUpdateCount += result.taughtContextCount();
        diagnosticRescaleCount += result.rescaledContextCount;
    }

    private void setContextTrace(@NonNull String stage,
                                 int orderDepth,
                                 @NonNull RarPpmdContext context,
                                 @NonNull RarPpmdSeeContext seeContext,
                                 @NonNull RarPpmdDiagnosticOptions options) throws IOException {
        int symbolScale = context.unmaskedScale(escapeMask);
        int escapeScale = seeContext.mean();
        int totalScale = safeTraceTotalScale(symbolScale, escapeScale);
        lastDecodeTrace = "stage=" + stage
                + "; variant={" + options.diagnostic() + "}"
                + "; orderDepth=" + orderDepth
                + "; stateCount=" + context.stateCount()
                + "; symbolScale=" + symbolScale
                + "; escapeScale=" + escapeScale
                + "; totalScale=" + totalScale
                + "; masked=" + escapeMask.maskedCount()
                + "; see={summary=" + seeContext.summary()
                + "; shift=" + seeContext.shift()
                + "; count=" + seeContext.count() + "}"
                + "; history={" + symbolHistory.diagnostic() + "}";
    }

    private void setPrimaryContextTrace(@NonNull String stage,
                                        @NonNull RarPpmdContext context,
                                        @NonNull RarPpmdDiagnosticOptions options) throws IOException {
        int symbolScale = context.scale();
        int escapeScale = context.primaryEscapeScale();
        int totalScale = context.primaryScale();
        lastDecodeTrace = "stage=" + stage
                + "; variant={" + options.diagnostic() + "}"
                + "; stateCount=" + context.stateCount()
                + "; symbolScale=" + symbolScale
                + "; escapeScale=" + escapeScale
                + "; totalScale=" + totalScale
                + "; masked=" + escapeMask.maskedCount()
                + "; primaryContext=true"
                + "; history={" + symbolHistory.diagnostic() + "}";
    }

    private void setOrder0Trace(@NonNull RarPpmdDiagnosticOptions options) throws IOException {
        int activeScale = order0BootstrapModel.scale(escapeMask);
        lastDecodeTrace = "stage=order0"
                + "; variant={" + options.diagnostic() + "}"
                + "; activeScale=" + activeScale
                + "; masked=" + escapeMask.maskedCount()
                + "; history={" + symbolHistory.diagnostic() + "}";
    }

    @NonNull
    private RarPpmdSeeContext selectSeeContext(@NonNull RarPpmdDiagnosticOptions options,
                                               int orderDepth,
                                               @NonNull RarPpmdContext context) throws IOException {
        RarPpmdSeeContext selected = seeTable.select(orderDepth, context, escapeMask, maxOrder,
                symbolHistory.count());
        return options.seeContextFor(selected, orderDepth, context, escapeMask, symbolHistory.count());
    }

    private static int safeTraceTotalScale(int symbolScale, int escapeScale) throws IOException {
        long total = (long) symbolScale + (long) escapeScale;
        if (symbolScale <= 0 || escapeScale <= 0 || total > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd trace saw invalid context scale: symbolScale="
                            + symbolScale + "; escapeScale=" + escapeScale);
        }
        return (int) total;
    }

    private void rememberSymbol(int symbol) throws IOException {
        symbolHistory.remember(symbol);
    }
}
