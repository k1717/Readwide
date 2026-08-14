package com.readwide.codecs;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Streaming decoders for the WinZip ZIPX JPEG (96) and WavPack (97) methods. */
public final class ZipxNativeCodecs {
    public static final int METHOD_JPEG = 96;
    public static final int METHOD_WAVPACK = 97;

    private static volatile boolean loaded;

    private ZipxNativeCodecs() {}

    public static boolean supports(int method) {
        return method == METHOD_JPEG || method == METHOD_WAVPACK;
    }

    /**
     * Decodes one method payload without owning either stream.
     *
     * @return the exact number of uncompressed bytes written
     */
    public static long decode(int method,
                              @NonNull InputStream input,
                              @NonNull OutputStream output,
                              long maxOutputBytes) throws IOException {
        if (!supports(method)) throw new IOException("Unsupported native ZIPX method: " + method);
        if (maxOutputBytes < 0) throw new IOException("Invalid ZIPX output limit");
        ensureLoaded();
        return nativeDecode(method, input, output, maxOutputBytes);
    }

    private static synchronized void ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("readwide-zipx-codecs");
            loaded = true;
        }
    }

    private static native long nativeDecode(int method,
                                            InputStream input,
                                            OutputStream output,
                                            long maxOutputBytes) throws IOException;
}
