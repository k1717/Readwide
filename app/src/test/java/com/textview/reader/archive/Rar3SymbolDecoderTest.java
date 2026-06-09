package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Rar3SymbolDecoderTest {
    @Test
    public void actionForMainSymbol_classifiesControlSymbols() throws Exception {
        assertEquals(Rar3DecodeAction.Type.LITERAL,
                Rar3SymbolDecoder.actionForMainSymbol('A').type);
        assertEquals('A', Rar3SymbolDecoder.actionForMainSymbol('A').literal);

        assertEquals(Rar3DecodeAction.Type.END_BLOCK,
                Rar3SymbolDecoder.actionForMainSymbol(256).type);
        assertEquals(Rar3DecodeAction.Type.VM_FILTER,
                Rar3SymbolDecoder.actionForMainSymbol(257).type);
        assertEquals(Rar3DecodeAction.Type.REPEAT_LAST_MATCH,
                Rar3SymbolDecoder.actionForMainSymbol(258).type);
    }

    @Test
    public void actionForMainSymbol_classifiesDistanceSymbols() throws Exception {
        Rar3DecodeAction old = Rar3SymbolDecoder.actionForMainSymbol(261);
        assertEquals(Rar3DecodeAction.Type.OLD_DISTANCE_MATCH, old.type);
        assertEquals(2, old.slot);

        Rar3DecodeAction shortMatch = Rar3SymbolDecoder.actionForMainSymbol(263);
        assertEquals(Rar3DecodeAction.Type.SHORT_DISTANCE_MATCH, shortMatch.type);
        assertEquals(0, shortMatch.slot);
        assertEquals(2, shortMatch.baseLength);

        Rar3DecodeAction longMatch = Rar3SymbolDecoder.actionForMainSymbol(271);
        assertEquals(Rar3DecodeAction.Type.LONG_MATCH, longMatch.type);
        assertEquals(0, longMatch.slot);
    }

    @Test(expected = java.io.IOException.class)
    public void actionForMainSymbol_rejectsOutOfRangeSymbol() throws Exception {
        Rar3SymbolDecoder.actionForMainSymbol(Rar3HuffmanTables.NC);
    }
}
