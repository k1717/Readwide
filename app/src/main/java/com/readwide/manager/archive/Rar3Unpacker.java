package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * First-party RAR3/RAR4 compressed unpacker entry point.
 *
 * <p>Native libarchive remains primary. Production decoding streams LZ/PPMd blocks through
 * shared raw history and standard-filter output; the extractor restricts extended solid
 * admission to CRC/boundary-checked plain runs. Independent AES/split entries instead supply
 * a bounded logical stream without solid history. Mixed-mode and newly admitted input paths
 * still require real-fixture runtime validation. The old array PPMd adapter below is test-only.</p>
 */
final class Rar3Unpacker {

    private Rar3Unpacker() {}

    private static void checkpoint(FileOperationProgress progress) throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new java.io.InterruptedIOException("RAR extraction cancelled");
        }
    }

    private static OutputStream streamingTarget(RarDecodedOutput out, FileOperationProgress progress) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                checkpoint(progress);
                out.writeDecodedByte(value);
                if (progress != null) progress.addDoneBytes(1);
                checkpoint(progress);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                checkpoint(progress);
                out.writeDecodedBytes(bytes, offset, length);
                if (progress != null) progress.addDoneBytes(length);
                checkpoint(progress);
            }
        };
    }

    static void unpack(@NonNull Rar3UnpackContext context,
                       @NonNull File outFile,
                       @Nullable FileOperationProgress progress) throws IOException {
        unpackToFile(context, outFile, progress, true);
    }

    @NonNull
    static Rar3UnpackFileResult unpackForDiagnostics(@NonNull Rar3UnpackContext context,
                                                     @NonNull File outFile,
                                                     @Nullable FileOperationProgress progress) throws IOException {
        return unpackToFile(context, outFile, progress, false);
    }

    @NonNull
    static Rar3UnpackFileResult unpackSolidPrimerToDiscard(@NonNull Rar3UnpackContext context,
                                                           @Nullable FileOperationProgress progress) throws IOException {
        if (!context.solid) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 discard primer requires a solid-sequence context");
        }
        return unpackToDiscard(context, progress);
    }

    /** Verified discard also serves independent files that do not retain solid history. */
    @NonNull
    static Rar3UnpackFileResult unpackToDiscard(@NonNull Rar3UnpackContext context,
                                               @Nullable FileOperationProgress progress) throws IOException {
        return unpackToDecodedOutput(context, RarCrcDecodedOutput.discarding(), progress, true);
    }

    @NonNull
    private static Rar3UnpackFileResult unpackToFile(@NonNull Rar3UnpackContext context,
                                                     @NonNull File outFile,
                                                     @Nullable FileOperationProgress progress,
                                                     boolean failOnCrcMismatch) throws IOException {
        context.ensureUsable();
        checkpoint(progress);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create RAR output directory");
        }

        boolean success = false;
        try (OutputStream raw = ArchiveSupport.openExtractionOutputStream(outFile)) {
            RarCrcDecodedOutput checked = new RarCrcDecodedOutput(RarOutputStreamDecodedOutput.wrapOrMemory(raw));
            Rar3UnpackFileResult fileResult = unpackToDecodedOutput(
                    context, checked, progress, failOnCrcMismatch);
            success = true;
            return fileResult;
        } catch (IOException | RuntimeException | Error failure) {
            success = false;
            context.invalidateSolidState();
            throw failure;
        } finally {
            if (!success && outFile.exists() && !outFile.delete()) {
                // Best-effort cleanup. Leaving a partial file is worse than surfacing the original
                // decode error, but deletion failure should not mask the real cause.
            }
        }
    }

    @NonNull
    private static Rar3UnpackFileResult unpackToDecodedOutput(@NonNull Rar3UnpackContext context,
                                                              @NonNull RarCrcDecodedOutput checked,
                                                              @Nullable FileOperationProgress progress,
                                                              boolean failOnCrcMismatch) throws IOException {
        try {
            context.ensureUsable();
            return decodeAndCheck(context, checked, progress, failOnCrcMismatch);
        } catch (IOException | RuntimeException | Error failure) {
            context.invalidateSolidState();
            throw failure;
        }
    }

    @NonNull
    private static Rar3UnpackFileResult decodeAndCheck(@NonNull Rar3UnpackContext context,
                                                       @NonNull RarCrcDecodedOutput checked,
                                                       @Nullable FileOperationProgress progress,
                                                       boolean failOnCrcMismatch) throws IOException {
        checkpoint(progress);
        context.resetWindow();
        if (context.windowSize() <= 0 || (!context.solid && context.writePosition() != 0)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid RAR3/RAR4 unpacker state");
        }
        Rar3DecodeResult result;
        try (java.io.BufferedInputStream packed = context.openPackedPayload(progress)) {
            boolean reuse = context.reuseClassicTables();
            context.setReuseClassicTables(false);
            result = unpackStreamingPayload(context, new RarBitInput(packed, context.packedSize),
                    checked, !failOnCrcMismatch, reuse, progress);
            context.finishPackedPayload(progress);
        }
        if (result.written != context.unpackedSize || checked.written() != context.unpackedSize) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 first-party unpacker did not reach the declared unpacked size");
        }
        Rar3UnpackFileResult fileResult = new Rar3UnpackFileResult(
                result.written,
                result.bitsRead,
                result.blocks,
                checked.written(),
                checked.crcValue(),
                context.hasExpectedCrc(),
                context.hasExpectedCrc() ? context.expectedCrc() : -1L,
                result.classicLzTrace);
        if (!fileResult.crcMatches()) {
            context.invalidateSolidState();
            if (failOnCrcMismatch) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3/RAR4 first-party unpacker decoded the payload but CRC did not match; real compressed fixture support remains incomplete");
            }
        }
        return fileResult;
    }

    @NonNull
    static Rar3DecodeResult unpackPayloadForTest(@NonNull Rar3UnpackContext context,
                                                 @NonNull byte[] packed,
                                                 @NonNull OutputStream out) throws IOException {
        try {
            context.ensureUsable();
            context.resetWindow();
            return unpackPayload(context, packed, RarOutputStreamDecodedOutput.wrapOrMemory(out), null, false);
        } catch (IOException | RuntimeException | Error failure) {
            context.invalidateSolidState();
            throw failure;
        }
    }

    @NonNull
    private static Rar3DecodeResult unpackPayload(@NonNull Rar3UnpackContext context,
                                                  @NonNull byte[] packed,
                                                  @NonNull RarDecodedOutput out,
                                                  @Nullable FileOperationProgress progress,
                                                  boolean collectClassicLzTrace) throws IOException {
        checkpoint(progress);
        boolean reuseClassicTables = context.reuseClassicTables();
        context.setReuseClassicTables(false);
        // A table-less solid payload starts with Huffman data, not a PPMd mode bit.
        if (!reuseClassicTables) {
            Rar3PpmdBlockHeader ppmdHeader = Rar3PpmdBlockHeader.fromPackedPayload(packed);
            if (ppmdHeader.isPpmd()) {
                return unpackPpmdPayload(context, packed, out, progress, ppmdHeader);
            }
        }

        return unpackStreamingPayload(context, new RarBitInput(packed), out,
                collectClassicLzTrace, reuseClassicTables, progress);
    }

    @NonNull
    private static Rar3DecodeResult unpackStreamingPayload(@NonNull Rar3UnpackContext context,
                                                         @NonNull RarBitInput input,
                                                         @NonNull RarDecodedOutput out,
                                                         boolean collectClassicLzTrace,
                                                         boolean reuseClassicTables,
                                                         @Nullable FileOperationProgress progress) throws IOException {
        long limit = Math.max(0L, context.unpackedSize);

        OutputStream target = streamingTarget(out, progress);
        // Only pending filter regions are retained; normal bytes flow to the checked sink.
        // This stream never closes/commits the caller's file. Failure leaves cleanup to its guard.
        try (Rar3PpmdFilterOutput filtered = new Rar3PpmdFilterOutput(
                new java.io.BufferedOutputStream(target, 64 * 1024), limit, context.vmFilterState(),
                4 * 1024 * 1024, context.deferredFilters())) {
            OutputStream clipped = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    // Preserve legacy final-match dictionary surplus, without publishing it.
                    if (filtered.rawBytesUntilBoundary() > 0) filtered.write(value);
                    else if (filtered.needsInput()) throw new IOException("RAR3 filter starts beyond final entry size");
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (bytes == null) throw new NullPointerException("bytes");
                    if ((offset | length) < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException();
                    while (length > 0) {
                        int count = (int) Math.min(length, filtered.rawBytesUntilBoundary());
                        if (count == 0) {
                            if (filtered.needsInput()) throw new IOException("RAR3 filter starts beyond final entry size");
                            return; // Legacy final LZ match surplus stays in raw history.
                        }
                        filtered.write(bytes, offset, count);
                        offset += count; length -= count;
                    }
                }
            };
            RarLzWindow window = context.openWindow(clipped);
            Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decodeMixed(input, window, limit,
                    context.oldTableLengths(), context.vmFilterState(), context.state(),
                    context.mixedPpmd(), filtered, reuseClassicTables,
                    context.solid || context.requiresFileBoundary());
            if (context.requiresFileBoundary() && !engine.fileEndSeen()) {
                throw new IOException("RAR3 solid fallback requires an explicit file boundary");
            }
            filtered.finish(engine.fileEndSeen());
            checkpoint(progress);
            System.arraycopy(engine.tableState(), 0, context.oldTableLengths(), 0,
                    Math.min(engine.tableState().length, context.oldTableLengths().length));
            context.saveWindow(window);
            context.setReuseClassicTables(engine.reuseTablesForNextEntry());
            return new Rar3DecodeResult(filtered.outputBytes(), input.bitsRead(),
                    Math.max(1, engine.tableReads()),
                    collectClassicLzTrace ? new Rar3ClassicLzStateTrace().snapshot() : null);
        }
    }


    @NonNull
    private static Rar3DecodeResult unpackPpmdPayload(@NonNull Rar3UnpackContext context,
                                                       @NonNull byte[] packed,
                                                       @NonNull RarDecodedOutput out,
                                                       @Nullable FileOperationProgress progress,
                                                       @NonNull Rar3PpmdBlockHeader ppmdHeader) throws IOException {
        checkpoint(progress);
        long limit = Math.max(0L, context.unpackedSize);
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream(
                (int) Math.min(Math.max(limit, 0L), 1 << 24));
        RarLzWindow window = context.openWindow(collected);
        try {
            RarPpmdByteInput.ArrayInput ppmdInput = new RarPpmdByteInput.ArrayInput(
                    packed,
                    ppmdHeader.payloadOffset(),
                    Math.max(0, packed.length - ppmdHeader.payloadOffset()));
            Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                    ppmdInput,
                    context.ppmdState(),
                    ppmdHeader);
            Rar3PpmdBlockDecoder.decodeUntilControlOrLimit(
                    source,
                    window,
                    context.state(),
                    context.ppmdState(),
                    limit);
            byte[] partial = collected.toByteArray();
            out.writeDecodedBytes(partial, 0, partial.length);
            return new Rar3DecodeResult(partial.length, 0, 1, null);
        } finally {
            context.saveWindow(window);
        }
    }


}
