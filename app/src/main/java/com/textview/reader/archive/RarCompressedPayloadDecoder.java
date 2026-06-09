package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;

final class RarCompressedPayloadDecoder {
    private static final int RAR3_METHOD_FASTEST = 0x31;
    private static final int RAR3_METHOD_BEST = 0x35;

    private RarCompressedPayloadDecoder() {}

    static boolean isRar3Or4CompressionMethod(int method) {
        return method >= RAR3_METHOD_FASTEST && method <= RAR3_METHOD_BEST;
    }

    static void extractRar3Or4(@NonNull File archive,
                               long dataOffset,
                               long packedSize,
                               long unpackedSize,
                               int method,
                               boolean solid,
                               boolean splitBefore,
                               boolean splitAfter,
                               boolean encrypted,
                               long expectedCrc,
                               @NonNull File outFile,
                               @Nullable FileOperationProgress progress) throws IOException {
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                archive,
                dataOffset,
                packedSize,
                unpackedSize,
                method,
                solid,
                splitBefore,
                splitAfter,
                encrypted,
                expectedCrc);
        Rar3Unpacker.unpack(context, outFile, progress);
    }
}
