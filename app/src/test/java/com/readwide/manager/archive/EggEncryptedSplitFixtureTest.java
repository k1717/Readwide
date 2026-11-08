package com.readwide.manager.archive;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Optional ALZip split fixtures, originals and password stay outside the source package. */
@RunWith(Parameterized.class)
public class EggEncryptedSplitFixtureTest {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> ciphers() {
        return Arrays.asList(new Object[][]{{"zipcrypto"}, {"aes128"}, {"aes256"}, {"lea128"}, {"lea256"}});
    }

    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] SENTINEL = {51, 52, 53, 54};
    private final String cipher;
    private Path volumes;
    private Map<String, byte[]> originals;
    private char[] password;

    public EggEncryptedSplitFixtureTest(String cipher) { this.cipher = cipher; }

    @Before public void stage() throws Exception {
        String configured = System.getProperty("textview.externalArchiveFixtureDir");
        if (configured == null || configured.isEmpty()) configured = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        String secret = System.getenv("TEXTVIEW_EGG_FIXTURE_PASSWORD");
        assumeTrue("External ALZip split fixtures and test password required",
                configured != null && !configured.isEmpty() && secret != null && !secret.isEmpty());
        Path fixture = Path.of(configured).resolve("egg-encrypted-split");
        assumeTrue("External ALZip split fixture directory required", Files.isDirectory(fixture));
        password = secret.toCharArray();
        originals = tree(fixture.resolve("input"));
        assertEquals(4, originals.size());
        assertEquals(458144, originals.values().stream().mapToInt(b -> b.length).sum());
        volumes = temp.newFolder("volumes").toPath();
        for (int i = 1; i <= 5; i++) {
            Path source = fixture.resolve("archives").resolve(volumeName(i));
            assertTrue("Missing fixture volume: " + volumeName(i), Files.isRegularFile(source));
            Files.copy(source, part(i).toPath());
        }
        // Warm the index before each derivative checks whether volume changes invalidate it.
        listing(part(1));
    }

    @After public void release() {
        if (volumes != null) for (int i = 1; i <= 5; i++) ArchiveSupport.releaseViewerArchiveIndex(part(i));
        if (password != null) Arrays.fill(password, '\0');
    }

    @Test public void intactFirstAndLastMatchOriginalNamesAndBytes() throws Exception {
        for (int ordinal : new int[]{1, 5}) {
            File archive = part(ordinal);
            listing(archive);
            for (Map.Entry<String, byte[]> entry : originals.entrySet()) {
                File target = new File(temp.newFolder(), "selected");
                Files.write(target.toPath(), SENTINEL);
                accepted(ArchiveSupport.extractSingleEntryDetailed(archive, entry.getKey(), target, password));
                assertArrayEquals(entry.getValue(), Files.readAllBytes(target.toPath()));
                File direct = new File(temp.newFolder(), "direct");
                assertTrue(EggArchiveReader.extractSingleEntry(archive, entry.getKey(), direct, password));
                assertArrayEquals(entry.getValue(), Files.readAllBytes(direct.toPath()));
            }
            File target = new File(temp.newFolder(), "bulk");
            accepted(ArchiveSupport.extractArchiveDetailed(archive, target, false, password, null));
            sameTree(originals, target.toPath());
        }
    }

    @Test public void missingFirstRejectsLastWithoutChangingOutput() throws Exception {
        Files.delete(part(1).toPath());
        rejectedBoundary(5);
    }

    @Test public void missingMiddleRejectsBothEntrypoints() throws Exception {
        Files.delete(part(3).toPath());
        rejectedBoundary(1, 5);
    }

    @Test public void missingTailRejectsFirstWithoutChangingOutput() throws Exception {
        Files.delete(part(5).toPath());
        rejectedBoundary(1);
    }

    @Test public void damagedSignatureRejectsBothEntrypoints() throws Exception {
        mutate(3, 0);
        rejectedBoundary(1, 5);
    }

    @Test public void truncatedPrefixRejectsBothEntrypoints() throws Exception {
        byte[] bytes = Files.readAllBytes(part(3).toPath());
        Files.write(part(3).toPath(), Arrays.copyOf(bytes, 10));
        rejectedBoundary(1, 5);
    }

    @Test public void mismatchedPreviousIdRejectsBothEntrypoints() throws Exception {
        byte[] bytes = Files.readAllBytes(part(3).toPath());
        assertEquals(0x24f5a262, uint32(bytes, 14));
        assertEquals(8, bytes[19] & 255);
        mutate(3, 21);
        rejectedBoundary(1, 5);
    }

    @Test public void aliasedMiddleOrdinalRejectsBothEntrypoints() throws Exception {
        Files.copy(part(3).toPath(), volumes.resolve("egg-" + cipher + "-split.vol03.egg"));
        rejectedBoundary(1, 5);
    }

    @Test public void corruptMiddlePayloadPreservesTargets() throws Exception {
        mutate(3, 1024);
        for (int ordinal : new int[]{1, 5}) {
            listing(part(ordinal));
            rejectedOutputs(part(ordinal), "nested/binary.bin", false);
        }
    }

    @Test public void truncatedTailPayloadPreservesTargets() throws Exception {
        byte[] bytes = Files.readAllBytes(part(5).toPath());
        assertTrue(bytes.length > 100);
        Files.write(part(5).toPath(), Arrays.copyOf(bytes, 50));
        rejectedBoundary(1, 5);
    }

    private void rejectedBoundary(int... ordinals) throws Exception {
        for (int ordinal : ordinals) {
            File archive = part(ordinal);
            assertThrows(IOException.class, () -> ArchiveSupport.listEntries(archive, password));
            assertThrows(IOException.class, () -> EggArchiveReader.listEntries(archive, password));
            // Even an empty member cannot bypass an incomplete physical volume chain.
            rejectedOutputs(archive, "empty.txt", true);
            rejectedOutputs(archive, "nested/binary.bin", true);
        }
    }

    private void rejectedOutputs(File archive, String member, boolean framing) throws Exception {
        for (boolean existing : new boolean[]{false, true}) {
            File target = new File(temp.newFolder(), "selected");
            if (existing) Files.write(target.toPath(), SENTINEL);
            rejected(ArchiveSupport.extractSingleEntryDetailed(archive, member, target, password), framing);
            if (existing) assertArrayEquals(SENTINEL, Files.readAllBytes(target.toPath()));
            else assertFalse(target.exists());
            assertEquals(existing ? 1 : 0, target.getParentFile().list().length);

            File bulk = new File(temp.newFolder(), "bulk");
            Map<String, byte[]> before = new TreeMap<>();
            if (existing) {
                assertTrue(bulk.mkdir());
                for (String name : originals.keySet()) {
                    Path file = bulk.toPath().resolve(name);
                    Files.createDirectories(file.getParent());
                    Files.write(file, SENTINEL);
                    before.put(name, SENTINEL);
                }
            }
            rejected(ArchiveSupport.extractArchiveDetailed(archive, bulk, true, password, null), framing);
            if (existing) sameTree(before, bulk.toPath()); else assertFalse(bulk.exists());
            assertEquals(existing ? 1 : 0, bulk.getParentFile().list().length);
        }
    }

    private static void rejected(ArchiveSupport.ExtractionResult result, boolean framing) {
        assertFalse("Damaged archive accepted", result.success);
        if (framing) {
            assertNotEquals("Framing failure requested password: " + result.detail,
                    ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, result.failure);
            assertNotEquals("Framing failure rejected password: " + result.detail,
                    ArchiveSupport.ExtractionFailure.BAD_PASSWORD, result.failure);
        }
    }

    private void listing(File archive) throws IOException {
        Map<String, Long> entries = new TreeMap<>();
        for (ArchiveSupport.EntryInfo entry : ArchiveSupport.listEntries(archive, password)) {
            if (!entry.directory) assertNull(entries.put(entry.path, entry.size));
        }
        assertEquals(originals.keySet(), entries.keySet());
        for (String name : originals.keySet()) assertEquals((long) originals.get(name).length, (long) entries.get(name));
    }

    private void mutate(int ordinal, int offset) throws IOException {
        byte[] bytes = Files.readAllBytes(part(ordinal).toPath());
        bytes[offset] ^= 1;
        Files.write(part(ordinal).toPath(), bytes);
    }

    private static int uint32(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                | ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24);
    }
    private File part(int ordinal) { return volumes.resolve(volumeName(ordinal)).toFile(); }
    private String volumeName(int ordinal) { return "egg-" + cipher + "-split.vol" + ordinal + ".egg"; }
    private static void accepted(ArchiveSupport.ExtractionResult result) {
        assertTrue(result.failure + ": " + result.detail, result.success);
    }
    private static Map<String, byte[]> tree(Path root) throws IOException {
        assertTrue(Files.isDirectory(root));
        Map<String, byte[]> result = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                result.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return result;
    }
    private static void sameTree(Map<String, byte[]> expected, Path root) throws IOException {
        Map<String, byte[]> actual = tree(root);
        assertEquals(expected.keySet(), actual.keySet());
        for (String name : expected.keySet()) assertArrayEquals(name, expected.get(name), actual.get(name));
    }
}
