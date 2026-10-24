package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

/** Checked selected, bulk and verified-forward bridges to existing mixed solid decoding. */
final class Rar3LogicalSolidArchiveExtractor {
    private Rar3LogicalSolidArchiveExtractor() {}

    static boolean tryExtractEntry(RarArchiveReader.RarEntry target,
            List<RarArchiveReader.RarEntry> entries, File output, char[] password,
            FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        RarSolidReadPlan plan = prepare(target, entries);
        if (plan == null) return false;
        Rar3SolidState state = new Rar3SolidState();
        try {
            for (int i = 0; i < plan.steps.size(); i++) {
                RarSolidReadPlan.Step step = plan.steps.get(i);
                checkpoint(progress);
                try (Rar3PackedPayload payload = Rar3PackedPayload.open(step.entry, step.parts, password, progress)) {
                    Rar3UnpackContext context = Rar3UnpackContext.forLogicalSolidPayload(step.entry, payload, state);
                    if (i + 1 < plan.steps.size()) context.allowPartialFilterCarry();
                    if (step.entry == target) {
                        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
                            Rar3Unpacker.unpack(context, output, progress);
                            checkpoint(progress);
                            guard.commit();
                        }
                    } else {
                        Rar3Unpacker.unpackToDiscard(context, progress);
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException | Error failure) {
            state.invalidate();
            throw failure;
        }
    }

    /** Whole-archive bridge; all admission and path decisions precede output creation. */
    static boolean tryExtractArchive(List<RarArchiveReader.RarEntry> entries, File directory,
            char[] password, FileOperationProgress progress,
            ArchiveExtractionProgressTracker entryProgress) throws IOException {
        checkpoint(progress);
        Plan plan = prepareArchive(entries);
        if (plan == null) return false;
        java.util.ArrayList<File> outputs = new java.util.ArrayList<>();
        ArchiveOutputPreflight destinations = new ArchiveOutputPreflight(directory);
        for (Step step : plan.steps) {
            checkpoint(progress);
            outputs.add(destinations.add(step.entry.path, step.entry.directory));
        }
        if (progress != null) progress.setTotalBytes(plan.total);
        Decoder decoder = new Decoder(plan, password);
        try {
            for (int i = 0; i < plan.steps.size(); i++) {
                checkpoint(progress);
                RarArchiveReader.RarEntry entry = plan.steps.get(i).entry;
                if (entryProgress != null) {
                    if (entry.directory) entryProgress.onDirectory(entry.path);
                    else entryProgress.onFile(entry.path);
                } else if (progress != null) progress.setDetail(entry.path);
                decoder.extractNext(outputs.get(i), progress);
            }
            return true;
        } finally { decoder.retire(); }
    }

    static final class Step {
        final RarArchiveReader.RarEntry entry;
        final List<RarArchiveReader.RarEntry> parts;
        final boolean stored;
        boolean checked;
        boolean continuesSolid;
        Step(RarArchiveReader.RarEntry entry, List<RarArchiveReader.RarEntry> parts, boolean stored) {
            this.entry = entry; this.parts = parts; this.stored = stored;
        }
    }

    static final class Plan {
        final List<Step> steps;
        final long total;
        Plan(List<Step> steps, long total) {
            this.steps = java.util.Collections.unmodifiableList(steps); this.total = total;
        }
    }

    /** Snapshot routing metadata before indexing, then validate every required logical member. */
    static Plan prepareArchive(List<RarArchiveReader.RarEntry> entries) throws IOException {
        return prepareArchive(entries, false, true);
    }

    private static Plan prepareArchive(List<RarArchiveReader.RarEntry> entries,
            boolean allowStoredOnly, boolean nativeAvailable) throws IOException {
        java.util.ArrayList<RarArchiveReader.RarEntry> snapshot = new java.util.ArrayList<>();
        java.util.IdentityHashMap<RarArchiveReader.RarEntry, RarArchiveReader.RarEntry> copies =
                new java.util.IdentityHashMap<>();
        for (RarArchiveReader.RarEntry entry : entries) {
            checkpoint(null);
            if (entry == null) continue;
            // Preserve identity aliases for the topology check; copying each occurrence
            // separately would disguise a repeated solid payload as a new member.
            RarArchiveReader.RarEntry copy = copies.get(entry);
            if (copy == null) { copy = Rar3DecodePlan.snapshot(entry); copies.put(entry, copy); }
            snapshot.add(copy);
        }
        RarVolumeChain.Index chains = RarVolumeChain.index(snapshot);
        java.util.ArrayList<Step> steps = new java.util.ArrayList<>();
        Step previousCompressed = null;
        boolean sawFile = false, sawCompressed = false, needsBridge = !nativeAvailable;
        long total = 0;
        for (RarArchiveReader.RarEntry entry : snapshot) {
            checkpoint(null);
            if (entry.rarVersion != 4) return null;
            if (entry.splitBefore) continue;
            if (entry.directory) {
                steps.add(new Step(entry, java.util.Collections.emptyList(), false));
                continue;
            }
            boolean stored = RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
            if ((!stored && (!RarFeatureClassifier.hasRar3DecoderVersion(entry)
                    || !RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method)))
                    || (entry.encrypted() && !entry.encryption.isRar4Aes())) return null;
            List<RarArchiveReader.RarEntry> parts = chains.chain(entry);
            long packed = Rar3PackedPayload.validateMetadata(entry, parts);
            Step step = new Step(entry, parts, stored);
            if (stored) {
                if (entry.splitAfter) RarSplitStoredPlan.fromChain(parts);
                if ((!entry.encrypted() && packed != entry.unpackedSize)
                        || (entry.encrypted() && packed < entry.unpackedSize)) {
                    throw new IOException("Stored RAR logical size mismatch");
                }
                // Stored members bypass the compressed decoder and retain its history.
            } else {
                if (packed <= 0 || packed > Long.MAX_VALUE / 8) {
                    throw new IOException("RAR3 logical packed size is outside supported bounds");
                }
                if (entry.solid) {
                    if (previousCompressed == null) return null;
                    previousCompressed.checked = step.checked = true;
                    previousCompressed.continuesSolid = true;
                    // The same logical history is needed for plain runs interrupted by
                    // stored members; encryption/volume splitting is not a prerequisite.
                    needsBridge = true;
                }
                previousCompressed = step;
                sawCompressed = true;
            }
            sawFile = true;
            needsBridge |= entry.encrypted() || entry.splitAfter;
            steps.add(step);
            total = entry.unpackedSize > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + entry.unpackedSize;
        }
        boolean compressedBridge = sawCompressed && needsBridge;
        boolean storedBridge = allowStoredOnly && sawFile && !sawCompressed && (needsBridge || !nativeAvailable);
        return compressedBridge || storedBridge ? new Plan(steps, total) : null;
    }

    /** Ordered one-pass decoder shared by logical bulk and verified archive streaming. */
    static final class Decoder {
        private final Plan plan;
        private final char[] password;
        private Rar3SolidState state;
        private int position;
        private boolean retired;
        Decoder(Plan plan, char[] password) {
            this.plan = plan; this.password = password == null ? null : password.clone();
        }
        void extractNext(File output, FileOperationProgress progress) throws IOException {
            if (retired || position >= plan.steps.size()) throw new IOException("RAR logical plan exhausted or retired");
            try {
                checkpoint(progress);
                Step step = plan.steps.get(position);
                RarArchiveReader.RarEntry entry = step.entry;
                if (entry.directory) {
                    if (output != null) RarOutputFileGuard.ensureDirectory(output);
                } else if (step.stored) {
                    // Copy/verify separately; do not reset or feed the compressed history.
                    if (output == null) throw new IOException("Stored RAR verification requires a destination");
                    RarArchiveReader.extractStoredEntry(entry, output, password, step.parts, progress);
                } else {
                    if (!entry.solid) state = step.checked ? new Rar3SolidState() : null;
                    try (Rar3PackedPayload payload = Rar3PackedPayload.open(entry, step.parts, password, progress)) {
                        Rar3UnpackContext context;
                        if (step.checked) context = Rar3UnpackContext.forLogicalSolidPayload(entry, payload, state);
                        else {
                            context = Rar3UnpackContext.forLogicalPayload(entry, payload);
                            payload.input.mark(2);
                            int first = payload.input.read(), second = payload.input.read();
                            payload.input.reset();
                            if (first < 0 || second < 0) throw new IOException("Truncated RAR compressed start");
                            if ((first & 0x80) != 0) {
                                if ((first & 0x20) == 0) throw new IOException("Missing independent RAR PPMd reset");
                                context.requireFileBoundary();
                            }
                        }
                        if (step.continuesSolid) context.allowPartialFilterCarry();
                        if (output == null) {
                            Rar3Unpacker.unpackToDiscard(context, progress);
                            checkRunEnd(step);
                        }
                        else try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
                            Rar3Unpacker.unpack(context, output, progress);
                            checkRunEnd(step);
                            checkpoint(progress);
                            guard.commit();
                        }
                    }
                }
                checkpoint(progress);
                // The payload, output close and final filter check all succeeded.
                // Keep mixed history across stored members only if a later solid
                // compressed member needs it; a retained viewer spool does not.
                if (!entry.directory && !step.stored && !step.continuesSolid) releaseState();
                position++;
            } catch (IOException | RuntimeException | Error failure) {
                retire(); throw failure;
            }
        }
        private void checkRunEnd(Step step) throws IOException {
            if (!step.continuesSolid && state != null) state.deferredFilters().requireEmpty();
        }
        private void releaseState() {
            if (state != null) state.invalidate();
            state = null;
        }
        void retire() {
            retired = true;
            releaseState();
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    static ArchiveSupport.ForwardArchiveReader openForwardReader(
            List<RarArchiveReader.RarEntry> entries, char[] password, File directory) throws IOException {
        return openForwardReader(entries, password, directory, false);
    }

    static ArchiveSupport.ForwardArchiveReader openForwardReader(
            List<RarArchiveReader.RarEntry> entries, char[] password, File directory,
            boolean nativeAvailable) throws IOException {
        if (directory == null) return null;
        Plan plan = prepareArchive(entries, true, nativeAvailable);
        return plan == null ? null : new LogicalForwardReader(plan, password, directory);
    }

    /** Archive-only verified streaming; no reader pagination policy lives here. */
    private static final class LogicalForwardReader implements ArchiveSupport.ForwardArchiveReader {
        private final Plan plan;
        private final Decoder decoder;
        private final File directory;
        private int index;
        private RarArchiveReader.RarEntry current;
        private Step currentStep;
        private File spool;
        private InputStream input;
        private long verifiedSize, remainingBytes;
        private boolean decoded;
        private boolean failed;
        private boolean closed;

        private LogicalForwardReader(Plan plan, char[] password, File directory) {
            this.plan = plan;
            this.decoder = new Decoder(plan, password);
            this.directory = directory;
        }

        private void checkpoint() throws IOException {
            if (closed || failed) throw new IOException("RAR checked forward reader is closed or failed");
            if (Thread.currentThread().isInterrupted()) {
                IOException failure = new java.io.InterruptedIOException("RAR extraction cancelled");
                retire(failure);
                throw failure;
            }
        }

        @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
            checkpoint();
            drainCurrentEntry(Long.MAX_VALUE);
            currentStep = index < plan.steps.size() ? plan.steps.get(index++) : null;
            current = currentStep == null ? null : currentStep.entry;
            decoded = false;
            return current == null ? null : new ArchiveSupport.ForwardEntry(
                    current.path, current.directory, !current.directory);
        }

        private void decode(boolean retain) throws IOException {
            checkpoint();
            if (current == null || decoded) return;
            try {
                if (current.directory) {
                    decoder.extractNext(null, null);
                    decoded = true;
                    return;
                }
                boolean stored = currentStep.stored;
                if (retain || stored) {
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("Cannot create RAR spool directory");
                    }
                    spool = File.createTempFile("rar3_logical_verified_", ".spool", directory);
                    decoder.extractNext(spool, null);
                    // Do not publish any bytes before the entire entry passes size/CRC checks.
                    if (retain) {
                        verifiedSize = remainingBytes = spool.length();
                        input = new BufferedInputStream(new FileInputStream(spool));
                    } else clearSpool();
                } else {
                    // Skipped compressed entries still advance and verify solid history,
                    // but do not need a decoded file or a whole-entry heap buffer.
                    decoder.extractNext(null, null);
                }
                decoded = true;
            } catch (IOException | RuntimeException | Error failure) {
                retire(failure);
                throw failure;
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
            if (current != null && !current.directory && !decoded && current.unpackedSize > maximum) {
                throw new IOException("RAR entry exceeds requested drain bound");
            }
            decode(false);
            try {
                clearSpool();
            } catch (IOException | RuntimeException | Error failure) {
                retire(failure);
                throw failure;
            }
            return true;
        }

        private void retire(Throwable failure) {
            failed = true;
            decoder.retire();
            try { clearSpool(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }

        private void clearSpool() throws IOException {
            Throwable failure = null;
            try { if (input != null) { input.close(); input = null; } }
            catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; }
            finally { remainingBytes = verifiedSize = 0; }
            if (spool != null) {
                try {
                    if (spool.exists() && !spool.delete()) throw new IOException("Cannot delete verified RAR spool");
                    spool = null;
                } catch (IOException | RuntimeException | Error cleanup) {
                    Throwable deletion = cleanup;
                    if (failure == null) failure = deletion;
                    else if (failure != deletion) failure.addSuppressed(deletion);
                }
            }
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
        }

        @Override public void close() throws IOException {
            if (closed && spool == null && input == null) return;
            closed = true;
            decoder.retire();
            clearSpool();
        }
    }

    /** Metadata only. Independent cases retain their existing routes. */
    static RarSolidReadPlan prepare(RarArchiveReader.RarEntry target,
            List<RarArchiveReader.RarEntry> entries) throws IOException {
        if (target == null || !target.solid) return null;
        RarSolidReadPlan plan = RarSolidReadPlan.prepare(target, entries, 4);
        if (plan == null || plan.steps.size() < 2) return null;
        for (RarSolidReadPlan.Step step : plan.steps) {
            checkpoint(null);
            RarArchiveReader.RarEntry entry = step.entry;
            if (!RarFeatureClassifier.hasRar3DecoderVersion(entry)
                    || !RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method)
                    || (entry.encrypted() && !entry.encryption.isRar4Aes())) return null;
        }
        // Stored members neither feed nor reset the compressed dictionary/model.
        for (RarSolidReadPlan.Step step : plan.steps) {
            long packed = Rar3PackedPayload.validateMetadata(step.entry, step.parts);
            if (packed <= 0 || packed > Long.MAX_VALUE / 8) {
                throw new IOException("RAR3 logical solid packed size is outside supported bounds");
            }
        }
        return plan;
    }

    private static void checkpoint(FileOperationProgress progress) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new InterruptedIOException("RAR extraction cancelled");
        }
    }
}
