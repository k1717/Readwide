package com.textview.reader.archive;

/** File-level result for diagnostic RAR3/RAR4 first-party unpacker runs. */
final class Rar3UnpackFileResult {
    final long written;
    final long bitsRead;
    final int blocks;
    final long outputSize;
    final long actualCrc;
    final boolean hasExpectedCrc;
    final long expectedCrc;
    final Rar3ClassicLzStateTrace.Snapshot classicLzTrace;

    Rar3UnpackFileResult(long written,
                         long bitsRead,
                         int blocks,
                         long outputSize,
                         long actualCrc,
                         boolean hasExpectedCrc,
                         long expectedCrc) {
        this(written, bitsRead, blocks, outputSize, actualCrc, hasExpectedCrc, expectedCrc, null);
    }

    Rar3UnpackFileResult(long written,
                         long bitsRead,
                         int blocks,
                         long outputSize,
                         long actualCrc,
                         boolean hasExpectedCrc,
                         long expectedCrc,
                         Rar3ClassicLzStateTrace.Snapshot classicLzTrace) {
        this.written = written;
        this.bitsRead = bitsRead;
        this.blocks = blocks;
        this.outputSize = outputSize;
        this.actualCrc = actualCrc & 0xffffffffL;
        this.hasExpectedCrc = hasExpectedCrc;
        this.expectedCrc = expectedCrc & 0xffffffffL;
        this.classicLzTrace = classicLzTrace;
    }

    boolean crcMatches() {
        return !hasExpectedCrc || actualCrc == expectedCrc;
    }
}
