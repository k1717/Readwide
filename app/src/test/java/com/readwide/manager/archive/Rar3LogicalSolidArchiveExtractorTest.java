package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic composition coverage; not real-encoder interoperability evidence. */
public class Rar3LogicalSolidArchiveExtractorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final char[] PASSWORD = "bridge".toCharArray();
    private static final byte[] SALT = {1,2,3,4,5,6,7,8};

    @Test public void snapshotCannotDisguiseRepeatedSolidPayloadIdentity() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) for (boolean split : new boolean[]{false, true}) {
            List<RarArchiveReader.RarEntry> entries = unfinishedFutureFixture(encrypted, split);
            // Repeating the complete list leaves split topology superficially complete,
            // but must not turn original entry aliases into distinct owned payloads.
            entries.addAll(new ArrayList<>(entries));
            IOException failure = assertThrows(IOException.class,
                    () -> Rar3LogicalSolidArchiveExtractor.prepareArchive(entries));
            assertTrue(failure.getMessage().contains("Repeated RAR payload entry"));
        }
    }

    @Test public void wholeRunCannotDropFutureFilterAtArchiveEndOrIndependentReset() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) for (boolean split : new boolean[]{false, true}) {
            for (boolean reset : new boolean[]{false, true}) {
                List<RarArchiveReader.RarEntry> entries = unfinishedFutureFixture(encrypted, split);
                if (reset) append(entries, "reset.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                        new byte[]{'A','B'}, false, encrypted, split, false);
                File directory = temporary.newFolder();
                File output = new File(directory, "target.bin"); Files.write(output.toPath(), new byte[]{42});
                IOException failure = assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                        entries, directory, PASSWORD, null, null));
                assertTrue(failure.getMessage().contains("unfinished VM filter"));
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
                assertFalse(new File(directory, "reset.bin").exists());
                if (!encrypted && !split) {
                    File plain = temporary.newFolder();
                    File previous = new File(plain, "target.bin"); Files.write(previous.toPath(), new byte[]{42});
                    assertThrows(IOException.class, () -> Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(
                            entries, plain, null, null, null));
                    assertArrayEquals(new byte[]{42}, Files.readAllBytes(previous.toPath()));
                }
            }
        }
    }

    @Test public void selectedPrefixKeepsOwnValidOutputWithoutScanningUnselectedFilterTarget() throws Exception {
        List<RarArchiveReader.RarEntry> entries = unfinishedFutureFixture(false, false);
        RarArchiveReader.RarEntry selected = target(entries);
        append(entries, "after.bin", Rar3UnpackerTest.deferredFilterTarget(), new byte[]{'A'}, true, false, false, false);
        entries.get(entries.size() - 1).sourceArchive = new File(temporary.getRoot(), "unselected-absent.rar");
        File output = original();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractEntry(selected, entries, output, null, null));
        assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(output.toPath()));
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(selected, entries, output, null));
        assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(output.toPath()));
    }

    @Test public void completeRunConsumesFutureFilterAndPreservesStoredInterleaving() throws Exception {
        List<RarArchiveReader.RarEntry> entries = unfinishedFutureFixture(true, true);
        appendStored(entries, "stored.bin", new byte[]{42}, true, true, true);
        append(entries, "after.bin", Rar3UnpackerTest.deferredFilterTarget(), new byte[]{'A'}, true, true, true, false);
        File directory = temporary.newFolder();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(entries, directory, PASSWORD, null, null));
        assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(new File(directory, "after.bin").toPath()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(new File(directory, "stored.bin").toPath()));
    }

    @Test public void danglingForwardFilterExposesNoBytesAndRetiresWithEmptySpool() throws Exception {
        for (boolean plain : new boolean[]{false, true}) {
            List<RarArchiveReader.RarEntry> entries = unfinishedFutureFixture(!plain, !plain);
            File spool = temporary.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = plain
                    ? Rar3CheckedForwardReader.open(entries, spool, false)
                    : Rar3LogicalSolidArchiveExtractor.openForwardReader(entries, PASSWORD, spool)) {
                assertNotNull(reader); reader.nextEntry(); reader.nextEntry();
                byte[] bytes = {42, 42};
                assertThrows(IOException.class, () -> reader.read(bytes));
                assertArrayEquals(new byte[]{42, 42}, bytes);
                assertThrows(IOException.class, () -> reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void oversizedResultCannotSatisfyTwoFileCrcsBySplittingOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        append(entries, "primer.bin", Rar3UnpackerTest.oversizedFilterResult(), new byte[]{'A'}, false, true, true, false);
        append(entries, "target.bin", Rar3UnpackerTest.partialFilterTarget(), new byte[]{'B',0,0}, true, true, true, false);
        File output = original();
        assertThrows(IOException.class, () -> extract(entries, output));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private List<RarArchiveReader.RarEntry> unfinishedFutureFixture(boolean encrypted, boolean split) throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        append(entries, "primer.bin", Rar3UnpackerTest.partialFilterTarget(), new byte[]{'B'}, false, encrypted, split, false);
        append(entries, "target.bin", Rar3UnpackerTest.deferredLzPrimer(true), new byte[]{'A'}, true, encrypted, split, false);
        return entries;
    }

    @Test public void partialFilterSelectedAndBulkAcrossPlainAesAndSplitPayloads() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) for (boolean split : new boolean[]{false, true}) {
            List<RarArchiveReader.RarEntry> entries = partialFixture(encrypted, split, false, false);
            File output = original();
            assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractEntry(target(entries), entries, output, PASSWORD, null));
            assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(output.toPath()));
            File directory = temporary.newFolder();
            assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(entries, directory, PASSWORD, null, null));
            assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(new File(directory, "primer.bin").toPath()));
            assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(new File(directory, "target.bin").toPath()));
            if (!encrypted && !split) {
                File plainSelected = original();
                assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(
                        target(entries), entries, plainSelected, null));
                assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(plainSelected.toPath()));
                File plainDirectory = temporary.newFolder();
                assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, plainDirectory, null, null, null));
                assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(new File(plainDirectory, "target.bin").toPath()));
            }
        }
    }

    @Test public void partialFilterForwardSkipsPrimerAndStoredMemberWithoutLosingPrefix() throws Exception {
        List<RarArchiveReader.RarEntry> entries = partialFixture(true, true, false, false);
        List<RarArchiveReader.RarEntry> stored = new ArrayList<>();
        appendStored(stored, "stored.bin", new byte[]{42, 43}, true, true, true);
        entries.addAll(entries.indexOf(target(entries)), stored);
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, PASSWORD, directory, true)) {
            assertNotNull(reader);
            assertEquals("primer.bin", reader.nextEntry().path);
            assertEquals("stored.bin", reader.nextEntry().path);
            assertEquals("target.bin", reader.nextEntry().path);
            byte[] bytes = {42, 42};
            assertEquals(1, reader.read(bytes)); assertEquals('A', bytes[0]);
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void partialFilterCrcFailureRestoresTargetAndRetiresForwardSession() throws Exception {
        for (boolean badPrimer : new boolean[]{false, true}) {
            List<RarArchiveReader.RarEntry> entries = partialFixture(false, true, badPrimer, !badPrimer);
            File output = original();
            assertThrows(IOException.class, () -> extract(entries, output));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
            File spool = temporary.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(entries, null, spool)) {
                reader.nextEntry();
                if (!badPrimer) reader.nextEntry();
                byte[] bytes = {42, 42};
                assertThrows(IOException.class, () -> reader.read(bytes));
                assertArrayEquals(new byte[]{42, 42}, bytes);
                assertThrows(IOException.class, () -> reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void partialFilterCannotBeLostAtIndependentReset() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        append(entries, "primer.bin", Rar3UnpackerTest.partialFilterPrimer(), new byte[]{'A'}, false, true, false, false);
        append(entries, "reset.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                new byte[]{'A','B'}, false, true, false, false);
        File directory = temporary.newFolder();
        File output = new File(directory, "primer.bin"); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractArchive(entries, directory, PASSWORD, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        assertFalse(new File(directory, "reset.bin").exists());
    }

    private List<RarArchiveReader.RarEntry> partialFixture(boolean encrypted, boolean split,
            boolean badPrimer, boolean badTarget) throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        append(entries, "primer.bin", Rar3UnpackerTest.partialFilterPrimer(), new byte[]{'A'}, false, encrypted, split, badPrimer);
        append(entries, "target.bin", Rar3UnpackerTest.partialFilterTarget(), new byte[]{'A'}, true, encrypted, split, badTarget);
        return entries;
    }

    @Test public void selectedAesSolidContinuationReusesCheckedLzTables() throws Exception { successful(true, false); }
    @Test public void selectedSplitSolidContinuationReusesCheckedLzTables() throws Exception { successful(false, true); }
    @Test public void selectedAesSplitSolidContinuationCrossesCbcBoundary() throws Exception { successful(true, true); }

    @Test public void badTargetCrcRestoresExistingOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true, true);
        File output = original();
        assertThrows(IOException.class, () -> extract(entries, output));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void missingRequiredTailFailsBeforeOpeningMissingPrimer() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true, false);
        RarArchiveReader.RarEntry target = target(entries);
        entries.get(0).sourceArchive = new File(temporary.getRoot(), "absent-primer.rar");
        entries.remove(entries.size() - 1);
        File output = original();
        IOException failure = assertThrows(IOException.class,
                () -> Rar3LogicalSolidArchiveExtractor.tryExtractEntry(target, entries, output, PASSWORD, null));
        assertTrue(failure.getMessage().contains("split continuation"));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void storedMemberIsNotHistoryAndUnprimedTargetRemainsExcluded() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true, false);
        RarArchiveReader.RarEntry target = target(entries);
        RarArchiveReader.RarEntry stored = new RarArchiveReader.RarEntry("stored.bin", false,
                0, 0, 0, 4, 0x30, false, false, false, null, 0, 0);
        entries.add(entries.indexOf(target), stored);
        assertNotNull(Rar3LogicalSolidArchiveExtractor.prepare(target, entries));
        assertNull(Rar3LogicalSolidArchiveExtractor.prepare(target, Arrays.asList(target)));
    }

    @Test public void unrelatedEarlierAndLaterDamageDoesNotEnterSelectedHistory() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true, false);
        RarArchiveReader.RarEntry target = target(entries);
        RarArchiveReader.RarEntry damaged = new RarArchiveReader.RarEntry("unrelated.bin", false,
                -1, -1, -1, 4, 0x33, false, false, true, null, -1, 0);
        entries.add(0, damaged);
        entries.add(damaged);
        File output = original();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractEntry(target, entries, output, PASSWORD, null));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(output.toPath()));
    }

    @Test public void bulkAesSolidRun() throws Exception { bulk(true, false); }
    @Test public void bulkSplitSolidRun() throws Exception { bulk(false, true); }
    @Test public void bulkAesSplitSolidRun() throws Exception { bulk(true, true); }

    @Test public void bulkMissingTailPreflightsBeforeAnyOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true, false);
        entries.remove(entries.size() - 1);
        File directory = temporary.newFolder();
        assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                entries, directory, PASSWORD, null, null));
        assertEquals(0, directory.list().length);
    }

    @Test public void bulkSnapshotsRoutingMetadataAndRejectsUnprimedRuns() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, true, false);
        Rar3LogicalSolidArchiveExtractor.Plan plan = Rar3LogicalSolidArchiveExtractor.prepareArchive(entries);
        File originalSource = plan.steps.get(0).entry.sourceArchive;
        entries.get(0).sourceArchive = null;
        assertEquals(originalSource, plan.steps.get(0).entry.sourceArchive);
        assertNull(Rar3LogicalSolidArchiveExtractor.prepareArchive(Arrays.asList(target(fixture(true, false, false)))));
    }

    @Test public void bulkRejectsLaterUnsafePathBeforeWritingPrimer() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true, false, false);
        RarArchiveReader.RarEntry last = entries.remove(1);
        RarArchiveReader.RarEntry unsafe = new RarArchiveReader.RarEntry("../escape.bin", false,
                last.unpackedSize, last.packedSize, 0, 4, last.method, true, false, false,
                last.encryption, last.dataCrc, 0);
        unsafe.sourceArchive = last.sourceArchive;
        entries.add(unsafe);
        File directory = temporary.newFolder();
        assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                entries, directory, PASSWORD, null, null));
        assertEquals(0, directory.list().length);
    }

    @Test public void bulkSupportsStoredAndIndependentCompanionsWithResets() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true, true, false);
        RarArchiveReader.RarEntry stored = new RarArchiveReader.RarEntry("stored.bin", false,
                1, 1, 0, 4, 0x30, false, false, false, null, crc(new byte[]{42}), 0);
        stored.sourceArchive = temporary.newFile();
        Files.write(stored.sourceArchive.toPath(), new byte[]{42});
        entries.add(stored);
        append(entries, "independent.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                new byte[]{'A','B'}, false, false, false, false);
        entries.add(new RarArchiveReader.RarEntry("empty", true, 0, 0, 0, 4, 0, false, false, false, null, 0, 0));
        File directory = temporary.newFolder();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(entries, directory, PASSWORD, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(new File(directory, "stored.bin").toPath()));
        assertArrayEquals(new byte[]{'A','B'}, Files.readAllBytes(new File(directory, "independent.bin").toPath()));
        assertTrue(new File(directory, "empty").isDirectory());
    }

    @Test public void bulkCollisionFailsBeforeReplacingEarlierPrimer() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, false, false);
        appendStored(entries, "primer.bin/child", new byte[]{42}, false, false);
        File directory = temporary.newFolder();
        File primer = new File(directory, "primer.bin");
        Files.write(primer.toPath(), new byte[]{99});
        assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                entries, directory, null, null, null));
        assertArrayEquals(new byte[]{99}, Files.readAllBytes(primer.toPath()));
        assertEquals(1, directory.list().length);
    }

    @Test public void bulkExplicitDirectoryCollisionFailsBeforeAnyOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, false, false);
        entries.add(new RarArchiveReader.RarEntry("target.bin/", true,
                0, 0, 0, 4, 0, false, false, false, null, 0, 0));
        File directory = temporary.newFolder();
        assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                entries, directory, null, null, null));
        assertEquals(0, directory.list().length);
    }

    private void bulk(boolean encrypted, boolean split) throws Exception {
        File directory = temporary.newFolder();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                fixture(encrypted, split, false), directory, PASSWORD, null, null));
        assertArrayEquals(new byte[]{'A','B'}, Files.readAllBytes(new File(directory, "primer.bin").toPath()));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(directory, "target.bin").toPath()));
    }

    @Test public void forwardAesSolidUsesExistingPasswordAwareDispatch() throws Exception { forward(true, false); }
    @Test public void forwardSplitSolidUsesExistingPasswordAwareDispatch() throws Exception { forward(false, true); }
    @Test public void forwardAesSplitSolidUsesExistingPasswordAwareDispatch() throws Exception { forward(true, true); }

    @Test public void forwardSkippedPrimerAdvancesStateWithoutSpool() throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(
                fixture(true, true, false), PASSWORD, directory)) {
            assertNotNull(reader.nextEntry());
            assertTrue(reader.drainCurrentEntry(2));
            assertEquals(0, directory.list().length);
            assertNotNull(reader.nextEntry());
            byte[] buffer = new byte[4];
            assertEquals(1, reader.read(buffer));
            assertEquals('B', buffer[0]);
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void forwardBadCrcExposesNoTargetBytesAndRetires() throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(
                fixture(false, true, true), PASSWORD, directory)) {
            reader.nextEntry(); reader.nextEntry();
            byte[] buffer = {42,42};
            assertThrows(IOException.class, () -> reader.read(buffer));
            assertArrayEquals(new byte[]{42,42}, buffer);
            assertThrows(IOException.class, () -> reader.nextEntry());
            assertEquals(0, directory.list().length);
        }
    }

    @Test public void forwardDrainLimitRejectsBeforeDecoding() throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(
                fixture(false, true, false), PASSWORD, directory)) {
            reader.nextEntry();
            assertThrows(IOException.class, () -> reader.drainCurrentEntry(1));
            assertEquals(0, directory.list().length);
            assertTrue(reader.drainCurrentEntry(2));
        }
    }

    @Test public void forwardInterruptedReadRetiresAndCleansSpool() throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(
                fixture(true, false, false), PASSWORD, directory)) {
            reader.nextEntry();
            assertEquals(2, reader.read(new byte[4]));
            Thread.currentThread().interrupt();
            try { assertThrows(java.io.InterruptedIOException.class, () -> reader.read(new byte[4])); }
            finally { Thread.interrupted(); }
            assertEquals(0, directory.list().length);
            assertThrows(IOException.class, () -> reader.nextEntry());
        }
    }

    private void forward(boolean encrypted, boolean split) throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                fixture(encrypted, split, false), PASSWORD, directory, true)) {
            assertNotNull(reader);
            assertNotNull(reader.nextEntry());
            byte[] buffer = new byte[4];
            assertEquals(2, reader.read(buffer));
            assertArrayEquals(new byte[]{'A','B'}, Arrays.copyOf(buffer, 2));
            assertEquals(-1, reader.read(buffer));
            assertNotNull(reader.nextEntry());
            assertEquals(1, reader.read(buffer));
            assertEquals('B', buffer[0]);
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void independentAesLzForwardCoverage() throws Exception { independentForward(true, false); }
    @Test public void independentSplitLzForwardCoverage() throws Exception { independentForward(false, true); }
    @Test public void independentAesSplitLzForwardCoverage() throws Exception { independentForward(true, true); }

    @Test public void plainCompressedWithAesSplitStoredCompanionCanStream() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        append(entries, "independent.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                new byte[]{'A','B'}, false, false, false, false);
        appendStored(entries, "stored.bin", new byte[]{42,43}, true, true);
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, PASSWORD, directory, true)) {
            assertNotNull(reader);
            reader.nextEntry();
            assertTrue(reader.drainCurrentEntry(2));
            reader.nextEntry();
            byte[] buffer = new byte[4];
            assertEquals(2, reader.read(buffer));
            assertArrayEquals(new byte[]{42,43}, Arrays.copyOf(buffer, 2));
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void plainSolidWithEncryptedStoredCompanionUsesBulkBridge() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, false, false);
        appendStored(entries, "stored.bin", new byte[]{42}, true, false);
        File directory = temporary.newFolder();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(entries, directory, PASSWORD, null, null));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(directory, "target.bin").toPath()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(new File(directory, "stored.bin").toPath()));
    }

    @Test public void storedOnlyBulkKeepsExistingRouteButPlainSolidIsAdmitted() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        appendStored(entries, "stored.bin", new byte[]{42}, true, true);
        assertNull(Rar3LogicalSolidArchiveExtractor.prepareArchive(entries));
        assertNotNull(Rar3LogicalSolidArchiveExtractor.prepareArchive(fixture(false, false, false)));
    }

    @Test public void selectedPlainSolidRunCrossesStoredMember() throws Exception {
        selectedAcrossStored(false, false);
    }

    @Test public void plainPlanSelectedSkipsMissingUnrelatedStoredPayload() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(false, false, false, false);
        entries.get(1).sourceArchive = new File(temporary.getRoot(), "missing-stored.rar");
        File output = original();
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(
                target(entries), entries, output, null));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(output.toPath()));
    }

    @Test public void plainPlanBulkPreservesHistoryAndVerifiesStoredFile() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(false, false, false, false);
        File directory = temporary.newFolder();
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, directory, null, null, null));
        assertArrayEquals(new byte[]{42,43}, Files.readAllBytes(new File(directory, "stored.bin").toPath()));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(directory, "target.bin").toPath()));
    }

    @Test public void plainPlanBulkRejectsBadStoredCrcBeforeContinuation() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(false, false, false, false);
        RarArchiveReader.RarEntry old = entries.get(1);
        RarArchiveReader.RarEntry bad = new RarArchiveReader.RarEntry(old.path, false,
                old.unpackedSize, old.packedSize, old.dataOffset, 4, 0x30, false,
                false, false, null, old.dataCrc ^ 1, 0);
        bad.sourceArchive = old.sourceArchive; entries.set(1, bad);
        File directory = temporary.newFolder();
        File stored = new File(directory, "stored.bin"); Files.write(stored.toPath(), new byte[]{99});
        assertThrows(IOException.class, () -> Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(
                entries, directory, null, null, null));
        assertArrayEquals(new byte[]{99}, Files.readAllBytes(stored.toPath()));
        assertFalse(new File(directory, "target.bin").exists());
    }

    @Test public void bulkPlainStoredMemberPreservesCompressedState() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(false, false, false, false);
        File directory = temporary.newFolder();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(entries, directory, null, null, null));
        assertArrayEquals(new byte[]{42,43}, Files.readAllBytes(new File(directory, "stored.bin").toPath()));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(directory, "target.bin").toPath()));
    }

    @Test public void forwardPlainSolidSkipsStoredWithoutLosingHistory() throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                interleaved(false, false, false, false), null, directory, true)) {
            assertNotNull(reader);
            assertEquals("primer.bin", reader.nextEntry().path);
            assertEquals("stored.bin", reader.nextEntry().path);
            assertEquals("target.bin", reader.nextEntry().path);
            byte[] buffer = new byte[4];
            assertEquals(1, reader.read(buffer));
            assertEquals('B', buffer[0]);
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void independentPlainLzForwardWorksWithoutNative() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        append(entries, "independent.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                new byte[]{'A','B'}, false, false, false, false);
        File directory = temporary.newFolder();
        assertNull(Rar3PpmdSolidArchiveExtractor.openForwardReader(entries, null, directory, true));
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, null, directory, false)) {
            assertNotNull(reader);
            reader.nextEntry();
            byte[] buffer = new byte[4];
            assertEquals(2, reader.read(buffer));
            assertArrayEquals(new byte[]{'A','B'}, Arrays.copyOf(buffer, 2));
            assertNull(reader.nextEntry());
        }
    }

    @Test public void plainSolidBadCrcStillRestoresOutput() throws Exception {
        File output = original();
        List<RarArchiveReader.RarEntry> entries = fixture(false, false, true);
        assertThrows(IOException.class, () -> extract(entries, output));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private void independentForward(boolean encrypted, boolean split) throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        append(entries, "independent.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                new byte[]{'A','B'}, false, encrypted, split, false);
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, PASSWORD, directory, true)) {
            assertNotNull(reader);
            assertNotNull(reader.nextEntry());
            byte[] buffer = new byte[4];
            assertEquals(2, reader.read(buffer));
            assertArrayEquals(new byte[]{'A','B'}, Arrays.copyOf(buffer, 2));
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    private void appendStored(List<RarArchiveReader.RarEntry> entries, String name, byte[] raw,
            boolean encrypted, boolean split) throws Exception {
        appendStored(entries, name, raw, encrypted, split, false);
    }

    private void appendStored(List<RarArchiveReader.RarEntry> entries, String name, byte[] raw,
            boolean encrypted, boolean split, boolean solid) throws Exception {
        int start = entries.size();
        append(entries, name, raw, raw, solid, encrypted, split, false);
        for (int i = start; i < entries.size(); i++) {
            RarArchiveReader.RarEntry part = entries.get(i);
            RarArchiveReader.RarEntry stored = new RarArchiveReader.RarEntry(part.path, false,
                    part.unpackedSize, part.packedSize, part.dataOffset, 4, 0x30, solid,
                    part.splitBefore, part.splitAfter, part.encryption, part.dataCrc, 0);
            stored.sourceArchive = part.sourceArchive;
            entries.set(i, stored);
        }
    }

    @Test public void storedAesOnlyForwardCoverage() throws Exception { storedForward(true, false, true); }
    @Test public void storedSplitOnlyForwardCoverage() throws Exception { storedForward(false, true, true); }
    @Test public void storedAesSplitOnlyForwardCoverage() throws Exception { storedForward(true, true, true); }
    @Test public void storedPlainOnlyForwardWithoutNative() throws Exception { storedForward(false, false, false); }

    @Test public void selectedPlainStoredSolidSplitCopiesWithoutDictionary() throws Exception {
        selectedStoredSolidSplit(false);
    }

    @Test public void selectedAesStoredSolidSplitCopiesAcrossCipherBlockBoundary() throws Exception {
        selectedStoredSolidSplit(true);
    }

    @Test public void bulkPlainStoredSolidSplitPreservesCompressedHistory() throws Exception {
        bulkStoredSolidSplit(false);
    }

    @Test public void bulkAesStoredSolidSplitPreservesCompressedHistory() throws Exception {
        bulkStoredSolidSplit(true);
    }

    @Test public void forwardPlainStoredSolidSplitPreservesCompressedHistory() throws Exception {
        forwardStoredSolidSplit(false);
    }

    @Test public void forwardAesStoredSolidSplitPreservesCompressedHistory() throws Exception {
        forwardStoredSolidSplit(true);
    }

    @Test public void selectedStoredSolidSplitBadCrcRestoresExistingOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        appendStored(entries, "stored.bin", new byte[]{42,43}, false, true, true);
        Files.write(entries.get(1).sourceArchive.toPath(), new byte[]{99});
        File output = original();

        IOException failure = assertThrows(IOException.class, () -> RarArchiveReader.extractStoredEntry(
                entries.get(0), output, null, entries, null));

        assertTrue(failure instanceof RarSplitStoredFailure.ExtractionException);
        assertEquals(RarSplitStoredFailure.Code.CRC_MISMATCH,
                ((RarSplitStoredFailure.ExtractionException) failure).failure().code);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void storedSolidSplitCannotPrimeCompressedContinuation() throws Exception {
        List<RarArchiveReader.RarEntry> entries = mixedStoredSolidSplit(false);
        RarArchiveReader.RarEntry compressedTarget = entries.get(entries.size() - 1);
        entries.remove(0);

        assertNull(Rar3LogicalSolidArchiveExtractor.prepare(compressedTarget, entries));
        assertNull(Rar3LogicalSolidArchiveExtractor.prepareArchive(entries));
    }

    private void selectedStoredSolidSplit(boolean encrypted) throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        appendStored(entries, "stored.bin", new byte[]{42,43}, encrypted, true, true);
        File output = original();

        RarArchiveReader.extractStoredEntry(entries.get(0), output, PASSWORD, entries, null);

        assertArrayEquals(new byte[]{42,43}, Files.readAllBytes(output.toPath()));
    }

    private void bulkStoredSolidSplit(boolean encrypted) throws Exception {
        File directory = temporary.newFolder();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                mixedStoredSolidSplit(encrypted), directory, PASSWORD, null, null));
        assertArrayEquals(new byte[]{42,43}, Files.readAllBytes(new File(directory, "stored.bin").toPath()));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(directory, "target.bin").toPath()));
    }

    private void forwardStoredSolidSplit(boolean encrypted) throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                mixedStoredSolidSplit(encrypted), PASSWORD, directory, true)) {
            assertNotNull(reader);
            assertEquals("primer.bin", reader.nextEntry().path);
            assertTrue(reader.drainCurrentEntry(2));
            assertEquals("stored.bin", reader.nextEntry().path);
            byte[] bytes = new byte[4];
            assertEquals(2, reader.read(bytes));
            assertArrayEquals(new byte[]{42,43}, Arrays.copyOf(bytes, 2));
            assertEquals("target.bin", reader.nextEntry().path);
            assertEquals(1, reader.read(bytes));
            assertEquals('B', bytes[0]);
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    private List<RarArchiveReader.RarEntry> mixedStoredSolidSplit(boolean encrypted) throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(false, false, false);
        List<RarArchiveReader.RarEntry> stored = new ArrayList<>();
        appendStored(stored, "stored.bin", new byte[]{42,43}, encrypted, true, true);
        entries.addAll(1, stored);
        return entries;
    }

    @Test public void storedPlainOnlyStillPrefersNativeAndBulkRoute() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        appendStored(entries, "stored.bin", new byte[]{42,43}, false, false);
        assertNull(Rar3PpmdSolidArchiveExtractor.openForwardReader(entries, null, temporary.newFolder(), true));
        assertNull(Rar3LogicalSolidArchiveExtractor.prepareArchive(entries));
    }

    @Test public void storedSkippedPayloadChecksCrcAndRemovesSpool() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        appendStored(entries, "stored.bin", new byte[]{42,43}, false, true);
        RarArchiveReader.RarEntry last = entries.get(entries.size() - 1);
        Files.write(last.sourceArchive.toPath(), new byte[]{99});
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, null, directory, true)) {
            reader.nextEntry();
            assertThrows(IOException.class, () -> reader.drainCurrentEntry(2));
            assertEquals(0, directory.list().length);
            assertThrows(IOException.class, () -> reader.nextEntry());
        }
    }

    @Test public void storedBadCrcExposesNoBytes() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        appendStored(entries, "stored.bin", new byte[]{42,43}, false, false);
        Files.write(entries.get(0).sourceArchive.toPath(), new byte[]{99,99});
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, null, directory, false)) {
            reader.nextEntry();
            byte[] bytes = {7,7};
            assertThrows(IOException.class, () -> reader.read(bytes));
            assertArrayEquals(new byte[]{7,7}, bytes);
            assertEquals(0, directory.list().length);
        }
    }

    private void storedForward(boolean encrypted, boolean split, boolean nativeAvailable) throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        appendStored(entries, "stored.bin", new byte[]{42,43}, encrypted, split);
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3PpmdSolidArchiveExtractor.openForwardReader(
                entries, PASSWORD, directory, nativeAvailable)) {
            assertNotNull(reader);
            assertNotNull(reader.nextEntry());
            byte[] bytes = new byte[4];
            assertEquals(2, reader.read(bytes));
            assertArrayEquals(new byte[]{42,43}, Arrays.copyOf(bytes, 2));
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void selectedAesRunCrossesStoredMember() throws Exception { selectedAcrossStored(true, false); }
    @Test public void selectedSplitRunCrossesStoredMember() throws Exception { selectedAcrossStored(false, true); }
    @Test public void selectedAesSplitRunCrossesStoredMember() throws Exception { selectedAcrossStored(true, true); }

    @Test public void selectedRunDoesNotOpenUnrelatedMissingStoredPayload() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(true, false, false, false);
        entries.get(1).sourceArchive = new File(temporary.getRoot(), "missing-stored.rar");
        File output = original();
        assertTrue(extract(entries, output));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(output.toPath()));
    }

    @Test public void bulkAesSplitStoredMemberPreservesCompressedState() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(true, true, true, true);
        File directory = temporary.newFolder();
        assertTrue(Rar3LogicalSolidArchiveExtractor.tryExtractArchive(entries, directory, PASSWORD, null, null));
        assertArrayEquals(new byte[]{42,43}, Files.readAllBytes(new File(directory, "stored.bin").toPath()));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(directory, "target.bin").toPath()));
    }

    @Test public void forwardSkippedStoredMemberPreservesCompressedState() throws Exception {
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(
                interleaved(true, true, true, true), PASSWORD, directory)) {
            assertNotNull(reader);
            reader.nextEntry();
            assertTrue(reader.drainCurrentEntry(2));
            assertEquals("stored.bin", reader.nextEntry().path);
            assertTrue(reader.drainCurrentEntry(2));
            assertEquals(0, directory.list().length);
            reader.nextEntry();
            byte[] data = new byte[4];
            assertEquals(1, reader.read(data));
            assertEquals('B', data[0]);
            assertNull(reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void badInterveningStoredCrcRetiresForwardBeforeTarget() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(true, false, false, false);
        Files.write(entries.get(1).sourceArchive.toPath(), new byte[]{99,99});
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(
                entries, PASSWORD, directory)) {
            reader.nextEntry();
            assertTrue(reader.drainCurrentEntry(2));
            reader.nextEntry();
            byte[] data = {7,7};
            assertThrows(IOException.class, () -> reader.read(data));
            assertArrayEquals(new byte[]{7,7}, data);
            assertThrows(IOException.class, () -> reader.nextEntry());
        }
        assertEquals(0, directory.list().length);
    }

    @Test public void storedMemberCannotPrimeCompressedContinuation() throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(true, false, false, false);
        entries.remove(0);
        assertNull(Rar3LogicalSolidArchiveExtractor.prepare(target(entries), entries));
        assertNull(Rar3LogicalSolidArchiveExtractor.prepareArchive(entries));
    }

    private void selectedAcrossStored(boolean encrypted, boolean split) throws Exception {
        List<RarArchiveReader.RarEntry> entries = interleaved(encrypted, split, false, false);
        File output = original();
        assertTrue(extract(entries, output));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(output.toPath()));
    }

    private List<RarArchiveReader.RarEntry> interleaved(boolean encrypted, boolean split,
            boolean storedEncrypted, boolean storedSplit) throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(encrypted, split, false);
        List<RarArchiveReader.RarEntry> stored = new ArrayList<>();
        appendStored(stored, "stored.bin", new byte[]{42,43}, storedEncrypted, storedSplit);
        entries.addAll(entries.indexOf(target(entries)), stored);
        return entries;
    }

    private void successful(boolean encrypted, boolean split) throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(encrypted, split, false);
        File directory = temporary.newFolder();
        File output = new File(directory, "selected.bin");
        assertTrue(extract(entries, output));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new String[]{"selected.bin"}, directory.list());
    }

    private boolean extract(List<RarArchiveReader.RarEntry> entries, File output) throws Exception {
        return Rar3LogicalSolidArchiveExtractor.tryExtractEntry(target(entries), entries, output, PASSWORD, null);
    }

    private static RarArchiveReader.RarEntry target(List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) if (entry.solid && !entry.splitBefore) return entry;
        throw new AssertionError("Missing target");
    }

    private List<RarArchiveReader.RarEntry> fixture(boolean encrypted, boolean split, boolean badCrc) throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        // Same checked two-entry stream used by existing plain mixed-solid regression sources.
        append(entries, "primer.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                new byte[]{'A','B'}, false, encrypted, split, false);
        append(entries, "target.bin", new byte[]{0x60, 0}, new byte[]{'B'}, true, encrypted, split, badCrc);
        return entries;
    }

    private void append(List<RarArchiveReader.RarEntry> entries, String name, byte[] packed,
            byte[] raw, boolean solid, boolean encrypted, boolean split, boolean badCrc) throws Exception {
        RarArchiveReader.EncryptionInfo encryption = null;
        if (encrypted) {
            Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(PASSWORD, SALT);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parameters.key, "AES"),
                    new IvParameterSpec(parameters.iv));
            packed = cipher.doFinal(Arrays.copyOf(packed, (packed.length + 15) / 16 * 16));
            encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(SALT.clone());
        }
        long finalCrc = crc(raw) ^ (badCrc ? 1 : 0);
        if (split) {
            int cut = Math.min(7, packed.length - 1);
            byte[] first = Arrays.copyOf(packed, cut);
            entries.add(part(name, first, raw.length, solid, false, true, crc(first), encryption));
            entries.add(part(name, Arrays.copyOfRange(packed, cut, packed.length), raw.length,
                    solid, true, false, finalCrc, encryption));
        } else entries.add(part(name, packed, raw.length, solid, false, false, finalCrc, encryption));
    }

    private RarArchiveReader.RarEntry part(String name, byte[] bytes, long unpacked, boolean solid,
            boolean before, boolean after, long checksum, RarArchiveReader.EncryptionInfo encryption) throws Exception {
        File source = temporary.newFile();
        Files.write(source.toPath(), bytes);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(name, false,
                unpacked, bytes.length, 0, 4, 0x33, solid, before, after, encryption, checksum, 0);
        entry.sourceArchive = source;
        return entry;
    }

    private File original() throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), new byte[]{42});
        return file;
    }

    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
