package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

final class Rar3SymbolDecoder {
    static final int SYMBOL_END_BLOCK = 256;
    static final int SYMBOL_VM_FILTER = 257;
    static final int SYMBOL_REPEAT_LAST_MATCH = 258;
    static final int SYMBOL_OLD_DISTANCE_FIRST = 259;
    static final int SYMBOL_OLD_DISTANCE_LAST = 262;
    static final int SYMBOL_SHORT_DISTANCE_FIRST = 263;
    static final int SYMBOL_SHORT_DISTANCE_LAST = 270;
    static final int SYMBOL_LONG_MATCH_FIRST = 271;

    private Rar3SymbolDecoder() {}

    @NonNull
    static Rar3DecodeAction decodeMainAction(@NonNull Rar3HuffmanTables tables,
                                             @NonNull RarBitInput input) throws IOException {
        return actionForMainSymbol(tables.literalTable.decode(input));
    }

    @NonNull
    static Rar3DecodeAction actionForMainSymbol(int symbol) throws IOException {
        if (symbol < 0 || symbol >= Rar3HuffmanTables.NC) {
            throw new IOException("Invalid RAR3/RAR4 main symbol");
        }
        if (symbol < 256) return Rar3DecodeAction.literal(symbol);
        if (symbol == SYMBOL_END_BLOCK) return Rar3DecodeAction.endBlock();
        if (symbol == SYMBOL_VM_FILTER) return Rar3DecodeAction.vmFilter();
        if (symbol == SYMBOL_REPEAT_LAST_MATCH) return Rar3DecodeAction.repeatLastMatch();
        if (symbol >= SYMBOL_OLD_DISTANCE_FIRST && symbol <= SYMBOL_OLD_DISTANCE_LAST) {
            return Rar3DecodeAction.oldDistanceMatch(symbol - SYMBOL_OLD_DISTANCE_FIRST);
        }
        if (symbol >= SYMBOL_SHORT_DISTANCE_FIRST && symbol <= SYMBOL_SHORT_DISTANCE_LAST) {
            return Rar3DecodeAction.shortDistanceMatch(
                    symbol - SYMBOL_SHORT_DISTANCE_FIRST,
                    symbol - SYMBOL_SHORT_DISTANCE_FIRST + 2);
        }
        return Rar3DecodeAction.longMatch(symbol - SYMBOL_LONG_MATCH_FIRST);
    }
}
