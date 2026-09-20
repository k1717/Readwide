package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.zip.CRC32;

import com.readwide.manager.util.FileOperationProgress;

/**
 * First-party 7z reader for the coder chains Apache Commons Compress cannot
 * decode: folders containing the BCJ2 branch filter (coder id
 * {@code 03 03 01 1B}), a four-input coder Commons Compress rejects with
 * "Multi input/output stream coders are not yet supported", and folders
 * containing PPMd (id {@code 03 04 01}), ARM64 ({@code 0A}), RISC-V ({@code 0B}),
 * Swap2 ({@code 02 03 02}) or Swap4 ({@code 02 03 04}),
 * which Commons Compress has no 7z decoder mapping for. Such folders occur both
 * plain and under AES encryption; in the AES case Commons Compress decrypts correctly but then fails on the
 * inner coder, and the bundled libarchive cannot decrypt 7z at all, so
 * AES+BCJ2 and AES+PPMd archives were previously unsupported end to end.
 *
 * <p>This reader parses the 7z header itself (clean-room, from the documented
 * container format; no 7-Zip source is used), resolves each folder's coder
 * dependency graph, decodes the base streams with the app's bundled decoders
 * (LZMA/LZMA2 via xz-java; shared codec definitions in {@link SevenZCoderRegistry};
 * the Copy coder is a pass-through), decrypts AES
 * streams with {@link SevenZAesDecoder}, and applies {@link SevenZBcj2Decoder}
 * for the BCJ2 join and {@link SevenZPpmd7Decoder} for PPMd streams. Folders
 * whose coders are fully handled by Commons Compress are left to it; this
 * path is only taken as a fallback, gated on the archive actually containing
 * a BCJ2, PPMd, ARM64, RISC-V, Swap2 or Swap4 folder (including encoded headers). Unimplemented
 * coders raise a clear unsupported error rather than guessing.</p>
 */
final class SevenZBcj2ArchiveReader {
    @Nullable
    static ArchiveSupport.ForwardArchiveReader openSpecialForwardReader(File archive,
            char[] password, File spoolDirectory) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("7z extraction cancelled");
        if (SevenZSplitVolumeResolver.isSevenZSplitPart(archive)) {
            return openSplitSpecialForwardReader(archive, password, spoolDirectory);
        }
        SingleSourceStamp stamp = new SingleSourceStamp(archive);
        SevenZArchive parsed;
        try { parsed = parse(archive, password); }
        catch (IntegrityException failure) { throw failure; }
        catch (IOException unsupportedHeader) {
            if (Thread.currentThread().isInterrupted()) throw unsupportedHeader;
            stamp.check(archive);
            return null; // Keep Commons' broader header support.
        }
        stamp.check(archive);
        return parsed.usesSpecialCoder()
                ? new ForwardReader(archive, parsed, password, spoolDirectory, null, null, stamp) : null;
    }

    @Nullable
    private static ArchiveSupport.ForwardArchiveReader openSplitSpecialForwardReader(
            File archive, char[] password, File directory) throws IOException {
        ArchiveSourceSnapshot snapshot = ArchiveSourceSnapshot.capture(archive);
        SevenZSplitVolumeResolver.VolumeSet volumes = SevenZSplitVolumeResolver.resolve(archive);
        if (volumes == null || snapshot == null || !snapshot.matches(archive)) {
            throw new IOException("7z split source changed or is unavailable");
        }
        List<SplitVolumeInput.Segment> segments = new ArrayList<>();
        for (File part : volumes.parts) segments.add(new SplitVolumeInput.Segment(part, 0, part.length()));
        SplitVolumeInput source = new SplitVolumeInput(segments);
        boolean transferred = false;
        try {
            SevenZArchive parsed;
            try { parsed = parse(archive, password, source); }
            catch (IntegrityException failure) { throw failure; }
            catch (IOException unsupportedHeader) {
                if (Thread.currentThread().isInterrupted()) throw unsupportedHeader;
                return null;
            }
            if (!snapshot.matches(archive)) throw new IOException("7z split source changed while reading header");
            if (!parsed.usesSpecialCoder()) return null;
            ForwardReader result = new ForwardReader(archive, parsed, password, directory, source, snapshot, null);
            transferred = true;
            return result;
        } finally {
            if (!transferred) source.close();
        }
    }

    /** Metadata identity check, not a content hash or a lock against concurrent writers. */
    private static final class SingleSourceStamp {
        private final String path;
        private final long size, modified;
        SingleSourceStamp(File file) throws IOException {
            path = file.getCanonicalPath();
            size = file.length();
            modified = file.lastModified();
            check(file);
        }
        void check(File file) throws IOException {
            if (!file.isFile() || !file.canRead() || !path.equals(file.getCanonicalPath())
                    || size != file.length() || modified != file.lastModified()) {
                throw new IOException("7z source changed or is unavailable");
            }
        }
    }

    /** A folder is fully checked once, then all its entry slices share the disk spool. */
    private static final class ForwardReader implements ArchiveSupport.ForwardArchiveReader {
        private final File archive, directory;
        private final SevenZArchive parsed;
        private final char[] password;
        private final SplitVolumeInput splitSource;
        private final ArchiveSourceSnapshot sourceSnapshot;
        private final SingleSourceStamp singleSourceStamp;
        private int index, folderIndex = -1;
        private FileEntry current;
        private File spool;
        private RandomAccessFile input;
        private long remaining, verifiedFolderSize;
        private boolean closed, failed;
        ForwardReader(File archive, SevenZArchive parsed, char[] password, File directory,
                      SplitVolumeInput splitSource, ArchiveSourceSnapshot sourceSnapshot,
                      SingleSourceStamp singleSourceStamp) {
            this.archive = archive; this.parsed = parsed; this.directory = directory;
            this.password = password == null ? null : password.clone();
            this.splitSource = splitSource; this.sourceSnapshot = sourceSnapshot;
            this.singleSourceStamp = singleSourceStamp;
        }
        private void checkpoint() throws IOException {
            if (closed || failed) throw new IOException("7z forward reader is closed or failed");
            if (Thread.currentThread().isInterrupted()) throw new IOException("7z extraction cancelled");
        }
        @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
            try { return advance(); }
            catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
        }
        private ArchiveSupport.ForwardEntry advance() throws IOException {
            checkpoint();
            current = index < parsed.files.size() ? parsed.files.get(index++) : null;
            remaining = current == null ? 0 : current.size;
            if (current == null) {
                clearSpool();
                if (splitSource != null) splitSource.close();
                if (password != null) Arrays.fill(password, '\0');
                return null;
            }
            return new ArchiveSupport.ForwardEntry(ArchiveSupport.sanitizeEntryPathForList(current.name),
                    current.isDirectory, !current.isDirectory);
        }
        private void prepareCurrent() throws IOException {
            if (current == null || current.folderIndex < 0) return;
            try {
                if (folderIndex != current.folderIndex) {
                    clearSpool();
                    checkSourceSnapshot();
                    if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create 7z spool directory");
                    spool = File.createTempFile("sevenz_verified_folder_", ".spool", directory);
                    try (FolderStream folder = openFolder(archive, parsed, current.folderIndex, password, null, splitSource);
                         OutputStream output = ArchiveSupport.openExtractionOutputStream(spool)) {
                        folder.transfer(folder.size, output);
                        folder.drain();
                    }
                    checkSourceSnapshot();
                    input = new RandomAccessFile(spool, "r");
                    verifiedFolderSize = parsed.folders.get(current.folderIndex).getUnpackSize();
                    folderIndex = current.folderIndex;
                }
                // The verified folder is reused across entries; do not expose even a
                // surviving prefix if its backing file has since changed size.
                if (input.length() != verifiedFolderSize) {
                    throw new EOFException("Truncated or changed verified 7z spool");
                }
                input.seek(checkedStreamSum(current.offsetInFolder, current.size - remaining));
            } catch (IOException | RuntimeException failure) {
                failed = true;
                try { clearSpool(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public int read(byte[] buffer) throws IOException {
            try { return readCurrent(buffer); }
            catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
        }
        private int readCurrent(byte[] buffer) throws IOException {
            checkpoint();
            if (buffer.length == 0) return 0;
            if (current == null || current.isDirectory) return -1;
            prepareCurrent(); // Also validates a zero-length entry's folder.
            if (remaining == 0) return -1;
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("Truncated verified 7z spool");
            remaining -= count;
            return count;
        }
        @Override public boolean drainCurrentEntry(long maximum) throws IOException {
            try {
                checkpoint();
                if (maximum < 0 || remaining > maximum) throw new IOException("7z entry exceeds requested drain bound");
                remaining = 0;
                return true;
            } catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
        }
        private void checkSourceSnapshot() throws IOException {
            if (singleSourceStamp != null) singleSourceStamp.check(archive);
            if (sourceSnapshot != null && !sourceSnapshot.matches(archive)) {
                throw new IOException("7z split source changed before folder publication");
            }
        }
        private void retire(Throwable failure) {
            failed = true;
            try { close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
        @Override public boolean skipsUnreadEntryOnAdvance() { return true; }
        private void clearSpool() throws IOException {
            IOException failure = null;
            try { if (input != null) input.close(); }
            catch (IOException closeFailure) { failure = closeFailure; }
            finally { input = null; folderIndex = -1; verifiedFolderSize = 0; }
            if (spool != null) {
                try {
                    if (spool.exists() && !spool.delete()) {
                        throw new IOException("Cannot remove verified 7z spool");
                    }
                    spool = null;
                } catch (IOException | SecurityException cleanup) {
                    IOException deletionFailure = cleanup instanceof IOException ? (IOException) cleanup
                            : new IOException("Cannot remove verified 7z spool", cleanup);
                    if (failure == null) failure = deletionFailure;
                    else failure.addSuppressed(deletionFailure);
                }
            }
            if (failure != null) throw failure;
        }
        @Override public void close() throws IOException {
            // Retain a failed deletion's path so a later close can retry it.
            if (closed && spool == null && input == null) return;
            closed = true;
            if (password != null) Arrays.fill(password, '\0');
            Throwable failure = null;
            try { clearSpool(); }
            catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; throw cleanup; }
            finally {
                if (splitSource != null) {
                    try { splitSource.close(); }
                    catch (RuntimeException | Error cleanup) {
                        if (failure == null) throw cleanup;
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                }
            }
        }
    }

    private static final byte[] SIGNATURE = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    // Only encoded/plain metadata uses this array guard; PPMd file streams do not.
    private static final long MAX_STREAM_BYTES = 512L * 1024 * 1024;

    // Property IDs.
    private static final int K_END = 0x00;
    private static final int K_HEADER = 0x01;
    private static final int K_MAIN_STREAMS_INFO = 0x04;
    private static final int K_FILES_INFO = 0x05;
    private static final int K_PACK_INFO = 0x06;
    private static final int K_UNPACK_INFO = 0x07;
    private static final int K_SUBSTREAMS_INFO = 0x08;
    private static final int K_SIZE = 0x09;
    private static final int K_CRC = 0x0A;
    private static final int K_FOLDER = 0x0B;
    private static final int K_CODERS_UNPACK_SIZE = 0x0C;
    private static final int K_NUM_UNPACK_STREAM = 0x0D;
    private static final int K_EMPTY_STREAM = 0x0E;
    private static final int K_EMPTY_FILE = 0x0F;
    private static final int K_NAME = 0x11;
    private static final int K_ENCODED_HEADER = 0x17;
    private static final int K_DUMMY = 0x19;

    // Coder ids.
    private static final byte[] ID_BCJ2 = {0x03, 0x03, 0x01, 0x1B};

    private SevenZBcj2ArchiveReader() {
    }

    /** Returns true if any folder in the archive uses the BCJ2 coder. */
    static boolean archiveUsesBcj2(@NonNull File archive, @Nullable char[] password) {
        try {
            SevenZArchive parsed = parse(archive, password);
            for (Folder folder : parsed.folders) {
                if (folder.usesBcj2()) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /**
     * Returns true if any folder uses a coder this reader implements first
     * party or via a bundled filter because Commons Compress cannot (BCJ2,
     * PPMd, ARM64, RISC-V, Swap2 or Swap4, including encoded headers). Used to
     * gate the fallback so all other 7z archives keep their existing paths.
     */
    static boolean archiveUsesSpecialCoder(@NonNull File archive, @Nullable char[] password) throws IntegrityException {
        try {
            SevenZArchive parsed = parse(archive, password);
            return parsed.usesSpecialCoder();
        } catch (IntegrityException failure) {
            throw failure;
        } catch (IOException ignored) {
        }
        return false;
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archive,
                                                      @Nullable char[] password) throws IOException {
        SevenZArchive parsed = parse(archive, password);
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        for (FileEntry entry : parsed.files) {
            result.add(new ArchiveSupport.EntryInfo(entry.name, entry.isDirectory, entry.size, 0L));
        }
        return result;
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password) throws IOException {
        SevenZArchive parsed = parse(archive, password);
        for (FileEntry entry : parsed.files) {
            if (entry.isDirectory) continue;
            if (!entryPath.equals(entry.name)) continue;
            if (entry.folderIndex < 0) {
                writeEmptyEntry(outFile);
                return true;
            }
            try (FolderStream folder = openFolder(archive, parsed, entry.folderIndex, password, null)) {
                folder.writeEntry(entry.offsetInFolder, entry.size, outFile, true);
            }
            return true;
        }
        return false;
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress,
                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        SevenZArchive parsed = parse(archive, password);
        boolean any = false;
        int currentFolder = -1;
        FolderStream folderData = null;
        try {
            for (FileEntry entry : parsed.files) {
                if (progress != null && !progress.checkpoint()) return false;
                if (entry.isDirectory) {
                    if (entryProgress != null) entryProgress.onDirectory(entry.name);
                    File dir = safeChild(targetDir, entry.name);
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                        throw new IOException("Cannot create 7z output directory");
                    }
                    if (dir != null) any = true;
                    continue;
                }
                File out = safeChild(targetDir, entry.name);
                if (out == null) continue;
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) return false;
                if (entryProgress != null) entryProgress.onFile(entry.name);
                else if (progress != null) progress.setDetail(entry.name);
                if (entry.folderIndex < 0) {
                    writeEmptyEntry(out);
                    any = true;
                    continue;
                }
                if (entry.folderIndex != currentFolder) {
                    if (folderData != null) { folderData.drain(); folderData.close(); folderData = null; }
                    folderData = openFolder(archive, parsed, entry.folderIndex, password, progress);
                    currentFolder = entry.folderIndex;
                }
                // Even a zero-byte folder can carry CRCs and packed-stream checks.
                folderData.writeEntry(entry.offsetInFolder, entry.size, out, false);
                any = true;
            }
            if (folderData != null) folderData.drain();
            return any;
        } finally {
            if (folderData != null) folderData.close();
        }
    }

    // ----- Folder decoding -----

    /**
     * Decodes an encoded metadata header into the header parser's bounded array.
     * File extraction opens the same coder graph as a stream instead.
     */
    @NonNull
    private static byte[] decodeFolder(@NonNull File archive, @NonNull SevenZArchive parsed,
                                       int folderIndex, @Nullable char[] password,
                                       @Nullable SplitVolumeInput source) throws IOException {
        // Encoded metadata headers still need an array for the header parser.
        // File extraction uses openFolder directly and does not inherit this cap.
        long size = parsed.folders.get(folderIndex).getUnpackSize();
        if (size < 0 || size > MAX_STREAM_BYTES) throw new IOException("7z decoded header exceeds memory guard");
        try (FolderStream stream = openFolder(archive, parsed, folderIndex, password, null, source)) {
            byte[] header = readExact(stream.input, size);
            stream.position = size;
            stream.drain();
            return header;
        }
    }

    private static FolderStream openFolder(File archive, SevenZArchive parsed, int folderIndex,
                                            char[] password, FileOperationProgress progress) throws IOException {
        return openFolder(archive, parsed, folderIndex, password, progress, null);
    }

    private static FolderStream openFolder(File archive, SevenZArchive parsed, int folderIndex,
            char[] password, FileOperationProgress progress, SplitVolumeInput source) throws IOException {
        Folder folder = parsed.folders.get(folderIndex);
        SevenZDecodePlan plan = folder.decodePlan();
        if (plan.packedStreamCount != folder.numPackStreams) throw new IOException("7z packed stream count mismatch");
        List<InputStream> owned = new ArrayList<>();
        List<InputStream> packedStreams = new ArrayList<>();
        try {
            long offset = folder.firstPackOffset;
            for (int i = 0; i < folder.numPackStreams; i++) {
                long size = parsed.packSizes[folder.firstPackStreamIndex + i];
                InputStream packed = new java.io.BufferedInputStream(
                        source == null ? new FileRangeInputStream(archive, offset, size, progress)
                                : source.boundedStream(offset, size), 64 * 1024);
                packed = new IntegrityStream(packed, size,
                        parsed.packCrcs == null ? -1 : parsed.packCrcs[folder.firstPackStreamIndex + i],
                        new long[0], new long[0]);
                owned.add(packed);
                packedStreams.add(packed);
                offset = checkedStreamSum(offset, size);
            }
            InputStream[] outputs = new InputStream[plan.stepCount()];
            for (int i = 0; i < plan.stepCount(); i++) {
                SevenZDecodePlan.Step step = plan.step(i);
                InputStream[] inputs = new InputStream[step.inputCount()];
                for (int slot = 0; slot < inputs.length; slot++) {
                    int reference = step.source(slot);
                    inputs[slot] = reference < 0 ? packedStreams.get(-reference - 1) : outputs[reference];
                }
                InputStream decoded = step.decoder.open(inputs, password);
                InputStream bounded = new java.io.BufferedInputStream(
                        new ExactStream(decoded, step.decoder.outputSize), 64 * 1024);
                owned.add(bounded);
                outputs[step.coderIndex] = bounded;
            }
            InputStream finalStream = new IntegrityStream(outputs[plan.finalCoder], plan.outputSize, folder.crc,
                    folder.subStreamSizes, folder.subStreamCrcs);
            return new FolderStream(finalStream, plan.outputSize, owned, packedStreams, progress);
        } catch (IOException | RuntimeException | Error failure) {
            try { closeStreams(owned); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    static long checkedStreamSum(long left, long right) throws IOException {
        if (left < 0 || right < 0 || right > Long.MAX_VALUE - left) throw new IOException("7z stream offset overflow");
        return left + right;
    }

    private static void closeStreams(List<InputStream> streams) throws IOException {
        IOException failure = null;
        for (int i = streams.size() - 1; i >= 0; i--) {
            try { streams.get(i).close(); }
            catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }

    private static final class FileRangeInputStream extends InputStream {
        private final RandomAccessFile file;
        private final FileOperationProgress progress;
        private long remaining;
        FileRangeInputStream(File archive, long offset, long size, FileOperationProgress progress) throws IOException {
            file = new RandomAccessFile(archive, "r");
            this.progress = progress;
            try {
                if (offset < 0 || size < 0 || offset > file.length() || size > file.length() - offset) {
                    throw new EOFException("7z packed stream exceeds archive bounds");
                }
                file.seek(offset);
                remaining = size;
            } catch (IOException e) { file.close(); throw e; }
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            if (progress != null && !progress.checkpoint()) throw new IOException("7z extraction cancelled");
            if (remaining == 0) return -1;
            int count = file.read(data, offset, (int) Math.min(length, remaining));
            if (count < 0) throw new EOFException("Truncated 7z packed stream");
            remaining -= count;
            return count;
        }
        @Override public void close() throws IOException { file.close(); }
    }

    private static final class ExactStream extends InputStream {
        private final InputStream input;
        private final byte[] singleByte = new byte[1];
        private IOException failure;
        private boolean closed;
        private long remaining;
        private boolean validatedEnd;
        ExactStream(InputStream input, long size) throws IOException {
            if (size < 0) throw new IOException("Invalid 7z output size");
            this.input = input; remaining = size;
        }
        @Override public int read() throws IOException {
            return read(singleByte, 0, 1) < 0 ? -1 : singleByte[0] & 255;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
            if (closed) throw new IOException("7z stream is closed");
            if (failure != null) throw failure;
            if (length == 0) return 0;
            try {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("7z extraction cancelled");
                return readChecked(data, offset, length);
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }
        private int readChecked(byte[] data, int offset, int length) throws IOException {
            if (remaining == 0) {
                if (!validatedEnd) {
                    if (input.read() != -1) throw new IOException("7z coder exceeds declared output size");
                    validatedEnd = true;
                }
                return -1;
            }
            int count = input.read(data, offset, (int) Math.min(remaining, length));
            if (count < 0) throw new EOFException("7z coder output ended early");
            if (count == 0 || count > Math.min(remaining, length)) throw new IOException("Invalid or zero-progress 7z coder read");
            remaining -= count;
            return count;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            input.close();
        }
    }

    private static final class FolderStream implements java.io.Closeable {
        final InputStream input;
        final long size;
        final List<InputStream> owned;
        final List<InputStream> packedStreams;
        final FileOperationProgress progress;
        final byte[] buffer = new byte[64 * 1024];
        final List<RarOutputFileGuard> pendingOutputs = new ArrayList<>();
        long position;
        boolean verified, closed;
        FolderStream(InputStream input, long size, List<InputStream> owned,
                List<InputStream> packedStreams, FileOperationProgress progress) {
            this.input = input; this.size = size; this.owned = owned;
            this.packedStreams = packedStreams; this.progress = progress;
        }
        void transfer(long count, OutputStream target) throws IOException {
            if (count < 0 || count > size - position) throw new IOException("7z entry slice out of range");
            while (count > 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("7z extraction cancelled");
                if (progress != null && !progress.checkpoint()) throw new IOException("7z extraction cancelled");
                int read = input.read(buffer, 0, (int) Math.min(count, buffer.length));
                if (read < 0) throw new EOFException("7z folder ended early");
                if (read == 0 || read > Math.min(count, buffer.length)) throw new IOException("Invalid or zero-progress 7z folder read");
                if (target != null) target.write(buffer, 0, read);
                position += read; count -= read;
                if (target != null && progress != null) progress.addDoneBytes(read);
            }
        }
        void writeEntry(long offset, long length, File target, boolean finishFolder) throws IOException {
            if (offset < position || offset > size || length < 0 || length > size - offset) {
                throw new IOException("Invalid 7z entry range");
            }
            transfer(offset - position, null);
            if (closed || verified) throw new IOException("7z folder is closed or already verified");
            RarOutputFileGuard guard = RarOutputFileGuard.forTarget(target);
            pendingOutputs.add(guard);
            // Keep only rollback metadata, not an open output descriptor. A later
            // folder/packed CRC can invalidate earlier slices of the same folder.
            try (OutputStream output = ArchiveSupport.openExtractionOutputStream(target)) {
                transfer(length, output);
                if (finishFolder) drain();
            } catch (IOException | RuntimeException | Error failure) {
                verified = false; // A destination close error must also roll back.
                throw failure;
            }
        }
        void drain() throws IOException {
            if (closed) throw new IOException("7z folder is closed");
            if (verified) return;
            transfer(size - position, null);
            if (input.read() != -1) throw new IOException("7z folder exceeds declared size");
            // Coders can leave padding in packed streams. Verify those declared bytes too.
            for (InputStream packed : packedStreams) {
                int count;
                while ((count = packed.read(buffer)) != -1) {
                    if (count <= 0 || count > buffer.length) throw new IOException("Invalid or zero-progress 7z packed read");
                    if (Thread.currentThread().isInterrupted()) throw new IOException("7z extraction cancelled");
                }
            }
            verified = true;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            try { closeStreams(owned); }
            catch (IOException | RuntimeException | Error e) { failure = e; }
            // Both decoding AND decoder close must succeed before committing.
            if (failure == null && verified) for (RarOutputFileGuard guard : pendingOutputs) guard.commit();
            for (int i = pendingOutputs.size() - 1; i >= 0; i--) {
                try { pendingOutputs.get(i).close(); }
                catch (IOException | RuntimeException | Error e) {
                    if (failure == null) failure = e;
                    else if (failure != e) failure.addSuppressed(e);
                }
            }
            pendingOutputs.clear();
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
    }

    /** Checks every present CRC, including zero-length substreams, without whole-output arrays. */
    private static final class IntegrityStream extends InputStream {
        private final InputStream input;
        private final byte[] singleByte = new byte[1];
        private IOException failure;
        private boolean closed;
        private final long size, expected;
        private final long[] sizes, crcs;
        private final CRC32 totalCrc = new CRC32(), partCrc = new CRC32();
        private long position, partBytes;
        private int part;
        private boolean endChecked;
        IntegrityStream(InputStream input, long size, long expected, long[] sizes, long[] crcs) {
            this.input = input; this.size = size; this.expected = expected;
            this.sizes = sizes; this.crcs = crcs;
        }
        private void checkParts() throws IOException {
            while (part < sizes.length && partBytes == sizes[part]) {
                if (part < crcs.length) requireCrc(partCrc.getValue(), crcs[part], "substream");
                part++; partBytes = 0; partCrc.reset();
            }
        }
        @Override public int read() throws IOException {
            return read(singleByte, 0, 1) < 0 ? -1 : singleByte[0] & 255;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
            if (closed) throw new IOException("7z stream is closed");
            if (failure != null) throw failure;
            if (length == 0) return 0;
            try {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("7z extraction cancelled");
                return readChecked(data, offset, length);
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }
        private int readChecked(byte[] data, int offset, int length) throws IOException {
            checkParts();
            if (position == size) {
                if (!endChecked) {
                    if (input.read() != -1) throw new IOException("7z stream exceeds declared size");
                    requireCrc(totalCrc.getValue(), expected, "stream");
                    endChecked = true;
                }
                return -1;
            }
            long available = size - position;
            if (part < sizes.length) available = Math.min(available, sizes[part] - partBytes);
            int count = input.read(data, offset, (int) Math.min(length, available));
            if (count < 0) throw new EOFException("Truncated 7z stream");
            if (count == 0 || count > Math.min(length, available)) throw new IOException("Invalid or zero-progress 7z integrity read");
            totalCrc.update(data, offset, count);
            partCrc.update(data, offset, count);
            position += count; partBytes += count;
            checkParts();
            if (position == size) requireCrc(totalCrc.getValue(), expected, "stream");
            return count;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            input.close();
        }
    }

    static final class IntegrityException extends IOException {
        IntegrityException(String message) { super(message); }
    }

    private static void requireCrc(long actual, long expected, String kind) throws IOException {
        if (expected >= 0 && actual != expected) throw new IntegrityException("7z " + kind + " CRC mismatch");
    }

    // ----- Header parsing -----

    @NonNull
    private static SevenZArchive parse(@NonNull File archive, @Nullable char[] password) throws IOException {
        try (SplitVolumeInput source = new SplitVolumeInput(java.util.Collections.singletonList(
                new SplitVolumeInput.Segment(archive, 0, archive.length())))) {
            return parse(archive, password, source);
        }
    }

    private static SevenZArchive parse(@NonNull File archive, @Nullable char[] password,
                                       @NonNull SplitVolumeInput raf) throws IOException {
        byte[] sig = new byte[6];
        raf.readFully(sig);
        if (!Arrays.equals(sig, SIGNATURE)) throw new IOException("Not a 7z archive");
        raf.seek(8); // version
        long startCrc = readUIntLE(raf, 4);
        byte[] start = new byte[20];
        raf.readFully(start);
        CRC32 crc = new CRC32();
        crc.update(start);
        requireCrc(crc.getValue(), startCrc, "start header");
        raf.seek(12);
        long nextHeaderOffset = readUInt64LE(raf);
        long nextHeaderSize = readUInt64LE(raf);
        long nextCrc = readUIntLE(raf, 4);
        if (nextHeaderSize <= 0 || nextHeaderSize > MAX_STREAM_BYTES) {
            throw new IOException("7z header size out of range");
        }
        long headerOffset = checkedStreamSum(32L, nextHeaderOffset);
        if (headerOffset > raf.length() || nextHeaderSize > raf.length() - headerOffset) {
            throw new EOFException("7z next header exceeds archive bounds");
        }
        byte[] header = new byte[(int) nextHeaderSize];
        raf.seek(headerOffset);
        raf.readFully(header);
        crc.reset(); crc.update(header);
        requireCrc(crc.getValue(), nextCrc, "next header");

        ByteReader reader = new ByteReader(header);
        int id = reader.readByte();
        boolean specialEncodedHeader = false;
        if (id == K_ENCODED_HEADER) {
            DecodedHeader decoded = decodeEncodedHeader(archive, reader, password, raf);
            header = decoded.bytes;
            specialEncodedHeader = decoded.special;
            reader = new ByteReader(header);
            id = reader.readByte();
        }
        if (id != K_HEADER) {
            throw new IOException(password != null
                    ? "7z header could not be read (wrong password?)"
                    : "7z header not found");
        }
        SevenZArchive parsed = parseHeader(reader);
        parsed.specialEncodedHeader = specialEncodedHeader;
        return parsed;
    }

    @NonNull
    private static DecodedHeader decodeEncodedHeader(@NonNull File archive,
                                                     @NonNull ByteReader reader,
                                                     @Nullable char[] password,
                                                     @NonNull SplitVolumeInput source) throws IOException {
        StreamsInfo info = readStreamsInfo(reader);
        SevenZArchive tmp = new SevenZArchive();
        tmp.packPos = info.packPos;
        tmp.packSizes = info.packSizes;
        tmp.packCrcs = info.packCrcs;
        tmp.folders = info.folders;
        if (info.folders.isEmpty()) throw new IOException("7z encoded header has no folder");
        return new DecodedHeader(decodeFolder(archive, tmp, 0, password, source), tmp.usesSpecialCoder());
    }

    @NonNull
    private static SevenZArchive parseHeader(@NonNull ByteReader reader) throws IOException {
        SevenZArchive result = new SevenZArchive();
        int id = reader.readByte();
        if (id == 0x02) { // ArchiveProperties
            skipArchiveProperties(reader);
            id = reader.readByte();
        }
        if (id == 0x03) { // AdditionalStreamsInfo (rare)
            readStreamsInfo(reader);
            id = reader.readByte();
        }
        StreamsInfo streams = null;
        if (id == K_MAIN_STREAMS_INFO) {
            streams = readStreamsInfo(reader);
            id = reader.readByte();
        }
        if (streams != null) {
            result.packPos = streams.packPos;
            result.packSizes = streams.packSizes;
            result.packCrcs = streams.packCrcs;
            result.folders = streams.folders;
        }
        if (id == K_FILES_INFO) {
            readFilesInfo(reader, result, streams);
            id = reader.readByte();
        }
        if (id != K_END) throw new IOException("7z header missing terminator");
        return result;
    }

    private static void skipArchiveProperties(@NonNull ByteReader reader) throws IOException {
        while (true) {
            int propType = reader.readByte();
            if (propType == K_END) return;
            long size = reader.readNumber();
            reader.skip(size);
        }
    }

    @NonNull
    private static StreamsInfo readStreamsInfo(@NonNull ByteReader reader) throws IOException {
        StreamsInfo info = new StreamsInfo();
        int id = reader.readByte();
        if (id == K_PACK_INFO) {
            info.packPos = reader.readNumber();
            int numPack = boundedHeaderCount(reader.readNumber(), reader.remaining(), "packed streams");
            int type = reader.readByte();
            while (type != K_SIZE) {
                if (type == K_END) throw new IOException("7z PackInfo missing sizes");
                reader.skip(reader.readNumber());
                type = reader.readByte();
            }
            if (type == K_SIZE) {
                info.packSizes = new long[(int) numPack];
                for (int i = 0; i < numPack; i++) info.packSizes[i] = reader.readNumber();
                type = reader.readByte();
            }
            while (type != K_END) {
                if (type == K_CRC) {
                    info.packCrcs = readDigests(reader, (int) numPack);
                } else {
                    reader.skip(reader.readNumber());
                }
                type = reader.readByte();
            }
            id = reader.readByte();
        }
        if (info.packSizes == null) info.packSizes = new long[0];
        if (id == K_UNPACK_INFO) {
            readUnpackInfo(reader, info);
            id = reader.readByte();
        }
        if (id == K_SUBSTREAMS_INFO) {
            readSubStreamsInfo(reader, info);
            id = reader.readByte();
        } else {
            // Default: one substream per folder, sized to the folder output.
            for (Folder folder : info.folders) {
                folder.numUnpackSubStreams = 1;
                folder.subStreamSizes = new long[] {folder.getUnpackSize()};
                folder.subStreamCrcs = new long[] {folder.crc};
            }
        }
        if (id != K_END) throw new IOException("7z StreamsInfo missing terminator");
        assignPackStreamsToFolders(info);
        return info;
    }

    private static void readUnpackInfo(@NonNull ByteReader reader, @NonNull StreamsInfo info) throws IOException {
        int id = reader.readByte();
        if (id != K_FOLDER) throw new IOException("7z UnpackInfo missing Folder");
        int numFolders = boundedHeaderCount(reader.readNumber(), reader.remaining() / 3L, "folders");
        int external = reader.readByte();
        if (external != 0) throw new IOException("7z external folder definitions unsupported");
        info.folders = new ArrayList<>();
        for (int i = 0; i < numFolders; i++) {
            info.folders.add(readFolder(reader));
        }
        id = reader.readByte();
        if (id != K_CODERS_UNPACK_SIZE) throw new IOException("7z missing CodersUnpackSize");
        for (Folder folder : info.folders) {
            folder.coderUnpackSizes = new long[folder.totalOutputStreams];
            for (int i = 0; i < folder.totalOutputStreams; i++) {
                folder.coderUnpackSizes[i] = reader.readNumber();
            }
        }
        id = reader.readByte();
        while (id != K_END) {
            if (id == K_CRC) {
                long[] crcs = readDigests(reader, info.folders.size());
                for (int i = 0; i < crcs.length; i++) info.folders.get(i).crc = crcs[i];
            } else {
                reader.skip(reader.readNumber());
            }
            id = reader.readByte();
        }
    }

    @NonNull
    private static Folder readFolder(@NonNull ByteReader reader) throws IOException {
        Folder folder = new Folder();
        long numCoders = reader.readNumber();
        // Each coder needs at least a flags byte and a method byte in this header.
        if (numCoders <= 0 || numCoders > reader.remaining() / 2) throw new IOException("Invalid 7z coder count");
        folder.coders = new Coder[(int) numCoders];
        long totalIn = 0, totalOut = 0;
        for (int i = 0; i < numCoders; i++) {
            Coder coder = new Coder();
            int flags = reader.readByte();
            int idSize = flags & 0x0f;
            if (idSize == 0 || idSize > 8) throw new IOException("Invalid 7z method ID size");
            coder.id = reader.readBytes(idSize);
            long inputs = 1, outputs = 1;
            if ((flags & 0x10) != 0) {
                inputs = reader.readNumber(); outputs = reader.readNumber();
            }
            if (inputs <= 0 || inputs > Integer.MAX_VALUE || outputs <= 0 || outputs > Integer.MAX_VALUE) {
                throw new IOException("Invalid 7z coder stream count");
            }
            coder.numInStreams = (int) inputs; coder.numOutStreams = (int) outputs;
            if ((flags & 0x20) != 0) {
                long propSize = reader.readNumber();
                if (propSize < 0 || propSize > reader.remaining()) throw new IOException("Invalid 7z property size");
                coder.properties = reader.readBytes((int) propSize);
            }
            if ((flags & 0x80) != 0) throw new IOException("7z alternative coder methods unsupported");
            folder.coders[i] = coder;
            totalIn += inputs; totalOut += outputs;
        }
        // Bindings, packed indices and output sizes must fit in the remaining metadata.
        if (totalIn > (long) reader.remaining() + 1 || totalOut > reader.remaining()
                || totalIn > Integer.MAX_VALUE || totalOut > Integer.MAX_VALUE) {
            throw new IOException("7z stream counts exceed header bounds");
        }
        folder.totalInputStreams = (int) totalIn;
        folder.totalOutputStreams = (int) totalOut;
        int numBindPairs = folder.totalOutputStreams - 1;
        int numPackedStreams = folder.totalInputStreams - numBindPairs;
        if (numPackedStreams <= 0) throw new IOException("7z folder has no packed input");
        folder.bindPairInIndex = new int[numBindPairs];
        folder.bindPairOutIndex = new int[numBindPairs];
        boolean[] boundInputs = new boolean[folder.totalInputStreams];
        boolean[] boundOutputs = new boolean[folder.totalOutputStreams];
        for (int i = 0; i < numBindPairs; i++) {
            int input = readStreamIndex(reader, folder.totalInputStreams);
            int output = readStreamIndex(reader, folder.totalOutputStreams);
            if (boundInputs[input] || boundOutputs[output]) throw new IOException("Duplicate 7z coder binding");
            boundInputs[input] = true; boundOutputs[output] = true;
            folder.bindPairInIndex[i] = input; folder.bindPairOutIndex[i] = output;
        }
        for (int i = 0; i < boundOutputs.length; i++) {
            if (!boundOutputs[i]) { folder.finalOutputIndex = i; break; }
        }
        folder.packedInputIndices = new int[numPackedStreams];
        if (numPackedStreams == 1) {
            for (int i = 0; i < boundInputs.length; i++) {
                if (!boundInputs[i]) { folder.packedInputIndices[0] = i; break; }
            }
        } else {
            for (int i = 0; i < numPackedStreams; i++) {
                int input = readStreamIndex(reader, folder.totalInputStreams);
                if (boundInputs[input]) throw new IOException("Duplicate 7z packed input");
                boundInputs[input] = true;
                folder.packedInputIndices[i] = input;
            }
        }
        return folder;
    }

    private static int readStreamIndex(ByteReader reader, int count) throws IOException {
        long index = reader.readNumber();
        if (index < 0 || index >= count) throw new IOException("7z stream index out of range");
        return (int) index;
    }

    private static void readSubStreamsInfo(@NonNull ByteReader reader, @NonNull StreamsInfo info) throws IOException {
        int id = reader.readByte();
        for (Folder folder : info.folders) folder.numUnpackSubStreams = 1;
        if (id == K_NUM_UNPACK_STREAM) {
            for (Folder folder : info.folders) {
                folder.numUnpackSubStreams = boundedHeaderCount(reader.readNumber(),
                        (long) reader.remaining() + 1L, "substreams");
            }
            id = reader.readByte();
        }
        // Sizes: for each folder, (numSubstreams-1) explicit sizes; the last is
        // the remainder of the folder output.
        for (Folder folder : info.folders) {
            if (folder.numUnpackSubStreams == 0) {
                folder.subStreamSizes = new long[0];
                continue;
            }
            if (folder.numUnpackSubStreams > 1 && id != K_SIZE) {
                throw new IOException("7z multiple substreams are missing sizes");
            }
            long sum = 0;
            long[] sizes = new long[folder.numUnpackSubStreams];
            if (id == K_SIZE) {
                for (int i = 0; i < folder.numUnpackSubStreams - 1; i++) {
                    long s = reader.readNumber();
                    sizes[i] = s;
                    sum = checkedStreamSum(sum, s);
                }
            }
            if (folder.getUnpackSize() < sum) throw new IOException("7z substreams exceed folder size");
            sizes[folder.numUnpackSubStreams - 1] = folder.getUnpackSize() - sum;
            folder.subStreamSizes = sizes;
        }
        if (id == K_SIZE) id = reader.readByte();
        for (Folder folder : info.folders) {
            folder.subStreamCrcs = new long[folder.numUnpackSubStreams];
            Arrays.fill(folder.subStreamCrcs, -1L);
            if (folder.numUnpackSubStreams == 1) folder.subStreamCrcs[0] = folder.crc;
        }
        while (id != K_END) {
            if (id == K_CRC) {
                int numDigests = 0;
                for (Folder folder : info.folders) {
                    if (folder.numUnpackSubStreams == 1 && folder.crc >= 0) continue;
                    if (folder.numUnpackSubStreams > Integer.MAX_VALUE - numDigests) throw new IOException("Too many 7z digests");
                    numDigests += folder.numUnpackSubStreams;
                }
                long[] crcs = readDigests(reader, numDigests);
                int digest = 0;
                for (Folder folder : info.folders) {
                    // A single substream inherits its folder CRC and has no separate digest.
                    if (folder.numUnpackSubStreams == 1 && folder.crc >= 0) continue;
                    for (int i = 0; i < folder.numUnpackSubStreams; i++) folder.subStreamCrcs[i] = crcs[digest++];
                }
            } else {
                reader.skip(reader.readNumber());
            }
            id = reader.readByte();
        }
    }

    private static void assignPackStreamsToFolders(@NonNull StreamsInfo info) throws IOException {
        int packIndex = 0;
        long offset = checkedStreamSum(32L, info.packPos);
        for (Folder folder : info.folders) {
            folder.firstPackStreamIndex = packIndex;
            folder.firstPackOffset = offset;
            folder.numPackStreams = folder.packedInputIndices.length;
            if (folder.numPackStreams > info.packSizes.length - packIndex) throw new IOException("7z folder references missing packed stream");
            for (int i = 0; i < folder.numPackStreams; i++) {
                offset = checkedStreamSum(offset, info.packSizes[packIndex++]);
            }
        }
        if (packIndex != info.packSizes.length || (info.packCrcs != null && info.packCrcs.length != packIndex)) {
            throw new IOException("7z packed stream count mismatch");
        }
    }

    private static void readFilesInfo(@NonNull ByteReader reader,
                                      @NonNull SevenZArchive result,
                                      @Nullable StreamsInfo streams) throws IOException {
        long declaredFiles = reader.readNumber();
        long streamCount = 0L;
        if (streams != null) for (Folder folder : streams.folders) {
            streamCount = checkedStreamSum(streamCount, folder.numUnpackSubStreams);
        }
        // Named or empty files require metadata; unnamed streamed files are
        // bounded by the declared substreams. This rejects impossible counts
        // before allocating and never imposes an arbitrary entry-count ceiling.
        int numFiles = boundedHeaderCount(declaredFiles,
                Math.max(streamCount, 8L * reader.remaining()), "files");
        String[] names = new String[numFiles];
        BitSet emptyStream = new BitSet(numFiles);
        BitSet emptyFile = new BitSet();
        int numEmptyStreams = 0;
        boolean hasEmptyStream = false, hasEmptyFile = false, hasNames = false;

        while (true) {
            int propType = reader.readByte();
            if (propType == K_END) break;
            long size = reader.readNumber();
            ByteReader property = reader.readSlice(size);
            switch (propType) {
                case K_EMPTY_STREAM:
                    if (hasEmptyStream) throw new IOException("Duplicate 7z EmptyStream property");
                    hasEmptyStream = true;
                    emptyStream = readBitVector(property, numFiles);
                    numEmptyStreams = emptyStream.cardinality();
                    requirePropertyEnd(property);
                    break;
                case K_EMPTY_FILE:
                    if (hasEmptyFile) throw new IOException("Invalid 7z EmptyFile property");
                    hasEmptyFile = true;
                    emptyFile = readBitVector(property, numEmptyStreams);
                    requirePropertyEnd(property);
                    break;
                case K_NAME: {
                    if (hasNames) throw new IOException("Duplicate 7z Name property");
                    hasNames = true;
                    int external = property.readByte();
                    if (external != 0) throw new IOException("7z external names unsupported");
                    for (int i = 0; i < numFiles; i++) {
                        names[i] = readUtf16Name(property);
                    }
                    requirePropertyEnd(property);
                    break;
                }
                default:
                    // Unknown properties are isolated and skipped without copying.
                    break;
            }
        }

        // Map files to folders and offsets. Files with a stream draw from the
        // folder substreams in order; empty-stream files are dirs or empty.
        List<FileEntry> files = new ArrayList<>();
        int folderIndex = 0;
        int subInFolder = 0;
        long offsetInFolder = 0;
        int emptyCounter = 0;
        List<Folder> folders = streams == null ? new ArrayList<>() : streams.folders;
        for (int i = 0; i < numFiles; i++) {
            FileEntry entry = new FileEntry();
            entry.name = names[i] == null ? ("entry" + i) : names[i];
            if (emptyStream.get(i)) {
                boolean isEmptyFile = emptyFile.get(emptyCounter);
                emptyCounter++;
                entry.isDirectory = !isEmptyFile;
                entry.size = 0;
                entry.folderIndex = -1;
            } else {
                // Advance to a folder that has a substream available.
                while (folderIndex < folders.size()
                        && subInFolder >= folders.get(folderIndex).numUnpackSubStreams) {
                    folderIndex++;
                    subInFolder = 0;
                    offsetInFolder = 0;
                }
                if (folderIndex >= folders.size()) throw new IOException("7z file references missing folder");
                Folder folder = folders.get(folderIndex);
                entry.isDirectory = false;
                entry.folderIndex = folderIndex;
                entry.offsetInFolder = offsetInFolder;
                entry.size = folder.subStreamSizes[subInFolder];
                offsetInFolder = checkedStreamSum(offsetInFolder, entry.size);
                if (offsetInFolder > folder.getUnpackSize()) throw new IOException("7z file exceeds folder size");
                subInFolder++;
            }
            files.add(entry);
        }
        result.files = files;
    }

    // ----- Small helpers -----

    private static int boundedHeaderCount(long count, long maximum, String kind) throws IOException {
        if (count < 0 || count > Integer.MAX_VALUE || count > maximum) {
            throw new IOException("7z " + kind + " count exceeds header bounds");
        }
        return (int) count;
    }

    private static void requirePropertyEnd(ByteReader property) throws IOException {
        if (property.remaining() != 0) throw new IOException("7z property size mismatch");
    }

    private static long[] readDigests(@NonNull ByteReader reader, int count) throws IOException {
        if (count < 0) throw new IOException("Invalid 7z digest count");
        int allDefined = reader.readByte();
        BitSet defined = allDefined == 0 ? readBitVector(reader, count) : null;
        int storedCount = defined == null ? count : defined.cardinality();
        if (storedCount > reader.remaining() / 4) throw new EOFException("7z digest data is truncated");
        long[] crcs = new long[count];
        Arrays.fill(crcs, -1L);
        for (int i = 0; i < count; i++) {
            if (defined != null && !defined.get(i)) continue;
            long crc = 0;
            for (int b = 0; b < 4; b++) crc |= (long) reader.readByte() << (8 * b);
            crcs[i] = crc;
        }
        return crcs;
    }

    @NonNull
    private static BitSet readBitVector(@NonNull ByteReader reader, int count) throws IOException {
        if (count < 0 || ((long) count + 7L) / 8L > reader.remaining()) {
            throw new EOFException("7z bit vector is truncated");
        }
        BitSet bits = new BitSet(count);
        int mask = 0;
        int current = 0;
        for (int i = 0; i < count; i++) {
            if (mask == 0) {
                current = reader.readByte();
                mask = 0x80;
            }
            if ((current & mask) != 0) bits.set(i);
            mask >>>= 1;
        }
        return bits;
    }

    @NonNull
    private static String readUtf16Name(@NonNull ByteReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int lo = reader.readByte();
            int hi = reader.readByte();
            int ch = lo | (hi << 8);
            if (ch == 0) break;
            sb.append((char) ch);
        }
        return sb.toString().replace('\\', '/');
    }

    @NonNull
    private static byte[] readExact(@NonNull InputStream in, long size) throws IOException {
        if (size < 0 || size > MAX_STREAM_BYTES) throw new IOException("7z metadata exceeds memory guard");
        byte[] out = new byte[(int) size];
        int done = 0;
        while (done < out.length) {
            int n = in.read(out, done, out.length - done);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("7z header decoding cancelled");
            if (n < 0) throw new EOFException("7z stream ended early");
            if (n == 0) throw new IOException("7z header decoder made no progress");
            done += n;
        }
        return out;
    }

    private static void writeEmptyEntry(@NonNull File outFile) throws IOException {
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                out.flush();
            }
            guard.commit();
        }
    }

    @Nullable
    private static File safeChild(@NonNull File targetDir, @NonNull String name) throws IOException {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty() || cleaned.equals("..") || cleaned.startsWith("../")
                || cleaned.contains("/../") || cleaned.endsWith("/..")
                || cleaned.matches("^[A-Za-z]:.*")) {
            return null;
        }
        File out = new File(targetDir, cleaned);
        String base = targetDir.getCanonicalPath() + File.separator;
        String path = out.getCanonicalPath();
        if (!path.equals(targetDir.getCanonicalPath()) && !path.startsWith(base)) return null;
        return out;
    }

    private static boolean matchesId(@NonNull byte[] id, @NonNull byte[] expected) {
        return Arrays.equals(id, expected);
    }

    private static long readUInt64LE(@NonNull SplitVolumeInput raf) throws IOException {
        return readUIntLE(raf, 8);
    }

    private static long readUIntLE(@NonNull SplitVolumeInput raf, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int b = raf.read();
            if (b < 0) throw new EOFException("7z header truncated");
            value |= (long) b << (8 * i);
        }
        return value;
    }

    // ----- Model classes -----

    private static final class DecodedHeader {
        final byte[] bytes;
        final boolean special;
        DecodedHeader(byte[] bytes, boolean special) { this.bytes = bytes; this.special = special; }
    }

    private static final class SevenZArchive {
        long packPos;
        long[] packCrcs;
        long[] packSizes = new long[0];
        List<Folder> folders = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
        boolean specialEncodedHeader;

        boolean usesSpecialCoder() {
            if (specialEncodedHeader) return true;
            for (Folder folder : folders) {
                for (Coder coder : folder.coders) {
                    if (SevenZCoderRegistry.requiresSupplemental(coder.id)) return true;
                }
            }
            return false;
        }
    }

    private static final class StreamsInfo {
        long packPos;
        long[] packCrcs;
        long[] packSizes;
        List<Folder> folders = new ArrayList<>();
    }

    private static final class Coder {
        byte[] id = new byte[0];
        int numInStreams;
        int numOutStreams;
        @Nullable byte[] properties;
    }

    private static final class Folder {
        long crc = -1;
        long[] subStreamCrcs = new long[0];
        Coder[] coders = new Coder[0];
        int totalInputStreams;
        int totalOutputStreams;
        int[] bindPairInIndex = new int[0];
        int[] bindPairOutIndex = new int[0];
        int[] packedInputIndices = new int[0];
        long[] coderUnpackSizes = new long[0];
        int firstPackStreamIndex;
        long firstPackOffset;
        int finalOutputIndex = -1;
        private SevenZDecodePlan plan;
        int numPackStreams;
        int numUnpackSubStreams = 1;
        long[] subStreamSizes = new long[0];

        boolean usesBcj2() {
            for (Coder coder : coders) {
                if (matchesId(coder.id, ID_BCJ2)) return true;
            }
            return false;
        }

        long getUnpackSize() throws IOException {
            if (finalOutputIndex < 0 || finalOutputIndex >= coderUnpackSizes.length) {
                throw new IOException("7z folder has no final output size");
            }
            return coderUnpackSizes[finalOutputIndex];
        }

        SevenZDecodePlan decodePlan() throws IOException {
            if (plan == null) {
                SevenZDecodePlan.Coder[] specs = new SevenZDecodePlan.Coder[coders.length];
                for (int i = 0; i < coders.length; i++) {
                    Coder coder = coders[i];
                    specs[i] = new SevenZDecodePlan.Coder(coder.id, coder.properties,
                            coder.numInStreams, coder.numOutStreams);
                }
                plan = SevenZDecodePlan.compile(specs, coderUnpackSizes,
                        bindPairInIndex, bindPairOutIndex, packedInputIndices);
            }
            return plan;
        }
    }

    private static final class FileEntry {
        String name = "";
        boolean isDirectory;
        long size;
        int folderIndex = -1;
        long offsetInFolder;
    }

    /** Sequential reader over the parsed 7z header bytes. */
    private static final class ByteReader {
        private final byte[] data;
        private final int start, limit;
        private int pos;

        ByteReader(@NonNull byte[] data) {
            this(data, 0, data.length);
        }

        private ByteReader(byte[] data, int start, int limit) {
            this.data = data;
            this.start = start;
            this.pos = start;
            this.limit = limit;
        }

        ByteReader readSlice(long count) throws IOException {
            if (count < 0 || count > remaining()) throw new EOFException("7z property exceeds header bounds");
            ByteReader slice = new ByteReader(data, pos, pos + (int) count);
            pos += (int) count;
            return slice;
        }

        int position() {
            return pos;
        }

        void seek(long absolute) throws IOException {
            if (absolute < start || absolute > limit) throw new IOException("7z header seek out of range");
            pos = (int) absolute;
        }

        int remaining() { return limit - pos; }

        int readByte() throws IOException {
            if (pos >= limit) throw new EOFException("7z header underrun");
            return data[pos++] & 0xff;
        }

        @NonNull
        byte[] readBytes(int count) throws IOException {
            if (count < 0 || count > limit - pos) throw new EOFException("7z header underrun");
            byte[] out = Arrays.copyOfRange(data, pos, pos + count);
            pos += count;
            return out;
        }

        void skip(long count) throws IOException {
            if (count < 0 || count > limit - pos) throw new EOFException("7z header underrun");
            pos += (int) count;
        }

        /** Reads a 7z variable-length REAL_UINT64. */
        long readNumber() throws IOException {
            int first = readByte();
            long value = 0;
            int mask = 0x80;
            for (int i = 0; i < 8; i++) {
                if ((first & mask) == 0) {
                    value |= (long) (first & (mask - 1)) << (8 * i);
                    break;
                }
                value |= (long) readByte() << (8 * i);
                mask >>>= 1;
            }
            return value;
        }
    }
}
