package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;

public class Rar3UnpackerTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test public void mixedDispatchPreservesOneReservoirAcrossLzPpmdLz() throws Exception {
        BitWriter packed = new BitWriter();
        writeTable(packed, new int[]{'A', 256}, new int[0]);
        packed.writeBitString("00011"); // A, EOF symbol, same-file new table.
        packed.alignToByte(); packed.writeBits(0xa1, 8);
        writeTable(packed, new int[]{'C', 256}, new int[0]);
        packed.writeBitString("000100"); // C, explicit EOF with table reuse.
        int[] symbols = {'B', 2, 0};
        int[] cursor = {0};
        Rar3ClassicLzEngine.PpmdSource source = new Rar3ClassicLzEngine.PpmdSource() {
            @Override public void readTable(java.io.InputStream in) throws java.io.IOException {
                assertEquals(0xa1, in.read());
            }
            @Override public int symbol() { return symbols[cursor[0]++]; }
            @Override public int escape() { return 2; }
        };
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(actual, 3, programs)) {
            RarLzWindow window = new RarLzWindow(new byte[8], 0, 0,
                    RarOutputStreamDecodedOutput.wrapOrMemory(output));
            Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decodeMixed(new RarBitInput(packed.toByteArray()),
                    window, 3, new int[Rar3HuffmanTables.TABLE_SIZE], programs,
                    new Rar3UnpackState(), source, output, false, true);
            output.finish();
            assertTrue(engine.fileEndSeen());
            assertTrue(engine.reuseTablesForNextEntry());
            assertEquals(3, cursor[0]);
            assertArrayEquals(new byte[]{'A','B','C'}, actual.toByteArray());
        }
    }

    @Test public void mixedPpmdMatchUsesLzHistoryWithoutChangingLzMatchCache() throws Exception {
        BitWriter packed = new BitWriter();
        writeTable(packed, new int[]{'A', 256}, new int[0]);
        packed.writeBitString("00011"); packed.alignToByte(); packed.writeBits(0xa1, 8);
        int[] symbols = {2, 5, 0, 2, 2}; // Repeat previous byte four times, then file marker.
        int[] cursor = {0};
        Rar3UnpackState matches = new Rar3UnpackState();
        matches.rememberNewDistanceMatch(3, 7);
        Rar3ClassicLzEngine.PpmdSource source = new Rar3ClassicLzEngine.PpmdSource() {
            @Override public void readTable(java.io.InputStream in) throws java.io.IOException { assertEquals(0xa1, in.read()); }
            @Override public int symbol() { return symbols[cursor[0]++]; }
            @Override public int escape() { return 2; }
        };
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(actual, 5, programs)) {
            Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decodeMixed(new RarBitInput(packed.toByteArray()),
                    new RarLzWindow(new byte[8], 0, 0, RarOutputStreamDecodedOutput.wrapOrMemory(output)),
                    5, new int[Rar3HuffmanTables.TABLE_SIZE], programs, matches, source, output, false, true);
            output.finish();
            assertTrue(engine.fileEndSeen()); assertFalse(engine.reuseTablesForNextEntry());
            assertEquals(3, matches.oldDistance(0)); assertEquals(7, matches.lastLength());
            assertArrayEquals(new byte[]{'A','A','A','A','A'}, actual.toByteArray());
        }
    }

    @Test public void mixedPpmdToLzMatchReadsSharedUnfilteredHistory() throws Exception {
        BitWriter packed = new BitWriter(); packed.writeBits(0xa1,8);
        writeTable(packed, new int[]{256,271}, new int[]{0});
        packed.writeBitString("0100000"); // Match symbol 01, distance 0, EOF 00, flags 00.
        int[] symbols = {'A',2,0}; int[] cursor = {0};
        Rar3ClassicLzEngine.PpmdSource source = new Rar3ClassicLzEngine.PpmdSource() {
            @Override public void readTable(java.io.InputStream in) throws java.io.IOException { assertEquals(0xa1,in.read()); }
            @Override public int symbol() { return symbols[cursor[0]++]; }
            @Override public int escape() { return 2; }
        };
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(actual,4,programs)) {
            Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decodeMixed(new RarBitInput(packed.toByteArray()),
                    new RarLzWindow(new byte[8],0,0,RarOutputStreamDecodedOutput.wrapOrMemory(out)),4,
                    new int[Rar3HuffmanTables.TABLE_SIZE],programs,new Rar3UnpackState(),source,out,false,true);
            out.finish(); assertTrue(engine.fileEndSeen());
            assertArrayEquals(new byte[]{'A','A','A','A'},actual.toByteArray());
        }
    }

    @Test public void mixedModelResetDropsContinuationAndPropagatesInputErrors() throws Exception {
        Rar3MixedPpmdState state = new Rar3MixedPpmdState();
        state.readTable(new java.io.ByteArrayInputStream(new byte[]{(byte)0xe1,0,7,0,0,0,0}));
        assertEquals(7,state.escape());
        state.readTable(new java.io.ByteArrayInputStream(new byte[]{(byte)0x80,0,0,0,0}));
        assertEquals(7,state.escape());
        state.reset(); assertEquals(2,state.escape());
        try { state.readTable(new java.io.ByteArrayInputStream(new byte[]{(byte)0x80})); throw new AssertionError("Missing model"); }
        catch (java.io.IOException expected) { }
        java.io.IOException sentinel = new java.io.IOException("range input");
        try {
            state.readTable(new java.io.InputStream() {
                int position;
                @Override public int read() throws java.io.IOException {
                    if (position++ == 0) return 0xa1;
                    if (position == 2) return 0;
                    throw sentinel;
                }
            });
            throw new AssertionError("Expected range I/O failure");
        } catch (java.io.IOException expected) { org.junit.Assert.assertSame(sentinel,expected); }
    }

    @Test public void mixedHistoryGrowthPreservesWrappedBytes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RarLzWindow window = new RarLzWindow(new byte[8], 0, 0,
                RarOutputStreamDecodedOutput.wrapOrMemory(output));
        for (int i = 0; i < 12; i++) window.writeLiteral(i);
        window.ensureHistoryCapacity(16); output.reset();
        window.copyMatch(8, 8);
        assertArrayEquals(new byte[]{4,5,6,7,8,9,10,11}, output.toByteArray());
    }

    @Test public void requiredSolidBoundaryRejectsSizeOnlySuccess() throws Exception {
        byte[] packed = syntheticPayload(block(new int[]{'A'}, "00"));
        Rar3UnpackContext context = contextFor(writeArchive("missing-boundary.rar", packed), packed, 1, crc("A"));
        context.requireFileBoundary();
        File output = new File(tempFolder.getRoot(), "missing-boundary.out");
        try { Rar3Unpacker.unpack(context, output, null); throw new AssertionError("Missing boundary"); }
        catch (java.io.IOException expected) { assertFalse(output.exists()); }
    }

    @Test public void streamingLzWritesBeforeTheWholeEntryIsDecoded() throws Exception {
        BitWriter packed = new BitWriter();
        writeTable(packed, new int[]{'A',256}, new int[0]);
        for (int i = 0; i < 70000; i++) packed.writeBitString("00");
        byte[] bytes = packed.toByteArray();
        int[] written = {0};
        Rar3UnpackContext context = contextFor(writeArchive("streaming-output.rar", bytes), bytes, 70100, -1);
        // An intentionally truncated final symbol must fail after at least one 64 KiB flush.
        try {
            Rar3Unpacker.unpackPayloadForTest(context, bytes, new java.io.OutputStream() {
                @Override public void write(int value) { written[0]++; }
                @Override public void write(byte[] b, int off, int len) { written[0] += len; }
            });
            throw new AssertionError("Truncated input");
        } catch (java.io.IOException expected) { assertTrue(written[0] >= 65536); }
    }

    @Test
    public void unpack_streamedClassicPayloadHonorsPhysicalOffsetAndSize() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A'}, "00"));
        byte[] fileBytes = new byte[packed.length + 32];
        java.util.Arrays.fill(fileBytes, (byte) 0xff);
        System.arraycopy(packed, 0, fileBytes, 7, packed.length);
        File archive = writeArchive("stream-offset.rar", fileBytes);
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(archive, 7, packed.length,
                1, 0x33, false, false, false, false, crc("A"));
        File out = tempFolder.newFile("stream-offset.out");
        Rar3Unpacker.unpack(context, out, null);
        assertArrayEquals(new byte[] {'A'}, Files.readAllBytes(out.toPath()));
        try (java.io.InputStream input = context.openPackedPayload(null)) {
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) actual.write(value);
            assertArrayEquals(packed, actual.toByteArray());
        }
    }

    @Test
    public void unpack_streamRejectsPayloadTruncatedAfterContextCreation() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A'}, "00"));
        File archive = writeArchive("stream-truncated.rar", packed);
        Rar3UnpackContext context = contextFor(archive, packed, 1, crc("A"));
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(archive, "rw")) {
            file.setLength(packed.length - 1);
        }
        File out = new File(tempFolder.getRoot(), "stream-truncated.out");
        try {
            Rar3Unpacker.unpack(context, out, null);
            throw new AssertionError("Truncated physical range must fail");
        } catch (java.io.IOException expected) {
            assertFalse(out.exists());
        }
    }

    @Test
    public void unpack_largePackedClassicPayloadDoesNotNeedAnInputArray() throws Exception {
        org.junit.Assume.assumeTrue("Opt-in >2 GiB payload test (may use disk space)",
                Boolean.getBoolean("readwide.largeArchiveTests"));
        byte[] packed = syntheticPayload(block(new int[] {'A'}, "00"));
        File archive = writeArchive("large-packed-classic.rar", packed);
        long packedSize = (long) Integer.MAX_VALUE + 1;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(archive, "rw")) {
            file.setLength(packedSize);
        }
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(archive, 0, packedSize,
                1, 0x33, false, false, false, false, crc("A"));
        File out = tempFolder.newFile("large-packed-classic.out");
        Rar3Unpacker.unpack(context, out, null);
        assertArrayEquals(new byte[] {'A'}, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unpack_tablelessHighBitLiteralsDoNotRouteToPpmd() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        unpackSolidDiscard("tables-first.rar", syntheticPayload(
                block(new int[] {'A', 'B', 'C', 256}, "001100")), "A", state);
        assertTrue(state.reuseClassicTables());
        unpackSolidDiscard("tables-second.rar", rawBits("101100"), "C", state);
        assertTrue(state.reuseClassicTables());
        unpackSolidDiscard("tables-third.rar", rawBits("011101"), "B", state);
        assertFalse(state.reuseClassicTables());
        unpackSolidDiscard("tables-fresh.rar", syntheticPayload(
                block(new int[] {'D', 256}, "000101")), "D", state);
    }

    @Test
    public void unpack_tablelessRepeatKeepsMatchAndLowDistanceHistory() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        unpackSolidDiscard("reuse-match-first.rar", syntheticPayload(
                block(new int[] {'A', 256, 258, 263}, "0011000100")), "AAA", state);
        state.unpackState().rememberLowDistance(7);
        state.unpackState().startLowDistanceRepeat(15);
        unpackSolidDiscard("reuse-match-next.rar", rawBits("100100"), "AA", state);
        assertEquals(1, state.unpackState().oldDistance(0));
        assertEquals(2, state.unpackState().lastLength());
        assertEquals(7, state.unpackState().previousLowDistance());
        assertEquals(15, state.unpackState().lowDistanceRepeatCount());
    }

    @Test
    public void unpack_tablelessEntryCanReloadTablesWithinFile() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        unpackSolidDiscard("transition-first.rar", syntheticPayload(
                block(new int[] {'A', 'B', 'C', 256}, "001100")), "A", state);
        BitWriter bits = new BitWriter();
        bits.writeBitString("10111"); // C, end-block, new table in this file.
        bits.alignToByte();
        writeTable(bits, new int[] {'Z', 256}, new int[0]);
        bits.writeBitString("000101"); // Z, EOF, fresh table for the next file.
        unpackSolidDiscard("transition-next.rar", bits.toByteArray(), "CZ", state);
        assertFalse(state.reuseClassicTables());
    }

    @Test
    public void unpack_missingEndMarkerDoesNotEnableTableReuse() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        unpackSolidDiscard("no-marker.rar", syntheticPayload(
                block(new int[] {'A'}, "00")), "A", state);
        assertFalse(state.reuseClassicTables());
    }

    @Test
    public void unpack_partialEndFlagDoesNotEnableTableReuse() throws Exception {
        int[] tables = new int[Rar3HuffmanTables.TABLE_SIZE];
        tables['A'] = 2;
        tables[256] = 2;
        RarBitInput input = new RarBitInput(new byte[] {2});
        input.skipBits(3); // Remaining bits: A=00, EOF=01, first flag=0; second absent.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decodeSolid(input,
                new RarLzWindow(16, out), 1, tables,
                new Rar3VmFilter.ProgramState(), new Rar3UnpackState(), true);
        assertArrayEquals(new byte[] {'A'}, out.toByteArray());
        assertFalse(engine.reuseTablesForNextEntry());
    }

    @Test
    public void unpack_boundaryProbeHandlesUnequalAndMaximumCodeLengths() throws Exception {
        for (int endLength : new int[] {2, 15}) {
            int[] tables = new int[Rar3HuffmanTables.TABLE_SIZE];
            tables['A'] = 1;
            tables[256] = endLength;
            BitWriter bits = new BitWriter();
            bits.writeBits(0, 1); // A's one-bit code.
            bits.writeBits(1 << (endLength - 1), endLength); // Canonical EOF after A.
            bits.writeBits(0, 2);
            RarBitInput input = new RarBitInput(bits.toByteArray());
            Rar3ClassicLzEngine engine = Rar3ClassicLzEngine.decodeSolid(input,
                    new RarLzWindow(16, new ByteArrayOutputStream()), 1, tables,
                    new Rar3VmFilter.ProgramState(), new Rar3UnpackState(), true);
            assertTrue(engine.reuseTablesForNextEntry());
            assertEquals(0, engine.tableReads());
            assertEquals(endLength + 3, input.bitsRead());
        }
    }

    @Test
    public void unpack_shortSolidOutputInvalidatesContinuation() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        byte[] packed = syntheticPayload(block(new int[] {'A', 256}, "0100"));
        try {
            unpackSolidDiscard("short-output.rar", packed, "AA", state);
            throw new AssertionError("Early EOF must fail the declared size check");
        } catch (java.io.IOException expected) {
            // The exact decoder diagnostic can change. The contract below is
            // checked failure followed by invalidation of the solid history.
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
        assertInvalidSolidState(state);
    }

    @Test
    public void unpack_badSolidCrcRequiresExplicitResetBeforeFreshEntry() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        byte[] packed = syntheticPayload(block(new int[] {'A', 256}, "000100"));
        try {
            unpackSolidDiscard("crc-first.rar", packed, "B", state);
            throw new AssertionError("CRC mismatch must fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("CRC"));
        }
        assertInvalidSolidState(state);
        try {
            unpackSolidDiscard("crc-next.rar", packed, "A", state);
            throw new AssertionError("Failed history must not be reused");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("requires reset"));
        }
        state.reset();
        assertFalse(state.reuseClassicTables());
        unpackSolidDiscard("crc-reset.rar", packed, "A", state);
    }

    @Test
    public void unpack_diagnosticCrcMismatchAlsoInvalidatesContinuation() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        byte[] packed = syntheticPayload(block(new int[] {'A', 256}, "000100"));
        Rar3UnpackContext context = Rar3UnpackContext.forSolidEntry(
                writeArchive("diagnostic-crc.rar", packed), 0, packed.length, 1,
                0x33, false, false, false, crc("B"), state);
        Rar3UnpackFileResult result = Rar3Unpacker.unpackForDiagnostics(
                context, tempFolder.newFile("diagnostic-crc.out"), null);
        assertFalse(result.crcMatches());
        assertInvalidSolidState(state);
    }

    @Test
    public void unpack_outputFailureInvalidatesSolidContinuation() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        byte[] packed = syntheticPayload(block(new int[] {'A', 256}, "000100"));
        Rar3UnpackContext context = Rar3UnpackContext.forSolidEntry(
                writeArchive("output-failure.rar", packed), 0, packed.length, 1,
                0x33, false, false, false, crc("A"), state);
        try {
            Rar3Unpacker.unpackPayloadForTest(context, packed, new java.io.OutputStream() {
                @Override public void write(int value) throws java.io.IOException {
                    throw new java.io.IOException("simulated sink failure");
                }
            });
            throw new AssertionError("Output failure must propagate");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("simulated sink failure"));
        }
        assertInvalidSolidState(state);
    }

    @Test
    public void unpack_cancellationInvalidatesSolidContinuation() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        byte[] packed = syntheticPayload(block(new int[] {'A', 256}, "000100"));
        Rar3UnpackContext context = Rar3UnpackContext.forSolidEntry(
                writeArchive("cancel-solid.rar", packed), 0, packed.length, 1,
                0x33, false, false, false, crc("A"), state);
        Thread.currentThread().interrupt();
        try {
            Rar3Unpacker.unpackSolidPrimerToDiscard(context, null);
            throw new AssertionError("Interruption must propagate");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        } finally {
            Thread.interrupted();
        }
        assertInvalidSolidState(state);
    }

    private static byte[] rawBits(String value) {
        BitWriter bits = new BitWriter();
        bits.writeBitString(value);
        return bits.toByteArray();
    }

    private static void assertInvalidSolidState(Rar3SolidState state) throws Exception {
        assertFalse(state.reuseClassicTables());
        try {
            state.ensureUsable();
            throw new AssertionError("Failed solid state must reject reuse");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("requires reset"));
        }
    }

    @Test
    public void unpack_solidRepeatLastUsesDiscardedPrimersMatchHistory() throws Exception {
        byte[] primer = syntheticPayload(blockWithDistance(new int[] {'A', 'B', 'C', 271},
                new int[] {2}, "000110110")); // ABC, distance 3 / length 3 -> ABCABC.
        Rar3SolidState state = new Rar3SolidState();
        unpackSolidDiscard("match-primer.rar", primer, "ABCABC", state);
        assertEquals(3, state.unpackState().oldDistance(0));
        assertEquals(3, state.unpackState().lastLength());

        state.unpackState().rememberLowDistance(7);
        state.unpackState().startLowDistanceRepeat(15);
        byte[] repeat = syntheticPayload(block(new int[] {258}, "00"));
        unpackSolidDiscard("match-repeat.rar", repeat, "ABC", state);
        assertEquals(3, state.unpackState().oldDistance(0));
        assertEquals(0, state.unpackState().oldDistance(1));
        assertEquals(0, state.unpackState().previousLowDistance());
        assertEquals(0, state.unpackState().lowDistanceRepeatCount());
    }

    @Test
    public void unpack_solidOldDistanceSlotsPromoteSharedHistory() throws Exception {
        String[] expected = {"CA", "AB", "BC", "CC"};
        for (int slot = 0; slot < 4; slot++) {
            Rar3SolidState state = new Rar3SolidState();
            byte[] primer = syntheticPayload(blockWithDistance(new int[] {'A', 'B', 'C', 271},
                    new int[] {2}, "000110110"));
            unpackSolidDiscard("slot-primer-" + slot + ".rar", primer, "ABCABC", state);
            // Populate distinct offsets to exercise each selector and its move-to-front order.
            state.unpackState().resetNonSolid();
            for (int distance = 1; distance <= 4; distance++) {
                state.unpackState().rememberNewDistanceMatch(distance, 3);
            }
            BitWriter bits = new BitWriter();
            writeTable(bits, new int[] {259 + slot}, new int[0], new int[] {0});
            bits.writeBitString("000"); // Main selector 00; repeat-length slot 0 -> length 2.
            unpackSolidDiscard("slot-target-" + slot + ".rar", bits.toByteArray(), expected[slot], state);
            assertEquals(4 - slot, state.unpackState().oldDistance(0));
            assertEquals(2, state.unpackState().lastLength());
            if (slot > 0) assertEquals(4, state.unpackState().oldDistance(1));
        }
    }

    @Test
    public void unpack_solidShortMatchCarriesLengthTwoToNextEntry() throws Exception {
        Rar3SolidState state = new Rar3SolidState();
        byte[] primer = syntheticPayload(block(new int[] {'A', 263}, "000100"));
        unpackSolidDiscard("short-primer.rar", primer, "AAA", state);
        unpackSolidDiscard("short-repeat.rar", syntheticPayload(block(new int[] {258}, "00")),
                "AA", state);
        assertEquals(1, state.unpackState().lastDistance());
        assertEquals(2, state.unpackState().lastLength());
    }

    @Test
    public void unpack_newTableWithinEntryKeepsRepeatLastHistory() throws Exception {
        byte[] packed = syntheticPayload(
                blockWithDistance(new int[] {'A', 256, 271}, new int[] {0}, "0010001"),
                block(new int[] {258}, "00"));
        unpackSolidDiscard("table-repeat.rar", packed, "AAAAAAA", new Rar3SolidState());
    }

    @Test
    public void unpack_nonSolidContextClearsPreviouslySeededMatchHistory() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A'}, "00"));
        Rar3UnpackContext context = contextFor(writeArchive("reset-history.rar", packed), packed, 1, crc("A"));
        context.state().rememberNewDistanceMatch(9, 12);
        Rar3Unpacker.unpack(context, tempFolder.newFile("reset-history.out"), null);
        assertEquals(0, context.state().lastLength());
        assertEquals(0, context.state().oldDistance(0));
    }

    private void unpackSolidDiscard(String name, byte[] packed, String expected, Rar3SolidState state)
            throws Exception {
        Rar3UnpackFileResult result = Rar3Unpacker.unpackSolidPrimerToDiscard(
                Rar3UnpackContext.forSolidEntry(writeArchive(name, packed), 0, packed.length,
                        expected.length(), 0x33, false, false, false, crc(expected), state), null);
        assertTrue(result.crcMatches());
        assertEquals(expected.length(), result.written);
    }

    @Test
    public void unpack_writesSyntheticLiteralPayloadAndValidatesCrc() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("literal.rar", packed);
        File out = tempFolder.newFile("literal.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 1, crc("A"));

        Rar3Unpacker.unpack(context, out, null);

        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unpack_readsMultipleSyntheticBlocksUntilUnpackedSize() throws Exception {
        byte[] packed = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("multi.rar", packed);
        File out = tempFolder.newFile("multi.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 2, crc("AB"));

        Rar3Unpacker.unpack(context, out, null);

        assertArrayEquals("AB".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unpack_stopsAtDeclaredUnpackedSizeDuringMatch() throws Exception {
        byte[] packed = syntheticPayload(blockWithDistance(
                new int[] {'A', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {0},
                "00010"));
        File archive = writeArchive("limit.rar", packed);
        File out = tempFolder.newFile("limit.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 3, crc("AAA"));

        Rar3Unpacker.unpack(context, out, null);

        assertArrayEquals("AAA".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unpack_crcMismatchDeletesOutput() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("badcrc.rar", packed);
        File out = tempFolder.newFile("badcrc.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 1, 0x12345678L);

        try {
            Rar3Unpacker.unpack(context, out, null);
        } catch (Exception expected) {
            assertFalse(out.exists());
            return;
        }
        throw new AssertionError("CRC mismatch should fail before reporting success");
    }



    @Test
    public void unpack_realSample3PpmdFixturePassesCrc() throws Exception {
        decodeRealFixture("sample-3.rar", 95, 1363150, 1376878, 0x715fa904L, "sample3-ppmd.out");
    }

    @Test
    public void unpack_realSample4PpmdFixturePassesCrc() throws Exception {
        decodeRealFixture("sample-4.rar", 84, 480547, 1043365, 0x35705f9dL, "sample4-ppmd.out");
    }

    @Test
    public void unpack_realSample5AppleDoubleDocxFixturePassesCrc() throws Exception {
        decodeRealSample5Entry("sample5-appledouble-docx.out", 79, 403, 585, 0xf1ec33a7L);
    }

    @Test
    public void unpack_realSample5AppleDoubleDocFixturePassesCrc() throws Exception {
        decodeRealSample5Entry("sample5-appledouble-doc.out", 540, 167, 496, 0x03e46ab1L);
    }

    @Test
    public void unpack_realSample5DocxFixturePassesCrcAfterOldDistanceLengthFix() throws Exception {
        decodeRealSample5Entry("sample5-docx.out", 755, 5928, 6615, 0x0dc9e250L);
    }

    @Test
    public void unpack_realSample5DocFixturePassesCrcWithVmFilter() throws Exception {
        decodeRealFixture("sample-5.rar", 6730, 6033, 23552, 0x4ab9b212L,
                "sample5-doc-vm-filter.out");
    }


    @Test
    public void unpack_syntheticSolidEntryCopiesFromPreviousEntryWindow() throws Exception {
        byte[] firstPacked = syntheticPayload(
                block(new int[] {'A', 'B', 'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001101101"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        File firstArchive = writeArchive("solid-first.rar", firstPacked);
        File secondArchive = writeArchive("solid-second.rar", secondPacked);
        File firstOut = tempFolder.newFile("solid-first.bin");
        File secondOut = tempFolder.newFile("solid-second.bin");
        assertTrue(firstOut.delete());
        assertTrue(secondOut.delete());
        Rar3SolidState solidState = new Rar3SolidState();

        Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                firstArchive,
                0,
                firstPacked.length,
                3,
                0x33,
                false,
                false,
                false,
                crc("ABC"),
                solidState), firstOut, null);
        Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                secondArchive,
                0,
                secondPacked.length,
                3,
                0x33,
                false,
                false,
                false,
                crc("ABC"),
                solidState), secondOut, null);

        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(firstOut.toPath()));
        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(secondOut.toPath()));
    }


    @Test
    public void unpack_syntheticSolidPrimerCanDiscardOutputAndPreserveDictionary() throws Exception {
        byte[] firstPacked = syntheticPayload(
                block(new int[] {'A', 'B', 'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001101101"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        File firstArchive = writeArchive("solid-discard-first.rar", firstPacked);
        File secondArchive = writeArchive("solid-discard-second.rar", secondPacked);
        File secondOut = tempFolder.newFile("solid-discard-second.bin");
        assertTrue(secondOut.delete());
        Rar3SolidState solidState = new Rar3SolidState();

        Rar3UnpackFileResult primer = Rar3Unpacker.unpackSolidPrimerToDiscard(
                Rar3UnpackContext.forSolidEntry(
                        firstArchive,
                        0,
                        firstPacked.length,
                        3,
                        0x33,
                        false,
                        false,
                        false,
                        crc("ABC"),
                        solidState),
                null);
        Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                secondArchive,
                0,
                secondPacked.length,
                3,
                0x33,
                false,
                false,
                false,
                crc("ABC"),
                solidState), secondOut, null);

        assertEquals(3, primer.written);
        assertEquals(crc("ABC"), primer.actualCrc);
        assertTrue(solidState.initialized());
        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(secondOut.toPath()));
    }

    @Test
    public void unpack_solidDiscardPrimerRejectsCrcMismatchBeforeTargetDecode() throws Exception {
        byte[] firstPacked = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File firstArchive = writeArchive("solid-discard-badcrc-first.rar", firstPacked);
        Rar3SolidState solidState = new Rar3SolidState();

        try {
            Rar3Unpacker.unpackSolidPrimerToDiscard(
                    Rar3UnpackContext.forSolidEntry(
                            firstArchive,
                            0,
                            firstPacked.length,
                            1,
                            0x33,
                            false,
                            false,
                            false,
                            0x12345678L,
                            solidState),
                    null);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("CRC"));
            return;
        }
        throw new AssertionError("Discarded solid primers must still be CRC-gated");
    }

    @Test
    public void unpack_syntheticSolidStateResetDropsPreviousDictionary() throws Exception {
        byte[] firstPacked = syntheticPayload(
                block(new int[] {'A', 'B', 'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001101101"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        File firstArchive = writeArchive("solid-reset-first.rar", firstPacked);
        File secondArchive = writeArchive("solid-reset-second.rar", secondPacked);
        File firstOut = tempFolder.newFile("solid-reset-first.bin");
        File secondOut = tempFolder.newFile("solid-reset-second.bin");
        assertTrue(firstOut.delete());
        assertTrue(secondOut.delete());
        Rar3SolidState solidState = new Rar3SolidState();

        Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                firstArchive, 0, firstPacked.length, 3, 0x33, false, false, false,
                crc("ABC"), solidState), firstOut, null);
        solidState.reset();

        try {
            Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                    secondArchive, 0, secondPacked.length, 3, 0x33, false, false, false,
                    crc("ABC"), solidState), secondOut, null);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertFalse(secondOut.exists());
            return;
        }
        throw new AssertionError("Reset solid state must not keep the previous LZ dictionary");
    }

    @Test
    public void unpack_emptyMainTableStillDoesNotClaimDecodeSupport() throws Exception {
        File archive = tempFolder.newFile("payload.rar");
        byte[] packed = minimalRepeatZeroTablesPayload();
        Files.write(archive.toPath(), packed);
        File out = tempFolder.newFile("decoded.bin");
        assertTrue(out.delete());

        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                archive,
                0,
                packed.length,
                16,
                0x33,
                false,
                false,
                false,
                false);

        try {
            Rar3Unpacker.unpack(context, out, null);
        } catch (java.io.IOException expected) {
            // Any honest decode failure (unsupported-feature or plain decode
            // error) is acceptable; reporting success is not.
            assertFalse(out.exists());
            return;
        }
        throw new AssertionError("RAR3/RAR4 compressed scaffold must not report success without decode symbols");
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void context_rejectsSplitPayloadsUntilSplitRewriteOrSolidStateDecoderHandlesThem() throws Exception {
        File archive = tempFolder.newFile("split.rar");
        Files.write(archive.toPath(), new byte[] {1, 2, 3, 4});

        Rar3UnpackContext.forEntry(
                archive,
                0,
                4,
                16,
                0x33,
                false,
                false,
                true,
                false);
    }

    @Test
    public void unpackPayloadForTest_reportsBitsAndBlockCount() throws Exception {
        byte[] packed = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("result.rar", packed);
        Rar3UnpackContext context = contextFor(archive, packed, 2, -1L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rar3DecodeResult result = Rar3Unpacker.unpackPayloadForTest(context, packed, out);

        assertEquals(2, result.written);
        assertEquals(2, result.blocks);
        assertTrue(result.bitsRead > 0);
        assertArrayEquals("AB".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }



    private void decodeRealFixture(String fixtureName, long offset, long packedSize,
                                   long unpackedSize, long expectedCrc,
                                   String outName) throws Exception {
        File sample = externalFixture(fixtureName);
        File out = tempFolder.newFile(outName);
        assertTrue(out.delete());
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                sample,
                offset,
                packedSize,
                unpackedSize,
                0x33,
                false,
                false,
                false,
                false,
                expectedCrc);

        Rar3Unpacker.unpack(context, out, null);

        assertEquals(unpackedSize, Files.size(out.toPath()));
    }

    private void decodeRealSample5Entry(String outName, long offset, long packedSize, long unpackedSize, long expectedCrc) throws Exception {
        File sample = externalFixture("sample-5.rar");
        File out = tempFolder.newFile(outName);
        assertTrue(out.delete());
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                sample,
                offset,
                packedSize,
                unpackedSize,
                0x33,
                false,
                false,
                false,
                false,
                expectedCrc);

        Rar3Unpacker.unpack(context, out, null);

        assertEquals(unpackedSize, Files.size(out.toPath()));
    }

    private File writeArchive(String name, byte[] packed) throws Exception {
        File archive = tempFolder.newFile(name);
        Files.write(archive.toPath(), packed);
        return archive;
    }

    private Rar3UnpackContext contextFor(File archive, byte[] packed, long unpackedSize, long expectedCrc) throws Exception {
        return Rar3UnpackContext.forEntry(
                archive,
                0,
                packed.length,
                unpackedSize,
                0x33,
                false,
                false,
                false,
                false,
                expectedCrc);
    }

    private static long crc(String text) {
        CRC32 crc = new CRC32();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return crc.getValue() & 0xffffffffL;
    }

    private static Block block(int[] mainSymbols, String actionBits) {
        return new Block(mainSymbols, new int[0], actionBits);
    }

    private static Block blockWithDistance(int[] mainSymbols, int[] distanceSymbols, String actionBits) {
        return new Block(mainSymbols, distanceSymbols, actionBits);
    }

    private static byte[] syntheticPayload(Block... blocks) {
        BitWriter bits = new BitWriter();
        for (int b = 0; b < blocks.length; b++) {
            if (b > 0) {
                // End-of-block continuation: bit 15 set means "new table, same
                // file" (see Rar3ClassicLzEngine.readEndOfBlock).
                bits.writeBitString("1");
            }
            // readTables() byte-aligns the reader before parsing a table.
            bits.alignToByte();
            writeTable(bits, blocks[b].mainSymbols, blocks[b].distanceSymbols);
            bits.writeBitString(blocks[b].actionBits);
        }
        return bits.toByteArray();
    }

    private static void writeTable(BitWriter bits, int[] mainSymbols, int[] distanceSymbols) {
        writeTable(bits, mainSymbols, distanceSymbols, new int[0]);
    }

    private static void writeTable(BitWriter bits, int[] mainSymbols, int[] distanceSymbols,
                                   int[] repeatSymbols) {
        bits.writeBits(0, 2); // PPM=false, keep-old-table=false.
        for (int i = 0; i < Rar3HuffmanTables.BC; i++) {
            bits.writeBits(i == 0 || i == 1 || i == 2 || i == 18 ? 2 : 0, 4);
        }
        int[] lengths = new int[Rar3HuffmanTables.TABLE_SIZE];
        for (int symbol : mainSymbols) lengths[symbol] = 2;
        for (int symbol : distanceSymbols) lengths[Rar3HuffmanTables.NC + symbol] = 1;
        for (int symbol : repeatSymbols) {
            lengths[Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC + symbol] = 1;
        }
        writeMainTableLengths(bits, lengths);
    }

    private static void writeMainTableLengths(BitWriter bits, int[] lengths) {
        for (int i = 0; i < lengths.length;) {
            if (lengths[i] == 0) {
                int count = 0;
                while (i + count < lengths.length && lengths[i + count] == 0 && count < 138) count++;
                if (count >= 3) {
                    count = Math.min(count, 10);
                    bits.writeBitString("11"); // Bit-length symbol 18: zero run of 3..10.
                    bits.writeBits(count - 3, 3);
                    i += count;
                } else {
                    bits.writeBitString("00"); // Direct length 0.
                    i++;
                }
            } else if (lengths[i] == 1) {
                bits.writeBitString("01");
                i++;
            } else if (lengths[i] == 2) {
                bits.writeBitString("10");
                i++;
            } else {
                throw new IllegalArgumentException("Test table writer only supports lengths 0, 1, and 2");
            }
        }
    }

    private static byte[] minimalRepeatZeroTablesPayload() {
        BitWriter bits = new BitWriter();
        bits.writeBits(0, 2);
        for (int i = 0; i < Rar3HuffmanTables.BC; i++) {
            bits.writeBits(i == 18 ? 1 : 0, 4);
        }
        int remaining = Rar3HuffmanTables.TABLE_SIZE;
        while (remaining > 0) {
            int count = Math.min(10, remaining);
            bits.writeBits(0, 1);
            bits.writeBits(count - 3, 3);
            remaining -= count;
        }
        return bits.toByteArray();
    }


    private File externalFixture(String name) {
        String root = System.getProperty("textview.externalArchiveFixtureDir");
        if (root == null || root.trim().length() == 0) {
            root = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        }
        org.junit.Assume.assumeTrue("External archive fixture dir not provided",
                root != null && root.trim().length() > 0);
        File file = new File(root, name);
        org.junit.Assume.assumeTrue("Missing fixture: " + file.getAbsolutePath(), file.isFile());
        return file;
    }

    private static final class Block {
        final int[] mainSymbols;
        final int[] distanceSymbols;
        final String actionBits;

        Block(int[] mainSymbols, int[] distanceSymbols, String actionBits) {
            this.mainSymbols = mainSymbols;
            this.distanceSymbols = distanceSymbols;
            this.actionBits = actionBits;
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int bitsInCurrent;

        void alignToByte() {
            while (bitsInCurrent != 0) writeBits(0, 1);
        }

        void writeBitString(String bits) {
            for (int i = 0; i < bits.length(); i++) {
                char c = bits.charAt(i);
                if (c != '0' && c != '1') continue;
                writeBits(c == '1' ? 1 : 0, 1);
            }
        }

        void writeBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                bitsInCurrent++;
                if (bitsInCurrent == 8) {
                    out.write(current);
                    current = 0;
                    bitsInCurrent = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bitsInCurrent > 0) {
                out.write(current << (8 - bitsInCurrent));
                current = 0;
                bitsInCurrent = 0;
            }
            return out.toByteArray();
        }
    }
}
