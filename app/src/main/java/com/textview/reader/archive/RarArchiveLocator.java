package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates RAR signatures and resolves multi-volume chains.
 *
 * <p>This stays separate from {@link RarArchiveReader} so decoder work can focus on
 * header parsing and payload extraction instead of archive-name/SFX plumbing.</p>
 */
final class RarArchiveLocator {
    private static final byte[] RAR5_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00
    };
    private static final byte[] RAR4_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
    };

    private static final int MAX_SFX_SCAN = 1024 * 1024;
    private RarArchiveLocator() {}

    static int signatureLength(int version) {
        return version == 5 ? RAR5_SIGNATURE.length : RAR4_SIGNATURE.length;
    }

    static int detectRarVersion(@NonNull File archive) throws IOException {
        List<File> volumes = collectVolumes(archive);
        for (File volume : volumes) {
            try (RandomAccessFile raf = new RandomAccessFile(volume, "r")) {
                Signature signature = findSignature(raf);
                if (signature != null) return signature.version;
            }
        }
        return -1;
    }

    static long findEmbeddedRarSignatureOffset(@NonNull File archive) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            Signature signature = findSignature(raf);
            return signature == null ? -1L : signature.offset;
        }
    }

    @Nullable
    static Signature findSignature(@NonNull RandomAccessFile raf) throws IOException {
        raf.seek(0L);
        int scanLimit = (int) Math.min(Math.max(raf.length(), 0L), MAX_SFX_SCAN + RAR5_SIGNATURE.length);
        byte[] data = new byte[scanLimit];
        raf.readFully(data);
        int rar5 = indexOf(data, RAR5_SIGNATURE);
        int rar4 = indexOf(data, RAR4_SIGNATURE);
        if (rar5 < 0 && rar4 < 0) return null;
        if (rar5 >= 0 && (rar4 < 0 || rar5 <= rar4)) return new Signature(rar5, 5);
        return new Signature(rar4, 4);
    }

    @NonNull
    static List<File> collectVolumes(@NonNull File archive) {
        return new ArrayList<>(resolveVolumeChain(archive).volumes());
    }

    @NonNull
    static List<File> collectReadableVolumes(@NonNull File archive) throws IOException {
        return resolveVolumeChain(archive).requireReadableChain();
    }

    @NonNull
    static RarVolumeChainResolution resolveVolumeChain(@NonNull File archive) {
        return RarVolumeChainResolution.resolve(archive);
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

    static final class Signature {
        final long offset;
        final int version;

        Signature(long offset, int version) {
            this.offset = offset;
            this.version = version;
        }
    }
}
