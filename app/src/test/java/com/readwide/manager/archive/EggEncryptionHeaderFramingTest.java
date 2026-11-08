package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EggEncryptionHeaderFramingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "test1234".toCharArray();

    @Test public void publicZipCryptoPrefixInclusiveSamplesExtract() throws Exception {
        File directory = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive(fixture("enc_zip20.egg")), directory, PASSWORD));
        check(new File(directory, "a.txt"), 3150, 0x7a553a9dL);
        check(new File(directory, "b.bin"), 512, 0x7735137bL);
        assertSelected(fixture("oversized_encrypt_zip20.egg"), "fix.txt", 44, 0x71fdb873L);
    }

    @Test public void zipCryptoCanonicalWideAndPaddedHeadersKeepTheirBoundaries() throws Exception {
        for (boolean wide : new boolean[]{false, true}) {
            byte[] bytes = fixture("oversized_encrypt_zip20.egg");
            int field = encryptionField(bytes);
            assertSelected(replacePrefix(bytes, field, wide, 17), "fix.txt", 44, 0x71fdb873L);
            int prefix = wide ? 9 : 7;
            byte[] inclusive = replacePrefix(bytes, field, wide, 17 + prefix);
            assertSelected(inclusive, "fix.txt", 44, 0x71fdb873L);
            int end = field + prefix + 17;
            byte[] padding = new byte[prefix];
            padding[0] = 0x22; padding[1] = (byte) 0x82; padding[2] = (byte) 0xe2; padding[3] = 8;
            byte[] padded = concat(Arrays.copyOf(inclusive, end), padding,
                    new byte[]{0x78,0x56,0x34,0x12,0,3,0,9,8,7},
                    Arrays.copyOfRange(inclusive, end, inclusive.length));
            assertSelected(padded, "fix.txt", 44, 0x71fdb873L);
        }
    }

    @Test public void malformedZipCryptoOvercountsDoNotScanForAnotherEnd() throws Exception {
        for (int delta : new int[]{-1, 1, 2}) {
            byte[] bytes = fixture("oversized_encrypt_zip20.egg");
            bytes[encryptionField(bytes) + 5] += delta;
            rejectsWithoutReplacing(bytes);
        }
        byte[] bytes = fixture("oversized_encrypt_zip20.egg");
        int end = encryptionField(bytes) + 7 + 17;
        bytes[end] ^= 1;
        rejectsWithoutReplacing(bytes);
        rejectsWithoutReplacing(Arrays.copyOf(bytes, end - 1));
    }

    @Test public void unchangedProducerAesBzip2ExtractsInBulk() throws Exception {
        File archive = archive(fixture("enc_lea_bzip2.egg"));
        File directory = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, PASSWORD));
        check(new File(directory, "text.txt"), 3600, 0x2c19aec0L);
    }

    @Test public void canonicalAesEncryptionSizeStillExtracts() throws Exception {
        byte[] bytes = fixture("enc_lea_bzip2.egg");
        int field = encryptionField(bytes);
        bytes[field + 5] = 29;
        assertSelected(bytes, "text.txt", 3600, 0x2c19aec0L);
    }

    @Test public void prefixInclusiveLeaSizesAcceptNarrowAndWideFields() throws Exception {
        for (String name : new String[]{"lea128.egg", "lea256.egg"}) {
            for (boolean wide : new boolean[]{false, true}) {
                byte[] bytes = fixture(name);
                int field = encryptionField(bytes), fixedSize = bytes[field + 5] & 255;
                bytes = replacePrefix(bytes, field, wide, fixedSize + (wide ? 9 : 7));
                assertSelected(bytes, "hello.txt", 131, 0x1b239eb9L);
            }
        }
    }

    @Test public void paddedCanonicalSizeWinsEvenWhenPaddingStartsWithEnd() throws Exception {
        for (boolean wide : new boolean[]{false, true}) {
            for (boolean opaqueNextField : new boolean[]{false, true}) {
                byte[] bytes = fixture("lea128.egg");
                int field = encryptionField(bytes), fixedSize = bytes[field + 5] & 255;
                int prefixSize = wide ? 9 : 7;
                bytes = replacePrefix(bytes, field, wide, fixedSize + prefixSize);
                int end = field + prefixSize + fixedSize;
                byte[] padding = new byte[prefixSize];
                padding[0] = 0x22; padding[1] = (byte)0x82;
                padding[2] = (byte)0xe2; padding[3] = 0x08;
                byte[] next = opaqueNextField
                        ? new byte[]{0x78,0x56,0x34,0x12,0,3,0,9,8,7} : new byte[0];
                bytes = concat(Arrays.copyOf(bytes, end), padding, next,
                        Arrays.copyOfRange(bytes, end, bytes.length));
                assertSelected(bytes, "hello.txt", 131, 0x1b239eb9L);
            }
        }
    }

    @Test public void otherOvercountsAndUnknownMethodsRemainRejected() throws Exception {
        for (int delta : new int[]{-1, 1}) {
            byte[] bytes = fixture("enc_lea_bzip2.egg");
            int field = encryptionField(bytes);
            bytes[field + 5] += delta;
            rejectsWithoutReplacing(bytes);
        }
        byte[] bytes = fixture("enc_lea_bzip2.egg");
        bytes[encryptionField(bytes) + 7] = 7;
        rejectsWithoutReplacing(bytes);
    }

    @Test public void changedCandidateEndDoesNotResynchronize() throws Exception {
        byte[] bytes = fixture("enc_lea_bzip2.egg");
        int field = encryptionField(bytes);
        bytes[field + 7 + 29] ^= 1;
        rejectsWithoutReplacing(bytes);
    }

    @Test public void truncatedDeclaredExtentAndMissingFooterRemainRejected() throws Exception {
        byte[] bytes = fixture("enc_lea_bzip2.egg");
        int field = encryptionField(bytes), payload = field + 7;
        rejectsWithoutReplacing(Arrays.copyOf(bytes, payload + 29 + 4));
        rejectsWithoutReplacing(concat(Arrays.copyOf(bytes, payload + 19),
                Arrays.copyOfRange(bytes, payload + 29, bytes.length)));
    }

    @Test public void derivedSplitCutsPreservePrefixInclusiveEncryptionFraming() throws Exception {
        byte[] bytes = fixture("enc_lea_bzip2.egg");
        int field = encryptionField(bytes);
        // These synthetic cuts exercise logical input, not producer split rules.
        for (int cut : new int[]{field + 5, field + 8, field + 7 + 29 + 2, field + 7 + 36}) {
            File directory = temp.newFolder();
            File first = new File(directory, "fixture.vol1.egg");
            File second = new File(directory, "fixture.vol2.egg");
            Files.write(first.toPath(), concat(Round4Fixtures.prefix(1001, 0, 1002, true),
                    Arrays.copyOfRange(bytes, 18, cut)));
            Files.write(second.toPath(), concat(Round4Fixtures.prefix(1002, 1001, 0, true),
                    Arrays.copyOfRange(bytes, cut, bytes.length)));
            for (File selected : new File[]{first, second}) {
                File output = temp.newFile();
                assertTrue(EggArchiveReader.extractSingleEntry(selected, "text.txt", output, PASSWORD));
                check(output, 3600, 0x2c19aec0L);
            }
        }
    }

    @Test public void headerTailLookaheadPropagatesCancellationAndSourceChanges() throws Exception {
        byte[] bytes = fixture("enc_lea_bzip2.egg");
        File archive = archive(bytes);
        Method tail = EggArchiveReader.class.getDeclaredMethod("hasFileHeaderTail",
                SplitVolumeInput.class, long.class, long.class);
        tail.setAccessible(true);
        try (SplitVolumeInput input = new SplitVolumeInput(Collections.singletonList(
                new SplitVolumeInput.Segment(archive, 0, bytes.length)))) {
            Thread.currentThread().interrupt();
            try {
                InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                        () -> tail.invoke(null, input, 101L, (long)bytes.length));
                assertTrue(failure.getCause() instanceof IOException);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
        try (SplitVolumeInput input = new SplitVolumeInput(Collections.singletonList(
                new SplitVolumeInput.Segment(archive, 0, bytes.length)))) {
            long changed = archive.lastModified() + 5000;
            assertTrue(archive.setLastModified(changed));
            assertEquals(changed, archive.lastModified());
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> tail.invoke(null, input, 101L, (long)bytes.length));
            assertTrue(failure.getCause() instanceof IOException);
        }
    }

    private void assertSelected(byte[] bytes, String path, long size, long crc) throws Exception {
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive(bytes), path, output, PASSWORD));
        check(output, size, crc);
    }

    private void rejectsWithoutReplacing(byte[] bytes) throws Exception {
        File archive = archive(bytes), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () ->
                EggArchiveReader.extractSingleEntry(archive, "text.txt", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    private File archive(byte[] bytes) throws Exception {
        File file = new File(temp.newFolder(), "fixture.egg");
        Files.write(file.toPath(), bytes);
        return file;
    }

    private byte[] fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/archives/egg-lea/" + name + ".base64")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return Base64.getMimeDecoder().decode(new String(bytes.toByteArray(), StandardCharsets.US_ASCII));
        }
    }

    private static int encryptionField(byte[] bytes) {
        for (int i = 0; i <= bytes.length - 7; i++) {
            if (bytes[i] == 15 && bytes[i + 1] == 71 && bytes[i + 2] == (byte)209 && bytes[i + 3] == 8) return i;
        }
        throw new AssertionError("Missing encryption field");
    }

    private static byte[] replacePrefix(byte[] bytes, int field, boolean wide, int size) throws IOException {
        byte[] prefix = wide ? new byte[]{1,(byte)size,0,0,0} : new byte[]{0,(byte)size,0};
        return concat(Arrays.copyOf(bytes, field + 4), prefix,
                Arrays.copyOfRange(bytes, field + 7, bytes.length));
    }

    private static byte[] concat(byte[]... pieces) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] piece : pieces) bytes.write(piece);
        return bytes.toByteArray();
    }

    private static void check(File file, long size, long expectedCrc) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertEquals(size, bytes.length);
        CRC32 crc = new CRC32(); crc.update(bytes);
        assertEquals(expectedCrc, crc.getValue());
    }
}
