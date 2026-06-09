package com.textview.reader.archive;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveEntry;
import me.zhanghai.android.libarchive.ArchiveException;

/**
 * FOSS-friendly native archive bridge backed by libarchive-android.
 *
 * <p>This bridge is intentionally format-neutral. RAR uses it as the primary backend, ZIP uses
 * Zip4j/Commons/SevenZFile remain the primary paths for ZIP/TAR/7z and fall back here only
 * when the dedicated path cannot handle a case. Java readers remain useful for archive creation,
 * split-volume glue, password-specific ZIP handling, and environments where the Android native
 * libarchive payload is unavailable.</p>
 */
final class LibarchiveNativeBridge {
    private static final String TAG = "TextViewArchive";

    private static final int BLOCK_SIZE = 64 * 1024;
    private static final int MAX_SFX_SCAN = 1024 * 1024;
    private static final byte[] RAR4_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
    };
    private static final byte[] RAR5_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00
    };

    private static final boolean AVAILABLE;
    private static final String RAR_FORMAT_PROBE_STATUS;
    private static final String STATUS;

    static {
        boolean available = false;
        String status;
        String rarProbeStatus = "RAR/RAR5 format probe: not run";
        try {
            byte[] version = Archive.versionString();
            // Do not make RAR availability depend on the individual readSupportFormatRar()
            // / readSupportFormatRar5() helpers. Some Android builds expose a usable
            // readSupportFormatAll() path even when the per-format helper probe is not
            // linkable or returns an error. RAR extraction/listing below opens the reader
            // with readSupportFormatAll(), so the actual operation must be the authority.
            ProbeResult rarProbe = probeRarFormatSupportForDiagnosticsOnly();
            rarProbeStatus = rarProbe.detail;
            status = "libarchive-android bundled: "
                    + (version == null ? "unknown version" : new String(version, StandardCharsets.UTF_8))
                    + "; " + rarProbe.detail
                    + "; supportedAbis=" + Arrays.toString(Build.SUPPORTED_ABIS)
                    + "; javaLibraryPath=" + System.getProperty("java.library.path", "");
            available = true;
        } catch (Throwable t) {
            status = "libarchive-android unavailable: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage())
                    + "; supportedAbis=" + Arrays.toString(Build.SUPPORTED_ABIS)
                    + "; javaLibraryPath=" + System.getProperty("java.library.path", "");
            logWarning(status, t);
        }
        AVAILABLE = available;
        RAR_FORMAT_PROBE_STATUS = rarProbeStatus;
        STATUS = status;
    }

    private LibarchiveNativeBridge() {}

    private static void logWarning(@NonNull String message, @NonNull Throwable throwable) {
        try {
            Log.w(TAG, message, throwable);
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests do not provide android.util.Log. Backend availability
            // diagnostics must not fail class initialization before the fallback paths run.
        }
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static boolean isRarFormatAvailable() {
        // RAR support must not be disabled by the diagnostic per-format probe. The
        // extraction path uses readSupportFormatAll(), and libarchive's real answer
        // comes from readOpen/readNextHeader/readData. Returning AVAILABLE here keeps
        // RAR3/RAR5 compressed files on the libarchive primary path instead of falling
        // through to first-party unsupported messages before libarchive is attempted.
        return AVAILABLE;
    }

    @NonNull
    static String backendStatus() {
        return STATUS;
    }

    @NonNull
    static String rarFormatProbeStatus() {
        return RAR_FORMAT_PROBE_STATUS;
    }

    @NonNull
    static String listEntries(@NonNull String archivePath, @Nullable char[] password) throws IOException {
        return listEntries(new String[] { archivePath }, password);
    }

    @NonNull
    static String listEntries(@NonNull String[] archivePaths, @Nullable char[] password) throws IOException {
        ensureAvailable();
        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), password);
            StringBuilder out = new StringBuilder();
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                String path = normalizedEntryPath(entry);
                if (path != null) {
                    boolean directory = isDirectory(entry);
                    long size = ArchiveEntry.sizeIsSet(entry) ? ArchiveEntry.size(entry) : -1L;
                    long timeMillis = ArchiveEntry.mtimeIsSet(entry) ? ArchiveEntry.mtime(entry) * 1000L : 0L;
                    out.append(directory ? 'D' : 'F')
                            .append('\t').append(path)
                            .append('\t').append(size)
                            .append('\t').append(timeMillis)
                            .append('\n');
                }
                Archive.readDataSkip(reader.archive);
            }
            return out.toString();
        } catch (ArchiveException e) {
            throw toIOException("Could not list archive with libarchive", e);
        } finally {
            closeQuietly(reader);
        }
    }

    static boolean requiresPassword(@NonNull String archivePath) throws IOException {
        return requiresPassword(new String[] { archivePath });
    }

    static boolean requiresPassword(@NonNull String[] archivePaths) throws IOException {
        ensureAvailable();
        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), null);
            int encrypted = Archive.readHasEncryptedEntries(reader.archive);
            if (encrypted > 0) return true;
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                if (ArchiveEntry.isEncrypted(entry)
                        || ArchiveEntry.isDataEncrypted(entry)
                        || ArchiveEntry.isMetadataEncrypted(entry)) {
                    return true;
                }
                Archive.readDataSkip(reader.archive);
            }
            return false;
        } catch (ArchiveException e) {
            String msg = lower(e.getMessage());
            if (msg.contains("password") || msg.contains("passphrase") || msg.contains("encrypted")) return true;
            return false;
        } finally {
            closeQuietly(reader);
        }
    }

    static boolean extractArchive(@NonNull String archivePath,
                                  @NonNull String targetDir,
                                  @Nullable char[] password,
                                  @Nullable FileOperationProgress progress,
                                  @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        return extractArchive(new String[] { archivePath }, targetDir, password, progress, entryProgress);
    }

    static boolean extractArchive(@NonNull String[] archivePaths,
                                  @NonNull String targetDir,
                                  @Nullable char[] password,
                                  @Nullable FileOperationProgress progress,
                                  @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        ensureAvailable();
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        File root = new File(targetDir).getCanonicalFile();
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create output directory");

        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), password);
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
                String path = normalizedEntryPath(entry);
                if (path == null) {
                    Archive.readDataSkip(reader.archive);
                    continue;
                }
                boolean directory = isDirectory(entry);
                if (entryProgress != null) {
                    if (directory) entryProgress.onDirectory(path);
                    else entryProgress.onFile(path);
                } else if (progress != null) {
                    progress.setDetail(new File(path).getName());
                }
                File out = safeOutputFile(root, path);
                if (directory) {
                    if (!out.exists() && !out.mkdirs()) throw new IOException("Cannot create directory: " + out);
                    Archive.readDataSkip(reader.archive);
                } else if (isRegularFile(entry)) {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Cannot create output directory: " + parent);
                    }
                    extractCurrentEntry(reader.archive, entry, out, progress);
                } else {
                    // Do not materialize symlinks/devices from archives in the app sandbox.
                    Archive.readDataSkip(reader.archive);
                }
            }
            return true;
        } catch (ArchiveException e) {
            throw toIOException("Could not extract archive with libarchive", e);
        } finally {
            closeQuietly(reader);
        }
    }

    static boolean extractEntry(@NonNull String archivePath,
                                @NonNull String entryPath,
                                @NonNull String outPath,
                                @Nullable char[] password,
                                @Nullable FileOperationProgress progress) throws IOException {
        return extractEntry(new String[] { archivePath }, entryPath, outPath, password, progress);
    }

    static boolean extractEntry(@NonNull String[] archivePaths,
                                @NonNull String entryPath,
                                @NonNull String outPath,
                                @Nullable char[] password,
                                @Nullable FileOperationProgress progress) throws IOException {
        ensureAvailable();
        String wanted = normalizeForCompare(entryPath);
        if (wanted == null) throw new IOException("Invalid archive entry path");
        File outFile = new File(outPath).getCanonicalFile();
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory");
        }

        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), password);
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
                String path = normalizedEntryPath(entry);
                if (path == null) {
                    Archive.readDataSkip(reader.archive);
                    continue;
                }
                if (!wanted.equals(normalizeForCompare(path))) {
                    Archive.readDataSkip(reader.archive);
                    continue;
                }
                if (isDirectory(entry)) throw new IOException("Archive entry is a directory");
                if (!isRegularFile(entry)) throw new IOException("Unsupported archive entry type");
                if (progress != null) progress.setDetail(new File(path).getName());
                extractCurrentEntry(reader.archive, entry, outFile, progress);
                return true;
            }
            return false;
        } catch (ArchiveException e) {
            throw toIOException("Could not extract archive entry with libarchive", e);
        } finally {
            closeQuietly(reader);
        }
    }

    @NonNull
    private static Reader openReader(@NonNull String[] archivePaths, @Nullable char[] password)
            throws ArchiveException, IOException {
        String[] validated = validateArchivePaths(archivePaths);
        IOException fileOpenFailure = null;
        try {
            return openReaderWithFileNames(validated, password);
        } catch (IOException | SecurityException e) {
            fileOpenFailure = e instanceof IOException
                    ? (IOException) e
                    : new IOException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }

        if (!shouldUseCallbackReaderFallback(validated)) {
            throw fileOpenFailure;
        }

        try {
            return openReaderWithCallbacks(validated, password);
        } catch (IOException | SecurityException e) {
            if (fileOpenFailure != null) {
                e.addSuppressed(fileOpenFailure);
            }
            throw e;
        }
    }

    private static Reader openReaderWithFileNames(@NonNull String[] archivePaths,
                                                  @Nullable char[] password)
            throws ArchiveException, IOException {
        long archive = Archive.readNew();
        boolean opened = false;
        try {
            configureReader(archive, password);
            if (archivePaths.length == 1) {
                Archive.readOpenFileName(
                        archive,
                        archivePaths[0].getBytes(StandardCharsets.UTF_8),
                        BLOCK_SIZE);
            } else {
                byte[][] names = new byte[archivePaths.length][];
                for (int i = 0; i < archivePaths.length; i++) {
                    names[i] = archivePaths[i].getBytes(StandardCharsets.UTF_8);
                }
                Archive.readOpenFileNames(archive, names, BLOCK_SIZE);
            }
            opened = true;
            return new Reader(archive, null);
        } finally {
            if (!opened) {
                freeArchiveQuietly(archive);
            }
        }
    }

    private static Reader openReaderWithCallbacks(@NonNull String[] archivePaths,
                                                  @Nullable char[] password)
            throws ArchiveException, IOException {
        VolumeInput input = null;
        long archive = Archive.readNew();
        boolean opened = false;
        try {
            input = new VolumeInput(archivePaths);
            configureReader(archive, password);

            // Callback mode is now a narrow fallback for embedded-SFX offset handling.
            // Normal RAR/CBR/RAR5 files use readOpenFileName(s) first because that is the
            // libarchive-android API path closest to the native libarchive contract.
            Archive.readSetCallbackData(archive, input);
            Archive.readSetReadCallback(archive, (ignoredArchive, source) -> ((VolumeInput) source).read());
            Archive.readSetSkipCallback(archive, (ignoredArchive, source, request) -> ((VolumeInput) source).skip(request));
            Archive.readSetSeekCallback(archive, (ignoredArchive, source, offset, whence) -> ((VolumeInput) source).seek(offset, whence));
            Archive.readSetCloseCallback(archive, (ignoredArchive, source) -> ((VolumeInput) source).close());
            Archive.readOpen1(archive);
            opened = true;
            return new Reader(archive, input);
        } finally {
            if (!opened) {
                if (input != null) input.closeQuietly();
                freeArchiveQuietly(archive);
            }
        }
    }

    private static void configureReader(long archive, @Nullable char[] password) throws ArchiveException {
        Archive.setCharset(archive, StandardCharsets.UTF_8.name().getBytes(StandardCharsets.UTF_8));
        Archive.readSupportFilterAll(archive);
        enableAllSupportedFormats(archive);
        if (password != null && password.length > 0) {
            Archive.readAddPassphrase(archive, new String(password).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean shouldUseCallbackReaderFallback(@NonNull String[] archivePaths) {
        if (archivePaths.length != 1) return false;
        File file = new File(archivePaths[0]);
        if (!shouldScanForEmbeddedRarSignature(file)) return false;
        try {
            return findRarSignatureOffset(file) > 0L;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }


    private static void enableAllSupportedFormats(long archive) throws ArchiveException {
        try {
            Archive.readSupportFormatAll(archive);
        } catch (ArchiveException e) {
            throw e;
        } catch (LinkageError e) {
            throw new ArchiveException(Archive.ERRNO_FATAL,
                    "libarchive format method is not linkable: readSupportFormatAll", e);
        }
    }

    private static long nextHeaderEntry(long archive) throws ArchiveException {
        // libarchive-android's sample reader uses readNextHeader(), which returns an owned
        // entry pointer and 0 at EOF. Do not treat the returned long as an ARCHIVE_* status
        // code: native pointers can look like large negative Java long values on Android.
        return Archive.readNextHeader(archive);
    }

    private static void extractCurrentEntry(long archive,
                                            long entry,
                                            @NonNull File outFile,
                                            @Nullable FileOperationProgress progress)
            throws IOException, ArchiveException {
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        long size = ArchiveEntry.sizeIsSet(entry) ? Math.max(0L, ArchiveEntry.size(entry)) : -1L;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(outFile,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY)) {
            Archive.readDataIntoFd(archive, pfd.getFd());
        }
        if (progress != null && size > 0L) progress.addDoneBytes(size);
    }

    @Nullable
    private static String normalizedEntryPath(long entry) {
        String path = ArchiveEntry.pathnameUtf8(entry);
        if (path == null) return null;
        path = path.replace('\\', '/').trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.startsWith("./")) path = path.substring(2);
        if (path.isEmpty()) return null;
        if (path.equals("..") || path.startsWith("../") || path.contains("/../") || path.endsWith("/..")) return null;
        if (path.matches("^[A-Za-z]:.*")) return null;
        return path;
    }

    @Nullable
    private static String normalizeForCompare(@Nullable String input) {
        if (input == null) return null;
        String path = input.replace('\\', '/').trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.startsWith("./")) path = path.substring(2);
        return path.toLowerCase(Locale.ROOT);
    }

    private static boolean isDirectory(long entry) {
        return (ArchiveEntry.filetype(entry) & ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFDIR;
    }

    private static boolean isRegularFile(long entry) {
        return (ArchiveEntry.filetype(entry) & ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFREG;
    }

    @NonNull
    private static File safeOutputFile(@NonNull File root, @NonNull String entryPath) throws IOException {
        File out = new File(root, entryPath).getCanonicalFile();
        String rootPath = root.getPath();
        String outPath = out.getPath();
        if (!outPath.equals(rootPath) && !outPath.startsWith(rootPath + File.separator)) {
            throw new IOException("Archive entry escapes target directory");
        }
        return out;
    }

    @NonNull
    private static String[] validateArchivePaths(@NonNull String[] archivePaths) throws IOException {
        if (archivePaths.length == 0) throw new IOException("Archive path list is empty");
        List<String> out = new ArrayList<>(archivePaths.length);
        for (String path : archivePaths) {
            if (path == null || path.length() == 0) throw new IOException("Archive path is empty");
            out.add(new File(path).getAbsolutePath());
        }
        return out.toArray(new String[0]);
    }

    @NonNull
    private static ProbeResult probeRarFormatSupportForDiagnosticsOnly() {
        long archive = 0L;
        try {
            archive = Archive.readNew();
            Archive.readSupportFilterAll(archive);
            Archive.readSupportFormatRar(archive);
            Archive.readSupportFormatRar5(archive);
            return new ProbeResult(true, "RAR/RAR5 format probe: OK");
        } catch (Throwable t) {
            return new ProbeResult(false, "RAR/RAR5 format probe failed: "
                    + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        } finally {
            if (archive != 0L) freeArchiveQuietly(archive);
        }
    }

    private static final class ProbeResult {
        final boolean available;
        @NonNull
        final String detail;

        ProbeResult(boolean available, @NonNull String detail) {
            this.available = available;
            this.detail = detail;
        }
    }

    private static void ensureAvailable() throws IOException {
        if (!AVAILABLE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(STATUS);
        }
    }

    private static IOException toIOException(@NonNull String prefix, @NonNull ArchiveException e) {
        return toIOException(prefix, e.getMessage(), e);
    }

    private static IOException toIOException(@NonNull String prefix,
                                             @Nullable String message,
                                             @Nullable Throwable cause) {
        String lower = lower(message);
        if (isPasswordOrEncryptionError(lower)) {
            return new ArchiveSupport.PasswordRequiredException();
        }
        String detail = prefix + (message == null || message.isEmpty() ? "" : ": " + message);
        if (isUnsupportedBackendError(lower)) {
            return new RarArchiveReader.UnsupportedRarFeatureException(detail);
        }
        return cause == null ? new IOException(detail) : new IOException(detail, cause);
    }

    private static boolean isPasswordOrEncryptionError(@NonNull String lower) {
        return lower.contains("password")
                || lower.contains("passphrase")
                || lower.contains("encrypted");
    }

    private static boolean isUnsupportedBackendError(@NonNull String lower) {
        return lower.contains("unsupported")
                || lower.contains("not supported")
                || lower.contains("support unavailable")
                || lower.contains("solid archive support unavailable")
                || lower.contains("couldn't find out rar header")
                || lower.contains("bad rar file data");
    }

    @NonNull
    private static String lower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void closeQuietly(@Nullable Reader reader) {
        if (reader == null) return;
        freeArchiveQuietly(reader.archive);
        if (reader.input != null) reader.input.closeQuietly();
    }

    private static void freeArchiveQuietly(long archive) {
        if (archive == 0L) return;
        try {
            Archive.readFree(archive);
        } catch (Throwable ignored) {
        }
    }


    private static boolean shouldScanForEmbeddedRarSignature(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") || name.endsWith(".sfx") || name.endsWith(".bin");
    }

    private static int findRarSignatureOffset(@NonNull File file) throws IOException {
        long length = Math.max(0L, file.length());
        int scanLimit = (int) Math.min(length, MAX_SFX_SCAN + RAR5_SIGNATURE.length);
        if (scanLimit <= 0) return 0;
        byte[] data = new byte[scanLimit];
        int read = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            while (read < data.length) {
                int count = raf.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        if (read < data.length) {
            byte[] shrunk = new byte[read];
            System.arraycopy(data, 0, shrunk, 0, read);
            data = shrunk;
        }
        int rar5 = indexOf(data, RAR5_SIGNATURE);
        int rar4 = indexOf(data, RAR4_SIGNATURE);
        if (rar5 < 0 && rar4 < 0) return 0;
        if (rar5 >= 0 && (rar4 < 0 || rar5 <= rar4)) return rar5;
        return rar4;
    }

    private static int indexOf(@NonNull byte[] haystack, @NonNull byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return -1;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static final class Reader {
        final long archive;
        @Nullable
        final VolumeInput input;

        Reader(long archive, @Nullable VolumeInput input) {
            this.archive = archive;
            this.input = input;
        }
    }

    private static final class VolumeInput {
        private final List<Volume> volumes = new ArrayList<>();
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(BLOCK_SIZE);
        private final byte[] scratch = new byte[8192];
        private long position;
        private long totalLength;
        private int currentIndex = -1;
        private RandomAccessFile currentFile;

        VolumeInput(@NonNull String[] archivePaths) throws IOException {
            for (int i = 0; i < archivePaths.length; i++) {
                File file = new File(archivePaths[i]).getCanonicalFile();
                if (!file.isFile()) throw new IOException("Missing archive file: " + file);
                long offset = i == 0 && shouldScanForEmbeddedRarSignature(file)
                        ? findRarSignatureOffset(file)
                        : 0L;
                long readable = Math.max(0L, file.length() - offset);
                volumes.add(new Volume(i, file, offset, totalLength, readable));
                totalLength += readable;
            }
            if (volumes.isEmpty() || totalLength <= 0L) throw new IOException("Archive path list is empty");
        }

        @Nullable
        ByteBuffer read() throws ArchiveException {
            buffer.clear();
            if (position >= totalLength) {
                buffer.flip();
                return buffer;
            }
            try {
                while (buffer.hasRemaining() && position < totalLength) {
                    Volume volume = volumeForPosition(position);
                    ensureOpen(volume);
                    long inVolume = position - volume.logicalStart;
                    long realOffset = volume.fileOffset + inVolume;
                    currentFile.seek(realOffset);
                    int max = (int) Math.min(buffer.remaining(), volume.length - inVolume);
                    int count = currentFile.read(scratch, 0, Math.min(scratch.length, max));
                    if (count < 0) break;
                    buffer.put(scratch, 0, count);
                    position += count;
                    if (count == 0) break;
                }
                buffer.flip();
                return buffer;
            } catch (IOException e) {
                throw new ArchiveException(Archive.ERRNO_FATAL, "Archive read failed", e);
            }
        }

        long skip(long request) {
            if (request <= 0L) return 0L;
            long skipped = Math.min(request, Math.max(0L, totalLength - position));
            position += skipped;
            return skipped;
        }

        long seek(long offset, int whence) throws ArchiveException {
            long base;
            if (whence == OsConstants.SEEK_SET) {
                base = 0L;
            } else if (whence == OsConstants.SEEK_CUR) {
                base = position;
            } else if (whence == OsConstants.SEEK_END) {
                base = totalLength;
            } else {
                throw new ArchiveException(Archive.ERRNO_FATAL, "Unsupported archive seek mode");
            }
            long next = base + offset;
            if (next < 0L) throw new ArchiveException(Archive.ERRNO_FATAL, "Negative archive seek");
            position = Math.min(next, totalLength);
            return position;
        }

        void close() {
            closeQuietly();
        }

        void closeQuietly() {
            if (currentFile != null) {
                try {
                    currentFile.close();
                } catch (IOException ignored) {
                }
                currentFile = null;
                currentIndex = -1;
            }
        }

        @NonNull
        private Volume volumeForPosition(long logicalPosition) throws IOException {
            for (Volume volume : volumes) {
                if (logicalPosition >= volume.logicalStart
                        && logicalPosition < volume.logicalStart + volume.length) {
                    return volume;
                }
            }
            throw new IOException("Archive position out of range");
        }

        private void ensureOpen(@NonNull Volume volume) throws IOException {
            if (currentIndex == volume.index && currentFile != null) return;
            closeQuietly();
            currentFile = new RandomAccessFile(volume.file, "r");
            currentIndex = volume.index;
        }
    }

    private static final class Volume {
        final int index;
        final File file;
        final long fileOffset;
        final long logicalStart;
        final long length;

        Volume(int index, @NonNull File file, long fileOffset, long logicalStart, long length) {
            this.index = index;
            this.file = file;
            this.fileOffset = fileOffset;
            this.logicalStart = logicalStart;
            this.length = length;
        }
    }
}
