package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class Rar3SolidCbrPpmdFixtureProbeTest {
    @Test
    public void directTargetFixtureHasTwoPpmdImageEntriesWhenProvided() throws Exception {
        File fixture = directFixture();
        List<Rar3SolidCbrPpmdFixtureProbe.Row> rows = Rar3SolidCbrPpmdFixtureProbe.probe(fixture);

        assertEquals(2, rows.size());
        assertTargetRow(rows.get(0), "testfile.png", 84, 87, false, true, false, 0xa718,
                8, 25, -1);
        assertTargetRow(rows.get(1), "testfile.jpg", 182, 220, true, true, true, 0xc715,
                -1, -1, 0x15);
    }

    private static void assertTargetRow(Rar3SolidCbrPpmdFixtureProbe.Row row,
                                        String path,
                                        long packedSize,
                                        long unpackedSize,
                                        boolean solid,
                                        boolean ppmd,
                                        boolean keepOldTable,
                                        int rawFlags,
                                        int maxOrderHint,
                                        int memoryMbHint,
                                        int escapeCharHint) {
        assertEquals(path, row.path);
        assertEquals(packedSize, row.packedSize);
        assertEquals(unpackedSize, row.unpackedSize);
        assertEquals(solid, row.solid);
        assertEquals(ppmd, row.ppmd);
        assertEquals(keepOldTable, row.keepOldTable);
        assertEquals(rawFlags, row.rawFlags);
        assertEquals(maxOrderHint, row.maxOrderHint);
        assertEquals(memoryMbHint, row.memoryMbHint);
        assertEquals(escapeCharHint, row.escapeCharHint);
        assertEquals(2, row.payloadOffset);
        assertTrue(row.diagnostic.contains("PPMd decode-init"));
    }

    private static File directFixture() {
        String path = System.getProperty("textview.rar3SolidCbrFixture");
        if (path == null || path.trim().length() == 0) {
            path = System.getenv("TEXTVIEW_RAR3_SOLID_CBR_FIXTURE");
        }
        assumeTrue("Direct RAR3 solid CBR fixture not provided",
                path != null && path.trim().length() > 0);
        File fixture = new File(path);
        assumeTrue("Direct RAR3 solid CBR fixture missing: " + fixture.getAbsolutePath(), fixture.isFile());
        return fixture;
    }
}
