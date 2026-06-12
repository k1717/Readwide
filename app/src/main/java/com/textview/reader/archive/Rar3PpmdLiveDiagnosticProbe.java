package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Non-success diagnostic probe for real RAR3/RAR4 PPMd payloads.
 *
 * <p>This deliberately does not extract files and never marks an image/cache as valid. It only
 * feeds the visible PPMd payload bytes into the current first-party diagnostic model path and
 * records the first boundary reached. That makes the target solid-CBR fixture measurable without
 * hiding the remaining RAR PPMd-I gaps behind a false success path.</p>
 */
final class Rar3PpmdLiveDiagnosticProbe {
    private static final int DEFAULT_SYMBOL_LIMIT = 64;
    private static final int MAX_SYMBOL_LIMIT = 4096;

    private Rar3PpmdLiveDiagnosticProbe() {}

    @NonNull
    static List<Row> probeArchive(@NonNull File archive) throws IOException {
        return probeArchive(archive, DEFAULT_SYMBOL_LIMIT);
    }

    @NonNull
    static List<Row> probeArchive(@NonNull File archive, int symbolLimit) throws IOException {
        return probeArchiveWithOptions(archive, symbolLimit, RarPpmdDiagnosticOptions.standard());
    }

    @NonNull
    static List<VariantReport> probeArchiveVariants(@NonNull File archive, int symbolLimit)
            throws IOException {
        List<VariantReport> reports = new ArrayList<>();
        for (RarPpmdDiagnosticOptions options : RarPpmdDiagnosticOptions.comparisonSet()) {
            reports.add(new VariantReport(options.name(),
                    probeArchiveWithOptions(archive, symbolLimit, options)));
        }
        return reports;
    }

    @NonNull
    private static List<Row> probeArchiveWithOptions(@NonNull File archive,
                                                     int symbolLimit,
                                                     @NonNull RarPpmdDiagnosticOptions options)
            throws IOException {
        int safeLimit = normalizeLimit(symbolLimit);
        List<RarArchiveReader.RarEntry> entries =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
        List<Row> rows = new ArrayList<>();
        Rar3PpmdState ppmdState = new Rar3PpmdState();
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.directory) continue;
            byte[] packed = readPackedPayload(archive, entry);
            Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.fromPackedPayload(packed);
            Row row = probeEntry(entry, packed, header, ppmdState, safeLimit, options);
            rows.add(row);
            if (!header.isPpmd()) {
                ppmdState.resetNonSolid();
            }
        }
        return rows;
    }

    @NonNull
    static Row probePackedPayloadForTest(@NonNull String path,
                                         long packedSize,
                                         long unpackedSize,
                                         boolean solid,
                                         @NonNull byte[] packed,
                                         @NonNull Rar3PpmdState ppmdState,
                                         int symbolLimit) throws IOException {
        RarArchiveReader.RarEntry fake = new RarArchiveReader.RarEntry(
                path, false, unpackedSize, packedSize, 0, 4, 0x31, solid,
                false, false, null, 0, 0);
        return probeEntry(fake, packed, Rar3PpmdBlockHeader.fromPackedPayload(packed),
                ppmdState, normalizeLimit(symbolLimit), RarPpmdDiagnosticOptions.standard());
    }

    @NonNull
    static Row probePackedPayloadWithOptionsForTest(@NonNull String path,
                                                    long packedSize,
                                                    long unpackedSize,
                                                    boolean solid,
                                                    @NonNull byte[] packed,
                                                    @NonNull Rar3PpmdState ppmdState,
                                                    int symbolLimit,
                                                    @NonNull RarPpmdDiagnosticOptions options)
            throws IOException {
        RarArchiveReader.RarEntry fake = new RarArchiveReader.RarEntry(
                path, false, unpackedSize, packedSize, 0, 4, 0x31, solid,
                false, false, null, 0, 0);
        return probeEntry(fake, packed, Rar3PpmdBlockHeader.fromPackedPayload(packed),
                ppmdState, normalizeLimit(symbolLimit), options);
    }

    @NonNull
    private static Row probeEntry(@NonNull RarArchiveReader.RarEntry entry,
                                  @NonNull byte[] packed,
                                  @NonNull Rar3PpmdBlockHeader header,
                                  @NonNull Rar3PpmdState ppmdState,
                                  int symbolLimit,
                                  @NonNull RarPpmdDiagnosticOptions options) throws IOException {
        List<Integer> symbols = new ArrayList<>();
        List<TraceRow> traceRows = new ArrayList<>();
        String boundaryType = "none";
        String boundaryMessage = "symbol limit reached";
        boolean fatalStateBoundary = false;
        String modelDiagnostic = "not-started";

        if (!header.isPpmd()) {
            return new Row(options.name(), options.diagnostic(), entry.path, entry.packedSize,
                    entry.unpackedSize, entry.solid, header, 0, false, "non-ppmd",
                    header.diagnostic(), false, modelDiagnostic, symbols, traceRows);
        }
        if (packed.length < header.payloadOffset() + 4) {
            return new Row(options.name(), options.diagnostic(), entry.path, entry.packedSize,
                    entry.unpackedSize, entry.solid, header, 0, false, "too-small",
                    "PPMd payload is too small for range init", true, modelDiagnostic, symbols,
                    traceRows);
        }

        try {
            RarPpmdByteInput.ArrayInput input = new RarPpmdByteInput.ArrayInput(
                    packed, header.payloadOffset(), packed.length - header.payloadOffset());
            RarPpmdRangeDecoder rangeDecoder = new RarPpmdRangeDecoder(input);
            Rar3PpmdModelSymbolSource source =
                    Rar3PpmdModelSymbolSource.diagnosticWithOptionsForTest(
                            rangeDecoder, ppmdState, header, options);
            for (int i = 0; i < symbolLimit; i++) {
                TraceRow.Start start = TraceRow.captureStart(i, rangeDecoder, source);
                try {
                    int symbol = source.decodeSymbol();
                    if (symbol < 0 || symbol > 255) {
                        boundaryType = "invalid-symbol";
                        boundaryMessage = "decoded symbol is out of byte range: " + symbol;
                        fatalStateBoundary = true;
                        traceRows.add(TraceRow.boundary(start, rangeDecoder, source, boundaryType,
                                boundaryMessage));
                        break;
                    }
                    symbols.add(symbol);
                    traceRows.add(TraceRow.symbol(start, rangeDecoder, source, symbol));
                } catch (IOException e) {
                    boundaryType = (e instanceof RarArchiveReader.UnsupportedRarFeatureException)
                            ? "unsupported" : e.getClass().getSimpleName();
                    boundaryMessage = safeMessage(e);
                    fatalStateBoundary = true;
                    traceRows.add(TraceRow.boundary(start, rangeDecoder, source, boundaryType,
                            boundaryMessage));
                    break;
                }
            }
            modelDiagnostic = source.modelForTest().diagnostic();
        } catch (IOException e) {
            boundaryType = (e instanceof RarArchiveReader.UnsupportedRarFeatureException)
                    ? "unsupported" : e.getClass().getSimpleName();
            boundaryMessage = safeMessage(e);
            fatalStateBoundary = true;
            if (ppmdState.hasModel()) modelDiagnostic = ppmdState.requireModel().diagnostic();
        }

        return new Row(options.name(), options.diagnostic(), entry.path, entry.packedSize,
                entry.unpackedSize, entry.solid, header, symbols.size(),
                symbols.size() >= symbolLimit && !fatalStateBoundary, boundaryType,
                boundaryMessage, fatalStateBoundary, modelDiagnostic, symbols, traceRows);
    }

    private static int normalizeLimit(int symbolLimit) {
        if (symbolLimit <= 0) return DEFAULT_SYMBOL_LIMIT;
        return Math.min(symbolLimit, MAX_SYMBOL_LIMIT);
    }

    private static byte[] readPackedPayload(@NonNull File archive,
                                            @NonNull RarArchiveReader.RarEntry entry) throws IOException {
        if (entry.packedSize < 0 || entry.packedSize > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd live diagnostic payload is outside supported bounds: "
                            + entry.packedSize);
        }
        byte[] packed = new byte[(int) entry.packedSize];
        try (RandomAccessFile raf = new RandomAccessFile(
                entry.sourceArchive != null ? entry.sourceArchive : archive, "r")) {
            raf.seek(entry.dataOffset);
            raf.readFully(packed);
        }
        return packed;
    }

    @NonNull
    private static String safeMessage(@Nullable Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) return "";
        return throwable.getMessage();
    }

    static final class Row {
        @NonNull final String variantName;
        @NonNull final String variantDiagnostic;
        @NonNull final String path;
        final long packedSize;
        final long unpackedSize;
        final boolean solid;
        @NonNull final Rar3PpmdBlockHeader header;
        final int decodedSymbols;
        final boolean symbolLimitReached;
        @NonNull final String boundaryType;
        @NonNull final String boundaryMessage;
        final boolean fatalStateBoundary;
        @NonNull final String modelDiagnostic;
        @NonNull final List<Integer> firstSymbols;
        @NonNull final List<TraceRow> traceRows;

        Row(@NonNull String variantName,
            @NonNull String variantDiagnostic,
            @NonNull String path,
            long packedSize,
            long unpackedSize,
            boolean solid,
            @NonNull Rar3PpmdBlockHeader header,
            int decodedSymbols,
            boolean symbolLimitReached,
            @NonNull String boundaryType,
            @NonNull String boundaryMessage,
            boolean fatalStateBoundary,
            @NonNull String modelDiagnostic,
            @NonNull List<Integer> firstSymbols,
            @NonNull List<TraceRow> traceRows) {
            this.variantName = variantName;
            this.variantDiagnostic = variantDiagnostic;
            this.path = path;
            this.packedSize = packedSize;
            this.unpackedSize = unpackedSize;
            this.solid = solid;
            this.header = header;
            this.decodedSymbols = decodedSymbols;
            this.symbolLimitReached = symbolLimitReached;
            this.boundaryType = boundaryType;
            this.boundaryMessage = boundaryMessage;
            this.fatalStateBoundary = fatalStateBoundary;
            this.modelDiagnostic = modelDiagnostic;
            this.firstSymbols = new ArrayList<>(firstSymbols);
            this.traceRows = new ArrayList<>(traceRows);
        }

        @NonNull
        String firstSymbolsHex() {
            if (firstSymbols.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < firstSymbols.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format(Locale.US, "%02x", firstSymbols.get(i) & 0xff));
            }
            return sb.toString();
        }

        boolean reachedUnpackedSymbolTarget() {
            return unpackedSize >= 0 && decodedSymbols >= unpackedSize;
        }
        boolean matchesExpectedImageMagic() {
            String lower = path.toLowerCase(Locale.US);
            if (lower.endsWith(".png")) {
                return matchesMagic(0x89, 0x50, 0x4e, 0x47);
            }
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return matchesMagic(0xff, 0xd8);
            }
            if (lower.endsWith(".gif")) {
                return matchesMagic(0x47, 0x49, 0x46);
            }
            if (lower.endsWith(".webp")) {
                return matchesMagic(0x52, 0x49, 0x46, 0x46);
            }
            return false;
        }

        private boolean matchesMagic(int... expected) {
            if (firstSymbols.size() < expected.length) return false;
            for (int i = 0; i < expected.length; i++) {
                if ((firstSymbols.get(i) & 0xff) != (expected[i] & 0xff)) return false;
            }
            return true;
        }



        int expectedCommonImagePrefixMatchedBytes() {
            int[] expected = expectedCommonImagePrefix();
            int matched = 0;
            while (matched < expected.length && matched < firstSymbols.size()
                    && ((firstSymbols.get(matched) & 0xff) == (expected[matched] & 0xff))) {
                matched++;
            }
            return matched;
        }

        @NonNull
        String expectedCommonImagePrefixMismatchDiagnostic() {
            int[] expected = expectedCommonImagePrefix();
            if (expected.length == 0) return "not-image-or-unknown";
            int matched = expectedCommonImagePrefixMatchedBytes();
            if (matched >= expected.length) return "matched=" + matched + "/" + expected.length;
            String actual = matched < firstSymbols.size()
                    ? String.format(Locale.US, "%02x", firstSymbols.get(matched) & 0xff)
                    : "<missing>";
            return "matched=" + matched + "/" + expected.length
                    + "; mismatchIndex=" + matched
                    + "; expected=" + String.format(Locale.US, "%02x", expected[matched] & 0xff)
                    + "; actual=" + actual;
        }

        @NonNull
        private int[] expectedCommonImagePrefix() {
            String lower = path.toLowerCase(Locale.US);
            if (lower.endsWith(".png")) {
                return new int[] {
                        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52
                };
            }
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return new int[] { 0xff, 0xd8 };
            }
            if (lower.endsWith(".gif")) {
                return new int[] { 0x47, 0x49, 0x46 };
            }
            if (lower.endsWith(".webp")) {
                return new int[] { 0x52, 0x49, 0x46, 0x46 };
            }
            return new int[0];
        }

        @NonNull
        String unpackedProgressDiagnostic() {
            if (unpackedSize <= 0) return decodedSymbols + "/unknown";
            long percentTimesTen = Math.min(1000L, (decodedSymbols * 1000L) / unpackedSize);
            return decodedSymbols + "/" + unpackedSize
                    + " (" + (percentTimesTen / 10) + "." + (percentTimesTen % 10) + "%)";
        }

        @NonNull
        String diagnostic() {
            return "variant=" + variantName
                    + "; variantOptions={" + variantDiagnostic + "}"
                    + "; path=" + path
                    + "; packed=" + packedSize
                    + "; unpacked=" + unpackedSize
                    + "; solid=" + solid
                    + "; header={" + header.diagnostic() + "}"
                    + "; decodedSymbols=" + decodedSymbols
                    + "; unpackedProgress=" + unpackedProgressDiagnostic()
                    + "; reachedUnpackedTarget=" + reachedUnpackedSymbolTarget()
                    + "; matchesExpectedImageMagic=" + matchesExpectedImageMagic()
                    + "; expectedCommonPrefixMatched=" + expectedCommonImagePrefixMatchedBytes()
                    + "; expectedCommonPrefixMismatch={" + expectedCommonImagePrefixMismatchDiagnostic() + "}"
                    + "; limitReached=" + symbolLimitReached
                    + "; boundaryType=" + boundaryType
                    + "; fatalStateBoundary=" + fatalStateBoundary
                    + "; firstSymbolsHex=" + firstSymbolsHex()
                    + "; traceRows=" + traceRows.size()
                    + "; lastTrace={" + lastTraceDiagnostic() + "}"
                    + "; boundaryMessage=" + boundaryMessage
                    + "; model={" + modelDiagnostic + "}";
        }

        @NonNull
        String lastTraceDiagnostic() {
            if (traceRows.isEmpty()) return "";
            return traceRows.get(traceRows.size() - 1).diagnostic();
        }
    }

    static final class VariantReport {
        @NonNull final String variantName;
        @NonNull final List<Row> rows;

        VariantReport(@NonNull String variantName, @NonNull List<Row> rows) {
            this.variantName = variantName;
            this.rows = new ArrayList<>(rows);
        }

        int reachedUnpackedTargetCount() {
            int count = 0;
            for (Row row : rows) {
                if (row.reachedUnpackedSymbolTarget()) count++;
            }
            return count;
        }

        int totalDecodedSymbols() {
            int total = 0;
            for (Row row : rows) total += row.decodedSymbols;
            return total;
        }

        @NonNull
        String diagnostic() {
            StringBuilder sb = new StringBuilder("variant=").append(variantName)
                    .append("; rows=").append(rows.size())
                    .append("; reachedUnpackedTargets=").append(reachedUnpackedTargetCount())
                    .append("; totalDecodedSymbols=").append(totalDecodedSymbols());
            for (Row row : rows) {
                sb.append("\n  ").append(row.diagnostic());
            }
            return sb.toString();
        }
    }

    static final class TraceRow {
        final int symbolIndex;
        final int decodedSymbol;
        final boolean boundary;
        @NonNull final String boundaryType;
        @NonNull final String boundaryMessage;
        final long lowBefore;
        final long codeBefore;
        final long rangeBefore;
        final long lowAfter;
        final long codeAfter;
        final long rangeAfter;
        @NonNull final String decodeTraceBefore;
        @NonNull final String decodeTraceAfter;
        @NonNull final String modelBefore;
        @NonNull final String modelAfter;

        private TraceRow(int symbolIndex,
                         int decodedSymbol,
                         boolean boundary,
                         @NonNull String boundaryType,
                         @NonNull String boundaryMessage,
                         long lowBefore,
                         long codeBefore,
                         long rangeBefore,
                         long lowAfter,
                         long codeAfter,
                         long rangeAfter,
                         @NonNull String decodeTraceBefore,
                         @NonNull String decodeTraceAfter,
                         @NonNull String modelBefore,
                         @NonNull String modelAfter) {
            this.symbolIndex = symbolIndex;
            this.decodedSymbol = decodedSymbol;
            this.boundary = boundary;
            this.boundaryType = boundaryType;
            this.boundaryMessage = boundaryMessage;
            this.lowBefore = lowBefore;
            this.codeBefore = codeBefore;
            this.rangeBefore = rangeBefore;
            this.lowAfter = lowAfter;
            this.codeAfter = codeAfter;
            this.rangeAfter = rangeAfter;
            this.decodeTraceBefore = decodeTraceBefore;
            this.decodeTraceAfter = decodeTraceAfter;
            this.modelBefore = modelBefore;
            this.modelAfter = modelAfter;
        }

        @NonNull
        static Start captureStart(int symbolIndex,
                                  @NonNull RarPpmdRangeDecoder rangeDecoder,
                                  @NonNull Rar3PpmdModelSymbolSource source) {
            return new Start(symbolIndex, rangeDecoder.low(), rangeDecoder.code(),
                    rangeDecoder.range(), source.lastDecodeTraceForTest(),
                    source.modelForTest().diagnostic());
        }

        @NonNull
        static TraceRow symbol(@NonNull Start start,
                               @NonNull RarPpmdRangeDecoder rangeDecoder,
                               @NonNull Rar3PpmdModelSymbolSource source,
                               int symbol) {
            return new TraceRow(start.symbolIndex, symbol & 0xff, false, "none", "",
                    start.low, start.code, start.range,
                    rangeDecoder.low(), rangeDecoder.code(), rangeDecoder.range(),
                    start.decodeTrace, source.lastDecodeTraceForTest(),
                    start.modelDiagnostic, source.modelForTest().diagnostic());
        }

        @NonNull
        static TraceRow boundary(@NonNull Start start,
                                 @NonNull RarPpmdRangeDecoder rangeDecoder,
                                 @NonNull Rar3PpmdModelSymbolSource source,
                                 @NonNull String boundaryType,
                                 @NonNull String boundaryMessage) {
            return new TraceRow(start.symbolIndex, -1, true, boundaryType, boundaryMessage,
                    start.low, start.code, start.range,
                    rangeDecoder.low(), rangeDecoder.code(), rangeDecoder.range(),
                    start.decodeTrace, source.lastDecodeTraceForTest(),
                    start.modelDiagnostic, source.modelForTest().diagnostic());
        }

        @NonNull
        String diagnostic() {
            return "index=" + symbolIndex
                    + "; boundary=" + boundary
                    + "; symbol=" + (decodedSymbol < 0 ? "" : String.format(Locale.US, "%02x", decodedSymbol & 0xff))
                    + "; rangeBefore=" + rangeDiagnostic(lowBefore, codeBefore, rangeBefore)
                    + "; rangeAfter=" + rangeDiagnostic(lowAfter, codeAfter, rangeAfter)
                    + "; decodeTraceBefore={" + decodeTraceBefore + "}"
                    + "; decodeTraceAfter={" + decodeTraceAfter + "}"
                    + "; boundaryType=" + boundaryType
                    + "; boundaryMessage=" + boundaryMessage;
        }

        @NonNull
        private static String rangeDiagnostic(long low, long code, long range) {
            return "low=0x" + Long.toHexString(low)
                    + "; code=0x" + Long.toHexString(code)
                    + "; range=0x" + Long.toHexString(range);
        }

        static final class Start {
            final int symbolIndex;
            final long low;
            final long code;
            final long range;
            @NonNull final String decodeTrace;
            @NonNull final String modelDiagnostic;

            Start(int symbolIndex,
                  long low,
                  long code,
                  long range,
                  @NonNull String decodeTrace,
                  @NonNull String modelDiagnostic) {
                this.symbolIndex = symbolIndex;
                this.low = low;
                this.code = code;
                this.range = range;
                this.decodeTrace = decodeTrace;
                this.modelDiagnostic = modelDiagnostic;
            }
        }
    }
}
