package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * First-party RAR3/RAR4 compressed unpacker entry point.
 *
 * <p>This is deliberately still narrower than a support claim for real-world compressed RAR.
 * Normal compressed RAR remains owned by libarchive until this decoder has broader
 * CRC-verified real fixture coverage. The first-party path can now complete synthetic
 * classic-LZ blocks and a small set of real non-solid classic-LZ fixtures, write output, stop at
 * the declared unpacked size, and validate CRC where the caller supplies one.</p>
 */
final class Rar3Unpacker {

    private Rar3Unpacker() {}

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
        return unpackToDecodedOutput(context, RarCrcDecodedOutput.discarding(), progress, true);
    }

    @NonNull
    private static Rar3UnpackFileResult unpackToFile(@NonNull Rar3UnpackContext context,
                                                     @NonNull File outFile,
                                                     @Nullable FileOperationProgress progress,
                                                     boolean failOnCrcMismatch) throws IOException {
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create RAR output directory");
        }

        boolean success = false;
        try (OutputStream raw = new FileOutputStream(outFile)) {
            RarCrcDecodedOutput checked = new RarCrcDecodedOutput(RarOutputStreamDecodedOutput.wrapOrMemory(raw));
            Rar3UnpackFileResult fileResult = unpackToDecodedOutput(
                    context, checked, progress, failOnCrcMismatch);
            success = true;
            return fileResult;
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
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        context.resetWindow();
        if (context.windowSize() <= 0 || (!context.solid && context.writePosition() != 0)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid RAR3/RAR4 unpacker state");
        }
        byte[] packed = context.readPackedPayload();
        if (packed.length != context.packedSize) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3/RAR4 compressed payload read length mismatch");
        }
        Rar3DecodeResult result = unpackPayload(context, packed, checked, progress, !failOnCrcMismatch);
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
        if (failOnCrcMismatch && !fileResult.crcMatches()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3/RAR4 first-party unpacker decoded the payload but CRC did not match; real compressed fixture support remains incomplete");
        }
        return fileResult;
    }

    @NonNull
    static Rar3DecodeResult unpackPayloadForTest(@NonNull Rar3UnpackContext context,
                                                 @NonNull byte[] packed,
                                                 @NonNull OutputStream out) throws IOException {
        context.resetWindow();
        return unpackPayload(context, packed, RarOutputStreamDecodedOutput.wrapOrMemory(out), null, false);
    }

    @NonNull
    private static Rar3DecodeResult unpackPayload(@NonNull Rar3UnpackContext context,
                                                  @NonNull byte[] packed,
                                                  @NonNull RarDecodedOutput out,
                                                  @Nullable FileOperationProgress progress,
                                                  boolean collectClassicLzTrace) throws IOException {
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        Rar3PpmdBlockHeader ppmdHeader = Rar3PpmdBlockHeader.fromPackedPayload(packed);
        if (ppmdHeader.isPpmd()) {
            return unpackPpmdPayload(context, packed, out, progress, ppmdHeader);
        }

        RarBitInput input = new RarBitInput(packed);
        long limit = Math.max(0L, context.unpackedSize);

        // The verified classic-LZ engine needs random access to its own output to apply VM filters.
        // Decode into an in-memory buffer (bounded by the declared unpacked size), apply any
        // standard VM filters, then forward the filtered bytes to the real output. Solid dictionary
        // continuity is preserved by seeding the window via the shared context window.
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream(
                (int) Math.min(Math.max(limit, 0L), 1 << 24));
        RarLzWindow window = context.openWindow(collected);

        Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decode(
                input, window, limit, context.oldTableLengths());

        // Persist table state for solid keep-old-table continuity.
        System.arraycopy(engine.tableState(), 0, context.oldTableLengths(), 0,
                Math.min(engine.tableState().length, context.oldTableLengths().length));
        context.saveWindow(window);

        byte[] output = collected.toByteArray();
        if (engine.hasFilters()) {
            output = applyFilters(output, engine.filters());
        }
        out.writeDecodedBytes(output, 0, output.length);

        return new Rar3DecodeResult(output.length, input.bitsRead(), 1,
                collectClassicLzTrace ? new Rar3ClassicLzStateTrace().snapshot() : null);
    }


    @NonNull
    private static Rar3DecodeResult unpackPpmdPayload(@NonNull Rar3UnpackContext context,
                                                       @NonNull byte[] packed,
                                                       @NonNull RarDecodedOutput out,
                                                       @Nullable FileOperationProgress progress,
                                                       @NonNull Rar3PpmdBlockHeader ppmdHeader) throws IOException {
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
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

    /** Applies pending standard VM filters to the decoded output region(s), in decode order. */
    @NonNull
    private static byte[] applyFilters(@NonNull byte[] data,
                                       @NonNull java.util.List<Rar3VmFilter.PendingFilter> filters) throws IOException {
        for (Rar3VmFilter.PendingFilter f : filters) {
            if (f.type == Rar3VmFilter.StandardFilter.NONE) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3 non-standard VM filter is not supported");
            }
            int start = (int) f.blockStartAbs;
            int len = f.blockLength;
            if (start < 0 || len < 0 || start + len > data.length) {
                throw new IOException("RAR3 filter block out of range");
            }
            byte[] region = java.util.Arrays.copyOfRange(data, start, start + len);
            int[] r = f.initR.clone();
            r[4] = len;
            long fileOffset = f.initR[6] & 0xffffffffL;
            byte[] filtered = Rar3VmFilter.apply(f.type, region, len, r, fileOffset);
            System.arraycopy(filtered, 0, data, start, Math.min(filtered.length, len));
        }
        return data;
    }

}
