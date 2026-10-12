package com.readwide.manager.util;

import static org.junit.Assert.*;

import com.readwide.manager.view.CustomReaderView;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

public class LargeTextPageIndexCacheTest {
    @Test public void configurationKeyIgnoresOnlyTheBookkeepingSequence() {
        String stable = "{1.0 [en_US] 420dpi winConfig={bounds} as.7 fontWeightAdjustment=0}";
        assertEquals(stable, LargeTextPageIndexCache.configurationKey(
                "{1.0 [en_US] 420dpi winConfig={bounds} as.7 s.123 fontWeightAdjustment=0}"));
        assertEquals(stable, LargeTextPageIndexCache.configurationKey(stable));
        assertEquals("{config}", LargeTextPageIndexCache.configurationKey("{config s.-42}"));
        assertNotEquals(stable, LargeTextPageIndexCache.configurationKey(stable.replace("as.7", "as.8")));
        assertNotEquals(stable, LargeTextPageIndexCache.configurationKey(stable.replace("420dpi", "560dpi")));
        assertNotEquals(stable, LargeTextPageIndexCache.configurationKey(stable.replace("Adjustment=0", "Adjustment=100")));
        assertEquals("{oem-font=s.123}", LargeTextPageIndexCache.configurationKey("{oem-font=s.123}"));
    }

    @Test public void configurationKeyPreservesUnrecognizedAndIncompleteTokens() {
        assertEquals("", LargeTextPageIndexCache.configurationKey(null));
        for (String unchanged : new String[]{"", "{}", "{s.12}", "{config as.12}",
                "{config s.}", "{config s.-}", "{config s.+12}", "{config s.12suffix}",
                "{config s.12.3}", "{config s.\u0661}", "{config s.12", "{config s.12\t}"}) {
            assertEquals(unchanged, LargeTextPageIndexCache.configurationKey(unchanged));
        }
    }

    @Test public void configurationKeyHandlesConsecutiveTokensWithoutParsingIntegerValues() {
        assertEquals("{config as.7}", LargeTextPageIndexCache.configurationKey(
                "{config s.0 s.-2147483648 as.7 s.999999999999999999999}"));
        assertEquals("{config s.bad as.8}", LargeTextPageIndexCache.configurationKey(
                "{config s.bad s.42 as.8}"));
        String normalized = LargeTextPageIndexCache.configurationKey("{config s.5}");
        assertEquals(normalized, LargeTextPageIndexCache.configurationKey(normalized));
    }

    @Test public void reopeningWithDifferentConfigurationSequenceRestoresSavedAnchors() throws Exception {
        File cache = new File(temporary.getRoot(), "reopen");
        String first = "layout|configuration=" + LargeTextPageIndexCache.configurationKey("{font=1 s.2}");
        String reopen = "layout|configuration=" + LargeTextPageIndexCache.configurationKey("{font=1 s.91}");
        LargeTextPageIndexCache.save(cache, first, DIGEST, 200, anchors(), LIVE);
        ArrayList<CustomReaderView.PageTextAnchor> restored = LargeTextPageIndexCache.load(cache, reopen, DIGEST, 200, LIVE);
        assertNotNull(restored);
        assertEquals(2, restored.size());
        assertEquals(100, restored.get(1).charPosition);
        assertNull(LargeTextPageIndexCache.load(cache, reopen, new byte[32], 200, LIVE));
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final LargeTextPageIndexCache.Checkpoint LIVE = () -> {};
    private static final byte[] DIGEST = LargeTextPageIndexCache.newDigest().digest(new byte[]{1, 2, 3});

    private ArrayList<CustomReaderView.PageTextAnchor> anchors() {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        result.add(new CustomReaderView.PageTextAnchor(0, "", "한글 😀\n"));
        result.add(new CustomReaderView.PageTextAnchor(100, "before\uD800", "after"));
        return result;
    }

    @Test public void roundTripPreservesOffsetsAndExactUtf16Context() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "sig", DIGEST, 200, anchors(), LIVE);
        ArrayList<CustomReaderView.PageTextAnchor> loaded = LargeTextPageIndexCache.load(cache, "sig", DIGEST, 200, LIVE);
        assertNotNull(loaded);
        assertEquals(2, loaded.size());
        for (int i = 0; i < loaded.size(); i++) {
            assertEquals(anchors().get(i).charPosition, loaded.get(i).charPosition);
            assertEquals(anchors().get(i).anchorTextBefore, loaded.get(i).anchorTextBefore);
            assertEquals(anchors().get(i).anchorTextAfter, loaded.get(i).anchorTextAfter);
        }
    }

    @Test public void layoutContentAndTotalChangesAreMisses() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "sig", DIGEST, 200, anchors(), LIVE);
        assertNull(LargeTextPageIndexCache.load(cache, "other", DIGEST, 200, LIVE));
        assertNull(LargeTextPageIndexCache.load(cache, "sig", new byte[32], 200, LIVE));
        assertNull(LargeTextPageIndexCache.load(cache, "sig", DIGEST, 201, LIVE));
    }

    @Test public void truncatedCorruptAndTrailingDataAreMisses() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "sig", DIGEST, 200, anchors(), LIVE);
        byte[] original = Files.readAllBytes(cache.toPath());
        for (int cut : new int[]{0, 1, original.length / 2, original.length - 1}) {
            Files.write(cache.toPath(), Arrays.copyOf(original, cut));
            assertNull(LargeTextPageIndexCache.load(cache, "sig", DIGEST, 200, LIVE));
        }
        byte[] corrupt = original.clone();
        corrupt[original.length / 2] ^= 1;
        Files.write(cache.toPath(), corrupt);
        assertNull(LargeTextPageIndexCache.load(cache, "sig", DIGEST, 200, LIVE));
        Files.write(cache.toPath(), Arrays.copyOf(original, original.length + 1));
        assertNull(LargeTextPageIndexCache.load(cache, "sig", DIGEST, 200, LIVE));
    }

    @Test public void malformedAnchorOrderOrContextIsNotPublished() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        ArrayList<CustomReaderView.PageTextAnchor> invalid = anchors();
        invalid.add(new CustomReaderView.PageTextAnchor(99, "", ""));
        LargeTextPageIndexCache.save(cache, "sig", DIGEST, 200, invalid, LIVE);
        assertFalse(cache.exists());
        invalid = anchors();
        invalid.add(new CustomReaderView.PageTextAnchor(101, new String(new char[82]), ""));
        LargeTextPageIndexCache.save(cache, "sig", DIGEST, 200, invalid, LIVE);
        assertFalse(cache.exists());
    }

    @Test public void cancellationLeavesPreviousCacheUsable() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "old", DIGEST, 200, anchors(), LIVE);
        LargeTextPageIndexCache.Checkpoint cancelled = () -> { throw new InterruptedIOException("cancelled"); };
        try {
            LargeTextPageIndexCache.save(cache, "new", DIGEST, 200, anchors(), cancelled);
            fail("cancelled write");
        } catch (InterruptedIOException expected) { }
        try {
            LargeTextPageIndexCache.load(cache, "old", DIGEST, 200, cancelled);
            fail("cancelled read");
        } catch (InterruptedIOException expected) { }
        assertNotNull(LargeTextPageIndexCache.load(cache, "old", DIGEST, 200, LIVE));
    }

    @Test public void fingerprintDetectsSameSizeAndTimestampContentReplacement() throws Exception {
        File source = temporary.newFile("source.txt");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        long modified = source.lastModified();
        byte[] before = LargeTextPageIndexCache.fingerprint(source, LIVE);
        Files.write(source.toPath(), new byte[]{3, 2, 1});
        assertTrue(source.setLastModified(modified));
        assertFalse(Arrays.equals(before, LargeTextPageIndexCache.fingerprint(source, LIVE)));
    }

    @Test public void completeReplacementAndEmptyTextIndexRoundTrip() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "old", DIGEST, 200, anchors(), LIVE);
        ArrayList<CustomReaderView.PageTextAnchor> empty = new ArrayList<>();
        empty.add(new CustomReaderView.PageTextAnchor(0, "", ""));
        LargeTextPageIndexCache.save(cache, "new", DIGEST, 0, empty, LIVE);
        assertNull(LargeTextPageIndexCache.load(cache, "old", DIGEST, 200, LIVE));
        assertEquals(1, LargeTextPageIndexCache.load(cache, "new", DIGEST, 0, LIVE).size());
    }

    @Test public void korean23570PageIndexSurvivesReopenAboveTheOldEightMiBLimit() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        ArrayList<CustomReaderView.PageTextAnchor> large = new ArrayList<>();
        String before = new String(new char[80]).replace('\0', '\uD55C');
        String after = new String(new char[120]).replace('\0', '\uAE00');
        for (int i = 0; i < 23570; i++) large.add(new CustomReaderView.PageTextAnchor(i * 1000, before, after));
        LargeTextPageIndexCache.save(cache, "old", DIGEST, 200, anchors(), LIVE);
        LargeTextPageIndexCache.save(cache, "large", DIGEST, 23570000, large, LIVE);
        assertTrue(cache.length() > 8L * 1024 * 1024);
        ArrayList<CustomReaderView.PageTextAnchor> restored = LargeTextPageIndexCache.load(
                cache, "large", DIGEST, 23570000, LIVE);
        assertNotNull(restored);
        assertEquals(23570, restored.size());
        for (int i = 0; i < restored.size(); i++) {
            assertEquals(i * 1000, restored.get(i).charPosition);
            assertEquals(before, restored.get(i).anchorTextBefore);
            assertEquals(after, restored.get(i).anchorTextAfter);
        }
        assertNull(LargeTextPageIndexCache.load(cache, "large", new byte[32], 23570000, LIVE));
        assertNull(LargeTextPageIndexCache.load(cache, "changed-layout", DIGEST, 23570000, LIVE));
        // Damage beyond the old buffer boundary is still detected before source hashing.
        try (RandomAccessFile damaged = new RandomAccessFile(cache, "rw")) {
            damaged.seek(8L * 1024 * 1024 + 17);
            int value = damaged.read();
            damaged.seek(8L * 1024 * 1024 + 17);
            damaged.write(value ^ 1);
        }
        assertNull(LargeTextPageIndexCache.load(cache, "large", 23570000, LIVE,
                () -> { throw new AssertionError("corrupt index must not hash source"); }));
    }

    @Test public void streamingCacheRemainsByteCompatibleWithV1() throws Exception {
        File cache = new File(temporary.getRoot(), "legacy");
        byte[] legacy = v1Bytes("sig", 200, 2, anchors());
        Files.write(cache.toPath(), legacy);
        assertEquals(2, LargeTextPageIndexCache.load(cache, "sig", DIGEST, 200, LIVE).size());
        LargeTextPageIndexCache.save(cache, "sig", DIGEST, 200, anchors(), LIVE);
        assertArrayEquals(legacy, Files.readAllBytes(cache.toPath()));
    }

    @Test public void impossibleRecordCountsAreRejectedBeforeSourceHashing() throws Exception {
        File cache = new File(temporary.getRoot(), "invalid-count");
        for (int count : new int[]{0, -1, 201, Integer.MAX_VALUE}) {
            Files.write(cache.toPath(), v1Bytes("sig", 200, count, anchors()));
            assertNull(LargeTextPageIndexCache.load(cache, "sig", 200, LIVE,
                    () -> { throw new AssertionError("invalid count must not hash source"); }));
        }
    }

    @Test public void cancellationDuringStreamingSavePreservesPreviousIndexAndRemovesStaging() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "old", DIGEST, 200, anchors(), LIVE);
        byte[] original = Files.readAllBytes(cache.toPath());
        AtomicInteger checks = new AtomicInteger();
        try {
            LargeTextPageIndexCache.save(cache, "new", DIGEST, 200, anchors(), () -> {
                if (checks.incrementAndGet() == 3) throw new InterruptedIOException("cancelled mid-write");
            });
            fail("cancelled write");
        } catch (InterruptedIOException expected) { }
        assertArrayEquals(original, Files.readAllBytes(cache.toPath()));
        assertEquals(1, temporary.getRoot().listFiles().length);
        assertNotNull(LargeTextPageIndexCache.load(cache, "old", DIGEST, 200, LIVE));
    }

    @Test public void cancellationDuringChecksumValidationDoesNotReadSourceOrModifyCache() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "sig", DIGEST, 200, anchors(), LIVE);
        byte[] original = Files.readAllBytes(cache.toPath());
        AtomicInteger checks = new AtomicInteger();
        try {
            LargeTextPageIndexCache.load(cache, "sig", 200, () -> {
                if (checks.incrementAndGet() == 2) throw new InterruptedIOException("cancelled checksum");
            }, () -> { throw new AssertionError("cancelled validation must not hash source"); });
            fail("cancelled read");
        } catch (InterruptedIOException expected) { }
        assertArrayEquals(original, Files.readAllBytes(cache.toPath()));
    }

    private byte[] v1Bytes(String signature, int totalChars, int count,
            ArrayList<CustomReaderView.PageTextAnchor> values) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x54585031);
        output.writeUTF(signature);
        output.write(DIGEST);
        output.writeInt(totalChars);
        output.writeInt(count);
        for (CustomReaderView.PageTextAnchor anchor : values) {
            output.writeInt(anchor.charPosition);
            output.writeUTF(anchor.anchorTextBefore);
            output.writeUTF(anchor.anchorTextAfter);
        }
        CRC32 checksum = new CRC32();
        checksum.update(bytes.toByteArray());
        output.writeLong(checksum.getValue());
        output.close();
        return bytes.toByteArray();
    }

    @Test public void wrongLayoutMissDoesNotReadSourceFingerprint() throws Exception {
        File cache = new File(temporary.getRoot(), "index");
        LargeTextPageIndexCache.save(cache, "old", DIGEST, 200, anchors(), LIVE);
        assertNull(LargeTextPageIndexCache.load(cache, "new", 200, LIVE,
                () -> { throw new AssertionError("source must not be rescanned for wrong layout"); }));
    }
}
