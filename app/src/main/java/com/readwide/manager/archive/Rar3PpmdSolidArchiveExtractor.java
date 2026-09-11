package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, password);
        return openForwardReader(entries, password, spoolDirectory, nativeAvailable);
    }

    @Nullable
    static ArchiveSupport.ForwardArchiveReader openForwardReader(List<RarArchiveReader.RarEntry> entries,
            char[] password, File spoolDirectory, boolean nativeAvailable) throws IOException {
        boolean sawPpmd = false, needsFirstParty = !nativeAvailable;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.rarVersion != 4) return null;
            if (entry.directory || entry.splitBefore) continue;
            if (entry.unpackedSize < 0 || entry.dataCrc < 0) return null;
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) continue;
            if (!isPpmdBlockStart(entry, entries, password)) return null;
            if (!sawPpmd && entry.solid) return null; // Missing solid-set primer.
            sawPpmd = true;
            needsFirstParty |= entry.solid || entry.encrypted() || entry.splitAfter;
        }
        // Keep ordinary non-solid PPMd on native when available. The scoped solid
        // engine handles model carryover that otherwise degrades to bulk extraction.
        return sawPpmd && needsFirstParty
                ? new ForwardReader(entries, password, spoolDirectory) : null;
    }

    private static final class ForwardReader implements ArchiveSupport.ForwardArchiveReader {
        private final List<RarArchiveReader.RarEntry> entries;
        private final File directory;
        private final char[] password;
        private Rar3PpmdSolidStreamDecoder decoder;
        private RarArchiveReader.RarEntry current;
        private int index;
        private File spool;
        private java.io.InputStream input;
        private boolean decoded, failed, closed;

        ForwardReader(List<RarArchiveReader.RarEntry> entries, char[] password, File directory) {
            this.entries = entries; this.directory = directory;
            this.password = password == null ? null : password.clone();
        }

        private void checkpoint() throws IOException {
            if (closed || failed) throw new IOException("RAR3 PPMd forward reader is closed or failed");
            if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
        }

        @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
            checkpoint();
            drainCurrentEntry(Long.MAX_VALUE);
            do { current = index < entries.size() ? entries.get(index++) : null; }
            while (current != null && current.splitBefore);
            decoded = false;
            return current == null ? null : new ArchiveSupport.ForwardEntry(
                    current.path, current.directory, !current.directory);
        }

        private void decode(boolean retain) throws IOException {
            checkpoint();
            if (current == null || current.directory || decoded) return;
            try {
                boolean stored = RarFeatureClassifier.isRar3Or4StoredMethod(current.method);
                if (!stored && !current.solid) decoder = new Rar3PpmdSolidStreamDecoder();
                if (!stored && decoder == null) throw new IOException("Missing RAR3 PPMd solid primer");
                if (retain || stored) {
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create RAR spool directory");
                    spool = File.createTempFile("rar3_ppmd_verified_", ".spool", directory);
                    if (stored) RarArchiveReader.extractStoredEntry(current, spool, password, entries, null);
                    else writeVerifiedEntry(decoder, current, entries, password, spool, null);
                    if (retain) input = new java.io.BufferedInputStream(new java.io.FileInputStream(spool));
                    else clearSpool();
                } else {
                    // Skipping output must still advance the model/window and check the primer CRC.
                    decodeOne(decoder, current, entries, password, DISCARD, null);
                }
                decoded = true;
            } catch (IOException | RuntimeException failure) {
                failed = true;
                decoder = null;
                if (password != null) java.util.Arrays.fill(password, '\0');
                try { clearSpool(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                if (failure instanceof IOException) throw (IOException) failure;
                throw new IOException("RAR3 PPMd forward decode failed", failure);
            }
        }

        @Override public int read(byte[] buffer) throws IOException {
            checkpoint();
            if (buffer.length == 0) return 0;
            decode(true);
            return input == null ? -1 : input.read(buffer);
        }

        @Override public boolean drainCurrentEntry(long maximum) throws IOException {
            checkpoint();
            if (current != null && !current.directory && !decoded && current.unpackedSize > maximum) {
                throw new IOException("RAR entry exceeds requested drain bound");
            }
            decode(false);
            clearSpool();
            return true;
        }

        private void clearSpool() throws IOException {
            try { if (input != null) input.close(); }
            finally {
                input = null;
                if (spool != null) { spool.delete(); spool = null; }
            }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            decoder = null;
            if (password != null) java.util.Arrays.fill(password, '\0');
            clearSpool();
        }
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
        List<RarArchiveReader.RarEntry> chain = buildSolidChain(target, allEntries, password);
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
            if (!entry.solid) decoder = new Rar3PpmdSolidStreamDecoder();
            if (isTarget) {
                writeVerifiedEntry(decoder, entry, allEntries, password, outFile, progress);
                return true;
            }
            decodeOne(decoder, entry, allEntries, password, DISCARD, progress);
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
            if (entry.rarVersion != 4) {
                return false;
            }
            if (entry.unpackedSize < 0) {
                return false;
            }
            if (!isPpmdBlockStart(entry, entries, password)) {
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
            if (!entry.solid) decoder = new Rar3PpmdSolidStreamDecoder();
            writeVerifiedEntry(decoder, entry, entries, password, out, progress);
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
     * Builds the ordered list of compressed entries that must be decoded —
     * the start of the target's solid set through the target itself — or
     * returns null when this extractor does not apply.
     */
    @Nullable
    private static List<RarArchiveReader.RarEntry> buildSolidChain(
            @NonNull RarArchiveReader.RarEntry target,
            @NonNull List<RarArchiveReader.RarEntry> allEntries, char[] password) throws IOException {
        if (target.rarVersion >= 5 || target.directory) {
            return null;
        }
        if (target.splitBefore) {
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
            if (entry.directory || entry.splitBefore) {
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
            if (entry.directory || entry.splitBefore) {
                continue;
            }
            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                continue;
            }
            if (entry.rarVersion != 4) {
                return null;
            }
            if (entry.unpackedSize < 0) {
                return null;
            }
            if (!isPpmdBlockStart(entry, allEntries, password)) {
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

    private static boolean isPpmdBlockStart(@NonNull RarArchiveReader.RarEntry entry,
            List<RarArchiveReader.RarEntry> entries, char[] password)
            throws IOException {
        if (entry.sourceArchive == null) return false;
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
        if (entry.encrypted() || entry.splitAfter) {
            try (Rar3PpmdPayload payload = Rar3PpmdPayload.open(entry, entries, password, null)) {
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

    private static void writeVerifiedEntry(Rar3PpmdSolidStreamDecoder decoder,
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
        try (Rar3PpmdPayload payload = Rar3PpmdPayload.open(entry, entries, password, progress)) {
            java.io.InputStream input = payload.input;
            long crc = decoder.decodeEntry(input, payload.checksumEntry.unpackedSize, checked);
            // Force physical range/truncation checks even when the range coder stopped early.
            byte[] tail = new byte[64 * 1024];
            while (input.read(tail) != -1) {
                if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            }
            if (crc != (payload.checksumEntry.dataCrc & 0xffffffffL)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3 PPMd solid entry or primer failed CRC verification: " + entry.path);
            }
        }
    }
}
