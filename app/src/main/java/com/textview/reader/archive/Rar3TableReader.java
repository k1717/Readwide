package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Arrays;

final class Rar3TableReader {
    private static final int BLOCK_FLAG_PPM = 0x8000;
    private static final int BLOCK_FLAG_KEEP_OLD_TABLE = 0x4000;

    private Rar3TableReader() {}

    @NonNull
    static Rar3HuffmanTables readTables(@NonNull RarBitInput input,
                                        @NonNull int[] oldTableLengths) throws IOException {
        // RAR3 block tables are byte-aligned before their block flags. Without this, the
        // second and later blocks in a multi-block entry read the 16-bit flags at the wrong
        // bit offset, which corrupts the Huffman tables (and can look like a PPMd flag).
        input.alignToByte();
        int flags = input.peekBits(16);
        boolean keepOldTable = (flags & BLOCK_FLAG_KEEP_OLD_TABLE) != 0;
        if ((flags & BLOCK_FLAG_PPM) != 0) {
            throw Rar3FirstPartyGap.ppmdBlock(keepOldTable);
        }
        input.skipBits(2);

        int[] old = oldTableLengths.length >= Rar3HuffmanTables.TABLE_SIZE
                ? oldTableLengths
                : new int[Rar3HuffmanTables.TABLE_SIZE];
        if (!keepOldTable) Arrays.fill(old, 0);

        int[] bitLengthTable = readBitLengthTable(input);
        RarCanonicalHuffman bitLengthDecoder = RarCanonicalHuffman.fromCodeLengths(bitLengthTable);
        int[] tableLengths = readMainTableLengths(input, bitLengthDecoder, old);

        return new Rar3HuffmanTables(
                keepOldTable,
                tableLengths,
                RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(
                        tableLengths, 0, Rar3HuffmanTables.NC)),
                RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(
                        tableLengths,
                        Rar3HuffmanTables.NC,
                        Rar3HuffmanTables.NC + Rar3HuffmanTables.DC)),
                RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(
                        tableLengths,
                        Rar3HuffmanTables.NC + Rar3HuffmanTables.DC,
                        Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC)),
                RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(
                        tableLengths,
                        Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC,
                        Rar3HuffmanTables.TABLE_SIZE)));
    }

    private static int[] readBitLengthTable(@NonNull RarBitInput input) throws IOException {
        int[] lengths = new int[Rar3HuffmanTables.BC];
        for (int i = 0; i < lengths.length; i++) {
            int length = input.readBits(4);
            if (length == 15) {
                int zeroCount = input.readBits(4);
                if (zeroCount == 0) {
                    lengths[i] = 15;
                } else {
                    zeroCount += 2;
                    while (zeroCount-- > 0 && i < lengths.length) {
                        lengths[i++] = 0;
                    }
                    i--;
                }
            } else {
                lengths[i] = length;
            }
        }
        return lengths;
    }

    private static int[] readMainTableLengths(@NonNull RarBitInput input,
                                              @NonNull RarCanonicalHuffman bitLengthDecoder,
                                              @NonNull int[] oldTableLengths) throws IOException {
        int[] lengths = new int[Rar3HuffmanTables.TABLE_SIZE];
        int previous = 0;
        for (int i = 0; i < lengths.length;) {
            int number = bitLengthDecoder.decode(input);
            if (number < 16) {
                int value = (number + oldTableLengths[i]) & 0x0f;
                lengths[i++] = value;
                previous = value;
            } else if (number == 16) {
                int count = input.readBits(3) + 3;
                while (count-- > 0 && i < lengths.length) lengths[i++] = previous;
            } else if (number == 17) {
                int count = input.readBits(7) + 11;
                while (count-- > 0 && i < lengths.length) lengths[i++] = previous;
            } else if (number == 18) {
                int count = input.readBits(3) + 3;
                while (count-- > 0 && i < lengths.length) {
                    lengths[i++] = 0;
                    previous = 0;
                }
            } else {
                int count = input.readBits(7) + 11;
                while (count-- > 0 && i < lengths.length) {
                    lengths[i++] = 0;
                    previous = 0;
                }
            }
        }
        return lengths;
    }
}
