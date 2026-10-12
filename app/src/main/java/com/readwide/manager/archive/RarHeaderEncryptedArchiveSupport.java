package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level routing for RAR3/RAR4 archives created with encrypted headers (-hp).
 *
 * <p>The low-level decryptor only rewrites headers. This class owns the temporary-file lifecycle
 * and sends the rewritten visible-header archive back through the existing RAR router so stored,
 * encrypted stored, split rewrite, and libarchive-backed compressed paths are reused.</p>
 */
final class RarHeaderEncryptedArchiveSupport {
    private RarHeaderEncryptedArchiveSupport() {}

    @Nullable
    static List<ArchiveSupport.EntryInfo> tryListEntries(@NonNull File archive,
                                                         @Nullable char[] password) throws IOException {
        if (!hasRar4HeaderEncryptedVolume(archive)) return null;
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        HeaderDecryptedChain decrypted = null;
        try {
            decrypted = buildDecryptedChain(archive, password, null);
            return RarArchiveReader.listEntries(decrypted.firstVolume, password);
        } finally {
            closeQuietly(decrypted);
        }
    }

    static boolean tryExtractArchive(@NonNull File archive,
                                     @NonNull File targetDir,
                                     @Nullable char[] password,
                                     @Nullable FileOperationProgress progress,
                                     @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (!hasRar4HeaderEncryptedVolume(archive)) return false;
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        HeaderDecryptedChain decrypted = null;
        try {
            decrypted = buildDecryptedChain(archive, password, progress);
            return RarArchiveReader.extractArchiveIntoDirectory(
                    decrypted.firstVolume,
                    targetDir,
                    password,
                    progress,
                    entryProgress);
        } finally {
            closeQuietly(decrypted);
        }
    }

    static boolean tryExtractSingleEntry(@NonNull File archive,
                                         @NonNull String entryPath,
                                         @NonNull File outFile,
                                         @Nullable char[] password,
                                         @Nullable FileOperationProgress progress) throws IOException {
        if (!hasRar4HeaderEncryptedVolume(archive)) return false;
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        HeaderDecryptedChain decrypted = null;
        try {
            decrypted = buildDecryptedChain(archive, password, progress);
            return RarArchiveReader.extractSingleEntry(decrypted.firstVolume, entryPath, outFile, password);
        } finally {
            closeQuietly(decrypted);
        }
    }

    @Nullable
    static ArchiveSupport.ForwardArchiveReader openForwardReader(File archive, char[] password,
            File spoolDirectory, boolean nativeAvailable) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("RAR extraction cancelled");
        if (spoolDirectory == null || !hasRar4HeaderEncryptedVolume(archive)) return null;
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        HeaderDecryptedChain chain = buildDecryptedChain(archive, password, null);
        boolean transferred = false;
        try {
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(chain.firstVolume, password);
            // Header copies already belong to this first-party operation; permit its stored fallback.
            ArchiveSupport.ForwardArchiveReader delegate = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                    entries, password, spoolDirectory, false);
            if (delegate == null) delegate = Rar3CheckedForwardReader.open(entries, spoolDirectory, false);
            if (delegate == null) return null;
            OwnedForwardReader reader = new OwnedForwardReader(delegate, chain);
            transferred = true;
            return reader;
        } finally {
            if (!transferred) closeQuietly(chain);
        }
    }

    private static final class OwnedForwardReader implements ArchiveSupport.ForwardArchiveReader {
        private final ArchiveSupport.ForwardArchiveReader delegate;
        private final HeaderDecryptedChain chain;
        private boolean closed;
        OwnedForwardReader(ArchiveSupport.ForwardArchiveReader delegate, HeaderDecryptedChain chain) {
            this.delegate = delegate; this.chain = chain;
        }
        private void requireOpen() throws IOException {
            if (closed) throw new IOException("RAR header-decrypted reader is closed");
        }
        @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
            requireOpen();
            try { return delegate.nextEntry(); }
            catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
        }
        @Override public int read(byte[] buffer) throws IOException {
            requireOpen();
            try { return delegate.read(buffer); }
            catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
        }
        @Override public boolean drainCurrentEntry(long maximum) throws IOException {
            requireOpen();
            try { return delegate.drainCurrentEntry(maximum); }
            catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
        }
        private void retire(Throwable failure) {
            try { close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
        @Override public void close() throws IOException {
            closed = true;
            try { delegate.close(); }
            catch (IOException | RuntimeException | Error failure) {
                try { deleteOwnedTree(chain.singleTempFile ? chain.firstVolume : chain.tempRoot); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            deleteOwnedTree(chain.singleTempFile ? chain.firstVolume : chain.tempRoot);
        }
    }

    private static void deleteOwnedTree(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot inspect temporary RAR header directory");
            for (File child : children) deleteOwnedTree(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Cannot delete temporary RAR header copy");
    }

    private static boolean hasRar4HeaderEncryptedVolume(@NonNull File archive) throws IOException {
        for (File volume : RarArchiveLocator.collectVolumes(archive)) {
            if (Rar4HeaderEncryptedArchiveRewriter.isRar4HeaderEncrypted(volume)) return true;
        }
        return false;
    }

    @NonNull
    private static HeaderDecryptedChain buildDecryptedChain(@NonNull File archive,
                                                           @NonNull char[] password,
                                                           @Nullable FileOperationProgress progress) throws IOException {
        List<File> volumes = RarArchiveLocator.collectVolumes(archive);
        if (volumes.isEmpty()) {
            throw new IOException("RAR volume chain is empty");
        }

        // Fast path for single-volume -hp archives keeps the previous behavior and avoids
        // creating a temporary directory just to host one rewritten file.
        if (volumes.size() == 1) {
            File single = Rar4HeaderEncryptedArchiveRewriter.buildDecryptedHeaderCopy(
                    volumes.get(0),
                    password,
                    progress);
            return new HeaderDecryptedChain(single, single.getParentFile(), true);
        }

        File tempDir = createTempDirectory(archive);
        boolean success = false;
        List<File> rewrittenVolumes = new ArrayList<>(volumes.size());
        try {
            for (File volume : volumes) {
                if (progress != null && !progress.checkpoint()) {
                    throw new IOException("RAR extraction cancelled");
                }
                if (!Rar4HeaderEncryptedArchiveRewriter.isRar4HeaderEncrypted(volume)) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "Mixed header-encrypted and plain RAR volumes are not supported");
                }
                File rewritten = new File(tempDir, volume.getName());
                Rar4HeaderEncryptedArchiveRewriter.writeDecryptedHeaderCopy(
                        volume,
                        rewritten,
                        password,
                        progress);
                rewrittenVolumes.add(rewritten);
            }
            success = true;
            return new HeaderDecryptedChain(rewrittenVolumes.get(0), tempDir, false);
        } finally {
            if (!success) deleteRecursively(tempDir);
        }
    }

    @NonNull
    private static File createTempDirectory(@NonNull File archive) throws IOException {
        String prefix = "textview-rar4-hp-chain-";
        File parent = archive.getParentFile();
        File base = parent != null && parent.isDirectory() ? parent : null;
        File marker = File.createTempFile(prefix, ".tmp", base);
        if (!marker.delete()) throw new IOException("Cannot create temporary RAR directory");
        if (!marker.mkdirs()) throw new IOException("Cannot create temporary RAR directory");
        return marker;
    }

    private static void closeQuietly(@Nullable HeaderDecryptedChain chain) {
        if (chain == null) return;
        if (chain.singleTempFile) {
            deleteQuietly(chain.firstVolume);
        } else {
            deleteRecursively(chain.tempRoot);
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file == null || !file.exists()) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (SecurityException ignored) {
        }
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        deleteQuietly(file);
    }

    private static final class HeaderDecryptedChain {
        final File firstVolume;
        @Nullable final File tempRoot;
        final boolean singleTempFile;

        HeaderDecryptedChain(@NonNull File firstVolume,
                             @Nullable File tempRoot,
                             boolean singleTempFile) {
            this.firstVolume = firstVolume;
            this.tempRoot = tempRoot;
            this.singleTempFile = singleTempFile;
        }
    }
}
