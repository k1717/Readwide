package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sequentially decodes every file entry of a RAR3/RAR4 PPMd solid archive
 * with the real {@link Rar3PpmdSolidStreamDecoder} engine and reports
 * decoded length, CRC32 match, and image-magic match per entry.
 *
 * <p>Runnable on a plain JVM ({@code main}) for local verification; also
 * consumed by unit tests. Decode-only — no compression, no encryption.</p>
 */
final class Rar3PpmdEngineFixtureProbe {
    private Rar3PpmdEngineFixtureProbe() {}

    static final class Row {
        @NonNull final String path;
        final long unpackedSize;
        final int decodedBytes;
        final long expectedCrc;
        final long actualCrc;
        final boolean crcOk;
        final boolean magicOk;
        @NonNull final String magicName;
        @Nullable final String failure;
        @Nullable final byte[] data;

        Row(@NonNull String path, long unpackedSize, int decodedBytes, long expectedCrc,
            long actualCrc, boolean crcOk, boolean magicOk, @NonNull String magicName,
            @Nullable String failure, @Nullable byte[] data) {
            this.path = path;
            this.unpackedSize = unpackedSize;
            this.decodedBytes = decodedBytes;
            this.expectedCrc = expectedCrc;
            this.actualCrc = actualCrc;
            this.crcOk = crcOk;
            this.magicOk = magicOk;
            this.magicName = magicName;
            this.failure = failure;
            this.data = data;
        }

        @NonNull
        String describe() {
            if (failure != null) {
                return path + " FAILED: " + failure;
            }
            return String.format(Locale.US,
                    "%s decoded=%d/%d crc=%08x expect=%08x crcOk=%s magic=%s(%s)",
                    path, decodedBytes, unpackedSize, actualCrc, expectedCrc,
                    crcOk, magicOk, magicName);
        }
    }

    /**
     * Decodes all file entries in archive order. Stops at the first failed
     * entry because every later solid entry depends on its model state.
     */
    @NonNull
    static List<Row> probe(@NonNull File archive) throws IOException {
        List<RarArchiveReader.RarEntry> entries =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
        List<Row> rows = new ArrayList<>();
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.directory) {
                continue;
            }
            byte[] packed = readPackedPayload(archive, entry);
            Row row;
            try {
                Rar3PpmdSolidStreamDecoder.EntryResult result =
                        decoder.decodeEntry(packed, entry.unpackedSize);
                boolean crcOk = result.crc32 == (entry.dataCrc & 0xFFFFFFFFL);
                String magicName = magicName(result.data);
                row = new Row(entry.path, entry.unpackedSize, result.data.length,
                        entry.dataCrc & 0xFFFFFFFFL, result.crc32, crcOk,
                        !"unknown".equals(magicName), magicName, null, result.data);
            } catch (RarArchiveReader.UnsupportedRarFeatureException e) {
                row = new Row(entry.path, entry.unpackedSize, 0,
                        entry.dataCrc & 0xFFFFFFFFL, -1L, false, false, "unknown",
                        e.getMessage(), null);
            }
            rows.add(row);
            if (row.failure != null || !row.crcOk) {
                break;
            }
        }
        return rows;
    }

    @NonNull
    static String magicName(@NonNull byte[] data) {
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "png";
        }
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "gif";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
                && data[3] == 'F' && data[8] == 'W' && data[9] == 'E' && data[10] == 'B'
                && data[11] == 'P') {
            return "webp";
        }
        return "unknown";
    }

    @NonNull
    private static byte[] readPackedPayload(@NonNull File archive,
                                            @NonNull RarArchiveReader.RarEntry entry) throws IOException {
        if (entry.packedSize < 0 || entry.packedSize > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd engine probe payload is outside supported bounds: "
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

    /** Local JVM verification entry point (not used on Android). */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: Rar3PpmdEngineFixtureProbe <archive.cbr> [--dump-hex]");
            System.exit(2);
        }
        boolean dumpHex = args.length > 1 && "--dump-hex".equals(args[1]);
        List<Row> rows = probe(new File(args[0]));
        boolean allOk = !rows.isEmpty();
        for (Row row : rows) {
            System.out.println(row.describe());
            if (dumpHex && row.data != null) {
                StringBuilder sb = new StringBuilder(row.data.length * 2);
                for (byte b : row.data) {
                    sb.append(String.format(Locale.US, "%02x", b));
                }
                System.out.println("BYTES " + sb);
            }
            allOk &= row.failure == null && row.crcOk && row.magicOk;
        }
        System.out.println(allOk ? "RESULT: ALL ENTRIES DECODED + CRC MATCH"
                : "RESULT: FAILURE");
        System.exit(allOk ? 0 : 1);
    }
}
