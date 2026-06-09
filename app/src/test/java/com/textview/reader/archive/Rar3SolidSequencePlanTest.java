package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class Rar3SolidSequencePlanTest {
    @Test
    public void buildsPlanWithLeadingClassicLzPrimerOnly() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        RarArchiveReader.RarEntry primer = entry("primer.txt", 4, 0x33, false);
        RarArchiveReader.RarEntry target = entry("target.txt", 3, 0x33, true);
        entries.add(primer);
        entries.add(target);

        Rar3SolidSequencePlan plan = Rar3SolidSequencePlan.forTarget(entries, target);

        assertTrue(plan != null);
        assertEquals(0, plan.runStartIndex);
        assertEquals(1, plan.targetIndex);
        assertEquals(1, plan.primerEntryCount);
        assertEquals(4L, plan.primerUnpackedBytes);
        assertEquals(7L, plan.sequenceUnpackedBytes);
        assertEquals(2, plan.sequenceEntries().size());
    }

    @Test
    public void rejectsStoredRunStartBeforeSolidTarget() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("stored.txt", 5, 0x30, false));
        RarArchiveReader.RarEntry target = entry("target.txt", 3, 0x33, true);
        entries.add(target);

        assertEquals(null, Rar3SolidSequencePlan.forTarget(entries, target));
    }

    @Test
    public void rejectsUnsupportedCompressedEntryInsideRun() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("primer.txt", 4, 0x33, false));
        entries.add(entry("unsupported-solid.txt", 4, 0x30, true));
        RarArchiveReader.RarEntry target = entry("target.txt", 3, 0x33, true);
        entries.add(target);

        assertEquals(null, Rar3SolidSequencePlan.forTarget(entries, target));
    }

    private RarArchiveReader.RarEntry entry(String path, long unpackedSize, int method, boolean solid) throws Exception {
        File file = File.createTempFile("rar3-solid-plan-", ".bin");
        Files.write(file.toPath(), new byte[] {1, 2, 3, 4});
        file.deleteOnExit();
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                path,
                false,
                unpackedSize,
                4,
                0,
                4,
                method,
                solid,
                false,
                false,
                null,
                0,
                0);
        entry.sourceArchive = file;
        return entry;
    }
}
