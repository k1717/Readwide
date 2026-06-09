package com.textview.reader.archive;

import androidx.annotation.NonNull;

final class Rar3HuffmanTables {
    static final int BC = 20;
    static final int NC = 299;
    static final int DC = 60;
    static final int LDC = 17;
    static final int RC = 28;
    static final int TABLE_SIZE = NC + DC + LDC + RC;

    final boolean keepOldTable;
    @NonNull final int[] tableLengths;
    @NonNull final RarCanonicalHuffman literalTable;
    @NonNull final RarCanonicalHuffman distanceTable;
    @NonNull final RarCanonicalHuffman lowDistanceTable;
    @NonNull final RarCanonicalHuffman repeatTable;

    Rar3HuffmanTables(boolean keepOldTable,
                      @NonNull int[] tableLengths,
                      @NonNull RarCanonicalHuffman literalTable,
                      @NonNull RarCanonicalHuffman distanceTable,
                      @NonNull RarCanonicalHuffman lowDistanceTable,
                      @NonNull RarCanonicalHuffman repeatTable) {
        this.keepOldTable = keepOldTable;
        this.tableLengths = tableLengths;
        this.literalTable = literalTable;
        this.distanceTable = distanceTable;
        this.lowDistanceTable = lowDistanceTable;
        this.repeatTable = repeatTable;
    }
}
