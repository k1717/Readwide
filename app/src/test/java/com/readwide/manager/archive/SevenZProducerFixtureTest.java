package com.readwide.manager.archive;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Optional p7zip fixtures use separately extracted reference bytes, not decoder output. */
@RunWith(Parameterized.class)
public class SevenZProducerFixtureTest {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> fixtures() {
        return Arrays.asList(new Object[][]{{"ppmd-plain"}, {"ppmd-aes"},
                {"bcj2-store"}, {"bcj2-lzma"}, {"bcj2-aes"}});
    }

    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] SENTINEL = {41, 42, 43};
    private final String fixture;
    private byte[] archiveBytes;
    private String member;
    private byte[] expected;
    private char[] password;

    public SevenZProducerFixtureTest(String fixture) { this.fixture = fixture; }

    @Before public void stage() throws Exception {
        String configured = System.getProperty("textview.externalSevenZFixtureDir");
        assumeTrue("External 7z fixtures and independent reference output required",
                configured != null && !configured.isEmpty());
        Path root = Path.of(configured).resolve("embedded-" + fixture);
        assertTrue("Missing configured fixture", Files.isDirectory(root));
        archiveBytes = Files.readAllBytes(root.resolve("book.7z"));
        Map<String, byte[]> reference = tree(root.resolve("reference"));
        assertEquals("These producer graphs each have one output member", 1, reference.size());
        member = reference.keySet().iterator().next();
        expected = reference.get(member);
        assertEquals(fixture.startsWith("ppmd") ? 31600 : 2868, expected.length);
        password = fixture.endsWith("aes") ? "pw1717".toCharArray() : null;
    }

    @After public void erasePassword() { if (password != null) Arrays.fill(password, '\0'); }

    @Test public void singleVolumePublicOutputsMatchReferenceAndRejectWrongPassword() throws Exception {
        File archive = single(archiveBytes);
        exactOutputs(archive);
        if (password != null) {
            rejectedOutputs(archive, null);
            rejectedOutputs(archive, "incorrect".toCharArray());
        }
    }

    @Test public void derivedSplitFirstMiddleAndLastMatchReference() throws Exception {
        File[] parts = split(archiveBytes);
        for (int index : new int[]{0, 3, parts.length - 1}) exactOutputs(parts[index]);
        deleteParts(parts);
    }

    @Test public void partialReadDrainAdvanceAndEarlyCloseReleaseResources() throws Exception {
        for (boolean divided : new boolean[]{false, true}) {
            for (int mode = 0; mode < 4; mode++) {
                File[] parts = divided ? split(archiveBytes) : new File[]{single(archiveBytes)};
                File spool = temp.newFolder();
                char[] supplied = password == null ? null : password.clone();
                try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(
                        parts[parts.length - 1], supplied, spool)) {
                    assertNotNull(reader);
                    assertEquals(member, reader.nextEntry().path);
                    byte[] prefix = new byte[17];
                    assertEquals(prefix.length, reader.read(prefix));
                    assertArrayEquals(Arrays.copyOf(expected, prefix.length), prefix);
                    if (mode == 0) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        bytes.write(prefix);
                        byte[] buffer = new byte[997];
                        for (int n; (n = reader.read(buffer)) != -1;) bytes.write(buffer, 0, n);
                        assertArrayEquals(expected, bytes.toByteArray());
                    } else if (mode == 1) {
                        assertTrue(reader.drainCurrentEntry(expected.length));
                        assertEquals(-1, reader.read(new byte[1]));
                    }
                    if (mode != 3) assertNull(reader.nextEntry());
                }
                assertArrayEquals(password, supplied);
                assertEquals(0, spool.list().length);
                deleteParts(parts);
            }
        }
    }

    @Test public void corruptPackedDataNeverReplacesOutputOrExposesForwardBytes() throws Exception {
        byte[] damaged = archiveBytes.clone();
        // The retained graphs start packed data immediately after the 32-byte signature header.
        damaged[32] ^= 0x40;
        for (boolean divided : new boolean[]{false, true}) {
            File[] parts = divided ? split(damaged) : new File[]{single(damaged)};
            File archive = parts[parts.length - 1];
            rejectedOutputs(archive, password);
            rejectedForward(archive);
            deleteParts(parts);
        }
    }

    @Test public void missingMiddleVolumePreservesOutputAndLeavesNoSpool() throws Exception {
        File[] parts = split(archiveBytes);
        Files.delete(parts[3].toPath());
        for (int index : new int[]{0, parts.length - 1}) {
            rejectedOutputs(parts[index], password);
            rejectedForward(parts[index]);
        }
        deleteParts(parts);
    }

    @Test public void laterVolumeChangeAfterOpenRejectsBeforeReading() throws Exception {
        File[] parts = split(archiveBytes);
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(parts[0], password, spool)) {
            assertNotNull(reader);
            assertEquals(member, reader.nextEntry().path);
            Path tail = parts[parts.length - 1].toPath();
            byte[] bytes = Files.readAllBytes(tail);
            Files.write(tail, Arrays.copyOf(bytes, bytes.length + 1));
            byte[] buffer = SENTINEL.clone();
            assertThrows(IOException.class, () -> reader.read(buffer));
            assertArrayEquals(SENTINEL, buffer);
        }
        assertEquals(0, spool.list().length);
        deleteParts(parts);
    }

    private void exactOutputs(File archive) throws Exception {
        var entries = SevenZBcj2ArchiveReader.listEntries(archive, password);
        assertEquals(1, entries.size());
        assertEquals(member, entries.get(0).path);
        assertEquals(expected.length, entries.get(0).size);
        File selected = new File(temp.newFolder(), "selected");
        Files.write(selected.toPath(), SENTINEL);
        accepted(ArchiveSupport.extractSingleEntryDetailed(archive, member, selected, password));
        assertArrayEquals(expected, Files.readAllBytes(selected.toPath()));
        assertEquals(1, selected.getParentFile().list().length);
        File bulk = new File(temp.newFolder(), "bulk");
        accepted(ArchiveSupport.extractArchiveDetailed(archive, bulk, false, password, null));
        Map<String, byte[]> actual = tree(bulk.toPath());
        assertEquals(java.util.Set.of(member), actual.keySet());
        assertArrayEquals(expected, actual.get(member));
    }

    private void rejectedOutputs(File archive, char[] supplied) throws Exception {
        for (boolean existing : new boolean[]{false, true}) {
            File selected = new File(temp.newFolder(), "selected");
            if (existing) Files.write(selected.toPath(), SENTINEL);
            assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive, member, selected, supplied).success);
            if (existing) assertArrayEquals(SENTINEL, Files.readAllBytes(selected.toPath()));
            else assertFalse(selected.exists());
            assertEquals(existing ? 1 : 0, selected.getParentFile().list().length);
            File bulk = new File(temp.newFolder(), "bulk");
            if (existing) {
                Path prior = bulk.toPath().resolve(member);
                Files.createDirectories(prior.getParent());
                Files.write(prior, SENTINEL);
            }
            assertFalse(ArchiveSupport.extractArchiveDetailed(archive, bulk, true, supplied, null).success);
            if (existing) {
                Map<String, byte[]> actual = tree(bulk.toPath());
                assertEquals(java.util.Set.of(member), actual.keySet());
                assertArrayEquals(SENTINEL, actual.get(member));
            } else assertFalse(bulk.exists());
            assertEquals(existing ? 1 : 0, bulk.getParentFile().list().length);
        }
    }

    private void rejectedForward(File archive) throws Exception {
        File spool = temp.newFolder();
        assertThrows(IOException.class, () -> {
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, password, spool)) {
                assertNotNull(reader);
                assertNotNull(reader.nextEntry());
                reader.read(new byte[1]);
            }
        });
        assertEquals(0, spool.list().length);
    }

    private File single(byte[] bytes) throws IOException {
        File archive = new File(temp.newFolder(), "book.7z");
        Files.write(archive.toPath(), bytes);
        return archive;
    }

    private File[] split(byte[] bytes) throws IOException {
        // Byte slices exercise logical reads across headers/data; they are not producer split fixtures.
        int[] ends = {5, 31, 33, 48, bytes.length / 2, bytes.length - 17, bytes.length};
        File directory = temp.newFolder();
        File[] parts = new File[ends.length];
        for (int i = 0, start = 0; i < ends.length; start = ends[i++]) {
            assertTrue(ends[i] > start);
            parts[i] = new File(directory, String.format(Locale.ROOT, "book.7z.%03d", i + 1));
            Files.write(parts[i].toPath(), Arrays.copyOfRange(bytes, start, ends[i]));
        }
        return parts;
    }

    private static void deleteParts(File[] parts) throws IOException {
        for (File part : parts) Files.deleteIfExists(part.toPath());
    }
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
}
