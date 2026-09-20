package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
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
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, password);
        boolean needsFirstParty = !nativeAvailable || (password != null && password.length > 0);
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.directory || entry.splitBefore) continue;
            if (entry.rarVersion != 5 || (entry.method != 0 && !isEligibleCompressed(entry))) return null;
            needsFirstParty |= entry.encrypted() || (entry.rar5CompressionInfo & 0x3f) == 1;
        }
        return needsFirstParty ? new ForwardReader(entries, password, spoolDirectory) : null;
    }

    private static final class ForwardReader implements ArchiveSupport.ForwardArchiveReader {
        private final List<RarArchiveReader.RarEntry> entries;
        private final char[] password;
        private final File directory;
        private Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        private int index;
        private RarArchiveReader.RarEntry current;
        private File spool;
        private InputStream input;
        private long verifiedSize, remainingBytes;
        private boolean decoded;
        private boolean closed;
        private boolean failed;

        ForwardReader(List<RarArchiveReader.RarEntry> entries, char[] password, File directory) {
            this.entries = entries;
            this.password = password == null ? null : password.clone();
            this.directory = directory;
        }

        private void checkpoint() throws IOException {
            if (closed || failed) throw new IOException("RAR forward reader is closed or failed");
            if (Thread.currentThread().isInterrupted()) {
                IOException failure = new IOException("RAR extraction cancelled");
                retire(failure);
                throw failure;
            }
        }

        @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
            checkpoint();
            drainCurrentEntry(Long.MAX_VALUE);
            clearSpool();
            current = null;
            decoded = false;
            while (index < entries.size()) {
                RarArchiveReader.RarEntry entry = entries.get(index++);
                if (entry.splitBefore) continue;
                current = entry;
                return new ArchiveSupport.ForwardEntry(entry.path, entry.directory, !entry.directory);
            }
            return null;
        }

        private void decode(boolean retain) throws IOException {
            checkpoint();
            if (current == null || current.directory || decoded) return;
            try {
                if (retain || current.method == 0) {
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create RAR spool directory");
                    spool = File.createTempFile("rar5_verified_entry_", ".spool", directory);
                    if (current.method == 0) {
                        RarArchiveReader.extractStoredEntry(current, spool, password, entries, null);
                    } else {
                        try (OutputStream out = ArchiveSupport.openExtractionOutputStream(spool)) {
                            decodeOne(decoder, current, entries, password, out, null);
                        }
                    }
                    if (retain) {
                        verifiedSize = remainingBytes = spool.length();
                        input = new java.io.BufferedInputStream(new java.io.FileInputStream(spool));
                    } else clearSpool();
                } else {
                    // Non-image predecessors still prime the solid window and verify their CRC.
                    decodeOne(decoder, current, entries, password, DISCARD, null);
                }
                decoded = true;
            } catch (IOException | RuntimeException | Error failure) {
                retire(failure); // Never reuse partially advanced solid history.
                if (failure instanceof IOException) throw (IOException) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new IOException("RAR5 forward decode failed", failure);
            }
        }

        @Override public int read(byte[] buffer) throws IOException {
            checkpoint();
            if (buffer.length == 0) return 0;
            decode(true);
            try {
                if (input == null) return -1;
                if (spool.length() != verifiedSize) throw new IOException("Truncated or changed verified RAR spool");
                if (remainingBytes == 0) return -1;
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remainingBytes));
                if (count <= 0) throw new IOException("Truncated verified RAR spool");
                remainingBytes -= count;
                return count;
            } catch (IOException | RuntimeException | Error failure) {
                retire(failure);
                throw failure;
            }
        }

        @Override public boolean drainCurrentEntry(long maximum) throws IOException {
            checkpoint();
            if (current != null && !current.directory && !decoded
                    && (current.unpackedSize < 0 || current.unpackedSize > maximum)) {
                throw new IOException("RAR entry exceeds requested drain bound");
            }
            decode(false);
            try { clearSpool(); }
            catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
            return true;
        }

        private void retire(Throwable failure) {
            failed = true;
            try { close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }

        private void clearSpool() throws IOException {
            IOException failure = null;
            try { if (input != null) input.close(); }
            catch (IOException cleanup) { failure = cleanup; }
            finally { input = null; remainingBytes = verifiedSize = 0; }
            if (spool != null) {
                try {
                    if (spool.exists() && !spool.delete()) throw new IOException("Cannot delete verified RAR spool");
                    spool = null;
                } catch (IOException | SecurityException cleanup) {
                    IOException deletion = cleanup instanceof IOException ? (IOException) cleanup
                            : new IOException("Cannot delete verified RAR spool", cleanup);
                    if (failure == null) failure = deletion; else failure.addSuppressed(deletion);
                }
            }
            if (failure != null) throw failure;
        }

        @Override public void close() throws IOException {
            if (closed && spool == null && input == null && decoder == null) return;
            closed = true;
            if (password != null) java.util.Arrays.fill(password, '\0');
            Throwable failure = null;
            try { if (decoder != null) decoder.close(); }
            catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; throw cleanup; }
            finally {
                decoder = null;
                try { clearSpool(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (failure == null) throw cleanup;
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
        }
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
        List<RarArchiveReader.RarEntry> chain = buildSolidChain(target, allEntries);
        if (chain == null) {
            return false;
        }

        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            for (int i = 0; i < chain.size(); i++) {
                RarArchiveReader.RarEntry entry = chain.get(i);
                boolean isTarget = i == chain.size() - 1;
                if (progress != null && !progress.checkpoint()) {
                    throw new IOException("RAR extraction cancelled");
                }
                if (isTarget) {
                    try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                        try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                            decodeOne(decoder, entry, allEntries, password, out, progress);
                        }
                        guard.commit();
                    }
                    return true;
                } else {
                    decodeOne(decoder, entry, allEntries, password, DISCARD, progress);
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

        boolean sawEntry = false;
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
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
                try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
                    try (OutputStream fos = ArchiveSupport.openExtractionOutputStream(out)) {
                        decodeOne(decoder, entry, entries, password, fos, progress);
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

    /** Streams one logical file; the caller commits its guarded output only after CRC success. */
    private static void decodeOne(@NonNull Rar5CompressedDecoder decoder,
                                    @NonNull RarArchiveReader.RarEntry entry,
                                    @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                    @Nullable char[] password,
                                    @NonNull OutputStream destination,
                                    @Nullable FileOperationProgress progress) throws IOException {
        try (PackedPayload packed = openPackedPayload(entry, allEntries, password, progress)) {
            RarStoredPayloadIO.requireSupportedDataCheck(packed.checksumEntry);
            long unpackedSize = packed.checksumEntry.unpackedSize;
            if (unpackedSize < 0L) {
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
            decoder.decodeEntry(packed.input, unpackedSize, entry.rar5CompressionInfo, checked);
            // Consume padding and force cipher finalization / physical truncation checks.
            byte[] tail = new byte[64 * 1024];
            while (packed.input.read(tail) != -1) {
                if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            }
            // Intermediate split CRCs cover packed segments. Only the final part
            // carries the unpacked-file CRC; verify before any output is committed.
            if (!dataCheck.matches(packed.secrets)) {
                throw new ChecksumException(entry.path);
            }
        }
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
        if (target.rarVersion != 5 || target.directory || target.method == 0 || target.splitBefore) {
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
            if (entry == null || entry.directory || entry.method == 0 || entry.splitBefore) {
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
            if (entry == null || entry.directory || entry.method == 0 || entry.splitBefore) {
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
        if (entry.packedSize < 1) {
            return false;
        }
        if (entry.unpackedSize < 0) {
            return false;
        }
        return entry.sourceArchive != null;
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

    private static int indexOfEntry(@NonNull RarArchiveReader.RarEntry target,
                                    @NonNull List<RarArchiveReader.RarEntry> allEntries) {
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i) == target) {
                return i;
            }
        }
        return -1;
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
                    new RarPackedInputStream(segments, progress), 64 * 1024), checksumEntry, null);
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
                Rar5Crypto.deriveSecrets(password, enc.kdfCount, enc.salt);
        if (!Rar5Crypto.passwordMatches(secrets, enc.check)) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        javax.crypto.Cipher cipher = Rar5Crypto.createAesCbcDecryptCipher(secrets, enc.iv);
        InputStream raw = new BufferedInputStream(new RarPackedInputStream(segments, progress, secrets), 64 * 1024);
        return new PackedPayload(new BufferedInputStream(
                new javax.crypto.CipherInputStream(raw, cipher), 64 * 1024), checksumEntry, secrets);
    }

    @NonNull
    private static Rar5CompressedSplitPayload buildCompressedSplitPayload(
            @NonNull RarArchiveReader.RarEntry first,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        if (first.rarVersion != 5 || first.method == 0 || first.directory) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split payload expected");
        }
        List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(first, allEntries);
        if (!RarVolumeChain.isComplete(chain)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Incomplete RAR5 compressed split payload");
        }

        boolean encrypted = first.encrypted();
        long packedTotal = 0L;
        for (int i = 0; i < chain.size(); i++) {
            RarArchiveReader.RarEntry part = chain.get(i);
            validateCompressedSplitPart(first, part, i, chain.size(), encrypted);
            if (Long.MAX_VALUE - packedTotal < part.packedSize) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR5 compressed split payload is too large");
            }
            packedTotal += part.packedSize;
        }
        return new Rar5CompressedSplitPayload(
                first,
                RarVolumeChain.last(chain),
                RarVolumeChain.payloadSegments(chain),
                packedTotal);
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
                || part.rar5CompressionInfo != first.rar5CompressionInfo) {
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

        PackedPayload(InputStream input, RarArchiveReader.RarEntry checksumEntry, Rar5Crypto.Secrets secrets) {
            this.input = input;
            this.checksumEntry = checksumEntry;
            this.secrets = secrets;
        }

        @Override public void close() throws IOException { input.close(); }
    }
}
