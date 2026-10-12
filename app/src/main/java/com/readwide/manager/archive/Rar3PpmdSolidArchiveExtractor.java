package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
    private static void checkpoint(FileOperationProgress progress) throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new java.io.InterruptedIOException("RAR extraction cancelled");
        }
    }

    private Rar3PpmdSolidArchiveExtractor() {}

    /** Mixed classic-LZ/PPMd archives retain the broader extraction routes. */
    @Nullable
    static ArchiveSupport.ForwardArchiveReader openForwardReader(File archive, File spoolDirectory,
            boolean nativeAvailable) throws IOException {
        return openForwardReader(archive, null, spoolDirectory, nativeAvailable);
    }

    @Nullable
    static ArchiveSupport.ForwardArchiveReader openForwardReader(File archive, char[] password,
            File spoolDirectory, boolean nativeAvailable) throws IOException {
        if (RarArchiveLocator.detectRarVersion(archive) != 4) return null;
        ArchiveSupport.ForwardArchiveReader headers = RarHeaderEncryptedArchiveSupport.openForwardReader(
                archive, password, spoolDirectory, nativeAvailable);
        if (headers != null) return headers;
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, password);
        return openForwardReader(entries, password, spoolDirectory, nativeAvailable);
    }

    @Nullable
    static ArchiveSupport.ForwardArchiveReader openForwardReader(List<RarArchiveReader.RarEntry> entries,
            char[] password, File spoolDirectory, boolean nativeAvailable) throws IOException {
        if (spoolDirectory == null) return null;
        ArchiveSupport.ForwardArchiveReader ppmd = openPpmdForwardReader(
                entries, password, spoolDirectory, nativeAvailable);
        return ppmd != null ? ppmd : Rar3LogicalSolidArchiveExtractor.openForwardReader(
                entries, password, spoolDirectory, nativeAvailable);
    }

    @Nullable
    private static ArchiveSupport.ForwardArchiveReader openPpmdForwardReader(
            List<RarArchiveReader.RarEntry> entries, char[] password, File spoolDirectory,
            boolean nativeAvailable) throws IOException {
        RarVolumeChain.Index chains = RarVolumeChain.index(entries);
        boolean sawPpmd = false, needsFirstParty = !nativeAvailable;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.rarVersion != 4) return null;
            if (entry.directory || entry.splitBefore) continue;
            if (entry.unpackedSize < 0 || entry.dataCrc < 0) return null;
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) continue;
            if (!isPpmdBlockStart(entry, chains.chain(entry), password, null)) return null;
            if (!sawPpmd && entry.solid) return null; // Missing solid-set primer.
            sawPpmd = true;
            needsFirstParty |= entry.solid || entry.encrypted() || entry.splitAfter;
        }
        // Keep ordinary non-solid PPMd on native when available. The scoped solid
        // engine handles model carryover that otherwise degrades to bulk extraction.
        return sawPpmd && needsFirstParty
                ? new Rar3PpmdForwardReader(entries, password, spoolDirectory, chains) : null;
    }

    /**
     * @return true if the entry was extracted (CRC-verified) to outFile;
     *         false if this extractor does not apply to the entry
     * @throws IOException on decode failure, CRC mismatch, or cancellation
     */
    static boolean tryExtractSolidPpmdEntry(@NonNull RarArchiveReader.RarEntry target,
                                            @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                            @NonNull File outFile,
                                            @Nullable FileOperationProgress progress) throws IOException {
        return tryExtractSolidPpmdEntry(target, allEntries, outFile, null, progress);
    }

    static boolean tryExtractSolidPpmdEntry(RarArchiveReader.RarEntry target,
            List<RarArchiveReader.RarEntry> allEntries, File outFile, char[] password,
            FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        RarSolidReadPlan plan = prepareSolidPlan(target, allEntries, password, progress);
        if (plan == null) {
            return false;
        }

        // The selected plan begins at its only reset; allocate that decoder once.
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        for (int i = 0; i < plan.steps.size(); i++) {
            RarSolidReadPlan.Step step = plan.steps.get(i);
            RarArchiveReader.RarEntry entry = step.entry;
            boolean isTarget = i == plan.steps.size() - 1;
            checkpoint(progress);
            if (isTarget) {
                writeVerifiedEntry(decoder, entry, step.parts, password, outFile, progress);
                return true;
            }
            discardVerifiedEntry(decoder, entry, step.parts, password, progress);
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
        checkpoint(progress);
        RarVolumeChain.Index chains = RarVolumeChain.index(entries);
        boolean sawPpmd = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) {
                continue;
            }
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                continue; // handled by the stored path below
            }
            if (entry.rarVersion != 4) {
                return false;
            }
            if (entry.unpackedSize < 0) {
                return false;
            }
            if (!isPpmdBlockStart(entry, chains.chain(entry), password, progress)) {
                return false;
            }
            sawPpmd = true;
        }
        if (!sawPpmd) {
            return false;
        }

        java.util.Map<RarArchiveReader.RarEntry, File> destinations = ArchiveOutputPreflight.rar(targetDir, entries);
        if (progress != null) {
            progress.setTotalBytes(sumUnpackedBytes(entries));
        }
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        boolean sawEntry = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.splitBefore) {
                continue;
            }
            checkpoint(progress);
            if (entryProgress != null) {
                if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                else entryProgress.onFile(entry.path);
            } else if (progress != null) {
                progress.setDetail(entry.path);
            }
            File out = destinations.get(entry);
            sawEntry = true;
            if (entry.directory || entry.path.endsWith("/")) {
                RarOutputFileGuard.ensureDirectory(out);
                continue;
            }
            List<RarArchiveReader.RarEntry> chain = chains.chain(entry);
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                RarArchiveReader.extractStoredEntry(entry, out, password, chain, progress);
                continue;
            }
            if (!entry.solid) decoder = new Rar3PpmdSolidStreamDecoder();
            writeVerifiedEntry(decoder, entry, chain, password, out, progress);
        }
        return sawEntry;
    }

    private static long sumUnpackedBytes(@NonNull List<RarArchiveReader.RarEntry> entries) {
        long total = 0;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) continue;
            if (entry.unpackedSize > 0) {
                if (entry.unpackedSize > Long.MAX_VALUE - total) return Long.MAX_VALUE;
                total += entry.unpackedSize;
            }
        }
        return total;
    }

    /**
     * Prepares the ordered compressed entries and parts that must be decoded —
     * the start of the target's solid set through the target itself — or
     * returns null when this extractor does not apply.
     */
    @Nullable
    private static RarSolidReadPlan prepareSolidPlan(
            @NonNull RarArchiveReader.RarEntry target,
            @NonNull List<RarArchiveReader.RarEntry> allEntries, char[] password,
            FileOperationProgress progress) throws IOException {
        RarSolidReadPlan plan = RarSolidReadPlan.prepare(target, allEntries, 4);
        if (plan == null) return null;
        for (RarSolidReadPlan.Step step : plan.steps) {
            RarArchiveReader.RarEntry entry = step.entry;
            if (entry.rarVersion != 4) {
                return null;
            }
            if (entry.unpackedSize < 0) {
                return null;
            }
        }
        // Validate all selected metadata before any earlier primer probe can read or derive keys.
        for (RarSolidReadPlan.Step step : plan.steps) {
            Rar3PackedPayload.validateMetadata(step.entry, step.parts);
        }
        for (RarSolidReadPlan.Step step : plan.steps) {
            RarArchiveReader.RarEntry entry = step.entry;
            if (!isPpmdBlockStart(entry, step.parts, password, progress)) {
                // Classic-LZ solid members are a different decoder problem.
                return null;
            }
        }
        return plan;
    }

    private static boolean isPpmdBlockStart(@NonNull RarArchiveReader.RarEntry entry,
            List<RarArchiveReader.RarEntry> entries, char[] password, FileOperationProgress progress)
            throws IOException {
        checkpoint(progress);
        if (entry.sourceArchive == null) return false;
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
        if (entry.encrypted() || entry.splitAfter) {
            try (Rar3PackedPayload payload = Rar3PackedPayload.open(entry, entries, password, progress)) {
                int first = payload.input.read();
                return first >= 0 && (first & 0x80) != 0;
            }
        }
        // Classification needs one byte, not a 64 KiB buffered payload read per member.
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(entry.sourceArchive, "r")) {
            if (entry.dataOffset < 0 || entry.packedSize < 2 || entry.dataOffset > input.length()
                    || entry.packedSize > input.length() - entry.dataOffset) return false;
            input.seek(entry.dataOffset);
            int first = input.read();
            return first >= 0 && (first & 0x80) != 0;
        }
    }

    private static final OutputStream DISCARD = new OutputStream() {
        @Override public void write(int value) {}
        @Override public void write(byte[] bytes, int offset, int length) {}
    };

    /** Advances solid history without retaining output, including payload and CRC checks. */
    static void discardVerifiedEntry(Rar3PpmdSolidStreamDecoder decoder,
            RarArchiveReader.RarEntry entry, List<RarArchiveReader.RarEntry> entries,
            char[] password, FileOperationProgress progress) throws IOException {
        decodeOne(decoder, entry, entries, password, DISCARD, progress);
    }

    static void writeVerifiedEntry(Rar3PpmdSolidStreamDecoder decoder,
            RarArchiveReader.RarEntry entry, List<RarArchiveReader.RarEntry> entries, char[] password,
            File target, FileOperationProgress progress) throws IOException {
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(target)) {
            try (OutputStream out = ArchiveSupport.openExtractionOutputStream(target)) {
                decodeOne(decoder, entry, entries, password, out, progress);
            }
            guard.commit();
        }
    }

    private static void decodeOne(Rar3PpmdSolidStreamDecoder decoder,
            RarArchiveReader.RarEntry entry, List<RarArchiveReader.RarEntry> entries,
            char[] password, OutputStream destination,
            FileOperationProgress progress) throws IOException {
        OutputStream checked = new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
                if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
                destination.write(value);
                if (progress != null) progress.addDoneBytes(1);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
                if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
                destination.write(bytes, offset, length);
                if (progress != null) progress.addDoneBytes(length);
            }
        };
        try (Rar3PackedPayload payload = Rar3PackedPayload.open(entry, entries, password, progress)) {
            java.io.InputStream input = payload.input;
            long crc = decoder.decodeEntry(input, payload.checksumEntry.unpackedSize, checked);
            // Force physical range/truncation checks even when the range coder stopped early.
            payload.finish(progress);
            if (crc != (payload.checksumEntry.dataCrc & 0xffffffffL)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd solid entry or primer failed CRC verification: " + entry.path);
            }
        }
    }
}
