package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Small verifier for the target RAR3 solid CBR PPMd fixture boundary. */
final class Rar3SolidCbrPpmdFixtureProbe {
    private Rar3SolidCbrPpmdFixtureProbe() {}

    @NonNull
    static List<Row> probe(@NonNull File archive) throws IOException {
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
        List<Row> rows = new ArrayList<>();
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.directory) continue;
            byte[] packed = readPackedPayload(archive, entry);
            Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.fromPackedPayload(packed);
            rows.add(new Row(entry.path, entry.packedSize, entry.unpackedSize, entry.solid,
                    header.isPpmd(), header.keepOldTable(), header.rawFlags(),
                    header.maxOrderHint(), header.memoryMbHint(), header.escapeCharHint(),
                    header.payloadOffset(), header.diagnostic()));
        }
        return rows;
    }

    private static byte[] readPackedPayload(@NonNull File archive,
                                            @NonNull RarArchiveReader.RarEntry entry) throws IOException {
        if (entry.packedSize < 0 || entry.packedSize > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd fixture probe payload is outside supported bounds: "
                            + entry.packedSize);
        }
        byte[] packed = new byte[(int) entry.packedSize];
        try (RandomAccessFile raf = new RandomAccessFile(
                entry.sourceArchive != null ? entry.sourceArchive : archive, "r")) {
            raf.seek(entry.dataOffset);
            raf.readFully(packed);
        }
        return packed;
    }

    static final class Row {
        @NonNull final String path;
        final long packedSize;
        final long unpackedSize;
        final boolean solid;
        final boolean ppmd;
        final boolean keepOldTable;
        final int rawFlags;
        final int maxOrderHint;
        final int memoryMbHint;
        final int escapeCharHint;
        final int payloadOffset;
        @NonNull final String diagnostic;

        Row(@NonNull String path,
            long packedSize,
            long unpackedSize,
            boolean solid,
            boolean ppmd,
            boolean keepOldTable,
            int rawFlags,
            int maxOrderHint,
            int memoryMbHint,
            int escapeCharHint,
            int payloadOffset,
            @NonNull String diagnostic) {
            this.path = path;
            this.packedSize = packedSize;
            this.unpackedSize = unpackedSize;
            this.solid = solid;
            this.ppmd = ppmd;
            this.keepOldTable = keepOldTable;
            this.rawFlags = rawFlags;
            this.maxOrderHint = maxOrderHint;
            this.memoryMbHint = memoryMbHint;
            this.escapeCharHint = escapeCharHint;
            this.payloadOffset = payloadOffset;
            this.diagnostic = diagnostic;
        }
    }
}
