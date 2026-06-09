package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * First-party RAR3/RAR4 compressed unpacker entry point.
 *
 * <p>This is deliberately still narrower than a support claim for real-world compressed RAR.
 * Normal compressed RAR remains owned by libarchive until this decoder can pass broader
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
    private static Rar3UnpackFileResult unpackToFile(@NonNull Rar3UnpackContext context,
                                                     @NonNull File outFile,
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
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create RAR output directory");
        }

        boolean success = false;
        CRC32 crc32 = new CRC32();
        try (OutputStream raw = new FileOutputStream(outFile);
             CheckedOutputStream checked = new CheckedOutputStream(raw, crc32)) {
            Rar3DecodeResult result = unpackPayload(context, packed, checked, progress, !failOnCrcMismatch);
            if (result.written != context.unpackedSize) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3/RAR4 first-party unpacker did not reach the declared unpacked size");
            }
            long actual = crc32.getValue() & 0xffffffffL;
            Rar3UnpackFileResult fileResult = new Rar3UnpackFileResult(
                    result.written,
                    result.bitsRead,
                    result.blocks,
                    outFile.length(),
                    actual,
                    context.hasExpectedCrc(),
                    context.hasExpectedCrc() ? context.expectedCrc() : -1L,
                    result.classicLzTrace);
            if (failOnCrcMismatch && !fileResult.crcMatches()) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3/RAR4 first-party unpacker decoded the payload but CRC did not match; real compressed fixture support remains incomplete");
            }
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
    static Rar3DecodeResult unpackPayloadForTest(@NonNull Rar3UnpackContext context,
                                                 @NonNull byte[] packed,
                                                 @NonNull OutputStream out) throws IOException {
        context.resetWindow();
        return unpackPayload(context, packed, out, null, false);
    }

    @NonNull
    private static Rar3DecodeResult unpackPayload(@NonNull Rar3UnpackContext context,
                                                  @NonNull byte[] packed,
                                                  @NonNull OutputStream out,
                                                  @Nullable FileOperationProgress progress,
                                                  boolean collectClassicLzTrace) throws IOException {
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
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
        out.write(output);

        return new Rar3DecodeResult(output.length, input.bitsRead(), 1,
                collectClassicLzTrace ? new Rar3ClassicLzStateTrace().snapshot() : null);
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
