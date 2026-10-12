package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Extracts compressed entries from the RAR5 container (RAR 5/6 algorithm
 * version 0 and RAR 7 algorithm version 1) with the
 * first-party {@link Rar5CompressedDecoder}.
 *
 * <p>Solid semantics: a solid entry's window depends on every compressed
 * entry since the start of its solid run, so those predecessors are decoded
 * first (outputs discarded, window carried). Every available plain or key-dependent primer CRC
 * is checked before the target is attempted. Stored entries do not
 * touch the window and are skipped in chains.</p>
 *
 * <p>Failure policy: {@code tryExtract*} methods return {@code false} only
 * when this extractor does not apply; once decoding starts, any
 * inconsistency or CRC mismatch is a thrown error. No fallback output and
 * no partially written targets (guarded).</p>
 */
final class Rar5CompressedArchiveExtractor {
    private static void checkpoint(FileOperationProgress progress) throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new java.io.InterruptedIOException("RAR extraction cancelled");
        }
    }

    private Rar5CompressedArchiveExtractor() {}

    /** A decoded payload failed its checksum, not codec eligibility. */
    static final class ChecksumException extends IOException {
        ChecksumException(String path) { super("RAR5 entry failed checksum verification: " + path); }
    }

    /** Keeps solid history across pages; only a completed, checked entry is exposed. */
    @Nullable
    static ArchiveSupport.ForwardArchiveReader openForwardReader(File archive, char[] password,
            File spoolDirectory, boolean nativeAvailable) throws IOException {
        if (RarArchiveLocator.detectRarVersion(archive) != 5) return null;
        RarArchiveReader.EntryScan scan = RarArchiveReader.scanEntries(archive, password);
        List<RarArchiveReader.RarEntry> entries = scan.requireComplete();
        // Native cannot resolve file-copy records. Keep the already-filtered scan
        // rather than opening a native stream that fails at a later redirection.
        boolean needsFirstParty = !nativeAvailable || scan.hasRedirections
                || (password != null && password.length > 0);
        long previousWindow = 0;
        for (RarArchiveReader.RarEntry entry : entries) {
            // Native RAR5 rejects unknown-size headers even in intermediate volumes.
            needsFirstParty |= entry.unpackedSize < 0;
            if (entry.directory || entry.splitBefore) continue;
            if (entry.rarVersion != 5 || (entry.method != 0 && !isEligibleCompressed(entry))) return null;
            if (entry.method != 0) {
                long minimum = Rar5CompressedDecoder.declaredWindowSize(entry.rar5CompressionInfo);
                needsFirstParty |= entry.solid && previousWindow > 0 && minimum > previousWindow;
                previousWindow = entry.solid ? Math.max(previousWindow, minimum) : minimum;
            }
            needsFirstParty |= entry.encrypted() || (entry.rar5CompressionInfo & 0x3f) == 1;
            needsFirstParty |= entry.method != 0 && entry.packedSize == 0
                    && (entry.archiveSolid || entry.splitAfter);
        }
        return needsFirstParty ? new Rar5ForwardReader(entries, password, spoolDirectory,
                RarVolumeChain.index(entries)) : null;
    }


    /**
     * @return true if extracted to outFile (available plain or key-dependent CRC verified);
     *         false if this extractor does not apply to the entry
     * @throws IOException on decode failure, CRC mismatch, or cancellation
     */
    static boolean tryExtractEntry(@NonNull RarArchiveReader.RarEntry target,
                                   @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                   @NonNull File outFile,
                                   @Nullable char[] password,
                                   @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        RarSolidReadPlan plan = prepareSolidPlan(target, allEntries);
        if (plan == null) {
            return false;
        }

        long plannedHistorySize = 0;
        for (RarSolidReadPlan.Step step : plan.steps) {
            checkpoint(progress);
            plannedHistorySize = Math.max(plannedHistorySize,
                    Rar5CompressedDecoder.declaredWindowSize(step.entry.rar5CompressionInfo));
        }
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            for (int i = 0; i < plan.steps.size(); i++) {
                RarSolidReadPlan.Step step = plan.steps.get(i);
                RarArchiveReader.RarEntry entry = step.entry;
                boolean isTarget = i == plan.steps.size() - 1;
                checkpoint(progress);
                if (isTarget) {
                    try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                        try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                            decodeOne(decoder, entry, step.parts, password, out, progress,
                                    i == 0 ? plannedHistorySize : 0);
                        }
                        guard.commit();
                    }
                    return true;
                } else {
                    decodeOne(decoder, entry, step.parts, password, DISCARD, progress,
                            i == 0 ? plannedHistorySize : 0);
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
     * decodable RAR5-container entries (stored members are delegated to the
     * stored path). One shared decoder pass keeps solid window state
     * without re-priming per entry.
     *
     * @return true if every entry was extracted (available plain or key-dependent CRCs verified);
     *         false if this extractor does not apply to the archive
     */
    static boolean tryExtractArchive(@NonNull List<RarArchiveReader.RarEntry> entries,
                                     @NonNull File targetDir,
                                     @Nullable char[] password,
                                     @Nullable FileOperationProgress progress,
                                     @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        checkpoint(progress);
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

        RarVolumeChain.Index chains = RarVolumeChain.index(entries);
        java.util.Map<RarArchiveReader.RarEntry, Long> historyStarts = planDictionaryStarts(entries);
        java.util.Map<RarArchiveReader.RarEntry, File> destinations = ArchiveOutputPreflight.rar(targetDir, entries);
        if (progress != null) progress.setTotalBytes(RarArchiveReader.sumUnpackedBytes(entries));
        boolean sawEntry = false;
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
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
                if (entry.method == 0) {
                    RarArchiveReader.extractStoredEntry(entry, out, password, chain, progress);
                    continue;
                }
                // Keep already committed files, but do not touch this output for invalid metadata.
                if (entry.splitAfter) validateCompressedSplitChain(entry, chain);
                try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
                    try (OutputStream fos = ArchiveSupport.openExtractionOutputStream(out)) {
                        decodeOne(decoder, entry, chain, password, fos, progress,
                                historyStarts.getOrDefault(entry, 0L));
                    }
                    guard.commit();
                }
            }
        } catch (Rar5CompressedDecoder.Rar5DataException e) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 decode failed: " + e.getMessage());
        }
        return sawEntry;
    }

    private static final OutputStream DISCARD = new OutputStream() {
        @Override public void write(int value) {}
        @Override public void write(byte[] data, int offset, int length) {}
    };

    /**
     * One reverse metadata pass reserves each run's maximum at its compressed reset.
     * Directories, stored files and volume continuations do not reset solid history.
     * No payload reads or declared-size allocation; memory is one scalar per reset.
     * Ineligible later members remain decode-time failures, not lookahead exceptions.
     */
    static java.util.Map<RarArchiveReader.RarEntry, Long> planDictionaryStarts(
            List<RarArchiveReader.RarEntry> entries) throws IOException {
        java.util.Map<RarArchiveReader.RarEntry, Long> starts = new java.util.IdentityHashMap<>();
        java.util.ListIterator<RarArchiveReader.RarEntry> iterator = entries.listIterator(entries.size());
        long maximum = 0;
        while (iterator.hasPrevious()) {
            checkpoint(null);
            RarArchiveReader.RarEntry entry = iterator.previous();
            if (entry == null || entry.directory || entry.splitBefore || entry.method == 0) continue;
            if (!isEligibleCompressed(entry)) { maximum = 0; continue; }
            maximum = Math.max(maximum, Rar5CompressedDecoder.declaredWindowSize(entry.rar5CompressionInfo));
            if (!entry.solid) {
                starts.put(entry, maximum);
                maximum = 0;
            }
        }
        return starts;
    }

    /** Streams one logical file; the caller commits its guarded output only after CRC success. */
    static void decodeOne(@NonNull Rar5CompressedDecoder decoder,
                          @NonNull RarArchiveReader.RarEntry entry,
                          @NonNull List<RarArchiveReader.RarEntry> allEntries,
                          @Nullable char[] password,
                          @NonNull OutputStream destination,
                          @Nullable FileOperationProgress progress,
                          long plannedHistorySize) throws IOException {
        try (PackedPayload packed = openPackedPayload(entry, allEntries, password, progress)) {
            RarStoredPayloadIO.requireSupportedDataCheck(packed.checksumEntry);
            long unpackedSize = packed.checksumEntry.unpackedSize;
            if (unpackedSize < -1L) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR5 unpacked payload is outside supported bounds");
            }
            RarStoredPayloadIO.DataCheck dataCheck = new RarStoredPayloadIO.DataCheck(packed.checksumEntry);
            OutputStream checked = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    write(new byte[] {(byte) value}, 0, 1);
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
                    if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
                    destination.write(bytes, offset, length);
                    dataCheck.update(bytes, offset, length);
                    if (progress != null) progress.addDoneBytes(length);
                }
            };
            // A reset entry can also be empty, but only with archive-level solid
            // metadata. A continuation still needs previously verified history.
            boolean emptyPackedEntry = packed.packedSize == 0 && (entry.solid || entry.archiveSolid);
            decoder.decodeEntry(packed.input, unpackedSize, entry.rar5CompressionInfo, checked, emptyPackedEntry,
                    plannedHistorySize);
            // The empty path already read physical EOF, validating even zero-byte
            // segments and finalizing AES. No padding buffer is needed for it.
            if (!emptyPackedEntry) {
                verifyPackedEnd(packed.input, entry.encrypted(), progress);
            }
            // Intermediate split CRCs cover packed segments. Only the final part
            // carries the unpacked-file CRC; verify before any output is committed.
            if (!dataCheck.matches(packed.secrets)) {
                throw new ChecksumException(entry.path);
            }
        }
    }

    /**
     * The last compressed block ends the logical payload regardless of whether
     * an unpacked size was declared. Only AES alignment can follow it. Read one
     * byte beyond that allowance, not an arbitrary packed tail; physical EOF on
     * success still finalizes AES and checks the remaining split segments.
     */
    static void verifyPackedEnd(InputStream input, boolean encrypted,
                                @Nullable FileOperationProgress progress) throws IOException {
        int remainingPadding = encrypted ? 15 : 0;
        byte[] tail = new byte[remainingPadding + 1];
        while (true) {
            checkpoint(progress);
            int requested = remainingPadding + 1;
            int count = input.read(tail, 0, requested);
            checkpoint(progress);
            if (count == -1) return;
            if (count <= 0 || count > requested) {
                throw new IOException("Invalid RAR5 packed tail read progress");
            }
            if (count > remainingPadding) {
                throw new IOException("Unexpected data after RAR5 compressed stream");
            }
            remainingPadding -= count;
        }
    }

    /**
     * Prepares the ordered compressed entries and parts that must be decoded —
     * the start of the target's solid run through the target itself — or
     * returns null when this extractor does not apply.
     */
    @Nullable
    private static RarSolidReadPlan prepareSolidPlan(
            @NonNull RarArchiveReader.RarEntry target,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        if (!isEligibleCompressed(target)) {
            return null;
        }
        RarSolidReadPlan plan = RarSolidReadPlan.prepare(target, allEntries, 5);
        if (plan == null) return null;
        for (RarSolidReadPlan.Step step : plan.steps) {
            if (!isEligibleCompressed(step.entry)) {
                return null;
            }
        }
        // Validate all required continuations before any predecessor is decoded.
        // This is metadata-only: no payload reads, password derivation or output.
        // Keep eligibility above separate so unsupported runs retain their route.
        for (RarSolidReadPlan.Step step : plan.steps) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
            if (step.entry.splitAfter) validateCompressedSplitChain(step.entry, step.parts);
        }
        return plan;
    }

    private static boolean isEligibleCompressed(@NonNull RarArchiveReader.RarEntry entry) {
        if (entry.rarVersion != 5 || entry.directory) {
            return false;
        }
        // Encrypted entries are allowed only for RAR5 AES-256 with a usable
        // encryption record; the packed bytes are AES-CBC decrypted before
        // being handed to the decompressor. splitBefore/splitAfter are handled
        // by the volume-chain payload assembly, so they are no longer excluded.
        if (entry.encrypted()) {
            RarArchiveReader.EncryptionInfo enc = entry.encryption;
            if (enc == null || !enc.isRar5Aes256()) {
                return false;
            }
        }
        if (entry.method < 1 || entry.method > 5) {
            return false;
        }
        long info = entry.rar5CompressionInfo;
        if (!isSupportedCompressionInfo(info)) {
            return false;
        }
        // A split head describes only its own volume segment. Even zero bytes
        // here can precede a nonempty compressed stream on the next volume.
        // Resolve logical emptiness from the complete chain, not this header.
        if (entry.packedSize < 0 || (entry.packedSize == 0 && !entry.splitAfter
                && !isEmptyCompressedPayload(entry, entry.unpackedSize))) {
            return false;
        }
        if (entry.unpackedSize < -1) {
            return false;
        }
        return entry.sourceArchive != null;
    }

    private static boolean isEmptyCompressedPayload(RarArchiveReader.RarEntry first, long unpackedSize) {
        return unpackedSize == 0 && first.solid == ((first.rar5CompressionInfo & 0x40L) != 0)
                && (first.solid || first.archiveSolid);
    }

    static boolean isSupportedCompressionInfo(long info) {
        if (info < 0) {
            return false;
        }
        int algorithmVersion = (int) (info & 0x3F);
        if (algorithmVersion != 0 && algorithmVersion != 1) {
            return false;
        }
        try {
            Rar5CompressedDecoder.declaredWindowSize(info);
        } catch (Rar5CompressedDecoder.Rar5DataException invalidHeader) {
            return false;
        }
        return true;
    }

    /**
     * Opens a bounded stream across the entry's volume segments, optionally
     * AES-CBC decrypted. Packed payloads are never assembled into byte arrays.
     */
    @NonNull
    private static PackedPayload openPackedPayload(@NonNull RarArchiveReader.RarEntry entry,
                                            @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                            @Nullable char[] password,
                                            @Nullable FileOperationProgress progress)
            throws IOException {
        if (entry.sourceArchive == null) {
            throw new IOException("RAR5 entry source volume is missing");
        }

        boolean split = entry.splitBefore || entry.splitAfter;
        // Assemble the (possibly multi-volume) packed byte segments.
        List<RarCryptoStreams.EncryptedSegment> segments;
        long packedTotal;
        RarArchiveReader.RarEntry cryptoEntry = entry;
        RarArchiveReader.RarEntry checksumEntry = entry;
        if (split) {
            Rar5CompressedSplitPayload splitPayload = buildCompressedSplitPayload(
                    entry, allEntries);
            segments = splitPayload.segments;
            packedTotal = splitPayload.packedTotal;
            cryptoEntry = splitPayload.first;
            checksumEntry = splitPayload.last;
        } else {
            segments = java.util.Collections.singletonList(
                    new RarCryptoStreams.EncryptedSegment(
                            entry.sourceArchive, entry.dataOffset, entry.packedSize));
            packedTotal = entry.packedSize;
        }
        if (packedTotal < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 packed payload is outside supported bounds");
        }

        if (!cryptoEntry.encrypted()) {
            return new PackedPayload(new BufferedInputStream(
                    new RarPackedInputStream(segments, progress), 64 * 1024), checksumEntry, null, packedTotal);
        }
        if ((packedTotal % 16L) != 0) {
            throw new IOException("RAR5 encrypted payload is not AES block aligned");
        }
        RarArchiveReader.EncryptionInfo enc = cryptoEntry.encryption;
        if (enc == null || !enc.isRar5Aes256()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 entry encryption is not AES-256");
        }
        if (password == null || password.length == 0) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        Rar5Crypto.Secrets secrets =
                Rar5Crypto.deriveSecrets(password, enc.kdfCount, enc.salt, progress);
        if (!Rar5Crypto.passwordMatches(secrets, enc.check)) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        javax.crypto.Cipher cipher = Rar5Crypto.createAesCbcDecryptCipher(secrets, enc.iv);
        InputStream raw = new BufferedInputStream(new RarPackedInputStream(segments, progress, secrets), 64 * 1024);
        return new PackedPayload(new BufferedInputStream(
                new javax.crypto.CipherInputStream(raw, cipher), 64 * 1024), checksumEntry, secrets, packedTotal);
    }

    @NonNull
    private static Rar5CompressedSplitPayload buildCompressedSplitPayload(
            @NonNull RarArchiveReader.RarEntry first,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(first, allEntries);
        long packedTotal = validateCompressedSplitChain(first, chain);
        return new Rar5CompressedSplitPayload(
                first,
                RarVolumeChain.last(chain),
                RarVolumeChain.payloadSegments(chain),
                packedTotal);
    }

    /** Shared preflight and payload-time checks; never reads or decrypts source bytes. */
    static long validateCompressedSplitChain(@NonNull RarArchiveReader.RarEntry first,
            @NonNull List<RarArchiveReader.RarEntry> chain) throws IOException {
        if (first.rarVersion != 5 || first.method == 0 || first.directory) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split payload expected");
        }
        if (!RarVolumeChain.isComplete(chain)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Incomplete RAR5 compressed split payload");
        }

        boolean encrypted = first.encrypted();
        long packedTotal = 0L;
        for (int i = 0; i < chain.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
            RarArchiveReader.RarEntry part = chain.get(i);
            validateCompressedSplitPart(first, part, i, chain.size(), encrypted);
            if (Long.MAX_VALUE - packedTotal < part.packedSize) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR5 compressed split payload is too large");
            }
            packedTotal += part.packedSize;
        }
        long unpackedSize = RarVolumeChain.last(chain).unpackedSize;
        if (unpackedSize < -1L) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Invalid RAR5 compressed split unpacked size");
        }
        if (encrypted && (packedTotal % 16L) != 0L) {
            throw new IOException("RAR5 encrypted payload is not AES block aligned");
        }
        if (packedTotal == 0 && !isEmptyCompressedPayload(first, unpackedSize)) {
            throw new IOException("RAR5 empty split payload has inconsistent size or solid metadata");
        }
        return packedTotal;
    }

    private static void validateCompressedSplitPart(@NonNull RarArchiveReader.RarEntry first,
                                                    @NonNull RarArchiveReader.RarEntry part,
                                                    int index,
                                                    int count,
                                                    boolean encrypted) throws IOException {
        if (part.directory) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Directory entry cannot be a RAR5 compressed split payload part");
        }
        if (part.rarVersion != 5 || part.method == 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split payload expected");
        }
        if (!part.path.equals(first.path)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split continuation path mismatch");
        }
        if (part.method != first.method
                || part.rar5CompressionInfo != first.rar5CompressionInfo
                || part.archiveSolid != first.archiveSolid) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split parameters changed between volumes");
        }
        if (encrypted != part.encrypted()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Mixed encrypted and plain RAR5 compressed split payload is not supported");
        }
        if (encrypted && (first.encryption == null
                || !RarVolumeChain.sameRar5Encryption(first.encryption, part.encryption))) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 encrypted split parameters changed between volumes");
        }
        if (part.packedSize < 0L || part.dataOffset < 0L || part.sourceArchive == null) {
            throw new IOException("Invalid RAR5 compressed split segment bounds");
        }

        if (count == 1) {
            if (part.splitBefore || part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Invalid one-part RAR5 compressed split chain");
            }
        } else if (index == 0) {
            if (part.splitBefore || !part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Invalid first RAR5 compressed split part flags");
            }
        } else if (index == count - 1) {
            if (!part.splitBefore || part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Invalid last RAR5 compressed split part flags");
            }
        } else if (!part.splitBefore || !part.splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Invalid middle RAR5 compressed split part flags");
        }
    }

    private static final class Rar5CompressedSplitPayload {
        final RarArchiveReader.RarEntry first;
        final RarArchiveReader.RarEntry last;
        final List<RarCryptoStreams.EncryptedSegment> segments;
        final long packedTotal;

        Rar5CompressedSplitPayload(@NonNull RarArchiveReader.RarEntry first,
                                   @NonNull RarArchiveReader.RarEntry last,
                                   @NonNull List<RarCryptoStreams.EncryptedSegment> segments,
                                   long packedTotal) {
            this.first = first;
            this.last = last;
            this.segments = segments;
            this.packedTotal = packedTotal;
        }
    }

    private static final class PackedPayload implements java.io.Closeable {
        final InputStream input;
        final RarArchiveReader.RarEntry checksumEntry;
        final Rar5Crypto.Secrets secrets;
        final long packedSize;

        PackedPayload(InputStream input, RarArchiveReader.RarEntry checksumEntry,
                Rar5Crypto.Secrets secrets, long packedSize) {
            this.input = input;
            this.checksumEntry = checksumEntry;
            this.secrets = secrets;
            this.packedSize = packedSize;
        }

        @Override public void close() throws IOException { input.close(); }
    }
}
