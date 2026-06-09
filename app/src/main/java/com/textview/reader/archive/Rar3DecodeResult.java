package com.textview.reader.archive;

final class Rar3DecodeResult {
    final long written;
    final long bitsRead;
    final int blocks;
    final Rar3ClassicLzStateTrace.Snapshot classicLzTrace;

    Rar3DecodeResult(long written, long bitsRead, int blocks) {
        this(written, bitsRead, blocks, null);
    }

    Rar3DecodeResult(long written,
                     long bitsRead,
                     int blocks,
                     Rar3ClassicLzStateTrace.Snapshot classicLzTrace) {
        this.written = written;
        this.bitsRead = bitsRead;
        this.blocks = blocks;
        this.classicLzTrace = classicLzTrace;
    }
}
