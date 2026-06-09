package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import com.textview.reader.util.FileOperationProgress;

import javax.crypto.Cipher;

final class RarArchiveReader {
    private static final int BUFFER_SIZE = 1024 * 64;

    private static final int HEADER_MAIN = 1;
    private static final int HEADER_FILE = 2;
    private static final int HEADER_SERVICE = 3;
    private static final int HEADER_ENCRYPTION = 4;
    private static final int HEADER_END = 5;

    private static final int RAR4_HEADER_MAIN = 0x73;
    private static final int RAR4_HEADER_FILE = 0x74;
    private static final int RAR4_HEADER_END = 0x7b;
    private static final int RAR4_MAIN_PASSWORD = 0x0080;
    private static final int RAR4_LONG_BLOCK = 0x8000;
    private static final int RAR4_FILE_SPLIT_BEFORE = 0x0001;
    private static final int RAR4_FILE_SPLIT_AFTER = 0x0002;
    private static final int RAR4_FILE_PASSWORD = 0x0004;
    private static final int RAR4_FILE_SOLID = 0x0010;
    private static final int RAR4_FILE_DIRECTORY_MASK = 0x00e0;
    private static final int RAR4_FILE_DIRECTORY = 0x00e0;
    private static final int RAR4_FILE_LARGE = 0x0100;
    private static final int RAR4_FILE_UNICODE_NAME = 0x0200;
    private static final int RAR4_FILE_SALT = 0x0400;
    private static final int RAR4_FILE_EXT_TIME = 0x1000;
    private static final int RAR4_METHOD_STORE = 0x30;

    private static final long HEADER_FLAG_EXTRA = 0x0001L;
    private static final long HEADER_FLAG_DATA = 0x0002L;
    private static final long HEADER_FLAG_DATA_CONTINUES_PREVIOUS = 0x0008L;
    private static final long HEADER_FLAG_DATA_CONTINUES_NEXT = 0x0010L;

    private static final long FILE_FLAG_DIRECTORY = 0x0001L;
    private static final long FILE_FLAG_UNIX_MTIME = 0x0002L;
    private static final long FILE_FLAG_CRC32 = 0x0004L;
    private static final long FILE_FLAG_UNKNOWN_UNPACKED_SIZE = 0x0008L;

    private static final int EXTRA_FILE_ENCRYPTION = 0x01;
    private static final int EXTRA_FILE_TIME = 0x03;
    private static final int RAR5_ENCRYPTION_VERSION_AES256 = 0;
    private static final int RAR5_ENCRYPTION_CHECK_VALUE = 0x0001;

    private RarArchiveReader() {}

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archive) throws IOException {
        return listEntries(archive, null);
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archive,
                                                      @Nullable char[] password) throws IOException {
        IOException libarchiveFailure = null;
        if (shouldPreferLibarchiveForRar(archive)) {
            try {
                return RarLibarchiveFallback.listEntries(archive, password);
            } catch (ArchiveSupport.PasswordRequiredException e) {
                throw e;
            } catch (IOException | SecurityException e) {
                libarchiveFailure = asIOException(e);
                // Keep first-party metadata parsing as a safe fallback for store-only or
                // partially supported RAR4 files when the native backend rejects the file.
            }
        }

        List<RarEntry> entries;
        try {
            entries = readEntries(archive, password);
        } catch (UnsupportedRarFeatureException e) {
            List<ArchiveSupport.EntryInfo> headerDecrypted = RarHeaderEncryptedArchiveSupport.tryListEntries(archive, password);
            if (headerDecrypted != null) return headerDecrypted;
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, password, libarchiveFailure);
            if (isRar4OrOlderArchive(archive)) {
                if (libarchiveFailure != null) throw libarchiveFailure;
                return RarLibarchiveFallback.listEntries(archive, password);
            }
            if (isRar5Archive(archive)) {
                if (libarchiveFailure != null) throw libarchiveFailure;
                return listRar5EntriesWithFallback(archive, password);
            }
            throw e;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            List<ArchiveSupport.EntryInfo> headerDecrypted = RarHeaderEncryptedArchiveSupport.tryListEntries(archive, password);
            if (headerDecrypted != null) return headerDecrypted;
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, password, libarchiveFailure);
            throw e;
        }
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        for (RarEntry entry : entries) {
            if (entry.splitBefore) continue;
            result.add(new ArchiveSupport.EntryInfo(entry.path, entry.directory, entry.unpackedSize, entry.timeMillis));
        }
        return withSyntheticDirectories(result);
    }

    static boolean requiresPasswordForExtraction(@NonNull File archive) {
        try {
            if (isRarArchive(archive) && RarLibarchiveFallback.requiresPasswordForExtraction(archive)) {
                return true;
            }
            if (isRarArchive(archive) && RarHeaderEncryptionDetector.hasEncryptedHeaders(archive)) {
                return true;
            }
            for (RarEntry entry : readEntries(archive, null)) {
                if (!entry.directory && entry.encrypted()) return true;
            }
            return false;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive, @NonNull File targetDir) throws IOException {
        return extractArchiveIntoDirectory(archive, targetDir, null, null);
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
        IOException libarchiveFailure = null;
        if (shouldPreferLibarchiveForRar(archive)) {
            try {
                return RarLibarchiveFallback.extractArchiveIntoDirectory(
                        archive, targetDir, password, progress, entryProgress);
            } catch (ArchiveSupport.PasswordRequiredException e) {
                throw e;
            } catch (IOException | SecurityException e) {
                libarchiveFailure = asIOException(e);
                // libarchive is the primary RAR backend. Only fall through to the
                // Java reader for stored entries and explicitly scoped special-case
                // work. Do not pretend the unfinished generic RAR3 unpacker is usable.
            }
        }

        List<RarEntry> entries;
        try {
            entries = readEntries(archive, password);
        } catch (UnsupportedRarFeatureException e) {
            if (RarHeaderEncryptedArchiveSupport.tryExtractArchive(
                    archive, targetDir, password, progress, entryProgress)) {
                return true;
            }
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, password, libarchiveFailure);
            if (isRar4OrOlderArchive(archive)) {
                if (libarchiveFailure != null) throw libarchiveFailure;
                return extractRar3Or4ArchiveWithFallback(archive, targetDir, password, progress, entryProgress);
            }
            if (isRar5Archive(archive)) {
                if (libarchiveFailure != null) throw libarchiveFailure;
                return extractRar5ArchiveWithFallback(archive, targetDir, password, progress, entryProgress);
            }
            throw e;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            if (RarHeaderEncryptedArchiveSupport.tryExtractArchive(
                    archive, targetDir, password, progress, entryProgress)) {
                return true;
            }
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, password, libarchiveFailure);
            throw e;
        }
        if (RarFeatureClassifier.hasUnsupportedRar3Or4Payload(entries)) {
            requirePasswordIfNeeded(entries, password);
            if (Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithDecryptedCopy(
                    archive, entries, targetDir, password, progress, entryProgress)) {
                return true;
            }
            if (Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(entries, targetDir, password, progress, entryProgress)) {
                return true;
            }
            if (Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, targetDir, password, progress, entryProgress)) {
                return true;
            }
            if (libarchiveFailure != null || !RarLibarchiveFallback.isAvailable()) {
                throw RarFeatureClassifier.libarchivePrimaryRarFailure(entries, libarchiveFailure);
            }
            return extractRar3Or4ArchiveWithFallback(archive, targetDir, password, progress, entryProgress);
        }
        if (RarFeatureClassifier.shouldUseRar5FallbackForWholeArchive(entries)) {
            requirePasswordIfNeeded(entries, password);
            return extractRar5ArchiveEntryByEntry(entries, targetDir, password, progress, entryProgress);
        }
        if (progress != null) progress.setTotalBytes(sumUnpackedBytes(entries));
        boolean sawEntry = false;
        for (RarEntry entry : entries) {
            if (progress != null && !progress.checkpoint()) return false;
            if (entry.splitBefore) continue;
            if (entryProgress != null) {
                if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                else entryProgress.onFile(entry.path);
            } else if (progress != null) progress.setDetail(entry.path);
            File out = resolveOutput(targetDir, entry.path);
            if (out == null) return false;
            sawEntry = true;
            if (entry.directory || entry.path.endsWith("/")) {
                if (!out.exists() && !out.mkdirs()) return false;
                continue;
            }
            extractStoredEntry(entry, out, password, entries, progress);
        }
        return sawEntry;
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile) throws IOException {
        return extractSingleEntry(archive, entryPath, outFile, null);
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password) throws IOException {
        String normalized = sanitizeEntryPath(entryPath);
        if (normalized == null || normalized.endsWith("/")) return false;

        IOException libarchiveFailure = null;
        if (shouldPreferLibarchiveForRar(archive)) {
            try {
                return RarLibarchiveFallback.extractSingleEntry(
                        archive, normalized, outFile, password, null);
            } catch (ArchiveSupport.PasswordRequiredException e) {
                throw e;
            } catch (IOException | SecurityException e) {
                libarchiveFailure = asIOException(e);
                // Allow only stored-entry Java fallback. Compressed RAR3/RAR4 is
                // libarchive-owned unless it is moved into the explicit solid/encrypted
                // first-party engine.
            }
        }

        List<RarEntry> entries;
        try {
            entries = readEntries(archive, password);
        } catch (UnsupportedRarFeatureException e) {
            if (RarHeaderEncryptedArchiveSupport.tryExtractSingleEntry(
                    archive, normalized, outFile, password, null)) {
                return true;
            }
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, password, libarchiveFailure);
            if (isRar4OrOlderArchive(archive)) {
                if (libarchiveFailure != null) throw libarchiveFailure;
                return RarLibarchiveFallback.extractSingleEntry(
                        archive, normalized, outFile, password, null);
            }
            if (isRar5Archive(archive)) {
                if (libarchiveFailure != null) throw libarchiveFailure;
                return extractRar5SingleEntryWithFallback(archive, normalized, outFile, password, null);
            }
            throw e;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            if (RarHeaderEncryptedArchiveSupport.tryExtractSingleEntry(
                    archive, normalized, outFile, password, null)) {
                return true;
            }
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, password, libarchiveFailure);
            throw e;
        }
        for (RarEntry entry : entries) {
            if (entry.directory || entry.splitBefore) continue;
            if (!normalized.equals(entry.path)) continue;
            if (entry.encrypted() && (password == null || password.length == 0)) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
            if (RarFeatureClassifier.isUnsupportedRar3Or4Payload(entry)) {
                if (Rar3Or4SpecialCaseExtractor.tryExtractEntryWithDecryptedCopy(entry, outFile, password, null)) {
                    return true;
                }
                if (Rar3Or4SpecialCaseExtractor.tryExtractSplitEntryWithDecryptedCopy(entry, entries, outFile, password, null)) {
                    return true;
                }
                if (Rar3Or4SpecialCaseExtractor.tryExtractSplitEntryWithRewrittenCopy(entry, entries, outFile, null)) {
                    return true;
                }
                if (Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entry, entries, outFile, null)) {
                    return true;
                }
                if (libarchiveFailure != null || !RarLibarchiveFallback.isAvailable()) {
                    throw RarFeatureClassifier.libarchivePrimaryRarFailure(entry, libarchiveFailure);
                }
            }
            extractStoredEntry(entry, outFile, password, entries, null);
            return true;
        }
        return false;
    }

    @NonNull
    private static List<RarEntry> readEntries(@NonNull File archive) throws IOException {
        return readEntries(archive, null);
    }

    @NonNull
    private static List<RarEntry> readEntries(@NonNull File archive,
                                              @Nullable char[] password) throws IOException {
        List<File> volumes = RarArchiveLocator.collectReadableVolumes(archive);
        List<RarEntry> result = new ArrayList<>();
        for (File volume : volumes) {
            try (RandomAccessFile raf = new RandomAccessFile(volume, "r")) {
                result.addAll(readSingleVolumeEntries(volume, raf, password));
            } catch (IOException e) {
                if (result.isEmpty()) throw e;
                break;
            }
        }
        return result;
    }

    @NonNull
    static List<RarEntry> readEntriesForSplitStoredDiagnostics(@NonNull File archive,
                                                               @Nullable char[] password) throws IOException {
        return readEntries(archive, password);
    }

    @NonNull
    private static List<RarEntry> readSingleVolumeEntries(@NonNull File archive,
                                                          @NonNull RandomAccessFile raf,
                                                          @Nullable char[] password) throws IOException {
            RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(raf);
            if (signature == null) throw new IOException("Not a RAR archive");
            List<RarEntry> entries;
            if (signature.version != 5) {
                raf.seek(signature.offset + RarArchiveLocator.signatureLength(signature.version));
                entries = readRar4Entries(raf, password);
            } else {
                raf.seek(signature.offset + RarArchiveLocator.signatureLength(signature.version));
                entries = readRar5Entries(raf, password);
            }
            for (RarEntry entry : entries) entry.sourceArchive = archive;
            return entries;
    }

    @NonNull
    private static List<RarEntry> readRar4Entries(@NonNull RandomAccessFile raf,
                                                  @Nullable char[] password) throws IOException {
        List<RarEntry> result = new ArrayList<>();
        while (raf.getFilePointer() < raf.length()) {
            long headerStart = raf.getFilePointer();
            if (raf.length() - headerStart < 7) break;
            readUInt16LE(raf); // Header CRC. RAR4 CRC coverage varies by block kind; treat it as advisory for now.
            int type = raf.readUnsignedByte();
            int flags = readUInt16LE(raf);
            int headerSize = readUInt16LE(raf);
            if (headerSize < 7 || headerStart + headerSize > raf.length()) {
                throw new IOException("Invalid RAR4 header size");
            }

            byte[] headerData = new byte[headerSize - 7];
            raf.readFully(headerData);
            long dataSize = 0L;
            if (type == RAR4_HEADER_FILE) {
                RarEntry entry = parseRar4FileBlock(headerStart + headerSize, flags, headerData);
                if (entry != null) {
                    result.add(entry);
                    dataSize = entry.packedSize;
                }
            } else if (type == RAR4_HEADER_MAIN && (flags & RAR4_MAIN_PASSWORD) != 0) {
                if (password != null && password.length > 0) {
                    throw new UnsupportedRarFeatureException("Encrypted RAR4 headers are not supported yet");
                }
                throw new ArchiveSupport.PasswordRequiredException();
            } else if ((flags & RAR4_LONG_BLOCK) != 0 && headerData.length >= 4) {
                dataSize = uint32FromBytes(headerData, 0);
            }

            long next = headerStart + headerSize + dataSize;
            if (next < headerStart || next > raf.length()) throw new IOException("Invalid RAR4 data size");
            raf.seek(next);
            if (type == RAR4_HEADER_END) break;
        }
        return result;
    }

    @Nullable
    private static RarEntry parseRar4FileBlock(long dataOffset, int flags, @NonNull byte[] headerData) throws IOException {
        ByteCursor cursor = new ByteCursor(headerData);
        long packSize = readUInt32LE(cursor);
        long unpackedSize = readUInt32LE(cursor);
        cursor.readUnsignedByte(); // Host OS.
        long dataCrc = readUInt32LE(cursor);
        long dosTime = readUInt32LE(cursor);
        cursor.readUnsignedByte(); // Minimum RAR version needed to extract.
        int method = cursor.readUnsignedByte();
        int nameSize = readUInt16LE(cursor);
        cursor.readUnsignedInt(); // File attributes.

        if ((flags & RAR4_FILE_LARGE) != 0) {
            long highPack = readUInt32LE(cursor);
            long highUnpacked = readUInt32LE(cursor);
            packSize |= highPack << 32;
            unpackedSize |= highUnpacked << 32;
        }
        if (nameSize < 0 || nameSize > cursor.remaining()) throw new IOException("Invalid RAR4 name size");
        byte[] rawName = cursor.readBytes(nameSize);
        byte[] rar4Salt = new byte[0];
        if ((flags & RAR4_FILE_SALT) != 0 && cursor.remaining() >= 8) {
            rar4Salt = cursor.readBytes(8);
        }
        String path = decodeRar4Name(rawName, (flags & RAR4_FILE_UNICODE_NAME) != 0);
        String sanitized = sanitizeEntryPath(path);
        if (sanitized == null) return null;

        boolean directory = (flags & RAR4_FILE_DIRECTORY_MASK) == RAR4_FILE_DIRECTORY || sanitized.endsWith("/");
        boolean encrypted = (flags & RAR4_FILE_PASSWORD) != 0;
        boolean splitBefore = (flags & RAR4_FILE_SPLIT_BEFORE) != 0;
        boolean splitAfter = (flags & RAR4_FILE_SPLIT_AFTER) != 0;
        boolean solid = (flags & RAR4_FILE_SOLID) != 0;
        int normalizedMethod = method == RAR4_METHOD_STORE ? 0 : method;
        long timeMillis = dosTimeToMillis(dosTime);
        return new RarEntry(sanitized, directory, unpackedSize, packSize, dataOffset, 4,
                normalizedMethod, solid, splitBefore, splitAfter, encrypted ? EncryptionInfo.rar4Unsupported(rar4Salt) : null, dataCrc, timeMillis);
    }

    @NonNull
    private static List<RarEntry> readRar5Entries(@NonNull RandomAccessFile raf,
                                                  @Nullable char[] password) throws IOException {
        List<RarEntry> result = new ArrayList<>();
        while (raf.getFilePointer() < raf.length()) {
            HeaderBlock block = readRar5Header(raf);
            if (block == null) break;
            if (block.type == HEADER_ENCRYPTION) {
                if (password != null && password.length > 0) {
                    throw new UnsupportedRarFeatureException("Encrypted RAR5 headers are not supported yet");
                }
                throw new ArchiveSupport.PasswordRequiredException();
            }
            if (block.type == HEADER_FILE) {
                RarEntry entry = parseRar5FileBlock(block);
                if (entry != null) result.add(entry);
            }
            long next = block.dataOffset + block.dataSize;
            if (next < block.dataOffset || next > raf.length()) throw new IOException("Invalid RAR data size");
            raf.seek(next);
            if (block.type == HEADER_END) break;
        }
        return result;
    }

    @Nullable
    private static HeaderBlock readRar5Header(@NonNull RandomAccessFile raf) throws IOException {
        long headerStart = raf.getFilePointer();
        if (headerStart >= raf.length()) return null;
        if (raf.length() - headerStart < 5) return null;

        long headerCrc = readUInt32LE(raf);
        VInt headerSizeVint = readVInt(raf);
        if (headerSizeVint.value < 0 || headerSizeVint.value > 2L * 1024L * 1024L) {
            throw new IOException("Invalid RAR header size");
        }
        if (headerSizeVint.value > raf.length() - raf.getFilePointer()) {
            throw new IOException("RAR header exceeds file length");
        }

        byte[] headerData = new byte[(int) headerSizeVint.value];
        raf.readFully(headerData);
        verifyHeaderCrc(headerCrc, headerSizeVint.encoded, headerData);

        ByteCursor cursor = new ByteCursor(headerData);
        int type = checkedInt(cursor.readVInt());
        long flags = cursor.readVInt();
        long extraSize = (flags & HEADER_FLAG_EXTRA) != 0 ? cursor.readVInt() : 0L;
        long dataSize = (flags & HEADER_FLAG_DATA) != 0 ? cursor.readVInt() : 0L;
        int fieldsStart = cursor.position();
        int extraStart = safeExtraStart(headerData.length, extraSize);
        long dataOffset = raf.getFilePointer();

        return new HeaderBlock(type, flags, dataSize, dataOffset, headerData, fieldsStart, extraStart, extraSize);
    }

    @Nullable
    private static RarEntry parseRar5FileBlock(@NonNull HeaderBlock block) throws IOException {
        ByteCursor cursor = new ByteCursor(block.headerData, block.fieldsStart, block.extraStart);
        long fileFlags = cursor.readVInt();
        boolean directory = (fileFlags & FILE_FLAG_DIRECTORY) != 0;
        if ((fileFlags & FILE_FLAG_UNKNOWN_UNPACKED_SIZE) != 0) {
            throw new UnsupportedRarFeatureException("Unknown RAR unpacked size is not supported");
        }
        long unpackedSize = cursor.readVInt();
        cursor.readVInt(); // Attributes.
        long timeMillis = 0L;
        if ((fileFlags & FILE_FLAG_UNIX_MTIME) != 0) {
            timeMillis = readUInt32LE(cursor) * 1000L;
        }
        long dataCrc = -1L;
        if ((fileFlags & FILE_FLAG_CRC32) != 0) {
            dataCrc = readUInt32LE(cursor);
        }
        long compressionInfo = cursor.readVInt();
        cursor.readVInt(); // Host OS.
        long nameLength = cursor.readVInt();
        if (nameLength < 0 || nameLength > Integer.MAX_VALUE || nameLength > cursor.remaining()) {
            throw new IOException("Invalid RAR entry name length");
        }
        String path = new String(cursor.readBytes((int) nameLength), StandardCharsets.UTF_8);
        String sanitized = sanitizeEntryPath(path);
        if (sanitized == null) return null;

        ExtraInfo extra = parseFileExtra(block.headerData, block.extraStart, block.extraSize);
        if (extra.timeMillis > 0L) timeMillis = extra.timeMillis;

        int method = (int) ((compressionInfo & 0x0380L) >> 7);
        boolean solid = (compressionInfo & 0x0040L) != 0;
        boolean splitBefore = (block.flags & HEADER_FLAG_DATA_CONTINUES_PREVIOUS) != 0;
        boolean splitAfter = (block.flags & HEADER_FLAG_DATA_CONTINUES_NEXT) != 0;
        return new RarEntry(
                sanitized,
                directory,
                unpackedSize,
                block.dataSize,
                block.dataOffset,
                5,
                method,
                solid,
                splitBefore,
                splitAfter,
                extra.encryption,
                dataCrc,
                timeMillis);
    }

    @NonNull
    private static ExtraInfo parseFileExtra(@NonNull byte[] headerData,
                                            int extraStart,
                                            long extraSize) throws IOException {
        ExtraInfo info = new ExtraInfo();
        if (extraSize <= 0L) return info;
        long extraEndLong = (long) extraStart + extraSize;
        if (extraStart < 0 || extraEndLong > headerData.length) throw new IOException("Invalid RAR extra area");
        ByteCursor cursor = new ByteCursor(headerData, extraStart, (int) extraEndLong);
        while (cursor.hasRemaining()) {
            int recordStart = cursor.position();
            long recordSize = cursor.readVInt();
            if (recordSize < 0 || recordSize > cursor.remaining()) throw new IOException("Invalid RAR extra record");
            int recordEnd = checkedInt((long) cursor.position() + recordSize);
            if (recordEnd > cursor.limit()) throw new IOException("Invalid RAR extra record range");
            long recordType = cursor.readVInt();
            if (recordType == EXTRA_FILE_ENCRYPTION) {
                info.encryption = parseFileEncryptionRecord(cursor, recordEnd);
            } else if (recordType == EXTRA_FILE_TIME) {
                info.timeMillis = parseFileTimeRecord(cursor, recordEnd);
            }
            cursor.setPosition(recordEnd);
            if (cursor.position() <= recordStart) throw new IOException("Invalid RAR extra cursor");
        }
        return info;
    }

    @NonNull
    private static EncryptionInfo parseFileEncryptionRecord(@NonNull ByteCursor cursor,
                                                            int recordEnd) throws IOException {
        long version = cursor.readVInt();
        long flags = cursor.readVInt();
        int kdfCount = cursor.readUnsignedByte();
        byte[] salt = cursor.readBytes(16);
        byte[] iv = cursor.readBytes(16);
        byte[] check = ((flags & RAR5_ENCRYPTION_CHECK_VALUE) != 0 && cursor.position() + 12 <= recordEnd)
                ? cursor.readBytes(12)
                : new byte[0];
        return new EncryptionInfo(version, flags, kdfCount, salt, iv, check);
    }

    private static long parseFileTimeRecord(@NonNull ByteCursor cursor, int recordEnd) throws IOException {
        if (cursor.position() >= recordEnd) return 0L;
        long flags = cursor.readVInt();
        boolean unix = (flags & 0x0001L) != 0;
        boolean hasMtime = (flags & 0x0002L) != 0;
        boolean nanos = (flags & 0x0010L) != 0;
        if (!hasMtime) return 0L;
        if (unix) {
            long value = nanos ? readInt64LE(cursor) : readUInt32LE(cursor);
            return nanos ? value / 1_000_000L : value * 1000L;
        }
        long fileTime = readInt64LE(cursor);
        return windowsFileTimeToMillis(fileTime);
    }

    static void extractStoredEntry(@NonNull RarEntry entry,
                                           @NonNull File outFile,
                                           @Nullable char[] password,
                                           @NonNull List<RarEntry> allEntries,
                                           @Nullable FileOperationProgress progress) throws IOException {
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        if (entry.directory) return;
        if (entry.encrypted() && (password == null || password.length == 0)) {
            throw new ArchiveSupport.PasswordRequiredException();
        }
        if (RarFeatureClassifier.isUnsupportedRar3Or4Payload(entry)) {
            if (Rar3Or4SpecialCaseExtractor.tryExtractEntryWithDecryptedCopy(entry, outFile, password, progress)) {
                return;
            }
            if (Rar3Or4SpecialCaseExtractor.tryExtractSplitEntryWithDecryptedCopy(entry, allEntries, outFile, password, progress)) {
                return;
            }
            if (Rar3Or4SpecialCaseExtractor.tryExtractSplitEntryWithRewrittenCopy(entry, allEntries, outFile, progress)) {
                return;
            }
            if (tryExtractRar3Or4EntryWithLibarchiveFallback(entry, outFile, password, progress)) {
                RarStoredPayloadIO.verifyCrc(entry, outFile);
                return;
            }
            if (Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entry, allEntries, outFile, progress)) {
                return;
            }
            throw RarFeatureClassifier.libarchivePrimaryRarFailure(entry, null);
        }
        if (entry.rarVersion >= 5 && RarFeatureClassifier.shouldUseRar5CompressedFallback(entry)) {
            extractWithRar5CompressedFallback(entry, outFile, password, progress);
            return;
        }
        if (entry.splitBefore) throw new UnsupportedRarFeatureException("RAR split continuation cannot be extracted directly");
        boolean storedMethod = isStoredMethod(entry);
        if (entry.solid && !storedMethod) {
            throw new UnsupportedRarFeatureException("Compressed solid RAR entries require the solid-state decoder");
        }
        if (!storedMethod) {
            if (entry.rarVersion >= 5) {
                throw new UnsupportedRarFeatureException("Compressed RAR5 entries are not supported yet");
            }
            if (entry.splitAfter) {
                throw new UnsupportedRarFeatureException("Compressed split RAR entries are not supported yet");
            }
            throw new UnsupportedRarFeatureException("Compressed RAR3/RAR4 entries are not supported in this build");
        }
        if (!entry.encrypted() && !entry.splitBefore && !entry.splitAfter
                && entry.packedSize != entry.unpackedSize) {
            throw new UnsupportedRarFeatureException("Stored RAR size mismatch");
        }

        if (entry.splitAfter) {
            RarSplitStoredExtractor.extract(entry, outFile, password, allEntries, progress);
            return;
        }

        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            if (entry.encrypted()) {
                try (RandomAccessFile raf = openEntrySource(entry)) {
                    raf.seek(entry.dataOffset);
                    extractEncryptedStoredEntry(raf, entry, outFile, password, progress);
                }
            } else {
                try (RandomAccessFile raf = openEntrySource(entry)) {
                    raf.seek(entry.dataOffset);
                    extractPlainStoredEntry(raf, entry, outFile, progress);
                }
            }
            RarStoredPayloadIO.verifyCrc(entry, outFile);
            guard.commit();
        }
    }

    private static boolean isStoredMethod(@NonNull RarEntry entry) {
        if (entry.rarVersion < 5) return RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
        return entry.method == 0;
    }

    private static boolean shouldPreferLibarchiveForRar(@NonNull File archive) {
        return RarLibarchiveFallback.isAvailable() && isRarArchive(archive);
    }

    private static boolean isRarArchive(@NonNull File archive) {
        int version;
        try {
            version = RarArchiveLocator.detectRarVersion(archive);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
        return version == 4 || version == 5;
    }

    private static boolean extractRar3Or4ArchiveWithFallback(@NonNull File archive,
                                                              @NonNull File targetDir,
                                                              @Nullable char[] password,
                                                              @Nullable FileOperationProgress progress,
                                                              @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        IOException libarchiveFailure = null;
        if (RarLibarchiveFallback.isAvailable()) {
            try {
                return RarLibarchiveFallback.extractArchiveIntoDirectory(
                        archive,
                        targetDir,
                        password,
                        progress,
                        entryProgress);
            } catch (ArchiveSupport.PasswordRequiredException e) {
                throw e;
            } catch (IOException | SecurityException e) {
                libarchiveFailure = asIOException(e);
            }
        }

        List<RarEntry> entries;
        try {
            entries = readEntries(archive, password);
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException e) {
            if (RarHeaderEncryptedArchiveSupport.tryExtractArchive(
                    archive, targetDir, password, progress, entryProgress)) {
                return true;
            }
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, password, libarchiveFailure);
            if (libarchiveFailure != null) throw libarchiveFailure;
            throw e;
        }
        requirePasswordIfNeeded(entries, password);
        if (RarFeatureClassifier.hasUnsupportedRar3Or4Payload(entries)) {
            throw RarFeatureClassifier.libarchivePrimaryRarFailure(entries, libarchiveFailure);
        }
        return extractRar3Or4StoredArchiveFirstParty(entries, targetDir, password, progress, entryProgress);
    }

    private static boolean extractRar3Or4StoredArchiveFirstParty(@NonNull List<RarEntry> entries,
                                                                 @NonNull File targetDir,
                                                                 @Nullable char[] password,
                                                                 @Nullable FileOperationProgress progress,
                                                                 @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (progress != null) progress.setTotalBytes(sumUnpackedBytes(entries));
        boolean sawEntry = false;
        for (RarEntry entry : entries) {
            if (progress != null && !progress.checkpoint()) return false;
            if (entry.splitBefore) continue;
            if (entryProgress != null) {
                if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                else entryProgress.onFile(entry.path);
            } else if (progress != null) {
                progress.setDetail(entry.path);
            }
            File out = resolveOutput(targetDir, entry.path);
            if (out == null) return false;
            sawEntry = true;
            if (entry.directory || entry.path.endsWith("/")) {
                if (!out.exists() && !out.mkdirs()) return false;
                continue;
            }
            extractStoredEntry(entry, out, password, entries, progress);
        }
        return sawEntry;
    }

    @NonNull
    private static IOException asIOException(@NonNull Throwable t) {
        if (t instanceof IOException) return (IOException) t;
        return new IOException(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
    }

    private static boolean tryExtractRar3Or4EntryWithLibarchiveFallback(@NonNull RarEntry entry,
                                                                        @NonNull File outFile,
                                                                        @Nullable char[] password,
                                                                        @Nullable FileOperationProgress progress) throws IOException {
        if (!RarLibarchiveFallback.isAvailable()) return false;
        try {
            extractWithLibarchiveFallback(entry, outFile, password, progress);
            return true;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (UnsupportedRarFeatureException e) {
            return false;
        }
    }

    private static void extractWithLibarchiveFallback(@NonNull RarEntry entry,
                                                  @NonNull File outFile,
                                                  @Nullable char[] password,
                                                  @Nullable FileOperationProgress progress) throws IOException {
        if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
        boolean extracted = RarLibarchiveFallback.extractSingleEntry(
                entry.sourceArchive,
                entry.path,
                outFile,
                password,
                progress);
        if (!extracted) {
            throw new UnsupportedRarFeatureException("RAR libarchive fallback could not extract entry");
        }
    }

    private static void extractRar3Or4CompressedEntry(@NonNull RarEntry entry,
                                                       @NonNull File outFile,
                                                       @Nullable FileOperationProgress progress) throws IOException {
        if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
        RarCompressedPayloadDecoder.extractRar3Or4(
                entry.sourceArchive,
                entry.dataOffset,
                entry.packedSize,
                entry.unpackedSize,
                entry.method,
                entry.solid,
                entry.splitBefore,
                entry.splitAfter,
                entry.encrypted(),
                entry.dataCrc,
                outFile,
                progress);
    }

    private static void extractWithRar5CompressedFallback(@NonNull RarEntry entry,
                                                          @NonNull File outFile,
                                                          @Nullable char[] password,
                                                          @Nullable FileOperationProgress progress) throws IOException {
        if (entry.sourceArchive == null) throw new IOException("RAR5 entry source volume is missing");
        boolean extracted = extractRar5SingleEntryWithFallback(
                entry.sourceArchive,
                entry.path,
                outFile,
                password,
                progress);
        if (!extracted) {
            throw new UnsupportedRarFeatureException("RAR5 fallback could not extract entry");
        }
    }

    @NonNull
    private static List<ArchiveSupport.EntryInfo> listRar5EntriesWithFallback(@NonNull File archive,
                                                                              @Nullable char[] password) throws IOException {
        return RarLibarchiveFallback.listEntries(archive, password);
    }

    private static boolean extractRar5SingleEntryWithFallback(@NonNull File archive,
                                                              @NonNull String entryPath,
                                                              @NonNull File outFile,
                                                              @Nullable char[] password,
                                                              @Nullable FileOperationProgress progress) throws IOException {
        return RarLibarchiveFallback.extractSingleEntry(archive, entryPath, outFile, password, progress);
    }

    private static boolean extractRar5ArchiveWithFallback(@NonNull File archive,
                                                          @NonNull File targetDir,
                                                          @Nullable char[] password,
                                                          @Nullable FileOperationProgress progress,
                                                          @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        return RarLibarchiveFallback.extractArchiveIntoDirectory(archive, targetDir, password, progress, entryProgress);
    }

    private static boolean extractRar5ArchiveEntryByEntry(@NonNull List<RarEntry> entries,
                                                          @NonNull File targetDir,
                                                          @Nullable char[] password,
                                                          @Nullable FileOperationProgress progress,
                                                          @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (progress != null) progress.setTotalBytes(sumUnpackedBytes(entries));
        boolean sawEntry = false;
        for (RarEntry entry : entries) {
            if (progress != null && !progress.checkpoint()) return false;
            if (entry == null || entry.rarVersion < 5 || entry.splitBefore) continue;
            if (entryProgress != null) {
                if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                else entryProgress.onFile(entry.path);
            } else if (progress != null) {
                progress.setDetail(entry.path);
            }
            File out = resolveOutput(targetDir, entry.path);
            if (out == null) return false;
            sawEntry = true;
            if (entry.directory || entry.path.endsWith("/")) {
                if (!out.exists() && !out.mkdirs()) return false;
                continue;
            }
            extractStoredEntry(entry, out, password, entries, progress);
        }
        return sawEntry;
    }

    private static void requirePasswordIfNeeded(@NonNull List<RarEntry> entries,
                                                @Nullable char[] password) throws IOException {
        if (password != null && password.length > 0) return;
        for (RarEntry entry : entries) {
            if (entry != null && !entry.directory && entry.encrypted()) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
        }
    }

    private static long sumUnpackedBytes(@NonNull List<RarEntry> entries) {
        long total = 0L;
        boolean unknown = false;
        for (RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) continue;
            if (entry.unpackedSize < 0L) {
                unknown = true;
                continue;
            }
            if (Long.MAX_VALUE - total < entry.unpackedSize) return Long.MAX_VALUE;
            total += entry.unpackedSize;
        }
        return total > 0L ? total : (unknown ? -1L : 0L);
    }

    @NonNull
    private static RandomAccessFile openEntrySource(@NonNull RarEntry entry) throws IOException {
        if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
        return new RandomAccessFile(entry.sourceArchive, "r");
    }

    private static void extractPlainStoredEntry(@NonNull RandomAccessFile raf,
                                                @NonNull RarEntry entry,
                                                @NonNull File outFile,
                                                @Nullable FileOperationProgress progress) throws IOException {
        RarStoredPayloadIO.copyPlainEntryToFile(raf, entry.packedSize, outFile, progress);
    }

    private static void extractEncryptedStoredEntry(@NonNull RandomAccessFile raf,
                                                    @NonNull RarEntry entry,
                                                    @NonNull File outFile,
                                                    @Nullable char[] password,
                                                    @Nullable FileOperationProgress progress) throws IOException {
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        EncryptionInfo encryption = entry.encryption;
        if (encryption == null) {
            throw new UnsupportedRarFeatureException("Encrypted RAR file data is not supported yet");
        }
        if ((entry.packedSize % 16L) != 0L) {
            throw new UnsupportedRarFeatureException("Encrypted RAR data is not AES block aligned");
        }
        if (entry.rarVersion < 5 && encryption.isRar4Aes()) {
            if (!RarFeatureClassifier.isRar3Or4StoredMethod(entry.method) || entry.solid || entry.splitBefore || entry.splitAfter) {
                throw new UnsupportedRarFeatureException(
                        "RAR3/RAR4 encrypted compressed, solid, or split payloads are not supported yet");
            }
            extractRar4EncryptedStoredPayload(raf, entry, outFile, password, encryption, progress);
            return;
        }

        if (encryption.isRar5Aes256()) {
            if (entry.unpackedSize < 0L) throw new IOException("Invalid decrypted RAR stored size");
            Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(password, encryption.kdfCount, encryption.salt);
            if (!Rar5Crypto.passwordMatches(secrets, encryption.check)) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
            Cipher cipher = Rar5Crypto.createAesCbcDecryptCipher(secrets, encryption.iv);
            RarCryptoStreams.decryptToFile(
                    raf,
                    entry.packedSize,
                    entry.unpackedSize,
                    cipher,
                    outFile,
                    "RAR5 AES decrypt failed",
                    progress,
                    true);
            return;
        }
        throw new UnsupportedRarFeatureException("Encrypted RAR file data is not supported yet");
    }

    private static void extractRar4EncryptedStoredPayload(@NonNull RandomAccessFile raf,
                                                           @NonNull RarEntry entry,
                                                           @NonNull File outFile,
                                                           @NonNull char[] password,
                                                           @NonNull EncryptionInfo encryption,
                                                           @Nullable FileOperationProgress progress) throws IOException {
        Cipher cipher = Rar3Crypto.createAesCbcDecryptCipher(password, encryption.salt);
        RarCryptoStreams.decryptToFile(
                raf,
                entry.packedSize,
                entry.unpackedSize,
                cipher,
                outFile,
                "RAR3/RAR4 AES decrypt failed",
                progress,
                true);
    }

    static boolean isRar4OrOlderArchive(@NonNull File archive) {
        try {
            return RarArchiveLocator.detectRarVersion(archive) == 4;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    static boolean isRar5Archive(@NonNull File archive) {
        try {
            return RarArchiveLocator.detectRarVersion(archive) == 5;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static void verifyHeaderCrc(long expected,
                                        @NonNull byte[] encodedHeaderSize,
                                        @NonNull byte[] headerData) throws IOException {
        CRC32 crc32 = new CRC32();
        crc32.update(encodedHeaderSize, 0, encodedHeaderSize.length);
        crc32.update(headerData, 0, headerData.length);
        long actual = crc32.getValue() & 0xffffffffL;
        if (actual != (expected & 0xffffffffL)) throw new IOException("RAR header CRC mismatch");
    }

    private static long readUInt32LE(@NonNull RandomAccessFile raf) throws IOException {
        long b0 = raf.readUnsignedByte();
        long b1 = raf.readUnsignedByte();
        long b2 = raf.readUnsignedByte();
        long b3 = raf.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readUInt16LE(@NonNull RandomAccessFile raf) throws IOException {
        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static long readUInt32LE(@NonNull ByteCursor cursor) throws IOException {
        long b0 = cursor.readUnsignedByte();
        long b1 = cursor.readUnsignedByte();
        long b2 = cursor.readUnsignedByte();
        long b3 = cursor.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readUInt16LE(@NonNull ByteCursor cursor) throws IOException {
        int b0 = cursor.readUnsignedByte();
        int b1 = cursor.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static long readInt64LE(@NonNull ByteCursor cursor) throws IOException {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= ((long) cursor.readUnsignedByte()) << (8 * i);
        }
        return value;
    }

    @NonNull
    private static VInt readVInt(@NonNull RandomAccessFile raf) throws IOException {
        long value = 0L;
        int shift = 0;
        byte[] encoded = new byte[10];
        int count = 0;
        while (count < encoded.length) {
            int b = raf.readUnsignedByte();
            encoded[count++] = (byte) b;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                byte[] used = new byte[count];
                System.arraycopy(encoded, 0, used, 0, count);
                return new VInt(value, used);
            }
            shift += 7;
        }
        throw new IOException("RAR vint is too long");
    }

    private static int safeExtraStart(int headerLength, long extraSize) throws IOException {
        long start = (long) headerLength - extraSize;
        if (extraSize < 0 || start < 0 || start > headerLength) throw new IOException("Invalid RAR extra size");
        return (int) start;
    }

    private static int checkedInt(long value) throws IOException {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IOException("Integer overflow");
        return (int) value;
    }

    @NonNull
    static List<File> collectVolumeChainForBackend(@NonNull File archive) throws IOException {
        return RarArchiveLocator.collectReadableVolumes(archive);
    }

    @NonNull
    static RarVolumeChainResolution resolveVolumeChainForBackend(@NonNull File archive) {
        return RarArchiveLocator.resolveVolumeChain(archive);
    }

    static long findEmbeddedRarSignatureOffsetForBackend(@NonNull File archive) throws IOException {
        return RarArchiveLocator.findEmbeddedRarSignatureOffset(archive);
    }

    private static long uint32FromBytes(@NonNull byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24);
    }

    @Nullable
    static File resolveOutput(@NonNull File targetDir, @NonNull String entryPath) throws IOException {
        String path = sanitizeEntryPath(entryPath);
        if (path == null) return null;
        File out = new File(targetDir, path);
        return isSameOrDescendant(targetDir, out) ? out : null;
    }

    @Nullable
    private static String sanitizeEntryPath(String rawEntryName) {
        if (rawEntryName == null) return null;
        String entryName = rawEntryName.trim().replace('\\', '/');
        while (entryName.startsWith("./")) entryName = entryName.substring(2);
        while (entryName.contains("//")) entryName = entryName.replace("//", "/");
        if (entryName.length() == 0) return null;
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

    private static boolean isSameOrDescendant(@NonNull File ancestor, @NonNull File candidate) throws IOException {
        File ancestorCanonical = ancestor.getCanonicalFile();
        File current = candidate.getCanonicalFile();
        while (current != null) {
            if (ancestorCanonical.equals(current)) return true;
            current = current.getParentFile();
        }
        return false;
    }

    @NonNull
    private static List<ArchiveSupport.EntryInfo> withSyntheticDirectories(@NonNull List<ArchiveSupport.EntryInfo> entries) {
        Map<String, ArchiveSupport.EntryInfo> map = new LinkedHashMap<>();
        for (ArchiveSupport.EntryInfo entry : entries) {
            String path = entry.path;
            int slash = path.indexOf('/');
            while (slash >= 0) {
                String dir = path.substring(0, slash + 1);
                if (!map.containsKey(dir)) map.put(dir, new ArchiveSupport.EntryInfo(dir, true, -1L, 0L));
                slash = path.indexOf('/', slash + 1);
            }
            map.put(path, entry);
        }
        return new ArrayList<>(map.values());
    }

    private static long windowsFileTimeToMillis(long fileTime) {
        return (fileTime / 10_000L) - 11_644_473_600_000L;
    }

    private static long dosTimeToMillis(long dosTime) {
        int second = (int) ((dosTime & 0x1f) * 2);
        int minute = (int) ((dosTime >> 5) & 0x3f);
        int hour = (int) ((dosTime >> 11) & 0x1f);
        int day = (int) ((dosTime >> 16) & 0x1f);
        int month = (int) ((dosTime >> 21) & 0x0f);
        int year = (int) (((dosTime >> 25) & 0x7f) + 1980);
        if (month < 1 || month > 12 || day < 1 || day > 31) return 0L;
        java.util.Calendar calendar = java.util.Calendar.getInstance(java.util.TimeZone.getDefault(), java.util.Locale.ROOT);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }

    @NonNull
    private static String decodeRar4Name(@NonNull byte[] rawName, boolean unicodeName) {
        int plainLength = rawName.length;
        if (unicodeName) {
            for (int i = 0; i < rawName.length; i++) {
                if (rawName[i] == 0) {
                    plainLength = i;
                    break;
                }
            }
        }
        byte[] plain = new byte[plainLength];
        System.arraycopy(rawName, 0, plain, 0, plainLength);
        if (unicodeName && plainLength + 1 < rawName.length) {
            String decoded = decodeRar4UnicodeName(plain, rawName, plainLength + 1);
            if (decoded != null && decoded.length() > 0) return decoded;
        }
        String utf8 = tryDecode(plain, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8;
        return new String(plain, Charset.forName("IBM437"));
    }

    @Nullable
    private static String decodeRar4UnicodeName(@NonNull byte[] plainName,
                                                @NonNull byte[] rawName,
                                                int encodedOffset) {
        if (encodedOffset >= rawName.length) return null;
        int encodedPos = encodedOffset;
        int highByte = rawName[encodedPos++] & 0xff;
        int flags = 0;
        int flagBits = 0;
        int plainPos = 0;
        StringBuilder decoded = new StringBuilder(rawName.length);
        try {
            while (encodedPos < rawName.length) {
                if (flagBits == 0) {
                    flags = rawName[encodedPos++] & 0xff;
                    flagBits = 8;
                    if (encodedPos > rawName.length) break;
                }
                int mode = (flags >>> 6) & 0x03;
                flags = (flags << 2) & 0xff;
                flagBits -= 2;
                switch (mode) {
                    case 0:
                        if (encodedPos >= rawName.length) return null;
                        decoded.append((char) (rawName[encodedPos++] & 0xff));
                        plainPos++;
                        break;
                    case 1:
                        if (encodedPos >= rawName.length) return null;
                        decoded.append((char) ((highByte << 8) | (rawName[encodedPos++] & 0xff)));
                        plainPos++;
                        break;
                    case 2:
                        if (encodedPos + 1 >= rawName.length) return null;
                        int low = rawName[encodedPos++] & 0xff;
                        int high = rawName[encodedPos++] & 0xff;
                        decoded.append((char) (low | (high << 8)));
                        plainPos++;
                        break;
                    case 3:
                        if (encodedPos >= rawName.length) return null;
                        int length = rawName[encodedPos++] & 0xff;
                        if ((length & 0x80) != 0) {
                            if (encodedPos >= rawName.length) return null;
                            int correction = rawName[encodedPos++] & 0xff;
                            int count = (length & 0x7f) + 2;
                            for (int i = 0; i < count; i++) {
                                if (plainPos >= plainName.length) return null;
                                int value = ((plainName[plainPos++] & 0xff) + correction) & 0xff;
                                decoded.append((char) ((highByte << 8) | value));
                            }
                        } else {
                            int count = length + 2;
                            for (int i = 0; i < count; i++) {
                                if (plainPos >= plainName.length) return null;
                                decoded.append((char) (plainName[plainPos++] & 0xff));
                            }
                        }
                        break;
                    default:
                        return null;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return decoded.length() > 0 ? decoded.toString() : null;
    }

    @Nullable
    private static String tryDecode(@NonNull byte[] bytes, @NonNull Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return null;
        }
    }

    private static final class VInt {
        final long value;
        final byte[] encoded;

        VInt(long value, @NonNull byte[] encoded) {
            this.value = value;
            this.encoded = encoded;
        }
    }

    private static final class HeaderBlock {
        final int type;
        final long flags;
        final long dataSize;
        final long dataOffset;
        final byte[] headerData;
        final int fieldsStart;
        final int extraStart;
        final long extraSize;

        HeaderBlock(int type,
                    long flags,
                    long dataSize,
                    long dataOffset,
                    @NonNull byte[] headerData,
                    int fieldsStart,
                    int extraStart,
                    long extraSize) {
            this.type = type;
            this.flags = flags;
            this.dataSize = dataSize;
            this.dataOffset = dataOffset;
            this.headerData = headerData;
            this.fieldsStart = fieldsStart;
            this.extraStart = extraStart;
            this.extraSize = extraSize;
        }
    }

    static final class RarEntry {
        final String path;
        final boolean directory;
        final long unpackedSize;
        final long packedSize;
        final long dataOffset;
        final int rarVersion;
        final int method;
        final boolean solid;
        final boolean splitBefore;
        final boolean splitAfter;
        @Nullable final EncryptionInfo encryption;
        final long dataCrc;
        final long timeMillis;
        @Nullable File sourceArchive;

        RarEntry(@NonNull String path,
                 boolean directory,
                 long unpackedSize,
                 long packedSize,
                 long dataOffset,
                 int rarVersion,
                 int method,
                 boolean solid,
                 boolean splitBefore,
                 boolean splitAfter,
                 @Nullable EncryptionInfo encryption,
                 long dataCrc,
                 long timeMillis) {
            this.path = directory && !path.endsWith("/") ? path + "/" : path;
            this.directory = directory;
            this.unpackedSize = unpackedSize;
            this.packedSize = packedSize;
            this.dataOffset = dataOffset;
            this.rarVersion = rarVersion;
            this.method = method;
            this.solid = solid;
            this.splitBefore = splitBefore;
            this.splitAfter = splitAfter;
            this.encryption = encryption;
            this.dataCrc = dataCrc;
            this.timeMillis = timeMillis;
        }

        boolean encrypted() {
            return encryption != null;
        }
    }

    private static final class ExtraInfo {
        @Nullable EncryptionInfo encryption;
        long timeMillis;
    }

    static final class EncryptionInfo {
        final long version;
        final long flags;
        final int kdfCount;
        final byte[] salt;
        final byte[] iv;
        final byte[] check;
        final boolean rar4Unsupported;

        EncryptionInfo(long version,
                       long flags,
                       int kdfCount,
                       @NonNull byte[] salt,
                       @NonNull byte[] iv,
                       @NonNull byte[] check) {
            this.version = version;
            this.flags = flags;
            this.kdfCount = kdfCount;
            this.salt = salt;
            this.iv = iv;
            this.check = check;
            this.rar4Unsupported = false;
        }

        private EncryptionInfo(@NonNull byte[] rar4Salt) {
            this.version = -1L;
            this.flags = 0L;
            this.kdfCount = 0;
            this.salt = rar4Salt;
            this.iv = new byte[0];
            this.check = new byte[0];
            this.rar4Unsupported = true;
        }

        static EncryptionInfo rar4Unsupported(@NonNull byte[] rar4Salt) {
            return new EncryptionInfo(rar4Salt);
        }

        boolean isRar4Aes() {
            return rar4Unsupported && salt.length == 8;
        }

        boolean isRar5Aes256() {
            return !rar4Unsupported
                    && version == RAR5_ENCRYPTION_VERSION_AES256
                    && salt.length == 16
                    && iv.length == 16;
        }
    }

    static final class UnsupportedRarFeatureException extends ArchiveSupport.UnsupportedArchiveFeatureException {
        UnsupportedRarFeatureException(@NonNull String message) {
            super(message);
        }
    }

    private static final class ByteCursor {
        private final byte[] data;
        private final int limit;
        private int position;

        ByteCursor(@NonNull byte[] data) {
            this(data, 0, data.length);
        }

        ByteCursor(@NonNull byte[] data, int start, int limit) {
            this.data = data;
            this.position = start;
            this.limit = limit;
        }

        int position() {
            return position;
        }

        int limit() {
            return limit;
        }

        void setPosition(int position) throws IOException {
            if (position < 0 || position > limit) throw new IOException("Invalid RAR cursor position");
            this.position = position;
        }

        boolean hasRemaining() {
            return position < limit;
        }

        int remaining() {
            return limit - position;
        }

        int readUnsignedByte() throws IOException {
            if (position >= limit) throw new EOFException("Unexpected end of RAR header");
            return data[position++] & 0xff;
        }

        long readVInt() throws IOException {
            long value = 0L;
            int shift = 0;
            for (int i = 0; i < 10; i++) {
                int b = readUnsignedByte();
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IOException("RAR vint is too long");
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || length > remaining()) throw new EOFException("Unexpected end of RAR header bytes");
            byte[] result = new byte[length];
            System.arraycopy(data, position, result, 0, length);
            position += length;
            return result;
        }

        long readUnsignedInt() throws IOException {
            return readUInt32LE(this);
        }
    }
}
