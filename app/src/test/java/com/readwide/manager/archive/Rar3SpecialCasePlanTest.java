package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Planning and output safety sources; not an encoder interoperability claim. */
public class Rar3SpecialCasePlanTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void preparedPlainRoutesProbeOnceAndDoNotRescanTheArchiveDuringDecode() throws Exception {
        byte[] payload = Rar3FirstPartyArchiveExtractorTest.independentLzFixture();
        File source = temp.newFile(); Files.write(source.toPath(), payload);
        List<RarArchiveReader.RarEntry> entries = new LinkedList<>();
        for (int i = 0; i < 2000; i++) entries.add(entry("image-" + i, source, 0x33,
                payload.length, 2, crc(new byte[]{'A','B'}), false, false, null));
        int[] probes = {0};
        Rar3Or4SpecialCaseExtractor.ArchivePlan plan = Rar3Or4SpecialCaseExtractor.prepareArchivePlan(
                entries, null, candidate -> { probes[0]++; return classic(); });
        assertNotNull(plan);
        assertEquals(2000, plan.steps.size());
        assertEquals(2000, probes[0]);
        Rar3Or4SpecialCaseExtractor.PreparedEntry step = plan.steps.get(0);
        assertEquals(Rar3Or4SpecialCaseExtractor.Route.PLAIN_COMPRESSED, step.route);
        entries.get(0).sourceArchive = new File("replaced-after-planning");
        entries.clear();
        File out = temp.newFile();
        assertTrue(step.extract(out, null, null));
        assertArrayEquals(new byte[]{'A','B'}, Files.readAllBytes(out.toPath()));
        assertEquals(2000, probes[0]);
        assertThrows(UnsupportedOperationException.class, () -> plan.steps.clear());
    }

    @Test public void independentPpmdResetGetsItsExistingBoundaryCheckedPlan() throws Exception {
        File source = temp.newFile();
        RarArchiveReader.RarEntry ppmd = entry("ppmd", source, 0x33, 2, 1, 0,
                false, false, null);
        Rar3Or4SpecialCaseExtractor.ArchivePlan plan = Rar3Or4SpecialCaseExtractor.prepareArchivePlan(
                Collections.singletonList(ppmd), null, candidate -> new Rar3PpmdBlockProbe.Result(
                        Rar3PpmdBlockProbe.KIND_PPMD, false, 0xa000, 0, "reset"));
        assertNotNull(plan);
        assertEquals(Rar3DecodePlan.Action.START_CHECKED, plan.steps.get(0).plainPlan.step(0).action);
        assertNull(Rar3Or4SpecialCaseExtractor.prepareArchivePlan(Collections.singletonList(ppmd), null,
                candidate -> new Rar3PpmdBlockProbe.Result(
                        Rar3PpmdBlockProbe.KIND_PPMD, true, 0x8000, 0, "missing model")));
    }

    @Test public void splitPlanOwnsMetadataAndSkipsOnlyClaimedContinuations() throws Exception {
        byte[] salt = {1,2,3,4,5,6,7,8};
        RarArchiveReader.EncryptionInfo encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(salt);
        File first = temp.newFile(), last = temp.newFile();
        RarArchiveReader.RarEntry head = entry("secret", first, 0x33, 7, 2, 0, false, true, encryption);
        RarArchiveReader.RarEntry tail = entry("secret", last, 0x33, 9, 2, 0, true, false, encryption);
        Rar3Or4SpecialCaseExtractor.ArchivePlan plan = Rar3Or4SpecialCaseExtractor.prepareArchivePlan(
                Arrays.asList(head, directory("empty"), tail), new char[]{'p'}, candidate -> {
                    throw new AssertionError("Encrypted bytes must not be probed as plain headers");
                });
        assertEquals(2, plan.steps.size());
        assertEquals(Rar3Or4SpecialCaseExtractor.Route.AES_SPLIT, plan.steps.get(0).route);
        assertEquals(2, plan.steps.get(0).chain.size());
        salt[0] = 99; tail.sourceArchive = new File("changed-volume");
        assertEquals(1, plan.steps.get(0).entry.encryption.salt[0]);
        assertEquals(1, plan.steps.get(0).chain.get(1).encryption.salt[0]);
        assertEquals(last, plan.steps.get(0).chain.get(1).sourceArchive);
        assertThrows(UnsupportedOperationException.class, () -> plan.steps.get(0).chain.clear());
    }

    @Test public void orphanContinuationRejectsTheWholePlanBeforeCreatingOutput() throws Exception {
        RarArchiveReader.RarEntry stored = stored("kept.txt");
        RarArchiveReader.RarEntry orphan = entry("missing-head", temp.newFile(), 0x33,
                2, 1, 0, true, false, null);
        File target = new File(temp.getRoot(), "not-created");
        assertThrows(IOException.class, () -> Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                Arrays.asList(stored, orphan), target, null, null, null));
        assertFalse(target.exists());
    }

    @Test public void incompleteSplitCannotPublishAnEarlierStoredFile() throws Exception {
        RarArchiveReader.RarEntry head = entry("missing-tail", temp.newFile(), 0x33,
                2, 1, 0, false, true, null);
        File target = new File(temp.getRoot(), "not-created");
        assertThrows(IOException.class, () -> Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                Arrays.asList(stored("earlier.txt"), head), target, null, null, null));
        assertFalse(target.exists());
    }

    @Test public void twoHeadsCannotClaimTheSameContinuation() throws Exception {
        RarArchiveReader.RarEntry first = entry("shared", temp.newFile(), 0x33, 2, 1, 0, false, true, null);
        RarArchiveReader.RarEntry second = entry("shared", temp.newFile(), 0x33, 2, 1, 0, false, true, null);
        RarArchiveReader.RarEntry tail = entry("shared", temp.newFile(), 0x33, 2, 1, 0, true, false, null);
        File target = new File(temp.getRoot(), "not-created");
        assertThrows(IOException.class, () -> Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                Arrays.asList(stored("earlier.txt"), first, second, tail), target, null, null, null));
        assertFalse(target.exists());
    }

    @Test public void metadataSnapshotsDoNotHideRepeatedPartIdentity() throws Exception {
        RarArchiveReader.RarEntry head = entry("shared", temp.newFile(), 0x33, 2, 1, 0, false, true, null);
        RarArchiveReader.RarEntry middle = entry("shared", temp.newFile(), 0x33, 2, 1, 0, true, true, null);
        RarArchiveReader.RarEntry tail = entry("shared", temp.newFile(), 0x33, 2, 1, 0, true, false, null);
        IOException failure = assertThrows(IOException.class, () -> Rar3Or4SpecialCaseExtractor.prepareArchivePlan(
                Arrays.asList(head, middle, middle, tail), null, candidate -> {
                    throw new AssertionError("Topology must fail before mode probing");
                }));
        assertTrue(failure.getMessage().contains("Repeated RAR payload entry"));
    }

    @Test public void directoryCollisionCannotBeReportedAsSuccessfulExtraction() throws Exception {
        File target = temp.newFolder();
        File occupied = new File(target, "occupied");
        byte[] original = {42}; Files.write(occupied.toPath(), original);
        assertThrows(IOException.class, () -> Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                Arrays.asList(stored("new.txt"), directory("occupied")), target, null, null, null));
        assertArrayEquals(original, Files.readAllBytes(occupied.toPath()));
        assertFalse(new File(target, "new.txt").exists());
    }

    @Test public void existingDirectoryStillAllowsExtraction() throws Exception {
        File target = temp.newFolder();
        assertTrue(new File(target, "occupied").mkdir());
        assertTrue(Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                Arrays.asList(directory("occupied"), stored("new.txt")), target, null, null, null));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(new File(target, "new.txt").toPath()));
    }

    @Test public void missingPasswordRejectsAllRoutesBeforeCreatingOutput() throws Exception {
        RarArchiveReader.RarEntry secret = entry("secret", temp.newFile(), 0x33, 16, 2, 0,
                false, false, RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8]));
        File target = new File(temp.getRoot(), "not-created");
        assertFalse(Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                Arrays.asList(directory("empty"), stored("first.txt"), secret), target, null, null, null));
        assertFalse(target.exists());
    }

    @Test public void planningKeepsTheInterruptAndCreatesNoOutput() throws Exception {
        List<RarArchiveReader.RarEntry> entries = Collections.singletonList(stored("stored.txt"));
        File target = new File(temp.getRoot(), "not-created");
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> Rar3Or4SpecialCaseExtractor.tryExtractArchiveWithSpecialCases(
                    entries, target, null, null, null));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(target.exists());
        } finally { Thread.interrupted(); }
    }

    private RarArchiveReader.RarEntry stored(String name) throws IOException {
        byte[] bytes = {1,2,3}; File source = temp.newFile(); Files.write(source.toPath(), bytes);
        return entry(name, source, 0x30, bytes.length, bytes.length, crc(bytes), false, false, null);
    }
    private static RarArchiveReader.RarEntry entry(String name, File source, int method, long packed,
            long unpacked, long crc, boolean before, boolean after, RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(name, false, unpacked, packed,
                0, 4, method, false, before, after, encryption, crc, 0);
        entry.sourceArchive = source; return entry;
    }
    private static RarArchiveReader.RarEntry directory(String name) {
        return new RarArchiveReader.RarEntry(name, true, 0, 0, 0, 4, 0x30, false, false, false, null, 0, 0);
    }
    private static Rar3PpmdBlockProbe.Result classic() {
        return new Rar3PpmdBlockProbe.Result(Rar3PpmdBlockProbe.KIND_CLASSIC_LZ, false, 0, 0, "classic");
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
