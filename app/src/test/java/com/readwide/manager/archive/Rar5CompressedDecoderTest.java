package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.CRC32;

/**
 * End-to-end verification of the first-party RAR5-container compressed
 * decoder. The embedded fixtures originate from the CC0-dedicated
 * "RAR Test Files" collection (see docs/ASSET_PROVENANCE.md) and cover a
 * compressed entry, a stored+compressed mix, and a solid continuation.
 */
public class Rar5CompressedDecoderTest {
    @Test public void packedScalarContractFailuresRetireSolidState() throws Exception {
        byte[] valid = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        for (int invalid : new int[]{-2, 256, Integer.MAX_VALUE}) {
            for (boolean empty : new boolean[]{false, true}) {
                try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                    assertArrayEquals(new byte[]{65}, decoder.decodeEntry(valid, 1, 0x80L));
                    java.io.InputStream source = new java.io.InputStream() {
                        @Override public int read() { return invalid; }
                    };
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    org.junit.Assert.assertThrows(IOException.class,
                            () -> decoder.decodeEntry(source, 0, 0xc0L, output, empty));
                    assertEquals(0, output.size());
                    org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                            () -> decoder.decodeEntry(valid, 1, 0xc0L));
                    assertArrayEquals(new byte[]{65}, decoder.decodeEntry(valid, 1, 0x80L));
                }
            }
        }
    }

    @Test public void invalidBulkCountsFailBeforeParsingOrOutput() throws Exception {
        byte[] packed = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        for (boolean tooLarge : new boolean[]{false, true}) {
            int[] calls = {0};
            java.io.InputStream source = new java.io.ByteArrayInputStream(packed) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    calls[0]++;
                    return tooLarge ? length + 1 : -2;
                }
            };
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                org.junit.Assert.assertThrows(IOException.class,
                        () -> decoder.decodeEntry(source, 1, 0x80L, output));
                assertEquals(1, calls[0]);
                assertEquals(0, output.size());
                org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                        () -> decoder.decodeEntry(packed, 1, 0xc0L));
            }
        }
    }

    @Test public void fragmentedPackedReadStopsImmediatelyAfterInterruption() throws Exception {
        byte[] packed = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        int[] calls = {0};
        java.io.InputStream source = new java.io.ByteArrayInputStream(packed) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                calls[0]++;
                int count = super.read(bytes, offset, Math.min(1, length));
                Thread.currentThread().interrupt();
                return count;
            }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> decoder.decodeEntry(source, 1, 0x80L, output));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(1, calls[0]);
                assertEquals(0, output.size());
            } finally { Thread.interrupted(); }
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(packed, 1, 0xc0L));
        }
    }

    @Test public void scalarCancellationWinsOverInvalidDataAndEofIncludingEmptyEntries() throws Exception {
        for (int value : new int[]{-2, -1, 0, 256}) {
            for (boolean empty : new boolean[]{false, true}) {
                java.io.InputStream source = new java.io.InputStream() {
                    @Override public int read() { Thread.currentThread().interrupt(); return value; }
                };
                try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    try {
                        org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                                () -> decoder.decodeEntry(source, 0, 0x80L, output, empty));
                        assertTrue(Thread.currentThread().isInterrupted());
                        assertEquals(0, output.size());
                    } finally { Thread.interrupted(); }
                }
            }
        }
    }

    @Test public void zeroBulkReadFallbackRemainsValidAndPreservesSourceFailureIdentity() throws Exception {
        byte[] packed = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        java.io.InputStream fragmented = new java.io.ByteArrayInputStream(packed) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return 0; }
        };
        IOException sentinel = new IOException("synthetic packed-source failure");
        java.io.InputStream broken = new java.io.InputStream() {
            int position;
            boolean inBody;
            @Override public int read() throws IOException {
                if (inBody) throw sentinel;
                return packed[position++] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) { inBody = true; return 0; }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            decoder.decodeEntry(fragmented, 1, 0x80L, output);
            assertArrayEquals(new byte[]{65}, output.toByteArray());
            org.junit.Assert.assertSame(sentinel, org.junit.Assert.assertThrows(IOException.class,
                    () -> decoder.decodeEntry(broken, 1, 0xc0L, new ByteArrayOutputStream())));
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(packed, 1, 0xc0L));
        }
    }

    @Test public void finalOutputInterruptionDoesNotPrimeNextSolidEntry() throws Exception {
        byte[] packed = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override public void write(int value) { Thread.currentThread().interrupt(); }
            @Override public void write(byte[] bytes, int offset, int length) { Thread.currentThread().interrupt(); }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(packed), 1, 0x80L, output));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(packed, 1, 0xc0L));
            assertArrayEquals(new byte[]{65}, decoder.decodeEntry(packed, 1, 0x80L));
        }
    }

    @Test public void plannedHistoryPreservesBytesBeyondEarlierDictionaryWrap() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            long nextInfo = info | 0x440L | ((info & 1) != 0 ? 17L << 15 : 0);
            byte[] first = growthPrimer(info);
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                decoder.decodeEntry(new java.io.ByteArrayInputStream(first), 128 * 1024 + 1,
                        info, output, false, Rar5CompressedDecoder.declaredWindowSize(nextInfo));
                assertArrayEquals(growthPlaintext(), output.toByteArray());
                assertArrayEquals(new byte[]{65, 66, 66, 66},
                        decoder.decodeEntry(growthMatch(), 4, nextInfo));
            }
        }
    }

    @Test public void plannedStorageDoesNotIncreaseCurrentMatchDistanceEligibility() throws Exception {
        byte[] packed = tableBlock(tailCodeLengths(0x80L, 262, 1, 340, 1, 370, 1), true,
                bits -> { bits.write(0, 1); bits.write(0, 1); bits.write(0, 12); bits.write(0, 1); });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(packed), 4,
                            0x80L, output, false, 256 * 1024));
            assertEquals(0, output.size());
        }
    }

    @Test public void invalidOrLateHistoryReservationsFailBeforeOutput() throws Exception {
        for (long capacity : new long[]{-1, (1L << 40) + 1}) {
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                        () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]),
                                0, 0x80L, new ByteArrayOutputStream(), true, capacity));
            }
        }
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0,
                    0x80L, new ByteArrayOutputStream(), true, 256 * 1024);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]),
                            0, 0xc0L, output, true, 256 * 1024));
            assertEquals(0, output.size());
        }
    }

    @Test public void wrappedGrowthWorksThroughSelectedBulkAndSkippedViewerPrimers() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            assertGrowthRoutes(growthEntries(info, false, false, 0), null);
        }
    }

    @Test public void plannedGrowthKeepsAesAndSplitContinuityWithUnknownFinalSize() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) {
            java.util.List<RarArchiveReader.RarEntry> entries = growthEntries(0x100081L, encrypted, true, 0);
            int last = entries.size() - 1;
            entries.set(last, withUnpackedSize(entries.get(last), -1));
            assertGrowthRoutes(entries, encrypted ? new char[]{'p'} : null);
        }
    }

    @Test public void plannedGrowthStillRejectsBadPrimerAndFinalChecksums() throws Exception {
        for (int corrupt : new int[]{1, 2}) {
            java.util.List<RarArchiveReader.RarEntry> entries = growthEntries(0x80L, false, true, corrupt);
            RarArchiveReader.RarEntry target = entries.get(2);
            File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
            org.junit.Assert.assertThrows(Rar5CompressedArchiveExtractor.ChecksumException.class,
                    () -> Rar5CompressedArchiveExtractor.tryExtractEntry(target, entries, output, null, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
            File spool = temp.newFolder();
            try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries))) {
                reader.nextEntry();
                org.junit.Assert.assertThrows(Rar5CompressedArchiveExtractor.ChecksumException.class, () -> {
                    reader.nextEntry(); reader.read(new byte[4]);
                });
                org.junit.Assert.assertThrows(IOException.class, reader::nextEntry);
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void dictionaryPlanSeparatesResetsAndIgnoresStoredDirectoriesAndSplitTails() throws Exception {
        java.util.List<RarArchiveReader.RarEntry> first = growthEntries(0x80L, false, true, 0);
        java.util.List<RarArchiveReader.RarEntry> second = growthEntries(0x80L, false, false, 0);
        second.set(1, withCompressionInfo(second.get(1), 0xc0L));
        RarArchiveReader.RarEntry stored = new RarArchiveReader.RarEntry("stored", false,
                0, 0, 0, 5, 0, false, false, false, null, -1, 0, 23L << 10);
        RarArchiveReader.RarEntry directory = new RarArchiveReader.RarEntry("dir/", true,
                0, 0, 0, 5, 0, false, false, false, null, -1, 0, 23L << 10);
        java.util.List<RarArchiveReader.RarEntry> entries = new java.util.LinkedList<>(first);
        entries.add(1, stored); entries.add(2, directory); entries.add(3, null); entries.addAll(second);
        java.util.Map<RarArchiveReader.RarEntry, Long> plan = Rar5CompressedArchiveExtractor.planDictionaryStarts(entries);
        assertEquals(2, plan.size());
        assertEquals(Long.valueOf(256 * 1024), plan.get(first.get(0)));
        assertEquals(Long.valueOf(128 * 1024), plan.get(second.get(0)));
        assertFalse(plan.containsKey(first.get(1)));
        assertFalse(plan.containsKey(stored));
    }

    @Test public void dictionaryLookaheadDoesNotBlockEarlierFilesOnLaterIneligibleHeader() throws Exception {
        java.util.List<RarArchiveReader.RarEntry> entries = growthEntries(0x80L, false, false, 0);
        entries.set(1, withCompressionInfo(entries.get(1), 0xc2L));
        assertEquals(Long.valueOf(128 * 1024),
                Rar5CompressedArchiveExtractor.planDictionaryStarts(entries).get(entries.get(0)));
        try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, temp.newFolder(), RarVolumeChain.index(entries))) {
            assertEquals("primer.bin", reader.nextEntry().path);
            byte[] buffer = new byte[8192]; int total = 0;
            for (int count; (count = reader.read(buffer)) != -1;) total += count;
            assertEquals(128 * 1024 + 1, total);
            assertEquals("page.bin", reader.nextEntry().path);
            org.junit.Assert.assertThrows(IOException.class, () -> reader.read(buffer));
        }
    }

    @Test public void dictionaryPlanningHonorsCancellationBeforeOpeningPayloads() throws Exception {
        java.util.List<RarArchiveReader.RarEntry> entries = growthEntries(0x80L, false, false, 0);
        RarVolumeChain.Index chains = RarVolumeChain.index(entries);
        File directory = temp.newFolder();
        Thread.currentThread().interrupt();
        try {
            org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                    () -> new Rar5ForwardReader(entries, null, directory, chains));
        } finally { Thread.interrupted(); }
        assertEquals(0, directory.list().length);
    }

    @Test public void nativePresentForwardFactorySelectsPlannedGrowingRun() throws Exception {
        java.util.List<RarArchiveReader.RarEntry> entries = growthEntries(0x80L, false, false, 0);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(hexToBytes("526172211a070100"));
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeFixtureVint(fields, 1); writeFixtureVint(fields, 0); writeFixtureVint(fields, 4);
        writeUnknownFixtureHeader(archive, fields);
        for (RarArchiveReader.RarEntry entry : entries) {
            fields.reset();
            writeFixtureVint(fields, 2); writeFixtureVint(fields, 2); writeFixtureVint(fields, entry.packedSize);
            writeFixtureVint(fields, 4); writeFixtureVint(fields, entry.unpackedSize); writeFixtureVint(fields, 0);
            for (int i = 0; i < 4; i++) fields.write((int) (entry.dataCrc >>> (8 * i)));
            writeFixtureVint(fields, entry.rar5CompressionInfo); writeFixtureVint(fields, 0);
            byte[] name = entry.path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeFixtureVint(fields, name.length); fields.write(name);
            writeUnknownFixtureHeader(archive, fields); archive.write(Files.readAllBytes(entry.sourceArchive.toPath()));
        }
        fields.reset(); writeFixtureVint(fields, 5); writeFixtureVint(fields, 0); writeFixtureVint(fields, 0);
        writeUnknownFixtureHeader(archive, fields);
        File input = File.createTempFile("planned_growth_", ".rar", temp.getRoot());
        Files.write(input.toPath(), archive.toByteArray());
        try (ArchiveSupport.ForwardArchiveReader reader = Rar5CompressedArchiveExtractor.openForwardReader(
                input, null, temp.newFolder(), true)) {
            org.junit.Assert.assertNotNull(reader);
            assertEquals("primer.bin", reader.nextEntry().path);
            assertEquals("page.bin", reader.nextEntry().path);
            byte[] output = new byte[4]; assertEquals(4, reader.read(output));
            assertArrayEquals(new byte[]{65, 66, 66, 66}, output);
        }
    }

    private static byte[] growthPlaintext() {
        byte[] bytes = new byte[128 * 1024 + 1]; java.util.Arrays.fill(bytes, (byte) 66); bytes[0] = 65;
        return bytes;
    }
    private static byte[] growthPrimer(long info) throws IOException {
        int lowDistance = (info & 1) != 0 && (info & 0x100000L) == 0 ? 386 : 370;
        return tableBlock(tailCodeLengths(info, 65, 2, 66, 2, 262, 2, 340, 1, lowDistance, 1), true,
                bits -> { bits.write(0, 2); for (int i = 0; i < 128 * 1024; i++) bits.write(1, 2); });
    }
    private static byte[] growthMatch() throws IOException {
        // Main 262 = 10; distance slot 34 = 0; twelve high zero bits, low symbol 0.
        // Distance is 131073, producing ABBB (four bytes after distance length adjustments).
        return tablelessBlock(new byte[]{(byte) 0x80, 0}, 16, true);
    }
    private java.util.List<RarArchiveReader.RarEntry> growthEntries(long info, boolean encrypted,
            boolean split, int corrupt) throws Exception {
        java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        byte[][] plain = {growthPlaintext(), new byte[]{65, 66, 66, 66}};
        for (int file = 0; file < 2; file++) {
            byte[] packed = file == 0 ? growthPrimer(info) : growthMatch();
            RarArchiveReader.EncryptionInfo encryption = null;
            if (encrypted) {
                encryption = new RarArchiveReader.EncryptionInfo(0, 0, 1, new byte[16], new byte[16], new byte[0]);
                Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(new char[]{'p'}, 1, encryption.salt);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(secrets.key, "AES"),
                        new javax.crypto.spec.IvParameterSpec(encryption.iv));
                packed = cipher.doFinal(java.util.Arrays.copyOf(packed, (packed.length + 15) & ~15));
            }
            CRC32 checksum = new CRC32(); checksum.update(plain[file]);
            int parts = split ? 2 : 1;
            for (int part = 0; part < parts; part++) {
                byte[] bytes = java.util.Arrays.copyOfRange(packed, packed.length * part / parts,
                        packed.length * (part + 1) / parts);
                File source = temp.newFile(); Files.write(source.toPath(), bytes);
                CRC32 segment = new CRC32(); segment.update(bytes);
                long crc = part == parts - 1 ? checksum.getValue() ^ (corrupt == file + 1 ? 1 : 0) : segment.getValue();
                RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(file == 0 ? "primer.bin" : "page.bin",
                        false, plain[file].length, bytes.length, 0, 5, 1, file != 0, part != 0,
                        part != parts - 1, encryption, crc, 0, file == 0 ? info : info | 0x440L);
                entry.sourceArchive = source; entries.add(entry);
            }
        }
        return entries;
    }
    private void assertGrowthRoutes(java.util.List<RarArchiveReader.RarEntry> entries, char[] password) throws Exception {
        RarArchiveReader.RarEntry target = entries.get(entries.size() / 2);
        File selected = temp.newFile();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(target, entries, selected, password, null));
        assertArrayEquals(new byte[]{65, 66, 66, 66}, Files.readAllBytes(selected.toPath()));
        File root = temp.newFolder();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(entries, root, password, null, null));
        assertArrayEquals(growthPlaintext(), Files.readAllBytes(new File(root, "primer.bin").toPath()));
        assertArrayEquals(new byte[]{65, 66, 66, 66}, Files.readAllBytes(new File(root, "page.bin").toPath()));
        File spool = temp.newFolder();
        try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, password, spool, RarVolumeChain.index(entries))) {
            assertEquals("primer.bin", reader.nextEntry().path);
            assertEquals("page.bin", reader.nextEntry().path); // Skip/verify the primer without retaining its output.
            byte[] bytes = new byte[4]; assertEquals(4, reader.read(bytes));
            assertArrayEquals(new byte[]{65, 66, 66, 66}, bytes);
            org.junit.Assert.assertNull(reader.nextEntry());
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void solidDictionaryMinimumCanGrowAndDecreaseWithoutResettingTablesOrMatches() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            byte[] first = tableBlock(tailCodeLengths(info, 65, 2, 257, 2, 262, 2, 306, 1), true,
                    bits -> { bits.write(0, 2); bits.write(2, 2); bits.write(0, 1); });
            byte[] repeat = tablelessBlock(new byte[]{0x40}, 2, true);
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                assertArrayEquals(new byte[]{65, 65, 65}, decoder.decodeEntry(first, 3, info));
                assertArrayEquals(new byte[]{65, 65}, decoder.decodeEntry(repeat, 2, info | 0x440L));
                assertArrayEquals(new byte[]{65, 65}, decoder.decodeEntry(repeat, 2, info | 0x40L));
                // Fractional RAR7 dictionaries and its v0 compatibility mode use the same history rule.
                if ((info & 1) != 0) {
                    assertArrayEquals(new byte[]{65, 65}, decoder.decodeEntry(
                            repeat, 2, info | 0x440L | (17L << 15)));
                }
            }
        }
    }

    @Test public void emptySolidContinuationCanGrowDictionaryWithoutAllocatingIt() throws Exception {
        long info = 0x100081L;
        byte[] first = tableBlock(tailCodeLengths(info, 65, 2, 257, 2, 262, 2, 306, 1), true,
                bits -> { bits.write(0, 2); bits.write(2, 2); bits.write(0, 1); });
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(first, 3, info);
            decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0,
                    info | 0x40L | (23L << 10), new ByteArrayOutputStream(), true);
            assertArrayEquals(new byte[]{65, 65}, decoder.decodeEntry(
                    tablelessBlock(new byte[]{0x40}, 2, true), 2, info | 0x40L));
        }
    }

    @Test public void growthAfterWrappedHistoryRejectsBeforeOutputAndRetiresDecoder() throws Exception {
        int length = 128 * 1024 + 1;
        byte[] first = tableBlock(tailCodeLengths(0x80L, 65, 1), true,
                bits -> { for (int i = 0; i < length; i++) bits.write(0, 1); });
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            assertEquals(length, decoder.decodeEntry(first, length, 0x80L).length);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            IOException failure = org.junit.Assert.assertThrows(IOException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]),
                            0, 0x4c0L, output, true));
            assertTrue(failure.getMessage().contains("after history wrap"));
            assertEquals(0, output.size());
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]),
                            0, 0xc0L, output, true));
            // An explicit non-solid restart may create a fresh dictionary after failure.
            assertEquals(length, decoder.decodeEntry(first, length, 0x480L).length);
        }
    }

    @Test public void dictionaryGrowthWorksThroughSelectedBulkAndSkippedForwardPrimers() throws Exception {
        File archive = writeFixture("growing-solid.cbr", SOLID_CBR_HEX);
        java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>(
                RarArchiveReader.readEntries(archive, null));
        RarArchiveReader.RarEntry target = entries.get(1);
        long larger = (target.rar5CompressionInfo & 0x3c0L) | 0x100001L | (4L << 10);
        entries.set(1, withCompressionInfo(target, larger));
        File selected = temp.newFile();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(1), entries, selected, null, null));
        assertExtractedFile(selected, 87, PNG_CRC);
        File bulk = temp.newFolder();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(entries, bulk, null, null, null));
        assertExtractedFile(new File(bulk, "testfile.jpg"), 220, JPG_CRC);
        assertExtractedFile(new File(bulk, "testfile.png"), 87, PNG_CRC);
        File spool = temp.newFolder();
        try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries))) {
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertEquals("testfile.png", reader.nextEntry().path); // Unread primer must be decoded and checked.
            assertForwardPayload(reader, 87, PNG_CRC);
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void growingSplitContinuationStillRequiresFinalCrcAndGuardedPublication() throws Exception {
        File archive = writeFixture("growing-bad-crc.cbr", SOLID_CBR_HEX);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        RarArchiveReader.RarEntry target = entries.get(1);
        long larger = (target.rar5CompressionInfo & 0x3c0L) | 0x100001L | (4L << 10);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(withCompressionInfo(target, larger), true);
        java.util.List<RarArchiveReader.RarEntry> all = new java.util.ArrayList<>();
        all.add(entries.get(0)); all.addAll(split);
        File selected = temp.newFile();
        Files.write(selected.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(Rar5CompressedArchiveExtractor.ChecksumException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractEntry(split.get(0), all, selected, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(selected.toPath()));
        File bulk = temp.newFolder();
        File bulkTarget = new File(bulk, target.path);
        Files.write(bulkTarget.toPath(), new byte[]{43});
        org.junit.Assert.assertThrows(Rar5CompressedArchiveExtractor.ChecksumException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(all, bulk, null, null, null));
        assertExtractedFile(new File(bulk, "testfile.jpg"), 220, JPG_CRC);
        assertArrayEquals(new byte[]{43}, Files.readAllBytes(bulkTarget.toPath()));
        File spool = temp.newFolder();
        try (Rar5ForwardReader reader = new Rar5ForwardReader(all, null, spool, RarVolumeChain.index(all))) {
            reader.nextEntry(); reader.nextEntry();
            org.junit.Assert.assertThrows(Rar5CompressedArchiveExtractor.ChecksumException.class,
                    () -> reader.read(new byte[1]));
            assertEquals(0, spool.list().length);
            org.junit.Assert.assertThrows(IOException.class, () -> reader.nextEntry());
        }
    }

    private static RarArchiveReader.RarEntry withCompressionInfo(RarArchiveReader.RarEntry original, long info) {
        RarArchiveReader.RarEntry copy = new RarArchiveReader.RarEntry(original.path, original.directory,
                original.unpackedSize, original.packedSize, original.dataOffset, original.rarVersion, original.method,
                original.solid, original.splitBefore, original.splitAfter, original.encryption,
                original.dataCrc, original.timeMillis, info, original.blake2sp, original.archiveSolid);
        copy.sourceArchive = original.sourceArchive;
        return copy;
    }

    @Test public void zeroPackedSolidContinuationsPreserveTablesHistoryAndLastMatch() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            byte[] first = tableBlock(tailCodeLengths(info, 65, 2, 257, 2, 262, 2, 306, 1), true,
                    bits -> { bits.write(0, 2); bits.write(2, 2); bits.write(0, 1); });
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                assertArrayEquals(new byte[]{65, 65, 65}, decoder.decodeEntry(first, 3, info));
                ByteArrayOutputStream empty = new ByteArrayOutputStream();
                for (int i = 0; i < 3; i++) {
                    decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, info | 0x40L, empty, true);
                }
                assertEquals(0, empty.size());
                assertArrayEquals(new byte[]{65, 65}, decoder.decodeEntry(
                        tablelessBlock(new byte[]{0x40}, 2, true), 2, info | 0x40L)); // repeat 257 = 01
            }
        }
    }

    @Test public void emptyPackedContinuationRequiresValidMetadataAnEmptyStreamAndAPrimer() throws Exception {
        byte[] first = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, 0xc0L,
                            new ByteArrayOutputStream(), true));
            for (int fault = 0; fault < 5; fault++) {
                decoder.decodeEntry(first, 1, 0x80L);
                long info = fault == 0 ? 0xc2L : fault == 1 ? (0xc0L | (16L << 10)) : 0xc0L;
                long size = fault == 2 ? 1 : 0;
                byte[] packed = fault == 3 ? new byte[]{0} : new byte[0];
                boolean explicitEmpty = fault != 4; // EOF alone must not imply an empty entry.
                org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                        () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(packed), size, info,
                                new ByteArrayOutputStream(), explicitEmpty));
                org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                        () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, 0xc0L,
                                new ByteArrayOutputStream(), true)); // Failure retired the old history.
            }
        }
    }

    @Test public void emptyPackedContinuationHonorsCancellationAfterPhysicalEof() throws Exception {
        byte[] first = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(first, 1, 0x80L);
            java.io.InputStream interruptedEof = new java.io.InputStream() {
                @Override public int read() { Thread.currentThread().interrupt(); return -1; }
            };
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> decoder.decodeEntry(interruptedEof, 0, 0xc0L, new ByteArrayOutputStream(), true));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, 0xc0L,
                            new ByteArrayOutputStream(), true));
        }
    }

    @Test public void insertedEmptySolidHeaderWorksForSelectedAndBulkExtraction() throws Exception {
        File archive = fixtureWithEmptySolidHeader(false);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(3, entries.size());
        assertEquals(0, entries.get(1).packedSize);
        File empty = temp.newFile(); Files.write(empty.toPath(), new byte[]{42});
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(1), entries, empty, null, null));
        assertEquals(0, empty.length());
        File selected = temp.newFile();
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "testfile.png", selected, null));
        assertExtractedFile(selected, 87, PNG_CRC);
        File directory = temp.newFolder();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(entries, directory, null, null, null));
        assertExtractedFile(new File(directory, "testfile.jpg"), 220, JPG_CRC);
        assertEquals(0, new File(directory, "empty.txt").length());
        assertTrue(new File(directory, "empty.txt").isFile());
        assertExtractedFile(new File(directory, "testfile.png"), 87, PNG_CRC);
    }

    @Test public void forwardReaderRetainsOrDrainsEmptyContinuationWithoutLosingHistory() throws Exception {
        File archive = fixtureWithEmptySolidHeader(false);
        for (boolean readEmpty : new boolean[]{false, true}) {
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader =
                    Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
                assertEquals("testfile.jpg", reader.nextEntry().path);
                assertForwardPayload(reader, 220, JPG_CRC);
                assertEquals("empty.txt", reader.nextEntry().path);
                if (readEmpty) assertForwardPayload(reader, 0, 0);
                else assertTrue(reader.drainCurrentEntry(0));
                assertEquals("testfile.png", reader.nextEntry().path);
                assertForwardPayload(reader, 87, PNG_CRC);
                org.junit.Assert.assertNull(reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void badEmptyPrimerChecksumStopsSelectedBulkAndForwardPaths() throws Exception {
        File archive = fixtureWithEmptySolidHeader(true);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        File selected = temp.newFile(); Files.write(selected.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                entries.get(2), entries, selected, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(selected.toPath()));
        File directory = temp.newFolder();
        File empty = new File(directory, "empty.txt"); Files.write(empty.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                entries, directory, null, null, null));
        assertExtractedFile(new File(directory, "testfile.jpg"), 220, JPG_CRC);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(empty.toPath()));
        assertFalse(new File(directory, "testfile.png").exists());
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            reader.nextEntry(); reader.drainCurrentEntry(Long.MAX_VALUE);
            reader.nextEntry();
            org.junit.Assert.assertThrows(IOException.class, () -> reader.drainCurrentEntry(0));
            org.junit.Assert.assertThrows(IOException.class, reader::nextEntry);
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void emptySolidAesSplitChecksumsAndPasswordsStillGuardPublication() throws Exception {
        char[] password = "empty-solid-test".toCharArray(); // Synthetic public fixture password.
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
        for (boolean reset : new boolean[]{false, true}) {
        for (boolean encrypted : new boolean[]{false, true}) {
        for (boolean split : new boolean[]{false, true}) {
        for (int fault = 0; fault < (encrypted ? 5 : 3); fault++) {
            byte[] first = tableBlock(tailCodeLengths(info, 65, 1), true, bits -> bits.write(0, 1));
            File source = temp.newFile(); Files.write(source.toPath(), first);
            CRC32 firstCrc = new CRC32(); firstCrc.update(65);
            RarArchiveReader.RarEntry primer = new RarArchiveReader.RarEntry("primer", false, 1, first.length,
                    0, 5, 1, false, false, false, null, firstCrc.getValue(), 0, info);
            primer.sourceArchive = source;
            RarArchiveReader.EncryptionInfo encryption = null;
            byte[] hash = new RarBlake2sp().digest();
            long crc = 0;
            if (encrypted) {
                Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(password, 1, new byte[16]);
                byte[] check = java.util.Arrays.copyOf(secrets.pswCheck, 12);
                byte[] guard = java.security.MessageDigest.getInstance("SHA-256").digest(secrets.pswCheck);
                System.arraycopy(guard, 0, check, 8, 4);
                encryption = new RarArchiveReader.EncryptionInfo(0, 3, 1, new byte[16], new byte[16], check);
                crc = Rar5Crypto.tweakCrc32(0, secrets);
                hash = Rar5Crypto.tweakBlake2sp(hash, secrets);
            }
            if (fault == 2) hash[0] ^= 1;
            java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
            if (!reset) entries.add(primer);
            int targetIndex = entries.size();
            int count = split ? 2 : 1;
            for (int i = 0; i < count; i++) {
                boolean last = i == count - 1;
                RarArchiveReader.RarEntry part = new RarArchiveReader.RarEntry("empty", false, 0, 0, 0,
                        5, 1, !reset, i != 0, !last, encryption, crc ^ (last && fault == 1 ? 1 : 0),
                        0, info | (reset ? 0 : 0x40L), last ? hash : null, reset);
                part.sourceArchive = temp.newFile(); entries.add(part);
            }
            File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
            char[] supplied = fault == 3 ? "wrong".toCharArray() : fault == 4 ? null : password;
            if (fault == 0) {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(targetIndex), entries, target, supplied, null));
                assertEquals(0, target.length());
            } else {
                org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                        entries.get(targetIndex), entries, target, supplied, null));
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
            }
        }}}}}
    }

    @Test public void emptyResetPrimesAnEmptyDictionaryInAllRar5And7Modes() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, info, output, true);
                decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, info | 0x40L, output, true);
                assertEquals(0, output.size());
                byte[] firstData = tableBlock(tailCodeLengths(info, 65, 1), true, bits -> bits.write(0, 1));
                assertArrayEquals(new byte[]{65}, decoder.decodeEntry(firstData, 1, info | 0x40L));
            }
        }
    }

    @Test public void emptyResetDiscardsOldTablesAndDoesNotInferAnOrdinaryEof() throws Exception {
        byte[] first = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(first, 1, 0x80L);
            decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, 0x80L,
                    new ByteArrayOutputStream(), true);
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(tablelessBlock(new byte[]{0}, 1, true), 1, 0xc0L));
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new byte[0], 0, 0x80L));
        }
    }

    @Test public void firstEmptyHeaderPrimesSelectedBulkAndNativePreferredForwardRoutes() throws Exception {
        File archive = fixtureStartingWithEmptyReset(true, false);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(3, entries.size());
        assertTrue(entries.get(0).archiveSolid);
        assertFalse(entries.get(0).solid);
        File empty = temp.newFile(); Files.write(empty.toPath(), new byte[]{42});
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "empty.txt", empty, null));
        assertEquals(0, empty.length());
        File selected = temp.newFile();
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "testfile.png", selected, null));
        assertExtractedFile(selected, 87, PNG_CRC);
        File bulk = temp.newFolder();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(entries, bulk, null, null, null));
        assertTrue(new File(bulk, "empty.txt").isFile());
        assertEquals(0, new File(bulk, "empty.txt").length());
        assertExtractedFile(new File(bulk, "testfile.jpg"), 220, JPG_CRC);
        assertExtractedFile(new File(bulk, "testfile.png"), 87, PNG_CRC);
        for (boolean readEmpty : new boolean[]{false, true}) {
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader =
                    Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, true)) {
                org.junit.Assert.assertNotNull(reader);
                assertEquals("empty.txt", reader.nextEntry().path);
                if (readEmpty) assertForwardPayload(reader, 0, 0);
                else assertTrue(reader.drainCurrentEntry(0));
                assertEquals("testfile.jpg", reader.nextEntry().path);
                assertForwardPayload(reader, 220, JPG_CRC);
                assertEquals("testfile.png", reader.nextEntry().path);
                assertForwardPayload(reader, 87, PNG_CRC);
                org.junit.Assert.assertNull(reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void badFirstEmptyChecksumPreventsPublicationAndRetiresForwardReader() throws Exception {
        File archive = fixtureStartingWithEmptyReset(true, true);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        File selected = temp.newFile(); Files.write(selected.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                entries.get(2), entries, selected, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(selected.toPath()));
        File bulk = temp.newFolder();
        File empty = new File(bulk, "empty.txt"); Files.write(empty.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractArchive(
                entries, bulk, null, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(empty.toPath()));
        assertFalse(new File(bulk, "testfile.jpg").exists());
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, true)) {
            reader.nextEntry();
            org.junit.Assert.assertThrows(IOException.class, () -> reader.drainCurrentEntry(0));
            org.junit.Assert.assertThrows(IOException.class, reader::nextEntry);
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void emptyResetWithoutArchiveSolidFlagRemainsIneligible() throws Exception {
        File archive = fixtureStartingWithEmptyReset(false, false);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertFalse(entries.get(0).archiveSolid);
        File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
        assertFalse(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(0), entries, target, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
        org.junit.Assert.assertNull(Rar5CompressedArchiveExtractor.openForwardReader(
                archive, null, temp.newFolder(), true));
    }

    @Test public void emptyResetRejectsUnknownSizeBadRangeAndChangedSplitArchiveFlag() throws Exception {
        File source = temp.newFile();
        for (int fault = 0; fault < 3; fault++) {
            RarArchiveReader.RarEntry first = new RarArchiveReader.RarEntry("empty", false,
                    fault == 0 ? -1 : 0, 0, fault == 1 ? 1 : 0, 5, 1, false, false,
                    fault == 2, null, 0, 0, 0x80L, null, true);
            first.sourceArchive = source;
            java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
            entries.add(first);
            if (fault == 2) {
                RarArchiveReader.RarEntry last = new RarArchiveReader.RarEntry("empty", false,
                        0, 0, 0, 5, 1, false, true, false, null, 0, 0, 0x80L, null, false);
                last.sourceArchive = source; entries.add(last);
            }
            File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
            if (fault == 0) {
                assertFalse(Rar5CompressedArchiveExtractor.tryExtractEntry(first, entries, target, null, null));
            } else {
                org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                        first, entries, target, null, null));
            }
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
        }
    }

    @Test public void emptyResetCancellationDoesNotLeaveAPrimedDecoder() throws Exception {
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            java.io.InputStream interruptedEof = new java.io.InputStream() {
                @Override public int read() { Thread.currentThread().interrupt(); return -1; }
            };
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> decoder.decodeEntry(interruptedEof, 0, 0x80L, new ByteArrayOutputStream(), true));
            } finally { Thread.interrupted(); }
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(new byte[0]), 0, 0xc0L,
                            new ByteArrayOutputStream(), true));
        }
    }

    @Test public void mainSolidFlagCannotComeFromExtraDataOrATruncatedVolumeHeader() throws Exception {
        for (boolean extra : new boolean[]{false, true}) {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            archive.write(hexToBytes("526172211a070100"));
            ByteArrayOutputStream fields = new ByteArrayOutputStream();
            writeFixtureVint(fields, 1); // Main header.
            writeFixtureVint(fields, extra ? 1 : 0);
            if (extra) {
                writeFixtureVint(fields, 1); // One extra byte, no main fields.
                writeFixtureVint(fields, 4); // Must not be consumed as the archive flag.
            } else {
                writeFixtureVint(fields, 6); // Solid + volume-number-present, but no number.
            }
            writeUnknownFixtureHeader(archive, fields);
            File source = temp.newFile(); Files.write(source.toPath(), archive.toByteArray());
            org.junit.Assert.assertThrows(IOException.class, () -> RarArchiveReader.readEntries(source, null));
        }
    }

    /** Synthetic main/file headers around unchanged CC0 producer-compressed data. */
    private File fixtureStartingWithEmptyReset(boolean archiveSolid, boolean badEmptyCrc) throws Exception {
        File original = writeFixture("reset-source-" + archiveSolid + "-" + badEmptyCrc + ".rar", SOLID_CBR_HEX);
        byte[] originalBytes = Files.readAllBytes(original.toPath());
        java.util.List<RarArchiveReader.RarEntry> originals = RarArchiveReader.readEntries(original, null);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(hexToBytes("526172211a070100"));
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeFixtureVint(fields, 1); writeFixtureVint(fields, 0); writeFixtureVint(fields, archiveSolid ? 4 : 0);
        writeUnknownFixtureHeader(archive, fields);
        for (int index = -1; index < originals.size(); index++) {
            RarArchiveReader.RarEntry entry = index < 0 ? null : originals.get(index);
            fields.reset();
            writeFixtureVint(fields, 2); writeFixtureVint(fields, 2);
            writeFixtureVint(fields, entry == null ? 0 : entry.packedSize);
            writeFixtureVint(fields, 4); writeFixtureVint(fields, entry == null ? 0 : entry.unpackedSize);
            writeFixtureVint(fields, 0);
            long crc = entry == null ? (badEmptyCrc ? 1 : 0) : entry.dataCrc;
            for (int i = 0; i < 4; i++) fields.write((int) (crc >>> (8 * i)));
            writeFixtureVint(fields, entry == null ? originals.get(0).rar5CompressionInfo & ~0x40L
                    : entry.rar5CompressionInfo | 0x40L);
            writeFixtureVint(fields, 0);
            byte[] name = (entry == null ? "empty.txt" : entry.path).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeFixtureVint(fields, name.length); fields.write(name);
            writeUnknownFixtureHeader(archive, fields);
            if (entry != null) archive.write(originalBytes, (int) entry.dataOffset, (int) entry.packedSize);
        }
        fields.reset();
        writeFixtureVint(fields, 5); writeFixtureVint(fields, 0); writeFixtureVint(fields, 0);
        writeUnknownFixtureHeader(archive, fields);
        File result = temp.newFile(); Files.write(result.toPath(), archive.toByteArray());
        return result;
    }

    @Test public void zeroPackedContinuationStillValidatesPhysicalVolumeBounds() throws Exception {
        File archive = writeFixture("empty-bounds-primer.rar", SOLID_CBR_HEX);
        RarArchiveReader.RarEntry primer = RarArchiveReader.readEntries(archive, null).get(0);
        for (boolean missing : new boolean[]{false, true}) {
            File source = missing ? new File(temp.getRoot(), "missing-empty-source") : temp.newFile();
            RarArchiveReader.RarEntry empty = new RarArchiveReader.RarEntry("empty", false, 0, 0, 1,
                    5, primer.method, true, false, false, null, 0, 0, primer.rar5CompressionInfo | 0x40L);
            empty.sourceArchive = source;
            File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
            org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                    empty, java.util.Arrays.asList(primer, empty), target, null, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
        }
    }

    @Test public void zeroPackedNonSolidOrNonEmptyMetadataRemainsIneligible() throws Exception {
        File archive = writeFixture("empty-invalid-primer.rar", SOLID_CBR_HEX);
        RarArchiveReader.RarEntry primer = RarArchiveReader.readEntries(archive, null).get(0);
        for (int fault = 0; fault < 3; fault++) {
            long info = primer.rar5CompressionInfo | (fault == 2 ? 0 : 0x40L);
            RarArchiveReader.RarEntry invalid = new RarArchiveReader.RarEntry("invalid", false,
                    fault == 0 ? 1 : 0, 0, 0, 5, primer.method, fault != 1, false, false, null, 0, 0, info);
            invalid.sourceArchive = temp.newFile();
            File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
            assertFalse(Rar5CompressedArchiveExtractor.tryExtractEntry(invalid,
                    java.util.Arrays.asList(primer, invalid), target, null, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
        }
    }

    /** Insert a synthetic empty header into the existing CC0 solid fixture.
     * The two producer-compressed payloads are preserved, not regenerated.
     */
    private File fixtureWithEmptySolidHeader(boolean badCrc) throws Exception {
        File original = temp.newFile(); Files.write(original.toPath(), hexToBytes(SOLID_CBR_HEX));
        RarArchiveReader.RarEntry first = RarArchiveReader.readEntries(original, null).get(0);
        int insertion = (int) (first.dataOffset + first.packedSize);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeFixtureVint(fields, 2); writeFixtureVint(fields, 2); writeFixtureVint(fields, 0); // file, data flag, packed size
        writeFixtureVint(fields, 4); writeFixtureVint(fields, 0); writeFixtureVint(fields, 0); // CRC flag, unpacked size, attributes
        fields.write(new byte[]{(byte) (badCrc ? 1 : 0), 0, 0, 0});
        writeFixtureVint(fields, first.rar5CompressionInfo | 0x40L); writeFixtureVint(fields, 0);
        byte[] name = "empty.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeFixtureVint(fields, name.length); fields.write(name);
        ByteArrayOutputStream checked = new ByteArrayOutputStream();
        writeFixtureVint(checked, fields.size()); checked.write(fields.toByteArray());
        CRC32 crc = new CRC32(); crc.update(checked.toByteArray());
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) header.write((int) (crc.getValue() >>> (8 * i)));
        header.write(checked.toByteArray());
        byte[] originalBytes = Files.readAllBytes(original.toPath());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(originalBytes, 0, insertion); result.write(header.toByteArray());
        result.write(originalBytes, insertion, originalBytes.length - insertion);
        File archive = temp.newFile(); Files.write(archive.toPath(), result.toByteArray());
        return archive;
    }

    private static void writeFixtureVint(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f); value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    @Test public void noOpRepeatTailsUseTheSameGrammarForRar5AndBothRar7TableModes() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            int[] codes = tailCodeLengths(info, 65, 1, 257, 1);
            byte[] packed = tableBlock(codes, true, bits -> { bits.write(0, 1); bits.write(7, 3); });
            assertArrayEquals(new byte[]{65}, decodeFilters(packed, 1, info));
        }
    }

    @Test public void unknownOutputUsesLastBlockForRar5AndBothRar7TableModes() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            byte[] first = tableBlock(tailCodeLengths(info, 65, 1, 257, 1), false,
                    bits -> { bits.write(0, 1); bits.write(7, 3); });
            byte[] last = tablelessBlock(new byte[]{0}, 1, true);
            byte[] packed = joinFilterBlocks(first, last);
            assertArrayEquals(new byte[]{65, 65}, decodeFilters(packed, -1, info));
            assertArrayEquals(new byte[0], decodeFilters(tablelessBlock(new byte[0], 0, true), -1, info));
            // Known-size paths retain both underflow and overrun rejection.
            assertRejectedBeforeBufferedOutput(packed, 1, info);
            assertRejectedBeforeBufferedOutput(packed, 3, info);
        }
    }

    @Test public void unknownOutputRequiresCompleteLastBlockAndCompleteFilters() throws Exception {
        for (long info : new long[]{0x80L, 0x81L}) {
            byte[] missingLast = tableBlock(tailCodeLengths(info, 65, 1), false, bits -> bits.write(0, 1));
            byte[] complete = tableBlock(tailCodeLengths(info, 65, 1), true, bits -> bits.write(0, 1));
            assertRejectedBeforeBufferedOutput(missingLast, -1, info);
            assertRejectedBeforeBufferedOutput(java.util.Arrays.copyOf(complete, complete.length - 1), -1, info);
            assertRejectedBeforeBufferedOutput(new byte[0], -1, info);
            assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info, 65, 2), true,
                    bits -> bits.write(0, 1)), -1, info);
            assertRejectedBeforeBufferedOutput(filterBlock(info == 0x81L, true, 2,
                    new int[]{0, 3, 0, 1}), -1, info);
            assertRejectedBeforeBufferedOutput(complete, -2, info);
        }
    }

    @Test public void unknownOutputFiltersAndSolidContinuationUseActualProducedHistory() throws Exception {
        byte[] packed = filterBlock(false, true, 3, new int[]{0, 3, 0, 1});
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            decoder.decodeEntry(new java.io.ByteArrayInputStream(packed), -1, 0x80L, output);
            assertArrayEquals(new byte[]{(byte) 191, 126, 61}, output.toByteArray());
            // Distance 1, length 2 copies raw A bytes, not the filtered output.
            byte[] repeat = tableBlock(tailCodeLengths(0x80L, 262, 1, 306, 1), true,
                    bits -> bits.write(0, 2));
            output.reset();
            decoder.decodeEntry(new java.io.ByteArrayInputStream(repeat), -1, 0xc0L, output);
            assertArrayEquals(new byte[]{65, 65}, output.toByteArray());
        }
    }

    @Test public void unknownSolidOutputCannotOverflowNumericHistoryPosition() throws Exception {
        byte[] one = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        byte[] two = tablelessBlock(new byte[]{0}, 2, true);
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            assertArrayEquals(new byte[]{65}, decoder.decodeEntry(one, 1, 0x80L));
            java.lang.reflect.Field offset = Rar5CompressedDecoder.class.getDeclaredField("solidOffset");
            offset.setAccessible(true); offset.setLong(decoder, Long.MAX_VALUE - 2);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(two), -1, 0xc0L, output));
            assertEquals(0, output.size());
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(one, 1, 0xc0L));
            assertArrayEquals(new byte[]{65}, decoder.decodeEntry(one, 1, 0x80L));
        }
    }

    @Test public void unknownOutputRetiresHistoryOnSinkFailure() throws Exception {
        byte[] packed = tableBlock(tailCodeLengths(0x80L, 65, 1), true, bits -> bits.write(0, 1));
        IOException failure = new IOException("injected output failure");
        java.io.OutputStream sink = new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw failure; }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw failure; }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            org.junit.Assert.assertSame(failure, org.junit.Assert.assertThrows(IOException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(packed), -1, 0x80L, sink)));
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(packed, 1, 0xc0L));
        }
    }

    @Test public void unknownEmptyLastBlockChecksCancellationBeforeSuccess() throws Exception {
        java.io.InputStream source = new java.io.ByteArrayInputStream(tablelessBlock(new byte[0], 0, true)) {
            @Override public synchronized int read() {
                int value = super.read();
                if (pos == count) Thread.currentThread().interrupt();
                return value;
            }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> decoder.decodeEntry(source, -1, 0x80L, new ByteArrayOutputStream()));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void noOpAndEmptyTrailingBlocksRetainTablesAndSolidHistory() throws Exception {
        for (long info : new long[]{0x80L, 0x81L}) {
            byte[] first = tableBlock(tailCodeLengths(info, 65, 1, 257, 1), false,
                    bits -> bits.write(0, 1));
            byte[] tail = tablelessBlock(new byte[]{(byte) 0xe0}, 3, false);
            byte[] last = tablelessBlock(new byte[0], 0, true);
            byte[] packed = joinFilterBlocks(first, joinFilterBlocks(tail, last));
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                assertArrayEquals(new byte[]{65}, decoder.decodeEntry(packed, 1, info));
                // A solid successor uses the predecessor's literal table.
                assertArrayEquals(new byte[]{65}, decoder.decodeEntry(
                        tablelessBlock(new byte[]{0}, 1, true), 1, info | 0x40L));
            }
        }
    }

    @Test public void zeroOutputEntryCanContainAnEmptyTablelessFinalBlock() throws Exception {
        for (long info : new long[]{0x80L, 0x81L}) {
            assertArrayEquals(new byte[0], decodeFilters(tablelessBlock(new byte[0], 0, true), 0, info));
            assertArrayEquals(new byte[0], decodeFilters(tableBlock(
                    tailCodeLengths(info, 257, 1), true, bits -> bits.write(0, 3)), 0, info));
        }
    }

    @Test public void repeatTailWithAnActualPreviousMatchStillRejectsOutputOverrun() throws Exception {
        for (long info : new long[]{0x80L, 0x81L}) {
            byte[] packed = tableBlock(tailCodeLengths(info, 65, 2, 257, 2, 262, 2, 306, 1), true,
                    bits -> { bits.write(0, 2); bits.write(2, 2); bits.write(0, 1); bits.write(1, 2); });
            assertRejectedBeforeBufferedOutput(packed, 3, info);
        }
    }

    @Test public void paddedLookaheadCannotCompleteATruncatedHuffmanCode() throws Exception {
        for (long info : new long[]{0x80L, 0x81L}) {
            byte[] packed = tableBlock(tailCodeLengths(info, 65, 2), true, bits -> bits.write(0, 1));
            assertRejectedBeforeBufferedOutput(packed, 1, info);
        }
    }

    @Test public void validShortCodesAndUnusedEmptyAlphabetsRemainSupported() throws Exception {
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
            for (int width : new int[]{1, 2, 7, 11, 15}) {
                byte[] packed = tableBlock(tailCodeLengths(info, 65, width), true,
                        bits -> bits.write(0, width));
                assertArrayEquals(new byte[]{65}, decodeFilters(packed, 1, info));
            }
        }
    }

    @Test public void invalidPrefixesAndEmptyUsedTablesCannotManufactureSymbolZero() throws Exception {
        long info = 0x80L;
        assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info, 65, 2), true,
                bits -> bits.write(1, 2)), 1, info); // 01 is outside the single 00 code.
        assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info), true,
                bits -> bits.write(0, 1)), 1, info); // Empty main table.
        assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info, 262, 1), true,
                bits -> bits.write(0, 2)), 2, info); // Match reads an empty distance table.
    }

    @Test public void oversubscribedHuffmanTreesAreRejectedBeforeAnyOutput() throws Exception {
        for (long info : new long[]{0x80L, 0x81L}) {
            assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info, 65, 1, 66, 1, 67, 1),
                    true, bits -> bits.write(0, 1)), 1, info);
            assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info, 65, 1, 66, 2, 67, 2, 68, 2),
                    true, bits -> bits.write(0, 1)), 1, info);
        }
        // Oversubscribed code-length alphabet, before any main/distance table exists.
        byte[] alphabet = new byte[10]; alphabet[0] = 0x11; alphabet[1] = 0x10;
        assertRejectedBeforeBufferedOutput(frameBlock(alphabet, 80, true), 1, 0x80L);
    }

    @Test public void packedPaddingCannotSupplyMissingLengthDistanceOrTableBits() throws Exception {
        long info = 0x80L;
        assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info, 262, 2, 310, 1), true,
                bits -> bits.write(0, 3)), 2, info); // Distance slot 4 needs one more raw bit.
        assertRejectedBeforeBufferedOutput(tableBlock(tailCodeLengths(info, 305, 2, 306, 1), true,
                bits -> bits.write(0, 10)), 3586, info); // Length needs 9 bits, only 8 supplied.
        byte[] alphabet = new byte[10]; java.util.Arrays.fill(alphabet, (byte) 0x55);
        assertRejectedBeforeBufferedOutput(frameBlock(alphabet, 79, true), 0, info);
    }

    @Test public void malformedTailRetiresSolidStateButAllowsAFreshIndependentEntry() throws Exception {
        byte[] valid = tableBlock(tailCodeLengths(0x80L, 65, 2), true, bits -> bits.write(0, 2));
        byte[] invalid = tableBlock(tailCodeLengths(0x80L, 65, 2), true, bits -> bits.write(0, 1));
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            assertArrayEquals(new byte[]{65}, decoder.decodeEntry(valid, 1, 0x80L));
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(invalid, 1, 0xc0L));
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(valid, 1, 0xc0L));
            assertArrayEquals(new byte[]{65}, decoder.decodeEntry(valid, 1, 0x80L));
        }
    }

    @Test public void noOutputTailChecksCancellationWithoutClearingInterrupt() throws Exception {
        byte[] packed = tableBlock(tailCodeLengths(0x80L, 257, 1), true, bits -> bits.write(0, 8));
        java.io.InputStream source = new java.io.ByteArrayInputStream(packed) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                Thread.currentThread().interrupt(); // After the block-header checkpoint.
                return count;
            }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> decoder.decodeEntry(source, 0, 0x80L, new ByteArrayOutputStream()));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void emptyBlockSequenceChecksCancellationWithoutClearingInterrupt() throws Exception {
        byte[] packed = joinFilterBlocks(tablelessBlock(new byte[0], 0, false),
                tablelessBlock(new byte[0], 0, true));
        java.io.InputStream source = new java.io.ByteArrayInputStream(packed) {
            @Override public synchronized int read() {
                int value = super.read();
                if (pos == 3) Thread.currentThread().interrupt();
                return value;
            }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> decoder.decodeEntry(source, 0, 0x80L, new ByteArrayOutputStream()));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void knownFinalSizeKeepsAesSplitChecksAndRejectsExtraPackedTail() throws Exception {
        checkBlockTailExtraction(false);
    }

    @Test public void unknownFinalSizeKeepsAesSplitChecksAndRejectsExtraPackedTail() throws Exception {
        checkBlockTailExtraction(true);
    }

    private void checkBlockTailExtraction(boolean unknownSize) throws Exception {
        char[] password = "tail-test".toCharArray(); // Synthetic public fixture password.
        for (long info : new long[]{0x80L, 0x81L, 0x100081L}) {
        for (boolean encrypted : new boolean[]{false, true}) {
        for (boolean split : new boolean[]{false, true}) {
        for (boolean emptyHead : new boolean[]{false, true}) {
        if (emptyHead && !split) continue;
        for (int fault = 0; fault < 4; fault++) {
            byte[] packed = fault == 1
                    ? tableBlock(tailCodeLengths(info, 65, 2), true, bits -> bits.write(0, 1))
                    : tableBlock(tailCodeLengths(info, 65, 1, 257, 1), true,
                            bits -> { bits.write(0, 1); bits.write(7, 3); });
            RarArchiveReader.EncryptionInfo encryption = null;
            if (fault == 3) packed = java.util.Arrays.copyOf(packed, packed.length + 16);
            if (encrypted) {
                encryption = new RarArchiveReader.EncryptionInfo(0, 0, 1, new byte[16], new byte[16], new byte[0]);
                Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(password, 1, encryption.salt);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(secrets.key, "AES"),
                        new javax.crypto.spec.IvParameterSpec(encryption.iv));
                packed = cipher.doFinal(java.util.Arrays.copyOf(packed, (packed.length + 15) & ~15));
            }
            CRC32 finalCrc = new CRC32(); finalCrc.update(65);
            java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
            byte[][] parts = !split ? new byte[][]{packed} : emptyHead
                    ? new byte[][]{new byte[0], java.util.Arrays.copyOfRange(packed, 0, 17),
                        java.util.Arrays.copyOfRange(packed, 17, packed.length)}
                    : new byte[][]{java.util.Arrays.copyOfRange(packed, 0, 17),
                        java.util.Arrays.copyOfRange(packed, 17, packed.length)};
            int count = parts.length;
            for (int i = 0; i < count; i++) {
                byte[] part = parts[i];
                CRC32 partCrc = new CRC32(); partCrc.update(part);
                long crc = i == count - 1 ? finalCrc.getValue() ^ (fault == 2 ? 1 : 0) : partCrc.getValue();
                File source = temp.newFile(); Files.write(source.toPath(), part);
                RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("tail.bin", false,
                        unknownSize ? -1 : 1, part.length, 0,
                        5, 1, false, i != 0, i != count - 1, encryption, crc, 0, info);
                entry.sourceArchive = source; entries.add(entry);
            }
            File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
            if (fault != 0) {
                org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                        entries.get(0), entries, target, password, null));
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
            } else {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(0), entries, target, password, null));
                assertArrayEquals(new byte[]{65}, Files.readAllBytes(target.toPath()));
            }
            File bulk = temp.newFolder();
            File bulkTarget = new File(bulk, "tail.bin");
            Files.write(bulkTarget.toPath(), new byte[]{42});
            if (fault != 0) {
                org.junit.Assert.assertThrows(IOException.class, () ->
                        Rar5CompressedArchiveExtractor.tryExtractArchive(entries, bulk, password, null, null));
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(bulkTarget.toPath()));
            } else {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(entries, bulk, password, null, null));
                assertArrayEquals(new byte[]{65}, Files.readAllBytes(bulkTarget.toPath()));
            }
            for (boolean retain : new boolean[]{false, true}) {
                File spool = temp.newFolder();
                try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, password, spool,
                        RarVolumeChain.index(entries))) {
                    assertEquals("tail.bin", reader.nextEntry().path);
                    byte[] visible = {42};
                    if (fault != 0) {
                        if (retain) {
                            org.junit.Assert.assertThrows(IOException.class, () -> reader.read(visible));
                            assertArrayEquals(new byte[]{42}, visible);
                        } else {
                            org.junit.Assert.assertThrows(IOException.class,
                                    () -> reader.drainCurrentEntry(Long.MAX_VALUE));
                        }
                        org.junit.Assert.assertThrows(IOException.class, reader::nextEntry);
                    } else {
                        if (retain) {
                            assertEquals(1, reader.read(visible));
                            assertArrayEquals(new byte[]{65}, visible);
                            assertEquals(-1, reader.read(visible));
                        } else {
                            assertTrue(reader.drainCurrentEntry(Long.MAX_VALUE));
                        }
                        assertNull(reader.nextEntry());
                    }
                }
                assertEquals(0, spool.list().length);
            }
        }}}}}
    }

    private void assertRejectedBeforeBufferedOutput(byte[] packed, long size, long info) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(packed), size, info, output));
            assertEquals(0, output.size());
        }
    }

    /** Explicit code lengths make malformed-prefix/bit-boundary expectations independent of the decoder. */
    private static int[] tailCodeLengths(long info, int... symbolLengthPairs) {
        int[] codes = new int[Rar5CompressedDecoder.usesExtendedDistanceTable(info) ? 446 : 430];
        for (int i = 0; i < symbolLengthPairs.length; i += 2) codes[symbolLengthPairs[i]] = symbolLengthPairs[i + 1];
        return codes;
    }

    private static byte[] tableBlock(int[] codes, boolean last, java.util.function.Consumer<BitWriter> symbols)
            throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        // Twenty five-bit code-length symbols, with canonical codes 0..19.
        for (int i = 0; i < 10; i++) data.write(0x55);
        BitWriter bits = new BitWriter();
        for (int code : codes) bits.write(code, 5);
        symbols.accept(bits);
        data.write(bits.toByteArray());
        return frameBlock(data.toByteArray(), bits.bitCount(), last);
    }

    private static byte[] tablelessBlock(byte[] data, int validBits, boolean last) throws IOException {
        byte[] packed = frameBlock(data, validBits, last);
        packed[0] ^= (byte) 0x80; packed[1] ^= (byte) 0x80;
        return packed;
    }

    @Test public void finalChecksumFailureIsCorruptionAndPreservesOutput() throws Exception {
        File archive = writeFixture("bad-final.rar", SOLID_CBR_HEX);
        RarArchiveReader.RarEntry first = RarArchiveReader.readEntries(archive, null).get(0);
        RarArchiveReader.RarEntry bad = new RarArchiveReader.RarEntry("unsupported-wrong-password.jpg", false,
                first.unpackedSize, first.packedSize, first.dataOffset, 5, first.method, first.solid,
                false, false, null, first.dataCrc ^ 1, first.timeMillis, first.rar5CompressionInfo);
        bad.sourceArchive = first.sourceArchive;
        File output = temp.newFile();
        ArchiveTestTextFiles.writeString(output.toPath(), "existing");
        try {
            Rar5CompressedArchiveExtractor.tryExtractEntry(bad, java.util.Collections.singletonList(bad),
                    output, null, null);
            fail("Expected checksum failure");
        } catch (Rar5CompressedArchiveExtractor.ChecksumException expected) {
            assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                    ArchiveFailureClassifier.classify(expected));
        }
        assertEquals("existing", ArchiveTestTextFiles.readString(output.toPath()));
    }
    @Test public void forwardFailure_truncatedSpool() throws Exception {
        File archive = writeFixture("failure.rar", SOLID_CBR_HEX);
        File spool = temp.newFolder();
        RarForwardFailureAssertions.truncatedSpool(Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false), spool);
    }

    @Test public void forwardFailure_deletionRetry() throws Exception {
        File archive = writeFixture("failure.rar", SOLID_CBR_HEX);
        File spool = temp.newFolder();
        RarForwardFailureAssertions.deletionRetry(Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false), spool);
    }

    @Test public void forwardFailure_interruptionRetires() throws Exception {
        File archive = writeFixture("failure.rar", SOLID_CBR_HEX);
        File spool = temp.newFolder();
        RarForwardFailureAssertions.interruptionRetires(Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false), spool);
    }


    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** testfile.rar5.cbr (410 bytes): jpg compressed (method 5) + png stored. */
    private static final String CBR_HEX = ""
            + "526172211a0701003392b5e50a01050600050101808000f41d9aa6220202d60106dc01b68302d00e503a6cb170da8005"
            + "010c7465737466696c652e6a7067c54cd326644532f65035d9d3c67673922101107c2504110685202d4b6b11a106b684"
            + "106896be0ed4dcf85a082f80212031e805b5a9b1b380a3b254f8c6f49a66e6e6ef93fe686e1986061fff5fde2e794fe0"
            + "0b760b900208000c4d43f50b409233b4f54bec9913f9aa422845955d7195965d97195d86d81b5a334db85298a213af3a"
            + "57a6bca7dc0c2a088b8af9854e0146757f956616994465049ff884e1e51e7501d87e8249f0590a913fc76fb53bc589f6"
            + "3b57267adae5c32471c9e766fd3092c72c18eae8a59a1c9a87f419544eddebe633de8fcce250dc1c220202d70006d700"
            + "b68302d00e503a62acb0af8000010c7465737466696c652e706e6789504e470d0a1a0a0000000d494844520000000200"
            + "00000208030000004568fd1600000006504c5445000000ffffffa5d99fdd0000000c494441547801636060044200000c"
            + "000339e077030000000049454e44ae4260821d77565103050400";

    /** testfile.rar5.solid.cbr (407 bytes): jpg compressed + png compressed solid. */
    private static final String SOLID_CBR_HEX = ""
            + "526172211a07010020b6fa110a0105060405010180800046c44bd1220202ec0106dc01b68302d00e503a6cb170da801d"
            + "010c7465737466696c652e6a7067c477e9276544432f57044ac99996de5e66497731181041e1282288231cc8b8890c52"
            + "6a62260a20e8742082a4d8ba9e02ea41d4bb1e06820bc01090191fe066d753b1d4960a4aabd538c5ddd7f17eaf57aab9"
            + "357c515545515e3c7df6bfdc5d77978013ab5e5ae0d34003e272fd83481ba0ce31edffce19cfece633a678e840b3cf22"
            + "0a5021fa2f8cb0aa1a3492952442a71534c453a57e00f9c280f456d42a79063b1770a7217252233a24c5a4258e3c7138"
            + "c37cbf512328680655ba9e7dff8efcdbdba8ffbe71b8b33aaf6d5db972efd76f8f3da367fa6cee65f1d5e1b5bdd23be0"
            + "703ae4736ba9517daef8d31cd7d7220202be0006d700b68302d00e503a62acb0afc01d010c7465737466696c652e706e"
            + "6745243be89ac69c28aa825a865d48f618a98c76c9f26ffee4140d36b5990a2b5afb2ff5fc14b04b1e8d9e56b7679dca"
            + "c5f41bcdc1c8cb80964b11f7569f981d77565103050400";

    /** testfile.rar5.solid.rar (97 bytes): single compressed txt entry. */
    private static final String SOLID_RAR_HEX = ""
            + "526172211a07010020b6fa110a010506040501018080001b084cbc2202029b00068c00b68302d00e503afe8fc16e801d"
            + "010c7465737466696c652e747874c6841830022fb32fd85305562ac15ce39390bfdfe35bc983f357901d775651030504"
            + "00";

    private static final long TXT_CRC = 0x6ec18ffeL;
    private static final long PNG_CRC = 0xafb0ac62L;
    private static final long JPG_CRC = 0xda70b16cL;

    // ---- embedded fixtures (always-on) ----

    @Test public void unknownSizeHeadersReachSelectedBulkAndForwardSolidExtraction() throws Exception {
        File archive = unknownSizedProducerArchive(false);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(2, entries.size());
        for (RarArchiveReader.RarEntry entry : entries) assertEquals(-1, entry.unpackedSize);
        File selected = temp.newFile();
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "testfile.png", selected, null));
        assertExtractedFile(selected, 87, PNG_CRC);
        File bulk = temp.newFolder();
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, bulk, null));
        assertExtractedFile(new File(bulk, "testfile.jpg"), 220, JPG_CRC);
        assertExtractedFile(new File(bulk, "testfile.png"), 87, PNG_CRC);
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, true)) {
            org.junit.Assert.assertNotNull(reader); // Unknown headers override native preference.
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertTrue(reader.drainCurrentEntry(220));
            assertEquals("testfile.png", reader.nextEntry().path);
            CRC32 crc = new CRC32();
            byte[] bytes = new byte[17]; int count; int size = 0;
            while ((count = reader.read(bytes)) != -1) { crc.update(bytes, 0, count); size += count; }
            assertEquals(87, size); assertEquals(PNG_CRC, crc.getValue());
            org.junit.Assert.assertNull(reader.nextEntry());
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void bulkCompressedProgressReplacesStaleTotalsWithLogicalSizes() throws Exception {
        File archive = writeFixture("progress-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive, null).get(0);
        for (boolean unknownFinal : new boolean[]{false, true}) {
            java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original, false);
            split.set(0, withUnpackedSize(split.get(0), 3));
            if (unknownFinal) {
                int last = split.size() - 1;
                split.set(last, withUnpackedSize(split.get(last), -1));
            }
            com.readwide.manager.util.FileOperationProgress progress =
                    new com.readwide.manager.util.FileOperationProgress("RAR", null);
            progress.setTotalBytes(3);
            progress.addDoneBytes(2); // A prior route must not leak its byte counter.
            File output = temp.newFolder();
            assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(split, output, null, progress, null));
            assertExtractedFile(new File(output, original.path), 12, TXT_CRC);
            assertEquals(12L, progress.snapshot().doneBytes);
            assertEquals(unknownFinal, progress.snapshot().indeterminate);
            assertEquals(unknownFinal ? 0L : 12L, progress.snapshot().totalBytes);
        }
    }

    @Test public void unknownSizeForwardBoundStopsAndRetiresPartlyDecodedHistory() throws Exception {
        File archive = unknownSizedProducerArchive(false);
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            reader.nextEntry();
            IOException failure = org.junit.Assert.assertThrows(IOException.class,
                    () -> reader.drainCurrentEntry(219));
            assertTrue(failure.getMessage().contains("drain bound"));
            org.junit.Assert.assertThrows(IOException.class, () -> reader.nextEntry());
            assertEquals(0, spool.list().length);
        }
        // Invalid caller input must fail before decoding, without consuming the entry.
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            reader.nextEntry();
            org.junit.Assert.assertThrows(IOException.class, () -> reader.drainCurrentEntry(-1));
            assertTrue(reader.drainCurrentEntry(220));
            assertEquals("testfile.png", reader.nextEntry().path);
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void unknownSizeFinalChecksumFailureRestoresTargetsAndRetiresForwardReader() throws Exception {
        File archive = unknownSizedProducerArchive(true);
        java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        File selected = temp.newFile(); Files.write(selected.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                entries.get(1), entries, selected, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(selected.toPath()));
        File bulk = temp.newFolder(); File existing = new File(bulk, "testfile.png");
        Files.write(existing.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractArchive(entries, bulk, null, null, null));
        assertExtractedFile(new File(bulk, "testfile.jpg"), 220, JPG_CRC);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            reader.nextEntry(); reader.drainCurrentEntry(Long.MAX_VALUE);
            reader.nextEntry();
            org.junit.Assert.assertThrows(IOException.class, () -> reader.read(new byte[1]));
            org.junit.Assert.assertThrows(IOException.class, () -> reader.nextEntry());
            assertEquals(0, spool.list().length);
        }
    }

    /** Synthetic unknown-size headers; the CC0 producer's compressed solid payloads are unchanged. */
    private File unknownSizedProducerArchive(boolean badFinalCrc) throws Exception {
        File original = temp.newFile(); Files.write(original.toPath(), hexToBytes(SOLID_CBR_HEX));
        byte[] originalBytes = Files.readAllBytes(original.toPath());
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(hexToBytes("526172211a070100"));
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeFixtureVint(fields, 1); writeFixtureVint(fields, 0); writeFixtureVint(fields, 4);
        writeUnknownFixtureHeader(archive, fields);
        for (RarArchiveReader.RarEntry entry : RarArchiveReader.readEntries(original, null)) {
            fields.reset();
            writeFixtureVint(fields, 2); writeFixtureVint(fields, 2); writeFixtureVint(fields, entry.packedSize);
            writeFixtureVint(fields, 12); writeFixtureVint(fields, 0); writeFixtureVint(fields, 0);
            long crc = entry.dataCrc ^ (badFinalCrc && entry.path.endsWith(".png") ? 1 : 0);
            for (int i = 0; i < 4; i++) fields.write((int) (crc >>> (8 * i)));
            writeFixtureVint(fields, entry.rar5CompressionInfo); writeFixtureVint(fields, 0);
            byte[] name = entry.path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeFixtureVint(fields, name.length); fields.write(name);
            writeUnknownFixtureHeader(archive, fields);
            archive.write(originalBytes, (int) entry.dataOffset, (int) entry.packedSize);
        }
        fields.reset();
        writeFixtureVint(fields, 5); writeFixtureVint(fields, 0); writeFixtureVint(fields, 0);
        writeUnknownFixtureHeader(archive, fields);
        File result = temp.newFile(); Files.write(result.toPath(), archive.toByteArray());
        return result;
    }

    private static void writeUnknownFixtureHeader(ByteArrayOutputStream archive, ByteArrayOutputStream fields)
            throws IOException {
        ByteArrayOutputStream checked = new ByteArrayOutputStream();
        writeFixtureVint(checked, fields.size()); checked.write(fields.toByteArray());
        CRC32 crc = new CRC32(); crc.update(checked.toByteArray());
        for (int i = 0; i < 4; i++) archive.write((int) (crc.getValue() >>> (8 * i)));
        archive.write(checked.toByteArray());
    }

    @Test public void unknownSplitHeadUsesFinalSizeForSelectedBulkAndForward() throws Exception {
        File archive = writeFixture("unknown-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive, null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original, false);
        split.set(0, withUnpackedSize(split.get(0), -1));
        File selected = temp.newFile();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(split.get(0), split, selected, null, null));
        assertExtractedFile(selected, 12, TXT_CRC);
        File bulk = temp.newFolder();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(split, bulk, null, null, null));
        assertExtractedFile(new File(bulk, original.path), 12, TXT_CRC);
        File spool = temp.newFolder();
        for (long headSize : new long[]{-1, 3}) {
            split.set(0, withUnpackedSize(split.get(0), headSize));
            try (ArchiveSupport.ForwardArchiveReader reader =
                    new Rar5ForwardReader(split, null, spool, RarVolumeChain.index(split))) {
                assertEquals(original.path, reader.nextEntry().path);
                org.junit.Assert.assertThrows(IOException.class, () -> reader.drainCurrentEntry(11));
                assertEquals(0, spool.list().length);
                // Neither an unknown nor a partial head replaces the final logical size.
                assertTrue(reader.drainCurrentEntry(12));
                org.junit.Assert.assertNull(reader.nextEntry());
                assertEquals(0, spool.list().length);
            }
        }
    }

    @Test public void unknownSplitPrimerPreservesSolidHistory() throws Exception {
        File archive = writeFixture("unknown-solid.rar", SOLID_CBR_HEX);
        java.util.List<RarArchiveReader.RarEntry> originals = RarArchiveReader.readEntries(archive, null);
        java.util.List<RarArchiveReader.RarEntry> all = splitForRegression(originals.get(0), false);
        all.set(0, withUnpackedSize(all.get(0), -1));
        all.add(originals.get(1));
        File selected = temp.newFile();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(originals.get(1), all, selected, null, null));
        assertExtractedFile(selected, 87, PNG_CRC);
    }

    @Test public void unknownSplitHeadStillRollsBackBadFinalCrc() throws Exception {
        File archive = writeFixture("unknown-bad-crc.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive, null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original, true);
        split.set(0, withUnpackedSize(split.get(0), -1));
        File selected = temp.newFile();
        Files.write(selected.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(IOException.class,
                () -> Rar5CompressedArchiveExtractor.tryExtractEntry(split.get(0), split, selected, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(selected.toPath()));
    }

    private static RarArchiveReader.RarEntry withUnpackedSize(RarArchiveReader.RarEntry original, long size) {
        RarArchiveReader.RarEntry copy = new RarArchiveReader.RarEntry(original.path, original.directory,
                size, original.packedSize, original.dataOffset, original.rarVersion, original.method,
                original.solid, original.splitBefore, original.splitAfter, original.encryption,
                original.dataCrc, original.timeMillis, original.rar5CompressionInfo, original.blake2sp,
                original.archiveSolid);
        copy.sourceArchive = original.sourceArchive;
        return copy;
    }

    @Test public void zeroByteSplitHeadUsesCompletePayloadAcrossExtractionRoutes() throws Exception {
        File archive = writeFixture("zero-head-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive, null).get(0);
        for (long headSize : new long[]{-1, 0, 3, original.unpackedSize}) {
            java.util.List<RarArchiveReader.RarEntry> entries = splitWithZeroHead(original, headSize);
            File selected = temp.newFile();
            assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(0), entries, selected, null, null));
            assertExtractedFile(selected, 12, TXT_CRC);
            File bulk = temp.newFolder();
            assertTrue(Rar5CompressedArchiveExtractor.tryExtractArchive(entries, bulk, null, null, null));
            assertExtractedFile(new File(bulk, original.path), 12, TXT_CRC);
            File spool = temp.newFolder();
            try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries))) {
                reader.nextEntry();
                org.junit.Assert.assertThrows(IOException.class, () -> reader.drainCurrentEntry(11));
                assertEquals(0, spool.list().length);
                assertTrue(reader.drainCurrentEntry(12));
                org.junit.Assert.assertNull(reader.nextEntry());
            }
        }
    }

    @Test public void zeroByteSplitPrimerStillSuppliesHistoryForTheNextSolidMember() throws Exception {
        File archive = writeFixture("zero-head-solid.rar", SOLID_CBR_HEX);
        java.util.List<RarArchiveReader.RarEntry> originals = RarArchiveReader.readEntries(archive, null);
        java.util.List<RarArchiveReader.RarEntry> entries = splitWithZeroHead(originals.get(0), -1);
        entries.add(originals.get(1));
        File target = temp.newFile();
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(originals.get(1), entries, target, null, null));
        assertExtractedFile(target, 87, PNG_CRC);
        File spool = temp.newFolder();
        try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries))) {
            reader.nextEntry(); // Implicit drain must not confuse the empty segment with an empty primer.
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardPayload(reader, 87, PNG_CRC);
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void zeroTotalSplitPayloadUsesFinalSizeAndPreflightsBeforeOutputCreation() throws Exception {
        for (int fault = 0; fault < 4; fault++) {
            RarArchiveReader.RarEntry original = new RarArchiveReader.RarEntry("empty", false,
                    fault == 1 ? -1 : fault == 2 ? 1 : 0, 0, 0, 5, 1, false,
                    false, false, null, 0, 0, 0x80L, null, fault != 3);
            original.sourceArchive = temp.newFile();
            java.util.List<RarArchiveReader.RarEntry> entries = splitWithZeroHead(original, -1);
            File parent = new File(temp.getRoot(), "zero-total-output-" + fault);
            File target = new File(parent, "empty");
            File spool = new File(temp.getRoot(), "zero-total-spool-" + fault);
            if (fault == 0) {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(0), entries, target, null, null));
                assertTrue(target.isFile()); assertEquals(0, target.length());
                try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries))) {
                    reader.nextEntry(); assertTrue(reader.drainCurrentEntry(0));
                }
                assertFalse(spool.exists());
            } else {
                org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                        entries.get(0), entries, target, null, null));
                assertFalse(parent.exists());
                try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries))) {
                    reader.nextEntry();
                    org.junit.Assert.assertThrows(IOException.class, () -> reader.read(new byte[1]));
                    org.junit.Assert.assertThrows(IOException.class, reader::nextEntry);
                }
                assertFalse(spool.exists());
            }
        }
    }

    @Test public void zeroByteSplitHeadStillChecksCrcHashAndPhysicalRange() throws Exception {
        File archive = writeFixture("zero-head-checked.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive, null).get(0);
        for (int fault = 0; fault < 5; fault++) {
            java.util.List<RarArchiveReader.RarEntry> entries = splitWithZeroHead(original, -1);
            RarArchiveReader.RarEntry first = entries.get(0);
            byte[] hash = new RarBlake2sp().digest();
            if (fault == 1) hash[0] ^= 1;
            RarArchiveReader.RarEntry changed = new RarArchiveReader.RarEntry(first.path, false,
                    -1, 0, fault == 2 ? 1 : 0, 5, first.method, first.solid, false, true, null,
                    fault == 0 ? 1 : 0, 0, first.rar5CompressionInfo, hash, first.archiveSolid);
            changed.sourceArchive = fault == 3 ? new File(temp.getRoot(), "missing-head") : first.sourceArchive;
            entries.set(0, changed);
            File target = temp.newFile(); Files.write(target.toPath(), new byte[]{42});
            if (fault == 4) {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(changed, entries, target, null, null));
                assertExtractedFile(target, 12, TXT_CRC);
            } else {
                org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                        changed, entries, target, null, null));
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
                File spool = temp.newFolder();
                try (Rar5ForwardReader reader = new Rar5ForwardReader(entries, null, spool, RarVolumeChain.index(entries))) {
                    reader.nextEntry();
                    org.junit.Assert.assertThrows(IOException.class, () -> reader.drainCurrentEntry(Long.MAX_VALUE));
                    org.junit.Assert.assertThrows(IOException.class, reader::nextEntry);
                }
                assertEquals(0, spool.list().length);
            }
        }
    }

    @Test public void missingContinuationAfterZeroByteHeadFailsBeforeCreatingOutput() throws Exception {
        File archive = writeFixture("zero-head-missing-tail.rar", SOLID_RAR_HEX);
        java.util.List<RarArchiveReader.RarEntry> entries = splitWithZeroHead(
                RarArchiveReader.readEntries(archive, null).get(0), -1);
        entries.remove(1);
        File parent = new File(temp.getRoot(), "missing-tail-output");
        org.junit.Assert.assertThrows(IOException.class, () -> Rar5CompressedArchiveExtractor.tryExtractEntry(
                entries.get(0), entries, new File(parent, "file"), null, null));
        assertFalse(parent.exists());
    }

    @Test public void zeroByteHeadInNumberedVolumesReachesPublicAndNativePreferredForwardPaths() throws Exception {
        File originalFile = writeFixture("zero-head-container-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(originalFile, null).get(0);
        byte[] originalBytes = Files.readAllBytes(originalFile.toPath());
        for (boolean unknownHead : new boolean[]{false, true}) {
            File directory = temp.newFolder();
            File[] volumes = {new File(directory, "sample.part01.rar"), new File(directory, "sample.part02.rar")};
            for (int i = 0; i < 2; i++) {
                boolean first = i == 0;
                ByteArrayOutputStream archive = new ByteArrayOutputStream();
                archive.write(hexToBytes("526172211a070100"));
                ByteArrayOutputStream fields = new ByteArrayOutputStream();
                writeFixtureVint(fields, 1); writeFixtureVint(fields, 0);
                writeFixtureVint(fields, first ? 1 : 3); // Volume, then volume + number-present.
                if (!first) writeFixtureVint(fields, 1);
                writeUnknownFixtureHeader(archive, fields);
                fields.reset();
                writeFixtureVint(fields, 2); writeFixtureVint(fields, first ? 0x12 : 0x0a);
                writeFixtureVint(fields, first ? 0 : original.packedSize);
                writeFixtureVint(fields, first && unknownHead ? 12 : 4);
                writeFixtureVint(fields, original.unpackedSize); writeFixtureVint(fields, 0);
                long crc = first ? 0 : original.dataCrc;
                for (int b = 0; b < 4; b++) fields.write((int) (crc >>> (8 * b)));
                writeFixtureVint(fields, original.rar5CompressionInfo); writeFixtureVint(fields, 0);
                byte[] name = original.path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writeFixtureVint(fields, name.length); fields.write(name);
                writeUnknownFixtureHeader(archive, fields);
                if (!first) archive.write(originalBytes, (int) original.dataOffset, (int) original.packedSize);
                fields.reset();
                writeFixtureVint(fields, 5); writeFixtureVint(fields, 0); writeFixtureVint(fields, first ? 1 : 0);
                writeUnknownFixtureHeader(archive, fields);
                Files.write(volumes[i].toPath(), archive.toByteArray());
            }
            java.util.List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(volumes[1], null);
            assertEquals(2, entries.size());
            assertEquals(0, entries.get(0).packedSize);
            assertFalse(entries.get(0).archiveSolid);
            File target = temp.newFile();
            assertTrue(RarArchiveReader.extractSingleEntry(volumes[1], original.path, target, null));
            assertExtractedFile(target, 12, TXT_CRC);
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader =
                    Rar5CompressedArchiveExtractor.openForwardReader(volumes[1], null, spool, true)) {
                org.junit.Assert.assertNotNull(reader);
                assertEquals(original.path, reader.nextEntry().path);
                assertForwardPayload(reader, 12, TXT_CRC);
                org.junit.Assert.assertNull(reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    private java.util.List<RarArchiveReader.RarEntry> splitWithZeroHead(
            RarArchiveReader.RarEntry original, long headSize) throws Exception {
        java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            RarArchiveReader.RarEntry part = new RarArchiveReader.RarEntry(original.path, false,
                    i == 0 ? headSize : original.unpackedSize, i == 0 ? 0 : original.packedSize,
                    i == 0 ? 0 : original.dataOffset, 5, original.method, original.solid, i != 0, i == 0,
                    null, i == 0 ? 0 : original.dataCrc, original.timeMillis, original.rar5CompressionInfo,
                    i == 0 ? null : original.blake2sp, original.archiveSolid);
            part.sourceArchive = i == 0 ? temp.newFile() : original.sourceArchive;
            entries.add(part);
        }
        return entries;
    }

    @Test
    public void splitCompressedFileUsesTheFinalUnpackedCrc() throws Exception {
        File archive = writeFixture("split-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original, false);
        File out = new File(temp.getRoot(), "split-decoded.txt");
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(
                split.get(0), split, out, null, null));
        assertExtractedFile(out, 12, TXT_CRC);
    }

    @Test
    public void splitCompressedFileRejectsBadFinalCrcBeforeCommitting() throws Exception {
        File archive = writeFixture("split-bad-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original, true);
        File out = new File(temp.getRoot(), "bad-final.txt");
        try {
            Rar5CompressedArchiveExtractor.tryExtractEntry(split.get(0), split, out, null, null);
            throw new AssertionError("Bad final CRC must fail");
        } catch (IOException expected) {
            assertTrue("No output may be committed", !out.exists());
        }
    }

    @Test
    public void splitPredecessorPrimesSolidTargetExactlyOnce() throws Exception {
        File archive = writeFixture("split-solid-source.rar", SOLID_CBR_HEX);
        java.util.List<RarArchiveReader.RarEntry> originals =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(originals.get(0), false);
        java.util.List<RarArchiveReader.RarEntry> all = new java.util.LinkedList<>(split);
        all.add(0, new RarArchiveReader.RarEntry("unrelated-orphan", false, 1, 1, 0,
                5, 0, false, true, false, null, 0, 0, 0));
        all.add(originals.get(1));
        java.lang.reflect.Method plan = Rar5CompressedArchiveExtractor.class.getDeclaredMethod(
                "prepareSolidPlan", RarArchiveReader.RarEntry.class, java.util.List.class);
        plan.setAccessible(true);
        RarSolidReadPlan prepared = (RarSolidReadPlan) plan.invoke(null, originals.get(1), all);
        assertEquals(2, prepared.steps.size());
        assertTrue(prepared.steps.get(0).entry == split.get(0));
        assertTrue(prepared.steps.get(1).entry == originals.get(1));
        assertEquals(split, prepared.steps.get(0).parts);
        File out = new File(temp.getRoot(), "split-solid.png");
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(
                originals.get(1), all, out, null, null));
        assertExtractedFile(out, 87, PNG_CRC);
    }

    @Test public void splitCompressedRejectsBadPackedCrcEvenWhenDecodedFileCrcIsValid() throws Exception {
        File archive = writeFixture("bad-packed-crc.rar",SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive,null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original,false);
        RarArchiveReader.RarEntry first = split.get(0);
        RarArchiveReader.RarEntry bad = new RarArchiveReader.RarEntry(first.path,false,
                first.unpackedSize,first.packedSize,first.dataOffset,5,first.method,first.solid,
                false,true,null,first.dataCrc^1,first.timeMillis,first.rar5CompressionInfo);
        bad.sourceArchive = first.sourceArchive;
        split.set(0,bad);
        File out = new File(temp.getRoot(),"bad-packed-output.txt");
        try { Rar5CompressedArchiveExtractor.tryExtractEntry(bad,split,out,null,null);
            throw new AssertionError("Intermediate packed CRC must be checked");
        } catch (IOException expected) { assertTrue(!out.exists()); }
    }

    private java.util.List<RarArchiveReader.RarEntry> splitForRegression(
            RarArchiveReader.RarEntry original, boolean badFinalCrc) throws Exception {
        byte[] packed = new byte[(int) original.packedSize];
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(original.sourceArchive, "r")) {
            input.seek(original.dataOffset);
            input.readFully(packed);
        }
        int cut = packed.length / 2;
        byte[][] segments = {java.util.Arrays.copyOfRange(packed, 0, cut),
                java.util.Arrays.copyOfRange(packed, cut, packed.length)};
        java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            File volume = temp.newFile("payload-" + i + ".bin");
            Files.write(volume.toPath(), segments[i]);
            CRC32 packedCrc = new CRC32();
            packedCrc.update(segments[i]);
            long crc = i == 0 ? packedCrc.getValue()
                    : (badFinalCrc ? original.dataCrc ^ 1L : original.dataCrc);
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                    original.path, false, original.unpackedSize, segments[i].length, 0,
                    5, original.method, original.solid, i != 0, i == 0, null,
                    crc, original.timeMillis, original.rar5CompressionInfo);
            entry.sourceArchive = volume;
            entries.add(entry);
        }
        return entries;
    }

    @Test
    public void compressedTxtEntryDecodesWithCrcMatch() throws Exception {
        File archive = writeFixture("solid.rar", SOLID_RAR_HEX);
        File outDir = temp.newFolder("txt");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.txt"), 12, TXT_CRC);
    }

    @Test
    public void mixedStoredAndCompressedCbrExtractsBothImages() throws Exception {
        File archive = writeFixture("plain.cbr", CBR_HEX);
        File outDir = temp.newFolder("cbr");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.jpg"), 220, JPG_CRC);
        assertExtractedFile(new File(outDir, "testfile.png"), 87, PNG_CRC);
    }

    @Test
    public void solidCbrExtractsBothImagesWithWindowCarryover() throws Exception {
        File archive = writeFixture("solid.cbr", SOLID_CBR_HEX);
        File outDir = temp.newFolder("solidcbr");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.jpg"), 220, JPG_CRC);
        assertExtractedFile(new File(outDir, "testfile.png"), 87, PNG_CRC);
    }

    @Test
    public void forwardReaderCarriesSolidHistoryAndCleansSpools() throws Exception {
        File archive = writeFixture("forward-solid.cbr", SOLID_CBR_HEX);
        File spool = temp.newFolder("forward-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertForwardPayload(reader, 220, JPG_CRC);
            assertEquals(1, spool.list().length);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertEquals(0, spool.list().length);
            assertForwardPayload(reader, 87, PNG_CRC);
        }
        assertEquals(0, spool.list().length);
    }

    @Test
    public void forwardReaderDrainsSolidPrimerWithoutSpoolingIt() throws Exception {
        File archive = writeFixture("forward-skip.cbr", SOLID_CBR_HEX);
        File spool = temp.newFolder("skip-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            reader.nextEntry();
            assertTrue(reader.drainCurrentEntry(Long.MAX_VALUE));
            assertEquals(0, spool.list().length);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardPayload(reader, 87, PNG_CRC);
        }
        assertEquals(0, spool.list().length);
    }

    private static void assertForwardPayload(ArchiveSupport.ForwardArchiveReader reader,
            int length, long expectedCrc) throws Exception {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buffer = new byte[37];
        int count, total = 0;
        while ((count = reader.read(buffer)) != -1) { crc.update(buffer, 0, count); total += count; }
        assertEquals(length, total);
        assertEquals(expectedCrc, crc.getValue());
    }

    @Test
    public void singleEntryExtractionOfSolidMemberPrimesPredecessors() throws Exception {
        File archive = writeFixture("solid2.cbr", SOLID_CBR_HEX);
        File out = new File(temp.newFolder("single"), "testfile.png");
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "testfile.png", out, null));
        assertExtractedFile(out, 87, PNG_CRC);
    }

    @Test
    public void singleEntryExtractionOfNonSolidCompressedMemberWorks() throws Exception {
        File archive = writeFixture("solid3.cbr", SOLID_CBR_HEX);
        File out = new File(temp.newFolder("single2"), "testfile.jpg");
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "testfile.jpg", out, null));
        assertExtractedFile(out, 220, JPG_CRC);
    }

    @Test
    public void solidEntryWithoutPrimerIsRejectedNotFaked() throws Exception {
        byte[] cbr = hexToBytes(SOLID_CBR_HEX);
        // The solid png entry payload starts at 0x151 and is 62 bytes;
        // compression info 0x0ec0 declares solid + 1 MiB dictionary.
        byte[] packed = new byte[62];
        System.arraycopy(cbr, 0x151, packed, 0, 62);
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        try {
            decoder.decodeEntry(packed, 87, 0x0ec0L);
            fail("Solid entry without a primed window must not decode");
        } catch (Rar5CompressedDecoder.Rar5DataException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("solid"));
        }
    }

    @Test
    public void corruptedCompressedPayloadFailsInsteadOfReportingSuccess() throws Exception {
        byte[] cbr = hexToBytes(SOLID_CBR_HEX);
        byte[] packed = new byte[236];
        System.arraycopy(cbr, 0x3e, packed, 0, 236);
        packed[40] ^= 0x5A; // corrupt mid-stream
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        boolean failed;
        try {
            byte[] data = decoder.decodeEntry(packed, 220, 0x0e80L);
            CRC32 crc = new CRC32();
            crc.update(data);
            failed = crc.getValue() != JPG_CRC;
        } catch (Rar5CompressedDecoder.Rar5DataException expected) {
            failed = true;
        }
        assertTrue("Corrupted payload must fail decode or CRC, never report a clean result", failed);
    }

    @Test
    public void rar7V0SolidCompatibilityMarkerDecodesExistingV0Stream() {
        byte[] cbr = hexToBytes(CBR_HEX);
        byte[] packed = new byte[236];
        System.arraycopy(cbr, 0x3e, packed, 0, packed.length);

        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        byte[] data = decoder.decodeEntry(
                packed, 220, 0x0e80L | 1L | 0x100000L);
        CRC32 crc = new CRC32();
        crc.update(data);
        assertEquals(JPG_CRC, crc.getValue());
    }

    @Test
    public void rar7VersionOneParsesExtendedDictionaryAndDistanceGeometry() {
        long fortyEightMiB = 1L
                | (8L << 10)   // 128 KiB * 2^8 = 32 MiB
                | (16L << 15); // plus 16/32 = 16 MiB
        assertEquals(48L * 1024 * 1024,
                Rar5CompressedDecoder.declaredWindowSize(fortyEightMiB));
        assertTrue(Rar5CompressedDecoder.usesExtendedDistanceTable(fortyEightMiB));
        assertTrue(Rar5CompressedArchiveExtractor.isSupportedCompressionInfo(fortyEightMiB));

        long oneTiB = 1L | (23L << 10);
        assertEquals(1L << 40, Rar5CompressedDecoder.declaredWindowSize(oneTiB));

        long v0SolidCompatibility = fortyEightMiB | 0x100000L;
        assertFalse(Rar5CompressedDecoder.usesExtendedDistanceTable(v0SolidCompatibility));
        assertTrue(Rar5CompressedArchiveExtractor.isSupportedCompressionInfo(
                v0SolidCompatibility));
    }

    @Test
    public void rar7ExtendedDistanceStreamUsesBoundedWindowAndZeroFillsVoid() {
        long oneTiBMethodOne = 1L | 0x80L | (23L << 10);
        byte[] decoded = new Rar5CompressedDecoder().decodeEntry(
                buildRar7ExtendedDistancePayload(), 6, oneTiBMethodOne);
        assertArrayEquals(new byte[] {'A', 0, 0, 0, 0, 0}, decoded);
    }

    @Test
    public void decoderStillRejectsUnknownPostRar7AlgorithmVersions() {
        assertFalse(Rar5CompressedArchiveExtractor.isSupportedCompressionInfo(2L));
        try {
            Rar5CompressedDecoder.declaredWindowSize(2L);
            fail("Unknown algorithm versions must be rejected");
        } catch (Rar5CompressedDecoder.Rar5DataException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("version"));
        }
    }

    // ---- external fixtures (assume-gated; the rar-test-files build dir) ----

    @Test
    public void extractionEligibilityDoesNotImposeFormerPackedOrUnpackedCaps() throws Exception {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "large.bin", false, (long) Integer.MAX_VALUE + 1, 65L * 1024 * 1024,
                0, 5, 1, false, false, false, null, -1, 0, 0x80L);
        entry.sourceArchive = new File(temp.getRoot(), "large.rar");
        java.lang.reflect.Method eligible = Rar5CompressedArchiveExtractor.class.getDeclaredMethod(
                "isEligibleCompressed", RarArchiveReader.RarEntry.class);
        eligible.setAccessible(true);
        assertEquals(Boolean.TRUE, eligible.invoke(null, entry));
    }

    @Test
    public void streamingEntryAcceptsLongSizeAndPropagatesOutputFailure() throws Exception {
        IOException stop = new IOException("deliberate output failure");
        try {
            new Rar5CompressedDecoder().decodeEntry(
                    new java.io.ByteArrayInputStream(buildRepeatedLiteralBlock(false)),
                    (long) Integer.MAX_VALUE + 123L, 0x80L, new java.io.OutputStream() {
                        @Override public void write(int value) throws IOException { throw stop; }
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                            assertEquals(64 * 1024, length);
                            throw stop;
                        }
                    });
            fail("Output failure must propagate");
        } catch (IOException expected) {
            org.junit.Assert.assertSame(stop, expected);
        }
    }

    @Test
    public void streamingFiltersAtZeroAndAdjacentRangesAreAppliedBeforeOutput() throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) data.write(i == 0 || i == 9 ? 1 : 0);
        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // main A
        writeZeroLengths(bits, 190);
        bits.write(0, 1); // main filter (256)
        writeZeroLengths(bits, 49);
        bits.write(0, 1); // distance 0
        writeZeroLengths(bits, 123); // remaining distance/low-distance/repetition tables
        for (int start : new int[] {0, 4}) {
            bits.write(1, 1); // filter
            bits.write(0, 2); bits.write(start, 8);
            bits.write(0, 2); bits.write(4, 8);
            bits.write(0, 3); // delta
            bits.write(0, 5); // one channel
        }
        bits.write(0, 8); // eight A literals
        byte[] tail = bits.toByteArray();
        data.write(tail);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        new Rar5CompressedDecoder().decodeEntry(new java.io.ByteArrayInputStream(
                frameBlock(data.toByteArray(), bits.bitCount(), true)), 8, 0x80L, decoded);
        assertArrayEquals(new byte[] {(byte) 191, 126, 61, (byte) 252,
                (byte) 191, 126, 61, (byte) 252}, decoded.toByteArray());
    }

    @Test
    public void streamingCrossesBlockBoundariesAndCarriesSolidHistory() throws Exception {
        byte[] first = buildRepeatedLiteralBlock(false);
        byte[] last = buildRepeatedLiteralBlock(true);
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        decoder.decodeEntry(new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(first), new java.io.ByteArrayInputStream(last)),
                2L * (1 + 32 * 4097), 0x80L, result);
        assertEquals(2 * (1 + 32 * 4097), result.size());
        for (byte value : result.toByteArray()) assertEquals('A', value);
        result.reset();
        decoder.decodeEntry(new java.io.ByteArrayInputStream(last), 1 + 32 * 4097,
                0xC0L, result);
        assertEquals(1 + 32 * 4097, result.size());
    }

    @Test public void shortRar5FilterBlocksDecodeWithoutAUniversalFourByteMinimum() throws Exception {
        for (int size=1;size<=3;size++) {
            for (int type=0;type<=3;type++) {
                byte[] expected = new byte[size];
                for (int i=0;i<size;i++) expected[i]=(byte)(type==0?-65*(i+1):65);
                assertArrayEquals(expected,decodeFilters(filterBlock(false,true,size,
                        new int[]{0,size,type,1}),size,0x80L));
            }
            byte[] expected = new byte[size]; java.util.Arrays.fill(expected,(byte)-65);
            assertArrayEquals(expected,decodeFilters(filterBlock(false,true,size,
                    new int[]{0,size,0,32}),size,0x80L));
        }
    }

    @Test public void sameBlockRar5FiltersRunInDeclarationOrder() throws Exception {
        assertArrayEquals(new byte[]{65,(byte)195,(byte)195,(byte)199},
                decodeFilters(filterBlock(false,true,4,new int[]{0,4,0,1},new int[]{0,4,0,2}),4,0x80L));
        assertArrayEquals(new byte[]{65,(byte)130,4,(byte)134},
                decodeFilters(filterBlock(false,true,4,new int[]{0,4,0,2},new int[]{0,4,0,1}),4,0x80L));
    }

    @Test public void rar5BranchFiltersReuseOwnedBlocksAndPreserveOperandsAndTails() throws Exception {
        for (int type : new int[]{1,2,3}) {
            byte[] data = type==3 ? new byte[]{8,0,0,(byte)0xeb,4,0,0,(byte)0xea,42}
                    : new byte[]{(byte)0xe8,20,0,0,0,(byte)0xe9,30,0,0,0,42};
            byte[] expected = type==3 ? new byte[]{7,0,0,(byte)0xeb,4,0,0,(byte)0xea,42}
                    : new byte[]{(byte)0xe8,14,0,0,0,(byte)0xe9,(byte)(type==2?19:30),0,0,0,42};
            Class<?> info = Class.forName(Rar5CompressedDecoder.class.getName()+"$FilterInfo");
            java.lang.reflect.Constructor<?> constructor = info.getDeclaredConstructor(); constructor.setAccessible(true);
            Object filter = constructor.newInstance();
            java.lang.reflect.Field field = info.getDeclaredField("type"); field.setAccessible(true); field.setInt(filter,type);
            field=info.getDeclaredField("blockLength"); field.setAccessible(true); field.setInt(filter,data.length);
            field=info.getDeclaredField("blockStart"); field.setAccessible(true); field.setLong(filter,type==3?4:5);
            java.lang.reflect.Method apply = Rar5CompressedDecoder.class.getDeclaredMethod("applyFilter",info,byte[].class);
            apply.setAccessible(true);
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                org.junit.Assert.assertSame(data,apply.invoke(decoder,filter,data));
            }
            assertArrayEquals(expected,data);
        }
    }

    @Test public void chainsCanFollowPlainPrefixesAndAdjacentFilterRanges() throws Exception {
        byte[] expected = {65,65,65,(byte)195,(byte)134,(byte)138,(byte)191,126};
        assertArrayEquals(expected,decodeFilters(filterBlock(false,true,8,
                new int[]{2,4,0,1},new int[]{2,4,0,1},new int[]{6,2,0,1},new int[]{6,2,1,1}),8,0x80L));
    }

    @Test public void oneFilterChainCanSpanCompressedBlockBoundaries() throws Exception {
        byte[] packed = joinFilterBlocks(
                filterBlock(false,false,2,new int[]{0,4,0,1},new int[]{0,4,0,1}),
                filterBlock(false,true,2));
        assertArrayEquals(new byte[]{65,(byte)195,(byte)134,(byte)138},decodeFilters(packed,4,0x80L));
    }

    @Test public void rar7ExtendedAndV0CompatibleTablesUseTheSameFilterChain() throws Exception {
        for (long info : new long[]{0x81L,0x100081L}) {
            boolean extended = Rar5CompressedDecoder.usesExtendedDistanceTable(info);
            assertArrayEquals(new byte[]{65,(byte)195,(byte)134,(byte)138},decodeFilters(
                    filterBlock(extended,true,4,new int[]{0,4,0,1},new int[]{0,4,0,1}),4,info));
        }
    }

    @Test public void filterChainsRejectPartialOverlapsDifferentLengthsAndInvalidRanges() throws Exception {
        for (int[][] records : new int[][][]{
                {{0,4,0,1},{1,4,0,1}}, {{0,4,0,1},{0,3,0,1}},
                {{4,4,0,1},{0,4,0,1}}, {{0,0,0,1}}, {{7,2,0,1}},
                {{-1,1,0,1}}, {{0,1,4,1}}
        }) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
                byte[] packed = filterBlock(false,true,8,records);
                org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                        ()->decoder.decodeEntry(new java.io.ByteArrayInputStream(packed),8,0x80L,output));
                assertEquals(0,output.size());
            }
        }
    }

    @Test public void pendingFilterMetadataBudgetRejectsBeforeOutputAndAllowsFreshRestart() throws Exception {
        int[][] records = new int[Rar5CompressedDecoder.MAX_PENDING_FILTERS+1][];
        java.util.Arrays.fill(records,new int[]{0,1,1,1});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            byte[] packed = filterBlock(false,true,1,records);
            Rar5CompressedDecoder.Rar5DataException error = org.junit.Assert.assertThrows(
                    Rar5CompressedDecoder.Rar5DataException.class,
                    ()->decoder.decodeEntry(new java.io.ByteArrayInputStream(packed),1,0x80L,output));
            assertTrue(error.getMessage().contains("metadata")); assertEquals(0,output.size());
            decoder.decodeEntry(new java.io.ByteArrayInputStream(filterBlock(false,true,1)),1,0x80L,output);
            assertArrayEquals(new byte[]{65},output.toByteArray());
        }
    }

    @Test public void maximumQueuedShortChainsAreIterativeAndReleaseDescriptors() throws Exception {
        int[][] records = new int[Rar5CompressedDecoder.MAX_PENDING_FILTERS][];
        java.util.Arrays.fill(records,new int[]{0,1,1,1});
        byte[] packed = joinFilterBlocks(filterBlock(false,false,1,records),filterBlock(false,true,1,records));
        assertArrayEquals(new byte[]{65,65},decodeFilters(packed,2,0x80L));
    }

    @Test public void truncatedDeltaChannelRecordDoesNotLeaveReusableFilterState() throws Exception {
        ByteArrayOutputStream data = filterAlphabet(); BitWriter bits = filterTables(false);
        bits.write(1,1); filterInteger(bits,0); filterInteger(bits,1); bits.write(0,3);
        data.write(bits.toByteArray()); // no five-bit channel field
        byte[] packed = frameBlock(data.toByteArray(),bits.bitCount(),true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    ()->decoder.decodeEntry(new java.io.ByteArrayInputStream(packed),1,0x80L,output));
            assertEquals(0,output.size());
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    ()->decoder.decodeEntry(new java.io.ByteArrayInputStream(filterBlock(false,true,1)),1,0xc0L,output));
        }
    }

    @Test public void filterCancellationRetiresSolidContinuationBeforeFilteredOutput() throws Exception {
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        java.io.OutputStream destination = new java.io.OutputStream() {
            @Override public void write(int value) { accepted.write(value); Thread.currentThread().interrupt(); }
            @Override public void write(byte[] bytes,int offset,int length) {
                accepted.write(bytes,offset,length); Thread.currentThread().interrupt();
            }
        };
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            byte[] packed = filterBlock(false,true,5,new int[]{1,4,0,1},new int[]{1,4,0,1});
            try {
                org.junit.Assert.assertThrows(IOException.class,
                        ()->decoder.decodeEntry(new java.io.ByteArrayInputStream(packed),5,0x80L,destination));
            } finally { Thread.interrupted(); }
            assertArrayEquals(new byte[]{65},accepted.toByteArray());
            org.junit.Assert.assertThrows(Rar5CompressedDecoder.Rar5DataException.class,
                    ()->decoder.decodeEntry(new java.io.ByteArrayInputStream(filterBlock(false,true,1)),1,0xc0L,accepted));
        }
    }

    @Test public void filterChainDoesNotReplaceRawSolidHistory() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(new java.io.ByteArrayInputStream(filterBlock(false,true,4,
                    new int[]{0,4,0,1},new int[]{0,4,0,1})),4,0x80L,output);
            output.reset();
            ByteArrayOutputStream data = filterAlphabet(); BitWriter bits = new BitWriter();
            writeZeroLengths(bits,262); bits.write(0,1); // only main symbol 262: length 2
            writeZeroLengths(bits,43); bits.write(0,1); // distance slot 0: distance 1
            writeZeroLengths(bits,123);
            bits.write(0,1); bits.write(0,1); // one two-byte match, no new literal
            data.write(bits.toByteArray());
            decoder.decodeEntry(new java.io.ByteArrayInputStream(frameBlock(data.toByteArray(),bits.bitCount(),true)),
                    2,0xc0L,output);
            assertArrayEquals(new byte[]{65,65},output.toByteArray());
        }
    }

    @Test public void sameBlockChainsPassSelectedAesSplitIntegrityAndRollback() throws Exception {
        char[] password = "filter-test".toCharArray(); // synthetic public fixture password
        byte[] expected = {65,(byte)195,(byte)134,(byte)138};
        CRC32 finalCrc = new CRC32(); finalCrc.update(expected);
        for (boolean extended : new boolean[]{false,true}) {
        for (boolean encrypted : new boolean[]{false,true}) {
        for (boolean split : new boolean[]{false,true}) {
        for (boolean corrupt : new boolean[]{false,true}) {
            byte[] packed = filterBlock(extended,true,4,new int[]{0,4,0,1},new int[]{0,4,0,1});
            RarArchiveReader.EncryptionInfo encryption = null;
            if (encrypted) {
                encryption = new RarArchiveReader.EncryptionInfo(0,0,1,new byte[16],new byte[16],new byte[0]);
                Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(password,1,encryption.salt);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(secrets.key,"AES"),
                        new javax.crypto.spec.IvParameterSpec(encryption.iv));
                packed = cipher.doFinal(java.util.Arrays.copyOf(packed,(packed.length+15)&~15));
            }
            java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
            int count = split?2:1;
            for (int i=0;i<count;i++) {
                byte[] part = java.util.Arrays.copyOfRange(packed,i==0?0:17,split&&i==0?17:packed.length);
                CRC32 partCrc = new CRC32(); partCrc.update(part);
                long crc = i==count-1?(finalCrc.getValue()^(corrupt?1:0)):partCrc.getValue();
                File source = temp.newFile(); Files.write(source.toPath(),part);
                RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("data.bin",false,4,part.length,0,
                        5,1,false,i!=0,i!=count-1,encryption,crc,0,extended?0x81L:0x80L);
                entry.sourceArchive=source; entries.add(entry);
            }
            File target = temp.newFile(); Files.write(target.toPath(),new byte[]{42});
            if (corrupt) {
                org.junit.Assert.assertThrows(IOException.class,()->Rar5CompressedArchiveExtractor.tryExtractEntry(
                        entries.get(0),entries,target,password,null));
                assertArrayEquals(new byte[]{42},Files.readAllBytes(target.toPath()));
            } else {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entries.get(0),entries,target,password,null));
                assertArrayEquals(expected,Files.readAllBytes(target.toPath()));
            }
        }}}}
    }

    private static byte[] decodeFilters(byte[] packed,int size,long info) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(new java.io.ByteArrayInputStream(packed),size,info,output);
        }
        return output.toByteArray();
    }

    /** Hand-encoded grammar, not a real RAR producer fixture. Records: start/length/type/channels. */
    private static byte[] filterBlock(boolean extended,boolean last,int literals,int[]... records) throws IOException {
        ByteArrayOutputStream data = filterAlphabet(); BitWriter bits = filterTables(extended);
        for (int[] record : records) {
            bits.write(1,1); filterInteger(bits,record[0]); filterInteger(bits,record[1]);
            bits.write(record[2],3);
            if (record[2]==0) bits.write(record[3]-1,5);
        }
        for (int i=0;i<literals;i++) bits.write(0,1);
        data.write(bits.toByteArray());
        return frameBlock(data.toByteArray(),bits.bitCount(),last);
    }

    private static ByteArrayOutputStream filterAlphabet() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i=0;i<10;i++) data.write(i==0||i==9?1:0);
        return data;
    }

    private static BitWriter filterTables(boolean extended) {
        BitWriter bits = new BitWriter();
        writeZeroLengths(bits,65); bits.write(0,1); // main A
        writeZeroLengths(bits,190); bits.write(0,1); // main filter
        writeZeroLengths(bits,49); bits.write(0,1); // distance 0
        writeZeroLengths(bits,extended?139:123);
        return bits;
    }

    private static void filterInteger(BitWriter bits,int value) {
        int bytes = value<0||value>0xffffff?4:value>0xffff?3:value>0xff?2:1;
        bits.write(bytes-1,2);
        for (int i=0;i<bytes;i++) bits.write(value>>>(8*i),8);
    }

    private static byte[] joinFilterBlocks(byte[] first,byte[] second) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(first); out.write(second);
        return out.toByteArray();
    }

    @Test
    public void largeStreamingOutputCrossesFormer256MiBLimitWhenEnabled() throws Exception {
        assumeTrue("Enable explicitly for a large streaming regression",
                Boolean.getBoolean("readwide.largeArchiveTests"));
        final int blocks = 2100;
        byte[] ordinary = buildRepeatedLiteralBlock(false);
        byte[] last = buildRepeatedLiteralBlock(true);
        java.io.InputStream generated = new java.io.InputStream() {
            int block;
            int offset;
            @Override public int read() {
                if (block == blocks) return -1;
                byte[] current = block == blocks - 1 ? last : ordinary;
                int value = current[offset++] & 0xff;
                if (offset == current.length) { offset = 0; block++; }
                return value;
            }
        };
        final long[] count = {0};
        long expected = blocks * (1L + 32 * 4097);
        assertTrue(expected > 256L * 1024 * 1024);
        new Rar5CompressedDecoder().decodeEntry(generated, expected, 0x80L,
                new java.io.OutputStream() {
                    @Override public void write(int value) { assertEquals('A', value); count[0]++; }
                    @Override public void write(byte[] bytes, int offset, int length) {
                        for (int i = offset; i < offset + length; i++) assertEquals('A', bytes[i]);
                        count[0] += length;
                    }
                });
        assertEquals(expected, count[0]);
    }

    @Test
    public void rar5SolidMatchBeyond64MiBUsesSpilledHistoryWhenEnabled() throws Exception {
        assertSolidLongHistory(0);
    }

    @Test
    public void rar7SolidMatchBeyond64MiBUsesSpilledHistoryWhenEnabled() throws Exception {
        assertSolidLongHistory(1);
    }

    private void assertSolidLongHistory(int algorithm) throws Exception {
        assumeTrue("Enable explicitly for a >64 MiB history regression",
                Boolean.getBoolean("readwide.largeArchiveTests"));
        byte[] ordinary = buildRepeatedLiteralBlock(false, algorithm == 1);
        byte[] last = buildRepeatedLiteralBlock(true, algorithm == 1);
        final int blocks = 512;
        long primedBytes = blocks * (1L + 32 * 4097);
        assertTrue(primedBytes > 64L * 1024 * 1024 + 1);
        java.io.InputStream input = new java.io.InputStream() {
            int block, offset;
            @Override public int read() {
                if (block == blocks) return -1;
                byte[] bytes = block == blocks - 1 ? last : ordinary;
                int value = bytes[offset++] & 255;
                if (offset == bytes.length) { offset = 0; block++; }
                return value;
            }
        };
        final long[] count = {0};
        java.io.OutputStream checked = new java.io.OutputStream() {
            @Override public void write(int value) { assertEquals('A', value); count[0]++; }
            @Override public void write(byte[] bytes, int offset, int length) {
                for (int i = offset; i < offset + length; i++) assertEquals('A', bytes[i]);
                count[0] += length;
            }
        };
        long info = (10L << 10) | 0x80L | algorithm; // 128 MiB logical dictionary.
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(input, primedBytes, info, checked);
            decoder.decodeEntry(new java.io.ByteArrayInputStream(buildLongHistoryMatchBlock(algorithm)),
                    6, info | 0x40L, checked);
        }
        assertEquals(primedBytes + 6, count[0]);
    }

    private static byte[] buildLongHistoryMatchBlock(int algorithm) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) data.write(i == 0 || i == 9 ? 1 : 0);
        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // literal A
        writeZeroLengths(bits, 196);
        bits.write(0, 1); // match symbol 262
        writeZeroLengths(bits, 95);
        bits.write(0, 1); // distance slot 52 => (2 << 25) + 1
        writeZeroLengths(bits, algorithm == 1 ? 27 : 11);
        bits.write(0, 1); // low distance 0
        writeZeroLengths(bits, 59);
        bits.write(0, 1); // literal A
        bits.write(1, 1); // match base length 2 + 3 distance increments
        bits.write(0, 1); // distance slot 52
        bits.write(0, 21);
        bits.write(0, 1); // low distance 0
        data.write(bits.toByteArray());
        byte[] block = data.toByteArray();
        int valid = bits.bitCount() % 8;
        int flags = 0xC0 | ((valid == 0 ? 8 : valid) - 1);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(flags);
        packed.write((0x5A ^ flags ^ block.length) & 255);
        packed.write(block.length);
        packed.write(block);
        return packed.toByteArray();
    }

    private static byte[] buildRepeatedLiteralBlock(boolean last) throws IOException {
        return buildRepeatedLiteralBlock(last, false);
    }

    private static byte[] buildRepeatedLiteralBlock(boolean last, boolean extended) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) data.write(i == 0 || i == 9 ? 1 : 0);
        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // main literal A, length 1
        writeZeroLengths(bits, 239);
        bits.write(0, 1); // main match 305, length 1
        bits.write(0, 1); // distance 0, length 1
        writeZeroLengths(bits, extended ? 139 : 123);
        bits.write(0, 1); // initial A
        for (int i = 0; i < 32; i++) {
            bits.write(1, 1); // length code 43
            bits.write(511, 9); // match length 4097
            bits.write(0, 1); // distance 1
        }
        data.write(bits.toByteArray());
        return frameBlock(data.toByteArray(), bits.bitCount(), last);
    }

    private static byte[] frameBlock(byte[] block, int tailBitCount, boolean last) throws IOException {
        int finalBits = tailBitCount & 7;
        if (finalBits == 0) finalBits = 8;
        int sizeBytes = block.length>0xffff?3:block.length>0xff?2:1;
        if (block.length>0xffffff) throw new AssertionError("Fixture exceeds RAR block framing");
        int flags = 0x80 | (last ? 0x40 : 0) | (sizeBytes-1)<<3 | finalBits - 1;
        int checksum = 0x5a ^ flags;
        for (int i=0;i<sizeBytes;i++) checksum ^= (block.length >>> (8*i)) & 255;
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(flags);
        packed.write(checksum);
        for (int i=0;i<sizeBytes;i++) packed.write(block.length >>> (8*i));
        packed.write(block);
        return packed.toByteArray();
    }

    @Test
    public void externalRar5FixturesAllExtractWhenProvided() throws Exception {
        File build = externalFixtureBuildDir();
        String[][] cases = {
                {"testfile.rar5.rar", "testfile.txt"},
                {"testfile.rar5.solid.rar", "testfile.txt"},
                {"testfile.rar5.cbr", "testfile.jpg"},
                {"testfile.rar5.solid.cbr", "testfile.png"},
                {"testfile.rar5.locked.cbr", "testfile.jpg"},
                {"testfile.rar5.rr.cbr", "testfile.png"},
        };
        for (String[] c : cases) {
            File archive = new File(build, c[0]);
            assumeTrue("Missing fixture: " + archive, archive.isFile());
            File outDir = temp.newFolder("ext_" + c[0].replace('.', '_'));
            assertTrue("extract failed: " + c[0],
                    RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
            assertTrue("missing " + c[1] + " from " + c[0],
                    new File(outDir, c[1]).isFile());
        }
    }

    // ---- helpers ----

    private void assertExtractedFile(File file, int expectedSize, long expectedCrc) throws IOException {
        assertTrue("missing output: " + file, file.isFile());
        byte[] data = Files.readAllBytes(file.toPath());
        assertEquals(expectedSize, data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        assertEquals(expectedCrc, crc.getValue());
    }

    private File writeFixture(String name, String hex) throws IOException {
        File fixture = temp.newFile(name);
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            out.write(hexToBytes(hex));
        }
        return fixture;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * Builds one version-1 block from the public RAR5-container grammar:
     * literal 'A', then a long-distance match using extended distance slot 64
     * (base length 2 plus the three standard distance-dependent increments).
     * The declared 1 TiB dictionary is never allocated; the match points into
     * the initial zero-filled portion of the logical window.
     */
    private static byte[] buildRar7ExtendedDistancePayload() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        // Bit-length alphabet: symbols 1 and 19 have one-bit codes (0 and 1).
        for (int i = 0; i < 10; i++) {
            data.write(i == 0 || i == 9 ? 0x01 : 0x00);
        }

        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // main literal 65 ('A') length 1
        writeZeroLengths(bits, 196);
        bits.write(0, 1); // main match symbol 262 length 1
        writeZeroLengths(bits, 107);
        bits.write(0, 1); // extended distance slot 64 length 1
        writeZeroLengths(bits, 15);
        bits.write(0, 1); // low-distance symbol 0 length 1
        writeZeroLengths(bits, 59);

        bits.write(0, 1);  // literal 'A'
        bits.write(1, 1);  // match symbol 262 => length 2
        bits.write(0, 1);  // distance slot 64
        bits.write(0, 27); // high bits of the 33-bit distance
        bits.write(0, 1);  // low-distance symbol 0
        byte[] tail = bits.toByteArray();
        data.write(tail, 0, tail.length);

        byte[] block = data.toByteArray();
        int finalBits = bits.bitCount() & 7;
        if (finalBits == 0) finalBits = 8;
        int flags = 0xC0 | (finalBits - 1); // last block + tables present
        int checksum = 0x5A ^ flags ^ block.length;
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(flags);
        packed.write(checksum & 0xFF);
        packed.write(block.length);
        packed.write(block, 0, block.length);
        return packed.toByteArray();
    }

    private static void writeZeroLengths(BitWriter bits, int count) {
        while (count > 0) {
            int chunk = Math.min(count, 138);
            if (count > chunk && count - chunk < 11) chunk = count - 11;
            if (chunk < 11) {
                throw new AssertionError("test table zero run is too short: " + chunk);
            }
            bits.write(1, 1); // bit-length symbol 19
            bits.write(chunk - 11, 7);
            count -= chunk;
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;
        private int count;

        void write(long value, int width) {
            for (int i = width - 1; i >= 0; i--) {
                current = (current << 1) | (int) ((value >>> i) & 1L);
                used++;
                count++;
                if (used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
        }

        int bitCount() {
            return count;
        }

        byte[] toByteArray() {
            if (used > 0) {
                out.write(current << (8 - used));
                current = 0;
                used = 0;
            }
            return out.toByteArray();
        }
    }

    private static File externalFixtureBuildDir() {
        String path = System.getProperty("textview.rarFixtureRoot");
        if (path == null || path.trim().length() == 0) {
            path = System.getenv("TEXTVIEW_RAR_FIXTURE_ROOT");
        }
        assumeTrue("RAR fixture root not provided", path != null && path.trim().length() > 0);
        File build = new File(path, "rar-test-files-master/build");
        if (!build.isDirectory()) {
            build = new File(path); // allow pointing directly at the build dir
        }
        assumeTrue("RAR fixture build dir missing: " + build.getAbsolutePath(), build.isDirectory());
        return build;
    }
}
