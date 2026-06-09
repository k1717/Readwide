package com.textview.reader.archive;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

final class RarCanonicalHuffman {
    private static final int MAX_BITS = 15;

    private final int[] symbolsByCode;
    private final int[] firstCodeByLength;
    private final int[] firstSymbolIndexByLength;
    private final int maxLength;

    private RarCanonicalHuffman(int[] symbolsByCode,
                                int[] firstCodeByLength,
                                int[] firstSymbolIndexByLength,
                                int maxLength) {
        this.symbolsByCode = symbolsByCode;
        this.firstCodeByLength = firstCodeByLength;
        this.firstSymbolIndexByLength = firstSymbolIndexByLength;
        this.maxLength = maxLength;
    }

    private static int[] newFilled(int size, int value) {
        int[] arr = new int[size];
        Arrays.fill(arr, value);
        return arr;
    }

    static RarCanonicalHuffman fromCodeLengths(int[] codeLengths) throws IOException {
        if (codeLengths == null || codeLengths.length == 0) {
            throw new IOException("RAR Huffman table is empty");
        }

        int[] counts = new int[MAX_BITS + 1];
        int maxLength = 0;
        for (int length : codeLengths) {
            if (length < 0 || length > MAX_BITS) {
                throw new IOException("Invalid RAR Huffman code length");
            }
            if (length == 0) continue;
            counts[length]++;
            if (length > maxLength) maxLength = length;
        }
        if (maxLength == 0) {
            return new RarCanonicalHuffman(new int[0],
                    newFilled(MAX_BITS + 1, -1),
                    newFilled(MAX_BITS + 1, -1),
                    0);
        }

        int[] nextCode = new int[MAX_BITS + 1];
        int code = 0;
        for (int bits = 1; bits <= MAX_BITS; bits++) {
            code = (code + counts[bits - 1]) << 1;
            if (code + counts[bits] > (1 << bits)) {
                throw new IOException("Oversubscribed RAR Huffman table");
            }
            nextCode[bits] = code;
        }

        int[] firstCodeByLength = new int[MAX_BITS + 1];
        int[] firstSymbolIndexByLength = new int[MAX_BITS + 1];
        Arrays.fill(firstCodeByLength, -1);
        Arrays.fill(firstSymbolIndexByLength, -1);

        int symbolCount = 0;
        for (int length = 1; length <= maxLength; length++) {
            if (counts[length] > 0) {
                firstCodeByLength[length] = nextCode[length];
                firstSymbolIndexByLength[length] = symbolCount;
                symbolCount += counts[length];
            }
        }

        int[] offsetByLength = Arrays.copyOf(firstSymbolIndexByLength, firstSymbolIndexByLength.length);
        int[] symbolsByCode = new int[symbolCount];
        for (int symbol = 0; symbol < codeLengths.length; symbol++) {
            int length = codeLengths[symbol];
            if (length == 0) continue;
            symbolsByCode[offsetByLength[length]++] = symbol;
            nextCode[length]++;
        }

        return new RarCanonicalHuffman(symbolsByCode, firstCodeByLength, firstSymbolIndexByLength, maxLength);
    }

    int decode(RarBitInput input) throws IOException {
        if (maxLength == 0) throw new IOException("RAR Huffman decode from empty table");
        int code = 0;
        for (int length = 1; length <= maxLength; length++) {
            try {
                code = (code << 1) | input.readBit();
            } catch (EOFException e) {
                throw new EOFException("RAR Huffman code ended unexpectedly");
            }
            int firstCode = firstCodeByLength[length];
            if (firstCode < 0) continue;
            int index = code - firstCode;
            int firstIndex = firstSymbolIndexByLength[length];
            int nextFirstIndex = nextFirstIndex(length);
            if (index >= 0 && firstIndex + index < nextFirstIndex) {
                return symbolsByCode[firstIndex + index];
            }
        }
        throw new IOException("Invalid RAR Huffman code");
    }

    private int nextFirstIndex(int length) {
        for (int i = length + 1; i < firstSymbolIndexByLength.length; i++) {
            if (firstSymbolIndexByLength[i] >= 0) return firstSymbolIndexByLength[i];
        }
        return symbolsByCode.length;
    }
}
