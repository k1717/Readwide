package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.tukaani.xz.*;

/** One immutable capability table for supplemental 7z dispatch, arity and property preparation. */
final class SevenZCoderRegistry {
    private SevenZCoderRegistry() {}

    private enum Kind { CORE, ADDITIONAL, MODERN_BCJ }
    interface Decoder {
        InputStream open(InputStream[] inputs, char[] password) throws IOException;
        default InputStream open(InputStream[] inputs, char[] password, ArrayCache arrays) throws IOException {
            return open(inputs, password);
        }
    }
    private interface CachedDecoder extends Decoder {
        @Override InputStream open(InputStream[] inputs, char[] password, ArrayCache arrays) throws IOException;
        @Override default InputStream open(InputStream[] inputs, char[] password) throws IOException {
            return open(inputs, password, ArrayCache.getDummyCache());
        }
    }
    private interface Preparer { Decoder prepare(byte[] properties, long outputSize) throws IOException; }
    private interface UnaryDecoder { InputStream open(InputStream input) throws IOException; }
    private interface BcjOptions { FilterOptions create(int start) throws IOException; }

    static final class Entry {
        final String id, name;
        final int inputCount;
        final boolean supplemental;
        final boolean dedicatedForward;
        private final Kind kind;
        private final Preparer preparer;

        private Entry(String id, String name, int inputCount, boolean supplemental, Kind kind,
                      Preparer preparer, boolean dedicatedForward) {
            this.id = id; this.name = name; this.inputCount = inputCount;
            this.supplemental = supplemental; this.kind = kind; this.preparer = preparer;
            this.dedicatedForward = dedicatedForward;
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
            return open(inputs, password, ArrayCache.getDummyCache());
        }
        InputStream open(InputStream[] inputs, char[] password, ArrayCache arrays) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("7z extraction cancelled");
            if (arrays == null) throw new NullPointerException("arrays");
            if (inputs == null || inputs.length != entry.inputCount) throw new IOException("Invalid 7z coder inputs");
            for (InputStream input : inputs) if (input == null) throw new IOException("Unbound 7z coder input");
            return decoder.open(inputs, password, arrays);
        }
    }

    private static final Map<String, Entry> ENTRIES = createEntries();

    static Entry find(byte[] id) { return ENTRIES.get(hex(id)); }
    static Collection<Entry> entries() { return ENTRIES.values(); }
    static boolean requiresSupplemental(byte[] id) {
        Entry entry = find(id);
        return entry != null && entry.supplemental;
    }
    static boolean requiresDedicatedForward(byte[] id) {
        Entry entry = find(id);
        return entry != null && entry.dedicatedForward;
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
                noProperties("Swap2", input -> new SevenZSwapInputStream(input, 2)), true);
        add(entries, "020304", "Swap4", 1, true, Kind.ADDITIONAL,
                noProperties("Swap4", input -> new SevenZSwapInputStream(input, 4)), true);
        add(entries, "030101", "LZMA", 1, false, Kind.CORE, (props, size) -> {
            requireLength(props, 5, "LZMA");
            long declared = (props[1] & 255L) | ((props[2] & 255L) << 8)
                    | ((props[3] & 255L) << 16) | ((props[4] & 255L) << 24);
            int dictionary = boundedDictionary(declared, size, LZMAInputStream.DICT_SIZE_MAX);
            LZMAInputStream.getMemoryUsage(dictionary, props[0]); // Validate lc/lp/pb without opening input.
            return (CachedDecoder) (inputs, password, arrays) -> openXzPayload(inputs[0], "LZMA", input -> {
                LZMAInputStream decoded = new LZMAInputStream(
                        new java.io.BufferedInputStream(input, 64 * 1024), size, props[0], dictionary, null, arrays);
                // Old 7z producers can include an EOS marker despite a known size.
                // XZ >= 1.10 validates the actual marker; size and folder CRC checks remain in place.
                decoded.enableRelaxedEndCondition();
                return decoded;
            });
        });
        add(entries, "21", "LZMA2", 1, false, Kind.CORE, (props, size) -> {
            requireLength(props, 1, "LZMA2");
            int property = props[0] & 255;
            if (property > 40) throw new IOException("Invalid 7z LZMA2 dictionary property");
            long declared = property == 40 ? 0xffffffffL : (2L | (property & 1)) << (property / 2 + 11);
            int dictionary = boundedDictionary(declared, size, LZMA2InputStream.DICT_SIZE_MAX);
            // XZ's cache-taking LZMA2 constructor is package-private. Its public
            // options factory retains our per-reader cache for ordinary dictionaries.
            if (dictionary <= LZMA2Options.DICT_SIZE_MAX) {
                LZMA2Options options = new LZMA2Options();
                options.setDictSize(dictionary);
                return (CachedDecoder) (inputs, password, arrays) -> openXzPayload(
                        inputs[0], "LZMA2", input -> options.getInputStream(input, arrays));
            }
            // Options has an encoder-sized ceiling (768 MiB), not a decoding limit.
            // Keep the larger raw-decoder range through its public constructor.
            return (inputs, password) -> openXzPayload(
                    inputs[0], "LZMA2", input -> new LZMA2InputStream(input, dictionary));
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
        // Folder input is bounded. Decode every stream, not just a valid prefix.
        add(entries, "040202", "BZip2", 1, true, Kind.ADDITIONAL,
                noProperties("BZip2", SevenZCoderRegistry::openBzip2), true);
        add(entries, "04F71001", "LZHAM", 1, true, Kind.ADDITIONAL, (props, size) -> {
            // Original LzhamRegister CProps: version, dictionary exponent,
            // encoder level/flags and reserved byte. Upstream locks the v1.x
            // bitstream; verified 1.0/1.1 use the same default table profile.
            // Do not extend this to alpha/future versions or custom profiles.
            requireLength(props, 5, "LZHAM");
            int version = props[0] & 255;
            if (version != 0x10 && version != 0x11) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported 7z LZHAM property version");
            }
            int dictionaryBits = props[1] == 0 ? 26 : props[1] & 255;
            if (dictionaryBits < 15 || dictionaryBits > 29) throw new IOException("Invalid 7z LZHAM dictionary exponent");
            return (inputs, password) -> {
                try {
                    return terminalPayload(com.readwide.codecs.LzhamInputStream.open(inputs[0], dictionaryBits, size), "LZHAM");
                } catch (IOException failure) { throw payloadFailure(failure, "LZHAM"); }
            };
        }, true);
        add(entries, "04F71101", "Zstandard", 1, true, Kind.ADDITIONAL, (props, size) -> {
            // 7-Zip-zstd: current one-byte flags, or legacy 3/5-byte version/level
            // metadata. The legacy fields are descriptive, not decoder settings.
            if (props.length != 1 && props.length != 3 && props.length != 5) {
                throw new IOException("Invalid 7z Zstandard properties");
            }
            if (props.length == 1 && props[0] != 0) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported 7z Zstandard flags");
            }
            return (inputs, password) -> LibarchiveZstdInputStream.open(inputs[0]);
        });
        add(entries, "04F71102", "Brotli", 1, true, Kind.ADDITIONAL, (props, size) -> {
            // 7-Zip-zstd producer version/level, not decoder memory settings.
            requireLength(props, 3, "Brotli");
            return (inputs, password) -> new SevenZBrotliInputStream(inputs[0], size);
        }, true);
        add(entries, "04F71104", "LZ4", 1, true, Kind.ADDITIONAL, (props, size) -> {
            // 7-Zip-zstd DProps: version major/minor, level, two reserved bytes.
            // These are producer metadata; LZ4 frame headers carry decoding options.
            requireLength(props, 5, "LZ4");
            return (inputs, password) -> terminalPayload(new Lz4ArchiveInputStream(inputs[0]), "LZ4");
        }, true);
        add(entries, "04F71105", "LZ5", 1, true, Kind.ADDITIONAL, (props, size) -> {
            requireLength(props, 5, "LZ5"); // Producer version/level and reserved bytes.
            return (inputs, password) -> com.readwide.codecs.LzFamilyInputStream.open(inputs[0], false, size);
        }, true);
        add(entries, "04F71106", "Lizard", 1, true, Kind.ADDITIONAL, (props, size) -> {
            requireLength(props, 3, "Lizard"); // Producer version/level.
            return (inputs, password) -> com.readwide.codecs.LzFamilyInputStream.open(inputs[0], true, size);
        }, true);
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

    /** Distinguish a decoder's malformed/truncated payload from an upstream I/O failure. */
    private static final class XzInput extends java.io.FilterInputStream {
        private IOException failure;
        XzInput(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            try { return in.read(); }
            catch (IOException error) { failure = error; throw error; }
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            try { return in.read(bytes, offset, length); }
            catch (IOException error) { failure = error; throw error; }
        }
        IOException classify(IOException error, String codec) throws IOException {
            ArchiveCancellation.rethrowIfCancelled(error, null);
            // XZ retains the original input exception. A real I/O error, including
            // an upstream EOFException, must not become a codec-integrity claim.
            if (error == failure || !(error instanceof CorruptedInputException
                    || error instanceof java.io.EOFException)) return error;
            return payloadFailure(error, codec);
        }
    }

    private static InputStream openXzPayload(InputStream input, String codec, UnaryDecoder decoder) throws IOException {
        XzInput source = new XzInput(input);
        final InputStream decoded;
        try { decoded = decoder.open(source); }
        catch (IOException error) { throw source.classify(error, codec); }
        try {
            return new java.io.FilterInputStream(decoded) {
                private IOException failure;
                @Override public int read() throws IOException {
                    if (failure != null) throw failure;
                    try { return in.read(); }
                    catch (IOException error) { failure = source.classify(error, codec); throw failure; }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (failure != null && length != 0) throw failure;
                    try { return in.read(bytes, offset, length); }
                    catch (IOException error) { failure = source.classify(error, codec); throw failure; }
                }
            };
        } catch (RuntimeException | Error failure) {
            closeUnadopted(decoded, failure);
            throw failure;
        }
    }

    private static InputStream openBzip2(InputStream input) throws IOException {
        try {
            return terminalPayload(new ArchiveBzip2InputStream(input, null), "BZip2");
        } catch (IOException failure) { throw payloadFailure(failure, "BZip2"); }
    }

    private static InputStream terminalPayload(InputStream input, String codec) {
        try {
            return new java.io.FilterInputStream(input) {
                @Override public int read() throws IOException {
                    try { return in.read(); }
                    catch (IOException failure) { throw payloadFailure(failure, codec); }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    try { return in.read(bytes, offset, length); }
                    catch (IOException failure) { throw payloadFailure(failure, codec); }
                }
            };
        } catch (RuntimeException | Error failure) {
            closeUnadopted(input, failure);
            throw failure;
        }
    }

    /** A successfully opened decoder may own native resources before wrapper allocation. */
    private static void closeUnadopted(InputStream input, Throwable failure) {
        try { input.close(); }
        catch (IOException | RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    private static IOException payloadFailure(IOException failure, String codec) throws IOException {
        ArchiveCancellation.rethrowIfCancelled(failure, null);
        if (failure instanceof SevenZBcj2ArchiveReader.IntegrityException) return failure;
        // Retrying a single-stream backend can accept a valid prefix and ignore
        // a corrupt later stream, even when that prefix has the expected CRC.
        IOException terminal = new SevenZBcj2ArchiveReader.IntegrityException("7z " + codec + " payload could not be validated");
        terminal.initCause(failure);
        return terminal;
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
        add(entries, id, name, inputs, supplemental, kind, preparer, false);
    }
    private static void add(Map<String, Entry> entries, String id, String name, int inputs,
                            boolean supplemental, Kind kind, Preparer preparer, boolean dedicatedForward) {
        if (dedicatedForward && !supplemental) throw new IllegalArgumentException("Dedicated forward coder must be supplemental");
        if (entries.put(id, new Entry(id, name, inputs, supplemental, kind, preparer, dedicatedForward)) != null) {
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
