package com.textview.reader.archive;

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

public class RarFixtureReportTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void generate_reportsStoredSplitCandidateAndDeduplicatesLaterVolume() throws Exception {
        byte[] payload = "stored-split-report".getBytes(StandardCharsets.UTF_8);
        int cut = 7;
        File first = new File(tempFolder.getRoot(), "demo.rar");
        File later = new File(tempFolder.getRoot(), "demo.r00");
        writeRar4Volume(first,
                "file.txt",
                copyOfRange(payload, 0, cut),
                RAR4_FILE_SPLIT_AFTER,
                crc32(copyOfRange(payload, 0, cut)),
                cut);
        writeRar4Volume(later,
                "file.txt",
                copyOfRange(payload, cut, payload.length),
                RAR4_FILE_SPLIT_BEFORE,
                crc32(payload),
                payload.length);

        RarFixtureReport report = RarFixtureReport.generate(tempFolder.getRoot(), null, 1);

        assertEquals(1, report.rows().size());
        assertEquals(1, report.readableCount());
        assertEquals(1, report.supportedStoredSplitCount());
        assertEquals(0, report.chainInvalidCount());
        assertTrue(report.toMarkdown().contains("demo.rar"));
        assertTrue(report.toMarkdown().contains("Backend route"));
        assertTrue(report.toMarkdown().contains("TRY_FIRST_PARTY_STORED_SPLIT"));
        assertTrue(report.toMarkdown().contains("Non-solid compatibility"));
        assertTrue(report.toMarkdown().contains("FIRST_PARTY_STORED_SPLIT"));
        assertTrue(report.toMarkdown().contains("first-party stored split candidate"));
    }

    @Test
    public void generate_reportsNonSolidClassicLzRouteSeparatelyFromStoredSplit() throws Exception {
        File archive = new File(tempFolder.getRoot(), "classic-lz.rar");
        writeRar4Volume(archive,
                "classic.txt",
                new byte[] {0x11, 0x22, 0x33, 0x44},
                0,
                crc32(new byte[] {0x11, 0x22, 0x33, 0x44}),
                4L,
                0x33);

        RarFixtureReport report = RarFixtureReport.generate(tempFolder.getRoot(), null, 1);

        assertEquals(1, report.rows().size());
        assertEquals(1, report.readableCount());
        assertEquals(1, report.firstPartyRoutedEntryCount());
        assertEquals(0, report.supportedStoredSplitCount());
        assertTrue(report.toMarkdown().contains("TRY_FIRST_PARTY_CLASSIC_LZ_NON_SOLID"));
        assertTrue(report.toMarkdown().contains("LIMITED_CLASSIC_LZ_FALLBACK"));
        assertTrue(report.toMarkdown().contains("backend route counts"));
        assertTrue(report.toMarkdown().contains("non-solid compatibility counts"));
        assertTrue(report.toMarkdown().contains("PPMd block probe"));
        assertTrue(report.toMarkdown().contains("classicLz=1"));
    }


    @Test
    public void generate_reportsVisiblePpmdPayloadForDecoderWork() throws Exception {
        File archive = new File(tempFolder.getRoot(), "ppmd.rar");
        writeRar4Volume(archive,
                "ppmd.txt",
                new byte[] {(byte) 0x80, 0x00, 0x11, 0x22},
                0,
                crc32(new byte[] {(byte) 0x80, 0x00, 0x11, 0x22}),
                4L,
                0x33);

        RarFixtureReport report = RarFixtureReport.generate(tempFolder.getRoot(), null, 1);
        String markdown = report.toMarkdown();

        assertEquals(1, report.rows().size());
        assertTrue(markdown.contains("PPMd block probe"));
        assertTrue(markdown.contains("ppmd=1"));
        assertTrue(markdown.contains("rawFlags=0x8000"));
    }

    @Test
    public void generate_reportsBrokenNewStyleChainBeforeParsing() throws Exception {
        File first = new File(tempFolder.getRoot(), "broken.part001.rar");
        File later = new File(tempFolder.getRoot(), "broken.part003.rar");
        writeRar4Volume(first,
                "file.txt",
                new byte[] {1, 2},
                RAR4_FILE_SPLIT_AFTER,
                crc32(new byte[] {1, 2}),
                2L);
        writeRar4Volume(later,
                "file.txt",
                new byte[] {3, 4},
                RAR4_FILE_SPLIT_BEFORE,
                crc32(new byte[] {1, 2, 3, 4}),
                4L);

        RarFixtureReport report = RarFixtureReport.generate(tempFolder.getRoot(), null, 1);

        assertEquals(1, report.rows().size());
        assertEquals(RarFixtureReport.Status.CHAIN_INVALID, report.rows().get(0).status);
        assertEquals(1, report.chainInvalidCount());
        assertTrue(report.toMarkdown().contains("RAR split volume chain is incomplete"));
    }

    @Test
    public void generate_ignoresPlainNonRarFiles() throws Exception {
        File text = tempFolder.newFile("not-rar.txt");
        try (FileOutputStream out = new FileOutputStream(text)) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }

        RarFixtureReport report = RarFixtureReport.generate(tempFolder.getRoot(), null, 1);

        assertEquals(0, report.rows().size());
        assertTrue(report.toMarkdown().contains("total candidates: 0"));
    }

    private static final int RAR4_FILE_SPLIT_BEFORE = 0x0001;
    private static final int RAR4_FILE_SPLIT_AFTER = 0x0002;

    private static void writeRar4Volume(File file,
                                        String entryName,
                                        byte[] payload,
                                        int fileFlags,
                                        long crc,
                                        long unpackedSize) throws Exception {
        writeRar4Volume(file, entryName, payload, fileFlags, crc, unpackedSize, 0x30);
    }

    private static void writeRar4Volume(File file,
                                        String entryName,
                                        byte[] payload,
                                        int fileFlags,
                                        long crc,
                                        long unpackedSize,
                                        int method) throws Exception {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));
            byte[] body = bytes(
                    uint32(payload.length),
                    uint32(unpackedSize),
                    new byte[] {1},
                    uint32(crc),
                    uint32(0),
                    new byte[] {29},
                    new byte[] {(byte) method},
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

    private static byte[] copyOfRange(byte[] src, int start, int end) {
        byte[] out = new byte[Math.max(0, end - start)];
        System.arraycopy(src, start, out, 0, out.length);
        return out;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
