package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Extracts RAR5 (algorithm version 5.0) compressed entries with the
 * first-party {@link Rar5CompressedDecoder}.
 *
 * <p>Solid semantics: a solid entry's window depends on every compressed
 * entry since the start of its solid run, so those predecessors are decoded
 * first (outputs discarded, window carried). Every primer entry must pass
 * its own CRC check before the target is attempted. Stored entries do not
 * touch the window and are skipped in chains.</p>
 *
 * <p>Failure policy: {@code tryExtract*} methods return {@code false} only
 * when this extractor does not apply; once decoding starts, any
 * inconsistency or CRC mismatch is a thrown error. No fallback output and
 * no partially written targets (guarded).</p>
 */
final class Rar5CompressedArchiveExtractor {
    private Rar5CompressedArchiveExtractor() {}

    /** Per-entry packed/unpacked guards for in-memory decoding. */
    private static final long MAX_PACKED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 256L * 1024 * 1024;

    /**
     * @return true if the entry was extracted (CRC-verified) to outFile;
     *         false if this extractor does not apply to the entry
     * @throws IOException on decode failure, CRC mismatch, or cancellation
     */
    static boolean tryExtractEntry(@NonNull RarArchiveReader.RarEntry target,
                                   @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                   @NonNull File outFile,
                                   @Nullable FileOperationProgress progress) throws IOException {
        List<RarArchiveReader.RarEntry> chain = buildSolidChain(target, allEntries);
        if (chain == null) {
            return false;
        }

        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        try {
            for (int i = 0; i < chain.size(); i++) {
                RarArchiveReader.RarEntry entry = chain.get(i);
                boolean isTarget = i == chain.size() - 1;
                if (progress != null && !progress.checkpoint()) {
                    throw new IOException("RAR extraction cancelled");
                }
                byte[] data = decodeOne(decoder, entry);
                if (progress != null) {
                    progress.addDoneBytes(entry.unpackedSize > 0 ? entry.unpackedSize : 0);
                }
                if (isTarget) {
                    try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                        try (FileOutputStream out = new FileOutputStream(outFile)) {
                            out.write(data);
                        }
                        RarStoredPayloadIO.verifyCrc(entry, outFile);
                        guard.commit();
                    }
                    return true;
                }
            }
        } catch (Rar5CompressedDecoder.Rar5DataException e) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 decode failed: " + e.getMessage());
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR5 solid chain did not contain the target entry");
    }

    /**
     * Extracts a whole archive whose compressed members are all first-party
     * decodable RAR5 v5.0 entries (stored members are delegated to the
     * stored path). One shared decoder pass keeps solid window state
     * without re-priming per entry.
     *
     * @return true if every entry was extracted (each CRC-verified);
     *         false if this extractor does not apply to the archive
     */
    static boolean tryExtractArchive(@NonNull List<RarArchiveReader.RarEntry> entries,
                                     @NonNull File targetDir,
                                     @Nullable char[] password,
                                     @Nullable FileOperationProgress progress,
                                     @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        boolean sawCompressed = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) {
                continue;
            }
            if (entry.rarVersion != 5) {
                return false;
            }
            if (entry.method == 0) {
                continue; // stored, handled by the stored path below
            }
            if (!isEligibleCompressed(entry)) {
                return false;
            }
            sawCompressed = true;
        }
        if (!sawCompressed) {
            return false;
        }

        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        boolean sawEntry = false;
        try {
            for (RarArchiveReader.RarEntry entry : entries) {
                if (entry == null || entry.splitBefore) {
                    continue;
                }
                if (progress != null && !progress.checkpoint()) {
                    return false;
                }
                if (entryProgress != null) {
                    if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                    else entryProgress.onFile(entry.path);
                } else if (progress != null) {
                    progress.setDetail(entry.path);
                }
                File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
                if (out == null) {
                    return false;
                }
                sawEntry = true;
                if (entry.directory || entry.path.endsWith("/")) {
                    if (!out.exists() && !out.mkdirs()) {
                        return false;
                    }
                    continue;
                }
                if (entry.method == 0) {
                    RarArchiveReader.extractStoredEntry(entry, out, password, entries, progress);
                    continue;
                }
                byte[] data = decodeOne(decoder, entry);
                try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(data);
                    }
                    RarStoredPayloadIO.verifyCrc(entry, out);
                    guard.commit();
                }
                if (progress != null) {
                    progress.addDoneBytes(entry.unpackedSize > 0 ? entry.unpackedSize : 0);
                }
            }
        } catch (Rar5CompressedDecoder.Rar5DataException e) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 decode failed: " + e.getMessage());
        }
        return sawEntry;
    }

    /** Decodes one compressed entry and enforces its CRC before returning. */
    @NonNull
    private static byte[] decodeOne(@NonNull Rar5CompressedDecoder decoder,
                                    @NonNull RarArchiveReader.RarEntry entry) throws IOException {
        byte[] packed = readPackedPayload(entry);
        byte[] data = decoder.decodeEntry(packed, entry.unpackedSize, entry.rar5CompressionInfo);
        if (entry.dataCrc >= 0) {
            CRC32 crc = new CRC32();
            crc.update(data);
            if (crc.getValue() != (entry.dataCrc & 0xFFFFFFFFL)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR5 entry failed CRC verification: " + entry.path);
            }
        }
        return data;
    }

    /**
     * Builds the ordered list of compressed entries that must be decoded —
     * the start of the target's solid run through the target itself — or
     * returns null when this extractor does not apply.
     */
    @Nullable
    private static List<RarArchiveReader.RarEntry> buildSolidChain(
            @NonNull RarArchiveReader.RarEntry target,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) {
        if (target.rarVersion != 5 || target.directory || target.method == 0) {
            return null;
        }
        if (!isEligibleCompressed(target)) {
            return null;
        }
        int targetIndex = indexOfEntry(target, allEntries);
        if (targetIndex < 0) {
            return null;
        }

        // Walk backwards to the start of the solid run: the most recent
        // compressed file entry whose solid flag is clear.
        int startIndex = -1;
        for (int i = targetIndex; i >= 0; i--) {
            RarArchiveReader.RarEntry entry = allEntries.get(i);
            if (entry == null || entry.directory || entry.method == 0) {
                continue; // stored entries do not touch the window
            }
            if (!entry.solid) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            return null; // the run start is missing
        }

        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        for (int i = startIndex; i <= targetIndex; i++) {
            RarArchiveReader.RarEntry entry = allEntries.get(i);
            if (entry == null || entry.directory || entry.method == 0) {
                continue;
            }
            if (!isEligibleCompressed(entry)) {
                return null;
            }
            chain.add(entry);
        }
        if (chain.isEmpty() || chain.get(chain.size() - 1) != target) {
            return null;
        }
        return chain;
    }

    private static boolean isEligibleCompressed(@NonNull RarArchiveReader.RarEntry entry) {
        if (entry.rarVersion != 5 || entry.directory) {
            return false;
        }
        if (entry.encrypted() || entry.splitBefore || entry.splitAfter) {
            return false;
        }
        if (entry.method < 1 || entry.method > 5) {
            return false;
        }
        long info = entry.rar5CompressionInfo;
        if (info < 0 || (info & 0x3F) != 0) {
            return false; // unknown header or non-5.0 algorithm version
        }
        if (entry.packedSize < 1 || entry.packedSize > MAX_PACKED_BYTES) {
            return false;
        }
        if (entry.unpackedSize < 0 || entry.unpackedSize > MAX_UNPACKED_BYTES) {
            return false;
        }
        return entry.sourceArchive != null;
    }

    private static int indexOfEntry(@NonNull RarArchiveReader.RarEntry target,
                                    @NonNull List<RarArchiveReader.RarEntry> allEntries) {
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private static byte[] readPackedPayload(@NonNull RarArchiveReader.RarEntry entry)
            throws IOException {
        byte[] packed = new byte[(int) entry.packedSize];
        if (entry.sourceArchive == null) {
            throw new IOException("RAR5 entry source volume is missing");
        }
        try (RandomAccessFile raf = new RandomAccessFile(entry.sourceArchive, "r")) {
            raf.seek(entry.dataOffset);
            raf.readFully(packed);
        }
        return packed;
    }
}
