package com.readwide.manager.archive;

import android.os.Build;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
        ArchiveCancellation.checkpoint(null);
        ensureAvailable();
        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), password);
            StringBuilder out = new StringBuilder(RarLibarchiveFallback.LISTING_HEADER);
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                ArchiveCancellation.checkpoint(null);
                String path = normalizedEntryPath(entry);
                if (path != null && (isDirectory(entry) || isRegularFile(entry))) {
                    boolean directory = isDirectory(entry);
                    long size = ArchiveEntry.sizeIsSet(entry) ? ArchiveEntry.size(entry) : -1L;
                    long timeMillis = ArchiveEntry.mtimeIsSet(entry) ? ArchiveEntry.mtime(entry) * 1000L : 0L;
                    out.append(directory ? 'D' : 'F')
                            .append('\t').append(RarLibarchiveFallback.encodeListingPath(path))
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
        ArchiveCancellation.checkpoint(null);
        ensureAvailable();
        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), null);
            int encrypted = Archive.readHasEncryptedEntries(reader.archive);
            if (encrypted > 0) return true;
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                ArchiveCancellation.checkpoint(null);
                if (ArchiveEntry.isEncrypted(entry)
                        || ArchiveEntry.isDataEncrypted(entry)
                        || ArchiveEntry.isMetadataEncrypted(entry)) {
                    return true;
                }
                Archive.readDataSkip(reader.archive);
            }
            return false;
        } catch (ArchiveException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
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
        ArchiveCancellation.checkpoint(progress);
        ensureAvailable();
        File root = new File(targetDir).getCanonicalFile();
        RarOutputFileGuard.ensureDirectory(root);

        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), password);
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                ArchiveCancellation.checkpoint(progress);
                String path = normalizedEntryPath(entry);
                if (path == null && isDirectory(entry)
                        && isRelativeRootMarker(ArchiveEntry.pathnameUtf8(entry))) {
                    Archive.readDataSkip(reader.archive);
                    continue;
                }
                if (path == null) throw new IOException("Unsafe or empty archive member path");
                if (!isDirectory(entry) && !isRegularFile(entry)) {
                    // Links/devices are not payload files, even when a hardlink's
                    // file type is reported as regular by the container decoder.
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
                    RarOutputFileGuard.ensureDirectory(out);
                    Archive.readDataSkip(reader.archive);
                } else {
                    extractCurrentEntry(reader.archive, entry, out, progress);
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
        return extractEntryInternal(archivePaths, entryPath, outPath, password, progress, false);
    }

    static boolean extractEntryWithSequentialPriming(@NonNull String[] archivePaths,
                                                    @NonNull String entryPath,
                                                    @NonNull String outPath,
                                                    @Nullable char[] password,
                                                    @Nullable FileOperationProgress progress) throws IOException {
        return extractEntryInternal(archivePaths, entryPath, outPath, password, progress, true);
    }

    private static boolean extractEntryInternal(@NonNull String[] archivePaths,
                                                @NonNull String entryPath,
                                                @NonNull String outPath,
                                                @Nullable char[] password,
                                                @Nullable FileOperationProgress progress,
                                                boolean primeSkippedRegularFiles) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        ensureAvailable();
        String wanted = normalizeForCompare(entryPath);
        if (wanted == null) throw new IOException("Invalid archive entry path");
        File outFile = new File(outPath).getCanonicalFile();

        Reader reader = null;
        try {
            reader = openReader(validateArchivePaths(archivePaths), password);
            long entry;
            while ((entry = nextHeaderEntry(reader.archive)) != 0L) {
                ArchiveCancellation.checkpoint(progress);
                if (!isDirectory(entry) && !isRegularFile(entry)) {
                    Archive.readDataSkip(reader.archive);
                    continue;
                }
                String path = normalizedEntryPath(entry);
                String comparePath = path == null ? null : normalizeForCompare(path);
                if (comparePath == null || !wanted.equals(comparePath)) {
                    if (primeSkippedRegularFiles && isRegularFile(entry)) {
                        drainCurrentRegularEntryForSequentialDecoder(reader.archive, progress);
                    } else {
                        Archive.readDataSkip(reader.archive);
                    }
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
        ArchiveCancellation.checkpoint(null);
        String[] validated = validateArchivePaths(archivePaths);
        IOException fileOpenFailure = null;
        try {
            return openReaderWithFileNames(validated, password);
        } catch (IOException | SecurityException e) {
            ArchiveCancellation.rethrowIfCancelled(e, null);
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

    private static long nextHeaderEntry(long archive) throws IOException {
        ArchiveCancellation.checkpoint(null);
        // libarchive-android's sample reader uses readNextHeader(), which returns an owned
        // entry pointer and 0 at EOF. Do not treat the returned long as an ARCHIVE_* status
        // code: native pointers can look like large negative Java long values on Android.
        return Archive.readNextHeader(archive);
    }

    private static void extractCurrentEntry(long archive, long entry,
                                            @NonNull File outFile,
                                            @Nullable FileOperationProgress progress)
            throws IOException, ArchiveException {
        ArchiveCancellation.checkpoint(progress);
        boolean knownSize = ArchiveEntry.sizeIsSet(entry);
        long expected = knownSize ? ArchiveEntry.size(entry) : -1L;
        if (knownSize && expected < 0L) throw new IOException("Invalid archive entry size");
        ByteBuffer block = ByteBuffer.allocateDirect(BLOCK_SIZE);
        byte[] decoded = new byte[BLOCK_SIZE];
        long total = 0L;
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                while (true) {
                    ArchiveCancellation.checkpoint(progress);
                    block.clear();
                    Archive.readData(archive, block);
                    int produced = block.position();
                    if (produced <= 0) break;
                    total = ArchiveSupport.checkedAddDecodedStreamBytes(total, produced);
                    if (expected >= 0L && total > expected) throw new IOException("Archive entry exceeds declared size");
                    block.flip();
                    block.get(decoded, 0, produced);
                    // The operation-wide budget is charged by this guarded output
                    // before each physical write, including entries whose header did
                    // not declare an unpacked size.
                    out.write(decoded, 0, produced);
                    if (progress != null) progress.addDoneBytes(produced);
                }
                if (expected >= 0L && total != expected) throw new IOException("Truncated archive entry");
                out.flush();
            }
            guard.commit();
        }
    }

    private static void drainCurrentRegularEntryForSequentialDecoder(long archive,
                                                                     @Nullable FileOperationProgress progress)
            throws IOException, ArchiveException {
        ArchiveCancellation.checkpoint(progress);
        ByteBuffer block = ByteBuffer.allocateDirect(BLOCK_SIZE);
        long decodedBytes = 0L;
        while (true) {
            ArchiveCancellation.checkpoint(progress);
            block.clear();
            Archive.readData(archive, block);
            int produced = block.position();
            if (produced <= 0) return;
            if (decodedBytes > Long.MAX_VALUE - produced) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "Sequential archive entry byte count overflow");
            }
            decodedBytes += produced;
        }
    }

    @Nullable
    private static String normalizedEntryPath(long entry) {
        return normalizeForCompare(ArchiveEntry.pathnameUtf8(entry));
    }

    @Nullable
    private static String normalizeForCompare(@Nullable String input) {
        return ArchiveSupport.sanitizeEntryPathForList(input);
    }

    private static boolean isRelativeRootMarker(@Nullable String path) {
        return path != null && path.replace('\\', '/').matches("(?:\\./)+|\\.");
    }

    private static boolean isDirectory(long entry) {
        return (ArchiveEntry.filetype(entry) & ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFDIR;
    }

    private static boolean isRegularFile(long entry) {
        return (ArchiveEntry.filetype(entry) & ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFREG
                && !ArchiveEntry.hardlinkIsSet(entry);
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
        try { ArchiveCancellation.rethrowIfCancelled(cause, null); }
        catch (java.io.InterruptedIOException cancelled) { return cancelled; }
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

    /**
     * Opens a forward-streaming handle over an archive (single file or multi-volume set),
     * exposing libarchive's natural forward iteration to the shared sequential image reader.
     * This is the streaming counterpart to the one-shot list/extract helpers above: instead of
     * walking the whole archive in one call, the caller advances entry by entry and reads each
     * entry's data on demand. Used for RAR/CBR, whose libarchive backend has no random access.
     */
    @NonNull
    static ForwardStream openForwardStream(@NonNull String[] archivePaths, @Nullable char[] password)
            throws IOException {
        ArchiveCancellation.checkpoint(null);
        ensureAvailable();
        String[] paths = validateArchivePaths(archivePaths);
        ArchiveSupport.ForwardArchiveReader supplemental = SevenZNativeForwardAdapter.open(paths, password);
        if (supplemental != null) {
            try { return new ForwardStream(supplemental); }
            catch (RuntimeException | Error failure) {
                try { supplemental.close(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        Reader reader = openReader(paths, password);
        boolean handed = false;
        try {
            ForwardStream stream = new ForwardStream(reader);
            handed = true;
            return stream;
        } finally {
            // The native ForwardStream constructor allocates a direct ByteBuffer, which can fail with
            // an OutOfMemoryError after the native reader handle is already open. Mirror the
            // opened/finally guard the other openReader* paths use so the native handle (and any
            // volume input) is freed rather than leaked if construction does not complete.
            if (!handed) closeQuietly(reader);
        }
    }

    /**
     * Opens one compressor stream (for example a plain .zst file) with libarchive's
     * raw/empty formats. Raw is intentionally not enabled on the generic archive
     * reader because it would make arbitrary corrupt archives look like one file.
     */
    @NonNull
    static ForwardStream openRawForwardStream(@NonNull String archivePath) throws IOException {
        ArchiveCancellation.checkpoint(null);
        ensureAvailable();
        String[] paths = validateArchivePaths(new String[]{archivePath});
        Reader reader = openRawReader(paths[0]);
        boolean handed = false;
        try {
            ForwardStream stream = new ForwardStream(reader);
            handed = true;
            return stream;
        } finally {
            if (!handed) closeQuietly(reader);
        }
    }

    @NonNull
    private static Reader openRawReader(@NonNull String archivePath)
            throws ArchiveException, IOException {
        if (new File(archivePath).length() == 0L) throw new IOException("Missing Zstandard frame");
        long archive = Archive.readNew();
        boolean opened = false;
        try {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().getBytes(StandardCharsets.UTF_8));
            // A raw reader must apply one Zstd layer, not recursively decode a .zst
            // payload inside it. The forced decoder still rejects malformed frames;
            // an empty decoded frame is valid, but a missing packed frame is not.
            Archive.readSupportFilterZstd(archive);
            Archive.readAppendFilter(archive, Archive.FILTER_ZSTD);
            try {
                Archive.readSupportFormatRaw(archive);
                Archive.readSupportFormatEmpty(archive);
            } catch (LinkageError e) {
                throw new ArchiveException(Archive.ERRNO_FATAL,
                        "libarchive raw/empty format methods are not linkable", e);
            }
            Archive.readOpenFileName(
                    archive,
                    archivePath.getBytes(StandardCharsets.UTF_8),
                    BLOCK_SIZE);
            boolean selectedZstd = false;
            int filterCount = Archive.filterCount(archive);
            for (int index = 0; index < filterCount; index++) {
                if (Archive.filterCode(archive, index) == Archive.FILTER_ZSTD) {
                    selectedZstd = true;
                    break;
                }
            }
            if (!selectedZstd) {
                throw new ArchiveException(Archive.ERRNO_FATAL,
                        "Input is not a Zstandard-compressed stream");
            }
            opened = true;
            return new Reader(archive, null);
        } finally {
            if (!opened) freeArchiveQuietly(archive);
        }
    }

    /** Metadata for the entry the {@link ForwardStream} is currently positioned on. */
    static final class ForwardStreamEntry {
        @Nullable final String path;
        final boolean directory;
        final boolean regularFile;
        final boolean encrypted;
        final long size;

        ForwardStreamEntry(@Nullable String path, boolean directory, boolean regularFile,
                           boolean encrypted, long size) {
            this.path = path;
            this.directory = directory;
            this.regularFile = regularFile;
            this.encrypted = encrypted;
            this.size = size;
        }
    }

    /**
     * A single open libarchive reader positioned for forward iteration. Not thread-safe; the
     * caller (a session-scoped sequential reader) serializes access under its own lock.
     */
    static final class ForwardStream {
        @Nullable private final Reader reader;
        @Nullable private final ArchiveSupport.ForwardArchiveReader supplemental;
        private final ByteBuffer block;
        private boolean haveCurrentEntry;
        private boolean closed;
        private boolean cleanupComplete;
        @Nullable private IOException failure;

        private ForwardStream(@NonNull Reader reader) {
            this.reader = reader;
            supplemental = null;
            block = ByteBuffer.allocateDirect(BLOCK_SIZE);
        }

        private ForwardStream(@NonNull ArchiveSupport.ForwardArchiveReader supplemental) {
            reader = null;
            this.supplemental = supplemental;
            block = null;
        }

        /**
         * Advances to the next archive entry, returning its metadata, or null at end of archive.
         * Callers must explicitly drain entries whose decoded bytes contribute to a solid
         * dictionary before advancing; a header-level skip is not sufficient for every RAR.
         */
        @Nullable
        ForwardStreamEntry nextEntry() throws IOException {
            try {
                checkpoint();
                ForwardStreamEntry entry = advance();
                checkpoint();
                return entry;
            } catch (IOException error) { throw retire(error); }
            catch (RuntimeException | Error error) { retire(error); throw error; }
        }

        @Nullable
        private ForwardStreamEntry advance() throws IOException {
            if (closed) return null;
            if (supplemental != null) {
                ArchiveSupport.ForwardEntry entry = supplemental.nextEntry();
                haveCurrentEntry = entry != null;
                return entry == null ? null : new ForwardStreamEntry(entry.path, entry.directory,
                        entry.hasData, false, -1L);
            }
            try {
                long entry = nextHeaderEntry(reader.archive);
                if (entry == 0L) {
                    haveCurrentEntry = false;
                    return null;
                }
                haveCurrentEntry = true;
                boolean encrypted = ArchiveEntry.isEncrypted(entry)
                        || ArchiveEntry.isDataEncrypted(entry)
                        || ArchiveEntry.isMetadataEncrypted(entry);
                long size = ArchiveEntry.sizeIsSet(entry) ? ArchiveEntry.size(entry) : -1L;
                boolean directory = isDirectory(entry), regular = isRegularFile(entry);
                return new ForwardStreamEntry(directory || regular ? normalizedEntryPath(entry) : null,
                        directory, regular, encrypted, size);
            } catch (ArchiveException e) {
                throw toIOException("Could not read next archive header with libarchive", e);
            }
        }

        /**
         * Reads up to {@code out.length} bytes of the current entry into {@code out}, returning
         * the number of bytes read or -1 at the end of the current entry. {@code readData}
         * advances the direct buffer's position by the byte count (a count of 0, i.e. no
         * advance, marks the end of the current entry's data).
         */
        int read(@NonNull byte[] out) throws IOException {
            java.util.Objects.requireNonNull(out, "out");
            try {
                checkpoint();
                if (out.length == 0) return 0;
                int count = readCurrent(out);
                checkpoint();
                if (count < -1 || count == 0 || count > out.length) {
                    throw new IOException("Invalid forward archive read count");
                }
                return count;
            } catch (IOException error) { throw retire(error); }
            catch (RuntimeException | Error error) { retire(error); throw error; }
        }

        private int readCurrent(byte[] out) throws IOException {
            if (closed || !haveCurrentEntry) return -1;
            if (supplemental != null) return supplemental.read(out);
            block.clear();
            if (out.length < block.capacity()) {
                block.limit(out.length);
            }
            try {
                Archive.readData(reader.archive, block);
            } catch (ArchiveException e) {
                throw toIOException("Could not read archive entry data with libarchive", e);
            }
            int produced = block.position();
            if (produced <= 0) return -1;
            block.flip();
            block.get(out, 0, produced);
            return produced;
        }

        /**
         * Decodes the current member into the reusable direct buffer and discards
         * it. This primes solid RAR/7z state like a normal read while avoiding the
         * extra direct-buffer-to-byte-array copy used by {@link #read(byte[])}.
         */
        boolean drainCurrentEntry(long maxDecodedBytes) throws IOException {
            try {
                checkpoint();
                boolean drained = drain(maxDecodedBytes);
                checkpoint();
                return drained;
            } catch (IOException error) { throw retire(error); }
            catch (RuntimeException | Error error) { retire(error); throw error; }
        }

        private boolean drain(long maxDecodedBytes) throws IOException {
            if (closed || !haveCurrentEntry) return true;
            if (supplemental != null) {
                boolean drained = supplemental.drainCurrentEntry(maxDecodedBytes < 0 ? Long.MAX_VALUE : maxDecodedBytes);
                if (drained) haveCurrentEntry = false;
                return drained;
            }
            long total = 0L;
            while (true) {
                checkpoint();
                block.clear();
                try {
                    Archive.readData(reader.archive, block);
                } catch (ArchiveException e) {
                    throw toIOException("Could not drain archive entry data with libarchive", e);
                }
                checkpoint();
                int produced = block.position();
                if (produced <= 0) {
                    haveCurrentEntry = false;
                    return true;
                }
                if (total > Long.MAX_VALUE - produced) throw new IOException("Archive drain byte count overflow");
                total += produced;
                if (maxDecodedBytes >= 0L && total > maxDecodedBytes) {
                    throw new IOException("Sequential archive entry exceeds the extraction safety limit");
                }
            }
        }

        private void checkpoint() throws IOException {
            if (failure != null) throw failure;
            ArchiveCancellation.checkpoint(null);
        }

        private IOException retire(Throwable error) {
            if (failure == null) failure = error instanceof IOException
                    ? (IOException) error : new IOException("Forward archive reader failed", error);
            try { close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != error) error.addSuppressed(cleanup);
            }
            return failure;
        }

        void close() throws IOException {
            if (cleanupComplete) return;
            closed = true;
            haveCurrentEntry = false;
            if (supplemental != null) supplemental.close();
            else closeQuietly(reader);
            cleanupComplete = true;
        }
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
        private boolean closed;
        private ArchiveException failure;

        VolumeInput(@NonNull String[] archivePaths) throws IOException {
            for (int i = 0; i < archivePaths.length; i++) {
                ArchiveCancellation.checkpoint(null);
                File file = new File(archivePaths[i]).getCanonicalFile();
                if (!file.isFile()) throw new IOException("Missing archive file: " + file);
                long offset = i == 0 && shouldScanForEmbeddedRarSignature(file)
                        ? findRarSignatureOffset(file)
                        : 0L;
                long readable = Math.max(0L, file.length() - offset);
                if (readable > Long.MAX_VALUE - totalLength) throw new IOException("Archive volume size overflow");
                volumes.add(new Volume(i, file, offset, totalLength, readable));
                totalLength += readable;
            }
            if (volumes.isEmpty() || totalLength <= 0L) throw new IOException("Archive path list is empty");
        }

        @Nullable
        ByteBuffer read() throws ArchiveException {
            try {
                requireReadable();
                buffer.clear();
                Volume checked = null;
                while (buffer.hasRemaining() && position < totalLength) {
                    ArchiveCancellation.checkpoint(null);
                    Volume volume = volumeForPosition(position);
                    if (checked != volume) { volume.check(); checked = volume; }
                    ensureOpen(volume);
                    long inVolume = position - volume.logicalStart;
                    long realOffset = volume.fileOffset + inVolume;
                    currentFile.seek(realOffset);
                    int max = (int) Math.min(buffer.remaining(), volume.length - inVolume);
                    int count = currentFile.read(scratch, 0, Math.min(scratch.length, max));
                    if (count <= 0) throw new IOException("Truncated or stalled archive volume");
                    buffer.put(scratch, 0, count);
                    position += count;
                }
                if (checked != null) checked.check();
                buffer.flip();
                return buffer;
            } catch (IOException e) {
                throw retire(e);
            }
        }

        long skip(long request) throws ArchiveException {
            try {
                requireReadable();
                if (request <= 0L) return 0L;
                long skipped = Math.min(request, totalLength - position);
                // A metadata skip must not hide a shortened source volume.
                long end = position + skipped;
                for (Volume volume : volumes) {
                    if (volume.logicalStart < end && volume.logicalStart + volume.length > position) volume.check();
                }
                position = end;
                return skipped;
            } catch (IOException error) { throw retire(error); }
        }

        long seek(long offset, int whence) throws ArchiveException {
            try {
                requireReadable();
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
                if (offset > 0L && base > Long.MAX_VALUE - offset) throw new IOException("Archive seek overflow");
                long next = base + offset;
                if (next < 0L) throw new ArchiveException(Archive.ERRNO_FATAL, "Negative archive seek");
                position = Math.min(next, totalLength);
                return position;
            } catch (IOException error) { throw retire(error); }
        }

        private void requireReadable() throws IOException {
            if (failure != null) throw failure;
            if (closed) throw new IOException("Archive volume input is closed");
            ArchiveCancellation.checkpoint(null);
        }

        private ArchiveException retire(IOException error) {
            if (failure == null) failure = error instanceof ArchiveException ? (ArchiveException) error
                    : new ArchiveException(Archive.ERRNO_FATAL, "Archive input failed", error);
            try { close(); } catch (ArchiveException cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            return failure;
        }

        void close() throws ArchiveException {
            closed = true;
            try { closeCurrentFile(); }
            catch (IOException error) { throw new ArchiveException(Archive.ERRNO_FATAL, "Archive input close failed", error); }
        }

        void closeQuietly() { try { close(); } catch (ArchiveException ignored) {} }

        private void closeCurrentFile() throws IOException {
            if (currentFile != null) {
                currentFile.close();
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
            closeCurrentFile();
            currentFile = new RandomAccessFile(volume.file, "r");
            currentIndex = volume.index;
            volume.check();
            if (currentFile.length() != volume.fileOffset + volume.length) throw new IOException("Archive volume changed");
        }
    }

    private static final class Volume {
        final int index;
        final File file;
        final long fileOffset;
        final long logicalStart;
        final long length;
        final long modified;

        Volume(int index, @NonNull File file, long fileOffset, long logicalStart, long length) {
            this.index = index;
            this.file = file;
            this.fileOffset = fileOffset;
            this.logicalStart = logicalStart;
            this.length = length;
            this.modified = file.lastModified();
        }

        void check() throws IOException {
            ArchiveCancellation.checkpoint(null);
            if (!file.isFile() || file.length() != fileOffset + length || file.lastModified() != modified) {
                throw new IOException("Archive volume changed while reading");
            }
        }
    }
}
