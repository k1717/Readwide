package com.readwide.manager.archive;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Synthetic level-0/3 headers and static Huffman blocks; not producer interoperability evidence. */
@RunWith(AndroidJUnit4.class)
public class LhaNativeDecoderInstrumentedTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Before public void requireNative() {
        assertTrue(LibarchiveNativeBridge.backendStatus(), LibarchiveNativeBridge.isAvailable());
    }

    @Test public void lh4AndExistingMethodsReachListingSelectedBulkAndForwardRoutes() throws Exception {
        for (String method : new String[]{"-lh0-", "-lz4-", "-lh4-", "-lh5-", "-lh6-", "-lh7-"}) {
            byte[] plain = repeated('A', 1024);
            byte[] packed = method.equals("-lh0-") || method.equals("-lz4-") ? plain
                    : literalBlock(method, 'A', plain.length);
            File archive = archive(entry(method, "page", packed, plain));
            List<ArchiveSupport.EntryInfo> rows = RarLibarchiveFallback.parseListingRows(
                    LibarchiveNativeBridge.listEntries(archive.getPath(), null));
            assertEquals(1, rows.size());
            assertEquals("page", rows.get(0).path);
            assertSelected(archive, "page", plain);
            File root = temporary.newFolder();
            assertTrue(LibarchiveNativeBridge.extractArchive(archive.getPath(), root.getPath(), null, null, null));
            assertArrayEquals(plain, Files.readAllBytes(new File(root, "page").toPath()));
            LibarchiveNativeBridge.ForwardStream reader = LibarchiveNativeBridge.openForwardStream(
                    new String[]{archive.getPath()}, null);
            try {
                assertEquals("page", reader.nextEntry().path);
                assertArrayEquals(plain, drain(reader));
                assertNull(reader.nextEntry());
            } finally { reader.close(); }
        }
    }

    @Test public void pm0StoredAndEmptyMembersReachPublicRoutesAndKeepFollowingLh0() throws Exception {
        byte[] plain = repeated('P', 1024), tail = new byte[]{42};
        File archive = temporary.newFile("stored.pma");
        Files.write(archive.toPath(), Files.readAllBytes(archive(
                entry("-pm0-", "page", plain, plain),
                entry("-pm0-", "empty", new byte[0], new byte[0]),
                entry("-lh0-", "tail", tail, tail)).toPath()));
        List<ArchiveSupport.EntryInfo> rows = ArchiveSupport.listEntries(archive, null);
        assertEquals(3, rows.size());
        for (String path : new String[]{"page", "empty", "tail"}) {
            assertTrue(rows.stream().anyMatch(row -> path.equals(row.path) && !row.directory));
        }
        File selected = temporary.newFile();
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "page", selected, null));
        assertArrayEquals(plain, Files.readAllBytes(selected.toPath()));
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "empty", selected, null));
        assertEquals(0L, selected.length());
        File root = temporary.newFolder();
        assertTrue(ArchiveSupport.extractArchive(archive, root, false, null));
        assertArrayEquals(plain, Files.readAllBytes(new File(root, "page").toPath()));
        assertEquals(0L, new File(root, "empty").length());
        assertArrayEquals(tail, Files.readAllBytes(new File(root, "tail").toPath()));
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            assertNotNull(reader);
            String[] paths = {"page", "empty", "tail"};
            byte[][] expected = {plain, new byte[0], tail};
            for (int i = 0; i < paths.length; i++) {
                assertEquals(paths[i], reader.nextEntry().path);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[97];
                for (int count; (count = reader.read(buffer)) >= 0;) {
                    assertTrue(count > 0); output.write(buffer, 0, count);
                }
                assertArrayEquals(expected[i], output.toByteArray());
            }
            assertNull(reader.nextEntry());
        }
    }

    @Test public void pm0CommentsCannotBecomeUnixSymlinkMetadata() throws Exception {
        byte[] comment = new byte[12];
        comment[0] = 'U'; comment[6] = (byte) 0xff; comment[7] = (byte) 0xa1;
        byte[] plain = new byte[]{42};
        File archive = archive(entry("-pm0-", "page", plain, plain, comment));
        List<ArchiveSupport.EntryInfo> rows = RarLibarchiveFallback.parseListingRows(
                LibarchiveNativeBridge.listEntries(archive.getPath(), null));
        assertEquals(1, rows.size());
        assertEquals("page", rows.get(0).path);
        assertFalse(rows.get(0).directory);
        assertSelected(archive, "page", plain);
    }

    @Test public void pm0InsideLhaByteSplitsKeepsSelectedAndForwardIntegrity() throws Exception {
        byte[] plain = repeated('P', 1024);
        byte[] complete = Files.readAllBytes(archive(entry("-pm0-", "page", plain, plain)).toPath());
        File folder = temporary.newFolder();
        File first = new File(folder, "stored.lha"), last = new File(folder, "stored.l01");
        int split = complete.length - 20;
        Files.write(first.toPath(), Arrays.copyOf(complete, split));
        Files.write(last.toPath(), Arrays.copyOfRange(complete, split, complete.length));
        assertEquals("page", ArchiveSupport.listEntries(last, null).get(0).path);
        File selected = temporary.newFile();
        assertTrue(ArchiveSupport.extractSingleEntry(last, "page", selected, null));
        assertArrayEquals(plain, Files.readAllBytes(selected.toPath()));
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(first, null)) {
            assertNotNull(reader);
            assertEquals("page", reader.nextEntry().path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[97];
            for (int count; (count = reader.read(buffer)) >= 0;) {
                assertTrue(count > 0); output.write(buffer, 0, count);
            }
            assertArrayEquals(plain, output.toByteArray());
            assertNull(reader.nextEntry());
        }
        complete[split] ^= 1;
        Files.write(last.toPath(), Arrays.copyOfRange(complete, split, complete.length));
        Files.write(selected.toPath(), new byte[]{7});
        assertFalse(ArchiveSupport.extractSingleEntry(last, "page", selected, null));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(selected.toPath()));
    }

    @Test public void pm0RejectsBadChecksumsTruncationAndStoredSizeMismatchWithoutReplacingOutput() throws Exception {
        byte[] plain = new byte[]{41, 42};
        byte[] valid = entry("-pm0-", "page", plain, plain);
        byte[] badHeader = valid.clone(); badHeader[1] ^= 1;
        assertSelectedFailure(archive(badHeader));
        assertSelectedFailure(archive(entry("-pm0-", "page", plain, new byte[]{43, 44})));
        assertSelectedFailure(archive(Arrays.copyOf(valid, valid.length - 2)));
        assertSelectedFailure(archive(entry("-pm0-", "page", plain, new byte[]{41})));
        assertSelectedFailure(archive(entry("-pm0-", "page", new byte[0], plain)));
        byte[] crcFree = Arrays.copyOf(valid, valid.length - 2);
        int oldHeaderLength = (valid[0] & 255) + 2;
        System.arraycopy(valid, oldHeaderLength, crcFree, oldHeaderLength - 2, plain.length);
        crcFree[0] -= 2; updateLevel0Checksum(crcFree);
        assertSelectedFailure(archive(crcFree));
    }

    @Test public void pm0DoesNotAdmitCompressedPmarcOrOtherHeaderLevels() throws Exception {
        byte[] plain = new byte[]{42};
        for (String method : new String[]{"-pm1-", "-pm2-", "-px0-", "-pmx-"}) {
            assertSelectedFailure(archive(entry(method, "page", plain, plain)));
        }
        for (int level : new int[]{1, 2, 3}) {
            byte[] value = entry("-pm0-", "page", plain, plain);
            value[20] = (byte) level; updateLevel0Checksum(value);
            assertSelectedFailure(archive(value));
        }
    }

    @Test public void lh4DecodesDynamicLiteralAndPositionTablesAtMaximumDistance() throws Exception {
        // A complete two-symbol code tree: A=0, B=1. The length tree encodes
        // a 65-zero run followed by two length-1 codes.
        Bits literals = new Bits();
        literals.put(8, 16);
        literals.put(4, 5);
        literals.put(0, 3); literals.put(0, 3); literals.put(1, 3);
        literals.put(0, 2); literals.put(1, 3);
        literals.put(67, 9);
        literals.put(0, 1); literals.put(45, 9);
        literals.put(1, 1); literals.put(1, 1);
        literals.put(0, 4); literals.put(0, 4);
        literals.put(0x55, 8);
        byte[] alternating = "ABABABAB".getBytes(StandardCharsets.US_ASCII);
        assertSelected(archive(entry("-lh4-", "page", literals.finish(), alternating)), "page", alternating);

        // Distance 4096 reaches the oldest byte of LH4's prefilled space history.
        assertSelected(archive(entry("-lh4-", "page", matchBlock("-lh4-", 12, true), repeated(' ', 3))),
                "page", repeated(' ', 3));
    }

    @Test public void outputWindowWrapAndPerEntryHistoryResetPreserveExactBytes() throws Exception {
        Bits data = new Bits();
        for (int i = 0; i < 3; i++) fixedBlock(data, "-lh4-", 'X', 50000, 0);
        byte[] first = repeated('X', 150000); // Crosses the native 128 KiB output window.
        byte[] second = repeated(' ', 3);
        File archive = archive(entry("-lh4-", "first", data.finish(), first),
                entry("-lh4-", "second", matchBlock("-lh4-", 12, false), second),
                entry("-lh5-", "third", matchBlock("-lh5-", 13, false), second));
        LibarchiveNativeBridge.ForwardStream reader = LibarchiveNativeBridge.openForwardStream(
                new String[]{archive.getPath()}, null);
        try {
            for (String path : new String[]{"first", "second", "third"}) {
                assertEquals(path, reader.nextEntry().path);
                assertArrayEquals(path.equals("first") ? first : second, drain(reader));
            }
            assertNull(reader.nextEntry());
        } finally { reader.close(); }
        File root = temporary.newFolder();
        assertTrue(LibarchiveNativeBridge.extractArchive(archive.getPath(), root.getPath(), null, null, null));
        assertArrayEquals(first, Files.readAllBytes(new File(root, "first").toPath()));
        assertArrayEquals(second, Files.readAllBytes(new File(root, "second").toPath()));
        assertArrayEquals(second, Files.readAllBytes(new File(root, "third").toPath()));
    }

    @Test public void lh4RejectsOutOfWindowPositionTablesButLh5StillAcceptsItsWindow() throws Exception {
        for (boolean dynamic : new boolean[]{false, true}) {
            byte[] plain = repeated(' ', 3);
            assertSelectedFailure(archive(entry("-lh4-", "page", matchBlock("-lh4-", 13, dynamic), plain)));
            assertSelected(archive(entry("-lh5-", "page", matchBlock("-lh5-", 13, dynamic), plain)), "page", plain);
        }
    }

    @Test public void malformedDataHeadersAndChecksumsRestoreExistingOutput() throws Exception {
        byte[] plain = repeated(' ', 3);
        byte[] valid = entry("-lh4-", "page", matchBlock("-lh4-", 12, false), plain);
        byte[] badHeader = valid.clone(); badHeader[1] ^= 1;
        assertSelectedFailure(archive(badHeader));
        assertSelectedFailure(archive(entry("-lh4-", "page", matchBlock("-lh4-", 12, false), repeated('!', 3))));
        byte[] packed = matchBlock("-lh4-", 12, false);
        assertSelectedFailure(archive(entry("-lh4-", "page", Arrays.copyOf(packed, packed.length - 1), plain)));
        assertSelectedFailure(archive(entry("-lh4-", "page", literalBlock("-lh4-", 510, 1), plain)));
    }

    @Test public void amigaByteSplitsReachPublicListingSelectedBulkAndForwardRoutes() throws Exception {
        byte[] plain = repeated('A', 1024);
        byte[] complete = Files.readAllBytes(archive(entry("-lh4-", "page",
                literalBlock("-lh4-", 'A', plain.length), plain)).toPath());
        for (String extension : new String[]{".lha", ".lzh"}) {
            File folder = temporary.newFolder();
            File first = new File(folder, "comic" + extension);
            File middle = new File(folder, "comic.l01");
            File last = new File(folder, "comic.l02");
            Files.write(first.toPath(), Arrays.copyOf(complete, 2));
            Files.write(middle.toPath(), Arrays.copyOfRange(complete, 2, complete.length - 1));
            Files.write(last.toPath(), new byte[]{0});
            assertEquals("page", ArchiveSupport.listEntries(middle, null).get(0).path);
            File selected = temporary.newFile();
            assertTrue(ArchiveSupport.extractSingleEntry(last, "page", selected, null));
            assertArrayEquals(plain, Files.readAllBytes(selected.toPath()));
            File bulk = new File(folder, "output");
            assertTrue(ArchiveSupport.extractArchive(first, bulk, false, null));
            assertArrayEquals(plain, Files.readAllBytes(new File(bulk, "page").toPath()));
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(middle, null)) {
                assertNotNull(reader);
                assertEquals("page", reader.nextEntry().path);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[97];
                for (int count; (count = reader.read(buffer)) >= 0;) {
                    assertTrue(count > 0); output.write(buffer, 0, count);
                }
                assertArrayEquals(plain, output.toByteArray());
                assertNull(reader.nextEntry());
            }
        }
    }

    @Test public void independentArchivesCannotMasqueradeAsByteSplitVolumes() throws Exception {
        byte[] plain = new byte[]{42};
        byte[] complete = Files.readAllBytes(archive(entry("-lh0-", "page", plain, plain)).toPath());
        File folder = temporary.newFolder();
        File first = new File(folder, "comic.lha"), second = new File(folder, "comic.l01");
        Files.write(first.toPath(), complete); Files.write(second.toPath(), complete);
        assertThrows(IOException.class, () -> ArchiveSupport.listEntries(first, null));
        File selected = temporary.newFile(); Files.write(selected.toPath(), new byte[]{7});
        assertFalse(ArchiveSupport.extractSingleEntry(second, "page", selected, null));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(selected.toPath()));
        File output = new File(folder, "output");
        assertFalse(ArchiveSupport.extractArchive(first, output, false, null));
        assertFalse(output.exists());
    }

    @Test public void level3ByteSplitsReuseNativeRoutesAndKeepHeaderCrcValidation() throws Exception {
        byte[] plain = repeated('A', 1024);
        byte[] complete = Files.readAllBytes(archive(entryLevel3("-lh4-", "page",
                literalBlock("-lh4-", 'A', plain.length), plain)).toPath());
        File folder = temporary.newFolder();
        File first = new File(folder, "comic.lha"), second = new File(folder, "comic.l01");
        // Split inside the first four-byte extended-header length field.
        Files.write(first.toPath(), Arrays.copyOf(complete, 30));
        Files.write(second.toPath(), Arrays.copyOfRange(complete, 30, complete.length));
        assertEquals("page", ArchiveSupport.listEntries(second, null).get(0).path);
        File selected = temporary.newFile();
        assertTrue(ArchiveSupport.extractSingleEntry(second, "page", selected, null));
        assertArrayEquals(plain, Files.readAllBytes(selected.toPath()));
        File bulk = new File(folder, "output");
        assertTrue(ArchiveSupport.extractArchive(first, bulk, false, null));
        assertArrayEquals(plain, Files.readAllBytes(new File(bulk, "page").toPath()));
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(second, null)) {
            assertNotNull(reader);
            assertEquals("page", reader.nextEntry().path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[97];
            for (int count; (count = reader.read(buffer)) >= 0;) {
                assertTrue(count > 0); output.write(buffer, 0, count);
            }
            assertArrayEquals(plain, output.toByteArray());
            assertNull(reader.nextEntry());
        }
        complete[33] ^= 1; // Common extended-header CRC; framing itself is unchanged.
        Files.write(second.toPath(), Arrays.copyOfRange(complete, 30, complete.length));
        Files.write(selected.toPath(), new byte[]{7});
        assertFalse(ArchiveSupport.extractSingleEntry(second, "page", selected, null));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(selected.toPath()));
    }

    private void assertSelected(File archive, String path, byte[] expected) throws Exception {
        File output = temporary.newFile();
        assertTrue(LibarchiveNativeBridge.extractEntry(archive.getPath(), path, output.getPath(), null, null));
        assertArrayEquals(expected, Files.readAllBytes(output.toPath()));
    }

    private void assertSelectedFailure(File archive) throws Exception {
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> LibarchiveNativeBridge.extractEntry(
                archive.getPath(), "page", output.getPath(), null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private static byte[] drain(LibarchiveNativeBridge.ForwardStream reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[997];
        for (int count; (count = reader.read(buffer)) >= 0;) {
            assertTrue("Nonempty reads must make progress", count > 0);
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private File archive(byte[]... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] entry : entries) output.write(entry);
        output.write(0);
        File archive = temporary.newFile(); Files.write(archive.toPath(), output.toByteArray());
        return archive;
    }

    private static byte[] entry(String method, String name, byte[] packed, byte[] plain) throws IOException {
        return entry(method, name, packed, plain, new byte[0]);
    }

    private static byte[] entry(String method, String name, byte[] packed, byte[] plain,
                                byte[] extra) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        byte[] path = name.getBytes(StandardCharsets.US_ASCII);
        header.write(method.getBytes(StandardCharsets.US_ASCII));
        little(header, packed.length, 4); little(header, plain.length, 4);
        little(header, 0, 4); header.write(0x20); header.write(0);
        header.write(path.length); header.write(path);
        little(header, crc16(plain), 2);
        header.write(extra);
        byte[] fields = header.toByteArray();
        int checksum = 0; for (byte value : fields) checksum += value & 255;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(fields.length); output.write(checksum);
        output.write(fields); output.write(packed);
        return output.toByteArray();
    }

    private static void updateLevel0Checksum(byte[] entry) {
        int checksum = 0, end = (entry[0] & 255) + 2;
        for (int i = 2; i < end; i++) checksum += entry[i] & 255;
        entry[1] = (byte) checksum;
    }

    private static byte[] entryLevel3(String method, String name, byte[] packed, byte[] plain) throws IOException {
        byte[] path = name.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        little(header, 4, 2); header.write(method.getBytes(StandardCharsets.US_ASCII));
        little(header, packed.length, 4); little(header, plain.length, 4);
        little(header, 0, 4); header.write(0x20); header.write(3);
        little(header, crc16(plain), 2); header.write(0);
        little(header, 28 + 7 + 5 + path.length + 4, 4);
        little(header, 7, 4); header.write(0); little(header, 0, 2);
        little(header, 5 + path.length, 4); header.write(1); header.write(path);
        little(header, 0, 4);
        byte[] fields = header.toByteArray();
        int crc = crc16(fields); fields[33] = (byte) crc; fields[34] = (byte) (crc >>> 8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(fields); output.write(packed);
        return output.toByteArray();
    }

    private static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte value : bytes) {
            crc ^= value & 255;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xa001);
        }
        return crc;
    }

    private static void little(ByteArrayOutputStream output, int value, int bytes) {
        for (int i = 0; i < bytes; i++) output.write(value >>> (i * 8));
    }

    private static byte[] repeated(int value, int count) {
        byte[] result = new byte[count]; Arrays.fill(result, (byte)value); return result;
    }

    private static int positionBits(String method) {
        return method.equals("-lh6-") || method.equals("-lh7-") ? 5 : 4;
    }

    private static void fixedBlock(Bits bits, String method, int symbol, int count, int position) {
        bits.put(count, 16);
        bits.put(0, 5); bits.put(0, 5);
        bits.put(0, 9); bits.put(symbol, 9);
        bits.put(0, positionBits(method)); bits.put(position, positionBits(method));
    }

    private static byte[] literalBlock(String method, int symbol, int count) {
        Bits bits = new Bits(); fixedBlock(bits, method, symbol, count, 0); return bits.finish();
    }

    private static byte[] matchBlock(String method, int position, boolean dynamic) {
        Bits bits = new Bits();
        if (dynamic) {
            bits.put(1, 16);
            bits.put(0, 5); bits.put(0, 5);
            bits.put(0, 9); bits.put(256, 9); // One three-byte match.
            bits.put(position + 1, positionBits(method));
            for (int i = 0; i <= position; i++) bits.put(i == 0 || i == position ? 1 : 0, 3);
            bits.put(1, 1); // Select the larger-distance symbol.
        } else fixedBlock(bits, method, 256, 1, position);
        bits.put((1 << (position - 1)) - 1, position - 1);
        return bits.finish();
    }

    private static final class Bits {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int value, count;
        void put(int next, int width) {
            for (int i = width - 1; i >= 0; i--) {
                value = (value << 1) | ((next >>> i) & 1);
                if (++count == 8) { output.write(value); value = 0; count = 0; }
            }
        }
        byte[] finish() {
            if (count != 0) { output.write(value << (8 - count)); value = 0; count = 0; }
            return output.toByteArray();
        }
    }
}
