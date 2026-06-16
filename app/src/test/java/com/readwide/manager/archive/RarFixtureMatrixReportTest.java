package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public class RarFixtureMatrixReportTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void generate_reportsListingAndFirstFileExtraction() throws Exception {
        File archive = new File(tempFolder.getRoot(), "matrix.rar");
        byte[] payload = "rar-matrix-fixture".getBytes(StandardCharsets.UTF_8);
        writeRar4StoredVolume(archive, "file.txt", payload);

        File probes = tempFolder.newFolder("probes");
        RarFixtureMatrixReport report = RarFixtureMatrixReport.generate(tempFolder.getRoot(), null, 1, probes);
        String markdown = report.toMarkdown();

        assertEquals(1, report.rows().size());
        assertEquals(1, report.listedCount());
        assertEquals(1, report.firstFileOkCount());
        assertTrue(markdown.contains("RAR real-fixture extraction matrix"));
        assertTrue(markdown.contains("matrix.rar"));
        assertTrue(markdown.contains("OK file.txt"));
    }

    @Test
    public void generate_reportsBrokenChainWithoutExtractionProbe() throws Exception {
        File first = new File(tempFolder.getRoot(), "broken.part001.rar");
        File later = new File(tempFolder.getRoot(), "broken.part003.rar");
        writeRar4StoredVolume(first, "file.txt", new byte[] {1, 2}, 0x0002);
        writeRar4StoredVolume(later, "file.txt", new byte[] {3, 4}, 0x0001);

        RarFixtureMatrixReport report = RarFixtureMatrixReport.generate(
                tempFolder.getRoot(), null, 1, tempFolder.newFolder("broken-probes"));

        assertEquals(1, report.rows().size());
        assertEquals(RarFixtureMatrixReport.ListStatus.CHAIN_INVALID, report.rows().get(0).listStatus);
        assertTrue(report.toMarkdown().contains("CHAIN_INVALID"));
    }

    private static void writeRar4StoredVolume(File file,
                                              String entryName,
                                              byte[] payload) throws Exception {
        writeRar4StoredVolume(file, entryName, payload, 0);
    }

    private static void writeRar4StoredVolume(File file,
                                              String entryName,
                                              byte[] payload,
                                              int fileFlags) throws Exception {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));
            byte[] body = bytes(
                    uint32(payload.length),
                    uint32(payload.length),
                    new byte[] {1},
                    uint32(crc32(payload)),
                    uint32(0),
                    new byte[] {29},
                    new byte[] {0x30},
                    uint16(rawName.length),
                    uint32(0),
                    rawName);
            out.write(rar4Header(0x74, 0x8000 | fileFlags, body));
            out.write(payload);
            out.write(rar4Header(0x7b, 0, new byte[0]));
        }
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) throws Exception {
        int size = 7 + body.length;
        return bytes(uint16(0), new byte[] {(byte) type}, uint16(flags), uint16(size), body);
    }

    private static byte[] bytes(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }

    private static byte[] uint16(int value) {
        return new byte[] {(byte) value, (byte) (value >>> 8)};
    }

    private static byte[] uint32(long value) {
        return new byte[] {
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
