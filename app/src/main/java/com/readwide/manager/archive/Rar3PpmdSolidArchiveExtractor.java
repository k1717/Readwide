package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a RAR3/RAR4 PPMd solid entry with the first-party
 * {@link Rar3PpmdSolidStreamDecoder} engine.
 *
 * <p>Solid semantics: the target's PPMd model and LZ window depend on every
 * compressed entry since the start of its solid set, so those predecessors
 * are decoded sequentially first (outputs discarded, state carried over).
 * Every primer entry must pass its own CRC check before the target is
 * attempted — a primer mismatch means the model state is wrong and the
 * target would silently corrupt, so extraction stops there.</p>
 *
 * <p>Failure policy: {@link #tryExtractSolidPpmdEntry} returns {@code false}
 * only when this path does not apply (not a RAR3/RAR4 PPMd solid chain it
 * can handle); once decoding starts, any inconsistency is a thrown error.
 * No fallback output, no partially written target (guarded), no success
 * reporting without a CRC match.</p>
 */
final class Rar3PpmdSolidArchiveExtractor {
    private Rar3PpmdSolidArchiveExtractor() {}

    /** Per-entry packed payload guard for in-memory decoding. */
    private static final long MAX_PACKED_BYTES = 64L * 1024 * 1024;

    /**
     * @return true if the entry was extracted (CRC-verified) to outFile;
     *         false if this extractor does not apply to the entry
     * @throws IOException on decode failure, CRC mismatch, or cancellation
     */
    static boolean tryExtractSolidPpmdEntry(@NonNull RarArchiveReader.RarEntry target,
                                            @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                            @NonNull File outFile,
                                            @Nullable FileOperationProgress progress) throws IOException {
        List<RarArchiveReader.RarEntry> chain = buildSolidChain(target, allEntries);
        if (chain == null) {
            return false;
        }

        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        for (int i = 0; i < chain.size(); i++) {
            RarArchiveReader.RarEntry entry = chain.get(i);
            boolean isTarget = i == chain.size() - 1;
            if (progress != null && !progress.checkpoint()) {
                throw new IOException("RAR extraction cancelled");
            }
            byte[] packed = readPackedPayload(entry);
            Rar3PpmdSolidStreamDecoder.EntryResult result =
                    decoder.decodeEntry(packed, entry.unpackedSize);
            if (entry.dataCrc >= 0 && result.crc32 != (entry.dataCrc & 0xFFFFFFFFL)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        (isTarget
                                ? "RAR3 PPMd solid entry failed CRC verification: "
                                : "RAR3 PPMd solid primer entry failed CRC verification: ")
                                + entry.path);
            }
            if (progress != null) {
                progress.addDoneBytes(entry.unpackedSize > 0 ? entry.unpackedSize : 0);
            }
            if (isTarget) {
                try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                    try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                        out.write(result.data);
                    }
                    RarStoredPayloadIO.verifyCrc(entry, outFile);
                    guard.commit();
                }
                return true;
            }
        }
        // Unreachable: the chain always ends with the target.
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3 PPMd solid chain did not contain the target entry");
    }

    /**
     * Extracts a whole archive whose compressed members are all RAR3/RAR4
     * PPMd entries (stored members are allowed and delegated to the stored
     * path). One shared decoder pass keeps the solid model and window state
     * without re-priming per entry.
     *
     * @return true if every entry was extracted (each CRC-verified);
     *         false if this extractor does not apply to the archive
     * @throws IOException on decode failure, CRC mismatch, or cancellation
     */
    static boolean tryExtractArchivePpmdSolid(@NonNull List<RarArchiveReader.RarEntry> entries,
                                              @NonNull File targetDir,
                                              @Nullable char[] password,
                                              @Nullable FileOperationProgress progress,
                                              @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        boolean sawPpmd = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) {
                continue;
            }
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                continue; // handled by the stored path below
            }
            if (entry.rarVersion >= 5 || entry.encrypted() || entry.splitAfter) {
                return false;
            }
            if (entry.packedSize < 2 || entry.packedSize > MAX_PACKED_BYTES
                    || entry.unpackedSize < 0) {
                return false;
            }
            if (!isPpmdBlockStart(entry)) {
                return false;
            }
            sawPpmd = true;
        }
        if (!sawPpmd) {
            return false;
        }

        if (progress != null) {
            progress.setTotalBytes(sumUnpackedBytes(entries));
        }
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        boolean sawEntry = false;
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
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                RarArchiveReader.extractStoredEntry(entry, out, password, entries, progress);
                continue;
            }
            byte[] packed = readPackedPayload(entry);
            Rar3PpmdSolidStreamDecoder.EntryResult result =
                    decoder.decodeEntry(packed, entry.unpackedSize);
            if (entry.dataCrc >= 0 && result.crc32 != (entry.dataCrc & 0xFFFFFFFFL)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd solid entry failed CRC verification: " + entry.path);
            }
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
                try (OutputStream fos = ArchiveSupport.openExtractionOutputStream(out)) {
                    fos.write(result.data);
                }
                RarStoredPayloadIO.verifyCrc(entry, out);
                guard.commit();
            }
            if (progress != null) {
                progress.addDoneBytes(entry.unpackedSize > 0 ? entry.unpackedSize : 0);
            }
        }
        return sawEntry;
    }

    private static long sumUnpackedBytes(@NonNull List<RarArchiveReader.RarEntry> entries) {
        long total = 0;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) continue;
            if (entry.unpackedSize > 0) total += entry.unpackedSize;
        }
        return total;
    }

    /**
     * Builds the ordered list of compressed entries that must be decoded —
     * the start of the target's solid set through the target itself — or
     * returns null when this extractor does not apply.
     */
    @Nullable
    private static List<RarArchiveReader.RarEntry> buildSolidChain(
            @NonNull RarArchiveReader.RarEntry target,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        if (target.rarVersion >= 5 || target.directory) {
            return null;
        }
        if (target.encrypted() || target.splitBefore || target.splitAfter) {
            return null;
        }
        if (RarFeatureClassifier.isRar3Or4StoredMethod(target.method)) {
            return null;
        }
        int targetIndex = indexOfEntry(target, allEntries);
        if (targetIndex < 0) {
            return null;
        }

        // Walk backwards to the start of the solid set: the most recent
        // compressed file entry whose solid flag is clear.
        int startIndex = -1;
        for (int i = targetIndex; i >= 0; i--) {
            RarArchiveReader.RarEntry entry = allEntries.get(i);
            if (entry.directory) {
                continue;
            }
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                continue; // stored entries do not touch the model or window
            }
            if (!entry.solid) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            // The set start is missing (e.g. split across an absent volume).
            return null;
        }

        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        for (int i = startIndex; i <= targetIndex; i++) {
            RarArchiveReader.RarEntry entry = allEntries.get(i);
            if (entry.directory) {
                continue;
            }
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                continue;
            }
            if (entry.rarVersion >= 5 || entry.encrypted()
                    || entry.splitBefore || entry.splitAfter) {
                return null;
            }
            if (entry.packedSize < 2 || entry.packedSize > MAX_PACKED_BYTES
                    || entry.unpackedSize < 0) {
                return null;
            }
            if (!isPpmdBlockStart(entry)) {
                // Classic-LZ solid members are a different decoder problem.
                return null;
            }
            chain.add(entry);
        }
        if (chain.isEmpty() || chain.get(chain.size() - 1) != target) {
            return null;
        }
        return chain;
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

    private static boolean isPpmdBlockStart(@NonNull RarArchiveReader.RarEntry entry)
            throws IOException {
        if (entry.sourceArchive == null) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(entry.sourceArchive, "r")) {
            raf.seek(entry.dataOffset);
            int first = raf.read();
            return first >= 0 && (first & 0x80) != 0;
        }
    }

    @NonNull
    private static byte[] readPackedPayload(@NonNull RarArchiveReader.RarEntry entry)
            throws IOException {
        byte[] packed = new byte[(int) entry.packedSize];
        if (entry.sourceArchive == null) {
            throw new IOException("RAR entry source volume is missing");
        }
        try (RandomAccessFile raf = new RandomAccessFile(entry.sourceArchive, "r")) {
            raf.seek(entry.dataOffset);
            raf.readFully(packed);
        }
        return packed;
    }
}
