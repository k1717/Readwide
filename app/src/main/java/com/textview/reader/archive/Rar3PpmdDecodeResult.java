package com.textview.reader.archive;

/** Result of executing RAR3/RAR4 PPMd control symbols after PPMd byte decoding. */
final class Rar3PpmdDecodeResult {
    static final int END_BLOCK = 1;
    static final int END_FILE = 2;
    static final int LIMIT_REACHED = 3;

    final int type;
    final long written;
    final int symbolsRead;

    Rar3PpmdDecodeResult(int type, long written, int symbolsRead) {
        this.type = type;
        this.written = written;
        this.symbolsRead = symbolsRead;
    }
}
