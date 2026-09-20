package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class Rar3FirstPartyArchiveExtractorTest {
    @Test public void classicFallbackDirectoryCollisionPreservesExistingFile() throws Exception {
        assertClassicDirectoryHandling(true);
    }

    @Test public void classicFallbackAcceptsExistingDirectory() throws Exception {
        assertClassicDirectoryHandling(false);
    }

    private void assertClassicDirectoryHandling(boolean collide) throws Exception {
        byte[] packed = syntheticPayload(block(new int[]{'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(directory("occupied"));
        entries.add(entry("plain.txt", writeArchive("classic-directory.payload", packed),
                packed, 1, crc("A"), false));
        assertTrue(Rar3FirstPartyArchiveExtractor.isArchiveLimitedFallbackAllowed(entries));
        File out = tempFolder.newFolder();
        File occupied = new File(out, "occupied");
        if (collide) Files.write(occupied.toPath(), new byte[]{42});
        else assertTrue(occupied.mkdir());
        try {
            assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, out, null, null, null));
            if (collide) throw new AssertionError("Existing file must not satisfy a directory entry");
            assertTrue(occupied.isDirectory());
            assertArrayEquals(new byte[]{'A'}, Files.readAllBytes(new File(out, "plain.txt").toPath()));
        } catch (java.io.IOException failure) {
            if (!collide) throw failure;
            assertTrue(failure.getMessage().contains("directory"));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(occupied.toPath()));
            assertFalse(new File(out, "plain.txt").exists());
        }
    }

    @Test public void forwardFailure_truncatedSpool() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "failure");
        File spool = tempFolder.newFolder();
        RarForwardFailureAssertions.truncatedSpool(Rar3CheckedForwardReader.open(entries, spool, false), spool);
    }

    @Test public void forwardFailure_deletionRetry() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "failure");
        File spool = tempFolder.newFolder();
        RarForwardFailureAssertions.deletionRetry(Rar3CheckedForwardReader.open(entries, spool, false), spool);
    }

    @Test public void forwardFailure_interruptionRetires() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "failure");
        File spool = tempFolder.newFolder();
        RarForwardFailureAssertions.interruptionRetires(Rar3CheckedForwardReader.open(entries, spool, false), spool);
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test public void checkedForwardRetainsTablesAcrossPagesAndCleansSpools() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "forward");
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            org.junit.Assert.assertNotNull(reader);
            assertFalse(reader.skipsUnreadEntryOnAdvance());
            org.junit.Assert.assertEquals("forward-first.txt", reader.nextEntry().path);
            assertArrayEquals(new byte[]{'A','B'}, readForward(reader));
            org.junit.Assert.assertEquals(1, spool.list().length);
            org.junit.Assert.assertEquals("forward-second.txt", reader.nextEntry().path);
            assertArrayEquals(new String[0], spool.list());
            assertArrayEquals(new byte[]{'B'}, readForward(reader));
            org.junit.Assert.assertNull(reader.nextEntry());
            org.junit.Assert.assertNull(reader.nextEntry());
        }
        assertArrayEquals(new String[0], spool.list());
    }

    @Test public void checkedForwardVerifiesSkippedPrimerWithoutSpoolingIt() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "skip");
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            reader.nextEntry();
            reader.nextEntry();
            assertArrayEquals(new String[0], spool.list());
            assertArrayEquals(new byte[]{'B'}, readForward(reader));
        }
    }

    @Test public void checkedForwardPreservesDirectoriesAndIndependentStoredBarriers() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "one");
        entries.add(1, directory("empty"));
        entries.add(stored("note.txt", "note", false, crc("note")));
        addCheckedRun(entries, "two");
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            reader.nextEntry();
            assertTrue(reader.nextEntry().directory);
            org.junit.Assert.assertEquals(-1, reader.read(new byte[1]));
            reader.nextEntry();
            assertArrayEquals(new byte[]{'B'}, readForward(reader));
            reader.nextEntry();
            assertArrayEquals("note".getBytes(StandardCharsets.UTF_8), readForward(reader));
            reader.nextEntry();
            reader.nextEntry();
            assertArrayEquals(new byte[]{'B'}, readForward(reader));
        }
        assertArrayEquals(new String[0], spool.list());
    }

    @Test public void checkedForwardNeverPublishesBadCrcAndRetiresState() throws Exception {
        byte[] payload = syntheticPayload(block(new int[]{'A','B',256}, "00011000"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("bad", writeArchive("bad-forward", payload), payload, 2, crc("wrong"), false));
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            reader.nextEntry();
            byte[] untouched = new byte[]{42};
            try { reader.read(untouched); throw new AssertionError("CRC must fail before publication"); }
            catch (java.io.IOException expected) { assertArrayEquals(new byte[]{42}, untouched); }
            assertArrayEquals(new String[0], spool.list());
            try { reader.nextEntry(); throw new AssertionError("Failed reader must remain retired"); }
            catch (java.io.IOException expected) { }
        }
    }

    @Test public void checkedForwardSkippedBadPrimerCannotExposeNextPage() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "bad-primer");
        RarArchiveReader.RarEntry first = entries.get(0);
        byte[] payload = Files.readAllBytes(first.sourceArchive.toPath());
        entries.set(0, entry(first.path, first.sourceArchive, payload, 2, crc("bad"), false));
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            reader.nextEntry();
            try { reader.nextEntry(); throw new AssertionError("Bad primer must stop advance"); }
            catch (java.io.IOException expected) { }
            assertArrayEquals(new String[0], spool.list());
        }
    }

    @Test public void checkedForwardDrainBoundAndEmptyReadDoNotStartDecoding() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "bound");
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            reader.nextEntry();
            org.junit.Assert.assertEquals(0, reader.read(new byte[0]));
            try { reader.drainCurrentEntry(1); throw new AssertionError("Drain bound must apply"); }
            catch (java.io.IOException expected) { }
            assertArrayEquals(new String[0], spool.list());
            assertArrayEquals(new byte[]{'A','B'}, readForward(reader));
        }
    }

    @Test public void checkedForwardCloseDuringPartialReadDeletesOwnedSpool() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "close");
        File spool = tempFolder.newFolder();
        ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false);
        try {
            reader.nextEntry();
            org.junit.Assert.assertEquals(1, reader.read(new byte[1]));
            org.junit.Assert.assertEquals(1, spool.list().length);
        } finally { reader.close(); }
        reader.close();
        assertArrayEquals(new String[0], spool.list());
        try { reader.read(new byte[1]); throw new AssertionError("Closed reader must reject reads"); }
        catch (java.io.IOException expected) { }
    }

    @Test public void checkedForwardRetainsNativePreferenceAndRejectsMissingPrimer() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "eligibility");
        File spool = tempFolder.newFolder();
        org.junit.Assert.assertNull(Rar3CheckedForwardReader.open(entries, spool, true));
        entries.remove(0);
        org.junit.Assert.assertNull(Rar3CheckedForwardReader.open(entries, spool, false));
        assertArrayEquals(new String[0], spool.list());
    }

    @Test public void checkedForwardStoredCrcFailureCleansSpool() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(stored("bad-stored", "bad", false, crc("wrong")));
        addCheckedRun(entries, "stored-failure");
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); throw new AssertionError("Stored CRC must fail"); }
            catch (java.io.IOException expected) { }
            assertArrayEquals(new String[0], spool.list());
        }
    }

    @Test public void checkedForwardInterruptionRetiresReaderAndCleansSpool() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "cancel");
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            reader.nextEntry();
            reader.read(new byte[1]);
            Thread.currentThread().interrupt();
            try { reader.nextEntry(); throw new AssertionError("Interrupted reader must stop"); }
            catch (java.io.IOException expected) { }
            finally { Thread.interrupted(); }
            assertArrayEquals(new String[0], spool.list());
            try { reader.read(new byte[1]); throw new AssertionError("Cancelled state cannot resume"); }
            catch (java.io.IOException expected) { }
        }
    }

    private static byte[] readForward(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[7];
        int n;
        while ((n = reader.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    @Test public void checkedForwardRejectsEncryptedAndSplitMixedCandidates() throws Exception {
        byte[] payload = syntheticPayload(block(new int[]{'A','B',256}, "00011000"));
        File archive = writeArchive("excluded-forward", payload);
        File spool = tempFolder.newFolder();
        for (boolean encrypted : new boolean[]{false, true}) {
            RarArchiveReader.RarEntry candidate = entryWithOptions("excluded", archive, payload,
                    2, crc("AB"), 4, 0x33, false, false, !encrypted,
                    encrypted ? RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8]) : null);
            org.junit.Assert.assertNull(Rar3CheckedForwardReader.open(
                    java.util.Collections.singletonList(candidate), spool, false));
        }
        assertArrayEquals(new String[0], spool.list());
    }

    @Test public void checkedForwardRequiresExplicitFileBoundaryBeforePublication() throws Exception {
        byte[] payload = syntheticPayload(block(new int[]{'A',256}, "0"));
        RarArchiveReader.RarEntry candidate = entry("no-boundary",
                writeArchive("no-boundary-forward", payload), payload, 1, crc("A"), false);
        File spool = tempFolder.newFolder();
        byte[] continuation = new byte[]{0x60};
        RarArchiveReader.RarEntry later = entry("later", writeArchive("later-boundary.payload", continuation),
                continuation, 1, crc("A"), true);
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(
                java.util.Arrays.asList(candidate, later), spool, false)) {
            org.junit.Assert.assertNotNull(reader);
            reader.nextEntry();
            try { reader.read(new byte[1]); throw new AssertionError("Missing boundary must fail"); }
            catch (java.io.IOException expected) { }
            assertArrayEquals(new String[0], spool.list());
        }
    }

    @Test public void plannedForwardReadsSizeTerminatedIndependentLz() throws Exception {
        RarArchiveReader.RarEntry standalone = sizeTerminated("standalone", crc("A"));
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(
                java.util.Collections.singletonList(standalone), spool, false)) {
            org.junit.Assert.assertNotNull(reader);
            org.junit.Assert.assertEquals("standalone", reader.nextEntry().path);
            assertArrayEquals(new byte[]{'A'}, readForward(reader));
            org.junit.Assert.assertNull(reader.nextEntry());
        }
        assertArrayEquals(new String[0], spool.list());
    }

    @Test public void plannedBulkAndForwardMixIndependentLzWithCheckedRuns() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(sizeTerminated("before", crc("A")));
        entries.add(directory("folder"));
        addCheckedRun(entries, "planned");
        entries.add(sizeTerminated("after", crc("A")));
        File out = tempFolder.newFolder();
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, out, null, null, null));
        assertTrue(new File(out, "folder").isDirectory());
        String[] names = {"before", "folder/", "planned-first.txt", "planned-second.txt", "after"};
        String[] expected = {"A", "", "AB", "B", "A"};
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            for (int i = 0; i < names.length; i++) {
                org.junit.Assert.assertEquals(names[i], reader.nextEntry().path);
                assertArrayEquals(expected[i].getBytes(StandardCharsets.UTF_8), readForward(reader));
                if (i != 1) assertArrayEquals(expected[i].getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(new File(out, names[i]).toPath()));
            }
            org.junit.Assert.assertNull(reader.nextEntry());
        }
        assertArrayEquals(new String[0], spool.list());
    }

    @Test public void plannedDiscardVerifiesIndependentCrcWithoutSpooling() throws Exception {
        for (boolean valid : new boolean[]{true, false}) {
            List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
            entries.add(sizeTerminated("skip-" + valid, crc(valid ? "A" : "B")));
            addCheckedRun(entries, "after-skip-" + valid);
            File spool = tempFolder.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
                reader.nextEntry();
                try {
                    reader.nextEntry();
                    if (!valid) throw new AssertionError("Skipped file CRC must be checked");
                    assertArrayEquals(new String[0], spool.list());
                    assertArrayEquals(new byte[]{'A','B'}, readForward(reader));
                } catch (java.io.IOException failure) {
                    if (valid) throw failure;
                    org.junit.Assert.assertThrows(java.io.IOException.class, reader::nextEntry);
                }
            }
            assertArrayEquals(new String[0], spool.list());
        }
    }

    @Test public void plannedTargetPrimesAcrossDirectoriesWithoutCreatingThem() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "target-plan");
        entries.add(1, directory("unrequested"));
        File destination = tempFolder.newFolder();
        File out = new File(destination, "selected.txt");
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(
                entries.get(2), entries, out, null));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(out.toPath()));
        assertArrayEquals(new String[]{"selected.txt"}, destination.list());
    }

    @Test public void plannedForwardOwnsSourceAndEntryListSnapshot() throws Exception {
        RarArchiveReader.RarEntry candidate = sizeTerminated("snapshot", crc("A"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>(); entries.add(candidate);
        File spool = tempFolder.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = Rar3CheckedForwardReader.open(entries, spool, false)) {
            entries.clear();
            candidate.sourceArchive = tempFolder.newFile();
            reader.nextEntry();
            assertArrayEquals(new byte[]{'A'}, readForward(reader));
        }
        assertArrayEquals(new String[0], spool.list());
    }

    @Test public void plannedExecutorCannotResumeAfterFailure() throws Exception {
        RarArchiveReader.RarEntry candidate = sizeTerminated("failed-plan", crc("B"));
        Rar3DecodePlan plan = Rar3DecodePlan.forArchive(java.util.Collections.singletonList(candidate));
        Rar3FirstPartyArchiveExtractor.PlannedDecoder decoder = new Rar3FirstPartyArchiveExtractor.PlannedDecoder(plan);
        File out = tempFolder.newFile(); Files.write(out.toPath(), new byte[]{42});
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> decoder.extractNext(out, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> decoder.extractNext(out, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
    }

    @Test public void plannedIndependentArchivePreflightsStoredChecksums() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(sizeTerminated("preflight-independent", crc("A")));
        entries.add(stored("no-stored-crc", "x", false, -1));
        assertCheckedDeclinedWithoutOutput(entries);
    }

    private RarArchiveReader.RarEntry sizeTerminated(String name, long checksum) throws Exception {
        byte[] packed = syntheticPayload(block(new int[]{'A',256}, "0"));
        return entry(name, writeArchive(name + ".payload", packed), packed, 1, checksum, false);
    }

    @Test public void checkedFallbackAllowsIndependentStoredFilesAroundSolidRuns() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(stored("before.txt", "start", false, crc("start")));
        addCheckedRun(entries, "left");
        entries.add(stored("between.txt", "middle", false, crc("middle")));
        addCheckedRun(entries, "right");
        entries.add(stored("empty.txt", "", false, 0));
        File out = tempFolder.newFolder();
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, out, null, null, null));
        assertArrayEquals("start".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(out, "before.txt").toPath()));
        assertArrayEquals("middle".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(out, "between.txt").toPath()));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(out, "left-second.txt").toPath()));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(out, "right-second.txt").toPath()));
        assertTrue(new File(out, "empty.txt").isFile());
        assertArrayEquals(new byte[0], Files.readAllBytes(new File(out, "empty.txt").toPath()));
    }

    @Test public void checkedFallbackPreservesEmptyDirectoriesWithoutResettingSolidState() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "dirs");
        entries.add(1, directory("empty/nested"));
        File out = tempFolder.newFolder();
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, out, null, null, null));
        assertTrue(new File(out, "empty/nested").isDirectory());
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(new File(out, "dirs-second.txt").toPath()));
    }

    @Test public void checkedFallbackStoredCrcFailurePreservesExistingTarget() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "crc");
        entries.add(stored("existing.txt", "new", false, crc("wrong")));
        File out = tempFolder.newFolder();
        File existing = new File(out, "existing.txt");
        Files.write(existing.toPath(), new byte[]{42});
        try {
            Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, out, null, null, null);
            throw new AssertionError("Stored CRC failure must propagate");
        } catch (java.io.IOException expected) {
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        }
    }

    @Test public void checkedFallbackRejectsSolidStoredBeforeAnyOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "solid-store");
        entries.add(stored("stored.txt", "x", true, crc("x")));
        assertCheckedDeclinedWithoutOutput(entries);
    }

    @Test public void checkedFallbackDoesNotCarryHistoryAcrossIndependentStoredEntry() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "barrier");
        entries.add(1, stored("barrier.txt", "x", false, crc("x")));
        assertCheckedDeclinedWithoutOutput(entries);
    }

    @Test public void checkedFallbackRequiresStoredCrcBeforeAnyOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "missing-crc");
        entries.add(stored("stored.txt", "x", false, -1));
        assertCheckedDeclinedWithoutOutput(entries);
    }

    @Test public void checkedFallbackRejectsStoredSizeMismatchBeforeAnyOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "size");
        byte[] payload = new byte[]{'x'};
        entries.add(entryWithOptions("stored.txt", writeArchive("size-store.payload", payload),
                payload, 2, crc("x"), 4, 0x30, false, false, false, null));
        assertCheckedDeclinedWithoutOutput(entries);
    }

    @Test public void checkedFallbackDirectoryCollisionPreservesExistingFile() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        addCheckedRun(entries, "collision");
        entries.add(0, directory("occupied"));
        File out = tempFolder.newFolder();
        File existing = new File(out, "occupied");
        Files.write(existing.toPath(), new byte[]{42});
        try {
            Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, out, null, null, null);
            throw new AssertionError("Directory must not replace a file");
        } catch (java.io.IOException expected) {
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
            assertFalse(new File(out, "collision-first.txt").exists());
        }
    }

    private void addCheckedRun(List<RarArchiveReader.RarEntry> entries, String prefix) throws Exception {
        byte[] first = syntheticPayload(block(new int[]{'A','B',256}, "00011000"));
        byte[] second = new byte[]{0x60};
        entries.add(entry(prefix + "-first.txt", writeArchive(prefix + "-first.payload", first),
                first, 2, crc("AB"), false));
        entries.add(entry(prefix + "-second.txt", writeArchive(prefix + "-second.payload", second),
                second, 1, crc("B"), true));
    }

    private RarArchiveReader.RarEntry stored(String path, String text, boolean solid, long checksum) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        return entryWithOptions(path, writeArchive(path + ".payload", payload), payload,
                payload.length, checksum, 4, 0x30, solid, false, false, null);
    }

    private static RarArchiveReader.RarEntry directory(String path) {
        return new RarArchiveReader.RarEntry(path, true, 0, 0, 0, 4, 0x30,
                false, false, false, null, 0, 0);
    }

    private void assertCheckedDeclinedWithoutOutput(List<RarArchiveReader.RarEntry> entries) throws Exception {
        File out = tempFolder.newFolder();
        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, out, null, null, null));
        assertArrayEquals(new String[0], out.list());
    }

    @Test public void checkedSolidFallbackDecodesTablelessTarget() throws Exception {
        byte[] first = syntheticPayload(block(new int[]{'A','B',256}, "00011000"));
        byte[] second = new byte[]{0x60}; // B, EOF, reuse tables: 01 10 00.
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("first.txt", writeArchive("checked-first.payload", first), first, 2, crc("AB"), false));
        entries.add(entry("second.txt", writeArchive("checked-second.payload", second), second, 1, crc("B"), true));
        File directory = tempFolder.newFolder();
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, directory, null, null, null));
        assertArrayEquals(new byte[]{'A','B'}, Files.readAllBytes(new File(directory,"first.txt").toPath()));
        File single = tempFolder.newFile();
        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entries.get(1), entries, single, null));
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(single.toPath()));
    }

    @Test public void checkedSolidFallbackNeverPublishesTargetAfterPrimerCrcFailure() throws Exception {
        byte[] first = syntheticPayload(block(new int[]{'A','B',256}, "00011000"));
        byte[] second = new byte[]{0x60};
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("first.txt", writeArchive("bad-primer.payload", first), first, 2, crc("XX"), false));
        entries.add(entry("second.txt", writeArchive("bad-target.payload", second), second, 1, crc("B"), true));
        File target = tempFolder.newFile(); Files.write(target.toPath(), new byte[]{42});
        try {
            Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entries.get(1), entries, target, null);
            throw new AssertionError("Bad primer CRC");
        } catch (java.io.IOException expected) { assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath())); }
    }

    @Test public void checkedSolidFallbackRejectsMissingCrcBeforeOutput() throws Exception {
        byte[] first = syntheticPayload(block(new int[]{'A',256}, "000100"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("first.txt", writeArchive("unknown-crc.payload", first), first, 1, -1, false));
        entries.add(entry("second.txt", writeArchive("unknown-target.payload", first), first, 1, crc("A"), true));
        File target = tempFolder.newFile(); Files.write(target.toPath(), new byte[]{42});
        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entries.get(1), entries, target, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void tryExtractArchive_decodesSyntheticNonSolidAndSolidSequence() throws Exception {
        byte[] firstPacked = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] solidPrimerPacked = syntheticPayload(
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'D', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] solidSecondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("plain.txt", writeArchive("plain.payload", firstPacked), firstPacked, 1, crc("A"), false));
        entries.add(entry("solid-primer.txt", writeArchive("solid-primer.payload", solidPrimerPacked), solidPrimerPacked, 3, crc("BCD"), true));
        entries.add(entry("solid-copy.txt", writeArchive("solid-copy.payload", solidSecondPacked), solidSecondPacked, 3, crc("BCD"), true));
        File target = tempFolder.newFolder("out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchive(entries, target, null, null, null));

        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "plain.txt").toPath()));
        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "solid-primer.txt").toPath()));
        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "solid-copy.txt").toPath()));
    }

    @Test
    public void tryExtractSingleEntry_primesPreviousSolidEntries() throws Exception {
        byte[] primerPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        RarArchiveReader.RarEntry primer = entry("primer.txt", writeArchive("primer.payload", primerPacked), primerPacked, 3, crc("ABC"), true);
        RarArchiveReader.RarEntry second = entry("second.txt", writeArchive("second.payload", secondPacked), secondPacked, 3, crc("ABC"), true);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(primer);
        entries.add(second);
        File out = tempFolder.newFile("single-solid.txt");
        assertTrue(out.delete());

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntry(second, entries, out, null));

        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }


    @Test
    public void tryExtractSingleEntry_primesSolidTargetFromLeadingNonSolidEntry() throws Exception {
        byte[] primerPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'D', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] targetPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        RarArchiveReader.RarEntry primer = entry("first-in-solid-run.txt", writeArchive("first-in-solid-run.payload", primerPacked), primerPacked, 4, crc("ABCD"), false);
        RarArchiveReader.RarEntry target = entry("solid-target.txt", writeArchive("solid-target.payload", targetPacked), targetPacked, 3, crc("BCD"), true);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(primer);
        entries.add(target);
        File out = tempFolder.newFile("solid-target.txt");
        assertTrue(out.delete());

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntry(target, entries, out, null));

        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void tryExtractArchive_keepsLeadingNonSolidDictionaryForFollowingSolidEntry() throws Exception {
        byte[] primerPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'D', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] targetPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("first.txt", writeArchive("archive-first.payload", primerPacked), primerPacked, 4, crc("ABCD"), false));
        entries.add(entry("second.txt", writeArchive("archive-second.payload", targetPacked), targetPacked, 3, crc("BCD"), true));
        File targetDir = tempFolder.newFolder("archive-out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchive(entries, targetDir, null, null, null));

        assertArrayEquals("ABCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "first.txt").toPath()));
        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "second.txt").toPath()));
    }

    @Test
    public void tryExtractArchive_bootstrapsFirstEntryMarkedSolidFromEmptyDictionary() throws Exception {
        byte[] firstPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("first-solid.txt", writeArchive("first-solid.payload", firstPacked), firstPacked, 3, crc("ABC"), true));
        entries.add(entry("second-solid.txt", writeArchive("second-solid.payload", secondPacked), secondPacked, 3, crc("ABC"), true));
        File targetDir = tempFolder.newFolder("solid-bootstrap-out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchive(entries, targetDir, null, null, null));

        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "first-solid.txt").toPath()));
        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "second-solid.txt").toPath()));
    }

    @Test
    public void limitedFallbackExtractsOnlyNonSolidClassicLz() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry entry = entry("limited.txt", writeArchive("limited.payload", packed), packed, 1, crc("A"), false);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry);
        File target = tempFolder.newFolder("limited-out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "limited.txt").toPath()));
    }

    @Test
    public void limitedFallbackRejectsPpmdBeforeClassicLzEngine() throws Exception {
        byte[] ppmdPacked = new byte[] {(byte) 0x80, 0x00, 0x00, 0x00};
        RarArchiveReader.RarEntry ppmd = entry("ppmd.txt", writeArchive("limited-ppmd.payload", ppmdPacked), ppmdPacked, 1, crc("A"), false);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(ppmd);
        File target = tempFolder.newFolder("limited-ppmd-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));
        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(ppmd, entries, tempFolder.newFile("ppmd-out.txt"), null));
    }

    @Test
    public void limitedFallbackDoesNotEnableSolidClassicLz() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry entry = entry("solid.txt", writeArchive("limited-solid.payload", packed), packed, 1, crc("A"), true);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry);
        File target = tempFolder.newFolder("limited-solid-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));
    }

    @Test
    public void tryExtractSingleEntry_rejectsEncryptedCompressedCandidate() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry encrypted = new RarArchiveReader.RarEntry(
                "encrypted.txt",
                false,
                1,
                packed.length,
                0,
                4,
                0x33,
                false,
                false,
                false,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8}),
                crc("A"),
                0);
        encrypted.sourceArchive = writeArchive("encrypted.payload", packed);
        File out = tempFolder.newFile("encrypted-out.txt");
        assertTrue(out.delete());

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntry(encrypted, java.util.Collections.singletonList(encrypted), out, null));
        assertFalse(out.exists());
    }


    @Test
    public void limitedFallbackRejectsMixedEncryptedStoredBeforeWriting() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("limited.txt", writeArchive("mixed-limited.payload", packed), packed, 1, crc("A"), false));
        entries.add(entryWithOptions(
                "encrypted-stored.txt",
                writeArchive("mixed-encrypted-stored.payload", "B".getBytes(StandardCharsets.UTF_8)),
                "B".getBytes(StandardCharsets.UTF_8),
                1,
                crc("B"),
                4,
                0x30,
                false,
                false,
                false,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8})));
        File target = tempFolder.newFolder("mixed-encrypted-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertFalse(new File(target, "limited.txt").exists());
        assertFalse(new File(target, "encrypted-stored.txt").exists());
    }

    @Test
    public void limitedFallbackRejectsMixedSplitStoredBeforeWriting() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("limited.txt", writeArchive("mixed-split-limited.payload", packed), packed, 1, crc("A"), false));
        entries.add(entryWithOptions(
                "split-stored.txt",
                writeArchive("mixed-split-stored.payload", "B".getBytes(StandardCharsets.UTF_8)),
                "B".getBytes(StandardCharsets.UTF_8),
                1,
                crc("B"),
                4,
                0x30,
                false,
                false,
                true,
                null));
        File target = tempFolder.newFolder("mixed-split-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertFalse(new File(target, "limited.txt").exists());
        assertFalse(new File(target, "split-stored.txt").exists());
    }

    @Test
    public void limitedFallbackRejectsMissingSolidBoundaryBeforeWriting() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("limited.txt", writeArchive("mixed-solid-limited.payload", packed), packed, 1, crc("A"), false));
        entries.add(entry("solid.txt", writeArchive("mixed-solid.payload", packed), packed, 1, crc("A"), true));
        File target = tempFolder.newFolder("mixed-solid-out");

        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertFalse(new File(target, "limited.txt").exists());
        assertFalse(new File(target, "solid.txt").exists());
    }

    @Test
    public void limitedFallbackDeletesOutputOnCrcMismatch() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry entry = entry(
                "bad-crc.txt",
                writeArchive("bad-crc.payload", packed),
                packed,
                1,
                crc("not A"),
                false);
        File out = tempFolder.newFile("bad-crc.txt");
        assertTrue(out.delete());

        try {
            Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entry, java.util.Collections.singletonList(entry), out, null);
            assertFalse("CRC mismatch should fail before commit", true);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            // expected
        }

        assertFalse(out.exists());
    }

    private RarArchiveReader.RarEntry entry(String path, File archive, byte[] packed, long unpackedSize, long crc, boolean solid) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                path,
                false,
                unpackedSize,
                packed.length,
                0,
                4,
                0x33,
                solid,
                false,
                false,
                null,
                crc,
                0);
        entry.sourceArchive = archive;
        return entry;
    }


    private RarArchiveReader.RarEntry entryWithOptions(String path,
                                                       File archive,
                                                       byte[] packed,
                                                       long unpackedSize,
                                                       long crc,
                                                       int rarVersion,
                                                       int method,
                                                       boolean solid,
                                                       boolean splitBefore,
                                                       boolean splitAfter,
                                                       RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                path,
                false,
                unpackedSize,
                packed.length,
                0,
                rarVersion,
                method,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                crc,
                0);
        entry.sourceArchive = archive;
        return entry;
    }

    private File writeArchive(String name, byte[] packed) throws Exception {
        File archive = tempFolder.newFile(name);
        Files.write(archive.toPath(), packed);
        return archive;
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
        bits.writeBits(0, 2);
        for (int i = 0; i < Rar3HuffmanTables.BC; i++) {
            bits.writeBits(i == 0 || i == 1 || i == 2 || i == 18 ? 2 : 0, 4);
        }
        int[] lengths = new int[Rar3HuffmanTables.TABLE_SIZE];
        for (int symbol : mainSymbols) lengths[symbol] = 2;
        for (int symbol : distanceSymbols) lengths[Rar3HuffmanTables.NC + symbol] = 1;
        writeMainTableLengths(bits, lengths);
    }

    private static void writeMainTableLengths(BitWriter bits, int[] lengths) {
        for (int i = 0; i < lengths.length;) {
            if (lengths[i] == 0) {
                int count = 0;
                while (i + count < lengths.length && lengths[i + count] == 0 && count < 10) count++;
                if (count >= 3) {
                    bits.writeBitString("11");
                    bits.writeBits(count - 3, 3);
                    i += count;
                } else {
                    bits.writeBitString("00");
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
