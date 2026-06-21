package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests for the bounded copy that backs font import. The production cap is 64 MB;
 * these exercise the boundary behavior directly with a small limit so we do not
 * have to stream tens of megabytes. The cap (reject oversized streams) lives in
 * {@code copyBounded}; deleting the partial file on rejection is the caller's
 * responsibility in the import path.
 */
public class FontImportSizeLimitTest {

    private static File tempDest(String name) throws IOException {
        File f = File.createTempFile(name, ".ttf");
        f.deleteOnExit();
        return f;
    }

    @Test
    public void copyWithinLimitWritesEveryByte() throws Exception {
        File dest = tempDest("font-small");
        byte[] data = new byte[500];
        FontManager.copyBounded(new ByteArrayInputStream(data), dest, 1000L);
        assertEquals(500L, dest.length());
        dest.delete();
    }

    @Test
    public void copyAtExactLimitSucceeds() throws Exception {
        File dest = tempDest("font-exact");
        byte[] data = new byte[1000];
        // total == maxBytes is allowed; only total > maxBytes is rejected.
        FontManager.copyBounded(new ByteArrayInputStream(data), dest, 1000L);
        assertEquals(1000L, dest.length());
        dest.delete();
    }

    @Test
    public void copyExceedingLimitThrows() throws Exception {
        File dest = tempDest("font-oversized");
        byte[] data = new byte[2048];
        boolean threw = false;
        try {
            FontManager.copyBounded(new ByteArrayInputStream(data), dest, 1024L);
        } catch (IOException expected) {
            threw = true;
        }
        assertTrue("oversized stream must be rejected", threw);
        dest.delete();
    }

    @Test
    public void copyJustOverLimitOnLaterChunkThrows() throws Exception {
        // Spans multiple 8 KB read chunks so the overflow is detected mid-copy
        // rather than on the first read.
        File dest = tempDest("font-chunked");
        byte[] data = new byte[20000];
        boolean threw = false;
        try {
            FontManager.copyBounded(new ByteArrayInputStream(data), dest, 10000L);
        } catch (IOException expected) {
            threw = true;
        }
        assertTrue("chunked oversized stream must be rejected", threw);
        dest.delete();
    }

    @Test
    public void uniqueDestinationStaysWithinByteLimitOnCollision() throws Exception {
        // A near-cap base name plus a collision suffix must not exceed the filename
        // byte cap (MAX_FONT_FILE_NAME_BYTES = 240). Force a collision and assert the
        // resolved candidate is re-truncated to fit.
        File dir = new File(System.getProperty("java.io.tmpdir"), "rw-fonttest-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        try {
            StringBuilder base = new StringBuilder();
            for (int i = 0; i < 78; i++) base.append('\uAC00'); // 78 x 3 bytes = 234 bytes
            String safeName = base + ".ttf"; // ~238 UTF-8 bytes, already within the cap
            File existing = new File(dir, safeName);
            assertTrue(existing.createNewFile()); // force the collision branch

            File candidate = FontManager.getInstance().uniqueFontDestination(dir, safeName);

            int candidateBytes = candidate.getName().getBytes(StandardCharsets.UTF_8).length;
            assertTrue("collision-resolved name must fit the byte cap, was " + candidateBytes,
                    candidateBytes <= 240);
            org.junit.Assert.assertNotEquals(existing.getName(), candidate.getName());
        } finally {
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File f : kids) f.delete();
            }
            dir.delete();
        }
    }
}
