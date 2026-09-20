package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.ARM64Options;
import org.tukaani.xz.RISCVOptions;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.LZMA2Options;

/** Self-made containers use bundled encoders; producer-generated fixtures remain necessary. */
public class SevenZModernBcjArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "modern-bcj".toCharArray();

    @Test public void modernFiltersUsePublicSingleAndBulkExtraction() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            byte[] raw = payload(method);
            File archive = fixture(method, true, false, false, false, false);
            assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
            assertFalse(SevenZBcj2ArchiveReader.archiveUsesBcj2(archive, null));
            assertEquals(3, ArchiveSupport.listEntries(archive, null).size());
            File single = temp.newFile();
            assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "2.bin", single, null).success);
            assertArrayEquals(Arrays.copyOfRange(raw, 16, 32), Files.readAllBytes(single.toPath()));
            File target = temp.newFolder();
            assertTrue(ArchiveSupport.extractArchive(archive, target, true, null));
            assertArrayEquals(Arrays.copyOfRange(raw, 0, 16), Files.readAllBytes(new File(target, "1.bin").toPath()));
            assertArrayEquals(Arrays.copyOfRange(raw, 16, 32), Files.readAllBytes(new File(target, "2.bin").toPath()));
            assertTrue(new File(target, "empty.bin").isFile());
            assertEquals(0, new File(target, "empty.bin").length());
        }
    }

    @Test public void encryptedModernFiltersUsePublicVerifiedForwardReader() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            File archive = fixture(method, true, true, false, false, false);
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, PASSWORD, spool)) {
                assertForward(reader, payload(method));
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void splitModernFiltersKeepVerifiedForwardReading() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            byte[] bytes = Files.readAllBytes(fixture(method, true, true, false, false, false).toPath());
            File directory = temp.newFolder();
            File[] parts = new File[3];
            int[] cuts = {0, 20, bytes.length / 2, bytes.length};
            for (int i = 0; i < parts.length; i++) {
                parts[i] = new File(directory, "comic.cb7.00" + (i + 1));
                Files.write(parts[i].toPath(), Arrays.copyOfRange(bytes, cuts[i], cuts[i + 1]));
            }
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(parts[1], PASSWORD, spool)) {
                assertForward(reader, payload(method));
            }
            assertEquals(0, spool.list().length);
            for (File part : parts) assertTrue("Split handle must close", part.delete());
        }
    }

    @Test public void headerOnlyModernFilterKeepsSupplementalRoute() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            // File data uses LZMA2/AES only; the modern filter exists solely in the encoded header.
            File archive = fixture(method, false, true, true, false, false);
            assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, PASSWORD));
            File out = temp.newFile();
            assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "1.bin", out, PASSWORD).success);
            assertArrayEquals(Arrays.copyOfRange(payload(method), 0, 16), Files.readAllBytes(out.toPath()));
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, PASSWORD, spool)) {
                assertForward(reader, payload(method));
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void corruptModernFolderFailsBeforeForwardBytes() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            File archive = fixture(method, true, false, false, true, false);
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
                assertNotNull(reader);
                assertEquals("1.bin", reader.nextEntry().path);
                byte[] untouched = {42};
                try { reader.read(untouched); fail("Bad folder CRC"); }
                catch (SevenZBcj2ArchiveReader.IntegrityException expected) { }
                assertEquals(42, untouched[0]);
                assertEquals(0, spool.list().length);
                try { reader.nextEntry(); fail("Failed session must retire"); }
                catch (IOException expected) { }
            }
        }
    }

    @Test public void wrongModernFilterPasswordPreservesExistingTarget() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            File archive = fixture(method, true, true, false, false, false);
            File target = temp.newFile();
            Files.write(target.toPath(), new byte[]{42});
            try {
                SevenZBcj2ArchiveReader.extractSingleEntry(archive, "1.bin", target, "wrong".toCharArray());
                fail("Wrong password must fail decoding or CRC");
            } catch (IOException expected) { }
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.toPath()));
        }
    }

    @Test public void specialSevenZDirectoryCollisionPreservesExistingFile() throws Exception {
        File archive = fixture(0x0a, true, false, false, false, true);
        File target = temp.newFolder();
        File occupied = new File(target, "occupied");
        Files.write(occupied.toPath(), new byte[]{42});
        try {
            SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null);
            fail("A file cannot satisfy a directory entry");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("directory")); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(occupied.toPath()));
        assertFalse(new File(target, "1.bin").exists());
        File valid = temp.newFolder();
        assertTrue(new File(valid, "occupied").mkdir());
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, valid, null, null, null));
    }

    @Test public void ordinaryLzma2StillDeclinesSupplementalForwardRoute() throws Exception {
        File archive = fixture(0x0a, false, false, false, false, false);
        assertFalse(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        File spool = temp.newFolder();
        assertNull(SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool));
        assertEquals(0, spool.list().length);
    }

    @Test public void hugeDictionaryDeclarationWorksInEncryptedAndPlainModernFolders() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            for (boolean aes : new boolean[]{false, true}) {
                File archive = fixture(method, true, aes, false, false, false, true);
                File output = temp.newFile();
                assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "2.bin", output, aes ? PASSWORD : null).success);
                assertArrayEquals(Arrays.copyOfRange(payload(method), 16, 32), Files.readAllBytes(output.toPath()));
                File spool = temp.newFolder();
                try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, aes ? PASSWORD : null, spool)) {
                    assertForward(reader, payload(method));
                }
                assertEquals(0, spool.list().length);
            }
        }
    }

    @Test public void hugeDictionaryDeclarationAlsoWorksInEncodedHeaders() throws Exception {
        File archive = fixture(0x0a, false, true, true, false, false, true);
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, PASSWORD));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, PASSWORD, spool)) {
            assertForward(reader, payload(0x0a));
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void emptyStreamFileCanBeExtractedIndividually() throws Exception {
        File archive = fixture(0x0a, true, false, false, false, false);
        File target = temp.newFile();
        Files.write(target.toPath(), new byte[]{42});
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "empty.bin", target, null));
        assertTrue(target.isFile());
        assertEquals(0, target.length());
        File directory = temp.newFolder();
        File marker = new File(directory, "keep"); Files.write(marker.toPath(), new byte[]{42});
        try {
            SevenZBcj2ArchiveReader.extractSingleEntry(archive, "empty.bin", directory, null);
            fail("An empty file must not replace a directory");
        } catch (IOException expected) { }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(marker.toPath()));
    }

    @Test public void zeroByteFolderStillChecksCrcBeforeCommitting() throws Exception {
        for (boolean bad : new boolean[]{false, true}) {
            Graph graph = graph(new byte[0], 0x0a, false);
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write(1); header.write(4); streams(header, graph, 0, false, bad);
            header.write(5); number(header, 1);
            byte[] name = "empty.bin\0".getBytes(StandardCharsets.UTF_16LE);
            header.write(17); number(header, name.length + 1); header.write(0); header.write(name);
            header.write(0); header.write(0);
            File archive = writeArchive(graph.packed, header.toByteArray());
            File target = temp.newFolder();
            File output = new File(target, "empty.bin"); Files.write(output.toPath(), new byte[]{42});
            try {
                assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null));
                if (bad) fail("Zero-byte folder CRC must not be skipped");
                assertTrue(output.isFile()); assertEquals(0, output.length());
            } catch (SevenZBcj2ArchiveReader.IntegrityException expected) {
                if (!bad) throw expected;
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
            }
        }
    }

    private static void assertForward(ArchiveSupport.ForwardArchiveReader reader, byte[] raw) throws Exception {
        assertNotNull(reader);
        for (int i = 0; i < 2; i++) {
            assertEquals((i + 1) + ".bin", reader.nextEntry().path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[3];
            int n;
            while ((n = reader.read(buffer)) != -1) output.write(buffer, 0, n);
            assertArrayEquals(Arrays.copyOfRange(raw, i * 16, (i + 1) * 16), output.toByteArray());
        }
        assertEquals("empty.bin", reader.nextEntry().path);
        assertEquals(-1, reader.read(new byte[1]));
        assertNull(reader.nextEntry());
    }

    private File fixture(int method, boolean modernData, boolean aes, boolean encodedHeader,
                         boolean badCrc, boolean directory) throws Exception {
        return fixture(method, modernData, aes, encodedHeader, badCrc, directory, false);
    }

    private File fixture(int method, boolean modernData, boolean aes, boolean encodedHeader,
                         boolean badCrc, boolean directory, boolean hugeDictionary) throws Exception {
        byte[] raw = payload(method);
        Graph data = graph(raw, modernData ? method : 0, aes);
        if (hugeDictionary) data.props.set(aes ? 1 : 0, new byte[]{40});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); // Header, MainStreamsInfo
        streams(header, data, 0, true, badCrc);
        header.write(5); number(header, directory ? 4 : 3); // FilesInfo
        header.write(14); number(header, 1); header.write(directory ? 0x90 : 0x20); // Empty streams.
        header.write(15); number(header, 1); header.write(directory ? 0x40 : 0x80); // Last is an empty file, not directory.
        byte[] names = ((directory ? "occupied\0" : "") + "1.bin\0" + "2.bin\0" + "empty.bin\0").getBytes(StandardCharsets.UTF_16LE);
        header.write(17); number(header, names.length + 1); header.write(0); header.write(names);
        header.write(0); header.write(0);
        byte[] next = header.toByteArray();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(data.packed);
        if (encodedHeader) {
            Graph encoded = graph(next, method, aes);
            if (hugeDictionary) encoded.props.set(aes ? 1 : 0, new byte[]{40});
            ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
            descriptor.write(23); streams(descriptor, encoded, packed.size(), false, false);
            packed.write(encoded.packed);
            next = descriptor.toByteArray();
        }
        return writeArchive(packed.toByteArray(), next);
    }

    private File writeArchive(byte[] packed, byte[] next) throws Exception {
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        little(start, packed.length, 8); little(start, next.length, 8); little(start, crc(next), 4);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});
        little(archive, crc(start.toByteArray()), 4); archive.write(start.toByteArray());
        archive.write(packed); archive.write(next);
        File result = new File(temp.newFolder(), "modern.7z");
        Files.write(result.toPath(), archive.toByteArray());
        return result;
    }

    private static Graph graph(byte[] raw, int method, boolean aes) throws Exception {
        byte[] filtered = raw;
        if (method != 0) {
            FilterOptions options;
            if (method == 0x0a) { ARM64Options arm = new ARM64Options(); arm.setStartOffset(256); options = arm; }
            else { RISCVOptions riscv = new RISCVOptions(); riscv.setStartOffset(256); options = riscv; }
            filtered = encode(options, raw);
        }
        LZMA2Options lzma = new LZMA2Options(1);
        lzma.setDictSize(1 << 20);
        byte[] compressed = encode(lzma, filtered);
        Graph graph = new Graph(raw, compressed);
        if (aes) {
            byte[] key = Arrays.copyOf(new String(PASSWORD).getBytes(StandardCharsets.UTF_16LE), 32);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            graph.packed = cipher.doFinal(Arrays.copyOf(compressed, ((compressed.length + 15) / 16) * 16));
            graph.add(new byte[]{6,(byte)0xf1,7,1}, new byte[]{0x3f,0}, compressed.length);
        }
        graph.add(new byte[]{0x21}, new byte[]{16}, filtered.length);
        if (method != 0) graph.add(new byte[]{(byte)method}, new byte[]{0,1,0,0}, raw.length);
        return graph;
    }

    private static byte[] encode(FilterOptions options, byte[] raw) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FinishableOutputStream output = options.getOutputStream(new FinishableWrapperOutputStream(bytes))) {
            output.write(raw);
        }
        return bytes.toByteArray();
    }

    private static void streams(ByteArrayOutputStream out, Graph graph, long packPos,
                                boolean twoFiles, boolean badCrc) throws Exception {
        out.write(6); number(out, packPos); number(out, 1); out.write(9); number(out, graph.packed.length);
        out.write(10); out.write(1); little(out, crc(graph.packed), 4); out.write(0);
        out.write(7); out.write(11); number(out, 1); out.write(0); number(out, graph.ids.size());
        for (int i = 0; i < graph.ids.size(); i++) {
            byte[] id = graph.ids.get(i), props = graph.props.get(i);
            out.write(id.length | 0x20); out.write(id); number(out, props.length); out.write(props);
        }
        for (int i = 0; i + 1 < graph.ids.size(); i++) { number(out, i + 1); number(out, i); }
        out.write(12);
        for (long size : graph.sizes) number(out, size);
        out.write(10); out.write(1); little(out, crc(graph.raw) ^ (badCrc ? 1 : 0), 4); out.write(0);
        if (twoFiles) {
            out.write(8); out.write(13); number(out, 2); out.write(9); number(out, 16);
            out.write(10); out.write(1);
            little(out, crc(Arrays.copyOfRange(graph.raw, 0, 16)), 4);
            little(out, crc(Arrays.copyOfRange(graph.raw, 16, 32)), 4);
            out.write(0);
        }
        out.write(0);
    }

    private static byte[] payload(int method) {
        byte[] bytes = new byte[32];
        for (int offset = 0; offset < bytes.length; offset += 4) {
            int word = method == 0x0a ? 0x94000001 : 0x000000ef;
            for (int b = 0; b < 4; b++) bytes[offset + b] = (byte)(word >>> (8 * b));
        }
        return bytes;
    }

    private static void number(ByteArrayOutputStream out, long value) {
        // The format permits the full-width form for any nonnegative value.
        out.write(0xff); little(out, value, 8);
    }
    private static void little(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int)(value >>> (8 * i)) & 255);
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }

    private static final class Graph {
        final byte[] raw;
        byte[] packed;
        final List<byte[]> ids = new ArrayList<>(), props = new ArrayList<>();
        final List<Long> sizes = new ArrayList<>();
        Graph(byte[] raw, byte[] packed) { this.raw = raw; this.packed = packed; }
        void add(byte[] id, byte[] properties, long size) { ids.add(id); props.add(properties); sizes.add(size); }
    }
}
