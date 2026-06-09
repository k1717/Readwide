package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Lightweight RAR3/RAR4 compressed-block classifier for PPMd work.
 *
 * <p>RAR3/RAR4 compressed payloads start a new table/control block on a byte boundary. The
 * highest bit of the first 16-bit control field selects PPMd (1) versus classic-LZ table data
 * (0), and the next bit is the keep-old-table flag used by both block families. This class does
 * not decode PPMd symbols; it only gives routing, reports, and future PPMd tests a precise,
 * file-backed way to distinguish PPMd fixtures from classic-LZ fixtures before trying the
 * unfinished first-party PPMd statistical model.</p>
 */
final class Rar3PpmdBlockProbe {
    static final int KIND_NOT_RAR3_OR4_COMPRESSED = 0;
    static final int KIND_UNKNOWN = 1;
    static final int KIND_CLASSIC_LZ = 2;
    static final int KIND_PPMD = 3;

    private Rar3PpmdBlockProbe() {}

    @NonNull
    static Result probe(@NonNull RarArchiveReader.RarEntry entry) {
        if (entry.rarVersion >= 5 || entry.directory || entry.method == 0 || entry.method == 0x30) {
            return new Result(KIND_NOT_RAR3_OR4_COMPRESSED, false, -1, -1,
                    "not a RAR3/RAR4 compressed payload");
        }
        if (entry.encrypted()) {
            return new Result(KIND_UNKNOWN, false, -1, -1,
                    "encrypted payload; compressed block flags are not visible before decrypt");
        }
        if (entry.splitBefore) {
            return new Result(KIND_UNKNOWN, false, -1, -1,
                    "split continuation; first block belongs to an earlier volume segment");
        }
        if (entry.packedSize < 2) {
            return new Result(KIND_UNKNOWN, false, -1, -1,
                    "packed payload is too small to contain RAR3 block flags");
        }
        File source = entry.sourceArchive;
        if (source == null) {
            return new Result(KIND_UNKNOWN, false, -1, -1,
                    "source archive is unavailable for block probing");
        }
        try (RandomAccessFile raf = new RandomAccessFile(source, "r")) {
            if (entry.dataOffset < 0 || entry.dataOffset + 2 > raf.length()) {
                return new Result(KIND_UNKNOWN, false, -1, -1,
                        "packed payload offset is outside the source archive");
            }
            raf.seek(entry.dataOffset);
            int first = raf.readUnsignedByte();
            int second = raf.readUnsignedByte();
            int flags = (first << 8) | second;
            boolean ppmd = (flags & 0x8000) != 0;
            boolean keepOldTable = (flags & 0x4000) != 0;
            return new Result(ppmd ? KIND_PPMD : KIND_CLASSIC_LZ,
                    keepOldTable,
                    flags,
                    entry.dataOffset,
                    ppmd ? "RAR3/RAR4 PPMd block flag detected"
                            : "RAR3/RAR4 classic-LZ block flag detected");
        } catch (IOException | SecurityException e) {
            return new Result(KIND_UNKNOWN, false, -1, entry.dataOffset,
                    "could not probe RAR3/RAR4 compressed block flags: "
                            + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static boolean isPpmdPayload(@NonNull RarArchiveReader.RarEntry entry) {
        return probe(entry).kind == KIND_PPMD;
    }

    @NonNull
    static String diagnostic(@NonNull RarArchiveReader.RarEntry entry) {
        return probe(entry).diagnostic();
    }

    static final class Result {
        final int kind;
        final boolean keepOldTable;
        final int rawFlags;
        final long dataOffset;
        @NonNull final String detail;

        Result(int kind, boolean keepOldTable, int rawFlags, long dataOffset, @NonNull String detail) {
            this.kind = kind;
            this.keepOldTable = keepOldTable;
            this.rawFlags = rawFlags;
            this.dataOffset = dataOffset;
            this.detail = detail;
        }

        boolean isPpmd() {
            return kind == KIND_PPMD;
        }

        boolean isClassicLz() {
            return kind == KIND_CLASSIC_LZ;
        }

        @NonNull
        String kindName() {
            switch (kind) {
                case KIND_NOT_RAR3_OR4_COMPRESSED:
                    return "not-compressed-rar3";
                case KIND_CLASSIC_LZ:
                    return "classic-lz";
                case KIND_PPMD:
                    return "ppmd";
                case KIND_UNKNOWN:
                default:
                    return "unknown";
            }
        }

        @NonNull
        String diagnostic() {
            String flagsText = rawFlags < 0 ? "n/a" : String.format(Locale.US, "0x%04x", rawFlags);
            return kindName()
                    + "; keepOldTable=" + keepOldTable
                    + "; rawFlags=" + flagsText
                    + "; dataOffset=" + dataOffset
                    + "; " + detail;
        }
    }
}
