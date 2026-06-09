package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Mutable state holder for the first-party RAR3/RAR4 compressed unpacker.
 *
 * <p>The current app still keeps libarchive as the primary RAR compressed backend. This context
 * intentionally starts small so the future LZ/Huffman decoder and solid dictionary continuation
 * can be added without growing {@link RarArchiveReader} again.</p>
 */
final class Rar3UnpackContext {
    private static final int DEFAULT_WINDOW_SIZE = 4 * 1024 * 1024;

    @NonNull final File archive;
    final long dataOffset;
    final long packedSize;
    final long unpackedSize;
    final int method;
    final boolean solid;
    final boolean splitBefore;
    final boolean splitAfter;
    final boolean encrypted;
    private final long expectedCrc;
    private final Rar3SolidState solidState;

    private final int[] oldTableLengths = new int[Rar3HuffmanTables.TABLE_SIZE];
    private final Rar3UnpackState state = new Rar3UnpackState();
    private final Rar3PpmdState ppmdState = new Rar3PpmdState();
    private byte[] window;
    private int writePosition;

    private Rar3UnpackContext(@NonNull File archive,
                              long dataOffset,
                              long packedSize,
                              long unpackedSize,
                              int method,
                              boolean solid,
                              boolean splitBefore,
                              boolean splitAfter,
                              boolean encrypted,
                              long expectedCrc,
                              Rar3SolidState solidState) {
        this.archive = archive;
        this.dataOffset = dataOffset;
        this.packedSize = packedSize;
        this.unpackedSize = unpackedSize;
        this.method = method;
        this.solid = solid;
        this.splitBefore = splitBefore;
        this.splitAfter = splitAfter;
        this.encrypted = encrypted;
        this.expectedCrc = expectedCrc;
        this.solidState = solidState;
    }

    @NonNull
    static Rar3UnpackContext forEntry(@NonNull File archive,
                                      long dataOffset,
                                      long packedSize,
                                      long unpackedSize,
                                      int method,
                                      boolean solid,
                                      boolean splitBefore,
                                      boolean splitAfter,
                                      boolean encrypted) throws IOException {
        return forEntry(archive, dataOffset, packedSize, unpackedSize, method, solid,
                splitBefore, splitAfter, encrypted, -1L);
    }

    @NonNull
    static Rar3UnpackContext forEntry(@NonNull File archive,
                                      long dataOffset,
                                      long packedSize,
                                      long unpackedSize,
                                      int method,
                                      boolean solid,
                                      boolean splitBefore,
                                      boolean splitAfter,
                                      boolean encrypted,
                                      long expectedCrc) throws IOException {
        if (encrypted) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 encrypted compressed payloads are handled by the visible-header rewrite path when possible; remaining cases require the unfinished first-party unpacker");
        }
        if (solid) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 compressed solid payloads require dictionary continuation in the unfinished first-party solid-state unpacker");
        }
        if (splitBefore || splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 compressed split payloads are handled by the split rewrite path when possible; remaining cases require the unfinished first-party unpacker");
        }
        validateCompressedPayloadBounds(archive, dataOffset, packedSize, unpackedSize, method);
        return new Rar3UnpackContext(
                archive,
                dataOffset,
                packedSize,
                unpackedSize,
                method,
                false,
                false,
                false,
                false,
                expectedCrc,
                null);
    }

    @NonNull
    static Rar3UnpackContext forSolidEntry(@NonNull File archive,
                                           long dataOffset,
                                           long packedSize,
                                           long unpackedSize,
                                           int method,
                                           boolean splitBefore,
                                           boolean splitAfter,
                                           boolean encrypted,
                                           long expectedCrc,
                                           @NonNull Rar3SolidState solidState) throws IOException {
        if (encrypted) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 encrypted solid compressed payloads require the unfinished first-party solid-state unpacker");
        }
        if (splitBefore || splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 split solid compressed payloads require the unfinished first-party solid-state unpacker");
        }
        validateCompressedPayloadBounds(archive, dataOffset, packedSize, unpackedSize, method);
        return new Rar3UnpackContext(
                archive,
                dataOffset,
                packedSize,
                unpackedSize,
                method,
                true,
                false,
                false,
                false,
                expectedCrc,
                solidState);
    }

    @NonNull
    static Rar3UnpackContext forSolidSequenceEntry(@NonNull File archive,
                                                   long dataOffset,
                                                   long packedSize,
                                                   long unpackedSize,
                                                   int method,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   boolean encrypted,
                                                   long expectedCrc,
                                                   @NonNull Rar3SolidState solidState) throws IOException {
        if (encrypted) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 encrypted solid-sequence compressed payloads require the unfinished first-party solid-state unpacker");
        }
        if (splitBefore || splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 split solid-sequence compressed payloads require the unfinished first-party solid-state unpacker");
        }
        validateCompressedPayloadBounds(archive, dataOffset, packedSize, unpackedSize, method);
        return new Rar3UnpackContext(
                archive,
                dataOffset,
                packedSize,
                unpackedSize,
                method,
                true,
                false,
                false,
                false,
                expectedCrc,
                solidState);
    }

    private static void validateCompressedPayloadBounds(@NonNull File archive,
                                                        long dataOffset,
                                                        long packedSize,
                                                        long unpackedSize,
                                                        int method) throws IOException {
        if (!RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(method)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Unknown RAR3/RAR4 compression method");
        }
        if (packedSize < 0 || unpackedSize < 0 || dataOffset < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid RAR3/RAR4 compressed payload bounds");
        }
        long archiveLength = archive.length();
        if (archiveLength < dataOffset || archiveLength - dataOffset < packedSize) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3/RAR4 compressed payload is truncated");
        }
    }

    void resetWindow() {
        if (solidState != null) {
            if (!solidState.initialized()) solidState.updateWritePosition(0);
            return;
        }
        if (window == null) window = new byte[DEFAULT_WINDOW_SIZE];
        writePosition = 0;
        state.resetNonSolid();
        ppmdState.resetNonSolid();
    }

    @NonNull
    RarLzWindow openWindow(@NonNull java.io.OutputStream out) {
        return openWindow(RarOutputStreamDecodedOutput.wrapOrMemory(out));
    }

    @NonNull
    RarLzWindow openWindow(@NonNull RarDecodedOutput out) {
        if (solidState != null) {
            return new RarLzWindow(solidState.window(), solidState.writePosition(), out);
        }
        if (window == null) window = new byte[DEFAULT_WINDOW_SIZE];
        return new RarLzWindow(window, writePosition, out);
    }

    void saveWindow(@NonNull RarLzWindow lzWindow) {
        if (solidState != null) {
            solidState.updateWritePosition(lzWindow.position());
        } else {
            writePosition = lzWindow.position();
        }
    }

    int windowSize() {
        return solidState != null ? solidState.windowSize() : (window == null ? DEFAULT_WINDOW_SIZE : window.length);
    }

    int writePosition() {
        return solidState != null ? solidState.writePosition() : writePosition;
    }

    boolean hasExpectedCrc() {
        return expectedCrc >= 0;
    }

    long expectedCrc() {
        return expectedCrc & 0xffffffffL;
    }

    @NonNull
    byte[] readPackedPayload() throws IOException {
        if (packedSize > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 first-party compressed payload is too large for the current in-memory decoder scaffold");
        }
        byte[] packed = new byte[(int) packedSize];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(dataOffset);
            raf.readFully(packed);
        }
        return packed;
    }

    @NonNull
    int[] oldTableLengths() {
        return solidState != null ? solidState.oldTableLengths() : oldTableLengths;
    }

    @NonNull
    Rar3UnpackState state() {
        return solidState != null ? solidState.unpackState() : state;
    }

    @NonNull
    Rar3PpmdState ppmdState() {
        return solidState != null ? solidState.ppmdState() : ppmdState;
    }
}
