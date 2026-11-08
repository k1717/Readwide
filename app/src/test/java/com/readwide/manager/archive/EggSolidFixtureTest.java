package com.readwide.manager.archive;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** External public fixtures; pinned provenance and observed byte oracles are in the archive notes. */
public class EggSolidFixtureTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] SENTINEL = {41, 42, 43};
    private static final String[] UNEGG_NAMES = {"a", "empty.txt", "hello.txt", "lorem.txt", "random.bin"};

    @Test public void uneggSolidKeepsEmptyEntriesAndPartialReadBoundaries() throws Exception {
        verify(fixture("unegg"), UNEGG_NAMES, new long[]{1, 0, 45, 2200, 4096},
                new long[]{0xe8b7be43L, 0, 0xeb50cc6aL, 0xcc0c6d8dL, 0xb4c1d904L});
    }

    @Test public void eggDotNetSolidKeepsNestedNamesAndPartialReadBoundaries() throws Exception {
        verify(fixture("eggdotnet"), new String[]{"s/lorem_ipsum_short.txt", "s/lorem_ipsum_short2.txt"},
                new long[]{525, 525}, new long[]{0xf700ae3aL, 0xf700ae3aL});
    }

    @Test public void corruptSharedBlockPreservesSelectedAndBulkTargets() throws Exception {
        File archive = corruptBlock();
        for (String name : UNEGG_NAMES) {
            for (boolean existing : new boolean[]{false, true}) {
                File selected = new File(temp.newFolder(), "out");
                if (existing) Files.write(selected.toPath(), SENTINEL);
                ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(archive, name, selected, null);
                assertFalse("Corrupt shared block accepted for " + name, result.success);
                if (existing) assertArrayEquals(SENTINEL, Files.readAllBytes(selected.toPath()));
                else assertFalse(selected.exists());
                assertEquals(existing ? 1 : 0, selected.getParentFile().list().length);
            }
        }
        for (boolean existing : new boolean[]{false, true}) {
            File bulk = new File(temp.newFolder(), "bulk");
            if (existing) {
                assertTrue(bulk.mkdir());
                for (String name : UNEGG_NAMES) Files.write(new File(bulk, name).toPath(), SENTINEL);
            }
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(archive, bulk, true, null, null);
            assertFalse("Corrupt bulk accepted", result.success);
            if (existing) {
                for (String name : UNEGG_NAMES) assertArrayEquals(SENTINEL, Files.readAllBytes(new File(bulk, name).toPath()));
                assertEquals(UNEGG_NAMES.length, bulk.list().length);
            } else assertFalse(bulk.exists());
            assertEquals(existing ? 1 : 0, bulk.getParentFile().list().length);
        }
    }

    @Test public void corruptSharedBlockCannotPublishOrDrainItsFirstMember() throws Exception {
        File archive = corruptBlock();
        for (boolean direct : new boolean[]{false, true}) {
            for (boolean drain : new boolean[]{false, true}) {
                File spool = temp.newFolder();
                try (ArchiveSupport.ForwardArchiveReader reader = open(archive, spool, direct)) {
                    assertNotNull(reader);
                    assertEquals("a", reader.nextEntry().path);
                    byte[] buffer = SENTINEL.clone();
                    assertThrows(IOException.class, () -> {
                        if (drain) reader.drainCurrentEntry(1); else reader.read(buffer);
                    });
                    assertArrayEquals(SENTINEL, buffer);
                    assertThrows(IOException.class, reader::nextEntry);
                }
                assertArrayEquals(new String[0], spool.list());
            }
        }
    }

    private void verify(File archive, String[] names, long[] sizes, long[] crcs) throws Exception {
        assertTrue(EggArchiveReader.isSolidArchive(archive));
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        assertEquals(names.length, entries.size());
        Map<String, byte[]> expected = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], entries.get(i).path);
            assertEquals(sizes[i], entries.get(i).size);
            assertFalse(entries.get(i).directory);
            File target = new File(temp.newFolder(), "selected");
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(archive, names[i], target, null);
            assertTrue("Selected: " + result.failure + " " + result.detail, result.success);
            byte[] bytes = Files.readAllBytes(target.toPath());
            assertEquals(sizes[i], bytes.length);
            CRC32 crc = new CRC32(); crc.update(bytes);
            assertEquals(crcs[i], crc.getValue());
            expected.put(names[i], bytes);
        }
        File bulk = new File(temp.newFolder(), "bulk");
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(archive, bulk, false, null, null);
        assertTrue("Bulk: " + result.failure + " " + result.detail, result.success);
        for (String name : names) assertArrayEquals(expected.get(name), Files.readAllBytes(new File(bulk, name).toPath()));

        for (boolean direct : new boolean[]{false, true}) {
            // Full read, whole-entry drain, prefix+drain, prefix+implicit advance, early close.
            for (int mode = 0; mode < 5; mode++) {
                File spool = temp.newFolder();
                try (ArchiveSupport.ForwardArchiveReader reader = open(archive, spool, direct)) {
                    assertNotNull(reader);
                    boolean exercised = mode == 0;
                    for (String name : names) {
                        ArchiveSupport.ForwardEntry entry = reader.nextEntry();
                        assertNotNull(entry);
                        assertEquals(name, entry.path);
                        byte[] wanted = expected.get(name);
                        if (!exercised && wanted.length > 1) {
                            exercised = true;
                            if (mode == 1) assertTrue(reader.drainCurrentEntry(wanted.length));
                            else {
                                byte[] first = new byte[1];
                                assertEquals(1, reader.read(first));
                                assertEquals(wanted[0], first[0]);
                                if (mode == 2) assertTrue(reader.drainCurrentEntry(wanted.length - 1));
                                if (mode == 4) break;
                            }
                        } else assertArrayEquals(wanted, read(reader, wanted.length));
                    }
                    assertTrue("Partial-read case not exercised", exercised);
                    if (mode != 4) { assertNull(reader.nextEntry()); assertNull(reader.nextEntry()); }
                }
                assertArrayEquals("Spool leaked", new String[0], spool.list());
            }
        }
    }

    private static byte[] read(ArchiveSupport.ForwardArchiveReader reader, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[37];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            assertTrue(count > 0 && count <= limit - out.size());
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static ArchiveSupport.ForwardArchiveReader open(File archive, File spool, boolean direct) throws IOException {
        return direct ? EggArchiveReader.openSolidForwardReader(archive, spool)
                : ArchiveSupport.openForwardReader(archive, null, spool);
    }

    private File corruptBlock() throws Exception {
        byte[] bytes = Files.readAllBytes(fixture("unegg").toPath());
        // This pinned fixture has a single Deflate block at 278, with its payload at 300.
        assertArrayEquals(new byte[]{0x13, 0x0c, (byte) 0xb5, 2}, Arrays.copyOfRange(bytes, 278, 282));
        assertEquals(1, bytes[282]);
        assertEquals(0xe5b2356eL, Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes, 292, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()));
        bytes[292] ^= 1;
        File altered = new File(temp.newFolder(), "bad-crc.egg");
        Files.write(altered.toPath(), bytes);
        return altered;
    }

    private static File fixture(String name) {
        String path = System.getProperty("textview.externalArchiveFixtureDir");
        if (path == null || path.trim().isEmpty()) path = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        assumeTrue("External archive fixture directory not supplied", path != null && !path.trim().isEmpty());
        File file = new File(new File(path, "egg-solid"), name + ".egg");
        assumeTrue("Missing external fixture: " + file, file.isFile());
        return file;
    }
}
