package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.tukaani.xz.*;

/** One immutable capability table for supplemental 7z dispatch, arity and property preparation. */
final class SevenZCoderRegistry {
    private SevenZCoderRegistry() {}

    private enum Kind { CORE, ADDITIONAL, MODERN_BCJ }
    interface Decoder { InputStream open(InputStream[] inputs, char[] password) throws IOException; }
    private interface Preparer { Decoder prepare(byte[] properties, long outputSize) throws IOException; }
    private interface UnaryDecoder { InputStream open(InputStream input) throws IOException; }
    private interface BcjOptions { FilterOptions create(int start) throws IOException; }

    static final class Entry {
        final String id, name;
        final int inputCount;
        final boolean supplemental;
        private final Kind kind;
        private final Preparer preparer;

        private Entry(String id, String name, int inputCount, boolean supplemental, Kind kind, Preparer preparer) {
            this.id = id; this.name = name; this.inputCount = inputCount;
            this.supplemental = supplemental; this.kind = kind; this.preparer = preparer;
        }
        boolean isAdditional() { return kind != Kind.CORE; }
        boolean isModernBcj() { return kind == Kind.MODERN_BCJ; }

        Prepared prepare(byte[] properties, long outputSize) throws IOException {
            if (outputSize < 0) throw new IOException("Invalid 7z coder output size");
            // No caller-owned property array or password is retained in an execution plan.
            byte[] copy = properties == null ? new byte[0] : properties.clone();
            return new Prepared(this, outputSize, preparer.prepare(copy, outputSize));
        }
    }

    static final class Prepared {
        final Entry entry;
        final long outputSize;
        private final Decoder decoder;
        private Prepared(Entry entry, long outputSize, Decoder decoder) {
            this.entry = entry; this.outputSize = outputSize; this.decoder = decoder;
        }
        InputStream open(InputStream[] inputs, char[] password) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("7z extraction cancelled");
            if (inputs == null || inputs.length != entry.inputCount) throw new IOException("Invalid 7z coder inputs");
            for (InputStream input : inputs) if (input == null) throw new IOException("Unbound 7z coder input");
            return decoder.open(inputs, password);
        }
    }

    private static final Map<String, Entry> ENTRIES = createEntries();

    static Entry find(byte[] id) { return ENTRIES.get(hex(id)); }
    static Collection<Entry> entries() { return ENTRIES.values(); }
    static boolean requiresSupplemental(byte[] id) {
        Entry entry = find(id);
        return entry != null && entry.supplemental;
    }
    static Entry require(byte[] id) throws IOException {
        Entry entry = find(id);
        if (entry == null) throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported 7z coder " + hex(id));
        return entry;
    }

    private static Map<String, Entry> createEntries() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        add(entries, "00", "Copy", 1, false, Kind.CORE, (props, size) -> {
            requireLength(props, 0, "Copy");
            return (inputs, password) -> inputs[0];
        });
        add(entries, "020302", "Swap2", 1, true, Kind.ADDITIONAL,
                noProperties("Swap2", input -> new SevenZSwapInputStream(input, 2)));
        add(entries, "020304", "Swap4", 1, true, Kind.ADDITIONAL,
                noProperties("Swap4", input -> new SevenZSwapInputStream(input, 4)));
        add(entries, "030101", "LZMA", 1, false, Kind.CORE, (props, size) -> {
            requireLength(props, 5, "LZMA");
            long declared = (props[1] & 255L) | ((props[2] & 255L) << 8)
                    | ((props[3] & 255L) << 16) | ((props[4] & 255L) << 24);
            int dictionary = boundedDictionary(declared, size, LZMAInputStream.DICT_SIZE_MAX);
            LZMAInputStream.getMemoryUsage(dictionary, props[0]); // Validate lc/lp/pb without opening input.
            return (inputs, password) -> new LZMAInputStream(new java.io.BufferedInputStream(inputs[0], 64 * 1024), size, props[0], dictionary);
        });
        add(entries, "21", "LZMA2", 1, false, Kind.CORE, (props, size) -> {
            requireLength(props, 1, "LZMA2");
            int property = props[0] & 255;
            if (property > 40) throw new IOException("Invalid 7z LZMA2 dictionary property");
            long declared = property == 40 ? 0xffffffffL : (2L | (property & 1)) << (property / 2 + 11);
            int dictionary = boundedDictionary(declared, size, LZMA2InputStream.DICT_SIZE_MAX);
            return (inputs, password) -> new LZMA2InputStream(inputs[0], dictionary);
        });
        add(entries, "06F10701", "AES", 1, false, Kind.CORE, (props, size) -> {
            SevenZAesDecoder.validateProperties(props);
            return (inputs, password) -> {
                if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
                return SevenZAesDecoder.decodeStream(inputs[0], props, password, size);
            };
        });
        add(entries, "0303011B", "BCJ2", 4, true, Kind.CORE, (props, size) -> {
            requireLength(props, 0, "BCJ2");
            return (inputs, password) -> SevenZBcj2Decoder.decodeStream(inputs[0], inputs[1], inputs[2], inputs[3], size);
        });
        add(entries, "030401", "PPMd", 1, true, Kind.CORE, (props, size) -> {
            SevenZPpmd7Decoder.validateProperties(props);
            return (inputs, password) -> SevenZPpmd7Decoder.decodeStream(inputs[0], props, size);
        });
        add(entries, "040108", "Deflate", 1, false, Kind.ADDITIONAL,
                noProperties("Deflate", OwnedDeflateInputStream::new));
        add(entries, "040109", "Deflate64", 1, false, Kind.ADDITIONAL, noProperties("Deflate64", Deflate64CompressorInputStream::new));
        add(entries, "040202", "BZip2", 1, false, Kind.ADDITIONAL, noProperties("BZip2", BZip2CompressorInputStream::new));
        add(entries, "03", "Delta", 1, false, Kind.ADDITIONAL, (props, size) -> {
            requireLength(props, 1, "Delta");
            DeltaOptions options = new DeltaOptions((props[0] & 255) + 1);
            return (inputs, password) -> options.getInputStream(inputs[0]);
        });
        bcj(entries, "03030103", "x86", false, start -> { X86Options o = new X86Options(); o.setStartOffset(start); return o; });
        bcj(entries, "03030205", "PowerPC", false, start -> { PowerPCOptions o = new PowerPCOptions(); o.setStartOffset(start); return o; });
        bcj(entries, "03030401", "IA64", false, start -> { IA64Options o = new IA64Options(); o.setStartOffset(start); return o; });
        bcj(entries, "03030501", "ARM", false, start -> { ARMOptions o = new ARMOptions(); o.setStartOffset(start); return o; });
        bcj(entries, "03030701", "ARM Thumb", false, start -> { ARMThumbOptions o = new ARMThumbOptions(); o.setStartOffset(start); return o; });
        bcj(entries, "03030805", "SPARC", false, start -> { SPARCOptions o = new SPARCOptions(); o.setStartOffset(start); return o; });
        bcj(entries, "0A", "ARM64", true, start -> { ARM64Options o = new ARM64Options(); o.setStartOffset(start); return o; });
        bcj(entries, "0B", "RISC-V", true, start -> { RISCVOptions o = new RISCVOptions(); o.setStartOffset(start); return o; });
        return Collections.unmodifiableMap(entries);
    }

    /** Independent coder streams have no preset history: output bounds the useful dictionary. */
    static int boundedDictionary(long declared, long outputSize, int implementationMaximum) throws IOException {
        if (declared < 0 || outputSize < 0) throw new IOException("Invalid 7z dictionary size");
        long effective = Math.max(4096L, Math.min(declared, outputSize));
        if (effective > implementationMaximum) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("7z dictionary exceeds decoder implementation range");
        }
        return (int) effective;
    }

    private static Preparer noProperties(String name, UnaryDecoder decoder) {
        return (props, size) -> {
            requireLength(props, 0, name);
            return (inputs, password) -> decoder.open(inputs[0]);
        };
    }
    private static void bcj(Map<String, Entry> entries, String id, String name, boolean modern, BcjOptions factory) {
        add(entries, id, name, 1, modern, modern ? Kind.MODERN_BCJ : Kind.ADDITIONAL, (props, size) -> {
            if (props.length != 0 && props.length != 4) throw new IOException("Invalid 7z BCJ properties");
            int start = props.length == 0 ? 0 : (props[0] & 255) | ((props[1] & 255) << 8)
                    | ((props[2] & 255) << 16) | ((props[3] & 255) << 24);
            FilterOptions options = factory.create(start);
            return (inputs, password) -> options.getInputStream(inputs[0]);
        });
    }
    private static void add(Map<String, Entry> entries, String id, String name, int inputs,
                            boolean supplemental, Kind kind, Preparer preparer) {
        if (entries.put(id, new Entry(id, name, inputs, supplemental, kind, preparer)) != null) {
            throw new IllegalStateException("Duplicate 7z coder " + id);
        }
    }
    private static void requireLength(byte[] properties, int expected, String name) throws IOException {
        if (properties.length != expected) throw new IOException("Invalid 7z " + name + " properties");
    }
    private static String hex(byte[] id) {
        if (id == null || id.length == 0 || id.length > 8) return "(invalid method ID)";
        final char[] digits = "0123456789ABCDEF".toCharArray();
        char[] result = new char[id.length * 2];
        for (int i = 0; i < id.length; i++) {
            result[i * 2] = digits[(id[i] & 255) >>> 4]; result[i * 2 + 1] = digits[id[i] & 15];
        }
        return new String(result);
    }
}
