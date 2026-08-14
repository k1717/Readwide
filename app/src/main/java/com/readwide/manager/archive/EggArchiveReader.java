package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.tukaani.xz.LZMAInputStream;

import java.io.File;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import com.readwide.manager.util.FileOperationProgress;

/**
 * Read-only decoder for the ESTsoft EGG archive container.
 *
 * <p>The container layout (magic numbers, header field order and sizes, block
 * structure and compression-method codes) is implemented as first-party Java code
 * from public EGG container concepts and empirical verification against real
 * ALZip-created archives. See {@code docs/EGG_FORMAT_NOTES.md}. This reader
 * extracts entries compressed with Store / Deflate / BZip2 / AZO / LZMA. The AZO
 * path is a modified Java port of the zlib-licensed kippler/xunazo decoder and
 * remains extraction-only. Every block's CRC32 is verified.</p>
 *
 * <p>The logical archive layout is: a 14-byte EGG header, archive-level extra
 * fields (Split, Solid, ...) terminated by an END field, then FILE entries -
 * each FILE has its own extra fields terminated by END, followed by its BLOCKs
 * (a BLOCK header is itself terminated by an END field before its data) - and
 * a final archive-level END.</p>
 *
 * <p>Split archives ({@code name.vol1.egg}, {@code name.vol2.egg}, ...) are a
 * plain byte-level cut of one logical archive: the first volume is read whole
 * and each later volume contributes the bytes after its own header prefix. The
 * Split extra field links volumes by program id ({@code prev} of a volume must
 * equal the header id of the volume before it), which this reader validates
 * while assembling the chain. ZipCrypto-encrypted entries decrypt with the
 * classic PKWARE keystream (one decryptor per file, verified against the
 * 12-byte check data before any output, continuing across the file's blocks).
 * AES-128/256 entries decrypt with the WinZip AES construction implemented by
 * {@link EggWinZipAesCrypto}, verifying the 2-byte password check before any
 * output and the 10-byte HMAC-SHA1 footer over the whole ciphertext at file
 * end.</p>
 *
 * <p>Solid archives (Solid extra field in the archive header) store one
 * decoded stream across all blocks: file data follows file-header order back
 * to back, so extraction is a single sequential pass split by each entry's
 * declared size, with per-block CRC verification unchanged. Extracting one
 * entry decodes from the stream start up to that entry (this layout and its
 * CRC semantics were validated against ESTsoft's own unegg decoder; see
 * {@code docs/EGG_FORMAT_NOTES.md}). LEA-encrypted entries and encrypted
 * solid archives are reported as unsupported rather than producing partial or
 * corrupt output.</p>
 */
final class EggArchiveReader {

    // Little-endian uint32 magic numbers used by the EGG container.
    private static final int MAGIC_EGG = 0x41474745;
    private static final int MAGIC_FILE = 0x0a8590e3;
    private static final int MAGIC_BLOCK = 0x02b50c13;
    private static final int MAGIC_ENCRYPT = 0x08d1470f;
    private static final int MAGIC_WINDOWS_FILEINFO = 0x2c86950b;
    private static final int MAGIC_POSIX_FILEINFO = 0x1ee922e5;
    private static final int MAGIC_FILENAME = 0x0a8591ac;
    private static final int MAGIC_COMMENT = 0x04c63672;
    private static final int MAGIC_SPLIT = 0x24f5a262;
    private static final int MAGIC_SOLID = 0x24e5a060;
    private static final int MAGIC_DUMMY = 0x07463307;
    private static final int MAGIC_END = 0x08e28222;

    // CompressionMethod enum (Store=0, Deflate=1, bzip=2, azo=3, lzma=4).
    private static final int COMP_STORE = 0;
    private static final int COMP_DEFLATE = 1;
    private static final int COMP_BZIP = 2;
    private static final int COMP_AZO = 3;
    private static final int COMP_LZMA = 4;

    // EGG LZMA block data preamble: 4 bytes (version/props-size words) followed
    // by the 5-byte LZMA properties (props byte + little-endian dictionary size).
    // The compressed stream starts right after these 9 bytes.
    // EncryptMethod codes carried in the ENCRYPT extra field's first byte.
    private static final int ENC_ZIPCRYPTO = 0;
    private static final int ENC_AES128 = 1;
    private static final int ENC_AES256 = 2;
    private static final int ENC_LEA128 = 5;
    private static final int ENC_LEA256 = 6;

    private static final int LZMA_DATA_HEADER_SIZE = 9;
    private static final int LZMA_PROPS_OFFSET = 4;
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final int MAX_SPLIT_VOLUMES = 999;

    private static final Pattern VOLUME_NAME =
            Pattern.compile("^(.*\\.vol)(\\d+)(\\.egg)$", Pattern.CASE_INSENSITIVE);

    private EggArchiveReader() {
    }

    // ----- Entry model -----

    private static final class EggEntry {
        String path = "";
        byte[] rawNamePayload;  // FILENAME payload, decoded after the scan via the archive-wide corpus
        int rawNameGpb;
        boolean directory;
        long uncompressedSize;
        boolean encrypted;
        int encryptMethod = -1;
        byte[] encryptVerify;   // ZipCrypto: 12-byte encrypted check data
        long encryptCrc;        // ZipCrypto: CRC32 whose top byte verifies the password
        byte[] aesSalt;         // AES: 8 (AES-128) or 16 (AES-256) byte salt
        byte[] aesVerifier;     // AES: 2-byte PBKDF2 password verifier
        byte[] aesFooter;       // AES: 10-byte HMAC-SHA1 of the ciphertext
        boolean solid;
        final List<EggBlock> blocks = new ArrayList<>();
    }

    private static final class EggBlock {
        int method;
        long uncompSize;
        long compSize;
        long crc;       // unsigned 32-bit, stored in a long
        long dataOffset;
    }

    /** Archive-level header prefix of one physical volume. */
    private static final class VolumePrefix {
        long programId;    // header id, used as the split chain link
        long splitPrev;    // Split field: previous volume's header id (0 = first)
        long splitNext;    // Split field: next volume's header id (0 = last)
        boolean hasSplit;
        boolean solid;
        long dataOffset;   // physical offset of the first byte after the prefix END
    }

    // ----- Public API (mirrors AlzipArchiveReader shape) -----

    static boolean isEgg(@NonNull File archive) {
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            if (raf.length() < 14) return false;
            int b0 = raf.read();
            int b1 = raf.read();
            int b2 = raf.read();
            int b3 = raf.read();
            int sig = (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
            return sig == MAGIC_EGG;
        } catch (IOException e) {
            return false;
        }
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archive,
                                                      @Nullable char[] password) throws IOException {
        try (SplitVolumeInput in = openVolumes(archive)) {
            List<EggEntry> entries = readEntries(archive, in);
            ArrayList<ArchiveSupport.EntryInfo> result = new ArrayList<>();
            for (EggEntry entry : entries) {
                result.add(new ArchiveSupport.EntryInfo(entry.path, entry.directory,
                        entry.uncompressedSize, 0L));
            }
            return result;
        }
    }

    static boolean requiresPasswordForExtraction(@NonNull File archive) {
        try (SplitVolumeInput in = openVolumes(archive)) {
            for (EggEntry entry : readEntries(archive, in)) {
                if (entry.encrypted) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password) throws IOException {
        String normalized = sanitizeEntryPath(entryPath);
        if (normalized == null) return false;
        try (SplitVolumeInput in = openVolumes(archive)) {
            List<EggEntry> entries = readEntries(archive, in);
            if (isSolid(entries)) {
                return extractSolidSingle(in, entries, normalized, outFile, password);
            }
            for (EggEntry entry : entries) {
                if (entry.directory) continue;
                if (!normalized.equals(sanitizeEntryPath(entry.path))) continue;
                writeEntry(in, entry, outFile, password, null);
                return true;
            }
        }
        return false;
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password) throws IOException {
        return extractArchiveIntoDirectory(archive, targetDir, password, null);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress) throws IOException {
        return extractArchiveIntoDirectory(archive, targetDir, password, progress, null);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress,
                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        boolean any = false;
        try (SplitVolumeInput in = openVolumes(archive)) {
            List<EggEntry> entries = readEntries(archive, in);
            if (progress != null) progress.setTotalBytes(sumUncompressedBytes(entries));
            if (isSolid(entries)) {
                return extractSolidArchive(in, entries, targetDir, password, progress, entryProgress);
            }
            for (EggEntry entry : entries) {
                if (progress != null && !progress.checkpoint()) return false;
                if (entry.directory) {
                    if (entryProgress != null) entryProgress.onDirectory(entry.path);
                    continue;
                }
                if (entryProgress != null) entryProgress.onFile(entry.path);
                else if (progress != null) progress.setDetail(entry.path);
                File outFile = resolveOutput(targetDir, entry.path);
                if (outFile == null) continue;
                writeEntry(in, entry, outFile, password, progress);
                any = true;
            }
        }
        return any;
    }

    // ----- Solid extraction -----

    private static boolean isSolid(@NonNull List<EggEntry> entries) {
        for (EggEntry entry : entries) {
            if (entry.solid) return true;
        }
        return false;
    }

    /**
     * Collects the archive's blocks in stream order. In a solid archive every
     * block follows the file header list, so the parser attaches them all to
     * the entry that precedes them; walking every entry in order recovers the
     * physical block sequence regardless of which entry they landed on.
     */
    @NonNull
    private static List<EggBlock> collectSolidBlocks(@NonNull List<EggEntry> entries) {
        List<EggBlock> blocks = new ArrayList<>();
        for (EggEntry entry : entries) {
            blocks.addAll(entry.blocks);
        }
        return blocks;
    }

    private static void requireSolidSupported(@NonNull List<EggEntry> entries) throws IOException {
        for (EggEntry entry : entries) {
            if (entry.encrypted) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "Encrypted solid EGG archives are not supported");
            }
            if (!entry.directory && entry.uncompressedSize > MAX_ENTRY_BYTES) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        "EGG entry is too large for this decoder");
            }
        }
    }

    /**
     * Extracts a solid archive: the decoded concatenation of all blocks is one
     * continuous stream carrying every file's data in file-header order, so a
     * single sequential pass splits it by each entry's uncompressed size.
     * Block CRCs are verified per block over the decoded bytes, exactly as in
     * the non-solid path (layout and CRC semantics validated against the
     * vendor's unegg 0.5 decoder; see docs/EGG_FORMAT_NOTES.md).
     */
    private static boolean extractSolidArchive(@NonNull SplitVolumeInput in,
                                               @NonNull List<EggEntry> entries,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress,
                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        requireSolidSupported(entries);
        List<SolidTarget> targets = new ArrayList<>();
        for (EggEntry entry : entries) {
            if (entry.directory) {
                if (entryProgress != null) entryProgress.onDirectory(entry.path);
                File dir = resolveOutput(targetDir, entry.path);
                if (dir != null && !dir.exists() && !dir.mkdirs()) {
                    throw new IOException("Cannot create output directory");
                }
                continue;
            }
            File outFile = resolveOutput(targetDir, entry.path);
            // A rejected path still consumes its bytes to keep later entries
            // aligned in the solid stream; it just writes nowhere.
            targets.add(new SolidTarget(entry.path, entry.uncompressedSize, outFile));
        }
        if (targets.isEmpty()) return false;
        SolidEntryWriter writer = new SolidEntryWriter(targets, progress, entryProgress);
        boolean ok = false;
        try {
            CRC32 runningCrc = new CRC32();
            for (EggBlock block : collectSolidBlocks(entries)) {
                if (progress != null && !progress.checkpoint()) throw new IOException("EGG extraction cancelled");
                writeBlock(in, block, writer, runningCrc, progress, null);
            }
            writer.finish();
            ok = true;
        } finally {
            writer.closeQuietly(ok);
        }
        return writer.wroteAnything();
    }

    /**
     * Extracts one entry from a solid archive by decoding the solid stream
     * from its beginning, discarding bytes before the entry and stopping once
     * the entry's range is written. Blocks fully consumed on the way are CRC
     * verified; the block the extraction stops inside is not.
     */
    private static boolean extractSolidSingle(@NonNull SplitVolumeInput in,
                                              @NonNull List<EggEntry> entries,
                                              @NonNull String normalizedPath,
                                              @NonNull File outFile,
                                              @Nullable char[] password) throws IOException {
        requireSolidSupported(entries);
        long offset = 0L;
        long length = -1L;
        for (EggEntry entry : entries) {
            if (entry.directory) continue;
            if (normalizedPath.equals(sanitizeEntryPath(entry.path))) {
                length = entry.uncompressedSize;
                break;
            }
            offset += Math.max(0L, entry.uncompressedSize);
        }
        if (length < 0L) return false;

        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create output directory");

        boolean ok = false;
        try (OutputStream fileOut = ArchiveSupport.openExtractionOutputStream(outFile)) {
            SolidRangeWriter writer = new SolidRangeWriter(fileOut, offset, length);
            CRC32 runningCrc = new CRC32();
            try {
                for (EggBlock block : collectSolidBlocks(entries)) {
                    writeBlock(in, block, writer, runningCrc, null, null);
                    if (writer.done()) break;
                }
            } catch (SolidRangeDone ignored) {
                // Target range fully written; remaining blocks skipped.
            }
            if (!writer.done()) throw new IOException("Solid EGG stream ended before entry data");
            fileOut.flush();
            ok = true;
        } finally {
            if (!ok) {
                try { //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                } catch (SecurityException ignored) {
                }
            }
        }
        return true;
    }

    /** One non-directory entry's slot in the solid stream. */
    private static final class SolidTarget {
        @NonNull final String path;
        final long size;
        @Nullable final File outFile;   // null: consume bytes, write nowhere

        SolidTarget(@NonNull String path, long size, @Nullable File outFile) {
            this.path = path;
            this.size = Math.max(0L, size);
            this.outFile = outFile;
        }
    }

    /** Splits the decoded solid stream into consecutive entry files. */
    private static final class SolidEntryWriter extends OutputStream {
        @NonNull private final List<SolidTarget> targets;
        @Nullable private final FileOperationProgress progress;
        @Nullable private final ArchiveExtractionProgressTracker entryProgress;
        private int index = -1;
        private long remaining;
        @Nullable private OutputStream current;
        @Nullable private File currentFile;
        private boolean wroteAny;

        SolidEntryWriter(@NonNull List<SolidTarget> targets,
                         @Nullable FileOperationProgress progress,
                         @Nullable ArchiveExtractionProgressTracker entryProgress) {
            this.targets = targets;
            this.progress = progress;
            this.entryProgress = entryProgress;
        }

        @Override
        public void write(int b) throws IOException {
            byte[] one = {(byte) b};
            write(one, 0, 1);
        }

        @Override
        public void write(@NonNull byte[] buffer, int offset, int length) throws IOException {
            while (length > 0) {
                while (remaining == 0L) {
                    if (!advance()) throw new IOException("Solid EGG stream longer than declared entries");
                }
                int n = (int) Math.min(length, remaining);
                if (current != null) current.write(buffer, offset, n);
                remaining -= n;
                offset += n;
                length -= n;
            }
        }

        /** Moves to the next target, creating its file. Zero-size targets are
         *  created here and closed by the following {@code advance()} call,
         *  which the write loop and {@link #finish()} issue while
         *  {@code remaining == 0}. */
        private boolean advance() throws IOException {
            closeCurrent();
            index++;
            if (index >= targets.size()) return false;
            SolidTarget target = targets.get(index);
            if (entryProgress != null) entryProgress.onFile(target.path);
            else if (progress != null) progress.setDetail(target.path);
            if (target.outFile != null) {
                File parent = target.outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot create output directory");
                }
                current = ArchiveSupport.openExtractionOutputStream(target.outFile);
                currentFile = target.outFile;
                wroteAny = true;
            } else {
                current = null;
                currentFile = null;
            }
            remaining = target.size;
            return true;
        }

        /** Creates any trailing zero-size entries and validates the fill. */
        void finish() throws IOException {
            if (remaining != 0L) {
                throw new IOException("Solid EGG stream ended before entry data");
            }
            while (advance()) {
                if (remaining != 0L) {
                    throw new IOException("Solid EGG stream ended before entry data");
                }
            }
            closeCurrent();
        }

        boolean wroteAnything() {
            return wroteAny;
        }

        private void closeCurrent() throws IOException {
            if (current != null) {
                current.close();
                current = null;
                currentFile = null;
            }
        }

        void closeQuietly(boolean ok) {
            try {
                if (current != null) current.close();
            } catch (IOException ignored) {
            }
            if (!ok && currentFile != null) {
                try { //noinspection ResultOfMethodCallIgnored
                    currentFile.delete();
                } catch (SecurityException ignored) {
                }
            }
            current = null;
        }
    }

    /** Signals that a solid single-entry range has been fully written. */
    private static final class SolidRangeDone extends IOException {
        SolidRangeDone() {
            super("solid range complete");
        }
    }

    /** Discards bytes before a range, writes the range, then signals done. */
    private static final class SolidRangeWriter extends OutputStream {
        @NonNull private final OutputStream out;
        private long toSkip;
        private long toWrite;

        SolidRangeWriter(@NonNull OutputStream out, long offset, long length) {
            this.out = out;
            this.toSkip = Math.max(0L, offset);
            this.toWrite = Math.max(0L, length);
        }

        boolean done() {
            return toWrite == 0L;
        }

        @Override
        public void write(int b) throws IOException {
            byte[] one = {(byte) b};
            write(one, 0, 1);
        }

        @Override
        public void write(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (toSkip > 0L) {
                long skipped = Math.min(toSkip, length);
                toSkip -= skipped;
                offset += (int) skipped;
                length -= (int) skipped;
            }
            if (length <= 0) return;
            int n = (int) Math.min(length, toWrite);
            if (n > 0) {
                out.write(buffer, offset, n);
                toWrite -= n;
            }
            if (toWrite == 0L) throw new SolidRangeDone();
        }
    }

    // ----- Split volume resolution -----

    /**
     * Opens the archive as a logical volume set. A non-split archive yields a
     * single whole-file segment. A split archive (Split field with a non-zero
     * next id) yields the first volume whole plus every later volume minus its
     * own header prefix, with the prev/id chain validated volume by volume.
     */
    @NonNull
    private static SplitVolumeInput openVolumes(@NonNull File archive) throws IOException {
        VolumePrefix first = scanPrefix(archive);
        List<SplitVolumeInput.Segment> segments = new ArrayList<>();
        segments.add(new SplitVolumeInput.Segment(archive, 0L, archive.length()));
        if (!first.hasSplit || first.splitNext == 0L) {
            if (first.hasSplit && first.splitPrev != 0L) {
                throw new IOException("EGG split volume opened without its first volume: " + archive.getName());
            }
            return new SplitVolumeInput(segments);
        }
        if (first.splitPrev != 0L) {
            throw new IOException("EGG split volume opened without its first volume: " + archive.getName());
        }
        File current = archive;
        long expectedPrev = first.programId;
        long nextId = first.splitNext;
        int guard = 0;
        while (nextId != 0L) {
            if (++guard > MAX_SPLIT_VOLUMES) throw new IOException("EGG split volume chain too long");
            File next = nextVolumeFile(current);
            if (next == null || !next.isFile()) {
                throw new IOException("Missing EGG split volume after " + current.getName());
            }
            VolumePrefix prefix = scanPrefix(next);
            if (!prefix.hasSplit || prefix.splitPrev != expectedPrev) {
                throw new IOException("EGG split volume chain mismatch at " + next.getName());
            }
            long payload = next.length() - prefix.dataOffset;
            if (payload < 0) throw new IOException("EGG split volume shorter than its header: " + next.getName());
            segments.add(new SplitVolumeInput.Segment(next, prefix.dataOffset, payload));
            current = next;
            expectedPrev = prefix.programId;
            nextId = prefix.splitNext;
        }
        return new SplitVolumeInput(segments);
    }

    /** Derives the next volume's file (vol N -> vol N+1), preserving zero padding. */
    @Nullable
    private static File nextVolumeFile(@NonNull File current) {
        File parent = current.getParentFile();
        if (parent == null) return null;
        Matcher m = VOLUME_NAME.matcher(current.getName());
        if (!m.matches()) return null;
        String digits = m.group(2);
        long number;
        try {
            number = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        String next = Long.toString(number + 1);
        if (digits.length() > next.length() && digits.charAt(0) == '0') {
            StringBuilder padded = new StringBuilder();
            for (int i = next.length(); i < digits.length(); i++) padded.append('0');
            next = padded.append(next).toString();
        }
        return new File(parent, m.group(1) + next + m.group(3));
    }

    /** Reads one physical volume's 14-byte header and archive-level extra fields. */
    @NonNull
    private static VolumePrefix scanPrefix(@NonNull File volume) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(volume, "r")) {
            VolumePrefix prefix = new VolumePrefix();
            if (raf.length() < 14 || readUInt32LE(raf) != MAGIC_EGG) {
                throw invalidSignature(volume);
            }
            readUInt16LE(raf);                                // version
            prefix.programId = readUInt32LE(raf) & 0xffffffffL; // header id
            readUInt32LE(raf);                                // reserved
            long len = raf.length();
            while (raf.getFilePointer() + 4 <= len) {
                long fieldPos = raf.getFilePointer();
                int sig = readUInt32LE(raf);
                if (sig == MAGIC_END) {
                    prefix.dataOffset = raf.getFilePointer();
                    return prefix;
                }
                if (sig == MAGIC_FILE) {
                    // Tolerated legacy layout without a prefix END: data begins here.
                    prefix.dataOffset = fieldPos;
                    return prefix;
                }
                int gpb = raf.readUnsignedByte();
                long size;
                if ((gpb & 1) == 1) {
                    size = readUInt32LE(raf) & 0xffffffffL;
                } else {
                    size = readUInt16LE(raf);
                }
                long payloadStart = raf.getFilePointer();
                if (payloadStart + size > len) throw unsupported(volume);
                if (sig == MAGIC_SPLIT && size >= 8) {
                    prefix.hasSplit = true;
                    prefix.splitPrev = readUInt32LE(raf) & 0xffffffffL;
                    prefix.splitNext = readUInt32LE(raf) & 0xffffffffL;
                } else if (sig == MAGIC_SOLID) {
                    prefix.solid = true;
                }
                raf.seek(payloadStart + size);
            }
            throw unsupported(volume);
        }
    }

    // ----- Parsing -----

    @NonNull
    private static List<EggEntry> readEntries(@NonNull File archive,
                                              @NonNull SplitVolumeInput in) throws IOException {
        in.seek(0);
        long len = in.length();
        if (len < 14 || readUInt32LE(in) != MAGIC_EGG) {
            throw invalidSignature(archive);
        }
        readUInt16LE(in);  // version
        readUInt32LE(in);  // header id
        readUInt32LE(in);  // reserved

        // Archive-level extra fields (Split/Solid/...) up to the prefix END. A
        // FILE signature here is tolerated for archives without a prefix END.
        boolean archiveSolid = false;
        while (in.getFilePointer() + 4 <= len) {
            long fieldPos = in.getFilePointer();
            int sig = readUInt32LE(in);
            if (sig == MAGIC_END) break;
            if (sig == MAGIC_FILE) {
                in.seek(fieldPos);
                break;
            }
            int gpb = in.readUnsignedByte();
            long size;
            if ((gpb & 1) == 1) {
                size = readUInt32LE(in) & 0xffffffffL;
            } else {
                size = readUInt16LE(in);
            }
            long payloadStart = in.getFilePointer();
            if (payloadStart + size > len) throw unsupported(archive);
            if (sig == MAGIC_SOLID) archiveSolid = true;
            in.seek(payloadStart + size);
        }

        List<EggEntry> entries = new ArrayList<>();
        while (in.getFilePointer() + 4 <= len) {
            int sig = readUInt32LE(in);
            if (sig == MAGIC_END) {
                // Archive-level END terminates the entry list.
                break;
            }
            if (sig != MAGIC_FILE) {
                // Unknown top-level structure; stop rather than guess.
                break;
            }
            EggEntry entry = readFileEntry(in, len);
            if (entry == null) break;
            entry.solid |= archiveSolid;
            entries.add(entry);
        }
        if (entries.isEmpty()) throw unsupported(archive);
        decodeEntryNames(entries);
        return entries;
    }

    /**
     * Decodes every entry name with one archive-wide charset decision (see
     * {@link ArchiveFilenameDecoder.NameCorpus}): the long names in an
     * archive supply the encoding signal that one-syllable names lack, so a
     * short legacy name can no longer flip to a different code page than its
     * siblings. Locale code-page hints and UTF-8 names keep their existing
     * per-name precedence.
     */
    private static void decodeEntryNames(@NonNull List<EggEntry> entries) {
        ArchiveFilenameDecoder.NameCorpus corpus = new ArchiveFilenameDecoder.NameCorpus();
        for (EggEntry entry : entries) {
            byte[] payload = entry.rawNamePayload;
            if (payload == null) continue;
            int offset = filenameOffset(payload, entry.rawNameGpb);
            if (payload.length > offset) corpus.observe(payload, offset, payload.length - offset);
        }
        for (EggEntry entry : entries) {
            byte[] payload = entry.rawNamePayload;
            if (payload == null) continue;
            entry.path = decodeFilename(payload, entry.rawNameGpb, corpus);
            entry.rawNamePayload = null;
        }
    }

    private static int filenameOffset(@NonNull byte[] payload, int gpb) {
        return (gpb & (1 << 5)) != 0 && payload.length >= 2 ? 2 : 0;
    }

    @Nullable
    private static EggEntry readFileEntry(@NonNull SplitVolumeInput in, long len) throws IOException {
        EggEntry entry = new EggEntry();
        readUInt32LE(in);                 // file id
        entry.uncompressedSize = readUInt64LE(in);

        // Extra fields until this FILE's END (a BLOCK signature is tolerated
        // for archives that omit the per-file END).
        while (in.getFilePointer() + 4 <= len) {
            int fieldSig = readUInt32LE(in);
            if (fieldSig == MAGIC_END) break;
            if (fieldSig == MAGIC_BLOCK) {
                in.seek(in.getFilePointer() - 4);
                break;
            }
            if (!readExtraField(in, entry, fieldSig, len)) {
                return entry; // malformed; keep what we have
            }
        }

        // Blocks. A BLOCK header is method(1) + hint(1) + uncomp(4) + comp(4)
        // + crc(4), terminated by an END field, then the compressed data.
        while (in.getFilePointer() + 4 <= len) {
            long pos = in.getFilePointer();
            int sig = readUInt32LE(in);
            if (sig != MAGIC_BLOCK) {
                // Not a block: the archive-level END or the next FILE. Rewind so
                // the outer loop sees it.
                in.seek(pos);
                break;
            }
            EggBlock block = new EggBlock();
            block.method = in.readUnsignedByte();
            in.readUnsignedByte();         // methodHint
            block.uncompSize = readUInt32LE(in) & 0xffffffffL;
            block.compSize = readUInt32LE(in) & 0xffffffffL;
            block.crc = readUInt32LE(in) & 0xffffffffL;
            long afterHeader = in.getFilePointer();
            if (afterHeader + 4 <= len && readUInt32LE(in) == MAGIC_END) {
                block.dataOffset = in.getFilePointer();
            } else {
                // Tolerated legacy layout without the block END: data begins
                // right after the CRC.
                in.seek(afterHeader);
                block.dataOffset = afterHeader;
            }
            entry.blocks.add(block);
            long skipTo = block.dataOffset + block.compSize;
            if (skipTo < block.dataOffset || skipTo > len) break;
            in.seek(skipTo);
        }

        entry.directory = entry.path.endsWith("/") || (entry.blocks.isEmpty() && entry.uncompressedSize == 0
                && entry.path.endsWith("/"));
        return entry;
    }

    private static boolean readExtraField(@NonNull SplitVolumeInput in,
                                          @NonNull EggEntry entry,
                                          int fieldSig,
                                          long len) throws IOException {
        int gpb = in.readUnsignedByte();
        long size;
        if ((gpb & 1) == 1) {
            size = readUInt32LE(in) & 0xffffffffL;
        } else {
            size = readUInt16LE(in);
        }
        long payloadStart = in.getFilePointer();
        if (size < 0 || payloadStart + size > len) return false;

        if (fieldSig == MAGIC_FILENAME) {
            byte[] payload = new byte[(int) Math.min(size, 65535)];
            in.readFully(payload);
            entry.rawNamePayload = payload;
            entry.rawNameGpb = gpb;
        } else if (fieldSig == MAGIC_ENCRYPT) {
            entry.encrypted = true;
            // Payload: method u8, then for ZipCrypto the 12-byte encrypted
            // check data followed by the CRC32 it verifies against; for
            // AES-128/256 the salt (8/16), the 2-byte password verifier, and
            // the 10-byte HMAC-SHA1 footer over the file's ciphertext.
            if (size >= 1) {
                byte[] payload = new byte[(int) Math.min(size, 64)];
                in.readFully(payload);
                entry.encryptMethod = payload[0] & 0xff;
                if (entry.encryptMethod == ENC_ZIPCRYPTO && payload.length >= 17) {
                    entry.encryptVerify = new byte[12];
                    System.arraycopy(payload, 1, entry.encryptVerify, 0, 12);
                    entry.encryptCrc = (payload[13] & 0xffL)
                            | ((payload[14] & 0xffL) << 8)
                            | ((payload[15] & 0xffL) << 16)
                            | ((payload[16] & 0xffL) << 24);
                } else if (entry.encryptMethod == ENC_AES128 || entry.encryptMethod == ENC_AES256) {
                    int saltLen = entry.encryptMethod == ENC_AES128 ? 8 : 16;
                    if (payload.length >= 1 + saltLen + 2 + 10) {
                        entry.aesSalt = new byte[saltLen];
                        System.arraycopy(payload, 1, entry.aesSalt, 0, saltLen);
                        entry.aesVerifier = new byte[2];
                        System.arraycopy(payload, 1 + saltLen, entry.aesVerifier, 0, 2);
                        entry.aesFooter = new byte[10];
                        System.arraycopy(payload, 3 + saltLen, entry.aesFooter, 0, 10);
                    }
                }
            }
        } else if (fieldSig == MAGIC_SOLID) {
            entry.solid = true;
        }
        // WINDOWS_FILEINFO / POSIX_FILEINFO / COMMENT / DUMMY / SPLIT: skipped.

        in.seek(payloadStart + size);
        return true;
    }

    @NonNull
    private static String decodeFilename(@NonNull byte[] payload, int gpb,
                                         @Nullable ArchiveFilenameDecoder.NameCorpus corpus) {
        // FILENAME payload: optional locale (uint16) when the locale bit is set,
        // followed by the name bytes. The reference treats names as UTF-8 unless
        // a locale code page applies; locale hints take precedence, then the
        // archive-wide corpus decision, then per-name scoring.
        int offset = 0;
        int localeCodePage = 0;
        boolean hasLocale = (gpb & (1 << 5)) != 0;
        if (hasLocale && payload.length >= 2) {
            localeCodePage = (payload[0] & 0xff) | ((payload[1] & 0xff) << 8);
            offset = 2;
        }
        int nameLen = payload.length - offset;
        if (nameLen <= 0) return "";
        return ArchiveFilenameDecoder.decodeEggName(payload, offset, nameLen, localeCodePage, corpus)
                .replace('\\', '/');
    }

    // ----- Extraction -----

    private static void writeEntry(@NonNull SplitVolumeInput in,
                                   @NonNull EggEntry entry,
                                   @NonNull File outFile,
                                   @Nullable char[] password,
                                   @Nullable FileOperationProgress progress) throws IOException {
        if (progress != null && !progress.checkpoint()) throw new IOException("EGG extraction cancelled");
        BlockDecryptor decryptor = null;
        EggWinZipAesCrypto aes = null;
        if (entry.encrypted) {
            if (password == null || password.length == 0) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
            if (entry.encryptMethod == ENC_ZIPCRYPTO) {
                if (entry.encryptVerify == null) {
                    throw new IOException("Missing EGG encryption check data");
                }
                // One decryptor per file: after the 12-byte check data the same
                // keystream continues into the file's block data, in block order.
                final AlzipZipCrypto crypto = new AlzipZipCrypto(password);
                if (!crypto.checkHeader(entry.encryptVerify, (int) (entry.encryptCrc >>> 24))) {
                    throw new IOException("Invalid password for EGG entry");
                }
                decryptor = new BlockDecryptor() {
                    @Override
                    public void decryptInPlace(@NonNull byte[] buffer, int offset, int length) {
                        crypto.decryptInPlace(buffer, offset, length);
                    }
                };
            } else if (entry.encryptMethod == ENC_AES128 || entry.encryptMethod == ENC_AES256) {
                aes = openAesDecryptor(entry, password);
                final EggWinZipAesCrypto aesRef = aes;
                decryptor = new BlockDecryptor() {
                    @Override
                    public void decryptInPlace(@NonNull byte[] buffer, int offset, int length) throws IOException {
                        aesRef.decryptInPlace(buffer, offset, length);
                    }
                };
            } else {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                        encryptionMethodName(entry.encryptMethod) + "-encrypted EGG entries are not supported");
            }
        }
        if (entry.solid) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "Solid EGG archives are not supported");
        }
        if (entry.uncompressedSize > MAX_ENTRY_BYTES) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "EGG entry is too large for this decoder");
        }

        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create output directory");

        boolean ok = false;
        try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
            CRC32 runningCrc = new CRC32();
            for (EggBlock block : entry.blocks) {
                if (progress != null && !progress.checkpoint()) throw new IOException("EGG extraction cancelled");
                writeBlock(in, block, out, runningCrc, progress, decryptor);
            }
            if (aes != null && entry.aesFooter != null && !aes.verifyFooter(entry.aesFooter)) {
                throw new IOException("EGG AES data authentication failed");
            }
            out.flush();
            ok = true;
        } finally {
            if (!ok) {
                try { //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                } catch (SecurityException ignored) {
                }
            }
        }
    }

    /**
     * Builds the per-file AES context. The password bytes are tried as UTF-8
     * first; if the 2-byte PBKDF2 verifier rejects them and the password has
     * non-ASCII characters, the legacy Windows-949 bytes ALZip would have
     * hashed are tried before failing.
     */
    @NonNull
    private static EggWinZipAesCrypto openAesDecryptor(@NonNull EggEntry entry,
                                                       @NonNull char[] password) throws IOException {
        if (entry.aesSalt == null || entry.aesVerifier == null || entry.aesFooter == null) {
            throw new IOException("Missing EGG AES encryption header");
        }
        int keyBits = entry.encryptMethod == ENC_AES128 ? 128 : 256;
        EggWinZipAesCrypto aes = new EggWinZipAesCrypto(
                EggWinZipAesCrypto.passwordBytesUtf8(password), entry.aesSalt, entry.aesVerifier, keyBits);
        if (aes.isPasswordVerified()) return aes;
        boolean ascii = true;
        for (char c : password) {
            if (c > 0x7f) {
                ascii = false;
                break;
            }
        }
        if (!ascii) {
            try {
                aes = new EggWinZipAesCrypto(
                        EggWinZipAesCrypto.passwordBytes(password, Charset.forName("MS949")),
                        entry.aesSalt, entry.aesVerifier, keyBits);
                if (aes.isPasswordVerified()) return aes;
            } catch (UnsupportedCharsetException ignored) {
                // MS949 unavailable on this runtime: UTF-8 result stands.
            }
        }
        throw new IOException("Invalid password for EGG entry");
    }

    /** Sequential in-place decryption over a file's stored block bytes. */
    private interface BlockDecryptor {
        void decryptInPlace(@NonNull byte[] buffer, int offset, int length) throws IOException;
    }

    private static void writeBlock(@NonNull SplitVolumeInput in,
                                   @NonNull EggBlock block,
                                   @NonNull OutputStream out,
                                   @NonNull CRC32 runningCrc,
                                   @Nullable FileOperationProgress progress,
                                   @Nullable BlockDecryptor crypto) throws IOException {
        if (block.compSize < 0 || block.compSize > MAX_ENTRY_BYTES || block.uncompSize > MAX_ENTRY_BYTES) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("EGG block size out of range");
        }
        // A single sequential stream over the block's stored bytes. When the
        // entry is ZipCrypto-encrypted the whole stored payload (including the
        // LZMA preamble or AZO framing) is ciphertext, so decryption wraps the
        // raw bytes here, sharing the per-file keystream across blocks.
        try (InputStream input = openBlockInputStream(in, block, crypto)) {
            switch (block.method) {
                case COMP_STORE:
                    copyDecodedBlock(input, out, runningCrc, block, progress);
                    return;
                case COMP_DEFLATE: {
                    Inflater inflater = new Inflater(true);
                    try {
                        copyDecodedBlock(new InflaterInputStream(input, inflater), out, runningCrc, block, progress);
                    } finally {
                        inflater.end();
                    }
                    return;
                }
                case COMP_BZIP:
                    copyDecodedBlock(new BZip2CompressorInputStream(input), out, runningCrc, block, progress);
                    return;
                case COMP_LZMA:
                    writeLzmaBlock(input, block, out, runningCrc, progress);
                    return;
                case COMP_AZO:
                    writeAzoBlock(input, block, out, runningCrc, progress);
                    return;
                default:
                    throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                            "Unsupported EGG compression method " + block.method);
            }
        }
    }

    @NonNull
    private static InputStream openBlockInputStream(@NonNull SplitVolumeInput in,
                                                    @NonNull EggBlock block,
                                                    @Nullable BlockDecryptor crypto) throws IOException {
        InputStream raw = in.boundedStream(block.dataOffset, block.compSize);
        return crypto == null ? raw : new EggDecryptingInputStream(raw, crypto);
    }

    @NonNull
    private static String encryptionMethodName(int method) {
        switch (method) {
            case ENC_AES128: return "AES-128";
            case ENC_AES256: return "AES-256";
            case ENC_LEA128: return "LEA-128";
            case ENC_LEA256: return "LEA-256";
            default: return "Method-" + method;
        }
    }

    private static void copyDecodedBlock(@NonNull InputStream input,
                                         @NonNull OutputStream out,
                                         @NonNull CRC32 runningCrc,
                                         @NonNull EggBlock block,
                                         @Nullable FileOperationProgress progress) throws IOException {
        CRC32 blockCrc = new CRC32();
        byte[] chunk = new byte[64 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(chunk)) != -1) {
            if (progress != null && !progress.checkpoint()) throw new IOException("EGG extraction cancelled");
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException("EGG entry exceeds size limit");
            }
            blockCrc.update(chunk, 0, read);
            runningCrc.update(chunk, 0, read);
            out.write(chunk, 0, read);
            if (progress != null) progress.addDoneBytes(read);
        }
        if (block.crc != 0 && (blockCrc.getValue() & 0xffffffffL) != block.crc) {
            throw new IOException("EGG block CRC mismatch");
        }
    }

    private static void writeAzoBlock(@NonNull InputStream input,
                                      @NonNull EggBlock block,
                                      @NonNull OutputStream out,
                                      @NonNull CRC32 runningCrc,
                                      @Nullable FileOperationProgress progress) throws IOException {
        byte[] compressed = readCompressedBlock(input, block);
        byte[] plain = decodeAzoBlock(compressed, block.uncompSize);
        CRC32 blockCrc = new CRC32();
        blockCrc.update(plain);
        if (block.crc != 0 && (blockCrc.getValue() & 0xffffffffL) != block.crc) {
            throw new IOException("EGG block CRC mismatch");
        }
        runningCrc.update(plain);
        out.write(plain);
        if (progress != null) progress.addDoneBytes(plain.length);
    }

    private static void writeLzmaBlock(@NonNull InputStream input,
                                       @NonNull EggBlock block,
                                       @NonNull OutputStream out,
                                       @NonNull CRC32 runningCrc,
                                       @Nullable FileOperationProgress progress) throws IOException {
        if (block.compSize < LZMA_DATA_HEADER_SIZE) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("Truncated EGG LZMA header");
        }
        // The 9-byte preamble carries a 4-byte version/props-size prefix, then
        // the 5-byte LZMA properties: props byte + little-endian dictionary
        // size. (Verified against real ALZip LZMA archives; reading the props
        // from offset 0 decodes garbage.) Consumed from the same sequential
        // stream as the compressed body so that decryption stays aligned.
        byte[] header = new byte[LZMA_DATA_HEADER_SIZE];
        int done = 0;
        while (done < header.length) {
            int n = input.read(header, done, header.length - done);
            if (n < 0) throw new ArchiveSupport.UnsupportedArchiveFeatureException("Truncated EGG LZMA header");
            done += n;
        }
        byte propsByte = header[LZMA_PROPS_OFFSET];
        int dictSize = (header[LZMA_PROPS_OFFSET + 1] & 0xff)
                | ((header[LZMA_PROPS_OFFSET + 2] & 0xff) << 8)
                | ((header[LZMA_PROPS_OFFSET + 3] & 0xff) << 16)
                | ((header[LZMA_PROPS_OFFSET + 4] & 0xff) << 24);
        try (InputStream lzma = new LZMAInputStream(input, block.uncompSize, propsByte, dictSize)) {
            copyDecodedBlock(lzma, out, runningCrc, block, progress);
        } catch (IOException e) {
            if (e instanceof ArchiveSupport.UnsupportedArchiveFeatureException) throw (ArchiveSupport.UnsupportedArchiveFeatureException) e;
            if (e instanceof SolidRangeDone) throw e;
            String message = e.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("crc")) throw e;
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "EGG LZMA block could not be decoded: " + e.getMessage());
        }
    }

    @NonNull
    private static byte[] readCompressedBlock(@NonNull InputStream input,
                                              @NonNull EggBlock block) throws IOException {
        if (block.compSize < 0 || block.compSize > MAX_ENTRY_BYTES) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("EGG block size out of range");
        }
        byte[] compressed = new byte[(int) block.compSize];
        int done = 0;
        while (done < compressed.length) {
            int n = input.read(compressed, done, compressed.length - done);
            if (n < 0) throw new IOException("Unexpected EOF in EGG block");
            done += n;
        }
        return compressed;
    }

    @NonNull
    private static byte[] decodeAzoBlock(@NonNull byte[] data, long uncompSize) throws IOException {
        try {
            return AzoDecoder.decode(data, uncompSize);
        } catch (IOException e) {
            if (e instanceof ArchiveSupport.UnsupportedArchiveFeatureException) throw (ArchiveSupport.UnsupportedArchiveFeatureException) e;
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "EGG AZO block could not be decoded: " + e.getMessage());
        }
    }

    /** Sequential decrypting view over a block's stored bytes. */
    private static final class EggDecryptingInputStream extends InputStream {
        @NonNull private final InputStream source;
        @NonNull private final BlockDecryptor crypto;

        EggDecryptingInputStream(@NonNull InputStream source, @NonNull BlockDecryptor crypto) {
            this.source = source;
            this.crypto = crypto;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int n = source.read(buffer, offset, length);
            if (n > 0) crypto.decryptInPlace(buffer, offset, n);
            return n;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    // ----- Helpers -----

    private static long sumUncompressedBytes(@NonNull List<EggEntry> entries) {
        long total = 0L;
        boolean unknown = false;
        for (EggEntry entry : entries) {
            if (entry == null || entry.directory) continue;
            if (entry.uncompressedSize < 0L) {
                unknown = true;
                continue;
            }
            if (Long.MAX_VALUE - total < entry.uncompressedSize) return Long.MAX_VALUE;
            total += entry.uncompressedSize;
        }
        return total > 0L ? total : (unknown ? -1L : 0L);
    }

    @Nullable
    private static File resolveOutput(@NonNull File targetDir, @NonNull String entryPath) throws IOException {
        String safe = sanitizeEntryPath(entryPath);
        if (safe == null) return null;
        File out = new File(targetDir, safe);
        String base = targetDir.getCanonicalPath() + File.separator;
        String target = out.getCanonicalPath();
        if (!target.equals(targetDir.getCanonicalPath()) && !target.startsWith(base)) {
            return null; // path traversal guard
        }
        return out;
    }

    @Nullable
    private static String sanitizeEntryPath(String rawEntryName) {
        if (rawEntryName == null) return null;
        String entryName = rawEntryName.trim().replace('\\', '/');
        while (entryName.startsWith("./")) entryName = entryName.substring(2);
        while (entryName.contains("//")) entryName = entryName.replace("//", "/");
        if (entryName.isEmpty()) return null;
        if (entryName.startsWith("/")
                || entryName.equals("..")
                || entryName.startsWith("../")
                || entryName.contains("/../")
                || entryName.endsWith("/..")
                || entryName.matches("^[A-Za-z]:.*")) {
            return null;
        }
        return entryName;
    }

    private static int readUInt16LE(@NonNull SplitVolumeInput in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if ((b0 | b1) < 0) throw new IOException("Unexpected EOF");
        return (b0 & 0xff) | ((b1 & 0xff) << 8);
    }

    private static int readUInt32LE(@NonNull SplitVolumeInput in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new IOException("Unexpected EOF");
        return (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
    }

    private static long readUInt64LE(@NonNull SplitVolumeInput in) throws IOException {
        long lo = readUInt32LE(in) & 0xffffffffL;
        long hi = readUInt32LE(in) & 0xffffffffL;
        return lo | (hi << 32);
    }

    private static int readUInt16LE(@NonNull RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        if ((b0 | b1) < 0) throw new IOException("Unexpected EOF");
        return (b0 & 0xff) | ((b1 & 0xff) << 8);
    }

    private static int readUInt32LE(@NonNull RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new IOException("Unexpected EOF");
        return (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
    }

    @NonNull
    private static IOException unsupported(@NonNull File archive) {
        return new ArchiveSupport.UnsupportedArchiveFeatureException(
                "Unsupported or malformed EGG archive: " + archive.getName());
    }

    @NonNull
    private static IOException invalidSignature(@NonNull File archive) {
        return new IOException("Invalid EGG signature: " + archive.getName());
    }
}
