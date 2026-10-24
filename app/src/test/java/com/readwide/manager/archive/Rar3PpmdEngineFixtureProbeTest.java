package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;

/**
 * End-to-end verification of the first-party RAR3/RAR4 PPMd solid decoder
 * against the target solid CBR fixture (one PPMd reset entry + one PPMd
 * continuation entry).
 *
 * <p>The 381-byte fixture archive is embedded so these tests always run;
 * the external-fixture variants additionally exercise the same checks
 * against a caller-provided file when configured.</p>
 */
public class Rar3PpmdEngineFixtureProbeTest {
    @Test public void realPpmdModelSurvivesLzFileAndSymbolCodedVmChain() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>(
                RarArchiveReader.readEntries(writeEmbeddedFixture(), null));
        byte[] packed = Rar3UnpackerTest.emptyLzFileBoundary();
        File lz = temp.newFile(); Files.write(lz.toPath(), packed);
        RarArchiveReader.RarEntry middle = new RarArchiveReader.RarEntry("empty-lz.bin", false,
                0, packed.length, 0, 4, 0x33, true, false, false, null, 0, 0);
        middle.sourceArchive = lz; entries.add(1, middle);
        Rar3LogicalSolidArchiveExtractor.Decoder decoder = new Rar3LogicalSolidArchiveExtractor.Decoder(
                Rar3LogicalSolidArchiveExtractor.prepareArchive(entries), null);
        java.lang.reflect.Field stateField = decoder.getClass().getDeclaredField("state");
        java.lang.reflect.Field modelField = Rar3MixedPpmdState.class.getDeclaredField("model");
        stateField.setAccessible(true); modelField.setAccessible(true);
        try {
            File png = temp.newFile(); decoder.extractNext(png, null);
            assertFile(png, PNG_SIZE, PNG_CRC);
            Rar3SolidState state = (Rar3SolidState) stateField.get(decoder);
            Object model = modelField.get(state.mixedPpmd());
            org.junit.Assert.assertNotNull(model);
            state.unpackState().rememberNewDistanceMatch(3, 7);
            decoder.extractNext(null, null); // A real LZ table/file marker with no raw output.
            org.junit.Assert.assertSame(state, stateField.get(decoder));
            org.junit.Assert.assertSame(model, modelField.get(state.mixedPpmd()));
            assertEquals(3, state.unpackState().oldDistance(0)); assertEquals(7, state.unpackState().lastLength());
            assertEquals(PNG_SIZE, state.retained());
            // The bodies use real range coding; the inserted VM records are synthetic
            // symbol transport and must not be described as producer interoperability.
            try (Rar3PpmdFilterOutput queue = new Rar3PpmdFilterOutput(new java.io.ByteArrayOutputStream(),
                    0, state.vmFilterState(), Rar3PpmdFilterOutput.MAX_FILTER_BYTES, state.deferredFilters())) {
                queue.acceptParsed(state.vmFilterState().parse(0xa0,
                        Rar3VirtualMachineTest.resizingRecord(2, 2), 0, Rar3PpmdFilterOutput.MAX_FILTER_BYTES));
                int[] record = {0,0}; int[] cursor = {0};
                queue.readRecord(() -> cursor[0] < record.length ? record[cursor[0]++] : -1);
                queue.finish(true);
            }
            File jpg = temp.newFile(); decoder.extractNext(jpg, null);
            assertFile(jpg, JPG_SIZE, JPG_CRC);
            assertEquals(PNG_SIZE + JPG_SIZE, state.retained());
            assertNull(stateField.get(decoder)); assertNull(modelField.get(state.mixedPpmd()));
            assertEquals(0, state.vmFilterState().programCount());
            org.junit.Assert.assertThrows(IOException.class, state::ensureUsable);
            state.deferredFilters().requireEmpty();
        } finally { decoder.retire(); }
    }

    private static void assertFile(File file, int size, long expectedCrc) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath()); CRC32 crc = new CRC32(); crc.update(data);
        assertEquals(size, data.length); assertEquals(expectedCrc, crc.getValue());
    }

    @Test public void verifiedRunReleasesHistoryBeforeCloseAndIndependentRunStartsFresh() throws Exception {
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(writeEmbeddedFixture(), null);
        entries = new java.util.ArrayList<>(entries);
        entries.add(Rar3DecodePlan.snapshot(entries.get(0))); // Independent reset after the solid pair.
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, "owned-copy".toCharArray(), spool, false)) {
            java.lang.reflect.Field field = Rar3PpmdForwardReader.class.getDeclaredField("decoder");
            java.lang.reflect.Field password = Rar3PpmdForwardReader.class.getDeclaredField("password");
            field.setAccessible(true); password.setAccessible(true);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
            Rar3PpmdSolidStreamDecoder first = (Rar3PpmdSolidStreamDecoder) field.get(reader);
            org.junit.Assert.assertNotNull(first); // The JPEG still needs the model/history.
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertForwardEntry(reader, JPG_SIZE, JPG_CRC);
            assertNull(field.get(reader));
            assertRetired(first);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
            assertNull(field.get(reader));
            assertNull(reader.nextEntry()); assertNull(reader.nextEntry());
            assertEquals(-1, reader.read(new byte[1]));
            for (char value : (char[]) password.get(reader)) assertEquals('\0', value);
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void symbolCodedFilterReuseSurvivesRangeCodedSolidContinuation() throws Exception {
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        seedFilterInput(decoder, false, 2);
        assertEquals(PNG_CRC, decoder.decodeEntry(packedBody(false), PNG_SIZE).crc32);
        decoder.requireCompleteFilterRun();
        java.lang.reflect.Field programsField = Rar3PpmdSolidStreamDecoder.class.getDeclaredField("vmPrograms");
        java.lang.reflect.Field queueField = Rar3PpmdSolidStreamDecoder.class.getDeclaredField("deferredFilters");
        programsField.setAccessible(true); queueField.setAccessible(true);
        Rar3VmFilter.ProgramState programs = (Rar3VmFilter.ProgramState) programsField.get(decoder);
        Rar3PpmdFilterOutput.DeferredFilters queue = (Rar3PpmdFilterOutput.DeferredFilters) queueField.get(decoder);
        assertEquals(1, programs.programCount());
        // Synthetic PPMd-symbol record: reuse selected program and old length, relative start 0.
        // Real range-coded file bodies follow; the archive itself does not carry this VM record.
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(
                new java.io.ByteArrayOutputStream(), 0, programs, Rar3PpmdFilterOutput.MAX_FILTER_BYTES, queue)) {
            int[] record = {0, 0}; int[] position = {0};
            output.readRecord(() -> position[0] < record.length ? record[position[0]++] : -1);
            assertEquals(2, position[0]); output.finish(true);
        }
        assertEquals(JPG_CRC, decoder.decodeEntry(packedBody(true), JPG_SIZE).crc32);
        assertEquals(PNG_SIZE + JPG_SIZE, decoder.windowSize());
        assertEquals(1, programs.programCount()); decoder.requireCompleteFilterRun();
    }

    @Test public void forwardMetadataCannotRetargetSolidSuccessorsOrChangeAesSalt() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) for (boolean split : new boolean[]{false, true}) {
            char[] password = encrypted ? "test-secret".toCharArray() : null;
            List<RarArchiveReader.RarEntry> entries = transformedEntries(encrypted, split, password);
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                    entries, password, spool, false)) {
                assertTrue(reader instanceof Rar3PpmdForwardReader);
                assertEquals("testfile.png", reader.nextEntry().path);
                assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
                for (RarArchiveReader.RarEntry entry : entries) {
                    entry.sourceArchive = new File(temp.getRoot(), "absent-retarget.rar");
                    if (entry.encryption != null) java.util.Arrays.fill(entry.encryption.salt, (byte) 99);
                }
                entries.clear();
                assertEquals("testfile.jpg", reader.nextEntry().path);
                assertForwardEntry(reader, JPG_SIZE, JPG_CRC);
                assertNull(reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void ownedForwardMetadataStillRejectsRepeatedEntryIdentity() throws Exception {
        RarArchiveReader.RarEntry entry = RarArchiveReader.readEntries(writeEmbeddedFixture(), null).get(0);
        File spool = temp.newFolder();
        org.junit.Assert.assertThrows(IOException.class, () -> new Rar3PpmdForwardReader(
                java.util.Arrays.asList(entry, entry), null, spool));
        assertEquals(0, spool.list().length);
    }

    @Test public void rangeCodedContinuationConsumesSeededFutureAndPartialVmInput() throws Exception {
        // Real range-coded PNG/JPEG bodies, but synthetic boundary state: not a producer VM fixture.
        for (boolean partial : new boolean[]{false, true}) {
            Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
            assertEquals(PNG_CRC, decoder.decodeEntry(packedBody(false), PNG_SIZE).crc32);
            seedFilterInput(decoder, partial, partial ? 1 : 2);
            Rar3PpmdSolidStreamDecoder.EntryResult result = decoder.decodeEntry(packedBody(true), JPG_SIZE);
            assertEquals(JPG_CRC, result.crc32); assertEquals(JPG_SIZE, result.data.length);
            assertEquals(PNG_SIZE + JPG_SIZE, decoder.windowSize());
            decoder.requireCompleteFilterRun();
        }
    }

    @Test public void destinationFlushFailureRetiresPpmdBeforeAnotherFile() throws Exception {
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        IOException sentinel = new IOException("destination flush failed");
        java.io.OutputStream destination = new java.io.OutputStream() {
            @Override public void write(int value) { }
            @Override public void write(byte[] bytes, int offset, int length) { }
            @Override public void flush() throws IOException { throw sentinel; }
            @Override public void close() { fail("Decoder does not own the destination"); }
        };
        org.junit.Assert.assertSame(sentinel, org.junit.Assert.assertThrows(IOException.class,
                () -> decoder.decodeEntry(new java.io.ByteArrayInputStream(packedBody(false)), PNG_SIZE, destination)));
        assertRetired(decoder);
    }

    @Test public void externalCrcFailureRetiresPpmdBeforeAnotherFile() throws Exception {
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(writeEmbeddedFixture(), null).get(0);
        RarArchiveReader.RarEntry bad = new RarArchiveReader.RarEntry(original.path, false,
                original.unpackedSize, original.packedSize, original.dataOffset, 4, original.method,
                false, false, false, null, original.dataCrc ^ 1L, 0);
        bad.sourceArchive = original.sourceArchive;
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        org.junit.Assert.assertThrows(IOException.class, () -> Rar3PpmdSolidArchiveExtractor.discardVerifiedEntry(
                decoder, bad, java.util.Collections.singletonList(bad), null, null, true));
        assertRetired(decoder);
    }

    @Test public void oversizedCarriedVmResultRetiresRealPpmdContinuationWithoutPublishingIt() throws Exception {
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        assertEquals(PNG_CRC, decoder.decodeEntry(packedBody(false), PNG_SIZE).crc32);
        seedFilterInput(decoder, true, JPG_SIZE + 1);
        java.io.ByteArrayOutputStream destination = new java.io.ByteArrayOutputStream();
        org.junit.Assert.assertThrows(IOException.class, () -> decoder.decodeEntry(
                new java.io.ByteArrayInputStream(packedBody(true)), JPG_SIZE, destination));
        assertEquals(0, destination.size()); assertRetired(decoder);
    }

    private static byte[] packedBody(boolean jpeg) {
        byte[] archive = hexToBytes(FIXTURE_CBR_HEX);
        return java.util.Arrays.copyOfRange(archive, jpeg ? 0xc0 : 0x40, jpeg ? 0x176 : 0x94);
    }

    private static void seedFilterInput(Rar3PpmdSolidStreamDecoder decoder, boolean partial,
            int resultSize) throws Exception {
        java.lang.reflect.Field programsField = Rar3PpmdSolidStreamDecoder.class.getDeclaredField("vmPrograms");
        java.lang.reflect.Field queueField = Rar3PpmdSolidStreamDecoder.class.getDeclaredField("deferredFilters");
        programsField.setAccessible(true); queueField.setAccessible(true);
        Rar3VmFilter.ProgramState programs = (Rar3VmFilter.ProgramState) programsField.get(decoder);
        Rar3PpmdFilterOutput.DeferredFilters queue = (Rar3PpmdFilterOutput.DeferredFilters) queueField.get(decoder);
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new java.io.ByteArrayOutputStream(),
                0, programs, Rar3PpmdFilterOutput.MAX_FILTER_BYTES, queue, partial)) {
            out.acceptParsed(programs.parse(0xa0, Rar3VirtualMachineTest.slicingRecord(
                    2, resultSize, partial ? 1 : 0), 0, Rar3PpmdFilterOutput.MAX_FILTER_BYTES));
            if (partial) out.write(42); // Discarded by the synthetic slice; do not alter real history.
            out.finish(true);
        }
    }

    private static void assertRetired(Rar3PpmdSolidStreamDecoder decoder) throws Exception {
        IOException failure = org.junit.Assert.assertThrows(IOException.class,
                () -> decoder.decodeEntry(packedBody(true), JPG_SIZE));
        assertTrue(failure.getMessage().contains("retired"));
        // Also prohibit a reset header from reviving the same failed instance.
        org.junit.Assert.assertThrows(IOException.class, () -> decoder.decodeEntry(packedBody(false), PNG_SIZE));
        decoder.requireCompleteFilterRun(); // Retired work must not retain a deferred prefix.
    }

    @Test public void forwardFailure_truncatedSpool() throws Exception {
        File archive = writeEmbeddedFixture();
        File spool = temp.newFolder();
        RarForwardFailureAssertions.truncatedSpool(Rar3PpmdSolidArchiveExtractor.openForwardReader(archive, spool, false), spool);
    }

    @Test public void forwardFailure_deletionRetry() throws Exception {
        File archive = writeEmbeddedFixture();
        File spool = temp.newFolder();
        RarForwardFailureAssertions.deletionRetry(Rar3PpmdSolidArchiveExtractor.openForwardReader(archive, spool, false), spool);
    }

    @Test public void forwardFailure_interruptionRetires() throws Exception {
        File archive = writeEmbeddedFixture();
        File spool = temp.newFolder();
        RarForwardFailureAssertions.interruptionRetires(Rar3PpmdSolidArchiveExtractor.openForwardReader(archive, spool, false), spool);
    }


    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** testfile_rar3_solid.cbr (381 bytes): testfile.png + testfile.jpg, both PPMd, solid. */
    private static final String FIXTURE_CBR_HEX = ""
            + "526172211a07003bd07308000d0000000000000062d47480802c0054000000570000000062acb0af0000212a1d350c00"
            + "200000007465737466696c652e706e67a71888c5fbb542d1f3ded5f7b2c970254f81bf495b52740c9dee9307b04dc502"
            + "422a6eb601460c90a7b9b5a71ceff87387a5fc0269e03c8c0e35402c66502becc86b2a47980e360402c0000000bf8867"
            + "f6a9ffd427a77490802c00b6000000dc000000006cb170da0000212a1d350c00200000007465737466696c652e6a7067"
            + "c715fecff824ae8b900f34afd6ef2cd188338b7cd5790f769ed618231edffc8e4677337ff39dc0e5c952fef4ba715dd3"
            + "696e0995e6215306378140fdbd48d55b0fd8372a4cf27920c17d947a85e90a7c3329f423d733c0b1dbbbf5e25e5717a8"
            + "56cb61233cf05b91d707fef2d00b433a2e66912d5a6292a8118a6ce087f87590b30a8d5ac34804c95d04c1262ff68c90"
            + "a44cb64cb6f9582036e1bd536f918fa6536d2f79685e97a75ed3dfe46d0000bf8867f6a9ffd4c43d7b00400700";

    private static final long PNG_CRC = 0xafb0ac62L;
    private static final long JPG_CRC = 0xda70b16cL;
    private static final int PNG_SIZE = 87;
    private static final int JPG_SIZE = 220;

    @Test public void forwardSessionOwnsListAndPasswordCopies() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>(
                RarArchiveReader.readEntries(writeEmbeddedFixture(), null));
        char[] password = "caller-owned".toCharArray();
        char[] expectedPassword = password.clone();
        File spool = temp.newFolder("owned-session-spool");
        Rar3PpmdForwardReader reader = new Rar3PpmdForwardReader(
                entries, password, spool);
        java.lang.reflect.Field passwordField = Rar3PpmdForwardReader.class.getDeclaredField("password");
        passwordField.setAccessible(true);
        char[] ownedPassword = (char[]) passwordField.get(reader);
        try (Rar3PpmdForwardReader ignored = reader) {
            org.junit.Assert.assertNotSame(password, ownedPassword);
            entries.clear();
            java.util.Arrays.fill(password, 'x');
            assertArrayEquals(expectedPassword, ownedPassword);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertForwardEntry(reader, JPG_SIZE, JPG_CRC);
            assertNull(reader.nextEntry());
        }
        assertArrayEquals(new char[expectedPassword.length], ownedPassword);
        for (char value : password) assertEquals('x', value);
        assertEquals(0, spool.list().length);
        reader.close(); // Repeated close must not revive or re-own the session.
        org.junit.Assert.assertThrows(IOException.class, () -> reader.nextEntry());
    }

    @Test public void forwardSessionAdvancesAfterPartialReadWithoutResettingSolidHistory() throws Exception {
        File spool = temp.newFolder("partial-advance-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(writeEmbeddedFixture(), spool, false)) {
            assertTrue(reader instanceof Rar3PpmdForwardReader);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertEquals(0, reader.read(new byte[0]));
            assertEquals(0, spool.list().length);
            assertEquals(1, reader.read(new byte[1]));
            assertEquals(1, spool.list().length);
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertEquals(0, spool.list().length);
            assertForwardEntry(reader, JPG_SIZE, JPG_CRC);
            assertNull(reader.nextEntry());
            assertNull(reader.nextEntry());
            assertEquals(-1, reader.read(new byte[1]));
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void independentPpmdPrimerCanUseSharedAesSplitLzPpmdEntryPoint() throws Exception {
        char[] password = "test-secret".toCharArray();
        List<RarArchiveReader.RarEntry> entries = transformedEntries(true, true, password);
        File out = temp.newFile();
        assertTrue(Rar3IndependentPayloadExtractor.tryExtract(entries.get(0), entries, out, password, null));
        CRC32 crc = new CRC32(); crc.update(Files.readAllBytes(out.toPath()));
        assertEquals(PNG_CRC, crc.getValue());
        assertEquals(PNG_SIZE, out.length());
    }

    @Test public void bulkCanMixPlainPpmdClassicLzStoredAndIndependentAesSplitEntries() throws Exception {
        char[] password = "test-secret".toCharArray();
        List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        entries.add(transformedEntries(false, false, null).get(0)); // Independent real PPMd primer.
        byte[] lz = Rar3FirstPartyArchiveExtractorTest.independentLzFixture();
        File lzSource = temp.newFile(); Files.write(lzSource.toPath(), lz);
        RarArchiveReader.RarEntry lzEntry = new RarArchiveReader.RarEntry("plain-lz.bin", false,
                2, lz.length, 0, 4, 0x33, false, false, false, null,
                packedPartCrc(new byte[]{'A','B'}, 0, 2), 0);
        lzEntry.sourceArchive = lzSource; entries.add(lzEntry);
        File storedSource = temp.newFile(); Files.write(storedSource.toPath(), new byte[]{42});
        RarArchiveReader.RarEntry stored = new RarArchiveReader.RarEntry("note.bin", false,
                1, 1, 0, 4, 0x30, false, false, false, null, packedPartCrc(new byte[]{42}, 0, 1), 0);
        stored.sourceArchive = storedSource; entries.add(stored);
        List<RarArchiveReader.RarEntry> encrypted = transformedEntries(true, true, password);
        for (int i = 0; i < 2; i++) {
            RarArchiveReader.RarEntry part = encrypted.get(i);
            RarArchiveReader.RarEntry renamed = new RarArchiveReader.RarEntry("secret.png", false,
                    part.unpackedSize, part.packedSize, part.dataOffset, 4, part.method, false,
                    part.splitBefore, part.splitAfter, part.encryption, part.dataCrc, 0);
            renamed.sourceArchive = part.sourceArchive; entries.add(renamed);
        }
        File directory = temp.newFolder();
        assertTrue(Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                entries, directory, password, null, null));
        for (String name : new String[]{"testfile.png", "secret.png"}) {
            byte[] bytes = Files.readAllBytes(new File(directory, name).toPath());
            assertEquals(PNG_SIZE, bytes.length);
            assertEquals(PNG_CRC, packedPartCrc(bytes, 0, bytes.length));
        }
        org.junit.Assert.assertArrayEquals(new byte[]{'A','B'}, Files.readAllBytes(new File(directory, "plain-lz.bin").toPath()));
        org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(new File(directory, "note.bin").toPath()));
    }

    @Test public void ppmdBulkDirectoryCollisionFailsBeforePublishingImages() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        entries.add(new RarArchiveReader.RarEntry("occupied", true, 0, 0, 0,
                4, 0x30, false, false, false, null, 0, 0));
        entries.addAll(RarArchiveReader.readEntries(writeEmbeddedFixture(), null));
        File target = temp.newFolder();
        File occupied = new File(target, "occupied");
        Files.write(occupied.toPath(), new byte[]{42});
        IOException failure = org.junit.Assert.assertThrows(IOException.class,
                () -> Rar3PpmdSolidArchiveExtractor.tryExtractArchivePpmdSolid(
                        entries, target, null, null, null));
        assertEquals("Cannot create output directory", failure.getMessage());
        org.junit.Assert.assertArrayEquals(new byte[]{42}, Files.readAllBytes(occupied.toPath()));
        assertTrue(!new File(target, "testfile.png").exists());
    }

    @Test public void encryptedSplitPpmdKeepsCipherAndSolidModelAcrossVolumes() throws Exception {
        for (boolean encrypted : new boolean[]{false,true}) {
            char[] password = encrypted ? "test-secret".toCharArray() : null;
            List<RarArchiveReader.RarEntry> entries = transformedEntries(encrypted, true, password);
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                    entries,password,spool,true)) {
                assertTrue(reader != null);
                if (password != null) java.util.Arrays.fill(password, 'x'); // Adapter owns a copy.
                entries.clear(); // Reader retains entry order and split-chain membership.
                assertEquals("testfile.png", reader.nextEntry().path);
                assertTrue(reader.drainCurrentEntry(Long.MAX_VALUE)); // verified split primer, no spool
                assertEquals(0,spool.list().length);
                assertEquals("testfile.jpg", reader.nextEntry().path);
                assertForwardEntry(reader,JPG_SIZE,JPG_CRC);
                assertNull(reader.nextEntry());
            }
            assertEquals(0,spool.list().length);
        }
    }

    @Test public void ppmdBulkAndForwardRejectOrphanBeforePayloadWork() throws Exception {
        List<RarArchiveReader.RarEntry> entries = transformedEntries(false, true, null);
        entries.add(new RarArchiveReader.RarEntry("orphan.bin", false, 1, 1, 0,
                4, 0x30, false, true, false, null, 0, 0));
        File target = new File(temp.getRoot(), "not-created");
        IOException bulk = org.junit.Assert.assertThrows(IOException.class,
                () -> Rar3PpmdSolidArchiveExtractor.tryExtractArchivePpmdSolid(
                        entries, target, null, null, null));
        assertTrue(bulk.getMessage().contains("split head"));
        IOException forward = org.junit.Assert.assertThrows(IOException.class, () -> {
            try (ArchiveSupport.ForwardArchiveReader ignored =
                         Rar3PpmdSolidArchiveExtractor.openForwardReader(entries, null, target, false)) { }
        });
        assertTrue(forward.getMessage().contains("split head"));
        assertTrue(!target.exists());
    }

    @Test public void encryptedPpmdSingleTargetPrimesAndChecksEarlierSplitMember() throws Exception {
        char[] password = "test-secret".toCharArray();
        List<RarArchiveReader.RarEntry> entries = transformedEntries(true,true,password);
        RarArchiveReader.RarEntry target = entries.get(2);
        entries = new java.util.LinkedList<>(entries);
        entries.add(0, new RarArchiveReader.RarEntry("unrelated-orphan", false, 1, 1, 0,
                4, 0x30, false, true, false, null, 0, 0));
        entries.add(1, null);
        entries.add(new RarArchiveReader.RarEntry("later-missing", false, 1, 1, 0,
                4, 0x33, false, false, true, null, 0, 0));
        File out = temp.newFile();
        assertTrue(Rar3PpmdSolidArchiveExtractor.tryExtractSolidPpmdEntry(
                target,entries,out,password,null));
        CRC32 crc = new CRC32(); crc.update(Files.readAllBytes(out.toPath()));
        assertEquals(JPG_CRC,crc.getValue());
        assertEquals(JPG_SIZE,out.length());
    }

    @Test public void encryptedPpmdRejectsWrongPasswordAndIncompleteVolumeChain() throws Exception {
        List<RarArchiveReader.RarEntry> entries = transformedEntries(true,true,"test-secret".toCharArray());
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries,"wrong".toCharArray(),spool,false)) {
            if (reader != null) {
                reader.nextEntry();
                try { reader.read(new byte[1]); fail("Wrong password must not publish bytes"); }
                catch (IOException expected) { }
            }
        } catch (IOException expected) { }
        assertEquals(0,spool.list().length);
        entries.remove(1);
        try { Rar3PackedPayload.open(entries.get(0),entries,"test-secret".toCharArray(),null); fail(); }
        catch (IOException expected) { }
    }

    @Test public void encryptedPpmdPayloadRejectsBadIntermediateCiphertextCrc() throws Exception {
        char[] password = "test-secret".toCharArray();
        List<RarArchiveReader.RarEntry> entries = transformedEntries(true,true,password);
        RarArchiveReader.RarEntry first = entries.get(0);
        RarArchiveReader.RarEntry bad = new RarArchiveReader.RarEntry(first.path,false,
                first.unpackedSize,first.packedSize,first.dataOffset,4,first.method,first.solid,
                false,true,first.encryption,first.dataCrc ^ 1,first.timeMillis);
        bad.sourceArchive = first.sourceArchive; entries.set(0,bad);
        try (Rar3PackedPayload payload = Rar3PackedPayload.open(bad,entries,password,null)) {
            try {
                while (payload.input.read(new byte[64]) != -1) { }
                fail("Encrypted PPMd must not skip intermediate packed CRC");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("packed-volume")); }
        }
    }

    private List<RarArchiveReader.RarEntry> transformedEntries(boolean encrypted, boolean split,
            char[] password) throws Exception {
        File fixture = writeEmbeddedFixture();
        List<RarArchiveReader.RarEntry> originals = RarArchiveReader.readEntries(fixture,null);
        byte[] archive = Files.readAllBytes(fixture.toPath());
        List<RarArchiveReader.RarEntry> result = new java.util.ArrayList<>();
        for (RarArchiveReader.RarEntry entry : originals) {
            byte[] packed = java.util.Arrays.copyOfRange(archive,(int)entry.dataOffset,
                    (int)(entry.dataOffset+entry.packedSize));
            RarArchiveReader.EncryptionInfo enc = null;
            if (encrypted) {
                byte[] salt = new byte[]{1,2,3,4,5,6,7,8};
                enc = RarArchiveReader.EncryptionInfo.rar4Unsupported(salt);
                Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password,salt);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(parameters.key,"AES"),
                        new javax.crypto.spec.IvParameterSpec(parameters.iv));
                packed = cipher.doFinal(java.util.Arrays.copyOf(packed,((packed.length+15)/16)*16));
            }
            int cut = split ? 17 : packed.length; // deliberately crosses an AES block in the middle
            for (int offset = 0; offset < packed.length;) {
                int end = offset == 0 ? cut : packed.length;
                File volume = temp.newFile();
                Files.write(volume.toPath(),java.util.Arrays.copyOfRange(packed,offset,end));
                RarArchiveReader.RarEntry part = new RarArchiveReader.RarEntry(entry.path,false,
                        entry.unpackedSize,end-offset,0,4,entry.method,entry.solid,offset>0,end<packed.length,
                        enc,end<packed.length ? packedPartCrc(packed,offset,end-offset) : entry.dataCrc,0);
                part.sourceArchive = volume;
                result.add(part);
                offset = end;
            }
        }
        return result;
    }

    private static long packedPartCrc(byte[] packed, int offset, int length) {
        CRC32 crc = new CRC32(); crc.update(packed,offset,length); return crc.getValue();
    }

    // ---- embedded fixture (always-on) ----

    @Test
    public void forwardReaderCarriesModelAndWindowAcrossVerifiedImages() throws Exception {
        File fixture = writeEmbeddedFixture();
        File spool = temp.newFolder("forward-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, true)) {
            assertTrue(reader != null); // Covered solid PPMd uses the first-party adapter even with native present.
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
            assertEquals(1, spool.list().length);
            java.lang.reflect.Field decoder = reader.getClass().getDeclaredField("decoder");
            decoder.setAccessible(true);
            Object firstModel = decoder.get(reader);
            org.junit.Assert.assertNotNull(firstModel);
            assertEquals("testfile.jpg", reader.nextEntry().path);
            org.junit.Assert.assertSame(firstModel, decoder.get(reader));
            assertEquals(0, spool.list().length);
            assertForwardEntry(reader, JPG_SIZE, JPG_CRC);
            assertNull(decoder.get(reader)); // Verified terminal member no longer needs history.
            assertRetired((Rar3PpmdSolidStreamDecoder) firstModel);
            assertNull(reader.nextEntry());
            assertEquals(0, spool.list().length);
        }
        assertEquals(0, spool.list().length);
    }

    @Test
    public void skippedPrimerIsDecodedAndVerifiedWithoutSpooling() throws Exception {
        File spool = temp.newFolder("discard-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     ArchiveSupport.openForwardReader(writeEmbeddedFixture(), null, spool)) {
            assertEquals("testfile.png", reader.nextEntry().path);
            assertTrue(reader.drainCurrentEntry(Long.MAX_VALUE));
            assertEquals(0, spool.list().length);
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertForwardEntry(reader, JPG_SIZE, JPG_CRC);
        }
        assertEquals(0, spool.list().length);
    }

    @Test
    public void corruptPrimerCrcRejectsBytesAndRetiresReader() throws Exception {
        File fixture = writeEmbeddedFixture();
        // Change the stored first-file CRC only; RAR4 header CRC is advisory in the existing parser.
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(fixture, "rw")) {
            file.seek(0x24); int value = file.read(); file.seek(0x24); file.write(value ^ 1);
        }
        File spool = temp.newFolder("crc-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, false)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); fail("CRC must pass before any image bytes are returned"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("CRC")); }
            assertEquals(0, spool.list().length);
            try { reader.nextEntry(); fail("Failed history must not resume"); }
            catch (IOException expected) { }
        }
    }

    @Test
    public void skippedCorruptPrimerCannotReachNextSolidImage() throws Exception {
        File fixture = writeEmbeddedFixture();
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(fixture, "rw")) {
            file.seek(0x24); int value = file.read(); file.seek(0x24); file.write(value ^ 1);
        }
        File spool = temp.newFolder("bad-discard-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, false)) {
            reader.nextEntry();
            try { reader.nextEntry(); fail("Advancing must verify unread primer"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("CRC")); }
            assertEquals(0, spool.list().length);
        }
    }

    @Test
    public void forwardSpoolRespectsSharedBudgetAndCloseCleansPartialRead() throws Exception {
        File fixture = writeEmbeddedFixture();
        File spool = temp.newFolder("budget-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, false);
             ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(1)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); fail("Expected guarded-output failure"); }
            catch (IOException expected) { }
            assertEquals(0, spool.list().length);
        }
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, false)) {
            reader.nextEntry();
            assertEquals(1, reader.read(new byte[1]));
            assertEquals(1, spool.list().length);
        }
        assertEquals(0, spool.list().length);
    }

    @Test
    public void forwardPreflightRejectsIncompleteMetadataAndChecksMixedPayloadBeforePublication() throws Exception {
        File fixture = writeEmbeddedFixture();
        byte[] original = Files.readAllBytes(fixture.toPath());
        File spool = temp.newFolder("scope-spool");
        for (int flags : new int[]{1, 2, 4}) { // RAR4 split-before, split-after, encrypted file flags.
            byte[] changed = original.clone(); changed[0x17] |= flags;
            Files.write(fixture.toPath(), changed);
            try (ArchiveSupport.ForwardArchiveReader reader =
                         Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, false)) {
                assertNull(reader);
            } catch (IOException expected) { /* malformed split/encryption metadata is not an eligible archive */ }
        }
        byte[] mixed = original.clone(); mixed[0xc0] &= 0x7f;
        Files.write(fixture.toPath(), mixed);
        // A non-PPMd start belongs to the mixed-capable logical reader. Its
        // header probe is not proof that this deliberately damaged body decodes.
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, false)) {
            org.junit.Assert.assertNotNull(reader);
            org.junit.Assert.assertFalse(reader instanceof Rar3PpmdForwardReader);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
            assertEquals("testfile.jpg", reader.nextEntry().path);
            byte[] untouched = {42};
            org.junit.Assert.assertThrows(IOException.class, () -> reader.read(untouched));
            assertArrayEquals(new byte[]{42}, untouched);
            org.junit.Assert.assertThrows(IOException.class, reader::nextEntry);
        }
        assertEquals(0, spool.list().length);
    }

    private static void assertForwardEntry(ArchiveSupport.ForwardArchiveReader reader,
            int expectedSize, long expectedCrc) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[13];
        int count, total = 0;
        while ((count = reader.read(buffer)) != -1) { crc.update(buffer, 0, count); total += count; }
        assertEquals(expectedSize, total);
        assertEquals(expectedCrc, crc.getValue());
    }

    @Test
    public void nonSolidResetGetsFreshDecoderAndKeepsNativePreference() throws Exception {
        File fixture = writeEmbeddedFixture();
        byte[] original = Files.readAllBytes(fixture.toPath());
        java.io.ByteArrayOutputStream archive = new java.io.ByteArrayOutputStream();
        archive.write(original, 0, 0x94); // Main header and complete first reset member.
        archive.write(original, 0x14, 0x80); // A second independent reset member (duplicate name is legal).
        archive.write(original, 0x176, 7); // End header.
        Files.write(fixture.toPath(), archive.toByteArray());
        File spool = temp.newFolder("resets-spool");
        assertNull(Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, true));
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar3PpmdSolidArchiveExtractor.openForwardReader(fixture, spool, false)) {
            reader.nextEntry(); assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
            java.lang.reflect.Field decoder = reader.getClass().getDeclaredField("decoder");
            decoder.setAccessible(true);
            assertNull(decoder.get(reader)); // Independent entry completed and released its model.
            reader.nextEntry(); assertForwardEntry(reader, PNG_SIZE, PNG_CRC);
            assertNull(decoder.get(reader)); // The next independent entry also completed from a fresh reset.
        }
        assertEquals(0, spool.list().length);
    }

    @Test
    public void streamingDecoderPreservesBothEntryCrcs() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        java.io.ByteArrayOutputStream first = new java.io.ByteArrayOutputStream();
        assertEquals(PNG_CRC, decoder.decodeEntry(
                new java.io.ByteArrayInputStream(cbr, 0x40, 0x54), PNG_SIZE, first));
        assertEquals(PNG_CRC, crc32(first.toByteArray()));
        java.io.ByteArrayOutputStream second = new java.io.ByteArrayOutputStream();
        assertEquals(JPG_CRC, decoder.decodeEntry(
                new java.io.ByteArrayInputStream(cbr, 0xc0, 0xb6), JPG_SIZE, second));
        assertEquals(JPG_CRC, crc32(second.toByteArray()));
    }

    @Test
    public void streamingOutputFailureInvalidatesContinuation() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        IOException sentinel = new IOException("destination full");
        try {
            decoder.decodeEntry(new java.io.ByteArrayInputStream(cbr, 0x40, 0x54), PNG_SIZE,
                    new java.io.OutputStream() {
                        @Override public void write(int value) throws IOException { throw sentinel; }
                    });
            fail("Expected output failure");
        } catch (IOException expected) { org.junit.Assert.assertSame(sentinel, expected); }
        try {
            decoder.decodeEntry(new java.io.ByteArrayInputStream(cbr, 0xc0, 0xb6), JPG_SIZE,
                    new java.io.ByteArrayOutputStream());
            fail("Failed state must not be reused");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("continuation")); }
    }

    @Test
    public void uncheckedStreamingFailureInvalidatesContinuation() throws Exception {
        byte[] cbr=hexToBytes(FIXTURE_CBR_HEX);
        for(boolean error:new boolean[]{false,true}) {
            Rar3PpmdSolidStreamDecoder decoder=new Rar3PpmdSolidStreamDecoder();
            Throwable sentinel=error?new AssertionError("sink"):new IllegalStateException("sink");
            org.junit.Assert.assertSame(sentinel,org.junit.Assert.assertThrows(Throwable.class,()->
                    decoder.decodeEntry(new java.io.ByteArrayInputStream(cbr,0x40,0x54),PNG_SIZE,
                            new java.io.OutputStream() {
                                @Override public void write(int value) {
                                    if(sentinel instanceof Error)throw (Error)sentinel;
                                    throw (RuntimeException)sentinel;
                                }
                            })));
            IOException next=org.junit.Assert.assertThrows(IOException.class,()->
                    decoder.decodeEntry(new java.io.ByteArrayInputStream(cbr,0xc0,0xb6),JPG_SIZE,
                            new java.io.ByteArrayOutputStream()));
            assertTrue(next.getMessage().contains("continuation"));
        }
    }

    @Test
    public void engineDecodesBothSolidPpmdEntriesWithCrcMatch() throws Exception {
        File fixture = writeEmbeddedFixture();
        List<Rar3PpmdEngineFixtureProbe.Row> rows = Rar3PpmdEngineFixtureProbe.probe(fixture);
        assertBothRowsFullyDecoded(rows);
    }

    @Test
    public void streamDecoderEnforcesEndOfDataMarkerAndSolidContinuation() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        byte[] pngPacked = new byte[0x54];
        System.arraycopy(cbr, 0x40, pngPacked, 0, 0x54);
        byte[] jpgPacked = new byte[0xB6];
        System.arraycopy(cbr, 0xC0, jpgPacked, 0, 0xB6);

        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        Rar3PpmdSolidStreamDecoder.EntryResult png = decoder.decodeEntry(pngPacked, PNG_SIZE);
        assertEquals(PNG_CRC, png.crc32);
        assertEquals(PNG_SIZE, png.data.length);
        assertEquals("png", Rar3PpmdEngineFixtureProbe.magicName(png.data));

        Rar3PpmdSolidStreamDecoder.EntryResult jpg = decoder.decodeEntry(jpgPacked, JPG_SIZE);
        assertEquals(JPG_CRC, jpg.crc32);
        assertEquals(JPG_SIZE, jpg.data.length);
        assertEquals("jpeg", Rar3PpmdEngineFixtureProbe.magicName(jpg.data));
        assertEquals(crc32(jpg.data), jpg.crc32);
    }

    @Test
    public void continuationEntryWithoutPrimerIsRejectedNotFaked() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        byte[] jpgPacked = new byte[0xB6];
        System.arraycopy(cbr, 0xC0, jpgPacked, 0, 0xB6);
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        try {
            decoder.decodeEntry(jpgPacked, JPG_SIZE);
            fail("Continuation entry without a primed model must not decode");
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("continuation"));
        }
    }

    @Test
    public void corruptedPrimerPayloadFailsInsteadOfReportingSuccess() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        byte[] pngPacked = new byte[0x54];
        System.arraycopy(cbr, 0x40, pngPacked, 0, 0x54);
        pngPacked[20] ^= 0x5A; // corrupt mid-stream
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        boolean failed;
        try {
            Rar3PpmdSolidStreamDecoder.EntryResult result = decoder.decodeEntry(pngPacked, PNG_SIZE);
            failed = result.crc32 != PNG_CRC;
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            failed = true;
        }
        assertTrue("Corrupted payload must fail decode or CRC, never report a clean result", failed);
    }

    @Test
    public void wholeArchiveExtractionProducesBothImagesViaProductionPath() throws Exception {
        File fixture = writeEmbeddedFixture();
        File outDir = temp.newFolder("out");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(fixture, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.png"), PNG_SIZE, PNG_CRC);
        assertExtractedFile(new File(outDir, "testfile.jpg"), JPG_SIZE, JPG_CRC);
    }

    @Test
    public void singleEntryExtractionOfContinuationEntryPrimesPredecessors() throws Exception {
        File fixture = writeEmbeddedFixture();
        File out = new File(temp.newFolder("single"), "testfile.jpg");
        assertTrue(RarArchiveReader.extractSingleEntry(fixture, "testfile.jpg", out, null));
        assertExtractedFile(out, JPG_SIZE, JPG_CRC);
    }

    @Test
    public void singleEntryExtractionOfFirstEntryWorks() throws Exception {
        File fixture = writeEmbeddedFixture();
        File out = new File(temp.newFolder("single2"), "testfile.png");
        assertTrue(RarArchiveReader.extractSingleEntry(fixture, "testfile.png", out, null));
        assertExtractedFile(out, PNG_SIZE, PNG_CRC);
    }

    @Test
    public void decodedBytesMatchGroundTruthExactly() throws Exception {
        File fixture = writeEmbeddedFixture();
        List<Rar3PpmdEngineFixtureProbe.Row> rows = Rar3PpmdEngineFixtureProbe.probe(fixture);
        assertEquals(2, rows.size());
        byte[] expectedPngPrefix = hexToBytes("89504e470d0a1a0a0000000d49484452");
        byte[] expectedJpgPrefix = hexToBytes("ffd8ffe000104a464946");
        byte[] pngPrefix = new byte[expectedPngPrefix.length];
        byte[] jpgPrefix = new byte[expectedJpgPrefix.length];
        System.arraycopy(rows.get(0).data, 0, pngPrefix, 0, pngPrefix.length);
        System.arraycopy(rows.get(1).data, 0, jpgPrefix, 0, jpgPrefix.length);
        assertArrayEquals(expectedPngPrefix, pngPrefix);
        assertArrayEquals(expectedJpgPrefix, jpgPrefix);
    }

    // ---- external fixture (assume-gated, same checks against a real file) ----

    @Test
    public void externalFixtureDecodesBothEntriesWhenProvided() throws Exception {
        File fixture = externalFixture();
        List<Rar3PpmdEngineFixtureProbe.Row> rows = Rar3PpmdEngineFixtureProbe.probe(fixture);
        assertBothRowsFullyDecoded(rows);
    }

    @Test
    public void externalFixtureWholeArchiveExtractionWhenProvided() throws Exception {
        File fixture = externalFixture();
        File outDir = temp.newFolder("ext");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(fixture, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.png"), PNG_SIZE, PNG_CRC);
        assertExtractedFile(new File(outDir, "testfile.jpg"), JPG_SIZE, JPG_CRC);
    }

    // ---- helpers ----

    private void assertBothRowsFullyDecoded(List<Rar3PpmdEngineFixtureProbe.Row> rows) {
        assertEquals(2, rows.size());

        Rar3PpmdEngineFixtureProbe.Row png = rows.get(0);
        assertEquals("testfile.png", png.path);
        assertNull(png.failure);
        assertEquals(PNG_SIZE, png.decodedBytes);
        assertEquals(PNG_CRC, png.actualCrc);
        assertTrue(png.crcOk);
        assertTrue(png.magicOk);
        assertEquals("png", png.magicName);

        Rar3PpmdEngineFixtureProbe.Row jpg = rows.get(1);
        assertEquals("testfile.jpg", jpg.path);
        assertNull(jpg.failure);
        assertEquals(JPG_SIZE, jpg.decodedBytes);
        assertEquals(JPG_CRC, jpg.actualCrc);
        assertTrue(jpg.crcOk);
        assertTrue(jpg.magicOk);
        assertEquals("jpeg", jpg.magicName);
    }

    private void assertExtractedFile(File file, int expectedSize, long expectedCrc) throws IOException {
        assertTrue("missing output: " + file, file.isFile());
        byte[] data = Files.readAllBytes(file.toPath());
        assertEquals(expectedSize, data.length);
        assertEquals(expectedCrc, crc32(data));
    }

    private File writeEmbeddedFixture() throws IOException {
        File fixture = new File(temp.newFolder(), "testfile_rar3_solid.cbr");
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            out.write(hexToBytes(FIXTURE_CBR_HEX));
        }
        return fixture;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static File externalFixture() {
        String path = System.getProperty("textview.rar3SolidCbrFixture");
        if (path == null || path.trim().length() == 0) {
            path = System.getenv("TEXTVIEW_RAR3_SOLID_CBR_FIXTURE");
        }
        assumeTrue("Direct RAR3 solid CBR fixture not provided",
                path != null && path.trim().length() > 0);
        File fixture = new File(path);
        assumeTrue("Direct RAR3 solid CBR fixture missing: " + fixture.getAbsolutePath(),
                fixture.isFile());
        return fixture;
    }
}
