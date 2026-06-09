package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

final class Rar3BlockDecoder {
    private Rar3BlockDecoder() {}

    /** Outcome of decoding one RAR3 block after the RAR3 end-of-block marker. */
    enum EndOfBlock {
        NEW_TABLE,            // symbol 256: a new table follows in the same file
        NEW_FILE_WITH_TABLE,  // new-file marker with a following table
        NEW_FILE,             // new-file marker, no further table for this entry
        LIMIT_REACHED         // produced unpackedLimit bytes before any end marker
    }

    static EndOfBlock decodeUntilEndOrUnsupported(@NonNull Rar3HuffmanTables tables,
                                               @NonNull RarBitInput input,
                                               @NonNull RarLzWindow window,
                                               @NonNull Rar3UnpackState state,
                                               long unpackedLimit) throws IOException {
        return decodeUntilEndOrUnsupported(tables, input, window, state, unpackedLimit, null);
    }

    static EndOfBlock decodeUntilEndOrUnsupported(@NonNull Rar3HuffmanTables tables,
                                               @NonNull RarBitInput input,
                                               @NonNull RarLzWindow window,
                                               @NonNull Rar3UnpackState state,
                                               long unpackedLimit,
                                               Rar3ClassicLzStateTrace trace) throws IOException {
        while (unpackedLimit < 0 || window.written() < unpackedLimit) {
            Rar3DecodeAction action = Rar3SymbolDecoder.decodeMainAction(tables, input);
            switch (action.type) {
                case LITERAL:
                    window.writeLiteral(action.literal);
                    if (trace != null) trace.recordLiteral();
                    break;
                case END_BLOCK:
                    if (trace != null) trace.recordEndBlock(input.bitsRead(), window.written());
                    // After symbol 256, the stream stores the new-table / new-file flags.
                    // Top bit set -> a new table follows in the same file (consume 1 bit).
                    // Top bit clear -> this is a new-file marker; the next bit says whether a
                    // new table follows (consume 2 bits). Failing to consume these bits leaves
                    // the bitstream misaligned and makes the next block header look like PPMd.
                    {
                        int bits = input.peekBits(16);
                        if ((bits & 0x8000) != 0) {
                            input.skipBits(1);
                            return EndOfBlock.NEW_TABLE;
                        }
                        boolean newTable = (bits & 0x4000) != 0;
                        input.skipBits(2);
                        return newTable ? EndOfBlock.NEW_FILE_WITH_TABLE : EndOfBlock.NEW_FILE;
                    }
                case VM_FILTER:
                    if (trace != null) trace.recordVmFilter();
                    RarVmFilter parsedFilter = RarVmFilterParser.read(input, window.written());
                    throw Rar3FirstPartyGap.vmFilterMissingExecutionState(parsedFilter);
                case REPEAT_LAST_MATCH:
                    executeMatch(action.type, Rar3LengthDistanceDecoder.repeatLastMatch(state), window, state, unpackedLimit, trace);
                    break;
                case OLD_DISTANCE_MATCH:
                    executeMatch(action.type, Rar3LengthDistanceDecoder.decodeOldDistanceMatch(
                            action, tables, input, state), window, state, unpackedLimit, trace);
                    break;
                case SHORT_DISTANCE_MATCH:
                    executeMatch(action.type, Rar3LengthDistanceDecoder.decodeShortDistanceMatch(
                            action, input), window, state, unpackedLimit, trace);
                    break;
                case LONG_MATCH:
                    executeMatch(action.type, Rar3LengthDistanceDecoder.decodeLongMatch(
                            action, tables, input, state), window, state, unpackedLimit, trace);
                    break;
                default:
                    throw new IOException("Unknown RAR3/RAR4 decode action");
            }
        }
        if (trace != null) trace.recordBlockLimitReached(input.bitsRead(), window.written());
        return EndOfBlock.LIMIT_REACHED;
    }

    private static void executeMatch(@NonNull Rar3DecodeAction.Type actionType,
                                     @NonNull Rar3LengthDistanceDecoder.Match match,
                                     @NonNull RarLzWindow window,
                                     @NonNull Rar3UnpackState state,
                                     long unpackedLimit,
                                     Rar3ClassicLzStateTrace trace) throws IOException {
        int decodedLength = match.length;
        int emittedLength = decodedLength;
        if (unpackedLimit >= 0) {
            long remaining = unpackedLimit - window.written();
            if (remaining <= 0) return;
            if (emittedLength > remaining) emittedLength = (int) remaining;
        }
        long writtenBefore = window.written();
        Rar3ClassicLzStateTransitionCheck.Snapshot stateBefore = trace == null
                ? null
                : Rar3ClassicLzStateTransitionCheck.snapshot(state);
        window.copyMatch(match.distance, emittedLength);
        if (match.kind == Rar3LengthDistanceDecoder.Match.KIND_OLD_DISTANCE) {
            state.rememberOldDistanceMatch(match.oldDistanceSlot, decodedLength);
        } else if (match.kind == Rar3LengthDistanceDecoder.Match.KIND_REPEAT_LAST) {
            state.rememberRepeatLastMatch(decodedLength);
        } else {
            state.rememberNewDistanceMatch(match.distance, decodedLength);
        }
        if (trace != null) {
            Rar3ClassicLzStateTransitionCheck.Result transition =
                    Rar3ClassicLzStateTransitionCheck.check(
                            actionType,
                            match.distance,
                            decodedLength,
                            match.oldDistanceSlot,
                            stateBefore,
                            Rar3ClassicLzStateTransitionCheck.snapshot(state));
            trace.recordMatch(actionType,
                    match.distance,
                    decodedLength,
                    emittedLength,
                    match.oldDistanceSlot,
                    writtenBefore,
                    window.written(),
                    stateBefore.oldDistances[0],
                    stateBefore.oldDistances[1],
                    stateBefore.oldDistances[2],
                    stateBefore.oldDistances[3],
                    stateBefore.lastDistance,
                    stateBefore.lastLength,
                    state.oldDistance(0),
                    state.oldDistance(1),
                    state.oldDistance(2),
                    state.oldDistance(3),
                    state.lastDistance(),
                    state.lastLength(),
                    match.lengthSlot,
                    match.distanceSlot,
                    match.distanceExtraBits,
                    match.lowDistance,
                    match.lowDistanceRepeated,
                    transition);
        }
    }
}
