package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.LZMAInputStream;

import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import com.readwide.manager.util.FileOperationProgress;

/**
 * First-party 7z reader for the coder chains Apache Commons Compress cannot
 * decode: folders containing the BCJ2 branch filter (coder id
 * {@code 03 03 01 1B}), a four-input coder Commons Compress rejects with
 * "Multi input/output stream coders are not yet supported", and folders
 * containing the PPMd coder (id {@code 03 04 01}), which Commons Compress has
 * no decoder for. Such folders occur both plain and under AES encryption; in
 * the AES case Commons Compress decrypts correctly but then fails on the
 * inner coder, and the bundled libarchive cannot decrypt 7z at all, so
 * AES+BCJ2 and AES+PPMd archives were previously unsupported end to end.
 *
 * <p>This reader parses the 7z header itself (clean-room, from the documented
 * container format; no 7-Zip source is used), resolves each folder's coder
 * dependency graph, decodes the base streams with the app's bundled decoders
 * (LZMA/LZMA2 via xz-java; the Copy coder is a pass-through), decrypts AES
 * streams with {@link SevenZAesDecoder}, and applies {@link SevenZBcj2Decoder}
 * for the BCJ2 join and {@link SevenZPpmd7Decoder} for PPMd streams. Folders
 * whose coders are fully handled by Commons Compress are left to it; this
 * path is only taken as a fallback, gated on the archive actually containing
 * a BCJ2 or PPMd folder. Unimplemented coders raise a clear unsupported error
 * rather than guessing.</p>
 */
final class SevenZBcj2ArchiveReader {
    private static final byte[] SIGNATURE = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
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
    private static final byte[] ID_COPY = {0x00};
    private static final byte[] ID_LZMA = {0x03, 0x01, 0x01};
    private static final byte[] ID_LZMA2 = {0x21};
    private static final byte[] ID_BCJ2 = {0x03, 0x03, 0x01, 0x1B};
    private static final byte[] ID_PPMD = {0x03, 0x04, 0x01};
    private static final byte[] ID_AES = {0x06, (byte) 0xF1, 0x07, 0x01};

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
     * party because the Commons Compress path cannot (BCJ2 or PPMd). Used to
     * gate the fallback so all other 7z archives keep their existing paths.
     */
    static boolean archiveUsesSpecialCoder(@NonNull File archive, @Nullable char[] password) {
        try {
            SevenZArchive parsed = parse(archive, password);
            for (Folder folder : parsed.folders) {
                if (folder.usesBcj2() || folder.usesPpmd()) return true;
            }
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
            if (entry.isDirectory || entry.folderIndex < 0) continue;
            if (!entryPath.equals(entry.name)) continue;
            byte[] folderData = decodeFolder(archive, parsed, entry.folderIndex, password);
            writeSlice(folderData, entry.offsetInFolder, entry.size, outFile);
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
        byte[] folderData = null;
        for (FileEntry entry : parsed.files) {
            if (progress != null && !progress.checkpoint()) return false;
            if (entry.isDirectory) {
                if (entryProgress != null) entryProgress.onDirectory(entry.name);
                File dir = safeChild(targetDir, entry.name);
                if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
                continue;
            }
            File out = safeChild(targetDir, entry.name);
            if (out == null) continue;
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            if (entryProgress != null) entryProgress.onFile(entry.name);
            else if (progress != null) progress.setDetail(entry.name);
            if (entry.folderIndex < 0 || entry.size == 0) {
                writeSlice(new byte[0], 0, 0, out);
                any = true;
                continue;
            }
            if (entry.folderIndex != currentFolder) {
                folderData = decodeFolder(archive, parsed, entry.folderIndex, password);
                currentFolder = entry.folderIndex;
            }
            writeSlice(folderData, entry.offsetInFolder, entry.size, out);
            if (progress != null) progress.addDoneBytes(entry.size);
            any = true;
        }
        return any;
    }

    // ----- Folder decoding -----

    /**
     * Decodes an entire folder to its final uncompressed bytes by resolving
     * the coder dependency graph: each coder's inputs are either pack streams
     * (read from disk) or the outputs of earlier coders (via bind pairs); the
     * folder's output is the coder output that is not bound to any input.
     */
    @NonNull
    private static byte[] decodeFolder(@NonNull File archive,
                                       @NonNull SevenZArchive parsed,
                                       int folderIndex,
                                       @Nullable char[] password) throws IOException {
        Folder folder = parsed.folders.get(folderIndex);
        long[] packSizes = parsed.packSizes;
        long basePackOffset = 32 + parsed.packPos;
        long packStreamOffset = basePackOffset;
        for (int i = 0; i < folder.firstPackStreamIndex; i++) {
            packStreamOffset += packSizes[i];
        }

        // Read this folder's pack streams from disk into memory.
        byte[][] packStreams = new byte[folder.numPackStreams][];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            long offset = packStreamOffset;
            for (int i = 0; i < folder.numPackStreams; i++) {
                long size = packSizes[folder.firstPackStreamIndex + i];
                if (size < 0 || size > MAX_STREAM_BYTES) throw new IOException("7z pack stream too large");
                byte[] buffer = new byte[(int) size];
                raf.seek(offset);
                raf.readFully(buffer);
                packStreams[i] = buffer;
                offset += size;
            }
        }

        // Map each global input index to its pack stream (if packed).
        byte[][] inputData = new byte[folder.totalInputStreams][];
        for (int i = 0; i < folder.packedInputIndices.length; i++) {
            inputData[folder.packedInputIndices[i]] = packStreams[i];
        }

        // Resolve each coder's single output on demand.
        byte[][] coderOutput = new byte[folder.coders.length][];
        boolean[] resolving = new boolean[folder.coders.length];
        int finalCoder = folder.findFinalOutputCoder();
        byte[] result = resolveCoderOutput(folder, finalCoder, inputData, coderOutput, resolving, password);
        if (result.length > folder.getUnpackSize()) {
            return Arrays.copyOf(result, (int) folder.getUnpackSize());
        }
        return result;
    }

    @NonNull
    private static byte[] resolveCoderOutput(@NonNull Folder folder,
                                             int coderIndex,
                                             @NonNull byte[][] inputData,
                                             @NonNull byte[][] coderOutput,
                                             @NonNull boolean[] resolving,
                                             @Nullable char[] password) throws IOException {
        if (coderOutput[coderIndex] != null) return coderOutput[coderIndex];
        if (resolving[coderIndex]) throw new IOException("7z coder graph has a cycle");
        resolving[coderIndex] = true;

        Coder coder = folder.coders[coderIndex];
        int firstInput = folder.coderInputBase[coderIndex];
        byte[][] inputs = new byte[coder.numInStreams][];
        for (int i = 0; i < coder.numInStreams; i++) {
            int globalInput = firstInput + i;
            byte[] packed = inputData[globalInput];
            if (packed != null) {
                inputs[i] = packed;
            } else {
                int sourceCoder = folder.boundInputToCoder(globalInput);
                if (sourceCoder < 0) throw new IOException("7z input stream is unbound");
                inputs[i] = resolveCoderOutput(folder, sourceCoder, inputData, coderOutput, resolving, password);
            }
        }

        long unpackSize = folder.coderUnpackSizes[coderIndex];
        byte[] output = runCoder(coder, inputs, unpackSize, password);
        coderOutput[coderIndex] = output;
        resolving[coderIndex] = false;
        return output;
    }

    @NonNull
    private static byte[] runCoder(@NonNull Coder coder,
                                   @NonNull byte[][] inputs,
                                   long unpackSize,
                                   @Nullable char[] password) throws IOException {
        if (unpackSize < 0 || unpackSize > MAX_STREAM_BYTES) {
            throw new IOException("7z coder output size out of range");
        }
        if (matchesId(coder.id, ID_COPY)) {
            return inputs[0];
        }
        if (matchesId(coder.id, ID_LZMA)) {
            return decodeLzma(inputs[0], coder.properties, unpackSize);
        }
        if (matchesId(coder.id, ID_LZMA2)) {
            return decodeLzma2(inputs[0], coder.properties, unpackSize);
        }
        if (matchesId(coder.id, ID_AES)) {
            if (password == null || password.length == 0) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
            return SevenZAesDecoder.decode(inputs[0], coder.properties, password, unpackSize);
        }
        if (matchesId(coder.id, ID_BCJ2)) {
            return SevenZBcj2Decoder.decode(inputs[0], inputs[1], inputs[2], inputs[3], unpackSize);
        }
        if (matchesId(coder.id, ID_PPMD)) {
            return SevenZPpmd7Decoder.decode(inputs[0], coder.properties, unpackSize);
        }
        throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                "Unsupported 7z coder " + hex(coder.id));
    }

    @NonNull
    private static byte[] decodeLzma(@NonNull byte[] data, @Nullable byte[] props, long unpackSize) throws IOException {
        if (props == null || props.length < 5) throw new IOException("7z LZMA properties missing");
        byte propsByte = props[0];
        int dictSize = (props[1] & 0xff) | ((props[2] & 0xff) << 8)
                | ((props[3] & 0xff) << 16) | ((props[4] & 0xff) << 24);
        try (InputStream in = new LZMAInputStream(new ByteArrayInputStream(data), unpackSize, propsByte, dictSize)) {
            return readExact(in, unpackSize);
        }
    }

    @NonNull
    private static byte[] decodeLzma2(@NonNull byte[] data, @Nullable byte[] props, long unpackSize) throws IOException {
        if (props == null || props.length < 1) throw new IOException("7z LZMA2 properties missing");
        int dictSize = dictSizeFromProp(props[0]);
        try (InputStream in = new LZMA2InputStream(new ByteArrayInputStream(data), dictSize)) {
            return readExact(in, unpackSize);
        }
    }

    private static int dictSizeFromProp(byte prop) {
        int bits = prop & 0x3f;
        if (bits > 40) return Integer.MAX_VALUE;
        if (bits == 40) return 0xFFFFFFFF;
        return (2 | (bits & 1)) << (bits / 2 + 11);
    }

    // ----- Header parsing -----

    @NonNull
    private static SevenZArchive parse(@NonNull File archive, @Nullable char[] password) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            byte[] sig = new byte[6];
            raf.readFully(sig);
            if (!Arrays.equals(sig, SIGNATURE)) throw new IOException("Not a 7z archive");
            raf.skipBytes(2); // version
            raf.skipBytes(4); // start header CRC
            long nextHeaderOffset = readUInt64LE(raf);
            long nextHeaderSize = readUInt64LE(raf);
            if (nextHeaderSize <= 0 || nextHeaderSize > MAX_STREAM_BYTES) {
                throw new IOException("7z header size out of range");
            }
            byte[] header = new byte[(int) nextHeaderSize];
            raf.seek(32 + nextHeaderOffset);
            raf.readFully(header);

            ByteReader reader = new ByteReader(header);
            int id = reader.readByte();
            if (id == K_ENCODED_HEADER) {
                header = decodeEncodedHeader(archive, reader, password);
                reader = new ByteReader(header);
                id = reader.readByte();
            }
            if (id != K_HEADER) {
                throw new IOException(password != null
                        ? "7z header could not be read (wrong password?)"
                        : "7z header not found");
            }
            return parseHeader(reader);
        }
    }

    @NonNull
    private static byte[] decodeEncodedHeader(@NonNull File archive,
                                              @NonNull ByteReader reader,
                                              @Nullable char[] password) throws IOException {
        StreamsInfo info = readStreamsInfo(reader);
        SevenZArchive tmp = new SevenZArchive();
        tmp.packPos = info.packPos;
        tmp.packSizes = info.packSizes;
        tmp.folders = info.folders;
        if (info.folders.isEmpty()) throw new IOException("7z encoded header has no folder");
        return decodeFolder(archive, tmp, 0, password);
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
            result.folders = streams.folders;
        }
        if (id == K_FILES_INFO) {
            readFilesInfo(reader, result, streams);
            id = reader.readByte();
        }
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
            long numPack = reader.readNumber();
            int type = reader.readByte();
            if (type == K_SIZE) {
                info.packSizes = new long[(int) numPack];
                for (int i = 0; i < numPack; i++) info.packSizes[i] = reader.readNumber();
                type = reader.readByte();
            }
            while (type != K_END) {
                if (type == K_CRC) {
                    skipDigests(reader, (int) numPack);
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
            }
        }
        // id should be K_END here.
        assignPackStreamsToFolders(info);
        return info;
    }

    private static void readUnpackInfo(@NonNull ByteReader reader, @NonNull StreamsInfo info) throws IOException {
        int id = reader.readByte();
        if (id != K_FOLDER) throw new IOException("7z UnpackInfo missing Folder");
        long numFolders = reader.readNumber();
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
                skipDigests(reader, info.folders.size());
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
        folder.coders = new Coder[(int) numCoders];
        folder.coderInputBase = new int[(int) numCoders];
        folder.coderOutputBase = new int[(int) numCoders];
        int totalIn = 0;
        int totalOut = 0;
        for (int i = 0; i < numCoders; i++) {
            Coder coder = new Coder();
            int flags = reader.readByte();
            int idSize = flags & 0x0f;
            coder.id = reader.readBytes(idSize);
            if ((flags & 0x10) != 0) {
                coder.numInStreams = (int) reader.readNumber();
                coder.numOutStreams = (int) reader.readNumber();
            } else {
                coder.numInStreams = 1;
                coder.numOutStreams = 1;
            }
            if ((flags & 0x20) != 0) {
                long propSize = reader.readNumber();
                coder.properties = reader.readBytes((int) propSize);
            }
            if ((flags & 0x80) != 0) throw new IOException("7z alternative coder methods unsupported");
            folder.coderInputBase[i] = totalIn;
            folder.coderOutputBase[i] = totalOut;
            folder.coders[i] = coder;
            totalIn += coder.numInStreams;
            totalOut += coder.numOutStreams;
        }
        folder.totalInputStreams = totalIn;
        folder.totalOutputStreams = totalOut;

        int numBindPairs = totalOut - 1;
        folder.bindPairInIndex = new int[numBindPairs];
        folder.bindPairOutIndex = new int[numBindPairs];
        for (int i = 0; i < numBindPairs; i++) {
            folder.bindPairInIndex[i] = (int) reader.readNumber();
            folder.bindPairOutIndex[i] = (int) reader.readNumber();
        }

        int numPackedStreams = totalIn - numBindPairs;
        if (numPackedStreams == 1) {
            // The single packed stream is the only input not used by a bind pair.
            int packedIndex = -1;
            for (int i = 0; i < totalIn; i++) {
                if (folder.boundInputToCoderRaw(i) < 0) {
                    packedIndex = i;
                    break;
                }
            }
            if (packedIndex < 0) throw new IOException("7z folder has no packed input");
            folder.packedInputIndices = new int[] {packedIndex};
        } else {
            folder.packedInputIndices = new int[numPackedStreams];
            for (int i = 0; i < numPackedStreams; i++) {
                folder.packedInputIndices[i] = (int) reader.readNumber();
            }
        }
        return folder;
    }

    private static void readSubStreamsInfo(@NonNull ByteReader reader, @NonNull StreamsInfo info) throws IOException {
        int id = reader.readByte();
        for (Folder folder : info.folders) folder.numUnpackSubStreams = 1;
        if (id == K_NUM_UNPACK_STREAM) {
            for (Folder folder : info.folders) {
                folder.numUnpackSubStreams = (int) reader.readNumber();
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
            long sum = 0;
            long[] sizes = new long[folder.numUnpackSubStreams];
            if (id == K_SIZE) {
                for (int i = 0; i < folder.numUnpackSubStreams - 1; i++) {
                    long s = reader.readNumber();
                    sizes[i] = s;
                    sum += s;
                }
            }
            sizes[folder.numUnpackSubStreams - 1] = folder.getUnpackSize() - sum;
            folder.subStreamSizes = sizes;
        }
        if (id == K_SIZE) id = reader.readByte();
        while (id != K_END) {
            if (id == K_CRC) {
                int numDigests = 0;
                for (Folder folder : info.folders) numDigests += folder.numUnpackSubStreams;
                skipDigests(reader, numDigests);
            } else {
                reader.skip(reader.readNumber());
            }
            id = reader.readByte();
        }
    }

    private static void assignPackStreamsToFolders(@NonNull StreamsInfo info) {
        int packIndex = 0;
        for (Folder folder : info.folders) {
            folder.firstPackStreamIndex = packIndex;
            folder.numPackStreams = folder.packedInputIndices.length;
            packIndex += folder.numPackStreams;
        }
    }

    private static void readFilesInfo(@NonNull ByteReader reader,
                                      @NonNull SevenZArchive result,
                                      @Nullable StreamsInfo streams) throws IOException {
        long numFiles = reader.readNumber();
        String[] names = new String[(int) numFiles];
        BitSet emptyStream = new BitSet((int) numFiles);
        BitSet emptyFile = new BitSet();
        int numEmptyStreams = 0;

        while (true) {
            int propType = reader.readByte();
            if (propType == K_END) break;
            long size = reader.readNumber();
            long endPos = reader.position() + size;
            switch (propType) {
                case K_EMPTY_STREAM:
                    emptyStream = readBitVector(reader, (int) numFiles);
                    numEmptyStreams = emptyStream.cardinality();
                    break;
                case K_EMPTY_FILE:
                    emptyFile = readBitVector(reader, numEmptyStreams);
                    break;
                case K_NAME: {
                    int external = reader.readByte();
                    if (external != 0) throw new IOException("7z external names unsupported");
                    for (int i = 0; i < numFiles; i++) {
                        names[i] = readUtf16Name(reader);
                    }
                    break;
                }
                default:
                    break;
            }
            reader.seek(endPos);
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
                offsetInFolder += entry.size;
                subInFolder++;
            }
            files.add(entry);
        }
        result.files = files;
    }

    // ----- Small helpers -----

    private static void skipDigests(@NonNull ByteReader reader, int count) throws IOException {
        int allDefined = reader.readByte();
        int defined = count;
        if (allDefined == 0) {
            BitSet set = readBitVector(reader, count);
            defined = set.cardinality();
        }
        reader.skip((long) defined * 4);
    }

    @NonNull
    private static BitSet readBitVector(@NonNull ByteReader reader, int count) throws IOException {
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
        byte[] out = new byte[(int) size];
        int done = 0;
        while (done < out.length) {
            int n = in.read(out, done, out.length - done);
            if (n < 0) throw new EOFException("7z stream ended early");
            done += n;
        }
        return out;
    }

    private static void writeSlice(@NonNull byte[] data, long offset, long size, @NonNull File outFile) throws IOException {
        if (offset < 0 || size < 0 || offset + size > data.length) {
            throw new IOException("7z entry slice out of range");
        }
        boolean ok = false;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            out.write(data, (int) offset, (int) size);
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

    @NonNull
    private static String hex(@NonNull byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X", b & 0xff));
        return sb.toString();
    }

    private static long readUInt64LE(@NonNull RandomAccessFile raf) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            int b = raf.read();
            if (b < 0) throw new EOFException("7z header truncated");
            value |= (long) b << (8 * i);
        }
        return value;
    }

    // ----- Model classes -----

    private static final class SevenZArchive {
        long packPos;
        long[] packSizes = new long[0];
        List<Folder> folders = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
    }

    private static final class StreamsInfo {
        long packPos;
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
        Coder[] coders = new Coder[0];
        int[] coderInputBase = new int[0];
        int[] coderOutputBase = new int[0];
        int totalInputStreams;
        int totalOutputStreams;
        int[] bindPairInIndex = new int[0];
        int[] bindPairOutIndex = new int[0];
        int[] packedInputIndices = new int[0];
        long[] coderUnpackSizes = new long[0];
        int firstPackStreamIndex;
        int numPackStreams;
        int numUnpackSubStreams = 1;
        long[] subStreamSizes = new long[0];

        boolean usesBcj2() {
            for (Coder coder : coders) {
                if (matchesId(coder.id, ID_BCJ2)) return true;
            }
            return false;
        }

        boolean usesPpmd() {
            for (Coder coder : coders) {
                if (matchesId(coder.id, ID_PPMD)) return true;
            }
            return false;
        }

        /** The coder output not consumed by any bind pair is the folder output. */
        int findFinalOutputCoder() throws IOException {
            for (int c = 0; c < coders.length; c++) {
                int outIndex = coderOutputBase[c];
                boolean bound = false;
                for (int bp : bindPairOutIndex) {
                    if (bp == outIndex) {
                        bound = true;
                        break;
                    }
                }
                if (!bound) return c;
            }
            throw new IOException("7z folder has no final output coder");
        }

        long getUnpackSize() {
            if (coderUnpackSizes.length == 0) return 0;
            try {
                return coderUnpackSizes[coderOutputBase[findFinalOutputCoder()]];
            } catch (IOException e) {
                return coderUnpackSizes[coderUnpackSizes.length - 1];
            }
        }

        /** Maps a global input index to the coder whose output is bound to it. */
        int boundInputToCoder(int globalInput) {
            int coder = boundInputToCoderRaw(globalInput);
            return coder;
        }

        int boundInputToCoderRaw(int globalInput) {
            for (int i = 0; i < bindPairInIndex.length; i++) {
                if (bindPairInIndex[i] == globalInput) {
                    int outIndex = bindPairOutIndex[i];
                    for (int c = 0; c < coders.length; c++) {
                        if (coderOutputBase[c] == outIndex) return c;
                    }
                }
            }
            return -1;
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
        private int pos;

        ByteReader(@NonNull byte[] data) {
            this.data = data;
        }

        int position() {
            return pos;
        }

        void seek(long absolute) throws IOException {
            if (absolute < 0 || absolute > data.length) throw new IOException("7z header seek out of range");
            pos = (int) absolute;
        }

        int readByte() throws IOException {
            if (pos >= data.length) throw new EOFException("7z header underrun");
            return data[pos++] & 0xff;
        }

        @NonNull
        byte[] readBytes(int count) throws IOException {
            if (count < 0 || pos + count > data.length) throw new EOFException("7z header underrun");
            byte[] out = Arrays.copyOfRange(data, pos, pos + count);
            pos += count;
            return out;
        }

        void skip(long count) throws IOException {
            if (count < 0 || pos + count > data.length) throw new EOFException("7z header underrun");
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
