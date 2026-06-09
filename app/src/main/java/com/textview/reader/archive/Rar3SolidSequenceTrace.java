package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Per-entry diagnostic trace for the narrow RAR3/RAR4 classic-LZ solid sequence probe. */
final class Rar3SolidSequenceTrace {
    enum EntryStatus {
        SUCCESS,
        CRC_MISMATCH,
        GAP,
        FAILED,
        NOT_RUN
    }

    static final class EntryTrace {
        @NonNull final String path;
        final boolean target;
        final boolean solid;
        final int method;
        final boolean initializedBefore;
        final int writePositionBefore;
        final boolean initializedAfter;
        final int writePositionAfter;
        final EntryStatus status;
        final long written;
        final long outputSize;
        final long actualCrc;
        final long expectedCrc;
        final long bitsRead;
        final int blocks;
        @NonNull final Rar3SolidCarryoverCheck carryover;
        @NonNull final Rar3ClassicLzCrcMismatchAnalysis classicLz;
        @NonNull final Rar3ClassicLzBoundaryCheck classicLzBoundary;
        @Nullable final Rar3ClassicLzStateTrace.Snapshot classicLzTrace;
        @NonNull final String detail;

        private EntryTrace(@NonNull RarArchiveReader.RarEntry entry,
                           boolean target,
                           boolean initializedBefore,
                           int writePositionBefore,
                           boolean initializedAfter,
                           int writePositionAfter,
                           @NonNull EntryStatus status,
                           long written,
                           long outputSize,
                           long actualCrc,
                           long expectedCrc,
                           long bitsRead,
                           int blocks,
                           @Nullable Rar3ClassicLzStateTrace.Snapshot classicLzTrace,
                           @NonNull String detail) {
            this.path = entry.path;
            this.target = target;
            this.solid = entry.solid;
            this.method = entry.method;
            this.initializedBefore = initializedBefore;
            this.writePositionBefore = writePositionBefore;
            this.initializedAfter = initializedAfter;
            this.writePositionAfter = writePositionAfter;
            this.status = status;
            this.written = written;
            this.outputSize = outputSize;
            this.actualCrc = actualCrc;
            this.expectedCrc = expectedCrc;
            this.bitsRead = bitsRead;
            this.blocks = blocks;
            this.classicLzTrace = classicLzTrace;
            this.carryover = Rar3SolidCarryoverCheck.analyze(
                    target,
                    entry.solid,
                    status,
                    initializedBefore,
                    writePositionBefore,
                    initializedAfter,
                    writePositionAfter,
                    written);
            this.classicLz = Rar3ClassicLzCrcMismatchAnalysis.analyze(
                    status,
                    this.carryover,
                    written,
                    outputSize,
                    actualCrc,
                    expectedCrc,
                    blocks,
                    bitsRead,
                    detail);
            this.classicLzBoundary = Rar3ClassicLzBoundaryCheck.analyze(this.classicLz, classicLzTrace);
            this.detail = detail;
        }
    }

    @Nullable final Rar3SolidSequencePlan plan;
    @NonNull private final List<EntryTrace> entries;
    final boolean completedTarget;
    @NonNull final String detail;

    private Rar3SolidSequenceTrace(@Nullable Rar3SolidSequencePlan plan,
                                   @NonNull List<EntryTrace> entries,
                                   boolean completedTarget,
                                   @NonNull String detail) {
        this.plan = plan;
        this.entries = Collections.unmodifiableList(entries);
        this.completedTarget = completedTarget;
        this.detail = detail;
    }

    @NonNull
    static Rar3SolidSequenceTrace capture(@NonNull List<RarArchiveReader.RarEntry> archiveEntries,
                                          @NonNull RarArchiveReader.RarEntry target,
                                          @NonNull File workDir,
                                          @Nullable FileOperationProgress progress) {
        Rar3SolidSequencePlan plan = Rar3SolidSequencePlan.forTarget(archiveEntries, target);
        if (plan == null) {
            return new Rar3SolidSequenceTrace(null,
                    Collections.<EntryTrace>emptyList(),
                    false,
                    "no validated RAR3/RAR4 classic-LZ solid sequence plan");
        }
        if (!workDir.exists() && !workDir.mkdirs()) {
            return new Rar3SolidSequenceTrace(plan,
                    Collections.<EntryTrace>emptyList(),
                    false,
                    "could not create solid trace work directory");
        }

        Rar3SolidState solidState = new Rar3SolidState();
        List<EntryTrace> rows = new ArrayList<>();
        boolean completed = false;
        int index = 0;
        for (RarArchiveReader.RarEntry entry : plan.sequenceEntries()) {
            if (!entry.solid) solidState.reset();
            boolean beforeInitialized = solidState.initialized();
            int beforePosition = solidState.writePosition();
            File out = new File(workDir, "solid-trace-" + index++ + ".bin");
            deleteIfExists(out);
            EntryStatus status;
            long written = -1L;
            long outputSize = -1L;
            long actualCrc = -1L;
            long expectedCrc = entry.dataCrc >= 0L ? entry.dataCrc & 0xffffffffL : -1L;
            long bitsRead = -1L;
            int blocks = -1;
            String detail;
            try {
                Rar3UnpackContext context = Rar3UnpackContext.forSolidSequenceEntry(
                        entry.sourceArchive,
                        entry.dataOffset,
                        entry.packedSize,
                        entry.unpackedSize,
                        entry.method,
                        entry.splitBefore,
                        entry.splitAfter,
                        false,
                        entry.dataCrc,
                        solidState);
                Rar3UnpackFileResult result = Rar3Unpacker.unpackForDiagnostics(context, out, progress);
                written = result.written;
                outputSize = result.outputSize;
                actualCrc = result.actualCrc;
                expectedCrc = result.hasExpectedCrc ? result.expectedCrc : expectedCrc;
                bitsRead = result.bitsRead;
                blocks = result.blocks;
                Rar3ClassicLzStateTrace.Snapshot classicLzTrace = result.classicLzTrace;
                status = result.crcMatches() ? EntryStatus.SUCCESS : EntryStatus.CRC_MISMATCH;
                detail = result.crcMatches() ? "decoded" : "decoded but CRC mismatch";
                if (classicLzTrace != null) detail += "; " + classicLzTrace.compact();
                rows.add(new EntryTrace(entry,
                        entry == target,
                        beforeInitialized,
                        beforePosition,
                        solidState.initialized(),
                        solidState.writePosition(),
                        status,
                        written,
                        outputSize,
                        actualCrc,
                        expectedCrc,
                        bitsRead,
                        blocks,
                        classicLzTrace,
                        detail));
                deleteIfExists(out);
                if (entry == target && status == EntryStatus.SUCCESS) {
                    completed = true;
                    break;
                }
                if (status != EntryStatus.SUCCESS) break;
                continue;
            } catch (RarArchiveReader.UnsupportedRarFeatureException e) {
                status = EntryStatus.GAP;
                detail = message(e);
            } catch (IOException | RuntimeException e) {
                status = EntryStatus.FAILED;
                detail = message(e);
            }
            boolean afterInitialized = solidState.initialized();
            int afterPosition = solidState.writePosition();
            rows.add(new EntryTrace(entry,
                    entry == target,
                    beforeInitialized,
                    beforePosition,
                    afterInitialized,
                    afterPosition,
                    status,
                    written,
                    outputSize,
                    actualCrc,
                    expectedCrc,
                    bitsRead,
                    blocks,
                    null,
                    detail));
            deleteIfExists(out);
            if (entry == target && status == EntryStatus.SUCCESS) {
                completed = true;
                break;
            }
            if (status != EntryStatus.SUCCESS) break;
        }
        return new Rar3SolidSequenceTrace(plan,
                rows,
                completed,
                "entries=" + rows.size() + "; completedTarget=" + completed + "; " + plan.detail);
    }

    @NonNull
    List<EntryTrace> entries() {
        return entries;
    }

    @NonNull
    String compactSummary() {
        if (entries.isEmpty()) return detail;
        EntryTrace last = entries.get(entries.size() - 1);
        return "solidTrace entries=" + entries.size()
                + "; completedTarget=" + completedTarget
                + "; last=" + last.path
                + "; status=" + last.status
                + "; beforePos=" + last.writePositionBefore
                + "; afterPos=" + last.writePositionAfter
                + "; written=" + last.written
                + "; crc=" + hex(last.actualCrc)
                + "; expected=" + hex(last.expectedCrc)
                + "; carryover=" + last.carryover.status
                + "; classicLz=" + last.classicLz.status
                + "; boundary=" + last.classicLzBoundary.status
                + "; next=" + last.classicLzBoundary.nextStep
                + "; lzState=" + (last.classicLzTrace == null ? "-" : last.classicLzTrace.markdownStatus())
                + "; detail=" + last.detail;
    }

    @NonNull
    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Entry | Target | Solid | Method | Status | Init before | Pos before | Init after | Pos after | Written | CRC32 | Expected CRC32 | Blocks | Bits read | Carryover | Classic-LZ diagnosis | Boundary check | LZ state trace | Detail |\n");
        sb.append("|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---|\n");
        for (EntryTrace row : entries) {
            sb.append("| ").append(escape(row.path))
                    .append(" | ").append(row.target)
                    .append(" | ").append(row.solid)
                    .append(" | ").append(String.format(Locale.US, "0x%02x", row.method & 0xff))
                    .append(" | ").append(row.status)
                    .append(" | ").append(row.initializedBefore)
                    .append(" | ").append(row.writePositionBefore)
                    .append(" | ").append(row.initializedAfter)
                    .append(" | ").append(row.writePositionAfter)
                    .append(" | ").append(row.written)
                    .append(" | ").append(hex(row.actualCrc))
                    .append(" | ").append(hex(row.expectedCrc))
                    .append(" | ").append(row.blocks)
                    .append(" | ").append(row.bitsRead)
                    .append(" | ").append(escape(row.carryover.markdownStatus()))
                    .append(" | ").append(escape(row.classicLz.markdownStatus()))
                    .append(" | ").append(escape(row.classicLzBoundary.markdownStatus()))
                    .append(" | ").append(escape(row.classicLzTrace == null ? "-" : row.classicLzTrace.markdownStatus()))
                    .append(" | ").append(escape(row.detail))
                    .append(" |\n");
        }
        return sb.toString();
    }

    @NonNull
    private static String message(@NonNull Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }

    private static void deleteIfExists(@NonNull File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    @NonNull
    private static String hex(long crc) {
        return crc < 0L ? "-" : String.format(Locale.US, "0x%08x", crc & 0xffffffffL);
    }

    @NonNull
    private static String escape(@Nullable String text) {
        if (text == null || text.length() == 0) return "-";
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
