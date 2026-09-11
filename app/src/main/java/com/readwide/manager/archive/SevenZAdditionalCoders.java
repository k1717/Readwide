package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorInputStream;
import org.apache.commons.compress.compressors.deflate.DeflateParameters;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.tukaani.xz.*;

/** Existing FOSS codecs usable inside a BCJ2/PPMd graph, not just standalone 7z folders. */
final class SevenZAdditionalCoders {
    private SevenZAdditionalCoders() {}

    /** Null means unknown method. Recognised methods with invalid properties fail explicitly. */
    static InputStream open(byte[] id, byte[] properties, InputStream input) throws IOException {
        int length = properties == null ? 0 : properties.length;
        if (is(id, 4, 1, 8) || is(id, 4, 1, 9) || is(id, 4, 2, 2)) {
            if (length != 0) throw new IOException("Unexpected 7z compression properties");
            if (is(id, 4, 2, 2)) return new BZip2CompressorInputStream(input);
            if (is(id, 4, 1, 9)) return new Deflate64CompressorInputStream(input);
            DeflateParameters options = new DeflateParameters();
            options.setWithZlibHeader(false);
            return new DeflateCompressorInputStream(input, options);
        }
        if (is(id, 3)) {
            if (length != 1) throw new IOException("Invalid 7z Delta properties");
            return new DeltaOptions((properties[0] & 255) + 1).getInputStream(input);
        }
        int kind = is(id, 3, 3, 1, 3) ? 1 : is(id, 3, 3, 2, 5) ? 2
                : is(id, 3, 3, 4, 1) ? 3 : is(id, 3, 3, 5, 1) ? 4
                : is(id, 3, 3, 7, 1) ? 5 : is(id, 3, 3, 8, 5) ? 6 : 0;
        if (kind == 0) return null;
        if (length != 0 && length != 4) throw new IOException("Invalid 7z BCJ properties");
        int start = length == 0 ? 0 : (properties[0] & 255) | ((properties[1] & 255) << 8)
                | ((properties[2] & 255) << 16) | ((properties[3] & 255) << 24);
        switch (kind) {
            case 1: { X86Options o = new X86Options(); o.setStartOffset(start); return o.getInputStream(input); }
            case 2: { PowerPCOptions o = new PowerPCOptions(); o.setStartOffset(start); return o.getInputStream(input); }
            case 3: { IA64Options o = new IA64Options(); o.setStartOffset(start); return o.getInputStream(input); }
            case 4: { ARMOptions o = new ARMOptions(); o.setStartOffset(start); return o.getInputStream(input); }
            case 5: { ARMThumbOptions o = new ARMThumbOptions(); o.setStartOffset(start); return o.getInputStream(input); }
            default: { SPARCOptions o = new SPARCOptions(); o.setStartOffset(start); return o.getInputStream(input); }
        }
    }

    private static boolean is(byte[] id, int... expected) {
        if (id == null || id.length != expected.length) return false;
        for (int i = 0; i < id.length; i++) if ((id[i] & 255) != expected[i]) return false;
        return true;
    }
}
