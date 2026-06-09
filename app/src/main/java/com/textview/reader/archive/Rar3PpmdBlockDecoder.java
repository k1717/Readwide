package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Executes RAR3/RAR4 PPMd control symbols after the PPMd model has produced bytes.
 *
 * <p>RAR's PPMd stream is not just literal bytes. The escape byte introduces control commands
 * for ending a PPM block/file, embedding LZ matches, and scheduling VM filters. This class covers
 * that RAR-specific control layer so the future PPMd model/range coder can plug into a small,
 * tested surface instead of growing {@link Rar3Unpacker} or {@link RarArchiveReader}.</p>
 */
final class Rar3PpmdBlockDecoder {
    private Rar3PpmdBlockDecoder() {}

    @NonNull
    static Rar3PpmdDecodeResult decodeUntilControlOrLimit(@NonNull Rar3PpmdSymbolSource source,
                                                          @NonNull RarLzWindow window,
                                                          @NonNull Rar3UnpackState state,
                                                          @NonNull Rar3PpmdState ppmdState,
                                                          long unpackedLimit) throws IOException {
        int symbols = 0;
        while (unpackedLimit < 0 || window.written() < unpackedLimit) {
            int symbol = safeDecode(source);
            symbols++;
            if (symbol != ppmdState.escapeChar()) {
                window.writeLiteral(symbol);
                continue;
            }

            int control = safeDecode(source);
            symbols++;
            switch (control) {
                case 0:
                    return new Rar3PpmdDecodeResult(
                            Rar3PpmdDecodeResult.END_BLOCK, window.written(), symbols);
                case 1:
                    window.writeLiteral(ppmdState.escapeChar());
                    break;
                case 2:
                    return new Rar3PpmdDecodeResult(
                            Rar3PpmdDecodeResult.END_FILE, window.written(), symbols);
                case 3:
                    throw Rar3FirstPartyGap.ppmdVmFilter(window.written());
                case 4:
                    symbols += executePpmdLzMatch(source, window, state, unpackedLimit);
                    break;
                case 5:
                    symbols += executePpmdRleMatch(source, window, state, unpackedLimit);
                    break;
                default:
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "RAR3/RAR4 PPMd control symbol is invalid: " + control);
            }
        }
        return new Rar3PpmdDecodeResult(
                Rar3PpmdDecodeResult.LIMIT_REACHED, window.written(), symbols);
    }

    private static int executePpmdLzMatch(@NonNull Rar3PpmdSymbolSource source,
                                          @NonNull RarLzWindow window,
                                          @NonNull Rar3UnpackState state,
                                          long unpackedLimit) throws IOException {
        int distance = 0;
        for (int i = 0; i < 3; i++) {
            distance = (distance << 8) | safeDecode(source);
        }
        int length = safeDecode(source) + 32;
        int actualDistance = distance + 2;
        int actualLength = clampLength(length, window, unpackedLimit);
        window.copyMatch(actualDistance, actualLength);
        state.rememberNewDistanceMatch(actualDistance, actualLength);
        return 4;
    }

    private static int executePpmdRleMatch(@NonNull Rar3PpmdSymbolSource source,
                                           @NonNull RarLzWindow window,
                                           @NonNull Rar3UnpackState state,
                                           long unpackedLimit) throws IOException {
        int length = safeDecode(source) + 4;
        int actualLength = clampLength(length, window, unpackedLimit);
        window.copyMatch(1, actualLength);
        state.rememberNewDistanceMatch(1, actualLength);
        return 1;
    }

    private static int clampLength(int length,
                                   @NonNull RarLzWindow window,
                                   long unpackedLimit) throws IOException {
        if (length < 0) throw new IOException("Invalid RAR3/RAR4 PPMd match length");
        if (unpackedLimit < 0) return length;
        long remaining = unpackedLimit - window.written();
        if (remaining <= 0) return 0;
        return length > remaining ? (int) remaining : length;
    }

    private static int safeDecode(@NonNull Rar3PpmdSymbolSource source) throws IOException {
        int value = source.decodeSymbol();
        if (value < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd model returned corrupt/end-of-stream data");
        }
        if (value > 255) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd model returned an out-of-range symbol: " + value);
        }
        return value;
    }
}
