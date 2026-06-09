package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

public class RarSplitStoredExtractorTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void extractPlainStoredSplitConcatenatesAndCommitsAfterCrc() throws Exception {
        byte[] expected = new byte[] {'h', 'e', 'l', 'l', 'o'};
        File part1 = writeFile("part1.rar", new byte[] {'h', 'e'});
        File part2 = writeFile("part2.rar", new byte[] {'l', 'l', 'o'});
        RarArchiveReader.RarEntry first = entry(2, false, true, crc(expected));
        RarArchiveReader.RarEntry last = entry(3, true, false, crc(expected));
        first.sourceArchive = part1;
        last.sourceArchive = part2;
        File out = new File(tempFolder.getRoot(), "out.bin");

        RarSplitStoredExtractor.extract(first, out, null, Arrays.asList(first, last), null);

        assertTrue(out.exists());
        assertBytes(expected, readAll(out));
    }

    @Test
    public void extractPlainStoredSplitDeletesPartialOutputOnCrcFailure() throws Exception {
        File part1 = writeFile("bad1.rar", new byte[] {'b', 'a'});
        File part2 = writeFile("bad2.rar", new byte[] {'d'});
        RarArchiveReader.RarEntry first = entry(2, false, true, 0x12345678L);
        RarArchiveReader.RarEntry last = entry(1, true, false, 0x12345678L);
        first.sourceArchive = part1;
        last.sourceArchive = part2;
        File out = new File(tempFolder.getRoot(), "bad-out.bin");

        try {
            RarSplitStoredExtractor.extract(first, out, null, Arrays.asList(first, last), null);
        } catch (java.io.IOException expected) {
            assertTrue(expected instanceof RarSplitStoredFailure.ExtractionException);
            assertFalse(out.exists());
            return;
        }
        throw new AssertionError("CRC mismatch must fail");
    }

    private File writeFile(String name, byte[] bytes) throws Exception {
        File file = tempFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private static RarArchiveReader.RarEntry entry(long packedSize,
                                                   boolean splitBefore,
                                                   boolean splitAfter,
                                                   long crc) {
        return new RarArchiveReader.RarEntry(
                "file.bin",
                false,
                5L,
                packedSize,
                0L,
                4,
                0x30,
                false,
                splitBefore,
                splitAfter,
                null,
                crc,
                0L);
    }

    private static long crc(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue() & 0xffffffffL;
    }

    private static byte[] readAll(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return data;
    }

    private static void assertBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals((long) expected[i], (long) actual[i]);
        }
    }
}
