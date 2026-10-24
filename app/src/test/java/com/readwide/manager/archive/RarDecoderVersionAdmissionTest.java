package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Admission tests only: synthetic metadata does not establish legacy decoder coverage. */
public class RarDecoderVersionAdmissionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void knownOtherGenerationsDeclineBeforeModeProbe() throws Exception {
        for (int version : new int[]{0, 15, 20, 26, 36, 255}) {
            RarArchiveReader.RarEntry entry = entry(version, 0x33, false, false, false);
            assertFalse(Rar3FirstPartyArchiveExtractor.isFirstPartyCompressedCandidate(entry));
            assertFalse(Rar3DecodePlan.isCompressedCandidate(entry));
            assertNull(Rar3DecodePlan.compile(Collections.singletonList(entry), e -> {
                throw new AssertionError("Legacy bytes must not be probed as RAR3");
            }));
            assertEquals(Rar3PpmdBlockProbe.KIND_NOT_RAR3_OR4_COMPRESSED,
                    Rar3PpmdBlockProbe.probe(entry).kind);
            assertEquals(version, Rar3DecodePlan.snapshot(entry).unpackVersion);
        }
    }

    @Test public void version29AndExistingSyntheticEntriesKeepTheirPlans() throws Exception {
        RarArchiveReader.RarEntry originalConstructor = new RarArchiveReader.RarEntry(
                "old-call.bin", false, 2, 2, 0, 4, 0x33, false, false, false, null, 0, 0);
        assertEquals(-1, originalConstructor.unpackVersion);
        assertTrue(RarFeatureClassifier.hasRar3DecoderVersion(originalConstructor));
        for (int version : new int[]{29, -1}) {
            Rar3DecodePlan plan = Rar3DecodePlan.compile(Arrays.asList(
                    entry(version, 0x33, false, false, false),
                    entry(version, 0x33, true, false, false)), e -> classic());
            assertNotNull(plan);
            assertTrue(plan.step(0).continuesSolid);
            assertEquals(Rar3DecodePlan.Action.CONTINUE_CHECKED, plan.step(1).action);
        }
    }

    @Test public void oldStoredMemberDoesNotBreakModernSolidHistory() throws Exception {
        Rar3DecodePlan plan = Rar3DecodePlan.compile(Arrays.asList(
                entry(29, 0x33, false, false, false), entry(20, 0, false, false, false),
                entry(29, 0x33, true, false, false)), e -> classic());
        assertNotNull(plan);
        assertEquals(Rar3DecodePlan.Action.STORED, plan.step(1).action);
        assertTrue(plan.step(0).continuesSolid);
    }

    @Test public void oldSolidRunLeavesForwardSelectionAvailableToOtherBackends() throws Exception {
        RarArchiveReader.RarEntry head = entry(20, 0x33, false, false, false);
        RarArchiveReader.RarEntry tail = entry(20, 0x33, true, false, false);
        File spool = temp.newFolder();
        assertNull(Rar3LogicalSolidArchiveExtractor.prepareArchive(Arrays.asList(head, tail)));
        assertNull(Rar3LogicalSolidArchiveExtractor.prepare(tail, Arrays.asList(head, tail)));
        assertNull(Rar3PpmdSolidArchiveExtractor.openForwardReader(Arrays.asList(head, tail), null, spool, true));
        assertEquals(0, spool.list().length);
    }

    @Test public void splitContinuationCannotChangeCompressedGeneration() throws Exception {
        RarArchiveReader.RarEntry head = entry(29, 0x33, false, false, true);
        RarArchiveReader.RarEntry tail = entry(20, 0x33, false, true, false);
        assertThrows(IOException.class, () -> Rar3PackedPayload.validateMetadata(head, Arrays.asList(head, tail)));
        assertFalse(Rar3IndependentPayloadExtractor.isCandidate(entry(20, 0x33, false, false, true)));
    }

    @Test public void oldStoredSplitMetadataKeepsExistingAdmission() throws Exception {
        RarArchiveReader.RarEntry head = entry(20, 0, false, false, true);
        RarArchiveReader.RarEntry tail = entry(26, 0, false, true, false);
        assertEquals(4, Rar3PackedPayload.validateMetadata(head, Arrays.asList(head, tail)));
    }

    private RarArchiveReader.RarEntry entry(int version, int method, boolean solid, boolean before, boolean after) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("file.bin", false, 2, 2, 0,
                4, method, solid, before, after, null, 0, 0, -1, null, false, version);
        entry.sourceArchive = new File(temp.getRoot(), "not-opened.rar");
        return entry;
    }

    private static Rar3PpmdBlockProbe.Result classic() {
        return new Rar3PpmdBlockProbe.Result(Rar3PpmdBlockProbe.KIND_CLASSIC_LZ, false, 0, 0, "synthetic");
    }
}
